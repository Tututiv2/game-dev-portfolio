using System;
using UnityEngine;

/// <summary>Spec §15.2 / §15.7. serialized = Limited, SCT = Secret, RGB = Secret RGB.</summary>
public enum SkinVariant { None, Tracker, SCT, RGB }

/// <summary>Spec §15.2's ladder. The top three are serialized: numbered, capped, tradable.</summary>
public enum Rarity { Common, Rare, Epic, Legendary, Mythic, Limited, Secret, SecretRGB }

/// <summary>Spec §15.6 wear bands.</summary>
public enum FloatBand { FactoryNew, MinimalWear, FieldTested, WellWorn, BattleScarred }

/// <summary>
/// One owned item. Field-for-field from spec §15.9.
///
/// ★ Two of these fields exist because of §15.9's standing instruction: "If a
/// third-party market ever appears, the only thing that makes it viable is
/// STABLE GLOBALLY-UNIQUE ITEM IDS plus a SERVER-AUTHORITATIVE OWNER RECORD.
/// Both are nearly free to include now and effectively impossible to retrofit
/// onto items already issued. Include them. Ship no market." So itemId and the
/// owner chain are here from the first minted item, and nothing sells for money.
/// </summary>
[Serializable]
public class SkinInstance
{
    /// <summary>Globally unique, permanent — the anchor for everything.</summary>
    public string itemId;
    public string designId;

    /// <summary>0 for non-serialized. Sequential and permanent per design.</summary>
    public int serial;
    public int editionCap;

    /// <summary>0..1, permanent, travels with the item through trades.</summary>
    public float wear;

    public SkinVariant variant;
    public Rarity rarity;
    public int trackerKills;

    /// <summary>NEVER changes — this is the "OG" badge.</summary>
    public string originalOwnerUserId;
    public string[] previousOwnerIds = new string[0];
    public string currentOwnerId;

    /// <summary>ISO-8601. Stored as a string so JsonUtility round-trips it.</summary>
    public string mintDate;
    public string acquisitionSource;

    public bool IsSerialized { get { return serial > 0; } }

    /// <summary>Spec §15.5: serial #1 of any design is a one-time event in the
    /// game's lifetime. "Surface it loudly."</summary>
    public bool IsFirstOfItsKind { get { return serial == 1; } }

    public FloatBand Band { get { return FloatWear.BandFor(wear); } }

    public string DisplaySerial()
    {
        return IsSerialized ? "#" + serial + " / " + editionCap : "";
    }

    /// <summary>
    /// A detached copy. The backend hands these out instead of its own records
    /// (spec §18.4) — a caller that writes to what it was given must not be able
    /// to reach authoritative state. A cloud backend returns deserialized DTOs
    /// and such writes are silently local; LocalBackend must behave the same way
    /// or Phase 10's swap changes behaviour at every call site.
    /// </summary>
    public SkinInstance Clone()
    {
        return new SkinInstance
        {
            itemId = itemId,
            designId = designId,
            serial = serial,
            editionCap = editionCap,
            wear = wear,
            variant = variant,
            rarity = rarity,
            trackerKills = trackerKills,
            originalOwnerUserId = originalOwnerUserId,
            previousOwnerIds = previousOwnerIds == null
                ? new string[0]
                : (string[])previousOwnerIds.Clone(),
            currentOwnerId = currentOwnerId,
            mintDate = mintDate,
            acquisitionSource = acquisitionSource
        };
    }
}

/// <summary>Spec §15.6 wear bands and the float roll.</summary>
public static class FloatWear
{
    public const float FactoryNewMax = 0.07f;
    public const float MinimalWearMax = 0.15f;
    public const float FieldTestedMax = 0.38f;
    public const float WellWornMax = 0.45f;

    public static FloatBand BandFor(float f)
    {
        if (f < FactoryNewMax) return FloatBand.FactoryNew;
        if (f < MinimalWearMax) return FloatBand.MinimalWear;
        if (f < FieldTestedMax) return FloatBand.FieldTested;
        if (f < WellWornMax) return FloatBand.WellWorn;
        return FloatBand.BattleScarred;
    }

    public static string ShortName(FloatBand b)
    {
        switch (b)
        {
            case FloatBand.FactoryNew: return "FN";
            case FloatBand.MinimalWear: return "MW";
            case FloatBand.FieldTested: return "FT";
            case FloatBand.WellWorn: return "WW";
            default: return "BS";
        }
    }

    /// <summary>
    /// Spec §15.6:
    ///   roll = pow(random01, floatCurveExponent)   // premium 0.5 skews clean, F2P 1.0
    ///   f    = lerp(design.floatMin, design.floatMax, roll)
    /// </summary>
    public static float Roll(float random01, float curveExponent, float floatMin, float floatMax)
    {
        float roll = Mathf.Pow(Mathf.Clamp01(random01), curveExponent);
        return Mathf.Lerp(floatMin, floatMax, roll);
    }
}
