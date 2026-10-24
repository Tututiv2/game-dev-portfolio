package com.skyclone.mod.hollows;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Every menu of the hollows module. Each one opens from a command AND from an NPC. */
final class HollowsMenus {

    private final HollowsModule m;

    HollowsMenus(HollowsModule m) { this.m = m; }

    private static Material crystalIcon(String gem) {
        return switch (gem) {
            case "AMETHYST" -> Material.PURPLE_STAINED_GLASS;
            case "JADE" -> Material.LIME_STAINED_GLASS;
            case "SAPPHIRE" -> Material.LIGHT_BLUE_STAINED_GLASS;
            case "AMBER" -> Material.ORANGE_STAINED_GLASS;
            default -> Material.YELLOW_STAINED_GLASS;
        };
    }

    private static void skyMenuBack(Menu menu) {
        menu.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
    }

    // =============================================================================================
    // the guide: /hollows
    // =============================================================================================

    Menu guide(Player p) {
        HollowsData d = m.quests.data(p);
        Menu menu = new Menu("Crystal Caverns", 6).chrome();
        int carried = 0;
        for (Lore.Quest q : Lore.QUESTS) if (Sky.items().count(p, q.crystal()) > 0) carried++;
        List<String> head = new ArrayList<>(List.of("&7A world of rock under the Dwarven", "&7Mines, mined from wall to wall.",
                "&7Five crystals hide in its corners;", "&7set all five in the &dCrystal Nucleus&7.",
                "", "&7Crystals carried: &a" + carried + "&7/5"));
        head.addAll(m.lobbies.status());
        head.add("");
        head.add("&8A lobby takes no newcomers from Day " + m.dials.lobbyCloseDay.get() + " and");
        head.add("&8closes on Day " + m.dials.lobbyShutdownDay.get() + "; then new caves open.");
        head.add("&8Entry: " + Text.strip(Sky.requirements().describe(m.dials.cavernsRequirement.get())));
        menu.set(4, Icons.of(Material.AMETHYST_CLUSTER, "&5Crystal Caverns", head));
        int[] slots = {20, 21, 22, 23, 24};
        for (int i = 0; i < Lore.QUESTS.size(); i++) {
            Lore.Quest q = Lore.QUESTS.get(i);
            int have = Sky.items().count(p, q.crystal());
            List<String> lore = new ArrayList<>();
            lore.add("&7Zone: " + q.zone());
            lore.add("&7Found at: &f" + q.structure());
            lore.add("");
            lore.addAll(Text.wrap("&7" + q.how(), 32));
            lore.add("");
            if (q.gem().equals("JADE")) {
                lore.add("&7Keepers helped: &a" + d.keepersGiven.size() + "&7/4");
                lore.add("&7Buried chests opened: &a" + d.chestsOpened);
            }
            if (q.gem().equals("AMETHYST")) lore.add("&7Inner temple runs: &a" + d.templeRuns);
            if (q.gem().equals("SAPPHIRE")) {
                int parts = 0;
                for (String part : Lore.ROBOT_PARTS) if (Sky.items().count(p, part) > 0) parts++;
                lore.add("&7Automaton parts carried: &a" + parts + "&7/6");
            }
            lore.add("&7Completed: &a" + d.runs.getOrDefault(q.gem(), 0) + "&7 times");
            lore.add(have > 0 ? "&a✔ You carry this crystal." : "&c✖ Not carried.");
            ItemStack icon = Icons.of(crystalIcon(q.gem()), "&d" + Text.titleCase(q.gem()) + " Crystal", lore);
            menu.set(slots[i], have > 0 ? Icons.glow(icon) : icon);
        }
        menu.set(31, Icons.of(Material.BEACON, "&dCrystal Nucleus", "&7Open the socket menu.", "", "&eClick to open!"),
                c -> { if (!Sky.commands().run(c.player(), "nucleus")) Msg.error(c.player(), "The Nucleus is not available."); });
        menu.set(29, Icons.of(Material.RECOVERY_COMPASS, "&aFind a Place", "&7Point yourself at a temple, a city,", "&7a camp or a boss lair. Sparks and", "&7the action bar show the way.", "", "&8/hollows find", "", "&eClick to choose!"),
                c -> m.waypoints.menu(c.player()));
        menu.set(33, Icons.of(Material.WITHER_SKELETON_SKULL, "&cBosses", "&7Magmaw, the Goblin Queen and the", "&7Gang Boss: who is up right now.", "", "&8/hollows bosses", "", "&eClick to check!"),
                c -> bosses(c.player()));
        long left = m.passes.passLeft(p);
        menu.set(13, Icons.of(Material.NAME_TAG, "&aCrystal Caverns Pass", "&7Entry to the caverns for &a" + m.dials.passHours.get() + " hours&7.",
                "", "&7Cost: &6" + Text.commas(m.dials.passPrice.get()) + " Coins",
                left > 0 ? "&7Time left: &a" + LobbyMath.span(left) : "&cYou have no pass.",
                m.dials.passRequired.get() ? "" : "&8(Passes are switched off on this server.)",
                "&8/hollows pass", "", "&eClick to buy!"), c -> pass(c.player()));
        menu.set(38, Icons.of(Material.COMPASS, "&aWhere am I?", "&7Shows your zone and the nearest", "&7landmark.", "", "&eClick to check!"),
                c -> whereAmI(c.player()));
        menu.set(40, Icons.of(Material.ENDER_PEARL, "&bFast Travel", "&7Warp to the Caverns Entrance.", "", "&eClick to warp!"),
                c -> { c.player().closeInventory(); Sky.worlds().warp(c.player(), "crystal_hollows"); });
        menu.set(42, Icons.of(Material.PACKED_ICE, "&bFrozen Tunnels", "&7Base camp, corpses and the four", "&7frozen gemstones.", "", "&eClick to open!"),
                c -> tunnels(c.player()));
        skyMenuBack(menu);
        menu.open(p);
        return menu;
    }

