#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Carry the Assassin (IdaFaber SK_INQUISITOR_M_01) across from the old project, as
URP, with its materials bound to the slots URP/Lit ACTUALLY SAMPLES.

    python Tools/import_inquisitor.py --dry-run    # report, copy nothing
    python Tools/import_inquisitor.py              # do it

WHY IT RENDERS WHITE TODAY
--------------------------
`MAT_INQUISITOR_CLOTHES_06.mat` is the "Black Variant". Its `_BaseMap`,
`_BaseColorMap` and `_MainTex` all point at **T_INQUISITOR_CLOTHES_BaseColor_05**
-- the WHITE texture -- and only `_BaseColor_Map` (with the extra underscore, a
property of the vendor's own Shader Graph) points at BaseColor_06. URP/Lit reads
`_BaseMap`. So you get the white one, from a material named Black, with no error.

Checked across the whole set rather than assumed: **only 02 (Blue) and 06 (Black)
are mismatched.** 01, 03, 04 and 05 agree with themselves. So "every colourway has
this shape" is not quite right -- but the two that matter here do, and a rule that
is true of two thirds of a set is exactly the kind of thing that gets applied
blindly to the other third. This script therefore *compares* the two slots per
material and logs when they disagree, rather than always preferring one.

TWO MORE OF THE SAME CLASS, FOUND ON THE WAY IN
-----------------------------------------------
`T_INQUISITOR_CLOTHES_MetallicSmoothness.png` and
`T_INQUISITOR_CLOTHES_MaskMap.png` ship with **`sRGBTexture: 1`**. They are data
maps -- metallic, smoothness, ambient occlusion -- not colour. Sampled as sRGB
they get gamma-decoded on the way in, so every metallic and smoothness value
arrives wrong (and darker), for a surface that still renders and still looks
plausible. The copies here are forced to linear, and the script prints every flag
it changed rather than doing it silently.

Normals already ship `textureType: 1`; that is asserted rather than assumed,
because a normal map imported as a colour texture is this same bug one more time.

WHAT IT COPIES, AND WHY IT IS NOT THE WHOLE PACK
------------------------------------------------
The IdaFaber pack is 2,905 MB and this repo has no remote to prune from, so what
lands here is permanent. Two filters:

1. **The mesh has exactly THREE material slots** -- HEAD, MAT_BODY, MAT_CLOTHES.
   It is hooded, masked and gloved, so there is no hair, no eyes and no lashes on
   this model at all. Read out of the FBX, not assumed.
2. **Only the textures URP/Lit can sample.** The vendor materials also bind dirt
   masks, blood masks, subsurface, MatID, pattern and per-region aegyosal normals
   -- 496 MB of maps for slots URP has no code for. Copying "every bound texture"
   would be technically true and 3.4x the size for zero pixels on screen.

Run with --dry-run first; it prints the megabyte total before anything is copied.
"""

import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# The pack ships a whole family on ONE skeleton, so this script is not specific
# to M_01 despite its filename. --mesh/--name select any of them:
#   python Tools/import_inquisitor.py --mesh Meshes/Boy/SK_INQUISITOR_M_03.fbx --name Operative
MESH = "Meshes/Boy/SK_INQUISITOR_M_01.fbx"
NAME = "Inquisitor"
PACK = None   # override the source pack root; Miyoko lives in a different one
for _i, _a in enumerate(sys.argv):
    if _a == "--mesh" and _i + 1 < len(sys.argv):
        MESH = sys.argv[_i + 1]
    if _a == "--name" and _i + 1 < len(sys.argv):
        NAME = sys.argv[_i + 1]
    if _a == "--pack" and _i + 1 < len(sys.argv):
        PACK = sys.argv[_i + 1]

OLD_PROJECT = os.path.join(os.path.dirname(ROOT),
                           "Game creation", "Zombies", "Zombies Unity", "Assets")
SRC = os.path.join(OLD_PROJECT, PACK if PACK else "IdaFaber")

DST = os.path.join(ROOT, "Assets", "_Project", "Art", "Characters", NAME)

URP_LIT_GUID = "933532a4fcc9baf4fa0491de14d08ed7"

DRY = "--dry-run" in sys.argv

# Which clothes colourways to bring. The pack has six albedos at ~19 MB each; the
# theme system (spec 15.11) exists precisely so we do not need all of them.
#
#   06 "Black"  -- the look actually asked for.
#   04 "Red"    -- the RECOLOUR SOURCE. An HSV hue shift cannot invent saturation
#                  (S * mul is 0 for any mul when S is 0) and cannot brighten
#                  (V * mul only darkens), so a near-black or a white base yields
#                  nothing at all through Recolor.shadergraph. Measured mean
#                  saturation across the six atlases: 04 = 0.206 and 03 = 0.175 are
#                  the only two with anything to rotate; 05 (White) = 0.007 and
#                  02 (Blue) = 0.010 are effectively greyscale.
#
# Change this list to change the size. Each extra costs ~19 MB.
COLOURWAYS = [(6, "Black"), (4, "Red")]

# vendor property -> our URP/Lit property. Order within a tuple is preference.
# `_BaseColor_Map` (extra underscore) is the vendor Shader Graph's own albedo slot
# and is where the true colourway lives on the two broken materials.
ALBEDO_SLOTS = ("_BaseColor_Map", "_BaseColorMap", "_BaseMap", "_MainTex")
NORMAL_SLOTS = ("_NormalMap", "_Normal_Map", "_BumpMap")
METAL_SLOTS = ("_MetallicGlossMap",)
OCCL_SLOTS = ("_MaskMap", "_RGBA_Mask_Map", "_OcclusionMap")

# Maps that must be LINEAR in URP. Anything sampled for a number rather than a
# colour: metallic, smoothness, occlusion, and normals.
LINEAR = ("_BumpMap", "_MetallicGlossMap", "_OcclusionMap")


def existing_copy(fn, src):
    """Path to an already-imported, byte-identical copy of this texture, or None.

    The pack ships ONE shared texture library across the whole character family,
    so importing a second character re-copies its body and clothes maps verbatim:
    M_03 costs 162 MB of which about 125 MB is already on disk under the
    Inquisitor, pixel for pixel. In a repo where these go through Git LFS that
    duplication is permanent.

    Identity is by SIZE AND CONTENT HASH, never by filename. Two files in this
    pack can share a name and differ -- reusing on the name alone would silently
    dress one character in another's textures, which renders perfectly and is
    simply the wrong character. That is the same failure
    Zombie_EachPrefabWearsItsOwnSkinsTextures exists to catch.
    """
    import hashlib
    root = os.path.join(ROOT, "Assets", "_Project", "Art", "Characters")
    if not os.path.isdir(root):
        return None
    want_size = os.path.getsize(src)
    want = None
    for dirpath, _dirs, files in os.walk(root):
        if fn not in files:
            continue
        cand = os.path.join(dirpath, fn)
        if os.path.getsize(cand) != want_size:
            continue
        if want is None:
            want = hashlib.md5(open(src, "rb").read()).hexdigest()
        if hashlib.md5(open(cand, "rb").read()).hexdigest() == want:
            return cand
    return None


def slot_label(slot):
    """Vendor slot name -> our material suffix.

    MAT_ / T_ prefixes are noise, and the rest is title-cased so MAT_EYES_L
    becomes Eyes_L. Derived rather than mapped, because the pack's meshes carry
    between 3 and 22 slots and any fixed table is wrong for all but one of them.
    """
    t = slot
    for pre in ("MAT_", "T_"):
        if t.startswith(pre):
            t = t[len(pre):]
    parts = [w for w in t.split("_") if w]
    return "_".join(w.capitalize() if len(w) > 2 else w.upper() for w in parts) or "Slot"


def log(m):
    print(m, flush=True)


def guid_of(meta):
    if not os.path.isfile(meta):
        return None
    m = re.search(r"^guid: ([a-f0-9]{32})", open(meta, encoding="utf-8", errors="replace").read(), re.M)
    return m.group(1) if m else None


def index_pack():
    """guid -> absolute path, for every asset in the source pack."""
    out = {}
    for dirpath, _, files in os.walk(SRC):
        for f in files:
            if not f.endswith(".meta"):
                continue
            g = guid_of(os.path.join(dirpath, f))
            if g:
                out[g] = os.path.join(dirpath, f[:-5])
    return out


def tex_bindings(mat_path):
    """property -> texture guid, for one vendor material."""
    body = open(mat_path, encoding="utf-8", errors="replace").read()
    return dict(re.findall(
        r"- (_\w+):\n\s+m_Texture: \{fileID: 2800000, guid: ([a-f0-9]{32})", body))


def float_of(mat_path, name, default):
    m = re.search(r"- %s: ([-0-9.eE]+)" % re.escape(name),
                  open(mat_path, encoding="utf-8", errors="replace").read())
    return float(m.group(1)) if m else default


def mesh_slots():
    """
    slot name -> vendor material path, READ OUT OF THE MODEL'S OWN externalObjects.

    Not typed. The pack's FBX declares sixteen material names across all its
    outfit variants and this mesh uses three of them; which three is a fact about
    the file.
    """
    meta = os.path.join(SRC, MESH + ".meta")
    body = open(meta, encoding="utf-8", errors="replace").read()
    blk = re.search(r"externalObjects:(.*?)\n  materials:", body, re.S)
    if not blk:
        raise SystemExit("No externalObjects in " + meta)
    remap = dict(re.findall(
        r"name: (\S+)\n    second: \{fileID: 2100000, guid: ([a-f0-9]{32})", blk.group(1)))
    fbx = open(os.path.join(SRC, MESH), "rb").read()
    used = [n for n in remap if re.search(rb"\x00" + re.escape(n.encode()) + rb"\x00", fbx)
            or n.encode() in fbx]
    # Narrow to the slots this mesh's renderers really declare. The FBX lists a
    # material name once per submesh that uses it.
    # Take whatever the mesh ACTUALLY declares, in the remap's own order.
    #
    # This used to demand exactly HEAD + MAT_BODY + MAT_CLOTHES, which is M_01's
    # set and nobody else's: the Girl meshes carry no HEAD slot at all and Miyoko
    # declares eight. Asserting one model's shape as a universal invariant turns
    # "this character is different" into "the importer is broken".
    #
    # Slots URP/Lit cannot meaningfully sample are dropped rather than imported --
    # the pack binds subsurface, MatID, dirt and blood masks that cost hundreds of
    # megabytes and put zero pixels on screen.
    want = [n for n in remap if n in used and not any(
        skip in n.upper() for skip in ("PHOENIX",))]
    if not want:
        raise SystemExit("The model declares no material slots at all; found " + str(used))
    return want, remap


def fix_meta(text, want_linear, want_normal, name):
    """Force the colour-space and texture-type flags this SLOT needs. Returns (text, notes)."""
    notes = []
    srgb = "0" if want_linear else "1"
    cur = re.search(r"\bsRGBTexture: (\d)", text)
    if cur and cur.group(1) != srgb:
        text = re.sub(r"\bsRGBTexture: \d", "sRGBTexture: " + srgb, text, count=1)
        notes.append("%s: sRGBTexture %s -> %s" % (name, cur.group(1), srgb))
    tt = re.search(r"\btextureType: (\d+)", text)
    want_tt = "1" if want_normal else "0"
    if tt and tt.group(1) != want_tt:
        text = re.sub(r"\btextureType: \d+", "textureType: " + want_tt, text, count=1)
        notes.append("%s: textureType %s -> %s" % (name, tt.group(1), want_tt))
    return text, notes


def material_yaml(name, tex, metallic, smoothness):
    """URP/Lit. `tex` is our-property -> (guid or None)."""
    kw = []
    if tex.get("_BumpMap"):
        kw.append("_NORMALMAP")
    if tex.get("_MetallicGlossMap"):
        kw.append("_METALLICSPECGLOSSMAP")
    if tex.get("_OcclusionMap"):
        kw.append("_OCCLUSIONMAP")
    kwblock = ("  m_ValidKeywords:\n" + "".join("  - %s\n" % k for k in kw)) if kw \
        else "  m_ValidKeywords: []\n"

    def slot(p):
        g = tex.get(p)
        return ("{fileID: 2800000, guid: %s, type: 3}" % g) if g else "{fileID: 0}"

    order = ["_BaseMap", "_BumpMap", "_DetailAlbedoMap", "_DetailMask", "_DetailNormalMap",
             "_EmissionMap", "_MainTex", "_MetallicGlossMap", "_OcclusionMap",
             "_ParallaxMap", "_SpecGlossMap"]
    texenvs = "".join(
        "    - %s:\n        m_Texture: %s\n        m_Scale: {x: 1, y: 1}\n"
        "        m_Offset: {x: 0, y: 0}\n" % (p, slot("_BaseMap" if p == "_MainTex" else p))
        for p in order)

    return """%%YAML 1.1
%%TAG !u! tag:unity3d.com,2011:
--- !u!21 &2100000
Material:
  serializedVersion: 8
  m_ObjectHideFlags: 0
  m_CorrespondingSourceObject: {fileID: 0}
  m_PrefabInstance: {fileID: 0}
  m_PrefabAsset: {fileID: 0}
  m_Name: %s
  m_Shader: {fileID: 4800000, guid: %s, type: 3}
  m_Parent: {fileID: 0}
  m_ModifiedSerializedProperties: 0
%s  m_InvalidKeywords: []
  m_LightmapFlags: 4
  m_EnableInstancingVariants: 0
  m_DoubleSidedGI: 0
  m_CustomRenderQueue: -1
  stringTagMap:
    RenderType: Opaque
  disabledShaderPasses:
  - MOTIONVECTORS
  m_LockedProperties:
  m_SavedProperties:
    serializedVersion: 3
    m_TexEnvs:
%s    m_Ints: []
    m_Floats:
    - _AlphaClip: 0
    - _AlphaToMask: 0
    - _Blend: 0
    - _BlendModePreserveSpecular: 1
    - _BumpScale: 1
    - _ClearCoatMask: 0
    - _ClearCoatSmoothness: 0
    - _Cull: 2
    - _Cutoff: 0.5
    - _DetailAlbedoMapScale: 1
    - _DetailNormalMapScale: 1
    - _DstBlend: 0
    - _DstBlendAlpha: 0
    - _EnvironmentReflections: 1
    - _GlossMapScale: 1
    - _Glossiness: %s
    - _GlossyReflections: 1
    - _Metallic: %s
    - _OcclusionStrength: 1
    - _Parallax: 0.005
    - _QueueOffset: 0
    - _ReceiveShadows: 1
    - _Smoothness: %s
    - _SmoothnessTextureChannel: 0
    - _SpecularHighlights: 1
    - _SrcBlend: 1
    - _SrcBlendAlpha: 1
    - _Surface: 0
    - _WorkflowMode: 1
    - _ZWrite: 1
    m_Colors:
    - _BaseColor: {r: 1, g: 1, b: 1, a: 1}
    - _Color: {r: 1, g: 1, b: 1, a: 1}
    - _EmissionColor: {r: 0, g: 0, b: 0, a: 1}
    - _SpecColor: {r: 0.2, g: 0.2, b: 0.2, a: 1}
  m_BuildTextureStacks: []
  m_AllowLocking: 1
""" % (name, URP_LIT_GUID, kwblock, texenvs, smoothness, metallic, smoothness)


def main():
    if not os.path.isdir(SRC):
        raise SystemExit("Source pack not found at " + SRC)

    by_guid = index_pack()
    slots, remap = mesh_slots()
    log("Model slots read from the FBX: " + ", ".join(slots))
    log("")

    plan_tex = {}          # dst filename -> (src path, our-slot, wants_linear, wants_normal)
    plan_mat = []          # (our material name, {our prop -> dst tex filename}, metallic, smooth)
    mismatches = []

    def resolve(binds, names):
        for n in names:
            g = binds.get(n)
            if g and g in by_guid:
                return n, g
        return None, None

    for slot in slots:
        vendor = by_guid[remap[slot]]
        binds = tex_bindings(vendor)

        if slot == "MAT_CLOTHES":
            variants = COLOURWAYS
        else:
            variants = [(None, None)]

        # The albedo the vendor's OWN shader reads vs the one URP reads.
        a_prop, a_guid = resolve(binds, ALBEDO_SLOTS)
        urp_guid = binds.get("_BaseMap")
        if urp_guid and a_guid and urp_guid != a_guid:
            mismatches.append(
                "%s: _BaseMap holds %s but _BaseColor_Map holds %s -- URP reads _BaseMap, "
                "so this material renders as the WRONG COLOURWAY."
                % (os.path.basename(vendor),
                   os.path.basename(by_guid.get(urp_guid, urp_guid)),
                   os.path.basename(by_guid[a_guid])))

        n_prop, n_guid = resolve(binds, NORMAL_SLOTS)
        m_prop, m_guid = resolve(binds, METAL_SLOTS)
        o_prop, o_guid = resolve(binds, OCCL_SLOTS)

        for num, label in variants:
            if num is not None:
                # Pick the colourway albedo BY NAME from the pack, so a broken
                # material's binding cannot decide which colour we ship.
                cand = os.path.join(SRC, "Textures",
                                    "T_INQUISITOR_CLOTHES_BaseColor_0%d.png" % num)
                if not os.path.isfile(cand):
                    raise SystemExit("No colourway texture at " + cand)
                albedo_src = cand
            else:
                albedo_src = by_guid[a_guid] if a_guid else None

            texs = {}
            for prop, src in (("_BaseMap", albedo_src),
                              ("_BumpMap", by_guid.get(n_guid) if n_guid else None),
                              ("_MetallicGlossMap", by_guid.get(m_guid) if m_guid else None),
                              ("_OcclusionMap", by_guid.get(o_guid) if o_guid else None)):
                if not src:
                    continue
                fn = os.path.basename(src)
                texs[prop] = fn
                # ACCUMULATE the slots, do not overwrite. One file often feeds two
                # slots (the body's MetallicSmoothness is both _MetallicGlossMap and
                # _OcclusionMap), and keying by filename alone silently dropped the
                # first -- which made the size report name a slot the file also, but
                # not only, serves. Same class as everything else in here: a report
                # describing something adjacent to what was done.
                src0, props, lin, nrm = plan_tex.get(fn, (src, set(), False, False))
                props.add(prop)
                plan_tex[fn] = (src, props,
                                lin or prop in LINEAR,
                                nrm or prop == "_BumpMap")

            # DERIVED from the slot name, not looked up in a table. The table
            # held exactly M_01's three slots, so importing any other mesh in the
            # family -- M_03 has twenty-two -- died on a KeyError. A mesh with a
            # slot the author did not anticipate is the normal case in this pack,
            # not the exception.
            mat_name = NAME + "_" + slot_label(slot)
            if label:
                mat_name += "_" + label
            plan_mat.append((mat_name, texs,
                             float_of(vendor, "_Metallic", 0.0),
                             1.0))   # see note in the module docstring

    # ---- report the size BEFORE touching anything -------------------------
    total = os.path.getsize(os.path.join(SRC, MESH))
    log("%8.2f MB  %s" % (total / 1048576.0, os.path.basename(MESH)))
    for fn in sorted(plan_tex):
        src, props, lin, nrm = plan_tex[fn]
        sz = os.path.getsize(src)
        total += sz
        log("%8.2f MB  %-46s -> %s%s" % (sz / 1048576.0, fn,
                                         ", ".join(sorted(props)), "  [linear]" if lin else ""))
    log("")
    log("TOTAL TO ADD: %.1f MB   (%d textures + 1 mesh, %d materials)"
        % (total / 1048576.0, len(plan_tex), len(plan_mat)))
    log("")

    if mismatches:
        log("THE SLOT-BINDING BUG, as found in the pack:")
        for m in mismatches:
            log("  " + m)
        log("")

    if DRY:
        log("--dry-run: nothing copied.")
        return

    os.makedirs(os.path.join(DST, "Textures"), exist_ok=True)
    os.makedirs(os.path.join(DST, "Materials"), exist_ok=True)

    # ---- textures, with their metas corrected for the slot -----------------
    notes = []
    reused = {}          # our filename -> the already-imported path we point at
    for fn in sorted(plan_tex):
        src, props, lin, nrm = plan_tex[fn]
        share = existing_copy(fn, src)
        if share is not None:
            reused[fn] = share
            continue
        shutil.copy2(src, os.path.join(DST, "Textures", fn))
        meta = open(src + ".meta", encoding="utf-8", errors="replace").read()
        meta, n = fix_meta(meta, lin, nrm, fn)
        notes += n
        # Not from a store package once it is ours; keeping AssetOrigin would
        # attribute the copy to IdaFaber's upload id and confuse the importer.
        meta = re.sub(r"^AssetOrigin:\n(?:  .*\n)+", "", meta, count=1, flags=re.M)
        with open(os.path.join(DST, "Textures", fn + ".meta"), "w",
                  encoding="utf-8", newline="\n") as fh:
            fh.write(meta)

    if notes:
        log("Import flags corrected (data maps must not be sRGB in URP):")
        for n in notes:
            log("  " + n)
        log("")

    # ---- materials ---------------------------------------------------------
    tex_guid = {}
    for fn in plan_tex:
        where = reused.get(fn, os.path.join(DST, "Textures", fn))
        tex_guid[fn] = guid_of(where + ".meta")
    if reused:
        saved = sum(os.path.getsize(v) for v in reused.values()) / 1048576.0
        log("Reused %d texture(s) already imported, byte-identical -- %.1f MB not duplicated:"
            % (len(reused), saved))
        for fn in sorted(reused):
            log("  %-46s <- %s" % (fn, os.path.relpath(reused[fn], ROOT)))
        log("")

    mat_guid = {}
    for name, texs, metallic, smooth in plan_mat:
        p = os.path.join(DST, "Materials", name + ".mat")
        g = guid_of(p + ".meta") or __import__("hashlib").md5(
            ("aftermarket.inq." + name).encode()).hexdigest()
        mat_guid[name] = g
        with open(p, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(material_yaml(name, dict((k, tex_guid[v]) for k, v in texs.items()),
                                   metallic, smooth))
        with open(p + ".meta", "w", encoding="utf-8", newline="\n") as fh:
            fh.write("fileFormatVersion: 2\nguid: %s\nNativeFormatImporter:\n"
                     "  externalObjects: {}\n  mainObjectFileID: 2100000\n  userData: \n"
                     "  assetBundleName: \n  assetBundleVariant: \n" % g)
        log("material %-28s %s" % (name, ", ".join(
            "%s=%s" % (k, v) for k, v in sorted(texs.items()))))

    # ---- the mesh, and its remap ------------------------------------------
    dst_fbx = os.path.join(DST, os.path.basename(MESH))
    shutil.copy2(os.path.join(SRC, MESH), dst_fbx)
    meta = open(os.path.join(SRC, MESH + ".meta"), encoding="utf-8", errors="replace").read()
    meta = re.sub(r"^AssetOrigin:\n(?:  .*\n)+", "", meta, count=1, flags=re.M)

    pick = {}
    for slot in slots:
        n = NAME + "_" + slot_label(slot)
        if slot == "MAT_CLOTHES":
            n += "_" + COLOURWAYS[0][1]
        pick[slot] = n
    entry = "  externalObjects:\n"
    for slot in slots:
        entry += ("  - first:\n      type: UnityEngine:Material\n"
                  "      assembly: UnityEngine.CoreModule\n      name: %s\n"
                  "    second: {fileID: 2100000, guid: %s, type: 2}\n"
                  % (slot, mat_guid[pick[slot]]))
    new = re.sub(r"^  externalObjects:.*?(?=^  materials:)", entry, meta,
                 count=1, flags=re.M | re.S)
    if new == meta:
        raise SystemExit("Could not rewrite externalObjects in the model meta.")
    with open(dst_fbx + ".meta", "w", encoding="utf-8", newline="\n") as fh:
        fh.write(new)

    # ---- verify the END STATE ---------------------------------------------
    bad = []
    check = open(dst_fbx + ".meta", encoding="utf-8").read()
    blk = re.search(r"^  externalObjects:(.*?)(?=^  materials:)", check, re.M | re.S)
    if not blk:
        bad.append("model meta has no externalObjects block")
    else:
        for slot in slots:
            if "name: " + slot not in blk.group(1):
                bad.append("model meta does not remap slot " + slot)
        found = set(re.findall(r"guid: ([a-f0-9]{32})", blk.group(1)))
        want = set(mat_guid[pick[s]] for s in slots)
        if found != want:
            bad.append("model remap resolves to %s, expected %s" % (sorted(found), sorted(want)))

    for name, texs, _, _ in plan_mat:
        body = open(os.path.join(DST, "Materials", name + ".mat"), encoding="utf-8").read()
        if "_BaseMap" in texs:
            got = re.search(r"- _BaseMap:\n        m_Texture: \{fileID: 2800000, guid: ([a-f0-9]{32})", body)
            if not got:
                bad.append(name + ": _BaseMap is EMPTY -- this is the bug, not the fix")
            elif got.group(1) != tex_guid[texs["_BaseMap"]]:
                bad.append(name + ": _BaseMap points somewhere unexpected")
        if "_BumpMap" in texs and "_NORMALMAP" not in body:
            bad.append(name + ": binds _BumpMap without the _NORMALMAP keyword, so URP/Lit "
                              "will never sample it")

    # Every data map we copied must be linear on disk.
    for fn, (src, props, lin, nrm) in plan_tex.items():
        if not lin:
            continue
        # Follow the reuse map: a shared texture's flags matter exactly as much as
        # a freshly copied one's, and it is the SHARED file the material points at.
        where = reused.get(fn, os.path.join(DST, "Textures", fn))
        t = open(where + ".meta", encoding="utf-8").read()
        m = re.search(r"\bsRGBTexture: (\d)", t)
        if m and m.group(1) != "0":
            bad.append(fn + " is still flagged sRGB but feeds " + ", ".join(sorted(props)))

    if bad:
        for b in bad:
            log("FAIL " + b)
        raise SystemExit("Verification failed.")

    log("")
    log("verified: the model remaps all %d slots to the new URP/Lit materials, every "
        "_BaseMap is bound, every normal carries _NORMALMAP, and every data map is linear."
        % len(slots))
    # MEASURED off disk, not the planned total. The plan says what WOULD be
    # copied; dedup then reuses most of it, so reporting the plan overstated
    # this import by 32x (162.6 MB claimed, 5.1 MB real). A report that
    # describes intent instead of state is worse than no report.
    added = sum(os.path.getsize(os.path.join(dp, f))
                for dp, _d, fs in os.walk(DST) for f in fs)
    log("Added %.1f MB under %s  (%d texture(s) reused from an earlier import)"
        % (added / 1048576.0, os.path.relpath(DST, ROOT), len(reused)))


if __name__ == "__main__":
    main()
