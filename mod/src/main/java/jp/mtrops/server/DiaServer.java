package jp.mtrops.server;

import jp.mtrops.adapter.DiaAdapter;
import jp.mtrops.adapter.DiaHooks;
import jp.mtrops.core.DiaCodec;
import jp.mtrops.core.DiaModel;
import jp.mtrops.core.DiaRuntime;
import jp.mtrops.core.OpsConfig;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * カスタムダイヤのサーバー側(設定の正本・時刻表の生成・列車処理のフックへの情報提供)。
 * 監視(OpsServer)と違い、見ている人がいなくても常に動く(ダイヤはゲームの動きそのものなので)。
 */
public final class DiaServer implements DiaHooks.Context {

	private static final Logger LOGGER = LoggerFactory.getLogger("mtrops/dia");

	private final MinecraftServer server;
	private final OpsConfig cfg;
	private final OpsSavedData saved;
	private final DiaRuntime runtime = new DiaRuntime();
	private final ZoneId zone;
	private Consumer<byte[]> broadcaster = b -> {
	};

	private volatile Map<Long, Long> sidingDepot = new HashMap<>();
	private Map<Long, List<Long>> depotRoutes = new LinkedHashMap<>();
	private Map<Long, DiaModel.Profile> profiles = new LinkedHashMap<>();
	private int stateHash;
	private byte[] syncBytes;
	private long tick;
	private boolean warnedHooks;
	private Map<String, Double> ratios;
	private long ratiosAt;

	public DiaServer(MinecraftServer server, OpsConfig cfg, OpsSavedData saved) {
		this.server = server;
		this.cfg = cfg;
		this.saved = saved;
		ZoneId z;
		try {
			z = cfg.timeZone == null || cfg.timeZone.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(cfg.timeZone);
		} catch (RuntimeException e) {
			LOGGER.warn("timeZone の指定が不正です({})。サーバーの既定のタイムゾーンを使います", cfg.timeZone);
			z = ZoneId.systemDefault();
		}
		this.zone = z;
		DiaHooks.setContext(this);
	}

	public void stop() {
		DiaHooks.setContext(null);
	}

	public void setBroadcaster(Consumer<byte[]> b) {
		this.broadcaster = b;
	}

	public DiaModel.Config config() {
		return saved.dia;
	}

	public byte[] syncBytes() {
		if (syncBytes == null) {
			syncBytes = encodeSync();
		}
		return syncBytes;
	}

	// ------------------------------------------------------------------ tick

	public void tick() {
		tick++;
		if (tick % 100 == 1) {
			refresh(false);
		}
		if (tick % 1200 == 0) {
			DiaHooks.sweep(tick);
			runtime.sweep(System.currentTimeMillis(), 120_000);
		}
		if (!warnedHooks && tick == 1200 && !runtime.customDepots().isEmpty() && !DiaHooks.dwellHookSeen) {
			warnedHooks = true;
			LOGGER.error("カスタムダイヤ用の列車処理フック(ミックスイン)が動いていません。MTRのバージョンが 3.2.2-hotfix-1 か確認してください。発車時刻・ATC・回復運転は無効です");
		}
	}

	/** 路線・車庫・経路を読み直し、変わっていれば時刻表を作り直す。 */
	public void refresh(boolean force) {
		sidingDepot = DiaAdapter.sidingDepots(server);
		depotRoutes = DiaAdapter.depotRoutes(server);
		final DiaModel.Config c = saved.dia;
		final List<Long> custom = new ArrayList<>();
		for (final Map.Entry<Long, List<Long>> e : depotRoutes.entrySet()) {
			if (c.isDepotCustom(e.getValue())) {
				custom.add(e.getKey());
			}
		}
		Collections.sort(custom);
		// 走行時間の見積もりに使う実績比率。数分おきにだけ更新する(時刻表が頻繁に作り直されないように)
		if (ratios == null || tick - ratiosAt > 3000) {
			ratiosAt = tick;
			ratios = jp.mtrops.core.DiaStretch.ratios(saved.runs);
		}
		final Map<Long, DiaModel.Profile> profs = DiaAdapter.buildProfiles(server, custom);
		final byte[] profBytes = DiaCodec.encodeProfiles(profs);
		final int hash = Arrays.hashCode(profBytes) * 31 + Arrays.hashCode(DiaCodec.encodeConfig(c)) * 17 + depotRoutes.hashCode() * 7 + ratios.hashCode();
		if (!force && hash == stateHash) {
			return;
		}
		stateHash = hash;
		profiles = profs;
		runtime.update(c, profs, depotRoutes, ratios);
		for (final String w : runtime.plan().warnings) {
			LOGGER.warn("カスタムダイヤ: {}", w);
		}
		syncBytes = encodeSync();
		broadcaster.accept(syncBytes);
	}

	// ------------------------------------------------------------------ 設定の更新(権限確認は呼び出し側)

	/** @return 失敗理由。成功なら null */
	public String applyUpdate(DiaModel.Config incoming, int baseRev) {
		final DiaModel.Config cur = saved.dia;
		if (baseRev != cur.rev) {
			return "他の人が先にダイヤを更新しました。読み込み直してから保存してください";
		}
		final String err = incoming.validate();
		if (err != null) {
			return err;
		}
		incoming.rev = cur.rev + 1;
		saved.dia = incoming;
		saved.setDirty();
		try {
			refresh(true);
		} catch (RuntimeException e) {
			LOGGER.error("ダイヤの反映に失敗しました: {}", e.toString(), e);
			return "サーバーでダイヤを反映できませんでした(ログを確認してください)";
		}
		return null;
	}

	// ------------------------------------------------------------------ 同期データ

	/** S2C: タイムゾーン・現在のUTCオフセット・設定・理想走行 */
	private byte[] encodeSync() {
		return jp.mtrops.core.DiaSync.encode(zone.getId(), zone.getRules().getOffset(Instant.now()).getTotalSeconds(), saved.dia, profiles, new ArrayList<>(runtime.customDepots()), ratios == null ? new HashMap<>() : ratios);
	}

	/** 実行中の列車の便・遅れ(S2C、数秒ごと) */
	public byte[] encodeLive() {
		return jp.mtrops.core.DiaSync.encodeLive(nowSec(), runtime.snapshot());
	}

	// ------------------------------------------------------------------ DiaHooks.Context

	@Override
	public long depotOfSiding(long sidingId) {
		final Long d = sidingDepot.get(sidingId);
		return d == null ? 0 : d;
	}

	@Override
	public DiaRuntime runtime() {
		return runtime;
	}

	@Override
	public double nowSec() {
		final LocalTime t = LocalTime.now(zone);
		return t.toSecondOfDay() + t.getNano() / 1e9;
	}

	@Override
	public int minOpenTicks() {
		return saved.dia.minOpen * 20;
	}

	@Override
	public long currentTick() {
		return tick;
	}

	/** 「その日の0時からの秒」→ その時刻の UNIXミリ秒(now に一番近い日付で)。時刻表API用。 */
	public ZoneId zone() {
		return zone;
	}
}
