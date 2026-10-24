using NUnit.Framework;
using UnityEngine;

/// <summary>
/// Asserts the numbers in RoundRules / DamageMath / CamoProgression / PlayerWallet
/// against REBUILD_SPEC.md. These lock the spec's figures into something that
/// fails loudly if a "quick tune" quietly changes the design.
/// </summary>
public class SpecNumbersTests
{
    // ---- §4.1 damage and Essence ----

    [Test]
    public void Spec41_DoubleTapAppliesAfterHeadshotMultiplier()
    {
        // 10 base, x5 headshot, x2 Double Tap = 100
        float d = DamageMath.FinalDamage(10f, true, 5f, true, 2f, false);
        Assert.AreEqual(100f, d, 1e-4f);
    }

    [Test]
    public void Spec41_BaseGunIsFiveBodyShotsOrOneHeadshot()
    {
        const float zombieHealth = 50f;   // §5.3 default

        float body = DamageMath.FinalDamage(10f, false, 5f, false, 2f, false);
        Assert.AreEqual(10f, body, 1e-4f);
        Assert.AreEqual(5, Mathf.CeilToInt(zombieHealth / body), "A base gun is 5 body shots.");

        float head = DamageMath.FinalDamage(10f, true, 5f, false, 2f, false);
        Assert.GreaterOrEqual(head, zombieHealth, "A base gun is 1 headshot.");
    }

    [Test]
    public void Spec41_InstaKillOverridesEverything()
    {
        Assert.AreEqual(DamageMath.InstaKillDamage,
            DamageMath.FinalDamage(10f, false, 5f, false, 2f, true), 1e-4f);
    }

    [Test]
    public void Spec41_PointsTable()
    {
        Assert.AreEqual(5,   DamageMath.PointsForHit(true,  false, false), "Hit, target survives");
        Assert.AreEqual(90,  DamageMath.PointsForHit(true,  true,  false), "Body kill");
        Assert.AreEqual(130, DamageMath.PointsForHit(true,  true,  true),  "Headshot kill");
        Assert.AreEqual(0,   DamageMath.PointsForHit(false, false, false), "Hitting a corpse pays nothing");
        Assert.AreEqual(0,   DamageMath.PointsForHit(false, true,  true),  "A corpse cannot be re-killed for points");
    }

    [Test]
    public void Spec41_PackAPunch()
    {
        float damage = 10f;
        int mag = 30;
        int reserve = 180;
        DamageMath.ApplyPackAPunch(ref damage, ref mag, ref reserve);

        Assert.AreEqual(20f, damage, 1e-4f, "damage x2");
        Assert.AreEqual(45, mag, "magazine x1.5, 30 -> 45");
        Assert.AreEqual(360, reserve, "maxReserve x2, 180 -> 360");
    }

    // ---- §5.1 / §5.2 rounds ----

    [Test]
    public void Spec52_ZombiesPerRound()
    {
        Assert.AreEqual(5, RoundRules.ZombiesForRound(1));
        Assert.AreEqual(50, RoundRules.ZombiesForRound(10));
        Assert.AreEqual(125, RoundRules.ZombiesForRound(25));
    }

    [Test]
    public void Spec51_SpeedBands()
    {
        Assert.AreEqual(1.5f, RoundRules.SpeedForRound(1), 1e-4f);
        Assert.AreEqual(1.5f, RoundRules.SpeedForRound(4), 1e-4f);
        Assert.AreEqual(3.5f, RoundRules.SpeedForRound(5), 1e-4f);
        Assert.AreEqual(3.5f, RoundRules.SpeedForRound(9), 1e-4f);
        Assert.AreEqual(6.0f, RoundRules.SpeedForRound(10), 1e-4f);
        Assert.AreEqual(6.0f, RoundRules.SpeedForRound(99), 1e-4f);
    }

