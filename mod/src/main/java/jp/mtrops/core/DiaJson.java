package jp.mtrops.core;

import java.util.Map;

/** カスタムダイヤの設定 → JSON(Webアプリ・スクリプト向けの読み取り専用)。時刻は秒。IDは文字列で出す。編集はゲーム内のダイヤ編成ツールで行う。 */
public final class DiaJson {

	private DiaJson() {
	}

	public static void writeConfig(JsonWriter w, DiaModel.Config c) {
		w.beginObject();
		w.field("rev", c.rev);
		w.field("sep", c.sep);
		w.field("minOpen", c.minOpen);
		w.name("routes").beginArray();
		for (final DiaModel.RouteSetting r : c.routes.values()) {
			w.beginObject();
			w.idField("routeId", r.routeId);
			w.field("custom", r.custom);
			w.field("safety", r.safety);
			w.field("overKmh", r.overKmh);
			w.field("maxKmh", r.maxKmh);
			w.field("delayThreshold", r.delayThreshold);
			if (r.table != null) {
				w.name("table");
				writeTable(w, r.table);
			}
			w.endObject();
		}
		w.endArray();
		w.endObject();
	}

	private static void writeTable(JsonWriter w, DiaModel.Table t) {
		w.beginObject();
		w.field("first", t.first).field("last", t.last);
		w.name("periods").beginArray();
		for (final DiaModel.Period x : t.periods) {
			w.beginObject().field("name", x.name).field("start", x.start).field("end", x.end).field("headway", x.headway).endObject();
		}
		w.endArray();
		w.name("extra").beginArray();
		t.extra.forEach(w::value);
		w.endArray();
		w.name("cancelled").beginArray();
		t.cancelled.forEach(w::value);
		w.endArray();
		w.name("offsets").beginObject();
		for (final Map.Entry<Integer, Integer> e : t.offsets.entrySet()) {
			w.field(Integer.toString(e.getKey()), e.getValue());
		}
		w.endObject();
		w.endObject();
	}
}
