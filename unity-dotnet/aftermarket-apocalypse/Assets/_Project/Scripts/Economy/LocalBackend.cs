using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using UnityEngine;

/// <summary>
/// The Phase 8 backend, running locally (spec §17 phase 8, §18.3).
///
/// It is written to BEHAVE server-authoritatively even though there is no server
/// yet (§18.4): it rejects client-supplied results, it owns the roll, it owns the
/// serial, and it returns immutable outcomes. That is the whole point — when
/// PlayFabBackend replaces it in Phase 10, the call sites do not change and no
/// trust boundary moves.
///
/// Everything returned to a caller is a DETACHED SNAPSHOT (Clone), never a live
/// record. That is not politeness: a cloud backend returns deserialized DTOs, so
/// writes a caller makes to a returned object are silently local there. If
/// LocalBackend handed out its own objects, call sites could come to depend on
/// mutating them and would break — silently — the day Phase 10 swaps the
/// implementation. Same behaviour on both sides of the swap, or the swap is not
/// the one-line change §18.3 promises.
///
/// §18.5 — SERIAL ALLOCATION MUST BE ATOMIC. "Two players opening the last case
/// of a design simultaneously must not both receive #250. This is a transaction,
/// not an increment." Locally that means the read-modify-write of the mint
/// counter happens under a lock, and the counter is persisted through
/// SafeJsonStore so a crash mid-write cannot re-issue a serial that was already
/// handed out (spec §13.1 defect #24).
/// </summary>
public class LocalBackend : IBackend
{
    private const string StoreFile = "collection.json";

    private readonly object gate = new object();
    private LocalStore store;
    private IReadOnlyList<SacredDefinition> catalogue;
    private bool inMemory;

    [Serializable]
    private class LocalStore
    {
        public PlayerProfile profile = new PlayerProfile();
        public List<MintCounter> counters = new List<MintCounter>();
        public List<string> entitlements = new List<string>();
    }

    [Serializable]
    private class MintCounter
    {
        public string designId;
        public int minted;
    }

    // ---------- catalogue ----------

    /// <summary>Injected by tests; otherwise loaded from Resources at first use.</summary>
    public void SetCatalogue(IReadOnlyList<SacredDefinition> sacreds)
    {
        catalogue = sacreds;
    }

    private SacredDefinition FindSacred(string sacredId)
    {
        if (catalogue == null)
            catalogue = Resources.LoadAll<SacredDefinition>("Sacreds");
        foreach (SacredDefinition s in catalogue)
            if (s != null && s.sacredId == sacredId) return s;
        return null;
    }

    // ---------- persistence ----------

    private string Path { get { return SafeJsonStore.PathFor(StoreFile); } }

    private LocalStore Store
    {
        get
        {
            if (store == null)
            {
                store = SafeJsonStore.Load<LocalStore>(Path) ?? new LocalStore();
                if (store.profile == null) store.profile = new PlayerProfile();
                if (store.counters == null) store.counters = new List<MintCounter>();
            }
            return store;
        }
    }

    private void Persist()
    {
        if (inMemory) return;
        SafeJsonStore.Save(Path, Store);
    }

    private int MintedCount(string designId)
    {
        foreach (MintCounter c in Store.counters)
            if (c.designId == designId) return c.minted;
        return 0;
    }

    // ---------- IBackend ----------

    public Task<PlayerProfile> LoadProfile(string userId)
    {
        lock (gate)
        {
            if (string.IsNullOrEmpty(Store.profile.userId)) Store.profile.userId = userId;
            // A SNAPSHOT, not the live record. Handing out Store.profile would let
            // any caller do `(await LoadProfile(id)).embers = 999999` and have the
            // next Persist() write it to disk.
            return Task.FromResult(Store.profile.Clone());
        }
    }

