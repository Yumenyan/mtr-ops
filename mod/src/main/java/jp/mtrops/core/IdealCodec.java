package jp.mtrops.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * 車庫から出た列車の理想走行(Profile)のバイナリ表現。サーバー→クライアントの時刻表同期(ScheduleSync)で使う。
 */
public final class IdealCodec {

	private static final int MAGIC = 0x49444C31; // "IDL1"
	private static final int VERSION = 1;

	private IdealCodec() {
	}

	// ------------------------------------------------------------------ 理想走行

	public static byte[] encodeProfiles(Map<Long, IdealRun.Profile> profiles) {
		try {
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			final DataOutputStream out = new DataOutputStream(bos);
			out.writeInt(MAGIC);
			out.writeByte(VERSION);
			out.writeInt(profiles.size());
			for (final IdealRun.Profile p : profiles.values()) {
				out.writeLong(p.depotId);
				out.writeLong(p.sidingId);
				out.writeDouble(p.total);
				out.writeShort(p.stops.size());
				for (final IdealRun.Stop s : p.stops) {
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

	public static Map<Long, IdealRun.Profile> decodeProfiles(byte[] data) throws IOException {
		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		header(in);
		final Map<Long, IdealRun.Profile> map = new java.util.LinkedHashMap<>();
		final int n = count(in.readInt(), 4000);
		for (int i = 0; i < n; i++) {
			final IdealRun.Profile p = new IdealRun.Profile();
			p.depotId = in.readLong();
			p.sidingId = in.readLong();
			p.total = in.readDouble();
			final int sn = count(in.readShort(), 1000);
			for (int k = 0; k < sn; k++) {
				final IdealRun.Stop s = new IdealRun.Stop();
				s.pathIndex = in.readInt();
				s.stationId = in.readLong();
				s.platformId = in.readLong();
				s.routeId = in.readLong();
				s.station = in.readUTF();
				s.platform = in.readUTF();
				s.arr = in.readFloat();
				s.dep = in.readFloat();
				s.pass = in.readBoolean();
				s.stationIndex = in.readShort();
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

	private static void header(DataInputStream in) throws IOException {
		if (in.readInt() != MAGIC) {
			throw new IOException("bad magic");
		}
		final int v = in.readByte();
		if (v > VERSION) {
			throw new IOException("unsupported version " + v);
		}
	}

	private static int count(int n, int max) throws IOException {
		if (n < 0 || n > max) {
			throw new IOException("bad count " + n);
		}
		return n;
	}
}
