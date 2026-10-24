# How to visit the Town of Beginnings

You do **not** need to generate anything. The Town is already built and on disk. Ignore
`Pregen Floor One.bat` — that is only for filling in the rest of the 10 km floor later.

---

## 1. Start the server

Double-click **`Dev Server.bat`**.

Wait for this line in the black console window:

```
Done (7.123s)! For help, type "help"
```

## 2. Make yourself an operator

You need this to use the teleport command. In that **same console window**, type your exact
Minecraft username and press Enter:

```
op YourMinecraftName
```

You should see `Made YourMinecraftName a server operator`.

## 3. Connect

In Minecraft: **Multiplayer → Direct Connection**, and enter:

```
localhost:25577
```

(`127.0.0.1:25577` works too. The port is 25577, **not** the usual 25565.)

### Turn your render distance up first

**Options → Video Settings → Render Distance → 32 chunks.** This matters more than anything else
here. The server was sending only 10 chunks (160 blocks) of a town that is 1,000 blocks across, so
the town looked like a wall of fog in every direction. `view-distance` is now 32 server-side (512
blocks), but the client has to ask for it — the server will not push more than you request.

## 4. You will spawn in an empty flat world — this is expected

Do not panic, the Town is not missing. You land in the ordinary Minecraft overworld, because the
Town lives in its own dimension called `aincrad:floors_01_10`.

Type this in chat to go there:

```
/aincrad tp
```

You will arrive in the **Central Plaza, facing north at Black Iron Palace**, in creative mode with
flight enabled.

---

## What to look for

| Direction | What you should see |
|---|---|
| **North** | Black Iron Palace — the huge dark mass with the dome. Walk up the ceremonial stair; the doors open into real interiors. |
| **Straight up** | The clock tower above you, with readable analog clock faces on all four sides and a bell |
| **Under your feet** | The Teleport Gate platform, inside the open Arrival Rotunda |
| **East** | Central Market — the densest streets |
| **West** | Inn and crafting quarter |
| **Southwest** | Church quarter |
| **Any direction** | The city wall ring, with three gates. Roads run out through them. |
| **South, past the wall** | Aincrad's southern rim |

Try walking into any house — all **1,150** have a real door, a real interior, and most have an
upper floor with a bed you can actually reach by stairs.

Useful commands while you look around:

```
/aincrad verify spawn        - safe zone + service anchor check
/aincrad verify buildings    - runs the acceptance test over all 1,150 houses
/aincrad status              - mask + ledger state
/aincrad tp                  - back to the plaza if you get lost
```

## The map wall

A vanilla map only draws ground you have personally walked over, so a fresh one is a blank sheet.
Instead there is a physical board whose pixels are read out of the world itself, so it is **fully
revealed the moment it is built** and everyone who walks up to it sees the same thing.

```
/aincrad mapwall             - town + road approaches (3,840 x 2,304 blocks)
/aincrad mapwall 16 5 5 0 0  - the entire floor, centred on the floor rather than the town
/aincrad mapwall stop        - cancel a build in progress
/aincrad map                 - an ordinary handheld map, which does fill in as you explore
```

It builds on the plaza's north side at **(-2, 328, 4390)** — walk up to it and look north. Progress
is reported in chat; the default board takes about 20 seconds.

Two honest caveats:

- **Void patches are real.** Chunks that were saved while pregeneration was interrupted sit at a
  partial generation status. The board refuses to finish generating them just to draw them, so they
  appear as void. The command reports exactly how many it skipped.
- **The whole-floor board is barely legible at 1:1.** At 16 blocks/pixel the entire Town of
  Beginnings is about 62 pixels across. That is the 1:1 scale being too large to read, not a
  rendering limit.

---

## Notes / current limits

- **Beyond the Town it is flat.** Only the Town slice (7,912 chunks) plus ~5% of the floor centre has
  shaped terrain. Fly far out and you will find a flat green plate — that is what
  `Pregen Floor One.bat` fills in (about 2.3 hours, resumable, safe to close).
- **The sky is open.** Floor Two's structural plate is not built yet, so there is no ceiling
  overhead. It should eventually hang about 100 blocks up.
- **Nothing moves.** NPCs exist as 1,481 database records, not walking entities. Shops, the Teleport
  Gate and service counters are structures without behaviour yet.
- **Creative mode is deliberate** for sightseeing. To switch back to survival-style play, change
  `gamemode=creative` back to `gamemode=adventure` in `server/server.properties`.

## To shut down

Type `stop` in the console window, or just close it.
