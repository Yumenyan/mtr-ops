package jp.mtrops.core;

import java.util.ArrayDeque;
import java.util.Deque;

/** 依存なしの最小JSONライター(カンマ管理つき)。 */
public final class JsonWriter {

	private final StringBuilder sb = new StringBuilder(1024);
	private final Deque<Boolean> firstStack = new ArrayDeque<>();
	private boolean afterName;

	private void beforeValue() {
		if (afterName) {
			afterName = false;
			return;
		}
		if (!firstStack.isEmpty()) {
			if (firstStack.peek()) {
				firstStack.pop();
				firstStack.push(false);
			} else {
				sb.append(',');
			}
		}
	}

	public JsonWriter beginObject() {
		beforeValue();
		sb.append('{');
		firstStack.push(true);
		return this;
	}

	public JsonWriter endObject() {
		firstStack.pop();
		sb.append('}');
		return this;
	}

	public JsonWriter beginArray() {
		beforeValue();
		sb.append('[');
		firstStack.push(true);
		return this;
	}

	public JsonWriter endArray() {
		firstStack.pop();
		sb.append(']');
		return this;
	}

	public JsonWriter name(String name) {
		beforeValue();
		quote(name);
		sb.append(':');
		afterName = true;
		return this;
	}

	public JsonWriter value(String v) {
		beforeValue();
		if (v == null) {
			sb.append("null");
		} else {
			quote(v);
		}
		return this;
	}

	public JsonWriter value(long v) {
		beforeValue();
		sb.append(v);
		return this;
	}

	public JsonWriter value(boolean v) {
		beforeValue();
		sb.append(v);
		return this;
	}

	/** decimals桁に丸める。NaN/Infinityは null。 */
	public JsonWriter value(double v, int decimals) {
		beforeValue();
		if (Double.isNaN(v) || Double.isInfinite(v)) {
			sb.append("null");
		} else if (decimals <= 0) {
			sb.append(Math.round(v));
		} else {
			final double f = Math.pow(10, decimals);
			final double r = Math.round(v * f) / f;
			sb.append(r == Math.rint(r) && Math.abs(r) < 1e15 ? String.valueOf((long) r) : String.valueOf(r));
		}
		return this;
	}

	public JsonWriter nullValue() {
		beforeValue();
		sb.append("null");
		return this;
	}

	// --- 便利メソッド ---
	public JsonWriter field(String name, String v) {
		return name(name).value(v);
	}

	public JsonWriter field(String name, long v) {
		return name(name).value(v);
	}

	public JsonWriter field(String name, boolean v) {
		return name(name).value(v);
	}

	public JsonWriter field(String name, double v, int decimals) {
		return name(name).value(v, decimals);
	}

	/** IDは JavaScript の Number で精度が落ちるため、文字列で出す。 */
	public JsonWriter idField(String name, long id) {
		return name(name).value(Long.toString(id));
	}

	private void quote(String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			final char c = s.charAt(i);
			switch (c) {
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		sb.append('"');
	}

	@Override
	public String toString() {
		return sb.toString();
	}
}
