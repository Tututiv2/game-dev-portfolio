using System.Reflection;
using NUnit.Framework;

/// <summary>
/// ★ THE TWO-WALLET BUG. Spec §15.1: playing accrues persistent currency, and
/// §15.12.1: Sacreds are bought with it.
///
/// <para>Those two sentences were implemented against different stores.
/// <c>PlayerWallet</c> banked every run's Embers into <c>PlayerPrefs</c>;
/// <c>LocalBackend</c> kept Embers on <see cref="PlayerProfile"/> and the store
/// spent from there. Neither was broken on its own, both persisted, and nothing
/// ever compared them — so the entire Phase 8 economy would have shipped with a
/// shop that read ◆ 0 no matter how long you played.</para>
///
/// <para>No existing test could see it: <c>EconomyTests</c> granted currency
/// through the backend and spent it through the backend (consistent), and
/// <c>SpecNumbersTests</c> only checked <c>ComputeRunEmbers</c>'s arithmetic
/// (also consistent). The gap was between the two, which is exactly where an
/// integration this quiet lives.</para>
/// </summary>
public class WalletTests
{
    LocalBackend backend;

    [SetUp]
    public void SetUp()
    {
        backend = new LocalBackend();
        // Never touch the real collection.json from a test run.
        backend.UseInMemoryStore();
        Backend.Set(backend);
    }

    [TearDown]
    public void TearDown()
    {
        Backend.Set(null);
    }

    static int Embers(LocalBackend b)
    {
        return b.LoadProfile("local").Result.embers;
    }

    /// <summary>
    /// The one that matters: bank a run the way dying does, then read the balance
    /// the store reads.
    /// </summary>
    [Test]
    public void Wallet_PlayingBanksIntoTheWalletTheStoreActuallySpendsFrom()
    {
        Assert.AreEqual(0, Embers(backend), "precondition: a fresh profile starts empty");

        int earned = PlayerWallet.ComputeRunEmbers(12, 300, true);
        Assert.Greater(earned, 0, "precondition: this run should be worth something");

        PlayerWallet.AddEmbers(earned);

        Assert.AreEqual(earned, Embers(backend),
            "PlayerWallet banked " + earned + " Embers but the profile the store spends from " +
            "still reads " + Embers(backend) + ". These are two different wallets again — " +
            "the player earns into one and shops from the other, and nothing errors.");
    }

    [Test]
    public void Wallet_SoulCrystalsAndShardsLandInTheSameProfile()
    {
        PlayerWallet.AddSoulCrystals(7);
        PlayerWallet.AddShards(11);

        PlayerProfile p = backend.LoadProfile("local").Result;
        Assert.AreEqual(7, p.soulCrystals, "Soul Crystals earned in play did not reach the profile.");
        Assert.AreEqual(11, p.shards, "Shards did not reach the profile.");
    }

    /// <summary>
    /// Earnings must survive being spent against — the round trip, not just the
    /// credit. A credit that lands somewhere the charge path cannot see would
    /// pass the test above if both happened to read the same field by accident.
    /// </summary>
    [Test]
    public void Wallet_EarnedEmbersCanActuallyBuyASacred()
    {
        var ash = ScriptableObject_Ash();
        backend.SetCatalogue(new[] { ash });

        PlayerWallet.AddEmbers(ash.price);

        SacredResult r = backend.OpenSacred("local", ash.sacredId).Result;

        Assert.IsTrue(r.success,
            "a Sacred priced at " + ash.price + " refused after banking exactly that many " +
            "Embers. Reason flags: soldOut=" + r.soldOut + " insufficientFunds=" +
            r.insufficientFunds + " onCooldown=" + r.onCooldown + " msg=" + r.message);
        Assert.AreEqual(0, Embers(backend), "the purchase did not debit the same balance it checked.");
    }

    /// <summary>
    /// A negative credit is a caller bug. Clamping it to zero would hide the bug;
    /// applying it would be an unaudited spend route around the charge path.
    /// </summary>
    [Test]
    public void Wallet_ANegativeCreditIsRefusedRatherThanAppliedOrClamped()
    {
        PlayerWallet.AddEmbers(500);
        UnityEngine.TestTools.LogAssert.Expect(UnityEngine.LogType.Error,
            new System.Text.RegularExpressions.Regex("negative amount"));

        backend.CreditCurrency("local", -100, 0, 0).Wait();

        Assert.AreEqual(500, Embers(backend),
            "a negative credit changed the balance. Spending must go through the Sacred " +
            "charge path so it is audited in one place.");
    }

    /// <summary>
    /// Guards the fix at the source. If somebody reintroduces a currency getter
    /// backed by PlayerPrefs, the second wallet is back and every test above can
    /// still pass while the game misbehaves — because the tests exercise the
    /// façade, and the bug is a SECOND path that bypasses it.
    /// </summary>
    [Test]
    public void Wallet_PlayerWalletHoldsNoCurrencyOfItsOwn()
    {
        var t = typeof(PlayerWallet);
        foreach (string name in new[] { "Embers", "SoulCrystals", "Shards" })
        {
            PropertyInfo p = t.GetProperty(name, BindingFlags.Public | BindingFlags.NonPublic
                                                 | BindingFlags.Static);
            Assert.IsNull(p,
                "PlayerWallet." + name + " is back. Currency lives on PlayerProfile and only " +
                "there; a local copy here is how the game ended up with two wallets that " +
                "never spoke. Load the profile instead.");
        }

        string src = System.IO.File.ReadAllText(
            "Assets/_Project/Scripts/Core/PlayerWallet.cs");
        foreach (string key in new[] { "\"Embers\"", "\"SoulCrystals\"", "\"Shards\"" })
        {
            StringAssert.DoesNotContain(key, src,
                "PlayerWallet still names a PlayerPrefs key " + key + ". That key was the " +
                "second wallet.");
        }
    }

    static SacredDefinition ScriptableObject_Ash()
    {
        var design = UnityEngine.ScriptableObject.CreateInstance<SkinDesign>();
        design.designId = "test_design";
        design.editionCap = 250;
        design.floatMin = 0f;
        design.floatMax = 1f;

        var s = UnityEngine.ScriptableObject.CreateInstance<SacredDefinition>();
        s.sacredId = "ash_sacred";
        s.displayName = "Ash Sacred";
        s.currency = CurrencyKind.Embers;
        s.price = 500;
        s.designs = new[] { design };
        return s;
    }
}
