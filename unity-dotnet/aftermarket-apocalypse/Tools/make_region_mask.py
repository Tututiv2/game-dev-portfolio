#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Build the §15.11.1 three-channel `_RegionMask` for the Assassin, DERIVED from the
vendor's own MatID maps rather than painted by hand.

    python Tools/make_region_mask.py --dry-run
    python Tools/make_region_mask.py

WHAT A REGION MASK IS FOR
-------------------------
Without it, a theme hue-shifts the whole model and you get "everything including
his face is blue" — §15.11.1 calls this out as the difference between the feature
looking professional and looking cheap. With it, each mask channel carries its own
hue/saturation/value set:

    R = cloth      (the thing you actually dye)
    G = armour     (metal — must stay silver, or the character reads as plastic)
    B = leather / skin / everything else

WHY IT IS DERIVED AND NOT PAINTED
---------------------------------
The IdaFaber pack already ships `T_INQUISITOR_CLOTHES_MatID.png`: a flat
region-id map in ~11 distinct colours, authored by the artist who made the mesh,
in the same UV layout as the albedo. Painting a mask by hand when a better one is
already in the pack would be inventing data that exists.

THE CLASSIFICATION IS MEASURED, NOT TYPED
-----------------------------------------
Which MatID region is metal is not guessed from its colour or its name. For each
region this reads the mean **metallic** out of
`T_INQUISITOR_CLOTHES_MetallicSmoothness.png` (channel R), and the regions fall
into three clusters with enormous gaps between them:

      ~0.005   yellow, red MatID                       (10.7% of the atlas)
      ~0.310   green, cyan, magenta, blue MatID        (46.7%)
      ~0.982   grey, tan, black MatID                  (37.8%)

The split points are found as the two largest gaps in the sorted per-region
metallic values, so they come from the data. If the pack ever changes and the
clusters stop being separable, this **refuses** rather than picking a threshold
that happens to be in the middle of a blob.

WHICH CLUSTER IS "CLOTH"
------------------------
The highest-metallic cluster is armour — that is what metallic MEANS. For the
other two, the one whose albedo carries more **saturation** is the cloth, because
cloth is the thing that gets dyed and metal/leather here is desaturated. Measured
on the Red colourway: the mid cluster contains the two most saturated regions in
the whole atlas (0.709 and 0.592) and the low cluster is 0.009 and 0.041. Again a
measurement, not a naming convention.

WHAT THE OTHER TWO MATERIALS GET
--------------------------------
`HEAD` is a face and a mask — one material, no MatID map in the pack — so it is
solid **B**. `MAT_BODY` is the chainmail underlayer, so it is solid **G**; its own
MatID map is 92.7% one colour, which is not worth a texture. Both are written as
1x1 constants: a mask that says the same thing everywhere does not need pixels.
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

PACK = os.path.join(os.path.dirname(ROOT), "Game creation", "Zombies", "Zombies Unity",
                    "Assets", "IdaFaber", "Textures")
DST = os.path.join(ROOT, "Assets", "_Project", "Art", "Characters", "Inquisitor")
TEX = os.path.join(DST, "Textures")
MAT = os.path.join(DST, "Materials")

SIZE = 1024          # region masks are low-frequency; 4096 would be 16x the bytes for nothing
DRY = "--dry-run" in sys.argv


def log(m):
    print(m, flush=True)


def guid_of(meta):
    if not os.path.isfile(meta):
        return None
    m = re.search(r"^guid: ([a-f0-9]{32})", open(meta, encoding="utf-8", errors="replace").read(), re.M)
    return m.group(1) if m else None


def stable_guid(key):
    import hashlib
    return hashlib.md5(("aftermarket.mask." + key).encode("utf-8")).hexdigest()


