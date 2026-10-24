"""Runs INSIDE Blender: builds the whole catalog as real geometry and writes .glb.

    blender --background --factory-startup --python tools/blender_models.py -- --out DIR

Nothing here is modelled by hand. Every shape is a primitive or a generated
vertex ring, so the roster is reproducible from source and a change to a colour
or a proportion is a one-line edit rather than a re-sculpt.

Two rules the game depends on, enforced by `normalise()` on every model:
  * the base sits at z = 0, so the renderer can drop a model straight onto the
    ground without knowing anything about it;
  * the model is EXACTLY 1.0 unit tall, so the existing `scale = stage height`
    instancing works unchanged.

The catalog's procedural shapes are not all one unit tall for their own stage
height -- a tree's foliage sits at 1.35h and a hab's dome tops out at 0.62h --
so each model carries a `fit` multiplier that the runtime applies once at load.
That keeps the silhouette the game already has while the geometry underneath
becomes a real model. Poly counts are deliberately tiny: this ships to a phone.
"""

import json
import math
import os
import sys

import bpy

# --------------------------------------------------------------------- colour

def _lin(c):
    """sRGB 0..1 to linear. glTF baseColorFactor is linear; so is Blender."""
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def rgb(hexv):
    r = _lin(((hexv >> 16) & 255) / 255.0)
    g = _lin(((hexv >> 8) & 255) / 255.0)
    b = _lin((hexv & 255) / 255.0)
    return (r, g, b)


def _principled(m):
    for n in m.node_tree.nodes:
        if n.type == 'BSDF_PRINCIPLED':
            return n
    return None


def _set(node, names, value):
    for n in names:
        if n in node.inputs:
            node.inputs[n].default_value = value
            return True
    return False


def mat(name, colour, rough=0.9, metal=0.0, emit=None, strength=1.6):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    # The exporter writes doubleSided = not use_backface_culling, and a
    # double-sided material shades every back face of every instance. Nothing
    # here is a plane you can see the far side of, so cull.
    if hasattr(m, 'use_backface_culling'):
        m.use_backface_culling = True
    b = _principled(m)
    if b is None:
        return m
    r, g, bl = rgb(colour)
    _set(b, ['Base Color', 'Base Colour'], (r, g, bl, 1.0))
    _set(b, ['Roughness'], rough)
    _set(b, ['Metallic'], metal)
    if emit is not None:
        er, eg, eb = rgb(emit)
        _set(b, ['Emission Color', 'Emission Colour', 'Emission'], (er, eg, eb, 1.0))
        _set(b, ['Emission Strength'], strength)
    return m


# ----------------------------------------------------------------- primitives

def _new(ob, m, flat):
    ob.data.materials.append(m)
    bpy.ops.object.select_all(action='DESELECT')
    ob.select_set(True)
    bpy.context.view_layer.objects.active = ob
    if flat:
        bpy.ops.object.shade_flat()
    else:
        bpy.ops.object.shade_smooth()
    return ob


def _added():
    return bpy.context.view_layer.objects.active


def cyl(m, r1, r2, depth, z, verts=6, x=0.0, y=0.0, rot=(0, 0, 0), flat=True):
    """A tapered cylinder. r2 == 0 is a cone, r1 == r2 is a post."""
    bpy.ops.mesh.primitive_cone_add(vertices=verts, radius1=r1, radius2=r2,
                                    depth=depth, location=(x, y, z), rotation=rot)
    return _new(_added(), m, flat)


def ico(m, r, z, subdiv=1, x=0.0, y=0.0, sz=1.0, flat=True):
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=subdiv, radius=r,
                                          location=(x, y, z))
    ob = _added()
    ob.scale = (1.0, 1.0, sz)
    return _new(ob, m, flat)


def box(m, sx, sy, sz, z, x=0.0, y=0.0, rot=(0, 0, 0), flat=True):
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=(x, y, z), rotation=rot,
                                    scale=(sx, sy, sz))
    return _new(_added(), m, flat)


