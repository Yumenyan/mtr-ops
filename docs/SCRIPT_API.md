# スクリプト向け公開API (`jp.mtrops.api.MtrOpsApi`)

クライアント側で、NTE(Rhino)などのスクリプトから呼べる静的メソッドです。例外は投げず、スレッドセーフです。
値は約0.5秒ごとの同期で更新されます(出庫直後の一瞬は空文字)。

| メソッド | 戻り値 |
|---|---|
| `numberOf(trainId)` | 編成番号(車番)。未割当・未受信は `""` |
| `seriesOf(trainId)` | 系列名(登録した系列、空ならMTRの車両名) |
| `carNumberOf(trainId, carIndex)` | 各車両の車番(`carIndex` は1始まり) |
| `isReady()` | サーバーから最初の列車情報を受信済みか |

```js
const Api = Packages.jp.mtrops.api.MtrOpsApi;
const number = String(Api.numberOf(train.id()));
```

`train.id()` は64bitの整数で、Rhinoでは数値(double)として渡されることがあります。MOD側はdoubleに丸めた値で照合するので、そのまま渡して問題ありません。
MOD未導入の環境では `Packages...` がJavaクラスにならないため、`docs/scripts/numberdisp.js` のように `String(cls).indexOf('JavaClass')` で判定してから使ってください。

## 発車標(ScheduleEntry)から列車の情報を引く

装飾オブジェクト(発車標)のスクリプトで、到着予定の1件(`ScheduleEntry`)ごとに、対応する列車の遅れ・編成番号・車種を取れます。

| メソッド | 戻り値 |
|---|---|
| `entryDelaySec(entry)` | 遅れ(秒)。遅れていない・列車が見つからない場合は `0`(早着も `0`) |
| `entryDelayMin(entry)` | 遅れ(分、切り上げ)。1秒でも遅れていれば1以上 |
| `entryNumber(entry)` | 編成番号。無ければ `""` |
| `entryTypeId(entry)` | 車両ID(mtr_custom_resources.json の `id`、例 `mtr_custom_train_xxx`) |
| `entryTypeName(entry)` | 車両名(同 `name`) |
| `entrySeries(entry)` | 系列名(登録した系列。空なら車両名) |
| `entryTrainId(entry)` | 列車ID(64bitなので文字列) |
| `entryExpectedMillis(entry)` | 到着見込み(UNIXミリ秒、mtrops の補正済み) |

どのメソッドも第2引数にホームIDを渡せます(例 `Api.entryNumber(e, platformId)`)。省略時はMTRの到着予定一覧から探します。

```js
const Api = Packages.jp.mtrops.api.MtrOpsApi;
const e = arrivals.get(i);                     // ScheduleEntry
const late = Api.entryDelaySec(e);             // 例: 120
const no = String(Api.entryNumber(e));         // 例: "1001F"
const name = String(Api.entryTypeName(e));     // 例: "E233系1000番台"
```

### 対応づけの仕組み(列車IDは持たない)
`ScheduleEntry` は路線ID・ホームID・到着時刻を持ちますが、列車IDはありません。そのため、
1. ホームID → 駅ID を MTR のクライアントデータ(`ClientData.DATA_CACHE`)で引く
2. 同じ路線を走り、その駅へ向かっている(または停車中の)列車を、到着の近い順に並べる(次駅までの駅数 → 距離)
3. 同じホーム・同じ路線のエントリを到着時刻順に並べたときの n 番目のエントリ ↔ n 番目の列車、で対応づける

というマッチングをしています。始発前(まだ車庫にいる)の予定や、次駅情報(詳細レベル1以上)が無い場合は該当なし(`""` / `0`)になります。車種(`entryTypeId` など)は詳細レベル3以上のときに取れます。
同じホームの到着予定一覧は `ClientData.SCHEDULES_FOR_PLATFORM` から読みます。読めない版では「直近2秒以内に問い合わせたエントリ」から順位を数えます(到着の早い順に問い合わせるスクリプトを想定)。
MTR 3.2.2 の `ScheduleEntry` はホームIDを持たないため、`ClientData.SCHEDULES_FOR_PLATFORM` のどのホームの一覧に入っているかで判定します。列車との対応は、サーバーから届く到着予想(そのホームに早く着く順)を優先します。

## そのホームを通過する列車(停車しない列車)

停車しない列車は `ScheduleEntry` に出ないため、ホームIDで引く専用のメソッドを使います。`i` は 0 が通過の一番早い列車です。

| メソッド | 戻り値 |
|---|---|
| `passCount(platformId, horizonSec)` | 今から `horizonSec` 秒以内にそのホームを通過する列車の数 |
| `passMillis(platformId, i)` | 通過時刻(UNIXミリ秒、mtrops の補正済み)。無ければ `0` |
| `passInSec(platformId, i)` | 通過までの残り(秒)。無ければ `-1` |
| `passDelaySec(platformId, i)` | 遅れ(秒) |
| `passNumber(platformId, i)` | 編成番号 |
| `passTypeId(platformId, i)` / `passTypeName(platformId, i)` | 車両ID / 車両名 |
| `passRouteId(platformId, i)` / `passTrainId(platformId, i)` | 路線ID / 列車ID(文字列) |

```js
const n = Api.passCount(platformId, 600);        // 10分以内の通過列車
for (let i = 0; i < n; i++) {
  const t = new Date(Api.passMillis(platformId, i));
  // 例: 「通過 12:34 / 1001F」
}
```

通過は、列車の経路のうち「ホームのレールを止まらずに通る箇所」から求めます(経路の残りの1周目まで)。通過時刻は、ホームの手前端に先頭が着く時刻です。
駅の時刻表API(`/api/schedule/駅`)にも `"pass":true` の行として出ます(`departure` は `arrival` と同じ)。列車の `etas` にも `pass` が付きます。
