package com.skyclone.mod.collections;

/** Pure slot arithmetic for the collection menus (unit-tested without a server). */
final class CollectionsMenusLayout {

    private CollectionsMenusLayout() {}

    /** Tier road: row 2 (slots 18-26), then row 3 (27-35), each row centred. Up to 18 tiers. */
    static int[] road(int n) {
        n = Math.max(0, Math.min(18, n));
        int[] out = new int[n];
        int first = Math.min(9, n);
        int second = n - first;
        int o1 = (9 - first) / 2;
        for (int i = 0; i < first; i++) out[i] = 18 + o1 + i;
        int o2 = (9 - second) / 2;
        for (int i = 0; i < second; i++) out[first + i] = 27 + o2 + i;
        return out;
    }
}
