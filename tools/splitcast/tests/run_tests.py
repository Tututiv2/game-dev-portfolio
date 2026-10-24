"""SplitCast checks.

Two layers, and the second is the one that counts:

  offline  - pipeline.py is pure, so the whole command can be verified with no
             ffmpeg, no sound card, no stream.
  live     - ffmpeg actually listens on a local RTMP port, a second ffmpeg pushes
             a real encoded stream into it, and the two branches are written to
             disk and MEASURED. This proves the filter graph, the dual-output
             mapping and the listener before anything is pointed at Twitch.

The live layer exists because the offline layer cannot see the two things most
likely to be wrong: whether ffmpeg will let one input stream be both -c copy'd
and fed into a complex filter, and whether the mix actually preserves level.
"""

import os
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from splitcast import config, devices, pipeline, rtmpsplit, runner, slobs  # noqa: E402

# test_key_discovery drives runner._adopt, which PERSISTS what it learns. A test
# once wrote its fake key into the user's live config.json and wiped a real
# stream key doing exactly this (see landmine 13). config.save() resolves its
# path at call time precisely so this redirect works - it must happen before any
# test runs, not inside one.
_TMP_CFG = os.path.join(tempfile.mkdtemp(prefix="splitcast_run_cfg_"), "config.json")
config.PATH = _TMP_CFG

REAL_CFG = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "config.json")

PORT = 11935  # deliberately not 1935 - never fight a live stream for a port

# The live layer runs the SHIPPING filter graph, and that graph contains `azmq`
# with no bind_address - ffmpeg's option parser rejects every escape form for a
# URL with colons in it, so the default tcp://*:5555 is the only address that
# parses (see pipeline.filter_graph). It is therefore not a port the suite can
# move out of the way, and a running SplitCast owns it.
#
# Left alone, that surfaced as three FAILED measurement checks reading "Address
# in use" - which looks exactly like the filter graph being broken, on a suite
# whose whole job is to tell you the filter graph is fine. Say the real thing.
ZMQ_PORT = 5555
NO_WINDOW = 0x08000000

PASS = []
FAIL = []


def _console(text):
    """Windows consoles are cp1252 and raise on anything outside it. Named
    _console rather than _safe because runner._safe already means something else
    in this file - key redaction - and two different _safe's would be a trap."""
    enc = sys.stdout.encoding or "utf-8"
    try:
        return str(text).encode(enc, "replace").decode(enc, "replace")
    except Exception:
        return str(text).encode("ascii", "replace").decode("ascii")


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    mark = "  ok  " if cond else " FAIL "
    line = "[" + mark + "] " + name
    if detail:
        line += "   " + detail
    print(_console(line))
    return cond


def port_in_use(port):
    """Is something already listening on this local port?

    Connect rather than bind: binding would succeed on a free port and then have
    to be released, which is a race against the thing about to use it.
    """
    import socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(0.4)
    try:
        return sock.connect_ex(("127.0.0.1", int(port))) == 0
    finally:
        sock.close()


def run(argv, timeout=120):
    return subprocess.run(argv, capture_output=True, text=True, errors="replace",
                          timeout=timeout, creationflags=NO_WINDOW)


# ---------------------------------------------------------------- offline

