# Claude Session Saver

One button parks every open Claude Code session; the same button brings them all back in the right folder, on the right conversation, with the right name.

I often have around ten Claude Code sessions open across different project folders. Closing them by hand, then later `cd`-ing into each folder and running `claude -r` while trying to remember which conversation was which, was slow and error-prone. Session Saver records the whole set as a named layout and restores it in one click, as tabs in one Windows Terminal window or as separate windows.

## Features

- Save and close every running session in one click, or reopen a saved layout
- Named layouts (`layouts/<name>.json`); `last` is the default
- Restore as Windows Terminal tabs or separate windows
- Optional "resume full session as-is" answer for large sessions
- Duplicate guard: refuses to reopen a session that is already running
- Never closes the session it was launched from
- `Check.bat`: a read-only report of what it can see and the exact command a reopen would run

## How it works

- **No transcript scraping.** Claude Code keeps a live registry of running sessions at `~/.claude/sessions/<pid>.json` with `sessionId`, `cwd` and `name`. A restore is just `claude --resume <sessionId>` started in `cwd`.
- **A pid is not proof of life.** Pids are recycled and force-killed sessions leave stale files behind. A session counts as alive only if the pid is a live `claude.exe` and its process start time matches the registry's `startedAt`. Stale files are swept after every close.
- **Environment scrubbing.** A process launched from inside a Claude session inherits markers such as `CLAUDE_CODE_CHILD_SESSION`, which brings the reopened session up with transcript saving off, so it could never be resumed again. Those variables are stripped for the launch. `NO_COLOR` is also restored to its User/Machine value rather than inherited; console colour attributes went from 1 distinct value to 6-7 after the fix.
- **Answering a prompt safely.** Large sessions ask whether to resume from a summary, and there is no CLI flag or setting for it. A detached helper attaches to each new console, reads the screen with `ReadConsoleOutputCharacterW`, and only if the prompt is actually displayed sends Down, re-reads to confirm the selection moved, then sends Enter. A session that never asks is never sent a keystroke.
- **The quoting bug.** `Start-Process -ArgumentList` does not quote array elements, so any path with a space silently split in two. Both launch paths build one pre-quoted string.

## Tech

PowerShell 5.1 with WinForms, Win32 console APIs via P/Invoke, Windows Terminal.

## Running it

Double-click `Session Saver.bat` (launches the GUI with no console). Run `Check.bat` first if something looks wrong. `settings.json` holds `launchMode` (`tabs`/`windows`). Set `SESSIONSAVER_TRACE=1` to write a startup log.

**Verified** with a real round trip: two live sessions in two folders, save and close, reopen, both back with the correct ids, folders, names and transcript saving on.
