using UnityEngine;

/// <summary>
/// Per-match counters needed to bank a run (spec §13.11, §15.1).
///
/// The old build reloaded the scene on death: no death screen, no summary, no
/// run recorded, nothing banked. Since the Embers formula is
///   (highestRound x 50) + (totalKills x 5) + (firstWinOfDay ? 200 : 0)
/// something has to actually count kills and rounds. This is it.
/// </summary>
public static class MatchStats
{
    public static int TotalKills { get; private set; }
    public static int HighestRound { get; private set; }
    public static float MatchStartTime { get; private set; }

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
    public static void BeginMatch()
    {
        TotalKills = 0;
        HighestRound = 0;
        MatchStartTime = Time.time;
    }

    public static void RecordKill()
    {
        TotalKills++;
    }

    public static void RecordRound(int round)
    {
        if (round > HighestRound) HighestRound = round;
    }

    private const string KEY_LAST_WIN_DAY = "LastWinDayOfYear";

    /// <summary>First win of the (local) day is worth +200 Embers. Consumes the
    /// flag, so calling it twice in one day only pays once.</summary>
    public static bool ConsumeFirstWinOfDay()
    {
        int today = System.DateTime.Now.DayOfYear + System.DateTime.Now.Year * 1000;
        if (PlayerPrefs.GetInt(KEY_LAST_WIN_DAY, -1) == today) return false;
        PlayerPrefs.SetInt(KEY_LAST_WIN_DAY, today);
        PlayerPrefs.Save();
        return true;
    }

    /// <summary>Bank the run and return what was earned. Safe to call once per run end.</summary>
    public static int BankRun()
    {
        return PlayerWallet.BankRun(HighestRound, TotalKills, ConsumeFirstWinOfDay());
    }
}
