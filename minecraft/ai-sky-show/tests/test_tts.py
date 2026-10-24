"""TTS: queue ordering / gap / staleness / priority, engine resolution, SAPI command, IndexTTS protocol.
Headless: fake engines only. Never starts IndexTTS2, never runs SAPI."""
import base64
import json
import os
import subprocess
import sys
import tempfile
import textwrap
import threading
import time
import unittest
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "orchestrator"))

from show import tts as T  # noqa: E402


def write_wav(path, ms, rate=24000):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"\x00\x00" * int(rate * ms / 1000))


class Dials(dict):
    def get(self, k, d=None):
        return super().get(k, d)


class FakeEngine:
    name = "sapi"

    def __init__(self, ms=60, fail=False, block: threading.Event | None = None):
        self.ms, self.fail, self.block = ms, fail, block
        self.calls = []
        self.last_error = ""

    def synth(self, text, out, char_id, voice, emotion):
        self.calls.append((char_id, text, emotion))
        if self.block is not None:
            self.block.wait(5)
            self.block = None
        if self.fail:
            return False
        write_wav(out, self.ms)
        return True

    def close(self):
        pass


class FakeIdx(FakeEngine):
    name = "indextts"

    def __init__(self, running=False, **kw):
        super().__init__(**kw)
        self._running = running

    def running(self):
        return self._running


def make(dials=None, **eng):
    tmp = tempfile.mkdtemp(prefix="aisky_tts_")
    engines = {"sapi": eng.get("sapi", FakeEngine()), "indextts": eng.get("indextts", FakeIdx()),
               "none": T.NoneEngine()}
    d = Dials(tts_engine="auto", voice_global_gap_s=0.05, voice_stale_s=8.0)
    d.update(dials or {})
    return T.TTS(audio_dir=tmp, engines=engines, dials=d), engines


class Collector:
    def __init__(self):
        self.events = []
        self.done = threading.Event()
        self.lock = threading.Lock()

    def start(self, line, clip):
        with self.lock:
            self.events.append(("start", line.char, line.text, time.time(), clip))

    def skip(self, line, reason):
        with self.lock:
            self.events.append(("skip", line.char, line.text, time.time(), reason))

    def end(self, line, clip):
        with self.lock:
            self.events.append(("end", line.char, line.text, time.time(), clip))

    def wait(self, n, timeout=5):
        t0 = time.time()
        while time.time() - t0 < timeout:
            with self.lock:
                if len(self.events) >= n:
                    return list(self.events)
            time.sleep(0.01)
        return list(self.events)


