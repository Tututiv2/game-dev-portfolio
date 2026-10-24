using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Renders the map scene to PNG files so it can actually be LOOKED at.
///
/// <para><b>Why this exists.</b> Everything about Phase 3 up to this point was
/// verified by tests — the grid contract, the YAML serialization, the navmesh, the
/// spawn clearances. Not one of those can answer "does the space read as a place
/// you would fight in", and until this tool existed the project had never rendered
/// a single frame. Tests prove the scene is correct; only a picture proves it is
/// any good.</para>
///
/// <para>It is read-only with respect to the scene: it opens the map, spawns a
/// throwaway camera, renders, and never saves. It cannot damage hand dressing,
/// so it is safe to re-run as often as you like while dressing the map.</para>
///
/// <para>Run headless (no editor window needed, but note the missing
/// <c>-nographics</c> — a graphics device is required to render anything):</para>
/// <code>
/// Unity.exe -batchmode -projectPath . -executeMethod SceneShot.CaptureBatch -quit
/// </code>
/// </summary>
public static class SceneShot
{
    const string k_OutputDir = "Screenshots";
    const int k_Width = 1600;
    const int k_Height = 900;

    struct Shot
    {
        public string Name;
        public Vector3 Position;
        public Vector3 Euler;
        public float Fov;
        public string What;

        /// <summary>
        /// Places a cyan 1x1x1 METRE cube on whatever this shot is pointed at.
        ///
        /// <para>Texture scale cannot be judged by eye from a photo — "are these
        /// bricks the right size" is unanswerable without something known-size in
        /// frame, and back-solving it from FOV arithmetic is how you talk yourself
        /// into the wrong number. Compare the brick to the cube.</para>
        ///
        /// <para>The position is found by raycasting along the camera's forward
        /// rather than typed in. Hand coordinates put the first attempt's cube
        /// BEHIND the camera, in a shot that otherwise looked completely fine —
        /// exactly the failure a self-placing ruler cannot have.</para>
        /// </summary>
        public bool Ruler;

        /// <summary>
        /// Read this shot's position from the LIVE Player transform instead of
        /// from <see cref="Position"/>.
        ///
        /// <para>Because the spawn shot was a hard-coded <c>(W(6), 1.6f, W(1))</c>
        /// = (24, 1.6, 4) captioned "exactly what the player sees on spawn", and
        /// once the player start moved that caption was simply false — the shot
        /// went on rendering the old position, which is itself inside a
        /// <c>Kit_Wall_4_Half</c>. Two renders taken 14 m apart came out looking
        /// identical, which is how it was noticed. This file's own rule already
        /// says every fact in report.txt must be read from the scene rather than
        /// asserted; a camera position is a fact about the scene.</para>
        /// </summary>
        public bool FromPlayerEye;
    }

    // Cell centres are at cell * 4 m; the map is 14 x 14 cells, so 0..52 m.
    static float W(float cell) => cell * MapKitBuilder.Grid;

