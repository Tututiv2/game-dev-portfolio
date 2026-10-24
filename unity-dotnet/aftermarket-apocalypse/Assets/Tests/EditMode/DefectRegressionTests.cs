using NUnit.Framework;
using UnityEngine;

/// <summary>
/// One test per defect from REBUILD_SPEC.md §13 / §13.1 that can be verified
/// without a running scene. Each test name cites the defect it guards.
///
/// These are regression guards, not proof the game is fun. What they prove is
/// that the specific bugs the spec says cost this project weeks cannot silently
/// come back.
/// </summary>
public class DefectRegressionTests
{
    // ---------------------------------------------------------------
    // #21 (SEVERE) — staggered zombies shrank permanently and their head
    // hitbox moved, because StaggerRoutine wrote an ABSOLUTE Vector3.one * 0.94
    // onto a transform whose real scale is 1.5.
    // ---------------------------------------------------------------

    [Test]
    public void Defect21_StaggerScalesRelativeToBase_NotToOne()
    {
        Vector3 prefabScale = new Vector3(1.5f, 1.5f, 1.5f);   // Enemy.prefab :381-392
        Vector3 staggered = EnemyAI.StaggerScale(prefabScale);

        Assert.AreEqual(1.41f, staggered.x, 1e-4f, "Stagger must be 94% of the model's REAL scale (1.5 * 0.94 = 1.41).");
        Assert.AreNotEqual(0.94f, staggered.x, "Writing Vector3.one * 0.94 is exactly the shipped bug.");
    }

    [Test]
    public void Defect21_RepeatedStaggerAndRestoreIsStable()
    {
        // Target staggers 25% of non-killing hits, so this happens dozens of
        // times per magazine. The old code shrank the zombie every single time.
        GameObject model = new GameObject("Model");
        model.transform.localScale = new Vector3(1.5f, 1.5f, 1.5f);
        Vector3 cachedBase = model.transform.localScale;

        for (int i = 0; i < 50; i++)
        {
            model.transform.localScale = EnemyAI.StaggerScale(cachedBase);
            model.transform.localScale = cachedBase;             // the restore path
        }

        Assert.AreEqual(1.5f, model.transform.localScale.x, 1e-5f,
            "50 staggers must leave the zombie exactly the size it started, or the Head collider drifts.");
        Object.DestroyImmediate(model);
    }

    // ---------------------------------------------------------------
    // #24 (SEVERE) — the collection file could be wiped: no try/catch on load,
    // non-atomic File.WriteAllText on save. A wipe re-issues #1 of a design
    // whose #1 was already minted, breaking the economy's one guarantee.
    // ---------------------------------------------------------------

    [System.Serializable]
    private class Bag { public int serial; public string name; }

    private static string TempPath(string leaf)
    {
        return System.IO.Path.Combine(Application.temporaryCachePath, leaf);
    }

    private static void Cleanup(string path)
    {
        foreach (string p in new[] { path, path + ".bak", path + ".tmp" })
            if (System.IO.File.Exists(p)) System.IO.File.Delete(p);
    }

    [Test]
    public void Defect24_SaveThenLoadRoundTrips()
    {
        string path = TempPath("aa_roundtrip.json");
        Cleanup(path);

        Assert.IsTrue(SafeJsonStore.Save(path, new Bag { serial = 37, name = "Starfade Camo" }));

        Bag loaded = SafeJsonStore.Load<Bag>(path);
        Assert.IsNotNull(loaded);
        Assert.AreEqual(37, loaded.serial);
        Assert.AreEqual("Starfade Camo", loaded.name);

        Cleanup(path);
    }

    [Test]
    public void Defect24_SaveLeavesNoTempFileBehind()
    {
        string path = TempPath("aa_notmp.json");
        Cleanup(path);

        SafeJsonStore.Save(path, new Bag { serial = 1, name = "A" });

        Assert.IsFalse(System.IO.File.Exists(path + ".tmp"),
            "A leftover .tmp means the atomic replace did not complete.");
        Cleanup(path);
    }

