/* Everything that is DOM. The scene never touches an element and this file
 * never touches three.js; they meet in main.js. */

const $ = sel => document.querySelector(sel);
const el = (tag, cls, txt) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (txt != null) n.textContent = txt;
  return n;
};
const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g,
  c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const kfmt = n => n >= 1e6 ? (n / 1e6).toFixed(1) + 'M'
  : n >= 1e3 ? (n / 1e3).toFixed(n >= 1e4 ? 0 : 1) + 'k' : String(n);
const ago = t => {
  const d = Date.now() / 1000 - t;
  if (d < 60) return Math.round(d) + 's';
  if (d < 3600) return Math.round(d / 60) + 'm';
  if (d < 86400) return Math.round(d / 3600) + 'h';
  return Math.round(d / 86400) + 'd';
};

export class Hud {
  constructor(graph, api) {
    this.g = graph;
    this.api = api;                 // { focusProject, focusNode, setFlagged, ... }
    this.sort = 'size';
    this.projSel = null;
    this.feedCount = 0;
    this.agents = new Map();
    this.wire();
    this.stats();
    this.projects();
    this.staleness();
    setInterval(() => this.staleness(), 120000);
  }

  /* ------------------------------------------------------------- wiring */
  wire() {
    // One page serves every copy of the map. A copy built without a classifier
    // has nothing for these two controls to do, so it does not show them; a
    // copy with one names its layer in instance.json (`flag_label`).
    const meta = this.g.meta || {};
    this.label = meta.flag_label || 'flagged';
    document.documentElement.style.setProperty('--flag-label', JSON.stringify(this.label));
    $('#btn-flagged').hidden = $('#btn-split').hidden = !meta.flag_layer;
    if (meta.name) { document.title = 'UMAP — ' + meta.name; $('#brandsub').textContent = meta.name; }
    $('#blend').addEventListener('input', e => this.api.setBlend(e.target.value / 100));
    $('#btn-replay').addEventListener('click', () => this.api.replay());
    $('#btn-flagged').addEventListener('click', () => this.api.cycleFlagged());
    $('#btn-agent').addEventListener('click', e => {
      const on = e.target.classList.toggle('on');
      $('#panel-agents').style.display = on ? '' : 'none';
    });
    $('#btn-demo').addEventListener('click', () => this.api.demo());
    $('#btn-split').addEventListener('click', () => this.showSplit());
    $('#btn-kin').addEventListener('click', () => this.api.cycleKin());
    $('#link-hook').addEventListener('click', e => { e.preventDefault(); this.showHookHelp(); });
    document.querySelectorAll('[data-close]').forEach(b => b.addEventListener('click', e => {
      const what = e.target.dataset.close;
      if (what === 'modal') $('#modal').hidden = true;
      else $('#panel-detail').hidden = true;
    }));
    $('#modal').addEventListener('click', e => { if (e.target.id === 'modal') $('#modal').hidden = true; });

    document.querySelectorAll('.filters button').forEach(b => b.addEventListener('click', () => {
      document.querySelectorAll('.filters button').forEach(x => x.classList.remove('on'));
      b.classList.add('on');
      this.sort = b.dataset.sort;
      this.projects();
    }));

    const search = $('#search');
    search.addEventListener('input', () => this.search(search.value));
    search.addEventListener('keydown', e => {
      if (e.key === 'Escape') { search.value = ''; this.search(''); search.blur(); }
      if (e.key === 'Enter') {
        const first = $('#searchhits div');
        if (first) first.click();
      }
    });
    addEventListener('keydown', e => {
      if (e.target.tagName === 'INPUT') return;
      if (e.key === '/') { e.preventDefault(); search.focus(); }
      if (e.key === 'r') this.api.replay();
      if (e.key === 'n') this.api.cycleFlagged();
      if (e.key === 'Escape') { $('#panel-detail').hidden = true; this.api.clearFilter(); }
    });
  }

