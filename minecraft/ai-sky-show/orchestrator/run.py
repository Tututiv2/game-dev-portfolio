"""AISkyShow command line.

    py -3 run.py preflight
    py -3 run.py episode [--mock] [--brain fake|real] [--minutes N] [--no-obs] [--voice auto|sapi|indextts|none]
    py -3 run.py edit <episode_dir>
    py -3 run.py obs-setup            # connect to OBS and build/repair the three AISky scenes
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

try:                                     # Windows consoles are cp1252; never die on a print
    sys.stdout.reconfigure(errors="replace")
    sys.stderr.reconfigure(errors="replace")
except Exception:
    pass


def cmd_preflight(a) -> int:
    from show.preflight import run_preflight
    r = run_preflight(mock=a.mock, check_obs=not a.no_obs)
    print("AISkyShow preflight")
    print(r.text())
    return 0 if r.ok else 2


def cmd_episode(a) -> int:
    from show.episode import Episode, EpisodeOptions
    from show.preflight import run_preflight
    if not a.skip_preflight:
        r = run_preflight(mock=a.mock, check_obs=not a.no_obs)
        print(r.text())
        if not r.ok:
            print("refusing to start - fix the FAIL lines (or --skip-preflight if you know better)")
            return 2
    brain = a.brain or ("fake" if a.mock else "real")
    opts = EpisodeOptions(mock=a.mock, brain=brain, agents=a.agents, minutes=a.minutes, use_obs=not a.no_obs,
                          voice=a.voice, start_server=not a.no_server, director_llm=not a.rubric_only)
    if a.speed:
        opts.speed = a.speed
    elif a.mock and brain == "fake" and (a.agents in (None, "sim")):
        opts.speed = 1.0
    if a.fast_intro:
        opts.title_s, opts.join_gap_s, opts.beat_s = 3.0, 6.0, 3.0
    ep = Episode(opts)
    res = ep.run()
    print(json.dumps(res, indent=2))
    return 0 if res.get("ok") else 1


def cmd_edit(a) -> int:
    from show.edit import generate
    out = generate(Path(a.episode_dir), fps=a.fps)
    for k, v in out.items():
        print(f"  {k:13s} {v}")
    return 0


def cmd_obs_setup(a) -> int:
    from show.config import SHOW
    from show.obs import connect_from_config, OBSRig, read_obs_websocket_config, OBSError
    cfg = read_obs_websocket_config()
    print(f"OBS websocket config: enabled={cfg.get('server_enabled')} port={cfg.get('server_port')} "
          f"auth={cfg.get('auth_required')} password={'set' if cfg.get('server_password') else 'none'}")
    try:
        c = connect_from_config(timeout=4)
    except Exception as e:
        print(f"cannot connect to OBS: {e}\nsee docs/OBS_SETUP.md")
        return 2
    try:
        v = c.request("GetVersion")
        print(f"connected: OBS {v.get('obsVersion')} / websocket {v.get('obsWebSocketVersion')}")
        rig = OBSRig(c, SHOW.characters, overlay_url=f"http://127.0.0.1:{SHOW.ports['orchestrator']}/overlay")
        print(json.dumps(rig.ensure_scenes(), indent=2))
        rig.grid()
        print("scenes ready; program scene set to AISky Grid")
        return 0
    except OBSError as e:
        print(f"OBS error: {e}")
        return 1
    finally:
        c.close()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="run.py", description="Can 4 AIs Beat Hypixel SkyBlock? - show rig")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("preflight", help="check the machine is ready")
    p.add_argument("--mock", action="store_true")
    p.add_argument("--no-obs", action="store_true")
    p.set_defaults(fn=cmd_preflight)

    p = sub.add_parser("episode", help="run an episode")
    p.add_argument("--mock", action="store_true", help="no Minecraft/OBS: simulated world")
    p.add_argument("--brain", choices=["fake", "real"], default=None, help="fake = canned replies (default with --mock)")
    p.add_argument("--agents", choices=["sim", "real"], default=None, help="sim = scripted agents (mock only)")
    p.add_argument("--minutes", type=float, default=None)
    p.add_argument("--speed", type=float, default=None, help="mock+sim only: run the clock N x faster")
    p.add_argument("--fast-intro", action="store_true", help="short title/join beats (testing)")
    p.add_argument("--no-obs", action="store_true", help="do not record")
    p.add_argument("--no-server", action="store_true", help="do not start the dashboard on 5650")
    p.add_argument("--rubric-only", action="store_true", help="director without the LLM layer")
    p.add_argument("--voice", default="auto", choices=["auto", "indextts", "sapi", "none"])
    p.add_argument("--skip-preflight", action="store_true")
    p.set_defaults(fn=cmd_episode)

    p = sub.add_parser("edit", help="(re)generate chapters/best moments/EDL/shorts/transcript")
    p.add_argument("episode_dir")
    p.add_argument("--fps", type=int, default=30)
    p.set_defaults(fn=cmd_edit)

    p = sub.add_parser("obs-setup", help="build the AISky scenes in OBS")
    p.set_defaults(fn=cmd_obs_setup)

    a = ap.parse_args(argv)
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
