package com.skyclone.mod.pets;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 5 (2026-10-06): skins, forms, burrowing, the dig condition, Unbreakable Will, double-count guards. Shipped data. */
class PetWave5Test {

    private static JsonObject fx(PetDefs d, String pet, String type) {
        PetDefs.PetDef p = d.pet(pet);
        assertNotNull(p, pet);
        for (String r : p.rarities) for (PetDefs.Ability a : p.tiers.get(r).abilities)
            for (JsonObject f : a.fx) if (type.equals(f.get("t").getAsString())) return f;
        return null;
    }

    private static PetDefs.Ability ability(PetDefs d, String pet, String key) {
        PetDefs.PetDef p = d.pet(pet);
        for (String r : p.rarities) for (PetDefs.Ability a : p.tiers.get(r).abilities) if (a.key.equals(key)) return a;
        return null;
    }

    @Test
    void skinsAreCosmeticsWithAPriceAndAPet() {
        PetDefs d = PetMathTest.defs();
        assertTrue(d.skins.size() >= 359, "skins loaded: " + d.skins.size());
        Set<String> rarities = Set.copyOf(PetMath.RARITIES);
        for (PetDefs.Skin s : d.skins.values()) {
            assertTrue(s.id.startsWith("PET_SKIN_"), s.id);
            assertFalse(s.pets.isEmpty(), s.id + " fits no pet");
            for (String t : s.pets) assertNotNull(d.pet(t), s.id + " fits unknown pet " + t);
            assertTrue(s.bits > 0, s.id + " has no Bits price");
            assertTrue(rarities.contains(s.rarity), s.id + " rarity " + s.rarity);
            assertTrue(s.skull != null && !s.skull.isBlank(), s.id + " has no texture");
            assertTrue(s.fits(s.pets.get(0)));
            assertFalse(s.fits("NOT_A_PET"));
        }
        // a few well-known ones: a Bee skin only fits a Bee
        PetDefs.Skin bee = null;
        for (PetDefs.Skin s : d.skins.values()) if (s.pets.equals(List.of("BEE"))) { bee = s; break; }
        assertNotNull(bee, "a Bee skin");
        assertFalse(bee.fits("ENDER_DRAGON"));
    }

    @Test
    void digTagsFollowMiningsBlockTable() {
        PetDefs d = PetMathTest.defs();
        assertFalse(d.dig.isEmpty(), "dig.json loaded");
        assertTrue(d.digTags("dwarven_mines", "GRAY_WOOL").contains("mithril"), "gray wool is Mithril in the Dwarven Mines");
        assertTrue(d.digTags("dwarven_mines", "LIGHT_BLUE_WOOL").contains("mithril"));
        assertTrue(d.digTags("dwarven_mines", "COAL_BLOCK").contains("ore"), "pure coal counts as an ore (Goblin Grunt Labour)");
        assertTrue(d.digTags("gold_mine", "GOLD_ORE").contains("ore"));
        assertFalse(d.digTags("dwarven_mines", "POLISHED_DIORITE").contains("mithril"), "titanium is not Mithril");
        assertTrue(d.digTags("crystal_hollows", "RED_STAINED_GLASS").contains("gemstone"));
        assertEquals(List.of(), d.digTags("hub", "OAK_LOG"));
        assertEquals(List.of(), d.digTags(null, "STONE"));
        // the data uses the condition exactly where the reference says "when mining X"
        JsonObject mithril = fx(d, "MITHRIL_GOLEM", "stat");
        assertNotNull(mithril);
        assertTrue(ability(d, "MITHRIL_GOLEM", "mithril_golem_mithril_affinity").fx.get(0).get("when").toString().contains("dig:mithril"));
        assertTrue(ability(d, "GOBLIN", "goblin_grunt_work").fx.get(0).get("when").toString().contains("dig:ore"));
    }

