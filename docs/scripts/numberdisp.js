if (typeof getShared === 'undefined') include('mtr:system/general/resource_cache.js');
let traintxt1 = Resources.readString(Resources.id('seiyopack:data/train.txt'))
let s_txt = Resources.readString(Resources.id('seiyopack:data/siding.txt'));
let ayadenlist = Resources.readString(Resources.id('seiyopack:data/ayaden.txt'));
include('mtr:system/general/fare.js');
importPackage(java.io, java.net);
importClass(java.nio.charset.StandardCharsets);
importClass(java.lang.System);
let alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
let Hiragana = 'あいうえおかきくけこさすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわを'
let data = [];
let ran = false;
let apicycle = new CycleTracker(['api', 0.5])
let count = 0;
let executed = false;
let genzai;
let trainstart;
let trainend;
let banned;
let tmp;
let trainspace;
let rep = '';
let zenkaimap;
let trains;
let trainnumtemp;
let trmap = [];
let _se = Resources.readString(Resources.id('mtr:system/images/se.txt')).split('\n');
let _exp = Resources.readString(Resources.id('mtr:system/images/exp.txt')).split('\n');
let amayaid = Resources.readString(Resources.id('mtr:system/images/amaya.txt')).split('\n');

function isAmaya(train) {
    for (let s = 0; lc[s] !== undefined; s++) {
        if (amayaid[s] == train.trainTypeId()) {
            return true;
        }
    }
    return false;
}

importClass(java.lang.Long);
function SetValue(obj, name, value) {
    let field = obj.getClass().getSuperclass().getDeclaredField(name);
    field.setAccessible(true);
    return field.set(obj, value);
}

function GetValue(obj, name) {
    let field = obj.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(obj);
}

