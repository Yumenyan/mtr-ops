package jp.mtrops.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Geometry のバイナリ表現。1パケットに収まらない分は複数パート(同じ rev)に分けて送る。
 * 区間は頂点を (x, y, z) の差分(可変長)で持つ。
 */
public final class GeometryCodec {

	private static final int MAGIC = 0x4D4F4732; // "MOG2"

	private GeometryCodec() {
	}

	public static final class Part {
		public int rev;
		public int part;
		public int parts;
		public final List<Geometry.Section> sections = new ArrayList<>();
	}

	public static List<byte[]> encode(Geometry g, int maxBytes) {
		final List<List<Geometry.Section>> groups = new ArrayList<>();
		List<Geometry.Section> cur = new ArrayList<>();
		int size = 0;
		for (final Geometry.Section s : g.sections) {
			final int est = 48 + s.pointCount() * 9 + s.partTypes.length * 24;
			if (!cur.isEmpty() && size + est > maxBytes - 64) {
				groups.add(cur);
				cur = new ArrayList<>();
				size = 0;
			}
			cur.add(s);
			size += est;
		}
		groups.add(cur);
		final List<byte[]> out = new ArrayList<>();
		for (int i = 0; i < groups.size(); i++) {
			out.add(write(g.rev, i, groups.size(), groups.get(i)));
		}
		return out;
	}

	private static byte[] write(int rev, int part, int parts, List<Geometry.Section> list) {
		try {
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			final DataOutputStream out = new DataOutputStream(bos);
			out.writeInt(MAGIC);
			out.writeInt(rev);
			out.writeShort(part);
			out.writeShort(parts);
			out.writeInt(list.size());
			for (final Geometry.Section s : list) {
				out.writeLong(s.fromStationId);
				out.writeLong(s.toStationId);
				out.writeFloat((float) s.distance);
				out.writeUTF(s.mode == null ? "" : s.mode);
				final int pc = Math.min(s.partTypes.length, Math.min(s.partFrom.length, s.partTo.length));
				out.writeShort(pc);
				for (int i = 0; i < pc; i++) {
					out.writeUTF(s.partTypes[i]);
					out.writeFloat(s.partFrom[i]);
					out.writeFloat(s.partTo[i]);
				}
				final int n = s.pointCount();
				out.writeShort(n);
				int px = 0, py = 0, pz = 0;
				for (int i = 0; i < n; i++) {
					writeVar(out, s.points[i * 3] - px);
					writeVar(out, s.points[i * 3 + 1] - py);
					writeVar(out, s.points[i * 3 + 2] - pz);
					px = s.points[i * 3];
					py = s.points[i * 3 + 1];
					pz = s.points[i * 3 + 2];
				}
			}
			out.flush();
			return bos.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static Part decode(byte[] data) throws IOException {
		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		if (in.readInt() != MAGIC) {
			throw new IOException("bad geometry magic");
		}
		final Part p = new Part();
		p.rev = in.readInt();
		p.part = in.readUnsignedShort();
		p.parts = in.readUnsignedShort();
		final int count = in.readInt();
		for (int i = 0; i < count; i++) {
			final Geometry.Section s = new Geometry.Section();
			s.fromStationId = in.readLong();
			s.toStationId = in.readLong();
			s.distance = in.readFloat();
			s.mode = in.readUTF();
			final int parts = in.readUnsignedShort();
			s.partTypes = new String[parts];
			s.partFrom = new float[parts];
			s.partTo = new float[parts];
			for (int k = 0; k < parts; k++) {
				s.partTypes[k] = in.readUTF();
				s.partFrom[k] = in.readFloat();
				s.partTo[k] = in.readFloat();
			}
			final int n = in.readUnsignedShort();
			s.points = new int[n * 3];
			int px = 0, py = 0, pz = 0;
			for (int j = 0; j < n; j++) {
				px += readVar(in);
				py += readVar(in);
				pz += readVar(in);
				s.points[j * 3] = px;
				s.points[j * 3 + 1] = py;
				s.points[j * 3 + 2] = pz;
			}
			p.sections.add(s);
		}
		return p;
	}

	/** ZigZag + LEB128 */
	private static void writeVar(DataOutputStream out, int v) throws IOException {
		int z = (v << 1) ^ (v >> 31);
		while ((z & ~0x7F) != 0) {
			out.writeByte((z & 0x7F) | 0x80);
			z >>>= 7;
		}
		out.writeByte(z);
	}

	private static int readVar(DataInputStream in) throws IOException {
		int result = 0;
		int shift = 0;
		while (true) {
			final int b = in.readUnsignedByte();
			result |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				break;
			}
			shift += 7;
			if (shift > 35) {
				throw new IOException("bad varint");
			}
		}
		return (result >>> 1) ^ -(result & 1);
	}
}
