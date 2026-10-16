package jp.mtrops.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 列車スナップショットのバイナリ符号化と、上限バイト数に収めるための「優先度による削減」。
 *
 * 1パケットが maxBytes を超えるとき、次の順に情報を落としていく(番号の大きい方から削る):
 *
 *   L4  各車両ごとの位置・向き            ← 最初に削る(描画の滑らかさ用。無くても列車は描ける)
 *   L3  両数・乗客数・車庫線/車庫ID・系列・車種
 *   L2  高度・向き・前駅/現在駅・進捗%・停車残り時間
 *   L1  次駅・行先
 *   L0  ID・路線・XZ座標・速度・状態・フラグ・遅延秒・編成番号 ← 最後まで残す(必須)
 *
 * L0でも収まらない場合は、異常な列車を先頭にして複数パートに分割して送る(情報は捨てない)。
 */
public final class SnapshotCodec {

	private static final int VERSION = 1;
	public static final int MAX_LEVEL = 4;
	private static final int RECENT_RESOLVED_INCIDENTS = 20;

	private static final int F_ON_ROUTE = 1;
	private static final int F_MANUAL = 1 << 1;
	private static final int F_DOOR = 1 << 2;
	private static final int F_AT_STOP = 1 << 3;
	// 1 << 4 は旧「消失(ghost)」フラグ。廃止したが互換のため欠番にしておく
	private static final int F_DWELLING = 1 << 5;
	private static final int F_FROZEN = 1 << 6;
	private static final int F_NOPATH = 1 << 7;

	private SnapshotCodec() {
	}

	/** snap.level に実際に採用したレベルを書き戻す。 */
	public static List<byte[]> encode(Snapshot snap, int maxLevel, int maxBytes) {
		final List<Incident> incidents = pickIncidents(snap.incidents);
		for (int level = Math.min(maxLevel, MAX_LEVEL); level >= 0; level--) {
			final byte[] bytes = encodePart(snap, level, 0, 1, snap.trains, incidents);
			if (bytes.length <= maxBytes) {
				snap.level = level;
				final List<byte[]> single = new ArrayList<>();
				single.add(bytes);
				return single;
			}
		}

		// L0でも1パケットに入らない → 重要度(異常な列車)順に並べて、各パートが予算に収まるまで分割数を増やす
		snap.level = 0;
		final List<TrainView> sorted = new ArrayList<>(snap.trains);
		sorted.sort(Comparator.<TrainView>comparingInt(v -> -v.status.ordinal()));
		final int emptySize = encodePart(snap, 0, 0, 1, new ArrayList<>(), new ArrayList<>()).length;
		final int incidentBytes = encodePart(snap, 0, 0, 1, new ArrayList<>(), incidents).length - emptySize;
		final int sample = Math.max(1, Math.min(sorted.size(), 64));
		final int avg = Math.max(1, (encodePart(snap, 0, 0, 1, sorted.subList(0, Math.min(sorted.size(), sample)), new ArrayList<>()).length - emptySize) / sample);
		int perPart = Math.max(1, (maxBytes - emptySize - incidentBytes) / (avg + 8));
		while (true) {
			final int parts = Math.max(1, (sorted.size() + perPart - 1) / perPart);
			final List<byte[]> out = new ArrayList<>();
			boolean fits = true;
			for (int p = 0; p < parts && fits; p++) {
				final List<TrainView> slice = sorted.subList(Math.min(sorted.size(), p * perPart), Math.min(sorted.size(), (p + 1) * perPart));
				final byte[] bytes = encodePart(snap, 0, p, parts, slice, p == 0 ? incidents : new ArrayList<>());
				if (bytes.length > maxBytes && perPart > 1) {
					fits = false;
				}
				out.add(bytes);
			}
			if (fits) {
				return out;
			}
			perPart = Math.max(1, perPart * 85 / 100);
		}
	}

	/** 指定レベルで1パケットにしたときのサイズ(テスト・診断用)。 */
	public static int sizeAtLevel(Snapshot snap, int level) {
		return encodePart(snap, level, 0, 1, snap.trains, pickIncidents(snap.incidents)).length;
	}

	private static List<Incident> pickIncidents(List<Incident> all) {
		final List<Incident> out = new ArrayList<>();
		int resolved = 0;
		for (final Incident i : all) {
			if (i.isActive()) {
				out.add(i);
			} else if (resolved < RECENT_RESOLVED_INCIDENTS) {
				out.add(i);
				resolved++;
			}
		}
		return out;
	}