function getNumberLegacy(state, train, type) {
    rep = '';
    const siding = train.siding();
    if (siding == null) {
        state.trainnum = '';
    } else {
        if (siding.name.length() > 0) {
            if (siding.name.substring(0, 1) == '#') {
                if (siding.name.indexOf('@') > -1) {
                    let trainstart = java.lang.String.valueOf(siding.name.substring(siding.name.indexOf('@'), siding.name.indexOf('~')));
                    if (alphabet.indexOf(siding.name.substring(siding.name.indexOf('@'), siding.name.indexOf('@') + 1)) > -1) {
                        rep = siding.name.substring(siding.name.indexOf('@'), siding.name.indexOf('@') + 1).toUpperCase();
                    }
                    trainstart = trainstart.replaceAll("[^0-9]", "");
                    if (siding.name.indexOf(',', siding.name.indexOf('~') + 1) > -1) {
                        trainend = java.lang.String.valueOf(siding.name.substring(siding.name.indexOf('~') + 1, siding.name.indexOf(',', siding.name.indexOf('~') + 1))).replaceAll("[^0-9]", "");
                    } else {
                        trainend = java.lang.String.valueOf(siding.name.substring(siding.name.indexOf('~') + 1, siding.name.length())).replaceAll("[^0-9]", "");
                    }
                    trainspace = (trainend - trainstart) + 1;
                    if (trainspace < 1) {
                        trainspace = 1;
                    }
                    trainnumtemp = java.lang.String.valueOf(parseInt(trainstart) + Math.abs(Math.floor(train.id() / 100000000) % trainspace));
                    if (trainnumtemp.indexOf('.0') > -1) {
                        trainnumtemp = trainnumtemp.substring(0, trainnumtemp.indexOf('.0'));
                    }
                    state.trainnum = siding.name.substring(1, siding.name.indexOf('@')) + trainnumtemp.padStart(4, '・');
                } else {
                    state.trainnum = siding.name.substring(1, siding.name.length());
                }
                trainspace = 999;
            } else if (s_txt.indexOf(siding.name) > -1 && !(siding.name < 100000)) {
                trainstart = s_txt.substring(s_txt.indexOf(siding.name) + siding.name.length(), s_txt.indexOf(';', s_txt.indexOf(siding.name)));
                tmp = s_txt.indexOf(trainstart) + 1 + trainstart.length()
                trainend = s_txt.substring(tmp, s_txt.indexOf(';', tmp))
                trainspace = parseInt(trainend) - parseInt(trainstart) + 1;
                if (trainspace < 1) {
                    trainspace = 1;
                }
                state.trainnum = parseInt(trainstart) + Math.abs(Math.floor(train.id() / 100000000) % trainspace);
                if (state.trainnum > 0 && state.trainnum < 99999999) {

                } else {
                    state.trainnum = '';
                }
            } else if (siding.name.substring(0, 1) == '@' && siding.name.indexOf('~') > -1) {
                let trainstart = java.lang.String.valueOf(siding.name.substring(1, siding.name.indexOf('~')));
                if (alphabet.indexOf(trainstart.substring(0, 1).toUpperCase()) > -1) {
                    rep = trainstart.substring(0, 1).toUpperCase();
                }
                trainstart = trainstart.replaceAll("[^0-9]", "");
                if (siding.name.indexOf(',', siding.name.indexOf('~') + 1) > -1) {
                    trainend = java.lang.String.valueOf(siding.name.substring(siding.name.indexOf('~') + 1, siding.name.indexOf(',', siding.name.indexOf('~') + 1))).replaceAll("[^0-9]", "");
                } else {
                    trainend = java.lang.String.valueOf(siding.name.substring(siding.name.indexOf('~') + 1, siding.name.length())).replaceAll("[^0-9]", "");
                }
                trainspace = (trainend - trainstart) + 1;
                if (trainspace < 1) {
                    trainspace = 1;
                }
                state.trainnum = parseInt(trainstart) + Math.abs(Math.floor(train.id() / 100000000) % trainspace);
                if (state.trainnum > 0 && state.trainnum < 99999999) {
                } else {
                    state.trainnum = '';
                }
            } else {
                if (traintxt1.indexOf(train.trainTypeId()) > -1 && train.trainTypeId() != 'mtr_custom_train_ints_466') {
                    trainstart = traintxt1.substring(traintxt1.indexOf(train.trainTypeId()) + train.trainTypeId().length(), traintxt1.indexOf(';', traintxt1.indexOf(train.trainTypeId())));
                    tmp = traintxt1.indexOf(trainstart) + trainstart.length() + 1
                    if (alphabet.indexOf(trainstart.substring(1, 2).toUpperCase()) > -1) {
                        rep = trainstart.substring(1, 2).toUpperCase();
                    }
                    trainstart = trainstart.replaceAll("[^0-9]", "");
                    trainend = traintxt1.substring(tmp + 1, traintxt1.indexOf(';', tmp)).replaceAll("[^0-9]", "");
                    trainspace = parseInt(trainend) - parseInt(trainstart) + 1;
                    if (trainspace < 1) {
                        trainspace = 1;
                    }
                    state.trainnum = parseInt(trainstart) + Math.abs(Math.floor(train.id() / 100000000) % trainspace);
                    if (state.trainnum > 0 && state.trainnum < 99999999) {
                    } else {
                        state.trainnum = '';
                    }
                } else {
                    state.trainnum = '';
                }
            }
        }
        state.trainnum = java.lang.String.valueOf(state.trainnum);
        banned = [
            '825', '118', '224', '125', '119', '145', '616', '116', '021', '617', '130', '522', '316', '828', '112', '121', '016', '061', '161', '611', '310', '508', '461', '122', '666', '817', '012', '000'
        ]
        if (state.trainnum.indexOf('.0') > -1) {
            state.trainnum = state.trainnum.substring(0, state.trainnum.indexOf('.0'));
        }
        let fusei = -1;
        let save = java.lang.String.valueOf(state.trainnum);
        let ban = true;
        state.trainnum = java.lang.String.valueOf(state.trainnum);
        if (state.trainnum.indexOf('.0') > -1) {
            state.trainnum = state.trainnum.substring(0, state.trainnum.indexOf('.0'))
        }
        for (let c = 0; banned[c] !== undefined; c++) {
            if (state.trainnum.lastIndexOf(banned[c]) == java.lang.String.valueOf(state.trainnum).length() - 3 && parseInt(state.trainnum) < 99999999 && java.lang.String.valueOf(state.trainnum).length() > 2 && state.trainnum != '116116' && state.trainnum != '323853' && state.trainnum != '323840') {
                if (siding.name.substring(0, 1) == '#') {
                    fusei = save;
                }
                if (siding.name.substring(0, 1) == '@' && siding.name.indexOf('~') > -1) {
                    if (parseInt(siding.name.substring(siding.name.indexOf('~') + 1, siding.name.length())) - parseInt(siding.name.substring(1, siding.name.indexOf('~'))) < 2) {
                        fusei = save;
                    }
                }
                if (siding.name.substring(0, 1) == '@' && siding.name.indexOf(':') > -1) {
                    if (parseInt(siding.name.substring(siding.name.indexOf(':') + 1, siding.name.length())) - parseInt(siding.name.substring(1, siding.name.indexOf(':'))) < 2) {
                        fusei = save;
                    }
                }
                let b = 0;
                while (b < 99) {
                    b++;
                    ban = false;
                    state.trainnum = java.lang.String.valueOf(parseInt(state.trainnum) + 1);
                    if (siding.name.substring(0, 1) == '#') {
                        if (siding.name.length() > 0) {
                            if (alphabet.indexOf(save.substring(0, 1).toUpperCase()) > -1) {
                                siding.name = '#' + save.substring(0, 1) + state.trainnum;
                            } else {
                                siding.name = '#' + state.trainnum;
                            }
                        }
                    }
                    if (siding.name.substring(0, 1) == '@' && siding.name.indexOf('~') > -1) {
                        if (parseInt(siding.name.substring(siding.name.indexOf('~') + 1, siding.name.length())) - parseInt(siding.name.substring(1, siding.name.indexOf('~'))) < 2) {
                            if (alphabet.indexOf(save.substring(0, 1).toUpperCase()) > -1) {
                                siding.name = '#' + save.substring(0, 1) + state.trainnum;
                            } else {
                                siding.name = '#' + state.trainnum;
                            }
                        }
                    }
                    if (siding.name.substring(0, 1) == '@' && siding.name.indexOf(':') > -1) {
                        if (parseInt(siding.name.substring(siding.name.indexOf(':') + 1, siding.name.length())) - parseInt(siding.name.substring(1, siding.name.indexOf(':'))) < 2) {
                            if (alphabet.indexOf(save.substring(0, 1).toUpperCase()) > -1) {
                                siding.name = '#' + save.substring(0, 1) + state.trainnum;
                            } else {
                                siding.name = '#' + state.trainnum;
                            }
                        }
                    }
                    let loop = true;
                    for (let a = 0; banned[a] !== undefined; a++) {
                        if (state.trainnum.toString().substring(java.lang.String.valueOf(state.trainnum).length() - 5, java.lang.String.valueOf(state.trainnum).length() - 2) == banned[a]) {
                            loop = false;
                        }
                    }
                    if (loop) {
                        break;
                    }
                }
            }
        }
        if (ban) {
            state.trainnum = save;
        } else if (fusei === -1) {
            if (alphabet.indexOf(save.substring(0, 1).toUpperCase()) > -1) {
                state.trainnum = save.substring(0, 1) + state.trainnum;
            }
        } else {
            if (java.lang.String.valueOf(siding.name).indexOf('.0') > -1) {
                siding.name = java.lang.String.valueOf(siding.name).substring(0, java.lang.String.valueOf(siding.name).indexOf('.0'))
            }
            MinecraftClient.displayMessage('§c§l警告:不正な車両番号「' + fusei + '」が使用されたため、「' + siding.name.substring(1, siding.name.length()) + '」に置換されました。', false);
        }
        if (state.trainnum.indexOf('.0') > -1) {
            state.trainnum = state.trainnum.substring(0, state.trainnum.indexOf('.0'))
        }
        state.trainnum = rep + state.trainnum;
        return state.trainnum;
    }
}


