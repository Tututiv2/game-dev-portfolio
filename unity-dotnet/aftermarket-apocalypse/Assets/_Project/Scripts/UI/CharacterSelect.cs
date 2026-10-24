using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using TMPro;
using UnityEngine;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

/// <summary>
/// The minimal character-select / preview screen from spec §15.12.
///
/// <para><b>Why this exists at all:</b> the game is first-person, so the player
/// never sees their own character. Buying and theming a model you cannot look at
/// is not a feature. §15.12.3 calls the live 3D preview "the whole sell".</para>
///
/// <para>This is the PREVIEW half of §15.12, not the store. There are no prices,
/// no purchase flow and no Sacreds here — those need the economy wired up.
/// Selecting a character and a theme, seeing it, and having the choice persist is
/// what makes the art visible, which is what was asked for.</para>
///
/// <para><b>Locked themes are rendered, never trusted (§15.12.2).</b> The lock
/// state comes from <c>IBackend.HasEntitlement</c>, not from
/// <c>ThemeDefinition.adminOnly</c>. The flag decides how it LOOKS; the backend
/// decides whether it can be equipped. On <c>LocalBackend</c> that is a local
/// list, but it goes through the interface now so moving to a real server is not
/// a rewrite.</para>
/// </summary>
public class CharacterSelect : MonoBehaviour
{
    [Header("Content")]
    [Tooltip("The roster. Adding a character is authoring a CharacterDefinition " +
             "and dropping it in here — never a code change (§18.1).")]
    public CharacterCatalog characterCatalog;
    public ThemeCatalog themeCatalog;

    [Header("Scene wiring")]
    [Tooltip("Empty transform the selected character is instantiated under.")]
    public Transform previewAnchor;
    public float turntableDegreesPerSecond = 22f;

    [Header("UI")]
    public TMP_Text characterNameLabel;
    public TMP_Text themeNameLabel;
    public RectTransform themeRow;
    public Button swatchTemplate;
    public Button nextCharacterButton;
    public Button prevCharacterButton;

    const string k_CharacterKey = "SelectedCharacter";
    const string k_ThemeKeyPrefix = "SelectedTheme_";

    /// <summary>Roster size, tolerating a missing catalog.</summary>
    public int Count => characterCatalog == null || characterCatalog.characters == null
        ? 0 : characterCatalog.characters.Length;

    public string CurrentId
    {
        get
        {
            if (Count == 0) return "";
            var c = characterCatalog.characters[Mathf.Clamp(index, 0, Count - 1)];
            return c == null ? "" : c.characterId;
        }
    }

    int index;
    GameObject activeBody;
    readonly List<Button> swatches = new List<Button>();
    readonly List<ThemeDefinition> swatchThemes = new List<ThemeDefinition>();
    readonly Dictionary<string, Task<bool>> entitlement = new Dictionary<string, Task<bool>>();
    readonly HashSet<string> lockedApplied = new HashSet<string>();
    ThemeDefinition current;

    void Start()
    {
        if (themeCatalog != null) ThemeSystem.Catalog = themeCatalog;
        SeedLocalEntitlements();

        // The template lives in the scene so it can be authored; it must never be
        // one of the live buttons.
        if (swatchTemplate != null) swatchTemplate.gameObject.SetActive(false);

        if (nextCharacterButton != null) nextCharacterButton.onClick.AddListener(Next);
        if (prevCharacterButton != null) prevCharacterButton.onClick.AddListener(Prev);

        // Restore by id and fall back to the first entry, so a character that was
        // removed from the roster degrades to "show someone" rather than to an
        // out-of-range index.
        index = Mathf.Max(0, characterCatalog == null
            ? 0 : characterCatalog.IndexOf(PlayerPrefs.GetString(k_CharacterKey, "")));
        BuildSwatches();
        ShowCharacter(index);
    }

