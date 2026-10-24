using System.Collections.Generic;
using NUnit.Framework;

/// <summary>
/// Spec §15.8: "Publish the drop table in the UI. Several jurisdictions require
/// disclosed odds for paid boxes."
///
/// <para>These are the only tests in the project about a number a regulator might
/// read, so they check the thing that actually matters: that the published figure
/// is what a player experiences, not what the roll tree would produce in
/// isolation.</para>
/// </summary>
public class OddsPublicationTests
{
    static OddsPublication.Row Find(DropTable.Outcome o)
    {
        foreach (OddsPublication.Row r in OddsPublication.Rows())
        {
            if (r.outcome == o) return r;
        }
        Assert.Fail("no published row for " + o);
        return default(OddsPublication.Row);
    }

    [Test]
    public void Odds_SumToOne()
    {
        double sum = 0;
        foreach (OddsPublication.Row r in OddsPublication.Rows()) sum += r.probability;
        Assert.AreEqual(1.0, sum, 1e-4,
            "the published outcomes sum to " + sum + ", so the table either double-counts or " +
            "loses probability. A disclosed table that does not sum to 1 is indefensible.");
    }

    /// <summary>
    /// ★ The one that matters. <see cref="DropTable.RgbPityThreshold"/> guarantees
    /// an RGB on the 101st open, and DropTable's own comment records that this
    /// makes RGB roughly 79x more common than the tree alone. Publishing the tree
    /// figure (0.0125%, 1 in 8,000) would understate the top prize of a paid box
    /// by about eighty times — wrong in the exact direction a disclosure rule
    /// exists to catch.
    /// </summary>
    [Test]
    public void Odds_PublishedRgbIncludesPity_NotJustTheTree()
    {
        float treeOnly = DropTable.SerializedChance
                       * DropTable.SctPromotionChance
                       * DropTable.RgbPromotionChance;

        OddsPublication.Row rgb = Find(DropTable.Outcome.RGB);

        Assert.Greater(rgb.probability, treeOnly * 10f,
            "the published RGB rate is " + rgb.probability + ", which is within an order of " +
            "magnitude of the bare tree rate " + treeOnly + ". Pity has not been included, so " +
            "the panel is understating how often the top prize actually lands.");

        // With the shipped constants this lands at ~0.996% — about 1 in 100.
        Assert.AreEqual(0.00996f, rgb.probability, 0.0005f,
            "the published RGB rate moved. If DropTable's constants changed this is expected " +
            "and the number here should be updated; if they did not, the pity maths broke.");
        Assert.AreEqual(100, rgb.oneInN,
            "the '1 in N' the player reads should be about 1 in 100 with the shipped pity " +
            "threshold, not 1 in 8,000.");
    }

    [Test]
    public void Odds_ExpectedOpensPerRgbIsJustUnderThePityThreshold()
    {
        double e = OddsPublication.ExpectedOpensPerRgb();

        Assert.Less(e, DropTable.RgbPityThreshold,
            "expected opens per RGB (" + e + ") is not below the pity threshold (" +
            DropTable.RgbPityThreshold + "). Natural pulls should shorten the average, so " +
            "anything at or above the threshold means the tree is being ignored entirely.");
        Assert.Greater(e, DropTable.RgbPityThreshold * 0.9,
            "expected opens per RGB (" + e + ") is far below the pity threshold, which would " +
            "mean natural RGB pulls dominate — they do not at these odds.");
    }

    [Test]
    public void Odds_SerializedAndSctKeepTheirTreeProportions()
    {
        OddsPublication.Row ser = Find(DropTable.Outcome.Serialized);
        OddsPublication.Row sct = Find(DropTable.Outcome.SCT);

        // The tree says an SCT is one 5% promotion below a plain serialized, so
        // serialized should be about 20x more common. Pity scales both equally
        // and must not disturb the ratio between them.
        Assert.AreEqual(20f, ser.probability / sct.probability, 1.5f,
            "the ratio of serialized to SCT is " + (ser.probability / sct.probability) +
            ", but the tree makes SCT exactly one 5% gate below serialized. Pity scales the " +
            "non-RGB outcomes uniformly, so this ratio should survive it.");
    }

    /// <summary>
    /// The published table must be derived from the constants the roll uses. If
    /// somebody retypes it as a literal string, changing the pity threshold moves
    /// the game's behaviour and leaves the disclosure behind.
    /// </summary>
    [Test]
    public void Odds_PanelTextQuotesThePityThresholdTheRollActuallyUses()
    {
        string text = OddsPublication.PanelText();

        StringAssert.Contains(DropTable.RgbPityThreshold.ToString(), text,
            "the odds panel does not mention the pity threshold the roll uses, so a player " +
            "cannot see the rule that produces almost every RGB in the game.");
        StringAssert.Contains(DropTable.BulkBuyCount.ToString(), text,
            "the panel does not state the buy-" + DropTable.BulkBuyCount + "-get-" +
            DropTable.BulkFreeCount + "-free rule.");

        foreach (OddsPublication.Row r in OddsPublication.Rows())
        {
            StringAssert.Contains(r.label, text, "the panel omits the " + r.label + " row.");
        }
    }

    [Test]
    public void Odds_EveryOutcomeIsPublished()
    {
        var published = new List<DropTable.Outcome>();
        foreach (OddsPublication.Row r in OddsPublication.Rows()) published.Add(r.outcome);

        foreach (DropTable.Outcome o in System.Enum.GetValues(typeof(DropTable.Outcome)))
        {
            Assert.Contains(o, published,
                o + " is a possible result and is missing from the published table. A " +
                "disclosure that omits an outcome is worse than none.");
        }
    }
}
