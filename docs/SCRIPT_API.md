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
