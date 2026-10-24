using System.Collections.Generic;
using System.IO;
using System.Linq;
using NUnit.Framework;
using TMPro;
using UnityEditor;
using UnityEditor.Animations;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.InputSystem.UI;
using UnityEngine.SceneManagement;

/// <summary>
/// The core loop: currency, HUD, event system, rounds, and the zombie.
///
/// <para><b>Every test in this file exists because the thing it checks was
/// ABSENT and nothing noticed.</b> That is the specific failure mode this
/// project keeps recording — 121 tests passed while the map had no ScoreManager,
/// no Canvas, no EventSystem and no EnemySpawner, because every test that
/// existed checked a property of something that was there. An absence has no
/// properties.</para>
///
/// <para>So these are deliberately shaped as existence-and-wiring checks, and
/// several of them assert the SECOND half of a pair — not just "there is a
/// promptText" but "the promptText has a font", because a TMP component with a
/// null font renders nothing while looking healthy in the hierarchy.</para>
/// </summary>
public class CoreLoopTests
{
    static Scene s_Scene;

    [OneTimeSetUp]
    public void OpenTheMap()
    {
        Assert.IsTrue(File.Exists(MapSeeder.ScenePath),
            "No map scene at " + MapSeeder.ScenePath + ".");

        // Same rule as MapSceneTests: OpenScene from a script does NOT prompt, it
        // discards unsaved changes and clears the undo stack. Pressing Run in the
        // Test Runner must never be able to destroy hand dressing.
        var open = SceneManager.GetActiveScene();
        if (open.path == MapSeeder.ScenePath) { s_Scene = open; return; }

        if (!Application.isBatchMode &&
            !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            Assert.Ignore("Skipped: the open scene has unsaved changes and loading the map " +
                          "would discard them.");

        s_Scene = EditorSceneManager.OpenScene(MapSeeder.ScenePath, OpenSceneMode.Single);
    }

    static List<T> FindAll<T>() where T : Component
    {
        var found = new List<T>();
        foreach (var root in s_Scene.GetRootGameObjects())
            found.AddRange(root.GetComponentsInChildren<T>(true));
        return found;
    }

    static T FindOne<T>() where T : Component
    {
        var found = FindAll<T>();
        Assert.AreEqual(1, found.Count,
            "Expected exactly one " + typeof(T).Name + " in the map, found " + found.Count + ".");
        return found[0];
    }

    // ------------------------------------------------------------- currency

    [Test]
    public void Map_HasAScoreManager_OrNothingCanBeBought()
    {
        // ScoreManager.TrySpend opens with `if (instance == null) return false;`,
        // so with none in the scene every door, wallbuy, box, perk machine,
        // Pack-a-Punch, ammo crate and gum machine REFUSES — and Award returns
        // early too, so you cannot earn your way out of it either.
        Assert.IsNotNull(FindOne<ScoreManager>());
    }

    [Test]
    public void Map_ScoreManagerHasNoSerializedStartingBalance()
    {
        // The opening balance must come from RoundRules.StartingEssence, not from
        // a number baked into the scene by a tool. A serialized value always beats
        // a script default; that is the Part I "READ FIRST" bug.
        var field = typeof(ScoreManager).GetField("essence");
        Assert.IsNotNull(field, "ScoreManager.essence is gone — this test needs rewriting.");
        Assert.IsTrue(field.IsDefined(typeof(System.NonSerializedAttribute), false),
            "ScoreManager.essence is serialized again, so AshfallDepot.unity can carry a stale " +
            "starting balance that overrides RoundRules.StartingEssence and nothing would say so.");
    }

    [Test]
    public void Map_GobblegumManagerExists_OrTheGumMachineRefusesBeforeItEvenCharges()
    {
        // GobblegumMachine.Interact returns false at
        // `GobblegumManager.instance == null` BEFORE it reaches TrySpend, so
        // fixing the currency alone still leaves the 500-cost machine silent.
        Assert.IsNotNull(FindOne<GobblegumManager>());
    }

    // ------------------------------------------------------------------ HUD

    [Test]
    public void Map_TheInteractionPromptIsWiredAndCanActuallyRender()
    {
        var interaction = FindOne<InteractionSystem>();
        Assert.IsNotNull(interaction.promptText,
            "InteractionSystem.promptText is null. SetPrompt opens with " +
            "`if (promptText == null) return;`, so every prompt is computed and thrown away and " +
            "you walk up to a machine and see NOTHING — which reads as 'broken', not as " +
            "'you cannot afford it'.");

        // The second half, and the one that actually bit: a TextMeshProUGUI with
        // no font renders nothing at all while looking perfectly healthy.
        Assert.IsNotNull(interaction.promptText.font,
            "The prompt has no TMP font asset, so it renders nothing. Import the TMP Essential " +
            "Resources.");
    }

    [Test]
    public void Map_TheEssenceReadoutIsWiredAndCanActuallyRender()
    {
        var score = FindOne<ScoreManager>();
        Assert.IsNotNull(score.essenceText,
            "ScoreManager.essenceText is null — RefreshUI writes nowhere, so Essence changes " +
            "are invisible.");
        Assert.IsNotNull(score.essenceText.font, "The essence readout has no TMP font.");
    }

    [Test]
    public void Map_TheRoundReadoutIsWiredAndCanActuallyRender()
    {
        var spawner = FindOne<EnemySpawner>();
        Assert.IsNotNull(spawner.roundText,
            "EnemySpawner.roundText is null — the round number is never announced or displayed.");
        Assert.IsNotNull(spawner.roundText.font, "The round readout has no TMP font.");
    }

    [Test]
    public void Map_TheRoundReadoutIsAnchoredAtTheScreenCentre()
    {
        // EnemySpawner.AnimateRoundStart drives anchoredPosition from
        // textCenterPos to textCornerPos, and those are OFFSETS FROM THE ANCHOR.
        // Anchored anywhere else, the round announcement flies off-screen — and
        // it would still pass every "roundText is not null" check.
        var rect = FindOne<EnemySpawner>().roundText.rectTransform;
        Assert.AreEqual(0.5f, rect.anchorMin.x, 0.001f, "Round text anchorMin.x");
        Assert.AreEqual(0.5f, rect.anchorMin.y, 0.001f, "Round text anchorMin.y");
        Assert.AreEqual(0.5f, rect.anchorMax.x, 0.001f, "Round text anchorMax.x");
        Assert.AreEqual(0.5f, rect.anchorMax.y, 0.001f, "Round text anchorMax.y");
    }

    [Test]
    public void Map_HasExactlyOneCanvasAndItIsScreenSpace()
    {
        var canvases = FindAll<Canvas>();
        Assert.AreEqual(1, canvases.Count,
            "Expected one HUD canvas, found " + canvases.Count + ".");
        Assert.AreEqual(RenderMode.ScreenSpaceOverlay, canvases[0].renderMode,
            "The HUD canvas is not screen-space, so the HUD is somewhere in the world.");
    }

    [Test]
    public void Map_NoPlaytestRigRootSurvives()
    {
        // The scaffolding is retired. If it comes back it brings a SECOND
        // ScoreManager, and ScoreManager.Awake destroys one of them by load order.
        foreach (var go in s_Scene.GetRootGameObjects())
            Assert.AreNotEqual("PlaytestRig", go.name,
                "A PlaytestRig root is back in the scene. CoreLoopBuilder replaces it; two " +
                "ScoreManagers is a runtime coin toss.");
    }

    // ---------------------------------------------------------- event system

    [Test]
    public void Map_HasAnEventSystem()
    {
        Assert.IsNotNull(FindOne<EventSystem>());
    }

    [Test]
    public void Map_TheEventSystemUsesTheInputSystemModuleNotTheLegacyOne()
    {
        var es = FindOne<EventSystem>();

        Assert.IsNotNull(es.GetComponent<InputSystemUIInputModule>(),
            "The EventSystem has no InputSystemUIInputModule, so nothing on a canvas will ever " +
            "receive input.");

        // activeInputHandler is 1 (Input System only), so StandaloneInputModule
        // reads UnityEngine.Input and THROWS — every frame, from a component whose
        // whole job is to be invisible until something needs clicking.
        Assert.IsNull(es.GetComponent<StandaloneInputModule>(),
            "The EventSystem carries a legacy StandaloneInputModule. This project runs " +
            "Input-System-only, where UnityEngine.Input throws at runtime.");
    }

    // --------------------------------------------------------------- rounds

    [Test]
    public void Map_HasAnEnemySpawner_OrThereIsNoGame()
    {
        Assert.IsNotNull(FindOne<EnemySpawner>());
    }

    [Test]
    public void Map_TheSpawnerUsesEveryDerivedSpawnPointAndNoOthers()
    {
        var spawner = FindOne<EnemySpawner>();

        var root = GameObject.Find("EnemySpawnPoints");
        Assert.IsNotNull(root, "No 'EnemySpawnPoints' root — MapSeeder derives these from the " +
                               "baked navmesh and nothing else knows where they are.");

        var derived = new List<Transform>();
        foreach (Transform t in root.transform) derived.Add(t);

        Assert.IsNotNull(spawner.spawnPoints, "EnemySpawner.spawnPoints is null.");
        Assert.AreEqual(derived.Count, spawner.spawnPoints.Length,
            "EnemySpawner has " + spawner.spawnPoints.Length + " spawn points but the scene " +
            "derived " + derived.Count + ". They must not be typed or filtered by hand — " +
            "re-seeding the map moves every one of them.");

        // A null entry is the quiet one: TryPickSpawnPosition does `if (point ==
        // null) continue;`, so a broken reference just makes spawns rarer rather
        // than erroring, and rounds get slower for no visible reason.
        for (int i = 0; i < spawner.spawnPoints.Length; i++)
            Assert.IsNotNull(spawner.spawnPoints[i],
                "EnemySpawner.spawnPoints[" + i + "] is a null reference.");

        CollectionAssert.AreEquivalent(derived, spawner.spawnPoints,
            "EnemySpawner points at Transforms that are not the scene's derived spawn points.");
    }

    [Test]
    public void Map_TheSpawnerHasAZombieToSpawn()
    {
        var spawner = FindOne<EnemySpawner>();
        Assert.IsNotNull(spawner.enemyPrefab,
            "EnemySpawner.enemyPrefab is null. TrySpawnEnemy opens with " +
            "`if (enemyPrefab == null) return false;`, so rounds start, the counter says " +
            "5 zombies, and nothing ever appears — and the round can never end.");
    }

    [Test]
    public void Map_TheSpawnerCarriesNoPacingFieldsThatCouldOverrideRoundRules()
    {
        // The Part I "READ FIRST" bug in its original form: the scene ran
        // timeBetweenSpawns = 5 while the script said 2. There must be no
        // serialized field on EnemySpawner that can restate a RoundRules number.
        var banned = new[]
        {
            "timeBetweenSpawns", "zombiesPerRound", "maxConcurrent",
            "maxConcurrentZombies", "walkSpeed", "runSpeed", "sprintSpeed",
        };
        var fields = typeof(EnemySpawner).GetFields().Select(f => f.Name).ToArray();
        foreach (var name in banned)
            CollectionAssert.DoesNotContain(fields, name,
                "EnemySpawner has a public '" + name + "' field again. Pacing lives in " +
                "RoundRules so the scene cannot silently disagree with the script.");
    }

    // --------------------------------------------------------------- zombie

    static GameObject LoadZombie()
    {
        var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(ZombieImporter.EnemyPrefabPath);
        Assert.IsNotNull(prefab,
            "No zombie prefab at " + ZombieImporter.EnemyPrefabPath + ". Run " +
            "'python Tools/import_zombie.py' then Tools > Aftermarket > Import Zombie.");
        return prefab;
    }

    /// <summary>
    /// Every zombie mesh on disk, keyed by its skin number.
    /// </summary>
    static SortedDictionary<int, string> ImportedSkins()
    {
        var found = new SortedDictionary<int, string>();
        foreach (var guid in AssetDatabase.FindAssets("SK_ZombieM_02_Mesh t:Model",
                                                      new[] { ZombieImporter.ArtRoot }))
        {
            var path = AssetDatabase.GUIDToAssetPath(guid);
            if (!path.EndsWith(".fbx")) continue;
            var m = System.Text.RegularExpressions.Regex.Match(
                Path.GetFileNameWithoutExtension(path), @"Mesh(\d+)$");
            if (m.Success) found[int.Parse(m.Groups[1].Value)] = path;
        }
        return found;
    }

    [Test]
    public void Zombie_EverySkinOnDiskHasItsOwnEnemyPrefab()
    {
        var skins = ImportedSkins();
        Assert.IsNotEmpty(skins, "No zombie meshes under " + ZombieImporter.ArtRoot + ".");
        foreach (var kv in skins)
        {
            var path = ZombieImporter.PrefabPathForSkin(kv.Key);
            Assert.IsNotNull(AssetDatabase.LoadAssetAtPath<GameObject>(path),
                "Skin " + kv.Key + "'s mesh is imported (" + kv.Value + ") but there is no " +
                "prefab at " + path + ", so that look can never spawn. Run " +
                "Tools > Aftermarket > Import Zombie.");
        }
    }

    /// <summary>
    /// THE ONE THAT CATCHES A BODY WEARING ANOTHER BODY'S SKIN.
    ///
    /// The three zombies are three MESHES with three TEXTURE SETS, paired. Every
    /// name involved differs only by a trailing digit, and the materials used to
    /// be renamed to a skin-independent name on the way in — so importing a
    /// second skin overwrote the first's material while both meshes stayed on
    /// disk. The result renders perfectly happily; it is simply the wrong zombie,
    /// and no existence check can see it.
    ///
    /// So: skin N's prefab must bind skin N's textures and nobody else's.
    /// </summary>
    [Test]
    public void Zombie_EachPrefabWearsItsOwnSkinsTextures()
    {
        foreach (var kv in ImportedSkins())
        {
            int skin = kv.Key;
            var path = ZombieImporter.PrefabPathForSkin(skin);
            var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(path);
            if (prefab == null) continue;   // covered by the test above

            var renderers = prefab.GetComponentsInChildren<SkinnedMeshRenderer>(true);
            Assert.IsNotEmpty(renderers, path + " has no SkinnedMeshRenderer at all.");

            int checkedMaps = 0;
            foreach (var r in renderers)
            {
                foreach (var mat in r.sharedMaterials)
                {
                    Assert.IsNotNull(mat,
                        path + ": renderer '" + r.name + "' has an empty material slot, so it " +
                        "renders as Unity's default grey with nothing logged.");

                    var tex = mat.HasProperty("_BaseMap") ? mat.GetTexture("_BaseMap") : null;
                    Assert.IsNotNull(tex,
                        path + ": material '" + mat.name + "' has no _BaseMap. A URP/Lit " +
                        "material with no albedo renders flat white and looks healthy in the " +
                        "inspector.");

                    StringAssert.Contains("Skin" + skin, tex.name,
                        path + " (skin " + skin + ") is rendering with '" + tex.name +
                        "', which belongs to another skin. This is the body-wearing-another-" +
                        "body's-skin failure: it renders fine and is simply the wrong zombie.");
                    checkedMaps++;
                }
            }
            Assert.Greater(checkedMaps, 0, path + ": no materials were actually checked.");
        }
    }

    [Test]
    public void Zombie_NoTwoSkinsShareAMaterialAsset()
    {
        var byMaterial = new Dictionary<string, int>();
        foreach (var kv in ImportedSkins())
        {
            var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(
                ZombieImporter.PrefabPathForSkin(kv.Key));
            if (prefab == null) continue;
            foreach (var r in prefab.GetComponentsInChildren<SkinnedMeshRenderer>(true))
                foreach (var mat in r.sharedMaterials)
                {
                    if (mat == null) continue;
                    var matPath = AssetDatabase.GetAssetPath(mat);
                    if (byMaterial.TryGetValue(matPath, out int owner) && owner != kv.Key)
                        Assert.Fail("Skins " + owner + " and " + kv.Key + " both render with " +
                                    matPath + ". The import overwrote one skin's material with " +
                                    "another's — they must be skin-suffixed.");
                    byMaterial[matPath] = kv.Key;
                }
        }
    }

    [Test]
    public void Map_TheSpawnerRollsEveryZombieLookThatWasImported()
    {
        OpenTheMap();
        var spawner = Object.FindFirstObjectByType<EnemySpawner>();
        Assert.IsNotNull(spawner, "No EnemySpawner in the map.");
        Assert.IsNotNull(spawner.enemyPrefab, "EnemySpawner.enemyPrefab is null.");

        var wired = new HashSet<string>();
        wired.Add(AssetDatabase.GetAssetPath(spawner.enemyPrefab));
        if (spawner.variantPrefabs != null)
            foreach (var v in spawner.variantPrefabs)
            {
                Assert.IsNotNull(v,
                    "EnemySpawner.variantPrefabs has a NULL entry. PickPrefab skips nulls, but " +
                    "an empty slot here means a look that was imported and never wired.");
                wired.Add(AssetDatabase.GetAssetPath(v));
            }

        foreach (var kv in ImportedSkins())
        {
            var path = ZombieImporter.PrefabPathForSkin(kv.Key);
            if (AssetDatabase.LoadAssetAtPath<GameObject>(path) == null) continue;
            Assert.IsTrue(wired.Contains(path),
                "Skin " + kv.Key + "'s prefab (" + path + ") exists but the map's EnemySpawner " +
                "never spawns it, so it is 160 MB of art that cannot appear in play. Run " +
                "Tools > Aftermarket > Build Core Loop.");
        }
    }

    [Test]
    public void Zombie_TheControllerDeclaresEveryParameterEnemyAIDrives()
    {
        // THE ONE THAT MATTERS MOST. ZombieM_02 ships its own AnimatorController
        // with ZERO parameters — it is a showcase reel. Setting a parameter an
        // Animator does not have logs an error PER CALL, so using it would give a
        // wall of console spam and a zombie frozen in place. Nothing about
        // "the prefab has an Animator" would have caught that.
        var controller = AssetDatabase.LoadAssetAtPath<AnimatorController>(
            ZombieImporter.ControllerPath);
        Assert.IsNotNull(controller, "No controller at " + ZombieImporter.ControllerPath);

        var expected = new Dictionary<string, AnimatorControllerParameterType>
        {
            { "IsWalking", AnimatorControllerParameterType.Bool },
            { "IsRunning", AnimatorControllerParameterType.Bool },
            { "IsSprinting", AnimatorControllerParameterType.Bool },
            { "Attack", AnimatorControllerParameterType.Trigger },
            { "AttackIndex", AnimatorControllerParameterType.Int },
            { "WalkIndex", AnimatorControllerParameterType.Int },
        };

        foreach (var want in expected)
        {
            var found = controller.parameters.FirstOrDefault(p => p.name == want.Key);
            Assert.IsNotNull(found,
                "The zombie controller has no '" + want.Key + "' parameter, but EnemyAI sets it.");
            Assert.AreEqual(want.Value, found.type, "Parameter '" + want.Key + "' has the wrong type.");
        }
    }

    [Test]
    public void Zombie_HasAStateForEveryAttackAndWalkIndexEnemyAICanRoll()
    {
        var controller = AssetDatabase.LoadAssetAtPath<AnimatorController>(
            ZombieImporter.ControllerPath);
        var states = controller.layers[0].stateMachine.states.Select(s => s.state).ToList();

        // EnemyAI rolls AttackIndex in [0, attackCount) and WalkIndex in
        // [0, walkCount). Defaults are 5 and 2. A roll with no matching transition
        // is a zombie that stands still instead of swinging.
        var ai = LoadZombie().GetComponent<EnemyAI>();
        for (int i = 1; i <= ai.attackCount; i++)
            Assert.IsTrue(states.Any(s => s.name == "Attack" + i),
                "No 'Attack" + i + "' state, but EnemyAI.attackCount is " + ai.attackCount + ".");
        for (int i = 1; i <= ai.walkCount; i++)
            Assert.IsTrue(states.Any(s => s.name == "Walk" + i),
                "No 'Walk" + i + "' state, but EnemyAI.walkCount is " + ai.walkCount + ".");

        // Every state must actually carry a clip. An empty state is a T-pose that
        // passes any "the state exists" check.
        foreach (var s in states)
            Assert.IsNotNull(s.motion, "State '" + s.name + "' has no motion assigned.");
    }

    [Test]
    public void Zombie_HeadColliderCarriesTheHeadTag()
    {
        // GunSystem.ApplyHit decides headshots with a bare
        // hit.collider.CompareTag("Head") — on the COLLIDER, not the object, not a
        // parent. Without it headshots silently stop existing: no 130-Essence
        // kills, no camo progression, no ding, and nothing logged.
        var heads = LoadZombie().GetComponentsInChildren<Transform>(true)
                                .Where(t => t.CompareTag("Head")).ToList();
        Assert.AreEqual(1, heads.Count,
            "Expected exactly one object tagged 'Head' on the zombie, found " + heads.Count + ".");
        Assert.IsNotNull(heads[0].GetComponent<Collider>(),
            "The 'Head'-tagged object has no Collider, so the raycast can never hit it.");
    }

    [Test]
    public void Zombie_HasARagdollWithJointedBones()
    {
        var zombie = LoadZombie();

        // EnemyAI.Start logs an error at `ragdollRBs.Length <= 1`, and Die() hands
        // the corpse to SetRagdollState(true) — with no bodies the zombie just
        // freezes upright and disappears.
        var bodies = zombie.GetComponentsInChildren<Rigidbody>(true);
        Assert.GreaterOrEqual(bodies.Length, 10,
            "Only " + bodies.Length + " Rigidbodies on the zombie; a ragdoll needs the standard " +
            "set (pelvis, spine, head, arms, legs).");

        var joints = zombie.GetComponentsInChildren<CharacterJoint>(true);
        Assert.GreaterOrEqual(joints.Length, 9,
            "Only " + joints.Length + " CharacterJoints — bones with no joint fly apart on death.");
        foreach (var j in joints)
            Assert.IsNotNull(j.connectedBody,
                "CharacterJoint on '" + j.name + "' has no connectedBody, so that limb is loose.");
    }

    [Test]
    public void Zombie_BonesAreKinematicUntilDeath()
    {
        // A live zombie whose bone Rigidbodies are dynamic collapses into a heap
        // the frame it spawns. EnemyAI.SetRagdollState(false) is called in Start,
        // but the prefab must not be wrong for the frames before that.
        foreach (var rb in LoadZombie().GetComponentsInChildren<Rigidbody>(true))
            Assert.IsTrue(rb.isKinematic,
                "Rigidbody on '" + rb.name + "' is dynamic in the prefab.");
    }

    [Test]
    public void Zombie_DoesNotApplyRootMotion()
    {
        // The NavMeshAgent owns position. Root motion fights it every frame, and
        // the symptom is the walk stutter EnemyAI's comments describe chasing.
        var animator = LoadZombie().GetComponentInChildren<Animator>(true);
        Assert.IsNotNull(animator, "The zombie has no Animator.");
        Assert.IsFalse(animator.applyRootMotion,
            "Root motion is on, so the animation and the NavMeshAgent are both trying to move " +
            "the zombie.");
        Assert.IsNotNull(animator.runtimeAnimatorController,
            "The Animator has no controller, so the zombie T-poses through the whole match.");
    }

    [Test]
    public void Zombie_CarriesTheComponentsTheRoundLoopCallsInTo()
    {
        var zombie = LoadZombie();
        Assert.IsNotNull(zombie.GetComponent<EnemyAI>(), "no EnemyAI");
        Assert.IsNotNull(zombie.GetComponent<Target>(), "no Target — it cannot be shot");
        Assert.IsNotNull(zombie.GetComponent<UnityEngine.AI.NavMeshAgent>(), "no NavMeshAgent");

        // Target.Die routes through EnemyAI.Die, which is what calls
        // EnemySpawner.EnemyKilled(). Without that the round counter never falls
        // and the round cannot end.
        Assert.IsNotNull(zombie.GetComponent<Target>().GetComponent<EnemyAI>(),
            "Target and EnemyAI must be on the same object or kills are not counted.");
    }

    [Test]
    public void Zombie_TheAnimationClipsActuallyMoveThisModelsBones()
    {
        // THE ONE THING NOTHING ELSE HERE COULD CATCH.
        //
        // Every animation FBX in this pack is imported Generic (animationType 2)
        // with avatarSetup 1, so each one carries its OWN avatar. The Animator on
        // the prefab uses the MESH's avatar. In Generic mode clips bind by
        // transform PATH, so this works only for as long as the animation rig and
        // the mesh rig have identical hierarchies.
        //
        // If they ever diverge — a different skin, a re-export, a renamed bone —
        // the clip binds to nothing and the zombie T-poses through the entire
        // match. The controller still has its six parameters, every state still
        // has a motion, the prefab still has an Animator, and every other test in
        // this file still passes. Sampling the clip onto the real model is the
        // only thing that asks the actual question.
        var prefab = LoadZombie();
        var instance = (GameObject)PrefabUtility.InstantiatePrefab(prefab);
        try
        {
            var animator = instance.GetComponentInChildren<Animator>(true);
            Assert.IsNotNull(animator, "The zombie has no Animator.");
            var model = animator.gameObject;

            var controller = AssetDatabase.LoadAssetAtPath<AnimatorController>(
                ZombieImporter.ControllerPath);

            // Walk is the one the game spends nearly all its time in.
            var walk = controller.layers[0].stateMachine.states
                                 .Select(s => s.state)
                                 .FirstOrDefault(s => s.name == "Walk1");
            Assert.IsNotNull(walk, "No Walk1 state.");
            var clip = walk.motion as AnimationClip;
            Assert.IsNotNull(clip, "Walk1's motion is not an AnimationClip.");
            Assert.Greater(clip.length, 0.05f, "Walk1's clip has no length.");

            var bones = model.GetComponentsInChildren<Transform>(true);
            var watched = bones.Where(b => b.name == "thigh_l" || b.name == "calf_l" ||
                                           b.name == "spine_03" || b.name == "upperarm_r")
                               .ToList();
            Assert.IsNotEmpty(watched, "None of the watched bones exist on this skeleton.");

            clip.SampleAnimation(model, 0f);
            var start = watched.Select(b => b.localRotation).ToList();

            clip.SampleAnimation(model, clip.length * 0.5f);
            var half = watched.Select(b => b.localRotation).ToList();

            float moved = 0f;
            for (int i = 0; i < watched.Count; i++)
                moved += Quaternion.Angle(start[i], half[i]);

            Assert.Greater(moved, 5f,
                "Sampling Walk1 at t=0 and t=" + (clip.length * 0.5f).ToString("F2") +
                " moved the watched bones by a total of " + moved.ToString("F2") + " degrees. " +
                "The clip is not binding to this model's skeleton, so the zombie will T-pose " +
                "through the whole match while every other check passes.");
        }
        finally
        {
            Object.DestroyImmediate(instance);
        }
    }

    [Test]
    public void Zombie_IsOnTheEnemyLayerSoWhatIsEnemyMatchesSomething()
    {
        int enemy = LayerMask.NameToLayer(ZombieImporter.k_EnemyLayer);
        Assert.GreaterOrEqual(enemy, 0, "There is no 'Enemy' layer in TagManager.asset.");

        // WeaponRigBuilder sets every gun's whatIsEnemy to exactly this layer. On
        // any other layer that field is a serialized knob that matches nothing.
        foreach (var t in LoadZombie().GetComponentsInChildren<Transform>(true))
            Assert.AreEqual(enemy, t.gameObject.layer,
                "'" + t.name + "' is on layer " + LayerMask.LayerToName(t.gameObject.layer) +
                ", not '" + ZombieImporter.k_EnemyLayer + "'.");
    }

    [Test]
    public void Map_EveryCameraRendersTheEnemyLayer_Law9()
    {
        // §14 Law 9. Now that the zombie is on its own layer, a cullingMask that
        // drops it makes the ENTIRE HORDE INVISIBLE — while they still path to
        // you, still damage you, and still die. Nothing is logged. This is the
        // guard that stops a later render tweak doing that.
        int enemy = LayerMask.NameToLayer(ZombieImporter.k_EnemyLayer);
        var cameras = FindAll<Camera>();
        Assert.IsNotEmpty(cameras, "The map has no Camera.");

        foreach (var cam in cameras)
            Assert.AreNotEqual(0, cam.cullingMask & (1 << enemy),
                "Camera '" + cam.name + "' does not render the Enemy layer, so zombies would be " +
                "invisible while remaining fully lethal.");
    }

    [Test]
    public void Zombie_MaterialsUseTheUniversalRenderPipelineShader()
    {
        // The pack's URP materials are named "Buit-in_*", which is exactly why
        // they were believed not to exist. This asserts the substance rather than
        // the name: whatever the file is called, it must be on the same shader the
        // project's own kit materials use, or the zombie renders magenta.
        var renderers = LoadZombie().GetComponentsInChildren<Renderer>(true);
        Assert.Greater(renderers.Length, 0, "The zombie has no Renderer.");

        foreach (var r in renderers)
        foreach (var m in r.sharedMaterials)
        {
            Assert.IsNotNull(m, "A material slot on '" + r.name + "' is empty.");
            Assert.IsNotNull(m.shader, "Material '" + m.name + "' has no shader.");
            StringAssert.StartsWith("Universal Render Pipeline/", m.shader.name,
                "Material '" + m.name + "' is on shader '" + m.shader.name + "', which is not a " +
                "URP shader — it will render magenta in this project.");
        }
    }

    [Test]
    public void Zombie_TextureBindingsAllResolve()
    {
        // The whole reason the .meta sidecars were carried across. A material
        // references its textures by GUID; a fresh GUID makes every binding land
        // on nothing, with NO import error at all.
        foreach (var r in LoadZombie().GetComponentsInChildren<Renderer>(true))
        foreach (var m in r.sharedMaterials)
        {
            if (m == null) continue;
            foreach (var prop in new[] { "_BaseMap", "_BumpMap", "_OcclusionMap" })
            {
                if (!m.HasProperty(prop)) continue;
                Assert.IsNotNull(m.GetTexture(prop),
                    "Material '" + m.name + "' has no texture on " + prop + ". If the .meta " +
                    "sidecars were not copied, every binding resolves to null silently.");
            }
        }
    }
}
