using System.Collections.Generic;
using Unity.AI.Navigation;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.AI;
using UnityEngine.SceneManagement;

/// <summary>
/// The two things every tool that puts solid geometry into the map must do, in
/// one place instead of copied per builder.
///
/// <para><b>This exists because Phases 6 and 7 did neither.</b> They dropped
/// thirteen solid boxes with BoxColliders — two Mystery Boxes, five perk
/// machines, Pack-a-Punch, an Ammo Crate, a Gobblegum machine and three wallbuys
/// — into the shared map without rebaking the navmesh and without checking spawn
/// clearance. <see cref="PropDresser"/> already had both checks and they were
/// private to it, so the second and third tools to need them simply went
/// without.</para>
///
/// <para><b>Phases 4 and 5 are the deliberate exceptions and stay that way.</b>
/// Door debris must NOT be baked (§14 Law 12 — it lives on the excluded
/// <c>DoorDebris</c> layer and blocks with a carving obstacle instead), and
/// window barriers block with a runtime <see cref="NavMeshLink"/> rather than
/// with geometry. Both of those builders say so in their own doc comments. An
/// ordinary solid machine has no such reason, which is why its absence there was
/// an omission rather than a decision.</para>
/// </summary>
public static class MapBake
{
    public const string NavMeshAsset = "Assets/_Project/Scenes/AshfallDepot_NavMesh.asset";

    /// <summary>
    /// §14 Law 8 wants ~2 m of walkable clearance at a spawn point. Anything
    /// solid dropped inside that turns a valid spawn into an agent stuck inside a
    /// box. 2.5 m is Law 8's floor plus half a metre for the object's own width.
    ///
    /// <para><b>Spawn points are DERIVED by <see cref="MapSeeder"/>, not typed</b>,
    /// which is exactly why this check reads the live scene: their coordinates are
    /// unknowable when writing a placement table by hand, so no amount of care
    /// over the literals can substitute for it.</para>
    /// </summary>
    public const float SpawnKeepOut = 2.5f;

    /// <summary>
    /// Opens the map, or keeps it if it is already the open scene. Never calls a
    /// bare <c>OpenScene</c>: from a script that does NOT prompt — it discards
    /// unsaved changes and clears the undo stack.
    /// </summary>
    public static bool OpenMapIfNeeded(string tag)
    {
        return OpenSceneIfNeeded(tag, MapSeeder.ScenePath);
    }

