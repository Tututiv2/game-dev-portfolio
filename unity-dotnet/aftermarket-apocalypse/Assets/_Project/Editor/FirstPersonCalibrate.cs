using System.Collections.Generic;
using System.IO;
using System.Text;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Sweep the arm muscles and report where the hand actually lands.
///
/// <para>Unity's muscle axis names name the JOINT, not the direction of travel.
/// "Left Arm Front-Back" does not say whether +1 is front. The first pose was
/// written from the names and put every character's hands 63% of a screen below
/// the frame with the fingertips BEHIND the camera — on all seven, identically,
/// so it looked like a systematic bug rather than a wrong guess.</para>
///
/// <para>So the values are solved rather than chosen: this drives one axis at a
/// time across its whole range and prints the hand's viewport position at each
/// step. Reading the table off is how <see cref="FirstPersonPoses"/> got its
/// numbers, and re-running it is how to re-derive them if a rig changes.</para>
/// </summary>
public static class FirstPersonCalibrate
{
    const string k_Catalog = "Assets/_Project/Content/Characters/CharacterCatalog.asset";

    /// <summary>
    /// Grid-search the three axes that carry "hands in front of you" and report
    /// the combinations that land the hand nearest a target on screen.
    ///
    /// <para>The one-axis-at-a-time sweep below establishes DIRECTION, which is
    /// what the muscle names fail to say. It cannot give the values, because the
    /// three interact: raising the arm changes what bending the elbow does. So
    /// the sweep answers "which way" and this answers "how far".</para>
    /// </summary>
    [MenuItem("Tools/Aftermarket/Solve First-Person Arm Pose")]
    public static void Solve()
    {
        var catalog = AssetDatabase.LoadAssetAtPath<CharacterCatalog>(k_Catalog);
        if (catalog == null) { Debug.LogError("[FPSolve] no catalog"); return; }

        // Target in CAMERA-LOCAL METRES, not viewport coordinates.
        //
        // Viewport was the wrong objective and it solved to a wrong pose that
        // scored perfectly: both hands on screen, 0.135 m apart, meeting in the
        // middle with the fingers pointing at each other. Screen position says
        // nothing about separation, and separation is half of what "open hands
        // in front of you" means. In metres it is stated directly: x is half the
        // gap between the hands, y is below eye level, z is reach.
        // y is SOLVED FROM THE FRUSTUM, not picked. At 60 deg horizontal on
        // 16:9 the vertical FOV is 36 deg, so at 0.45 m the visible half-height
        // is only 0.146 m -- and the first target of -0.20 m put the hands
        // BELOW the bottom edge while the solver reported a perfect score.
        // -0.07 m lands them about a quarter up the frame.
        var target = new Vector3(0.17f, -0.07f, 0.45f);

        var root = new GameObject("FPSolve");
        var holder = new GameObject("CameraHolder").transform;
        holder.SetParent(root.transform, false);
        var sb = new StringBuilder();
        try
        {
            var body = root.AddComponent<FirstPersonBody>();
            body.catalog = catalog;
            body.cameraHolder = holder;
            PlayerPrefs.SetString("SelectedCharacter", "inquisitor");
            if (!body.Spawn()) { Debug.LogError("[FPSolve] spawn refused"); return; }

            var camGo = new GameObject("solve_cam");
            camGo.transform.SetParent(holder, false);
            camGo.transform.localPosition = Vector3.up * body.eyeHeight;
            var cam = camGo.AddComponent<Camera>();
            cam.fieldOfView = 60f;
            cam.nearClipPlane = 0.02f;

            var hand = body.Animator.GetBoneTransform(HumanBodyBones.RightHand);
            var best = new List<KeyValuePair<float, string>>();
            var pose = new FirstPersonPoses.Muscle[3];
            float bestErr = float.MaxValue;
            Vector3 bestValues = Vector3.zero;

            for (float up = -0.25f; up <= 1.001f; up += 0.125f)
            for (float fb = -1f; fb <= 0.251f; fb += 0.125f)
            for (float st = -1f; st <= 0.251f; st += 0.125f)
            {
                pose[0] = new FirstPersonPoses.Muscle("Right Arm Down-Up", up);
                pose[1] = new FirstPersonPoses.Muscle("Right Arm Front-Back", fb);
                pose[2] = new FirstPersonPoses.Muscle("Right Forearm Stretch", st);
                body.ApplyPose(pose);
                body.PushPose();

                Vector3 p = cam.transform.InverseTransformPoint(hand.position);
                if (p.z <= 0.15f) continue;                 // behind, or inside the face
                float err = (p - target).sqrMagnitude;
                if (err < bestErr) { bestErr = err; bestValues = new Vector3(up, fb, st); }
                best.Add(new KeyValuePair<float, string>(
                    err, $"up {up,5:F3}  frontback {fb,5:F3}  stretch {st,5:F3}  -> " +
                         $"cam-local ({p.x,5:F3},{p.y,5:F3},{p.z,5:F3}) m   " +
                         $"gap {p.x * 2f,5:F3} m   err {Mathf.Sqrt(err),6:F4}"));
            }

            best.Sort((a, b) => a.Key.CompareTo(b.Key));

            // --- second pass: point the fingers FORWARD --------------------
            // Position and orientation are independent problems and the twist
            // axes barely move the hand, so solving them together would let a
            // tiny position gain buy a badly turned wrist. The winning position
            // is locked first, then the wrist is solved against it.
            var bestPos = bestValues;
            var handBone = body.Animator.GetBoneTransform(HumanBodyBones.RightHand);
            var tipBone = body.Animator.GetBoneTransform(HumanBodyBones.RightMiddleDistal);
            float bestFwd = -2f;
            string bestTwist = "(none)";
            var pose5 = new FirstPersonPoses.Muscle[5];
            for (float ft = -1f; ft <= 1.001f; ft += 0.125f)
            for (float hio = -1f; hio <= 1.001f; hio += 0.25f)
            for (float hdu = -0.5f; hdu <= 0.501f; hdu += 0.25f)
            {
                pose5[0] = new FirstPersonPoses.Muscle("Right Arm Down-Up", bestPos.x);
                pose5[1] = new FirstPersonPoses.Muscle("Right Arm Front-Back", bestPos.y);
                pose5[2] = new FirstPersonPoses.Muscle("Right Forearm Stretch", bestPos.z);
                pose5[3] = new FirstPersonPoses.Muscle("Right Forearm Twist In-Out", ft);
                pose5[4] = new FirstPersonPoses.Muscle("Right Hand In-Out", hio);
                body.ApplyPose(pose5);
                body.ApplyPose(new[] { new FirstPersonPoses.Muscle("Right Hand Down-Up", hdu) });
                body.PushPose();
                Vector3 d = cam.transform.InverseTransformDirection(
                    (tipBone.position - handBone.position).normalized);
                if (d.z > bestFwd)
                {
                    bestFwd = d.z;
                    bestTwist = $"forearm twist {ft,5:F3}  hand in-out {hio,5:F3}  " +
                                $"hand down-up {hdu,5:F3}  -> fingers point right {d.x,5:F2} " +
                                $"up {d.y,5:F2} forward {d.z,5:F2}";
                }
            }
            sb.AppendLine();
            sb.AppendLine("Wrist, solved against the winning position:");
            sb.AppendLine("  " + bestTwist);
            sb.AppendLine($"Solving for the RIGHT hand at camera-local {target} m " +
                          $"(a {target.x * 2f:F2} m gap between the hands). " +
                          $"{best.Count} reachable combinations.");
            sb.AppendLine();
            for (int i = 0; i < Mathf.Min(12, best.Count); i++) sb.AppendLine("  " + best[i].Value);
        }
        finally
        {
            Object.DestroyImmediate(root);
        }

        Debug.Log("[FPSolve]\n" + sb);
    }

