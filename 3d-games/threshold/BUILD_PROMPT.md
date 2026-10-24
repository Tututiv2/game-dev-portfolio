# THRESHOLD — build prompt

> Working name for the prototype. The folder is `CozyInterior/`. This document is the source of
> truth for what gets built and why. A fresh Claude session should read this file first and
> nothing else.

---

## 0. The one sentence

**Cozy is not quiet. Cozy is *filtered*.**

The feeling being chased here is not silence and it is not safety. It is the moment a loud thing
becomes a soft thing *while still obviously being there* — the rain is still falling, the wind is
still screaming, you simply moved a wall between you and it, and now you get to enjoy it. Shelter
you can **hear**.

Everything in this build serves that one moment. If walking through a doorway during a downpour
does not give the owner chills, nothing else in this document matters.

### The corollary that most games miss

Going inside does not only *subtract* sound. It **adds** one.

Outside in the rain you hear a broadband hiss coming from everywhere — rain hitting ground, all
around, no direction. Step inside and that hiss drops away, but you *gain* something you could not
hear at all outdoors: **the drumming on the roof above your head.** A near-field, directional,
overhead impact layer that exists only when there is something over you.

That is the actual mechanism of "cozy". You didn't lose the storm, you got a better seat for it.
An interior that is merely a muffled exterior feels dead. An interior that hands you a new sound
feels like shelter.

**Requirement:** the roof layer is an interior-only source, spatialised above the listener, and it
must be *inaudible* when outdoors. Non-negotiable.

---

## 1. Scope of this build

This is a **vertical slice**, not the game. One hand-authored cabin, on one small patch of one
planet, under one weather system.

**In scope:**
- First-person walk/look, indoor and outdoor, with a door and windows that open **by degrees**
- One cabin: four walls, roof, one door, two windows, one interior partition (so there is an
  inner room that is *doubly* insulated — sound through two walls)
- One continuous weather system: calm → breeze → rain → downpour → thunderstorm
- Full acoustic simulation of the boundary between inside and outside
- A live A/B toggle between two entirely different acoustic techniques (§4)
- A debug HUD that shows you what the audio system currently believes (§7)
- A headless audio test suite that *measures* the claims this document makes (§8)

**Explicitly NOT in scope:**
block placement/building UI · multiple planets · space or flight · survival stats (oxygen, temp,
hunger) · combat · multiplayer · saving · NPCs · tornadoes/hurricanes · day-night cycle beyond
what lighting needs.

**Why:** this is a probe to find out what's achievable *before* it gets folded into the main space
game. It answers one question — "can we make the inside/outside transition feel the way it feels
in real life" — and it answers it in isolation where nothing else can muddy the result.

**But** — every system here is written so the real game can absorb it without a rewrite. See §9.

---

## 2. The engine spine: the sealed-volume solver

This is the load-bearing system. Build it first, because everything else reads from it.

The world is a **block grid** from day one, even though the cabin is hand-authored. Blocks carry
material data. The solver does one thing:

> Flood-fill outward through empty cells from the player's position. If the fill escapes to open
> sky, the player is **outdoors**. If the fill closes, the player is in a **sealed volume**, and
> the boundary of that fill enumerates every **aperture** connecting it to elsewhere.

From that single pass you get, free:

| Output | Used for |
|---|---|
| `sealed: bool` | is the player indoors at all |
| `cellCount` → volume in m³ | reverb decay time, room "size" feel |
| `surfaces[]` by material | reverb character (wood warm, stone bright, glass hard) |
| `apertures[]` — position, area, openness, what's on the other side | **the entire acoustic model** |
| `roofArea` above listener | roof-drumming layer gain |
| which cells are interior | precipitation culling, fog culling, wind culling |

This is the same solver Space Engineers uses for airtightness. That is not a coincidence and it is
the reason this prototype is worth doing here: **when the survival game needs pressurisation,
temperature, and life support, they read from this exact structure.** One solve, five features.

