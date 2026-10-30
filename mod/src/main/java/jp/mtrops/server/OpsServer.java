package jp.mtrops.server;

import io.netty.buffer.Unpooled;
import jp.mtrops.ConfigLoader;
import jp.mtrops.Net;
import jp.mtrops.adapter.MtrAdapter;
import jp.mtrops.adapter.ScheduleAdapter;
import jp.mtrops.core.AnomalyDetector;
import jp.mtrops.core.IdealRun;
import jp.mtrops.core.EtaCodec;
import jp.mtrops.core.EtaPlanner;
import jp.mtrops.core.ScheduleSync;
import jp.mtrops.core.TrainView;
import jp.mtrops.core.ElapsedTracker;
import jp.mtrops.core.FormationAssigner;
import jp.mtrops.core.Geometry;
import jp.mtrops.core.GeometryCodec;
import jp.mtrops.core.OpsConfig;
import jp.mtrops.core.OpsRegistry;
import jp.mtrops.core.RegistryCodec;
import jp.mtrops.core.Snapshot;
import jp.mtrops.core.SnapshotCodec;
import jp.mtrops.core.Topology;
import jp.mtrops.core.TopologyCodec;
import jp.mtrops.core.TrainSample;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * サーバー側の本体(設計書 §2/§3: 車両・車番情報の正本はサーバー)。
 *
 *  ・MTRの列車を一定間隔でサンプリング(Adapter経由)
 *  ・車庫台帳(OpsRegistry)に従って編成番号を割り当て、ワールドへ保存
 *  ・異常検知(AnomalyDetector)
 *  ・購読中のクライアントへ スナップショット / トポロジー / 台帳 を配信
 *  ・クライアントからの「1車庫分の更新」を検証して反映(全車庫丸ごと上書きは受け付けない)
 *
 * すべてサーバースレッドで動かす。ネットワークスレッドで届いた要求は server.execute() で持ち込む。
 */
public final class OpsServer {

	private static final Logger LOGGER = LoggerFactory.getLogger("mtrops/server");
	private static OpsServer instance;

	public static OpsServer get() {
		return instance;
	}

	public static void start(MinecraftServer server) {
		final OpsServer s = new OpsServer(server);
		instance = s;
		s.adapterOk = MtrAdapter.selfCheck();
		if (!s.adapterOk) {
			LOGGER.error("MTR の列車情報にアクセスできないため、運行監視を無効化します。");
		} else {
			LOGGER.info("mtrops 起動: サンプリング間隔 {} tick / 1パケット上限 {} bytes", s.cfg.sampleTicks, s.cfg.maxSnapshotBytes);
		}
	}

	public static void stop() {
		if (instance != null) {
			instance.saved.setDirty();
		}
		instance = null;
	}

	private final MinecraftServer server;
	private final OpsConfig cfg;
	private final OpsSavedData saved;
	private final OpsRegistry registry;
	private final FormationAssigner assigner;
	private final ElapsedTracker elapsed = new ElapsedTracker();
	private AnomalyDetector detector;
	private final Set<UUID> subscribers = new HashSet<>();

	private boolean adapterOk;
	private boolean hadSubscribers;
	private long tickCount;
	private int sampleCounter;
	private int seq;
	private int lastLevel = SnapshotCodec.MAX_LEVEL;
	private int topologyRev;
	private int topologyContentHash;
	private byte[] topologyBytes;
	private final Set<Long> scannedTrains = new HashSet<>();
	private final java.util.Map<String, Geometry.Section> sectionMap = new java.util.LinkedHashMap<>();
	private List<byte[]> geometryParts = new ArrayList<>();
	private int geometryRev;
	private byte[] scheduleBytes;
	private int etaSeq;
	private long lastSectionsSave;

	/** 列車ごとの「最後に着いた停車駅」(駅間の所要時間の実績を取るため) */
	private static final class StopTrack {
		long station;
		long arriveTick;
		/** その駅に着いた時点での、次の停車駅とMTRの理論上の所要時間(tick) */
		long nextStation;
		double nextIdeal = Double.NaN;
	}

	private final java.util.Map<Long, StopTrack> stopTracks = new java.util.HashMap<>();

	private OpsServer(MinecraftServer server) {
		this.server = server;
		this.cfg = ConfigLoader.load();
		this.saved = OpsSavedData.get(server);
		this.registry = saved.registry;
		this.registry.skipEndings = new java.util.HashSet<>(cfg.skipNumberEndings);
		this.assigner = new FormationAssigner(registry);
		this.detector = new AnomalyDetector(cfg);
	}

	public OpsConfig config() {
		return cfg;
	}

