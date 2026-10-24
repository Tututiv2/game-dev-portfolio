"""blend_probe.py - look inside a downloaded .blend before trusting a single thing about it.

A downloaded asset is an unknown. It can be 300k tris, in centimetres, with its
scale unapplied, its textures missing, and its floor 40 m from the origin - and
Blender will open it and show you a lovely corridor either way. Unity will not
say a word about any of it. This measures the things that decide whether a mesh
can become a playable map module.

Run:
  blender --background --factory-startup --python Tools/map/blend_probe.py -- \
      --blend "C:/path/thing.blend" [--json out.json] [--render DIR] [--fbx OUT.fbx]

Everything it prints is measured off the mesh. Nothing is inferred from names,
except where a name is reported AS a name.
"""

import bpy, bmesh, sys, os, json, math
from mathutils import Vector

GRID = 4.0        # the project's cell, metres  (MapKitBuilder.Grid)
STOREY = 4.0      # wall height / floor-to-floor (MapKitBuilder.WallHeight)
EYE = 1.7         # player eye height, for the walkability read
STEP_OFFSET = 0.30  # Unity CharacterController default
SLOPE_LIMIT = 45.0  # Unity default, and the navmesh bake default
PLAYER_HEIGHT = 1.8  # capsule height - clearance below this is not standable


# ---- args -----------------------------------------------------------------

def argv_after_dashes():
    return sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []


def parse_args():
    a = argv_after_dashes()
    out = {"blend": None, "json": None, "render": None, "fbx": None, "fixture": False}
    i = 0
    while i < len(a):
        if a[i] == "--fixture":
            out["fixture"] = True
            i += 1
        elif a[i].startswith("--") and i + 1 < len(a):
            out[a[i][2:]] = a[i + 1]
            i += 2
        else:
            i += 1
    return out


# ---- geometry helpers -----------------------------------------------------

def world_tris(obj, depsgraph):
    """Evaluated, triangulated, WORLD-space bmesh. Modifiers included, because a
    mirror or array modifier is real geometry an un-evaluated read misses."""
    ev = obj.evaluated_get(depsgraph)
    try:
        me = ev.to_mesh()
    except RuntimeError:
        return None
    bm = bmesh.new()
    bm.from_mesh(me)
    bm.transform(obj.matrix_world)
    bmesh.ops.triangulate(bm, faces=bm.faces[:])
    ev.to_mesh_clear()
    return bm


def raw_bmesh(obj):
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    return bm


def horizontal_bands(bm, normal_tol=0.94, min_area=0.05):
    """Area-weighted histogram of upward-facing faces by height.

    This is how you find floors, landings and stair treads without believing a
    single object name. A step tread and a floor differ only in area."""
    bands = {}
    for f in bm.faces:
        if f.normal.z < normal_tol:
            continue
        area = f.calc_area()
        if area < min_area:
            continue
        z = sum(v.co.z for v in f.verts) / len(f.verts)
        key = round(round(z / 0.01) * 0.01, 2)   # 1 cm bins. At 5 cm a 0.18 m stair
        #                                          rise bins alternately to 0.15 and
        #                                          0.20, and no staircase is ever found.
        b = bands.setdefault(key, {"area": 0.0, "faces": 0,
                                   "min": [1e9, 1e9], "max": [-1e9, -1e9]})
        b["area"] += area
        b["faces"] += 1
        for v in f.verts:
            b["min"][0] = min(b["min"][0], v.co.x)
            b["min"][1] = min(b["min"][1], v.co.y)
            b["max"][0] = max(b["max"][0], v.co.x)
            b["max"][1] = max(b["max"][1], v.co.y)
    return bands


