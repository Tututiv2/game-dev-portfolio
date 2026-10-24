# Landmine ledger — Shadow

> House law, carried over from the Minecraft repo: **every bug fixed becomes a written rule in the
> same change.** A rule here is cheaper than rediscovering the bug. If you disarm something, write
> the rule — and disarm it *in code*, never by telling the owner not to use it.

Each entry: what actually happened, and the rule it produced.

---

## 1. `fs.rename` silently destroys the destination
**Was:** `shadow:renamePath` called `fs.rename` with no existence check. Renaming `game.old.js` to
`game.js` destroyed the real `game.js` instantly — no prompt, no undo. Dragging a file into a folder
that already held that name did the same, because drag-to-move goes through the same handler.

**Rule:** any filesystem move/rename must check the destination first and refuse. Guard in the
**main process**, not the renderer — that way every current and future caller is covered by one check.

## 2. Deleting was permanent
**Was:** `shadow:deletePath` used `fs.rm(recursive: true, force: true)`. A folder delete was an
instant, unrecoverable wipe. The path guard also permitted the project root itself.

**Rule:** user-triggered deletes go to the Recycle Bin via `shell.trashItem`, never `fs.rm`. If the
OS refuses, fail loudly — never fall back to a hard delete. Destructive handlers pass
`allowRoot: false` to `resolveInsideProject` so no path can ever resolve to the project folder.

## 3. Opening a project kept the old project's tabs
**Was:** `handleOpenProject` reset tabs; `openRecentProject` did not. Tabs store only a
*project-relative* path, so after switching via the Recent list, one Ctrl+S wrote the previous
project's code over a same-named file in the new project.

**Rule:** every project-switch path resets tabs, tree, active tab and selection identically. If two
functions do the same job, they must do *all* of it — divergence between them is the bug.

## 4. Binary files were opened as text and saved back corrupted
**Was:** `shadow:readFile` did `fs.readFile(path, "utf-8")` on anything. Clicking a PNG decoded it
lossily (every unrepresentable byte became U+FFFD); one Ctrl+S wrote that back, destroying the asset.

**Rule:** detect binary by *content* (NUL byte or lossy UTF-8 decode), not by an extension list —
an extension list always misses a format. Refuse to open rather than opening something unsaveable.

## 5. Running the tests wiped the real database
**Was:** `tests/conftest.py` had an `autouse=True` fixture deleting `state.json` and every row in
`projects` / `assets` / `generation_jobs`. Nothing redirected it, so it hit the real
`backend/shadow.db` and `backend/.shadow-assets/`. Both are gitignored, so there was no recovery.
It had already run at least once.

**Rule:** tests point at a temp DB and temp storage, set **before** `app.main` / `app.db` are
imported (both resolve paths at import time). `_assert_isolated()` in conftest re-checks this and
refuses to run otherwise — a guard, not a comment.

## 6. The AI feature fabricated its output when the backend was unreachable
**Was:** `shadow:generatePatch` caught every error and silently substituted a fake patch that
appended `// Shadow AI note: <prompt>` to the first file. Since the backend never defined
`/api/v1/ai/patch`, this fired on *every* request — the feature had never worked once, while
reporting success.

**Rule:** a mock may never stand in for a failed real call at runtime. Mock-first is fine as a
*development* stage; a silent fallback that presents fake output as real is not. Fail loudly with
the endpoint and the real error.

## 7. Unsaved edits were discarded without warning
**Was:** Close Tab, Close Others, Open Project, Open Recent and Ctrl+R all threw away dirty buffers
silently.

**Rule:** one `confirmDiscard(paths, action)` helper guards every path that drops buffers, plus a
`beforeunload` listener for the routes that bypass the UI entirely (browser-level reload, window
close).

## 8. Every AI request was a cold start
**Was:** each call to the AI sent the attached files and nothing else. There was no conversation
history, so a follow-up like *"make it stronger"* had no idea what "it" referred to. The feature
looked like a chat but behaved like a series of unrelated one-shot requests.

**Rule:** the CLI returns a `session_id` and accepts `--resume`. Keep it, scoped to the open project,
and clear it on project switch. Anything that presents itself as a conversation must actually carry
conversation state — otherwise the UI is lying about what it is.

## 9. Every file was declared to Monaco as TypeScript
**Was:** both editors hardcoded `defaultLanguage="typescript"`, so HTML, CSS, JSON and Markdown were
shown with error squiggles for problems they do not have, and all files shared one editor model —
which let undo history bleed between tabs.

**Rule:** derive the language from the file extension, and pass `path` to Monaco so each file gets
its own model. One shared model across every file is never right.

## 10. Stop did not stop anything
**Was:** the Council's Stop button called `child.kill()` on a process spawned with `shell: true`.
On Windows that only kills the `cmd.exe` wrapper — the real `claude` process is a *grandchild*. It
kept running (still consuming the subscription), and because it still held the stdout pipe open the
`close` event never fired, so the awaiting promise never settled and the run sat on screen saying
"Round 1 of 2" forever. Verified by clicking Stop and watching nothing happen. `terminalKill` and the
AI timeout had the same flaw.

**Rule:** never `child.kill()` anything spawned with `shell: true`. Use `killProcessTree()`, which
runs `taskkill /pid <pid> /T /F` on Windows. And test a Stop button by *pressing it* — a cancel path
that silently fails is worse than no cancel button, because it reports success. This applies to every
future spawn: timeouts, project switches and window close all need the tree kill too.

## 11. The desktop app was never in git
**Was:** all ~7,000 lines of `APPLICATION/` existed only as untracked files. No undo, no recovery.

**Rule:** new top-level surfaces get committed the day they are created. Check `git status` for
untracked source directories before starting any refactor.

## 12. "Shadow can't do that" was one line, and it was load-bearing in the wrong places
**Was:** every AI surface — council, patch seam, agent report, and the Terminal — went through one
shared `runClaudeCli`, which passed `--disallowed-tools Bash Edit Write NotebookEdit`. The ban was
correct for the three surfaces that parse the reply as JSON and apply changes through Shadow's own
reviewed apply/undo path. It was wrong for the Terminal, where it turned an agentic dev tool into
something that could only ever describe work it was forbidden to do. The complaint it produced —
*"it keeps saying it can't do stuff"* — was accurate.

