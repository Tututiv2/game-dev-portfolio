"""Let a test agent report a checklist to the progress board.

    py -3 tools/progress/step.py start "Regression playtest" 12   # title, total steps
    py -3 tools/progress/step.py done "Bazaar: buy order filled"  # one step finished
    py -3 tools/progress/step.py clear
"""
import json
import sys
import time
from pathlib import Path

LIVE = Path(__file__).resolve().parents[2] / "docs/live/progress.json"


def main(argv):
    LIVE.parent.mkdir(parents=True, exist_ok=True)
    cur = json.loads(LIVE.read_text(encoding="utf-8")) if LIVE.exists() else {}
    cmd = argv[1] if len(argv) > 1 else ""
    if cmd == "start":
        cur = {"title": argv[2], "total": int(argv[3]), "done": 0, "step": "starting", "started": time.time()}
    elif cmd == "done":
        cur["done"] = min(cur.get("total", 1), cur.get("done", 0) + 1)
        cur["step"] = argv[2] if len(argv) > 2 else ""
    elif cmd == "clear":
        LIVE.unlink(missing_ok=True)
        return
    else:
        print(__doc__)
        return
    LIVE.write_text(json.dumps(cur), encoding="utf-8")


if __name__ == "__main__":
    main(sys.argv)
