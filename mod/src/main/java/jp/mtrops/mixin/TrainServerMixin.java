package jp.mtrops.mixin;

import jp.mtrops.adapter.DiaHooks;
import mtr.data.TrainServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MTR の TrainServer.simulateTrain(…)(戻り値 = クライアントへ同期するか)に差し込む。
 * カスタムダイヤで停車時間や速度を変えた列車は、0.5秒ごとに同期させてクライアントの表示とずれないようにする。
 */
@Mixin(value = TrainServer.class, remap = false)
public abstract class TrainServerMixin {

	@Inject(method = "simulateTrain", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private void mtrops$sync(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && DiaHooks.forceSync((TrainServer) (Object) this)) {
			cir.setReturnValue(true);
		}
	}
}
