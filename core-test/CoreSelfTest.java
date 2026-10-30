import jp.mtrops.core.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Minecraft/MTR なしで core を検証する自己テスト。  実行: core-test/run.sh */
public class CoreSelfTest {

	static int passed = 0, failed = 0;

	static void check(boolean cond, String name) {
		if (cond) {
			passed++;
			System.out.println("  ok   " + name);
		} else {
			failed++;
			System.out.println("  FAIL " + name);
		}
	}

	static TrainSample train(long id, long siding, long depot, long route, double x, double z, float speed, double progress) {
		final TrainSample s = new TrainSample();
		s.id = id; s.sidingId = siding; s.depotId = depot; s.routeId = route;
		s.x = x; s.y = 64; s.z = z; s.speedMps = speed;
		s.onRoute = true; s.cars = 8; s.spacing = 20;
		s.railProgress = progress; s.pathLength = 2000;
		s.nextStationId = 11; s.destStationId = 12; s.curStationId = 0; s.prevStationId = 10;
		s.idealTicks = 0; s.elapsedTicks = 0;
		s.trainType = "train_24_8";
		return s;
	}

	static OpsRegistry.DepotRecord depot(long id, long siding, String... formationIds) {
		final OpsRegistry.DepotRecord d = new OpsRegistry.DepotRecord();
		d.depotId = id; d.dimension = "minecraft:overworld";
		final List<OpsRegistry.Formation> list = new ArrayList<>();
		for (final String f : formationIds) {
			final OpsRegistry.Formation fo = new OpsRegistry.Formation();
			fo.id = f; fo.series = "1000系";
			list.add(fo);
		}
		d.sidings.put(siding, list);
		return d;
	}

	public static void main(String[] args) throws Exception {
		registryTests();
		assignerTests();
		detectorTests();
		codecTests();
		rangeTests();
		budgetTests();
		httpTests();
		geometryTests();
		entryMatcherTests();
		etaTests();
		railTimeTests();
		System.out.println("\n" + passed + " passed, " + failed + " failed");
		System.exit(failed == 0 ? 0 : 1);
	}

	static void registryTests() throws Exception {
		System.out.println("[registry]");
		final OpsRegistry reg = new OpsRegistry();
		check(reg.applyDepotUpdate(depot(1, 100, "1001F", "1002F"), 0, null) == OpsRegistry.Result.OK, "新規車庫を登録");
		check(reg.depots.get(1L).rev == 1, "rev が 1 になる");
		check(reg.applyDepotUpdate(depot(1, 100, "1001F", "1002F", "1003F"), 0, null) == OpsRegistry.Result.CONFLICT, "古いbaseRevは CONFLICT");
		check(reg.applyDepotUpdate(depot(1, 100, "1001F", "1002F", "1003F"), 1, null) == OpsRegistry.Result.OK, "正しいbaseRevで更新");
		check(reg.applyDepotUpdate(depot(2, 200, "1002F"), 0, null) == OpsRegistry.Result.DUPLICATE, "同じ車種で他車庫と編成ID重複は DUPLICATE");
		reg.sidingTypes = new HashMap<>(Map.of(100L, "mtr_custom_train_a", 200L, "mtr_custom_train_b"));
		check(reg.applyDepotUpdate(depot(2, 200, "1002F"), 0, null) == OpsRegistry.Result.OK, "車種IDが違えば同じ車番を許容");
		check(reg.applyDepotUpdate(depot(2, 200, "1002F"), 1, null) == OpsRegistry.Result.OK, "再保存も可");
		reg.removeDepot(2);
		reg.sidingTypes = new HashMap<>();
		check(reg.applyDepotUpdate(depot(2, 200, "2001F"), 0, null) == OpsRegistry.Result.OK, "別車庫は独立して更新可");
		check(reg.depots.get(1L).formationCount() == 3, "別車庫の更新が車庫1を上書きしない");
		check(reg.applyDepotUpdate(depot(3, 300, "A", "A"), 0, null) == OpsRegistry.Result.DUPLICATE, "車庫内重複を拒否");
		check(reg.applyDepotUpdate(depot(3, 300, "  "), 0, null) == OpsRegistry.Result.INVALID, "空IDを拒否");
		final Set<Long> valid = new HashSet<>(List.of(100L));
		final OpsRegistry.DepotRecord withBad = depot(4, 999, "X1");
		check(reg.applyDepotUpdate(withBad, 0, valid) == OpsRegistry.Result.OK && reg.depots.get(4L).sidings.isEmpty(), "存在しない車庫線は除去");

		final byte[] bin = RegistryCodec.encodeRegistry(reg, true);
		final OpsRegistry back = RegistryCodec.decodeRegistry(bin);
		check(back.depots.size() == reg.depots.size() && back.depots.get(1L).formationCount() == 3, "台帳のエンコード/デコード往復");

		final byte[] upd = RegistryCodec.encodeDepotUpdate(depot(1, 100, "1001F"), 2);
		final RegistryCodec.DepotUpdate du = RegistryCodec.decodeDepotUpdate(upd);
		check(du.baseRev == 2 && du.depot.depotId == 1, "1車庫分のC2S更新の往復");

		final OpsRegistry.DepotRecord big = new OpsRegistry.DepotRecord();
		big.depotId = 9;
		final List<OpsRegistry.Formation> bl = new ArrayList<>();
		for (int i = 0; i < 400; i++) {
			final OpsRegistry.Formation f = new OpsRegistry.Formation();
			f.id = "F" + i; f.series = "S";
			for (int c = 0; c < 10; c++) f.carNumbers.add("F" + i + "-" + (c + 1) + "-LONGLONGLONG");
			bl.add(f);
		}
		big.sidings.put(1L, bl);
		check(RegistryCodec.encodeDepotUpdate(big, 0) == null, "32KB上限を超えるC2Sは null(送信側で拒否)");
	}

