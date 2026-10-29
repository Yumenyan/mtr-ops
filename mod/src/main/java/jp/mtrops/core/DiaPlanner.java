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
 * 設定(DiaModel.Config)と理想走行(Profile)から、車庫ごとの時刻表(Run)を作る。
 * サーバーとクライアントが同じ入力から同じ結果を得られるよう、乱数やHashMapの順序に依存しない。
 *
 * 手順:
 *  1. 基準駅の発車時刻を、初電〜終電・時間帯ごとの間隔・追加便・取りやめ便から並べる
 *  2. 理想走行時間と手動の追加停車から、各駅の到着・発車と出庫時刻を出す
 *  3. 自動の噛み合わせ(autoMesh)が有効な車庫は、同じホームを使う他の列車と重ならないよう全便のずらしを決める
 *  4. それでも同じホームで詰まる便は、手前の停車駅で待たせる(待避・抑止)。
 *     同じ線路(同じホーム)で前後していた列車は、次の共通の駅でも順番を変えない(追い越しは別ホームのある駅だけ)
 */
public final class DiaPlanner {

	public static final class Result {
		public final Map<Long, List<DiaModel.Run>> runs = new LinkedHashMap<>();
		/** 自動で決めたずらし(秒) */
		public final Map<Long, Integer> offsets = new LinkedHashMap<>();
		public final List<String> warnings = new ArrayList<>();

		public DiaModel.Run find(long depotId, int no) {
			final List<DiaModel.Run> list = runs.get(depotId);
			if (list == null || no < 1 || no > list.size()) {
				return null;
			}
			final DiaModel.Run r = list.get(no - 1);
			return r.no == no ? r : null;
		}
	}

	private DiaPlanner() {
	}

	/** 計算中だけ使う表: Run → 停車駅ごとのホームID・駅ID */
	private static final class Ctx {
		final Map<DiaModel.Run, long[]> platforms = new java.util.IdentityHashMap<>();
		final Map<DiaModel.Run, long[]> stations = new java.util.IdentityHashMap<>();

		void register(List<DiaModel.Run> runs, DiaModel.Profile prof) {
			final long[] pf = new long[prof.stops.size()];
			final long[] st = new long[prof.stops.size()];
			for (int i = 0; i < pf.length; i++) {
				pf[i] = prof.stops.get(i).platformId;
				st[i] = prof.stops.get(i).stationId;
			}
			for (final DiaModel.Run r : runs) {
				platforms.put(r, pf);
				stations.put(r, st);
			}
		}
	}

	/**
	 * @param customDepots カスタムダイヤで動かす車庫(路線設定から決まる)
	 * @param profiles     車庫ごとの理想走行
	 */
	public static Result plan(DiaModel.Config cfg, List<Long> customDepots, Map<Long, DiaModel.Profile> profiles) {
		final Result res = new Result();
		final List<Long> depots = new ArrayList<>(customDepots);
		Collections.sort(depots);

		// 1〜2: 基本の時刻表
		final Map<Long, int[]> anchors = new LinkedHashMap<>();
		for (final Long d : depots) {
			final DiaModel.DepotPlan plan = cfg.plans.get(d);
			final DiaModel.Profile prof = profiles.get(d);
			if (plan == null || prof == null || prof.stops.isEmpty()) {
				if (plan != null && (prof == null || prof.stops.isEmpty())) {
					res.warnings.add("車庫 " + d + ": 経路の停車駅が取れないため時刻表を作れません(経路生成を確認してください)");
				}
				continue;
			}
			final int[] times = anchorTimes(plan);
			if (times.length > DiaModel.MAX_RUNS_PER_DEPOT) {
				res.warnings.add("車庫 " + d + ": 便数が多すぎるため先頭 " + DiaModel.MAX_RUNS_PER_DEPOT + " 便だけにしました");
			}
			anchors.put(d, times.length > DiaModel.MAX_RUNS_PER_DEPOT ? java.util.Arrays.copyOf(times, DiaModel.MAX_RUNS_PER_DEPOT) : times);
		}

		// 3: 自動のずらし(便の多い車庫から順に固定していく)
		final List<Long> order = new ArrayList<>(anchors.keySet());
		order.sort(Comparator.<Long>comparingInt(d -> -anchors.get(d).length).thenComparing(d -> d));
		final List<DiaModel.Run> fixed = new ArrayList<>();
		final Ctx ctx = new Ctx();
		for (final Long d : order) {
			final DiaModel.DepotPlan plan = cfg.plans.get(d);
			final DiaModel.Profile prof = profiles.get(d);
			int offset = plan.offset;
			if (plan.autoMesh && !fixed.isEmpty()) {
				offset = bestOffset(cfg, plan, prof, anchors.get(d), fixed, ctx);
			}
			res.offsets.put(d, offset);
			final List<DiaModel.Run> list = build(plan, prof, anchors.get(d), offset, cfg.slackSec, cfg.slackPct);
			ctx.register(list, prof);
			res.runs.put(d, list);
			fixed.addAll(list);
		}
		// 出力の並びは車庫ID順に
		final Map<Long, List<DiaModel.Run>> sorted = new LinkedHashMap<>();
		for (final Long d : depots) {
			if (res.runs.containsKey(d)) {
				sorted.put(d, res.runs.get(d));
			}
		}
		res.runs.clear();
		res.runs.putAll(sorted);

		// 4: 待避・抑止
		resolve(cfg, res, profiles, ctx);
		return res;
	}

