package jp.mtrops.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MTR 3.2.2 の車庫線(Siding)の列車は、編成番号を持たない匿名の TrainServer(ランダムなlong ID)。
 * そこで「車庫線ごとの出庫順リスト」を台帳に持ち、サンプルのたびに
 *   ・消えた列車の割り当てを解放
 *   ・新しく現れた列車に、空いている最初の編成を割り当て
 * を行う。割り当ては OpsRegistry.assignments に入り、ワールドに保存される。
 *
 * MTRは車庫線の設定変更で列車を一括削除する(個別削除はできない)。その場合も
 * 「生きている列車に空き編成を順に当てる」ので、番号がずれても最終的に一意性は保たれる。
 */
public final class FormationAssigner {

	private final OpsRegistry registry;
	private boolean registryChanged;

	public FormationAssigner(OpsRegistry registry) {
		this.registry = registry;
	}

	/** 自動採番で台帳(編成の一覧)自体が増えたか。読み出すとリセットされる(クライアントへの再同期用)。 */
	public boolean takeRegistryChanged() {
		final boolean c = registryChanged;
		registryChanged = false;
		return c;
	}

	/**
	 * @return 割り当てに変化があれば true(保存が必要)
	 */
	public boolean assign(List<TrainSample> samples) {
		return assign(samples, null);
	}

	public boolean assign(List<TrainSample> samples, Set<Long> present) {
		boolean changed = false;
		final Set<Long> live = new HashSet<>();
		final Map<Long, List<TrainSample>> bySiding = new HashMap<>();
		for (final TrainSample s : samples) {
			live.add(s.id);
			bySiding.computeIfAbsent(s.sidingId, k -> new ArrayList<>()).add(s);
		}

		// 1) 消えた列車の割り当てを解放
		changed |= registry.assignments.keySet().removeIf(id -> !live.contains(id) && (present == null || !present.contains(id)));

		// 2) 車庫線ごとに割り当て
		for (final Map.Entry<Long, List<TrainSample>> entry : bySiding.entrySet()) {
			final long sidingId = entry.getKey();
			final List<TrainSample> trains = entry.getValue();
			trains.sort(Comparator.comparingLong(t -> t.id));
			final long depotId = trains.get(0).depotId;
			final List<OpsRegistry.Formation> formations = registry.formationsOf(depotId, sidingId);

			final Map<String, OpsRegistry.Formation> byId = new HashMap<>();
			if (formations != null) {
				for (final OpsRegistry.Formation f : formations) {
					byId.put(f.id, f);
				}
			}

			final Set<String> used = new HashSet<>();
			final List<TrainSample> pending = new ArrayList<>();
			for (final TrainSample t : trains) {
				final OpsRegistry.Assign a = registry.assignments.get(t.id);
				if (a != null && a.sidingId == sidingId && byId.containsKey(a.formationId) && used.add(a.formationId)) {
					apply(t, byId.get(a.formationId));
				} else {
					if (a != null) {
						registry.assignments.remove(t.id);
						changed = true;
					}
					pending.add(t);
				}
			}
			for (final TrainSample t : pending) {
				OpsRegistry.Formation chosen = null;
				final OpsRegistry.DepotRecord drec = registry.depots.get(depotId);
				final OpsRegistry.NumberRange rng = drec == null ? null : drec.ranges.get(sidingId);
				if (rng != null && rng.random) {
					// ランダム採番: 範囲内の空き番号から無作為に(登録済みの空き編成も候補)
					final int before = formations == null ? 0 : formations.size();
					chosen = registry.pickRandom(depotId, sidingId, t.cars, used);
					if (chosen != null) {
						used.add(chosen.id);
						final List<OpsRegistry.Formation> after = registry.formationsOf(depotId, sidingId);
						if (after != null && after.size() != before) {
							registryChanged = true;
						}
						registry.assignments.put(t.id, new OpsRegistry.Assign(sidingId, chosen.id));
						apply(t, chosen);
						changed = true;
					}
					continue;
				}
				if (formations != null) {
					for (final OpsRegistry.Formation f : formations) {
						if (used.add(f.id)) {
							chosen = f;
							break;
						}
					}
				}
				if (chosen == null) {
					// 空き編成が無い → 車番範囲があれば自動で登録して採番する
					chosen = registry.autoRegister(depotId, sidingId, t.cars);
					if (chosen != null) {
						used.add(chosen.id);
						registryChanged = true;
					}
				}
				if (chosen != null) {
					registry.assignments.put(t.id, new OpsRegistry.Assign(sidingId, chosen.id));
					apply(t, chosen);
					changed = true;
				}
			}
		}
		return changed;
	}

	/** 登録済み編成のうち、現在どの列車にも割り当てられていないもの(在庫)。 */
	public Set<String> idleFormations(long depotId) {
		final Set<String> assigned = new HashSet<>();
		registry.assignments.values().forEach(a -> assigned.add(OpsRegistry.indexKey(a.sidingId, a.formationId)));
		final Set<String> idle = new HashSet<>();
		final OpsRegistry.DepotRecord d = registry.depots.get(depotId);
		if (d != null) {
			d.sidings.forEach((sid, l) -> l.forEach(f -> {
				if (!assigned.contains(OpsRegistry.indexKey(sid, f.id))) {
					idle.add(f.id);
				}
			}));
		}
		return idle;
	}

	private static void apply(TrainSample t, OpsRegistry.Formation f) {
		t.formationId = f.id;
		t.series = f.series;
	}
}
