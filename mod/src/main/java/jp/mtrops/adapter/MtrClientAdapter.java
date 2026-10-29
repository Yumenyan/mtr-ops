package jp.mtrops.adapter;

import jp.mtrops.core.RailNetwork;
import mtr.client.ClientData;
import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;
import mtr.data.ScheduleEntry;
import mtr.data.Platform;
import mtr.data.Rail;
import mtr.data.RailType;
import mtr.data.SavedRailBase;
import mtr.data.Siding;
import mtr.data.Station;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * クライアント側のMTR依存部。車両タイプID(MTRの列車ID)から、表示名を引く。
 * 名前は MTR が読み込んだリソースパックの mtr_custom_resources.json の "name" など(TrainClientRegistry に登録済みのもの)。
 */
public final class MtrClientAdapter {

	private MtrClientAdapter() {
	}

	/** 見つからない/空のときは空文字。 */
	public static String typeName(String trainId) {
		if (trainId == null || trainId.isEmpty()) {
			return "";
		}
		try {
			final TrainProperties p = TrainClientRegistry.getTrainProperties(trainId);
			if (p == null || p.name == null) {
				return "";
			}
			return p.name.getString();
		} catch (RuntimeException e) {
			return "";
		}
	}

	// ------------------------------------------------------------------ レールの形と種別(/api/rails)

	private static int lastRailCount = -1;
	private static int lastRailHash;

	/**
	 * クライアントが受信済みのレール(ClientData.RAILS)を写す。クライアントスレッドから呼ぶこと。
	 * 変化が無ければ null(作り直さない)。
	 */
	public static RailNetwork.Snapshot snapshotRails(String dimension, boolean force) {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = ClientData.RAILS;
		int count = 0;
		int hash = dimension.hashCode();
		for (final Map.Entry<BlockPos, Map<BlockPos, Rail>> e : rails.entrySet()) {
			count += e.getValue().size();
			hash = hash * 31 + e.getKey().hashCode() * 7 + e.getValue().size();
		}
		if (!force && count == lastRailCount && hash == lastRailHash) {
			return null;
		}
		lastRailCount = count;
		lastRailHash = hash;

		final Map<java.util.UUID, SavedRailBase> saved = new HashMap<>();
		for (final Platform p : ClientData.PLATFORMS) {
			index(saved, p);
		}
		for (final Siding sd : ClientData.SIDINGS) {
			index(saved, sd);
		}
		final RailNetwork.Snapshot snap = new RailNetwork.Snapshot();
		snap.dimension = dimension;
		snap.builtMillis = System.currentTimeMillis();
		final Set<java.util.UUID> done = new HashSet<>();
		for (final Map.Entry<BlockPos, Map<BlockPos, Rail>> e : rails.entrySet()) {
			final BlockPos a = e.getKey();
			for (final Map.Entry<BlockPos, Rail> e2 : e.getValue().entrySet()) {
				final BlockPos b = e2.getKey();
				final java.util.UUID key = mtr.path.PathData.getRailProduct(a, b);
				if (!done.add(key)) {
					continue;
				}
				Rail rail = e2.getValue();
				final Map<BlockPos, Rail> back = rails.get(b);
				final Rail reverse = back == null ? null : back.get(a);
				boolean oneWay = false;
				if (rail.railType == RailType.NONE && reverse != null && reverse.railType != RailType.NONE) {
					rail = reverse;
					oneWay = true;
				} else if (reverse == null || reverse.railType == RailType.NONE) {
					oneWay = rail.railType != RailType.NONE;
				}
				snap.rails.add(toGeo(rail, saved.get(key), oneWay));
			}
		}
		return snap;
	}

