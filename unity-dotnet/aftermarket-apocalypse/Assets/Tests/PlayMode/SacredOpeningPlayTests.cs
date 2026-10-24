using System.Collections;
using System.Collections.Generic;
using System.IO;
using NUnit.Framework;
using TMPro;
using UnityEngine;
using UnityEngine.SceneManagement;
using UnityEngine.TestTools;
using UnityEngine.UI;

/// <summary>
/// Plays the §15.8.1 opening for real and MEASURES where the winner ends up.
///
/// <para>Everything about the reel that <c>SacredOpeningTests</c> checks is
/// arithmetic on a pure class. That arithmetic can be perfectly right while the
/// thing on screen is wrong, because the card's screen position depends on the
/// anchors, pivots and parent sizes the scene builder chose — none of which the
/// pure class knows about. This project has been caught by exactly that gap
/// repeatedly: 111 tests green while the player stood inside a wall, six props
/// with valid triangle counts that were all sheets, a horde that would have
/// moonwalked.</para>
///
/// <para>So this one loads the real <c>Store.unity</c>, drives a real open with a
/// fabricated backend result, waits for the spin, and compares the winning card's
/// screen position against the centre marker's. It also writes a PNG, because a
/// number that says "centred" and a picture that shows a blank strip look the
/// same in a test report.</para>
/// </summary>
public class SacredOpeningPlayTests
{
    const string k_Scene = "Assets/_Project/Scenes/Store.unity";
    const string k_Shots = "Screenshots/store";

    static SkinInstance FakeItem(SkinVariant variant, int serial, Rarity rarity)
    {
        return new SkinInstance
        {
            itemId = "playmode-" + variant + "-" + serial,
            designId = "starfade_camo",
            serial = serial,
            editionCap = 250,
            wear = 0.0412f,
            variant = variant,
            rarity = rarity,
        };
    }

    [UnitySetUp]
    public IEnumerator LoadStore()
    {
#if UNITY_EDITOR
        if (!File.Exists(k_Scene))
        {
            Assert.Ignore("No Store.unity — run Tools > Aftermarket > Build Store.");
            yield break;
        }
        UnityEditor.SceneManagement.EditorSceneManager.LoadSceneInPlayMode(
            k_Scene, new LoadSceneParameters(LoadSceneMode.Single));
        yield return null;
        yield return null;
#else
        Assert.Ignore("Editor-only: loads a scene by asset path.");
        yield break;
#endif
    }

    /// <summary>
    /// The decisive one. A reel that stops half a card short still passes every
    /// EditMode test in this project, and is the single most visible way this
    /// feature can be wrong.
    /// </summary>
    [UnityTest]
    public IEnumerator Opening_TheWinningCardStopsUnderTheCentreMarker()
    {
        SacredOpening opening = Object.FindFirstObjectByType<SacredOpening>(FindObjectsInactive.Include);
        Assert.IsNotNull(opening, "Store.unity has no SacredOpening.");

        RectTransform marker = FindRect("CentreMarker");
        Assert.IsNotNull(marker, "Store.unity has no CentreMarker, so there is nothing to land on.");

        var result = new SacredResult
        {
            success = true,
            item = FakeItem(SkinVariant.None, 37, Rarity.Limited),
        };

        bool done = false;
        opening.Play(result, null, delegate { done = true; });
        Assert.IsTrue(opening.IsPlaying, "Play() did not start — the reel never ran.");

        // Wait out the spin plus the settle, with a hard ceiling so a hang fails
        // the test instead of the run.
        float spin = SacredReel.SpinSecondsFor(DropTable.Outcome.Serialized);
        float deadline = Time.unscaledTime + spin + 6f;
        while (opening.IsPlaying && !opening.IsRevealing && Time.unscaledTime < deadline)
            yield return null;
        Assert.IsTrue(opening.IsRevealing,
            "the reel never reached its reveal within " + (spin + 6f) + " s.");
        // The shake moves shakeRoot, not the reel, so the cards are already final.
        yield return null;

        RectTransform reelRoot = FindRect("ReelRoot");
        Assert.IsNotNull(reelRoot, "no ReelRoot");

        Transform winner = reelRoot.Find("Card" + SacredReel.WinnerIndex);
        Assert.IsNotNull(winner,
            "the pool has no Card" + SacredReel.WinnerIndex + ". SacredOpening pools " +
            SacredReel.CardCount + " cards and the winner sits at index " +
            SacredReel.WinnerIndex + ".");

        float dx = Mathf.Abs(winner.position.x - marker.position.x);
        float cardWidth = ((RectTransform)winner).rect.width * winner.lossyScale.x;

        yield return Shoot("reel_landed");

        Assert.Less(dx, cardWidth * 0.25f,
            "the winning card settled " + dx.ToString("0.0") + " world units from the centre " +
            "marker, which is more than a quarter of its " + cardWidth.ToString("0.0") + "-unit " +
            "width. It is visibly off-centre. The reel arithmetic is verified in EditMode, so " +
            "the fault is in the scene's anchors/pivots, not in SacredReel.");

        // And it must be the WINNER sitting there, not merely a card.
        SacredReel reel = SacredReel.Build(DropTable.Outcome.Serialized,
                                           result.item.itemId.GetHashCode());
        Assert.IsTrue(reel.Cards[SacredReel.WinnerIndex].isWinner,
            "the card at the landing index is not flagged as the winner.");

        // Cancel(), not StopAllCoroutines(): stopping the coroutine directly
        // leaves `playing` true forever and the NEXT test's Play() is refused.
        // That is how Cancel() came to exist.
        opening.Cancel();
        yield return null;
    }