    static readonly Shot[] k_Shots =
    {
        // Eye height is 1.6 m to match MapSeeder's CameraHolder — anything else
        // lies about how tall the walls feel.
        // Position is IGNORED for this one -- it is read from the live Player
        // transform at capture time. See FromPlayerEye below. The literal is kept
        // only so the struct stays uniform.
        new Shot { Name = "01_player_eye_spawn", Position = new Vector3(W(6), 1.6f, W(1)),
                   Euler = new Vector3(2f, 0f, 0f), Fov = 60f, FromPlayerEye = true,
                   What = "Exactly what the player sees on spawn, at real eye height." },

        new Shot { Name = "02_spawn_yard_across", Position = new Vector3(W(2), 1.6f, W(1)),
                   Euler = new Vector3(3f, 55f, 0f), Fov = 60f,
                   What = "Zone A looking across the yard at the divider wall and its doorways." },

        new Shot { Name = "03_doorway_approach", Position = new Vector3(W(3), 1.6f, W(2)),
                   Euler = new Vector3(0f, 0f, 0f), Fov = 60f,
                   What = "Walking up to the west buyable-door opening. Is the doorway readable?" },

        new Shot { Name = "04_warehouse_exterior", Position = new Vector3(W(3), 1.6f, W(5)),
                   Euler = new Vector3(4f, 0f, 0f), Fov = 60f,
                   What = "The warehouse from outside, in zone B." },

        new Shot { Name = "05_warehouse_interior", Position = new Vector3(W(3), 1.6f, W(9)),
                   Euler = new Vector3(0f, 200f, 0f), Fov = 70f,
                   What = "Standing inside the warehouse looking back at its door wall." },

        // Aimed at the EAST PERIMETER, which has no doorways. The first version of
        // this shot faced a zone divider and the ruler ray flew straight through its
        // doorway, planting the cube 21 m away where it measured nothing.
        new Shot { Name = "06_wall_material_closeup", Position = new Vector3(W(12) + 2f, 1.6f, W(7)),
                   Euler = new Vector3(0f, 90f, 0f), Fov = 50f,
                   What = "Solid perimeter wall, 4 m tall, with a 1 m cube against it. Count courses against the cube.",
                   Ruler = true },

        new Shot { Name = "07_floor_material_closeup", Position = new Vector3(W(7), 1.6f, W(3)),
                   Euler = new Vector3(35f, 20f, 0f), Fov = 50f,
                   What = "The ground with a 1 m cube on it, for the same reason.",
                   Ruler = true },

        new Shot { Name = "08_ramp_and_verticality", Position = new Vector3(W(2), 1.6f, W(6)),
                   Euler = new Vector3(2f, -80f, 0f), Fov = 65f,
                   What = "The ramp on the west edge — does the verticality read?" },

        // Does the climb arrive anywhere? This pair is why the map was re-seeded:
        // before, both were taken 4 m up in mid-air with nothing to step onto.
        new Shot { Name = "13_warehouse_roof", Position = new Vector3(W(3), 5.6f, W(9)),
                   Euler = new Vector3(6f, 80f, 0f), Fov = 70f,
                   What = "Standing ON the warehouse roof, at eye height above it. Is this fightable space?" },

        new Shot { Name = "14_loading_dock", Position = new Vector3(W(10.5f), 3.6f, W(1.5f)),
                   Euler = new Vector3(6f, -105f, 0f), Fov = 70f,
                   What = "Standing ON the loading dock deck, looking back down the ramp across the yard." },

        // Phase 4: does the debris actually fill the doorway, and is the switch
        // somewhere a player would find it?
        new Shot { Name = "17_buyable_door_750", Position = new Vector3(12f, 1.6f, 13.5f),
                   Euler = new Vector3(0f, 0f, 0f), Fov = 60f,
                   What = "Walking up to the 750 door on the zone divider." },

        new Shot { Name = "18_buyable_door_1000", Position = new Vector3(40f, 1.6f, 13.5f),
                   Euler = new Vector3(0f, 0f, 0f), Fov = 60f,
                   What = "The 1000 door into zone C." },

        new Shot { Name = "19_power_switch", Position = new Vector3(45f, 1.5f, 33f),
                   Euler = new Vector3(0f, 90f, 0f), Fov = 55f,
                   What = "Inside the substation, looking at the power switch on the east wall." },

        // Phase 5: the boarded windows. Warehouse north wall, cell (1, 11).
        new Shot { Name = "20_window_barrier", Position = new Vector3(4f, 1.9f, 50f),
                   Euler = new Vector3(0f, 180f, 0f), Fov = 55f,
                   What = "A boarded window from outside — six planks across the opening." },

        // Phase 7: do the perk machines read as distinct at a glance?
        new Shot { Name = "21_perk_warehouse", Position = new Vector3(10f, 1.6f, 36f),
                   Euler = new Vector3(0f, 320f, 0f), Fov = 65f,
                   What = "Juggernog and Speed Cola inside the warehouse." },

        new Shot { Name = "22_pap_substation", Position = new Vector3(40f, 1.6f, 31f),
                   Euler = new Vector3(0f, 35f, 0f), Fov = 65f,
                   What = "Pack-a-Punch and Double Tap in the substation, near the switch." },

        new Shot { Name = "15_ramp_foot", Position = new Vector3(W(7), 1.6f, W(2)),
                   Euler = new Vector3(2f, 75f, 0f), Fov = 65f,
                   What = "Ground level at the foot of the ramp. Does it meet the floor cleanly, no lip?" },

        new Shot { Name = "16_warehouse_stairwell", Position = new Vector3(W(3), 1.6f, W(9)),
                   Euler = new Vector3(10f, 55f, 0f), Fov = 70f,
                   What = "Inside the warehouse, looking at the staircase and the roof over it." },

        new Shot { Name = "09_substation", Position = new Vector3(W(9), 1.6f, W(5)),
                   Euler = new Vector3(4f, 20f, 0f), Fov = 60f,
                   What = "The substation building in zone C." },

        new Shot { Name = "10_sky_horizon", Position = new Vector3(W(6), 1.6f, W(6)),
                   Euler = new Vector3(-14f, 200f, 0f), Fov = 65f,
                   What = "Looking up toward the HDRI sun direction (SkySetup rotation is 200 deg)." },

        // Not a gameplay view — a plan shot, for judging layout and proportion.
        new Shot { Name = "11_overhead_plan", Position = new Vector3(W(6.5f), 62f, W(6.5f)),
                   Euler = new Vector3(90f, 0f, 0f), Fov = 60f,
                   What = "Straight down. Layout, zone shape, and whether the map is too empty." },

        new Shot { Name = "12_three_quarter_aerial", Position = new Vector3(-18f, 30f, -18f),
                   Euler = new Vector3(28f, 45f, 0f), Fov = 55f,
                   What = "Three-quarter aerial from outside the map, for overall silhouette." },
    };

