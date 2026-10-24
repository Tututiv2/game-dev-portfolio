"""kit_cut.py - turn downloaded architecture into kit modules on the project's grid.

A downloaded corridor is one mesh of whatever size the author felt like. The kit
is 4 m cells, 4 m storeys, origin at the bottom-centre of the footprint, nothing
overhanging its cell. This slices the first into the second.

It also builds a COLLISION PROXY per module, and that is not an optimisation - it
is a correctness requirement. MapKitTests Law 7 demands every collider be >= 0.5 m
on its thinnest axis, because the navmesh bakes at 0.12 m voxels and anything
thinner is missed by the bake. Downloaded walls are ~0.2 m. Use the render mesh as
the collider and the zombies path straight through the walls, while the map looks
perfect and every test that inspects the RENDER passes.

Two rules the proxy follows, both learned from this repo's own landmines:

  * a slab is thickened AWAY from its own normal, i.e. behind the surface, so
    fattening a 0.2 m wall to 0.5 m does not eat 0.3 m out of a 3 m corridor;
  * a staircase gets ONE WEDGE, not a box per tread. A 0.18 m tread box is
    illegal under Law 7 anyway, and the ramp trap is already paid for in
    docs/map-build.md: a wedge's walkable surface has to start on the floor, or
    there is a step at its foot that nothing can climb.

Run:
  blender --background --factory-startup --python Tools/map/kit_cut.py -- \
      --blend in.blend --out Assets/_Project/Art/Kit/Modules --prefix Station \
      [--cell 4.0] [--align auto|none] [--dry-run]
"""

import bpy, bmesh, sys, os, json, math
from mathutils import Vector

CELL = 4.0
STOREY = 4.0
MIN_COLLIDER_THICKNESS = 0.5   # MapKitBuilder.Thickness / Law 7
NAV_VOXEL = 0.12
PLANE_BIN = 0.05               # how close two faces must be to be one slab
MIN_SLAB_AREA = 0.40           # m2 - below this a slab is trim, not collision


def argv_after_dashes():
    return sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []


def parse_args():
    a = argv_after_dashes()
    out = {"blend": None, "out": None, "prefix": "Mod", "cell": CELL,
           "align": "auto", "dry_run": False, "only": None, "exclude": None,
           "tex_max": TEX_MAX, "skip_textures": False}
    i = 0
    while i < len(a):
        if a[i] == "--dry-run":
            out["dry_run"] = True
            i += 1
        elif a[i] == "--skip-textures":
            out["skip_textures"] = True
            i += 1
        elif a[i].startswith("--") and i + 1 < len(a):
            out[a[i][2:].replace("-", "_")] = a[i + 1]
            i += 2
        else:
            i += 1
    out["cell"] = float(out["cell"])
    return out


# ---- gather ---------------------------------------------------------------

def join_scene_geometry():
    """One evaluated, world-space, triangulated bmesh of everything.

    Evaluated, because a mirror or array modifier is real geometry. World-space,
    because a downloaded file's object transforms are arbitrary and the grid is not."""
    depsgraph = bpy.context.evaluated_depsgraph_get()
    merged = bmesh.new()
    mat_names = []
    for obj in list(bpy.context.scene.objects):
        if obj.type != "MESH":
            continue
        ev = obj.evaluated_get(depsgraph)
        try:
            me = ev.to_mesh()
        except RuntimeError:
            continue
        tmp = bmesh.new()
        tmp.from_mesh(me)
        tmp.transform(obj.matrix_world)
        bmesh.ops.triangulate(tmp, faces=tmp.faces[:])
        # remap material indices into one global list
        local = [m.name if m else "None" for m in obj.data.materials] or ["None"]
        remap = []
        for n in local:
            if n not in mat_names:
                mat_names.append(n)
            remap.append(mat_names.index(n))
        for f in tmp.faces:
            f.material_index = remap[min(f.material_index, len(remap) - 1)]
        out = bpy.data.meshes.new("_join_tmp")
        tmp.to_mesh(out)
        tmp.free()
        merged.from_mesh(out)
        bpy.data.meshes.remove(out)
        ev.to_mesh_clear()
    return merged, mat_names


def bounds_of(bm):
    mn = Vector((1e9, 1e9, 1e9))
    mx = Vector((-1e9, -1e9, -1e9))
    for v in bm.verts:
        for i in range(3):
            mn[i] = min(mn[i], v.co[i])
            mx[i] = max(mx[i], v.co[i])
    return mn, mx


def dominant_floor_z(bm):
    """The z of the largest upward-facing surface - the walkable plane.

    Chosen by AREA, not by lowest point, because the lowest point of a downloaded
    corridor is usually the underside of its floor slab or a stray bit of kerb."""
    bands = {}
    for f in bm.faces:
        if f.normal.z < 0.94:
            continue
        z = round(round((sum(v.co.z for v in f.verts) / len(f.verts)) / 0.01) * 0.01, 2)
        bands[z] = bands.get(z, 0.0) + f.calc_area()
    if not bands:
        return None
    return max(bands.items(), key=lambda kv: kv[1])[0]


