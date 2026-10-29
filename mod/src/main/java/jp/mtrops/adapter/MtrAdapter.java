package jp.mtrops.adapter;

import jp.mtrops.core.ElapsedTracker;
import jp.mtrops.core.Geometry;
import jp.mtrops.core.Topology;
import jp.mtrops.core.TrainSample;
import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.Siding;
import mtr.data.Station;
import mtr.data.TrainServer;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MTR 3.2.2-hotfix-1 への依存をすべてここ(と MtrFields)に閉じ込めるAdapter層。
 * これより上のレイヤー(jp.mtrops.core / server / client)は mtr.* を import しない。
 *
 * このクラスが使うMTR APIは、3.x.x タグ(mod_version=3.2.2-hotfix-1)のソースで存在を確認したものだけ:
 *   RailwayData.getInstance(Level) / .sidings / .depots / .stations / .routes / .dataCache
 *   DataCache.sidingIdToDepot / platformIdToStation / routeIdMap
 *   Train: id, sidingId, trainId, trainCars, spacing, path, transportMode, getSpeed(), getRailProgress(),
 *          getDoorValue(), getElapsedDwellTicks(), isOnRoute(), isCurrentlyManual(), getIndex(double, boolean)
 *   PathData: rail, savedRailBaseId, dwellTime ; Rail.getPosition(double) ; TransportMode.railOffset
 *   Siding.TimeSegment: startRailProgress, endRailProgress, endTime, savedRailBaseId, getTime(double)
 *   Route: platformIds[].platformId, getLastPlatformId(), routeType, circularState, isLightRailRoute, lightRailRouteNumber
 *   AreaBase.getCenter() / corner1 / corner2 ; SavedRailBase.getMidPos()
 */
public final class MtrAdapter {

	private static final Logger LOGGER = LoggerFactory.getLogger("mtrops/adapter");
	private static long errorLogCooldown;

	private MtrAdapter() {
	}

	/** 起動時に呼び、MTR側の構造が想定どおりかをログに出す。 */
	public static boolean selfCheck() {
		final String missing = MtrFields.missing();
		if (!missing.isEmpty()) {
			LOGGER.error("MTR の想定フィールドが見つかりません(別バージョンのMTRの可能性): {}", missing);
		}
		return MtrFields.sidingTrainsAvailable();
	}

	// ------------------------------------------------------------------ 列車サンプリング

	public static void sampleAll(MinecraftServer server, long nowTick, ElapsedTracker tracker, boolean withCars, List<TrainSample> out, Set<Long> present) {
		final Set<Long> live = new HashSet<>();
		for (final ServerLevel level : server.getAllLevels()) {
			sampleLevel(level, nowTick, tracker, withCars, out, present);
		}
		for (final TrainSample s : out) {
			live.add(s.id);
		}
		tracker.retainOnly(live);
	}

	private static void sampleLevel(ServerLevel level, long nowTick, ElapsedTracker tracker, boolean withCars, List<TrainSample> out, Set<Long> present) {
		final RailwayData railwayData = RailwayData.getInstance(level);
		if (railwayData == null) {
			return;
		}
		final DataCache dataCache = railwayData.dataCache;
		final String dimension = level.dimension().location().toString();

		for (final Siding siding : new ArrayList<>(railwayData.sidings)) {
			final Depot depot = dataCache.sidingIdToDepot.get(siding.id);
			for (final TrainServer train : new ArrayList<>(MtrFields.trains(siding))) {
				present.add(train.id); // サンプリングに失敗しても「実在する」ことは記録する(誤った列車消失を防ぐ)
				try {
					final TrainSample s = sampleTrain(train, siding, depot, dataCache, dimension, nowTick, tracker, withCars);
					if (s != null) {
						out.add(s);
					}
				} catch (RuntimeException e) {
					logRateLimited("列車のサンプリングに失敗 (id=" + train.id + ")", e);
				}
			}
		}
	}

