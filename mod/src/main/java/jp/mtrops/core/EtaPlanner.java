package jp.mtrops.core;

/**
 * 到着予想の仕上げ(MC/MTRに依存しない)。
 *
 *  1. calibrate: MTRの理論値(Siding.TimeSegment から求めた残りtick)を、駅間ごとの実績比率(SectionStats)で補正する。
 *     理論値は信号待ち・加減速の差で実際より短く出るため、遠い駅ほど早すぎる予想になる。区間ごとに伸ばして積み上げる。
 *  2. applyTimetable: カスタムダイヤの列車は、時刻表の到着(定刻)を入れ、早く着く駅では発車時刻まで待つことを見込みに反映する。
 */
public final class EtaPlanner {

	/** MTRのドアの開閉にかかる最短の時間(tick)。Train.DOOR_DELAY*2 + DOOR_MOVE_TIME*2 相当 */
	public static final int DOOR_TICKS = 2 * 20 + 2 * 64;

	private EtaPlanner() {
	}

	/**
	 * etaIdealTicks から etaTicks を作る。
	 * @param lastStationId 直前に停車した駅(停車中ならその駅)。不明は 0(最初の区間は補正しない)
	 */
	public static void calibrate(TrainSample s, long lastStationId, SectionStats stats) {
		final int n = s.etaIdealTicks.length;
		s.etaTicks = new int[n];
		if (s.etaSchedTicks.length != n) {
			s.etaSchedTicks = new int[n];
			java.util.Arrays.fill(s.etaSchedTicks, TrainSample.NO_SCHED);
		}
		if (s.etaPass.length != n) {
			s.etaPass = new boolean[n];
		}
		long prevSt = lastStationId;
		double prevIdeal = 0;
		double prevExp = 0;
		for (int k = 0; k < n; k++) {
			final double d = Math.max(0, s.etaIdealTicks[k] - prevIdeal);
			if (s.etaPass[k]) {
				// 通過するホーム: 次に停車する駅までの区間の比率で伸ばす。停車駅の基準(prevSt など)は進めない
				long next = 0;
				for (int j = k + 1; j < n; j++) {
					if (!s.etaPass[j]) {
						next = s.etaStationIds[j];
						break;
					}
				}
				final double r = prevSt == 0 || next == 0 || stats == null ? 1 : stats.ratio(prevSt, next);
				s.etaTicks[k] = (int) Math.min(Integer.MAX_VALUE, Math.round(prevExp + d * r));
				continue;
			}
			final double r = prevSt == 0 || stats == null ? 1 : stats.ratio(prevSt, s.etaStationIds[k]);
			final double exp = prevExp + d * r;
			s.etaTicks[k] = (int) Math.min(Integer.MAX_VALUE, Math.round(exp));
			prevIdeal = Math.max(prevIdeal, s.etaIdealTicks[k]);
			prevExp = exp;
			prevSt = s.etaStationIds[k];
		}
	}

	/**
	 * カスタムダイヤの便の時刻表を、到着予想に重ねる。
	 * @param prof        車庫の理想走行(停車駅の並び)
	 * @param run         この列車に割り当てた便
	 * @param lastStop    最後に発車した停車駅(prof.stops の番号。-1 = まだ)
	 * @param currentStop 停車中の停車駅の番号(走行中は -1)
	 * @param nowSec      サーバーのタイムゾーンでの、その日の0時からの秒
	 * @param minOpenSec  遅れているときにドアを開けておく最短の秒(DiaModel.Config.minOpen)
	 */
	public static void applyTimetable(TrainSample s, DiaModel.Profile prof, DiaModel.Run run, int lastStop, int currentStop, double nowSec, int minOpenSec) {
		final int n = s.etaTicks.length;
		if (prof == null || run == null || n == 0) {
			return;
		}
		// 停車駅の番号を対応づける(ホームIDで、前から順に)
		final int[] stopOf = new int[n];
		java.util.Arrays.fill(stopOf, -1);
		int p = Math.max(0, Math.max(lastStop, currentStop) + 1);
		for (int k = 0; k < n; k++) {
			if (k < s.etaPass.length && s.etaPass[k]) {
				continue; // 通過は時刻表の停車駅に数えない
			}
			int q = p;
			while (q < prof.stops.size() && (prof.stops.get(q).pass || prof.stops.get(q).platformId != s.etaPlatformIds[k])) {
				q++;
			}
			if (q >= prof.stops.size() || q >= run.arr.length) {
				break;
			}
			stopOf[k] = q;
			p = q + 1;
		}
		final int minDwell = minOpenSec * 20 + DOOR_TICKS;
		// 物理的な見込み(補正済み)から各区間の走行時間(前の駅での停車を除く)を取り出し、時刻表の発車時刻まで待つ分を足していく。
		// depPrev = 直前の駅を出る見込み、physPrev = 物理的な見込みでの直前の駅の発車(今からのtick)
		double depPrev = 0;
		double physPrev = 0;
		if (s.atStop && currentStop >= 0 && currentStop < run.dep.length) {
			final double dwellPhys = dwellTicks(prof, currentStop);
			physPrev = Math.max(0, dwellPhys - s.elapsedDwellTicks);
			final double remainMin = Math.max(0, Math.min(dwellPhys, minDwell) - s.elapsedDwellTicks);
			depPrev = Math.max(remainMin, ticksUntil(run.dep[currentStop], nowSec));
		}
		for (int k = 0; k < n; k++) {
			final double leg = Math.max(0, s.etaTicks[k] - physPrev);
			final double arr = depPrev + leg;
			physPrev = s.etaTicks[k] + (stopOf[k] >= 0 ? dwellTicks(prof, stopOf[k]) : 0);
			set(s, k, arr, stopOf[k], run, nowSec);
			depPrev = departure(arr, stopOf[k], prof, run, nowSec, minDwell);
		}
	}

	private static void set(TrainSample s, int k, double arr, int stop, DiaModel.Run run, double nowSec) {
		s.etaTicks[k] = (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(arr)));
		s.etaSchedTicks[k] = stop >= 0 ? (int) Math.round(ticksUntil(run.arr[stop], nowSec)) : TrainSample.NO_SCHED;
	}

	/** 見込みの到着 arr から、その駅を発車する見込み(時刻表の発車時刻より前には出ない。遅れていれば最短の停車で出る)。 */
	private static double departure(double arr, int stop, DiaModel.Profile prof, DiaModel.Run run, double nowSec, int minDwell) {
		if (stop < 0) {
			return arr;
		}
		final double phys = dwellTicks(prof, stop);
		if (stop >= run.dep.length) {
			return arr + phys;
		}
		final double sched = ticksUntil(run.dep[stop], nowSec);
		return Math.max(arr + Math.min(phys, minDwell), sched);
	}

	private static double dwellTicks(DiaModel.Profile prof, int stop) {
		final DiaModel.Stop st = prof.stops.get(stop);
		return Math.max(0, (st.dep - st.arr) * 20);
	}

	/** 「その日の秒」の時刻まで、今から何tickか(日またぎは前後12時間で丸める)。 */
	static double ticksUntil(int daySec, double nowSec) {
		double d = (daySec - nowSec) % DiaModel.DAY;
		if (d < -DiaModel.DAY / 2.0) {
			d += DiaModel.DAY;
		} else if (d >= DiaModel.DAY / 2.0) {
			d -= DiaModel.DAY;
		}
		return d * 20;
	}
}