def torus(m, major, minor, z, mseg=12, nseg=5, x=0.0, y=0.0, rot=(0, 0, 0), flat=False):
    bpy.ops.mesh.primitive_torus_add(major_radius=major, minor_radius=minor,
                                     major_segments=mseg, minor_segments=nseg,
                                     location=(x, y, z), rotation=rot)
    return _new(_added(), m, flat)


def disc(m, r, z, seg=16, rot=(0, 0, 0)):
    """A flat n-gon. `primitive_circle_add` with a fill, for gate membranes."""
    bpy.ops.mesh.primitive_circle_add(vertices=seg, radius=r, fill_type='NGON',
                                      location=(0, 0, z), rotation=rot)
    return _new(_added(), m, False)


def uvs(m, r, z, seg=10, ring=6, x=0.0, y=0.0, sz=1.0, flat=False):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=seg, ring_count=ring, radius=r,
                                         location=(x, y, z))
    ob = _added()
    ob.scale = (1.0, 1.0, sz)
    return _new(ob, m, flat)


def poly(name, m, verts, faces, flat=True):
    me = bpy.data.meshes.new(name)
    me.from_pydata([tuple(v) for v in verts], [], [tuple(f) for f in faces])
    me.update()
    ob = bpy.data.objects.new(name, me)
    bpy.context.collection.objects.link(ob)
    return _new(ob, m, flat)


def octa(m, r, h, z, x=0.0, y=0.0, tilt=0.0):
    """An eight-faced shard. Blender has no octahedron primitive, so generate it."""
    v = [(0, 0, h * 0.5), (r, 0, 0), (0, r, 0), (-r, 0, 0), (0, -r, 0), (0, 0, -h * 0.5)]
    f = [(0, 1, 2), (0, 2, 3), (0, 3, 4), (0, 4, 1),
         (5, 2, 1), (5, 3, 2), (5, 4, 3), (5, 1, 4)]
    ob = poly('shard', m, v, f)
    ob.location = (x, y, z)
    ob.rotation_euler = (tilt, 0.0, 0.0)
    return ob


def dome(m, r, h, z, seg=12, ring=4, flat=False):
    """A closed hemisphere. Cheaper and more controllable than cutting a sphere."""
    verts = [(0.0, 0.0, h)]
    for i in range(1, ring + 1):
        t = (i / ring) * (math.pi * 0.5)
        rr, zz = r * math.sin(t), h * math.cos(t)
        for j in range(seg):
            a = (j / seg) * math.pi * 2.0
            verts.append((rr * math.cos(a), rr * math.sin(a), zz))
    faces = []
    for j in range(seg):
        faces.append((0, 1 + j, 1 + (j + 1) % seg))
    for i in range(ring - 1):
        a0, a1 = 1 + i * seg, 1 + (i + 1) * seg
        for j in range(seg):
            k = (j + 1) % seg
            faces.append((a0 + j, a1 + j, a1 + k, a0 + k))
    base = 1 + (ring - 1) * seg
    faces.append(tuple(range(base + seg - 1, base - 1, -1)))
    ob = poly('dome', m, verts, faces, flat=flat)
    ob.location = (0.0, 0.0, z)
    return ob


# ---------------------------------------------------------------- scene admin

def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def apply_all(ob):
    bpy.ops.object.select_all(action='DESELECT')
    ob.select_set(True)
    bpy.context.view_layer.objects.active = ob
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)


def join_all(name):
    obs = [o for o in bpy.context.scene.objects if o.type == 'MESH']
    for o in obs:
        apply_all(o)
    bpy.ops.object.select_all(action='DESELECT')
    for o in obs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = obs[0]
    if len(obs) > 1:
        bpy.ops.object.join()
    ob = bpy.context.view_layer.objects.active
    ob.name = name
    return ob


