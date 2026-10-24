using System;
using System.Collections;
using System.Collections.Generic;
using TMPro;
using UnityEngine;
using UnityEngine.UI;

/// <summary>
/// The §15.8.1 opening: the reel, the near-miss, the settle and the reveal.
///
/// <para>§15.8.1 opens with "this is the product, treat it that way" — it is the
/// single most-repeated piece of UI in the game.</para>
///
/// <para><b>The outcome is NOT decided here.</b> <see cref="Play"/> takes a
/// <see cref="SacredResult"/> the backend already produced and animates toward
/// it. §18.4: "the reel is theatre played over a known outcome. Never let the
/// animation determine the item." Nothing in this file rolls, and
/// <see cref="SacredReel"/> — which does the arranging — has no random source
/// except a caller-supplied seed that decides filler cards only.</para>
///
/// <para><b>The cards are pooled</b> (§18.6). Thirty instantiations per open is a
/// stutter, and the opening is the thing players repeat most.</para>
/// </summary>
public class SacredOpening : MonoBehaviour
{
    [Header("Reel")]
    [Tooltip("Cards are laid out as children of this and slid horizontally.")]
    public RectTransform reelRoot;

    /// <summary>
    /// Everything the reel apparatus draws: the clipping viewport AND the centre
    /// marker. Hiding <see cref="reelRoot"/> alone hides only the CARDS -- the
    /// viewport carries an Image at alpha 0.92 and the marker is a sibling above
    /// it, so both stayed on screen permanently, a dark band and a yellow line
    /// sitting over the idle store covering four tiles' prices and the odds panel.
    /// Nothing errored; the reel was correctly hidden and its backdrop was not.
    /// Null on scenes built before this existed, which is why Hide falls back.
    /// </summary>
    public RectTransform reelStage;

    [Tooltip("One card, disabled, cloned into the pool. Its width sets the pitch.")]
    public RectTransform cardTemplate;

    [Tooltip("Gap between cards, in the same units as the template's width.")]
    public float cardSpacing = 12f;

    [Header("Reveal")]
    public CanvasGroup revealGroup;
    public Image revealBorder;
    public TMP_Text revealSerial;
    public TMP_Text revealName;
    public TMP_Text revealRarity;
    public TMP_Text revealFloat;
    public TMP_Text revealVariant;
    public TMP_Text revealFirstOfItsKind;

    [Header("Escalation")]
    [Tooltip("Dimmer behind the card. SCT desaturates the screen to near-black.")]
    public Image screenDim;
    public CanvasGroup rgbTakeover;
    public RectTransform shakeRoot;

    [Header("Bulk summary (§15.8.1 — skip-to-summary)")]
    [Tooltip("Shown after a bulk open. Null degrades to a Debug.Log rather than to silence.")]
    public CanvasGroup bulkSummaryGroup;
    public TMP_Text bulkSummaryText;

    [Header("Audio (optional — silent if unassigned)")]
    public AudioSource tickSource;
    public AudioSource stingSource;

    readonly List<RectTransform> pool = new List<RectTransform>();
    readonly List<Image> poolFill = new List<Image>();
    readonly List<TMP_Text> poolLabel = new List<TMP_Text>();

    bool playing;
    bool revealing;
    bool bulk;
    float noSkipUntil;

    /// <summary>
    /// True while an open is on screen. The store must not start another.
    ///
    /// <para>It covers a BULK bundle as a whole, not just the reveal currently
    /// running. <see cref="RunBulk"/> plays the individual opens as nested
    /// coroutines, so <c>playing</c> drops to false between them — a store gated
    /// on that alone could start a second bundle in the gap between card four and
    /// card five, and the two would interleave on screen.</para>
    /// </summary>
    public bool IsPlaying { get { return playing || bulk; } }

    /// <summary>True while a bulk bundle is being played out or summarised.</summary>
    public bool IsBulk { get { return bulk; } }

    /// <summary>True once the reel has settled and the card is up.</summary>
    public bool IsRevealing { get { return revealing; } }

