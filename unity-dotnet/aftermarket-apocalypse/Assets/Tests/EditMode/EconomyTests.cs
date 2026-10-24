using System.Threading.Tasks;
using NUnit.Framework;
using UnityEngine;

/// <summary>
/// Phase 2's economy core against REBUILD_SPEC.md §15.3-15.6, §18.3-18.5.
///
/// These numbers are the product. §15.3's tree and §15.4's pity are the thing
/// players chase, and §15.4 explicitly warns against "silently correcting" the
/// pity rate — so the accepted consequence is asserted here rather than left as
/// prose somebody later mistakes for a bug.
/// </summary>
public class EconomyTests
{
    // ---------- §15.3 the promotion tree ----------

    [Test]
    public void Spec153_TreeIsStrictlySequential_RgbCannotSkipSct()
    {
        // roll1 passes the serialized gate, roll2 FAILS the SCT gate.
        // Even with a roll3 that would have promoted, the result must stop at
        // Serialized: "RGB cannot appear on a non-SCT item."
        var o = DropTable.Resolve(0.01f, 0.99f, 0.0f, 0);
        Assert.AreEqual(DropTable.Outcome.Serialized, o);
    }

    [Test]
    public void Spec153_EachGateIsFivePercent()
    {
        Assert.AreEqual(0.05f, DropTable.SerializedChance, 1e-6f);
        Assert.AreEqual(0.05f, DropTable.SctPromotionChance, 1e-6f);
        Assert.AreEqual(0.05f, DropTable.RgbPromotionChance, 1e-6f);
    }

    [Test]
    public void Spec153_CompoundOddsMatchThePublishedTable()
    {
        // §15.3's table: 4.75% serialized, 0.2375% SCT, 0.0125% RGB.
        float pSerializedOrBetter = DropTable.SerializedChance;
        float pSctOrBetter = DropTable.SerializedChance * DropTable.SctPromotionChance;
        float pRgb = pSctOrBetter * DropTable.RgbPromotionChance;

        Assert.AreEqual(0.0475f, pSerializedOrBetter - pSctOrBetter, 1e-6f, "plain serialized ~1 in 21");
        Assert.AreEqual(0.002375f, pSctOrBetter - pRgb, 1e-6f, "SCT ~1 in 421");
        Assert.AreEqual(0.000125f, pRgb, 1e-9f, "RGB ~1 in 8,000");
    }

    [Test]
    public void Spec153_NonSerializedIsNinetyFivePercent()
    {
        var o = DropTable.Resolve(0.06f, 0f, 0f, 0);
        Assert.AreEqual(DropTable.Outcome.NonSerialized, o);
        Assert.IsFalse(DropTable.IsSerializedOutcome(o));
    }

    [Test]
    public void Spec153_AllThreeGatesPassingGivesRgb()
    {
        Assert.AreEqual(DropTable.Outcome.RGB, DropTable.Resolve(0.0f, 0.0f, 0.0f, 0));
    }

    // ---------- §15.4 pity ----------

    [Test]
    public void Spec154_PityThresholdIsOneHundredAndOne()
    {
        Assert.AreEqual(101, DropTable.RgbPityThreshold);
    }

    [Test]
    public void Spec154_HundredAndFirstOpenIsGuaranteedRgb()
    {
        // 100 opens have happened since the last RGB; this one is the 101st.
        // Rolls chosen to fail every gate — pity must override them.
        var o = DropTable.Resolve(0.99f, 0.99f, 0.99f, 100);
        Assert.AreEqual(DropTable.Outcome.RGB, o, "The 101st open after the last RGB is guaranteed.");
    }

    [Test]
    public void Spec154_HundredthOpenIsNotGuaranteed()
    {
        var o = DropTable.Resolve(0.99f, 0.99f, 0.99f, 99);
        Assert.AreEqual(DropTable.Outcome.NonSerialized, o, "Pity must not fire one open early.");
    }

    [Test]
    public void Spec154_AcceptedConsequence_PityDominatesNaturalRgbRate()
    {
        // ★ §15.4 records this deliberately: pity yields RGB at ~1% of opens vs
        // 0.0125% from the tree — about 79x — so nearly all RGB comes from pity.
        // The spec says implement it as the named constant and DO NOT silently
        // "correct" it. This test exists so a future reader sees it as intended.
        float pityRate = 1f / DropTable.RgbPityThreshold;
        float naturalRate = DropTable.SerializedChance * DropTable.SctPromotionChance
                          * DropTable.RgbPromotionChance;

        Assert.AreEqual(0.0099f, pityRate, 0.0005f, "~1% of opens");
        Assert.Greater(pityRate / naturalRate, 70f, "The spec quotes roughly 79x the natural rate.");
        Assert.Less(pityRate / naturalRate, 90f);
    }