    /// <summary>
    /// The same thing for ANY map. Everything below this line is about a scene, not
    /// about AshfallDepot in particular, and the project now has more than one map.
    /// </summary>
    public static bool OpenSceneIfNeeded(string tag, string scenePath)
    {
        if (SceneManager.GetActiveScene().path == scenePath) return true;

        if (!Application.isBatchMode &&
            !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
        {
            Debug.LogWarning($"[{tag}] cancelled — the open scene has unsaved changes.");
            return false;
        }
        EditorSceneManager.OpenScene(scenePath, OpenSceneMode.Single);
        return true;
    }

    /// <summary>Spawn points and the player start, read from the scene.</summary>
    public static List<Vector3> KeepOutPoints()
    {
        var points = new List<Vector3>();

        var spawnRoot = GameObject.Find("EnemySpawnPoints");
        if (spawnRoot != null)
            foreach (Transform t in spawnRoot.transform) points.Add(t.position);

        var player = GameObject.Find("Player");
        if (player != null) points.Add(player.transform.position);

        return points;
    }

    /// <summary>
    /// True when <paramref name="position"/> is clear of every spawn point.
    /// Compared on the XZ plane only — a machine one storey above a spawn is not
    /// standing on it.
    /// </summary>
    public static bool IsClearOfSpawns(Vector3 position, List<Vector3> keepOut, out float nearest)
    {
        nearest = float.PositiveInfinity;
        foreach (var k in keepOut)
        {
            float d = Vector2.Distance(new Vector2(k.x, k.z), new Vector2(position.x, position.z));
            if (d < nearest) nearest = d;
        }
        return nearest >= SpawnKeepOut;
    }

    /// <summary>
    /// Nudges a solid machine off a spawn point, onto the nearest spot that is
    /// clear, on the navmesh, and not inside existing geometry. Returns false when
    /// there is nowhere within <paramref name="searchRadius"/> — a caller that
    /// gets false should refuse to place rather than place badly.
    ///
    /// <para><b>Why a search and not a corrected literal.</b> Spawn points are
    /// derived from the baked navmesh, so a hand-typed machine coordinate cannot
    /// be checked by reading it; <c>MysteryBox_Warehouse</c> was typed at (12, 34)
    /// and landed 2.00 m from a spawn that did not exist when the number was
    /// chosen. Re-typing it fixes today's scene and re-arms the trap for the next
    /// re-seed, which moves every spawn.</para>
    ///
    /// <para>The navmesh test is what keeps the nudge honest — it is the only
    /// cheap way to tell "two metres left, still on the warehouse floor" from "two
    /// metres left, inside the wall".</para>
    /// </summary>
    public static bool FindClearSpot(Vector3 desired, Vector3 footprint, List<Vector3> keepOut,
                                     out Vector3 result, float searchRadius = 6f)
    {
        return FindClearSpot(desired, footprint, keepOut, out result, out _, searchRadius);
    }

    /// <summary>
    /// As above, and reports WHY the desired point was rejected. The callers log
    /// this: "moved 3 m to clear a spawn point" was printed for a machine that had
    /// actually been sitting inside a wall, which is a different problem with a
    /// different fix.
    /// </summary>
    public static bool FindClearSpot(Vector3 desired, Vector3 footprint, List<Vector3> keepOut,
                                     out Vector3 result, out string reason,
                                     float searchRadius = 6f)
    {
        Physics.SyncTransforms();
        result = desired;

        // VALIDATE THE POINT THAT IS ACTUALLY USED.
        //
        // This used to be `if (IsClearOfSpawns(desired)) return true;`, so the
        // navmesh and occupancy tests this method's own doc calls load-bearing ran
        // ONLY on ring candidates -- that is, only in the one case where the typed
        // coordinate had already failed the spawn check. Of sixteen placements
        // exactly one was ever nudged; the other fifteen kept a hand-typed literal
        // that nothing had checked was on the floor. For the three relocation
        // markers that is worse than cosmetic: MysteryBox.Relocate teleports with a
        // bare `transform.position = destination.position` and no further test, so
        // an unvalidated marker is a box inside a wall.
        if (IsUsable(desired, footprint, keepOut, out reason)) return true;

        // Rings outward, so the first acceptable answer is also the smallest move.
        for (float r = 0.5f; r <= searchRadius; r += 0.5f)
        {
            for (int i = 0; i < 24; i++)
            {
                float a = i * Mathf.PI * 2f / 24f;
                var candidate = desired + new Vector3(Mathf.Cos(a) * r, 0f, Mathf.Sin(a) * r);
                if (!IsUsable(candidate, footprint, keepOut, out _)) continue;

                result = candidate;
                return true;
            }
        }
        return false;
    }

    /// <summary>
    /// Clear of spawns, standing on the navmesh, and not inside anything.
    ///
    /// <para>The navmesh sample probes from GROUND LEVEL, not from the caller's
    /// y. Machines and box markers are authored at y = 0.6 while the yard navmesh
    /// is at y = 0.04, so sampling at the authored height with the old 0.6 m reach
    /// left four centimetres of margin — a marginally different bake and every
    /// lookup would have started returning false. Probing from y = 0 with a 1.5 m
    /// reach covers the ground floor with room to spare and still excludes the
    /// 2 m loading dock and the 4 m roofs.</para>
    ///
    /// <para>The overlap box uses the object's TRUE half-extents. A shrunk 80%
    /// box was tried first, on the theory that machines stand flush against walls
    /// and an exact test would reject them for touching. Measured: with the exact
    /// footprint the same three objects move and no others, so nothing in this map
    /// is actually authored flush and the leniency bought only a thinner margin on
    /// a Mystery Box teleport destination.</para>
    /// </summary>
    static bool IsUsable(Vector3 position, Vector3 footprint, List<Vector3> keepOut,
                         out string reason)
    {
        if (!IsClearOfSpawns(position, keepOut, out float nearest))
        {
            reason = $"{nearest:F2} m from a spawn point or the player start " +
                     $"(keep-out is {SpawnKeepOut} m)";
            return false;
        }

        var probe = new Vector3(position.x, 0f, position.z);
        if (!NavMesh.SamplePosition(probe, out var hit, 1.5f, NavMesh.AllAreas))
        {
            reason = "no walkable navmesh under it at ground level";
            return false;
        }
        if (hit.position.y > 0.5f)
        {
            reason = $"the only navmesh under it is at y = {hit.position.y:F2}, " +
                     "which is a raised surface, not the ground floor";
            return false;
        }

        var centre = new Vector3(position.x, hit.position.y + footprint.y * 0.5f, position.z);
        foreach (var c in Physics.OverlapBox(centre, footprint * 0.5f, Quaternion.identity,
                                             ~0, QueryTriggerInteraction.Ignore))
        {
            // Its own not-yet-created body cannot be here, so anything solid is
            // somebody else's.
            if (c.GetComponentInParent<CharacterController>() != null)
            {
                reason = "the player is standing there";
                return false;
            }
            for (var t = c.transform; t != null; t = t.parent)
            {
                if (!t.name.StartsWith("Kit_")) continue;
                reason = $"it is inside map structure ({t.name})";
                return false;
            }
            if (c.GetComponentInParent<IInteractable>() != null)
            {
                reason = $"another machine is there ({c.name})";
                return false;
            }
        }

        reason = null;
        return true;
    }

    /// <summary>
    /// Seats a flat wall decal against the wall behind it, deriving BOTH its
    /// position and its facing from the surface it finds.
    ///
    /// <para><b>Because a hand-typed position and a hand-typed yaw are two sources
    /// of truth that drift apart.</b> <c>WallBuy_Yard_Pistol</c> was written at
    /// (24, 1.5, 1.8) facing +Z; the nearest wall in zone A is at z = 4, which is
    /// BEHIND the panel's back and 1.9 m away — so the chalk hung in open air at
    /// 1.5 m with nothing to be painted on. The other two wallbuys were seated
    /// correctly, which is the signature of a rule applied by hand.</para>
    ///
    /// <para>Searches both ways along the given facing, so a yaw that is merely
    /// backwards is corrected rather than fatal, and reports what it did.</para>
    /// </summary>
    public static bool SeatOnWall(Vector3 desired, float yaw, float halfThickness,
                                  out Vector3 position, out Quaternion rotation,
                                  float maxSearch = 3f)
    {
        Physics.SyncTransforms();

        var forward = Quaternion.Euler(0f, yaw, 0f) * Vector3.forward;
        position = desired;
        rotation = Quaternion.Euler(0f, yaw, 0f);

        RaycastHit best = default;
        bool found = false;
        foreach (var dir in new[] { -forward, forward })
        {
            // RaycastAll and filter, NOT Raycast-and-hope.
            //
            // The first version took the nearest hit against ~0 and seated
            // WallBuy_Yard_Pistol 0.28 m from the player start, because what it
            // found at 1.9 m was the PLAYER's own CharacterController — a collider
            // like any other, on the Default layer like the walls. It reported
            // success and produced a chalk panel stuck to the player instead of a
            // panel stuck to nothing, which is not an improvement.
            var hits = Physics.RaycastAll(desired, dir, maxSearch, ~0,
                                          QueryTriggerInteraction.Ignore);
            foreach (var hit in hits)
            {
                if (!IsStructure(hit.collider)) continue;
                if (found && hit.distance >= best.distance) continue;
                best = hit;
                found = true;
            }
        }

        if (!found) return false;

        // Sit just proud of the surface, facing out along its normal.
        position = best.point + best.normal * halfThickness;
        rotation = Quaternion.LookRotation(best.normal, Vector3.up);
        return true;
    }

    /// <summary>
    /// True for map structure you can paint on: a kit piece, and not the player,
    /// a prop, or another machine. A wall decal seated against any of those is
    /// still a decal seated against nothing that will stay put.
    /// </summary>
    static bool IsStructure(Collider collider)
    {
        if (collider.GetComponentInParent<CharacterController>() != null) return false;
        if (collider.GetComponentInParent<IInteractable>() != null) return false;

        for (var t = collider.transform; t != null; t = t.parent)
            if (t.name.StartsWith("Kit_")) return true;

        return false;
    }

    /// <summary>
    /// Moves the Player to a start position that is on the navmesh, clear of
    /// structure, and clear of enemy spawns.
    ///
    /// <para><b>This exists because the player spawned inside a wall and nothing
    /// noticed for seven phases.</b> <see cref="MapSeeder"/> typed the start at
    /// cell (6, 1) and <c>BuildCover</c> independently placed a
    /// <c>Kit_Wall_4_Half</c> across that same cell — (22, 0, 3.75) to
    /// (26, 2, 4.25) — with the start dead centre. The <c>y</c> was wrong too:
    /// <c>CharacterController.center</c> is (0, 0.9, 0) over a 1.8 m height, so
    /// <c>transform.position.y</c> is the FEET, and 1.2 floated the player over a
    /// floor at 0. Every test passed, the map screenshots looked fine, and the
    /// game was unplayable from frame one.</para>
    ///
    /// <para>The seeder is one-shot, so fixing its literal does not fix the
    /// scene that already exists. This does, and re-running it is harmless.</para>
    /// </summary>
    [MenuItem("Tools/Aftermarket/Fix Player Start")]
    public static void FixPlayerStart()
    {
        if (!OpenMapIfNeeded("MapBake")) return;

        var player = GameObject.Find("Player");
        if (player == null) { Debug.LogError("[MapBake] no 'Player' root."); return; }

        var cc = player.GetComponentInChildren<CharacterController>();
        if (cc == null) { Debug.LogError("[MapBake] the Player has no CharacterController."); return; }

        // Enemy spawns only — the player start is itself in KeepOutPoints(), and
        // keeping away from where it already is makes no sense.
        var spawns = new List<Vector3>();
        var spawnRoot = GameObject.Find("EnemySpawnPoints");
        if (spawnRoot != null)
            foreach (Transform t in spawnRoot.transform) spawns.Add(t.position);

        Physics.SyncTransforms();
        var was = player.transform.position;

        // SEARCH FROM THE DESIGNED START, NOT FROM WHERE THE PLAYER CURRENTLY IS.
        //
        // Two separate bugs came from doing it the other way. The search offset
        // only x and z, so `probe` kept the BROKEN start's y of 1.2 — and
        // SamplePosition's 1.0 m reach from there could not touch the yard navmesh
        // at y = 0.04, 1.16 m below. So every ring over flat floor failed and the
        // first spot that could succeed was raised geometry: it landed at r = 10.5
        // on the loading-dock ramp, 10.5 m from the design position. Then, because
        // the next run started from THAT position, the ramp sampled fine and the
        // tool reported "already clear" — a wrong answer that made itself
        // permanent. Anchoring to MapSeeder.PlayerStart fixes both: the probe y is
        // floor level, and the repair is idempotent.
        var from = MapSeeder.PlayerStart;

        for (float r = 0f; r <= 12f; r += 0.5f)
        {
            int steps = r == 0f ? 1 : 24;
            for (int i = 0; i < steps; i++)
            {
                float a = i * Mathf.PI * 2f / Mathf.Max(1, steps);
                var probe = from + new Vector3(Mathf.Cos(a) * r, 0f, Mathf.Sin(a) * r);

                if (!NavMesh.SamplePosition(probe, out var hit, 1.0f, NavMesh.AllAreas)) continue;

                // Ground level only. Without this the search happily answers with
                // the navmesh baked onto the flat top of a crate — an early run put
                // the player 9 m away standing at y = 0.87 on a prop. Same rule
                // MapSeeder already applies to derived spawn candidates. Note this
                // guard alone was NOT enough: it only lowered the ceiling, and the
                // ramp answer above came in at 0.237.
                if (hit.position.y > 0.5f) continue;

                if (!IsClearOfSpawns(hit.position, spawns, out _)) continue;

                // Feet on the surface, then test the capsule the player will
                // actually occupy — the whole point is that a point test would
                // have passed at the old position too.
                var feet = new Vector3(hit.position.x, hit.position.y + 0.05f, hit.position.z);
                var centre = feet + cc.center;
                var up = Vector3.up * Mathf.Max(0f, cc.height * 0.5f - cc.radius);
                bool blocked = false;
                foreach (var c in Physics.OverlapCapsule(centre + up, centre - up, cc.radius,
                                                         ~0, QueryTriggerInteraction.Ignore))
                    if (IsStructure(c)) { blocked = true; break; }
                if (blocked) continue;

                if (Vector3.Distance(feet, was) < 0.001f)
                {
                    Debug.Log($"[MapBake] the player start at {was} is already the best " +
                              $"spot near the designed start {from}.");
                    return;
                }

                player.transform.position = feet;
                EditorSceneManager.MarkSceneDirty(player.scene);
                EditorSceneManager.SaveScene(player.scene);
                Debug.Log($"[MapBake] moved the player start from {was} to {feet} — " +
                          $"{Vector3.Distance(from, feet):F2} m from the designed start " +
                          $"{from}, on navmesh at y = {hit.position.y:F3}.");
                return;
            }
        }
        Debug.LogError($"[MapBake] no clear player start within 12 m of the designed " +
                       $"start {from}. The Player was left at {was}.");
    }

    /// <summary>
    /// Prints every spawn point and every interactable's distance to the nearest
    /// one. Read-only. This is how a placement coordinate gets chosen at all:
    /// spawn points are derived, so "is (12, 34) far enough from a spawn" has no
    /// answer until the scene is asked.
    /// </summary>
    [MenuItem("Tools/Aftermarket/Audit Spawn Clearance")]
    public static void AuditClearance()
    {
        if (!OpenMapIfNeeded("MapBake")) return;

        var keepOut = KeepOutPoints();
        var lines = new List<string> { $"{keepOut.Count} keep-out points:" };
        foreach (var k in keepOut) lines.Add($"  {k}");

        // What structure is near the player start, and is the player inside it?
        var playerGo = GameObject.Find("Player");
        if (playerGo != null)
        {
            var p = playerGo.transform.position;
            lines.Add($"Structure within 3 m of the player start {p}:");
            foreach (var c in Physics.OverlapSphere(p, 3f, ~0, QueryTriggerInteraction.Ignore))
            {
                if (!IsStructure(c)) continue;
                lines.Add($"  {c.transform.root.name}/{c.name} bounds {c.bounds.min} .. {c.bounds.max}");
            }
            var cc = playerGo.GetComponentInChildren<CharacterController>();
            if (cc != null)
            {
                var centre = p + cc.center;
                bool inside = false;
                foreach (var c in Physics.OverlapCapsule(
                             centre + Vector3.up * (cc.height * 0.5f - cc.radius),
                             centre - Vector3.up * (cc.height * 0.5f - cc.radius),
                             cc.radius, ~0, QueryTriggerInteraction.Ignore))
                {
                    // EVERYTHING solid, not just Kit_ structure. A barrel is as
                    // solid as a wall to a CharacterController, and the first
                    // version of this audit could not have seen one.
                    if (c.GetComponentInParent<CharacterController>() != null) continue;
                    inside = true;
                    lines.Add($"  PLAYER CAPSULE OVERLAPS {c.transform.root.name}/{c.name}");
                }
                if (!inside) lines.Add("  player capsule is clear of everything solid");
            }
        }

        lines.Add($"Interactables vs the {SpawnKeepOut} m keep-out:");
        foreach (var mb in Object.FindObjectsByType<MonoBehaviour>(FindObjectsSortMode.None))
        {
            if (!(mb is IInteractable)) continue;
            bool clear = IsClearOfSpawns(mb.transform.position, keepOut, out float nearest);
            lines.Add($"  {(clear ? "ok  " : "FAIL")} {nearest,6:F2} m  {mb.name} " +
                      $"{mb.transform.position}");
        }
        Debug.Log(string.Join("\n", lines));
    }

    /// <summary>
    /// Rebake, because solid objects the navmesh does not know about are worse
    /// than no objects: agents walk straight through them, so the cover is a lie
    /// and the machine you are hiding behind stops nothing.
    /// </summary>
    public static bool Rebake(string tag)
    {
        var surface = Object.FindFirstObjectByType<NavMeshSurface>();
        if (surface == null)
        {
            Debug.LogError($"[{tag}] no NavMeshSurface in the scene; new geometry will not block.");
            return false;
        }

        surface.BuildNavMesh();

        // WRITE THE DATA BACK INTO THE EXISTING ASSET, BEFORE THE SCENE IS SAVED.
        //
        // BuildNavMesh() does not update the .asset — it hands back a brand new
        // in-memory NavMeshData and drops the reference to the old one. Save the
        // scene in that state and the data is embedded inline, which silently
        // forces the whole scene to BINARY regardless of EditorSettings.ForceText,
        // and ForceReserializeAssets will not undo it. That is the exact failure
        // Map_SceneIsTextYamlSoHandEditsCanBeDiffed exists to catch, and it caught
        // this on the first run of PropDresser.
        if (surface.navMeshData == null)
        {
            Debug.LogWarning($"[{tag}] the rebake produced no navmesh data.");
            return false;
        }

        var existing = AssetDatabase.LoadAssetAtPath<NavMeshData>(NavMeshAsset);
        if (existing != null && existing != surface.navMeshData)
        {
            // CopySerialized rather than CreateAsset: it overwrites in place, so the
            // asset's GUID survives and the scene's reference to it stays valid.
            EditorUtility.CopySerialized(surface.navMeshData, existing);
            surface.navMeshData = existing;
        }
        else if (!AssetDatabase.Contains(surface.navMeshData))
        {
            AssetDatabase.CreateAsset(surface.navMeshData, NavMeshAsset);
        }

        EditorUtility.SetDirty(surface.navMeshData);
        AssetDatabase.SaveAssets();
        return true;
    }
}