    /// <summary>
    /// True when the player is allowed to dismiss. §15.8.1: an RGB takeover
    /// cannot be skipped for ~2 s.
    ///
    /// <para><b>It requires the reveal to be UP, not merely an open to be in
    /// progress.</b> The first version was <c>playing &amp;&amp; now >=
    /// noSkipUntil</c>, and <c>noSkipUntil</c> is only assigned after the reel
    /// settles — so during the entire 5-6 second spin it compared against 0 and
    /// answered TRUE. Anything asking "may the player dismiss this yet?" got yes
    /// while the reel was still moving. Caught by a PlayMode test whose wait loop
    /// believed it and measured the card mid-spin, 12.5 cards short.</para>
    /// </summary>
    public bool CanSkipNow { get { return revealing && Time.unscaledTime >= noSkipUntil; } }

    void Awake()
    {
        BuildPool();
        Hide();
        HideSummary();
    }

    void BuildPool()
    {
        if (cardTemplate == null || reelRoot == null || pool.Count > 0) return;
        cardTemplate.gameObject.SetActive(false);

        for (int i = 0; i < SacredReel.CardCount; i++)
        {
            RectTransform c = Instantiate(cardTemplate, reelRoot);
            c.gameObject.SetActive(true);
            c.name = "Card" + i;

            // Pin every card to the CENTRE of reelRoot and give it a centre pivot.
            //
            // This is not tidying. The first version left the cards on whatever
            // anchors the scene builder happened to author and slid reelRoot
            // itself, so where the winner ended up depended on reelRoot's rect
            // width and pivot — which this component does not own. It landed
            // 825 world units off, about thirteen card-widths, and every EditMode
            // test still passed because the arithmetic was never the problem.
            // Anchoring here makes card i's offset mean the same thing whatever
            // the builder did.
            c.anchorMin = c.anchorMax = new Vector2(0.5f, 0.5f);
            c.pivot = new Vector2(0.5f, 0.5f);

            pool.Add(c);
            poolFill.Add(c.GetComponent<Image>());
            poolLabel.Add(c.GetComponentInChildren<TMP_Text>());
        }
    }

    /// <summary>
    /// Put card <paramref name="i"/> where it belongs when the strip has scrolled
    /// <paramref name="scrolled"/> cards.
    ///
    /// <para>The SCROLL LIVES ON THE CARDS, not on reelRoot. At
    /// <c>scrolled == WinnerIndex</c> the winner's offset is exactly zero, i.e.
    /// dead on reelRoot's own centre — so "the reel landed correctly" reduces to
    /// "reelRoot's centre is the marker", which is a single fact the scene can be
    /// checked for, instead of an interaction between a rect width, two pivots and
    /// an anchoredPosition.</para>
    /// </summary>
    void Place(int i, float scrolled)
    {
        pool[i].anchoredPosition = new Vector2((i - scrolled) * Pitch, 0f);
    }

    float Pitch
    {
        get { return (cardTemplate != null ? cardTemplate.rect.width : 160f) + cardSpacing; }
    }

    void Hide()
    {
        if (revealGroup != null) { revealGroup.alpha = 0f; revealGroup.blocksRaycasts = false; }
        if (rgbTakeover != null) { rgbTakeover.alpha = 0f; rgbTakeover.blocksRaycasts = false; }
        if (screenDim != null) screenDim.color = new Color(0f, 0f, 0f, 0f);
        if (reelStage != null) reelStage.gameObject.SetActive(false);
        else if (reelRoot != null) reelRoot.gameObject.SetActive(false);
    }

    void HideSummary()
    {
        if (bulkSummaryGroup != null)
        {
            bulkSummaryGroup.alpha = 0f;
            bulkSummaryGroup.blocksRaycasts = false;
        }
    }

