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
	private static final int VERSION = 3;

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
		out.writeShort(c.maxHold);
		out.writeShort(c.slackSec);
		out.writeShort(c.slackPct);
		out.writeInt(c.routes.size());
		for (final DiaModel.RouteSetting r : c.routes.values()) {
			out.writeLong(r.routeId);
			out.writeBoolean(r.custom);
			out.writeByte(r.safety);
			out.writeShort(r.overKmh);
			out.writeShort(r.maxKmh);
			out.writeShort(r.delayThreshold);
		}
		out.writeInt(c.plans.size());
		for (final DiaModel.DepotPlan p : c.plans.values()) {
			out.writeLong(p.depotId);
			out.writeShort(p.anchor);
			out.writeInt(p.first);
			out.writeInt(p.last);
			out.writeInt(p.offset);
			out.writeBoolean(p.autoMesh);
			out.writeShort(p.lead);
			out.writeShort(p.periods.size());
			for (final DiaModel.Period x : p.periods) {
				out.writeUTF(x.name == null ? "" : x.name);
				out.writeInt(x.start);
				out.writeInt(x.end);
				out.writeInt(x.headway);
			}
			out.writeShort(p.extra.size());
			for (final Integer x : p.extra) {
				out.writeInt(x);
			}
			out.writeShort(p.cancelled.size());
			for (final Integer x : p.cancelled) {
				out.writeInt(x);
			}
			out.writeShort(p.holds.size());
			for (final Map.Entry<Integer, Integer> e : p.holds.entrySet()) {
				out.writeShort(e.getKey());
				out.writeShort(e.getValue());
			}
		}
	}

	static DiaModel.Config readConfig(DataInputStream in, int version) throws IOException {
		final DiaModel.Config c = new DiaModel.Config();
		c.rev = in.readInt();
		c.sep = in.readShort();
		c.minOpen = in.readShort();
		c.maxHold = in.readShort();
		if (version >= 2) {
			c.slackSec = in.readShort();
			c.slackPct = in.readShort();
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
			c.routes.put(r.routeId, r);
		}
		final int np = count(in.readInt(), DiaModel.MAX_PLANS);
		for (int i = 0; i < np; i++) {
			final DiaModel.DepotPlan p = new DiaModel.DepotPlan();
			p.depotId = in.readLong();
			p.anchor = in.readShort();
			p.first = in.readInt();
			p.last = in.readInt();
			p.offset = in.readInt();
			p.autoMesh = in.readBoolean();
			p.lead = in.readShort();
			final int pn = count(in.readShort(), DiaModel.MAX_PERIODS);
			for (int k = 0; k < pn; k++) {
				final DiaModel.Period x = new DiaModel.Period();
				x.name = in.readUTF();
				x.start = in.readInt();
				x.end = in.readInt();
				x.headway = in.readInt();
				p.periods.add(x);
			}
			final int en = count(in.readShort(), DiaModel.MAX_EXTRA);
			for (int k = 0; k < en; k++) {
				p.extra.add(in.readInt());
			}
			final int cn = count(in.readShort(), DiaModel.MAX_EXTRA);
			for (int k = 0; k < cn; k++) {
				p.cancelled.add(in.readInt());
			}
			final int hn = count(in.readShort(), 500);
			for (int k = 0; k < hn; k++) {
				p.holds.put((int) in.readShort(), (int) in.readShort());
			}
			c.plans.put(p.depotId, p);
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
		final int n = count(in.readInt(), DiaModel.MAX_PLANS * 4);
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
