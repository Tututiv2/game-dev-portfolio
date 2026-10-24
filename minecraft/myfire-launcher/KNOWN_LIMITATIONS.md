# What was tested, and what was not

The brief asks for this to be honest, so nothing below is inferred from "it should work".

## Ran, on this machine, with the output checked

| Thing | Evidence |
|---|---|
| **Vanilla 1.20.1 launches** | Reached the title screen: `Sound engine started`, `OpenAL initialized`, texture atlases created. 6 s. |
| **Vanilla 1.12.2 launches** | Same markers, under auto-downloaded **Java 8**, 11 old-style native files extracted, `-Dlog4j2.formatMsgNoLookups=true` applied. |
| **Forge 1.20.1 + Yes Steve Model** | Installed headlessly in 21 s. Client reached the title screen in 14 s, 94 classpath entries, `mainClass=cpw.mods.bootstraplauncher.BootstrapLauncher`. Log shows `Found valid mod file ysm-2.6.5-forge+mc1.20.1-release.jar with {yes_steve_model}` loaded **from the instance's own mods folder**. |
| **Forge 1.12.2 + mods** | Installed headlessly under Java 8. Client reached the title screen in 9 s under `net.minecraft.launchwrapper.Launch`, with both mods discovered. |
| **Fabric 26.2** | Installed from the Fabric meta API. Launched under auto-downloaded **Java 25** (`java-runtime-epsilon`), `KnotClient`, title screen in 9 s. |
| **Java auto-download** | Java 8, 17 and 25 all fetched from Mojang's runtime index (403 files for 17) and probed to confirm the major. Adoptium fallback also exercised, before the index URL was corrected. |
| **Multiplayer join, end to end** | Quick Play into a throwaway 1.20.1 server: client logged `Connecting to localhost, 25599` then `Loaded 2 advancements`; the **server** logged `Tutut[/127.0.0.1:64352] logged in` and `Tutut joined the game`. |
| **Second run downloads zero bytes** | 3,662 artefacts (64 libraries + 3,598 assets, 649 MB) verified by SHA-1 in 1.6 s with **zero** downloads planned. |
| **`%appdata%\.minecraft` untouched** | SHA-1 baseline of all 7 files taken before any work; re-hashed after five instances and five launches. `CHANGED: none · ADDED: none`. Your `fabric-api`, `voicechat` and `secondwatch` jars are byte-identical. |
| **Game survives the launcher dying** | Launcher pid killed with `Stop-Process -Force`; the game process was still alive 6 s later at 942 MB RSS with 74 threads. |
| **All four dialogs build and populate** | The create dialog listed 102 releases and resolved 12 Forge builds for the selected version. |
| **DPAPI storage** | Real seal/unseal round trip through `CryptProtectData` on this machine. |
| **All five instances re-launched after the shared-jar change** | `tests/acceptance.py`: 5/5 reached the title screen. |
| **The main menu, library and console all render** | Screenshotted from the real window. Menu: hero card, instance grid, runtime chips, ember field. Library: list, detail, actions. Console: colour-coded log. |
| **Launching from the new UI** | `app.play(inst)` -- exactly what the menu's PLAY button calls -- reached the title screen in 13 s, `busy` cleared, 44 console lines captured, and the menu repainted while the game ran. |
| **The hover hang is gone** | Reproduced first: a stationary pointer oscillated hover 10/10 times at **56 pixel positions** across 7 widgets. After the fix, dragging the pointer through **348 positions** across all four card columns — under `pythonw`, launched from the .bat — left the window responding to `SendMessageTimeout` and the watchdog silent. |
| **Idle cost** | 14.6% of one core with the menu focused, **0.5% when it is not**. Measured with `process_time` around the real event loop. |
| **54/54 tests pass** | `Test.bat` and `Test.bat net`. |

## Built and unit-tested, but never run against the live service

**Real Microsoft sign-in.** All five hops, token refresh, multi-account storage and the
error translation are written and covered by 11 tests against a mocked transport — including
every `XErr` code, `authorization_pending` / `slow_down` polling, and 403-vs-404 landing on
different exception classes.

It has **never talked to Microsoft**, because that needs an approved Azure app and approval
takes weeks. Concretely untested until you have a client ID and approval:

- The real device-code round trip (the mock scripts the responses; the live service may
  differ in field names or timing).
- Whether the entitlement check accepts your specific account shape.
- Skin-head rendering from a real skin URL — the code path runs, but only a placeholder
  head has actually been drawn, because the dev account has no skin. That now applies to
  the account switcher too: every row in the Accounts dialog draws its own head.
- **Signing a *second* real account in.** Switching between accounts is fully exercised
  offline (the store, the menu and the dialog are all tested, and switching deliberately
  does no token work at all), but the launcher has never held two real Microsoft logins
  at once, because it has never held one.
- Refresh after a genuine 24-hour expiry. The *logic* is tested; the wall-clock case is not.

When approval lands: paste the client ID into Settings and sign in. If it 403s, the app is
still under review — the launcher says so in those words.

## Not tested

- **`ALT`+`Y` opening the Yes Steve Model picker.** The mod loads from the right folder and
  the client reaches the title screen; pressing a key inside the game window is not something
  I can drive. Your two servers are `online-mode=true` and whitelisted, so the dev account
  cannot join them — I deliberately did **not** change your server config to work around
  that. Once you have real login, that test is yours to run.