def normalise(ob, height=1.0):
    """Base at z=0, centred on x/y, exactly `height` tall. The renderer relies
    on both halves of that: it drops the origin on the ground and scales by the
    stage height, so a model that is 1.4 tall would sink or float."""
    apply_all(ob)
    vs = ob.data.vertices
    xs = [v.co.x for v in vs]
    ys = [v.co.y for v in vs]
    zs = [v.co.z for v in vs]
    cx, cy = (min(xs) + max(xs)) * 0.5, (min(ys) + max(ys)) * 0.5
    lo, hi = min(zs), max(zs)
    s = height / (hi - lo) if hi > lo else 1.0
    for v in vs:
        v.co.x = (v.co.x - cx) * s
        v.co.y = (v.co.y - cy) * s
        v.co.z = (v.co.z - lo) * s
    ob.data.update()
    return ob


def tri_count(ob):
    me = ob.data
    try:
        me.calc_loop_triangles()
        return len(me.loop_triangles)
    except Exception:
        return sum(max(0, len(p.vertices) - 2) for p in me.polygons)


_EXPORT_KW = dict(
    export_format='GLB',
    use_selection=False,
    export_apply=True,
    export_yup=True,
    export_normals=True,
    export_materials='EXPORT',
    export_cameras=False,
    export_lights=False,
    export_animations=False,
    export_extras=False,
    export_tangents=False,
    export_texcoords=False,
    export_skins=False,
    export_draco_mesh_compression_enable=False,
)


def export_glb(path):
    # The operator's property set moves between Blender versions; ask it what it
    # takes rather than guessing, so a rename is a dropped option not a crash.
    props = {p.identifier for p in bpy.ops.export_scene.gltf.get_rna_type().properties}
    kw = {k: v for k, v in _EXPORT_KW.items() if k in props}
    kw['filepath'] = path
    bpy.ops.export_scene.gltf(**kw)


# --------------------------------------------------------------------- models
# Colours mirror render.js so a loaded model reads as the same object as the
# procedural one it replaces.

def build_tree():
    bark = mat('bark', 0x5B4432, rough=0.95)
    leaf = mat('leaf', 0x39804A, rough=0.85)
    leaf2 = mat('leaf_dark', 0x2F6B3F, rough=0.85)
    cyl(bark, 0.085, 0.055, 0.62, 0.31, verts=6)
    cyl(bark, 0.030, 0.014, 0.20, 0.54, verts=4, x=0.09, rot=(0, math.radians(38), 0))
    ico(leaf, 0.27, 0.70, subdiv=1, sz=0.85)
    ico(leaf2, 0.20, 0.88, subdiv=1, x=0.07, sz=0.9)
    ico(leaf2, 0.15, 0.66, subdiv=1, x=-0.20, y=0.05, sz=0.9)
    return 'tree'


def build_bloom():
    stem = mat('stem', 0x2F7A4A, rough=0.8)
    glow = mat('bloom_glow', 0x8FF0C0, rough=0.35, emit=0x38C98A, strength=2.2)
    cyl(stem, 0.055, 0.032, 0.56, 0.28, verts=5)
    for a in range(3):
        th = a * (math.pi * 2 / 3)
        cyl(stem, 0.075, 0.0, 0.30, 0.16,
            verts=4, x=math.cos(th) * 0.11, y=math.sin(th) * 0.11,
            rot=(math.radians(72) * math.sin(th), math.radians(-72) * math.cos(th), 0))
    ico(glow, 0.21, 0.72, subdiv=2)
    ico(glow, 0.09, 0.92, subdiv=1)
    return 'bloom'


def build_crystal():
    ice = mat('crystal', 0x9FD8F2, rough=0.25, metal=0.3, emit=0x2E7FA8, strength=1.4)
    octa(ice, 0.20, 1.00, 0.50)
    octa(ice, 0.11, 0.52, 0.26, x=0.17, tilt=math.radians(11))
    octa(ice, 0.08, 0.34, 0.17, x=-0.15, y=0.09, tilt=math.radians(-13))
    return 'crystal'


