"""
Shadow — headless Blender mesh pipeline.

Turns any mesh Shadow can produce or fetch (PLY from the forges, OBJ, GLB, FBX,
STL, or a generated high-poly from an image-to-3D model) into a game-ready
asset: triangle-budgeted, UV-unwrapped, optionally normal-baked, exported as GLB
with optional LODs.

Run it, never import it:

    blender --background --factory-startup --python mesh_pipeline.py -- \
        --input  in.ply \
        --output out.glb \
        --target-tris 3000 \
        --bake \
        --lods 0.5,0.2

Everything after the bare `--` is ours; Blender eats everything before it.
`--factory-startup` matters: it ignores the user's addons and preferences, so a
plugin the owner installed last week cannot change what this produces.

The LAST line of stdout is a single JSON object. Parse that, ignore the rest —
Blender writes a lot of noise to stdout and none of it is ours.

WHY THIS EXISTS AS A SCRIPT AND NOT A LIBRARY CALL: `bpy` is only importable
inside Blender. Shipping it as a wheel is a 200 MB dependency that breaks on
every Blender version bump. Spawning the Blender the user already has installed
costs one process and survives upgrades.
"""

import json
import math
import os
import sys
import traceback

import bpy  # noqa: E402  (only exists inside Blender)


# --------------------------------------------------------------------------
# argument parsing
# --------------------------------------------------------------------------

def parse_args(argv):
    """Minimal parser. argparse works here, but its error path calls sys.exit
    with a bare message and Blender swallows it — we want structured failure."""
    if "--" in argv:
        argv = argv[argv.index("--") + 1:]
    else:
        argv = []

    args = {
        "input": None,
        "output": None,
        "target_tris": 0,          # 0 = don't decimate
        "bake": False,
        "bake_size": 1024,
        "lods": [],                # extra ratios, e.g. [0.5, 0.2]
        "smooth_angle": 30.0,      # degrees; 0 disables auto-smooth
        "center": True,            # move to origin
        "normalize_scale": 0.0,    # 0 = leave scale alone, else fit largest axis
        "engine": "generic",       # generic | unity | roblox
    }

    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--input":            args["input"] = argv[i + 1]; i += 2
        elif a == "--output":         args["output"] = argv[i + 1]; i += 2
        elif a == "--target-tris":    args["target_tris"] = int(argv[i + 1]); i += 2
        elif a == "--bake":           args["bake"] = True; i += 1
        elif a == "--bake-size":      args["bake_size"] = int(argv[i + 1]); i += 2
        elif a == "--lods":           args["lods"] = [float(x) for x in argv[i + 1].split(",") if x.strip()]; i += 2
        elif a == "--smooth-angle":   args["smooth_angle"] = float(argv[i + 1]); i += 2
        elif a == "--no-center":      args["center"] = False; i += 1
        elif a == "--normalize-scale": args["normalize_scale"] = float(argv[i + 1]); i += 2
        elif a == "--engine":         args["engine"] = argv[i + 1].strip().lower(); i += 2
        else:
            raise ValueError("Unknown argument: " + a)
    return args


# --------------------------------------------------------------------------
# scene helpers
# --------------------------------------------------------------------------

def wipe_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)


IMPORTERS = {
    ".ply":  lambda p: bpy.ops.wm.ply_import(filepath=p),
    ".obj":  lambda p: bpy.ops.wm.obj_import(filepath=p),
    ".stl":  lambda p: bpy.ops.wm.stl_import(filepath=p),
    ".glb":  lambda p: bpy.ops.import_scene.gltf(filepath=p),
    ".gltf": lambda p: bpy.ops.import_scene.gltf(filepath=p),
    ".fbx":  lambda p: bpy.ops.import_scene.fbx(filepath=p),
    ".dae":  lambda p: bpy.ops.wm.collada_import(filepath=p),
}


def import_mesh(path):
    ext = os.path.splitext(path)[1].lower()
    if ext not in IMPORTERS:
        raise ValueError(
            "Unsupported input '%s'. Supported: %s" % (ext, ", ".join(sorted(IMPORTERS)))
        )
    IMPORTERS[ext](path)
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    if not meshes:
        raise ValueError("No mesh objects found in " + path)
    return meshes


def join_meshes(meshes):
    """One object out, always. Downstream steps (decimate ratio, bake, LOD naming)
    are all single-object operations, and a generated mesh often arrives as a
    dozen disconnected shells."""
    bpy.ops.object.select_all(action="DESELECT")
    for m in meshes:
        m.select_set(True)
    bpy.context.view_layer.objects.active = meshes[0]
    if len(meshes) > 1:
        bpy.ops.object.join()
    return bpy.context.view_layer.objects.active


