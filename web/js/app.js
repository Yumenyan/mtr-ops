'use strict';
(function () {
  // ---------- helpers ----------
  const $ = (s, r = document) => r.querySelector(s);
  function h(tag, attrs, ...kids) {
    const e = document.createElement(tag);
    if (attrs) for (const k in attrs) {
      const v = attrs[k];
      if (v == null || v === false) continue;
      if (k === 'class') e.className = v;
      else if (k === 'style') e.style.cssText = v;
      else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else if (k === 'data') for (const d in v) e.dataset[d] = v[d];
      else e.setAttribute(k, v === true ? '' : v);
    }
    for (const c of kids.flat(Infinity)) if (c != null && c !== false) e.append(c.nodeType ? c : document.createTextNode(String(c)));
    return e;
  }
  // 駅名に埋め込まれた駅番号 [AA01] は表示名から取り除く(駅番号は別の丸バッジに出す)
  const CODE_RE = /\s*[\[［]\s*([A-Za-z]+)\s*(-?)\s*(\d+)\s*[\]］]\s*/g;
  const clean = n => (n || '').replace(CODE_RE, ' ').replace(/\s+/g, ' ').trim();
  const jp = n => clean((n || '').split('|')[0]);
  const en = n => clean((n || '').split('|')[1] || '');
  // 路線名の ( ) 内の英字を2文字ずつに区切った記号(例 (AAAC) → AA, AC)に、駅名の [AA01] の英字が含まれるときだけ駅番号を出す
  function routeLetters(r) {
    if (!r) return [];
    if (r._letters) return r._letters;
    const out = []; const re = /[(（]([^)）]*)[)）]/g; let m;
    while ((m = re.exec(r.name || ''))) {
      const letters = m[1].replace(/[^A-Za-z]/g, '').toUpperCase();
      for (let i = 0; i + 2 <= letters.length; i += 2) out.push(letters.slice(i, i + 2));
      if (letters.length === 1) out.push(letters);
    }
    r._letters = out; return out;
  }
  function stationCode(r, stationId) {
    const st = S.stations.get(String(stationId)); if (!st) return null;
    const letters = routeLetters(r); if (!letters.length) return null;
    const re = new RegExp(CODE_RE.source, 'g'); let m;
    while ((m = re.exec(st.name || ''))) {
      const lt = m[1].toUpperCase();
      if (letters.includes(lt)) return { letters: lt, num: m[2] + m[3] };   // ハイフンは番号側に残す(例 -01)
    }
    return null;
  }
  function codeEls(c) { return c ? [h('span', null, c.letters), h('span', null, c.num)] : []; }
  const nameEl = (n, cls) => { const p = h('span', cls ? { class: cls } : null, jp(n)); return en(n) ? h('span', null, p, h('span', { class: 'en' }, en(n))) : p; };
  const pad = n => String(n).padStart(2, '0');
  const fmtDelay = s => s < 30 ? '定刻' : s < 60 ? `約${s}秒遅れ` : `約${Math.round(s / 60)}分遅れ`;
  const SVG = 'http://www.w3.org/2000/svg';
  function icon(path) { const s = document.createElementNS(SVG, 'svg'); s.setAttribute('viewBox', '0 0 24 24'); s.innerHTML = path; return s; } // 固定文字列のみ

  const STATUS = {
    normal:   { label: '平常',     en: 'Normal',   mark: '●', rank: 0 },
    delay:    { label: '遅延',     en: 'Delay',    mark: '◐', rank: 1 },
    bigDelay: { label: '大幅遅延', en: 'Major delay', mark: '◉', rank: 2 },
    stopped:  { label: '停止',     en: 'Stopped',  mark: '■', rank: 3 }
  };
  const st = k => STATUS[k] || STATUS.normal;
  const chip = (k, solid) => h('span', { class: `chip s-${k}${solid ? ' solid' : ''}` }, h('i', null, st(k).mark), h('span', null, st(k).label));
  const worst = ks => ks.reduce((a, k) => st(k).rank > st(a).rank ? k : a, 'normal');

  // ---------- state ----------
  const S = {
    live: null, routes: [], stations: new Map(), routeMap: new Map(), formations: [], depots: [],
    ghosts: { suspects: [], past: [] }, topoRev: -1, ok: false, lastOk: 0, err: '', trains: new Map(), incidents: [], routeLive: new Map()
  };
  const route = id => S.routeMap.get(String(id));
  const station = id => S.stations.get(String(id));

  async function get(path) {
    const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 4000);
    try { const r = await fetch(path, { signal: ctl.signal, cache: 'no-store' }); if (!r.ok) throw new Error('HTTP ' + r.status); return await r.json(); }
    finally { clearTimeout(t); }
  }
  async function loadStatic() {
    const [r, s] = await Promise.all([get('/api/routes'), get('/api/stations')]);
    S.routes = r.routes || [];
    S.routeMap = new Map(S.routes.map(x => [String(x.id), x]));
    S.stations = new Map((s.stations || []).map(x => [String(x.id), x]));
  }
  async function loadGhosts() { try { S.ghosts = await get('/api/ghosts'); } catch (e) { /* 次回 */ } }
  async function loadFleet() {
    loadGhosts();
    try { const [f, d] = await Promise.all([get('/api/formations'), get('/api/depots')]); S.formations = f.formations || []; S.depots = d.depots || []; } catch (e) { /* 次回 */ }
  }
  let polling = false;
  async function poll() {
    if (polling) return; polling = true;
    try {
      const d = await get('/api/live');
      if (parseHash().view === 'ghost') await loadGhosts();
      S.live = d.status; S.ok = true; S.lastOk = Date.now(); S.err = '';
      S.trains = new Map((d.trains || []).map(t => [String(t.id), t]));
      S.incidents = d.incidents || [];
      S.routeLive = new Map((d.routes || []).map(r => [String(r.id), r]));
      if (d.status.topologyRev !== S.topoRev) { await loadStatic(); S.topoRev = d.status.topologyRev; }
    } catch (e) { S.ok = false; S.err = String(e.message || e); }
    polling = false; render();
  }

  // ---------- router ----------
  function parseHash() {
    const [path, q] = (location.hash.replace(/^#\/?/, '') || 'home').split('?');
    const p = path.split('/'); const params = new URLSearchParams(q || '');
    return { view: p[0] || 'home', arg: p[1] || '', train: params.get('t') || '', sid: params.get('s') || '' };
  }
  function go(view, arg, train, sid) {
    const q = [train ? 't=' + encodeURIComponent(train) : '', sid ? 's=' + encodeURIComponent(sid) : ''].filter(Boolean).join('&');
    location.hash = '#/' + view + (arg ? '/' + arg : '') + (q ? '?' + q : '');
  }
  const TABS = [
    ['home', '運行情報', '<path d="M4 6h16M4 12h16M4 18h10"/>'],
    ['diagram', '列車位置', '<circle cx="12" cy="5" r="2"/><circle cx="12" cy="19" r="2"/><path d="M12 7v10M7 12h5"/>'],
    ['map', '地図', '<path d="M9 4 3 6v14l6-2 6 2 6-2V4l-6 2z M9 4v14 M15 6v14"/>'],
    ['fleet', '車両', '<rect x="3" y="6" width="18" height="10" rx="3"/><path d="M7 20l1-4M17 20l-1-4M3 11h18"/>'],
    ['incidents', '異常', '<path d="M12 3 2 20h20z M12 10v5 M12 18v.5"/>'],
    ['ghost', 'スタック', '<path d="M5 20V10a7 7 0 0 1 14 0v10l-3-2-2 2-2-2-2 2-2-2z M9.5 11h.01 M14.5 11h.01"/>']
  ];
  // タブは1度だけ作って以後は属性と件数だけ更新する(作り直すと、押している最中に要素が入れ替わってクリックが失われる)
  let tabEls = null;
  function drawTabs(cur) {
    const nav = $('#tabs');
    if (!tabEls) {
      tabEls = new Map(); nav.replaceChildren();
      for (const [id, label, p] of TABS) {
        const dot = h('span', { class: 'dot', hidden: true });
        const btn = h('button', { class: 'tab', type: 'button', onclick: () => go(id) }, icon(p), label, dot);
        tabEls.set(id, { btn, dot }); nav.append(btn);
      }
    }
    const active = S.incidents.filter(i => i.active && i.type !== 'ghost').length; const gh = (S.ghosts.suspects || []).length;
    for (const [id, { btn, dot }] of tabEls) {
      if (id === cur) btn.setAttribute('aria-current', 'page'); else btn.removeAttribute('aria-current');
      const n = id === 'incidents' ? active : id === 'ghost' ? gh : 0;
      dot.hidden = !n; if (dot.textContent !== String(n || '')) dot.textContent = n || '';
    }
  }

  // ---------- views ----------
  function routeBadge(r) {
    return h('span', { class: 'rbadge', style: `--c:${r.color}`, 'aria-hidden': 'true' });
  }
  function routeStatus(r) { const l = S.routeLive.get(String(r.id)); return l ? l.status : (r.status || 'normal'); }

  // 路線の検索つき一覧(数百路線でも探せる)。入力欄を保ったまま一覧だけ更新できるよう refill を公開する。
  let routeQ = '', routeFilter = 'all', refill = null;
  // 同じ路線分類(駅ナンバリングの英字が同じ / 始終点が同じ)の路線を1つにまとめる。一覧と乗換案内で共通に使う。
  function lineKeyOf(r) {
    const lt = routeLetters(r)[0] || ''; const sids = (r.stationIds || []).map(String);
    return lt ? 'L' + lt : (sids.length ? 'E' + [sids[0], sids[sids.length - 1]].sort().join('-') : 'R' + r.id);
  }
  function lineGroups(routes) {
    const groups = new Map();
    for (const r of routes) {
      const lt = routeLetters(r)[0] || ''; const key = lineKeyOf(r);
      let g = groups.get(key); if (!g) { g = { key, rep: r, letter: lt, members: [] }; groups.set(key, g); }
      g.members.push(r);
    }
    return [...groups.values()].map(g => { g.line = lineFor(g.rep, g.letter); return g; });
  }
  function routeBrowser(onPick, compact) {
    const fbox = h('div', { class: 'pills' }); const list = h('div', { class: compact ? 'rlist' : 'grid' }); const note = h('div', { class: 'nodata' });
    const input = h('input', { class: 'search', type: 'search', placeholder: '路線名で検索(例: 空港、白鉄)', value: routeQ, 'aria-label': '路線を検索',
      oninput: e => { routeQ = e.target.value; fill(); } });
    function fill() {
      fbox.replaceChildren(...[['all', 'すべて'], ['running', '運行中'], ['trouble', '乱れあり']].map(([k, l]) =>
        h('button', { class: 'pill', 'aria-pressed': String(routeFilter === k), onclick: () => { routeFilter = k; fill(); } }, l)));
      const words = routeQ.toLowerCase().trim().split(/[\s\u3000]+/).filter(Boolean);
      let rs = S.routes.filter(r => {
        const text = `${r.name} ${r.number || ''}`.toLowerCase();
        if (!words.every(w => text.includes(w))) return false;
        const l = S.routeLive.get(String(r.id)) || {};
        if (routeFilter === 'running') return (l.trainCount || 0) > 0;
        if (routeFilter === 'trouble') return routeStatus(r) !== 'normal';
        return true;
      });
      rs.sort((a, b) => st(routeStatus(b)).rank - st(routeStatus(a)).rank || jp(a.name).localeCompare(jp(b.name), 'ja'));
      // 同じ路線分類(駅ナンバリングの英字が同じ / 始終点が同じ)の路線は1件にまとめる
      let gl = lineGroups(rs).map(g => {
        const line = g.line; const agg = { n: g.members.length, trainCount: 0, maxDelaySec: 0, status: 'normal' };
        for (const m of g.members) { const l = S.routeLive.get(String(m.id)) || {}; agg.trainCount += l.trainCount || 0; agg.maxDelaySec = Math.max(agg.maxDelaySec, l.maxDelaySec || 0); const k = routeStatus(m); if (st(k).rank > st(agg.status).rank) agg.status = k; }
        return { rep: Object.assign({}, g.rep, { name: line.name || g.rep.name, color: line.color || g.rep.color }), letter: g.letter, agg };
      });
      gl.sort((a, b) => st(b.agg.status).rank - st(a.agg.status).rank || jp(a.rep.name).localeCompare(jp(b.rep.name), 'ja'));
      const total = gl.length; gl = gl.slice(0, 120);
      list.replaceChildren(...gl.map(g => routeItem(g.rep, x => { diagLetter = g.letter; onPick(x); }, compact, g.agg)));
      note.textContent = total ? `${total}路線${total > gl.length ? `(上位${gl.length}件を表示。検索で絞り込めます)` : ''}(上下線・種別はまとめて表示)` : '該当する路線がありません';
    }
    fill(); refill = fill;
    return h('div', null, input, fbox, note, list);
  }
  function routeItem(r, onPick, compact, agg) {
    const l = agg || S.routeLive.get(String(r.id)) || {}; const k = agg ? agg.status : routeStatus(r);
    const meta = (agg && agg.n > 1 ? `${agg.n}系統 ・ ` : '') + `${l.trainCount ?? 0}本運行` + (l.maxDelaySec >= 60 ? ` ・最大${Math.round(l.maxDelaySec / 60)}分遅れ` : '');
    if (compact) return h('button', { class: 'rrow', style: `--c:${r.color}`, onclick: () => onPick(r) }, routeBadge(r), h('span', { class: 'rname' }, nameEl(r.name), h('small', null, meta)), chip(k));
    return h('button', { class: 'rcard', style: `--c:${r.color}`, onclick: () => onPick(r) },
      h('span', { class: 'bar' }),
      h('span', { class: 'body' }, h('span', { class: 'nm' }, routeBadge(r), nameEl(r.name)), h('span', { class: 'meta' }, h('span', null, meta), chip(k))));
  }

  function viewHome() {
    const c = S.live ? S.live.counts : {};
    const bad = Object.keys(c).filter(k => k !== 'normal' && c[k] > 0);
    const w = worst(bad);
    const board = h('section', { class: 'board' },
      h('div', { class: `big s-${w}` }, st(w).mark),
      h('div', null,
        h('h1', null, bad.length ? '一部の列車に乱れがあります' : 'ただいま平常どおり運行しています'),
        h('p', null, `運行中 ${S.live ? S.live.inService : 0} 本 ／ 異常検知 ${S.live ? S.live.activeIncidents : 0} 件`)),
      h('div', { class: 'counts' }, bad.map(k => h('span', { class: `chip s-${k}` }, h('i', null, st(k).mark), `${st(k).label} ${c[k]}`))));
    const act = S.incidents.filter(i => i.active).slice(0, 3);
    return h('div', null, board,
      act.length ? [h('h2', { class: 'sec' }, '発生中の異常'), act.map(incidentEl)] : null,
      h('h2', { class: 'sec' }, '路線別の運行状況'),
      S.routes.length ? routeBrowser(r => go('diagram', r.id)) : h('div', { class: 'empty' }, 'まだ路線データがありません'));
  }

  function incidentEl(i) {
    const r = route(i.routeId);
    return h('button', { class: `inc ${i.severity}${i.active ? '' : ' done'}`, style: r ? `--c:${r.color}` : null, onclick: () => i.trainId && go(parseHash().view, parseHash().arg, i.trainId) },
      h('span', null, h('b', null, i.typeLabel, i.active ? '' : '(解消)'), h('span', null, i.message),
        h('br'), h('small', null, `${jp(i.routeName)} ・ ${i.agoSec < 60 ? i.agoSec + '秒前' : Math.round(i.agoSec / 60) + '分前'}`)));
  }
  // ---- スタック調査(ゴースト疑い) ----
  function copyText(text, btn) {
    const done = () => { const o = btn.textContent; btn.textContent = 'コピーしました'; setTimeout(() => { btn.textContent = o; }, 1500); };
    try { navigator.clipboard.writeText(text).then(done, () => { btn.textContent = text; }); } catch (e) { btn.textContent = text; }
  }
  function ghostCard(g) {
    const r = route(g.routeId);
    const tp = `/tp @s ${Math.round(g.x)} ${Math.round(g.y) + 2} ${Math.round(g.z)}`;
    const btnTp = h('button', { class: 'gbtn', onclick: () => copyText(tp, btnTp) }, '移動コマンドをコピー');
    const btnId = h('button', { class: 'gbtn', onclick: () => copyText(String(g.trainId), btnId) }, '列車IDをコピー');
    return h('div', { class: 'gcard ' + (g.confirmed ? 'bad' : 'warn') },
      h('div', { class: 'ghead' }, h('b', null, g.title || g.formation || ('列車#' + String(g.trainId).slice(-6))), h('span', { class: 'gtag' }, g.confirmed ? `疑い確定(${g.seconds}秒継続)` : '判定中(5秒継続で確定)')),
      g.reasons.map(x => h('div', { class: 'greason' }, h('b', null, x.label), h('span', null, x.detail))),
      h('dl', { class: 'gmeta' },
        h('dt', null, '車庫'), h('dd', null, `${jp(g.depotName) || '不明'} / ${jp(g.sidingName) || '不明'}`),
        h('dt', null, '路線'), h('dd', null, r ? jp(r.name) : (g.routeName ? jp(g.routeName) : '行先なし(路線を特定できません)')),
        h('dt', null, '状態'), h('dd', null, `${g.onRoute ? '運行中' : '車庫待機'} ・ 速度 ${g.speedKmh} km/h`),
        h('dt', null, '座標'), h('dd', null, `X ${Math.round(g.x)} / Y ${Math.round(g.y)} / Z ${Math.round(g.z)}`)),
      h('div', { class: 'gact' }, btnTp, btnId));
  }
  function viewGhost() {
    const g = S.ghosts || { suspects: [], past: [] };
    return h('div', null,
      h('div', { class: 'gintro' }, h('b', null, 'スタック調査(ゴースト疑い)'),
        h('p', null, 'MTRのデータ上は存在するのに、動かず閉塞を塞ぎ続ける車両を探します。確定ではなく「疑い」です。ワールドで実物を確認し、判定の精度を上げていく前提の機能です。'),
        h('details', null, h('summary', null, '判定の基準と、起きる条件'),
          h('ul', null,
            h('li', null, h('b', null, '停止固着: '), 'MTRの Train.simulateTrain は、次の停車位置 nextStoppingIndex が経路の長さ以上だと毎tick何もせず戻ります。経路が再生成されて短くなると、残っていた列車がこの状態になり、削除もされません。'),
            h('li', null, h('b', null, '経路・距離表なし: '), '経路が空、または距離表が経路より短い列車。位置が算出できないため、座標は車庫線の位置です。'),
            h('li', null, '列車は経路の再生成後も、繰り返し区間の設定が変わらない限り削除されません(Siding.generateRoute)。経路や駅、車庫の設定を変えた直後に起きやすくなります。'),
            h('li', null, 'ゴースト疑いは5秒以上続いたものを「確定」として扱い、異常タブにも通知します。')))),
      h('h2', { class: 'sec' }, `疑いのある車両 ${g.suspects.length}`),
      g.suspects.length ? g.suspects.map(ghostCard) : h('div', { class: 'empty' }, '現在、ゴースト疑いの車両はありません'),
      g.past.length ? [h('h2', { class: 'sec' }, '過去の記録(解消済み)'), g.past.slice(0, 30).map(incidentEl)] : null);
  }
  function viewIncidents() {
    const a = S.incidents.filter(i => i.active), b = S.incidents.filter(i => !i.active);
    return h('div', null, h('h2', { class: 'sec' }, `発生中 ${a.length}`),
      a.length ? a.map(incidentEl) : h('div', { class: 'empty' }, '現在、異常はありません'),
      b.length ? [h('h2', { class: 'sec' }, '解消済み'), b.slice(0, 30).map(incidentEl)] : null);
  }

  // 駅番号(路線記号+連番)

  // 列車の路線上位置(駅index の小数)と進行方向
  // ---- 路線のまとめ: 駅ナンバリング(英字)が同じ路線を1本の「ライン」にし、上下線・種別をまたいで表示する ----
  function stationCodes(st) { const out = []; const re = new RegExp(CODE_RE.source, 'g'); let m; while ((m = re.exec((st && st.name) || ''))) out.push({ l: m[1].toUpperCase(), n: parseInt(m[3], 10) || 0 }); return out; }
  let lineCache = { key: '', map: new Map() };
  function lineFor(r, letter) {
    const key = S.stations.size + '/' + S.routes.length;
    if (lineCache.key !== key) lineCache = { key, map: new Map() };
    const sids = (r.stationIds || []).map(String);
    const ep = sids.length ? [sids[0], sids[sids.length - 1]].sort().join('-') : String(r.id);
    const ck = letter ? 'L' + letter : 'E' + ep;
    if (lineCache.map.has(ck)) return lineCache.map.get(ck);
    let ids, routes, name, color = r.color;
    if (letter) {
      const list = [];
      for (const [id, st] of S.stations) { const c = stationCodes(st).find(c => c.l === letter); if (c) list.push({ id: String(id), n: c.n }); }
      list.sort((a, b) => a.n - b.n); ids = list.map(x => x.id);
      routes = S.routes.filter(q => routeLetters(q).includes(letter));
      const base = q => jp(q.name).replace(/[(（][^)）]*[)）]/g, '').trim();
      const best = routes.slice().sort((a, b) => base(a).length - base(b).length)[0] || r;
      name = base(best) || jp(r.name); color = best.color;
    } else {
      routes = S.routes.filter(q => { const a = (q.stationIds || []).map(String); return a.length && [a[0], a[a.length - 1]].sort().join('-') === ep; });
      if (!routes.length) routes = [r];
      ids = ((routes.slice().sort((a, b) => (b.stationIds || []).length - (a.stationIds || []).length)[0]).stationIds || []).map(String);
      name = jp(r.name);
    }
    const line = { ids, idx: new Map(ids.map((x, i) => [x, i])), routeSet: new Set(routes.map(q => String(q.id))), name, color, letter: letter || '', nRoutes: routes.length };
    lineCache.map.set(ck, line); return line;
  }
  // 実位置(x,z)を駅をつないだ折れ線へ射影し、折れ線上の位置(区間番号+割合)を返す。通過駅を挟む区間でも実際の位置になる。
  function projectChain(pts, x, z) {
    let best = null;
    for (let i = 0; i < pts.length - 1; i++) {
      const ax = pts[i][0], az = pts[i][1], bx = pts[i + 1][0], bz = pts[i + 1][1];
      const dx = bx - ax, dz = bz - az, L2 = dx * dx + dz * dz;
      const u = L2 > 0 ? Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / L2)) : 0;
      const px = ax + dx * u, pz = az + dz * u, d2 = (x - px) ** 2 + (z - pz) ** 2;
      if (!best || d2 < best.d2) best = { i, u, d2 };
    }
    return best;
  }
  function hasXZ(t) { return Number.isFinite(t.x) && Number.isFinite(t.z) && !(t.x === 0 && t.z === 0); }
  function linePos(t, line) {
    const g = id => { const v = line.idx.get(String(id)); return v === undefined ? -1 : v; };
    const d = g(t.destStationId); const a = g(t.prevStationId), b = g(t.nextStationId);
    let pos = -1;
    if (t.atStop && t.currentStationId && g(t.currentStationId) >= 0) pos = g(t.currentStationId);
    else if (a >= 0 && b >= 0 && a !== b && hasXZ(t)) {
      // 前の停車駅〜次の停車駅(間の通過駅を含む)の駅列に、実際の座標を射影して位置を決める
      const lo = Math.min(a, b), hi = Math.max(a, b); const pts = [];
      for (let k = lo; k <= hi; k++) { const st0 = station(line.ids[k]); if (!st0) { pts.length = 0; break; } pts.push([st0.x, st0.z]); }
      const pr = pts.length >= 2 ? projectChain(pts, t.x, t.z) : null;
      pos = pr ? lo + pr.i + pr.u : a + (b - a) * (t.progress || 0) / 100;
    }
    else if (a >= 0 && b >= 0 && a !== b) pos = a + (b - a) * (t.progress || 0) / 100;
    else if (b >= 0) pos = b; else if (a >= 0) pos = a;
    if (pos < 0) return null;
    // 向き: 行先がこの線上にあれば行先の方向を最優先(行先と逆向きにはならない)。無ければ前後の駅から。
    let dir = 0;
    if (d >= 0 && Math.abs(d - pos) > 0.01) dir = d > pos ? 1 : -1;
    else if (a >= 0 && b >= 0 && a !== b) dir = b > a ? 1 : -1;
    else if (t.atStop && b >= 0 && b !== pos) dir = b > pos ? 1 : -1;
    if (!dir) dir = pos >= line.ids.length - 1 ? 1 : pos <= 0 ? -1 : 1;
    return { pos, dir };
  }
  function trainPos(t, ids) {
    const at = (id, from = 0) => ids.indexOf(String(id), from);
    if (t.atStop && t.currentStationId) { const i = at(t.currentStationId); if (i >= 0) return { pos: i, dir: dirOf(t, ids, i) }; }
    if (t.prevStationId && t.nextStationId) {
      for (let i = 0; i < ids.length - 1; i++) {
        const a = ids[i], b = ids[i + 1];
        if (a === String(t.prevStationId) && b === String(t.nextStationId)) return { pos: i + (t.progress || 0) / 100, dir: 1 };
        if (b === String(t.prevStationId) && a === String(t.nextStationId)) return { pos: i + 1 - (t.progress || 0) / 100, dir: -1 };
      }
    }
    const n = at(t.nextStationId); if (n >= 0) return { pos: n, dir: 1 };
    return null;
  }
  function dirOf(t, ids, i) {
    const n = ids.indexOf(String(t.nextStationId));
    if (n >= 0 && n < i) return -1; return 1;
  }

  let diagRoute = '', pickerOpen = false, diagLetter = '';
  const xfOpen = new Set();
  function viewDiagram(arg) {
    if (!S.routes.length) return h('div', { class: 'empty' }, '路線データを待っています…');
    const r = route(arg) || route(diagRoute) || S.routes[0]; diagRoute = String(r.id);
    const pills = h('div', null,
      h('button', { class: 'rpick', style: `--c:${r.color}`, 'aria-expanded': String(pickerOpen), onclick: () => { pickerOpen = !pickerOpen; render(true); } },
        routeBadge(r), h('span', { class: 'rname' }, nameEl(r.name), h('small', null, `全${S.routes.length}路線から選ぶ`)), h('span', { class: 'caret' }, pickerOpen ? '▲' : '▼')),
      pickerOpen ? h('div', { class: 'rpanel' }, routeBrowser(x => { pickerOpen = false; go('diagram', x.id); }, true)) : null);
    const letters = routeLetters(r); const cur = letters.includes(diagLetter) ? diagLetter : (letters[0] || '');
    const line = lineFor(r, cur); const ids = line.ids; const ROW = 74;
    const lr = cur ? { _letters: [cur] } : r;
    const tabs = letters.length > 1 ? h('div', { class: 'pills' }, letters.map(l => h('button', { class: 'xf-chip', style: `--c:${r.color}`, 'aria-pressed': String(l === cur), onclick: () => { diagLetter = l; render(true); } }, h('i'), l + ' 線'))) : null;
    const wrap = h('div', { class: 'diag', style: `--c:${line.color || r.color};height:${ids.length * ROW}px` });
    if (!ids.length) return h('div', null, pills, tabs, h('div', { class: 'empty' }, 'この路線の駅リストは容量の都合で省略されています。地図・車両タブをご利用ください。'));
    wrap.append(h('div', { class: 'track', style: `top:${ROW / 2}px;bottom:${ROW / 2}px` }));
    ids.forEach((id, i) => {
      const s = station(id) || { name: id };
      const others = lineGroups((s.routeIds || []).filter(x => !line.routeSet.has(String(x))).map(route).filter(Boolean));   // 同じ路線分類は1つに数える
      wrap.append(h('div', { class: 'st' + (parseHash().sid === id ? ' hl' : ''), data: { sid: id }, style: `top:${i * ROW}px` },
        h('div', { class: 'node' }, codeEls(stationCode(lr, id))),
        h('div', { class: 'lab' }, h('b', null, jp(s.name)), en(s.name) ? h('span', { class: 'en' }, en(s.name)) : null,
          others.length ? h('div', { class: 'xf' },
            h('button', { class: 'xf-btn', 'aria-expanded': String(xfOpen.has(id)), onclick: () => { xfOpen.has(id) ? xfOpen.delete(id) : xfOpen.add(id); render(true); } },
              h('span', { class: 'xf-dots' }, others.slice(0, 4).map(o => h('span', { style: `--c:${o.line.color || o.rep.color}` }))), `乗換 ${others.length}路線`, xfOpen.has(id) ? ' ▲' : ' ▼'),
            xfOpen.has(id) ? h('div', { class: 'xf-list' }, others.map(o => h('button', { class: 'xf-chip', style: `--c:${o.line.color || o.rep.color}`, onclick: () => { diagLetter = o.letter; go('diagram', o.rep.id, '', id); } }, h('i'), jp(o.line.name || o.rep.name)))) : null) : null)));
    });
    const placed = [];
    for (const t of S.trains.values()) { if (!line.routeSet.has(String(t.routeId))) continue; const p = linePos(t, line); if (p) placed.push({ t, p, y0: p.pos * ROW + ROW / 2 }); }
    placed.sort((m, n) => m.p.pos - n.p.pos);
    // 札は上り(左)・下り(右)に分け、重ならないよう縦にずらし、記号の位置へ引出線でつなぐ。ずらしが大きすぎるものは省略して一覧に回す。
    const GAP = 44, MAXSHIFT = ROW * 1.3; const lanes = { l: [], r: [] }; const hidden = []; let maxY = ids.length * ROW;
    for (const it of placed) {
      const lane = lanes[it.p.dir < 0 ? 'l' : 'r']; const last = lane[lane.length - 1];
      const y = Math.max(it.y0, last ? last.y + GAP : -1e9);
      if (y - it.y0 > MAXSHIFT && last) { last.more = (last.more || 0) + 1; hidden.push(it); continue; }
      it.y = y; lane.push(it); maxY = Math.max(maxY, y + 24);
    }
    wrap.style.height = maxY + 'px';
    const svg = document.createElementNS(SVG, 'svg'); svg.setAttribute('class', 'leaders'); svg.setAttribute('width', '236'); svg.setAttribute('height', String(maxY));
    wrap.append(svg);
    const colOf2 = t => t.status === 'normal' ? 'ok' : t.status === 'delay' ? 'warn' : t.status === 'stopped' ? 'stop' : 'bad';
    for (const side of ['l', 'r']) for (const it of lanes[side]) {
      const { t, p } = it; const sel = parseHash().train === String(t.id); const tr = route(t.routeId); const rc = (tr && tr.color) || '#888';
      const ln = document.createElementNS(SVG, 'line');
      ln.setAttribute('x1', side === 'l' ? '80' : '132'); ln.setAttribute('y1', String(it.y)); ln.setAttribute('x2', '106'); ln.setAttribute('y2', String(it.y0));
      ln.setAttribute('stroke', rc); ln.setAttribute('stroke-width', sel ? '3.5' : '2'); ln.setAttribute('stroke-linecap', 'round'); svg.append(ln);
      wrap.append(h('span', { class: 'tmark' + (sel ? ' sel' : ''), style: `top:${it.y0}px;--sc:var(--${colOf2(t)});--rc:${rc}` }));
      wrap.append(h('button', {
        class: `tchip ${side}${sel ? ' sel' : ''}`, style: `top:${it.y}px;--sc:var(--${colOf2(t)});--rc:${rc}`, title: tr ? jp(tr.name) : '',
        onclick: () => go('diagram', r.id, t.id), 'aria-label': `${t.title || t.formation} ${st(t.status).label}`
      }, h('span', { class: 'fm-no' }, (p.dir < 0 ? '▲ ' : '▼ ') + (t.title || t.formation || '番号未設定')), h('span', { class: 'stt' }, st(t.status).label),
        it.more ? h('span', { class: 'more' }, `他${it.more}`) : null));
    }
    const hiddenList = hidden.length ? h('div', { class: 'hid' }, h('b', null, `表示しきれない列車 ${hidden.length}本`),
      h('div', { class: 'hidl' }, hidden.map(({ t, p }) => { const tr = route(t.routeId); return h('button', { class: 'hidchip', style: `--rc:${(tr && tr.color) || '#888'};--sc:var(--${colOf2(t)})`, onclick: () => go('diagram', r.id, t.id) },
        (p.dir < 0 ? '▲ ' : '▼ ') + (t.title || t.formation || '番号未設定'), h('small', null, st(t.status).label)); }))) : null;
    const here = placed;
    return h('div', null, pills, tabs,
      wrap, hiddenList,
      h('div', { class: 'nodata' }, `${jp(line.name)}:${here.length}本が運行中(${line.nRoutes}系統をまとめて表示)。左が上り(▲)、右が下り(▼)。札と線の位置を引出線でつないでいます。`));
  }

  // ----- 地図 -----
  const MAP = { cv: null, ctx: null, cx: 0, cz: 0, scale: 0.05, fitted: false, disp: new Map(), raf: 0, drag: null, pts: [] };
  function viewMap() {
    const wrap = h('div', { class: 'mapwrap' });
    if (!MAP.cv) { MAP.cv = h('canvas', { class: 'map' }); MAP.ctx = MAP.cv.getContext('2d'); bindMap(MAP.cv); }
    wrap.append(MAP.cv, h('div', { class: 'mapctl' },
      h('button', { 'aria-label': '拡大', onclick: () => zoom(1.4) }, '+'), h('button', { 'aria-label': '縮小', onclick: () => zoom(1 / 1.4) }, '−'),
      h('button', { 'aria-label': '全体表示', onclick: () => { MAP.fitted = false; fit(); } }, '⌂')));
    requestAnimationFrame(() => { sizeMap(); fit(); });
    startMapLoop();
    return h('div', null, wrap, h('div', { class: 'nodata', style: 'margin-top:8px' }, 'ドラッグで移動・ホイール/ピンチで拡大縮小・列車をタップで詳細'));
  }
  function sizeMap() {
    const cv = MAP.cv, r = cv.parentElement.getBoundingClientRect(), d = window.devicePixelRatio || 1;
    if (!r.width) return; cv.width = r.width * d; cv.height = r.height * d; MAP.w = r.width; MAP.h = r.height; MAP.d = d;
  }
  function fit() {
    if (MAP.fitted || !S.stations.size || !MAP.w) return;
    let a = 1e9, b = -1e9, c = 1e9, e = -1e9;
    for (const s of S.stations.values()) { a = Math.min(a, s.x); b = Math.max(b, s.x); c = Math.min(c, s.z); e = Math.max(e, s.z); }
    MAP.cx = (a + b) / 2; MAP.cz = (c + e) / 2;
    MAP.scale = Math.min(MAP.w / Math.max(b - a + 400, 400), MAP.h / Math.max(e - c + 400, 400)); MAP.fitted = true;
  }
  function zoom(f, px, py) {
    px = px ?? MAP.w / 2; py = py ?? MAP.h / 2;
    const wx = (px - MAP.w / 2) / MAP.scale + MAP.cx, wz = (py - MAP.h / 2) / MAP.scale + MAP.cz;
    MAP.scale = Math.min(2, Math.max(0.004, MAP.scale * f));
    MAP.cx = wx - (px - MAP.w / 2) / MAP.scale; MAP.cz = wz - (py - MAP.h / 2) / MAP.scale;
  }
  function bindMap(cv) {
    const ptrs = new Map(); let pinch = 0, moved = 0;
    cv.addEventListener('pointerdown', e => { cv.setPointerCapture(e.pointerId); ptrs.set(e.pointerId, [e.offsetX, e.offsetY]); moved = 0; });
    cv.addEventListener('pointermove', e => {
      if (!ptrs.has(e.pointerId)) return; const o = ptrs.get(e.pointerId); ptrs.set(e.pointerId, [e.offsetX, e.offsetY]);
      if (ptrs.size === 1) { MAP.cx -= (e.offsetX - o[0]) / MAP.scale; MAP.cz -= (e.offsetY - o[1]) / MAP.scale; moved += Math.abs(e.offsetX - o[0]) + Math.abs(e.offsetY - o[1]); }
      else if (ptrs.size === 2) { const [p, q] = [...ptrs.values()]; const dd = Math.hypot(p[0] - q[0], p[1] - q[1]); if (pinch) zoom(dd / pinch, (p[0] + q[0]) / 2, (p[1] + q[1]) / 2); pinch = dd; moved += 9; }
    });
    const up = e => {
      const was = ptrs.has(e.pointerId); ptrs.delete(e.pointerId); if (ptrs.size < 2) pinch = 0;
      if (was && e.type === 'pointerup' && moved < 6) pickTrain(e.offsetX, e.offsetY);
    };
    cv.addEventListener('pointerup', up); cv.addEventListener('pointercancel', up);
    cv.addEventListener('wheel', e => { e.preventDefault(); zoom(e.deltaY < 0 ? 1.15 : 1 / 1.15, e.offsetX, e.offsetY); }, { passive: false });
  }
  function pickTrain(x, y) {
    let best = null, bd = 22 * 22;
    for (const p of MAP.pts) { const d = (p.x - x) ** 2 + (p.y - y) ** 2; if (d < bd) { bd = d; best = p.id; } }
    if (best) go('map', '', best);
  }
  // 駅間を 水平・垂直・45度 だけで結ぶ(鉄道路線図風)。同じ駅の組は向きによらず同じ形にして、共用区間が重なるようにする。
  function octiPath(a, b) {
    const flip = String(a.id) > String(b.id);
    const p = flip ? b : a, q = flip ? a : b;
    const dx = q.x - p.x, dz = q.z - p.z, ax = Math.abs(dx), az = Math.abs(dz);
    const sx = Math.sign(dx), sz = Math.sign(dz), d = Math.min(ax, az);
    const pts = [[p.x, p.z]];
    if (ax >= az) { pts.push([p.x + sx * (ax - d) / 2, p.z]); pts.push([p.x + sx * ((ax - d) / 2 + d), p.z + sz * d]); }
    else { pts.push([p.x, p.z + sz * (az - d) / 2]); pts.push([p.x + sx * d, p.z + sz * ((az - d) / 2 + d)]); }
    pts.push([q.x, q.z]);
    return flip ? pts.reverse() : pts;
  }
  const polyLen = pts => { let l = 0; for (let i = 1; i < pts.length; i++) l += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]); return l; };
  function polyAt(pts, f) {
    let want = polyLen(pts) * Math.min(1, Math.max(0, f));
    for (let i = 1; i < pts.length; i++) {
      const l = Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
      if (want <= l || i === pts.length - 1) { const k = l ? Math.min(1, want / l) : 0; return [pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * k, pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * k]; }
      want -= l;
    }
    return pts[0];
  }
  let segKey = '', segs = new Map();
  function buildSegs() {
    const key = S.topoRev + ':' + S.routes.length + ':' + S.stations.size;
    if (key === segKey) return; segKey = key; segs = new Map();
    for (const r of S.routes) {
      const ids = (r.stationIds || []).map(String); const list = [];
      for (let i = 0; i + 1 < ids.length; i++) {
        const a = station(ids[i]), b = station(ids[i + 1]);
        list.push(a && b ? { a: ids[i], b: ids[i + 1], pts: octiPath(a, b) } : null);
      }
      segs.set(String(r.id), list);
    }
  }
  // 列車の表示位置: 路線図(折れ線)の上に、前駅→次駅の進行率で置く。情報が足りなければ実座標。
  // 前駅→次駅の実座標上で、列車が何割進んでいるか(実位置の射影)。座標が無いときだけ従来の値。
  function realFrac(pa, pb, t) {
    const A = station(pa), B = station(pb);
    if (A && B && hasXZ(t)) { const r = projectChain([[A.x, A.z], [B.x, B.z]], t.x, t.z); if (r) return r.u; }
    return (t.progress || 0) / 100;
  }
  function trainXZ(t) {
    const r = route(t.routeId); const list = r && segs.get(String(r.id));
    if (list && t.prevStationId && t.nextStationId) {
      const pa = String(t.prevStationId), pb = String(t.nextStationId);
      for (const sg of list) {
        if (!sg) continue;
        if (sg.a === pa && sg.b === pb) return polyAt(sg.pts, realFrac(pa, pb, t));
        if (sg.a === pb && sg.b === pa) return polyAt(sg.pts, 1 - realFrac(pa, pb, t));
      }
    }
    if (t.atStop && t.currentStationId) { const s0 = station(t.currentStationId); if (s0) return [s0.x, s0.z]; }
    return [t.x, t.z];
  }
  function startMapLoop() {
    cancelAnimationFrame(MAP.raf);
    const css = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
    const loop = () => {
      if (parseHash().view !== 'map' || !MAP.cv.isConnected) return;
      const c = MAP.ctx, d = MAP.d || 1; if (!MAP.w) { sizeMap(); fit(); }
      buildSegs();
      c.setTransform(d, 0, 0, d, 0, 0); c.clearRect(0, 0, MAP.w, MAP.h);
      const X = x => (x - MAP.cx) * MAP.scale + MAP.w / 2, Z = z => (z - MAP.cz) * MAP.scale + MAP.h / 2;
      const ink = css('--ink'), card = css('--card'), sub = css('--sub');
      c.lineCap = 'round'; c.lineJoin = 'round';
      const lw = Math.max(4, Math.min(9, MAP.scale * 100));
      let ri = 0;
      for (const r of S.routes) {
        const list = segs.get(String(r.id)) || []; ri++;
        c.strokeStyle = r.color; c.lineWidth = lw; c.beginPath();
        let prevEnd = null;
        for (const sg of list) {
          if (!sg) { prevEnd = null; continue; }
          sg.pts.forEach((pt, i) => { if (i === 0 && prevEnd) c.lineTo(X(pt[0]), Z(pt[1])); else if (i === 0) c.moveTo(X(pt[0]), Z(pt[1])); else c.lineTo(X(pt[0]), Z(pt[1])); });
          prevEnd = sg.pts[sg.pts.length - 1];
        }
        c.stroke();
      }
      c.font = '700 11px sans-serif'; c.textBaseline = 'middle';
      const showLab = MAP.scale > 0.012;
      for (const s of S.stations.values()) {
        if (s._mk !== S.routes.length) { s._mk = S.routes.length; s._multi = new Set((s.routeIds || []).map(route).filter(Boolean).map(lineKeyOf)).size > 1; }
        const multi = s._multi;
        c.beginPath(); c.arc(X(s.x), Z(s.z), multi ? 6.5 : 5, 0, 7); c.fillStyle = card; c.fill(); c.lineWidth = 2.5; c.strokeStyle = ink; c.stroke();
        if (showLab) { c.lineWidth = 3; c.strokeStyle = card; c.strokeText(jp(s.name), X(s.x) + 10, Z(s.z) - 10); c.fillStyle = ink; c.fillText(jp(s.name), X(s.x) + 10, Z(s.z) - 10); }
      }
      MAP.pts = []; const sel = parseHash().train;
      for (const t of S.trains.values()) {
        const [tx, tz] = trainXZ(t);
        let p = MAP.disp.get(t.id); if (!p) { p = { x: tx, z: tz }; MAP.disp.set(t.id, p); }
        p.x += (tx - p.x) * .15; p.z += (tz - p.z) * .15;
        const px = X(p.x), py = Z(p.z); MAP.pts.push({ id: t.id, x: px, y: py });
        const col = css('--' + ({ normal: 'ok', delay: 'warn', bigDelay: 'bad', stopped: 'stop' }[t.status] || 'ok'));
        c.beginPath(); c.arc(px, py, 8, 0, 7); c.fillStyle = card; c.fill(); c.lineWidth = 3.5; c.strokeStyle = (route(t.routeId) || {}).color || col; c.stroke();
        c.beginPath(); c.arc(px, py, 3.2, 0, 7); c.fillStyle = col; c.fill();
        if (String(t.id) === sel) { c.beginPath(); c.arc(px, py, 14, 0, 7); c.lineWidth = 2; c.strokeStyle = ink; c.stroke(); }
        if (t.formation && (MAP.scale > 0.02 || t.status !== 'normal')) { c.fillStyle = sub; c.fillText(t.title || t.formation, px + 12, py + 4); }
      }
      MAP.raf = requestAnimationFrame(loop);
    };
    MAP.raf = requestAnimationFrame(loop);
  }
  window.addEventListener('resize', () => { if (MAP.cv) { sizeMap(); } });

  // ----- 車両 -----
  let fleetQ = '';
  const colOf = k => k === 'normal' ? 'ok' : k === 'delay' ? 'warn' : k === 'stopped' ? 'stop' : 'bad';
  function viewFleet() {
    const box = h('div', { id: 'fleet-list' });
    const search = h('input', { class: 'search', type: 'search', placeholder: '編成番号・形式・車庫・路線で検索', value: fleetQ, 'aria-label': '検索',
      oninput: e => { fleetQ = e.target.value; fillFleet(box); } });
    fillFleet(box);
    return h('div', null, search, box);
  }
  function fillFleet(box) {
    const q = fleetQ.trim().toLowerCase(); box.replaceChildren();
    const match = (...xs) => !q || xs.join(' ').toLowerCase().includes(q);
    if (!S.depots.length) { box.append(h('div', { class: 'empty' }, '車庫データを待っています…')); return; }
    let shown = 0;
    for (const d of S.depots) {
      const body = [];
      for (const sd of d.sidings || []) {
        const fs = (sd.formations || []).filter(f => match(f.id, f.series, jp(d.name), jp(sd.name), sd.typeName));
        const un = (sd.unassigned || []).filter(u => match('未割当', u.label, u.series, jp(d.name), jp(sd.name), jp(u.routeName)));
        if (!fs.length && !un.length && q) continue;
        shown += fs.length + un.length;
        const cards = [];
        for (const f of fs) {
          const live = f.state === 'service' && f.trainId; const k = f.status || 'normal';
          cards.push(h('button', { class: `fm ${live ? 'live' : 'dep'}`, style: `--sc:var(--${colOf(k)})`, onclick: () => live && go('fleet', '', f.trainId) },
            h('b', null, f.title || f.id), `${f.series || sd.typeName || '形式不明'} ${f.cars ? f.cars + '両' : ''}`, h('br'),
            live ? h('span', { class: `s-${k}` }, `${st(k).mark} ${jp(f.routeName) || '運行中'}`) : h('span', { class: 'nodata' }, '車庫')));
        }
        for (const u of un) {
          cards.push(h('button', { class: 'fm live un', style: `--sc:var(--${colOf(u.status)})`, onclick: () => go('fleet', '', u.trainId) },
            h('b', null, '番号未設定'), `${u.series || sd.typeName || '形式不明'} ${u.cars}両`, h('br'),
            h('span', { class: `s-${u.status}` }, `${st(u.status).mark} ${jp(u.routeName) || '運行中'}`)));
        }
        body.push(h('div', { class: 'siding' },
          h('h4', null, `${jp(sd.name)}`, sd.typeName ? ` ・ ${sd.typeName}` : '', ` ・ 登録 ${sd.registered}編成`, sd.range ? ` ・ 車番 ${sd.range.text}` : ' ・ 車番範囲 未設定'),
          cards.length ? h('div', { class: 'fl' }, cards) : h('div', { class: 'nodata' }, '編成がありません')));
      }
      if (body.length) box.append(h('section', { class: 'depot', style: `--c:${d.color || '#888'}` }, h('h3', null, nameEl(d.name)), body));
    }
    if (!box.children.length) box.append(h('div', { class: 'empty' }, q ? '該当する編成がありません' : '車庫線がありません'));
    else if (!q && S.depots.every(d => (d.sidings || []).every(sd => !sd.range && !(sd.formations || []).length))) {
      box.prepend(h('div', { class: 'banner', style: 'margin:0 0 10px' }, 'ゲーム内で /mtrops を開き、車庫線ごとに車番の範囲(例: 1001〜1100)を入れると、出庫した列車へ順に番号が付きます。'));
    }
  }

  // ----- 詳細シート -----
  function drawSheet() {
    const el = $('#sheet'); const id = parseHash().train; const t = id && S.trains.get(id);
    if (!id) { el.hidden = true; return; }
    el.hidden = false; el.replaceChildren();
    const close = () => go(parseHash().view, parseHash().arg);
    if (!t) { el.append(h('div', { class: 'sh-head' }, h('h2', null, '列車が見つかりません'), h('button', { class: 'x', onclick: close, 'aria-label': '閉じる' }, '×')), h('div', { class: 'sh-sec nodata' }, 'この列車は運行を終えたか、データから消えています。')); return; }
    const r = route(t.routeId) || { color: '#888', name: '' };
    el.style.setProperty('--c', r.color);
    const ids = (r.stationIds || []).map(String); const tp = trainPos(t, ids);
    const dc = t.status === 'normal' ? 'ok' : t.status === 'delay' ? 'warn' : t.status === 'stopped' ? 'stop' : 'bad';
    // 進行方向の残り駅(現在位置から終点まで)
    const ahead = [];
    if (tp) {
      const cur = Math.floor(tp.pos + (tp.dir > 0 ? (Number.isInteger(tp.pos) ? 0 : 1) : 0));
      if (tp.dir > 0) for (let i = Math.ceil(tp.pos); i < ids.length; i++) ahead.push(i);
      else for (let i = Math.floor(tp.pos); i >= 0; i--) ahead.push(i);
    }
    const carsEl = () => h('div', { class: 'tl-cars', style: `--c:${r.color}` },
      h('span', { class: 'nose' + (tp && tp.dir < 0 ? ' up' : '') }),
      Array.from({ length: t.cars || 0 }, (_, i) => h('span', { class: 'car2', title: (t.carNumbers || [])[i] || '' }, h('span', null, i + 1), h('small', null, ((t.carNumbers || [])[i] || '').split('-')[0] || ''))));
    const tl = h('div', { class: 'tl', style: `--c:${r.color}` });
    if (t.atStop && ahead.length && Number.isInteger(tp.pos)) { /* 停車中: 現在駅の行に編成を置く */ }
    ahead.forEach((i, k) => {
      const s2 = station(ids[i]) || { name: ids[i] };
      tl.append(h('div', { class: 'tl-row' },
        h('div', { class: 'tl-node' }, codeEls(stationCode(r, ids[i]))),
        h('div', { class: 'tl-name' }, jp(s2.name), en(s2.name) ? h('small', null, en(s2.name)) : null,
          k === 0 ? h('em', null, t.atStop ? '停車中' : 'まもなく') : null)));
      if (k === 0 && t.cars) tl.append(carsEl());
      else if (k > 0 && t.cars && k < 3) tl.append(h('div', { class: 'tl-gap' }));
    });
    el.append(...[
      h('div', { class: 'sh-head', style: `--c:${r.color}` }, h('div', { class: 'sh-title' }, '列車詳細情報'),
        h('button', { class: 'x', onclick: close, 'aria-label': '閉じる' }, '×')),
      h('div', { class: 'sh-hero' },
        h('div', { class: 'nodata' }, `${jp(r.name)} ・ ${t.title || t.formation || '番号未設定'}${t.series ? ' ・ ' + t.series : ''}`),
        h('div', { class: 'dest' }, h('b', null, jp(t.destination) || '回送'), ' 行き'),
        h('div', { class: 'hero-row' }, h('span', { class: `s-${dc}`, style: 'font-weight:800' }, `遅れ：${(t.delaySec || 0) < 30 ? 'なし' : fmtDelay(t.delaySec)}`), chip(t.status)),
        t.manual ? h('div', { class: 'nodata' }, '手動運転中(遅れ判定の対象外)') : null),
      h('div', { class: 'sh-sec' }, h('dl', { class: 'dl' },
        row('現在', t.atStop ? `${jp(t.currentStation)} 停車中${t.dwellRemainSec != null ? `(あと${t.dwellRemainSec}秒)` : ''}` : (t.prevStation ? `${jp(t.prevStation)} → ${jp(t.nextStation)}` : null)),
        row('車両', t.typeName || t.series),
        row('速度', `${Math.round(t.speedKmh)} km/h`), row('乗客', t.passengers != null ? `${t.passengers}人` : null),
        row('車庫', t.depotName && `${jp(t.depotName)} ${t.sidingName || ''}`))),
      ahead.length ? h('div', { class: 'sh-sec' }, h('div', { class: 'nodata', style: 'margin-bottom:8px' }, 'これから停まる駅'), tl) : null,
      h('div', { class: 'sh-sec nodata' }, '容量の都合で省略された項目は表示されません。')].filter(Boolean));
  }
  function row(k, v) { return v == null || v === '' ? null : [h('dt', null, k), h('dd', null, v)]; }

  // ---------- render ----------
  let pressing = false, renderPending = false;
  const press = () => { pressing = true; };
  const release = () => { setTimeout(() => { pressing = false; if (renderPending) { renderPending = false; render(); } }, 80); };
  window.addEventListener('pointerdown', press, { passive: true, capture: true });
  ['pointerup', 'pointercancel', 'touchend', 'touchcancel', 'dragend'].forEach(ev => window.addEventListener(ev, release, { passive: true, capture: true }));
  window.addEventListener('blur', () => { pressing = false; });
  // 画面が隠れている間は更新を止め、戻ったらすぐ更新する
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { poll(); loadFleet(); } });
  let lastInput = 0;
  ['scroll', 'wheel', 'touchstart', 'touchmove', 'touchend', 'pointerdown'].forEach(ev => window.addEventListener(ev, () => { lastInput = Date.now(); }, { passive: true, capture: true }));
  let touching = false;
  window.addEventListener('touchstart', () => { touching = true; }, { passive: true, capture: true });
  ['touchend', 'touchcancel'].forEach(ev => window.addEventListener(ev, () => { touching = false; }, { passive: true, capture: true }));
  function busyScrolling() { return touching || Date.now() - lastInput < 1200; }
  function render(force) {
    if (!force && pressing) { renderPending = true; return; }   // 押している最中は画面を入れ替えない
    try { renderNow(force); } catch (e) {
      console.error(e);
      const b = $('#banner'); b.hidden = false; b.className = 'banner err'; b.textContent = '表示中にエラーが起きました。自動で再試行します。(' + (e && e.message || e) + ')';
    }
  }
  function renderNow(force) {
    const p = parseHash(); const view = $('#view');
    drawTabs(p.view);
    const b = $('#banner');
    if (!S.ok) { b.hidden = false; b.className = 'banner err'; b.textContent = S.lastOk ? '接続が途切れました。再接続しています…' : 'MODに接続できません。Minecraftを起動し、ワールドに入ってください。'; }
    else if (S.live && (!S.live.connected || S.live.stale)) { b.hidden = false; b.className = 'banner'; b.textContent = 'サーバーからの更新が止まっています。表示は最新ではありません。'; }
    else b.hidden = true;
    if (S.live) $('#clock').textContent = S.live.clock || '--:--';
    const keep = p.view === 'map' && MAP.cv && MAP.cv.isConnected;
    if (!keep) {
      const x = window.scrollY; const inner = [...document.querySelectorAll('#view .rlist, #view .rpanel')].map(e => e.scrollTop);
      const v = { home: viewHome, diagram: () => viewDiagram(p.arg), map: viewMap, fleet: viewFleet, incidents: viewIncidents, ghost: viewGhost }[p.view] || viewHome;
      const key = p.view + '/' + p.arg;
      const typing = key === renderedKey && document.activeElement && document.activeElement.classList.contains('search');
      if (typing && p.view === 'fleet') { fillFleet($('#fleet-list')); }
      else if (typing && refill && (p.view === 'home' || p.view === 'diagram')) { refill(); }
      else if (!force && busyScrolling() && key === renderedKey) { /* 操作中は作り直さない(慣性スクロールやタッチが途切れるため) */ }
      else { refill = null; const hh = view.offsetHeight; view.style.minHeight = hh + 'px'; const nv = v(); if (nv.outerHTML === view.firstElementChild?.outerHTML && view.childElementCount === 1) { view.style.minHeight = ''; } else view.replaceChildren(nv); requestAnimationFrame(() => { view.style.minHeight = ''; }); window.scrollTo({ top: x, left: 0, behavior: 'instant' }); document.querySelectorAll('#view .rlist, #view .rpanel').forEach((e, i) => { if (inner[i]) e.scrollTop = inner[i]; }); renderedKey = key; }
    }
    drawSheet();
    if (p.view === 'diagram' && p.sid && scrolledFor !== location.hash) {
      const el = document.querySelector(`[data-sid="${CSS.escape(p.sid)}"]`);
      if (el) { scrolledFor = location.hash; el.scrollIntoView({ block: 'center' }); }
    }
  }
  let scrolledFor = '', renderedKey = '';
  let lastView = '';
  window.addEventListener('hashchange', () => {
    const v = parseHash().view; if (v !== lastView) { lastView = v; window.scrollTo(0, 0); }
    if (v !== 'map') cancelAnimationFrame(MAP.raf); render(true);
  });
  // テーマ
  const root = document.documentElement;
  try { const t = localStorage.getItem('mtrops-theme'); if (t) root.dataset.theme = t; } catch (e) { /* 無効でも動作 */ }
  $('#theme').addEventListener('click', () => {
    const dark = root.dataset.theme ? root.dataset.theme === 'dark' : matchMedia('(prefers-color-scheme:dark)').matches;
    root.dataset.theme = dark ? 'light' : 'dark'; try { localStorage.setItem('mtrops-theme', root.dataset.theme); } catch (e) { /* */ }
  });
  lastView = parseHash().view;
  render(true); poll(); loadFleet();
  setInterval(() => { if (!document.hidden) poll(); }, 1000); setInterval(() => { if (!document.hidden) loadFleet(); }, 5000);
})();