    @Test
    void unbreakableWillAndBurrowingMath() {
        // Last Stand V on four pieces = 20 levels x 2.5% = 50% Defense; Ankylosaurus at 50% makes that 25% more
        assertEquals(25, PetMath.lastStandExtraPct(20, 2.5, 50), 1e-9);
        assertEquals(0, PetMath.lastStandExtraPct(0, 2.5, 50), 1e-9);
        assertEquals(0, PetMath.lastStandExtraPct(5, 2.5, 0), 1e-9);
        // burrowing energy: 3 blocks at 1 each with Efficient Gait 10% = 2.7 spent; idle refills; clamped
        assertEquals(97.3, PetMath.tunnelEnergy(100, 3, 1, 10, 2, 100), 1e-9);
        assertEquals(100, PetMath.tunnelEnergy(99, 0, 1, 10, 2, 100), 1e-9);
        assertEquals(0, PetMath.tunnelEnergy(1, 3, 1, 0, 2, 100), 1e-9);
        assertEquals(97, PetMath.tunnelEnergy(100, 3, 1, -50, 2, 100), 1e-9, "a negative saving never costs more");
        // Beastmaster Crest growth: one step per 25 mythological kills ("Your kills: 0/25"), capped
        assertEquals(0, PetMath.crestSteps(24));
        assertEquals(1, PetMath.crestSteps(25));
        assertEquals(4, PetMath.crestSteps(110));
        assertEquals(PetMath.CREST_MAX_STEPS, PetMath.crestSteps(1_000_000));
        assertEquals(0, PetMath.crestSteps(-5));
    }

    @Test
    void wave5EffectsAreInTheData() {
        PetDefs d = PetMathTest.defs();
        JsonObject morph = fx(d, "RAT", "morph");
        assertNotNull(morph, "Rat Shapeshift");
        assertTrue(morph.get("scale").getAsDouble() > 0 && morph.get("scale").getAsDouble() < 1);
        assertNotNull(fx(d, "RAT", "cheese"), "Rat Cheese Nose");
        assertNotNull(fx(d, "RAT", "rat_blessing"), "Rat Rodent Blessing");
        assertNotNull(fx(d, "ROCK", "sail"), "Rock Rolling Stone");
        JsonObject tunnel = fx(d, "ARMADILLO", "tunnel");
        assertNotNull(tunnel, "Armadillo Burrower");
        assertEquals("crystal_hollows", tunnel.getAsJsonArray("islands").get(0).getAsString());
        assertNotNull(fx(d, "ARMADILLO", "tunnel_energy"), "Armadillo Efficient Gait");
        JsonObject will = fx(d, "ANKYLOSAURUS", "last_stand_boost");
        assertNotNull(will, "Ankylosaurus Unbreakable Will");
        assertEquals(2.5, will.get("per").getAsDouble(), 1e-9);   // enchanting data: Last Stand 2.5% per level
    }

    /** mf_vs reads the killed mob's DEF (core's per-kill hook has no entity): the key must still find its family. */
    @Test
    void magicFindAgainstAFamily() {
        com.skyclone.api.mob.MobDef inq = new com.skyclone.api.mob.MobDef();
        inq.id = "myth_minos_inquisitor"; inq.name = "Minos Inquisitor"; inq.entity = "ZOMBIE";
        assertTrue(PetEffects.matchesKey("mythological", PetEffects.defKey(inq)));
        com.skyclone.api.mob.MobDef yeti = new com.skyclone.api.mob.MobDef();
        yeti.id = "jerry_yeti"; yeti.name = "Yeti"; yeti.entity = "POLAR_BEAR";
        assertTrue(PetEffects.matchesKey("winter", PetEffects.defKey(yeti)));
        assertFalse(PetEffects.matchesKey("mythological", PetEffects.defKey(yeti)));
        PetDefs d = PetMathTest.defs();
        assertEquals("winter", fx(d, "BABY_YETI", "mf_vs").get("match").getAsString(), "Baby Yeti Old Friends");
    }

    /** Perks another module already applies must not ALSO be applied here (double counting). */
    @Test
    void perksOwnedElsewhereAreNotDoubled() {
        PetDefs d = PetMathTest.defs();
        String[][] owned = {{"EERIE", "eerie_fearama"}, {"EERIE", "eerie_fearcreasing"}, {"GHOUL", "ghoul_army_of_the_dead"},
                {"GHOUL", "ghoul_reaper_soul"}, {"KUUDRA", "kuudra_crimson"}};
        for (String[] o : owned) {
            PetDefs.Ability a = ability(d, o[0], o[1]);
            assertNotNull(a, o[1]);
            for (JsonObject f : a.fx) assertEquals("external", f.get("t").getAsString(), o[1] + " is applied by its owner, not by pets");
        }
        assertNull(fx(d, "EERIE", "dmg_per_fear"), "weapons applies Dread Edge");
    }
}
