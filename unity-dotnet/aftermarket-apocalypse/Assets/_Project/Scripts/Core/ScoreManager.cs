using UnityEngine;
using TMPro;

/// <summary>
/// ESSENCE — the in-match currency (spec §15.1, §21). Resets every match.
/// Buys doors, perks, the box, Pack-a-Punch and ammo. Nothing persistent is
/// ever priced in Essence; that inversion is spec §0.3's single largest error
/// and is corrected here by simply not exposing a spend path for it.
///
/// Also owns the round number, as in the old build (spec §2.1).
///
/// Spec §13.9: six machine scripts dereferenced ScoreManager.instance unguarded.
/// The static TrySpend/Award helpers below are the fix — callers never touch
/// .instance, so a missing manager degrades instead of throwing.
/// </summary>
public class ScoreManager : MonoBehaviour
{
    public static ScoreManager instance;

    [Header("State")]
    /// <summary>
    /// In-match currency. Spec calls this Essence — never "points", "credits" or
    /// "cash".
    ///
    /// <para><b>Deliberately NOT serialized.</b> It used to be a public inspector
    /// field defaulting to 0, seeded at build time by the old PlaytestRig editor
    /// tool, which meant the starting balance was a literal baked into
    /// AshfallDepot.unity that no test could see and no reader could find. A
    /// serialized value always beats a script default — that is the Part I
    /// "READ FIRST" bug this project keeps designing out. The one copy now lives
    /// in <see cref="RoundRules.StartingEssence"/> and is read in Awake.</para>
    /// </summary>
    [System.NonSerialized] public int essence;

    public int roundNumber = 1;

    [Header("UI")]
    public TextMeshProUGUI essenceText;

    void Awake()
    {
        // Last loaded wins, but do not silently stomp a live manager.
        if (instance != null && instance != this)
        {
            Debug.LogWarning("ScoreManager: a second instance appeared on '" + name + "'. Destroying the duplicate.");
            Destroy(this);
            return;
        }
        instance = this;

        // The one place a match's opening balance is decided. See the field's note
        // above for why this is not an inspector value.
        essence = RoundRules.StartingEssence;
        RefreshUI();
    }

    void OnDestroy()
    {
        if (instance == this) instance = null;
    }

    // ---------- static, null-safe API (spec §13.9) ----------

    public static int Essence
    {
        get { return instance != null ? instance.essence : 0; }
    }

    public static int Round
    {
        get { return instance != null ? instance.roundNumber : 1; }
    }

    /// <summary>Award Essence. Double Points multiplies AT CREDIT TIME (spec §7),
    /// so Essence earned inside the window is permanently banked.</summary>
    public static void Award(int amount)
    {
        if (instance == null || amount == 0) return;
        if (amount > 0 && PowerUpManager.DoublePointsActive) amount *= 2;
        instance.essence += amount;
        instance.RefreshUI();
    }

    /// <summary>Attempt a purchase. Returns false and spends nothing if unaffordable.</summary>
    public static bool TrySpend(int cost)
    {
        if (instance == null) return false;
        if (cost < 0) return false;
        if (instance.essence < cost) return false;
        instance.essence -= cost;
        instance.RefreshUI();
        return true;
    }

    public static bool CanAfford(int cost)
    {
        return instance != null && instance.essence >= cost;
    }

    public void RefreshUI()
    {
        if (essenceText != null) essenceText.text = essence.ToString();
    }
}
