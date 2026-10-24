using System.Collections;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using NUnit.Framework;
using UnityEngine;
using UnityEngine.SceneManagement;
using UnityEngine.TestTools;
#if UNITY_EDITOR
using UnityEditor;
using UnityEditor.SceneManagement;
#endif

/// <summary>
/// DIAGNOSTIC, not a guard. This exists because "the zombie renders untextured
/// while alive and correct once it dies" cannot be seen from the editor — the
/// editor render (Screenshots/zombie/front.png) is perfectly textured, so every
/// asset-level check passes. The difference only exists while the Animator is
/// writing bone transforms, which is what EnemyAI.Die switches off.
///
/// It loads the real map in Play mode, drops one zombie in front of a camera,
/// and photographs the SAME zombie in the SAME spot with only `anim.enabled`
/// changed — so the picture pair isolates that one line and nothing else.
/// Everything it reports is read off the live objects; nothing is asserted.
/// </summary>
public class ZombieRuntimeDiagnostics
{
    const string k_MapPath = "Assets/_Project/Scenes/AshfallDepot.unity";
    const string k_PrefabPath = "Assets/_Project/Art/Zombie/Enemy.prefab";
    static readonly string k_OutDir =
        Path.Combine(Directory.GetCurrentDirectory(), "Screenshots", "zombie_play");

    const int k_Width = 900;
    const int k_Height = 1200;

    readonly StringBuilder report = new StringBuilder();