    /** {@code /hollows pass}: confirm and buy a Crystal Caverns Pass. */
    void pass(Player p) {
        long left = m.passes.passLeft(p);
        Sky.menus().confirm(p, "Buy a Caverns Pass?", List.of("&7" + m.dials.passHours.get() + " hours of Crystal Caverns access",
                "&7for &6" + Text.commas(m.dials.passPrice.get()) + " Coins&7.", left > 0 ? "&7Adds to your " + LobbyMath.span(left) + " left." : ""),
                () -> m.passes.buy(p), null);
    }

    /** Boss board: which of the caverns' three bosses is standing right now; click to track its lair. */
    void bosses(Player p) {
        Menu menu = new Menu("Cavern Bosses", 4).chrome();
        Object[][] rows = {
                {11, "ch_magmaw", "&cMagmaw", "structure.emberdeep", Material.MAGMA_CREAM, "Emberdeep, deep in the Magma Fields.",
                        "&7" + m.magmaw.hitsToKill() + " hits, whatever the weapon. Calls blazes and ember bats.", "&7Drops: &6Topaz crystal&7 for every hitter.", m.dials.magmawRespawn.get()},
                {13, GoblinQueen.MOB, "&6Goblin Queen", "structure.goblin_queen_den", Material.TURTLE_EGG, "Her den, south of the Goblin Holdout.",
                        "&7Calls Brood Guards at 75/50/25%.", "&7Drops: &6Amber crystal&7 + Goblin Eggs.", m.dials.queenRespawn.get()},
                {15, "ch_boss_corleone", "&7Gang Boss Vittore", "structure.gang_hideout", Material.GOLDEN_HELMET, "The treasure gang's hideout (east).",
                        "&7A million health. Bring friends.", "&7Drops: treasure-gang loot.", m.dials.gangBossRespawn.get()}};
        boolean here = Quests.CH.equals(Quests.island(p));
        for (Object[] r : rows) {
            boolean up = !Sky.mobs().live((String) r[1]).isEmpty();
            var icon = Icons.of((Material) r[4], (String) r[2], "&7" + r[5], "", (String) r[6], (String) r[7], "",
                    up ? "&a&lALIVE &7- go get it!" : "&c&lDOWN &7- returns about " + Text.commas((Integer) r[8] / 60.0) + " min after a kill",
                    "", here ? "&eClick to track its lair!" : "&8Track it from inside the caverns.");
            menu.set((Integer) r[0], up ? Icons.glow(icon) : icon, c -> {
                Player pl = c.player();
                if (!m.waypoints.track(pl, Quests.CH, (String) r[3], (String) r[2])) { Msg.error(pl, "Be in the Crystal Caverns to track a boss lair."); return; }
                pl.closeInventory();
            });
        }
        menu.set(31, Icons.back("Crystal Caverns"), c -> guide(c.player()));
        menu.open(p);
    }

    private void whereAmI(Player p) {
        var z = Sky.worlds().zoneOf(p);
        String isl = Quests.island(p);
        if (!Quests.CH.equals(isl) && !Quests.GT.equals(isl)) { Msg.error(p, "You are not in the caverns or the tunnels."); return; }
        Msg.send(p, "&7You are in &e" + (z == null ? "the rock" : Text.strip(z.displayName())) + " &7at y=" + p.getLocation().getBlockY() + ".");
        var lay = Sky.worlds().layout(isl);
        if (lay == null) return;
        String best = null;
        double bd = Double.MAX_VALUE;
        for (var a : lay.anchors().values()) {
            if (!a.name().startsWith("structure.") && !a.name().startsWith("landmark.")) continue;
            double dd = Math.pow(a.x() - p.getLocation().getX(), 2) + Math.pow(a.z() - p.getLocation().getZ(), 2) + Math.pow(a.y() - p.getLocation().getY(), 2);
            if (dd < bd) { bd = dd; best = a.name(); }
        }
        if (best != null) Msg.send(p, "&7Nearest landmark: &a" + Text.titleCase(best.substring(best.indexOf('.') + 1)) + " &8(" + (int) Math.sqrt(bd) + " blocks)");
    }

