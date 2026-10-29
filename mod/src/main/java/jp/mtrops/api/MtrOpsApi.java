package jp.mtrops.api;

import jp.mtrops.adapter.MtrClientAdapter;
import jp.mtrops.client.OpsClient;
import jp.mtrops.core.EntryMatcher;
import jp.mtrops.core.EtaCodec;
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
 *   Api.entryExpectedMillis(e) 到着見込み(UNIXミリ秒、mtrops の補正済み)
 * どれも第2引数にホームIDを渡せる(例 Api.entryNumber(e, platformId))。省略するとMTRの到着予定一覧から探す。
 * ScheduleEntry は列車IDを持たないため、そのホームへの到着予想が早い順に列車を並べ、
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

	private static final class Cand {
		final TrainView view;
		final int ticks;

		Cand(TrainView view, int ticks) {
			this.view = view;
			this.ticks = ticks;
		}
	}

	private static long platformArg(Object platformId) {
		return platformId instanceof Number ? (long) ((Number) platformId).doubleValue() : -1;
	}

	/**
	 * 発車標の1エントリに対応する列車。見つからなければ null。
	 * 同じホーム・同じ路線のエントリを到着時刻順に並べた順位 n と、そのホームへの到着予想が早い順の n 番目の列車を対応づける。
	 * 到着予想(サーバーから約1秒ごと)が無いときは、路線上の位置から並べる(EntryMatcher)。
	 */
	private static TrainView trainOfEntry(Object entry, Object platformId) {
		try {
			final Snapshot snap = OpsClient.STORE.live();
			if (snap == null || entry == null) {
				return null;
			}
			final MtrClientAdapter.EntryData d = MtrClientAdapter.readEntry(entry, platformArg(platformId));
			if (d.routeId < 0 || d.platformId <= 0) {
				return null;
			}
			final int rank = rankOf(d);
			final List<Cand> cands = new ArrayList<>();
			for (final TrainView v : snap.trains) {
				if (!v.s.onRoute || v.s.routeId != d.routeId && !hasRouteAt(v.s.id, d.platformId, d.routeId)) {
					continue;
				}
				for (final EtaCodec.Entry e : OpsClient.STORE.etasOf(v.s.id)) {
					if (e.platformId == d.platformId && (e.routeId == 0 || e.routeId == d.routeId)) {
						cands.add(new Cand(v, e.ticks));
						break;
					}
				}
			}
			if (!cands.isEmpty()) {
				cands.sort(Comparator.comparingInt(c -> c.ticks));
				return rank < cands.size() ? cands.get(rank).view : null;
			}
			final long station = MtrClientAdapter.stationOfPlatform(d.platformId);
			return station == 0 ? null : EntryMatcher.match(snap, OpsClient.STORE.topology(), d.routeId, station, rank);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static boolean hasRouteAt(long trainId, long platformId, long routeId) {
		for (final EtaCodec.Entry e : OpsClient.STORE.etasOf(trainId)) {
			if (e.platformId == platformId && e.routeId == routeId) {
				return true;
			}
		}
		return false;
	}

	/** 同じホーム・同じ路線のエントリを到着時刻順に並べたときの、このエントリの順位(0始まり)。 */
	private static int rankOf(MtrClientAdapter.EntryData d) {
		if (d.arrivalMillis == Long.MIN_VALUE) {
			return 0;
		}
		final List<Long> times = new ArrayList<>();
		for (final MtrClientAdapter.EntryData e : MtrClientAdapter.entriesOfPlatform(d.platformId)) {
			if (e.routeId == d.routeId) {
				times.add(e.arrivalMillis);
			}
		}
		times.sort(Comparator.naturalOrder());
		final int i = times.indexOf(d.arrivalMillis);
		return Math.max(0, i);
	}

	private static EtaCodec.Entry etaOf(TrainView v, MtrClientAdapter.EntryData d) {
		for (final EtaCodec.Entry e : OpsClient.STORE.etasOf(v.s.id)) {
			if (e.platformId == d.platformId) {
				return e;
			}
		}
		return null;
	}

	/** 遅れ(秒)。遅れていない・対応する列車なしは 0。早着(負の値)は 0 にする。 */
	public static int entryDelaySec(Object entry) {
		return entryDelaySec(entry, null);
	}

	public static int entryDelaySec(Object entry, Object platformId) {
		final TrainView v = trainOfEntry(entry, platformId);
		return v == null ? 0 : Math.max(0, v.delaySec);
	}

	/** 遅れ(分、切り上げ)。1秒でも遅れていれば 1 以上。 */
	public static int entryDelayMin(Object entry) {
		return (entryDelaySec(entry) + 59) / 60;
	}

	public static int entryDelayMin(Object entry, Object platformId) {
		return (entryDelaySec(entry, platformId) + 59) / 60;
	}

	/** 編成番号。無ければ空文字。 */
	public static String entryNumber(Object entry) {
		return entryNumber(entry, null);
	}

	public static String entryNumber(Object entry, Object platformId) {
		final TrainView v = trainOfEntry(entry, platformId);
		return v == null || v.s.formationId == null ? "" : v.s.formationId;
	}

	/** 車両ID(mtr_custom_resources.json の id。例 mtr_custom_train_xxx)。無ければ空文字。 */
	public static String entryTypeId(Object entry) {
		return entryTypeId(entry, null);
	}

	public static String entryTypeId(Object entry, Object platformId) {
		final TrainView v = trainOfEntry(entry, platformId);
		return v == null || v.s.trainType == null ? "" : v.s.trainType;
	}

	/** 車両名(mtr_custom_resources.json の name)。 */
	public static String entryTypeName(Object entry) {
		return entryTypeName(entry, null);
	}

	public static String entryTypeName(Object entry, Object platformId) {
		final String id = entryTypeId(entry, platformId);
		return id.isEmpty() ? "" : MtrClientAdapter.typeName(id);
	}

	/** 列車ID(64bitなので文字列)。無ければ空文字。 */
	public static String entryTrainId(Object entry) {
		return entryTrainId(entry, null);
	}

	public static String entryTrainId(Object entry, Object platformId) {
		final TrainView v = trainOfEntry(entry, platformId);
		return v == null ? "" : Long.toString(v.s.id);
	}

	/** 編成の系列名(登録した系列。空なら車両名)。 */
	public static String entrySeries(Object entry) {
		return entrySeries(entry, null);
	}

	public static String entrySeries(Object entry, Object platformId) {
		final TrainView v = trainOfEntry(entry, platformId);
		if (v == null) {
			return "";
		}
		if (v.s.series != null && !v.s.series.isEmpty()) {
			return v.s.series;
		}
		return v.s.trainType == null ? "" : MtrClientAdapter.typeName(v.s.trainType);
	}

	/**
	 * そのホームへの到着見込み(UNIXミリ秒、mtrops の補正済み。カスタムダイヤは時刻表の待ち合わせも反映)。
	 * 対応する列車が無い(まだ出庫していない等)ときは、エントリの到着時刻(MTRの計算)をそのまま返す。
	 */
	public static double entryExpectedMillis(Object entry) {
		return entryExpectedMillis(entry, null);
	}

	public static double entryExpectedMillis(Object entry, Object platformId) {
		final MtrClientAdapter.EntryData d = MtrClientAdapter.readEntry(entry, platformArg(platformId));
		final TrainView v = trainOfEntry(entry, platformId);
		final EtaCodec.Entry e = v == null ? null : etaOf(v, d);
		if (e == null) {
			return d.arrivalMillis == Long.MIN_VALUE ? 0 : d.arrivalMillis;
		}
		return OpsClient.STORE.etas().receivedMillis + e.ticks * 50.0;
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
