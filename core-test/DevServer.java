import jp.mtrops.core.*;

import java.nio.file.*;
import java.util.*;

/**
 * Minecraft なしで Webアプリを確認するための開発用サーバー。
 * 本物の core(異常検知・編成割り当て・符号化・API・HTTPサーバー)をそのまま使い、
 * 列車の動きだけをシミュレーションで作る。
 *
 *   core-test/dev-server.sh [port]      → http://127.0.0.1:8765/
 *
 * シナリオ(異常の見え方を確認するため、しきい値を短くしてある):
 *   +15秒  環状線の1本が走行中に停止 → 停止 → 運行異常
 *   +40秒  港南線の1本が消失(列車消失 → 約3分後に表示から消える)
 *   常時   星陽線に遅延列車(90秒)と大幅遅延列車(400秒)
 */
public class DevServer {

	record St(long id, String name, int x, int z, int zone) {
	}

	static final class Line {
		long id;
		String name, number = "", dest = "";
		int color;
		boolean loop;
		List<St> sts = new ArrayList<>();
		double[] cum;
		double total;

		void init() {
			cum = new double[sts.size()];
			double d = 0;
			for (int i = 0; i < sts.size(); i++) {
				if (i > 0) d += dist(sts.get(i - 1), sts.get(i));
				cum[i] = d;
			}
			total = d + (loop ? dist(sts.get(sts.size() - 1), sts.get(0)) : 0);
		}

		double[] pointAt(double pos) {
			final int n = sts.size();
			final int segs = loop ? n : n - 1;
			double p = ((pos % total) + total) % total;
			for (int i = 0; i < segs; i++) {
				final St a = sts.get(i), b = sts.get((i + 1) % n);
				final double len = dist(a, b);
				final double start = i == 0 ? 0 : cum[i];
				if (p <= start + len || i == segs - 1) {
					final double f = Math.max(0, Math.min(1, (p - start) / len));
					final double x = a.x() + (b.x() - a.x()) * f, z = a.z() + (b.z() - a.z()) * f;
					final double yaw = Math.toDegrees(Math.atan2(b.x() - a.x(), -(b.z() - a.z())));
					return new double[]{x, z, (yaw + 360) % 360};
				}
			}
			return new double[]{sts.get(0).x(), sts.get(0).z(), 0};
		}

		/** 次に止まる駅のindexと、そこまでの距離 */
		int nextIndex(double pos, double[] distOut) {
			final int n = sts.size();
			for (int i = 0; i < n; i++) {
				if (cum[i] > pos + 0.01) {
					distOut[0] = cum[i] - pos;
					return i;
				}
			}
			if (loop) {
				distOut[0] = total - pos;
				return 0;
			}
			distOut[0] = 0;
			return n - 1;
		}
	}

	static double dist(St a, St b) {
		return Math.hypot(a.x() - b.x(), a.z() - b.z());
	}

	static final class Sim {
		long id, sidingId, depotId;
		Line line;
		double pos, speed;
		int dwellLeftTicks;
		boolean atStop;
		int curIdx = -1, lastIdx = -1;
		int delaySec;
		boolean frozen, hidden;
	}

	static long sid = 1000;

	static St st(String name, int x, int z) {
		return new St(++sid, name, x, z, 1);
	}

