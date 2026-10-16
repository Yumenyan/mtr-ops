package jp.mtrops.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 車庫・編成・車番の台帳(サーバー側が正本)。
 *
 * 構造:  車庫(Depot) ─ 車庫線(Siding, MTRのID) ─ 編成(Formation: 1001F など)
 * 車庫線と編成は別データ。1本の車庫線に複数の編成を登録でき、出庫順に割り当てる(FormationAssigner)。
 *
 * 更新は「車庫単位」。各車庫は rev(版番号)を持ち、更新時に baseRev が一致しなければ CONFLICT を返す。
 * これにより、別々の車庫を同時に編集しても互いを上書きしない。
 */
public final class OpsRegistry {

	public static final int MAX_FORMATIONS_PER_DEPOT = 500;
	public static final int MAX_ID_LENGTH = 16;
	public static final int MAX_CAR_NUMBER_LENGTH = 24;

	public static final class Formation {
		public String id = "";
		public String series = "";
		/** 両数。0 = 実際の列車の両数に合わせる。 */
		public int cars;
		/** 各車両の車番(任意)。空なら「編成ID-N」を自動生成。 */
		public List<String> carNumbers = new ArrayList<>();

		public Formation copy() {
			final Formation f = new Formation();
			f.id = id;
			f.series = series;
			f.cars = cars;
			f.carNumbers = new ArrayList<>(carNumbers);
			return f;
		}
	}

	/**
	 * 車庫線ごとの車番範囲。編成番号は  prefix + 数字(width桁にゼロ埋め、0なら埋めない) + suffix  で、
	 * 数字が start〜end の範囲に入るものだけを許す。例: prefix="", start=1001, end=1100, suffix="F" → 1001F〜1100F
	 */
	public static final class NumberRange {
		public String prefix = "";
		public String suffix = "";
		public int start;
		public int end;
		public int width;
		/** true: 出庫時に範囲内の空き番号からランダムに採番する(既定は若い番号から) */
		public boolean random;
		/** 各車両の番号の付け方: 0=編成番号-N(従来) 1=連番(両数ごとに区切る) 2=桁置換(号車ごとにある桁を変える) */
		public int carScheme;
		/** 桁置換で変える桁(1=一の位 2=十の位 3=百の位 4=千の位) */
		public int digitPos = 3;

		public NumberRange copy() {
			final NumberRange r = new NumberRange();
			r.prefix = prefix;
			r.suffix = suffix;
			r.start = start;
			r.end = end;
			r.width = width;
			r.random = random;
			r.carScheme = carScheme;
			r.digitPos = digitPos;
			return r;
		}

		public boolean valid() {
			return start >= 0 && end >= start && end - start < 5000 && width >= 0 && width <= 8
					&& carScheme >= 0 && carScheme <= 2 && digitPos >= 1 && digitPos <= 8 && prefix.length() <= 12 && suffix.length() <= 12 && !hasControlChars(prefix) && !hasControlChars(suffix);
		}

		public String format(int n) {
			final String digits = width > 0 ? String.format("%0" + width + "d", n) : Integer.toString(n);
			return prefix + digits + suffix;
		}

		/** 編成IDが範囲内の番号なら、その数値。範囲外・形式違いは -1。 */
		public int parse(String id) {
			if (id == null || !id.startsWith(prefix) || !id.endsWith(suffix) || id.length() <= prefix.length() + suffix.length()) {
				return -1;
			}
			final String mid = id.substring(prefix.length(), id.length() - suffix.length());
			if (mid.length() > 9) {
				return -1;
			}
			for (int i = 0; i < mid.length(); i++) {
				if (mid.charAt(i) < '0' || mid.charAt(i) > '9') {
					return -1;
				}
			}
			final int n = Integer.parseInt(mid);
			return n >= start && n <= end ? n : -1;
		}