    [MenuItem("Tools/Aftermarket/Calibrate First-Person Arms")]
    public static void Run()
    {
        var catalog = AssetDatabase.LoadAssetAtPath<CharacterCatalog>(k_Catalog);
        if (catalog == null) { Debug.LogError("[FPCal] no catalog"); return; }

        var root = new GameObject("FPCal");
        var holder = new GameObject("CameraHolder").transform;
        holder.SetParent(root.transform, false);
        var sb = new StringBuilder();
        try
        {
            var body = root.AddComponent<FirstPersonBody>();
            body.catalog = catalog;
            body.cameraHolder = holder;
            PlayerPrefs.SetString("SelectedCharacter", "inquisitor");
            if (!body.Spawn()) { Debug.LogError("[FPCal] spawn refused"); return; }

            var camGo = new GameObject("cal_cam");
            camGo.transform.SetParent(holder, false);
            camGo.transform.localPosition = Vector3.up * body.eyeHeight;
            var cam = camGo.AddComponent<Camera>();
            cam.fieldOfView = 60f;
            cam.nearClipPlane = 0.02f;

            sb.AppendLine("Right hand viewport position while sweeping ONE muscle at a time.");
            sb.AppendLine("Target: x,y inside 0..1 and z roughly 0.35-0.60 m in front.");
            sb.AppendLine();

            string[] axes =
            {
                "Right Arm Down-Up",
                "Right Arm Front-Back",
                "Right Forearm Stretch",
                "Right Shoulder Down-Up",
                "Right Shoulder Front-Back",
                "Right Arm Twist In-Out",
            };

            foreach (string axis in axes)
            {
                sb.AppendLine(axis);
                for (float v = -1f; v <= 1.001f; v += 0.25f)
                {
                    // Everything else neutral, so the axis under test is the only
                    // thing moving. Sweeping from an already-posed arm measures
                    // the interaction, not the axis.
                    var pose = new List<FirstPersonPoses.Muscle> { new FirstPersonPoses.Muscle(axis, v) };
                    foreach (string other in axes)
                        if (other != axis) pose.Add(new FirstPersonPoses.Muscle(other, 0f));

                    body.ApplyPose(pose.ToArray());
                    body.PushPose();

                    var t = body.Animator.GetBoneTransform(HumanBodyBones.RightHand);
                    Vector3 p = cam.WorldToViewportPoint(t.position);
                    sb.AppendLine($"   {v,5:F2} -> viewport ({p.x,6:F3},{p.y,6:F3})  {p.z,6:F2} m");
                }
                sb.AppendLine();
            }
        }
        finally
        {
            Object.DestroyImmediate(root);
        }

        string dir = Path.Combine(Directory.GetParent(Application.dataPath).FullName,
                                  "Screenshots", "firstperson");
        Directory.CreateDirectory(dir);
        File.WriteAllText(Path.Combine(dir, "calibration.txt"), sb.ToString());
        Debug.Log("[FPCal]\n" + sb);
    }
}
