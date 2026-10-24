/// <summary>
/// The camo unlock ladder (spec §4.3) as pure data + pure functions.
///
/// Spec §0.4 point 5: the 14 finished camo materials and this ladder ARE the
/// Common tier of the skin economy, already made. Phase 8 layers serialized
/// skins on top rather than replacing any of it.
/// </summary>
public static class CamoProgression
{
    /// <summary>Index order is load-bearing — it keys the PlayerPrefs entries
    /// "&lt;Weapon&gt;_Camo_&lt;0..13&gt;".</summary>
    public static readonly string[] Names =
    {
        "Dust", "Ice", "Python", "Bengal", "Crimson", "Blue Tiger", "Urban",
        "Red Hex", "Gold", "Diamond",          // 0-9   stage 1, headshots
        "Digital", "Dark Matter", "Abyssal Flow", // 10-12 stage 2, rapid-kill streaks
        "Cosmic Aether"                        // 13    stage 3, mastery
    };

    public const int Count = 14;
    public const int Stage3Index = 13;
    public const int MasteryPrerequisiteIndex = 12;

    /// <summary>Headshots required for camos 0..9.</summary>
    public static readonly int[] Stage1Headshots = { 10, 25, 50, 75, 100, 150, 200, 250, 300, 350 };

    /// <summary>Rapid-kill streaks required for camos 10..12, counted only AFTER
    /// stage 1 is complete.</summary>
    public static readonly int[] Stage2Streaks = { 5, 10, 15 };

    /// <summary>A rapid-kill streak is 10 kills with no more than 5 s between
    /// consecutive kills (spec §4.1).</summary>
    public const int RapidKillsPerStreak = 10;
    public const float RapidKillWindow = 5f;

    public static bool IsStage1Complete(int headshots)
    {
        return headshots >= Stage1Headshots[Stage1Headshots.Length - 1];   // 350
    }

    /// <summary>How many of camos 0..9 this headshot count has earned.</summary>
    public static int Stage1UnlockCount(int headshots)
    {
        int n = 0;
        for (int i = 0; i < Stage1Headshots.Length; i++)
            if (headshots >= Stage1Headshots[i]) n++;
        return n;
    }

    /// <summary>How many of camos 10..12 this streak count has earned.</summary>
    public static int Stage2UnlockCount(int streaks)
    {
        int n = 0;
        for (int i = 0; i < Stage2Streaks.Length; i++)
            if (streaks >= Stage2Streaks[i]) n++;
        return n;
    }

    /// <summary>Spec §4.3: headshots stop counting once stage 1 completes, and
    /// streaks do not count until it does.</summary>
    public static bool HeadshotsStillCount(int headshots)
    {
        return !IsStage1Complete(headshots);
    }

    public static bool StreaksStillCount(int headshots)
    {
        return IsStage1Complete(headshots);
    }

    // PlayerPrefs key helpers — one place, so a typo cannot silently split a
    // player's progression across two keys.
    public static string HeadshotKey(string weapon)   { return weapon + "_Headshots"; }
    public static string RapidKillKey(string weapon)  { return weapon + "_RapidKills"; }
    public static string CamoKey(string weapon, int index) { return weapon + "_Camo_" + index; }
    public static string EquippedKey(string weapon)   { return weapon + "_EquippedCamo"; }
}
