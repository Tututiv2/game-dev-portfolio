# ShowCam

A Fabric **client** mod for Minecraft **26.1.2** (Fabric Loader 0.19.5, Fabric API
0.155.3+26.1.2, Java 25, Loom 1.17, no mappings line) that gives the AISky show rig:

1. **A per-client window title** (`SHOWCAM_TITLE`), so OBS can window-capture each of the
   five show clients separately. They all run the same launcher instance and would
   otherwise all be called `Minecraft 26.1.2 - Multiplayer (3rd-party Server)`.
2. **A smooth cinematic camera** (`SHOWCAM=1`, camera client only), driven over a localhost
   HTTP API (CONTRACT section 11): orbit, push-in, follow, crane, dolly, static.

Mod id `showcam`, package `com.aisky.showcam`. It lives in the same instance as TestPilot
(`MinecraftLauncher/data/instances/Fabric-26.1.2/mods/`), which the owner also plays
himself, so it is **completely inert** unless one of its environment variables is set.

---

## Environment variables (or `-D` system properties)

| Env | Property | Default | Meaning |
|---|---|---|---|
| `SHOWCAM_TITLE` | `showcam.title` | unset | Window title. Alone it does nothing else |
| `SHOWCAM` | `showcam.enabled` | unset = **off** | `1` = camera client: HTTP server + camera override |
| `SHOWCAM_PORT` | `showcam.port` | `25660` | HTTP port (127.0.0.1 only). Does NOT enable anything by itself |
| `SHOWCAM_HUD` | `showcam.hud` | `0` | `1` keeps the HUD visible during shots |
| `SHOWCAM_TP` | `showcam.tp` | `1` | `0` = never send `/tp` for the camera account |

With neither `SHOWCAM` nor `SHOWCAM_TITLE` set, the entrypoint logs
`[showcam] inactive ...` and returns, and all three mixins return on their first line.

All option changes (hidden HUD, view bobbing off, first-person) are **in memory only**:
while ShowCam is active `Options.save()` is cancelled, exactly like TestPilot, so the
owner's `options.txt` is never written.

---

## Endpoints (camera client, `http://127.0.0.1:25660`)

### `POST /shot`

```json
{"type": "orbit|pushin|follow|crane|dolly|static|stop",
 "target": {"player": "AccountName"} | {"x": 1.5, "y": 70, "z": -3},
 "seconds": 8, "radius": 6, "height": 2, "fov": 60, "speed": 1.0,
 "cut": false, "clip": true}
```

Every field except `type` (and `target`, except for `stop`) is optional. `target.player`
is the **Minecraft account name** (what the server sees), matched case-insensitively.
`target` may also be a bare string (`"target": "AISkyKaito"`).

| type | what the camera does | default seconds / radius / height |
|---|---|---|
| `orbit` | circles the subject at `radius`, `height` above its eyes, `speed` 1 = 18 deg/s (a turn every 20 s; negative reverses). Eases in, and with `seconds > 0` eases to a stop at `seconds` and holds | 0 (forever) / 6 / 2 |
| `pushin` | starts `radius` in front of the subject's FACE, `height` above it, and closes to 0.3 x radius (min 1.0) at eye level while the FOV narrows to 55% | 4 / 4 / 0.5 |
| `follow` | over the right shoulder, `radius` behind, looking 8 blocks ahead; the subject's facing is smoothed (~0.5 s) so head flicks do not whip the camera | 0 (forever) / 3.5 / 0.8 |
| `crane` | starts low and close (0.5 x radius, just below eye level), rises to `height` and pulls back to 1.6 x radius | 6 / 5 / 6 |
| `dolly` | tracks sideways along a straight line past the subject (1.6 x radius x `speed` each side), closest at mid-shot, always looking at it | 6 / 5 / 1 |
| `static` | a locked-off tripod `radius` out and `height` up that pans to keep the subject framed | 0 (forever) / 6 / 2 |
| `stop` | releases the camera back to vanilla, with the player's eye placed where the lens was (no visible jump), and restores HUD/bobbing | - |

* Every shot starts from the **bearing the camera is already at**, so consecutive shots on
  one subject continue instead of swinging to a fixed side.
* A new shot **glides** out of the previous one (position, look point and FOV are low-passed
  per frame, frame-rate independent). `"cut": true` snaps instead; so does any jump of more
  than 40 blocks, and a subject that teleports more than 24 blocks (a warp) is cut with.
