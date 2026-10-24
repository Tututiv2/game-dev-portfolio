package com.skyclone.mod.island;

import java.util.Locale;
import java.util.Map;

/**
 * Island Ranks (research 02 §2.3: unlocked at Social 9; the research names the reward and nothing more, so the
 * ranks themselves are designed here - // TUNE). A member who manages the island and has Social 9 gives a NON-member
 * a rank on this island. Ranks only ever LOOSEN the guest rules for that one player; nobody who is not a co-op member
 * can ever build, break or touch minions, whatever their rank.
 * <ul>
 *   <li><b>Guest</b> (default) - the island's guest settings apply.</li>
 *   <li><b>Friend</b> - may come even when the island is full (the guest limit does not count them), and may always
 *       use doors, gates, trapdoors, buttons and levers.</li>
 *   <li><b>Trusted</b> - a Friend who may also open containers and pick up items, whatever the guest toggles say.</li>
 * </ul>
 * No Bukkit here.
 */
public enum IslandRank {
    GUEST("guest", "Guest", "&7"),
    FRIEND("friend", "Friend", "&a"),
    TRUSTED("trusted", "Trusted", "&b");

    public final String id, label, color;

    IslandRank(String id, String label, String color) {
        this.id = id;
        this.label = label;
        this.color = color;
    }

    public boolean ignoresGuestLimit() { return this != GUEST; }

    public boolean doors(boolean islandSetting) { return this != GUEST || islandSetting; }

    public boolean containers(boolean islandSetting) { return this == TRUSTED || islandSetting; }

    public boolean pickup(boolean islandSetting) { return this == TRUSTED || islandSetting; }

    /** "[Friend] " style prefix; blank for guests. */
    public String prefix() { return this == GUEST ? "" : color + "[" + label + "] "; }

    public IslandRank next() { return values()[(ordinal() + 1) % values().length]; }

    public static IslandRank parse(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.equals("none") || t.equals("default") || t.equals("reset")) return GUEST;
        if (t.equals("friends")) return FRIEND;
        if (t.equals("trust")) return TRUSTED;
        for (IslandRank r : values()) if (r.id.equals(t)) return r;
        return null;
    }

    /** A player's rank on an island ({@code ranks}: uuid -> rank id). */
    public static IslandRank of(Map<String, String> ranks, String uuid) {
        if (ranks == null || uuid == null) return GUEST;
        IslandRank r = parse(ranks.get(uuid));
        return r == null ? GUEST : r;
    }

    /** Set (or clear, for GUEST) a rank. False when the cap of ranked players is reached. */
    public static boolean set(Map<String, String> ranks, String uuid, IslandRank r, int cap) {
        if (r == null || r == GUEST) { ranks.remove(uuid); return true; }
        if (!ranks.containsKey(uuid) && cap > 0 && ranks.size() >= cap) return false;
        ranks.put(uuid, r.id);
        return true;
    }
}