    /// <summary>
    /// Animate an already-decided open. <paramref name="onDone"/> fires when the
    /// player dismisses the reveal (or immediately on a failed result).
    /// </summary>
    /// <param name="result">what the backend returned. NOT modified.</param>
    /// <param name="design">the design behind the item, for its name and weapon.
    /// May be null; the card degrades to the design id rather than showing nothing.</param>
    public void Play(SacredResult result, SkinDesign design, Action onDone)
    {
        // `bulk` counts as busy too: a bundle plays its individual opens as nested
        // coroutines, so `playing` is momentarily false BETWEEN them, and a single
        // open started in that gap would run over the bundle's next reveal.
        if (playing || bulk)
        {
            Debug.LogWarning("[Sacred] an open is already on screen; ignoring the new one. " +
                             "The caller should be gated on IsPlaying — starting a second " +
                             "reel would leave the first one's reveal on screen over it.");
            return;
        }
        if (result == null || !result.success || result.item == null)
        {
            // A failed open is not an animation. Charging is the backend's job and
            // it did not happen, so there is nothing to reveal.
            if (onDone != null) onDone();
            return;
        }
        StartCoroutine(Run(result, design, onDone));
    }

    IEnumerator Run(SacredResult result, SkinDesign design, Action onDone)
    {
        playing = true;
        revealing = false;
        BuildPool();

        DropTable.Outcome outcome = OutcomeOf(result.item);

        // The seed comes from the item's own id, so the same open always draws
        // the same strip. That matters for a bug report: "it landed wrong" is
        // reproducible instead of being a story about a random reel.
        int seed = result.item.itemId != null ? result.item.itemId.GetHashCode() : 0;
        SacredReel reel = SacredReel.Build(outcome, seed);

        Paint(reel, result.item);

        if (reelStage != null) reelStage.gameObject.SetActive(true);
        if (reelRoot != null) reelRoot.gameObject.SetActive(true);
        if (revealGroup != null) { revealGroup.alpha = 0f; revealGroup.blocksRaycasts = false; }

        // ---- beat 3: the spin -------------------------------------------
        int ticked = 0;
        float t = 0f;
        while (t < reel.SpinSeconds)
        {
            t += Time.unscaledDeltaTime;
            float scrolled = reel.CardsScrolledAt(t);
            for (int i = 0; i < pool.Count; i++) Place(i, scrolled);

            int now = reel.TicksAt(t);
            if (now > ticked)
            {
                ticked = now;
                if (tickSource != null)
                {
                    // Pitch rises as it slows — the gaps widen in time, so the
                    // ear reads deceleration even without watching.
                    tickSource.pitch = Mathf.Lerp(0.85f, 1.6f, t / Mathf.Max(reel.SpinSeconds, 0.0001f));
                    tickSource.Play();
                }
            }
            yield return null;
        }

        // Land exactly on the winner. Leaving this to the last frame's t would
        // stop a fraction of a card short and the winner would sit visibly
        // off-centre — the one visual error people screenshot.
        for (int i = 0; i < pool.Count; i++) Place(i, SacredReel.WinnerIndex);

        // ---- beat 5: settle ---------------------------------------------
        if (stingSource != null) stingSource.Play();
        yield return Shake(SacredReel.ShakeFor(outcome));

        // ---- beat 6: reveal ---------------------------------------------
        ShowReveal(result.item, design, outcome);
        noSkipUntil = Time.unscaledTime + SacredReel.NoSkipSecondsFor(outcome);
        revealing = true;

        while (!CanSkipNow) yield return null;
        while (!Dismissed()) yield return null;

        Hide();
        revealing = false;
        playing = false;
        if (onDone != null) onDone();
    }

    /// <summary>
    /// Any click or key dismisses. Reads GameInput rather than UnityEngine.Input:
    /// activeInputHandler is 1, so the legacy API throws.
    /// </summary>
    static bool Dismissed()
    {
        return GameInput.AnyDismissPressed();
    }

    void Paint(SacredReel reel, SkinInstance winner)
    {
        for (int i = 0; i < pool.Count && i < reel.Cards.Length; i++)
        {
            SacredReel.Card c = reel.Cards[i];
            // Position is Place()'s job and only Place()'s — setting it here too
            // was how the two disagreed.
            if (poolFill[i] != null) poolFill[i].color = SacredReel.ColorFor(c.rarity);
            if (poolLabel[i] != null)
                poolLabel[i].text = c.isWinner ? winner.designId : c.rarity.ToString();
        }
        for (int i = 0; i < pool.Count; i++) Place(i, 0f);
    }

