package jp.mtrops.core;

import java.util.List;
import java.util.Map;

/** カスタムダイヤ設定 ⇔ JSON(Webアプリ用)。時刻はすべて秒。IDは文字列で出し、数値・文字列どちらでも読む。 */
public final class DiaJson {

	private DiaJson() {
	}

	public static void writeConfig(JsonWriter w, DiaModel.Config c) {
		w.beginObject();
		w.field("rev", c.rev);
		w.field("sep", c.sep);
		w.field("minOpen", c.minOpen);
		w.field("maxHold", c.maxHold);
		w.field("slackSec", c.slackSec);
		w.field("slackPct", c.slackPct);
		w.name("routes").beginArray();
		for (final DiaModel.RouteSetting r : c.routes.values()) {
			w.beginObject();
			w.idField("routeId", r.routeId);
			w.field("custom", r.custom);
			w.field("safety", r.safety);
			w.field("overKmh", r.overKmh);
			w.field("maxKmh", r.maxKmh);
			w.field("delayThreshold", r.delayThreshold);
			w.endObject();
		}
		w.endArray();
		w.name("plans").beginArray();
		for (final DiaModel.DepotPlan p : c.plans.values()) {
			writePlan(w, p);
		}
		w.endArray();
		w.endObject();
	}

	public static void writePlan(JsonWriter w, DiaModel.DepotPlan p) {
		w.beginObject();
		w.idField("depotId", p.depotId);
		w.field("anchor", p.anchor);
		w.field("first", p.first);
		w.field("last", p.last);
		w.field("offset", p.offset);
		w.field("autoMesh", p.autoMesh);
		w.field("lead", p.lead);
		w.name("periods").beginArray();
		for (final DiaModel.Period x : p.periods) {
			w.beginObject().field("name", x.name).field("start", x.start).field("end", x.end).field("headway", x.headway).endObject();
		}
		w.endArray();
		w.name("extra").beginArray();
		p.extra.forEach(w::value);
		w.endArray();
		w.name("cancelled").beginArray();
		p.cancelled.forEach(w::value);
		w.endArray();
		w.name("holds").beginObject();
		for (final Map.Entry<Integer, Integer> e : p.holds.entrySet()) {
			w.field(Integer.toString(e.getKey()), e.getValue());
		}
		w.endObject();
		w.endObject();
	}

	/** Webアプリから届いた設定を読む。rev は呼び出し側で扱う。不正な値は例外。 */
	public static DiaModel.Config readConfig(Object json) {
		final Map<String, Object> o = JsonReader.obj(json);
		final DiaModel.Config c = new DiaModel.Config();
		c.rev = JsonReader.num(o.get("rev"), 0);
		c.sep = JsonReader.num(o.get("sep"), c.sep);
		c.minOpen = JsonReader.num(o.get("minOpen"), c.minOpen);
		c.maxHold = JsonReader.num(o.get("maxHold"), c.maxHold);
		c.slackSec = JsonReader.num(o.get("slackSec"), c.slackSec);
		c.slackPct = JsonReader.num(o.get("slackPct"), c.slackPct);
		for (final Object ro : JsonReader.arr(o.get("routes"))) {
			final Map<String, Object> m = JsonReader.obj(ro);
			final DiaModel.RouteSetting r = new DiaModel.RouteSetting();
			r.routeId = JsonReader.lng(m.get("routeId"), 0);
			if (r.routeId == 0) {
				continue;
			}
			r.custom = JsonReader.bool(m.get("custom"), false);
			r.safety = JsonReader.num(m.get("safety"), DiaModel.SAFETY_ATC);
			r.overKmh = JsonReader.num(m.get("overKmh"), 0);
			r.maxKmh = JsonReader.num(m.get("maxKmh"), 0);
			r.delayThreshold = JsonReader.num(m.get("delayThreshold"), 10);
			c.routes.put(r.routeId, r);
		}
		for (final Object po : JsonReader.arr(o.get("plans"))) {
			final DiaModel.DepotPlan p = readPlan(po);
			if (p.depotId != 0) {
				c.plans.put(p.depotId, p);
			}
		}
		final String err = c.validate();
		if (err != null) {
			throw new IllegalArgumentException(err);
		}
		return c;
	}

	static DiaModel.DepotPlan readPlan(Object po) {
		final Map<String, Object> m = JsonReader.obj(po);
		final DiaModel.DepotPlan p = new DiaModel.DepotPlan();
		p.depotId = JsonReader.lng(m.get("depotId"), 0);
		p.anchor = JsonReader.num(m.get("anchor"), 0);
		p.first = time(m.get("first"), p.first);
		p.last = time(m.get("last"), p.last);
		p.offset = JsonReader.num(m.get("offset"), 0);
		p.autoMesh = JsonReader.bool(m.get("autoMesh"), true);
		p.lead = JsonReader.num(m.get("lead"), p.lead);
		for (final Object xo : JsonReader.arr(m.get("periods"))) {
			final Map<String, Object> x = JsonReader.obj(xo);
			final DiaModel.Period per = new DiaModel.Period();
			per.name = JsonReader.str(x.get("name"), "");
			per.start = time(x.get("start"), -1);
			per.end = time(x.get("end"), -1);
			per.headway = JsonReader.num(x.get("headway"), -1);
			p.periods.add(per);
		}
		addTimes(p.extra, m.get("extra"));
		addTimes(p.cancelled, m.get("cancelled"));
		for (final Map.Entry<String, Object> e : JsonReader.obj(m.get("holds")).entrySet()) {
			final int k = JsonReader.num(e.getKey(), -1);
			final int v = JsonReader.num(e.getValue(), 0);
			if (k >= 0 && v > 0) {
				p.holds.put(k, v);
			}
		}
		return p;
	}

	private static void addTimes(List<Integer> out, Object o) {
		for (final Object x : JsonReader.arr(o)) {
			final int t = time(x, -1);
			if (t >= 0) {
				out.add(t);
			}
		}
	}

	/** 秒(数値)でも "HH:MM[:SS]" でも受け付ける。 */
	static int time(Object o, int def) {
		if (o instanceof String && ((String) o).contains(":")) {
			final int t = DiaModel.parseHms((String) o);
			return t < 0 ? def : t;
		}
		return JsonReader.num(o, def);
	}
}
