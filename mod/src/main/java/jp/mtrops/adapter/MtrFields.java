package jp.mtrops.adapter;

import mtr.data.Siding;
import mtr.data.Train;
import mtr.data.TrainServer;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * MTR 3.2.2-hotfix-1 の private/protected フィールドへのアクセス(リフレクション)。
 * 対象はソース(3.x.x タグ)で存在を確認したフィールドのみ:
 *   Siding.trains              private final Set<TrainServer>
 *   Train.nextStoppingIndex    protected int
 *   Train.distances            protected final List<Double>
 *   Train.ridingEntities       protected final Set<UUID>
 *   TrainServer.timeSegments   private final List<Siding.TimeSegment>
 *   TrainServer.routeId        private long
 *
 * フィールドが見つからない場合(別バージョンのMTR)は missing に名前を残し、
 * MtrAdapter.selfCheck() が起動時にログへ出す。推測で別の経路を使うことはしない。
 */
final class MtrFields {

	private static final StringBuilder MISSING = new StringBuilder();

	private static final Field SIDING_TRAINS = find(Siding.class, "trains");
	private static final Field TRAIN_NEXT_STOP = find(Train.class, "nextStoppingIndex");
	private static final Field TRAIN_DISTANCES = find(Train.class, "distances");
	private static final Field TRAIN_RIDING = find(Train.class, "ridingEntities");
	private static final Field TS_TIME_SEGMENTS = find(TrainServer.class, "timeSegments");
	private static final Field TS_ROUTE_ID = find(TrainServer.class, "routeId");

	private MtrFields() {
	}

	private static Field find(Class<?> owner, String name) {
		try {
			final Field f = owner.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (ReflectiveOperationException | RuntimeException e) {
			MISSING.append(owner.getSimpleName()).append('.').append(name).append(' ');
			return null;
		}
	}

	static String missing() {
		return MISSING.toString().trim();
	}

	static boolean sidingTrainsAvailable() {
		return SIDING_TRAINS != null && TRAIN_DISTANCES != null;
	}

	@SuppressWarnings("unchecked")
	static Set<TrainServer> trains(Siding siding) {
		try {
			return SIDING_TRAINS == null ? Collections.emptySet() : (Set<TrainServer>) SIDING_TRAINS.get(siding);
		} catch (ReflectiveOperationException e) {
			return Collections.emptySet();
		}
	}

	static int nextStoppingIndex(Train train) {
		try {
			return TRAIN_NEXT_STOP == null ? -1 : TRAIN_NEXT_STOP.getInt(train);
		} catch (ReflectiveOperationException e) {
			return -1;
		}
	}

	@SuppressWarnings("unchecked")
	static List<Double> distances(Train train) {
		try {
			return TRAIN_DISTANCES == null ? Collections.emptyList() : (List<Double>) TRAIN_DISTANCES.get(train);
		} catch (ReflectiveOperationException e) {
			return Collections.emptyList();
		}
	}

	@SuppressWarnings("unchecked")
	static int passengerCount(Train train) {
		try {
			return TRAIN_RIDING == null ? 0 : ((Set<UUID>) TRAIN_RIDING.get(train)).size();
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	@SuppressWarnings("unchecked")
	static List<Siding.TimeSegment> timeSegments(TrainServer train) {
		try {
			return TS_TIME_SEGMENTS == null ? Collections.emptyList() : (List<Siding.TimeSegment>) TS_TIME_SEGMENTS.get(train);
		} catch (ReflectiveOperationException e) {
			return Collections.emptyList();
		}
	}

	static long routeId(TrainServer train) {
		try {
			return TS_ROUTE_ID == null ? 0 : TS_ROUTE_ID.getLong(train);
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}
}