    /// <summary>
    /// Abandon an open in progress and return to a usable state.
    ///
    /// <para>Needed because <c>playing</c> is cleared at the END of the coroutine.
    /// Anything that stops the coroutine without letting it finish — a scene
    /// unload, a test, <c>StopAllCoroutines</c> from elsewhere — used to leave
    /// this component permanently "busy", after which every later open was
    /// silently refused with a console warning and the store looked dead. Found
    /// by a PlayMode test whose second case could not start because its first
    /// case had stopped the coroutine.</para>
    ///
    /// <para>It does NOT undo the pull. The item was minted and charged for by
    /// the backend before the animation began (§18.4); cancelling the theatre
    /// cannot and must not reach that.</para>
    /// </summary>
    public void Cancel()
    {
        StopAllCoroutines();
        Hide();
        HideSummary();
        revealing = false;
        playing = false;
        bulk = false;
        noSkipUntil = 0f;
    }

    void OnDisable()
    {
        if (playing || bulk) Cancel();
    }

    // ------------------------------------------------------------------ bulk

    /// <summary>
    /// §15.8.1: "Bulk opens (the 10+1) must support a skip-to-summary that still
    /// plays the full reveal for anything serialized or above."
    ///
    /// <para>Walks an already-minted bundle in the order the backend delivered it,
    /// plays the whole §15.8.1 opening for every result
    /// <see cref="SacredReel.CanSkipInBulk"/> says NO to, and accumulates the rest
    /// into one summary shown at the end.</para>
    ///
    /// <para><b>What gets summarised is a property of the OUTCOME, not a
    /// preference and not a setting</b> — <c>CanSkipInBulk</c> is the single
    /// address for that rule and this method does not second-guess it. A bulk of
    /// eleven that happens to contain an RGB still stops and plays the takeover.</para>
    ///
    /// <para><paramref name="designFor"/> is a lookup rather than an array so this
    /// stays ignorant of how the store indexes designs; null is allowed and the
    /// reveal degrades to the design id, exactly as <see cref="Play"/> does.</para>
    /// </summary>
    public void PlayBulk(IList<SacredResult> results, Func<SkinInstance, SkinDesign> designFor,
                         Action onDone)
    {
        if (playing || bulk)
        {
            Debug.LogWarning("[Sacred] a bundle or an open is already on screen; ignoring the " +
                             "new one. The caller should be gated on IsPlaying.");
            return;
        }
        if (results == null || results.Count == 0)
        {
            if (onDone != null) onDone();
            return;
        }
        StartCoroutine(RunBulk(results, designFor, onDone));
    }

    IEnumerator RunBulk(IList<SacredResult> results, Func<SkinInstance, SkinDesign> designFor,
                        Action onDone)
    {
        bulk = true;
        HideSummary();

        for (int i = 0; i < results.Count; i++)
        {
            SacredResult r = results[i];
            if (r == null || !r.success || r.item == null) continue;   // reported in the summary
            if (SacredReel.CanSkipInBulk(OutcomeOf(r.item))) continue; // summarised, not played

            SkinDesign design = designFor != null ? designFor(r.item) : null;
            // Nested, not Play(): Play refuses while `bulk` is set, and it must —
            // the refusal is what stops a second BUNDLE starting mid-bundle.
            yield return StartCoroutine(Run(r, design, null));
        }

        ShowSummary(results);

        // One frame before listening, then a floor on how long the summary is up.
        // Dismissal is edge-triggered (WasPressedThisFrame), so the press that
        // closed the LAST reveal is still "pressed this frame" when this loop first
        // runs — without these the summary would flash and vanish on the same input
        // that dismissed the card before it, and the player would never see it.
        yield return null;
        float floorUntil = Time.unscaledTime + 0.4f;
        while (Time.unscaledTime < floorUntil) yield return null;
        while (!Dismissed()) yield return null;

        HideSummary();
        bulk = false;
        if (onDone != null) onDone();
    }

