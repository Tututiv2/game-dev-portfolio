using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Turns the modules <c>Tools/map/kit_cut.py</c> cut out of a downloaded .blend into
/// kit prefabs on this project's grid.
///
/// <para><b>Why this is not part of <see cref="MapKitBuilder"/>.</b> That tool
/// refuses to run once <c>AshfallDepot.unity</c> exists, and the refusal is right:
/// <c>SaveAsPrefabAsset</c> mints fresh child fileIDs, and the dressed scene stores
/// per-instance overrides against the old ones, which are then dropped silently on
/// the next save. This builder only ever creates NEW assets, so it can never orphan
/// an override on one of the nine original pieces.</para>
///
/// <para><b>The axis mapping is derived, not assumed.</b> The collider boxes are
/// authored in Blender's Z-up space and the FBX exporter converts axes on the way
/// out. Rather than hard-code a guess, every module records its own bounds in
/// Blender space, and <see cref="DeriveMapping"/> finds the signed permutation that
/// reproduces the imported mesh's bounds. If none does, the build refuses. A wrong
/// guess here puts every collider in the map somewhere other than the wall it
/// belongs to, and renders perfectly.</para>
/// </summary>
public static class KitModuleBuilder
{
    public const string ModuleFolder = "Assets/_Project/Art/Kit/Modules";
    public const string MaterialFolder = ModuleFolder + "/Materials";

    /// <summary>Half a cell. Nothing in a module may reach past its own cell.</summary>
    static float HalfCell => MapKitBuilder.Grid * 0.5f;

    // ---- manifest ---------------------------------------------------------
    // Flat lists, not dictionaries: JsonUtility cannot deserialise a dictionary,
    // and finding that out after the fact is a silent field of nulls.

    [Serializable]
    public class BoxRec
    {
        public float[] center;
        public float[] size;
    }

    [Serializable]
    public class MatRec
    {
        public string name;
        public string baseMap;
        public string normalMap;
        public string metallicGlossMap;
        public string normalSource;
        public float[] baseColor;
        public float metallicValue;
        public float smoothnessValue = 0.5f;
    }

    [Serializable]
    public class ModRec
    {
        public string name;
        public int[] cell;
        public int tris;
        public float[] bounds_min_blender;
        public float[] bounds_max_blender;
        public int collider_boxes;
        public List<BoxRec> boxes = new List<BoxRec>();
        public bool stair_wedge;
        public int colliders_intruding_into_walkable_space;
        public List<string> materials = new List<string>();
    }

    [Serializable]
    public class Manifest
    {
        public string source;
        public float cell_m;
        public float storey_m;
        public float min_collider_thickness_m;
        public float walkable_plane_y;
        public List<MatRec> material_maps = new List<MatRec>();
        public List<ModRec> modules = new List<ModRec>();
    }

    public static string[] ManifestPaths()
    {
        return Directory.Exists(ModuleFolder)
            ? Directory.GetFiles(ModuleFolder, "*_modules.json").OrderBy(p => p).ToArray()
            : new string[0];
    }

    public static Manifest Load(string path)
    {
        return JsonUtility.FromJson<Manifest>(File.ReadAllText(path));
    }

    // ---- build ------------------------------------------------------------

    [MenuItem("Tools/Aftermarket/Build Kit Modules")]
    public static void Build()
    {
        var manifests = ManifestPaths();
        if (manifests.Length == 0)
        {
            Debug.LogError($"[KitModuleBuilder] no *_modules.json under {ModuleFolder}. " +
                           "Run Tools/map/kit_cut.py first.");
            return;
        }

        Directory.CreateDirectory(MaterialFolder);
        int built = 0, failed = 0;
        var problems = new List<string>();

        foreach (var mpath in manifests)
        {
            var man = Load(mpath);
            Debug.Log($"[KitModuleBuilder] {Path.GetFileName(mpath)}: {man.modules.Count} modules, " +
                      $"{man.material_maps.Count} materials, cell {man.cell_m} m");

            if (!DeriveManifestMapping(man, out var map, out var why))
            {
                Debug.LogError($"[KitModuleBuilder] {Path.GetFileName(mpath)}: {why}");
                failed += man.modules.Count;
                continue;
            }
            Debug.Log($"[KitModuleBuilder] axis mapping {why}");

            var mats = BuildMaterials(man);
            foreach (var mod in man.modules)
            {
                if (BuildModule(man, mod, mats, map, problems)) built++;
                else failed++;
            }
        }

        AssetDatabase.SaveAssets();
        AssetDatabase.Refresh();

        foreach (var p in problems) Debug.LogError("[KitModuleBuilder] " + p);
        Debug.Log($"[KitModuleBuilder] built {built} module prefab(s), {failed} refused, " +
                  $"{problems.Count} problem(s).");
    }

