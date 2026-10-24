// Keyboard + mouse. Presses are stamped on the AUDIO clock the moment the event
// arrives, because every skill check is graded against the song, not the frame.

export class Input {
  constructor(canvas, clock) {
    this.canvas = canvas;
    this.clock = clock;          // () => seconds on the audio clock (or perf clock before audio)
    this.keys = new Set();
    this.queue = [];             // [{type, t}] action presses, drained by the game each frame
    this.moves = [];             // [{t, dx, dy}] pointer deltas for flick reading (last ~0.5 s)
    this.dx = 0; this.dy = 0;    // accumulated look delta since last frame
    this.locked = false;
    this.enabled = true;
    this.onLockChange = null;

    addEventListener('keydown', (e) => {
      if (e.repeat) { if (!['KeyW', 'KeyA', 'KeyS', 'KeyD'].includes(e.code)) e.preventDefault(); return; }
      if (['Space', 'Tab', 'ShiftLeft', 'ShiftRight', 'KeyQ', 'KeyF'].includes(e.code)) e.preventDefault();
      this.keys.add(e.code);
      const t = this.clock();
      const map = {
        Space: 'jump', ShiftLeft: 'dash', ShiftRight: 'dash', KeyQ: 'release', KeyF: 'parry',
        KeyV: 'view', Tab: 'panel', Enter: 'skip', KeyR: 'restart', Escape: 'escape', KeyE: 'parry',
      };
      if (map[e.code] && this.enabled) this.queue.push({ type: map[e.code], t });
    });
    addEventListener('keyup', (e) => this.keys.delete(e.code));
    addEventListener('blur', () => this.keys.clear());

    canvas.addEventListener('mousedown', (e) => {
      if (!this.locked) return;
      e.preventDefault();
      const t = this.clock();
      if (e.button === 0) this.queue.push({ type: 'attack', t });
      if (e.button === 2) this.queue.push({ type: 'parry', t });
    });
    canvas.addEventListener('contextmenu', (e) => e.preventDefault());
    addEventListener('mousemove', (e) => {
      if (!this.locked) return;
      this.dx += e.movementX;
      this.dy += e.movementY;
      this.moves.push({ t: this.clock(), dx: e.movementX, dy: e.movementY });
      if (this.moves.length > 256) this.moves.splice(0, 64);
    });
    document.addEventListener('pointerlockchange', () => {
      this.locked = document.pointerLockElement === canvas;
      this.keys.clear();
      if (this.onLockChange) this.onLockChange(this.locked);
    });
  }

  lock() {
    try {
      const p = this.canvas.requestPointerLock({ unadjustedMovement: true });
      // unadjustedMovement (raw input) is not supported everywhere; fall back.
      if (p && p.catch) p.catch(() => { try { this.canvas.requestPointerLock(); } catch {} });
    } catch { try { this.canvas.requestPointerLock(); } catch {} }
  }
  unlock() { if (document.pointerLockElement) document.exitPointerLock(); }

  down(code) { return this.keys.has(code); }
  axis() {
    let x = 0, z = 0;
    if (this.keys.has('KeyW')) z += 1;
    if (this.keys.has('KeyS')) z -= 1;
    if (this.keys.has('KeyD')) x += 1;
    if (this.keys.has('KeyA')) x -= 1;
    const m = Math.hypot(x, z);
    return m > 0 ? { x: x / m, z: z / m } : { x: 0, z: 0 };
  }
  drain() { const q = this.queue; this.queue = []; return q; }
  look() { const r = { dx: this.dx, dy: this.dy }; this.dx = 0; this.dy = 0; return r; }
  recentMoves(now, span = 0.5) {
    // keep the buffer short; readFlick only ever looks ~0.1 s either side of a press
    while (this.moves.length && this.moves[0].t < now - span) this.moves.shift();
    return this.moves;
  }
}