# ---- slicing --------------------------------------------------------------

def slice_to_grid(bm, cell, origin_x, origin_y):
    """Bisect along every cell boundary, so no triangle straddles two modules.

    Without this a face spanning a seam lands in whichever cell its centroid falls
    in, and the neighbouring module has a hole exactly one triangle wide - which
    reads as a light leak in game and as nothing at all in a wireframe."""
    mn, mx = bounds_of(bm)
    planes = []
    k = math.floor((mn.x - origin_x) / cell)
    while origin_x + k * cell <= mx.x + 1e-6:
        planes.append((Vector((origin_x + k * cell, 0, 0)), Vector((1, 0, 0))))
        k += 1
    k = math.floor((mn.y - origin_y) / cell)
    while origin_y + k * cell <= mx.y + 1e-6:
        planes.append((Vector((0, origin_y + k * cell, 0)), Vector((0, 1, 0))))
        k += 1
    for co, no in planes:
        bmesh.ops.bisect_plane(bm, geom=bm.verts[:] + bm.edges[:] + bm.faces[:],
                               plane_co=co, plane_no=no, clear_inner=False,
                               clear_outer=False)
    # Bisecting a triangle yields a triangle AND a quad, so the mesh is no longer
    # triangulated after this. The footprint rasteriser reads three vertices per
    # face, so an untriangulated quad rasterises as half of itself: the surface
    # comes out speckled and the greedy merge returns 130 boxes instead of 4.
    bmesh.ops.triangulate(bm, faces=bm.faces[:])
    return len(planes)


def cell_of(face, cell, origin_x, origin_y):
    c = face.calc_center_median()
    return (math.floor((c.x - origin_x) / cell + 1e-6),
            math.floor((c.y - origin_y) / cell + 1e-6))


# ---- collision proxy ------------------------------------------------------

SLAB_RES = 0.25                # m - rasterisation cell for a surface footprint


def planar_slabs(faces):
    """Cluster faces into axis-aligned planar surfaces, KEEPING their triangles.

    The triangles are the point. An earlier version stored only each cluster's
    bounding box, and a bounding box fills its own hole: the ring of faces where a
    corridor ends, or the frame around a doorway, has an AABB that covers the
    opening. Every door in the asset would have been invisibly bricked up, and the
    render would have been perfect."""
    slabs = {}
    for f in faces:
        n = f.normal
        axis = max(range(3), key=lambda i: abs(n[i]))
        if abs(n[axis]) < 0.94:
            continue                      # not axis-aligned - left to the render mesh
        sign = 1 if n[axis] > 0 else -1
        c = f.calc_center_median()
        key = (axis, sign, round(round(c[axis] / PLANE_BIN) * PLANE_BIN, 2))
        s = slabs.setdefault(key, {"area": 0.0, "tris": [],
                                   "min": [1e9, 1e9, 1e9], "max": [-1e9, -1e9, -1e9]})
        s["area"] += f.calc_area()
        s["tris"].append([tuple(v.co) for v in f.verts])
        for v in f.verts:
            for i in range(3):
                s["min"][i] = min(s["min"][i], v.co[i])
                s["max"][i] = max(s["max"][i], v.co[i])
    return {k: v for k, v in slabs.items() if v["area"] >= MIN_SLAB_AREA}


def _in_tri(px, py, t, u, v):
    ax, ay = t[0][u], t[0][v]
    bx, by = t[1][u], t[1][v]
    cx, cy = t[2][u], t[2][v]
    d = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
    if abs(d) < 1e-12:
        return False
    l1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / d
    l2 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / d
    return l1 >= -1e-9 and l2 >= -1e-9 and (l1 + l2) <= 1 + 1e-9


