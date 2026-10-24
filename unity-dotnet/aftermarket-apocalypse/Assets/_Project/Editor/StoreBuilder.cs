using System.Collections.Generic;
using System.IO;
using System.Reflection;
using TMPro;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

/// <summary>
/// Builds <c>Assets/_Project/Scenes/Store.unity</c> — the Phase 8 store screen
/// (§15.12.3) and the Sacred opening rig (§15.8.1).
///
/// <para><c>Tools &gt; Aftermarket &gt; Build Store</c>. The scene is a BUILD
/// OUTPUT, unlike AshfallDepot: re-running rebuilds it from scratch. Nothing here
/// is a place for hand edits, so nothing here needs to survive one.</para>
///
/// <para><b>Wiring is the whole job.</b> <see cref="SacredOpening"/> degrades
/// quietly on every null field — deliberately, so a missing AudioSource is not a
/// crash — which means a half-wired scene looks perfectly healthy and shows
/// nothing. <see cref="Verify"/> therefore walks both components by REFLECTION
/// and reports every public field it finds, rather than checking the handful
/// somebody remembered to list.</para>
/// </summary>
public static class StoreBuilder
{
    const string k_ScenePath = "Assets/_Project/Scenes/Store.unity";
    const string k_SacredDir = "Assets/_Project/Content/Sacreds";
    const string k_DesignDir = "Assets/_Project/Content/SkinDesigns";

    /// <summary>The reel pitch comes from this. SacredOpening's Pitch reads
    /// <c>cardTemplate.rect.width</c>, so a zero-width template would stack all
    /// thirty cards on top of each other and the reel would never move.</summary>
    const float k_CardWidth = 160f;
    const float k_CardHeight = 220f;

    // Fields that are null ON PURPOSE. Listed here so the read-back can say
    // "intentionally empty" instead of "MISSING", which is the difference between
    // a report you read and a report you learn to ignore.
    static readonly HashSet<string> k_DeliberatelyNull = new HashSet<string>
    {
        "SacredOpening.tickSource",
        "SacredOpening.stingSource",
    };

    [MenuItem("Tools/Aftermarket/Build Store")]
    public static void Build()
    {
        // *** THIS GUARD IS NOT OPTIONAL AND MUST COME FIRST. ***
        // EditorSceneManager.NewScene does NOT prompt when called from a script:
        // it discards unsaved changes and clears the undo stack. Run this with
        // AshfallDepot open and an hour of hand dressing unsaved, and the dressing
        // is gone with no undo. CLAUDE.md records this exact trap being paid for
        // four times (SceneShot, SkySetup, MapSceneTests, CharacterSelectBuilder).
        if (!EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
        {
            Debug.Log("[Store] cancelled — the open scene has unsaved changes and building " +
                      "would discard them.");
            return;
        }

        var log = new List<string>();
        BuildScene(log);
        AssetDatabase.SaveAssets();
        AssetDatabase.Refresh();
        Debug.Log("[Store]\n  " + string.Join("\n  ", log));
    }

    // ------------------------------------------------------------- the content

    static SacredDefinition[] LoadSacreds()
    {
        var found = new List<SacredDefinition>();
        foreach (string guid in AssetDatabase.FindAssets("t:SacredDefinition", new[] { k_SacredDir }))
        {
            var s = AssetDatabase.LoadAssetAtPath<SacredDefinition>(AssetDatabase.GUIDToAssetPath(guid));
            if (s != null) found.Add(s);
        }
        // FindAssets' order is not documented and is not stable across machines or
        // reimports. The grid is built in array order, so an unsorted load would
        // shuffle the storefront between runs for no reason and make any screenshot
        // diff meaningless.
        found.Sort(delegate (SacredDefinition a, SacredDefinition b)
        {
            int byPrice = a.price.CompareTo(b.price);
            return byPrice != 0 ? byPrice : string.CompareOrdinal(a.sacredId, b.sacredId);
        });
        return found.ToArray();
    }

    static SkinDesign[] LoadDesigns()
    {
        var found = new List<SkinDesign>();
        foreach (string guid in AssetDatabase.FindAssets("t:SkinDesign", new[] { k_DesignDir }))
        {
            var d = AssetDatabase.LoadAssetAtPath<SkinDesign>(AssetDatabase.GUIDToAssetPath(guid));
            if (d != null) found.Add(d);
        }
        found.Sort(delegate (SkinDesign a, SkinDesign b)
        {
            return string.CompareOrdinal(a.designId, b.designId);
        });
        return found.ToArray();
    }

    // --------------------------------------------------------------- the scene

    static void BuildScene(List<string> log)
    {
        Scene scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);

        RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Flat;
        RenderSettings.ambientLight = new Color(0.28f, 0.28f, 0.30f, 1f);

        // A screen-space-overlay canvas renders with no camera at all, but a scene
        // with none logs "No cameras rendering" every frame and cannot be
        // screenshotted.
        var camGo = new GameObject("UICamera");
        camGo.transform.localPosition = new Vector3(0f, 0f, -10f);
        Camera cam = camGo.AddComponent<Camera>();
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.05f, 0.05f, 0.07f, 1f);
        cam.cullingMask = ~0;   // Law 9

