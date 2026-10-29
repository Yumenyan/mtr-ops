package jp.mtrops.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * レール(線路)の形と種別。クライアントがMTRから読んだものを写して持ち、/api/geometry で返す。
 *
 * 種別の分類:
 *  mode  : train / boat / cable_car / airplane            (MTRの TransportMode)
 *  type  : MTRの RailType そのまま(WOODEN〜DIAMOND, PLATFORM, SIDING, TURN_BACK, CABLE_CAR, CABLE_CAR_STATION, RUNWAY, AIRPLANE_DUMMY, NONE)
 *  role  : line(本線) / platform(ホーム) / siding(車庫線) / turn_back(折返し) / station(ケーブルカーの駅) / runway(滑走路) / dummy
 */
public final class RailNetwork {

	public static final class Rail {
		public double x1, y1, z1, x2, y2, z2;
		public String mode = "";
		public String type = "";
		public String role = "";
		/** 最高速度 km/h(加速できるレールのみ。ホーム・車庫線などは0) */
		public int speedKmh;
		public double length;
		/** 片方向(逆向きは通れない) */
		public boolean oneWay;
		/** ホーム・車庫線ならそのID・名前(番線/車庫線名)と駅/車庫 */
		public long savedRailId;
		public String savedRailName = "";
		public long stationId;
		/** 形(x,z を交互に並べた点列。直線なら両端だけ) */
		public float[] points = new float[0];
	}

	public static final class Snapshot {
		public final List<Rail> rails = new ArrayList<>();
		public long builtMillis;
		public String dimension = "";
	}

	private RailNetwork() {
	}

	public static String role(String type) {
		switch (type) {
			case "PLATFORM":
				return "platform";
			case "SIDING":
				return "siding";
			case "TURN_BACK":
				return "turn_back";
			case "CABLE_CAR_STATION":
				return "station";
			case "RUNWAY":
				return "runway";
			case "AIRPLANE_DUMMY":
				return "dummy";
			case "NONE":
				return "none";
			default:
				return "line";
		}
	}

	/**
	 * クエリ: mode=train,boat / type=PLATFORM / role=platform,siding / bbox=x1,z1,x2,z2 / shape=0(両端だけ)
	 */
	public static String toJson(Snapshot snap, Map<String, String> q) {
		final java.util.Set<String> modes = set(q.get("mode"));
		final java.util.Set<String> types = set(q.get("type"));
		final java.util.Set<String> roles = set(q.get("role"));
		final boolean shape = !"0".equals(q.get("shape"));
		double[] bbox = null;
		if (q.containsKey("bbox")) {
			final String[] p = q.get("bbox").split(",");
			if (p.length == 4) {
				try {
					final double a = Double.parseDouble(p[0]), b = Double.parseDouble(p[1]), c = Double.parseDouble(p[2]), d = Double.parseDouble(p[3]);
					bbox = new double[]{Math.min(a, c), Math.min(b, d), Math.max(a, c), Math.max(b, d)};
				} catch (NumberFormatException ignored) {
				}
			}
		}
		final int limit = q.containsKey("limit") ? Math.max(1, Math.min(200_000, JsonReader.num(q.get("limit"), 50_000))) : 50_000;
		final JsonWriter w = new JsonWriter().beginObject();
		w.field("ok", true);
		w.field("dimension", snap == null ? "" : snap.dimension);
		w.field("builtMillis", snap == null ? 0 : snap.builtMillis);
		int count = 0;
		int total = 0;
		w.name("rails").beginArray();
		if (snap != null) {
			for (final Rail r : snap.rails) {
				if (!modes.isEmpty() && !modes.contains(r.mode)) {
					continue;
				}
				if (!types.isEmpty() && !types.contains(r.type.toLowerCase(Locale.ROOT))) {
					continue;
				}
				if (!roles.isEmpty() && !roles.contains(r.role)) {
					continue;
				}
				if (bbox != null && (Math.max(r.x1, r.x2) < bbox[0] || Math.min(r.x1, r.x2) > bbox[2] || Math.max(r.z1, r.z2) < bbox[1] || Math.min(r.z1, r.z2) > bbox[3])) {
					continue;
				}
				total++;
				if (count >= limit) {
					continue;
				}
				count++;
				w.beginObject();
				w.name("from").beginArray().value(r.x1, 1).value(r.y1, 1).value(r.z1, 1).endArray();
				w.name("to").beginArray().value(r.x2, 1).value(r.y2, 1).value(r.z2, 1).endArray();
				w.field("mode", r.mode).field("type", r.type).field("role", r.role);
				w.field("speedKmh", r.speedKmh).field("length", r.length, 1).field("oneWay", r.oneWay);
				if (r.savedRailId != 0) {
					w.idField("savedRailId", r.savedRailId).field("savedRailName", r.savedRailName);
					if (r.stationId != 0) {
						w.idField("stationId", r.stationId);
					}
				}
				if (shape && r.points.length > 4) {
					w.name("points").beginArray();
					for (int i = 0; i + 1 < r.points.length; i += 2) {
						w.beginArray().value(r.points[i], 1).value(r.points[i + 1], 1).endArray();
					}
					w.endArray();
				}
				w.endObject();
			}
		}
		w.endArray();
		w.field("count", count);
		w.field("total", total);
		w.field("truncated", total > count);
		w.endObject();
		return w.toString();
	}

	private static java.util.Set<String> set(String csv) {
		final java.util.Set<String> s = new java.util.HashSet<>();
		if (csv != null) {
			for (final String x : csv.split(",")) {
				if (!x.trim().isEmpty()) {
					s.add(x.trim().toLowerCase(Locale.ROOT));
				}
			}
		}
		return s;
	}
}
