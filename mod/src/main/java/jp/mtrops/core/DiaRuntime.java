package jp.mtrops.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * カスタムダイヤの実行時の状態(サーバー側)。MCに依存しない。
 *  ・どの列車がどの便か(出庫した時刻から決める。途中から見つけた列車は駅と時刻から推定)
 *  ・停車駅ごとの発車時刻と、遅れ
 * MTRの列車処理(サーバースレッド)からだけ呼ばれる想定。HTTPスレッドからは snapshot() で読む。
 */
public final class DiaRuntime {

	public static final class Assign {
		public long depotId;
		public int no;
		/** 最後に発車した停車駅(Profile.stops の番号)。-1 = まだ */
		public int lastStop = -1;
		/** 遅れ(秒)。正 = 遅れ、負 = 早着 */
		public int delay;
		public long lastSeenMillis;
	}

	/** 外から読むための写し。 */
	public static final class View {
		public long trainId;
		public long depotId;
		public int no;
		public int lastStop;
		public int delay;
	}

	private DiaModel.Config config = new DiaModel.Config();
	private Map<Long, DiaModel.Profile> profiles = new HashMap<>();
	private DiaPlanner.Result plan = new DiaPlanner.Result();
	private Set<Long> customDepots = new HashSet<>();
	/** pathIndex → 停車駅番号(車庫ごと) */
	private final Map<Long, Map<Integer, Integer>> stopOfPath = new HashMap<>();
	private final Map<Long, Assign> assigns = new HashMap<>();
	private final Map<String, Long> taken = new HashMap<>();

	public synchronized void update(DiaModel.Config cfg, Map<Long, DiaModel.Profile> profs, Map<Long, List<Long>> depotRoutes) {
		this.config = cfg;
		this.profiles = profs;
		final Set<Long> custom = new HashSet<>();
		for (final Map.Entry<Long, List<Long>> e : depotRoutes.entrySet()) {
			if (cfg.isDepotCustom(e.getValue())) {
				custom.add(e.getKey());
			}
		}
		this.customDepots = custom;
		final List<Long> list = new ArrayList<>(custom);
		Collections.sort(list);
		this.plan = DiaPlanner.plan(cfg, list, profs);
		stopOfPath.clear();
		for (final DiaModel.Profile p : profs.values()) {
			final Map<Integer, Integer> m = new HashMap<>();
			for (int i = 0; i < p.stops.size(); i++) {
				m.put(p.stops.get(i).pathIndex, i);
			}
			stopOfPath.put(p.depotId, m);
		}
		// 便の並びが変わったら割り当てをやり直す(便番号の意味が変わるため)
		assigns.clear();
		taken.clear();
	}

	public synchronized DiaPlanner.Result plan() {
		return plan;
	}

	public synchronized DiaModel.Profile profile(long depotId) {
		return profiles.get(depotId);
	}

	public synchronized DiaModel.Config config() {
		return config;
	}

	public synchronized boolean isCustomDepot(long depotId) {
		return customDepots.contains(depotId);
	}

	public synchronized Set<Long> customDepots() {
		return new HashSet<>(customDepots);
	}

	public synchronized DiaModel.RouteSetting route(long routeId) {
		return config.route(routeId);
	}

	/** 停車駅の番号。時刻表にない位置なら -1。 */
	public synchronized int stopOf(long depotId, int pathIndex) {
		final Map<Integer, Integer> m = stopOfPath.get(depotId);
		final Integer v = m == null ? null : m.get(pathIndex);
		return v == null ? -1 : v;
	}

	/** 出庫した列車に便を割り当てる(出庫予定 ±3分 で、まだ使われていない一番近い便)。 */
	public synchronized Assign onDeploy(long trainId, long depotId, int nowSec, long nowMillis) {
		release(trainId);
		final List<DiaModel.Run> runs = plan.runs.get(depotId);
		if (runs == null) {
			return null;
		}
		DiaModel.Run best = null;
		int bestDiff = 181;
		for (final DiaModel.Run r : runs) {
			final int d = Math.abs(DiaModel.wrapDiff(nowSec, r.depotDep));
			if (d < bestDiff && !taken.containsKey(r.key())) {
				best = r;
				bestDiff = d;
			}
		}
		return best == null ? null : take(trainId, best, -1, nowMillis);
	}

