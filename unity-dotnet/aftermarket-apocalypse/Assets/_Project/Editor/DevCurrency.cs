using UnityEditor;
using UnityEngine;

/// <summary>
/// Dev-only currency and save management for walking around the store.
///
/// <para>A fresh profile has 0 Embers and 0 Soul Crystals, which is correct —
/// §15.1 says playing is what accrues them — but it means the only thing a new
/// install can click is the free Recruit Sacred, once per day. That is a true
/// state of the game and a useless one to look at.</para>
///
/// <para><b>This is not a rig and nothing depends on it.</b> The lesson from
/// <c>PlaytestRig</c> is that scaffolding which the game needs in order to work
/// becomes load-bearing and then becomes a lie. These are menu items a human
/// invokes on purpose; deleting this file changes nothing about how the game
/// runs.</para>
/// </summary>
public static class DevCurrency
{
    const string k_User = "local";

    [MenuItem("Tools/Aftermarket/Dev — Grant Test Currency")]
    public static void Grant()
    {
        var local = Backend.Current as LocalBackend;
        if (local == null)
        {
            Debug.LogError("[Dev] Backend.Current is not a LocalBackend, so there is no local " +
                           "save to write to. Nothing granted.");
            return;
        }

        // Enough to reach every tier in the §15.8 ladder several times over:
        // the dearest Embers Sacred is 2,000 and the dearest premium is 40 ✦.
        local.DevGrantCurrency(50000, 500, 5000);
        Report("granted 50,000 ◆ / 500 ✦ / 5,000 ◇");
    }

    [MenuItem("Tools/Aftermarket/Dev — Show Profile")]
    public static void Show()
    {
        Report("current profile");
    }

    /// <summary>
    /// Wipe the local save. Separate from Grant so it is never a side effect —
    /// this destroys minted serials, and a serial is permanent by design (§15.2).
    /// </summary>
    [MenuItem("Tools/Aftermarket/Dev — Delete Local Save")]
    public static void Wipe()
    {
        string path = SafeJsonStore.PathFor("collection.json");
        if (!EditorUtility.DisplayDialog(
                "Delete the local collection?",
                "This deletes " + path + ".\n\nEvery item you have minted, every serial and " +
                "every entitlement goes with it. Serials are permanent by design — this is the " +
                "only way to reissue #1 of a design.",
                "Delete", "Cancel"))
            return;

        if (System.IO.File.Exists(path)) System.IO.File.Delete(path);
        Backend.Set(null);            // force a fresh LocalBackend on next use
        Debug.Log("[Dev] deleted " + path + " and reset the backend.");
    }

    static void Report(string what)
    {
        var task = Backend.Current.LoadProfile(k_User);
        if (!task.IsCompleted)
        {
            Debug.Log("[Dev] " + what + " — the profile load did not complete synchronously, " +
                      "so the balance is not shown. That is expected on a real backend.");
            return;
        }
        PlayerProfile p = task.Result;
        Debug.Log("[Dev] " + what + "\n" +
                  "  ◆ Embers        " + p.embers.ToString("n0") + "\n" +
                  "  ✦ Soul Crystals " + p.soulCrystals.ToString("n0") + "\n" +
                  "  ◇ Shards        " + p.shards.ToString("n0") + "\n" +
                  "  inventory       " + (p.inventory == null ? 0 : p.inventory.Count) + " item(s)\n" +
                  "  opens since RGB " + p.opensSinceLastRgb + " (pity fires at " +
                  DropTable.RgbPityThreshold + ")\n" +
                  "  save            " + SafeJsonStore.PathFor("collection.json"));
    }

    /// <summary>
    /// Essence, for testing inside a match. ESSENCE IS NOT EMBERS, and confusing
    /// the two is easy because both read as "money":
    ///
    /// <list type="bullet">
    /// <item>Essence lives on <see cref="ScoreManager"/>, exists only inside a
    /// match, starts at <see cref="RoundRules.StartingEssence"/> (500) and buys
    /// doors, wallbuys, the Mystery Box, perks and Pack-a-Punch.</item>
    /// <item>Embers live on the profile, survive death, and buy Sacreds in the
    /// Store. Granting those does NOTHING for a machine in the map.</item>
    /// </list>
    ///
    /// <para>Only works while playing, because <c>ScoreManager.essence</c> is
    /// [NonSerialized] runtime state — there is no edit-mode value to set, and a
    /// menu item that silently no-ops is worse than one that says why.</para>
    /// </summary>
    [MenuItem("Tools/Aftermarket/Dev — Grant Essence (in play)")]
    public static void GrantEssence()
    {
        if (!EditorApplication.isPlaying)
        {
            Debug.LogWarning("[Dev] Essence only exists during a match. Press Play first " +
                             "(Tools > Aftermarket > Play > The Map), then run this.");
            return;
        }
        if (ScoreManager.instance == null)
        {
            Debug.LogError("[Dev] no ScoreManager in the running scene — this is the map's " +
                           "currency, so you are probably in a menu scene.");
            return;
        }

        // Enough for the Mystery Box (950) plus both doors and a perk, so a test
        // run can reach the interesting machines without farming a single zombie.
        ScoreManager.Award(10000);
        Debug.Log("[Dev] +10,000 Essence. The Mystery Box is 950 — it can roll any weapon " +
                  "the rig knows about, including the Sakura Fang.");
    }
}
