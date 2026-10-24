using System.Collections.Generic;
using System.Linq;
using System.Text;
using UnityEditor;
using UnityEngine;

/// <summary>
/// A map as DATA: rectangles of walkable cells on the project's 4 m grid, and the
/// kit geometry derived from them.
///
/// <para><b>Why this exists.</b> <see cref="MapSeeder"/> hand-writes every room of
/// <c>AshfallDepot</c> as its own method, and every phase builder hard-codes that
/// map's world coordinates. All of it is correct and none of it can be pointed at a
/// second map. This is the same knowledge — 4 m cells, walls on cell BOUNDARIES,
/// floors whose origin is their walkable top — expressed once so any number of maps
/// can be authored as a table of rectangles.</para>
///
/// <para><b>Walls are derived, never typed.</b> A wall goes wherever a walkable cell
/// meets a non-walkable one. Typing wall coordinates by hand is a second source of
/// truth that drifts the moment a room moves, which is the failure this project has
/// already paid for more than once.</para>
/// </summary>
public static class MapPlan
{
    public const float Cell = 4f;              // MapKitBuilder.Grid
    public const float StoreyHeight = 4f;      // MapKitBuilder.WallHeight

    public enum Edge { North, East, South, West }

    /// <summary>Centre of a cell, at the walkable plane of its storey.</summary>
    public static Vector3 Centre(int cx, int cz, int storey = 0)
    {
        return new Vector3(cx * Cell, storey * StoreyHeight, cz * Cell);
    }

    public static Vector2Int Step(Edge e)
    {
        switch (e)
        {
            case Edge.North: return new Vector2Int(0, 1);
            case Edge.South: return new Vector2Int(0, -1);
            case Edge.East: return new Vector2Int(1, 0);
            default: return new Vector2Int(-1, 0);
        }
    }

    // ---- the plan ---------------------------------------------------------

    /// <summary>One walkable storey. Everything else is derived from these sets.</summary>
    public class Storey
    {
        public int index;
        public readonly HashSet<Vector2Int> Floor = new HashSet<Vector2Int>();
        public readonly HashSet<Vector2Int> Roof = new HashSet<Vector2Int>();

        /// <summary>Cells whose geometry comes from somewhere else — an imported
        /// module — so the kit must not floor, wall or roof them.</summary>
        public readonly HashSet<Vector2Int> Reserved = new HashSet<Vector2Int>();

        public readonly Dictionary<(Vector2Int, Edge), string> Forced =
            new Dictionary<(Vector2Int, Edge), string>();
        public readonly HashSet<(Vector2Int, Edge)> Open = new HashSet<(Vector2Int, Edge)>();

        public Storey(int index) { this.index = index; }

        /// <summary>Add a rectangle of walkable cells, inclusive of both corners.</summary>
        public Storey Room(int x0, int z0, int x1, int z1, bool roofed = true)
        {
            for (int x = Mathf.Min(x0, x1); x <= Mathf.Max(x0, x1); x++)
                for (int z = Mathf.Min(z0, z1); z <= Mathf.Max(z0, z1); z++)
                {
                    Floor.Add(new Vector2Int(x, z));
                    if (roofed) Roof.Add(new Vector2Int(x, z));
                    else Roof.Remove(new Vector2Int(x, z));
                }
            return this;
        }

        /// <summary>Hand a rectangle over to imported geometry.</summary>
        public Storey Module(int x0, int z0, int x1, int z1)
        {
            for (int x = Mathf.Min(x0, x1); x <= Mathf.Max(x0, x1); x++)
                for (int z = Mathf.Min(z0, z1); z <= Mathf.Max(z0, z1); z++)
                {
                    var c = new Vector2Int(x, z);
                    Floor.Add(c);
                    Reserved.Add(c);
                    Roof.Remove(c);
                }
            return this;
        }

        /// <summary>Put a specific piece on one cell edge — a doorway or a window
        /// between two cells that would otherwise have no wall at all.</summary>
        public Storey Wall(int x, int z, Edge e, string piece)
        {
            Forced[(new Vector2Int(x, z), e)] = piece;
            return this;
        }

        /// <summary>Leave one edge with no wall, whatever the derivation says.</summary>
        public Storey Opening(int x, int z, Edge e)
        {
            Open.Add((new Vector2Int(x, z), e));
            return this;
        }

        public bool Walkable(Vector2Int c) { return Floor.Contains(c); }
    }

    // ---- building ---------------------------------------------------------

