using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using TMPro;
using UnityEngine;
using UnityEngine.UI;

/// <summary>
/// The §15.12 store: the Sacred ladder, the currency header, the published odds,
/// and the purchase confirmation that premium spends require.
///
/// <para>This is the SACREDS tab of §15.12.3. Characters and themes live in
/// <see cref="CharacterSelect"/>; trading (§15.9) and Soul Crystal purchases with
/// real money are Phase 11 and are deliberately absent.</para>
///
/// <para><b>Every decision here belongs to the backend.</b> The screen never
/// checks whether you can afford something before calling
/// <c>IBackend.OpenSacred</c> — it calls, and renders whichever failure comes
/// back. A client-side affordability check would be a second copy of the rule
/// that can disagree with the server, and §18.4 is explicit that the client may
/// render state and may never decide it.</para>
/// </summary>
public class StoreScreen : MonoBehaviour
{
    [Header("Content")]
    public SacredDefinition[] sacreds = new SacredDefinition[0];
    public SkinDesign[] designs = new SkinDesign[0];

    [Header("Header")]
    public TMP_Text embersLabel;
    public TMP_Text crystalsLabel;
    public TMP_Text shardsLabel;

    [Header("Grid")]
    public RectTransform grid;
    public Button sacredTemplate;

    [Header("Odds (§15.8 — publishing them is not optional)")]
    public TMP_Text oddsPanel;

    [Header("Confirm")]
    public GameObject confirmPanel;
    public TMP_Text confirmText;
    public Button confirmYes;
    public Button confirmNo;

    [Header("Bulk (§15.4 — buy 10, get the 11th free)")]
    [Tooltip("Arms bulk mode. The next tile clicked is bought as a bundle, not singly.")]
    public Button bulkToggle;
    [Tooltip("The toggle's own label. Bulk mode changes what a CLICK DOES, so it has to " +
             "be visible on the button itself, not implied.")]
    public TMP_Text bulkToggleLabel;

    [Header("Feedback")]
    public TMP_Text statusLabel;

    [Header("Opening")]
    public SacredOpening opening;

    /// <summary>
    /// How many the bulk button buys. Taken from <see cref="DropTable"/> rather
    /// than typed, so the storefront cannot offer a "10" that the backend's bonus
    /// arithmetic no longer recognises.
    /// </summary>
    public const int BulkPaidCount = DropTable.BulkBuyCount;

    /// <summary>Spec §15.1: the currency glyphs are part of the names.</summary>
    public const string EmberGlyph = "◆";      // ◆
    public const string CrystalGlyph = "✦";    // ✦
    public const string ShardGlyph = "◇";      // ◇

    readonly List<Button> tiles = new List<Button>();
    Task<PlayerProfile> profileTask;
    Task<SacredResult> openTask;
    Task<SacredResult[]> bulkTask;
    SacredDefinition pendingConfirm;
    bool pendingConfirmIsBulk;
    bool bulkArmed;
    PlayerProfile profile;

    /// <summary>True when the next tile click buys a bundle. Exposed for tests and
    /// for anything that needs to describe the store's current mode.</summary>
    public bool BulkArmed { get { return bulkArmed; } }

    void Start()
    {
        SeedLocalCatalogue();
        if (sacredTemplate != null) sacredTemplate.gameObject.SetActive(false);
        if (confirmPanel != null) confirmPanel.SetActive(false);
        if (confirmYes != null) confirmYes.onClick.AddListener(ConfirmYes);
        if (confirmNo != null) confirmNo.onClick.AddListener(ConfirmNo);
        if (bulkToggle != null) bulkToggle.onClick.AddListener(ToggleBulk);
        PaintBulkToggle();

        // §15.8: publish the odds. Computed from DropTable's own constants by
        // OddsPublication, never typed here — a second copy would drift the day
        // the pity threshold is tuned, and these are disclosed odds.
        if (oddsPanel != null) oddsPanel.text = OddsPublication.PanelText();

        BuildGrid();
        Refresh();
    }

