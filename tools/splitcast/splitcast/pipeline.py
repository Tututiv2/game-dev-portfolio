"""SplitCast - ffmpeg command construction.

PURE MODULE. No subprocess, no tkinter, no device enumeration, no filesystem, no
network. Every function here is a function of the config dict and nothing else,
which is what lets tests/run_tests.py verify the whole command - and actually
execute the filter graph against synthetic inputs - with no stream running, no
sound card and nothing live.

The shape of the job:

    Streamlabs --(one encode)--> 127.0.0.1:1935 --+--> clean branch  -c copy      -> YouTube
                                                  |
                                                  +--> music branch  video copied,
                                                       audio re-mixed with the
                                                       music bus            -> Twitch

Video is COPIED on both branches. It is never re-encoded, so the second
destination costs no GPU at all - only the music branch's AAC re-encode, which is
about 1% of a core. The cost that is real is upload: two full streams leave the
machine, same as any multistream.
"""

# Default ingest endpoints. Both accept the key appended directly.
INGEST = {
    "twitch": "rtmp://live.twitch.tv/app/",
    "youtube": "rtmp://a.rtmp.youtube.com/live2/",
}

DEST_ORDER = ("twitch", "youtube")


def default_config():
    return {
        "ffmpeg": r"C:\Users\you\Documents\FFmpeg\bin\ffmpeg.exe",
        "listen_host": "127.0.0.1",
        "listen_port": 1935,        # splitter - what Streamlabs connects to
        "relay_port": 1936,         # ffmpeg, behind the splitter
        # Second inbound stream (Dual Output vertical) is passed straight
        # through to the real platform, untouched and unmixed.
        "passthrough_host": "a.rtmp.youtube.com",
        "passthrough_port": 1935,
        "split_swap": False,        # flip if vertical connects first
        # Read the YouTube key out of the incoming stream instead of using a
        # pasted one. YouTube mints a new broadcast key at every Go Live, so a
        # configured key is stale within a day and publishes to a dead stream.
        "auto_youtube_key": True,

        "app": "live",
        "key": "stream",
        # Which platform hears the music. The other gets the untouched mix.
        "music_dest": "twitch",
        # The FULL dshow name, including the parenthesised driver. ffmpeg matches
        # this literally - the short "SteelSeries Sonar - Stream" enumerates fine
        # in Windows but is NOT what dshow will open.
        "music_device": "SteelSeries Sonar - Stream (SteelSeries Sonar Virtual Audio Device)",
        "music_volume": 0.60,
        # Live volume over ZMQ. Off falls back to "applies on restart".
        "live_volume": True,
        "zmq_port": 5555,
        # Sonar's virtual endpoints report as multichannel. A flat downmix drops
        # the front pair by ~12 dB (see AudioCapture/CLAUDE.md - same card, same
        # lesson), so take channels 0 and 1 at unity instead.
        "front_pair_only": True,
        "audio_bitrate": "160k",
        # Now-playing overlay (see overlay.py). 5570 continues the port numbering
        # the rest of the portfolio uses.
        "overlay_port": 5570,
        "overlay_tagline": "Music on Twitch!",
        "overlay_accent": "#9146FF",
        "overlay_process": "Spotify.exe",
        # Combined chat overlay (chat.py + chat_page.py).
        "chat_twitch_channel": "tutidapro",
        "chat_youtube_handle": "Tutidayoutube",
        "chat_max_messages": 22,
        # Seconds a chat line stays on screen. 0 disables expiry.
        "chat_message_ttl": 20,
        # The second-monitor chat window (chatwindow.py). Its own scale, since
        # a 520px-wide window needs different sizing to a 4K overlay.
        "chat_window_scale": 1.5,
        # Twitch write access. The token is OAuth, scoped to chat + moderation
        # only, and revocable at twitch.tv/settings/connections. Never a password.
        "twitch_client_id": "",
        "twitch_token": "",
        "default_timeout_seconds": 600,
        # YouTube write access. Google dropped the implicit grant, so this is the
        # code flow with PKCE. The "secret" is not confidential for installed
        # apps - Google documents that - and is treated accordingly.
        "youtube_client_id": "",
        "youtube_client_secret": "",
        "youtube_access": "",
        "youtube_refresh": "",
        "youtube_expires_at": 0,
        "chat_window_width": 520,
        "chat_window_height": 900,
        "chat_font_size": 19,
        # Multiplies the whole overlay layout. 1.0 was too small to read on
        # a stream; try ?scale=N on the preview URL to retune live.
        "overlay_scale": 1.7,
        "destinations": {
            "twitch": {"enabled": True, "url": INGEST["twitch"], "key": ""},
            "youtube": {"enabled": True, "url": INGEST["youtube"], "key": ""},
        },
    }


