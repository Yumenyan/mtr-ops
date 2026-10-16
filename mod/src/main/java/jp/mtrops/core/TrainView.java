package jp.mtrops.core;

/**
 * API・同期パケットに載せる列車の状態。サンプル + 判定結果。
 * detailLevel が低いスナップショットから復元した場合、載っていない項目は既定値(NaN/0/-1)になる。
 */
public final class TrainView {

	public TrainSample s = new TrainSample();
	public TrainStatus status = TrainStatus.NORMAL;
	public int delaySec;
	/** 進捗 0..100。不明は -1 */
	public int progressPct = -1;

	public static TrainView of(TrainSample s, TrainStatus status, int delaySec) {
		final TrainView v = new TrainView();
		v.s = s;
		v.status = status;
		v.delaySec = delaySec;
		if (s.pathLength > 0) {
			v.progressPct = (int) Math.max(0, Math.min(100, Math.round(s.railProgress / s.pathLength * 100)));
		}
		return v;
	}
}
