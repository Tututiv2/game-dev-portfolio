// Tiny test harness. No dependencies — it runs in node and in the browser unchanged, which is the
// whole reason the solver and the acoustic maths have no DOM or Web Audio anywhere in them.

const groups = [];
let current = null;

export function group(name) {
  current = { name, checks: [] };
  groups.push(current);
}

function record(ok, label, detail) {
  if (!current) group('ungrouped');
  current.checks.push({ ok, label, detail });
  return ok;
}

// Every assertion takes an optional trailing `note` that is appended to the failure detail. Say
// what the number MEANS, not just what it was — a bare "got 3.607" sends you hunting for which of
// forty parameters moved.
const join = (base, note) => (note ? `${base}   (${note})` : base);

export function check(label, ok, detail = '') {
  return record(!!ok, label, detail);
}

export function eq(label, actual, expected, note = '') {
  const ok = actual === expected;
  return record(ok, label, join(`expected ${fmt(expected)}, got ${fmt(actual)}`, note));
}

export function near(label, actual, expected, tol, note = '') {
  const ok = Math.abs(actual - expected) <= tol;
  return record(ok, label, join(`expected ${fmt(expected)} ±${tol}, got ${fmt(actual)}`, note));
}

export function gt(label, actual, bound, note = '') {
  const ok = actual > bound;
  return record(ok, label, join(`expected > ${fmt(bound)}, got ${fmt(actual)}`, note));
}

export function lt(label, actual, bound, note = '') {
  const ok = actual < bound;
  return record(ok, label, join(`expected < ${fmt(bound)}, got ${fmt(actual)}`, note));
}

export function between(label, actual, lo, hi, note = '') {
  const ok = actual >= lo && actual <= hi;
  return record(ok, label, join(`expected ${fmt(lo)}..${fmt(hi)}, got ${fmt(actual)}`, note));
}

function fmt(v) {
  if (typeof v === 'number') return Number.isInteger(v) ? String(v) : v.toFixed(3);
  return String(v);
}

export function report() {
  let pass = 0, fail = 0;
  const lines = [];
  for (const g of groups) {
    lines.push(`\n  ${g.name}`);
    for (const c of g.checks) {
      if (c.ok) { pass++; lines.push(`    PASS  ${c.label}`); }
      else { fail++; lines.push(`    FAIL  ${c.label}\n            ${c.detail}`); }
    }
  }
  lines.push(`\n  ${pass}/${pass + fail} checks passed${fail ? `  — ${fail} FAILED` : ''}\n`);
  return { pass, fail, text: lines.join('\n'), groups };
}

export function reset() {
  groups.length = 0;
  current = null;
}
