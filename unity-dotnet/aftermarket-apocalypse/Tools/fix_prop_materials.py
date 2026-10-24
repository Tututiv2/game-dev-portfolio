#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Give the forged props real URP materials -- and stop the baked NORMAL map being
read as their ALBEDO.

    python Tools/fix_prop_materials.py            # do it
    python Tools/fix_prop_materials.py --dry-run  # say what it would do

THE BUG THIS FIXES
------------------
Every prop .fbx.meta carries `externalObjects: {}` with `materialLocation: 1`
("use embedded materials"), so Unity renders each prop through the material
Blender wrote INTO the FBX. That material is an `FbxSurfacePhong` named
`<slug>_bakemat`, and mesh_pipeline.py's `--bake` step hangs the baked
`<slug>_normal.png` off its DIFFUSE input -- there is no albedo in the chain, so
the only image it had was the normal map.

A tangent-space normal map is overwhelmingly (128,128,255): flat lavender-blue.
Read as albedo, under a Phong specular lobe, that is a pale blue mirror -- the
"teal chrome crate". Nothing errors. The FBX is valid, the material is valid,
the texture is valid; it is bound to a slot that means something else.

Same shape as the two already recorded in CLAUDE.md: the ZombieM_02 FBX
remapping to Built-in materials that were never copied, and the Inquisitor
binding its colourway texture to `_BaseColor_Map` while `_BaseMap` -- the one
URP/Lit actually samples -- keeps the white one.

WHAT IT WRITES
--------------
  Assets/_Project/Art/Props/Textures/<slug>_normal.png   (+ .meta, textureType 1)
  Assets/_Project/Art/Props/Materials/<slug>.mat         (+ .meta, URP/Lit)
and an `externalObjects` remap into <slug>.fbx.meta and both LOD metas, so the
embedded material name resolves to the new .mat.

Nothing in AshfallDepot.unity changes. Checked before writing this: the scene
carries no `propertyPath: m_Materials` override on any prop instance, so the 22
placed props pick the remap up from the importer.

THREE THINGS IT DERIVES RATHER THAN TYPES
-----------------------------------------
1. The embedded material name is READ OUT OF THE FBX BINARY, not assumed to be
   "<slug>_bakemat". If mesh_pipeline ever renames it, this errors instead of
   writing a remap that matches nothing -- which is the failure mode of a remap:
   Unity does not warn about an externalObjects entry whose `name` matches no
   embedded material, it just carries on using the embedded one.
2. The normal map filename is read out of the FBX too, for the same reason.
3. `_BaseColor` is MEASURED -- the mean of the masked subject in the prop's own
   Flux reference image (`_work/<slug>/<slug>_cutout.png`), averaged in LINEAR
   light and then re-encoded to sRGB for authoring, because Unity gamma-decodes
   Color-typed material properties on upload in a linear project. That is the
   `_SpecColor: 0.04` trap CLAUDE.md already paid for, in the other direction.
   If the reference image is missing this REFUSES rather than typing a colour.

   It is still a stand-in: the props have no albedo texture and there is nothing
   in the pipeline that could produce one. A flat measured tint is honest about
   that. Colour proper needs Hunyuan3D's paint stage or a camera projection of
   the Flux image, neither of which is done here.