def local_url(cfg):
    """The address FFMPEG listens on - behind the splitter, not the port
    Streamlabs dials. See rtmpsplit.py: ffmpeg can only accept one connection,
    so the splitter owns the public port and hands ffmpeg the first stream."""
    return "rtmp://{}:{}/{}/{}".format(
        cfg["listen_host"], cfg.get("relay_port", 1936), cfg["app"], cfg["key"]
    )


def inbound_url(cfg):
    """The address STREAMLABS points at - the splitter's port."""
    return "rtmp://{}:{}/{}/{}".format(
        cfg["listen_host"], cfg["listen_port"], cfg["app"], cfg["key"]
    )


def streamlabs_server(cfg):
    """What to paste into Streamlabs' 'Server' box (it wants the key separate)."""
    return "rtmp://{}:{}/{}".format(cfg["listen_host"], cfg["listen_port"], cfg["app"])


def dest_url(cfg, dest):
    d = cfg["destinations"][dest]
    base = d.get("url") or INGEST[dest]
    if not base.endswith("/"):
        base += "/"
    return base + d.get("key", "").strip()


def enabled_dests(cfg):
    """Enabled AND has a key. A destination with no key is not a destination.

    This matters because the YouTube key starts empty by design - it is learned
    from the first stream. Launching ffmpeg with a keyless output URL makes that
    output fail, and ONE failing output kills the whole ffmpeg process, taking
    Twitch down with it. So YouTube simply is not published until its key is
    known, and the relay relaunches with it once it is.
    """
    out = []
    for d in DEST_ORDER:
        dest = cfg["destinations"].get(d, {})
        if dest.get("enabled") and str(dest.get("key", "")).strip():
            out.append(d)
    return out


def waiting_for_key(cfg):
    """Destinations switched on but not yet publishable - shown in the UI so an
    absent YouTube reads as 'learning the key', not as a fault."""
    return [d for d in DEST_ORDER
            if cfg["destinations"].get(d, {}).get("enabled")
            and not str(cfg["destinations"].get(d, {}).get("key", "")).strip()]


# --------------------------------------------------------------------------
# argv fragments
# --------------------------------------------------------------------------

def listen_input(cfg):
    """The RTMP server. -timeout -1 waits forever for Streamlabs to connect, so
    the relay can be started first and left sitting there."""
    return [
        "-listen", "1",
        "-timeout", "-1",
        "-f", "flv",
        "-i", local_url(cfg),
    ]


def music_input(cfg):
    """The music bus, as a DirectShow capture device.

    audio_buffer_size is in ms. The default is tiny and produces dropouts under
    load; 80 ms is enough headroom for a game to stutter without the music
    glitching, and is well under any latency a viewer would notice.
    """
    return [
        "-f", "dshow",
        "-audio_buffer_size", "80",
        "-i", "audio=" + cfg["music_device"],
    ]


