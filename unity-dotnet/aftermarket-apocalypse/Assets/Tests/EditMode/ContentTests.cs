using System.Collections.Generic;
using NUnit.Framework;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Spec §17 phase 2 delivers "ScriptableObject CONTENT", and §18.1 requires the
/// content to exist as .assets so adding a weapon or a skin never touches code.
///
/// A rule with nothing under it proves nothing — the first version of Phase 2
/// shipped the three C# type definitions and zero .asset files, and called that
/// done. These tests load the real assets off disk, so that cannot recur.
/// </summary>
public class ContentTests
{
    private const string Root = "Assets/_Project/Content";

    private static List<T> LoadAll<T>(string subfolder) where T : ScriptableObject
    {
        var list = new List<T>();
        string[] guids = AssetDatabase.FindAssets("t:" + typeof(T).Name, new[] { Root + "/" + subfolder });
        foreach (string g in guids)
        {
            T a = AssetDatabase.LoadAssetAtPath<T>(AssetDatabase.GUIDToAssetPath(g));
            if (a != null) list.Add(a);
        }
        return list;
    }

    [Test]
    public void Content_GameConfigExistsAndMatchesTheSpecNumbers()
    {
        GameConfig cfg = AssetDatabase.LoadAssetAtPath<GameConfig>(Root + "/GameConfig.asset");
        Assert.IsNotNull(cfg, "No GameConfig.asset. Run Tools > Aftermarket > Rebuild Phase 2 Content.");

        Assert.AreEqual(5, cfg.zombiesPerRoundMultiplier, "spec §5.2");
        Assert.AreEqual(24, cfg.maxConcurrentZombies, "spec §13.1 defect #1 / §16");
        Assert.AreEqual(2f, cfg.timeBetweenSpawns, 1e-4f, "the INTENT; the old scene shipped 5");
        Assert.AreEqual(0.04f, cfg.powerUpDropChance, 1e-4f, "spec §7: 4% of kills");
    }

    [Test]
    public void Content_WeaponsExist()
    {
        var weapons = LoadAll<WeaponDefinition>("Weapons");
        Assert.IsNotEmpty(weapons, "No WeaponDefinition assets authored.");
        Assert.IsNotNull(weapons.Find(w => w.weaponName == "Glock"));
        Assert.IsNotNull(weapons.Find(w => w.weaponName == "M4"));
    }

    [Test]
    public void Content_EveryWeaponHoldsTheReserveInvariant()
    {
        foreach (WeaponDefinition w in LoadAll<WeaponDefinition>("Weapons"))
        {
            Assert.GreaterOrEqual(w.maxReserve, w.magazineSize,
                w.name + ": a reserve below one magazine makes RefillAmmo meaningless.");
            Assert.Greater(w.fireRate, 0f, w.name + ": fireRate is SECONDS BETWEEN SHOTS, never 0.");
        }
    }

    [Test]
    public void Content_AllFivePerksAreAuthored()
    {
        var perks = LoadAll<PerkDefinition>("Perks");
        Assert.AreEqual(5, perks.Count, "Spec §3.3 lists exactly five perks in the current build.");

        foreach (PerkType t in System.Enum.GetValues(typeof(PerkType)))
            Assert.IsNotNull(perks.Find(p => p.perk == t), "No asset for perk " + t);
    }

    [Test]
    public void Content_PerkCostsMatchTheMapProgression()
    {
        var perks = LoadAll<PerkDefinition>("Perks");
        // Spec §12.1 / §12.2: Quick Revive 1500, Double Tap 2000, Stamin-Up 2000,
        // Juggernog 2500, Speed Cola 3000.
        Assert.AreEqual(1500, perks.Find(p => p.perk == PerkType.QuickRevive).defaultCost);
        Assert.AreEqual(2000, perks.Find(p => p.perk == PerkType.DoubleTap).defaultCost);
        Assert.AreEqual(2000, perks.Find(p => p.perk == PerkType.StaminUp).defaultCost);
        Assert.AreEqual(2500, perks.Find(p => p.perk == PerkType.Juggernog).defaultCost);
        Assert.AreEqual(3000, perks.Find(p => p.perk == PerkType.SpeedCola).defaultCost);
    }

