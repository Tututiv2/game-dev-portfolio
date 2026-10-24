using System.Collections.Generic;
using System.Linq;
using UnityEditor;
using UnityEditor.Animations;
using UnityEngine;

/// <summary>
/// Turns the carried-over ZombieM_02 art into something
/// <see cref="EnemySpawner"/> can instantiate: an AnimatorController that speaks
/// <see cref="EnemyAI"/>'s language, a ragdoll, and the Enemy prefab.
///
/// <para><b>The art is carried, not authored.</b> REBUILD_SPEC.md §1.4 lists
/// ZombieM_02 as Load-bearing carry-over. <c>Tools/import_zombie.py</c> does the
/// copying (and the TGA -> PNG re-encode, pixel-verified); this does everything
/// that needs the Unity API.</para>
///
/// <para><b>The pack DOES ship a URP variant.</b> It was believed to be Built-in
/// and HDRP only, because its URP materials are named <c>Buit-in_*</c> — the
/// vendor's own typo, inside a folder called <c>URP</c>. Their shader GUID is
/// <c>933532a4fcc9baf4fa0491de14d08ed7</c>, which is URP/Lit — the same shader
/// this project's own kit materials use. So no shader conversion was needed and
/// none was done. The names are fixed on the way in.</para>
///
/// <para><b>What actually had to be built is the Animator.</b> The pack's own
/// <c>ZombieM_02 Animator Controller</c> has <b>zero parameters</b> — it is a
/// showcase reel, not a game controller. <c>EnemyAI</c> drives six:
/// <c>IsWalking</c>, <c>IsRunning</c>, <c>IsSprinting</c>, <c>Attack</c>,
/// <c>AttackIndex</c> and <c>WalkIndex</c>. Setting a parameter an Animator does
/// not have is not a silent no-op — it logs an error per call, so a wrong
/// controller is a wall of console spam and a zombie frozen in T-pose.</para>
///
/// <para><b>And the ragdoll.</b> <c>EnemyAI.Start</c> logs an error when it finds
/// fewer than two Rigidbodies, and <c>Die()</c> hands the corpse to
/// <c>SetRagdollState(true)</c>. Unity's Ragdoll Wizard is an interactive editor
/// window and cannot be scripted, so the bodies, colliders and CharacterJoints
/// are built here from the skeleton's own measurements.</para>
/// </summary>
public static class ZombieImporter
{
    public const string ArtRoot = "Assets/_Project/Art/Zombie";
    public const string ControllerPath = ArtRoot + "/ZombieM_02.controller";
    public const string EnemyPrefabPath = ArtRoot + "/Enemy.prefab";

    /// <summary>
    /// The three looks are 1 MOSS (olive green, blue denim), 2 ICE (cold
    /// blue-grey, pale frost-white spurs) and 3 TOXIC (near-black with glowing
    /// green crystals). <b>None of them is an "ordinary" zombie</b> — this
    /// comment used to say skin 2 was, and that description was written without
    /// anyone opening Screenshots/zombie/skin2/front.png.
    ///
    /// <para>Skin 2 is the primary for one reason only, and it is a reference
    /// reason rather than an art one: <c>AshfallDepot.unity</c>'s spawner already
    /// points at the unsuffixed <c>Enemy.prefab</c>. See
    /// <see cref="PrefabPathForSkin"/>.</para>
    /// </summary>
    public const int PrimarySkin = 2;

    /// <summary>
    /// What each skin actually looks like, read off
    /// <c>Screenshots/zombie/skin&lt;N&gt;/front.png</c>. One address, so a look
    /// cannot be described two different ways in two files —
    /// <c>Tools/import_zombie.py</c>'s <c>SKIN_NAMES</c> is the other copy and
    /// <c>ZombieSkinTests</c> holds them to each other and to the textures.
    /// </summary>
    public static string SkinLook(int skin)
    {
        switch (skin)
        {
            // Named from the ALBEDO, not from a render. The mean _BaseMap RGB
            // Unity reports is in brackets — see ZombieSkinAudit.
            case 1: return "ICE / blue albedo (0.26,0.32,0.39) — CURRENTLY RENDERS OLIVE, blue channel dead";
            case 2: return "BONE / warm pale albedo (0.46,0.41,0.39), reads cool under the map's sky";
            case 3: return "TOXIC / green albedo (0.40,0.45,0.39) with a green emissive";
            default: return "unknown skin " + skin;
        }
    }