def filter_graph(cfg, stream_label="0:a", music_label="1:a", out_label="mix"):
    """Fold the music bus into the stream audio.

    Two details here are load-bearing and neither is the obvious one:

    * amix normalize defaults to TRUE, which divides every input by the number of
      inputs. Left alone it would drop the game and mic by 6 dB the moment music
      was added - the stream would get quieter for having gained a source. It is
      off, and the music level is set explicitly instead.

    * dropout_transition defaults to 2 s, which re-ramps the remaining input's
      gain whenever the other one gaps. On a live capture device a gap is normal,
      so the default turns every hiccup into an audible 2-second swell of the
      game audio. Zero.
    """
    music_chain = []
    if cfg.get("front_pair_only", True):
        music_chain.append("pan=stereo|c0=c0|c1=c1")
    music_chain.append("aresample=48000:async=1:first_pts=0")
    # azmq brokers live commands to named filters, which is the only way to
    # change volume without restarting - and restarting the relay means a
    # two-second blackout on both platforms. The filter is NAMED (@music) so a
    # command can address it; an unnamed one is unreachable.
    #
    # bind_address is deliberately left at its default. ffmpeg's option parser
    # rejects every escape form for a URL containing colons ("No option name
    # near '//127.0.0.1:5555'"), so the default tcp://*:5555 is the only address
    # that parses. It binds all interfaces - see SETUP.md.
    if cfg.get("live_volume", True):
        music_chain.append("azmq")
    music_chain.append("volume@music={:.4f}".format(float(cfg["music_volume"])))

    return (
        "[{m}]{chain}[m];"
        "[{s}]aresample=48000[s];"
        "[s][m]amix=inputs=2:duration=first:normalize=0:dropout_transition=0[{o}]"
    ).format(m=music_label, s=stream_label, o=out_label, chain=",".join(music_chain))


def clean_output(url):
    """Bit-for-bit passthrough. Whatever Streamlabs encoded is what arrives."""
    return ["-map", "0:v", "-map", "0:a", "-c", "copy", "-f", "flv", url]


def music_output(cfg, url, out_label="mix"):
    """Video copied untouched; only the audio is rebuilt."""
    return [
        "-map", "0:v", "-c:v", "copy",
        "-map", "[" + out_label + "]",
        "-c:a", "aac", "-b:a", cfg["audio_bitrate"], "-ar", "48000", "-ac", "2",
        "-f", "flv", url,
    ]


def build(cfg, music=True):
    """Assemble the full argv.

    music=False is the fallback the supervisor drops to when the music device
    cannot be opened: both branches become straight passthrough, so a dead sound
    card costs you the music and NOT the stream.
    """
    dests = enabled_dests(cfg)
    if not dests:
        raise ValueError("no destinations enabled")

    music_dest = cfg["music_dest"] if music else None
    if music_dest is not None and music_dest not in dests:
        # The platform that was meant to get music is switched off - nothing to
        # mix into, so don't open the device at all.
        music_dest = None

    # -progress writes machine-readable telemetry to stdout, which is how the
    # supervisor tells "listening, nobody connected" apart from "live and
    # pushing frames". Parsing that out of the human log would be guesswork.
    argv = [cfg["ffmpeg"], "-hide_banner", "-nostdin", "-nostats",
            "-loglevel", "level+warning", "-progress", "pipe:1"]
    argv += listen_input(cfg)
    if music_dest is not None:
        argv += music_input(cfg)
        argv += ["-filter_complex", filter_graph(cfg)]

    for dest in dests:
        url = dest_url(cfg, dest)
        if dest == music_dest:
            argv += music_output(cfg, url)
        else:
            argv += clean_output(url)
    return argv


def describe(cfg, music=True):
    """One human-readable line per destination, for the GUI and for --dryrun."""
    lines = []
    dests = enabled_dests(cfg)
    for d in waiting_for_key(cfg):
        lines.append("{:<8} waiting - key will be read from the stream".format(d))
    md = cfg["music_dest"] if (music and cfg["music_dest"] in dests) else None
    for dest in dests:
        if dest == md:
            lines.append("{:<8} music ON   (video copied, audio re-mixed @ {})"
                         .format(dest, cfg["audio_bitrate"]))
        else:
            lines.append("{:<8} music OFF  (bit-for-bit passthrough)".format(dest))
    return lines