    // ---- materials --------------------------------------------------------

    static string Safe(string n)
    {
        return new string(n.Select(c => char.IsLetterOrDigit(c) ? c : '_').ToArray());
    }

    static Texture2D LoadTexture(string folder, string file, TextureKind kind)
    {
        if (string.IsNullOrEmpty(file)) return null;
        string path = folder + "/Textures/" + file;
        var tex = AssetDatabase.LoadAssetAtPath<Texture2D>(path);
        if (tex == null)
        {
            Debug.LogWarning($"[KitModuleBuilder] missing texture {path}");
            return null;
        }

        // The import settings ARE the content as far as the shader is concerned.
        // A normal map imported as a colour texture is not decoded; a metallic/
        // smoothness map imported as sRGB has its values gamma-shifted. Neither errors.
        var imp = AssetImporter.GetAtPath(path) as TextureImporter;
        if (imp != null)
        {
            bool dirty = false;
            if (kind == TextureKind.Normal)
            {
                if (imp.textureType != TextureImporterType.NormalMap)
                {
                    imp.textureType = TextureImporterType.NormalMap;
                    dirty = true;
                }
            }
            else
            {
                if (imp.textureType != TextureImporterType.Default)
                {
                    imp.textureType = TextureImporterType.Default;
                    dirty = true;
                }
                bool wantSrgb = kind == TextureKind.Albedo;
                if (imp.sRGBTexture != wantSrgb)
                {
                    imp.sRGBTexture = wantSrgb;
                    dirty = true;
                }
                if (kind == TextureKind.MetallicGloss)
                {
                    // smoothness lives in the ALPHA channel, so the alpha must survive
                    if (imp.alphaSource != TextureImporterAlphaSource.FromInput)
                    {
                        imp.alphaSource = TextureImporterAlphaSource.FromInput;
                        dirty = true;
                    }
                    if (imp.alphaIsTransparency)
                    {
                        imp.alphaIsTransparency = false;
                        dirty = true;
                    }
                }
            }
            if (dirty)
            {
                imp.SaveAndReimport();
                tex = AssetDatabase.LoadAssetAtPath<Texture2D>(path);
            }
        }
        return tex;
    }

    enum TextureKind { Albedo, Normal, MetallicGloss }

    static Dictionary<string, Material> BuildMaterials(Manifest man)
    {
        var shader = Shader.Find("Universal Render Pipeline/Lit");
        if (shader == null)
        {
            Debug.LogError("[KitModuleBuilder] URP/Lit shader not found.");
            return new Dictionary<string, Material>();
        }

        var byName = new Dictionary<string, Material>();
        foreach (var rec in man.material_maps)
        {
            if (rec == null || string.IsNullOrEmpty(rec.name)) continue;
            string path = $"{MaterialFolder}/M_{Safe(rec.name)}.mat";
            var mat = AssetDatabase.LoadAssetAtPath<Material>(path);
            if (mat == null)
            {
                mat = new Material(shader);
                AssetDatabase.CreateAsset(mat, path);
            }
            mat.shader = shader;

            var albedo = LoadTexture(ModuleFolder, rec.baseMap, TextureKind.Albedo);
            var normal = LoadTexture(ModuleFolder, rec.normalMap, TextureKind.Normal);
            var mg = LoadTexture(ModuleFolder, rec.metallicGlossMap, TextureKind.MetallicGloss);

            mat.SetFloat("_WorkflowMode", 1f);          // metallic, not specular

            // Render BOTH faces. blend_probe reported several of this asset's walls
            // as zero-thickness single-sided planes (2 to 11 triangles, 0.00 m on one
            // axis), and a single-sided plane seen from behind renders as nothing —
            // so standing in the corridor left holes straight through the level with
            // no missing geometry and no error. The collision proxy was never fooled;
            // only the eye was.
            mat.SetFloat("_Cull", 0f);
            mat.doubleSidedGI = true;
            if (albedo != null) mat.SetTexture("_BaseMap", albedo);
            if (rec.baseColor != null && rec.baseColor.Length >= 3 && albedo == null)
                mat.SetColor("_BaseColor", new Color(rec.baseColor[0], rec.baseColor[1],
                                                     rec.baseColor[2], 1f));
            else
                mat.SetColor("_BaseColor", Color.white);

            if (normal != null)
            {
                mat.SetTexture("_BumpMap", normal);
                mat.SetFloat("_BumpScale", 1f);
                mat.EnableKeyword("_NORMALMAP");
            }
            else mat.DisableKeyword("_NORMALMAP");

            if (mg != null)
            {
                mat.SetTexture("_MetallicGlossMap", mg);
                // the map carries the real values; these two are multipliers over it
                mat.SetFloat("_Metallic", 1f);
                mat.SetFloat("_Smoothness", 1f);
                mat.SetFloat("_SmoothnessTextureChannel", 0f);   // 0 = metallic map ALPHA
                mat.EnableKeyword("_METALLICSPECGLOSSMAP");
            }
            else
            {
                mat.DisableKeyword("_METALLICSPECGLOSSMAP");
                mat.SetFloat("_Metallic", rec.metallicValue);
                mat.SetFloat("_Smoothness", rec.smoothnessValue);
            }

            EditorUtility.SetDirty(mat);
            byName[rec.name] = mat;
        }
        return byName;
    }

