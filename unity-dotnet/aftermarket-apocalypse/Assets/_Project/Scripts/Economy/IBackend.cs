using System;
using System.Collections.Generic;
using System.Threading.Tasks;

/// <summary>
/// Spec §18.3 — "the single most important instruction here."
///
/// Ship LocalBackend first. The day this moves to the cloud must be a ONE-LINE
/// composition-root change (see <see cref="Backend"/>). This was skipped last
/// time: CollectionManager hard-coded the mint, the odds AND JSON persistence in
/// one class, and every economy feature ended up sitting behind untangling it.
///
/// §18.4 — SERVER-AUTHORITATIVE. The client owns input, visual settings and its
/// own animation. The server owns HP, Essence, Embers, Soul Crystals, Sacred
/// rolls, serial allocation, inventory mutations and tracker increments. Note
/// what this interface does NOT have: any method that lets a caller say what it
/// received. OpenSacred takes a userId and a sacredId and returns an outcome; a
/// client that says "I got #1 RGB" has nowhere to say it.
/// </summary>
public interface IBackend
{
    Task<PlayerProfile> LoadProfile(string userId);

    /// <summary>Server rolls, server allocates the serial. The reel animation in
    /// §15.8.1 is theatre played over an outcome that is already decided.</summary>
    Task<SacredResult> OpenSacred(string userId, string sacredId);

    /// <summary>
    /// Spec §15.4's bulk bonus: buy 10, get the 11th free. One transaction.
    ///
    /// <para><paramref name="paidCount"/> is how many the player is BUYING; how
    /// many they RECEIVE is <see cref="DropTable.TotalSacredsForPurchase"/> and is
    /// never a caller's arithmetic — a second copy of "10 + 1" is a second place
    /// the bonus can be got wrong.</para>
    ///
    /// <para><b>ATOMIC ON AFFORDABILITY.</b> The whole bundle is priced and
    /// checked before a single unit is debited. A half-charged bundle is the worst
    /// failure available here: the player is out currency with no way to tell how
    /// much, and the server has no record of what they were promised.</para>
    ///
    /// <para>The returned array is one entry per DELIVERED open, in the order they
    /// were minted, so pity (§15.4) is visible in the sequence. A bundle that
    /// could not start at all comes back as a SINGLE failed result carrying the
    /// reason — never an empty array, because an empty array and "nothing went
    /// wrong" look identical at the call site.</para>
    ///
    /// <para>A <see cref="CurrencyKind.Free"/> Sacred REFUSES. It is a
    /// once-per-UTC-day entitlement, not a price, so "buy ten of them" has no
    /// meaning — and silently opening one instead would be the store deciding what
    /// the player bought.</para>
    /// </summary>
    Task<SacredResult[]> OpenSacredBulk(string userId, string sacredId, int paidCount);

    Task<bool> ExecuteTrade(TradeOffer offer);

    /// <summary>Spec §15.7: tracker kills increment client-side, batch-synced,
    /// and are SERVER-CLAMPED.</summary>
    Task IncrementTracker(string itemId, int kills);

    /// <summary>Spec §15.12.2: entitlements are checked server-side, never with a
    /// client bool. Admin-only themes are gated through here.</summary>
    Task<bool> HasEntitlement(string userId, string entitlementId);

    /// <summary>
    /// Pay a run's earnings into the profile — §15.1's "playing accrues
    /// persistent currency" and §15.10's first-win-of-day bonus.
    ///
    /// <para><b>This exists because there were TWO wallets.</b> Currency lives on
    /// <see cref="PlayerProfile"/> and the store spends from it, but
    /// <c>PlayerWallet</c> was banking every run into <c>PlayerPrefs</c> instead —
    /// a Phase 1 placeholder its own comment said Phase 2 would retire, and never
    /// did. Both stores worked, both persisted, and neither ever saw the other:
    /// you could bank thousands of Embers and the store would still read zero,
    /// with no error anywhere. Crediting has to go through the backend for the
    /// same reason spending does.</para>
    ///
    /// <para>Negative amounts are rejected rather than clamped — a negative credit
    /// is a caller bug, and silently treating it as zero hides it.</para>
    /// </summary>
    Task CreditCurrency(string userId, int embers, int soulCrystals, int shards);
}