    [UnityTest]
    public IEnumerator Zombie_PhotographItAliveAndWithTheAnimatorOff()
    {
#if !UNITY_EDITOR
        yield break;
#else
        // The map has no navmesh under an arbitrary point and EnemyAI is happy to
        // complain about that; none of it is the subject here.
        LogAssert.ignoreFailingMessages = true;
        Directory.CreateDirectory(k_OutDir);

        EditorSceneManager.LoadSceneInPlayMode(
            k_MapPath, new LoadSceneParameters(LoadSceneMode.Single));
        yield return null;
        while (!SceneManager.GetActiveScene().isLoaded) yield return null;
        for (int i = 0; i < 5; i++) yield return null;

        var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(k_PrefabPath);
        Assert.IsNotNull(prefab, "no Enemy.prefab at " + k_PrefabPath);

        // Stand it beside the live Player — flat floor on the navmesh, and the
        // exact lighting the owner is looking at when he sees the bug. Read from
        // the scene, never typed: the player start has already moved once.
        var playerGo = GameObject.Find("Player");
        Assert.IsNotNull(playerGo, "no Player in the map");
        // 20 m over the map: the real scene, the real lighting, and nothing in
        // front of the camera. Framing this against map geometry photographed a
        // warehouse wall twice.
        Vector3 stage = playerGo.transform.position + new Vector3(0f, 20f, 0f);
        var zombie = Object.Instantiate(prefab, stage, Quaternion.identity);
        zombie.name = "DiagnosticZombie";

        var camGo = new GameObject("DiagCam");
        var cam = camGo.AddComponent<Camera>();
        cam.fieldOfView = 40f;
        cam.nearClipPlane = 0.05f;
        cam.farClipPlane = 500f;
        cam.clearFlags = CameraClearFlags.Skybox;
        cam.cullingMask = ~0;

        // Let Start() run once, then take the AI and the stabiliser out of the
        // way so nothing but the Animator is touching this thing, and drive the
        // walk directly. The subject is the renderer, not the brain.
        yield return null;
        var brain = zombie.GetComponent<EnemyAI>();
        if (brain != null) brain.enabled = false;
        var stab = zombie.GetComponent<ZombieStabilizer>();
        if (stab != null) stab.enabled = false;
        var nav = zombie.GetComponent<UnityEngine.AI.NavMeshAgent>();
        if (nav != null) nav.enabled = false;
        zombie.transform.position = stage;

        var a0 = zombie.GetComponentInChildren<Animator>(true);
        if (a0 != null) { a0.SetBool("IsWalking", true); a0.Play("Walk1", 0, 0.3f); }
        for (int i = 0; i < 60; i++) yield return null;

        var smrs = zombie.GetComponentsInChildren<SkinnedMeshRenderer>(true);
        var anim = zombie.GetComponentInChildren<Animator>(true);

        report.AppendLine("scene: " + SceneManager.GetActiveScene().name);
        report.AppendLine("zombie root scale: " + zombie.transform.localScale.ToString("F4"));
        report.AppendLine("model local scale: " +
            (anim != null ? anim.transform.localScale.ToString("F4") : "<no animator>"));
        report.AppendLine("model local rot:   " +
            (anim != null ? anim.transform.localRotation.eulerAngles.ToString("F2") : "-"));
        report.AppendLine();

        if (anim != null)
        {
            report.AppendLine("ANIMATOR");
            report.AppendLine("  controller     : " +
                (anim.runtimeAnimatorController != null ? anim.runtimeAnimatorController.name : "<null>"));
            report.AppendLine("  avatar         : " +
                (anim.avatar != null ? anim.avatar.name : "<null>"));
            report.AppendLine("  avatar.isValid : " + (anim.avatar != null && anim.avatar.isValid));
            report.AppendLine("  avatar.isHuman : " + (anim.avatar != null && anim.avatar.isHuman));
            report.AppendLine("  cullingMode    : " + anim.cullingMode);
            report.AppendLine("  applyRootMotion: " + anim.applyRootMotion);
            var st = anim.GetCurrentAnimatorStateInfo(0);
            report.AppendLine("  state hash     : " + st.fullPathHash + "  normTime " +
                st.normalizedTime.ToString("F2", CultureInfo.InvariantCulture));
            report.AppendLine();
        }

        foreach (var smr in smrs)
        {
            report.AppendLine("SKINNED MESH: " + smr.name);
            var mesh = smr.sharedMesh;
            report.AppendLine("  sharedMesh      : " + (mesh != null ? mesh.name : "<NULL>"));
            if (mesh != null)
            {
                report.AppendLine("  submeshes       : " + mesh.subMeshCount);
                report.AppendLine("  bindposes       : " + mesh.bindposes.Length);
                report.AppendLine("  mesh.uv len     : " + mesh.uv.Length +
                                  "   verts " + mesh.vertexCount);
                report.AppendLine("  mesh.tangents   : " + mesh.tangents.Length);
                report.AppendLine("  mesh.normals    : " + mesh.normals.Length);
                report.AppendLine("  mesh.bounds     : " + mesh.bounds.ToString("F3"));
            }
            report.AppendLine("  bones           : " + smr.bones.Length);
            report.AppendLine("  rootBone        : " +
                (smr.rootBone != null ? smr.rootBone.name : "<NULL>"));
            report.AppendLine("  quality         : " + smr.quality);
            report.AppendLine("  updateOffscreen : " + smr.updateWhenOffscreen);
            report.AppendLine("  localBounds     : " + smr.localBounds.ToString("F3"));
            report.AppendLine("  world bounds    : " + smr.bounds.ToString("F3"));
            report.AppendLine("  isVisible       : " + smr.isVisible);
            report.AppendLine("  materials       : " + smr.sharedMaterials.Length);
            report.AppendLine("  hasPropertyBlock: " + smr.HasPropertyBlock());
            for (int i = 0; i < smr.sharedMaterials.Length; i++)
            {
                var m = smr.sharedMaterials[i];
                if (m == null) { report.AppendLine("    [" + i + "] <NULL MATERIAL>"); continue; }
                var baseMap = m.HasProperty("_BaseMap") ? m.GetTexture("_BaseMap") : null;
                var mainTex = m.HasProperty("_MainTex") ? m.GetTexture("_MainTex") : null;
                report.AppendLine("    [" + i + "] " + m.name + "  shader=" + m.shader.name);
                report.AppendLine("         _BaseMap  = " + (baseMap != null ? baseMap.name : "<NULL>"));
                report.AppendLine("         _MainTex  = " + (mainTex != null ? mainTex.name : "<NULL>"));
                report.AppendLine("         _BaseColor= " +
                    (m.HasProperty("_BaseColor") ? m.GetColor("_BaseColor").ToString("F3") : "-"));
                report.AppendLine("         _BaseMap_ST = " +
                    (m.HasProperty("_BaseMap") ? m.GetTextureScale("_BaseMap").ToString("F3") + " / " +
                                                 m.GetTextureOffset("_BaseMap").ToString("F3") : "-"));
                report.AppendLine("         keywords  = " + string.Join(",", m.shaderKeywords));
            }
            report.AppendLine();
        }

        // WHERE THE CAMERA CAN ACTUALLY SEE IT, found by probing rather than
        // typed. The first version put the camera at a hand-written offset and
        // photographed the inside of a warehouse wall three times.
        var focus = stage + Vector3.up * 0.95f;
        Vector3 best = Vector3.forward;
        float bestClear = -1f;
        for (int a = 0; a < 24; a++)
        {
            var dir = Quaternion.Euler(0f, a * 15f, 0f) * Vector3.forward;
            float clear = Physics.Raycast(focus, dir, out var hit, 6f) ? hit.distance : 6f;
            if (clear > bestClear) { bestClear = clear; best = dir; }
        }
        float dist = Mathf.Clamp(bestClear - 0.4f, 1.6f, 3.2f);
        camGo.transform.position = focus + best * dist + Vector3.up * 0.15f;
        camGo.transform.LookAt(focus);
        report.AppendLine("camera " + dist.ToString("F2") + " m out, clearance " +
                          bestClear.ToString("F2") + " m");
        report.AppendLine();

        // ---- A: exactly as it plays --------------------------------------
        var picA = Shoot(cam, "A_alive_animator_on");
        report.AppendLine("A_alive_animator_on   " + picA.stats +
                          "   isVisible=" + smrs[0].isVisible);
        var bonesA = SampleBones(smrs);

        // ---- A2: one frame later, animator still on. If A and A2 are the
        // same picture the Animator is not writing anything at all.
        yield return null;
        var picA2 = Shoot(cam, "A2_alive_one_frame_later");
        report.AppendLine("A2_one_frame_later    " + picA2.stats +
                          "   pixels differing from A: " + Diff(picA.pixels, picA2.pixels) + "%");
        var bonesA2 = SampleBones(smrs);

        // ---- B: the one line EnemyAI.Die changes -------------------------
        if (anim != null) anim.enabled = false;
        yield return null;
        var picB = Shoot(cam, "B_animator_off");
        report.AppendLine("B_animator_off        " + picB.stats +
                          "   pixels differing from A2: " + Diff(picA2.pixels, picB.pixels) + "%");
        var bonesB = SampleBones(smrs);

        report.AppendLine();
        report.AppendLine("BONE WORLD POSITIONS  (A = alive, A2 = +1 frame, B = animator off)");
        for (int i = 0; i < bonesA.Length; i++)
            report.AppendLine("  " + bonesA[i].name.PadRight(16) +
                " A" + bonesA[i].pos.ToString("F3") +
                "  A->A2 " + Vector3.Distance(bonesA[i].pos, bonesA2[i].pos).ToString("F4") +
                "  A2->B " + Vector3.Distance(bonesA2[i].pos, bonesB[i].pos).ToString("F4") +
                "  scale " + bonesB[i].scale.ToString("F3"));

        // ---- C: a real death, ragdoll and all ----------------------------
        if (anim != null) anim.enabled = true;
        yield return null;
        var ai = zombie.GetComponent<EnemyAI>();
        if (ai != null) { ai.enabled = true; ai.Die(); }
        for (int i = 0; i < 3; i++) yield return null;
        report.AppendLine();
        report.AppendLine("C_after_real_Die      " + Shoot(cam, "C_after_real_Die").stats);

        File.WriteAllText(Path.Combine(k_OutDir, "report.txt"), report.ToString());
        Debug.Log("[ZombieDiag] wrote " + k_OutDir + "\n" + report);
        yield return null;
#endif
    }

