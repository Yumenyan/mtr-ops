package jp.mtrops.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小さなJSONパーサー(Webアプリからの保存要求を読むためだけ)。
 * 結果は Map / List / String / Double / Boolean / null。深さと長さに上限を設ける。
 */
public final class JsonReader {

	public static final int MAX_DEPTH = 32;
	public static final int MAX_LENGTH = 2_000_000;

	private final String s;
	private int i;

	private JsonReader(String s) {
		this.s = s;
	}

	public static Object parse(String text) {
		if (text == null || text.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("JSONが大きすぎます");
		}
		final JsonReader r = new JsonReader(text);
		r.ws();
		final Object v = r.value(0);
		r.ws();
		if (r.i != text.length()) {
			throw r.err("余分な文字があります");
		}
		return v;
	}

	private IllegalArgumentException err(String m) {
		return new IllegalArgumentException("JSONの読み取りに失敗しました(" + i + "文字目): " + m);
	}

	private void ws() {
		while (i < s.length()) {
			final char c = s.charAt(i);
			if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
				i++;
			} else {
				break;
			}
		}
	}

	private Object value(int depth) {
		if (depth > MAX_DEPTH) {
			throw err("入れ子が深すぎます");
		}
		if (i >= s.length()) {
			throw err("途中で終わっています");
		}
		final char c = s.charAt(i);
		switch (c) {
			case '{':
				return object(depth);
			case '[':
				return array(depth);
			case '"':
				return string();
			case 't':
				expect("true");
				return Boolean.TRUE;
			case 'f':
				expect("false");
				return Boolean.FALSE;
			case 'n':
				expect("null");
				return null;
			default:
				return number();
		}
	}

	private void expect(String word) {
		if (!s.startsWith(word, i)) {
			throw err(word + " ではありません");
		}
		i += word.length();
	}

	private Map<String, Object> object(int depth) {
		final Map<String, Object> m = new LinkedHashMap<>();
		i++;
		ws();
		if (i < s.length() && s.charAt(i) == '}') {
			i++;
			return m;
		}
		while (true) {
			ws();
			if (i >= s.length() || s.charAt(i) != '"') {
				throw err("キーがありません");
			}
			final String k = string();
			ws();
			if (i >= s.length() || s.charAt(i) != ':') {
				throw err(": がありません");
			}
			i++;
			ws();
			m.put(k, value(depth + 1));
			ws();
			if (i >= s.length()) {
				throw err("途中で終わっています");
			}
			final char c = s.charAt(i++);
			if (c == '}') {
				return m;
			}
			if (c != ',') {
				throw err(", か } がありません");
			}
		}
	}

	private List<Object> array(int depth) {
		final List<Object> l = new ArrayList<>();
		i++;
		ws();
		if (i < s.length() && s.charAt(i) == ']') {
			i++;
			return l;
		}
		while (true) {
			ws();
			l.add(value(depth + 1));
			ws();
			if (i >= s.length()) {
				throw err("途中で終わっています");
			}
			final char c = s.charAt(i++);
			if (c == ']') {
				return l;
			}
			if (c != ',') {
				throw err(", か ] がありません");
			}
		}
	}

	private String string() {
		final StringBuilder b = new StringBuilder();
		i++;
		while (i < s.length()) {
			final char c = s.charAt(i++);
			if (c == '"') {
				return b.toString();
			}
			if (c == '\\') {
				if (i >= s.length()) {
					break;
				}
				final char e = s.charAt(i++);
				switch (e) {
					case '"':
					case '\\':
					case '/':
						b.append(e);
						break;
					case 'b':
						b.append('\b');
						break;
					case 'f':
						b.append('\f');
						break;
					case 'n':
						b.append('\n');
						break;
					case 'r':
						b.append('\r');
						break;
					case 't':
						b.append('\t');
						break;
					case 'u':
						if (i + 4 > s.length()) {
							throw err("\\u が不正です");
						}
						b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
						i += 4;
						break;
					default:
						throw err("不正なエスケープ");
				}
			} else {
				b.append(c);
			}
		}
		throw err("文字列が閉じていません");
	}

	private Double number() {
		final int st = i;
		while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
			i++;
		}
		if (st == i) {
			throw err("値がありません");
		}
		try {
			return Double.parseDouble(s.substring(st, i));
		} catch (NumberFormatException e) {
			throw err("数値が不正です");
		}
	}

	// ------------------------------------------------------------------ 取り出しの補助

	@SuppressWarnings("unchecked")
	public static Map<String, Object> obj(Object o) {
		return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
	}

	@SuppressWarnings("unchecked")
	public static List<Object> arr(Object o) {
		return o instanceof List ? (List<Object>) o : new ArrayList<>();
	}

	public static long lng(Object o, long def) {
		if (o instanceof Number) {
			return ((Number) o).longValue();
		}
		if (o instanceof String) {
			try {
				return Long.parseLong(((String) o).trim());
			} catch (NumberFormatException e) {
				return def;
			}
		}
		return def;
	}

	public static int num(Object o, int def) {
		final long v = lng(o, def);
		return v > Integer.MAX_VALUE || v < Integer.MIN_VALUE ? def : (int) v;
	}

	public static boolean bool(Object o, boolean def) {
		return o instanceof Boolean ? (Boolean) o : def;
	}

	public static String str(Object o, String def) {
		return o instanceof String ? (String) o : def;
	}
}