def tri_count(obj):
    """Triangles, not polygons. An n-gon is (n-2) triangles once it reaches a GPU,
    so counting faces here would under-report by a wide margin on quad meshes."""
    return sum(len(p.vertices) - 2 for p in obj.data.polygons)


def apply_transforms(obj):
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)


def center_and_scale(obj, do_center, fit_size):
    if do_center:
        bpy.ops.object.origin_set(type="ORIGIN_GEOMETRY", center="BOUNDS")
        obj.location = (0.0, 0.0, 0.0)
    if fit_size and fit_size > 0:
        dims = obj.dimensions
        largest = max(dims.x, dims.y, dims.z)
        if largest > 0:
            f = fit_size / largest
            obj.scale = (f, f, f)
            apply_transforms(obj)


def cleanup_geometry(obj):
    """Generated meshes routinely arrive with doubled vertices, zero-area faces and
    inconsistent winding. Every later step behaves better after this, and the
    decimator in particular produces visibly worse silhouettes on a mesh with
    duplicate verts because it cannot collapse across the seam."""
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.mesh.remove_doubles(threshold=1e-5)
    bpy.ops.mesh.delete_loose()
    bpy.ops.mesh.normals_make_consistent(inside=False)
    bpy.ops.object.mode_set(mode="OBJECT")


def flatness(obj):
    """Smallest bounding dimension over the largest.

    Catches a single flat sheet -- the forge's first barrel was
    0.0078 x 1.0117 x 0.786 m, flatness 0.008 -- but see solidity() for why this
    is not sufficient on its own.
    """
    dims = sorted(float(d) for d in obj.dimensions)
    return dims[0] / dims[2] if dims[2] > 0 else 0.0


def solidity(obj):
    """Enclosed volume as a fraction of the bounding box.

    flatness() ALONE IS A LIAR and this exists because it got caught doing it.
    It compares bounding-box dimensions, so the moment a mesh has any extent on
    all three axes it passes -- and two perpendicular flat plates in an L have
    exactly that. The re-forged barrel scored flatness 0.658, sailed through, and
    was recorded in props.json as `"degenerate": false` while being two sheets.
    A bounding box fundamentally cannot see this failure.

    Volume can. A solid prop fills a real fraction of its box: a cylinder is
    pi/4 = 0.785 of the box it sits in, a chunky irregular object 0.3-0.6. Two
    thin plates enclose almost nothing, so the ratio collapses toward zero
    regardless of how well they span the box.

    Uses bmesh.calc_volume(), which is the divergence-theorem sum over faces, so
    it is meaningful for a closed mesh and merely small for an open one -- either
    way a sheet scores near zero, which is the answer we want.
    """
    import bmesh
    bm = bmesh.new()
    try:
        bm.from_mesh(obj.data)
        volume = abs(bm.calc_volume(signed=True))
    finally:
        bm.free()

    d = obj.dimensions
    box = float(d.x) * float(d.y) * float(d.z)
    return volume / box if box > 0 else 0.0


def decimate_to(obj, target_tris, max_step=0.5):
    """Quadric-error collapse. `ratio` is a fraction of FACES, but our budget is in
    triangles, so convert through the current triangle count -- otherwise a quad
    mesh lands at roughly half the requested budget.

    STAGED, not one shot. Collapse decimation scores each edge by the error it
    would introduce *against the mesh it is currently holding*, so asking for a
    99.7% cut in a single pass (349,776 -> 1,200 on the crate) makes every
    catastrophic collapse simultaneously, each judged against a surface that has
    not yet been simplified. Halving repeatedly and applying each stage means
    every stage re-scores against what the previous stage actually produced.
    Same budget, same modifier, better shape.
    """
    current = tri_count(obj)
    if target_tris <= 0 or current <= target_tris:
        return current, 1.0, 0

    overall = target_tris / float(current)
    stages = 0
    while tri_count(obj) > target_tris and stages < 16:
        current = tri_count(obj)
        ratio = max(target_tris / float(current), max_step)
        if ratio >= 0.999:
            break

        mod = obj.modifiers.new(name="ShadowDecimate%d" % stages, type="DECIMATE")
        mod.decimate_type = "COLLAPSE"
        mod.ratio = ratio
        mod.use_collapse_triangulate = True
        bpy.context.view_layer.objects.active = obj
        bpy.ops.object.modifier_apply(modifier=mod.name)
        stages += 1

    return tri_count(obj), overall, stages


