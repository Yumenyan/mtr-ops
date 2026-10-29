package jp.mtrops.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 駅間(区間)ごとの線路の線形と実距離。MTRの列車の経路(PathData)から、サーバーが実際に走った経路を元に作る。
 * キーは「発駅ID → 着駅ID」の向き付き。列車が一度も走っていない区間は載らない(APIでは直線距離で補う)。
 */
public final class Geometry {

	public static final class Section {
		public long fromStationId;
		public long toStationId;
		/** 線路に沿った距離(ブロック) */
		public double distance;
		/** 折れ線の頂点 [x, y, z, x, y, z, ...](ブロック座標) */
		public int[] points = new int[0];
		/** 乗り物の種類(MTRの TransportMode を小文字に: train / boat / cable_car / airplane) */
		public String mode = "";
		/**
		 * レールの種別ごとの区切り(区間の始点からの距離)。MTRの RailType そのまま(WOODEN〜DIAMOND, PLATFORM, SIDING, TURN_BACK, CABLE_CAR, RUNWAY など)。
		 * 同じ種別が続くところは1つにまとめる。
		 */
		public String[] partTypes = new String[0];
		public float[] partFrom = new float[0];
		public float[] partTo = new float[0];

		public int pointCount() {
			return points.length / 3;
		}
	}

	public int rev;
	public final List<Section> sections = new ArrayList<>();
	private Map<String, Section> exactIndex;

	/** 発駅→着駅の区間。無ければ null。 */
	public synchronized Section find(long from, long to) {
		if (exactIndex == null) {
			final Map<String, Section> exact = new HashMap<>();
			for (final Section s : sections) {
				exact.put(s.fromStationId + ">" + s.toStationId, s);
			}
			exactIndex = exact;
		}
		return exactIndex.get(from + ">" + to);
	}

	/** 同じ区間の逆方向。無ければ null。 */
	public Section findEither(long a, long b) {
		final Section s = find(a, b);
		return s != null ? s : find(b, a);
	}
}
