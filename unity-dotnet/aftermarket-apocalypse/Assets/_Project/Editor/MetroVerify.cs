using System.Collections.Generic;
using System.Linq;
using System.Text;
using UnityEditor;
using UnityEngine;
using UnityEngine.AI;
using UnityEngine.SceneManagement;
using Edge = MapPlan.Edge;

/// <summary>
/// Does this map work as a round-based map? Answered by pathfinding, not by reading
/// the plan and believing it.
///
/// <para>Two questions matter and neither is visible in a screenshot. <b>Can a
/// zombie reach everything</b> — a machine in a pocket the navmesh never joined is
/// invisible until someone walks there. And <b>is there a LOOP</b> — the entire
/// game past round 10 is walking a horde in a circle, so a map that is a tree of
/// dead ends is not a hard map, it is an unplayable one. A loop is exactly two
/// internally vertex-disjoint routes between the spawn and the far side, which is a
/// max-flow question, so it is asked as one.</para>
/// </summary>
public static class MetroVerify
{
    /// <summary>Cells the loop must run between: the spawn hall and the north hall.</summary>
    static readonly Vector2Int k_LoopFrom = new Vector2Int(8, 2);
    static readonly Vector2Int k_LoopTo = new Vector2Int(8, 11);

    public class Report
    {
        public int gameplayObjects;
        public int unreachable;
        public readonly List<string> problems = new List<string>();
        public int disjointRoutes;
        public int spawnPoints;
        public int spawnsThatCannotReachThePlayer;
        public float navMeshArea;
        public bool Ok => problems.Count == 0;
    }

    [MenuItem("Tools/Aftermarket/Verify New Map (Ashfall Metro)")]
    public static void Run()
    {
        if (!MapBake.OpenSceneIfNeeded("MetroVerify", MetroSeeder.ScenePath)) return;
        var report = Verify(SceneManager.GetActiveScene());
        var sb = new StringBuilder("[MetroVerify]\n");
        sb.AppendLine($"  navmesh                 {report.navMeshArea:0} m2 of walkable surface");
        sb.AppendLine($"  gameplay objects        {report.gameplayObjects}, " +
                      $"{report.unreachable} unreachable from the player start");
        sb.AppendLine($"  enemy spawns            {report.spawnPoints}, " +
                      $"{report.spawnsThatCannotReachThePlayer} cannot path to the player");
        sb.AppendLine($"  disjoint loop routes    {report.disjointRoutes} " +
                      "(2 or more means the horde can be walked in a circle)");
        foreach (var p in report.problems) sb.AppendLine("  ! " + p);
        if (report.Ok) sb.AppendLine("  no problems");
        Debug.Log(sb.ToString());
    }

    public static Report Verify(Scene scene)
    {
        var r = new Report();

        var tri = NavMesh.CalculateTriangulation();
        r.navMeshArea = TriangulationArea(tri);
        if (r.navMeshArea <= 0f) r.problems.Add("there is no navmesh in this scene at all");

        var player = GameObject.Find("Player");
        if (player == null)
        {
            r.problems.Add("no 'Player' root");
            return r;
        }
        if (!NavMesh.SamplePosition(player.transform.position, out var start, 3f, NavMesh.AllAreas))
        {
            r.problems.Add($"the player start {player.transform.position} is not on the navmesh");
            return r;
        }

        // --- everything a player has to be able to walk to ---------------------
        var targets = new List<(string name, Vector3 at)>();
        foreach (var root in scene.GetRootGameObjects())
            foreach (var mb in root.GetComponentsInChildren<MonoBehaviour>(true))
            {
                if (mb is IInteractable) targets.Add((mb.name, mb.transform.position));
            }

        r.gameplayObjects = targets.Count;
        foreach (var (name, at) in targets)
        {
            if (!ReachableNear(start.position, at))
            {
                r.unreachable++;
                r.problems.Add($"{name} at {at} cannot be walked to from the player start");
            }
        }

        // --- spawns that cannot reach the player are rounds that never end -----
        var spawnRoot = GameObject.Find("EnemySpawnPoints");
        if (spawnRoot == null) r.problems.Add("no 'EnemySpawnPoints' root — nothing can spawn");
        else
            foreach (Transform t in spawnRoot.transform)
            {
                r.spawnPoints++;
                var path = new NavMeshPath();
                NavMesh.CalculatePath(t.position, start.position, NavMesh.AllAreas, path);
                if (path.status != NavMeshPathStatus.PathComplete)
                {
                    r.spawnsThatCannotReachThePlayer++;
                    r.problems.Add($"{t.name} cannot path to the player — that round never ends");
                }
            }

        // --- the loop -----------------------------------------------------------
        r.disjointRoutes = DisjointRoutes(MetroSeeder.BuildPlan(), k_LoopFrom, k_LoopTo);
        if (r.disjointRoutes < 2)
            r.problems.Add($"only {r.disjointRoutes} route(s) between the spawn hall and the north " +
                           "hall, so the map is a dead end rather than a loop — there is nowhere " +
                           "to walk a horde past round 10");
        return r;
    }