	private static TrainSample sampleTrain(TrainServer t, Siding siding, Depot depot, DataCache dc, String dimension, long nowTick, ElapsedTracker tracker, boolean withCars) {
		final List<Double> distances = MtrFields.distances(t);
		final int pathSize = t.path.size();
		if (pathSize == 0 || distances.size() < pathSize) {
			// 経路が空/距離表が不足 → 位置は算出できない。ゴースト疑いとして最小限のサンプルを返す(車庫線の位置に置く)。
			final TrainSample g = new TrainSample();
			g.id = t.id;
			g.sidingId = siding.id;
			g.depotId = depot == null ? 0 : depot.id;
			g.dimension = dimension;
			g.trainType = t.trainId;
			g.cars = t.trainCars;
			g.spacing = t.spacing;
			g.onRoute = t.isOnRoute();
			g.cableCar = siding.transportMode == mtr.data.TransportMode.CABLE_CAR;
			g.speedMps = t.getSpeed() * 20F;
			g.railProgress = t.getRailProgress();
			g.routeId = MtrFields.routeId(t);
			final net.minecraft.core.BlockPos mid = siding.getMidPos();
			g.x = mid.getX();
			g.y = mid.getY();
			g.z = mid.getZ();
			g.suspect = TrainSample.SUSPECT_NOPATH;
			return g;
		}

		final TrainSample s = new TrainSample();
		s.id = t.id;
		s.sidingId = siding.id;
		s.depotId = depot == null ? 0 : depot.id;
		s.dimension = dimension;
		s.trainType = t.trainId;
		s.cars = t.trainCars;
		s.spacing = t.spacing;
		s.onRoute = t.isOnRoute();
		s.cableCar = siding.transportMode == mtr.data.TransportMode.CABLE_CAR;
		s.manual = t.isCurrentlyManual();
		s.speedMps = t.getSpeed() * 20F; // blocks/tick → m/s
		s.doorOpen = t.getDoorValue() > 0.01F;
		s.railProgress = t.getRailProgress();
		s.pathLength = distances.get(pathSize - 1);
		s.passengers = MtrFields.passengerCount(t);
		s.routeId = MtrFields.routeId(t);

		final Route route = dc.routeIdMap.get(s.routeId);
		if (route != null) {
			final Station dest = dc.platformIdToStation.get(route.getLastPlatformId());
			s.destStationId = dest == null ? 0 : dest.id;
		}

		final Vec3 head = positionAt(t, distances, s.railProgress);
		if (head != null) {
			s.x = head.x;
			s.y = head.y;
			s.z = head.z;
			final Vec3 behind = positionAt(t, distances, s.railProgress - Math.max(2, t.spacing / 2.0));
			if (behind != null) {
				s.yaw = bearing(head, behind);
			}
			if (withCars && s.onRoute && s.cars > 0) {
				fillCarPositions(s, t, distances);
			}
		}

		// --- 駅(次・前・現在) ---
		final int nextStop = MtrFields.nextStoppingIndex(t);
		if (nextStop >= pathSize) {
			s.suspect |= TrainSample.SUSPECT_FROZEN; // Train.simulateTrain 冒頭の早期returnに該当(毎tick何もしない)
		}
		final boolean valid = s.onRoute && nextStop >= 0 && nextStop < pathSize;
		s.nextStopIndex = nextStop;
		if (valid) {
			final PathData stopPath = t.path.get(nextStop);
			s.atStop = t.getSpeed() == 0 && stopPath.dwellTime > 0 && Math.abs(s.railProgress - distances.get(nextStop)) < 1.5;
			s.elapsedDwellTicks = t.getElapsedDwellTicks();
			if (s.atStop) {
				s.curPlatformId = stopPath.savedRailBaseId;
				final int total = stopPath.dwellTime * 10;
				s.dwellRemainTicks = Math.max(0, (int) (total - t.getElapsedDwellTicks()));
				s.dwelling = s.dwellRemainTicks > 0;
				s.curStationId = stationId(dc, stopPath);
				s.nextStationId = nextStationAfter(t, dc, nextStop + 1);
			} else {
				s.nextStationId = stationId(dc, stopPath);
			}
			s.prevStationId = previousStation(t, dc, nextStop - 1);
		}

		// --- ダイヤ(TimeSegment)から遅延計算用の理想時刻を求める ---
		if (s.onRoute && !s.manual) {
			final List<Siding.TimeSegment> segments = MtrFields.timeSegments(t);
			s.idealTicks = idealTicks(segments, s.railProgress, s.atStop, t.getElapsedDwellTicks());
			s.idealNextArrivalTicks = s.atStop ? Double.NaN : idealNextArrival(segments, s.railProgress);
			s.elapsedTicks = tracker.elapsed(s.id, true, nowTick, s.idealTicks);
			fillEtas(t, s, segments, depot, dc);
		} else {
			tracker.elapsed(s.id, false, nowTick, Double.NaN);
		}
		return s;
	}