// ====================================================================
// mtrops 連携 (自動検出。ClientConfig での切替は不要)
//   ・mtrops が入っていて、サーバーから受信済みで、その列車に編成番号が
//     割り当てられている → 新方式 (編成番号・各車番)
//   ・それ以外 (未導入 / 未受信 / 未割当) → 旧方式 (getNumberLegacy)
//   列車ごとに判定するので、混在していても動きます。
// ====================================================================
let MtrOpsApi = null;
try {
    let cls = Packages.jp.mtrops.api.MtrOpsApi;
    if (String(cls).indexOf('JavaClass') >= 0) MtrOpsApi = cls;
} catch (e) {
    MtrOpsApi = null;
}

/** この列車に mtrops の編成番号があるか。 */
function mtropsNumberOf(train) {
    if (MtrOpsApi == null) return '';
    try {
        if (!MtrOpsApi.isReady()) return '';
        return String(MtrOpsApi.numberOf(train.id()));
    } catch (e) {
        return '';
    }
}

/** 編成番号(表示用)。 */
function getNumber(state, train, type) {
    let n = mtropsNumberOf(train);
    if (n !== '') {
        state.trainnum = n;
        state.numNew = true;
        return n;
    }
    state.numNew = false;
    return getNumberLegacy(state, train, type);
}

