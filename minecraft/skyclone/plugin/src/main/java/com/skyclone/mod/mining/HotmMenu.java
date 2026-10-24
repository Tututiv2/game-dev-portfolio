package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.MenuClick;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * /hotm - the Heart of the Mountain tree. Two pages of five tiers (the tree is 7 wide and 10 tall);
 * tier markers on the left, powder and tokens on the right. Click to unlock/upgrade, shift-click for
 * ten levels, right-click to switch a perk off and on (or to pick a pickaxe ability).
 */
final class HotmMenu {

    private final MiningModule m;

    HotmMenu(MiningModule m) { this.m = m; }

    void open(Player p, int page) {
        Menu menu = new Menu("Heart of the Mountain", 6).chrome();
        draw(p, menu, page);
        menu.open(p);
    }

    private void draw(Player p, Menu menu, int page) {
        Hotm h = m.hotm;
        HotmTree t = h.tree;
        MiningData d = MiningUtil.data(p);
        int tier = h.tier(p);
        int firstTier = page == 0 ? 1 : 6;
        for (int tt = firstTier; tt < firstTier + 5; tt++) {
            int row = 4 - (tt - firstTier);
            menu.set(row * 9, tierIcon(p, tt, tier, d.hotmXp));
            for (int x = 0; x < 7; x++) {
                HotmTree.Perk perk = t.at(tt, x);
                int slot = row * 9 + 1 + x;
                if (perk == null) { menu.set(slot, Icons.filler()); continue; }
                final int pg = page;
                menu.set(slot, perkIcon(p, perk, tier), c -> click(c, perk, pg));
            }
        }
        // right column: summary
        int tokens = h.tokensFree(p);
        menu.set(8, Icons.of(Material.REDSTONE_BLOCK, "&5Heart of the Mountain",
                "&7Tier: &5" + tier + "&7/" + t.maxTier(), "&7HOTM XP: &5" + Text.commas(d.hotmXp),
                "", "&7Earn HOTM XP from &6commissions&7.", "&7Spend powder and tokens here.", "",
                "&7Selected ability: &6" + PickaxeAbilities.name(d.selectedAbility) + " " + (d.selectedAbility == null ? "" : Text.roman(h.abilityLevel(p)))));
        menu.set(17, Icons.amount(Icons.of(Material.ENDER_EYE, "&5Tokens of the Mountain", "&7Available: &5" + tokens,
                "&7Total earned: &5" + h.tokensTotal(p), "", "&7One token unlocks a perk.", "&7Earned from tiers and the Core."), Math.max(1, tokens)));
        menu.set(26, powderIcon(p, "MITHRIL", Material.PRISMARINE_CRYSTALS));
        menu.set(35, powderIcon(p, "GEMSTONE", Material.PINK_DYE));
        menu.set(44, powderIcon(p, "GLACITE", Material.PACKED_ICE));
        // bottom row
        if (page == 0) menu.set(53, Icons.of(Material.ARROW, "&aTiers 6 - 10", "&7Scroll up the mountain.", "", "&eClick to view!"), c -> open(c.player(), 1));
        else menu.set(45, Icons.of(Material.ARROW, "&aTiers 1 - 5", "&7Scroll down the mountain.", "", "&eClick to view!"), c -> open(c.player(), 0));
        menu.set(48, Icons.back("Mining"), c -> m.menus.openMain(c.player()));
        menu.set(50, Icons.of(Material.TNT, "&cReset Heart of the Mountain",
                "&7Refunds every powder you spent and", "&7every token. Your tiers and HOTM XP", "&7stay.", "",
                "&7Cost: &6" + Text.commas(m.dials.hotmResetCost.get()) + " Coins", "", "&eClick to reset!"), c -> confirmReset(c.player()));
        menu.set(52, Icons.of(Material.BOOK, "&aHow it works",
                "&8• &7Tiers come from &5HOTM XP&7.", "&8• &7Each tier gives &5tokens&7.",
                "&8• &7A token unlocks a perk next to", "&7  one you already own.",
                "&8• &7Levels after the first cost powder.", "&8• &7Right-click a perk to turn it off.",
                "&8• &7Pick one &6Pickaxe Ability&7; right-", "&7  click a pickaxe or drill to use it."));
    }