    public Task<SacredResult> OpenSacred(string userId, string sacredId)
    {
        lock (gate)                       // §18.5: the whole open is one transaction
        {
            SacredDefinition sacred = FindSacred(sacredId);
            if (sacred == null) return Task.FromResult(SacredResult.Fail("Unknown Sacred: " + sacredId));

            // 1. Eligible pool = designs that still have edition capacity (§15.5).
            if (EligiblePool(sacred).Count == 0)
            {
                // SOLD OUT and DO NOT CHARGE (§15.5).
                return Task.FromResult(SoldOut("SOLD OUT"));
            }

            // 2. Charge only once we know the open can succeed.
            ChargeResult charged = Charge(sacred);
            if (charged != ChargeResult.Paid)
            {
                bool cooldown = charged == ChargeResult.AlreadyClaimedToday;
                return Task.FromResult(new SacredResult
                {
                    success = false,
                    insufficientFunds = !cooldown,
                    onCooldown = cooldown,
                    message = cooldown
                        ? "Already claimed today — resets at 00:00 UTC"
                        : "Not enough " + sacred.currency
                });
            }

            // 3-6. The single-open core, shared with OpenSacredBulk.
            SacredResult minted = MintOne(userId, sacred);
            if (minted != null) return Task.FromResult(minted);

            // UNREACHABLE: the pool was non-empty four lines above and the gate has
            // not been released since. Handled anyway, and handled by REFUNDING,
            // because the one outcome that must never happen is charging for
            // nothing — an "impossible" branch that silently keeps the money is how
            // that ships.
            Refund(sacred, 1);
            return Task.FromResult(SoldOut("SOLD OUT — you were not charged."));
        }
    }

    /// <summary>
    /// ★ Spec §15.4: buy 10, get the 11th free. See <see cref="IBackend"/> for the
    /// contract; this is how it is kept.
    ///
    /// <para><b>Every ordering decision below is load-bearing.</b> Affordability is
    /// checked against the WHOLE bundle before anything is debited, the pool is
    /// checked before that, and the entire thing runs inside one
    /// <c>lock (gate)</c> — §18.5's "this is a transaction, not an increment",
    /// which for eleven serials in a row matters eleven times as much.</para>
    ///
    /// <para><b>Pity carries across the bundle for free</b>, because
    /// <see cref="MintOne"/> is the same code a single open runs and it advances
    /// <c>opensSinceLastRgb</c> on the live profile. There is no per-bundle copy of
    /// the counter to get out of step — which is exactly the bug a bulk path
    /// written as its own loop would have.</para>
    /// </summary>
    public Task<SacredResult[]> OpenSacredBulk(string userId, string sacredId, int paidCount)
    {
        lock (gate)
        {
            // A caller bug, NOT something to clamp. Clamping 0 to 1 would turn
            // "the button passed the wrong variable" into a purchase the player
            // did not ask for, and it would do it silently.
            if (paidCount < 1)
                return One(SacredResult.Fail(
                    "A bulk open needs paidCount >= 1; got " + paidCount + ". This is a caller " +
                    "bug and is refused rather than clamped."));

            // An upper bound so a wrong variable cannot become an eleven-figure
            // mint loop under a held lock. It is a sanity rail, not a game rule.
            if (paidCount > MaxBulkPaidCount)
                return One(SacredResult.Fail(
                    "A bulk open is capped at " + MaxBulkPaidCount + " paid opens; got " +
                    paidCount + "."));

            SacredDefinition sacred = FindSacred(sacredId);
            if (sacred == null) return One(SacredResult.Fail("Unknown Sacred: " + sacredId));

            // §15.4's bonus is a discount on a PURCHASE. A Free Sacred is a
            // once-per-UTC-day entitlement gated by HasClaimedFreeToday, so "buy
            // ten" has no meaning — and quietly opening ONE instead would be the
            // backend deciding what the player bought.
            if (sacred.currency == CurrencyKind.Free)
                return One(SacredResult.Fail(
                    sacred.displayName + " is a free daily claim, not a purchase — it cannot " +
                    "be bought in bulk. Open it once; it resets at 00:00 UTC."));

            // long, because price * paidCount is the one place here that can
            // overflow an int, and an overflowed total wraps NEGATIVE — which any
            // balance would then "afford".
            long total = (long)sacred.price * paidCount;

            // ★ ATOMIC ON AFFORDABILITY. Priced and checked in full before a single
            // unit moves.
            if (!CanAfford(sacred, total))
                return One(new SacredResult
                {
                    success = false,
                    insufficientFunds = true,
                    message = "Not enough " + sacred.currency + " — " + paidCount + " × " +
                              sacred.price + " is " + total + ". You were not charged."
                });

            // SOLD OUT and DO NOT CHARGE (§15.5), before the debit for the same
            // reason a single open checks it first.
            if (EligiblePool(sacred).Count == 0)
                return One(SoldOut("SOLD OUT — you were not charged."));

            AdjustBalance(sacred.currency, -(int)total);

            int deliver = DropTable.TotalSacredsForPurchase(paidCount);
            var results = new List<SacredResult>(deliver);

            for (int i = 0; i < deliver; i++)
            {
                SacredResult r = MintOne(userId, sacred);
                if (r != null) { results.Add(r); continue; }

                // The pool retired part-way through (§15.5). Stop, and give back
                // the per-open price for every PAID open that will not be
                // delivered. The free ones are the tail of the bundle, so they are
                // the first thing lost and they cost nothing to lose.
                int paidDelivered = i < paidCount ? i : paidCount;
                int refundUnits = paidCount - paidDelivered;
                if (refundUnits > 0) Refund(sacred, refundUnits);

                results.Add(SoldOut(
                    "SOLD OUT after " + i + " of " + deliver + " — every design in this Sacred " +
                    "has retired. " + (refundUnits > 0
                        ? refundUnits * sacred.price + " " + sacred.currency + " refunded."
                        : "Only free opens were lost, so there is nothing to refund.")));
                break;
            }

            // MintOne persists after each item, but a refund happens after the last
            // one — so without this the give-back would be the one part of the
            // transaction that a crash could lose.
            Persist();
            return Task.FromResult(results.ToArray());
        }
    }