    /// <summary>
    /// The reveal must actually carry the numbers §15.8.1 lists, with the serial
    /// as the biggest thing on the card. A reveal whose labels are empty renders
    /// as a coloured rectangle and looks like a styling choice.
    /// </summary>
    [UnityTest]
    public IEnumerator Opening_TheRevealShowsTheSerialFloatAndRarity()
    {
        SacredOpening opening = Object.FindFirstObjectByType<SacredOpening>(FindObjectsInactive.Include);
        Assert.IsNotNull(opening);

        var item = FakeItem(SkinVariant.SCT, 1, Rarity.Secret);   // serial #1 -> §15.5
        var result = new SacredResult { success = true, item = item };

        opening.Play(result, null, null);
        float deadline = Time.unscaledTime + SacredReel.SpinSecondsFor(DropTable.Outcome.SCT) + 6f;
        while (opening.IsPlaying && !opening.IsRevealing && Time.unscaledTime < deadline)
            yield return null;
        Assert.IsTrue(opening.IsRevealing, "the SCT open never reached its reveal.");
        yield return null;

        yield return Shoot("reveal_sct_serial1");

        Assert.IsNotNull(opening.revealSerial, "no serial label wired");
        StringAssert.Contains("1", opening.revealSerial.text,
            "the serial label reads '" + opening.revealSerial.text + "' for serial #1 of 250. " +
            "§15.8.1 makes the serial the largest element on the card because it is the thing " +
            "that is unique in the world.");
        StringAssert.Contains("250", opening.revealSerial.text, "the edition cap is missing.");

        Assert.IsNotNull(opening.revealFloat, "no float label wired");
        StringAssert.Contains("0.0412", opening.revealFloat.text,
            "the raw float is missing from '" + opening.revealFloat.text + "'. §15.8.1 asks for " +
            "the band AND the raw number — two Factory New items are not the same item.");

        Assert.IsTrue(opening.revealFirstOfItsKind == null
                      || opening.revealFirstOfItsKind.gameObject.activeSelf,
            "serial #1 did not get its FIRST OF ITS KIND treatment (§15.5: it happens once " +
            "per design in the game's entire lifetime).");

        Assert.IsTrue(opening.revealVariant != null && opening.revealVariant.gameObject.activeSelf,
            "an SCT pull showed no variant badge.");

        opening.Cancel();
        yield return null;
    }

