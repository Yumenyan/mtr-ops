package jp.mtrops.mixin;

import jp.mtrops.adapter.DiaHooks;
import mtr.data.Train;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MTR の Train.simulateTrain(Level, float, Depot) に差し込む(MTRのjarは変更しない)。
 *  ・getRailSpeed の呼び出しを置き換え: カスタムダイヤの列車だけ、ATC/ATS の速度制限と回復運転を反映
 *  ・最後に停車時間の調整(発車時刻まで待つ/遅れているときは詰める)
 * カスタムダイヤでない列車・クライアント側の列車は、MTR の値をそのまま使う。
 *
 * このパッケージにはミックスイン以外のクラスを置かないこと(ミックスインのパッケージは他のクラスから参照できない)。
 */
@Mixin(value = Train.class, remap = false)
public abstract class TrainMixin {

	@Redirect(method = "simulateTrain", at = @At(value = "INVOKE", target = "Lmtr/data/Train;getRailSpeed(I)F"), remap = false, require = 0)
	private float mtrops$railSpeed(Train self, int railIndex) {
		return DiaHooks.railSpeed(self, railIndex);
	}

	@Inject(method = "simulateTrain", at = @At("TAIL"), remap = false, require = 0)
	private void mtrops$afterSimulate(CallbackInfo ci) {
		DiaHooks.afterSimulate((Train) (Object) this);
	}
}