	/** 割り当てが無いまま走っている列車(再起動直後など)を、いま居る停車駅と時刻から推定する。 */
	public synchronized Assign guess(long trainId, long depotId, int stop, int nowSec, long nowMillis) {
		final Assign a = assigns.get(trainId);
		if (a != null) {
			return a;
		}
		final List<DiaModel.Run> runs = plan.runs.get(depotId);
		if (runs == null || stop < 0) {
			return null;
		}
		DiaModel.Run best = null;
		int bestDiff = 601;
		for (final DiaModel.Run r : runs) {
			if (stop >= r.dep.length || taken.containsKey(r.key())) {
				continue;
			}
			final int d = Math.abs(DiaModel.wrapDiff(nowSec, r.dep[stop]));
			if (d < bestDiff) {
				best = r;
				bestDiff = d;
			}
		}
		return best == null ? null : take(trainId, best, stop - 1, nowMillis);
	}

	private Assign take(long trainId, DiaModel.Run r, int lastStop, long nowMillis) {
		final Assign a = new Assign();
		a.depotId = r.depotId;
		a.no = r.no;
		a.lastStop = lastStop;
		a.lastSeenMillis = nowMillis;
		assigns.put(trainId, a);
		taken.put(r.key(), trainId);
		return a;
	}

	public synchronized Assign assignOf(long trainId) {
		return assigns.get(trainId);
	}

	public synchronized void release(long trainId) {
		final Assign a = assigns.remove(trainId);
		if (a != null) {
			taken.remove(a.depotId + "#" + a.no);
		}
	}

	/** その停車駅の発車時刻(秒)。便が無い/時刻表にない駅なら Integer.MIN_VALUE。 */
	public synchronized int departureOf(long trainId, int stop) {
		final Assign a = assigns.get(trainId);
		if (a == null || stop < 0) {
			return Integer.MIN_VALUE;
		}
		final DiaModel.Run r = plan.find(a.depotId, a.no);
		if (r == null || stop >= r.dep.length) {
			return Integer.MIN_VALUE;
		}
		return r.dep[stop];
	}

	public synchronized int arrivalOf(long trainId, int stop) {
		final Assign a = assigns.get(trainId);
		if (a == null || stop < 0) {
			return Integer.MIN_VALUE;
		}
		final DiaModel.Run r = plan.find(a.depotId, a.no);
		if (r == null || stop >= r.arr.length) {
			return Integer.MIN_VALUE;
		}
		return r.arr[stop];
	}

	/** 停車駅を発車した。遅れを記録する。 */
	public synchronized void onDepart(long trainId, int stop, int nowSec) {
		final Assign a = assigns.get(trainId);
		if (a == null || stop < 0) {
			return;
		}
		final int target = departureOf(trainId, stop);
		if (target != Integer.MIN_VALUE) {
			a.delay = DiaModel.wrapDiff(nowSec, target);
		}
		a.lastStop = Math.max(a.lastStop, stop);
	}

	/** 停車中・走行中に遅れを更新する(発車予定を過ぎても止まっている/到着予定を過ぎても着かない)。 */
	public synchronized void observe(long trainId, int nextStop, boolean stopped, int nowSec, long nowMillis) {
		final Assign a = assigns.get(trainId);
		if (a == null) {
			return;
		}
		a.lastSeenMillis = nowMillis;
		if (nextStop < 0) {
			return;
		}
		final int ref = stopped ? departureOf(trainId, nextStop) : arrivalOf(trainId, nextStop);
		if (ref != Integer.MIN_VALUE) {
			final int late = DiaModel.wrapDiff(nowSec, ref);
			if (late > a.delay) {
				a.delay = late;
			}
		}
	}

	public synchronized int delayOf(long trainId) {
		final Assign a = assigns.get(trainId);
		return a == null ? 0 : a.delay;
	}

	/** しばらく見ていない列車の割り当てを外す(削除された列車など)。 */
	public synchronized void sweep(long nowMillis, long maxAgeMillis) {
		final List<Long> gone = new ArrayList<>();
		for (final Map.Entry<Long, Assign> e : assigns.entrySet()) {
			if (nowMillis - e.getValue().lastSeenMillis > maxAgeMillis) {
				gone.add(e.getKey());
			}
		}
		gone.forEach(this::release);
	}