	@SuppressWarnings("unchecked")
	private static void index(Map<java.util.UUID, SavedRailBase> map, SavedRailBase base) {
		try {
			final java.lang.reflect.Field f = SavedRailBase.class.getDeclaredField("positions");
			f.setAccessible(true);
			final java.util.List<BlockPos> pos = new java.util.ArrayList<>((Set<BlockPos>) f.get(base));
			if (pos.size() == 2) {
				map.put(mtr.path.PathData.getRailProduct(pos.get(0), pos.get(1)), base);
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
		}
	}

	private static RailNetwork.Rail toGeo(Rail rail, SavedRailBase base, boolean oneWay) {
		final RailNetwork.Rail g = new RailNetwork.Rail();
		final double len = rail.getLength();
		final Vec3 p1 = rail.getPosition(0);
		final Vec3 p2 = rail.getPosition(len);
		g.x1 = p1.x;
		g.y1 = p1.y;
		g.z1 = p1.z;
		g.x2 = p2.x;
		g.y2 = p2.y;
		g.z2 = p2.z;
		g.length = len;
		g.type = rail.railType.toString();
		g.mode = rail.transportMode.toString().toLowerCase(Locale.ROOT);
		g.role = RailNetwork.role(g.type);
		g.speedKmh = rail.railType.canAccelerate ? Math.round(rail.railType.maxBlocksPerTick * 72F) : 0;
		g.oneWay = oneWay;
		if (base != null) {
			g.savedRailId = base.id;
			g.savedRailName = base.name == null ? "" : base.name;
			if (base instanceof Platform) {
				final Station st = ClientData.DATA_CACHE.platformIdToStation.get(base.id);
				g.stationId = st == null ? 0 : st.id;
			}
		}
		// 曲線は約8ブロックごとに点を打つ(直線は両端だけ)
		final Vec3 mid = rail.getPosition(len / 2);
		final double dev = Math.abs((mid.x - (p1.x + p2.x) / 2)) + Math.abs((mid.z - (p1.z + p2.z) / 2));
		final int seg = dev < 0.25 ? 1 : (int) Math.max(2, Math.min(64, Math.ceil(len / 8)));
		g.points = new float[(seg + 1) * 2];
		for (int i = 0; i <= seg; i++) {
			final Vec3 p = rail.getPosition(len * i / seg);
			g.points[i * 2] = (float) p.x;
			g.points[i * 2 + 1] = (float) p.z;
		}
		return g;
	}

	// ------------------------------------------------------------------ ScheduleEntry(発車標の到着予定)

	/**
	 * MTR 3.2.2 の ScheduleEntry は arrivalMillis / trainCars / routeId / currentStationIndex を持ち、ホームIDは持たない。
	 * ホームは ClientData.SCHEDULES_FOR_PLATFORM(ホームID → その到着予定の集合)のどれに入っているかで決める。
	 */
	public static final class EntryData {
		public long routeId = -1;
		public long platformId = -1;
		public long arrivalMillis = Long.MIN_VALUE;
		public int cars = -1;
		public int stationIndex = -1;
	}

	/** ScheduleEntry → ホームID(同じ集合に入っているもの)。到着予定は約1秒ごとに作り直されるので、見つからなければ作り直す。 */
	private static final Map<Object, Long> PLATFORM_OF = new java.util.IdentityHashMap<>();
	private static long platformIndexBuilt;

	private static synchronized long platformOf(Object entry) {
		Long id = PLATFORM_OF.get(entry);
		if (id == null && System.currentTimeMillis() - platformIndexBuilt > 100) {
			PLATFORM_OF.clear();
			for (final Map.Entry<Long, Set<ScheduleEntry>> e : new java.util.ArrayList<>(ClientData.SCHEDULES_FOR_PLATFORM.entrySet())) {
				for (final ScheduleEntry x : new java.util.ArrayList<>(e.getValue())) {
					PLATFORM_OF.put(x, e.getKey());
				}
			}
			platformIndexBuilt = System.currentTimeMillis();
			id = PLATFORM_OF.get(entry);
		}
		return id == null ? -1 : id;
	}

	/**
	 * ScheduleEntry から路線・ホーム・到着時刻・両数・路線内の駅番号を読む。
	 * @param platformId スクリプトが知っているホームID(分からなければ -1。そのときは到着予定の一覧から探す)
	 */
	public static EntryData readEntry(Object entry, long platformId) {
		final EntryData d = new EntryData();
		try {
			if (entry instanceof ScheduleEntry) {
				final ScheduleEntry e = (ScheduleEntry) entry;
				d.routeId = e.routeId;
				d.arrivalMillis = e.arrivalMillis;
				d.cars = e.trainCars;
				d.stationIndex = e.currentStationIndex;
				d.platformId = platformId > 0 ? platformId : platformOf(entry);
			}
		} catch (RuntimeException | LinkageError ignored) {
		}
		return d;
	}

	/** ホームID → 駅ID(クライアントのMTRデータ)。不明は 0。 */
	public static long stationOfPlatform(long platformId) {
		try {
			final Station st = ClientData.DATA_CACHE.platformIdToStation.get(platformId);
			return st == null ? 0 : st.id;
		} catch (RuntimeException | LinkageError e) {
			return 0;
		}
	}

	/** そのホームの到着予定(読めなければ空)。 */
	public static java.util.List<EntryData> entriesOfPlatform(long platformId) {
		final java.util.List<EntryData> out = new java.util.ArrayList<>();
		try {
			final Set<ScheduleEntry> set = ClientData.SCHEDULES_FOR_PLATFORM.get(platformId);
			if (set != null) {
				for (final ScheduleEntry e : new java.util.ArrayList<>(set)) {
					final EntryData d = new EntryData();
					d.routeId = e.routeId;
					d.arrivalMillis = e.arrivalMillis;
					d.cars = e.trainCars;
					d.stationIndex = e.currentStationIndex;
					d.platformId = platformId;
					out.add(d);
				}
			}
		} catch (RuntimeException | LinkageError ignored) {
		}
		return out;
	}
}