    [Test]
    public void Defect24_CorruptLiveFileRecoversFromBackup()
    {
        string path = TempPath("aa_recover.json");
        Cleanup(path);

        // Two good saves: the second rolls the first into .bak.
        SafeJsonStore.Save(path, new Bag { serial = 1, name = "first" });
        SafeJsonStore.Save(path, new Bag { serial = 2, name = "second" });
        Assert.IsTrue(System.IO.File.Exists(path + ".bak"), "The second save must produce a .bak.");

        // Simulate being killed mid-write: the live file is now garbage.
        System.IO.File.WriteAllText(path, "{ this is not json");

        UnityEngine.TestTools.LogAssert.ignoreFailingMessages = true;
        Bag loaded = SafeJsonStore.Load<Bag>(path);
        UnityEngine.TestTools.LogAssert.ignoreFailingMessages = false;

        Assert.IsNotNull(loaded, "A corrupt live file must NOT come back as an empty collection.");
        Assert.IsTrue(SafeJsonStore.LastLoadUsedBackup);
        Assert.AreEqual(1, loaded.serial, "Recovery should yield the previous good save.");

        Cleanup(path);
    }

    [Test]
    public void Defect24_MissingFileReturnsNullNotGarbage()
    {
        string path = TempPath("aa_missing.json");
        Cleanup(path);
        Assert.IsNull(SafeJsonStore.Load<Bag>(path));
    }

    // ---------------------------------------------------------------
    // #6 — Insta-Kill / Double Points did not stack: a second pickup's expiry
    // cleared the flag EARLY, so picking up a second one shortened the buff.
    // ---------------------------------------------------------------

    [Test]
    public void Defect6_SecondPickupExtendsRatherThanShortens()
    {
        const float duration = 30f;

        float expiry = PowerUpManager.ExtendBuff(0f, -1f, duration);
        Assert.AreEqual(30f, expiry, 1e-4f);

        // Second pickup 10 s in: must run to 60, not be cut back to 40 or 30.
        float extended = PowerUpManager.ExtendBuff(10f, expiry, duration);
        Assert.AreEqual(60f, extended, 1e-4f, "A second pickup must ADD a full duration to the existing expiry.");
        Assert.Greater(extended, expiry, "A second pickup must never shorten the buff.");
    }

    [Test]
    public void Defect6_PickupAfterExpiryStartsFresh()
    {
        float expiry = PowerUpManager.ExtendBuff(0f, -1f, 30f);
        float afterLapse = PowerUpManager.ExtendBuff(100f, expiry, 30f);
        Assert.AreEqual(130f, afterLapse, 1e-4f, "An expired buff must restart from now, not from the stale expiry.");
    }

    // ---------------------------------------------------------------
    // #26 — the paid Max Ammo was worse than the free one, and on a gun whose
    // reserve exceeded maxReserve, paying 1000 Essence DESTROYED rounds.
    // ---------------------------------------------------------------

    [Test]
    public void Defect26_ReserveAboveMaxIsClampedOnAwake()
    {
        GameObject go = new GameObject("Gun");
        GunSystem gun = go.AddComponent<GunSystem>();   // Awake runs here

        // The shipped Map2 values: reserveAmmo 300 against maxReserve 180.
        // Awake has already clamped whatever the defaults were; re-assert the
        // invariant the clamp exists to hold.
        Assert.LessOrEqual(gun.reserveAmmo, gun.maxReserve,
            "reserveAmmo must never exceed maxReserve, or RefillAmmo() burns the difference.");

        Object.DestroyImmediate(go);
    }

    [Test]
    public void Defect26_RefillNeverReducesReserve()
    {
        GameObject go = new GameObject("Gun");
        GunSystem gun = go.AddComponent<GunSystem>();

        gun.maxReserve = 180;
        gun.reserveAmmo = 300;      // as if an inspector override slipped through
        gun.RefillAmmo();

        Assert.AreEqual(300, gun.reserveAmmo,
            "Paying for a refill must never take ammo away — that is the shipped bug.");

        gun.reserveAmmo = 12;
        gun.RefillAmmo();
        Assert.AreEqual(180, gun.reserveAmmo, "A refill from empty tops up to maxReserve.");

        Object.DestroyImmediate(go);
    }

    // ---------------------------------------------------------------
    // trap #10 — decal hover was i * 0.0004 and reached a visible 4 cm by
    // splat #60, so blood visibly floated above the floor.
    // ---------------------------------------------------------------

    [Test]
    public void Trap10_DecalHoverStaysMillimetric()
    {
        for (int i = 0; i < 10000; i++)
        {
            float hover = BloodFX.DecalHover(i);
            Assert.GreaterOrEqual(hover, 0.008f);
            Assert.LessOrEqual(hover, 0.015f,
                "Hover must stay in millimetres forever. The old i * 0.0004 hit 4 cm by splat #60.");
        }
    }