	private static byte[] encodePart(Snapshot snap, int level, int part, int parts, List<TrainView> trains, List<Incident> incidents) {
		try {
			final ByteArrayOutputStream bos = new ByteArrayOutputStream(64 + trains.size() * 48);
			final DataOutputStream out = new DataOutputStream(bos);
			out.writeByte(VERSION);
			out.writeInt(snap.seq);
			out.writeByte(level);
			out.writeByte(part);
			out.writeByte(parts);
			out.writeLong(snap.gameTick);
			out.writeInt(snap.dayTime);
			out.writeLong(snap.wallMillis);
			out.writeInt(trains.size());
			for (final TrainView v : trains) {
				writeTrain(out, v, level);
			}
			out.writeInt(incidents.size());
			for (final Incident i : incidents) {
				writeIncident(out, i);
			}
			out.flush();
			return bos.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void writeTrain(DataOutputStream out, TrainView v, int level) throws IOException {
		final TrainSample s = v.s;
		out.writeLong(s.id);
		out.writeLong(s.routeId);
		out.writeFloat((float) s.x);
		out.writeFloat((float) s.z);
		out.writeShort(clampShort(Math.round(s.speedMps * 3.6F * 10F)));
		out.writeByte(v.status.code());
		int flags = 0;
		flags |= s.onRoute ? F_ON_ROUTE : 0;
		flags |= s.manual ? F_MANUAL : 0;
		flags |= s.doorOpen ? F_DOOR : 0;
		flags |= s.atStop ? F_AT_STOP : 0;
		flags |= s.dwelling ? F_DWELLING : 0;
		flags |= (s.suspect & TrainSample.SUSPECT_FROZEN) != 0 ? F_FROZEN : 0;
		flags |= (s.suspect & TrainSample.SUSPECT_NOPATH) != 0 ? F_NOPATH : 0;
		out.writeByte(flags);
		out.writeShort(clampShort(v.delaySec));
		out.writeUTF(s.formationId == null ? "" : s.formationId);

		if (level >= 1) {
			out.writeLong(s.nextStationId);
			out.writeLong(s.destStationId);
			final int n = Math.min(TrainSample.MAX_ETAS, Math.min(s.etaStationIds.length, s.etaTicks.length));
			out.writeByte(n);
			for (int i = 0; i < n; i++) {
				out.writeLong(s.etaStationIds[i]);
				out.writeInt(s.etaTicks[i]);
			}
		}
		if (level >= 2) {
			out.writeFloat((float) s.y);
			out.writeShort(Math.round(normalizeYaw(s.yaw) * 10F) % 3600);
			out.writeLong(s.prevStationId);
			out.writeLong(s.curStationId);
			out.writeByte(v.progressPct);
			out.writeShort(clampShort(s.dwellRemainTicks < 0 ? -1 : s.dwellRemainTicks / 20));
		}
		if (level >= 3) {
			out.writeByte(s.cars);
			out.writeShort(clampShort(s.passengers));
			out.writeLong(s.sidingId);
			out.writeLong(s.depotId);
			out.writeUTF(s.series == null ? "" : s.series);
			out.writeUTF(s.trainType == null ? "" : s.trainType);
		}
		if (level >= 4) {
			final int n = s.carX == null ? 0 : Math.min(32, s.carX.length);
			out.writeByte(n);
			for (int i = 0; i < n; i++) {
				out.writeFloat(s.carX[i]);
				out.writeFloat(s.carZ[i]);
				out.writeShort(Math.round(normalizeYaw(s.carYaw[i]) * 10F) % 3600);
			}
		}
	}

	private static void writeIncident(DataOutputStream out, Incident i) throws IOException {
		out.writeInt(i.id);
		out.writeByte(i.type.ordinal());
		out.writeByte(i.severity.ordinal());
		out.writeLong(i.trainId);
		out.writeLong(i.routeId);
		out.writeUTF(i.formation == null ? "" : i.formation);
		out.writeUTF(i.message == null ? "" : i.message);
		out.writeInt(i.seconds);
		out.writeLong(i.sinceTick);
		out.writeLong(i.lastTick);
		out.writeLong(i.resolvedTick);
	}

	// ------------------------------------------------------------------ decode

	public static final class Part {
		public Snapshot snapshot = new Snapshot();
		public int part;
		public int parts;
	}

	public static Part decode(byte[] data) throws IOException {
		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		final int version = in.readUnsignedByte();
		if (version > VERSION) {
			throw new IOException("unsupported snapshot version " + version);
		}
		final Part p = new Part();
		final Snapshot snap = p.snapshot;
		snap.seq = in.readInt();
		snap.level = in.readUnsignedByte();
		p.part = in.readUnsignedByte();
		p.parts = in.readUnsignedByte();
		snap.gameTick = in.readLong();
		snap.dayTime = in.readInt();
		snap.wallMillis = in.readLong();
		final int trainCount = in.readInt();
		for (int i = 0; i < trainCount; i++) {
			snap.trains.add(readTrain(in, snap.level));
		}
		final int incidentCount = in.readInt();
		for (int i = 0; i < incidentCount; i++) {
			snap.incidents.add(readIncident(in));
		}
		return p;
	}

	private static TrainView readTrain(DataInputStream in, int level) throws IOException {
		final TrainView v = new TrainView();
		final TrainSample s = v.s;
		s.id = in.readLong();
		s.routeId = in.readLong();
		s.x = in.readFloat();
		s.z = in.readFloat();
		s.speedMps = in.readShort() / 36F;
		v.status = TrainStatus.fromCode(in.readUnsignedByte());
		final int flags = in.readUnsignedByte();
		s.onRoute = (flags & F_ON_ROUTE) != 0;
		s.manual = (flags & F_MANUAL) != 0;
		s.doorOpen = (flags & F_DOOR) != 0;
		s.atStop = (flags & F_AT_STOP) != 0;
		s.dwelling = (flags & F_DWELLING) != 0;
		s.suspect = ((flags & F_FROZEN) != 0 ? TrainSample.SUSPECT_FROZEN : 0) | ((flags & F_NOPATH) != 0 ? TrainSample.SUSPECT_NOPATH : 0);
		v.delaySec = in.readShort();
		final String formation = in.readUTF();
		s.formationId = formation.isEmpty() ? null : formation;
		s.y = Double.NaN;
		s.yaw = Float.NaN;

		if (level >= 1) {
			s.nextStationId = in.readLong();
			s.destStationId = in.readLong();
			final int n = in.readUnsignedByte();
			s.etaStationIds = new long[n];
			s.etaTicks = new int[n];
			for (int i = 0; i < n; i++) {
				s.etaStationIds[i] = in.readLong();
				s.etaTicks[i] = in.readInt();
			}
		}
		if (level >= 2) {
			s.y = in.readFloat();
			s.yaw = in.readShort() / 10F;
			s.prevStationId = in.readLong();
			s.curStationId = in.readLong();
			v.progressPct = in.readByte();
			final int dwell = in.readShort();
			s.dwellRemainTicks = dwell < 0 ? -1 : dwell * 20;
		}
		if (level >= 3) {
			s.cars = in.readUnsignedByte();
			s.passengers = in.readShort();
			s.sidingId = in.readLong();
			s.depotId = in.readLong();
			final String series = in.readUTF();
			s.series = series.isEmpty() ? null : series;
			s.trainType = in.readUTF();
		}
		if (level >= 4) {
			final int n = in.readUnsignedByte();
			if (n > 0) {
				s.carX = new float[n];
				s.carZ = new float[n];
				s.carYaw = new float[n];
				for (int i = 0; i < n; i++) {
					s.carX[i] = in.readFloat();
					s.carZ[i] = in.readFloat();
					s.carYaw[i] = in.readShort() / 10F;
				}
			}
		}
		return v;
	}

	private static Incident readIncident(DataInputStream in) throws IOException {
		final Incident i = new Incident();
		i.id = in.readInt();
		i.type = Incident.Type.fromCode(in.readUnsignedByte());
		i.severity = Incident.Severity.fromCode(in.readUnsignedByte());
		i.trainId = in.readLong();
		i.routeId = in.readLong();
		i.formation = in.readUTF();
		i.message = in.readUTF();
		i.seconds = in.readInt();
		i.sinceTick = in.readLong();
		i.lastTick = in.readLong();
		i.resolvedTick = in.readLong();
		return i;
	}

	private static int clampShort(long v) {
		return (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
	}

	private static float normalizeYaw(float yaw) {
		float y = yaw % 360F;
		if (y < 0) {
			y += 360F;
		}
		return y;
	}
}