    /// <summary>
    /// Hand the stand-in server the Sacreds it is selling.
    ///
    /// <para><b>Without this every open fails on the first click.</b>
    /// <c>LocalBackend.FindSacred</c> falls back to
    /// <c>Resources.LoadAll&lt;SacredDefinition&gt;("Sacreds")</c>, and the eight
    /// Sacred assets live in <c>Assets/_Project/Content/Sacreds/</c>, which is not
    /// a <c>Resources</c> folder — so the catalogue loads as an empty array,
    /// <c>FindSacred</c> returns null and every purchase reports a bare failure.
    /// Nothing errors: an empty catalogue is a valid catalogue.</para>
    ///
    /// <para>Injecting beats moving the assets into <c>Resources/</c>: a
    /// <c>Resources</c> folder is pulled into the build whole whether or not
    /// anything reaches it, and "which Sacreds exist" becomes a property of the
    /// folder layout instead of something you can look at. Same argument
    /// <c>ThemeCatalog</c> makes.</para>
    ///
    /// <para><b>Guarded on the backend being local</b>, exactly like
    /// <c>CharacterSelect.SeedLocalEntitlements</c>: this is the stand-in server
    /// being handed its own data, not the client telling a real server what it
    /// sells. Point the composition root at PlayFab and this does nothing.</para>
    /// </summary>
    void SeedLocalCatalogue()
    {
        LocalBackend local = Backend.Current as LocalBackend;
        if (local == null || sacreds == null || sacreds.Length == 0) return;
        local.SetCatalogue(sacreds);
    }

    void BuildGrid()
    {
        foreach (Button b in tiles)
        {
            if (b != null) Destroy(b.gameObject);
        }
        tiles.Clear();
        if (grid == null || sacredTemplate == null) return;

        foreach (SacredDefinition s in sacreds)
        {
            if (s == null) continue;
            Button b = Instantiate(sacredTemplate, grid);
            b.gameObject.SetActive(true);
            b.name = "Sacred_" + s.sacredId;

            TMP_Text label = b.GetComponentInChildren<TMP_Text>();
            if (label != null) label.text = s.displayName + "\n" + PriceText(s);

            SacredDefinition captured = s;
            b.onClick.AddListener(delegate { OnTile(captured); });
            tiles.Add(b);
        }
    }

    /// <summary>"500 ◆", "12 ✦", "FREE". One place, so a tile and a confirm dialog
    /// cannot quote different prices for the same Sacred.</summary>
    public static string PriceText(SacredDefinition s)
    {
        return s == null ? "" : AmountText(s.currency, s.price);
    }

    /// <summary>
    /// An arbitrary amount in a currency's own glyph. Split out of
    /// <see cref="PriceText"/> when bulk arrived: a bundle's total is not the
    /// Sacred's price, and formatting it separately is how a confirm dialog ends
    /// up quoting a different currency than the tile it came from.
    /// </summary>
    public static string AmountText(CurrencyKind currency, int amount)
    {
        switch (currency)
        {
            case CurrencyKind.Free: return "FREE";
            case CurrencyKind.Embers: return amount + " " + EmberGlyph;
            case CurrencyKind.SoulCrystals: return amount + " " + CrystalGlyph;
            case CurrencyKind.Shards: return amount + " " + ShardGlyph;
            default: return amount.ToString();
        }
    }

    /// <summary>
    /// What the confirm dialog says for a bundle.
    ///
    /// <para>It states the REAL total — <paramref name="paidCount"/> × price — and
    /// the real delivery, from
    /// <see cref="DropTable.TotalSacredsForPurchase"/>. §15.12.3's rule is "never
    /// one-click a premium spend", and a dialog that shows the unit price while
    /// charging ten times it is a one-click premium spend wearing a confirmation.</para>
    ///
    /// <para>Static and pure so the number it quotes can be checked in EditMode
    /// against the number the backend charges.</para>
    /// </summary>
    public static string BulkConfirmText(SacredDefinition s, int paidCount)
    {
        if (s == null) return "";
        int total = s.price * paidCount;
        int deliver = DropTable.TotalSacredsForPurchase(paidCount);
        int free = deliver - paidCount;
        return "Open " + paidCount + " × " + s.displayName + " for " +
               AmountText(s.currency, total) + "?\n" +
               "You receive " + deliver + (free > 0 ? " — " + free + " free." : ".");
    }

    /// <summary>What the confirm dialog says for a single open.</summary>
    public static string SingleConfirmText(SacredDefinition s)
    {
        if (s == null) return "";
        return "Open " + s.displayName + " for " + PriceText(s) + "?";
    }

