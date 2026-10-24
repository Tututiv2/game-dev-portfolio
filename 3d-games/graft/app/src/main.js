import { C } from './sim/const.js';
import * as P from './sim/planet.js';
import { CATALOG, KIND, spec } from './sim/catalog.js';
import * as W from './sim/world.js';
import { createView } from './view/render.js';
import { createControls } from './view/controls.js';
import { loadModelPack } from './view/assets.js';
import * as SC from './sim/scale.js';
import { registerServiceWorker } from './view/packs.js';

const KEY = 'graft:save:v1';
const $ = (id) => document.getElementById(id);

// A blank page says nothing, and there is no console on a phone.
function fatal(msg) {
  let el = $('fatal');
  if (!el) { el = document.createElement('pre'); el.id = 'fatal'; document.body.appendChild(el); }
  el.textContent = 'GRAFT could not start.\n\n' + msg;
}
window.addEventListener('error', (e) => fatal((e.message || 'error') + '\n' + (e.filename || '') + ':' + (e.lineno || '')));
window.addEventListener('unhandledrejection', (e) => fatal('unhandled: ' + ((e.reason && e.reason.message) || e.reason)));

/* ------------------------------------------------------------------ world */
let w = null;
try { const raw = localStorage.getItem(KEY); if (raw) w = W.load(raw); } catch (e) {}
if (!w) w = W.createWorld();

let awayGain = null;
try {
  const last = +localStorage.getItem(KEY + ':t') || 0;
  if (last) {
    const away = Math.min((Date.now() - last) / 1000, C.grow.offlineCap);
    if (away > 30) {
      const before = { ...w.res };
      const objBefore = w.objects.length;
      W.advance(w, away);
      awayGain = { away, objects: w.objects.length - objBefore, res: subRes(w.res, before) };
    }
  }
} catch (e) {}

function subRes(a, b) {
  const o = {};
  for (const k of Object.keys(a)) if ((a[k] - (b[k] || 0)) > 0) o[k] = Math.round(a[k] - (b[k] || 0));
  return o;
}

/* ------------------------------------------------------------------- view */
const canvas = $('stage');
const view = createView(canvas);
const ctl = createControls(canvas, $('stick'), $('knob'));

// Real geometry, if any is there. Loaded in the background and swapped in when
// it arrives: the game is playable from the first frame either way, and a pack
// that 404s is a game that looks like it always did.
loadModelPack().then((pack) => {
  if (!pack) return;
  view.useModelPack(pack);
  flash('Models loaded.');
}).catch(() => { /* the fallback IS the shipping look */ });

/* ------------------------------------------------------------------- HUD */
let picked = KIND.TREE;
let ghost = null;
let holding = 0;

function buildPalette() {
  const bar = $('palette');
  bar.textContent = '';
  for (const c of CATALOG) {
    const b = document.createElement('button');
    b.className = 'slot';
    b.dataset.kind = c.id;
    b.innerHTML = `<span class="nm">${c.name}</span><span class="cost"></span>`;
    b.addEventListener('click', () => { picked = c.id; paintPalette(); });
    bar.appendChild(b);
  }
}
function costText(c) {
  const n = c.needs || {};
  const k = Object.keys(n);
  return k.length ? k.map(x => `${n[x]}${x[0]}`).join(' ') : 'free';
}
function paintPalette() {
  for (const b of $('palette').children) {
    const c = spec(b.dataset.kind);
    b.classList.toggle('on', b.dataset.kind === picked);
    const locked = !W.unlocked(w, c.id);
    const poor = !W.canAfford(w, c.id);
    b.classList.toggle('locked', locked);
    b.classList.toggle('poor', !locked && poor);
    b.querySelector('.cost').textContent = locked ? `needs a ${c.unlock}` : costText(c);
  }
}
buildPalette();

// ---- everything above the surface ----------------------------------------
// The ladder is a panel rather than a second world you fly to. What matters is
// that it is one tap from the ground and shows the same purse, because the
// ground is what pays for all of it.
$('skyBtn').addEventListener('click', () => { paintSky(); $('sky').hidden = false; });
$('skyClose').addEventListener('click', () => { $('sky').hidden = true; });
$('sky').addEventListener('click', (e) => { if (e.target === $('sky')) $('sky').hidden = true; });

