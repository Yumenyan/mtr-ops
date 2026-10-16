package jp.mtrops.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ローカルHTTPサーバー(設計書 §2 の「ローカルHTTP API」)。
 *
 * jdk.httpserver モジュールは Minecraft ランチャー同梱の縮小JREに含まれない場合があるため、
 * java.base のみ(ServerSocket)で実装している。GET/HEAD/OPTIONS のみ対応、接続ごとに Connection: close。
 *
 * セキュリティ:
 *  - 127.0.0.1 にのみバインド(LAN・外部からは到達不可)
 *  - Host ヘッダが localhost / 127.0.0.1 / [::1] 以外なら拒否(DNSリバインディング対策)
 *  - Origin は localhost 系・"null"(file://)・設定した許可Originのみ CORS 許可
 *  - 読み取り専用。ゲームの状態を変更するエンドポイントは持たない
 */
public final class LocalHttpServer {

	public interface Resources {
		/** "index.html" のような相対パスの中身。なければ null。 */
		byte[] read(String path);
	}

	private final ServerSocket serverSocket;
	private final ApiHandler api;
	private final Resources resources;
	private final Set<String> extraOrigins = new HashSet<>();
	private final ExecutorService pool;
	private volatile boolean running;
	private Thread acceptThread;

	public LocalHttpServer(int port, ApiHandler api, Resources resources, Collection<String> extraOrigins) throws IOException {
		this.api = api;
		this.resources = resources;
		if (extraOrigins != null) {
			this.extraOrigins.addAll(extraOrigins);
		}
		serverSocket = new ServerSocket();
		serverSocket.setReuseAddress(true);
		serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50);
		final AtomicInteger n = new AtomicInteger();
		pool = Executors.newFixedThreadPool(6, r -> {
			final Thread t = new Thread(r, "mtrops-http-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	public int port() {
		return serverSocket.getLocalPort();
	}

	public void start() {
		running = true;
		acceptThread = new Thread(this::acceptLoop, "mtrops-http-accept");
		acceptThread.setDaemon(true);
		acceptThread.start();
	}

	public void stop() {
		running = false;
		try {
			serverSocket.close();
		} catch (IOException ignored) {
		}
		pool.shutdownNow();
	}

	private void acceptLoop() {
		while (running) {
			try {
				final Socket socket = serverSocket.accept();
				pool.execute(() -> handle(socket));
			} catch (IOException e) {
				if (!running) {
					return;
				}
			} catch (RuntimeException e) {
				// RejectedExecutionException など。停止中なら抜ける。
				if (!running) {
					return;
				}
			}
		}
	}

	// ------------------------------------------------------------------

	private void handle(Socket socket) {
		try (Socket s = socket) {
			s.setSoTimeout(5000);
			s.setTcpNoDelay(true);
			final InputStream in = new BufferedInputStream(s.getInputStream(), 4096);
			final OutputStream out = s.getOutputStream();

			final String requestLine = readLine(in, 4096);
			if (requestLine == null || requestLine.isEmpty()) {
				return;
			}
			final Map<String, String> headers = new HashMap<>();
			for (int i = 0; i < 64; i++) {
				final String line = readLine(in, 8192);
				if (line == null || line.isEmpty()) {
					break;
				}
				final int c = line.indexOf(':');
				if (c > 0) {
					headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
				}
			}

			final String[] parts = requestLine.split(" ");
			if (parts.length < 2) {
				write(out, 400, "text/plain; charset=utf-8", "bad request".getBytes(StandardCharsets.UTF_8), null, false);
				return;
			}
			final String method = parts[0].toUpperCase(Locale.ROOT);
			final String target = parts[1];

			if (!hostAllowed(headers.get("host"))) {
				write(out, 403, "text/plain; charset=utf-8", "forbidden host".getBytes(StandardCharsets.UTF_8), null, false);
				return;
			}
			final String origin = headers.get("origin");
			final String corsOrigin = origin != null && originAllowed(origin) ? origin : null;
			if (origin != null && corsOrigin == null) {
				write(out, 403, "text/plain; charset=utf-8", "forbidden origin".getBytes(StandardCharsets.UTF_8), null, false);
				return;
			}

			if ("OPTIONS".equals(method)) {
				write(out, 204, "text/plain", new byte[0], corsOrigin, false);
				return;
			}
			if (!"GET".equals(method) && !"HEAD".equals(method)) {
				write(out, 405, "text/plain; charset=utf-8", "method not allowed".getBytes(StandardCharsets.UTF_8), corsOrigin, false);
				return;
			}
			final boolean head = "HEAD".equals(method);

			final int qi = target.indexOf('?');
			final String rawPath = qi < 0 ? target : target.substring(0, qi);
			final Map<String, String> query = qi < 0 ? new HashMap<>() : parseQuery(target.substring(qi + 1));
			final String path = decode(rawPath);

			if (path.startsWith("/api/") || path.equals("/api")) {
				final ApiHandler.Response r = api.handle(path, query);
				write(out, r.code, "application/json; charset=utf-8", r.body.getBytes(StandardCharsets.UTF_8), corsOrigin, head);
				return;
			}

			String rel = path.equals("/") ? "index.html" : path.substring(1);
			if (rel.contains("..") || rel.contains("\\") || rel.startsWith("/") || rel.indexOf('\0') >= 0) {
				write(out, 400, "text/plain; charset=utf-8", "bad path".getBytes(StandardCharsets.UTF_8), corsOrigin, head);
				return;
			}
			final byte[] body = resources == null ? null : resources.read(rel);
			if (body == null) {
				write(out, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8), corsOrigin, head);
				return;
			}
			write(out, 200, contentType(rel), body, corsOrigin, head);
		} catch (IOException ignored) {
			// クライアントが先に切断した等。無視。
		} catch (RuntimeException ignored) {
		}
	}

	private boolean hostAllowed(String host) {
		if (host == null) {
			return false;
		}
		String h = host.toLowerCase(Locale.ROOT);
		if (h.startsWith("[")) {
			final int end = h.indexOf(']');
			h = end > 0 ? h.substring(0, end + 1) : h;
		} else {
			final int c = h.indexOf(':');
			if (c >= 0) {
				h = h.substring(0, c);
			}
		}
		return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("[::1]");
	}

	private boolean originAllowed(String origin) {
		final String o = origin.toLowerCase(Locale.ROOT);
		return o.equals("null")
				|| o.startsWith("http://localhost") || o.startsWith("http://127.0.0.1") || o.startsWith("http://[::1]")
				|| extraOrigins.contains(origin);
	}

	private static String readLine(InputStream in, int max) throws IOException {
		final ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
		int c;
		while ((c = in.read()) >= 0) {
			if (c == '\n') {
				break;
			}
			if (c != '\r') {
				buf.write(c);
			}
			if (buf.size() > max) {
				throw new IOException("line too long");
			}
		}
		if (c < 0 && buf.size() == 0) {
			return null;
		}
		return buf.toString(StandardCharsets.UTF_8);
	}

	private static Map<String, String> parseQuery(String raw) {
		final Map<String, String> map = new HashMap<>();
		for (final String pair : raw.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			final int eq = pair.indexOf('=');
			final String k = decode(eq < 0 ? pair : pair.substring(0, eq));
			final String v = eq < 0 ? "" : decode(pair.substring(eq + 1));
			map.put(k, v);
		}
		return map;
	}

	private static String decode(String s) {
		try {
			return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return s;
		}
	}

	private static String statusText(int code) {
		switch (code) {
			case 200:
				return "OK";
			case 204:
				return "No Content";
			case 400:
				return "Bad Request";
			case 403:
				return "Forbidden";
			case 404:
				return "Not Found";
			case 405:
				return "Method Not Allowed";
			default:
				return "Internal Server Error";
		}
	}

	private static String contentType(String path) {
		final String p = path.toLowerCase(Locale.ROOT);
		if (p.endsWith(".html")) return "text/html; charset=utf-8";
		if (p.endsWith(".css")) return "text/css; charset=utf-8";
		if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript; charset=utf-8";
		if (p.endsWith(".json")) return "application/json; charset=utf-8";
		if (p.endsWith(".svg")) return "image/svg+xml";
		if (p.endsWith(".png")) return "image/png";
		if (p.endsWith(".woff2")) return "font/woff2";
		if (p.endsWith(".ico")) return "image/x-icon";
		return "application/octet-stream";
	}

	private static void write(OutputStream out, int code, String type, byte[] body, String corsOrigin, boolean head) throws IOException {
		final StringBuilder h = new StringBuilder(256);
		h.append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n");
		h.append("Content-Type: ").append(type).append("\r\n");
		h.append("Content-Length: ").append(body.length).append("\r\n");
		h.append("Cache-Control: no-store\r\n");
		h.append("X-Content-Type-Options: nosniff\r\n");
		if (corsOrigin != null) {
			h.append("Access-Control-Allow-Origin: ").append(corsOrigin).append("\r\n");
			h.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
			h.append("Access-Control-Allow-Headers: *\r\n");
			h.append("Access-Control-Allow-Private-Network: true\r\n");
			h.append("Vary: Origin\r\n");
		}
		h.append("Connection: close\r\n\r\n");
		out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
		if (!head) {
			out.write(body);
		}
		out.flush();
	}
}