    [Test]
    public void Content_GumsAndPowerUpsAreAuthored()
    {
        Assert.AreEqual(3, LoadAll<GumDefinition>("Gums").Count, "Spec §6 lists three gums.");
        Assert.AreEqual(4, LoadAll<PowerUpDefinition>("PowerUps").Count, "Spec §7 lists four power-ups.");
    }

    [Test]
    public void Content_SacredLadderMatchesSpec158()
    {
        var sacreds = LoadAll<SacredDefinition>("Sacreds");
        Assert.AreEqual(8, sacreds.Count, "Spec §15.8's ladder has eight Sacreds.");

        Assert.AreEqual(500, sacreds.Find(s => s.sacredId == "ash_sacred").price);
        Assert.AreEqual(2000, sacreds.Find(s => s.sacredId == "ember_sacred").price);
        Assert.AreEqual(12, sacreds.Find(s => s.sacredId == "phantom_sacred").price);
        Assert.AreEqual(40, sacreds.Find(s => s.sacredId == "starfade_sacred").price);
        Assert.AreEqual(25, sacreds.Find(s => s.sacredId == "midnight_sacred").price);

        Assert.AreEqual(CurrencyKind.Embers, sacreds.Find(s => s.sacredId == "ash_sacred").currency,
            "F2P Sacreds are bought with Embers ◆.");
        Assert.AreEqual(CurrencyKind.SoulCrystals, sacreds.Find(s => s.sacredId == "phantom_sacred").currency,
            "Premium Sacreds are bought with Soul Crystals ✦.");
    }

    [Test]
    public void Content_NoSacredIsPricedInEssence()
    {
        // ★ Spec §15.1's single most important correction: LootCrate used to cost
        // 1500 ESSENCE — in-match points that evaporate on death. "A chase economy
        // cannot run on a currency that evaporates when you die."
        foreach (SacredDefinition s in LoadAll<SacredDefinition>("Sacreds"))
        {
            Assert.That(s.currency, Is.EqualTo(CurrencyKind.Embers)
                                      .Or.EqualTo(CurrencyKind.SoulCrystals)
                                      .Or.EqualTo(CurrencyKind.Shards)
                                      .Or.EqualTo(CurrencyKind.Free),
                s.name + " must be priced in a PERSISTENT currency.");
        }
    }

    [Test]
    public void Content_EverySacredHasDesignsAndEveryDesignHasACap()
    {
        var designs = LoadAll<SkinDesign>("SkinDesigns");
        Assert.IsNotEmpty(designs, "No SkinDesign assets authored.");

        foreach (SkinDesign d in designs)
        {
            Assert.Greater(d.editionCap, 0, d.name + ": an edition cap of 0 can never mint.");
            Assert.LessOrEqual(d.floatMin, d.floatMax, d.name + ": inverted float range.");
            Assert.IsNotEmpty(d.designId, d.name + ": designId is baked into every minted item.");
        }

        foreach (SacredDefinition s in LoadAll<SacredDefinition>("Sacreds"))
            Assert.IsNotEmpty(s.designs, s.name + " has no designs, so every open reports SOLD OUT.");
    }

    [Test]
    public void Content_DesignIdsAreUnique()
    {
        // The designId keys the mint counter. Two designs sharing one would share
        // a serial sequence — a silent, unrecoverable economy corruption.
        var seen = new HashSet<string>();
        foreach (SkinDesign d in LoadAll<SkinDesign>("SkinDesigns"))
            Assert.IsTrue(seen.Add(d.designId), "Duplicate designId: " + d.designId);
    }

    [Test]
    public void Content_AuthoredSacredsDriveTheRealBackend()
    {
        // End to end on the actual assets, not hand-built fakes: the authored
        // ladder must be openable through IBackend.
        var sacreds = LoadAll<SacredDefinition>("Sacreds");
        SacredDefinition recruit = sacreds.Find(s => s.sacredId == "recruit_sacred");
        Assert.IsNotNull(recruit, "Recruit Sacred (the free daily) is missing.");

        LocalBackend backend = new LocalBackend();
        backend.UseInMemoryStore();
        backend.SetCatalogue(sacreds);

        SacredResult r = backend.OpenSacred("tutut", "recruit_sacred").GetAwaiter().GetResult();
        Assert.IsTrue(r.success, r.message);
        Assert.IsNotNull(r.item);
        Assert.IsNotEmpty(r.item.itemId);
        Assert.IsNotEmpty(r.item.designId);
    }
}