    /// <summary>
    /// The same question asked without any of the harness's help: let the real
    /// EnemySpawner run a real round, find a zombie it spawned, photograph it
    /// alive and then dead from the same camera. The isolated test above renders
    /// a correct zombie, so if the bug is real it lives in something this path
    /// does and that one does not.
    /// </summary>
    [UnityTest]
    public IEnumerator Zombie_PhotographOneTheSpawnerMade()
    {
#if !UNITY_EDITOR
        yield break;
#else
        LogAssert.ignoreFailingMessages = true;
        Directory.CreateDirectory(k_OutDir);
        var log = new StringBuilder();

        EditorSceneManager.LoadSceneInPlayMode(
            k_MapPath, new LoadSceneParameters(LoadSceneMode.Single));
        yield return null;
        while (!SceneManager.GetActiveScene().isLoaded) yield return null;

        var spawner = Object.FindFirstObjectByType<EnemySpawner>();
        Assert.IsNotNull(spawner, "no EnemySpawner in the map");
        log.AppendLine("spawner: prefab=" +
            (spawner.enemyPrefab != null ? spawner.enemyPrefab.name : "<null>") +
            "  spawnPoints=" + (spawner.spawnPoints != null ? spawner.spawnPoints.Length : 0));

        // initialGameDelay is 5 s of real time; batchmode runs uncapped so this
        // is a wait on the clock, not on a frame count.
        float deadline = Time.realtimeSinceStartup + 45f;
        EnemyAI subject = null;
        while (Time.realtimeSinceStartup < deadline)
        {
            subject = Object.FindFirstObjectByType<EnemyAI>();
            if (subject != null) break;
            yield return null;
        }
        log.AppendLine("round " + spawner.round + ", alive " + spawner.concurrentAlive);
        if (subject == null)
        {
            log.AppendLine("NO ZOMBIE SPAWNED within 45 s — nothing to photograph.");
            File.WriteAllText(Path.Combine(k_OutDir, "live_report.txt"), log.ToString());
            Debug.Log("[ZombieDiag/live]\n" + log);
            yield break;
        }

        // Let it get up, start walking, and settle into locomotion.
        float walkUntil = Time.realtimeSinceStartup + 4f;
        while (Time.realtimeSinceStartup < walkUntil && subject != null) yield return null;

        var camGo = new GameObject("LiveDiagCam");
        var cam = camGo.AddComponent<Camera>();
        cam.fieldOfView = 40f;
        cam.nearClipPlane = 0.05f;
        cam.farClipPlane = 500f;

        var sub = subject.transform;
        var focus = sub.position + Vector3.up * 1.0f;
        // Stand where the zombie is heading, which is open ground by definition —
        // it walked there.
        var backOff = -sub.forward;
        camGo.transform.position = focus + backOff * 3.0f + Vector3.up * 0.1f;
        camGo.transform.LookAt(focus);

        var smr = subject.GetComponentInChildren<SkinnedMeshRenderer>();
        var an = subject.GetComponentInChildren<Animator>();
        log.AppendLine("subject at " + sub.position.ToString("F2") +
                       "  animator.enabled=" + (an != null && an.enabled) +
                       "  smr.isVisible=" + (smr != null && smr.isVisible));
        if (smr != null)
            foreach (var m in smr.sharedMaterials)
                log.AppendLine("  mat " + (m != null ? m.name : "<null>") +
                    "  _BaseMap=" + (m != null && m.HasProperty("_BaseMap") && m.GetTexture("_BaseMap") != null
                        ? m.GetTexture("_BaseMap").name : "<NULL>") +
                    "  _BaseColor=" + (m != null && m.HasProperty("_BaseColor")
                        ? m.GetColor("_BaseColor").ToString("F3") : "-"));
        log.AppendLine("  renderer property blocks:");
        foreach (var r in subject.GetComponentsInChildren<Renderer>())
        {
            if (!r.HasPropertyBlock()) continue;
            var b = new MaterialPropertyBlock();
            r.GetPropertyBlock(b);
            log.AppendLine("    " + r.name + " HAS a block, _BaseColor=" +
                           b.GetColor("_BaseColor").ToString("F3"));
        }

        var alive = Shoot(cam, "LIVE_A_alive");
        log.AppendLine("LIVE_A_alive          " + alive.stats);
        yield return null;
        var alive2 = Shoot(cam, "LIVE_A2_alive");
        log.AppendLine("LIVE_A2_alive         " + alive2.stats +
                       "   differs from A: " + Diff(alive.pixels, alive2.pixels) + "%");

        subject.Die();
        for (int i = 0; i < 2; i++) yield return null;
        var dead = Shoot(cam, "LIVE_B_dead");
        log.AppendLine("LIVE_B_dead           " + dead.stats +
                       "   differs from A2: " + Diff(alive2.pixels, dead.pixels) + "%");

        File.WriteAllText(Path.Combine(k_OutDir, "live_report.txt"), log.ToString());
        Debug.Log("[ZombieDiag/live]\n" + log);
        yield return null;
#endif
    }