def build_kelp():
    frond = mat('kelp', 0x2F8F7A, rough=0.55, emit=0x1D6B58, strength=1.2)
    for i, (x, y, lean) in enumerate([(0.0, 0.0, 4.0), (0.075, 0.05, -11.0), (-0.07, -0.04, 9.0)]):
        h = [1.0, 0.72, 0.60][i]
        cyl(frond, 0.045 * h, 0.012, h, h * 0.5, verts=4, x=x, y=y,
            rot=(math.radians(lean), 0, 0))
    for z, sx in [(0.34, 0.13), (0.56, 0.11), (0.76, 0.08)]:
        box(frond, sx, 0.012, 0.11, z, x=sx * 0.5, rot=(0, math.radians(24), 0))
    return 'kelp'


def build_hab():
    shell = mat('hab_shell', 0xB9C3CF, rough=0.6, metal=0.2)
    trim = mat('hab_trim', 0x7C8695, rough=0.7, metal=0.3)
    lit = mat('hab_light', 0xFFD9A0, rough=0.4, emit=0xFFB44D, strength=2.4)
    dome(shell, 0.62, 0.62, 0.0, seg=12, ring=4, flat=False)
    torus(trim, 0.62, 0.035, 0.02, mseg=12, nseg=4)
    box(trim, 0.30, 0.12, 0.40, 0.20, y=0.58)
    box(lit, 0.17, 0.05, 0.24, 0.17, y=0.635)
    cyl(trim, 0.07, 0.07, 0.16, 0.60, verts=6, x=0.22)
    return 'hab'


def build_mast():
    steel = mat('mast_steel', 0x8B95A3, rough=0.6, metal=0.4)
    lamp = mat('mast_lamp', 0xFF6B5A, rough=0.4, emit=0xFF4A33, strength=3.0)
    cyl(steel, 0.055, 0.022, 0.92, 0.46, verts=5)
    for z, w in [(0.42, 0.24), (0.60, 0.18), (0.76, 0.12)]:
        box(steel, w * 2, 0.022, 0.022, z)
        box(steel, 0.022, w * 2, 0.022, z)
    for a in range(3):
        th = a * (math.pi * 2 / 3)
        cyl(steel, 0.016, 0.010, 0.46, 0.20, verts=3,
            x=math.cos(th) * 0.10, y=math.sin(th) * 0.10,
            rot=(math.radians(19) * math.sin(th), math.radians(-19) * math.cos(th), 0))
    uvs(lamp, 0.075, 0.94, seg=8, ring=6)
    return 'mast'


def build_kiln():
    stone = mat('kiln_stone', 0x6D5C50, rough=0.9)
    flue = mat('kiln_flue', 0x4C4038, rough=0.9)
    fire = mat('kiln_fire', 0xFF8A3C, rough=0.5, emit=0xFF5A1A, strength=3.2)
    box(stone, 0.78, 0.78, 0.46, 0.23)
    cyl(stone, 0.40, 0.30, 0.16, 0.54, verts=8)
    cyl(flue, 0.12, 0.09, 0.34, 0.79, verts=6, x=0.13)
    cyl(flue, 0.15, 0.15, 0.05, 0.96, verts=6, x=0.13)
    box(fire, 0.20, 0.06, 0.20, 0.16, y=0.40)
    box(stone, 0.30, 0.10, 0.06, 0.02, y=0.42)
    return 'kiln'


def build_beacon():
    post = mat('beacon_post', 0x7C8695, rough=0.6, metal=0.4)
    neon_a = mat('beacon_neon_a', 0xFF4FD8, rough=0.3, emit=0xFF2FC8, strength=4.0)
    neon_b = mat('beacon_neon_b', 0x4FE8FF, rough=0.3, emit=0x2FD8FF, strength=4.0)
    cyl(post, 0.055, 0.028, 0.95, 0.475, verts=6)
    cyl(post, 0.17, 0.13, 0.05, 0.025, verts=8)
    torus(neon_a, 0.34, 0.045, 0.62, mseg=14, nseg=5)
    torus(neon_b, 0.23, 0.035, 0.80, mseg=12, nseg=5)
    ico(neon_a, 0.075, 0.96, subdiv=1)
    return 'beacon'