def test_offline():
    print("\n--- offline: command construction ---")
    cfg = pipeline.default_config()
    cfg["destinations"]["twitch"]["key"] = "tw_secret_key"
    cfg["destinations"]["youtube"]["key"] = "yt_secret_key"
    cfg["music_dest"] = "twitch"

    argv = pipeline.build(cfg)
    joined = " ".join(argv)

    check("twitch url carries its key",
          "rtmp://live.twitch.tv/app/tw_secret_key" in joined)
    check("youtube url carries its key",
          "rtmp://a.rtmp.youtube.com/live2/yt_secret_key" in joined)
    check("listens rather than dials out", "-listen" in argv and "1" in argv)
    check("waits forever for streamlabs", "-timeout" in argv
          and argv[argv.index("-timeout") + 1] == "-1")

    # The bug this whole file exists to prevent.
    check("amix normalize is OFF", "normalize=0" in joined,
          "on would drop game+mic 6 dB")
    check("amix ends with the stream", "duration=first" in joined)
    check("no dropout swell", "dropout_transition=0" in joined)
    check("music resampled to 48k", "aresample=48000" in joined)
    check("front pair taken at unity", "pan=stereo|c0=c0|c1=c1" in joined,
          "sonar endpoints are multichannel")

    # Exactly one branch may re-encode audio.
    check("video never re-encoded",
          "libx264" not in joined and "nvenc" not in joined and "-c:v copy" in joined)
    check("exactly one aac encode", joined.count("-c:a aac") == 1)
    check("clean branch is pure copy", "-map 0:v -map 0:a -c copy" in joined)

    # Ordering: the music branch must map the FILTER output, never the raw input.
    tail = joined.split("-c:v copy", 1)[1]
    check("music branch maps the mix", "-map [mix]" in tail)

    print("\n--- offline: routing ---")
    cfg2 = pipeline.default_config()
    cfg2["destinations"]["twitch"]["key"] = "a"
    cfg2["destinations"]["youtube"]["key"] = "b"
    cfg2["music_dest"] = "youtube"
    j2 = " ".join(pipeline.build(cfg2))
    # The music branch is the one whose URL follows the aac encoder. Anything
    # before it is a straight copy.
    after_aac = j2.split("-c:a aac", 1)[1]
    check("music_dest=youtube puts music on youtube only",
          "a.rtmp.youtube.com" in after_aac and "live.twitch.tv" not in after_aac)
    check("describe() agrees with the argv",
          any(line.startswith("youtube") and "music ON" in line
              for line in pipeline.describe(cfg2))
          and any(line.startswith("twitch") and "music OFF" in line
                  for line in pipeline.describe(cfg2)))

    cfg3 = pipeline.default_config()
    cfg3["destinations"]["twitch"]["key"] = "a"
    cfg3["destinations"]["youtube"]["enabled"] = False
    cfg3["music_dest"] = "youtube"
    j3 = " ".join(pipeline.build(cfg3))
    check("music platform disabled -> device never opened",
          "dshow" not in j3 and "amix" not in j3,
          "no point holding the sound card")

    cfg4 = pipeline.default_config()
    cfg4["destinations"]["twitch"]["key"] = "a"
    cfg4["destinations"]["youtube"]["key"] = "b"
    j4 = " ".join(pipeline.build(cfg4, music=False))
    check("fallback mode is pure passthrough",
          "amix" not in j4 and "dshow" not in j4 and j4.count("-c copy") == 2,
          "a dead sound card must not cost the stream")

    print("")
    print("--- offline: keyless destinations ---")
    cfg6 = pipeline.default_config()
    cfg6["destinations"]["twitch"]["key"] = "tw"
    cfg6["destinations"]["youtube"]["key"] = ""          # learned from the stream
    j6 = " ".join(pipeline.build(cfg6))
    # ONE failing output kills the whole ffmpeg process, so publishing to a
    # keyless URL would take Twitch down with it.
    check("keyless destination is not published",
          "a.rtmp.youtube.com" not in j6,
          "a keyless output would fail and kill the relay, twitch included")
    check("the destination with a key still publishes", "live.twitch.tv/app/tw" in j6)
    check("it is reported as waiting, not missing",
          "youtube" in " ".join(pipeline.waiting_for_key(cfg6)))
    check("describe() says it is waiting",
          any("waiting" in l for l in pipeline.describe(cfg6)))

    cfg6["destinations"]["youtube"]["key"] = "yt-learned"
    check("once learned, it publishes",
          "a.rtmp.youtube.com/live2/yt-learned" in " ".join(pipeline.build(cfg6)))

    cfg7 = pipeline.default_config()
    cfg7["destinations"]["twitch"]["key"] = ""
    cfg7["destinations"]["youtube"]["key"] = ""
    try:
        pipeline.build(cfg7)
        check("refuses when nothing is publishable", False)
    except ValueError:
        check("refuses when nothing is publishable", True)

    print("\n--- offline: safety ---")
    cfg5 = pipeline.default_config()
    cfg5["destinations"]["twitch"]["key"] = "live_1234_SUPERSECRET"
    cfg5["destinations"]["youtube"]["key"] = "yt-abcd-efgh"
    safe = " ".join(runner._safe(pipeline.build(cfg5), cfg5))
    check("keys never reach the log",
          "SUPERSECRET" not in safe and "yt-abcd-efgh" not in safe)
    red = config.redact(cfg5)
    check("keys redacted in saved/printed config",
          "SUPERSECRET" not in str(red) and red["destinations"]["twitch"]["key"].endswith("CRET"))

    try:
        pipeline.build({**pipeline.default_config(),
                        "destinations": {"twitch": {"enabled": False},
                                         "youtube": {"enabled": False}}})
        check("refuses to run with no destination", False)
    except ValueError:
        check("refuses to run with no destination", True)

    print("\n--- offline: device parsing ---")
    dump = (
        '[dshow @ 0000] "MX Brio" (video)\n'
        '[dshow @ 0000]   Alternative name "@device_pnp_x"\n'
        '[dshow @ 0000] "SteelSeries Sonar - Stream" (audio)\n'
        '[dshow @ 0000] "LSVCam" (none)\n'
        '[dshow @ 0000] "Microphone (Realtek(R) Audio)" (audio)\n'
    )
    names = devices.parse_devices(dump)
    check("parses only audio devices",
          names == ["SteelSeries Sonar - Stream", "Microphone (Realtek(R) Audio)"],
          str(names))
    check("device names with brackets survive",
          "Microphone (Realtek(R) Audio)" in names)
    check("music bus ranks first",
          devices.rank(["Zed", "SteelSeries Sonar - Stream"])[0] == devices.SONAR_STREAM)

    print("\n--- offline: failure classification ---")
    check("dshow device error is caught",
          runner.looks_like_device_failure(
              ["[dshow @ 0] Could not find audio only device with name"]))
    check("io error is caught",
          runner.looks_like_device_failure(["[dshow @ 0] real-time buffer, I/O error"]))
    check("a normal disconnect is NOT blamed on the device",
          not runner.looks_like_device_failure(
              ["[flv @ 0] Failed to update header with correct duration.",
               "Error writing trailer: End of file"]),
          "otherwise every disconnect silently kills the music")


