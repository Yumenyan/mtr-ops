package jp.mtrops.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 「発車からの経過tick」を列車ごとに追跡する。
 *
 * MTRは列車ごとの遅延を持たない(TrainDelay は信号待ちの発生位置の記録のみ)。そこで
 *   遅延 = 発車からの実経過 − ダイヤ(TimeSegment)上でその位置に着くはずの時刻
 * で算出する。サーバー再起動などで途中から観測を始めた列車は、その時点を遅延0として基準を取り直す
 * (= 再起動直後は遅延が0から積み上がる)。
 */
public final class ElapsedTracker {

	private static final double WRAP_BACK_TICKS = 200;

	private final Map<Long, Long> baseline = new HashMap<>();
	private final Map<Long, Double> lastIdeal = new HashMap<>();

	/**
	 * @param idealNow ダイヤ上の現在位置の時刻(tick)。不明は NaN
	 * @return 発車(基準)からの経過tick。走行していなければ NaN
	 */
	public double elapsed(long trainId, boolean onRoute, long nowTick, double idealNow) {
		if (!onRoute) {
			baseline.remove(trainId);
			lastIdeal.remove(trainId);
			return Double.NaN;
		}
		if (Double.isNaN(idealNow)) {
			final Long b = baseline.get(trainId);
			return b == null ? Double.NaN : (double) (nowTick - b);
		}
		final Double prev = lastIdeal.get(trainId);
		Long b = baseline.get(trainId);
		// 初観測、または周回などでダイヤ上の時刻が大きく巻き戻った → 基準を取り直す
		if (b == null || (prev != null && idealNow < prev - WRAP_BACK_TICKS)) {
			b = nowTick - (long) idealNow;
			baseline.put(trainId, b);
		}
		lastIdeal.put(trainId, idealNow);
		return nowTick - b;
	}

	public void retainOnly(Set<Long> liveIds) {
		baseline.keySet().retainAll(liveIds);
		lastIdeal.keySet().retainAll(liveIds);
	}

	public void clear() {
		baseline.clear();
		lastIdeal.clear();
	}
}
