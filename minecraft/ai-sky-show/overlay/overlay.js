/* AISkyShow overlay — listens to /stream (overlay messages, CONTRACT §5) and draws the show.
   Plain JS, no dependencies. The page is also the VOICE audio source OBS records. */
(() => {
  "use strict";
  const Q = new URLSearchParams(location.search);
  const DEMO = Q.has("demo");
  const MUTE = Q.get("mute") === "1";
  const $ = (s, el = document) => el.querySelector(s);
  const stage = $("#stage");
  const body = document.body;
  if (DEMO || Q.get("bg") === "1") body.classList.add("with-bg");

  // ---------- fit 1920x1080 into whatever window we are in (OBS = exactly 1:1) ----------
  function fit() {
    const s = Math.min(innerWidth / 1920, innerHeight / 1080);
    const x = (innerWidth - 1920 * s) / 2, y = (innerHeight - 1080 * s) / 2;
    stage.style.transform = `translate(${x}px, ${y}px) scale(${s})`;
  }
  addEventListener("resize", fit); fit();

  // ---------- colour helpers (no color-mix: OBS's Chromium may predate it) ----------
  function rgb(hex) {
    const h = (hex || "#ffffff").replace("#", "");
    const n = parseInt(h.length === 3 ? h.split("").map(c => c + c).join("") : h, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function paint(el, hex) {
    const [r, g, b] = rgb(hex);
    el.style.setProperty("--c", hex);
    el.style.setProperty("--ca", `rgba(${r},${g},${b},.45)`);
    el.style.setProperty("--cb", `rgba(${r},${g},${b},.72)`);
    el.style.setProperty("--cd", `rgb(${r * .45 | 0},${g * .45 | 0},${b * .45 | 0})`);
  }
  const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  function coins(n) {
    n = Number(n); if (!isFinite(n)) return "—";
    for (const [d, s] of [[1e9, "B"], [1e6, "M"], [1e3, "k"]]) if (Math.abs(n) >= d) return (n / d).toFixed(1).replace(/\.0$/, "") + s;
    return String(Math.round(n));
  }

  // ---------- characters / quadrants ----------
  const chars = {};
  function ensureChar(name, info = {}) {
    let c = chars[name];
    if (!c) {
      const slot = Number.isFinite(+info.slot) ? +info.slot : Object.keys(chars).length;
      const q = document.createElement("div");
      q.className = "quad"; q.dataset.slot = slot;
      q.innerHTML = `
        <div class="frame"></div>
        <div class="plate">
          <div class="head"><div class="num">0${slot + 1}</div><div class="name"><span class="nm"></span><span class="lv">LV<b>—</b></span></div></div>
          <div class="arch"></div>
          <div class="body">
            <div class="stats"><span class="coin">0</span><span class="isle">Private Island</span></div>
            <div class="hp"><i></i><span></span></div>
            <div class="goal">waking up…</div>
          </div>
        </div>
        <div class="chats"></div>
        <div class="capslot"></div>`;
      $("#quads").appendChild(q);
      c = chars[name] = { name, slot, q, color: info.color || "#ffffff", st: {} };
      $(".nm", q).textContent = name.toUpperCase();
    }
    if (info.color && info.color !== c.color) c.color = info.color;
    paint(c.q, c.color);
    if (info.archetype) $(".arch", c.q).textContent = info.archetype;
    return c;
  }
  function charFor(name, color) { return name ? ensureChar(name, color ? { color } : {}) : null; }

  function onStatus(m) {
    const c = ensureChar(m.char, m);
    const st = c.st, q = c.q;
    const prevGoal = st.goal;
    for (const k of ["level", "purse", "island", "goal", "health", "maxHealth"]) if (m[k] !== undefined && m[k] !== null) st[k] = m[k];
    if (st.level !== undefined) $(".lv b", q).textContent = st.level;
    if (st.purse !== undefined) $(".coin", q).textContent = coins(st.purse);
    if (st.island) $(".isle", q).textContent = st.island;
    if (st.goal) {
      const g = $(".goal", q);
      g.textContent = st.goal;
      if (prevGoal !== st.goal && !m.replay) { g.classList.remove("flash"); void g.offsetWidth; g.classList.add("flash"); }
    }
    if (st.health !== undefined) {
      const max = st.maxHealth || 100, pct = Math.max(0, Math.min(100, 100 * st.health / max));
      const hp = $(".hp", q);
      $("i", hp).style.width = pct + "%";
      $("span", hp).textContent = `${Math.round(st.health)} / ${Math.round(max)}`;
      hp.classList.toggle("low", pct < 25);
    }
  }

  // ---------- audio ----------
  let unlocked = false;
  const unlockEl = $("#unlock");
  unlockEl.addEventListener("click", () => {
    unlocked = true; unlockEl.classList.remove("show");
    const a = new Audio(); a.play().catch(() => {});
  });
  function playClip(url) {
    if (!url || MUTE) return null;
    const a = new Audio(url);
    a.preload = "auto";
    a.play().catch(err => { if (err && err.name === "NotAllowedError" && !unlocked) unlockEl.classList.add("show"); });
    return a;
  }

  // ---------- typewriter synced to the clip ----------
  function typewriter(el, text, ms, done) {
    el.innerHTML = '<span class="shown"></span><span class="rest"></span>';
    const shown = el.firstChild, rest = el.lastChild;
    rest.textContent = text;
    const t0 = performance.now();
    (function frame(now) {
      const p = Math.min(1, (now - t0) / Math.max(200, ms));
      const n = Math.round(text.length * p);
      shown.textContent = text.slice(0, n); rest.textContent = text.slice(n);
      if (p < 1) requestAnimationFrame(frame); else done && done();
    })(t0);
  }
  /* Start revealing when the audio actually starts (or right away without audio). Calls back
     when BOTH the reveal and the audio are finished. */
  function voiced(textEl, text, msg, done) {
    const a = playClip(msg.audio_url);
    let started = false, revealDone = false, audioDone = !a;
    const finish = () => { if (revealDone && audioDone) done(); };
    const go = () => {
      if (started) return; started = true;
      let ms = msg.duration_ms || 3000;
      if (a && isFinite(a.duration) && a.duration > 0) ms = a.duration * 1000;
      typewriter(textEl, text, ms * 0.9, () => { revealDone = true; finish(); });
    };
    if (a) {
      a.addEventListener("playing", go, { once: true });
      a.addEventListener("ended", () => { audioDone = true; finish(); }, { once: true });
      a.addEventListener("error", () => { audioDone = true; go(); finish(); }, { once: true });
      setTimeout(go, 900);                                   // never wait for ever on a blocked autoplay
      setTimeout(() => { audioDone = true; finish(); }, (msg.duration_ms || 4000) + 2500);
    } else go();
    return a;
  }

  // ---------- inner-voice captions ----------
  const liveCaps = {};
  const isGrid = () => body.classList.contains("mode-grid");
  function onCaption(m) {
    const c = charFor(m.char, m.color);
    const color = m.color || (c && c.color) || "#fff";
    const holder = isGrid() && c ? $(".capslot", c.q) : $("#center-caps");
    if (!isGrid()) $("#center-caps").querySelectorAll(".caption").forEach(e => e.remove());
    if (liveCaps[m.char]) liveCaps[m.char].remove();
    const el = document.createElement("div");
    const emo = (m.emotion || "calm").toLowerCase();
    el.className = `caption emo-${emo}`;
    paint(el, color);
    el.innerHTML = `
      <div class="tag"><span class="who">${esc(m.char)}</span><span class="thinking"><span class="cloud"><i></i><i></i><i></i></span>THINKING</span></div>
      <div class="emo">${esc(emo)}</div>
      <div class="text"><span class="q">“</span><span class="tw"></span><span class="q close">”</span></div>
      <div class="trail"><i></i><i></i></div>`;
    holder.appendChild(el);
    liveCaps[m.char] = el;
    if (c) c.q.classList.add("speaking");
    voiced($(".tw", el), m.text || "", m, () => {
      el.classList.add("done");
      setTimeout(() => {
        el.classList.add("out");
        setTimeout(() => { el.remove(); if (liveCaps[m.char] === el) { delete liveCaps[m.char]; if (c) c.q.classList.remove("speaking"); } }, 500);
      }, 1700 + Math.min(1500, (m.text || "").length * 12));
    });
  }

  // ---------- narrator ----------
  let narrTimer = 0;
  function onNarrate(m) {
    const n = $("#narr");
    n.classList.add("on");
    clearTimeout(narrTimer);
    voiced($(".text", n), m.text || "", m, () => { narrTimer = setTimeout(() => n.classList.remove("on"), 2200); });
  }

  // ---------- chat bubbles ----------
  function onChat(m) {
    const c = charFor(m.char, m.color);
    const holder = isGrid() && c ? $(".chats", c.q) : $("#cine-chat");
    const b = document.createElement("div");
    b.className = "bubble typing";
    paint(b, m.color || (c && c.color) || "#fff");
    b.innerHTML = `<span class="who">${esc(m.char)}</span><span class="dots"><i></i><i></i><i></i></span><span class="txt">${esc(m.text)}</span>`;
    holder.appendChild(b);
    while (holder.children.length > 3) holder.firstElementChild.remove();
    const typing = Math.max(650, Math.min(1800, 400 + (m.text || "").length * 28));
    setTimeout(() => {
      b.classList.remove("typing");
      b.style.animation = "none"; void b.offsetWidth; b.style.animation = "";
      setTimeout(() => { b.classList.add("out"); setTimeout(() => b.remove(), 500); }, 8000);
    }, typing);
  }

  // ---------- ticker ----------
  function onTicker(m) {
    const lane = $("#ticker .lane");
    const it = document.createElement("div");
    it.className = "item" + ((m.drama || 0) >= 6 ? " big" : "");
    if (m.color) paint(it, m.color);
    it.innerHTML = `<span class="pip"></span><span>${esc(m.text)}</span>`;
    if (lane.firstChild) { const s = document.createElement("div"); s.className = "sep"; lane.prepend(s); }
    lane.prepend(it);
    const items = lane.querySelectorAll(".item");
    if (items.length > 4) { const last = items[items.length - 1]; const prev = last.previousElementSibling; last.remove(); if (prev && prev.classList.contains("sep")) prev.remove(); }
  }

  // ---------- clock ----------
  const clock = { elapsed: 0, total: 1800, running: false, at: performance.now(), seen: false };
  function onClock(m) {
    clock.elapsed = +m.elapsed_s || 0; clock.total = +m.total_s || 1800; clock.running = !!m.running;
    clock.at = performance.now(); clock.seen = true;
    if (m.episode) $("#clock .ep").textContent = "EP " + String(m.episode).padStart(2, "0");
  }
  const mmss = s => { s = Math.max(0, Math.floor(s)); return String(Math.floor(s / 60)).padStart(2, "0") + ":" + String(s % 60).padStart(2, "0"); };
  function drawClock(now) {
    const el = $("#clock");
    const e = clock.elapsed + (clock.running ? (now - clock.at) / 1000 : 0);
    $(".t", el).textContent = mmss(e);
    $(".tot", el).textContent = "/ " + mmss(clock.total);
    $(".bar i", el).style.width = Math.min(100, 100 * e / clock.total) + "%";
    el.classList.toggle("idle", !clock.seen);
    el.classList.toggle("paused", clock.seen && !clock.running && e > 0 && e < clock.total);
    el.classList.toggle("final", clock.total - e < 60 && e < clock.total);
    requestAnimationFrame(drawClock);
  }
  requestAnimationFrame(drawClock);

  // ---------- scenes ----------
  let mode = "grid";
  function sting() {
    for (const id of ["#flash", "#speed"]) { const e = $(id); e.classList.remove("go"); void e.offsetWidth; e.classList.add("go"); }
  }
  let lowerTimer = 0;
  function showLower(lt, target) {
    const el = $("#lower");
    clearTimeout(lowerTimer);
    el.classList.remove("on", "leave");
    if (!lt) return;
    const col = lt.color || (target && chars[target] && chars[target].color) || "#ff3d5a";
    paint(el, col);
    $(".kick", el).textContent = target ? target.toUpperCase() : "LIVE";
    $(".title", el).textContent = lt.title || "";
    $(".sub", el).textContent = lt.subtitle || "";
    $(".sub", el).style.display = lt.subtitle ? "" : "none";
    void el.offsetWidth;
    setTimeout(() => el.classList.add("on"), 450);
    lowerTimer = setTimeout(() => el.classList.add("leave"), 6500);
  }
  let introTimer = 0;
  function showIntro(target, lt) {
    const c = chars[target];
    const el = $("#intro");
    clearTimeout(introTimer);
    el.classList.remove("on", "leave");
    paint(el, (lt && lt.color) || (c && c.color) || "#fff");
    const slot = lt && Number.isFinite(+lt.slot) ? +lt.slot : (c ? c.slot : 0);
    $(".num", el).textContent = "PLAYER 0" + (slot + 1);
    $(".nm", el).textContent = (lt && lt.title) || target.toUpperCase();
    $(".ghost", el).textContent = ((lt && lt.title) || target.toUpperCase() + " ").repeat(3);
    $(".ar", el).textContent = (lt && lt.subtitle) || (c && $(".arch", c.q).textContent.toUpperCase()) || "";
    void el.offsetWidth;
    el.classList.add("on");
    body.classList.add("intro-card");
    introTimer = setTimeout(() => {
      el.classList.add("leave"); body.classList.remove("intro-card");
      setTimeout(() => el.classList.remove("on", "leave"), 400);
    }, 4600);
  }
  function onScene(m) {
    const next = m.mode || "grid";
    const prev = mode;
    mode = next;
    body.classList.remove("mode-grid", "mode-cinematic", "mode-intro");
    body.classList.add("mode-" + next);
    for (const c of Object.values(chars)) c.q.classList.toggle("focus", next === "grid" && !!m.focus && m.focus === c.name);
    if (next === "cinematic") {
      if (prev !== "cinematic" && !m.replay) sting();
      showLower(m.lower_third, m.target);
    } else {
      $("#lower").classList.add("leave");
    }
    if (next === "intro" && m.target && !m.replay) showIntro(m.target, m.lower_third);
    // move live captions into the right holder for the new layout
    for (const [name, el] of Object.entries(liveCaps)) {
      const c = chars[name];
      const holder = next === "grid" && c ? $(".capslot", c.q) : $("#center-caps");
      if (el.parentNode !== holder) holder.appendChild(el);
    }
  }
  function onLetterbox(m) { body.classList.toggle("letterbox", !!m.on); }

  // ---------- title card ----------
  let titleTimer = 0;
  function onTitle(m) {
    const el = $("#title");
    const words = String(m.title || "").split(/\s+/).filter(Boolean);
    let l1 = words, l2 = [], l3 = [];
    // "Can 4 AIs" / "Beat Hypixel" / "SkyBlock?" : last word huge, the two before it big, the rest on top
    if (words.length >= 4) { l1 = words.slice(0, -3); l2 = words.slice(-3, -1); l3 = words.slice(-1); }
    else if (words.length > 1) { l1 = words.slice(0, -1); l3 = words.slice(-1); }
    const up = ws => ws.map(w => /^[A-Z0-9]{2,}s\W*$/.test(w) ? w : w.toUpperCase()).join(" ");   // keep "AIs"
    $(".l1", el).textContent = up(l1);
    $(".l2", el).textContent = up(l2);
    $(".l3", el).textContent = up(l3);
    $(".l2", el).style.display = l2.length ? "" : "none";
    const cast = $(".cast", el); cast.innerHTML = "";
    const list = Object.values(chars).sort((a, b) => a.slot - b.slot);
    list.forEach((c, i) => { const s = document.createElement("span"); s.textContent = c.name.toUpperCase(); paint(s, c.color); s.style.animationDelay = (1.5 + i * .12) + "s"; cast.appendChild(s); });
    cast.style.display = list.length && words.length >= 4 ? "" : "none";
    $(".ep", el).textContent = m.subtitle || "";
    $(".ep", el).style.display = m.subtitle ? "" : "none";
    clearTimeout(titleTimer);
    el.classList.remove("on", "leave"); void el.offsetWidth; el.classList.add("on");
    titleTimer = setTimeout(() => { el.classList.add("leave"); setTimeout(() => el.classList.remove("on", "leave"), 650); }, (m.seconds || 6) * 1000);
  }

  // ---------- wire-up ----------
  const H = {
    status: onStatus, caption: onCaption, chat: onChat, ticker: onTicker, clock: onClock, scene: onScene,
    letterbox: onLetterbox, title_card: onTitle, narrate: onNarrate,
    cost: () => {}, debug: m => console.warn("[bridge]", m),
  };
  function handle(m) {
    const fn = H[m.kind];
    if (!fn) return;
    try { fn(m); } catch (e) { console.error("overlay", m.kind, e); }
  }
  window.AISKY = { handle, chars };          // for poking from DevTools

  function connect() {
    const es = new EventSource("/stream");
    const conn = $("#conn");
    es.onopen = () => conn.classList.remove("show");
    es.onmessage = e => { try { handle(JSON.parse(e.data)); } catch (err) { console.error(err); } };
    es.onerror = () => { if (!window.obsstudio) conn.classList.add("show"); };   // never on the recording
  }
  if (Q.get("nostream") !== "1") connect();      // ?nostream=1: drive it by hand via AISKY.handle
})();