def mask_meta(guid):
    """
    A region mask is DATA. sRGBTexture must be 0 or every weight is gamma-decoded
    on upload and the blend is wrong — the same trap the MetallicSmoothness maps
    shipped with. Compression is off: block compression averages across 4x4
    blocks, which bleeds one region's id into its neighbour exactly at the
    boundaries the mask exists to define.
    """
    return (
        "fileFormatVersion: 2\n"
        "guid: %s\n"
        "TextureImporter:\n"
        "  internalIDToNameTable: []\n"
        "  externalObjects: {}\n"
        "  serializedVersion: 13\n"
        "  mipmaps:\n"
        "    mipMapMode: 0\n"
        "    enableMipMap: 1\n"
        "    sRGBTexture: 0\n"
        "    linearTexture: 1\n"
        "    fadeOut: 0\n"
        "    borderMipMap: 0\n"
        "    mipMapsPreserveCoverage: 0\n"
        "    alphaTestReferenceValue: 0.5\n"
        "    mipMapFadeDistanceStart: 1\n"
        "    mipMapFadeDistanceEnd: 3\n"
        "  bumpmap:\n"
        "    convertToNormalMap: 0\n"
        "    externalNormalMap: 0\n"
        "    heightScale: 0.25\n"
        "    normalMapFilter: 0\n"
        "    flipGreenChannel: 0\n"
        "  isReadable: 0\n"
        "  streamingMipmaps: 0\n"
        "  streamingMipmapsPriority: 0\n"
        "  vTOnly: 0\n"
        "  ignoreMipmapLimit: 0\n"
        "  grayScaleToAlpha: 0\n"
        "  generateCubemap: 6\n"
        "  cubemapConvolution: 0\n"
        "  seamlessCubemap: 0\n"
        "  textureFormat: 1\n"
        "  maxTextureSize: %d\n"
        "  textureSettings:\n"
        "    serializedVersion: 2\n"
        "    filterMode: 1\n"
        "    aniso: 1\n"
        "    mipBias: 0\n"
        "    wrapU: 1\n"
        "    wrapV: 1\n"
        "    wrapW: 1\n"
        "  nPOTScale: 1\n"
        "  lightmap: 0\n"
        "  compressionQuality: 100\n"
        "  spriteMode: 0\n"
        "  spriteExtrude: 1\n"
        "  spriteMeshType: 1\n"
        "  alignment: 0\n"
        "  spritePivot: {x: 0.5, y: 0.5}\n"
        "  spritePixelsToUnits: 100\n"
        "  spriteBorder: {x: 0, y: 0, z: 0, w: 0}\n"
        "  spriteGenerateFallbackPhysicsShape: 1\n"
        "  alphaUsage: 0\n"
        "  alphaIsTransparency: 0\n"
        "  spriteTessellationDetail: -1\n"
        "  textureType: 0\n"
        "  textureShape: 1\n"
        "  singleChannelComponent: 0\n"
        "  flipbookRows: 1\n"
        "  flipbookColumns: 1\n"
        "  maxTextureSizeSet: 0\n"
        "  compressionQualitySet: 0\n"
        "  textureFormatSet: 0\n"
        "  ignorePngGamma: 0\n"
        "  applyGammaDecoding: 0\n"
        "  swizzle: 50462976\n"
        "  cookieLightType: 0\n"
        "  platformSettings:\n"
        "  - serializedVersion: 4\n"
        "    buildTarget: DefaultTexturePlatform\n"
        "    maxTextureSize: %d\n"
        "    resizeAlgorithm: 0\n"
        "    textureFormat: -1\n"
        "    textureCompression: 0\n"
        "    compressionQuality: 100\n"
        "    crunchedCompression: 0\n"
        "    allowsAlphaSplitting: 0\n"
        "    overridden: 1\n"
        "    ignorePlatformSupport: 0\n"
        "    androidETC2FallbackOverride: 0\n"
        "    forceMaximumCompressionQuality_BC6H_BC7: 0\n"
        "  spriteSheet:\n"
        "    serializedVersion: 2\n"
        "    sprites: []\n"
        "    outline: []\n"
        "    customData: \n"
        "    physicsShape: []\n"
        "    bones: []\n"
        "    spriteID: \n"
        "    internalID: 0\n"
        "    vertices: []\n"
        "    indices: \n"
        "    edges: []\n"
        "    weights: []\n"
        "    secondaryTextures: []\n"
        "    spriteCustomMetadata:\n"
        "      entries: []\n"
        "    nameFileIdTable: {}\n"
        "  mipmapLimitGroupName: \n"
        "  pSDRemoveMatte: 0\n"
        "  userData: \n"
        "  assetBundleName: \n"
        "  assetBundleVariant: \n" % (guid, SIZE, SIZE))


