#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Hand-authors Assets/_Project/Art/Shaders/Recolor.shadergraph for
'Aftermarket Apocalypse' (Unity 6000.3.1f1, URP 17.x, ShaderGraph 17.x).

Format derived from the real, currently-importing graph
Assets/TextMesh Pro/Shaders/TMP_SDF-URP Lit.shadergraph plus the node classes in
Library/PackageCache/com.unity.shadergraph@252d58e80e1a/Editor/.

A .shadergraph is ShaderGraph MultiJson: a stream of JSON objects separated by
blank lines, the first being the GraphData.
"""
import hashlib
import io
import json
import os
import sys

# Derived from this file's own location, not typed. It WAS an absolute literal
# ("D:\projects\Aftermarket Apocalypse") while this script lived
# outside the repo; now that it ships inside Tools/shadergraph/, a hardcoded path
# would keep writing to wherever the project used to be — silently, since the
# script has no way to notice it regenerated the wrong copy.
PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(PROJ, "Assets", "_Project", "Art", "Shaders", "Recolor.shadergraph")

_objs = []


def oid(key):
    """Deterministic 32-hex object id from a stable key."""
    return hashlib.md5(("aftermarket.recolor." + key).encode("utf-8")).hexdigest()


def guid(key):
    h = hashlib.md5(("aftermarket.recolor.guid." + key).encode("utf-8")).hexdigest()
    return "%s-%s-%s-%s-%s" % (h[0:8], h[8:12], h[12:16], h[16:20], h[20:32])


def add(o):
    _objs.append(o)
    return o["m_ObjectId"]


def ref(i):
    return {"m_Id": i}


def drawstate(x, y, w=200.0, h=100.0):
    return {"m_Expanded": True,
            "m_Position": {"serializedVersion": "2", "x": float(x), "y": float(y),
                           "width": float(w), "height": float(h)}}


def node_common(key, name, x, y, w=200.0, h=100.0, slots=(), synonyms=()):
    return {
        "m_ObjectId": oid(key),
        "m_Group": {"m_Id": ""},
        "m_Name": name,
        "m_DrawState": drawstate(x, y, w, h),
        "m_Slots": [ref(s) for s in slots],
        "synonyms": list(synonyms),
        "m_Precision": 0,
        "m_PreviewExpanded": True,
        "m_DismissedVersion": 0,
        "m_PreviewMode": 0,
        "m_CustomColors": {"m_SerializableColors": []},
    }


# ---------------------------------------------------------------- slots


def slot_v1(key, sid, disp, out, stype, value=0.0, labels=None, stage=3):
    return add({"m_SGVersion": 0, "m_Type": "UnityEditor.ShaderGraph.Vector1MaterialSlot",
                "m_ObjectId": oid(key), "m_Id": sid, "m_DisplayName": disp,
                "m_SlotType": stype, "m_Hidden": False, "m_ShaderOutputName": out,
                "m_StageCapability": stage, "m_Value": value, "m_DefaultValue": 0.0,
                "m_Labels": labels if labels is not None else []})


def slot_vec(key, sid, disp, out, stype, n, stage=3, labels=None):
    t = {2: "Vector2MaterialSlot", 3: "Vector3MaterialSlot", 4: "Vector4MaterialSlot"}[n]
    comp = ["x", "y", "z", "w"][:n]
    zero = dict((c, 0.0) for c in comp)
    if labels is None:
        labels = ["X", "Y", "Z", "W"][:n]
    return add({"m_SGVersion": 0, "m_Type": "UnityEditor.ShaderGraph." + t,
                "m_ObjectId": oid(key), "m_Id": sid, "m_DisplayName": disp,
                "m_SlotType": stype, "m_Hidden": False, "m_ShaderOutputName": out,
                "m_StageCapability": stage, "m_Value": zero, "m_DefaultValue": dict(zero),
                "m_Labels": labels})


def slot_dynvec(key, sid, disp, out, stype, value=(0.0, 0.0, 0.0, 0.0)):
    v = {"x": value[0], "y": value[1], "z": value[2], "w": value[3]}
    return add({"m_SGVersion": 0, "m_Type": "UnityEditor.ShaderGraph.DynamicVectorMaterialSlot",
                "m_ObjectId": oid(key), "m_Id": sid, "m_DisplayName": disp,
                "m_SlotType": stype, "m_Hidden": False, "m_ShaderOutputName": out,
                "m_StageCapability": 3, "m_Value": v,
                "m_DefaultValue": {"x": 0.0, "y": 0.0, "z": 0.0, "w": 0.0}})


def _mat(fill):
    m = {}
    for r in range(4):
        for c in range(4):
            m["e%d%d" % (r, c)] = fill(r, c)
    return m


MAT_ZERO = _mat(lambda r, c: 0.0)
MAT_TWO = _mat(lambda r, c: 2.0)
MAT_IDENT = _mat(lambda r, c: 1.0 if r == c else 0.0)


def slot_dynval(key, sid, disp, out, stype, value=None):
    return add({"m_SGVersion": 0, "m_Type": "UnityEditor.ShaderGraph.DynamicValueMaterialSlot",
                "m_ObjectId": oid(key), "m_Id": sid, "m_DisplayName": disp,
                "m_SlotType": stype, "m_Hidden": False, "m_ShaderOutputName": out,
                "m_StageCapability": 3,
                "m_Value": dict(value if value is not None else MAT_ZERO),
                "m_DefaultValue": dict(MAT_IDENT)})


def slot_tex2d_out(key, disp):
    return add({"m_SGVersion": 0, "m_Type": "UnityEditor.ShaderGraph.Texture2DMaterialSlot",
                "m_ObjectId": oid(key), "m_Id": 0, "m_DisplayName": disp,
                "m_SlotType": 1, "m_Hidden": False, "m_ShaderOutputName": "Out",
                "m_StageCapability": 3, "m_BareResource": False})


def slot_tex2d_in(key):
    return add({"m_SGVersion": 0, "m_Type": "UnityEditor.ShaderGraph.Texture2DInputMaterialSlot",
                "m_ObjectId": oid(key), "m_Id": 1, "m_DisplayName": "Texture",
                "m_SlotType": 0, "m_Hidden": False, "m_ShaderOutputName": "Texture",
                "m_StageCapability": 3, "m_BareResource": False,
                "m_Texture": {"m_SerializedTexture": "{\"texture\":{\"instanceID\":0}}",
                              "m_Guid": ""},
                "m_DefaultType": 0})


def slot_uv(key):
    return add({"m_SGVersion": 0, "m_Type": "UnityEditor.ShaderGraph.UVMaterialSlot",
                "m_ObjectId": oid(key), "m_Id": 2, "m_DisplayName": "UV",
                "m_SlotType": 0, "m_Hidden": False, "m_ShaderOutputName": "UV",
                "m_StageCapability": 3, "m_Value": {"x": 0.0, "y": 0.0},
                "m_DefaultValue": {"x": 0.0, "y": 0.0}, "m_Labels": [], "m_Channel": 0})


def slot_sampler(key):
    return add({"m_SGVersion": 0, "m_Type": "UnityEditor.ShaderGraph.SamplerStateMaterialSlot",
                "m_ObjectId": oid(key), "m_Id": 3, "m_DisplayName": "Sampler",
                "m_SlotType": 0, "m_Hidden": False, "m_ShaderOutputName": "Sampler",
                "m_StageCapability": 3, "m_BareResource": False})


# ---------------------------------------------------------------- properties

PROP_IDS = []


def prop_texture(name, default_type, x_unused=0):
    """default_type: 0 White, 1 Black, 2 Grey, 3 NormalMap, 4 LinearGrey, 5 Red"""
    i = add({"m_SGVersion": 0,
             "m_Type": "UnityEditor.ShaderGraph.Internal.Texture2DShaderProperty",
             "m_ObjectId": oid("prop:" + name),
             "m_Guid": {"m_GuidSerialized": guid(name)},
             "m_Name": name,
             "m_DefaultRefNameVersion": 0,
             "m_RefNameGeneratedByDisplayName": "",
             "m_DefaultReferenceName": name,
             "m_OverrideReferenceName": name,
             "m_GeneratePropertyBlock": True,
             "m_UseCustomSlotLabel": False,
             "m_CustomSlotLabel": "",
             "m_DismissedVersion": 0,
             "m_Precision": 0,
             "overrideHLSLDeclaration": False,
             "hlslDeclarationOverride": 0,
             "m_Hidden": False,
             "m_Value": {"m_SerializedTexture": "{\"texture\":{\"instanceID\":0}}",
                         "m_Guid": ""},
             "isMainTexture": False,
             "useTilingAndOffset": False,
             "m_Modifiable": True,
             "m_DefaultType": default_type})
    PROP_IDS.append(i)
    return i


def prop_color(name, rgba, hdr):
    i = add({"m_SGVersion": 3,
             "m_Type": "UnityEditor.ShaderGraph.Internal.ColorShaderProperty",
             "m_ObjectId": oid("prop:" + name),
             "m_Guid": {"m_GuidSerialized": guid(name)},
             "m_Name": name,
             "m_DefaultRefNameVersion": 0,
             "m_RefNameGeneratedByDisplayName": "",
             "m_DefaultReferenceName": name,
             "m_OverrideReferenceName": name,
             "m_GeneratePropertyBlock": True,
             "m_UseCustomSlotLabel": False,
             "m_CustomSlotLabel": "",
             "m_DismissedVersion": 0,
             "m_Precision": 0,
             "overrideHLSLDeclaration": False,
             "hlslDeclarationOverride": 0,
             "m_Hidden": False,
             "m_Value": {"r": rgba[0], "g": rgba[1], "b": rgba[2], "a": rgba[3]},
             "isMainColor": False,
             "m_ColorMode": 1 if hdr else 0})
    PROP_IDS.append(i)
    return i


def prop_float(name, value, lo, hi):
    i = add({"m_SGVersion": 1,
             "m_Type": "UnityEditor.ShaderGraph.Internal.Vector1ShaderProperty",
             "m_ObjectId": oid("prop:" + name),
             "m_Guid": {"m_GuidSerialized": guid(name)},
             "m_Name": name,
             "m_DefaultRefNameVersion": 0,
             "m_RefNameGeneratedByDisplayName": "",
             "m_DefaultReferenceName": name,
             "m_OverrideReferenceName": name,
             "m_GeneratePropertyBlock": True,
             "m_UseCustomSlotLabel": False,
             "m_CustomSlotLabel": "",
             "m_DismissedVersion": 0,
             "m_Precision": 0,
             "overrideHLSLDeclaration": False,
             "hlslDeclarationOverride": 0,
             "m_Hidden": False,
             "m_Value": value,
             "m_FloatType": 1,          # Slider
             "m_RangeValues": {"x": lo, "y": hi}})
    PROP_IDS.append(i)
    return i


# ---------------------------------------------------------------- nodes


def property_node(key, prop_id, name, kind, x, y):
    """kind: 'tex' | 'color' | 'float'"""
    if kind == "tex":
        s = slot_tex2d_out(key + ".slot", name)
    elif kind == "color":
        s = slot_vec(key + ".slot", 0, name, "Out", 1, 4)
    else:
        s = slot_v1(key + ".slot", 0, name, "Out", 1)
    n = node_common(key, "Property", x, y, 134.0, 34.0, [s])
    n["m_SGVersion"] = 0
    n["m_Type"] = "UnityEditor.ShaderGraph.PropertyNode"
    n["m_Property"] = ref(prop_id)
    return add(n)


def sample_tex(key, x, y, texture_type=0):
    slots = [
        slot_vec(key + ".rgba", 0, "RGBA", "RGBA", 1, 4, stage=2, labels=[]),
        slot_v1(key + ".r", 4, "R", "R", 1, stage=2),
        slot_v1(key + ".g", 5, "G", "G", 1, stage=2),
        slot_v1(key + ".b", 6, "B", "B", 1, stage=2),
        slot_v1(key + ".a", 7, "A", "A", 1, stage=2),
        slot_tex2d_in(key + ".tex"),
        slot_uv(key + ".uv"),
        slot_sampler(key + ".sampler"),
    ]
    n = node_common(key, "Sample Texture 2D", x, y, 184.0, 253.0, slots)
    n["m_SGVersion"] = 0
    n["m_Type"] = "UnityEditor.ShaderGraph.SampleTexture2DNode"
    n["m_PreviewExpanded"] = False
    n["m_TextureType"] = texture_type
    n["m_NormalMapSpace"] = 0
    n["m_EnableGlobalMipBias"] = True
    n["m_MipSamplingMode"] = 0
    return add(n)


def colorspace(key, frm, to, x, y):
    slots = [
        slot_vec(key + ".in", 0, "In", "In", 0, 3),
        slot_vec(key + ".out", 1, "Out", "Out", 1, 3),
    ]
    n = node_common(key, "Colorspace Conversion", x, y, 208.0, 278.0, slots)
    n["m_SGVersion"] = 0
    n["m_Type"] = "UnityEditor.ShaderGraph.ColorspaceConversionNode"
    n["m_PreviewExpanded"] = False
    n["m_Conversion"] = {"from": frm, "to": to}
    return add(n)


def split_node(key, x, y):
    slots = [
        slot_dynvec(key + ".in", 0, "In", "In", 0),
        slot_v1(key + ".r", 1, "R", "R", 1),
        slot_v1(key + ".g", 2, "G", "G", 1),
        slot_v1(key + ".b", 3, "B", "B", 1),
        slot_v1(key + ".a", 4, "A", "A", 1),
    ]
    n = node_common(key, "Split", x, y, 120.0, 149.0, slots, ["separate"])
    n["m_SGVersion"] = 0
    n["m_Type"] = "UnityEditor.ShaderGraph.SplitNode"
    return add(n)


def combine_node(key, x, y):
    slots = [
        slot_v1(key + ".r", 0, "R", "R", 0, labels=["X"]),
        slot_v1(key + ".g", 1, "G", "G", 0, labels=["X"]),
        slot_v1(key + ".b", 2, "B", "B", 0, labels=["X"]),
        slot_v1(key + ".a", 3, "A", "A", 0, labels=["X"]),
        slot_vec(key + ".rgba", 4, "RGBA", "RGBA", 1, 4, labels=[]),
        slot_vec(key + ".rgb", 5, "RGB", "RGB", 1, 3),
        slot_vec(key + ".rg", 6, "RG", "RG", 1, 2),
    ]
    n = node_common(key, "Combine", x, y, 140.0, 166.0, slots, ["append"])
    n["m_SGVersion"] = 0
    n["m_Type"] = "UnityEditor.ShaderGraph.CombineNode"
    n["m_PreviewExpanded"] = False
    return add(n)


def multiply_node(key, x, y):
    slots = [
        slot_dynval(key + ".a", 0, "A", "A", 0, MAT_ZERO),
        slot_dynval(key + ".b", 1, "B", "B", 0, MAT_TWO),
        slot_dynval(key + ".out", 2, "Out", "Out", 1, MAT_ZERO),
    ]
    n = node_common(key, "Multiply", x, y, 130.0, 118.0, slots,
                    ["multiplication", "times", "x"])
    n["m_SGVersion"] = 0
    n["m_Type"] = "UnityEditor.ShaderGraph.MultiplyNode"
    n["m_PreviewExpanded"] = False
    return add(n)


def add_node(key, x, y):
    slots = [
        slot_dynvec(key + ".a", 0, "A", "A", 0),
        slot_dynvec(key + ".b", 1, "B", "B", 0),
        slot_dynvec(key + ".out", 2, "Out", "Out", 1),
    ]
    n = node_common(key, "Add", x, y, 130.0, 118.0, slots,
                    ["addition", "sum", "plus"])
    n["m_SGVersion"] = 0
    n["m_Type"] = "UnityEditor.ShaderGraph.AddNode"
    n["m_PreviewExpanded"] = False
    return add(n)


def maximum_node(key, x, y, b_value=(1e-4, 1e-4, 1e-4, 1e-4)):
    """max(A, B). Used as the divide-by-zero guard on the region-mask sum:
    an all-black mask texel would otherwise divide by 0 and produce NaN, which
    reads as a black or garbage pixel with nothing logged."""
    slots = [
        slot_dynvec(key + ".a", 0, "A", "A", 0),
        slot_dynvec(key + ".b", 1, "B", "B", 0, b_value),
        slot_dynvec(key + ".out", 2, "Out", "Out", 1),
    ]
    n = node_common(key, "Maximum", x, y, 130.0, 118.0, slots)
    n["m_SGVersion"] = 0
    n["m_Type"] = "UnityEditor.ShaderGraph.MaximumNode"
    n["m_PreviewExpanded"] = False
    return add(n)


def divide_node(key, x, y, a_value=(1.0, 1.0, 1.0, 1.0)):
    slots = [
        slot_dynvec(key + ".a", 0, "A", "A", 0, a_value),
        slot_dynvec(key + ".b", 1, "B", "B", 0, (2.0, 2.0, 2.0, 2.0)),
        slot_dynvec(key + ".out", 2, "Out", "Out", 1),
    ]
    n = node_common(key, "Divide", x, y, 130.0, 118.0, slots,
                    ["division", "divided by"])
    n["m_SGVersion"] = 0
    n["m_Type"] = "UnityEditor.ShaderGraph.DivideNode"
    n["m_PreviewExpanded"] = False
    return add(n)


def dot_node(key, x, y):
    """DotProductNode: A and B are DynamicDimensionVector, Out is a Vector1."""
    slots = [
        slot_dynvec(key + ".a", 0, "A", "A", 0),
        slot_dynvec(key + ".b", 1, "B", "B", 0, (0.0, 1.0, 0.0, 0.0)),
        slot_v1(key + ".out", 2, "Out", "Out", 1),
    ]
    n = node_common(key, "Dot Product", x, y, 130.0, 118.0, slots,
                    ["scalar product"])
    n["m_SGVersion"] = 0
    n["m_Type"] = "UnityEditor.ShaderGraph.DotProductNode"
    n["m_PreviewExpanded"] = False
    return add(n)


def unary_node(key, type_name, disp, x, y, synonyms=()):
    slots = [
        slot_dynvec(key + ".in", 0, "In", "In", 0),
        slot_dynvec(key + ".out", 1, "Out", "Out", 1),
    ]
    n = node_common(key, disp, x, y, 130.0, 100.0, slots, synonyms)
    n["m_SGVersion"] = 0
    n["m_Type"] = "UnityEditor.ShaderGraph." + type_name
    n["m_PreviewExpanded"] = False
    return add(n)


BLOCK_SLOT = {
    # descriptor -> (slot type, display name, output name, extra fields)
    "SurfaceDescription.BaseColor": ("ColorRGBMaterialSlot", "Base Color", "BaseColor"),
    "SurfaceDescription.NormalTS": ("NormalMaterialSlot", "Normal (Tangent Space)", "NormalTS"),
    "SurfaceDescription.Metallic": ("Vector1MaterialSlot", "Metallic", "Metallic"),
    "SurfaceDescription.Smoothness": ("Vector1MaterialSlot", "Smoothness", "Smoothness"),
    "SurfaceDescription.Occlusion": ("Vector1MaterialSlot", "Ambient Occlusion", "Occlusion"),
    "SurfaceDescription.Emission": ("ColorRGBMaterialSlot", "Emission", "Emission"),
    "SurfaceDescription.Alpha": ("Vector1MaterialSlot", "Alpha", "Alpha"),
    "SurfaceDescription.AlphaClipThreshold": ("Vector1MaterialSlot", "Alpha Clip Threshold",
                                              "AlphaClipThreshold"),
    "VertexDescription.Position": ("PositionMaterialSlot", "Position", "Position"),
    "VertexDescription.Normal": ("NormalMaterialSlot", "Normal", "Normal"),
    "VertexDescription.Tangent": ("TangentMaterialSlot", "Tangent", "Tangent"),
}


def block_node(descriptor, x, y, default_v1=0.0):
    st, disp, out = BLOCK_SLOT[descriptor]
    vertex = descriptor.startswith("VertexDescription")
    stage = 1 if vertex else 2
    key = "block:" + descriptor
    if st == "Vector1MaterialSlot":
        s = slot_v1(key + ".slot", 0, disp, out, 0, value=default_v1,
                    labels=["X"], stage=stage)
    elif st == "ColorRGBMaterialSlot":
        s = add({"m_SGVersion": 0,
                 "m_Type": "UnityEditor.ShaderGraph.ColorRGBMaterialSlot",
                 "m_ObjectId": oid(key + ".slot"), "m_Id": 0, "m_DisplayName": disp,
                 "m_SlotType": 0, "m_Hidden": False, "m_ShaderOutputName": out,
                 "m_StageCapability": stage,
                 "m_Value": {"x": 0.0, "y": 0.0, "z": 0.0},
                 "m_DefaultValue": {"x": 0.0, "y": 0.0, "z": 0.0},
                 "m_Labels": ["X", "Y", "Z"],
                 "m_ColorMode": 0,
                 "m_DefaultColor": {"r": 0.5, "g": 0.5, "b": 0.5, "a": 1.0}})
    else:
        space = {"PositionMaterialSlot": 0, "NormalMaterialSlot": 0,
                 "TangentMaterialSlot": 0}[st]
        if descriptor == "SurfaceDescription.NormalTS":
            space = 3   # Tangent space
        s = add({"m_SGVersion": 0,
                 "m_Type": "UnityEditor.ShaderGraph." + st,
                 "m_ObjectId": oid(key + ".slot"), "m_Id": 0, "m_DisplayName": disp,
                 "m_SlotType": 0, "m_Hidden": False, "m_ShaderOutputName": out,
                 "m_StageCapability": stage,
                 "m_Value": {"x": 0.0, "y": 0.0, "z": 0.0},
                 "m_DefaultValue": {"x": 0.0, "y": 0.0, "z": 0.0},
                 "m_Labels": ["X", "Y", "Z"] if st != "NormalMaterialSlot" else [],
                 "m_Space": space})
    n = node_common(key, descriptor, x, y, 200.0, 40.66650390625, [s])
    n["m_SGVersion"] = 0
    n["m_Type"] = "UnityEditor.ShaderGraph.BlockNode"
    n["m_PreviewMode"] = 0
    n["m_SerializedDescriptor"] = descriptor
    return add(n)


# ================================================================ build

EDGES = []


def edge(onode, oslot, inode, islot):
    EDGES.append({"m_OutputSlot": {"m_Node": ref(onode), "m_SlotId": oslot},
                  "m_InputSlot": {"m_Node": ref(inode), "m_SlotId": islot}})


def build():
    # ---- properties (order = inspector order)
    p_basemap = prop_texture("_BaseMap", 0)
    p_basecol = prop_color("_BaseColor", (1.0, 1.0, 1.0, 1.0), hdr=False)
    p_hue = prop_float("_HueShift", 0.0, -1.0, 1.0)
    p_sat = prop_float("_SaturationMul", 1.0, 0.0, 4.0)
    p_val = prop_float("_ValueMul", 1.0, 0.0, 2.0)
    # Regions 1 and 2 (§15.11.1). Same ranges and defaults as region 0, so a
    # material that never touches them behaves exactly as it did before the
    # mask existed — the identity is (hue 0, sat x1, value x1).
    p_hue2 = prop_float("_HueShift2", 0.0, -1.0, 1.0)
    p_sat2 = prop_float("_SaturationMul2", 1.0, 0.0, 4.0)
    p_val2 = prop_float("_ValueMul2", 1.0, 0.0, 2.0)
    p_hue3 = prop_float("_HueShift3", 0.0, -1.0, 1.0)
    p_sat3 = prop_float("_SaturationMul3", 1.0, 0.0, 4.0)
    p_val3 = prop_float("_ValueMul3", 1.0, 0.0, 2.0)
    # DefaultType.Red (5), NOT White. Unbound this samples (1,0,0,1), which
    # normalises to weights (1,0,0) = 100% region 0 = exactly the single-set
    # behaviour every existing material already renders with. A White default
    # would sample (1,1,1,1) -> weights (1/3,1/3,1/3) and silently AVERAGE all
    # three parameter sets on every material in the project, with nothing
    # logged. RecolorShaderCheck asserts both the ShaderLab default string and
    # the rendered result of leaving it unbound.
    p_region = prop_texture("_RegionMask", 5)
    p_bump = prop_texture("_BumpMap", 3)
    p_mgmap = prop_texture("_MetallicGlossMap", 0)
    # URP/Lit's own name for the cut-out threshold. Using the same name is what
    # lets a vendor material keep its tuned cutoff (0.222 on Miyoko's hair) when
    # it moves onto this shader -- a differently named property would silently
    # reset every one of them to a default.
    p_cutoff = prop_float("_Cutoff", 0.5, 0.0, 1.0)
    p_metal = prop_float("_Metallic", 0.0, 0.0, 1.0)
    p_smooth = prop_float("_Smoothness", 0.5, 0.0, 1.0)
    p_occ = prop_texture("_OcclusionMap", 0)
    p_emap = prop_texture("_EmissionMap", 1)
    p_ecol = prop_color("_EmissionColor", (0.0, 0.0, 0.0, 1.0), hdr=True)

    # ---- property nodes
    n_basemap = property_node("pn.BaseMap", p_basemap, "_BaseMap", "tex", -2000, -900)
    n_basecol = property_node("pn.BaseColor", p_basecol, "_BaseColor", "color", -600, -540)
    n_hue = property_node("pn.HueShift", p_hue, "_HueShift", "float", -2600, -1900)
    n_hue2 = property_node("pn.HueShift2", p_hue2, "_HueShift2", "float", -2600, -1840)
    n_hue3 = property_node("pn.HueShift3", p_hue3, "_HueShift3", "float", -2600, -1780)
    n_sat = property_node("pn.SaturationMul", p_sat, "_SaturationMul", "float", -2600, -1700)
    n_sat2 = property_node("pn.SaturationMul2", p_sat2, "_SaturationMul2", "float", -2600, -1640)
    n_sat3 = property_node("pn.SaturationMul3", p_sat3, "_SaturationMul3", "float", -2600, -1580)
    n_val = property_node("pn.ValueMul", p_val, "_ValueMul", "float", -2600, -1500)
    n_val2 = property_node("pn.ValueMul2", p_val2, "_ValueMul2", "float", -2600, -1440)
    n_val3 = property_node("pn.ValueMul3", p_val3, "_ValueMul3", "float", -2600, -1380)
    n_region = property_node("pn.RegionMask", p_region, "_RegionMask", "tex", -2900, -2200)
    n_bump = property_node("pn.BumpMap", p_bump, "_BumpMap", "tex", -2000, -520)
    n_mgmap = property_node("pn.MetallicGlossMap", p_mgmap, "_MetallicGlossMap", "tex", -2000, -300)
    n_cutoff = property_node("pn.Cutoff", p_cutoff, "_Cutoff", "float", -1450, -400)
    n_metal = property_node("pn.Metallic", p_metal, "_Metallic", "float", -1450, -240)
    n_smooth = property_node("pn.Smoothness", p_smooth, "_Smoothness", "float", -1450, -150)
    n_occ = property_node("pn.OcclusionMap", p_occ, "_OcclusionMap", "tex", -2000, -60)
    n_emap = property_node("pn.EmissionMap", p_emap, "_EmissionMap", "tex", -2000, 160)
    n_ecol = property_node("pn.EmissionColor", p_ecol, "_EmissionColor", "color", -1450, 250)

    # ---- samplers
    s_base = sample_tex("sample.Base", -1750, -900, 0)
    s_bump = sample_tex("sample.Bump", -1750, -520, 1)   # TextureType.Normal
    s_mg = sample_tex("sample.MetallicGloss", -1750, -300, 0)
    s_occ = sample_tex("sample.Occlusion", -1750, -60, 0)
    s_emis = sample_tex("sample.Emission", -1750, 160, 0)
    # TextureType.Default (0), same as every other non-normal sampler here. The
    # node has no linear/data mode: whether the GPU sRGB-decodes on sample is a
    # property of the TEXTURE ASSET (its importer's sRGB flag), not of the
    # graph. Import any authored _RegionMask with sRGB OFF. Unbound, the
    # ShaderLab "red" default is (1,0,0,1) whose channels are 0 and 1 — both
    # fixed points of the sRGB curve — so the default is decode-invariant.
    s_region = sample_tex("sample.RegionMask", -2700, -2200, 0)

    # ---- region weights: normalise the mask, then blend the PARAMETERS
    #
    # m = SAMPLE(_RegionMask, uv).rgb
    # w = m / max(m.r + m.g + m.b, 1e-4)
    #
    # ONE HSV round trip on parameters blended by w, not three round trips
    # blended by w. Cheaper, and identical wherever the mask is one-hot, which
    # it is everywhere except antialiased region borders — and a smooth
    # parameter blend is what you want there anyway.
    comb_m = combine_node("comb.RegionRGB", -2460, -2200)
    sum_rg = add_node("math.RegionSumRG", -2460, -2000)
    sum_rgb = add_node("math.RegionSumRGB", -2300, -2000)
    denom = maximum_node("math.RegionDenom", -2140, -2000)
    weights = divide_node("math.RegionWeights", -2000, -2200)

    comb_hue = combine_node("comb.HueSet", -2380, -1900)
    dot_hue = dot_node("math.BlendHue", -2180, -1900)
    comb_sat = combine_node("comb.SatSet", -2380, -1700)
    dot_sat = dot_node("math.BlendSat", -2180, -1700)
    comb_val = combine_node("comb.ValSet", -2380, -1500)
    dot_val = dot_node("math.BlendVal", -2180, -1500)

    # ---- the recolor chain
    rgb2hsv = colorspace("cs.RGBtoHSV", 0, 2, -1470, -960)
    spl = split_node("split.HSV", -1200, -960)
    n_add = add_node("math.AddHue", -960, -960)
    n_frac = unary_node("math.Frac", "FractionNode", "Fraction", -780, -960, ["remainder"])
    n_mulsat = multiply_node("math.MulSat", -960, -820)
    n_satur = unary_node("math.SatClamp", "SaturateNode", "Saturate", -780, -820, ["clamp"])
    n_mulval = multiply_node("math.MulVal", -960, -680)
    comb = combine_node("comb.HSV", -580, -900)
    hsv2rgb = colorspace("cs.HSVtoRGB", 2, 0, -380, -900)
    mul_base = multiply_node("math.MulBaseColor", -120, -820)

    mul_metal = multiply_node("math.MulMetallic", -1200, -300)
    mul_smooth = multiply_node("math.MulSmoothness", -1200, -160)
    mul_emis = multiply_node("math.MulEmission", -1200, 160)

    # ---- blocks
    b_pos = block_node("VertexDescription.Position", 300, -1250)
    b_nrm = block_node("VertexDescription.Normal", 300, -1180)
    b_tan = block_node("VertexDescription.Tangent", 300, -1110)

    b_basecolor = block_node("SurfaceDescription.BaseColor", 300, -900)
    b_normalts = block_node("SurfaceDescription.NormalTS", 300, -830)
    b_metallic = block_node("SurfaceDescription.Metallic", 300, -760)
    b_smoothness = block_node("SurfaceDescription.Smoothness", 300, -690, 0.5)
    b_occlusion = block_node("SurfaceDescription.Occlusion", 300, -620, 1.0)
    b_emission = block_node("SurfaceDescription.Emission", 300, -550)
    b_alpha = block_node("SurfaceDescription.Alpha", 300, -480, 1.0)
    b_cutoff = block_node("SurfaceDescription.AlphaClipThreshold", 300, -410, 0.5)

    # ---- edges
    edge(n_basemap, 0, s_base, 1)
    edge(s_base, 0, rgb2hsv, 0)                 # RGBA -> In (.xyz)
    edge(rgb2hsv, 1, spl, 0)

    # region weights
    edge(n_region, 0, s_region, 1)
    edge(s_region, 4, comb_m, 0)                # R -> R
    edge(s_region, 5, comb_m, 1)                # G -> G
    edge(s_region, 6, comb_m, 2)                # B -> B
    edge(s_region, 4, sum_rg, 0)
    edge(s_region, 5, sum_rg, 1)
    edge(sum_rg, 2, sum_rgb, 0)
    edge(s_region, 6, sum_rgb, 1)
    edge(sum_rgb, 2, denom, 0)                  # max(sum, 1e-4)
    edge(comb_m, 5, weights, 0)                 # A = m.rgb (Vector3)
    edge(denom, 2, weights, 1)                  # B = the guarded sum (scalar)

    # per-channel parameter blend: dot(w, float3(p0, p1, p2))
    edge(n_hue, 0, comb_hue, 0)
    edge(n_hue2, 0, comb_hue, 1)
    edge(n_hue3, 0, comb_hue, 2)
    edge(weights, 2, dot_hue, 0)
    edge(comb_hue, 5, dot_hue, 1)

    edge(n_sat, 0, comb_sat, 0)
    edge(n_sat2, 0, comb_sat, 1)
    edge(n_sat3, 0, comb_sat, 2)
    edge(weights, 2, dot_sat, 0)
    edge(comb_sat, 5, dot_sat, 1)

    edge(n_val, 0, comb_val, 0)
    edge(n_val2, 0, comb_val, 1)
    edge(n_val3, 0, comb_val, 2)
    edge(weights, 2, dot_val, 0)
    edge(comb_val, 5, dot_val, 1)

    # The blended scalars enter the EXISTING chain at exactly the points the
    # single properties used to.
    edge(spl, 1, n_add, 0)                      # H
    edge(dot_hue, 2, n_add, 1)
    edge(n_add, 2, n_frac, 0)

    edge(spl, 2, n_mulsat, 0)                   # S
    edge(dot_sat, 2, n_mulsat, 1)
    edge(n_mulsat, 2, n_satur, 0)

    edge(spl, 3, n_mulval, 0)                   # V
    edge(dot_val, 2, n_mulval, 1)

    edge(n_frac, 1, comb, 0)
    edge(n_satur, 1, comb, 1)
    edge(n_mulval, 2, comb, 2)

    edge(comb, 5, hsv2rgb, 0)                   # RGB (Vector3) -> In
    edge(hsv2rgb, 1, mul_base, 0)               # <-- recolour FIRST
    edge(n_basecol, 0, mul_base, 1)             # <-- _BaseColor multiplies the RESULT
    edge(mul_base, 2, b_basecolor, 0)

    # The base map's alpha already reached Alpha; without a threshold block and
    # m_AlphaClip below it was computed and thrown away, which is why hair had to
    # be kept off this shader entirely.
    edge(s_base, 7, b_alpha, 0)
    edge(n_cutoff, 0, b_cutoff, 0)

    edge(n_bump, 0, s_bump, 1)
    edge(s_bump, 0, b_normalts, 0)

    edge(n_mgmap, 0, s_mg, 1)
    edge(s_mg, 4, mul_metal, 0)
    edge(n_metal, 0, mul_metal, 1)
    edge(mul_metal, 2, b_metallic, 0)
    edge(s_mg, 7, mul_smooth, 0)
    edge(n_smooth, 0, mul_smooth, 1)
    edge(mul_smooth, 2, b_smoothness, 0)

    edge(n_occ, 0, s_occ, 1)
    edge(s_occ, 5, b_occlusion, 0)

    edge(n_emap, 0, s_emis, 1)
    edge(s_emis, 0, mul_emis, 0)
    edge(n_ecol, 0, mul_emis, 1)
    edge(mul_emis, 2, b_emission, 0)

    # ---- sticky note (the graph's own description)
    note = add({"m_SGVersion": 0,
                "m_Type": "UnityEditor.ShaderGraph.StickyNoteData",
                "m_ObjectId": oid("note.description"),
                "m_Title": "Recolor (REBUILD_SPEC.md 15.11.1)",
                "m_Content": (
                    "Theme recolour for Aftermarket Apocalypse.\n\n"
                    "m    = SAMPLE(_RegionMask, uv).rgb\n"
                    "w    = m / max(m.r + m.g + m.b, 1e-4)\n"
                    "hue  = dot(w, float3(_HueShift,      _HueShift2,      _HueShift3))\n"
                    "sat  = dot(w, float3(_SaturationMul, _SaturationMul2, _SaturationMul3))\n"
                    "val  = dot(w, float3(_ValueMul,      _ValueMul2,      _ValueMul3))\n\n"
                    "albedo = SAMPLE(_BaseMap, uv)\n"
                    "hsv    = RGBtoHSV(albedo.rgb)\n"
                    "hsv.x  = frac(hsv.x + hue)\n"
                    "hsv.y  = saturate(hsv.y * sat)\n"
                    "hsv.z  = hsv.z * val\n"
                    "BaseColor = HSVtoRGB(hsv) * _BaseColor.rgb\n\n"
                    "LOAD-BEARING ORDER: the _BaseColor multiply is the LAST step, "
                    "AFTER the HSV round trip. EnemyAI.TintRenderers writes _BaseColor "
                    "into a MaterialPropertyBlock to signal sprinters "
                    "(RoundRules.SprinterTint = 0.62). Spec 15.11.3: the theme sets the "
                    "base hue and the type signal applies ON TOP of it. Wiring _BaseColor "
                    "into the INPUT of the RGB->HSV node would hue-shift the sprinter "
                    "tint and destroy the readability signal.\n\n"
                    "LOAD-BEARING DEFAULT: _RegionMask defaults to \"red\", NOT \"white\". "
                    "Unbound it samples (1,0,0,1), which normalises to weights (1,0,0) = "
                    "100% region 0 = exactly the single-parameter-set behaviour every "
                    "material had before regions existed. A white default would give "
                    "(1/3,1/3,1/3) and silently AVERAGE all three sets on every material "
                    "in the project, with nothing logged.\n\n"
                    "The PARAMETERS are blended and the HSV round trip runs ONCE. Doing "
                    "three round trips and blending the results costs 3x and differs only "
                    "on antialiased region borders, where blending parameters is the "
                    "better answer anyway.\n\n"
                    "_RegionMask is a DATA map: import it with sRGB OFF. The graph cannot "
                    "enforce that - the Sample Texture 2D node has no linear mode, and "
                    "whether the GPU decodes on sample is a property of the texture asset. "
                    "The \"red\" default is decode-invariant (0 and 1 are fixed points of "
                    "the sRGB curve), so only an AUTHORED mask can get this wrong.\n\n"
                    "Property names deliberately match URP/Lit so an existing material "
                    "can be switched to this shader without losing its maps."),
                "m_TextSize": 0,
                "m_Theme": 0,
                "m_Position": {"serializedVersion": "2", "x": -2600.0, "y": -1250.0,
                               "width": 520.0, "height": 620.0},
                "m_Group": {"m_Id": ""}})

    # ---- targets
    subtarget = add({"m_SGVersion": 2,
                     "m_Type": "UnityEditor.Rendering.Universal.ShaderGraph.UniversalLitSubTarget",
                     "m_ObjectId": oid("subtarget.lit"),
                     "m_WorkflowMode": 1,          # Metallic
                     "m_NormalDropOffSpace": 0,    # Tangent
                     "m_ClearCoat": False,
                     "m_BlendModePreserveSpecular": False})
    target = add({"m_SGVersion": 1,
                  "m_Type": "UnityEditor.Rendering.Universal.ShaderGraph.UniversalTarget",
                  "m_ObjectId": oid("target.universal"),
                  "m_Datas": [],
                  "m_ActiveSubTarget": ref(subtarget),
                  "m_AllowMaterialOverride": False,
                  "m_SurfaceType": 0,      # Opaque
                  "m_ZTestMode": 4,
                  "m_ZWriteControl": 0,
                  "m_AlphaMode": 0,
                  "m_RenderFace": 0,
                  # ON. With this false the Alpha output is computed and
                  # discarded, so every cut-out material rendered as a solid
                  # slab -- hair fused into one lump. That forced hair, lashes
                  # and eyes off this shader and out of the theme system.
                  "m_AlphaClip": True,
                  "m_CastShadows": True,
                  "m_ReceiveShadows": True,
                  "m_AdditionalMotionVectorMode": 0,
                  "m_AlembicMotionVectors": False,
                  "m_SupportsLODCrossFade": False,
                  "m_CustomEditorGUI": "",
                  "m_SupportVFX": False})

    category = add({"m_SGVersion": 0,
                    "m_Type": "UnityEditor.ShaderGraph.CategoryData",
                    "m_ObjectId": oid("category.default"),
                    "m_Name": "",
                    "m_ChildObjectList": [ref(i) for i in PROP_IDS]})

    node_ids = [n_basemap, n_basecol, n_hue, n_sat, n_val, n_bump, n_mgmap,
                n_metal, n_smooth, n_occ, n_emap, n_ecol,
                n_hue2, n_sat2, n_val2, n_hue3, n_sat3, n_val3, n_region,
                s_base, s_bump, s_mg, s_occ, s_emis, s_region,
                comb_m, sum_rg, sum_rgb, denom, weights,
                comb_hue, dot_hue, comb_sat, dot_sat, comb_val, dot_val,
                rgb2hsv, spl, n_add, n_frac, n_mulsat, n_satur, n_mulval,
                comb, hsv2rgb, mul_base, mul_metal, mul_smooth, mul_emis,
                b_pos, b_nrm, b_tan,
                b_basecolor, b_normalts, b_metallic, b_smoothness,
                b_occlusion, b_emission, b_alpha, b_cutoff, n_cutoff]

    graph = {
        "m_SGVersion": 3,
        "m_Type": "UnityEditor.ShaderGraph.GraphData",
        "m_ObjectId": oid("graphdata"),
        "m_Properties": [ref(i) for i in PROP_IDS],
        "m_Keywords": [],
        "m_Dropdowns": [],
        "m_CategoryData": [ref(category)],
        "m_Nodes": [ref(i) for i in node_ids],
        "m_GroupDatas": [],
        "m_StickyNoteDatas": [ref(note)],
        "m_Edges": EDGES,
        "m_VertexContext": {"m_Position": {"x": 300.0, "y": -1300.0},
                            "m_Blocks": [ref(b_pos), ref(b_nrm), ref(b_tan)]},
        "m_FragmentContext": {"m_Position": {"x": 300.0, "y": -950.0},
                              "m_Blocks": [ref(b_basecolor), ref(b_normalts),
                                           ref(b_metallic), ref(b_smoothness),
                                           ref(b_occlusion), ref(b_emission),
                                           ref(b_alpha), ref(b_cutoff)]},
        "m_PreviewData": {"serializedMesh": {"m_SerializedMesh": "{\"mesh\":{\"instanceID\":0}}",
                                             "m_Guid": ""},
                          "preventRotation": False},
        "m_Path": "Aftermarket",
        "m_GraphPrecision": 0,
        "m_PreviewMode": 2,
        "m_OutputNode": {"m_Id": ""},
        "m_ActiveTargets": [ref(target)],
    }

    # GraphData must be object 0.
    ordered = [graph] + _objs
    # sanity: unique ids, all refs resolve
    ids = [o["m_ObjectId"] for o in ordered]
    assert len(ids) == len(set(ids)), "duplicate object ids"
    known = set(ids)

    def walk(x):
        if isinstance(x, dict):
            if set(x.keys()) == {"m_Id"} and isinstance(x["m_Id"], str) and x["m_Id"]:
                assert x["m_Id"] in known, "dangling ref " + x["m_Id"]
            for v in x.values():
                walk(v)
        elif isinstance(x, list):
            for v in x:
                walk(v)

    walk(ordered)

    chunks = [json.dumps(o, indent=4, separators=(",", ": ")) for o in ordered]
    text = "\n\n".join(chunks) + "\n"
    d = os.path.dirname(OUT)
    if not os.path.isdir(d):
        os.makedirs(d)
    with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("wrote %s (%d objects, %d edges, %d bytes)"
          % (OUT, len(ordered), len(EDGES), len(text.encode("utf-8"))))


if __name__ == "__main__":
    build()
