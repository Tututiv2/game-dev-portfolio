"""Tkinter control panel. Stdlib only - nothing to install, nothing to rot.

This module is the ONLY one that imports tkinter. pipeline.py builds the command,
runner.py supervises it, devices.py finds the sound card; none of them know a
window exists, which is what lets the test suite drive all of it headless.
"""

import os
import threading
import time
import webbrowser
import tkinter as tk
from tkinter import ttk

from . import chatwindow, config, devices, overlay, pipeline, runner
from . import slobs, winaudio
from . import twitch as twitch_mod
from . import twitchauth
from . import youtube as yt_mod
from . import youtubeauth

BG = "#15171c"
FG = "#e7e9ee"
DIM = "#8b93a4"
ACC = "#7c5cff"
OK = "#3ddc84"
WARN = "#ffb02e"
BAD = "#ff5c5c"

STATE_COLOUR = {
    "stopped": DIM,
    "listening": WARN,
    "live": OK,
    "retrying": BAD,
}
STATE_TEXT = {
    "stopped": "STOPPED",
    "listening": "WAITING FOR STREAMLABS",
    "live": "LIVE",
    "retrying": "RECONNECTING",
}

BULLET = "•"


def _build_stamp():
    """Newest mtime across the package - the moment this code was last edited."""
    here = os.path.dirname(os.path.abspath(__file__))
    newest = 0.0
    for name in os.listdir(here):
        if name.endswith(".py"):
            try:
                newest = max(newest, os.path.getmtime(os.path.join(here, name)))
            except OSError:
                pass
    return time.strftime("%H:%M", time.localtime(newest)) if newest else "?"


