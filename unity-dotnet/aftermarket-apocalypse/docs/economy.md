<!-- Split out of CLAUDE.md on 2026-08-01. The text below is copied
     byte-for-byte; CLAUDE.md now carries a one-line index entry for each
     landmine here. Read this file before touching the systems it covers. -->

# Phase 8 — the Sacred economy's visible half

Covers the wallet unification, `SacredReel`, published odds, the store,
bulk opens, `ScreenNav`, and the collection screen.

## Phase 8 — the Sacred economy's visible half (2026-07-31)

§17 phase 8 is "Skin & Sacred economy … on `LocalBackend` — mint, Sacreds, the
opening (§15.8.1), **the Theme/recolor system (§15.11) and the Store (§15.12)**".
The themes are above. The rest of the *logic* already existed from Phase 2 —
`DropTable`, `SkinInstance`, `FloatWear`, `SacredDefinition`, `LocalBackend.OpenSacred`
with minting, serials and edition caps, 23 passing `EconomyTests`. What was
missing was every part a player can see.

> ### ★★ THERE WERE TWO EMBER WALLETS AND ONLY ONE COULD BUY ANYTHING
>
> Found while checking whether the store was usable on a fresh install. It is the
> reason Phase 8 would have shipped dead.
>
> - **`PlayerWallet`** kept Embers, Soul Crystals and Shards in **`PlayerPrefs`**.
>   That is where *playing* banked them: `PlayerHealth` on death →
>   `MatchStats.BankRun()` → `PlayerWallet.BankRun` → `AddEmbers`.
> - **`LocalBackend`** kept the same three on `PlayerProfile`, persisted to
>   **`collection.json`**. That is what the store *spends*
>   (`LocalBackend.cs:245`) and what any balance readout shows.
>
> They never spoke. Die on round 20, watch **"+1,250 EMBERS BANKED"**, open the
> store, read **◆ 0** — and every paid Sacred refuses, forever, with nothing
> logged. Both stores were internally consistent, both persisted correctly, and
> both were *right*; there were simply two of them. That is why it survived seven
> phases: nothing was broken.
>
> **The file predicted its own bug.** `PlayerWallet`'s header said *"PlayerPrefs
> is the Phase 1 placeholder. Phase 2 moves reads/writes behind IBackend
> (§18.3)"*. Phase 2 shipped `IBackend` **with currency on the profile** and left
> the placeholder in place, so it became a second source of truth instead of
> being replaced by one. A comment describing an intention is not a migration.
>
> **No existing test could see it.** `EconomyTests` granted through the backend
> and spent through the backend — consistent. `SpecNumbersTests` checked
> `ComputeRunEmbers`'s arithmetic — also consistent. The defect was in the gap
> *between* the two, and neither test crosses it.
>
> Fixed by deleting the second wallet rather than syncing it: `IBackend` gains
> `CreditCurrency`, `PlayerWallet`'s three `Add*` methods are a façade over it,
> and **there are no currency getters left in `PlayerWallet`** — a cached local
> copy is how you get two wallets again. `BestRound` and `Extractions` stay in
> PlayerPrefs; they are stats, not currency, and nothing spends them.
> `TrySpendEmbers`/`TrySpendSoulCrystals`/`TrySpendShards` are gone: they debited
> the wallet the store does not charge, which is the same bug pointing backwards.
>
> `WalletTests.Wallet_PlayerWalletHoldsNoCurrencyOfItsOwn` is the guard that
> matters, and it is deliberately *not* written against the façade — it reflects
> over `PlayerWallet` for a currency property and greps the file for a PlayerPrefs
> currency key, because the failure mode is a SECOND path that bypasses the façade
> the other tests exercise.

### The opening (§15.8.1) — "this is the product, treat it that way"

`Scripts/Economy/SacredReel.cs` (pure) + `Scripts/UI/SacredOpening.cs` (renders it).

**The maths is a plain class with no Unity objects in it**, the way `DropTable`
is, so the parts that fail silently are testable without a scene.

- **The outcome is decided before the reel exists.** `SacredOpening.Play` takes a
  `SacredResult` the backend already produced; `SacredReel.Build` takes the
  outcome as an argument and cannot roll. §18.4: *"the reel is theatre played over
  a known outcome. Never let the animation determine the item."* The only random
  source is a caller seed that decides **filler cards**, and that seed is derived
  from the item's own `itemId` so an open is reproducible from a bug report.
