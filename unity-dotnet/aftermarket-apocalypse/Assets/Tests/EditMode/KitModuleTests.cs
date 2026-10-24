using System.Collections.Generic;
using System.IO;
using System.Linq;
using NUnit.Framework;
using UnityEditor;
using UnityEngine;

/// <summary>
/// The contract for modules cut out of a downloaded .blend by
/// <c>Tools/map/kit_cut.py</c> and imported by <see cref="KitModuleBuilder"/>.
///
/// <para>Deliberately a separate file from <c>MapKitTests</c>. Those iterate
/// <c>MapKitBuilder.Pieces</c>, which is the nine hand-authored ProBuilder pieces,
/// and several of their assertions do not apply here — a hand-authored wall is
/// symmetric about its own origin, while a module is a slice of a real building and
/// its geometry sits wherever the building put it. What must hold either way is
/// that a piece fits its cell, that its floor is at y = 0, and that its collision
/// is thicker than the navmesh voxel.</para>
/// </summary>
public class KitModuleTests
{
    static KitModuleBuilder.Manifest[] s_Manifests;

    static KitModuleBuilder.Manifest[] Manifests()
    {
        if (s_Manifests == null)
            s_Manifests = KitModuleBuilder.ManifestPaths()
                .Select(KitModuleBuilder.Load).Where(m => m != null).ToArray();
        return s_Manifests;
    }

    static IEnumerable<KitModuleBuilder.ModRec> Modules()
    {
        return Manifests().SelectMany(m => m.modules);
    }

    static void RequireModules()
    {
        if (!Modules().Any())
            Assert.Ignore("No kit modules in this checkout — nothing to verify.");
    }

    static GameObject LoadPrefab(KitModuleBuilder.ModRec mod)
    {
        string path = $"{KitModuleBuilder.ModuleFolder}/Kit_{mod.name}.prefab";
        var go = AssetDatabase.LoadAssetAtPath<GameObject>(path);
        Assert.IsNotNull(go, $"{mod.name}: no prefab at {path}");
        return go;
    }

    [Test]
    public void KitModule_EveryModuleInTheManifestHasAPrefab()
    {
        RequireModules();
        foreach (var mod in Modules()) Assert.IsNotNull(LoadPrefab(mod));
    }

    [Test]
    public void KitModule_EveryColliderClearsTheNavmeshVoxel_Law7()
    {
        // §14 Law 7 again, and it is the reason the collision is a generated proxy
        // rather than the render mesh: the downloaded walls are ~0.2 m thick, the
        // navmesh bakes at 0.12 m voxels, and a collider thinner than the voxel is
        // missed by the bake. Zombies then walk through a wall that looks solid.
        RequireModules();
        foreach (var mod in Modules())
        {
            var root = LoadPrefab(mod);
            var boxes = root.GetComponentsInChildren<BoxCollider>();
            Assert.AreEqual(mod.boxes.Count, boxes.Length,
                $"{mod.name}: manifest says {mod.boxes.Count} collider boxes, prefab has {boxes.Length}");

            foreach (var b in boxes)
            {
                float thinnest = Mathf.Min(b.size.x, Mathf.Min(b.size.y, b.size.z));
                Assert.GreaterOrEqual(thinnest, MapKitBuilder.Thickness - 1e-3f,
                    $"{mod.name}/{b.name}: thinnest collider axis is {thinnest:0.###} m, " +
                    $"below the {MapKitBuilder.Thickness} m floor. Size was {b.size}.");
            }
        }
    }

    [Test]
    public void KitModule_NoColliderContainsSpaceAPlayerCanStandIn()
    {
        // Measured in Blender by ray cast, not asserted here from first principles:
        // a collider that contains a standable point is an invisible step, wall or
        // ceiling. This is the check that caught the collision proxy thickening the
        // floor UPWARD into the corridor by 0.3 m.
        RequireModules();
        foreach (var mod in Modules())
            Assert.AreEqual(0, mod.colliders_intruding_into_walkable_space,
                $"{mod.name}: {mod.colliders_intruding_into_walkable_space} collider box(es) " +
                "contain space a player stands in.");
    }

    [Test]
    public void KitModule_NothingOverhangsItsCell()
    {
        RequireModules();
        float half = MapKitBuilder.Grid * 0.5f + 0.01f;
        foreach (var mod in Modules())
        {
            var mf = LoadPrefab(mod).GetComponentInChildren<MeshFilter>();
            Assert.IsNotNull(mf, $"{mod.name}: no MeshFilter");
            var b = mf.sharedMesh.bounds;
            Assert.LessOrEqual(Mathf.Max(-b.min.x, b.max.x), half,
                $"{mod.name}: reaches past its {MapKitBuilder.Grid} m cell on X ({b.min.x:0.###}..{b.max.x:0.###})");
            Assert.LessOrEqual(Mathf.Max(-b.min.z, b.max.z), half,
                $"{mod.name}: reaches past its {MapKitBuilder.Grid} m cell on Z ({b.min.z:0.###}..{b.max.z:0.###})");
        }
    }