    /// <summary>
    /// A rail, not a rule: nothing in §15 caps a bulk buy, but an unbounded
    /// paidCount is an unbounded mint loop holding the gate.
    /// </summary>
    public const int MaxBulkPaidCount = 100;

    static Task<SacredResult[]> One(SacredResult r)
    {
        // Never an empty array. "Nothing came back" and "nothing went wrong" read
        // identically at a call site, and one of them is a refusal the player needs
        // the reason for.
        return Task.FromResult(new[] { r });
    }

    static SacredResult SoldOut(string why)
    {
        return new SacredResult { success = false, soldOut = true, message = why };
    }

    /// <summary>Designs that still have edition capacity (§15.5). Recomputed per
    /// open, because minting is what consumes the capacity.</summary>
    private List<SkinDesign> EligiblePool(SacredDefinition sacred)
    {
        var pool = new List<SkinDesign>();
        if (sacred == null || sacred.designs == null) return pool;
        foreach (SkinDesign d in sacred.designs)
            if (d != null && MintedCount(d.designId) < d.editionCap) pool.Add(d);
        return pool;
    }

    /// <summary>
    /// THE SINGLE-OPEN CORE — the roll, the pity, the uniform pick, the serial and
    /// the mint. Assumes the caller has ALREADY taken payment, and must be called
    /// with <c>gate</c> held.
    ///
    /// <para>It exists so <see cref="OpenSacred"/> and
    /// <see cref="OpenSacredBulk"/> cannot disagree about how a serial is
    /// allocated. Serials are permanent (§15.2) and the mint counter is the serial
    /// (§15.5), so two implementations of this would be two ways to issue #1.</para>
    ///
    /// <para>Returns <c>null</c> — not a failure result — when the pool is empty.
    /// The two callers owe the player different things at that point (one has not
    /// charged yet, the other has), so the decision belongs to them.</para>
    /// </summary>
    private SacredResult MintOne(string userId, SacredDefinition sacred)
    {
        List<SkinDesign> pool = EligiblePool(sacred);
        if (pool.Count == 0) return null;

        // The SERVER rolls. §15.3's chain, with §15.4's pity.
        DropTable.Outcome outcome = DropTable.Resolve(
            UnityEngine.Random.value, UnityEngine.Random.value, UnityEngine.Random.value,
            Store.profile.opensSinceLastRgb);

        if (outcome == DropTable.Outcome.RGB) Store.profile.opensSinceLastRgb = 0;
        else Store.profile.opensSinceLastRgb++;

        // ★ Uniform selection across the surviving pool — flat, not rarity-weighted
        // (§15.3).
        SkinDesign design = pool[UnityEngine.Random.Range(0, pool.Count)];

        // Allocate the serial. serial = ++counter, and THE SERIAL IS THE MINT
        // COUNTER (§15.5). Non-serialized outcomes consume no serial.
        int serial = 0;
        if (DropTable.IsSerializedOutcome(outcome))
        {
            MintCounter counter = null;
            foreach (MintCounter c in Store.counters)
                if (c.designId == design.designId) { counter = c; break; }
            if (counter == null)
            {
                counter = new MintCounter { designId = design.designId, minted = 0 };
                Store.counters.Add(counter);
            }
            counter.minted++;
            serial = counter.minted;
        }

        SkinVariant variant = DropTable.VariantFor(outcome);
        if (variant == SkinVariant.None && UnityEngine.Random.value < DropTable.TrackerChance)
            variant = SkinVariant.Tracker;

        SkinInstance item = new SkinInstance
        {
            itemId = Guid.NewGuid().ToString("N"),
            designId = design.designId,
            serial = serial,
            editionCap = design.editionCap,
            wear = FloatWear.Roll(UnityEngine.Random.value, sacred.floatCurveExponent,
                                  design.floatMin, design.floatMax),
            variant = variant,
            rarity = DropTable.RarityFor(outcome),
            trackerKills = 0,
            originalOwnerUserId = userId,
            currentOwnerId = userId,
            previousOwnerIds = new string[0],
            mintDate = DateTime.UtcNow.ToString("o"),
            acquisitionSource = sacred.displayName
        };

        Store.profile.inventory.Add(item);

        if (design.linkedCamoIndex >= 0)
            PlayerPrefs.SetInt(CamoProgression.CamoKey(design.weaponName, design.linkedCamoIndex), 1);

        // Persisted PER ITEM, including inside a bundle. A serial that has been
        // handed out must survive a crash on the next one (§13.1 defect #24) — the
        // alternative is re-issuing it.
        Persist();
        GameEvents.RaiseSkinMinted(item);

        return new SacredResult
        {
            success = true,
            item = item.Clone(),      // snapshot: the caller cannot rewrite its own serial
            message = design.displayName + " " + item.DisplaySerial()
        };
    }

