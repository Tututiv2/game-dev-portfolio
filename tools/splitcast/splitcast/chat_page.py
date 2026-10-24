"""The combined-chat overlay page.

Kept out of overlay.py only because it is large. The interesting decisions:

* Twitch is read IN THE BROWSER over anonymous IRC-on-WebSocket. No token, no
  server round trip, and messages arrive the instant they are sent. YouTube
  cannot be read that way (its endpoint refuses cross-origin requests), so it is
  polled from the Python side. One overlay, two completely different transports.

* Twitch emote positions in the `emotes` tag are indexed by CODE POINT, not by
  UTF-16 unit. Slicing the raw JS string breaks the moment anyone types an emoji
  (which is two UTF-16 units and one code point) - every emote after it lands in
  the wrong place. The message is split with Array.from() first for that reason.
"""

PAGE = r"""<!doctype html>
<meta charset="utf-8">
<title>SplitCast Combined Chat</title>
<style>
  :root {
    --twitch: #a970ff;
    --youtube: #ff4e45;
    --ink: #ffffff;
    --dim: #c9cede;
  }
  /* Everything below is in rem, and 1rem is 1/120th of the viewport width.
     That makes 1rem = 16px on a 1920-wide source and 32px on a 3840-wide one,
     so the SAME page fills a 4K browser source at native resolution instead of
     being rendered at 1080p and scaled up (which is what makes an overlay look
     soft on a 4K canvas). Set the browser source to your canvas size and the
     layout follows. */
  html { font-size: calc(100vw / 120); }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body {
    width: 100%; height: 100%;
    background: transparent;
    overflow: hidden;
    font-family: "Segoe UI", Inter, system-ui, sans-serif;
  }
  #chat {
    position: absolute;
    left: 0; right: 0; bottom: 0;
    padding: 1rem 1.125rem;
    display: flex; flex-direction: column; justify-content: flex-end;
    gap: 0.4375rem;
    max-height: 100%;
  }
  .msg {
    display: flex; align-items: flex-start; gap: 0.5625rem;
    font-size: calc(__SIZE__ * 0.0625rem); line-height: 1.34;
    color: var(--ink);
    /* An overlay sits on gameplay, so legibility comes from an outline rather
       than a panel - a panel would block the game, a plain glow would not
       survive a bright frame. */
    text-shadow: 0 .125rem .25rem rgba(0,0,0,.95), 0 0 .125rem rgba(0,0,0,.9);
    animation: pop .22s ease-out;
    word-break: break-word;
  }
  @keyframes pop { from { opacity: 0; transform: translateY(.4375rem); } }
  /* Messages age out so the overlay does not become a wall of stale chat.
     The fade is a class rather than an animation-delay so the timer can be
     cancelled if the message is removed for hitting the count cap first. */
  .msg.fade { opacity: 0; transform: translateX(-.75rem);
              transition: opacity .5s ease, transform .5s ease; }
  @media (prefers-reduced-motion: reduce) { .msg { animation: none; } }

  .icon { flex: 0 0 auto; width: 1.05em; height: 1.05em; margin-top: .18em; }
  .icon svg { width: 100%; height: 100%; display: block;
              filter: drop-shadow(0 .0625rem .125rem rgba(0,0,0,.9)); }
  .tw .icon svg { fill: var(--twitch); }
  .yt .icon svg { fill: var(--youtube); }

  .body { min-width: 0; }
  .who { font-weight: 700; margin-right: .35em; }
  .tw .who { color: var(--twitch); }
  .yt .who { color: var(--youtube); }
  .txt { color: var(--ink); font-weight: 500; }
  .emote {
    height: 1.5em; vertical-align: -.35em; margin: 0 .1em;
    filter: drop-shadow(0 .0625rem .125rem rgba(0,0,0,.8));
  }
  /* Dock mode: the same chat as a readable WINDOW rather than an overlay.
     Opaque so it survives any desktop behind it, scrollable so history is
     reachable, and messages never expire - the whole point is reading back. */
  body.dock { background: #0e1015; }
  body.dock #chat {
    position: static; height: 100%; overflow-y: auto;
    justify-content: flex-start; padding: 1rem 1.25rem 1.5rem;
    scrollbar-width: thin; scrollbar-color: #2c3040 transparent;
  }
  body.dock #chat::-webkit-scrollbar { width: .5rem; }
  body.dock #chat::-webkit-scrollbar-thumb { background: #2c3040; border-radius: .25rem; }
  body.dock .msg {
    text-shadow: none;
    padding: .3rem .1rem;
    border-bottom: .0625rem solid rgba(255,255,255,.045);
  }
  body.dock .msg.fade { opacity: 1; transform: none; }
  .time {
    color: #6b7186; font-size: .72em; margin-right: .45em;
    font-variant-numeric: tabular-nums; display: none;
  }
  body.dock .time { display: inline; }
  body.dock #status {
    position: sticky; top: 0; left: 0; opacity: 1;
    background: #0e1015; padding: .35rem 0 .5rem; width: 100%;
    border-bottom: .0625rem solid rgba(255,255,255,.07);
  }

  /* Compose bar and moderation menu exist ONLY in dock mode. The overlay is
     on stream - a text box and a ban button have no business being visible to
     viewers, and cannot be clicked there anyway. */
  #compose { display: none; }
  body.dock #compose {
    display: flex; gap: .4rem; align-items: center;
    position: sticky; bottom: 0; width: 100%;
    padding: .5rem 0 .2rem; background: #0e1015;
    border-top: .0625rem solid rgba(255,255,255,.08);
  }
  #compose input {
    flex: 1; min-width: 0; background: #1a1e28; color: #e7e9ee;
    border: .0625rem solid #2a2f3c; border-radius: .5rem;
    padding: .5rem .7rem; font: inherit; font-size: .95em; outline: none;
  }
  #compose input:focus { border-color: var(--twitch); }
  #compose button {
    background: var(--twitch); color: #fff; border: 0; border-radius: .5rem;
    padding: .5rem .9rem; font: inherit; font-weight: 700; cursor: pointer;
  }
  #compose button:disabled { opacity: .5; cursor: default; }
  #compose select {
    background: #1a1e28; color: #e7e9ee; border: .0625rem solid #2a2f3c;
    border-radius: .5rem; padding: .5rem .4rem; font: inherit; font-size: .9em;
  }
  #sendnote { font-size: .8em; color: #8b93a4; min-height: 1em; padding: .1rem 0 .3rem; }
  body.dock .who { cursor: pointer; }
  body.dock .who:hover { text-decoration: underline; }

  #menu {
    display: none; position: fixed; z-index: 40;
    background: #171a22; border: .0625rem solid #2a2f3c; border-radius: .6rem;
    box-shadow: 0 .5rem 1.6rem rgba(0,0,0,.6); padding: .4rem; min-width: 11rem;
  }
  #menu.open { display: block; }
  #menu .mhead {
    font-size: .82em; color: #9aa2b4; padding: .25rem .5rem .4rem;
    border-bottom: .0625rem solid #262b36; margin-bottom: .3rem;
  }
  #menu button {
    display: block; width: 100%; text-align: left; background: none; border: 0;
    color: #e7e9ee; font: inherit; font-size: .9em; padding: .42rem .5rem;
    border-radius: .35rem; cursor: pointer;
  }
  #menu button:hover { background: #232833; }
  #menu button.danger { color: #ff7b7b; }
  #menu .note { font-size: .8em; color: #8b93a4; padding: .3rem .5rem; }

  #hist {
    display: none; position: fixed; inset: 5% 6%; z-index: 60;
    background: #12151c; border: .0625rem solid #2a2f3c; border-radius: .8rem;
    box-shadow: 0 1rem 3rem rgba(0,0,0,.7); flex-direction: column; overflow: hidden;
  }
  #hist.open { display: flex; }
  #hist header {
    display: flex; align-items: center; justify-content: space-between;
    padding: .7rem .9rem; border-bottom: .0625rem solid #262b36; flex: 0 0 auto;
  }
  #hist header b { font-size: 1em; }
  #hist header span { color: #8b93a4; font-size: .85em; font-weight: 500; }
  #hist .rows { overflow-y: auto; padding: .5rem .9rem .9rem; }
  #hist .r { display: flex; gap: .5rem; padding: .28rem 0; align-items: baseline;
             border-bottom: .0625rem solid rgba(255,255,255,.04); }
  #hist .r .p { flex: 0 0 auto; font-size: .72em; font-weight: 700; }
  #hist .r .p.tw { color: var(--twitch); }
  #hist .r .p.yt { color: var(--youtube); }
  #hist .r .t { color: #6b7186; font-size: .72em; font-variant-numeric: tabular-nums; }
  #hist button.x {
    background: #232833; border: 0; color: #e7e9ee; border-radius: .4rem;
    padding: .3rem .7rem; cursor: pointer; font: inherit; font-size: .85em;
  }

  #status {
    position: absolute; top: .5rem; left: 1.125rem;
    font-size: .6875rem; color: var(--dim); opacity: .55;
    text-shadow: 0 .0625rem .1875rem rgba(0,0,0,.9);
  }
</style>

<body>
<div id="status">connecting…</div>
<div id="chat"></div>
<div id="sendnote"></div>
<div id="compose">
  <select id="target" title="Where the message goes">
    <option value="all">All</option>
    <option value="twitch">Twitch</option>
    <option value="youtube">YouTube</option>
  </select>
  <input id="say" type="text" placeholder="Message your chat..." autocomplete="off">
  <button id="sendbtn">Send</button>
</div>
<div id="menu"></div>
<div id="hist"></div>

<script>
/* Layout scale. 1rem is 1/120th of the viewport width times this factor, so the
   overlay is a function of the canvas rather than a pixel size - and ?scale=2.5
   retunes it live in the preview without touching the config or restarting. */
(function () {
  const qs = new URLSearchParams(location.search);
  const dock = qs.has('dock');
  const q = parseFloat(qs.get('scale'));
  const SCALE = (q > 0 && q < 20) ? q : (dock ? __DOCKSCALE__ : __SCALE__);
  function fit() {
    // An OVERLAY scales with the canvas it is drawn on, so its base is derived
    // from viewport width. A WINDOW does not - it is whatever size the user
    // dragged it to, and tying text size to that made a 520px-wide dock window
    // render at 520/120 = 4.3px per rem, i.e. about 5px text. A window gets an
    // absolute base instead, and 500% of 4px is still unreadable.
    const base = dock ? 16 : Math.max(window.innerWidth, 320) / 120;
    document.documentElement.style.fontSize = (base * SCALE) + 'px';
  }
  fit();
  addEventListener('resize', fit);
})();

/* The browser source caches this page, so an edit would otherwise need a manual
   "Refresh cache" in Streamlabs every time. /version is a hash of the page the
   server would serve RIGHT NOW - it only changes once SplitCast is actually
   running new code, so this reloads exactly when there is something new and
   never merely because a file on disk was touched. */
(function () {
  let mine = null;
  async function check() {
    try {
      const v = await (await fetch('/version', { cache: 'no-store' })).json();
      if (mine === null) mine = v.hash;
      else if (v.hash !== mine) location.reload();
    } catch (e) { /* server restarting - try again next tick */ }
    setTimeout(check, 5000);
  }
  check();
})();
const DEMO = new URLSearchParams(location.search).has('demo');
const CHANNEL = "__TWITCH__".toLowerCase();
const MAX = __MAX__;
const DOCK = new URLSearchParams(location.search).has('dock');
// In a dock window nothing expires - reading back is the entire purpose.
const TTL = DOCK ? 0
  : (parseFloat(new URLSearchParams(location.search).get('ttl')) || __TTL__);
const KEEP = DOCK ? 400 : null;
if (DOCK) document.body.classList.add('dock');

function stamp() {
  const d = new Date();
  return String(d.getHours()).padStart(2, '0') + ':' +
         String(d.getMinutes()).padStart(2, '0');
}
const chat = document.getElementById('chat');
const statusEl = document.getElementById('status');

const ICONS = {
  twitch: '<svg viewBox="0 0 24 24"><path d="M4 2 2 6.5V21h5v3h3l3-3h4l5-5V2H4zm16 12.5L17 18h-4l-3 3v-3H6V4h14v10.5zM15 7.5h2v5h-2v-5zm-5 0h2v5h-2v-5z"/></svg>',
  youtube: '<svg viewBox="0 0 24 24"><path d="M23 12s0-3.9-.5-5.8c-.3-1-1.1-1.8-2.1-2.1C18.5 3.5 12 3.5 12 3.5s-6.5 0-8.4.6c-1 .3-1.8 1.1-2.1 2.1C1 8.1 1 12 1 12s0 3.9.5 5.8c.3 1 1.1 1.8 2.1 2.1 1.9.6 8.4.6 8.4.6s6.5 0 8.4-.6c1-.3 1.8-1.1 2.1-2.1.5-1.9.5-5.8.5-5.8zM9.8 15.5v-7l6.2 3.5-6.2 3.5z"/></svg>'
};

const state = { tw: false, yt: 'idle' };
function paintStatus() {
  statusEl.textContent = 'twitch ' + (state.tw ? 'connected' : 'offline')
                       + '  ·  youtube ' + state.yt;
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, c =>
    ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

function render(parts) {
  return parts.map(p => p.t === 'emote'
    ? '<img class="emote" src="' + esc(p.u) + '" alt="' + esc(p.a || '') + '" loading="lazy">'
    : esc(p.v)).join('');
}

function add(msg) {
  const row = document.createElement('div');
  row.className = 'msg ' + (msg.platform === 'twitch' ? 'tw' : 'yt');
  row.dataset.user = msg.author || '';
  row.dataset.platform = msg.platform;
  // YouTube bans take a channel id, never a display name. Kept on the row so a
  // click has it without another lookup.
  row.dataset.channel = msg.channel_id || '';
  row.innerHTML =
    '<span class="icon">' + ICONS[msg.platform] + '</span>' +
    '<span class="body"><span class="time">' + stamp() + '</span><span class="who" ' +
      (msg.color ? 'style="color:' + esc(msg.color) + '"' : '') + '>' +
      esc(msg.author) + '</span><span class="txt">' + render(msg.parts) + '</span></span>';
  // Only autoscroll if the reader is already at the bottom; yanking the view
  // down while they are scrolled up reading is worse than not scrolling at all.
  const atBottom = !DOCK ||
    (chat.scrollHeight - chat.scrollTop - chat.clientHeight) < 60;
  chat.appendChild(row);
  if (typeof filterUser !== 'undefined' && filterUser && row.dataset.user !== filterUser) {
    row.style.display = 'none';
  }
  const cap = KEEP || MAX;
  while (chat.children.length > cap) chat.removeChild(chat.firstChild);
  if (DOCK && atBottom) chat.scrollTop = chat.scrollHeight;
  if (TTL > 0) {
    setTimeout(() => {
      row.classList.add('fade');
      setTimeout(() => row.remove(), 600);
    }, TTL * 1000);
  }
}

/* ---------- compose + moderation (dock window only) ---------- */
const say = document.getElementById('say');
const sendbtn = document.getElementById('sendbtn');
const sendnote = document.getElementById('sendnote');
const menu = document.getElementById('menu');
let filterUser = null;

function note(text, bad) {
  sendnote.textContent = text || '';
  sendnote.style.color = bad ? '#ff7b7b' : '#8b93a4';
  if (text) setTimeout(() => { if (sendnote.textContent === text) sendnote.textContent = ''; }, 6000);
}

async function post(path, body) {
  const r = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  return r.json();
}

const target = document.getElementById('target');

function quotaNote(res) {
  // YouTube charges 50 units per send against 10,000/day, and with two
  // broadcasts that is 100 per message. Showing what is left beats discovering
  // the ceiling when a send starts failing mid-stream.
  if (res.youtube_sends_left === undefined) return '';
  return '  ·  youtube sends left today: ' + res.youtube_sends_left;
}

async function sendMessage() {
  const text = say.value.trim();
  if (!text) return;
  sendbtn.disabled = true;
  const where = target ? target.value : 'twitch';
  try {
    const res = await post('/chat/send', { text: text, platform: where });
    if (res.ok) {
      say.value = '';
      note('sent to ' + (res.sent || []).join(', ') + quotaNote(res));
      if (res.error) note('partly failed - ' + res.error, true);
    } else {
      note(res.error || 'send failed', true);
    }
  } catch (e) {
    note('SplitCast not reachable', true);
  } finally {
    sendbtn.disabled = false;
    say.focus();
  }
}
if (sendbtn) sendbtn.addEventListener('click', sendMessage);
if (say) say.addEventListener('keydown', e => { if (e.key === 'Enter') sendMessage(); });

function applyFilter() {
  for (const row of chat.children) {
    const match = !filterUser || row.dataset.user === filterUser;
    row.style.display = match ? '' : 'none';
  }
}

const hist = document.getElementById('hist');
function closeHist() { hist.classList.remove('open'); }

async function showHistory(user) {
  hist.innerHTML = '<header><b>' + esc(user) + '</b><span>loading…</span>' +
                   '<button class="x">Close</button></header><div class="rows"></div>';
  hist.classList.add('open');
  hist.querySelector('button.x').addEventListener('click', closeHist);
  let data = { messages: [], count: 0 };
  try {
    data = await (await fetch('/chat/user?name=' + encodeURIComponent(user),
                              { cache: 'no-store' })).json();
  } catch (e) { /* fall through to the empty state */ }

  const rows = hist.querySelector('.rows');
  hist.querySelector('header span').textContent =
    data.count + (data.count === 1 ? ' message' : ' messages');
  if (!data.count) {
    rows.innerHTML = '<div class="r"><span class="t">nothing recorded yet. ' +
                     'History starts when SplitCast does - for older messages use ' +
                     'Open their Twitch card.</span></div>';
    return;
  }
  rows.innerHTML = data.messages.map(m => {
    const d = new Date((m.ts || 0) * 1000);
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    const tag = m.platform === 'twitch' ? 'tw' : 'yt';
    const label = m.platform === 'twitch' ? 'TW' : 'YT';
    const body = (m.parts && m.parts.length) ? render(m.parts) : esc(m.text || '');
    return '<div class="r"><span class="p ' + tag + '">' + label + '</span>' +
           '<span class="t">' + hh + ':' + mm + '</span><span>' + body + '</span></div>';
  }).join('');
  rows.scrollTop = rows.scrollHeight;
}

function closeMenu() { menu.classList.remove('open'); }
document.addEventListener('click', e => { if (!menu.contains(e.target)) closeMenu(); });
document.addEventListener('keydown', e => { if (e.key === 'Escape') { closeHist(); closeMenu(); } });

function openMenu(x, y, user, platform, channelId) {
  const esc2 = s => esc(s);
  let html = '<div class="mhead">' + esc2(user) + '  ·  ' + platform + '</div>';
  html += '<button data-act="history">Their chat history (this stream)</button>';
  html += '<button data-act="show">Show only their messages</button>';
  if (filterUser) html += '<button data-act="clear">Show everyone again</button>';
  if (platform === 'twitch') {
    html += '<button data-act="timeout" data-secs="60">Timeout 1 min</button>';
    html += '<button data-act="timeout" data-secs="600">Timeout 10 min</button>';
    html += '<button data-act="timeout" data-secs="3600">Timeout 1 hour</button>';
    html += '<button data-act="unban">Untimeout / unban</button>';
    html += '<button class="danger" data-act="ban">Ban permanently</button>';
    // Twitch's own viewer card can show messages from BEFORE SplitCast started -
    // it uses a private endpoint we deliberately do not depend on, so link to it
    // rather than reverse-engineer something that breaks without warning.
    html += '<button data-act="twitchcard">Open their Twitch card</button>';
  } else if (channelId) {
    html += '<button data-act="timeout" data-secs="60">Timeout 1 min</button>';
    html += '<button data-act="timeout" data-secs="600">Timeout 10 min</button>';
    html += '<button data-act="timeout" data-secs="3600">Timeout 1 hour</button>';
    html += '<button class="danger" data-act="ban">Ban permanently</button>';
    html += '<div class="note">Applies to both your live chats.</div>';
  } else {
    // A YouTube ban needs a channel id. Seeded backlog messages predate that
    // being captured, so say why rather than offering a button that fails.
    html += '<div class="note">No channel id for this message - moderation needs '
         +  'one. Their next message will have it.</div>';
  }
  menu.innerHTML = html;
  menu.classList.add('open');
  // Keep it on screen when the click is near an edge.
  const r = menu.getBoundingClientRect();
  menu.style.left = Math.min(x, window.innerWidth - r.width - 8) + 'px';
  menu.style.top = Math.min(y, window.innerHeight - r.height - 8) + 'px';

  menu.querySelectorAll('button').forEach(b => {
    b.addEventListener('click', async () => {
      const act = b.dataset.act;
      closeMenu();
      if (act === 'history') { showHistory(user); return; }
      if (act === 'twitchcard') {
        window.open('https://www.twitch.tv/popout/' + CHANNEL +
                    '/viewercard/' + encodeURIComponent(user.toLowerCase()),
                    '_blank', 'width=420,height=640');
        return;
      }
      if (act === 'show') { filterUser = user; applyFilter(); note('showing only ' + user); return; }
      if (act === 'clear') { filterUser = null; applyFilter(); note('showing everyone'); return; }
      const res = await post('/chat/mod', {
        platform: platform, action: act, user: user,
        channel_id: channelId || '',
        seconds: b.dataset.secs ? Number(b.dataset.secs) : null
      });
      if (res.ok) note(act + ' ok: ' + user + (res.seconds && act === 'timeout' ? ' (' + res.seconds + 's)' : ''));
      else note(res.error || (act + ' failed'), true);
    });
  });
}

chat.addEventListener('click', e => {
  if (!DOCK) return;                       // never on the stream overlay
  const who = e.target.closest('.who');
  if (!who) return;
  const row = who.closest('.msg');
  if (!row) return;
  e.stopPropagation();
  openMenu(e.clientX, e.clientY, row.dataset.user, row.dataset.platform,
           row.dataset.channel);
});

/* ---------- Twitch: anonymous IRC over WebSocket ---------- */
function parseTags(raw) {
  const tags = {};
  raw.split(';').forEach(kv => {
    const i = kv.indexOf('=');
    tags[kv.slice(0, i)] = kv.slice(i + 1);
  });
  return tags;
}

function twitchParts(text, emoteTag) {
  // Positions are CODE POINT indices. Splitting the raw string would misplace
  // every emote that follows an astral character.
  const chars = Array.from(text);
  if (!emoteTag) return [{ t: 'text', v: text }];
  const spans = [];
  emoteTag.split('/').forEach(chunk => {
    const [id, ranges] = chunk.split(':');
    if (!ranges) return;
    ranges.split(',').forEach(r => {
      const [a, b] = r.split('-').map(Number);
      if (Number.isFinite(a) && Number.isFinite(b)) spans.push({ a, b, id });
    });
  });
  if (!spans.length) return [{ t: 'text', v: text }];
  spans.sort((x, y) => x.a - y.a);

  const out = [];
  let cur = 0;
  for (const s of spans) {
    if (s.a > cur) out.push({ t: 'text', v: chars.slice(cur, s.a).join('') });
    out.push({
      t: 'emote',
      u: 'https://static-cdn.jtvnw.net/emoticons/v2/' + s.id + '/default/dark/2.0',
      a: chars.slice(s.a, s.b + 1).join('')
    });
    cur = s.b + 1;
  }
  if (cur < chars.length) out.push({ t: 'text', v: chars.slice(cur).join('') });
  return out;
}

let ws, twRetry = 1000;
function connectTwitch() {
  if (!CHANNEL) { state.tw = false; paintStatus(); return; }
  ws = new WebSocket('wss://irc-ws.chat.twitch.tv:443');
  ws.onopen = () => {
    // justinfan<n> is Twitch's documented anonymous login - read-only, no token.
    ws.send('CAP REQ :twitch.tv/tags twitch.tv/commands');
    ws.send('NICK justinfan' + (10000 + Math.floor(Math.random() * 80000)));
    ws.send('JOIN #' + CHANNEL);
    state.tw = true; twRetry = 1000; paintStatus();
  };
  ws.onclose = () => {
    state.tw = false; paintStatus();
    setTimeout(connectTwitch, twRetry);
    twRetry = Math.min(twRetry * 2, 20000);
  };
  ws.onerror = () => { try { ws.close(); } catch (e) {} };
  ws.onmessage = ev => {
    ev.data.split('\r\n').forEach(line => {
      if (!line) return;
      if (line.startsWith('PING')) { ws.send('PONG :tmi.twitch.tv'); return; }
      const m = line.match(/^@([^ ]+) :([^!]+)![^ ]+ PRIVMSG #[^ ]+ :(.*)$/);
      if (!m) return;
      const tags = parseTags(m[1]);
      const text = m[3];
      const msg = {
        platform: 'twitch',
        author: tags['display-name'] || m[2],
        color: tags.color || '',
        parts: twitchParts(text, tags.emotes)
      };
      add(msg);
      // The SERVER never sees Twitch chat - it is read here, in the browser. Feed
      // it up or "their whole history" would silently be YouTube-only, which is
      // worse than no history because it looks complete.
      fetch('/chat/ingest', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          platform: 'twitch', author: msg.author, text: text,
          parts: msg.parts, id: tags.id || ''
        })
      }).catch(() => {});
    });
  };
}

/* ---------- YouTube: polled from the Python side ---------- */
let since = 0;
async function pollYouTube() {
  try {
    const r = await fetch('/chat/poll?since=' + since, { cache: 'no-store' });
    const d = await r.json();
    since = d.cursor;
    state.yt = d.status;
    (d.messages || []).forEach(add);
  } catch (e) {
    state.yt = 'server down';
  }
  paintStatus();
  setTimeout(pollYouTube, 1500);
}

/* ---------- demo: fake traffic so the overlay can be positioned and
              styled BEFORE going live. Real emote CDN URLs, so the sizing
              you see is the sizing you get. ---------- */
const E = id => 'https://static-cdn.jtvnw.net/emoticons/v2/' + id + '/default/dark/2.0';
const DEMO_LINES = [
  { platform:'twitch', author:'ballchaser99', color:'#ff7f50',
    parts:[{t:'text',v:'that redirect was filthy '},{t:'emote',u:E(25),a:'Kappa'}] },
  { platform:'youtube', author:'Marra',
    parts:[{t:'text',v:'first time catching you live, lets go'}] },
  { platform:'twitch', author:'SSL_or_bust', color:'#7cc7ff',
    parts:[{t:'emote',u:E(1902),a:'Keepo'},{t:'text',v:' whiffed it'}] },
  { platform:'youtube', author:'kaykay',
    parts:[{t:'text',v:'what rank are you rn?'}] },
  { platform:'twitch', author:'DiamondAndy', color:'#5cffb1',
    parts:[{t:'text',v:'day 239 lets gooo '},{t:'emote',u:E(354),a:'4Head'}] },
  { platform:'youtube', author:'Tuti Fan 01',
    parts:[{t:'text',v:'the music slaps today'}] },
  { platform:'twitch', author:'nvmitsfine', color:'#ffd166',
    parts:[{t:'text',v:'chat is combined now?? clean'}] },
  { platform:'twitch', author:'a_very_long_username_here', color:'#c792ea',
    parts:[{t:'text',v:'testing a much longer message so you can see how it wraps across lines in the overlay'}] },
  { platform:'youtube', author:'sowy',
    parts:[{t:'emote',u:E(86),a:'BibleThump'},{t:'text',v:' so close'}] }
];

function runDemo() {
  state.tw = true; state.yt = 'demo';
  statusEl.textContent = 'DEMO - fake chat, nothing is connected';
  let i = 0;
  add(DEMO_LINES[i++]);
  setInterval(() => { add(DEMO_LINES[i % DEMO_LINES.length]); i++; }, 1600);
}

if (DEMO) {
  runDemo();
} else {
  connectTwitch();
  pollYouTube();
  paintStatus();
}
</script>
"""


def build(cfg):
    return (PAGE
            .replace("__TWITCH__", (cfg.get("chat_twitch_channel") or "")
                     .replace('"', ""))
            .replace("__MAX__", str(int(cfg.get("chat_max_messages", 22))))
            .replace("__SIZE__", str(int(cfg.get("chat_font_size", 19))))
            .replace("__SCALE__", str(float(cfg.get("overlay_scale", 1.7))))
            .replace("__TTL__", str(float(cfg.get("chat_message_ttl", 20))))
            .replace("__DOCKSCALE__", str(float(cfg.get("chat_window_scale", 1.5)))))