    /// <summary>
    /// The FIRST CLICK. Everything else in this file feeds the animation a
    /// fabricated result; this one goes through the real backend, the way the
    /// store does when a human presses a tile.
    ///
    /// <para>It exists because the store was one line away from being dead on
    /// arrival: <c>LocalBackend.FindSacred</c> falls back to
    /// <c>Resources.LoadAll("Sacreds")</c> and the Sacred assets are not under a
    /// <c>Resources</c> folder, so the catalogue loaded EMPTY and every purchase
    /// returned a bare failure with nothing logged. A test that fabricates its own
    /// SacredResult can never see that — it skips the exact step that was broken.</para>
    /// </summary>
    [UnityTest]
    public IEnumerator Store_TheFreeSacredCanActuallyBeOpenedOnAFreshProfile()
    {
        StoreScreen store = Object.FindFirstObjectByType<StoreScreen>(FindObjectsInactive.Include);
        Assert.IsNotNull(store, "Store.unity has no StoreScreen.");
        Assert.Greater(store.sacreds.Length, 0, "the store has no Sacreds wired.");

        // A throwaway in-memory backend: a real one would spend from — and mint
        // into — the owner's actual collection.json.
        var backend = new LocalBackend();
        backend.UseInMemoryStore();
        backend.SetCatalogue(store.sacreds);
        Backend.Set(backend);

        SacredDefinition free = null;
        foreach (SacredDefinition s in store.sacreds)
        {
            if (s != null && s.currency == CurrencyKind.Free) { free = s; break; }
        }
        Assert.IsNotNull(free, "no free Sacred in the ladder, so a player with 0 Embers has " +
                               "nothing to click on a fresh install.");

        var task = Backend.Current.OpenSacred(StoreScreen.UserId, free.sacredId);
        float deadline = Time.unscaledTime + 5f;
        while (!task.IsCompleted && Time.unscaledTime < deadline) yield return null;
        Assert.IsTrue(task.IsCompleted, "OpenSacred never completed.");

        SacredResult r = task.Result;
        Assert.IsTrue(r.success,
            "opening the free '" + free.sacredId + "' failed on a fresh profile. " +
            "soldOut=" + r.soldOut + " insufficientFunds=" + r.insufficientFunds +
            " onCooldown=" + r.onCooldown + " message='" + r.message + "'. If the message is " +
            "empty the catalogue is probably empty — see StoreScreen.SeedLocalCatalogue.");
        Assert.IsNotNull(r.item, "the open succeeded but minted nothing.");
        Assert.IsFalse(string.IsNullOrEmpty(r.item.itemId), "the minted item has no id.");

        Backend.Set(null);
        yield return null;
    }

    /// <summary>
    /// §15.8.1: "Bulk opens must support a skip-to-summary." Eleven ordinary pulls
    /// must NOT play eleven reels.
    ///
    /// <para>The rule itself is unit-tested on <c>SacredReel.CanSkipInBulk</c>, but
    /// a rule proved on a pure class and a coroutine that honours it are two
    /// different claims — the reel's own 825-unit landing error was exactly that
    /// gap. This drives the real coroutine and TIMES it: eleven full reels would be
    /// about 57 seconds, so reaching the summary inside 10 proves they were
    /// summarised rather than played.</para>
    /// </summary>
    [UnityTest]
    public IEnumerator Opening_ABulkOfOrdinaryPullsGoesStraightToTheSummary()
    {
        SacredOpening opening = Object.FindFirstObjectByType<SacredOpening>(FindObjectsInactive.Include);
        Assert.IsNotNull(opening);
        Assert.IsNotNull(opening.bulkSummaryGroup,
            "no bulk summary group wired, so a bundle would summarise into nothing.");

        var results = new List<SacredResult>();
        for (int i = 0; i < 11; i++)
        {
            results.Add(new SacredResult
            {
                success = true,
                item = FakeItem(SkinVariant.None, 0, Rarity.Common),   // ordinary => skippable
            });
        }

        float oneReel = SacredReel.SpinSecondsFor(DropTable.Outcome.NonSerialized);
        opening.PlayBulk(results, null, null);
        Assert.IsTrue(opening.IsBulk, "PlayBulk did not start.");

        float deadline = Time.unscaledTime + 10f;
        while (opening.bulkSummaryGroup.alpha <= 0f && Time.unscaledTime < deadline)
            yield return null;

        Assert.Greater(opening.bulkSummaryGroup.alpha, 0f,
            "the summary never appeared within 10 s. Eleven ordinary pulls at " +
            oneReel.ToString("0.0") + " s each would be " + (oneReel * 11f).ToString("0") +
            " s, so this bundle is playing every reel instead of summarising them — " +
            "§15.8.1's skip-to-summary is not being honoured.");

        StringAssert.Contains("11", opening.bulkSummaryText.text,
            "the summary does not state how many were opened. Text was: '" +
            opening.bulkSummaryText.text + "'");

        opening.Cancel();
        Assert.IsFalse(opening.IsBulk, "Cancel() left the component stuck in bulk mode.");
        yield return null;
    }