		/** "モハE233-1001" → [前の文字, 数字, 後ろの文字]。数字が無ければ null。 */
		public static String[] split(String id) {
			if (id == null) {
				return null;
			}
			int end = id.length();
			while (end > 0 && !Character.isDigit(id.charAt(end - 1))) {
				end--;
			}
			int begin = end;
			while (begin > 0 && Character.isDigit(id.charAt(begin - 1))) {
				begin--;
			}
			if (begin == end || end - begin > 9) {
				return null;
			}
			return new String[]{id.substring(0, begin), id.substring(begin, end), id.substring(end)};
		}

		/**
		 * 号車(1始まり)ごとの車両番号。編成番号の文字(モハE233- など)はそのまま前後に付ける。
		 * 計算できない(方式なし・数字なし)場合は null。
		 */
		public String carNumber(String formationId, int carIndex, int cars) {
			if (carScheme == 0 || carIndex < 1 || cars < 1) {
				return null;
			}
			final String[] parts = split(formationId);
			if (parts == null) {
				return null;
			}
			final int n = Integer.parseInt(parts[1]);
			final int digits = parts[1].length();
			if (carScheme == 1) {
				final long v = (long) start + (long) (n - start) * cars + (carIndex - 1);
				if (v < 0) {
					return null;
				}
				final String base = Long.toString(v);
				final int w = Math.max(width, parts[1].startsWith("0") ? digits : 0);
				return parts[0] + (w > base.length() ? "0".repeat(w - base.length()) + base : base) + parts[2];
			}
			// 桁置換: digitPos 桁目を号車の数字(cars<=10 なら 号車 mod 両数、それ以外は 号車 mod 10)にする
			final int pos = Math.max(1, Math.min(8, digitPos));
			final int d = cars <= 10 ? carIndex % cars : carIndex % 10;
			final StringBuilder sb = new StringBuilder(parts[1]);
			while (sb.length() < pos) {
				sb.insert(0, '0');
			}
			sb.setCharAt(sb.length() - pos, (char) ('0' + d));
			return parts[0] + sb + parts[2];
		}

		public String describe() {
			return format(start) + " 〜 " + format(end);
		}
	}

	public static final class DepotRecord {
		public long depotId;
		public String dimension = "";
		public int rev;
		/** この車庫で車番に使わない数(例 "4, 9, 1013-1019")。自動採番と「追加」が飛ばす。 */
		public String exclude = "";
		/** 車庫線ID → 車番範囲(未設定の車庫線は自動採番しない) */
		public final Map<Long, NumberRange> ranges = new LinkedHashMap<>();
		/** 車庫線ID → 出庫順の編成リスト */
		public final Map<Long, List<Formation>> sidings = new LinkedHashMap<>();

		public DepotRecord copy() {
			final DepotRecord d = new DepotRecord();
			d.depotId = depotId;
			d.dimension = dimension;
			d.rev = rev;
			d.exclude = exclude;
			sidings.forEach((sid, list) -> {
				final List<Formation> copy = new ArrayList<>();
				list.forEach(f -> copy.add(f.copy()));
				d.sidings.put(sid, copy);
			});
			ranges.forEach((sid, r) -> d.ranges.put(sid, r.copy()));
			return d;
		}

		public int formationCount() {
			int n = 0;
			for (final List<Formation> l : sidings.values()) {
				n += l.size();
			}
			return n;
		}
	}

	public static final class Assign {
		public long sidingId;
		public String formationId = "";

		public Assign(long sidingId, String formationId) {
			this.sidingId = sidingId;
			this.formationId = formationId;
		}
	}

