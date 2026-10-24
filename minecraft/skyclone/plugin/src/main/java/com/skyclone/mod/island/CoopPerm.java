package com.skyclone.mod.island;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-member co-op permissions on a shared Private Island (research 05 §9.2: "Island permissions - per-member
 * toggles for build/break/chests/minions", and the co-op scam note: "role permissions (Owner/Member) for bank and
 * island chests"). The island's OWNER - the longest-standing member of the profile - always has every permission and
 * is the only one who may change the others'. Every other member has a set of toggles, all on by default (the
 * reference lets every co-op member do everything), stored per island in {@link IslandSettings#coopPerms}.
 *
 * <p>No Bukkit here: unit tests run every rule.
 */
public enum CoopPerm {
    BUILD("build", "Place Blocks", "GRASS_BLOCK", "Place blocks, pour liquids, put up", "frames, signs and armour stands."),
    BREAK("break", "Break Blocks", "IRON_PICKAXE", "Mine and chop blocks on the island", "(the quarry, trees, crops, builds)."),
    CONTAINERS("containers", "Open Containers", "CHEST", "Open chests, barrels, furnaces,", "hoppers and other storage."),
    MINIONS("minions", "Use Minions", "ARMOR_STAND", "Open, collect from, place and pick", "up the island's minions."),
    MANAGE("manage", "Manage Island", "COMPARATOR", "Change island settings, set the", "spawn, kick and ban guests.");

    public final String id, label, icon;
    public final List<String> desc;

    CoopPerm(String id, String label, String icon, String... desc) {
        this.id = id;
        this.label = label;
        this.icon = icon;
        this.desc = List.of(desc);
    }

    public static CoopPerm parse(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.equals("place") || t.equals("building")) t = "build";
        if (t.equals("mine") || t.equals("breaking")) t = "break";
        if (t.equals("chests") || t.equals("chest") || t.equals("storage")) t = "containers";
        if (t.equals("minion")) t = "minions";
        if (t.equals("settings") || t.equals("admin")) t = "manage";
        for (CoopPerm p : values()) if (p.id.equals(t)) return p;
        return null;
    }

    /** "build,break,containers" -> the set; unknown words are ignored; "all" = every one, "none" = empty. */
    public static Set<CoopPerm> parseSet(String spec) {
        Set<CoopPerm> out = EnumSet.noneOf(CoopPerm.class);
        if (spec == null) return out;
        for (String part : spec.split("[,; ]+")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty() || t.equals("none")) continue;
            if (t.equals("all")) return EnumSet.allOf(CoopPerm.class);
            CoopPerm p = parse(t);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** One member's toggles. Gson-friendly; a missing entry means "the defaults". */
    public static final class Toggles {
        public boolean build = true, breaking = true, containers = true, minions = true, manage = true;

        public Toggles() {}

        public static Toggles of(Set<CoopPerm> granted) {
            Toggles t = new Toggles();
            for (CoopPerm p : values()) t.set(p, granted.contains(p));
            return t;
        }

        public boolean get(CoopPerm p) {
            return switch (p) {
                case BUILD -> build;
                case BREAK -> breaking;
                case CONTAINERS -> containers;
                case MINIONS -> minions;
                case MANAGE -> manage;
            };
        }

        public void set(CoopPerm p, boolean on) {
            switch (p) {
                case BUILD -> build = on;
                case BREAK -> breaking = on;
                case CONTAINERS -> containers = on;
                case MINIONS -> minions = on;
                case MANAGE -> manage = on;
            }
        }

        public int count() {
            int n = 0;
            for (CoopPerm p : values()) if (get(p)) n++;
            return n;
        }
    }

    // =========================================================================================
    // rules
    // =========================================================================================

    /**
     * May {@code member} do {@code perm} on an island whose owner is {@code owner}?
     * The owner (and anyone, while the island has no known owner yet) may do everything; a member without an entry
     * gets {@code defaults}.
     */
    public static boolean allowed(UUID member, CoopPerm perm, UUID owner, Map<String, Toggles> perms, Set<CoopPerm> defaults) {
        if (member == null || perm == null) return false;
        if (owner == null || owner.equals(member)) return true;
        Toggles t = perms == null ? null : perms.get(member.toString());
        return t == null ? defaults.contains(perm) : t.get(perm);
    }

    /**
     * Who owns the island: the recorded owner while they are still a member, else the first of {@code membersByJoin}
     * (oldest first), else null. {@code membersByJoin} may be empty when nothing is known.
     */
    public static UUID resolveOwner(UUID recorded, List<UUID> currentMembers, List<UUID> membersByJoin) {
        if (recorded != null && (currentMembers == null || currentMembers.isEmpty() || currentMembers.contains(recorded))) return recorded;
        if (membersByJoin != null) for (UUID u : membersByJoin) {
            if (currentMembers == null || currentMembers.isEmpty() || currentMembers.contains(u)) return u;
        }
        return null;
    }

    /** Drop toggles of players who are no longer members (keeps the stored JSON small and a re-invite fresh). */
    public static int prune(Map<String, Toggles> perms, List<UUID> members) {
        if (perms == null || members == null || members.isEmpty()) return 0;
        List<String> gone = new ArrayList<>();
        for (String k : perms.keySet()) {
            UUID u;
            try { u = UUID.fromString(k); } catch (IllegalArgumentException e) { gone.add(k); continue; }
            if (!members.contains(u)) gone.add(k);
        }
        for (String k : gone) perms.remove(k);
        return gone.size();
    }

    /**
     * Workstations that only open a screen and never change the world: every co-op member may use them on their own
     * island whatever their block toggles (core denies them to non-builders; wave 5b re-allows them for members).
     * Brewing stands, furnaces and the like are containers and follow "Open Containers" instead.
     */
    public static final Set<String> WORKSTATIONS = Set.of("CRAFTING_TABLE", "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL",
            "ENCHANTING_TABLE", "SMITHING_TABLE", "GRINDSTONE", "STONECUTTER", "LOOM", "CARTOGRAPHY_TABLE", "FLETCHING_TABLE",
            "BELL");

    public static boolean workstation(String material) { return material != null && WORKSTATIONS.contains(material); }

    /** "build,containers" (the granted toggles), "none", or "default" when there is no entry. For admin output. */
    public static String summary(Toggles t) {
        if (t == null) return "default";
        List<String> on = new ArrayList<>();
        for (CoopPerm p : values()) if (t.get(p)) on.add(p.id);
        return on.isEmpty() ? "none" : String.join(",", on);
    }
}
