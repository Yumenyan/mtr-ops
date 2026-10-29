package jp.mtrops.adapter;

import jp.mtrops.core.DiaModel;
import jp.mtrops.core.DiaRuntime;
import mtr.data.Train;
import mtr.data.TrainServer;
import mtr.path.PathData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MTRの列車処理に差し込む処理(jp.mtrops.mixin から呼ばれる)。
 * カスタムダイヤの車庫の列車(サーバー側)だけを扱い、それ以外は MTR の動きをそのまま返す。
 * 例外は絶対に外へ出さない(MTRの列車処理を止めないため)。
 */
public final class DiaHooks {

	/** サーバー側の状態(DiaServer が実装)。 */
	public interface Context {
		long depotOfSiding(long sidingId);

		DiaRuntime runtime();

		/** 列車が今走っている路線、無ければ車庫の最初のカスタム路線の設定 */
		DiaModel.RouteSetting setting(long routeId, long depotId);

		/** サーバーのタイムゾーンでの、その日の0時からの秒(小数あり) */
		double nowSec();

		int minOpenTicks();

		long currentTick();

		/** カスタムダイヤの車庫の出庫時刻(UTCのその日の0時からのミリ秒、MTRの Depot.tempDepartures と同じ形)。カスタムでなければ null */
		List<Integer> departuresMillis(long depotId);
	}

	/** ATC/ATS のパターンに使う減速度(列車の加減速度に対する割合)。常用ブレーキ相当で早めに減速し始める */
	private static final float ATC_DECEL = 0.6F;

	/** 速度・停車を変えた列車をクライアントへ同期する間隔(tick)。長いと、クライアントの予測との差が溜まって巻き戻りに見える */
	private static final int SYNC_TICKS = 4;

	private static volatile Context context;
	/** フックが一度でも呼ばれたか(ミックスインが効いているかの確認用) */
	public static volatile boolean speedHookSeen;
	public static volatile boolean dwellHookSeen;

	private static final class State {
		boolean onRoute;
		int stoppedAt = -1;
		long stoppedPlatform;
		boolean adjusted;
		boolean limited;
		long lastSync;
		long lastTouch;
	}

	private static final Map<Long, State> STATES = new HashMap<>();

	private DiaHooks() {
	}

	public static void setContext(Context c) {
		context = c;
		synchronized (STATES) {
			STATES.clear();
		}
	}

	private static State state(long id, long tick) {
		synchronized (STATES) {
			final State s = STATES.computeIfAbsent(id, k -> new State());
			s.lastTouch = tick;
			return s;
		}
	}

	/** しばらく処理されていない列車の状態を捨てる。 */
	public static void sweep(long tick) {
		synchronized (STATES) {
			STATES.values().removeIf(s -> tick - s.lastTouch > 1200);
		}
	}

	// ------------------------------------------------------------------ 出庫時刻(Depot.generateTempDepartures の最後)

	/** カスタムダイヤの車庫だけ、作業用の出庫時刻を時刻表のものに置き換える。保存される設定(departures 等)は触らない。 */
	public static void overrideDepartures(mtr.data.Depot depot) {
		final Context c = context;
		if (c == null || depot == null) {
			return;
		}
		try {
			final List<Integer> ms = c.departuresMillis(depot.id);
			if (ms != null && !ms.isEmpty()) {
				depot.tempDepartures.clear();
				depot.tempDepartures.addAll(ms);
				depot.tempDepartures.sort(Integer::compareTo);
			}
		} catch (RuntimeException ignored) {
		}
	}

	// ------------------------------------------------------------------ 速度(Train.simulateTrain の getRailSpeed を置き換え)

	public static float railSpeed(Train train, int railIndex) {
		final float original = train.getRailSpeed(railIndex);
		speedHookSeen = true;
		final Context c = context;
		if (c == null || !(train instanceof TrainServer)) {
			return original;
		}
		try {
			final TrainServer t = (TrainServer) train;
			if (t.isCurrentlyManual()) {
				return original;
			}
			final long depot = c.depotOfSiding(t.sidingId);
			if (depot == 0 || !c.runtime().isCustomDepot(depot)) {
				return original;
			}
			final DiaModel.RouteSetting rs = c.setting(MtrFields.routeId(t), depot);
			float v = DiaRuntime.recoverySpeed(original, rs, c.runtime().delayOf(t.id));
			final int safety = rs == null ? DiaModel.SAFETY_ATC : rs.safety;
			if (safety != DiaModel.SAFETY_OFF) {
				final double d = obstacleDistance(t);
				v = DiaRuntime.safetyLimit(safety, d, t.accelerationConstant * ATC_DECEL, v);
			}
			state(t.id, c.currentTick()).limited = v < original - 1e-4F || v > original + 1e-4F;
			return v;
		} catch (RuntimeException e) {
			return original;
		}
	}

