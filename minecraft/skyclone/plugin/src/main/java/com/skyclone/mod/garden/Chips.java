package com.skyclone.mod.garden;

import com.skyclone.api.Sky;
import com.skyclone.api.item.Rarity;
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
import java.util.Map;

/**
 * Garden chips (lane 13; wiki "Garden Chips", Oct 2026). Redeeming the first chip unlocks it at level 1 (Rare);
 * 4 more make it Epic, 16 more Legendary. Rarity sets the level cap (10 / 15 / 20) and the value per level
 * (e.g. Vermin Vaporizer 3 / 4 / 5 Bonus Pest Chance). Levels 2-20 are bought with Sowdust, which farming on the
 * Garden earns ({@link #earn}). Chip state is the profile's (co-op shared), like the Copper that buys them.
 */
final class Chips {

    private Chips() {}

    static final String SOWDUST = "&2✿ Sowdust";

    // ---- state ------------------------------------------------------------------------------------

    /** Rarity index of a chip (0 Rare, 1 Epic, 2 Legendary), -1 when none is redeemed. */
    static int rarity(GardenData d, String id) {
        return EffectMath.chipRarity(d.redeemed(id), G.T.garden.chipRules.chipsFor);
    }

    /** Highest level this chip may reach right now (its rarity's cap, under the {@code garden.chip-max-level} ceiling). */
    static int cap(GardenData d, String id) {
        return Math.min(G.chipMax.get(), EffectMath.chipCap(rarity(d, id), G.T.garden.chipRules.caps));
    }

    /** The chip's working level: never above its rarity's cap (an old save's one-chip-a-level count is clamped here). */
    static int level(GardenData d, String id) {
        int lvl = d.chip(id);
        if (lvl <= 0) return 0;
        return Math.max(1, Math.min(lvl, cap(d, id)));
    }

    /** Sowdust for the next level, or -1 at the cap. */
    static long nextCost(GardenData d, String id) {
        int lvl = level(d, id);
        if (lvl <= 0 || lvl >= cap(d, id)) return -1;
        long c = EffectMath.chipLevelCost(lvl, G.T.garden.chipRules.sowdustCost);
        return c < 0 ? -1 : Math.round(c * Math.max(0, G.chipSowdustMult.get()));
    }

    static String rarityName(int r) {
        List<String> names = G.T.garden.chipRules.rarities;
        if (r < 0 || names.isEmpty()) return "&7Not redeemed";
        Rarity rr = Rarity.valueOf(names.get(Math.min(r, names.size() - 1)));
        return rr.code() + Text.titleCase(rr.name());
    }

    // ---- redeeming ------------------------------------------------------------------------------

