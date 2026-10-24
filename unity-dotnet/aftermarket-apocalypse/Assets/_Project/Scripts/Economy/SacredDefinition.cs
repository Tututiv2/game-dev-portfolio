using UnityEngine;

/// <summary>Spec §15.1: the currency a Sacred is bought with. Names are final.</summary>
public enum CurrencyKind { Embers, SoulCrystals, Shards, Free }

/// <summary>
/// ★ Spec §15.8: "The cases are called SACREDS. Not crates, not cases, not
/// boxes." — "You opened an Ashfall Sacred."
///
/// The ladder from §15.8: Recruit (free) - Ash 500 ◆ - Ember 2,000 ◆ -
/// Phantom 12 ✦ - Starfade 40 ✦ - Founder's (earned) - Midnight 25 ✦ -
/// Rainbow (pity).
/// </summary>
[CreateAssetMenu(menuName = "Aftermarket/Sacred", fileName = "Sacred_")]
public class SacredDefinition : ScriptableObject
{
    public string sacredId = "ash_sacred";
    public string displayName = "Ash Sacred";

    [Header("Price")]
    public CurrencyKind currency = CurrencyKind.Embers;
    public int price = 500;

    [Header("Class")]
    [Tooltip("Premium Sacreds skew the float curve cleaner (spec §15.6).")]
    public bool isPremium = false;

    [Tooltip("Spec §15.6: premium 0.5 skews clean, F2P 1.0 is flat.")]
    public float floatCurveExponent = 1.0f;

    [Header("Contents")]
    [Tooltip("Designs this Sacred can yield. ★ Spec §15.3: all serialized designs " +
             "share the same pull chance — flat, NOT rarity-weighted. Selection is " +
             "uniform across whatever designs still have edition capacity.")]
    public SkinDesign[] designs = new SkinDesign[0];

    void OnValidate()
    {
        floatCurveExponent = Mathf.Max(0.01f, floatCurveExponent);
        if (isPremium && Mathf.Approximately(floatCurveExponent, 1f)) floatCurveExponent = 0.5f;
        if (string.IsNullOrWhiteSpace(sacredId)) sacredId = name;
    }
}
