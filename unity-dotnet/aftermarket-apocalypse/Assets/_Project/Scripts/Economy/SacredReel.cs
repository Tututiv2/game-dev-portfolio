using System;
using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// The maths of the §15.8.1 opening reel, as a pure class with no Unity objects
/// in it, so the parts that can be wrong silently are testable without a scene.
///
/// <para><b>The result is decided before this class is constructed.</b> Spec
/// §18.4: "the result is decided by the server before the animation starts. The
/// reel is theatre played over a known outcome. Never let the animation determine
/// the item." So <see cref="Build"/> takes the winning outcome as an argument and
/// its only job is to arrange 30 cards such that the winner is the one under the
/// marker when the scroll stops. It cannot roll, and it holds no random source
/// except the caller's seed — which decides the FILLER, never the winner.</para>
///
/// <para><b>The near-miss is the whole reason the animation works</b> (§15.8.1
/// beat 4): the strip is seeded so a higher-rarity card passes one or two slots
/// before the winner. That is a display fact, not a lie about the outcome — the
/// winner index is fixed first and the tease is written next to it.</para>
/// </summary>
public class SacredReel
{
    /// <summary>One card on the strip. Purely what to draw.</summary>
    [Serializable]
    public struct Card
    {
        public Rarity rarity;
        public SkinVariant variant;
        public bool isWinner;
    }

    /// <summary>
    /// How many cards the strip holds. §15.8.1 says "~30", and §18.6 says pool
    /// them — 30 instantiations per open is a stutter.
    /// </summary>
    public const int CardCount = 30;

    /// <summary>
    /// Where the winner sits. Deliberately NOT the last card: the strip has to
    /// continue past the marker or the reel visibly runs out of cards as it
    /// stops, which reads as the animation breaking rather than decelerating.
    /// </summary>
    public const int WinnerIndex = 25;

    /// <summary>§15.8.1 beat 3: the spin is 5-6 s.</summary>
    public const float BaseSpinSeconds = 5.2f;

    /// <summary>
    /// §15.8.1: a serialized pull "slows FURTHER than normal before landing".
    /// Extra seconds added to the spin, by outcome.
    /// </summary>
    public static float SpinSecondsFor(DropTable.Outcome o)
    {
        switch (o)
        {
            case DropTable.Outcome.RGB: return BaseSpinSeconds + 1.6f;
            case DropTable.Outcome.SCT: return BaseSpinSeconds + 1.1f;
            case DropTable.Outcome.Serialized: return BaseSpinSeconds + 0.6f;
            default: return BaseSpinSeconds;
        }
    }

    public readonly Card[] Cards;
    public readonly float SpinSeconds;
    public readonly DropTable.Outcome Outcome;

    SacredReel(Card[] cards, float spinSeconds, DropTable.Outcome outcome)
    {
        Cards = cards;
        SpinSeconds = spinSeconds;
        Outcome = outcome;
    }

    /// <summary>
    /// Arrange the strip for an already-decided outcome.
    /// </summary>
    /// <param name="outcome">what the backend already rolled — never re-rolled here</param>
    /// <param name="seed">decides FILLER cards and which of the two near-miss
    /// slots is used. Deterministic so an open can be replayed exactly.</param>
    public static SacredReel Build(DropTable.Outcome outcome, int seed)
    {
        var cards = new Card[CardCount];
        var rng = new System.Random(seed);

        // Filler first, so the winner and the tease can overwrite it and there is
        // no chance of a hole.
        for (int i = 0; i < CardCount; i++)
        {
            cards[i] = new Card
            {
                rarity = FillerRarity(rng),
                variant = SkinVariant.None,
                isWinner = false,
            };
        }

        // The winner. Written before the near-miss so the tease can never
        // overwrite it — an off-by-one here would hand the player a different
        // item than the server issued, which is the one bug in this file that
        // would actually matter.
        cards[WinnerIndex] = new Card
        {
            rarity = DropTable.RarityFor(outcome),
            variant = DropTable.VariantFor(outcome),
            isWinner = true,
        };

        // The near-miss: one or two slots BEFORE the winner, i.e. passing the
        // marker just before it.
        int teaseOffset = 1 + rng.Next(2);          // 1 or 2
        int teaseIndex = WinnerIndex - teaseOffset;
        if (teaseIndex >= 0 && teaseIndex != WinnerIndex)
        {
            cards[teaseIndex] = new Card
            {
                rarity = TeaseRarity(outcome),
                variant = SkinVariant.None,
                isWinner = false,
            };
        }

        return new SacredReel(cards, SpinSecondsFor(outcome), outcome);
    }

