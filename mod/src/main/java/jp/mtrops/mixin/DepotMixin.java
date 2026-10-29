package jp.mtrops.mixin;

import jp.mtrops.adapter.DiaHooks;
import mtr.data.Depot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MTR の Depot.generateTempDepartures(Level) の最後に差し込む(MTRのjarは変更しない)。
 * カスタムダイヤの車庫だけ、作業用の出庫時刻(tempDepartures、保存されない)を時刻表の出庫時刻に置き換える。
 * 車庫に保存されている出庫設定(departures / frequencies / useRealTime)は一切書き換えないので、
 * 通常の鉄道ダッシュボードの表示・動作はそのまま。カスタムダイヤを解除すれば MTR の設定どおりの出庫に戻る。
 */
@Mixin(value = Depot.class, remap = false)
public abstract class DepotMixin {

	@Inject(method = "generateTempDepartures", at = @At("TAIL"), remap = false, require = 0)
	private void mtrops$departures(CallbackInfo ci) {
		DiaHooks.overrideDepartures((Depot) (Object) this);
	}
}