	static void assignerTests() {
		System.out.println("[assigner]");
		final OpsRegistry reg = new OpsRegistry();
		reg.applyDepotUpdate(depot(1, 100, "1001F", "1002F"), 0, null);
		final FormationAssigner as = new FormationAssigner(reg);

		final List<TrainSample> t1 = new ArrayList<>(List.of(train(500, 100, 1, 7, 0, 0, 10, 100)));
		check(as.assign(t1), "新しい列車に割り当て(変更あり)");
		check("1001F".equals(t1.get(0).formationId), "最初の列車は 1001F");

		final List<TrainSample> t2 = new ArrayList<>(List.of(train(500, 100, 1, 7, 0, 0, 10, 100), train(400, 100, 1, 7, 0, 0, 10, 50)));
		as.assign(t2);
		final Map<Long, String> got = new HashMap<>();
		t2.forEach(s -> got.put(s.id, s.formationId));
		check("1001F".equals(got.get(500L)) && "1002F".equals(got.get(400L)), "既存列車の番号は保たれ、新列車に空き番号");

		final List<TrainSample> t3 = new ArrayList<>(List.of(train(500, 100, 1, 7, 0, 0, 10, 100), train(400, 100, 1, 7, 0, 0, 10, 50), train(300, 100, 1, 7, 0, 0, 10, 10)));
		as.assign(t3);
		check(t3.get(2).formationId == null, "登録数を超える列車は番号なし");

		final List<TrainSample> t4 = new ArrayList<>(List.of(train(400, 100, 1, 7, 0, 0, 10, 50)));
		as.assign(t4);
		check("1002F".equals(t4.get(0).formationId) && !reg.assignments.containsKey(500L), "消えた列車の割り当てを解放");

		final OpsRegistry.DepotRecord edit = depot(1, 100, "1002F");
		reg.applyDepotUpdate(edit, 1, null);
		final List<TrainSample> t5 = new ArrayList<>(List.of(train(400, 100, 1, 7, 0, 0, 10, 50)));
		as.assign(t5);
		check("1002F".equals(t5.get(0).formationId), "編成リスト編集後も残った編成は維持");
	}

	static void detectorTests() {
		System.out.println("[detector]");
		final OpsConfig cfg = new OpsConfig();
		final AnomalyDetector det = new AnomalyDetector(cfg);
		long tick = 0;

		// 正常走行
		TrainSample a = train(1, 100, 1, 7, 0, 0, 20, 100);
		AnomalyDetector.Result r = det.update(tick, new ArrayList<>(List.of(a)));
		check(r.views.get(0).status == TrainStatus.NORMAL, "走行中は NORMAL");

		// 遅延
		a = train(1, 100, 1, 7, 10, 0, 20, 200);
		a.idealTicks = 1000; a.elapsedTicks = 1000 + 20 * 70;
		r = det.update(tick += 10, new ArrayList<>(List.of(a)));
		check(r.views.get(0).status == TrainStatus.DELAY && r.views.get(0).delaySec == 70, "70秒遅れ → DELAY");
		a = train(1, 100, 1, 7, 20, 0, 20, 300);
		a.idealTicks = 1000; a.elapsedTicks = 1000 + 20 * 400;
		r = det.update(tick += 10, new ArrayList<>(List.of(a)));
		check(r.views.get(0).status == TrainStatus.BIG_DELAY, "400秒遅れ → BIG_DELAY");
		check(r.activeCount == 0, "遅延だけではインシデントにならない(異常と分離)");

		// 停止 → 列車停止 → 運行異常
		final AnomalyDetector det2 = new AnomalyDetector(cfg);
		tick = 0;
		det2.update(tick, new ArrayList<>(List.of(train(2, 100, 1, 7, 5, 5, 0, 500))));
		r = det2.update(tick += 20 * 50, new ArrayList<>(List.of(train(2, 100, 1, 7, 5, 5, 0, 500))));
		check(r.views.get(0).status == TrainStatus.STOPPED, "50秒停止 → STOPPED");
		check(r.activeCount == 1, "停止インシデントが1件");
		r = det2.update(tick += 20 * 200, new ArrayList<>(List.of(train(2, 100, 1, 7, 5, 5, 0, 500))));
		check(r.views.get(0).status == TrainStatus.STOPPED, "250秒停止でも STOPPED のまま(運行異常ステータスは無い)");
		TrainSample moving = train(2, 100, 1, 7, 50, 5, 15, 520);
		r = det2.update(tick += 20, new ArrayList<>(List.of(moving)));
		check(r.views.get(0).status == TrainStatus.NORMAL && r.activeCount == 0, "走り出すとインシデント解消");
		check(r.incidents.stream().anyMatch(i -> !i.isActive()), "解消済みインシデントが履歴に残る");

		// 停車中(規定時間内)は停止扱いにしない
		final AnomalyDetector det3 = new AnomalyDetector(cfg);
		tick = 0;
		for (int i = 0; i < 20; i++) {
			final TrainSample d = train(3, 100, 1, 7, 0, 0, 0, 800);
			d.atStop = true; d.dwelling = true;
			r = det3.update(tick += 20 * 10, new ArrayList<>(List.of(d)));
		}
		check(r.views.get(0).status == TrainStatus.NORMAL, "規定停車時間内の停車は NORMAL");

		// 位置情報が更新されない
		final AnomalyDetector det4 = new AnomalyDetector(cfg);
		tick = 0;
		det4.update(tick, new ArrayList<>(List.of(train(4, 100, 1, 7, 9, 9, 20, 100))));
		r = det4.update(tick += 20 * 25, new ArrayList<>(List.of(train(4, 100, 1, 7, 9, 9, 20, 100))));
		check(r.views.get(0).status == TrainStatus.NORMAL && r.activeCount == 1, "速度あり・位置不変25秒 → インシデントのみ(状態は変えない)");

		// 列車が消えても「消失」状態・通知は出さない(MTR自身が終点・設定変更で列車を消すため)
		final AnomalyDetector det5 = new AnomalyDetector(cfg);
		tick = 0;
		det5.update(tick, new ArrayList<>(List.of(train(6, 100, 1, 7, 0, 0, 20, 900), train(5, 100, 1, 7, 100, 0, 20, 500))));
		for (int i = 0; i < 6; i++) {
			r = det5.update(tick += 20 * 10, new ArrayList<>(List.of(train(6, 100, 1, 7, i + 1, 0, 20, 910 + i))));
		}
		check(r.activeCount == 0 && r.views.size() == 1, "列車が消えても異常通知やゴースト表示は出ない");

		// 手動運転は停止判定の対象外
		final AnomalyDetector det7 = new AnomalyDetector(cfg);
		tick = 0;
		for (int i = 0; i < 30; i++) {
			final TrainSample m = train(8, 100, 1, 7, 0, 0, 0, 100);
			m.manual = true;
			r = det7.update(tick += 20 * 10, new ArrayList<>(List.of(m)));
		}
		check(r.views.get(0).status == TrainStatus.NORMAL, "手動運転の停止は異常にしない");

		// 路線不明
		final AnomalyDetector det8 = new AnomalyDetector(cfg);
		tick = 0;
		det8.update(tick, new ArrayList<>(List.of(train(9, 100, 1, 0, 0, 0, 20, 100))));
		final TrainSample mm = train(9, 100, 1, 0, 100, 0, 20, 200);
		r = det8.update(tick += 20 * 12, new ArrayList<>(List.of(mm)));
		check(r.incidents.stream().anyMatch(i -> i.type == Incident.Type.MISMATCH), "走行中に路線不明 → 不一致インシデント");
	}

