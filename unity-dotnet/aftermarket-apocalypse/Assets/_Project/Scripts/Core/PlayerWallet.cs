using UnityEngine;

/// <summary>
/// The PERSISTENT currencies (spec §15.1). These survive death, match end and
/// quitting — that is the whole point: "a chase economy cannot run on a currency
/// that evaporates when you die."
///
/// ★ Names are final (spec §15.1). The retired names Credits, Cash and
/// Ash Shards do not appear anywhere in this project, including the PlayerPrefs
/// keys.
///
/// <para><b>★ THERE WERE TWO WALLETS, AND ONLY ONE OF THEM COULD BUY ANYTHING.</b>
/// This class used to hold Embers, Soul Crystals and Shards in <c>PlayerPrefs</c>,
/// while <c>LocalBackend</c> held the same three on <see cref="PlayerProfile"/>
/// and the store spent from THAT. So a run banked its earnings into a wallet no
/// shop could see: you could die on round 20, watch "+1,250 EMBERS BANKED", open
/// the store, and read ◆ 0 — with every Sacred refusing, forever, and nothing
/// logged. Both stores were internally consistent and both persisted correctly,
/// which is exactly why it survived: neither was broken, they were just two.</para>
///
/// <para>The header of this file predicted it — "PlayerPrefs is the Phase 1
/// placeholder. Phase 2 moves reads/writes behind IBackend (spec §18.3)". Phase 2
/// shipped <c>IBackend</c> with currency on the profile and left this one in
/// place, so the placeholder became a second source of truth instead of being
/// replaced by one.</para>
///
/// <para><b>Currency now lives on the profile and only there.</b> The three
/// <c>Add*</c> methods are a thin façade over
/// <see cref="IBackend.CreditCurrency"/>; there are no currency getters here any
/// more, because a cached copy is how you get two wallets again. Anything that
/// needs to display a balance loads the profile, as <c>StoreScreen</c> does.
/// <c>BestRound</c> and <c>Extractions</c> stay in PlayerPrefs: they are local
/// stats, not currency, and nothing spends them.</para>
/// </summary>
public static class PlayerWallet
{
    private const string KEY_BEST_ROUND    = "BestRound";
    private const string KEY_EXTRACTIONS   = "Extractions";

    /// <summary>
    /// Who the earnings belong to. One id while the backend is local; Phase 10
    /// replaces it with the authenticated one.
    /// </summary>
    public const string UserId = "local";

    public static int BestRound
    {
        get { return PlayerPrefs.GetInt(KEY_BEST_ROUND, 0); }
    }

    public static int Extractions
    {
        get { return PlayerPrefs.GetInt(KEY_EXTRACTIONS, 0); }
    }

    /// <summary>◆ Embers — earned by playing. Buys F2P Sacreds.</summary>
    public static void AddEmbers(int amount)
    {
        if (amount <= 0) return;
        Backend.Current.CreditCurrency(UserId, amount, 0, 0);
    }

    /// <summary>✦ Soul Crystals — bought with real money AND dropped for surviving
    /// past round 10. Both paths matter: the earned path is what stops the top
    /// tier feeling purely paywalled (spec §15.1).</summary>
    public static void AddSoulCrystals(int amount)
    {
        if (amount <= 0) return;
        Backend.Current.CreditCurrency(UserId, 0, amount, 0);
    }

    /// <summary>Shards — from scrapping duplicates. Feeds trade-ups.</summary>
    public static void AddShards(int amount)
    {
        if (amount <= 0) return;
        Backend.Current.CreditCurrency(UserId, 0, 0, amount);
    }

    // The TrySpend* methods are gone with the PlayerPrefs wallet. Nothing called
    // them — spending already went through the backend's own charge path in
    // LocalBackend.OpenSacred — and leaving a spend route that debited a
    // different store from the one the store charges would have been the same bug
    // pointing the other way. Trade-ups (§15.10) will spend Shards through
    // IBackend when they exist.

    public static void RecordRoundReached(int round)
    {
        if (round > BestRound)
        {
            PlayerPrefs.SetInt(KEY_BEST_ROUND, round);
            PlayerPrefs.Save();
        }
    }

    public static void RecordExtraction()
    {
        PlayerPrefs.SetInt(KEY_EXTRACTIONS, Extractions + 1);
        PlayerPrefs.Save();
    }

    /// <summary>
    /// End-of-run banking (spec §15.1). Spec §13.11: the old build reloaded the
    /// scene on death and recorded nothing at all. Every run now pays out.
    ///   embers = (highestRound x 50) + (totalKills x 5) + (firstWinOfDay ? 200 : 0)
    /// </summary>
    public static int ComputeRunEmbers(int highestRound, int totalKills, bool firstWinOfDay)
    {
        return (highestRound * 50) + (totalKills * 5) + (firstWinOfDay ? 200 : 0);
    }

    public static int BankRun(int highestRound, int totalKills, bool firstWinOfDay)
    {
        int earned = ComputeRunEmbers(highestRound, totalKills, firstWinOfDay);
        AddEmbers(earned);
        RecordRoundReached(highestRound);
        return earned;
    }
}
