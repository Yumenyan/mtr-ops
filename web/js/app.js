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
  const SVG = 'http://www.w3.org/2000/svg';
  function icon(path) { const s = document.createElementNS(SVG, 'svg'); s.setAttribute('viewBox', '0 0 24 24'); s.innerHTML = path; return s; } // 固定文字列のみ
  function fixedSvg(cls, html) { const s = h('span', { class: cls }); s.innerHTML = html; return s; }                                          // 固定文字列のみ

  // 駅名に埋め込まれた駅番号 [AA01] は表示名から取り除く(駅番号は別の丸バッジに出す)
  const CODE_RE = /\s*[\[［]\s*([A-Za-z]+)\s*(-?)\s*(\d+)\s*[\]］]\s*/g;
  const clean = n => (n || '').replace(CODE_RE, ' ').replace(/\s+/g, ' ').trim();
  const jp = n => clean((n || '').split('|')[0]);
  const en = n => clean((n || '').split('|')[1] || '');
  const nameEl = n => { const p = h('span', null, jp(n)); return en(n) ? h('span', null, p, h('span', { class: 'en' }, en(n))) : p; };
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
  function stationCode(letters, stationId) {
    const st0 = S.stations.get(String(stationId)); if (!st0 || !letters || !letters.length) return null;
    const re = new RegExp(CODE_RE.source, 'g'); let m;
    while ((m = re.exec(st0.name || ''))) {
      const lt = m[1].toUpperCase();
      if (letters.includes(lt)) return { letters: lt, num: m[2] + m[3] };
    }
    return null;
  }
  function stationCodes(st0) { const out = []; const re = new RegExp(CODE_RE.source, 'g'); let m; while ((m = re.exec((st0 && st0.name) || ''))) out.push({ l: m[1].toUpperCase(), n: parseInt(m[3], 10) || 0 }); return out; }
  const codeEls = c => c ? [h('span', null, c.letters), h('span', null, c.num)] : [];
  const fmtDelay = s => s < 30 ? '定刻' : s < 60 ? `約${Math.round(s)}秒遅れ` : `約${Math.round(s / 60)}分遅れ`;
  const pad2 = n => String(n).padStart(2, '0');
  const fmtM = m => Math.round(m).toLocaleString('ja-JP') + ' m';

  const STATUS = {
    normal:   { label: '平常運転',   short: '平常',     mark: '●', rank: 0 },
    delay:    { label: '遅延',       short: '遅延',     mark: '◐', rank: 1 },
    bigDelay: { label: '大幅遅延',   short: '大幅遅延', mark: '◉', rank: 2 },
    stopped:  { label: '運転見合わせ', short: '停止',   mark: '■', rank: 3 }
  };
  const st = k => STATUS[k] || STATUS.normal;
  const chip = k => h('span', { class: `chip s-${k}` }, h('i', null, st(k).mark), h('span', null, st(k).short));
  const worst = ks => ks.reduce((a, k) => st(k).rank > st(a).rank ? k : a, 'normal');
  const colOf = k => k === 'normal' ? 'ok' : k === 'delay' ? 'warn' : k === 'stopped' ? 'stop' : 'bad';

  // ---------- state ----------
  const S = {
    live: null, routes: [], stations: new Map(), routeMap: new Map(), formations: [], depots: [],
    ghosts: { suspects: [], past: [] }, topoRev: -1, ok: false, lastOk: 0, err: '', trains: new Map(), routeLive: new Map(),
    groups: [], groupsRev: 0, groupByRoute: new Map(), groupByKey: new Map(), selKey: '', zoom: false, distSel: '', distCache: new Map()
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
    buildGroups();
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
      S.live = d.status; S.ok = true; S.lastOk = Date.now(); S.err = '';
      S.trains = new Map((d.trains || []).map(t => [String(t.id), t]));
      S.routeLive = new Map((d.routes || []).map(r => [String(r.id), r]));
      if (d.status.topologyRev !== S.topoRev) { await loadStatic(); S.topoRev = d.status.topologyRev; }
    } catch (e) { S.ok = false; S.err = String(e.message || e); }
    polling = false; render();
  }

  // ---------- 路線のまとめ: 同じ路線名(括弧書きを除く)・同じナンバリング記号の路線は1本の「ライン」に自動でマージする ----------
  const baseName = r => jp(r.name).replace(/[(（][^)）]*[)）]/g, '').replace(/\s+/g, ' ').trim() || jp(r.name) || String(r.id);
  const nameKey = r => baseName(r).replace(/\s+/g, '').toLowerCase();
  function buildGroups() {
    const parent = new Map(S.routes.map(r => [String(r.id), String(r.id)]));
    const find = x => { while (parent.get(x) !== x) { parent.set(x, parent.get(parent.get(x))); x = parent.get(x); } return x; };
    const union = (a, b) => { a = find(a); b = find(b); if (a !== b) parent.set(b, a); };
    const byName = new Map(), byLetter = new Map();
    for (const r of S.routes) {
      const id = String(r.id);
      const nk = nameKey(r); if (byName.has(nk)) union(byName.get(nk), id); else byName.set(nk, id);
      for (const l of routeLetters(r)) { if (byLetter.has(l)) union(byLetter.get(l), id); else byLetter.set(l, id); }
    }
    const map = new Map();
    for (const r of S.routes) { const k = find(String(r.id)); let g = map.get(k); if (!g) { g = { members: [], ids: new Set() }; map.set(k, g); } g.members.push(r); g.ids.add(String(r.id)); }
    const out = [];
    for (const g of map.values()) {
      g.members.sort((a, b) => (b.stationIds || []).length - (a.stationIds || []).length);
      g.rep = g.members[0];
      g.key = 'g' + [...g.ids].sort()[0];
      g.name = g.members.map(baseName).filter(Boolean).sort((a, b) => a.length - b.length)[0] || baseName(g.rep);
      const letters = routeLetters(g.rep).slice();
      for (const m of g.members) for (const l of routeLetters(m)) if (!letters.includes(l)) letters.push(l);
      g.letters = letters; g.letter = letters[0] || '';
      g.color = g.rep.color; g._chain = null;
      out.push(g);
    }
    S.groups = out.sort((a, b) => a.name.localeCompare(b.name, 'ja') || a.key.localeCompare(b.key));
    S.groupByKey = new Map(S.groups.map(g => [g.key, g]));
    S.groupByRoute = new Map();
    for (const g of S.groups) for (const id of g.ids) S.groupByRoute.set(id, g);
    S.groupsRev++;
  }
  function groupAgg(g) {
    const a = { trainCount: 0, maxDelaySec: 0, status: 'normal', delayed: 0 };
    for (const m of g.members) {
      const l = S.routeLive.get(String(m.id)); if (!l) continue;
      a.trainCount += l.trainCount || 0; a.delayed += l.delayedCount || 0; a.maxDelaySec = Math.max(a.maxDelaySec, l.maxDelaySec || 0);
      if (st(l.status).rank > st(a.status).rank) a.status = l.status in STATUS ? l.status : 'normal';
    }
    return a;
  }
  // 路線シンボル: 文字なしの路線色の丸
  function lsym(g, cls, onclick, badge) {
    return h(onclick ? 'button' : 'span', { class: `lsym ${cls || ''}`, style: `--c:${g.color}`, type: onclick ? 'button' : null, title: g.name, 'aria-label': g.name, onclick },
      badge && badge !== 'normal' ? h('span', { class: `tbadge s-${badge}` }) : null);
  }
  // ライン全体の駅の並び: 駅ナンバリングがあればその順、無ければ最長の系統。他の系統にしかない駅は元の並びを保って差し込む。
  function chainOf(g) {
    const key = S.topoRev + ':' + S.routes.length;
    if (g._chain && g._chainKey === key) return g._chain;
    const member = new Set(); for (const m of g.members) for (const id of m.stationIds || []) member.add(String(id));
    let base = null;
    if (g.letter) {
      const list = [];
      for (const id of member) { const c = stationCodes(station(id)).find(c => c.l === g.letter); if (c) list.push({ id, n: c.n }); }
      if (list.length >= 2) { list.sort((a, b) => a.n - b.n); base = list.map(x => x.id); }
    }
    if (!base) base = (g.rep.stationIds || []).map(String).filter((x, i, a) => a.indexOf(x) === i);
    const idx = new Set(base);
    for (const m of g.members) {
      let anchor = -1;
      for (const raw of m.stationIds || []) {
        const id = String(raw);
        if (idx.has(id)) { anchor = base.indexOf(id); continue; }
        base.splice(anchor + 1, 0, id); idx.add(id); anchor++;
      }
    }
    g._chain = { ids: base, idx: new Map(base.map((x, i) => [x, i])) }; g._chainKey = key;
    return g._chain;
  }
  // 実位置(x,z)を駅をつないだ折れ線へ射影し、折れ線上の位置(区間番号+割合)を返す。
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
  const hasXZ = t => Number.isFinite(t.x) && Number.isFinite(t.z) && !(t.x === 0 && t.z === 0);
  // 列車の路線(ライン)上の位置(駅indexの小数)と進行方向(+1: 駅番号が増える側 / -1: 減る側)
  function linePos(t, chain) {
    const g = id => { const v = chain.idx.get(String(id)); return v === undefined ? -1 : v; };
    const d = g(t.destStationId), a = g(t.prevStationId), b = g(t.nextStationId);
    let pos = -1;
    if (t.atStop && t.currentStationId && g(t.currentStationId) >= 0) pos = g(t.currentStationId);
    else if (a >= 0 && b >= 0 && a !== b && hasXZ(t)) {
      const lo = Math.min(a, b), hi = Math.max(a, b); const pts = [];
      for (let k = lo; k <= hi; k++) { const s0 = station(chain.ids[k]); if (!s0) { pts.length = 0; break; } pts.push([s0.x, s0.z]); }
      const pr = pts.length >= 2 ? projectChain(pts, t.x, t.z) : null;
      pos = pr ? lo + pr.i + pr.u : a + (b - a) * (t.progress || 0) / 100;
    } else if (a >= 0 && b >= 0 && a !== b) pos = a + (b - a) * (t.progress || 0) / 100;
    else if (b >= 0) pos = b; else if (a >= 0) pos = a;
    if (pos < 0) return null;
    let dir = 0;
    if (d >= 0 && Math.abs(d - pos) > 0.01) dir = d > pos ? 1 : -1;
    else if (a >= 0 && b >= 0 && a !== b) dir = b > a ? 1 : -1;
    else if (t.atStop && b >= 0 && b !== pos) dir = b > pos ? 1 : -1;
    if (!dir) dir = pos >= chain.ids.length - 1 ? -1 : 1;
    return { pos, dir };
  }
  // 列車詳細用: その列車の路線(停車順)の中での位置
  function trainPos(t, ids) {
    const at = id => ids.indexOf(String(id));
    if (t.atStop && t.currentStationId) { const i = at(t.currentStationId); if (i >= 0) return { pos: i, dir: 1 }; }
    if (t.prevStationId && t.nextStationId) {
      for (let i = 0; i < ids.length - 1; i++) {
        const a = ids[i], b = ids[i + 1];
        if (a === String(t.prevStationId) && b === String(t.nextStationId)) return { pos: i + (t.progress || 0) / 100, dir: 1 };
        if (b === String(t.prevStationId) && a === String(t.nextStationId)) return { pos: i + 1 - (t.progress || 0) / 100, dir: -1 };
      }
    }
    const n = at(t.nextStationId); if (n >= 0) return { pos: n > 0 ? n - 0.5 : 0, dir: 1 };
    return null;
  }

  // ---------- router ----------
  function parseHash() {
    const [path, q] = (location.hash.replace(/^#\/?/, '') || 'home').split('?');
    const p = path.split('/'); const params = new URLSearchParams(q || '');
    let view = p[0] || 'home';
    if (['diagram', 'map', 'incidents'].includes(view)) view = 'home';   // 旧版のリンク
    return { view, arg: p[1] || '', train: params.get('t') || '', sid: params.get('s') || '', m: params.get('m') || '' };
  }
  function go(view, arg, train, sid, m) {
    const q = [train ? 't=' + encodeURIComponent(train) : '', sid ? 's=' + encodeURIComponent(sid) : '', m ? 'm=' + encodeURIComponent(m) : ''].filter(Boolean).join('&');
    location.hash = '#/' + view + (arg ? '/' + arg : '') + (q ? '?' + q : '');
  }
  const TABS = [
    ['home', 'ホーム', '<path d="M3 11 12 3l9 8v10h-6v-6H9v6H3z"/>'],
    ['lines', '路線一覧', '<path d="M4 6h16M4 12h16M4 18h10"/>'],
    ['fleet', '車両', '<rect x="3" y="6" width="18" height="10" rx="3"/><path d="M7 20l1-4M17 20l-1-4M3 11h18"/>'],
    ['ghost', 'スタック調査', '<path d="M5 20V10a7 7 0 0 1 14 0v10l-3-2-2 2-2-2-2 2-2-2z M9.5 11h.01 M14.5 11h.01"/>'],
    ['settings', '設定', '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1"/>']
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
    const gh = (S.ghosts.suspects || []).length;
    for (const [id, { btn, dot }] of tabEls) {
      if (id === cur) btn.setAttribute('aria-current', 'page'); else btn.removeAttribute('aria-current');
      const n = id === 'ghost' ? gh : 0;
      dot.hidden = !n; if (dot.textContent !== String(n || '')) dot.textContent = n || '';
    }
  }

  // ---------- ホーム(路線セレクタ・運行状況・列車位置) ----------
  const SP_BASE = 210, PAD = 96;                    // 駅間(px)。詰まっても列車を重ねず、縦の段に積むので広めに取る
  const TRAIN_SVG = '<svg viewBox="0 0 76 30" aria-hidden="true"><rect x="2" y="3" width="62" height="24" rx="8" fill="currentColor"/><rect x="10" y="9" width="38" height="9" rx="4" fill="#fff" fill-opacity=".86"/><path d="M62 3 74 15 62 27z" fill="#26292e"/></svg>';
  let dscrollLeft = {}, dscrollInit = new Set();

  function pickGroup(arg) {
    if (arg) { const g = S.groupByRoute.get(String(arg)) || S.groupByKey.get(arg); if (g) { S.selKey = g.key; return g; } }
    let g = S.groupByKey.get(S.selKey);
    if (!g) {
      g = S.groups.find(x => groupAgg(x).trainCount > 0) || S.groups[0];
      if (g) S.selKey = g.key;
    }
    return g;
  }
  function statusText(g, a) {
    if (!a.trainCount) return `${g.name}は現在、運行中の列車がありません。`;
    if (a.status === 'normal') return `${g.name}は現在、平常どおり運転しています。`;
    const late = a.maxDelaySec >= 60 ? `(最大約${Math.round(a.maxDelaySec / 60)}分の遅れ)` : '';
    if (a.status === 'stopped') return `${g.name}は現在、停止している列車があります。${late}`;
    if (a.status === 'bigDelay') return `${g.name}は現在、大幅に遅れている列車があります。${late}`;
    return `${g.name}は現在、一部の列車に遅れが出ています。${late}`;
  }
  function positionEl(g) {
    const chain = chainOf(g); const ids = chain.ids; const SP = S.zoom ? SP_BASE * 1.45 : SP_BASE;
    if (!ids.length) return h('div', { class: 'empty' }, 'この路線の駅リストは容量の都合で省略されています。');
    const sel = parseHash();
    const placed = [];
    for (const t of S.trains.values()) {
      if (!g.ids.has(String(t.routeId))) continue;
      const p = linePos(t, chain); if (p) placed.push({ t, p, x: PAD + p.pos * SP });
    }
    placed.sort((a, b) => a.x - b.x);
    // 同じ向きの列車は、横が重なったら次の段へ積む(段数に上限なし = 詰まっても全列車を表示する)
    const TW = 140, LH = 82;
    const lanes = { 1: [], '-1': [] };
    for (const it of placed) {
      const L = lanes[it.p.dir > 0 ? 1 : -1]; let k = 0;
      while (L[k] !== undefined && L[k] + 6 > it.x - TW / 2) k++;
      L[k] = it.x + TW / 2; it.lane = k;
    }
    const upN = Math.max(1, lanes[1].length), downN = Math.max(1, lanes[-1].length);
    const lineY = upN * LH + 22, belowTop = lineY + 92;
    const width = PAD * 2 + (ids.length - 1) * SP, height = belowTop + downN * LH + 14;
    const cv = h('div', { class: 'dcanvas', style: `width:${width}px;height:${height}px;--c:${g.color}` });
    cv.append(h('div', { class: 'dline', style: `top:${lineY}px` }));
    const lt = g.letter ? [g.letter] : g.letters;
    ids.forEach((id, i) => {
      const s0 = station(id) || { name: id }; const code = stationCode(lt, id);
      cv.append(h('div', { class: 'dst' + (sel.sid === id ? ' hl' : ''), data: { sid: id }, style: `left:${PAD + i * SP}px;top:${lineY}px` },
        h('div', { class: 'nd' + (code ? '' : ' plain') }, codeEls(code)),
        h('div', { class: 'nm' }, h('b', null, jp(s0.name)), en(s0.name) ? h('span', { class: 'en' }, en(s0.name)) : null)));
    });
    for (const it of placed) {
      const { t, p } = it; const up = p.dir > 0; const tr = route(t.routeId);
      const k = it.lane; const selected = sel.train === String(t.id);
      const top = up ? lineY - 14 - (k + 1) * LH + 14 : belowTop + k * LH;
      const mins = Math.max(1, Math.ceil((t.delaySec || 0) / 60));
      const label = t.destination ? `${jp(t.destination)}行き` : (t.atStop || t.onRoute ? '行先不明' : '回送');
      cv.append(h('button', {
        class: `tr ${up ? 'up' : 'down'} ${up ? 'right' : 'left'}${selected ? ' sel' : ''}`, type: 'button',
        style: `left:${it.x}px;top:${top}px;--tw:${TW}px;--sc:var(--${colOf(t.status)});--c:${(tr && tr.color) || g.color}`,
        title: `${t.title || t.formation || ''} ${st(t.status).label}`, 'aria-label': `${label} ${t.title || t.formation || ''} ${st(t.status).label}`,
        onclick: () => go('home', g.rep.id, t.id)
      },
      h('span', { class: 'tbub' }, label, (t.title || t.formation) ? h('small', null, t.title || t.formation) : null),
      h('span', { class: 'ticon' }, fixedSvg('', TRAIN_SVG), (t.status !== 'normal' || (t.delaySec || 0) >= 30)
        ? h('span', { class: `dbadge s-${t.status}` }, t.status === 'stopped' ? '停止' : `+${mins}`) : null)));
    }
    const sc = h('div', { class: 'dscroll', id: 'dscroll', data: { key: g.key } }, cv);
    return h('div', { class: 'pos' },
      placed.length ? null : h('div', { class: 'empty', style: 'position:absolute;left:0;right:0;top:8px;z-index:5;pointer-events:none' }, '運行中の列車はありません'),
      sc, h('div', { class: 'pos-foot' },
        h('span', null, `${new Date(S.lastOk || Date.now()).toLocaleTimeString('ja-JP', { hour12: false })} 更新 ※実際の列車位置等と異なる場合があります ／ ${placed.length}本 運行中`),
        h('button', { type: 'button', onclick: () => { S.zoom = !S.zoom; render(true); } }, S.zoom ? '縮小' : '拡大', ' ⤢')));
  }
  const ACTS = [
    ['stations', '駅一覧・乗換', '<circle cx="6" cy="6" r="2.5"/><circle cx="18" cy="18" r="2.5"/><path d="M8.5 6H14a4 4 0 0 1 0 8H10a4 4 0 0 0 0 4h5.5"/>'],
    ['dist', '駅間距離', '<path d="M3 17 17 3l4 4L7 21z M7 13l2 2 M10 10l2 2 M13 7l2 2"/>'],
    ['fleet', '車両一覧', '<rect x="3" y="6" width="18" height="10" rx="3"/><path d="M7 20l1-4M17 20l-1-4M3 11h18"/>'],
    ['ghost', 'スタック調査', '<path d="M5 20V10a7 7 0 0 1 14 0v10l-3-2-2 2-2-2-2 2-2-2z M9.5 11h.01 M14.5 11h.01"/>']
  ];
  const metaOf = a => `${a.trainCount}本運行${a.maxDelaySec >= 60 ? ` ・最大${Math.round(a.maxDelaySec / 60)}分遅れ` : ''}`;
  const setChip = (el, k) => { const c = `chip s-${k}`; if (el.className !== c) el.className = c; const t = `${st(k).mark} ${st(k).short}`; if (el.textContent !== t) el.textContent = t; };
  const matchGroup = (g, q) => { const w = q.toLowerCase().trim().split(/[\s\u3000]+/).filter(Boolean); const text = (g.name + ' ' + g.members.map(m => `${m.name} ${m.number || ''}`).join(' ')).toLowerCase(); return w.every(x => text.includes(x)); };
  // 左の路線リストは一度だけ作り、以後は文字と色を書き換えるだけにする(並びも固定。作り直し・並べ替えをしないのでスクロールや選択が動かない)
  const HOME = { root: null, rev: -1, list: null, main: null, items: new Map(), q: '', listScroll: 0, lastSel: '', mainKey: '' };
  function buildHomeRoot() {
    HOME.items = new Map();
    const search = h('input', { class: 'search lsearch', type: 'search', placeholder: '路線を検索', value: HOME.q, 'aria-label': '路線を検索',
      oninput: e => { HOME.q = e.target.value; for (const [k, it] of HOME.items) it.el.hidden = !matchGroup(S.groupByKey.get(k), HOME.q); } });
    const list = h('div', { class: 'llist', onscroll: e => { HOME.listScroll = e.target.scrollTop; } });
    for (const g of S.groups) {
      const meta = h('small'), badge = h('span');
      const el = h('button', { class: 'litem', type: 'button', style: `--c:${g.color}`, hidden: !matchGroup(g, HOME.q), onclick: () => go('home', g.rep.id) },
        lsym(g, 'md'), h('span', { class: 'ltx' }, h('b', null, g.name), meta), badge);
      HOME.items.set(g.key, { el, meta, badge }); list.append(el);
    }
    HOME.list = list; HOME.main = h('div', { class: 'hmain' });
    HOME.root = h('div', { class: 'hsplit' }, h('aside', { class: 'lside' }, search, list), HOME.main);
    HOME.rev = S.groupsRev; HOME.lastSel = ''; HOME.mainKey = '';
  }
  function updateHomeList(sel) {
    for (const g of S.groups) {
      const it = HOME.items.get(g.key); if (!it) continue; const a = groupAgg(g);
      const m = metaOf(a); if (it.meta.textContent !== m) it.meta.textContent = m;
      setChip(it.badge, a.status);
      it.el.classList.toggle('sel', g === sel);
    }
    if (HOME.lastSel !== sel.key) {
      HOME.lastSel = sel.key; const it = HOME.items.get(sel.key), L = HOME.list;
      if (it) { if (it.el.offsetTop < L.scrollTop) L.scrollTop = it.el.offsetTop - 8; else if (it.el.offsetTop + it.el.offsetHeight > L.scrollTop + L.clientHeight) L.scrollTop = it.el.offsetTop + it.el.offsetHeight - L.clientHeight + 8; HOME.listScroll = L.scrollTop; }
    }
  }
  function mainHome(g) {
    const a = groupAgg(g);
    const sub = g.members.length > 1 ? `${g.members.length}系統をまとめて表示` : (g.rep.destination ? `${jp(g.rep.destination)}方面` : '');
    return h('div', null,
      h('div', { class: 'lhead' }, lsym(g, 'xl'), h('div', { class: 'lname' }, g.name, sub ? h('small', null, sub) : null)),
      h('div', { class: 'stat' },
        h('div', { class: 'stat-h' }, h('span', { class: `ring s-${a.status}` }), h('b', null, st(a.status).label), a.status === 'normal' ? null : chip(a.status)),
        h('p', null, statusText(g, a)),
        h('p', { class: 'sub' }, `運行中 ${a.trainCount} 本${a.delayed ? ` ／ 遅れのある列車 ${a.delayed} 本` : ''}`)),
      positionEl(g),
      h('div', { class: 'acts' }, ACTS.map(([id, label, p]) => h('button', { class: 'act', type: 'button',
        onclick: () => (id === 'fleet' || id === 'ghost') ? go(id) : go('home', g.rep.id, '', '', id) }, icon(p), h('span', null, label)))));
  }
  function renderHome(view, force) {
    if (!S.groups.length) { HOME.root = null; view.replaceChildren(h('div', { class: 'empty' }, S.ok ? '路線データを待っています…' : 'MODに接続すると路線が表示されます')); return; }
    const p = parseHash(); const g = pickGroup(p.arg);
    if (!HOME.root || HOME.rev !== S.groupsRev) buildHomeRoot();
    if (view.firstElementChild !== HOME.root) { view.replaceChildren(HOME.root); HOME.list.scrollTop = HOME.listScroll; }
    updateHomeList(g);
    if (!force && busyScrolling() && HOME.mainKey === g.key) return;
    const oldD = HOME.main.querySelector('#dscroll'); if (oldD) dscrollLeft[oldD.dataset.key] = oldD.scrollLeft;
    const nv = mainHome(g);
    const same = nv.outerHTML === HOME.main.firstElementChild?.outerHTML;
    if (!same) {
      const x = window.scrollY;
      HOME.main.replaceChildren(nv);
      const nd = $('#dscroll');
      if (nd) {
        const sk = nd.dataset.key;
        if (dscrollLeft[sk] != null && dscrollInit.has(sk)) nd.scrollLeft = dscrollLeft[sk];
        else { dscrollInit.add(sk); const first = nd.querySelector('.tr'); nd.scrollLeft = first ? Math.max(0, first.offsetLeft - nd.clientWidth / 2) : 0; }
      }
      window.scrollTo({ top: x, left: 0, behavior: 'instant' });
    }
    HOME.mainKey = g.key;
  }
  // ---------- 路線一覧(一度だけ作り、以後は文字と色だけ書き換える。並びは名前順で固定) ----------
  const LINES = { root: null, rev: -1, q: '', filter: 'all', items: new Map(), note: null, pills: null };
  function buildLines() {
    LINES.items = new Map();
    const input = h('input', { class: 'search', type: 'search', placeholder: '路線名で検索', value: LINES.q, 'aria-label': '路線を検索', oninput: e => { LINES.q = e.target.value; applyLines(); } });
    LINES.pills = h('div', { class: 'pills' }, [['all', 'すべて'], ['running', '運行中'], ['trouble', '乱れあり']].map(([k, l]) =>
      h('button', { class: 'pill', type: 'button', 'data-k': k, onclick: () => { LINES.filter = k; applyLines(); } }, l)));
    LINES.note = h('div', { class: 'nodata' });
    const grid = h('div', { class: 'grid' });
    for (const g of S.groups) {
      const meta = h('small'), badge = h('span');
      const el = h('button', { class: 'lcard', type: 'button', style: `--c:${g.color}`, onclick: () => go('home', g.rep.id) },
        lsym(g, 'md'), h('span', { class: 'tx' }, h('b', null, g.name), meta), badge);
      LINES.items.set(g.key, { el, meta, badge }); grid.append(el);
    }
    LINES.root = h('div', null, input, LINES.pills, LINES.note, grid); LINES.rev = S.groupsRev;
  }
  function applyLines() {
    for (const b of LINES.pills.children) b.setAttribute('aria-pressed', String(b.dataset.k === LINES.filter));
    let shown = 0;
    for (const g of S.groups) {
      const it = LINES.items.get(g.key); if (!it) continue; const a = groupAgg(g);
      const m = `${g.members.length > 1 ? g.members.length + '系統 ・ ' : ''}${metaOf(a)}`; if (it.meta.textContent !== m) it.meta.textContent = m;
      setChip(it.badge, a.status);
      const vis = matchGroup(g, LINES.q) && (LINES.filter === 'running' ? a.trainCount > 0 : LINES.filter === 'trouble' ? a.status !== 'normal' : true);
      it.el.hidden = !vis; if (vis) shown++;
    }
    LINES.note.textContent = shown ? `${shown}路線 ・ 同じ路線名・同じナンバリングの系統は自動でまとめています` : '該当する路線がありません';
  }
  function renderLines(view) {
    if (!LINES.root || LINES.rev !== S.groupsRev) buildLines();
    if (view.firstElementChild !== LINES.root) view.replaceChildren(LINES.root);
    applyLines();
  }

  // ---------- 車両 ----------
  let fleetQ = '';
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
    for (const d of S.depots) {
      const body = [];
      for (const sd of d.sidings || []) {
        const fs = (sd.formations || []).filter(f => match(f.id, f.series, jp(d.name), jp(sd.name), sd.typeName));
        const un = (sd.unassigned || []).filter(u => match('未割当', u.label, u.series, jp(d.name), jp(sd.name), jp(u.routeName)));
        if (!fs.length && !un.length && q) continue;
        const cards = [];
        for (const f of fs) {
          const live = f.state === 'service' && f.trainId; const k = f.status || 'normal';
          cards.push(h('button', { class: `fm ${live ? 'live' : 'dep'}`, type: 'button', style: `--sc:var(--${colOf(k)})`, onclick: () => live && go('fleet', '', f.trainId) },
            h('b', null, f.title || f.id), `${f.series || sd.typeName || '形式不明'} ${f.cars ? f.cars + '両' : ''}`, h('br'),
            live ? h('span', { class: `s-${k}` }, `${st(k).mark} ${jp(f.routeName) || '運行中'}`) : h('span', { class: 'nodata' }, '車庫')));
        }
        for (const u of un) {
          cards.push(h('button', { class: 'fm live un', type: 'button', style: `--sc:var(--${colOf(u.status)})`, onclick: () => go('fleet', '', u.trainId) },
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
      box.prepend(h('div', { class: 'banner-note' }, 'ゲーム内で /mtrops を開き、車庫線ごとに車番の範囲(例: 1001〜1100)を入れると、出庫した列車へ順に番号が付きます。'));
    }
  }

  // ---------- スタック調査(ゴースト疑い) ----------
  function copyText(text, btn) {
    const done = () => { const o = btn.textContent; btn.textContent = 'コピーしました'; setTimeout(() => { btn.textContent = o; }, 1500); };
    try { navigator.clipboard.writeText(text).then(done, () => { btn.textContent = text; }); } catch (e) { btn.textContent = text; }
  }
  function ghostCard(g) {
    const r = route(g.routeId);
    const tp = `/tp @s ${Math.round(g.x)} ${Math.round(g.y) + 2} ${Math.round(g.z)}`;
    const btnTp = h('button', { class: 'gbtn', type: 'button', onclick: () => copyText(tp, btnTp) }, '移動コマンドをコピー');
    const btnId = h('button', { class: 'gbtn', type: 'button', onclick: () => copyText(String(g.trainId), btnId) }, '列車IDをコピー');
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
            h('li', null, 'ゴースト疑いは5秒以上続いたものを「確定」として扱います。')))),
      h('h2', { class: 'sec' }, `疑いのある車両 ${g.suspects.length}`),
      g.suspects.length ? g.suspects.map(ghostCard) : h('div', { class: 'empty' }, '現在、ゴースト疑いの車両はありません'),
      g.past.length ? [h('h2', { class: 'sec' }, '過去の記録(解消済み)'), g.past.slice(0, 30).map(i => h('div', { class: 'gpast' }, h('b', null, i.typeLabel), ' ', i.message))] : null);
  }

  // ---------- 設定 ----------
  function viewSettings() {
    const root = document.documentElement; const cur = root.dataset.theme || 'auto';
    const setTheme = v => { if (v === 'auto') delete root.dataset.theme; else root.dataset.theme = v; try { v === 'auto' ? localStorage.removeItem('mtrops-theme') : localStorage.setItem('mtrops-theme', v); } catch (e) { /* */ } render(true); };
    const L = S.live || {};
    return h('div', { class: 'set' },
      h('section', null, h('h3', null, '表示テーマ'),
        h('div', { class: 'seg' }, [['auto', '自動'], ['light', 'ライト'], ['dark', 'ダーク']].map(([v, l]) => h('button', { type: 'button', 'aria-pressed': String(cur === v), onclick: () => setTheme(v) }, l)))),
      h('section', null, h('h3', null, '接続'),
        h('dl', { class: 'dl' },
          h('dt', null, 'MOD'), h('dd', null, L.mod || '未接続'),
          h('dt', null, '状態'), h('dd', null, S.ok ? (L.connected ? '受信中' : 'サーバー待ち') : '接続できません'),
          h('dt', null, '列車数'), h('dd', null, L.trainCount != null ? `${L.trainCount}本(運行中 ${L.inService}本)` : '-'),
          h('dt', null, '詳細レベル'), h('dd', null, L.detailLevel != null ? `L${L.detailLevel}` : '-'),
          h('dt', null, '線形データ'), h('dd', null, L.geometrySections != null ? `${L.geometrySections}区間を取得済み(走行した列車から自動で集めます)` : '-'))),
      h('section', null, h('h3', null, 'ローカルAPI(外部プロジェクト向け)'),
        h('ul', null,
          h('li', null, h('code', null, '/api/geometry'), ' 駅間ごとの距離と線形(折れ線)'),
          h('li', null, h('code', null, '/api/sections'), ' 駅間距離のみ'),
          h('li', null, h('code', null, '/api/routes/{id}'), ' 路線の駅順・駅間距離・累積距離・線形'),
          h('li', null, h('code', null, '/api/distance?from=&to=[&route=]'), ' 2駅間の線路沿い距離'),
          h('li', null, 'そのほか ', h('code', null, '/api/live'), ' ', h('code', null, '/api/trains'), ' ', h('code', null, '/api/stations'), ' など'))));
  }

  // ---------- モーダル ----------
  function modalFrame(title, body, color, wide) {
    const close = () => { const p = parseHash(); go(p.view, p.arg); };
    return h('div', { class: 'mbox' + (wide ? ' wide' : ''), style: `--c:${color || 'var(--navy)'}`, role: 'dialog', 'aria-label': title, onclick: e => e.stopPropagation() },
      h('div', { class: 'mh' }, title, h('button', { class: 'x', type: 'button', onclick: close, 'aria-label': '閉じる' }, '×')), body);
  }
  function trainModal(t) {
    const r = route(t.routeId) || { color: '#888', name: '', stationIds: [] };
    const color = r.color;
    let ids = (r.stationIds || []).map(String); let tp = trainPos(t, ids);
    if (tp && tp.dir < 0) { ids = ids.slice().reverse(); tp = trainPos(t, ids); }
    const letters = routeLetters(r);
    const lateTxt = (t.delaySec || 0) < 30 ? 'なし' : fmtDelay(t.delaySec);
    const dc = colOf(t.status);
    const nCars = t.cars || 0;
    const etaOf = new Map((t.etas || []).map(e => [String(e.stationId), e]));
    // 到着予想は現実の時刻(この画面を開いている端末の時計)。残り秒数は最後に受信した時点からの値。
    const etaClock = sec => { const d = new Date((S.lastOk || Date.now()) + sec * 1000); return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`; };
    const etaIn = sec => { const rest = Math.max(0, Math.round(sec - (Date.now() - (S.lastOk || Date.now())) / 1000)); return rest < 60 ? `あと${rest}秒` : `あと${Math.floor(rest / 60)}分${pad2(rest % 60)}秒`; };
    const carLabel = i => { const raw = (t.carNumbers || [])[i] || ''; const m = raw.match(/(\d+)\s*$/); return m ? m[1] : raw; };
    const carsEl = cls => h('div', { class: 'cars ' + (cls || ''), style: `--c:${color}` },
      h('span', { class: 'nose' }),
      nCars ? Array.from({ length: nCars }, (_, i) => h('span', { class: 'car', title: (t.carNumbers || [])[i] || '' }, h('b', null, i + 1), h('small', null, carLabel(i)))) : h('span', { class: 'car' }, h('b', null, '?')));
    const tl = h('div', { class: 'tlist', style: `--c:${color}` });
    const row = (i, cls, tag, tagCls, withCars) => {
      const s0 = station(ids[i]) || { name: ids[i] }; const eta = etaOf.get(ids[i]);
      return h('div', { class: 'trow ' + (cls || '') + (withCars ? ' hascars' : '') }, h('div', { class: 'nd' }, codeEls(stationCode(letters, ids[i]))),
        h('div', { class: 'nm' }, jp(s0.name), en(s0.name) ? h('small', null, en(s0.name)) : null),
        withCars ? carsEl('inrow') : null,
        eta ? h('span', { class: 'tm' + ((t.delaySec || 0) >= 60 ? ' late' : '') }, etaClock(eta.sec), h('small', null, etaIn(eta.sec))) : null,
        tag ? h('span', { class: 'tag ' + (tagCls || '') }, tag) : null);
    };
    if (tp) {
      const stopped = t.atStop && Number.isInteger(tp.pos);
      const a = Math.floor(tp.pos + 1e-6);                              // 列車の手前にある駅(停車中はその駅)
      const firstAhead = a + 1;
      const passedTo = stopped ? a - 1 : a;                             // 通過済みとして薄く表示する最後の駅
      for (let i = Math.max(0, passedTo - 1); i <= passedTo; i++) tl.append(row(i, 'past'));
      if (stopped) tl.append(row(a, 'here', '停車中', 'stop', true));
      else {
        const prevS = station(ids[a]), nextS = station(ids[firstAhead]);
        tl.append(h('div', { class: 'tblock', style: `--c:${color}` }, carsEl(''),
          h('div', { class: 'tstate' }, h('span', null, `走行中 ${Math.round(t.speedKmh || 0)} km/h`), h('span', { class: 'nodata' }, `${prevS ? jp(prevS.name) : ''} → ${nextS ? jp(nextS.name) : ''}`))));
      }
      const frac = tp.pos - Math.floor(tp.pos);
      for (let i = firstAhead; i < ids.length; i++) tl.append(i === firstAhead ? row(i, '', !stopped && frac >= 0.7 ? 'まもなく' : '次の駅', '') : row(i));
      if (stopped && t.dwellRemainSec != null) tl.append(h('div', { class: 'nodata', style: 'padding:2px 0 0 60px' }, `発車まであと${t.dwellRemainSec}秒`));
      if (firstAhead >= ids.length) tl.append(h('div', { class: 'nodata', style: 'padding:6px 0 0 60px' }, 'この先に停車駅はありません'));
    }
    const dl = [['編成番号', t.title || t.formation || '番号未設定'], ['車両', t.typeName || t.series], t.typeName && t.series && t.series !== t.typeName ? ['系列', t.series] : null,
      ['両数', t.cars ? `${t.cars}両` : null], ['速度', t.speedKmh != null ? `${Math.round(t.speedKmh)} km/h` : null], ['乗客', t.passengers != null ? `${t.passengers}人` : null],
      ['車庫', t.depotName && `${jp(t.depotName)} ${t.sidingName || ''}`], ['路線', jp(r.name)]].filter(x => x && x[1] != null && x[1] !== '');
    const first = station((r.stationIds || [])[0]);
    return modalFrame('列車詳細情報', [
      h('div', { class: 'hero' },
        first ? h('div', { class: 'orig' }, `${jp(first.name)} 始発`) : null,
        h('div', { class: 'dest' }, h('b', null, jp(t.destination) || '回送'), ' 行き'),
        t.manual ? h('div', { class: 'meta' }, '手動運転中(遅れ判定の対象外)') : null),
      h('div', { class: 'drow' }, chip(t.status), h('div', { class: `late s-${dc === 'ok' ? 'normal' : t.status}` }, `遅れ：${lateTxt}`)),
      h('div', { class: 'msec' }, tp ? tl : h('div', { class: 'nodata' }, 'この列車の位置を路線上で特定できません(詳細レベルが低いか、路線外です)。'),
        tp && etaOf.size ? h('div', { class: 'nodata', style: 'margin-top:8px' }, '到着予想は、ダイヤ上の残り時間から計算した現実の時刻です。') : null),
      h('div', { class: 'mdl' }, h('dl', { class: 'dl' }, dl.map(([k, v]) => [h('dt', null, k), h('dd', null, v)]))),
      h('div', { class: 'msec nodata' }, '容量の都合で省略された項目は表示されません。')], color, true);
  }
  function stationsModal(g) {
    const chain = chainOf(g); const lt = g.letter ? [g.letter] : g.letters;
    const list = h('div', { class: 'tlist', style: `--c:${g.color}` });
    chain.ids.forEach(id => {
      const s0 = station(id) || { name: id };
      const others = new Map();
      for (const rid of s0.routeIds || []) { const og = S.groupByRoute.get(String(rid)); if (og && og !== g) others.set(og.key, og); }
      list.append(h('div', { class: 'trow' }, h('div', { class: 'nd' }, codeEls(stationCode(lt, id))),
        h('div', { class: 'nm' }, jp(s0.name), en(s0.name) ? h('small', null, en(s0.name)) : null,
          others.size ? h('div', { class: 'xfl' }, [...others.values()].map(o => h('button', { class: 'xfc', type: 'button', style: `--c:${o.color}`, onclick: () => go('home', o.rep.id, '', id) }, h('i'), o.name))) : null)));
    });
    return modalFrame(`${g.name} の駅一覧・乗換`, h('div', { class: 'msec' }, chain.ids.length ? list : h('div', { class: 'empty' }, '駅の並びが取得できていません')), g.color, true);
  }
  function distModal(g) {
    const sel = g.members.find(m => String(m.id) === S.distSel) || g.rep; S.distSel = String(sel.id);
    const cached = S.distCache.get(S.distSel);
    if (!cached || Date.now() - cached.t > 15000) fetchRouteDetail(S.distSel);
    const d = cached && cached.data;
    const pick = g.members.length > 1 ? h('div', { class: 'sel-route' }, h('span', { class: 'nodata' }, '系統'),
      h('select', { 'aria-label': '系統', onchange: e => { S.distSel = e.target.value; render(true); } },
        g.members.map(m => h('option', Object.assign({ value: String(m.id) }, String(m.id) === S.distSel ? { selected: true } : {}), `${jp(m.name)}(${(m.stationIds || []).length}駅)`)))) : null;
    let body;
    if (!d) body = h('div', { class: 'empty' }, '読み込み中…');
    else if (!d.stations || d.stations.length < 2) body = h('div', { class: 'empty' }, 'この系統の駅の並びが取得できていません');
    else {
      const rows = [];
      d.stations.forEach((s0, i) => {
        rows.push(h('tr', null, h('td', null, jp(s0.name)), h('td', null, ''), h('td', null, fmtM(s0.cumulativeM))));
        const sg = d.segments[i];
        if (sg) rows.push(h('tr', { class: 'segrow' }, h('td', null, '↓ 次の駅まで'), h('td', null, fmtM(sg.distanceM), sg.measured ? null : h('span', { class: 'est', title: '線路の実測がまだ無いため、駅の座標の直線距離です' }, '直線')), h('td', null, '')));
      });
      body = [h('div', { class: 'msec' },
        h('div', { class: 'nodata', style: 'margin-bottom:8px' }, `全長 ${fmtM(d.totalM)}${d.allMeasured ? '(線路沿いの実測)' : '(一部は直線距離で補っています。列車が走るほど実測が増えます)'}`),
        h('table', { class: 'dtbl' }, h('thead', null, h('tr', null, h('th', null, '駅'), h('th', null, '区間'), h('th', null, '累積'))), h('tbody', null, rows)))];
    }
    return modalFrame(`${g.name} の駅間距離`, [pick, body], g.color, true);
  }
  const fetching = new Set();
  async function fetchRouteDetail(id) {
    if (fetching.has(id)) return; fetching.add(id);
    try { const data = await get(`/api/routes/${id}?points=0`); S.distCache.set(id, { t: Date.now(), data }); } catch (e) { S.distCache.set(id, { t: Date.now(), data: null }); }
    fetching.delete(id); render(true);
  }
  function drawModal() {
    const el = $('#modal'); const p = parseHash();
    let box = null;
    if (p.train) {
      const t = S.trains.get(p.train);
      box = t ? trainModal(t) : modalFrame('列車が見つかりません', h('div', { class: 'msec nodata' }, 'この列車は運行を終えたか、データから消えています。'));
    } else if (p.m && S.groups.length) {
      const g = pickGroup(p.arg);
      if (g) box = p.m === 'stations' ? stationsModal(g) : p.m === 'dist' ? distModal(g) : null;
    }
    if (!box) { if (!el.hidden) { el.hidden = true; el.replaceChildren(); document.body.style.overflow = ''; } return; }
    const keep = el.firstElementChild ? el.firstElementChild.scrollTop : 0;
    el.hidden = false; el.replaceChildren(box); box.scrollTop = keep; document.body.style.overflow = 'hidden';
    el.onclick = () => { const q = parseHash(); go(q.view, q.arg); };
  }
  window.addEventListener('keydown', e => { if (e.key === 'Escape' && !$('#modal').hidden) { const q = parseHash(); go(q.view, q.arg); } });

  // ---------- render ----------
  let pressing = false, renderPending = false;
  const press = () => { pressing = true; };
  const release = () => { setTimeout(() => { pressing = false; if (renderPending) { renderPending = false; render(); } }, 80); };
  window.addEventListener('pointerdown', press, { passive: true, capture: true });
  ['pointerup', 'pointercancel', 'touchend', 'touchcancel', 'dragend'].forEach(ev => window.addEventListener(ev, release, { passive: true, capture: true }));
  window.addEventListener('blur', () => { pressing = false; });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { poll(); loadFleet(); } });
  let lastInput = 0;
  ['scroll', 'wheel', 'touchstart', 'touchmove', 'touchend', 'pointerdown'].forEach(ev => window.addEventListener(ev, () => { lastInput = Date.now(); }, { passive: true, capture: true }));
  let touching = false;
  window.addEventListener('touchstart', () => { touching = true; }, { passive: true, capture: true });
  ['touchend', 'touchcancel'].forEach(ev => window.addEventListener(ev, () => { touching = false; }, { passive: true, capture: true }));
  const busyScrolling = () => touching || Date.now() - lastInput < 1200;
  function render(force) {
    if (!force && pressing) { renderPending = true; return; }
    try { renderNow(force); } catch (e) {
      console.error(e);
      const b = $('#banner'); b.hidden = false; b.className = 'banner err'; b.textContent = '表示中にエラーが起きました。自動で再試行します。(' + (e && e.message || e) + ')';
    }
  }
  let renderedKey = '', scrolledFor = '';
  function renderNow(force) {
    const p = parseHash(); const view = $('#view');
    drawTabs(p.view);
    const b = $('#banner');
    if (!S.ok) { b.hidden = false; b.className = 'banner err'; b.textContent = S.lastOk ? '接続が途切れました。再接続しています…' : 'MODに接続できません。Minecraftを起動し、ワールドに入ってください。'; }
    else if (S.live && (!S.live.connected || S.live.stale)) { b.hidden = false; b.className = 'banner'; b.textContent = 'サーバーからの更新が止まっています。表示は最新ではありません。'; }
    else b.hidden = true;
    if (S.live) $('#clock').textContent = S.live.clock || '--:--';
    if (p.view === 'home') renderHome(view, force);
    else if (p.view === 'lines') renderLines(view);
    else {
      const builders = { fleet: viewFleet, ghost: viewGhost, settings: viewSettings };
      const key = p.view;
      const typing = key === renderedKey && document.activeElement && document.activeElement.classList.contains('search');
      if (typing && p.view === 'fleet') fillFleet($('#fleet-list'));
      else if (!force && busyScrolling() && key === renderedKey) { /* 操作中は作り直さない */ }
      else {
        const x = window.scrollY;
        const nv = (builders[p.view] || viewFleet)();
        if (!(nv.outerHTML === view.firstElementChild?.outerHTML && view.childElementCount === 1)) { view.replaceChildren(nv); window.scrollTo({ top: x, left: 0, behavior: 'instant' }); }
      }
    }
    renderedKey = p.view;
    drawModal();
    if (p.view === 'home' && p.sid && scrolledFor !== location.hash) {
      const el = document.querySelector(`[data-sid="${CSS.escape(p.sid)}"]`), sc = $('#dscroll');
      if (el && sc) { scrolledFor = location.hash; sc.scrollLeft = Math.max(0, el.offsetLeft - sc.clientWidth / 2); }
    }
  }
  let lastView = parseHash().view;
  window.addEventListener('hashchange', () => {
    const v = parseHash().view; if (v !== lastView) { lastView = v; window.scrollTo(0, 0); }
    render(true);
  });
  // テーマ
  const root = document.documentElement;
  try { const t = localStorage.getItem('mtrops-theme'); if (t) root.dataset.theme = t; } catch (e) { /* 無効でも動作 */ }
  $('#theme').addEventListener('click', () => {
    const dark = root.dataset.theme ? root.dataset.theme === 'dark' : matchMedia('(prefers-color-scheme:dark)').matches;
    root.dataset.theme = dark ? 'light' : 'dark'; try { localStorage.setItem('mtrops-theme', root.dataset.theme); } catch (e) { /* */ }
  });
  render(true); poll(); loadFleet();
  setInterval(() => { if (!document.hidden) poll(); }, 1000); setInterval(() => { if (!document.hidden) loadFleet(); }, 5000);
})();