    [Test]
    public void Spec51_HealthCompoundsTenPercentAndCapsAtRound50()
    {
        Assert.AreEqual(1f, RoundRules.HealthMultiplierForRound(1), 1e-4f);
        Assert.AreEqual(1.1f, RoundRules.HealthMultiplierForRound(2), 1e-4f);

        float atCap = RoundRules.HealthMultiplierForRound(50);
        Assert.AreEqual(106.7f, atCap, 0.5f, "Spec §5.1 quotes x106.7 at the cap.");
        Assert.AreEqual(atCap, RoundRules.HealthMultiplierForRound(200), 1e-3f, "The cap must actually cap.");
        Assert.AreEqual(5335f, 50f * atCap, 30f, "Spec §5.1 quotes 5,335 HP at the cap.");
    }

    [Test]
    public void Defect1_ConcurrentCapExists()
    {
        // Spec §13.1 / §16: the old build had no cap at all.
        Assert.AreEqual(24, RoundRules.MaxConcurrentZombies);
        Assert.Less(RoundRules.MaxConcurrentZombies, RoundRules.ZombiesForRound(25),
            "The cap only means anything if it is below a late-round wave size.");
    }

    [Test]
    public void PartI_SpawnCadenceIsTheIntendedTwoSecondsNotTheScenesFive()
    {
        Assert.AreEqual(2f, RoundRules.TimeBetweenSpawns, 1e-4f);
    }

    [Test]
    public void Spec52_RoundNamesAreRomanToTwentyFive()
    {
        Assert.AreEqual("I", RoundRules.RoundName(1));
        Assert.AreEqual("X", RoundRules.RoundName(10));
        Assert.AreEqual("XXV", RoundRules.RoundName(25));
        Assert.AreEqual("26", RoundRules.RoundName(26));
    }

    [Test]
    public void Defect7_StandUpTimeHasExactlyOneValue()
    {
        // Spec §5.5: ZombieStabilizer 3.5 s vs EnemyAI 3.0 s. "Pick one value."
        Assert.AreEqual(3.0f, RoundRules.ZombieStandUpTime, 1e-4f);
    }

    // ---- §15.1 currencies ----

    [Test]
    public void Spec151_SoulCrystalChanceCurve()
    {
        Assert.AreEqual(0f, RoundRules.SoulCrystalChance(9), 1e-4f, "Nothing below round 10.");
        Assert.AreEqual(0.30f, RoundRules.SoulCrystalChance(10), 1e-4f, "30% at round 10.");
        Assert.AreEqual(0.33f, RoundRules.SoulCrystalChance(11), 1e-4f, "+3% per round.");

        // NOTE — a discrepancy inside the spec itself, resolved in favour of the
        // formula. §10.3 gives  min(0.3 + (finishedRound - 10) * 0.03, 0.85)  and
        // annotates it "caps at R28". It does not: at round 28 the raw value is
        // 0.3 + 18*0.03 = 0.84, and the clamp only bites from round 29
        // (0.3 + 19*0.03 = 0.87 -> 0.85). The formula is the normative part and
        // is what this implementation follows; the prose comment is off by one.
        Assert.AreEqual(0.84f, RoundRules.SoulCrystalChance(28), 1e-4f, "Round 28 is 0.84, not yet clamped.");
        Assert.AreEqual(0.85f, RoundRules.SoulCrystalChance(29), 1e-4f, "The clamp first bites at round 29.");
        Assert.AreEqual(0.85f, RoundRules.SoulCrystalChance(90), 1e-4f, "And stays capped.");
    }

    [Test]
    public void Spec151_SoulCrystalAmount()
    {
        Assert.AreEqual(1, RoundRules.SoulCrystalAmount(10, 1));
        Assert.AreEqual(2, RoundRules.SoulCrystalAmount(10, 2));
        Assert.AreEqual(2, RoundRules.SoulCrystalAmount(20, 1), "+1 per 20 rounds.");
        Assert.AreEqual(3, RoundRules.SoulCrystalAmount(40, 1));
    }