class QueueTests(unittest.TestCase):
    def test_speak_returns_clip_with_measured_duration(self):
        tts, _ = make(sapi=FakeEngine(ms=250))
        clip = tts.speak("Kaito", "LET'S GO", "hype")
        self.assertIsNotNone(clip)
        self.assertEqual(clip.engine, "sapi")
        self.assertTrue(clip.url.startswith("/audio/") and clip.url.endswith(".wav"))
        self.assertAlmostEqual(clip.duration_ms, 250, delta=2)
        self.assertTrue(Path(clip.path).is_file())

    def test_fifo_order_one_at_a_time_with_gap(self):
        tts, _ = make(sapi=FakeEngine(ms=80), dials={"voice_global_gap_s": 0.1})
        c = Collector()
        for i in range(3):
            tts.say(T.Line("Kaito", f"line {i}"), c.start, c.skip, c.end)
        evs = c.wait(6)
        starts = [e for e in evs if e[0] == "start"]
        self.assertEqual([e[2] for e in starts], ["line 0", "line 1", "line 2"])
        for a, b in zip(starts, starts[1:]):
            self.assertGreaterEqual(b[3] - a[3], 0.08 + 0.1 - 0.02, "next line must wait duration + gap")
        tts.stop()

    def test_end_fires_after_duration_and_busy_flag(self):
        tts, _ = make(sapi=FakeEngine(ms=200))
        c = Collector()
        tts.say(T.Line("Ren", "calculated."), c.start, c.skip, c.end)
        c.wait(1)
        self.assertTrue(tts.busy())
        evs = c.wait(2)
        self.assertEqual([e[0] for e in evs], ["start", "end"])
        self.assertGreaterEqual(evs[1][3] - evs[0][3], 0.18)
        self.assertFalse(tts.busy())
        tts.stop()

    def test_stale_line_dropped_to_caption(self):
        tts, eng = make()
        c = Collector()
        tts.say(T.Line("Mochi", "old thought", created=time.time() - 30), c.start, c.skip, c.end)
        evs = c.wait(1)
        self.assertEqual(evs[0][0], "skip")
        self.assertEqual(evs[0][4], "stale")
        self.assertEqual(eng["sapi"].calls, [], "a stale line must not even be synthesised")
        tts.stop()

    def test_line_goes_stale_while_waiting_for_previous(self):
        tts, _ = make(sapi=FakeEngine(ms=600), dials={"voice_stale_s": 0.3, "voice_global_gap_s": 0.05})
        c = Collector()
        tts.say(T.Line("Kaito", "first"), c.start, c.skip, c.end)
        tts.say(T.Line("Yuki", "second"), c.start, c.skip, c.end)
        evs = c.wait(3)
        kinds = {(e[0], e[2]) for e in evs}
        self.assertIn(("start", "first"), kinds)
        self.assertIn(("skip", "second"), kinds)
        tts.stop()

    def test_narrator_jumps_the_queue(self):
        gate = threading.Event()
        tts, _ = make(sapi=FakeEngine(ms=30, block=gate))
        c = Collector()
        tts.say(T.Line("Kaito", "blocking"), c.start, c.skip, c.end)
        time.sleep(0.1)                      # worker is now stuck synthesising 'blocking'
        tts.say(T.Line("Ren", "char line"), c.start, c.skip, c.end)
        tts.say(T.Line("Narrator", "narr line", priority=0), c.start, c.skip, c.end)
        gate.set()
        starts = [e[2] for e in c.wait(6) if e[0] == "start"]
        self.assertEqual(starts, ["blocking", "narr line", "char line"])
        tts.stop()

    def test_voice_false_and_engine_none_are_caption_only(self):
        tts, eng = make()
        c = Collector()
        tts.say(T.Line("Yuki", "quiet", voice=False), c.start, c.skip, c.end)
        self.assertEqual(c.wait(1)[0][4], "caption")
        tts.dials["tts_engine"] = "none"
        tts.say(T.Line("Yuki", "quieter"), c.start, c.skip, c.end)
        self.assertEqual(c.wait(2)[1][4], "caption")
        self.assertEqual(eng["sapi"].calls, [])
        tts.stop()

    def test_failed_synthesis_is_caption_only(self):
        tts, _ = make(sapi=FakeEngine(fail=True))
        c = Collector()
        tts.say(T.Line("Ren", "x"), c.start, c.skip, c.end)
        self.assertEqual(c.wait(1)[0][4], "failed")
        tts.stop()


class EngineResolution(unittest.TestCase):
    def setUp(self):
        self._ref = T.ref_clip

    def tearDown(self):
        T.ref_clip = self._ref

    def test_auto_is_sapi_until_indextts_worker_runs(self):
        tts, _ = make(indextts=FakeIdx(running=False))
        self.assertEqual(tts.resolve_engine("kaito", {}), "sapi")

    def test_auto_uses_indextts_only_with_a_reference_clip(self):
        tts, eng = make(indextts=FakeIdx(running=True))
        T.ref_clip = lambda cid, voice=None: None
        self.assertEqual(tts.resolve_engine("kaito", {}), "sapi")
        T.ref_clip = lambda cid, voice=None: Path("ref.wav")
        self.assertEqual(tts.resolve_engine("kaito", {}), "indextts")
        clip = tts.speak("Kaito", "hi", "hype")
        self.assertEqual(clip.engine, "indextts")

    def test_explicit_indextts_never_starts_the_worker(self):
        tts, eng = make(indextts=FakeIdx(running=False), dials={"tts_engine": "indextts"})
        self.assertEqual(tts.resolve_engine("kaito", {}), "sapi")
        tts.speak("Kaito", "hi")
        self.assertEqual(eng["indextts"].calls, [])

    def test_indextts_failure_falls_back_to_sapi(self):
        tts, eng = make(indextts=FakeIdx(running=True, fail=True))
        T.ref_clip = lambda cid, voice=None: Path("ref.wav")
        clip = tts.speak("Ren", "fallback please")
        self.assertEqual(clip.engine, "sapi")
        self.assertEqual(len(eng["indextts"].calls), 1)