	/** 除外リスト "4, 9, 1013-1019" を [from,to] の配列にする。読めない部分は null を返す(全体が不正)。 */
	public static int[][] parseExclude(String text) {
		final List<int[]> out = new ArrayList<>();
		if (text == null) {
			return new int[0][];
		}
		for (final String part : text.replace('、', ',').replace('，', ',').replace('〜', '-').replace('~', '-').split("[,\\s]+")) {
			if (part.isEmpty()) {
				continue;
			}
			final String[] ab = part.split("-", -1);
			try {
				if (ab.length == 1) {
					final int v = Integer.parseInt(ab[0]);
					out.add(new int[]{v, v});
				} else if (ab.length == 2 && !ab[0].isEmpty() && !ab[1].isEmpty()) {
					final int a = Integer.parseInt(ab[0]);
					final int b = Integer.parseInt(ab[1]);
					out.add(new int[]{Math.min(a, b), Math.max(a, b)});
				} else {
					return null;
				}
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return out.toArray(new int[0][]);
	}

	public static boolean isExcluded(int[][] ex, int n) {
		if (ex != null) {
			for (final int[] r : ex) {
				if (n >= r[0] && n <= r[1]) {
					return true;
				}
			}
		}
		return false;
	}

	public enum Result {OK, CONFLICT, DUPLICATE, INVALID, TOO_MANY}

	public final Map<Long, DepotRecord> depots = new LinkedHashMap<>();
	/** MTR列車ID → 割り当て済みの編成。ワールドに保存し、再起動後も同じ列車が同じ編成番号を保つ。 */
	public final Map<Long, Assign> assignments = new HashMap<>();

	/** 自動採番で飛ばす数字の末尾3桁(設定 skipNumberEndings)。手動で付ける番号には適用しない。 */
	public Set<String> skipEndings = new HashSet<>();

	/** 車庫線ID → 車種ID(mtr_custom_resources の車両ID)。同じ車番でも車種が違えば別物として扱う。 */
	public volatile Map<Long, String> sidingTypes = new HashMap<>();

	public void setTypes(Topology t) {
		if (t == null) {
			return;
		}
		final Map<Long, String> m = new HashMap<>();
		for (final Topology.Siding sd : t.sidings) {
			m.put(sd.id, sd.trainType == null ? "" : sd.trainType);
		}
		sidingTypes = m;
	}

	private String typeOf(long sidingId) {
		final String t = sidingTypes.get(sidingId);
		return t == null ? "" : t;
	}

	/** 一意性の鍵: 車種ID + 編成番号 */
	private String uniqueKey(long sidingId, String id) {
		return typeOf(sidingId) + "\u0001" + id;
	}

	/** 台帳内の編成を引く鍵(車庫線ID + 編成番号) */
	public static String indexKey(long sidingId, String id) {
		return sidingId + ":" + id;
	}

	/** applyDepotUpdate が失敗したときの理由(ログ・GUI表示用)。 */
	public String lastError = "";

	public Result applyDepotUpdate(DepotRecord incoming, int baseRev, Set<Long> validSidingIds) {
		final DepotRecord existing = depots.get(incoming.depotId);
		final int curRev = existing == null ? 0 : existing.rev;
		if (baseRev != curRev) {
			lastError = "他のプレイヤーが先にこの車庫を更新しました(rev " + curRev + ")";
			return Result.CONFLICT;
		}

		final DepotRecord next = incoming.copy();
		if (validSidingIds != null) {
			next.sidings.keySet().removeIf(sid -> !validSidingIds.contains(sid));
			next.ranges.keySet().removeIf(sid -> !validSidingIds.contains(sid));
		}
		next.exclude = next.exclude == null ? "" : next.exclude.trim();
		if (next.exclude.length() > 120 || parseExclude(next.exclude) == null || hasControlChars(next.exclude)) {
			lastError = "除外する車番の書式が不正です(例: 4, 9, 1013-1019)";
			return Result.INVALID;
		}
		for (final NumberRange r : next.ranges.values()) {
			r.prefix = r.prefix == null ? "" : r.prefix.trim();
			r.suffix = r.suffix == null ? "" : r.suffix.trim();
			if (!r.valid()) {
				lastError = "車番範囲が不正です(開始≦終了、最大5000件、前後の文字は8文字まで)";
				return Result.INVALID;
			}
		}
		if (next.formationCount() > MAX_FORMATIONS_PER_DEPOT) {
			lastError = "1車庫あたりの編成は " + MAX_FORMATIONS_PER_DEPOT + " までです";
			return Result.TOO_MANY;
		}

		final Set<String> mine = new HashSet<>();
		for (final Map.Entry<Long, List<Formation>> entry : next.sidings.entrySet()) {
			final NumberRange range = next.ranges.get(entry.getKey());
			for (final Formation f : entry.getValue()) {
				f.id = f.id == null ? "" : f.id.trim();
				f.series = f.series == null ? "" : f.series.trim();
				if (f.id.isEmpty() || f.id.length() > MAX_ID_LENGTH || hasControlChars(f.id) || f.series.length() > MAX_ID_LENGTH * 2) {
					lastError = "編成IDが不正です: '" + f.id + "'";
					return Result.INVALID;
				}
				if (range != null && range.parse(f.id) < 0) {
					lastError = "編成番号 '" + f.id + "' はこの車庫線の車番範囲(" + range.describe() + ")外です";
					return Result.INVALID;
				}
				f.cars = Math.max(0, Math.min(32, f.cars));
				final List<String> cleaned = new ArrayList<>();
				for (final String n : f.carNumbers) {
					if (n != null && !n.trim().isEmpty() && n.length() <= MAX_CAR_NUMBER_LENGTH && !hasControlChars(n)) {
						cleaned.add(n.trim());
					}
				}
				f.carNumbers = cleaned.size() > 32 ? new ArrayList<>(cleaned.subList(0, 32)) : cleaned;
				if (!mine.add(uniqueKey(entry.getKey(), f.id))) {
					lastError = "車番 '" + f.id + "' が同じ車種で重複しています";
					return Result.DUPLICATE;
				}
			}
		}
		for (final DepotRecord other : depots.values()) {
			if (other.depotId == next.depotId) {
				continue;
			}
			for (final Map.Entry<Long, List<Formation>> oe : other.sidings.entrySet()) {
				for (final Formation f : oe.getValue()) {
					if (mine.contains(uniqueKey(oe.getKey(), f.id))) {
						lastError = "車番 '" + f.id + "' は同じ車種の他の車庫で使われています";
						return Result.DUPLICATE;
					}
				}
			}
		}

		next.rev = curRev + 1;
		depots.put(next.depotId, next);
		return Result.OK;
	}

	/**
	 * 表示される番号の集合(同じ車種で重複させない対象)。
	 * 連番/桁置換: 各車の車番。それ以外: 編成番号と(登録済みなら)各車の車番。
	 */
	private Set<String> shownNumbers(NumberRange range, Formation f, int cars) {
		final Set<String> out = new HashSet<>();
		if (range != null && range.carScheme != 0 && f.carNumbers.isEmpty()) {
			final int n = f.cars > 0 ? f.cars : Math.max(0, cars);
			for (int i = 1; i <= n; i++) {
				final String made = range.carNumber(f.id, i, n);
				if (made != null) {
					out.add(made);
				}
			}
			if (!out.isEmpty()) {
				return out;
			}
		}
		out.add(f.id);
		out.addAll(f.carNumbers);
		return out;
	}

	/** 同じ車種の登録済み編成が既に使っている表示番号。 */
	private Set<String> occupiedShown(long sidingId) {
		final String type = typeOf(sidingId);
		final Set<String> out = new HashSet<>();
		for (final DepotRecord d : depots.values()) {
			for (final Map.Entry<Long, List<Formation>> e : d.sidings.entrySet()) {
				if (!typeOf(e.getKey()).equals(type)) {
					continue;
				}
				final NumberRange r = d.ranges.get(e.getKey());
				for (final Formation f : e.getValue()) {
					out.addAll(shownNumbers(r, f, f.cars));
				}
			}
		}
		return out;
	}

	/** 新しい編成 id (cars 両) を登録すると、同じ車種の他編成の番号と被るか。 */
	private boolean collidesShown(long sidingId, NumberRange range, String id, int cars, Set<String> occupied) {
		final Formation probe = new Formation();
		probe.id = id;
		probe.cars = Math.max(0, Math.min(32, cars));
		for (final String n : shownNumbers(range, probe, cars)) {
			if (occupied.contains(n)) {
				return true;
			}
		}
		return false;
	}

	/** 全車庫で使用中の編成ID。 */
	public Set<String> allFormationIds() {
		final Set<String> ids = new HashSet<>();
		for (final DepotRecord d : depots.values()) {
			for (final Map.Entry<Long, List<Formation>> e : d.sidings.entrySet()) {
				for (final Formation f : e.getValue()) {
					ids.add(uniqueKey(e.getKey(), f.id));
				}
			}
		}
		return ids;
	}

	/**
	 * 車番範囲が設定された車庫線に、空いている最小の番号で編成を自動登録する。範囲が無い/満杯なら null。
	 * 台帳が変わるので車庫の rev を進める(編集中のプレイヤーには競合として通知される)。
	 */
	public final java.util.Random random = new java.util.Random();

	/**
	 * ランダム採番: 範囲内で「使用中でも他で登録済みでもない」番号を無作為に選ぶ。
	 * この車庫線に登録済みで空いている編成が当たればそれを返し、未登録の番号なら新規登録して返す。範囲が満杯なら null。
	 * @param inUse この車庫線で現在列車に割り当て済みの編成ID
	 */
	public Formation pickRandom(long depotId, long sidingId, int cars, Set<String> inUse) {
		final DepotRecord rec = depots.get(depotId);
		if (rec == null) {
			return null;
		}
		final NumberRange range = rec.ranges.get(sidingId);
		if (range == null || !range.valid()) {
			return null;
		}
		final List<Formation> mine = rec.sidings.get(sidingId);
		final Map<String, Formation> own = new HashMap<>();
		final Set<String> ownKeys = new HashSet<>();
		if (mine != null) {
			for (final Formation f : mine) {
				own.put(f.id, f);
				ownKeys.add(uniqueKey(sidingId, f.id));
			}
		}
		final Set<String> blocked = allFormationIds();
		blocked.removeAll(ownKeys);
		final int[][] ex = parseExclude(rec.exclude);
		final Set<String> occupied = occupiedShown(sidingId);
		final List<Integer> free = new ArrayList<>();
		for (int n = range.start; n <= range.end; n++) {
			final String id = range.format(n);
			if (id.length() <= MAX_ID_LENGTH && !inUse.contains(id) && !blocked.contains(uniqueKey(sidingId, id)) && !isSkipped(n) && !isExcluded(ex, n)) {
				if (own.containsKey(id)) {
					free.add(n);
				} else if (rec.formationCount() < MAX_FORMATIONS_PER_DEPOT && !collidesShown(sidingId, range, id, cars, occupied)) {
					free.add(n);
				}
			}
		}
		if (free.isEmpty()) {
			return null;
		}
		final String id = range.format(free.get(random.nextInt(free.size())));
		final Formation have = own.get(id);
		if (have != null) {
			return have;
		}
		final Formation f = new Formation();
		f.id = id;
		f.cars = Math.max(0, Math.min(32, cars));
		rec.sidings.computeIfAbsent(sidingId, k -> new ArrayList<>()).add(f);
		rec.rev++;
		return f;
	}

	public Formation autoRegister(long depotId, long sidingId, int cars) {
		final DepotRecord rec = depots.get(depotId);
		if (rec == null) {
			return null;
		}
		final NumberRange range = rec.ranges.get(sidingId);
		if (range == null || !range.valid() || rec.formationCount() >= MAX_FORMATIONS_PER_DEPOT) {
			return null;
		}
		final Set<String> used = allFormationIds();
		final Set<String> occupied = occupiedShown(sidingId);
		final int[][] ex = parseExclude(rec.exclude);
		for (int n = range.start; n <= range.end; n++) {
			final String id = range.format(n);
			if (id.length() <= MAX_ID_LENGTH && !used.contains(uniqueKey(sidingId, id)) && !isSkipped(n) && !isExcluded(ex, n)
					&& !collidesShown(sidingId, range, id, cars, occupied)) {
				final Formation f = new Formation();
				f.id = id;
				f.cars = Math.max(0, Math.min(32, cars));
				rec.sidings.computeIfAbsent(sidingId, k -> new ArrayList<>()).add(f);
				rec.rev++;
				return f;
			}
		}
		return null;
	}

	private boolean isSkipped(int n) {
		if (skipEndings.isEmpty()) {
			return false;
		}
		final String digits = Integer.toString(n);
		return digits.length() >= 3 && skipEndings.contains(digits.substring(digits.length() - 3));
	}

	public boolean removeDepot(long depotId) {
		return depots.remove(depotId) != null;
	}

	public Map<String, Formation> formationIndex() {
		final Map<String, Formation> index = new HashMap<>();
		for (final DepotRecord d : depots.values()) {
			for (final Map.Entry<Long, List<Formation>> e : d.sidings.entrySet()) {
				for (final Formation f : e.getValue()) {
					index.put(indexKey(e.getKey(), f.id), f);
				}
			}
		}
		return index;
	}

	public List<Formation> formationsOf(long depotId, long sidingId) {
		final DepotRecord d = depots.get(depotId);
		if (d != null) {
			final List<Formation> l = d.sidings.get(sidingId);
			if (l != null) {
				return l;
			}
		}
		for (final DepotRecord other : depots.values()) {
			final List<Formation> l = other.sidings.get(sidingId);
			if (l != null) {
				return l;
			}
		}
		return null;
	}

	/** 車番(「1001-3」など)の一覧。登録がなければ自動生成。範囲の方式があればそれに従う。 */
	public static List<String> carLabels(Formation f, int carsHint) {
		return carLabels(f, carsHint, null);
	}

	public static List<String> carLabels(Formation f, int carsHint, NumberRange range) {
		final List<String> out = new ArrayList<>();
		if (!f.carNumbers.isEmpty()) {
			out.addAll(f.carNumbers);
			return out;
		}
		final int n = f.cars > 0 ? f.cars : Math.max(0, carsHint);
		for (int i = 1; i <= n; i++) {
			final String made = range == null ? null : range.carNumber(f.id, i, n);
			out.add(made != null ? made : f.id + "-" + i);
		}
		return out;
	}

	public NumberRange rangeOfSiding(long sidingId) {
		for (final DepotRecord d : depots.values()) {
			final NumberRange r = d.ranges.get(sidingId);
			if (r != null) {
				return r;
			}
		}
		return null;
	}

	public List<String> carLabelsFor(long sidingId, Formation f, int carsHint) {
		return carLabels(f, carsHint, rangeOfSiding(sidingId));
	}

	/** 編成の表示名。first: 1号車の車番(連番/桁置換のとき)。無ければ編成番号。 */
	public static String formationTitle(String formationId, String firstCar) {
		return firstCar != null && !firstCar.isEmpty() ? firstCar : formationId;
	}

	public String formationTitleFor(long sidingId, Formation f, int carsHint) {
		final NumberRange r = rangeOfSiding(sidingId);
		if (r != null && r.carScheme != 0 && f.carNumbers.isEmpty()) {
			final List<String> labels = carLabels(f, carsHint, r);
			if (!labels.isEmpty()) {
				return formationTitle(f.id, labels.get(0));
			}
		}
		if (!f.carNumbers.isEmpty()) {
			return formationTitle(f.id, null);
		}
		return formationTitle(f.id, null);
	}

	static boolean hasControlChars(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (Character.isISOControl(s.charAt(i))) {
				return true;
			}
		}
		return false;
	}
}