### Implementation notes
- Cache aggressively. Re-solve only when a block changes, a door/window state changes, or the
  player crosses into a different volume. Never per-frame.
- Volumes get stable IDs so the audio system can crossfade between them rather than snapping.
- Solve is capped (e.g. 20k cells) — hit the cap and it's classified outdoors. Prevents a
  pathological fill on an open plain.
- A door counts as an aperture whose `openness` is 0..1, *not* as a solid block that toggles. The
  solver must never see a discontinuity when a door swings.

---

## 3. The acoustic model

### 3.1 Transmission through a wall is a low-pass, not a volume knob

Real mass-law transmission loss rises roughly **6 dB per octave** — a wall eats treble far harder
than bass. That is *why* muffled sounds muffled. Implement wall transmission as gain reduction
**plus** a low-pass filter, per material. Starting values, to be tuned by ear:

| Material | Attenuation | Low-pass cutoff |
|---|---|---|
| Wooden wall | −20 dB | 700 Hz |
| Stone / concrete | −32 dB | 400 Hz |
| Glass pane (closed) | −22 dB | 1200 Hz |
| Metal hull | −28 dB | 550 Hz |
| Cloth / tarp | −9 dB | 3500 Hz |

Two walls between you and the source stack (the inner room of the cabin exists to demonstrate
exactly this).

### 3.2 The aperture *dominates* — this is the whole trick

Here is the real physics that makes the owner's favourite detail work:

> **A hole destroys isolation out of all proportion to its size.** A wall that is 1% open by area
> can never give you more than about 20 dB of isolation, no matter how thick it is. The sound does
> not care about your wall; it walks through the gap.

So: **do not model an open window as "the wall attenuates less".** Model it as a *separate
positional sound source placed at the aperture*.

For each aperture between the listener's volume and the outdoors:
1. Spawn a **virtual point source** at the aperture's centroid, in world space.
2. It plays the outdoor weather bed.
3. Its gain and filter cutoff are driven by `openness` and area.
4. It is **fully spatialised** (HRTF panner), so it has a direction.

That is the entire mechanism, and it produces every single example the owner gave, emergently:

- *"opening the window makes a bit more noise only at the window"* → the aperture emitter is at the
  window. Turn your head, it moves. Walk toward it, it gets louder. **This is the shot.**
- *"muffled wind inside"* → wall transmission path, heavily low-passed.
- *"you can hear it on the roof"* → §0 roof layer.
- Standing in an open doorway → the aperture is at your face, huge and unfiltered, while the volume
  behind you still holds reverb. This position should feel *specifically* like standing in a
  doorway, and it will, for free.

Aperture starting values:

| State | Gain | Cutoff |
|---|---|---|
| Window closed | −24 dB | 900 Hz |
| Window cracked (10%) | −9 dB | 3.5 kHz |
| Window half open | −4 dB | 8 kHz |
| Window / door wide | −1 dB | 18 kHz (bypass) |

Note how brutally non-linear that is — the first 10% of opening buys most of the loudness. That's
correct, that's real, and it makes the analog window control *feel* like something.

### 3.3 Wind through a narrow gap whistles

A gap doesn't just pass wind, it **resonates**. Give every aperture a band-pass "whistle" voice
whose centre frequency rises as the gap narrows and whose gain peaks at partial opening and falls
off at both ends (sealed = silent, wide = silent, cracked = eerie).

Roughly: `f ≈ 200 Hz + 2400 / max(openness, 0.02)`, clamped to 300–4000 Hz, gain peaking near
`openness ≈ 0.12`, scaled by wind speed.

This is a small amount of code for an enormous amount of atmosphere. A window left barely open
during a gale should be genuinely unsettling.

### 3.4 Room reverb from the solve