  /* -------------------------------------------------------------- stats */
  stats() {
    const g = this.g;
    const loc = g.projects.reduce((a, p) => a + (p.loc || 0), 0);
    $('#stat-nodes').textContent = kfmt(g.nodes.length);
    $('#stat-projects').textContent = g.projects.length;
    $('#stat-loc').textContent = kfmt(loc);
    this.updateHidden();
    $('#brandsub').textContent = `${g.meta.name || 'UMAP'} · ${g.meta.nodes} nodes`;
  }

  updateHidden() {
    const mode = this.api.flaggedMode();
    const n = this.g.nodes.filter(x => x.flagged).length;
    $('#stat-hidden').textContent = mode === 0 ? n : 0;
    const b = $('#btn-flagged');
    b.className = 'flagged ' + ['off', 'dim', 'on'][mode];
    b.textContent = this.label + ' ' + ['hidden', 'dimmed', 'shown'][mode];
    this.projects();
  }

  /* ----------------------------------------------------------- projects */
  projects() {
    const list = $('#projlist');
    const mode = this.api.flaggedMode();
    let ps = this.g.projects.map((p, i) => ({ p, i }));
    if (mode === 0) ps = ps.filter(x => x.p.flagged < 2);
    const cmp = {
      size: (a, b) => b.p.loc - a.p.loc,
      recent: (a, b) => b.p.mtime - a.p.mtime,
      name: (a, b) => a.p.title.localeCompare(b.p.title),
      status: (a, b) => (a.p.status + a.p.title).localeCompare(b.p.status + b.p.title),
    }[this.sort];
    ps.sort(cmp);
    list.innerHTML = '';
    for (const { p, i } of ps) {
      const li = el('li');
      li.dataset.i = i;
      if (p.status === 'active') li.classList.add('hot');
      if (p.flagged) li.classList.add('flaggedrow');
      if (p.flagged && mode === 1) li.classList.add('muted');
      const sw = el('span', 'swatch');
      sw.style.background = p.color;
      li.append(sw, el('span', 'st st-' + p.status), el('span', 'nm', p.title));
      li.append(el('span', 'ct', this.sort === 'recent' ? ago(p.mtime) : kfmt(p.loc)));
      li.title = `${p.id} · ${p.status} · ${p.tech || p.stack}`;
      li.addEventListener('click', () => this.api.focusProject(i));
      li.addEventListener('mouseenter', () => this.api.highlightProject(i));
      li.addEventListener('mouseleave', () => this.api.highlightProject(null));
      list.append(li);
    }
    $('#proj-count').textContent = ps.length;
  }

  markProject(i) {
    document.querySelectorAll('#projlist li').forEach(li =>
      li.classList.toggle('sel', +li.dataset.i === i));
  }