def slab_rects(slab, axis, res=SLAB_RES):
    """The surface's real footprint, as rectangles - holes and all.

    Rasterise the triangles onto a grid in the plane, then greedily merge occupied
    cells into rectangles. A doorway is a run of empty cells, so it stays a doorway."""
    u, v = [i for i in range(3) if i != axis]
    u0, u1 = slab["min"][u], slab["max"][u]
    v0, v1 = slab["min"][v], slab["max"][v]
    nu = max(1, int(math.ceil((u1 - u0) / res)))
    nv = max(1, int(math.ceil((v1 - v0) / res)))
    if nu * nv > 40000:                    # a huge flat floor: fall back to its AABB
        return [(u0, v0, u1, v1)]
    grid = [[False] * nu for _ in range(nv)]
    for t in slab["tris"]:
        tu0 = min(p[u] for p in t); tu1 = max(p[u] for p in t)
        tv0 = min(p[v] for p in t); tv1 = max(p[v] for p in t)
        for j in range(max(0, int((tv0 - v0) / res)), min(nv, int((tv1 - v0) / res) + 1)):
            py = v0 + (j + 0.5) * res
            for i in range(max(0, int((tu0 - u0) / res)), min(nu, int((tu1 - u0) / res) + 1)):
                if grid[j][i]:
                    continue
                if _in_tri(u0 + (i + 0.5) * res, py, t, u, v):
                    grid[j][i] = True

    rects = []
    for j in range(nv):
        i = 0
        while i < nu:
            if not grid[j][i]:
                i += 1
                continue
            i2 = i
            while i2 + 1 < nu and grid[j][i2 + 1]:
                i2 += 1
            j2 = j
            while j2 + 1 < nv and all(grid[j2 + 1][k] for k in range(i, i2 + 1)):
                j2 += 1
            for jj in range(j, j2 + 1):
                for ii in range(i, i2 + 1):
                    grid[jj][ii] = False
            rects.append((u0 + i * res, v0 + j * res,
                          u0 + (i2 + 1) * res, v0 + (j2 + 1) * res))
            i = i2 + 1
    return rects


PLAYER_RADIUS = 0.4            # CharacterController capsule radius


def standable_points(cell_step=0.5, player_height=1.8, radius=PLAYER_RADIUS):
    """Sample points a player's body can occupy, by ray cast against the SCENE.

    This exists to answer one question that no amount of looking at a mesh can:
    which side of a wall is the side people walk on. Everything about thickening
    a collider depends on it."""
    scn = bpy.context.scene
    depsgraph = bpy.context.evaluated_depsgraph_get()
    mn = Vector((1e9, 1e9, 1e9))
    mx = Vector((-1e9, -1e9, -1e9))
    for obj in scn.objects:
        if obj.type != "MESH":
            continue
        for c in obj.bound_box:
            p = obj.matrix_world @ Vector(c)
            for i in range(3):
                mn[i] = min(mn[i], p[i])
                mx[i] = max(mx[i], p[i])
    pts = []
    top, bottom = mx.z + 1.0, mn.z - 1.0
    y = mn.y + cell_step * 0.5
    while y < mx.y:
        x = mn.x + cell_step * 0.5
        while x < mx.x:
            z = top
            hits = []
            for _ in range(12):
                ok, loc, nrm, _i, _o, _m = scn.ray_cast(
                    depsgraph, Vector((x, y, z)), Vector((0, 0, -1)), distance=z - bottom)
                if not ok:
                    break
                hits.append((loc.z, nrm.z))
                z = loc.z - 1e-3
                if z <= bottom:
                    break
            for i, (hz, hn) in enumerate(hits):
                if hn < 0.7:
                    continue
                head = (hits[i - 1][0] - hz) if i > 0 else 99.0
                if head < player_height:
                    continue
                # A player is a capsule, not a point. Without this, a sample 0.05 m
                # off a wall counts as standable, and every wall's own collider then
                # reports as an "intrusion" into space nobody can occupy.
                p = Vector((x, y, hz + 0.9))
                if any(scn.ray_cast(depsgraph, p, Vector(d), distance=radius)[0]
                       for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0))):
                    continue
                pts.append((x, y, hz + 0.9))     # mid-body, where a capsule is
            x += cell_step
        y += cell_step
    return pts


def _overlap(box, pts):
    mn, mx = box
    return sum(1 for p in pts
               if mn[0] <= p[0] <= mx[0] and mn[1] <= p[1] <= mx[1] and mn[2] <= p[2] <= mx[2])


