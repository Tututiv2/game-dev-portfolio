"""Cost meter + the shared turn budget (CONTRACT §6 `max_turns_per_hour`, `budget_usd_stop`).

    from show.cost import METER, TURN_BUDGET
    METER.record("Kaito", reply, backend="claude_code")
    METER.snapshot()        # {"usd", "turns", "per_hour", "cache_hit_ratio", "agents": {...}}
    TURN_BUDGET.try_acquire(max_per_hour)   # shared across ALL agents (subscription rate-limit guard)

Prices are per million tokens. Verified 2026-10-03 two ways:
  * the claude-api skill's model table: claude-opus-5-5 = $4 in / $20 out / $0.20 cache read;
    cache writes are 1.25x input for the 5-minute TTL and 2x for the 1-hour TTL.
  * a real `claude -p` call: total_cost_usd for 493 in + 46 out was exactly 493*4e-6 + 46*20e-6,
    and a 576-token cache write cost exactly 576*8e-6 -> Claude Code writes the 1-HOUR cache ($8).
The ApiBrain uses the default 5-minute TTL ($5). For the claude_code backend the meter takes the
CLI's own `total_cost_usd` (list-price equivalent; the subscription does not actually bill it).
"""
from __future__ import annotations

import collections
import threading
import time
from typing import Any

PRICES: dict[str, dict[str, float]] = {
    "claude-opus-5-5":   {"in": 4.0, "out": 20.0, "cache_read": 0.20, "cache_write_5m": 5.0, "cache_write_1h": 8.0},
    "claude-opus-5":     {"in": 5.0, "out": 25.0, "cache_read": 0.50, "cache_write_5m": 6.25, "cache_write_1h": 10.0},
    "claude-opus-4-8":   {"in": 5.0, "out": 25.0, "cache_read": 0.50, "cache_write_5m": 6.25, "cache_write_1h": 10.0},
    "claude-sonnet-5-5": {"in": 2.0, "out": 10.0, "cache_read": 0.20, "cache_write_5m": 2.5, "cache_write_1h": 4.0},
    "claude-haiku-4-5":  {"in": 1.0, "out": 5.0, "cache_read": 0.10, "cache_write_5m": 1.25, "cache_write_1h": 2.0},
}
DEFAULT_PRICE = PRICES["claude-opus-5-5"]


def _g(u: Any, key: str) -> float:
    if u is None:
        return 0.0
    v = u.get(key) if isinstance(u, dict) else getattr(u, key, None)
    try:
        return float(v or 0)
    except (TypeError, ValueError):
        return 0.0


def usage_cost(model: str, usage: Any, ttl: str = "5m") -> float:
    """USD for one response's usage (dict or SDK object)."""
    p = PRICES.get(model, DEFAULT_PRICE)
    cin, cout = _g(usage, "input_tokens"), _g(usage, "output_tokens")
    cread, cwrite = _g(usage, "cache_read_input_tokens"), _g(usage, "cache_creation_input_tokens")
    # split the write by TTL when the breakdown is present
    cc = usage.get("cache_creation") if isinstance(usage, dict) else getattr(usage, "cache_creation", None)
    w1h = _g(cc, "ephemeral_1h_input_tokens") if cc is not None else 0.0
    w5m = _g(cc, "ephemeral_5m_input_tokens") if cc is not None else 0.0
    if w1h + w5m == 0:
        if ttl == "1h":
            w1h = cwrite
        else:
            w5m = cwrite
    return (cin * p["in"] + cout * p["out"] + cread * p["cache_read"]
            + w5m * p["cache_write_5m"] + w1h * p["cache_write_1h"]) / 1_000_000


class TurnBudget:
    """Sliding one-hour window of turn starts, shared by every agent."""

    def __init__(self, clock=time.monotonic) -> None:
        self._lock = threading.Lock()
        self._t: collections.deque[float] = collections.deque()
        self._clock = clock

    def _trim(self, now: float) -> None:
        while self._t and now - self._t[0] >= 3600.0:
            self._t.popleft()

    def try_acquire(self, max_per_hour: int) -> bool:
        with self._lock:
            now = self._clock()
            self._trim(now)
            if max_per_hour <= 0 or len(self._t) >= max_per_hour:
                return False
            self._t.append(now)
            return True

    def used(self) -> int:
        with self._lock:
            self._trim(self._clock())
            return len(self._t)

    def wait_s(self, max_per_hour: int) -> float:
        """Seconds until a slot frees up (0 if one is free now)."""
        with self._lock:
            now = self._clock()
            self._trim(now)
            if len(self._t) < max(1, max_per_hour):
                return 0.0
            return max(0.0, 3600.0 - (now - self._t[0]))


