"""
Carry ZombieM_02 across from the old project into this one.

REBUILD_SPEC.md 1.4 lists ZombieM_02 as Load-bearing carry-over art. It is not
something to author. The work is moving it and making its materials URP, and the
surprise is that MOST OF THAT IS ALREADY DONE: the pack ships a URP/ folder with
its own prefabs and its own materials on the URP/Lit shader (guid
933532a4fcc9baf4fa0491de14d08ed7 -- the same shader this project's own kit
materials use). The materials are merely MISNAMED "Buit-in_*", which is what
makes it look like there is no URP variant. There is.

So this script does not convert a shader. It copies a curated subset:

  * one of the three body meshes (they are three skins of one character)
  * only the animation clips the authored controller actually uses
  * only the textures the two URP materials actually bind
  * the two URP materials themselves

Two rules it follows, both taken from how this project handled the 580 MB
ShadowVultureMaterialPack:

  1. **Copy the .meta files.** A material references its textures by GUID. Copy
     the .tga and let Unity mint a fresh GUID and every binding in the material
     lands on nothing -- with no import error, which is the failure mode this
     project keeps recording. Carrying the sidecar carries the GUID AND every
     import setting the vendor chose (textureType 1 and sRGB 0 on the normals,
     sRGB 1 on the albedos), so the result is byte-for-byte the import the old
     project had.

  2. **Do not copy 1.7 GB into a git repo.** The full pack is 1,708 MB, of which
     1,588 MB is uncompressed 4096x4096 TGA across three skins. One skin's bound
     textures are 416 MB.

The textures are re-encoded TGA -> PNG. That is **lossless** -- PNG is a
lossless format, and this script PROVES it rather than asserting it: every
converted image is re-opened and compared pixel-for-pixel against its source
with numpy, and the run fails if a single pixel differs. 416 MB becomes about
150 MB with, demonstrably, no quality change at all. Resolution is NOT reduced,
because Unity's own import settings (carried over in the .meta) already decide
the built size and second-guessing them would be a quality decision made by
accident.

Run:  python Tools/import_zombie.py
      python Tools/import_zombie.py --dry-run
"""
import os
import re
import shutil
import sys

import numpy as np
from PIL import Image

Image.MAX_IMAGE_PIXELS = None

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.abspath(os.path.join(
    HERE, "..", "Game creation", "Zombies", "Zombies Unity", "Assets", "ZombieM_02"))
DST = os.path.join(HERE, "Assets", "_Project", "Art", "Zombie")

