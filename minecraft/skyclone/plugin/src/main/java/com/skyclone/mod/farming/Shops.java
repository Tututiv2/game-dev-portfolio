package com.skyclone.mod.farming;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The farming counters: Harvesta's Medal Exchange (Extra Fortune, the Farming level cap and her stock), the
 * desert pelt shops, and the Barn farmhand. Every purchase re-checks every cost on the click tick, takes
 * everything, then gives - and items that no installed module registered are simply not listed.
 */
final class Shops {

    private final FarmingModule m;
    private final Map<UUID, Long> lastBuy = new HashMap<>();

    Shops(FarmingModule m) { this.m = m; }

    // =================================================================================================
    // Harvesta
    // =================================================================================================

    void openAnita(Player p) {
        FarmData d = m.data(p);
        Menu menu = new Menu("Harvesta's Medal Exchange", 6).chrome();
        menu.set(4, Icons.of(Material.GOLD_INGOT, "&6Your Medals", List.of(
                "&6Gold: &e" + d.gold, "&fSilver: &e" + d.silver, "&cBronze: &e" + d.bronze, "",
                "&aHarvest Fair Tickets: &e" + Text.commasInt(tickets(p)), "",
                "&7Earn medals by placing in the", "&7Harvest Fair (&e/harvestfair&7).")));

        // Extra Farming Fortune (skyhanni AnitaUpgradeCosts: 15 tiers)
        int tier = d.anitaFortune;
        List<String> ff = new ArrayList<>();
        ff.add("&7Permanently gain &6+" + m.dials.anitaPerTier.get() + "☘ Farming Fortune &7per tier.");
        ff.add("");
        ff.add("&7Current: &a" + tier + "&7/&a" + m.tables.anitaFortune.size() + "  &6(+" + tier * m.dials.anitaPerTier.get() + "☘)");
        if (tier < m.tables.anitaFortune.size()) {
            int[] c = m.tables.anitaFortune.get(tier);
            ff.add(""); ff.add("&7Cost:"); ff.add(" &6" + c[0] + " Gold Medal" + (c[0] == 1 ? "" : "s"));
            if (c[1] > 0) ff.add(" &a" + c[1] + " Harvest Fair Tickets");
            ff.add(""); ff.add(canAfford(p, d, c[0], c[1]) ? "&eClick to upgrade!" : "&cYou can't afford this!");
        } else { ff.add(""); ff.add("&a&lMAXED"); }
        menu.set(11, Icons.of(Material.WHEAT, "&aExtra Farming Fortune", ff), c -> buyFortune(c.player()));

        // Farming level cap (research 02 2.4.1)
        int step = d.capSteps;
        List<String> cap = new ArrayList<>();
        cap.add("&7Raise your &aFarming &7level cap by 1.");
        cap.add("");
        cap.add("&7Current cap: &a" + (50 + step) + " &7(max 60)");
        cap.add("&7Unique gold crops: &6" + d.uniqueGolds.size());
        if (step < m.tables.farmingCap.size()) {
            int[] c = m.tables.farmingCap.get(step);
            cap.add(""); cap.add("&7Cost:"); cap.add(" &6" + c[0] + " Gold Medal" + (c[0] == 1 ? "" : "s"));
            if (c[1] > 0) cap.add(" &a" + c[1] + " Harvest Fair Tickets");
            if (m.dials.capNeedsUniqueGolds.get()) cap.add(" &7Requires &6Gold &7in &e" + (step + 1) + " &7different crops");
            cap.add("");
            boolean ok = canAfford(p, d, c[0], c[1]) && (!m.dials.capNeedsUniqueGolds.get() || d.uniqueGolds.size() >= step + 1);
            cap.add(ok ? "&eClick to upgrade!" : "&cRequirements not met!");
        } else { cap.add(""); cap.add("&a&lMAXED"); }
        menu.set(13, Icons.of(Material.EXPERIENCE_BOTTLE, "&aFarming Level Cap", cap), c -> buyCap(c.player()));

        // Personal Bests (research 08 1.16: "Anita sells a Personal Best bonus")
        List<String> pb = new ArrayList<>();
        pb.add("&7Each crop's best Harvest Fair score");
        pb.add("&7grants &6Crop Fortune &7for that crop:");
        pb.add("&6+0.01☘ &7per step of your best, up to &6+" + m.dials.pbMaxFortune.get() + "☘&7.");
        pb.add("");
        int shown = 0;
        for (Crop c : Crop.values()) {
            long best = d.personalBest.getOrDefault(c.name(), 0L);
            if (best <= 0) continue;
            double f = FortuneMath.personalBestFortune(best, m.tables.pbStep(c), m.dials.pbMaxFortune.get());
            pb.add("&a" + c.display + "&7: &e" + Text.commasInt(best) + " &8(step " + m.tables.pbStep(c) + ") &6+" + Text.commas(f) + "☘");
            shown++;
        }
        if (shown == 0) pb.add("&7No personal bests yet.");
        pb.add("");
        if (d.pbUnlocked) pb.add("&a&lUNLOCKED");
        else {
            pb.add("&7Cost:");
            pb.add(" &6" + m.dials.pbUnlockGold.get() + " Gold Medal" + (m.dials.pbUnlockGold.get() == 1 ? "" : "s"));
            if (m.dials.pbUnlockTickets.get() > 0) pb.add(" &a" + m.dials.pbUnlockTickets.get() + " Harvest Fair Tickets");
            pb.add("");
            pb.add(canAfford(p, d, m.dials.pbUnlockGold.get(), m.dials.pbUnlockTickets.get()) ? "&eClick to unlock!" : "&cYou can't afford this!");
        }
        menu.set(15, Icons.of(Material.GOLDEN_CARROT, "&dPersonal Bests", pb), c -> buyPersonalBests(c.player()));

        menu.set(50, Icons.of(Material.CLOCK, "&eHarvest Fair", "&7Schedule, live standings and", "&7your contest history.", "", "&eClick to view!"),
                c -> m.menus.openContest(c.player()));

        // stock: three full rows, so the medal exchanges, tickets and the almanac at the end of the list always show
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        int i = 0;
        for (Tables.ShopEntry e : m.tables.shops.getOrDefault("anita", List.of())) {
            if (i >= slots.length) break;
            ItemStack icon = icon(p, d, e);
            if (icon == null) continue;
            menu.set(slots[i++], icon, c -> buy(c.player(), e, () -> openAnita(c.player())));
        }
        menu.set(48, Icons.back("Farming"), c -> m.menus.openMain(c.player()));
        menu.open(p);
    }