- **The winner is written before the near-miss**, so the tease can never overwrite
  it. `Reel_TheTeaseNeverOverwritesTheWinner` asserts exactly one card carries the
  winner flag, across every outcome and 60 seeds — an off-by-one there would show
  the player an item the server did not mint, which is the only bug in this
  feature that costs anything real.
- **The reel lands on the winner exactly, not near it.** The final frame snaps to
  `WinnerIndex * pitch` rather than trusting the last `t`, because stopping a
  fraction of a card short leaves the winner visibly off-centre.
- The tease sits **1 or 2 slots before** the winner and alternates between them —
  a fixed distance is one players learn. For an RGB win there is nothing higher,
  so the tease is SCT: it keeps the beat for the pull that most deserves one.
- **Only RGB blocks skipping** (2 s, §15.8.1) and **only ordinary pulls can be
  summarised away** in a bulk open. `CanSkipInBulk` is a property of the outcome,
  not a user preference.
- The tier is **recovered from the minted item** (`SacredOpening.OutcomeOf`), not
  carried alongside it — a separate outcome enum is a second source of truth that
  can disagree with what is in the inventory. A **Tracker is not a serialisation
  tier**: §15.7 puts Trackers on ~5% of items including ordinary ones, so treating
  the variant as "serialized" would give twenty times too many pulls the gold-seam
  treatment.

> ### ⚠ THE REEL WAS 12.5 CARDS OFF AND EVERY EDITMODE TEST WAS GREEN
>
> `Assets/Tests/PlayMode/SacredOpeningPlayTests.cs` loads the real `Store.unity`,
> drives a real open with a fabricated result, and **measures the winning card's
> screen position against the centre marker**. It found two bugs on its first run
> that 184 EditMode tests could not:
>
> **1. The scroll was on the wrong object.** `SacredOpening` slid `reelRoot` by
> `-scrolled * pitch` and left the cards on whatever anchors the scene builder
> authored. Where the winner ended up therefore depended on `reelRoot`'s rect
> width and pivot — which this component does not own. Measured **825 world
> units off, about thirteen card-widths.** The fix moves the scroll onto the
> **cards** (`Place(i, scrolled)` → `(i - scrolled) * pitch`) and pins each pooled
> card to `reelRoot`'s centre with a centre pivot. At `scrolled == WinnerIndex`
> the winner's offset is exactly zero, so "did it land" reduces to "is reelRoot's
> centre the marker" — one fact, instead of an interaction between a rect width,
> two pivots and an `anchoredPosition`.
>
> **2. `CanSkipNow` was true for the entire spin.** It read
> `playing && now >= noSkipUntil`, and `noSkipUntil` is only assigned *after* the
> reel settles — so during all 5–6 seconds it compared against 0 and answered
> **yes**. Anything asking "may the player dismiss this yet?" got the wrong
> answer, including §15.8.1's RGB no-skip window. There is now a separate
> `revealing` flag and `CanSkipNow` requires the card to actually be up.
>
> **The second bug is why the first looked worse than it was.** The test's own
> wait loop believed `CanSkipNow`, exited immediately and measured mid-spin — at
> `EaseOutCubic(1.2/5.8) × 25 = 12.5` cards, which is exactly the 2,143 px it
> reported. So one defect produced the other's evidence. Both are real and both
> are fixed; the reel now lands within a quarter of a card width.
>
> `SacredOpening.Cancel()` exists for a third, smaller one: `playing` is cleared
> at the END of the coroutine, so anything that stopped the coroutine early —
> a scene unload, a test — left the component permanently "busy" and every later
> open was silently refused. It does **not** undo the pull: the item was minted
> and charged before the animation began.

### ★ Publishing the odds — the tree is not what the player experiences

`Scripts/Economy/OddsPublication.cs`. §15.8 says publish the drop table, and notes
that several jurisdictions require disclosed odds for paid boxes.

**The obvious implementation is wrong in the direction that matters.** Copying
DropTable's doc comment gives "RGB 0.0125%, 1 in 8,000" — the *tree's* odds. But
`RgbPityThreshold` guarantees an RGB on the 101st open, and DropTable's own
comment already records the consequence (~79x). Computed exactly:

| outcome | published | 1 in |
|---|---|---|
| Standard | 94.0653% | 1 |
| Serialized | 4.7033% | 21 |
| SCT | 0.2352% | 425 |
| **RGB** | **0.9963%** | **100** |