    // ---- axis mapping -----------------------------------------------------

    /// <summary>A signed permutation taking Blender local space to Unity local space.</summary>
    public struct Mapping
    {
        public int ix, iy, iz;        // which Blender axis feeds each Unity axis
        public float sx, sy, sz;      // and with what sign

        public Vector3 Point(float[] b)
        {
            return new Vector3(b[ix] * sx, b[iy] * sy, b[iz] * sz);
        }

        public Vector3 Extent(float[] s)
        {
            return new Vector3(Mathf.Abs(s[ix]), Mathf.Abs(s[iy]), Mathf.Abs(s[iz]));
        }

        public override string ToString()
        {
            string[] n = { "x", "y", "z" };
            return $"(unity.x={Sign(sx)}b.{n[ix]}, unity.y={Sign(sy)}b.{n[iy]}, unity.z={Sign(sz)}b.{n[iz]})";
        }

        static string Sign(float s) { return s < 0 ? "-" : "+"; }

        public bool Same(Mapping o)
        {
            return ix == o.ix && iy == o.iy && iz == o.iz &&
                   sx == o.sx && sy == o.sy && sz == o.sz;
        }
    }

    /// <summary>
    /// Every signed permutation that reproduces this module bounds. Usually more
    /// than one: a module whose local bounds are symmetric about its origin, say
    /// -2..2 on X, matches +X and -X equally well, and an axis-aligned box carries
    /// no information that could tell them apart.
    /// </summary>
    public static List<Mapping> CandidateMappings(float[] bMin, float[] bMax,
                                                  Bounds unityBounds, float tol = 0.005f)
    {
        var found = new List<Mapping>();
        if (bMin == null || bMax == null || bMin.Length != 3 || bMax.Length != 3) return found;

        int[][] perms = { new[] { 0, 1, 2 }, new[] { 0, 2, 1 }, new[] { 1, 0, 2 },
                          new[] { 1, 2, 0 }, new[] { 2, 0, 1 }, new[] { 2, 1, 0 } };
        float[] signs = { 1f, -1f };

        foreach (var p in perms)
            foreach (var a in signs)
                foreach (var b in signs)
                    foreach (var c in signs)
                    {
                        var m = new Mapping { ix = p[0], iy = p[1], iz = p[2], sx = a, sy = b, sz = c };
                        var p1 = m.Point(bMin);
                        var p2 = m.Point(bMax);
                        if ((Vector3.Min(p1, p2) - unityBounds.min).sqrMagnitude < tol * tol &&
                            (Vector3.Max(p1, p2) - unityBounds.max).sqrMagnitude < tol * tol)
                            found.Add(m);
                    }
        return found;
    }

