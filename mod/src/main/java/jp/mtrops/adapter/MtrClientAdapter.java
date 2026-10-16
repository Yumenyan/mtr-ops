package jp.mtrops.adapter;

import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;

/**
 * クライアント側のMTR依存部。車両タイプID(MTRの列車ID)から、表示名を引く。
 * 名前は MTR が読み込んだリソースパックの mtr_custom_resources.json の "name" など(TrainClientRegistry に登録済みのもの)。
 */
public final class MtrClientAdapter {

	private MtrClientAdapter() {
	}

	/** 見つからない/空のときは空文字。 */
	public static String typeName(String trainId) {
		if (trainId == null || trainId.isEmpty()) {
			return "";
		}
		try {
			final TrainProperties p = TrainClientRegistry.getTrainProperties(trainId);
			if (p == null || p.name == null) {
				return "";
			}
			return p.name.getString();
		} catch (RuntimeException e) {
			return "";
		}
	}

	// ------------------------------------------------------------------ ScheduleEntry(発車標の到着予定)

	/**
	 * ScheduleEntry や ClientData は、版による違いを吸収するためリフレクションで読む(コンパイル時の依存を持たない)。
	 * 読めなかった値は -1 / null を返す。
	 */
	public static final class EntryData {
		public long routeId = -1;
		public long platformId = -1;
		public long arrivalMillis = Long.MIN_VALUE;
		public int cars = -1;
	}

	private static final java.util.Map<Class<?>, java.util.Map<String, java.lang.reflect.AccessibleObject>> ACCESSORS = new java.util.concurrent.ConcurrentHashMap<>();

	/** フィールド名またはメソッド名のどれか(最初に見つかったもの)で数値を読む。 */
	private static Number readNumber(Object o, String... names) {
		final java.util.Map<String, java.lang.reflect.AccessibleObject> cache = ACCESSORS.computeIfAbsent(o.getClass(), k -> new java.util.concurrent.ConcurrentHashMap<>());
		for (final String name : names) {
			try {
				java.lang.reflect.AccessibleObject a = cache.get(name);
				if (a == null) {
					a = lookup(o.getClass(), name);
					if (a == null) {
						continue;
					}
					cache.put(name, a);
				}
				final Object v = a instanceof java.lang.reflect.Field ? ((java.lang.reflect.Field) a).get(o) : ((java.lang.reflect.Method) a).invoke(o);
				if (v instanceof Number) {
					return (Number) v;
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				// 次の候補へ
			}
		}
		return null;
	}

	private static java.lang.reflect.AccessibleObject lookup(Class<?> c, String name) {
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
			try {
				final java.lang.reflect.Field f = k.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (ReflectiveOperationException | RuntimeException ignored) {
			}
		}
		try {
			final java.lang.reflect.Method m = c.getMethod(name);
			m.setAccessible(true);
			return m;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** ScheduleEntry(または同じ項目を持つオブジェクト)から路線・ホーム・到着時刻・両数を読む。 */
	public static EntryData readEntry(Object entry) {
		final EntryData d = new EntryData();
		if (entry == null) {
			return d;
		}
		Number n = readNumber(entry, "routeId");
		d.routeId = n == null ? -1 : n.longValue();
		n = readNumber(entry, "platformId");
		d.platformId = n == null ? -1 : n.longValue();
		n = readNumber(entry, "arrivalMillis", "getArrivalMillis");
		d.arrivalMillis = n == null ? Long.MIN_VALUE : n.longValue();
		n = readNumber(entry, "trainCars", "getTrainCars", "carCount");
		d.cars = n == null ? -1 : n.intValue();
		return d;
	}

	/** ホームID → 駅ID。クライアントのMTRデータ(ClientData.DATA_CACHE.platformIdToStation)を引く。不明は 0。 */
	public static long stationOfPlatform(long platformId) {
		try {
			final Class<?> cd = Class.forName("mtr.client.ClientData");
			final Object cache = cd.getField("DATA_CACHE").get(null);
			final Object map = cache.getClass().getField("platformIdToStation").get(cache);
			final Object station = ((java.util.Map<?, ?>) map).get(platformId);
			if (station == null) {
				return 0;
			}
			final Number id = readNumber(station, "id");
			return id == null ? 0 : id.longValue();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return 0;
		}
	}

	/** そのホームの到着予定の一覧(ClientData.SCHEDULES_FOR_PLATFORM)。読めなければ null。 */
	public static java.util.List<EntryData> entriesOfPlatform(long platformId) {
		try {
			final Class<?> cd = Class.forName("mtr.client.ClientData");
			final Object map = cd.getField("SCHEDULES_FOR_PLATFORM").get(null);
			final Object coll = ((java.util.Map<?, ?>) map).get(platformId);
			if (!(coll instanceof java.util.Collection)) {
				return null;
			}
			final java.util.List<EntryData> out = new java.util.ArrayList<>();
			for (final Object e : new java.util.ArrayList<>((java.util.Collection<?>) coll)) {
				final EntryData d = readEntry(e);
				if (d.platformId < 0) {
					d.platformId = platformId;
				}
				out.add(d);
			}
			return out;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