    // =============================================================================================
    // NPC menus
    // =============================================================================================

    void odala(Player p) {
        int need = m.dials.odalaSludge.get();
        int have = Sky.items().count(p, "SLUDGE_JUICE");
        Menu menu = new Menu("Odala's Trades", 4).chrome();
        menu.set(13, Icons.of(Material.TRIPWIRE_HOOK, Quests.name("JUNGLE_KEY"),
                "&7Opens the shrine on top of", "&7the Jungle Temple.", "",
                "&7Cost: &a" + need + "x " + Text.strip(Quests.name("SLUDGE_JUICE")), "&7You have: " + (have >= need ? "&a" : "&c") + have,
                "", have >= need ? "&eClick to trade!" : "&cNot enough Sludge Juice!"), c -> {
            Player pl = c.player();
            if (Sky.items().count(pl, "SLUDGE_JUICE") < need) { Msg.error(pl, "You need " + need + " Sludge Juice."); return; }
            Sky.items().remove(pl, "SLUDGE_JUICE", need);
            Sky.loot().give(pl, "JUNGLE_KEY", 1, LootSource.NPC_SHOP);
            m.quests.data(pl).odalaTrades++;
            Msg.success(pl, "Odala carves you a Jungle Key.");
            Msg.sound(pl, Sound.ENTITY_VILLAGER_YES, 1f);
            odala(pl);
        });
        menu.set(11, Icons.of(Material.SLIME_BALL, "&aWhere is Sludge?", "&7Sludges ooze around the jungle", "&7floor. Every one drops juice."));
        menu.set(15, Icons.of(Material.WITHER_SKELETON_SKULL, "&cKey Guardians", "&7A guardian patrols the temple", "&7steps with a key on its belt."));
        menu.open(p);
    }

    void keepers(Player p) {
        HollowsData d = m.quests.data(p);
        Menu menu = new Menu("Keepers of Varnas", 4).chrome();
        int[] slots = {10, 12, 14, 16};
        int i = 0;
        for (var e : Lore.KEEPER_TOOLS.entrySet()) {
            boolean given = d.keepersGiven.contains(e.getKey());
            boolean carried = Sky.items().count(p, e.getValue()) > 0;
            menu.set(slots[i++], Icons.of(given ? Material.LIME_DYE : carried ? Material.YELLOW_DYE : Material.GRAY_DYE,
                    "&6Keeper of " + Text.titleCase(e.getKey()), "&7Lost: " + Quests.name(e.getValue()), "",
                    given ? "&a✔ Returned" : carried ? "&eCarried: talk to this Keeper!" : "&c✖ Still buried"));
        }
        boolean det = Sky.items().count(p, Lore.METAL_DETECTOR) > 0;
        menu.set(22, Icons.of(Material.IRON_HOE, "&fMetal Detector", "&7Hold it in the hall to hear how", "&7close a buried chest is. Right-click", "&7on the spot to see which floor tile", "&7to dig open.", "",
                "&7Treasures dug: &a" + d.treasuresDug, "", det ? "&aIn your inventory." : "&eClick to get one!"), c -> {
            if (Sky.items().count(c.player(), Lore.METAL_DETECTOR) > 0) return;
            Sky.loot().give(c.player(), Lore.METAL_DETECTOR, 1, LootSource.REWARD);
            keepers(c.player());
        });
        menu.open(p);
    }

    void professor(Player p) {
        Menu menu = new Menu("Professor Gearhart", 5).chrome();
        int[] slots = {10, 11, 12, 14, 15, 16};
        int have = 0;
        for (int i = 0; i < Lore.ROBOT_PARTS.size(); i++) {
            String part = Lore.ROBOT_PARTS.get(i);
            int n = Sky.items().count(p, part);
            if (n > 0) have++;
            menu.set(slots[i], Icons.of(n > 0 ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE, Quests.name(part),
                    "&7Dropped by Automatons in the", "&7Lost Relic City.", "", "&7You have: " + (n > 0 ? "&a" + n : "&c0")));
        }
        boolean app = Sky.items().count(p, Lore.APPARATUS) > 0;
        boolean ready = have == 6 || app;
        menu.set(31, Icons.of(ready ? Material.EMERALD_BLOCK : Material.REDSTONE_BLOCK, ready ? "&aHand Over" : "&cMissing parts",
                "&7Parts carried: &a" + have + "&7/6" + (app ? " &7(+ apparatus)" : ""), "&7Reward: " + Quests.name("SAPPHIRE_CRYSTAL"), "",
                ready ? "&eClick to trade!" : "&cBring all six parts."), c -> {
            if (m.quests.professorTrade(c.player())) c.player().closeInventory();
        });
        menu.open(p);
    }

