using NUnit.Framework;
using UnityEngine;

/// <summary>
/// Spec §15.8.1 — the opening. The tests here are the ones where being wrong
/// costs the player something real, not the ones that check the animation is
/// pretty.
///
/// <para>The single worst bug this feature can have is the reel landing on a
/// different card than the one the server minted. The player would see an item
/// they do not own, and their inventory would disagree with the animation they
/// just screenshotted. Everything else is cosmetic by comparison.</para>
/// </summary>
public class SacredOpeningTests
{
    static SkinInstance Item(SkinVariant variant, int serial, Rarity rarity)
    {
        return new SkinInstance
        {
            itemId = "item-" + serial + "-" + variant,
            designId = "starfade_camo",
            serial = serial,
            editionCap = 250,
            wear = 0.0412f,
            variant = variant,
            rarity = rarity,
        };
    }

    // --------------------------------------------------------------- the reel

    [Test]
    public void Reel_LandsExactlyOnTheWinner_NotNearIt()
    {
        foreach (DropTable.Outcome o in System.Enum.GetValues(typeof(DropTable.Outcome)))
        {
            for (int seed = 0; seed < 25; seed++)
            {
                SacredReel reel = SacredReel.Build(o, seed);

                Assert.AreEqual(SacredReel.WinnerIndex, reel.LandedIndex,
                    "outcome " + o + " seed " + seed + ": the reel stopped on card " +
                    reel.LandedIndex + " but the winner is at " + SacredReel.WinnerIndex +
                    ". The player would be shown an item the server did not mint.");

                Assert.IsTrue(reel.Cards[SacredReel.WinnerIndex].isWinner,
                    "outcome " + o + " seed " + seed + ": the landing slot is not flagged as " +
                    "the winner, so the reveal would read a filler card.");
            }
        }
    }

    [Test]
    public void Reel_TheWinnersCardMatchesTheOutcomeTheBackendRolled()
    {
        foreach (DropTable.Outcome o in System.Enum.GetValues(typeof(DropTable.Outcome)))
        {
            SacredReel reel = SacredReel.Build(o, 7);
            SacredReel.Card w = reel.Cards[SacredReel.WinnerIndex];

            Assert.AreEqual(DropTable.RarityFor(o), w.rarity,
                "the winning card's rarity disagrees with the outcome for " + o);
            Assert.AreEqual(DropTable.VariantFor(o), w.variant,
                "the winning card's variant disagrees with the outcome for " + o);
        }
    }

    /// <summary>
    /// §15.8.1 beat 4: "the reel is seeded so a high-rarity card passes 1–2 slots
    /// before the winner. This is not a trick; it is the whole reason the
    /// animation works." A near-miss that lands anywhere else is not a near-miss.
    /// </summary>
    [Test]
    public void Reel_TheNearMissSitsOneOrTwoSlotsBeforeTheWinner()
    {
        bool sawOne = false, sawTwo = false;

        for (int seed = 0; seed < 60; seed++)
        {
            SacredReel reel = SacredReel.Build(DropTable.Outcome.NonSerialized, seed);
            Rarity tease = SacredReel.TeaseRarity(DropTable.Outcome.NonSerialized);

            bool at1 = reel.Cards[SacredReel.WinnerIndex - 1].rarity == tease;
            bool at2 = reel.Cards[SacredReel.WinnerIndex - 2].rarity == tease;
            Assert.IsTrue(at1 || at2,
                "seed " + seed + ": no tease card in either slot before the winner.");

            sawOne |= at1;
            sawTwo |= at2;
        }

        Assert.IsTrue(sawOne && sawTwo,
            "across 60 seeds the tease only ever used one of the two slots, so it is at a " +
            "fixed distance and players will learn it. §15.8.1 says 1-2 slots.");
    }

    [Test]
    public void Reel_TheTeaseNeverOverwritesTheWinner()
    {
        foreach (DropTable.Outcome o in System.Enum.GetValues(typeof(DropTable.Outcome)))
        {
            for (int seed = 0; seed < 60; seed++)
            {
                SacredReel reel = SacredReel.Build(o, seed);
                int winners = 0;
                foreach (SacredReel.Card c in reel.Cards)
                {
                    if (c.isWinner) winners++;
                }
                Assert.AreEqual(1, winners,
                    "outcome " + o + " seed " + seed + ": " + winners + " cards flagged winner.");
            }
        }
    }

    [Test]
    public void Reel_IsDeterministicForTheSameSeed()
    {
        SacredReel a = SacredReel.Build(DropTable.Outcome.SCT, 12345);
        SacredReel b = SacredReel.Build(DropTable.Outcome.SCT, 12345);
        for (int i = 0; i < SacredReel.CardCount; i++)
        {
            Assert.AreEqual(a.Cards[i].rarity, b.Cards[i].rarity,
                "card " + i + " differs between two builds with the same seed, so an open " +
                "cannot be replayed from a bug report.");
        }
    }

