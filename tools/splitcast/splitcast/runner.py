"""Supervises the ffmpeg relay.

The one rule this module exists to enforce: a problem with the MUSIC must never
become a problem with the STREAM. The music is a nice-to-have bolted onto the
side; the stream is the thing thousands of people are watching. So every failure
path here ends in "keep both platforms up", and losing the music is an acceptable
outcome where dropping the relay is not.

States:
    stopped    -> nothing running
    listening  -> ffmpeg is up and holding port 1935, Streamlabs has not connected
    live       -> frames are moving to both destinations
    retrying   -> ffmpeg exited, waiting to relaunch
"""

import collections
import socket
import subprocess
import threading
import time

from . import pipeline, rtmpsplit, slobs, zmqctl

# How often to ask Streamlabs' own config what key it is about to publish with.
# It lands on disk seconds before the socket opens, so this is not a race worth
# tightening - it is a poll that only has to beat a human clicking Go Live.
KEY_POLL_SECONDS = 0.5

# How long ffmpeg has to survive before we stop blaming the music device for its
# death. A bad device name fails in well under a second; anything that ran for
# longer than this died of something else and must not silently drop the music.
FAST_FAIL_SECONDS = 8.0

RESTART_DELAY = 2.0

_NO_WINDOW = 0x08000000

# Substrings that mean "the music device is the problem". Deliberately plain
# substring tests rather than regexes - a pattern that silently cannot match
# looks exactly like a device that is working fine.
# A zmq bind failure must cost the SLIDER, never the stream.
ZMQ_ERRORS = ("azmq", "zmq_", "address already in use", "bind failed")

DEVICE_ERRORS = (
    "could not find audio only device",
    "could not find video only device",
    "could not set audio device",
    "could not open audio device",
    "i/o error",
    "device not found",
    "cannot open device",
)


def looks_like_zmq_failure(lines):
    return any(sig in ln.lower() for ln in lines for sig in ZMQ_ERRORS)


def looks_like_device_failure(lines):
    for line in lines:
        low = line.lower()
        if any(sig in low for sig in DEVICE_ERRORS):
            return True
        if "dshow" in low and ("error" in low or "fail" in low):
            return True
    return False


