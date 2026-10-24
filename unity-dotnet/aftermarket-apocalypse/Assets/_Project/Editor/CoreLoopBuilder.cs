using System.Collections.Generic;
using System.Linq;
using TMPro;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.InputSystem.UI;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

/// <summary>
/// The permanent systems every gameplay scene needs, and the round director that
/// makes the map a game rather than a walk-around.
///
/// <para><b>This replaces <c>PlaytestRig</c>, which is deleted.</b> That tool was
/// scaffolding: it existed to answer "do Phases 4-7 work at all", built a
/// ScoreManager and a prompt HUD under a root called <c>PlaytestRig</c>, and told
/// you in its own log that it was not phase content. The thing it scaffolded for
/// is what this builds, so keeping both would mean two tools that create a
/// ScoreManager and a <c>ScoreManager.Awake</c> that warns about duplicates.</para>
///
/// <para><b>Why this is a gap and not a phase.</b> §17's twelve phases contain no
/// enemies/rounds/HUD phase — Phase 5 is "Barriers / windows: the round-one loop"
/// and Phase 7 is "Perks, PaP, power-ups, dogs", and both are meaningless unless
/// ordinary zombies already work. <see cref="EnemySpawner"/> and
/// <see cref="RoundRules"/> have existed since Phase 1. They were simply never
/// put in the scene.</para>
///
/// <para><b>Idempotent AND non-destructive</b>, which are not the same thing —
/// that is the <c>WeaponRigBuilder</c> lesson. Everything is found by name and
/// reused; nothing is ever destroyed except the retired <c>PlaytestRig</c> root.
/// So you can re-run this after hand-tuning HUD positions and keep the tuning.
/// The one thing it re-asserts every run is the set of cross-object references
/// (prompt, essence text, round text, spawn points), because a null there is a
/// silent feature-absent, which is the failure mode this whole project keeps
/// recording.</para>
///
/// <para><b><c>Systems</c> is NOT a phase root.</b> The phase roots are documented
/// undo units — "deleting the root undoes the whole pass". This one is permanent
/// scene content and deleting it breaks the game. That distinction is exactly
/// what <see cref="Phase7Builder"/> got wrong by putting <c>PowerUpManager</c>
/// inside <c>Phase7</c>.</para>
/// </summary>
public static class CoreLoopBuilder
{
    /// <summary>Which scene Build() was asked for, so the async font-import
    /// continuation re-enters on the same one instead of on the default map.</summary>
    static string s_TargetScene;

    public const string SystemsRoot = "Systems";
    const string k_RetiredRig = "PlaytestRig";

    /// <summary>The file TMP's own importer uses to decide the essentials are in.</summary>
    const string k_TmpSettings = "Assets/TextMesh Pro/Resources/TMP Settings.asset";

    /// <summary>Guards against queueing the package import twice on a double click.</summary>
    static bool s_ImportInFlight;

    /// <summary>
    /// Zombie first, then the scene, because <see cref="BuildSpawner"/> looks the
    /// Enemy prefab up off disk — run the other way round on a fresh project and
    /// the spawner is wired to nothing and says so.
    ///
    /// <para>Exists as a single entry point so a batchmode run is one boot rather
    /// than two:
    /// <c>Unity.exe -batchmode -projectPath . -executeMethod CoreLoopBuilder.BuildAll -quit</c>.</para>
    /// </summary>
    [MenuItem("Tools/Aftermarket/Build Everything (zombie + core loop)")]
    public static void BuildAll()
    {
        // STOP if the zombie did not build. Carrying on would re-wire the spawner
        // to a stale Enemy.prefab and report success — which is exactly what
        // happened the first time this ran against a switched skin.
        if (!ZombieImporter.Import())
        {
            Debug.LogError("[CoreLoop] the zombie did not build, so the scene was left alone " +
                           "rather than wired to a stale prefab. Fix the error above and re-run.");
            return;
        }
        Build();
    }