def build_clothes_mask():
    from PIL import Image
    import numpy as np
    Image.MAX_IMAGE_PIXELS = None

    matid_path = os.path.join(PACK, "T_INQUISITOR_CLOTHES_MatID.png")
    ms_path = os.path.join(TEX, "T_INQUISITOR_CLOTHES_MetallicSmoothness.png")
    alb_path = os.path.join(TEX, "T_INQUISITOR_CLOTHES_BaseColor_04.png")
    for p in (matid_path, ms_path, alb_path):
        if not os.path.isfile(p):
            raise SystemExit("Missing " + p + " — run Tools/import_inquisitor.py first.")

    mid = np.asarray(Image.open(matid_path).convert("RGB").resize((SIZE, SIZE), Image.NEAREST)).astype(int)
    ms = np.asarray(Image.open(ms_path).convert("RGBA").resize((SIZE, SIZE), Image.BILINEAR)).astype(float) / 255.0
    alb = np.asarray(Image.open(alb_path).convert("RGB").resize((SIZE, SIZE), Image.BILINEAR)).astype(float) / 255.0

    flat = mid.reshape(-1, 3)
    key = (flat[:, 0] // 32) * 10000 + (flat[:, 1] // 32) * 100 + (flat[:, 2] // 32)
    uq, inv, cnt = np.unique(key, return_inverse=True, return_counts=True)

    met_flat = ms.reshape(-1, 4)[:, 0]
    rgb = alb.reshape(-1, 3)
    mx = rgb.max(axis=1)
    mn = rgb.min(axis=1)
    sat_flat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0.0)

    regions = []
    for i in range(len(uq)):
        m = inv == i
        if cnt[i] / float(len(flat)) < 0.002:
            continue      # dust: antialiased borders between real regions
        regions.append({
            "idx": i,
            "area": cnt[i] / float(len(flat)),
            "metallic": float(met_flat[m].mean()),
            "sat": float(sat_flat[m].mean()),
            "colour": tuple(int(v) for v in flat[m][:64].mean(axis=0).round()),
        })
    if len(regions) < 3:
        raise SystemExit("Only %d usable MatID regions — cannot form three groups." % len(regions))

    # --- split by the two largest gaps in sorted metallic -------------------
    regions.sort(key=lambda r: r["metallic"])
    gaps = [(regions[i + 1]["metallic"] - regions[i]["metallic"], i) for i in range(len(regions) - 1)]
    gaps.sort(reverse=True)
    if gaps[0][0] < 0.15 or gaps[1][0] < 0.15:
        raise SystemExit(
            "The MatID regions' metallic values do not separate into three clusters "
            "(largest gaps %.3f and %.3f). Refusing to pick a threshold in the middle of a "
            "blob — that would be a typed constant wearing a measurement's clothes."
            % (gaps[0][0], gaps[1][0]))
    cuts = sorted([gaps[0][1], gaps[1][1]])
    groups = [regions[:cuts[0] + 1], regions[cuts[0] + 1:cuts[1] + 1], regions[cuts[1] + 1:]]

    # highest metallic == armour, by definition of metallic
    metal = groups[2]
    others = [groups[0], groups[1]]
    # of the rest, the one carrying more albedo saturation is the cloth
    others.sort(key=lambda g: sum(r["sat"] * r["area"] for r in g) / max(sum(r["area"] for r in g), 1e-6))
    other, cloth = others[0], others[1]

    log("  MatID regions, grouped by MEASURED metallic:")
    for name, grp, ch in (("cloth  -> R", cloth, 0), ("armour -> G", metal, 1), ("other  -> B", other, 2)):
        area = sum(r["area"] for r in grp)
        met = sum(r["metallic"] * r["area"] for r in grp) / max(area, 1e-6)
        sat = sum(r["sat"] * r["area"] for r in grp) / max(area, 1e-6)
        log("    %-12s %5.1f%%  metallic %.3f  albedo sat %.3f   ids %s"
            % (name, 100 * area, met, sat, ", ".join(str(r["colour"]) for r in grp)))

    out = np.zeros((SIZE * SIZE, 3), dtype=np.uint8)
    for grp, ch in ((cloth, 0), (metal, 1), (other, 2)):
        for r in grp:
            out[inv == r["idx"], ch] = 255

    # Any texel that landed in no group (the sub-0.2% dust) would be (0,0,0),
    # which the shader normalises to a divide-by-nothing. Give it to cloth: an
    # unclassified border texel between two regions is visually cloth-adjacent,
    # and more importantly it must not be black.
    empty = out.sum(axis=1) == 0
    out[empty, 0] = 255
    log("    unclassified texels handed to R: %.3f%%" % (100.0 * empty.mean()))

    return Image.fromarray(out.reshape(SIZE, SIZE, 3), "RGB")


