package com.skyclone.mod.dungeons;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.stat.Stat;
import com.skyclone.mod.dungeons.gear.StarredWeapons;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 5: the starred dungeon weapons are copies of the live base def plus the reference's deltas. */
class StarredWeaponsTest {

    @Test
    void copyAddsTheDeltasAndKeepsTheAbility() {
        ItemDef base = ItemDef.of("SHADOW_FURY", "Shadow Fury", "DIAMOND_SWORD", Rarity.LEGENDARY, ItemCategory.SWORD);
        base.damage = 300;
        base.stats.put(Stat.STRENGTH, 130.0);
        base.stats.put(Stat.SPEED, 30.0);
        base.tags.add("museum");
        base.tags.add("reforgeable");
        AbilityDef a = new AbilityDef("weapons_shadow_fury", "Shadow Dance", AbilityDef.Trigger.RIGHT_CLICK, 0, 15, List.of("&7lore"));
        base.abilities.add(a);
        StarredWeapons.Row r = new StarredWeapons.Row();
        r.id = "STARRED_SHADOW_FURY";
        r.base = "SHADOW_FURY";
        r.rarity = "LEGENDARY";
        r.damage = 10;
        r.abilityDamage = 500;
        r.stats = Map.of("STRENGTH", 30.0, "SPEED", 10.0, "CRIT_DAMAGE", 30.0);
        ItemDef s = StarredWeapons.starred(base, r);
        assertEquals("STARRED_SHADOW_FURY", s.id);
        assertEquals("⚚ Shadow Fury", s.name);
        assertEquals(310, s.damage, 1e-9);
        assertEquals(160, s.stats.get(Stat.STRENGTH), 1e-9);
        assertEquals(40, s.stats.get(Stat.SPEED), 1e-9);
        assertEquals(30, s.stats.get(Stat.CRIT_DAMAGE), 1e-9);
        assertTrue(s.dungeon);
        assertFalse(s.tags.contains("museum"));
        assertTrue(s.tags.contains("reforgeable"));
        assertEquals(1, s.abilities.size());
        assertEquals("weapons_shadow_fury", s.abilities.get(0).id);
        assertEquals(0, s.abilities.get(0).damage, 1e-9);        // an ability with no damage stays without
        // the base def is untouched
        assertEquals(300, base.damage, 1e-9);
        assertEquals(130, base.stats.get(Stat.STRENGTH), 1e-9);
        assertTrue(base.tags.contains("museum"));
    }

    @Test
    void shippedDataIsConsistent() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("data/dungeons/starred_weapons.json")) {
            assertNotNull(in, "starred_weapons.json is on the classpath");
            List<StarredWeapons.Row> rows = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<List<StarredWeapons.Row>>() {}.getType());
            assertTrue(rows.size() >= 8, "the nine starred dungeon weapons");
            for (StarredWeapons.Row r : rows) {
                assertEquals("STARRED_" + r.base, r.id, "a starred id is STARRED_ + its base id");
                assertTrue(r.damage >= 0 && r.abilityDamage >= 0, r.id + " gets weaker when starred");
                for (String k : r.stats.keySet()) Stat.valueOf(k);   // throws on an unknown stat
                assertNotNull(r.grid, r.id + " has a recipe");
                assertEquals(9, r.grid.size());
                assertEquals(r.base + ":1", r.grid.get(4), r.id + ": the base weapon sits in the middle");
            }
        }
    }
}
