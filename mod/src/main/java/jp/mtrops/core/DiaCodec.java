package jp.mtrops.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * カスタムダイヤのバイナリ表現。ワールド保存・サーバー→クライアント同期・クライアント→サーバーの更新で共用する。
 * 時刻表(Run)そのものは送らない。設定と理想走行(Profile)から、両側が同じ DiaPlanner で作り直す。
 */
public final class DiaCodec {

	private static final int MAGIC = 0x44494131; // "DIA1"
	private static final int VERSION = 4;

	private DiaCodec() {
	}

	// ------------------------------------------------------------------ 設定

	public static byte[] encodeConfig(DiaModel.Config c) {
		try {
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			final DataOutputStream out = new DataOutputStream(bos);
			out.writeInt(MAGIC);
			out.writeByte(VERSION);
			writeConfig(out, c);
			out.flush();
			return bos.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static DiaModel.Config decodeConfig(byte[] data) throws IOException {
		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		final int v = header(in);
		return readConfig(in, v);
	}

	static void writeConfig(DataOutputStream out, DiaModel.Config c) throws IOException {
		out.writeInt(c.rev);
		out.writeShort(c.sep);
		out.writeShort(c.minOpen);
		out.writeInt(c.routes.size());
		for (final DiaModel.RouteSetting r : c.routes.values()) {
			out.writeLong(r.routeId);
			out.writeBoolean(r.custom);
			out.writeByte(r.safety);
			out.writeShort(r.overKmh);
			out.writeShort(r.maxKmh);
			out.writeShort(r.delayThreshold);
			out.writeBoolean(r.table != null);
			if (r.table != null) {
				writeTable(out, r.table);
			}
		}
	}

	private static void writeTable(DataOutputStream out, DiaModel.Table t) throws IOException {
		out.writeInt(t.first);
		out.writeInt(t.last);
		out.writeShort(t.periods.size());
		for (final DiaModel.Period x : t.periods) {
			out.writeUTF(x.name == null ? "" : x.name);
			out.writeInt(x.start);
			out.writeInt(x.end);
			out.writeInt(x.headway);
		}
		out.writeShort(t.extra.size());
		for (final Integer x : t.extra) {
			out.writeInt(x);
		}
		out.writeShort(t.cancelled.size());
		for (final Integer x : t.cancelled) {
			out.writeInt(x);
		}
		out.writeShort(t.offsets.size());
		for (final Map.Entry<Integer, Integer> e : t.offsets.entrySet()) {
			out.writeShort(e.getKey());
			out.writeInt(e.getValue());
		}
	}

	private static DiaModel.Table readTable(DataInputStream in) throws IOException {
		final DiaModel.Table t = new DiaModel.Table();
		t.first = in.readInt();
		t.last = in.readInt();
		final int pn = count(in.readShort(), DiaModel.MAX_PERIODS);
		for (int k = 0; k < pn; k++) {
			final DiaModel.Period x = new DiaModel.Period();
			x.name = in.readUTF();
			x.start = in.readInt();
			x.end = in.readInt();
			x.headway = in.readInt();
			t.periods.add(x);
		}
		final int en = count(in.readShort(), DiaModel.MAX_EXTRA);
		for (int k = 0; k < en; k++) {
			t.extra.add(in.readInt());
		}
		final int cn = count(in.readShort(), DiaModel.MAX_EXTRA);
		for (int k = 0; k < cn; k++) {
			t.cancelled.add(in.readInt());
		}
		final int on = count(in.readShort(), 500);
		for (int k = 0; k < on; k++) {
			t.offsets.put((int) in.readShort(), in.readInt());
		}
		return t;
	}

	/** 版2〜3(車庫ごとの時刻表)の設定は、路線のダイヤ運転の有無・保安装置などだけ引き継ぎ、車庫の時刻表は捨てる。 */
	static DiaModel.Config readConfig(DataInputStream in, int version) throws IOException {
		final DiaModel.Config c = new DiaModel.Config();
		c.rev = in.readInt();
		c.sep = in.readShort();
		c.minOpen = in.readShort();
		if (version < 4) {
			in.readShort(); // 旧 maxHold
			if (version >= 2) {
				in.readShort();
				in.readShort(); // 旧 slackSec / slackPct
			}
		}
		final int nr = count(in.readInt(), DiaModel.MAX_ROUTES);
		for (int i = 0; i < nr; i++) {
			final DiaModel.RouteSetting r = new DiaModel.RouteSetting();
			r.routeId = in.readLong();
			r.custom = in.readBoolean();
			r.safety = in.readByte();
			r.overKmh = in.readShort();
			r.maxKmh = in.readShort();
			r.delayThreshold = in.readShort();
			if (version >= 4 && in.readBoolean()) {
				r.table = readTable(in);
			}
			c.routes.put(r.routeId, r);
		}
		if (version < 4) {
			// 旧: 車庫ごとの時刻表。使わないので読み飛ばす
			final int np = count(in.readInt(), 1000);
			for (int i = 0; i < np; i++) {
				in.readLong();
				in.readShort();
				in.readInt();
				in.readInt();
				in.readInt();
				in.readBoolean();
				in.readShort();
				final int pn = count(in.readShort(), DiaModel.MAX_PERIODS);
				for (int k = 0; k < pn; k++) {
					in.readUTF();
					in.readInt();
					in.readInt();
					in.readInt();
				}
				final int en = count(in.readShort(), DiaModel.MAX_EXTRA);
				for (int k = 0; k < en; k++) {
					in.readInt();
				}
				final int cn = count(in.readShort(), DiaModel.MAX_EXTRA);
				for (int k = 0; k < cn; k++) {
					in.readInt();
				}
				final int hn = count(in.readShort(), 500);
				for (int k = 0; k < hn; k++) {
					in.readShort();
					in.readShort();
				}
			}
		}
		return c;
	}

	// ------------------------------------------------------------------ 理想走行

	public static byte[] encodeProfiles(Map<Long, DiaModel.Profile> profiles) {
		try {
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			final DataOutputStream out = new DataOutputStream(bos);
			out.writeInt(MAGIC);
			out.writeByte(VERSION);
			out.writeInt(profiles.size());
			for (final DiaModel.Profile p : profiles.values()) {
				out.writeLong(p.depotId);
				out.writeLong(p.sidingId);
				out.writeDouble(p.total);
				out.writeShort(p.stops.size());
				for (final DiaModel.Stop s : p.stops) {
					out.writeInt(s.pathIndex);
					out.writeLong(s.stationId);
					out.writeLong(s.platformId);
					out.writeLong(s.routeId);
					out.writeUTF(cut(s.station, 200));
					out.writeUTF(cut(s.platform, 40));
					out.writeFloat((float) s.arr);
					out.writeFloat((float) s.dep);
					out.writeBoolean(s.pass);
					out.writeShort(s.stationIndex);
				}
			}
			out.flush();
			return bos.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static Map<Long, DiaModel.Profile> decodeProfiles(byte[] data) throws IOException {
		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		final int version = header(in);
		final Map<Long, DiaModel.Profile> map = new java.util.LinkedHashMap<>();
		final int n = count(in.readInt(), 4000);
		for (int i = 0; i < n; i++) {
			final DiaModel.Profile p = new DiaModel.Profile();
			p.depotId = in.readLong();
			p.sidingId = in.readLong();
			p.total = in.readDouble();
			final int sn = count(in.readShort(), 1000);
			for (int k = 0; k < sn; k++) {
				final DiaModel.Stop s = new DiaModel.Stop();
				s.pathIndex = in.readInt();
				s.stationId = in.readLong();
				s.platformId = in.readLong();
				s.routeId = in.readLong();
				s.station = in.readUTF();
				s.platform = in.readUTF();
				s.arr = in.readFloat();
				s.dep = in.readFloat();
				s.pass = in.readBoolean();
				if (version >= 3) {
					s.stationIndex = in.readShort();
				}
				p.stops.add(s);
			}
			map.put(p.depotId, p);
		}
		return map;
	}

	private static String cut(String s, int max) {
		if (s == null) {
			return "";
		}
		return s.length() > max ? s.substring(0, max) : s;
	}

	private static int header(DataInputStream in) throws IOException {
		if (in.readInt() != MAGIC) {
			throw new IOException("bad magic");
		}
		final int v = in.readByte();
		if (v > VERSION) {
			throw new IOException("unsupported dia version " + v);
		}
		return v;
	}

	private static int count(int n, int max) throws IOException {
		if (n < 0 || n > max) {
			throw new IOException("bad count " + n);
		}
		return n;
	}
}