	// ------------------------------------------------------------------ 1. 基準駅の発車時刻

	static int[] anchorTimes(DiaModel.DepotPlan plan) {
		final TreeSet<Integer> set = new TreeSet<>();
		final List<DiaModel.Period> periods = new ArrayList<>(plan.periods);
		periods.sort(Comparator.comparingInt(p -> p.start));
		int t = plan.first;
		int guard = 0;
		while (t <= plan.last && guard++ < 20000) {
			final DiaModel.Period p = periodAt(periods, t);
			if (p == null) {
				// 時間帯の外 → 次の時間帯の始まりへ
				final DiaModel.Period next = nextPeriod(periods, t);
				if (next == null) {
					break;
				}
				t = next.start;
				continue;
			}
			set.add(t);
			t += p.headway;
		}
		if (plan.last >= plan.first && !set.isEmpty() && !set.contains(plan.last) && periodAt(periods, plan.last) != null) {
			// 終電の時刻は必ず入れる(間隔の途中なら直前の便との間が詰まるだけ)
			final Integer before = set.floor(plan.last);
			if (before == null || plan.last - before >= 60) {
				set.add(plan.last);
			}
		}
		for (final Integer x : plan.extra) {
			set.add(x);
		}
		for (final Integer x : plan.cancelled) {
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

	// ------------------------------------------------------------------ 2. 時刻表を作る

	static List<DiaModel.Run> build(DiaModel.DepotPlan plan, DiaModel.Profile prof, int[] anchors, int offset) {
		return build(plan, prof, anchors, offset, 0, 0);
	}

	static List<DiaModel.Run> build(DiaModel.DepotPlan plan, DiaModel.Profile prof, int[] anchors, int offset, int slackSec, int slackPct) {
		final int n = prof.stops.size();
		final int anchor = Math.max(0, Math.min(n - 1, plan.anchor));
		// 手動の追加停車を足した、車庫を出てからの到着・発車(秒、四捨五入)
		final double[] arr = new double[n];
		final double[] dep = new double[n];
		final int[] hold = new int[n];
		double shift = 0;
		double lastDep = 0;
		for (int i = 0; i < n; i++) {
			final DiaModel.Stop s = prof.stops.get(i);
			hold[i] = s.pass ? 0 : Math.max(0, plan.holds.getOrDefault(i, 0));
			if (!s.pass) {
				// 余裕時分: 前の停車駅(最初は車庫)からの走行時間に応じて足す
				shift += slackSec + Math.max(0, s.arr - lastDep) * slackPct / 100.0;
				lastDep = s.dep;
			}
			arr[i] = s.arr + shift;
			shift += hold[i];
			dep[i] = s.dep + shift;
		}
		final List<DiaModel.Run> list = new ArrayList<>(anchors.length);
		int no = 0;
		for (final int a0 : anchors) {
			final int a = a0 + offset;
			final DiaModel.Run r = new DiaModel.Run();
			r.depotId = plan.depotId;
			r.no = ++no;
			r.arr = new int[n];
			r.dep = new int[n];
			r.hold = hold.clone();
			final double base = a - dep[anchor];
			for (int i = 0; i < n; i++) {
				r.arr[i] = (int) Math.round(base + arr[i]);
				r.dep[i] = (int) Math.round(base + dep[i]);
			}
			r.dep[anchor] = a; // 基準駅は指定どおり
			r.depotDep = (int) Math.floor(base) - plan.lead;
			list.add(r);
		}
		return list;
	}

	// ------------------------------------------------------------------ 3. 自動のずらし

	private static int bestOffset(DiaModel.Config cfg, DiaModel.DepotPlan plan, DiaModel.Profile prof, int[] anchors, List<DiaModel.Run> fixed, Ctx ctx) {
		// ずらしの探索範囲: この車庫の最大の運転間隔まで(5秒刻み)
		int maxHw = 60;
		for (final DiaModel.Period p : plan.periods) {
			maxHw = Math.max(maxHw, p.headway);
		}
		maxHw = Math.min(maxHw, 3600);
		final Map<Long, List<int[]>> occ = occupancy(fixed, ctx);
		int best = plan.offset;
		long bestScore = Long.MAX_VALUE;
		for (int o = 0; o < maxHw; o += 5) {
			final int cand = plan.offset + o;
			final List<DiaModel.Run> runs = build(plan, prof, anchors, cand, cfg.slackSec, cfg.slackPct);
			long over = 0;
			int minGap = maxHw;
			for (final DiaModel.Run r : runs) {
				for (int i = 0; i < r.arr.length; i++) {
					final List<int[]> list = occ.get(prof.stops.get(i).platformId);
					if (list != null) {
						over += overlap(list, r.arr[i] - cfg.sep, r.dep[i] + cfg.sep);
						minGap = Math.min(minGap, gap(list, r.arr[i], r.dep[i]));
					}
				}
			}
			// 重なりが少ないこと > 一番近い列車との間隔が広いこと > ずらしが小さいこと
			final long score = over * 10_000_000L + (long) (maxHw - minGap) * 10_000L + o;
			if (score < bestScore) {
				bestScore = score;
				best = cand;
			}
		}
		return best;
	}

	/** 区間 [a,b] と、並べた区間リストのうち一番近いものとの間隔(重なっていれば0)。 */
	private static int gap(List<int[]> sorted, int a, int b) {
		int lo = 0;
		int hi = sorted.size();
		while (lo < hi) {
			final int mid = (lo + hi) >>> 1;
			if (sorted.get(mid)[0] < a) {
				lo = mid + 1;
			} else {
				hi = mid;
			}
		}
		int g = Integer.MAX_VALUE;
		for (int k = Math.max(0, lo - 3); k < Math.min(sorted.size(), lo + 2); k++) {
			final int[] x = sorted.get(k);
			final int d = x[1] < a ? a - x[1] : x[0] > b ? x[0] - b : 0;
			g = Math.min(g, d);
		}
		return g;
	}

	private static Map<Long, List<int[]>> occupancy(List<DiaModel.Run> runs, Ctx ctx) {
		final Map<Long, List<int[]>> map = new HashMap<>();
		for (final DiaModel.Run r : runs) {
			final long[] pf = ctx.platforms.get(r);
			if (pf == null) {
				continue;
			}
			for (int i = 0; i < r.arr.length; i++) {
				map.computeIfAbsent(pf[i], k -> new ArrayList<>()).add(new int[]{r.arr[i], r.dep[i]});
			}
		}
		for (final List<int[]> l : map.values()) {
			l.sort(Comparator.comparingInt(x -> x[0]));
		}
		return map;
	}

	private static long overlap(List<int[]> sorted, int from, int to) {
		long sum = 0;
		int lo = 0;
		int hi = sorted.size();
		while (lo < hi) {
			final int mid = (lo + hi) >>> 1;
			if (sorted.get(mid)[0] < from - 3600) {
				lo = mid + 1;
			} else {
				hi = mid;
			}
		}
		for (int i = lo; i < sorted.size(); i++) {
			final int[] x = sorted.get(i);
			if (x[0] > to) {
				break;
			}
			final int a = Math.max(from, x[0]);
			final int b = Math.min(to, x[1]);
			if (b > a) {
				sum += b - a;
			}
		}
		return sum;
	}

	// ------------------------------------------------------------------ 4. 待避・抑止

	private static final class Visit {
		final DiaModel.Run run;
		final int i;
		final long platform;

		Visit(DiaModel.Run run, int i, long platform) {
			this.run = run;
			this.i = i;
			this.platform = platform;
		}

		int arr() {
			return run.arr[i];
		}

		int dep() {
			return run.dep[i];
		}
	}

	private static void resolve(DiaModel.Config cfg, Result res, Map<Long, DiaModel.Profile> profiles, Ctx ctx) {
		final Map<Long, List<Visit>> byPlatform = new HashMap<>();
		final Map<DiaModel.Run, DiaModel.Profile> profOf = new java.util.IdentityHashMap<>();
		for (final Map.Entry<Long, List<DiaModel.Run>> e : res.runs.entrySet()) {
			final DiaModel.Profile prof = profiles.get(e.getKey());
			for (final DiaModel.Run r : e.getValue()) {
				profOf.put(r, prof);
				for (int i = 0; i < r.arr.length; i++) {
					final long pf = prof.stops.get(i).platformId;
					byPlatform.computeIfAbsent(pf, k -> new ArrayList<>()).add(new Visit(r, i, pf));
				}
			}
		}
		if (byPlatform.isEmpty()) {
			return;
		}
		final List<Long> platforms = new ArrayList<>(byPlatform.keySet());
		Collections.sort(platforms);
		final Map<DiaModel.Run, Integer> added = new java.util.IdentityHashMap<>();
		final Comparator<Visit> byArr = Comparator.comparingInt(Visit::arr).thenComparingLong(v -> v.run.depotId).thenComparingInt(v -> v.run.no);
		final Comparator<Visit> byDep = Comparator.comparingInt(Visit::dep).thenComparingLong(v -> v.run.depotId).thenComparingInt(v -> v.run.no);

		boolean changed = true;
		int pass = 0;
		while (changed && pass++ < 60) {
			changed = false;
			for (final Long pf : platforms) {
				final List<Visit> list = byPlatform.get(pf);
				// (a) 同じホーム: 前の列車が出てから sep 秒あけて次が入る
				list.sort(byArr);
				for (int k = 1; k < list.size(); k++) {
					final Visit first = list.get(k - 1);
					final Visit second = list.get(k);
					if (first.run != second.run) {
						changed |= push(cfg, added, profOf, second, first.dep() + cfg.sep - second.arr());
					}
				}
				// (b) 同じホームから続けて出た列車は、次の共通の駅まで同じ線路を走るので追い越せない
				list.sort(byDep);
				for (int k = 1; k < list.size(); k++) {
					final Visit lead = list.get(k - 1);
					final Visit follow = list.get(k);
					if (lead.run == follow.run) {
						continue;
					}
					final int[] next = nextCommon(lead, follow, ctx);
					if (next != null) {
						final int need = lead.run.arr[next[0]] + cfg.sep / 2 - follow.run.arr[next[1]];
						changed |= push(cfg, added, profOf, new Visit(follow.run, next[1], 0), need);
					}
				}
			}
		}
		// 解消できなかった重なりを警告にする
		int conflicts = 0;
		String sample = null;
		for (final Long pf : platforms) {
			final List<Visit> list = byPlatform.get(pf);
			list.sort(byArr);
			for (int k = 1; k < list.size(); k++) {
				final Visit a = list.get(k - 1);
				final Visit b = list.get(k);
				if (a.run != b.run && b.arr() < a.dep() + cfg.sep) {
					conflicts++;
					if (sample == null) {
						final DiaModel.Stop s = profOf.get(b.run).stops.get(b.i);
						sample = s.station + (s.platform.isEmpty() ? "" : " " + s.platform + "番線") + " " + DiaModel.hms(b.arr())
								+ "(車庫" + a.run.depotId + "の" + a.run.no + "便と車庫" + b.run.depotId + "の" + b.run.no + "便)";
					}
				}
			}
		}
		if (conflicts > 0) {
			res.warnings.add("同じホームで列車が詰まる箇所が " + conflicts + " か所あります。例: " + sample + "。間隔を広げるか、待避の上限(maxHold)を見直してください");
		}
	}

	/** v の駅へ着くのを need 秒遅らせる(手前の停車駅で待たせる)。 */
	private static boolean push(DiaModel.Config cfg, Map<DiaModel.Run, Integer> added, Map<DiaModel.Run, DiaModel.Profile> profOf, Visit v, int need) {
		if (need <= 0) {
			return false;
		}
		final int total = added.getOrDefault(v.run, 0) + need;
		if (total > cfg.maxHold) {
			return false;
		}
		// 手前の「止まる」駅を探す(通過駅では待てない)
		final DiaModel.Profile prof = profOf.get(v.run);
		int at = v.i - 1;
		while (at >= 0 && prof.stops.get(at).pass) {
			at--;
		}
		added.put(v.run, total);
		delay(v.run, at, need);
		return true;
	}

	/** lead と follow が lead.i / follow.i の後で次に同じ駅を通る位置 [leadの番号, followの番号]。無ければ null。 */
	private static int[] nextCommon(Visit lead, Visit follow, Ctx ctx) {
		final long[] sa = ctx.stations.get(lead.run);
		final long[] sb = ctx.stations.get(follow.run);
		if (sa == null || sb == null) {
			return null;
		}
		final int limA = Math.min(sa.length, lead.i + 40);
		final int limB = Math.min(sb.length, follow.i + 40);
		for (int j = follow.i + 1; j < limB; j++) {
			for (int i = lead.i + 1; i < limA; i++) {
				if (sa[i] == sb[j]) {
					return new int[]{i, j};
				}
			}
		}
		return null;
	}

	/** at 番目の停車駅で need 秒待たせる(以降の時刻を後ろへ)。at<0 なら出庫から遅らせる。 */
	private static void delay(DiaModel.Run r, int at, int need) {
		if (at < 0) {
			r.depotDep += need;
			for (int i = 0; i < r.arr.length; i++) {
				r.arr[i] += need;
				r.dep[i] += need;
			}
			return;
		}
		r.hold[at] += need;
		r.dep[at] += need;
		for (int i = at + 1; i < r.arr.length; i++) {
			r.arr[i] += need;
			r.dep[i] += need;
		}
	}
}
