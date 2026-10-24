package com.skyclone.mod.garden;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One member's own garden progress ({@code Sky.profiles().member(p, "garden", ...)}). Gson-friendly; never retype a field. */
public final class GardenMember {
    public long pestsKilled;
    public Map<String, Long> pestKills = new HashMap<>();
    public long repellentUntil;
    public int repellentFactor;
    /** Wriggling Larvae eaten (each +2 Bonus Pest Chance, max 5). */
    public int larva;
    /** Pestwarden rewards claimed (item ids). */
    public List<String> pestwardenClaimed = new ArrayList<>();
    public boolean metGardener;
    public long visitorsAccepted;
    /** Peony / Blossom piece uuid -> Garden visitor offers completed while it was worn (wave 5). */
    public Map<String, Long> pieceServed = new HashMap<>();
    /** Pestwarden's Bonus (wave 5): {@link #pestsKilled} at the last turn-in, the Farming Fortune it gave and until when. */
    public long pestsTurnedIn;
    /** Pests currency (lane 9, G7): 1 per pest vacuumed (or emptied from a trap), spent at the Pestwarden. */
    public long pestBag;
    public double pestBonusFf;
    public long pestBonusUntil;
}