	public static void main(String[] args) throws Exception {
		final int port = args.length > 0 ? Integer.parseInt(args[0]) : 8765;
		final Path webDir = Paths.get(args.length > 1 ? args[1] : "web").toAbsolutePath();

		// ---- 路線 ----
		final St chuo = st("星陽中央[SA03][KB02]|Seiyo Central", 0, 0);
		final St hokusei = st("北星台|Hokuseidai", 0, -900);
		final St ohkawa = st("大川端|Okawabata", 700, -600);
		final St sakura = st("東桜ヶ丘|Higashi-Sakuragaoka", 1500, -350);
		final St konan = st("港南|Konan", 1300, 300);
		final St shinmachi = st("新町|Shinmachi", 650, 650);
		final St midori = st("緑ヶ原|Midorigahara", -500, 700);
		final St shiratori = st("白鳥|Shiratori", -1100, 250);
		final St takami = st("高見台|Takamidai", -900, -400);
		final St kaihin = st("海浜公園|Kaihin-koen", 1500, 1200);
		final St kuko = st("空港前|Kuko-mae", 2100, 1500);
		final St nanpu = st("南風原|Nanpubaru", 300, 1500);
		final St seiyoWest = st("西星陽[SA01]|Nishi-Seiyo", -1700, -50);
		final St seiyoEast = st("東星陽[SA-06]|Higashi-Seiyo", 2300, -200);

		final List<Line> lines = new ArrayList<>();
		final Line l1 = line(1, "星陽線(SAKB)|Seiyo Line", 0x0072BC, false, "S", seiyoWest, takami, chuo, ohkawa, sakura, seiyoEast);
		final Line l2 = line(2, "港南線|Konan Line", 0xF39700, false, "K", hokusei, chuo, shinmachi, nanpu);
		final Line l3 = line(3, "環状線|Loop Line", 0x00A95C, true, "", chuo, ohkawa, konan, shinmachi, midori, shiratori, takami);
		final Line l4 = line(4, "空港線|Airport Line", 0x009EA7, false, "A", konan, kaihin, kuko);
		// 同じ路線名の系統(短い区間運転・逆向き)。Web側で1本のラインに自動でマージされる
		final Line l5 = line(5, "星陽線(SAKB)|Seiyo Line", 0x0072BC, false, "S", chuo, ohkawa, sakura, seiyoEast);
		final Line l6 = line(6, "星陽線(SAKB)|Seiyo Line", 0x0072BC, false, "S", seiyoEast, sakura, ohkawa, chuo, takami, seiyoWest);
		lines.addAll(List.of(l1, l2, l3, l4, l5, l6));

		final Topology topo = new Topology();
		topo.rev = 1;
		final Set<Long> added = new HashSet<>();
		for (final Line l : lines) {
			for (final St s : l.sts) {
				if (added.add(s.id())) {
					final Topology.Station o = new Topology.Station();
					o.id = s.id(); o.name = s.name(); o.x = s.x(); o.z = s.z(); o.color = 0; o.zone = s.zone();
					topo.stations.add(o);
				}
			}
			final Topology.Route r = new Topology.Route();
			r.id = l.id; r.name = l.name; r.number = l.number; r.color = l.color; r.circular = l.loop ? 1 : 0;
			r.stationIds = l.sts.stream().mapToLong(St::id).toArray();
			topo.routes.add(r);
		}
		// 環状線は最初の駅に戻る表示のため、先頭を末尾にも付ける
		topo.routes.get(2).stationIds = appendFirst(topo.routes.get(2).stationIds);

		final long depot1 = 7001, depot2 = 7002;
		final long sd11 = 7101, sd12 = 7102, sd21 = 7201, sd22 = 7202;
		addDepot(topo, depot1, "星陽車両基地|Seiyo Depot", 0xE60012, -1500, -300, new long[]{1, 3});
		addDepot(topo, depot2, "港南車庫|Konan Depot", 0xF39700, 800, 900, new long[]{2, 4});
		addSiding(topo, sd11, "1番線", depot1, -1500, -280, "e233", 8);
		addSiding(topo, sd12, "2番線", depot1, -1500, -320, "e233", 8);
		addSiding(topo, sd21, "1番線", depot2, 800, 880, "e235", 6);
		addSiding(topo, sd22, "2番線", depot2, 800, 920, "e235", 6);

		// ---- 台帳(編成) ----
		final OpsRegistry registry = new OpsRegistry();
		registry.applyDepotUpdate(depotRecord(depot1, Map.of(sd11, List.of("1001F", "1002F", "1003F", "1004F"), sd12, List.of("1005F", "1006F", "1007F"))), 0, null);
		final OpsRegistry.DepotRecord rec2 = depotRecord(depot2, Map.of(sd21, List.of("2001F", "2002F", "2003F"), sd22, List.of()));
		final OpsRegistry.NumberRange rng = new OpsRegistry.NumberRange();
		rng.suffix = "F"; rng.start = 2101; rng.end = 2110;   // 2番線は範囲指定のみ → 出庫順に自動採番
		rec2.ranges.put(sd22, rng);
		registry.applyDepotUpdate(rec2, 0, null);

		// ---- 列車 ----
		final List<Sim> sims = new ArrayList<>();
		long tid = 90001;
		final Object[][] plan = {
				{l1, 0.00, sd11, depot1, 0}, {l1, 0.25, sd11, depot1, 0}, {l1, 0.5, sd11, depot1, 90}, {l1, 0.75, sd12, depot1, 400},
				{l2, 0.05, sd21, depot2, 0}, {l2, 0.35, sd21, depot2, 0}, {l2, 0.65, sd22, depot2, 0},
				{l3, 0.0, sd12, depot1, 0}, {l3, 0.25, sd12, depot1, 0}, {l3, 0.5, sd11, depot1, 0}, {l3, 0.75, sd11, depot1, 0},
				{l4, 0.1, sd22, depot2, 0}, {l4, 0.6, sd21, depot2, 0},
				{l5, 0.2, sd11, depot1, 150}, {l6, 0.30, sd11, depot1, 0}, {l6, 0.33, sd12, depot1, 0}, {l6, 0.36, sd12, depot1, 200}, {l6, 0.7, sd11, depot1, 0},
		};
		for (final Object[] p : plan) {
			final Sim s = new Sim();
			s.id = tid++;
			s.line = (Line) p[0];
			s.pos = ((Double) p[1]) * s.line.total;
			s.sidingId = (Long) p[2];
			s.depotId = (Long) p[3];
			s.delaySec = (Integer) p[4];
			sims.add(s);
		}
		final Sim stopper = sims.get(8);   // 環状線: 途中で止まる
		final Sim vanisher = sims.get(5);  // 港南線: 消える

		// ---- core の組み立て ----
		final OpsConfig cfg = new OpsConfig();
		cfg.stoppedSec = 20;
		cfg.sampleTicks = 10;
		final FormationAssigner assigner = new FormationAssigner(registry);
		final AnomalyDetector detector = new AnomalyDetector(cfg);
		final ClientStore store = new ClientStore();
		store.setTopology(topo);
		store.setRegistry(registry);
		// 線形(駅間距離): 空港線以外は、少し曲げた折れ線で「計測済み」にする。空港線は未計測(直線で補完)の見本。
		final Geometry geo = new Geometry();
		geo.rev = 1;
		for (final Line l : lines) {
			if (l.id == 4) continue;
			for (int i = 1; i < l.sts.size(); i++) {
				final St a = l.sts.get(i - 1), b = l.sts.get(i);
				final double mx = (a.x() + b.x()) / 2.0 + (b.z() - a.z()) * 0.08, mz = (a.z() + b.z()) / 2.0 - (b.x() - a.x()) * 0.08;
				final Geometry.Section sec = new Geometry.Section();
				sec.fromStationId = a.id(); sec.toStationId = b.id();
				sec.points = new int[]{a.x(), 70, a.z(), (int) mx, 70, (int) mz, b.x(), 70, b.z()};
				sec.distance = Math.hypot(mx - a.x(), mz - a.z()) + Math.hypot(b.x() - mx, b.z() - mz);
				geo.sections.add(sec);
			}
		}
		for (final byte[] part : GeometryCodec.encode(geo, 900_000)) {
			try { store.acceptGeometryPart(GeometryCodec.decode(part)); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
		}

		final ApiHandler api = new ApiHandler(store, id -> id.equals("e233") ? "E233系1000番台|E233 Series" : id.equals("e235") ? "E235系|E235 Series" : "");
		final LocalHttpServer server = new LocalHttpServer(port, api, rel -> {
			try {
				final Path p = webDir.resolve(rel).normalize();
				return p.startsWith(webDir) && Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
			} catch (Exception e) {
				return null;
			}
		}, List.of());
		server.start();
		System.out.println("開発用サーバー: http://127.0.0.1:" + server.port() + "/   (Ctrl+C で終了)");
		System.out.println("Webアプリのディレクトリ: " + webDir);

		long tick = 20 * 600; // ゲーム内時刻の見た目用
		int seq = 0;
		final long startNanos = System.nanoTime();
		final Random rnd = new Random(7);
		while (true) {
			final double dt = 0.5; // 秒
			final double elapsedSec = (System.nanoTime() - startNanos) / 1e9;
			tick += 10;

			if (elapsedSec > 15 && !stopper.frozen) {
				stopper.frozen = true;
			}
			if (elapsedSec > 40 && !vanisher.hidden) {
				vanisher.hidden = true;
			}
			if (elapsedSec > 220 && vanisher.hidden) { // 戻ってきた
				vanisher.hidden = false;
			}

			final List<TrainSample> samples = new ArrayList<>();
			for (final Sim s : sims) {
				step(s, dt, rnd);
				if (s.hidden) continue;
				samples.add(sample(s, tick));
			}
			{ // 開発用: ゴースト疑い(停止固着)の車両を1本置く
				final Sim base = sims.get(0);
				final TrainSample g = sample(base, tick);
				final double[] gp = base.line.pointAt(base.line.total * 0.42);
				g.id = 99999; g.x = gp[0]; g.z = gp[1]; g.speedMps = 0; g.atStop = false; g.dwelling = false; g.doorOpen = false;
				g.routeId = 0; g.destStationId = 0; g.suspect = TrainSample.SUSPECT_FROZEN; g.railProgress = base.line.total * 0.42;
				samples.add(g);
			}
			assigner.assign(samples);
			final AnomalyDetector.Result res = detector.update(tick, samples);

			final Snapshot snap = new Snapshot();
			snap.seq = ++seq;
			snap.gameTick = tick;
			snap.dayTime = (int) (tick % 24000);
			snap.wallMillis = System.currentTimeMillis();
			snap.trains.addAll(res.views);
			snap.incidents.addAll(res.incidents);
			for (final byte[] part : SnapshotCodec.encode(snap, cfg.maxLevel, cfg.maxSnapshotBytes)) {
				store.acceptSnapshotPart(SnapshotCodec.decode(part));
			}
			Thread.sleep(500);
		}
	}

	static Line line(long id, String name, int color, boolean loop, String number, St... sts) {
		final Line l = new Line();
		l.id = id; l.name = name; l.color = color; l.loop = loop; l.number = number;
		l.sts.addAll(Arrays.asList(sts));
		l.init();
		return l;
	}

	static long[] appendFirst(long[] a) {
		final long[] out = Arrays.copyOf(a, a.length + 1);
		out[a.length] = a[0];
		return out;
	}

	static void addDepot(Topology t, long id, String name, int color, int x, int z, long[] routes) {
		final Topology.Depot d = new Topology.Depot();
		d.id = id; d.name = name; d.color = color; d.x = x; d.z = z; d.dimension = "minecraft:overworld"; d.routeIds = routes;
		t.depots.add(d);
	}

	static void addSiding(Topology t, long id, String name, long depot, int x, int z, String type, int max) {
		final Topology.Siding s = new Topology.Siding();
		s.id = id; s.name = name; s.depotId = depot; s.x = x; s.z = z; s.trainType = type; s.maxTrains = max; s.unlimited = false;
		t.sidings.add(s);
	}

	static OpsRegistry.DepotRecord depotRecord(long depotId, Map<Long, List<String>> sidings) {
		final OpsRegistry.DepotRecord d = new OpsRegistry.DepotRecord();
		d.depotId = depotId;
		d.dimension = "minecraft:overworld";
		new TreeMap<>(sidings).forEach((sid, ids) -> {
			final List<OpsRegistry.Formation> list = new ArrayList<>();
			for (final String id : ids) {
				final OpsRegistry.Formation f = new OpsRegistry.Formation();
				f.id = id;
				f.series = id.startsWith("1") ? "E233系" : "E235系";
				f.cars = id.startsWith("1") ? 8 : 6;
				list.add(f);
			}
			d.sidings.put(sid, list);
		});
		return d;
	}

	static void step(Sim s, double dt, Random rnd) {
		final Line l = s.line;
		if (s.frozen) {
			s.speed = 0;
			s.atStop = false;
			return;
		}
		if (s.dwellLeftTicks > 0) {
			s.dwellLeftTicks -= (int) (dt * 20);
			s.speed = 0;
			s.atStop = true;
			if (s.dwellLeftTicks <= 0) {
				s.dwellLeftTicks = 0;
				s.atStop = false;
				s.pos += 1.0; // 駅を離れる
			}
			return;
		}
		final double[] dist = new double[1];
		final int next = l.nextIndex(s.pos, dist);
		final double a = 1.0;
		final double vmax = 24;
		final double target = Math.min(vmax, Math.sqrt(2 * a * Math.max(0, dist[0] - 2)) + 1.5);
		s.speed += Math.signum(target - s.speed) * Math.min(Math.abs(target - s.speed), a * dt * 2);
		s.pos += s.speed * dt;
		if (s.pos >= l.total) {
			s.pos -= l.total;
		}
		if (!l.loop && s.pos >= l.cum[l.sts.size() - 1] - 0.5) {
			s.pos = 0;
			s.dwellLeftTicks = 0;
		}
		final double[] d2 = new double[1];
		final int n2 = l.nextIndex(s.pos, d2);
		if (d2[0] < 3 && s.speed < 3) {
			s.pos = l.cum[n2] + (n2 == 0 && l.loop ? l.total : 0);
			if (s.pos >= l.total && l.loop) s.pos -= l.total;
			s.speed = 0;
			s.atStop = true;
			s.curIdx = n2;
			s.dwellLeftTicks = 20 * (12 + rnd.nextInt(8));
		}
	}

	static TrainSample sample(Sim s, long tick) {
		final Line l = s.line;
		final TrainSample t = new TrainSample();
		t.id = s.id; t.sidingId = s.sidingId; t.depotId = s.depotId; t.routeId = l.id;
		t.dimension = "minecraft:overworld";
		t.trainType = "e233";
		t.cars = s.sidingId == 7201 || s.sidingId == 7202 ? 6 : 8;
		t.spacing = 20;
		final double[] p = l.pointAt(s.pos);
		t.x = p[0]; t.y = 70; t.z = p[1]; t.yaw = (float) p[2];
		t.speedMps = (float) s.speed;
		t.onRoute = true;
		t.passengers = (int) (t.cars * 18 + (s.id % 17) * 3);
		t.railProgress = s.pos;
		t.pathLength = l.total;
		t.destStationId = l.sts.get(l.loop ? 0 : l.sts.size() - 1).id();
		t.atStop = s.atStop;
		t.dwelling = s.atStop && s.dwellLeftTicks > 0;
		t.doorOpen = t.dwelling && s.dwellLeftTicks < 20 * 12 && s.dwellLeftTicks > 20 * 3;
		t.dwellRemainTicks = s.atStop ? s.dwellLeftTicks : -1;

		final double[] d = new double[1];
		final int next = l.nextIndex(s.pos, d);
		if (s.atStop) {
			final int cur = Math.max(0, Math.min(l.sts.size() - 1, indexNear(l, s.pos)));
			t.curStationId = l.sts.get(cur).id();
			t.nextStationId = l.sts.get((cur + 1) % l.sts.size()).id();
			t.prevStationId = l.sts.get((cur - 1 + l.sts.size()) % l.sts.size()).id();
		} else {
			t.nextStationId = l.sts.get(next).id();
			t.prevStationId = l.sts.get((next - 1 + l.sts.size()) % l.sts.size()).id();
		}

		t.idealTicks = 0;
		t.elapsedTicks = s.delaySec * 20.0;
		t.idealNextArrivalTicks = Double.NaN;

		t.carX = new float[t.cars]; t.carZ = new float[t.cars]; t.carYaw = new float[t.cars];
		for (int i = 0; i < t.cars; i++) {
			final double[] q = l.pointAt(s.pos - i * 20 - 10);
			t.carX[i] = (float) q[0]; t.carZ[i] = (float) q[1]; t.carYaw[i] = (float) q[2];
		}
		return t;
	}

	static int indexNear(Line l, double pos) {
		int best = 0;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < l.cum.length; i++) {
			final double d = Math.min(Math.abs(l.cum[i] - pos), l.loop ? Math.abs(l.total - Math.abs(l.cum[i] - pos)) : Double.MAX_VALUE);
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}
}
