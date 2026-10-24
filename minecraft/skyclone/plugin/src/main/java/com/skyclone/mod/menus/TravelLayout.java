package com.skyclone.mod.menus;

import com.skyclone.api.world.WorldService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The Fast Travel menu's map: which island sits in which slot (research 08 section 11.10), its icon,
 * a fallback display name (the island module's own {@code IslandDef.displayName} wins when it is
 * registered) and a short description in our own words. Pure, so a unit test can check slots.
 *
 * <p>Instanced islands (private island, garden) are not reached through a warp but through their
 * owner's command: {@code command} names it ({@code /is}, {@code /garden}).
 */
public final class TravelLayout {

    private TravelLayout() {}

    /**
     * @param island   island id (CONTRACT section 3)
     * @param slot     slot in the 6-row Fast Travel menu
     * @param name     fallback display name (renamed where the reference name is a proper noun)
     * @param material fallback icon material name
     * @param texture  key into textures.json (may be null)
     * @param color    legacy colour char for the name
     * @param command  command to run instead of a warp (instanced islands), or null
     * @param about    one-line description
     * @param hint     how the reference game unlocks it (shown when it is locked and the warp has no requirement text)
     */
    public record Entry(String island, int slot, String name, String material, String texture, char color,
                        String command, String about, String hint) {}

    /** The island ids the CONTRACT fixes. */
    public static final Set<String> CONTRACT_ISLANDS = Set.of("hub", "private_island", "barn", "mushroom_desert",
            "park", "gold_mine", "deep_caverns", "dwarven_mines", "crystal_hollows", "glacite_tunnels", "spiders_den",
            "the_end", "crimson_isle", "jerrys_workshop", "dungeon_hub", "catacombs", "garden", "rift",
            "backwater_bayou", "kuudra");

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private static void e(String island, int slot, String name, String material, String texture, char color,
                          String command, String about, String hint) {
        ENTRIES.put(island, new Entry(island, slot, name, material, texture, color, command, about, hint));
    }

