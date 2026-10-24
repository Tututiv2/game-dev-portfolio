using System.Collections.Generic;
using System.IO;
using System.Text;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Photograph the first-person body, from the player's own eyes and from
/// outside, and MEASURE whether the hands are actually on screen.
///
/// <para>This exists before the feature is finished, on purpose. Unity's muscle
/// axis names describe the JOINT and not the direction of travel — "Arm
/// Front-Back" does not say which sign is front — so the pose in
/// <see cref="FirstPersonPoses"/> cannot be written correctly from the
/// documentation. It has to be looked at. Guessing produced a character reaching
/// backwards through their own chest, which from inside the head looks exactly
/// like no hands at all.</para>
///
/// <para>It measures as well as renders, because "I can see hands" is the one
/// thing a render of a first-person view cannot settle: an empty frame and a
/// frame with the hands just off the bottom edge look identical. The report
/// projects each hand bone into viewport space, so "off screen" is a number and
/// says which way.</para>
/// </summary>
public static class FirstPersonShot
{
    const string k_Catalog = "Assets/_Project/Content/Characters/CharacterCatalog.asset";
    static string ShotDir =>
        Path.Combine(Directory.GetParent(Application.dataPath).FullName, "Screenshots", "firstperson");

    [MenuItem("Tools/Aftermarket/Capture First-Person Hands")]
    public static void CaptureBatch()
    {
        // Without a graphics device every PNG is a flat fill that looks exactly
        // like a correct render on disk. Refuse rather than write seven of them.
        if (SystemInfo.graphicsDeviceType == UnityEngine.Rendering.GraphicsDeviceType.Null)
        {
            Debug.LogError("[FPShot] null graphics device — re-run WITHOUT -nographics.");
            return;
        }

        var catalog = AssetDatabase.LoadAssetAtPath<CharacterCatalog>(k_Catalog);
        if (catalog == null) { Debug.LogError("[FPShot] no CharacterCatalog at " + k_Catalog); return; }

        Directory.CreateDirectory(ShotDir);

        // The first version had no lights and no ambient at all, and produced
        // seven perfectly uniform frames while the report cheerfully said 4/4
        // hand points were on screen. Both were true: the hands WERE in the
        // frustum, and nothing lit them. A geometric check cannot see that,
        // which is why Render() now measures the pixels as well.
        var prevMode = RenderSettings.ambientMode;
        var prevAmbient = RenderSettings.ambientLight;
        var prevSky = RenderSettings.skybox;
        RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Flat;
        RenderSettings.ambientLight = new Color(0.38f, 0.38f, 0.40f, 1f);
        RenderSettings.skybox = null;
        var lights = MakeLights();
        var report = new StringBuilder();
        report.AppendLine("First-person body — eye view and third person.");
        report.AppendLine("Hand positions are VIEWPORT coordinates from the player's own camera:");
        report.AppendLine("  x,y in 0..1 is on screen; z is metres in front (negative = BEHIND the camera).");
        report.AppendLine();

        int shot = 0;
        foreach (var def in catalog.characters)
        {
            if (def == null || def.prefab == null) continue;

            var root = new GameObject("FPProbe_" + def.characterId);
            var holder = new GameObject("CameraHolder").transform;
            holder.SetParent(root.transform, false);
            try
            {
                // The probe drives the REAL component. A shot rig that posed the
                // rig its own way would photograph itself rather than the thing
                // that ships — which is how the map's "player eye" shot spent a
                // whole session rendering from inside a wall.
                var body = root.AddComponent<FirstPersonBody>();
                body.catalog = catalog;
                body.handPose = AssetDatabase.LoadAssetAtPath<HandPoseSettings>(
                    "Assets/_Project/Content/HandPose.asset");
                body.cameraHolder = holder;
                PlayerPrefs.SetString("SelectedCharacter", def.characterId);

                // The camera must exist BEFORE Spawn: the arms copy is parented
                // to it, and the hand targets are camera-space. Built after, the
                // arms hang off the holder instead and every measurement is of
                // the wrong rig.
                var cam = MakeCamera(holder, body.eyeHeight);

                if (!body.Spawn())
                {
                    report.AppendLine($"{def.characterId,-12} SKIPPED — Spawn() refused; see the " +
                                      "console for which of avatar/humanoid/prefab is wrong.");
                    continue;
                }
                body.ResolveEye();
                body.ApplySettings();
                body.CalibrateBend();
                body.CalibrateSpine();
                string pixels = Render(cam, Path.Combine(ShotDir, $"fp_{def.characterId}_eye.png"), 1280, 720);
                shot++;

                Measure(report, def.characterId, body, cam);
                report.AppendLine("    eye view: " + pixels);
                report.AppendLine();

                // ELBOW STABILITY across pitch — the owner's report was "when
                // looking up and down my elbows go in and out". The hands are
                // camera-space so they never move on screen; the elbow is the
                // joint that absorbs whatever the shoulders fail to.
                var elbow = body.ArmsAnimator.GetBoneTransform(HumanBodyBones.RightLowerArm);
                if (elbow != null)
                {
                    var seen = new List<Vector3>();
                    foreach (float p in new[] { -40f, -20f, 0f, 20f, 40f })
                    {
                        holder.localRotation = Quaternion.Euler(p, 0f, 0f);
                        body.PushPose();
                        seen.Add(cam.transform.InverseTransformPoint(elbow.position));
                    }
                    float spread = 0f;
                    for (int i = 1; i < seen.Count; i++)
                        spread = Mathf.Max(spread, Vector3.Distance(seen[i], seen[0]));
                    report.AppendLine($"    elbow moves {spread:F3} m over +/-40deg of pitch " +
                                      "(camera-relative; lower is steadier)");
                    holder.localRotation = Quaternion.identity;
                    body.PushPose();
                }

                // LOOKING DOWN. The owner's report was "my body phases when I
                // look down" — the camera sits at the head bone, which is the
                // MIDDLE of the skull, so pitching down drives the chest through
                // the near plane. A forward-looking shot cannot see that at all,
                // which is why this view exists.
                holder.localRotation = Quaternion.Euler(55f, 0f, 0f);
                body.PushPose();
                report.AppendLine("    look down 55deg: " + Render(cam,
                    Path.Combine(ShotDir, $"fp_{def.characterId}_down.png"), 1280, 720));
                holder.localRotation = Quaternion.identity;
                body.PushPose();

                // Third person, head restored, so the whole body can be judged.
                body.ShowHead();
                body.PushPose();
                var outside = MakeCamera(null, 0f);
                outside.transform.position = root.transform.position +
                                             new Vector3(1.9f, 1.35f, 2.1f);
                outside.transform.LookAt(root.transform.position + Vector3.up * 1.05f);
                report.AppendLine("    body view: " + Render(outside, Path.Combine(ShotDir, $"fp_{def.characterId}_body.png"), 900, 1200));
                Object.DestroyImmediate(outside.gameObject);
                Object.DestroyImmediate(cam.gameObject);
            }
            finally
            {
                Object.DestroyImmediate(root);
            }
        }

        foreach (var l in lights) Object.DestroyImmediate(l);
        RenderSettings.ambientMode = prevMode;
        RenderSettings.ambientLight = prevAmbient;
        RenderSettings.skybox = prevSky;

        File.WriteAllText(Path.Combine(ShotDir, "report.txt"), report.ToString());
        Debug.Log($"[FPShot] {shot} character(s) photographed -> {ShotDir}\n" + report);
    }