    [Test]
    public void Spec154_BulkBuyGivesElevenForTen()
    {
        Assert.AreEqual(11, DropTable.TotalSacredsForPurchase(10));
        Assert.AreEqual(9, DropTable.TotalSacredsForPurchase(9), "Nine paid earns no bonus.");
        Assert.AreEqual(22, DropTable.TotalSacredsForPurchase(20));
        Assert.AreEqual(0, DropTable.TotalSacredsForPurchase(0));
    }

    // ---------- §15.6 float / wear ----------

    [Test]
    public void Spec156_BandBoundaries()
    {
        Assert.AreEqual(FloatBand.FactoryNew, FloatWear.BandFor(0.00f));
        Assert.AreEqual(FloatBand.FactoryNew, FloatWear.BandFor(0.069f));
        Assert.AreEqual(FloatBand.MinimalWear, FloatWear.BandFor(0.07f));
        Assert.AreEqual(FloatBand.FieldTested, FloatWear.BandFor(0.15f));
        Assert.AreEqual(FloatBand.WellWorn, FloatWear.BandFor(0.38f));
        Assert.AreEqual(FloatBand.BattleScarred, FloatWear.BandFor(0.45f));
        Assert.AreEqual(FloatBand.BattleScarred, FloatWear.BandFor(1.00f));
    }

    /// <summary>
    /// ⚠ A CONTRADICTION INSIDE SPEC §15.6, IMPLEMENTED LITERALLY AND FLAGGED
    /// RATHER THAN SILENTLY "FIXED".
    ///
    /// §15.6 states two things that cannot both hold:
    ///   (a) "Lower is cleaner" — Factory New is 0.00-0.07, Battle-Scarred 0.45-1.00.
    ///   (b) roll = pow(random01, floatCurveExponent), "premium 0.5 (skews clean)".
    ///
    /// For random01 in [0,1], pow(x, 0.5) >= x. So an exponent BELOW 1 pushes the
    /// roll UP, toward higher floats — i.e. DIRTIER, the opposite of the stated
    /// intent. Skewing clean needs an exponent ABOVE 1 (pow(0.25, 2) = 0.0625).
    ///
    /// The formula is implemented exactly as written, because paying customers
    /// getting systematically worse wear than free players is a decision with
    /// money attached and is the owner's to make, not one to quietly invert.
    /// Flip SacredDefinition.floatCurveExponent to ~2.0 on premium Sacreds to get
    /// the behaviour the prose describes.
    /// </summary>
    [Test]
    public void Spec156_PremiumExponentOfPointFiveActuallySkewsDIRTIER_specContradiction()
    {
        float f2p = FloatWear.Roll(0.25f, 1.0f, 0f, 1f);
        float premiumAsWritten = FloatWear.Roll(0.25f, 0.5f, 0f, 1f);
        float premiumAsIntended = FloatWear.Roll(0.25f, 2.0f, 0f, 1f);

        Assert.AreEqual(0.25f, f2p, 1e-4f, "F2P exponent 1.0 is a flat pass-through.");
        Assert.AreEqual(0.5f, premiumAsWritten, 1e-4f, "pow(0.25, 0.5) = 0.5");

        Assert.Greater(premiumAsWritten, f2p,
            "Exponent 0.5 yields a HIGHER float than F2P. Higher = more worn = Battle-Scarred.");
        Assert.Less(premiumAsIntended, f2p,
            "An exponent above 1 is what actually skews toward Factory New.");
    }

    [Test]
    public void Spec156_PerDesignCapsConstrainTheBand()
    {
        // A design that only exists Factory New / Minimal Wear.
        for (float r = 0f; r <= 1f; r += 0.05f)
        {
            float f = FloatWear.Roll(r, 1f, 0f, 0.14f);
            Assert.LessOrEqual(f, 0.14f);
            Assert.AreNotEqual(FloatBand.BattleScarred, FloatWear.BandFor(f));
        }
    }

    // ---------- §18.3 / §18.4 / §18.5 the backend ----------