    [MenuItem("Tools/Aftermarket/Build Core Loop (systems + HUD + rounds)")]
    public static void Build()
    {
        Build(MapSeeder.ScenePath);
    }

    /// <summary>Wire the permanent systems into a NAMED scene. The project has more
    /// than one map now, and every system built below is a property of a playable
    /// scene rather than of AshfallDepot.</summary>
    public static void Build(string scenePath)
    {
        s_TargetScene = scenePath;
        if (!MapBake.OpenSceneIfNeeded("CoreLoop", scenePath)) return;

        // FONT FIRST, BEFORE ANY TMP COMPONENT IS CREATED.
        //
        // Without the TMP Essential Resources a TextMeshProUGUI has no font and no
        // material: it renders NOTHING while looking perfectly healthy in the
        // hierarchy. Building the HUD first and finding out afterwards is how you
        // ship an invisible UI and call it wired. Carried over from PlaytestRig,
        // which learned it the hard way.
        var font = FindAnyFont();
        if (font == null)
        {
            // Not an error: the import is async, so "no font yet" is the expected
            // state on a machine that has never imported them.
            StartTmpImportThenFinish();
            return;
        }

        var scene = SceneManager.GetActiveScene();
        int retired = RetireThePlaytestRig(scene);

        var root = FindOrCreateRoot(scene, SystemsRoot);

        // --- currency -------------------------------------------------------
        // The opening balance is NOT set here. ScoreManager reads
        // RoundRules.StartingEssence in Awake and its essence field is
        // [NonSerialized], so this scene cannot carry a stale literal.
        var score = FindOrAdd<ScoreManager>(root, "ScoreManager");

        // --- the gum machine's own prerequisite -----------------------------
        // GobblegumMachine.Interact returns false at GobblegumManager.instance ==
        // null BEFORE it reaches TrySpend, and nothing anywhere creates one.
        FindOrAdd<GobblegumManager>(root, "GobblegumManager");

        // --- UI event plumbing ----------------------------------------------
        var eventSystem = BuildEventSystem(root);

        // --- HUD ------------------------------------------------------------
        var hud = BuildHud(root, font, out var prompt, out var essenceValue, out var roundText);

        // --- pause, with the HUD actually wired to it -----------------------
        // GameBootstrap creates a "PauseController (auto)" at runtime when none
        // exists, but with no references — so Esc paused and the HUD stayed up
        // over the pause menu. A real one in the scene can carry hudRoot.
        var pause = FindOrAdd<PauseMenu>(root, "PauseController");
        Undo.RecordObject(pause, "Wire pause");
        pause.hudRoot = hud.gameObject;
        EditorUtility.SetDirty(pause);

        // --- rounds ---------------------------------------------------------
        var spawner = BuildSpawner(root, roundText, out int spawnPointCount, out string spawnNote);

        // --- the references that necessarily live outside the root ----------
        var interaction = Object.FindFirstObjectByType<InteractionSystem>();
        if (interaction == null)
        {
            Debug.LogError("[CoreLoop] no InteractionSystem in the scene — the HUD was built but " +
                           "no prompt can ever reach it. Run Tools > Aftermarket > Wire Interaction.");
        }
        else
        {
            Undo.RecordObject(interaction, "Wire prompt");
            interaction.promptText = prompt;
            EditorUtility.SetDirty(interaction);
        }

        Undo.RecordObject(score, "Wire essence text");
        score.essenceText = essenceValue;
        EditorUtility.SetDirty(score);

        EditorSceneManager.MarkSceneDirty(scene);
        EditorSceneManager.SaveScene(scene);

        // VERIFY WHAT WAS BUILT BY READING IT BACK, rather than asserting it.
        // Every line below is measured off the live components.
        string fontName = prompt.font != null ? prompt.font.name : "NULL";
        string wiredTo = interaction != null ? interaction.name : "NOTHING";
        string enemyPrefab = spawner.enemyPrefab != null ? spawner.enemyPrefab.name : "NONE";
        string retiredNote = retired > 0
            ? "  Retired the old PlaytestRig root (" + retired + " object) — this replaces it.\n"
            : "";

        if (prompt.font == null || essenceValue.font == null || roundText.font == null)
        {
            Debug.LogError("[CoreLoop] HUD text has no font after construction. It will render " +
                           "nothing. Delete the Systems root and import the TMP Essential " +
                           "Resources by hand.");
        }

        Debug.Log("[CoreLoop] built under '" + SystemsRoot + "'.\n" + retiredNote +
                  "  ScoreManager      starts at RoundRules.StartingEssence = " + RoundRules.StartingEssence + " (not a scene value)\n" +
                  "  GobblegumManager  present\n" +
                  "  EventSystem       " + eventSystem.name + " + InputSystemUIInputModule\n" +
                  "  HUD               font = " + fontName + ", prompt -> " + wiredTo + ", essence + round wired\n" +
                  "  EnemySpawner      " + spawnPointCount + " spawn points, enemyPrefab = " + enemyPrefab + "\n" +
                  "                    " + spawnNote + "\n" +
                  "  Navmesh NOT rebaked, correctly — this adds no colliders and no renderers.");
    }