**Rule:** tool access is a property of the *surface*, not of the app. A shared spawn helper must not
impose one policy on callers with different contracts. When a capability is deliberately withheld,
the comment says which caller needs it withheld and why, so the next person does not read a blanket
ban as a blanket decision — and does not "fix" it globally and silently break the JSON parsers.

## 13. A full-tool agent needs a way back, not a shorter tool list
**Was:** the safety story for the AI was "it cannot touch your disk". Once the Terminal's build agent
legitimately needs to touch the disk, that story is gone and nothing replaces it.

**Rule:** capability gets a checkpoint, not a veto. `checkpointBeforeAgentRun()` snapshots the working
tree with `git stash create` (which builds a commit object without touching the worktree, the index,
HEAD or the branch) before every build run. Two gaps are *surfaced in the UI* rather than hidden:
untracked files are not in the snapshot, and a non-repo folder gets no snapshot at all — the user is
told outright that there is no way back. A safety net you cannot see is not a safety net.

Two things verified by running them rather than reasoning about them:
- `git checkout <ref> -- .` restores the content **and stages every file it touched**. Undo must use
  `git restore --source=<ref> --worktree -- .`, which leaves the index alone. Checked on git 2.49.
- NDJSON from `--output-format stream-json` must have its tail buffer flushed on `close`. The final
  `result` event — the one carrying the summary and the cost — is the last line written, and if it
  arrives without a trailing newline a split-on-newline reader drops exactly the line that matters.

## 14. `shell: true` silently split every argument containing a space
**Was:** every spawn in `main.ts` uses `shell: process.platform === "win32"`, which flattens the argv
array into one command line for cmd.exe to re-parse. Any argument containing a space arrives at the
child as *two* arguments. Measured rather than reasoned about:

```
shell:false   ["-m","Shadow checkpoint","D:/Ai Game/Shadow"]
shell:true    ["-m","Shadow","checkpoint","D:/Ai","Game/Shadow"]
```

Two things failed from this, and neither raised an error:
- `--add-dir <folder>` for every allowed folder whose path contains a space — which in this portfolio
  is all of them (`...\Ai Game\...`). "Let the agent read another folder" had been quietly doing
  nothing for those paths.
- `git stash store -m "Shadow checkpoint" <sha>`, so the build agent's pre-run snapshot was created
  but never anchored in the stash reflog. `git stash list` was empty and the commit was collectable.
  The UI showed a checkpoint and a restore command that would eventually stop resolving.

**Rule:** under `shell: true`, every argument that is not a literal you typed yourself goes through
`shellArg()`. Paths and user text are never safe to interpolate raw. This is the same defect class as
passing a prompt as argv (entry 13) — cmd.exe re-parsing what it is handed — and it fails silently
rather than loudly, so it will not be found by watching for errors. Verify by printing the child's
`process.argv`, not by reading the spawn call.

**Also:** a capability that "looks fine in the UI" is not verified. The checkpoint line rendered
perfectly for a snapshot that was not being kept. Check the effect on disk, not the label.

## 15. A tool that indexes AI sessions indexed its own indexing
**Was:** the Sessions surface digests every Claude Code transcript under `~/.claude/projects`. Each
digest is itself a `claude -p` call, so it *writes a transcript to the same directory*. Sweeping 108
sessions would therefore create 108 new sessions, which the next sweep would treat as work to
summarise, which would create 108 more. A feedback loop that spends real allowance and never
converges — and it would have looked like the feature working, just with a session list that grew
every time you used it.

**Rule:** any process that both reads a store and writes to that store must record what it wrote and
exclude it (`sessions/store.ts` → `internal-runs.json`). Ask of every new indexer: *does running this
create the thing it consumes?* Note the ledger is separate from `shadow-runs.json` on purpose — a
council run IS work the owner wants listed; a digest call is not. One flag could not express both.

## 16. A byte prefilter that matched the lines it was meant to skip
**Was:** the transcript scanner tests raw bytes before decoding, to avoid `JSON.parse` on hundreds of
megabytes. The ported filter included `"timestamp"` and `"cwd"`. Measured on a real 45 MB transcript:

```
"timestamp"           9001 / 11901 lines  (76%)
"type":"user" + assistant   7685 / 11901  (65%)
```

`"timestamp"` matches nearly everything — including the `file-history-snapshot` and `attachment`
lines that are the *largest in the file*. The optimisation was decoding the fat lines to learn
nothing from them.

**Rule:** a prefilter's value is what it EXCLUDES, and that is a measurement, not a guess. Count hits
against a real file before trusting one. Narrowing was only safe because the dropped fields are
themselves carried on the lines still being read (8,446 `"cwd"` hits vs 7,685 message lines) — verify
that before removing a marker.

## 17. The surface scrolled instead of the list
**Was:** `.sessions-surface` is a grid with a `minmax(0,1fr)` row for the list, and the list had its
own `overflow-y`. With 109 rows the surface still grew past the viewport and the *whole app* scrolled,
taking the header and the surface-switch tabs off screen. Nothing errored; the app just lost the way
back out of the tab you were in.

**Rule:** a full-height surface sets `overflow: hidden` on itself and on its body, so the only thing
that can scroll is the pane that owns its own scrollbar. And: check a list view at REAL row counts. It
looked correct at zero rows and at six, and only broke at a hundred — which is the only count that
matters, because that is what the user has.

## 18. "Opened a console" was printed for a console that failed to open
**Was:** the external-handoff button built its command as arguments to cmd's `start`:

```
cmd /c start "Shadow handoff" cmd /k type "<path>" | claude
```

It failed with «Windows cannot find 'handoff\'» — `start` treats a leading quoted token as the
window TITLE, Node re-quotes arguments containing spaces on the way to cmd.exe, and the two
conventions disagree, so the title was re-parsed as a command. Same defect class as entries 13 and 14.

The worse half: **Shadow reported success anyway.** `start` returns 0 as soon as it has handed off, so
`spawn` did not error and the UI printed "Opened a Claude Code console … with the handoff loaded"
while an error dialog sat on screen behind it.