    private boolean canAfford(Player p, FarmData d, int gold, int tickets) { return d.gold >= gold && tickets(p) >= tickets; }

    private void buyFortune(Player p) {
        if (!rate(p)) return;
        FarmData d = m.data(p);
        if (d.anitaFortune >= m.tables.anitaFortune.size()) { Msg.error(p, "This upgrade is maxed!"); return; }
        int[] c = m.tables.anitaFortune.get(d.anitaFortune);
        if (!canAfford(p, d, c[0], c[1])) { Msg.error(p, "You can't afford this!"); return; }
        if (!takeTickets(p, c[1])) { noTickets(p); return; }
        d.gold -= c[0];
        d.anitaFortune++;
        Sky.stats().invalidate(p);
        Sky.profiles().saveNow(p);
        Msg.success(p, "Extra Farming Fortune upgraded to tier " + d.anitaFortune + "! &6(+" + d.anitaFortune * m.dials.anitaPerTier.get() + "☘)");
        openAnita(p);
    }

    private void buyPersonalBests(Player p) {
        if (!rate(p)) return;
        FarmData d = m.data(p);
        if (d.pbUnlocked) { Msg.error(p, "You already unlocked Personal Bests!"); return; }
        int gold = m.dials.pbUnlockGold.get(), tk = m.dials.pbUnlockTickets.get();
        if (!canAfford(p, d, gold, tk)) { Msg.error(p, "You can't afford this!"); return; }
        if (!takeTickets(p, tk)) { noTickets(p); return; }
        d.gold -= gold;
        d.pbUnlocked = true;
        Sky.profiles().saveNow(p);
        Msg.success(p, "Unlocked &dPersonal Bests&a! Your best Harvest Fair scores now add Crop Fortune.");
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        openAnita(p);
    }

    private void buyCap(Player p) {
        if (!rate(p)) return;
        FarmData d = m.data(p);
        if (d.capSteps >= m.tables.farmingCap.size()) { Msg.error(p, "Your Farming cap is already 60!"); return; }
        int[] c = m.tables.farmingCap.get(d.capSteps);
        if (m.dials.capNeedsUniqueGolds.get() && d.uniqueGolds.size() < d.capSteps + 1) {
            Msg.error(p, "You need a Gold medal in " + (d.capSteps + 1) + " different crops first!");
            return;
        }
        if (!canAfford(p, d, c[0], c[1])) { Msg.error(p, "You can't afford this!"); return; }
        if (!takeTickets(p, c[1])) { noTickets(p); return; }
        d.gold -= c[0];
        d.capSteps++;
        m.applyCap(p);
        Sky.profiles().saveNow(p);
        Msg.box(p, "&a&lFARMING CAP RAISED", List.of("&7Your Farming skill can now reach level &a" + (50 + d.capSteps) + "&7."));
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        openAnita(p);
    }

