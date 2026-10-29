package jp.mtrops.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import jp.mtrops.ConfigLoader;
import jp.mtrops.Net;
import jp.mtrops.adapter.MtrClientAdapter;
import jp.mtrops.core.ApiHandler;
import jp.mtrops.core.ClientStore;
import jp.mtrops.core.GeometryCodec;
import jp.mtrops.core.LocalHttpServer;
import jp.mtrops.core.OpsConfig;
import jp.mtrops.core.OpsRegistry;
import jp.mtrops.core.RegistryCodec;
import jp.mtrops.core.SnapshotCodec;
import jp.mtrops.core.TopologyCodec;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * クライアント側(設計書 §2): サーバーから受け取ったデータをキャッシュし、ローカルHTTP APIで公開する。
 * HTTPサーバーは 127.0.0.1 のみ。Webアプリ本体もjarに同梱し、同じポートから配信する。
 */
public final class OpsClient {

	private static final Logger LOGGER = LoggerFactory.getLogger("mtrops/client");

	public static final ClientStore STORE = new ClientStore();

	private static OpsConfig cfg;
	private static LocalHttpServer http;
	private static int httpPort = -1;
	private static boolean openGuiRequested;
	private static volatile boolean diaPending;
	private static boolean openDiaRequested;
	private static boolean diaCanEdit = true;
	private static long railTick;
	private static final Map<String, byte[]> RESOURCE_CACHE = new ConcurrentHashMap<>();

	private OpsClient() {
	}