/** 各車の車番 (carIndex は1始まり、先頭車=1)。取得できなければ ''。 */
function carNumberFor(train, carIndex) {
    if (MtrOpsApi == null) return '';
    try {
        return String(MtrOpsApi.carNumberOf(train.id(), carIndex));
    } catch (e) {
        return '';
    }
}

/** 長い番号(モハE233-1001 等)を枠(幅maxW)に収まるよう縮小して描く。 */
function drawNum(g, str, x, y, maxW) {
    let f0 = g.getFont();
    let w = g.getFontMetrics().stringWidth(str);
    if (maxW === undefined) maxW = 344;
    if (w > maxW) {
        g.setFont(f0.deriveFont(f0.getSize2D() * maxW / w));
    }
    g.drawString(str, x, y);
    g.setFont(f0);
}

/**
 * 番号の描画(フォントは車種ごとに numbermain の font 引数で決まる)。
 * numbermain と、各車の車番(drawCarNumText)の両方がこれを使う。
 * isNew: mtrops の新方式の番号。BIZ/P は旧10文字形式前提の配置なので、新方式では汎用配置にする。
 */
function drawNumberText(g, number, font, isNew) {
    if (isNew && (font === 'BIZ' || font === 'P')) {
        g.setColor(font === 'BIZ' ? Color.BLACK : Color.WHITE);
        g.setFont(getShared('font_murecho').deriveFont(Font.BOLD, 50));
        drawNum(g, number, 50, 50, 344);
        return;
    }
        if (font === true) {
            g.setFont(getShared('font_murecho').deriveFont(Font.BOLD, 50));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font === false) {
            g.setFont(getShared('font_kktt').deriveFont(Font.PLAIN, 40));
            g.setColor(Color.WHITE);
            drawNum(g, number, 10, 90, 344);
        } else if (font == 'DIN') {
            g.setFont(getShared('font_din').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font == 'MUSEO') {
            g.setFont(getShared('font_museo').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font == 'MONO') {
            g.setFont(getShared('font_mono').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font == 'AYAFON') {
            g.setFont(getShared('font_ayafon').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font == 'QUANTICO') {
            g.setFont(getShared('font_quantico').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font === 'Murecho') {
            g.setFont(getShared('font_murecho').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            if (JSON.parse(ClientConfig.get('enabledsq'))) {
                g.drawString('廃車', 10, 90);
            } else {
                drawNum(g, number, 8, 80, 344);
            }
        } else if (font === 'Hina') {
            g.setFont(getShared('font_hina').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            if (JSON.parse(ClientConfig.get('enabledsq'))) {
                g.drawString('廃車実行中', 10, 90);
            } else {
                drawNum(g, number, 8, 80, 344);
            }
        } else if (font === 'IBM') {
            g.setFont(getShared('font_ibm').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font === 'OS') {
            g.setFont(getShared('font_opensans').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font === 'KANIT') {
            g.setFont(getShared('font_kanit').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font === 'JOST') {
            g.setFont(getShared('font_jost2').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font === 'METRO') {
            g.setFont(getShared('font_metro').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font === 'INTER') {
            g.setFont(getShared('font_inter').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font === 'MILL') {
            g.setFont(getShared('font_millte').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font === 'MILL2') {
            g.setFont(getShared('font_millte2').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font === 'NUMBER') {
            g.setFont(getShared('font_jost').deriveFont(Font.BOLD, 60));
            g.setColor(Color.WHITE);
            drawNum(g, number, 8, 80, 344);
        } else if (font === 'BIZ') {
            g.setColor(Color.BLACK);
            if (java.lang.String.valueOf(number).length() > 9) {
                g.setFont(getShared('font_murecho').deriveFont(Font.BOLD, 18));
                g.drawString(number.substring(0, 5), 82, 16.5)
                if (Hiragana.includes(number.charAt(5))) {
                    g.drawString(number.charAt(5), 23, 48);
                    g.setFont(getShared('font_murecho').deriveFont(Font.BOLD, 50));
                    if (number.includes('・')) {
                        g.drawString(number.substring(6, 8) + ' ' + number.substring(8, 10), 40, 61);
                    } else {
                        g.drawString(number.substring(6, 8) + '-' + number.substring(8, 10), 50, 61);
                    }
                } else {
                    g.setFont(getShared('font_murecho').deriveFont(Font.BOLD, 50));
                    if (number.includes('・')) {
                        g.drawString(number.substring(6, 8) + ' ' + number.substring(8, 10), 60, 61);
                    } else {
                        g.drawString(number.substring(6, 8) + '-' + number.substring(8, 10), 50, 61);
                    }
                }
            } else {
                g.setFont(getShared('font_murecho').deriveFont(Font.BOLD, 50));
                drawNum(g, number, 50, 50, 344);
            }
        } else if (font === 'P') {
            g.setColor(Color.WHITE);
            if (java.lang.String.valueOf(number).length() > 9) {
                g.setFont(getShared('font_murecho').deriveFont(Font.BOLD, 18));
                g.drawString(number.substring(0, 5), 82, 16.5)
                if (Hiragana.includes(number.charAt(5))) {
                    g.drawString(number.charAt(5), 23, 48);
                    g.setFont(getShared('font_murecho').deriveFont(Font.BOLD, 50));
                    if (number.includes('・')) {
                        g.drawString(number.substring(6, 8) + ' ' + number.substring(8, 10), 60, 61);
                    } else {
                        g.drawString(number.substring(6, 8) + '-' + number.substring(8, 10), 50, 61);
                    }
                } else {
                    g.setFont(getShared('font_murecho').deriveFont(Font.BOLD, 50));
                    if (number.includes('・')) {
                        g.drawString(number.substring(6, 8) + ' ' + number.substring(8, 10), 60, 61);
                    } else {
                        g.drawString(number.substring(6, 8) + '-' + number.substring(8, 10), 50, 61);
                    }
                }
            }
        } else {
            g.setFont(getShared('font_kktt').deriveFont(Font.PLAIN, 40));
            g.setColor(Color.WHITE);
            drawNum(g, number, 10, 90, 344);
        }
}

function numbermain(ctx, state, train, font, isdraw) {
    apicycle.tick();
    if (!executed) {
        FareInit(state, train);
        executed = true;
    }
    /*if (isAmaya(train)) {
        AmayaNewSpeak(ctx, state, train);
    } else if (ayadenlist.includes(train.trainTypeId() + '\n')) {
        AmayaNewSpeak(ctx, state, train);
    } else {
        LocalSpeak(ctx, state, train);
    }
            if (apicycle.stateNowFirst() && !ayadenlist.includes(train.trainTypeId() + '\n')) {
        if (train.trainTypeId().includes('lencie_')) {
            LencieSpeak(ctx, state, train);
        } else {
            AutoSpeak(ctx, state, train, number);
        }
    }*/
    FareMain(ctx, state, train);
    let number = getNumber(state, train);
    //getTime(state, train, 0);
    if (train.shouldRenderDetail() && isdraw != false) {
        g.setBackground(new Color(1, 1, 1, 0));
        g.clearRect(0, 0, 360, 200);
        state.numfont = font;
        // 新方式のときは、各車の車番を train_common.js の processAllCars が
        // 別テクスチャで描く。ここでは編成共通のスロットを空(透明)にするだけ。
        if (state.numNew && typeof CAR_NUM_SEPARATE !== 'undefined' && CAR_NUM_SEPARATE && CAR_NUM_STAGE > 0) {
            return number;
        }
        drawNumberText(g, number, font, state.numNew === true);
    }
    let PlatformInfo = train.getThisRoutePlatforms();
    if (PlatformInfo[train.getThisRoutePlatformsNextIndex()] != undefined && train.isOnRoute()) {
        if (PlatformInfo[train.getThisRoutePlatformsNextIndex()].station != null) {
            let keito = PlatformInfo[train.getThisRoutePlatformsNextIndex()].route.lightRailRouteNumber;
            let for1 = TextUtil.getCjkParts(PlatformInfo[train.getThisRoutePlatformsNextIndex()].destinationName);
            let next = TextUtil.getCjkParts(PlatformInfo[train.getThisRoutePlatformsNextIndex()].station.name);
            let output;
            if (keito == undefined) {
                output = for1.substring(for1.lastIndexOf(']') + 1, for1.length()) + '行き 車番:' + number + ' 次駅:' + next.substring(next.lastIndexOf(']') + 1, next.length()) + (train.doorValue() > 0 || Math.abs(PlatformInfo[train.getThisRoutePlatformsNextIndex()].distance - train.railProgress()) < 1 ? '(停車中)' : '(あと' + Math.floor((PlatformInfo[train.getThisRoutePlatformsNextIndex()].distance - train.railProgress()) * 10) / 10 + 'm)')
            } else if (TextUtil.getCjkParts(keito) == '') {
                output = TextUtil.getNonCjkParts(keito) + ' ' + for1.substring(for1.lastIndexOf(']') + 1, for1.length()) + '行き 車番:' + number + ' 次駅:' + next.substring(next.lastIndexOf(']') + 1, next.length()) + (train.doorValue() > 0 || Math.abs(PlatformInfo[train.getThisRoutePlatformsNextIndex()].distance - train.railProgress()) < 1 ? '(停車中)' : '(あと' + Math.floor((PlatformInfo[train.getThisRoutePlatformsNextIndex()].distance - train.railProgress()) * 10) / 10 + 'm)')
            } else {
                output = TextUtil.getCjkParts(keito) + ' ' + for1.substring(for1.lastIndexOf(']') + 1, for1.length()) + '行き 車番:' + number + ' 次駅:' + next.substring(next.lastIndexOf(']') + 1, next.length()) + (train.doorValue() > 0 || Math.abs(PlatformInfo[train.getThisRoutePlatformsNextIndex()].distance - train.railProgress()) < 1 ? '(停車中)' : '(あと' + Math.floor((PlatformInfo[train.getThisRoutePlatformsNextIndex()].distance - train.railProgress()) * 10) / 10 + 'm)')
            }
            ctx.setDebugInfo('行先', PlacementOrder.UPPER, output);
        }
    } else {
        if (PlatformInfo[train.getThisRoutePlatformsNextIndex()] == null) {
            ctx.setDebugInfo('行先', PlacementOrder.UPPER, '- 車番:' + number + '')
        } else {
            ctx.setDebugInfo('行先', PlacementOrder.UPPER, '- 車番:' + number + '')
        }
    }
}

function CarNum(internalId, i, max) {
    let intermediateValue = internalId * i;
    let id = internalId;
    intermediateValue = intermediateValue ^ (id >> 16);
    intermediateValue = intermediateValue ^ (id << 8);
    intermediateValue = intermediateValue ^ (id >> 4);
    let vehicleNumber = intermediateValue % max;
    return Math.abs(vehicleNumber);
}

/** 各車の車番テクスチャを描く (train_common.js から呼ばれる)。font は numbermain と同じ指定。 */
function drawCarNumText(g, text, font) {
    g.setBackground(new Color(1, 1, 1, 0));
    g.clearRect(0, 0, 360, 200);
    drawNumberText(g, text, font, true);
}