	static Snapshot makeSnapshot(int trains) {
		final Snapshot s = new Snapshot();
		s.seq = 5; s.gameTick = 123456; s.dayTime = 3000; s.wallMillis = System.currentTimeMillis();
		for (int i = 0; i < trains; i++) {
			final TrainSample t = train(1000 + i, 100, 1, 7, i * 3.5, i * -2.25, 12.5F, 100 + i);
			t.formationId = (i % 3 == 0) ? null : "F" + i;
			t.series = "1000系";
			t.yaw = 123.4F;
			t.carX = new float[8]; t.carZ = new float[8]; t.carYaw = new float[8];
			for (int c = 0; c < 8; c++) { t.carX[c] = (float) t.x - c * 20; t.carZ[c] = (float) t.z; t.carYaw[c] = 90; }
			s.trains.add(TrainView.of(t, i % 50 == 0 ? TrainStatus.STOPPED : TrainStatus.NORMAL, i % 7));
		}
		final Incident inc = new Incident();
		inc.id = 1; inc.type = Incident.Type.STOPPED; inc.severity = Incident.Severity.WARN;
		inc.trainId = 1000; inc.formation = "F0"; inc.message = "停止"; inc.sinceTick = 100; inc.lastTick = 200;
		inc.resolvedTick = -1;
		s.incidents.add(inc);
		return s;
	}

	static void codecTests() throws Exception {
		System.out.println("[codec]");
		final Snapshot snap = makeSnapshot(5);
		final List<byte[]> parts = SnapshotCodec.encode(snap, 4, 200_000);
		check(parts.size() == 1 && snap.level == 4, "余裕があれば最高詳細(L4)で1パケット");
		final SnapshotCodec.Part p = SnapshotCodec.decode(parts.get(0));
		final TrainView v = p.snapshot.trains.get(1);
		check(Math.abs(v.s.x - 3.5) < 0.01 && v.s.carX.length == 8 && v.s.cars == 8, "L4の位置・各車両位置が往復する");
		check("F1".equals(v.s.formationId) && p.snapshot.trains.get(0).s.formationId == null, "編成番号の有無が往復する");
		check(p.snapshot.incidents.size() == 1 && p.snapshot.incidents.get(0).type == Incident.Type.STOPPED, "インシデントが往復する");

		final Topology t = new Topology();
		t.rev = 3;
		final Topology.Station st = new Topology.Station(); st.id = 11; st.name = "新宿"; st.x = 10; st.z = 20; st.color = 0xFF0000; t.stations.add(st);
		final Topology.Route ro = new Topology.Route(); ro.id = 7; ro.name = "山手線"; ro.color = 0x00AA00; ro.stationIds = new long[]{11, 12}; ro.destination = "品川"; t.routes.add(ro);
		final Topology.Depot de = new Topology.Depot(); de.id = 1; de.name = "A車両基地"; de.routeIds = new long[]{7}; t.depots.add(de);
		final Topology.Siding si = new Topology.Siding(); si.id = 100; si.name = "1番線"; si.depotId = 1; t.sidings.add(si);
		final Topology back = TopologyCodec.decode(TopologyCodec.encode(t, 1_000_000));
		check(back.stations.get(0).name.equals("新宿") && back.routes.get(0).stationIds.length == 2 && back.sidings.get(0).depotId == 1, "トポロジーの往復");
		final Topology slim = TopologyCodec.decode(TopologyCodec.encode(t, 10));
		check(slim.routes.get(0).stationIds.length == 0 && slim.routes.get(0).name.equals("山手線"), "上限超過時は停車駅の並びを落として基本情報は残す");
	}

