# How Shadow's AI editing is wired

> Built 2026-07-26. The client half of this feature already existed — a diff review UI, an apply
> handler, and undo. Only the thing that answers the request was missing.

## The contract

`APPLICATION/src/main.ts` → `requestAiPatches(prompt, includeFiles)` sends:

```json
{ "prompt": "...", "files": [ { "relativePath": "...", "content": "..." } ] }
```

and expects back:

```json
{ "patches": [ { "relativePath": "...", "before": "...", "after": "...", "description": "..." } ] }
```

`after` is the **complete new file**, not a diff. The renderer diffs it against disk for review.

## Two providers, one contract

| Mode | When | Cost |
|---|---|---|
| **Claude Code CLI** (default) | `SHADOW_AI_PATCH_URL` is unset | £0 — runs on the user's own Claude subscription, no API key |
| **HTTP service** | `SHADOW_AI_PATCH_URL` is set | Whatever that service costs |

The CLI is the default *on purpose*: the packaged `Shadow.exe` ships without a Python backend, so an
HTTP-only design could never work in a real build. Shelling out to a local CLI keeps the desktop app
self-contained. The HTTP branch is kept so the seam stays open — not because anything needs it today.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `SHADOW_AI_MODEL` | `claude-opus-5[1m]` | Model passed to `--model`. The **`[1m]` suffix is load-bearing** — it selects the 1,000,000-token context window; the bare `claude-opus-5` gets 200,000. Verified against the CLI. `claude-sonnet-5` is cheaper for light edits but drops back to a smaller window. |
| `SHADOW_AI_CLI` | `claude` | CLI binary name or full path. |
| `SHADOW_AI_TIMEOUT_MS` | `300000` | How long to wait before giving up. |
| `SHADOW_AI_PATCH_URL` | *(unset)* | Set to switch to the HTTP provider. |

## Why the CLI is invoked the way it is

- **Prompt goes over stdin, not argv.** Windows caps a command line at ~8 KB; file contents blow
  straight past that. This is not a style choice — argv would break on any real request.
- **Tool access is per-surface, not per-app.** See `toolPolicyArgs` in `src/main.ts` (~`:428`). There are
  three policies and the caller picks one:

  | Policy | Flags emitted | Who uses it | Why |
  |---|---|---|---|
  | `readonly` | `--permission-mode dontAsk --disallowed-tools Bash Edit Write NotebookEdit` | patch seam, agent report, session digester | These parse the reply as JSON and apply changes through Shadow's own reviewed apply/undo path. An agent holding `Write` there edits the file *and* returns prose, so the parse fails and the diff view empties. |
  | `verify` | `--permission-mode dontAsk --disallowed-tools Edit Write NotebookEdit` | council reviewers | A reviewer that can run the tests and reproduce the bug beats one reasoning from a diff. Still no writes: the three reviewers run **concurrently** against one working tree. |
  | `full` | `--permission-mode bypassPermissions`, **no tool bans** | **council author**, Terminal build agent | These surfaces do the work rather than describe it. Both run alone, never alongside another writer. |

  `dontAsk` on the first two is because this runs headless — an interactive prompt would hang forever
  with no UI to answer it.

> ### ⚠ Do not "fix" the council author back to read-only
>
> This file previously documented a single blanket `--disallowed-tools Bash Edit Write NotebookEdit`
> applied to **every** surface. That is landmine 12, and it is fixed. If you are reading an older copy of
> this doc, or the comment at `src/main.ts:2164`, and are about to restore the blanket ban — don't. The
> council author is *supposed* to hold `Write`/`Edit`/`Bash`; that is the entire product.
>
> See also landmine 24: a Council author reporting that those tools "don't exist" almost certainly means
> **`Shadow.exe` is older than `src/main.ts`**, not that the policy is wrong. Date the binary first.

## Conversation continuity

The CLI keeps history per `session_id`. Shadow captures it from the first reply and passes it back
with `--resume` on every subsequent request, so follow-ups work:

> "Add gravity." → `GRAVITY = 0.5`
> "make it twice as strong" → `GRAVITY = 1`   *(no mention of gravity in the second prompt)*

The session id is scoped to the open project and cleared on project switch — a conversation is about
one project's code. The **+** button in the Agent Chats header starts a fresh one.

Files are still re-sent every turn, and on a follow-up the prompt says so explicitly: the attached
contents are the *current* state on disk and override whatever the model remembers, because patches
may have been applied or rejected in between.

**Chat transcripts** are stored separately, one file per project under
`userData/chats/<hash-of-project-path>.json`, capped at 400 messages. Opening a project restores the
conversation. This is deliberately *not* in `settings.json` — a single chat message should not
rewrite the whole settings blob.

## Guards on the response

1. **Payload cap** (~600 KB of attached files) with a plain-language error, so a huge selection fails
   fast instead of stalling.
2. **Unattached paths are rejected and logged.** A patch for a file the user did not attach never
   becomes reviewable — that is the defence against a hallucinated or unrequested path.
3. **`before` always comes from disk**, never the model's echo of it. The model's copy is only used
   to detect a no-op.
4. **No-op patches are dropped** rather than shown as an empty diff.
5. **Failures are loud.** There is no fallback that fabricates a patch — see landmine #6 in
   `landmines.md` for why that rule exists.

## Verifying it works

`docs/` has no test harness for this, but the pipeline was validated end to end before shipping:
attach a real file, request a change, confirm the returned patch has `before` matching disk exactly,
`after` differing, and the path attached. Round trip was ~6s on `claude-opus-5`.

**Context window:** the default model string carries the `[1m]` suffix, giving 1,000,000 tokens
instead of 200,000. This matters most for the council — three reviewers each reading a real codebase
is the workload that runs out of room, and a reviewer that silently loses the end of what it was
given will approve work it never finished reading.
