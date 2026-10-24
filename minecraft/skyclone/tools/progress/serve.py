"""Live progress board for SkyClone's agent workflows (playtests, builds, fixers).

    py -3 tools/progress/serve.py            -> http://localhost:5660/  (phone: http://yourpc.local:5660/)

Reads the newest Claude Code workflow run under WORKFLOWS (one journal.jsonl + one transcript per
agent) and shows: every agent with its phase, state, elapsed time, what it is doing right now, an ETA
from how long agents with the same label prefix took in earlier runs, and the newest TestPilot
screenshot. Needs no cooperation from the agents. If an agent writes docs/live/progress.json (see
step.py) its checklist (done/total) is shown too. Stdlib only; read-only except for its own cache.
"""
import glob
import html
import json
import os
import statistics
import sys
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORT = int(os.environ.get("PROGRESS_PORT", "5660"))
ROOT = Path(__file__).resolve().parents[2]                      # SkyblockClone/
WORKFLOWS = Path(os.environ.get("PROGRESS_WORKFLOWS", str(
    Path.home() / ".claude/projects/D--projects/92ed239c-7332-4877-92fe-13082a55fbb5/subagents/workflows")))
SHOT_DIRS = [ROOT / "docs/playtest", ROOT / "tools/testpilot/verify"]
LIVE = ROOT / "docs/live/progress.json"
STALE_SECONDS = 15 * 60

_cache = {}


def ts(s):
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
    except Exception:
        return None


def first_last(path):
    """(first timestamp, last timestamp, last action) of a transcript, reading only its head and tail."""
    st = path.stat()
    key = (str(path), st.st_mtime, st.st_size)
    if key in _cache:
        return _cache[key]
    first = last = None
    action = ""
    with open(path, "rb") as f:
        head = f.readline()
        try:
            first = ts(json.loads(head).get("timestamp", ""))
        except Exception:
            pass
        f.seek(max(0, st.st_size - 200_000))
        tail = f.read().decode("utf-8", "replace").splitlines()
    for line in reversed(tail):
        try:
            d = json.loads(line)
        except Exception:
            continue
        if last is None:
            last = ts(d.get("timestamp", ""))
        msg = d.get("message") or {}
        if d.get("type") == "assistant" and not action:
            for c in reversed(msg.get("content") or []):
                if isinstance(c, dict) and c.get("type") == "tool_use":
                    inp = c.get("input") or {}
                    detail = inp.get("description") or inp.get("command") or inp.get("file_path") or inp.get("pattern") or ""
                    action = f"{c.get('name')}: {str(detail).strip().splitlines()[0] if str(detail).strip() else ''}"[:140]
                    break
                if isinstance(c, dict) and c.get("type") == "text" and c.get("text", "").strip():
                    action = c["text"].strip().splitlines()[0][:140]
                    break
        if last is not None and action:
            break
    _cache[key] = (first, last, action)
    return _cache[key]


def runs():
    return sorted((p for p in WORKFLOWS.glob("wf_*") if p.is_dir()), key=lambda p: p.stat().st_mtime, reverse=True)


def agents_of(run):
    done = set()
    jp = run / "journal.jsonl"
    if jp.exists():
        for line in jp.read_text(encoding="utf-8", errors="replace").splitlines():
            try:
                d = json.loads(line)
            except Exception:
                continue
            if d.get("type") == "result":
                done.add(d.get("agentId"))
    out = []
    for meta in run.glob("agent-*.meta.json"):
        aid = meta.name[len("agent-"):-len(".meta.json")]
        try:
            m = json.loads(meta.read_text(encoding="utf-8"))
        except Exception:
            continue
        tr = run / f"agent-{aid}.jsonl"
        if not tr.exists():
            continue
        first, last, action = first_last(tr)
        out.append({"id": aid, "label": m.get("description", aid), "phase": m.get("workflowPhase", ""),
                    "start": first, "last": last, "action": action, "done": aid in done})
    return out


def history():
    """Median duration (s) of finished agents per label prefix ('playtest', 'fix', 'mod'...) over recent runs."""
    durs = {}
    for run in runs()[:12]:
        for a in agents_of(run):
            if a["done"] and a["start"] and a["last"]:
                prefix = a["label"].split(":")[0]
                durs.setdefault(prefix, []).append(a["last"] - a["start"])
    return {k: statistics.median(v) for k, v in durs.items() if v}


