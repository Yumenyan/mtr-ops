package jp.mtrops.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 遅延と「列車そのものの異常」を分離して判定する(設計書 §9)。
 * 毎サンプルで update() を呼ぶ。状態はこのインスタンスに保持される(サーバー側1個)。
 *
 * 手動運転中の列車(プレイヤーが運転)は、停止・位置・到着超過の判定対象外にする。
 */
public final class AnomalyDetector {

	public static final int TICKS_PER_SEC = 20;
	/** 見えなくなってからこの時間で、その列車の判定状態を破棄する。 */
	private static final long FORGET_AFTER_TICKS = 10L * TICKS_PER_SEC;

	public static final class Result {
		public final List<TrainView> views = new ArrayList<>();
		/** 未解消 → 解消済み(新しい順)の順。 */
		public final List<Incident> incidents = new ArrayList<>();
		public int activeCount;
	}

	private static final class State {
		long firstSeen;
		long lastSeen;
		TrainSample last;
		double anchorX, anchorZ;
		long lastMoveTick;
		long stopSince = -1;
		long bunchSince = -1;
		long mismatchSince = -1;
		long ghostSince = -1;
		double progressFrac;
	}

	private final OpsConfig cfg;
	private final Map<Long, State> states = new HashMap<>();
	private final Map<String, Incident> open = new HashMap<>();
	private final LinkedList<Incident> history = new LinkedList<>();
	private int nextIncidentId = 1;

	public AnomalyDetector(OpsConfig cfg) {
		this.cfg = cfg;
	}

	public Result update(long nowTick, List<TrainSample> samples) {
		return update(nowTick, samples, null);
	}

	/**
	 * @param presentIds MTR側に「実在する」列車のID。サンプリングに失敗した(samplesに無い)だけの列車を
	 *                   「消失」と誤判定しないために使う。nullなら samples のIDのみを実在とみなす。
	 */
	public Result update(long nowTick, List<TrainSample> samples, Set<Long> presentIds) {
		final Result result = new Result();
		final Set<Long> seen = new HashSet<>();
		if (presentIds != null) {
			for (final Long pid : presentIds) {
				final State known = states.get(pid);
				if (known != null) {
					known.lastSeen = nowTick; // 実在はしている → 消失ではない(サンプル失敗は無視)
				}
			}
		}
		final Map<Long, List<TrainSample>> bySiding = new HashMap<>();

		for (final TrainSample s : samples) {
			seen.add(s.id);
			final State st = states.computeIfAbsent(s.id, k -> {
				final State n = new State();
				n.firstSeen = nowTick;
				n.anchorX = s.x;
				n.anchorZ = s.z;
				n.lastMoveTick = nowTick;
				return n;
			});
			st.lastSeen = nowTick;
			st.last = s;
			st.progressFrac = s.pathLength > 0 ? s.railProgress / s.pathLength : 0;
			if (s.onRoute) {
				bySiding.computeIfAbsent(s.sidingId, k -> new ArrayList<>()).add(s);
			}
		}

		for (final TrainSample s : samples) {
			result.views.add(evaluate(states.get(s.id), s, nowTick));
		}

		evaluateHeadway(nowTick, bySiding);
		handleAbsent(nowTick, seen, presentIds);

		// 消えた列車に紐づく未解消インシデントは handleAbsent 側で閉じる。ここでは整理のみ。
		final List<Incident> active = new ArrayList<>(open.values());
		active.sort(Comparator.<Incident>comparingInt(i -> -i.severity.ordinal()).thenComparingLong(i -> i.sinceTick));
		result.incidents.addAll(active);
		result.incidents.addAll(history);
		result.activeCount = active.size();
		return result;
	}

	public int activeIncidentCount() {
		return open.size();
	}

	// ------------------------------------------------------------------

