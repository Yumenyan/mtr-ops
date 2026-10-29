package jp.mtrops.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 駅間(停車駅A → 次の停車駅B)の所要時間の実績。
 * 「Aに着いてからBに着くまで」(Aでの停車時間を含む)の実測と、同じ区間のMTRの理論値(Siding.TimeSegment)を
 * 指数移動平均で持つ。MTRの理論値は信号待ちや加減速の差で実際より短く出るため、
 * 到着予想・時刻表の推定はこの比率(実績 ÷ 理論)で補正する。
 */
public final class SectionStats {

	/** 指数移動平均の重み(新しい観測の割合) */
	private static final double ALPHA = 0.25;
	/** 補正の比率の範囲(これを外れる観測は異常として捨てる) */
	public static final double MIN_RATIO = 0.6;
	public static final double MAX_RATIO = 4.0;
	private static final int MAX_SECTIONS = 20_000;

	public static final class Stat {
		public long fromStationId;
		public long toStationId;
		/** 実測(秒、指数移動平均) */
		public double actualSec;
		/** MTRの理論値(秒、指数移動平均) */
		public double idealSec;
		public int samples;
		public long lastMillis;

		public double ratio() {
			if (samples <= 0 || idealSec <= 0.5) {
				return 1;
			}
			return Math.max(MIN_RATIO, Math.min(MAX_RATIO, actualSec / idealSec));
		}
	}

	private final Map<String, Stat> map = new LinkedHashMap<>();

	public static String key(long from, long to) {
		return from + ">" + to;
	}

	/** 観測を足す。比率が範囲外のもの(待避・手動運転・長時間の停止など)は捨てる。 @return 採用したか */
	public synchronized boolean add(long from, long to, double actualSec, double idealSec, long nowMillis) {
		if (from == 0 || to == 0 || from == to || actualSec <= 0 || idealSec <= 0.5) {
			return false;
		}
		final double r = actualSec / idealSec;
		if (r < MIN_RATIO || r > MAX_RATIO) {
			return false;
		}
		Stat s = map.get(key(from, to));
		if (s == null) {
			if (map.size() >= MAX_SECTIONS) {
				return false;
			}
			s = new Stat();
			s.fromStationId = from;
			s.toStationId = to;
			s.actualSec = actualSec;
			s.idealSec = idealSec;
			map.put(key(from, to), s);
		} else {
			s.actualSec += (actualSec - s.actualSec) * ALPHA;
			s.idealSec += (idealSec - s.idealSec) * ALPHA;
		}
		s.samples++;
		s.lastMillis = nowMillis;
		return true;
	}

	/** 区間の補正比率(実績が無ければ1)。 */
	public synchronized double ratio(long from, long to) {
		final Stat s = map.get(key(from, to));
		return s == null ? 1 : s.ratio();
	}

	public synchronized Stat get(long from, long to) {
		final Stat s = map.get(key(from, to));
		return s == null ? null : copy(s);
	}

	public synchronized List<Stat> all() {
		final List<Stat> out = new ArrayList<>(map.size());
		for (final Stat s : map.values()) {
			out.add(copy(s));
		}
		return out;
	}

	public synchronized int size() {
		return map.size();
	}

	private static Stat copy(Stat s) {
		final Stat c = new Stat();
		c.fromStationId = s.fromStationId;
		c.toStationId = s.toStationId;
		c.actualSec = s.actualSec;
		c.idealSec = s.idealSec;
		c.samples = s.samples;
		c.lastMillis = s.lastMillis;
		return c;
	}

	public synchronized void write(DataOutputStream out) throws IOException {
		out.writeInt(map.size());
		for (final Stat s : map.values()) {
			out.writeLong(s.fromStationId);
			out.writeLong(s.toStationId);
			out.writeFloat((float) s.actualSec);
			out.writeFloat((float) s.idealSec);
			out.writeInt(s.samples);
			out.writeLong(s.lastMillis);
		}
	}

	public static SectionStats read(DataInputStream in) throws IOException {
		final SectionStats st = new SectionStats();
		final int n = in.readInt();
		if (n < 0 || n > MAX_SECTIONS) {
			throw new IOException("bad section count " + n);
		}
		for (int i = 0; i < n; i++) {
			final Stat s = new Stat();
			s.fromStationId = in.readLong();
			s.toStationId = in.readLong();
			s.actualSec = in.readFloat();
			s.idealSec = in.readFloat();
			s.samples = in.readInt();
			s.lastMillis = in.readLong();
			st.map.put(key(s.fromStationId, s.toStationId), s);
		}
		return st;
	}
}
