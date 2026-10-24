using System.IO;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Authors Phase 2's ScriptableObject CONTENT (spec §17 phase 2, §18.1).
///
/// §18.1 is a rule about where content lives, and a rule with no content under it
/// proves nothing. This creates the real .asset files — the two weapons the old
/// build shipped, the five perks, the three gums, the four power-ups, the tuning
/// config, and the §15.8 Sacred ladder with designs behind it.
///
/// Idempotent: an existing asset is updated in place, never replaced, so the GUID
/// survives and anything already referencing it keeps working. Run again after
/// adding a field and the assets pick up the new default without losing edits to
/// other fields you made by hand.
///
/// Tools &gt; Aftermarket &gt; Rebuild Phase 2 Content
/// </summary>
public static class ContentBootstrap
{
    private const string Root = "Assets/_Project/Content";

    [MenuItem("Tools/Aftermarket/Rebuild Phase 2 Content")]
    public static void Rebuild()
    {
        EnsureFolders();

        // ---- tuning (spec §5.1, §5.2, §7) ----
        GameConfig cfg = Upsert<GameConfig>(Root + "/GameConfig.asset");
        cfg.zombiesPerRoundMultiplier = 5;
        cfg.maxConcurrentZombies = 24;
        cfg.timeBetweenSpawns = 2f;
        cfg.walkSpeed = 1.5f;
        cfg.runSpeed = 3.5f;
        cfg.sprintSpeed = 6f;
        cfg.healthGrowthPerRound = 1.1f;
        cfg.healthGrowthCapRound = 50;
        cfg.crawlerChance = 0.12f;
        cfg.powerUpDropChance = 0.04f;
        cfg.powerUpBuffDuration = 30f;
        cfg.powerUpOrbLifetime = 20f;
        EditorUtility.SetDirty(cfg);

        // ---- weapons (spec §4.1; the two the old build actually shipped) ----
        MakeWeapon("Glock", "Glock", damage: 10f, fireRate: 0.14f, mag: 15, reserve: 90,
                   auto: false, wallbuy: 500);
        MakeWeapon("M4", "M4", damage: 10f, fireRate: 0.1f, mag: 30, reserve: 180,
                   auto: true, wallbuy: 1200);

        // ---- perks (spec §3.3; costs from §12.1/§12.2) ----
        MakePerk(PerkType.QuickRevive, "Quick Revive", 1500, new Color(0.55f, 0.8f, 1f));
        MakePerk(PerkType.DoubleTap, "Double Tap", 2000, new Color(1f, 0.85f, 0.3f));
        MakePerk(PerkType.StaminUp, "Stamin-Up", 2000, new Color(1f, 0.55f, 0.15f));
        MakePerk(PerkType.Juggernog, "Juggernog", 2500, new Color(0.9f, 0.15f, 0.15f));
        MakePerk(PerkType.SpeedCola, "Speed Cola", 3000, new Color(0.3f, 0.85f, 0.35f));

        // ---- gobblegums (spec §6) ----
        MakeGum(GumType.MaxAmmo, "Max Ammo",
                "Refills every weapon you carry, holstered ones included.", 1f);
        MakeGum(GumType.InPlainSight, "In Plain Sight",
                "Zombies ignore you completely for the duration.", 10f);
        MakeGum(GumType.Perkaholic, "Perkaholic",
                "Grants every perk, up to your slot limit.", 0f);

        // ---- power-ups (spec §7) ----
        MakePowerUp(PowerUpType.MaxAmmo, "Max Ammo", new Color(0.2f, 0.6f, 1f), "MAX AMMO", 0);
        MakePowerUp(PowerUpType.InstaKill, "Insta-Kill", Color.red, "INSTA-KILL", 0);
        MakePowerUp(PowerUpType.DoublePoints, "Double Points", new Color(1f, 0.85f, 0.2f), "DOUBLE POINTS", 0);
        MakePowerUp(PowerUpType.Nuke, "Nuke", new Color(0.4f, 1f, 0.3f), "NUKE", DamageMath.PointsNuke);

        // ---- skin designs (spec §15.5) ----
        SkinDesign starfade = MakeDesign("starfade_camo", "Starfade Camo", "M4", 250, 0f, 0.6f);
        SkinDesign ashfall = MakeDesign("ashfall_ember", "Ashfall Ember", "M4", 500, 0f, 1f);
        SkinDesign midnight = MakeDesign("midnight_carnival", "Midnight Carnival", "Glock", 100, 0f, 0.38f);
        SkinDesign firstLight = MakeDesign("first_light", "First Light", "Glock", 25, 0f, 0.15f);

        SkinDesign[] all = { starfade, ashfall, midnight, firstLight };

        // ---- the Sacred ladder (spec §15.8) ----
        // ★ "The cases are called SACREDS. Not crates, not cases, not boxes."
        MakeSacred("recruit_sacred", "Recruit Sacred", CurrencyKind.Free, 0, false, all);
        MakeSacred("ash_sacred", "Ash Sacred", CurrencyKind.Embers, 500, false, all);
        MakeSacred("ember_sacred", "Ember Sacred", CurrencyKind.Embers, 2000, false, all);
        MakeSacred("phantom_sacred", "Phantom Sacred", CurrencyKind.SoulCrystals, 12, true, all);
        MakeSacred("starfade_sacred", "Starfade Sacred", CurrencyKind.SoulCrystals, 40, true, all);
        MakeSacred("founders_sacred", "Founder's Sacred", CurrencyKind.Free, 0, true, all);
        MakeSacred("midnight_sacred", "Midnight Sacred", CurrencyKind.SoulCrystals, 25, true, all);
        MakeSacred("rainbow_sacred", "Rainbow Sacred", CurrencyKind.Free, 0, true, all);

        AssetDatabase.SaveAssets();
        AssetDatabase.Refresh();
        Debug.Log("ContentBootstrap: Phase 2 content written under " + Root);
    }