# ------------------------------------------------------------------- live

def mean_volume(ffmpeg, path):
    proc = run([ffmpeg, "-hide_banner", "-nostdin", "-i", path,
                "-af", "volumedetect", "-f", "null", "-"])
    for line in (proc.stderr or "").splitlines():
        if "mean_volume:" in line:
            return float(line.split("mean_volume:")[1].strip().split()[0])
    return None


def has_streams(ffmpeg, path):
    proc = run([ffmpeg, "-hide_banner", "-i", path, "-f", "null", "-"])
    err = proc.stderr or ""
    return ("Video:" in err), ("Audio:" in err)


def test_live(ffmpeg):
    print("\n--- live: real ffmpeg, real RTMP, measured output ---")
    tmp = tempfile.mkdtemp(prefix="splitcast_")
    src = os.path.join(tmp, "src.flv")
    music = os.path.join(tmp, "music.wav")
    out_clean = os.path.join(tmp, "clean.flv")
    out_music = os.path.join(tmp, "music.flv")

    # A stand-in for Streamlabs: 4 s of video plus a 300 Hz tone.
    r = run([ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
             "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30",
             "-f", "lavfi", "-i", "sine=frequency=300:sample_rate=48000",
             "-t", "4", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
             "-c:a", "aac", "-ar", "48000", "-ac", "2", "-f", "flv", src])
    if not check("built a stand-in stream", os.path.exists(src), r.stderr.strip()[:160]):
        return

    # A stand-in for the Sonar music bus: a 1000 Hz tone at the same level.
    run([ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
         "-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=48000",
         "-t", "6", "-ac", "2", music])
    check("built a stand-in music bus", os.path.exists(music))

    cfg = pipeline.default_config()
    cfg["music_volume"] = 1.0
    cfg["front_pair_only"] = False  # the stand-in is already stereo

    # The real graph and the real output args - only the INPUTS are swapped for
    # files, which is exactly why those are separate functions in pipeline.py.
    listen_url = "rtmp://127.0.0.1:{}/live/stream".format(PORT)
    argv = [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-nostdin",
            "-listen", "1", "-timeout", "20", "-f", "flv", "-i", listen_url,
            "-stream_loop", "-1", "-i", music,
            "-filter_complex", pipeline.filter_graph(cfg)]
    argv += pipeline.clean_output(out_clean)
    argv += pipeline.music_output(cfg, out_music)

    listener = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, errors="replace", creationflags=NO_WINDOW)
    time.sleep(2.5)  # let it bind and start listening

    pusher = run([ffmpeg, "-hide_banner", "-loglevel", "error", "-re",
                  "-i", src, "-c", "copy", "-f", "flv", listen_url], timeout=90)
    check("streamlabs stand-in connected and pushed", pusher.returncode == 0,
          (pusher.stderr or "").strip()[:160])

    try:
        listener.wait(timeout=45)
    except subprocess.TimeoutExpired:
        listener.kill()
    lerr = (listener.stderr.read() or "").strip()

    ok_clean = os.path.exists(out_clean) and os.path.getsize(out_clean) > 5000
    ok_music = os.path.exists(out_music) and os.path.getsize(out_music) > 5000
    check("clean branch produced output", ok_clean, lerr[:200])
    check("music branch produced output", ok_music, lerr[:200])
    if not (ok_clean and ok_music):
        return

    cv, ca = has_streams(ffmpeg, out_clean)
    mv, ma = has_streams(ffmpeg, out_music)
    check("clean branch has video and audio", cv and ca)
    check("music branch has video and audio", mv and ma)

    # This is the check that could not be made offline: ffmpeg must allow one
    # input stream to be -c copy'd AND fed into a complex filter at once.
    check("one input feeds both a copy and a filter", ok_clean and ok_music,
          "the whole design depends on this")

    v_src = mean_volume(ffmpeg, src)
    v_clean = mean_volume(ffmpeg, out_clean)
    v_music = mean_volume(ffmpeg, out_music)
    if None in (v_src, v_clean, v_music):
        check("could measure levels", False)
    else:
        # Compared against the SOURCE, never against a number invented here. An
        # absolute threshold would only ever be testing the guess that wrote it.
        check("clean branch level identical to source", abs(v_clean - v_src) < 0.5,
              "src {:.1f} dB  clean {:.1f} dB".format(v_src, v_clean))
        check("music branch is louder, not quieter", v_music > v_clean + 1.0,
              "clean {:.1f} dB  music {:.1f} dB".format(v_clean, v_music))
        # normalize=1 would have SUBTRACTED ~3 dB here instead of adding it.
        check("mix gained level rather than losing it",
              1.0 < (v_music - v_clean) < 6.0,
              "{:+.1f} dB".format(v_music - v_clean))

    # And the clean branch must carry NO music. Band-pass 1000 Hz and compare.
    def tone_level(path, freq):
        proc = run([ffmpeg, "-hide_banner", "-nostdin", "-i", path,
                    "-af", "bandpass=f={}:width_type=h:w=60,volumedetect".format(freq),
                    "-f", "null", "-"])
        for line in (proc.stderr or "").splitlines():
            if "mean_volume:" in line:
                return float(line.split("mean_volume:")[1].strip().split()[0])
        return None

    t_clean = tone_level(out_clean, 1000)
    t_music = tone_level(out_music, 1000)
    if t_clean is not None and t_music is not None:
        check("the music tone is ABSENT from the clean branch",
              t_music > t_clean + 15.0,
              "1 kHz: clean {:.1f} dB  music {:.1f} dB".format(t_clean, t_music))
    else:
        check("could isolate the music tone", False)