    [Test]
    public void Spec151_EmberFormula()
    {
        // embers = (highestRound x 50) + (totalKills x 5) + (firstWinOfDay ? 200 : 0)
        Assert.AreEqual(50 * 12 + 5 * 300, PlayerWallet.ComputeRunEmbers(12, 300, false));
        Assert.AreEqual(50 * 12 + 5 * 300 + 200, PlayerWallet.ComputeRunEmbers(12, 300, true));
        Assert.AreEqual(0, PlayerWallet.ComputeRunEmbers(0, 0, false));
    }

    // ---- §4.3 camos ----

    [Test]
    public void Spec43_FourteenCamosInSpecOrder()
    {
        Assert.AreEqual(14, CamoProgression.Count);
        Assert.AreEqual(14, CamoProgression.Names.Length);
        Assert.AreEqual("Dust", CamoProgression.Names[0]);
        Assert.AreEqual("Diamond", CamoProgression.Names[9]);
        Assert.AreEqual("Dark Matter", CamoProgression.Names[11]);
        Assert.AreEqual("Cosmic Aether", CamoProgression.Names[13]);
    }

    [Test]
    public void Spec43_Stage1Thresholds()
    {
        Assert.AreEqual(0, CamoProgression.Stage1UnlockCount(9));
        Assert.AreEqual(1, CamoProgression.Stage1UnlockCount(10));
        Assert.AreEqual(5, CamoProgression.Stage1UnlockCount(100));
        Assert.AreEqual(10, CamoProgression.Stage1UnlockCount(350));
        Assert.AreEqual(10, CamoProgression.Stage1UnlockCount(9999), "There are only 10 stage-1 camos.");
    }

    [Test]
    public void Spec43_StageGating()
    {
        Assert.IsTrue(CamoProgression.HeadshotsStillCount(349));
        Assert.IsFalse(CamoProgression.HeadshotsStillCount(350), "Headshots stop counting once stage 1 completes.");

        Assert.IsFalse(CamoProgression.StreaksStillCount(349), "Streaks do not count until stage 1 is done.");
        Assert.IsTrue(CamoProgression.StreaksStillCount(350));
    }

    [Test]
    public void Spec43_Stage2Thresholds()
    {
        Assert.AreEqual(0, CamoProgression.Stage2UnlockCount(4));
        Assert.AreEqual(1, CamoProgression.Stage2UnlockCount(5));
        Assert.AreEqual(3, CamoProgression.Stage2UnlockCount(15));
        Assert.AreEqual(10, CamoProgression.RapidKillsPerStreak);
        Assert.AreEqual(5f, CamoProgression.RapidKillWindow, 1e-4f);
    }

    // ---- §15.1 retired names must not reappear ----

