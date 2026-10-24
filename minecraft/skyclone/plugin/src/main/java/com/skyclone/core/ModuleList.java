package com.skyclone.core;

import java.util.List;

/**
 * Every feature module, in ENABLE order. Core loads each by reflection; a missing or broken one
 * is logged and skipped, so the game still boots without it.
 *
 * <p>Convention: module id "foo" lives in package com.skyclone.mod.foo with entry class
 * com.skyclone.mod.foo.FooModule (id with its first letter upper-cased + "Module").
 *
 * <p>Order only matters for enable(); cross-module lookups belong in postEnable() or at call time.
 * Worlds/builders come after systems so system modules have registered their break handlers,
 * mobs and NPCs before any island is built and populated.
 */
public final class ModuleList {

    private ModuleList() {}

    public static final List<String> IDS = List.of(
            // --- progression systems ---
            "skills",
            "collections",
            "progression",     // skyblock level, objectives/tutorial, fairy souls, bestiary, museum
            "materials",       // fallback catalogue of generic + enchanted materials (registers in postEnable)
            "crafting",
            "enchanting",
            "reforges",
            "pets",
            "accessories",
            "armor",
            "weapons",
            "alchemy",
            "storage",
            "menus",
            // --- economy ---
            "bank",
            "bazaar",
            "auction",
            "shops",
            "mayors",
            // --- gathering & combat systems ---
            "mining",
            "farming",
            "foraging",
            "fishing",
            "slayer",
            "minions",
            "garden",
            "soulflow",        // wave 5: Overflow / soulflow ability gate (Features.Soulflow)
            "hunting",         // wave 5: Hunting skill, attribute shards, Critter Safari (Features.Hunting)
            // --- islands (generators + their mobs + their NPC placements) ---
            "island",
            "hub",
            "mines",
            "hollows",
            "combatzones",
            "crimson",
            "galatea",         // wave 5: Lotus Atoll + Torrent Canyon (after fishing/foraging/hub: second arches)
            // --- wave 2 ---
            "dungeons",
            "kuudra",
            "rift",
            // --- wave 5: after progression (they only read other services at use time) ---
            "chocolate",       // Chocolate Factory + Hopsworth's Hunt
            "extras"           // Abiphone, City Projects, Fire Sales, Skins, the Harp
    );

    /** "skills" -> "com.skyclone.mod.skills.SkillsModule" */
    public static String className(String id) {
        return "com.skyclone.mod." + id + "." + Character.toUpperCase(id.charAt(0)) + id.substring(1) + "Module";
    }
}
