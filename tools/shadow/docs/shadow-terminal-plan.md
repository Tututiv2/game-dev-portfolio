# Shadow Terminal — multi-agent dev terminal

> Status: **design, not built.** Written 2026-07-26 from a working session on the owner's
> Minecraft server. Everything in § Why actually happened; it is the plan's proof case.

Shadow Terminal is an agentic coding terminal (Claude Code / Cursor shaped). The thing that makes
it different from both: **agents are peers that can talk to each other, coordinate on shared
resources, and review each other's work** — not one assistant answering one human.

---

## Why — the failure this exists to fix

On 2026-07-26, three separate AI sessions deployed to the owner's **live production** Minecraft
server within eight minutes. None of them knew the others existed.

```
09:47:47   session A deploys live   -> snapshot predeploy-20260726-094740
09:53:19   session B deploys live   -> snapshot predeploy-20260726-095319
09:55:04   session A deploys again  -> snapshot predeploy-20260726-095504
```

Both sessions upload the **same shared** `server/plugins/GamePlugin.jar`, so whichever built last
silently won. It was caught only because session B happened to list the snapshot directory before
pushing and noticed a folder timestamped 40 seconds earlier. B then diffed the two jars class by
class and confirmed its build was a strict superset (0 classes lost, 1 added) before continuing.

**That was luck, not design.** Had A been mid-edit, B would have shipped broken code to real
players. The same day, the same repo also produced: two sessions editing shared files
(`AdminPanelMenu`, `BossRaidService`) unaware of each other, and a `deploy-remote.ps1` landmine
that had previously wiped every live player's progress and was "fixed" by *verbally telling the
owner not to run it* — which does nothing for a parallel session that never read the warning.

**Requirement #1: an agent must be able to find out what other agents are doing before it acts.**

---

## The core distinction: a lock is not a chat

The instinct is "let the agents talk to each other." That is right for *review* and wrong for
*safety*. Two agents having a conversation does not prevent a race — both can still decide to
deploy. These are two different problems with two different primitives:

| Problem | Wrong primitive | Right primitive |
|---|---|---|
| Don't clobber each other | Chat | **Lease / lock** on the shared resource |
| Catch my blind spots | Lock | **Adversarial review** by an agent that didn't write it |

Build the lock first. It is smaller, it has a proven failure it fixes, and it is verifiable.

### Tier 1 — recency guard (zero new infrastructure)

Uses state that already exists. Before a protected action, look for evidence that the same action
just ran; if it's fresh, refuse:

```bash
newest=$(ls -1t /home/minecraft/server/backups/ | grep predeploy | head -1)
# parse timestamp; if < 5 minutes old -> another agent is mid-deploy -> ABORT
```

On the 09:53 deploy this would have printed *"predeploy-20260726-094740 is 40 seconds old —
another session is deploying, aborting"* and the collision never happens. **Fails open** — it can
never block a legitimate first deploy, so it is safe to add to a production path immediately.

### Tier 2 — a real lease

`mkdir` is atomic on POSIX: it succeeds only if the directory does not already exist. That makes
it the classic lock primitive with no dependencies.

```bash
mkdir /srv/.deploy.lock            # atomic: wins or fails
echo "$AGENT_ID|$(date +%s)" > /srv/.deploy.lock/owner
```

Rules that matter:
- **Hold the lock on the shared resource itself** (the live box), not on the local machine — that
  is the thing actually being contended, and it survives agents running from different machines.
- **Auto-expire** (~10 min). A crashed agent must not wedge deploys forever.
- **Steal loudly.** Taking a stale lock is allowed, but it must be logged with both agent IDs.
- **Fail open on lock-system errors.** A broken lock must not become a broken deploy.

Locks generalize past deploys: same file, same branch, same migration, same live config.

---

## The reviewer: a job spec, not a backstory

A persona (*"you are a grizzled veteran reviewer"*) mostly changes the voice. What makes a second
agent actually catch things:

1. **A narrow adversarial job.** "Find what's wrong with this diff" beats "review this code."
   Open-ended reviewers agree with you.
2. **It must not have written the code.** Its entire value is not sharing the author's
   assumptions. Feeding it the author's reasoning first burns that advantage.
3. **Ground truth it can check claims against** (below).
4. **Evidence-only reporting.** "Report only what you can point at a file/line/log for."

> ⚠ **The known failure mode: two LLMs in open conversation converge.** They validate each other
> into a bad plan and it reads as consensus. Mitigations: give the reviewer a job that only pays
> off if it disagrees; don't show it the author's justification up front; prefer several
> independent reviewers with *different lenses* (correctness / security / does-it-actually-run)
> over one general one; treat unanimous agreement as weak evidence, not strong.