    // =============================================================================================
    // Frozen Tunnels
    // =============================================================================================

    void quartermaster(Player p) {
        Menu menu = new Menu("Camp Quartermaster", 4).chrome();
        double camp = m.dials.campfirePrice.get();
        int mats = m.dials.keyMaterials.get();
        double coins = m.dials.keyCoins.get();
        shopItem(menu, 11, p, "PORTABLE_CAMPFIRE", Material.CAMPFIRE, camp, null, 0, "&7Warm yourself anywhere in the tunnels.");
        shopItem(menu, 13, p, "TUNGSTEN_KEY", Material.TRIPWIRE_HOOK, coins, "TUNGSTEN", mats, "&7Opens a frozen Tungsten corpse.");
        shopItem(menu, 15, p, "UMBER_KEY", Material.TRIPWIRE_HOOK, coins, "UMBER", mats, "&7Opens a frozen Umber corpse.");
        menu.open(p);
    }

    private void shopItem(Menu menu, int slot, Player p, String id, Material mat, double coins, String matId, int matN, String desc) {
        if (!Sky.items().exists(id)) { menu.set(slot, Icons.of(Material.BARRIER, "&c" + Text.titleCase(id), "&7Not available.")); return; }
        List<String> lore = new ArrayList<>(List.of(desc, "", "&7Cost:", "&6" + Text.commas(coins) + " Coins"));
        if (matId != null) lore.add("&a" + matN + "x " + Text.strip(Quests.name(matId)));
        lore.add("");
        lore.add("&eClick to buy!");
        menu.set(slot, Icons.of(mat, Quests.name(id), lore), c -> {
            Player pl = c.player();
            if (matId != null && Sky.items().count(pl, matId) < matN) { Msg.error(pl, "You need " + matN + " " + Text.strip(Quests.name(matId)) + "."); return; }
            if (!Sky.economy().withdraw(pl, coins, "hollows: quartermaster " + id)) { Msg.error(pl, "You don't have enough coins!"); return; }
            if (matId != null) Sky.items().remove(pl, matId, matN);
            Sky.loot().give(pl, id, 1, LootSource.NPC_SHOP);
            Msg.success(pl, "Bought " + Text.strip(Quests.name(id)) + "!");
        });
    }

    void tunnels(Player p) {
        Menu menu = new Menu("Frozen Tunnels", 4).chrome();
        menu.set(4, Icons.of(Material.PACKED_ICE, "&bFrozen Tunnels", "&7Icy tunnels under the Dwarven Mines.",
                "&7Mine Rimestone, Umber, Tungsten and", "&7four gemstones found nowhere else:", "&8Onyx, Aquamarine, Citrine, Peridot.", "",
                "&7Cold builds while you are away from", "&7the Base Camp fire.", "",
                "&8Entry: " + Text.strip(Sky.requirements().describe(m.dials.tunnelsRequirement.get()))));
        menu.set(11, Icons.of(Material.ENDER_PEARL, "&bWarp to Base Camp", "", "&eClick to warp!"),
                c -> { c.player().closeInventory(); Sky.worlds().warp(c.player(), "glacite_tunnels"); });
        menu.set(13, Icons.of(Material.CAMPFIRE, "&eQuartermaster", "&7Campfires and corpse keys.", "", "&eClick to browse!"), c -> quartermaster(c.player()));
        menu.set(15, Icons.of(Material.BOOK, "&6Commissions", "&7Tunnel work orders.", "", "&eClick to open!"),
                c -> { if (!Sky.commands().run(c.player(), "commissions", Quests.GT)) Msg.error(c.player(), "No commissions right now."); });
        menu.set(24, Icons.of(Material.RECOVERY_COMPASS, "&aFind a Place", "&7Point yourself at the Junction,", "&7Old Lupo, the Depths or the lake.", "", "&eClick to choose!"),
                c -> m.waypoints.menu(c.player()));
        menu.set(22, Icons.of(Material.BONE, "&fMineshafts & Corpses", "&7Shafts open while you mine here.", "", "&eClick to open!"),
                c -> Sky.commands().run(c.player(), "mineshaft"));
        menu.set(30, Icons.back("Crystal Caverns"), c -> guide(c.player()));
        menu.open(p);
    }
}