    /// <summary>
    /// The one mapping consistent with EVERY module in a manifest.
    ///
    /// <para>One export ran with one set of axis settings, so one mapping has to
    /// explain all of them. Deriving it per module instead let symmetric modules
    /// choose a different sign from their asymmetric neighbours - which scattered
    /// the reassembled corridor across 18.8 m of ground where the source asset is
    /// 10.8 m wide, and mirrored their collision, while every test still passed,
    /// because both size and cell-fit are sign-invariant.</para>
    /// </summary>
    public static bool DeriveManifestMapping(Manifest man, out Mapping mapping, out string why)
    {
        mapping = default;
        List<Mapping> survivors = null;
        int used = 0;

        foreach (var mod in man.modules)
        {
            string fbx = ModuleFolder + "/" + mod.name + ".fbx";
            var mesh = AssetDatabase.LoadAllAssetsAtPath(fbx).OfType<Mesh>()
                                    .FirstOrDefault(m => !m.name.EndsWith("_COL_WEDGE"));
            if (mesh == null) continue;

            var cands = CandidateMappings(mod.bounds_min_blender, mod.bounds_max_blender,
                                          mesh.bounds);
            if (cands.Count == 0)
            {
                why = $"{mod.name}: no signed permutation reproduces its bounds - the FBX is " +
                      "not the mesh this manifest describes.";
                return false;
            }
            survivors = survivors == null
                ? cands
                : survivors.Where(sv => cands.Any(c => c.Same(sv))).ToList();
            used++;
            if (survivors.Count == 0)
            {
                why = $"no single axis mapping explains every module (failed at {mod.name}).";
                return false;
            }
        }

        if (survivors == null || used == 0) { why = "no modules with meshes."; return false; }
        if (survivors.Count > 1)
        {
            why = $"{survivors.Count} mappings fit all {used} modules " +
                  $"({string.Join(" / ", survivors.Select(m => m.ToString()))}). Every module in " +
                  "this set is symmetric about its own origin, so nothing here can tell the signs " +
                  "apart. Refusing rather than guessing.";
            return false;
        }

        mapping = survivors[0];
        why = $"derived from {used} module(s): {mapping}";
        return true;
    }

    // ---- one module -------------------------------------------------------

    static bool BuildModule(Manifest man, ModRec mod, Dictionary<string, Material> mats,
                            Mapping map, List<string> problems)
    {
        string fbxPath = $"{ModuleFolder}/{mod.name}.fbx";
        if (!File.Exists(fbxPath))
        {
            problems.Add($"{mod.name}: no FBX at {fbxPath}");
            return false;
        }

        var imp = AssetImporter.GetAtPath(fbxPath) as ModelImporter;
        if (imp != null)
        {
            bool dirty = false;
            if (!imp.useFileScale) { imp.useFileScale = true; dirty = true; }
            if (!Mathf.Approximately(imp.globalScale, 1f)) { imp.globalScale = 1f; dirty = true; }
            // we build our own URP materials from the manifest, which records which
            // map feeds which slot; the FBX's own materials are the trap
            if (imp.materialImportMode != ModelImporterMaterialImportMode.None)
            { imp.materialImportMode = ModelImporterMaterialImportMode.None; dirty = true; }
            if (!imp.generateSecondaryUV) { imp.generateSecondaryUV = true; dirty = true; }
            if (imp.addCollider) { imp.addCollider = false; dirty = true; }
            if (imp.importNormals != ModelImporterNormals.Import)
            { imp.importNormals = ModelImporterNormals.Import; dirty = true; }
            if (dirty) imp.SaveAndReimport();
        }

        var meshes = AssetDatabase.LoadAllAssetsAtPath(fbxPath).OfType<Mesh>().ToList();
        var wedgeMesh = meshes.FirstOrDefault(m => m.name.EndsWith("_COL_WEDGE"));
        var renderMesh = meshes.FirstOrDefault(m => !m.name.EndsWith("_COL_WEDGE"));
        if (renderMesh == null)
        {
            problems.Add($"{mod.name}: FBX contains no render mesh");
            return false;
        }

        if (!CandidateMappings(mod.bounds_min_blender, mod.bounds_max_blender,
                               renderMesh.bounds).Any(c => c.Same(map)))
        {
            problems.Add(
                $"{mod.name}: the manifest-wide axis mapping {map} does not reproduce this " +
                $"module bounds ({V(mod.bounds_min_blender)})..({V(mod.bounds_max_blender)}) " +
                $"against the imported mesh {renderMesh.bounds.min}..{renderMesh.bounds.max}.");
            return false;
        }

        var root = new GameObject("Kit_" + mod.name);
        try
        {
            var render = new GameObject("Render");
            render.transform.SetParent(root.transform, false);
            render.AddComponent<MeshFilter>().sharedMesh = renderMesh;
            var mr = render.AddComponent<MeshRenderer>();
            mr.sharedMaterials = mod.materials
                .Select(n => mats.TryGetValue(n, out var m) ? m : null)
                .ToArray();

            var collision = new GameObject("Collision");
            collision.transform.SetParent(root.transform, false);
            foreach (var b in mod.boxes)
            {
                if (b?.center == null || b.size == null) continue;
                var bc = collision.AddComponent<BoxCollider>();
                bc.center = map.Point(b.center);
                bc.size = map.Extent(b.size);
            }

            if (wedgeMesh != null)
            {
                var ramp = new GameObject("Collision_StairRamp");
                ramp.transform.SetParent(root.transform, false);
                var mc = ramp.AddComponent<MeshCollider>();
                mc.sharedMesh = wedgeMesh;
                mc.convex = false;
            }

            // No NavigationStatic: it is deprecated, and this map does not use it.
            // MapSeeder bakes with collectObjects = All and useGeometry =
            // PhysicsColliders, so what the navmesh sees is the collider proxy above,
            // not the render mesh and not a static flag.
            const StaticEditorFlags flags =
                StaticEditorFlags.ContributeGI | StaticEditorFlags.OccluderStatic |
                StaticEditorFlags.OccludeeStatic | StaticEditorFlags.BatchingStatic |
                StaticEditorFlags.ReflectionProbeStatic;
            foreach (var t in root.GetComponentsInChildren<Transform>(true))
                GameObjectUtility.SetStaticEditorFlags(t.gameObject, flags);

            string prefabPath = $"{ModuleFolder}/Kit_{mod.name}.prefab";
            PrefabUtility.SaveAsPrefabAsset(root, prefabPath);

            foreach (var p in Validate(mod, renderMesh, map)) problems.Add(p);
            return true;
        }
        finally
        {
            UnityEngine.Object.DestroyImmediate(root);
        }
    }

