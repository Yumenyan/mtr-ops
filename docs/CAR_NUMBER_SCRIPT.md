# 車番(各車両)表示のスクリプト対応

ゲーム内での確認は、ユーザー側のテスト結果に基づきます(段階5まででクラッシュなしを確認済みの方式がベース)。
差し替えるのは次の2ファイルです。

- `docs/scripts/numberdisp.js` → `mtr:system/general/numberdisp.js`
- `docs/scripts/train_common.js` → `mtr:system/general/train_common.js`

## 切替(自動検出)
mtrops導入済み・受信済み・その列車に編成番号がある → 新方式。それ以外 → 旧方式(`getNumberLegacy`)。ClientConfig不要。

## 仕組み
1. `getNumber` が新/旧を判定し `state.numNew` を立てる。
2. 新方式のとき `numbermain` は編成共通スロットを透明にし、`processAllCars` → `processCarNumbers` が各車に車番を描く。
3. **DisplayHelper はスクリプトのロード時に固定数だけ作り**、create/render中は作らない。close/disposeはしない(NTEの不具合回避)。
4. 各車は「列車ID:号車」をキーに、近くで描画される車両だけが枠を借りる。1秒描画されなければ他の車両が再利用。
   → 旧版の「事前割当」で枠が尽きて1両だけ/無表示になる問題を解消。

## フォント
フォントは各車種の `numbermain(..., font)` の指定をそのまま使います(`state.numfont`)。
描画は `drawNumberText` に共通化したので、`'Murecho'`/`'OS'`/`'KANIT'`/`'JOST'`/`'METRO'`/`'INTER'`/`'MILL'`/`'NUMBER'` 等、元の全分岐が各車にも適用されます。
`BIZ`/`P`(旧10文字形式前提の配置)は新方式の番号では汎用配置で描きます。

## 車種ごとの位置
init.js の `var slotCfg = ...` の直後に **1行追加**します(slotCfg は train_common の読み込み時にはまだ無く、ロード時に DisplayHelper を作る必要があるため、init側から渡します)。

```js
var slotCfg = { ... };
carNumRegister(slotCfg);            // これだけ
// train_common から slotCfg が見えない環境なら予備として車種IDも渡す:
// carNumRegister(slotCfg, 'e233');  // trainTypeId に 'e233' を含む列車に適用
```
足さない車種は標準位置 `CAR_NUM_CFG_DEFAULT`。

## 負荷・dispose について
枠は 登録ごとに `CAR_NUM_POOL_SIZE`(12)個の固定で、増え続けません。dispose しなくてもメモリは一定です。
車種を多く登録するほど合計が増えるので、重ければ `CAR_NUM_POOL_SIZE` を減らしてください(近くの車両分だけあれば足ります)。
dispose はNTEの不具合リスクが高いため、入れていません。

## 調整
- `CAR_NUM_SEPARATE = false` … 各車描画を止めて編成番号のみ。
- `CAR_NUM_STAGE` … 0〜5。クラッシュ切り分け用(通常は5)。
- 号車は車両インデックスに固定(i=0 が1号車)。折返し・スイッチバックで isReversed が変わっても車番は物理的な車両に付いたままです。

## 注意: ループ内で const を使わない
NTEのRhinoでは `const` が関数スコープ扱いで、forループ内の `const x = ...` が2周目以降も初回の値のまま残ることがあります。
「全車が1号車の車番になる」不具合の原因はこれでした。追加したコードは全て `let` にしています。

## 実験: 編成ごとに確保して dispose で破棄 (CAR_NUM_PER_STATE)
`train_common.js` の `CAR_NUM_PER_STATE = true` で有効。既定は false(共有プール)。
- create 時(initCommonState 内): ロード時に作った base から、その編成の両数ぶんだけ `base.create()`(元の `state.dh = dhBase.create()` と同じ操作)。
- dispose 時: 各init.jsの dispose に **1行追加**が必要です。
  ```js
  function dispose(ctx, state, train) {
      RemoveDoor(state, train);
      carNumDispose(state);      // ★追加(state.dh.close() の前後どちらでも)
      state.dh.close();
  }
  ```
  `carNumDispose` は先に `state.carDhs = null` にしてから close します(以後のrenderが参照しない)。
- 未検証・危険度: NTEの「実行中のdispose/モデルdisposeで落ちる」不具合に当たる可能性があります。
  落ちたら false に戻してください。dispose行を入れ忘れると破棄されず増え続けます。
- 利点: 共有プールの枠数(`CAR_NUM_POOL_SIZE`)の見積もりが不要になり、必要な両数だけ確保されます。
