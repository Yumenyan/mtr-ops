package jp.mtrops.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * ローカルHTTP APIの中身(HTTPサーバーとは独立。ClientStore だけを見る)。
 *
 * GET /api/status        接続状態・件数・ゲーム内時刻
 * GET /api/live          画面更新用の一括取得(status + trains + incidents + routes)
 * GET /api/trains        ?route=&status=&depot=&q=&all=1
 * GET /api/trains/{id}   1編成の詳細
 * GET /api/depots        車庫 → 車庫線 → 編成(状態つき)
 * GET /api/vehicles      車両(1両ごと)の一覧
 * GET /api/formations    編成の一覧
 * GET /api/routes        路線と運行状況
 * GET /api/stations      駅 / GET /api/stations/{id} 到着予定つき
 * GET /api/delays        路線ごとの遅延集計
 * GET /api/incidents     ?active=1 で未解消のみ
 * GET /api/geometry      駅間ごとの距離と線形(折れ線)。?from=&to= で絞り込み
 * GET /api/sections      駅間距離のみ(?points=1 で線形も)
 * GET /api/routes/{id}   路線の駅順・駅間距離・累積距離・線形(?points=0 で線形なし)
 * GET /api/distance      ?from=駅ID&to=駅ID[&route=路線ID] 2駅間の線路沿い距離
 * GET /api/runtimes      駅間ごとの所要時間(MTRの理論値・走行・停車・実績)
 * GET /api/schedule/{駅} 駅の時刻表(出庫予定と所要時間の推定から。?hours=2)。駅はID・英名・和名で指定(例 nagaumi)
 * GET /api/rails         レールの形と種別(鉄道/船/ケーブルカー/飛行機、本線/ホーム/車庫線/折返し 等)。/api/geometry/rails も同じ
 * GET /api/dia           ダイヤ編成ツールの設定・路線ごとの時刻表(?route=ID で便を含める)・詰まり/待避の診断・運行中の列車の便と遅れ(読み取りのみ。編集はゲーム内のダイヤ編成ツール)
 * 列車の etas(到着予想)は折り返し後の路線も含む。既定は20分先まで(?etaHorizon=秒 で変更、最大7200)
 *
 * IDはJavaScriptで精度が落ちるため、すべて文字列で返す。
 * 詳細レベルが低くて載っていない項目は、キーごと省略する。
 */
public final class ApiHandler {

	public static final String VERSION = "2.0.0";

	public static final class Response {
		public final int code;
		public final String body;

		public Response(int code, String body) {
			this.code = code;
			this.body = body;
		}
	}

	private final ClientStore store;

	private Topology cachedTopology;
	private Map<Long, Topology.Station> stationMap = new HashMap<>();
	private Map<Long, Topology.Route> routeMap = new HashMap<>();
	private Map<Long, Topology.Depot> depotMap = new HashMap<>();
	private Map<Long, Topology.Siding> sidingMap = new HashMap<>();
	private OpsRegistry cachedRegistry;
	private Map<String, OpsRegistry.Formation> formationIdx = new HashMap<>();

	/** 編成の表示名。連番/桁置換: 1号車の車番、編成-N: 編成番号。 */
	private String titleOf(long sidingId, String formationId, int cars) {
		if (formationId == null || formationId.isEmpty()) {
			return "";
		}
		final OpsRegistry.Formation f = formationIdx.get(OpsRegistry.indexKey(sidingId, formationId));
		return f == null ? OpsRegistry.formationTitle(formationId, null) : store.registry().formationTitleFor(sidingId, f, cars);
	}

	/** 列車タイプID(例: mtr_custom_train_xxx)→表示名。クライアントのMTRリソース(mtr_custom_resources.json)から引く。 */
	private final Function<String, String> typeNames;

	public ApiHandler(ClientStore store) {
		this(store, id -> "");
	}

	public ApiHandler(ClientStore store, Function<String, String> typeNames) {
		this.store = store;
		this.typeNames = typeNames;
	}

	private String typeName(String trainType) {
		if (trainType == null || trainType.isEmpty()) {
			return "";
		}
		String n;
		try {
			n = typeNames.apply(trainType);
		} catch (RuntimeException e) {
			n = "";
		}
		if (n == null) {
			return "";
		}
		final int bar = n.indexOf('|');
		return (bar > 0 ? n.substring(0, bar) : n).trim();
	}

	/** 系列名: 編成に登録した系列を優先し、無ければ車両タイプ名。 */
	private String seriesOf(String registered, String trainType) {
		return registered != null && !registered.isEmpty() ? registered : typeName(trainType);
	}

	private synchronized void refreshMaps() {
		final OpsRegistry reg = store.registry();
		if (reg != cachedRegistry) {
			cachedRegistry = reg;
			formationIdx = reg.formationIndex();
		}
		final Topology t = store.topology();
		if (t == cachedTopology) {
			return;
		}
		cachedTopology = t;
		stationMap = new HashMap<>();
		routeMap = new HashMap<>();
		depotMap = new HashMap<>();
		sidingMap = new HashMap<>();
		t.stations.forEach(s -> stationMap.put(s.id, s));
		t.routes.forEach(r -> routeMap.put(r.id, r));
		t.depots.forEach(d -> depotMap.put(d.id, d));
		t.sidings.forEach(s -> sidingMap.put(s.id, s));
	}

	public Response handle(String path, Map<String, String> q) {
		refreshMaps();
		ETA_HORIZON.set(parseInt(q.get("etaHorizon"), 1200));
		final String p = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
		try {
			switch (p) {
				case "/api/status":
					return ok(status());
				case "/api/live":
					return ok(live());
				case "/api/trains":
					return ok(trains(q));
				case "/api/depots":
					return ok(depots());
				case "/api/vehicles":
					return ok(vehicles());
				case "/api/formations":
					return ok(formations());
				case "/api/routes":
					return ok(routes());
				case "/api/stations":
					return ok(stations());
				case "/api/delays":
					return ok(delays());
				case "/api/incidents":
					return ok(incidents(q));
				case "/api/ghosts":
					return ok(ghosts());
				case "/api/geometry":
					return ok(geometry(q, true));
				case "/api/sections":
					return ok(geometry(q, "1".equals(q.get("points"))));
				case "/api/runtimes":
					return ok(runtimes(q));
				case "/api/rails":
				case "/api/geometry/rails":
					return ok(RailNetwork.toJson(store.rails(), q));
				case "/api/dia":
					return ok(dia(q));
				case "/api/schedule":
					if (q.get("station") == null) {
						return new Response(400, error("station を指定してください(例 /api/schedule/nagaumi または ?station=駅ID)"));
					} else {
						final String body = schedule(q.get("station"), q);
						return body == null ? new Response(404, error("station not found")) : ok(body);
					}
				case "/api/distance": {
					final String body = distance(q);
					return body == null ? new Response(404, error("station not found")) : ok(body);
				}
				default:
					break;
			}
			if (p.startsWith("/api/trains/")) {
				final String body = trainDetail(p.substring("/api/trains/".length()));
				return body == null ? new Response(404, error("train not found")) : ok(body);
			}
			if (p.startsWith("/api/schedule/")) {
				final String body = schedule(p.substring("/api/schedule/".length()), q);
				return body == null ? new Response(404, error("station not found")) : ok(body);
			}
			if (p.startsWith("/api/routes/")) {
				final String body = routeDetail(p.substring("/api/routes/".length()), !"0".equals(q.get("points")));
				return body == null ? new Response(404, error("route not found")) : ok(body);
			}
			if (p.startsWith("/api/stations/")) {
				final String body = stationDetail(p.substring("/api/stations/".length()));
				return body == null ? new Response(404, error("station not found")) : ok(body);
			}
		} catch (RuntimeException e) {
			return new Response(500, error("internal error: " + e.getClass().getSimpleName()));
		}
		return new Response(404, error("unknown endpoint"));
	}

	private static Response ok(String body) {
		return new Response(200, body);
	}

	private static String error(String message) {
		return new JsonWriter().beginObject().field("ok", false).field("error", message).endObject().toString();
	}

	// ------------------------------------------------------------------ status / live

	private String status() {
		final JsonWriter w = new JsonWriter();
		writeStatus(w);
		return w.toString();
	}