class App:
    def __init__(self, root):
        self.root = root
        self.cfg = config.load()
        self.relay = None
        self.overlay = overlay.Overlay(self.cfg)
        self._pending = []
        self._pending_lock = threading.Lock()

        root.title("SplitCast")
        root.configure(bg=BG)


        self._build()
        # Size from what the widgets actually ask for, not from a guess. Rows
        # have been added twice and each time a hardcoded height left the newest
        # one below the fold, which reads as the feature not existing.
        root.update_idletasks()
        need_h = min(root.winfo_reqheight() + 20, root.winfo_screenheight() - 80)
        need_w = max(820, root.winfo_reqwidth() + 20)
        root.geometry("{}x{}".format(need_w, need_h))
        root.minsize(760, min(760, need_h))

        self.root.after(120, self._drain)
        self.refresh_devices()
        self._refresh_mode()

    # -- layout -----------------------------------------------------------
    def _label(self, parent, text, colour=DIM, size=9, bold=False):
        return tk.Label(parent, text=text, bg=BG, fg=colour,
                        font=("Segoe UI", size, "bold" if bold else "normal"))

    def _section(self, title):
        tk.Frame(self.root, bg="#262a35", height=1).pack(fill="x", padx=14, pady=(12, 0))
        self._label(self.root, title, ACC, 10, True).pack(anchor="w", padx=14, pady=(6, 2))
        box = tk.Frame(self.root, bg=BG)
        box.pack(fill="x", padx=14)
        return box

    def _build(self):
        head = tk.Frame(self.root, bg=BG)
        head.pack(fill="x", padx=14, pady=(12, 0))
        self._label(head, "SplitCast", FG, 17, True).pack(side="left")
        self.state_lbl = self._label(head, "STOPPED", DIM, 10, True)
        self.state_lbl.pack(side="right")
        # Which build this window is running. Editing the source does not reload
        # a live Tk app, so "my window is out of date" is otherwise invisible -
        # it presents as a feature simply not being there.
        self._label(head, "build " + _build_stamp(), DIM, 8).pack(side="right", padx=10)

        self.mode_tagline = self._label(self.root, "", DIM)
        self.mode_tagline.pack(anchor="w", padx=14)

        # --- 0. mode -------------------------------------------------------
        box = self._section("0   Mode")
        self.mode_var = tk.StringVar(value="split")
        for name, title, blurb in (
            ("split", "Split",
             "music on Twitch only  -  2 uploads  -  SplitCast relays"),
            ("direct", "Direct",
             "Streamlabs Multistream  -  1 upload  -  music on both"),
        ):
            row = tk.Frame(box, bg=BG)
            row.pack(fill="x", pady=1)
            tk.Radiobutton(row, text=title, value=name, variable=self.mode_var,
                           command=self._mode_picked, bg=BG, fg=FG, selectcolor=BG,
                           activebackground=BG, activeforeground=FG,
                           font=("Segoe UI", 9, "bold"), width=8, anchor="w"
                           ).pack(side="left")
            self._label(row, blurb, DIM).pack(side="left", padx=4)

        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=(6, 2))
        self.mode_btn = tk.Button(row, text="APPLY MODE", command=self.apply_mode,
                                  bg=ACC, fg="#0d0f14", relief="flat",
                                  font=("Segoe UI", 9, "bold"), padx=16, pady=5)
        self.mode_btn.pack(side="left")
        self.mode_state = self._label(row, "", DIM, 9, True)
        self.mode_state.pack(side="left", padx=12)

        self.mode_steps = self._label(box, "", DIM)
        self.mode_steps.pack(anchor="w", pady=(2, 0))

        # --- 1. where Streamlabs sends -----------------------------------
        box = self._section("1   Point Streamlabs here")
        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=2)
        self._label(row, "Server", DIM).pack(side="left")
        self.server_var = tk.StringVar(value=pipeline.streamlabs_server(self.cfg))
        tk.Entry(row, textvariable=self.server_var, bg="#1e212a", fg=FG,
                 relief="flat", insertbackground=FG,
                 readonlybackground="#1e212a", state="readonly"
                 ).pack(side="left", fill="x", expand=True, padx=8)
        tk.Button(row, text="Copy", command=lambda: self._copy(self.server_var.get()),
                  bg="#2a2e3a", fg=FG, relief="flat", padx=10).pack(side="left")

        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=2)
        self._label(row, "Key    ", DIM).pack(side="left")
        self.key_var = tk.StringVar(value=self.cfg["key"])
        tk.Entry(row, textvariable=self.key_var, bg="#1e212a", fg=FG, relief="flat",
                 insertbackground=FG).pack(side="left", fill="x", expand=True, padx=8)
        tk.Button(row, text="Copy", command=lambda: self._copy(self.key_var.get()),
                  bg="#2a2e3a", fg=FG, relief="flat", padx=10).pack(side="left")
        self._label(box, "Streamlabs > Settings > Stream > Custom RTMP.  "
                         "Multistream must be OFF.", DIM).pack(anchor="w", pady=(2, 0))

        # --- 2. destinations ----------------------------------------------
        box = self._section("2   Stream keys")
        self.dest_vars = {}
        self.enable_vars = {}
        for dest in pipeline.DEST_ORDER:
            row = tk.Frame(box, bg=BG)
            row.pack(fill="x", pady=2)
            ev = tk.BooleanVar(value=self.cfg["destinations"][dest]["enabled"])
            self.enable_vars[dest] = ev
            tk.Checkbutton(row, variable=ev, bg=BG, activebackground=BG,
                           selectcolor="#2a2e3a", command=self._sync_summary
                           ).pack(side="left")
            self._label(row, dest.title().ljust(9), FG).pack(side="left")
            sv = tk.StringVar(value=self.cfg["destinations"][dest]["key"])
            self.dest_vars[dest] = sv
            tk.Entry(row, textvariable=sv, bg="#1e212a", fg=FG, relief="flat",
                     show=BULLET, insertbackground=FG
                     ).pack(side="left", fill="x", expand=True, padx=8)

        # --- 3. music -------------------------------------------------------
        box = self._section("3   Music")
        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=2)
        self._label(row, "Plays on", DIM).pack(side="left")
        self.music_dest = tk.StringVar(value=self.cfg["music_dest"])
        for dest in pipeline.DEST_ORDER:
            tk.Radiobutton(row, text=dest.title(), value=dest, variable=self.music_dest,
                           bg=BG, fg=FG, activebackground=BG, activeforeground=FG,
                           selectcolor="#2a2e3a", command=self._sync_summary
                           ).pack(side="left", padx=6)

        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=2)
        self._label(row, "Source  ", DIM).pack(side="left")
        self.device_var = tk.StringVar(value=self.cfg["music_device"])
        self.device_box = ttk.Combobox(row, textvariable=self.device_var, state="readonly")
        self.device_box.pack(side="left", fill="x", expand=True, padx=8)
        tk.Button(row, text="Rescan", command=self.refresh_devices,
                  bg="#2a2e3a", fg=FG, relief="flat", padx=10).pack(side="left")
        tk.Button(row, text="Test", command=self.test_device,
                  bg="#2a2e3a", fg=FG, relief="flat", padx=10).pack(side="left", padx=(6, 0))

        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=2)
        self._label(row, "Volume ", DIM).pack(side="left")
        self.vol = tk.DoubleVar(value=self.cfg["music_volume"])
        tk.Scale(row, from_=0.0, to=1.5, resolution=0.05, orient="horizontal",
                 variable=self.vol, bg=BG, fg=FG, troughcolor="#1e212a",
                 highlightthickness=0, relief="flat", length=280,
                 command=self._volume_moved).pack(side="left", padx=8)
        self.vol_lbl = self._label(row, "", DIM, 9)
        self.vol_lbl.pack(side="left", padx=6)
        self._label(box, "Applies LIVE once you are streaming - no restart, no "
                         "interruption. Before that it is saved for launch.",
                    DIM).pack(anchor="w")

        # --- 4. run ----------------------------------------------------------
        box = self._section("4   Run")
        self.summary = self._label(box, "", FG, 9)
        self.summary.pack(anchor="w", pady=(0, 6))

        row = tk.Frame(box, bg=BG)
        row.pack(fill="x")
        self.go = tk.Button(row, text="START RELAY", command=self.toggle,
                            bg=ACC, fg="white", relief="flat",
                            font=("Segoe UI", 11, "bold"), padx=24, pady=8)
        self.go.pack(side="left")
        self.music_lbl = self._label(row, "", OK, 9, True)
        self.music_lbl.pack(side="left", padx=14)

        # --- 5. now-playing overlay -----------------------------------------
        box = self._section("5   Now-playing overlay  (optional)")
        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=2)
        self._label(row, "Caption", DIM).pack(side="left")
        self.tagline = tk.StringVar(value=self.cfg.get("overlay_tagline",
                                                       "Music on Twitch!"))
        tk.Entry(row, textvariable=self.tagline, bg="#1e212a", fg=FG, relief="flat",
                 insertbackground=FG).pack(side="left", fill="x", expand=True, padx=8)

        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=4)
        self.ov_btn = tk.Button(row, text="START OVERLAY", command=self.toggle_overlay,
                                bg="#2a2e3a", fg=FG, relief="flat",
                                font=("Segoe UI", 9, "bold"), padx=16, pady=5)
        self.ov_btn.pack(side="left")
        self.ov_url = tk.StringVar(value=self.overlay.url)
        tk.Entry(row, textvariable=self.ov_url, bg="#1e212a", fg=DIM, relief="flat",
                 readonlybackground="#1e212a", state="readonly"
                 ).pack(side="left", fill="x", expand=True, padx=8)
        tk.Button(row, text="Copy", command=lambda: self._copy(self.ov_url.get()),
                  bg="#2a2e3a", fg=FG, relief="flat", padx=10).pack(side="left")
        tk.Button(row, text="Preview", command=self.preview_overlay,
                  bg="#2a2e3a", fg=FG, relief="flat", padx=10).pack(side="left", padx=(6, 0))
        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=(2, 0))
        self._label(row, "Chat  ", DIM).pack(side="left")
        self.chat_url = tk.StringVar(value=self.overlay.chat_url)
        tk.Entry(row, textvariable=self.chat_url, bg="#1e212a", fg=DIM, relief="flat",
                 readonlybackground="#1e212a", state="readonly"
                 ).pack(side="left", fill="x", expand=True, padx=8)
        tk.Button(row, text="Copy", command=lambda: self._copy(self.chat_url.get()),
                  bg="#2a2e3a", fg=FG, relief="flat", padx=10).pack(side="left")
        tk.Button(row, text="Preview", command=self.preview_chat,
                  bg="#2a2e3a", fg=FG, relief="flat", padx=10).pack(side="left", padx=(6, 0))

        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=(6, 0))
        tk.Button(row, text="OPEN CHAT WINDOW", command=self.open_chat_window,
                  bg="#2a2e3a", fg=FG, relief="flat",
                  font=("Segoe UI", 9, "bold"), padx=16, pady=5).pack(side="left")
        self._label(row, "  a readable window for your second monitor",
                    DIM).pack(side="left")

        self._label(box, "Both are Browser Sources at 1920x1080. Tick "
                         "'Shutdown source when not visible'.",
                    DIM).pack(anchor="w")
        self._label(box, "Combined chat: Twitch + YouTube, with emotes. "
                         "Twitch needs no login; YouTube is read while you are live.",
                    DIM).pack(anchor="w")

        # --- 6. chatting back -------------------------------------------------
        box = self._section("6   Chat back  (Twitch)")
        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=2)
        self._label(row, "Client ID", DIM).pack(side="left")
        self.tw_client = tk.StringVar(value=self.cfg.get("twitch_client_id", ""))
        tk.Entry(row, textvariable=self.tw_client, bg="#1e212a", fg=FG, relief="flat",
                 insertbackground=FG).pack(side="left", fill="x", expand=True, padx=8)
        tk.Button(row, text="Get one", command=self.open_twitch_console,
                  bg="#2a2e3a", fg=FG, relief="flat", padx=10).pack(side="left")

        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=4)
        self.tw_btn = tk.Button(row, text="CONNECT TWITCH", command=self.connect_twitch,
                                bg="#9146FF", fg="white", relief="flat",
                                font=("Segoe UI", 9, "bold"), padx=16, pady=5)
        self.tw_btn.pack(side="left")
        self.tw_state = self._label(row, "", DIM, 9, True)
        self.tw_state.pack(side="left", padx=12)
        tk.Button(row, text="Disconnect", command=self.disconnect_twitch,
                  bg="#2a2e3a", fg=FG, relief="flat", padx=10).pack(side="right")
        self._label(box, "Opens Twitch's own page - you click Authorize. No password "
                         "is ever typed here, and you can revoke it any time.",
                    DIM).pack(anchor="w")
        self.tw_client.trace_add("write",
                                 self._make_saver(self.tw_client, "twitch_client_id"))
        self._refresh_twitch_state()

        # --- 7. youtube write access -------------------------------------
        box = self._section("7   Chat back  (YouTube)")
        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=2)
        self._label(row, "Client ID    ", DIM).pack(side="left")
        self.yt_client = tk.StringVar(value=self.cfg.get("youtube_client_id", ""))
        tk.Entry(row, textvariable=self.yt_client, bg="#1e212a", fg=FG, relief="flat",
                 insertbackground=FG).pack(side="left", fill="x", expand=True, padx=8)
        tk.Button(row, text="Get one", command=self.open_google_console,
                  bg="#2a2e3a", fg=FG, relief="flat", padx=10).pack(side="left")

        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=2)
        self._label(row, "Client secret", DIM).pack(side="left")
        self.yt_secret = tk.StringVar(value=self.cfg.get("youtube_client_secret", ""))
        tk.Entry(row, textvariable=self.yt_secret, bg="#1e212a", fg=FG, relief="flat",
                 show=BULLET, insertbackground=FG
                 ).pack(side="left", fill="x", expand=True, padx=8)

        row = tk.Frame(box, bg=BG)
        row.pack(fill="x", pady=4)
        self.yt_btn = tk.Button(row, text="CONNECT YOUTUBE", command=self.connect_youtube,
                                bg="#ff4e45", fg="white", relief="flat",
                                font=("Segoe UI", 9, "bold"), padx=16, pady=5)
        self.yt_btn.pack(side="left")
        self.yt_state = self._label(row, "", DIM, 9, True)
        self.yt_state.pack(side="left", padx=12)
        tk.Button(row, text="Disconnect", command=self.disconnect_youtube,
                  bg="#2a2e3a", fg=FG, relief="flat", padx=10).pack(side="right")
        self._label(box, "Sending costs YouTube quota: 50 units of 10,000 a day, and "
                         "you have two live chats - about 100 messages/day.",
                    DIM).pack(anchor="w")
        # Persist credential fields as they are typed. Requiring a button press
        # to save them meant a filled-in form looked done while the config was
        # still empty - which is indistinguishable from the app ignoring you.
        for var, key in ((self.yt_client, "youtube_client_id"),
                         (self.yt_secret, "youtube_client_secret")):
            var.trace_add("write", self._make_saver(var, key))
        self._refresh_youtube_state()

        self.logbox = tk.Text(self.root, bg="#0f1116", fg=DIM, relief="flat",
                              height=9, font=("Consolas", 8), wrap="none")
        self.logbox.pack(fill="both", expand=True, padx=14, pady=(10, 12))
        self._sync_summary()

    # -- behaviour --------------------------------------------------------
    def _volume_moved(self, _value):
        """Debounced. A Tk Scale fires on every pixel of a drag, and each event
        would be its own zmq round trip - so the send is deferred and the timer
        restarted, meaning only the value the slider LANDS on is sent."""
        if getattr(self, "_vol_job", None):
            try:
                self.root.after_cancel(self._vol_job)
            except Exception:
                pass
        self._vol_job = self.root.after(180, self._apply_volume)

    def _apply_volume(self):
        self._vol_job = None
        value = round(float(self.vol.get()), 3)
        self.cfg["music_volume"] = value
        if not self.relay or self.relay.state == "stopped":
            self.vol_lbl.config(text="saved for launch", fg=DIM)
            return
        ok, detail = self.relay.set_volume(value)
        if ok:
            self.vol_lbl.config(text="live", fg=OK)
        else:
            self.vol_lbl.config(text=detail[:26], fg=WARN)

    # -- twitch -----------------------------------------------------------
    def open_twitch_console(self):
        webbrowser.open("https://dev.twitch.tv/console/apps/create")
        self._log("Twitch console opened. Name it anything, set OAuth Redirect URL "
                  "to " + twitch_mod.REDIRECT_URI + ", category Application "
                  "Integration, then copy the Client ID here.")

    def _refresh_twitch_state(self):
        tok = self.cfg.get("twitch_token", "")
        if not tok:
            self.tw_state.config(text="not connected", fg=DIM)
            return
        self.tw_state.config(text="checking...", fg=DIM)

        def work():
            try:
                info = twitch_mod.validate(tok)
            except Exception:
                info = None
            if info:
                self._post(lambda: self.tw_state.config(
                    text="connected as " + str(info.get("login", "?")), fg=OK))
            else:
                self._post(lambda: self.tw_state.config(
                    text="token expired - reconnect", fg=WARN))
        threading.Thread(target=work, daemon=True).start()

    def connect_twitch(self):
        cid = self.tw_client.get().strip()
        if not cid:
            self._log("!! paste your Twitch Client ID first (press 'Get one')")
            return
        self.cfg["twitch_client_id"] = cid
        config.save(self.cfg)
        self.tw_btn.config(state="disabled")
        self.tw_state.config(text="waiting for browser...", fg=WARN)
        self._log("browser opened - click Authorize on Twitch's page")

        def work():
            try:
                tw = twitchauth.connect(cid)
            except Exception as exc:
                self._post(lambda: self._log("!! twitch connect failed: " + str(exc)[:160]))
                self._post(lambda: self.tw_state.config(text="not connected", fg=BAD))
            else:
                self.cfg["twitch_token"] = tw.token
                config.save(self.cfg)
                if self.overlay:
                    self.overlay.cfg["twitch_token"] = tw.token
                    self.overlay.cfg["twitch_client_id"] = cid
                    self.overlay._tw = None       # rebuild with the new token
                self._post(lambda: self.tw_state.config(
                    text="connected as " + str(tw.login), fg=OK))
                self._post(lambda: self._log("twitch connected as " + str(tw.login)))
            finally:
                self._post(lambda: self.tw_btn.config(state="normal"))
        threading.Thread(target=work, daemon=True).start()

    def disconnect_twitch(self):
        tok = self.cfg.get("twitch_token", "")
        cid = self.cfg.get("twitch_client_id", "")
        if tok and cid:
            threading.Thread(target=lambda: twitch_mod.revoke(tok, cid),
                             daemon=True).start()
        self.cfg["twitch_token"] = ""
        config.save(self.cfg)
        if self.overlay:
            self.overlay.cfg["twitch_token"] = ""
            self.overlay._tw = None
        self.tw_state.config(text="not connected", fg=DIM)
        self._log("twitch token revoked and cleared")

    def _make_saver(self, var, key):
        """Debounced write-through so every keystroke is not a disk write."""
        def on_change(*_a):
            job = getattr(self, "_save_jobs", {}).get(key)
            if job:
                try:
                    self.root.after_cancel(job)
                except Exception:
                    pass
            if not hasattr(self, "_save_jobs"):
                self._save_jobs = {}

            def commit():
                self.cfg[key] = var.get().strip()
                try:
                    config.save(self.cfg)
                except Exception:
                    pass
            self._save_jobs[key] = self.root.after(600, commit)
        return on_change

    # -- youtube ----------------------------------------------------------
    def open_google_console(self):
        webbrowser.open("https://console.cloud.google.com/apis/credentials")
        self._log("Google console opened. New project > enable 'YouTube Data API v3' > "
                  "OAuth consent screen (External, add yourself as a test user) > "
                  "Credentials > Create OAuth client ID > type DESKTOP APP. "
                  "Copy the Client ID and secret here.")

    def _yt_verifier(self):
        """One verifier per login attempt, shared with the auth flow so the
        URL shown to the user and the code redemption agree."""
        self._yt_pending = yt_mod.make_verifier()
        return self._yt_pending

    def _refresh_youtube_state(self):
        if not self.cfg.get("youtube_refresh"):
            self.yt_state.config(text="not connected", fg=DIM)
        else:
            self.yt_state.config(text="connected", fg=OK)

    def connect_youtube(self):
        cid = self.yt_client.get().strip()
        sec = self.yt_secret.get().strip()
        if not cid:
            self._log("!! paste your Google Client ID first (press 'Get one')")
            return
        self.cfg["youtube_client_id"] = cid
        self.cfg["youtube_client_secret"] = sec
        config.save(self.cfg)
        self.yt_btn.config(state="disabled")
        self.yt_state.config(text="waiting for browser...", fg=WARN)

        # The default browser is not necessarily the one signed into the right
        # account. Put the URL where it can be pasted anywhere, and copy it to
        # the clipboard, rather than assuming.
        url = yt_mod.authorize_url(cid, self._yt_verifier())
        self._copy(url)
        self._log("browser opened AND link copied to clipboard.")
        self._log("If it opened the wrong browser, paste the link into the one "
                  "signed into your YouTube account.")

        def work():
            try:
                tok = youtubeauth.connect(cid, sec, verifier=self._yt_pending)
            except Exception as exc:
                self._post(lambda: self._log("!! youtube connect failed: " + str(exc)[:200]))
                self._post(lambda: self.yt_state.config(text="not connected", fg=BAD))
            else:
                self.cfg["youtube_access"] = tok["access"]
                self.cfg["youtube_refresh"] = tok["refresh"]
                self.cfg["youtube_expires_at"] = tok["expires_at"]
                config.save(self.cfg)
                if self.overlay:
                    for k in ("youtube_client_id", "youtube_client_secret",
                              "youtube_access", "youtube_refresh", "youtube_expires_at"):
                        self.overlay.cfg[k] = self.cfg[k]
                    self.overlay._yt_api = None      # rebuild with the new token
                self._post(lambda: self.yt_state.config(text="connected", fg=OK))
                self._post(lambda: self._log("youtube connected"))
            finally:
                self._post(lambda: self.yt_btn.config(state="normal"))
        threading.Thread(target=work, daemon=True).start()

    def disconnect_youtube(self):
        tok = self.cfg.get("youtube_refresh", "")
        if tok:
            threading.Thread(target=lambda: yt_mod.revoke(tok), daemon=True).start()
        for k in ("youtube_access", "youtube_refresh"):
            self.cfg[k] = ""
        self.cfg["youtube_expires_at"] = 0
        config.save(self.cfg)
        if self.overlay:
            self.overlay.cfg["youtube_refresh"] = ""
            self.overlay._yt_api = None
        self.yt_state.config(text="not connected", fg=DIM)
        self._log("youtube token revoked and cleared")

    def _copy(self, text):
        self.root.clipboard_clear()
        self.root.clipboard_append(text)

    # -- mode -------------------------------------------------------------
    #
    # SPLIT and DIRECT differ by one file, and Streamlabs reads that file only
    # at startup - so every switch costs a Streamlabs restart, and forgetting it
    # fails SILENTLY in both directions. That is why this shows a checklist
    # rather than a tick: the file is the only part SplitCast can do itself.

    def _host_port(self):
        return self.cfg["listen_host"], int(self.cfg["listen_port"])

    def _music_reaches_streamlabs(self):
        """Would music be heard in DIRECT mode? (captured, detail)

        In SPLIT the relay adds music afterwards, so Streamlabs deliberately
        does NOT encode the music channel. Nobody is reminded of that when they
        switch, and the failure is a silent absence rather than an error.
        """
        try:
            endpoints = winaudio.render_endpoints()
            names = [winaudio.name_for(dev, endpoints)
                     for _, _, dev, muted in slobs.captured_audio() if not muted]
            named = [n for n in names if n]
            if not named:
                return None, "could not read what Streamlabs captures"
            for name in named:
                if winaudio.looks_like_music_bus(name):
                    return True, name
            return False, ", ".join(named)
        except Exception as exc:                       # noqa: BLE001
            return None, str(exc)

    def _refresh_mode(self):
        """Show the mode that is actually ON DISK, never the radio button.

        The radio button is an intention; services.json is the fact. Showing the
        intention would make an un-applied switch look applied.
        """
        host, port = self._host_port()
        try:
            on_disk = slobs.current_mode(host, port)
        except Exception:                              # noqa: BLE001
            on_disk = "unknown"
        colour = {"split": OK, "direct": ACC}.get(on_disk, WARN)
        self.mode_state.config(text="on disk: " + on_disk, fg=colour)

        if on_disk in ("split", "direct"):
            self.mode_var.set(on_disk)
        self.mode_tagline.config(
            text="Music reaches one platform only. The other gets a bit-for-bit copy."
            if on_disk == "split" else
            "Streamlabs sends one upload to both platforms. Music is on both.")
        self._mode_picked()
        return on_disk

    def _mode_picked(self):
        """Enable the relay only in the mode where it can receive anything."""
        want = self.mode_var.get()
        try:
            on_disk = slobs.current_mode(*self._host_port())
        except Exception:                              # noqa: BLE001
            on_disk = "unknown"

        running = bool(self.relay and self.relay.state != "stopped")
        if on_disk == "direct" and not running:
            self.go.config(state="disabled")
        else:
            self.go.config(state="normal")

        if want != on_disk and on_disk != "unknown":
            self.mode_steps.config(
                text="not applied yet - press APPLY MODE", fg=WARN)
        else:
            self.mode_steps.config(text=self._steps_for(on_disk), fg=DIM)

    def _steps_for(self, mode):
        if mode == "direct":
            music, detail = self._music_reaches_streamlabs()
            music_line = {
                True: "music OK - Streamlabs already encodes " + str(detail),
                False: "MUSIC WILL BE SILENT - Streamlabs encodes only " +
                       str(detail) + ". Add an Audio Output Capture "
                       "source for SteelSeries Sonar - Media.",
                None: "could not check the music channel (" + str(detail) + ")",
            }[music]
            return ("Restart Streamlabs, then in Go Live turn Twitch ON.\n"
                    "The relay is not used - overlay and chat still work.\n"
                    + music_line)
        if mode == "split":
            return ("Restart Streamlabs, then in Go Live leave Twitch OFF "
                    "(SplitCast delivers it).\n"
                    "Start the relay below before going live.")
        return "services.json could not be read - is Streamlabs installed?"

    def apply_mode(self):
        want = self.mode_var.get()
        if self.relay and self.relay.state != "stopped":
            self._log("!! stop the relay before changing mode")
            return
        ok, detail = slobs.set_mode(want, *self._host_port())
        self._log(("mode -> " + want + ": " if ok else "!! mode change failed: ")
                  + detail)
        on_disk = self._refresh_mode()
        if not ok:
            return
        self._log("RESTART STREAMLABS - it reads services.json only at startup,")
        self._log("and skipping it fails silently in both directions.")
        for line in self._steps_for(on_disk).split("\n"):
            self._log("  - " + line)

    def collect(self):
        cfg = self.cfg
        cfg["key"] = self.key_var.get().strip() or "stream"
        cfg["music_dest"] = self.music_dest.get()
        cfg["music_device"] = self.device_var.get()
        cfg["music_volume"] = round(float(self.vol.get()), 3)
        for dest in pipeline.DEST_ORDER:
            cfg["destinations"][dest]["key"] = self.dest_vars[dest].get().strip()
            cfg["destinations"][dest]["enabled"] = bool(self.enable_vars[dest].get())
        self.server_var.set(pipeline.streamlabs_server(cfg))
        return cfg

    def _sync_summary(self, *_):
        try:
            cfg = self.collect()
            text = "\n".join(pipeline.describe(cfg)) or "nothing enabled"
        except Exception as exc:
            text = str(exc)
        self.summary.config(text=text)

    def refresh_devices(self):
        def work():
            names = devices.rank(devices.list_audio(self.cfg["ffmpeg"]))
            self._post(lambda: self._set_devices(names))
        threading.Thread(target=work, daemon=True).start()

    def _set_devices(self, names):
        self.device_box["values"] = names
        if self.device_var.get() not in names and names:
            self.device_var.set(names[0])
        if devices.find_stream_bus(names) is None:
            self._log("note: " + devices.SONAR_STREAM + " is not being offered - "
                      "turn Streamer Mode ON in SteelSeries Sonar, then Rescan.")

    def test_device(self):
        name = self.device_var.get()
        self._log("testing " + name + " ...")

        def work():
            ok, detail = devices.probe(self.cfg["ffmpeg"], name)
            self._post(lambda: self._log(("  OK    " if ok else "  FAIL  ") + detail))
        threading.Thread(target=work, daemon=True).start()

    def toggle(self):
        if self.relay and self.relay.state != "stopped":
            self.relay.stop()
            self.relay = None
            self.go.config(text="START RELAY", bg=ACC)
            self.music_lbl.config(text="")
            self._mode_picked()
            return
        cfg = self.collect()
        # A missing YouTube key is normal now - it is read from the stream. Only
        # refuse if NOTHING is publishable, otherwise the relay cannot start on
        # the very run that would have taught it the key.
        if not pipeline.enabled_dests(cfg):
            self._log("!! no destination has a stream key yet - "
                      "paste at least one (Twitch) to start")
            return
        for d in pipeline.waiting_for_key(cfg):
            self._log("{} has no key yet - it will be learned from the stream".format(d))
        config.save(cfg)
        self.relay = runner.Relay(cfg, on_event=self._event)
        self.relay.start()
        self.go.config(text="STOP RELAY", bg="#3a3f4d")
        self.music_lbl.config(text="music ON", fg=OK)
        self.vol_lbl.config(text="", fg=DIM)
        self._mode_picked()

    def toggle_overlay(self):
        if self.overlay.httpd:
            self.overlay.stop()
            self.ov_btn.config(text="START OVERLAY", bg="#2a2e3a")
            self._log("overlay stopped")
            return
        self.cfg["overlay_tagline"] = self.tagline.get().strip() or "Music on Twitch!"
        try:
            url = self.overlay.start()
        except OSError as exc:
            self._log("!! overlay could not start: " + str(exc))
            return
        config.save(self.cfg)
        self.ov_url.set(url)
        self.chat_url.set(self.overlay.chat_url)
        self.ov_btn.config(text="STOP OVERLAY", bg="#3a3f4d")
        self._log("overlay serving at " + url)

    def open_chat_window(self):
        if not self.overlay.httpd:
            self.toggle_overlay()      # the window is useless without the server
        if not self.overlay.httpd:
            return
        ok, detail = chatwindow.open_window(self.cfg)
        self._log(("chat window opened via " + detail) if ok
                  else ("!! " + detail))

    def preview_chat(self):
        if not self.overlay.httpd:
            self.toggle_overlay()
        if self.overlay.httpd:
            # Preview with fake traffic - an empty chat tells you nothing about
            # whether the overlay is the right size or in the right place.
            webbrowser.open(self.overlay.chat_url + "?demo=1")

    def preview_overlay(self):
        if not self.overlay.httpd:
            self.toggle_overlay()
        if self.overlay.httpd:
            # Fake track, same reason the chat preview uses fake chat: an empty
            # overlay and a broken one look identical.
            webbrowser.open(self.overlay.url + "?demo=1")

    # -- thread-safe UI updates -------------------------------------------
    def _post(self, fn):
        with self._pending_lock:
            self._pending.append(fn)

    def _event(self, kind, text):
        if kind == "log":
            self._post(lambda: self._log(text))
        elif kind == "state":
            self._post(lambda: self._state(text))
        elif kind == "music" and text == "off":
            self._post(lambda: self.music_lbl.config(
                text="music OFF (device failed)", fg=BAD))

    def _drain(self):
        with self._pending_lock:
            jobs, self._pending = self._pending, []
        for job in jobs:
            try:
                job()
            except Exception:
                pass
        self.root.after(120, self._drain)

    def _state(self, state):
        self.state_lbl.config(text=STATE_TEXT.get(state, state.upper()),
                              fg=STATE_COLOUR.get(state, DIM))

    def _log(self, text):
        self.logbox.insert("end", text + "\n")
        self.logbox.see("end")
        if float(self.logbox.index("end")) > 500:
            self.logbox.delete("1.0", "200.0")


def main():
    root = tk.Tk()
    app = App(root)

    def on_close():
        if app.relay:
            app.relay.stop()
        app.overlay.stop()
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", on_close)
    root.mainloop()
