package jp.mtrops.core;

/**
 * 到着予想の仕上げ(MC/MTRに依存しない)。
 *
 *  1. calibrate: MTRの理論値(Siding.TimeSegment から求めた残りtick)を、駅間ごとの実績比率(SectionStats)で補正する。
 *     理論値は信号待ち・加減速の差で実際より短く出るため、遠い駅ほど早すぎる予想になる。区間ごとに伸ばして積み上げる。
 */
public final class EtaPlanner {

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


}