    /// <summary>
    /// The other half of the same rule: anything serialized or above must still
    /// play its full reveal inside a bundle. A skip-to-summary that swallows a
    /// serialized pull would hide the rarest thing the player has ever seen.
    /// </summary>
    [UnityTest]
    public IEnumerator Opening_ABulkStillFullyRevealsAnythingSerialized()
    {
        SacredOpening opening = Object.FindFirstObjectByType<SacredOpening>(FindObjectsInactive.Include);
        Assert.IsNotNull(opening);

        var results = new List<SacredResult>
        {
            new SacredResult { success = true, item = FakeItem(SkinVariant.None, 0, Rarity.Common) },
            new SacredResult { success = true, item = FakeItem(SkinVariant.None, 44, Rarity.Limited) },
            new SacredResult { success = true, item = FakeItem(SkinVariant.None, 0, Rarity.Common) },
        };

        opening.PlayBulk(results, null, null);

        float deadline = Time.unscaledTime
                       + SacredReel.SpinSecondsFor(DropTable.Outcome.Serialized) + 8f;
        while (!opening.IsRevealing && Time.unscaledTime < deadline) yield return null;

        Assert.IsTrue(opening.IsRevealing,
            "the serialized pull in the bundle never reached a reveal — it was summarised away. " +
            "SacredReel.CanSkipInBulk is false for Serialized and the bulk coroutine must " +
            "honour that.");
        StringAssert.Contains("44", opening.revealSerial.text,
            "the reveal that came up is not the serialized item's. Serial label read '" +
            opening.revealSerial.text + "'.");

        opening.Cancel();
        yield return null;
    }

    // ------------------------------------------------------------------ bits

    static RectTransform FindRect(string name)
    {
        foreach (RectTransform r in Object.FindObjectsByType<RectTransform>(
                     FindObjectsInactive.Include, FindObjectsSortMode.None))
        {
            if (r.name == name) return r;
        }
        return null;
    }

    /// <summary>
    /// Grab the frame SYNCHRONOUSLY.
    ///
    /// <para><c>ScreenCapture.CaptureScreenshot</c> is deferred — it queues the
    /// write and returns, and in a batchmode test run the process can finish the
    /// test before the file lands. The first version used it and produced an
    /// empty Screenshots/store/ while every assertion passed, which is the exact
    /// shape of "verified, with no picture" this project keeps being burned by.
    /// <c>CaptureScreenshotAsTexture</c> hands back the pixels there and then,
    /// but only at end of frame — hence the yield.</para>
    /// </summary>
    static IEnumerator Shoot(string name)
    {
        Directory.CreateDirectory(k_Shots);
        string path = Path.Combine(k_Shots, name + ".png");

        // BEST EFFORT, and deliberately not load-bearing.
        //
        // Two capture routes were tried and both have a batchmode problem:
        // CaptureScreenshotAsTexture needs WaitForEndOfFrame, which never
        // resumes in -batchmode and HUNG the whole run; CaptureScreenshot is
        // deferred and can lose the race with the process exiting. So this uses
        // the deferred one, waits a few frames, and REPORTS whether the file
        // landed instead of pretending. The assertions above are the actual
        // proof — they measure RectTransform positions, which do not depend on
        // a frame reaching disk.
        ScreenCapture.CaptureScreenshot(path);
        for (int i = 0; i < 20; i++) yield return null;

        Debug.Log(File.Exists(path)
            ? "[SacredShot] wrote " + path
            : "[SacredShot] NO PNG at " + path + " — batchmode dropped the capture. " +
              "The positional assertions still ran; only the picture is missing.");
    }
}