    // ---------------------------------------------------------------- pieces

    /// <summary>
    /// The <c>EventSystem</c> the scene has never had.
    ///
    /// <para><b>It must be the Input System module, not <c>StandaloneInputModule</c>.</b>
    /// <c>ProjectSettings.activeInputHandler</c> is 1 (Input System only), so the
    /// legacy module throws <c>InvalidOperationException</c> the moment it reads
    /// <c>UnityEngine.Input</c> — every frame, from a component whose whole job is
    /// to be invisible until something needs clicking.</para>
    ///
    /// <para><b>No actions asset is assigned, on purpose.</b>
    /// <c>InputSystemUIInputModule.OnEnable</c> calls <c>AssignDefaultActions()</c>
    /// when it has none, which instantiates the package's built-in
    /// <c>DefaultInputActions</c> — nothing to wire and nothing to lose. Letting
    /// Unity's <c>Reset()</c> path run instead would serialize a reference to
    /// <c>Assets/InputSystem_Actions.inputactions</c>, and CLAUDE.md is explicit
    /// that that template is NOT the gameplay source and editing it changes
    /// nothing. A serialized pointer to it would be a lie waiting to be believed.
    /// So this uses a plain <c>AddComponent</c>, which does not call
    /// <c>Reset</c>.</para>
    /// </summary>
    static EventSystem BuildEventSystem(GameObject root)
    {
        // Scene-wide, not root-wide: EventSystem is a singleton and a second one
        // disables itself with a warning.
        var existing = Object.FindFirstObjectByType<EventSystem>();
        if (existing != null)
        {
            if (existing.GetComponent<InputSystemUIInputModule>() == null)
            {
                if (existing.GetComponent<StandaloneInputModule>() is StandaloneInputModule legacy)
                {
                    Debug.LogWarning("[CoreLoop] the existing EventSystem carries a legacy " +
                                     "StandaloneInputModule, which throws under Input-System-only. " +
                                     "Replacing it with InputSystemUIInputModule.");
                    Undo.DestroyObjectImmediate(legacy);
                }
                Undo.AddComponent<InputSystemUIInputModule>(existing.gameObject);
            }
            return existing;
        }

        var go = new GameObject("EventSystem");
        Undo.RegisterCreatedObjectUndo(go, "Build EventSystem");
        go.transform.SetParent(root.transform, false);
        var es = go.AddComponent<EventSystem>();
        go.AddComponent<InputSystemUIInputModule>();
        return es;
    }