    [MenuItem("Tools/Aftermarket/Capture Map Screenshots")]
    public static void Capture() => Run();

    /// <summary>Entry point for -executeMethod. Never throws past the caller so
    /// batchmode always writes the report before -quit tears the editor down.</summary>
    public static void CaptureBatch()
    {
        try { Run(); }
        catch (Exception e) { Debug.LogError($"[SceneShot] failed: {e}"); }
    }

    static void Run()
    {
        if (!File.Exists(MapSeeder.ScenePath))
        {
            Debug.LogError($"[SceneShot] no scene at {MapSeeder.ScenePath}.");
            return;
        }

        // If the map is ALREADY open, use it as it stands and do not reload.
        //
        // EditorSceneManager.OpenScene called from a script does not prompt: it
        // discards unsaved changes in the open scene and clears the undo stack, so
        // an hour of hand dressing goes on one click of the menu item. This tool is
        // specifically advertised as safe to run *while* dressing, so it must not be
        // the thing that eats the dressing. Screenshotting the live, unsaved state
        // is also what you actually want mid-session.
        if (SceneManager.GetActiveScene().path != MapSeeder.ScenePath)
        {
            if (!Application.isBatchMode &&
                !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            {
                Debug.LogWarning("[SceneShot] cancelled — the open scene has unsaved changes " +
                                 "and loading the map would discard them.");
                return;
            }
            EditorSceneManager.OpenScene(MapSeeder.ScenePath, OpenSceneMode.Single);
        }

        Directory.CreateDirectory(k_OutputDir);

        var report = new StringBuilder();
        report.AppendLine($"scene: {MapSeeder.ScenePath}");
        report.AppendLine($"graphics device: {SystemInfo.graphicsDeviceType} ({SystemInfo.graphicsDeviceName})");
        report.AppendLine($"render pipeline: {(UnityEngine.Rendering.GraphicsSettings.currentRenderPipeline != null ? UnityEngine.Rendering.GraphicsSettings.currentRenderPipeline.name : "BUILT-IN (no SRP!)")}");
        report.AppendLine($"colour space: {QualitySettings.activeColorSpace}");
        report.AppendLine($"skybox material: {(RenderSettings.skybox != null ? RenderSettings.skybox.name : "NONE")}");
        report.AppendLine($"ambient mode: {RenderSettings.ambientMode}");
        report.AppendLine($"fog: {RenderSettings.fog} density {RenderSettings.fogDensity}");
        report.AppendLine();

        // A graphics device is mandatory. -nographics gives Null and every PNG
        // comes out a flat colour, which is exactly the kind of "it ran, so it
        // worked" result worth failing loudly on instead.
        if (SystemInfo.graphicsDeviceType == UnityEngine.Rendering.GraphicsDeviceType.Null)
        {
            Debug.LogError("[SceneShot] no graphics device — run WITHOUT -nographics or every " +
                           "screenshot will be a blank fill.");
            return;
        }

        var camGo = new GameObject("~SceneShotCamera") { hideFlags = HideFlags.HideAndDontSave };
        var cam = camGo.AddComponent<Camera>();
        cam.clearFlags = CameraClearFlags.Skybox;
        cam.nearClipPlane = 0.05f;
        cam.farClipPlane = 400f;
        cam.cullingMask = ~0;
        cam.allowHDR = true;

        // Without this the shot has NO post-processing — no tonemapping, so every
        // HDR value over 1.0 clips flat white and the sky becomes a blank sheet.
        // renderPostProcessing defaults to FALSE on a bare AddComponent<Camera>().
        var urp = camGo.AddComponent<UnityEngine.Rendering.Universal.UniversalAdditionalCameraData>();
        urp.renderPostProcessing = true;
        urp.antialiasing = UnityEngine.Rendering.Universal.AntialiasingMode.SubpixelMorphologicalAntiAliasing;

        // READ from the scene, never assert. This line used to be a hardcoded string
        // claiming the scene camera had post-processing off. It was true when it was
        // written and false the moment MapSeeder was fixed, so the report — the
        // artifact you are meant to trust — went on stating a bug that no longer
        // existed. A report that describes the code instead of the scene is worse
        // than no report.
        report.AppendLine("shot camera: post-processing ON");
        report.AppendLine("scene camera: " + DescribeSceneCameraPostProcessing());
        report.AppendLine();

        GameObject ruler = null;
        try
        {
            foreach (var shot in k_Shots)
            {
                var from = shot.Position;
                if (shot.FromPlayerEye)
                {
                    var player = GameObject.Find("Player");
                    var holder = player != null ? FindDeep(player.transform, "CameraHolder") : null;
                    if (holder != null) from = holder.position;
                    else if (player != null) from = player.transform.position + Vector3.up * 1.6f;
                    else Debug.LogWarning("[SceneShot] no Player in the scene, so " +
                                          shot.Name + " fell back to a typed position.");
                }

                camGo.transform.SetPositionAndRotation(from, Quaternion.Euler(shot.Euler));
                cam.fieldOfView = shot.Fov;

                if (ruler != null) UnityEngine.Object.DestroyImmediate(ruler);
                ruler = shot.Ruler ? BuildRuler(camGo.transform) : null;

                var stats = RenderToFile(cam, Path.Combine(k_OutputDir, shot.Name + ".png"));
                report.AppendLine($"{shot.Name}.png  {shot.What}");
                report.AppendLine($"    from {Fmt(from)} looking {Fmt(shot.Euler)} fov {shot.Fov}" +
                                  (shot.FromPlayerEye ? "  (read from the live Player)" : ""));
                if (shot.Ruler)
                    report.AppendLine(ruler != null
                        ? $"    1 m cyan reference cube at {Fmt(ruler.transform.position)}"
                        : "    RULER MISSED — the camera forward ray hit nothing.");
                report.AppendLine($"    {stats}");
            }
        }
        finally
        {
            if (ruler != null) UnityEngine.Object.DestroyImmediate(ruler);
            UnityEngine.Object.DestroyImmediate(camGo);
        }

        report.AppendLine();
        report.Append(MeasureProps());
        report.AppendLine();
        report.Append(ShootProps());

        var reportPath = Path.Combine(k_OutputDir, "report.txt");
        File.WriteAllText(reportPath, report.ToString());
        Debug.Log($"[SceneShot] wrote {k_Shots.Length} screenshots and {reportPath}\n{report}");
    }

    /// <summary>
    /// One picture of every forged prop, each standing next to a 1 m cube.
    ///
    /// <para>The FBX files were never opened after the forge wrote them. Their
    /// reported bounds alone say something is wrong — a "barrel" 8 mm across one
    /// axis is not a barrel — but bounds cannot tell you whether a mesh is a good
    /// barrel, only how big its box is.</para>
    /// </summary>
    static string ShootProps()
    {
        var sb = new StringBuilder();
        sb.AppendLine("prop contact sheet (each prop beside a 1 m cube):");

        var dir = Path.Combine(k_OutputDir, "props");
        Directory.CreateDirectory(dir);

        var camGo = new GameObject("~PropCam") { hideFlags = HideFlags.HideAndDontSave };
        var cam = camGo.AddComponent<Camera>();
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.22f, 0.22f, 0.25f);
        cam.nearClipPlane = 0.01f;
        var urp = camGo.AddComponent<UnityEngine.Rendering.Universal.UniversalAdditionalCameraData>();
        urp.renderPostProcessing = true;

        // Well away from the map so nothing of the level leaks into the frame.
        var stage = new Vector3(0f, -500f, 0f);

        var key = new GameObject("~PropKey") { hideFlags = HideFlags.HideAndDontSave };
        var light = key.AddComponent<Light>();
        light.type = LightType.Directional;
        light.intensity = 1.4f;
        key.transform.rotation = Quaternion.Euler(35f, 150f, 0f);

        foreach (var slug in new[] { "barrel_rusted", "crate_wooden", "debris_pile",
                                     "generator_unit", "pallet_stack", "shrine_obelisk" })
        {
            var path = $"Assets/_Project/Art/Props/{slug}.fbx";
            var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(path);
            if (prefab == null) { sb.AppendLine($"    {slug}: could not load {path}"); continue; }

            var instance = UnityEngine.Object.Instantiate(prefab);
            instance.hideFlags = HideFlags.HideAndDontSave;
            instance.transform.position = stage;

            // The prop's own bounds decide the framing, so a mis-scaled prop still
            // fills the frame instead of vanishing to a dot.
            var bounds = new Bounds(stage, Vector3.zero);
            bool any = false;
            foreach (var r in instance.GetComponentsInChildren<Renderer>())
            {
                if (!any) { bounds = r.bounds; any = true; } else bounds.Encapsulate(r.bounds);
            }

            var ruler = GameObject.CreatePrimitive(PrimitiveType.Cube);
            ruler.hideFlags = HideFlags.HideAndDontSave;
            var shader = Shader.Find("Universal Render Pipeline/Unlit") ?? Shader.Find("Unlit/Color");
            var mat = new Material(shader) { hideFlags = HideFlags.HideAndDontSave };
            mat.color = new Color(0f, 0.9f, 1f);
            if (mat.HasProperty("_BaseColor")) mat.SetColor("_BaseColor", new Color(0f, 0.9f, 1f));
            ruler.GetComponent<MeshRenderer>().sharedMaterial = mat;
            ruler.transform.position = new Vector3(bounds.max.x + 0.9f, stage.y + 0.5f, stage.z);

            bounds.Encapsulate(ruler.GetComponent<Renderer>().bounds);

            float radius = bounds.extents.magnitude;
            float distance = radius / Mathf.Sin(Mathf.Deg2Rad * 22f) + 0.5f;
            cam.fieldOfView = 44f;
            camGo.transform.position = bounds.center + new Vector3(0.75f, 0.5f, -1f).normalized * distance;
            camGo.transform.LookAt(bounds.center);

            var stats = RenderToFile(cam, Path.Combine(dir, slug + ".png"));
            sb.AppendLine($"    {slug}.png  {stats}");

            UnityEngine.Object.DestroyImmediate(ruler);
            UnityEngine.Object.DestroyImmediate(instance);
        }

        UnityEngine.Object.DestroyImmediate(key);
        UnityEngine.Object.DestroyImmediate(camGo);
        return sb.ToString();
    }