    /// <summary>
    /// The head collider must carry this tag or headshots never register —
    /// <c>GunSystem.ApplyHit</c> decides with a bare
    /// <c>hit.collider.CompareTag("Head")</c>, so no tag means no 130-Essence
    /// kills, no camo progression and no ding, with nothing logged.
    /// </summary>
    const string k_HeadTag = "Head";

    /// <summary>Every gun's <c>whatIsEnemy</c> mask is set to this layer by
    /// <see cref="WeaponRigBuilder"/>, so the zombie has to actually be on it.</summary>
    public const string k_EnemyLayer = "Enemy";

    [MenuItem("Tools/Aftermarket/Import Zombie (controller + ragdoll + prefab)")]
    public static void ImportMenu()
    {
        Import();
    }

    /// <summary>
    /// Returns false when nothing was built, so a caller can STOP.
    ///
    /// <para>It used to return void, and that mattered: when the mesh lookup
    /// failed, <c>BuildAll</c> carried straight on to <c>CoreLoopBuilder.Build</c>,
    /// which re-wired the spawner to the PREVIOUS run's <c>Enemy.prefab</c> — by
    /// then pointing at a deleted mesh, with no renderers at all. The build
    /// reported success. A step that can fail silently and a caller that cannot
    /// hear it is the same defect twice.</para>
    /// </summary>
    public static bool Import()
    {
        var models = FindImportedMeshes();
        if (models.Count == 0) return false;

        var controller = BuildController();
        if (controller == null) return false;

        // ONE PREFAB PER IMPORTED SKIN. Owner's decision 2026-07-31: "add ALL
        // skins for zombies", superseding the earlier "skin 2 only".
        //
        // The controller is SHARED by all of them and that is not a shortcut —
        // the three zombies are three meshes on ONE skeleton, and the clips are
        // Generic, so they bind by transform path and drive any of the three.
        // Building three identical controllers would be three things to keep in
        // step instead of one.
        int built = 0;
        var names = new List<string>();
        foreach (var kv in models)
        {
            var prefab = BuildPrefab(kv.Value, controller, PrefabPathForSkin(kv.Key));
            if (prefab == null) return false;
            built++;
            names.Add("skin " + kv.Key + " -> " + System.IO.Path.GetFileName(PrefabPathForSkin(kv.Key)));
        }

        AssetDatabase.SaveAssets();
        AssetDatabase.Refresh();
        Debug.Log("[Zombie] built " + built + " enemy prefab(s): " + string.Join(", ", names) +
                  ".\nThe primary (skin " + PrimarySkin + ", " + SkinLook(PrimarySkin) + ") is " +
                  EnemyPrefabPath + "; EnemySpawner rolls between all of them.");
        return true;
    }

    /// <summary>
    /// Where a given skin's prefab lives.
    ///
    /// <para><b>Skin 2 keeps the unsuffixed name</b> and the other two are
    /// suffixed. That asymmetry is deliberate rather than sloppy:
    /// <c>Enemy.prefab</c> is referenced by <c>AshfallDepot.unity</c>'s spawner
    /// already, and renaming it would leave a missing reference in the one file
    /// this project intends hand edits to accumulate in.</para>
    ///
    /// <para><b>It is not because skin 2 is the plain one</b> — it is ICE, and
    /// there is no plain one. That was the stated justification here and it was
    /// a description of art nobody had looked at. The reference reason above is
    /// the real one and stands on its own.</para>
    /// </summary>
    public static string PrefabPathForSkin(int skin)
    {
        return skin == PrimarySkin ? EnemyPrefabPath : ArtRoot + "/Enemy_Skin" + skin + ".prefab";
    }

