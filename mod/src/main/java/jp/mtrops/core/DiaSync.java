package jp.mtrops.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** カスタムダイヤのサーバー→クライアント同期の形式(S2C DIA / DIA_LIVE)。 */
public final class DiaSync {

	/** クライアントが持つカスタムダイヤの状態(不変として扱う)。 */
	public static final class State {
		public String zone = "";
		/** 受信時点のUTCからのずれ(秒) */
		public int utcOffset;
		public DiaModel.Config config = new DiaModel.Config();
		public Map<Long, DiaModel.Profile> profiles = new LinkedHashMap<>();
		public List<Long> customDepots = new ArrayList<>();
		/** 駅間ごとの走行時間の実績比率(DiaStretch.ratios)。サーバーと同じ計算にするために同期する */
		public Map<String, Double> ratios = new java.util.HashMap<>();
		/** 設定と理想走行から作り直した時刻表(サーバーと同じ計算) */
		public DiaPlanner.Result plan = new DiaPlanner.Result();
		public boolean received;
	}

	public static final class Live {
		/** サーバーの「その日の秒」(受信時点) */
		public double serverNow;
		public long receivedMillis;
		public final List<DiaRuntime.View> trains = new ArrayList<>();
	}

	private DiaSync() {
	}

	public static byte[] encode(String zone, int utcOffset, DiaModel.Config config, Map<Long, DiaModel.Profile> profiles, List<Long> customDepots, Map<String, Double> ratios) {
		try {
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			final DataOutputStream out = new DataOutputStream(bos);
			out.writeUTF(zone);
			out.writeInt(utcOffset);
			final byte[] c = DiaCodec.encodeConfig(config);
			out.writeInt(c.length);
			out.write(c);
			final byte[] p = DiaCodec.encodeProfiles(profiles);
			out.writeInt(p.length);
			out.write(p);
			final List<Long> custom = new ArrayList<>(customDepots);
			Collections.sort(custom);
			out.writeInt(custom.size());
			for (final Long d : custom) {
				out.writeLong(d);
			}
			out.writeInt(ratios.size());
			for (final Map.Entry<String, Double> e : ratios.entrySet()) {
				out.writeUTF(e.getKey());
				out.writeDouble(e.getValue());
			}
			out.flush();
			return bos.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static State decode(byte[] data) throws IOException {
		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		final State s = new State();
		s.zone = in.readUTF();
		s.utcOffset = in.readInt();
		s.config = DiaCodec.decodeConfig(read(in));
		s.profiles = DiaCodec.decodeProfiles(read(in));
		final int n = in.readInt();
		if (n < 0 || n > 100_000) {
			throw new IOException("bad count");
		}
		for (int i = 0; i < n; i++) {
			s.customDepots.add(in.readLong());
		}
		final int rn = in.readInt();
		if (rn < 0 || rn > 100_000) {
			throw new IOException("bad ratio count");
		}
		for (int i = 0; i < rn; i++) {
			final String k = in.readUTF();
			s.ratios.put(k, in.readDouble());
		}
		s.plan = DiaPlanner.plan(s.config, s.profiles, s.ratios);
		s.received = true;
		return s;
	}

	private static byte[] read(DataInputStream in) throws IOException {
		final int len = in.readInt();
		if (len < 0 || len > 4_000_000) {
			throw new IOException("bad length");
		}
		final byte[] b = new byte[len];
		in.readFully(b);
		return b;
	}

	public static byte[] encodeLive(double serverNow, List<DiaRuntime.View> views) {
		try {
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			final DataOutputStream out = new DataOutputStream(bos);
			out.writeDouble(serverNow);
			out.writeInt(views.size());
			for (final DiaRuntime.View v : views) {
				out.writeLong(v.trainId);
				out.writeLong(v.routeId);
				out.writeInt(v.no);
				out.writeInt(v.lastStop);
				out.writeInt(v.delay);
			}
			out.flush();
			return bos.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static Live decodeLive(byte[] data) throws IOException {
		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		final Live l = new Live();
		l.serverNow = in.readDouble();
		l.receivedMillis = System.currentTimeMillis();
		final int n = in.readInt();
		if (n < 0 || n > 100_000) {
			throw new IOException("bad count");
		}
		for (int i = 0; i < n; i++) {
			final DiaRuntime.View v = new DiaRuntime.View();
			v.trainId = in.readLong();
			v.routeId = in.readLong();
			v.no = in.readInt();
			v.lastStop = in.readInt();
			v.delay = in.readInt();
			l.trains.add(v);
		}
		return l;
	}

	/** C2S DIA_UPDATE 用: 設定を圧縮する。 */
	public static byte[] deflate(byte[] data) {
		final java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
		d.setInput(data);
		d.finish();
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final byte[] buf = new byte[8192];
		while (!d.finished()) {
			out.write(buf, 0, d.deflate(buf));
		}
		d.end();
		return out.toByteArray();
	}
}