# WHICH OF THE THREE ZOMBIES. Pass --skin all | 1 | 2 | 3 | 1,3.
#
# They are not three colourways of one character -- the MESHES differ too, so the
# mesh, the texture folder and the material folder all have to move together or
# you get a body wearing another body's skin. That coupling is the whole reason
# this is one number rather than three paths.
#
# WHAT EACH SKIN'S ALBEDO ACTUALLY IS. MEASURED -- the mean RGB Unity itself
# reports for the bound _BaseMap, via Tools > Aftermarket > Audit Zombie Skins
# (written to Screenshots/zombie/skin_audit.txt):
#
#   1  ICE    -- body albedo (0.262, 0.323, 0.393)   BLUE-dominant, ice crystals
#   2  BONE   -- body albedo (0.457, 0.412, 0.387)   WARM pale, bone spurs
#   3  TOXIC  -- body albedo (0.397, 0.445, 0.393)   GREEN, plus a green emissive
#
# ################ READ THIS BEFORE RENAMING ANY OF THEM ##################
#
# A TEXTURE AND A RENDER ARE TWO DIFFERENT FACTS, AND THIS FILE HAS NOW TWICE
# RECORDED THE SECOND AS IF IT WERE THE FIRST.
#
# Round one: both this file and CLAUDE.md called skin 1 "ICE / blue frost
# crystals". Somebody finally rendered skin 1, saw an olive-green zombie, and
# relabelled it "MOSS / olive green".
#
# Round two: "ICE" was then DELETED rather than tried against the other numbers,
# and skin 2 -- still never rendered -- was given a freshly invented "BONE / the
# ordinary zombie". When skin 2 was eventually rendered it came out cold
# blue-grey, so "ICE" was moved onto it.
#
# BOTH ROUNDS ARGUED FROM THE PICTURE AND NEITHER OPENED THE TEXTURE. The texture
# says skin 1 is blue and skin 2 is warm, so the ORIGINAL labels were right about
# the art and every correction since has been chasing two render artefacts:
#
#   * SKIN 1 RENDERS WITH ITS BLUE CHANNEL AT LITERALLY ZERO. Measured on
#     Screenshots/zombie/skin1/front.png: 51.9% of body pixels have B == 0
#     exactly, against 0.0% for skins 2 and 3, and every pixel across the chest
#     row is (r, g, 0). A blue albedo under any positive light cannot do that.
#     *** THE CAUSE IS NOT FOUND. *** Until it is, skin 1 is an ICE zombie that
#     renders mossy, and "MOSS" was a name for a bug.
#
#   * SKIN 2 LOOKS COOL BECAUSE THE SKY IS. Its albedo is warm (R > G > B) and
#     nearly neutral, so it takes the colour of the light -- and the map's HDRI
#     is belfast_sunset_puresky, a blue-grey sky. "Cold blue-grey" describes the
#     lighting, not the zombie.
#
# So the rule is NOT "render it before you name it" -- that is exactly what
# produced both errors. It is: NAME THE ART FROM THE ART, and record separately
# how it happens to render today, because a render is albedo x lighting x
# whatever is currently broken. And: disproving a label where it sits does not
# make it false at every other address -- move it, or prove it belongs nowhere.
#
# THE PACK SHIPS EXACTLY THREE AND THERE ARE NO MORE. Mesh/ holds
# SK_ZombieM_02_Mesh1/2/3.fbx and Textures/ holds Texture_1/2/3; that is the
# whole of it. (The old project also carries a separate, much cruder `Zombie/`
# pack -- Zombie1/2/3.FBX sharing ONE texture -- which is not this pack and is
# not imported here.)
#
# <b>All three are imported by default now.</b> Owner's decision 2026-07-31,
# superseding the earlier "skin 2 only": "add ALL skins for zombies". They coexist
# on disk -- meshes and textures were already skin-suffixed, and the materials now
# are too, which is the only thing that used to collide.
DEFAULT_SKINS = (1, 2, 3)
SKINS = DEFAULT_SKINS
for _i, _a in enumerate(sys.argv):
    if _a == "--skin" and _i + 1 < len(sys.argv):
        _v = sys.argv[_i + 1].strip().lower()
        SKINS = DEFAULT_SKINS if _v == "all" else tuple(
            int(x) for x in _v.replace(" ", "").split(",") if x)
for _s in SKINS:
    if _s not in (1, 2, 3):
        raise SystemExit("--skin takes 'all' or any of 1, 2, 3 (e.g. --skin 1,3)")

# Each entry states the TEXTURE, then how it currently RENDERS where the two
# differ. See the block above: those are two different facts and this project has
# now twice written down the second one as if it were the first.
SKIN_NAMES = {
    1: "ICE / blue-dominant albedo (0.26,0.32,0.39) "
       "-- BUT CURRENTLY RENDERS FLAT OLIVE, blue channel dead. See CLAUDE.md.",
    2: "BONE / warm pale albedo (0.46,0.41,0.39); reads cool blue-grey under the map's sky",
    3: "TOXIC / green albedo (0.40,0.45,0.39) with a green emissive",
}


def mesh_name(skin):
    return "SK_ZombieM_02_Mesh%d.fbx" % skin


def material_dir(skin):
    return "URP/Materials/Material_%d" % skin