def solid_boxes(faces, standable):
    """Collision boxes for one module: each surface's real footprint, extruded away
    from the space the player stands in.

    Two bugs are already paid for here. Thickening every face behind its own normal
    grew the floor's UNDERSIDE upward to +0.3 m - an invisible step down the length
    of every corridor. And using a surface's bounding box sealed every opening,
    because the AABB of a ring covers the hole."""
    out = []
    for (axis, _sign, _off), slab in planar_slabs(faces).items():
        u, v = [i for i in range(3) if i != axis]
        a0, a1 = slab["min"][axis], slab["max"][axis]
        for (ru0, rv0, ru1, rv1) in slab_rects(slab, axis):
            mn = [0.0, 0.0, 0.0]
            mx = [0.0, 0.0, 0.0]
            mn[u], mx[u] = ru0, ru1
            mn[v], mx[v] = rv0, rv1
            mn[axis], mx[axis] = a0, a1
            if min(mx[o] - mn[o] for o in (u, v)) < 0.05:
                continue
            # Law 7 is about the collider's THINNEST axis, so enforcing it on the
            # surface's own axis is not enough: the top edge of a 0.2 m wall
            # rasterises to a 0.25 m strip and is thin sideways instead. Enforcing
            # only the one axis produced boxes 0.25 m across, which fail the
            # project's own kit test.
            for ax in range(3):
                depth = mx[ax] - mn[ax]
                if depth >= MIN_COLLIDER_THICKNESS:
                    continue
                grow = MIN_COLLIDER_THICKNESS - depth
                down = ([c for c in mn], [c for c in mx]); down[0][ax] -= grow
                up = ([c for c in mn], [c for c in mx]); up[1][ax] += grow
                # Which way is "into the wall" is not a property of the face. It is a
                # property of where people can stand, so ask that.
                mn, mx = down if _overlap(down, standable) <= _overlap(up, standable) else up
            out.append(([c for c in mn], [c for c in mx]))

    # a box wholly inside another box is collision nobody will ever touch
    keep = []
    for i, (mn, mx) in enumerate(out):
        if any(j != i and all(o_mn[k] <= mn[k] + 1e-4 and mx[k] <= o_mx[k] + 1e-4
                              for k in range(3))
               for j, (o_mn, o_mx) in enumerate(out)):
            continue
        keep.append((mn, mx))
    return keep


def intruding_boxes(boxes, standable):
    """The guard. A collider that contains a point a player stands on is a step,
    a wall or a ceiling that is not there on screen - and nothing reports it."""
    return [(b, _overlap(b, standable)) for b in boxes if _overlap(b, standable) > 0]


def stair_wedge(faces):
    """One wedge for a flight of steps, or None if these faces are not a flight.

    A box per 0.18 m tread is illegal under Law 7 and makes the player climb by
    a chain of 0.18 m teleports. A wedge is one smooth surface the navmesh bakes
    happily, provided its walkable face starts ON the floor - see the ramp trap
    in docs/map-build.md, where a rotated slab left a 0.45 m lip at its foot."""
    bands = {}
    for f in faces:
        if f.normal.z < 0.94:
            continue
        a = f.calc_area()
        if a < 0.02:
            continue
        z = round(round((sum(v.co.z for v in f.verts) / len(f.verts)) / 0.01) * 0.01, 2)
        b = bands.setdefault(z, {"area": 0.0, "min": [1e9, 1e9], "max": [-1e9, -1e9]})
        b["area"] += a
        for v in f.verts:
            b["min"][0] = min(b["min"][0], v.co.x)
            b["min"][1] = min(b["min"][1], v.co.y)
            b["max"][0] = max(b["max"][0], v.co.x)
            b["max"][1] = max(b["max"][1], v.co.y)
    zs = sorted(bands)
    run = [z for z in zs if 0.08 <= (zs[zs.index(z) + 1] - z if zs.index(z) + 1 < len(zs) else 0) <= 0.45]
    if len(run) < 3:
        return None
    lo, hi = run[0], zs[zs.index(run[-1]) + 1]
    xs = [bands[z]["min"][0] for z in run] + [bands[z]["max"][0] for z in run]
    ys = [bands[z]["min"][1] for z in run] + [bands[z]["max"][1] for z in run]
    # which horizontal axis does the flight travel along?
    span = [max(xs) - min(xs), max(ys) - min(ys)]
    travel = 0 if span[0] > span[1] else 1
    return {"lo_z": lo, "hi_z": hi, "travel_axis": travel,
            "min": [min(xs), min(ys)], "max": [max(xs), max(ys)],
            "risers": len(run),
            "rise": round((hi - lo) / len(run), 3)}


def build_box_mesh(bm, mn, mx):
    verts = []
    for z in (mn[2], mx[2]):
        for y in (mn[1], mx[1]):
            for x in (mn[0], mx[0]):
                verts.append(bm.verts.new((x, y, z)))
    bm.verts.ensure_lookup_table()
    q = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
    for a, b, c, d in q:
        try:
            bm.faces.new((verts[a], verts[b], verts[c], verts[d]))
        except ValueError:
            pass


def build_wedge_mesh(bm, w):
    """A true wedge: the low end's walkable surface is AT the floor, not lifted."""
    x0, y0 = w["min"]
    x1, y1 = w["max"]
    lo, hi = w["lo_z"], w["hi_z"]
    base = lo - MIN_COLLIDER_THICKNESS
    if w["travel_axis"] == 0:
        prof = [(x0, lo), (x1, hi), (x1, base), (x0, base)]
        pts = [(px, y, pz) for (px, pz) in prof for y in (y0, y1)]
    else:
        prof = [(y0, lo), (y1, hi), (y1, base), (y0, base)]
        pts = [(x, py, pz) for (py, pz) in prof for x in (x0, x1)]
    vs = [bm.verts.new(p) for p in pts]
    bm.verts.ensure_lookup_table()
    side_a = [vs[0], vs[2], vs[4], vs[6]]
    side_b = [vs[1], vs[3], vs[5], vs[7]]
    for quad in (side_a, side_b[::-1]):
        try:
            bm.faces.new(quad)
        except ValueError:
            pass
    for i in range(4):
        j = (i + 1) % 4
        try:
            bm.faces.new((vs[2 * i], vs[2 * i + 1], vs[2 * j + 1], vs[2 * j]))
        except ValueError:
            pass



