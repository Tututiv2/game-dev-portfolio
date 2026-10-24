package com.skyclone.mod.menus;

import com.skyclone.api.Sky;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fast Travel (research 08 section 11.10): one icon per island in the genre's slots, left-click warps
 * to the island, right-click lists its sub-warps. Islands come from {@code Sky.worlds().warpInfos()}
 * (whatever the island modules registered), placed by {@link TravelLayout}. Locked islands say what
 * unlocks them via {@code Sky.requirements().describe}.
 */
final class FastTravelMenu {

    private FastTravelMenu() {}

    /** Warps grouped by island, registration order kept. */
    static Map<String, List<WorldService.WarpInfo>> byIsland() {
        Map<String, List<WorldService.WarpInfo>> out = new LinkedHashMap<>();
        try {
            for (WorldService.WarpInfo w : Sky.worlds().warpInfos()) out.computeIfAbsent(w.island(), k -> new ArrayList<>()).add(w);
        } catch (Throwable ignored) {
            // worlds not up
        }
        return out;
    }

    /** A warp whose id is the island's id but which is registered on another island (one-element list), else empty. */
    static List<WorldService.WarpInfo> sameNamedWarp(Map<String, List<WorldService.WarpInfo>> warps, String island) {
        for (List<WorldService.WarpInfo> l : warps.values())
            for (WorldService.WarpInfo w : l) if (w.id().equals(island)) return List.of(w);
        return List.of();
    }

    /** The island's main warp: the one named like the island, else the first. */
    static WorldService.WarpInfo mainWarp(String island, List<WorldService.WarpInfo> warps) {
        if (warps == null || warps.isEmpty()) return null;
        for (WorldService.WarpInfo w : warps) if (w.id().equals(island)) return w;
        return warps.get(0);
    }

    /**
     * Null when the player may use it, else the "Requires ..." text naming ONLY the parts they are
     * missing: "skill:foraging:5 &amp; foraging:scroll:jungle" with Foraging 7 says just the scroll.
     */
    static String blocker(Player p, String island, WorldService.WarpInfo w) {
        IslandDef def = Sky.worlds().island(island);
        List<String> missing = new ArrayList<>();
        if (def != null) missingParts(p, def.requirement, missing);
        if (w != null) missingParts(p, w.requirement(), missing);
        return missing.isEmpty() ? null : String.join("&c, ", missing);
    }

    private static void missingParts(Player p, String requirement, List<String> into) {
        for (String part : TravelLayout.requirementParts(requirement)) {
            if (Sky.requirements().check(p, part)) continue;
            String d = Sky.requirements().describe(part);
            if (!into.contains(d)) into.add(d);
        }
    }

    static String islandName(String island) {
        IslandDef def = Sky.worlds().island(island);
        if (def != null && def.displayName != null && !def.displayName.isBlank()) return Text.strip(def.displayName);
        TravelLayout.Entry e = TravelLayout.entry(island);
        return e != null ? e.name() : Text.titleCase(island);
    }

    /** {unlocked destinations, total destinations} - the SkyBlock Menu shows it. Aliases count once. */
    static int[] countUnlocked(Player p) {
        int ok = 0, total = 0;
        for (Map.Entry<String, List<WorldService.WarpInfo>> e : byIsland().entrySet()) {
            for (List<WorldService.WarpInfo> g : TravelLayout.aliasGroups(e.getValue())) {
                total++;
                if (groupBlocker(p, e.getKey(), g) == null) ok++;
            }
        }
        return new int[]{ok, total};
    }

    // ---- alias groups (one destination registered under several warp ids) -------------------------

    /** The warp of a group this player should use: an open one first (see {@link TravelLayout#pick}). */
    static WorldService.WarpInfo best(Player p, String island, List<WorldService.WarpInfo> group) {
        return TravelLayout.pick(group, island, w -> blocker(p, island, w) == null);
    }

    /** Null when any warp of the group is open, else every distinct way in, joined with "or". */
    static String groupBlocker(Player p, String island, List<WorldService.WarpInfo> group) {
        List<String> ways = new ArrayList<>();
        for (WorldService.WarpInfo w : group) {
            String b = blocker(p, island, w);
            if (b == null) return null;
            if (!ways.contains(b)) ways.add(b);
        }
        return ways.isEmpty() ? null : String.join(" &7or &c", ways);
    }

