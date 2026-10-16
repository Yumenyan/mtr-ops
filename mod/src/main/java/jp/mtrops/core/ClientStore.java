package jp.mtrops.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * クライアント側のキャッシュ(設計書 §2: MTR情報キャッシュ / 車両情報キャッシュ)。
 * サーバーから受信した内容を保持し、ローカルHTTP APIはここだけを見る。
 * ネットワークスレッドとHTTPスレッドから触られるので、公開する値は不変スナップショットとして差し替える。
 */
public final class ClientStore {

	private volatile Topology topology = new Topology();
	private volatile OpsRegistry registry = new OpsRegistry();
	private volatile Snapshot live;
	private volatile long liveReceivedMillis;
	private volatile int lastEditResultCode = -1;
	private volatile String lastEditMessage = "";
	private volatile int editSeq;

	private final Map<Integer, List<SnapshotCodec.Part>> pending = new HashMap<>();

	public Topology topology() {
		return topology;
	}

	public OpsRegistry registry() {
		return registry;
	}

	public Snapshot live() {
		return live;
	}

	public long liveAgeMillis() {
		return live == null ? Long.MAX_VALUE : System.currentTimeMillis() - liveReceivedMillis;
	}

	public void setTopology(Topology t) {
		this.topology = t;
	}

	public void setRegistry(OpsRegistry r) {
		this.registry = r;
	}

	public int lastEditResultCode() {
		return lastEditResultCode;
	}

	public String lastEditMessage() {
		return lastEditMessage;
	}

	public int editSeq() {
		return editSeq;
	}

	public void setEditResult(int code, String message) {
		this.lastEditResultCode = code;
		this.lastEditMessage = message;
		this.editSeq++;
	}

	public synchronized void acceptSnapshotPart(SnapshotCodec.Part p) {
		if (p.parts <= 1) {
			pending.clear();
			publish(p.snapshot);
			return;
		}
		final List<SnapshotCodec.Part> list = pending.computeIfAbsent(p.snapshot.seq, k -> new ArrayList<>());
		list.add(p);
		// 古いseqの未完成分は捨てる
		pending.keySet().removeIf(seq -> seq < p.snapshot.seq);
		if (list.size() >= p.parts) {
			final Snapshot merged = new Snapshot();
			SnapshotCodec.Part first = null;
			for (final SnapshotCodec.Part q : list) {
				if (q.part == 0) {
					first = q;
				}
			}
			if (first == null) {
				return;
			}
			merged.seq = first.snapshot.seq;
			merged.level = first.snapshot.level;
			merged.gameTick = first.snapshot.gameTick;
			merged.dayTime = first.snapshot.dayTime;
			merged.wallMillis = first.snapshot.wallMillis;
			merged.incidents.addAll(first.snapshot.incidents);
			list.sort(java.util.Comparator.comparingInt(q -> q.part));
			for (final SnapshotCodec.Part q : list) {
				merged.trains.addAll(q.snapshot.trains);
			}
			pending.remove(p.snapshot.seq);
			publish(merged);
		}
	}

	private void publish(Snapshot s) {
		this.live = s;
		this.liveReceivedMillis = System.currentTimeMillis();
	}

	public void clear() {
		topology = new Topology();
		registry = new OpsRegistry();
		live = null;
		pending.clear();
	}
}
