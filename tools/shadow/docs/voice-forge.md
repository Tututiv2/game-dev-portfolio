# Voice Forge — spoken dialogue from a text prompt, locally and for free

**Built and verified 2026-08-07.** Text in, an acted `.wav` out, in a voice cloned from a few
seconds of reference audio. Runs entirely on the owner's GPU. No API, no cost, no account.

```
"Hold the line."  +  ref.wav (5-15s)  -->  IndexTTS2  -->  2.97s @ 22050 Hz mono
                  +  "exhausted but resolute"              8.2 s on an RTX 4070 SUPER
```

## Why IndexTTS2 and not any other local TTS

Because **timbre and performance are separable**. Every other local option clones a voice and reads
your line in whatever mood the reference clip happened to be in. IndexTTS2 takes *who is speaking*
from one place and *how they are speaking* from another:

| Mode | Where the delivery comes from | Use it for |
|---|---|---|
| `speaker` | the reference clip itself | the plain baseline read |
| `text` | a description: `"quietly furious"` | barks, and directing without recording |
| `vector` | 8 sliders summing to ≤ 0.8 | a cast, because numbers repeat and adjectives do not |
| `audio` | a **second** clip's performance | act the line yourself, then have the character say it |

One recorded reference therefore yields a whole cast's worth of deliveries. That is the entire
reason this is wired in, and it is why all four modes are exposed rather than just the easy one.

The eight axes, **in order** — the vector is positional, so a reordering silently swaps two
emotions rather than failing:

```
happy, angry, sad, afraid, disgusted, melancholic, surprised, calm
```

Cross-checked against upstream's README prose and `webui.py`'s slider order (喜 怒 哀 惧 厌恶 低落 惊喜 平静).

## Measured on this machine

| | |
|---|---|
| model load, warm | **23.7 s** |
| model load, very first run | **148.4 s** — it also downloads ~5 GB of aux models it does not ship with |
| one line (~3 s of speech), **idle GPU** | **5–8 s** |
| one line, **while a game is running** | **31–75 s** — see below |
| output | 22050 Hz mono wav |
| on disk | 10.6 GB total (5.5 GB checkpoints + 5.1 GB aux cache) |
| device | `cuda`, fp16 |

### Close the game before a batch

Those two rows are the **same model, same worker, same reference clip** — the only difference is
that Rocket League was running on the same card. A 3-second reference measured **8.3 s** with the GPU
idle and **75.0 s** with the GPU at 96% from a game. That is a **9× slowdown**, and it looks exactly
like a broken forge.

Reference length is *not* the cause, which was worth proving rather than assuming — one batch, one
model load, three reference lengths under identical contention:

| reference | same line |
|---|---|
| 3 s | 75.0 s |
| 6 s | 40.2 s |
| 12.7 s | 30.9 s |

The **shortest** reference was the **slowest**, so the spread is contention noise, not prompt cost.
Use a 5–15 s reference because it clones better, not because it is faster.

## The one structural difference from every other forge

`assetForge` spawns Blender per mesh and `spriteForge` spawns Python per sheet, because both start
in ~2 s and then work for far longer. **IndexTTS2 inverts that ratio**: 23.7 s to load seven models,
then 5–8 s a line. A thirty-line barks file costs 24 s of loading *once*, or **twelve minutes** of it
if the process is spawned per line.

So the Python side is a **persistent worker**: `voice_worker.py` runs as a long-lived child speaking
JSON lines over stdin/stdout. This is still not a server — nothing has to be started by hand,
nothing listens on a port, and it dies with the app (`disposeVoiceWorker()` on `before-quit`). The
objection recorded in `spriteForge.ts` was to a process the owner must remember to run, not to a
long-lived child.

Three consequences, all deliberate:

- **stdout is the protocol and nothing else may touch it.** See landmine 41 — the real stdout is
  captured at import and `sys.stdout` is repointed at stderr, so torch/tqdm/BigVGAN chatter is
  misrouted by construction rather than by discipline.
- **The timeout is stall-based, never wall-clock.** Same rule `generateVideoFrames` had to learn: a
  first load also downloads models, so "how long should this take" has no answer, but "is it still
  making noise" does. The deadline resets on every byte.
- **Requests are serialised.** One GPU, one stdin pipe. Two clicks cannot interleave.

## Two front doors, one implementation

The app uses the worker. An **agent** cannot hold a pipe, so the same script also runs one-shot:

```bash
"<index-tts>/.venv/Scripts/python.exe" "<APPLICATION>/scripts/voice/voice_worker.py" \
    --model-dir "<index-tts>/checkpoints" --once \
    --speaker ref.wav --text "Hold the line." --out line.wav \
    --emotion-mode text --emotion-text "exhausted but resolute"
```

