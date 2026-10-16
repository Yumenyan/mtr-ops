package jp.mtrops.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 路線・駅・車庫・車庫線のスナップショット(サーバーのMTRデータから作る静的寄りの情報)。
 * 変更があったときだけ rev を上げて再配信する。列車の位置はここには入れない。
 */
public final class Topology {

	public static final class Station {
		public long id;
		public String name = "";
		public int x, z;
		public int color;
		public int zone;
	}

	public static final class Route {
		public long id;
		public String name = "";
		/** 軽鉄道などの系統番号。なければ空。 */
		public String number = "";
		public int color;
		/** 0=NORMAL 1=LIGHT_RAIL 2=HIGH_SPEED (MTRのRouteType準拠。不明は0) */
		public int type;
		/** 0=環状でない 1=時計回り 2=反時計回り */
		public int circular;
		public boolean hidden;
		/** 0=TRAIN 1=BOAT 2=CABLE_CAR 3=AIRPLANE */
		public int mode;
		/** 停車駅の並び(連続する同一駅は1つに圧縮) */
		public long[] stationIds = new long[0];
		/** 終点の表示名(カスタム行先があればそれ)。空なら最後の駅名 */
		public String destination = "";
	}

	public static final class Siding {
		public long id;
		public String name = "";
		public long depotId;
		public int x, z;
		public String trainType = "";
		public int maxTrains;
		public boolean unlimited;
	}

	public static final class Depot {
		public long id;
		public String name = "";
		public int color;
		public int x, z;
		public String dimension = "";
		public long[] routeIds = new long[0];
	}

	public int rev;
	public final List<Station> stations = new ArrayList<>();
	public final List<Route> routes = new ArrayList<>();
	public final List<Depot> depots = new ArrayList<>();
	public final List<Siding> sidings = new ArrayList<>();

	public Station station(long id) {
		for (final Station s : stations) {
			if (s.id == id) {
				return s;
			}
		}
		return null;
	}

	public Route route(long id) {
		for (final Route r : routes) {
			if (r.id == id) {
				return r;
			}
		}
		return null;
	}

	public Depot depot(long id) {
		for (final Depot d : depots) {
			if (d.id == id) {
				return d;
			}
		}
		return null;
	}

	public Siding siding(long id) {
		for (final Siding s : sidings) {
			if (s.id == id) {
				return s;
			}
		}
		return null;
	}
}