Expected opens per RGB is **100.371**. So publishing the tree figure would have
**understated the top prize of a paid box by 80x**. The panel computes from
`DropTable`'s own constants — change the pity threshold and the disclosure moves
with it, because there is no second copy to forget.

### The store (§15.12.3)

`Scripts/UI/StoreScreen.cs` + `Editor/StoreBuilder.cs` → `Scenes/Store.unity`.
This is the SACREDS tab only; trading (§15.9) and real-money Soul Crystal
purchases are Phase 11 and are deliberately absent.

- **It never checks affordability before calling the backend.** It calls
  `OpenSacred` and renders whichever answer comes back. A client-side check is a
  second copy of the rule that can disagree with the server (§18.4).
- **The three failure flags are reported separately.** `SacredResult` carries
  `soldOut`, `onCooldown` and `insufficientFunds`, and its own doc records why:
  collapsing them once made a second free daily claim report *"Not enough Free"*,
  sending the player looking for money when the real condition was a timer.
- **Confirmation is keyed on the CURRENCY, not a price threshold.** §15.12.3:
  "never one-click a premium spend". Soul Crystals are the only thing bought with
  real money, so `NeedsConfirmation` tests `currency == SoulCrystals`; a rule
  written as `price > 20` stops protecting anything the day a Sacred is repriced.

> ### ⚠ The backend's Sacred catalogue loads from `Resources/Sacreds` — which does not exist
>
> `LocalBackend.FindSacred` falls back to
> `Resources.LoadAll<SacredDefinition>("Sacreds")`, and the eight Sacred assets
> live in `Assets/_Project/Content/Sacreds/` — **not** under a `Resources` folder.
> So the catalogue loaded as an **empty array**, `FindSacred` returned null, and
> **every purchase failed on the very first click** with a bare message. Nothing
> errors: an empty catalogue is a valid catalogue.
>
> `StoreScreen.SeedLocalCatalogue` injects the authored list instead of moving the
> assets into `Resources/` — a `Resources` folder is pulled into the build whole
> whether or not anything reaches it, and "which Sacreds exist" would become a
> property of folder layout rather than something you can look at. It is guarded
> on `Backend.Current as LocalBackend`, the same shape as
> `CharacterSelect.SeedLocalEntitlements`: the stand-in server being handed its
> own data, never the client telling a real server what it sells.
>
> **The fabricated-result tests could not have caught this**, because they skip
> the exact step that was broken. `Store_TheFreeSacredCanActuallyBeOpenedOnAFreshProfile`
> goes through the real backend on a throwaway in-memory store and asserts a free
> Sacred actually mints — the first-click path a human takes.

### Bulk opens (§15.4 — buy 10, get the 11th free)

`IBackend.OpenSacredBulk(userId, sacredId, paidCount)`. The store has a
`BUY ×1 / BUY ×10 → 11 SACREDS` toggle that changes what a tile click does.

**The single-open core was extracted, not copied.** `LocalBackend.MintOne` now
holds the roll, pity, uniform pick, serial allocation and mint, and both
`OpenSacred` and `OpenSacredBulk` call it. Duplicating it would mean two places
that can disagree about how a serial is allocated, and a serial is permanent
(§15.2). It is also what makes **pity carry across a bundle for free** — there is
no per-bundle copy of `opensSinceLastRgb` to get out of step.

Four decisions worth keeping:

- **Atomic on affordability.** The whole bundle is priced and checked before a
  single unit is debited, and the total is a `long` — an overflowed `int` total
  wraps negative and then *every* balance affords it.
- **Sold out mid-bundle refunds every undelivered PAID open.** §15.5 is "SOLD OUT
  and DO NOT CHARGE"; the free tail is lost first and costs nothing.
- **A `CurrencyKind.Free` Sacred refuses a bulk buy** and does not burn the daily
  claim. It is a once-per-UTC-day entitlement, not a price.
- **`paidCount < 1` refuses rather than clamps.** Clamping 0 to 1 turns "the
  button passed the wrong variable" into a purchase the player did not ask for.
  `MaxBulkPaidCount = 100` is a **rail, not a game rule** — it stops a wrong
  variable becoming an unbounded mint loop under a held lock.

