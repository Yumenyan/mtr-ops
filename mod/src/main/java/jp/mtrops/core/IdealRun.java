package jp.mtrops.core;

import java.util.ArrayList;
import java.util.List;

/** 車庫から出た列車の理想の走行(MTRの走行時間計算と同じ)。MC/MTRに依存しない。時刻表API・駅間の所要時間APIの元になる。 */
public final class IdealRun {

	private IdealRun() {
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
}
