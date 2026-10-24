/// <summary>
/// The damage and Essence payout rules (spec §4.1), as pure functions.
///
/// Two things this shape buys us:
///  * The multiplier ORDER is fixed and provable. Spec is explicit that Double
///    Tap applies AFTER the headshot multiplier; with floats that is not merely
///    cosmetic once other multipliers land.
///  * The corpse rule (spec trap #3) becomes a parameter named wasAlive rather
///    than a comment. Corpses stay collider-hittable for seconds after death;
///    if payout is not gated on a health sample taken BEFORE the damage is
///    applied, corpses can be farmed indefinitely.
/// </summary>
public static class DamageMath
{
    public const int InstaKillDamage = 999999;

    // Essence payouts (spec §4.1)
    public const int PointsHitSurvived = 5;
    public const int PointsBodyKill    = 90;
    public const int PointsHeadshotKill = 130;
    public const int PointsNuke        = 400;

    /// <summary>
    /// final = damage x (headshot ? headshotMultiplier : 1) x (doubleTap ? 2 : 1),
    /// overridden entirely by Insta-Kill.
    /// </summary>
    public static float FinalDamage(float baseDamage, bool headshot, float headshotMultiplier,
                                    bool doubleTap, float doubleTapDamage, bool instaKill)
    {
        if (instaKill) return InstaKillDamage;
        float final = baseDamage;
        if (headshot) final *= headshotMultiplier;
        if (doubleTap) final *= doubleTapDamage;   // AFTER the headshot multiplier
        return final;
    }

    /// <summary>
    /// Essence for one bullet landing on an enemy.
    /// <paramref name="wasAlive"/> MUST be sampled before damage is applied.
    /// Double Points is NOT applied here — spec §7 says it multiplies at credit
    /// time inside the score manager, so applying it here too would double-dip.
    /// </summary>
    public static int PointsForHit(bool wasAlive, bool killedByThisHit, bool headshot)
    {
        if (!wasAlive) return 0;                                  // hitting a corpse pays nothing
        if (!killedByThisHit) return PointsHitSurvived;           // +5
        return headshot ? PointsHeadshotKill : PointsBodyKill;    // +130 / +90
    }

    /// <summary>Pack-a-Punch, spec §6/§4.1. Idempotent at the call site.
    /// fireRate, reloadTime and headshotMultiplier are deliberately untouched.</summary>
    public static void ApplyPackAPunch(ref float damage, ref int magazineSize, ref int maxReserve)
    {
        damage *= 2f;
        magazineSize = UnityEngine.Mathf.RoundToInt(magazineSize * 1.5f);   // 30 -> 45
        maxReserve *= 2;                                                     // 180 -> 360
    }
}
