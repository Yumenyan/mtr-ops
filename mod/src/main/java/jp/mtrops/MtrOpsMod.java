package jp.mtrops;

import jp.mtrops.server.OpsServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** 共通(サーバー)エントリポイント。専用サーバーでもクライアント(統合サーバー)でも動く。 */
public final class MtrOpsMod implements ModInitializer {

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(OpsServer::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> OpsServer.stop());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			final OpsServer ops = OpsServer.get();
			if (ops != null) {
				ops.tick();
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			final OpsServer ops = OpsServer.get();
			if (ops != null) {
				ops.unsubscribe(handler.player);
			}
		});

		// クライアントが接続時に送る。購読登録と、初期データ(トポロジー・台帳)の送信。
		ServerPlayNetworking.registerGlobalReceiver(Net.HELLO, (server, player, handler, buf, responseSender) -> {
			final int protocol = buf.readInt();
			server.execute(() -> {
				final OpsServer ops = OpsServer.get();
				if (ops != null) {
					ops.subscribe(player, protocol);
				}
			});
		});

		// 変更した1車庫分だけを受け取る(1.19.2のC2S上限 32KiB に収まるよう、クライアント側でも事前に検査している)。
		ServerPlayNetworking.registerGlobalReceiver(Net.DEPOT_UPDATE, (server, player, handler, buf, responseSender) -> {
			final byte[] payload = buf.readByteArray();
			server.execute(() -> {
				final OpsServer ops = OpsServer.get();
				if (ops != null) {
					ops.handleDepotUpdate(player, payload);
				}
			});
		});
	}
}