    /// <summary>
    /// §15.8.1 beat 3: "cubic ease-out so it decelerates hard at the end". A
    /// linear scroll would pass this file's other tests and feel like nothing.
    /// </summary>
    [Test]
    public void Reel_DeceleratesRatherThanScrollingLinearly()
    {
        SacredReel reel = SacredReel.Build(DropTable.Outcome.NonSerialized, 1);
        float half = reel.CardsScrolledAt(reel.SpinSeconds * 0.5f);
        float linearHalf = SacredReel.WinnerIndex * 0.5f;

        Assert.Greater(half, linearHalf * 1.3f,
            "at the halfway point the reel had only travelled " + half + " cards of " +
            SacredReel.WinnerIndex + ". A cubic ease-out should be well past halfway by now; " +
            "this is scrolling more or less linearly and will not read as slowing down.");

        // And it must actually arrive, not asymptote.
        Assert.AreEqual(SacredReel.WinnerIndex, reel.CardsScrolledAt(reel.SpinSeconds), 0.001f);
        Assert.AreEqual(0f, reel.CardsScrolledAt(0f), 0.001f);
    }

    [Test]
    public void Reel_SerializedPullsSpinLongerThanOrdinaryOnes()
    {
        float plain = SacredReel.SpinSecondsFor(DropTable.Outcome.NonSerialized);
        Assert.Greater(SacredReel.SpinSecondsFor(DropTable.Outcome.Serialized), plain,
            "§15.8.1: a serialized pull must slow FURTHER than normal before landing.");
        Assert.Greater(SacredReel.SpinSecondsFor(DropTable.Outcome.SCT),
                       SacredReel.SpinSecondsFor(DropTable.Outcome.Serialized));
        Assert.Greater(SacredReel.SpinSecondsFor(DropTable.Outcome.RGB),
                       SacredReel.SpinSecondsFor(DropTable.Outcome.SCT));
        Assert.GreaterOrEqual(plain, 5f, "§15.8.1 asks for a 5-6 s spin.");
        Assert.LessOrEqual(plain, 6f, "§15.8.1 asks for a 5-6 s spin.");
    }

    [Test]
    public void Reel_OnlyRgbBlocksSkipping_AndOnlyOrdinaryPullsCanBeSummarisedAway()
    {
        Assert.AreEqual(2f, SacredReel.NoSkipSecondsFor(DropTable.Outcome.RGB), 0.001f,
            "§15.8.1: do not allow skipping an RGB takeover for ~2 s.");
        Assert.AreEqual(0f, SacredReel.NoSkipSecondsFor(DropTable.Outcome.SCT), 0.001f,
            "only RGB blocks skipping; making the player sit through every SCT is a different " +
            "and worse decision than the spec's.");

        Assert.IsTrue(SacredReel.CanSkipInBulk(DropTable.Outcome.NonSerialized));
        foreach (DropTable.Outcome o in new[] { DropTable.Outcome.Serialized,
                                                DropTable.Outcome.SCT, DropTable.Outcome.RGB })
        {
            Assert.IsFalse(SacredReel.CanSkipInBulk(o),
                "§15.8.1: a bulk skip-to-summary must still play the full reveal for " +
                o + ".");
        }
    }

    // ------------------------------------------------------- outcome recovery

    /// <summary>
    /// The opening derives the tier from the minted item rather than being told
    /// it separately. If this mapping is wrong an RGB pull gets a standard
    /// presentation — the rarest thing in the game arriving with no ceremony.
    /// </summary>
    [Test]
    public void Opening_RecoversTheOutcomeTierFromTheMintedItem()
    {
        Assert.AreEqual(DropTable.Outcome.RGB,
            SacredOpening.OutcomeOf(Item(SkinVariant.RGB, 3, Rarity.SecretRGB)));
        Assert.AreEqual(DropTable.Outcome.SCT,
            SacredOpening.OutcomeOf(Item(SkinVariant.SCT, 9, Rarity.Secret)));
        Assert.AreEqual(DropTable.Outcome.Serialized,
            SacredOpening.OutcomeOf(Item(SkinVariant.None, 37, Rarity.Limited)));
        Assert.AreEqual(DropTable.Outcome.NonSerialized,
            SacredOpening.OutcomeOf(Item(SkinVariant.None, 0, Rarity.Common)));

        // A Tracker is a variant but NOT a serialisation tier — §15.7 says ~5% of
        // items are Trackers, including ordinary ones. Treating it as serialized
        // would give twenty times too many items the gold-seam treatment.
        Assert.AreEqual(DropTable.Outcome.NonSerialized,
            SacredOpening.OutcomeOf(Item(SkinVariant.Tracker, 0, Rarity.Common)));
    }

    [Test]
    public void Opening_ShakeEscalatesWithRarity()
    {
        Assert.Greater(SacredReel.ShakeFor(DropTable.Outcome.RGB),
                       SacredReel.ShakeFor(DropTable.Outcome.SCT));
        Assert.Greater(SacredReel.ShakeFor(DropTable.Outcome.SCT),
                       SacredReel.ShakeFor(DropTable.Outcome.Serialized));
        Assert.Greater(SacredReel.ShakeFor(DropTable.Outcome.Serialized),
                       SacredReel.ShakeFor(DropTable.Outcome.NonSerialized));
    }

    [Test]
    public void Opening_EveryRarityTierGetsItsOwnColour()
    {
        var seen = new System.Collections.Generic.Dictionary<string, Rarity>();
        foreach (Rarity r in System.Enum.GetValues(typeof(Rarity)))
        {
            Color c = SacredReel.ColorFor(r);
            string key = c.r.ToString("0.000") + "," + c.g.ToString("0.000") + "," + c.b.ToString("0.000");
            Rarity clash;
            Assert.IsFalse(seen.TryGetValue(key, out clash),
                r + " renders in the same colour as " + clash +
                ". Rarity colour is the entire readability budget on a card — the same trap " +
                "Pack-a-Punch and Double Tap hit in the map.");
            seen[key] = r;
        }
    }
}