    static Canvas BuildHud(GameObject root, TMP_FontAsset font,
                           out TextMeshProUGUI prompt, out TextMeshProUGUI essenceValue,
                           out TextMeshProUGUI roundText)
    {
        var hudGo = FindOrCreateChild(root.transform, "HUD");

        var canvas = hudGo.GetComponent<Canvas>();
        if (canvas == null) canvas = Undo.AddComponent<Canvas>(hudGo);
        canvas.renderMode = RenderMode.ScreenSpaceOverlay;
        canvas.sortingOrder = 100;

        var scaler = hudGo.GetComponent<CanvasScaler>();
        if (scaler == null) scaler = Undo.AddComponent<CanvasScaler>(hudGo);
        scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
        scaler.referenceResolution = new Vector2(1920f, 1080f);

        // No GraphicRaycaster. Nothing on this canvas is clickable, and a raycaster
        // spanning the whole screen is a good way to start eating input later. A
        // pause panel that needs one can add it to its own canvas.

        prompt = MakeText(hudGo.transform, "Prompt", font, 34f,
                          new Vector2(0.5f, 0.5f), new Vector2(0f, -90f),
                          new Vector2(900f, 60f), TextAlignmentOptions.Center);
        // InteractionSystem.SetPrompt toggles activeSelf, so its starting state does
        // not matter, but starting hidden means an empty bar never flashes on load.
        if (string.IsNullOrEmpty(prompt.text)) prompt.text = "";

        var crosshair = MakeText(hudGo.transform, "Crosshair", font, 30f,
                                 new Vector2(0.5f, 0.5f), Vector2.zero,
                                 new Vector2(60f, 60f), TextAlignmentOptions.Center);
        // The interaction raycast leaves the camera centre. With no mark there you
        // cannot tell what you are aiming at, so "no prompt" is ambiguous between
        // "not looking at it" and "it is broken".
        crosshair.text = "+";

        var essenceLabel = MakeText(hudGo.transform, "EssenceLabel", font, 22f,
                                    new Vector2(0f, 0f), new Vector2(150f, 96f),
                                    new Vector2(240f, 30f), TextAlignmentOptions.Left);
        essenceLabel.text = "ESSENCE";

        essenceValue = MakeText(hudGo.transform, "EssenceValue", font, 44f,
                                new Vector2(0f, 0f), new Vector2(180f, 56f),
                                new Vector2(300f, 56f), TextAlignmentOptions.Left);
        // ScoreManager.RefreshUI writes the bare number here on every Award/TrySpend.
        // Showing the real starting value means a HUD that reads 0 at runtime is
        // visibly a wiring failure rather than a plausible balance.
        essenceValue.text = RoundRules.StartingEssence.ToString();

        // ROUND TEXT IS ANCHORED AT THE SCREEN CENTRE, and that is load-bearing.
        // EnemySpawner.AnimateRoundStart drives anchoredPosition from textCenterPos
        // (0,0) to textCornerPos and fontSize from centerFontSize to cornerFontSize.
        // Those are offsets from THIS anchor, so anchoring anywhere else sends the
        // round announcement off-screen.
        roundText = MakeText(hudGo.transform, "RoundText", font, 100f,
                             new Vector2(0.5f, 0.5f), new Vector2(-760f, 440f),
                             new Vector2(900f, 340f), TextAlignmentOptions.Center);
        roundText.text = "";
        roundText.color = Color.red;

        return canvas;
    }