    /// <summary>
    /// Finds every zombie mesh that has been imported, keyed by skin number.
    ///
    /// <para><b>This was hardcoded to <c>SK_ZombieM_02_Mesh1.fbx</c> and it broke
    /// the moment the skin changed.</b> The pack's three zombies are three
    /// different MESHES, not three colourways, so
    /// <c>import_zombie.py --skin 2</c> leaves <c>Mesh2.fbx</c> on disk and the
    /// hardcoded lookup found nothing — the importer bailed out, and the build
    /// carried on and re-wired the spawner to the previous run's prefab, which
    /// now referenced a deleted mesh and had no renderers at all. Nothing errored
    /// except one line in a 400-line log.</para>
    ///
    /// <para>It then went the other way and REFUSED on more than one mesh, which
    /// was correct only while a single skin could be installed at a time. All
    /// three are supposed to be present now.</para>
    /// </summary>
    static SortedDictionary<int, GameObject> FindImportedMeshes()
    {
        var result = new SortedDictionary<int, GameObject>();
        var found = AssetDatabase.FindAssets("SK_ZombieM_02_Mesh t:Model", new[] { ArtRoot })
                                 .Select(AssetDatabase.GUIDToAssetPath)
                                 .Where(p => p.EndsWith(".fbx"))
                                 .OrderBy(p => p)
                                 .ToList();

        if (found.Count == 0)
        {
            Debug.LogError("[Zombie] no SK_ZombieM_02_Mesh*.fbx under " + ArtRoot + ". Run " +
                           "'python Tools/import_zombie.py' first — it copies the art across " +
                           "from the old project.");
            return result;
        }

        // THE SKIN NUMBER IS READ OUT OF THE FILE NAME, not counted.
        //
        // This used to REFUSE to run on more than one mesh, because the importer
        // was a switch: two meshes meant a half-cleaned import, and picking one
        // silently is how you get a body wearing another body's skin. That
        // reasoning was right then and is wrong now — all three are supposed to
        // be here. The protection it gave up is replaced by pairing each mesh
        // with its OWN numbered prefab, so no skin can be silently substituted
        // for another.
        foreach (var p in found)
        {
            var m = System.Text.RegularExpressions.Regex.Match(
                System.IO.Path.GetFileNameWithoutExtension(p), @"Mesh(\d+)$");
            if (!m.Success)
            {
                Debug.LogError("[Zombie] cannot read a skin number from " + p +
                               ". Expected SK_ZombieM_02_Mesh<N>.fbx.");
                continue;
            }
            int skin = int.Parse(m.Groups[1].Value);
            var go = AssetDatabase.LoadAssetAtPath<GameObject>(p);
            if (go != null) result[skin] = go;
        }

        if (!result.ContainsKey(PrimarySkin))
            Debug.LogWarning("[Zombie] skin " + PrimarySkin + " (" + SkinLook(PrimarySkin) + ") is NOT " +
                             "imported, so " + EnemyPrefabPath + " will not be built and the " +
                             "scene's spawner reference will be left dangling. Run " +
                             "'python Tools/import_zombie.py --skin " + PrimarySkin + "'.");
        return result;
    }

    // ------------------------------------------------------------ controller

