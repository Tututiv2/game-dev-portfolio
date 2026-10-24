package com.skyclone.mod.crimson;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 3: the Matriarch's daily Heavy Pearls and the Leviathan-clear requirement keys. */
class CrimsonPearlsTest {

    private static final String U = "3f1c2a9e-0000-4000-8000-000000000042";

    @Test
    void orderIsADeterministicPermutationThatChangesByDay() {
        int[] a = CrimsonRules.pearlOrder(U, "2026-10-04", 9);
        assertArrayEquals(a, CrimsonRules.pearlOrder(U, "2026-10-04", 9));
        int[] sorted = a.clone();
        Arrays.sort(sorted);
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8}, sorted);
        boolean differs = false;
        for (int d = 5; d < 12 && !differs; d++) differs = !Arrays.equals(a, CrimsonRules.pearlOrder(U, "2026-10-" + d, 9));
        assertTrue(differs, "the spots should move from day to day");
    }

    @Test
    void collectingInAnyOrderPaysExactlyTheAllowance() {
        int[] o = CrimsonRules.pearlOrder(U, "2026-10-04", 9);
        Set<Integer> taken = new HashSet<>();
        Map<Integer, Integer> show = CrimsonRules.pearlsShowing(o, 3, taken);
        assertEquals(3, show.size(), "three distinct spots on a normal day");
        // take the LAST one first
        Integer last = null;
        for (Integer j : show.values()) last = j;
        taken.add(last);
        assertEquals(2, CrimsonRules.pearlsShowing(o, 3, taken).size());
        while (!CrimsonRules.pearlsShowing(o, 3, taken).isEmpty())
            taken.add(CrimsonRules.pearlsShowing(o, 3, taken).values().iterator().next());
        assertEquals(3, taken.size());
    }

    @Test
    void perfumeBeyondTheSpotCountReusesSpots() {
        int[] o = CrimsonRules.pearlOrder(U, "2026-10-04", 9);
        int allowance = 3 + 4 * 3;                       // 4 perfumes x 3 pearls
        Set<Integer> taken = new HashSet<>();
        int got = 0;
        for (int guard = 0; guard < 100; guard++) {
            Map<Integer, Integer> show = CrimsonRules.pearlsShowing(o, allowance, taken);
            if (show.isEmpty()) break;
            assertTrue(show.size() <= 9, "never more pearls showing than spots");
            taken.add(show.values().iterator().next());
            got++;
        }
        assertEquals(allowance, got);
    }

    @Test
    void leviathanClearKeysFollowTheReference() {
        // Hypixel API: KUUDRA_COMPLETION NONE (= Basic) on Basic pieces, HOT on Hot ... INFERNAL on Infernal
        assertEquals("kuudra:basic", CrimsonRules.kuudraRequirement("CRIMSON_CHESTPLATE", true));
        assertEquals("kuudra:hot", CrimsonRules.kuudraRequirement("HOT_AURORA_HELMET", true));
        assertEquals("kuudra:burning", CrimsonRules.kuudraRequirement("BURNING_TERROR_BOOTS", true));
        assertEquals("kuudra:fiery", CrimsonRules.kuudraRequirement("FIERY_FERVOR_LEGGINGS", true));
        assertEquals("kuudra:infernal", CrimsonRules.kuudraRequirement("INFERNAL_HOLLOW_HELMET", true));
        assertEquals("kuudra:basic", CrimsonRules.kuudraRequirement("RUNIC_STAFF", false));
        assertEquals("kuudra:basic", CrimsonRules.kuudraRequirement("HOLLOW_WAND", false));
        assertEquals("kuudra:infernal", CrimsonRules.kuudraRequirement("TORMENTOR", false));
        assertNull(CrimsonRules.kuudraRequirement("ASPECT_OF_THE_END", false));
    }
}
