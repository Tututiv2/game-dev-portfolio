# How a click on Play becomes a process

One page. The core is a library with **no UI imports** — that is the single architectural
decision the rest of the project leans on, because almost none of the hard parts are visual
and all of them need to be testable without a window or a game.

```
launcher/
  paths.py       directory layout, MAX_PATH handling, slugs
  logs.py        redaction at the logging layer (not at call sites)
  net.py         retry/backoff, SHA-1, resume, atomic writes, bounded pool, progress
  rules.py       rule evaluation: os/arch/version-regex + feature flags
  versions.py    manifest, version JSON, the inheritsFrom merge
  libraries.py   library selection, classpath order, natives (both packagings)
  assets.py      shared content-addressed asset store + the two legacy layouts
  java.py        runtime index, download, probe, per-version selection
  instances.py   isolation, instance.json, create/duplicate/delete/export
  nbt.py         servers.dat (uncompressed NBT)
  launch.py      argument templating, argv assembly, spawn, supervise
  settings.py    app-level defaults
  accounts/      base.py (the interface) · local.py (dev stub) · msa.py (5 hops)
                 secrets.py (DPAPI/Keychain/libsecret) · store.py (multi-account)
  loaders/       fabric.py (Fabric+Quilt) · forge.py (Forge+NeoForge)
  ui/            theme.py · skin.py · dialogs.py · app.py     <- imports the core, never
                                                                 the other way round
  cli.py         headless entry point
```

## The pipeline

**Play** calls `launch.prepare()` on a worker thread, then `launch.spawn()`. `prepare` does
everything except start the process, which is what lets a test assert the exact command line
without a game.

### 1. Resolve — `versions.resolve()`

Fetch the version JSON (from disk if cached; version JSONs are immutable), then follow
`inheritsFrom` to the vanilla root with a visited-set, and fold from the root upward.

The merge order is a **correctness** requirement:

- `libraries` — **child first, then parent**, de-duplicated on `group:artifact:classifier`
  keeping the first. Forge ships patched copies of vanilla classes and they must win the
  classpath. De-duping on `group:artifact` alone would delete
  `org.lwjgl:lwjgl:3.3.1:natives-windows` because plain `org.lwjgl:lwjgl:3.3.1` already
  matched — a different jar, and half the version list stops starting.
- `arguments.game` / `arguments.jvm` — **parent first, child appended**.
- Scalars (`mainClass`, `minecraftArguments`, `type`) — child wins where present.
- `assetIndex`, `downloads`, `javaVersion` — inherited.

### 2. Java — `java.resolve_java()`

`javaVersion.majorVersion` if declared, **Java 8 if the field is absent** (pre-1.17).
Download from Mojang's runtime index, fall back to Adoptium, then **probe the binary** and
refuse to launch on a mismatch.

### 3. Libraries — `libraries.plan_downloads()`

Evaluate each library's `rules` for this OS. Then, per library, work out where its bytes
come from — `downloads.artifact` (vanilla), a Maven repo root in `url` (Fabric/Forge), or
neither, meaning the loader installer already produced it locally and it just has to be
there. Anything in that last category which is *missing* is reported as an incomplete loader
install rather than as a mysterious crash.

### 4. Assets — `assets.ensure_index()` + `plan_downloads()`

One shared `assets/` store, content-addressed by SHA-1. Thousands of small files, so:
bounded concurrency, skip anything already present with a matching hash. The two legacy
shapes (`virtual`, `map_to_resources`) are materialised into a name-shaped tree.

### 5. Natives — `libraries.extract_natives()`

Both packagings are supported. Old-style (`natives: {...}` + `downloads.classifiers`) are
extracted into a **fresh directory per launch**, honouring `extract.exclude`; new-style
(1.19+, classifier baked into the coordinate) are ordinary classpath jars with nothing to
extract. Zero extracted files is the normal, healthy answer on modern versions.

The natives directory is per-launch and deleted when the process exits — sharing one between
two running instances is a file-lock failure on Windows with no useful message.

### 6. Command line — `launch.build_arguments()`

Two eras: a `minecraftArguments` string (pre-1.13, split on whitespace) or
`arguments: {game, jvm}` where each element is a string or a rules-gated object.

Every `${placeholder}` is substituted from one table, and **an unresolved placeholder
raises**, naming itself. That strictness paid for itself immediately: it caught
`${quickPlayPath}`, which 1.20.1 emits from its own argument block whenever the Quick Play
feature flag is set, and which would otherwise have reached the command line as a literal.

All `auth_*` values come from the `Account` interface. Nothing in the launch pipeline
reaches for a token any other way — which is why the dev stub and a real Microsoft account
are interchangeable without touching this file.

### 7. Spawn — `launch.spawn()`

```python
subprocess.Popen(plan.argv, cwd=..., stdout=handle, ...)
```

Two Windows constraints:

- **`plan.argv` is a list, never a string.** The install path is
  `D:\projects\MinecraftLauncher`. Concatenation splits that at the space in
  "Ai Game" and the launch fails with a message about a missing main class.
- **Output goes to a file, not a pipe**, and the child is spawned `DETACHED_PROCESS |
  CREATE_NEW_PROCESS_GROUP`. A pipe's read end dies with the launcher; a file handle is the
  child's own. Verified: killing the launcher leaves the game running.

The in-app console tails that file, so it is live output that also survives the launcher
closing.

### 8. Supervise — `launch.GameProcess`

A tail thread and a reaper thread. On exit the natives directory is removed and
`diagnose()` produces a verdict — including the one people miss, that **exit code 0 with an
instant close is almost always a natives or classpath failure**, so the last 40 lines are
surfaced automatically rather than left in a file nobody opens.

## Authentication

`accounts/base.py` defines the whole contract: `name`, `uuid`, `access_token`, `user_type`,
`xuid`, `online`. Two implementations satisfy it.

`msa.py` is five hops, each its own function so each is testable against a mocked transport:
device code → Xbox Live → XSTS → Minecraft → entitlements → profile. XSTS `XErr` codes are
translated to sentences. 403 and 404 land on **different exception classes**.

Refresh tokens go to DPAPI (`accounts/secrets.py`, via `ctypes` so there is no dependency);
the short-lived Minecraft token is never written to disk. Refresh happens **eagerly on
launch**, not lazily on failure — discovering an expired token halfway through a 600 MB
download is a terrible experience.

Tokens are redacted **at the logging layer** (`logs.py`), by two mechanisms: registered
secret values, and shape matching for tokens we never saw. Plus one thing shape matching
cannot do — `LaunchPlan.redacted_argv()` blanks the element *after* `--accessToken`, because
an opaque token in its own argv slot looks like any other string.
