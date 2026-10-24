"""Rank candidates by how well they match what was actually asked for.

THE PROBLEM THIS SOLVES: the structural filter in pixelart.score_sprite() catches
BROKEN output -- solid blobs, sprite sheets, empty tiles. It cannot catch output that
is a perfectly well-formed sprite of the wrong thing. Asked for a pickaxe, a clean
drawing of a drill passes every structural check there is.

CLIP scores image-text similarity, so it can answer "which of these looks most like a
pickaxe" directly, with no training and no examples. Generate more than needed, rank,
show the best. The drill loses to the pickaxe and is never seen.

TWO SCORERS, COMBINED:

* **Prompt match (CLIP, available immediately).** Similarity between the sprite and the
  requested subject, minus similarity to a set of confusable decoys. Subtracting decoys
  matters: raw similarity to "a pickaxe" is high for anything tool-shaped, whereas the
  MARGIN over "a drill" and "a hammer" is what actually separates them.
* **Personal taste (needs history).** Once the ledger holds enough liked sprites, the
  mean CLIP embedding of the liked set becomes a taste vector, and similarity to it is
  a personal-preference score. This is a nearest-centroid classifier -- deliberately
  the simplest thing that works, because it retrains in milliseconds on every new pick
  and needs ~20 examples rather than the ~50 a LoRA wants.

Both operate on CLIP IMAGE EMBEDDINGS of the final 16x16 sprite upscaled, not the
512px source, so they judge the thing the player will actually see.
"""

from __future__ import annotations

import os
import threading
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image

DEFAULT_CLIP = "openai/clip-vit-base-patch32"

# A hardcoded decoy list per class cannot support "generate anything" -- ask for a
# katana and there is no entry, so the candidate is scored against nothing and any
# sword-shaped blob wins. Instead the decoys are DERIVED: embed the subject, embed this
# vocabulary, and take the nearest neighbours that are not the subject itself. Those are
# by definition the things most easily confused with what was asked for, which is
# exactly what the margin needs to be measured against.
DECOY_VOCABULARY: list[str] = [
    # tools
    "a pickaxe", "a drill", "a hammer", "a sledgehammer", "an axe", "a hatchet",
    "a shovel", "a spade", "a wrench", "a screwdriver", "a chisel", "a saw",
    "a fishing rod", "a scythe", "a rake", "a crowbar",
    # blades
    "a sword", "a katana", "a longsword", "a dagger", "a knife", "a machete",
    "a rapier", "a scimitar", "a greatsword", "a cleaver", "a spear", "a halberd",
    "a trident", "a glaive",
    # ranged / magic
    "a bow", "a crossbow", "an arrow", "a wand", "a staff", "a scepter", "a gun",
    "a grenade", "a torch", "a lantern",
    # wearables
    "a helmet", "a chestplate", "a shirt", "a robe", "a cloak", "a cape",
    "a pair of boots", "a pair of gloves", "a shield", "a backpack", "a crown",
    "a mask", "a pair of wings", "a feather",
    # trinkets / misc
    "a key", "a coin", "a gem", "a crystal", "a ring", "an amulet", "a potion bottle",
    "a book", "a scroll", "a chest", "an egg", "a skull", "a heart", "a star",
    "a flower", "a leaf", "a rock", "an ingot", "a bone",
]

# Kept only as a hint: when a class is known, these are FORCED into the decoy set even
# if the vocabulary search would not have surfaced them. Everything still works for a
# class that is not listed here.
DECOY_HINTS: dict[str, list[str]] = {
    "armor": ["a shirt", "a robe", "a backpack"],
    "wing": ["a leaf", "a cape", "a feather"],
}

_vocab_cache: "np.ndarray | None" = None


def auto_decoys(subject: str, *, asset_class: str | None = None, count: int = 6) -> list[str]:
    """The vocabulary entries most confusable with `subject`, excluding the subject.

    This is what makes the forge subject-agnostic: "obsidian katana" automatically gets
    scored against sword/longsword/rapier/machete rather than against nothing.
    """
    global _vocab_cache
    if _vocab_cache is None:
        _vocab_cache = embed_texts(DECOY_VOCABULARY)

    subject_vector = embed_texts([f"a {subject}"])[0]
    similarity = _vocab_cache @ subject_vector

    # Drop entries that ARE the subject -- scoring a katana against "a katana" would
    # subtract the very signal being measured and collapse every score toward zero.
    subject_words = {w.strip(".,").lower() for w in subject.split()}
    picked: list[str] = []
    for index in np.argsort(-similarity):
        phrase = DECOY_VOCABULARY[index]
        noun = phrase.split()[-1].lower()
        if noun in subject_words:
            continue
        picked.append(phrase)
        if len(picked) >= count:
            break

    for hint in DECOY_HINTS.get(asset_class or "", []):
        if hint not in picked and hint.split()[-1].lower() not in subject_words:
            picked.append(hint)
    return picked

_model = None
_processor = None
_lock = threading.Lock()


