package com.skyclone.mod.armor;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.stat.Stat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What one player is wearing, resolved once per tick: piece ids, defs, whether each piece's
 * requirements are met (pieces you cannot use grant no bonus, the same rule core applies to their
 * stats), and per-set piece counts. Cached per (player, server tick) because the stat provider,
 * every damage modifier and every listener ask the same question.
 */
final class Worn {

    /** Helmet, chestplate, leggings, boots. */
    static final int HEAD = 0, CHEST = 1, LEGS = 2, FEET = 3;

    private static final Map<UUID, Worn> CACHE = new HashMap<>();

    final Player player;
    final ItemStack[] stacks = new ItemStack[4];
    final String[] ids = new String[4];
    final ItemDef[] defs = new ItemDef[4];
    final boolean[] usable = new boolean[4];
    private final Map<String, Integer> counts = new HashMap<>();
    private final int tick;

    private Worn(Player p, int tick) {
        this.player = p;
        this.tick = tick;
        PlayerInventory inv = p.getInventory();
        stacks[HEAD] = inv.getHelmet();
        stacks[CHEST] = inv.getChestplate();
        stacks[LEGS] = inv.getLeggings();
        stacks[FEET] = inv.getBoots();
        for (int i = 0; i < 4; i++) {
            ItemStack s = stacks[i];
            if (s == null || s.getType().isAir()) continue;
            String id = Sky.items().idOf(s);
            if (id == null) continue;
            ItemDef d = Sky.items().def(id);
            ids[i] = id;
            defs[i] = d;
            boolean ok;
            try { ok = Sky.items().meetsRequirements(p, s, false); } catch (RuntimeException e) { ok = false; }
            usable[i] = ok;
            if (ok && d != null && d.setId != null && !d.setId.isEmpty()) counts.merge(d.setId, 1, Integer::sum);
        }
    }

    static Worn of(Player p) {
        int now = Bukkit.getCurrentTick();
        Worn w = CACHE.get(p.getUniqueId());
        if (w != null && w.tick == now && w.player == p) return w;
        w = new Worn(p, now);
        CACHE.put(p.getUniqueId(), w);
        return w;
    }

    static void forget(UUID id) { CACHE.remove(id); }

    static void clear() { CACHE.clear(); }

    /** Usable worn pieces of this set. */
    int pieces(String setId) { return counts.getOrDefault(setId, 0); }

    /** Full set: every piece the set has (3 for tuxedos/3-piece sets, 4 otherwise). */
    boolean full(String setId) {
        ArmorSets.SetInfo s = ArmorModule.sets().get(setId);
        int need = s == null ? 4 : Math.max(1, s.size());
        return pieces(setId) >= need;
    }

    /** Wearing this exact item (requirements met). */
    boolean wears(String itemId) {
        for (int i = 0; i < 4; i++) if (usable[i] && itemId.equals(ids[i])) return true;
        return false;
    }

    int slotOf(String itemId) {
        for (int i = 0; i < 4; i++) if (usable[i] && itemId.equals(ids[i])) return i;
        return -1;
    }

    /** Sum of one stat over this set's usable worn pieces, from the full item sheet (reforge, enchants, gems...). */
    double sheetStat(String setId, Stat stat) {
        double t = 0;
        for (int i = 0; i < 4; i++) {
            if (!usable[i] || defs[i] == null || !setId.equals(defs[i].setId)) continue;
            t += sheet(i).total(stat);
        }
        return t;
    }

    ItemStatSheet sheet(int slot) {
        if (sheets[slot] == null) sheets[slot] = Sky.items().sheet(stacks[slot]);
        return sheets[slot];
    }

    private final ItemStatSheet[] sheets = new ItemStatSheet[4];

    boolean any() {
        for (String id : ids) if (id != null) return true;
        return false;
    }

    /** Magma Lord tier count: armour pieces + the Magma Lord Necklace (wave 5). Equipment is only looked up when armour is worn. */
    int magmaLord() {
        int n = pieces(SetIds.MAGMA_LORD);
        return n <= 0 ? 0 : SetMath.magmaLordPieces(n, wearsEquipment(SetIds.MAGMA_LORD_NECKLACE));
    }

    /** Fireproof: 2+ Magma Lord pieces (necklace counted). */
    boolean fireproof() { return magmaLord() >= 2; }

    /** Lord's Blessing: all 5 pieces (dial), or the 4 armour pieces when no necklace item is registered. */
    boolean lordsBlessing() {
        int n = pieces(SetIds.MAGMA_LORD);
        if (n <= 0) return false;
        return magmaLord() >= SetMath.magmaLordNeed(ArmorDials.MAGMA_LORD_PIECES.get(), Sky.items().exists(SetIds.MAGMA_LORD_NECKLACE));
    }

    /** Peace Treaty: 2+ pieces of one Hunter set (wave 5). */
    boolean peaceTreaty() {
        return ArmorDials.PEACE_TREATY.get() && SetMath.peaceTreaty(ArmorDials.PEACE_TREATY_PIECES.get(),
                pieces(SetIds.BRONZE), pieces(SetIds.SILVER), pieces(SetIds.GOLD_HUNTER), pieces(SetIds.DIAMOND_HUNTER));
    }

    private java.util.Set<String> equipment;

    /**
     * Wave 5: the ids of the usable EQUIPMENT pieces (necklace/cloak/belt/gloves, from {@code Features.Equipment}),
     * resolved once per tick like the rest. Used where an equipment piece counts toward an armour tier (the
     * Magma Lord Necklace is the fifth piece of Lord's Blessing). Empty when nothing provides equipment.
     */
    boolean wearsEquipment(String itemId) {
        if (equipment == null) {
            equipment = new java.util.HashSet<>();
            try {
                java.util.List<ItemStack> l = Sky.service(com.skyclone.api.feature.Features.Equipment.class).worn(player);
                if (l != null) for (ItemStack s : l) {
                    String id = s == null || s.getType().isAir() ? null : Sky.items().idOf(s);
                    if (id != null && Sky.items().meetsRequirements(player, s, false)) equipment.add(id);
                }
            } catch (RuntimeException ignored) { }
        }
        return equipment.contains(itemId);
    }
}