    private static SkinDesign MakeDesign(string id, int cap)
    {
        SkinDesign d = ScriptableObject.CreateInstance<SkinDesign>();
        d.designId = id;
        d.displayName = id;
        d.weaponName = "M4";
        d.editionCap = cap;
        d.floatMin = 0f;
        d.floatMax = 1f;
        d.linkedCamoIndex = -1;
        return d;
    }

    private static SacredDefinition MakeSacred(string id, CurrencyKind cur, int price, params SkinDesign[] designs)
    {
        SacredDefinition s = ScriptableObject.CreateInstance<SacredDefinition>();
        s.sacredId = id;
        s.displayName = id;
        s.currency = cur;
        s.price = price;
        s.floatCurveExponent = 1f;
        s.designs = designs;
        return s;
    }

    private static LocalBackend MakeBackend(params SacredDefinition[] sacreds)
    {
        LocalBackend b = new LocalBackend();
        b.UseInMemoryStore();          // never touch the real collection.json
        b.SetCatalogue(sacreds);
        return b;
    }

    [Test]
    public void Spec185_SerialsAreSequentialAndNeverRepeat()
    {
        SkinDesign design = MakeDesign("only", 500);
        // A ZERO-PRICE EMBERS Sacred, not a Free one.
        //
        // This test opens 400 times to prove the serial counter is atomic and
        // gapless; it used CurrencyKind.Free only because that charged nothing.
        // Free is now a once-per-day entitlement (spec line 1364), so 400 free
        // opens is exactly what must NOT be possible. Price 0 in a charged
        // currency keeps this test measuring the mint instead of the paywall.
        SacredDefinition sacred = MakeSacred("free_sacred", CurrencyKind.Embers, 0, design);
        LocalBackend backend = MakeBackend(sacred);

        var seen = new System.Collections.Generic.HashSet<int>();
        int serialised = 0;

        for (int i = 0; i < 400; i++)
        {
            SacredResult r = backend.OpenSacred("tutut", "free_sacred").GetAwaiter().GetResult();
            Assert.IsTrue(r.success, r.message);
            if (r.item.serial == 0) continue;
            serialised++;
            Assert.IsTrue(seen.Add(r.item.serial),
                "Serial " + r.item.serial + " was issued twice. The mint counter is not atomic.");
        }

        // Pity guarantees an RGB by open 101, so 400 opens must produce serials.
        Assert.Greater(serialised, 0, "400 opens produced no serialized item at all.");
        // Sequential from 1 with no gaps.
        for (int s = 1; s <= serialised; s++)
            Assert.IsTrue(seen.Contains(s), "Serial " + s + " is missing — the counter skipped.");
    }

    [Test]
    public void Spec155_SoldOutDoesNotCharge()
    {
        SkinDesign design = MakeDesign("tiny", 1);
        SacredDefinition sacred = MakeSacred("ember", CurrencyKind.Embers, 100, design);
        LocalBackend backend = MakeBackend(sacred);

        // DETERMINISM: only a SERIALIZED outcome consumes the single edition, and
        // that is a 5% roll — so funding must not be the binding constraint.
        // Granting exactly 100 opens' worth would leave a 0.95^100 = 0.59% chance
        // of running out of Embers before ever drawing one, and pity does not
        // rescue it because pity first fires on open 101. Fund far past the pity
        // threshold and retirement is guaranteed, not probable.
        backend.DevGrantCurrency(100 * 1000, 0, 0);   // 1000 opens

        bool retired = false;
        for (int i = 0; i < 400; i++)
        {
            SacredResult r = backend.OpenSacred("tutut", "ember").GetAwaiter().GetResult();
            Assert.IsFalse(r.insufficientFunds, "Funding must not be what ends this loop.");
            if (r.soldOut) { retired = true; break; }
        }
        Assert.IsTrue(retired,
            "The cap-1 design must retire within 400 opens — pity alone guarantees a " +
            "serialized outcome by open " + DropTable.RgbPityThreshold + ".");

        PlayerProfile before = backend.LoadProfile("tutut").GetAwaiter().GetResult();
        int embersBefore = before.embers;

        SacredResult sold = backend.OpenSacred("tutut", "ember").GetAwaiter().GetResult();
        Assert.IsTrue(sold.soldOut, "Every design retired, so the Sacred must report SOLD OUT.");
        Assert.IsFalse(sold.success);

        PlayerProfile after = backend.LoadProfile("tutut").GetAwaiter().GetResult();
        Assert.AreEqual(embersBefore, after.embers, "A sold-out Sacred must NOT charge.");
    }

