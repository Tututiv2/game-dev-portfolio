# MyFire Launcher

A Minecraft: Java Edition launcher that keeps **every version in its own folder**, downloads
**its own Java**, and installs **Fabric / Quilt / Forge / NeoForge** for you.

Double-click **`MyFire Launcher.bat`**. There is nothing to install — it is standard-library
Python, so there is no `pip install`, no virtualenv, and nothing that can rot between
sessions.

---

## 1. Microsoft sign-in, and the approval wall — read this first

Multiplayer on a real server needs a real Microsoft login. A server in online mode asks
Mojang to confirm the session, and nothing short of a genuine sign-in satisfies that.

**You need your own Azure application (client) ID.** Registration is free and takes ten
minutes:

1. [portal.azure.com](https://portal.azure.com) → sign in with any Microsoft account.
2. **Microsoft Entra ID** → **App registrations** → **New registration**.
3. Name it anything. **Supported account types: "Personal Microsoft accounts only"** —
   Minecraft accounts are personal, not work/school.
4. Register, then copy the **Application (client) ID**.
5. **Authentication → Advanced settings → "Allow public client flows" → Yes → Save.**
   Without this the device-code flow fails.
6. No client secret. A desktop app cannot keep one and this flow does not need one.
7. Don't look for `XboxLive.signin` in the API permissions picker — it isn't listed there.
   It is requested at runtime in the `scope` parameter.

Then paste the client ID into **Settings** in the launcher.

### The part that governs the schedule

Microsoft gates the Minecraft API behind a **manual review**. A freshly registered app is
**not** approved by default.

- **Apply here: <https://aka.ms/mce-reviewappid>**
- Until it is approved, `api.minecraftservices.com` returns **403**. The Microsoft and Xbox
  Live hops will appear to work perfectly, which is what makes this confusing to diagnose.
- **Approval has been reported to take weeks to a couple of months.** Apps registered before
  the policy came in are unaffected, which is why long-established launchers work.

The launcher treats that 403 as its own named error and says so in as many words, rather
than showing a raw HTTP failure:

> This launcher's Microsoft app has not been approved for the Minecraft API yet (403).
> That is an approval status, not a bug — apply or check at aka.ms/mce-reviewappid.

**A 403 and a 404 are different things and the launcher never conflates them.** A 404 from
`/minecraft/profile` means the account does not own Java Edition (usually a Bedrock-only or
Game Pass account) — a completely different problem with a completely different fix.

### Everything else works while you wait

Only real online sign-in is blocked. Version resolution, downloads, Java management,
instances, all four mod loaders, the UI, Quick Play — all of it works now, and is tested
against local servers with a **dev account** (`--dev`). That account is offline-only: it
cannot join an online-mode server and never claims it can.

To use it: run `MyFire Launcher.bat --dev`, then click the account chip (top right) →
**Manage accounts…** → **Use a dev account**. With nothing signed in, the chip goes
straight there.

---

## 2. The app

It opens on a **main menu**: a *Continue* card for whatever you played last (with a one-click
**Play + join server** when that instance has a server saved), a grid of your instances, and a
strip showing which Java runtimes the launcher has fetched for you. **Library** gives the
full list, the per-instance detail pane and the log console. `Esc` goes back to the menu.

The menu is drawn on a canvas rather than assembled from tk widgets -- rounded cards, a radial
ember glow, and a slow field of sparks. See `launcher/ui/canvaskit.py` for how each effect is
done and why that way; tkinter has no rounded corners, gradients or alpha of its own.

## 3. Everyday use

| I want to… | Do this |
|---|---|
| Open the launcher | Double-click **`MyFire Launcher.bat`** |
| Make a new install | **+ New** → name, version, loader → **Create** |
| Play | Select it on the left → **Play** |
| Join a server on launch | Set *Quick play server* in **Edit**, then **Play + join server** |
| Add a mod | **Mods** → drop the `.jar` in the folder that opens |
| Add a second account | Click the account chip (top right) → **Add another account** |
| Switch account | Click the account chip → pick a name. The ticked one is who plays |
| Sign an account out | **Accounts** → **Sign out** on that row (deletes the saved login only) |
| See why it crashed | **Console** at the bottom right |
| Move everything | The whole `data\` folder is self-contained |

### The layout

```
MinecraftLauncher\
  MyFire Launcher.bat      <- double-click this
  Test.bat                 <- run the test suite
  launcher\                <- the code (core has no UI imports)
  tests\
  data\                    <- everything the launcher downloads
    instances\<name>\      <- PER INSTANCE: mods, saves, config, options.txt, servers.dat
    assets\ libraries\     <- SHARED, content-addressed by SHA-1
    versions\ runtimes\    <- SHARED, keyed by immutable version id
    settings.json accounts.json logs\
```

`assets`, `libraries`, `versions` and `runtimes` are shared because they are addressed by
hash or by an immutable id, so two instances pointing at the same file is always safe.
Everything mutable is per-instance and never shared.

---

## 4. Isolation — the thing this exists for

Your normal game is Fabric 26.2 with `fabric-api`, `voicechat` and `secondwatch` in
`%appdata%\.minecraft\mods\`. **This launcher never writes there.** It is not a convention,
it is enforced: `guard_game_dir()` raises if any instance path resolves inside the real
`.minecraft`, and it runs on every launch rather than only at creation, because a
hand-edited `instance.json` is otherwise a loaded gun.

Verified after installing Forge 1.20.1, Forge 1.12.2, Fabric 26.2 and two vanilla versions
and launching all of them:

```
baseline files: 7 | now: 7
CHANGED: none
ADDED  : none
VERDICT: UNCHANGED - byte for byte
```

The launcher also never touches the **official launcher's** `launcher_profiles.json`. That
file is rewritten from memory when the official launcher exits, so anything written to it
while it is open is silently erased — which is exactly why a Forge install "disappears".
This launcher keeps its own state and writes its own stub only inside its own `data\`
folder, where the Forge installer needs one to exist.

---

## 5. Java

The only system-wide Java here is 25, which nothing below 1.20.5 can use. The launcher
downloads and manages its own, per version, from Mojang's runtime index (with Adoptium as a
fallback):

| Minecraft | Java | Component |
|---|---|---|
| ≤ 1.16.5 | 8 | `jre-legacy` |
| 1.17 – 1.20.4 | 17 | `java-runtime-gamma` |
| 1.20.5 – 1.21.x | 21 | `java-runtime-delta` |
| 26.x | 25 | `java-runtime-epsilon` |

Versions before 1.17 declare no `javaVersion` at all; those default to **Java 8**, never to
"whatever is installed". The launcher also *probes* the runtime it installed and refuses to
launch if it is the wrong major, because the alternative is a wall of
`Unsupported class file major version` that points nowhere useful.

> **Note for anyone following the original spec:** the runtime index URL in it,
> `piston-meta.mojang.com/v1/products/java-runtime/all.json`, **404s**. The real document
> sits behind a content-hash path segment. The correct URL is in `launcher/java.py`.

---

## 6. Mod loaders

**Fabric and Quilt** publish a ready-made version JSON through their meta API. The launcher
fetches it, writes it to `versions/<id>/<id>.json`, and the normal `inheritsFrom` resolver
does the rest.

**Forge and NeoForge** need their official installer, run headlessly:

```
<the java that Minecraft version needs> -jar forge-<ver>-installer.jar --installClient <dir>
```

Three things this gets right, each of which costs an hour the first time:

1. The installer **requires `launcher_profiles.json` to already exist** or it aborts. A stub
   is written first, and the profile it injects is then ignored.
2. It runs under **the Java that Minecraft version needs** — the 1.12.2 installer wants
   Java 8.
3. The **vanilla client jar must already be present**, because the processors patch it.

---

## 7. Command line

The UI is a client of the same core. Everything is drivable headlessly:

```
py -3 -m launcher doctor                       environment report
py -3 -m launcher versions --type release      list versions
py -3 -m launcher list                         list instances
py -3 -m launcher --dev create "Name" --version 1.20.1 --loader forge --loader-version 47.4.10
py -3 -m launcher --dev dryrun <slug> --dev-user Tutut     print the exact argv, no game
py -3 -m launcher --dev launch <slug> --dev-user Tutut --server localhost:25595
```

`dryrun` is the useful one: it resolves, downloads, verifies and prints the command line
that *would* be handed to the process — with the token redacted — without starting a game.

---

## 8. Tests

```
Test.bat            43 offline checks: merge order, rules, templating, auth, NBT, isolation
Test.bat net        + 3 network checks: real downloads, SHA-1s, zero-byte second run
```

All 46 pass. What they actually assert is in
[`tests/run_tests.py`](tests/run_tests.py) — each check names the trap it protects.

---

## 9. What was actually tested

See [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md). It is honest about the parts that were
run and the parts that were not.