    /// <summary>
    /// Places the round director and points it at the spawn points
    /// <see cref="MapSeeder"/> derived from the baked navmesh.
    ///
    /// <para><b>It reads <c>EnemySpawnPoints</c> from the scene rather than taking
    /// coordinates.</b> Those seven points were DERIVED — scored against the
    /// navmesh for walkable area and for a complete path to the player — so their
    /// positions are unknowable to anything typing literals, and re-seeding the
    /// map moves them. This is the same rule <see cref="MapBake.KeepOutPoints"/>
    /// follows, and it is why the brief's "use MapBake, do not invent a second
    /// spawn system" is the right instruction: there is exactly one spawn set and
    /// it lives in the scene.</para>
    ///
    /// <para><b>Pacing is not touched.</b> Wave size, the concurrent cap and the
    /// spawn interval all come from <see cref="RoundRules"/>; the spawner has no
    /// inspector field for any of them, which is the cure for the Part I
    /// "READ FIRST" bug where the scene ran <c>timeBetweenSpawns = 5</c> while the
    /// script said 2.</para>
    /// </summary>
    static EnemySpawner BuildSpawner(GameObject root, TextMeshProUGUI roundText,
                                     out int spawnPointCount, out string note)
    {
        var spawner = FindOrAdd<EnemySpawner>(root, "EnemySpawner");
        Undo.RecordObject(spawner, "Wire spawner");

        spawner.roundText = roundText;

        var spawnRoot = GameObject.Find("EnemySpawnPoints");
        if (spawnRoot == null)
        {
            spawner.spawnPoints = new Transform[0];
            spawnPointCount = 0;
            note = "NO 'EnemySpawnPoints' ROOT IN THE SCENE — no zombie can spawn.";
            Debug.LogError("[CoreLoop] " + note);
            EditorUtility.SetDirty(spawner);
            return spawner;
        }

        var points = new System.Collections.Generic.List<Transform>();
        foreach (Transform t in spawnRoot.transform) points.Add(t);
        points.Sort((a, b) => string.CompareOrdinal(a.name, b.name));
        spawner.spawnPoints = points.ToArray();
        spawnPointCount = points.Count;

        // The zombie prefab, if it has been imported. Left null rather than
        // guessed: EnemySpawner.TrySpawnEnemy opens with
        // `if (enemyPrefab == null) return false;`, so a wrong prefab is worse
        // than none — it would spawn something that is not a zombie and the round
        // would still never end.
        if (spawner.enemyPrefab == null)
        {
            var found = AssetDatabase.LoadAssetAtPath<GameObject>(ZombieImporter.EnemyPrefabPath);
            if (found != null) spawner.enemyPrefab = found;
        }

        // THE OTHER SKINS. Rebuilt from disk every run rather than preserved,
        // because a stale entry here points at a prefab that may have been
        // deleted by a --skin re-import, and a null in that array used to be a
        // silent lost spawn. Whatever is on disk now IS the answer.
        var variants = new List<GameObject>();
        for (int skin = 1; skin <= 3; skin++)
        {
            if (skin == ZombieImporter.PrimarySkin) continue;
            var v = AssetDatabase.LoadAssetAtPath<GameObject>(ZombieImporter.PrefabPathForSkin(skin));
            if (v != null) variants.Add(v);
        }
        spawner.variantPrefabs = variants.ToArray();

        note = spawner.enemyPrefab == null
            ? "enemyPrefab is NULL — rounds will start and no zombie will appear. Run Tools > Aftermarket > Import Zombie."
            : "wired to " + spawner.enemyPrefab.name + " + " + variants.Count +
              " variant look(s): " + (variants.Count == 0
                  ? "none imported"
                  : string.Join(", ", variants.Select(v => v.name)));
        if (spawner.enemyPrefab == null) Debug.LogWarning("[CoreLoop] " + note);

        EditorUtility.SetDirty(spawner);
        return spawner;
    }

    /// <summary>
    /// Deletes the retired <c>PlaytestRig</c> root, if the scene still carries one.
    ///
    /// <para>Run BEFORE the Systems root is built, so the scene never briefly holds
    /// two ScoreManagers — <c>ScoreManager.Awake</c> destroys the duplicate with a
    /// warning, and which one survives depends on load order.</para>
    /// </summary>
    static int RetireThePlaytestRig(Scene scene)
    {
        GameObject rig = null;
        foreach (var go in scene.GetRootGameObjects())
            if (go.name == k_RetiredRig) rig = go;
        if (rig == null) return 0;

        // Clear the one outbound reference first, so it is explicitly nulled rather
        // than left as a broken fileID for the next save to drop silently. It is
        // re-pointed at the new HUD a few lines later.
        var interaction = Object.FindFirstObjectByType<InteractionSystem>();
        if (interaction != null)
        {
            Undo.RecordObject(interaction, "Unwire playtest prompt");
            interaction.promptText = null;
            EditorUtility.SetDirty(interaction);
        }

        Undo.DestroyObjectImmediate(rig);
        return 1;
    }