**Rule:** for a detached spawn, exit code 0 is not evidence the thing came up — it is evidence the
launcher exited. Wait for `error`/`exit`, treat a non-zero code as a failure, and always include the
manual fallback (the handoff path and the command to run it) in the error, so a failed launch still
leaves the user something they can use. And: put the whole command in a script file, passing only that
file's path on a command line. Any argument list containing quotes, spaces and a pipe will eventually
be re-parsed by cmd.exe into something else.

## 19. Piping a prompt into the CLI silently makes it non-interactive
**Was:** the same launcher fed the handoff in with `type file | claude`, which is quote-proof and
therefore looked like the right answer. It is not: with stdin redirected, the CLI runs in print mode —
it answers once and exits, dropping the user at a bare `cmd` prompt. The button is called "Open in
Claude Code" and its entire purpose is a console the owner can keep typing in, so this defeated the
feature while appearing to work (a window did open, and a real agent did run in it).

**Rule:** stdin is not a free channel — redirecting it changes the CLI's *mode*, not just its input.
When a session must stay interactive, pass the prompt as an argument. To keep that argument safe, pass
a POINTER rather than the text: write the payload to a file and hand over a short fixed sentence
naming it, with the path arriving via an environment variable (`cmd` expands `%VAR%` inside double
quotes, so a path with spaces stays one argument). User-typed text then never touches a command line
at all. Verify by looking for the CLI's interactive trust prompt, not by checking a window exists.

## 20. The handoff was built, paid for, and thrown away
**Was:** `cnt <task>` with `target: "here"` summarised the relevant prior sessions — real digest calls,
real allowance — then cleared `aiSessionId`, `councilSessionId` and `agentTaskSessionId` so the next
run would be a cold start. The built prompt was returned to the renderer, shown as a receipt, and
dropped. Nothing held it. The Terminal then printed *"Shadow's agent memory was cleared and seeded
with this handoff"* — it was cleared, not seeded, and the next thing typed into the Build box began
with no prior context at all. Only the `-x` (external console) path actually used the text.

The feature's entire purpose is to avoid re-explaining prior work, so this failed at exactly the thing
it existed to do, while reporting success and charging for the digests.

**Rule:** clearing state is not the same as seeding it, and a message that claims the second must be
able to point at the code that does it. When a build step costs money, follow its output to a
consumer before believing the feature works — `grep` for the field name and check it is *read*
somewhere, not just returned. `pendingCarryOver` now holds it until a surface consumes it exactly
once. Same family as entry 18: the UI asserted a state the code never produced.

## 21. Rule 15 applied to digests but not to everything else Shadow spawns
**Was:** entry 15 established that a process which reads a store and writes to it must record what it
wrote. It was applied to digest and relevance calls. But *every* `claude -p` Shadow runs writes a
transcript into the same `~/.claude/projects` the Sessions surface reads — and only the council
AUTHOR was being marked. Council reviewers were not, and they are the worst case: each runs with
`resume: null` on purpose (independence is the feature), so one reviewed round spawns **three** fresh
transcripts, none of which any human will ever read. Unmarked they are listed as separate work and
become digest-eligible, so the sweep spends allowance summarising Shadow arguing with itself. The
Terminal's build agent was unmarked too, so its runs — which ARE the owner's work — showed up as
anonymous "agent" sessions.

Seen directly rather than reasoned about: a `cnt` receipt in the council listed three carried-over
candidates reading *"review task, not implementation"*. Those were reviewer transcripts from one
earlier turn, already in the store.

**Rule:** when you add a new caller of the CLI, decide which ledger it belongs in *in the same
change* — `markShadowRun` for work the owner wants listed, `markInternalRun` for plumbing. Neither is
the default, and "it is just one more spawn" is how a store fills with noise it then pays to
summarise. Ask entry 15's question of every new caller, not once per subsystem.

## 22. A CLI call nobody held a handle to outlived the app
**Was:** `runClaudeCli` only gave the caller a handle if it passed `onSpawn`, which in practice only
the council did. A patch, an agent report or a session digest had no handle anywhere, so no shutdown
path could reach them. Found by looking rather than reasoning: after Shadow had closed, a `claude`
process it started was still alive — orphaned under its `cmd.exe` wrapper, burning ~0 CPU, waiting on
a reply that no longer had anywhere to go, and still costing quota.

**Rule:** a spawn's lifetime is the app's problem, not the caller's. `liveCliChildren` now holds every
CLI child and `killAllCliChildren()` runs last on `before-quit`, after the specific killers, because
overlap is free (tree-killing a dead pid is a no-op) and forgetting is not. Never make cleanup depend
on a caller opting in — the caller who forgets is exactly the one that leaks.

## 23. The warning arrived after the point it was warning about
**Was:** a full-tool run was started against the wrong project — Shadow restores the last-opened
project, which was the owner's real 130,000-file Unity folder, not the throwaway repo the previous
run had used. The folder is not a git repo, so no checkpoint was possible, and the UI correctly said
so in red: *"The agent can write and delete files here with no way back."*

It said it **while the agent was already running.** A warning printed after the irreversible thing has
started is a log entry, not a safeguard. Nothing was damaged — the run was killed during its reading
phase and a modified-in-the-last-15-minutes sweep over all 130,889 files came back empty — but that
was luck and speed, not design.

**Rule:** "no way back" is a precondition, not a notice. A full-tool run in a folder that cannot be
checkpointed must not start until the user has said yes to that specific fact, and any automated
driver must assert which project is open *before* starting a run rather than assuming it is whatever
it was last time. House law is disarm in code, not in prose — a red paragraph is prose.

## 24. A stale `Shadow.exe` made the Council author look neutered
**Was:** the Council author agent reported that `Write`, `Edit` and `Bash` were **absent from its
toolbelt entirely** — not denied, unregistered — and that paths outside the project were refused with
*"Claude Code is running in don't ask mode."* It read its own situation correctly and concluded the
agent definition or allowlist was wrong.

The source was **fine**. `main.ts:1237` passes `"full"`, and `toolPolicyArgs` correctly maps that to
`--permission-mode bypassPermissions` with no tool bans. But `Shadow.exe` was built **2026-07-29
20:00**, roughly eight hours *before* that fix landed at **03:53** the next morning. The packaged
`app.asar` still contained the pre-fix call:

```js
runClaudeCli(authorPrompt, projectRoot, councilSessionId, track)   // no policy argument
```

With the policy argument missing it fell through to the parameter default, `"readonly"`, which emits
exactly `--permission-mode dontAsk --disallowed-tools Bash Edit Write NotebookEdit`. Both reported
symptoms, precisely. `toolPolicyArgs` did not exist in the packaged build at all.

This is the same shape as the old two-Shadow-folders trap, which we believed we had closed by deleting
the stale install — but it survives *inside a single folder*, as source-versus-exe. A stale build never
announces itself: it launches, the UI looks current, and only the behaviour is old. Worse, the agent
diagnosing it runs *inside* the stale build, so it reports on a world where the fix genuinely does not
exist. Its report is simultaneously accurate and completely misleading, and it will keep sending you
back to code that is already correct.

**Rule:** when a fix appears to have had no effect, **verify what is actually running before
re-diagnosing the code** — compare the build artifact's mtime against the newest source file, and grep
`app.asar` for the fixed line. Never conclude "the source is wrong" from the behaviour of a binary you
have not dated. Disarmed in code, not prose: `Run-Shadow.bat` now compares the newest `src/**` and
`renderer/**` `.ts`/`.tsx` mtime against `Shadow.exe` and requires explicit confirmation before
launching a build older than its source.

## 25. The build script deleted the app before checking it could build a new one
**Was:** `scripts/build-portable.ps1` did `Remove-Item Shadow.exe -Force` **before** running
electron-builder. A single invalid key in `package.json`'s `build.extraResources` — schema validation,
caught in under a second — was enough to abort the build. The old exe was already gone, so a config
typo turned straight into *"Shadow is not installed any more."* There was no fallback and nothing to
launch.

It also hid itself: `npm run build:exe 2>&1 | tail -12` reports the exit code of `tail`, not of the
build, so the failure was reported as **exit 0**. The only evidence was the stack trace scrolled past
in the output.

**Rule:** never destroy the last known-good artifact before its replacement exists. The script now
`Move-Item`s the exe to `Shadow.exe.previous`, builds, and **restores it if the build fails**, deleting
the backup only once a new exe is confirmed on disk. It also checks `$LASTEXITCODE` and warns when the
builder errored but an exe somehow exists. Corollary for anyone running builds through a pipe: a piped
command's exit status is the *last* command's — check the artifact, not `$?`.

## 26. An agent's Unity launches stole focus mid-game, and Shadow could not hide them
**Was:** the council author was running Unity batch captures in a loop
(`Unity.exe -batchmode -executeMethod SceneShot.CaptureBatch -quit`) while the owner played Rocket
League. Every launch opened a console window that took the foreground, knocking the game out of
exclusive fullscreen — **ten launches in eight minutes**, several lost matches. Focus never returned
to the game; it fell through to Shadow's own window, which made Shadow look like the culprit.

Three plausible causes were tested and disproved before the real one showed up, and each wrong turn
is worth keeping:

1. **"A spawn is missing `windowsHide`."** Every spawn in `src/` already had it.
2. **"The CLI chain flashes consoles."** A faithful repro — Shadow's exact spawn shape, hosted inside
   *Electron* so the parent is a console-less GUI process — ran a full `claude -p` doing Bash and git
   work and produced **zero** console windows. An earlier version of that same test run from
   PowerShell proved nothing: a console-owning parent lends its console to children, so the condition
   under test could never occur. **A repro of a console bug must be hosted by a GUI-subsystem parent.**
3. **"Shadow's window is calling `focus()`."** No `focus`/`show`/`moveTop` in `main.ts`, no
   `window.focus()` in the shipped bundle. Shadow was the *victim* of the Z-order fall-through.

The cause is a Windows rule that is easy to read past: **`CREATE_NO_WINDOW` — what `windowsHide: true`
becomes — is ignored for programs that are not console applications.** Unity is GUI subsystem, so its
`AllocConsole()` always builds a fresh, visible console. The `STARTUPINFO` `SW_HIDE` that *would*
hide it only reaches a **direct** child. Measured against a GUI-subsystem stand-in that calls
`AllocConsole` exactly as Unity does:

| spawn shape | console window? |
|---|---|
| direct child, `shell: false`, `windowsHide: true` | none |
| `shell: true` (cmd.exe in the middle) | **yes** |
| `conhost.exe --headless -- tool` | **yes** |
| anything with git-bash in the chain | **yes** |
| from bash, via `Start-Process -WindowStyle Hidden` | none |

So Shadow cannot fix this from its own spawn flags at all: the process that calls `CreateProcess` for
Unity is the agent's `bash`, four levels down, and only that call can hide it.

**Rule:** a GUI-subsystem program must be launched by the process that can set its show state — for
an agent, that means its own command line. `windowsToolBriefing()` in `main.ts` now tells every agent
that runs commands (council author and the build agent) to wrap GUI programs in
`powershell -NoProfile -Command "Start-Process ... -Wait -WindowStyle Hidden"`, and says why, so it
does not get "simplified" back out. Console tools (git, npm, node, python) are unaffected — they
inherit Shadow's hidden console and never create a window. Corollary: **`windowsHide` is not a
guarantee.** It covers console programs only; for anything GUI, hiding is the caller's job.

## 27. Two paths for the same folder, compared as raw strings, emptied a list that was full

The Council's new "Recent work" rail filters sessions to the open project:

```ts
(session.cwd ?? "").toLowerCase().startsWith(project.rootPath.toLowerCase())
```

It showed **"Nothing recorded in Shadow yet"** for a folder with 40 sessions in it. Nothing threw,
nothing logged, and the message was a plausible sentence — so the natural reading was "the scan
hasn't finished" or "these sessions aren't tagged", not "the comparison is wrong".