	static void rangeTests() throws Exception {
		System.out.println("[range / auto numbering / absent trains]");
		final OpsRegistry reg = new OpsRegistry();
		final OpsRegistry.DepotRecord d = depot(1, 100);
		d.sidings.get(100L).clear();
		final OpsRegistry.NumberRange r = new OpsRegistry.NumberRange();
		r.suffix = "F"; r.start = 1001; r.end = 1003;
		d.ranges.put(100L, r);
		check(reg.applyDepotUpdate(d, 0, Set.of(100L)) == OpsRegistry.Result.OK, "車番範囲つきの車庫を保存");
		check(r.parse("1002F") == 1002 && r.parse("1004F") < 0 && r.parse("1002") < 0 && r.parse("X1002F") < 0, "範囲の判定(範囲外・形式違いを弾く)");

		final FormationAssigner as = new FormationAssigner(reg);
		final List<TrainSample> ts = new ArrayList<>(List.of(train(11, 100, 1, 7, 0, 0, 10, 100), train(12, 100, 1, 7, 0, 0, 10, 300)));
		as.assign(ts);
		check("1001F".equals(ts.get(0).formationId) && "1002F".equals(ts.get(1).formationId), "出庫した列車へ範囲の若い番号を自動採番");
		check(as.takeRegistryChanged() && !as.takeRegistryChanged(), "自動採番で台帳変更フラグが立つ(1回だけ)");
		check(reg.depots.get(1L).rev == 3, "自動登録ごとに車庫revが進む");
		final List<TrainSample> ts2 = new ArrayList<>(List.of(train(11, 100, 1, 7, 0, 0, 10, 110), train(12, 100, 1, 7, 0, 0, 10, 310), train(13, 100, 1, 7, 0, 0, 10, 50), train(14, 100, 1, 7, 0, 0, 10, 20)));
		as.assign(ts2);
		check("1001F".equals(ts2.get(0).formationId) && "1002F".equals(ts2.get(1).formationId), "再サンプルでも同じ列車は同じ番号");
		final long assigned = ts2.stream().filter(t -> t.formationId != null).count();
		check(assigned == 3 && new HashSet<>(ts2.stream().map(t -> t.formationId).filter(Objects::nonNull).toList()).size() == 3, "範囲が満杯なら残りは未割当・重複なし");

		// 範囲外の編成は保存できない
		final OpsRegistry.DepotRecord bad = reg.depots.get(1L).copy();
		bad.sidings.get(100L).get(0).id = "9999F";
		check(reg.applyDepotUpdate(bad, reg.depots.get(1L).rev, Set.of(100L)) == OpsRegistry.Result.INVALID, "範囲外の編成番号は INVALID");
		// 他の車庫との重複
		final OpsRegistry.DepotRecord other = depot(2, 200, "1001F");
		check(reg.applyDepotUpdate(other, 0, Set.of(200L)) == OpsRegistry.Result.DUPLICATE, "他車庫と重複する番号は DUPLICATE");

		// コーデックv2 往復
		final OpsRegistry back = RegistryCodec.decodeRegistry(RegistryCodec.encodeRegistry(reg, true));
		check(back.depots.get(1L).ranges.get(100L).describe().equals("1001F 〜 1003F"), "車番範囲が保存形式を往復する");
		final byte[] up = RegistryCodec.encodeDepotUpdate(reg.depots.get(1L), 3);
		check(RegistryCodec.decodeDepotUpdate(up).depot.ranges.size() == 1, "車庫更新ペイロードにも範囲が入る");

		// 旧版(v1)で保存されたワールドデータの読み込み(再起動・MOD更新でデータが消えない)
		final ByteArrayOutputStream bos = new ByteArrayOutputStream();
		final DataOutputStream o = new DataOutputStream(bos);
		o.writeInt(0x4D4F5031); o.writeByte(1); o.writeInt(1);
		o.writeLong(77); o.writeUTF("minecraft:overworld"); o.writeInt(4); o.writeInt(1);
		o.writeLong(900); o.writeShort(1); o.writeUTF("1001F"); o.writeUTF("E233"); o.writeByte(8); o.writeByte(0);
		o.writeInt(1); o.writeLong(555); o.writeLong(900); o.writeUTF("1001F");
		final OpsRegistry old = RegistryCodec.decodeRegistry(bos.toByteArray());
		check(old.depots.get(77L).rev == 4 && old.depots.get(77L).sidings.get(900L).get(0).id.equals("1001F") && old.assignments.get(555L).formationId.equals("1001F"), "v1形式の保存データを読み込める(編成・割り当て・rev保持)");
		check(RegistryCodec.decodeRegistry(RegistryCodec.encodeRegistry(old, true)).assignments.size() == 1, "読み込んだv1データをv2で再保存しても割り当てが残る");
		// 自動採番の忌み番号スキップ
		final OpsRegistry sk = new OpsRegistry();
		sk.skipEndings = new HashSet<>(Set.of("116", "000"));
		final OpsRegistry.DepotRecord sd = depot(5, 500); sd.sidings.get(500L).clear();
		final OpsRegistry.NumberRange sr = new OpsRegistry.NumberRange(); sr.start = 1115; sr.end = 1120; sd.ranges.put(500L, sr);
		sk.applyDepotUpdate(sd, 0, null);
		final List<String> got = new ArrayList<>();
		for (int i = 0; i < 4; i++) got.add(sk.autoRegister(5, 500, 4).id);
		check(got.equals(List.of("1115", "1117", "1118", "1119")), "忌み番号(末尾116)を飛ばして採番: " + got);

		// 車庫ごとの除外番号
		final OpsRegistry exr = new OpsRegistry();
		final OpsRegistry.DepotRecord ed = depot(6, 600); ed.sidings.get(600L).clear();
		final OpsRegistry.NumberRange er = new OpsRegistry.NumberRange(); er.start = 1; er.end = 20; ed.ranges.put(600L, er);
		ed.exclude = "4, 9、13-15";
		check(exr.applyDepotUpdate(ed, 0, null) == OpsRegistry.Result.OK, "除外リスト付きの車庫を保存");
		final List<String> eg = new ArrayList<>();
		for (int i = 0; i < 12; i++) eg.add(exr.autoRegister(6, 600, 4).id);
		check(eg.equals(List.of("1", "2", "3", "5", "6", "7", "8", "10", "11", "12", "16", "17")), "除外番号(4,9,13-15)を飛ばして採番: " + eg);
		check(RegistryCodec.decodeRegistry(RegistryCodec.encodeRegistry(exr, true)).depots.get(6L).exclude.equals("4, 9、13-15"), "除外リストが保存形式で往復する");
		ed.exclude = "abc";
		check(exr.applyDepotUpdate(ed, exr.depots.get(6L).rev, null) == OpsRegistry.Result.INVALID, "不正な除外書式は INVALID");

		// 各車両の番号(文字つきの編成番号、連番、桁置換)
		final OpsRegistry.NumberRange cr = new OpsRegistry.NumberRange(); cr.start = 1001; cr.end = 1100; cr.prefix = "TX-";
		cr.carScheme = 1;
		check(cr.carNumber("TX-1001", 1, 8).equals("TX-1001") && cr.carNumber("TX-1001", 8, 8).equals("TX-1008") && cr.carNumber("TX-1002", 1, 8).equals("TX-1009") && cr.carNumber("TX-1002", 8, 8).equals("TX-1016"), "連番: 1001〜1008、1009〜1016(文字つき)");
		cr.carScheme = 2; cr.digitPos = 3;
		final StringBuilder dg = new StringBuilder();
		for (int i = 1; i <= 8; i++) dg.append(cr.carNumber("TX-1001", i, 8)).append(' ');
		check(dg.toString().trim().equals("TX-1101 TX-1201 TX-1301 TX-1401 TX-1501 TX-1601 TX-1701 TX-1001"), "桁置換(百の位): " + dg);
		cr.prefix = ""; cr.suffix = "";
		check(cr.carNumber("モハE233-1000", 2, 10).equals("モハE233-1200"), "日本語の前置き(モハE233-)が反映される");
		check(cr.carNumber("abc", 1, 8) == null, "数字が無ければ従来表記に戻る");
		final OpsRegistry.Formation cf = new OpsRegistry.Formation(); cf.id = "1001"; cf.cars = 4;
		cr.carScheme = 0;
		check(OpsRegistry.carLabels(cf, 0, cr).get(0).equals("1001-1"), "方式なしは従来の表記");
		check(OpsRegistry.formationTitle("TX-1001", null).equals("TX-1001"), "編成表示名: 編成番号そのまま");
		cr.carScheme = 1; cr.digitPos = 3; cr.random = true;
		final OpsRegistry cReg = new OpsRegistry();
		final OpsRegistry.DepotRecord cDep = depot(8, 800); cDep.sidings.get(800L).clear(); cDep.ranges.put(800L, cr);
		cReg.applyDepotUpdate(cDep, 0, null);
		{
			final OpsRegistry.Formation tf = new OpsRegistry.Formation(); tf.id = "TX-1002"; tf.cars = 8;
			check(cReg.formationTitleFor(800L, tf, 8).equals("TX-1009"), "連番: 編成表示名は1号車の車番: " + cReg.formationTitleFor(800L, tf, 8));
		}
		{
			// 連番で両数が違う編成: 8両の 1001〜1008 と、4両の候補(1002→1005〜1008)が被らないこと
			final OpsRegistry.NumberRange nr = new OpsRegistry.NumberRange(); nr.start = 1001; nr.end = 1100; nr.carScheme = 1;
			final OpsRegistry nReg = new OpsRegistry();
			final OpsRegistry.DepotRecord nDep = depot(9, 900, "1001"); nDep.ranges.put(900L, nr);
			nDep.sidings.get(900L).get(0).cars = 8;
			nReg.applyDepotUpdate(nDep, 0, null);
			final OpsRegistry.Formation nf = nReg.autoRegister(9, 900, 4);
			check(nf != null && nf.id.equals("1003"), "連番: 他編成の車番と被る候補を飛ばす: " + (nf == null ? null : nf.id));
			nr.random = true;
			boolean clash = false;
			for (int k = 0; k < 40; k++) {
				final OpsRegistry.Formation rf = nReg.pickRandom(9, 900, 4, new java.util.HashSet<>());
				if (rf == null) break;
				final java.util.List<String> labs = OpsRegistry.carLabels(rf, 4, nr);
				for (final OpsRegistry.Formation oth : nReg.formationsOf(9, 900)) {
					if (oth != rf) for (final String l : OpsRegistry.carLabels(oth, oth.cars, nr)) if (labs.contains(l)) clash = true;
				}
				final java.util.Set<String> inUse = new java.util.HashSet<>(); inUse.add(rf.id);
			}
			check(!clash, "連番+ランダム: 号車番号が他編成と被らない");
		}
		final OpsRegistry cBack = RegistryCodec.decodeRegistry(RegistryCodec.encodeRegistry(cReg, true));
		check(cBack.depots.get(8L).ranges.get(800L).carScheme == 1 && cBack.depots.get(8L).ranges.get(800L).random, "各車両の方式が保存形式で往復する");

		// ゴースト疑い: 5秒以上続いたら通知、解消で閉じる、通信で往復する
		final AnomalyDetector gd = new AnomalyDetector(new OpsConfig());
		final TrainSample gs = train(70, 100, 1, 7, 5, 5, 0, 500); gs.suspect = TrainSample.SUSPECT_FROZEN;
		AnomalyDetector.Result gr = gd.update(0, new ArrayList<>(List.of(gs)));
		check(gr.incidents.stream().noneMatch(i -> i.type == Incident.Type.GHOST), "ゴースト疑いは即時には通知しない");
		final TrainSample gs2 = train(70, 100, 1, 7, 5, 5, 0, 500); gs2.suspect = TrainSample.SUSPECT_FROZEN;
		gr = gd.update(20 * 6, new ArrayList<>(List.of(gs2)));
		check(gr.incidents.stream().anyMatch(i -> i.type == Incident.Type.GHOST && i.isActive()), "6秒続くとゴースト疑いを通知");
		gr = gd.update(20 * 8, new ArrayList<>(List.of(train(70, 100, 1, 7, 5, 5, 0, 500))));
		check(gr.incidents.stream().noneMatch(i -> i.type == Incident.Type.GHOST && i.isActive()), "疑いが消えると通知も閉じる");
		final Snapshot gsn = new Snapshot();
		final TrainSample gs3 = train(71, 100, 1, 7, 5, 5, 0, 500); gs3.suspect = TrainSample.SUSPECT_NOPATH | TrainSample.SUSPECT_FROZEN;
		gsn.trains.add(TrainView.of(gs3, TrainStatus.NORMAL, 0)); gsn.level = 4;
		final SnapshotCodec.Part gp = SnapshotCodec.decode(SnapshotCodec.encode(gsn, 4, 100000).get(0));
		check(gp.snapshot.trains.get(0).s.suspect == 3, "ゴースト疑いのフラグが通信で往復する");

		// ランダム採番(出庫時に範囲内から重複なし)
		final OpsRegistry rr = new OpsRegistry();
		final OpsRegistry.DepotRecord rd = depot(7, 700); rd.sidings.get(700L).clear();
		final OpsRegistry.NumberRange rrg = new OpsRegistry.NumberRange(); rrg.start = 101; rrg.end = 110; rrg.random = true; rd.ranges.put(700L, rrg);
		rd.exclude = "105";
		rr.applyDepotUpdate(rd, 0, null);
		check(RegistryCodec.decodeRegistry(RegistryCodec.encodeRegistry(rr, true)).depots.get(7L).ranges.get(700L).random, "ランダム設定が往復する");
		final FormationAssigner ra = new FormationAssigner(rr);
		final List<TrainSample> rs = new ArrayList<>();
		for (int i = 0; i < 9; i++) rs.add(train(900 + i, 700, 7, 7, 50, 5, 10, 100 + i));
		rs.forEach(t -> t.sidingId = 700);
		ra.assign(rs);
		final Set<String> rids = new HashSet<>();
		boolean inRange = true, inOrder = true; int prevN = 0;
		for (final TrainSample t : rs) { rids.add(t.formationId); final int n = Integer.parseInt(t.formationId); inRange &= n >= 101 && n <= 110 && n != 105; inOrder &= n > prevN; prevN = n; }
		check(rids.size() == 9 && inRange, "ランダム採番: 9本に範囲内(除外105を除く)で重複なし: " + rids);
		final TrainSample extra = train(990, 700, 7, 7, 50, 5, 10, 150); extra.sidingId = 700;
		final List<TrainSample> all = new ArrayList<>(rs); all.add(extra);
		ra.assign(all);
		check(extra.formationId == null, "範囲が満杯(9/9使用中)なら番号なし");

		// 消えた列車の判定状態は片付く(停止中に消えた列車の通知が残り続けない)
		final AnomalyDetector det = new AnomalyDetector(new OpsConfig());
		long tick = 0;
		det.update(tick, new ArrayList<>(List.of(train(41, 100, 1, 7, 0, 0, 0, 500))));
		AnomalyDetector.Result res = det.update(tick += 20 * 60, new ArrayList<>(List.of(train(41, 100, 1, 7, 0, 0, 0, 500))));
		check(res.activeCount == 1, "停止列車の通知が出る");
		res = det.update(tick += 20 * 30, new ArrayList<>());
		check(res.activeCount == 0, "その列車が消えると通知も閉じる");
		// サンプルに載らなくてもMTR側に実在する列車の状態は保持される
		final AnomalyDetector det2 = new AnomalyDetector(new OpsConfig());
		tick = 0;
		det2.update(tick, new ArrayList<>(List.of(train(51, 100, 1, 7, 0, 0, 0, 500))), new HashSet<>(Set.of(51L)));
		det2.update(tick += 20 * 60, new ArrayList<>(List.of(train(51, 100, 1, 7, 0, 0, 0, 500))), new HashSet<>(Set.of(51L)));
		res = det2.update(tick += 20 * 30, new ArrayList<>(), new HashSet<>(Set.of(51L)));
		check(res.activeCount == 1, "実在する列車は一時的にサンプルが欠けても通知を保持");
	}

