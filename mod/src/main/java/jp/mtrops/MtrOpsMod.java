package jp.mtrops;

import jp.mtrops.item.DiaDashboardItem;
import jp.mtrops.server.OpsServer;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** 共通(サーバー)エントリポイント。専用サーバーでもクライアント(統合サーバー)でも動く。 */
public final class MtrOpsMod implements ModInitializer {

	/** ダイヤ編成ツール(MTRの鉄道ダッシュボードの亜種。カスタムダイヤの設定はすべてこの中で行う) */
	public static final Item DIA_DASHBOARD = new DiaDashboardItem(new Item.Properties().stacksTo(1).tab(CreativeModeTab.TAB_TRANSPORTATION));

	@Override
	public void onInitialize() {
		Registry.register(Registry.ITEM, new ResourceLocation(Net.MOD_ID, "dia_dashboard"), DIA_DASHBOARD);

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

		// カスタムダイヤの設定(圧縮済み。ダイヤ編成ツールから)
		ServerPlayNetworking.registerGlobalReceiver(Net.DIA_UPDATE, (server, player, handler, buf, responseSender) -> {
			// ネットワークスレッドで例外を出すと接続が切れるので、読み取りの失敗は握りつぶして拒否の返事にする
			int rev = -1;
			byte[] data = null;
			try {
				rev = buf.readInt();
				data = buf.readByteArray(32_000);
			} catch (RuntimeException e) {
				data = null;
			}
			final int baseRev = rev;
			final byte[] payload = data;
			server.execute(() -> {
				final OpsServer ops = OpsServer.get();
				if (ops != null) {
					ops.handleDiaUpdate(player, baseRev, payload == null ? new byte[0] : payload);
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
