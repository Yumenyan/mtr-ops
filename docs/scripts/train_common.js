// ============================================
// 共通車両ヘルパー（全車種で共有）
// このファイルは一度だけ読み込まれる想定
// ============================================

// 設定値のグローバルキャッシュ（フレーム毎の再読込を防ぐ）
var _configCache = {
    playhorn: null,
    playbrake: null,
    render_lcd: null,
    lastUpdateTick: -1
};

// 設定値を一定間隔で更新（毎フレームJSON.parseしない）
var _configRateLimit = new RateLimit(1.0); // 1秒に1回で十分
function refreshConfigCache() {
    if (_configRateLimit.shouldUpdate()) {
        try { _configCache.playhorn = JSON.parse(ClientConfig.get('playhorn').toLowerCase()); }
        catch (e) { _configCache.playhorn = false; }

        try { _configCache.playbrake = JSON.parse(ClientConfig.get('playbrake')); }
        catch (e) { _configCache.playbrake = false; }

        _configCache.render_lcd = ClientConfig.get('render_lcd');
    }
}

// 標準初期化（共通のstateプロパティをまとめて設定）
function initCommonState(state, train, airSoundId, pisRateLimit) {
    state.pisRateLimit = new RateLimit(pisRateLimit != undefined ? pisRateLimit : 0.05);
    state.beforespeed = train.speed() * 72;
    state.speedover = 0;
    state.psdn = -1;
    state.runsoundlimit = new RateLimit(0.5);
    state.airplayed = true;
    state.soundair = new TickableSound(Resources.id('mtr:' + (airSoundId || 'air3')));
    _carNumStateAlloc(state, train);   // 実験モード(CAR_NUM_PER_STATE)のときだけ動く
}

// ブレーキ音対応の初期化（ブレーキ音がある車種用）
function initBrakeState(state) {
    state.soundbrake = new TickableSound(Resources.id('mtr:brake'));
    state.brakeready = true;
    state.brakeplayed = false;
}

// 発車ホーン共通処理（getRailIndexの重複呼び出しを解消）
function playPlatformHorn(state, train, sound) {
    let railIndex = train.getRailIndex(train.railProgress() + 10, false);
    if (railIndex > -1
        && train.path().get(railIndex).rail.railType == train.path().get(0).rail.railType.PLATFORM
        && railIndex != state.zenkai2
        && _configCache.playhorn) {
        SoundHelper.play(sound);
        state.zenkai2 = railIndex;
    }
}

// ブレーキ音処理共通化
function processBrakeSound(state, train) {
    let currentSpeedKmvh = train.speed() * 72;

    if (train.speed() == 0 || state.beforespeed + 0.002 < currentSpeedKmvh) {
        state.brakeready = true;
        SoundHelper.stop(state.soundbrake);
    }

    let shouldTriggerBrake = (
        state.beforespeed > currentSpeedKmvh &&
        train.speed() > 0 &&
        currentSpeedKmvh < 140 &&
        state.brakeready &&
        _configCache.playbrake
    );

    if (shouldTriggerBrake) {
        state.soundbrake.setData(1, 1.0, train.lastCarPosition[0]);
        SoundHelper.play(state.soundbrake);
        state.brakeplayed = true;
    }

    if (state.brakeplayed) {
        state.brakeready = false;
        state.brakeplayed = false;
    }

    state.beforespeed = currentSpeedKmvh;
}

// 1両ごとの空気音処理共通化
function playAirForCar(state, train, i, airSoundId) {
    state.soundair.setData(1, 1.0, train.lastCarPosition[i]);
    if (train.speed() > 0 && state.airplayed == false) {
        if (i % 3 > 1 || i == 0) {
            state.airplayed = true;
        }
        if (airSoundId) {
            playAir(state, train, airSoundId);
        }
    }
    if (train.speed() == 0) {
        state.airplayed = false;
    }
}

// クラスター（パンタグラフ等）描画判定共通化
function shouldDrawCluster(carCount, i) {
    if (carCount < 3) {
        return i == 0;
    }
    return i !== 0 && i !== carCount && i % 2 !== 0;
}

// LCD描画距離判定共通化
function shouldRenderLcd(train, i) {
    return _configCache.render_lcd > MinecraftClient.getCameraDistance(train.lastCarPosition[i]);
}


// ============================================
// 各車の車番 (mtrops 連携)  ※位置を車種別にするときだけ init.js に1行(carNumRegister)
//   ・mtrops 導入済み かつ その列車に編成番号がある → 各車に個別の車番を表示
//   ・それ以外 → 従来どおり(numberdisp.js の旧方式)
// ============================================
// true: 新方式のとき numbermain は編成共通スロットを空にし、ここで各車を描く
var CAR_NUM_SEPARATE = true;
// 切り分け用(クラッシュ調査):
//   0 = 各車描画なし(編成共通スロットも従来どおり=旧動作)
//   1 = 車番の取得だけ(Java呼び出し。DisplayHelperは作らない)
//   2 = DisplayHelper を create() するだけ(描かない)
//   3 = + graphicsFor() で文字を描く(uploadしない)
//   4 = + upload()
//   5 = + drawCarModel()  (全部)
var CAR_NUM_STAGE = 5;

