package jp.mtrops.core;

/**
 * Adapter層(MTR依存)がサンプリング時点で詰める「生の列車情報」。
 * このクラスはMinecraft/MTRに依存しないので、core は単体でテストできる。
 * 値が取れなかったものは NaN / 0 / -1 のままにしておく。
 */
public final class TrainSample {

	public long id;
	public long sidingId;
	public long depotId;
	public long routeId;
	public String dimension = "";
	public String trainType = "";

	public double x, y, z;
	public float yaw;
	/** m/s */
	public float speedMps;

	public boolean onRoute;
	public boolean manual;
	public boolean doorOpen;
	/** 停車位置(ホーム)に止まっている */
	public boolean atStop;
	/** 停車位置にいて、規定の停車時間がまだ残っている(=正常な停車) */
	public boolean dwelling;

	public double railProgress;
	public double pathLength;

	public long prevStationId, curStationId, nextStationId, destStationId;

	public int cars;
	public int spacing = 20;
	public int passengers;
	/** 停車時間の残り(tick)。不明は -1 */
	public int dwellRemainTicks = -1;

	/** ダイヤ上の「現在位置を通過するはずの時刻」(発車からのtick)。不明は NaN */
	public double idealTicks = Double.NaN;
	/** ダイヤ上の「次の停車駅への到着時刻」(発車からのtick)。不明は NaN */
	public double idealNextArrivalTicks = Double.NaN;
	/** 発車からの実経過tick。不明は NaN */
	public double elapsedTicks = Double.NaN;

	/** 編成割り当て(FormationAssignerが設定) */
	/** ゴースト疑い(ビット): 1=停止固着(nextStoppingIndex>=経路長でMTRが毎tick何もしない) 2=経路が空/距離表が不足で位置を算出できない */
	public int suspect;
	/** ロープウェイ(CABLE_CAR)。運行情報との不一致判定の対象外。通信には載せない。 */
	public boolean cableCar;
	public static final int SUSPECT_FROZEN = 1;
	public static final int SUSPECT_NOPATH = 2;

	/** これから着く停車駅と、そこへの到着までの残り(tick)。ダイヤ上の時間なので、遅れていても timetable 通りの走行を前提にした見込み。 */
	public long[] etaStationIds = new long[0];
	public int[] etaTicks = new int[0];
	public static final int MAX_ETAS = 24;

	public String formationId;
	public String series;

	/** 各車両の位置(任意)。 */
	public float[] carX, carZ, carYaw;

	public String label() {
		if (formationId != null && !formationId.isEmpty()) {
			return formationId;
		}
		return "列車#" + Long.toHexString(id & 0xFFFFFFL);
	}
}