    /// <summary>
    /// Authors the controller <see cref="EnemyAI"/> actually drives.
    ///
    /// <para>Shape, and why: a <c>StandUp</c> entry state that falls through to
    /// <c>Idle</c> on exit time (EnemyAI freezes the agent for
    /// <see cref="RoundRules.ZombieStandUpTime"/> at spawn and never triggers a
    /// stand-up, so this has to be automatic); four locomotion states selected by
    /// the three bools plus <c>WalkIndex</c>; and five attacks entered from Any
    /// State on the <c>Attack</c> trigger, discriminated by <c>AttackIndex</c>.</para>
    ///
    /// <para><b>There is no death state and that is correct.</b>
    /// <c>EnemyAI.Die</c> sets <c>anim.enabled = false</c> before handing over to
    /// the ragdoll, so any death clip would be cut off on frame one. The pack's
    /// three Death clips are deliberately not even copied across.</para>
    ///
    /// <para><b>Sprint reuses Run1 at 1.6x.</b> The pack ships no sprint clip.
    /// Playing the run faster is what the old project's controller did too (its
    /// Sprint state pointed at the same motion), and it is honest: round 10+
    /// zombies move at 6 m/s against the run's 3.5, so the legs have to keep
    /// up.</para>
    /// </summary>
    static AnimatorController BuildController()
    {
        var clips = LoadClips();
        if (clips == null) return null;

        // Created fresh every run rather than patched. A controller is a pure
        // build output — nothing is hand-authored in it — so rebuilding is the
        // whole point, and merging states into an existing graph is how you end up
        // with two Idles and a transition nobody can find.
        var controller = AnimatorController.CreateAnimatorControllerAtPath(ControllerPath);

        controller.AddParameter("IsWalking", AnimatorControllerParameterType.Bool);
        controller.AddParameter("IsRunning", AnimatorControllerParameterType.Bool);
        controller.AddParameter("IsSprinting", AnimatorControllerParameterType.Bool);
        controller.AddParameter("Attack", AnimatorControllerParameterType.Trigger);
        controller.AddParameter("AttackIndex", AnimatorControllerParameterType.Int);
        controller.AddParameter("WalkIndex", AnimatorControllerParameterType.Int);

        var sm = controller.layers[0].stateMachine;

        var standUp = sm.AddState("StandUp");
        standUp.motion = clips["StandUp1"];
        sm.defaultState = standUp;

        var idle = sm.AddState("Idle");
        idle.motion = clips["Idle1"];

        var walk0 = sm.AddState("Walk1");
        walk0.motion = clips["Walk1"];
        var walk1 = sm.AddState("Walk2");
        walk1.motion = clips["Walk2"];

        var run = sm.AddState("Run");
        run.motion = clips["Run1"];

        var sprint = sm.AddState("Sprint");
        sprint.motion = clips["Run1"];
        sprint.speed = 1.6f;

        // --- spawn ----------------------------------------------------------
        var rise = standUp.AddTransition(idle);
        rise.hasExitTime = true;
        rise.exitTime = 0.95f;
        rise.duration = 0.15f;

        // --- into locomotion -------------------------------------------------
        AddLocomotion(idle, walk0, 0.15f,
                      Cond("IsWalking", true), IntCond("WalkIndex", AnimatorConditionMode.Equals, 0));
        AddLocomotion(idle, walk1, 0.15f,
                      Cond("IsWalking", true), IntCond("WalkIndex", AnimatorConditionMode.Equals, 1));
        AddLocomotion(idle, run, 0.15f, Cond("IsRunning", true));
        AddLocomotion(idle, sprint, 0.15f, Cond("IsSprinting", true));

        // --- back to idle -----------------------------------------------------
        // EnemyAI.StopWalkAnimations clears all three bools together, so one
        // condition each is enough and covers every stop: arriving in range,
        // attacking, stealth, and tearing a barrier.
        AddLocomotion(walk0, idle, 0.15f, Cond("IsWalking", false));
        AddLocomotion(walk1, idle, 0.15f, Cond("IsWalking", false));
        AddLocomotion(run, idle, 0.15f, Cond("IsRunning", false));
        AddLocomotion(sprint, idle, 0.15f, Cond("IsSprinting", false));

        // --- attacks ----------------------------------------------------------
        // From Any State, because a swing has to be able to start out of walking,
        // running or idling. EnemyAI sets AttackIndex BEFORE the trigger in the
        // same frame, so the int is already correct when the transition is
        // evaluated.
        for (int i = 1; i <= 5; i++)
        {
            var attack = sm.AddState("Attack" + i);
            attack.motion = clips["Attack" + i];

            var enter = sm.AddAnyStateTransition(attack);
            enter.duration = 0.05f;
            enter.hasExitTime = false;
            // Without this an attack retriggers itself mid-swing and the zombie
            // stutters on the first frame forever.
            enter.canTransitionToSelf = false;
            enter.conditions = new[]
            {
                Cond("Attack", true),
                IntCond("AttackIndex", AnimatorConditionMode.Equals, i - 1),
            };

            var leave = attack.AddTransition(idle);
            leave.hasExitTime = true;
            leave.exitTime = 0.9f;
            leave.duration = 0.1f;
        }

        EditorUtility.SetDirty(controller);
        AssetDatabase.SaveAssets();

        Debug.Log("[Zombie] built " + ControllerPath + " — 6 parameters, " +
                  sm.states.Length + " states. The pack's own controller has zero " +
                  "parameters and could not be driven by EnemyAI.");
        return controller;
    }

    static void AddLocomotion(AnimatorState from, AnimatorState to, float duration,
                              params AnimatorCondition[] conditions)
    {
        var t = from.AddTransition(to);
        t.hasExitTime = false;      // locomotion must react now, not at a loop boundary
        t.duration = duration;
        t.conditions = conditions;
    }

    static AnimatorCondition Cond(string parameter, bool on)
    {
        return new AnimatorCondition
        {
            parameter = parameter,
            mode = on ? AnimatorConditionMode.If : AnimatorConditionMode.IfNot,
            threshold = 0f,
        };
    }

    static AnimatorCondition IntCond(string parameter, AnimatorConditionMode mode, int value)
    {
        return new AnimatorCondition { parameter = parameter, mode = mode, threshold = value };
    }