Last stdout line is `__SHADOW_VOICE_RESULT__` + JSON. **A failure arrives on that line too**, so the
exit code is never the arbiter. `voiceAgentBriefing()` injects this into the council author's prompt
automatically — but only when the checkout, its venv *and* the weights are all present, because
advertising a tool that will fail costs the agent a turn.

For many lines an agent uses `--batch lines.json` (a JSON array of `{text, out}`), which pays the
model load exactly once. **Do not loop `--once`.**

## Install

It is not vendored — it is ~10 GB. Shadow finds it, and the tab prints the exact commands when it
cannot. Set `SHADOW_INDEXTTS_PATH` to override the search.

```bash
git clone https://github.com/index-tts/index-tts.git ~/Documents/index-tts
cd ~/Documents/index-tts
uv sync                       # NOT --all-extras, see below
hf download IndexTeam/IndexTTS-2 --local-dir checkpoints
```

Three things that are easy to get wrong:

- **`uv sync`, not `uv sync --all-extras`.** The `deepspeed` extra is what upstream tells Windows
  users to skip, and `accel` pulls `flash-attn`, which needs a CUDA toolchain to build. Plain
  `uv sync` still gets the **cu128** torch build, because index-tts pins
  `download.pytorch.org/whl/cu128` in `[tool.uv.sources]` for win32.
- **The interpreter is the checkout's `.venv`, never `python` on PATH.** `torch`, `transformers` and
  `indextts` itself exist only in that venv. The system Python fails with
  `ModuleNotFoundError: indextts`, which reads as a broken forge rather than the wrong interpreter.
- **A `checkpoints` folder that exists is not one that works.** An interrupted download leaves the
  directory and `config.yaml` in place with the multi-gigabyte weights missing, so `checkpointsIn()`
  checks by **weight** — at least one file over 50 MB — not by presence.

There are two `Documents` folders on this machine and the install is in the non-OneDrive one; both
are searched. See landmine 40.

## Known, harmless

```
>> Failed to load custom CUDA kernel for BigVGAN. Falling back to torch.
```

Upstream's optional compiled vocoder kernel is not built, so it uses the torch path. It works — the
audio above was produced with this warning on screen — it is just slightly slower. Not worth a CUDA
toolchain.

## Where things land

`Documents/Shadow Library/` (the same central library as meshes and sprites, and the same
append-only reasoning — a crash mid-write costs one record, not the history):

```
voices.jsonl                     one line per take
voice-refs.jsonl                 one line per reference voice
voices/_references/<id>.wav      reference clips, COPIED not linked
voices/<id>/<name>.wav           the take
voices/<id>/<name>.js            a paste-in snippet
voices/<id>/manifest.json
```

References are **copied**, because a reference clip *is* the identity of a character and a ledger
full of paths into `Downloads` is a cast that disappears when the folder is tidied.

**Where reference clips come from:** `Ai Game/AudioCapture/` (the owner's own tool, built the same
day) records the mic, the PC's output, or both into exactly the WAV shape this wants — 24 kHz mono
16-bit, silence-trimmed, peak-normalised — with no conversion step. That is the intended front of
this pipeline: record five to fifteen seconds there, add it as a reference here, and the character
exists.

Read `seconds` and `sampleRate` off the result rather than assuming them, or timed subtitles drift.

## Verified

26/26 checks against the **running** app over CDP (landmine 28: a green typecheck is not a build;
landmine 29: screenshot through the DevTools protocol, not the desktop). Bridge methods present,
`voiceStatus` reports ready, the tab mounts, and a real line synthesised through the live IPC to
2.97 s @ 22050 Hz, landed in the ledger, and read back with a `RIFF` header.

Before that, the worker itself was proven directly: a 3-line batch covering **all three** of the
non-default emotion modes, 3/3, one model load. That run is also what showed
`normalize_emo_vec` rewriting a requested `calm 0.2` to `0.1125` — upstream applies a bias pass, so
the numbers you set are not the numbers it uses, and the result JSON reports the ones it used.

## Not built

- **Duration control.** IndexTTS2's paper advertises it; the `infer()` signature the WebUI calls does
  not expose it, so it is not claimed here. `max_mel_tokens` bounds length but does not target one.
- **Streaming.** `infer(stream_return=True)` yields chunks. Nothing in Shadow consumes audio
  incrementally yet, so a line arrives whole.
- **Voice Forge → Sprite Forge handoff.** A talking-head clip and a lip-sync pass is the obvious next
  join, and neither exists.
