package jp.mtrops.core;

import java.util.HashMap;
import java.util.Map;

/**
 * 駅間の走行時間の実績比率(実績 ÷ MTRの理論値)。ダイヤ編成ツールの所要時間の見積もり(表の初期値・詰まりの診断)にだけ使う。
 * 発車時刻そのものは動かさない。
 *
 * 比率は 1.0〜MAX 倍に収め、0.05刻みに丸める(実績の揺れで見積もりが変わり続けないように)。観測が少ない区間は 1.0 のまま。
 * サーバーが同期するので、サーバーとクライアントは同じ見積もりになる。
 */
public final class DiaStretch {

	public static final double MAX = 1.6;
	public static final int MIN_SAMPLES = 3;

	private DiaStretch() {
	}

	/** 実績から、区間ごとの丸めた比率の表を作る。 */
	public static Map<String, Double> ratios(SectionStats runs) {
		final Map<String, Double> out = new HashMap<>();
		for (final SectionStats.Stat s : runs.all()) {
			if (s.samples < MIN_SAMPLES) {
				continue;
			}
			final double r = Math.round(Math.max(1.0, Math.min(MAX, s.ratio())) * 20) / 20.0;
			if (r > 1.0) {
				out.put(SectionStats.key(s.fromStationId, s.toStationId), r);
			}
		}
		return out;
	}
}
