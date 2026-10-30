package jp.mtrops.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 路線ごとの時刻表(DiaModel.Table)から、便ごとの各ホームの到着・発車時刻と、詰まり・待避の診断を作る。MC/MTRに依存しない。
 *
 * 発車時刻は利用者が決めた表のとおり(MTRの停車時間とは無関係)。MTRの走行時間(理想走行 + 実績の比率)は、
 *  ・表の初期値(各ホームの発車 = 前のホームの発車 + 走行 + 停車)
 *  ・到着時刻の見込み(診断・到着予想に使う)
 * にだけ使う。診断は表の時刻から次を調べる:
 *  ・ホームの重なり(同じホームに、前の列車が出る前に次の列車が着く / 通過する)
 *  ・駅間の追い越し(出る順と着く順が入れ替わる。追い越しは別のホーム(待避線)のある駅でだけできる)
 *  ・待避できるか(駅で出る順と着く順が入れ替わり、ホームが別)
 *  ・所要時間の不足(表の駅間の時間が、MTRの走行時間より短い)
 * サーバーとクライアントが同じ入力から同じ結果を得られるよう、乱数やHashMapの順序に依存しない。
 */
public final class DiaPlanner {

	/** 路線の停車ホームの並びと、MTRの走行時間。 */
	public static final class RouteInfo {
		public long routeId;
		public DiaModel.Profile profile;
		/** 停車するホーム(通過を除く)。stationIndex の順 */
		public final List<DiaModel.Stop> stops = new ArrayList<>();
		/** 前のホームの発車から、このホームの到着までの秒(MTRの理想走行 × 実績の比率)。[0] は 0 */
		public double[] estRun = new double[0];
		/** MTRの停車時間(秒)。表の初期値と、始発ホームへの到着時刻に使う */
		public double[] dwell = new double[0];
		/** 表に無いホームの既定の「始発の発車からの経過秒」 */
		public int[] defaultOffsets = new int[0];
		/** i番目の駅間(i-1 → i)で通過するホーム */
		public final List<List<DiaModel.Stop>> passes = new ArrayList<>();
		/** 出る順・着く順の比較に使う、通過も含めた駅の並び(stop または pass) */
		public final List<DiaModel.Stop> visits = new ArrayList<>();
	}

	public static final class Result {
		public final Map<Long, List<DiaModel.Run>> runs = new LinkedHashMap<>();
		public final Map<Long, RouteInfo> info = new LinkedHashMap<>();
		public final List<DiaModel.Issue> issues = new ArrayList<>();
		public final List<String> warnings = new ArrayList<>();

		public DiaModel.Run find(long routeId, int no) {
			final List<DiaModel.Run> list = runs.get(routeId);
			if (list == null || no < 1 || no > list.size()) {
				return null;
			}
			final DiaModel.Run r = list.get(no - 1);
			return r.no == no ? r : null;
		}

		/** 表の発車時刻(ホームの番号 → 始発の発車からの経過秒)。表に無いホームは既定値。 */
		public int offset(DiaModel.Config cfg, long routeId, int idx) {
			final RouteInfo ri = info.get(routeId);
			if (ri == null || idx < 0 || idx >= ri.defaultOffsets.length) {
				return 0;
			}
			return effectiveOffset(cfg.route(routeId), ri, idx);
		}
	}

	private DiaPlanner() {
	}

	// ------------------------------------------------------------------ 路線の情報

	/** 車庫の理想走行から、路線のホームの並びと走行時間を取り出す。複数の車庫にあるときは最初のもの。 */
	public static RouteInfo routeInfo(long routeId, Map<Long, DiaModel.Profile> profiles, Map<String, Double> ratios) {
		final List<Long> depots = new ArrayList<>(profiles.keySet());
		Collections.sort(depots);
		for (final Long d : depots) {
			final DiaModel.Profile prof = profiles.get(d);
			final RouteInfo ri = fromProfile(routeId, prof, ratios);
			if (ri != null) {
				return ri;
			}
		}
		return null;
	}