	/** 前方で先行列車が在線している閉塞の手前までの距離(ブロック)。見通し内に無ければ -1。 */
	private static double obstacleDistance(TrainServer t) {
		final List<Map<UUID, Long>> positions = MtrFields.trainPositions(t);
		if (positions == null) {
			return -1;
		}
		final List<PathData> path = t.path;
		final List<Double> dist = MtrFields.distances(t);
		if (path.isEmpty() || dist.size() < path.size()) {
			return -1;
		}
		final double progress = t.getRailProgress();
		final float a = Math.max(t.accelerationConstant * ATC_DECEL, 1e-4F);
		final float v = t.getSpeed();
		final double look = Math.min(800, v * v / (2 * a) + 64);
		final int head = t.getIndex(0, t.spacing, true);
		for (int j = Math.max(1, head + 1); j < path.size(); j++) {
			final double start = dist.get(j - 1);
			if (start - progress > look) {
				break;
			}
			final UUID product = path.get(j).getRailProduct();
			for (final Map<UUID, Long> m : positions) {
				final Long owner = m.get(product);
				if (owner != null && owner != t.id) {
					return Math.max(0, start - progress);
				}
			}
		}
		return -1;
	}

	// ------------------------------------------------------------------ 停車(Train.simulateTrain の最後)

	public static void afterSimulate(Train train) {
		dwellHookSeen = true;
		final Context c = context;
		if (c == null || !(train instanceof TrainServer)) {
			return;
		}
		try {
			after(c, (TrainServer) train);
		} catch (RuntimeException ignored) {
		}
	}

	private static void after(Context c, TrainServer t) {
		final long depot = c.depotOfSiding(t.sidingId);
		if (depot == 0 || !c.runtime().isCustomDepot(depot)) {
			return;
		}
		final DiaRuntime rt = c.runtime();
		final long tick = c.currentTick();
		final State st = state(t.id, tick);
		final double now = c.nowSec();
		final long millis = System.currentTimeMillis();
		final boolean onRoute = t.isOnRoute();
		if (!onRoute) {
			if (st.onRoute) {
				rt.release(t.id);
			}
			st.onRoute = false;
			st.stoppedAt = -1;
			st.adjusted = false;
			return;
		}
		if (!st.onRoute) {
			st.onRoute = true;
			rt.onDeploy(t.id, depot, (int) Math.floor(now), millis);
		}
		final List<PathData> path = t.path;
		final int nsi = MtrFields.nextStoppingIndex(t);
		if (nsi < 0 || nsi >= path.size()) {
			return;
		}
		final List<Double> dist = MtrFields.distances(t);
		final PathData pd = path.get(nsi);
		final int stop = rt.stopOf(depot, nsi);
		if (stop >= 0 && rt.assignOf(t.id) == null) {
			rt.guess(t.id, depot, stop, (int) Math.floor(now), millis);
		}
		final boolean stopped = t.getSpeed() == 0;
		final boolean atPlatform = stopped && pd.dwellTime > 0 && dist.size() > nsi && Math.abs(t.getRailProgress() - dist.get(nsi)) < 1.5;
		st.adjusted = false;
		if (atPlatform && stop >= 0 && !t.isCurrentlyManual()) {
			final int target = rt.departureOf(t.id, stop);
			if (target != Integer.MIN_VALUE) {
				double diff = target - now;
				diff = ((diff % DiaModel.DAY) + DiaModel.DAY + DiaModel.DAY / 2.0) % DiaModel.DAY - DiaModel.DAY / 2.0;
				int remaining = (int) Math.round(diff * 20);
				// 前の列車が出てから最低限の間隔(同じホームの最小間隔 + 20秒)をあける。遅れて追いついても詰まらないように自動で待たせる
				remaining = Math.max(remaining, rt.gapRemainingTicks(pd.savedRailBaseId, rt.config().sep + 20, millis));
				final float e = MtrFields.elapsedDwellTicks(t);
				final float ne = DiaRuntime.adjustDwell(e, pd.dwellTime * 10, remaining, c.minOpenTicks());
				if (Math.abs(ne - e) > 1e-3F) {
					MtrFields.setElapsedDwellTicks(t, ne);
					st.adjusted = true;
				}
			}
			st.stoppedAt = stop;
			st.stoppedPlatform = pd.savedRailBaseId;
		} else if (!stopped && st.stoppedAt >= 0) {
			rt.onDepart(t.id, st.stoppedAt, (int) Math.floor(now));
			rt.recordDeparture(st.stoppedPlatform, millis);
			st.stoppedAt = -1;
		}
		if (tick % 20 == t.id % 20) {
			rt.observe(t.id, stop, stopped, (int) Math.floor(now), millis);
		}
	}

	// ------------------------------------------------------------------ 同期(TrainServer.simulateTrain の戻り値)

	/** 停車時間や速度を変えた列車は、クライアントの表示がずれないよう0.5秒ごとに同期させる。 */
	public static boolean forceSync(TrainServer t) {
		final Context c = context;
		if (c == null) {
			return false;
		}
		try {
			final State st;
			synchronized (STATES) {
				st = STATES.get(t.id);
			}
			if (st == null || !(st.adjusted || st.limited)) {
				return false;
			}
			final long tick = c.currentTick();
			if (tick - st.lastSync >= SYNC_TICKS) {
				st.lastSync = tick;
				return true;
			}
		} catch (RuntimeException ignored) {
		}
		return false;
	}
}
