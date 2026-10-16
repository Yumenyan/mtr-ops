package jp.mtrops.api;

import jp.mtrops.adapter.MtrClientAdapter;
import jp.mtrops.client.OpsClient;
import jp.mtrops.core.EntryMatcher;
import jp.mtrops.core.OpsRegistry;
import jp.mtrops.core.Snapshot;
import jp.mtrops.core.TrainView;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 他のアドオン(NTE/MTRのRhinoスクリプトなど)から車番を読むための公開API。クライアント側専用。
 *
 * スクリプトからの使い方(numberdisp.js の例):
 *   const Api = Packages.jp.mtrops.api.MtrOpsApi;
 *   const number = String(Api.numberOf(train.id()));     // 例 "1001" / 未割当・未受信は ""
 *
 * 引数の列車IDは Object で受ける。スクリプトの train.id() は longだが、Rhinoでは
 * JavaScriptの数値(double)として渡されることがあり、64bit値は下位ビットが丸められる。
 * そのため内部では ID を double に丸めた値で照合する(丸めたIDが衝突する確率は無視できる)。
 *
 * 発車標(ScheduleEntry)から列車を引くメソッド(entry で始まるもの):
 *   const e = arrivals.get(i);                         // ScheduleEntry
 *   Api.entryDelaySec(e)  遅れ(秒。遅れなし/不明は 0)    Api.entryDelayMin(e) 遅れ(分。切り上げ)
 *   Api.entryNumber(e)    編成番号                      Api.entryTypeId(e)  車両ID(mtr_custom_resources.json の id)
 *   Api.entryTypeName(e)  車両名(同 name)               Api.entryTrainId(e) 列車ID(文字列)
 * ScheduleEntry は列車IDを持たないため、同じ路線で、その駅(ホーム)へ向かっている列車を到着の近い順に並べ、
 * 同じホーム・路線のエントリを到着時刻順に並べたときの順位で対応づける。該当する列車が無いときは空文字/0。
 *
 * どのメソッドも例外を投げず、スレッドセーフ(スクリプトは別スレッドで呼ばれることがある)。
 * 値は約0.5秒ごとの同期で更新される。
 */
public final class MtrOpsApi {

	private static final class Index {
		final Snapshot source;
		final Map<Double, TrainView> byId = new HashMap<>();

		Index(Snapshot s) {
			this.source = s;
			for (final TrainView v : s.trains) {
				byId.put((double) v.s.id, v);
			}
		}
	}

	private static final class FormationIndex {
		final OpsRegistry source;
		final Map<String, OpsRegistry.Formation> byId;

		FormationIndex(OpsRegistry r) {
			this.source = r;
			this.byId = r.formationIndex();
		}
	}

	private static volatile Index index;
	private static volatile FormationIndex formationIndex;

	private MtrOpsApi() {
	}

	/** サーバーから最初の列車情報を受信済みか。 */
	public static boolean isReady() {
		return OpsClient.STORE.live() != null;
	}

	/** 編成番号(車番)。未割り当て・不明は空文字。 */
	public static String numberOf(Object trainId) {
		final TrainView v = find(trainId);
		return v == null || v.s.formationId == null ? "" : v.s.formationId;
	}

	/** 編成の系列名(登録した系列。空ならMTRの車両名)。不明は空文字。 */
	public static String seriesOf(Object trainId) {
		final TrainView v = find(trainId);
		return v == null || v.s.series == null ? "" : v.s.series;
	}

	/** 各車両の車番。carIndex は1始まり(先頭車=1)。不明は空文字。 */
	public static String carNumberOf(Object trainId, int carIndex) {
		try {
			final TrainView v = find(trainId);
			if (v == null || v.s.formationId == null) {
				return "";
			}
			final OpsRegistry reg = OpsClient.STORE.registry();
			FormationIndex fi = formationIndex;
			if (fi == null || fi.source != reg) {
				fi = new FormationIndex(reg);
				formationIndex = fi;
			}
			final OpsRegistry.Formation f = fi.byId.get(OpsRegistry.indexKey(v.s.sidingId, v.s.formationId));
			if (f == null) {
				return "";
			}
			final List<String> labels = reg.carLabelsFor(v.s.sidingId, f, v.s.cars);
			return carIndex >= 1 && carIndex <= labels.size() ? labels.get(carIndex - 1) : "";
		} catch (RuntimeException e) {
			return "";
		}
	}


	// ------------------------------------------------------------------ 発車標(ScheduleEntry)

	private static final long SEEN_TTL_NANOS = 2_000_000_000L;
	/** ClientData から到着予定の一覧が読めない版向け: 直近に問い合わせされたエントリの到着時刻(順位の算出に使う) */
	private static final Map<String, Map<Long, Long>> SEEN = new HashMap<>();

