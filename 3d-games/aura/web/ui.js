// DOM side of the HUD. Everything here is cheap to call every frame: values are
// cached and the DOM is only touched when something visibly changes.

const $ = (id) => document.getElementById(id);

export class UI {
  constructor() {
    this.el = {
      hud: $('hud'), hpFill: $('hp').querySelector('.fill'), hpLag: $('hp').querySelector('.lag'),
      auraFill: $('aura').querySelector('.fill'), auraReady: $('auraReady'), dash: $('dash'),
      score: $('scoreNum'), flow: $('flow'), flowMult: $('flowMult'),
      boss: $('boss'), bossFill: $('bossbar').querySelector('.fill'), bossLag: $('bossbar').querySelector('.lag'),
      crosshair: $('crosshair'), beat: $('beat'), view: $('view'), popups: $('popups'),
      subtitle: $('subtitle'), prompt: $('prompt'), ring: $('ring'), ringOuter: $('ring').querySelector('.outer'),
      titlecard: $('titlecard'), lockhint: $('lockhint'),
      timing: $('timing'), ticks: $('ticks'), lastMs: $('lastMs'), dojoStats: $('dojoStats'),
    };
    this.cache = {};
    this.subTimer = 0;
    this.scoreShown = 0;
  }

  set(key, v, fn) { if (this.cache[key] !== v) { this.cache[key] = v; fn(v); } }

  show(id, on) { $(id).classList.toggle('hidden', !on); }
  hud(on) { this.el.hud.classList.toggle('hidden', !on); }
  cine(mode) {
    document.body.classList.toggle('cine', mode === 'cine' || mode === 'tight');
    document.body.classList.toggle('cine-tight', mode === 'tight');
  }

  bars(hp, hpMax, aura, auraMax) {
    const h = Math.max(0, hp / hpMax), a = Math.max(0, aura / auraMax);
    this.set('hp', h.toFixed(3), () => {
      this.el.hpFill.style.transform = `scaleX(${h})`;
      this.el.hpLag.style.transform = `scaleX(${h})`;
    });
    this.set('aura', a.toFixed(3), () => { this.el.auraFill.style.transform = `scaleX(${a})`; });
    this.set('auraReady', a >= 1, (on) => this.el.auraReady.classList.toggle('on', on));
  }
  dash(ready, visible) {
    this.set('dashV', visible, (v) => { this.el.dash.style.visibility = v ? 'visible' : 'hidden'; });
    this.set('dash', ready, (r) => this.el.dash.classList.toggle('empty', !r));
  }
  dashFlash() {
    this.el.dash.classList.remove('flash'); void this.el.dash.offsetWidth; this.el.dash.classList.add('flash');
    clearTimeout(this._df); this._df = setTimeout(() => this.el.dash.classList.remove('flash'), 220);
  }
  boss(on, frac = 1) {
    this.set('bossOn', on, (v) => this.el.boss.classList.toggle('hidden', !v));
    if (on) this.set('boss', frac.toFixed(3), () => {
      this.el.bossFill.style.transform = `scaleX(${Math.max(0, frac)})`;
      this.el.bossLag.style.transform = `scaleX(${Math.max(0, frac)})`;
    });
  }
  score(n, dt) {
    // count up rather than jump: a number that climbs is a number people watch
    this.scoreShown += (n - this.scoreShown) * Math.min(1, dt * 10);
    if (Math.abs(n - this.scoreShown) < 1) this.scoreShown = n;
    this.set('score', Math.round(this.scoreShown), (v) => { this.el.score.textContent = v.toLocaleString(); });
  }
  flow(mult, level, event) {
    this.set('flow', level, () => { this.el.flowMult.textContent = `x${mult.toFixed(1)}`; });
    this.set('flowHot', level >= 4, (v) => this.el.flow.classList.toggle('hot', v));
    if (event === 'up') this.kick(this.el.flow, 'bump', 120);
    if (event === 'break') this.kick(this.el.flow, 'broke', 320);
  }
  flowVisible(on) { this.set('flowVis', on, (v) => { this.el.flow.style.visibility = v ? 'visible' : 'hidden'; }); }
  kick(el, cls, ms) {
    el.classList.remove(cls); void el.offsetWidth; el.classList.add(cls);
    setTimeout(() => el.classList.remove(cls), ms);
  }
  beat(p, on) {
    this.set('beatOn', on, (v) => { if (!v) this.el.beat.style.opacity = 0; });
    if (!on) return;
    const s = 1 + p * 0.45;
    this.el.beat.style.opacity = (0.15 + p * 0.75).toFixed(2);
    this.el.beat.style.transform = `rotate(45deg) scale(${s.toFixed(3)})`;
  }
  crosshair(on) { this.set('xh', on, (v) => { this.el.crosshair.style.display = v ? 'block' : 'none'; }); }
  view(text) { this.set('view', text, (v) => { this.el.view.textContent = v; }); }
  lockHint(on) { this.set('lockhint', on, (v) => this.el.lockhint.classList.toggle('hidden', !v)); }