# Exactly the clips ZombieImporter's AnimatorController references. The other
# twelve the pack ships -- 180Left1, 180Right1, BackWalk1, Death1-3, Gethit1-2,
# Idle2, Rage1, StrafeL1, StrafeR1 -- are deliberately left behind: Death is
# unreachable (EnemyAI.Die disables the Animator and hands over to the ragdoll)
# and nothing else has a state yet. They are one line away in ANIMATIONS if a
# state is ever added for them.
ANIMATIONS = [
    "StandUp1",             # the 3 s spawn rise (RoundRules.ZombieStandUpTime)
    "Idle1",
    "Walk1", "Walk2",       # EnemyAI sets WalkIndex 0..walkCount-1, walkCount = 2
    "Run1",                 # drives both Run and Sprint; Sprint plays it faster
    "Attack1", "Attack2", "Attack3", "Attack4", "Attack5",   # attackCount = 5
]

# "Buit-in_" is the vendor's own typo and it sits inside a folder called URP,
# which is actively misleading -- it is the single reason this pack reads as
# "Built-in and HDRP only". Renamed on the way in. The .meta comes along, so the
# GUID is unchanged and nothing that referenced it can break.
def material_renames(skin):
    return {
        "Buit-in_ZombieM_02_Body_Skin%d.mat" % skin:
            "ZombieM_02_Body_Skin%d.mat" % skin,
        "Buit-in_ZombieM_02_Cloth_Skin%d.mat" % skin:
            "ZombieM_02_Cloth_Skin%d.mat" % skin,
    }


# THE TARGET NAMES CARRY THE SKIN NUMBER, and that is a reversal.
#
# They used to be skin-INdependent ("ZombieM_02_Body.mat") so that switching skins
# was a re-import rather than an edit of four other files. That was right while
# exactly one skin was ever on disk. It is wrong now: with all three imported, one
# name for three different materials is a collision, and the last import would
# silently win. Nothing in C# ever referenced these by string -- ZombieImporter
# resolves materials through the FBX's own externalObjects remap -- so suffixing
# them costs nothing and removes the collision at source.

DRY = "--dry-run" in sys.argv


def log(msg):
    print(msg, flush=True)


def guid_of(meta_path):
    with open(meta_path, encoding="utf-8") as fh:
        m = re.search(r"^guid: ([a-f0-9]{32})", fh.read(), re.M)
    return m.group(1) if m else None


def index_source_textures():
    """guid -> absolute .tga path, for every texture in the pack."""
    out = {}
    for dirpath, _, files in os.walk(os.path.join(SRC, "Textures")):
        for f in files:
            if not f.endswith(".tga.meta"):
                continue
            g = guid_of(os.path.join(dirpath, f))
            if g:
                out[g] = os.path.join(dirpath, f[:-5])
    return out


def existing_guids_in_project():
    """
    Every GUID already used in Assets/, so an incoming one cannot collide with
    an unrelated asset.

    <b>Everything under the zombie's own folder is excluded.</b> This import is
    idempotent by design -- re-running it, or switching skins, re-copies the same
    animation FBXs carrying the same GUIDs they had last time, which is the
    CORRECT outcome and not a collision. The first version did not exclude them
    and refused to run a second time, reporting ten "clashes" in which the file
    it was clashing with was its own previous output.
    """
    seen = {}
    for dirpath, _, files in os.walk(os.path.join(HERE, "Assets")):
        if os.path.abspath(dirpath).startswith(os.path.abspath(DST)):
            continue
        for f in files:
            if not f.endswith(".meta"):
                continue
            p = os.path.join(dirpath, f)
            g = guid_of(p)
            if g:
                seen[g] = os.path.relpath(p, HERE)
    return seen