function fmtNum(n) {
  if (n == null) return '';
  if (typeof n === 'object' && n.toString) return n.toString();
  if (n < 1000) return String(Math.round(n));
  if (n < 1e6) return Math.round(n).toLocaleString('en-US');
  return n.toExponential(2);
}

function paintSky() {
  const sc = w.scale;
  const tier = SC.tierSpec(sc.tier);
  $('skyTier').textContent = 'tier ' + (SC.tierIndex(sc.tier) + 1) + ' of ' + SC.TIERS.length;
  $('skyName').textContent = tier ? (tier.name || sc.tier) : sc.tier;

  const goal = SC.nextGoal(sc, w.res);
  $('skyGoal').textContent = goal
    ? `Next: ${goal.name}${goal.wait ? ` — about ${fmtTime(goal.wait)} of gathering away` : ' — you can afford it now'}`
    : 'There is nothing above this.';

  const r = SC.rates(sc);
  const boon = SC.surfaceBoon(sc);
  const bits = [];
  if (r) for (const [k, v] of Object.entries(r)) if (v) bits.push(`<b>${fmtNum(v)}</b> ${k}/s`);
  if (boon && boon.growth > 1) bits.push(`ground grows <b>x${boon.growth.toFixed(1)}</b>`);
  $('skyRates').innerHTML = bits.length ? bits.join('') : '<span>nothing is running up there yet</span>';

  const box = $('mega');
  box.textContent = '';
  const list = SC.MEGA.filter(m => SC.tierIndex(m.tier) <= SC.tierIndex(sc.tier) + 1);
  for (const m of list) {
    const b = document.createElement('button');
    b.className = 'mg';
    const why = SC.blockedReason(sc, w.res, m.id, w.seen);
    const building = sc.building && sc.building.id === m.id;
    const done = SC.has(sc, m.id);
    b.classList.toggle('can', !why && !done && !building);
    b.classList.toggle('busy', !!building);
    b.disabled = !!why || done || !!building;

    const left = document.createElement('div');
    const nm = document.createElement('div');
    nm.className = 'mgName';
    nm.textContent = m.name;
    const wy = document.createElement('div');
    wy.className = 'mgWhy';
    wy.textContent = done ? 'built' : building ? 'building…' : (why || (m.blurb || ''));
    left.appendChild(nm); left.appendChild(wy);

    const cost = document.createElement('div');
    cost.className = 'mgCost';
    const c = SC.costOf(m.id) || {};
    cost.textContent = done ? '' : Object.entries(c)
      .map(([k, v]) => `${fmtNum(v)} ${k.slice(0, 3)}`).join('\n');

    b.appendChild(left); b.appendChild(cost);
    b.addEventListener('click', () => {
      if (SC.begin(sc, w.res, m.id, w.seen)) {
        flash(`${m.name}: under way.`);
        paintSky();
      }
    });
    box.appendChild(b);
  }
}

$('plant').addEventListener('click', doPlant);
$('closeAway').addEventListener('click', () => { $('away').hidden = true; });

if (awayGain) {
  const bits = [];
  if (awayGain.objects > 0) bits.push(`${awayGain.objects} new thing${awayGain.objects > 1 ? 's' : ''} stand where you left space`);
  const r = Object.entries(awayGain.res).map(([k, v]) => `${v} ${k}`);
  if (r.length) bits.push(r.join(', ') + ' waiting');
  $('awayBody').textContent = `${fmtTime(awayGain.away)} away. ` + (bits.length ? bits.join('. ') + '.' : 'Everything kept growing.');
  $('away').hidden = false;
}

function fmtTime(s) {
  if (s < 90) return Math.round(s) + 's';
  if (s < 5400) return Math.round(s / 60) + ' minutes';
  return (s / 3600).toFixed(1) + ' hours';
}

function flash(t) {
  const el = $('toast');
  el.textContent = t;
  el.classList.remove('show'); void el.offsetWidth; el.classList.add('show');
}

/* --------------------------------------------------------------- actions */
function aimPoint() {
  // You plant a little ahead of where you stand, so you can see it appear.
  const f = P.facing(w.player.dir, w.player.yaw);
  return P.norm(P.add(P.mul(w.player.dir, C.planet.radius), P.mul(f, 3.0)));
}