	/** 発車標の1エントリに対応する列車。見つからなければ null。 */
	private static TrainView trainOfEntry(Object entry) {
		try {
			final Snapshot snap = OpsClient.STORE.live();
			if (snap == null || entry == null) {
				return null;
			}
			final MtrClientAdapter.EntryData d = MtrClientAdapter.readEntry(entry);
			if (d.routeId < 0 || d.platformId < 0) {
				return null;
			}
			final long station = MtrClientAdapter.stationOfPlatform(d.platformId);
			if (station == 0) {
				return null;
			}
			return EntryMatcher.match(snap, OpsClient.STORE.topology(), d.routeId, station, rankOf(d));
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** 同じホーム・同じ路線のエントリを到着時刻順に並べたときの、このエントリの順位(0始まり)。 */
	private static int rankOf(MtrClientAdapter.EntryData d) {
		if (d.arrivalMillis == Long.MIN_VALUE) {
			return 0;
		}
		final List<MtrClientAdapter.EntryData> all = MtrClientAdapter.entriesOfPlatform(d.platformId);
		if (all != null && !all.isEmpty()) {
			final List<Long> times = new ArrayList<>();
			for (final MtrClientAdapter.EntryData e : all) {
				if (e.routeId == d.routeId && e.arrivalMillis != Long.MIN_VALUE) {
					times.add(e.arrivalMillis);
				}
			}
			times.sort(Comparator.naturalOrder());
			final int i = times.indexOf(d.arrivalMillis);
			if (i >= 0) {
				return i;
			}
		}
		// 一覧が読めない: 直近2秒以内に問い合わせたエントリのうち、より早いものの数(スクリプトが到着順に問い合わせる前提)
		final long now = System.nanoTime();
		synchronized (SEEN) {
			final Map<Long, Long> m = SEEN.computeIfAbsent(d.routeId + ":" + d.platformId, k -> new HashMap<>());
			m.values().removeIf(t -> now - t > SEEN_TTL_NANOS);
			m.put(d.arrivalMillis, now);
			int rank = 0;
			for (final long t : m.keySet()) {
				if (t < d.arrivalMillis) {
					rank++;
				}
			}
			return rank;
		}
	}

	/** 遅れ(秒)。遅れていない・対応する列車なしは 0。早着(負の値)は 0 にする。 */
	public static int entryDelaySec(Object entry) {
		final TrainView v = trainOfEntry(entry);
		return v == null ? 0 : Math.max(0, v.delaySec);
	}

	/** 遅れ(分、切り上げ)。1秒でも遅れていれば 1 以上。 */
	public static int entryDelayMin(Object entry) {
		return (entryDelaySec(entry) + 59) / 60;
	}

	/** 編成番号。無ければ空文字。 */
	public static String entryNumber(Object entry) {
		final TrainView v = trainOfEntry(entry);
		return v == null || v.s.formationId == null ? "" : v.s.formationId;
	}

	/** 車両ID(mtr_custom_resources.json の id。例 mtr_custom_train_xxx)。無ければ空文字。 */
	public static String entryTypeId(Object entry) {
		final TrainView v = trainOfEntry(entry);
		return v == null || v.s.trainType == null ? "" : v.s.trainType;
	}

	/** 車両名(mtr_custom_resources.json の name)。 */
	public static String entryTypeName(Object entry) {
		final String id = entryTypeId(entry);
		return id.isEmpty() ? "" : MtrClientAdapter.typeName(id);
	}

	/** 列車ID(64bitなので文字列)。無ければ空文字。 */
	public static String entryTrainId(Object entry) {
		final TrainView v = trainOfEntry(entry);
		return v == null ? "" : Long.toString(v.s.id);
	}

	/** 編成の系列名(登録した系列。空なら車両名)。 */
	public static String entrySeries(Object entry) {
		final TrainView v = trainOfEntry(entry);
		if (v == null) {
			return "";
		}
		if (v.s.series != null && !v.s.series.isEmpty()) {
			return v.s.series;
		}
		return v.s.trainType == null ? "" : MtrClientAdapter.typeName(v.s.trainType);
	}

	private static TrainView find(Object trainId) {
		try {
			if (!(trainId instanceof Number)) {
				return null;
			}
			final Snapshot snap = OpsClient.STORE.live();
			if (snap == null) {
				return null;
			}
			Index idx = index;
			if (idx == null || idx.source != snap) {
				idx = new Index(snap);
				index = idx;
			}
			return idx.byId.get(((Number) trainId).doubleValue());
		} catch (RuntimeException e) {
			return null;
		}
	}
}