def constant_mask(channel):
    from PIL import Image
    px = [0, 0, 0]
    px[channel] = 255
    return Image.new("RGB", (4, 4), tuple(px))


def bind(mat_name, tex_file, tex_guid):
    """Put the mask into `_RegionMask` on one material. Returns a log line."""
    p = os.path.join(MAT, mat_name + ".mat")
    if not os.path.isfile(p):
        return "  MISSING material " + p
    body = open(p, encoding="utf-8").read()
    entry = ("    - _RegionMask:\n"
             "        m_Texture: {fileID: 2800000, guid: %s, type: 3}\n"
             "        m_Scale: {x: 1, y: 1}\n"
             "        m_Offset: {x: 0, y: 0}\n" % tex_guid)
    if "- _RegionMask:" in body:
        body = re.sub(r"    - _RegionMask:\n        m_Texture: \{[^}]*\}\n"
                      r"        m_Scale: \{[^}]*\}\n        m_Offset: \{[^}]*\}\n",
                      entry, body, count=1)
    else:
        # Keep m_TexEnvs alphabetical, as Unity writes it.
        body = body.replace("    - _SpecGlossMap:", entry + "    - _SpecGlossMap:", 1)
    if not DRY:
        with open(p, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(body)
    return "  %-30s _RegionMask = %s" % (mat_name, tex_file)


def main():
    if not os.path.isdir(MAT):
        raise SystemExit("No Inquisitor materials at " + MAT)

    log("Deriving the region mask from the pack's own MatID map.")
    log("")
    clothes = build_clothes_mask()
    log("")

    plan = [
        ("T_Inquisitor_Clothes_RegionMask.png", clothes,
         ["Inquisitor_Clothes_Black", "Inquisitor_Clothes_Red"]),
        ("T_Inquisitor_Body_RegionMask.png", constant_mask(1), ["Inquisitor_Body"]),
        ("T_Inquisitor_Head_RegionMask.png", constant_mask(2), ["Inquisitor_Head"]),
    ]

    if DRY:
        for fn, img, mats in plan:
            log("  would write %-42s %sx%s -> %s" % (fn, img.size[0], img.size[1], ", ".join(mats)))
        log("")
        log("--dry-run: nothing written.")
        return

    os.makedirs(TEX, exist_ok=True)
    total = 0
    for fn, img, mats in plan:
        path = os.path.join(TEX, fn)
        img.save(path, optimize=True)
        g = guid_of(path + ".meta") or stable_guid(fn)
        with open(path + ".meta", "w", encoding="utf-8", newline="\n") as fh:
            fh.write(mask_meta(g))
        total += os.path.getsize(path)
        log("  wrote %-42s %6.1f KB" % (fn, os.path.getsize(path) / 1024.0))
        for m in mats:
            log(bind(m, fn, g))
    log("")
    log("Added %.1f KB." % (total / 1024.0))

    # ---- verify the end state ------------------------------------------
    bad = []
    for fn, img, mats in plan:
        g = guid_of(os.path.join(TEX, fn + ".meta"))
        meta = open(os.path.join(TEX, fn + ".meta"), encoding="utf-8").read()
        if "sRGBTexture: 0" not in meta:
            bad.append(fn + " is not linear — a region mask is data, not colour")
        if "textureCompression: 0" not in meta:
            bad.append(fn + " is compressed — block compression bleeds region ids across "
                            "exactly the boundaries this texture exists to define")
        for m in mats:
            body = open(os.path.join(MAT, m + ".mat"), encoding="utf-8").read()
            got = re.search(r"- _RegionMask:\n        m_Texture: \{fileID: 2800000, guid: ([a-f0-9]{32})", body)
            if not got:
                bad.append(m + " has no _RegionMask bound")
            elif got.group(1) != g:
                bad.append(m + " binds the wrong mask")
    if bad:
        for b in bad:
            log("FAIL " + b)
        raise SystemExit("Verification failed.")
    log("verified: every mask is linear and uncompressed, and every material binds its own.")


if __name__ == "__main__":
    main()