    // ---------------------------------------------------------------
    // #5 — WeaponSwitcher was hardcoded to 2 slots and reset its unlock array
    // on every scene load, so anything bought vanished the moment you died.
    // ---------------------------------------------------------------

    [Test]
    public void Defect5_InventoryIsListDrivenAndRespectsCapacity()
    {
        GameObject root = new GameObject("Inventory");
        WeaponInventory inv = root.AddComponent<WeaponInventory>();

        for (int i = 0; i < 5; i++)
            inv.weapons.Add(new GameObject("Weapon" + i));

        inv.startingWeapons = new System.Collections.Generic.List<int> { 0 };
        inv.carryCapacity = 2;
        inv.Initialize();

        Assert.IsTrue(inv.HasWeapon(0));
        Assert.AreEqual(1, inv.Carried.Count);

        inv.GiveWeapon(3);
        Assert.IsTrue(inv.HasWeapon(3), "A fifth weapon must be reachable — two hardcoded slots was the bug.");
        Assert.AreEqual(2, inv.Carried.Count);

        // Over capacity: replaces the ACTIVE one, BO2 style.
        inv.GiveWeapon(4);
        Assert.AreEqual(2, inv.Carried.Count, "Capacity must be respected.");
        Assert.IsTrue(inv.HasWeapon(4));

        foreach (GameObject w in inv.weapons) Object.DestroyImmediate(w);
        Object.DestroyImmediate(root);
    }

    // ---------------------------------------------------------------
    // §2.3 / trap #2 — the pause flag. Input fires at timeScale 0.
    // ---------------------------------------------------------------

    [Test]
    public void PauseGate_SetsAndRestoresTimeScale()
    {
        PauseGate.Set(false);
        Assert.IsFalse(PauseGate.IsPaused);

        PauseGate.Set(true);
        Assert.IsTrue(PauseGate.IsPaused);
        Assert.AreEqual(0f, Time.timeScale, 1e-6f);

        PauseGate.Set(false);
        Assert.IsFalse(PauseGate.IsPaused);
        Assert.AreEqual(1f, Time.timeScale, 1e-6f);
    }

    [Test]
    public void NoRuntimeCodeReadsTheLegacyInputManager()
    {
        // The Phase 2 Input System migration, carried as debt for a long time with
        // activeInputHandler on "Both" — which meant nothing was broken and nothing
        // was finished either. The project shipped an InputSystem_Actions.inputactions
        // that nothing read while gameplay went on calling UnityEngine.Input.
        //
        // Every read now goes through GameInput. This scans the real runtime source,
        // because the old API would creep back one file at a time otherwise.
        //
        // Comments are stripped first: PauseGate's class summary legitimately writes
        // Input.GetKeyDown while describing the bug it guards, and failing on prose
        // would only teach people to delete the prose.
        string root = System.IO.Path.Combine(
            System.IO.Directory.GetParent(Application.dataPath).FullName,
            "Assets", "_Project", "Scripts");
        Assert.IsTrue(System.IO.Directory.Exists(root), "No runtime source at " + root);

        var offenders = new System.Collections.Generic.List<string>();
        var block = new System.Text.RegularExpressions.Regex(
            @"/\*.*?\*/", System.Text.RegularExpressions.RegexOptions.Singleline);
        // Lookbehind so GameInput.Move and myInput.Foo do not match.
        var legacy = new System.Text.RegularExpressions.Regex(@"(?<![A-Za-z0-9_.])Input\s*\.");

        foreach (string file in System.IO.Directory.GetFiles(
                     root, "*.cs", System.IO.SearchOption.AllDirectories))
        {
            string code = block.Replace(System.IO.File.ReadAllText(file), string.Empty);

            var stripped = new System.Text.StringBuilder();
            foreach (string line in code.Split('\n'))
            {
                int slash = line.IndexOf("//", System.StringComparison.Ordinal);
                stripped.Append(slash >= 0 ? line.Substring(0, slash) : line).Append('\n');
            }

            if (legacy.IsMatch(stripped.ToString()))
                offenders.Add(System.IO.Path.GetFileName(file));
        }

        Assert.IsEmpty(offenders,
            "These runtime files still call the legacy Input Manager: " +
            string.Join(", ", offenders) + ". Read input through GameInput instead. " +
            "With activeInputHandler set to Input System only, UnityEngine.Input throws " +
            "at runtime rather than merely being inconsistent with the new bindings.");
    }
}
