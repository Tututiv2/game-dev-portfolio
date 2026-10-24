package com.skyclone.mod.alchemy;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Potion Bag (research 08 section 2.5): potions only; unlocked at Nether Wart collection II with
 * 9 slots, +9 at V, VIII, X and XII (collections hand-off), so 45 at most.
 */
final class PotionBag {

    private PotionBag() {}

    static final int MAX = 45;
    /** Nether Wart collection tier -> slots at that tier. */
    static final int[][] UNLOCKS = {{2, 9}, {5, 18}, {8, 27}, {10, 36}, {12, 45}};

    static int slots(Player p) {
        if (!Sky.has(Features.Collections.class)) return Math.max(0, Math.min(MAX, AlchemyDials.BAG_FALLBACK_SLOTS.get()));
        int tier;
        try { tier = Sky.service(Features.Collections.class).tier(p, "NETHER_STALK"); } catch (RuntimeException e) { tier = 0; }
        return slotsForTier(tier);
    }

    static int slotsForTier(int tier) {
        int s = 0;
        for (int[] u : UNLOCKS) if (tier >= u[0]) s = u[1];
        return s;
    }

    static int nextTier(int slots) {
        for (int[] u : UNLOCKS) if (u[1] > slots) return u[0];
        return -1;
    }

    static void open(Player p) {
        if (!Sky.profiles().isReady(p)) { Msg.error(p, "Your profile is still loading."); return; }
        int n = slots(p);
        if (n <= 0) {
            Msg.error(p, "The Potion Bag unlocks at Nether Wart Collection II!");
            return;
        }
        AlchemyData d = EffectEngine.data(p);
        Menu m = new Menu("Potion Bag", 6);
        m.chrome();
        Inventory inv = m.getInventory();
        boolean[] unreadable = new boolean[MAX];
        for (int i = 0; i < MAX; i++) {
            if (i < n) {
                m.free(i);
                String raw = i < d.bag.size() ? d.bag.get(i) : "";
                ItemStack st = PotionItems.decode(raw);
                unreadable[i] = st == null && raw != null && !raw.isEmpty();
                inv.setItem(i, st);
            } else {
                int t = nextTier(i);
                m.set(i, Icons.of(Material.RED_STAINED_GLASS_PANE, "&cLocked Slot",
                        t > 0 ? "&7Unlocks at &aNether Wart Collection " + com.skyclone.api.ui.Text.roman(t) + "&7." : "&7Locked."));
            }
        }
        // anything stored beyond the current size (a dial was lowered) stays in data, untouched
        m.keepFreeSlotItems();
        m.set(48, Icons.back("Alchemy"), c -> AlchemyMenus.hub(c.player()));
        m.set(50, Icons.of(Material.BOOK, "&aPotion Bag", "&7Holds potions only. They stay safe",
                "&7here and do not clutter your inventory.", "", "&7Slots: &a" + n + "&7/&a" + MAX));
        m.onClose(pl -> save(pl, m, n, unreadable));
        m.open(p);
    }

    private static void save(Player p, Menu m, int n, boolean[] unreadable) {
        if (!Sky.profiles().isReady(p)) return;
        AlchemyData d = EffectEngine.data(p);
        Inventory inv = m.getInventory();
        List<String> keep = new ArrayList<>(d.bag);
        while (keep.size() < n) keep.add("");
        List<ItemStack> refused = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ItemStack st = inv.getItem(i);
            inv.setItem(i, null);
            if (st != null && !st.getType().isAir() && !PotionItems.bagAccepts(st)) {
                refused.add(st);
                st = null;
            }
            boolean empty = st == null || st.getType().isAir();
            if (empty && unreadable[i]) continue;          // never delete a stored stack we merely failed to read
            keep.set(i, PotionItems.encode(st));
        }
        d.bag = keep;
        Sky.profiles().saveNow(p);
        if (!refused.isEmpty()) {
            for (ItemStack r : refused) Sky.loot().give(p, r, LootSource.STORAGE);
            Msg.error(p, "The Potion Bag only holds potions; the rest went back to your inventory.");
        }
    }

    /** Number of potions stored (admin/self-test). */
    static int count(AlchemyData d) {
        int c = 0;
        for (String s : d.bag) if (s != null && !s.isEmpty()) c++;
        return c;
    }
}