	public static void init() {
		cfg = ConfigLoader.load();

		// --- 受信 ---
		ClientPlayNetworking.registerGlobalReceiver(Net.TOPOLOGY, (client, handler, buf, sender) -> {
			final byte[] bytes = buf.readByteArray();
			try {
				STORE.setTopology(TopologyCodec.decode(bytes));
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("トポロジーの受信に失敗: {}", e.toString());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Net.REGISTRY, (client, handler, buf, sender) -> {
			final byte[] bytes = buf.readByteArray();
			try {
				STORE.setRegistry(RegistryCodec.decodeRegistry(bytes));
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("車庫台帳の受信に失敗: {}", e.toString());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Net.GEOMETRY, (client, handler, buf, sender) -> {
			final byte[] bytes = buf.readByteArray();
			try {
				STORE.acceptGeometryPart(GeometryCodec.decode(bytes));
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("線形データの受信に失敗: {}", e.toString());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Net.SNAPSHOT, (client, handler, buf, sender) -> {
			final byte[] bytes = buf.readByteArray();
			try {
				STORE.acceptSnapshotPart(SnapshotCodec.decode(bytes));
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("スナップショットの受信に失敗: {}", e.toString());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Net.EDIT_RESULT, (client, handler, buf, sender) -> {
			final int code = buf.readInt();
			final String message = buf.readUtf();
			if (code == Net.RESULT_DIA_OK || code == Net.RESULT_DIA_REJECTED || diaPending && (code == Net.RESULT_NO_PERMISSION || code == Net.RESULT_BAD_PAYLOAD)) {
				diaPending = false;
				STORE.setDiaResult(code, message);
			} else {
				STORE.setEditResult(code, message);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Net.DIA, (client, handler, buf, sender) -> {
			final byte[] bytes = buf.readByteArray();
			try {
				STORE.setDia(jp.mtrops.core.DiaSync.decode(bytes));
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("カスタムダイヤの受信に失敗: {}", e.toString());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Net.DIA_LIVE, (client, handler, buf, sender) -> {
			final byte[] bytes = buf.readByteArray();
			try {
				STORE.setDiaLive(jp.mtrops.core.DiaSync.decodeLive(bytes));
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("カスタムダイヤ(運行状況)の受信に失敗: {}", e.toString());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Net.ETAS, (client, handler, buf, sender) -> {
			final byte[] bytes = buf.readByteArray();
			try {
				STORE.acceptEtaPart(jp.mtrops.core.EtaCodec.decode(bytes));
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("到着予想の受信に失敗: {}", e.toString());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Net.SCHEDULE, (client, handler, buf, sender) -> {
			final byte[] bytes = buf.readByteArray();
			try {
				STORE.setSchedule(jp.mtrops.core.ScheduleSync.decode(bytes));
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("時刻表データの受信に失敗: {}", e.toString());
			}
		});
		// ダイヤ編成ツール(アイテム)を使った → 画面を開く(ネットワークスレッドから届くので次のtickで開く)
		ClientPlayNetworking.registerGlobalReceiver(Net.OPEN_DIA, (client, handler, buf, sender) -> {
			final boolean canEdit = buf.readBoolean();
			client.execute(() -> {
				diaCanEdit = canEdit;
				openDiaRequested = true;
			});
		});

		// --- 接続 / 切断 ---
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			final FriendlyByteBuf hello = PacketByteBufs.create();
			hello.writeInt(Net.PROTOCOL);
			ClientPlayNetworking.send(Net.HELLO, hello);
			startHttpIfNeeded();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> STORE.clear());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stopHttp());

		// --- 操作: キー(既定は未割り当て)とコマンド ---
		final KeyMapping openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mtrops.open_manager", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.mtrops"));
		final KeyMapping webKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mtrops.open_web", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, "key.categories.mtrops"));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				openGuiRequested = true;
			}
			// レールの形と種別(/api/rails)を約5秒ごとに写す(変化が無ければ作り直さない)
			if (client.level != null && ++railTick % 100 == 1) {
				try {
					final jp.mtrops.core.RailNetwork.Snapshot g = MtrClientAdapter.snapshotRails(client.level.dimension().location().toString(), false);
					if (g != null) {
						STORE.setRails(g);
					}
				} catch (RuntimeException | LinkageError e) {
					// レールの写しに失敗しても他の機能は続ける
				}
			}
			if (openDiaRequested && client.player != null) {
				openDiaRequested = false;
				client.setScreen(new DiaDashboardScreen(diaCanEdit));
			}
			while (webKey.consumeClick()) {
				// 閲覧だけ(読み取り専用API)なので権限不要。編集の権限はサーバー側で保存時に検査する
				if (httpPort > 0) {
					Util.getPlatform().openUri(webUrl());
				} else if (client.player != null) {
					client.player.displayClientMessage(Component.literal("運行情報Web: " + webUrl()), true);
				}
			}
			// チャット画面が閉じた後に開く必要があるため、次のtickで開く
			if (openGuiRequested && client.player != null) {
				openGuiRequested = false;
				client.setScreen(new DepotManagerScreen());
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("mtrops")
						.executes(ctx -> {
							openGuiRequested = true;
							return Command.SINGLE_SUCCESS;
						})
						.then(ClientCommandManager.literal("web").executes(ctx -> {
							final String url = webUrl();
							ctx.getSource().sendFeedback(Component.literal("運行情報Web: " + url));
							if (httpPort > 0) {
								Util.getPlatform().openUri(url);
							}
							return Command.SINGLE_SUCCESS;
						}))
						.then(ClientCommandManager.literal("dia").executes(ctx -> {
							// 閲覧用(保存はサーバーで権限を確認する)。アイテム「ダイヤ編成ツール」からも開ける
							openDiaRequested = true;
							return Command.SINGLE_SUCCESS;
						}))
						.then(ClientCommandManager.literal("url").executes(ctx -> {
							ctx.getSource().sendFeedback(Component.literal("運行情報Web: " + webUrl()));
							return Command.SINGLE_SUCCESS;
						}))
						.then(ClientCommandManager.literal("status").executes(ctx -> {
							final OpsRegistry reg = STORE.registry();
							ctx.getSource().sendFeedback(Component.literal(
									"mtrops: 接続=" + (STORE.live() != null) + " / 列車=" + (STORE.live() == null ? 0 : STORE.live().trains.size())
											+ " / 台帳の車庫=" + reg.depots.size() + " / HTTP=" + webUrl()));
							return Command.SINGLE_SUCCESS;
						}))));
	}

	/** カスタムダイヤの設定をサーバーへ送る。失敗理由、送れたら null。どのスレッドから呼んでもよい。 */
	public static String sendDia(jp.mtrops.core.DiaModel.Config config, int baseRev) {
		final String err = config.validate();
		if (err != null) {
			return err;
		}
		final byte[] packed = jp.mtrops.core.DiaSync.deflate(jp.mtrops.core.DiaCodec.encodeConfig(config));
		if (packed.length > 30_000) {
			return "ダイヤの設定が大きすぎて送れません(圧縮後 " + packed.length / 1024 + "KB / 上限 約30KB)。追加便・取りやめ便を減らしてください";
		}
		final Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null) {
			return "ゲームに接続していないため保存できません";
		}
		diaPending = true;
		mc.execute(() -> {
			if (mc.getConnection() != null) {
				final FriendlyByteBuf buf = PacketByteBufs.create();
				buf.writeInt(baseRev);
				buf.writeByteArray(packed);
				ClientPlayNetworking.send(Net.DIA_UPDATE, buf);
			}
		});
		return null;
	}

	// ------------------------------------------------------------------ HTTP

	public static String webUrl() {
		return httpPort > 0 ? "http://127.0.0.1:" + httpPort + "/" : "(HTTPサーバーは起動していません)";
	}

	private static synchronized void startHttpIfNeeded() {
		if (http != null || !cfg.httpEnabled) {
			return;
		}
		for (int port = cfg.httpPort; port < cfg.httpPort + 10; port++) {
			try {
				final LocalHttpServer server = new LocalHttpServer(port, new ApiHandler(STORE, MtrClientAdapter::typeName), OpsClient::readResource, cfg.allowedOrigins);
				server.start();
				http = server;
				httpPort = server.port();
				LOGGER.info("ローカル運行情報APIを起動しました: http://127.0.0.1:{}/", httpPort);
				return;
			} catch (IOException e) {
				// ポート使用中(複数インスタンス起動など)。次のポートを試す。
			}
		}
		LOGGER.warn("ローカルHTTPサーバーを起動できませんでした(ポート {}〜{} が使用中)", cfg.httpPort, cfg.httpPort + 9);
	}

	private static synchronized void stopHttp() {
		if (http != null) {
			http.stop();
			http = null;
			httpPort = -1;
		}
	}

	private static byte[] readResource(String path) {
		final byte[] cached = RESOURCE_CACHE.get(path);
		if (cached != null) {
			return cached.length == 0 ? null : cached;
		}
		byte[] data;
		try (InputStream in = OpsClient.class.getResourceAsStream("/assets/mtrops/web/" + path)) {
			data = in == null ? new byte[0] : in.readAllBytes();
		} catch (IOException e) {
			data = new byte[0];
		}
		RESOURCE_CACHE.put(path, data);
		return data.length == 0 ? null : data;
	}
}