    /// <summary>
    /// Through the PLAYER'S camera, not a fresh one. A bare AddComponent&lt;Camera&gt;
    /// gets no UniversalAdditionalCameraData and therefore renders with post
    /// processing OFF — this project has already been bitten by exactly that once
    /// (see CLAUDE.md, "the player camera had no post-processing at all"). So the
    /// two tests above photograph a pipeline the player never looks through.
    /// </summary>
    [UnityTest]
    public IEnumerator Zombie_PhotographThroughThePlayersOwnCamera()
    {
#if !UNITY_EDITOR
        yield break;
#else
        LogAssert.ignoreFailingMessages = true;
        Directory.CreateDirectory(k_OutDir);
        var log = new StringBuilder();

        EditorSceneManager.LoadSceneInPlayMode(
            k_MapPath, new LoadSceneParameters(LoadSceneMode.Single));
        yield return null;
        while (!SceneManager.GetActiveScene().isLoaded) yield return null;
        for (int i = 0; i < 10; i++) yield return null;

        var cam = Camera.main;
        Assert.IsNotNull(cam, "no camera tagged MainCamera in the map");
        var data = cam.GetComponent<UnityEngine.Rendering.Universal.UniversalAdditionalCameraData>();
        log.AppendLine("player camera: " + cam.name +
            "  post=" + (data != null ? data.renderPostProcessing.ToString() : "<no URP data>") +
            "  AA=" + (data != null ? data.antialiasing.ToString() : "-") +
            "  cullingMask=" + cam.cullingMask);

        var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(k_PrefabPath);
        var zombie = Object.Instantiate(prefab);
        zombie.name = "CameraTestZombie";
        yield return null;
        // Nothing but the Animator drives it; the AI would walk it out of shot.
        var brain = zombie.GetComponent<EnemyAI>();
        if (brain != null) brain.enabled = false;
        var stab = zombie.GetComponent<ZombieStabilizer>();
        if (stab != null) stab.enabled = false;
        var nav = zombie.GetComponent<UnityEngine.AI.NavMeshAgent>();
        if (nav != null) nav.enabled = false;

        var anim = zombie.GetComponentInChildren<Animator>(true);
        if (anim != null) { anim.SetBool("IsWalking", true); anim.Play("Walk1", 0, 0.3f); }

        // Stand it 3.2 m down the camera's own forward, at the camera's foot level,
        // and face it back at the lens.
        Vector3 fwd = cam.transform.forward; fwd.y = 0f; fwd.Normalize();
        zombie.transform.position = cam.transform.position + fwd * 3.2f + Vector3.down * 1.5f;
        zombie.transform.rotation = Quaternion.LookRotation(-fwd, Vector3.up);

        for (int i = 0; i < 40; i++) yield return null;

        var picA = ShootWith(cam, "CAM_A_alive");
        log.AppendLine("CAM_A_alive           " + picA.stats);
        yield return null;
        var picA2 = ShootWith(cam, "CAM_A2_alive");
        log.AppendLine("CAM_A2_alive          " + picA2.stats +
                       "   differs from A: " + Diff(picA.pixels, picA2.pixels) + "%");

        if (anim != null) anim.enabled = false;
        yield return null;
        var picB = ShootWith(cam, "CAM_B_animator_off");
        log.AppendLine("CAM_B_animator_off    " + picB.stats +
                       "   differs from A2: " + Diff(picA2.pixels, picB.pixels) + "%");

        File.WriteAllText(Path.Combine(k_OutDir, "camera_report.txt"), log.ToString());
        Debug.Log("[ZombieDiag/cam]\n" + log);
        yield return null;
#endif
    }

