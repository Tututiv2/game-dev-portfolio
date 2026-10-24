/* ============================================================================
   STANDING ON IT — the only view that can judge the detail layer.

   Every other shooting harness here looks at a world from outside. B1e is
   about the band between a boot and a kilometre, and none of that band is
   visible from orbit: one baked texel is 1.2 km, so from up there the whole
   question is invisible by construction.

   This drops the camera onto the surface and looks at the horizon.

     node tests/groundshot.mjs
     node tests/groundshot.mjs --arch=glacier --seed=VESPER-1 --night=1

   NIGHT MATTERS. refs/SURFACE.md: both of the owner's reference images are the
   NIGHT SIDE, so the sun is doing none of the work and what carries the picture
   is emissive ground and sky. A daylit test render will look fine and prove
   nothing — so this shoots both, side by side, and the night one is the one to
   judge the art against.

   Real GPU (ANGLE), and ?time=9 so two runs are comparable: the clock drives
   the glow pulse and the clouds, and an unpinned clock makes every shot differ
   from the last for reasons that have nothing to do with the change.
   ========================================================================= */
import puppeteer from 'puppeteer-core';
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'shots', 'ground');
const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));
fs.mkdirSync(OUT, { recursive: true });

const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new',
  args: ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist'],
  defaultViewport: { width: 1280, height: 720 }, protocolTimeout: 300000,
});
const page = await browser.newPage();
page.on('pageerror', e => console.log('  PAGEERROR: ' + e.message));
page.on('console', m => { if (m.type() === 'error') console.log('  ERR ' + m.text().slice(0, 300)); });

/* THE ALTITUDE HAS TO BE SOLVED FOR, NOT SET.
   `dist` is a multiple of the planet RADIUS — i.e. of the DATUM — and the
   ground is only sometimes near the datum. Measured on VESPER-1 glacier: the
   ground under the camera sits 2589 m BELOW the datum, so ?dist=1.0 leaves you
   floating 2.6 km up, and ?dist=1.002 (which CLAUDE.md documents as "stand on
   it") leaves you at 5.7 km. The viewer's floor clamp only catches the opposite
   case, where the ground is above the datum.

   So: place the camera, ask groundR() where the ground actually is along that
   direction, and set dist = (ground + eye)/radius.

   EYE HEIGHT drives everything downstream. depthForAltitude() targets a chunk
   about 1.2x the altitude, so a vertex lands near alt/27 — at 120 m the mesh
   has ~4.5 m vertices and the finest detail octave (3.5 m) is just visible;
   the same 5x5 grid then covers only ~360 m before the orbital sphere takes
   over, which is the missing quadtree showing through and is not hidden here
   on purpose.

   LOOK ANGLE. -0.15 rad aims at the horizon, which is what you want to judge
   now that the tree actually reaches it. Before the quadtree this shot had to
   be taken looking ~30 degrees DOWN, because the single-LOD grid covered 300 m
   against an 8.6 km horizon and a level shot came back as a sliver of ground in
   an empty frame. If a horizon shot ever goes empty again, that is the tree
   failing to reach — check tests/quadtree.mjs before touching the art. */