    [Test]
    public void Spec155_EditionCapIsNeverExceeded()
    {
        SkinDesign design = MakeDesign("capped", 3);

        // A ZERO-PRICE EMBERS Sacred, not a Free one. This test used
        // CurrencyKind.Free only because that charged nothing -- and once the free
        // daily gate landed, the second of these 600 opens started coming back on
        // cooldown, so the loop ended after ONE open and the assertion passed with
        // highest = 1 without ever approaching the cap it exists to check. A test
        // that stops passing for its own reason is worse than one that fails.
        SacredDefinition sacred = MakeSacred("capped_free", CurrencyKind.Embers, 0, design);
        LocalBackend backend = MakeBackend(sacred);

        int highest = 0, opens = 0;
        for (int i = 0; i < 600; i++)
        {
            SacredResult r = backend.OpenSacred("tutut", "capped_free").GetAwaiter().GetResult();
            if (r.soldOut) break;
            Assert.IsFalse(r.onCooldown, "A cooldown must not be what ends this loop.");
            Assert.IsFalse(r.insufficientFunds, "Funding must not be what ends this loop.");
            if (r.success) opens++;
            if (r.item != null && r.item.serial > highest) highest = r.item.serial;
        }

        Assert.GreaterOrEqual(opens, 3,
            $"Only {opens} open(s) succeeded, so the cap was never actually reached and this " +
            "assertion would pass on a backend that mints nothing at all.");
        Assert.LessOrEqual(highest, 3, "A design must retire forever at its edition cap.");
    }

    [Test]
    public void Spec184_InsufficientFundsMintsNothing()
    {
        SkinDesign design = MakeDesign("pricey", 100);
        SacredDefinition sacred = MakeSacred("premium", CurrencyKind.SoulCrystals, 40, design);
        LocalBackend backend = MakeBackend(sacred);   // zero currency

        SacredResult r = backend.OpenSacred("tutut", "premium").GetAwaiter().GetResult();
        Assert.IsFalse(r.success);
        Assert.IsTrue(r.insufficientFunds);
        Assert.IsNull(r.item, "A failed open must not mint an item.");

        PlayerProfile p = backend.LoadProfile("tutut").GetAwaiter().GetResult();
        Assert.AreEqual(0, p.inventory.Count);
    }

    [Test]
    public void Spec159_OriginalOwnerNeverChangesAcrossTrades()
    {
        SkinDesign design = MakeDesign("tradeable", 100);
        SacredDefinition sacred = MakeSacred("free3", CurrencyKind.Free, 0, design);
        LocalBackend backend = MakeBackend(sacred);

        SacredResult r = backend.OpenSacred("tutut", "free3").GetAwaiter().GetResult();
        Assert.IsTrue(r.success);
        string itemId = r.item.itemId;
        Assert.IsNotEmpty(itemId, "Every item needs a stable globally-unique id from day one (§15.9).");

        TradeOffer offer = new TradeOffer
        {
            fromUserId = "tutut",
            toUserId = "friend",
            fromItemIds = new[] { itemId }
        };
        Assert.IsTrue(backend.ExecuteTrade(offer).GetAwaiter().GetResult());

        PlayerProfile p = backend.LoadProfile("tutut").GetAwaiter().GetResult();
        SkinInstance item = p.inventory.Find(i => i.itemId == itemId);
        Assert.AreEqual("friend", item.currentOwnerId);
        Assert.AreEqual("tutut", item.originalOwnerUserId, "The OG badge must never change.");
        Assert.Contains("tutut", item.previousOwnerIds);
    }

    [Test]
    public void Spec157_TrackerIncrementsAreServerClamped()
    {
        SkinDesign design = MakeDesign("tracked", 100);
        SacredDefinition sacred = MakeSacred("free4", CurrencyKind.Free, 0, design);
        LocalBackend backend = MakeBackend(sacred);

        SacredResult r = backend.OpenSacred("tutut", "free4").GetAwaiter().GetResult();
        backend.IncrementTracker(r.item.itemId, 999999).GetAwaiter().GetResult();

        PlayerProfile p = backend.LoadProfile("tutut").GetAwaiter().GetResult();
        SkinInstance item = p.inventory.Find(i => i.itemId == r.item.itemId);
        Assert.AreEqual(LocalBackend.MaxTrackerBatch, item.trackerKills,
            "A client claiming 999,999 kills in one batch must be clamped, not believed.");
    }

