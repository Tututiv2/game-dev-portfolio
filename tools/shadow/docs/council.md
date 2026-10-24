# The Council — how it works

> Built 2026-07-29. This is the feature the rest of Shadow was scaffolding for: a chat where an
> author agent writes the answer, independent reviewers attack it, and you only see work that
> survived them — while watching the whole argument happen.

## The flow

1. You send a message. The **Reviewed** toggle in the composer decides whether reviewers are seated
   (default on; flick it off for throwaway questions).
2. The **author** (Opus 5, via the Claude Code CLI) writes the answer.
3. **Three reviewers** run *concurrently*, each in a **fresh CLI session**, each with one lens.
4. If every reviewer approves, the answer surfaces. If any objects, the author gets the objections
   **as data** and revises — one more round, then it stops.
5. If an objection is still standing after round 2, the answer surfaces marked **disputed** with the
   objection attached and two buttons: *Accept anyway* or *Send it back*.

Everything streams to the transcript as it happens. When a run finishes the deliberation collapses
behind a `show deliberation (n)` link and the final answer is what's left on screen.

## The lenses

Defined once in `src/shared/contracts.ts` as `COUNCIL_LENSES`, so the orchestrator and the UI can
never disagree about who is seated.

| Lens | Looks for | Explicitly ignores |
|---|---|---|
| **Correctness** | Logic that does not do what it claims, wrong APIs, false claims, mishandled edge cases | style, scope, elegance |
| **Blast radius** | Data loss, silent overwrites, irreversible actions, side effects on shared state | happy-path correctness |
| **Scope** | Invented requirements, abstraction with one caller, unasked-for work — and the reverse: part of the request left unanswered | bugs and risk |

## Why it is built this way — read before changing it

**Two LLMs in open conversation converge.** They validate each other into a bad answer and it reads
as consensus. "All three agreed" is *weak* evidence by default. Six mitigations make it worth
something, and every one is load-bearing:

1. **Distinct lenses**, not three general reviewers. Three generalists produce three copies of the
   same objection and miss everything none of them thought of.
2. **Fresh CLI session per reviewer.** `runClaudeCli(..., resumeSessionId: null, ...)` — never
   `--resume` the author's session. Shared history is exactly how a reviewer inherits the author's
   blind spot. This one line is the whole feature.
3. **Reviewers see the author's output, never its reasoning.**
4. **The brief pays off on disagreement:** "find what is wrong", not "review this".
5. **Evidence only.** A finding must point at a file, a function, or a specific claim. A `revise`
   verdict with no finding is downgraded to `approve` in code — a veto with no evidence is not a
   veto.
6. **Round cap of 4** (`COUNCIL_MAX_ROUNDS`). A cap, not a target: a run ends the moment every
   reviewer approves, or the moment a round returns the identical objections as the round before it.
   Unbounded back-and-forth burns the subscription and converges anyway.

Break any of these and the panel still *looks* like it works. That is the dangerous part: it will
rubber-stamp bad work at full confidence, and the UI will say "3 of 3 agreed".

The author's revision brief says so explicitly: *if an objection is wrong, say so and give the
evidence, do not cave to be agreeable.* Caving to a confident reviewer is convergence wearing a
different hat.

### It works — first real run, 2026-07-29

Asked what `--permission-mode dontAsk` does. Author cited `main.ts:301` and `main.ts:304` correctly.
Correctness: no objection ("every mechanical claim checks out against main.ts:294-318"). Blast
radius: no objection. **Scope objected** — the answer "tacks on an unrequested speculative security
advisory beyond the asked-for short explanation", quoting the exact sentence. Two approvals and one
evidenced objection on the first try, from a question with no bug in it at all.

## Where the code lives

| Piece | File |
|---|---|
| Lens definitions, event and message types | `src/shared/contracts.ts` |
| Orchestrator, prompts, verdict schema | `src/main.ts` — `runCouncil`, `buildCouncil*Prompt` |
| Stop / kill agents | `src/main.ts` — `stopCouncilRun`, `stopAllCouncilRuns` |
| IPC + live event channel | `src/preload.ts` — `councilAsk`, `councilStop`, `onCouncilEvent` |
| The surface | `renderer/src/Council.tsx` |
| Styles | `renderer/src/styles.css` — the `.council-*` block |

## Things worth knowing

- **One run at a time.** A second `councilAsk` while a run is live is refused with a plain-language
  error, because two councils would interleave events in one transcript and race on the author's
  session id.
- **Stop actually kills the agents.** Every spawned child is tracked per run; Stop kills all of
  them. Marking a run stopped without killing the children would leave `claude` processes burning
  the subscription with nothing listening.
- **The author's session carries across turns** (so "make it simpler" knows what "it" is) and is
  cleared on project switch, same as the Studio conversation.