    [Test]
    public void KitModule_TheWalkablePlaneIsTheOrigin()
    {
        // Kit_Floor_4x4 puts its walkable top surface at y = 0, so every floor in
        // the map sits at y = 0. A module whose floor is somewhere else does not
        // drop into that coordinate space, and the mistake shows up as a step.
        RequireModules();
        foreach (var mod in Modules())
        {
            var b = LoadPrefab(mod).GetComponentInChildren<MeshFilter>().sharedMesh.bounds;
            Assert.LessOrEqual(b.min.y, 0.05f,
                $"{mod.name}: its lowest geometry is at y {b.min.y:0.###}, above the walkable plane");
            Assert.GreaterOrEqual(b.max.y, -0.05f,
                $"{mod.name}: all of its geometry is below the walkable plane (max y {b.max.y:0.###})");
        }
    }

    [Test]
    public void KitModule_EveryMaterialSlotIsFilledWithUrpLit()
    {
        RequireModules();
        foreach (var mod in Modules())
        {
            var mr = LoadPrefab(mod).GetComponentInChildren<MeshRenderer>();
            Assert.IsNotNull(mr, $"{mod.name}: no MeshRenderer");
            Assert.AreEqual(mod.materials.Count, mr.sharedMaterials.Length,
                $"{mod.name}: {mod.materials.Count} submeshes but {mr.sharedMaterials.Length} materials");
            for (int i = 0; i < mr.sharedMaterials.Length; i++)
            {
                var m = mr.sharedMaterials[i];
                Assert.IsNotNull(m, $"{mod.name}: material slot {i} ({mod.materials[i]}) is empty");
                Assert.AreEqual("Universal Render Pipeline/Lit", m.shader.name,
                    $"{mod.name}: material {m.name} is on {m.shader.name}, not URP/Lit");
            }
        }
    }

    [Test]
    public void KitModule_NormalMapsAreImportedAsNormalMapsAndMaskMapsAreLinear()
    {
        // The asset ships no normal maps at all: every material feeds a greyscale
        // HEIGHT map into the Normal input through a Bump node, and kit_cut.py
        // converts it. Both halves of this are silent failures — a height map bound
        // straight into _BumpMap decodes to a garbage normal, and a metallic/
        // smoothness map imported as sRGB has every value gamma-shifted. Neither
        // errors, and both are only visible as "the lighting looks a bit off".
        RequireModules();
        foreach (var man in Manifests())
        {
            foreach (var rec in man.material_maps)
            {
                if (!string.IsNullOrEmpty(rec.normalMap))
                {
                    var imp = Importer(rec.normalMap);
                    Assert.IsNotNull(imp, $"{rec.name}: normal map {rec.normalMap} is not in the project");
                    Assert.AreEqual(TextureImporterType.NormalMap, imp.textureType,
                        $"{rec.name}: {rec.normalMap} is imported as {imp.textureType}, not NormalMap");
                }
                if (!string.IsNullOrEmpty(rec.metallicGlossMap))
                {
                    var imp = Importer(rec.metallicGlossMap);
                    Assert.IsNotNull(imp, $"{rec.name}: mask map {rec.metallicGlossMap} is not in the project");
                    Assert.IsFalse(imp.sRGBTexture,
                        $"{rec.name}: {rec.metallicGlossMap} is imported as sRGB — metallic and " +
                        "smoothness are linear data, not colour");
                    Assert.AreEqual(TextureImporterAlphaSource.FromInput, imp.alphaSource,
                        $"{rec.name}: {rec.metallicGlossMap} discards its alpha, which is where " +
                        "URP/Lit reads SMOOTHNESS from");
                }
            }
        }
    }

    static TextureImporter Importer(string file)
    {
        return AssetImporter.GetAtPath($"{KitModuleBuilder.ModuleFolder}/Textures/{file}")
               as TextureImporter;
    }

    [Test]
    public void KitModule_StairsAreOneRampAndTheNavmeshCanBakeThem()
    {
        // A box per 0.18 m tread is illegal under Law 7 and makes the player climb
        // by a chain of teleports, so a flight becomes one wedge. The wedge is only
        // useful if the navmesh will bake it, which means its walkable face has to
        // be under the 45 degree slope limit — the same limit the ramp trap in
        // docs/map-build.md was about.
        RequireModules();
        int checkedRamps = 0;
        foreach (var mod in Modules())
        {
            if (!mod.stair_wedge) continue;
            var root = LoadPrefab(mod);
            var ramp = root.GetComponentsInChildren<MeshCollider>()
                           .FirstOrDefault(c => c.name == "Collision_StairRamp");
            Assert.IsNotNull(ramp, $"{mod.name}: manifest says it has a stair wedge, prefab has no ramp collider");
            Assert.IsNotNull(ramp.sharedMesh, $"{mod.name}: ramp collider has no mesh");

            float best = 0f, bestArea = 0f;
            var mesh = ramp.sharedMesh;
            var v = mesh.vertices;
            var t = mesh.triangles;
            for (int i = 0; i < t.Length; i += 3)
            {
                Vector3 a = v[t[i]], b = v[t[i + 1]], c = v[t[i + 2]];
                Vector3 cross = Vector3.Cross(b - a, c - a);
                float area = cross.magnitude * 0.5f;
                Vector3 n = cross.normalized;
                if (n.y <= 0.01f || area <= bestArea) continue;
                bestArea = area;
                best = Vector3.Angle(n, Vector3.up);
            }
            Assert.Greater(bestArea, 0f, $"{mod.name}: the ramp has no upward-facing surface at all");
            Assert.LessOrEqual(best, 45f,
                $"{mod.name}: the ramp's walkable face is {best:0.#}°, over the 45° navmesh slope limit");
            checkedRamps++;
        }
        Assert.Greater(checkedRamps, 0, "no module carried a stair wedge — the ramp path is untested");
    }
}