THE KEYWORD IS LOAD-BEARING
---------------------------
`_NORMALMAP` must be in `m_ValidKeywords` or URP/Lit compiles the variant that
never samples `_BumpMap` -- the texture is bound, the inspector shows it, and it
contributes nothing. That is this same bug class one more time, so the verify
pass at the end asserts the keyword, not just the binding.
"""

import hashlib
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

PROPS_DIR = os.path.join(ROOT, "Assets", "_Project", "Art", "Props")
TEX_DIR = os.path.join(PROPS_DIR, "Textures")
MAT_DIR = os.path.join(PROPS_DIR, "Materials")
WORK = os.path.join(ROOT, "Tools", "forge", "_work")

# Template for a normal-map .meta, taken from a texture Unity itself imported in
# this project rather than hand-written: Art/Materials/Brick wall seamless/normals.png.
NORMAL_META_TEMPLATE = os.path.join(
    ROOT, "Assets", "_Project", "Art", "Materials", "Brick wall seamless", "normals.png.meta")

URP_LIT_GUID = "933532a4fcc9baf4fa0491de14d08ed7"

# Surface response. Not measured -- these props are dry, non-metal debris, so
# metallic 0 is a statement about what they ARE, and 0.15 smoothness is "matte
# but not chalk". The thing that was actually wrong was never the roughness.
METALLIC = 0.0
SMOOTHNESS = 0.15

DRY = "--dry-run" in sys.argv


def log(msg):
    print(msg, flush=True)


def guid_of(meta_path):
    if not os.path.isfile(meta_path):
        return None
    with open(meta_path, encoding="utf-8") as fh:
        m = re.search(r"^guid: ([a-f0-9]{32})", fh.read(), re.M)
    return m.group(1) if m else None


def stable_guid(key):
    """Deterministic, so re-running this script cannot orphan a reference."""
    return hashlib.md5(("aftermarket.prop." + key).encode("utf-8")).hexdigest()


def guid_for(asset_path, key):
    """Reuse the GUID already on disk if there is one; otherwise mint a stable one."""
    return guid_of(asset_path + ".meta") or stable_guid(key)


def read_fbx_bindings(fbx_path):
    """
    (material_name, normal_png_name) read out of the FBX itself.

    Both are stored as length-prefixed ASCII in the binary, so a plain scan is
    enough and does not need an FBX parser. Refuses on 0 or >1 match rather than
    picking one -- a remap written against a guessed name is silently ignored.
    """
    data = open(fbx_path, "rb").read()
    mats = sorted(set(m.decode() for m in re.findall(rb"[A-Za-z0-9_]+_bakemat", data)))
    texs = sorted(set(t.decode() for t in re.findall(rb"[A-Za-z0-9_]+_normal\.png", data)))
    if len(mats) != 1:
        raise SystemExit(
            "%s embeds %d materials matching *_bakemat (%s). This script assumes exactly "
            "one and will not guess -- an externalObjects entry whose name matches no "
            "embedded material is ignored without any warning."
            % (os.path.basename(fbx_path), len(mats), mats))
    if len(texs) > 1:
        raise SystemExit("%s embeds %d normal maps (%s)." % (os.path.basename(fbx_path), len(texs), texs))
    return mats[0], (texs[0] if texs else None)


def measured_base_color(slug):
    """
    Mean subject colour of the prop's own Flux reference, averaged in LINEAR
    light, returned as the sRGB-encoded value to AUTHOR into the .mat.

    Unity gamma-decodes Color-typed properties on upload in a linear project, so
    the number in the file is sRGB and the shader receives its linear decode.
    Averaging in sRGB instead would be averaging non-linear numbers, which is
    wrong in the same quiet way as everything else in this file.
    """
    try:
        from PIL import Image
        import numpy as np
    except ImportError:
        raise SystemExit("Pillow and numpy are needed to measure the prop colours "
                         "(pip install pillow numpy).")

    cutout = os.path.join(WORK, slug, slug + "_cutout.png")
    if not os.path.isfile(cutout):
        raise SystemExit(
            "No reference image at %s, so there is nothing to measure this prop's colour "
            "FROM. Refusing to type a plausible one -- that is the mistake this project "
            "keeps paying for." % cutout)

    arr = np.asarray(Image.open(cutout).convert("RGBA")).astype(np.float64)
    mask = arr[..., 3] > 200
    if mask.sum() < 1000:
        raise SystemExit("The matte in %s covers %.2f%% of the frame -- too little to "
                         "average." % (cutout, 100.0 * mask.mean()))
    srgb = arr[..., :3][mask] / 255.0
    linear = np.where(srgb <= 0.04045, srgb / 12.92, ((srgb + 0.055) / 1.055) ** 2.4)
    mean = linear.mean(axis=0)
    out = np.where(mean <= 0.0031308, mean * 12.92, 1.055 * mean ** (1 / 2.4) - 0.055)
    return tuple(round(float(v), 6) for v in out), float(100.0 * mask.mean())


def write(path, text):
    if DRY:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def normal_meta(guid):
    body = open(NORMAL_META_TEMPLATE, encoding="utf-8").read()
    if "textureType: 1" not in body:
        raise SystemExit("The template meta %s is not textureType 1 (NormalMap) any more."
                         % NORMAL_META_TEMPLATE)
    body = re.sub(r"^guid: [a-f0-9]{32}", "guid: " + guid, body, count=1, flags=re.M)
    # It is not from a store package; carrying the template's AssetOrigin would
    # attribute our own file to ShadowVulture's pack.
    body = re.sub(r"^AssetOrigin:\n(?:  .*\n)+", "", body, count=1, flags=re.M)
    return body


def material_yaml(name, color, normal_guid):
    r, g, b = color
    has_normal = normal_guid is not None
    tex_normal = ("{fileID: 2800000, guid: %s, type: 3}" % normal_guid) if has_normal else "{fileID: 0}"
    keywords = "  m_ValidKeywords:\n  - _NORMALMAP\n" if has_normal else "  m_ValidKeywords: []\n"
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
    - _BaseMap:
        m_Texture: {fileID: 0}
        m_Scale: {x: 1, y: 1}
        m_Offset: {x: 0, y: 0}
    - _BumpMap:
        m_Texture: %s
        m_Scale: {x: 1, y: 1}
        m_Offset: {x: 0, y: 0}
    - _DetailAlbedoMap:
        m_Texture: {fileID: 0}
        m_Scale: {x: 1, y: 1}
        m_Offset: {x: 0, y: 0}
    - _DetailMask:
        m_Texture: {fileID: 0}
        m_Scale: {x: 1, y: 1}
        m_Offset: {x: 0, y: 0}
    - _DetailNormalMap:
        m_Texture: {fileID: 0}
        m_Scale: {x: 1, y: 1}
        m_Offset: {x: 0, y: 0}
    - _EmissionMap:
        m_Texture: {fileID: 0}
        m_Scale: {x: 1, y: 1}
        m_Offset: {x: 0, y: 0}
    - _MainTex:
        m_Texture: {fileID: 0}
        m_Scale: {x: 1, y: 1}
        m_Offset: {x: 0, y: 0}
    - _MetallicGlossMap:
        m_Texture: {fileID: 0}
        m_Scale: {x: 1, y: 1}
        m_Offset: {x: 0, y: 0}
    - _OcclusionMap:
        m_Texture: {fileID: 0}
        m_Scale: {x: 1, y: 1}
        m_Offset: {x: 0, y: 0}
    - _ParallaxMap:
        m_Texture: {fileID: 0}
        m_Scale: {x: 1, y: 1}
        m_Offset: {x: 0, y: 0}
    - _SpecGlossMap:
        m_Texture: {fileID: 0}
        m_Scale: {x: 1, y: 1}
        m_Offset: {x: 0, y: 0}
    m_Ints: []
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
    - _GlossMapScale: 0
    - _Glossiness: 0
    - _GlossyReflections: 0
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
    - _BaseColor: {r: %s, g: %s, b: %s, a: 1}
    - _Color: {r: %s, g: %s, b: %s, a: 1}
    - _EmissionColor: {r: 0, g: 0, b: 0, a: 1}
    - _SpecColor: {r: 0.2, g: 0.2, b: 0.2, a: 1}
  m_BuildTextureStacks: []
  m_AllowLocking: 1
""" % (name, URP_LIT_GUID, keywords, tex_normal, METALLIC, SMOOTHNESS, r, g, b, r, g, b)


