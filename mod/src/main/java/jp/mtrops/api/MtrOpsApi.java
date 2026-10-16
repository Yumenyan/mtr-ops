package jp.mtrops.api;

import jp.mtrops.client.OpsClient;
import jp.mtrops.core.OpsRegistry;
import jp.mtrops.core.Snapshot;
import jp.mtrops.core.TrainView;

import java.util.HashMap;
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