    static int s_Placed;
    public static int PlacedThisRun => s_Placed;
    public static void ResetCount() { s_Placed = 0; }

    /// <summary>
    /// Instantiate a kit prefab, keeping the prefab link, and strip ProBuilder.
    ///
    /// <para>The strip is not cosmetic: a live ProBuilderMesh rebuilds a private copy
    /// of its geometry per instance, which embeds hundreds of meshes into the scene
    /// file and forces it to serialize as BINARY even under ForceText. Learned in
    /// <see cref="MapSeeder"/>; repeated here because it applies to any map.</para>
    /// </summary>
    public static GameObject Place(string pieceName, Transform parent, Vector3 position,
                                   float yaw = 0f)
    {
        var piece = System.Array.Find(MapKitBuilder.Pieces, p => p.Name == pieceName);
        var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(piece.PrefabPath);
        if (prefab == null)
        {
            Debug.LogError($"[MapPlan] kit piece missing: {pieceName}");
            return null;
        }
        return PlacePrefab(prefab, parent, position, yaw);
    }

    public static GameObject PlacePrefab(GameObject prefab, Transform parent, Vector3 position,
                                         float yaw = 0f)
    {
        s_Placed++;
        var go = (GameObject)PrefabUtility.InstantiatePrefab(prefab, parent);
        go.transform.position = position;
        go.transform.rotation = Quaternion.Euler(0f, yaw, 0f);
        StripProBuilder(prefab, go);
        return go;
    }

    static void StripProBuilder(GameObject prefab, GameObject instance)
    {
        foreach (var pb in instance.GetComponentsInChildren<UnityEngine.ProBuilder.ProBuilderMesh>(true))
            Object.DestroyImmediate(pb, true);

        var src = prefab.GetComponentsInChildren<MeshFilter>(true);
        var dst = instance.GetComponentsInChildren<MeshFilter>(true);
        if (src.Length != dst.Length) return;
        for (int i = 0; i < dst.Length; i++)
        {
            dst[i].sharedMesh = src[i].sharedMesh;
            var col = dst[i].GetComponent<MeshCollider>();
            if (col != null) col.sharedMesh = src[i].sharedMesh;
        }
    }

    /// <summary>Where a wall sits, and which way it faces, for one cell edge.</summary>
    public static void EdgeTransform(int cx, int cz, Edge e, int storey,
                                     out Vector3 position, out float yaw)
    {
        float half = Cell * 0.5f;
        Vector3 c = Centre(cx, cz, storey);
        switch (e)
        {
            case Edge.North: position = c + new Vector3(0f, 0f, half); yaw = 0f; break;
            case Edge.South: position = c - new Vector3(0f, 0f, half); yaw = 0f; break;
            case Edge.East: position = c + new Vector3(half, 0f, 0f); yaw = 90f; break;
            default: position = c - new Vector3(half, 0f, 0f); yaw = 90f; break;
        }
    }

