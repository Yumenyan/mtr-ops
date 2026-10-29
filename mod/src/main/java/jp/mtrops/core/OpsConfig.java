package jp.mtrops.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 設定値。config/mtrops.json から Gson で読み込む(public フィールド名がそのままキー)。
 * 異常検知のしきい値は設計書 §9 の「設定可能にする」に対応。
 */
public final class OpsConfig {

	// ---- 同期 ----
	/** 何tickごとに列車状態をサンプリング・配信するか(20tick=1秒)。 */
	public int sampleTicks = 10;
	/** 1パケットあたりの上限バイト数。超えたら詳細レベルを下げる。 */
	public int maxSnapshotBytes = 200_000;
	/** 送信する最大の詳細レベル(0..4)。 */
	public int maxLevel = 4;

	// ---- 遅延 ----
	public int delaySec = 60;
	public int bigDelaySec = 300;

	// ---- 異常検知 ----
	/** この秒数、停車位置以外/規定時間外で止まっていたら「列車停止」。 */
	public int stoppedSec = 45;
	/** 速度があるのに位置が動かない状態がこの秒数続いたら「位置情報が更新されない」。 */
	public int staleSec = 20;
	public double staleMinMoveM = 0.3;
	/** 次駅への到着予定をこの秒数超過したら「大幅遅延」+異常情報。 */
	public int overdueSec = 240;
	/** 前の列車との間隔(m)がこれ未満の状態が bunchSec 秒続くと情報として通知。 */
	public double bunchGapM = 15;
	public int bunchSec = 60;
	/** 0より大きいとき、前の列車との間隔(m)がこれを超えると情報として通知。 */
	public double maxGapM = 0;
	public int incidentHistory = 100;

	// ---- 車番 ----
	/** 自動採番のとき飛ばす番号。数字の末尾3桁がこれと一致する番号は使わない(忌み番号など)。 */
	public List<String> skipNumberEndings = new ArrayList<>(java.util.Arrays.asList(
			"825", "118", "224", "125", "119", "145", "616", "116", "021", "617", "130", "522", "316", "828", "112",
			"121", "016", "061", "161", "611", "310", "508", "461", "122", "666", "817", "012", "000"));

	// ---- 編集権限 ----
	/** true の場合、車庫台帳の編集にOP権限(レベル2)が必要。false なら MTR と同じ(クリエイティブ/サバイバル)。 */
	public boolean requireOpToEdit = false;

	// ---- カスタムダイヤ ----
	/** 時刻表の時刻の基準にするタイムゾーン(例: "Asia/Tokyo")。空ならサーバーの既定。 */
	public String timeZone = "";

	// ---- ローカルHTTP ----
	public boolean httpEnabled = true;
	public int httpPort = 8765;
	/** 追加で許可するOrigin(例: "https://example.github.io")。localhost系は常に許可。 */
	public List<String> allowedOrigins = new ArrayList<>();

	public void clamp() {
		sampleTicks = Math.max(2, Math.min(100, sampleTicks));
		maxSnapshotBytes = Math.max(20_000, Math.min(900_000, maxSnapshotBytes));
		maxLevel = Math.max(0, Math.min(4, maxLevel));
		httpPort = Math.max(1025, Math.min(65535, httpPort));
		if (skipNumberEndings == null) {
			skipNumberEndings = new ArrayList<>();
		}
		if (allowedOrigins == null) {
			allowedOrigins = new ArrayList<>();
		}
	}
}
