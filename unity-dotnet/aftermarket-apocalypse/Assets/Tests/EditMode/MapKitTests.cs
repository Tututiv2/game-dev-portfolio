using NUnit.Framework;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Phase 3, layer 1: the modular structure kit (spec §17's rewritten art box).
///
/// Same principle as <c>ContentTests</c> — these load the real prefabs off disk,
/// so "the kit exists" cannot be claimed without it being true. The Law 7
/// thickness test is the important one: it is the single geometric mistake that
/// makes zombies walk through walls, and it is invisible until you playtest.
/// </summary>
public class MapKitTests
{
    static GameObject LoadPiece(MapKitBuilder.KitPiece piece)
    {
        var go = AssetDatabase.LoadAssetAtPath<GameObject>(piece.PrefabPath);
        Assert.IsNotNull(go,
            $"Missing kit piece at {piece.PrefabPath}. Run Tools > Aftermarket > Build Map Kit.");
        return go;
    }

    [Test]
    public void Kit_EveryPieceExistsAsAPrefab()
    {
        foreach (var piece in MapKitBuilder.Pieces)
            LoadPiece(piece);
    }

    [Test]
    public void Kit_EveryPieceIsSurfacedWithARealPbrMaterial()
    {
        foreach (var piece in MapKitBuilder.Pieces)
        {
            var mat = AssetDatabase.LoadAssetAtPath<Material>(piece.MaterialPath);
            Assert.IsNotNull(mat,
                $"{piece.Name}: no material at {piece.MaterialPath}. §17 requires the owned PBR sets, " +
                "not painted greyscale — the subset has to be copied into Art/Materials.");

            Assert.IsNotNull(mat.shader, $"{piece.Name}: material has no shader.");
            StringAssert.Contains("Universal Render Pipeline", mat.shader.name,
                $"{piece.Name}: expected a URP shader, got '{mat.shader.name}'.");

            // §17's whole argument is that quality lives in the material. A set with
            // no normal map is exactly the flat look the old build had.
            Assert.IsTrue(mat.HasProperty("_BumpMap") && mat.GetTexture("_BumpMap") != null,
                $"{piece.Name}: material '{mat.name}' has no normal map bound.");

            foreach (var renderer in LoadPiece(piece).GetComponentsInChildren<MeshRenderer>())
                Assert.AreEqual(mat, renderer.sharedMaterial,
                    $"{piece.Name}: renderer '{renderer.name}' is not using the kit material.");
        }
    }

    [Test]
    public void Kit_EveryColliderIsThickerThanTheNavmeshVoxel_Law7()
    {
        // §14 Law 7: "Keep collision >= 0.5 m thick even when the visual is thin."
        // §14 Law 13 bakes at 0.12 m voxels; anything thinner than the voxel is
        // missed by the bake and zombies path straight through it.
        foreach (var piece in MapKitBuilder.Pieces)
        {
            var root = LoadPiece(piece);
            var colliders = root.GetComponentsInChildren<MeshCollider>();

            Assert.IsNotEmpty(colliders, $"{piece.Name}: no MeshCollider at all — Law 15.");

            foreach (var collider in colliders)
            {
                Assert.IsNotNull(collider.sharedMesh, $"{piece.Name}/{collider.name}: collider has no mesh.");

                Vector3 size = collider.sharedMesh.bounds.size;
                float thinnest = Mathf.Min(size.x, Mathf.Min(size.y, size.z));

                Assert.GreaterOrEqual(thinnest, MapKitBuilder.Thickness - 1e-3f,
                    $"{piece.Name}/{collider.name}: thinnest axis is {thinnest:0.###} m, " +
                    $"below the {MapKitBuilder.Thickness} m Law 7 floor. Size was {size}.");
            }
        }
    }

    [Test]
    public void Kit_PiecesAreAnchoredToTheGridSoTheySnap()
    {
        // §17: "on a fixed grid ... so pieces snap and tile". The contract this
        // tool commits to is that a piece's root origin is the bottom-centre of
        // its footprint, so placing it at a grid coordinate puts it there exactly.
        foreach (var piece in MapKitBuilder.Pieces)
        {
            var root = LoadPiece(piece);

            Bounds? combined = null;
            foreach (var filter in root.GetComponentsInChildren<MeshFilter>())
            {
                var b = filter.sharedMesh.bounds;
                // Into root space. Children may be rotated (the ramp is).
                var t = filter.transform;
                var world = new Bounds(t.localRotation * b.center + t.localPosition, Vector3.zero);
                foreach (var corner in Corners(b))
                    world.Encapsulate(t.localRotation * corner + t.localPosition);

                if (combined.HasValue) { var c = combined.Value; c.Encapsulate(world); combined = c; }
                else combined = world;
            }

            Assert.IsTrue(combined.HasValue, $"{piece.Name}: no meshes.");
            Bounds bounds = combined.Value;

            Assert.AreEqual(0f, bounds.center.x, 0.01f, $"{piece.Name}: not centred on X.");
            Assert.AreEqual(0f, bounds.center.z, 0.01f, $"{piece.Name}: not centred on Z.");

            // Floors hang below the origin so their walkable surface is y = 0.
            // Everything else sits on it.
            float expected = piece.Name == "Kit_Floor_4x4" ? bounds.max.y : bounds.min.y;
            Assert.AreEqual(0f, expected, 0.01f,
                $"{piece.Name}: origin is not on the piece's seating plane (bounds y " +
                $"{bounds.min.y:0.###}..{bounds.max.y:0.###}).");

            // Nothing may overhang its 4 m cell, or the kit stops tiling.
            Assert.LessOrEqual(bounds.size.x, MapKitBuilder.Grid + 0.01f, $"{piece.Name}: wider than one grid cell.");
            Assert.LessOrEqual(bounds.size.z, MapKitBuilder.Grid + 0.01f, $"{piece.Name}: deeper than one grid cell.");
        }
    }

    [Test]
    public void Kit_TheRampsWalkableSurfaceStartsOnTheFloor()
    {
        // The ramp used to be a slab rotated about its own centre. Every test above
        // passed on it — its bounds started at y = 0, it fitted its cell, its
        // collider was thick enough — and it was still unusable, because a tilted
        // slab lifts its low end by Thickness * cos(angle) = 0.45 m. That is a
        // 0.45 m step at the foot of the ramp: over the navmesh step height, so no
        // zombie could get on it, and over CharacterController.stepOffset, so nor
        // could the player.
        //
        // Bounds could never see that. The walkable SURFACE has to be measured, so
        // this reads the actual vertices at the low edge.
        var piece = System.Array.Find(MapKitBuilder.Pieces, p => p.Name == "Kit_Ramp_4");
        var root = LoadPiece(piece);

        var filter = root.GetComponentInChildren<MeshFilter>();
        Assert.IsNotNull(filter?.sharedMesh, "Kit_Ramp_4 has no mesh.");

        var t = filter.transform;
        var vertices = filter.sharedMesh.vertices;

        float lowestZ = float.MaxValue, peak = float.MinValue;
        foreach (var v in vertices)
        {
            var p = t.localRotation * v + t.localPosition;
            lowestZ = Mathf.Min(lowestZ, p.z);
            peak = Mathf.Max(peak, p.y);
        }

        float topOfLowEnd = float.MinValue;
        foreach (var v in vertices)
        {
            var p = t.localRotation * v + t.localPosition;
            if (p.z <= lowestZ + 0.01f) topOfLowEnd = Mathf.Max(topOfLowEnd, p.y);
        }

        Assert.AreEqual(0f, topOfLowEnd, 0.02f,
            $"The ramp's surface at its low edge is {topOfLowEnd:0.###} m above the floor. " +
            "Anything over the navmesh step height is a wall the ramp cannot be walked onto.");

        Assert.AreEqual(MapKitBuilder.RampRise, peak, 0.02f,
            $"The ramp climbs to {peak:0.###} m, not the declared RampRise of " +
            $"{MapKitBuilder.RampRise} m. Anything placed to meet it at the top will not line up.");
    }

    [Test]
    public void Kit_DoorwayAndWindowActuallyHaveOpenings()
    {
        // A "doorway" wall that came out solid is just a wall, and you would only
        // find out by walking into it. Bounds volume cannot see a hole in a single
        // mesh — a doorway wall's bounding box is still the full wall — so this
        // measures true enclosed volume via the divergence theorem.
        float full = MapKitBuilder.Grid * MapKitBuilder.WallHeight * MapKitBuilder.Thickness;

        // Control. If the method cannot measure a plain ProBuilder box correctly,
        // nothing it says about the other two pieces means anything.
        float solid = SignedVolume("Kit_Wall_4");
        Assert.AreEqual(full, solid, 0.05f,
            $"Control failed: a solid 4x4 wall should enclose {full:0.##} m3, measured {solid:0.##}. " +
            "The volume method is unreliable, so the opening checks below prove nothing.");

        float doorway = SignedVolume("Kit_Wall_4_Doorway");
        Assert.Less(doorway, full * 0.9f,
            $"Kit_Wall_4_Doorway encloses {doorway:0.##} m3 of a possible {full:0.##} — it looks solid. " +
            "GenerateDoor's legWidth / ledgeHeight are probably wrong.");

        float window = SignedVolume("Kit_Wall_4_Window");
        Assert.Less(window, full * 0.9f,
            $"Kit_Wall_4_Window encloses {window:0.##} m3 of a possible {full:0.##} — it looks solid.");
    }

    /// <summary>
    /// Enclosed volume of every mesh under a piece, by the divergence theorem:
    /// sum of the signed tetrahedron volumes each triangle forms with the origin.
    /// Exact for closed meshes and sign-correct for Unity's clockwise winding.
    /// </summary>
    static float SignedVolume(string name)
    {
        var piece = System.Array.Find(MapKitBuilder.Pieces, p => p.Name == name);
        var root = AssetDatabase.LoadAssetAtPath<GameObject>(piece.PrefabPath);
        Assert.IsNotNull(root, $"Missing {piece.PrefabPath}.");

        float total = 0f;
        foreach (var filter in root.GetComponentsInChildren<MeshFilter>())
        {
            var mesh = filter.sharedMesh;
            Assert.IsNotNull(mesh, $"{name}/{filter.name}: null sharedMesh.");

            var verts = mesh.vertices;
            var tris = mesh.triangles;
            float v = 0f;
            for (int i = 0; i < tris.Length; i += 3)
                v += Vector3.Dot(verts[tris[i]], Vector3.Cross(verts[tris[i + 1]], verts[tris[i + 2]])) / 6f;

            total += Mathf.Abs(v);
        }
        return total;
    }

    static Vector3[] Corners(Bounds b)
    {
        Vector3 mn = b.min, mx = b.max;
        return new[]
        {
            new Vector3(mn.x, mn.y, mn.z), new Vector3(mx.x, mn.y, mn.z),
            new Vector3(mn.x, mx.y, mn.z), new Vector3(mx.x, mx.y, mn.z),
            new Vector3(mn.x, mn.y, mx.z), new Vector3(mx.x, mn.y, mx.z),
            new Vector3(mn.x, mx.y, mx.z), new Vector3(mx.x, mx.y, mx.z),
        };
    }
}