* `"clip": true` (default) pulls the lens in front of any block between it and the subject.
* After `seconds`, move shots (`pushin`, `crane`, `dolly`) hold their end framing and keep
  tracking; `orbit` has stopped and holds; `follow`/`static` keep going. `status.done`
  becomes true. Nothing auto-releases: send the next shot or `stop`.
* Values are clamped (radius 0.5-64, height -16..64, fov 10-120, speed -10..10, seconds
  0-600). Unknown `type` or a missing target is a 400 with an `error`.

Reply: `{"ok": true, "accepted": {type, target, seconds, radius, height, fov, speed, cut, clip}}`
with the defaults filled in. The shot is picked up on the next rendered frame.

### `GET /status` (also `GET /`)

```json
{"ok": true, "active": true, "inWorld": true, "queued": false, "title": "AISky - Camera",
 "shot": {"type": "orbit", "target": "AISkyKaito", "seconds": 8.0},
 "elapsed": 3.217, "progress": 0.402, "done": false,
 "camera": {"x": 4.1, "y": 72.6, "z": -1.9, "yaw": 63.2, "pitch": 14.8, "fov": 60.0},
 "targetLoaded": true, "targetPos": {"x": 0.5, "y": 71.4, "z": 0.5},
 "note": null}
```

Idle (`active: false`) it still reports the vanilla camera pose. `targetLoaded: false`
means the subject is not in this client's world view: if it was never seen the lens holds
still and the shot's clock does not start; `note` says `waiting for target to load` or
which `/tp` was sent.

### `GET /ping`

`{"ok": true, "mod": "showcam", "title": ...}`

---

## How the camera works (and why)

The lens is overridden **inside `Camera.update(DeltaTracker)` every rendered frame**, from the
subject's own interpolated position (`Entity.getPosition(partialTick)`), so it is exactly as
smooth as the frame rate. The injection point is right after vanilla stores `hudFov`
(verified with javap: `alignWithEntity -> fov = calculateFov -> hudFov = calculateHudFov ->
prepareCullFrustum(getViewRotationMatrix, ..., position) -> setupPerspective(fov)`), so the
culling frustum, the culling projection and the render projection are all built from
ShowCam's position, rotation and FOV, and any other mod's `calculateFov` hook (AuraQoL's zoom,
which is in this instance) has already run and cannot fight it.

The alternative - moving the local spectator player smoothly per frame - was rejected: the
player's rendered position is `lerp(partialTick, xo, x)`, updated 20 times a second, so a
player moved every frame stutters against that interpolation, and the server answers fast
movement with "moved too quickly" corrections.

The player entity is still moved, but only as a **chunk anchor**: on every client tick it is
dragged toward the lens in steps of at most 5 blocks (vanilla rejects more than 10 per tick),
so the server keeps streaming the chunks and entities the shot needs. It is never on screen:
the camera stays "first person" (`detached` is pinned false), and vanilla only draws the
camera's own entity when the camera is detached. This only happens when the camera account
is in **spectator** mode (a survival body is never dragged through blocks). If the lens is
more than 64 blocks away, or the subject is not loaded on this client at all, ShowCam sends
`/tp @s <x y z>` or `/tp @s <player>` (at most every 3 s) - the camera account must be **op**.
Turn that off with `SHOWCAM_TP=0`.

The HUD is hidden (`options.hideGui`, in memory) for the duration of a shot; the hand is not
rendered for a spectator anyway, and view bobbing is switched off.

Mixin targets (all verified against the 26.1.2 client with javap, descriptors exact):

| Mixin | Target | Injection |
|---|---|---|
| `CameraMixin` | `net.minecraft.client.Camera#update(Lnet/minecraft/client/DeltaTracker;)V` | `@At(FIELD, PUTFIELD Camera.hudFov:F, shift AFTER)` (exactly one store in the method) |
| | shadows: `detached:Z`, `fov:F`, `setPosition(Lnet/minecraft/world/phys/Vec3;)V`, `setRotation(FF)V`, `position()Lnet/minecraft/world/phys/Vec3;`, `xRot()F`, `yRot()F` | |
| `MinecraftTitleMixin` | `net.minecraft.client.Minecraft#createTitle()Ljava/lang/String;` (private; feeds both the `Window` constructor and `updateTitle()`) | `@At("RETURN")`, cancellable, replaces the value |
| `OptionsMixin` | `net.minecraft.client.Options#save()V` | `@At("HEAD")`, cancel when ShowCam is active |

---

## Coexisting with TestPilot

Both mods sit in one instance and are independent: TestPilot (port 2565x) drives the player,
ShowCam (25660) drives the lens. Both cancel `Options.save()`; two cancelling HEAD injections
coexist. TestPilot's no-focus window, fps cap and mute apply to every show client.