	// ------------------------------------------------------------------ 購読

	public void subscribe(ServerPlayer player, int protocol) {
		if (protocol != Net.PROTOCOL) {
			LOGGER.warn("{} のmtrops通信プロトコルが一致しません (client={}, server={})", player.getGameProfile().getName(), protocol, Net.PROTOCOL);
			return;
		}
		subscribers.add(player.getUUID());
		if (topologyBytes == null) {
			refreshTopology(true);
		}
		send(player, Net.TOPOLOGY, topologyBytes);
		send(player, Net.REGISTRY, RegistryCodec.encodeRegistry(registry, false));
		for (final byte[] part : geometryParts) {
			send(player, Net.GEOMETRY, part);
		}
		if (adapterOk) {
			if (scheduleBytes == null) {
				try {
					refreshSchedule();
				} catch (RuntimeException | LinkageError e) {
					LOGGER.warn("時刻表データの作成に失敗: {}", e.toString());
				}
			} else {
				send(player, Net.SCHEDULE, scheduleBytes);
			}
		}
	}

	public void unsubscribe(ServerPlayer player) {
		subscribers.remove(player.getUUID());
	}

	// ------------------------------------------------------------------ tick

	private long lastErrorLogTick = -100000;

	/** サーバーtickを絶対に落とさない: 他アドオンによる列車削除などで想定外の状態になっても、例外はログに残して握りつぶす。 */
	public void tick() {
		try {
			tickInner();
		} catch (RuntimeException | LinkageError e) {
			if (tickCount - lastErrorLogTick > 1200) {
				lastErrorLogTick = tickCount;
				LOGGER.error("mtrops の tick 処理で例外が発生しました(サーバーは継続します): {}", e.toString(), e);
			}
		}
	}

	private void tickInner() {
		tickCount++;
		if (!adapterOk) {
			return;
		}
		if (tickCount - lastSectionsSave > 6000) {
			lastSectionsSave = tickCount;
			saved.setDirty();
		}
		if (subscribers.isEmpty()) {
			if (hadSubscribers) {
				// 誰も見ていない間は何もしない。再開時に古い状態で誤検知しないよう検知状態を作り直す。
				detector = new AnomalyDetector(cfg);
				elapsed.clear();
				hadSubscribers = false;
			}
			return;
		}
		hadSubscribers = true;

		if (tickCount % 100 == 0) {
			refreshTopology(false);
			refreshGeometry();
		}
		if (tickCount % 400 == 5) {
			refreshSchedule();
		}
		if (tickCount % cfg.sampleTicks != 0) {
			return;
		}
		sampleCounter++;
		publishSnapshot();
	}

	private void publishSnapshot() {
		final List<TrainSample> samples = new ArrayList<>();
		final boolean withCars = cfg.maxLevel >= 4 && (lastLevel >= 4 || sampleCounter % 20 == 0);
		final java.util.Set<Long> present = new java.util.HashSet<>();
		MtrAdapter.sampleAll(server, tickCount, elapsed, withCars, samples, present);

		if (assigner.assign(samples, present)) {
			saved.setDirty();
		}
		if (assigner.takeRegistryChanged()) {
			saved.setDirty();
			broadcastRegistry(); // 車番範囲からの自動採番で編成が増えた
		}
		processEtas(samples);
		final AnomalyDetector.Result result = detector.update(tickCount, samples, present);

		final Snapshot snap = new Snapshot();
		snap.seq = ++seq;
		snap.gameTick = tickCount;
		snap.dayTime = (int) (server.overworld().getDayTime() % 24000L);
		snap.wallMillis = System.currentTimeMillis();
		snap.trains.addAll(result.views);
		snap.incidents.addAll(result.incidents);

		final List<byte[]> parts = SnapshotCodec.encode(snap, cfg.maxLevel, Math.min(cfg.maxSnapshotBytes, Net.MAX_S2C_BYTES));
		lastLevel = snap.level;

		final List<byte[]> etaParts = sampleCounter % 2 == 0 ? EtaCodec.encode(samples, ++etaSeq, snap.wallMillis, Net.MAX_S2C_BYTES) : null;
		for (final ServerPlayer p : subscribedPlayers()) {
			for (final byte[] part : parts) {
				send(p, Net.SNAPSHOT, part);
			}
			if (etaParts != null) {
				for (final byte[] part : etaParts) {
					send(p, Net.ETAS, part);
				}
			}
		}
	}

	// ------------------------------------------------------------------ 到着予想(実績での補正)

