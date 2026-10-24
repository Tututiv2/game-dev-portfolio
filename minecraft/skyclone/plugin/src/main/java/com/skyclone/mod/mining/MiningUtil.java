package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Text;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Small shared helpers. */
final class MiningUtil {

    private MiningUtil() {}

    /** Islands where the custom breaking engine and the mining perks apply ("Mining Islands"). */
    static final Set<String> MINING_ISLANDS = Set.of("gold_mine", "deep_caverns", "dwarven_mines", "crystal_hollows",
            "glacite_tunnels", "mineshaft");
    /** Islands with powder, commissions and the HOTM economy. */
    static final Set<String> POWDER_ISLANDS = Set.of("dwarven_mines", "crystal_hollows", "glacite_tunnels", "mineshaft");
    static final List<String> POWDERS = List.of("MITHRIL", "GEMSTONE", "GLACITE");
    static final String DOT = "᠅";

    static MiningData data(Player p) { return Sky.profiles().member(p, "mining", MiningData.class); }

    static boolean ready(Player p) { return p != null && p.isOnline() && Sky.profiles().isReady(p); }

    static String island(Player p) { return island(p.getLocation()); }

    static String island(Location l) {
        if (l == null || l.getWorld() == null) return null;
        try { return Sky.worlds().islandOf(l.getWorld()); } catch (Throwable t) { return null; }
    }

    static boolean onMiningIsland(Player p) {
        String i = island(p);
        return i != null && MINING_ISLANDS.contains(i);
    }

    /** Player setting keys mining reads (menus' Settings, request 13): off = no chat/sound alert of that kind. */
    static final String SETTING_COMMISSION_ALERTS = "mining.commission_alerts", SETTING_EVENT_ALERTS = "mining.event_alerts";

    /** The player's Settings toggle {@code key} (true when no settings module provides it, or on any error). */
    static boolean setting(Player p, String key) {
        try {
            var s = Sky.service(com.skyclone.api.feature.Features.Settings.class);
            return s == null || s.flag(p, key, true);
        } catch (RuntimeException e) { return true; }
    }

    /**
     * One number of the player's active pet ability (0 with no pet, no such ability, or no pets module).
     * Keys are {@code <pet>_<ability_snake>} from the pets data, token "a" is the first number.
     */
    static double pet(Player p, String abilityKey) {
        try {
            com.skyclone.api.feature.Features.Pets pets = Sky.service(com.skyclone.api.feature.Features.Pets.class);
            return pets == null ? 0 : Math.max(0, pets.abilityValue(p, abilityKey, "a"));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The pet ability that adds "+X% more" of this powder type (Mithril Golem / Tunnel Wyrm / Glacite Golem). */
    static String petPowderAbility(String type) {
        return switch (type == null ? "" : type) {
            case "GEMSTONE" -> "scatha_bejeweled_eyes";
            case "GLACITE" -> "glacite_golem_powder_powered";
            default -> "mithril_golem_the_smell_of_powder";
        };
    }

    static String today() { return LocalDate.now(ZoneOffset.UTC).toString(); }

    /** Reset the per-day counters when the UTC date changed. */
    static void rollDay(MiningData d) {
        String t = today();
        if (!t.equals(d.dailyDate)) {
            d.dailyDate = t;
            d.dailyCommissions = 0;
            d.dailyGrind.clear();
            d.dailyPowderDone = false;
            d.gemsToday = 0;
        }
    }

    static String powderColor(String type) {
        return switch (type == null ? "" : type) {
            case "GEMSTONE" -> "&d";
            case "GLACITE" -> "&b";
            default -> "&2";
        };
    }

    static String powderName(String type) {
        return switch (type == null ? "" : type) {
            case "GEMSTONE" -> "Gemstone Powder";
            case "GLACITE" -> "Rimestone Powder";
            default -> "Mithril Powder";
        };
    }

    /** "&2᠅ Mithril Powder" */
    static String powderLabel(String type) { return powderColor(type) + DOT + " " + powderName(type); }

    static String powderOf(String island) {
        if (island == null) return "MITHRIL";
        return switch (island) {
            case "crystal_hollows" -> "GEMSTONE";
            case "glacite_tunnels", "mineshaft" -> "GLACITE";
            default -> "MITHRIL";
        };
    }

    static String parsePowder(String s) {
        if (s == null) return null;
        String u = s.trim().toUpperCase();
        if (u.startsWith("MITH")) return "MITHRIL";
        if (u.startsWith("GEM")) return "GEMSTONE";
        if (u.startsWith("GLAC") || u.startsWith("RIME")) return "GLACITE";
        return null;
    }

    /** Display name of an item id, coloured by rarity, safe for unknown ids. */
    static String itemName(String id) {
        if ("COINS".equals(id)) return "&6Coins";
        ItemDef d = Sky.items().def(id);
        if (d == null) return "&7" + Text.titleCase(id);
        String n = d.name == null ? Text.titleCase(id) : d.name;
        return (n.startsWith("&") ? "" : d.rarity.code()) + n;
    }

    static boolean exists(String id) {
        if (id == null) return false;
        if ("COINS".equals(id)) return true;
        try { return Sky.items().exists(id); } catch (Throwable t) { return false; }
    }

    static String heldId(Player p) {
        ItemStack h = p.getInventory().getItemInMainHand();
        return h == null || h.getType().isAir() ? null : Sky.items().idOf(h);
    }

    static ThreadLocalRandom rnd() { return ThreadLocalRandom.current(); }

    static String fmt(double v) { return Text.commas(v); }

    /** "ID:amount" with ids that may contain ':' (core recipe-cell rules). */
    static String[] cell(String c) {
        if (c == null || c.isEmpty()) return null;
        if (exists(c)) return new String[]{c, "1"};
        int i = c.lastIndexOf(':');
        if (i < 0) return new String[]{c, "1"};
        return new String[]{c.substring(0, i), c.substring(i + 1)};
    }
}
