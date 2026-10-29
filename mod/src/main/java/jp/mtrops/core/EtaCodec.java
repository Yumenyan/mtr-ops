package jp.mtrops.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 列車ごとの到着予想(S2C ETAS、約1秒ごと)。スナップショットの詳細レベルの予算とは別に送る。
 * 1パケットに収まらない分は同じ seq の複数パートに分ける(全部揃ったら差し替え)。
 */
public final class EtaCodec {

	private static final int MAGIC = 0x4D4F4554; // "MOET"

	private EtaCodec() {
	}

	public static final class Entry {
		public long stationId;
		public long platformId;
		public long routeId;
		/** 到着までの見込み(tick) */
		public int ticks;
		/** 時刻表上の到着までの残り(tick)。時刻表が無ければ TrainSample.NO_SCHED */
		public int schedTicks = TrainSample.NO_SCHED;
	}

	/** 受信した全列車分(不変として扱う)。 */
	public static final class Set {
		public int seq;
		public long serverMillis;
		public long receivedMillis;
		public final Map<Long, List<Entry>> byTrain = new HashMap<>();
	}

	public static final class Part {
		public int seq;
		public int part;
		public int parts;
		public long serverMillis;
		public final Map<Long, List<Entry>> byTrain = new HashMap<>();
	}

	public static List<byte[]> encode(List<TrainSample> samples, int seq, long serverMillis, int maxBytes) {
		final List<List<TrainSample>> groups = new ArrayList<>();
		List<TrainSample> cur = new ArrayList<>();
		int size = 0;
		for (final TrainSample s : samples) {
			final int n = count(s);
			if (n == 0) {
				continue;
			}
			final int est = 12 + n * 32;
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
			out.add(write(groups.get(i), seq, i, groups.size(), serverMillis));
		}
		return out;
	}

	private static int count(TrainSample s) {
		return Math.min(TrainSample.MAX_ETAS, Math.min(Math.min(s.etaStationIds.length, s.etaTicks.length),
				Math.min(Math.min(s.etaPlatformIds.length, s.etaRouteIds.length), s.etaSchedTicks.length)));
	}

	private static byte[] write(List<TrainSample> list, int seq, int part, int parts, long serverMillis) {
		try {
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			final DataOutputStream out = new DataOutputStream(bos);
			out.writeInt(MAGIC);
			out.writeInt(seq);
			out.writeShort(part);
			out.writeShort(parts);
			out.writeLong(serverMillis);
			out.writeInt(list.size());
			for (final TrainSample s : list) {
				final int n = count(s);
				out.writeLong(s.id);
				out.writeByte(n);
				for (int i = 0; i < n; i++) {
					out.writeLong(s.etaStationIds[i]);
					out.writeLong(s.etaPlatformIds[i]);
					out.writeLong(s.etaRouteIds[i]);
					out.writeInt(s.etaTicks[i]);
					out.writeInt(s.etaSchedTicks[i]);
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
			throw new IOException("bad eta magic");
		}
		final Part p = new Part();
		p.seq = in.readInt();
		p.part = in.readUnsignedShort();
		p.parts = in.readUnsignedShort();
		p.serverMillis = in.readLong();
		final int trains = in.readInt();
		if (trains < 0 || trains > 100_000) {
			throw new IOException("bad train count");
		}
		for (int t = 0; t < trains; t++) {
			final long id = in.readLong();
			final int n = in.readUnsignedByte();
			final List<Entry> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				final Entry e = new Entry();
				e.stationId = in.readLong();
				e.platformId = in.readLong();
				e.routeId = in.readLong();
				e.ticks = in.readInt();
				e.schedTicks = in.readInt();
				list.add(e);
			}
			p.byTrain.put(id, list);
		}
		return p;
	}
}