	private void processEtas(List<TrainSample> samples) {
		final java.util.Set<Long> seen = new HashSet<>();
		final long nowMillis = System.currentTimeMillis();
		for (final TrainSample s : samples) {
			seen.add(s.id);
			if (!s.onRoute || s.manual) {
				stopTracks.remove(s.id);
				continue;
			}
			StopTrack tr = stopTracks.get(s.id);
			if (s.atStop && s.curStationId != 0) {
				final long arriveTick = tickCount - (long) s.elapsedDwellTicks;
				if (tr == null || tr.station != s.curStationId) {
					// 新しい駅に着いた: 前の駅からの実績を記録
					if (tr != null && tr.nextStation == s.curStationId && !Double.isNaN(tr.nextIdeal)) {
						saved.sections.add(tr.station, s.curStationId, (arriveTick - tr.arriveTick) / 20.0, tr.nextIdeal / 20.0, nowMillis);
					}
					tr = new StopTrack();
					tr.station = s.curStationId;
					tr.arriveTick = arriveTick;
					stopTracks.put(s.id, tr);
				}
				if (Double.isNaN(tr.nextIdeal)) {
					for (int k = 0; k < s.etaStationIds.length; k++) {
						if (k < s.etaPass.length && s.etaPass[k]) {
							continue; // 通過するホームは次の停車駅ではない
						}
						tr.nextStation = s.etaStationIds[k];
						tr.nextIdeal = s.etaIdealTicks[k] + s.elapsedDwellTicks;
						break;
					}
				}
			}
			final long last = s.atStop && s.curStationId != 0 ? s.curStationId : tr != null ? tr.station : s.prevStationId;
			EtaPlanner.calibrate(s, last, saved.sections);
		}
		stopTracks.keySet().retainAll(seen);
	}

	// ------------------------------------------------------------------ 時刻表API用の同期

	/** 全車庫の理想走行。1回の計算が重い(1tickずつ走行を再現する)ので、1回に数車庫ずつ作り、数分おきに全部作り直す。 */
	private final java.util.Map<Long, IdealRun.Profile> scheduleProfiles = new java.util.LinkedHashMap<>();
	private final java.util.Set<Long> scheduleTried = new HashSet<>();
	private long scheduleProfilesBuilt;

	private void refreshSchedule() {
		final java.util.Set<Long> depots = ScheduleAdapter.depotRoutes(server).keySet();
		if (tickCount - scheduleProfilesBuilt > 12000) {
			scheduleProfilesBuilt = tickCount;
			scheduleProfiles.clear();
			scheduleTried.clear();
		}
		scheduleProfiles.keySet().retainAll(depots);
		final List<Long> todo = new ArrayList<>();
		for (final Long d : depots) {
			if (!scheduleTried.contains(d) && todo.size() < 4) {
				todo.add(d);
			}
		}
		if (!todo.isEmpty()) {
			scheduleTried.addAll(todo); // 経路が無く作れなかった車庫も、次の全体の作り直しまで再計算しない
			scheduleProfiles.putAll(ScheduleAdapter.buildProfiles(server, todo));
		}
		final java.util.Map<Long, IdealRun.Profile> profiles = scheduleProfiles;
		final long now = System.currentTimeMillis();
		final java.util.Map<Long, List<Long>> deploys = ScheduleAdapter.upcomingDeploys(server, now, 3L * 3600_000L);
		scheduleBytes = ScheduleSync.encode(now, profiles, deploys, saved.sections, Net.MAX_S2C_BYTES);
		for (final ServerPlayer p : subscribedPlayers()) {
			send(p, Net.SCHEDULE, scheduleBytes);
		}
	}

	/** 走っている列車の経路から、駅間の距離・線形を少しずつ集める(新しい区間が増えたときだけ配信)。 */
	private void refreshGeometry() {
		if (!MtrAdapter.collectGeometry(server, scannedTrains, sectionMap, 3)) {
			return;
		}
		final Geometry g = new Geometry();
		g.rev = ++geometryRev;
		g.sections.addAll(sectionMap.values());
		geometryParts = GeometryCodec.encode(g, Net.MAX_S2C_BYTES);
		for (final ServerPlayer p : subscribedPlayers()) {
			for (final byte[] part : geometryParts) {
				send(p, Net.GEOMETRY, part);
			}
		}
	}

	private void refreshTopology(boolean force) {
		final Topology t = MtrAdapter.buildTopology(server);
		registry.setTypes(t);
		t.rev = 0;
		final int hash = Arrays.hashCode(TopologyCodec.encode(t, Net.MAX_S2C_BYTES));
		if (!force && topologyBytes != null && hash == topologyContentHash) {
			return;
		}
		topologyContentHash = hash;
		t.rev = ++topologyRev;
		topologyBytes = TopologyCodec.encode(t, Net.MAX_S2C_BYTES);
		if (!force) {
			for (final ServerPlayer p : subscribedPlayers()) {
				send(p, Net.TOPOLOGY, topologyBytes);
			}
		}
	}

