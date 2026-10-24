/* ============================================================================
   BOOT — paint first, detect the renderer, never show a white screen.

   THE FAILURE THIS EXISTS TO KILL:
   Building the scene and compiling these shaders blocks the main thread for a
   while. A browser that has not painted yet shows WHITE, so a slow start and a
   hard crash look identical from the outside — and the only report you get is
   "it stays white", which says nothing about which one it was.

   Three guarantees:
     1. The dark curtain in orbit.html is painted BEFORE any WebGL work begins,
        because we yield to the browser twice before touching the GPU.
     2. If anything throws, the error goes ON THE SCREEN, in full, so it can be
        read out rather than guessed at.
     3. If the renderer turns out to be a SOFTWARE one (hardware acceleration
        off, or a driver fallback), we say so plainly and drop the quality dial
        instead of grinding at 25 seconds a frame, which is itself a white screen.
   ========================================================================= */

const el = (id) => document.getElementById(id);

/** wait for the browser to actually paint, not just for a timer to fire */
export function painted() {
  return new Promise((res) => requestAnimationFrame(() => requestAnimationFrame(
    () => setTimeout(res, 0))));
}

export function bootMsg(msg, hint) {
  const m = el('bootMsg'), h = el('bootHint');
  if (m) m.textContent = msg;
  if (h && hint !== undefined) h.innerHTML = hint;
}

export function bootNote(html) {
  const n = el('bootNote');
  if (n) n.innerHTML = html || '';
}

/* ---------------------------------------------------------------------------
   HAS THIS MACHINE EVER RUN IT?

   The first launch on a given computer spends about half a minute inside the
   shader compiler — ANGLE translating the whole noise library to HLSL. Chrome
   caches the compiled program on disk, so every launch after that is under two
   seconds (tests/boottime.mjs: 28 s cold, 1.7 s warm).

   That is fine, and it is completely unacceptable to not SAY so: half a minute
   of an unexplained dark screen is indistinguishable from the thing being
   broken, which is the report this project has already had. So the first run
   gets an honest warning and every run after gets nothing.

   file:// has no reliable storage, so this is best-effort and must never throw.
   ------------------------------------------------------------------------ */
const SEEN_KEY = 'vesper.compiled.v1';
export function isFirstRun() {
  try { return !localStorage.getItem(SEEN_KEY); } catch { return false; }
}
export function markRun() {
  try { localStorage.setItem(SEEN_KEY, String(Date.now())); } catch { /* file:// */ }
}

export const FIRST_RUN_NOTE =
  '<b>First time on this computer.</b> Your browser is compiling the renderer, ' +
  'which takes about half a minute. It is saved afterwards — every launch after ' +
  'this one opens in about a second.<br>The bar keeps moving while it works. ' +
  'If it is still here after two minutes, something is actually wrong.';

export function bootDone() {
  const b = el('boot');
  if (b) { b.classList.add('gone'); setTimeout(() => { b.style.display = 'none'; }, 700); }
}

export function bootError(err, extra = '') {
  const b = el('boot');
  if (!b) return;
  b.style.display = 'flex';
  b.classList.remove('gone');
  b.classList.add('err');
  el('bootTitle').textContent = 'STUCK';
  el('bootMsg').textContent = 'this is a bug, not your machine';
  el('bootHint').innerHTML =
    'Send this text to Claude and it can be fixed exactly.' + extra +
    '<pre>' + String(err && err.stack || err).replace(/[<>&]/g, c =>
      ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c])) + '</pre>';
  window.__V = window.__V || {};
  window.__V.bootError = String(err && err.message || err);
}

/**
 * Ask the GPU what it actually is, cheaply, on a throwaway canvas — before
 * committing to building anything.
 */
export function probeRenderer() {
  try {
    const c = document.createElement('canvas');
    const gl = c.getContext('webgl2') || c.getContext('webgl');
    if (!gl) return { ok: false, name: 'none', software: true, reason: 'no WebGL at all' };
    const dbg = gl.getExtension('WEBGL_debug_renderer_info');
    const name = dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL)
                     : gl.getParameter(gl.RENDERER);
    const software = /swiftshader|software|llvmpipe|basic render|microsoft basic/i.test(name);
    return { ok: true, name, software, webgl2: !!c.getContext('webgl2') };
  } catch (e) {
    return { ok: false, name: 'error', software: true, reason: String(e.message) };
  }
}

export const SOFTWARE_HELP =
  '<br><b>Your graphics card is not being used.</b> Chrome has fallen back to ' +
  'drawing this with the processor, which is hundreds of times slower — a single ' +
  'frame can take half a minute, so the screen looks frozen.<br><br>' +
  'Open <code>chrome://gpu</code> in a new tab. If the top lines say ' +
  '<b>Software only</b>, go to <code>chrome://settings/system</code> and turn on ' +
  '<b>Use graphics acceleration when available</b>, then restart Chrome.<br><br>' +
  'Running anyway at reduced quality so you can still see something.';