	static void budgetTests() throws Exception {
		System.out.println("[budget]");
		final Snapshot big = makeSnapshot(3000);
		final int s4 = SnapshotCodec.sizeAtLevel(big, 4), s3 = SnapshotCodec.sizeAtLevel(big, 3), s2 = SnapshotCodec.sizeAtLevel(big, 2),
				s1 = SnapshotCodec.sizeAtLevel(big, 1), s0 = SnapshotCodec.sizeAtLevel(big, 0);
		System.out.printf("       3000列車のサイズ: L4=%d L3=%d L2=%d L1=%d L0=%d bytes%n", s4, s3, s2, s1, s0);
		check(s4 > s3 && s3 > s2 && s2 > s1 && s1 > s0, "詳細レベルが下がるほどサイズが小さくなる");

		for (final int budget : new int[]{s4 + 10, s3 + 10, s2 + 10, s1 + 10, s0 + 10}) {
			final Snapshot sn = makeSnapshot(3000);
			final List<byte[]> parts = SnapshotCodec.encode(sn, 4, budget);
			check(parts.size() == 1 && parts.get(0).length <= budget, "予算 " + budget + " → L" + sn.level + " に自動で下げて収める");
		}

		final Snapshot huge = makeSnapshot(3000);
		final int budget = s0 / 4;
		final List<byte[]> parts = SnapshotCodec.encode(huge, 4, budget);
		check(parts.size() > 1 && parts.stream().allMatch(b -> b.length <= budget), "L0でも収まらなければ分割(" + parts.size() + "パート、各 ≤ 予算)");
		final ClientStore store = new ClientStore();
		Collections.shuffle(parts, new Random(1));
		for (final byte[] b : parts) store.acceptSnapshotPart(SnapshotCodec.decode(b));
		check(store.live() != null && store.live().trains.size() == 3000, "分割パートがクライアントで全列車に再結合される");
		final TrainView first = store.live().trains.get(0);
		check(first.status == TrainStatus.STOPPED, "分割時は異常な列車が先頭パートに入る");

		final Snapshot capped = makeSnapshot(10);
		SnapshotCodec.encode(capped, 1, 1_000_000);
		check(capped.level == 1, "maxLevel 設定で上限を絞れる");
	}

