"""Voice: inner-voice lines -> wav clips the overlay plays (CONTRACT.md §10).

    from show.tts import TTS, Line
    tts = TTS(audio_dir=ep_dir / "audio")
    clip = tts.speak("Kaito", "I can't lose here.", "determined")   # sync, -> Clip | None
    tts.say(Line("Kaito", "...", "hype"), on_start=..., on_skip=..., on_end=...)  # queued

Engines (dial `tts_engine`: auto | indextts | sapi | none):
  indextts  Shadow's Voice Forge worker (IndexTTS2, GPU). A persistent child process speaking JSON
            lines over stdin/stdout - NOT a server. It is only ever started by an explicit
            `start_indextts()` (episode preflight at record time, or the dashboard button), never
            implicitly by `auto`, and it refuses to start while Rocket League / Streamlabs run.
  sapi      Windows System.Speech in ONE persistent PowerShell 5.1 process -> 24 kHz mono wav.
            Zero GPU, the always-works fallback. Voice gender / rate / pitch per character from
            cast.json `voice.sapi_*`.
  none      captions only.
`auto` = indextts if its worker is already running and the speaker has a reference clip, else sapi.

The global queue speaks ONE line at a time with `voice_global_gap_s` of silence between lines, and
synthesises the next line while the current one plays. A line whose queue wait (its age minus its
own synthesis time) exceeds `voice_stale_s` is dropped to caption-only - stale thoughts are worse
than none. The narrator jumps the queue (priority 0).
"""
from __future__ import annotations

import base64
import heapq
import itertools
import json
import os
import re
import subprocess
import sys
import threading
import time
import uuid
import wave
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from .config import ROOT, SHOW
from .dials import DIALS

CREATE_NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0
VOICES_DIR = ROOT / "voices"
DEFAULT_AUDIO_DIR = ROOT / "episodes" / "_scratch" / "audio"
GPU_GUARD_PROCESSES = ("RocketLeague.exe", "Streamlabs OBS.exe", "Streamlabs Desktop.exe")

# ---------------------------------------------------------------------------------------------
# emotion -> IndexTTS2 delivery
# ---------------------------------------------------------------------------------------------
# IndexTTS2's 8 axes, IN ORDER (positional - a reorder silently swaps two emotions):
EMO_AXES = ("happy", "angry", "sad", "afraid", "disgusted", "melancholic", "surprised", "calm")


def _vec(**kw: float) -> list[float]:
    return [float(kw.get(a, 0.0)) for a in EMO_AXES]


# Each sums to <= 0.8 (upstream asserts it). Vectors, not adjectives: numbers repeat across a cast.
EMOTION_VECTORS: dict[str, list[float]] = {
    "hype":       _vec(happy=0.45, surprised=0.25),
    "calm":       _vec(calm=0.6),
    "angry":      _vec(angry=0.6, disgusted=0.1),
    "scared":     _vec(afraid=0.55, surprised=0.15),
    "smug":       _vec(happy=0.3, calm=0.3, disgusted=0.1),
    "confused":   _vec(surprised=0.35, calm=0.2, afraid=0.1),
    "sad":        _vec(sad=0.45, melancholic=0.25),
    "determined": _vec(angry=0.25, calm=0.35),
    "happy":      _vec(happy=0.6),
}
# The same moods as words, for `emotionMode: text` (used if a vector is rejected).
EMOTION_TEXT: dict[str, str] = {
    "hype": "excited and fired up, shouting with energy",
    "calm": "calm, quiet and composed",
    "angry": "angry and frustrated, spitting the words",
    "scared": "scared and panicking, breathless",
    "smug": "smug and self-satisfied, a little teasing",
    "confused": "confused and unsure, puzzled",
    "sad": "sad and deflated",
    "determined": "determined and resolute, gritted teeth",
    "happy": "happy and warm",
}
EMOTIONS = tuple(EMOTION_VECTORS)


def norm_emotion(emotion: str | None) -> str:
    e = (emotion or "calm").strip().lower()
    return e if e in EMOTION_VECTORS else "calm"


