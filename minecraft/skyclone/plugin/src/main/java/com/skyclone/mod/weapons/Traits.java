package com.skyclone.mod.weapons;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which item defs are OURS (extra.src = "weapons") and their parsed traits. Behaviour only ever
 * applies to ids whose REGISTERED def is ours: if the slayer or dungeons module registered its own
 * version of a fallback weapon, that module owns the behaviour and we stay out of the way.
 */
final class Traits {

    private static final Gson GSON = new Gson();
    private static final Trait NONE = new Trait();

    private final Map<String, Trait> byId = new ConcurrentHashMap<>();
    private final Set<String> owned = ConcurrentHashMap.newKeySet();
    private final Map<String, String> parseErrors = new ConcurrentHashMap<>();

    void rebuild() {
        byId.clear();
        owned.clear();
        parseErrors.clear();
        for (ItemDef d : Sky.items().all()) {
            if (!isOurs(d)) continue;
            owned.add(d.id);
            JsonElement w = d.extra.get("w");
            if (w == null || !w.isJsonObject()) continue;
            try {
                Trait t = GSON.fromJson(w, Trait.class);
                if (t != null) byId.put(d.id, t);
            } catch (RuntimeException e) {
                parseErrors.put(d.id, e.getMessage());
            }
        }
    }

    static boolean isOurs(ItemDef d) {
        return d != null && d.extra != null && d.extra.has("src") && "weapons".equals(d.extra.get("src").getAsString());
    }

    boolean owns(String id) { return id != null && owned.contains(id); }

    Set<String> ownedIds() { return owned; }

    Map<String, String> parseErrors() { return parseErrors; }

    /** Trait of one of our items; NONE (all off) for anything else. Never null. */
    Trait of(String id) {
        if (id == null || !owned.contains(id)) return NONE;
        return byId.getOrDefault(id, NONE);
    }

    Trait of(ItemStack stack) { return of(idOf(stack)); }

    static String idOf(ItemStack s) {
        if (s == null || s.getType().isAir()) return null;
        try { return Sky.items().idOf(s); } catch (RuntimeException e) { return null; }
    }

    static String heldId(Player p) { return idOf(p.getInventory().getItemInMainHand()); }

    Trait held(Player p) { return of(heldId(p)); }

    static boolean none(Trait t) { return t == NONE; }
}