	static void httpTests() throws Exception {
		System.out.println("[http]");
		final ClientStore store = new ClientStore();
		final Topology t = new Topology();
		final Topology.Station st = new Topology.Station(); st.id = 11; st.name = "新宿"; t.stations.add(st);
		final Topology.Station st2 = new Topology.Station(); st2.id = 12; st2.name = "品川"; t.stations.add(st2);
		final Topology.Route ro = new Topology.Route(); ro.id = 7; ro.name = "山手線"; ro.color = 0x00AA00; ro.stationIds = new long[]{11, 12}; t.routes.add(ro);
		final Topology.Depot dp = new Topology.Depot(); dp.id = 1; dp.name = "A車両基地"; dp.routeIds = new long[]{7}; t.depots.add(dp);
		final Topology.Siding sd = new Topology.Siding(); sd.id = 100; sd.name = "1番線"; sd.depotId = 1; t.sidings.add(sd);
		store.setTopology(t);
		final OpsRegistry reg = new OpsRegistry();
		reg.applyDepotUpdate(depot(1, 100, "F1", "F2"), 0, null);
		store.setRegistry(reg);
		final Snapshot snap = makeSnapshot(4);
		final Part0 ignore = null;
		store.acceptSnapshotPart(SnapshotCodec.decode(SnapshotCodec.encode(snap, 4, 200000).get(0)));

		final ApiHandler api = new ApiHandler(store);
		final LocalHttpServer server = new LocalHttpServer(0, api, path -> path.equals("index.html") ? "<h1>hi</h1>".getBytes(StandardCharsets.UTF_8) : null, List.of());
		server.start();
		final int port = server.port();
		final HttpClient client = HttpClient.newHttpClient();

		HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status")).build(), HttpResponse.BodyHandlers.ofString());
		check(res.statusCode() == 200 && res.body().contains("\"connected\":true") && res.body().contains("\"clock\":\"09:00\""), "/api/status (ゲーム時刻 3000 → 09:00)");

		res = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/trains")).build(), HttpResponse.BodyHandlers.ofString());
		check(res.statusCode() == 200 && res.body().contains("\"routeName\":\"山手線\"") && res.body().contains("\"nextStation\":\"新宿\""), "/api/trains に路線名・次駅名が付く");

		res = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/trains?status=stopped")).build(), HttpResponse.BodyHandlers.ofString());
		check(res.body().split("\"id\":").length - 1 == 1, "/api/trains?status=stopped で絞り込み");

		res = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/trains/F1")).build(), HttpResponse.BodyHandlers.ofString());
		check(res.statusCode() == 200 && res.body().contains("\"carNumbers\":[\"F1-1\"") , "/api/trains/{編成} に車番が付く: " + (res.body().length() > 0));

		res = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/depots")).build(), HttpResponse.BodyHandlers.ofString());
		check(res.statusCode() == 200 && res.body().contains("\"formations\"") && res.body().contains("\"state\":\"service\""), "/api/depots(編成の状態つき)");
		for (final String ep : new String[]{"/api/vehicles", "/api/formations", "/api/routes", "/api/stations", "/api/delays", "/api/incidents", "/api/live", "/api/stations/11"}) {
			res = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ep)).build(), HttpResponse.BodyHandlers.ofString());
			check(res.statusCode() == 200 && res.body().startsWith("{") && res.body().endsWith("}"), ep + " が JSON を返す");
		}
		res = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/nothing")).build(), HttpResponse.BodyHandlers.ofString());
		check(res.statusCode() == 404, "未定義のエンドポイントは 404");

		res = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(), HttpResponse.BodyHandlers.ofString());
		check(res.statusCode() == 200 && res.body().contains("hi"), "静的ファイル配信 (/ → index.html)");

		// 悪いHost(DNSリバインディング)
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.getOutputStream().write("GET /api/status HTTP/1.1\r\nHost: evil.example.com\r\n\r\n".getBytes());
			final String line = new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
			check(line != null && line.contains("403"), "不正な Host ヘッダは 403");
		}
		// 悪いOrigin
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.getOutputStream().write(("GET /api/status HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nOrigin: https://evil.example.com\r\n\r\n").getBytes());
			final String line = new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
			check(line != null && line.contains("403"), "許可されていない Origin は 403");
		}
		// パストラバーサル
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.getOutputStream().write(("GET /../secret HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n").getBytes());
			final String line = new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
			check(line != null && line.contains("400"), "パストラバーサルは 400");
		}
		// POST 拒否(読み取り専用)
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.getOutputStream().write(("POST /api/status HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n").getBytes());
			final String line = new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
			check(line != null && line.contains("405"), "POST は 405(読み取り専用)");
		}
		server.stop();
	}


	static void geometryTests() throws Exception {
		System.out.println("[geometry]");
		final Geometry g = new Geometry();
		g.rev = 7;
		for (int i = 0; i < 3; i++) {
			final Geometry.Section sec = new Geometry.Section();
			sec.fromStationId = 10 + i; sec.toStationId = 11 + i; sec.distance = 123.5 + i;
			sec.points = new int[]{100 * i, 64, -50, 100 * i + 40, 65, -80, 100 * i + 99, 64, -130000};
			g.sections.add(sec);
		}
		final List<byte[]> parts = GeometryCodec.encode(g, 150);
		check(parts.size() > 1, "線形は上限を超えると複数パートに分かれる (" + parts.size() + ")");
		final ClientStore st = new ClientStore();
		Collections.reverse(parts);
		for (final byte[] b : parts) st.acceptGeometryPart(GeometryCodec.decode(b));
		final Geometry r = st.geometry();
		check(r.rev == 7 && r.sections.size() == 3, "全パートが揃うと復元される");
		final Geometry.Section a = r.find(11, 12);
		check(a != null && Math.abs(a.distance - 124.5) < 0.01 && a.points[8] == -130000 && a.points[3] == 140, "距離と頂点(差分符号化)が復元される");

		// API
		final Topology topo = new Topology();
		for (int i = 10; i <= 14; i++) {
			final Topology.Station s = new Topology.Station();
			s.id = i; s.name = "S" + i; s.x = i * 100; s.z = 0;
			topo.stations.add(s);
		}
		final Topology.Route route = new Topology.Route();
		route.id = 1; route.name = "R"; route.stationIds = new long[]{10, 11, 12, 13};
		topo.routes.add(route);
		st.setTopology(topo);
		final ApiHandler api = new ApiHandler(st);
		final Map<String, String> q = new HashMap<>();
		q.put("from", "10"); q.put("to", "12"); q.put("route", "1");
		final String d = api.handle("/api/distance", q).body;
		check(d.contains("\"distanceM\":248") && d.contains("\"method\":\"route\""), "路線に沿った2駅間距離(計測値の合計): " + d.substring(0, Math.min(120, d.length())));
		q.remove("route");
		final String d2 = api.handle("/api/distance", q).body;
		check(d2.contains("\"method\":\"graph\"") && d2.contains("\"allMeasured\":true"), "路線指定なしは区間グラフの最短経路");
		q.put("from", "13"); q.put("to", "14");
		check(api.handle("/api/distance", q).body.contains("\"allMeasured\":false"), "未計測の区間は直線距離で補い measured=false");
		final String rd = api.handle("/api/routes/1", new HashMap<>()).body;
		check(rd.contains("\"cumulativeM\"") && rd.contains("\"points\"") && rd.contains("\"totalM\""), "路線詳細に累積距離と線形");
		check(api.handle("/api/routes/999", new HashMap<>()).code == 404, "存在しない路線は404");
		check(api.handle("/api/sections", new HashMap<>()).body.contains("\"measured\":true"), "/api/sections");
	}

	static void entryMatcherTests() {
		System.out.println("[entry matcher]");
		final Topology topo = new Topology();
		for (int i = 1; i <= 4; i++) {
			final Topology.Station s = new Topology.Station();
			s.id = i; s.name = "S" + i; s.x = i * 1000; s.z = 0;
			topo.stations.add(s);
		}
		final Topology.Route route = new Topology.Route();
		route.id = 5; route.stationIds = new long[]{1, 2, 3, 4};
		topo.routes.add(route);
		final Snapshot snap = new Snapshot();
		// 駅4に向かう: 次駅=4(近い) / 次駅=3 / 次駅=2、別路線、駅3を出た後
		final long[][] defs = {{101, 5, 3, 3500}, {102, 5, 2, 2100}, {103, 5, 4, 3900}, {104, 9, 4, 3990}};
		for (final long[] d : defs) {
			final TrainView v = new TrainView();
			v.s.id = d[0]; v.s.routeId = d[1]; v.s.nextStationId = d[2]; v.s.onRoute = true; v.s.x = d[3];
			v.delaySec = (int) d[0];
			snap.trains.add(v);
		}
		final List<TrainView> c = EntryMatcher.candidates(snap, topo, 5, 4);
		check(c.size() == 3 && c.get(0).s.id == 103 && c.get(1).s.id == 101 && c.get(2).s.id == 102, "駅4への到着順: 103, 101, 102");
		check(EntryMatcher.match(snap, topo, 5, 4, 1).delaySec == 101, "rank=1 は2番目に着く列車");
		check(EntryMatcher.match(snap, topo, 5, 4, 3) == null, "列車数を超える rank は null");
		snap.trains.get(2).s.atStop = true; snap.trains.get(2).s.curStationId = 4;
		check(EntryMatcher.match(snap, topo, 5, 4, 0).s.id == 103, "停車中の列車が先頭");
		snap.trains.get(2).s.atStop = false; snap.trains.get(2).s.nextStationId = 3; snap.trains.get(2).s.x = 3300;
		check(EntryMatcher.candidates(snap, topo, 5, 2).size() == 1, "通り過ぎた列車は候補にならない(駅2は102のみ)");
	}

	static TrainSample etaSample(long id, long[] st, int[] ideal) {
		final TrainSample s = new TrainSample();
		s.id = id; s.onRoute = true;
		s.etaStationIds = st;
		s.etaPlatformIds = new long[st.length];
		s.etaRouteIds = new long[st.length];
		for (int i = 0; i < st.length; i++) { s.etaPlatformIds[i] = st[i] * 10; s.etaRouteIds[i] = 5; }
		s.etaIdealTicks = ideal;
		s.etaTicks = ideal.clone();
		s.etaSchedTicks = new int[st.length];
		Arrays.fill(s.etaSchedTicks, TrainSample.NO_SCHED);
		return s;
	}

	static void etaTests() throws Exception {
		System.out.println("[eta]");
		// 専用パケットで往復
		final TrainSample a = etaSample(1, new long[]{5, 6}, new int[]{200, 1500});
		a.etaSchedTicks[1] = 1400;
		final List<byte[]> parts = EtaCodec.encode(List.of(a), 3, 1000L, 900000);
		final ClientStore cs = new ClientStore();
		for (final byte[] b : parts) cs.acceptEtaPart(EtaCodec.decode(b));
		final List<EtaCodec.Entry> back = cs.etasOf(1);
		check(back.size() == 2 && back.get(1).stationId == 6 && back.get(1).ticks == 1500 && back.get(1).schedTicks == 1400 && back.get(0).platformId == 50 && back.get(0).schedTicks == TrainSample.NO_SCHED, "到着予想(駅・ホーム・路線・見込み・定刻)が専用パケットで往復する");
		final List<TrainSample> many = new ArrayList<>();
		for (int i = 0; i < 400; i++) many.add(etaSample(i, new long[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}));
		final List<byte[]> big = EtaCodec.encode(many, 4, 0, 30000);
		final ClientStore cs2 = new ClientStore();
		Collections.reverse(big);
		for (final byte[] b : big) cs2.acceptEtaPart(EtaCodec.decode(b));
		check(big.size() > 1 && cs2.etas().byTrain.size() == 400, "大きいときは分割して送り、揃ったら差し替える (" + big.size() + "パート)");

		// 通過するホーム: 専用パケットで往復し、補正では停車駅の基準を進めない
		final TrainSample ps = etaSample(7, new long[]{2, 3, 4}, new int[]{1000, 2000, 3000});
		ps.etaPass = new boolean[]{false, true, false};
		final ClientStore cs3 = new ClientStore();
		for (final byte[] b : EtaCodec.encode(List.of(ps), 9, 0L, 900000)) cs3.acceptEtaPart(EtaCodec.decode(b));
		check(!cs3.etasOf(7).get(0).pass && cs3.etasOf(7).get(1).pass && !cs3.etasOf(7).get(2).pass, "通過のフラグが専用パケットで往復する");
		final SectionStats pst = new SectionStats();
		pst.add(2, 4, 220, 200, 0);
		EtaPlanner.calibrate(ps, 1, pst);
		check(ps.etaTicks[0] == 1000 && ps.etaTicks[2] == 1000 + 2000 * 11 / 10 && ps.etaTicks[1] > ps.etaTicks[0] && ps.etaTicks[1] < ps.etaTicks[2], "通過時刻も前後の停車駅の間に入る: " + Arrays.toString(ps.etaTicks));

		// 駅間の実績で補正(遠い駅ほど早すぎる予想にならない)
		final SectionStats stats = new SectionStats();
		check(stats.add(1, 2, 120, 100, 0) && !stats.add(1, 2, 1000, 100, 0), "実績を足す(比率が極端なもの=待避などは捨てる)");
		stats.add(2, 3, 300, 200, 0);
		final TrainSample c = etaSample(2, new long[]{2, 3}, new int[]{2000, 6000});
		EtaPlanner.calibrate(c, 1, stats);
		check(c.etaTicks[0] == 2400 && c.etaTicks[1] == 2400 + 6000, "区間ごとの比率で伸ばして積み上げる: " + Arrays.toString(c.etaTicks));
		final TrainSample c2 = etaSample(3, new long[]{2, 3}, new int[]{2000, 6000});
		EtaPlanner.calibrate(c2, 0, stats);
		check(c2.etaTicks[0] == 2000 && c2.etaTicks[1] == 2000 + 6000, "直前の駅が分からない最初の区間は補正しない");
		check(Math.abs(stats.ratio(9, 9) - 1) < 1e-9 && Math.abs(stats.ratio(1, 2) - 1.2) < 1e-9, "実績が無い区間の比率は1");

		// 時刻表API用の同期と、駅の時刻表
		final IdealRun.Profile prof = profile(1, stop(1, 10, 100, 0, 30), stop(2, 11, 110, 100, 130), stop(3, 12, 120, 200, 230));
		final Map<Long, IdealRun.Profile> profs = new LinkedHashMap<>();
		profs.put(1L, prof);
		final long now = System.currentTimeMillis();
		final Map<Long, List<Long>> dep = new LinkedHashMap<>();
		dep.put(1L, List.of(now + 60_000, now + 660_000));
		final ScheduleSync.State ss = ScheduleSync.decode(ScheduleSync.encode(now, profs, dep, stats, 900000));
		check(ss.profiles.get(1L).stops.size() == 3 && ss.deploys.get(1L).length == 2 && Math.abs(ss.stats.ratio(1, 2) - 1.2) < 1e-9, "時刻表の同期(理想走行・出庫予定・実績)");
		final Map<String, double[]> runs = ScheduleSync.idealRunTimes(profs);
		check(Math.abs(runs.get(SectionStats.key(10, 11))[0] - 70) < 1e-9 && Math.abs(runs.get(SectionStats.key(10, 11))[1] - 30) < 1e-9, "駅間の走行時間と停車時間");

		final ClientStore st = new ClientStore();
		final Topology topo = new Topology();
		for (long id = 10; id <= 12; id++) {
			final Topology.Station x = new Topology.Station(); x.id = id; x.name = (id == 11 ? "長海|Nagaumi" : "駅" + id + "|Station " + id); topo.stations.add(x);
		}
		final Topology.Route r = new Topology.Route(); r.id = 5; r.name = "海線"; r.stationIds = new long[]{10, 11, 12}; topo.routes.add(r);
		final Topology.Depot dp = new Topology.Depot(); dp.id = 1; dp.name = "海車庫"; dp.routeIds = new long[]{5}; topo.depots.add(dp);
		st.setTopology(topo);
		for (final IdealRun.Stop x : prof.stops) x.routeId = 5;
		st.setSchedule(ScheduleSync.decode(ScheduleSync.encode(now, profs, dep, new SectionStats(), 900000)));
		final ApiHandler api = new ApiHandler(st);
		final String body = api.handle("/api/schedule/nagaumi", new HashMap<>()).body;
		check(body.contains("\"kind\":\"planned\"") && body.contains("\"stationId\":\"11\"") && body.split("\"kind\"").length - 1 == 2, "駅の時刻表(英名で指定、2時間先まで): " + body.substring(0, Math.min(160, body.length())));
		check(api.handle("/api/schedule/長海", new HashMap<>()).code == 200 && api.handle("/api/schedule/nowhere", new HashMap<>()).code == 404, "和名でも引ける / 無い駅は404");
		final String rt = api.handle("/api/runtimes", new HashMap<>()).body;
		check(rt.contains("\"runSec\":70") && rt.contains("\"dwellSec\":30"), "駅間の所要時間API");
		check(api.handle("/api/stations", new HashMap<>()).body.contains("\"slug\":\"nagaumi\""), "駅一覧にURL用の呼び名");
	}


	static IdealRun.Stop stop(int path, long station, long platform, double arr, double dep) {
		final IdealRun.Stop s = new IdealRun.Stop();
		s.pathIndex = path; s.stationId = station; s.platformId = platform; s.arr = arr; s.dep = dep; s.station = "駅" + station;
		return s;
	}

	static IdealRun.Profile profile(long depot, IdealRun.Stop... stops) {
		final IdealRun.Profile p = new IdealRun.Profile();
		p.depotId = depot; p.stops.addAll(Arrays.asList(stops));
		return p;
	}

	static void railTimeTests() throws Exception {
		System.out.println("[rail time]");
		// 走行時間(MTRと同じ1tick刻み)
		final RailTime.Rail sd = new RailTime.Rail(); sd.length = 40;
		final RailTime.Rail line = new RailTime.Rail(); line.length = 1000; line.maxSpeed = 1.0F;
		final RailTime.Rail pf = new RailTime.Rail(); pf.length = 40; pf.dwell = 20;
		final RailTime.Rail line2 = new RailTime.Rail(); line2.length = 500; line2.maxSpeed = 1.0F;
		final RailTime.Rail pf2 = new RailTime.Rail(); pf2.length = 40; pf2.dwell = 20;
		final RailTime.Result rt = RailTime.compute(new RailTime.Rail[]{sd, line, pf, line2, pf2}, 40, 2, 20, 0.01F, 0.3F);
		check(rt.arriveTick[2] > 1000 && rt.arriveTick[2] < 1300 && rt.arriveTick[0] < 0 && rt.endTick[0] >= 0 && rt.endTick[1] < rt.arriveTick[2], "走行時間: 1000ブロックを最高72km/h・加速0.01で約1100tick: " + rt.arriveTick[2]);
		check(rt.arriveTick[4] - rt.arriveTick[2] > 500 + 200 && rt.arriveTick[4] - rt.arriveTick[2] < 900, "走行時間: 停車(10秒=200tick)を含む次駅まで: " + (rt.arriveTick[4] - rt.arriveTick[2]));
	}


	private static class Part0 {
	}
}
