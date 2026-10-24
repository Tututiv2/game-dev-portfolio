package com.skyclone.mod.dungeons.shop;

import com.skyclone.api.Sky;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.dungeons.DungeonMenus;
import com.skyclone.mod.dungeons.loot.LData;
import com.skyclone.mod.dungeons.loot.RewardChests;
import com.skyclone.mod.dungeons.run.RunManager;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The essence shop (research 04 sec 3.1 "Essence shop"; perk names and level costs from NEU essenceshops.json):
 * Undead essence buys Catacombs perks (chest luck, essence looting, a free Revive Stone, in-dungeon stat %), Wither
 * essence buys permanent stats and stronger blessings. The research gives the costs but not every effect size; those
 * are marked TUNE below. Payment is essence ITEMS from the inventory (see the hand-off: the essence pouch belongs to
 * the reforges module and has no API yet - the "Essence as items" toggle keeps chest essence spendable here).
 */
public final class EssenceShop {

    private EssenceShop() {}

    public record Perk(String id, String essence, String name, Material icon, int[] costs, String effect) {}

    public static final List<Perk> PERKS = List.of(
            new Perk("catacombs_boss_luck", "UNDEAD", "Boss Luck", Material.GOLD_INGOT, new int[]{100, 1_000, 10_000, 100_000},
                    "&7Reward chests roll better: &a+1/+3/+5/+10 &7luck."),
            new Perk("catacombs_looting", "UNDEAD", "Looting", Material.CHEST, new int[]{1_000, 2_000, 3_000, 4_000, 5_000},
                    "&7Reward chests hold &d+5% &7more essence per level."),                    // TUNE
            new Perk("revive_stone", "UNDEAD", "Help of the Fairies", Material.TOTEM_OF_UNDYING, new int[]{200_000},
                    "&7Start every run with a &6Revive Stone&7."),
            new Perk("catacombs_health", "UNDEAD", "Health Essence", Material.RED_DYE, new int[]{1_000, 2_500, 5_000, 10_000, 25_000},
                    "&c+2% ❤ Health &7per level in the Catacombs."),                            // TUNE
            new Perk("catacombs_defense", "UNDEAD", "Defense Essence", Material.IRON_CHESTPLATE, new int[]{1_000, 4_000, 6_000, 8_000, 10_000},
                    "&a+2% ❈ Defense &7per level in the Catacombs."),                           // TUNE
            new Perk("catacombs_strength", "UNDEAD", "Strength Essence", Material.BLAZE_POWDER, new int[]{1_000, 4_000, 6_000, 8_000, 10_000},
                    "&c+2% ❁ Strength &7per level in the Catacombs."),                          // TUNE
            new Perk("catacombs_intelligence", "UNDEAD", "Intelligence Essence", Material.ENCHANTED_BOOK, new int[]{1_000, 4_000, 6_000, 8_000, 10_000},
                    "&b+2% ✎ Intelligence &7per level in the Catacombs."),                      // TUNE
            new Perk("catacombs_crit_damage", "UNDEAD", "Critical Essence", Material.DIAMOND_SWORD, new int[]{1_000, 3_000, 10_000, 20_000, 50_000},
                    "&9+2% ☠ Crit Damage &7per level in the Catacombs."),                       // TUNE
            new Perk("permanent_health", "WITHER", "Forbidden Health", Material.APPLE, new int[]{100, 250, 500, 1_000, 1_500},
                    "&c+2 ❤ Health &7per level, everywhere."),                                  // TUNE
            new Perk("permanent_defense", "WITHER", "Forbidden Defense", Material.SHIELD, new int[]{100, 250, 500, 1_000, 1_500},
                    "&a+1 ❈ Defense &7per level, everywhere."),                                 // TUNE
            new Perk("permanent_speed", "WITHER", "Forbidden Speed", Material.SUGAR, new int[]{100, 250},
                    "&f+1 ✦ Speed &7per level, everywhere."),                                   // TUNE
            new Perk("permanent_intelligence", "WITHER", "Forbidden Intelligence", Material.LAPIS_LAZULI, new int[]{100, 250, 500, 1_000, 1_500},
                    "&b+2 ✎ Intelligence &7per level, everywhere."),                            // TUNE
            new Perk("permanent_strength", "WITHER", "Forbidden Strength", Material.NETHER_WART, new int[]{100, 250, 500, 1_000, 1_500},
                    "&c+1 ❁ Strength &7per level, everywhere."),                                // TUNE
            new Perk("forbidden_blessing", "WITHER", "Forbidden Blessing", Material.BEACON, new int[]{200, 400, 600, 800, 1_000, 1_200, 1_400, 1_600, 1_800, 2_000},
                    "&dBlessings &7are &a+1% &7stronger per level."),                            // TUNE
            new Perk("dungeonbreaker_secrets", "WITHER", "Echoes of the Lost", Material.ECHO_SHARD, new int[]{250, 500, 1_000, 2_000, 5_000},
                    "&b+2 ✯ Magic Find &7per level in the Catacombs."));                        // TUNE: ours

