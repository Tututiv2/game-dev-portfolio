package com.skyclone.mod.pets;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Follow-up 2026-10-06 (garden Request 11): a pet can hold the Green Bandana, and
 * {@code abilityValue(p, "held:<ITEM_ID>", "a")} reports 1 while the summoned pet holds it. The garden applies the
 * +4 Farming Fortune per Garden level itself, so the pets fx must stay 'info' (a stat fx would count twice).
 */
class PetHeldFlagTest {

    @Test
    void greenBandanaIsAHoldableInfoOnlyItem() {
        PetDefs d = PetMathTest.defs();
        PetDefs.HeldItem h = d.held.get("GREEN_BANDANA");
        assertNotNull(h, "GREEN_BANDANA must be in held_items.json");
        assertEquals(1, h.fx.size(), "exactly one fx");
        JsonObject fx = h.fx.get(0);
        assertEquals("info", fx.get("t").getAsString(), "the garden applies the fortune; pets must not");
    }

    @Test
    void heldFlagMatchesOnlyTheHeldItem() {
        assertEquals(1, PetService.heldFlag("GREEN_BANDANA", "held:GREEN_BANDANA"));
        assertEquals(1, PetService.heldFlag("GREEN_BANDANA", "held:green_bandana"));
        assertEquals(0, PetService.heldFlag("BROWN_BANDANA", "held:GREEN_BANDANA"));
        assertEquals(0, PetService.heldFlag(null, "held:GREEN_BANDANA"));
        assertEquals(0, PetService.heldFlag("GREEN_BANDANA", "GREEN_BANDANA"), "needs the held: prefix");
        assertEquals(0, PetService.heldFlag("GREEN_BANDANA", "held:"));
    }
}
