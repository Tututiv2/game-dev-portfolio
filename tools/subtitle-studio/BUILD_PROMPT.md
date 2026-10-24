# BUILD PROMPT — "SubtitleStudio": a free, local, CapCut-style AI auto-subtitle app

Paste this whole file to Opus 5 as the task. It is written to be self-contained.

---

## 0. What you are building

A **Windows desktop application** that does what CapCut Pro's "Auto Captions" does, but runs
100% locally, offline, and free:

1. User drops in a video file.
2. The app transcribes the speech automatically with **word-level timestamps** using OpenAI's
   Whisper model running locally on the GPU.
3. The app groups those words into good-looking caption lines and renders them in an animated,
   CapCut-style look (word-by-word highlight, pop-in, thick outline, drop shadow).
4. The user can visually preview, restyle with presets, and hand-edit the transcript text and timings.
5. The user exports: either **burned into the video** (hardcoded pixels) or as a **sidecar
   subtitle file** (`.srt` / `.ass` / `.vtt`), via a professional render-settings panel modeled
   on DaVinci Resolve's Deliver page.

There is **no cloud service, no API key, no account, and no per-minute cost.** Everything is a
local model plus ffmpeg.

---

## 1. Target machine (already verified — build for exactly this)

| Thing | Value |
|---|---|
| OS | Windows 11 Home, 10.0.26200 |
| GPU | **NVIDIA GeForce RTX 4070 SUPER, 12 GB VRAM** (CUDA available, `nvidia-smi` on PATH) |
| CPU | AMD Ryzen 5 7600X, 6C/12T |
| RAM | 64 GB |
| Python | **3.10.9** at `C:\Users\you\AppData\Local\Programs\Python\Python310\python.exe` (this is `python` on PATH). A 3.13.3 also exists as `py` — **do not use it**, several ML wheels have no 3.13 build. Pin to 3.10. |
| ffmpeg | `C:\Users\you\Documents\FFmpeg\bin\ffmpeg.exe` (and `ffprobe.exe`), already on PATH |
| ffmpeg capabilities | **verified present**: `h264_nvenc`, `hevc_nvenc`, `av1_nvenc`, `prores_ks`, and the `ass` + `subtitles` filters (libass is compiled in) |
| Node, Git | present, not needed for this project |
| Project root | `D:\projects\VideoSubtitler` (exists, empty) |

Because libass and NVENC are already in this ffmpeg build, **do not bundle or download another
ffmpeg.** Resolve the binary at startup: prefer `shutil.which("ffmpeg")`, fall back to the
hardcoded path above, and show a clear error dialog if neither works.

**Hard constraint on the owner's workflow: the owner does not run terminal commands.** Ship
`INSTALL.bat` (one double-click, sets up the venv and dependencies with a visible progress log)
and `SubtitleStudio.bat` (one double-click, launches the app). The app must never require the
user to type a command, activate a venv, or edit a config file.

---

## 2. Stack (use exactly this — these choices are load-bearing)