    // ---------------------------------------------------------------- helpers

    static GameObject FindOrCreateRoot(Scene scene, string name)
    {
        foreach (var go in scene.GetRootGameObjects())
            if (go.name == name) return go;

        var root = new GameObject(name);
        Undo.RegisterCreatedObjectUndo(root, "Build Core Loop");
        return root;
    }

    static GameObject FindOrCreateChild(Transform parent, string name)
    {
        var existing = parent.Find(name);
        if (existing != null) return existing.gameObject;

        // RectTransform, because everything created through this path is UI. A
        // plain Transform gets silently replaced when a Canvas is added, which
        // loses any Undo record made against it.
        var go = new GameObject(name, typeof(RectTransform));
        Undo.RegisterCreatedObjectUndo(go, "Build Core Loop");
        go.transform.SetParent(parent, false);
        return go;
    }

    static T FindOrAdd<T>(GameObject root, string childName) where T : Component
    {
        // Prefer an existing component anywhere in the scene over making a second
        // one: these are all effective singletons and a duplicate is a bug that
        // only shows up at runtime.
        var anywhere = Object.FindFirstObjectByType<T>();
        if (anywhere != null) return anywhere;

        var child = root.transform.Find(childName);
        var go = child != null ? child.gameObject : new GameObject(childName);
        if (child == null)
        {
            Undo.RegisterCreatedObjectUndo(go, "Build Core Loop");
            go.transform.SetParent(root.transform, false);
        }
        return Undo.AddComponent<T>(go);
    }

    /// <summary>
    /// Creates the text if it is missing, and otherwise leaves an existing one's
    /// transform and size ALONE — only the font is re-asserted.
    ///
    /// <para>That asymmetry is deliberate. A null font renders nothing and is
    /// always a bug; a hand-nudged position is work, and a tool that calls itself
    /// idempotent while flattening hand work is the <c>WeaponRigBuilder</c>
    /// mistake.</para>
    /// </summary>
    static TextMeshProUGUI MakeText(Transform parent, string name, TMP_FontAsset font,
                                    float size, Vector2 anchor, Vector2 offset,
                                    Vector2 dimensions, TextAlignmentOptions align)
    {
        var existing = parent.Find(name);
        if (existing != null)
        {
            var found = existing.GetComponent<TextMeshProUGUI>();
            if (found == null) found = Undo.AddComponent<TextMeshProUGUI>(existing.gameObject);
            if (found.font == null)
            {
                Undo.RecordObject(found, "Re-assert font");
                found.font = font;
                EditorUtility.SetDirty(found);
            }
            return found;
        }

        var go = new GameObject(name, typeof(RectTransform));
        Undo.RegisterCreatedObjectUndo(go, "Build Core Loop");
        go.transform.SetParent(parent, false);

        var rect = go.GetComponent<RectTransform>();
        rect.anchorMin = anchor;
        rect.anchorMax = anchor;
        rect.pivot = new Vector2(0.5f, 0.5f);
        rect.anchoredPosition = offset;
        rect.sizeDelta = dimensions;

        var tmp = go.AddComponent<TextMeshProUGUI>();
        tmp.font = font;
        tmp.fontSize = size;
        tmp.alignment = align;
        tmp.color = Color.white;
        tmp.raycastTarget = false;
        return tmp;
    }