        TMP_FontAsset font = FindAnyFont();
        if (font == null)
        {
            Debug.LogError("[Store] NO TMP FONT ASSET EXISTS IN THIS PROJECT. Every label in the " +
                           "store will render NOTHING while looking perfectly healthy in the " +
                           "hierarchy. Import the TMP Essential Resources and rebuild.");
            log.Add("NO TMP FONT       every label will be invisible");
        }

        var canvasGo = new GameObject("Canvas", typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster));
        Canvas canvas = canvasGo.GetComponent<Canvas>();
        canvas.renderMode = RenderMode.ScreenSpaceOverlay;
        var scaler = canvasGo.GetComponent<CanvasScaler>();
        scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
        scaler.referenceResolution = new Vector2(1920f, 1080f);
        scaler.matchWidthOrHeight = 0.5f;

        // The EventSystem MUST carry InputSystemUIInputModule. activeInputHandler
        // is 1 (Input System only), so StandaloneInputModule reads
        // UnityEngine.Input and throws every frame — the store would be unclickable
        // behind a wall of exceptions.
        //
        // Plain AddComponent, NOT ObjectFactory: Unity's Reset() path serializes a
        // reference to Assets/InputSystem_Actions.inputactions, the template
        // CLAUDE.md is explicit is NOT the gameplay source. With nothing assigned,
        // OnEnable calls AssignDefaultActions() and uses the package's built-in set.
        var es = new GameObject("EventSystem", typeof(UnityEngine.EventSystems.EventSystem));
        es.AddComponent<UnityEngine.InputSystem.UI.InputSystemUIInputModule>();

        // ---- background + chrome -------------------------------------------
        Image bg = MakeImage(canvasGo.transform, "Background", new Color(0.07f, 0.07f, 0.09f, 1f));
        Stretch((RectTransform)bg.transform);
        bg.raycastTarget = false;

        MakeLabel(canvasGo.transform, "Title", font, 52f, TextAlignmentOptions.Left,
                  "STORE", new Vector2(0f, 1f), new Vector2(40f, -56f), new Vector2(420f, 80f));

        // §15.12.3's left rail. Static text: CHARACTERS is CharacterSelect.unity,
        // and THEMES / CRYSTALS are Phase 11. Shown rather than hidden so the shape
        // of the screen is honest about what exists.
        MakeLabel(canvasGo.transform, "NavRail", font, 28f, TextAlignmentOptions.TopLeft,
                  "<b>SACREDS</b>\n<color=#666666>CHARACTERS\nTHEMES\nCRYSTALS</color>",
                  new Vector2(0f, 1f), new Vector2(40f, -140f), new Vector2(260f, 220f));

        // ---- 1. currency header, top right ---------------------------------
        // The glyphs come from StoreScreen (§15.1 says they are part of the names);
        // these are placeholders that PaintHeader overwrites on the first profile
        // load.
        TMP_Text embers = MakeLabel(canvasGo.transform, "Embers", font, 36f, TextAlignmentOptions.Right,
                                    StoreScreen.EmberGlyph + " —", new Vector2(1f, 1f),
                                    new Vector2(-40f, -46f), new Vector2(300f, 56f));
        TMP_Text crystals = MakeLabel(canvasGo.transform, "Crystals", font, 36f, TextAlignmentOptions.Right,
                                      StoreScreen.CrystalGlyph + " —", new Vector2(1f, 1f),
                                      new Vector2(-40f, -100f), new Vector2(300f, 56f));
        TMP_Text shards = MakeLabel(canvasGo.transform, "Shards", font, 36f, TextAlignmentOptions.Right,
                                    StoreScreen.ShardGlyph + " —", new Vector2(1f, 1f),
                                    new Vector2(-40f, -154f), new Vector2(300f, 56f));

        // ---- 2. the Sacred grid --------------------------------------------
        var gridGo = new GameObject("SacredGrid", typeof(RectTransform), typeof(GridLayoutGroup));
        gridGo.transform.SetParent(canvasGo.transform, false);
        var grid = (RectTransform)gridGo.transform;
        Place(grid, new Vector2(0f, 1f), new Vector2(340f, -230f), new Vector2(1100f, 660f),
              new Vector2(0f, 1f));
        var gl = gridGo.GetComponent<GridLayoutGroup>();
        gl.cellSize = new Vector2(240f, 300f);
        gl.spacing = new Vector2(20f, 20f);
        gl.startCorner = GridLayoutGroup.Corner.UpperLeft;
        gl.childAlignment = TextAnchor.UpperLeft;

        // ONE template, disabled. StoreScreen clones it per Sacred; building the
        // tiles here would be a second source of truth for what a tile is.
        Button sacredTemplate = MakeTile(gridGo.transform, "SacredTemplate", font);
        sacredTemplate.gameObject.SetActive(false);

        // ---- 2b. the §15.4 bulk toggle --------------------------------------
        // Directly above the grid, because it changes what clicking the grid DOES.
        // A mode switch parked in a corner is a mode switch nobody knows is on.
        Button bulkToggle = MakeButton(canvasGo.transform, "BulkToggle", font, "BUY ×1",
                                       new Vector2(0f, 1f), new Vector2(490f, -180f),
                                       new Vector2(360f, 62f));
        bulkToggle.GetComponent<Image>().color = new Color(0.30f, 0.42f, 0.62f, 1f);
        TMP_Text bulkToggleLabel = bulkToggle.GetComponentInChildren<TMP_Text>();
        bulkToggleLabel.color = Color.white;
        // The label is left as StoreScreen's OFF text. PaintBulkToggle rewrites it
        // at Start and on every click, so typing the armed text here would be a
        // second copy of the mode's wording that only shows before the first frame.

        MakeLabel(canvasGo.transform, "BulkHint", font, 20f, TextAlignmentOptions.Left,
                  "§15.4 — buy " + DropTable.BulkBuyCount + ", the " +
                  DropTable.TotalSacredsForPurchase(DropTable.BulkBuyCount) + "th is free",
                  new Vector2(0f, 1f), new Vector2(870f, -196f), new Vector2(420f, 40f))
            .color = new Color(0.62f, 0.68f, 0.78f, 1f);

        // ---- 3. the odds panel ---------------------------------------------
        // Left EMPTY. OddsPublication.PanelText() computes it from DropTable's own
        // constants at Start; typing a number here would be a second copy of
        // disclosed odds, drifting the day the pity threshold is tuned.
        TMP_Text odds = MakeLabel(canvasGo.transform, "OddsPanel", font, 20f, TextAlignmentOptions.TopLeft,
                                  "", new Vector2(1f, 1f), new Vector2(-40f, -230f),
                                  new Vector2(420f, 620f));
        odds.color = new Color(0.78f, 0.78f, 0.82f, 1f);

        // ---- 5. the status line --------------------------------------------
        TMP_Text status = MakeLabel(canvasGo.transform, "StatusLabel", font, 30f, TextAlignmentOptions.Center,
                                    "", new Vector2(0.5f, 0f), new Vector2(0f, 60f),
                                    new Vector2(1400f, 60f));
        status.color = new Color(1f, 0.85f, 0.45f, 1f);

        // ---- 4. the confirm dialog -----------------------------------------
        // §15.12.3: never one-click a premium spend.
        var confirmGo = new GameObject("ConfirmPanel", typeof(RectTransform));
        confirmGo.transform.SetParent(canvasGo.transform, false);
        Stretch((RectTransform)confirmGo.transform);

        Image confirmScrim = MakeImage(confirmGo.transform, "Scrim", new Color(0f, 0f, 0f, 0.75f));
        Stretch((RectTransform)confirmScrim.transform);   // eats clicks on the store behind it

        Image confirmCard = MakeImage(confirmGo.transform, "Card", new Color(0.13f, 0.13f, 0.16f, 1f));
        Place((RectTransform)confirmCard.transform, new Vector2(0.5f, 0.5f), Vector2.zero,
              new Vector2(760f, 320f), new Vector2(0.5f, 0.5f));

        TMP_Text confirmText = MakeLabel(confirmCard.transform, "ConfirmText", font, 34f,
                                         TextAlignmentOptions.Center, "?", new Vector2(0.5f, 1f),
                                         new Vector2(0f, -90f), new Vector2(700f, 140f));
        Button yes = MakeButton(confirmCard.transform, "ConfirmYes", font, "CONFIRM",
                                new Vector2(0.5f, 0f), new Vector2(-130f, 70f), new Vector2(220f, 76f));
        Button no = MakeButton(confirmCard.transform, "ConfirmNo", font, "CANCEL",
                               new Vector2(0.5f, 0f), new Vector2(130f, 70f), new Vector2(220f, 76f));
        yes.GetComponent<Image>().color = new Color(0.85f, 0.72f, 0.25f, 1f);
        confirmGo.SetActive(false);

        // ---- 6. the opening rig --------------------------------------------
        // Last child of the Canvas: an overlay canvas draws in hierarchy order, so
        // the reveal has to be built after the store or the store draws over it.
        SacredOpening opening = BuildOpening(canvasGo.transform, font, log);

        // ---- 7 + 8. wire ----------------------------------------------------
        var store = canvasGo.AddComponent<StoreScreen>();
        store.sacreds = LoadSacreds();
        store.designs = LoadDesigns();
        store.embersLabel = embers;
        store.crystalsLabel = crystals;
        store.shardsLabel = shards;
        store.grid = grid;
        store.sacredTemplate = sacredTemplate;
        store.oddsPanel = odds;
        store.confirmPanel = confirmGo;
        store.confirmText = confirmText;
        store.confirmYes = yes;
        store.confirmNo = no;
        store.statusLabel = status;
        store.bulkToggle = bulkToggle;
        store.bulkToggleLabel = bulkToggleLabel;
        store.opening = opening;

        Directory.CreateDirectory(Path.GetDirectoryName(k_ScenePath));
        // The nav bar goes on LAST so it sits above everything else in the
        // canvas draw order — a store panel painted over the only way out is a
        // dead end the player cannot see is a dead end.
        NavBarBuilder.Build(canvasGo.transform, font, k_ScenePath);

        EditorSceneManager.SaveScene(scene, k_ScenePath);

        // Report by READING THE SCENE BACK. Every "built successfully" line in this
        // project that described intent rather than state has eventually been wrong.
        log.Add("scene             " + k_ScenePath);
        foreach (string line in Describe()) log.Add("  " + line);
    }

    static SacredOpening BuildOpening(Transform parent, TMP_FontAsset font, List<string> log)
    {
        var root = new GameObject("Opening", typeof(RectTransform));
        root.transform.SetParent(parent, false);
        Stretch((RectTransform)root.transform);
        var opening = root.AddComponent<SacredOpening>();

        // Draw order inside the rig, back to front: dim, RGB takeover, the reel
        // strip, the centre marker, then the reveal card on top of all of it.
        Image dim = MakeImage(root.transform, "ScreenDim", new Color(0f, 0f, 0f, 0f));
        Stretch((RectTransform)dim.transform);
        dim.raycastTarget = false;

        var rgbGo = new GameObject("RgbTakeover", typeof(RectTransform), typeof(CanvasGroup));
        rgbGo.transform.SetParent(root.transform, false);
        Stretch((RectTransform)rgbGo.transform);
        var rgbGroup = rgbGo.GetComponent<CanvasGroup>();
        rgbGroup.alpha = 0f;
        rgbGroup.blocksRaycasts = false;
        // Four bands so a full-screen takeover reads as a takeover rather than as a
        // tint. SacredOpening only drives the group's alpha, so what is underneath
        // is presentation and is free to change.
        MakeTakeoverBand(rgbGo.transform, "BandR", new Color(1f, 0.15f, 0.25f, 0.55f), 1f);
        MakeTakeoverBand(rgbGo.transform, "BandG", new Color(0.2f, 1f, 0.35f, 0.45f), 0.62f);
        MakeTakeoverBand(rgbGo.transform, "BandB", new Color(0.25f, 0.4f, 1f, 0.45f), 0.34f);
        MakeTakeoverBand(rgbGo.transform, "BandC", new Color(0f, 0f, 0f, 0.55f), 0.16f);

        // The viewport clips the reel. Without a mask the thirty cards run off both
        // sides of the screen and the reel reads as a moving wall rather than as a
        // strip passing a marker.
        // One parent for the whole reel apparatus, so "hide the reel" is a single
        // SetActive instead of three things that have to be remembered separately.
        var stageGo = new GameObject("ReelStage", typeof(RectTransform));
        stageGo.transform.SetParent(root.transform, false);
        var reelStage = (RectTransform)stageGo.transform;
        Place(reelStage, new Vector2(0.5f, 0.5f), Vector2.zero, Vector2.zero, new Vector2(0.5f, 0.5f));

        var viewGo = new GameObject("ReelViewport", typeof(RectTransform), typeof(Image), typeof(RectMask2D));
        viewGo.transform.SetParent(stageGo.transform, false);
        Place((RectTransform)viewGo.transform, new Vector2(0.5f, 0.5f), new Vector2(0f, 40f),
              new Vector2(1180f, k_CardHeight + 40f), new Vector2(0.5f, 0.5f));
        var viewImg = viewGo.GetComponent<Image>();
        viewImg.color = new Color(0.02f, 0.02f, 0.03f, 0.92f);
        viewImg.raycastTarget = false;

        var reelGo = new GameObject("ReelRoot", typeof(RectTransform));
        reelGo.transform.SetParent(viewGo.transform, false);
        var reelRoot = (RectTransform)reelGo.transform;
        // Anchored and pivoted at the viewport's centre, so a card whose own
        // anchoredPosition is (i * pitch, 0) sits exactly i pitches right of the
        // marker — which is the arithmetic SacredOpening.Paint and the landing
        // frame both assume.
        reelRoot.anchorMin = reelRoot.anchorMax = new Vector2(0.5f, 0.5f);
        reelRoot.pivot = new Vector2(0.5f, 0.5f);
        reelRoot.anchoredPosition = Vector2.zero;
        reelRoot.sizeDelta = Vector2.zero;

        RectTransform cardTemplate = MakeCard(reelGo.transform, "CardTemplate", font);
        cardTemplate.gameObject.SetActive(false);

        // The centre marker. Above the viewport in hierarchy order so it is not
        // clipped by the mask, and so cards pass BEHIND it.
        Image marker = MakeImage(stageGo.transform, "CentreMarker", new Color(1f, 0.95f, 0.6f, 1f));
        Place((RectTransform)marker.transform, new Vector2(0.5f, 0.5f), new Vector2(0f, 40f),
              new Vector2(4f, k_CardHeight + 64f), new Vector2(0.5f, 0.5f));
        marker.raycastTarget = false;

        // shakeRoot wraps the reveal card, and ONLY the reveal card. Shaking a
        // parent that also held the dim would shake the whole screen's blackout.
        var shakeGo = new GameObject("ShakeRoot", typeof(RectTransform));
        shakeGo.transform.SetParent(root.transform, false);
        var shakeRoot = (RectTransform)shakeGo.transform;
        Place(shakeRoot, new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(1000f, 760f),
              new Vector2(0.5f, 0.5f));

        var revealGo = new GameObject("RevealCard", typeof(RectTransform), typeof(CanvasGroup));
        revealGo.transform.SetParent(shakeGo.transform, false);
        Stretch((RectTransform)revealGo.transform);
        var revealGroup = revealGo.GetComponent<CanvasGroup>();
        revealGroup.alpha = 0f;
        revealGroup.blocksRaycasts = false;

        // revealBorder is the outer frame — SacredOpening writes the tier colour
        // into it, and animates it through the rainbow for an RGB pull.
        Image border = MakeImage(revealGo.transform, "RevealBorder", Color.white);
        Stretch((RectTransform)border.transform);
        border.raycastTarget = false;
        Image face = MakeImage(revealGo.transform, "Face", new Color(0.09f, 0.09f, 0.12f, 1f));
        var faceRt = (RectTransform)face.transform;
        Stretch(faceRt);
        faceRt.offsetMin = new Vector2(10f, 10f);
        faceRt.offsetMax = new Vector2(-10f, -10f);
        face.raycastTarget = false;

        // §15.8.1's priority order, expressed as SIZE. 1 — the serial, "largest
        // element on the card", because it is the thing that is unique in the
        // world. It is deliberately more than twice the name.
        TMP_Text serial = MakeLabel(revealGo.transform, "RevealSerial", font, 148f,
                                    TextAlignmentOptions.Center, "", new Vector2(0.5f, 1f),
                                    new Vector2(0f, -150f), new Vector2(940f, 200f));
        serial.color = new Color(1f, 0.92f, 0.62f, 1f);
        serial.enableAutoSizing = false;

        TMP_Text firstOfItsKind = MakeLabel(revealGo.transform, "RevealFirstOfItsKind", font, 46f,
                                            TextAlignmentOptions.Center, "FIRST OF ITS KIND",
                                            new Vector2(0.5f, 1f), new Vector2(0f, -46f),
                                            new Vector2(940f, 70f));
        firstOfItsKind.color = new Color(1f, 0.78f, 0.2f, 1f);

        TMP_Text name = MakeLabel(revealGo.transform, "RevealName", font, 54f,
                                  TextAlignmentOptions.Center, "", new Vector2(0.5f, 0.5f),
                                  new Vector2(0f, -40f), new Vector2(940f, 80f));
        TMP_Text rarity = MakeLabel(revealGo.transform, "RevealRarity", font, 40f,
                                    TextAlignmentOptions.Center, "", new Vector2(0.5f, 0.5f),
                                    new Vector2(0f, -120f), new Vector2(940f, 60f));
        TMP_Text flt = MakeLabel(revealGo.transform, "RevealFloat", font, 32f,
                                 TextAlignmentOptions.Center, "", new Vector2(0.5f, 0f),
                                 new Vector2(0f, 150f), new Vector2(940f, 50f));
        TMP_Text variant = MakeLabel(revealGo.transform, "RevealVariant", font, 36f,
                                     TextAlignmentOptions.Center, "", new Vector2(0.5f, 0f),
                                     new Vector2(0f, 90f), new Vector2(940f, 56f));
        variant.color = new Color(0.6f, 0.95f, 1f, 1f);

        MakeLabel(revealGo.transform, "DismissHint", font, 22f, TextAlignmentOptions.Center,
                  "press anything to continue", new Vector2(0.5f, 0f), new Vector2(0f, 34f),
                  new Vector2(940f, 40f)).color = new Color(0.6f, 0.6f, 0.65f, 1f);

        // ---- the §15.8.1 bulk summary --------------------------------------
        // Built LAST inside the rig, so it draws over the reveal card: a bundle
        // ends with its final reveal still on screen underneath.
        var summaryGo = new GameObject("BulkSummary", typeof(RectTransform), typeof(CanvasGroup));
        summaryGo.transform.SetParent(root.transform, false);
        Stretch((RectTransform)summaryGo.transform);
        var summaryGroup = summaryGo.GetComponent<CanvasGroup>();
        summaryGroup.alpha = 0f;
        summaryGroup.blocksRaycasts = false;

        Image summaryScrim = MakeImage(summaryGo.transform, "SummaryScrim",
                                       new Color(0f, 0f, 0f, 0.88f));
        Stretch((RectTransform)summaryScrim.transform);

        Image summaryCard = MakeImage(summaryGo.transform, "SummaryCard",
                                      new Color(0.11f, 0.11f, 0.14f, 1f));
        Place((RectTransform)summaryCard.transform, new Vector2(0.5f, 0.5f), Vector2.zero,
              new Vector2(900f, 720f), new Vector2(0.5f, 0.5f));
        summaryCard.raycastTarget = false;

        // TOP-aligned and generous: eleven opens can list several serialized pulls
        // and a centred box would push the first line off the top of the card the
        // moment there are more than a handful.
        TMP_Text summaryText = MakeLabel(summaryCard.transform, "BulkSummaryText", font, 34f,
                                         TextAlignmentOptions.Top, "", new Vector2(0.5f, 1f),
                                         new Vector2(0f, -40f), new Vector2(840f, 620f));
        summaryText.color = new Color(0.95f, 0.95f, 0.98f, 1f);

        MakeLabel(summaryCard.transform, "SummaryDismissHint", font, 22f,
                  TextAlignmentOptions.Center, "press anything to continue",
                  new Vector2(0.5f, 0f), new Vector2(0f, 34f), new Vector2(840f, 40f))
            .color = new Color(0.6f, 0.6f, 0.65f, 1f);

        opening.bulkSummaryGroup = summaryGroup;
        opening.bulkSummaryText = summaryText;

        opening.reelRoot = reelRoot;
        opening.reelStage = reelStage;
        opening.cardTemplate = cardTemplate;
        opening.cardSpacing = 12f;
        opening.revealGroup = revealGroup;
        opening.revealBorder = border;
        opening.revealSerial = serial;
        opening.revealName = name;
        opening.revealRarity = rarity;
        opening.revealFloat = flt;
        opening.revealVariant = variant;
        opening.revealFirstOfItsKind = firstOfItsKind;
        opening.screenDim = dim;
        opening.rgbTakeover = rgbGroup;
        opening.shakeRoot = shakeRoot;
        // tickSource / stingSource stay null: there is no audio in this project yet
        // and SacredOpening is written to run silent rather than to throw.

        if (serial.fontSize <= name.fontSize)
        {
            Debug.LogError("[Store] revealSerial is not larger than revealName. §15.8.1 makes the " +
                           "serial the largest element on the card.");
        }
        log.Add("opening rig       reel pitch " + (k_CardWidth + opening.cardSpacing) +
                " px, " + SacredReel.CardCount + " cards pooled at runtime");
        return opening;
    }

    // --------------------------------------------------------------- read-back

    /// <summary>
    /// Batchmode entry point for the SECOND run: opens the built scene and reports
    /// what is actually in it. Separate from <see cref="Build"/> on purpose —
    /// reading back inside the same process reads the objects still held in
    /// memory, not the file that was written.
    /// </summary>
    public static void VerifyBatch()
    {
        if (!EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo()) return;
        EditorSceneManager.OpenScene(k_ScenePath, OpenSceneMode.Single);
        Debug.Log("[Store verify] " + k_ScenePath + "\n  " + string.Join("\n  ", Describe()));
    }

    [MenuItem("Tools/Aftermarket/Verify Store")]
    public static void Verify()
    {
        Debug.Log("[Store verify]\n  " + string.Join("\n  ", Describe()));
    }

    /// <summary>
    /// Every public field on both components, by REFLECTION.
    ///
    /// <para>Listing them by hand is the failure this is guarding against: a field
    /// nobody remembered to wire is exactly the field nobody remembers to check,
    /// and <see cref="SacredOpening"/> is silent about all of them.</para>
    /// </summary>
    static List<string> Describe()
    {
        var lines = new List<string>();

        var store = Object.FindFirstObjectByType<StoreScreen>(FindObjectsInactive.Include);
        var opening = Object.FindFirstObjectByType<SacredOpening>(FindObjectsInactive.Include);
        var es = Object.FindFirstObjectByType<UnityEngine.EventSystems.EventSystem>(FindObjectsInactive.Include);

        if (store == null) { lines.Add("StoreScreen       *** ABSENT ***"); return lines; }
        if (opening == null) lines.Add("SacredOpening     *** ABSENT ***");

        int wired = 0, missing = 0;
        lines.Add(Row("component.field", "state", "detail"));
        lines.Add(Row("--------------------------------", "-------", "----------------------------"));
        DescribeFields(store, "StoreScreen", lines, ref wired, ref missing);
        if (opening != null) DescribeFields(opening, "SacredOpening", lines, ref wired, ref missing);

        lines.Add("");
        lines.Add("wired " + wired + ", MISSING " + missing +
                  (missing == 0 ? "  (everything that should be set, is)" : "  *** FIX THESE ***"));

        lines.Add("sacreds.Length    " + store.sacreds.Length +
                  (store.sacreds.Length == 8 ? "  (expected 8)" : "  *** EXPECTED 8 ***"));
        lines.Add("designs.Length    " + store.designs.Length);
        lines.Add("sacred order      " + SacredIds(store));
        lines.Add("font              " +
                  (store.statusLabel != null && store.statusLabel.font != null
                   ? store.statusLabel.font.name
                   : "*** NULL — every label renders nothing ***"));
        lines.Add("EventSystem       " + (es == null ? "*** ABSENT ***"
                  : es.GetComponent<UnityEngine.InputSystem.UI.InputSystemUIInputModule>() != null
                    ? "InputSystemUIInputModule"
                    : "*** NOT InputSystemUIInputModule — will throw every frame ***"));

        if (opening != null)
        {
            float s = opening.revealSerial != null ? opening.revealSerial.fontSize : 0f;
            float n = opening.revealName != null ? opening.revealName.fontSize : 0f;
            lines.Add("serial vs name    " + s + " vs " + n +
                      (s > n ? "  (serial is largest — §15.8.1)" : "  *** SERIAL MUST BE LARGEST ***"));
            float w = opening.cardTemplate != null ? opening.cardTemplate.rect.width : 0f;
            lines.Add("card width        " + w + "  -> pitch " + (w + opening.cardSpacing) +
                      (w > 1f ? "" : "  *** ZERO WIDTH: the reel would never move ***"));
            lines.Add("reel clipped by   " + MaskOf(opening.reelRoot));
        }

        lines.Add("template active   sacredTemplate=" +
                  (store.sacredTemplate != null && store.sacredTemplate.gameObject.activeSelf) +
                  ", cardTemplate=" +
                  (opening != null && opening.cardTemplate != null && opening.cardTemplate.gameObject.activeSelf) +
                  "  (both must be False)");
        lines.Add("confirmPanel      " + (store.confirmPanel != null && store.confirmPanel.activeSelf
                                          ? "active *** should start disabled ***" : "disabled"));
        return lines;
    }

    static void DescribeFields(Object owner, string label, List<string> lines,
                               ref int wired, ref int missing)
    {
        foreach (FieldInfo f in owner.GetType().GetFields(BindingFlags.Public | BindingFlags.Instance))
        {
            string key = label + "." + f.Name;
            object v = f.GetValue(owner);

            if (typeof(Object).IsAssignableFrom(f.FieldType))
            {
                var o = v as Object;
                bool set = o != null;                       // Unity's null, not C#'s
                bool deliberate = k_DeliberatelyNull.Contains(key);
                if (set) wired++;
                else if (!deliberate) missing++;
                lines.Add(Row(key,
                              set ? "wired" : deliberate ? "empty" : "MISSING",
                              set ? o.name + " (" + o.GetType().Name + ")"
                                  : deliberate ? "intentionally null — no audio yet"
                                               : "*** NOT WIRED ***"));
            }
            else if (f.FieldType.IsArray)
            {
                var arr = v as System.Array;
                int n = arr == null ? -1 : arr.Length;
                int nulls = 0;
                for (int i = 0; i < n; i++)
                {
                    var e = arr.GetValue(i) as Object;
                    if (e == null) nulls++;
                }
                if (n > 0) wired++; else missing++;
                lines.Add(Row(key, n > 0 ? "wired" : "MISSING",
                              n + " element(s)" + (nulls > 0 ? ", " + nulls + " NULL" : "")));
            }
            else
            {
                lines.Add(Row(key, "value", v == null ? "null" : v.ToString()));
            }
        }
    }

    static string SacredIds(StoreScreen store)
    {
        var ids = new List<string>();
        foreach (SacredDefinition s in store.sacreds)
        {
            ids.Add(s == null ? "<NULL>" : s.sacredId + "(" + StoreScreen.PriceText(s) + ")");
        }
        return string.Join(", ", ids.ToArray());
    }

    static string MaskOf(RectTransform reelRoot)
    {
        if (reelRoot == null) return "*** reelRoot is null ***";
        Transform t = reelRoot.parent;
        while (t != null)
        {
            if (t.GetComponent<RectMask2D>() != null) return "RectMask2D on " + t.name;
            if (t.GetComponent<Mask>() != null) return "Mask on " + t.name;
            t = t.parent;
        }
        return "*** NOTHING — cards will not clip at the viewport edge ***";
    }

    static string Row(string a, string b, string c)
    {
        return a.PadRight(37) + b.PadRight(9) + c;
    }

    // ------------------------------------------------------------------- bits

    /// <summary>
    /// A TMP font, or null. Null is a build ERROR, not a warning: a TMP_Text with
    /// no font renders NOTHING while looking perfectly healthy in the hierarchy,
    /// which is how this project shipped world-space popups nobody could see.
    ///
    /// <para>The project's own default is preferred, and a font asset named
    /// "…Fallback" is taken only as a last resort. The first FindAssets hit here is
    /// <c>LiberationSans SDF - Fallback</c>, a dynamic asset whose atlas is
    /// whatever some other scene happened to make TMP rasterise — using it as a
    /// PRIMARY font makes the store's glyph coverage depend on unrelated work.</para>
    /// </summary>
    static TMP_FontAsset FindAnyFont()
    {
        TMP_Settings settings = TMP_Settings.instance;
        if (settings != null && TMP_Settings.defaultFontAsset != null)
            return TMP_Settings.defaultFontAsset;

        TMP_FontAsset fallback = null;
        foreach (string guid in AssetDatabase.FindAssets("t:TMP_FontAsset"))
        {
            var a = AssetDatabase.LoadAssetAtPath<TMP_FontAsset>(AssetDatabase.GUIDToAssetPath(guid));
            if (a == null) continue;
            if (a.name.IndexOf("Fallback", System.StringComparison.OrdinalIgnoreCase) >= 0)
            {
                if (fallback == null) fallback = a;
                continue;
            }
            return a;
        }
        return fallback;
    }

    static void Stretch(RectTransform rt)
    {
        rt.anchorMin = Vector2.zero;
        rt.anchorMax = Vector2.one;
        rt.pivot = new Vector2(0.5f, 0.5f);
        rt.offsetMin = Vector2.zero;
        rt.offsetMax = Vector2.zero;
    }

    static void Place(RectTransform rt, Vector2 anchor, Vector2 pos, Vector2 size, Vector2 pivot)
    {
        rt.anchorMin = rt.anchorMax = anchor;
        rt.pivot = pivot;
        rt.sizeDelta = size;
        rt.anchoredPosition = pos;
    }

    static Image MakeImage(Transform parent, string name, Color color)
    {
        var go = new GameObject(name, typeof(RectTransform), typeof(Image));
        go.transform.SetParent(parent, false);
        Image img = go.GetComponent<Image>();
        img.color = color;
        return img;
    }

    static void MakeTakeoverBand(Transform parent, string name, Color color, float top)
    {
        Image i = MakeImage(parent, name, color);
        var rt = (RectTransform)i.transform;
        rt.anchorMin = new Vector2(0f, top - 0.16f);
        rt.anchorMax = new Vector2(1f, top);
        rt.offsetMin = rt.offsetMax = Vector2.zero;
        i.raycastTarget = false;
    }

    static TMP_Text MakeLabel(Transform parent, string name, TMP_FontAsset font, float size,
                              TextAlignmentOptions align, string text,
                              Vector2 anchor, Vector2 pos, Vector2 boxSize)
    {
        var go = new GameObject(name, typeof(RectTransform));
        go.transform.SetParent(parent, false);
        var t = go.AddComponent<TextMeshProUGUI>();
        t.font = font;
        t.fontSize = size;
        t.alignment = align;
        t.text = text;
        t.raycastTarget = false;
        Vector2 pivot = new Vector2(anchor.x, anchor.y);
        Place((RectTransform)go.transform, anchor, pos, boxSize, pivot);
        return t;
    }

    /// <summary>A store tile: Image + one child TMP_Text, which is exactly the
    /// shape StoreScreen's BuildGrid clones and fills.</summary>
    static Button MakeTile(Transform parent, string name, TMP_FontAsset font)
    {
        var go = new GameObject(name, typeof(RectTransform), typeof(Image), typeof(Button));
        go.transform.SetParent(parent, false);
        ((RectTransform)go.transform).sizeDelta = new Vector2(240f, 300f);
        go.GetComponent<Image>().color = new Color(0.20f, 0.20f, 0.26f, 1f);

        var labelGo = new GameObject("Label", typeof(RectTransform));
        labelGo.transform.SetParent(go.transform, false);
        var t = labelGo.AddComponent<TextMeshProUGUI>();
        t.font = font;
        t.fontSize = 28f;
        t.alignment = TextAlignmentOptions.Center;
        t.color = Color.white;
        t.text = "Sacred";
        t.raycastTarget = false;
        var rt = (RectTransform)labelGo.transform;
        rt.anchorMin = Vector2.zero;
        rt.anchorMax = Vector2.one;
        rt.offsetMin = new Vector2(8f, 8f);
        rt.offsetMax = new Vector2(-8f, -8f);
        return go.GetComponent<Button>();
    }

    /// <summary>A reel card: Image + one child TMP_Text — the shape
    /// <see cref="SacredOpening"/>'s pool reads with GetComponent&lt;Image&gt; and
    /// GetComponentInChildren&lt;TMP_Text&gt;.</summary>
    static RectTransform MakeCard(Transform parent, string name, TMP_FontAsset font)
    {
        var go = new GameObject(name, typeof(RectTransform), typeof(Image));
        go.transform.SetParent(parent, false);
        var rt = (RectTransform)go.transform;
        // Anchored at the reel's centre so anchoredPosition IS the offset in
        // pitches, and sized in sizeDelta so rect.width is the pitch source.
        Place(rt, new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(k_CardWidth, k_CardHeight),
              new Vector2(0.5f, 0.5f));
        go.GetComponent<Image>().color = Color.grey;

        var labelGo = new GameObject("Label", typeof(RectTransform));
        labelGo.transform.SetParent(go.transform, false);
        var t = labelGo.AddComponent<TextMeshProUGUI>();
        t.font = font;
        t.fontSize = 20f;
        t.alignment = TextAlignmentOptions.Center;
        t.color = Color.black;
        t.text = "";
        t.raycastTarget = false;
        var lrt = (RectTransform)labelGo.transform;
        lrt.anchorMin = Vector2.zero;
        lrt.anchorMax = Vector2.one;
        lrt.offsetMin = new Vector2(6f, 6f);
        lrt.offsetMax = new Vector2(-6f, -6f);
        return rt;
    }

    static Button MakeButton(Transform parent, string name, TMP_FontAsset font, string text,
                             Vector2 anchor, Vector2 pos, Vector2 size)
    {
        var go = new GameObject(name, typeof(RectTransform), typeof(Image), typeof(Button));
        go.transform.SetParent(parent, false);
        Place((RectTransform)go.transform, anchor, pos, size, new Vector2(0.5f, 0.5f));
        go.GetComponent<Image>().color = new Color(0.85f, 0.85f, 0.88f, 1f);

        var labelGo = new GameObject("Label", typeof(RectTransform));
        labelGo.transform.SetParent(go.transform, false);
        var t = labelGo.AddComponent<TextMeshProUGUI>();
        t.font = font;
        t.fontSize = 26f;
        t.alignment = TextAlignmentOptions.Center;
        t.color = Color.black;
        t.text = text;
        t.raycastTarget = false;
        var rt = (RectTransform)labelGo.transform;
        rt.anchorMin = Vector2.zero;
        rt.anchorMax = Vector2.one;
        rt.offsetMin = rt.offsetMax = Vector2.zero;
        return go.GetComponent<Button>();
    }
}