    /// <summary>
    /// THE DISTANCE SWEEP. Every earlier shot here was taken from 3 m, and
    /// EnemySpawner deliberately spawns between preferMinDistance 18 and
    /// preferMaxDistance 70 — so a zombie is normally FIRST seen at 20-70 m and
    /// only ever gets close if it reaches you. A defect that only shows at range
    /// would have survived all of the above.
    /// </summary>
    [UnityTest]
    public IEnumerator Zombie_PhotographItAcrossTheDistancesYouActuallySeeItAt()
    {
#if !UNITY_EDITOR
        yield break;
#else
        LogAssert.ignoreFailingMessages = true;
        Directory.CreateDirectory(k_OutDir);
        var log = new StringBuilder();

        EditorSceneManager.LoadSceneInPlayMode(
            k_MapPath, new LoadSceneParameters(LoadSceneMode.Single));
        yield return null;
        while (!SceneManager.GetActiveScene().isLoaded) yield return null;
        for (int i = 0; i < 10; i++) yield return null;

        var cam = Camera.main;
        Assert.IsNotNull(cam, "no MainCamera");
        var fps = Object.FindFirstObjectByType<FPSController>();
        if (fps != null) fps.enabled = false;   // stop it rewriting the look angles

        log.AppendLine("shadowDistance " + QualitySettings.shadowDistance +
                       "   fog " + RenderSettings.fog +
                       " mode " + RenderSettings.fogMode +
                       " density " + RenderSettings.fogDensity +
                       " colour " + RenderSettings.fogColor.ToString("F3"));
        log.AppendLine("ambient mode " + RenderSettings.ambientMode +
                       "  intensity " + RenderSettings.ambientIntensity +
                       "  reflection intensity " + RenderSettings.reflectionIntensity);

        var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(k_PrefabPath);
        var zombie = Object.Instantiate(prefab);
        yield return null;
        var brain = zombie.GetComponent<EnemyAI>();
        if (brain != null) brain.enabled = false;
        var stab = zombie.GetComponent<ZombieStabilizer>();
        if (stab != null) stab.enabled = false;
        var nav = zombie.GetComponent<UnityEngine.AI.NavMeshAgent>();
        if (nav != null) nav.enabled = false;
        var anim = zombie.GetComponentInChildren<Animator>(true);
        if (anim != null) { anim.SetBool("IsWalking", true); anim.Play("Walk1", 0, 0.3f); }
        for (int i = 0; i < 20; i++) yield return null;

        // FIND a clear sightline instead of assuming one. "+X from the player"
        // ran straight into a warehouse wall and photographed brickwork.
        Vector3 eye = cam.transform.position;
        Vector3 line = Vector3.forward; float clear = 0f;
        for (int a = 0; a < 72; a++)
        {
            var dir = Quaternion.Euler(0f, a * 5f, 0f) * Vector3.forward;
            float run = Physics.Raycast(eye, dir, out var h, 70f) ? h.distance : 70f;
            if (run > clear) { clear = run; line = dir; }
        }
        log.AppendLine("clear sightline " + clear.ToString("F1") + " m on heading " +
                       Quaternion.LookRotation(line).eulerAngles.y.ToString("F0"));

        foreach (float d in new[] { 4f, 10f, 20f, 35f, 55f })
        {
            if (d > clear - 2f) { log.AppendLine("DIST_" + d + "m skipped, no room"); continue; }
            var at = eye + line * d; at.y = 0.05f;
            zombie.transform.position = at;
            zombie.transform.rotation = Quaternion.LookRotation(-line, Vector3.up);
            cam.transform.LookAt(at + Vector3.up * 1.0f);
            yield return null;

            string tag = "DIST_" + Mathf.RoundToInt(d).ToString("00") + "m";
            var pic = ShootWith(cam, tag);

            // CROP the rendered pixels; do NOT zoom the camera. Mip level is
            // chosen from on-screen texel density, so narrowing the FOV to get a
            // closer look would pick a sharper mip and hide anything mip-driven —
            // which is the whole class of defect a distance sweep is looking for.
            // VIEWPORT, not screen. WorldToScreenPoint uses cam.pixelWidth, which
            // is the display, not the 900x1200 RenderTexture this shot went to —
            // the first version cropped a patch of ground because of it.
            var hv = cam.WorldToViewportPoint(at + Vector3.up * 1.7f);
            var fv = cam.WorldToViewportPoint(at);
            var head = new Vector3(hv.x * k_Width, hv.y * k_Height, hv.z);
            var feet = new Vector3(fv.x * k_Width, fv.y * k_Height, fv.z);
            log.AppendLine(tag.PadRight(12) + pic.stats + "   " + SkinTone(pic.pixels) +
                "   on screen " + Mathf.Abs(head.y - feet.y).ToString("F0") + " px tall");
            CropAndSave(pic.pixels, tag + "_crop",
                        Mathf.RoundToInt((head.x + feet.x) * 0.5f),
                        Mathf.RoundToInt((head.y + feet.y) * 0.5f),
                        Mathf.Max(24, Mathf.Abs(head.y - feet.y) * 0.75f));
        }

        File.WriteAllText(Path.Combine(k_OutDir, "distance_report.txt"), log.ToString());
        Debug.Log("[ZombieDiag/dist]\n" + log);
        yield return null;
#endif
    }

