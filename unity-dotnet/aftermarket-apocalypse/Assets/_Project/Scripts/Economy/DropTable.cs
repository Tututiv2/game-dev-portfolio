using UnityEngine;

/// <summary>
/// THE PROMOTION TREE (spec §15.3) and RGB PITY (§15.4), as pure functions with
/// an injected random source so the odds are deterministic under test.
///
/// Rolled on every Sacred open, in order:
///
///     5%  of opens yield a SERIALIZED item
///          |- 5% of those promote to SCT
///                 |- 5% of those promote to RGB
///
/// giving 4.75% plain serialized (~1 in 21), 0.2375% SCT (~1 in 421) and
/// 0.0125% RGB (~1 in 8,000).
///
/// RGB cannot appear on a non-SCT item. The chain is strictly sequential — that
/// is a design rule, not an implementation detail, so it is expressed as nested
/// gates rather than three independent rolls.
/// </summary>
public static class DropTable
{
    /// <summary>Each step of the chain. Spec §15.3 is explicit that all three are 5%.</summary>
    public const float SerializedChance = 0.05f;
    public const float SctPromotionChance = 0.05f;
    public const float RgbPromotionChance = 0.05f;

    /// <summary>
    /// ★ Spec §15.4. "The 101st open after the last RGB is a guaranteed RGB."
    ///
    /// RECORDED CONSEQUENCE, DELIBERATELY ACCEPTED BY THE OWNER (§15.4): this
    /// produces RGB at roughly 1% of all opens versus 0.0125% from the tree —
    /// about 79x the natural rate — so nearly all RGB in the game will come from
    /// pity rather than luck, and RGB ends up only ~4.8x rarer than a plain
    /// serialized pull. The spec's instruction is to implement it as this named
    /// constant so it is a one-line tune, and to NOT silently "correct" it.
    /// </summary>
    public const int RgbPityThreshold = 101;

    /// <summary>Spec §15.4: buy 10 Sacreds, get the 11th free.</summary>
    public const int BulkBuyCount = 10;
    public const int BulkFreeCount = 1;

    public enum Outcome { NonSerialized, Serialized, SCT, RGB }

    /// <summary>
    /// Decide one Sacred open.
    /// </summary>
    /// <param name="roll1">uniform 0..1 — serialized gate</param>
    /// <param name="roll2">uniform 0..1 — SCT promotion</param>
    /// <param name="roll3">uniform 0..1 — RGB promotion</param>
    /// <param name="opensSinceLastRgb">count of opens since the last RGB was issued</param>
    public static Outcome Resolve(float roll1, float roll2, float roll3, int opensSinceLastRgb)
    {
        // Pity is checked FIRST and short-circuits the tree entirely: the 101st
        // open is guaranteed, not merely more likely.
        if (opensSinceLastRgb + 1 >= RgbPityThreshold) return Outcome.RGB;

        if (roll1 >= SerializedChance) return Outcome.NonSerialized;
        if (roll2 >= SctPromotionChance) return Outcome.Serialized;
        if (roll3 >= RgbPromotionChance) return Outcome.SCT;
        return Outcome.RGB;
    }

    public static Rarity RarityFor(Outcome o)
    {
        switch (o)
        {
            case Outcome.RGB: return Rarity.SecretRGB;
            case Outcome.SCT: return Rarity.Secret;
            case Outcome.Serialized: return Rarity.Limited;
            default: return Rarity.Common;
        }
    }

    public static SkinVariant VariantFor(Outcome o)
    {
        switch (o)
        {
            case Outcome.RGB: return SkinVariant.RGB;
            case Outcome.SCT: return SkinVariant.SCT;
            default: return SkinVariant.None;
        }
    }

    public static bool IsSerializedOutcome(Outcome o)
    {
        return o != Outcome.NonSerialized;
    }

    /// <summary>Spec §15.7: roughly 5% of items are Trackers.</summary>
    public const float TrackerChance = 0.05f;

    /// <summary>How many Sacreds you receive for buying <paramref name="paidFor"/>.</summary>
    public static int TotalSacredsForPurchase(int paidFor)
    {
        if (paidFor <= 0) return 0;
        return paidFor + (paidFor / BulkBuyCount) * BulkFreeCount;
    }
}