    /// <summary>Why a charge did or did not go through. A bool could only ever
    /// say "no", which is how the free daily gate ended up reported as a missing
    /// currency.</summary>
    private enum ChargeResult { Paid, NotEnough, AlreadyClaimedToday }

    private ChargeResult Charge(SacredDefinition sacred)
    {
        switch (sacred.currency)
        {
            // §15's ladder (spec line 1364) lists the Recruit Sacred as
            // "free -- daily login + first win of day". It was implemented as an
            // unconditional `return true`, so a free Sacred could be opened in a
            // loop forever: unlimited serial-numbered items, minted straight into
            // the economy the rest of Part II is built on. One claim per Sacred per
            // UTC day is the floor that makes "free" mean an entitlement rather
            // than a tap.
            case CurrencyKind.Free:
                if (Store.profile.HasClaimedFreeToday(sacred.sacredId))
                    return ChargeResult.AlreadyClaimedToday;
                Store.profile.RecordFreeClaim(sacred.sacredId);
                return ChargeResult.Paid;
            case CurrencyKind.Embers:
                if (Store.profile.embers < sacred.price) return ChargeResult.NotEnough;
                Store.profile.embers -= sacred.price;
                GameEvents.RaiseEmbersChanged(Store.profile.embers);
                return ChargeResult.Paid;
            case CurrencyKind.SoulCrystals:
                if (Store.profile.soulCrystals < sacred.price) return ChargeResult.NotEnough;
                Store.profile.soulCrystals -= sacred.price;
                GameEvents.RaiseSoulCrystalsChanged(Store.profile.soulCrystals);
                return ChargeResult.Paid;
            case CurrencyKind.Shards:
                if (Store.profile.shards < sacred.price) return ChargeResult.NotEnough;
                Store.profile.shards -= sacred.price;
                return ChargeResult.Paid;
        }
        return ChargeResult.NotEnough;
    }