    /// <summary>
    /// §15.12.3: "Purchase confirmation for anything costing Soul Crystals. Never
    /// one-click a premium spend."
    ///
    /// <para>Keyed on the CURRENCY, not on a price threshold or an isPremium flag.
    /// Soul Crystals are the only thing bought with real money (§15.12.1), so
    /// spending them is the only irreversible act in the store — and a rule
    /// written as "price &gt; 20" would silently stop protecting anything the day
    /// a Sacred is repriced.</para>
    /// </summary>
    public static bool NeedsConfirmation(SacredDefinition s)
    {
        return s != null && s.currency == CurrencyKind.SoulCrystals;
    }

    void OnTile(SacredDefinition s)
    {
        if (opening != null && opening.IsPlaying) return;
        if (openTask != null && !openTask.IsCompleted) return;
        if (bulkTask != null && !bulkTask.IsCompleted) return;

        // Captured HERE, not read again in ConfirmYes. The toggle is still on
        // screen behind the dialog, so re-reading it at confirm time would let a
        // click land on a bundle the dialog never priced.
        bool asBulk = bulkArmed;

        if (NeedsConfirmation(s))
        {
            pendingConfirm = s;
            pendingConfirmIsBulk = asBulk;
            if (confirmPanel != null) confirmPanel.SetActive(true);
            if (confirmText != null)
                confirmText.text = asBulk ? BulkConfirmText(s, BulkPaidCount) : SingleConfirmText(s);
            return;
        }
        if (asBulk) BeginBulk(s); else Begin(s);
    }

    void ConfirmYes()
    {
        SacredDefinition s = pendingConfirm;
        bool asBulk = pendingConfirmIsBulk;
        ConfirmNo();
        if (s == null) return;
        if (asBulk) BeginBulk(s); else Begin(s);
    }

    void ConfirmNo()
    {
        pendingConfirm = null;
        pendingConfirmIsBulk = false;
        if (confirmPanel != null) confirmPanel.SetActive(false);
    }

    void Begin(SacredDefinition s)
    {
        Say("Opening " + s.displayName + "…");
        // The server rolls, allocates the serial and charges. The reel that
        // follows is theatre over this result (§18.4).
        openTask = Backend.Current.OpenSacred(UserId, s.sacredId);
    }

    /// <summary>
    /// §15.4's bundle. Note what is NOT here: any check that the player can afford
    /// ten of them. Affordability is atomic on the server side and a client copy of
    /// that rule could disagree with it (§18.4) — the difference being that with a
    /// bundle, disagreeing means telling the player they bought eleven things they
    /// did not get.
    /// </summary>
    void BeginBulk(SacredDefinition s)
    {
        Say("Opening " + BulkPaidCount + " × " + s.displayName + "…");
        bulkTask = Backend.Current.OpenSacredBulk(UserId, s.sacredId, BulkPaidCount);
    }

    void ToggleBulk()
    {
        if (opening != null && opening.IsPlaying) return;
        bulkArmed = !bulkArmed;
        PaintBulkToggle();
    }

    void PaintBulkToggle()
    {
        if (bulkToggleLabel == null) return;
        bulkToggleLabel.text = bulkArmed
            ? "BUY ×" + BulkPaidCount + "  →  " +
              DropTable.TotalSacredsForPurchase(BulkPaidCount) + " SACREDS"
            : "BUY ×1";
    }

    /// <summary>
    /// The signed-in player. A single stand-in id while the backend is local;
    /// Phase 10 replaces this with the authenticated one and nothing else here
    /// changes.
    /// </summary>
    public const string UserId = "local";

    void Update()
    {
        if (openTask != null && openTask.IsCompleted)
        {
            Task<SacredResult> done = openTask;
            openTask = null;

            if (done.Status != TaskStatus.RanToCompletion)
            {
                Say("The store could not reach the backend.");
                Debug.LogWarning("[Store] OpenSacred faulted: " +
                                 (done.Exception == null ? "(no exception)" : done.Exception.Message));
            }
            else
            {
                Present(done.Result);
            }
        }

        if (bulkTask != null && bulkTask.IsCompleted)
        {
            Task<SacredResult[]> done = bulkTask;
            bulkTask = null;

            if (done.Status != TaskStatus.RanToCompletion)
            {
                Say("The store could not reach the backend.");
                Debug.LogWarning("[Store] OpenSacredBulk faulted: " +
                                 (done.Exception == null ? "(no exception)" : done.Exception.Message));
            }
            else
            {
                PresentBulk(done.Result);
            }
        }

        if (profileTask != null && profileTask.IsCompleted)
        {
            Task<PlayerProfile> done = profileTask;
            profileTask = null;
            if (done.Status == TaskStatus.RanToCompletion)
            {
                profile = done.Result;
                PaintHeader();
            }
        }
    }

