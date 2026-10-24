using System.Collections;
using UnityEngine;
using TMPro;

public enum PowerUpType { MaxAmmo, InstaKill, DoublePoints, Nuke }

/// <summary>
/// Power-up drops (spec §7) AND the game's general announcement banner — quests,
/// extraction, currency drops and the power switch all route through
/// <see cref="Announce"/>. Self-building: it makes its own 44 pt centre-top text
/// and builds the orbs out of primitives.
///
/// Spec §13.6 fix (defect #6): Insta-Kill and Double Points did not stack.
/// Picking up a second one started a SECOND coroutine whose expiry cleared the
/// flag early, so the second pickup actively shortened the buff. The state is
/// now an expiry TIMESTAMP that pickups extend, and "active" is a comparison
/// against Time.time. There is no flag for a stale coroutine to clear.
/// </summary>
public class PowerUpManager : MonoBehaviour
{
    public static PowerUpManager instance;

    [Header("Drops (spec §7)")]
    [Range(0f, 1f)] public float dropChance = 0.04f;      // 4% of kills
    public float orbLifetime = 20f;
    public float buffDuration = 30f;                      // Insta-Kill / Double Points

    [Header("UI")]
    public TextMeshProUGUI announceText;

    // Expiry timestamps, not booleans (the stacking fix).
    private float instaKillUntil = -1f;
    private float doublePointsUntil = -1f;

    public static bool InstaKillActive
    {
        get { return instance != null && Time.time < instance.instaKillUntil; }
    }

    public static bool DoublePointsActive
    {
        get { return instance != null && Time.time < instance.doublePointsUntil; }
    }

    /// <summary>Seconds left, for a HUD timer. 0 when inactive.</summary>
    public static float InstaKillRemaining
    {
        get { return instance == null ? 0f : Mathf.Max(0f, instance.instaKillUntil - Time.time); }
    }

    public static float DoublePointsRemaining
    {
        get { return instance == null ? 0f : Mathf.Max(0f, instance.doublePointsUntil - Time.time); }
    }

    void Awake()
    {
        if (instance != null && instance != this) { Destroy(this); return; }
        instance = this;
    }

    void OnDestroy()
    {
        if (instance == this) instance = null;
    }

    /// <summary>Called from EnemyAI.Die(). 4% chance, then a uniform pick of the four.</summary>
    public void TryDrop(Vector3 position)
    {
        if (Random.value > dropChance) return;
        PowerUpType type = (PowerUpType)Random.Range(0, 4);
        SpawnOrb(position + Vector3.up * 0.6f, type);
    }

    public void SpawnOrb(Vector3 position, PowerUpType type)
    {
        GameObject orb = GameObject.CreatePrimitive(PrimitiveType.Sphere);
        orb.name = "PowerUp_" + type;
        orb.transform.position = position;
        orb.transform.localScale = Vector3.one * 0.5f;

        SphereCollider col = orb.GetComponent<SphereCollider>();
        col.isTrigger = true;

        Rigidbody rb = orb.AddComponent<Rigidbody>();
        rb.isKinematic = true;
        rb.useGravity = false;

        Color color = ColorFor(type);
        Renderer r = orb.GetComponent<Renderer>();
        Material mat = new Material(Shader.Find("Universal Render Pipeline/Lit"));
        mat.color = color;
        mat.EnableKeyword("_EMISSION");
        mat.SetColor("_EmissionColor", color * 2f);
        r.material = mat;

        Light light = orb.AddComponent<Light>();
        light.type = LightType.Point;
        light.color = color;
        light.range = 6f;
        light.intensity = 3f;

        PowerUpPickup pickup = orb.AddComponent<PowerUpPickup>();
        pickup.type = type;
        pickup.lifetime = orbLifetime;
    }

    public static Color ColorFor(PowerUpType type)
    {
        switch (type)
        {
            case PowerUpType.MaxAmmo:      return new Color(0.2f, 0.6f, 1f);
            case PowerUpType.InstaKill:    return Color.red;
            case PowerUpType.DoublePoints: return new Color(1f, 0.85f, 0.2f);
            default:                       return new Color(0.4f, 1f, 0.3f);   // Nuke
        }
    }

