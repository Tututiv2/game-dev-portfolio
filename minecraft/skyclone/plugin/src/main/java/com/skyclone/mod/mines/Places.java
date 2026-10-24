package com.skyclone.mod.mines;

import com.skyclone.mod.mines.gen.DeepCavernsGenerator;
import com.skyclone.mod.mines.gen.DwarvenMinesGenerator;

import java.util.List;

/**
 * Names and small facts about the three mines islands, shared by menus, commands and tests.
 * Pure Java: no Bukkit.
 */
public final class Places {

    private Places() {}

    public static final String GOLD = "gold_mine", DEEP = "deep_caverns", DWARVEN = "dwarven_mines";
    public static final List<String> ISLANDS = List.of(GOLD, DEEP, DWARVEN);

    /** Number of Deep Caverns layers. */
    public static int layers() { return DeepCavernsGenerator.LAYER_IDS.length; }

    /** 1-based layer number of a Deep Caverns zone id (layer zones, lift stations, haul roads, the arrival hall), or 0. */
    public static int layerOfZone(String zoneId) {
        if (zoneId == null) return 0;
        for (int i = 0; i < DeepCavernsGenerator.LAYER_IDS.length; i++) if (DeepCavernsGenerator.LAYER_IDS[i].equals(zoneId)) return i + 1;
        if (zoneId.startsWith("lift_")) return parse(zoneId.substring(5));
        if (zoneId.equals("arrival")) return 1;
        if (zoneId.equals("sanctuary_gate")) return 6;
        return 0;
    }

    public static String layerName(int n) {
        return n >= 1 && n <= layers() ? DeepCavernsGenerator.LAYER_NAMES[n - 1] : "Unknown";
    }

    public static char layerColor(int n) {
        return n >= 1 && n <= layers() ? DeepCavernsGenerator.LAYER_COLORS[n - 1] : '7';
    }

    /** Ores each Deep Caverns layer is known for (research 03 §9.1). */
    public static String layerOres(int n) {
        return switch (n) {
            case 1 -> "Coal, Iron, Gold";
            case 2 -> "Lapis";
            case 3 -> "Redstone, Gold";
            case 4 -> "Emerald";
            case 5 -> "Diamond";
            case 6 -> "Diamond, Diamond Blocks, Obsidian";
            default -> "";
        };
    }

    /** Mobs of each layer (research 03 §9.1). */
    public static String layerMobs(int n) {
        return switch (n) {
            case 1 -> "Sneaky Creepers";
            case 2 -> "Lapis Zombies";
            case 3 -> "Redstone Pigmen";
            case 4 -> "Emerald Slimes";
            case 5 -> "Miner Zombies, Miner Skeletons";
            case 6 -> "Diamond Zombies, Diamond Skeletons";
            default -> "";
        };
    }

    public static String layerIcon(int n) {
        return switch (n) {
            case 1 -> "TNT";
            case 2 -> "LAPIS_ORE";
            case 3 -> "REDSTONE_ORE";
            case 4 -> "EMERALD_ORE";
            case 5 -> "DIAMOND_ORE";
            case 6 -> "OBSIDIAN";
            default -> "STONE";
        };
    }

    /** Minecart stations in menu order. */
    public static List<String> stations() { return DwarvenMinesGenerator.STATIONS; }

    public static String stationName(String id) {
        return switch (id) {
            case "village" -> "Dwarven Village";
            case "palace" -> "Royal Palace";
            case "forge" -> "The Forge";
            case "gateway" -> "Deepking's Gateway";
            case "base_camp" -> "Dwarven Base Camp";
            default -> {
                for (DwarvenMinesGenerator.District d : DwarvenMinesGenerator.DISTRICTS) if (d.id().equals(id)) yield d.name();
                yield id;
            }
        };
    }

    public static String stationIcon(String id) {
        return switch (id) {
            case "village" -> "BELL";
            case "palace" -> "GOLD_BLOCK";
            case "forge" -> "ANVIL";
            case "gateway" -> "AMETHYST_CLUSTER";
            case "base_camp" -> "PACKED_ICE";
            case "lava_springs" -> "MAGMA_BLOCK";
            case "goblin_burrows" -> "ZOMBIE_HEAD";
            case "great_ice_wall" -> "BLUE_ICE";
            default -> "PRISMARINE";
        };
    }

    /** Anchors other modules (and this one) rely on, per island (CONTRACT §4 + shops + mining). */
    public static List<String> requiredAnchors(String island) {
        List<String> out = new java.util.ArrayList<>(List.of("spawn"));
        switch (island) {
            case GOLD -> out.addAll(List.of("npc.gold_forger", "npc.iron_forger"));
            case DEEP -> {
                out.addAll(List.of("npc.delver", "npc.blaster"));
                for (int n = 1; n <= layers(); n++) { out.add("npc.lift." + n); out.add("lift." + n); }
            }
            case DWARVEN -> {
                out.addAll(List.of("npc.mine_king", "npc.commissions", "npc.forge", "npc.hotm",
                        "npc.lamplighter", "npc.pickwright", "npc.foreman", "npc.mine_cook",
                        "npc.fetcher", "npc.riddlewright", "npc.drill_mechanic",
                        "npc.barkeep", "npc.archivist", "npc.guard.1", "npc.guard.2", "npc.old_miner",
                        // wave 5: The Lift, the Crystal Caverns warden, the Miner's Guild elder
                        "npc.lift.dwarven", "lift.dwarven", "npc.gwendolyn", "npc.guild"));
                for (String s : stations()) { out.add("npc.ticket." + s); out.add("station." + s); }
            }
            default -> { }
        }
        return out;
    }

    /** Portal areas each island must have (each one a registered warp). */
    public static List<String> requiredPortals(String island) {
        return switch (island) {
            case GOLD -> List.of("portal.hub", "portal.deep_caverns");
            case DEEP -> List.of("portal.hub", "portal.gold_mine", "portal.dwarven_mines");
            case DWARVEN -> List.of("portal.hub", "portal.deep_caverns", "portal.crystal_hollows", "portal.glacite_tunnels");
            default -> List.of();
        };
    }

    /** Ore areas the mining module's regen and zone commissions read. */
    public static List<String> requiredOreAreas(String island) {
        return switch (island) {
            case GOLD -> List.of("ores.pit", "ores.lapis_tunnel", "ores.gold_gallery");
            case DEEP -> java.util.Arrays.stream(DeepCavernsGenerator.LAYER_IDS).map(s -> "ores." + s).toList();
            case DWARVEN -> List.of("ores.ramparts_quarry", "ores.upper_mines", "ores.lava_springs", "ores.cliffside_veins",
                    "ores.royal_mines", "ores.far_reserve", "ores.goblin_burrows", "ores.great_ice_wall", "ores.the_mist");
            default -> List.of();
        };
    }

    /** Fairy souls per island in the research (03 §28): Gold Mine 12, Deep Caverns 21, Dwarven Mines 15 (NEU). */
    public static int researchSouls(String island) {
        return switch (island) { case GOLD -> 12; case DEEP -> 21; case DWARVEN -> 15; default -> 0; };
    }

    /** Minecart fare: free within a dial-set base, plus a per-block rate. Never negative. */
    public static long fare(double base, double perBlock, double distance) {
        return Math.max(0, Math.round(base + perBlock * Math.max(0, distance)));
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
