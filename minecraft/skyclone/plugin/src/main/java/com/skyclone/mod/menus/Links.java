package com.skyclone.mod.menus;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.skill.SkillService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Opens a {@link FeatureIndex} feature for a player without importing the module that owns it:
 * the owner's feature service when it is really installed, else its registered command, else a
 * fallback built into this module, else "coming soon".
 */
final class Links {

    private Links() {}

    /** Built-in screens of this module, keyed by the "menus:x" service names in FeatureIndex. */
    static Map<String, Consumer<Player>> builtIns = Map.of();

    /**
     * Built-ins that only stand in when the owning module is missing (tried AFTER commands).
     * "menus:bags": storage owns /bags (it enables first, so its registration wins) and its Your Bags
     * menu knows the quiver lock, the sack of sacks and the vault; ours is only the fallback.
     */
    private static final java.util.Set<String> FALLBACK_ONLY = java.util.Set.of("menus:quests", "menus:effects", "menus:home", "menus:bags");

    /** Feature-service keys: installed? and how to open. */
    static boolean serviceInstalled(String key) {
        if (key == null) return false;
        return switch (key) {
            case "skills" -> Sky.has(SkillService.class);
            case "collections" -> Sky.has(Features.Collections.class);
            case "pets" -> Sky.has(Features.Pets.class);
            case "storage", "wardrobe" -> Sky.has(Features.Storage.class);
            case "accessories" -> Sky.has(Features.Accessories.class);
            case "sacks" -> Sky.has(Features.Sacks.class);
            case "slayer" -> Sky.has(Features.Slayers.class);
            case "auction" -> Sky.has(Features.Auctions.class);
            // wave 5: owners' openMenu methods (core-wave5 section 2); a provider that does not override one
            // runs the owner's own command, so this is never worse than the command path below
            case "bank" -> Sky.has(Features.Bank.class);
            case "equipment" -> Sky.has(Features.Equipment.class);
            case "mining" -> Sky.has(Features.Mining.class);
            case "garden" -> Sky.has(Features.Garden.class);
            case "bestiary" -> Sky.has(Features.Bestiary.class);
            case "museum" -> Sky.has(Features.Museum.class);
            case "sblevel" -> Sky.has(Features.SkyLevel.class);
            case "potionbag" -> Sky.has(Features.Potions.class);
            default -> key.startsWith("menus:") && builtIns.containsKey(key);
        };
    }

    private static boolean openService(Player p, String key) {
        switch (key) {
            case "skills" -> Sky.service(SkillService.class).openMenu(p);
            case "collections" -> Sky.service(Features.Collections.class).openMenu(p);
            case "pets" -> Sky.service(Features.Pets.class).openMenu(p);
            case "storage" -> Sky.service(Features.Storage.class).openStorage(p);
            case "wardrobe" -> Sky.service(Features.Storage.class).openWardrobe(p);
            case "accessories" -> Sky.service(Features.Accessories.class).openBag(p);
            case "sacks" -> Sky.service(Features.Sacks.class).openMenu(p);
            case "slayer" -> Sky.service(Features.Slayers.class).openMenu(p);
            case "auction" -> Sky.service(Features.Auctions.class).open(p);
            case "bank" -> Sky.service(Features.Bank.class).openMenu(p);
            case "equipment" -> Sky.service(Features.Equipment.class).openMenu(p);
            case "mining" -> Sky.service(Features.Mining.class).openMenu(p);
            case "garden" -> Sky.service(Features.Garden.class).openMenu(p);
            case "bestiary" -> Sky.service(Features.Bestiary.class).openMenu(p);
            case "museum" -> Sky.service(Features.Museum.class).openMenu(p);
            case "sblevel" -> Sky.service(Features.SkyLevel.class).openMenu(p);
            case "potionbag" -> Sky.service(Features.Potions.class).openBag(p);
            default -> {
                Consumer<Player> b = builtIns.get(key);
                if (b == null) return false;
                b.accept(p);
            }
        }
        return true;
    }

    /** Open a feature. Returns false (after telling the player) when nothing can open it. */
    static boolean open(Player p, String featureId) {
        FeatureIndex.Feature f = FeatureIndex.get(featureId);
        if (f == null) { MenuKit.comingSoon(p, "That"); return false; }
        try {
            String svc = f.service();
            boolean fallbackOnly = svc != null && FALLBACK_ONLY.contains(svc);
            if (svc != null && !fallbackOnly && serviceInstalled(svc) && openService(p, svc)) return true;
            String[] args = f.args().toArray(new String[0]);
            for (String cmd : f.commands()) {
                if (Sky.commands().run(p, cmd, args)) return true;
            }
            if (svc != null && fallbackOnly && openService(p, svc)) return true;
        } catch (Throwable t) {
            Sky.log().warning("[menus] opening " + featureId + " for " + p.getName() + " failed: " + t);
            MenuKit.deny(p, "Something went wrong opening " + f.name() + ".");
            return false;
        }
        MenuKit.comingSoon(p, f.name());
        return false;
    }

    // ---- availability (for lore and /sb menus links) -------------------------------------------

    /** Is a command name known to the server's command map? Null when the probe itself does not work. */
    static Boolean commandKnown(String name) {
        // Core's own registry first (CommandService.exists, menus Request 2): exact for every command
        // registered through Sky.commands(), no matter how Paper exposes Brigadier commands.
        try { if (Sky.commands().exists(name)) return true; } catch (Throwable ignored) { /* older core */ }
        try {
            var map = Bukkit.getCommandMap();
            if (map.getCommand("sbmenu") == null && map.getCommand("sb") == null) return null;   // probe blind
            return map.getCommand(name) != null;
        } catch (Throwable t) {
            return null;
        }
    }

    enum State { AVAILABLE, MISSING, UNKNOWN }

    static State state(String featureId) {
        FeatureIndex.Feature f = FeatureIndex.get(featureId);
        if (f == null) return State.MISSING;
        if (f.service() != null && serviceInstalled(f.service())) return State.AVAILABLE;
        boolean blind = false;
        for (String c : f.commands()) {
            Boolean k = commandKnown(c);
            if (k == null) blind = true;
            else if (k) return State.AVAILABLE;
        }
        return blind ? State.UNKNOWN : State.MISSING;
    }

    /** Lore lines for a feature's availability: nothing when available. */
    static List<String> stateLore(String featureId) {
        return switch (state(featureId)) {
            case MISSING -> List.of("", "&cComing soon!");
            default -> List.of();
        };
    }

    /** "&amp;8/pets" hint, or empty. */
    static String commandHint(String featureId) {
        FeatureIndex.Feature f = FeatureIndex.get(featureId);
        if (f == null || f.primaryCommand() == null) return "";
        String args = f.args().isEmpty() ? "" : " " + String.join(" ", f.args());
        return "&8/" + f.primaryCommand() + args;
    }
}