    /// <summary>
    /// Floors, ceilings and every derived wall for one storey.
    /// Returns the number of pieces placed.
    /// </summary>
    public static int BuildStorey(Storey s, Transform parent)
    {
        int before = s_Placed;
        float roofY = StoreyHeight - 0.5f;      // MapKitBuilder.Thickness

        var floors = new GameObject($"Floors_L{s.index}").transform;
        floors.SetParent(parent, false);
        foreach (var c in s.Floor.OrderBy(c => c.x).ThenBy(c => c.y))
        {
            if (s.Reserved.Contains(c)) continue;
            Place("Kit_Floor_4x4", floors, Centre(c.x, c.y, s.index));
        }

        // BACKING for module cells. An imported module does not fill its cell — a
        // 2 m corridor inside a 4 m cell leaves the rest with no floor and no
        // ceiling — and skipping those cells entirely left holes you could see the
        // void through, standing in the map. A floor 2 cm below the walkable plane
        // and a ceiling above the module's own fill the gaps without z-fighting the
        // geometry that IS there, and 2 cm is far under the navmesh voxel.
        var backing = new GameObject($"ModuleBacking_L{s.index}").transform;
        backing.SetParent(parent, false);
        foreach (var c in s.Reserved.OrderBy(c => c.x).ThenBy(c => c.y))
        {
            Place("Kit_Floor_4x4", backing, Centre(c.x, c.y, s.index) - Vector3.up * 0.02f);
            Place("Kit_Ceiling_4x4", backing, Centre(c.x, c.y, s.index) + Vector3.up * roofY);
        }

        var roofs = new GameObject($"Ceilings_L{s.index}").transform;
        roofs.SetParent(parent, false);
        foreach (var c in s.Roof.OrderBy(c => c.x).ThenBy(c => c.y))
        {
            if (s.Reserved.Contains(c)) continue;
            Place("Kit_Ceiling_4x4", roofs, Centre(c.x, c.y, s.index) + Vector3.up * roofY);
        }

        var walls = new GameObject($"Walls_L{s.index}").transform;
        walls.SetParent(parent, false);
        var done = new HashSet<(Vector2Int, Edge)>();

        foreach (var c in s.Floor.OrderBy(c => c.x).ThenBy(c => c.y))
        {
            foreach (Edge e in new[] { Edge.North, Edge.East, Edge.South, Edge.West })
            {
                var key = (c, e);
                if (s.Open.Contains(key)) continue;

                var step = Step(e);
                var n = new Vector2Int(c.x + step.x, c.y + step.y);

                string piece;
                if (s.Forced.TryGetValue(key, out piece))
                {
                    // an explicit doorway or window, even between two open cells
                }
                else if (s.Forced.ContainsKey((n, Opposite(e))))
                {
                    continue;               // the neighbour already asked for this edge
                }
                else if (s.Walkable(n))
                {
                    continue;               // open to the next room
                }
                else
                {
                    // Reserved cells DO get an exterior wall, and this used to skip
                    // them on the reasoning that a module brings its own. It does —
                    // but its walls sit INSIDE its cell and several of them are
                    // single-sided planes, so at the edge of the map you could see
                    // straight out into the void past them. A kit wall on the cell
                    // boundary sits outside the module's own and seals the level;
                    // where the module already had a wall, nobody ever sees this one.
                    piece = "Kit_Wall_4";
                }

                // One wall per boundary. The neighbour would ask for the same edge
                // from its own side, and two coincident walls z-fight and double the
                // collider.
                var canonical = Canonical(c, e);
                if (!done.Add(canonical)) continue;

                EdgeTransform(c.x, c.y, e, s.index, out var pos, out var yaw);
                Place(piece, walls, pos, yaw);
            }
        }
        return s_Placed - before;
    }

    public static Edge Opposite(Edge e)
    {
        switch (e)
        {
            case Edge.North: return Edge.South;
            case Edge.South: return Edge.North;
            case Edge.East: return Edge.West;
            default: return Edge.East;
        }
    }

    /// <summary>Both sides of a boundary name the same key, so it is only built once.</summary>
    static (Vector2Int, Edge) Canonical(Vector2Int c, Edge e)
    {
        if (e == Edge.South) return (new Vector2Int(c.x, c.y - 1), Edge.North);
        if (e == Edge.West) return (new Vector2Int(c.x - 1, c.y), Edge.East);
        return (c, e);
    }

    // ---- verification -----------------------------------------------------

    /// <summary>
    /// The plan as text, north at the top. Printed to the log on every build.
    ///
    /// <para>A layout typed as rectangles is unreadable as rectangles. This is the
    /// only cheap way to see whether the map you described is the map you meant,
    /// and it costs nothing to look at.</para>
    /// </summary>
    public static string Ascii(Storey s, Dictionary<Vector2Int, char> marks = null)
    {
        if (s.Floor.Count == 0) return "(empty)";
        int x0 = s.Floor.Min(c => c.x) - 1, x1 = s.Floor.Max(c => c.x) + 1;
        int z0 = s.Floor.Min(c => c.y) - 1, z1 = s.Floor.Max(c => c.y) + 1;

        var sb = new StringBuilder();
        for (int z = z1; z >= z0; z--)
        {
            sb.Append($"{z,3} ");
            for (int x = x0; x <= x1; x++)
            {
                var c = new Vector2Int(x, z);
                char ch;
                if (marks != null && marks.TryGetValue(c, out ch)) { sb.Append(ch); continue; }
                if (s.Reserved.Contains(c)) sb.Append('M');
                else if (!s.Floor.Contains(c)) sb.Append('#');
                else if (!s.Roof.Contains(c)) sb.Append('~');    // open to sky
                else sb.Append('.');
            }
            sb.Append('\n');
        }
        sb.Append("    ");
        for (int x = x0; x <= x1; x++) sb.Append(Mathf.Abs(x % 10));
        return sb.ToString();
    }
}