    void Update()
    {
        if (previewAnchor != null)
            previewAnchor.Rotate(Vector3.up, turntableDegreesPerSecond * Time.deltaTime, Space.World);

        // Entitlement answers arrive off the main thread's timeline. Poll rather
        // than `async void`, so a faulted task cannot vanish silently.
        for (int i = 0; i < swatchThemes.Count; i++)
        {
            ThemeDefinition t = swatchThemes[i];
            if (t == null || lockedApplied.Contains(t.themeId)) continue;
            Task<bool> task;
            if (!entitlement.TryGetValue(t.themeId, out task) || !task.IsCompleted) continue;

            bool owned = task.Status == TaskStatus.RanToCompletion && task.Result;
            lockedApplied.Add(t.themeId);
            PaintSwatch(swatches[i], t, owned);

            if (task.IsFaulted)
            {
                Debug.LogWarning("[CharacterSelect] entitlement check for '" + t.themeId +
                                 "' failed; treating as LOCKED. " +
                                 (task.Exception == null ? "" : task.Exception.Message));
            }
        }
    }

    /// <summary>
    /// Give the stand-in server the free themes to grant.
    ///
    /// <para><b>This is the SERVER seeding its own data, not the client deciding
    /// it owns something.</b> The distinction is the whole of §15.12.2 and it is
    /// enforced by the type test: this runs only when <c>Backend.Current</c> is
    /// literally a <see cref="LocalBackend"/>. Point the composition root at a
    /// real backend and this block does nothing at all, the entitlement list
    /// comes down the wire, and no code path here changes.</para>
    ///
    /// <para>Without it every swatch reads locked on a fresh install — including
    /// the ones §15.12.1 says are free — which looks like a broken screen rather
    /// than an ungranted entitlement.</para>
    /// </summary>
    void SeedLocalEntitlements()
    {
        LocalBackend local = Backend.Current as LocalBackend;
        if (local == null || themeCatalog == null) return;

        foreach (ThemeDefinition t in themeCatalog.themes)
        {
            if (t == null || t.adminOnly || string.IsNullOrEmpty(t.themeId)) continue;
            local.GrantEntitlement(t.themeId);
        }
    }

    // ------------------------------------------------------------- characters

    public void Next() { Step(1); }
    public void Prev() { Step(-1); }

    /// <summary>
    /// Confirm and go back to the menu.
    ///
    /// <para>Stepping already persists the choice — <see cref="ShowCharacter"/>
    /// writes it the moment you look at someone — so this button changes no
    /// state. It exists because a screen that saves silently gives you no way to
    /// know you picked anything: the owner's words were "I can see the
    /// characters but can't select one". An affordance IS the feature here.</para>
    /// </summary>
    public void Confirm()
    {
        PlayerPrefs.Save();
        Back();
    }

    public void Back()
    {
        const string menu = "MainMenu";
        if (!Application.CanStreamedLevelBeLoaded(menu))
        {
            Debug.LogError("[CharacterSelect] '" + menu + "' is not in Build Settings, so there " +
                           "is no way back. Run Tools > Aftermarket > Build Main Menu.");
            return;
        }
        Time.timeScale = 1f;
        SceneManager.LoadScene(menu, LoadSceneMode.Single);
    }

    void Step(int d)
    {
        int n = Count;
        if (n == 0) return;
        index = (index + d + n) % n;
        ShowCharacter(index);
    }

    void ShowCharacter(int i)
    {
        if (activeBody != null) Destroy(activeBody);
        activeBody = null;

        if (Count == 0 || previewAnchor == null) return;
        CharacterDefinition e = characterCatalog.characters[Mathf.Clamp(i, 0, Count - 1)];
        if (e == null) return;

        // Saved by IDENTITY, not by position. Both this and the per-character
        // theme below used to persist the roster INDEX, so inserting a character
        // handed every existing player a different one and moved their themes
        // along with it -- silently, and permanently once it was written back.
        // Same shape as the scene build-index warning in NavBarBuilder.
        PlayerPrefs.SetString(k_CharacterKey, e.characterId);
        if (e.prefab == null)
        {
            Debug.LogError("[CharacterSelect] character '" + e.characterId +
                           "' has no prefab, so there is nothing to preview.");
            return;
        }

        activeBody = Instantiate(e.prefab, previewAnchor);
        activeBody.transform.localPosition = Vector3.zero;
        activeBody.transform.localRotation = Quaternion.identity;

        if (characterNameLabel != null) characterNameLabel.text = e.displayName;

        // §15.12.4: the theme is remembered PER CHARACTER.
        string saved = PlayerPrefs.GetString(k_ThemeKeyPrefix + e.characterId, "default");
        ApplyTheme(ThemeSystem.Resolve(saved), false);
    }