def test_key_discovery():
    """Reading the horizontal key out of Streamlabs' config before it connects.

    The bug being guarded: learning the key from the wire means relaunching
    ffmpeg mid-connection, and Streamlabs answers a horizontal deactivate by
    killing the VERTICAL output permanently. Learning it from disk moves the
    relaunch to before anything is connected, where it costs nothing.

    Every case below is written from the real files this machine produced on
    2026-08-28, including the two decoys that must never be adopted.
    """
    print("\n--- offline: streamlabs key discovery ---")

    HORIZONTAL = ('{"type":"rtmp_common","settings":{"streamType":"rtmp_common",'
                  '"service":"YouTube - RTMPS","server":"rtmp://127.0.0.1:1935/live2",'
                  '"key":"03h9-2kfh-hu68-avjr-fpux","protocol":"RTMP"}}')
    VERTICAL = ('{"type":"rtmp_custom","settings":{"streamType":"rtmp_custom",'
                '"server":"rtmp://a.rtmp.youtube.com/live2",'
                '"key":"c2bj-gy5h-5yxb-9xwb-5a6c","use_auth":false}}')
    STALE = ('{"type":"rtmp_custom","settings":{"streamType":"rtmp_custom",'
             '"server":"rtmp://127.0.0.1:1935/live","key":"stream",'
             '"use_auth":false}}')

    h = slobs.parse_service(HORIZONTAL)
    check("a service file parses to server+key",
          h and h["key"] == "03h9-2kfh-hu68-avjr-fpux"
          and h["server"] == "rtmp://127.0.0.1:1935/live2")
    check("garbage parses to None, never an exception",
          slobs.parse_service("not json at all") is None
          and slobs.parse_service("") is None
          and slobs.parse_service('{"no":"settings"}') is None)

    check("our own listen address is recognised",
          slobs.points_at("rtmp://127.0.0.1:1935/live2", "127.0.0.1", 1935))
    check("a bare rtmp:// host defaults to port 1935",
          slobs.points_at("rtmp://127.0.0.1/live2", "127.0.0.1", 1935))
    check("a DIFFERENT port is not our address",
          not slobs.points_at("rtmp://127.0.0.1:1936/live2", "127.0.0.1", 1935))
    check("real youtube is not our address - the vertical can never be adopted",
          not slobs.points_at("rtmp://a.rtmp.youtube.com/live2", "127.0.0.1", 1935))
    check("a malformed port does not raise",
          not slobs.points_at("rtmp://127.0.0.1:nonsense/live2", "127.0.0.1", 1935))

    check("a platform destination is identified as YouTube",
          slobs.is_youtube(slobs.parse_service(HORIZONTAL)))
    check("an rtmp_custom file with no service name is not",
          not slobs.is_youtube(slobs.parse_service(STALE)))

    # -- the whole thing, against a directory laid out like the real one ---
    tmp = tempfile.mkdtemp(prefix="splitcast-slobs-")

    def put(name, text, age=0.0):
        path = os.path.join(tmp, name)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)
        if age:
            old = time.time() - age
            os.utime(path, (old, old))
        return path

    check("an empty directory yields no key, rather than failing",
          slobs.read_key("127.0.0.1", 1935, tmp) is None)

    put("service1.json", VERTICAL)
    check("THE VERTICAL'S KEY IS NEVER ADOPTED",
          slobs.read_key("127.0.0.1", 1935, tmp) is None,
          "it names a.rtmp.youtube.com, so it is not ours to publish")

    put("service.json", STALE, age=600)
    check("the placeholder key from a dead experiment is not adopted",
          slobs.read_key("127.0.0.1", 1935, tmp) is None,
          "points at us, but is not a YouTube platform destination")

    put("service.json.tmp", HORIZONTAL)
    check("the horizontal key IS adopted, from the .tmp streamlabs leaves behind",
          slobs.read_key("127.0.0.1", 1935, tmp) == "03h9-2kfh-hu68-avjr-fpux")

    put("service.json", HORIZONTAL.replace("03h9-2kfh-hu68-avjr-fpux", "newer-key"))
    check("when both names exist the NEWEST wins",
          slobs.read_key("127.0.0.1", 1935, tmp) == "newer-key")

    put("service.json.bak", HORIZONTAL.replace("03h9-2kfh-hu68-avjr-fpux", "backup-key"))
    put("service.json.splitcast-backup",
        HORIZONTAL.replace("03h9-2kfh-hu68-avjr-fpux", "older-backup-key"))
    check("backup files are ignored however new they are",
          slobs.read_key("127.0.0.1", 1935, tmp) == "newer-key",
          "a .bak is stale by definition")

    # -- the relaunch guard ------------------------------------------------
    cfg = pipeline.default_config()
    cfg["destinations"]["youtube"]["enabled"] = True
    cfg["destinations"]["youtube"]["key"] = "old-key"
    relay = runner.Relay(cfg)

    before = open(REAL_CFG, "rb").read() if os.path.exists(REAL_CFG) else None
    check("tests do not point at the real config",
          os.path.abspath(config.PATH) != os.path.abspath(REAL_CFG),
          "_adopt persists, and a test once wiped a live stream key")

    check("adopting a new key reports that it changed",
          relay._adopt("brand-new-key") is True
          and cfg["destinations"]["youtube"]["key"] == "brand-new-key")
    check("adopting the SAME key changes nothing - so nothing relaunches",
          relay._adopt("brand-new-key") is False,
          "this is what makes the pre-connection read pay off")
    check("an empty key is never adopted", relay._adopt("") is False)

    cfg["auto_youtube_key"] = False
    check("auto_youtube_key=false is honoured", relay._adopt("some-other-key") is False)

    class _Splitter:
        def __init__(self, n):
            self.n = n

        def live_connections(self):
            return self.n

    relay._splitter = _Splitter(1)
    check("the splitter reports a live connection, which blocks a relaunch",
          relay._splitter.live_connections() == 1)
    check("Splitter exposes live_connections for the runner to ask",
          hasattr(rtmpsplit.Splitter, "live_connections"))

    src = open(os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "splitcast", "runner.py"), encoding="utf-8").read()
    watch = src.split("def _watch_key")[1].split("def _on_key")[0]
    check("_watch_key refuses to relaunch while a connection is live",
          "live_connections()" in watch and "continue" in watch,
          "killing ffmpeg mid-connection is the bug, not the fix")
    check("_watch_key only acts while idle",
          '"listening"' in watch and "self.state" in watch)

    # -- a relaunch we asked for is not a fault ----------------------------
    # Terminating ffmpeg makes it write "I/O error" on the way out, and that
    # string is in DEVICE_ERRORS - so without this, adopting a key inside the
    # fast-fail window would be read as the music bus dying and would silently
    # drop the music for the rest of the session.
    check("an ffmpeg teardown message WOULD otherwise be blamed on the device",
          runner.looks_like_device_failure(["[in#1 @ 0000] Error: I/O error"]),
          "which is why the deliberate flag has to exist")

    relay2 = runner.Relay(pipeline.default_config())
    check("a relay starts with no deliberate kill pending",
          not relay2._deliberate.is_set())
    relay2._kill(intentional=True)
    check("an intentional kill raises the flag", relay2._deliberate.is_set())
    relay2._deliberate.clear()
    relay2._kill()
    check("an ordinary kill does NOT - real faults still classify",
          not relay2._deliberate.is_set())

    sup = src.split("def _supervise")[1].split("def _run_once")[0]
    check("_supervise skips fault classification after a deliberate kill",
          "_deliberate.is_set()" in sup
          and sup.index("_deliberate.is_set()") < sup.index("looks_like_zmq_failure"),
          "and before both heuristics, or it would not help")

    after = open(REAL_CFG, "rb").read() if os.path.exists(REAL_CFG) else None
    check("the real config.json is untouched by all of the above",
          before == after,
          "the user's live stream key survives a full run")


def main():
    cfg = config.load()
    ffmpeg = cfg["ffmpeg"]
    print("SplitCast checks")
    print("ffmpeg:", ffmpeg)

    test_offline()
    test_key_discovery()

    if not os.path.exists(ffmpeg):
        print("\n!! ffmpeg not found - skipping the live layer")
        FAIL.append("ffmpeg present")
    elif "--offline" in sys.argv:
        print("\n(skipping live layer: --offline)")
    elif port_in_use(ZMQ_PORT):
        print("\n!! SplitCast is already running - it owns zmq port {},"
              " which the".format(ZMQ_PORT))
        print("   shipping filter graph must bind. CLOSE SPLITCAST and re-run"
              " to measure")
        print("   the live layer. Skipped rather than failed: nothing here is"
              " broken.")
    else:
        test_live(ffmpeg)

    print("\n" + "=" * 58)
    print("{} passed, {} failed".format(len(PASS), len(FAIL)))
    if FAIL:
        for name in FAIL:
            print("   FAILED:", name)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
