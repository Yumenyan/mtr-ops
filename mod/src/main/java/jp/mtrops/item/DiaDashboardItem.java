package jp.mtrops.item;

import jp.mtrops.server.OpsServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * ダイヤ編成ツール。MTRの鉄道ダッシュボード(ItemDashboard)と同じく、使うと画面を開く。
 * MTRのダッシュボードには一切手を入れず、路線・車庫・ホームなどはMTRで作ったものをそのまま読むだけ。
 * 画面を開く指示はサーバーから送る(MTRと同じ方式。専用サーバーでクライアント側のクラスを読み込まないため)。
 */
public final class DiaDashboardItem extends Item {

	public DiaDashboardItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
		if (!world.isClientSide() && player instanceof ServerPlayer) {
			final OpsServer ops = OpsServer.get();
			if (ops != null) {
				ops.openDiaDashboard((ServerPlayer) player);
			}
		}
		return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), world.isClientSide());
	}
}