    /// <summary>
    /// A 1 x 1 x 1 metre cube, sitting on the ground, in a colour that appears
    /// nowhere in the map so it can never be mistaken for scenery. Unlit on purpose:
    /// a lit cube changes brightness with the sun and stops reading as a ruler.
    /// </summary>
    static GameObject BuildRuler(Transform camera)
    {
        // Whatever the shot is aimed at, that is what gets the ruler. Backed off
        // half a metre so the cube stands next to the surface instead of inside it.
        if (!Physics.Raycast(camera.position, camera.forward, out var hit, 60f))
            return null;

        var stand = hit.point - camera.forward * 0.5f;

        var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
        go.name = "~MetreRuler";
        go.hideFlags = HideFlags.HideAndDontSave;
        go.transform.position = new Vector3(stand.x, 0.5f, stand.z);   // sitting on y = 0
        go.transform.localScale = Vector3.one;      // Unity's cube primitive is 1 m.

        var shader = Shader.Find("Universal Render Pipeline/Unlit") ?? Shader.Find("Unlit/Color");
        var mat = new Material(shader) { hideFlags = HideFlags.HideAndDontSave };
        mat.color = new Color(0f, 0.9f, 1f);
        if (mat.HasProperty("_BaseColor")) mat.SetColor("_BaseColor", new Color(0f, 0.9f, 1f));
        go.GetComponent<MeshRenderer>().sharedMaterial = mat;

        UnityEngine.Object.DestroyImmediate(go.GetComponent<BoxCollider>());
        return go;
    }

