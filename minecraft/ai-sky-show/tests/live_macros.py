"""POST-STREAM GATE: exercise every macro against ONE real TestPilot client on the show server.

NOT a unit test (unittest discovery skips it - the name does not start with `test`). Run it only when
the owner is NOT streaming/gaming; it refuses while Streamlabs / Rocket League / obs64 run unless
--force.

Preconditions:
  1. show server up on :25640 (scripts/start_server.ps1), ShowControl on :25642 (optional - state digest)
  2. a FRESH profile is best (objective chain from the start):  RCON `show reset` or a new account
  3. one client up, e.g.:   py -3 -c "from show import clients; clients.launch(clients.plan()[0])"
     (run from AISkyShow/orchestrator)  - or pass --launch

    py -3 tests/live_macros.py                    # all steps, client slot 0 (Kaito, :25650)
    py -3 tests/live_macros.py --slot 2           # Mochi's client
    py -3 tests/live_macros.py --only talk_gerald,chop,craft_pickaxe
    py -3 tests/live_macros.py --list

Writes episodes/_live_gate/<time>/results.json + a screenshot per step and prints PASS/FAIL per step.
A FAIL is information, not necessarily a bug: read the result text - it is exactly what the model sees.
"""
from __future__ import annotations

import argparse
import base64
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "orchestrator"))

from show.config import SHOW  # noqa: E402
from show.macros import run_action  # noqa: E402
from show.observe import observe, screenshot_block  # noqa: E402
from show.pilot_bridge import get_pilot, health  # noqa: E402

BUSY = ("streamlabs", "rocketleague", "obs64")

# (step id, tool, args, what to verify by eye in the screenshot / result)
STEPS = [
    ("look_island", "look_around", {}, "island, Gerald, portal to hub listed"),
    ("talk_gerald", "talk_to", {"npc": "Gerald"}, "intro lines then his menu (Starter Kit...)"),
    ("kit", "click_item", {"name": "Starter Kit"}, "Rookie Pickaxe/Axe/Hoe + Bread in inventory"),
    ("close1", "close_menu", {}, ""),
    ("chop", "chop", {"block": "oak", "count": 4}, "logs in inventory, +Foraging XP"),
    ("craft_planks", "craft", {"item": "oak planks", "n": 2}, "Crafted 4x Oak Planks"),
    ("craft_sticks", "craft", {"item": "stick"}, "sticks"),
    ("craft_table", "craft", {"item": "crafting table"}, "objective 'craft workbench' completes"),
    ("craft_pickaxe", "craft", {"item": "wooden pickaxe"}, "objective 'wooden pickaxe' completes"),
    ("mine", "mine", {"block": "cobblestone", "count": 5}, "walks to the Quarry, 5 cobblestone"),
    ("sbmenu", "open_menu", {"name": "skyblock"}, "SkyBlock Menu buttons listed"),
    ("click_skills", "click_item", {"name": "Your Skills"}, "Your Skills screen"),
    ("close2", "close_menu", {}, ""),
    ("minion", "place_minion", {}, "minion armour stand on a pad"),
    ("equip", "equip", {"item": "Rookie Pickaxe"}, "held item changes"),
    ("say", "say", {"text": "gate test: hello from the macro rig"}, "line in chat"),
    ("cmd", "command", {"text": "/questlog"}, "Quest Log opens"),
    ("close3", "close_menu", {}, ""),
    ("cmd_bad", "command", {"text": "/sb give x"}, "must be REFUSED (ok=false)"),
    ("warp_hub", "warp", {"dest": "hub"}, "in the hub, Village Square"),
    ("look_hub", "look_around", {}, "Town Crier Hollis, places near"),
    ("talk_hollis", "talk_to", {"npc": "Town Crier Hollis"}, "Hub Map menu"),
    ("close4", "close_menu", {}, ""),
    ("warp_locked", "warp", {"dest": "graveyard"}, "REFUSED with 'walk_to graveyard' hint (fresh profile)"),
    ("walk_graveyard", "walk_to", {"place": "graveyard"}, "routes round the village, arrives at the west gate; NEW AREA"),
    ("fight", "fight", {"target": "Graveyard Zombie", "count": 2, "seconds": 45}, "kills counted, HP tracked"),
    ("warp_hub2", "warp", {"dest": "hub"}, ""),
    ("warp_graveyard", "warp", {"dest": "graveyard"}, "now unlocked"),
    ("walk_farm", "walk_to", {"place": "Farm Merchant"}, "arrives next to the Farm Merchant"),
    ("farm", "farm", {"block": "wheat", "count": 6}, "wheat in inventory (grown crops only)"),
    ("talk_merchant", "talk_to", {"npc": "Farm Merchant"}, "greeting then the shop"),
    ("sell", "sell_to_npc", {"item": "wheat"}, "purse goes up ~6/wheat"),
    ("buy", "buy_from_npc", {"npc": "Farm Merchant", "item": "Wheat", "n": 1}, "bought (or 'not enough coins')"),
    ("close5", "close_menu", {}, ""),
    ("talk_gideon", "talk_to", {"npc": "Fisher Gideon"}, "free Fishing Rod (menu/dialogue)"),
    ("close6", "close_menu", {}, ""),
    ("fish", "fish", {"seconds": 40}, "walks to a hotspot/lake, bites + catches"),
    ("ability", "use_ability", {}, "right-click with held item"),
    ("wait", "wait", {"seconds": 3}, ""),
    ("unstuck", "unstuck", {}, "jump/sidestep (may /hub)"),
    ("warp_island", "warp", {"dest": "island"}, "back home"),
    ("collect", "collect_minions", {}, "minion menu -> Collect All"),
    ("bazaar", "bazaar", {"action": "sell_inventory"}, "REFUSED below SkyBlock Level 7 - check the message"),
]


