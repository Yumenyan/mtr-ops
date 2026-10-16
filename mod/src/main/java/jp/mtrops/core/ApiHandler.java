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
		w.name("stations").beginArray();
		for (int i = 0; i < r.stationIds.length; i++) {
			if (i > 0) {
				cum += segs.get(i - 1).distance;
			}
			final Topology.Station st = stationMap.get(r.stationIds[i]);
			w.beginObject();
			w.idField("id", r.stationIds[i]);
			w.field("name", st == null ? "" : st.name);
			if (st != null) {
				w.field("x", st.x).field("z", st.z);
			}
			w.field("cumulativeM", cum, 1);
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
		if (level >= 1 && s.etaStationIds.length > 0) {
			w.name("etas").beginArray();
			for (int i = 0; i < s.etaStationIds.length; i++) {
				final Topology.Station es = stationMap.get(s.etaStationIds[i]);
				w.beginObject().idField("stationId", s.etaStationIds[i]);
				if (es != null) {
					w.field("station", es.name);
				}
				w.field("sec", s.etaTicks[i] / 20);
				w.field("clock", clock((int) (snap.dayTime + s.etaTicks[i])));
				w.endObject();
			}
			w.endArray();
		}
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