Player clients (title only):

```
TESTPILOT=1 TESTPILOT_PORT=25650 SHOWCAM_TITLE="AISky - Kaito"
```

Camera client:

```
TESTPILOT=1 TESTPILOT_PORT=25654 SHOWCAM=1 SHOWCAM_PORT=25660 SHOWCAM_TITLE="AISky - Camera"
TESTPILOT_FPS=60 TESTPILOT_WINDOW=1920x1080        # recommended: TestPilot caps at 30 fps / 720p by default
```

The launcher passes its environment through to the game, so set these on the
`py -3 -m launcher launch Fabric-26.1.2 --dev-user <account> --server 127.0.0.1:25640 --detach`
process (run from `MinecraftLauncher\`), which is what `pilot.py launch_client` does for
`TESTPILOT`. Then on the server: `/show camera <cameraAccount>` (spectator, op, hidden from
players). Do not also run the server-side fallback `/show cam ...` on the same account while
ShowCam is driving it - both would move the camera player.

---

## Build / install

```powershell
cd "D:\projects\AISkyShow\showcam"
powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1      # gradlew build --no-daemon, then copy
```

`install.ps1` refuses while Streamlabs / Rocket League / OBS are running (`-Force` overrides),
removes any older `showcam-*.jar` from the instance's `mods\` and copies
`build\libs\showcam-1.0.0+mc26.1.2.jar` in. It installs only into
`MinecraftLauncher\data\instances\Fabric-26.1.2\mods\`, never `%APPDATA%\.minecraft`.

Manual equivalent:

```powershell
.\gradlew.bat build --no-daemon
copy build\libs\showcam-1.0.0+mc26.1.2.jar "..\..\MinecraftLauncher\data\instances\Fabric-26.1.2\mods\"
```

Pure-maths check (no game, no gradle, a few seconds, safe any time):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File check\check.ps1    # 73 checks
```

It runs the shipping `CamMath` / `ShotSpec` / `ShotPlanner` at 30/60/144 fps: Minecraft's
yaw/pitch conventions, easing at rest at both ends, frame-rate independent smoothing, orbit
sweeping exactly 117 degrees in 8 s at every frame rate, push-in/crane/dolly/static/follow
geometry, and that no shot's ideal path moves more than 0.5 block in one 60 fps frame.

---

## Verification checklist (after the stream)

Status as written (2026-10-03, during a live stream): every source compiles with `javac
--release 25` against the real 26.1.2 client jar + Fabric Loader 0.19.5 + Fabric API module
jars + Mixin; every target/shadow above was checked with javap; `check\check.ps1` passes
73/73. **Not yet done: the gradle/Loom build, and anything in a running game.**

1. [ ] `install.ps1` - gradle build succeeds and the jar lands in the instance `mods\`.
2. [ ] **Inert path**: launch the instance normally (no env vars) - log has
       `[showcam] inactive`, title is vanilla, `options.txt` still saves (change a setting,
       quit, check the timestamp), F1/camera untouched. *Opens a normal focus-taking window:
       do it when the owner is not using the PC.*
3. [ ] **Mixins apply**: no `InvalidInjectionException` / `Critical injection failure` in
       `logs\latest.log` (with `defaultRequire: 1` a missed target crashes at startup).
4. [ ] **Title**: launch one player client with `SHOWCAM_TITLE="AISky - Test"` - the window
       title is exactly that, before AND after joining the server (updateTitle on join).
5. [ ] **Camera**: camera client with the env above, `/show camera <account>`, then
       `Invoke-RestMethod http://127.0.0.1:25660/status` answers.
6. [ ] Each shot against a moving player: `orbit`, `pushin`, `follow`, `crane`, `dolly`,
       `static`, then `stop`. Look for: no stutter, no snap at shot start (except `cut`),
       HUD hidden, camera body not visible, `stop` returns without a jump and HUD restored.
       `/status` progress/done correct. Record a 60 s clip in OBS and scrub it.
7. [ ] Target not loaded: shot on a player 500 blocks away - `note` shows the `/tp`, the
       camera arrives and the shot starts. Target warps to another island: cut + follow.
8. [ ] `clip`: orbit a player standing next to a wall - the lens stays in front of the wall.
9. [ ] Server log: no "moved too quickly" spam for the camera account during a long orbit.
10. [ ] Instance `options.txt` timestamp unchanged after a camera session.
11. [ ] Owner's own session afterwards (no env vars) plays normally, including AuraQoL zoom.