def unwrap(obj, angle_limit=66.0, island_margin=0.02):
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.uv.smart_project(angle_limit=math.radians(angle_limit),
                             island_margin=island_margin)
    bpy.ops.object.mode_set(mode="OBJECT")


def shade_smooth(obj, angle_deg):
    if angle_deg <= 0:
        return
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.shade_smooth()
    # Blender 4.1 removed mesh.use_auto_smooth in favour of a modifier.
    try:
        bpy.ops.object.modifier_add(type="SMOOTH_BY_ANGLE")
        obj.modifiers[-1]["Input_1"] = math.radians(angle_deg)
    except Exception:
        mesh = obj.data
        if hasattr(mesh, "use_auto_smooth"):
            mesh.use_auto_smooth = True
            mesh.auto_smooth_angle = math.radians(angle_deg)


# --------------------------------------------------------------------------
# normal baking (high -> low)
# --------------------------------------------------------------------------

def bake_normals(high, low, size, out_dir, base_name):
    """Project the high-poly surface onto the low-poly as a tangent-space normal
    map. This is the step that makes a 3k-triangle prop still read as detailed:
    the bumps move from geometry into a texture.

    Fails soft — a missing bake is a cosmetic loss, not a broken asset, and
    Cycles can legitimately run out of VRAM on a dense source mesh.
    """
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 8            # normal bakes are not noisy; 8 is plenty
    scene.cycles.use_denoising = False
    try:
        scene.cycles.device = "GPU"
    except Exception:
        pass

    img_path = os.path.join(out_dir, base_name + "_normal.png")
    img = bpy.data.images.new(base_name + "_normal", width=size, height=size,
                              alpha=False, float_buffer=False)
    img.colorspace_settings.name = "Non-Color"   # normal maps are DATA, not colour

    mat = bpy.data.materials.new(base_name + "_bakemat")
    mat.use_nodes = True
    node = mat.node_tree.nodes.new("ShaderNodeTexImage")
    node.image = img
    mat.node_tree.nodes.active = node

    low.data.materials.clear()
    low.data.materials.append(mat)

    bpy.ops.object.select_all(action="DESELECT")
    high.select_set(True)
    low.select_set(True)
    bpy.context.view_layer.objects.active = low   # active == the target

    scene.render.bake.use_selected_to_active = True
    scene.render.bake.cage_extrusion = 0.05
    scene.render.bake.max_ray_distance = 0.2
    scene.render.bake.margin = 8

    bpy.ops.object.bake(type="NORMAL", use_clear=True)

    img.filepath_raw = img_path
    img.file_format = "PNG"
    img.save()

    # Re-wire so the exported GLB actually references the map we just baked.
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    if bsdf:
        nm = mat.node_tree.nodes.new("ShaderNodeNormalMap")
        mat.node_tree.links.new(node.outputs["Color"], nm.inputs["Color"])
        mat.node_tree.links.new(nm.outputs["Normal"], bsdf.inputs["Normal"])

    return img_path


def export_fbx(path):
    """Unity's native format. Added in this repo's copy -- Shadow's version has no
    FBX path at all and sends Unity a GLB, which Unity 6 cannot read.

    apply_scale_options='FBX_SCALE_NONE' keeps 1 Blender unit == 1 metre == 1 Unity
    unit. bake_space_transform converts Blender's Z-up to Unity's Y-up at export so
    the prop is not lying on its face when it lands in the scene."""
    bpy.ops.export_scene.fbx(
        filepath=path,
        use_selection=True,
        apply_unit_scale=True,
        apply_scale_options="FBX_SCALE_NONE",
        bake_space_transform=True,
        axis_forward="-Z",
        axis_up="Y",
        mesh_smooth_type="FACE",
        use_mesh_modifiers=True,
        path_mode="COPY",
        embed_textures=True,
    )


def export_glb(path):
    bpy.ops.export_scene.gltf(
        filepath=path,
        export_format="GLB",
        use_selection=True,
        export_apply=True,
        export_yup=True,        # Unity and Roblox are both Y-up; Blender is Z-up
    )


# --------------------------------------------------------------------------
# engine targets
# --------------------------------------------------------------------------

