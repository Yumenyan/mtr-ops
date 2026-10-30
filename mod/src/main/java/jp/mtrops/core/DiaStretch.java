package jp.mtrops.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 理想走行(MTRの理論値)を、実際の走行時間の実績で伸ばす。
 * MTRの理論値は信号待ち・加減速の差で実際より短く出る。そのまま時刻表にすると、後ろの列車が追いついて詰まるので、
 * 駅間ごとの「実績 ÷ 理論」の比率(SectionStats)で走行時間を伸ばしてから時刻表を組む(自動調整)。
 *
 * 比率は 1.0〜MAX 倍に収め、0.05刻みに丸める(実績の揺れで時刻表が作り直されないように)。観測が少ない区間は伸ばさない。
 * サーバーがこれを適用した理想走行を同期するので、サーバーとクライアントの時刻表は同じになる。
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

	public static Map<Long, DiaModel.Profile> apply(Map<Long, DiaModel.Profile> profiles, Map<String, Double> ratios) {
		if (ratios.isEmpty()) {
			return profiles;
		}
		final Map<Long, DiaModel.Profile> out = new LinkedHashMap<>();
		for (final Map.Entry<Long, DiaModel.Profile> e : profiles.entrySet()) {
			out.put(e.getKey(), apply(e.getValue(), ratios));
		}
		return out;
	}

	public static DiaModel.Profile apply(DiaModel.Profile p, Map<String, Double> ratios) {
		final DiaModel.Profile q = new DiaModel.Profile();
		q.depotId = p.depotId;
		q.sidingId = p.sidingId;
		double shift = 0;
		DiaModel.Stop prev = null;
		for (final DiaModel.Stop s : p.stops) {
			final DiaModel.Stop c = new DiaModel.Stop();
			c.pathIndex = s.pathIndex;
			c.stationId = s.stationId;
			c.platformId = s.platformId;
			c.routeId = s.routeId;
			c.station = s.station;
			c.platform = s.platform;
			c.stationIndex = s.stationIndex;
			c.pass = s.pass;
			if (!s.pass && prev != null) {
				final Double r = ratios.get(SectionStats.key(prev.stationId, s.stationId));
				if (r != null) {
					shift += Math.max(0, s.arr - prev.dep) * (r - 1);
				}
			}
			c.arr = s.arr + shift;
			c.dep = s.dep + shift;
			if (!s.pass) {
				prev = s;
			}
			q.stops.add(c);
		}
		q.total = p.total + shift;
		return q;
	}
}