  /* ------------------------------------------------------------- search */
  search(q) {
    const box = $('#searchhits');
    q = q.trim().toLowerCase();
    if (!q) { box.classList.remove('open'); box.innerHTML = ''; this.api.clearFilter(); return; }
    const mode = this.api.flaggedMode();
    const hits = [];
    for (let i = 0; i < this.g.projects.length && hits.length < 60; i++) {
      const p = this.g.projects[i];
      if (mode === 0 && p.flagged === 2) continue;
      const hay = (p.title + ' ' + p.id + ' ' + (p.tags || []).join(' ') + ' ' +
                   (p.terms || []).join(' ') + ' ' + p.stack + ' ' + p.status).toLowerCase();
      if (hay.includes(q)) hits.push({ kind: 'project', i, label: p.title, sub: p.tech || p.stack });
    }
    const mask = new Uint8Array(this.g.nodes.length);
    let matched = 0;
    for (let i = 0; i < this.g.nodes.length; i++) {
      const nd = this.g.nodes[i];
      if (mode === 0 && nd.flagged) continue;
      const p = this.g.projects[nd.p];
      const inPath = nd.path.toLowerCase().includes(q);
      const inSym = nd.sym && nd.sym.some(s => s.toLowerCase().includes(q));
      const inHead = nd.head && nd.head.toLowerCase().includes(q);
      if (inPath || inSym || inHead ||
          (p && (p.title.toLowerCase().includes(q) || (p.tags || []).some(t => t.includes(q))))) {
        mask[i] = 1; matched++;
        if (hits.length < 60 && (inPath || inSym)) {
          hits.push({ kind: 'node', i, label: nd.name,
                      sub: inSym && !inPath
                        ? nd.sym.filter(s => s.toLowerCase().includes(q)).slice(0, 3).join(', ')
                          + '  —  ' + nd.path
                        : nd.path });
        }
      }
    }
    box.innerHTML = '';
    for (const h of hits.slice(0, 40)) {
      const d = el('div');
      if (h.kind === 'node') d.dataset.node = h.i;
      d.innerHTML = `${esc(h.label)}<small>${esc(h.sub)}</small>`;
      d.addEventListener('click', () => {
        box.classList.remove('open');
        if (h.kind === 'project') this.api.focusProject(h.i); else this.api.focusNode(h.i);
      });
      box.append(d);
    }
    box.classList.toggle('open', hits.length > 0);
    this.api.setFilter(matched ? mask : null, matched);

    // Names and symbols are what the browser holds; a function is USED in more
    // files than it is defined in, so the server greps the actual contents and
    // merges what it finds. Debounced, and dropped if the query moved on.
    this._contentSearch(q, mask, box);
  }

  _contentSearch(q, mask, box) {
    clearTimeout(this._searchTimer);
    this._searchQuery = q;
    if (q.length < 3) return;
    this._searchTimer = setTimeout(async () => {
      let r;
      try {
        r = await fetch('/api/search?limit=300&q=' + encodeURIComponent(q)).then(x => x.json());
      } catch (err) {
        return;                       // server not running: names-only is fine
      }
      if (!r.ok || this._searchQuery !== q) return;
      const mode = this.api.flaggedMode();
      const already = new Set([...box.querySelectorAll('[data-node]')].map(d => +d.dataset.node));
      let added = 0, extra = 0;
      for (const h of r.hits) {
        if (mode === 0 && h.flagged) continue;
        if (!mask[h.node]) { mask[h.node] = 1; extra++; }
        if (already.has(h.node) || added >= 40) continue;
        const d = el('div');
        d.dataset.node = h.node;
        d.innerHTML = `${esc(this.g.nodes[h.node].name)}` +
          `<small>${h.count}&times; &middot; ${esc(h.line.slice(0, 60))}</small>`;
        d.title = h.path;
        d.addEventListener('click', () => {
          box.classList.remove('open');
          this.api.focusNode(h.node);
        });
        box.append(d);
        added++;
      }
      if (extra) this.api.setFilter(mask);
      if (added) {
        box.classList.add('open');
        const note = el('div', 'searchnote',
          `${r.count} file${r.count === 1 ? '' : 's'} contain "${q}"` +
          (r.capped ? ` (showing ${r.shown})` : ''));
        box.prepend(note);
      }
    }, 220);
  }