# Roblox rejects a MeshPart over this. It is a hard platform limit, not a
# guideline — an over-budget mesh fails at import with a message that does not
# name the number, so enforce it here where we can say something useful.
ROBLOX_MAX_TRIS = 10000

# 1 stud = 0.28 m. Roblox's importer has no reliable "metres" mode, so a mesh
# authored in metres arrives roughly 3.5x too small and every prop needs hand
# rescaling in Studio.
STUDS_PER_METRE = 1.0 / 0.28


def engine_profile(engine):
    """Per-engine export rules. Returns (extension, max_tris, scale_factor)."""
    if engine == "roblox":
        return ".obj", ROBLOX_MAX_TRIS, STUDS_PER_METRE
    if engine == "unity":
        # LOCAL CHANGE from Shadow's copy of this script, and the reason this file
        # was copied into the repo rather than referenced.
        #
        # Shadow's version returns ".glb" here with a comment saying "Unity imports
        # GLB/FBX directly". That is true of FBX and FALSE of GLB. Unity 6 ships no
        # glTF importer -- there is none in Packages/manifest.json and none in
        # 6000.3.1f1's BuiltInPackages -- so a .glb dropped into Assets/ becomes an
        # unrecognised DefaultAsset with no mesh, no material, and NO IMPORT ERROR.
        # It looks like it worked until you try to place it.
        #
        # FBX imports natively with zero extra packages. Unity works in metres, so
        # no rescale.
        return ".fbx", 0, 1.0
    return ".glb", 0, 1.0


def export_obj(path):
    """Roblox Studio imports .obj and .fbx. OBJ is chosen because it is plain
    text, has no version drift, and carries the UVs and material reference we
    need — an FBX exporter version mismatch is a class of bug worth avoiding."""
    bpy.ops.wm.obj_export(
        filepath=path,
        export_selected_objects=True,
        export_materials=True,
        export_uv=True,
        export_normals=True,
        export_triangulated_mesh=True,   # Roblox triangulates anyway; do it predictably
        forward_axis="NEGATIVE_Z",
        up_axis="Y",
    )


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------