- **A reviewer that fails is not an approval.** It is reported in the transcript by name with the
  real error, and the footer chip reads e.g. "2 of 3 agreed".
- **The transcript is per project** in `localStorage`, capped at 40 turns. A run that was in flight
  when Shadow closed is shown as stopped rather than as a spinner that can never resolve.
- **Cost:** a reviewed turn is 1 author call + 3 reviewer calls, doubled if a round 2 happens — up
  to 8 CLI calls. That is why the toggle exists.

## The composer — 2026-07-31

Four things the owner asked for, all in the Council composer.

### Recent work, down the left

The session index, filtered to the open project, newest first. Clicking rows selects them; they are
carried into the **next** message via `sessionsCarryOver({ sessionIds })`, and the receipt under the
composer lists exactly what went in. Same machinery as `cnt <task>`, except the choice is the
owner's, so no relevance filter runs and everything picked goes in.

Two deliberate details:

- **The first fetch is deferred ~1.2 s.** `sessionsView()` triggers a full transcript scan on its
  first ever call (~10 s of disk here), and the Council is the surface Shadow opens on. Firing it
  during mount would put that scan in front of the first paint. The index persists, so it is a
  one-time cost.
- **The project filter normalises paths.** See landmine 27 — comparing `project.rootPath` to
  `session.cwd` as raw strings showed an empty rail for a folder with 40 sessions in it.

### Drag and drop a picture, `[Image #1]` like Claude Code

Drop a file on the composer or paste a screenshot. The bytes go to `stageImage`, which writes them
under `userData/dropped-images/<uuid>.<ext>` and returns the path; the composer inserts the token
`[Image #1]` into the message text and shows a thumbnail chip.

Why bytes and not a path: a clipboard paste **has** no path, and a dropped file's path is the
renderer's claim about the filesystem. Writing the bytes ourselves means the only file any agent is
ever pointed at is one Shadow put there. `councilAsk` re-checks that every submitted image path is
inside the staging root and refuses anything else.

Why a path and not inlined base64: the CLI's `Read` tool decodes images natively, so the file on
disk is the highest-fidelity form available and costs the prompt nothing. The staging root is always
in `--add-dir`, or the agent is refused the read and answers as if there were no picture.
**Verified**: a staged PNG containing text was read back correctly by the CLI through `--add-dir`.

Reviewers get the same images. Without them a reviewer reads `[Image #1]` as an unresolvable token
and judges the answer against a request it cannot fully see.

### Saying something while it works

The composer is no longer disabled during a run. Enter queues a note; it appears in the transcript
as *queued*, then flips to *you said, mid-run* when it lands.

**It is queued, not injected.** The author is a headless `claude -p` with no stdin to interrupt.
Notes are drained at the author's **turn boundary** — the moment its current CLI call returns, and
crucially *before* any reviewer sees the work, so steering a wrong turn still counts. A note that
arrives while a question modal is up rides along with the answer instead of buying its own turn.
`MAX_STEER_DELIVERIES` (12) bounds how many extra author turns a run can spend this way; past it,
notes fold into the next round's prompt rather than being dropped.

The prompt tells the author it is **not** starting over — the failure mode of "the user interrupted
you" is an agent that throws away ten minutes of correct work.

**Verified live**: asked for `NOTES.md` *and* `SUMMARY.md`, then queued "do NOT create SUMMARY.md,
only NOTES.md, and add a line saying exactly `GRAVITY IS 9.8`". The note showed as queued, flipped to
delivered at the turn boundary, and the author took a second turn: it **deleted** the `SUMMARY.md` it
had already written and added `GRAVITY IS 9.8` at line 8 of `NOTES.md`.

### What's running — a model per seat, effort, and ultracode

The composer chip shows what is actually running. Clicking it, or typing `/effort`, opens the panel.