  /* ------------------------------------------------------------- detail */
  showNode(i, neighbors) {
    const nd = this.g.nodes[i], p = this.g.projects[nd.p];
    const d = $('#detail');
    d.innerHTML = '';
    d.append(el('h2', null, nd.name));
    const sub = el('div', 'sub');
    sub.innerHTML = `<span style="color:${p.color}">${esc(p.title)}</span> · ${esc(nd.path)}`;
    d.append(sub);

    if (nd.flagged) {
      const w = el('div', 'warnbox');
      w.innerHTML = `<b>${esc(this.label)}</b> — ${esc(nd.why || 'flagged')}`;
      d.append(w);
    }

    // What the file SAYS it is comes before the bookkeeping about it.
    if (nd.head) d.append(el('p', 'fact', nd.head));

    if (nd.sym?.length) {
      const t = el('div', 'tags');
      t.append(el('h3', null, `defines ${nd.sym.length}${nd.sym.length >= 18 ? '+' : ''}`));
      for (const s of nd.sym) {
        const c = el('span', 'tag sym', s);
        c.addEventListener('click', () => { $('#search').value = s; this.search(s); });
        t.append(c);
      }
      d.append(t);
    }

    const kv = [
      ['kind', nd.k === 'asset' ? `asset dir (${nd.why || nd.loc + ' files'})` : nd.lang],
      [nd.k === 'asset' ? 'files' : 'lines', kfmt(nd.loc)],
      ['size', kfmt(nd.size) + 'B'],
      nd.mtime ? ['touched', ago(nd.mtime) + ' ago'] : null,
      nd.vend ? ['note', 'vendored / third-party'] : null,
    ].filter(Boolean);
    for (const [k, v] of kv) {
      const r = el('div', 'kv');
      r.innerHTML = `<span>${esc(k)}</span><b>${esc(v)}</b>`;
      d.append(r);
    }

    if (nd.k === 'file') {
      const peek = el('button', null, 'read the file');
      peek.addEventListener('click', () => this.showFile(nd.path));
      d.append(peek);
    }

    if (neighbors && neighbors.length) {
      const near = el('div', 'near');
      near.append(el('h3', null, 'nearest in meaning'));
      for (const nb of neighbors) {
        const row = el('div');
        row.innerHTML = `<span class="sim">${(nb.sim * 100).toFixed(0)}%</span>` +
                        `<span class="pth">${esc(this.g.nodes[nb.i].path)}</span>`;
        row.addEventListener('click', () => this.api.focusNode(nb.i));
        near.append(row);
      }
      d.append(near);
    }
    const open = el('button', null, 'show its project');
    open.addEventListener('click', () => this.api.focusProject(nd.p));
    d.append(open);
    $('#panel-detail').hidden = false;
  }

  showProject(pi) {
    const p = this.g.projects[pi];
    const d = $('#detail');
    d.innerHTML = '';
    d.append(el('h2', null, p.title));
    const sub = el('div', 'sub');
    sub.innerHTML = `<span style="color:${p.color}">${esc(p.stack)}</span> · ${esc(p.status)}` +
      (p.unmapped ? ' · <span style="color:#ffd23f">not in CLAUDE.md</span>' : '') +
      `<br>${esc(p.tech || '')}`;
    d.append(sub);

    if (p.flagged) {
      const w = el('div', 'warnbox');
      w.innerHTML = `<b>${esc(this.label)} · ${p.flagged === 2 ? 'whole project' : 'partly'}</b> — ` +
        `${esc(p.flagged_why || '')} <br><small>${p.flagged_files} flagged files · ` +
        `verdict from ${esc(p.flagged_src)}</small>`;
      d.append(w);
    }
    if (p.what) d.append(el('p', null, p.what));
    if (p.fact) d.append(el('p', 'fact', p.fact));
    if (p.lesson) d.append(el('p', 'lesson', p.lesson));

    if (p.tags?.length) {
      const t = el('div', 'tags');
      for (const tag of p.tags) {
        const s = el('span', 'tag', tag);
        s.addEventListener('click', () => { $('#search').value = tag; this.search(tag); });
        t.append(s);
      }
      d.append(t);
    }
    for (const [k, v] of [
      ['entry', p.entry], ['files', kfmt(p.files)], ['lines', kfmt(p.loc)],
      ['mapped nodes', p.n], ['touched', ago(p.mtime) + ' ago'],
    ]) {
      if (!v && v !== 0) continue;
      const r = el('div', 'kv');
      r.innerHTML = `<span>${esc(k)}</span><b>${esc(v)}</b>`;
      d.append(r);
    }
    if (p.terms?.length) {
      const r = el('div', 'kv');
      r.innerHTML = `<span>its own words</span><b>${esc(p.terms.slice(0, 6).join(' · '))}</b>`;
      d.append(r);
    }
    const kin = this.g.kin_edges.filter(e => e.a === pi || e.b === pi);
    if (kin.length) {
      const near = el('div', 'near');
      near.append(el('h3', null, 'related work'));
      for (const k of kin.slice(0, 10)) {
        const other = k.a === pi ? k.b : k.a;
        const row = el('div');
        row.innerHTML = `<span class="pth" style="direction:ltr">${esc(this.g.projects[other].title)}` +
                        `<small style="color:#6c7c92"> — ${esc(k.why || '')}</small></span>`;
        row.addEventListener('click', () => this.api.focusProject(other));
        near.append(row);
      }
      d.append(near);
    }
    $('#panel-detail').hidden = false;
    this.markProject(pi);
  }