def emotion_request(emotion: str | None, char_id: str | None = None, scale: float = 1.0) -> dict:
    """The emotion part of a voice_worker request. A per-emotion clip wins (voices/<id>/<emo>.wav)."""
    e = norm_emotion(emotion)
    if char_id:
        clip = VOICES_DIR / char_id / f"{e}.wav"
        if clip.is_file():
            return {"emotionMode": "audio", "emotionAudio": str(clip)}
    v = [round(x * max(0.0, min(1.0, scale)), 4) for x in EMOTION_VECTORS[e]]
    if sum(v) <= 0:
        return {"emotionMode": "speaker"}
    return {"emotionMode": "vector", "emotionVector": v}


# ---------------------------------------------------------------------------------------------
# small helpers
# ---------------------------------------------------------------------------------------------
@dataclass
class Clip:
    path: str
    url: str
    duration_ms: int
    engine: str = ""


def wav_duration_ms(path: str | os.PathLike) -> int | None:
    try:
        with wave.open(str(path), "rb") as w:
            rate = w.getframerate() or 1
            return int(round(w.getnframes() * 1000.0 / rate))
    except (OSError, wave.Error, EOFError):
        return None


_NON_BMP = re.compile(r"[\U00010000-\U0010FFFF]")


def clean_text(text: str) -> str:
    """Strip what a TTS engine reads badly: emoji, markdown stars, stage directions, URLs."""
    t = _NON_BMP.sub("", text or "")
    t = re.sub(r"https?://\S+", "", t)
    t = re.sub(r"[*_`~#]+", "", t)
    t = re.sub(r"\s+", " ", t).strip()
    return t[:400]


def guard_processes_running(names=GPU_GUARD_PROCESSES) -> list[str]:
    """Which GPU-contention processes are running (Rocket League, Streamlabs). Light: one tasklist."""
    if sys.platform != "win32":
        return []
    try:
        out = subprocess.run(["tasklist", "/fo", "csv", "/nh"], capture_output=True, text=True,
                             timeout=10, creationflags=CREATE_NO_WINDOW).stdout.lower()
    except (OSError, subprocess.SubprocessError):
        return []
    return [n for n in names if f'"{n.lower()}"' in out]


def _char_info(char: str) -> tuple[str, dict]:
    """(id, voice dict) for a character name/id/account, or the narrator."""
    if char.lower() in ("narrator", SHOW.narrator.get("name", "Narrator").lower()):
        return "narrator", dict(SHOW.narrator.get("voice", {}))
    for c in SHOW.characters:
        if char.lower() in (c.name.lower(), c.id.lower(), c.account.lower()):
            return c.id, dict(c.voice)
    return char.lower(), {}


def ref_clip(char_id: str, voice: dict | None = None) -> Path | None:
    cands = []
    if voice and voice.get("engine_ref"):
        cands.append(ROOT / voice["engine_ref"])
    cands.append(VOICES_DIR / char_id / "ref.wav")
    for p in cands:
        if p.is_file() and p.stat().st_size > 1000:
            return p
    return None


# ---------------------------------------------------------------------------------------------
# engine: SAPI (System.Speech via ONE persistent PowerShell 5.1 process)
# ---------------------------------------------------------------------------------------------
# Jobs travel as ASCII JSON lines (ensure_ascii) because PS 5.1 decodes piped stdin with the OEM
# code page; \uXXXX escapes survive that and ConvertFrom-Json decodes them.
SAPI_SCRIPT = r"""
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$syn = New-Object System.Speech.Synthesis.SpeechSynthesizer
$voices = @($syn.GetInstalledVoices() | Where-Object { $_.Enabled } | ForEach-Object { $_.VoiceInfo })
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(24000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
[Console]::Out.WriteLine('READY ' + (($voices | ForEach-Object { $_.Name + ':' + $_.Gender }) -join ','))
[Console]::Out.Flush()
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($line -eq $null) { break }
  if ($line.Trim() -eq '') { continue }
  try {
    $job = $line | ConvertFrom-Json
    if ($job.op -eq 'quit') { break }
    $want = [string]$job.gender
    $v = $voices | Where-Object { $_.Gender.ToString() -eq $want } | Select-Object -First 1
    if ($v -eq $null) { $v = $voices | Select-Object -First 1 }
    $syn.SelectVoice($v.Name)
    $syn.Rate = [int]$job.rate
    $syn.Volume = 100
    $syn.SetOutputToWaveFile([string]$job.out, $fmt)
    $syn.SpeakSsml([string]$job.ssml)
    $syn.SetOutputToNull()
    [Console]::Out.WriteLine('OK ' + $job.id)
  } catch {
    try { $syn.SetOutputToNull() } catch {}
    [Console]::Out.WriteLine('ERR ' + $job.id + ' ' + ($_.Exception.Message -replace '\s+', ' '))
  }
  [Console]::Out.Flush()
}
$syn.Dispose()
"""


