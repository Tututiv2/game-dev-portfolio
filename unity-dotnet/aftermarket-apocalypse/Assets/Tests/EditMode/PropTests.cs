using System.Collections.Generic;
using System.IO;
using NUnit.Framework;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Phase 3, layer 3: the generated props (spec §17 — "generated locally and free").
///
/// The important test here is the boring-looking one: that each prop imported as
/// real geometry at all. Unity 6 has no glTF importer, so a .glb dropped into
/// Assets/ becomes an unrecognised DefaultAsset with no mesh, no material and
/// <b>no import error</b>. The forge would look like it worked, the files would be
/// the right size, and the props would simply not exist. That failure is silent
/// enough to survive a code review, so it gets a test.
/// </summary>
public class PropTests
{
    const string PropFolder = "Assets/_Project/Art/Props";
    const string ManifestPath = "Tools/forge/props.json";

    static string RepoRoot => Directory.GetParent(Application.dataPath).FullName;

    static List<string> ForgedProps()
    {
        var props = new List<string>();
        if (!Directory.Exists(PropFolder)) return props;

        foreach (string path in Directory.GetFiles(PropFolder, "*.fbx"))
        {
            string name = Path.GetFileNameWithoutExtension(path);
            if (name.Contains("_LOD")) continue;   // LODs are checked separately
            props.Add(path.Replace('\\', '/'));
        }
        return props;
    }

    [Test]
    public void Props_TheForgeProducedSomething()
    {
        Assert.IsNotEmpty(ForgedProps(),
            $"No props in {PropFolder}. Run: python Tools/forge/forge_props.py");
    }

    [Test]
    public void Props_NoGlbEverReachesAssets()
    {
        // §17 claims Asset Forge's Unity target emits a GLB "ready to drop into
        // Assets/". It is not: Unity 6 ships no glTF importer. This asserts the
        // forge's FBX path stayed fixed.
        Assert.IsTrue(Directory.Exists(PropFolder), $"{PropFolder} does not exist.");

        var strays = new List<string>();
        foreach (string path in Directory.GetFiles(PropFolder, "*", SearchOption.AllDirectories))
        {
            string ext = Path.GetExtension(path).ToLowerInvariant();
            if (ext == ".glb" || ext == ".gltf") strays.Add(path);
        }

        Assert.IsEmpty(strays,
            "glTF files found under Art/Props: " + string.Join(", ", strays) +
            ". Unity 6 cannot import them — they land as DefaultAssets with no mesh and " +
            "no error. mesh_pipeline.py's engine_profile() must return .fbx for unity.");
    }

    [Test]
    public void Props_EveryPropImportsAsRealGeometry()
    {
        foreach (string path in ForgedProps())
        {
            var asset = AssetDatabase.LoadAssetAtPath<GameObject>(path);
            Assert.IsNotNull(asset,
                $"{path} did not import as a model. If this is a glTF, Unity 6 cannot read it.");

            var filters = asset.GetComponentsInChildren<MeshFilter>(true);
            Assert.IsNotEmpty(filters, $"{path} imported but contains no MeshFilter.");

            int triangles = 0;
            foreach (var filter in filters)
            {
                Assert.IsNotNull(filter.sharedMesh, $"{path}: MeshFilter with no mesh.");
                triangles += filter.sharedMesh.triangles.Length / 3;
            }

            Assert.Greater(triangles, 0, $"{path} has no triangles.");
        }
    }

    [Test]
    public void Props_StayWithinTheirTriangleBudget()
    {
        // A generated mesh arrives around 200k triangles. If the budget silently
        // stopped being applied, the props would still import and still look right
        // in a screenshot — and tank the framerate.
        string manifestFile = Path.Combine(RepoRoot, ManifestPath);
        if (!File.Exists(manifestFile))
            Assert.Ignore($"No {ManifestPath}; nothing to check budgets against.");

        var manifest = JsonUtility.FromJson<Manifest>(File.ReadAllText(manifestFile));
        Assert.IsNotNull(manifest?.props, $"{ManifestPath} did not parse.");
        Assert.IsNotEmpty(manifest.props, $"{ManifestPath} lists no props.");

        foreach (var entry in manifest.props)
        {
            Assert.Greater(entry.sourceTris, entry.finalTris,
                $"{entry.slug}: decimation did nothing ({entry.sourceTris} -> {entry.finalTris}).");

            // Decimation collapses edges, so it lands near the budget rather than on
            // it. A wide-but-real ceiling: the point is to catch "budget ignored",
            // not to police the exact number.
            Assert.LessOrEqual(entry.finalTris, entry.targetTris * 3,
                $"{entry.slug}: {entry.finalTris} tris against a {entry.targetTris} budget.");

            // Every prop must be reproducible from what is recorded.
            Assert.Greater(entry.seed, 0, $"{entry.slug}: no seed recorded, so the input is lost.");
            Assert.IsNotEmpty(entry.prompt, $"{entry.slug}: no prompt recorded.");
        }
    }

    [System.Serializable] class Manifest { public List<Entry> props; }

    [System.Serializable]
    class Entry
    {
        public string slug;
        public string prompt;
        public long seed;
        public int targetTris;
        public int sourceTris;
        public int finalTris;
    }
}
