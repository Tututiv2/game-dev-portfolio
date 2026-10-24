# Hand-off: cross-module ("all") fixes

## Wave-3 integrate finding: "no wave-3 feature has been seen in a real client" (2026-10-04)

The finding cannot be closed from a session that may not start the server or a client, so this pass did the
two things that can be done without one: read every wave-3 feature's player-facing path for the bugs that
only a client shows, and turn the hand-offs' prose "verify live" lists into a pass that runs in one command.
**Still not seen in a client.** The next round runs the script below.

### Bugs found by reading the client paths, fixed

| Where | Bug a client would have hit | Fix |
|---|---|---|
| hub `TravelSpots.trigger` (the Rift arch) | A refused walk-in gate pushed the player "away from the box centre". The Rift arch's gate is a 1 x 5 portal sheet, so anyone who walked in off the middle column was pushed ALONG the sheet, stayed inside the portal, and was refused again 3 s later - the "nudged back out" never happened for 4 of 5 columns | `TravelSpots.nudge(box, at)`: a thin gate pushes straight across its thin side, toward the side the player came from (facing decides at dead centre); pads (wide) still push from the centre. Unit test `TravelSpotsNudgeTest` (3 cases); hub tests 29/29 |
| hub `TargetPractice` | The glowing target is a per-player block change. Anything that re-sends that chunk section to the client (walking out of range and back, a block update beside it) silently restores the real block, and the round carried on with no visible target | the glow is re-sent once a second while a round runs (`glow()`, `Session.refresh`) |
| pets `PetRide` | `InventoryOpenEvent` on the Armadillo mount was cancelled to protect the saddle. A rider's client does not open its own inventory on E - it asks the server, which opens the horse screen - so a mounted player had **no inventory at all** | the screen opens (it shows the player's inventory); every click, shift-click, double-click collect or drag that could reach the mount's own slots is cancelled instead. Supersedes the pets hand-off's "its inventory never opens" |

Read and found sound (no change): Quiver Arrow (proxy pickup is impossible - `setConsumeItem(false)` makes the
arrow creative-pickup-only, and `Bows.prepare` sets DISALLOWED anyway; the client's predicted ammo use is
corrected by the forced re-sync), Belly of the Beast pearls and Rift Gallery exhibits (owner-only
ItemDisplays are re-shown every tick, so a relog or chunk reload cannot leave them hidden), the Portal Isle's
dark-frame nudge (toward the front anchor, not the box centre), Kuudra "Hunt Again" (detaching the crew closes
the finished run and frees its arena before the new one is picked), the Runic Pedestal (core hands
player-inventory clicks to the menu and re-validates the slot; fusing a stack of 2 from one slot is handled).

### The pass, scripted: `tools/testpilot/wave3_pass.py`

14 checks, one per wave-3 feature: `rift_arch`, `target_practice`, `portal_isle`, `kuudra_again`, `moonmarsh`,
`belly_pearls`, `goblin_queen`, `quiver_arrow`, `pet_riding`, `bank_account_wide`, `rift_gallery` (Gallery +
Split or Steal; Odonata is particles, left as a hand step the report names), `runic_pedestal`, `carnival`,
`gfs`. Admin set-up goes over RCON, everything the player does goes through TestPilot. Verdict per check:
PASS / FAIL / LOOK (ran, a person reads the screenshot) / SKIP / ERROR. Output `docs/playtest/wave3/report.md`
+ `report.json` + screenshots. It ops the test user for the run and deops at the end; it never starts or stops
a server or client and never edits `server.properties`.

To run (the operator does the parts the script refuses to):
1. `cd tools\testpilot && gradlew.bat build`, copy `build\libs\testpilot-1.0.0+mc26.1.2.jar` into the
   Fabric-26.1.2 instance's `mods\` (README section 5). Built here 2026-10-04, **not installed** (that folder
   belongs to `MinecraftLauncher/`). Without it `target_practice` and `quiver_arrow` report SKIP.
2. `online-mode=false`, restart, `py -3 pilot.py launch --user PilotTest` (README section 1).
3. `set PYTHONIOENCODING=utf-8` then `py -3 wave3_pass.py --user PilotTest`.
4. Restore `online-mode=true`, restart.

**The script has not been run** (no client in this phase). Its RCON client WAS exercised against the running
dev server with read-only commands (`list`, `sb info`).

### TestPilot: `/usehold` (new endpoint)

A bow cannot be fired through TestPilot before this: `/use` presses and releases right click on the same tick,
below the bow's 0.1-power floor, so no arrow ever leaves. `/usehold {seconds, x,y,z, lift}` holds the real
`keyUse` KeyMapping and lets vanilla release it, re-aiming at the point every tick; `{probe:true}` answers
without pressing anything (old jars answer 404). `Pilot.use_hold()` in `pilot.py`; README endpoint table updated.

### Paid for (RCON)

Sending the RCON command packet and the end-marker packet back to back (what `scripts/rcon.ps1` does) made
Paper 26.2 drop the connection with no reply about one time in four (2 of 6 here; 0 of 10 when the marker is
sent after the first reply packet arrives). `wave3_pass.Rcon` waits for the first reply, and uses one
connection per command. `scripts/rcon.ps1` was not changed; if it ever returns nothing for a command that
should print, this is the likely cause.

Verified: `bash tools/check.sh all` CHECK OK (902 files); hub JUnit 29/29 (26 + 3 new); TestPilot
`gradlew build` OK; `py_compile` of `wave3_pass.py` and `pilot.py`.