    /// <summary>
    /// Pulls one AnimationClip out of each imported animation FBX.
    ///
    /// <para>Read off the asset rather than named by hand. The clip inside
    /// <c>Anim_ZombieM_02@Walk1.fbx</c> is not necessarily called "Walk1" — the
    /// pack's take is "Take 001" — so a name table would be a second source of
    /// truth that is wrong on arrival. The FILE name is the contract; the clip is
    /// whatever is inside it.</para>
    ///
    /// <para><c>__preview__</c> clips are Unity's editor-preview artefacts and are
    /// skipped; assigning one produces a state that plays nothing.</para>
    /// </summary>
    static Dictionary<string, AnimationClip> LoadClips()
    {
        var wanted = new[]
        {
            "StandUp1", "Idle1", "Walk1", "Walk2", "Run1",
            "Attack1", "Attack2", "Attack3", "Attack4", "Attack5",
        };

        var result = new Dictionary<string, AnimationClip>();
        var missing = new List<string>();

        foreach (var name in wanted)
        {
            string path = ArtRoot + "/Animations/Anim_ZombieM_02@" + name + ".fbx";
            var clip = AssetDatabase.LoadAllAssetsAtPath(path)
                                    .OfType<AnimationClip>()
                                    .FirstOrDefault(c => !c.name.StartsWith("__preview__"));
            if (clip == null) missing.Add(name);
            else result[name] = clip;
        }

        if (missing.Count > 0)
        {
            Debug.LogError("[Zombie] no AnimationClip found in: " + string.Join(", ", missing) +
                           ". Run 'python Tools/import_zombie.py' and let Unity finish importing.");
            return null;
        }
        return result;
    }

    // ---------------------------------------------------------------- prefab

    static GameObject BuildPrefab(GameObject model, AnimatorController controller, string prefabPath)
    {
        var root = new GameObject("Enemy");
        try
        {
            var instance = (GameObject)PrefabUtility.InstantiatePrefab(model);
            instance.transform.SetParent(root.transform, false);
            instance.name = "Model";

            // WHICH WAY THE MODEL FACES IS MEASURED, NOT ASSUMED.
            //
            // The NavMeshAgent drives the ROOT's forward (+Z). If the mesh is
            // authored facing any other way the whole horde moonwalks — the most
            // visible bug it is possible to ship — and a typed 180 is right only
            // for as long as nobody re-exports the FBX. So the yaw is derived from
            // the skeleton's own feet.
            float yaw = DeriveFacingYaw(instance.transform, out string facingNote);
            instance.transform.localRotation = Quaternion.Euler(0f, yaw, 0f);

            var animator = instance.GetComponent<Animator>();
            if (animator == null) animator = instance.AddComponent<Animator>();
            animator.runtimeAnimatorController = controller;

            // ROOT MOTION OFF. The NavMeshAgent owns position; root motion would
            // fight it every frame and the result reads as walk stutter, which is
            // exactly the symptom EnemyAI's comments describe chasing before.
            animator.applyRootMotion = false;
            animator.cullingMode = AnimatorCullingMode.CullUpdateTransforms;

            var ragdollBones = BuildRagdoll(instance.transform, out string ragdollNote);

            // --- the root's own components -----------------------------------
            var capsule = root.AddComponent<CapsuleCollider>();
            capsule.height = 1.8f;
            capsule.radius = 0.3f;
            capsule.center = new Vector3(0f, 0.9f, 0f);
            // EnemyAI.Die disables THIS one and leaves the bone colliders enabled,
            // so a corpse is still shootable while the living silhouette is a
            // single cheap capsule.

            var body = root.AddComponent<Rigidbody>();
            body.isKinematic = true;
            body.useGravity = false;
            // EnemyAI sets isKinematic itself in Start, because a dynamic root
            // Rigidbody fights the agent. Setting it here as well means the prefab
            // is not briefly wrong on the first frame.

            var agent = root.AddComponent<UnityEngine.AI.NavMeshAgent>();
            agent.height = 2f;
            agent.radius = 0.35f;
            // Everything else about the agent — speed, avoidance, braking,
            // priority — is set by EnemyAI.Start from RoundRules, deliberately not
            // here. A serialized speed on this prefab would be exactly the Part I
            // "READ FIRST" bug: the asset quietly overriding the script.

            var ai = root.AddComponent<EnemyAI>();
            ai.anim = animator;

            var target = root.AddComponent<Target>();
            target.health = 50f;   // spec §5.1 base; EnemyAI multiplies by round

            root.AddComponent<ZombieStabilizer>();
            root.AddComponent<ZombieVoice>();

            // THE ENEMY LAYER, on the root AND every bone.
            //
            // The zombie worked on Default: the shot mask is
            // `whatIsEnemy | whatIsWorld`, which is ~0 either way, and the damage
            // branch is chosen by GetComponentInParent<Target>(), not by layer. But
            // WeaponRigBuilder sets every gun's whatIsEnemy to exactly this layer,
            // so on Default that field matched nothing at all — a serialized knob
            // that turns nothing, which is the bug class this project keeps
            // designing out. Someone would eventually tune it and watch nothing
            // happen.
            //
            // Safe here because the camera cullingMask is all layers (§14 Law 9)
            // and the physics collision matrix is all-on; both are asserted by
            // tests rather than assumed, because narrowing either one later would
            // make the entire horde invisible or intangible with nothing logged.
            int enemyLayer = LayerMask.NameToLayer(k_EnemyLayer);
            if (enemyLayer < 0)
            {
                Debug.LogError("[Zombie] there is no '" + k_EnemyLayer + "' layer, so the zombie " +
                               "was left on Default. Every gun's whatIsEnemy mask will match " +
                               "nothing.");
            }
            else
            {
                foreach (var t in root.GetComponentsInChildren<Transform>(true))
                    t.gameObject.layer = enemyLayer;
            }

            var prefab = PrefabUtility.SaveAsPrefabAsset(root, prefabPath, out bool ok);
            if (!ok || prefab == null)
            {
                Debug.LogError("[Zombie] failed to save " + prefabPath);
                return null;
            }

            // prefabPath, NOT EnemyPrefabPath. It said EnemyPrefabPath and so
            // reported "built Enemy.prefab" three times in a row while actually
            // writing three different files — a log describing what the code used
            // to do rather than what it just did.
            Debug.Log("[Zombie] built " + prefabPath + ".\n" +
                      "  facing      " + facingNote + "\n" +
                      "  ragdoll     " + ragdollBones + " bodies. " + ragdollNote + "\n" +
                      "  animator    " + controller.name + ", root motion OFF (the agent owns position)\n" +
                      "  colliders   root capsule (disabled on death) + bone colliders (always on)\n" +
                      "  agent/speed set by EnemyAI from RoundRules, not serialized here");
            return prefab;
        }
        finally
        {
            Object.DestroyImmediate(root);
        }
    }