    public static Perk perk(String id) { for (Perk p : PERKS) if (p.id.equals(id)) return p; return null; }

    public static int level(Player p, String id) { return Sky.profiles().isReady(p) ? RewardChests.data(p).perks.getOrDefault(id, 0) : 0; }

    public static void init() {
        Sky.stats().addProvider("dungeons_essence_perks", EssenceShop::stats);
    }

    static void stats(Player p, StatMap into) {
        if (!Sky.profiles().isReady(p)) return;
        LData d = RewardChests.data(p);
        if (d.perks.isEmpty()) return;
        into.add(Stat.HEALTH, 2 * d.perks.getOrDefault("permanent_health", 0));
        into.add(Stat.DEFENSE, d.perks.getOrDefault("permanent_defense", 0));
        into.add(Stat.SPEED, d.perks.getOrDefault("permanent_speed", 0));
        into.add(Stat.INTELLIGENCE, 2 * d.perks.getOrDefault("permanent_intelligence", 0));
        into.add(Stat.STRENGTH, d.perks.getOrDefault("permanent_strength", 0));
        if (RunManager.get() == null || RunManager.get().runOf(p) == null) return;
        into.addPercent(Stat.HEALTH, 2 * d.perks.getOrDefault("catacombs_health", 0));
        into.addPercent(Stat.DEFENSE, 2 * d.perks.getOrDefault("catacombs_defense", 0));
        into.addPercent(Stat.STRENGTH, 2 * d.perks.getOrDefault("catacombs_strength", 0));
        into.addPercent(Stat.INTELLIGENCE, 2 * d.perks.getOrDefault("catacombs_intelligence", 0));
        into.addPercent(Stat.CRIT_DAMAGE, 2 * d.perks.getOrDefault("catacombs_crit_damage", 0));
        into.add(Stat.MAGIC_FIND, 2 * d.perks.getOrDefault("dungeonbreaker_secrets", 0));
    }

    public static void open(Player p) { open(p, "UNDEAD"); }