	private TrainView evaluate(State st, TrainSample s, long now) {
		int delaySec = 0;
		if (!Double.isNaN(s.idealTicks) && !Double.isNaN(s.elapsedTicks) && !s.manual && s.onRoute) {
			delaySec = (int) Math.max(0, Math.round((s.elapsedTicks - s.idealTicks) / TICKS_PER_SEC));
		}

		// --- ゴースト疑い(動かない車両が閉塞を塞ぐ): 5秒以上続いたら通知 ---
		if (s.suspect != 0) {
			if (st.ghostSince < 0) {
				st.ghostSince = now;
			}
		} else {
			st.ghostSince = -1;
		}
		final int ghostSec = st.ghostSince < 0 ? 0 : (int) ((now - st.ghostSince) / TICKS_PER_SEC);
		final String why = (s.suspect & TrainSample.SUSPECT_FROZEN) != 0
				? "MTRが毎tick何もせず固着している(経路再生成後に次の停車位置が経路外)"
				: "経路が空、または距離表が不足していて位置を取得できない";
		toggle(Incident.Type.GHOST, s.suspect != 0 && ghostSec >= 5, Incident.Severity.CRIT, s, now, ghostSec,
				s.label() + " はゴースト疑い: " + why);

		TrainStatus status = TrainStatus.NORMAL;
		if (s.onRoute && !s.manual) {
			// --- 停止 ---
			final boolean stoppedNow = s.speedMps < 0.05F && !s.dwelling;
			if (stoppedNow) {
				if (st.stopSince < 0) {
					st.stopSince = now;
				}
			} else {
				st.stopSince = -1;
			}
			final int stoppedSec = st.stopSince < 0 ? 0 : (int) ((now - st.stopSince) / TICKS_PER_SEC);

			final boolean stopped = stoppedSec >= cfg.stoppedSec;
			toggle(Incident.Type.STOPPED, stopped, Incident.Severity.WARN, s, now, stoppedSec,
					s.label() + " が " + stoppedSec + " 秒間停止しています");

			// --- 位置が更新されない(速度はあるのに動かない) ---
			final double moved = Math.hypot(s.x - st.anchorX, s.z - st.anchorZ);
			if (moved >= cfg.staleMinMoveM || s.speedMps < 0.5F) {
				st.anchorX = s.x;
				st.anchorZ = s.z;
				st.lastMoveTick = now;
			}
			final int staleSec = (int) ((now - st.lastMoveTick) / TICKS_PER_SEC);
			final boolean stale = s.speedMps >= 0.5F && staleSec >= cfg.staleSec;
			toggle(Incident.Type.POSITION_STALE, stale, Incident.Severity.CRIT, s, now, staleSec,
					s.label() + " の位置情報が " + staleSec + " 秒間更新されていません");

			// --- 次駅への到着予定超過 ---
			boolean overdue = false;
			int overdueSec = 0;
			if (!s.atStop && !Double.isNaN(s.idealNextArrivalTicks) && !Double.isNaN(s.elapsedTicks)) {
				overdueSec = (int) ((s.elapsedTicks - s.idealNextArrivalTicks) / TICKS_PER_SEC);
				overdue = overdueSec >= cfg.overdueSec;
			}
			toggle(Incident.Type.OVERDUE, overdue, Incident.Severity.WARN, s, now, Math.max(0, overdueSec),
					s.label() + " が次駅への到着予定を " + overdueSec + " 秒超過しています");

			// --- 運行情報との不一致(走行中なのに路線が特定できない/車庫が不明) ---
			final boolean mismatchNow = !s.cableCar && (s.routeId == 0 || s.depotId == 0); // ロープウェイは路線・車庫の対応が通常の列車と違うため除外
			if (mismatchNow) {
				if (st.mismatchSince < 0) {
					st.mismatchSince = now;
				}
			} else {
				st.mismatchSince = -1;
			}
			final int mismatchSec = st.mismatchSince < 0 ? 0 : (int) ((now - st.mismatchSince) / TICKS_PER_SEC);
			toggle(Incident.Type.MISMATCH, mismatchNow && mismatchSec >= 10, Incident.Severity.WARN, s, now, mismatchSec,
					s.label() + " の路線または車庫を運行情報から特定できません");

			// --- 状態の決定 ---
			if (delaySec >= cfg.bigDelaySec || overdue) {
				status = TrainStatus.BIG_DELAY;
			} else if (delaySec >= cfg.delaySec) {
				status = TrainStatus.DELAY;
			}
			if (stopped && TrainStatus.STOPPED.isWorseThan(status)) {
				status = TrainStatus.STOPPED;
			}
		} else {
			st.stopSince = -1;
			st.mismatchSince = -1;
			st.anchorX = s.x;
			st.anchorZ = s.z;
			st.lastMoveTick = now;
			closeAllFor(s.id, now, EnumSetOfTrainLocal.TYPES_WITHOUT_HEADWAY);
		}
		return TrainView.of(s, status, delaySec);
	}

	/** 種別ごとの一括クローズ用。 */
	private static final class EnumSetOfTrainLocal {
		static final Incident.Type[] TYPES_WITHOUT_HEADWAY = {
				Incident.Type.STOPPED, Incident.Type.POSITION_STALE,
				Incident.Type.OVERDUE, Incident.Type.MISMATCH
		};
	}