// 車番スロットの位置。init.js の number1/number2 と同じ値(標準)。
// 車種ごとの位置は各init.jsで carNumRegister(slotCfg) を呼んで登録します(下記参照)。
var CAR_NUM_CFG_DEFAULT = {
    "version": 1,
    "texSize": [360, 200],
    "slots": [
        { "name": "number1", "texArea": [0, 0, 360, 200],
          "pos": [[[1.23, 0.26, 0.3], [1.23, -0.14, 0.3], [1.23, -0.14, -0.5], [1.23, 0.26, -0.5]]],
          "offsets": [[0.15, 0.1, 0]] },
        { "name": "number2", "texArea": [0, 0, 360, 200],
          "pos": [[[-1.23, 0.26, -0.5], [-1.23, -0.14, -0.5], [-1.23, -0.14, 0.3], [-1.23, 0.26, 0.3]]],
          "offsets": [[-0.15, 0.1, 0]] }
    ]
};
// ---- 各車の車番テクスチャ ------------------------------------------------
// ★DisplayHelper は「スクリプトのロード時(トップレベル)」にだけ作る。create()/render() では作らない。
//   close/dispose は行わない(落ちるため)。枠は固定数で、増え続けないので重くならない。
//
// ■ 車種ごとの位置:
//   各init.js の `var slotCfg = ...` の直後に 1行足すと、その車種の number1/number2 の位置で
//   車番を出せます(slotCfg はこのファイルの読み込み時にはまだ無いため、init側で渡す必要があります):
//       carNumRegister(slotCfg);
//   足さない車種は、標準位置(CAR_NUM_CFG_DEFAULT)で出ます。
//
// ■ 仕組み:
//   各車は「列車ID:号車」をキーに、近くで描画される車両だけが枠を借ります。
//   1秒以上描画されなかった枠は他の車両が再利用します。
// 方式切替: false = 共有プール(安全・実績あり) / true = 編成ごとに必要な両数だけ確保して dispose で破棄(実験的)
//   true にする場合は各init.jsの dispose の中に  carNumDispose(state);  を1行足してください(下記)。
//   足さないと破棄されずに増え続けます(その場合は自動で共有プールにはフォールバックしません)。
var CAR_NUM_PER_STATE = false;
var CAR_NUM_POOL_SIZE = 12;     // 登録(車種)ごとの同時表示枠。目安: 編成両数 × 同時に近くで見える編成数 (6両×2編成=12)
var _carNumReg = {};            // cfgキー -> {cfg, pool:[{dh,name,key,font,text,t}], map:{}}

function _carNumPick(src) {
    let picked = [];
    let srcSlots = src.slots;
    for (let i = 0; i < srcSlots.length; i++) {
        let nm = String(srcSlots[i].name);
        if (nm === 'number1' || nm === 'number2') {
            picked.push({ "name": nm, "texArea": [0, 0, 360, 200], "pos": srcSlots[i].pos, "offsets": srcSlots[i].offsets });
        }
    }
    return picked;
}

function _carNumKeyOf(src) {
    return JSON.stringify(_carNumPick(src));
}

function _carNumEnsure(cfgSrc) {
    // cfgSrc: slotCfg 形式、または CAR_NUM_CFG_DEFAULT
    let slots = _carNumPick(cfgSrc);
    if (slots.length === 0) return null;
    let key = JSON.stringify(slots);
    if (_carNumReg[key] !== undefined) return key;
    try {
        let cfg = { "version": 1, "texSize": [360, 200], "slots": slots };
        let base = new DisplayHelper(cfg);
        let pool = [];
        for (let i = 0; i < (CAR_NUM_PER_STATE ? 1 : CAR_NUM_POOL_SIZE); i++) {
            pool.push({ dh: base.create(), name: slots[0].name, key: null, font: null, text: null, t: 0 });
        }
        _carNumReg[key] = { pool: pool, map: {}, base: base };
        return key;
    } catch (e) {
        return null;
    }
}

// init.js のトップレベル(slotCfg の直後)から呼ぶ。ロード時なので DisplayHelper を作ってよい。
// matchId(省略可): slotCfg が train_common から見えない場合の予備。trainTypeId にこの文字列が含まれる列車に使う。
var _carNumMatches = [];
function carNumRegister(slotCfgObj, matchId) {
    let k = _carNumEnsure(slotCfgObj);
    if (k !== null && matchId !== undefined) _carNumMatches.push({ id: String(matchId), key: k });
    return k;
}

var _carNumDefaultKey = _carNumEnsure(CAR_NUM_CFG_DEFAULT);

