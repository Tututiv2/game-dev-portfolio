package com.skyclone.mod.mayors;

/**
 * Per-player member data under key "mayors" (Gson; add fields, never retype them). The counters are
 * listed in docs/handoff/mayors.md for progression (SkyBlock XP tasks) to read.
 */
public final class PlayerData {
    /** SkyBlock year of the last free New Year Cake claimed (-1 = never). */
    public int lastCakeYear = -1;
    public int giftsGiven;
    public int giftsOpened;
    public int burrowsDug;
    public int mythKills;
    public int geraldsFound;
    public int siegeRounds;
    public int auctionsWon;
    public double auctionCoinsSpent;
    /** Carnival: tokens to spend at the token shop, the real (UTC) day tickets were last claimed, games played, best score. */
    public long carnivalTokens;
    public long carnivalTicketDay = -1;
    public int carnivalGames;
    public int carnivalBest;
    /** Wave 5. Carnival Perks bought (perk id -> tier), kept for good. */
    public java.util.Map<String, Integer> carnivalPerks = new java.util.HashMap<>();
    /** Wave 5. Which Carnival (the mayor term) the goal progress below belongs to; -1 = none yet. */
    public int carnivalGoalTerm = -1;
    public java.util.List<String> carnivalGoalsDone = new java.util.ArrayList<>();
    /** Games played this Carnival, per booth (FRUIT / SHOOT / FISH). */
    public java.util.Map<String, Integer> carnivalPlays = new java.util.HashMap<>();
    /** Best score ever per booth. */
    public java.util.Map<String, Integer> carnivalBestScores = new java.util.HashMap<>();
}