	private void writeStatus(JsonWriter w) {
		final Snapshot s = store.live();
		final long age = store.liveAgeMillis();
		w.beginObject();
		w.field("ok", true);
		w.field("mod", "mtrops " + VERSION);
		w.field("connected", s != null);
		w.field("stale", s == null || age > 5000);
		w.field("snapshotAgeMs", s == null ? -1 : age);
		w.field("topologyRev", store.topology().rev);
		w.field("geometryRev", store.geometry().rev);
		w.field("customDia", !store.dia().customDepots.isEmpty());
		w.field("scheduleReceived", store.schedule().received);
		w.field("geometrySections", store.geometry().sections.size());
		if (s != null) {
			w.field("seq", s.seq);
			w.field("detailLevel", s.level);
			w.field("gameTick", s.gameTick);
			w.field("dayTime", s.dayTime);
			w.field("clock", clock(s.dayTime));
			int inService = 0;
			final EnumMap<TrainStatus, Integer> counts = new EnumMap<>(TrainStatus.class);
			for (final TrainView v : s.trains) {
				if (v.s.onRoute) {
					inService++;
					counts.merge(v.status, 1, Integer::sum);
				}
			}
			w.field("trainCount", s.trains.size());
			w.field("inService", inService);
			int active = 0;
			for (final Incident i : s.incidents) {
				if (i.isActive()) {
					active++;
				}
			}
			w.field("activeIncidents", active);
			w.name("counts").beginObject();
			for (final TrainStatus st : TrainStatus.values()) {
				w.field(st.key, counts.getOrDefault(st, 0));
			}
			w.endObject();
		}
		w.endObject();
	}

	private String live() {
		final Snapshot s = store.live();
		final JsonWriter w = new JsonWriter();
		w.beginObject();
		w.name("status");
		writeStatus(w);
		w.name("trains").beginArray();
		if (s != null) {
			for (final TrainView v : s.trains) {
				if (v.s.onRoute) {
					writeTrain(w, v, s, false);
				}
			}
		}
		w.endArray();
		w.name("incidents");
		writeIncidentArray(w, s, false);
		w.name("routes");
		writeRouteArray(w, s, false);
		w.endObject();
		return w.toString();
	}


	// ------------------------------------------------------------------ 駅間距離・線路の線形

	/** 駅間1区間の距離。measured=false は線路の実測が無く、駅の座標の直線距離で補ったもの。 */
	private static final class Seg {
		long from, to;
		double distance;
		boolean measured;
		boolean reversed;
		int[] points;
		Geometry.Section sec;
	}

	private Seg segment(long from, long to) {
		final Geometry g = store.geometry();
		final Seg seg = new Seg();
		seg.from = from;
		seg.to = to;
		Geometry.Section sec = g.find(from, to);
		if (sec == null) {
			sec = g.find(to, from);
			seg.reversed = sec != null;
		}
		if (sec != null) {
			seg.measured = true;
			seg.distance = sec.distance;
			seg.points = sec.points;
			seg.sec = sec;
			return seg;
		}
		final Topology.Station a = stationMap.get(from);
		final Topology.Station b = stationMap.get(to);
		seg.distance = a == null || b == null ? 0 : Math.hypot(a.x - b.x, a.z - b.z);
		seg.points = a == null || b == null ? new int[0] : new int[]{a.x, 0, a.z, b.x, 0, b.z};
		return seg;
	}

	private void writePoints(JsonWriter w, int[] pts, boolean reversed) {
		w.beginArray();
		final int n = pts.length / 3;
		for (int k = 0; k < n; k++) {
			final int i = reversed ? n - 1 - k : k;
			w.beginArray().value(pts[i * 3]).value(pts[i * 3 + 1]).value(pts[i * 3 + 2]).endArray();
		}
		w.endArray();
	}

	private void writeSeg(JsonWriter w, Seg seg, boolean withPoints) {
		w.beginObject();
		w.idField("fromStationId", seg.from);
		w.idField("toStationId", seg.to);
		final Topology.Station a = stationMap.get(seg.from);
		final Topology.Station b = stationMap.get(seg.to);
		if (a != null) {
			w.field("from", a.name);
		}
		if (b != null) {
			w.field("to", b.name);
		}
		w.field("distanceM", seg.distance, 1);
		w.field("measured", seg.measured);
		if (seg.sec != null) {
			writeParts(w, seg.sec, seg.reversed);
		}
		writeRuntime(w, seg.from, seg.to);
		if (withPoints) {
			w.name("points");
			writePoints(w, seg.points, seg.reversed);
		}
		w.endObject();
	}

