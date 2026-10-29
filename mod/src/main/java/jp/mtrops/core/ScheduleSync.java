package jp.mtrops.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 時刻表API(/api/schedule)・所要時間API(/api/runtimes)のための同期データ(S2C SCHEDULE、約20秒ごと)。
 *  ・全車庫の理想走行(車庫を出てから各停車駅に着く・出るまでの秒。MTRの走行計算と同じ)
 *  ・今後の出庫予定(UNIXミリ秒。MTRの車庫の出庫時刻から。カスタムダイヤの車庫は時刻表の出庫時刻に置き換わっている)
 *  ・駅間の所要時間の実績(SectionStats)
 */
public final class ScheduleSync {

	private static final int MAGIC = 0x4D4F5343; // "MOSC"

	private ScheduleSync() {
	}

	/** クライアントが持つ状態(不変として扱う)。 */
	public static final class State {
		public boolean received;
		/** サーバーの時計(送信時点) */
		public long serverMillis;
		/** 受信した時点のクライアントの時計 */
		public long receivedMillis;
		public Map<Long, DiaModel.Profile> profiles = new LinkedHashMap<>();
		public Map<Long, long[]> deploys = new LinkedHashMap<>();
		public SectionStats stats = new SectionStats();

		/** サーバーの時刻(ミリ秒) → クライアントの時計での同じ瞬間 */
		public long toClientMillis(long serverMillis) {
			return serverMillis - this.serverMillis + receivedMillis;
		}
	}

	public static byte[] encode(long serverMillis, Map<Long, DiaModel.Profile> profiles, Map<Long, List<Long>> deploys, SectionStats stats, int maxBytes) {
		final Map<Long, DiaModel.Profile> profs = new LinkedHashMap<>(profiles);
		byte[] out = write(serverMillis, profs, deploys, stats);
		// 大きすぎるときは、停車駅の多い車庫の理想走行から落とす(出庫予定と実績は残す)
		while (out.length > maxBytes && !profs.isEmpty()) {
			Long biggest = null;
			int most = -1;
			for (final Map.Entry<Long, DiaModel.Profile> e : profs.entrySet()) {
				if (e.getValue().stops.size() > most) {
					most = e.getValue().stops.size();
					biggest = e.getKey();
				}
			}
			profs.remove(biggest);
			out = write(serverMillis, profs, deploys, stats);
		}
		return out;
	}

	private static byte[] write(long serverMillis, Map<Long, DiaModel.Profile> profiles, Map<Long, List<Long>> deploys, SectionStats stats) {
		try {
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			final DataOutputStream out = new DataOutputStream(bos);
			out.writeInt(MAGIC);
			out.writeLong(serverMillis);
			final byte[] p = DiaCodec.encodeProfiles(profiles);
			out.writeInt(p.length);
			out.write(p);
			out.writeInt(deploys.size());
			for (final Map.Entry<Long, List<Long>> e : deploys.entrySet()) {
				out.writeLong(e.getKey());
				final List<Long> list = e.getValue();
				final int n = Math.min(list.size(), 2000);
				out.writeShort(n);
				for (int i = 0; i < n; i++) {
					out.writeLong(list.get(i));
				}
			}
			stats.write(out);
			out.flush();
			return bos.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static State decode(byte[] data) throws IOException {
		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		if (in.readInt() != MAGIC) {
			throw new IOException("bad schedule magic");
		}
		final State s = new State();
		s.serverMillis = in.readLong();
		final int len = in.readInt();
		if (len < 0 || len > 4_000_000) {
			throw new IOException("bad length");
		}
		final byte[] p = new byte[len];
		in.readFully(p);
		s.profiles = DiaCodec.decodeProfiles(p);
		final int n = in.readInt();
		if (n < 0 || n > 100_000) {
			throw new IOException("bad depot count");
		}
		for (int i = 0; i < n; i++) {
			final long depot = in.readLong();
			final int k = in.readUnsignedShort();
			final long[] times = new long[k];
			for (int j = 0; j < k; j++) {
				times[j] = in.readLong();
			}
			s.deploys.put(depot, times);
		}
		s.stats = SectionStats.read(in);
		s.received = true;
		s.receivedMillis = System.currentTimeMillis();
		return s;
	}

	/** 理想走行の駅間(停車駅→次の停車駅)ごとの、走行時間と停車時間(秒)。同じ区間が複数の車庫にあれば最初のもの。 */
	public static Map<String, double[]> idealRunTimes(Map<Long, DiaModel.Profile> profiles) {
		final Map<String, double[]> out = new LinkedHashMap<>();
		for (final DiaModel.Profile p : profiles.values()) {
			final List<DiaModel.Stop> stops = new ArrayList<>();
			for (final DiaModel.Stop s : p.stops) {
				if (!s.pass) {
					stops.add(s);
				}
			}
			for (int i = 1; i < stops.size(); i++) {
				final DiaModel.Stop a = stops.get(i - 1);
				final DiaModel.Stop b = stops.get(i);
				if (a.stationId == b.stationId) {
					continue;
				}
				out.putIfAbsent(SectionStats.key(a.stationId, b.stationId), new double[]{Math.max(0, b.arr - a.dep), Math.max(0, a.dep - a.arr)});
			}
		}
		return out;
	}
}