Feed the volume solve into a reverb (convolution with a procedurally-generated impulse, or a
Feedback Delay Network — implementer's choice, FDN is cheaper and modulates smoothly).

- **RT60 from volume + material:** small wooden cabin ≈ 0.35 s, warm and short. Stone hall ≈ 1.8 s.
  Outdoors ≈ 0 (dry, with only a distant "landscape" tail if anything).
- **Wet/dry mix** from how enclosed the volume is.
- Everything the player hears indoors — footsteps, door hinges, their own movement, *and the
  transmitted weather* — goes through it. That is what makes an interior feel like a place rather
  than a filter setting.

### 3.5 Thunder, and distance as a filter

- Flash first, boom `distance / 343` seconds later. 3 seconds per kilometre.
- **Air absorption eats high frequencies over distance far faster than low.** So distant thunder is
  a long low rumble and near thunder is a sharp crack — and it is *the same sound*, differently
  filtered. Implement one thunder synth, low-pass and stretch it by distance. Free realism, and it
  gives storms a real sense of scale as they approach.
- Near strikes should momentarily duck everything else and leave a tail in the room reverb.

### 3.6 **The threshold must be continuous — this is the highest-risk detail**

If the audio *snaps* when the player crosses the doorframe, the entire project is dead. It will
read as a bug and as cheapness, and no amount of good filtering will save it.

Requirements:
- The listener's acoustic state is a **blend**, not a lookup. When near a boundary, blend the
  volumes weighted by proximity. Standing in a doorway = genuinely half in each.
- Every filter cutoff, gain, and reverb parameter is smoothed toward its target with a time
  constant of ~80–150 ms. Use `AudioParam` ramps, never direct assignment.
- Door and window animation drives `openness` continuously; the audio follows the *animation*, not
  the input event.
- Walking slowly through the door should be a smooth sweep you can stop halfway through.
- §8 has a test that measures this.

---

## 4. The toggle — and why it's the right call

The owner asked for both the simple approach and the real one, with a switch. That's not a
compromise; it's the best idea in this spec, for two reasons.

**One:** it turns a design claim into something the owner can verify with his own ears, mid-storm,
standing at his own window. Press `B`, hear the difference, decide. No argument required.

**Two:** it forces a clean interface. The acoustic system ends up behind a boundary
(`AcousticBackend`) with a tiny API, which is exactly the seam the real game will need when this
is ported.

### Backend A — `BakedBackend`
Pre-rendered "outside" and "inside" stems, crossfaded when the player crosses the threshold. What
most games ship. Cheap, robust, ~50 lines.

### Backend B — `PortalBackend`
Everything in §3. Live graph, portal emitters, per-material filters, solver-driven reverb.

### The elegant part: both backends use the same source material

All weather audio is **synthesised in code** (§5), so the baked stems are produced by rendering
that same synth through `OfflineAudioContext` **at boot**. Nothing is recorded, nothing ships as a
file, and — critically — the A/B comparison is a pure test of *technique*, not of content. Same
rain, two ways of putting a wall in front of it.

Toggle with **`B`**, live, no reload, no audio dropout. HUD shows which is active.

---

## 5. Sound is synthesised, not sampled — zero asset files

> **SUPERSEDED 2026-08-17 for rain and roof. Read this section as the design that was tried first.**
>
> This was written before anyone had heard it, and the argument in it is still a good argument —
> it is just not the one that won. The synth was measured, corrected twice (a one-pole filter that
> tilted the spectrum instead of band-limiting it; density; onsets; peak normalisation) and
> demonstrably improved each time, and a human listened to the third version and said it still
> sounded terrible. At that point the honest read is that "sounds like rain" is not a property the
> measurements were capturing, and no further round of the same loop was going to find it.
>
> So **outdoor rain and roof drumming are now CC0/public-domain recordings** — see
> `audio/CREDITS.md`. **Wind, thunder and all foley remain synthesised**, and thunder specifically
> must stay that way: a recording carries the air absorption of its own distance baked in, so
> filtering it again for §3.5 double-counts and collapses the near-versus-far contrast the feature
> exists to show. Measured, a recorded strike went from 2092/291 Hz down to 232/144 Hz.
>
> Everything below still describes the fallback path, which is live code: if the .wav files are
> missing the game synthesises instead of failing, and `tests/synth.test.mjs` covers it.

Weather is the single best case for procedural audio that exists:

- **Rain bed** — filtered white/pink noise, band-shaped. Intensity moves cutoff and gain.
- **Rain droplets** — sparse scheduled impulse grains, short resonant pings, randomised pitch. This
  layer is what makes rain read as *individual drops* rather than static, and it is the layer that
  becomes the roof drumming (denser, lower, closer, overhead).
- **Wind** — noise through a slowly-modulated band-pass; gust envelope on top. Two or three
  detuned layers give it body.
- **Whistle** — high-Q band-pass on the same noise (§3.3).
- **Thunder** — low-frequency noise burst, long multi-stage decay, run through the reverb.
- **Footsteps, doors, hinges, latches** — short synthesised transients, material-dependent.

Everything is deterministic from a seed, so the test suite can reproduce a run exactly.

**What survived the switch, and it is the load-bearing part:** there is still exactly ONE rain
buffer, feeding the diffuse bed and every portal at once. That is what makes the sound through the
wall and the sound at the open window the same rain rather than two unrelated rainstorms playing
either side of a pane — and it is the reason a pile of downloaded loops would not have worked.

---

## 6. The visual half — cozy is not audio-only

The audio is the thesis, but the shot only lands if the eye agrees.

- **Colour temperature contrast is the whole image.** Interior lamp/fire ≈ 2400–2700 K, warm
  orange. Storm exterior ≈ 6500–7500 K, cold blue-grey. Standing in the doorway you should have
  warm light on one cheek and cold on the other. This does more work than any other visual choice.
- **Rain on glass** — droplets and running streaks rendered on window panes only, refracting the
  exterior. Density tracks precipitation. The single most "cozy" image in games.
- **Precipitation must be culled by the solver.** Rain does not fall inside the cabin. Ever. It
  stops at the roof plane. If an aperture is open, a small amount blows *through* it and wets the
  floor near the window — that detail alone sells the whole system.
- **Fog / haze** likewise stops at the wall. Looking out of a window into fog is a different image
  from standing in it.
- **Wind response is aperture-gated.** Outdoor grass and trees whip. Indoor curtains, hanging
  lamps and papers move *only* if an aperture is open, and only near it. Crack the window and the
  curtain stirs. That's the visual twin of the audio portal, from the same data.
- **Condensation** creeps in on glass when it's cold outside and warm in. Clears where you wipe it,
  if that's cheap.
- **Dust motes** in a light shaft from the window. Firelight flicker driving the interior light's
  intensity and position slightly.
- **Screen effects outdoors in a storm:** wet lens droplets, slight camera shake in gusts, reduced
  visibility. So that going inside is visible *relief* and not just a change of scene.

---

## 7. Controls and HUD

**Controls:** WASD move · mouse look · Shift sprint · `E` interact · **hold `E` on a window to open
it by degrees** (analog — "a bit more noise" needs a dial, not a switch) · `F` toggle interior
light · `B` toggle acoustic backend · `~` toggle debug HUD · `1`–`5` force weather state · `T`
trigger a thunder strike.

**Debug HUD** (this is a development instrument, keep it good):
- Current volume ID, sealed y/n, volume m³, dominant material
- Active backend
- Live list of apertures: position, area, openness, current gain and cutoff
- Current low-pass cutoff on the transmission path, as a number and a little curve
- Reverb RT60 and wet mix
- Weather state and intensity, wind vector, precipitation rate
- Frame time and audio-graph node count

---

## 8. Verification — measure it, don't vibe it

Every project in this portfolio earns its claims. This one is unusually testable, because Web
Audio ships `OfflineAudioContext`: the entire audio graph can be rendered faster than real time
with no speakers and no browser window, then FFT'd and asserted against. Run with `?test`, and
from the command line where possible.

**Acoustic assertions** (all against rendered audio, not against parameter values):
1. Outdoor rain bed has substantial energy above 4 kHz.
2. Indoors, sealed, door and windows shut: energy above 4 kHz is at least 20 dB below (1).
3. Cracking one window raises the 2–8 kHz band by a measurable amount, and **the increase is
   asymmetric between the stereo channels** in the direction of the window. This is the test that
   proves the portal model is doing what §3.2 claims — a baked crossfade *cannot* pass it, which
   makes it the A/B proof in machine-readable form.
4. The roof layer registers non-zero energy indoors and **exactly zero** outdoors.
5. Two walls (inner room) attenuate measurably more than one.
6. Thunder at 3 km arrives ~8.7 s after the flash and has a lower spectral centroid than thunder
   at 200 m.
7. Wind whistle centre frequency rises as gap openness falls.

**Agency assertions — added 2026-08-17, and the ones that were missing.**

Every check above measures whether a *component* is correct. All of them passed on a build whose
verdict from a listener was "kinda mid, idk why", because none of them asked the only question a
player can answer directly: **does doing something change what I hear?** It did not — the roof layer
sat 16 dB over everything else and never varied, so opening the front door onto a downpour moved the
total level by 1.5 dB. A soundscape can be right in every part and still be inert.

9. Throwing the front door wide open changes the total level by **> 4 dB**.
10. Cracking a window 10% changes it by **> 3 dB** standing at that window.
11. Stepping inside and shutting up is **> 12 dB** quieter than standing in the storm.
12. The roof layer is still the loudest single layer indoors — 9–11 must not be bought by
    destroying §0's corollary. It is the tension between 9–11 and 12 that fixes the mix, and neither
    side of it can be tuned alone.

**Continuity assertion — the important one:**
8. Sample the full acoustic parameter set every frame along a scripted walk that crosses the
   threshold at three speeds (slow, walk, sprint). Assert **no single-frame jump** in gain exceeds
   ~1.5 dB or in cutoff exceeds ~15%. This catches the snap that would kill the project, and it
   catches it automatically forever.

**Solver assertions:**
9. The cabin classifies as sealed; removing one roof block flips it to unsealed; replacing it flips
   it back.
10. Aperture enumeration finds exactly the door and the two windows, at the right positions.
11. Solver cost stays under budget with the cabin fully closed and fully open.

**Performance:** frame time budget and audio node count logged; assert no unbounded node growth
after 5 minutes of storm (Web Audio node leaks are the classic failure mode here).

---

## 9. Seams left open for the real game

This slice hand-authors a cabin, but nothing in it assumes a hand-authored cabin.

- **Grid from day zero.** The cabin is placed through the same `grid.set(x,y,z,material)` API a
  player-built structure will use. When building arrives, rooms you build are immediately audible
  with no acoustics work at all.
- **Materials are data.** Acoustic properties (attenuation, cutoff, reverb character, footstep
  sound) are rows in a table. A new block type is a new row, not new code.
- **The solver's return value is what life support needs.** `{sealed, volume, apertures[],
  surfaces[]}` is precisely the input for pressurisation, temperature and oxygen. When the survival
  systems land, they subscribe to the solver rather than re-deriving anything.
- **Weather is an interface, not a hardcoded storm.** A `WeatherProfile` describes precipitation
  type, wind curve, thunder frequency, palette. Different planets are different profiles. Do not
  write `if (planet === 'x')` anywhere.
- **`AcousticBackend` is a clean boundary** (§4), so the whole audio system can be lifted into
  another codebase.

---

## 10. Stack and layout

Vanilla JS ES modules + three.js (vendored), **no build step**. `Play.bat` serves the folder and
opens the browser. Consistent with Neon Nomad 3D, Gleaner, Learn to Fly.

```
CozyInterior/
  Play.bat                    → local server + open browser
  index.html
  vendor/three.module.js      (+ its sibling core file — see landmines)
  src/
    main.js                   boot, loop, wiring
    player.js                 first-person controller, listener transform
    world/
      grid.js                 block grid + material table
      volumes.js              THE SOLVER — flood fill, volumes, apertures
      cabin.js                the hand-authored cabin, via the grid API
      terrain.js              small ground patch, trees, grass
      openings.js             doors + analog windows, animation → openness
    weather/
      weather.js              state machine, continuous intensity, profiles
      precip.js               rain particles, culled by the solver
      wind.js                 wind vector, gusts, foliage + cloth response
      thunder.js              strike scheduling, flash, distance
    audio/
      engine.js               AudioContext, master graph, listener sync
      backend.js              AcousticBackend interface
      backendBaked.js         A: crossfaded stems
      backendPortal.js        B: portals, filters, solver-driven reverb
      synth.js                procedural rain / wind / thunder / foley
      reverb.js               FDN or procedural impulse generation
      materials.js            acoustic properties per block material
    ui/hud.js                 debug overlay
  tests/
    run.js                    OfflineAudioContext harness (§8)
  CLAUDE.md                   written at the end, as the folder's map