The cause: `toProjectSummary()` stores `rootPath` **verbatim**, exactly as it arrived. It is never
re-resolved. So a root that reaches it as `D:/projects/Shadow` stays forward-slashed,
while every transcript on disk records `cwd` as `D:\projects\Shadow`. Same folder, zero
matching characters after the drive letter. Two paths that name one directory are not one string:
slash direction, drive-letter case, and a trailing separator all vary by who produced the path
(`openPathAsProject` resolves and yields backslashes; a settings file, a drag-drop, or an env var may
not).

The second, quieter half: `startsWith` with no boundary check. `.../Shadow` matches `.../Shadow2`
and `.../ShadowBackup`, so the fix for the first bug would have introduced a subtler one.

**Rule:** never compare two filesystem paths as raw strings. Normalise both sides — unify separators,
lowercase (on Windows), strip the trailing separator — and require a separator boundary for
containment: `a === b || a.startsWith(b + "/")`. `isInside()` in `Council.tsx` is that function; use
it rather than writing the comparison inline again.

**Corollary, and the reason this cost time at all:** a filter that finds nothing must not be able to
look like an empty dataset. The rail now distinguishes "still reading" from "read, and this folder is
genuinely empty" and offers the wider scope in the same sentence — but the real lesson is that a
silent-zero filter is a bug that reports itself as a fact.

---

## 28. `npx tsc --noEmit` is not a build, and a stale `dist/main.js` lies convincingly

Adding `--dir` argv parsing to the main process, I typechecked both tsconfigs, launched the app,
and watched it open the *previous* project. The obvious reading was "my argv parsing is wrong" —
Electron eats some switches, `Start-Process` mangles quoting, `parseLaunchIntent` has a bug. All
plausible, all wrong. The main process was still running `dist/main.js` from a build made **before
the feature existed**, because `--noEmit` had verified the source and written nothing.

The renderer does not have this failure mode in the same way — Vite rebuilds on `npm run build` and
the window reloads — so the habit of "typecheck, then look" is safe there and quietly unsafe here.

**Rule:** `npx tsc --noEmit` proves the source compiles. It does not put the source in front of the
app. Before drawing ANY conclusion from a main-process behaviour, run `npm run build` — and when a
main-process change appears to have had no effect at all, suspect the build before the code.

---

## 29. `PrintWindow` returns a black rectangle for this app, and screenshotting the desktop is worse

Visual verification is house law (HANDOFF §1, mistake 3). Two obvious ways to do it both fail here:

- **`CopyFromScreen` on the primary screen** captured Minecraft, fullscreen, because the owner had
  it in the foreground. The HANDOFF already warns about this; it is not hypothetical, it happened
  on the first try of this session.
- **`PrintWindow` on Shadow's window handle** — the standard way to capture an occluded window —
  returned the title bar and a black client area, even with `PW_RENDERFULLCONTENT`. Chromium
  composites through the GPU and there is nothing in the window's device context to copy.

Bringing Shadow to the foreground would work, and is exactly what landmine 26 says not to do: the
owner games fullscreen and a focus steal is a real cost to him, not a cosmetic one.

**Rule:** screenshot Shadow through the DevTools protocol, not the desktop. Launch with
`--remote-debugging-port=<port>`, then `Page.captureScreenshot` over the page target's websocket.
It captures the real rendered page, works while the window is completely covered, and never touches
focus. `Runtime.evaluate` on the same socket also drives the UI and reads state, which is how the
subagent feed was verified without a human clicking anything.

Two operational notes, both of which cost a retry:
- **Pick a fresh port after every kill.** The old port stays bound long enough that the next launch
  logs `bind() returned an error` and starts with no devtools at all — the app runs, so the failure
  looks like a CDP bug rather than a port conflict.
- **`taskkill /F` loses unflushed `localStorage`.** Chromium batches those writes to disk. A turn
  that was definitely on screen can be genuinely absent after a force-kill, which reads exactly like
  a persistence bug in the transcript code. It is not. Close the window, or discount the evidence.

---

## 30. `Start-Process -ArgumentList` does not quote its arguments — landmine 14, one layer out

`shellArg()` exists because `shell: true` on Windows splits an unquoted path at its spaces
(landmine 14). The same bug reappeared from the other direction while *testing* that fix:

```powershell
Start-Process -FilePath $exe -ArgumentList $appDir,'--dir','D:\projects\VESPER'
```

Windows PowerShell 5.1 joins that array with spaces and adds no quotes, so Electron received
`D:\projects\Ai` as its app path and exited instantly — with **empty stdout and empty stderr**,
because a GUI-subsystem process has nowhere to write. A silent immediate exit reads as a crash in
your own startup code.

**Rule:** every project in this portfolio lives under a path with a space in it, so assume any
argument containing one will be split unless something quoted it. Launch from a shell that quotes
(`bash`, or `cmd` with explicit quotes) rather than trusting `-ArgumentList`, and never diagnose a
GUI process from the absence of output on its pipes.

---

## 31. The conform offset and the hide threshold are the same number in disguise

The character assembler pushes each worn layer out from the body by
`0.0035 × layer` metres so that underwear, shorts and a coat do not z-fight on one surface. It then
deletes body faces sitting under a garment, using a `--hide-threshold` of 8 mm.

Those two numbers were written in different functions by different reasoning and never compared.
A `top` is layer 3, so the conform moves it **10.5 mm** away — every body vertex it covers is now
farther from it than the 8 mm threshold meant to detect coverage. The mask matched **zero faces and
reported success**: no error, no warning, valid GLB, clothing that a body pokes straight through.

`hide_covered_faces()` now takes the offset and adds it back, because it is measuring a gap the
pipeline created itself.

**Rule:** when one step displaces geometry by a constant and a later step tests a distance against
that same geometry, the two constants are coupled whether or not the code says so. Pass the
displacement forward rather than letting the second step measure a gap the first one manufactured —
and be suspicious of any mask, filter or hit-test that returns exactly zero without complaining.

---

## 32. Fitted loose, it never touched the body — so nothing conformed and nothing hid

The same zero-face result had a second, independent cause, which is why fixing the threshold alone
did not clear it.

Garments were fitted to **1.14× the body's width** on the reasoning that clothing should be roomier
than skin. But the conform is a shrinkwrap in `OUTSIDE` mode, which by definition moves only
vertices that are *inside* the target. A garment sitting 31 mm clear of the torso has no vertices
inside it, so the conform was a no-op, the garment hovered, and the hide-mask correctly found
nothing underneath it.