/// <summary>Everything the client is allowed to know about a player.</summary>
[Serializable]
public class PlayerProfile
{
    public string userId;
    public int embers;
    public int soulCrystals;
    public int shards;
    public int opensSinceLastRgb;
    public int premiumOpensSinceRainbow;
    public List<SkinInstance> inventory = new List<SkinInstance>();

    /// <summary>
    /// "sacredId=yyyy-MM-dd" for each free Sacred already claimed today.
    ///
    /// A plain list of strings rather than a dictionary because this is
    /// JsonUtility-serialized by SafeJsonStore, and JsonUtility does not serialize
    /// Dictionary at all -- it would silently persist as empty and the daily gate
    /// would reset on every load, which is the same exploit with extra steps.
    /// </summary>
    public List<string> freeSacredClaims = new List<string>();

    /// <summary>Detached copy — see SkinInstance.Clone. A profile handed to a
    /// caller is a snapshot, never the server's record.</summary>
    /// <summary>UTC, so the reset does not move with the player's timezone or with
    /// a machine clock nudged forward an hour.</summary>
    public static string TodayStamp()
    {
        return System.DateTime.UtcNow.ToString("yyyy-MM-dd");
    }

    public bool HasClaimedFreeToday(string sacredId)
    {
        return freeSacredClaims.Contains(sacredId + "=" + TodayStamp());
    }

    public void RecordFreeClaim(string sacredId)
    {
        string today = TodayStamp();
        // Yesterday's rows are dead weight; drop them so the list cannot grow
        // without bound across a long-lived profile.
        freeSacredClaims.RemoveAll(e => !e.EndsWith("=" + today));
        string key = sacredId + "=" + today;
        if (!freeSacredClaims.Contains(key)) freeSacredClaims.Add(key);
    }

    public PlayerProfile Clone()
    {
        PlayerProfile copy = new PlayerProfile
        {
            userId = userId,
            embers = embers,
            soulCrystals = soulCrystals,
            shards = shards,
            opensSinceLastRgb = opensSinceLastRgb,
            premiumOpensSinceRainbow = premiumOpensSinceRainbow,
            inventory = new List<SkinInstance>(inventory.Count),
            // Carry the daily claims. Without this the gate evaporates on every
            // Clone() -- and Clone is what hands the profile to callers -- so a
            // copy would look like it had claimed nothing today.
            freeSacredClaims = new List<string>(freeSacredClaims)
        };
        foreach (SkinInstance i in inventory) copy.inventory.Add(i.Clone());
        return copy;
    }
}

/// <summary>The outcome of one Sacred open.</summary>
[Serializable]
public class SacredResult
{
    public bool success;

    /// <summary>Spec §15.5: when every design is retired the Sacred reports SOLD
    /// OUT and DOES NOT CHARGE.</summary>
    public bool soldOut;

    public bool insufficientFunds;

    /// <summary>
    /// The Sacred was affordable but is on cooldown — the free daily has already
    /// been claimed today.
    ///
    /// <para>Without this the only signal a caller got was
    /// <see cref="insufficientFunds"/>, so a second free claim reported "Not
    /// enough Free": the UI told the player they lacked a currency when the real
    /// condition was a timer that resets at UTC midnight. A wrong reason is worse
    /// than a bare failure, because it sends the player looking for money.</para>
    /// </summary>
    public bool onCooldown;

    public string message;

    /// <summary>Null when the open failed.</summary>
    public SkinInstance item;

    public static SacredResult Fail(string why)
    {
        return new SacredResult { success = false, message = why };
    }
}

[Serializable]
public class TradeOffer
{
    public string fromUserId;
    public string toUserId;
    public string[] fromItemIds = new string[0];
    public string[] toItemIds = new string[0];
}

/// <summary>
/// THE COMPOSITION ROOT (spec §18.3). Swapping to PlayFab is the one line below.
/// Nothing else in the game may construct a backend.
/// </summary>
public static class Backend
{
    private static IBackend current;

    public static IBackend Current
    {
        get
        {
            if (current == null) current = new LocalBackend();   // <-- the one line
            return current;
        }
    }

    /// <summary>For tests and for Phase 10's swap.</summary>
    public static void Set(IBackend backend)
    {
        current = backend;
    }

    [UnityEngine.RuntimeInitializeOnLoadMethod(UnityEngine.RuntimeInitializeLoadType.SubsystemRegistration)]
    private static void Reset()
    {
        current = null;
    }
}