    [Test]
    public void Spec15122_EntitlementsAreNotAClientBool()
    {
        LocalBackend backend = MakeBackend();
        Assert.IsFalse(backend.HasEntitlement("tutut", "inquisitor_rose").GetAwaiter().GetResult(),
            "Locked admin-only content must default to NOT owned.");
        backend.GrantEntitlement("inquisitor_rose");
        Assert.IsTrue(backend.HasEntitlement("tutut", "inquisitor_rose").GetAwaiter().GetResult());
    }

    [Test]
    public void Spec183_CompositionRootDefaultsToLocalBackend()
    {
        Backend.Set(null);
        Assert.IsInstanceOf<LocalBackend>(Backend.Current,
            "Phase 10 swaps this one line; until then the default must be LocalBackend.");
    }

    [Test]
    public void FreeSacredsAreOncePerDayNotUnlimited()
    {
        // Forwarded as a real bug and it was real: LocalBackend.Charge had
        // `case CurrencyKind.Free: return true;` with no cap, cooldown or counter
        // anywhere. A free Sacred could be opened in a loop forever, minting
        // unlimited serial-numbered items into the economy the whole of Part II
        // is built on top of.
        //
        // Spec line 1364 lists the Recruit Sacred as "free -- daily login + first
        // win of day", so the entitlement is DAILY. This checks the floor: a
        // second claim on the same UTC day is refused, and the record survives a
        // Clone (which is how callers receive the profile).
        var profile = new PlayerProfile { userId = "test" };

        Assert.IsFalse(profile.HasClaimedFreeToday("recruit_sacred"),
            "A fresh profile should not have claimed anything today.");

        profile.RecordFreeClaim("recruit_sacred");
        Assert.IsTrue(profile.HasClaimedFreeToday("recruit_sacred"),
            "The claim was not recorded, so the gate would never close.");

        // Different Sacreds are independent entitlements.
        Assert.IsFalse(profile.HasClaimedFreeToday("other_sacred"),
            "Claiming one free Sacred must not consume another.");

        var copy = profile.Clone();
        Assert.IsTrue(copy.HasClaimedFreeToday("recruit_sacred"),
            "Clone() dropped the claim record. Since Clone is what hands the " +
            "profile to callers, that would reset the gate on every read and the " +
            "exploit would survive the fix.");

        // Recording twice must not double up.
        profile.RecordFreeClaim("recruit_sacred");
        int matching = 0;
        foreach (var e in profile.freeSacredClaims)
            if (e.StartsWith("recruit_sacred=")) matching++;
        Assert.AreEqual(1, matching, "Duplicate claim rows for the same day.");
    }

    /// <summary>
    /// A cooldown must not report itself as poverty.
    ///
    /// <para>The daily gate above was correct and its only outward signal was
    /// wrong: a second claim came back as <c>insufficientFunds = true</c> with the
    /// message "Not enough Free". The player was told they lacked a currency when
    /// the actual condition was a timer that resets at UTC midnight — which sends
    /// them looking for money that would not have helped. A wrong reason is worse
    /// than a bare failure.</para>
    /// </summary>
    [Test]
    public async Task FreeSacredCooldownIsReportedAsCooldownNotAsPoverty()
    {
        SkinDesign design = MakeDesign("cool_design", 100);
        SacredDefinition sacred = MakeSacred("cool_free", CurrencyKind.Free, 0, design);

        LocalBackend backend = MakeBackend(sacred);

        SacredResult first = await backend.OpenSacred("u", "cool_free");
        Assert.IsTrue(first.success, "The first free claim of the day must succeed.");

        SacredResult second = await backend.OpenSacred("u", "cool_free");
        Assert.IsFalse(second.success, "A second free claim on the same day must be refused.");
        Assert.IsTrue(second.onCooldown,
            "The refusal did not set onCooldown, so a caller cannot tell a timer from a price.");
        Assert.IsFalse(second.insufficientFunds,
            "A free Sacred on cooldown reported insufficientFunds -- the UI would say the " +
            "player cannot afford something that costs nothing.");
        StringAssert.DoesNotContain("Not enough", second.message,
            "The message still blames funding: " + second.message);
    }
}
