using NUnit.Framework;
using UnityEngine;

/// <summary>Phase 2's non-economy infrastructure: §18.2 GameEvents, §18.6 pooling,
/// §18.1 ScriptableObject-driven tuning.</summary>
public class InfrastructureTests
{
    [SetUp]
    public void Setup()
    {
        GameEvents.ResetAll();
        RoundRules.ResetToDefaults();
    }

    [TearDown]
    public void Teardown()
    {
        GameEvents.ResetAll();
        RoundRules.ResetToDefaults();
    }

    // ---- §18.2 ----

    [Test]
    public void Spec182_EventsDeliverToSubscribers()
    {
        int got = -1;
        GameEvents.RoundStarted += r => got = r;
        GameEvents.RaiseRoundStarted(7);
        Assert.AreEqual(7, got);
    }

    [Test]
    public void Spec182_RaisingWithNoSubscribersIsSafe()
    {
        Assert.DoesNotThrow(() => GameEvents.RaiseMatchEnded());
        Assert.DoesNotThrow(() => GameEvents.RaiseZombieKilled(Vector3.zero, true));
    }

    [Test]
    public void Spec182_ResetDropsSubscribersSoTheyCannotLeakBetweenMatches()
    {
        int calls = 0;
        GameEvents.RoundStarted += _ => calls++;
        GameEvents.RaiseRoundStarted(1);
        Assert.AreEqual(1, calls);

        GameEvents.ResetAll();
        GameEvents.RaiseRoundStarted(2);
        Assert.AreEqual(1, calls,
            "A listener from a previous match must not still fire — that is how you get " +
            "MissingReferenceException from a system that should not be running.");
    }

    // ---- §18.6 ----

    [Test]
    public void Spec186_PoolReusesInsteadOfInstantiating()
    {
        GameObject prefab = new GameObject("PoolPrefab");
        ObjectPool pool = new ObjectPool(prefab, prewarm: 4);

        Assert.AreEqual(4, pool.IdleCount);
        Assert.AreEqual(4, pool.TotalCreated);

        GameObject a = pool.Get(Vector3.zero, Quaternion.identity);
        Assert.IsTrue(a.activeSelf);
        Assert.AreEqual(3, pool.IdleCount);
        Assert.AreEqual(4, pool.TotalCreated, "Taking from a warm pool must not instantiate.");

        pool.Release(a);
        Assert.IsFalse(a.activeSelf);
        Assert.AreEqual(4, pool.IdleCount);

        pool.Clear();
        Object.DestroyImmediate(prefab);
    }

    [Test]
    public void Spec186_PoolGrowsWhenExhausted()
    {
        GameObject prefab = new GameObject("PoolPrefab2");
        ObjectPool pool = new ObjectPool(prefab, prewarm: 1);

        pool.Get(Vector3.zero, Quaternion.identity);
        pool.Get(Vector3.zero, Quaternion.identity);   // exhausted -> must create

        Assert.AreEqual(2, pool.TotalCreated);
        pool.Clear();
        Object.DestroyImmediate(prefab);
    }

    [Test]
    public void Spec186_PoolDoesNotRetainUnbounded()
    {
        GameObject prefab = new GameObject("PoolPrefab3");
        ObjectPool pool = new ObjectPool(prefab, prewarm: 0, maxRetained: 2);

        GameObject a = pool.Get(Vector3.zero, Quaternion.identity);
        GameObject b = pool.Get(Vector3.zero, Quaternion.identity);
        GameObject c = pool.Get(Vector3.zero, Quaternion.identity);
        pool.Release(a);
        pool.Release(b);
        pool.Release(c);

        Assert.AreEqual(2, pool.IdleCount,
            "A pool that only ever grows is a memory leak wearing a performance-optimisation hat.");
        pool.Clear();
        Object.DestroyImmediate(prefab);
    }

    // ---- §18.1 ----

    [Test]
    public void Spec181_GameConfigOverridesTuningAndResetRestoresIt()
    {
        Assert.AreEqual(24, RoundRules.MaxConcurrentZombies);
        Assert.AreEqual(2f, RoundRules.TimeBetweenSpawns, 1e-4f);

        GameConfig cfg = ScriptableObject.CreateInstance<GameConfig>();
        cfg.maxConcurrentZombies = 12;
        cfg.timeBetweenSpawns = 1.25f;
        cfg.zombiesPerRoundMultiplier = 7;
        cfg.walkSpeed = 1.5f; cfg.runSpeed = 3.5f; cfg.sprintSpeed = 6f;
        cfg.healthGrowthPerRound = 1.1f; cfg.healthGrowthCapRound = 50; cfg.crawlerChance = 0.12f;
        cfg.Apply();

        Assert.AreEqual(12, RoundRules.MaxConcurrentZombies);
        Assert.AreEqual(1.25f, RoundRules.TimeBetweenSpawns, 1e-4f);
        Assert.AreEqual(70, RoundRules.ZombiesForRound(10));

        RoundRules.ResetToDefaults();
        Assert.AreEqual(24, RoundRules.MaxConcurrentZombies,
            "Config from one match must not leak into a scene that has none.");
        Assert.AreEqual(50, RoundRules.ZombiesForRound(10));

        Object.DestroyImmediate(cfg);
    }

    [Test]
    public void Spec181_WeaponDefinitionKeepsReserveWithinItsMaximum()
    {
        WeaponDefinition def = ScriptableObject.CreateInstance<WeaponDefinition>();
        def.weaponName = "M4";
        def.magazineSize = 30;
        def.maxReserve = 180;

        GameObject go = new GameObject("Gun");
        GunSystem gun = go.AddComponent<GunSystem>();
        gun.reserveAmmo = 300;          // the shipped Map2 divergence

        def.ApplyTo(gun);

        Assert.AreEqual(180, gun.maxReserve);
        Assert.LessOrEqual(gun.reserveAmmo, gun.maxReserve,
            "Applying a definition must restore the invariant, not import the bug.");

        Object.DestroyImmediate(go);
        Object.DestroyImmediate(def);
    }
}