def build_tower():
    clad = mat('tower_clad', 0x8E97A6, rough=0.7, metal=0.2)
    neon_a = mat('tower_neon_a', 0xFF4FD8, rough=0.3, emit=0xFF2FC8, strength=3.0)
    neon_b = mat('tower_neon_b', 0x4FE8FF, rough=0.3, emit=0x2FD8FF, strength=3.0)
    cyl(clad, 0.33, 0.24, 0.86, 0.43, verts=4, rot=(0, 0, math.radians(45)))
    box(clad, 0.42, 0.42, 0.05, 0.885)
    cyl(clad, 0.05, 0.02, 0.20, 0.98, verts=4)
    for z, m in [(0.30, neon_b), (0.52, neon_a), (0.74, neon_b)]:
        box(m, 0.50, 0.50, 0.028, z, rot=(0, 0, math.radians(45)))
    ico(neon_a, 0.05, 1.07, subdiv=1)
    return 'tower'


def build_astronaut():
    suit = mat('suit', 0xE9EDF2, rough=0.55, metal=0.1, emit=0x2A3242, strength=1.0)
    dark = mat('suit_dark', 0x59647A, rough=0.7, metal=0.2, emit=0x1A2130, strength=1.0)
    visor = mat('visor', 0x1B2B3A, rough=0.18, metal=0.6, emit=0x3FA8D8, strength=1.4)
    lampm = mat('lamp', 0xFFF0C0, rough=0.4, emit=0xFFF0C0, strength=3.0)
    # torso
    uvs(suit, 0.155, 0.62, seg=10, ring=6, sz=1.55)
    box(dark, 0.22, 0.13, 0.26, 0.60, y=-0.16)
    # helmet
    uvs(suit, 0.145, 0.885, seg=12, ring=8)
    uvs(visor, 0.122, 0.885, seg=12, ring=8, y=0.05)
    uvs(lampm, 0.045, 0.90, seg=6, ring=4, x=0.125, y=0.05)
    # arms
    for sx in (-1, 1):
        cyl(suit, 0.052, 0.040, 0.34, 0.63, verts=6, x=sx * 0.19,
            rot=(0, math.radians(sx * 7), 0))
        uvs(dark, 0.055, 0.455, seg=8, ring=5, x=sx * 0.205)
    # legs
    for sx in (-1, 1):
        cyl(dark, 0.062, 0.050, 0.44, 0.23, verts=6, x=sx * 0.085)
        box(dark, 0.13, 0.20, 0.06, 0.03, x=sx * 0.085, y=0.03)
    return 'astronaut'


# ---- the buildings that cause cosmic events -------------------------------
# Colours and proportions read straight off render.js's own draw table, so a
# loaded one reads as the same building as the procedural one it replaces. They
# are OPTIONAL in the strongest sense: these kinds are being added to the
# catalog as this is written, and if an id ends up spelled differently the model
# is simply never asked for and the procedural shape stands.

def build_lattice():
    frame = mat('lattice_frame', 0x39404F, rough=0.5, metal=0.6)
    ring = mat('lattice_ring', 0x2A2F3D, rough=0.4, metal=0.8, emit=0x140B22, strength=1.0)
    lit = mat('lattice_lit', 0x7A4FD8, rough=0.3, emit=0x6A2FD8, strength=3.4)
    core = mat('lattice_core', 0x05050A, rough=0.1, metal=0.9, emit=0x1A0A2E, strength=1.2)
    cyl(frame, 0.10, 0.05, 0.98, 0.49, verts=6)
    torus(ring, 0.40, 0.045, 0.55, mseg=14, nseg=5)
    torus(lit, 0.28, 0.035, 0.78, mseg=12, nseg=5)
    ico(core, 0.14, 0.66, subdiv=1)
    return 'lattice'