@dataclass
class Ranked:
    index: int
    prompt_match: float      # margin over decoys; higher is more on-subject
    taste: float             # similarity to previously liked sprites (0 when no history)
    total: float
    embedding: np.ndarray


def _load():
    global _model, _processor
    if _model is not None:
        return _model, _processor
    with _lock:
        if _model is not None:
            return _model, _processor
        import torch
        from transformers import CLIPModel, CLIPProcessor

        name = os.getenv("CLIP_MODEL") or DEFAULT_CLIP
        model = CLIPModel.from_pretrained(name)
        model.eval()
        if torch.cuda.is_available():
            model = model.to("cuda")
        _model = model
        _processor = CLIPProcessor.from_pretrained(name)
        return _model, _processor


def embed_images(images: list[Image.Image]) -> np.ndarray:
    import torch

    model, processor = _load()
    # CLIP was trained on photographs at 224px. A 16x16 sprite must be upscaled with
    # NEAREST -- bilinear would blur it back into the soft gradients the pixel pipeline
    # deliberately removed, and CLIP would then score a different image than the player sees.
    prepared = [im.convert("RGB").resize((224, 224), Image.Resampling.NEAREST) for im in images]
    inputs = processor(images=prepared, return_tensors="pt")
    if torch.cuda.is_available():
        inputs = {k: v.to("cuda") for k, v in inputs.items()}
    with torch.no_grad():
        features = _as_tensor(model.get_image_features(**inputs))
    features = features / features.norm(dim=-1, keepdim=True)
    return features.cpu().numpy()


def embed_texts(texts: list[str]) -> np.ndarray:
    import torch

    model, processor = _load()
    inputs = processor(text=texts, return_tensors="pt", padding=True, truncation=True)
    if torch.cuda.is_available():
        inputs = {k: v.to("cuda") for k, v in inputs.items()}
    with torch.no_grad():
        features = _as_tensor(model.get_text_features(**inputs))
    features = features / features.norm(dim=-1, keepdim=True)
    return features.cpu().numpy()


def _as_tensor(result):
    """transformers 5.x returns an output object here; 4.x returned a bare tensor.

    Supporting both keeps this working across the version the machine happens to have
    installed, instead of pinning transformers for one attribute access.
    """
    for attribute in ("image_embeds", "text_embeds", "pooler_output", "last_hidden_state"):
        value = getattr(result, attribute, None)
        if value is not None:
            return value if value.dim() == 2 else value.mean(dim=1)
    return result


def rank(
    images: list[Image.Image],
    subject: str,
    *,
    asset_class: str | None = None,
    taste_vector: np.ndarray | None = None,
    taste_weight: float = 0.35,
) -> list[Ranked]:
    """Best first. `taste_vector` is optional; without history this is pure prompt match."""
    if not images:
        return []

    image_vectors = embed_images(images)

    decoys = auto_decoys(subject, asset_class=asset_class)
    text_vectors = embed_texts([f"a {subject}", *decoys])
    subject_vector, decoy_vectors = text_vectors[0], text_vectors[1:]

    subject_similarity = image_vectors @ subject_vector
    if len(decoy_vectors):
        # Max, not mean: losing badly to ONE confusable thing is what condemns a
        # candidate. Averaging lets a strong drill score hide behind weak decoys.
        decoy_similarity = (image_vectors @ decoy_vectors.T).max(axis=1)
    else:
        decoy_similarity = np.zeros(len(images))
    prompt_match = subject_similarity - decoy_similarity

    if taste_vector is not None and np.linalg.norm(taste_vector) > 0:
        unit = taste_vector / np.linalg.norm(taste_vector)
        taste = image_vectors @ unit
    else:
        taste = np.zeros(len(images))

    ranked = [
        Ranked(
            index=i,
            prompt_match=float(prompt_match[i]),
            taste=float(taste[i]),
            total=float(prompt_match[i] + taste_weight * taste[i]),
            embedding=image_vectors[i],
        )
        for i in range(len(images))
    ]
    ranked.sort(key=lambda r: r.total, reverse=True)
    return ranked


def taste_vector_from(sprite_paths: list[str | Path], weights: list[float] | None = None) -> np.ndarray | None:
    """Weighted mean embedding of previously liked sprites = 'what this person likes'.

    Nearest-centroid on purpose. With ~20 examples anything heavier (a trained head, a
    fine-tune) overfits to whatever the last few picks happened to be, and this version
    retrains instantly on every new heart instead of needing a training run.
    """
    images, kept_weights = [], []
    for i, path in enumerate(sprite_paths):
        p = Path(path)
        if not p.exists():
            continue
        images.append(Image.open(p))
        kept_weights.append(weights[i] if weights and i < len(weights) else 1.0)

    if not images:
        return None

    vectors = embed_images(images)
    weight_array = np.asarray(kept_weights, dtype=np.float32).reshape(-1, 1)
    centroid = (vectors * weight_array).sum(axis=0) / max(weight_array.sum(), 1e-6)
    norm = np.linalg.norm(centroid)
    return centroid / norm if norm > 0 else None
