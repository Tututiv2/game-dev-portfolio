package com.skyclone.mod.armor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Fear (wave 5). The reference stat has no {@code Stat} here, so it is summed from where the data puts it:
 * {@code extra.otherStats.FEAR} on armour (our generator), equipment and accessories (the accessories data
 * uses the same key), plus the Greater Spook reforge on any worn piece. Fearsome (Great Spook, full set)
 * reads the total: "monsters at or below your total Fear level will run away from you".
 */
final class Fear {

    private Fear() {}

    /** Talisman family: only the best one in the bag counts (the bag's own family rule). */
    static final List<String> SPOOK_ACCESSORIES = List.of("GREAT_SPOOK_ARTIFACT", "GREAT_SPOOK_RING", "GREAT_SPOOK_TALISMAN");
    static final String SPOOK_STAFF = "GREAT_SPOOK_STAFF";

    static double total(Player p) {
        double t = 0;
        Worn w = Worn.of(p);
        for (int i = 0; i < 4; i++) {
            if (!w.usable[i] || w.stacks[i] == null) continue;
            t += piece(w.stacks[i], w.defs[i]);
        }
        try {
            List<ItemStack> eq = Sky.service(Features.Equipment.class).worn(p);
            if (eq != null) for (ItemStack s : eq) {
                String id = s == null ? null : Sky.items().idOf(s);
                if (id != null && Sky.items().meetsRequirements(p, s, false)) t += piece(s, Sky.items().def(id));
            }
        } catch (RuntimeException ignored) { }
        try {
            Features.Accessories acc = Sky.service(Features.Accessories.class);
            for (String id : SPOOK_ACCESSORIES) {
                if (acc.has(p, id)) { t += other(Sky.items().def(id), "FEAR"); break; }
            }
        } catch (RuntimeException ignored) { }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (SPOOK_STAFF.equals(Sky.items().idOf(hand))) t += other(Sky.items().def(SPOOK_STAFF), "FEAR");
        return t;
    }

    /** One worn piece: its own Fear plus the Greater Spook reforge's. */
    static double piece(ItemStack s, ItemDef def) {
        double v = other(def, "FEAR");
        try {
            ItemData d = Sky.items().data(s);
            Rarity r = Sky.items().rarity(s);
            if (d != null) v += SetMath.reforgeFear(d.reforge, r == null ? 3 : r.ordinal());
        } catch (RuntimeException ignored) { }
        return v;
    }

    /** {@code def.extra.otherStats.<key>}, 0 when absent. */
    static double other(ItemDef def, String key) {
        if (def == null || def.extra == null) return 0;
        JsonElement os = def.extra.get("otherStats");
        if (os == null || !os.isJsonObject()) return 0;
        JsonObject o = os.getAsJsonObject();
        JsonElement v = o.get(key);
        try { return v == null ? 0 : v.getAsDouble(); } catch (RuntimeException e) { return 0; }
    }
}
