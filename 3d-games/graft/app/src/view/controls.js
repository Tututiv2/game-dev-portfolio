// One thumb to walk, one to look. Keyboard and mouse work too, but the phone
// is the target: nothing here needs two hands or a key you have to find.

export function createControls(canvas, stick, knob) {
  const st = {
    forward: 0, strafe: 0, turn: 0, run: false,
    look: { yaw: 0, pitch: 0 },
    moving: false, running: false,
  };

  const keys = new Set();
  window.addEventListener('keydown', (e) => {
    keys.add(e.key.toLowerCase());
    if ([' ', 'w', 'a', 's', 'd'].includes(e.key.toLowerCase())) e.preventDefault();
  });
  window.addEventListener('keyup', (e) => keys.delete(e.key.toLowerCase()));
  window.addEventListener('blur', () => keys.clear());

  /* ---- left thumb: a stick that appears where you put your thumb down ---- */
  let moveId = null, ox = 0, oy = 0;
  const R = 52;

  function showStick(x, y) {
    stick.style.left = x + 'px';
    stick.style.top = y + 'px';
    stick.hidden = false;
    knob.style.transform = 'translate(-50%,-50%)';
  }

  canvas.addEventListener('pointerdown', (e) => {
    const half = canvas.clientWidth * 0.5;
    if (e.clientX < half && moveId === null) {
      moveId = e.pointerId; ox = e.clientX; oy = e.clientY;
      showStick(ox, oy);
      canvas.setPointerCapture(e.pointerId);
    } else if (lookId === null && e.clientX >= half) {
      lookId = e.pointerId; lx = e.clientX; ly = e.clientY; lmoved = 0;
    }
  });

  canvas.addEventListener('pointermove', (e) => {
    if (e.pointerId === moveId) {
      let dx = e.clientX - ox, dy = e.clientY - oy;
      const d = Math.hypot(dx, dy);
      if (d > R) { dx *= R / d; dy *= R / d; }
      knob.style.transform = `translate(calc(-50% + ${dx}px), calc(-50% + ${dy}px))`;
      st.strafe = dx / R;
      st.forward = -dy / R;
      st.running = d > R * 0.86;
    } else if (e.pointerId === lookId) {
      const dx = e.clientX - lx, dy = e.clientY - ly;
      lmoved += Math.abs(dx) + Math.abs(dy);
      st.look.yaw -= dx * 0.006;
      st.look.pitch = Math.max(-0.25, Math.min(1.25, st.look.pitch + dy * 0.004));
      lx = e.clientX; ly = e.clientY;
    }
  });

  function endMove(e) {
    if (e.pointerId !== moveId) return;
    moveId = null; stick.hidden = true;
    st.forward = 0; st.strafe = 0; st.running = false;
  }
  canvas.addEventListener('pointerup', endMove);
  canvas.addEventListener('pointercancel', endMove);

  /* ---- right thumb: look ------------------------------------------------ */
  let lookId = null, lx = 0, ly = 0, lmoved = 0;
  let onTap = null;
  canvas.addEventListener('pointerup', (e) => {
    if (e.pointerId !== lookId) return;
    lookId = null;
    if (lmoved < 10 && onTap) onTap(e.clientX, e.clientY);   // that was a tap
  });
  canvas.addEventListener('pointercancel', (e) => { if (e.pointerId === lookId) lookId = null; });

  canvas.addEventListener('wheel', (e) => {
    st.look.pitch = Math.max(-0.25, Math.min(1.25, st.look.pitch + Math.sign(e.deltaY) * 0.06));
  }, { passive: true });

  /** Read the current intent. Keyboard is folded in here so both work at once. */
  function poll() {
    let f = st.forward, s = st.strafe, run = st.running;
    if (keys.has('w') || keys.has('arrowup')) f = 1;
    if (keys.has('s') || keys.has('arrowdown')) f = -1;
    if (keys.has('a')) s = -1;
    if (keys.has('d')) s = 1;
    if (keys.has('shift')) run = true;
    let turn = 0;
    if (keys.has('arrowleft')) turn = 1;
    if (keys.has('arrowright')) turn = -1;
    st.moving = Math.abs(f) > 0.06 || Math.abs(s) > 0.06;
    return { forward: f, strafe: s, turn, run, moving: st.moving, running: run && st.moving };
  }

  return {
    poll,
    look: st.look,
    setTap(fn) { onTap = fn; },
    keys,
  };
}