  pop(text, cls = '', ms = 900) {
    const d = document.createElement('div');
    d.className = 'pop ' + cls;
    d.textContent = text;
    this.el.popups.appendChild(d);
    setTimeout(() => d.remove(), ms);
    while (this.el.popups.children.length > 8) this.el.popups.firstChild.remove();
  }
  subtitle(text, secs = 2.5) {
    this.el.subtitle.textContent = text;
    this.el.subtitle.classList.add('on');
    clearTimeout(this._sub);
    this._sub = setTimeout(() => this.el.subtitle.classList.remove('on'), secs * 1000);
  }
  prompt(text) {
    this.set('prompt', text || '', (v) => {
      this.el.prompt.textContent = v;
      this.el.prompt.classList.toggle('on', !!v);
    });
  }
  promptHit() { this.kick(this.el.prompt, 'hit', 120); }
  // x,y in CSS px; k = 0..1 progress to the hit (outer ring collapses onto the inner)
  ring(on, x = 0, y = 0, k = 0) {
    this.set('ringOn', on, (v) => this.el.ring.classList.toggle('on', v));
    if (!on) return;
    this.el.ring.style.left = x + 'px';
    this.el.ring.style.top = y + 'px';
    const size = 70 + (1 - k) * 260;
    this.el.ringOuter.style.width = this.el.ringOuter.style.height = size + 'px';
  }
  titlecard() {
    const c = this.el.titlecard;
    c.classList.remove('on'); void c.offsetWidth; c.classList.add('on');
  }

  // ---- dojo timing strip. ms: signed offset (negative = early)
  timing(on) { this.el.timing.classList.toggle('hidden', !on); }
  timingZones(perfectEarly, perfectLate, goodEarly, span = 250) {
    const px = (ms) => 50 + (ms / span) * 50;
    const z = this.el.timing.querySelectorAll('.zone');
    z[0].style.left = px(-goodEarly) + '%'; z[0].style.width = (px(perfectLate) - px(-goodEarly)) + '%';
    z[1].style.left = px(-perfectEarly) + '%'; z[1].style.width = (px(perfectLate) - px(-perfectEarly)) + '%';
    this.span = span;
  }
  timingTick(ms, grade) {
    const span = this.span || 250;
    const i = document.createElement('i');
    i.className = grade;
    i.style.left = Math.max(0, Math.min(100, 50 + (ms / span) * 50)) + '%';
    this.el.ticks.appendChild(i);
    while (this.el.ticks.children.length > 24) this.el.ticks.firstChild.remove();
    [...this.el.ticks.children].forEach((c, k, a) => { c.style.opacity = (0.25 + 0.75 * (k + 1) / a.length).toFixed(2); });
    this.el.lastMs.textContent = ms == null ? 'NO PRESS' : `${ms > 0 ? '+' : ''}${Math.round(ms)} ms ${Math.abs(ms) < 8 ? '' : ms < 0 ? 'EARLY' : 'LATE'}`;
  }
  timingClear() { this.el.ticks.innerHTML = ''; this.el.lastMs.innerHTML = '&nbsp;'; }
  dojoStats(html) { this.set('dojoStats', html, (v) => { this.el.dojoStats.innerHTML = v; }); }
}
