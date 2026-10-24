package com.testpilot;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave-4 endgame: {@code /walkto} opened a door with a Highlord in hand and Wither Impact fired
 * (a teleport, 300 mana, a shield). Doors are opened bare-handed now; this is the choice of hand.
 */
class DoorHandTest {

    static DoorHand.Kind[] inv(DoorHand.Kind fill) {
        DoorHand.Kind[] k = new DoorHand.Kind[36];
        Arrays.fill(k, fill);
        k[8] = DoorHand.Kind.RISKY;     // the SkyBlock Menu: "Click to open"
        return k;
    }

    @Test
    void emptyHandIsUsedAsIs() {
        DoorHand.Kind[] k = inv(DoorHand.Kind.RISKY);
        k[3] = DoorHand.Kind.EMPTY;
        assertEquals(new DoorHand.Choice(3, -1), DoorHand.choose(k, 3));
    }

    @Test
    void anEmptyHotbarSlotBeatsTheHeldWeapon() {
        DoorHand.Kind[] k = inv(DoorHand.Kind.RISKY);
        k[6] = DoorHand.Kind.EMPTY;
        k[20] = DoorHand.Kind.EMPTY;
        assertEquals(new DoorHand.Choice(6, -1), DoorHand.choose(k, 0));
    }

    @Test
    void fullHotbarParksTheHeldItemInAnEmptyInventorySlot() {
        DoorHand.Kind[] k = inv(DoorHand.Kind.RISKY);
        k[20] = DoorHand.Kind.EMPTY;
        DoorHand.Choice c = DoorHand.choose(k, 2);
        assertEquals(2, c.select(), "keeps the slot it holds, emptied for the click");
        assertEquals(20, c.parkIn());
    }

    @Test
    void neverSelectsOrParksTheSkyBlockMenuSlot() {
        DoorHand.Kind[] k = inv(DoorHand.Kind.RISKY);
        k[20] = DoorHand.Kind.EMPTY;
        DoorHand.Choice c = DoorHand.choose(k, 8);
        assertTrue(c.select() >= 0 && c.select() <= DoorHand.LAST_HOTBAR);
        k[8] = DoorHand.Kind.EMPTY;     // even if slot 9 were empty
        assertTrue(DoorHand.choose(k, 8).select() <= DoorHand.LAST_HOTBAR);
    }

    @Test
    void fullInventoryFallsBackToAnItemWithNoAbilityOrRefuses() {
        DoorHand.Kind[] k = inv(DoorHand.Kind.RISKY);
        k[5] = DoorHand.Kind.SAFE;
        assertEquals(new DoorHand.Choice(5, -1), DoorHand.choose(k, 0));
        assertTrue(DoorHand.choose(inv(DoorHand.Kind.RISKY), 0).refused(), "every slot an ability item: route round");
    }

    @Test
    void abilityLoreIsRecognised() {
        assertTrue(DoorHand.risky(List.of("", "Ability: Wither Impact RIGHT CLICK", "Mana Cost: 300")));
        assertTrue(DoorHand.risky(List.of("Click to open!")));
        assertFalse(DoorHand.risky(List.of("Damage: +120", "Strength: +50", "", "LEGENDARY SWORD")));
        assertFalse(DoorHand.risky(List.of()));
    }
}