The pipeline only works in the other order: **fit snug enough to interpenetrate, then let the
conform resolve the intersection outward.** `GARMENT_LOOSENESS` is 1.02 and documented at the
constant.

A third thing was hiding both: the self-test dressed a stock Blender cylinder, whose 24 side faces
each span its **entire height**, so no face could ever lie wholly under a shirt even with everything
else correct. Real input is a 200k-triangle generated mesh.

**Rule:** a test whose geometry is unlike the real input is not testing the real path — subdivide
the stand-in until it resembles what will actually arrive. And when a stage's precondition is
"the inputs overlap", assert the overlap rather than assuming the fit produced it; a no-op
shrinkwrap and a perfect shrinkwrap are indistinguishable from their return value.

---

## 33. Counted before the next step deleted some — the triangle report was of a mesh that never shipped

`character_assemble.py` records each part's triangle count as it places it. Garments are placed
last, and placing one **deletes body faces underneath it**.

So the torso was counted at 5,276 triangles, then the shirt removed 2,640 of them, and the result
still said 5,276. The reported total was 74,924 for a character that exported at 72,284. Nothing
errored; the number was simply of a mesh that had never existed by the time anything read it — and
that number is stored in the library as `finalTris` and shown in the UI as the budget the owner is
deciding against.

The fix is one loop after the assembly finishes that re-counts every placed part.

**Rule:** a measurement taken mid-pipeline is only valid if nothing downstream mutates what was
measured. When a later stage can reach back and change an earlier subject — a mask, a boolean, a
merge, a cleanup pass — take the measurement at the end, not at the point that feels natural.
Metrics of intermediate state read exactly like metrics of final state, and nothing distinguishes
them at the call site.

---

## 34. The packaging filter shipped the script and not the table it reads

`character_slots.json` is the single source of truth for the character slot table — the Blender
assembler reads it, and so does the UI, specifically so the two cannot drift.

`package.json`'s `extraResources` filter was:

```json
{ "from": "scripts/blender", "to": "scripts/blender", "filter": ["**/*.py"] }
```

Written when everything in that folder was Python. A packaged build would therefore have contained
`character_assemble.py` and **not** the JSON it cannot run without — working perfectly in dev and
failing only in the installed app, which is the most expensive place to find anything.

**Rule:** an extension-based packaging filter is a whitelist that silently ages. The moment a script
gains a sibling of a different type — a JSON table, a shader, a `.txt` template — the filter drops
it without a word. Prefer filtering by folder, or re-read the filter whenever you add a file next to
something that ships. And when a script loads a data file, make its absence a named error
(`load_slot_table()` says "check the extraResources filter") rather than a stack trace.

---

## 35. Entry 6 again, in the sprite pipeline — the mock stood in for the real thing at runtime

`advance_job` ran the sprite export like this:

```python
try:
    export_result = run_real_sprite_pipeline(...)
except Exception:
    export_result = run_mock_sprite_pipeline(...)
```

`run_mock_sprite_pipeline` returns **`https://picsum.photos/seed/sheet-<job>/...`** — a random
stock photograph off the internet — as the sprite sheet, and sets `status="succeeded"`.

So a missing OpenCV, an unreachable video URL, a malformed key colour, or a tolerance that keyed
away the entire subject all produced the same thing: a green job, an export bundle, and a
photograph presented to the owner as their animation. The bare `except Exception` caught the class
of failure the user most needed to hear about and replaced it with plausible output.

**Entry 6 already wrote this rule**, about `shadow:generatePatch`, and it did not travel: the rule
lived in a doc about the AI seam rather than as something checked when a new subsystem grew its own
mock. Mock-first is a legitimate development stage — the conventions in `CLAUDE.md` say so — but the
switch has to be *deliberate*. It is now `SHADOW_SPRITES_MOCK=1`, and nothing silently selects it.

**Rule:** a mock may never be selected by a failure. If a mock path exists, the thing that chooses it
is a flag someone set, never an exception someone swallowed. When you add a mock to a new subsystem,
grep for `except` around the real call in the same change — this is the second time the same defect
has been written in this repo, so assume it will be written a third time somewhere else.

---

## 36. A mask that matched nothing was handed the largest possible bounding box

Subject detection took the bounding box of the non-transparent pixels per frame, and for a frame
where the key had matched *everything* it did this:

```python
if len(xs) == 0 or len(ys) == 0:
    boxes.append((0, 0, frame.shape[1], frame.shape[0]))   # the ENTIRE video frame
    continue
```

Every frame is then padded to `max(width)` and `max(height)` across all boxes. A full-frame box wins
that `max()` against every real one — so **one over-keyed frame silently resized the whole sheet to
full video resolution**, leaving each sprite a small island in the middle of a 1920×1080 cell, and
the export reported success. The natural reading of the result is "the generator produced a bad
video", not "one frame poisoned the sizing of all the others".

This is landmine 31's shape from the other direction: there, a mask matching zero faces reported
success; here, a mask matching zero pixels reported the largest possible answer.

**Rule:** an empty result is not a degenerate value of a normal result, and must never be encoded as
one — least of all as the *extreme* value, where it will dominate any `max()`, `min()` or union
downstream. Return `None`, exclude it, and say which input produced it. `align_frames` now rejects
such frames by index with a reason, and raises outright if every frame is empty.

---

## 37. The aligner deleted the animation it was aligning

"Auto alignment" cropped each frame to its own bounding box and re-centred it on a common canvas.
For a walk cycle that is destructive in the most literal sense: **the vertical bob IS the
frame-to-frame change in where the subject sits**, so centring every frame independently flattens it
into a character sliding along a rail. The sprites all looked correct in isolation; only playback was
wrong, which is the hardest place to notice it.

The same crop drifts the pivot. When the subject's *size* changes between frames — wings extending, a
crouch — the bounding box changes with it, so the body's centre moves relative to the frame's centre.
Anything rotating the sprite about the frame centre then wobbles on its own axis.