    void ShowSummary(IList<SacredResult> results)
    {
        string text = BulkSummaryText(results);
        if (bulkSummaryText != null) bulkSummaryText.text = text;
        if (bulkSummaryGroup != null)
        {
            bulkSummaryGroup.alpha = 1f;
            bulkSummaryGroup.blocksRaycasts = true;
        }
        if (bulkSummaryText == null || bulkSummaryGroup == null)
        {
            // Loud rather than silent. A bulk open with no summary panel wired
            // looks, from the player's chair, exactly like a bulk open that
            // delivered nothing.
            Debug.LogWarning("[Sacred] no bulk summary panel wired, so " + results.Count +
                             " opens were summarised to the console only:\n" + text);
        }
    }

    /// <summary>What a bundle contained, counted the way §15.8.1 splits it.</summary>
    public struct BulkTally
    {
        /// <summary>Results that carried a minted item.</summary>
        public int delivered;
        /// <summary>Results that did not — a sold-out tail, or a refusal.</summary>
        public int failed;
        /// <summary>Delivered items rolled into the summary (§15.8.1: NonSerialized only).</summary>
        public int summarised;
        /// <summary>Delivered items that stopped the bundle and played in full.</summary>
        public int revealed;
    }

    /// <summary>
    /// Count a bundle without animating it. Pure and static so the
    /// skip-to-summary RULE is testable in EditMode — the coroutine that acts on
    /// it needs a scene, a canvas and real time, which is precisely the kind of
    /// thing this project has repeatedly shipped untested.
    /// </summary>
    public static BulkTally Tally(IList<SacredResult> results)
    {
        var t = new BulkTally();
        if (results == null) return t;
        for (int i = 0; i < results.Count; i++)
        {
            SacredResult r = results[i];
            if (r == null || !r.success || r.item == null) { t.failed++; continue; }
            t.delivered++;
            if (SacredReel.CanSkipInBulk(OutcomeOf(r.item))) t.summarised++;
            else t.revealed++;
        }
        return t;
    }

    /// <summary>
    /// The summary panel's text: the totals, then a line for every pull that was
    /// worth stopping for.
    ///
    /// <para>The notable pulls are listed INDIVIDUALLY rather than counted by
    /// tier. A serial is the thing that is unique in the world (§15.8.1), so
    /// "2 Limited" throws away the only part of a serialized pull the player
    /// cares about.</para>
    /// </summary>
    public static string BulkSummaryText(IList<SacredResult> results)
    {
        BulkTally t = Tally(results);
        var sb = new System.Text.StringBuilder();
        sb.Append("BULK OPEN — ").Append(t.delivered).Append(" SACRED");
        if (t.delivered != 1) sb.Append('S');
        sb.Append('\n');
        sb.Append(t.summarised).Append(" standard, ").Append(t.revealed).Append(" revealed");

        if (t.failed > 0)
        {
            sb.Append('\n').Append(t.failed)
              .Append(" not delivered — see the store's message for why.");
        }

        if (results != null)
        {
            for (int i = 0; i < results.Count; i++)
            {
                SacredResult r = results[i];
                if (r == null || !r.success || r.item == null) continue;
                if (SacredReel.CanSkipInBulk(OutcomeOf(r.item))) continue;
                sb.Append('\n')
                  .Append(r.item.rarity.ToString().ToUpperInvariant())
                  .Append("  ")
                  .Append(r.item.IsSerialized ? r.item.DisplaySerial() : r.item.designId);
                if (r.item.IsFirstOfItsKind) sb.Append("  FIRST OF ITS KIND");
            }
        }
        return sb.ToString();
    }