def copy_with_meta(src_file, dst_file, dry=DRY):
    """Copy a file AND its sidecar. Never one without the other."""
    src_meta, dst_meta = src_file + ".meta", dst_file + ".meta"
    if not os.path.exists(src_meta):
        raise SystemExit("No .meta beside " + src_file + " -- refusing to copy it "
                         "naked, that is how GUID links get broken silently.")
    if dry:
        return os.path.getsize(src_file)
    os.makedirs(os.path.dirname(dst_file), exist_ok=True)
    shutil.copy2(src_file, dst_file)
    shutil.copy2(src_meta, dst_meta)
    return os.path.getsize(src_file)


def convert_texture(src_tga, dst_png):
    """
    TGA -> PNG, losslessly, and PROVE it.

    The .meta is carried over under the new extension, so the GUID and every
    import setting survive. TextureImporter does not care which container the
    pixels arrived in.
    """
    im = Image.open(src_tga)
    mode = im.mode
    # Palettised images (the two occlusion maps ship as mode "P") are expanded to
    # RGB. A palette PNG would import fine, but comparing them meaningfully means
    # comparing resolved colour, and RGB is what Unity ends up with either way.
    out = im.convert("RGB") if mode == "P" else im

    if DRY:
        return os.path.getsize(src_tga), 0, mode

    os.makedirs(os.path.dirname(dst_png), exist_ok=True)
    out.save(dst_png, format="PNG", optimize=False, compress_level=6)

    # VERIFY, do not assert. Re-open what was written and compare every pixel.
    # "PNG is lossless" is true and is not evidence that THIS file survived.
    back = Image.open(dst_png)
    a = np.asarray(out)
    b = np.asarray(back)
    if a.shape != b.shape or not np.array_equal(a, b):
        raise SystemExit("LOSSY ROUND TRIP on " + os.path.basename(src_tga) +
                         " -- refusing to continue.")

    shutil.copy2(src_tga + ".meta", dst_png + ".meta")
    return os.path.getsize(src_tga), os.path.getsize(dst_png), mode


def clear_skin(skin):
    """
    Remove the mesh, textures and materials OF ONE SKIN, so re-importing it is
    clean without disturbing the other two.

    <b>This used to clear every skin on disk, and that was correct when the tool
    was a switch.</b> It is a straight bug now that all three coexist: importing
    skin 1 would delete skins 2 and 3 and the run would report success, because
    everything it went on to copy really did copy. The filter is on the skin
    number, and meshes/textures/materials all carry it in their names.</b>

    <b>Sidecars ARE deleted here, and that is the opposite of the rule for
    props.</b> The prop rule exists because a re-forge produces a BETTER VERSION
    OF THE SAME ASSET, and 22 prop instances in AshfallDepot.unity reference it
    by GUID -- minting a fresh GUID would orphan every one of them.

    A skin switch is not that. Skin 2's mesh is a DIFFERENT MESH from skin 1's,
    with different geometry; keeping skin 1's GUID on it would be a silent
    substitution of exactly the kind that rule protects against. And nothing in
    any scene points at these directly: the only reference is Enemy.prefab, which
    ZombieImporter rebuilds from scratch on the same run.

    Animations are skin-independent and are left alone.
    """
    removed = []
    targets = []
    for f in os.listdir(DST) if os.path.isdir(DST) else []:
        if re.match(r"SK_ZombieM_02_Mesh%d\.fbx(\.meta)?$" % skin, f):
            targets.append(os.path.join(DST, f))
    for sub in ("Textures", "Materials"):
        d = os.path.join(DST, sub)
        if os.path.isdir(d):
            targets += [os.path.join(d, f) for f in os.listdir(d)
                        if ("Skin%d" % skin) in f]

    # THE LEGACY UNSUFFIXED PAIR, from back when one skin was installed at a time.
    #
    # Leaving these behind is not merely untidy, it silently corrupts the import.
    # They carry the vendor's ORIGINAL material GUIDs, so when the suffixed copy
    # arrives with the same .meta Unity sees the GUID already taken and MINTS A
    # NEW ONE for the newcomer -- while the model's externalObjects remap still
    # points at the original. The result is a zombie rendering through an orphan
    # file that no longer matches its own name, which is precisely the confusion
    # the rename exists to remove. Observed, not hypothesised: skin 2 came out
    # rendering "ZombieM_02_Body" out of ZombieM_02_Body.mat while
    # ZombieM_02_Body_Skin2.mat sat beside it unused on a freshly minted GUID.
    if skin == 2:
        for legacy in ("ZombieM_02_Body.mat", "ZombieM_02_Cloth.mat"):
            for f in (legacy, legacy + ".meta"):
                targets.append(os.path.join(DST, "Materials", f))

    for t in targets:
        if os.path.isfile(t):
            removed.append(os.path.relpath(t, DST).replace("\\", "/"))
            if not DRY:
                os.remove(t)
    return removed