    /// <summary>
    /// Whether the scene's OWN camera renders post-processing, read off the scene.
    /// If it does not, these screenshots are prettier than the game and say so.
    /// </summary>
    static string DescribeSceneCameraPostProcessing()
    {
        Camera found = null;
        foreach (var root in SceneManager.GetActiveScene().GetRootGameObjects())
        {
            var candidate = root.GetComponentInChildren<Camera>(true);
            if (candidate != null) { found = candidate; break; }
        }

        if (found == null) return "the scene has no camera of its own.";

        var data = found.GetComponent<UnityEngine.Rendering.Universal.UniversalAdditionalCameraData>();
        if (data == null)
            return $"'{found.name}' has NO UniversalAdditionalCameraData, so post-processing is " +
                   "OFF in play and these shots do NOT match what the game renders.";

        return data.renderPostProcessing
            ? $"'{found.name}' has post-processing ON, so these shots match what the game renders."
            : $"'{found.name}' has post-processing explicitly OFF, so these shots do NOT match play.";
    }

    static string Fmt(Vector3 v) =>
        string.Format(CultureInfo.InvariantCulture, "({0:0.#}, {1:0.#}, {2:0.#})", v.x, v.y, v.z);

    /// <summary>
    /// Renders one frame and reports the mean luminance and the number of distinct
    /// colours. A blank render — wrong pipeline, no device, camera inside geometry —
    /// looks identical to a good one on disk unless you measure it, and "1 unique
    /// colour" is the tell.
    /// </summary>
    static string RenderToFile(Camera cam, string path)
    {
        var rt = new RenderTexture(k_Width, k_Height, 24, RenderTextureFormat.ARGB32,
                                   RenderTextureReadWrite.sRGB) { antiAliasing = 4 };
        var tex = new Texture2D(k_Width, k_Height, TextureFormat.RGBA32, false, false);

        try
        {
            cam.targetTexture = rt;
            cam.Render();
            cam.targetTexture = null;

            var previous = RenderTexture.active;
            RenderTexture.active = rt;
            tex.ReadPixels(new Rect(0, 0, k_Width, k_Height), 0, 0);
            tex.Apply();
            RenderTexture.active = previous;

            var bytes = tex.EncodeToPNG();
            File.WriteAllBytes(path, bytes);

            var pixels = tex.GetPixels32();
            double sum = 0;
            var unique = new HashSet<int>();
            for (int i = 0; i < pixels.Length; i += 7)   // every 7th pixel: plenty for a sanity check
            {
                var p = pixels[i];
                sum += 0.2126 * p.r + 0.7152 * p.g + 0.0722 * p.b;
                unique.Add((p.r >> 3 << 10) | (p.g >> 3 << 5) | (p.b >> 3));
            }
            int sampled = (pixels.Length + 6) / 7;

            return string.Format(CultureInfo.InvariantCulture,
                "{0} KB, mean luma {1:0.0}/255, {2} distinct colours{3}",
                bytes.Length / 1024, sum / sampled, unique.Count,
                unique.Count < 8 ? "   <-- SUSPECT: essentially a flat fill" : "");
        }
        finally
        {
            cam.targetTexture = null;
            UnityEngine.Object.DestroyImmediate(tex);
            rt.Release();
            UnityEngine.Object.DestroyImmediate(rt);
        }
    }