Both were reachable from the published contract, so both had to stay reachable: the three legacy
modes are unchanged and `test_both_centered_flattens_vertical_motion` asserts they still behave the
old way, so a future "unification" cannot quietly restore the flattening for everyone.

**Rule:** a normalisation step must be told what it is allowed to normalise away. Alignment defaults
to `union` — one shared crop rectangle, every pixel keeping its true position relative to every other
frame — because the safe default for a transform over a *sequence* is the one that preserves the
differences between its elements. And when you keep a legacy mode for compatibility, write the test
that pins its old behaviour, or "compatibility" is just a name.

---

## 38. A number the mock invented travelled as if it had been measured

The sprite metadata, the export response and the generated JS snippet all carried
`offset_y: -12`. Nothing computed it. It was written in `run_mock_sprite_pipeline` as a
placeholder, copied verbatim into the real pipeline, and shipped to every consumer as alignment
data — while `README.md` phase 4 says this field should be *"pixel offsets required for consistent
animation placement"*, i.e. the thing that was never implemented.

Downstream that is worse than a wrong number, because it is an *unfalsifiable* one: a game applying
it sees sprites sitting 12px off, assumes its own maths is wrong, and adds a compensating −12
somewhere else. Two errors that cancel are much harder to remove than one that does not.

Found alongside it: `create_mask_preview` never took `masking_mode` at all, so the preview was
always a chroma key regardless of what was selected — and it ran its own private copy of the keying
maths, so tuning tolerance against the preview tuned it against a different algorithm than the one
that would build the sheet.

**Rule:** a field that claims to be measured must be traceable to the code that measures it. When a
mock and a real implementation produce the same shape, the mock's *placeholder values* are the
dangerous part — grep for them in the real path before shipping. And a preview must call the same
function as the thing it previews; two implementations of one algorithm is not a preview, it is a
second opinion.

---

## 39. The bridge is `window.shadowApp`; the docs all say `window.shadow`

Every doc in this repo writes the renderer API as `window.shadow.forgeMesh(...)` —
`asset-forge.md` does it five times. The preload actually exposes it as **`window.shadowApp`**.
The docs are using a shorthand; nothing says so.

A new surface written from the docs therefore compiles perfectly and fails at runtime with
`Cannot read properties of undefined`. Nothing catches it earlier, because the idiomatic way to
reach the bridge in a renderer file is a cast:

```ts
const bridge = () => (window as unknown as { shadow: Bridge }).shadow;   // typechecks. always undefined.
```

The cast asserts the shape, so TypeScript has nothing to object to, and `npm run build` is green.
The existing surfaces get this right only because they were written by copying a neighbour
(`CharacterForge.tsx` line 1: `window.shadowApp`), not from the docs.

Found by driving the built app over CDP (landmine 29) and evaluating
`Object.keys(window).filter(k => /shadow/i.test(k))`, which answered `shadowApp`.

**Rule:** reach the bridge through the **typed global**, never a local cast — `window.shadowApp` is
declared, so a typo is a compile error and a wrong name is impossible. If you catch yourself writing
`window as unknown as {...}` to get at something the app already exposes, you have just disabled the
only check that would have caught this. And a renderer surface is not verified by a green build:
evaluate one of its bridge calls in the running app before believing it is wired.

---

## 40. There are two `Documents` folders and the install is in the other one

Voice Forge looks for the IndexTTS2 checkout in `Documents\index-tts`. That sentence has two
different answers on this machine:

```
os.homedir() + "\Documents"        C:\Users\you\Documents            <- git clone lands here
app.getPath("documents")           C:\Users\you\OneDrive\Documents   <- Shadow Library lands here
```

OneDrive redirects the *shell's* Documents folder, but `C:\Users\you\Documents` still exists on
disk and is what a terminal means by `~/Documents`. So a tool installed by typing `git clone` goes
to one, and everything Shadow writes with `app.getPath("documents")` goes to the other — **in the
same session, on the same machine, both called "Documents"**.

A discovery list that checks only one of them finds the install about half the time, depending on
whether the owner installed the tool from a terminal or Shadow created the path. The failure is the
worst shape: "IndexTTS2 is not installed" pointing at a folder the owner can see is right there.

`findComfyInstall` has the same single-candidate bug latent in it today; it has not bitten only
because that install happens to be elsewhere.

**Rule:** when resolving a well-known user folder, check **both** `os.homedir()`-derived and
`app.getPath()`-derived paths — they are not the same folder and neither is wrong. And when a
discovery probe reports "not found", print the paths it actually looked at: the bug is invisible
until the list is on screen next to the folder that exists.

---

## 41. A subprocess whose stdout is a protocol must own stdout exclusively

`voice_worker.py` talks to Shadow in JSON lines over stdout. It imports torch, transformers,
`indextts`, tqdm and BigVGAN — and every one of those prints. During the first run this landed in
the log:

```
>> Failed to load custom CUDA kernel for BigVGAN. Falling back to torch.
Fetching 6 files:  17%|#######   | 1/6 [00:00<00:03,  1.28it/s]
```

Either line arriving between two protocol messages corrupts the stream. Worse, it corrupts it
*intermittently* — only when a model happens to warn, only on a first run, only when a download is
slow enough to tick — which is the hardest possible failure to reproduce and the easiest to blame
on the consumer.

Discipline does not fix this. "Remember to print to stderr" holds until an upstream library you do
not control decides to print something new on an upgrade.

**Rule:** capture the real stdout **once, at import, before anything else runs**, and immediately
repoint `sys.stdout` at stderr:

```python
_PROTO = sys.stdout
sys.stdout = sys.stderr        # everything that is not protocol now goes somewhere harmless
```

Protocol writes go to `_PROTO`; everything else is misrouted *by construction*. Also keep
`ensure_ascii=True` on the JSON: the alternative writes raw UTF-8 into a pipe whose encoding is the
Windows console codepage, and one non-ASCII character in a voice name raises `UnicodeEncodeError`
from inside the library — killing a synthesis that had already succeeded.

Corollary for the reader: a chunk boundary can fall inside a JSON line, so buffer and split on
newlines. Parsing per `data` event drops results at random under exactly the same conditions.

---

## 42. Node refuses to spawn a `.cmd`, and `shell: true` is not the fix