    static string V(float[] a)
    {
        return a == null ? "null" : string.Join(", ", a.Select(f => f.ToString("0.###")));
    }

    // ---- the contract -----------------------------------------------------

    /// <summary>
    /// What a kit module promises. Deliberately NOT the same list as
    /// <c>MapKitTests.Kit_PiecesAreAnchoredToTheGridSoTheySnap</c>: a hand-authored
    /// piece is symmetric about its own origin, while a module is a slice of a real
    /// building and its geometry sits wherever the building put it. What must hold
    /// is that it fits its cell and that its walkable surface is at y = 0.
    /// </summary>
    public static List<string> Validate(ModRec mod, Mesh renderMesh, Mapping map)
    {
        var bad = new List<string>();
        var b = renderMesh.bounds;
        float half = HalfCell + 0.01f;

        if (b.min.x < -half || b.max.x > half || b.min.z < -half || b.max.z > half)
            bad.Add($"{mod.name}: overhangs its {MapKitBuilder.Grid} m cell " +
                    $"(x {b.min.x:0.###}..{b.max.x:0.###}, z {b.min.z:0.###}..{b.max.z:0.###})");

        if (b.min.y > 0.05f || b.max.y < -0.05f)
            bad.Add($"{mod.name}: nothing at the walkable plane y = 0 " +
                    $"(y {b.min.y:0.###}..{b.max.y:0.###})");

        foreach (var box in mod.boxes)
        {
            if (box?.size == null) continue;
            float thinnest = Mathf.Min(box.size[0], Mathf.Min(box.size[1], box.size[2]));
            if (thinnest < MapKitBuilder.Thickness - 1e-3f)
                bad.Add($"{mod.name}: a collider box is {thinnest:0.###} m on its thinnest axis, " +
                        $"below the {MapKitBuilder.Thickness} m Law 7 floor — the navmesh bakes at " +
                        "0.12 m voxels and zombies path through anything thinner");
        }

        if (mod.colliders_intruding_into_walkable_space > 0)
            bad.Add($"{mod.name}: {mod.colliders_intruding_into_walkable_space} collider box(es) " +
                    "contain space a player can stand in — an invisible obstruction");

        return bad;
    }
}
