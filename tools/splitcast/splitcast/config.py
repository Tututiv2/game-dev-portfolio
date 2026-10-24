"""Config load/save. Keys live here and nowhere else."""

import json
import os

from . import pipeline

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATH = os.path.join(HERE, "config.json")


def _merge(base, over):
    out = dict(base)
    for k, v in (over or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


def load(path=None):
    # Resolved at CALL time, never as a default argument. `path=PATH` binds the
    # value at import, so reassigning config.PATH later (as the test suite does
    # to protect the user's real file) silently had no effect - and a test wrote
    # its fake key straight into a live config, wiping a real stream key.
    path = path or PATH
    cfg = pipeline.default_config()
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as fh:
            cfg = _merge(cfg, json.load(fh))
    return cfg


def save(cfg, path=None):
    path = path or PATH
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(cfg, fh, indent=2)
    os.replace(tmp, path)
    return path


def redact(cfg):
    """A copy safe to print or screenshot. Stream keys are account credentials -
    anything that reaches a log or a capture must go through this first."""
    out = json.loads(json.dumps(cfg))
    for d in out.get("destinations", {}).values():
        if d.get("key"):
            d["key"] = "*" * 8 + d["key"][-4:]
    return out
