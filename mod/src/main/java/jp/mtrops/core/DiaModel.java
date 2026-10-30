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
	public static final int MAX_PLANS = 1000;
	public static final int MAX_PERIODS = 48;
	public static final int MAX_EXTRA = 400;
	public static final int MAX_RUNS_PER_DEPOT = 2000;

	private DiaModel() {
	}

	/** 路線ごとの設定。 */
	public static final class RouteSetting {
		public long routeId;
		/** true = カスタムダイヤ(時刻表運転) / false = MTRの通常方式 */
		public boolean custom;
		/** 保安装置: 0=なし(MTR標準) 1=ATS(段階的な速度制限) 2=ATC(連続パターン) */
		public int safety = SAFETY_ATC;
		/** 遅延時、レールの最高速度を何km/h超えてよいか(0=回復運転なし) */
		public int overKmh;
		/** 回復運転の上限速度km/h(0=上限なし。レール速度+overKmh まで) */
		public int maxKmh;
		/** 何秒以上遅れたら回復運転するか */
		public int delayThreshold = 10;

		public RouteSetting copy() {
			final RouteSetting r = new RouteSetting();
			r.routeId = routeId;
			r.custom = custom;
			r.safety = safety;
			r.overKmh = overKmh;
			r.maxKmh = maxKmh;
			r.delayThreshold = delayThreshold;
			return r;
		}

		boolean valid() {
			return safety >= 0 && safety <= 2 && overKmh >= 0 && overKmh <= 200 && maxKmh >= 0 && maxKmh <= 1000 && delayThreshold >= 0 && delayThreshold <= 3600;
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

	/** 車庫(運用)ごとの時刻表の組み方。 */
	public static final class DepotPlan {
		public long depotId;
		/** 発車時刻を指定する基準駅(Profile.stops の何番目か。0=最初の停車駅) */
		public int anchor;
		/** 基準駅の初電・終電の発車時刻 */
		public int first = 5 * 3600;
		public int last = 24 * 3600;
		/** 時間帯ごとの間隔。どの時間帯にも入らない時刻は運転しない。 */
		public final List<Period> periods = new ArrayList<>();
		/** 追加する便(基準駅の発車時刻) */
		public final List<Integer> extra = new ArrayList<>();
		/** 取りやめる便(基準駅の発車時刻) */
		public final List<Integer> cancelled = new ArrayList<>();
		/** 駅ごとの追加停車秒(停車駅の番号 → 秒)。待避や調整に使う。 */
		public final Map<Integer, Integer> holds = new TreeMap<>();
		/** 全便を何秒ずらすか(自動の噛み合わせで決めた値もここに入る) */
		public int offset;
		/** 他の車庫の列車と噛み合うよう、ずらしと待避を自動で決める */
		public boolean autoMesh = true;
		/** 出庫の余裕(秒)。早めに出して最初の駅で時刻まで待たせる */
		public int lead = 30;

		public DepotPlan copy() {
			final DepotPlan p = new DepotPlan();
			p.depotId = depotId;
			p.anchor = anchor;
			p.first = first;
			p.last = last;
			periods.forEach(x -> p.periods.add(x.copy()));
			p.extra.addAll(extra);
			p.cancelled.addAll(cancelled);
			p.holds.putAll(holds);
			p.offset = offset;
			p.autoMesh = autoMesh;
			p.lead = lead;
			return p;
		}

		boolean valid() {
			if (anchor < 0 || anchor > 500 || first < 0 || last < first || last > 2 * DAY || lead < 0 || lead > 600 || offset < -DAY || offset > DAY) {
				return false;
			}
			if (periods.size() > MAX_PERIODS || extra.size() > MAX_EXTRA || cancelled.size() > MAX_EXTRA || holds.size() > 500) {
				return false;
			}
			for (final Period x : periods) {
				if (x.start < 0 || x.end <= x.start || x.end > 2 * DAY || x.headway < 30 || x.headway > 4 * 3600 || x.name == null || x.name.length() > 24) {
					return false;
				}
			}
			for (final Map.Entry<Integer, Integer> e : holds.entrySet()) {
				if (e.getKey() < 0 || e.getValue() < 0 || e.getValue() > 1800) {
					return false;
				}
			}
			return true;
		}
	}

	/** ダイヤ全体の設定。 */
	public static final class Config {
		public int rev;
		/** 同じホームを使う列車どうしの最小間隔(前の列車の発車〜次の列車の到着、秒) */
		public int sep = 40;
		/** 遅れているとき、ドアが開いてから最短で何秒で閉め始めるか */
		public int minOpen = 3;
		/** 自動の待避・抑止で入れてよい最大の停車延長(秒) */
		public int maxHold = 180;
		/** 余裕時分: 駅間ごとに足す秒と、走行時間に対する割合(%)。MTRの実際の走りとの誤差を吸収する */
		public int slackSec = 2;
		public int slackPct = 3;
		public final Map<Long, RouteSetting> routes = new LinkedHashMap<>();
		public final Map<Long, DepotPlan> plans = new LinkedHashMap<>();

		public Config copy() {
			final Config c = new Config();
			c.rev = rev;
			c.sep = sep;
			c.minOpen = minOpen;
			c.maxHold = maxHold;
			c.slackSec = slackSec;
			c.slackPct = slackPct;
			routes.forEach((k, v) -> c.routes.put(k, v.copy()));
			plans.forEach((k, v) -> c.plans.put(k, v.copy()));
			return c;
		}

		public RouteSetting route(long routeId) {
			return routes.get(routeId);
		}

		public boolean isCustom(long routeId) {
			final RouteSetting r = routes.get(routeId);
			return r != null && r.custom;
		}

		/** 車庫に登録された路線のどれかがカスタムなら、その車庫はカスタムダイヤで動かす。 */
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
			if (sep < 0 || sep > 600 || minOpen < 0 || minOpen > 60 || maxHold < 0 || maxHold > 3600 || slackSec < 0 || slackSec > 120 || slackPct < 0 || slackPct > 50) {
				return "全体設定の値が範囲外です";
			}
			if (routes.size() > MAX_ROUTES || plans.size() > MAX_PLANS) {
				return "設定が多すぎます";
			}
			for (final RouteSetting r : routes.values()) {
				if (!r.valid()) {
					return "路線の設定が範囲外です (route " + r.routeId + ")";
				}
			}
			for (final DepotPlan p : plans.values()) {
				if (!p.valid()) {
					return "車庫の時刻表設定が範囲外です (depot " + p.depotId + ")";
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

	/** 時刻表の1便(1本の列車の1行程)。 */
	public static final class Run {
		public long depotId;
		/** 車庫内の通し番号(1始まり、基準駅の発車順) */
		public int no;
		/** 車庫を出る時刻 */
		public int depotDep;
		/** 停車駅ごとの到着・発車時刻(Profile.stops と同じ並び) */
		public int[] arr;
		public int[] dep;
		/** 停車駅ごとの追加停車(手動の待避 + 自動の待避・抑止) */
		public int[] hold;

		public String key() {
			return depotId + "#" + no;
		}
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
			if (h < 0 || h > 47 || m < 0 || m > 59 || s < 0 || s > 59) {
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