	private static double idealTicks(List<Siding.TimeSegment> segments, double progress, boolean atStop, float elapsedDwell) {
		try {
			if (atStop) {
				// ホームに停車中: 到着予定 + 停車で経過した時間(停車時間も含めてダイヤ上の時間が進む)
				for (final Siding.TimeSegment seg : segments) {
					if (seg.savedRailBaseId != 0 && Math.abs(seg.endRailProgress - progress) < 1.5) {
						return seg.endTime + elapsedDwell;
					}
				}
				return Double.NaN;
			}
			for (final Siding.TimeSegment seg : segments) {
				if (progress >= seg.startRailProgress - 1e-6 && progress <= seg.endRailProgress + 1e-6) {
					final double v = seg.getTime(progress);
					return Double.isNaN(v) || Double.isInfinite(v) ? Double.NaN : v;
				}
			}
		} catch (RuntimeException ignored) {
		}
		return Double.NaN;
	}

	/** 到着予想を出す範囲(tick)。時刻表API用に2時間先まで(Webの列車詳細などは既定で20分先までに絞る) */
	private static final int ETA_HORIZON_TICKS = 2 * 3600 * 20;

	/**
	 * これから着く停車駅の、MTRの理論上の到着までの残り(tick)。経路の終わりまで(折り返し後の路線も含む)。
	 * 計算は MTR 3.2.2 の TrainServer.simulateTrain の発車標(ScheduleEntry)の作り方と同じ:
	 *   いまの位置のダイヤ上の時刻 currentTime と各停車区間の終わりの時刻 endTime の差。停車中は停車の経過分を引く。
	 *   「無限に繰り返す」車庫は2周目まで見る(MTRと同じく、周回の継ぎ目は最後の駅の時刻に合わせる)。
	 * 結果は etaIdealTicks に入れ、実績による補正(EtaPlanner.calibrate)はサーバー側で後から行う。
	 */
	private static void fillEtas(TrainServer t, TrainSample s, List<Siding.TimeSegment> segments, Depot depot, DataCache dc) {
		final int size = segments.size();
		if (size == 0) {
			return;
		}
		try {
			double currentTime = -1;
			int start = 0;
			for (final Siding.TimeSegment seg : segments) {
				if (RailwayData.isBetween(s.railProgress, seg.startRailProgress, seg.endRailProgress)) {
					currentTime = seg.getTime(s.railProgress);
					break;
				}
				start++;
			}
			if (currentTime < 0 || Double.isNaN(currentTime)) {
				return;
			}
			final double base = -s.elapsedDwellTicks;
			final boolean repeat = MtrFields.isRepeat(t);
			final List<long[]> found = new ArrayList<>();
			long[] pending = null;
			double offsetTime = 0;
			double offsetTimeTemp = 0;
			boolean secondRound = false;
			for (int i = start; i < size + (repeat ? size : 0) && found.size() < TrainSample.MAX_ETAS; i++) {
				final Siding.TimeSegment seg = segments.get(i % size);
				if (seg.savedRailBaseId != 0) {
					if (secondRound) {
						offsetTime = offsetTimeTemp - seg.endTime;
						secondRound = false;
						pending = null;
					} else if (pending != null) {
						found.add(pending);
						pending = null;
					}
					final double ticks = seg.endTime + offsetTime - currentTime + base;
					if (ticks > ETA_HORIZON_TICKS) {
						break;
					}
					final long[] e = {seg.savedRailBaseId, routeOf(t, seg, depot, dc), Math.round(ticks)};
					if (repeat) {
						pending = e;
					} else {
						found.add(e);
					}
					offsetTimeTemp = seg.endTime;
				}
				if (i == size - 1) {
					secondRound = true;
				}
			}
			if (pending != null && found.size() < TrainSample.MAX_ETAS) {
				found.add(pending);
			}
			final List<long[]> ok = new ArrayList<>();
			for (final long[] e : found) {
				// 停車中の駅(到着済み)は除く
				if (ok.isEmpty() && s.atStop && e[0] == s.curPlatformId && e[2] <= 0) {
					continue;
				}
				final Station st = dc.platformIdToStation.get(e[0]);
				if (st != null) {
					ok.add(new long[]{st.id, e[0], e[1], Math.max(0, e[2])});
				}
			}
			final int n = ok.size();
			s.etaStationIds = new long[n];
			s.etaPlatformIds = new long[n];
			s.etaRouteIds = new long[n];
			s.etaIdealTicks = new int[n];
			s.etaTicks = new int[n];
			s.etaSchedTicks = new int[n];
			for (int k = 0; k < n; k++) {
				final long[] e = ok.get(k);
				s.etaStationIds[k] = e[0];
				s.etaPlatformIds[k] = e[1];
				s.etaRouteIds[k] = e[2];
				s.etaIdealTicks[k] = (int) Math.min(Integer.MAX_VALUE, e[3]);
				s.etaTicks[k] = s.etaIdealTicks[k];
				s.etaSchedTicks[k] = TrainSample.NO_SCHED;
			}
		} catch (RuntimeException e) {
			logRateLimited("到着予想の計算に失敗 (id=" + t.id + ")", e);
		}
	}

