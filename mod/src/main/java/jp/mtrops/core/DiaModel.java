package jp.mtrops.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * カスタムダイヤ(時刻表運転)の設定とデータ。MC/MTRに依存しない。
 *
 * 考え方:
 *  ・MTRの経路は「車庫(=その車庫に登録した路線の並び)」ごとに決まるので、時刻表は車庫ごとに組む。
 *  ・ダイヤ方式(通常/カスタム)・保安装置・回復運転は路線ごとに設定する。
 *    車庫に登録した路線のどれかがカスタムなら、その車庫の列車はカスタムダイヤで走る。
 *  ・時刻はすべて「サーバーのタイムゾーンでの、その日の0時からの秒」。日をまたぐ便は 86400 以上を使ってよい。
 */
public final class DiaModel {

	public static final int SAFETY_OFF = 0;
	public static final int SAFETY_ATS = 1;
	public static final int SAFETY_ATC = 2;

	public static final int DAY = 86400;
	public static final int MAX_ROUTES = 2000;
	public static final int MAX_PERIODS = 48;
	public static final int MAX_EXTRA = 400;
	public static final int MAX_RUNS_PER_ROUTE = 2000;

	private DiaModel() {
	}

	/** 路線ごとの設定。 */
	public static final class RouteSetting {
		public long routeId;
		/** true = ダイヤ運転(時刻表どおりに発車) / false = MTRの通常方式 */
		public boolean custom;
		/** 保安装置: 0=なし(MTR標準) 1=ATS(段階的な速度制限) 2=ATC(連続パターン) */
		public int safety = SAFETY_ATC;
		/** 遅延時、レールの最高速度を何km/h超えてよいか(0=回復運転なし) */
		public int overKmh;
		/** 回復運転の上限速度km/h(0=上限なし。レール速度+overKmh まで) */
		public int maxKmh;
		/** 何秒以上遅れたら回復運転するか */
		public int delayThreshold = 10;
		/** この路線の時刻表(ダイヤ運転のとき) */
		public Table table;

		public RouteSetting copy() {
			final RouteSetting r = new RouteSetting();
			r.routeId = routeId;
			r.custom = custom;
			r.safety = safety;
			r.overKmh = overKmh;
			r.maxKmh = maxKmh;
			r.delayThreshold = delayThreshold;
			r.table = table == null ? null : table.copy();
			return r;
		}

		boolean valid() {
			return safety >= 0 && safety <= 2 && overKmh >= 0 && overKmh <= 200 && maxKmh >= 0 && maxKmh <= 1000 && delayThreshold >= 0 && delayThreshold <= 3600 && (table == null || table.valid());
		}
	}

	/** 時間帯(朝・昼・夕など)ごとの運転間隔。start〜end(秒, endは含まない)。 */
	public static final class Period {
		public int start;
		public int end;
		public int headway;
		public String name = "";

		public Period() {
		}

		public Period(String name, int start, int end, int headway) {
			this.name = name;
			this.start = start;
			this.end = end;
			this.headway = headway;
		}

		public Period copy() {
			return new Period(name, start, end, headway);
		}
	}

	/**
	 * 路線の時刻表。「始発ホームを発車する時刻」の並び(初便・終便・時間帯ごとの間隔・追加・取りやめ)と、
	 * 路線のホームごとの「始発の発車からの経過秒」(offsets)で決まる。続行便は始発の発車時刻に offsets を足したもの。
	 * ホームの番号は MTR の路線のホームの並び(0 = 始発ホーム)。offsets に無いホームは、MTRの走行時間から作った既定値。
	 * 発車時刻は MTR の停車時間とは無関係に、この表のとおりに発車する。
	 */
	public static final class Table {
		public int first = 5 * 3600;
		public int last = 24 * 3600;
		/** 時間帯ごとの間隔。どの時間帯にも入らない時刻は運転しない。 */
		public final List<Period> periods = new ArrayList<>();
		/** 追加する便(始発ホームの発車時刻) */
		public final List<Integer> extra = new ArrayList<>();
		/** 取りやめる便(始発ホームの発車時刻) */
		public final List<Integer> cancelled = new ArrayList<>();
		/** ホームの番号 → 始発の発車からの経過秒(そのホームの発車時刻) */
		public final Map<Integer, Integer> offsets = new TreeMap<>();

		public Table copy() {
			final Table t = new Table();
			t.first = first;
			t.last = last;
			periods.forEach(x -> t.periods.add(x.copy()));
			t.extra.addAll(extra);
			t.cancelled.addAll(cancelled);
			t.offsets.putAll(offsets);
			return t;
		}

		/** 初期値: 終日10分間隔。 */
		public static Table defaults() {
			final Table t = new Table();
			t.periods.add(new Period("終日", 0, 2 * DAY, 600));
			return t;
		}

		boolean valid() {
			if (first < 0 || last < first || last > 2 * DAY || periods.size() > MAX_PERIODS || extra.size() > MAX_EXTRA || cancelled.size() > MAX_EXTRA || offsets.size() > 500) {
				return false;
			}
			for (final Period x : periods) {
				if (x.start < 0 || x.end <= x.start || x.end > 2 * DAY || x.headway < 30 || x.headway > 4 * 3600 || x.name == null || x.name.length() > 24) {
					return false;
				}
			}
			for (final Map.Entry<Integer, Integer> e : offsets.entrySet()) {
				if (e.getKey() < 0 || e.getValue() < 0 || e.getValue() > DAY) {
					return false;
				}
			}
			return true;
		}
	}