def repoint_fbx_materials_at_urp(meta_path, skin):
    """
    Point the model's material remap at the URP materials instead of the Built-in
    ones. THIS is the actual "convert its materials to URP" work.

    The pack shares ONE mesh across all three render pipelines
    (Mesh/SK_ZombieM_02_Mesh1.fbx), and that mesh's importer `externalObjects`
    block remaps its two slots -- "body_" and "cloth" -- to the **Built-in**
    materials. The URP and HDRP variants do not touch the model at all; they
    override the renderer's material array on their own PREFABS.

    So importing the FBX and building a fresh prefab from it -- which is what
    ZombieImporter does, because the pack's prefab carries the vendor's own
    parameterless controller and no ragdoll -- gets the BUILT-IN materials, which
    were not copied, so every slot resolves to nothing. Unity reports no error for
    this: a missing material renders as the magenta error shader at worst and as
    Unity's default grey at best, and the import log stays clean.

    The mapping is derived, not typed: for each URP material, the same-named file
    under Buit-in/ gives the GUID to replace. Returns a list of log lines.
    """
    lines = []
    # A DRY RUN MUST NOT INSPECT THE DESTINATION AT ALL.
    #
    # It copies nothing, so the meta sitting there is either absent (a skin not
    # yet imported) or the PREVIOUS run's output, which has already had its
    # built-in GUIDs rewritten. Reading that and demanding the built-in GUID be
    # present made --dry-run fail on precisely the skin that was correctly
    # installed -- a check reporting the opposite of the truth.
    if DRY:
        return ["would repoint the model's %d material slots at the copied URP "
                "materials" % len(material_renames(skin))]
    if not os.path.exists(meta_path):
        raise SystemExit("No meta at " + meta_path)

    # built-in guid -> urp guid, matched on identical file names WITHIN THE SAME
    # SKIN. Material_1 was hardcoded here in the first version, so importing skin
    # 2 paired nothing -- and the verification below still printed "verified"
    # because it iterated an empty dict. A check that passes vacuously is worse
    # than no check, which is the single most repeated lesson in this project.
    swap = {}
    for src_name, dst_name in material_renames(skin).items():
        builtin = os.path.join(SRC, "Buit-in/Materials/Material_%d" % skin, src_name + ".meta")
        urp = os.path.join(SRC, material_dir(skin), src_name + ".meta")
        if not (os.path.exists(builtin) and os.path.exists(urp)):
            raise SystemExit("Could not pair " + src_name + " across Buit-in/ and URP/ for skin " +
                             str(skin) + " -- refusing to leave the model pointing at materials "
                             "that were not copied.")
        swap[guid_of(builtin)] = (guid_of(urp), dst_name)

    if not swap:
        raise SystemExit("Nothing to remap -- the material pairing produced no entries.")

    body = open(meta_path, encoding="utf-8").read()
    for old, (new, dst_name) in swap.items():
        if old not in body:
            raise SystemExit("Built-in material guid " + old + " is not in the model's "
                             "externalObjects. The pack's remap has changed shape and this "
                             "script's assumption is wrong -- fix it rather than shipping a "
                             "model with unresolved materials.")
        body = body.replace(old, new)
        lines.append("material slot repointed " + old[:8] + ".. -> " + new[:8] +
                     ".. (" + dst_name + ")")

    if DRY:
        return lines

    with open(meta_path, "w", encoding="utf-8", newline="") as fh:
        fh.write(body)

    # VERIFY THE END STATE, not the absence of specific old strings. Read the
    # file back and require that the externalObjects material GUIDs are exactly
    # the URP ones that were copied -- so this cannot pass by doing nothing.
    check = open(meta_path, encoding="utf-8").read()
    block = re.search(r"externalObjects:(.*?)\n  materials:", check, re.S)
    if not block:
        raise SystemExit("No externalObjects block in the model meta after the remap.")
    found = set(re.findall(r"guid: ([a-f0-9]{32})", block.group(1)))
    expected = {new for new, _ in swap.values()}
    if found != expected:
        raise SystemExit("Model meta externalObjects resolve to " + str(sorted(found)) +
                         " but the copied URP materials are " + str(sorted(expected)) + ".")
    lines.append("verified: the model's %d material slots resolve to the copied URP materials "
                 "and nothing else." % len(expected))
    return lines