    public void Collect(PowerUpType type)
    {
        switch (type)
        {
            case PowerUpType.MaxAmmo:
                AmmoService.RefillAllWeapons();
                Announce("MAX AMMO");
                break;

            case PowerUpType.InstaKill:
                instaKillUntil = ExtendBuff(Time.time, instaKillUntil, buffDuration);
                Announce("INSTA-KILL");
                break;

            case PowerUpType.DoublePoints:
                doublePointsUntil = ExtendBuff(Time.time, doublePointsUntil, buffDuration);
                Announce("DOUBLE POINTS");
                break;

            case PowerUpType.Nuke:
                Nuke();
                break;
        }
    }

    /// <summary>
    /// THE STACKING FIX (spec §13.6 defect #6), isolated so it can be tested.
    /// Extend from whichever is later — now, or the existing expiry — so a second
    /// pickup can only ever ADD time. The old coroutine-per-pickup design meant
    /// the FIRST coroutine's expiry cleared the flag while the second buff was
    /// still meant to be running, i.e. picking up a second Insta-Kill made your
    /// Insta-Kill shorter.
    /// </summary>
    public static float ExtendBuff(float now, float currentExpiry, float duration)
    {
        return Mathf.Max(now, currentExpiry) + duration;
    }

    void Nuke()
    {
        // TakeDamage(health + 1, 0, ...) — 0 points, so no "+0" popups carpet the
        // map (spec §5.3). The flat 400 is awarded once, here.
        Target[] all = FindObjectsByType<Target>(FindObjectsSortMode.None);
        foreach (Target t in all)
        {
            if (t == null || t.health <= 0f) continue;
            t.TakeDamage(t.health + 1f, 0, false, t.transform.position);
        }
        ScoreManager.Award(DamageMath.PointsNuke);
        Announce("NUKE");
    }

    /// <summary>The game's general banner. Self-builds a 44 pt centre-top label
    /// if nothing is wired (spec §7).</summary>
    public void Announce(string message)
    {
        if (announceText == null) BuildAnnounceText();
        if (announceText == null) { Debug.Log("[Announce] " + message); return; }
        StopCoroutine("AnnounceRoutine");
        StartCoroutine(AnnounceRoutine(message));
    }

    IEnumerator AnnounceRoutine(string message)
    {
        announceText.text = message;
        Color c = announceText.color;
        announceText.color = new Color(c.r, c.g, c.b, 1f);
        yield return new WaitForSeconds(2.2f);
        float t = 0f;
        while (t < 0.8f)
        {
            t += Time.deltaTime;
            announceText.color = new Color(c.r, c.g, c.b, 1f - t / 0.8f);
            yield return null;
        }
        announceText.text = "";
    }

    void BuildAnnounceText()
    {
        GameObject canvasGO = new GameObject("AnnounceCanvas");
        canvasGO.transform.SetParent(transform);
        Canvas canvas = canvasGO.AddComponent<Canvas>();
        canvas.renderMode = RenderMode.ScreenSpaceOverlay;
        canvas.sortingOrder = 400;
        canvasGO.AddComponent<UnityEngine.UI.CanvasScaler>();

        GameObject textGO = new GameObject("AnnounceText");
        textGO.transform.SetParent(canvasGO.transform, false);
        announceText = textGO.AddComponent<TextMeshProUGUI>();
        announceText.fontSize = 44f;
        announceText.alignment = TextAlignmentOptions.Center;
        announceText.color = new Color(1f, 0.92f, 0.6f, 1f);
        announceText.raycastTarget = false;

        RectTransform rt = announceText.rectTransform;
        rt.anchorMin = new Vector2(0.5f, 1f);
        rt.anchorMax = new Vector2(0.5f, 1f);
        rt.pivot = new Vector2(0.5f, 1f);
        rt.anchoredPosition = new Vector2(0f, -110f);
        rt.sizeDelta = new Vector2(1200f, 80f);
    }
}