const EYE = Number(argv.eye || 120);
const LOOK = argv.look || '-0.15';
await page.goto(pathToFileURL(path.join(ROOT, 'VESPER.html')).href
  + `?pdb=1&time=9&spin=0&look=${LOOK}`,
  { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForFunction('!!window.__V && window.__V.frames >= 5', { timeout: 300000 });

const WORLDS = argv.arch
  ? [[argv.seed || 'VESPER-1', argv.arch]]
  : [['VESPER-1', 'glacier'], ['VESPER-1', 'volcanic'],
     ['ASHFALL', 'crystal'], ['NORTHWIND', 'tundra']];

/* THE SUN ANGLE IS SOLVED FOR, LIKE THE ALTITUDE, AND FOR THE SAME REASON.
   These are the sun's elevation ABOVE THE CAMERA'S OWN HORIZON, in degrees —
   what the player experiences — not the world-frame number `setSun` takes.

   The first version passed `setSun(0.9, -0.75)` and called the result "night".
   It was not. `setSun` sets a direction in WORLD space, and whether that is
   night where you are standing depends on where you are standing; once this
   harness started aiming itself at a caldera the camera moved a long way. The
   frame labelled volcanic-night had the sun at **+17 degrees** — broad daylight.
   Every art judgement made against those shots was judging sunlight, on a
   project whose art direction says explicitly that both reference images are the
   night side and that a daylit render "will look fine and prove nothing".

   Caught by an adversarial reviewer reading the harness rather than the picture.
   The lesson is the one already in DECISIONS: an instrument that cannot see a
   class of input will confidently exonerate it. */
const LIGHTS = [['day', 35], ['night', -12]];

console.log('\n  standing on the surface — 1280x720, real GPU\n');
for (const [seed, arch] of WORLDS) {
  for (const [lname, sunEl] of LIGHTS) {
    if (argv.night && lname === 'day') continue;
    await page.evaluate((s, a, eye, aim) => {
      const V = window.__V;
      V.setWorld(s, a);
      V.setCam(1.0, 0.55, 0.05);

      /* STAND SOMEWHERE THE WORLD IS ALIVE.
         An arbitrary point on a volcanic world is DEAD BLACK ROCK, and correctly
         so — the art law is that the cracks concentrate into blazing regions with
         dead rock between them, which is what makes them read as fire instead of
         as texture. So a ground shot taken wherever the default camera happened
         to point came back at 35 distinct colours and looked like a bug in the
         renderer. It was a bug in the harness.

         Calderas (feature kind 1) are where a world puts its light. Sweep the
         camera azimuth/elevation, convert each to the direction it would
         actually look from, and keep whichever lands nearest one. */
      if (aim && V.def.features) {
        const target = V.def.features.find(f => f.kind === 1)
                    || V.def.features.find(f => f.kind === 2)
                    || V.def.features[0];
        if (target) {
          let best = null;
          for (let ai = 0; ai < 48; ai++) {
            for (let ei = -5; ei <= 5; ei++) {
              const az = (ai / 48) * Math.PI * 2, elc = ei * 0.22;
              V.setCam(null, az, elc);
              V.state.dist = 1.0;
              // one frame so the camera transform is real, then read it back
              const p = V.camera.position;
              const dir = new V.T.Vector3(
                Math.cos(elc) * Math.sin(az), Math.sin(elc), Math.cos(elc) * Math.cos(az));
              const o = V.planet.group.worldToLocal(dir.multiplyScalar(V.def.radius)).normalize();
              const d = o.x * target.x + o.y * target.y + o.z * target.z;
              if (!best || d > best.d) best = { d, az, el: elc };
            }
          }
          V.setCam(1.0, best.az, best.el);
        }
      }
      V.frames = 0;
    }, seed, arch, EYE, argv.aim !== '0');
    // one frame so the camera is actually placed, then solve for the altitude
    await page.waitForFunction('window.__V.frames >= 2', { timeout: 120000 });
    await page.evaluate((eye, targetDeg, dbg, seam, detail) => {
      const V = window.__V, P = V.planet, d = V.def;
      const c = P.group.worldToLocal(V.camera.position.clone());
      const R = Math.hypot(c.x, c.y, c.z);
      const gR = V.groundR(d, [c.x / R, c.y / R, c.z / R]);
      V.setCam((gR + eye) / d.radius, null, null);
      /* Stream the tree in fast. The shipping budget is a few chunks a frame so
         that flying does not stutter; a screenshot has nothing else to do, and
         waiting 150 frames for the horizon to fill in is 150 frames of a
         half-built world in the shot. */
      if (P.terrain) { P.terrain.budget = 32; P.terrain.clear(); }
      /* --dbg=N writes a lighting INPUT straight out instead of the shaded
         result (1 = mesh normal). Some worlds cannot be judged any other way:
         volcanic's albedo is near-black by design, so its terrain SHAPE is
         invisible in every light, and a shot of it comes back at 68 distinct
         colours whatever the geometry underneath is doing. The probe separates
         "what shape is the ground" from "what is it made of". */
      if (dbg > 0) V.setUniform('uTerrDbg', dbg);
      /* --seam=N overrides uSeamGlow, how much of the baked emissive is moved
         into the concave seams. --seam=0 turns the term off entirely, which is
         how you tell a SHADING artefact on a terrace riser from a GEOMETRY one
         (the mesh-normal probe above answers the other half). */
      if (seam !== null) V.setUniform('uSeamGlow', seam);
      // --detail=N overrides uEmisDetail, the ground-scale crack network.
      // --detail=0 is exactly the behaviour before it existed.
      if (detail !== null) V.setUniform('uEmisDetail', detail);

      /* SOLVE THE SUN. orbit.js builds the star direction in WORLD space from
         (state.az + state.sunAz, state.sunEl); the camera has its own up. So to
         put the sun at a chosen elevation above THIS camera's horizon there is
         no closed form worth writing — sweep sunEl and keep the best. 400 steps
         over the full range lands within a quarter of a degree, and it costs
         nothing because it is pure arithmetic. */
      const up = V.camera.position.clone().normalize();
      const az = V.state.az, sunAz = 0.9;
      const want = Math.sin(targetDeg * Math.PI / 180);
      let best = null;
      for (let i = 0; i <= 400; i++) {
        const se = -Math.PI / 2 + (i / 400) * Math.PI;
        const sx = Math.cos(se) * Math.sin(az + sunAz);
        const sy = Math.sin(se);
        const sz = Math.cos(se) * Math.cos(az + sunAz);
        const dot = sx * up.x + sy * up.y + sz * up.z;
        const err = Math.abs(dot - want);
        if (!best || err < best.err) best = { err, se };
      }
      V.setSun(sunAz, best.se);
      V.frames = 0;
    }, EYE, sunEl, Number(argv.dbg || 0), argv.seam === undefined ? null : Number(argv.seam),
      argv.detail === undefined ? null : Number(argv.detail));
    // the chunk budget streams terrain in a couple of chunks per update, so a
    // shot taken too early is a shot of the orbital sphere with holes in it
    // enough frames for the widened grid to finish streaming in at 24/frame
    await page.waitForFunction('window.__V.frames >= 400', { timeout: 600000 });
    await new Promise(r => setTimeout(r, 500));

    /* Report the altitude, the VERTEX SPACING, and — the one that turned out to
       matter most — WHETHER THE SUN IS ACTUALLY BELOW THE HORIZON.

       `setSun(az, el)` sets a direction in WORLD space. Whether that is night
       where the CAMERA is standing depends on where the camera is, and once this
       harness started aiming itself at a caldera the camera moved a long way.
       So a shot labelled "night" was not reliably night, and any art judgement
       made against it was judging sunlight. Print the sun's elevation above the
       local horizon and the shot interprets itself — the same fix as printing
       the vertex spacing instead of the LOD depth. */
    const info = await page.evaluate(() => {
      const V = window.__V, P = V.planet, t = P?.terrain;
      const camObj = P.group.worldToLocal(V.camera.position.clone());
      const R = Math.hypot(camObj.x, camObj.y, camObj.z);
      const dir = [camObj.x / R, camObj.y / R, camObj.z / R];
      const edge = t && t.depth >= 0 ? t.edgeAt(t.depth) : 0;
      // the sun as the PLAYER experiences it: its elevation above the horizon
      // at the camera's own feet, not its elevation in some world frame
      const sun = P.uniforms.uSun.value;
      const sunUp = sun.x * dir[0] + sun.y * dir[1] + sun.z * dir[2];
      return {
        fps: Math.round(V.fps || 0),
        chunks: t?.chunks?.size ?? 0,
        depth: t?.depth ?? -1,
        alt: R - V.groundR(V.def, dir),
        spacing: edge / 32,
        cover: t ? t.coverage() : 0,
        sunDeg: Math.asin(Math.max(-1, Math.min(1, sunUp))) * 180 / Math.PI,
      };
    });
    /* FLATNESS, AS A NUMBER.
       "It looks featureless" is an impression and impressions do not survive
       into a commit message. This reads the framebuffer back and reports how
       many distinct colours are in it and the per-channel spread. A ground
       shot that comes back with a handful of colours is not a lit surface, it
       is a constant — which is exactly what a normal fetched from a 1.2 km
       texel gives you across a 1 km view. */
    const stats = await page.evaluate(() => {
      const cv = document.getElementById('cv');
      const g = document.createElement('canvas');
      g.width = cv.width; g.height = cv.height;
      g.getContext('2d').drawImage(cv, 0, 0);
      const px = g.getContext('2d').getImageData(0, 0, g.width, g.height).data;
      const seen = new Set();
      let n = 0, s = 0, s2 = 0;
      for (let i = 0; i < px.length; i += 4) {
        seen.add((px[i] << 16) | (px[i + 1] << 8) | px[i + 2]);
        const l = 0.2126 * px[i] + 0.7152 * px[i + 1] + 0.0722 * px[i + 2];
        n++; s += l; s2 += l * l;
      }
      return { colours: seen.size, mean: s / n, sd: Math.sqrt(Math.max(0, s2 / n - (s / n) ** 2)) };
    });
    const d = await page.evaluate(() => document.getElementById('cv').toDataURL('image/png'));
    const file = path.join(OUT, `${arch}-${lname}.png`);
    fs.writeFileSync(file, Buffer.from(d.split(',')[1], 'base64'));
    console.log(`    ${arch.padEnd(9)} ${lname.padEnd(6)} ${String(info.fps).padStart(3)} fps` +
                `  eye ${info.alt.toFixed(0).padStart(4)} m` +
                `  depth ${String(info.depth).padStart(2)}` +
                `  vertex ${info.spacing.toFixed(1).padStart(6)} m` +
                `  grid ${(info.cover / 1000).toFixed(2)} km` +
                `  sun ${info.sunDeg >= 0 ? '+' : ''}${info.sunDeg.toFixed(0).padStart(3)}deg` +
                `${info.sunDeg < 0 ? ' NIGHT' : ' DAY  '}` +
                `  colours ${String(stats.colours).padStart(6)}` +
                `  sd ${stats.sd.toFixed(2).padStart(6)}` +
                `  -> ${arch}-${lname}.png`);
  }
}

await browser.close();
console.log('');