class SapiCommand(unittest.TestCase):
    def test_command_is_powershell_51_with_encoded_script(self):
        argv = T.sapi_command()
        self.assertEqual(argv[0], "powershell.exe")
        for flag in ("-NoProfile", "-NonInteractive", "-EncodedCommand"):
            self.assertIn(flag, argv)
        script = base64.b64decode(argv[argv.index("-EncodedCommand") + 1]).decode("utf-16-le")
        self.assertEqual(script, T.SAPI_SCRIPT)
        self.assertIn("SpeechAudioFormatInfo(24000", script)
        self.assertIn("SpeakSsml", script)

    def test_job_uses_cast_voice_settings(self):
        job = T.sapi_job("hehehe", "C:/x.wav", {"sapi_voice": "female", "sapi_rate": 3, "sapi_pitch": 3}, "calm", "j1")
        self.assertEqual(job["gender"], "Female")
        self.assertEqual(job["rate"], 3)
        self.assertIn('pitch="+18%"', job["ssml"])
        male = T.sapi_job("x", "o.wav", {"sapi_voice": "male", "sapi_rate": 12, "sapi_pitch": 0}, "scared")
        self.assertEqual(male["gender"], "Male")
        self.assertEqual(male["rate"], 10, "rate clamps to SAPI's -10..10")

    def test_ssml_escapes_and_job_line_is_ascii(self):
        job = T.sapi_job('a <b> & "c" café', "o.wav", {}, "calm")
        self.assertIn("&lt;b&gt; &amp; &quot;c&quot;", job["ssml"])
        line = json.dumps(job)
        line.encode("ascii")                      # PS 5.1 reads stdin in the OEM code page

    def test_clean_text_strips_emoji_and_markdown(self):
        self.assertEqual(T.clean_text("**LETS GO** \U0001F525 https://x.y lol"), "LETS GO lol")

    def test_char_info_maps_names_to_cast_voice(self):
        cid, voice = T._char_info("Mochi")
        self.assertEqual(cid, "mochi")
        self.assertEqual(voice.get("sapi_voice"), "female")
        self.assertEqual(T._char_info("Narrator")[0], "narrator")


