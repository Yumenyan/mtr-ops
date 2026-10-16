package jp.mtrops.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 台帳のバイナリ表現。ワールド保存・サーバー→クライアント同期・クライアント→サーバー(1車庫分)で共用する。
 * C2S は 1.19.2 のカスタムペイロード上限(32767バイト)があるため、encodeDepot は
 * 上限を超えるとき IOException ではなく null を返す。
 */
public final class RegistryCodec {

	private static final int MAGIC = 0x4D4F5031; // "MOP1"
	private static final int VERSION = 5;
	public static final int C2S_LIMIT = 30_000;

	private RegistryCodec() {
	}

	public static byte[] encodeRegistry(OpsRegistry reg, boolean withAssignments) {
		try {
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			final DataOutputStream out = new DataOutputStream(bos);
			out.writeInt(MAGIC);
			out.writeByte(VERSION);
			out.writeInt(reg.depots.size());
			for (final OpsRegistry.DepotRecord d : reg.depots.values()) {
				writeDepot(out, d);
			}
			if (withAssignments) {
				out.writeInt(reg.assignments.size());
				for (final Map.Entry<Long, OpsRegistry.Assign> e : reg.assignments.entrySet()) {
					out.writeLong(e.getKey());
					out.writeLong(e.getValue().sidingId);
					out.writeUTF(e.getValue().formationId);
				}
			} else {
				out.writeInt(0);
			}
			out.flush();
			return bos.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static OpsRegistry decodeRegistry(byte[] data) throws IOException {
		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		if (in.readInt() != MAGIC) {
			throw new IOException("bad magic");
		}
		final int version = in.readByte();
		if (version > VERSION) {
			throw new IOException("unsupported registry version " + version);
		}
		final OpsRegistry reg = new OpsRegistry();
		final int depotCount = in.readInt();
		for (int i = 0; i < depotCount; i++) {
			final OpsRegistry.DepotRecord d = readDepot(in, version);
			reg.depots.put(d.depotId, d);
		}
		final int assignCount = in.readInt();
		for (int i = 0; i < assignCount; i++) {
			final long trainId = in.readLong();
			final long sidingId = in.readLong();
			final String formationId = in.readUTF();
			reg.assignments.put(trainId, new OpsRegistry.Assign(sidingId, formationId));
		}
		return reg;
	}

	/** クライアント→サーバー: 変更した1車庫だけを送る。baseRev は編集開始時点のrev。 */
	public static byte[] encodeDepotUpdate(OpsRegistry.DepotRecord depot, int baseRev) {
		try {
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			final DataOutputStream out = new DataOutputStream(bos);
			out.writeInt(baseRev);
			out.writeByte(VERSION);
			writeDepot(out, depot);
			out.flush();
			final byte[] bytes = bos.toByteArray();
			return bytes.length > C2S_LIMIT ? null : bytes;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static final class DepotUpdate {
		public int baseRev;
		public OpsRegistry.DepotRecord depot;
	}

	public static DepotUpdate decodeDepotUpdate(byte[] data) throws IOException {
		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		final DepotUpdate u = new DepotUpdate();
		u.baseRev = in.readInt();
		final int version = in.readByte();
		if (version > VERSION) {
			throw new IOException("unsupported depot update version " + version);
		}
		u.depot = readDepot(in, version);
		return u;
	}

	private static void writeDepot(DataOutputStream out, OpsRegistry.DepotRecord d) throws IOException {
		out.writeLong(d.depotId);
		out.writeUTF(d.dimension == null ? "" : d.dimension);
		out.writeInt(d.rev);
		out.writeInt(d.sidings.size());
		for (final Map.Entry<Long, List<OpsRegistry.Formation>> e : d.sidings.entrySet()) {
			out.writeLong(e.getKey());
			out.writeShort(e.getValue().size());
			for (final OpsRegistry.Formation f : e.getValue()) {
				out.writeUTF(f.id);
				out.writeUTF(f.series == null ? "" : f.series);
				out.writeByte(f.cars);
				out.writeByte(f.carNumbers.size());
				for (final String n : f.carNumbers) {
					out.writeUTF(n);
				}
			}
		}
		// v2: 車庫線ごとの車番範囲
		out.writeShort(d.ranges.size());
		for (final Map.Entry<Long, OpsRegistry.NumberRange> e : d.ranges.entrySet()) {
			final OpsRegistry.NumberRange r = e.getValue();
			out.writeLong(e.getKey());
			out.writeUTF(r.prefix);
			out.writeUTF(r.suffix);
			out.writeInt(r.start);
			out.writeInt(r.end);
			out.writeByte(r.width);
		}
		// v3: 車庫ごとの除外車番
		out.writeUTF(d.exclude == null ? "" : d.exclude);
		// v4: ランダム採番の車庫線
		int rn = 0;
		for (final OpsRegistry.NumberRange r : d.ranges.values()) {
			rn += r.random ? 1 : 0;
		}
		out.writeShort(rn);
		for (final Map.Entry<Long, OpsRegistry.NumberRange> e : d.ranges.entrySet()) {
			if (e.getValue().random) {
				out.writeLong(e.getKey());
			}
		}
		// v5: 各車両の番号の付け方
		int cn = 0;
		for (final OpsRegistry.NumberRange r : d.ranges.values()) {
			cn += r.carScheme != 0 ? 1 : 0;
		}
		out.writeShort(cn);
		for (final Map.Entry<Long, OpsRegistry.NumberRange> e : d.ranges.entrySet()) {
			if (e.getValue().carScheme != 0) {
				out.writeLong(e.getKey());
				out.writeByte(e.getValue().carScheme);
				out.writeByte(e.getValue().digitPos);
			}
		}
	}

	private static OpsRegistry.DepotRecord readDepot(DataInputStream in, int version) throws IOException {
		final OpsRegistry.DepotRecord d = new OpsRegistry.DepotRecord();
		d.depotId = in.readLong();
		d.dimension = in.readUTF();
		d.rev = in.readInt();
		final int sidingCount = in.readInt();
		for (int i = 0; i < sidingCount; i++) {
			final long sidingId = in.readLong();
			final int n = in.readUnsignedShort();
			final List<OpsRegistry.Formation> list = new ArrayList<>(n);
			for (int j = 0; j < n; j++) {
				final OpsRegistry.Formation f = new OpsRegistry.Formation();
				f.id = in.readUTF();
				f.series = in.readUTF();
				f.cars = in.readUnsignedByte();
				final int carCount = in.readUnsignedByte();
				for (int k = 0; k < carCount; k++) {
					f.carNumbers.add(in.readUTF());
				}
				list.add(f);
			}
			d.sidings.put(sidingId, list);
		}
		if (version >= 2) {
			final int rc = in.readUnsignedShort();
			for (int i = 0; i < rc; i++) {
				final long sid = in.readLong();
				final OpsRegistry.NumberRange r = new OpsRegistry.NumberRange();
				r.prefix = in.readUTF();
				r.suffix = in.readUTF();
				r.start = in.readInt();
				r.end = in.readInt();
				r.width = in.readUnsignedByte();
				d.ranges.put(sid, r);
			}
		}
		if (version >= 3) {
			d.exclude = in.readUTF();
		}
		if (version >= 4) {
			final int rn = in.readUnsignedShort();
			for (int i = 0; i < rn; i++) {
				final OpsRegistry.NumberRange r = d.ranges.get(in.readLong());
				if (r != null) {
					r.random = true;
				}
			}
		}
		if (version >= 5) {
			final int cn = in.readUnsignedShort();
			for (int i = 0; i < cn; i++) {
				final long sid = in.readLong();
				final int scheme = in.readUnsignedByte();
				final int pos = in.readUnsignedByte();
				final OpsRegistry.NumberRange r = d.ranges.get(sid);
				if (r != null) {
					r.carScheme = scheme;
					r.digitPos = pos;
				}
			}
		}
		return d;
	}
}