    static {
        e("private_island", 10, "Private Island", "GRASS_BLOCK", null, 'a', "is",
                "Your own island: build, place minions and store your things.", "Always available");
        e("hub", 11, "Hub", "PLAYER_HEAD", "island.hub", 'b', null,
                "The town at the centre of everything: shops, bank, bazaar and auction house.", "Always available");
        e("dungeon_hub", 12, "Dungeon Hub", "WITHER_SKELETON_SKULL", null, 'c', null,
                "The lobby of the Catacombs, where dungeon parties gather.", "Reached from the Hub's crypt entrance");
        e("barn", 13, "The Barn", "PLAYER_HEAD", "island.barn", 'e', null,
                "Rolling farmland and animal pens around a great windmill.", "Reached from the Hub's farm");
        e("garden", 14, "The Garden", "SUNFLOWER", null, 'a', "garden",
                "Your private farm of 24 plots, visitors and the composter.", "SkyBlock Level 5");
        e("mushroom_desert", 15, "Mushroom Desert", "PLAYER_HEAD", "island.mushroom_desert", '6', null,
                "Red mesa, mushroom gorges and the trapper's hunting grounds.", "Farming Skill V");
        e("park", 16, "The Park", "PLAYER_HEAD", "island.park", 'a', null,
                "Five forests stacked on floating islands, from birch to dark oak.", "Foraging Skill I");
        e("gold_mine", 19, "Gold Mine", "PLAYER_HEAD", "island.gold_mine", '6', null,
                "A shallow open pit of gold, iron and coal.", "Reached from the Hub's coal mine");
        e("deep_caverns", 20, "Deep Caverns", "PLAYER_HEAD", "island.deep_caverns", '9', null,
                "Six cave layers stacked down into the dark, richer the deeper you go.", "Mining Skill V");
        e("dwarven_mines", 21, "Dwarven Mines", "PLAYER_HEAD", "island.dwarven_mines", '2', null,
                "A buried dwarf city of mithril veins, commissions and the forge.", "Mining Skill XII");
        e("crystal_hollows", 22, "Crystal Caverns", "PLAYER_HEAD", "island.crystal_hollows", 'd', null,
                "A vast gemstone cavern split into five zones around a crystal nucleus.", "Heart of the Mountain IV");
        e("spiders_den", 23, "Spider's Den", "PLAYER_HEAD", "island.spiders_den", '4', null,
                "A spider-infested mountain under a permanent drizzle.", "Combat Skill I");
        e("the_end", 24, "The End", "PLAYER_HEAD", "island.the_end", '5', null,
                "Floating end stone, the zealots and the dragon's nest.", "Combat Skill XII");
        e("crimson_isle", 25, "Crimson Isle", "PLAYER_HEAD", "island.crimson_isle", 'c', null,
                "A volcanic island torn between two rival factions.", "Combat Skill XXII");
        e("jerrys_workshop", 28, "Winter Workshop", "SNOW_BLOCK", null, 'f', null,
                "A snowed-in workshop that only opens for the last month of winter.", "Late Winter only");
        e("backwater_bayou", 29, "Backwater Bayou", "PLAYER_HEAD", "island.backwater_bayou", '3', null,
                "A murky swamp full of fish that bite nowhere else.", "Reached from the Hub");
        e("glacite_tunnels", 30, "Glacite Tunnels", "PLAYER_HEAD", "island.glacite_tunnels", 'b', null,
                "Frozen tunnels beneath the dwarven city, home of glacite and fossils.", "Heart of the Mountain VII");
        e("rift", 31, "The Rift", "PLAYER_HEAD", "island.rift", '5', null,
                "A warped copy of the world where time itself is your health.", "Rift entry");
        e("kuudra", 32, "Leviathan's Hollow", "PLAYER_HEAD", "island.kuudra", '4', null,
                "The Magma Leviathan's lair, hunted by a crew from the Crimson Isle docks.", "Crimson Isle faction reputation");
    }

    /**
     * Islands outside the CONTRACT list that modules register (they get an overflow slot): name,
     * icon and description only, slot -1. Without this the Moonmarsh showed a bare name.
     */
    private static final Map<String, Entry> EXTRA = new LinkedHashMap<>();

    static {
        EXTRA.put("galatea", new Entry("galatea", -1, "The Moonmarsh", "MANGROVE_PROPAGULE", null, '2', null,
                "Misty wetland of giant fig and mangrove trees, shared by every forager on the server.", "Foraging Skill XII"));
        // wave 5: the galatea and hunting modules' islands (their own display names)
        EXTRA.put("lotus_atoll", new Entry("lotus_atoll", -1, "Lotus Atoll", "LILY_PAD", null, '3', null,
                "A ring of lagoons where it rains on its own clock and trophy frogs bite.", null));
        EXTRA.put("torrhus_canyon", new Entry("torrhus_canyon", -1, "Torrent Canyon", "POINTED_DRIPSTONE", null, '6', null,
                "A river-cut canyon of springs and heights, with a journal to fill in.", null));
        EXTRA.put("critter_safari", new Entry("critter_safari", -1, "Critter Safari", "LEAD", null, 'a', null,
                "Fenced wilds where hunters trap critters for their shards.", "Hunting Skill XV"));
    }