    // ---------------------------------------------------------------- themes

    void BuildSwatches()
    {
        foreach (Button b in swatches)
        {
            if (b != null) Destroy(b.gameObject);
        }
        swatches.Clear();
        swatchThemes.Clear();
        lockedApplied.Clear();

        if (themeCatalog == null || swatchTemplate == null || themeRow == null) return;

        string userId = "local";
        foreach (ThemeDefinition t in themeCatalog.themes)
        {
            if (t == null) continue;
            Button b = Instantiate(swatchTemplate, themeRow);
            b.gameObject.SetActive(true);
            b.name = "Swatch_" + t.themeId;

            ThemeDefinition captured = t;
            b.onClick.AddListener(delegate { OnSwatch(captured); });

            swatches.Add(b);
            swatchThemes.Add(t);

            // Paint an interim state immediately so the row is never empty while
            // the backend answers; the poll in Update replaces it.
            PaintSwatch(b, t, !t.adminOnly);

            if (!entitlement.ContainsKey(t.themeId))
                entitlement[t.themeId] = Backend.Current.HasEntitlement(userId, t.themeId);
        }
    }

    /// <summary>
    /// Locked items stay VISIBLE and browsable — §15.12.2 is explicit that hiding
    /// them is worse, because players learn what exists and want it.
    /// </summary>
    void PaintSwatch(Button b, ThemeDefinition t, bool owned)
    {
        if (b == null) return;
        Image img = b.GetComponent<Image>();
        if (img != null)
        {
            Color c = SwatchColor(t);
            img.color = owned ? c : new Color(c.r * 0.35f, c.g * 0.35f, c.b * 0.35f, 0.7f);
            if (t.swatch != null) img.sprite = t.swatch;
        }
        TMP_Text label = b.GetComponentInChildren<TMP_Text>();
        if (label != null) label.text = owned ? t.displayName : t.displayName + "  ●";

        b.interactable = owned;
    }

    /// <summary>
    /// A readable stand-in colour when a theme has no authored swatch sprite:
    /// its own hue shift, at full saturation. Derived from the theme rather than
    /// typed, so a new theme cannot get someone else's colour.
    /// </summary>
    public static Color SwatchColor(ThemeDefinition t)
    {
        if (t == null) return Color.white;
        RegionTint r = t.Primary;
        float h = Mathf.Repeat(r.hueShift, 1f);
        float s = Mathf.Clamp01(r.saturationMul > 1f ? 1f : r.saturationMul);
        float v = Mathf.Clamp01(r.valueMul);
        if (t.IsIdentity) return new Color(0.8f, 0.8f, 0.8f, 1f);
        return Color.HSVToRGB(h, Mathf.Max(s, 0.35f), Mathf.Max(v, 0.4f));
    }

    void OnSwatch(ThemeDefinition t)
    {
        ApplyTheme(t, true);
    }

    void ApplyTheme(ThemeDefinition t, bool persist)
    {
        current = t;
        if (activeBody != null) ThemeSystem.Apply(activeBody, t);
        if (themeNameLabel != null)
            themeNameLabel.text = t == null ? "Default" : t.displayName;
        if (persist)
            PlayerPrefs.SetString(k_ThemeKeyPrefix + CurrentId, t == null ? "default" : t.themeId);
    }

    /// <summary>What the match should spawn the player wearing.</summary>
    public ThemeDefinition CurrentTheme { get { return current; } }
}