def import_skin(skin, already, by_guid):
    """
    Carry one skin across. Returns (tga_bytes, png_bytes, other_bytes).

    Animations are NOT copied here -- they are shared by all three skins and are
    done once by main(). Copying them per skin would be harmless but would report
    the same 10 files three times, and a log that overstates what happened is how
    this project has repeatedly ended up believing work was done twice.
    """
    log("")
    log("=== skin %d  (%s) ===" % (skin, SKIN_NAMES[skin]))

    # ---- materials, and the textures they actually bind --------------------
    wanted_tex = {}
    mat_plan = []
    for src_name, dst_name in material_renames(skin).items():
        src_mat = os.path.join(SRC, material_dir(skin), src_name)
        if not os.path.exists(src_mat):
            raise SystemExit("Missing material " + src_mat)
        body = open(src_mat, encoding="utf-8").read()
        # Read the bindings out of the material rather than listing textures by
        # hand: the material is the only thing that actually knows, and a
        # hand-written list silently drops a map the day the material changes.
        for guid in set(re.findall(r"guid: ([a-f0-9]{32})", body)):
            if guid in by_guid:
                wanted_tex[guid] = by_guid[guid]
        mat_plan.append((src_mat, os.path.join(DST, "Materials", dst_name), body))

    # ---- GUID collision check ---------------------------------------------
    incoming = dict(wanted_tex)
    for src_mat, _, _ in mat_plan:
        incoming[guid_of(src_mat + ".meta")] = src_mat
    incoming[guid_of(os.path.join(SRC, "Mesh", mesh_name(skin)) + ".meta")] = mesh_name(skin)

    clashes = [(g, already[g], incoming[g]) for g in incoming if g in already]
    if clashes:
        for g, mine, theirs in clashes:
            log("GUID CLASH " + g + ": " + mine + "  vs  " + theirs)
        raise SystemExit("Refusing to import -- a carried-over GUID already "
                         "exists in this project and copying would corrupt it.")

    stale = clear_skin(skin)
    if stale:
        log("cleared %d stale file(s) belonging to skin %d:" % (len(stale), skin))
        for s in stale[:4]:
            log("  " + s)
        if len(stale) > 4:
            log("  ... and %d more" % (len(stale) - 4))

    tga_bytes = png_bytes = other_bytes = 0

    log("textures (%d bound by the two materials):" % len(wanted_tex))
    for guid in sorted(wanted_tex, key=lambda g: os.path.basename(wanted_tex[g])):
        src_tga = wanted_tex[guid]
        dst_png = os.path.join(DST, "Textures",
                               os.path.basename(src_tga)[:-4] + ".png")
        a, b, mode = convert_texture(src_tga, dst_png)
        tga_bytes += a
        png_bytes += b
        # Say "verified" only when a verification actually ran. A dry run does
        # not convert anything, so claiming it did is the shape of report this
        # project keeps having to un-learn.
        note = "would convert" if DRY else "verified identical"
        log("  %-50s %6.1f -> %6.1f MB  (%s, %s)"
            % (os.path.basename(src_tga), a / 1048576, b / 1048576, mode, note))

    log("materials:")
    for src_mat, dst_mat, body in mat_plan:
        if not DRY:
            os.makedirs(os.path.dirname(dst_mat), exist_ok=True)
            # m_Name must match the new file name or the inspector shows the old
            # one, which would re-plant the exact confusion this rename removes.
            new_name = os.path.splitext(os.path.basename(dst_mat))[0]
            body = re.sub(r"^(  m_Name: ).*$", r"\g<1>" + new_name, body, count=1, flags=re.M)
            with open(dst_mat, "w", encoding="utf-8", newline="") as fh:
                fh.write(body)
            shutil.copy2(src_mat + ".meta", dst_mat + ".meta")
        other_bytes += os.path.getsize(src_mat)
        log("  %-42s -> %s" % (os.path.basename(src_mat), os.path.basename(dst_mat)))

    log("mesh:")
    other_bytes += copy_with_meta(os.path.join(SRC, "Mesh", mesh_name(skin)),
                                  os.path.join(DST, mesh_name(skin)))
    log("  " + mesh_name(skin))
    for line in repoint_fbx_materials_at_urp(os.path.join(DST, mesh_name(skin)) + ".meta", skin):
        log("    " + line)

    return tga_bytes, png_bytes, other_bytes


