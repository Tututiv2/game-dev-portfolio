package com.skyclone.mod.island;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One profile's island settings (shared by every co-op member). Stored as JSON in {@code island_profiles}
 * keyed by profile id, so they can be read for an OFFLINE owner when someone tries to /visit.
 * Gson-friendly: add fields, never retype them.
 */
public final class IslandSettings {
    /** "anyone" / "coop" / "nobody". Mirrors the members' {@code island.visitors} setting (menus module), strictest wins. */
    public String visitors = "anyone";
    /** Guests may open chests, barrels, furnaces... */
    public boolean guestContainers = false;
    /** Guests may use doors, trapdoors, gates, buttons and levers. */
    public boolean guestDoors = true;
    /** Guests may pick up items lying on the island (their own drops are always theirs). */
    public boolean guestPickup = false;
    /** Owner opt-in for guests while no member is online (also needs the server dial). */
    public boolean offlineVisits = false;

    /** Custom island spawn (/setspawn), template-relative. */
    public boolean homeSet = false;
    public double hx, hy, hz;
    public float hyaw, hpitch;

    /** Banned player uuids. */
    public List<String> banned = new ArrayList<>();
    /** Unique guests ever (uuids, capped). */
    public Set<String> uniqueGuests = new LinkedHashSet<>();
    public long totalVisits = 0;
    public long created = 0;

    /** Wave 5: the island's owner (uuid), the longest-standing co-op member. Null until first resolved. */
    public String owner = null;
    /** Wave 5: per-member co-op permissions (member uuid -> toggles). No entry = the server's defaults. */
    public Map<String, CoopPerm.Toggles> coopPerms = new HashMap<>();

    /** Wave 5b: Social 20 island name (null = "<owner>'s Island"). Cleaned text only (see {@link Decor#cleanText}). */
    public String islandName = null;
    /** Wave 5b: Island Ranks (Social 9) - non-member uuid -> rank id. */
    public Map<String, String> ranks = new HashMap<>();
    /** Wave 5b: visits per guest uuid (the Social Display's leaderboard), capped like uniqueGuests. */
    public Map<String, Integer> guestVisits = new HashMap<>();
    /** Wave 5b: placed island decorations (parkour, displays, eggs, showcases, NPCs). */
    public Decor.State decor = new Decor.State();
}