    // =================================================================================================
    // pelts / farmhand
    // =================================================================================================

    void openPeltShop(Player p, String shop, String title) {
        FarmData d = m.data(p);
        Menu menu = new Menu(title, 6).chrome();
        menu.set(4, Icons.of(Material.RABBIT_HIDE, "&5Pelts: " + Text.commasInt(d.pelts),
                "&7Earn pelts hunting for the trapper", "&7in the Trapper's Den."));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int i = 0, hidden = 0;
        for (Tables.ShopEntry e : m.tables.shops.getOrDefault(shop, List.of())) {
            if (i >= slots.length) break;
            ItemStack icon = icon(p, d, e);
            if (icon == null) { hidden++; continue; }
            menu.set(slots[i++], icon, c -> buy(c.player(), e, () -> openPeltShop(c.player(), shop, title)));
        }
        if (i == 0) menu.set(22, Icons.of(Material.BARRIER, "&cNothing to sell yet", "&7These goods arrive with other", "&7parts of the game."));
        menu.set(48, Icons.back("Trapper"), c -> m.trapper.openQuest(c.player()));
        menu.open(p);
    }

    void openFarmhand(Player p) {
        FarmData d = m.data(p);
        Menu menu = new Menu("Farmhand Brody", 4).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        int i = 0;
        for (Tables.ShopEntry e : m.tables.shops.getOrDefault("farmhand", List.of())) {
            if (i >= slots.length) break;
            ItemStack icon = icon(p, d, e);
            if (icon == null) continue;
            menu.set(slots[i++], icon, c -> buy(c.player(), e, () -> openFarmhand(c.player())));
        }
        menu.set(22, Icons.of(Material.BOOK, "&aHow farming tools work", List.of(
                "&7Rookie tools add a little &6Farming Fortune&7.",
                "&7A &aMathematical Hoe Blueprint &7plus &a512",
                "&7of one crop crafts that crop's &aMk. I &7tool,",
                "&7which you then upgrade with tickets.", "", "&eClick to open the Farming menu!")), c -> m.menus.openMain(c.player()));
        menu.set(30, Icons.back("Farming"), c -> m.menus.openMain(c.player()));
        menu.open(p);
    }

    // =================================================================================================
    // generic entries
    // =================================================================================================

    /** Display icon for an entry, or null when its result is not registered by anyone. */
    private ItemStack icon(Player p, FarmData d, Tables.ShopEntry e) {
        ItemStack base;
        String name;
        if (e.medal != null) {
            Material mat = switch (e.medal) { case "gold" -> Material.GOLD_INGOT; case "silver" -> Material.IRON_INGOT; default -> Material.COPPER_INGOT; };
            name = medalColor(e.medal) + e.amount + "x " + Text.titleCase(e.medal) + " Medal";
            base = Icons.of(mat, name);
        } else {
            if (!Sky.items().exists(e.result)) return null;
            try { base = Sky.items().create(e.result, Math.max(1, Math.min(64, e.amount))); }
            catch (Exception ex) { return null; }
        }
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Cost");
        if (e.coins > 0) lore.add("&6" + Text.commasInt(e.coins) + " Coins");
        if (e.gold > 0) lore.add("&6" + e.gold + "x Gold Medal");
        if (e.silver > 0) lore.add("&f" + e.silver + "x Silver Medal");
        if (e.bronze > 0) lore.add("&c" + e.bronze + "x Bronze Medal");
        if (e.pelts > 0) lore.add("&5" + Text.commasInt(e.pelts) + " Pelts");
        for (String it : e.items) {
            String[] sp = split(it);
            ItemDef def = Sky.items().def(sp[0]);
            lore.add((def == null ? "&7" + Text.titleCase(sp[0]) : def.rarity.code() + Text.strip(def.name)) + " &8x" + sp[1]);
        }
        lore.add("");
        lore.add(affordable(p, d, e) ? "&eClick to trade!" : "&cYou can't afford this!");
        return Icons.withLore(base, lore);
    }

    private static String medalColor(String medal) { return switch (medal) { case "gold" -> "&6"; case "silver" -> "&f"; default -> "&c"; }; }

    private static String[] split(String cell) {
        int c = cell.lastIndexOf(':');
        if (c > 0) {
            String tail = cell.substring(c + 1);
            if (tail.matches("\\d+") && !Sky.items().exists(cell)) return new String[]{cell.substring(0, c), tail};
        }
        return new String[]{cell, "1"};
    }

