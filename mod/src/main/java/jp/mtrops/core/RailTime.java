package jp.mtrops.core;

/**
 * 列車の理想走行時間を求める。MTR 3.2.2-hotfix-1 の Siding.generateTimeSegments と同じ計算
 * (1tickごとに加速/減速/等速を決め、停車駅では dwellTime*5 tick を前後に足す)を、MCに依存せず再現する。
 *
 * 入力は経路(PathData)を単純化した配列。tick 単位で返す(20tick = 1秒)。
 */
public final class RailTime {

	private RailTime() {
	}

	/** 経路の1要素(PathData 1つ分)。 */
	public static final class Rail {
		/** レールの長さ(ブロック) */
		public double length;
		/** 加速できるレールなら最高速度(ブロック/tick)。できないレール(ホーム・車庫線など)は負の値 */
		public float maxSpeed = -1;
		/** 停車時間(MTRの dwellTime。単位は0.5秒) */
		public int dwell;
		/** 次の要素との間で折り返す(isOppositeRail) */
		public boolean reverseAfter;
	}

	/** 結果: 停車する要素(dwell>0)ごとの到着tick(停車し始め)。停車しない要素は -1。 */
	public static final class Result {
		public double[] arriveTick;
		/** すべての要素について、そのレールの終わりに達したtick(通過時刻に使う) */
		public double[] endTick;
		public double totalTick;
	}

	/**
	 * @param railLength   車庫線の長さ(Siding.railLength)
	 * @param cars         両数
	 * @param spacing      1両の長さ
	 * @param accel        加速度(ブロック/tick^2)
	 * @param defaultSpeed 加速できないレールで使う既定の最高速度(RailType.getDefaultMaxBlocksPerTick)
	 */
	public static Result compute(Rail[] path, float railLength, int cars, int spacing, float accel, float defaultSpeed) {
		final float a = accel <= 0 ? 0.01F * 0.5F : accel;
		final Result r = new Result();
		r.arriveTick = new double[path.length];
		r.endTick = new double[path.length];
		java.util.Arrays.fill(r.arriveTick, -1);

		double distanceSum1 = 0;
		final java.util.ArrayDeque<Double> stopping = new java.util.ArrayDeque<>();
		for (final Rail p : path) {
			distanceSum1 += p.length;
			if (p.dwell > 0) {
				stopping.add(distanceSum1);
			}
		}

		double railProgress = (railLength + cars * spacing) / 2.0;
		double nextStoppingDistance = 0;
		float speed = 0;
		double time = 0;
		double distanceSum2 = 0;
		for (int i = 0; i < path.length; i++) {
			if (railProgress >= nextStoppingDistance) {
				nextStoppingDistance = stopping.isEmpty() ? distanceSum1 : stopping.poll();
			}
			final Rail p = path[i];
			final float railSpeed = p.maxSpeed > 0 ? p.maxSpeed : Math.max(speed, defaultSpeed);
			distanceSum2 += p.length;

			int guard = 0;
			while (railProgress < distanceSum2 && guard++ < 10_000_000) {
				if (speed > railSpeed || nextStoppingDistance - railProgress + 1 < 0.5 * speed * speed / a) {
					speed = Math.max(speed - a, a);
				} else if (speed < railSpeed) {
					speed = Math.min(speed + a, railSpeed);
				}
				railProgress = Math.min(railProgress + speed, distanceSum2);
				time++;
			}

			r.endTick[i] = time;
			if (p.dwell > 0) {
				r.arriveTick[i] = time;
			}
			time += p.dwell * 10;

			if (p.reverseAfter && i + 1 < path.length) {
				railProgress += spacing * cars;
			}
		}
		r.totalTick = time;
		return r;
	}
}
