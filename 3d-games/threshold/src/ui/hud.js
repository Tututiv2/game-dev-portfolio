// The debug HUD — a development instrument, not decoration.
//
// It shows what the audio system BELIEVES, which is the only way to tell a modelling mistake from
// a mixing one. Half the bugs in this project were found by watching a number here move when it
// should not have.

import { mat, gainToDb } from '../world/materials.js';

const CSS = `
.hud { position:fixed; top:10px; left:10px; z-index:20; font:11px/1.45 ui-monospace,Consolas,monospace;
  color:#cfe0ea; background:rgba(10,14,20,.76); border:1px solid rgba(140,180,210,.22);
  border-radius:6px; padding:9px 11px; min-width:330px; pointer-events:none;
  backdrop-filter:blur(3px); text-shadow:0 1px 2px rgba(0,0,0,.6); }
.hud h4 { margin:9px 0 3px; font-size:10px; letter-spacing:.10em; text-transform:uppercase;
  color:#6f93ab; font-weight:600; }
.hud h4:first-child { margin-top:0; }
.hud .row { display:flex; justify-content:space-between; gap:14px; }
.hud .k { color:#7f9cb0; }
.hud .v { color:#e6f2fa; }
.hud .hot { color:#ffc078; }
.hud .dim { color:#5d7385; }
.hud canvas { display:block; margin:5px 0 2px; border-radius:3px; background:rgba(0,0,0,.35); }
.hud .bar { height:3px; background:rgba(255,255,255,.10); border-radius:2px; margin:2px 0 4px; }
.hud .bar > i { display:block; height:100%; border-radius:2px; background:#5fa8d3; }
.crosshair { position:fixed; left:50%; top:50%; width:6px; height:6px; margin:-3px 0 0 -3px;
  border-radius:50%; border:1px solid rgba(255,255,255,.55); z-index:15; pointer-events:none; }
.prompt { position:fixed; left:50%; top:calc(50% + 26px); transform:translateX(-50%); z-index:16;
  font:12px ui-monospace,Consolas,monospace; color:#f2e4cd; pointer-events:none;
  background:rgba(10,14,20,.6); padding:4px 10px; border-radius:4px; white-space:nowrap;
  border:1px solid rgba(255,220,170,.2); }
.banner { position:fixed; left:50%; bottom:34px; transform:translateX(-50%); z-index:16;
  font:12px ui-monospace,Consolas,monospace; color:#cfe0ea; pointer-events:none;
  background:rgba(10,14,20,.7); padding:6px 14px; border-radius:5px; opacity:0; transition:opacity .25s;
  border:1px solid rgba(140,180,210,.25); }
.banner.show { opacity:1; }
`;

export class Hud {
  constructor() {
    const style = document.createElement('style');
    style.textContent = CSS;
    document.head.appendChild(style);

    this.el = document.createElement('div');
    this.el.className = 'hud';
    document.body.appendChild(this.el);

    this.cross = document.createElement('div');
    this.cross.className = 'crosshair';
    document.body.appendChild(this.cross);

    this.prompt = document.createElement('div');
    this.prompt.className = 'prompt';
    this.prompt.style.display = 'none';
    document.body.appendChild(this.prompt);

    this.banner = document.createElement('div');
    this.banner.className = 'banner';
    document.body.appendChild(this.banner);

    this.canvas = document.createElement('canvas');
    this.canvas.width = 306; this.canvas.height = 42;
    this.ctx2d = this.canvas.getContext('2d');

    this.visible = true;
    this.bannerTimer = 0;
    this.frameMs = 16;
  }

  toggle() {
    this.visible = !this.visible;
    this.el.style.display = this.visible ? '' : 'none';
  }

  say(text, seconds = 2.2) {
    this.banner.textContent = text;
    this.banner.classList.add('show');
    this.bannerTimer = seconds;
  }

  /** The low-pass response the listener is currently behind, drawn 20 Hz .. 20 kHz. */
  _drawCurve(cutoff, gain) {
    const c = this.ctx2d, w = this.canvas.width, h = this.canvas.height;
    c.clearRect(0, 0, w, h);
    c.fillStyle = 'rgba(0,0,0,.30)'; c.fillRect(0, 0, w, h);

    c.strokeStyle = 'rgba(255,255,255,.07)'; c.lineWidth = 1;
    for (const f of [100, 1000, 10000]) {
      const x = (Math.log2(f / 20) / Math.log2(1000)) * w;
      c.beginPath(); c.moveTo(x, 0); c.lineTo(x, h); c.stroke();
    }

    c.strokeStyle = '#5fa8d3'; c.lineWidth = 1.5;
    c.beginPath();
    for (let px = 0; px <= w; px++) {
      const f = 20 * Math.pow(1000, px / w);
      // Two-pole roll-off, matching the biquad the engine actually uses.
      const r = 1 / Math.sqrt(1 + Math.pow(f / cutoff, 4));
      const db = 20 * Math.log10(Math.max(r * gain, 1e-4));
      const y = h - ((db + 72) / 72) * h;
      if (px === 0) c.moveTo(px, y); else c.lineTo(px, y);
    }
    c.stroke();
  }