    private ItemStack tierIcon(Player p, int tt, int tier, double xp) {
        HotmTree t = m.hotm.tree;
        List<String> lore = new ArrayList<>();
        int tok = tt - 1 < t.tokens.length ? t.tokens[tt - 1] : 0;
        lore.add("&7Rewards:");
        lore.add("&8+&5" + tok + " Token" + (tok == 1 ? "" : "s") + " of the Mountain");
        String fl = m.hotm.forgeSlotLine(tt);
        if (fl != null) lore.add(fl);
        if (tt == m.dials.forgeUnlockTier.get()) lore.add("&8+&6Access to The Forge");
        if (tt == 4) lore.add("&8+&dAccess to the Crystal Caverns");
        if (tt == 7) lore.add("&8+&bAccess to the Frozen Tunnels");
        if (tt - 1 < t.skyblockXp.length) lore.add("&8+&b" + t.skyblockXp[tt - 1] + " SkyBlock XP");
        lore.add("");
        if (tt <= tier) {
            lore.add("&a&lUNLOCKED");
            return Icons.amount(Icons.of(Material.LIME_STAINED_GLASS_PANE, "&aTier " + tt, lore), tt);
        }
        if (tt == tier + 1) {
            double need = t.xpForTier(tt), have = xp - t.xpForTier(tier);
            double span = need - t.xpForTier(tier);
            lore.add("&7Progress: " + Text.bar(span <= 0 ? 1 : have / span, 20));
            lore.add("&5" + Text.commas(Math.max(0, have)) + "&7/&5" + Text.commas(span) + " HOTM XP");
            return Icons.amount(Icons.of(Material.YELLOW_STAINED_GLASS_PANE, "&eTier " + tt, lore), tt);
        }
        lore.add("&cLocked");
        return Icons.amount(Icons.of(Material.RED_STAINED_GLASS_PANE, "&cTier " + tt, lore), tt);
    }

    private ItemStack powderIcon(Player p, String type, Material mat) {
        MiningData d = MiningUtil.data(p);
        return Icons.of(mat, MiningUtil.powderColor(type) + MiningUtil.powderName(type),
                "&7You have: " + MiningUtil.powderColor(type) + Text.commas(d.powder(type)),
                "&7Spent: " + MiningUtil.powderColor(type) + Text.commas(d.powderSpent.getOrDefault(type, 0L)),
                "&7Earned in total: " + MiningUtil.powderColor(type) + Text.commas(d.powderEarned.getOrDefault(type, 0L)));
    }

    private ItemStack perkIcon(Player p, HotmTree.Perk perk, int tier) {
        Hotm h = m.hotm;
        MiningData d = MiningUtil.data(p);
        int lvl = d.perk(perk.id);
        boolean disabled = d.disabledPerks.contains(perk.id);
        boolean reach = h.tree.reachable(perk, d.perks, tier);
        List<String> lore = new ArrayList<>();
        Material mat;
        String nameCol;
        if (perk.core()) {
            mat = lvl > 0 ? Material.BEACON : Material.COAL_BLOCK;
            nameCol = lvl > 0 ? "&5" : reach ? "&e" : "&c";
            lore.add("&7Level &5" + lvl + "&8/" + perk.maxLevel);
            lore.add("");
            lore.addAll(coreLore(lvl, m.dials.forgeCoreSlot.get()));
        } else if (perk.ability) {
            boolean sel = perk.id.equals(d.selectedAbility);
            mat = lvl > 0 ? (sel ? Material.EMERALD_BLOCK : Material.GOLD_BLOCK) : Material.COAL_BLOCK;
            nameCol = lvl > 0 ? "&6" : reach ? "&e" : "&c";
            lore.add("&8Pickaxe Ability");
            lore.add("");
            lore.addAll(m.abilities.describe(perk.id, Math.max(1, h.abilityLevel(p))));
            lore.add("&8Cooldown: &a" + (int) m.abilities.baseCooldown(perk.id, h.abilityLevel(p)) + "s");
            if (sel) { lore.add(""); lore.add("&a&lSELECTED"); }
        } else {
            mat = lvl <= 0 ? Material.COAL : lvl >= perk.maxLevel ? Material.DIAMOND : Material.EMERALD;
            nameCol = disabled ? "&7" : lvl > 0 ? "&a" : reach ? "&e" : "&c";
            lore.add(perk.maxLevel > 1 ? "&7Level &a" + lvl + "&8/" + perk.maxLevel : "&8One-time perk");
            lore.add("");
            String cur = desc(perk, Math.max(1, lvl));
            for (String l : Text.wrap(cur, 36)) lore.add(l);
            if (lvl > 0 && lvl < perk.maxLevel && perk.values != null) {
                lore.add("");
                lore.add("&7Next level: &a" + fmt(perk.value(lvl)) + " &8➜ &a" + fmt(perk.value(lvl + 1)));
            }
            if (disabled) { lore.add(""); lore.add("&c&lDISABLED"); }
        }
        lore.add("");
        if (tier < perk.tier) {
            lore.add("&cRequires Heart of the Mountain Tier " + perk.tier + ".");
        } else if (lvl <= 0) {
            if (!reach) lore.add("&cUnlock a perk next to this one first.");
            else {
                lore.add("&7Cost: &51 Token of the Mountain");
                lore.add("");
                lore.add(h.tokensFree(p) > 0 ? "&eClick to unlock!" : "&cYou have no tokens.");
            }
        } else if (lvl < perk.maxLevel) {
            long cost = perk.costToNext(lvl);
            String type = perk.powderFor(lvl);
            lore.add("&7Cost: " + MiningUtil.powderColor(type) + Text.commas(cost) + " " + MiningUtil.powderName(type));
            if (!perk.core() && perk.maxLevel > 1) {
                long ten = h.costOf(perk, lvl, 10);
                lore.add("&8Shift-click: up to 10 levels (" + Text.commas(ten) + ")");
            }
            lore.add("");
            lore.add(d.powder(type) >= cost ? "&eClick to upgrade!" : "&cNot enough powder.");
        } else {
            lore.add("&a&lMAXED");
        }
        if (lvl > 0 && !perk.core()) lore.add(perk.ability ? "&bRight-click to select!" : "&bRight-click to " + (disabled ? "enable" : "disable") + "!");
        String name = nameCol + perk.name;
        ItemStack it = Icons.of(mat, name, lore);
        if (perk.maxLevel > 1 && lvl > 0) it = Icons.amount(it, Math.min(64, lvl));
        if (lvl > 0 && !disabled) it = Icons.glow(it);
        return it;
    }