  /* -------------------------------------------------------------- agents */
  agentEvent(ev, replay) {
    let a = this.agents.get(ev.agent);
    if (!a) {
      a = { id: ev.agent, name: ev.agent_name, color: ev.color, events: 0, follow: false };
      this.agents.set(ev.agent, a);
    }
    a.name = ev.agent_name || a.name;
    a.color = ev.color || a.color;
    a.tool = ev.tool; a.path = ev.rel || ev.path; a.last = ev.t; a.events++;
    if (!replay) this.pushFeed(ev);
    this.renderAgents();
  }

  pushFeed(ev) {
    const feed = $('#feed');
    const row = el('div', 'ev new' + (ev.node == null ? ' off' : ''));
    const time = new Date(ev.t * 1000).toLocaleTimeString(undefined, { hour12: false });
    row.innerHTML = `<span class="t">${esc(time.slice(3))}</span>` +
      `<span class="tool" style="color:${ev.color || '#9ec1e6'}">${esc(ev.tool)}</span>` +
      `<span class="pth">${esc(ev.rel || ev.detail || '—')}</span>`;
    feed.prepend(row);
    setTimeout(() => row.classList.remove('new'), 900);
    while (feed.childElementCount > 140) feed.lastElementChild.remove();
    $('#agenthelp').style.display = 'none';
  }

  renderAgents() {
    const box = $('#agentlist');
    const now = Date.now() / 1000;
    box.innerHTML = '';
    const list = [...this.agents.values()].sort((a, b) => b.last - a.last);
    let live = 0;
    for (const a of list.slice(0, 6)) {
      const idle = now - a.last;
      if (idle < 120) live++;
      const card = el('div', 'agentcard' + (idle > 120 ? ' idle' : '') + (a.follow ? ' follow' : ''));
      card.style.color = a.color;
      card.innerHTML =
        `<div class="top"><span class="chip" style="background:${a.color}"></span>` +
        `<span class="nm">${esc(a.name)}</span>` +
        `<span class="tl">${esc(a.tool || '')} · ${idle < 3 ? 'now' : ago(a.last)}</span></div>` +
        `<div class="pth">${esc(a.path || '')}</div>` +
        `<div class="bar" style="width:${Math.min(100, a.events * 3)}%"></div>`;
      card.title = 'click to follow this agent with the camera';
      card.addEventListener('click', () => {
        for (const o of this.agents.values()) if (o !== a) o.follow = false;
        a.follow = !a.follow;
        this.api.follow(a.follow ? a.id : null);
        this.renderAgents();
      });
      box.append(card);
    }
    $('#agent-count').textContent = list.length;
    $('#agenthelp').style.display = list.length ? 'none' : '';
    $('#livedot').classList.toggle('on', live > 0);
  }