    private boolean affordable(Player p, FarmData d, Tables.ShopEntry e) {
        if (e.coins > 0 && Sky.economy().purse(p) < e.coins) return false;
        if (d.gold < e.gold || d.silver < e.silver || d.bronze < e.bronze || d.pelts < e.pelts) return false;
        for (String it : e.items) {
            String[] sp = split(it);
            if (have(p, sp[0]) < Integer.parseInt(sp[1])) return false;
        }
        return true;
    }

    private boolean rate(Player p) {
        long now = System.currentTimeMillis();
        Long t = lastBuy.get(p.getUniqueId());
        if (t != null && now - t < 150) return false;
        lastBuy.put(p.getUniqueId(), now);
        return true;
    }

    private void buy(Player p, Tables.ShopEntry e, Runnable reopen) {
        if (!rate(p)) return;
        FarmData d = m.data(p);
        if (e.medal == null && !Sky.items().exists(e.result)) { Msg.error(p, "That item is not available."); return; }
        if (!affordable(p, d, e)) { Msg.error(p, "You can't afford this!"); return; }
        // Take the items first and verify every one; if anything comes up short (or the coins then fail), hand back
        // exactly what was taken. Medals and pelts are plain numbers in member data, so they move last and cannot fail.
        Map<String, Integer> taken = new HashMap<>();
        boolean ok = true;
        for (String it : e.items) {
            String[] sp = split(it);
            int need = Integer.parseInt(sp[1]);
            int got = takeUpTo(p, sp[0], need);
            if (got > 0) taken.merge(sp[0], got, Integer::sum);
            if (got < need) { ok = false; break; }
        }
        if (ok && e.coins > 0 && !Sky.economy().withdraw(p, e.coins, "farming shop: " + e.result + " x" + e.amount)) ok = false;
        if (!ok) {
            for (Map.Entry<String, Integer> t : taken.entrySet()) Harvest.give(p, t.getKey(), t.getValue(), LootSource.OTHER);
            Msg.error(p, "You can't afford this!");
            return;
        }
        d.gold -= e.gold; d.silver -= e.silver; d.bronze -= e.bronze; d.pelts -= e.pelts;
        if (e.medal != null) {
            switch (e.medal) { case "gold" -> d.gold += e.amount; case "silver" -> d.silver += e.amount; default -> d.bronze += e.amount; }
            Msg.success(p, "Exchanged for " + e.amount + " " + Text.titleCase(e.medal) + " Medal" + (e.amount == 1 ? "" : "s") + "!");
        } else {
            ItemDef def = Sky.items().def(e.result);
            if (def.isUnique()) for (int i = 0; i < e.amount; i++) Sky.loot().give(p, e.result, 1, LootSource.NPC_SHOP);
            else Harvest.give(p, e.result, e.amount, LootSource.NPC_SHOP);
            Msg.success(p, "You bought " + def.rarity.code() + Text.strip(def.name) + (e.amount > 1 ? " &8x" + e.amount : "") + "&a!");
        }
        Sky.profiles().saveNow(p);
        reopen.run();
    }

    // ---- items: inventory first, then sacks ---------------------------------------------------------

    static int have(Player p, String id) {
        return Sky.items().count(p, id) + Sky.service(Features.Sacks.class).count(p, id);
    }

    static boolean take(Player p, String id, int n) { return takeUpTo(p, id, n) >= n; }

    /** Removes up to {@code n} (inventory first, then sacks) and returns how many were removed. */
    static int takeUpTo(Player p, String id, int n) {
        if (n <= 0) return 0;
        int got = Sky.items().remove(p, id, n);
        if (got < n) got += Sky.service(Features.Sacks.class).remove(p, id, n - got);
        return got;
    }

    static int tickets(Player p) { return have(p, "JACOBS_TICKET"); }

    /** Short of tickets: say so, and where a reward that did not fit the inventory waits (core-combat request). */
    private static void noTickets(Player p) {
        Msg.error(p, "You don't have enough tickets!");
        Msg.send(p, "&7Rewards that did not fit your inventory wait in &e/pickupstash&7 - collect them first.");
    }

    private static boolean takeTickets(Player p, int n) {
        if (n <= 0) return true;
        if (tickets(p) < n) return false;
        int got = takeUpTo(p, "JACOBS_TICKET", n);
        if (got < n) { Harvest.give(p, "JACOBS_TICKET", got, LootSource.OTHER); return false; }
        return true;
    }

    static int farmingLevel(Player p) { return Sky.service(SkillService.class).level(p, SkillType.FARMING); }
}