> **The skip-to-summary rule is proved twice, on purpose.**
> `SacredReel.CanSkipInBulk` is unit-tested, but a rule proved on a pure class and
> a coroutine that honours it are different claims — the reel's own 825-unit
> landing error was exactly that gap. So
> `Opening_ABulkOfOrdinaryPullsGoesStraightToTheSummary` drives the real coroutine
> and **times it**: eleven ordinary pulls would be ~57 s of reels, so reaching the
> summary inside 10 s is the proof they were summarised. Its twin,
> `Opening_ABulkStillFullyRevealsAnythingSerialized`, checks the serialized one is
> *not* swallowed.

### The three screens are one surface — `ScreenNav`

`Tools > Aftermarket > Wire Screen Navigation`, and every screen builder now adds
the bar itself. STORE / COLLECTION / CHARACTER, top of each canvas, with the
button for the scene you are in greyed out.

> **A scene that is not in Build Settings cannot be loaded, and the symptom is a
> button that does nothing.** `SceneManager.LoadScene` throws for an unregistered
> scene, and from inside a UI callback that exception goes to a console nobody has
> open. All three of these scenes are **build outputs** — re-running a builder is
> routine — so `NavBarBuilder.EnsureInBuildSettings()` runs as part of every
> build, and `Nav_EveryScreenIsInBuildSettingsAndEnabled` fails the suite if one
> ever drops out. It checks **enabled** as well as present: a disabled row reads
> as fine in the inspector and is invisible to `CanStreamedLevelBeLoaded`.
>
> Existing entries are never reordered. Reordering changes scene BUILD INDICES,
> and anything loading by index would silently start loading a different scene.
>
> `ScreenNav` also checks `CanStreamedLevelBeLoaded` at `Start` and **disables**
> a button whose scene is missing, labelling it "(missing)" — a dead button that
> still looks clickable is worse than one that admits it.
>
> `Nav_EachScreenCarriesABarThatKnowsWhereItIs` catches the other half: a bar with
> the wrong `currentSceneName` greys out the wrong button, so one screen becomes
> unreachable and another links to itself.

### The collection screen (§15.2 / §15.9)

`Scripts/UI/InventoryScreen.cs` + `Editor/InventoryBuilder.cs` →
`Scenes/Inventory.unity`. Its own scene rather than a tab in `Store.unity`, so
two builders never write the same undiffable file.

**Phase 8 could not be called finished without it.** The opening spends five
seconds telling the player the serial is "the thing that is unique in the world",
and then the reveal faded and the item was gone into `collection.json` with no
way to look at it again. A chase economy where you cannot see what you caught is
a slot machine with no payout screen.

The display logic is **static functions** (`SortForDisplay`, `TileText`,
`Provenance`) so the parts that go quietly wrong are testable without a scene —
and what goes quietly wrong here is *order* and *provenance*:

- **Serialized first, then rarity, then serial ASCENDING.** Newest-first would
  bury serial #1 under everything minted since, and §15.5 calls #1 a one-time
  event in the game's lifetime. `Inventory_LowerSerialsSortFirstWithinATier`.
- **The sort is total.** Two items identical in every key still order by `itemId`,
  or the grid visibly reshuffles itself on each refresh.
- **The OG badge appears only when `originalOwnerUserId == currentOwnerId`.**
  §15.9 makes the original owner permanent precisely so the badge can be trusted;
  showing it on an item you did not mint makes all of the provenance worthless.
  `Inventory_TheOgBadgeAppearsOnlyForTheOriginalOwner`.
- **An empty collection says so.** An empty grid and a broken grid look identical,
  so the screen prints "Nothing yet. Open a Sacred in the store."

### Looking at it — a fresh profile has nothing to spend

`Tools > Aftermarket > Dev — Grant Test Currency` (50,000 ◆ / 500 ✦ / 5,000 ◇),
`Dev — Show Profile`, `Dev — Delete Local Save`. **None of it is scaffolding the
game depends on** — the `PlaytestRig` lesson is that a rig the game needs in
order to work becomes load-bearing and then becomes a lie. These are menu items a
human invokes on purpose; deleting `Editor/DevCurrency.cs` changes nothing.

A fresh install really is at 0/0/0 (verified: `Dev — Show Profile` prints it), so
without granting, the only clickable tile is the free **Recruit Sacred**, once per
day. That is correct behaviour and a poor thing to look at.

**The RGB takeover is 1 in 100 by design**, so clicking will probably not show it.
The four presentations are exercised by `SacredOpeningTests` and the reveal is
photographed by `SacredOpeningPlayTests`, but *seeing* an RGB means either 100
opens or forcing the outcome by hand.