	private void evaluateHeadway(long now, Map<Long, List<TrainSample>> bySiding) {
		final Set<Long> flagged = new HashSet<>();
		for (final List<TrainSample> group : bySiding.values()) {
			if (group.size() < 2) {
				continue;
			}
			group.sort(Comparator.<TrainSample>comparingDouble(t -> -t.railProgress));
			for (int i = 1; i < group.size(); i++) {
				final TrainSample front = group.get(i - 1);
				final TrainSample rear = group.get(i);
				if (rear.manual || front.manual) {
					continue;
				}
				final double gap = (front.railProgress - (double) front.cars * front.spacing) - rear.railProgress;
				final State st = states.get(rear.id);
				if (st == null) {
					continue;
				}
				final boolean tooClose = gap < cfg.bunchGapM && !(front.atStop && front.speedMps < 0.05F);
				final boolean tooFar = cfg.maxGapM > 0 && gap > cfg.maxGapM;
				if (tooClose || tooFar) {
					if (st.bunchSince < 0) {
						st.bunchSince = now;
					}
					final int sec = (int) ((now - st.bunchSince) / TICKS_PER_SEC);
					if (sec >= cfg.bunchSec) {
						flagged.add(rear.id);
						toggle(Incident.Type.HEADWAY, true, Incident.Severity.INFO, rear, now, sec,
								rear.label() + " と前列車の間隔が" + (tooClose ? "詰まっています" : "開いています") + "(約" + Math.round(gap) + "m)");
					}
				} else {
					st.bunchSince = -1;
				}
			}
		}
		for (final Map.Entry<Long, State> e : states.entrySet()) {
			if (!flagged.contains(e.getKey())) {
				if (e.getValue().bunchSince >= 0 && !bySiding.containsKey(e.getValue().last.sidingId)) {
					e.getValue().bunchSince = -1;
				}
				clear(Incident.Type.HEADWAY, e.getKey(), now);
			}
		}
	}

	/**
	 * 見えなくなった列車の状態を片付ける。「列車消失」という状態・通知は出さない
	 * (MTRは終点・設定変更・他アドオンの削除コマンドなどで正規に列車を消すため、原因を区別できない)。
	 * MTR側に実在する(サンプリングに失敗しているだけの)列車は触らない。
	 */
	private void handleAbsent(long now, Set<Long> seen, Set<Long> presentIds) {
		final Iterator<Map.Entry<Long, State>> it = states.entrySet().iterator();
		while (it.hasNext()) {
			final Map.Entry<Long, State> e = it.next();
			final long id = e.getKey();
			if (seen.contains(id) || (presentIds != null && presentIds.contains(id))) {
				continue;
			}
			if ((now - e.getValue().lastSeen) < FORGET_AFTER_TICKS) {
				continue;
			}
			closeAllFor(id, now, Incident.Type.values());
			it.remove();
		}
	}

	// ------------------------------------------------------------------ incident helpers

	private static String key(Incident.Type type, long trainId) {
		return type.ordinal() + ":" + trainId;
	}

	private void toggle(Incident.Type type, boolean condition, Incident.Severity sev, TrainSample s, long now, int seconds, String message) {
		if (condition) {
			raise(type, sev, s, now, seconds, message);
		} else {
			clear(type, s.id, now);
		}
	}

	private void raise(Incident.Type type, Incident.Severity sev, TrainSample s, long now, int seconds, String message) {
		final String k = key(type, s.id);
		Incident inc = open.get(k);
		if (inc == null) {
			inc = new Incident();
			inc.id = nextIncidentId++;
			inc.type = type;
			inc.severity = sev;
			inc.trainId = s.id;
			inc.sinceTick = now;
			open.put(k, inc);
		}
		inc.routeId = s.routeId;
		inc.formation = s.formationId == null ? "" : s.formationId;
		inc.message = message;
		inc.seconds = seconds;
		inc.lastTick = now;
	}

	private void clear(Incident.Type type, long trainId, long now) {
		final Incident inc = open.remove(key(type, trainId));
		if (inc != null) {
			inc.resolvedTick = now;
			history.addFirst(inc);
			while (history.size() > cfg.incidentHistory) {
				history.removeLast();
			}
		}
	}

	private void closeAllFor(long trainId, long now, Incident.Type[] types) {
		for (final Incident.Type t : types) {
			clear(t, trainId, now);
		}
	}
}
