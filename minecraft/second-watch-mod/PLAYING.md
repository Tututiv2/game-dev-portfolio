# Playing it

## Lunar / Badlion: not possible

Both are closed clients with their own mod systems. Neither can load a Fabric mod, let alone a
custom one. Any custom mod means a real Fabric install — there is no way around it, and no amount of
work on this end changes that.

---

## Two ways to launch, and they are not equivalent

### 1. `Play.bat` — the dev client (fast)

Double-click it. Starts the server, then the game. Multiplayer → Direct Connection → `localhost`.

Your username comes from `dev_username` in `gradle.properties`. **Change it to your real one.**

**What this cannot do: skins.** A dev client is always offline-mode, so the account has no session
and Mojang never sends texture data.

### 2. The real server + the pack (the one that actually tests this mod)

There is now a real dedicated server for this, sitting next to the two Forge ones:
**`MyFireServer/Start Horror Server.bat`** — Minecraft 26.2, Fabric 0.19.3,
`online-mode=true`, whitelisted, on `localhost:25597`. Verified booting to `Done` with all
three mods loaded and the whole `/secondwatch` command tree registered.

The client side is one file: **`MyHorrorMod-26.2.mrpack`**, built by `Launcher Pack.bat`.
Import it (Lovely Launcher → **Import pack**; Prism and the Modrinth app read it too) and
you get an isolated instance with the three mods and that server already in the multiplayer
list. Nothing is hand-copied and `.minecraft` is never touched.

Why a pack instead of the five steps this section used to list: it pins *exact* versions by
SHA-1, so "both ends must run identical builds" stops being something anyone has to
remember. Second Watch is carried inside it; Fabric API and Simple Voice Chat are
*referenced* by URL and hash, which keeps it at 141 KB and means nobody is redistributing
someone else's mod.

Slower to set up than `Play.bat`. It is also the only configuration in which this mod's
central illusion exists.

**The old manual route still works** if the other end has only the vanilla launcher, which
is the one launcher that cannot import a pack: run `Friend Pack.bat` and follow the READ ME
it writes.

---

## Why the skin is not cosmetic here

The phantom is a `Mannequin` wearing `partner.getGameProfile()`. That profile carries the skin as a
signed texture property issued by Mojang at login — and **an offline-mode server never receives
one**. So on an offline server the phantom renders as default Steve no matter who it is imitating.

Which means the entire premise — *is that my friend or is that the thing wearing my friend* —
cannot be evaluated at all. A phantom in the wrong skin is not a weaker scare, it is a different
object: a mannequin standing in a field.

**So: use the dev client to check that the body renders, walks and can be walked through. Use the
real launcher, online-mode, to judge whether it is frightening.** Those are two different questions
and only one of them can be answered cheaply.

---

## Getting your friend in

**Mods:** run `Launcher Pack.bat` and send them `MyHorrorMod-26.2.mrpack`. Their launcher does
the rest, fetching Fabric API and voice chat at the exact versions you have. (`Friend Pack.bat`
is still there for anyone on the vanilla launcher, which cannot import a pack — that route
needs them to fetch Fabric API from Modrinth by hand.)

Both of you must run **identical versions of every mod**. Mismatched builds mean each end interprets
the phantom differently, and the horror becomes a crash.

**Address:** the gradle dev server listens on **25565** and the real one on **25597**, both on
your machine only. To let someone else reach either, pick one:

| Route | Notes |
|---|---|
| **playit.gg / ngrok** | Easiest. Free tunnel, gives a public address, no router config |
| **Tailscale** | Both install it, they connect to your Tailscale IP. Private, no open ports |
| **Port forward 25565** | Fastest connection, but opens a port on your router |

With `online-mode=true` your friend must own the game and be logged in — which is what you want,
because that is what makes their skin exist.

---

## Once you are in

```
/secondwatch status     what this run rolled - SPOILERS, never on stream
/secondwatch book       the previous group's journal, with a username in it
/secondwatch phantom    a copy of YOU, 6 blocks ahead, only you can see it
/secondwatch voice      confirms your microphone is being recorded
/secondwatch night      force the next chapter (for testing and re-shoots)
/secondwatch start      begin a run - night 1 is deliberately ordinary
/secondwatch release    give the sky back
```

**The real phantom needs two players.** `PhantomDirector.consider()` throws below two live
observers, on purpose — with one observer nothing can ever be established as false, so the system
has no meaning alone. `/secondwatch phantom` exists to check the body renders, nothing more.

## Starting a clean run

The world in `run/` is a test bed with nights already burned through it. For a real session:

```
delete run\secondwatch-test
```

then set a new `level-seed` in `run/server.properties`. A fresh seed rolls a new tell, a new
trigger, a new dead group and a new journal — and night one will be an ordinary Minecraft night,
which is the whole setup.