# ---- materials -------------------------------------------------------------

TEX_MAX = 2048          # a corridor wall is read from 1-3 m away; 4K is 4x the bytes


def _trace_to_image(socket, depth=0, via=None):
    """Follow a shader link back to the image feeding it, REPORTING WHAT IT PASSED
    THROUGH on the way.

    The route is the whole point. This asset has no normal maps: every material
    feeds a greyscale HEIGHT map into the Normal input through a Bump node. An
    image is not a normal map because of the socket it ends up in - copying that
    PNG into Unity BumpMap slot is a valid binding that decodes grey as a garbage
    normal, lights every wall wrong, and reports nothing."""
    if depth > 8 or not socket.is_linked:
        return None, via
    node = socket.links[0].from_node
    if node.type == "TEX_IMAGE":
        return node.image, via
    if node.type == "BUMP":
        via = "bump"
    elif node.type == "NORMAL_MAP":
        via = "normal_map"
    for i in node.inputs:
        img, v = _trace_to_image(i, depth + 1, via)
        if img:
            return img, v
    return None, via


def _read(img, non_color=False):
    import numpy as np
    if non_color:
        img.colorspace_settings.name = "Non-Color"
    w, h = img.size
    buf = np.empty(w * h * 4, dtype=np.float32)
    img.pixels.foreach_get(buf)
    return buf.reshape(h, w, 4)


def _write(name, arr, path):
    import numpy as np
    h, w, _ = arr.shape
    ni = bpy.data.images.new(name, w, h, alpha=True)
    ni.pixels.foreach_set(np.ascontiguousarray(arr, dtype=np.float32).ravel())
    ni.filepath_raw = path
    ni.file_format = "PNG"
    ni.save()
    bpy.data.images.remove(ni)


def _fit(img, tex_max):
    w, h = img.size
    if max(w, h) <= tex_max:
        return
    k = tex_max / float(max(w, h))
    img.scale(max(1, int(w * k)), max(1, int(h * k)))


def height_to_normal(hgt, strength=3.0):
    """A real tangent-space normal map, from the height map the asset actually has.

    Without this every surface in the kit is lit as if it were flat, because the
    only detail map in the file is a height map and nothing consumes it."""
    import numpy as np
    h = hgt[:, :, 0]
    dx = (np.roll(h, -1, 1) - np.roll(h, 1, 1)) * strength
    dy = (np.roll(h, -1, 0) - np.roll(h, 1, 0)) * strength
    inv = 1.0 / np.sqrt(dx * dx + dy * dy + 1.0)
    return np.stack([(-dx * inv) * 0.5 + 0.5, (-dy * inv) * 0.5 + 0.5,
                     inv * 0.5 + 0.5, np.ones_like(h)], axis=-1)


def pack_metallic_smoothness(metal, rough, metal_value, rough_value, shape):
    """URP Lit reads metallic from R and SMOOTHNESS from A of one texture.

    Handing Unity a roughness map instead is the mistake already recorded against
    the kit material pack, where roughness sat in the specular slot and every wall
    in the map came out a mirror. Smoothness is 1 - roughness; that inversion has
    to happen somewhere, and here is the only place it can happen once."""
    import numpy as np
    h, w = shape

    def fit(a, fill):
        # metallic and roughness are NOT always authored at the same resolution -
        # this asset ships 2048 metallic against 1024 roughness - so one of them
        # has to be resampled before they can share a texture
        if a is None:
            return np.full((h, w), fill, np.float32)
        c = a[:, :, 0]
        if c.shape == (h, w):
            return c
        yi = (np.arange(h) * c.shape[0]) // h
        xi = (np.arange(w) * c.shape[1]) // w
        return c[yi][:, xi]

    m = fit(metal, metal_value)
    r = fit(rough, rough_value)
    z = np.zeros((h, w), np.float32)
    return np.stack([m, z, z, 1.0 - r], axis=-1)