function doPlant() {
  const dir = aimPoint();
  const why = W.blockedReason(w, picked, dir);
  if (why) { flash(why); return; }
  const o = W.plant(w, picked, dir);
  if (o) flash(`${spec(picked).name} planted. It grows from here.`);
  paintPalette();
}

ctl.setTap(() => {
  const t = W.targeted(w);
  if (t && spec(t.kind).harvest && W.finalStage(t)) holding = 0.0001;   // start a harvest
});

/* ------------------------------------------------------------------ loop */
let last = performance.now(), saveAt = 0, acc = 0;
let hiddenAt = 0;

document.addEventListener('visibilitychange', () => {
  if (document.hidden) { hiddenAt = Date.now(); return; }
  const away = hiddenAt ? (Date.now() - hiddenAt) / 1000 : 0;
  hiddenAt = 0; last = performance.now();
  if (away > 1) W.advance(w, Math.min(away, C.grow.offlineCap));
});

function frame(now) {
  const dt = Math.min((now - last) / 1000, 0.25);
  last = now;

  const input = ctl.poll();
  W.advance(w, dt, input);

  // hold-to-harvest on whatever you are standing next to
  const t = W.targeted(w);
  if (holding > 0) {
    if (t && spec(t.kind).harvest && W.finalStage(t)) {
      holding += dt;
      if (holding >= C.player.harvestTime) {
        const got = W.harvest(w, t);
        holding = 0;
        if (got) flash(Object.entries(got).map(([k, v]) => `+${v} ${k}`).join('  '));
        paintPalette();
      }
    } else holding = 0;
  }

  // the ghost of what you would plant
  const gdir = aimPoint();
  const why = W.blockedReason(w, picked, gdir);
  ghost = { dir: gdir, kind: picked, ok: !why };

  view.rig.yaw = ctl.look.yaw;
  view.rig.pitch = ctl.look.pitch;
  view.render(w, dt, { moving: input.moving, running: input.running, ghost });

  hud(t, why);
  if (!$('sky').hidden && Math.floor(acc * 2) !== skyPainted) { skyPainted = Math.floor(acc * 2); paintSky(); }

  acc += dt;
  if (acc - saveAt > 5) {
    saveAt = acc;
    try {
      localStorage.setItem(KEY, W.save(w));
      localStorage.setItem(KEY + ':t', String(Date.now()));
    } catch (e) {}
  }
  requestAnimationFrame(frame);
}

let skyPainted = -1;
let lastHudKey = '';
function hud(target, why) {
  const r = w.res;
  const key = `${Math.floor(r.biomass)}|${Math.floor(r.shard)}|${Math.floor(r.alloy)}|${picked}|${why || ''}|${target ? target.id + target.stage : ''}`;
  if (key !== lastHudKey) {
    lastHudKey = key;
    $('biomass').textContent = Math.floor(r.biomass);
    $('shard').textContent = Math.floor(r.shard);
    $('alloy').textContent = Math.floor(r.alloy);
    $('plant').textContent = why ? why.toUpperCase() : `${spec(picked).verb.toUpperCase()} ${spec(picked).name.toUpperCase()}`;
    $('plant').classList.toggle('no', !!why);
    paintPalette();

    if (target && spec(target.kind).harvest && W.finalStage(target)) {
      $('target').textContent = `tap to gather the ${spec(target.kind).name.toLowerCase()}`;
      $('target').hidden = false;
    } else if (target) {
      const s = spec(target.kind);
      $('target').textContent = `${s.name} — ${s.stages[target.stage].name}`;
      $('target').hidden = false;
    } else $('target').hidden = true;
  }
  $('hold').style.width = holding > 0 ? Math.min(100, (holding / C.player.harvestTime) * 100) + '%' : '0%';

  // settlements: the reason to come back
  const st = W.settlements(w).filter(s => s.tier);
  const bits = st.map(s => `${s.tier} of ${s.count}`);
  if (w.scale && w.scale.tier !== 'surface') bits.push(w.scale.tier);
  $('places').textContent = bits.join('  ·  ');
}

registerServiceWorker().catch(() => { /* offline install is a bonus, not a need */ });

requestAnimationFrame(frame);
window.GRAFT = { w, view, W, P, C, advance: W.advance };