    // ------------------------------------------------------------------

    private static void EnsureFolders()
    {
        foreach (string sub in new[] { "Weapons", "Perks", "Gums", "PowerUps", "SkinDesigns", "Sacreds" })
        {
            string path = Root + "/" + sub;
            if (!AssetDatabase.IsValidFolder(path))
            {
                Directory.CreateDirectory(path);
            }
        }
        AssetDatabase.Refresh();
    }

    /// <summary>Load-or-create. Never deletes, so GUIDs survive a re-run.</summary>
    private static T Upsert<T>(string path) where T : ScriptableObject
    {
        T existing = AssetDatabase.LoadAssetAtPath<T>(path);
        if (existing != null) return existing;
        T created = ScriptableObject.CreateInstance<T>();
        AssetDatabase.CreateAsset(created, path);
        return created;
    }

    private static void MakeWeapon(string id, string display, float damage, float fireRate,
                                   int mag, int reserve, bool auto, int wallbuy)
    {
        WeaponDefinition w = Upsert<WeaponDefinition>(Root + "/Weapons/Weapon_" + id + ".asset");
        w.weaponName = id;
        w.displayName = display;
        w.damage = damage;
        w.headshotMultiplier = 5f;
        w.range = 100f;
        w.impactForce = 2f;
        w.fireRate = fireRate;
        w.allowButtonHold = auto;
        w.sprintToFireDelay = 0.3f;
        w.recoilUp = 2f;
        w.recoilSide = 1f;
        w.magazineSize = mag;
        w.maxReserve = reserve;
        w.reloadTime = 1.5f;
        w.wallbuyCost = wallbuy;
        w.wallbuyAmmoCost = wallbuy / 2;
        EditorUtility.SetDirty(w);
    }

    private static void MakePerk(PerkType type, string display, int cost, Color color)
    {
        PerkDefinition p = Upsert<PerkDefinition>(Root + "/Perks/Perk_" + type + ".asset");
        p.perk = type;
        p.displayName = display;
        p.defaultCost = cost;
        p.machineColor = color;
        p.juggernogHealth = 250f;
        p.speedColaMultiplier = 0.5f;
        p.doubleTapDamage = 2f;
        p.doubleTapFireRate = 0.75f;
        p.staminUpMultiplier = 1.3f;
        EditorUtility.SetDirty(p);
    }

    private static void MakeGum(GumType type, string display, string description, float duration)
    {
        GumDefinition g = Upsert<GumDefinition>(Root + "/Gums/Gum_" + type + ".asset");
        g.gum = type;
        g.displayName = display;
        g.description = description;
        g.duration = duration;
        g.weight = 1;
        EditorUtility.SetDirty(g);
    }

    private static void MakePowerUp(PowerUpType type, string display, Color color,
                                    string announce, int flatEssence)
    {
        PowerUpDefinition p = Upsert<PowerUpDefinition>(Root + "/PowerUps/PowerUp_" + type + ".asset");
        p.type = type;
        p.displayName = display;
        p.color = color;
        p.announceText = announce;
        p.weight = 1;                       // spec §7: uniform 25% across four
        p.flatEssenceAward = flatEssence;
        EditorUtility.SetDirty(p);
    }

    private static SkinDesign MakeDesign(string id, string display, string weapon,
                                         int cap, float floatMin, float floatMax)
    {
        SkinDesign d = Upsert<SkinDesign>(Root + "/SkinDesigns/Design_" + id + ".asset");
        d.designId = id;
        d.displayName = display;
        d.weaponName = weapon;
        d.editionCap = cap;
        d.floatMin = floatMin;
        d.floatMax = floatMax;
        d.linkedCamoIndex = -1;
        EditorUtility.SetDirty(d);
        return d;
    }

    private static void MakeSacred(string id, string display, CurrencyKind currency,
                                   int price, bool premium, SkinDesign[] designs)
    {
        SacredDefinition s = Upsert<SacredDefinition>(Root + "/Sacreds/Sacred_" + id + ".asset");
        s.sacredId = id;
        s.displayName = display;
        s.currency = currency;
        s.price = price;
        s.isPremium = premium;
        // Spec §15.6 as literally written: premium 0.5, F2P 1.0. See the
        // contradiction recorded in CLAUDE.md — 0.5 skews DIRTIER, not cleaner.
        s.floatCurveExponent = premium ? 0.5f : 1.0f;
        s.designs = designs;
        EditorUtility.SetDirty(s);
    }
}
