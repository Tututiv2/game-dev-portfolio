package com.skyclone.mod.accessories;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.stat.Stat;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave 3: the effects that used to be lore-only hooks now ship as real, typed effects, with the
 * numbers their lore states, and no longer point at another system.
 */
class HookEffectsTest {

    private static final Map<String, JsonObject> BY_ID = new HashMap<>();

    private static Map<String, JsonObject> all() throws Exception {
        if (!BY_ID.isEmpty()) return BY_ID;
        for (String f : new String[]{"items.json", "equipment.json"}) {
            try (InputStream in = HookEffectsTest.class.getClassLoader().getResourceAsStream("data/accessories/" + f)) {
                assertNotNull(in, f);
                JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
                for (JsonElement e : arr) BY_ID.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
            }
        }
        return BY_ID;
    }

    private static List<Fx> fx(String id) throws Exception {
        JsonObject o = all().get(id);
        assertNotNull(o, id + " is shipped");
        List<Fx> flat = new ArrayList<>();
        for (Fx f : Fx.parseAll(o.getAsJsonObject("extra"), id)) f.flatten(flat);
        return flat;
    }

    private static Fx one(String id, String type) throws Exception {
        for (Fx f : fx(id)) if (f.type.equals(type)) {
            assertTrue(f.known, id + " " + type + " is a known type");
            return f;
        }
        throw new AssertionError(id + " has no " + type + " effect");
    }

    private static boolean hooked(String id) throws Exception {
        JsonObject extra = all().get(id).getAsJsonObject("extra");
        return extra != null && extra.has("hook");
    }

    @Test
    void vaccineLineResistsPoisonByItsLore() throws Exception {
        assertEquals(10, one("VACCINE_TALISMAN", "poison_resist").pct);
        assertEquals(25, one("VACCINE_RING", "poison_resist").pct);
        assertEquals(50, one("VACCINE_ARTIFACT", "poison_resist").pct);
        assertFalse(hooked("VACCINE_ARTIFACT"));
    }

    @Test
    void combatEquipmentIsLive() throws Exception {
        assertEquals(3, one("MAGNETIC_TALISMAN", "magnet").mult);
        assertEquals("ghast", one("NETHERRACK_LOOKING_SUNSHADE", "unseen").mob);
        assertEquals(10, one("BLAZE_BELT", "reflect").chance);
        assertEquals(20, one("VANQUISHED_BLAZE_BELT", "reflect").chance);
        Fx c = one("GAUNTLET_OF_CONTAGION", "contagion");
        assertEquals(10, c.pct);
        assertEquals(2, c.range);
        Fx sc = one("SCOVILLE_BELT", "nearby_def");
        assertEquals("burning", sc.mob);
        assertEquals(50, sc.per);
        assertEquals(1000, sc.cap);
        assertEquals("MAGMA_CREAM", one("MAGMA_NECKLACE", "drop_boost").item);
        assertEquals(20, one("VANQUISHED_MAGMA_NECKLACE", "drop_boost").pct);
        assertEquals(5, one("SHADOW_ASSASSIN_CLOAK", "tp_strike").window);
        assertEquals(10, one("STARRED_SHADOW_ASSASSIN_CLOAK", "tp_strike").pct);
        Fx dg = one("DRAGONFUSE_GLOVE", "weapon_buff");
        assertEquals("ASPECT_OF_THE_DRAGON", dg.item);
        assertEquals(35, dg.dmg);
        assertEquals(50, dg.str);
        assertEquals(25, one("IMPLOSION_BELT", "ability_boost").pct);
        assertEquals(10, one("MANTICORE_CLAW", "pet_copy").pct);
        Fx st = one("THUNDERBOLT_NECKLACE", "static");
        assertEquals(30, st.window);
        assertEquals(2, st.cap);
        assertEquals(15, st.pct);
        for (String id : new String[]{"MAGNETIC_TALISMAN", "BLAZE_BELT", "GAUNTLET_OF_CONTAGION", "SCOVILLE_BELT", "MAGMA_NECKLACE",
                "SHADOW_ASSASSIN_CLOAK", "DRAGONFUSE_GLOVE", "IMPLOSION_BELT", "THUNDERBOLT_NECKLACE", "SHRIVELED_BRACELET", "MANTICORE_CLAW"}) {
            assertFalse(hooked(id), id + " no longer waits on another system");
        }
    }