	private static RouteInfo fromProfile(long routeId, DiaModel.Profile prof, Map<String, Double> ratios) {
		if (prof == null) {
			return null;
		}
		final RouteInfo ri = new RouteInfo();
		ri.routeId = routeId;
		ri.profile = prof;
		int expect = 0;
		int lastPathIndex = -1;
		List<DiaModel.Stop> between = new ArrayList<>();
		boolean started = false;
		for (final DiaModel.Stop s : prof.stops) {
			if (s.routeId != routeId && !(s.pass && started)) {
				if (started && !s.pass) {
					break; // 別の路線へ移った
				}
				continue;
			}
			if (s.pass) {
				if (started) {
					between.add(s);
					ri.visits.add(s);
				}
				continue;
			}
			if (s.stationIndex != expect) {
				if (started) {
					break; // 同じ路線をもう一周(往復など)。最初の1回だけ使う
				}
				if (s.stationIndex != 0) {
					continue;
				}
			}
			started = true;
			ri.stops.add(s);
			ri.passes.add(between);
			between = new ArrayList<>();
			ri.visits.add(s);
			lastPathIndex = s.pathIndex;
			expect = ri.stops.size();
		}
		if (ri.stops.isEmpty()) {
			return null;
		}
		final int n = ri.stops.size();
		ri.estRun = new double[n];
		ri.dwell = new double[n];
		ri.defaultOffsets = new int[n];
		for (int i = 0; i < n; i++) {
			final DiaModel.Stop s = ri.stops.get(i);
			ri.dwell[i] = Math.max(0, s.dep - s.arr);
			if (i > 0) {
				final DiaModel.Stop p = ri.stops.get(i - 1);
				final Double r = ratios.get(SectionStats.key(p.stationId, s.stationId));
				ri.estRun[i] = Math.max(0, s.arr - p.dep) * (r == null ? 1 : r);
				ri.defaultOffsets[i] = (int) Math.round(ri.defaultOffsets[i - 1] + ri.estRun[i] + ri.dwell[i]);
			}
		}
		return ri;
	}

	static int effectiveOffset(DiaModel.RouteSetting rs, RouteInfo ri, int idx) {
		if (idx == 0) {
			return 0;
		}
		if (rs != null && rs.table != null) {
			final Integer v = rs.table.offsets.get(idx);
			if (v != null) {
				return v;
			}
		}
		return ri.defaultOffsets[idx];
	}

	// ------------------------------------------------------------------ 時刻表を作る

	/**
	 * @param profiles 車庫ごとの理想走行(全車庫。ダイヤ運転の路線を含む車庫だけでよい)
	 * @param ratios   駅間ごとの走行時間の比率(DiaStretch.ratios)。無ければ空
	 */
	public static Result plan(DiaModel.Config cfg, Map<Long, DiaModel.Profile> profiles, Map<String, Double> ratios) {
		final Result res = new Result();
		final List<Long> routeIds = new ArrayList<>();
		for (final DiaModel.RouteSetting rs : cfg.routes.values()) {
			if (rs.custom && rs.table != null) {
				routeIds.add(rs.routeId);
			}
		}
		Collections.sort(routeIds);
		for (final Long routeId : routeIds) {
			final DiaModel.RouteSetting rs = cfg.route(routeId);
			final RouteInfo ri = routeInfo(routeId, profiles, ratios);
			if (ri == null) {
				res.warnings.add("路線 " + routeId + ": 経路が取れないため時刻表を作れません(車庫に登録し、経路を生成してください)");
				continue;
			}
			res.info.put(routeId, ri);
			final int[] times = originTimes(rs.table);
			final int[] use = times.length > DiaModel.MAX_RUNS_PER_ROUTE ? java.util.Arrays.copyOf(times, DiaModel.MAX_RUNS_PER_ROUTE) : times;
			if (times.length > DiaModel.MAX_RUNS_PER_ROUTE) {
				res.warnings.add("路線 " + routeId + ": 便が多すぎるため先頭 " + DiaModel.MAX_RUNS_PER_ROUTE + " 便だけにしました");
			}
			res.runs.put(routeId, build(rs, ri, use));
		}
		diagnose(cfg, res);
		return res;
	}

