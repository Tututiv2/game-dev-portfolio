// Keyboard. Held-state, rebindable, and it records what it was given so §15's replay can
// play it back exactly.

export const DEFAULT_BINDS = {
  left: ['ArrowLeft', 'KeyA'],
  right: ['ArrowRight', 'KeyD'],
  thrust: ['Space', 'ArrowUp', 'KeyW'],
  confirm: ['Enter', 'Space'],
  back: ['Escape'],
  restart: ['KeyR'],
};

export function createInput(target, binds = DEFAULT_BINDS) {
  const down = new Set();
  const pressedThisFrame = new Set();
  const listeners = [];
  let enabled = true;

  const onDown = (e) => {
    if (e.repeat) return;
    down.add(e.code);
    pressedThisFrame.add(e.code);
    for (const fn of listeners) fn(e.code, e);
    // Space and the arrows scroll the page, which is very obviously wrong in a game.
    if (['Space', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.code)) e.preventDefault();
  };
  const onUp = (e) => down.delete(e.code);
  const onBlur = () => down.clear();   // held keys must not stick through an alt-tab

  target.addEventListener('keydown', onDown);
  target.addEventListener('keyup', onUp);
  window.addEventListener('blur', onBlur);

  const held = (action) => enabled && (binds[action] || []).some((c) => down.has(c));

  return {
    binds,
    held,
    isDown: (code) => down.has(code),
    /** The shape sim/physics.js wants. Read once per frame, fed to every substep. */
    sample() { return { left: held('left'), right: held('right'), thrust: held('thrust') }; },
    pressed(action) { return (binds[action] || []).some((c) => pressedThisFrame.has(c)); },
    onKey(fn) { listeners.push(fn); return () => listeners.splice(listeners.indexOf(fn), 1); },
    endFrame() { pressedThisFrame.clear(); },
    setEnabled(v) { enabled = v; if (!v) down.clear(); },
    destroy() {
      target.removeEventListener('keydown', onDown);
      target.removeEventListener('keyup', onUp);
      window.removeEventListener('blur', onBlur);
    },
  };
}

/**
 * Replay source (§15). Feeds a recorded input stream back a step at a time. Determinism
 * (test 9) is what makes this exact rather than approximate.
 */
export function createReplay(frames) {
  let i = 0;
  return {
    sample() { return frames[Math.min(i, frames.length - 1)] || { left: false, right: false, thrust: false }; },
    advance() { i++; },
    get done() { return i >= frames.length; },
    reset() { i = 0; },
  };
}

/** Packs one step of input into a byte, so a 60 s run records as ~7 KB rather than JSON. */
export function packInput(c) { return (c.left ? 1 : 0) | (c.right ? 2 : 0) | (c.thrust ? 4 : 0); }
export function unpackInput(b) { return { left: !!(b & 1), right: !!(b & 2), thrust: !!(b & 4) }; }