def main():
    result = {"ok": False}
    try:
        args = parse_args(list(sys.argv))
        if not args["input"] or not args["output"]:
            raise ValueError("--input and --output are required")
        if not os.path.isfile(args["input"]):
            raise ValueError("Input not found: " + args["input"])

        out_dir = os.path.dirname(os.path.abspath(args["output"])) or "."
        os.makedirs(out_dir, exist_ok=True)
        base_name = os.path.splitext(os.path.basename(args["output"]))[0]

        ext, max_tris, scale_factor = engine_profile(args["engine"])
        notes = []

        # Force the output extension to match the engine rather than trusting the
        # caller: an .obj named .glb imports as nothing, silently.
        out_path = os.path.join(out_dir, base_name + ext)

        # A platform cap is not a suggestion. Clamp rather than fail — the caller
        # asked for an asset, and a 10k version of it is still the asset.
        if max_tris:
            if args["target_tris"] <= 0 or args["target_tris"] > max_tris:
                notes.append(
                    "target_tris clamped to %d for %s" % (max_tris, args["engine"])
                )
                args["target_tris"] = max_tris

        wipe_scene()
        source = join_meshes(import_mesh(args["input"]))
        apply_transforms(source)
        cleanup_geometry(source)
        center_and_scale(source, args["center"], args["normalize_scale"])

        source_tris = tri_count(source)

        # Keep an untouched copy to bake FROM before the original is decimated.
        high = None
        if args["bake"] and args["target_tris"] > 0:
            high = source.copy()
            high.data = source.data.copy()
            bpy.context.collection.objects.link(high)
            high.name = base_name + "_high"

        # Measured BEFORE any budgeting, so the answer is about what the generator
        # produced rather than about what this script did to it. The forge's first
        # six props were blamed on the decimation ratio; re-measuring the raw meshes
        # showed the barrel was already a 7.8 mm sheet at 52,648 triangles, i.e.
        # born flat, and decimation was innocent for that one.
        source_flatness = flatness(source)
        source_solidity = solidity(source)

        # Two independent tests, because the first one alone was fooled. flatness
        # catches a single sheet; solidity catches an assembly of sheets that
        # happens to span its bounding box (an L of two plates scored flatness
        # 0.658 and passed while enclosing almost no volume).
        degenerate_reasons = []
        if source_flatness < 0.02:
            degenerate_reasons.append(
                "flatness %.4f (thinnest bounding dimension is %.1f%% of the "
                "longest) - this is a sheet" % (source_flatness, source_flatness * 100.0))
        # 0.20, calibrated against measured shapes rather than guessed:
        #   solid cylinder            0.780  (theory pi/4 = 0.785, so the metric is right)
        #   two perpendicular plates  0.039  (flatness gives this 0.990 -- top marks)
        #   the re-forged barrel      0.109
        # An open-frame prop (pallet stack, fence, chair) can legitimately land
        # near this line, which is fine: this raises a flag for the contact sheet,
        # it does not refuse the asset. The render is still the real check.
        if source_solidity < 0.20:
            degenerate_reasons.append(
                "solidity %.4f (encloses only %.1f%% of its bounding box; a solid "
                "prop is 0.3-0.8) - this is hollow or made of plates"
                % (source_solidity, source_solidity * 100.0))

        if degenerate_reasons:
            notes.append("DEGENERATE SOURCE: " + "; ".join(degenerate_reasons) +
                         ". Budgeting and exporting this produces a valid-looking "
                         "asset that is unusable.")

        final_tris, ratio, stages = decimate_to(source, args["target_tris"])
        if stages > 1:
            notes.append("decimated in %d stages" % stages)
        unwrap(source)
        shade_smooth(source, args["smooth_angle"])

        normal_map = None
        bake_error = None
        if high is not None:
            try:
                normal_map = bake_normals(high, source, args["bake_size"], out_dir, base_name)
            except Exception as exc:
                bake_error = str(exc)
            finally:
                bpy.data.objects.remove(high, do_unlink=True)

        # Engine unit conversion, applied last so the triangle work above is
        # unaffected by scale.
        if scale_factor != 1.0:
            source.scale = (scale_factor, scale_factor, scale_factor)
            apply_transforms(source)
            notes.append("scaled x%.3f into %s units" % (scale_factor, args["engine"]))

        bpy.ops.object.select_all(action="DESELECT")
        source.select_set(True)
        bpy.context.view_layer.objects.active = source
        if ext == ".obj":
            export_obj(out_path)
        elif ext == ".fbx":
            export_fbx(out_path)
        else:
            export_glb(out_path)
        args["output"] = out_path

        # LODs: same collapse at lower budgets. Cheap once the mesh is already clean.
        lods = []
        for idx, lod_ratio in enumerate(args["lods"], start=1):
            budget = max(4, int(final_tris * lod_ratio))
            lod = source.copy()
            lod.data = source.data.copy()
            bpy.context.collection.objects.link(lod)
            got, _, _ = decimate_to(lod, budget)
            lod_path = os.path.join(out_dir, "%s_LOD%d%s" % (base_name, idx, ext))
            bpy.ops.object.select_all(action="DESELECT")
            lod.select_set(True)
            bpy.context.view_layer.objects.active = lod
            # Same three-way dispatch as the main export. Missing the .fbx branch
            # here silently produced "name_LOD1.fbx.glb" -- the gltf exporter
            # appends its own extension -- i.e. LOD files Unity cannot read, named
            # as if it could.
            if ext == ".obj":
                export_obj(lod_path)
            elif ext == ".fbx":
                export_fbx(lod_path)
            else:
                export_glb(lod_path)
            lods.append({"level": idx, "path": lod_path, "tris": got})
            bpy.data.objects.remove(lod, do_unlink=True)

        result = {
            "ok": True,
            "input": args["input"],
            "output": args["output"],
            "engine": args["engine"],
            "sourceTris": source_tris,
            "finalTris": final_tris,
            "decimateRatio": round(ratio, 5),
            "decimateStages": stages,
            "sourceFlatness": round(source_flatness, 5),
            "sourceSolidity": round(source_solidity, 5),
            "degenerate": bool(degenerate_reasons),
            "degenerateReasons": degenerate_reasons,
            "normalMap": normal_map,
            "bakeError": bake_error,
            "lods": lods,
            "notes": notes,
            "withinEngineLimits": (max_tris == 0) or (final_tris <= max_tris),
            "bytes": os.path.getsize(args["output"]) if os.path.isfile(args["output"]) else 0,
        }

    except Exception as exc:
        result = {"ok": False, "error": str(exc), "trace": traceback.format_exc()}

    # Sentinel + JSON on the final line. Blender's own chatter is unpredictable,
    # so the caller looks for this marker rather than trying to parse all stdout.
    print("__SHADOW_MESH_RESULT__" + json.dumps(result))
    sys.stdout.flush()


if __name__ == "__main__":
    main()