def export_materials(mat_names, tex_dir, tex_max=TEX_MAX):
    """Unpack only the textures these materials use, at a sane size, and convert
    them into the three maps URP Lit actually consumes."""
    os.makedirs(tex_dir, exist_ok=True)
    out = {}
    shared = {}
    for name in mat_names:
        mat = bpy.data.materials.get(name)
        if not mat or not mat.use_nodes or not mat.node_tree:
            continue
        bsdf = next((n for n in mat.node_tree.nodes if n.type == "BSDF_PRINCIPLED"), None)
        if not bsdf:
            continue

        def grab(socket_name):
            sock = bsdf.inputs.get(socket_name)
            if sock is None:
                return None, None, None
            if not sock.is_linked:
                v = sock.default_value
                return None, None, ([round(c, 4) for c in v] if hasattr(v, "__len__")
                                    else round(float(v), 4))
            img, via = _trace_to_image(sock)
            if img is None:
                return None, via, None
            _fit(img, tex_max)
            return img, via, None

        base_i, _, base_v = grab("Base Color")
        met_i, _, met_v = grab("Metallic")
        rgh_i, _, rgh_v = grab("Roughness")
        nrm_i, nrm_via, _ = grab("Normal")

        # Several materials in this asset are the same textures under different
        # names - Wall 001-5, -6 and -7 all read one image set. Keying the output
        # on the material name wrote three identical copies of every 5 MB map.
        key = tuple((i.name if i is not None else None)
                    for i in (base_i, met_i, rgh_i, nrm_i))
        if key in shared:
            dup = dict(shared[key])
            dup["name"] = name
            out[name] = dup
            continue
        stem = bpy.path.clean_name(name)
        rec = {}
        if base_i is not None:
            fn = stem + "_Albedo.png"
            _write(stem + "_A", _read(base_i), os.path.join(tex_dir, fn))
            rec["baseMap"] = fn
        elif base_v:
            rec["baseColor"] = base_v

        if nrm_i is not None:
            arr = _read(nrm_i, non_color=True)
            fn = stem + "_Normal.png"
            if nrm_via == "normal_map":
                _write(stem + "_N", arr, os.path.join(tex_dir, fn))
                rec["normalSource"] = "authored"
            else:
                # a height map arriving through a Bump node - convert it, rather than
                # hand a greyscale image to Unity and call it a normal map
                _write(stem + "_N", height_to_normal(arr), os.path.join(tex_dir, fn))
                rec["normalSource"] = "derived from height via " + str(nrm_via)
            rec["normalMap"] = fn

        shape = None
        for i in (met_i, rgh_i):
            if i is not None:
                cand = (i.size[1], i.size[0])
                shape = cand if shape is None else (max(shape[0], cand[0]),
                                                    max(shape[1], cand[1]))
        if shape and (met_i is not None or rgh_i is not None):
            arr = pack_metallic_smoothness(
                _read(met_i, True) if met_i is not None else None,
                _read(rgh_i, True) if rgh_i is not None else None,
                float(met_v if isinstance(met_v, float) else 0.0),
                float(rgh_v if isinstance(rgh_v, float) else 0.5), shape)
            fn = stem + "_MetallicSmoothness.png"
            _write(stem + "_MS", arr, os.path.join(tex_dir, fn))
            rec["metallicGlossMap"] = fn
        else:
            rec["metallicValue"] = met_v if isinstance(met_v, float) else 0.0
            rec["smoothnessValue"] = round(1.0 - (rgh_v if isinstance(rgh_v, float) else 0.5), 4)

        rec["name"] = name
        shared[key] = rec
        out[name] = rec

    out = list(out.values())
    files = os.listdir(tex_dir)
    total = sum(os.path.getsize(os.path.join(tex_dir, f)) for f in files)
    print("[kit_cut] %d material(s) -> %d texture(s), %.1f MB, max %d px"
          % (len(out), len(files), total / 1e6, tex_max))
    return out


# ---- main -----------------------------------------------------------------