	/** 停車区間が属する路線。MTRが列車処理で埋めた TimeSegment.routeId を優先し、無ければ経路の停車番号から引く(MTRと同じ方法)。 */
	private static long routeOf(TrainServer t, Siding.TimeSegment seg, Depot depot, DataCache dc) {
		if (seg.routeId != 0 || depot == null) {
			return seg.routeId;
		}
		final long[] out = {0};
		try {
			final int index = t.getIndex(seg.endRailProgress, true);
			if (index >= 0 && index < t.path.size()) {
				RailwayData.useRoutesAndStationsFromIndex(t.path.get(index).stopIndex - 1, depot.routeIds, dc, (currentStationIndex, thisRoute, nextRoute, thisStation, nextStation, lastStation) -> out[0] = thisRoute == null ? 0 : thisRoute.id);
			}
		} catch (RuntimeException ignored) {
		}
		return out[0];
	}

	private static double idealNextArrival(List<Siding.TimeSegment> segments, double progress) {
		for (final Siding.TimeSegment seg : segments) {
			if (seg.savedRailBaseId != 0 && seg.endRailProgress > progress + 0.5) {
				return seg.endTime;
			}
		}
		return Double.NaN;
	}

	private static long stationId(DataCache dc, PathData pd) {
		final Station st = dc.platformIdToStation.get(pd.savedRailBaseId);
		return st == null ? 0 : st.id;
	}

	private static long nextStationAfter(TrainServer t, DataCache dc, int from) {
		for (int i = from; i < t.path.size(); i++) {
			final PathData pd = t.path.get(i);
			if (pd.dwellTime > 0) {
				final long id = stationId(dc, pd);
				if (id != 0) {
					return id;
				}
			}
		}
		return 0;
	}

	private static long previousStation(TrainServer t, DataCache dc, int from) {
		for (int i = Math.min(from, t.path.size() - 1); i >= 0; i--) {
			final PathData pd = t.path.get(i);
			if (pd.dwellTime > 0) {
				final long id = stationId(dc, pd);
				if (id != 0) {
					return id;
				}
			}
		}
		return 0;
	}

	/** Train.getRoutePosition(private) と同じ計算: 進捗 → 該当レール → レール上の位置。 */
	private static Vec3 positionAt(TrainServer t, List<Double> distances, double progress) {
		final double p = Math.max(progress, 0);
		final int index = t.getIndex(p, false);
		if (index < 0 || index >= t.path.size() || index >= distances.size()) {
			return null;
		}
		final double base = index == 0 ? 0 : distances.get(index - 1);
		return t.path.get(index).rail.getPosition(p - base).add(0, t.transportMode.railOffset, 0);
	}

	private static void fillCarPositions(TrainSample s, TrainServer t, List<Double> distances) {
		final int n = Math.min(32, s.cars);
		final float[] xs = new float[n];
		final float[] zs = new float[n];
		final float[] yaws = new float[n];
		final double half = t.spacing / 2.0;
		for (int i = 0; i < n; i++) {
			final double center = s.railProgress - i * (double) t.spacing - half;
			final Vec3 front = positionAt(t, distances, center + half * 0.5);
			final Vec3 back = positionAt(t, distances, center - half * 0.5);
			final Vec3 mid = positionAt(t, distances, center);
			if (mid == null || front == null || back == null) {
				return;
			}
			xs[i] = (float) mid.x;
			zs[i] = (float) mid.z;
			yaws[i] = bearing(front, back);
		}
		s.carX = xs;
		s.carZ = zs;
		s.carYaw = yaws;
	}

