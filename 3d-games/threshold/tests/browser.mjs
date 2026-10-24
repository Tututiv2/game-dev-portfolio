// The whole suite in the browser: the pure-logic checks that also run in node, plus the spectral
// ones that need a real Web Audio implementation.
//
// Results go both to the page (for a human) and to window.__TEST_RESULT (for tools/headless.mjs).

import { report, reset } from './harness.mjs';
import { run as runSynth } from './synth.test.mjs';
import { run as runSolver } from './solver.test.mjs';
import { run as runAcoustics } from './acoustics.test.mjs';
import { run as runContinuity } from './continuity.test.mjs';
import { run as runSpectral } from './spectral.test.mjs';

const CSS = `
  body { margin:0; background:#0a0e14; color:#c7d8e4;
    font:13px/1.6 ui-monospace,Consolas,"SF Mono",monospace; padding:26px 30px 60px; }
  h1 { font-size:17px; letter-spacing:.24em; color:#eef6fb; margin:0 0 2px; font-weight:600; }
  .sub { color:#6e8ba0; margin:0 0 22px; font-size:12px; }
  h2 { font-size:11px; letter-spacing:.10em; text-transform:uppercase; color:#6f93ab;
    margin:22px 0 6px; font-weight:600; border-bottom:1px solid rgba(140,180,210,.14);
    padding-bottom:4px; }
  .c { display:flex; gap:10px; padding:1px 0; }
  .tag { flex:0 0 42px; font-weight:600; }
  .pass .tag { color:#63c08a; }
  .fail .tag { color:#ff6f6f; }
  .fail .lbl { color:#ffc7c7; }
  .lbl { color:#c7d8e4; }
  .detail { color:#8aa2b4; margin-left:52px; font-size:12px; padding-bottom:3px; }
  .total { margin-top:26px; padding:12px 16px; border-radius:6px; font-size:14px;
    background:rgba(99,192,138,.10); border:1px solid rgba(99,192,138,.35); color:#a8e6c3; }
  .total.bad { background:rgba(255,111,111,.10); border-color:rgba(255,111,111,.4); color:#ffb3b3; }
  .running { color:#ffc078; }
`;

export async function run() {
  document.title = 'THRESHOLD — tests';
  const style = document.createElement('style');
  style.textContent = CSS;
  document.head.appendChild(style);
  document.body.innerHTML =
    '<h1>THRESHOLD</h1><p class="sub">verification suite &mdash; ' +
    'pure-logic checks plus real rendered audio</p><p class="running">rendering audio…</p>';

  reset();
  runSynth();
  runSolver();
  runAcoustics();
  runContinuity();
  try {
    await runSpectral();
  } catch (e) {
    const g = report();
    void g;
    document.body.insertAdjacentHTML('beforeend',
      `<p class="fail">spectral suite threw: ${String(e && e.stack || e)}</p>`);
  }

  const r = report();
  let html = '<h1>THRESHOLD</h1><p class="sub">verification suite &mdash; ' +
    'pure-logic checks plus real rendered audio</p>';
  for (const g of r.groups) {
    html += `<h2>${g.name}</h2>`;
    for (const c of g.checks) {
      html += `<div class="c ${c.ok ? 'pass' : 'fail'}"><span class="tag">${c.ok ? 'PASS' : 'FAIL'}</span>`
        + `<span class="lbl">${c.label}</span></div>`;
      if (!c.ok) html += `<div class="detail">${c.detail}</div>`;
    }
  }
  html += `<div class="total ${r.fail ? 'bad' : ''}">${r.pass}/${r.pass + r.fail} checks passed`
    + `${r.fail ? ` — ${r.fail} FAILED` : ''}</div>`;
  document.body.innerHTML = html;

  const summary = {
    pass: r.pass,
    fail: r.fail,
    failures: r.groups.flatMap(g => g.checks.filter(c => !c.ok)
      .map(c => ({ group: g.name, label: c.label, detail: c.detail }))),
    // Every check with its measured note, not only the ones that failed. The interesting question
    // after changing the synth is "what are the numbers NOW", and a bare pass count cannot answer
    // it. `node tools/headless.mjs --all` prints these.
    groups: r.groups.map(g => ({
      name: g.name,
      checks: g.checks.map(c => ({ ok: c.ok, label: c.label, detail: c.detail })),
    })),
  };
  globalThis.__TEST_RESULT = summary;
  console.log(r.text);
  return summary;
}
