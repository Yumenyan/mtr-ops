package jp.mtrops;

import net.minecraft.resources.ResourceLocation;

/**
 * ネットワークチャンネルとプロトコル。
 *
 *  C2S HELLO         クライアントが接続時に送る(プロトコル版)。サーバーは購読者に登録して最新データを送る
 *  S2C TOPOLOGY      路線・駅・車庫・車庫線(変更時のみ)
 *  S2C REGISTRY      全車庫の車庫台帳(編成・車番)。更新のたびに全車庫分を同期
 *  S2C SNAPSHOT      列車スナップショット(SnapshotCodec。上限超過時は詳細レベルを下げる/分割)
 *  S2C GEOMETRY      駅間ごとの距離と線路の線形(変更時のみ。複数パートに分割)
 *  C2S DEPOT_UPDATE  変更した1車庫分だけを送る。全車庫の丸ごと上書きは行わない
 *  S2C EDIT_RESULT   DEPOT_UPDATE / DIA_UPDATE の結果(コード + メッセージ)
 *  S2C DIA           カスタムダイヤ: 設定・理想走行・タイムゾーン(変更時)
 *  S2C DIA_LIVE      カスタムダイヤ: 列車ごとの便・遅れ(数秒ごと)
 *  C2S DIA_UPDATE    カスタムダイヤの設定(全体。rev付き。ダイヤ編成ツールから)
 *  S2C OPEN_DIA      ダイヤ編成ツール(アイテム)を使った → クライアントで画面を開く
 *  S2C ETAS          列車ごとの到着予想(折り返し後の路線も含め2時間先まで。約1秒ごと)
 *  S2C SCHEDULE      全車庫の理想走行・今後の出庫時刻・駅間の所要時間の実績(時刻表API用、約20秒ごと)
 *
 * 1.19.2 のカスタムペイロード上限: サーバー→クライアント 1MiB(1,048,576) / クライアント→サーバー 32KiB(32,767)。
 */
public final class Net {

	public static final String MOD_ID = "mtrops";
	public static final int PROTOCOL = 9;

	public static final ResourceLocation HELLO = new ResourceLocation(MOD_ID, "hello");
	public static final ResourceLocation TOPOLOGY = new ResourceLocation(MOD_ID, "topology");
	public static final ResourceLocation REGISTRY = new ResourceLocation(MOD_ID, "registry");
	public static final ResourceLocation SNAPSHOT = new ResourceLocation(MOD_ID, "snapshot");
	public static final ResourceLocation GEOMETRY = new ResourceLocation(MOD_ID, "geometry");
	public static final ResourceLocation DIA = new ResourceLocation(MOD_ID, "dia");
	public static final ResourceLocation DIA_LIVE = new ResourceLocation(MOD_ID, "dia_live");
	public static final ResourceLocation DIA_UPDATE = new ResourceLocation(MOD_ID, "dia_update");
	public static final ResourceLocation OPEN_DIA = new ResourceLocation(MOD_ID, "open_dia");
	public static final ResourceLocation SCHEDULE = new ResourceLocation(MOD_ID, "schedule");
	public static final ResourceLocation ETAS = new ResourceLocation(MOD_ID, "etas");
	public static final ResourceLocation DEPOT_UPDATE = new ResourceLocation(MOD_ID, "depot_update");
	public static final ResourceLocation EDIT_RESULT = new ResourceLocation(MOD_ID, "edit_result");

	/** EDIT_RESULT のコード。0〜4 は OpsRegistry.Result の ordinal と同じ。 */
	public static final int RESULT_NO_PERMISSION = 100;
	public static final int RESULT_UNKNOWN_DEPOT = 101;
	public static final int RESULT_BAD_PAYLOAD = 102;
	public static final int RESULT_DIA_REJECTED = 103;
	/** カスタムダイヤの保存に成功(EDIT_RESULT のコード) */
	public static final int RESULT_DIA_OK = 200;

	/** ペイロードの安全な最大長(S2Cは1MiB未満に抑える) */
	public static final int MAX_S2C_BYTES = 900_000;

	private Net() {
	}
}