    /// <summary>
    /// Spec §15.1 and §21: "Retired names — do not use: Credits (-> Embers),
    /// Cash (-> Soul Crystals), Ash Shards (-> Soul Crystals), Crate / Case /
    /// Loot Box (-> Sacred)." ★ Names are final.
    ///
    /// This scans the actual runtime source rather than asserting something
    /// trivially true, so a retired name reintroduced anywhere fails the build.
    /// Comments that quote the spec's own rename table are allowed through by
    /// the exemption below.
    ///
    /// ⚠ A THIRD CONTRADICTION INSIDE THE SPEC, resolved in favour of the ★.
    /// §18.3 writes the backend interface as
    ///     Task&lt;CaseResult&gt; OpenCase(string userId, string caseId);
    /// using the exact word §21 retires. §15.8 settles it with a ★ — "The cases
    /// are called SACREDS. Not crates, not cases, not boxes" — and the spec's own
    /// provenance rule (§0, Part I header) says that where sources disagree, the
    /// owner's ★ decisions win. §18.3's code block illustrates the SHAPE of the
    /// interface; the instruction is "IBackend from Phase 2", not a naming
    /// mandate. So the API is OpenSacred / SacredResult, and "Case" is banned.
    /// </summary>
    [Test]
    public void Spec21_AllFourRetiredNameGroupsAppearNowhereInRuntimeCode()
    {
        string scriptsRoot = System.IO.Path.Combine(Application.dataPath, "_Project/Scripts");
        Assert.IsTrue(System.IO.Directory.Exists(scriptsRoot), "Runtime scripts folder not found at " + scriptsRoot);

        // GROUP 1-3 — standalone words, matched on identifier boundaries so
        // ordinary English inside a string ("cashier") cannot trip it, while a
        // field, property or PlayerPrefs key named Cash or Credits does.
        string[] wordTokens = { "Credits", "Cash", "AshShards", "AshShard" };

        // GROUP 4 (Crate / Case / Loot Box) plus the two-word currency spelling —
        // matched as SUBSTRINGS, because the offenders are compounds. Boundary
        // matching would miss them: in "OpenCase" there is no word boundary
        // before "Case", so \bCase\b does not match it.
        //
        // ⚠ Bare "Crate" is deliberately NOT checked. §21 retires it in the
        // LOOT-BOX sense only, and spec §6 and §12.1 name the "Ammo Crate"
        // machine as legitimate content — AmmoCrate.cs must not be an offender.
        // Only the loot-box compounds are banned.
        string[] substringTokens =
        {
            "Ash Shard", "AshShard",
            "LootCrate", "Loot Crate",
            "LootBox", "Loot Box",
            "CaseResult", "OpenCase", "caseId", "CaseId",
        };

        var offenders = new System.Collections.Generic.List<string>();

        foreach (string file in System.IO.Directory.GetFiles(scriptsRoot, "*.cs", System.IO.SearchOption.AllDirectories))
        {
            string[] lines = System.IO.File.ReadAllLines(file);
            for (int i = 0; i < lines.Length; i++)
            {
                string line = lines[i];

                // Only CODE counts. The renames are deliberately documented in
                // PlayerWallet's and EnemySpawner's comments, and a comment
                // saying "Ash Shards is retired" is not a use of the name.
                string trimmed = line.TrimStart();
                if (trimmed.StartsWith("//") || trimmed.StartsWith("*") || trimmed.StartsWith("/*")) continue;

                foreach (string token in wordTokens)
                {
                    if (System.Text.RegularExpressions.Regex.IsMatch(
                            line, @"\b" + System.Text.RegularExpressions.Regex.Escape(token) + @"\b"))
                        offenders.Add(System.IO.Path.GetFileName(file) + ":" + (i + 1) +
                                      "  [" + token + "]  " + line.Trim());
                }

                foreach (string token in substringTokens)
                {
                    if (line.Contains(token))
                        offenders.Add(System.IO.Path.GetFileName(file) + ":" + (i + 1) +
                                      "  [" + token + "]  " + line.Trim());
                }
            }
        }

        Assert.IsEmpty(offenders,
            "Retired name(s) found. ★ Spec §21: Credits -> Embers, Cash -> Soul Crystals, " +
            "Ash Shards -> Soul Crystals, Crate/Case/Loot Box -> Sacred.\n" +
            string.Join("\n", offenders));
    }

    /// <summary>
    /// Guards the guard. If the scan silently stopped finding anything — wrong
    /// path, changed layout — every retired-name test would pass vacuously.
    /// </summary>
    [Test]
    public void Spec21_RetiredNameScanActuallyReadsSource()
    {
        string scriptsRoot = System.IO.Path.Combine(Application.dataPath, "_Project/Scripts");
        string[] files = System.IO.Directory.GetFiles(scriptsRoot, "*.cs", System.IO.SearchOption.AllDirectories);
        Assert.Greater(files.Length, 40, "The scan is reading far fewer files than this project has.");

        bool sawSacred = false;
        foreach (string f in files)
            if (System.IO.File.ReadAllText(f).Contains("OpenSacred")) { sawSacred = true; break; }
        Assert.IsTrue(sawSacred,
            "Expected to find the replacement name OpenSacred. If this fails the scan is " +
            "looking in the wrong place and the retired-name test proves nothing.");
    }
}
