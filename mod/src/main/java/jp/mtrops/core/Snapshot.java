package jp.mtrops.core;

import java.util.ArrayList;
import java.util.List;

/** ある瞬間の運行状態(全列車 + インシデント)。 */
public final class Snapshot {

	public int seq;
	/** 詳細レベル 0..4 (SnapshotCodec の優先度表を参照) */
	public int level;
	public long gameTick;
	/** ワールドの時刻(0..23999) */
	public int dayTime;
	public long wallMillis;
	public final List<TrainView> trains = new ArrayList<>();
	public final List<Incident> incidents = new ArrayList<>();
}