    /// <summary>
    /// Can the profile pay <paramref name="total"/> of this Sacred's currency?
    ///
    /// <para>Takes a <c>long</c> so an overflowed bundle price cannot arrive here
    /// as a negative number that every balance "affords".</para>
    ///
    /// <para><see cref="CurrencyKind.Free"/> answers NO. Affordability is not the
    /// question for a daily entitlement, and the bulk path refuses Free before it
    /// ever gets here — this is the second lock on that door, not the first.</para>
    /// </summary>
    private bool CanAfford(SacredDefinition sacred, long total)
    {
        switch (sacred.currency)
        {
            case CurrencyKind.Embers: return Store.profile.embers >= total;
            case CurrencyKind.SoulCrystals: return Store.profile.soulCrystals >= total;
            case CurrencyKind.Shards: return Store.profile.shards >= total;
            default: return false;
        }
    }

    /// <summary>
    /// Move a balance by <paramref name="delta"/> and raise the same events
    /// <see cref="Charge"/> raises. Negative debits, positive refunds.
    ///
    /// <para>Deliberately NOT routed through <see cref="Credit"/>: that method
    /// takes the gate itself and raises its events outside it, and every caller
    /// here is already inside the transaction. This is the in-transaction move.</para>
    /// </summary>
    private void AdjustBalance(CurrencyKind currency, int delta)
    {
        switch (currency)
        {
            case CurrencyKind.Embers:
                Store.profile.embers += delta;
                GameEvents.RaiseEmbersChanged(Store.profile.embers);
                break;
            case CurrencyKind.SoulCrystals:
                Store.profile.soulCrystals += delta;
                GameEvents.RaiseSoulCrystalsChanged(Store.profile.soulCrystals);
                break;
            case CurrencyKind.Shards:
                Store.profile.shards += delta;
                break;
        }
    }

    /// <summary>
    /// Give back <paramref name="units"/> opens' worth of the price — §15.5's "DO
    /// NOT CHARGE" applied after the fact, which is the only form it can take once
    /// a bundle has already been paid for.
    ///
    /// <para>A Free Sacred's "price" is the day's claim, so refunding one means
    /// releasing the claim rather than moving a balance. Unreachable today (bulk
    /// refuses Free), and written out anyway so that a future caller cannot get a
    /// silent no-op.</para>
    /// </summary>
    private void Refund(SacredDefinition sacred, int units)
    {
        if (units <= 0) return;
        if (sacred.currency == CurrencyKind.Free)
        {
            Store.profile.freeSacredClaims.Remove(
                sacred.sacredId + "=" + PlayerProfile.TodayStamp());
            return;
        }
        AdjustBalance(sacred.currency, sacred.price * units);
    }