def build_wormgate():
    frame = mat('gate_frame', 0x8E97A6, rough=0.4, metal=0.7, emit=0x1D5F6E, strength=1.0)
    film = mat('gate_film', 0x63F0E0, rough=0.2, emit=0x3FD8C8, strength=3.0)
    base = mat('gate_base', 0x6B7382, rough=0.8)
    up = (math.radians(90), 0, 0)
    torus(frame, 0.42, 0.075, 0.52, mseg=20, nseg=6, rot=up)
    disc(film, 0.36, 0.52, seg=18, rot=up)
    box(base, 0.52, 0.22, 0.10, 0.05)
    cyl(base, 0.05, 0.05, 0.16, 0.10, verts=4, x=0.40)
    cyl(base, 0.05, 0.05, 0.16, 0.10, verts=4, x=-0.40)
    return 'wormgate'


def build_lantern():
    post = mat('lantern_post', 0x4A4033, rough=0.9)
    glow = mat('lantern_glow', 0xFFE6A0, rough=0.3, emit=0xFFC060, strength=3.4)
    cyl(post, 0.07, 0.04, 0.80, 0.40, verts=5)
    cyl(post, 0.11, 0.09, 0.05, 0.025, verts=6)
    ico(glow, 0.19, 0.86, subdiv=2)
    cyl(post, 0.03, 0.02, 0.08, 1.02, verts=4)
    return 'lantern'


def build_luremast():
    steel = mat('lure_steel', 0x7D8695, rough=0.6, metal=0.5)
    fire = mat('lure_fire', 0xFF8A3C, rough=0.35, emit=0xFF5A1A, strength=3.2)
    cyl(steel, 0.09, 0.04, 0.80, 0.40, verts=5)
    torus(fire, 0.18, 0.025, 0.60, mseg=12, nseg=4)
    cyl(fire, 0.21, 0.0, 0.32, 0.90, verts=6)
    for a in range(3):
        th = a * (math.pi * 2 / 3)
        cyl(steel, 0.015, 0.010, 0.34, 0.17, verts=3,
            x=math.cos(th) * 0.09, y=math.sin(th) * 0.09,
            rot=(math.radians(15) * math.sin(th), math.radians(-15) * math.cos(th), 0))
    return 'luremast'


def build_crater():
    rock = mat('crater_rock', 0x4A423C, rough=0.98)
    ember = mat('crater_ember', 0xD8934A, rough=0.6, emit=0x7A3A10, strength=2.0)
    # Wide and shallow: this one is a scar, not a building. It reads as a
    # crater only if the rim is much wider than the mound is tall.
    torus(rock, 0.90, 0.10, 0.10, mseg=16, nseg=5, flat=True)
    ico(ember, 0.26, 0.12, subdiv=1, sz=0.55)
    ico(rock, 0.12, 0.06, subdiv=1, x=0.52, y=0.28, sz=0.6)
    ico(rock, 0.09, 0.05, subdiv=1, x=-0.46, y=-0.35, sz=0.6)
    return 'crater'


def build_anchor():
    steel = mat('anchor_steel', 0x9AA4B3, rough=0.45, metal=0.7)
    ring = mat('anchor_ring', 0xBFE9FF, rough=0.3, emit=0x4FA8FF, strength=3.0)
    orb = mat('anchor_orb', 0xDFE9F5, rough=0.3, emit=0x8FD0FF, strength=2.2)
    cyl(steel, 0.13, 0.06, 0.82, 0.41, verts=6)
    cyl(steel, 0.20, 0.16, 0.06, 0.03, verts=8)
    torus(ring, 0.30, 0.045, 0.76, mseg=16, nseg=5)
    uvs(orb, 0.115, 0.92, seg=10, ring=8)
    return 'anchor'