    /// <summary>
    /// Built to match MapSeeder EXACTLY: the holder carries the eye height and
    /// the camera sits on it at zero, so pitching the holder turns the camera in
    /// place.
    ///
    /// <para>The first version hung the camera ABOVE the holder. Forward-facing
    /// shots were identical, so it looked correct — but pitching then ORBITED
    /// the camera around the player's feet instead of turning the head, and the
    /// look-down view came back blank. That reads as "the body phases when you
    /// look down", which is exactly the bug it was built to investigate. An
    /// instrument that does not match the rig it measures invents its own
    /// findings.</para>
    /// </summary>
    static Camera MakeCamera(Transform parent, float height)
    {
        var go = new GameObject("fp_cam");
        if (parent != null)
        {
            parent.localPosition = Vector3.up * height;
            go.transform.SetParent(parent, false);
            go.transform.localPosition = Vector3.zero;
        }
        var cam = go.AddComponent<Camera>();
        cam.fieldOfView = 60f;
        // A view model sits well inside the default 0.3 m near plane. That
        // exact default cut 0.17 m off both existing weapons for five phases,
        // so it is set explicitly here rather than inherited.
        cam.nearClipPlane = 0.02f;
        cam.farClipPlane = 200f;
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.13f, 0.13f, 0.16f);
        return cam;
    }

    static void Measure(StringBuilder report, string id, FirstPersonBody body, Camera cam)
    {
        var bones = new Dictionary<string, HumanBodyBones>
        {
            { "left hand", HumanBodyBones.LeftHand },
            { "right hand", HumanBodyBones.RightHand },
            { "left index tip", HumanBodyBones.LeftIndexDistal },
            { "right index tip", HumanBodyBones.RightIndexDistal },
        };

        report.AppendLine(id);
        int onScreen = 0;
        foreach (var kv in bones)
        {
            var t = body.ArmsAnimator.GetBoneTransform(kv.Value);
            if (t == null) { report.AppendLine($"    {kv.Key,-16} MISSING from this rig"); continue; }
            Vector3 v = cam.WorldToViewportPoint(t.position);
            bool visible = v.z > 0f && v.x > 0f && v.x < 1f && v.y > 0f && v.y < 1f;
            if (visible) onScreen++;
            report.AppendLine($"    {kv.Key,-16} viewport ({v.x,6:F3},{v.y,6:F3}) {v.z,6:F2} m  " +
                              (visible ? "ON SCREEN" : Why(v)));
        }
        report.AppendLine($"    -> {onScreen}/{bones.Count} hand points on screen");

        // Finger CHAIN LENGTHS. These are fixed by the skeleton and cannot
        // change with pose, so they separate "the art is wrong" from "your pose
        // is wrong" -- which is not a distinction any screenshot can make.
        var chains = new[]
        {
            new { name = "thumb", a = HumanBodyBones.RightThumbProximal,
                  b = HumanBodyBones.RightThumbIntermediate, c = HumanBodyBones.RightThumbDistal },
            new { name = "index", a = HumanBodyBones.RightIndexProximal,
                  b = HumanBodyBones.RightIndexIntermediate, c = HumanBodyBones.RightIndexDistal },
            new { name = "middle", a = HumanBodyBones.RightMiddleProximal,
                  b = HumanBodyBones.RightMiddleIntermediate, c = HumanBodyBones.RightMiddleDistal },
        };
        var wrist = body.ArmsAnimator.GetBoneTransform(HumanBodyBones.RightHand);
        foreach (var ch in chains)
        {
            var a = body.ArmsAnimator.GetBoneTransform(ch.a);
            var bb = body.ArmsAnimator.GetBoneTransform(ch.b);
            var c = body.ArmsAnimator.GetBoneTransform(ch.c);
            if (a == null || bb == null || c == null) continue;
            float chain = Vector3.Distance(a.position, bb.position)
                        + Vector3.Distance(bb.position, c.position);
            float fromWrist = wrist == null ? 0f : Vector3.Distance(wrist.position, a.position);
            report.AppendLine($"    {ch.name,-7} chain {chain * 100f:F1} cm, root sits " +
                              $"{fromWrist * 100f:F1} cm from the wrist");
        }

        // Position is not enough. The first posed render put both hands on
        // screen and had them meeting in the middle with the fingers pointing at
        // each other -- every number above was green. Separation and pointing
        // direction are what "open hands in front of you" actually means.
        var lh = body.ArmsAnimator.GetBoneTransform(HumanBodyBones.LeftHand);
        var rh = body.ArmsAnimator.GetBoneTransform(HumanBodyBones.RightHand);
        if (lh != null && rh != null)
            report.AppendLine($"    hands are {Vector3.Distance(lh.position, rh.position):F3} m apart " +
                              "(want roughly 0.30-0.40)");

        foreach (var pair in new[]
        {
            new { side = "left", hand = HumanBodyBones.LeftHand, tip = HumanBodyBones.LeftMiddleDistal },
            new { side = "right", hand = HumanBodyBones.RightHand, tip = HumanBodyBones.RightMiddleDistal },
        })
        {
            var h = body.ArmsAnimator.GetBoneTransform(pair.hand);
            var t = body.ArmsAnimator.GetBoneTransform(pair.tip);
            if (h == null || t == null) continue;
            Vector3 d = cam.transform.InverseTransformDirection((t.position - h.position).normalized);
            report.AppendLine($"    {pair.side,-5} fingers point  right {d.x,6:F2}  up {d.y,6:F2}  " +
                              $"forward {d.z,6:F2}   (want forward near +1)");
        }
        report.AppendLine();
    }

    static string Why(Vector3 v)
    {
        if (v.z <= 0f) return "BEHIND THE CAMERA";
        if (v.y <= 0f) return "below frame";
        if (v.y >= 1f) return "above frame";
        return v.x <= 0f ? "off frame left" : "off frame right";
    }

    static GameObject[] MakeLights()
    {
        var made = new List<GameObject>();
        var rigs = new[]
        {
            new { rot = new Vector3(28f, 18f, 0f), intensity = 1.5f },
            new { rot = new Vector3(14f, -140f, 0f), intensity = 0.7f },
        };
        foreach (var r in rigs)
        {
            var go = new GameObject("FPShotLight") { hideFlags = HideFlags.HideAndDontSave };
            go.transform.rotation = Quaternion.Euler(r.rot);
            var l = go.AddComponent<Light>();
            l.type = LightType.Directional;
            l.intensity = r.intensity;
            l.color = Color.white;
            made.Add(go);
        }
        return made.ToArray();
    }

    /// <summary>
    /// Render, and report what is actually ON the image. A blank frame and a
    /// good one are the same size on disk and look identical in a file listing;
    /// "1 distinct colour" is the tell. This project has written that lesson
    /// down twice and this tool still shipped without it once.
    /// </summary>
    static string Render(Camera cam, string path, int w, int h)
    {
        var rt = new RenderTexture(w, h, 24, RenderTextureFormat.ARGB32) { antiAliasing = 4 };
        cam.targetTexture = rt;
        cam.Render();
        RenderTexture.active = rt;
        var tex = new Texture2D(w, h, TextureFormat.RGB24, false);
        tex.ReadPixels(new Rect(0, 0, w, h), 0, 0);
        tex.Apply();
        File.WriteAllBytes(path, tex.EncodeToPNG());

        var px = tex.GetPixels32();
        var seen = new HashSet<int>();
        double luma = 0;
        for (int i = 0; i < px.Length; i += 7)      // every 7th pixel is plenty
        {
            seen.Add((px[i].r >> 3) << 10 | (px[i].g >> 3) << 5 | (px[i].b >> 3));
            luma += 0.2126 * px[i].r + 0.7152 * px[i].g + 0.0722 * px[i].b;
        }
        string stats = $"mean luma {luma / (px.Length / 7) / 255.0:F3}, {seen.Count} distinct colours"
                     + (seen.Count <= 2 ? "   <-- BLANK, nothing rendered" : "");

        RenderTexture.active = null;
        cam.targetTexture = null;
        Object.DestroyImmediate(tex);
        rt.Release();
        Object.DestroyImmediate(rt);
        return stats;
    }
}