def latest_shot():
    best = None
    for d in SHOT_DIRS:
        for p in glob.glob(str(d / "**/*.png"), recursive=True):
            m = os.path.getmtime(p)
            if best is None or m > best[0]:
                best = (m, p)
    return best


def status():
    now = time.time()
    rs = runs()
    if not rs:
        return {"error": f"no workflow runs under {WORKFLOWS}"}
    run = rs[0]
    ags = agents_of(run)
    hist = history()
    for a in ags:
        a["elapsed"] = (now - a["start"]) if a["start"] else None
        idle = (now - a["last"]) if a["last"] else None
        a["idle"] = idle
        if a["done"]:
            a["state"] = "done"
            a["elapsed"] = (a["last"] - a["start"]) if a["start"] and a["last"] else None
        elif "session limit" in a["action"] or (idle is not None and idle > 3 * 3600):
            # an earlier attempt that a usage limit (or a stop) killed; a resumed run re-launches it
            a["state"] = "ended"
            a["elapsed"] = (a["last"] - a["start"]) if a["start"] and a["last"] else None
        elif idle is not None and idle > STALE_SECONDS:
            a["state"] = "stalled"
        else:
            a["state"] = "running"
        typical = hist.get(a["label"].split(":")[0])
        a["typical"] = typical
        a["eta"] = max(0.0, typical - a["elapsed"]) if (typical and a["state"] == "running" and a["elapsed"]) else None
    ags.sort(key=lambda a: (a["state"] != "running", a["state"] != "stalled", -(a["start"] or 0)))
    shot = latest_shot()
    live = None
    if LIVE.exists() and now - LIVE.stat().st_mtime < 3 * 3600:
        try:
            live = json.loads(LIVE.read_text(encoding="utf-8"))
        except Exception:
            live = None
    return {
        "run": run.name, "updated": now,
        "counts": {s: sum(1 for a in ags if a["state"] == s) for s in ("running", "stalled", "done", "ended")},
        "agents": ags, "shot": {"mtime": shot[0]} if shot else None, "live": live,
    }