    /** The "&amp;"-joined parts of a requirement key, trimmed, blanks dropped (CONTRACT: "a &amp; b" = all). */
    public static List<String> requirementParts(String requirement) {
        List<String> out = new ArrayList<>();
        if (requirement == null) return out;
        for (String part : requirement.split("&")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Fixed entries in declaration order. */
    public static List<Entry> entries() { return List.copyOf(ENTRIES.values()); }

    /** The fixed entry, else the overflow description, else null. */
    public static Entry entry(String island) {
        Entry e = ENTRIES.get(island);
        return e != null ? e : EXTRA.get(island);
    }

    // ---- warp aliases ------------------------------------------------------------------------------

    /**
     * Modules register the same destination under several warp ids ("dwarven_mines", "mines" and the
     * mining scroll's "mining_dwarven"; "forge" and "mining_forge"), often with different requirements.
     * Listing each one separately showed a place three times, some copies locked while another was
     * open. A group is one destination: same island AND (same anchor OR same display name), merged
     * transitively. Groups and their members keep registration order.
     */
    public static List<List<WorldService.WarpInfo>> aliasGroups(List<WorldService.WarpInfo> warps) {
        List<List<WorldService.WarpInfo>> groups = new ArrayList<>();
        if (warps == null) return groups;
        for (WorldService.WarpInfo w : warps) {
            if (w == null) continue;
            List<WorldService.WarpInfo> into = null;
            for (int i = 0; i < groups.size(); i++) {
                List<WorldService.WarpInfo> g = groups.get(i);
                if (!sameDestination(g, w)) continue;
                if (into == null) into = g;
                else { into.addAll(g); groups.remove(i--); }
            }
            if (into == null) { into = new ArrayList<>(); groups.add(into); }
            into.add(w);
        }
        // a merge can append an earlier-registered warp after a later one: restore registration order
        for (List<WorldService.WarpInfo> g : groups) g.sort(java.util.Comparator.comparingInt(warps::indexOf));
        return groups;
    }

    private static boolean sameDestination(List<WorldService.WarpInfo> group, WorldService.WarpInfo w) {
        for (WorldService.WarpInfo o : group) {
            if (!o.island().equals(w.island())) continue;
            if (anchor(o).equals(anchor(w))) return true;
            String a = nameKey(o), b = nameKey(w);
            if (!a.isEmpty() && a.equals(b)) return true;
        }
        return false;
    }

    private static String anchor(WorldService.WarpInfo w) { return w.anchor() == null ? "spawn" : w.anchor(); }

    /** Display name with colour codes and case dropped ("&amp;6The Forge" = "the forge"). */
    static String nameKey(WorldService.WarpInfo w) {
        String n = w.displayName() == null ? "" : w.displayName().replaceAll("[&§][0-9a-fk-orA-FK-OR]", "");
        return n.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The warp a group sends a player through: an open one before a locked one; then the warp named
     * like its island (the island module's own); then one that does not land on an NPC (an
     * {@code npc.*} anchor is where the NPC stands, so the player arrives inside it); then registration order.
     */
    public static WorldService.WarpInfo pick(List<WorldService.WarpInfo> group, String island, Predicate<WorldService.WarpInfo> open) {
        if (group == null || group.isEmpty()) return null;
        WorldService.WarpInfo best = null;
        int bestScore = Integer.MAX_VALUE;
        for (WorldService.WarpInfo w : group) {
            int score = (open != null && open.test(w) ? 0 : 4) + (w.id().equals(island) ? 0 : 2) + (anchor(w).startsWith("npc.") ? 1 : 0);
            if (score < bestScore) { best = w; bestScore = score; }
        }
        return best;
    }

    /** Slots handed out to islands registered by modules but missing from the table above. */
    public static final List<Integer> OVERFLOW_SLOTS = List.of(33, 34, 37, 38, 39, 40, 41, 42, 43);

    /**
     * Assign every island a slot: fixed ones first, unknown ones into {@link #OVERFLOW_SLOTS} in the
     * order given. Islands that do not fit are left out of the map (the warp list still shows them).
     */
    public static Map<String, Integer> assign(List<String> registeredIslands) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Entry e : ENTRIES.values()) out.put(e.island(), e.slot());
        List<Integer> free = new ArrayList<>(OVERFLOW_SLOTS);
        for (String id : registeredIslands) {
            if (out.containsKey(id) || free.isEmpty()) continue;
            out.put(id, free.remove(0));
        }
        return Collections.unmodifiableMap(out);
    }
}