    /// <summary>
    /// "Cyan blue" has to be measurable or it is an argument. Reports the mean
    /// colour of the most blue-dominant 2% of pixels, which is the zombie when it
    /// has gone cyan and is the sky when it has not.
    /// </summary>
    static string SkinTone(Color32[] px)
    {
        if (px == null) return "";
        long r = 0, g = 0, b = 0; int n = 0, blueish = 0;
        for (int i = 0; i < px.Length; i += 3)
        {
            var p = px[i];
            n++;
            if (p.b > p.r + 20 && p.b > 40) { blueish++; r += p.r; g += p.g; b += p.b; }
        }
        if (blueish == 0) return "blue-dominant pixels: 0%";
        return string.Format(CultureInfo.InvariantCulture,
            "blue-dominant {0:0.0}% of frame, mean RGB ({1},{2},{3})",
            100.0 * blueish / n, r / blueish, g / blueish, b / blueish);
    }

    /// <summary>
    /// Nearest-neighbour blow-up of a region of an already-rendered frame, so the
    /// pixels shown are exactly the pixels the GPU produced at the real FOV.
    /// </summary>
    static void CropAndSave(Color32[] px, string name, int cx, int cy, float halfH)
    {
        if (px == null) return;
        int half = Mathf.RoundToInt(halfH);
        int x0 = Mathf.Clamp(cx - half, 0, k_Width - 1);
        int x1 = Mathf.Clamp(cx + half, 0, k_Width - 1);
        int y0 = Mathf.Clamp(cy - half, 0, k_Height - 1);
        int y1 = Mathf.Clamp(cy + half, 0, k_Height - 1);
        int w = x1 - x0, h = y1 - y0;
        if (w < 4 || h < 4) return;

        int zoom = Mathf.Clamp(600 / Mathf.Max(w, h), 1, 24);
        var outTex = new Texture2D(w * zoom, h * zoom, TextureFormat.RGBA32, false, false);
        var dst = new Color32[w * zoom * h * zoom];
        for (int y = 0; y < h * zoom; y++)
            for (int x = 0; x < w * zoom; x++)
                dst[y * w * zoom + x] = px[(y0 + y / zoom) * k_Width + (x0 + x / zoom)];
        outTex.SetPixels32(dst);
        outTex.Apply();
        File.WriteAllBytes(Path.Combine(k_OutDir, name + ".png"), outTex.EncodeToPNG());
        Object.DestroyImmediate(outTex);
    }