PAGE = r"""<!doctype html><html lang=en><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><!-- phone first --><title>SkyClone Progress</title>
<style>
:root{--bg:#0d1117;--card:#161b22;--line:#262d36;--text:#e6edf3;--dim:#8b949e;--run:#3fb950;--stall:#d29922;--done:#58a6ff;--accent:#f0b429}
*{box-sizing:border-box}html,body{overflow-x:hidden}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 system-ui,Segoe UI,sans-serif}
header{padding:18px 16px 6px}h1{margin:0;font-size:22px}h1 span{color:var(--accent)}
.sub{color:var(--dim);font-size:13px;margin-top:2px}
main{padding:0 16px 40px;max-width:900px;margin:auto}
.pills{display:flex;gap:8px;margin:12px 0 4px;flex-wrap:wrap}.pill{background:var(--card);border:1px solid var(--line);border-radius:999px;padding:6px 12px;font-size:13px}
.pill b{font-size:16px;margin-right:4px}.r b{color:var(--run)}.s b{color:var(--stall)}.d b{color:var(--done)}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:12px 14px;margin:10px 0}
.top{display:flex;justify-content:space-between;align-items:center;gap:8px}.lab{font-weight:600;min-width:0;overflow-wrap:anywhere}
.badge{font-size:12px;border-radius:6px;padding:2px 8px;border:1px solid}
.badge.running{color:var(--run);border-color:var(--run)}.badge.stalled{color:var(--stall);border-color:var(--stall)}.badge.done{color:var(--done);border-color:var(--done)}.badge.ended{color:var(--dim);border-color:var(--line)}
.meta{color:var(--dim);font-size:13px;margin-top:4px}.act{font-family:ui-monospace,Consolas,monospace;font-size:12px;color:#c9d1d9;margin-top:6px;word-break:break-word}
.bar{height:6px;background:#21262d;border-radius:4px;margin-top:8px;overflow:hidden}.bar i{display:block;height:100%;background:var(--run)}
img{width:100%;border-radius:12px;border:1px solid var(--line);margin-top:6px}h2{font-size:15px;color:var(--dim);margin:20px 0 4px;font-weight:600}
details summary{cursor:pointer;color:var(--dim);margin-top:14px}
</style></head><body>
<header><h1>SkyClone <span>live progress</span></h1><div class=sub id=sub>loading...</div></header>
<main><div class=pills id=pills></div><div id=live></div><div id=running></div><h2 id=shoth></h2><div id=shot></div>
<details><summary id=donesum>Finished agents</summary><div id=done></div></details></main>
<script>
const esc=s=>String(s??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const dur=s=>{if(s==null)return'-';s=Math.round(s);const h=Math.floor(s/3600),m=Math.floor(s%3600/60);return h?`${h}h ${m}m`:m?`${m}m`:`${s}s`};
function card(a){const pct=(a.typical&&a.elapsed&&a.state==='running')?Math.min(99,Math.round(100*a.elapsed/a.typical)):null;
 return `<div class=card><div class=top><span class=lab>${esc(a.label)}</span><span class="badge ${a.state}">${a.state}</span></div>
 <div class=meta>${esc(a.phase)} · running ${dur(a.elapsed)}${a.eta!=null?` · about <b>${dur(a.eta)}</b> left (typical ${dur(a.typical)})`:''}${a.state==='stalled'?` · no activity for ${dur(a.idle)}`:''}</div>
 ${a.action&&a.state!=='done'?`<div class=act>now: ${esc(a.action)}</div>`:''}${pct!=null?`<div class=bar><i style="width:${pct}%"></i></div>`:''}</div>`}
async function tick(){try{const r=await fetch('/api/status',{cache:'no-store'});const s=await r.json();
 if(s.error){document.getElementById('sub').textContent=s.error;return}
 document.getElementById('sub').textContent=`run ${s.run} · updated ${new Date(s.updated*1000).toLocaleTimeString()}`;
 const c=s.counts;document.getElementById('pills').innerHTML=`<span class="pill r"><b>${c.running}</b>running</span><span class="pill s"><b>${c.stalled}</b>quiet 15m+</span><span class="pill d"><b>${c.done}</b>done</span>`;
 const L=s.live;document.getElementById('live').innerHTML=L?`<div class=card><div class=top><span class=lab>${esc(L.title||'Checklist')}</span><span class="badge running">${L.done}/${L.total}</span></div><div class=meta>${esc(L.step||'')}</div><div class=bar><i style="width:${Math.round(100*L.done/Math.max(1,L.total))}%"></i></div></div>`:'';
 document.getElementById('running').innerHTML=s.agents.filter(a=>a.state==='running'||a.state==='stalled').map(card).join('')||'<div class=card>Nothing running right now.</div>';
 const d=s.agents.filter(a=>a.state==='done'||a.state==='ended');document.getElementById('donesum').textContent=`Finished agents (${c.done} done, ${c.ended} cut off by limits or stops)`;document.getElementById('done').innerHTML=d.map(card).join('');
 if(s.shot){document.getElementById('shoth').textContent=`Latest test screenshot · ${new Date(s.shot.mtime*1000).toLocaleTimeString()}`;document.getElementById('shot').innerHTML=`<img src="/latest.png?t=${s.shot.mtime}">`}
}catch(e){document.getElementById('sub').textContent='offline: '+e}}
tick();setInterval(tick,5000);
</script></body></html>"""


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        try:
            if path == "/":
                self.send(200, PAGE.encode("utf-8"), "text/html; charset=utf-8")
            elif path == "/api/status":
                self.send(200, json.dumps(status()).encode("utf-8"), "application/json")
            elif path == "/latest.png":
                shot = latest_shot()
                if not shot:
                    self.send(404, b"no screenshot", "text/plain")
                else:
                    self.send(200, Path(shot[1]).read_bytes(), "image/png")
            else:
                self.send(404, b"not found", "text/plain")
        except Exception as e:  # never let one bad read kill the board
            self.send(500, html.escape(repr(e)).encode("utf-8"), "text/plain")


if __name__ == "__main__":
    if "--once" in sys.argv:
        print(json.dumps(status(), indent=1)[:4000])
        sys.exit(0)
    print(f"SkyClone progress board on http://localhost:{PORT}/  (phone: http://yourpc.local:{PORT}/)")
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
