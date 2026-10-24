// Lightweight flow-stream particles. Producers emit motes that drift toward the
// Rift (grid center), conveying "flow" without a full edge engine yet.

export function createParticleSystem() {
  return { particles: [], maxParticles: 600 };
}

// Spawn a mote at world position heading toward a target.
export function spawnMote(ps, x, y, tx, ty, color) {
  if (ps.particles.length >= ps.maxParticles) return;
  const dx = tx - x;
  const dy = ty - y;
  const dist = Math.hypot(dx, dy) || 1;
  const speed = 70 + Math.random() * 40; // px/s
  ps.particles.push({
    x, y,
    vx: (dx / dist) * speed,
    vy: (dy / dist) * speed,
    life: 0,
    maxLife: dist / speed,
    color,
    size: 1.6 + Math.random() * 1.6,
  });
}

export function updateParticles(ps, dt) {
  const arr = ps.particles;
  for (let i = arr.length - 1; i >= 0; i--) {
    const p = arr[i];
    p.life += dt;
    p.x += p.vx * dt;
    p.y += p.vy * dt;
    if (p.life >= p.maxLife) arr.splice(i, 1);
  }
}

// Draw motes in WORLD space (caller has already applied camera transform).
// Additive blending gives a cheap neon-bloom look (halo + bright core).
export function drawParticles(ctx, ps) {
  if (ps.particles.length === 0) return;
  const prev = ctx.globalCompositeOperation;
  ctx.globalCompositeOperation = "lighter";
  for (const p of ps.particles) {
    const t = p.life / p.maxLife;
    const alpha = Math.sin(t * Math.PI); // fade in/out
    // halo
    ctx.globalAlpha = alpha * 0.35;
    ctx.fillStyle = p.color;
    ctx.beginPath();
    ctx.arc(p.x, p.y, p.size * 2.6, 0, Math.PI * 2);
    ctx.fill();
    // bright core
    ctx.globalAlpha = alpha;
    ctx.fillStyle = "#ffffff";
    ctx.beginPath();
    ctx.arc(p.x, p.y, p.size * 0.7, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.globalCompositeOperation = prev;
  ctx.globalAlpha = 1;
}
