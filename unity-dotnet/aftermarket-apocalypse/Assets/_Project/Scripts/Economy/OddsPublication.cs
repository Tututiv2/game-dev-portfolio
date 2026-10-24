using System.Collections.Generic;

/// <summary>
/// The odds panel's numbers, COMPUTED FROM <see cref="DropTable"/> rather than
/// retyped — §15.8's "publish the drop table in the UI".
///
/// <para><b>Why this class exists at all.</b> The obvious implementation is a
/// label reading "Serialized 4.75% / SCT 0.2375% / RGB 0.0125%", copied out of
/// DropTable's own doc comment. Those three numbers are the TREE's odds, and the
/// tree is not what the player experiences.</para>
///
/// <para><b>★ Pity dominates, and publishing the tree would understate RGB by
/// about eighty times.</b> <see cref="DropTable.RgbPityThreshold"/> guarantees an
/// RGB on the 101st open after the last one, and DropTable's own comment records
/// the consequence: RGB arrives at roughly 1% of opens rather than the tree's
/// 0.0125%. A published figure of 0.0125% would therefore be wrong in the
/// direction that matters — understating how often a paid box pays out its top
/// prize is exactly the claim a disclosure rule exists to police. Several
/// jurisdictions require disclosed odds for paid boxes; Belgium effectively bans
/// them outright.</para>
///
/// <para>So this computes the LONG-RUN frequency including pity, from the same
/// constants the roll uses. Change <see cref="DropTable.RgbPityThreshold"/> and
/// the published panel moves with it, because there is no second copy of the
/// number to forget.</para>
/// </summary>
public static class OddsPublication
{
    public struct Row
    {
        public DropTable.Outcome outcome;
        public string label;
        /// <summary>Long-run share of all opens, 0..1, pity included.</summary>
        public float probability;
        /// <summary>"1 in N", rounded. 0 when the outcome cannot occur.</summary>
        public int oneInN;
    }

    /// <summary>
    /// Expected opens per RGB, given the tree probability and the pity threshold.
    ///
    /// <para>A renewal cycle ends when an RGB is issued. Open k (k &lt; T) ends it
    /// naturally with probability p; open T ends it by force. So
    /// E[N] = Σ_{k=1..T-1} k·p·(1-p)^(k-1) + T·(1-p)^(T-1).</para>
    ///
    /// <para>Summed directly rather than in closed form: T is 101, the loop is
    /// free, and a closed form here would be a second place for the pity rule to
    /// be expressed slightly differently from the code that enforces it.</para>
    /// </summary>
    public static double ExpectedOpensPerRgb()
    {
        double p = (double)DropTable.SerializedChance
                 * DropTable.SctPromotionChance
                 * DropTable.RgbPromotionChance;
        int T = DropTable.RgbPityThreshold;

        if (T <= 1) return 1.0;          // every open is a pity open
        if (p <= 0.0) return T;          // only pity can produce one

        double e = 0.0;
        double survive = 1.0;            // (1-p)^(k-1)
        for (int k = 1; k <= T - 1; k++)
        {
            e += k * p * survive;
            survive *= (1.0 - p);
        }
        e += T * survive;                // forced on open T
        return e;
    }

    /// <summary>
    /// Long-run probability of each outcome per open, pity included. Sums to 1.
    /// </summary>
    public static IList<Row> Rows()
    {
        double pSer = DropTable.SerializedChance;
        double pSct = DropTable.SctPromotionChance;
        double pRgb = DropTable.RgbPromotionChance;

        // The tree, per open, ignoring pity.
        double treeNon = 1.0 - pSer;
        double treeSerialized = pSer * (1.0 - pSct);
        double treeSct = pSer * pSct * (1.0 - pRgb);
        double treeRgb = pSer * pSct * pRgb;
        double treeNotRgb = 1.0 - treeRgb;

        // Pity sets the RGB rate outright; the rest share what is left, in the
        // tree's own proportions conditioned on "not an RGB open".
        double rgb = 1.0 / ExpectedOpensPerRgb();
        double rest = 1.0 - rgb;
        double scale = treeNotRgb > 0.0 ? rest / treeNotRgb : 0.0;

        var rows = new List<Row>
        {
            Make(DropTable.Outcome.NonSerialized, "Standard", treeNon * scale),
            Make(DropTable.Outcome.Serialized, "Serialized", treeSerialized * scale),
            Make(DropTable.Outcome.SCT, "SCT", treeSct * scale),
            Make(DropTable.Outcome.RGB, "RGB", rgb),
        };
        return rows;
    }

    static Row Make(DropTable.Outcome o, string label, double p)
    {
        return new Row
        {
            outcome = o,
            label = label,
            probability = (float)p,
            oneInN = p > 0.0 ? (int)System.Math.Round(1.0 / p) : 0,
        };
    }

    /// <summary>
    /// The panel text. Kept here so the store screen cannot quietly reformat the
    /// numbers into something friendlier.
    /// </summary>
    public static string PanelText()
    {
        var sb = new System.Text.StringBuilder();
        sb.AppendLine("ODDS PER OPEN");
        foreach (Row r in Rows())
        {
            sb.AppendLine(string.Format("  {0,-11} {1,7:0.0000}%   1 in {2:n0}",
                                        r.label, r.probability * 100f, r.oneInN));
        }
        sb.AppendLine();
        sb.AppendLine("Includes the guaranteed RGB on open " + DropTable.RgbPityThreshold +
                      " after the last one.");
        sb.AppendLine("Buy " + DropTable.BulkBuyCount + ", get " + DropTable.BulkFreeCount + " free.");
        return sb.ToString();
    }
}