    public Task<bool> ExecuteTrade(TradeOffer offer)
    {
        lock (gate)
        {
            // Phase 11 builds real trading. What matters NOW is that the owner
            // record is server-side and the provenance chain is append-only, so
            // items minted today are already tradable later (§15.9).
            if (offer == null) return Task.FromResult(false);
            foreach (string id in offer.fromItemIds)
            {
                SkinInstance item = FindItem(id);
                if (item == null || item.currentOwnerId != offer.fromUserId) return Task.FromResult(false);
            }
            foreach (string id in offer.fromItemIds)
            {
                SkinInstance item = FindItem(id);
                List<string> prev = new List<string>(item.previousOwnerIds) { item.currentOwnerId };
                item.previousOwnerIds = prev.ToArray();
                item.currentOwnerId = offer.toUserId;
                // originalOwnerUserId is NEVER touched — that is the OG badge.
            }
            Persist();
            return Task.FromResult(true);
        }
    }

    private SkinInstance FindItem(string itemId)
    {
        foreach (SkinInstance i in Store.profile.inventory)
            if (i.itemId == itemId) return i;
        return null;
    }

    /// <summary>Spec §15.7: SERVER-CLAMPED. A client that reports 10,000 kills in
    /// one batch is not believed.</summary>
    public const int MaxTrackerBatch = 500;

    public Task IncrementTracker(string itemId, int kills)
    {
        lock (gate)
        {
            SkinInstance item = FindItem(itemId);
            if (item == null || kills <= 0) return Task.CompletedTask;
            item.trackerKills += Mathf.Min(kills, MaxTrackerBatch);
            Persist();
            return Task.CompletedTask;
        }
    }

    public Task<bool> HasEntitlement(string userId, string entitlementId)
    {
        lock (gate)
        {
            return Task.FromResult(Store.entitlements.Contains(entitlementId));
        }
    }

    // ---------- test / dev helpers ----------

    public void GrantEntitlement(string entitlementId)
    {
        lock (gate)
        {
            if (!Store.entitlements.Contains(entitlementId)) Store.entitlements.Add(entitlementId);
            Persist();
        }
    }

    public void DevGrantCurrency(int embers, int soulCrystals, int shards)
    {
        Credit(embers, soulCrystals, shards);
    }

    /// <summary>
    /// The real earnings path — see <see cref="IBackend.CreditCurrency"/> for why
    /// it has to exist.
    /// </summary>
    public Task CreditCurrency(string userId, int embers, int soulCrystals, int shards)
    {
        if (embers < 0 || soulCrystals < 0 || shards < 0)
        {
            Debug.LogError("[Backend] CreditCurrency called with a negative amount (" +
                           embers + ", " + soulCrystals + ", " + shards + "). Spending goes " +
                           "through the Sacred purchase path, not through a negative credit.");
            return Task.CompletedTask;
        }
        Credit(embers, soulCrystals, shards);
        return Task.CompletedTask;
    }

    void Credit(int embers, int soulCrystals, int shards)
    {
        int newEmbers, newCrystals;
        lock (gate)
        {
            Store.profile.embers += embers;
            Store.profile.soulCrystals += soulCrystals;
            Store.profile.shards += shards;
            newEmbers = Store.profile.embers;
            newCrystals = Store.profile.soulCrystals;
            Persist();
        }
        // Raised OUTSIDE the lock: a listener that calls back into the backend
        // would deadlock on a non-reentrant lock, and HUD code doing exactly that
        // is entirely plausible.
        if (embers != 0) GameEvents.RaiseEmbersChanged(newEmbers);
        if (soulCrystals != 0) GameEvents.RaiseSoulCrystalsChanged(newCrystals);
    }

    /// <summary>Run entirely in memory, touching no file. Tests only — otherwise a
    /// test run would overwrite the player's real collection.json.</summary>
    public void UseInMemoryStore()
    {
        lock (gate)
        {
            store = new LocalStore();
            inMemory = true;
        }
    }
}