	/** 始発ホームの発車時刻の並び(初便〜終便・時間帯ごとの間隔・追加・取りやめ)。 */
	static int[] originTimes(DiaModel.Table t) {
		final TreeSet<Integer> set = new TreeSet<>();
		final List<DiaModel.Period> periods = new ArrayList<>(t.periods);
		periods.sort(Comparator.comparingInt(p -> p.start));
		int time = t.first;
		int guard = 0;
		while (time <= t.last && guard++ < 20000) {
			final DiaModel.Period p = periodAt(periods, time);
			if (p == null) {
				final DiaModel.Period next = nextPeriod(periods, time);
				if (next == null) {
					break;
				}
				time = next.start;
				continue;
			}
			set.add(time);
			time += p.headway;
		}
		if (t.last >= t.first && !set.isEmpty() && !set.contains(t.last) && periodAt(periods, t.last) != null) {
			final Integer before = set.floor(t.last);
			if (before == null || t.last - before >= 60) {
				set.add(t.last); // 終便の時刻は必ず入れる
			}
		}
		set.addAll(t.extra);
		for (final Integer x : t.cancelled) {
			set.removeIf(v -> Math.abs(v - x) <= 1);
		}
		final int[] out = new int[set.size()];
		int i = 0;
		for (final Integer v : set) {
			out[i++] = v;
		}
		return out;
	}

	private static DiaModel.Period periodAt(List<DiaModel.Period> periods, int t) {
		DiaModel.Period hit = null;
		for (final DiaModel.Period p : periods) {
			if (t >= p.start && t < p.end) {
				hit = p; // 重なっていたら後に始まる時間帯を優先(ピークを上書きできるように)
			}
		}
		return hit;
	}

	private static DiaModel.Period nextPeriod(List<DiaModel.Period> periods, int t) {
		for (final DiaModel.Period p : periods) {
			if (p.start > t) {
				return p;
			}
		}
		return null;
	}