  /** The map is a snapshot of a tree that is being worked in. Say so. */
  async staleness() {
    let r;
    try {
      r = await fetch('/api/staleness').then(x => x.json());
    } catch (err) {
      return;
    }
    const b = $('#stale');
    if (!r.ok || (r.changed + r.added) === 0) { b.hidden = true; return; }
    const n = r.changed + r.added;
    b.hidden = false;
    b.textContent = `${n} file${n === 1 ? '' : 's'} changed since this map was built`;
    b.classList.toggle('warn', n > 250);
    b.onclick = () => {
      $('#modalbody').innerHTML = `
        <h2>This map is a snapshot</h2>
        <p>It was built <b>${ago(r.built)} ago</b>. Since then
        <b>${r.changed}</b> mapped file${r.changed === 1 ? ' has' : 's have'} changed and
        <b>${r.added}</b> new one${r.added === 1 ? ' has' : 's have'} appeared, out of
        ${r.scanned} scanned.</p>
        <p>Run <b>Rebuild.bat</b> to refit it — about 70 seconds.</p>
        <p style="color:#8797ad">There is deliberately no incremental update. umap-learn can
        place a new point against a saved model, but that is an approximation from a frozen
        fit that drifts as the corpus moves, and the map would quietly stop being the thing
        its own measurements describe. Counting what changed is cheap and true; the rebuild
        is honest and short.</p>`;
      $('#modal').hidden = false;
    };
  }

  connection(ok) {
    $('#livedot').classList.toggle('on', ok && this.agents.size > 0);
    $('#livedot').title = ok ? 'connected to the event stream' : 'stream offline — is the server running?';
  }

  /* --------------------------------------------------------------- misc */
  tooltip(nd, at, project) {
    const t = $('#tooltip');
    if (!nd) { t.hidden = true; return; }
    t.hidden = false;
    t.style.left = at.x + 'px';
    t.style.top = at.y + 'px';
    t.innerHTML = `<b>${esc(nd.name)}</b>` +
      `<div class="meta"><span class="pj" style="color:${project.color}">${esc(project.title)}</span>` +
      ` · ${esc(nd.k === 'asset' ? nd.loc + ' files' : nd.lang + ' · ' + kfmt(nd.loc) + ' ln')}` +
      (nd.flagged ? ` · <span style="color:#ff6ec7">${esc(this.label)}</span>` : '') + `</div>` +
      (nd.head ? `<div class="meta" style="color:#b9cbe0">${esc(nd.head.slice(0, 150))}</div>` : '') +
      (nd.sym?.length ? `<div class="meta" style="color:#7f93ab">${esc(nd.sym.slice(0, 5).join(' · '))}</div>` : '');
  }

