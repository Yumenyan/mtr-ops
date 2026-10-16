package jp.mtrops.core;

/**
 * 列車の運行状態。宣言順がそのまま深刻度(小さいほど軽い)。
 * 設計書 §9 の「正常 / 遅延 / 大幅遅延 / 列車停止」(列車消失・運行異常は通常起きない/区別できないため廃止)に対応。
 */
public enum TrainStatus {
	NORMAL("normal", "平常"),
	DELAY("delay", "遅延"),
	BIG_DELAY("bigDelay", "大幅遅延"),
	STOPPED("stopped", "列車停止");

	public final String key;
	public final String label;

	TrainStatus(String key, String label) {
		this.key = key;
		this.label = label;
	}

	public int code() {
		return ordinal();
	}

	public static TrainStatus fromCode(int code) {
		final TrainStatus[] values = values();
		return code >= 0 && code < values.length ? values[code] : NORMAL;
	}

	public boolean isWorseThan(TrainStatus other) {
		return ordinal() > other.ordinal();
	}
}