    /// <summary>
    /// Ordinary cards. Weighted toward the common tiers because a strip of
    /// mostly-legendaries makes the winner unremarkable — the tease only reads as
    /// a tease against a dull background.
    /// </summary>
    static Rarity FillerRarity(System.Random rng)
    {
        int r = rng.Next(100);
        if (r < 55) return Rarity.Common;
        if (r < 82) return Rarity.Rare;
        if (r < 95) return Rarity.Epic;
        return Rarity.Legendary;
    }

    /// <summary>
    /// The near-miss card's rarity: strictly ABOVE the winner where there is room,
    /// and one below it at the top of the ladder.
    ///
    /// <para>For an RGB win there is nothing higher, so the tease is SCT — still a
    /// heart-stopper, and it keeps the beat rather than dropping it for exactly
    /// the pull that most deserves one.</para>
    /// </summary>
    public static Rarity TeaseRarity(DropTable.Outcome outcome)
    {
        switch (outcome)
        {
            case DropTable.Outcome.RGB: return Rarity.Secret;        // one below
            case DropTable.Outcome.SCT: return Rarity.SecretRGB;     // above
            case DropTable.Outcome.Serialized: return Rarity.Secret; // above
            default: return Rarity.Limited;                          // above Common
        }
    }

    /// <summary>
    /// §15.8.1 beat 3: "cubic ease-out so it decelerates hard at the end".
    /// </summary>
    public static float EaseOutCubic(float t)
    {
        t = Mathf.Clamp01(t);
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /// <summary>
    /// How far the strip has scrolled at time <paramref name="seconds"/>, measured
    /// in CARDS. 0 puts card 0 under the marker; <see cref="WinnerIndex"/> puts the
    /// winner under it.
    /// </summary>
    public float CardsScrolledAt(float seconds)
    {
        if (SpinSeconds <= 0f) return WinnerIndex;
        return WinnerIndex * EaseOutCubic(seconds / SpinSeconds);
    }

    /// <summary>
    /// How many cards have crossed the marker by <paramref name="seconds"/>. Each
    /// one is a tick, with pitch rising as the gaps widen in time (§15.8.1).
    /// </summary>
    public int TicksAt(float seconds)
    {
        return Mathf.Clamp(Mathf.FloorToInt(CardsScrolledAt(seconds)), 0, WinnerIndex);
    }

    /// <summary>
    /// The index under the marker once the spin has finished. Exposed so a test
    /// can assert the reel lands on the winner rather than near it.
    /// </summary>
    public int LandedIndex
    {
        get { return Mathf.RoundToInt(CardsScrolledAt(SpinSeconds)); }
    }

    /// <summary>
    /// §15.8.1: "Do not allow skipping for ~2 s" on an RGB full-screen takeover.
    /// Everything else is skippable as soon as the reveal starts.
    /// </summary>
    public static float NoSkipSecondsFor(DropTable.Outcome o)
    {
        return o == DropTable.Outcome.RGB ? 2f : 0f;
    }

    /// <summary>
    /// §15.8.1: bulk opens support skip-to-summary, but anything serialized or
    /// above still plays its full reveal. So "can this one be summarised away"
    /// is a property of the outcome, not a user preference.
    /// </summary>
    public static bool CanSkipInBulk(DropTable.Outcome o)
    {
        return o == DropTable.Outcome.NonSerialized;
    }

    /// <summary>
    /// Screen shake at the settle, proportional to rarity (§15.8.1 beat 5).
    /// </summary>
    public static float ShakeFor(DropTable.Outcome o)
    {
        switch (o)
        {
            case DropTable.Outcome.RGB: return 1f;
            case DropTable.Outcome.SCT: return 0.65f;
            case DropTable.Outcome.Serialized: return 0.35f;
            default: return 0.12f;
        }
    }

    /// <summary>The tier colours. One address, so two tiers cannot drift together.</summary>
    public static Color ColorFor(Rarity r)
    {
        switch (r)
        {
            case Rarity.Common: return new Color(0.62f, 0.65f, 0.68f);
            case Rarity.Rare: return new Color(0.30f, 0.55f, 0.90f);
            case Rarity.Epic: return new Color(0.62f, 0.35f, 0.88f);
            case Rarity.Legendary: return new Color(0.92f, 0.62f, 0.18f);
            case Rarity.Mythic: return new Color(0.90f, 0.25f, 0.35f);
            case Rarity.Limited: return new Color(0.95f, 0.82f, 0.30f);
            case Rarity.Secret: return new Color(1.00f, 0.95f, 0.80f);
            case Rarity.SecretRGB: return new Color(1.00f, 1.00f, 1.00f);
            default: return Color.grey;
        }
    }
}
