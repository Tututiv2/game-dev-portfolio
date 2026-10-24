package com.skyclone.mod.enchanting;

/** Member data key "enchanting" (Gson-friendly; add fields, never retype them). */
public class PlayerData {
    /** UTC date ("2026-10-02") the experiment counters belong to. */
    public String day = "";
    /** Superpairs games started on {@link #day} (a stat; the limit is {@link #charges}). */
    public int superpairsToday;
    /** Banked experiment charges; -1 = never set (filled to the maximum on first look). See {@link ExperimentCharges}. */
    public int charges = -1;
    /** Epoch ms the next charge arrives; 0 while the bank is full. */
    public long chargeAt;
    /** Extra games bought with XP levels + bits on {@link #day}. */
    public int extraRunsToday;
    /** Add-ons played since the last Superpairs (each once per Superpairs). */
    public boolean chronomatronDone;
    public boolean ultrasequencerDone;
    /** Bonus Superpairs clicks banked by the add-ons. */
    public int bonusClicks;

    // lifetime stats (shown in the Experimentation Table menu)
    public long superpairsPlayed;
    public long pairsMatched;
    public int bestChronomatron;
    public int bestUltrasequencer;
    public long tableEnchants;
    public long anvilCombines;
    public long levelsSpent;
}