    public static void open(Player p, String essence) {
        if (!Sky.profiles().isReady(p)) return;
        LData d = RewardChests.data(p);
        String item = "ESSENCE_" + essence;
        long have = owned(p, essence);
        Menu m = new Menu((essence.equals("UNDEAD") ? "Undead" : "Wither") + " Essence Shop", 6).chrome();
        m.set(4, Icons.of(essence.equals("UNDEAD") ? Material.ZOMBIE_HEAD : Material.WITHER_SKELETON_SKULL, "&d" + Text.titleCase(essence) + " Essence",
                "&7You have: &d" + Text.commas(have), "", "&7Perks are paid from your essence", "&7pouch first, then essence items", "&7in your inventory."));
        int slot = 19;
        for (Perk k : PERKS) {
            if (!k.essence.equals(essence)) continue;
            if (slot % 9 == 8) slot += 2;
            int lvl = d.perks.getOrDefault(k.id, 0);
            boolean max = lvl >= k.costs.length;
            List<String> lore = new ArrayList<>();
            lore.add(k.effect);
            lore.add("");
            lore.add("&7Level: &a" + lvl + "&7/" + k.costs.length);
            if (max) lore.add("&aMaxed!");
            else {
                int cost = k.costs[lvl];
                lore.add("&7Next level costs: &d" + Text.commasInt(cost) + " " + Text.titleCase(essence) + " Essence");
                lore.add("");
                lore.add(have >= cost ? "&eClick to upgrade!" : "&cNot enough essence!");
            }
            m.set(slot++, Icons.amount(Icons.of(k.icon, (max ? "&a" : "&e") + k.name + " " + Text.roman(Math.max(1, lvl)), lore), Math.max(1, lvl)),
                    c -> buy(c.player(), k, essence));
        }
        m.set(47, Icons.of(essence.equals("UNDEAD") ? Material.WITHER_SKELETON_SKULL : Material.ZOMBIE_HEAD,
                "&aSwitch to " + (essence.equals("UNDEAD") ? "Wither" : "Undead") + " perks", "&eClick to switch!"),
                c -> open(c.player(), essence.equals("UNDEAD") ? "WITHER" : "UNDEAD"));
        boolean items = d.essenceAsItems != null ? d.essenceAsItems : com.skyclone.mod.dungeons.DDials.ESSENCE_AS_ITEMS_DEFAULT.get();
        m.set(50, Icons.of(items ? Material.LIME_DYE : Material.GRAY_DYE, "&aEssence as items: " + (items ? "&aON" : "&cOFF"),
                "&7ON: reward-chest essence comes as items", "&7you can spend here (right-click one to", "&7put it into your star pouch).",
                "&7OFF: it goes straight into the pouch.", "", "&eClick to toggle!"), c -> {
            LData dd = RewardChests.data(c.player());
            dd.essenceAsItems = !(dd.essenceAsItems != null ? dd.essenceAsItems : com.skyclone.mod.dungeons.DDials.ESSENCE_AS_ITEMS_DEFAULT.get());
            open(c.player(), essence);
        });
        m.set(48, Icons.back("Catacombs"), c -> DungeonMenus.main(c.player()));
        m.open(p);
    }

    /** Essence in the reforges pouch plus essence items in the inventory. */
    static long owned(Player p, String essence) {
        String item = "ESSENCE_" + essence;
        long items = Sky.items().exists(item) ? Sky.items().count(p, item) : 0;
        return items + Math.max(0, Sky.service(com.skyclone.api.feature.Features.Essence.class).essence(p, essence));
    }

    static void buy(Player p, Perk k, String essence) {
        LData d = RewardChests.data(p);
        int lvl = d.perks.getOrDefault(k.id, 0);
        if (lvl >= k.costs.length) { Msg.error(p, "That perk is already maxed."); return; }
        int cost = k.costs[lvl];
        String item = "ESSENCE_" + essence;
        if (owned(p, essence) < cost) { Msg.error(p, "You need " + Text.commasInt(cost) + " " + Text.titleCase(essence) + " Essence."); return; }
        // integrator: pouch first (Features.Essence, reforges), the rest as items; refund the pouch if the items vanish
        var pouch = Sky.service(com.skyclone.api.feature.Features.Essence.class);
        long fromPouch = Math.min(cost, Math.max(0, pouch.essence(p, essence)));
        if (fromPouch > 0 && !pouch.takeEssence(p, essence, fromPouch)) fromPouch = 0;
        int fromItems = (int) (cost - fromPouch);
        if (fromItems > 0 && (!Sky.items().exists(item) || Sky.items().count(p, item) < fromItems || Sky.items().remove(p, item, fromItems) < fromItems)) {
            if (fromPouch > 0) pouch.addEssence(p, essence, fromPouch);
            Msg.error(p, "The essence went missing.");
            return;
        }
        d.perks.put(k.id, lvl + 1);
        Sky.stats().invalidate(p);
        Sky.profiles().saveNow(p);
        Msg.success(p, k.name + " is now level " + Text.roman(lvl + 1) + "!");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
        open(p, essence);
    }
}
