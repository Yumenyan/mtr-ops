package jp.mtrops.adapter;

import jp.mtrops.core.DiaModel;
import jp.mtrops.core.DiaRunTime;
import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.Platform;
import mtr.data.RailType;
import mtr.data.RailwayData;
import mtr.data.SavedRailBase;
import mtr.data.Siding;
import mtr.data.Station;
import mtr.data.TrainServer;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * カスタムダイヤ用のMTRアクセス(MTR 3.2.2-hotfix-1 のソースで確認した公開API/フィールドのみ)。
 *  ・車庫ごとの理想走行(停車駅・通過するホーム・時刻)を作る
 *  ・車庫の作業用の出庫時刻(Depot.tempDepartures、保存されない)を作り直させる(DepotMixin が時刻表の時刻に置き換える)
 *
 * 車庫・路線・ホーム・レールなど MTR に保存されたデータは一切書き換えない。
 */
public final class DiaAdapter {

	private static final Field TRAIN_RAIL_LENGTH = find(mtr.data.Train.class, "railLength");
	private static final Field SAVED_RAIL_POSITIONS = find(SavedRailBase.class, "positions");

	private DiaAdapter() {
	}

	private static Field find(Class<?> owner, String name) {
		try {
			final Field f = owner.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** 車庫ごとに登録された路線(順番どおり)。 */
	public static Map<Long, List<Long>> depotRoutes(MinecraftServer server) {
		final Map<Long, List<Long>> map = new LinkedHashMap<>();
		for (final ServerLevel level : server.getAllLevels()) {
			final RailwayData rd = RailwayData.getInstance(level);
			if (rd == null) {
				continue;
			}
			for (final Depot d : rd.depots) {
				map.put(d.id, new ArrayList<>(d.routeIds));
			}
		}
		return map;
	}

	/** 車庫線 → 車庫 の対応(列車処理のフックから引く)。 */
	public static Map<Long, Long> sidingDepots(MinecraftServer server) {
		final Map<Long, Long> map = new HashMap<>();
		for (final ServerLevel level : server.getAllLevels()) {
			final RailwayData rd = RailwayData.getInstance(level);
			if (rd == null) {
				continue;
			}
			for (final Siding sd : rd.sidings) {
				final Depot d = rd.dataCache.sidingIdToDepot.get(sd.id);
				if (d != null) {
					map.put(sd.id, d.id);
				}
			}
		}
		return map;
	}

	// ------------------------------------------------------------------ 理想走行

	/**
	 * 指定した車庫それぞれについて、経路が出来ている最初の車庫線から理想走行を作る。
	 * 経路が無い車庫は含めない。
	 */
	public static Map<Long, DiaModel.Profile> buildProfiles(MinecraftServer server, Collection<Long> depotIds) {
		final Map<Long, DiaModel.Profile> out = new LinkedHashMap<>();
		for (final ServerLevel level : server.getAllLevels()) {
			final RailwayData rd = RailwayData.getInstance(level);
			if (rd == null) {
				continue;
			}
			final DataCache dc = rd.dataCache;
			Map<java.util.UUID, Platform> byPos = null;
			final List<Siding> sidings = new ArrayList<>(rd.sidings);
			sidings.sort((a, b) -> Long.compare(a.id, b.id));
			for (final Siding sd : sidings) {
				final Depot depot = dc.sidingIdToDepot.get(sd.id);
				if (depot == null || !depotIds.contains(depot.id) || out.containsKey(depot.id)) {
					continue;
				}
				final TrainServer t = firstTrain(sd);
				if (t == null || t.path.isEmpty()) {
					continue;
				}
				if (byPos == null) {
					byPos = platformsByRail(rd.platforms);
				}
				final DiaModel.Profile p = profileOf(t, sd, depot, dc, byPos);
				if (p != null && !p.stops.isEmpty()) {
					out.put(depot.id, p);
				}
			}
		}
		return out;
	}

	private static TrainServer firstTrain(Siding sd) {
		for (final TrainServer t : MtrFields.trains(sd)) {
			return t;
		}
		return null;
	}

	private static DiaModel.Profile profileOf(TrainServer t, Siding sd, Depot depot, DataCache dc, Map<java.util.UUID, Platform> byPos) {
		final List<PathData> path = t.path;
		final int n = path.size();
		final DiaRunTime.Rail[] rails = new DiaRunTime.Rail[n];
		for (int i = 0; i < n; i++) {
			final PathData pd = path.get(i);
			final DiaRunTime.Rail r = new DiaRunTime.Rail();
			r.length = pd.rail.getLength();
			r.maxSpeed = pd.rail.railType.canAccelerate ? pd.rail.railType.maxBlocksPerTick : -1;
			r.dwell = pd.dwellTime;
			r.reverseAfter = i + 1 < n && pd.isOppositeRail(path.get(i + 1));
			rails[i] = r;
		}
		final float railLength = railLength(t);
		final DiaRunTime.Result res = DiaRunTime.compute(rails, railLength, t.trainCars, t.spacing, t.accelerationConstant, RailType.getDefaultMaxBlocksPerTick(t.transportMode));

		final DiaModel.Profile p = new DiaModel.Profile();
		p.depotId = depot.id;
		p.sidingId = sd.id;
		p.total = res.totalTick / 20.0;
		final List<Long> routeIds = depot.routeIds;
		for (int i = 0; i < n; i++) {
			final PathData pd = path.get(i);
			long platformId = 0;
			boolean pass = false;
			if (pd.dwellTime > 0 && pd.savedRailBaseId != 0) {
				platformId = pd.savedRailBaseId;
			} else if (pd.dwellTime == 0 && pd.rail.railType == RailType.PLATFORM) {
				final Platform pf = byPos.get(pd.getRailProduct());
				if (pf != null) {
					platformId = pf.id;
					pass = true;
				}
			}
			if (platformId == 0) {
				continue;
			}
			final Station st = dc.platformIdToStation.get(platformId);
			if (st == null) {
				continue;
			}
			// 折り返し駅などで同じホームに続けて現れる(到着→折返し)場合は1つにまとめる
			if (!p.stops.isEmpty()) {
				final DiaModel.Stop last = p.stops.get(p.stops.size() - 1);
				if (last.platformId == platformId && last.pathIndex == i - 1) {
					continue;
				}
			}
			final Platform pf = dc.platformIdMap.get(platformId);
			final DiaModel.Stop s = new DiaModel.Stop();
			s.pathIndex = i;
			s.platformId = platformId;
			s.stationId = st.id;
			s.station = st.name == null ? "" : st.name;
			s.platform = pf == null || pf.name == null ? "" : pf.name;
			s.pass = pass;
			// 停車番号 → 路線と路線内の駅番号(MTRの発車標と同じ割り当て。前の路線の終点と次の路線の始点が同じホームなら1つに数える)
			final long[] ri = {0, -1};
			RailwayData.useRoutesAndStationsFromIndex(pd.stopIndex - 1, routeIds, dc, (currentStationIndex, thisRoute, nextRoute, thisStation, nextStation, lastStation) -> {
				ri[0] = thisRoute == null ? 0 : thisRoute.id;
				ri[1] = currentStationIndex;
			});
			s.routeId = ri[0];
			s.stationIndex = (int) ri[1];
			if (pass) {
				s.arr = res.endTick[i] / 20.0;
				s.dep = s.arr;
			} else {
				s.arr = res.arriveTick[i] / 20.0;
				s.dep = (res.arriveTick[i] + pd.dwellTime * 10) / 20.0;
			}
			p.stops.add(s);
		}
		return p;
	}

	private static float railLength(TrainServer t) {
		try {
			return TRAIN_RAIL_LENGTH == null ? 0 : TRAIN_RAIL_LENGTH.getFloat(t);
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}


	@SuppressWarnings("unchecked")
	private static Map<java.util.UUID, Platform> platformsByRail(Set<Platform> platforms) {
		final Map<java.util.UUID, Platform> map = new HashMap<>();
		if (SAVED_RAIL_POSITIONS == null) {
			return map;
		}
		for (final Platform p : platforms) {
			try {
				final List<BlockPos> pos = new ArrayList<>((Set<BlockPos>) SAVED_RAIL_POSITIONS.get(p));
				if (pos.size() == 2) {
					map.put(PathData.getRailProduct(pos.get(0), pos.get(1)), p);
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
			}
		}
		return map;
	}

	// ------------------------------------------------------------------ 出庫時刻

	/**
	 * 車庫の作業用の出庫時刻(Depot.tempDepartures)を作り直させる。カスタムダイヤの車庫は DepotMixin で時刻表の時刻に置き換わり、
	 * カスタムでなくなった車庫は MTR の設定どおりに戻る。車庫に保存された設定(departures 等)は書き換えない。
	 */
	public static void regenerateDepartures(MinecraftServer server, Collection<Long> depotIds) {
		for (final ServerLevel level : server.getAllLevels()) {
			final RailwayData rd = RailwayData.getInstance(level);
			if (rd == null) {
				continue;
			}
			for (final Long id : depotIds) {
				final Depot d = rd.dataCache.depotIdMap.get(id);
				if (d != null) {
					d.generateTempDepartures(level);
				}
			}
		}
	}

	/**
	 * 今後 horizonMillis 以内の出庫予定(UNIXミリ秒)。MTR の作業用の出庫時刻(tempDepartures、その日の0時(UTC)からのミリ秒)から求める。
	 * 「無限に繰り返す」車庫は出庫しても入庫しないので対象外。
	 */
	public static Map<Long, List<Long>> upcomingDeploys(MinecraftServer server, long nowMillis, long horizonMillis) {
		final Map<Long, List<Long>> out = new LinkedHashMap<>();
		final long day = mtr.data.Depot.MILLISECONDS_PER_DAY;
		final long dayStart = nowMillis - Math.floorMod(nowMillis, day);
		for (final ServerLevel level : server.getAllLevels()) {
			final RailwayData rd = RailwayData.getInstance(level);
			if (rd == null) {
				continue;
			}
			for (final Depot d : rd.depots) {
				if (d.repeatInfinitely || d.routeIds.isEmpty()) {
					continue;
				}
				final List<Long> list = new ArrayList<>();
				for (final Integer ms : new ArrayList<>(d.tempDepartures)) {
					for (int k = 0; k <= 1; k++) {
						final long t = dayStart + k * day + ms;
						if (t > nowMillis && t <= nowMillis + horizonMillis) {
							list.add(t);
						}
					}
				}
				list.sort(Long::compare);
				if (!list.isEmpty()) {
					out.put(d.id, list);
				}
			}
		}
		return out;
	}
}