**Every seat gets its own model** (2026-08-03, the owner's call). Author, Correctness, Blast radius
and Scope each have a dropdown; `AGENT_SEATS` and `AGENT_MODELS` in `contracts.ts` are the only
definitions, and `modelForSeat()` in `main.ts` is the only reader. `SHADOW_AI_MODEL` still beats all
four — an env var that a per-seat pin could silently override would be a lie — and the panel says so
and disables the dropdowns when it is set.

> ⚠ **This section used to say the model was deliberately not editable, and the reason it gave still
> holds.** A reviewer on a 200k window quietly stops reading a big codebase and approves the part it
> never got to. The guard did not disappear, it moved: every entry in `AGENT_MODELS` states its
> context window in the label, and the panel says in one sentence why the window matters. If you are
> tempted to trim those blurbs, that is what they are for.

**Effort** is global, persists to `settings.agentEffort`, and becomes `--effort <level>` on every
spawn. `default` passes no flag at all, which is not the same as guessing a level: the CLI picks per
model, and hardcoding one here would override a better default the day it changes.

**Ultracode** invites the author to fan work out across a fleet of subagents. It is a **word in the
prompt, not a flag** — the CLI gates its `Workflow` tool on the user having asked for that scale, so
the toggle is Shadow asking on his behalf (`ultracodeBriefing()` in `main.ts`). Off by default,
because one question turning into dozens of agents is not something to infer. The composer chip
carries an `ULTRA` tag while it is on, so the state is never a surprise.

### Watching them work

Rewritten 2026-08-03, after the owner reported the progress view "only shows latest step".

He was right, and the data was never the problem: `turn.activity` has always been a full
`Record<agentId, CouncilActivity[]>`. It was the read side. `CouncilLive` opened collapsed, and
opened it showed *one line per agent* — `feed[feed.length - 1]`, the newest step, overwriting itself
— with the real list behind a second click. Worse, it was mounted on `running`, so the entire
history unmounted the instant a summary arrived, which is exactly when you go back to read it.

What it is now:

- **The feed is the view.** Open while the run is live, one tab per agent with its step count,
  auto-scrolling to the tail.
- **Subagents nest.** The author dispatches them constantly and they used to be a single opaque
  `Agent` line sitting there for minutes. Now their steps are indented under the call that spawned
  them and the group closes with `✓ returned` and what it reported.
- **It survives.** A finished turn collapses to `N steps of work · Finished` and reopens with
  everything still in it.
- **A pinned strip above the composer** shows who is alive, total steps and elapsed, so a run whose
  transcript has scrolled off the top is still accounted for.

The wiring that makes subagents visible is one flag: **`--forward-subagent-text`**, added to the
spawn only when someone is listening (`onActivity`). With it, every message a subagent produces
arrives tagged with the `parent_tool_use_id` of the `Agent` call that spawned it — on the event
envelope, not inside `message`, verified against a real run. `createActivityReader()` in `main.ts`
turns that into a parent/child relation and `buildStepTree()` in `Council.tsx` turns it into the
tree. Orphans (a group trimmed by the per-agent cap while its children were still arriving) are kept
at the top level rather than dropped, because binning them would erase real work from the view.

## `shadow` — the terminal command

Built 2026-08-03. Typing `shadow` in any folder opens that folder in Shadow, the same shape as
typing `claude` in a folder opens Claude Code there. `shadow -r` additionally opens the folder's
recent work to pick from.

Install once: double-click `APPLICATION\Install Shadow Command.bat` (or run
`scripts/install-shadow-command.ps1`). It writes a shim to `%LOCALAPPDATA%\Shadow\bin\shadow.cmd`
and puts that one directory on the user PATH. **Re-run it after moving or rebuilding Shadow** — the
exe path is baked into the shim.

Four things had to exist for this to work, none of which did:

| Piece | Where | Why it is needed |
|---|---|---|
| argv parsing | `parseLaunchIntent()` | There was none at all. `process.argv` was read nowhere in `src/`. |
| a folder that beats the remembered one | `app.whenReady` | Otherwise `shadow` in a project opens the *last* project, which is the one failure this feature exists to prevent. |
| a single-instance lock | `app.requestSingleInstanceLock` + `second-instance` | Two Shadows both hold `shadow.settings.json` and last-write-wins it, including `lastProjectRoot` — so closing them in the wrong order silently moves the project you come back to. The second invocation hands its argv to the first and exits. |
| a way to tell the renderer | `takeLaunchIntent` / `onLaunchIntent` | `setCurrentProject()` notifies nobody; every previous caller was a click that already knew. A command line is the first caller that isn't. |

**Boot is pulled, a second invocation is pushed.** `sendToRenderer` is fire-and-forget, and at boot
the window has not finished loading when argv is parsed — a push would go nowhere and `shadow -r`
would behave like plain `shadow`. So the renderer *asks* once on mount (`takeLaunchIntent`, which
clears it so a reload does not re-fire), while a `shadow` typed at an already-running window is
pushed on `onLaunchIntent`. Both land in the same handler.

**Why `--dir "%CD%"` and not `process.cwd()`:** a packaged app launched through `start` does not
reliably inherit the caller's directory, and a plausible-but-wrong folder would silently open the
wrong project. The shim quotes it, because every project in this portfolio lives under a path with a
space in it (landmine 14, and landmine 30 for the same bug one layer out).

**`-r` reuses the rail rather than adding a picker.** The "Recent work" rail already lists the open
project's sessions newest-first and carries the ones you click into the next message. `-r` opens it
and focuses the composer; it does not need a second UI for the same job.
