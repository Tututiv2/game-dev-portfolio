# AURA — module contract

Three builders work in parallel against this file. **Do not change a signature
here without saying so in your final report.** `web/game.js` (the loop, input,
camera, combat, story, dojo) is written against exactly these shapes.

## Ground rules (all modules)
- Vanilla JS ES modules, no build step, no npm. three.js r171 is vendored:
  `import * as THREE from './vendor/three/three.module.js';` (path relative to `web/`;
  from `web/audio/` there is no three.js — audio never needs it).
- **Not one asset file.** Every mesh built from primitives in code, every texture
  painted in code, every sound synthesised. (User mp3s in `music/` are the only exception.)
- **Write source with the editor tool, never through a shell heredoc** — this
  portfolio has had four control-character-in-source bugs from heredocs eating escapes.
- World: metres, **Y up**, **yaw 0 faces +Z**, yaw increases turning toward +X
  (i.e. forward = (sin yaw, 0, cos yaw)).
- `web/sim/` is pure (no DOM/three/audio). Don't touch it; import from it freely.
- Look and feel reference (the owner's own example, an "aura edit" reel): black
  frames, **electric cyan-blue glow (#4fdcff-ish) with a white-hot core**, macro
  shots of energy surging through veins/tubes, a single black-and-white frame on
  the beat, then a white-out. Anime cel shading with black outlines for characters.

---

## 1. Audio — `web/audio/engine.js` (+ `phonk.js`, `sfx.js`)

```js
export class AudioEngine {
  async init()                 // call from a user gesture. Creates ctx + buses, starts ambient bed at 0.
  get ctx()                    // the AudioContext
  get now()                    // ctx.currentTime
  get outputLatency()          // seconds (ctx.outputLatency || ctx.baseLatency || 0)
  setVolumes({ master, music, sfx })   // 0..1 each, any subset
  setAmbient(level, ramp = 1)  // wind + crickets + leaves bed, 0..1. Quiet dusk = ~0.6
  setMuffle(amount, ramp = .3) // 0 clear .. 1 knocked-down: world lowpassed hard + faint tinnitus ring
  setSlowmo(rate)              // 0.15..1. Subsequent SFX pitched/stretched by it. Music NEVER slows.
  sfx(name, opts = {})         // opts: { vol = 1, pan = 0 (-1..1), pitch = 1 }
  music                        // MusicDirector below
}
```
SFX names (all must exist; unknown name = console.warn, no throw):
`swing swingHeavy hit slice sever clang clangPerfect block whoosh jump land step
stepHeavy goblinGrowl goblinAttack goblinDie ogreRoar ogreSwing ogreImpact heartbeat
getUpPulse auraBurst uiTick uiSelect flowUp flowBreak reset throw deflect bodyFall
impactFrame whiteout`
- `clangPerfect` is THE aura sound: bright metallic ring + sub boom + short reverb tail.
- `reset` = the Aura Reset chime (air dash refilled) — short, rising, satisfying, like a RL flip-reset "tink".
- `flowUp` tiny tick that rises in pitch with `opts.pitch`; `flowBreak` = a dull detuned thud.
- `heartbeat` = lub-dub. `getUpPulse` = heartbeat + a whoosh swell (each SPACE press when getting up).

```js
class MusicDirector {
  async listTracks()     // [{id:'code', name:'Code Phonk (claim-free)'}, ...{id, name, file}] from GET /api/tracks
  async select(id)       // decode (user tracks) + detect drop/tempo via ../sim/analyze.js; returns
                         // { id, name, bpm, dropAt, firstBeat, detected: bool }. Overrides from
                         // /api/tracks fields (drop, bpm, firstBeat) win over detection;
                         // then localStorage 'aura:drop:<id>' (try/catch) wins over both.
  setDropOverride(id, seconds)   // nudge + persist to localStorage
  previewDrop()          // play selected track from drop-4s to drop+2s (for the nudge UI)
  startAwakening()       // start music NOW. Returns { musicStart, dropAt, beat, grid }
                         // all on the ctx clock. grid = new BeatGrid(bpm, t0) from ../sim/beat.js
                         // with a beat exactly at dropAt. Code phonk: lead-in = 2 bars of cowbell
                         // melody only (filter opening + riser), the LAST BEAT BEFORE THE DROP IS
                         // TOTAL SILENCE, then the drop (808 + kick + clap + hats + crash + sub boom).
                         // User track: start playback at (drop - leadIn) where leadIn = 2 bars at its bpm.
  get grid()             // current BeatGrid on ctx clock, or null if no music
  nextSectionDrop(t)     // ctx time of the next big section drop after t (code phonk: song form is
                         // 8 bars main + 1 bar break (build + silent last beat), repeating from dropAt,
                         // so drops at dropAt + k*9 bars; user track: every 8 bars from dropAt)
  isPlaying
  cut()                  // instant silence (the finishing blow)
  stop(fade = 1.5)
  boom()                 // one huge distorted 808 + crash + long tail (the body hitting the ground)
  startPractice(bpm)     // dojo: loop the main groove immediately (code phonk at bpm; user track =
                         // loop from its drop at its own bpm, bpm arg ignored). Returns grid.
  setMetronome(on)       // soft click on every beat (dojo), accent on bar 1
}
```
Code phonk: key C minor, default 130 BPM. Cowbell melody (808-style: two square
oscillators ~1:1.48 through a bandpass, short decay, played melodically), distorted
808 bass (sine + pitch drop + tanh waveshaper + lowpass, BASS BOOSTED), kick, clap,
16th hats with rolls, crash on drops. Sections must vary a little so 3 minutes of
fighting do not feel like one bar looped. Use a lookahead scheduler (setInterval
~25 ms, schedule ~150 ms ahead). Master chain ends in a limiter so the drop is loud,
not clipped. Also export `renderPhonkOffline(seconds, bpm)` → Promise<AudioBuffer>
(OfflineAudioContext, same voices) so a self-test can prove analyze.js finds the drop.

## 2. Characters — `web/actors.js`

Anime cel look: `MeshToonMaterial` with a 3-step gradient map + inverted-hull black
outlines (extrude along normal in the vertex shader, BackSide). Feet at local origin,
facing +Z. Procedural animation only.

```js
export function makePlayer()      // young swordsman: dark coat, spiky black hair, red scarf, katana in right hand
export function makeSister()      // ~1.15 m little girl: white dress, long hair with a ribbon
export function makeGoblin(seed)  // ~1.1 m hunched, grey-green, big ears, glowing yellow eyes, club OR spear (seed)
export function makeOgre()        // ~4.6 m brute: dark slate skin, horns, red eyes, LEFT hand grabs, RIGHT hand giant cleaver
export function makeViewmodel()   // first-person: right forearm + hand + katana (and left hand), for the camera
```
Every actor returns:
```js
{
  root,          // THREE.Group, place/rotate this (root.rotation.y = yaw)
  height, radius,
  sockets,       // named Object3Ds, world positions read via getWorldPosition
  pose(dt, s),   // call every frame. s = {
                 //   move: 0..1 (fraction of top speed), air: bool,
                 //   action: 'idle'|'attack'|'windup'|'parry'|'dash'|'hurt'|'down'|'getup'|'dead'
                 //           |'held'|'sit'|'hide'|'hug'|'throw'|'stagger'|'roar'|'hold'|'raise'|'chop'
                 //           |'slam'|'sweep',
                 //   t: 0..1 progress through that action,
                 //   combo: 0|1|2 (which swing), dir: 'left'|'right'|'over' (attacks/parries),
                 //   squeeze: 0..1 (ogre 'hold') }
  flash(color, secs),  // hit flash (emissive pulse)
  setAura(level),      // 0..1. Player: eyes glow cyan, veins glow along both forearms, hair tips lit
  dispose(),
}
```
Sockets required — player: `head eyes swordTip hand forearmR chest`; sister: `neck head`;
goblin: `head hand`; ogre: `head grabHand cleaverTip stub chest`.
Ogre also: `sever()` → detaches and returns the LEFT arm from mid-upper-arm down (a
Group, world transform preserved via `scene.attach` is the caller's job — just remove
it from the rig and return it) and switches the rig to one-armed poses; `stub` socket
is where the cut is. While 'hold', the sister's `neck` socket belongs at `grabHand`
(the caller parents her; the hand must be closed around empty space ~12 cm wide).
Viewmodel returns `{ group, pose(dt, s), setAura(level) }` with the same `s` shape;
group is added to the camera, sword visible lower-right, swings sweep across view.

Ogre attack poses: 'windup' with dir tells which: 'over' = cleaver raised high
(slam), 'left'/'right' = cleaver drawn back to that side (sweep). Make the windup
READABLE from 10 m — the direction must be obvious from the silhouette.

## 3. World, FX, post — `web/world.js`, `web/fx.js`, `web/post.js`

```js
// world.js
export function buildWorld(scene, renderer) → {
  update(dt, time, awaken),   // awaken 0..1 shifts the sky/fog/lights from quiet dusk to the charged look
  heightAt(x, z),             // 0 inside the arena (r < 26) — keep the fight flat
  arenaRadius: 26,
  spots: { start: V3(0,0,-20), sisterHide: V3(-8,0,7), ogreEntry: V3(7,0,26), ogreHold: V3(3,0,10) },
  sun,                         // the main DirectionalLight (shadows); game recentres its target on the player
}
export function buildDojo() → { scene, update(dt, time), spots: { player: V3(0,0,-6), target: V3(0,0,4) } }
```
Scene: a dirt road running along Z through a forest clearing at dusk, arena flat, trees
(instanced), grass tufts (instanced), rocks incl. a big hiding rock at sisterHide, fog,
a gradient sky dome with a huge moon, fireflies. Night-blue/purple dusk, quiet.
Dojo: night courtyard, wooden floor, lanterns, dark, reads as a training space.
Budget: ≤ 150 draw calls, one shadow-casting light.

```js
// fx.js
export class FX {
  constructor(scene)
  burst(kind, pos, dir = null, count = null)  // 'spark' 'ink' 'aura' 'dust' 'flow' 'reset' 'deflect' 'glass'
  slash(pos, yaw, pitch, dir, color, scale = 1)  // anime slash arc (additive crescent ribbon), fades in ~0.18 s
  shockwave(pos, radius, color)                   // expanding ground ring
  afterimage(obj3d, color)                        // ghost copy of an actor silhouette, fades ~0.35 s
  auraBody(obj3d, level)                          // continuous cyan flame around an actor; call per frame
  beam(from, to, color, width)                    // short streak (deflected spear trail, dash streak)
  update(dt)
}
```
Pool everything; nothing allocates per frame.

```js
// post.js — renders scene to a HalfFloat target, then one composite pass to screen
export class Post {
  constructor(renderer)
  setSize(w, h)
  render(scene, camera)
  u   // plain numbers the game animates every frame:
      // { exposure: 1, sat: 1, contrast: 1, gray: 0 (B&W amount), invert: 0 (impact frame),
      //   vignette: .35, aberration: 0, speed: 0 (radial anime speed lines), flash: 0 (white-out),
      //   down: 0 (knocked-down: dark edges + red pulse), tint: [r,g,b], time }
}
```
Tone mapping (ACES) and sRGB are done INSIDE the composite shader (three does not
tone-map into render targets). B&W must be HIGH CONTRAST (crushed blacks, hot whites),
not a gentle desaturate. `invert` of the B&W image is the anime impact frame.

## 4. Game — `web/game.js`, `web/ui.js`, `web/index.html`, `web/style.css`
Owned by the lead. Debug hooks on `window.AURA` (`state()`, `jump(phase)`, `freeze()`, `step(sec)`).