	/** ダイヤ全体の設定。 */
	public static final class Config {
		public int rev;
		/** 同じホームを使う列車どうしの最小間隔(前の列車の発車〜次の列車の到着、秒)。詰まりの診断の基準 */
		public int sep = 30;
		/** 遅れているとき、ドアが開いてから最短で何秒で閉め始めるか */
		public int minOpen = 3;
		public final Map<Long, RouteSetting> routes = new LinkedHashMap<>();

		public Config copy() {
			final Config c = new Config();
			c.rev = rev;
			c.sep = sep;
			c.minOpen = minOpen;
			routes.forEach((k, v) -> c.routes.put(k, v.copy()));
			return c;
		}

		public RouteSetting route(long routeId) {
			return routes.get(routeId);
		}

		public boolean isCustom(long routeId) {
			final RouteSetting r = routes.get(routeId);
			return r != null && r.custom;
		}

		/** 車庫に登録された路線のどれかがカスタムなら、その車庫の列車はダイヤ運転を含む。 */
		public boolean isDepotCustom(List<Long> depotRouteIds) {
			for (final Long id : depotRouteIds) {
				if (isCustom(id)) {
					return true;
				}
			}
			return false;
		}

		/** 入力値の検査。問題があれば理由、なければ null。 */
		public String validate() {
			if (sep < 0 || sep > 600 || minOpen < 0 || minOpen > 60) {
				return "全体設定の値が範囲外です";
			}
			if (routes.size() > MAX_ROUTES) {
				return "設定が多すぎます";
			}
			for (final RouteSetting r : routes.values()) {
				if (!r.valid()) {
					return "路線の設定が範囲外です (route " + r.routeId + ")";
				}
			}
			return null;
		}
	}

	/** 車庫から出た列車の、理想の走行(MTRの走行時間計算と同じ)。 */
	public static final class Profile {
		public long depotId;
		public long sidingId;
		public final List<Stop> stops = new ArrayList<>();
		/** 車庫を出てから経路の終わりまでの秒 */
		public double total;
	}

	public static final class Stop {
		/** MTRの PathData の位置(経路上の何番目のレールか) */
		public int pathIndex;
		public long stationId;
		public long platformId;
		public long routeId;
		public String station = "";
		public String platform = "";
		/** 路線の中での駅の番号(0始まり。ScheduleEntry.currentStationIndex と同じ)。不明は -1 */
		public int stationIndex = -1;
		/** 通過(止まらずにホームを通り抜ける)。到着=発車=通過時刻 */
		public boolean pass;
		/** 車庫を出てからの到着・発車(秒) */
		public double arr;
		public double dep;
	}

	/** 時刻表の1便(ある路線を始発ホームから終点まで走る1本の列車)。時刻はその日の0時からの秒。 */
	public static final class Run {
		public long routeId;
		/** 路線内の通し番号(1始まり、始発の発車順) */
		public int no;
		/** 始発ホームの発車時刻 */
		public int originDep;
		/** ホームごとの到着・発車(路線のホームの並びと同じ) */
		public int[] arr;
		public int[] dep;
		/** 途中で通過する他のホーム(ホームID)と通過時刻 */
		public long[] passPlatform = new long[0];
		public int[] passTime = new int[0];

		public String key() {
			return routeId + "#" + no;
		}
	}

	/** 時刻表の診断(詰まり・待避)。文章はUIが作る(路線名・駅名の表示を揃えるため)。 */
	public static final class Issue {
		public static final int PLATFORM = 0;
		public static final int OVERTAKE = 1;
		public static final int SIDING = 2;
		public static final int SHORT_TIME = 3;
		public static final int HEADWAY = 4;

		/** 0=待避できる(情報) 1=詰まる・足りない(要修正) */
		public int kind;
		/** PLATFORM=ホームが重なる / OVERTAKE=駅間で追い越すことになる / SIDING=待避できる / SHORT_TIME=所要時間が足りない / HEADWAY=間隔が近すぎる */
		public int sub;
		public long stationId;
		public String station = "";
		public String platform = "";
		public long routeA;
		public long routeB;
		public boolean passA;
		public boolean passB;
		/** 起きる時刻の例(その日の秒) */
		public int time;
		/** 足りない秒数・重なる秒数など */
		public int amount;
		/** 同じ問題が他に何件あるか */
		public int more;
	}

	/** 秒 → "HH:MM:SS"(24時以降もそのまま 25:10:00 のように出す) */
	public static String hms(int sec) {
		final boolean neg = sec < 0;
		final int s = Math.abs(sec);
		return (neg ? "-" : "") + two(s / 3600) + ":" + two(s / 60 % 60) + ":" + two(s % 60);
	}

	private static String two(int v) {
		return v < 10 ? "0" + v : Integer.toString(v);
	}

	/** "5:30", "05:30:15", "25:00" → 秒。不正なら -1。 */
	public static int parseHms(String text) {
		if (text == null) {
			return -1;
		}
		final String[] p = text.trim().split(":");
		if (p.length < 2 || p.length > 3) {
			return -1;
		}
		try {
			final int h = Integer.parseInt(p[0].trim());
			final int m = Integer.parseInt(p[1].trim());
			final int s = p.length == 3 ? Integer.parseInt(p[2].trim()) : 0;
			if (h < 0 || h > 48 || m < 0 || m > 59 || s < 0 || s > 59 || (h == 48 && (m > 0 || s > 0))) {
				return -1;
			}
			return h * 3600 + m * 60 + s;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** 2つの「その日の秒」の差 a-b を -12h〜+12h に丸める(日またぎ対策)。 */
	public static int wrapDiff(int a, int b) {
		int d = (a - b) % DAY;
		if (d < -DAY / 2) {
			d += DAY;
		} else if (d >= DAY / 2) {
			d -= DAY;
		}
		return d;
	}
}