def busy() -> list[str]:
    out = subprocess.run(["tasklist"], capture_output=True, text=True).stdout.lower()
    return [b for b in BUSY if b in out]


class LiveCtx:
    def __init__(self, pilot, char, log_path: Path):
        self.pilot = pilot
        self.char = char
        self._log = open(log_path, "a", encoding="utf-8")

    def log(self, msg: str) -> None:
        self._log.write(f"{time.strftime('%H:%M:%S')} {msg}\n")
        self._log.flush()

    def showcontrol_state(self) -> dict:
        try:
            from show.events import showcontrol
            return showcontrol(f"/state/{self.char.account}", timeout=2.0) or {}
        except Exception:  # noqa: BLE001
            return {}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--slot", type=int, default=0)
    ap.add_argument("--only", default="")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--force", action="store_true", help="run even if Streamlabs/RL/OBS is running")
    ap.add_argument("--launch", action="store_true", help="launch this slot's client first")
    ap.add_argument("--no-shots", action="store_true")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if a.list:
        for sid, tool, args, why in STEPS:
            print(f"{sid:16} {tool}({json.dumps(args)})  {why}")
        return 0
    b = busy()
    if b and not a.force:
        print(f"REFUSING: {', '.join(b)} running (owner streaming/gaming). Use --force only if he said so.")
        return 2
    char = SHOW.characters[a.slot]
    if a.launch:
        from show import clients
        clients.launch(clients.spec_for(char), wait=True, force=a.force)
    h = health(char.pilot_port)
    if not (h["ok"] and h["inWorld"]):
        print(f"client {char.account} not in world on :{char.pilot_port}: {h}")
        return 3
    out = ROOT / "episodes" / "_live_gate" / time.strftime("%Y%m%d_%H%M%S")
    out.mkdir(parents=True, exist_ok=True)
    ctx = LiveCtx(get_pilot(char.pilot_port), char, out / "macro.log")
    only = {s.strip() for s in a.only.split(",") if s.strip()}

    digest, chat_n = observe(ctx, max(0, ctx.pilot.chat_count() - 20), extra="LIVE GATE")
    (out / "digest_start.txt").write_text(digest, encoding="utf-8")
    print(digest, "\n" + "=" * 70)
    blk = screenshot_block(ctx)
    print("screenshot_block:", "OK" if blk else "FAILED",
          (f"{len(blk['source']['data']) * 3 // 4 // 1024} KB" if blk else ""))

    results = []
    for sid, tool, args, why in STEPS:
        if only and sid not in only:
            continue
        t0 = time.time()
        r = run_action(ctx, tool, args)
        dt = time.time() - t0
        expect_fail = "REFUSED" in why
        passed = (not r["ok"]) if expect_fail else r["ok"]
        print(f"{'PASS' if passed else 'FAIL'} {sid:16} {dt:5.1f}s  {r['result']}")
        if why:
            print(f"     check: {why}")
        shot = None
        if not a.no_shots:
            try:
                blk = screenshot_block(ctx)
                if blk:
                    shot = out / f"{len(results):02d}_{sid}.png"
                    shot.write_bytes(base64.b64decode(blk["source"]["data"]))
            except Exception:  # noqa: BLE001
                pass
        results.append({"step": sid, "tool": tool, "args": args, "ok": r["ok"], "pass": passed,
                        "seconds": round(dt, 1), "result": r["result"], "check": why,
                        "shot": shot.name if shot else None})
    digest, _ = observe(ctx, chat_n, extra="LIVE GATE (end)")
    (out / "digest_end.txt").write_text(digest, encoding="utf-8")
    (out / "results.json").write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    n_pass = sum(r["pass"] for r in results)
    print("=" * 70, f"\n{n_pass}/{len(results)} passed; details in {out}")
    return 0 if n_pass == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
