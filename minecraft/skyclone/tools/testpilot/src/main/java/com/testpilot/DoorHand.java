package com.testpilot;

import java.util.Locale;

/**
 * Which hand {@code /walkto} opens a door with. Pure (no Minecraft types), so it is unit-tested.
 *
 * <p>Why: the walker used to right-click doors with whatever was in the main hand. The door
 * opened, but the SERVER also saw a right click with that item, and a SkyBlock item's ability
 * fires on any right click (core's AbilityListener takes RIGHT_CLICK_BLOCK as well as AIR).
 * Wave-4 endgame: with a Highlord in hand, a door on the path fired Wither Impact mid-walk - a
 * 10-block teleport, 300 mana and a shield. A person opens a door with whatever they hold too,
 * but a person knows what their sword does; the walker does not, so it opens doors bare-handed.
 *
 * <p>Order of preference: the hand is already empty; an empty hotbar slot (1-8, never the
 * SkyBlock Menu in slot 9); park the held item in an empty inventory slot for the click and put
 * it back afterwards; a hotbar item with no ability text in its lore; otherwise refuse - the
 * door is routed round like a locked one rather than clicked with an ability item.
 */
final class DoorHand {

    /** What a slot holds, as far as a right click is concerned. */
    enum Kind { EMPTY, SAFE, RISKY }

    /** Hotbar slots the walker may select: 0-7. Slot 8 (the key labelled 9) is the SkyBlock Menu. */
    static final int LAST_HOTBAR = 7;

    /**
     * {@code select}: the hotbar slot to hold for the click (-1 when refused).
     * {@code parkIn}: an inventory index 9-35 to swap the held item into first (-1 = no swap).
     */
    record Choice(int select, int parkIn) {
        boolean refused() {
            return select < 0;
        }
    }

    private DoorHand() {
    }

    /** @param inv kinds of inventory indices 0-35 (0-8 hotbar, 9-35 main), @param selected 0-8. */
    static Choice choose(Kind[] inv, int selected) {
        if (selected >= 0 && selected <= LAST_HOTBAR && inv[selected] == Kind.EMPTY) {
            return new Choice(selected, -1);
        }
        for (int i = 0; i <= LAST_HOTBAR; i++) {
            if (inv[i] == Kind.EMPTY) {
                return new Choice(i, -1);
            }
        }
        int hand = selected >= 0 && selected <= LAST_HOTBAR ? selected : 0;
        for (int i = 9; i < Math.min(36, inv.length); i++) {
            if (inv[i] == Kind.EMPTY) {
                return new Choice(hand, i);
            }
        }
        if (selected >= 0 && selected <= LAST_HOTBAR && inv[selected] == Kind.SAFE) {
            return new Choice(selected, -1);
        }
        for (int i = 0; i <= LAST_HOTBAR; i++) {
            if (inv[i] == Kind.SAFE) {
                return new Choice(i, -1);
            }
        }
        return new Choice(-1, -1);
    }

    /**
     * Lore that says a right click does something: SkyClone renders every ability as
     * "Ability: Name RIGHT CLICK" (LoreRenderer), and other right-click items say "click to ...".
     */
    static boolean risky(Iterable<String> loreLines) {
        for (String l : loreLines) {
            String s = l.toLowerCase(Locale.ROOT);
            if (s.contains("ability") || s.contains("right click") || s.contains("right-click")
                    || s.contains("click to")) {
                return true;
            }
        }
        return false;
    }
}