	private String geometry(Map<String, String> q, boolean withPoints) {
		final Geometry g = store.geometry();
		final String from = q.get("from");
		final String to = q.get("to");
		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true).field("rev", g.rev);
		w.name("sections").beginArray();
		for (final Geometry.Section sec : g.sections) {
			if ((from != null && !from.equals(Long.toString(sec.fromStationId))) || (to != null && !to.equals(Long.toString(sec.toStationId)))) {
				continue;
			}
			final Seg seg = new Seg();
			seg.from = sec.fromStationId;
			seg.to = sec.toStationId;
			seg.distance = sec.distance;
			seg.measured = true;
			seg.points = sec.points;
			seg.sec = sec;
			writeSeg(w, seg, withPoints);
		}
		w.endArray().endObject();
		return w.toString();
	}

	private String routeDetail(String idText, boolean withPoints) {
		final Topology.Route r;
		try {
			r = routeMap.get(Long.parseLong(idText));
		} catch (NumberFormatException e) {
			return null;
		}
		if (r == null) {
			return null;
		}
		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true);
		w.idField("id", r.id).field("name", r.name);
		if (!r.number.isEmpty()) {
			w.field("number", r.number);
		}
		w.field("color", hex(r.color));
		w.field("circular", r.circular);
		w.field("destination", r.destination);
		double cum = 0;
		boolean allMeasured = true;
		final List<Seg> segs = new ArrayList<>();
		for (int i = 1; i < r.stationIds.length; i++) {
			segs.add(segment(r.stationIds[i - 1], r.stationIds[i]));
		}
		double cumSec = 0;
		boolean timeKnown = true;
		w.name("stations").beginArray();
		for (int i = 0; i < r.stationIds.length; i++) {
			if (i > 0) {
				cum += segs.get(i - 1).distance;
				final double t = travelSec(r.stationIds[i - 1], r.stationIds[i]);
				timeKnown &= t >= 0;
				cumSec += Math.max(0, t);
			}
			final Topology.Station st = stationMap.get(r.stationIds[i]);
			w.beginObject();
			w.idField("id", r.stationIds[i]);
			w.field("name", st == null ? "" : st.name);
			if (st != null) {
				w.field("x", st.x).field("z", st.z);
			}
			w.field("cumulativeM", cum, 1);
			if (timeKnown) {
				w.field("cumulativeSec", cumSec, 1);
			}
			w.endObject();
		}
		w.endArray();
		w.name("segments").beginArray();
		for (final Seg seg : segs) {
			allMeasured &= seg.measured;
			writeSeg(w, seg, withPoints);
		}
		w.endArray();
		w.field("totalM", cum, 1);
		w.field("allMeasured", allMeasured);
		w.endObject();
		return w.toString();
	}

	/** 2駅間の距離。route 指定: その路線の停車順に沿って合計。未指定: 駅間グラフの最短経路(無ければ直線)。 */
	private String distance(Map<String, String> q) {
		final long from, to;
		try {
			from = Long.parseLong(q.getOrDefault("from", ""));
			to = Long.parseLong(q.getOrDefault("to", ""));
		} catch (NumberFormatException e) {
			return null;
		}
		if (!stationMap.containsKey(from) || !stationMap.containsKey(to)) {
			return null;
		}
		List<Seg> path = null;
		String method = "";
		final String routeText = q.get("route");
		if (routeText != null) {
			try {
				final Topology.Route r = routeMap.get(Long.parseLong(routeText));
				if (r != null) {
					path = alongRoute(r, from, to);
					method = "route";
				}
			} catch (NumberFormatException ignored) {
			}
		}
		if (path == null) {
			path = shortestPath(from, to);
			method = "graph";
		}
		double total = 0;
		boolean allMeasured = true;
		for (final Seg seg : path) {
			total += seg.distance;
			allMeasured &= seg.measured;
		}
		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true);
		w.idField("fromStationId", from).idField("toStationId", to);
		w.field("distanceM", total, 1);
		w.field("method", method);
		w.field("allMeasured", allMeasured);
		w.field("straightM", straight(from, to), 1);
		w.name("segments").beginArray();
		for (final Seg seg : path) {
			writeSeg(w, seg, "1".equals(q.get("points")));
		}
		w.endArray().endObject();
		return w.toString();
	}

	private double straight(long a, long b) {
		final Topology.Station x = stationMap.get(a);
		final Topology.Station y = stationMap.get(b);
		return x == null || y == null ? 0 : Math.hypot(x.x - y.x, x.z - y.z);
	}

	private List<Seg> alongRoute(Topology.Route r, long from, long to) {
		int i = -1, j = -1;
		for (int k = 0; k < r.stationIds.length; k++) {
			if (r.stationIds[k] == from && i < 0) {
				i = k;
			}
			if (r.stationIds[k] == to && i >= 0 && k >= i) {
				j = k;
				break;
			}
		}
		final List<Seg> out = new ArrayList<>();
		if (i < 0 || j < 0) {
			return shortestPath(from, to);
		}
		for (int k = i + 1; k <= j; k++) {
			out.add(segment(r.stationIds[k - 1], r.stationIds[k]));
		}
		return out;
	}

	/** 計測済み区間+路線の隣接駅を辺とした最短経路(ダイクストラ)。繋がらなければ直線1区間。 */
	private List<Seg> shortestPath(long from, long to) {
		final Map<Long, List<long[]>> adj = new HashMap<>();
		final Set<String> seen = new HashSet<>();
		for (final Geometry.Section sec : store.geometry().sections) {
			addEdge(adj, seen, sec.fromStationId, sec.toStationId);
		}
		for (final Topology.Route r : store.topology().routes) {
			for (int i = 1; i < r.stationIds.length; i++) {
				addEdge(adj, seen, r.stationIds[i - 1], r.stationIds[i]);
			}
		}
		final Map<Long, Double> dist = new HashMap<>();
		final Map<Long, Long> prev = new HashMap<>();
		final java.util.PriorityQueue<double[]> pq = new java.util.PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
		final Map<Integer, Long> ids = new HashMap<>();
		dist.put(from, 0.0);
		ids.put(0, from);
		pq.add(new double[]{0, 0});
		int counter = 1;
		while (!pq.isEmpty()) {
			final double[] cur = pq.poll();
			final long u = ids.get((int) cur[1]);
			if (cur[0] > dist.getOrDefault(u, Double.MAX_VALUE)) {
				continue;
			}
			if (u == to) {
				break;
			}
			for (final long[] e : adj.getOrDefault(u, Collections.emptyList())) {
				final long v = e[0];
				final double nd = cur[0] + segment(u, v).distance;
				if (nd < dist.getOrDefault(v, Double.MAX_VALUE)) {
					dist.put(v, nd);
					prev.put(v, u);
					ids.put(counter, v);
					pq.add(new double[]{nd, counter++});
				}
			}
		}
		final List<Seg> out = new ArrayList<>();
		if (!prev.containsKey(to)) {
			if (from != to) {
				out.add(segment(from, to));
			}
			return out;
		}
		long cur = to;
		while (cur != from) {
			final long p = prev.get(cur);
			out.add(0, segment(p, cur));
			cur = p;
		}
		return out;
	}

	private static void addEdge(Map<Long, List<long[]>> adj, Set<String> seen, long a, long b) {
		if (a == b || !seen.add(Math.min(a, b) + ":" + Math.max(a, b))) {
			return;
		}
		adj.computeIfAbsent(a, k -> new ArrayList<>()).add(new long[]{b});
		adj.computeIfAbsent(b, k -> new ArrayList<>()).add(new long[]{a});
	}


	// ------------------------------------------------------------------ レールの種別(区間の内訳)

	private static void writeParts(JsonWriter w, Geometry.Section sec, boolean reversed) {
		if (!sec.mode.isEmpty()) {
			w.field("mode", sec.mode);
		}
		final int n = Math.min(sec.partTypes.length, Math.min(sec.partFrom.length, sec.partTo.length));
		if (n == 0) {
			return;
		}
		w.name("parts").beginArray();
		for (int k = 0; k < n; k++) {
			final int i = reversed ? n - 1 - k : k;
			final double a = reversed ? sec.distance - sec.partTo[i] : sec.partFrom[i];
			final double b = reversed ? sec.distance - sec.partFrom[i] : sec.partTo[i];
			w.beginObject().field("type", sec.partTypes[i]).field("role", RailNetwork.role(sec.partTypes[i]));
			w.field("fromM", a, 1).field("toM", b, 1).field("lengthM", b - a, 1).endObject();
		}
		w.endArray();
	}

	// ------------------------------------------------------------------ 駅間の所要時間

	private ScheduleSync.State cachedSchedule;
	private DiaSync.State cachedDia;
	private Map<String, double[]> idealRuns = new HashMap<>();
	private Map<Long, String> platformNames = new HashMap<>();
	private Map<Long, Double> platformDwell = new HashMap<>();

	/** 全車庫の理想走行(時刻表API用の同期 + カスタムダイヤの同期)から、駅間の走行・停車時間とホーム名を引く表を作る。 */
	private synchronized Map<String, double[]> idealRuns() {
		final ScheduleSync.State ss = store.schedule();
		final DiaSync.State ds = store.dia();
		if (ss != cachedSchedule || ds != cachedDia) {
			cachedSchedule = ss;
			cachedDia = ds;
			final Map<Long, DiaModel.Profile> all = new java.util.LinkedHashMap<>(ss.profiles);
			ds.profiles.forEach(all::putIfAbsent);
			idealRuns = ScheduleSync.idealRunTimes(all);
			final Map<Long, String> names = new HashMap<>();
			final Map<Long, Double> dwell = new HashMap<>();
			for (final DiaModel.Profile p : all.values()) {
				for (final DiaModel.Stop st : p.stops) {
					names.putIfAbsent(st.platformId, st.platform);
					if (!st.pass) {
						dwell.putIfAbsent(st.platformId, Math.max(0, st.dep - st.arr));
					}
				}
			}
			platformNames = names;
			platformDwell = dwell;
		}
		return idealRuns;
	}

	/** 駅間1区間の所要時間。travelSec = 実績(あれば)、無ければ理論上の走行+停車。 */
	private void writeRuntime(JsonWriter w, long from, long to) {
		final double[] ir = idealRuns().get(SectionStats.key(from, to));
		final SectionStats.Stat st = store.schedule().stats.get(from, to);
		if (ir == null && st == null) {
			return;
		}
		w.name("runtime").beginObject();
		if (ir != null) {
			w.field("runSec", ir[0], 1);
			w.field("dwellSec", ir[1], 1);
		}
		if (st != null) {
			w.field("mtrSec", st.idealSec, 1);
			w.field("observedSec", st.actualSec, 1);
			w.field("samples", st.samples);
			w.field("ratio", st.ratio(), 3);
		}
		final double travel = travelSec(from, to);
		if (travel >= 0) {
			w.field("travelSec", travel, 1);
		}
		w.endObject();
	}

	/** 前の駅に着いてから次の駅に着くまでの見込み(秒)。分からなければ -1。 */
	private double travelSec(long from, long to) {
		final SectionStats.Stat st = store.schedule().stats.get(from, to);
		if (st != null && st.samples > 0) {
			return st.actualSec;
		}
		final double[] ir = idealRuns().get(SectionStats.key(from, to));
		return ir == null ? -1 : ir[0] + ir[1];
	}

	private String runtimes(Map<String, String> q) {
		final Map<String, double[]> ir = idealRuns();
		final Map<String, long[]> keys = new java.util.LinkedHashMap<>();
		for (final String k : ir.keySet()) {
			final String[] p = k.split(">");
			keys.put(k, new long[]{Long.parseLong(p[0]), Long.parseLong(p[1])});
		}
		for (final SectionStats.Stat st : store.schedule().stats.all()) {
			keys.putIfAbsent(SectionStats.key(st.fromStationId, st.toStationId), new long[]{st.fromStationId, st.toStationId});
		}
		final String from = q.get("from");
		final String to = q.get("to");
		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true);
		w.field("note", "runSec=駅間の走行(MTRの理論値) / dwellSec=前の駅の停車 / mtrSec=MTRの理論値(到着→到着) / observedSec=実測の平均(到着→到着) / travelSec=見込み");
		w.name("sections").beginArray();
		for (final long[] k : keys.values()) {
			if ((from != null && !from.equals(Long.toString(k[0]))) || (to != null && !to.equals(Long.toString(k[1])))) {
				continue;
			}
			w.beginObject().idField("fromStationId", k[0]).idField("toStationId", k[1]);
			final Topology.Station a = stationMap.get(k[0]);
			final Topology.Station b = stationMap.get(k[1]);
			if (a != null) {
				w.field("from", a.name);
			}
			if (b != null) {
				w.field("to", b.name);
			}
			final double[] r = ir.get(SectionStats.key(k[0], k[1]));
			if (r != null) {
				w.field("runSec", r[0], 1).field("dwellSec", r[1], 1);
			}
			final SectionStats.Stat st = store.schedule().stats.get(k[0], k[1]);
			if (st != null) {
				w.field("mtrSec", st.idealSec, 1).field("observedSec", st.actualSec, 1).field("samples", st.samples).field("ratio", st.ratio(), 3);
			}
			w.field("travelSec", travelSec(k[0], k[1]), 1);
			w.endObject();
		}
		w.endArray().endObject();
		return w.toString();
	}

	// ------------------------------------------------------------------ 駅の時刻表

	/** 駅の指定(ID / 英名 / 和名 / 駅名の一部)→ 駅。英数字と文字以外と大文字小文字を無視して比べる。 */
	private Topology.Station resolveStation(String key) {
		if (key == null || key.isEmpty()) {
			return null;
		}
		try {
			final Topology.Station byId = stationMap.get(Long.parseLong(key));
			if (byId != null) {
				return byId;
			}
		} catch (NumberFormatException ignored) {
		}
		final String k = slug(key);
		if (k.isEmpty()) {
			return null;
		}
		Topology.Station prefix = null;
		for (final Topology.Station st : store.topology().stations) {
			final String[] parts = st.name.split("\\|");
			for (final String part : parts) {
				if (slug(stripCode(part)).equals(k)) {
					return st;
				}
			}
			if (slug(st.name).equals(k)) {
				return st;
			}
			if (prefix == null) {
				for (final String part : parts) {
					if (slug(stripCode(part)).startsWith(k)) {
						prefix = st;
					}
				}
			}
		}
		return prefix;
	}

	private static String stripCode(String name) {
		return name.replaceAll("[\\[［][^\\]］]*[\\]］]", "");
	}

	/** URLに使える駅の呼び名(英名があれば英名、無ければ和名を、文字と数字だけ・小文字にしたもの)。 */
	static String slug(String s) {
		final StringBuilder b = new StringBuilder();
		s.codePoints().forEach(c -> {
			if (Character.isLetterOrDigit(c)) {
				b.appendCodePoint(Character.toLowerCase(c));
			}
		});
		return b.toString();
	}

	static String stationSlug(String name) {
		final String[] parts = name.split("\\|");
		final String en = parts.length > 1 ? slug(stripCode(parts[1])) : "";
		return en.isEmpty() ? slug(stripCode(parts[0])) : en;
	}

	private static final class Ent {
		String kind;
		long arr;
		long dep;
		long sched = Long.MIN_VALUE;
		long platformId;
		long routeId;
		long depotId;
		boolean custom;
		boolean pass;
		TrainView train;
	}

	private String schedule(String key, Map<String, String> q) {
		final Topology.Station st = resolveStation(key);
		if (st == null) {
			return null;
		}
		idealRuns();
		double hours;
		try {
			hours = q.containsKey("hours") ? Double.parseDouble(q.get("hours")) : 2;
		} catch (NumberFormatException e) {
			hours = 2;
		}
		hours = Math.max(0.25, Math.min(6, hours));
		final long now = System.currentTimeMillis();
		final long until = now + (long) (hours * 3600_000L);
		final List<Ent> list = new ArrayList<>();
		final ScheduleSync.State ss = store.schedule();
		final DiaSync.State ds = store.dia();

		// 1. 走っている列車(到着予想。折り返し後の路線も含む)
		final Snapshot snap = store.live();
		final EtaCodec.Set es = store.etas();
		if (snap != null) {
			for (final TrainView v : snap.trains) {
				if (!v.s.onRoute) {
					continue;
				}
				for (final EtaCodec.Entry e : store.etasOf(v.s.id)) {
					if (e.stationId != st.id) {
						continue;
					}
					final Ent x = new Ent();
					x.kind = "live";
					x.arr = es.receivedMillis + e.ticks * 50L;
					if (x.arr < now - 30_000 || x.arr > until) {
						continue;
					}
					x.pass = e.pass;
					x.dep = e.pass ? x.arr : x.arr + (long) (platformDwell.getOrDefault(e.platformId, 0.0) * 1000);
					if (e.schedTicks != TrainSample.NO_SCHED) {
						x.sched = es.receivedMillis + e.schedTicks * 50L;
					}
					x.platformId = e.platformId;
					x.routeId = e.routeId != 0 ? e.routeId : v.s.routeId;
					x.depotId = v.s.depotId;
					x.custom = ds.plan.runs.containsKey(x.routeId);
					x.train = v;
					list.add(x);
				}
			}
		}

		// 2. これから出庫する列車(MTRの車庫の出庫時刻 + 理想走行を実績で補正した所要時間)
		for (final Map.Entry<Long, long[]> e : ss.deploys.entrySet()) {
			final DiaModel.Profile prof = ss.profiles.get(e.getKey());
			if (prof == null) {
				continue;
			}
			final double[] off = calibratedOffsets(prof, ss.stats);
			for (int k = 0; k < prof.stops.size(); k++) {
				final DiaModel.Stop stop = prof.stops.get(k);
				if (stop.pass || stop.stationId != st.id || ds.plan.runs.containsKey(stop.routeId)) {
					continue; // ダイヤ運転の路線は時刻表から
				}
				for (final long t0 : e.getValue()) {
					final Ent x = new Ent();
					x.kind = "planned";
					x.arr = ss.toClientMillis(t0) + (long) (off[k] * 1000);
					if (x.arr < now || x.arr > until) {
						continue;
					}
					x.dep = x.arr + (long) (Math.max(0, stop.dep - stop.arr) * 1000);
					x.platformId = stop.platformId;
					x.routeId = stop.routeId;
					x.depotId = prof.depotId;
					list.add(x);
				}
			}
		}

		// 3. ダイヤ運転の路線は時刻表どおり(まだ始発を出ていない便)
		if (ds.received) {
			final java.util.Set<String> running = new java.util.HashSet<>();
			for (final DiaRuntime.View v : store.diaLive().trains) {
				running.add(v.routeId + "#" + v.no);
			}
			for (final Map.Entry<Long, List<DiaModel.Run>> re : ds.plan.runs.entrySet()) {
				final DiaPlanner.RouteInfo ri = ds.plan.info.get(re.getKey());
				if (ri == null) {
					continue;
				}
				for (final DiaModel.Run r : re.getValue()) {
					if (running.contains(r.key()) || diaMillis(r.originDep, now, ds.utcOffset) < now) {
						continue;
					}
					for (int k = 0; k < ri.stops.size() && k < r.arr.length; k++) {
						final DiaModel.Stop stop = ri.stops.get(k);
						if (stop.stationId != st.id) {
							continue;
						}
						final Ent x = new Ent();
						x.kind = "planned";
						x.arr = diaMillis(r.arr[k], now, ds.utcOffset);
						if (x.arr < now || x.arr > until) {
							continue;
						}
						x.dep = diaMillis(r.dep[k], now, ds.utcOffset);
						x.sched = x.arr;
						x.platformId = stop.platformId;
						x.routeId = stop.routeId;
						x.custom = true;
						list.add(x);
					}
					for (int m = 0; m < r.passPlatform.length; m++) {
						final DiaModel.Stop ps = passStop(ri, r.passPlatform[m]);
						if (ps == null || ps.stationId != st.id) {
							continue;
						}
						final Ent x = new Ent();
						x.kind = "planned";
						x.pass = true;
						x.arr = diaMillis(r.passTime[m], now, ds.utcOffset);
						if (x.arr < now || x.arr > until) {
							continue;
						}
						x.dep = x.arr;
						x.sched = x.arr;
						x.platformId = ps.platformId;
						x.routeId = ps.routeId;
						x.custom = true;
						list.add(x);
					}
				}
			}
		}
		list.sort(java.util.Comparator.comparingLong(x -> x.arr));

		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true);
		w.idField("stationId", st.id).field("station", st.name).field("slug", stationSlug(st.name));
		w.field("now", now).field("hours", hours, 2);
		w.field("note", "kind=live は走行中の列車の到着予想(折り返し後も含む)、planned は車庫の出庫予定と所要時間からの推定(カスタムダイヤは時刻表)。時刻はこの端末の時計");
		w.name("departures").beginArray();
		for (final Ent x : list) {
			w.beginObject();
			w.field("kind", x.kind);
			if (x.pass) {
				w.field("pass", true);
			}
			w.field("arrival", hhmmss(x.arr)).field("departure", hhmmss(x.dep));
			w.field("arrivalMillis", x.arr).field("departureMillis", x.dep);
			w.field("inSec", Math.max(0, (x.arr - now) / 1000));
			if (x.sched != Long.MIN_VALUE) {
				w.field("scheduled", hhmmss(x.sched)).field("scheduledMillis", x.sched);
				w.field("delaySec", Math.max(0, (x.arr - x.sched) / 1000));
			}
			w.idField("platformId", x.platformId);
			final String pn = platformNames.get(x.platformId);
			if (pn != null && !pn.isEmpty()) {
				w.field("platform", pn);
			}
			final Topology.Route r = routeMap.get(x.routeId);
			w.idField("routeId", x.routeId);
			if (r != null) {
				w.field("routeName", r.name).field("routeColor", hex(r.color));
				if (!r.number.isEmpty()) {
					w.field("routeNumber", r.number);
				}
				final long last = r.stationIds.length == 0 ? 0 : r.stationIds[r.stationIds.length - 1];
				final Topology.Station dest = stationMap.get(last);
				w.field("destination", !r.destination.isEmpty() ? r.destination : dest == null ? "" : dest.name);
				w.field("terminating", !x.pass && last == st.id);
			}
			w.idField("depotId", x.depotId);
			final Topology.Depot d = depotMap.get(x.depotId);
			if (d != null) {
				w.field("depotName", d.name);
			}
			w.field("custom", x.custom);
			if (x.train != null) {
				final TrainSample s = x.train.s;
				w.idField("trainId", s.id);
				if (s.formationId != null) {
					w.field("formation", s.formationId).field("title", titleOf(s.sidingId, s.formationId, s.cars));
				}
				if (x.sched == Long.MIN_VALUE) {
					w.field("delaySec", x.train.delaySec);
				}
				if (s.cars > 0) {
					w.field("cars", s.cars);
				}
			}
			w.endObject();
		}
		w.endArray().endObject();
		return w.toString();
	}

	private static DiaModel.Stop passStop(DiaPlanner.RouteInfo ri, long platformId) {
		for (final List<DiaModel.Stop> l : ri.passes) {
			for (final DiaModel.Stop p : l) {
				if (p.platformId == platformId) {
					return p;
				}
			}
		}
		return null;
	}

	/** 車庫を出てから各停車駅に着くまでの秒(理想走行を、駅間ごとの実績比率で伸ばす)。通過駅は前の駅と同じ値。 */
	private static double[] calibratedOffsets(DiaModel.Profile prof, SectionStats stats) {
		final double[] off = new double[prof.stops.size()];
		double prevArr = 0;
		double prevOff = 0;
		long prevSt = 0;
		for (int k = 0; k < off.length; k++) {
			final DiaModel.Stop s = prof.stops.get(k);
			if (s.pass) {
				off[k] = prevOff + (s.arr - prevArr);
				continue;
			}
			final double d = Math.max(0, s.arr - prevArr);
			off[k] = prevOff + d * (prevSt == 0 ? 1 : stats.ratio(prevSt, s.stationId));
			prevArr = s.arr;
			prevOff = off[k];
			prevSt = s.stationId;
		}
		return off;
	}

	/** カスタムダイヤの「その日の秒」(サーバーのタイムゾーン)→ UNIXミリ秒(今に一番近い日付)。 */
	static long diaMillis(int daySec, long nowMillis, int utcOffset) {
		final long localNow = nowMillis / 1000 + utcOffset;
		final long dayStart = Math.floorDiv(localNow, DiaModel.DAY) * DiaModel.DAY;
		long t = dayStart + daySec;
		if (t - localNow > DiaModel.DAY / 2) {
			t -= DiaModel.DAY;
		} else if (localNow - t > DiaModel.DAY / 2) {
			t += DiaModel.DAY;
		}
		return (t - utcOffset) * 1000;
	}

	private static String hhmmss(long millis) {
		return java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault()).withNano(0).toString();
	}

	private static int parseInt(String s, int def) {
		try {
			return s == null ? def : Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	// ------------------------------------------------------------------ カスタムダイヤ(読み取りのみ)

	private String dia(Map<String, String> q) {
		final DiaSync.State st = store.dia();
		final DiaSync.Live live = store.diaLive();
		final Topology t = store.topology();
		final long only = q.containsKey("route") ? JsonReader.lng(q.get("route"), -1) : -1;
		final boolean allRuns = "all".equals(q.get("runs"));
		final JsonWriter w = new JsonWriter().beginObject();
		w.field("ok", true);
		w.field("received", st.received);
		w.field("zone", st.zone);
		w.field("utcOffset", st.utcOffset);
		double now = Double.NaN;
		if (live.receivedMillis > 0) {
			now = (live.serverNow + (System.currentTimeMillis() - live.receivedMillis) / 1000.0) % DiaModel.DAY;
		} else if (st.received) {
			now = Math.floorMod(System.currentTimeMillis() / 1000L + st.utcOffset, (long) DiaModel.DAY);
		}
		if (!Double.isNaN(now)) {
			w.field("serverNow", now, 1);
		}
		w.name("settings");
		DiaJson.writeConfig(w, st.config);
		w.name("customRoutes").beginArray();
		for (final DiaModel.RouteSetting r : st.config.routes.values()) {
			if (r.custom) {
				w.value(Long.toString(r.routeId));
			}
		}
		w.endArray();
		w.name("routes").beginArray();
		for (final Map.Entry<Long, DiaPlanner.RouteInfo> e : st.plan.info.entrySet()) {
			final DiaPlanner.RouteInfo ri = e.getValue();
			if (only != -1 && only != e.getKey()) {
				continue;
			}
			w.beginObject();
			w.idField("routeId", e.getKey());
			w.name("stops").beginArray();
			for (int i = 0; i < ri.stops.size(); i++) {
				final DiaModel.Stop s = ri.stops.get(i);
				w.beginObject().field("i", i).idField("stationId", s.stationId).idField("platformId", s.platformId)
						.field("station", s.station).field("platform", s.platform)
						.field("estRun", ri.estRun[i], 1).field("dwell", ri.dwell[i], 1)
						.field("offset", st.plan.offset(st.config, e.getKey(), i)).endObject();
			}
			w.endArray();
			final List<DiaModel.Run> runs = st.plan.runs.get(e.getKey());
			if (runs != null) {
				w.field("runCount", runs.size());
				if (allRuns || only == e.getKey()) {
					w.name("runs").beginArray();
					for (final DiaModel.Run r : runs) {
						w.beginObject().field("no", r.no).field("originDep", DiaModel.hms(r.originDep));
						w.name("arr").beginArray();
						for (final int x : r.arr) {
							w.value(DiaModel.hms(x));
						}
						w.endArray().name("dep").beginArray();
						for (final int x : r.dep) {
							w.value(DiaModel.hms(x));
						}
						w.endArray().endObject();
					}
					w.endArray();
				}
			}
			w.endObject();
		}
		w.endArray();
		w.name("issues").beginArray();
		for (final DiaModel.Issue is : st.plan.issues) {
			w.beginObject().field("kind", is.kind == 0 ? "info" : "problem").field("type", issueName(is.sub));
			w.idField("stationId", is.stationId).field("station", is.station).field("platform", is.platform);
			w.idField("routeA", is.routeA).idField("routeB", is.routeB);
			w.field("time", DiaModel.hms(is.time)).field("amount", is.amount).field("more", is.more).endObject();
		}
		w.endArray();
		w.name("warnings").beginArray();
		st.plan.warnings.forEach(w::value);
		w.endArray();
		w.name("live").beginArray();
		for (final DiaRuntime.View v : live.trains) {
			w.beginObject().idField("trainId", v.trainId).idField("routeId", v.routeId).field("no", v.no).field("lastStop", v.lastStop).field("delay", v.delay).endObject();
		}
		w.endArray();
		w.endObject();
		return w.toString();
	}

	private static String issueName(int sub) {
		switch (sub) {
			case DiaModel.Issue.PLATFORM:
				return "platform";
			case DiaModel.Issue.OVERTAKE:
				return "overtake";
			case DiaModel.Issue.SIDING:
				return "siding";
			case DiaModel.Issue.SHORT_TIME:
				return "shortTime";
			default:
				return "headway";
		}
	}

	// ------------------------------------------------------------------ trains

	private String trains(Map<String, String> q) {
		final Snapshot s = store.live();
		final String route = q.get("route");
		final String status = q.get("status");
		final String depot = q.get("depot");
		final String text = q.get("q") == null ? null : q.get("q").toLowerCase();
		final boolean all = "1".equals(q.get("all"));
		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true);
		w.name("trains").beginArray();
		if (s != null) {
			for (final TrainView v : s.trains) {
				if (!all && !v.s.onRoute) {
					continue;
				}
				if (route != null && !route.equals(Long.toString(v.s.routeId))) {
					continue;
				}
				if (status != null && !status.equals(v.status.key)) {
					continue;
				}
				if (depot != null && !depot.equals(Long.toString(v.s.depotId))) {
					continue;
				}
				if (text != null && !matches(v, text)) {
					continue;
				}
				writeTrain(w, v, s, false);
			}
		}
		w.endArray().endObject();
		return w.toString();
	}

	private boolean matches(TrainView v, String text) {
		if (v.s.formationId != null && v.s.formationId.toLowerCase().contains(text)) {
			return true;
		}
		final Topology.Route r = routeMap.get(v.s.routeId);
		if (r != null && (r.name.toLowerCase().contains(text) || r.number.toLowerCase().contains(text))) {
			return true;
		}
		final Topology.Station dest = stationMap.get(v.s.destStationId);
		return dest != null && dest.name.toLowerCase().contains(text);
	}

	private String trainDetail(String id) {
		final Snapshot s = store.live();
		if (s == null) {
			return null;
		}
		for (final TrainView v : s.trains) {
			if (Long.toString(v.s.id).equals(id) || (v.s.formationId != null && v.s.formationId.equals(id))) {
				final JsonWriter w = new JsonWriter();
				w.beginObject().field("ok", true).name("train");
				writeTrain(w, v, s, true);
				w.name("incidents");
				final List<Incident> mine = new ArrayList<>();
				for (final Incident i : s.incidents) {
					if (i.trainId == v.s.id) {
						mine.add(i);
					}
				}
				writeIncidents(w, mine, s);
				w.endObject();
				return w.toString();
			}
		}
		return null;
	}

	private static final ThreadLocal<Integer> ETA_HORIZON = ThreadLocal.withInitial(() -> 1200);

	/** 到着予想(折り返し後の路線も含む)。既定は20分先まで(etaHorizon)。カスタムダイヤは時刻表の到着(scheduled)も出す。 */
	private void writeEtas(JsonWriter w, TrainView v) {
		final List<EtaCodec.Entry> list = store.etasOf(v.s.id);
		if (list.isEmpty()) {
			return;
		}
		idealRuns();
		final EtaCodec.Set es = store.etas();
		final long now = System.currentTimeMillis();
		final int horizon = Math.max(60, Math.min(7200, ETA_HORIZON.get()));
		final double age = (now - es.receivedMillis) / 1000.0;
		w.name("etas").beginArray();
		for (final EtaCodec.Entry e : list) {
			final double sec = e.ticks / 20.0 - age;
			if (sec > horizon) {
				break;
			}
			final Topology.Station st = stationMap.get(e.stationId);
			w.beginObject().idField("stationId", e.stationId);
			if (st != null) {
				w.field("station", st.name);
			}
			w.idField("platformId", e.platformId);
			final String pn = platformNames.get(e.platformId);
			if (pn != null && !pn.isEmpty()) {
				w.field("platform", pn);
			}
			if (e.routeId != 0) {
				w.idField("routeId", e.routeId);
				final Topology.Route r = routeMap.get(e.routeId);
				if (r != null) {
					w.field("routeName", r.name);
				}
				w.field("turnback", e.routeId != v.s.routeId);
			}
			if (e.pass) {
				w.field("pass", true);
			}
			w.field("sec", Math.max(0, Math.round(sec)));
			final long at = es.receivedMillis + e.ticks * 50L;
			w.field("time", hhmmss(at)).field("millis", at);
			if (e.schedTicks != TrainSample.NO_SCHED) {
				final long sched = es.receivedMillis + e.schedTicks * 50L;
				w.field("scheduled", hhmmss(sched)).field("scheduledMillis", sched).field("scheduledSec", Math.round(e.schedTicks / 20.0 - age));
			}
			w.endObject();
		}
		w.endArray();
	}

	private void writeTrain(JsonWriter w, TrainView v, Snapshot snap, boolean detailed) {
		final TrainSample s = v.s;
		final int level = snap.level;
		w.beginObject();
		w.idField("id", s.id);
		if (s.formationId != null) {
			w.field("formation", s.formationId);
			w.field("title", titleOf(s.sidingId, s.formationId, s.cars));
		}
		final String ser = seriesOf(s.series, s.trainType);
		if (!ser.isEmpty()) {
			w.field("series", ser);
		}
		final String tn = typeName(s.trainType);
		if (!tn.isEmpty()) {
			w.field("typeName", tn);
		}
		w.idField("routeId", s.routeId);
		final Topology.Route route = routeMap.get(s.routeId);
		if (route != null) {
			w.field("routeName", route.name);
			if (!route.number.isEmpty()) {
				w.field("routeNumber", route.number);
			}
			w.field("routeColor", hex(route.color));
		}
		w.field("status", v.status.key);
		w.field("statusLabel", v.status.label);
		w.field("delaySec", v.delaySec);
		w.field("speedKmh", s.speedMps * 3.6, 1);
		w.field("x", s.x, 1);
		w.field("z", s.z, 1);
		w.field("onRoute", s.onRoute);
		if (s.suspect != 0) {
			w.name("suspect").beginArray();
			if ((s.suspect & TrainSample.SUSPECT_FROZEN) != 0) {
				w.value("frozen");
			}
			if ((s.suspect & TrainSample.SUSPECT_NOPATH) != 0) {
				w.value("nopath");
			}
			w.endArray();
		}
		w.field("manual", s.manual);
		w.field("doorOpen", s.doorOpen);
		w.field("atStop", s.atStop);
		w.field("dwelling", s.dwelling);
		if (level >= 1) {
			final Topology.Station next = stationMap.get(s.nextStationId);
			final Topology.Station dest = stationMap.get(s.destStationId);
			if (next != null) {
				w.idField("nextStationId", next.id);
				w.field("nextStation", next.name);
			}
			if (dest != null) {
				w.idField("destStationId", dest.id);
				w.field("destination", dest.name);
			} else if (route != null && !route.destination.isEmpty()) {
				w.field("destination", route.destination);
			}
		}
		writeEtas(w, v);
		if (level >= 2) {
			w.field("y", s.y, 1);
			if (!Float.isNaN(s.yaw)) {
				w.field("yaw", s.yaw, 1);
			}
			final Topology.Station prev = stationMap.get(s.prevStationId);
			final Topology.Station cur = stationMap.get(s.curStationId);
			if (prev != null) {
				w.idField("prevStationId", prev.id);
				w.field("prevStation", prev.name);
			}
			if (cur != null) {
				w.idField("currentStationId", cur.id);
				w.field("currentStation", cur.name);
			}
			if (v.progressPct >= 0) {
				w.field("progress", v.progressPct);
			}
			if (s.dwellRemainTicks >= 0) {
				w.field("dwellRemainSec", s.dwellRemainTicks / 20);
			}
		}
		if (level >= 3) {
			w.field("cars", s.cars);
			w.field("passengers", s.passengers);
			w.idField("depotId", s.depotId);
			w.idField("sidingId", s.sidingId);
			final Topology.Depot depot = depotMap.get(s.depotId);
			final Topology.Siding siding = sidingMap.get(s.sidingId);
			if (depot != null) {
				w.field("depotName", depot.name);
			}
			if (siding != null) {
				w.field("sidingName", siding.name);
			}
			w.field("trainType", s.trainType);
			if (s.formationId != null) {
				final OpsRegistry.Formation f = formationIdx.get(OpsRegistry.indexKey(s.sidingId, s.formationId));
				if (f != null) {
					w.name("carNumbers").beginArray();
					for (final String label : store.registry().carLabelsFor(s.sidingId, f, s.cars)) {
						w.value(label);
					}
					w.endArray();
				}
			}
		}
		if (level >= 4 && detailed && s.carX != null) {
			w.name("carPositions").beginArray();
			for (int i = 0; i < s.carX.length; i++) {
				w.beginArray().value(s.carX[i], 1).value(s.carZ[i], 1).value(s.carYaw[i], 0).endArray();
			}
			w.endArray();
		}
		w.endObject();
	}

	// ------------------------------------------------------------------ depots / formations / vehicles

	private Map<String, TrainView> trainsByFormation(Snapshot s) {
		final Map<String, TrainView> map = new HashMap<>();
		if (s != null) {
			for (final TrainView v : s.trains) {
				if (v.s.formationId != null) {
					map.put(OpsRegistry.indexKey(v.s.sidingId, v.s.formationId), v);
				}
			}
		}
		return map;
	}

	private static String stateOf(TrainView v) {
		if (v == null) {
			return "idle";
		}
		return v.s.onRoute ? "service" : "depot";
	}

	private String depots() {
		final Snapshot snap = store.live();
		final OpsRegistry reg = store.registry();
		final Map<String, TrainView> byFormation = trainsByFormation(snap);
		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true).name("depots").beginArray();

		final Set<Long> written = new HashSet<>();
		for (final Topology.Depot d : store.topology().depots) {
			written.add(d.id);
			final OpsRegistry.DepotRecord rec = reg.depots.get(d.id);
			w.beginObject();
			w.idField("id", d.id);
			w.field("name", d.name);
			w.field("color", hex(d.color));
			w.field("x", d.x);
			w.field("z", d.z);
			w.field("dimension", d.dimension);
			w.field("rev", rec == null ? 0 : rec.rev);
			w.name("routeIds").beginArray();
			for (final long rid : d.routeIds) {
				w.value(Long.toString(rid));
			}
			w.endArray();
			w.name("sidings").beginArray();
			for (final Topology.Siding sd : store.topology().sidings) {
				if (sd.depotId != d.id) {
					continue;
				}
				w.beginObject();
				w.idField("id", sd.id);
				w.field("name", sd.name);
				w.field("trainType", sd.trainType);
				w.field("typeName", typeName(sd.trainType));
				final OpsRegistry.NumberRange rng = rec == null ? null : rec.ranges.get(sd.id);
				if (rng != null) {
					w.name("range").beginObject().field("text", rng.describe()).field("start", rng.start).field("end", rng.end).endObject();
				}
				w.field("maxTrains", sd.maxTrains);
				w.field("unlimited", sd.unlimited);
				final List<OpsRegistry.Formation> list = rec == null ? null : rec.sidings.get(sd.id);
				int live = 0;
				w.name("formations").beginArray();
				if (list != null) {
					for (final OpsRegistry.Formation f : list) {
						final TrainView tv = byFormation.get(OpsRegistry.indexKey(sd.id, f.id));
						if (tv != null) {
							live++;
						}
						w.beginObject();
						w.field("id", f.id);
						w.field("title", titleOf(sd.id, f.id, f.cars > 0 ? f.cars : (tv == null ? 0 : tv.s.cars)));
						w.field("series", seriesOf(f.series, tv == null ? sd.trainType : tv.s.trainType));
						w.field("cars", f.cars > 0 ? f.cars : (tv == null ? 0 : tv.s.cars));
						w.field("state", stateOf(tv));
						if (tv != null) {
							w.idField("trainId", tv.s.id);
							w.field("status", tv.status.key);
						}
						w.endObject();
					}
				}
				w.endArray();
				// 編成番号が未割り当ての運行中列車(車番範囲が未設定、または範囲が満杯)
				w.name("unassigned").beginArray();
				if (snap != null) {
					for (final TrainView tv : snap.trains) {
						if (tv.s.sidingId == sd.id && (tv.s.formationId == null || tv.s.formationId.isEmpty())) {
							w.beginObject();
							w.idField("trainId", tv.s.id);
							w.field("label", tv.s.label());
							w.field("series", seriesOf(null, tv.s.trainType.isEmpty() ? sd.trainType : tv.s.trainType));
							w.field("cars", tv.s.cars);
							w.field("status", tv.status.key);
							final Topology.Route ur = routeMap.get(tv.s.routeId);
							if (ur != null) {
								w.field("routeName", ur.name);
							}
							w.endObject();
						}
					}
				}
				w.endArray();
				w.field("registered", list == null ? 0 : list.size());
				w.field("assigned", live);
				w.endObject();
			}
			w.endArray();
			w.endObject();
		}
		w.endArray().endObject();
		return w.toString();
	}

	private String formations() {
		final Snapshot snap = store.live();
		final Map<String, TrainView> byFormation = trainsByFormation(snap);
		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true).name("formations").beginArray();
		for (final OpsRegistry.DepotRecord rec : store.registry().depots.values()) {
			final Topology.Depot depot = depotMap.get(rec.depotId);
			for (final Map.Entry<Long, List<OpsRegistry.Formation>> e : rec.sidings.entrySet()) {
				final Topology.Siding siding = sidingMap.get(e.getKey());
				for (final OpsRegistry.Formation f : e.getValue()) {
					final TrainView tv = byFormation.get(OpsRegistry.indexKey(e.getKey(), f.id));
					w.beginObject();
					w.field("id", f.id);
					w.field("title", titleOf(e.getKey(), f.id, f.cars > 0 ? f.cars : (tv == null ? 0 : tv.s.cars)));
					w.field("series", seriesOf(f.series, tv == null ? (siding == null ? "" : siding.trainType) : tv.s.trainType));
					w.field("cars", f.cars > 0 ? f.cars : (tv == null ? 0 : tv.s.cars));
					w.idField("depotId", rec.depotId);
					w.field("depotName", depot == null ? "" : depot.name);
					w.idField("sidingId", e.getKey());
					w.field("sidingName", siding == null ? "" : siding.name);
					w.field("state", stateOf(tv));
					if (tv != null) {
						w.idField("trainId", tv.s.id);
						w.field("status", tv.status.key);
						final Topology.Route r = routeMap.get(tv.s.routeId);
						if (r != null) {
							w.field("routeName", r.name);
						}
					}
					w.endObject();
				}
			}
		}
		w.endArray().endObject();
		return w.toString();
	}

	private String vehicles() {
		final Snapshot snap = store.live();
		final Map<String, TrainView> byFormation = trainsByFormation(snap);
		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true).name("vehicles").beginArray();
		for (final OpsRegistry.DepotRecord rec : store.registry().depots.values()) {
			for (final Map.Entry<Long, List<OpsRegistry.Formation>> e : rec.sidings.entrySet()) {
				for (final OpsRegistry.Formation f : e.getValue()) {
					final TrainView tv = byFormation.get(OpsRegistry.indexKey(e.getKey(), f.id));
					final List<String> labels = store.registry().carLabelsFor(e.getKey(), f, tv == null ? 0 : tv.s.cars);
					for (int i = 0; i < labels.size(); i++) {
						w.beginObject();
						w.field("number", labels.get(i));
						w.field("formation", f.id);
						w.field("index", i + 1);
						w.field("series", seriesOf(f.series, tv == null ? "" : tv.s.trainType));
						w.idField("depotId", rec.depotId);
						w.idField("sidingId", e.getKey());
						w.field("state", stateOf(tv));
						if (tv != null) {
							w.idField("trainId", tv.s.id);
						}
						w.endObject();
					}
				}
			}
		}
		w.endArray().endObject();
		return w.toString();
	}

	// ------------------------------------------------------------------ routes / stations / delays

	private static final class RouteStat {
		int trains;
		int delayed;
		int maxDelay;
		long delaySum;
		TrainStatus worst = TrainStatus.NORMAL;
	}

	private Map<Long, RouteStat> routeStats(Snapshot s) {
		final Map<Long, RouteStat> stats = new LinkedHashMap<>();
		if (s == null) {
			return stats;
		}
		for (final TrainView v : s.trains) {
			if (!v.s.onRoute) {
				continue;
			}
			final RouteStat st = stats.computeIfAbsent(v.s.routeId, k -> new RouteStat());
			st.trains++;
			if (v.delaySec >= 60 || v.status != TrainStatus.NORMAL) {
				st.delayed++;
			}
			st.maxDelay = Math.max(st.maxDelay, v.delaySec);
			st.delaySum += v.delaySec;
			if (v.status.isWorseThan(st.worst)) {
				st.worst = v.status;
			}
		}
		return stats;
	}

	private String routes() {
		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true).name("routes");
		writeRouteArray(w, store.live(), true);
		w.endObject();
		return w.toString();
	}

	/**
	 * @param full false のとき(/api/live)は毎秒の転送量を抑えるため、状態の集計だけを返す。
	 *             名前・色・停車駅の並びは /api/routes から取得して結合する。
	 */
	private void writeRouteArray(JsonWriter w, Snapshot snap, boolean full) {
		final Map<Long, RouteStat> stats = routeStats(snap);
		w.beginArray();
		for (final Topology.Route r : store.topology().routes) {
			if (r.hidden) {
				continue;
			}
			final RouteStat st = stats.get(r.id);
			w.beginObject();
			w.idField("id", r.id);
			if (full) {
				w.field("name", r.name);
				if (!r.number.isEmpty()) {
					w.field("number", r.number);
				}
				w.field("color", hex(r.color));
				w.field("type", r.type);
				w.field("mode", r.mode);
				w.field("circular", r.circular);
				w.field("destination", r.destination);
				w.name("stationIds").beginArray();
				for (final long id : r.stationIds) {
					w.value(Long.toString(id));
				}
				w.endArray();
			}
			w.field("trainCount", st == null ? 0 : st.trains);
			w.field("delayedCount", st == null ? 0 : st.delayed);
			w.field("maxDelaySec", st == null ? 0 : st.maxDelay);
			w.field("status", st == null ? "noService" : st.worst.key);
			w.endObject();
		}
		w.endArray();
	}

	private String stations() {
		final Snapshot snap = store.live();
		final Map<Long, Integer> approaching = new HashMap<>();
		if (snap != null) {
			for (final TrainView v : snap.trains) {
				if (v.s.onRoute && v.s.nextStationId != 0) {
					approaching.merge(v.s.nextStationId, 1, Integer::sum);
				}
			}
		}
		final Map<Long, List<Long>> servedBy = new HashMap<>();
		for (final Topology.Route r : store.topology().routes) {
			for (final long sid : r.stationIds) {
				final List<Long> l = servedBy.computeIfAbsent(sid, k -> new ArrayList<>());
				if (!l.contains(r.id)) {
					l.add(r.id);
				}
			}
		}
		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true).name("stations").beginArray();
		for (final Topology.Station s : store.topology().stations) {
			w.beginObject();
			w.idField("id", s.id);
			w.field("name", s.name);
			w.field("slug", stationSlug(s.name));
			w.field("x", s.x);
			w.field("z", s.z);
			w.field("color", hex(s.color));
			w.field("zone", s.zone);
			w.field("approaching", approaching.getOrDefault(s.id, 0));
			w.name("routeIds").beginArray();
			for (final long rid : servedBy.getOrDefault(s.id, Collections.emptyList())) {
				w.value(Long.toString(rid));
			}
			w.endArray();
			w.endObject();
		}
		w.endArray().endObject();
		return w.toString();
	}

	private String stationDetail(String idText) {
		final Topology.Station st;
		try {
			st = stationMap.get(Long.parseLong(idText));
		} catch (NumberFormatException e) {
			return null;
		}
		if (st == null) {
			return null;
		}
		final Snapshot snap = store.live();
		final List<TrainView> incoming = new ArrayList<>();
		if (snap != null) {
			for (final TrainView v : snap.trains) {
				if (v.s.onRoute && v.s.nextStationId == st.id) {
					incoming.add(v);
				}
			}
		}
		incoming.sort(Comparator.comparingDouble(v -> Math.hypot(v.s.x - st.x, v.s.z - st.z)));
		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true);
		w.idField("id", st.id).field("name", st.name).field("x", st.x).field("z", st.z);
		w.name("arrivals").beginArray();
		for (int i = 0; i < Math.min(8, incoming.size()); i++) {
			final TrainView v = incoming.get(i);
			final double dist = Math.hypot(v.s.x - st.x, v.s.z - st.z);
			w.beginObject();
			w.idField("trainId", v.s.id);
			if (v.s.formationId != null) {
				w.field("formation", v.s.formationId);
				w.field("title", titleOf(v.s.sidingId, v.s.formationId, v.s.cars));
			}
			final Topology.Route r = routeMap.get(v.s.routeId);
			if (r != null) {
				w.field("routeName", r.name).field("routeColor", hex(r.color));
			}
			final Topology.Station dest = stationMap.get(v.s.destStationId);
			if (dest != null) {
				w.field("destination", dest.name);
			}
			w.field("distanceM", dist, 0);
			w.field("delaySec", v.delaySec);
			w.field("status", v.status.key);
			w.endObject();
		}
		w.endArray().endObject();
		return w.toString();
	}

	private String delays() {
		final Snapshot snap = store.live();
		final Map<Long, RouteStat> stats = routeStats(snap);
		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true);
		w.name("routes").beginArray();
		for (final Map.Entry<Long, RouteStat> e : stats.entrySet()) {
			final RouteStat st = e.getValue();
			final Topology.Route r = routeMap.get(e.getKey());
			w.beginObject();
			w.idField("routeId", e.getKey());
			w.field("name", r == null ? "(不明な路線)" : r.name);
			w.field("color", r == null ? "#888888" : hex(r.color));
			w.field("trainCount", st.trains);
			w.field("delayedCount", st.delayed);
			w.field("maxDelaySec", st.maxDelay);
			w.field("avgDelaySec", st.trains == 0 ? 0 : st.delaySum / (double) st.trains, 0);
			w.field("status", st.worst.key);
			w.endObject();
		}
		w.endArray();
		w.name("trains").beginArray();
		if (snap != null) {
			final List<TrainView> delayed = new ArrayList<>();
			for (final TrainView v : snap.trains) {
				if (v.delaySec > 0) {
					delayed.add(v);
				}
			}
			delayed.sort(Comparator.comparingInt(v -> -v.delaySec));
			for (final TrainView v : delayed) {
				w.beginObject();
				w.idField("id", v.s.id);
				if (v.s.formationId != null) {
					w.field("formation", v.s.formationId);
				w.field("title", titleOf(v.s.sidingId, v.s.formationId, v.s.cars));
				}
				w.idField("routeId", v.s.routeId);
				w.field("delaySec", v.delaySec);
				w.field("status", v.status.key);
				w.endObject();
			}
		}
		w.endArray().endObject();
		return w.toString();
	}

	// ------------------------------------------------------------------ incidents

	/** ゴースト疑いの列車(動かない・位置が取れない)と、過去に疑われて解消した記録。 */
	private String ghosts() {
		final Snapshot snap = store.live();
		final Map<Long, Incident> active = new HashMap<>();
		final List<Incident> past = new ArrayList<>();
		if (snap != null) {
			for (final Incident i : snap.incidents) {
				if (i.type == Incident.Type.GHOST) {
					if (i.isActive()) {
						active.put(i.trainId, i);
					} else {
						past.add(i);
					}
				}
			}
		}
		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true).name("suspects").beginArray();
		if (snap != null) {
			for (final TrainView v : snap.trains) {
				final TrainSample s = v.s;
				if (s.suspect == 0) {
					continue;
				}
				final Incident inc = active.get(s.id);
				final Topology.Route r = routeMap.get(s.routeId);
				final Topology.Depot d = depotMap.get(s.depotId);
				final Topology.Siding sd = sidingMap.get(s.sidingId);
				w.beginObject();
				w.idField("trainId", s.id);
				if (s.formationId != null) {
					w.field("formation", s.formationId);
			w.field("title", titleOf(s.sidingId, s.formationId, s.cars));
				}
				w.field("confirmed", inc != null);
				w.field("seconds", inc == null ? 0 : inc.seconds);
				w.field("onRoute", s.onRoute);
				w.field("speedKmh", s.speedMps * 3.6, 1);
				w.field("x", s.x, 0).field("y", Double.isNaN(s.y) ? 0 : s.y, 0).field("z", s.z, 0);
				w.idField("routeId", s.routeId);
				if (r != null) {
					w.field("routeName", r.name).field("routeColor", hex(r.color));
				}
				if (d != null) {
					w.field("depotName", d.name);
				}
				if (sd != null) {
					w.field("sidingName", sd.name);
				}
				w.name("reasons").beginArray();
				if ((s.suspect & TrainSample.SUSPECT_FROZEN) != 0) {
					w.beginObject().field("key", "frozen").field("label", "停止固着")
							.field("detail", "次の停車位置(nextStoppingIndex)が経路の長さ以上で、MTRが毎tick何もしません。列車は削除されず、通過した閉塞を塞ぎ続けます。").endObject();
				}
				if ((s.suspect & TrainSample.SUSPECT_NOPATH) != 0) {
					w.beginObject().field("key", "nopath").field("label", "経路・距離表なし")
							.field("detail", "経路が空、または距離表が不足していて位置を算出できません。座標は車庫線の位置です。").endObject();
				}
				w.endArray();
				w.endObject();
			}
		}
		w.endArray().name("past");
		writeIncidents(w, past, snap);
		w.endObject();
		return w.toString();
	}

	private String incidents(Map<String, String> q) {
		final Snapshot snap = store.live();
		final boolean activeOnly = "1".equals(q.get("active"));
		final JsonWriter w = new JsonWriter();
		w.beginObject().field("ok", true).name("incidents");
		writeIncidentArray(w, snap, activeOnly);
		w.endObject();
		return w.toString();
	}

	private void writeIncidentArray(JsonWriter w, Snapshot snap, boolean activeOnly) {
		final List<Incident> list = new ArrayList<>();
		if (snap != null) {
			for (final Incident i : snap.incidents) {
				if (!activeOnly || i.isActive()) {
					list.add(i);
				}
			}
		}
		writeIncidents(w, list, snap);
	}

	private void writeIncidents(JsonWriter w, List<Incident> list, Snapshot snap) {
		w.beginArray();
		for (final Incident i : list) {
			final Topology.Route r = routeMap.get(i.routeId);
			w.beginObject();
			w.field("id", i.id);
			w.field("type", i.type.key);
			w.field("typeLabel", i.type.label);
			w.field("severity", i.severity.key);
			w.idField("trainId", i.trainId);
			if (!i.formation.isEmpty()) {
				w.field("formation", i.formation);
			}
			w.idField("routeId", i.routeId);
			if (r != null) {
				w.field("routeName", r.name);
				w.field("routeColor", hex(r.color));
			}
			w.field("message", i.message);
			w.field("active", i.isActive());
			w.field("seconds", i.seconds);
			if (snap != null) {
				w.field("agoSec", Math.max(0, (snap.gameTick - i.sinceTick) / 20));
				if (!i.isActive()) {
					w.field("resolvedAgoSec", Math.max(0, (snap.gameTick - i.resolvedTick) / 20));
				}
			}
			w.endObject();
		}
		w.endArray();
	}

	// ------------------------------------------------------------------ helpers

	static String hex(int color) {
		return String.format("#%06x", color & 0xFFFFFF);
	}

	/** Minecraftの時刻(0=6:00)を HH:mm に。 */
	static String clock(int dayTime) {
		final int t = ((dayTime % 24000) + 24000 + 6000) % 24000;
		final int h = t / 1000;
		final int m = (t % 1000) * 60 / 1000;
		return String.format("%02d:%02d", h, m);
	}
}
