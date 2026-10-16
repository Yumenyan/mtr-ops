package jp.mtrops.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 発車標の ScheduleEntry(路線・停車駅・到着予定)に対応する実際の列車を探す。
 * ScheduleEntry は列車IDを持たないので、「同じ路線を走り、その駅へ向かっている列車」を到着の近い順に並べ、
 * 同じ路線・同じ駅(ホーム)のエントリを到着時刻順に並べたときの順位(rank, 0始まり)で対応づける。
 */
public final class EntryMatcher {

	private EntryMatcher() {
	}

	private static final class Cand {
		final TrainView view;
		final double key;

		Cand(TrainView view, double key) {
			this.view = view;
			this.key = key;
		}
	}

	/** その駅に到着する(または停車中の)列車を、到着の早い順に返す。次駅情報(詳細レベル1以上)が無い列車は対象外。 */
	public static List<TrainView> candidates(Snapshot snap, Topology topo, long routeId, long stationId) {
		final List<TrainView> none = new ArrayList<>();
		if (snap == null || topo == null || stationId == 0) {
			return none;
		}
		final Topology.Route route = topo.route(routeId);
		if (route == null) {
			return none;
		}
		final long[] ids = route.stationIds;
		final int target = indexOf(ids, stationId, 0);
		if (target < 0) {
			return none;
		}
		final boolean circular = route.circular != 0;
		final List<Cand> list = new ArrayList<>();
		for (final TrainView v : snap.trains) {
			final TrainSample s = v.s;
			if (!s.onRoute || s.routeId != routeId) {
				continue;
			}
			if (s.atStop && s.curStationId == stationId) {
				list.add(new Cand(v, -1));
				continue;
			}
			final long nextId = s.atStop && s.curStationId != 0 ? s.curStationId : s.nextStationId;
			if (nextId == 0) {
				continue;
			}
			// 次駅が路線に複数回出る場合は、目的駅に最も近い手前の出現を採る
			int ni = indexOf(ids, nextId, 0);
			if (ni < 0) {
				continue;
			}
			int hops = target - ni;
			if (hops < 0) {
				final int later = indexOf(ids, nextId, target + 1);
				if (circular && later >= 0) {
					hops = ids.length - later + target;
				} else if (circular) {
					hops = ids.length - ni + target;
				} else {
					continue; // もう通り過ぎている
				}
			}
			double dist = 0;
			if (!(s.atStop && s.curStationId != 0)) {
				final Topology.Station ns = topo.station(nextId);
				if (ns != null) {
					dist = Math.hypot(s.x - ns.x, s.z - ns.z);
				}
			}
			list.add(new Cand(v, hops * 1e6 + dist));
		}
		list.sort(Comparator.comparingDouble(c -> c.key));
		final List<TrainView> out = new ArrayList<>();
		for (final Cand c : list) {
			out.add(c.view);
		}
		return out;
	}

	/** rank 番目に到着する列車。無ければ null。 */
	public static TrainView match(Snapshot snap, Topology topo, long routeId, long stationId, int rank) {
		final List<TrainView> c = candidates(snap, topo, routeId, stationId);
		return rank >= 0 && rank < c.size() ? c.get(rank) : null;
	}

	private static int indexOf(long[] ids, long id, int from) {
		for (int i = Math.max(0, from); i < ids.length; i++) {
			if (ids[i] == id) {
				return i;
			}
		}
		return -1;
	}
}