def _xml_escape(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def sapi_ssml(text: str, pitch: int = 0) -> str:
    """SSML for one line. `pitch` is cast.json's sapi_pitch (-10..10) -> relative percent."""
    p = max(-10, min(10, int(pitch)))
    body = _xml_escape(text)
    if p:
        body = f'<prosody pitch="{p * 6:+d}%">{body}</prosody>'
    return ('<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="en-US">'
            f"{body}</speak>")


def sapi_job(text: str, out: str, voice: dict, emotion: str = "calm", job_id: str = "") -> dict:
    """One SAPI job: gender / rate / pitch from the character's voice block, nudged by emotion."""
    gender = str(voice.get("sapi_voice", "male")).strip().lower()
    gender = "Female" if gender.startswith("f") else "Male"
    rate = int(voice.get("sapi_rate", 0))
    pitch = int(voice.get("sapi_pitch", 0))
    e = norm_emotion(emotion)
    rate += {"hype": 1, "angry": 1, "scared": 2, "sad": -1, "calm": 0}.get(e, 0)
    pitch += {"hype": 1, "scared": 2, "sad": -1, "angry": -1}.get(e, 0)
    return {"id": job_id, "gender": gender, "rate": max(-10, min(10, rate)),
            "out": out, "ssml": sapi_ssml(text, pitch)}


def sapi_command() -> list[str]:
    """powershell.exe argv for the persistent SAPI worker (script travels as -EncodedCommand)."""
    enc = base64.b64encode(SAPI_SCRIPT.encode("utf-16-le")).decode("ascii")
    return ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-EncodedCommand", enc]


class SapiEngine:
    name = "sapi"

    def __init__(self, timeout_s: float = 20.0) -> None:
        self.timeout_s = timeout_s
        self._proc: subprocess.Popen | None = None
        self._lock = threading.Lock()
        self.voices = ""
        self.last_error = ""

    def available(self) -> bool:
        return sys.platform == "win32"

    def _ensure(self) -> subprocess.Popen:
        if self._proc and self._proc.poll() is None:
            return self._proc
        self._proc = subprocess.Popen(
            sapi_command(), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, text=True, encoding="ascii", errors="replace",
            bufsize=1, creationflags=CREATE_NO_WINDOW)
        line = self._readline(self._proc, 30.0)
        if not line or not line.startswith("READY"):
            self.close()
            raise RuntimeError(f"SAPI worker did not start: {line!r}")
        self.voices = line[6:].strip()
        return self._proc

    @staticmethod
    def _readline(proc: subprocess.Popen, timeout: float) -> str | None:
        box: list[str] = []
        t = threading.Thread(target=lambda: box.append(proc.stdout.readline()), daemon=True)
        t.start()
        t.join(timeout)
        return box[0].strip() if box else None

    def synth(self, text: str, out: Path, char_id: str, voice: dict, emotion: str) -> bool:
        with self._lock:
            jid = uuid.uuid4().hex[:8]
            try:
                proc = self._ensure()
                proc.stdin.write(json.dumps(sapi_job(text, str(out), voice, emotion, jid)) + "\n")
                proc.stdin.flush()
                line = self._readline(proc, self.timeout_s)
            except (OSError, RuntimeError, ValueError) as e:
                self.last_error = str(e)
                self.close()
                return False
            if line is None:
                self.last_error = "SAPI timed out"
                self.close()
                return False
            if not line.startswith(f"OK {jid}"):
                self.last_error = line
                return False
            return out.is_file()

    def close(self) -> None:
        p, self._proc = self._proc, None
        if p and p.poll() is None:
            try:
                p.stdin.write('{"op":"quit"}\n')
                p.stdin.flush()
                p.wait(2)
            except Exception:
                p.kill()


# ---------------------------------------------------------------------------------------------
# engine: IndexTTS2 via Shadow's Voice Forge worker
# ---------------------------------------------------------------------------------------------
RESULT_SENTINEL = "__SHADOW_VOICE_RESULT__"
EVENT_SENTINEL = "__SHADOW_VOICE_EVENT__"


def find_indextts() -> dict:
    """Locate the install the same way Shadow's voiceForge.ts does. Pure filesystem, no process."""
    roots = [os.environ.get("SHADOW_INDEXTTS_PATH"),
             str(Path.home() / "Documents" / "index-tts"),
             str(Path.home() / "OneDrive" / "Documents" / "index-tts")]
    info: dict[str, Any] = {"root": None, "python": None, "checkpoints": None, "worker": None,
                            "ready": False, "blockers": []}
    for r in roots:
        if r and Path(r).is_dir():
            info["root"] = r
            break
    shadow = Path(SHOW.paths.get("shadow", ROOT.parent / "Shadow"))
    worker = shadow / "APPLICATION" / "scripts" / "voice" / "voice_worker.py"
    info["worker"] = str(worker) if worker.is_file() else None
    if not info["root"]:
        info["blockers"].append("IndexTTS2 not installed (Documents\\index-tts or SHADOW_INDEXTTS_PATH)")
    else:
        py = Path(info["root"]) / ".venv" / "Scripts" / "python.exe"
        info["python"] = str(py) if py.is_file() else None
        ck = Path(info["root"]) / "checkpoints"
        try:
            if ck.is_dir() and any(f.is_file() and f.stat().st_size > 50 * 1024 * 1024 for f in ck.iterdir()):
                info["checkpoints"] = str(ck)
        except OSError:
            pass
        if not info["python"]:
            info["blockers"].append("no .venv in the index-tts checkout (run `uv sync` there)")
        if not info["checkpoints"]:
            info["blockers"].append("IndexTTS-2 weights missing (checkpoints has no file > 50 MB)")
    if not info["worker"]:
        info["blockers"].append(f"voice_worker.py not found at {worker}")
    info["ready"] = not info["blockers"]
    return info


class IndexTTSEngine:
    """Client for `voice_worker.py` serve mode. Started ONLY by start(); never by speak()."""
    name = "indextts"

    def __init__(self, stall_s: float = 150.0, popen=subprocess.Popen) -> None:
        self.stall_s = stall_s
        self._popen = popen
        self._proc = None
        self._lock = threading.Lock()          # one request at a time (one GPU, one pipe)
        self._results: dict[str, dict] = {}
        self._cv = threading.Condition()
        self._activity = time.time()
        self.loaded = False
        self.device = None
        self.load_seconds = None
        self.last_error = ""
        self.stderr_tail: list[str] = []

    # -- lifecycle --------------------------------------------------------------------------
    def running(self) -> bool:
        return self._proc is not None and self._proc.poll() is None

    def command(self, info: dict) -> list[str]:
        return [info["python"], info["worker"], "--model-dir", info["checkpoints"]]

    def start(self, preload: bool = True, force: bool = False) -> dict:
        """Spawn the worker (and load the model). RECORD TIME ONLY: GPU, ~24 s warm load."""
        if self.running():
            return {"ok": True, "already": True}
        info = find_indextts()
        if not info["ready"]:
            return {"ok": False, "error": "; ".join(info["blockers"])}
        busy = [] if force else guard_processes_running()
        if busy:
            return {"ok": False, "error": f"refusing to load IndexTTS2 while {', '.join(busy)} running "
                                          "(GPU contention makes it 9x slower)"}
        env = dict(os.environ, PYTHONUNBUFFERED="1", PYTHONIOENCODING="utf-8")
        self._attach(self._popen(self.command(info), cwd=info["root"], env=env,
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                 creationflags=CREATE_NO_WINDOW))
        if preload:
            r = self.request({"op": "load"})
            if not r.get("ok"):
                return r
            self.loaded = True
            self.device = r.get("device")
            self.load_seconds = r.get("loadSeconds")
        return {"ok": True, "device": self.device, "loadSeconds": self.load_seconds}

    def _attach(self, proc) -> None:
        """Adopt a running worker process (binary pipes) and start draining both streams."""
        self._proc = proc
        threading.Thread(target=self._read_stdout, args=(proc,), daemon=True, name="indextts-out").start()
        threading.Thread(target=self._read_stderr, args=(proc,), daemon=True, name="indextts-err").start()

    def close(self) -> None:
        p, self._proc = self._proc, None
        if p and p.poll() is None:
            try:
                p.stdin.write(b'{"op":"shutdown"}\n')
                p.stdin.flush()
                p.wait(5)
            except Exception:
                p.kill()
        self.loaded = False

    # -- protocol ---------------------------------------------------------------------------
    def _read_stdout(self, p) -> None:
        for raw in iter(p.stdout.readline, b""):
            self._activity = time.time()
            line = raw.decode("utf-8", "replace").strip()
            if line.startswith(RESULT_SENTINEL):
                try:
                    msg = json.loads(line[len(RESULT_SENTINEL):].strip())
                except ValueError:
                    continue
                with self._cv:
                    self._results[str(msg.get("id"))] = msg
                    self._cv.notify_all()
        with self._cv:
            self._cv.notify_all()

    def _read_stderr(self, p) -> None:
        for raw in iter(p.stderr.readline, b""):          # must drain or the pipe fills and blocks
            self._activity = time.time()
            self.stderr_tail = (self.stderr_tail + [raw.decode("utf-8", "replace").rstrip()])[-40:]

    def request(self, req: dict) -> dict:
        """Send one request, wait for its result. Timeout is STALL-based: resets on every byte."""
        if not self.running():
            return {"ok": False, "error": "IndexTTS2 worker not running"}
        with self._lock:
            rid = req.setdefault("id", uuid.uuid4().hex[:10])
            self._activity = time.time()
            try:
                self._proc.stdin.write((json.dumps(req) + "\n").encode("ascii"))
                self._proc.stdin.flush()
            except OSError as e:
                return {"ok": False, "error": f"worker pipe: {e}"}
            with self._cv:
                while rid not in self._results:
                    if not self.running():
                        return {"ok": False, "error": "IndexTTS2 worker exited"}
                    if time.time() - self._activity > self.stall_s:
                        return {"ok": False, "error": f"IndexTTS2 silent for {self.stall_s:.0f}s"}
                    self._cv.wait(1.0)
                return self._results.pop(rid)

    def build_request(self, text: str, out: Path, char_id: str, voice: dict, emotion: str) -> dict | None:
        ref = ref_clip(char_id, voice)
        if not ref:
            return None
        req = {"op": "speak", "text": text, "speaker": str(ref), "out": str(out)}
        req.update(emotion_request(emotion, char_id, float(voice.get("emotion_scale", 1.0))))
        return req

    def synth(self, text: str, out: Path, char_id: str, voice: dict, emotion: str) -> bool:
        req = self.build_request(text, out, char_id, voice, emotion)
        if req is None:
            self.last_error = f"no reference clip for {char_id}"
            return False
        r = self.request(req)
        if not r.get("ok") and req.get("emotionMode") == "vector":
            # a rejected vector should not cost the line: retry with the same mood in words
            req.pop("emotionVector", None)
            req.pop("id", None)
            req.update(emotionMode="text", emotionText=EMOTION_TEXT[norm_emotion(emotion)])
            r = self.request(req)
        if not r.get("ok"):
            self.last_error = str(r.get("error"))
            return False
        return out.is_file()


class NoneEngine:
    name = "none"

    def available(self) -> bool:
        return True

    def synth(self, *a, **k) -> bool:
        return False

    def close(self) -> None:
        pass


# ---------------------------------------------------------------------------------------------
# the TTS facade + global queue
# ---------------------------------------------------------------------------------------------
@dataclass
class Line:
    char: str
    text: str
    emotion: str = "calm"
    voice: bool = True                         # False -> caption only, never synthesised
    created: float = field(default_factory=time.time)
    priority: int = 1                          # 0 = narrator (jumps the queue)
    meta: dict = field(default_factory=dict)


Callback = Callable[..., Any]


class TTS:
    def __init__(self, audio_dir: Path | str | None = None, url_prefix: str = "/audio/",
                 engines: dict | None = None, dials=DIALS, clock: Callable[[], float] = time.time,
                 sleep: Callable[[float], None] = time.sleep) -> None:
        self.audio_dir = Path(audio_dir) if audio_dir else DEFAULT_AUDIO_DIR
        self.url_prefix = url_prefix
        self.dials = dials
        self.clock = clock
        self.sleep = sleep
        self.engines = engines or {"indextts": IndexTTSEngine(), "sapi": SapiEngine(), "none": NoneEngine()}
        self._heap: list = []
        self._seq = itertools.count()
        self._cv = threading.Condition()
        self._busy_until = 0.0
        self._worker: threading.Thread | None = None
        self._stop = False
        self.speaking: dict | None = None
        self.stats = {"spoken": 0, "stale": 0, "failed": 0, "caption": 0}
        self.last_engine = ""

    # -- config -----------------------------------------------------------------------------
    def set_audio_dir(self, path: Path | str) -> None:
        self.audio_dir = Path(path)

    def resolve_engine(self, char_id: str | None = None, voice: dict | None = None) -> str:
        want = str(self.dials.get("tts_engine", "auto")).lower()
        idx = self.engines.get("indextts")
        idx_ok = bool(idx and getattr(idx, "running", lambda: False)() and
                      (char_id is None or ref_clip(char_id, voice)))
        if want == "indextts":
            return "indextts" if idx_ok else "sapi"      # never starts the worker implicitly
        if want in ("sapi", "none"):
            return want
        return "indextts" if idx_ok else "sapi"

    def start_indextts(self, force: bool = False) -> dict:
        """Record-time step: spawn + load the IndexTTS2 worker. Never called by tests."""
        return self.engines["indextts"].start(preload=True, force=force)

    def status(self) -> dict:
        idx = self.engines.get("indextts")
        return {"dial": self.dials.get("tts_engine", "auto"), "resolved": self.resolve_engine(),
                "indextts_running": bool(idx and idx.running()),
                "indextts_loaded": bool(idx and getattr(idx, "loaded", False)),
                "speaking": self.speaking, "queued": len(self._heap), **self.stats,
                "last_engine": self.last_engine}

    # -- synchronous synthesis --------------------------------------------------------------
    def speak(self, char: str, text: str, emotion: str = "calm") -> Clip | None:
        text = clean_text(text)
        if not text:
            return None
        char_id, voice = _char_info(char)
        name = self.resolve_engine(char_id, voice)
        order = [name] + (["sapi"] if name == "indextts" else [])
        for eng_name in order:
            eng = self.engines.get(eng_name)
            if eng is None or eng_name == "none":
                return None
            self.audio_dir.mkdir(parents=True, exist_ok=True)
            fname = f"{int(self.clock() * 1000)}_{char_id}_{uuid.uuid4().hex[:6]}.wav"
            out = self.audio_dir / fname
            try:
                ok = eng.synth(text, out, char_id, voice, emotion)
            except Exception as e:                    # an engine must never take the show down
                ok = False
                eng.last_error = str(e)
            if ok:
                dur = wav_duration_ms(out)
                if dur and dur > 0:
                    self.last_engine = eng_name
                    return Clip(str(out), self.url_prefix + fname, dur, eng_name)
        return None

    # -- the global queue -------------------------------------------------------------------
    def say(self, line: Line, on_start: Callback | None = None, on_skip: Callback | None = None,
            on_end: Callback | None = None) -> None:
        """Queue a line. on_start(line, clip) when it should play; on_end(line, clip) when done;
        on_skip(line, reason) with reason in caption|stale|failed (show the caption, no audio)."""
        self._ensure_worker()
        with self._cv:
            heapq.heappush(self._heap, (line.priority, next(self._seq), line, on_start, on_skip, on_end))
            self._cv.notify()

    def _ensure_worker(self) -> None:
        if self._worker and self._worker.is_alive():
            return
        self._stop = False
        self._worker = threading.Thread(target=self._run, daemon=True, name="tts-queue")
        self._worker.start()

    def stop(self) -> None:
        with self._cv:
            self._stop = True
            self._cv.notify_all()
        for e in self.engines.values():
            try:
                e.close()
            except Exception:
                pass

    def _stale(self, line: Line, synth_s: float = 0.0) -> bool:
        return (self.clock() - line.created - synth_s) > float(self.dials.get("voice_stale_s", 8.0))

    @staticmethod
    def _call(fn, *a) -> None:
        if fn:
            try:
                fn(*a)
            except Exception:
                pass

    def _run(self) -> None:
        while True:
            with self._cv:
                while not self._heap and not self._stop:
                    self._cv.wait(0.5)
                if self._stop:
                    return
                _, _, line, on_start, on_skip, on_end = heapq.heappop(self._heap)
            if not line.voice or self.resolve_engine() == "none":
                self.stats["caption"] += 1
                self._call(on_skip, line, "caption")
                continue
            if self._stale(line):
                self.stats["stale"] += 1
                self._call(on_skip, line, "stale")
                continue
            t0 = self.clock()
            clip = self.speak(line.char, line.text, line.emotion)
            synth_s = self.clock() - t0
            if clip is None:
                self.stats["failed"] += 1
                self._call(on_skip, line, "failed")
                continue
            gap = float(self.dials.get("voice_global_gap_s", 1.5))
            wait = (self._busy_until + gap) - self.clock()
            if wait > 0:
                self.sleep(wait)
            if self._stale(line, synth_s):
                self.stats["stale"] += 1
                self._call(on_skip, line, "stale")
                continue
            self._busy_until = self.clock() + clip.duration_ms / 1000.0
            self.stats["spoken"] += 1
            self.speaking = {"char": line.char, "until": self._busy_until, "clip": clip.url}
            self._call(on_start, line, clip)
            t = threading.Timer(clip.duration_ms / 1000.0, self._finish, args=(line, clip, on_end))
            t.daemon = True
            t.start()

    def _finish(self, line: Line, clip: Clip, on_end) -> None:
        if self.speaking and self.speaking.get("clip") == clip.url:
            self.speaking = None
        self._call(on_end, line, clip)

    def busy(self) -> bool:
        """True while a voice line is playing (the director must not cut)."""
        return self.clock() < self._busy_until


if __name__ == "__main__":       # py -3 -m show.tts "Kaito" "text" [emotion]  -> one SAPI clip
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("char")
    ap.add_argument("text")
    ap.add_argument("emotion", nargs="?", default="calm")
    ap.add_argument("--engine", default="sapi", choices=["sapi", "indextts"])
    ap.add_argument("--out", default=str(DEFAULT_AUDIO_DIR))
    a = ap.parse_args()

    class _D(dict):
        def get(self, k, d=None):
            return super().get(k, DIALS.get(k, d))

    tts = TTS(audio_dir=a.out, dials=_D(tts_engine=a.engine))
    if a.engine == "indextts":
        print(tts.start_indextts())
    c = tts.speak(a.char, a.text, a.emotion)
    print(c)
    tts.stop()