    /** The group holding the island's main warp (the one named like the island, else the first). */
    static List<WorldService.WarpInfo> mainGroup(String island, List<WorldService.WarpInfo> warps) {
        WorldService.WarpInfo main = mainWarp(island, warps);
        if (main == null) return List.of();
        for (List<WorldService.WarpInfo> g : TravelLayout.aliasGroups(warps)) if (g.contains(main)) return g;
        return List.of(main);
    }

    // ---- menus ------------------------------------------------------------------------------------

    static Menu build(Player p) {
        Menu m = new Menu("Fast Travel", 6).chrome();
        Map<String, List<WorldService.WarpInfo>> warps = byIsland();
        Map<String, Integer> slots = TravelLayout.assign(new ArrayList<>(warps.keySet()));
        String here = Sky.worlds().islandOf(p.getLocation());
        int[] count = countUnlocked(p);
        m.set(4, Icons.of(Material.FILLED_MAP, "&bFast Travel", List.of(
                "&7Teleport to any island you have",
                "&7unlocked, from anywhere.",
                "",
                "&7Destinations unlocked: &a" + count[0] + "&7/&a" + count[1],
                "",
                "&eLeft-click an island to warp!",
                "&eRight-click to choose a warp!")));
        for (Map.Entry<String, Integer> e : slots.entrySet()) {
            String island = e.getKey();
            List<WorldService.WarpInfo> list = warps.getOrDefault(island, List.of());
            // an island built per instance (kuudra) has no warp of its own; its entry warp lives on another
            // island under the island's id (kuudra request 3: warp "kuudra" on crimson_isle)
            if (list.isEmpty()) list = sameNamedWarp(warps, island);
            final List<WorldService.WarpInfo> fl = list;
            final int destinations = TravelLayout.aliasGroups(fl).size();
            m.set(e.getValue(), islandIcon(p, island, fl, island.equals(here)), c -> {
                MenuKit.click(c.player());
                if (c.right() && destinations > 1) subWarps(c.player(), island).open(c.player());
                else travel(c.player(), island, best(c.player(), island, mainGroup(island, fl)));
            });
        }
        m.set(50, Icons.of(Material.PAPER, "&aAll Warps", List.of("&7Every destination in one list,", "&7including sub-warps.", "", "&eClick to view!")),
                c -> { MenuKit.click(c.player()); warpList(c.player(), 0).open(c.player()); });
        MenuKit.backToSkyMenu(m);
        return m;
    }

    static ItemStack islandIcon(Player p, String island, List<WorldService.WarpInfo> warps, boolean here) {
        TravelLayout.Entry entry = TravelLayout.entry(island);
        String name = islandName(island);
        char color = entry != null ? entry.color() : 'a';
        List<WorldService.WarpInfo> mainGroup = mainGroup(island, warps);
        WorldService.WarpInfo main = best(p, island, mainGroup);
        int destinations = TravelLayout.aliasGroups(warps).size();
        String command = entry != null ? entry.command() : null;
        List<String> lore = new ArrayList<>();
        if (main != null) lore.add("&8/warp " + main.id());
        else if (command != null) lore.add("&8/" + command);
        if (entry != null) { lore.add(""); lore.addAll(MenuKit.wrap(entry.about())); }
        boolean reachable = main != null || (command != null && Links.commandKnown(command) != Boolean.FALSE);
        if (!reachable) {
            lore.add("");
            lore.add("&cThis island is not open yet.");
            lore.add("&8Coming soon!");
            return Icons.of(Material.GRAY_DYE, "&c" + name, lore);
        }
        String block = main == null ? null : groupBlocker(p, island, mainGroup);
        if (destinations > 1) { lore.add(""); lore.add("&7Warps: &e" + destinations); }
        if (main != null && !Sky.worlds().isBuilt(island) && Sky.worlds().island(island) != null && !Sky.worlds().island(island).instanced) {
            lore.add("");
            lore.add("&eThis island is still being built...");
        }
        lore.add("");
        if (block != null) {
            lore.add("&cLocked");
            lore.add("&cRequires " + block + "&c.");
            if (entry != null && entry.hint() != null) lore.add("&8Unlocked by: " + entry.hint());
        } else {
            if (here) lore.add("&aYou are here!");
            lore.add("&eLeft-click to warp!");
            if (destinations > 1) lore.add("&eRight-click to choose a warp!");
        }
        ItemStack icon = MenuKit.icon(entry == null ? null : entry.texture(), entry == null ? "ENDER_PEARL" : entry.material(),
                (block != null ? "&c" : "&" + color) + name, lore);
        return here ? Icons.glow(icon) : icon;
    }