class CostMeter:
    def __init__(self, clock=time.monotonic) -> None:
        self._lock = threading.Lock()
        self._clock = clock
        self._t0 = clock()
        self._agents: dict[str, dict[str, float]] = {}
        self._stop_fired = False
        self._reporter: threading.Thread | None = None
        self._reporter_stop = threading.Event()

    def reset(self) -> None:
        with self._lock:
            self._t0 = self._clock()
            self._agents.clear()
            self._stop_fired = False

    def record(self, who: str, reply: Any) -> None:
        """Add one Reply (from brain.py) to `who`'s tally. Errors still count as a turn attempt."""
        u = getattr(reply, "usage", None) or {}
        with self._lock:
            a = self._agents.setdefault(who, {"usd": 0.0, "turns": 0, "errors": 0, "in": 0.0, "out": 0.0,
                                              "cache_read": 0.0, "cache_write": 0.0, "latency_s": 0.0})
            a["turns"] += 1
            if getattr(reply, "error", None):
                a["errors"] += 1
            a["usd"] += float(getattr(reply, "cost_usd", None) or 0.0)
            a["in"] += _g(u, "input_tokens")
            a["out"] += _g(u, "output_tokens")
            a["cache_read"] += _g(u, "cache_read_input_tokens")
            a["cache_write"] += _g(u, "cache_creation_input_tokens")
            a["latency_s"] += float(getattr(reply, "latency_s", 0.0) or 0.0)

    def total_usd(self) -> float:
        with self._lock:
            return sum(a["usd"] for a in self._agents.values())

    def snapshot(self) -> dict:
        with self._lock:
            agents = {k: dict(v) for k, v in self._agents.items()}
            hours = max((self._clock() - self._t0) / 3600.0, 1e-9)
        tot = {k: sum(a[k] for a in agents.values()) for k in
               ("usd", "turns", "errors", "in", "out", "cache_read", "cache_write", "latency_s")}
        prompt = tot["in"] + tot["cache_read"] + tot["cache_write"]
        for a in agents.values():
            p = a["in"] + a["cache_read"] + a["cache_write"]
            a["cache_hit_ratio"] = round(a["cache_read"] / p, 3) if p else 0.0
            a["avg_latency_s"] = round(a["latency_s"] / a["turns"], 2) if a["turns"] else 0.0
            a["usd"] = round(a["usd"], 4)
        return {
            "usd": round(tot["usd"], 4),
            "turns": int(tot["turns"]),
            "errors": int(tot["errors"]),
            "tokens": {"in": int(tot["in"]), "out": int(tot["out"]),
                       "cache_read": int(tot["cache_read"]), "cache_write": int(tot["cache_write"])},
            "per_hour": round(tot["usd"] / hours, 2) if hours > 1 / 3600 else 0.0,
            "turns_per_hour": round(tot["turns"] / hours, 1) if hours > 1 / 3600 else 0.0,
            "cache_hit_ratio": round(tot["cache_read"] / prompt, 3) if prompt else 0.0,
            "agents": agents,
        }

    def check_budget(self, dials, bus=None) -> bool:
        """True when the api backend has hit `budget_usd_stop`. On the first hit: pause everyone
        (dials `paused`=True) and emit a system event. The claude_code backend is never stopped by
        dollars (the subscription has no per-token bill) - `max_turns_per_hour` guards it instead."""
        if dials["backend"] != "api":
            return False
        limit = float(dials["budget_usd_stop"])
        usd = self.total_usd()
        if usd < limit:
            return False
        with self._lock:
            first = not self._stop_fired
            self._stop_fired = True
        if first:
            try:
                dials.set("paused", True)
            except Exception:
                pass
            if bus is not None:
                bus.emit("system", "budget_stop", drama=0,
                         text=f"API budget stop: ${usd:.2f} >= ${limit:.2f} - all agents paused",
                         data={"usd": round(usd, 4), "limit": limit})
        return True

    def start_reporter(self, bus, interval_s: float = 30.0) -> None:
        """Emit a `cost` event every interval_s (CONTRACT §3)."""
        if self._reporter and self._reporter.is_alive():
            return
        self._reporter_stop.clear()

        def loop() -> None:
            while not self._reporter_stop.wait(interval_s):
                s = self.snapshot()
                bus.emit("agent", "cost", text=f"${s['usd']:.2f} · {s['turns']} turns · ${s['per_hour']:.2f}/h",
                         data=s)
        self._reporter = threading.Thread(target=loop, daemon=True, name="CostReporter")
        self._reporter.start()

    def stop_reporter(self) -> None:
        self._reporter_stop.set()


METER = CostMeter()
TURN_BUDGET = TurnBudget()