```

---

## 11. Landmines already paid for in this portfolio

Do not rediscover these:

- **`three.module.min.js` needs its sibling core file.** Vendoring the one file silently fails.
- **A backtick inside a GLSL comment kills the entire JS module** when shaders live in template
  literals.
- **`flat` is a reserved word in GLSL.** So are a few others that read as innocent variable names.
- **LOD bound ≠ cull bound.** Using one for the other tears holes at chunk seams.
- **Cloning a `Matrix4` per placed object** cost a 224 ms GC pause in Neon Nomad. Reuse scratch
  objects in hot loops.
- **A height/terrain function must never be LOD-dependent** — mesh, collision, cursor and placement
  all read the same function or you get holes and floating objects.
- **New, specific to this project:** Web Audio nodes are not garbage-collected while connected.
  Every aperture emitter, every thunder voice, every droplet grain must be explicitly disconnected
  on teardown or a long storm will strangle the tab. Test 8/perf covers it.
- **New:** `AudioContext` cannot start without a user gesture. The title screen exists partly to be
  that gesture. Do not build a boot sequence that assumes audio is live before the first click.

---

## 12. Order of work

1. **Grid + solver + a test that proves the cabin is sealed.** No rendering needed to be sure.
2. First-person controller, cabin rendered, doors and analog windows animating.
3. Audio engine + synth. Rain and wind audible outdoors, unfiltered. No indoor logic yet.
4. `PortalBackend`: wall transmission, aperture emitters, roof layer, reverb. **This is the moment
   the project either works or doesn't — stop and listen here before building anything else.**
5. Continuity smoothing + the walk-through-the-door test.
6. `BakedBackend` + the `B` toggle.
7. Weather state machine, thunder, storm escalation.
8. Visual pass: light temperature, rain-on-glass, culling, curtains, condensation.
9. Full test suite green, HUD complete, `CLAUDE.md` written.

**Gate:** after step 4, the owner walks in and out of the cabin during a downpour and says whether
it works. Everything after step 5 is contingent on that answer.

---

## 13. Assumptions made, worth confirming

- **Scale:** 1 block = 1 metre, cabin roughly 8×6×3 blocks. Human eye height 1.7 m.
- **Headphones assumed** for the HRTF spatialisation. Speakers will still work but the "noise at
  the window" effect is dramatically better on headphones — worth a note on the title screen.
- **Cabin is wooden**, with a stone hearth and glass windows, because wood gives the warmest
  reverb and the best roof-drumming. If the real game is metal hulls in space, the same system
  handles it — it'll just sound colder and more resonant, which is correct.
- **No day/night cycle**, but the storm darkens the sky considerably, which gives the lighting
  contrast something to work with.
- **Tornado / hurricane are designed-not-built.** They are the same weather profile with the dial
  turned up plus a directional pressure event; noted here so the profile format doesn't get
  written in a way that excludes them.