- **macOS and Linux.** The code paths exist (`-XstartOnFirstThread`, `:` separators,
  Keychain/libsecret, symlinked runtimes) and the rule tests cover all three platforms, but
  nothing has been executed anywhere except Windows 11.
- **Quilt.** It shares every line with Fabric and the meta API shape is the same, but no
  Quilt instance was created.
- **NeoForge.** Same installer path as Forge, exercised for Forge only. The NeoForge version
  numbering heuristic (mapping `1.X.Y` to `X.Y.*`) is the part most likely to need a fix.
- **Instance import.** Export→import round-trips in a unit test with real files; no
  hand-made archive from elsewhere has been imported.
- **Resume-after-interruption.** `Range` resume is implemented and the refused-Range path is
  handled, but no download was actually interrupted mid-flight.
- **Old beta/alpha versions.** The legacy asset layouts (`virtual`, `map_to_resources`) are
  implemented from the spec and never run — no pre-1.6 version was launched.

## Known gaps, by choice

- **Forge's `install_profile.json` processor pipeline is not reimplemented.** The official
  installer is shelled out to instead. That is the documented v1 approach; doing it natively
  is a project on its own.
- **No mod manager.** No Modrinth/CurseForge browsing, no dependency resolution. Dropping
  jars into an instance's `mods/` folder is the whole story. Noted as v2 in the brief.
- **No telemetry**, of any kind.
- **`MAX_PATH`**: handled by keeping the data root shallow *and* routing the launcher's own
  file IO through the `\\?\` extended-length prefix (`paths.ext`). That prefix is never
  passed to Java, which cannot parse it. Untested against a genuinely >260-character path.
- **One `data\` folder, moved by moving the folder.** There is no migration tool; the
  instances root is configurable in code (`MYFIRE_DATA_ROOT`) but not yet in the UI.

## Things found while building this that the spec had wrong or omitted

1. **The Java runtime index URL in the spec 404s.**
   `piston-meta.mojang.com/v1/products/java-runtime/all.json` returns an Azure blob error.
   The real document sits behind a content-hash path segment. Verified live; a test now
   asserts the URL is not the bare form.
2. **`java-runtime-epsilon` (Java 25) exists** and is what 26.x asks for. Not in the spec's
   table.
3. **1.20+ emits its own Quick Play arguments** from `arguments.game`, gated on the feature
   flags — so the launcher must supply `${quickPlayPath}` and `${quickPlayMultiplayer}` as
   *values*, not append the flags itself. Appending them would duplicate the arguments. The
   spec describes only the appending half.
4. **The client jar's *filename* is load-bearing for modern Forge**, which the spec does not
   mention. Sharing one `1.20.1.jar` between vanilla and every modded 1.20.1 instance is the
   obvious optimisation and it breaks Forge: Forge runs on the module path and passes
   `-DignoreList=...,${version_name}.jar` to keep that filename out of the module graph.
   Under any other name the client jar becomes an automatic module and the launch dies with
   `ResolutionException: Modules _1._20._1 and minecraft export package
   net.minecraft.client to module MixinExtras`. The launcher downloads once under the root
   id and **hard-links** it to the launched id, so both facts hold and it still costs no
   extra bytes (verified: `links=2`, 138 MB apparent, 67 MB on disk). There is a test for
   this now, because it was introduced and caught inside the same hour.

5. **A hover effect that moves the widget is a feedback loop, and Windows kills the app for
   it.** The instance cards lifted 3 px on hover. A lift moves the card out from under a
   *stationary* pointer, so Tk fires `<Leave>`, which undoes the lift, which puts it back
   under the pointer, which fires `<Enter>`. The Tk thread never returns to the event loop
   and Windows closes the process with **Application Error 1002, "stopped interacting with
   Windows and was closed"** — a *hang*, which from outside looks exactly like a crash. This
   shipped in the first version of the main menu. Hover is now colour-only, and hit testing
   reads fixed geometry rather than the live bounding box, which is what makes the loop
   impossible rather than merely unlikely.
6. **A double-clicked app has to be able to explain its own failures.** After the hang there
   was *nothing* on disk: under `pythonw.exe` `sys.stderr` is `None`, so tracebacks go
   nowhere and `logging.StreamHandler` fails on every record while logging swallows its own
   errors. A hang does not even produce a traceback. The diagnosis came from a Windows event
   log entry, not from the launcher. Now stdio is redirected to a file before anything is
   imported, `report_callback_exception` is replaced, and a watchdog thread logs the main
   thread's stack when it stops ticking — which names the stuck function outright.
7. **A worker thread touching Tk does not raise -- it deadlocks, silently.** `_do_launch`
   ran on a background thread and called `console_write`, and the launcher hung with the
   window still painted and the game happily running behind it. Nothing in the logs, no
   traceback, no clue. Both `console_write` and `set_status` now check the current thread
   and re-queue themselves, and the suite parses `app.py` and fails if any `_do_*` method
   calls a UI method outside an `_on_ui(...)` wrapper. The check was verified by
   re-introducing the bug and watching it fail.