## Agent memory is a knowledge map, and the pattern already exists

The owner independently built a working version of this in
`Ai Game/Minecraft game im making/`, which the Terminal should formalize:

| File | Role |
|---|---|
| `CLAUDE.md` | Auto-loaded project map — what this is, layout, conventions |
| `knowledge/INDEX.md` → ~20 topic files | Durable how-to + a **landmine ledger** of real bugs and the rules they produced |
| `.agents/memory.md` | Rolling session state: what's live vs dev-only, what other sessions are mid-way through |
| `.agents/user.md` | The owner's profile and hard lines |

This is why a brand-new session there already knows the server cold. **A reviewer agent should not
"remember the theme of the project" — it should be pointed at these files.** Its job spec becomes
roughly: *read `knowledge/INDEX.md` and `.agents/`, here is a diff, find where it violates a
documented rule, contradicts what is live, or repeats a landmine already in the ledger.*

Note the house law that produced that ledger: **every bug fixed must become a written rule in the
same change.** The Terminal should make that cheap — ideally automatic.

---

## Observability: the animations are load-bearing

Rendering agent-to-agent conversation is not eye candy. Today, when a session goes wrong you find
out when production breaks. Watching agents talk is how a human catches one going off the rails
*while it is happening*. Design targets:

- Live transcript of inter-agent messages, clearly attributed.
- Who holds which lock, right now, and for how long.
- A knowledge-map view: which files/docs an agent actually read before acting.
- Interrupt: the human can stop or redirect any agent mid-run.

## Studio ↔ Terminal bridge (owner's ask)

One app, two surfaces, shared job/asset store:
- Select an asset in **Studio** → send it into a **Terminal** session as context.
- A **Terminal** agent can call **Studio** to generate an asset (e.g. write the sprite loader,
  then request the sprite) and get the file path back.
- Shared job history so both surfaces show the same runs.

## Shadow ↔ the Minecraft server (owner's ask, 2026-07-26)

The owner's main project (`Ai Game/Minecraft game im making/`) is the natural first real workload
for both surfaces — and there is a loop he is **already performing by hand every time**:

> AI-generate armor art → save the preview PNG → run `scripts/bake-armor.ps1` to bake it into the
> worn-armor UV sheets → rebuild the resource pack → deploy → log in and look at it → tweak → repeat.

That is precisely Studio's pipeline (prompt → image → mask/clean → export) bolted to a bake step.
Closing it is the highest-value integration:

**Studio side (asset loop)**
- Generate armor/item art in Studio, then hand the PNG straight to
  `scripts/bake-armor.ps1 -Preview <png> -Piece chest -Set prism` — no manual file shuffling.
- Preview the *worn* result (not just the icon) before deploying; the difference between "looks
  right in inventory" and "looks right on the body" has burned this project repeatedly.
- Sanity-check bakes automatically — e.g. measure sleeve rows / alpha coverage and flag a texture
  that will render see-through or wrong-length. Real bugs found this way on 2026-07-25/26.

**Terminal side (ops loop)**
- Run the deploy through the guard + lease so two agents can never collide (see § Why).
- Surface live status in the UI: service up/down, player count, a tail of `latest.log` filtered to
  real errors, and **which agent currently holds the deploy lock**.
- Read-only first. Anything that mutates live must go through the same snapshot-first path as
  `scripts/deploy-live-safe.ps1`.

> ⚠ **Hard constraint, non-negotiable.** That server has real players and real-money serialized
> items. Any Shadow feature touching it must: never push the dev world over live (a bulk world
> sync already wiped every player's progress once), snapshot before mutating, verify after, and
> roll back on failure. Read the project's `knowledge/deploy-workflow.md` §"💀 THE LIVE WIPE"
> before writing a single line of integration code.

## Build order

1. **Tier 1 recency guard** on the real Minecraft live-deploy path — smallest thing with a proven
   failure it prevents, and it protects a live server today.
2. **Tier 2 lease** + a `who-is-doing-what` query any agent can call.
3. **Reviewer agent** with the job spec above, pointed at the knowledge map.
4. **Transcript UI** + lock visualization.
5. **Studio ↔ Terminal bridge.**

## Open questions

- Do peer agents share one process, or is each a separate session coordinating through files?
  (Files are debuggable, survive crashes, and match the existing `.agents/` pattern.)
- How does an agent get *interrupted* mid-run by a peer, not just messaged between turns?
- What is the escalation path when two agents genuinely disagree — human tiebreak, or a third?
- Does the lock live in the repo (git-visible, conflict-prone) or on the resource (invisible to
  git, but authoritative)? Current lean: **on the resource.**