    // ---------------------------------------------------------------- TMP font
    // Carried over verbatim in behaviour from PlaytestRig, which is being deleted.
    // The async-import handling below was paid for once already: the first version
    // imported the package and looked for a font in the same call stack, found
    // none, and bailed out having built nothing -- while the import it had just
    // started was succeeding.

    static void StartTmpImportThenFinish()
    {
        if (s_ImportInFlight)
        {
            Debug.Log("[CoreLoop] the TMP import is still running — the build will finish by " +
                      "itself when it lands. Nothing to do.");
            return;
        }

        s_ImportInFlight = true;
        AssetDatabase.importPackageCompleted += OnPackageImported;
        AssetDatabase.importPackageFailed += OnPackageFailed;
        AssetDatabase.importPackageCancelled += OnPackageCancelled;

        Debug.Log("[CoreLoop] the TMP Essential Resources are not imported, so TextMeshProUGUI " +
                  "has no font and would render nothing. Importing them into Assets/TextMesh Pro/.\n" +
                  "  This is ASYNCHRONOUS — the build finishes itself when the import completes. " +
                  "If nothing happens, press the menu item again; the font will exist by then.\n" +
                  "  The import is PERMANENT and project-wide, and is also what lets " +
                  "GameFeedback's and FloatingText's world-space popups render at all.");

        TMP_PackageResourceImporter.ImportResources(true, false, false);
    }

    static void OnPackageImported(string packageName)
    {
        // importPackageCompleted fires for ANY package, so check it is ours —
        // otherwise an unrelated import triggers a scene build.
        if (packageName == null || !packageName.Contains("TMP Essential Resources")) return;
        StopListening();

        EditorApplication.delayCall += () =>
        {
            AssetDatabase.Refresh();
            if (FindAnyFont() == null)
            {
                Debug.LogError("[CoreLoop] the TMP Essential Resources imported but no " +
                               "TMP_FontAsset can be found, so NOTHING was built — an invisible " +
                               "HUD is worse than no HUD.");
                return;
            }
            // The scene Build() was originally asked for, not the default map.
            Build(string.IsNullOrEmpty(s_TargetScene) ? MapSeeder.ScenePath : s_TargetScene);
        };
    }

    static void OnPackageFailed(string packageName, string errorMessage)
    {
        if (packageName == null || !packageName.Contains("TMP Essential Resources")) return;
        StopListening();
        Debug.LogError("[CoreLoop] the TMP Essential Resources import FAILED (" + errorMessage +
                       "). Nothing was built. Run Window > TextMeshPro > Import TMP Essential " +
                       "Resources by hand, then press the menu item again.");
    }

    static void OnPackageCancelled(string packageName)
    {
        if (packageName == null || !packageName.Contains("TMP Essential Resources")) return;
        StopListening();
        Debug.LogWarning("[CoreLoop] the TMP Essential Resources import was cancelled, so nothing " +
                         "was built.");
    }

    static void StopListening()
    {
        AssetDatabase.importPackageCompleted -= OnPackageImported;
        AssetDatabase.importPackageFailed -= OnPackageFailed;
        AssetDatabase.importPackageCancelled -= OnPackageCancelled;
        s_ImportInFlight = false;
    }

    static TMP_FontAsset FindAnyFont()
    {
        // TMP_Settings.instance loads from Resources, so with no settings asset the
        // property getter is what throws. Guard the whole lookup.
        try
        {
            if (TMP_Settings.instance != null && TMP_Settings.defaultFontAsset != null)
                return TMP_Settings.defaultFontAsset;
        }
        catch (System.Exception e)
        {
            Debug.Log("[CoreLoop] TMP_Settings is not usable yet (" + e.GetType().Name +
                      "); falling back to a direct asset search.");
        }

        foreach (var guid in AssetDatabase.FindAssets("t:TMP_FontAsset"))
        {
            var asset = AssetDatabase.LoadAssetAtPath<TMP_FontAsset>(
                AssetDatabase.GUIDToAssetPath(guid));
            if (asset != null) return asset;
        }
        return null;
    }
}