The CDP verification harness spawned `node_modules/.bin/electron.cmd` and died with:

```
Error: spawn EINVAL
```

Node 20+ blocks spawning `.cmd`/`.bat` without a shell (it was a command-injection fix). The
obvious repair is `shell: true` — which is **landmines 14 and 30 all over again**: under a shell
every argument containing a space is re-split, and every path in this portfolio has one.

**Rule:** spawn the **real executable**, not the shim. `node_modules/electron/dist/electron.exe`,
not `.bin/electron.cmd`. The `.bin` entry exists for shells; a program that already knows the path
does not need it, and reaching for `shell: true` to get at a shim trades a clear error for a silent
argument-splitting bug.

---

## 43. Shapes matched, so nobody noticed the model was handed the wrong network's features

Hunyuan3D produced rubble for a day — flat slabs, shredded noise, valid GLBs with plausible
triangle counts and no error at any stage. Shadow's code had not changed; **ComfyUI had gone
0.9.2 → 0.30.0 underneath it.**

The root cause was in our graph the whole time and only became fatal after the upgrade. We loaded
`clip_vision_l.safetensors` through a separate `CLIPVisionLoader` and fed it to the model. But
`hunyuan_3d_v2.1.safetensors` **contains its own image encoder**, which `ImageOnlyCheckpointLoader`
hands out as its second return value — and that is the encoder the model was trained against.

It never errored, because CLIP ViT-L/14 is 1024-wide, the model declares `context_dim: 1024`, and
`Hunyuan3Dv2Conditioning` passes `last_hidden_state` through with **no projection**. Every shape
lined up while the model received features from a network it had never seen.

Worse, the repo had written the mistake down as a *lesson*: `asset-forge.md` said Hunyuan3D "needs
a 1024-wide vision encoder — `clip_vision_l`, not SigCLIP". SigCLIP threw a shape error, CLIP-L
silently did not, and **matching the shape was mistaken for matching the model**. A documented trap
sent every later reader past the real bug.

**Rule:** a tensor whose dimensions fit is not evidence that it is the right tensor. When a model
ships bundled weights, use the bundled ones — a loader that returns `(MODEL, CLIP_VISION, VAE)`
together is telling you they belong together. And when a third-party program is the floor you stand
on, its **own reference workflow is the specification**: ComfyUI shipped
`blueprints/Image to Model (Hunyuan3d 2.1).json` in the install, and reading it answered in one
minute what four generation runs could not.

---

## 44. Every stage said ok, so a day was spent looking in the wrong subsystem

Nothing in the chain could distinguish a reconstruction from rubble. `forgeImage` returned a
beautiful image, `forgeMeshFromImage` returned 218,608 triangles — squarely in the range this
repo's own docs call healthy — and `forgeMesh` decimated and baked it without complaint. The first
thing to notice was a human rendering the GLB.

Three checks now exist because of this, each of which turns a silent failure into a sentence:

- **Background-as-geometry.** Detected by *vertex density at an extreme*, not by flatness: share of
  vertices in the outermost 1/64 slice was 49.8% for a back wall and 62.2% for a ground plate,
  against 13.9% for a plain cylinder's own flat caps.
- **Collapse to a bas-relief.** 97.6% of a statue torso's vertices lay within the outer 10% of its
  depth. Nothing to salvage, so it is reported rather than cut away.
- **A missed triangle budget.** Blender's collapse decimator will not collapse edges that would go
  non-manifold, so on a degenerate mesh it stops early and *reports success* — 3,000 requested,
  **522,864** delivered.

**Rule:** a pipeline whose stages only report "did I run" cannot tell you "did it work". Any stage
whose output can be plausible-but-wrong needs one cheap measurement of the thing itself, chosen by
measuring good and bad cases and picking a threshold in the gap — not by intuition. Two earlier
guesses here (a shell count; a tight crop) were both wrong and both cost a cycle, because they were
reasoned about instead of measured.

---

## 45. The fix's own first version quietly broke the triangle budget

`drop_background_slab` began by keeping everything directly under the subject and deleting only what
jutted past it. Tidy, and wrong: it leaves a ragged, many-holed boundary, and Blender's collapse
decimator will not collapse across non-manifold edges — so a 6,000-triangle budget came back at
**77,594** and reported success. Slicing the whole band off in one flat cut leaves a single clean
planar opening, which decimates normally.

**Rule:** when removing geometry, prefer one clean cut to a precise cut-out. Downstream operations
care far more about the boundary being simple than about how much was kept, and "I removed exactly
the right vertices" is worth nothing if the result cannot be decimated.

---

## 43. A GPU benchmark measures the whole machine, not the code

Voice Forge was measured at **5–8 s a line** and that number went into the docs, the code comments
and `CLAUDE.md`. An hour later the same code, same worker, same model and the *same reference clip*
took **75 s a line**. Nothing had changed in the repo.

Rocket League was running. The card was at 96% utilisation with another process on it.

The first instinct was to blame the input that *had* changed — the owner supplied a 12.7 s reference
clip where testing had used a 3 s one, so "longer prompt, slower inference" was an appealing story.
It was wrong, and one batch settled it: three reference lengths, one model load, identical
conditions.

| reference | same line |
|---|---|
| 3 s | 75.0 s |
| 6 s | 40.2 s |
| 12.7 s | 30.9 s |

The **shortest** reference was the **slowest**. The variable being blamed was inversely correlated
with the effect, which is how you know you are looking at noise from somewhere else entirely.

This matters here more than in most repos: this machine has **one** 12 GB card, and ComfyUI,
Hunyuan3D, MiniMax H3 and IndexTTS2 all want it — as do Steam, Rocket League and a Wallpaper Engine
that renders continuously. Every "how fast is it" answer in this portfolio is implicitly *"…given
what else was on the GPU"*.

**Rule:** record the machine state beside any GPU timing — `nvidia-smi --query-compute-apps` takes a
second and turns an unreproducible number into a reproducible one. Publish the conditions with the
measurement, not just the measurement. And when a benchmark moves after an input changed, **test the
input as a variable** before writing the explanation down: an appealing causal story costs nothing
to believe and is why a wrong number survives into three documents.