	/** 進行方向(from → to)の方位。北(-Z)=0°, 東(+X)=90°。 */
	private static float bearing(Vec3 front, Vec3 back) {
		final double dx = front.x - back.x;
		final double dz = front.z - back.z;
		if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) {
			return Float.NaN;
		}
		double deg = Math.toDegrees(Math.atan2(dx, -dz));
		if (deg < 0) {
			deg += 360;
		}
		return (float) deg;
	}


	// ------------------------------------------------------------------ 駅間の距離・線形

	private static final double SAMPLE_STEP = 3.0;
	private static final double SIMPLIFY_TOLERANCE = 0.75;
	private static final int MAX_SECTION_POINTS = 400;

	/**
	 * まだ調べていない列車の経路(PathData)から、駅間の実距離と線形を取り出して out に足す。
	 * 駅の「ホーム」を通る連続したPathDataを1ノードとみなし、ノード間(=ホーム端〜ホーム端)を1区間にする。
	 * 1回の呼び出しで調べる列車数は maxTrains まで(サーバーtickの負荷を抑える)。
	 * @return 新しく区間を追加したか
	 */
	public static boolean collectGeometry(MinecraftServer server, Set<Long> scanned, java.util.Map<String, Geometry.Section> out, int maxTrains) {
		boolean added = false;
		int budget = maxTrains;
		for (final ServerLevel level : server.getAllLevels()) {
			final RailwayData rd = RailwayData.getInstance(level);
			if (rd == null) {
				continue;
			}
			for (final Siding siding : new ArrayList<>(rd.sidings)) {
				for (final TrainServer t : new ArrayList<>(MtrFields.trains(siding))) {
					if (budget <= 0) {
						return added;
					}
					if (scanned.contains(t.id)) {
						continue;
					}
					final List<Double> distances = MtrFields.distances(t);
					if (t.path.isEmpty() || distances.size() < t.path.size()) {
						continue;
					}
					scanned.add(t.id);
					budget--;
					try {
						added |= scanPath(t, distances, rd.dataCache, out);
					} catch (RuntimeException e) {
						logRateLimited("駅間の線形の取得に失敗 (id=" + t.id + ")", e);
					}
				}
			}
		}
		return added;
	}

	private static boolean scanPath(TrainServer t, List<Double> distances, DataCache dc, java.util.Map<String, Geometry.Section> out) {
		boolean added = false;
		long prevStation = 0;
		double prevEnd = 0;
		int prevEndIndex = -1;
		int i = 0;
		final int n = t.path.size();
		while (i < n) {
			final long st = stationId(dc, t.path.get(i));
			if (st == 0) {
				i++;
				continue;
			}
			int j = i;
			while (j + 1 < n && stationId(dc, t.path.get(j + 1)) == st) {
				j++;
			}
			final double end = distances.get(j);
			if (prevStation != 0 && prevStation != st && end > prevEnd) {
				final String key = prevStation + ">" + st;
				if (!out.containsKey(key) && end - prevEnd < 30000) {
					out.put(key, buildSection(t, distances, prevStation, st, prevEnd, end, prevEndIndex + 1, j));
					added = true;
				}
			}
			prevStation = st;
			prevEnd = end;
			prevEndIndex = j;
			i = j + 1;
		}
		return added;
	}

	private static Geometry.Section buildSection(TrainServer t, List<Double> distances, long from, long to, double start, double end, int firstIndex, int lastIndex) {
		final List<double[]> pts = new ArrayList<>();
		for (double p = start; p < end; p += SAMPLE_STEP) {
			addPoint(pts, positionAt(t, distances, p));
		}
		addPoint(pts, positionAt(t, distances, end - 0.01));
		final List<double[]> simple = simplify(pts, SIMPLIFY_TOLERANCE);
		final Geometry.Section sec = new Geometry.Section();
		sec.fromStationId = from;
		sec.toStationId = to;
		sec.distance = end - start;
		sec.mode = t.transportMode.toString().toLowerCase(java.util.Locale.ROOT);
		final int count = Math.min(MAX_SECTION_POINTS, simple.size());
		sec.points = new int[count * 3];
		for (int k = 0; k < count; k++) {
			// 上限を超えるときは等間隔に間引く(両端は残す)
			final double[] pt = simple.get(count == simple.size() ? k : (int) Math.round(k * (simple.size() - 1) / (double) (count - 1)));
			sec.points[k * 3] = (int) Math.round(pt[0]);
			sec.points[k * 3 + 1] = (int) Math.round(pt[1]);
			sec.points[k * 3 + 2] = (int) Math.round(pt[2]);
		}
		// レールの種別ごとの区切り(同じ種別が続くところはまとめる)
		final List<String> types = new ArrayList<>();
		final List<float[]> ranges = new ArrayList<>();
		for (int k = Math.max(0, firstIndex); k <= lastIndex && k < t.path.size() && k < distances.size(); k++) {
			final String type = t.path.get(k).rail.railType.toString();
			final float a = (float) Math.max(0, (k == 0 ? 0 : distances.get(k - 1)) - start);
			final float b = (float) Math.max(0, distances.get(k) - start);
			if (!types.isEmpty() && types.get(types.size() - 1).equals(type)) {
				ranges.get(ranges.size() - 1)[1] = b;
			} else {
				types.add(type);
				ranges.add(new float[]{a, b});
			}
		}
		sec.partTypes = types.toArray(new String[0]);
		sec.partFrom = new float[ranges.size()];
		sec.partTo = new float[ranges.size()];
		for (int k = 0; k < ranges.size(); k++) {
			sec.partFrom[k] = ranges.get(k)[0];
			sec.partTo[k] = ranges.get(k)[1];
		}
		return sec;
	}

	private static void addPoint(List<double[]> pts, Vec3 v) {
		if (v != null) {
			pts.add(new double[]{v.x, v.y, v.z});
		}
	}

	/** Douglas-Peucker(XZ平面)。 */
	private static List<double[]> simplify(List<double[]> pts, double tol) {
		if (pts.size() < 3) {
			return pts;
		}
		final boolean[] keep = new boolean[pts.size()];
		keep[0] = true;
		keep[pts.size() - 1] = true;
		final java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
		stack.push(new int[]{0, pts.size() - 1});
		while (!stack.isEmpty()) {
			final int[] r = stack.pop();
			double max = -1;
			int idx = -1;
			final double[] a = pts.get(r[0]);
			final double[] b = pts.get(r[1]);
			for (int k = r[0] + 1; k < r[1]; k++) {
				final double d = perpendicular(pts.get(k), a, b);
				if (d > max) {
					max = d;
					idx = k;
				}
			}
			if (max > tol && idx > 0) {
				keep[idx] = true;
				stack.push(new int[]{r[0], idx});
				stack.push(new int[]{idx, r[1]});
			}
		}
		final List<double[]> out = new ArrayList<>();
		for (int k = 0; k < pts.size(); k++) {
			if (keep[k]) {
				out.add(pts.get(k));
			}
		}
		return out;
	}

	private static double perpendicular(double[] p, double[] a, double[] b) {
		final double dx = b[0] - a[0];
		final double dz = b[2] - a[2];
		final double len = Math.hypot(dx, dz);
		if (len < 1e-9) {
			return Math.hypot(p[0] - a[0], p[2] - a[2]);
		}
		return Math.abs(dz * (p[0] - a[0]) - dx * (p[2] - a[2])) / len;
	}

	// ------------------------------------------------------------------ トポロジー

	public static Topology buildTopology(MinecraftServer server) {
		final Topology t = new Topology();
		final Set<Long> seenStations = new HashSet<>();
		for (final ServerLevel level : server.getAllLevels()) {
			final RailwayData rd = RailwayData.getInstance(level);
			if (rd == null) {
				continue;
			}
			final DataCache dc = rd.dataCache;
			final String dimension = level.dimension().location().toString();

			for (final Station st : rd.stations) {
				if (!seenStations.add(st.id)) {
					continue;
				}
				final Topology.Station o = new Topology.Station();
				o.id = st.id;
				o.name = st.name == null ? "" : st.name;
				final int[] c = center(st.corner1 == null ? null : new int[]{st.corner1.getA(), st.corner1.getB()}, st.corner2 == null ? null : new int[]{st.corner2.getA(), st.corner2.getB()});
				o.x = c[0];
				o.z = c[1];
				o.color = st.color;
				o.zone = st.zone;
				t.stations.add(o);
			}

			for (final Route r : rd.routes) {
				final Topology.Route o = new Topology.Route();
				o.id = r.id;
				o.name = r.name == null ? "" : r.name;
				o.number = r.isLightRailRoute && r.lightRailRouteNumber != null ? r.lightRailRouteNumber : "";
				o.color = r.color;
				o.type = r.routeType == null ? 0 : r.routeType.ordinal();
				o.circular = r.circularState == null ? 0 : r.circularState.ordinal();
				o.hidden = r.isHidden;
				o.mode = r.transportMode == null ? 0 : r.transportMode.ordinal();
				final List<Long> seq = new ArrayList<>();
				for (final Route.RoutePlatform rp : r.platformIds) {
					final Station st = dc.platformIdToStation.get(rp.platformId);
					if (st != null && (seq.isEmpty() || seq.get(seq.size() - 1) != st.id)) {
						seq.add(st.id);
					}
				}
				o.stationIds = new long[seq.size()];
				for (int i = 0; i < seq.size(); i++) {
					o.stationIds[i] = seq.get(i);
				}
				final String custom = r.platformIds.isEmpty() ? null : r.getDestination(r.platformIds.size() - 1);
				o.destination = custom == null ? "" : custom;
				t.routes.add(o);
			}

			for (final Depot d : rd.depots) {
				final Topology.Depot o = new Topology.Depot();
				o.id = d.id;
				o.name = d.name == null ? "" : d.name;
				o.color = d.color;
				final int[] c = center(d.corner1 == null ? null : new int[]{d.corner1.getA(), d.corner1.getB()}, d.corner2 == null ? null : new int[]{d.corner2.getA(), d.corner2.getB()});
				o.x = c[0];
				o.z = c[1];
				o.dimension = dimension;
				o.routeIds = new long[d.routeIds.size()];
				for (int i = 0; i < o.routeIds.length; i++) {
					o.routeIds[i] = d.routeIds.get(i);
				}
				t.depots.add(o);
			}

			for (final Siding sd : rd.sidings) {
				final Topology.Siding o = new Topology.Siding();
				o.id = sd.id;
				o.name = sd.name == null ? "" : sd.name;
				final Depot depot = dc.sidingIdToDepot.get(sd.id);
				o.depotId = depot == null ? 0 : depot.id;
				final BlockPos mid = sd.getMidPos();
				o.x = mid.getX();
				o.z = mid.getZ();
				o.trainType = sd.getTrainId();
				o.maxTrains = sd.getMaxTrains();
				o.unlimited = sd.getUnlimitedTrains();
				t.sidings.add(o);
			}
		}
		return t;
	}

	/** 車庫(Depot)に属する車庫線のID。車庫が存在しなければ null。 */
	public static Set<Long> sidingIdsOfDepot(MinecraftServer server, long depotId) {
		for (final ServerLevel level : server.getAllLevels()) {
			final RailwayData rd = RailwayData.getInstance(level);
			if (rd == null || !rd.dataCache.depotIdMap.containsKey(depotId)) {
				continue;
			}
			final Set<Long> ids = new HashSet<>();
			for (final Siding sd : rd.sidings) {
				final Depot d = rd.dataCache.sidingIdToDepot.get(sd.id);
				if (d != null && d.id == depotId) {
					ids.add(sd.id);
				}
			}
			return ids;
		}
		return null;
	}

	public static String dimensionOfDepot(MinecraftServer server, long depotId) {
		for (final ServerLevel level : server.getAllLevels()) {
			final RailwayData rd = RailwayData.getInstance(level);
			if (rd != null && rd.dataCache.depotIdMap.containsKey(depotId)) {
				return level.dimension().location().toString();
			}
		}
		return "";
	}

	/** MTRと同じ権限規則(クリエイティブ/サバイバルのみ。アドベンチャー・スペクテイターは不可)。 */
	public static boolean hasMtrEditPermission(net.minecraft.server.level.ServerPlayer player) {
		return !RailwayData.hasNoPermission(player);
	}

	private static int[] center(int[] a, int[] b) {
		if (a == null || b == null) {
			return new int[]{0, 0};
		}
		return new int[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2};
	}

	private static void logRateLimited(String message, Throwable e) {
		final long now = System.currentTimeMillis();
		if (now > errorLogCooldown) {
			errorLogCooldown = now + 30_000;
			LOGGER.warn("{}", message, e);
		}
	}
}