    static String fmt(double v) { return v == Math.rint(v) ? Text.commas(v) : String.valueOf(Math.round(v * 100) / 100.0); }

    static String desc(HotmTree.Perk perk, int lvl) {
        String d = perk.desc == null ? "" : perk.desc;
        double v = perk.value(lvl);
        double v2 = 0;
        if (perk.values_seacreaturechance != null) { v = HotmTree.Perk.at(perk.values_fishingspeed, lvl); v2 = HotmTree.Perk.at(perk.values_seacreaturechance, lvl); }
        if (perk.values_chance != null) { v = HotmTree.Perk.at(perk.values_chance, lvl); v2 = HotmTree.Perk.at(perk.values_locks, lvl); }
        if ("daily_grind".equals(perk.id) || "daily_powder".equals(perk.id)) return d.replace("{v}", "500 x HOTM tier");
        return "&7" + d.replace("{v}", fmt(v)).replace("{v2}", fmt(v2));
    }

    static List<String> coreLore(int lvl) { return coreLore(lvl, false); }

    /** {@code forgeSlot}: dial mining.forge-core-slot - otherwise Forge slots come from the HOTM tiers alone (wiki /The_Forge). */
    static List<String> coreLore(int lvl, boolean forgeSlot) {
        String[] r = {"&8+&c1 Pickaxe Ability Level", forgeSlot ? "&8+&a1 Forge Slot" : "&8(no reward here: Forge slots come from HOTM tiers)", "&8+&a1 Commission Slot",
                "&8+&21 base Mithril Powder &7when mining Mithril", "&8+&51 Token of the Mountain",
                "&8+&d2 base Gemstone Powder &7when mining gems", "&8+&51 Token of the Mountain",
                "&8+&b3 base Rimestone Powder &7from frozen metals", "&8+&a10% &7more frozen mineshafts", "&8+&52 Tokens of the Mountain"};
        List<String> out = new ArrayList<>();
        for (int i = 0; i < r.length; i++) out.add((lvl > i ? "&a✔ " : "&8✖ ") + r[i]);
        return out;
    }

    private void click(MenuClick c, HotmTree.Perk perk, int page) {
        Player p = c.player();
        MiningData d = MiningUtil.data(p);
        int lvl = d.perk(perk.id);
        if (c.right() && lvl > 0 && !perk.core()) {
            if (perk.ability) {
                d.selectedAbility = perk.id;
                Msg.success(p, "Selected " + PickaxeAbilities.name(perk.id) + " as your Pickaxe Ability!");
            } else {
                if (!d.disabledPerks.remove(perk.id)) d.disabledPerks.add(perk.id);
                Sky.stats().invalidate(p);
                Msg.send(p, "&7" + perk.name + " is now " + (d.disabledPerks.contains(perk.id) ? "&cdisabled" : "&aenabled") + "&7.");
            }
            open(p, page);
            return;
        }
        if (perk.ability && lvl > 0) {
            d.selectedAbility = perk.id;
            Msg.success(p, "Selected " + PickaxeAbilities.name(perk.id) + " as your Pickaxe Ability!");
            open(p, page);
            return;
        }
        String err = lvl <= 0 ? m.hotm.unlock(p, perk) : m.hotm.upgrade(p, perk, c.shift() ? 10 : 1);
        if (err != null) Msg.error(p, err);
        else {
            Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f);
            if (perk.core()) Msg.send(p, "&5Core of the Mountain is now level " + d.perk(perk.id) + "!");
        }
        open(p, page);
    }

    private void confirmReset(Player p) {
        double cost = m.dials.hotmResetCost.get();
        Sky.menus().confirm(p, "Reset HOTM?", List.of("&7Refunds all powder and tokens.", "&7Costs &6" + Text.commas(cost) + " Coins&7."), () -> {
            if (cost > 0 && !Sky.economy().withdraw(p, cost, "mining: hotm reset")) { Msg.error(p, "You need " + Text.commas(cost) + " coins in your purse."); return; }
            m.hotm.reset(p);
            Msg.success(p, "Your Heart of the Mountain was reset. Powder and tokens refunded!");
            open(p, 0);
        }, () -> open(p, 0));
    }
}