class IndexTTSProtocol(unittest.TestCase):
    def test_emotion_vectors_are_valid(self):
        for e, v in T.EMOTION_VECTORS.items():
            self.assertEqual(len(v), 8, e)
            self.assertLessEqual(sum(v), 0.8 + 1e-9, e)
            self.assertGreater(sum(v), 0, e)
            self.assertIn(e, T.EMOTION_TEXT)
        self.assertEqual(T.norm_emotion("FURIOUS"), "calm")
        r = T.emotion_request("scared", None)
        self.assertEqual(r["emotionMode"], "vector")
        self.assertEqual(r["emotionVector"][T.EMO_AXES.index("afraid")], 0.55)

    def test_per_emotion_clip_wins(self):
        old = T.VOICES_DIR
        with tempfile.TemporaryDirectory() as d:
            T.VOICES_DIR = Path(d)
            (Path(d) / "kaito").mkdir()
            write_wav(Path(d) / "kaito" / "angry.wav", 100)
            try:
                r = T.emotion_request("angry", "kaito")
                self.assertEqual(r["emotionMode"], "audio")
                self.assertTrue(r["emotionAudio"].endswith("angry.wav"))
            finally:
                T.VOICES_DIR = old

    def test_find_indextts_is_pure(self):
        info = T.find_indextts()                  # filesystem only; must not spawn anything
        self.assertIn("ready", info)
        self.assertIsInstance(info["blockers"], list)

    def test_worker_round_trip_with_fake_worker(self):
        """A stand-in for voice_worker.py serve mode: chatter on stdout, sentinel results, real wavs."""
        fake = textwrap.dedent(r'''
            import json, sys, wave
            print("loading torch... noise that is NOT protocol", flush=True)
            sys.stderr.write("tqdm 10%\n"); sys.stderr.flush()
            for line in sys.stdin:
                req = json.loads(line)
                if req.get("op") == "shutdown":
                    print("__SHADOW_VOICE_RESULT__ " + json.dumps({"ok": True, "id": req.get("id")}), flush=True)
                    break
                if req.get("op") == "load":
                    print("__SHADOW_VOICE_RESULT__ " + json.dumps({"ok": True, "id": req["id"], "device": "fake"}), flush=True)
                    continue
                if req.get("emotionMode") == "vector" and req.get("text") == "reject vector":
                    print("__SHADOW_VOICE_RESULT__ " + json.dumps({"ok": False, "id": req["id"], "error": "bad vector"}), flush=True)
                    continue
                with wave.open(req["out"], "wb") as w:
                    w.setnchannels(1); w.setsampwidth(2); w.setframerate(22050); w.writeframes(b"\0\0" * 22050)
                print("__SHADOW_VOICE_EVENT__ " + json.dumps({"stage": "speaking"}), flush=True)
                print("__SHADOW_VOICE_RESULT__ " + json.dumps({"ok": True, "id": req["id"], "path": req["out"],
                      "mode": req.get("emotionMode")}), flush=True)
        ''')
        with tempfile.TemporaryDirectory() as d:
            script = Path(d) / "fake_worker.py"
            script.write_text(fake, encoding="utf-8")
            ref = Path(d) / "ref.wav"
            write_wav(ref, 500)
            proc = subprocess.Popen([sys.executable, str(script)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, creationflags=T.CREATE_NO_WINDOW)
            eng = T.IndexTTSEngine(stall_s=10)
            eng._attach(proc)
            old = T.ref_clip
            T.ref_clip = lambda cid, voice=None: ref
            try:
                self.assertTrue(eng.request({"op": "load"})["ok"])
                out = Path(d) / "a.wav"
                self.assertTrue(eng.synth("Hold the line.", out, "kaito", {}, "determined"))
                self.assertEqual(T.wav_duration_ms(out), 1000)
                out2 = Path(d) / "b.wav"     # a rejected vector retries in text mode
                self.assertTrue(eng.synth("reject vector", out2, "kaito", {}, "angry"))
            finally:
                T.ref_clip = old
                eng.close()
            self.assertFalse(eng.running())

    def test_stall_timeout(self):
        with tempfile.TemporaryDirectory() as d:
            script = Path(d) / "silent.py"
            script.write_text("import sys, time\nfor l in sys.stdin: time.sleep(30)\n", encoding="utf-8")
            proc = subprocess.Popen([sys.executable, str(script)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, creationflags=T.CREATE_NO_WINDOW)
            eng = T.IndexTTSEngine(stall_s=1.0)
            eng._attach(proc)
            try:
                t0 = time.time()
                r = eng.request({"op": "ping"})
                self.assertFalse(r["ok"])
                self.assertIn("silent", r["error"])
                self.assertLess(time.time() - t0, 5)
            finally:
                proc.kill()
                proc.wait(5)
                for s in (proc.stdin, proc.stdout, proc.stderr):
                    s.close()

    def test_start_refuses_under_gpu_guard(self):
        eng = T.IndexTTSEngine(popen=lambda *a, **k: self.fail("must not spawn"))
        old_find, old_guard = T.find_indextts, T.guard_processes_running
        T.find_indextts = lambda: {"ready": True, "blockers": [], "python": "p", "worker": "w",
                                   "checkpoints": "c", "root": "."}
        T.guard_processes_running = lambda names=None: ["RocketLeague.exe"]
        try:
            r = eng.start()
            self.assertFalse(r["ok"])
            self.assertIn("RocketLeague.exe", r["error"])
        finally:
            T.find_indextts, T.guard_processes_running = old_find, old_guard


if __name__ == "__main__":
    unittest.main()