    @Test
    void shriveledBraceletGivesBothStatsAgainstArthropods() throws Exception {
        double wisdom = 0, mf = 0;
        for (Fx f : fx("SHRIVELED_BRACELET")) {
            // wave 5: the Magic Find half moved to core's per-target hook (mf_vs), so only arthropod drops see it
            if (f.type.equals("mf_vs")) {
                assertEquals("arthropod", f.mob);
                mf += f.value;
                continue;
            }
            if (!f.type.equals("vs_stat")) continue;
            assertEquals("arthropod", f.mob);
            if (f.stat == Stat.COMBAT_WISDOM) wisdom += f.value;
            assertTrue(f.stat != Stat.MAGIC_FIND, "MF must not also be a last-target stat (it would count twice)");
        }
        assertEquals(5, wisdom);
        assertEquals(10, mf);
    }

    @Test
    void explosionAbilitiesMatchByName() {
        DamageContext implosion = new DamageContext(null, null, DamageContext.Kind.MAGIC, null, 100);
        implosion.tags.add("ability");
        implosion.tags.add("ability:Implosion");
        DamageContext aote = new DamageContext(null, null, DamageContext.Kind.MAGIC, null, 100);
        aote.tags.add("ability:Instant Transmission");
        DamageContext bow = new DamageContext(null, null, DamageContext.Kind.MAGIC, null, 100);
        bow.tags.add("ability:Explosive Shot");
        String match = "implosion|explos|blast|wither impact";
        assertTrue(EffectEngine.matchesAbility(implosion, match));
        assertTrue(EffectEngine.matchesAbility(bow, match));
        assertFalse(EffectEngine.matchesAbility(aote, match));
        assertFalse(EffectEngine.matchesAbility(implosion, null));
    }

    @Test
    void tieredBonusesNeverPayAPieceTwice() throws Exception {
        // armour pays per armour piece; equipment pays only what is left under the tier cap
        assertEquals(4, AccMath.tieredEquipPieces(4, 4, 8));
        assertEquals(0, AccMath.tieredEquipPieces(4, 4, 4));   // Deepness Within: armour already filled it
        assertEquals(2, AccMath.tieredEquipPieces(2, 4, 4));
        assertEquals(3, AccMath.tieredEquipPieces(0, 3, 8));
        assertEquals(0, AccMath.tieredEquipPieces(0, 0, 8));
        for (String id : new String[]{"ANGLER_BELT", "ARACHNE_BELT", "BACKWATER_BELT"}) {
            boolean any = false;
            for (Fx f : Fx.parseAll(all().get(id).getAsJsonObject("extra"), id)) {
                if (!f.type.equals("set")) continue;
                any = true;
                assertTrue(f.perPiece, id + " " + f.name + " is a per-piece tiered bonus");
            }
            assertTrue(any, id + " has its tiered bonus");
        }
    }

    @Test
    void vanquishedPiecesBuildUpOnThePiece() throws Exception {
        assertEquals(0, AccMath.pieceBonus(999, 1000, 10));
        assertEquals(1, AccMath.pieceBonus(1000, 1000, 10));
        assertEquals(7, AccMath.pieceBonus(7_500, 1000, 10));
        assertEquals(10, AccMath.pieceBonus(5_000_000, 1000, 10));
        assertEquals(0, AccMath.pieceBonus(5_000, 0, 10));
        assertEquals("blaze", one("VANQUISHED_BLAZE_BELT", "piece_bonus").mob);
        assertEquals(Stat.STRENGTH, one("VANQUISHED_BLAZE_BELT", "piece_bonus").stat);
        assertEquals(Stat.DEFENSE, one("VANQUISHED_GHAST_CLOAK", "piece_bonus").stat);
        assertEquals(Stat.CRIT_DAMAGE, one("VANQUISHED_MAGMA_NECKLACE", "piece_bonus").stat);
        Fx glow = one("VANQUISHED_GLOWSTONE_GAUNTLET", "piece_bonus");
        assertEquals("GLOWSTONE_DUST", glow.item);   // what crimson's Gather event names
        assertEquals(Stat.INTELLIGENCE, glow.stat);
        for (String id : new String[]{"VANQUISHED_BLAZE_BELT", "VANQUISHED_GHAST_CLOAK", "VANQUISHED_MAGMA_NECKLACE", "VANQUISHED_GLOWSTONE_GAUNTLET"}) {
            assertFalse(hooked(id), id);
        }
    }

    @Test
    void everyShippedEffectTypeIsKnown() throws Exception {
        List<String> bad = new ArrayList<>();
        for (String id : all().keySet()) for (Fx f : fx(id)) if (!f.known) bad.add(id + ":" + f.type);
        assertTrue(bad.isEmpty(), bad.toString());
    }
}