// この列車に使う登録を決める(init.js の slotCfg が見えて登録済みならそれ、無ければ標準)
function _carNumRegFor(train) {
    try {
        if (typeof slotCfg !== 'undefined' && slotCfg && slotCfg.slots) {
            let k = _carNumKeyOf(slotCfg);
            if (_carNumReg[k] !== undefined) return _carNumReg[k];
        }
    } catch (e) {}
    try {
        let tid = String(train.trainTypeId());
        for (let i = 0; i < _carNumMatches.length; i++) {
            if (tid.indexOf(_carNumMatches[i].id) >= 0) return _carNumReg[_carNumMatches[i].key];
        }
    } catch (e) {}
    return _carNumDefaultKey !== null ? _carNumReg[_carNumDefaultKey] : null;
}

function _carNumBorrow(reg, key, now) {
    let slot = reg.map[key];
    if (slot !== undefined && slot.key === key) {
        return slot;
    }
    // 空き(未使用/1秒以上未使用)を探す
    let victim = null;
    for (let i = 0; i < reg.pool.length; i++) {
        let c = reg.pool[i];
        if (c.key === null) { victim = c; break; }
        if (now - c.t > 1000 && (victim === null || c.t < victim.t)) victim = c;
    }
    if (victim === null) return null;
    if (victim.key !== null) delete reg.map[victim.key];
    victim.key = key;
    victim.text = null;
    victim.font = null;
    reg.map[key] = victim;
    return victim;
}


// ---- 実験: 編成(state)ごとの確保 ---------------------------------------
// create 時: ロード時に作った base から base.create() するだけ(元の state.dh = dhBase.create() と同じ操作)。
function _carNumStateAlloc(state, train) {
    state.carDhs = null;
    if (!CAR_NUM_PER_STATE || !CAR_NUM_SEPARATE || CAR_NUM_STAGE < 2) return;
    try {
        let reg = _carNumRegFor(train);
        if (reg === null || reg.base === undefined) return;
        let n = train.trainCars();
        let arr = [];
        for (let i = 0; i < n; i++) {
            arr.push({ dh: reg.base.create(), name: reg.pool[0].name, font: null, text: null });
        }
        state.carDhs = arr;
    } catch (e) {
        state.carDhs = null;
    }
}

// 各init.js の dispose から呼ぶ。state.dh.close() と同じ要領で閉じる。
function carNumDispose(state) {
    let arr = state.carDhs;
    state.carDhs = null;          // 先に外して、以後の render から参照されないようにする
    if (!arr) return;
    for (let i = 0; i < arr.length; i++) {
        try { arr[i].dh.close(); } catch (e) {}
    }
}

// 新方式のとき各車に車番を描く
function processCarNumbers(ctx, state, train) {
    if (CAR_NUM_STAGE <= 0 || !CAR_NUM_SEPARATE || !state.numNew || typeof carNumberFor !== 'function') return;
    let reg = _carNumRegFor(train);
    if (reg === null) return;
    let n = train.trainCars();
    let now = Date.now();
    let tid = String(train.id());
    for (let i = 0; i < n; i++) {
        if (!shouldRenderLcd(train, i)) continue;
        // 車番は「物理的な車両」に付くので、折返し/スイッチバックで isReversed が変わっても
        // 車両インデックス i と車番の対応は変えない(i=0 が常に 1号車)。
        let idx = i + 1;
        let text = carNumberFor(train, idx);
        if (text === '') continue;
        if (CAR_NUM_STAGE < 2) continue;
        let slot;
        if (CAR_NUM_PER_STATE) {
            slot = state.carDhs ? state.carDhs[i] : null;
        } else {
            slot = _carNumBorrow(reg, tid + ':' + i, now);
        }
        if (!slot) continue;
        slot.t = now;
        if (slot.text !== text || slot.font !== state.numfont) {
            if (CAR_NUM_STAGE >= 3) drawCarNumText(slot.dh.graphicsFor(slot.name), text, state.numfont);
            if (CAR_NUM_STAGE >= 4) slot.dh.upload();
            slot.text = text;
            slot.font = state.numfont;
        }
        if (CAR_NUM_STAGE >= 5) ctx.drawCarModel(slot.dh.model, i, null);
    }
}

// 全車両共通のrenderループ（各両ごとの処理をまとめて実行）
function processAllCars(ctx, state, train, options) {
    // options: { airSoundId, drawLcd, lcdModel }
    let carCount = train.trainCars();
    processCarNumbers(ctx, state, train);   // ★追加: 各車の車番
    for (let i = 0; i < carCount; i++) {
        playAirForCar(state, train, i, options.airSoundId);

        if (options.drawLcd && shouldRenderLcd(train, i)) {
            ctx.drawCarModel(options.lcdModel, i, null);
        }

        if (shouldDrawCluster(carCount, i)) {
            ctx.drawCarModel(options.cluster, i, null);
        }
    }
}