def build_anomaly():
    skin = mat('anomaly', 0xC07FE8, rough=0.2, emit=0x8A2FD8, strength=2.6)
    octa(skin, 0.28, 0.86, 0.43)
    octa(skin, 0.13, 0.34, 0.62, x=0.20, tilt=math.radians(28))
    octa(skin, 0.10, 0.26, 0.24, x=-0.19, y=0.12, tilt=math.radians(-24))
    return 'anomaly'


def build_riftengine():
    case = mat('rift_case', 0x5B5468, rough=0.7, metal=0.3)
    blade = mat('rift_blade', 0xD86FFF, rough=0.25, emit=0xC02FFF, strength=3.6)
    box(case, 0.62, 0.44, 0.42, 0.21)
    box(case, 0.70, 0.10, 0.06, 0.42)
    box(blade, 0.07, 0.07, 0.88, 0.52)
    cyl(case, 0.06, 0.06, 0.10, 0.05, verts=4, x=0.26, y=0.16)
    cyl(case, 0.06, 0.06, 0.10, 0.05, verts=4, x=-0.26, y=-0.16)
    return 'riftengine'


def build_stellartap():
    steel = mat('tap_steel', 0x8B95A3, rough=0.5, metal=0.6)
    orb = mat('tap_orb', 0xFFF0C0, rough=0.3, emit=0xFFC040, strength=3.8)
    ring = mat('tap_ring', 0xFFD070, rough=0.35, emit=0xFFA020, strength=2.6)
    cyl(steel, 0.12, 0.035, 0.82, 0.41, verts=6)
    torus(ring, 0.28, 0.025, 0.69, mseg=14, nseg=4)
    uvs(orb, 0.17, 0.85, seg=10, ring=8)
    return 'stellartap'


# `fit` restores the silhouette the procedural renderer already draws: its parts
# sit at fractions of the stage height that do not stop at 1.0 (a tree's canopy
# tops out at 1.67h, a hab's dome at 0.62h). The model is a unit tall; the
# runtime multiplies by this once, so instancing still just scales by height.
MODELS = [
    ('tree', build_tree, 1.67),
    ('bloom', build_bloom, 1.21),
    ('crystal', build_crystal, 0.68),
    ('kelp', build_kelp, 1.00),
    ('hab', build_hab, 0.62),
    ('mast', build_mast, 1.18),
    ('kiln', build_kiln, 1.30),
    ('beacon', build_beacon, 1.07),
    ('tower', build_tower, 1.00),
    ('lattice', build_lattice, 1.00),
    ('wormgate', build_wormgate, 1.05),
    ('lantern', build_lantern, 1.24),
    ('luremast', build_luremast, 1.22),
    ('crater', build_crater, 0.36),
    ('anchor', build_anchor, 1.18),
    ('anomaly', build_anomaly, 0.80),
    ('riftengine', build_riftengine, 1.12),
    ('stellartap', build_stellartap, 1.22),
    ('astronaut', build_astronaut, 1.00),
]


def main():
    argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    out = 'build/models'
    for i, a in enumerate(argv):
        if a == '--out' and i + 1 < len(argv):
            out = argv[i + 1]
    out = os.path.abspath(out)
    os.makedirs(out, exist_ok=True)

    meta = {'blender': bpy.app.version_string, 'models': {}}
    for kind, build, fit in MODELS:
        reset()
        name = build()
        ob = join_all(name)
        normalise(ob, 1.0)
        tris = tri_count(ob)
        path = os.path.join(out, kind + '.glb')
        export_glb(path)
        meta['models'][kind] = {
            'file': kind + '.glb',
            'tris': tris,
            'verts': len(ob.data.vertices),
            'materials': len(ob.data.materials),
            'fit': fit,
        }
        print('MODEL %-10s %5d tris  %4d verts  %d mats' %
              (kind, tris, len(ob.data.vertices), len(ob.data.materials)))

    with open(os.path.join(out, '_meta.json'), 'w', encoding='utf-8') as f:
        json.dump(meta, f, indent=1)
    print('META %s' % os.path.join(out, '_meta.json'))


main()