    /// <summary>
    /// Recover the outcome tier from the minted item.
    ///
    /// <para>Derived from the item rather than passed alongside it: the item is
    /// what the backend actually issued, and a separately-carried outcome enum
    /// is a second source of truth that can disagree with the thing in the
    /// player's inventory.</para>
    /// </summary>
    public static DropTable.Outcome OutcomeOf(SkinInstance item)
    {
        if (item == null) return DropTable.Outcome.NonSerialized;
        if (item.variant == SkinVariant.RGB) return DropTable.Outcome.RGB;
        if (item.variant == SkinVariant.SCT) return DropTable.Outcome.SCT;
        return item.IsSerialized ? DropTable.Outcome.Serialized : DropTable.Outcome.NonSerialized;
    }

    void ShowReveal(SkinInstance item, SkinDesign design, DropTable.Outcome outcome)
    {
        if (revealGroup != null) { revealGroup.alpha = 1f; revealGroup.blocksRaycasts = true; }

        Color tier = SacredReel.ColorFor(item.rarity);
        if (revealBorder != null) revealBorder.color = tier;

        // §15.8.1's priority order. 1 — the serial, largest element on the card,
        // because it is the thing that is unique in the world.
        if (revealSerial != null)
        {
            revealSerial.text = item.IsSerialized ? item.DisplaySerial() : "";
            revealSerial.gameObject.SetActive(item.IsSerialized);
        }

        // 2 — skin name and weapon.
        if (revealName != null)
        {
            revealName.text = design != null
                ? design.displayName + "  ·  " + design.weaponName
                : item.designId;
        }

        // 3 — rarity, in the tier's colour.
        if (revealRarity != null)
        {
            revealRarity.text = item.rarity.ToString().ToUpperInvariant();
            revealRarity.color = tier;
        }

        // 4 — float band AND the raw float. The band alone is not enough: two
        // Factory New items are not the same item, and the raw number is what
        // trades on.
        if (revealFloat != null)
        {
            revealFloat.text = string.Format("{0} ({1:0.0000})",
                                             FloatWear.ShortName(item.Band), item.wear);
        }

        // 5 — variant badge.
        if (revealVariant != null)
        {
            bool has = item.variant != SkinVariant.None;
            revealVariant.gameObject.SetActive(has);
            revealVariant.text = has ? item.variant.ToString().ToUpperInvariant() : "";
        }

        // §15.5: serial #1 is a one-time event in the game's lifetime.
        if (revealFirstOfItsKind != null)
        {
            revealFirstOfItsKind.gameObject.SetActive(item.IsFirstOfItsKind);
            revealFirstOfItsKind.text = "FIRST OF ITS KIND";
        }

        // Escalation. Each tier has to be unmistakably different, not louder.
        if (screenDim != null)
        {
            float a = outcome == DropTable.Outcome.SCT ? 0.92f
                    : outcome == DropTable.Outcome.RGB ? 0.75f
                    : outcome == DropTable.Outcome.Serialized ? 0.55f : 0.35f;
            screenDim.color = new Color(0f, 0f, 0f, a);
        }
        if (rgbTakeover != null)
        {
            bool rgb = outcome == DropTable.Outcome.RGB;
            rgbTakeover.alpha = rgb ? 1f : 0f;
            rgbTakeover.blocksRaycasts = rgb;
        }
    }

    IEnumerator Shake(float strength)
    {
        if (shakeRoot == null || strength <= 0f) yield break;
        Vector2 home = shakeRoot.anchoredPosition;
        float dur = 0.28f + strength * 0.3f;
        float t = 0f;
        while (t < dur)
        {
            t += Time.unscaledDeltaTime;
            float decay = 1f - (t / dur);
            shakeRoot.anchoredPosition = home + new Vector2(
                (UnityEngine.Random.value - 0.5f) * 34f * strength * decay,
                (UnityEngine.Random.value - 0.5f) * 34f * strength * decay);
            yield return null;
        }
        shakeRoot.anchoredPosition = home;
    }

    void Update()
    {
        if (rgbTakeover != null && rgbTakeover.alpha > 0f && revealBorder != null)
        {
            // §15.8.1: RGB gets an animated rainbow border.
            revealBorder.color = Color.HSVToRGB(Mathf.Repeat(Time.unscaledTime * 0.35f, 1f), 0.75f, 1f);
        }
    }
}