	// ------------------------------------------------------------------ 台帳の更新(クライアント → サーバー)

	public void handleDepotUpdate(ServerPlayer player, byte[] payload) {
		try {
			handleDepotUpdateInner(player, payload);
		} catch (RuntimeException | LinkageError e) {
			// プレイヤーの操作で例外が出てもサーバーは止めない
			LOGGER.error("handleDepotUpdate で例外が発生しました: {}", e.toString(), e);
			sendResult(player, Net.RESULT_BAD_PAYLOAD, "サーバーで処理できませんでした");
		}
	}

	private void handleDepotUpdateInner(ServerPlayer player, byte[] payload) {
		if (!canEdit(player)) {
			sendResult(player, Net.RESULT_NO_PERMISSION, "車庫台帳を編集する権限がありません");
			return;
		}
		final RegistryCodec.DepotUpdate update;
		try {
			update = RegistryCodec.decodeDepotUpdate(payload);
		} catch (IOException | RuntimeException e) {
			sendResult(player, Net.RESULT_BAD_PAYLOAD, "不正なデータです");
			return;
		}
		final Set<Long> validSidings = MtrAdapter.sidingIdsOfDepot(server, update.depot.depotId);
		if (validSidings == null) {
			sendResult(player, Net.RESULT_UNKNOWN_DEPOT, "この車庫はMTRに存在しません");
			return;
		}
		update.depot.dimension = MtrAdapter.dimensionOfDepot(server, update.depot.depotId);

		registry.setTypes(MtrAdapter.buildTopology(server));
		final OpsRegistry.Result result = registry.applyDepotUpdate(update.depot, update.baseRev, validSidings);
		if (result == OpsRegistry.Result.OK) {
			saved.setDirty();
			LOGGER.info("{} が車庫 {} の編成台帳を更新しました (rev {})", player.getGameProfile().getName(), update.depot.depotId, registry.depots.get(update.depot.depotId).rev);
			broadcastRegistry();
			sendResult(player, 0, "保存しました");
		} else {
			sendResult(player, result.ordinal(), registry.lastError);
			send(player, Net.REGISTRY, RegistryCodec.encodeRegistry(registry, false));
		}
	}

	static byte[] inflate(byte[] data, int max) throws IOException {
		final java.util.zip.Inflater inf = new java.util.zip.Inflater();
		inf.setInput(data);
		final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		final byte[] buf = new byte[8192];
		try {
			while (!inf.finished()) {
				final int n = inf.inflate(buf);
				if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
					throw new IOException("truncated");
				}
				out.write(buf, 0, n);
				if (out.size() > max) {
					throw new IOException("too large");
				}
			}
		} catch (java.util.zip.DataFormatException e) {
			throw new IOException(e);
		} finally {
			inf.end();
		}
		return out.toByteArray();
	}

	private boolean canEdit(ServerPlayer player) {
		if (cfg.requireOpToEdit) {
			return player.hasPermissions(2);
		}
		return MtrAdapter.hasMtrEditPermission(player);
	}

	private void broadcastRegistry() {
		final byte[] bytes = RegistryCodec.encodeRegistry(registry, false);
		if (bytes.length > Net.MAX_S2C_BYTES) {
			LOGGER.error("車庫台帳が大きすぎて同期できません ({} bytes)", bytes.length);
			return;
		}
		for (final ServerPlayer p : subscribedPlayers()) {
			send(p, Net.REGISTRY, bytes);
		}
	}

	// ------------------------------------------------------------------ 送信ユーティリティ

	private List<ServerPlayer> subscribedPlayers() {
		final List<ServerPlayer> list = new ArrayList<>();
		for (final UUID id : new ArrayList<>(subscribers)) {
			final ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) {
				subscribers.remove(id);
			} else {
				list.add(p);
			}
		}
		return list;
	}

	private static void send(ServerPlayer player, net.minecraft.resources.ResourceLocation channel, byte[] bytes) {
		final FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeByteArray(bytes);
		ServerPlayNetworking.send(player, channel, buf);
	}

	private static void sendResult(ServerPlayer player, int code, String message) {
		final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeInt(code);
		buf.writeUtf(message == null ? "" : message);
		ServerPlayNetworking.send(player, Net.EDIT_RESULT, buf);
	}
}