    /** Warp the player to an island (instanced islands go through their owner's command). */
    static void travel(Player p, String island, WorldService.WarpInfo w) {
        TravelLayout.Entry entry = TravelLayout.entry(island);
        if (entry != null && entry.command() != null) {
            p.closeInventory();
            if (Sky.commands().run(p, entry.command())) return;
            if (island.equals("private_island")) { Sky.worlds().sendHome(p); return; }
        }
        if (w == null) { MenuKit.comingSoon(p, islandName(island)); return; }
        warp(p, island, w);
    }

    static void warp(Player p, String island, WorldService.WarpInfo w) {
        String block = blocker(p, island, w);
        if (block != null) { Msg.error(p, "Requires " + block + "&c!"); return; }
        p.closeInventory();
        Sky.worlds().warp(p, w.id());
    }

    /** Warp through whichever warp of the group is open (re-checked at click time). */
    static void warp(Player p, String island, List<WorldService.WarpInfo> group) {
        String block = groupBlocker(p, island, group);
        if (block != null) { Msg.error(p, "Requires " + block + "&c!"); return; }
        warp(p, island, best(p, island, group));
    }

    static Menu subWarps(Player p, String island) {
        String name = islandName(island);
        List<List<WorldService.WarpInfo>> list = TravelLayout.aliasGroups(byIsland().getOrDefault(island, List.of()));
        // 4 rows hold 14 warps; the hub has 19 (hub + 18 districts), so grow to 6 rows (28) when needed
        // (hub Request 1, integration round 2). Islands beyond 28 still have the paged "All Warps" list.
        int rows = list.size() > 14 ? 6 : 4;
        Menu m = new Menu(MenuKit.clip(name + " Warps"), rows).chrome();
        int[] slots = new int[(rows - 2) * 7];
        for (int r = 1, k = 0; r < rows - 1; r++) for (int c = 1; c <= 7; c++) slots[k++] = r * 9 + c;
        TravelLayout.Entry entry = TravelLayout.entry(island);
        for (int i = 0; i < list.size() && i < slots.length; i++) {
            List<WorldService.WarpInfo> g = list.get(i);
            m.set(slots[i], warpIcon(p, island, g, entry), c -> { MenuKit.click(c.player()); warp(c.player(), island, g); });
        }
        m.back("Fast Travel", () -> build(p));
        return m;
    }

    /** One destination; its other warp ids are listed as "Also" so typed commands stay discoverable. */
    static ItemStack warpIcon(Player p, String island, List<WorldService.WarpInfo> group, TravelLayout.Entry entry) {
        WorldService.WarpInfo w = best(p, island, group);
        String block = groupBlocker(p, island, group);
        List<String> lore = new ArrayList<>();
        lore.add("&8/warp " + w.id());
        List<String> also = new ArrayList<>();
        for (WorldService.WarpInfo o : group) if (o != w) also.add(o.id());
        if (!also.isEmpty()) lore.add("&8Also: /warp " + String.join(", ", also));
        lore.add("&7Island: &f" + islandName(island));
        // the island's own arrival point (its spawn) carries the island's description
        if (entry != null && entry.about() != null && group.stream().anyMatch(o -> o.anchor() == null || o.anchor().equals("spawn"))) {
            lore.add("");
            lore.addAll(MenuKit.wrap(entry.about()));
        }
        lore.add("");
        if (block != null) { lore.add("&cLocked"); lore.add("&cRequires " + block + "&c."); }
        else lore.add("&eClick to warp!");
        String tex = MenuKit.textures().containsKey("warp." + w.id()) ? "warp." + w.id() : (entry == null ? null : entry.texture());
        String display = w.displayName() == null || w.displayName().isBlank() ? Text.titleCase(w.id()) : Text.strip(w.displayName());
        return MenuKit.icon(tex, entry == null ? "ENDER_PEARL" : entry.material(), (block == null ? "&a" : "&c") + display, lore);
    }

    /** Every destination, paged: one entry per place, its aliases folded in. */
    static Menu warpList(Player p, int page) {
        List<List<WorldService.WarpInfo>> all = new ArrayList<>();
        for (List<WorldService.WarpInfo> l : byIsland().values()) all.addAll(TravelLayout.aliasGroups(l));
        return Sky.menus().paged("All Warps", all,
                g -> warpIcon(p, g.get(0).island(), g, TravelLayout.entry(g.get(0).island())),
                pc -> { MenuKit.click(pc.click().player()); warp(pc.click().player(), pc.entry().get(0).island(), pc.entry()); },
                menu -> menu.back("Fast Travel", () -> build(p)), page);
    }
}