	private static List<DiaModel.Run> build(DiaModel.RouteSetting rs, RouteInfo ri, int[] origins) {
		final int n = ri.stops.size();
		final int[] off = new int[n];
		for (int i = 0; i < n; i++) {
			off[i] = effectiveOffset(rs, ri, i);
		}
		final List<DiaModel.Run> list = new ArrayList<>(origins.length);
		int no = 0;
		for (final int o : origins) {
			final DiaModel.Run r = new DiaModel.Run();
			r.routeId = ri.routeId;
			r.no = ++no;
			r.originDep = o;
			r.arr = new int[n];
			r.dep = new int[n];
			final List<Long> pp = new ArrayList<>();
			final List<Integer> pt = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				r.dep[i] = o + off[i];
				// 到着の見込み: 前の発車 + 走行(ただし発車より後にはならない)。始発は MTR の停車時間だけ前
				r.arr[i] = i == 0 ? r.dep[i] - (int) Math.round(Math.max(10, ri.dwell[i])) : Math.min(r.dep[i], (int) Math.round(r.dep[i - 1] + ri.estRun[i]));
				if (i > 0) {
					final double run = Math.max(1, ri.stops.get(i).arr - ri.stops.get(i - 1).dep);
					for (final DiaModel.Stop ps : ri.passes.get(i)) {
						final double frac = Math.max(0, Math.min(1, (ps.arr - ri.stops.get(i - 1).dep) / run));
						pp.add(ps.platformId);
						pt.add((int) Math.round(r.dep[i - 1] + frac * Math.max(0, r.arr[i] - r.dep[i - 1])));
					}
				}
			}
			r.passPlatform = new long[pp.size()];
			r.passTime = new int[pt.size()];
			for (int k = 0; k < pp.size(); k++) {
				r.passPlatform[k] = pp.get(k);
				r.passTime[k] = pt.get(k);
			}
			list.add(r);
		}
		return list;
	}

	// ------------------------------------------------------------------ 診断

	private static final class Visit {
		DiaModel.Run run;
		long routeId;
		long platformId;
		long stationId;
		String station;
		String platform;
		boolean pass;
		int arr;
		int dep;
	}

	private static String key(int sub, long a, long b, long station) {
		return sub + ":" + a + ":" + b + ":" + station;
	}

	private static void diagnose(DiaModel.Config cfg, Result res) {
		final Map<String, DiaModel.Issue> seen = new LinkedHashMap<>();
		// --- 所要時間の不足(表の駅間が、MTRの走行時間より短い)
		for (final RouteInfo ri : res.info.values()) {
			final DiaModel.RouteSetting rs = cfg.route(ri.routeId);
			for (int i = 1; i < ri.stops.size(); i++) {
				final int planned = effectiveOffset(rs, ri, i) - effectiveOffset(rs, ri, i - 1);
				final int need = (int) Math.ceil(ri.estRun[i]) + cfg.minOpen + 8;
				if (planned < need) {
					final DiaModel.Issue is = new DiaModel.Issue();
					is.kind = 1;
					is.sub = DiaModel.Issue.SHORT_TIME;
					is.stationId = ri.stops.get(i).stationId;
					is.station = ri.stops.get(i).station;
					is.platform = ri.stops.get(i).platform;
					is.routeA = ri.routeId;
					is.amount = need - planned;
					seen.put(key(is.sub, ri.routeId, 0, is.stationId), is);
				}
			}
		}
		// --- 各ホームの訪問(停車・通過)
		final Map<Long, List<Visit>> byPlatform = new LinkedHashMap<>();
		final Map<Long, Map<Long, List<Visit>>> byRouteStation = new LinkedHashMap<>();
		for (final Map.Entry<Long, List<DiaModel.Run>> e : res.runs.entrySet()) {
			final RouteInfo ri = res.info.get(e.getKey());
			for (final DiaModel.Run r : e.getValue()) {
				for (int i = 0; i < ri.stops.size(); i++) {
					final DiaModel.Stop s = ri.stops.get(i);
					add(byPlatform, byRouteStation, visit(r, s, false, r.arr[i], r.dep[i]));
					if (i > 0) {
						final List<DiaModel.Stop> ps = ri.passes.get(i);
						for (final DiaModel.Stop p : ps) {
							for (int k = 0; k < r.passPlatform.length; k++) {
								if (r.passPlatform[k] == p.platformId) {
									add(byPlatform, byRouteStation, visit(r, p, true, r.passTime[k], r.passTime[k]));
									break;
								}
							}
						}
					}
				}
			}
		}
		// --- ホームの重なり
		for (final List<Visit> list : byPlatform.values()) {
			list.sort(Comparator.<Visit>comparingInt(v -> v.arr).thenComparingLong(v -> v.routeId).thenComparingInt(v -> v.run.no));
			for (int i = 0; i < list.size(); i++) {
				final Visit a = list.get(i);
				for (int j = i + 1; j < list.size(); j++) {
					final Visit b = list.get(j);
					if (b.arr >= a.dep + cfg.sep) {
						break;
					}
					if (a.run == b.run) {
						continue;
					}
					final DiaModel.Issue is = new DiaModel.Issue();
					is.kind = 1;
					is.sub = a.routeId == b.routeId && !a.pass && !b.pass ? DiaModel.Issue.HEADWAY : DiaModel.Issue.PLATFORM;
					is.stationId = a.stationId;
					is.station = a.station;
					is.platform = a.platform;
					is.routeA = a.routeId;
					is.routeB = b.routeId;
					is.passA = a.pass;
					is.passB = b.pass;
					is.time = b.arr;
					is.amount = Math.max(1, a.dep + cfg.sep - b.arr);
					final String k = key(is.sub, a.routeId * 10 + (a.pass ? 1 : 0), b.routeId * 10 + (b.pass ? 1 : 0), a.platformId);
					final DiaModel.Issue old = seen.get(k);
					if (old == null) {
						seen.put(k, is);
					} else {
						old.more++;
					}
				}
			}
		}
		// --- 駅間の追い越し / 待避
		final List<Long> routeIds = new ArrayList<>(res.info.keySet());
		for (int x = 0; x < routeIds.size(); x++) {
			for (int y = x + 1; y < routeIds.size(); y++) {
				compareRoutes(cfg, res, routeIds.get(x), routeIds.get(y), seen);
			}
		}
		final List<DiaModel.Issue> out = new ArrayList<>(seen.values());
		out.sort(Comparator.<DiaModel.Issue>comparingInt(i -> -i.kind).thenComparingInt(i -> i.sub).thenComparingInt(i -> i.time));
		res.issues.addAll(out.size() > 300 ? out.subList(0, 300) : out);
	}

	private static Visit visit(DiaModel.Run r, DiaModel.Stop s, boolean pass, int arr, int dep) {
		final Visit v = new Visit();
		v.run = r;
		v.routeId = r.routeId;
		v.platformId = s.platformId;
		v.stationId = s.stationId;
		v.station = s.station;
		v.platform = s.platform;
		v.pass = pass;
		v.arr = arr;
		v.dep = dep;
		return v;
	}

	private static void add(Map<Long, List<Visit>> byPlatform, Map<Long, Map<Long, List<Visit>>> byRouteStation, Visit v) {
		byPlatform.computeIfAbsent(v.platformId, k -> new ArrayList<>()).add(v);
		byRouteStation.computeIfAbsent(v.routeId, k -> new HashMap<>()).computeIfAbsent(v.stationId, k -> new ArrayList<>()).add(v);
	}

	/**
	 * 2つの路線の便どうしを比べる。両方が通る駅 P → S(通過を含めた並びで隣り合う駅)で、
	 *  ・P を出る順と S に着く順が逆なら、駅間で追い越すことになる(線路は追い越せない)
	 *  ・駅 Q で「着く順」と「出る順」が逆で、ホームが別なら、待避できる(同じホームなら重なりとして別に出る)
	 */
	private static void compareRoutes(DiaModel.Config cfg, Result res, long ra, long rb, Map<String, DiaModel.Issue> seen) {
		final RouteInfo ia = res.info.get(ra);
		final RouteInfo ib = res.info.get(rb);
		final List<DiaModel.Run> runsA = res.runs.get(ra);
		final List<DiaModel.Run> runsB = res.runs.get(rb);
		if (runsA == null || runsB == null || runsA.isEmpty() || runsB.isEmpty()) {
			return;
		}
		final Map<Long, Integer> posB = new HashMap<>();
		for (int i = 0; i < ib.visits.size(); i++) {
			posB.putIfAbsent(ib.visits.get(i).stationId, i);
		}
		for (int i = 0; i < ia.visits.size(); i++) {
			final DiaModel.Stop q = ia.visits.get(i);
			final Integer qb = posB.get(q.stationId);
			if (qb == null) {
				continue;
			}
			// 駅 Q での入れ替わり(待避)
			final DiaModel.Stop qB = ib.visits.get(qb);
			final boolean sameQ = q.platformId == qB.platformId;
			final int[] ta = times(runsA, ia, q);
			final int[] tb = times(runsB, ib, qB);
			if (ta == null || tb == null) {
				continue;
			}
			final int na = runsA.size();
			final int nb = runsB.size();
			int jStart = 0;
			for (int a = 0; a < na; a++) {
				while (jStart < nb && tb[jStart * 2 + 1] < ta[a * 2] - 1800) {
					jStart++;
				}
				for (int b = jStart; b < nb && tb[b * 2] < ta[a * 2 + 1] + 1800; b++) {
					final int arrA = ta[a * 2], depA = ta[a * 2 + 1], arrB = tb[b * 2], depB = tb[b * 2 + 1];
					final boolean aWaits = arrA < arrB && depA > depB;     // A が先に着いて待ち、B が先に出る
					final boolean bWaits = arrB < arrA && depB > depA;
					if ((aWaits || bWaits) && !sameQ) {
						final DiaModel.Issue is = new DiaModel.Issue();
						is.kind = 0;
						is.sub = DiaModel.Issue.SIDING;
						is.stationId = q.stationId;
						is.station = q.station;
						is.platform = aWaits ? q.platform : qB.platform;
						is.routeA = aWaits ? ra : rb;      // 待避する側
						is.routeB = aWaits ? rb : ra;      // 先に行く側
						is.passB = aWaits ? isPass(ib, qB) : isPass(ia, q);
						is.time = aWaits ? arrA : arrB;
						final String k = key(is.sub, is.routeA, is.routeB, q.stationId);
						final DiaModel.Issue old = seen.get(k);
						if (old == null) {
							seen.put(k, is);
						} else {
							old.more++;
						}
					}
				}
			}
			// 次の共通の駅 S までの駅間
			for (int k = i + 1; k < ia.visits.size(); k++) {
				final DiaModel.Stop sA = ia.visits.get(k);
				final Integer sb = posB.get(sA.stationId);
				if (sb == null) {
					continue;
				}
				if (sb <= qb) {
					break; // 路線で並びが逆(別の向き)。比べない
				}
				final DiaModel.Stop sB = ib.visits.get(sb);
				final int[] sa = times(runsA, ia, sA);
				final int[] sbT = times(runsB, ib, sB);
				if (sa != null && sbT != null) {
					int j0 = 0;
					for (int a = 0; a < na; a++) {
						while (j0 < nb && tb[j0 * 2 + 1] < ta[a * 2 + 1] - 1800) {
							j0++;
						}
						for (int b = j0; b < nb && tb[b * 2 + 1] < ta[a * 2 + 1] + 1800; b++) {
							final int depA = ta[a * 2 + 1], depB = tb[b * 2 + 1], arrA = sa[a * 2], arrB = sbT[b * 2];
							if (depA == depB || arrA == arrB) {
								continue;
							}
							if ((depA < depB) != (arrA < arrB)) {
								final DiaModel.Issue is = new DiaModel.Issue();
								is.kind = 1;
								is.sub = DiaModel.Issue.OVERTAKE;
								is.stationId = q.stationId;
								is.station = q.station + "→" + sA.station;
								is.routeA = depA < depB ? ra : rb;   // 先に出る側(追い越される側)
								is.routeB = depA < depB ? rb : ra;
								is.time = Math.min(depA, depB);
								is.amount = Math.abs(arrA - arrB);
								final String key = key(is.sub, is.routeA, is.routeB, q.stationId);
								final DiaModel.Issue old = seen.get(key);
								if (old == null) {
									seen.put(key, is);
								} else {
									old.more++;
								}
							}
						}
					}
				}
				break;
			}
		}
	}

	private static boolean isPass(RouteInfo ri, DiaModel.Stop s) {
		return s.pass;
	}

	/** ある路線のすべての便について、駅 s の [到着, 発車](通過は同じ値)を並べた配列(便の順)。無ければ null。 */
	private static int[] times(List<DiaModel.Run> runs, RouteInfo ri, DiaModel.Stop s) {
		final int[] out = new int[runs.size() * 2];
		if (!s.pass) {
			final int idx = ri.stops.indexOf(s);
			if (idx < 0) {
				return null;
			}
			for (int k = 0; k < runs.size(); k++) {
				out[k * 2] = runs.get(k).arr[idx];
				out[k * 2 + 1] = runs.get(k).dep[idx];
			}
			return out;
		}
		for (int k = 0; k < runs.size(); k++) {
			final DiaModel.Run r = runs.get(k);
			boolean found = false;
			for (int m = 0; m < r.passPlatform.length; m++) {
				if (r.passPlatform[m] == s.platformId) {
					out[k * 2] = r.passTime[m];
					out[k * 2 + 1] = r.passTime[m];
					found = true;
					break;
				}
			}
			if (!found) {
				return null;
			}
		}
		return out;
	}
}