    /// <summary>
    /// The yaw that turns the model to face the root's +Z, measured from the
    /// skeleton.
    ///
    /// <para><b>Toes point forward.</b> So the ankle-to-toe vector
    /// (<c>foot_l</c> -> <c>ball_l</c>) is the character's facing, in whatever
    /// space the FBX happened to be authored in. Both feet are used and summed
    /// because feet splay outward: one foot alone gives a reading rotated by half
    /// the stance angle, and Idle1's stance is not symmetric about the axis you
    /// would guess.</para>
    ///
    /// <para>Snapped to the nearest 90 degrees. The measurement exists to pick
    /// between four possible export orientations, not to correct a few degrees of
    /// pigeon-toe — leaving it unsnapped would bake a permanent 3-degree lean into
    /// every zombie in the game.</para>
    /// </summary>
    static float DeriveFacingYaw(Transform model, out string note)
    {
        Transform footL = FindBone(model, "foot_l"), toeL = FindBone(model, "ball_l");
        Transform footR = FindBone(model, "foot_r"), toeR = FindBone(model, "ball_r");

        Vector3 forward = Vector3.zero;
        if (footL != null && toeL != null) forward += toeL.position - footL.position;
        if (footR != null && toeR != null) forward += toeR.position - footR.position;

        var flat = new Vector3(forward.x, 0f, forward.z);
        if (flat.sqrMagnitude < 1e-6f)
        {
            note = "COULD NOT MEASURE (no foot/toe bones, or the feet are vertical) — left at 0. " +
                   "Check in the editor whether the zombie walks backwards.";
            Debug.LogWarning("[Zombie] " + note);
            return 0f;
        }

        float measured = Vector3.SignedAngle(flat.normalized, Vector3.forward, Vector3.up);
        float snapped = Mathf.Round(measured / 90f) * 90f;
        if (snapped <= -180f) snapped += 360f;

        note = "model faces " + measured.ToString("F1") + " deg off +Z (measured from the foot " +
               "bones), corrected by " + snapped.ToString("F0") + " deg";
        return snapped;
    }