    static void redeem(Player p, ItemStack hand, String id) {
        GardenData d = G.data(p);
        int[] need = G.T.garden.chipRules.chipsFor;
        int have = d.redeemed(id);
        int max = need.length == 0 ? 1 : need[need.length - 1];
        if (have >= max) { Msg.error(p, "That chip is already Legendary! Level it up with Sowdust in the Garden Chips menu."); return; }
        int n = p.isSneaking() ? Math.min(hand.getAmount(), max - have) : 1;
        if (hand.getAmount() > n) hand.setAmount(hand.getAmount() - n); else p.getInventory().setItemInMainHand(null);
        int before = rarity(d, id);
        d.chipsRedeemed.put(id, have + n);
        if (d.chip(id) <= 0) d.chips.put(id, 1);                 // the first chip unlocks it at level 1
        int after = rarity(d, id);
        Sky.stats().invalidate(p);
        GardenTables.Chip c = G.T.garden.chips.get(id);
        String name = c == null ? id : c.name;
        if (after > before && before >= 0)
            Msg.success(p, "CHIP UPGRADED! &7" + name + " is now " + rarityName(after) + "&7: max level &a" + cap(d, id) + "&7.");
        else if (before < 0)
            Msg.success(p, "CHIP REDEEMED! &7" + name + " unlocked at level &a1&7 (" + rarityName(after) + "&7).");
        else {
            int nextAt = after + 1 < need.length ? need[after + 1] : -1;
            Msg.success(p, "CHIP REDEEMED! &7" + name + ": &a" + (have + n) + " &7chips"
                    + (nextAt > 0 ? " &8(" + rarityName(after + 1) + " &8at " + nextAt + ")" : "") + "&7.");
        }
        Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f);
    }

    /** Spend Sowdust on one level. Only in the Garden (wiki: "spent to upgrade Garden Chips while in The Garden"). */
    static void levelUp(Player p, String id) {
        GardenData d = G.data(p);
        if (!G.onGarden(p)) { Msg.error(p, "Chips can only be levelled up while you are in the Garden!"); return; }
        int lvl = level(d, id);
        if (lvl <= 0) { Msg.error(p, "Redeem this chip first!"); return; }
        long cost = nextCost(d, id);
        if (cost < 0) {
            Msg.error(p, rarity(d, id) < G.T.garden.chipRules.caps.length - 1
                    ? "This chip is at its rarity's max level: redeem more chips to raise it!" : "This chip is maxed out!");
            return;
        }
        if (d.sowdust < cost) { Msg.error(p, "You need " + Text.commasInt(cost) + " Sowdust (you have " + Text.commasInt(Math.floor(d.sowdust)) + ")!"); return; }
        d.sowdust -= cost;
        d.chips.put(id, lvl + 1);
        Sky.stats().invalidate(p);
        GardenTables.Chip c = G.T.garden.chips.get(id);
        Msg.success(p, "CHIP LEVEL UP! &7" + (c == null ? id : c.name) + " &8" + lvl + " ➜ &a" + (lvl + 1) + " &7(&2-" + Text.commasInt(cost) + "✿&7)");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.6f);
    }

    // ---- earning Sowdust --------------------------------------------------------------------------

    /** The Sowdust boost in percent: the Elephant's Abundant Harvest and the Earthy reforge on the held tool. */
    static double boostPct(Player p) {
        double b = Math.max(0, Effects.pet(p, "elephant_abundant_harvest"));
        b += Effects.reforgeValue(p.getInventory().getItemInMainHand(), "earthy", 0, G.T.garden.sowdust.earthyPct);
        return b;
    }

    static void earn(GardenData d, double amount) {
        if (amount <= 0) return;
        double a = amount * Math.max(0, G.sowdustMult.get());
        d.sowdust += a;
        d.sowdustEarned += a;
    }

    // ---- menu -------------------------------------------------------------------------------------

    static Menu menu(Player p) {
        GardenData d = G.data(p);
        Menu m = new Menu("Garden Chips", 4).chrome();
        m.set(4, Icons.of(Material.AMETHYST_SHARD, "&aGarden Chips",
                "&7Right-click a chip item to redeem it.", "&7Redeemed chips raise its rarity:",
                "&9Rare &7(1) max &a10&7, &5Epic &7(5) max &a15&7,", "&6Legendary &7(21) max &a20&7.", "",
                "&7Level chips up with " + SOWDUST + "&7, earned", "&7by farming crops in your Garden.", "",
                "&7You have: " + SOWDUST + " &2" + Text.commasInt(Math.floor(d.sowdust))));
        int slot = 10;
        for (Map.Entry<String, GardenTables.Chip> e : G.T.garden.chips.entrySet()) {
            if (slot == 17) slot = 19;
            String id = e.getKey();
            GardenTables.Chip c = e.getValue();
            int lvl = level(d, id), r = rarity(d, id), cap = cap(d, id);
            long cost = nextCost(d, id);
            ItemStack it = G.exists(id) ? G.icon(id, 1) : Icons.of(Material.AMETHYST_SHARD, "&9" + c.name);
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("&7Rarity: " + rarityName(r) + " &8(" + d.redeemed(id) + " chips)");
            lore.add("&7Level: &a" + lvl + "&7/&a" + cap);
            lore.add("&7Effect now: &a" + (lvl > 0 ? effect(c, lvl, r) : "none"));
            if (lvl > 0 && cost >= 0) {
                lore.add("&7Next level: &a" + effect(c, lvl + 1, r));
                lore.add("&7Cost: " + SOWDUST + " &2" + Text.commasInt(cost));
                lore.add("");
                lore.add(d.sowdust >= cost ? "&eClick to level up!" : "&cNot enough Sowdust!");
            } else if (lvl > 0) {
                lore.add("");
                lore.add(r < G.T.garden.chipRules.caps.length - 1 ? "&7Redeem more chips to raise the cap." : "&aMaxed out!");
            }
            it = Icons.withLore(it, lore);
            m.set(slot++, Icons.amount(it, Math.max(1, lvl)), cl -> { levelUp(cl.player(), id); menu(cl.player()).open(cl.player()); });
        }
        m.back("The Desk", () -> Desk.build(p));
        return m;
    }

    static String effect(GardenTables.Chip c, int lvl) { return effect(c, lvl, 0); }

    static String effect(GardenTables.Chip c, int lvl, int rarity) {
        String v = Effects.fmt(Math.round(lvl * c.perAt(rarity) * 100) / 100.0);
        return switch (c.kind) {
            case "ff" -> "+" + v + "☘ Farming Fortune";
            case "wisdom" -> "+" + v + "☯ Farming Wisdom";
            case "overbloom" -> "+" + v + " Overbloom";
            case "pest_chance" -> "+" + v + "ൠ Bonus Pest Chance";
            case "greenhouse" -> "+" + v + "% Greenhouse base crops";
            case "analyzer_copper" -> "+" + v + "% Copper from the Crop Analyzer";
            case "visitor_harvest" -> v + "% sooner visitors while harvesting";
            case "ff_buffs" -> "+" + v + "% temporary Farming Fortune buffs";
            case "contest_fortune" -> "+" + v + " Crop Fortune in a Harvest Fair";
            case "tool_xp" -> "+" + v + "% farming tool experience";
            default -> v;
        };
    }
}