class Relay:
    def __init__(self, cfg, on_event=None):
        self.cfg = cfg
        self.on_event = on_event or (lambda *_: None)
        self.log = collections.deque(maxlen=400)
        self.state = "stopped"
        self.music_on = True
        self.stats = {}
        self._proc = None
        self._thread = None
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._splitter = None
        self._relay_lock = threading.Lock()
        self._watch = None
        self._deliberate = threading.Event()

    # -- plumbing ---------------------------------------------------------
    def _emit(self, kind, text=""):
        if kind == "log" and text:
            self.log.append(text)
        try:
            self.on_event(kind, text)
        except Exception:
            pass

    def _set_state(self, state):
        if state != self.state:
            self.state = state
            self._emit("state", state)

    def _start_splitter(self):
        """Front the relay with the fan-in proxy so Dual Output's SECOND stream
        has somewhere to go. Failure here is not fatal: without it ffmpeg simply
        binds the public port itself and a single stream still works."""
        if self._splitter:
            return
        routes = [(self.cfg["listen_host"], int(self.cfg.get("relay_port", 1936))),
                  (self.cfg.get("passthrough_host", "a.rtmp.youtube.com"),
                   int(self.cfg.get("passthrough_port", 1935)))]
        sp = rtmpsplit.Splitter(routes, host=self.cfg["listen_host"],
                                port=int(self.cfg["listen_port"]),
                                on_event=self._emit,
                                swap=bool(self.cfg.get("split_swap", False)),
                                on_key=self._on_key)
        try:
            sp.start()
            self._splitter = sp
        except OSError as exc:
            self._emit("log", "!! splitter could not bind {}: {} - "
                              "vertical passthrough disabled"
                       .format(self.cfg["listen_port"], exc))

    def _adopt(self, key):
        """Store a newly discovered YouTube key. True if anything changed.

        Does NOT relaunch - the caller decides that, because the cost of a
        relaunch depends entirely on whether anything is connected yet.
        """
        if not key or not self.cfg.get("auto_youtube_key", True):
            return False
        yt = self.cfg.get("destinations", {}).get("youtube") or {}
        if not yt.get("enabled") or key == yt.get("key"):
            return False
        yt["key"] = key
        try:
            from . import config as _config
            _config.save(self.cfg)
        except Exception:
            pass
        self._emit("log", "youtube key changed -> " + rtmpsplit.redact(key))
        return True

    def _key_from_streamlabs(self):
        """The key Streamlabs is about to publish to us with, read from its own
        config - or None. Never raises: this reads a file another program owns,
        and a problem here must not cost the stream."""
        try:
            return slobs.read_key(self.cfg["listen_host"],
                                  int(self.cfg["listen_port"]))
        except Exception as exc:                      # noqa: BLE001
            self._emit("log", "!! could not read streamlabs config: " + str(exc))
            return None

    def _watch_key(self):
        """Adopt the key BEFORE Streamlabs connects.

        Streamlabs writes each display's ingest settings to disk seconds ahead
        of opening the socket, so the key is knowable while the relay is still
        idle - and a relaunch with nothing connected is free. Learning it from
        the wire instead (see _on_key) means relaunching mid-connection, which
        Streamlabs answers by tearing down the VERTICAL output for good.

        Two conditions, and both matter. ffmpeg must be up but not yet carrying
        frames, and the splitter must be holding zero connections - because
        there is a window where Streamlabs has connected and no progress block
        has arrived yet, and killing ffmpeg in that window is precisely the bug.
        """
        while not self._stop.is_set():
            for _ in range(int(KEY_POLL_SECONDS * 10)):
                if self._stop.is_set():
                    return
                time.sleep(0.1)
            if self.state not in ("listening", "retrying"):
                continue
            if self._splitter and self._splitter.live_connections():
                continue
            key = self._key_from_streamlabs()
            if key and self._adopt(key):
                self._emit("log", "picked up from streamlabs before go-live - "
                                  "relaunching now, while nothing is connected")
                self._kill(intentional=True)

    def _on_key(self, key, label):
        """The splitter saw a publish key on the wire.

        This is the FALLBACK. _watch_key should have adopted the key from
        Streamlabs' config already, in which case this finds it unchanged and
        does nothing. It still exists because the file route depends on where
        Streamlabs keeps its config, and the wire cannot lie about what is
        being published.

        When it does fire, it relaunches mid-connection and the vertical output
        is lost for the session - so say that plainly rather than calling it a
        blip, and point at the reason.

        Only the RELAY stream teaches us anything - the passthrough carries the
        vertical, whose key belongs to a different rendition and would overwrite
        the horizontal's if it were allowed to.
        """
        if label != "relay" or not key:
            return
        if not self._adopt(key):
            return
        self._emit("log", "!! key learned from the wire, not from streamlabs' "
                          "config - relaunching mid-connection")
        self._emit("log", "!! the VERTICAL output will not survive this; "
                          "stop and restart the stream to get it back")
        self._kill(intentional=True)

    def set_volume(self, value):
        """Change the music level on the RUNNING relay.

        Restarting to apply a volume change would black out both
        platforms for ~2 s, which is a far worse price than a slider is
        worth - hence the zmq path. Returns (ok, detail).
        """
        self.cfg["music_volume"] = float(value)
        # "listening" is NOT live enough. ffmpeg -listen builds its filter graph
        # only once something connects, so azmq does not exist - and has not
        # bound its port - until Streamlabs is actually sending. Attempting the
        # command before then just times out and reports it as a fault.
        if self.state == "listening":
            return False, "applies when you go live"
        if self.state != "live":
            return False, "relay not running"
        if not self.music_on:
            return False, "music is off"
        if not self.cfg.get("live_volume", True):
            return False, "live volume disabled - applies on restart"
        return zmqctl.set_volume(value, port=int(self.cfg.get("zmq_port", 5555)))

    # -- control ----------------------------------------------------------
    def start(self):
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._start_splitter()
        self.music_on = True
        # Cheapest possible moment to get the key right: ffmpeg has not been
        # launched yet, so adopting costs not even a relaunch.
        if self._adopt(self._key_from_streamlabs()):
            self._emit("log", "using the key streamlabs is configured with")
        self._thread = threading.Thread(target=self._supervise, daemon=True)
        self._thread.start()
        self._watch = threading.Thread(target=self._watch_key, daemon=True)
        self._watch.start()

    def stop(self):
        self._stop.set()
        if self._splitter:
            self._splitter.stop()
            self._splitter = None
        self._kill()
        if self._thread:
            self._thread.join(timeout=6)
        if self._watch:
            self._watch.join(timeout=3)
            self._watch = None
        self._set_state("stopped")

    def _kill(self, intentional=False):
        """Stop ffmpeg. `intentional` marks a relaunch WE asked for.

        It matters because terminating ffmpeg makes it write things like "I/O
        error" on its way out, and "i/o error" is in DEVICE_ERRORS - so a
        deliberate relaunch inside the fast-fail window reads as the music bus
        having failed, and the supervisor answers that by dropping the music for
        the rest of the session. Adopting a key would silently cost the music.
        """
        if intentional:
            self._deliberate.set()
        with self._lock:
            proc = self._proc
            self._proc = None
        if not proc:
            return
        try:
            proc.terminate()
            try:
                proc.wait(timeout=4)
            except subprocess.TimeoutExpired:
                proc.kill()
        except Exception:
            pass

    # -- the loop ---------------------------------------------------------
    def _supervise(self):
        while not self._stop.is_set():
            argv = pipeline.build(self.cfg, music=self.music_on)
            self._emit("log", "$ " + " ".join(
                a if " " not in a else '"' + a + '"' for a in _safe(argv, self.cfg)))
            started = time.time()
            recent = self._run_once(argv)
            if self._stop.is_set():
                break
            ran_for = time.time() - started

            # We asked for this exit, so none of the fault heuristics below
            # apply to it - and there is nothing to back off from either, so
            # relaunch straight away and keep the gap as short as possible.
            if self._deliberate.is_set():
                self._deliberate.clear()
                continue

            if self.cfg.get("live_volume", True) and ran_for < FAST_FAIL_SECONDS:
                if looks_like_zmq_failure(recent):
                    # Port taken, or the firewall said no. Drop the live
                    # slider and keep the music - losing a convenience
                    # beats losing audio.
                    self.cfg["live_volume"] = False
                    self._emit("log", "!! live volume unavailable (zmq) - "
                                      "music still mixing, volume needs a restart")
                    continue

            if self.music_on and ran_for < FAST_FAIL_SECONDS \
                    and looks_like_device_failure(recent):
                # The music bus could not be opened. Drop it and relaunch as pure
                # passthrough rather than sit here failing - the stream matters
                # more than the soundtrack.
                self.music_on = False
                self._emit("log", "!! music device unavailable - "
                                  "relaunching WITHOUT music so the stream stays up")
                self._emit("music", "off")
                continue

            self._set_state("retrying")
            for _ in range(int(RESTART_DELAY * 10)):
                if self._stop.is_set():
                    return
                time.sleep(0.1)
        self._set_state("stopped")

    def _run_once(self, argv):
        """Run ffmpeg to completion. Returns the tail of its stderr."""
        try:
            proc = subprocess.Popen(
                argv,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                stdin=subprocess.DEVNULL,
                text=True,
                errors="replace",
                bufsize=1,
                creationflags=_NO_WINDOW,
            )
        except OSError as exc:
            self._emit("log", "!! cannot launch ffmpeg: {}".format(exc))
            self._set_state("retrying")
            time.sleep(RESTART_DELAY)
            return []

        with self._lock:
            self._proc = proc
        self._set_state("listening")

        recent = collections.deque(maxlen=40)
        err_thread = threading.Thread(
            target=self._pump_stderr, args=(proc, recent), daemon=True)
        err_thread.start()
        self._pump_progress(proc)
        proc.wait()
        err_thread.join(timeout=2)
        with self._lock:
            self._proc = None
        return list(recent)

    def _pump_stderr(self, proc, recent):
        for raw in iter(proc.stderr.readline, ""):
            line = raw.rstrip()
            if not line:
                continue
            recent.append(line)
            self._emit("log", line)

    def _pump_progress(self, proc):
        """Read ffmpeg's -progress blocks off stdout.

        The first block arriving is the signal that Streamlabs has connected and
        frames are actually moving - which is a different thing from ffmpeg being
        up, and the distinction is the whole reason to read this at all.
        """
        for raw in iter(proc.stdout.readline, ""):
            line = raw.strip()
            if "=" not in line:
                continue
            key, _, value = line.partition("=")
            self.stats[key] = value
            if key == "progress":
                if self.state == "listening":
                    self._set_state("live")
                self._emit("stats", "")


def _safe(argv, cfg):
    """argv with stream keys blanked, for logging."""
    keys = [d.get("key", "") for d in cfg.get("destinations", {}).values()]
    keys = [k for k in keys if k]
    out = []
    for a in argv:
        for k in keys:
            if k in a:
                a = a.replace(k, "<key>")
        out.append(a)
    return out