    static Transform FindBone(Transform root, string name)
    {
        foreach (var t in root.GetComponentsInChildren<Transform>(true))
            if (t.name == name) return t;
        return null;
    }

    // --------------------------------------------------------------- ragdoll

    /// <summary>
    /// One entry per ragdoll body. <c>bone</c> and <c>childBone</c> are the
    /// skeleton names the limb spans, so every collider is SIZED FROM THE
    /// SKELETON rather than from typed numbers — the pack could ship a taller
    /// zombie tomorrow and this still fits it.
    /// </summary>
    struct Limb
    {
        public string bone;
        public string childBone;    // null = size from the parent's own extent
        public float radius;
        public bool isHead;
    }

    /// <summary>
    /// The standard 13-body ragdoll, on ZombieM_02's UE-style skeleton
    /// (<c>pelvis</c> / <c>spine_03</c> / <c>upperarm_l</c> / <c>thigh_r</c> ...).
    ///
    /// <para>Fingers, twist bones and the IK control rig are deliberately left
    /// out. Every extra body is a solver cost on every corpse, and twist bones in
    /// particular are driven BY their parent — jointing them fights the skeleton
    /// and produces the limb-stretching this project's <c>SetRagdollState</c>
    /// already had to add joint projection to cure.</para>
    /// </summary>
    static readonly Limb[] k_Limbs =
    {
        new Limb { bone = "pelvis",     childBone = "spine_03", radius = 0.14f },
        new Limb { bone = "spine_03",   childBone = "neck_01",  radius = 0.15f },
        new Limb { bone = "head",       childBone = null,       radius = 0.13f, isHead = true },
        new Limb { bone = "upperarm_l", childBone = "lowerarm_l", radius = 0.055f },
        new Limb { bone = "lowerarm_l", childBone = "hand_l",     radius = 0.045f },
        new Limb { bone = "upperarm_r", childBone = "lowerarm_r", radius = 0.055f },
        new Limb { bone = "lowerarm_r", childBone = "hand_r",     radius = 0.045f },
        new Limb { bone = "thigh_l",    childBone = "calf_l",   radius = 0.08f },
        new Limb { bone = "calf_l",     childBone = "foot_l",   radius = 0.06f },
        new Limb { bone = "thigh_r",    childBone = "calf_r",   radius = 0.08f },
        new Limb { bone = "calf_r",     childBone = "foot_r",   radius = 0.06f },
    };

    /// <summary>Which body each limb hangs from. Nothing joints to the pelvis's
    /// parent, so the pelvis is the free root of the ragdoll.</summary>
    static readonly Dictionary<string, string> k_JointParent = new Dictionary<string, string>
    {
        { "spine_03", "pelvis" },
        { "head", "spine_03" },
        { "upperarm_l", "spine_03" },
        { "lowerarm_l", "upperarm_l" },
        { "upperarm_r", "spine_03" },
        { "lowerarm_r", "upperarm_r" },
        { "thigh_l", "pelvis" },
        { "calf_l", "thigh_l" },
        { "thigh_r", "pelvis" },
        { "calf_r", "thigh_r" },
    };

    /// <summary>Total mass is split across the bodies in roughly human
    /// proportions. A uniform mass makes the head as heavy as the torso and
    /// corpses flail.</summary>
    static readonly Dictionary<string, float> k_Mass = new Dictionary<string, float>
    {
        { "pelvis", 12f }, { "spine_03", 16f }, { "head", 5f },
        { "upperarm_l", 2.5f }, { "lowerarm_l", 1.8f },
        { "upperarm_r", 2.5f }, { "lowerarm_r", 1.8f },
        { "thigh_l", 6f }, { "calf_l", 4f },
        { "thigh_r", 6f }, { "calf_r", 4f },
    };

