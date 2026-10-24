"""The taste ledger: every candidate, every verdict, kept forever.

WHY THIS IS THE FIRST THING BUILT, before any learning exists:

Preference learning is not blocked on algorithms -- it is blocked on DATA. A LoRA
fine-tune needs roughly 50 liked examples and a lightweight preference scorer needs
about 20. If those verdicts are not recorded from the very first generation, then a
month of picking favourites produces nothing trainable and the work has to start over.
So the ledger ships first and the models that consume it arrive later, reading history
that already exists.

WHAT IS RECORDED, and why each field matters for training later:

* the FULL generation recipe (prompt, negative, seed, model, steps, guidance) -- a
  seed alone is meaningless without the settings that produced it, and reproducing a
  liked image is how you debug a model swap.
* the verdict (loved / picked / passed / auto-rejected) -- graded rather than binary,
  because "I clicked this one" and "I loved this one" are different training signals,
  and auto-rejects are useful negatives that a human never even saw.
* the asset class (pickaxe / armor / wing / ...) -- taste is per-class. A wing that
  looks like a pickaxe is a failure, so the classes must train separately.
* the surviving PNG -- LoRA training needs the pixels, not just the metadata.

The store is JSONL plus a folder of PNGs on purpose: append-only, survives a crash
mid-write, diffable, and readable without the app running.
"""

from __future__ import annotations

import json
import os
import shutil
import threading
from dataclasses import dataclass, asdict, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator
from uuid import uuid4

# Graded, not binary. Ordered worst -> best; the weight is what a trainer multiplies by.
VERDICT_WEIGHTS: dict[str, float] = {
    "auto_rejected": 0.0,   # never shown to a human (blob, sprite sheet)
    "passed": 0.15,         # shown, not chosen
    "picked": 1.0,          # chosen and installed
    "loved": 2.0,           # explicitly hearted - the strongest signal there is
}


@dataclass
class TasteRecord:
    id: str
    created_at: str
    asset_class: str            # "pickaxe", "armor", "wing", ...
    subject: str                # the bare request, e.g. "molten obsidian pickaxe"
    prompt: str
    negative_prompt: str | None
    model: str
    seed: int | None
    steps: int | None
    guidance: float | None
    verdict: str
    sprite_path: str | None     # pixelized PNG - the training target
    source_path: str | None     # full-res generation, kept for retraining at other sizes
    scores: dict[str, float] = field(default_factory=dict)   # clip/taste scores at the time
    notes: str = ""

    @property
    def weight(self) -> float:
        return VERDICT_WEIGHTS.get(self.verdict, 0.0)


class TasteLedger:
    def __init__(self, root: Path | None = None) -> None:
        base = root or Path(
            os.getenv("SHADOW_STORAGE_ROOT")
            or Path(__file__).resolve().parents[1] / ".shadow-assets"
        )
        self.root = base / "taste"
        self.sprites = self.root / "sprites"
        self.sources = self.root / "sources"
        for directory in (self.root, self.sprites, self.sources):
            directory.mkdir(parents=True, exist_ok=True)
        self.path = self.root / "ledger.jsonl"
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ writing

    def record(
        self,
        *,
        asset_class: str,
        subject: str,
        prompt: str,
        negative_prompt: str | None,
        model: str,
        seed: int | None,
        steps: int | None,
        guidance: float | None,
        verdict: str,
        sprite_path: str | Path | None = None,
        source_path: str | Path | None = None,
        scores: dict[str, float] | None = None,
        notes: str = "",
    ) -> TasteRecord:
        """Append one candidate. Images are COPIED in, never referenced in place.

        The generator writes into scratch storage that gets cleaned up; a ledger whose
        training images vanish underneath it is worse than no ledger, because the loss
        is silent and only discovered when training finally runs.
        """
        record_id = uuid4().hex[:12]
        kept_sprite = self._keep(sprite_path, self.sprites, record_id)
        kept_source = self._keep(source_path, self.sources, record_id)

        record = TasteRecord(
            id=record_id,
            created_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
            asset_class=asset_class,
            subject=subject,
            prompt=prompt,
            negative_prompt=negative_prompt,
            model=model,
            seed=seed,
            steps=steps,
            guidance=guidance,
            verdict=verdict,
            sprite_path=str(kept_sprite) if kept_sprite else None,
            source_path=str(kept_source) if kept_source else None,
            scores=scores or {},
            notes=notes,
        )
        with self._lock, self.path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(asdict(record)) + "\n")
        return record

    def set_verdict(self, record_id: str, verdict: str) -> bool:
        """Upgrade a verdict later (passed -> picked -> loved).

        Append-only: a correction is a new line, and readers take the LAST line per id.
        Rewriting history in place would risk truncating the whole ledger on a crash,
        and the progression itself is interesting data.
        """
        current = {r.id: r for r in self.all()}.get(record_id)
        if not current:
            return False
        current.verdict = verdict
        current.created_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
        with self._lock, self.path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(asdict(current)) + "\n")
        return True

    # ------------------------------------------------------------------ reading

    def all(self) -> list[TasteRecord]:
        """Latest state per id, in insertion order."""
        if not self.path.exists():
            return []
        latest: dict[str, TasteRecord] = {}
        for row in self._rows():
            try:
                record = TasteRecord(**row)
            except TypeError:
                continue    # a row from an older schema; skip rather than crash training
            latest[record.id] = record
        return list(latest.values())

    def training_set(self, asset_class: str | None = None, min_weight: float = 1.0) -> list[TasteRecord]:
        """Records good enough to learn FROM, newest first."""
        rows = [
            r for r in self.all()
            if r.weight >= min_weight
            and r.sprite_path
            and Path(r.sprite_path).exists()
            and (asset_class is None or r.asset_class == asset_class)
        ]
        return sorted(rows, key=lambda r: r.created_at, reverse=True)

    def stats(self) -> dict[str, Any]:
        """What is unlocked, and how far off the next capability is."""
        records = self.all()
        by_class: dict[str, dict[str, int]] = {}
        for record in records:
            bucket = by_class.setdefault(record.asset_class, {})
            bucket[record.verdict] = bucket.get(record.verdict, 0) + 1

        liked = len([r for r in records if r.weight >= 1.0])
        return {
            "total": len(records),
            "liked": liked,
            "by_class": by_class,
            "taste_scorer_ready": liked >= 20,
            "lora_ready": liked >= 50,
            "next_milestone": (
                f"{max(0, 20 - liked)} more picks unlock the personal taste scorer"
                if liked < 20
                else f"{max(0, 50 - liked)} more picks unlock LoRA fine-tuning"
                if liked < 50
                else "LoRA fine-tuning available"
            ),
        }

    # ------------------------------------------------------------------ internals

    def _rows(self) -> Iterator[dict]:
        with self.path.open("r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    yield json.loads(line)
                except json.JSONDecodeError:
                    continue    # tolerate a torn final line from an interrupted write

    @staticmethod
    def _keep(source: str | Path | None, target_dir: Path, record_id: str) -> Path | None:
        if not source:
            return None
        src = Path(source)
        if not src.exists():
            return None
        target = target_dir / f"{record_id}{src.suffix or '.png'}"
        shutil.copy2(src, target)
        return target
