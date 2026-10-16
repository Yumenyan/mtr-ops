package jp.mtrops.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Topology のバイナリ表現。
 *
 * 優先度による削減(上限バイト数を超えたら上から順に落とす):
 *   T0 駅・路線・車庫・車庫線の基本情報(落とさない)
 *   T1 路線の停車駅の並び / 終点名(路線図・次駅表示に使う。落とすと路線図が描けなくなる)
 *   ※ 現実的な規模(駅300・路線100・各40駅)でも約50KB程度なので、通常は全部載る。
 */
public final class TopologyCodec {

	private static final int MAGIC = 0x4D4F5432; // "MOT2"

	private TopologyCodec() {
	}

	public static byte[] encode(Topology t, int maxBytes) {
		byte[] full = write(t, true);
		if (full.length <= maxBytes) {
			return full;
		}
		return write(t, false);
	}

	private static byte[] write(Topology t, boolean withSequences) {
		try {
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			final DataOutputStream out = new DataOutputStream(bos);
			out.writeInt(MAGIC);
			out.writeInt(t.rev);
			out.writeBoolean(withSequences);

			out.writeInt(t.stations.size());
			for (final Topology.Station s : t.stations) {
				out.writeLong(s.id);
				out.writeUTF(s.name);
				out.writeInt(s.x);
				out.writeInt(s.z);
				out.writeInt(s.color);
				out.writeShort(s.zone);
			}

			out.writeInt(t.routes.size());
			for (final Topology.Route r : t.routes) {
				out.writeLong(r.id);
				out.writeUTF(r.name);
				out.writeUTF(r.number);
				out.writeInt(r.color);
				out.writeByte(r.type);
				out.writeByte(r.circular);
				out.writeByte(r.mode);
				out.writeBoolean(r.hidden);
				if (withSequences) {
					out.writeUTF(r.destination);
					out.writeShort(r.stationIds.length);
					for (final long id : r.stationIds) {
						out.writeLong(id);
					}
				}
			}

			out.writeInt(t.depots.size());
			for (final Topology.Depot d : t.depots) {
				out.writeLong(d.id);
				out.writeUTF(d.name);
				out.writeInt(d.color);
				out.writeInt(d.x);
				out.writeInt(d.z);
				out.writeUTF(d.dimension);
				out.writeShort(d.routeIds.length);
				for (final long id : d.routeIds) {
					out.writeLong(id);
				}
			}

			out.writeInt(t.sidings.size());
			for (final Topology.Siding s : t.sidings) {
				out.writeLong(s.id);
				out.writeUTF(s.name);
				out.writeLong(s.depotId);
				out.writeInt(s.x);
				out.writeInt(s.z);
				out.writeUTF(s.trainType);
				out.writeShort(s.maxTrains);
				out.writeBoolean(s.unlimited);
			}
			out.flush();
			return bos.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static Topology decode(byte[] data) throws IOException {
		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		if (in.readInt() != MAGIC) {
			throw new IOException("bad topology magic");
		}
		final Topology t = new Topology();
		t.rev = in.readInt();
		final boolean withSequences = in.readBoolean();

		final int stationCount = in.readInt();
		for (int i = 0; i < stationCount; i++) {
			final Topology.Station s = new Topology.Station();
			s.id = in.readLong();
			s.name = in.readUTF();
			s.x = in.readInt();
			s.z = in.readInt();
			s.color = in.readInt();
			s.zone = in.readShort();
			t.stations.add(s);
		}

		final int routeCount = in.readInt();
		for (int i = 0; i < routeCount; i++) {
			final Topology.Route r = new Topology.Route();
			r.id = in.readLong();
			r.name = in.readUTF();
			r.number = in.readUTF();
			r.color = in.readInt();
			r.type = in.readByte();
			r.circular = in.readByte();
			r.mode = in.readByte();
			r.hidden = in.readBoolean();
			if (withSequences) {
				r.destination = in.readUTF();
				final int n = in.readUnsignedShort();
				r.stationIds = new long[n];
				for (int j = 0; j < n; j++) {
					r.stationIds[j] = in.readLong();
				}
			}
			t.routes.add(r);
		}

		final int depotCount = in.readInt();
		for (int i = 0; i < depotCount; i++) {
			final Topology.Depot d = new Topology.Depot();
			d.id = in.readLong();
			d.name = in.readUTF();
			d.color = in.readInt();
			d.x = in.readInt();
			d.z = in.readInt();
			d.dimension = in.readUTF();
			final int n = in.readUnsignedShort();
			d.routeIds = new long[n];
			for (int j = 0; j < n; j++) {
				d.routeIds[j] = in.readLong();
			}
			t.depots.add(d);
		}

		final int sidingCount = in.readInt();
		for (int i = 0; i < sidingCount; i++) {
			final Topology.Siding s = new Topology.Siding();
			s.id = in.readLong();
			s.name = in.readUTF();
			s.depotId = in.readLong();
			s.x = in.readInt();
			s.z = in.readInt();
			s.trainType = in.readUTF();
			s.maxTrains = in.readUnsignedShort();
			s.unlimited = in.readBoolean();
			t.sidings.add(s);
		}
		return t;
	}
}