def main():
    args = parse_args()
    blend = args["blend"]
    if not blend or not os.path.exists(blend):
        print("[kit_cut] FILE NOT FOUND: %s" % blend)
        sys.exit(2)
    cell = args["cell"]
    outdir = args["out"]

    bpy.ops.wm.open_mainfile(filepath=blend)

    # A downloaded corridor is architecture AND its dressing in one file. The shell
    # is what tiles on a grid; a 193k-triangle cable is not, and instancing it into
    # every module would carry it dozens of times. Objects are filtered out of the
    # SCENE, so the walkable-space ray casts see the same world the modules do.
    only = [t.strip().lower() for t in (args["only"] or "").split(",") if t.strip()]
    excl = [t.strip().lower() for t in (args["exclude"] or "").split(",") if t.strip()]
    if only or excl:
        dropped = []
        for obj in list(bpy.context.scene.objects):
            if obj.type != "MESH":
                continue
            n = obj.name.lower()
            if (only and not any(t in n for t in only)) or any(t in n for t in excl):
                dropped.append(obj.name)
                bpy.data.objects.remove(obj, do_unlink=True)
        print("[kit_cut] filtered out %d object(s): %s"
              % (len(dropped), ", ".join(dropped[:8]) + (" ..." if len(dropped) > 8 else "")))

    src, mat_names = join_scene_geometry()
    if not src.faces:
        print("[kit_cut] no geometry")
        sys.exit(2)

    standable_raw = standable_points()
    mn, mx = bounds_of(src)
    floor_z = dominant_floor_z(src)
    print("[kit_cut] %d standable sample points" % len(standable_raw))
    print("[kit_cut] input %.2f x %.2f x %.2f m, %d tris, %d materials"
          % (mx.x - mn.x, mx.y - mn.y, mx.z - mn.z, len(src.faces), len(mat_names)))
    print("[kit_cut] dominant floor plane at z = %s" % floor_z)

    if args["align"] == "auto":
        # x/y: min corner to a cell corner.  z: the walkable plane to y=0, which is
        # where Kit_Floor_4x4 puts its own top surface, so modules drop straight in.
        dz = -(floor_z if floor_z is not None else mn.z)
        off = Vector((-mn.x, -mn.y, dz))
        bmesh.ops.translate(src, verts=src.verts[:], vec=off)
        # the ray casts ran against the untranslated SCENE, so carry them over
        standable_raw = [(p[0] + off.x, p[1] + off.y, p[2] + off.z) for p in standable_raw]
        mn, mx = bounds_of(src)
        print("[kit_cut] aligned: floor plane -> y 0, min corner -> origin")

    n_planes = slice_to_grid(src, cell, 0.0, 0.0)
    print("[kit_cut] bisected on %d cell boundaries -> %d tris" % (n_planes, len(src.faces)))

    cells = {}
    for f in src.faces:
        cells.setdefault(cell_of(f, cell, 0.0, 0.0), []).append(f)

    manifest = {"source": os.path.abspath(blend), "cell_m": cell,
                "storey_m": STOREY, "min_collider_thickness_m": MIN_COLLIDER_THICKNESS,
                "walkable_plane_y": 0.0, "materials": mat_names, "modules": []}

    if outdir and not args["dry_run"]:
        os.makedirs(outdir, exist_ok=True)
        if args["skip_textures"]:
            print("[kit_cut] --skip-textures: reusing the Textures folder as it is")
            old_path = os.path.join(outdir, "%s_modules.json" % args["prefix"])
            if os.path.exists(old_path):
                with open(old_path, encoding="utf-8") as f:
                    manifest["material_maps"] = json.load(f).get("material_maps", [])
        else:
            manifest["material_maps"] = export_materials(
                mat_names, os.path.join(outdir, "Textures"), int(args["tex_max"]))

    for (cx, cy), faces in sorted(cells.items()):
        tris = len(faces)
        if tris < 4:
            continue
        name = "%s_%02d_%02d" % (args["prefix"], cx, cy)

        # module-local space: origin at the bottom-centre of the CELL footprint,
        # with the walkable plane at y = 0 (the alignment above already put it there)
        ox = (cx + 0.5) * cell
        oy = (cy + 0.5) * cell

        boxes = solid_boxes(faces, standable_raw)
        wedge = stair_wedge(faces)
        if wedge:
            # a flight replaces the stepped slabs inside its own footprint
            boxes = [b for b in boxes
                     if not (b[0][2] >= wedge["lo_z"] - 0.05 and b[1][2] <= wedge["hi_z"] + 0.25)]

        bad = intruding_boxes(boxes, standable_raw)
        # The boxes are AABBs, so they ship as DATA and become real Unity
        # BoxColliders. Baking them into one collision MESH would also defeat
        # Law 7's per-collider check: the union's bounds are metres thick on every
        # axis, so the assertion would pass no matter how thin the parts are.
        local_boxes = [{"center": [round((b[0][i] + b[1][i]) / 2 - (ox, oy, 0)[i], 4)
                                   for i in range(3)],
                        "size": [round(b[1][i] - b[0][i], 4) for i in range(3)]}
                       for b in boxes]
        b_mn = [1e9, 1e9, 1e9]
        b_mx = [-1e9, -1e9, -1e9]
        for f in faces:
            for v in f.verts:
                for i in range(3):
                    b_mn[i] = min(b_mn[i], v.co[i] - (ox, oy, 0)[i])
                    b_mx[i] = max(b_mx[i], v.co[i] - (ox, oy, 0)[i])
        rec = {"name": name, "cell": [cx, cy], "tris": tris,
               "bounds_min_blender": [round(c, 4) for c in b_mn],
               "bounds_max_blender": [round(c, 4) for c in b_mx],
               "collider_boxes": len(boxes), "boxes": local_boxes,
               "stair_wedge": bool(wedge),
               "colliders_intruding_into_walkable_space": len(bad)}
        if bad:
            print("  !! %s: %d collider box(es) contain standable space - "
                  "invisible obstruction" % (name, len(bad)))
            for b, n_pts in bad:
                print("       x %.2f..%.2f  y %.2f..%.2f  z %.2f..%.2f  (%d sample pts inside)"
                      % (b[0][0], b[1][0], b[0][1], b[1][1], b[0][2], b[1][2], n_pts))
        if wedge:
            rec["wedge"] = {"risers": wedge["risers"], "rise_m": wedge["rise"],
                            "climb_m": round(wedge["hi_z"] - wedge["lo_z"], 3)}
        manifest["modules"].append(rec)

        if args["dry_run"]:
            print("  %-18s %6d tris  %d collider box(es)%s"
                  % (name, tris, len(boxes), "  + stair wedge" if wedge else ""))
            continue

        # ---- render mesh ----
        # Only the materials this module ACTUALLY uses. Appending the whole global
        # list gave every module 14 material slots, and a slot is a submesh, and a
        # submesh is a draw call - 112 of them for one corridor.
        used = sorted({f.material_index for f in faces})
        remap = {old_i: i for i, old_i in enumerate(used)}

        rend = bmesh.new()
        # UVs live on LOOPS, not on vertices, so rebuilding a mesh vertex by vertex
        # silently drops every texture coordinate. The modules came out geometrically
        # perfect and completely untextured, and nothing anywhere reported a problem:
        # the materials were right, the textures were right, and there was no surface
        # to put them on.
        uv_names = list(src.loops.layers.uv.keys())
        src_uvs = [src.loops.layers.uv[n] for n in uv_names]
        dst_uvs = [rend.loops.layers.uv.new(n) for n in uv_names]

        vmap = {}
        for f in faces:
            nv = []
            for v in f.verts:
                if v not in vmap:
                    vmap[v] = rend.verts.new((v.co.x - ox, v.co.y - oy, v.co.z))
                nv.append(vmap[v])
            try:
                nf = rend.faces.new(nv)
            except ValueError:
                continue
            nf.material_index = remap[f.material_index]
            nf.smooth = f.smooth
            for l_new, l_old in zip(nf.loops, f.loops):
                for du, su in zip(dst_uvs, src_uvs):
                    l_new[du].uv = l_old[su].uv
        rend.normal_update()

        me = bpy.data.meshes.new(name)
        rend.to_mesh(me)
        rend.free()
        for oi in used:
            me.materials.append(bpy.data.materials.get(mat_names[oi]))
        rec["materials"] = [mat_names[oi] for oi in used]

        # ---- collision proxy: only the wedge needs to be geometry ----
        col = bmesh.new()
        if wedge:
            w = dict(wedge)
            w["min"] = [wedge["min"][0] - ox, wedge["min"][1] - oy]
            w["max"] = [wedge["max"][0] - ox, wedge["max"][1] - oy]
            build_wedge_mesh(col, w)
        col_me = bpy.data.meshes.new(name + "_COL_WEDGE")
        col.to_mesh(col_me)
        col.free()

        # ---- export one FBX per module ----
        for o in list(bpy.context.scene.objects):
            bpy.data.objects.remove(o, do_unlink=True)
        ro = bpy.data.objects.new(name, me)
        bpy.context.scene.collection.objects.link(ro)
        if wedge:
            co = bpy.data.objects.new(name + "_COL_WEDGE", col_me)
            bpy.context.scene.collection.objects.link(co)

        path = os.path.join(outdir, name + ".fbx")
        if not hasattr(bpy.ops.export_scene, "fbx"):
            bpy.ops.preferences.addon_enable(module="io_scene_fbx")
        bpy.ops.export_scene.fbx(
            filepath=path, use_selection=False,
            apply_scale_options="FBX_SCALE_UNITS", axis_forward="-Z", axis_up="Y",
            object_types={"MESH"}, use_mesh_modifiers=True, mesh_smooth_type="FACE",
            # bake_space_transform=True, and it is load-bearing. With it False the
            # exporter leaves the vertices in Blender orientation and puts the axis
            # conversion on the OBJECT NODE. Unity honours that node when you
            # instantiate the FBX, but the Mesh asset itself is still Z-up - and the
            # kit prefabs are built from the mesh, so every module arrived lying on
            # its side with the wall height running along Unity Z.
            use_tspace=True, bake_space_transform=True, path_mode="STRIP")
        rec["fbx"] = path
        print("  %-18s %6d tris  %d collider box(es)%s -> %s"
              % (name, tris, len(boxes), "  + stair wedge" if wedge else "",
                 os.path.basename(path)))

    src.free()
    if outdir and not args["dry_run"]:
        mpath = os.path.join(outdir, "%s_modules.json" % args["prefix"])
        with open(mpath, "w", encoding="utf-8") as f:
            json.dump(manifest, f, indent=2)
        print("[kit_cut] manifest -> %s" % mpath)
    print("[kit_cut] %d module(s)" % len(manifest["modules"]))


main()