  async showSplit(project) {
    $('#modal').hidden = false;
    if (project) {
      $('#modalbody').innerHTML = `<h2>${esc(project)}</h2><p>reading…</p>`;
      let b;
      try {
        b = await fetch('/api/folders/' + encodeURIComponent(project)).then(r => r.json());
      } catch (err) { b = null; }
      if (!b) { $('#modalbody').innerHTML = '<p>no split file for that project</p>'; return; }
      const rows = b.flagged.paths.map(r =>
        `<div class="kv"><span class="pth" style="direction:ltr">${esc(r.path)}</span>` +
        `<b style="color:#ff9ad4">${esc(r.level)} — ${esc(r.why)}</b></div>`).join('');
      $('#modalbody').innerHTML =
        `<h2>${esc(b.title)}</h2>` +
        `<p><b style="color:${b.level === 'none' ? '#7dff9e' : '#ff6ec7'}">${esc(b.level)}</b>` +
        ` · verdict from <b>${esc(b.source)}</b></p>` +
        (b.reason ? `<p style="color:#bcd6f2">${esc(b.reason)}</p>` : '') +
        `<p>${b.clean.count} clean · ${b.flagged.count} ${esc(this.label)}</p>` +
        (rows || '<p style="color:#8797ad">nothing in this project is flagged.</p>') +
        `<p><button data-back="1">← all projects</button></p>`;
      $('#modalbody').querySelector('[data-back]')?.addEventListener('click', () => this.showSplit());
      return;
    }
    $('#modalbody').innerHTML = `<h2>${esc(this.label)}, separated</h2><p>reading…</p>`;
    let idx;
    try {
      idx = await fetch('/api/folders').then(r => r.json());
    } catch (err) { idx = []; }
    const flagged = idx.filter(r => r.level !== 'none');
    const clean = idx.filter(r => r.level === 'none');
    const row = r =>
      `<div class="kv" data-p="${esc(r.project)}" style="cursor:pointer">` +
      `<span>${esc(r.title)}</span>` +
      `<b style="color:${r.level === 'full' ? '#ff6ec7' : r.level === 'partial' ? '#c9a6ff' : '#7dff9e'}">` +
      `${esc(r.level)} · ${r.flagged}/${r.clean + r.flagged}</b></div>`;
    $('#modalbody').innerHTML =
      `<h2>${esc(this.label)}, separated</h2>` +
      `<p>Every project was classified file by file, then rolled up. The split lives in
       <code>UMAP/data/folders/&lt;project&gt;.json</code>, one file per project with a
       <code>clean</code> list and a <code>flagged</code> list. Click a row for the evidence.</p>` +
      `<h2>${flagged.length} flagged</h2>${flagged.map(row).join('')}` +
      `<h2>${clean.length} clean</h2>${clean.map(row).join('')}`;
    $('#modalbody').querySelectorAll('[data-p]').forEach(e =>
      e.addEventListener('click', () => this.showSplit(e.dataset.p)));
  }

  async showFile(path) {
    $('#modalbody').innerHTML = `<h2>${esc(path)}</h2><p>reading…</p>`;
    $('#modal').hidden = false;
    let r;
    try {
      r = await fetch('/api/file?lines=400&path=' + encodeURIComponent(path)).then(x => x.json());
    } catch (err) {
      r = { ok: false, reason: String(err) };
    }
    if (!r.ok) {
      $('#modalbody').innerHTML = `<h2>${esc(path)}</h2><p>${esc(r.reason || 'could not read it')}</p>`;
      return;
    }
    $('#modalbody').innerHTML =
      `<h2>${esc(path)}</h2>` +
      `<p style="color:#8797ad">${esc(r.lang)} · ${r.total} lines` +
      (r.truncated ? ' · showing the first 400' : '') + `</p>` +
      `<pre>${esc(r.text)}</pre>`;
  }

  showHookHelp() {
    const port = location.port || '5580';
    $('#modalbody').innerHTML = `
      <h2>Put a real agent on the map</h2>
      <p>The map lights up when something POSTs to
      <code>http://127.0.0.1:${port}/api/event</code>. A Claude Code hook does that
      on every tool call, so the agent does not have to know the map exists.</p>
      <ol>
        <li>Double-click <b>Install Agent Hook.bat</b> in the UMAP folder.
            It merges a <code>PreToolUse</code> hook into
            <code>.claude/settings.json</code> of the folder this map covers and touches nothing else.</li>
        <li>Start a Claude Code session anywhere in that folder.</li>
        <li>Watch it read, grep and edit. Every file it touches lights up here,
            with a trail behind it.</li>
      </ol>
      <p>To take it off again: <b>Install Agent Hook.bat --uninstall</b>.</p>
      <h2>Or let the agent ask the map questions</h2>
      <p>The map answers "what is near this file, in meaning?" —
      the agent calls it and the query itself shows up in the feed:</p>
      <pre>curl "http://127.0.0.1:${port}/api/neighbors?path=VESPER/src/orbit.js&amp;agent=me"</pre>
      <p>That is the same k-NN graph the layout was built from, so the answer is
      the map's own opinion, not a fresh guess.</p>`;
    $('#modal').hidden = false;
  }
}

export { $, el, esc, kfmt, ago };