    /// <summary>
    /// THE COLD-SHADER-CACHE TEST. EditorSettings has m_AsyncShaderCompilation: 1,
    /// and while a variant is still compiling the Editor draws the object in a
    /// solid placeholder colour instead of waiting. Every other test in this file
    /// ran against a warm Library/ShaderCache, which hides it completely.
    ///
    /// Shoot the very first frames the zombie is on screen and report whether
    /// anything was still compiling at the time.
    /// </summary>
    [UnityTest]
    public IEnumerator Zombie_PhotographTheFirstFramesItIsEverOnScreen()
    {
#if !UNITY_EDITOR
        yield break;
#else
        LogAssert.ignoreFailingMessages = true;
        Directory.CreateDirectory(k_OutDir);
        var log = new StringBuilder();
        log.AppendLine("asyncShaderCompilation = " + UnityEditor.EditorSettings.asyncShaderCompilation);

        EditorSceneManager.LoadSceneInPlayMode(
            k_MapPath, new LoadSceneParameters(LoadSceneMode.Single));
        yield return null;
        while (!SceneManager.GetActiveScene().isLoaded) yield return null;

        var cam = Camera.main;
        Assert.IsNotNull(cam, "no MainCamera");
        var fps = Object.FindFirstObjectByType<FPSController>();
        if (fps != null) fps.enabled = false;

        var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(k_PrefabPath);
        var zombie = Object.Instantiate(prefab);
        var brain = zombie.GetComponent<EnemyAI>();
        if (brain != null) brain.enabled = false;
        var stab = zombie.GetComponent<ZombieStabilizer>();
        if (stab != null) stab.enabled = false;
        var nav = zombie.GetComponent<UnityEngine.AI.NavMeshAgent>();
        if (nav != null) nav.enabled = false;

        Vector3 fwd = cam.transform.forward; fwd.y = 0f; fwd.Normalize();
        var at = cam.transform.position + fwd * 3.5f + Vector3.down * 1.5f;
        zombie.transform.position = at;
        zombie.transform.rotation = Quaternion.LookRotation(-fwd, Vector3.up);
        cam.transform.LookAt(at + Vector3.up * 1.0f);

        int[] frames = { 0, 1, 2, 4, 8, 16, 32, 64, 128 };
        int f = 0;
        foreach (int target in frames)
        {
            while (f < target) { yield return null; f++; }
            var pic = ShootWith(cam, "COLD_f" + target.ToString("000"));
            log.AppendLine("frame " + target.ToString("000").PadRight(5) + pic.stats +
                " | compiling=" + UnityEditor.ShaderUtil.anythingCompiling +
                " | " + SkinTone(pic.pixels));
        }

        File.WriteAllText(Path.Combine(k_OutDir, "cold_report.txt"), log.ToString());
        Debug.Log("[ZombieDiag/cold]\n" + log);
        yield return null;
#endif
    }

    /// <summary>
    /// HOW MUCH OF THE ZOMBIE'S COLOUR IS SKY REFLECTION? ZombieM_02_Body has
    /// _Smoothness 1 with the value taken from _MetallicGlossMap's alpha, which
    /// averages 0.60 and peaks 0.92 — near-mirror over parts of the skin, with
    /// _EnvironmentReflections on and the reflection source set to a sunset HDRI
    /// whose zenith is blue. That is a real candidate for a blue wash on a
    /// STANDING figure that goes away when the ragdoll lays it flat.
    ///
    /// Measures the zombie's own pixels with reflections on, off, and with
    /// smoothness forced down. If the numbers barely move, the hypothesis is dead.
    /// </summary>
    [UnityTest]
    public IEnumerator Zombie_HowMuchOfItsColourIsSkyReflection()
    {
#if !UNITY_EDITOR
        yield break;
#else
        LogAssert.ignoreFailingMessages = true;
        Directory.CreateDirectory(k_OutDir);
        var log = new StringBuilder();

        EditorSceneManager.LoadSceneInPlayMode(
            k_MapPath, new LoadSceneParameters(LoadSceneMode.Single));
        yield return null;
        while (!SceneManager.GetActiveScene().isLoaded) yield return null;
        for (int i = 0; i < 10; i++) yield return null;

        var cam = Camera.main;
        var fps = Object.FindFirstObjectByType<FPSController>();
        if (fps != null) fps.enabled = false;

        var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(k_PrefabPath);
        var zombie = Object.Instantiate(prefab);
        var brain = zombie.GetComponent<EnemyAI>();
        if (brain != null) brain.enabled = false;
        var stab = zombie.GetComponent<ZombieStabilizer>();
        if (stab != null) stab.enabled = false;
        var nav = zombie.GetComponent<UnityEngine.AI.NavMeshAgent>();
        if (nav != null) nav.enabled = false;
        var anim = zombie.GetComponentInChildren<Animator>(true);
        if (anim != null) { anim.SetBool("IsWalking", true); anim.Play("Walk1", 0, 0.3f); }

        // Well clear of the map so the ONLY thing behind it is sky, and the only
        // thing it can reflect is sky. Worst case for this hypothesis.
        var at = cam.transform.position + Vector3.up * 25f + cam.transform.forward * 3.5f;
        zombie.transform.position = at;
        zombie.transform.rotation = Quaternion.LookRotation(
            -new Vector3(cam.transform.forward.x, 0f, cam.transform.forward.z).normalized, Vector3.up);
        cam.transform.LookAt(at + Vector3.up * 1.0f);
        for (int i = 0; i < 20; i++) yield return null;

        var body = zombie.GetComponentsInChildren<SkinnedMeshRenderer>()
                         .Select(s => s.sharedMaterial)
                         .FirstOrDefault(m => m != null && m.name.Contains("Body"));
        Assert.IsNotNull(body, "no Body material found");
        float savedSmooth = body.GetFloat("_Smoothness");
        float savedRefl = RenderSettings.reflectionIntensity;
        log.AppendLine("_Smoothness as authored = " + savedSmooth +
                       ", _GlossMapScale = " + body.GetFloat("_GlossMapScale") +
                       ", _Metallic = " + body.GetFloat("_Metallic"));

        yield return null;
        log.AppendLine("as authored          " + Subject(ShootWith(cam, "REFL_A_authored")));

        RenderSettings.reflectionIntensity = 0f;
        yield return null;
        log.AppendLine("reflections OFF      " + Subject(ShootWith(cam, "REFL_B_no_reflections")));

        RenderSettings.reflectionIntensity = savedRefl;
        body.SetFloat("_Smoothness", 0.3f);
        body.SetFloat("_GlossMapScale", 0.3f);
        yield return null;
        log.AppendLine("smoothness 0.3       " + Subject(ShootWith(cam, "REFL_C_smoothness_0_3")));

        body.SetFloat("_Smoothness", savedSmooth);
        body.SetFloat("_GlossMapScale", 1f);

        File.WriteAllText(Path.Combine(k_OutDir, "reflection_report.txt"), log.ToString());
        Debug.Log("[ZombieDiag/refl]\n" + log);
        yield return null;
#endif
    }