| Layer | Choice | Why this and not the alternative |
|---|---|---|
| Language | Python 3.10 | Only ecosystem where Whisper, CTranslate2, and video tooling all coexist cleanly |
| GUI | **PySide6** (Qt 6, LGPL) | Native dark professional UI, real widgets, free for closed use. Not Tkinter (can't do the timeline/preview), not Electron (would need a second language + IPC for the ML side) |
| ASR | **`faster-whisper`** (CTranslate2 reimplementation of Whisper) | 4x faster and ~half the VRAM of `openai-whisper`, and it exposes `word_timestamps=True`. **Measured on this machine** (see §12): `large-v3` + `float16` + `BatchedInferencePipeline` runs at **~12–21x realtime**; plain sequential decoding is only ~2–14x, so use the batched path |
| Alignment (optional upgrade) | `whisperx` behind a feature flag | Wav2Vec2 forced alignment gives tighter word boundaries than Whisper's own. Adds ~2 GB of models and dependency friction — **ship without it, leave a clean seam** |
| Subtitle rendering | **Generate ASS (Advanced SubStation Alpha), burn with ffmpeg's `ass` filter (libass)** | This is the key decision — see §4. Do **not** write a per-frame Python/PIL compositor; it would be 50–100x slower and produce worse text shaping |
| Encoding | ffmpeg, NVENC by default, libx264/libx265 as CPU fallback, `prores_ks` for ProRes | Hardware encode on a 4070 is near-realtime |
| Media probing | `ffprobe -print_format json` | Don't guess resolution/fps/duration |
| Packaging | plain venv + `.bat` launchers | PyInstaller is a stretch goal, not v1 — a 3 GB model + CUDA DLLs makes a single-exe build painful |

`requirements.txt` — **these exact pins are verified working; §12 explains what
breaks with each alternative**:

```
PySide6==6.7.3
faster-whisper==1.2.1       # 1.1+ needed for BatchedInferencePipeline
ctranslate2==4.8.1          # MUST pair with cuDNN 9; 4.5.0 crashes on teardown
nvidia-cudnn-cu12==9.*      # MUST pair with ctranslate2 >= 4.5
nvidia-cublas-cu12
requests                    # faster-whisper imports it without depending on it
srt
numpy<2
```

---

## 3. The AI transcription stage

### 3.1 Audio extraction
Whisper wants 16 kHz mono PCM. Always normalize first rather than feeding it the container:

```
ffmpeg -y -i <input> -vn -ac 1 -ar 16000 -c:a pcm_s16le <temp>/audio.wav
```

Extract to a temp dir under `%LOCALAPPDATA%\SubtitleStudio\cache\<hash-of-input>\`, and cache it —
re-transcribing the same file should not re-extract.

### 3.2 Model loading

```python
from faster_whisper import WhisperModel
model = WhisperModel("large-v3", device="cuda", compute_type="float16")
```

- Default to `large-v3`. Expose a model picker (`tiny`/`base`/`small`/`medium`/`large-v3`/
  `distil-large-v3`) because a user in a hurry may want `distil-large-v3`, which is ~6x faster
  at close to large-v3 English quality.
- First run downloads ~3 GB from Hugging Face into `%USERPROFILE%\.cache\huggingface`. **Show a
  real progress UI for this** — a silent 3 GB download looks like a hang. Let the user pick a
  different cache dir (this machine's `C:` may be tight; `D:` has room).
- Detect CUDA availability and fall back to `device="cpu", compute_type="int8"` with a visible
  warning that it will be ~20x slower.

### 3.3 Transcription call

```python
segments, info = model.transcribe(
    audio_path,
    language=None,              # auto-detect; let the user override in the UI
    task="transcribe",          # also expose "translate" (→ English)
    word_timestamps=True,       # REQUIRED — this is what makes karaoke highlighting possible
    vad_filter=True,            # Silero VAD: strips silence
    vad_parameters=dict(min_silence_duration_ms=500),
    condition_on_previous_text=False,   # see the hallucination note below
    beam_size=5,
)
```

`segments` is a **generator** — consuming it is what actually does the work. Iterate it on a
worker thread and emit progress as `segment.end / info.duration`.

### 3.4 Known Whisper failure modes you must handle

- **Hallucination loops on silence/music.** Whisper will happily emit "Thanks for watching!" or
  repeat a phrase 40 times over a silent passage. Mitigations, all of which you should implement:
  `vad_filter=True`, `condition_on_previous_text=False`, and a **post-pass that drops any segment
  whose `no_speech_prob > 0.6`, or whose text is an n-gram repeated more than 3 times.**
- **Zero-length or inverted word spans.** Clamp: every word must satisfy `end > start`; if not,
  give it a 60 ms minimum and push the following word.
- **Words leaking past segment bounds.** Clamp word times into `[segment.start, segment.end]`.
- **Numerals and casing** vary run to run. Fine — the transcript is editable.

### 3.5 The internal data model

Normalize everything to this before anything else touches it. Every later stage reads only this:

```python
@dataclass
class Word:
    text: str
    start: float      # seconds
    end: float
    prob: float       # confidence 0..1, use it to tint low-confidence words in the editor

@dataclass
class Caption:        # one on-screen card
    words: list[Word]
    start: float      # may have lead-in padding vs words[0].start
    end: float
    style: str = "default"
```

Persist this as a **project file** (`.subproj`, JSON) next to the video so a user can close the
app and come back without re-transcribing.

---

## 4. Rendering: how to get the CapCut look

**Read this section carefully — it is the part every naive implementation gets wrong.**

CapCut's captions look good because of two things: (a) good line grouping, and (b) per-word
animated highlighting. Both are achievable in ASS, which libass renders extremely fast.

### 4.1 Line grouping (this matters more than the styling)

Turn the flat word stream into `Caption` cards using these rules, all user-adjustable in a
"Layout" panel:

- **Max words per card**: default 5 (CapCut-ish). Range 1–12.
- **Max characters per line**: default 24 for vertical/9:16, 42 for horizontal/16:9. Auto-pick
  the default from the source aspect ratio.
- **Max lines per card**: default 2.
- **Max card duration**: default 3.0 s.
- **Force a break when the gap between consecutive words exceeds** 0.45 s (a natural pause).
- **Force a break after sentence-final punctuation** (`.`, `?`, `!`, `…`).
- **Never orphan**: if the last card of a sentence would hold a single short word, pull a word
  back from the previous card.
- **Balance**: when a card wraps to 2 lines, split at the point that minimizes the length
  difference between lines, not at the raw character limit. Ragged captions look amateur.
- **Padding**: card start = `words[0].start - 0.05`, card end = `words[-1].end + 0.15`, then clamp
  so cards never overlap and never run past the video.
- **Minimum on-screen time** 0.7 s, so single short words don't flash.

### 4.2 ASS file generation

Write a UTF-8 `.ass` with a `[Script Info]` block whose `PlayResX`/`PlayResY` **exactly match the
output resolution** (get it from ffprobe). If these don't match, every font size and margin will
be wrong by a scale factor — this is the #1 source of "why are my subtitles tiny."

```
[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 2
ScaledBorderAndShadow: yes
YCbCr Matrix: TV.709
```

`WrapStyle: 2` means "no automatic wrapping" — you control the line breaks yourself with `\N`,
which you must, because you already balanced them in §4.1.

`ScaledBorderAndShadow: yes` makes outline/shadow scale with PlayRes. Without it, outlines look
hairline at 4K.

### 4.3 REVEAL MODES — the core mechanic

**This is a first-class, user-selectable property, and it is orthogonal to the visual theme.**
The same colours and font must be usable with any reveal mode, and any theme must be switchable
between modes without re-transcribing. Model it as:

```python
@dataclass
class Style:
    theme: Theme            # font, colours, outline, shadow — §4.5
    reveal: RevealMode      # HOW words appear — this section
    animation: Animation    # pop / fade / slide / none, + durations
    layout: Layout          # position, max words, safe margins — §4.1
```

Every mode below uses the same underlying trick: **one Dialogue event per word**, event *i*
spanning `[word[i].start, word[i].end]`. What differs is what that event *draws*.

Additionally, every mode emits a **lead-in event** from `card.start` to `word[0].start` and a
**tail event** from `word[-1].end` to `card.end`, so the card is already settled when the first
word lands and doesn't vanish the instant the last one ends.

---

#### Mode A — `KARAOKE_HIGHLIGHT` (the whole sentence is there, highlight moves across it)

Event *i* contains the **entire card text**, with word *i* in the active style and every other
word in the base style. Card text is visually static; only the highlight travels. Default mode,
easiest to read, best for dense speech.

Sub-variants worth exposing as a dropdown, because they read very differently:
- **Colour swap** — inactive words full white, active word in the accent colour.
- **Dim/undim** — inactive words at ~55% alpha (`{\alpha&H8C&}`), active at 100%. Feels like a fill.
- **Scale only** — no colour change, active word just scales up. Subtle, professional.
- **Underline/bar** — a coloured plate drawn behind the active word on a lower layer.

#### Mode B — `WORD_POP` (words appear one at a time and accumulate)

Event *i* shows words `0..i`; words `i+1..N` are **not yet visible**. The newest word pops in.

> ⚠️ **The trap that makes this look broken:** if you simply omit the future words, the line is
> narrower and, because it's centre-anchored, **the whole caption jitters left-and-right on every
> single word.** It looks awful and it is the #1 reason DIY versions of this mode get abandoned.
>
> **The fix: render future words fully transparent instead of omitting them.** Emit the complete
> card text every time, wrapping the not-yet-spoken words in `{\alpha&HFF&}` (fully invisible).
> They still occupy layout space, so the line width and every word's position are **locked from
> the first frame** and nothing moves. This also means your line-wrapping stays stable.

So event *i* = `[words 0..i-1 in base style] [word i in active style + pop] [words i+1..N at \alpha&HFF&]`.

#### Mode C — `ONE_WORD` (only the current word on screen, centred)

Event *i* contains **only** word *i*, centred with `\an5`. No transparency trick needed here —
re-centring is the intended effect. This is the punchy Hormozi/TikTok look. Force
`max_words_per_card = 1` in the layout when this mode is active, and let the word scale be much
larger (12–16% of video height) since it's alone on screen.

Sub-variant: **`ONE_PHRASE`** — 2–3 words at a time instead of 1, swapping as a group. Slightly
calmer, still punchy.

#### Mode D — `TYPEWRITER` (progressive reveal, no pop)

Same construction as Mode B, but the newest word has no scale animation — it just becomes opaque,
optionally with a short `\fad`. Use the same `\alpha&HFF&` position-locking trick. Calmer, good
for documentary/serious tone.

#### Mode E — `WHOLE_CARD` (no per-word anything)

One event per card, full text, no highlighting. Emit a single Dialogue event and skip the per-word
loop entirely. This is standard broadcast captioning — and it's also your **fallback whenever word
timestamps are missing or the user has heavily hand-edited the text** so that word timings no
longer line up. Always keep this path working.

---

**Implementation note:** write these as five small functions with one shared signature —
`(card: Caption, style: Style) -> list[DialogueEvent]` — behind a dispatch dict. They share the
lead-in/tail logic and the transparency helper, but they should not be one function with a mode
`if`-ladder inside it; that gets unreadable fast and these are the functions you'll iterate on
most.

A generated Mode A line looks like:

```
Dialogue: 0,0:00:03.12,0:00:03.41,Pop,,0,0,0,,{\pos(960,880)}we {\c&H00E5FF&\fscx112\fscy112\t(0,90,\fscx100\fscy100)}absolutely{\r} did
```

Tags you need:

| Tag | Use |
|---|---|
| `\pos(x,y)` | Exact placement. Prefer it over margins for predictable results |
| `\an2` / `\an5` / `\an8` | Anchor: bottom-center / middle-center / top-center |
| `\c&HBBGGRR&` | Primary colour — **note ASS is BGR, not RGB.** Write a helper; getting this backwards is a classic bug |
| `\3c&HBBGGRR&` `\bord` | Outline colour and width — the thick outline is most of the "CapCut look" |
| `\4c` `\shad` | Shadow colour and offset |
| `\fscx` `\fscy` | Horizontal/vertical scale % — this is your "pop" |
| `\t(t1,t2,tags)` | Animate tags from ms `t1` to `t2`, **relative to that event's own start** |
| `\fad(in,out)` | Fade in/out in ms |
| `\alpha&HXX&` | Transparency, `&H00&` opaque → `&HFF&` invisible. **`&HFF&` is what locks layout in Modes B/D** |
| `\1a` `\3a` `\4a` | Alpha of fill / outline / shadow separately — dim a word's fill while keeping its outline |
| `\p1 … \p0` | Vector drawing mode — used for the Boxed theme's plate and Mode A's underline bar |
| `\blur` | Soft glow when paired with a coloured outline |
| `\r` | Reset to the line's style — use it to end a per-word override |
| `\N` | Hard line break |

A word pop is `{\fscx112\fscy112\t(0,90,\fscx100\fscy100)}` — starts 12% oversized, settles over
90 ms. A card entrance is `{\fad(80,80)}` plus an optional `\t` on scale.

### 4.4 Theme separation rule

A **theme** is purely *how the text looks* — font, size, colours, outline, shadow, casing,
position. A **reveal mode** (§4.3) is *how words arrive*. **Keep these completely independent.**
Every theme ships with a recommended default mode, but the UI must let the user put any theme in
any mode, and switching either one must re-render instantly from the existing transcript with no
re-transcription.

Concretely: `ass_writer.py` takes `(captions, theme, reveal_mode, animation, layout)` and no
function in it may branch on theme *and* mode together. Themes only ever produce tag strings;
modes only ever decide which words get which tag string.

### 4.5 Ship these themes

Each is a JSON blob in `presets/` (font family, size %, base colour, active colour, outline colour
+ width, shadow, casing, letter-spacing, position, default reveal mode, default animation) so users
can duplicate and tweak them in-app. **All sizes are a percentage of video height, never pixels**,
so a theme is identical at 1080p and 4K.

| # | Theme | Look | Default mode |
|---|---|---|---|
| 1 | **Hormozi** | Anton, ALL CAPS, white base, active word yellow-green, very thick black outline, hard offset shadow, big pop | `WORD_POP` |
| 2 | **Beast** | Montserrat ExtraBold, ALL CAPS, active word cycles a colour list (yellow → green → red) per word rather than a fixed accent | `ONE_WORD` |
| 3 | **Clean Pop** | Inter SemiBold, sentence case, white, thin dark outline, active word scales only — no colour change | `KARAOKE_HIGHLIGHT` (scale-only variant) |
| 4 | **Karaoke Fill** | Base text at 55% alpha, active word snaps to 100% white. Reads as a sweeping fill | `KARAOKE_HIGHLIGHT` (dim/undim) |
| 5 | **Boxed** | Text on a semi-opaque rounded plate drawn with `\p1` on a lower layer. Maximum legibility over busy footage | `KARAOKE_HIGHLIGHT` |
| 6 | **Neon** | Bright cyan/magenta fill, coloured outline plus `\blur` for glow, dark drop shadow | `WORD_POP` |
| 7 | **Minimal** | Small, lowercase, single weight, no outline, subtle shadow, bottom-left. The "quiet" option | `TYPEWRITER` |
| 8 | **Broadcast** | Plain white on a black bar, bottom-centre, no animation whatsoever. Netflix-ish | `WHOLE_CARD` |
| 9 | **Podcast** | Large centred block in the middle third, generous line spacing, active word underlined with a coloured bar | `KARAOKE_HIGHLIGHT` (underline) |
| 10 | **Gradient Pop** | Simulated vertical gradient — the same text stacked on two layers with different colours and a clipped `\clip` region on top | `WORD_POP` |

Theme 10's gradient needs a note: **ASS has no true gradient tag.** The standard fake is to draw
the text twice on two layers, differing in colour, and use `\clip(x1,y1,x2,y2)` to reveal the top
copy only over the upper half. Implement the helper once; it's also how you'd do a two-tone outline.

**Theme 2's per-word colour cycling** means the active colour is a function of the word's index in
the card, not a constant. Make `active_colour` accept either a single colour or a list — the
writer picks `colours[i % len(colours)]`.

**Fonts**: pass `fontsdir=` to the filter and ship the open-licensed families these themes need —
**Inter, Montserrat, Anton, Bebas Neue, Poppins** (all SIL OFL, redistributable; include their
`OFL.txt` files in `assets/fonts/`). In ASS you specify the **family name** ("Anton"), *not* the
filename ("Anton-Regular.ttf"), and for weights you generally need the family name as installed
("Montserrat ExtraBold"), not `Montserrat` + a bold flag. A mismatch silently falls back to Arial
and the user just sees "my font didn't apply" — so **validate at load time** that every font a
theme references actually resolves in `assets/fonts/`, and surface a warning if not.

### 4.6 The style panel must show this live

The user picks a **theme** and a **reveal mode** as two separate controls, side by side, and the
scrub preview updates on every change. Because re-generating the `.ass` is pure string work
(milliseconds) and the preview is a single-frame ffmpeg render (§5), this can genuinely feel
instant — but only if you cache the transcript and never re-run Whisper on a style change. Make
that impossible to get wrong: transcription output is immutable input to the styling stage.

---

## 5. Preview

Two mechanisms, because one alone is not enough:

- **Scrub preview (instant, WYSIWYG).** For the current timeline position, render exactly one
  frame with subtitles burned in and show it in the Qt view:
  `ffmpeg -ss <t> -i <input> -vf "ass=subs.ass" -frames:v 1 -f image2pipe -vcodec png -`
  Read the PNG from stdout into a `QPixmap`. Put `-ss` **before** `-i` for a fast keyframe seek.
  This lands in roughly 150–400 ms, which is fast enough to feel live while dragging.
  Debounce to ~1 render in flight; cancel superseded renders.
- **Clip preview (motion).** A "Preview 10s" button that burns the current window to a temp MP4
  at reduced resolution with `h264_nvenc -preset p1` and plays it. This is the only way to
  actually judge the animation timing.

Do **not** try to reimplement ASS rendering in Qt for the preview. It will disagree with the
final export, and a preview that lies is worse than no preview.

---

## 6. Export / Render panel

Model the layout on DaVinci Resolve's Deliver page (the user supplied a screenshot of exactly
this). Preset chips across the top: **Custom Export · H.264 Master · H.265 Master · ProRes 422 HQ ·
Vertical (9:16) · Web Optimized**.

Fields:

- **File name**, **Location** (+ Browse). Remember the last-used location.
- **Video** tab: Format (MP4 / MOV / MKV) · Codec (H.264 / H.265 / AV1 / ProRes 422 HQ) ·
  Encoder (**NVENC** / CPU x264-x265) · Resolution (Source, or explicit, incl. **9:16 crop**) ·
  Frame rate (Source or explicit) · Quality (Automatic quality-based CQ, or **Restrict to N Kb/s**) ·
  Encoding profile · Keyframe interval · Preset (Fast / Balanced / Best).
- **Audio** tab: passthrough (`-c:a copy`, the default and best choice) or re-encode AAC at a
  chosen bitrate.
- **Subtitle Settings** tab — the part Resolve does badly and you should do well:
  - **Burn into video** (hardcoded), and/or
  - **Export sidecar**: `.srt` (widest support, no styling), `.ass` (full styling, VLC/mpv/Plex),
    `.vtt` (web), and/or
  - **Mux as a soft track** (`-c:s mov_text` for MP4, `-c:s ass` for MKV — MP4 cannot carry ASS
    styling, so warn and offer MKV).

### 6.1 Command construction

Burn-in, GPU path:

```
ffmpeg -y -i <input> -vf "ass=subs.ass:fontsdir=fonts" \
  -c:v h264_nvenc -preset p5 -tune hq -rc vbr -cq 19 -b:v 0 -maxrate 120M -bufsize 240M \
  -pix_fmt yuv420p -c:a copy -movflags +faststart <output>
```

Notes that will save you hours:

- **`-c:a copy` whenever possible.** Burning subtitles does not touch audio; re-encoding it is
  pure quality loss and time. Only re-encode if the container can't carry the source codec.
- **`-movflags +faststart`** for any MP4 destined for the web.
- **`-pix_fmt yuv420p`** or half of all players show a green/black video.
- **NVENC uses `-cq`, not `-crf`.** Quality-based: `-rc vbr -cq 19 -b:v 0`. Bitrate-restricted:
  `-rc cbr -b:v 120M`. Wire the panel's Automatic/Restrict radio pair to exactly this split.
- **Burn-in requires a full re-encode.** Sidecar/soft-mux does not — for those, use `-c copy` and
  the export should finish in about a second. Make that difference obvious in the UI.
- **VFR sources** (screen recordings, phone video) will drift. Pass `-fps_mode cfr` (modern
  ffmpeg; `-vsync cfr` on older builds) when burning in.
- Parse progress from `-progress pipe:1 -nostats` (gives `out_time_ms=`), not by regexing stderr.

### 6.2 ⚠️ The Windows filter-path trap

`ass=` takes a **filter argument**, so the path is parsed by ffmpeg's filtergraph parser. On
Windows, `C:\Users\you\subs.ass` breaks it three separate ways: the colon is an argument
separator, the backslashes are escapes, and spaces split tokens.

**Do not try to escape your way out of this.** The robust fix, and the one you should implement:

> Write the `.ass` to a short temp directory, set the ffmpeg subprocess's `cwd` to that
> directory, and pass the **bare relative filename**: `-vf "ass=subs.ass"`.

Same for `fontsdir` — copy fonts into a subfolder of that temp dir and pass `fontsdir=fonts`.
Also use a temp dir with **no spaces and no non-ASCII characters**, regardless of where the
user's video lives.

---

## 7. Application structure

```
VideoSubtitler/
├── INSTALL.bat                # creates .venv with python3.10, pip installs, verifies CUDA+ffmpeg
├── SubtitleStudio.bat         # launches the app, no console window
├── requirements.txt
├── README.md
├── assets/
│   ├── fonts/                 # Inter, Montserrat, Anton (SIL OFL — include the licenses)
│   └── icons/
├── presets/                   # the 10 themes as JSON (§4.5), user-extendable via Duplicate in-app
└── src/
    ├── main.py                # entry point, QApplication, dark palette
    ├── core/
    │   ├── ffmpeg.py          # binary discovery, probe(), run-with-progress, cwd-safe filters
    │   ├── transcribe.py      # faster-whisper wrapper, QThread worker, progress signals
    │   ├── model.py           # Word / Caption / Project dataclasses + .subproj JSON I/O
    │   ├── grouping.py        # §4.1 — pure functions, no Qt. Unit-test this hard
    │   ├── themes.py          # §4.5 — loads theme JSON, resolves fonts, emits ASS tag strings
    │   ├── reveal.py          # §4.3 — the 5 reveal modes, one function each, shared dispatch dict
    │   ├── ass_writer.py      # §4.2 — script header, event assembly. Pure functions, no Qt
    │   ├── srt_writer.py      # SRT / VTT sidecar output
    │   └── export.py          # builds and runs the ffmpeg command, emits progress
    └── ui/
        ├── main_window.py     # drop zone, menu, project open/save
        ├── preview.py         # frame-scrub preview + clip preview
        ├── timeline.py        # waveform + caption blocks, drag to retime, split/merge
        ├── transcript.py      # editable word/caption list; low-confidence words tinted
        ├── style_panel.py     # preset picker + live-editable style properties
        └── export_dialog.py   # §6, the Resolve-style render panel
```

**Architectural rule: `core/` must not import Qt, and `ui/` must not shell out to ffmpeg
directly.** `grouping.py`, `reveal.py`, and `ass_writer.py` in particular should be pure
input→output functions so they can be unit-tested without a GPU, a video file, or a window. Those
three files are where the quality of the output actually lives — `grouping.py` decides whether the
captions *read* well, `reveal.py` decides whether they *feel* good, and neither needs a single
frame of video to test.

**Threading**: every ffmpeg call and every transcription runs on a `QThread` worker communicating
by signals. Any blocking call on the GUI thread freezes the window and Windows paints it
"(Not Responding)". Provide a real Cancel that terminates the subprocess.

---

## 8. Build order (each milestone must run and be demoable on its own)

1. **M1 — Pipeline, no UI.** CLI script: video in → `.ass` + burned MP4 out. Proves
   faster-whisper, grouping, ASS generation, and the ffmpeg burn all work together. Nail the
   Windows path issue here, before any UI exists.
2. **M2 — Shell.** PySide6 dark window, drag-and-drop, ffprobe metadata display, Transcribe button
   with a real progress bar on a worker thread.
3. **M3 — Preview.** Frame-scrub preview + the 10s clip preview.
4. **M4 — Editing.** Transcript editor (fix text, split/merge/retime cards), timeline blocks.
5. **M5 — Style.** All 10 themes and all 5 reveal modes as independent controls, live style panel,
   per-word animation options. Build the reveal modes *first* (they're the mechanic), then the
   themes (they're just tag strings). Verify the full theme × mode matrix renders without
   crashing — 50 combinations, so generate them programmatically into a contact sheet rather than
   clicking through them by hand.
6. **M6 — Export.** The full Resolve-style render dialog, all codecs, sidecar + burn-in + soft mux,
   progress and cancel.
7. **M7 — Polish.** Project save/load, recent files, model download progress, batch queue, first-run
   setup wizard.

**M1 is already built and working — see §12 before you start.** Read the existing
`src/core/` first; do not rewrite it from scratch.

Stretch, only after M7: speaker diarization (`pyannote.audio` — needs a free Hugging Face token
and accepting a model license, so it must be optional), translation to a second language track,
auto-emoji, PyInstaller packaging.

---

## 9. The dependency landmine you will absolutely hit

**`faster-whisper` on CUDA needs cuDNN 9 and cuBLAS DLLs that are not part of a normal
`pip install`.** The symptom is a crash at model load or at the first inference:

```
Could not locate cudnn_ops64_9.dll. Please make sure it is in your library path!
```

or a hard process abort with no Python traceback at all.

The fix, which `INSTALL.bat` must do and `main.py` must verify at startup:

1. `pip install nvidia-cublas-cu12 nvidia-cudnn-cu12` (cuDNN 9.x — note that older
   `faster-whisper` 0.10 wants cuDNN **8**; if you pin the older release, pin the matching cuDNN).
2. At startup, **before** importing/constructing `WhisperModel`, prepend the wheel's DLL folders
   to the process DLL search path:

```python
import os, sys, site
from pathlib import Path
for pkg in ("nvidia/cublas/bin", "nvidia/cudnn/bin"):
    for sp in site.getsitepackages():
        p = Path(sp) / pkg
        if p.is_dir():
            os.add_dll_directory(str(p))       # Python 3.8+ on Windows
            os.environ["PATH"] = f"{p};{os.environ['PATH']}"
```

3. Wrap model construction in `try/except` and, on failure, fall back to CPU with an explanatory
   dialog rather than crashing.

Other traps worth pre-empting:

- **`numpy` 2.x** breaks several of these wheels. Pin `numpy<2`.
- **Torch is not required** by faster-whisper. Don't install a 2.5 GB CUDA torch build you never
  use — unless you enable the optional WhisperX path, which does need it.
- **First launch downloads ~3 GB.** Never let that look like a freeze.
- **Unicode / long paths** in video filenames: always pass subprocess args as a **list**, never a
  joined string, and never `shell=True`.

---

## 10. Definition of done

- A 10-minute 1080p60 talking-head MP4 goes from drop → finished burned-in export in **under 3
  minutes total** on this machine, with no terminal, no API key, and no network access after the
  one-time model download.
- Word highlighting is visibly in sync at frame level when scrubbed.
- Captions never overlap, never flash for under 0.7 s, and never leave a one-word orphan line.
- The same theme produces a visually identical result at 1080p and at 4K.
- **Every one of the 10 themes works in every one of the 5 reveal modes** (50 combinations, none
  crashing, none illegible), and switching either control re-renders the preview in under a second
  **without re-running Whisper**.
- In `WORD_POP` and `TYPEWRITER`, the caption **does not shift horizontally** as words appear —
  verify by diffing two rendered frames from the same card and confirming the already-visible words
  are at identical pixel positions.
- Sidecar `.srt` export finishes in about a second (stream copy) and opens correctly in VLC.
- Closing and reopening the app restores the project without re-transcribing.
- `grouping.py`, `reveal.py`, and `ass_writer.py` have unit tests that run with no GPU and no
  video file — including a test per reveal mode asserting the exact event count and time bounds
  for a known 3-word card.

---

## 11. How to work

Start with **M1** and get a real video through the whole pipeline before writing a single
widget. The path/escaping and cuDNN issues in §6.2 and §9 are the two things most likely to eat a
day; hit them first, while the codebase is still small. Ask before adding any dependency not
listed in §2.

---

## 12. WHAT M1 ALREADY BUILT — and what this document got wrong

**M1 is done, tested, and working.** Start from the code in `src/core/`, don't
re-derive it. `subtitle.py` is the CLI driver; `.venv` is set up; 41 unit tests
pass (`tests\test_core.py`); `tools\verify_no_shift.py` proves the layout lock at
pixel level; `tools\contact_sheet.py` renders every theme to one PNG.

Measured end-to-end on this machine: **92 s of 1080p video → transcribed, grouped,
styled, and burned in, in 16.3 s total** (3.6 s of that is model load). That
comfortably meets §10's "10-minute video in under 3 minutes".

### 12.1 Corrections to this document

Four things above are wrong or incomplete. They cost real debugging time:

1. **The "20–40x realtime" figure in §2 was optimistic.** That number describes
   *batched* inference. Plain `model.transcribe()` on `large-v3` measured
   **2.3–14.5x realtime** here. `BatchedInferencePipeline` (faster-whisper ≥ 1.1)
   measured **19–22x**, for output that matched sequential to within 0.8%
   (259 vs 261 words on a 92 s clip). Use the batched path; keep sequential as a
   fallback flag.

2. **§9 names the wrong cuDNN failure.** The real trap is that **the
   ctranslate2 ↔ cuDNN major version pairing is load-bearing in both directions**:
   ctranslate2 ≤ 4.4 links cuDNN **8**, ≥ 4.5 links cuDNN **9**. The `requirements.txt`
   in §2 as originally written (`ctranslate2==4.*` + `nvidia-cudnn-cu12==9.*`)
   resolves to a *mismatched* pair and dies with
   `Could not locate cudnn_ops_infer64_8.dll`.
   Also: `os.add_dll_directory` must glob, because the wheel layout varies
   (`nvidia/cudnn/bin` vs `nvidia/cudnn/bin/x64`).

3. **§9 says to wrap model construction in `try/except`. That does not work.**
   These failures abort the process from native code — no Python exception, no
   traceback, exit code `0xC0000409`. `try/except` never sees them. The working
   approach, implemented in `transcribe.cuda_runtime_ok()`, is to read the DLL
   names out of `ctranslate2.dll`'s import table and `ctypes.WinDLL` each one
   *before* touching the model, then degrade to CPU deliberately.

4. **ctranslate2 4.5.0 crashes in the `WhisperModel` destructor** — after a
   *successful* transcription, only once the audio is long enough (~3 min
   reproduces; 15 s does not). It looks exactly like "transcription finished then
   the app vanished with no error and no output files". Fixed in 4.8.1. This is
   why the pin is exact.

### 12.2 Non-obvious things the implementation got right

Preserve these; each was a bug first.

- **Card boundaries, not just card ends (§4.1).** Trimming only the earlier card's
  `end` on overlap still leaves neighbours overlapping by up to `lead_in` when
  speech is continuous, because `a.end + tail` collides with `b.start - lead_in`.
  Neighbours must agree a single shared boundary. The obvious unit test misses
  this — it only fails when consecutive words have **no gap** between them.
- **`BackColour` is the SHADOW colour**, not a background fill. Setting its alpha
  to 0 (the intuitive default for "no background") silently deletes the drop
  shadow from every outlined theme. `BorderStyle: 3`'s plate is drawn in
  **`OutlineColour`**, so that is what needs the translucency.
- **The gradient overlay needs its own ASS style (§4.5 theme 10).** Every reveal
  mode marks inactive words with a bare `\r`, which resets to *the event's own
  style*. Give the overlay copy `Style: SubGrad`, or `\r` snaps it back to the base
  colour and the gradient renders as flat single-colour text.
- **The separator space between words must be emitted under its own `\r`.**
  Otherwise it inherits the previous word's `\fscx112` pop and widens by 12%,
  twitching the rest of the line — a second, subtler source of the §4.3 jitter.
- **`BorderStyle: 3` beats hand-drawn `\p1` plates** for the Boxed and Broadcast
  themes. libass draws the plate itself, correctly sized, with no text-metrics
  guessing. Save `\p1` for the Podcast underline bar if you ever want it exact;
  `\u1` is a decent stand-in and is what ships.
- **Reuse the transcript.** `<video>.subproj` caches the word stream, so restyling
  never touches the GPU. This is what makes §4.6's "instant" style panel possible;
  wire M5 to it rather than re-running Whisper.

### 12.2b The app was rebuilt after first contact with the owner

The first UI was rejected, correctly. What replaced it:

- **A real player.** Single-frame libass renders cost ~180 ms each, so a
  "preview" built from them is a 3 fps slideshow with no sound. Playback now uses
  `QMediaPlayer` + `QVideoSink` at native frame rate with audio, and captions are
  drawn **in Qt** over the frame (`ui/caption_render.py`). Both paths call
  `reveal.word_state()` so they can never disagree about which words show; an
  **Exact** button renders the current frame through libass on demand.
- **A timeline** — ruler, filmstrip, audio waveform, and one subtitle track per
  layer. Everything cached per source file under `%LOCALAPPDATA%`.
- **Layers** (§12.4). The model is a list of `Layer`, not a caption list.
- **Caption sounds**, synthesised into `assets/sfx`, played on preview and mixed
  into the exported audio by building one WAV with a hit at each caption start —
  far cheaper than hundreds of `adelay` filter instances.
- **Style is usable before transcribing**, against a repeating sample caption.

Bugs that shipped in that first UI, all now guarded by `tools/verify_app.py`:
`ExportWorker` passing an argument `burn_subtitles()` didn't accept (every
burn-in crashed); `QFont.setWeight(int)` throwing inside `paintEvent` and wedging
the whole window; an ffmpeg call missing `CREATE_NO_WINDOW` that flashed a
console on Play; a `QThread` attribute named `start` shadowing `QThread.start()`.

### 12.4 Layers

A project holds **a list of layers**, each with its own words, theme, reveal
mode, position, size, words-per-card and time offset. They stack: ASS styles
`Sub0`, `Sub1`, … on ASS layer numbers `0`, `10`, … and the Qt overlay walks the
same ordered list.

`core/layers.py` is the only place that turns a `Layer` into captions. Two things
there are load-bearing:

- **`manual_cards` is a separate boolean from `card_breaks`.** Splitting or
  merging a card by hand must survive the next slider nudge, and an empty break
  list with manual mode on legitimately means "one single card" — a truthiness
  check would silently revert the user's edit.
- **Transcription deep-copies the words into every layer.** Sharing the objects
  would make an edit on one layer mutate the others.

### 12.3 Still open when M2 starts

- **Fonts are not shipped yet.** Anton, Inter, Bebas Neue and Poppins are *not*
  installed on this machine, so `hormozi` and `gradient_pop` currently fall back to
  Impact and `clean_pop` to Segoe UI. `themes.resolve_family()` detects this and
  warns. Ship the OFL files in `assets/fonts/` and pass `fontsdir=` (§4.5).
- The `line_spacing_pct` field is parsed but not applied — libass has no direct
  line-gap tag, so the Podcast theme's generous spacing needs `\pos`-per-line or an
  `fs`-scaled `\N` trick.
- `ONE_PHRASE` (§4.3 Mode C sub-variant) is not implemented.
- Only burn-in and sidecar output exist. Soft-muxing (§6) is untouched.