def remap_meta(meta_path, mat_name, mat_guid):
    """Write the externalObjects entry into one model .meta. Returns True if changed."""
    body = open(meta_path, encoding="utf-8").read()
    entry = ("  externalObjects:\n"
             "  - first:\n"
             "      type: UnityEngine:Material\n"
             "      assembly: UnityEngine.CoreModule\n"
             "      name: %s\n"
             "    second: {fileID: 2100000, guid: %s, type: 2}\n" % (mat_name, mat_guid))

    new = re.sub(r"^  externalObjects:.*?(?=^  materials:)", entry, body, count=1, flags=re.M | re.S)
    if new == body:
        return False
    if not DRY:
        with open(meta_path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(new)
    return True


def main():
    fbxs = sorted(f for f in os.listdir(PROPS_DIR)
                  if f.endswith(".fbx") and not re.search(r"_LOD\d+\.fbx$", f))
    if not fbxs:
        raise SystemExit("No prop FBXs in " + PROPS_DIR)

    log("Props found: %d" % len(fbxs))
    log("")

    for fbx in fbxs:
        slug = fbx[:-4]
        mat_name, normal_png = read_fbx_bindings(os.path.join(PROPS_DIR, fbx))
        color, cover = measured_base_color(slug)

        # ---- the normal map, as an actual NormalMap-typed texture ----------
        normal_guid = None
        if normal_png:
            src = os.path.join(WORK, slug, normal_png)
            if not os.path.isfile(src):
                raise SystemExit(
                    "%s embeds %s but %s is gone. The bake cannot be recovered from the FBX "
                    "here -- re-run the forge for this slug, or delete the bake from the "
                    "pipeline. Refusing to ship a material with an empty _BumpMap while "
                    "claiming the normal was rebound." % (fbx, normal_png, src))
            dst = os.path.join(TEX_DIR, normal_png)
            normal_guid = guid_for(dst, "tex/" + normal_png)
            if not DRY:
                os.makedirs(TEX_DIR, exist_ok=True)
                with open(src, "rb") as a, open(dst, "wb") as b:
                    b.write(a.read())
            write(dst + ".meta", normal_meta(normal_guid))

        # ---- the material --------------------------------------------------
        mat_path = os.path.join(MAT_DIR, slug + ".mat")
        mat_guid = guid_for(mat_path, "mat/" + slug)
        write(mat_path, material_yaml(slug, color, normal_guid))
        write(mat_path + ".meta",
              "fileFormatVersion: 2\nguid: %s\nNativeFormatImporter:\n  externalObjects: {}\n"
              "  mainObjectFileID: 2100000\n  userData: \n  assetBundleName: \n"
              "  assetBundleVariant: \n" % mat_guid)

        # ---- the remap, on the base FBX and every LOD -----------------------
        touched = []
        for cand in sorted(os.listdir(PROPS_DIR)):
            if not cand.endswith(".fbx"):
                continue
            if cand != fbx and not re.match(re.escape(slug) + r"_LOD\d+\.fbx$", cand):
                continue
            # An LOD embeds the same material; confirm rather than assume.
            cand_mat, _ = read_fbx_bindings(os.path.join(PROPS_DIR, cand))
            if cand_mat != mat_name:
                raise SystemExit("%s embeds '%s' but its base embeds '%s'." % (cand, cand_mat, mat_name))
            if remap_meta(os.path.join(PROPS_DIR, cand + ".meta"), mat_name, mat_guid):
                touched.append(cand)

        log("%-16s mat '%s' -> %s.mat  colour %s (from %.0f%% matte)  normal %s"
            % (slug, mat_name, slug,
               "(%.3f, %.3f, %.3f)" % color, cover,
               normal_png or "NONE"))
        log("%-16s remapped in: %s" % ("", ", ".join(touched) or "nothing"))

    if DRY:
        log("")
        log("--dry-run: nothing written.")
        return

    # ---- verify the END STATE, by reading it back ------------------------
    log("")
    bad = []
    for fbx in sorted(f for f in os.listdir(PROPS_DIR) if f.endswith(".fbx")):
        slug = re.sub(r"_LOD\d+$", "", fbx[:-4])
        meta = open(os.path.join(PROPS_DIR, fbx + ".meta"), encoding="utf-8").read()
        block = re.search(r"^  externalObjects:(.*?)(?=^  materials:)", meta, re.M | re.S)
        want_mat, _ = read_fbx_bindings(os.path.join(PROPS_DIR, fbx))
        if not block or "name: " + want_mat not in block.group(1):
            bad.append("%s: externalObjects does not name '%s'" % (fbx, want_mat))
            continue
        g = re.search(r"guid: ([a-f0-9]{32})", block.group(1))
        expect = guid_of(os.path.join(MAT_DIR, slug + ".mat.meta"))
        if not g or g.group(1) != expect:
            bad.append("%s: remap points at %s, expected %s" % (fbx, g and g.group(1), expect))

    for slug in sorted(set(re.sub(r"_LOD\d+$", "", f[:-4])
                           for f in os.listdir(PROPS_DIR) if f.endswith(".fbx"))):
        mat = open(os.path.join(MAT_DIR, slug + ".mat"), encoding="utf-8").read()
        bump = re.search(r"- _BumpMap:\n        m_Texture: \{fileID: 2800000, guid: ([a-f0-9]{32})", mat)
        if not bump:
            bad.append("%s.mat: _BumpMap is empty" % slug)
        elif "_NORMALMAP" not in mat:
            # The whole point. Bound but never sampled is the bug, not the fix.
            bad.append("%s.mat: binds _BumpMap but does not set the _NORMALMAP keyword, "
                       "so URP/Lit will not sample it" % slug)
        if "_BaseMap:\n        m_Texture: {fileID: 2800000" in mat:
            bad.append("%s.mat: something is bound to _BaseMap -- these props have no albedo, "
                       "and a normal map in that slot is the bug this script exists to fix" % slug)

    if bad:
        for b in bad:
            log("FAIL " + b)
        raise SystemExit("Verification failed.")

    log("verified: every prop FBX (base + LODs) remaps its embedded material to the new "
        "URP/Lit .mat, every .mat binds its normal to _BumpMap with _NORMALMAP set, and "
        "nothing is bound to _BaseMap.")


if __name__ == "__main__":
    main()
