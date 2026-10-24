package com.skyclone.mod.crafting;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Wave 5: the raw-material tree follows the cheapest packing recipe, never an unpacking or a vanilla recipe. */
class TreePickTest {

    private static Pattern pat(String rid, String result, int amount, boolean vanilla, Object... idAmt) {
        Pattern.Ing[] g = new Pattern.Ing[9];
        for (int i = 0; i < idAmt.length; i += 2) g[i / 2] = Pattern.Ing.of((String) idAmt[i], (Integer) idAmt[i + 1]);
        return Pattern.fromGrid(rid, result, amount, true, g, 0, null, vanilla);
    }

    private static final Map<String, Double> PRICE = Map.of("DIAMOND", 8.0, "DIAMOND_BLOCK", 72.0, "COAL", 2.0, "GOLD", 4.0);

    private static double cost(Pattern p) {
        double v = 0;
        for (Map.Entry<Pattern.Ing, Integer> e : p.totals().entrySet()) {
            Double c = PRICE.get(e.getKey().display());
            if (c == null) return -1;
            v += c * e.getValue();
        }
        return v / Math.max(1, p.amount);
    }

    @Test
    void unpackingOnACycleIsRawButPackingIsFollowed() {
        Pattern unpack = pat("m:diamond", "DIAMOND", 9, false, "DIAMOND_BLOCK", 1);
        Pattern pack = pat("m:diamond_block", "DIAMOND_BLOCK", 1, false, "DIAMOND", 9);
        Set<String> cycle = Set.of("m:diamond", "m:diamond_block");
        assertNull(CraftMath.treePick(List.of(unpack), cycle::contains, TreePickTest::cost), "a Diamond is not made of a block");
        assertEquals(pack, CraftMath.treePick(List.of(pack), cycle::contains, TreePickTest::cost));
        Map<String, Long> raw = CraftMath.raw("DIAMOND_BLOCK", 2, id -> CraftMath.treePick(
                id.equals("DIAMOND") ? List.of(unpack) : id.equals("DIAMOND_BLOCK") ? List.of(pack) : List.of(),
                cycle::contains, TreePickTest::cost), 6);
        assertEquals(Map.of("DIAMOND", 18L), raw);
    }

    @Test
    void cheapestInputsPerItemWinsAndVanillaNeverDoes() {
        Pattern coal = pat("a", "TORCHLIKE", 4, false, "COAL", 4);      // 2.0 per item
        Pattern gold = pat("b", "TORCHLIKE", 4, false, "GOLD", 1);      // 1.0 per item
        Pattern van = pat("vanilla:x", "TORCHLIKE", 64, true, "COAL", 1);
        Pattern unknown = pat("c", "TORCHLIKE", 1, false, "MYSTERY", 1);
        assertEquals(gold, CraftMath.treePick(List.of(coal, gold, van), null, TreePickTest::cost));
        assertEquals(coal, CraftMath.treePick(List.of(unknown, coal), null, TreePickTest::cost), "an unpriceable recipe comes after a priced one");
        assertEquals(unknown, CraftMath.treePick(List.of(unknown), null, TreePickTest::cost), "but is still followed when it is the only one");
        assertNull(CraftMath.treePick(List.of(van), null, TreePickTest::cost));
    }
}