  update(state, dt) {
    if (this.bannerTimer > 0) {
      this.bannerTimer -= dt;
      if (this.bannerTimer <= 0) this.banner.classList.remove('show');
    }

    const t = state.player.target;
    if (t) {
      this.prompt.style.display = '';
      this.prompt.textContent = t.type === 'door'
        ? `[E] ${t.openness > 0.5 ? 'close' : 'open'} the ${t.name}`
        : `[E] hold, or scroll — ${t.name} ${Math.round(t.openness * 100)}% open`;
    } else {
      this.prompt.style.display = 'none';
    }

    if (!this.visible) return;
    this.frameMs += (state.frameMs - this.frameMs) * 0.08;

    const f = state.frame;
    const s = state.smooth;
    const g = state.graph;
    const v = f ? g.volumes.get(f.volumeId) : null;
    const w = state.weather;

    const row = (k, val, cls = 'v') => `<div class="row"><span class="k">${k}</span><span class="${cls}">${val}</span></div>`;
    const bar = (x, col = '#5fa8d3') =>
      `<div class="bar"><i style="width:${Math.round(Math.max(0, Math.min(1, x)) * 100)}%;background:${col}"></i></div>`;

    let html = '';

    html += '<h4>where you are</h4>';
    if (v) {
      html += row('volume', v.outside ? 'outdoors' : `room #${v.id}`);
      if (!v.outside) {
        html += row('sealed', g.isSealed(v) ? 'yes' : 'NO', g.isSealed(v) ? 'v' : 'hot');
        html += row('size', `${v.volume} m³`);
        html += row('surface', mat(v.dominantMaterial).name);
      }
      html += row('outdoors weight', (f.outsideWeight * 100).toFixed(1) + '%');
      html += row('roof overhead', f.roof.hit
        ? `${mat(f.roof.material).name} @ ${f.roof.distance} m · ${(f.roof.cover * 100).toFixed(0)}%`
        : '<span class="dim">open sky</span>');
    }

    html += '<h4>what you hear</h4>';
    html += row('backend', state.backend.name === 'portal'
      ? '<span class="v">B: portals + filters</span>'
      : '<span class="hot">A: baked crossfade</span>');
    if (s) {
      const sum = s.summarise();
      html += row('storm', `${gainToDb(sum.storm).toFixed(1)} dB down`);
      html += row('roof layer', sum.roof > 0.001 ? `${gainToDb(sum.roof).toFixed(1)} dB` : '<span class="dim">silent</span>');
      html += row('wall cutoff', `${Math.round(s.diffuseCutoff)} Hz`);
      html += row('reverb', `RT60 ${s.rt60.toFixed(2)} s · wet ${(s.wet * 100).toFixed(0)}%`);
      this._drawCurve(s.diffuseCutoff, Math.max(s.diffuseGain, 1e-4));
      html += '<div id="hudcurve"></div>';
    }

    html += '<h4>openings</h4>';
    if (f && f.apertures.length) {
      for (const a of f.apertures) {
        const sm = s ? s.apertures.get(a.opening) : null;
        const gdb = sm ? gainToDb(Math.max(sm.gain, 1e-5)) : 99;
        const side = a.pan < -0.25 ? 'L' : a.pan > 0.25 ? 'R' : '·';
        html += row(
          `${a.name} ${Math.round(a.opening.openness * 100)}%`,
          `${gdb.toFixed(0)} dB @ ${Math.round(sm ? sm.cutoff : a.cutoff)} Hz  ${side} ${a.distance.toFixed(1)}m`);
        html += bar(1 - Math.min(1, gdb / 60), a.opening.openness > 0.02 ? '#ffc078' : '#3d6a86');
      }
    } else {
      html += '<div class="dim">none audible</div>';
    }

    html += '<h4>weather</h4>';
    html += row('state', w.label);
    html += row('rain', (w.rain * 100).toFixed(0) + '%');
    html += bar(w.rain, '#5fa8d3');
    html += row('wind', `${(w.windNow * 100).toFixed(0)}%  gust ${(w.gustValue * 100).toFixed(0)}%`);
    html += bar(w.windNow / 1.4, '#8fd3b0');
    html += row('strikes', state.strikes + ' active');

    html += '<h4>cost</h4>';
    html += row('frame', `${this.frameMs.toFixed(1)} ms`);
    html += row('audio nodes', `${state.backend.nodes}${state.backend.portals ? ` (${state.backend.portals} portals)` : ''}`);
    // Worth showing: if a .wav ever 404s the game keeps running on the synth, and without this the
    // only clue is a console warning nobody is looking at.
    if (state.backend.solo) {
      html += row('SOLO', `<span class="hot">${state.backend.solo}</span> — press M`);
    }
    if (state.backend.mix) {
      html += row('roof level', `${(-state.backend.mix.roofDb).toFixed(0)} dB   [ / ]`);
    }
    if (state.backend.weather) {
      html += row('rain source', state.backend.weather === 'recorded'
        ? 'recorded (CC0 / public domain)'
        : 'SYNTH — recordings failed to load');
    }
    html += row('solver fills', String(g.solveCount));

    html += '<h4 class="dim">keys</h4><div class="dim">WASD move · E interact · wheel window · '
      + 'B backend · M solo · [ ] roof level · 1-5 weather · T thunder · ~ hud</div>';

    this.el.innerHTML = html;
    const slot = this.el.querySelector('#hudcurve');
    if (slot) slot.appendChild(this.canvas);
  }
}