    static int BuildRagdoll(Transform modelRoot, out string note)
    {
        var bones = new Dictionary<string, Transform>();
        foreach (var t in modelRoot.GetComponentsInChildren<Transform>())
            if (!bones.ContainsKey(t.name)) bones[t.name] = t;

        var missing = k_Limbs.Where(l => !bones.ContainsKey(l.bone))
                             .Select(l => l.bone).ToList();
        if (missing.Count > 0)
        {
            note = "MISSING BONES: " + string.Join(", ", missing) + " — the skeleton is not " +
                   "the one this table was written for, so the ragdoll is incomplete.";
            Debug.LogError("[Zombie] " + note);
        }
        else
        {
            note = "sized from the skeleton, not from literals.";
        }

        var bodies = new Dictionary<string, Rigidbody>();

        foreach (var limb in k_Limbs)
        {
            if (!bones.TryGetValue(limb.bone, out var bone)) continue;

            var rb = bone.gameObject.GetComponent<Rigidbody>();
            if (rb == null) rb = bone.gameObject.AddComponent<Rigidbody>();
            rb.mass = k_Mass.TryGetValue(limb.bone, out float m) ? m : 3f;
            // Kinematic until EnemyAI.Die flips it. A live zombie whose bones are
            // dynamic collapses into a heap the moment it spawns.
            rb.isKinematic = true;
            rb.interpolation = RigidbodyInterpolation.Interpolate;
            bodies[limb.bone] = rb;

            AddLimbCollider(bone, limb, bones);
        }

        foreach (var pair in k_JointParent)
        {
            if (!bodies.TryGetValue(pair.Key, out _)) continue;
            if (!bodies.TryGetValue(pair.Value, out var parentBody)) continue;

            var bone = bones[pair.Key];
            var joint = bone.gameObject.GetComponent<CharacterJoint>();
            if (joint == null) joint = bone.gameObject.AddComponent<CharacterJoint>();
            joint.connectedBody = parentBody;

            // Limits are loose and symmetric rather than anatomically exact. A
            // corpse only has to fall convincingly, and tight limits on a rig this
            // script did not author is how you get a body that locks up mid-fall.
            joint.lowTwistLimit = new SoftJointLimit { limit = -30f };
            joint.highTwistLimit = new SoftJointLimit { limit = 30f };
            joint.swing1Limit = new SoftJointLimit { limit = 50f };
            joint.swing2Limit = new SoftJointLimit { limit = 30f };

            // EnemyAI.SetRagdollState turns projection on at death as well. Setting
            // it here too means it is right even if something else ragdolls this.
            joint.enableProjection = true;
            joint.projectionDistance = 0.05f;
            joint.projectionAngle = 60f;
        }

        return bodies.Count;
    }

    static void AddLimbCollider(Transform bone, Limb limb, Dictionary<string, Transform> bones)
    {
        if (limb.isHead)
        {
            var sphere = bone.gameObject.GetComponent<SphereCollider>();
            if (sphere == null) sphere = bone.gameObject.AddComponent<SphereCollider>();
            sphere.radius = limb.radius;
            // Sit it up the skull rather than on the neck joint the bone's origin
            // actually is, or the top of the head is not shootable.
            sphere.center = new Vector3(0f, limb.radius * 0.7f, 0f);

            // THE TAG. GunSystem.ApplyHit decides headshots with
            // CompareTag("Head") on the collider it hit — not on the object, not
            // on a parent. Miss this and headshots silently stop existing.
            bone.gameObject.tag = k_HeadTag;
            return;
        }

        var capsule = bone.gameObject.GetComponent<CapsuleCollider>();
        if (capsule == null) capsule = bone.gameObject.AddComponent<CapsuleCollider>();
        capsule.radius = limb.radius;

        // Length and axis are MEASURED from where the child bone actually is, in
        // this bone's own local space. Typing a length and an axis per limb would
        // be eleven more literals to be wrong about, and they would all be wrong
        // together the first time the pack ships a different skeleton.
        Vector3 local = Vector3.up * (limb.radius * 4f);
        if (limb.childBone != null && bones.TryGetValue(limb.childBone, out var child))
            local = bone.InverseTransformPoint(child.position);

        float length = local.magnitude;
        if (length < 0.01f) length = limb.radius * 4f;

        capsule.height = Mathf.Max(length, limb.radius * 2.2f);
        capsule.center = local * 0.5f;
        capsule.direction = DominantAxis(local);
    }

    /// <summary>0 = X, 1 = Y, 2 = Z — the axis the limb mostly runs along.</summary>
    static int DominantAxis(Vector3 v)
    {
        var a = new Vector3(Mathf.Abs(v.x), Mathf.Abs(v.y), Mathf.Abs(v.z));
        if (a.x >= a.y && a.x >= a.z) return 0;
        return a.y >= a.z ? 1 : 2;
    }
}