def read_stairs(bands):
    """Evenly-spaced small-area bands ARE a staircase. Report rise and run.

    A downloaded staircase whose rise beats Unity's 0.30 m step offset is a
    staircase the player cannot climb, and nothing in Unity will say so."""
    zs = sorted(bands.keys())
    runs, cur = [], []
    for i in range(len(zs) - 1):
        d = round(zs[i + 1] - zs[i], 2)
        if 0.08 <= d <= 0.45:
            if cur and abs(d - cur[-1][1]) > 0.03:
                runs.append(cur)
                cur = []
            cur.append((zs[i], d))
        elif cur:
            runs.append(cur)
            cur = []
    if cur:
        runs.append(cur)

    out = []
    for r in runs:
        if len(r) < 3:
            continue
        rise = sum(d for _, d in r) / len(r)
        # The FIRST band of a run is usually the floor the stairs leave from, and a
        # floor is metres deep. Measuring the tread there reported a 3.00 m going
        # and a 3.4 deg staircase. Median over the run, first band excluded.
        spans = []
        for z, _ in (r[1:] or r):
            b = bands[z]
            spans.append(min(abs(b["max"][0] - b["min"][0]),
                             abs(b["max"][1] - b["min"][1])))
        tread = round(sorted(spans)[len(spans) // 2], 3) if spans else 0.0
        out.append({
            "risers": len(r),
            "bottom_z": round(r[0][0], 3),
            "top_z": round(r[-1][0] + r[-1][1], 3),
            "rise_m": round(rise, 3),
            "tread_m": tread,
            "angle_deg": round(math.degrees(math.atan2(rise, tread)), 1) if tread else None,
            "total_climb_m": round(r[-1][0] + r[-1][1] - r[0][0], 3),
        })
    return out


def wall_span_at(bm, z, tol=0.6):
    """Footprint of vertical faces at a given height - the head-height read."""
    xs, ys = [], []
    for f in bm.faces:
        if abs(f.normal.z) > 0.5:
            continue
        zc = sum(v.co.z for v in f.verts) / len(f.verts)
        if abs(zc - z) > tol:
            continue
        for v in f.verts:
            xs.append(v.co.x)
            ys.append(v.co.y)
    if not xs:
        return None
    return {"x_span": round(max(xs) - min(xs), 2), "y_span": round(max(ys) - min(ys), 2)}


# ---- scene-level space probe ----------------------------------------------
#
# The per-object read above cannot answer the only question that matters, because
# a staircase is often 16 separate objects and a corridor is a floor object plus
# two wall objects. Whether a PLAYER fits is a property of the space between
# meshes, so it has to be measured against the whole scene at once.

def column_profile(scn, depsgraph, x, y, top, bottom, max_hits=12):
    """Every surface in one vertical column, top down. Returns [(z, normal_z)].

    Cast, step just past the hit, cast again. This is what makes a two-storey
    read possible - a single downward cast finds the roof and stops, and reports
    a building as having one floor."""
    hits = []
    z = top
    for _ in range(max_hits):
        ok, loc, nrm, _idx, _ob, _m = scn.ray_cast(
            depsgraph, Vector((x, y, z)), Vector((0, 0, -1)), distance=z - bottom)
        if not ok:
            break
        hits.append((loc.z, nrm.z))
        z = loc.z - 1e-3
        if z <= bottom:
            break
    return hits


def space_probe(step_hint=None):
    """Walkable floor area, headroom, and open width - measured by ray cast.

    Reports the thing you actually want to know about a downloaded corridor:
    how wide is it, how much headroom, and how much of it can a 1.8 m capsule
    stand up in."""
    scn = bpy.context.scene
    depsgraph = bpy.context.evaluated_depsgraph_get()
    mn, mx = scene_bounds()
    span_x, span_y = mx.x - mn.x, mx.y - mn.y
    if span_x <= 0 or span_y <= 0:
        return None
    step = step_hint or max(0.25, max(span_x, span_y) / 160.0)
    nx = max(1, int(span_x / step))
    ny = max(1, int(span_y / step))
    top, bottom = mx.z + 1.0, mn.z - 1.0

    # grid[iy][ix] = True if a capsule can stand on SOME level in that column
    stand = [[False] * nx for _ in range(ny)]
    levels = []          # every standable spot
    headrooms = []       # ENCLOSED spots only - see below
    for iy in range(ny):
        y = mn.y + (iy + 0.5) * step
        for ix in range(nx):
            x = mn.x + (ix + 0.5) * step
            hits = column_profile(scn, depsgraph, x, y, top, bottom)
            for i, (hz, hn) in enumerate(hits):
                if hn < 0.7:                       # not an upward-facing floor
                    continue
                ceil_z = hits[i - 1][0] if i > 0 else None
                enclosed = ceil_z is not None
                head = (ceil_z - hz) if enclosed else 99.0
                if head >= PLAYER_HEIGHT:
                    stand[iy][ix] = True
                    levels.append({"z": round(hz, 2), "head": round(head, 2),
                                   "enclosed": enclosed})
                    # A roof is standable and is NOT a room. Mixing the two put a
                    # 99 m "headroom" into the median and hid the real ceiling.
                    if enclosed:
                        headrooms.append(head)
    if not levels:
        return {"step_m": round(step, 3), "standable_cells": 0,
                "note": "no column in this file has PLAYER_HEIGHT of clear space above a floor"}

    def runs(grid, w, h, along_x):
        """Longest contiguous open run through each cell - i.e. the local width."""
        out = []
        if along_x:
            for r in range(h):
                run = 0
                for c in range(w):
                    if grid[r][c]:
                        run += 1
                    elif run:
                        out.append(run); run = 0
                if run:
                    out.append(run)
        else:
            for c in range(w):
                run = 0
                for r in range(h):
                    if grid[r][c]:
                        run += 1
                    elif run:
                        out.append(run); run = 0
                if run:
                    out.append(run)
        return out

    rx = runs(stand, nx, ny, True)
    ry = runs(stand, nx, ny, False)

    def med(v):
        return round(sorted(v)[len(v) // 2], 2) if v else 0.0

    cells = sum(1 for row in stand for c in row if c)
    n_enc = sum(1 for l in levels if l["enclosed"])
    cell_area = step * step
    area_at = {}
    for l in levels:
        area_at[l["z"]] = area_at.get(l["z"], 0.0) + cell_area
    total_area = sum(area_at.values())
    floor_min = max(2.0, 0.05 * total_area)      # a storey has area; a tread does not
    storeys = []
    for z in sorted(z for z, a in area_at.items() if a >= floor_min):
        if storeys and abs(z - storeys[-1][-1]) <= 0.35:
            storeys[-1].append(z)
        else:
            storeys.append([z])

    return {
        "step_m": round(step, 3),
        "standable_cells": cells,
        "standable_area_m2": round(cells * step * step, 1),
        "enclosed_area_m2": round(n_enc * step * step, 1),
        "open_topped_area_m2": round((len(levels) - n_enc) * step * step, 1),
        "headroom_min_m": round(min(headrooms), 2) if headrooms else None,
        "headroom_median_m": med(headrooms) if headrooms else None,
        "width_x_median_m": round(med(rx) * step, 2),
        "width_x_min_m": round(min(rx) * step, 2) if rx else 0,
        "width_y_median_m": round(med(ry) * step, 2),
        "width_y_min_m": round(min(ry) * step, 2) if ry else 0,
        "storey_count": len(storeys),
        "storey_floor_z": [round(sum(s) / len(s), 2) for s in storeys],
        "storey_rise_m": [round(sum(storeys[i + 1]) / len(storeys[i + 1])
                                - sum(storeys[i]) / len(storeys[i]), 2)
                          for i in range(len(storeys) - 1)],
    }


def scene_stairs():
    """Band analysis over EVERY mesh joined, so a 16-object staircase reads as a
    staircase. The per-object pass cannot see it - each step is one flat band."""
    depsgraph = bpy.context.evaluated_depsgraph_get()
    merged = bmesh.new()
    for obj in bpy.context.scene.objects:
        if obj.type != "MESH":
            continue
        bm = world_tris(obj, depsgraph)
        if bm is None:
            continue
        me = bpy.data.meshes.new("_probe_tmp")
        bm.to_mesh(me)
        bm.free()
        merged.from_mesh(me)
        bpy.data.meshes.remove(me)
    if not merged.faces:
        merged.free()
        return [], []
    bands = horizontal_bands(merged)
    merged.free()
    top = [{"z": z, "area_m2": round(b["area"], 2)}
           for z, b in sorted(bands.items(), key=lambda kv: -kv[1]["area"])[:10]]
    return read_stairs(bands), top


# ---- per-object report ----------------------------------------------------

def probe_object(obj, depsgraph):
    r = {"name": obj.name, "type": obj.type}
    if obj.type != "MESH":
        r["location"] = [round(c, 3) for c in obj.location]
        return r

    sc = obj.matrix_world.to_scale()
    r["scale"] = [round(c, 4) for c in sc]
    r["scale_applied"] = all(abs(c - 1.0) < 1e-4 for c in sc)
    r["uniform_scale"] = abs(sc.x - sc.y) < 1e-4 and abs(sc.y - sc.z) < 1e-4
    r["modifiers"] = [m.type for m in obj.modifiers]

    bm = world_tris(obj, depsgraph)
    if bm is None or len(bm.verts) == 0:
        r["empty"] = True
        return r

    mn = [1e9, 1e9, 1e9]
    mx = [-1e9, -1e9, -1e9]
    for v in bm.verts:
        for i in range(3):
            mn[i] = min(mn[i], v.co[i])
            mx[i] = max(mx[i], v.co[i])
    r["world_min"] = [round(c, 3) for c in mn]
    r["world_max"] = [round(c, 3) for c in mx]
    r["dimensions_m"] = [round(mx[i] - mn[i], 3) for i in range(3)]
    r["tris"] = len(bm.faces)
    r["verts"] = len(bm.verts)
    r["cells_xyz"] = [round((mx[0] - mn[0]) / GRID, 2),
                      round((mx[1] - mn[1]) / GRID, 2),
                      round((mx[2] - mn[2]) / STOREY, 2)]

    raw = raw_bmesh(obj)
    r["ngons"] = sum(1 for f in raw.faces if len(f.verts) > 4)
    r["quads"] = sum(1 for f in raw.faces if len(f.verts) == 4)
    r["non_manifold_edges"] = sum(1 for e in raw.edges if not e.is_manifold)
    r["loose_verts"] = sum(1 for v in raw.verts if not v.link_edges)
    raw.free()

    r["uv_layers"] = [uv.name for uv in obj.data.uv_layers]
    r["materials"] = [m.name if m else "<empty slot>" for m in obj.data.materials]
    r["shade_smooth"] = any(p.use_smooth for p in obj.data.polygons)

    bands = horizontal_bands(bm)
    ordered = sorted(bands.items(), key=lambda kv: -kv[1]["area"])
    r["floor_bands"] = [{"z": z, "area_m2": round(b["area"], 2), "faces": b["faces"]}
                        for z, b in ordered[:8]]
    stairs = read_stairs(bands)
    if stairs:
        r["stairs"] = stairs

    ground = min(bands.keys()) if bands else mn[2]
    r["wall_span_at_eye"] = wall_span_at(bm, ground + EYE)

    bm.free()
    return r


def probe_materials():
    out = []
    for m in bpy.data.materials:
        rec = {"name": m.name, "use_nodes": m.use_nodes, "images": []}
        if m.use_nodes and m.node_tree:
            for n in m.node_tree.nodes:
                if n.type == "TEX_IMAGE" and n.image:
                    img = n.image
                    path = bpy.path.abspath(img.filepath) if img.filepath else ""
                    rec["images"].append({
                        "name": img.name,
                        "path": path,
                        "packed": bool(img.packed_file),
                        "exists": bool(path) and os.path.exists(path),
                        "size": list(img.size),
                        "colorspace": img.colorspace_settings.name,
                    })
                if n.type == "BSDF_PRINCIPLED":
                    try:
                        rec["base_color"] = [round(c, 3) for c in n.inputs["Base Color"].default_value]
                        rec["metallic"] = round(n.inputs["Metallic"].default_value, 3)
                        rec["roughness"] = round(n.inputs["Roughness"].default_value, 3)
                    except Exception:
                        pass
        out.append(rec)
    return out


# ---- verdict --------------------------------------------------------------

def verdict(report):
    w = []
    us = report["units"]["scale_length"]
    if abs(us - 1.0) > 1e-6:
        w.append("unit scale_length is %s, not 1.0 - every measurement below is in Blender "
                 "units, and Unity will import at a different size than you read here" % us)

    meshes = [o for o in report["objects"] if o["type"] == "MESH" and not o.get("empty")]
    if not meshes:
        w.append("no mesh objects - this file has nothing to import")
        return w

    tris = sum(o["tris"] for o in meshes)
    if tris > 200000:
        w.append("%s tris total - heavy for a module that will be instanced; budget a decimate pass"
                 % format(tris, ","))

    unapplied = [o["name"] for o in meshes if not o["scale_applied"]]
    if unapplied:
        w.append("scale NOT applied on %d object(s) (%s) - FBX export bakes it, but lightmap UVs "
                 "and physics scale go wrong" % (len(unapplied), ", ".join(unapplied[:4])))

    nouv = [o["name"] for o in meshes if not o["uv_layers"]]
    if nouv:
        w.append("no UV map on %d object(s) (%s) - untextureable as-is"
                 % (len(nouv), ", ".join(nouv[:4])))

    nm = [o["name"] for o in meshes if o["non_manifold_edges"] > 0]
    if nm:
        w.append("non-manifold edges on %d object(s) - fine for rendering, breaks MeshCollider "
                 "convex hulls and navmesh carving" % len(nm))

    missing = [i["name"] for m in report["materials"] for i in m["images"]
               if not i["exists"] and not i["packed"]]
    if missing:
        w.append("%d texture(s) MISSING and not packed (%s) - the .blend references files that "
                 "did not come with it" % (len(missing), ", ".join(missing[:4])))

    far = [o["name"] for o in meshes
           if max(abs(c) for c in o["world_min"] + o["world_max"]) > 500]
    if far:
        w.append("%d object(s) more than 500 m from the origin - float precision, and a hard time "
                 "snapping to the %g m grid" % (len(far), GRID))

    sp = report.get("space") or {}
    if sp.get("standable_cells") == 0:
        w.append("nothing in this file is standable - no floor has %.1f m of clear space above "
                 "it, so it is a prop or a facade, not a space" % PLAYER_HEIGHT)
    if (sp.get("headroom_median_m") or 99) < 2.2:
        w.append("median headroom %.2f m - a 1.8 m capsule fits but it will feel like a crawlspace; "
                 "the project's kit is %g m floor-to-floor" % (sp["headroom_median_m"], STOREY))
    narrow = min(sp.get("width_x_median_m", 99), sp.get("width_y_median_m", 99))
    if narrow < 1.6:
        w.append("open width is %.2f m at its median - two zombies cannot pass a player abreast, "
                 "which makes it a choke, not a route" % narrow)

    all_stairs = [(o["name"], s) for o in meshes for s in o.get("stairs", [])]
    all_stairs += [("<whole scene>", s) for s in (report.get("scene_stairs") or [])]
    for name, s in all_stairs:
        if s["rise_m"] > STEP_OFFSET:
            w.append("%s has %.2f m step rise - over Unity's default %.2f m stepOffset, so the "
                     "player will NOT climb it" % (name, s["rise_m"], STEP_OFFSET))
        if s["angle_deg"] and s["angle_deg"] > SLOPE_LIMIT:
            w.append("%s stairs are %.1f deg - over the %.0f deg slope limit; the navmesh will "
                     "refuse to bake them" % (name, s["angle_deg"], SLOPE_LIMIT))
    return w


# ---- render ---------------------------------------------------------------

def scene_bounds():
    mn = [1e9, 1e9, 1e9]
    mx = [-1e9, -1e9, -1e9]
    for obj in bpy.context.scene.objects:
        if obj.type != "MESH":
            continue
        for c in obj.bound_box:
            p = obj.matrix_world @ Vector(c)
            for i in range(3):
                mn[i] = min(mn[i], p[i])
                mx[i] = max(mx[i], p[i])
    return Vector(mn), Vector(mx)


def pick_engine():
    items = {i.identifier for i in
             bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items}
    for e in ("BLENDER_EEVEE_NEXT", "BLENDER_EEVEE", "BLENDER_WORKBENCH"):
        if e in items:
            return e
    return "BLENDER_WORKBENCH"


def render_views(outdir, sections=None):
    """Four views, including two at 1.7 m standing INSIDE it. A three-quarter
    render of a corridor tells you nothing about what it is like to be in one."""
    os.makedirs(outdir, exist_ok=True)
    scn = bpy.context.scene
    scn.render.engine = pick_engine()
    scn.render.resolution_x, scn.render.resolution_y = 1280, 720
    scn.render.film_transparent = False

    mn, mx = scene_bounds()
    ctr = (mn + mx) / 2
    size = max((mx - mn).x, (mx - mn).y, (mx - mn).z) or 4.0

    if not any(o.type == "LIGHT" for o in scn.objects):
        sun_d = bpy.data.lights.new("probe_sun", type="SUN")
        sun_d.energy = 4.0
        sun = bpy.data.objects.new("probe_sun", sun_d)
        sun.rotation_euler = (math.radians(50), 0, math.radians(35))
        scn.collection.objects.link(sun)
    if scn.world is None:
        scn.world = bpy.data.worlds.new("probe_world")
    scn.world.use_nodes = True
    try:
        bg = scn.world.node_tree.nodes["Background"]
        bg.inputs[0].default_value = (0.25, 0.27, 0.32, 1.0)
        bg.inputs[1].default_value = 1.2
    except Exception:
        pass

    cam_d = bpy.data.cameras.new("probe_cam")
    cam_d.lens = 24
    cam = bpy.data.objects.new("probe_cam", cam_d)
    scn.collection.objects.link(cam)
    scn.camera = cam

    def look_at(pos, target):
        cam.location = Vector(pos)
        d = Vector(target) - Vector(pos)
        cam.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()

    inside_z = mn.z + EYE
    diag = (mx - mn).length
    plan_z = mx.z + size * 1.4
    views = [
        # framed off the DIAGONAL, not the longest side: at ctr+size the camera sat
        # inside the geometry of anything longer than it is wide
        ("A_three_quarter", (ctr.x + diag * 0.8, ctr.y - diag * 0.8, ctr.z + diag * 0.6),
         tuple(ctr), None),
        ("C_eye_along_x", (mn.x + 0.5, ctr.y, inside_z), (mx.x, ctr.y, inside_z), None),
        ("D_eye_along_y", (ctr.x, mn.y + 0.5, inside_z), (ctr.x, mx.y, inside_z), None),
    ]
    # A plan view of a ROOFED building is a picture of its roof. Cut it: the camera's
    # near clip plane slices everything above the section height away, which is what
    # an architectural plan has always been.
    for i, sz in enumerate(sections or [mn.z]):
        views.insert(1 + i, ("B_plan_%d_cut_at_%.1fm" % (i, sz + 2.0),
                             (ctr.x, ctr.y, plan_z), (ctr.x, ctr.y, mn.z),
                             plan_z - (sz + 2.0)))
    written = []
    for name, pos, tgt, clip in views:
        cam_d.type = "ORTHO" if name.startswith("B_plan") else "PERSP"
        cam_d.clip_start = clip if clip else 0.1
        cam_d.clip_end = max(1000.0, diag * 4)
        if cam_d.type == "ORTHO":
            cam_d.ortho_scale = size * 1.15
        look_at(pos, tgt)
        scn.render.filepath = os.path.join(outdir, name + ".png")
        bpy.ops.render.render(write_still=True)
        written.append(scn.render.filepath)
    return written


# ---- fbx ------------------------------------------------------------------

def export_fbx(path):
    """Unity-correct FBX. -Z forward / Y up is what stops a corridor arriving on
    its side; FBX_SCALE_UNITS is what stops it arriving 100x. Project law: FBX,
    never GLB - Unity 6 has no glTF importer and reports no error."""
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    if not hasattr(bpy.ops.export_scene, "fbx"):
        try:
            bpy.ops.preferences.addon_enable(module="io_scene_fbx")
        except Exception as e:
            return "FBX exporter unavailable: %s" % e
    bpy.ops.export_scene.fbx(
        filepath=path,
        use_selection=False,
        apply_scale_options="FBX_SCALE_UNITS",
        axis_forward="-Z", axis_up="Y",
        object_types={"MESH", "EMPTY"},
        use_mesh_modifiers=True,
        mesh_smooth_type="FACE",
        use_tspace=True,
        bake_space_transform=False,
        path_mode="COPY", embed_textures=False,
    )
    return path


# ---- fixture (self-test) --------------------------------------------------

def build_fixture(path):
    """A corridor with a staircase, built to KNOWN numbers, so the probe can be
    checked against an answer instead of against a vibe.

    Ground truth: corridor 3.00 x 16.00 m, ceiling 3.40 m.
                  16 steps, rise 0.18 m, tread 0.28 m -> 32.7 deg, climbs 2.88 m."""
    bpy.ops.wm.read_factory_settings(use_empty=True)

    def box(name, cx, cy, cz, sx, sy, sz):
        me = bpy.data.meshes.new(name)
        b = bmesh.new()
        bmesh.ops.create_cube(b, size=1.0)
        for v in b.verts:
            v.co.x *= sx
            v.co.y *= sy
            v.co.z *= sz
        b.to_mesh(me)
        b.free()
        ob = bpy.data.objects.new(name, me)
        ob.location = (cx, cy, cz)
        bpy.context.scene.collection.objects.link(ob)
        return ob

    box("Corridor_Floor", 0, 8, -0.1, 3.0, 16.0, 0.2)
    box("Corridor_Wall_L", -1.6, 8, 1.6, 0.2, 16.0, 3.2)
    box("Corridor_Wall_R", 1.6, 8, 1.6, 0.2, 16.0, 3.2)
    box("Corridor_Ceiling", 0, 8, 3.3, 3.0, 16.0, 0.2)
    for i in range(16):
        box("Step_%02d" % i, 0, 16.2 + i * 0.28, i * 0.18 + 0.09, 3.0, 0.28, 0.18)

    bpy.ops.wm.save_as_mainfile(filepath=path)
    return path


# ---- main -----------------------------------------------------------------

def main():
    args = parse_args()

    if args["fixture"]:
        print("[fixture] wrote %s" % build_fixture(args["blend"]))
        return

    blend = args["blend"]
    if not blend or not os.path.exists(blend):
        print("[probe] FILE NOT FOUND: %s" % blend)
        sys.exit(2)

    bpy.ops.wm.open_mainfile(filepath=blend)
    depsgraph = bpy.context.evaluated_depsgraph_get()

    report = {
        "file": os.path.abspath(blend),
        "size_mb": round(os.path.getsize(blend) / 1e6, 2),
        "saved_with_blender": ".".join(str(x) for x in bpy.data.version),
        "reading_blender": bpy.app.version_string,
        "units": {
            "system": bpy.context.scene.unit_settings.system,
            "scale_length": round(bpy.context.scene.unit_settings.scale_length, 6),
            "length_unit": bpy.context.scene.unit_settings.length_unit,
        },
        "collections": [c.name for c in bpy.data.collections],
        "objects": [probe_object(o, depsgraph) for o in bpy.context.scene.objects],
        "materials": probe_materials(),
        "armatures": [o.name for o in bpy.context.scene.objects if o.type == "ARMATURE"],
    }
    meshes = [o for o in report["objects"] if o["type"] == "MESH" and not o.get("empty")]
    report["totals"] = {
        "objects": len(report["objects"]),
        "meshes": len(meshes),
        "tris": sum(o["tris"] for o in meshes),
        "materials": len(report["materials"]),
    }
    if meshes:
        mn = [min(o["world_min"][i] for o in meshes) for i in range(3)]
        mx = [max(o["world_max"][i] for o in meshes) for i in range(3)]
        report["scene_bounds"] = {
            "min": mn, "max": mx,
            "size_m": [round(mx[i] - mn[i], 3) for i in range(3)],
            "cells": [round((mx[0] - mn[0]) / GRID, 2),
                      round((mx[1] - mn[1]) / GRID, 2),
                      round((mx[2] - mn[2]) / STOREY, 2)],
        }
    st, band_top = scene_stairs()
    report["scene_stairs"] = st
    report["scene_floor_bands"] = band_top
    report["space"] = space_probe()
    report["warnings"] = verdict(report)

    print("")
    print("=" * 78)
    print("  %s   %s MB   saved with Blender %s"
          % (os.path.basename(blend), report["size_mb"], report["saved_with_blender"]))
    print("=" * 78)
    u = report["units"]
    print("units      : %s / %s  scale_length=%s" % (u["system"], u["length_unit"], u["scale_length"]))
    t = report["totals"]
    line = "contents   : %d meshes, %s tris, %d materials" % (
        t["meshes"], format(t["tris"], ","), t["materials"])
    if report["armatures"]:
        line += ", %d armature(s)" % len(report["armatures"])
    print(line)
    if "scene_bounds" in report:
        sb = report["scene_bounds"]
        print("footprint  : %.2f x %.2f x %.2f m   = %.2f x %.2f cells, %.2f storeys "
              "(project grid %g m / %g m)"
              % (sb["size_m"][0], sb["size_m"][1], sb["size_m"][2],
                 sb["cells"][0], sb["cells"][1], sb["cells"][2], GRID, STOREY))
    print("-" * 78)
    for o in report["objects"]:
        if o["type"] != "MESH":
            print("  %-9s %s" % (o["type"], o["name"]))
            continue
        if o.get("empty"):
            print("  MESH      %s  <empty>" % o["name"])
            continue
        d = o["dimensions_m"]
        flag = "" if o["scale_applied"] else "  [SCALE NOT APPLIED]"
        print("  %-28s %7.2f x %7.2f x %7.2f m  %8s tris  uv=%d mats=%d%s"
              % (o["name"][:28], d[0], d[1], d[2], format(o["tris"], ","),
                 len(o["uv_layers"]), len(o["materials"]), flag))
        for s in o.get("stairs", []):
            print("      stairs: %d risers, rise %.3f m, tread %.3f m, %.1f deg, climbs %.2f m"
                  % (s["risers"], s["rise_m"], s["tread_m"], s["angle_deg"] or 0, s["total_climb_m"]))
        c = o.get("wall_span_at_eye")
        if c:
            print("      wall span at eye height (1.7 m): %.2f x %.2f m" % (c["x_span"], c["y_span"]))
    print("-" * 78)
    for s_ in report.get("scene_stairs") or []:
        print("  STAIRS (whole scene): %d risers, rise %.3f m, tread %.3f m, %.1f deg, climbs %.2f m"
              % (s_["risers"], s_["rise_m"], s_["tread_m"], s_["angle_deg"] or 0, s_["total_climb_m"]))
    sp = report.get("space")
    if sp and sp.get("standable_cells"):
        print("  SPACE (ray cast, %.2f m grid):" % sp["step_m"])
        print("      standable floor      : %.1f m2  (%.1f enclosed, %.1f open to sky) "
              "over %d storey band(s) at z = %s"
              % (sp["standable_area_m2"], sp["enclosed_area_m2"], sp["open_topped_area_m2"],
                 sp["storey_count"], ", ".join("%.2f" % z for z in sp["storey_floor_z"])))
        if sp["storey_rise_m"]:
            print("      floor-to-floor       : %s m   (project storey %g m)"
                  % (", ".join("%.2f" % r for r in sp["storey_rise_m"]), STOREY))
        if sp["headroom_median_m"] is not None:
            print("      headroom (enclosed)  : min %.2f m, median %.2f m"
                  % (sp["headroom_min_m"], sp["headroom_median_m"]))
        else:
            print("      headroom (enclosed)  : nothing is roofed - open geometry only")
        print("      open width  along X  : median %.2f m, narrowest %.2f m"
              % (sp["width_x_median_m"], sp["width_x_min_m"]))
        print("      open width  along Y  : median %.2f m, narrowest %.2f m"
              % (sp["width_y_median_m"], sp["width_y_min_m"]))
    elif sp:
        print("  SPACE: %s" % sp.get("note", "nothing standable"))
    print("-" * 78)
    for m in report["materials"]:
        imgs = ", ".join("%s%s" % (i["name"], "" if i["exists"] or i["packed"] else " [MISSING]")
                         for i in m["images"]) or "no image textures"
        print("  mat %-24s %s" % (m["name"][:24], imgs))
    print("-" * 78)
    if report["warnings"]:
        print("  WARNINGS")
        for w in report["warnings"]:
            print("   ! %s" % w)
    else:
        print("  no warnings - clean import")
    print("=" * 78)
    print("")

    if args["json"]:
        with open(args["json"], "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print("[probe] json -> %s" % args["json"])
    if args["render"]:
        secs = (report.get("space") or {}).get("storey_floor_z") or None
        for p in render_views(args["render"], secs):
            print("[probe] render -> %s" % p)
    if args["fbx"]:
        print("[probe] fbx -> %s" % export_fbx(args["fbx"]))


main()