    /// <summary>Mean colour of the non-sky pixels, i.e. the zombie.</summary>
    static string Subject(Pic p)
    {
        long r = 0, g = 0, b = 0; int n = 0;
        foreach (var c in p.pixels)
        {
            // The sky here is a bright pale wash; the subject is everything darker.
            if (c.r + c.g + c.b > 450) continue;
            r += c.r; g += c.g; b += c.b; n++;
        }
        if (n == 0) return "no subject pixels";
        return string.Format(CultureInfo.InvariantCulture,
            "subject {0:0.0}% of frame, mean RGB ({1},{2},{3})  B-R = {4}",
            100.0 * n / p.pixels.Length, r / n, g / n, b / n, (b - r) / n);
    }

    struct BoneSample { public string name; public Vector3 pos; public Vector3 scale; }

    static BoneSample[] SampleBones(SkinnedMeshRenderer[] smrs)
    {
        if (smrs.Length == 0) return new BoneSample[0];
        var bones = smrs[0].bones;
        int n = Mathf.Min(8, bones.Length);
        var outp = new BoneSample[n];
        for (int i = 0; i < n; i++)
        {
            var b = bones[i * bones.Length / n];
            outp[i] = new BoneSample
            {
                name = b != null ? b.name : "<null>",
                pos = b != null ? b.position : Vector3.zero,
                scale = b != null ? b.lossyScale : Vector3.zero,
            };
        }
        return outp;
    }

    struct Pic { public string stats; public Color32[] pixels; }

    static string Diff(Color32[] a, Color32[] b)
    {
        if (a == null || b == null || a.Length != b.Length) return "n/a";
        int n = 0;
        for (int i = 0; i < a.Length; i++)
            if (Mathf.Abs(a[i].r - b[i].r) + Mathf.Abs(a[i].g - b[i].g) +
                Mathf.Abs(a[i].b - b[i].b) > 12) n++;
        return (100.0 * n / a.Length).ToString("F2", CultureInfo.InvariantCulture);
    }

    /// <summary>Shoot with an existing camera, restoring its targetTexture.</summary>
    static Pic ShootWith(Camera cam, string name)
    {
        var saved = cam.targetTexture;
        try { return Shoot(cam, name); }
        finally { cam.targetTexture = saved; }
    }

    static Pic Shoot(Camera cam, string name)
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
            File.WriteAllBytes(Path.Combine(k_OutDir, name + ".png"), bytes);

            var pixels = tex.GetPixels32();
            double sum = 0;
            var unique = new System.Collections.Generic.HashSet<int>();
            for (int i = 0; i < pixels.Length; i += 7)
            {
                var p = pixels[i];
                sum += 0.2126 * p.r + 0.7152 * p.g + 0.0722 * p.b;
                unique.Add((p.r >> 3 << 10) | (p.g >> 3 << 5) | (p.b >> 3));
            }
            int sampled = (pixels.Length + 6) / 7;
            return new Pic
            {
                stats = string.Format(CultureInfo.InvariantCulture,
                    "{0} KB, mean luma {1:0.0}/255, {2} distinct colours",
                    bytes.Length / 1024, sum / sampled, unique.Count),
                pixels = pixels,
            };
        }
        finally
        {
            Object.DestroyImmediate(tex);
            rt.Release();
            Object.DestroyImmediate(rt);
        }
    }
}