def main():
    if not os.path.isdir(SRC):
        raise SystemExit("Source pack not found at " + SRC)

    log("source: " + SRC)
    log("target: " + DST)
    log("skins:  " + ", ".join("%d (%s)" % (s, SKIN_NAMES[s]) for s in SKINS))
    if DRY:
        log("DRY RUN -- nothing will be written.")

    by_guid = index_source_textures()
    already = existing_guids_in_project()

    tga_bytes = png_bytes = other_bytes = 0
    for skin in SKINS:
        a, b, c = import_skin(skin, already, by_guid)
        tga_bytes += a
        png_bytes += b
        other_bytes += c

    # ---- animations, ONCE, because every skin shares them ------------------
    #
    # The three zombies are three different MESHES but one SKELETON, and the
    # clips are Generic -- they bind by transform path, so the same ten FBXs
    # drive all three. Copying them per skin would re-copy identical GUIDs over
    # themselves; doing it once is both cheaper and honest in the log.
    log("")
    log("animations (%d of the 22 the pack ships, shared by every skin):" % len(ANIMATIONS))
    for a in ANIMATIONS:
        f = "Anim_ZombieM_02@" + a + ".fbx"
        src = os.path.join(SRC, "Animations", f)
        g = guid_of(src + ".meta")
        if g in already:
            raise SystemExit("GUID CLASH on " + f + ": " + already[g] +
                             " already uses " + g + ".")
        other_bytes += copy_with_meta(src, os.path.join(DST, "Animations", f))
        log("  " + a)

    log("")
    log("textures  %.0f MB of TGA  ->  %.0f MB of PNG   (lossless, pixel-verified)"
        % (tga_bytes / 1048576, png_bytes / 1048576))
    log("meshes + animations + materials             %.0f MB" % (other_bytes / 1048576))
    log("TOTAL CARRIED                               %.0f MB   (the full pack is 1708 MB)"
        % ((png_bytes + other_bytes) / 1048576))
    log("")
    log("Next: Unity must import these, then Tools > Aftermarket > Import Zombie "
        "builds the controller, the ragdoll and one Enemy prefab per skin.")


if __name__ == "__main__":
    main()