    /// <summary>
    /// Can a player stand somewhere NEXT TO this thing and reach it?
    ///
    /// <para>Not the same question as "is its own position on the navmesh", and the
    /// difference reported ten false failures — including Quick Revive standing in
    /// the middle of the spawn room. A machine is a solid box, the bake runs over
    /// the top of it, and the navmesh nearest to a machine's centre is therefore its
    /// own ROOF: a 1 m island connected to nothing. Sampling a ring at floor height
    /// asks about the floor beside it, which is where a player actually stands.</para>
    /// </summary>
    static bool ReachableNear(Vector3 start, Vector3 at, float maxRing = 2.5f)
    {
        var path = new NavMeshPath();
        foreach (float down in new[] { 0f, 1f, 2f })
            foreach (float ring in new[] { 1.0f, 1.75f, maxRing })
                for (int i = 0; i < 8; i++)
                {
                    float a = i * Mathf.PI * 0.25f;
                    var probe = at + new Vector3(Mathf.Cos(a) * ring, -down, Mathf.Sin(a) * ring);
                    if (!NavMesh.SamplePosition(probe, out var hit, 1.0f, NavMesh.AllAreas)) continue;
                    NavMesh.CalculatePath(start, hit.position, NavMesh.AllAreas, path);
                    if (path.status == NavMeshPathStatus.PathComplete) return true;
                }
        return false;
    }

    static float TriangulationArea(NavMeshTriangulation tri)
    {
        float area = 0f;
        for (int i = 0; i + 2 < tri.indices.Length; i += 3)
        {
            Vector3 a = tri.vertices[tri.indices[i]];
            Vector3 b = tri.vertices[tri.indices[i + 1]];
            Vector3 c = tri.vertices[tri.indices[i + 2]];
            area += Vector3.Cross(b - a, c - a).magnitude * 0.5f;
        }
        return area;
    }

    // ---- how many separate ways round ------------------------------------

    /// <summary>
    /// Internally vertex-disjoint routes between two cells, i.e. how many ways round
    /// the map there are that do not share a single room.
    ///
    /// <para>Max-flow with every cell split into in/out at capacity 1, so a route
    /// consumes the cells it uses. Menger's theorem makes the answer the number of
    /// independent routes, which is the thing a zombies map lives or dies on.</para>
    ///
    /// <para>A boarded window is NOT an edge: the player cannot climb a sill, and a
    /// loop that only exists for zombies is not a loop you can run.</para>
    /// </summary>
    public static int DisjointRoutes(MapPlan.Storey plan, Vector2Int from, Vector2Int to)
    {
        if (!plan.Floor.Contains(from) || !plan.Floor.Contains(to)) return 0;

        var cells = plan.Floor.ToList();
        var index = new Dictionary<Vector2Int, int>();
        for (int i = 0; i < cells.Count; i++) index[cells[i]] = i;

        int n = cells.Count;
        int nodes = n * 2 + 2;              // in/out per cell
        int source = index[from] * 2 + 1;   // out-node of the start
        int sink = index[to] * 2;           // in-node of the end

        var cap = new Dictionary<(int, int), int>();
        void AddEdge(int a, int b, int c)
        {
            cap.TryGetValue((a, b), out int existing);
            cap[(a, b)] = existing + c;
            if (!cap.ContainsKey((b, a))) cap[(b, a)] = 0;
        }

        for (int i = 0; i < n; i++)
        {
            // the start and the end are not consumed by their own routes
            int through = (i * 2 + 1 == source || i * 2 == sink) ? 1000 : 1;
            AddEdge(i * 2, i * 2 + 1, through);
        }

        foreach (var c in cells)
            foreach (Edge e in new[] { Edge.North, Edge.East, Edge.South, Edge.West })
            {
                var step = MapPlan.Step(e);
                var nb = new Vector2Int(c.x + step.x, c.y + step.y);
                if (!index.ContainsKey(nb)) continue;
                if (!Passable(plan, c, nb, e)) continue;
                AddEdge(index[c] * 2 + 1, index[nb] * 2, 1);
            }

        // Edmonds-Karp. The graph is a hundred cells; clarity beats cleverness.
        int flow = 0;
        while (true)
        {
            var prev = new int[nodes];
            for (int i = 0; i < nodes; i++) prev[i] = -1;
            prev[source] = source;

            var queue = new Queue<int>();
            queue.Enqueue(source);
            while (queue.Count > 0 && prev[sink] == -1)
            {
                int u = queue.Dequeue();
                for (int v = 0; v < nodes; v++)
                {
                    if (prev[v] != -1) continue;
                    if (!cap.TryGetValue((u, v), out int c) || c <= 0) continue;
                    prev[v] = u;
                    queue.Enqueue(v);
                }
            }
            if (prev[sink] == -1) break;

            int push = int.MaxValue;
            for (int v = sink; v != source; v = prev[v])
                push = Mathf.Min(push, cap[(prev[v], v)]);
            for (int v = sink; v != source; v = prev[v])
            {
                cap[(prev[v], v)] -= push;
                cap[(v, prev[v])] += push;
            }
            flow += push;
            if (flow > 50) break;
        }
        return flow;
    }

    /// <summary>Can a PLAYER walk between two adjacent cells? A solid wall says no;
    /// a doorway says yes, because a door is bought and then gone.</summary>
    static bool Passable(MapPlan.Storey plan, Vector2Int a, Vector2Int b, Edge e)
    {
        string piece;
        if (plan.Forced.TryGetValue((a, e), out piece) ||
            plan.Forced.TryGetValue((b, MapPlan.Opposite(e)), out piece))
            return piece == "Kit_Wall_4_Doorway";
        return true;
    }
}