	public synchronized List<View> snapshot() {
		final List<View> out = new ArrayList<>();
		for (final Map.Entry<Long, Assign> e : assigns.entrySet()) {
			final View v = new View();
			v.trainId = e.getKey();
			v.depotId = e.getValue().depotId;
			v.no = e.getValue().no;
			v.lastStop = e.getValue().lastStop;
			v.delay = e.getValue().delay;
			out.add(v);
		}
		out.sort((x, y) -> Long.compare(x.trainId, y.trainId));
		return out;
	}

	// ------------------------------------------------------------------ 速度(保安装置・回復運転)の計算

	/**
	 * 保安装置で許される速度(ブロック/tick)。
	 * @param distance 前方の止まるべき地点(先行列車がいる閉塞の手前)までの距離。無ければ負
	 * @param decel    減速度(ブロック/tick^2)
	 */
	public static float safetyLimit(int safety, double distance, float decel, float lineSpeed) {
		if (safety == DiaModel.SAFETY_OFF || distance < 0) {
			return lineSpeed;
		}
		final double d = Math.max(0, distance - STOP_MARGIN);
		final float pattern = (float) Math.sqrt(2 * decel * d);
		if (safety == DiaModel.SAFETY_ATC) {
			return Math.max(Math.min(lineSpeed, pattern), CRAWL);
		}
		// ATS: パターン速度を段階(25/45/65/85/105 km/h)に丸める
		final float kmh = pattern * 72F;
		float step;
		if (kmh < 25) {
			step = 15;
		} else if (kmh < 45) {
			step = 25;
		} else if (kmh < 65) {
			step = 45;
		} else if (kmh < 85) {
			step = 65;
		} else if (kmh < 105) {
			step = 85;
		} else {
			return lineSpeed;
		}
		return Math.min(lineSpeed, step / 72F);
	}

	/** MTR Train.DOOR_DELAY / DOOR_MOVE_TIME と同じ値(tick) */
	public static final int DOOR_DELAY = 20;
	public static final int DOOR_MOVE_TIME = 64;

	/**
	 * 停車中の経過tick(Train.elapsedDwellTicks)を、発車時刻に合わせて調整した値を返す。
	 *  ・早いとき: ドアを開けたまま待ち、ちょうど発車時刻に total に達するよう経過を戻す
	 *  ・遅れているとき: ドアが開いて minOpenTicks 経ったら、すぐ閉め始める位置まで進める
	 * @param remainingTicks 発車時刻までの残り(tick)。負なら遅れ
	 */
	public static float adjustDwell(float elapsed, int totalTicks, int remainingTicks, int minOpenTicks) {
		if (totalTicks <= 2 * DOOR_DELAY + 2) {
			return elapsed;
		}
		final float maxDoorMove = Math.min(DOOR_MOVE_TIME, totalTicks / 2F - DOOR_DELAY);
		final float closeStart = totalTicks - DOOR_DELAY - maxDoorMove;
		// ちょうど発車時刻に total に達する経過
		final float want = totalTicks - (float) remainingTicks;
		if (elapsed > want) {
			// 早い: ドアを開けたまま待つ。開き始めた後なら、開いた状態(DOOR_DELAY+1)より前には戻さない
			final float floor = Math.min(elapsed, Math.min(DOOR_DELAY + 1, closeStart - 1));
			return Math.max(want, floor);
		}
		// 遅い(このままだと発車時刻を過ぎる): ドアが minOpenTicks 開いたら、閉め始める位置まで詰める
		final float openedFor = elapsed - DOOR_DELAY - maxDoorMove;
		if (openedFor >= minOpenTicks && elapsed < closeStart) {
			return Math.min(want, closeStart);
		}
		return elapsed;
	}

	/** 先行列車のいる閉塞の何ブロック手前を停止目標にするか(その先は這う速度で詰める) */
	public static final double STOP_MARGIN = 6;

	/** 這う速度(約3.6km/h)。止まる地点まで進めるように */
	public static final float CRAWL = 0.05F;

	/** 回復運転を入れたレール速度(ブロック/tick)。 */
	public static float recoverySpeed(float railSpeed, DiaModel.RouteSetting r, int delay) {
		if (r == null || r.overKmh <= 0 || delay < r.delayThreshold) {
			return railSpeed;
		}
		float v = railSpeed + r.overKmh / 72F;
		if (r.maxKmh > 0) {
			v = Math.min(v, r.maxKmh / 72F);
		}
		return Math.max(railSpeed, v);
	}
}
