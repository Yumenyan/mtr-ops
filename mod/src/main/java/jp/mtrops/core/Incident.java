package jp.mtrops.core;

/** 異常・障害情報。通常の「遅延」とは別枠で扱う(設計書 §9)。 */
public final class Incident {

	public enum Type {
		STOPPED("stopped", "長時間停止"),
		POSITION_STALE("positionStale", "位置情報が更新されない"),
		OVERDUE("overdue", "次駅への到着予定を大幅に超過"),
		MISMATCH("mismatch", "運行情報と列車状態の不一致"),
		HEADWAY("headway", "異常な列車間隔"),
		GHOST("ghost", "ゴースト疑い(動かない車両が閉塞を塞ぐ可能性)");

		public final String key;
		public final String label;

		Type(String key, String label) {
			this.key = key;
			this.label = label;
		}

		public static Type fromCode(int code) {
			final Type[] values = values();
			return code >= 0 && code < values.length ? values[code] : STOPPED;
		}
	}

	public enum Severity {
		INFO("info"), WARN("warn"), CRIT("crit");

		public final String key;

		Severity(String key) {
			this.key = key;
		}

		public static Severity fromCode(int code) {
			final Severity[] values = values();
			return code >= 0 && code < values.length ? values[code] : INFO;
		}
	}

	public int id;
	public Type type = Type.STOPPED;
	public Severity severity = Severity.WARN;
	public long trainId;
	public long routeId;
	public String formation = "";
	public String message = "";
	/** 継続時間などの補足(秒)。 */
	public int seconds;
	public long sinceTick;
	public long lastTick;
	/** 解消された時刻。未解消は -1 */
	public long resolvedTick = -1;

	public boolean isActive() {
		return resolvedTick < 0;
	}
}