    /// <summary>
    /// Real-world size of every forged prop, straight off the imported mesh.
    ///
    /// <para>This is the only honest answer to "did the FBX export scale correctly".
    /// The exporter flags look right on paper either way; a barrel that imports 0.9 m
    /// tall is right and one that imports 90 m or 0.009 m is not, and nothing but
    /// reading the bounds tells you which you got.</para>
    /// </summary>
    static string MeasureProps()
    {
        var sb = new StringBuilder();
        sb.AppendLine("prop import scale (bounds of the imported mesh, in metres):");

        var guids = AssetDatabase.FindAssets("t:Model", new[] { "Assets/_Project/Art/Props" });
        if (guids.Length == 0) sb.AppendLine("    none found.");

        Array.Sort(guids);
        foreach (var guid in guids)
        {
            var assetPath = AssetDatabase.GUIDToAssetPath(guid);
            var go = AssetDatabase.LoadAssetAtPath<GameObject>(assetPath);
            if (go == null) { sb.AppendLine($"    {assetPath}: NOT a model (import failed)"); continue; }

            var filter = go.GetComponentInChildren<MeshFilter>();
            if (filter == null || filter.sharedMesh == null)
            {
                sb.AppendLine($"    {Path.GetFileName(assetPath)}: no mesh");
                continue;
            }

            var mesh = filter.sharedMesh;
            var scale = filter.transform.lossyScale;
            var size = Vector3.Scale(mesh.bounds.size, scale);
            sb.AppendLine(string.Format(CultureInfo.InvariantCulture,
                "    {0,-28} {1,7:0.000} x {2,7:0.000} x {3,7:0.000} m   {4} tris   import scale {5:0.###}",
                Path.GetFileName(assetPath), size.x, size.y, size.z,
                mesh.triangles.Length / 3, scale.x));
        }
        return sb.ToString();
    }

    static Transform FindDeep(Transform root, string name)
    {
        if (root.name == name) return root;
        foreach (Transform child in root)
        {
            var found = FindDeep(child, name);
            if (found != null) return found;
        }
        return null;
    }
}