    /// <summary>
    /// Render whichever answer came back.
    ///
    /// <para><b>The three failure flags are reported separately on purpose.</b>
    /// <see cref="SacredResult"/>'s own doc records why: collapsing them made a
    /// second free daily claim report "Not enough Free", which sent the player
    /// looking for money when the real condition was a timer. A wrong reason is
    /// worse than a bare failure.</para>
    /// </summary>
    void Present(SacredResult r)
    {
        if (r == null) { Say("The open returned nothing."); return; }

        if (!r.success)
        {
            if (r.soldOut) Say("SOLD OUT — every design in this Sacred has retired. You were not charged.");
            else if (r.onCooldown) Say("Already claimed today. Resets at midnight UTC.");
            else if (r.insufficientFunds) Say("Not enough currency.");
            else Say(string.IsNullOrEmpty(r.message) ? "The open failed." : r.message);
            Refresh();
            return;
        }

        Say("");
        if (opening != null)
        {
            opening.Play(r, DesignFor(r.item), Refresh);
        }
        else
        {
            // No animator wired is a build error, not a reason to swallow the
            // pull — the item was minted and charged for either way.
            Debug.LogError("[Store] no SacredOpening assigned, so a real pull was minted with " +
                           "no reveal. The item is in the inventory; the player saw nothing.");
            Refresh();
        }
    }

    /// <summary>
    /// Render a §15.4 bundle.
    ///
    /// <para><b>A bundle that delivered SOMETHING and a bundle that delivered
    /// NOTHING are different events and are reported differently.</b> A refusal
    /// comes back as a one-element array carrying the reason, and that goes through
    /// the same <see cref="Present"/> the single path uses so a bulk refusal cannot
    /// grow its own second vocabulary for "not enough currency". A partial
    /// delivery — the §15.5 sold-out tail — still plays what WAS delivered and
    /// says why the rest was not, because swallowing the tail would leave the
    /// player looking at eight cards after paying for eleven with nothing on
    /// screen accounting for the difference.</para>
    /// </summary>
    void PresentBulk(SacredResult[] results)
    {
        if (results == null || results.Length == 0)
        {
            // The backend contract forbids this, so it is a bug rather than a state.
            Debug.LogError("[Store] OpenSacredBulk returned an empty array. A refusal must " +
                           "carry its reason in a single result — an empty array is " +
                           "indistinguishable from success at every call site.");
            Say("The bulk open returned nothing.");
            Refresh();
            return;
        }

        SacredOpening.BulkTally tally = SacredOpening.Tally(results);
        if (tally.delivered == 0)
        {
            Present(results[0]);      // one reason, said in the store's one vocabulary
            return;
        }

        SacredResult tail = results[results.Length - 1];
        if (tail != null && !tail.success)
        {
            Say(string.IsNullOrEmpty(tail.message)
                ? "Part of the bundle could not be delivered."
                : tail.message);
        }
        else
        {
            Say("");
        }

        if (opening != null)
        {
            opening.PlayBulk(results, DesignFor, Refresh);
        }
        else
        {
            Debug.LogError("[Store] no SacredOpening assigned, so " + tally.delivered +
                           " real pulls were minted with no reveal. The items are in the " +
                           "inventory; the player saw nothing.");
            Refresh();
        }
    }

    public SkinDesign DesignFor(SkinInstance item)
    {
        if (item == null) return null;
        foreach (SkinDesign d in designs)
        {
            if (d != null && d.designId == item.designId) return d;
        }
        return null;
    }

    void Refresh()
    {
        profileTask = Backend.Current.LoadProfile(UserId);
    }

    void PaintHeader()
    {
        if (profile == null) return;
        if (embersLabel != null) embersLabel.text = EmberGlyph + " " + profile.embers.ToString("n0");
        if (crystalsLabel != null) crystalsLabel.text = CrystalGlyph + " " + profile.soulCrystals.ToString("n0");
        if (shardsLabel != null) shardsLabel.text = ShardGlyph + " " + profile.shards.ToString("n0");
    }

    void Say(string s)
    {
        if (statusLabel != null) statusLabel.text = s;
    }
}
