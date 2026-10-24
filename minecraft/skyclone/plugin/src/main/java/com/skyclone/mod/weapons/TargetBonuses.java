package com.skyclone.mod.weapons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.mob.MobDef;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bonuses that depend on WHAT is being hit or killed and on the player's Bestiary (wave 5):
 * <ul>
 *   <li>per-target Magic Find through core's {@code LootService.addMagicFindModifier} hook: the held weapon's
 *       {@code mfVs} (Sword of Revelations: +5 on mythic foes) and the Labyrinth Blade's +0.2 per
 *       Mythological Bestiary tier;</li>
 *   <li>the Labyrinth Blade's +1% damage per Mythological Bestiary tier (read by {@link Passives}).</li>
 * </ul>
 * Bestiary tiers: the kill counts are the progression module's ({@code Features.Bestiary.kills}); the family list,
 * kill brackets and caps are read (read-only) from its {@code data/progression/bestiary.json}, category
 * {@code mythological_creatures}. Without that file or module, tiers are 0 and the bonus is 0.
 */
final class TargetBonuses {

    static final String MYTH_CATEGORY = "mythological_creatures";

    /** One Bestiary family: id, kill thresholds of its bracket, kill cap, max tier. */
    record Family(String id, int[] thresholds, int cap, int maxTier) {}

    private final Traits traits;
    private List<Family> myth = List.of();
    private final Map<UUID, long[]> tierCache = new HashMap<>();   // {tiers, computedAt}

    TargetBonuses(Traits traits) { this.traits = traits; }

    void register() {
        myth = loadFamilies(MYTH_CATEGORY);
        Sky.loot().addMagicFindModifier("weapons", this::magicFind);
    }

    List<Family> mythFamilies() { return myth; }

    // ---- Magic Find on a kill -----------------------------------------------------------------------

    /** Core calls this once per kill with the killer and the mob's def. Never throws. */
    double magicFind(Player p, MobDef def) {
        if (p == null || def == null) return 0;
        try {
            ItemStack held = p.getInventory().getItemInMainHand();
            String id = Traits.idOf(held);
            if (id == null || !traits.owns(id)) return 0;
            Trait t = traits.of(id);
            if (t.mfVs == null && t.mythBestiary == null) return 0;
            if (!Sky.items().meetsRequirements(p, held, false)) return 0;
            double mf = 0;
            if (t.mfVs != null) for (Map.Entry<String, Double> e : t.mfVs.entrySet()) if (MobTypes.isDef(e.getKey(), def)) mf += e.getValue();
            if (t.mythBestiary != null && MobTypes.isDef("mythological", def)) mf += WeaponMath.perTier(mythTiers(p), t.mythBestiary.mf);
            return mf;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** What {@link #magicFind} would add for a mob id (admin / menus / self-tests). */
    double magicFindVs(Player p, String mobId) {
        MobDef d = mobId == null ? null : Sky.mobs().def(mobId);
        return d == null ? 0 : magicFind(p, d);
    }

    // ---- Mythological Bestiary tiers ------------------------------------------------------------------

    /** Sum of the player's tiers across every mythological family (cached 5 s per player). */
    int mythTiers(Player p) {
        if (p == null || myth.isEmpty() || !Sky.has(Features.Bestiary.class)) return 0;
        long now = System.currentTimeMillis();
        long[] c = tierCache.get(p.getUniqueId());
        if (c != null && now - c[1] < 5000) return (int) c[0];
        int sum = 0;
        Features.Bestiary b = Sky.service(Features.Bestiary.class);
        for (Family f : myth) {
            int kills;
            try { kills = b.kills(p, f.id()); } catch (RuntimeException e) { kills = 0; }
            sum += WeaponMath.bestiaryTier(kills, f.thresholds(), f.cap(), f.maxTier());
        }
        tierCache.put(p.getUniqueId(), new long[]{sum, now});
        return sum;
    }

    void forget(UUID id) { tierCache.remove(id); }

    static List<Family> loadFamilies(String categoryId) {
        List<Family> out = new ArrayList<>();
        try (InputStream in = Sky.plugin().getResource("data/progression/bestiary.json")) {
            if (in == null) return out;
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                JsonObject brackets = root.getAsJsonObject("brackets");
                for (JsonElement ce : root.getAsJsonArray("categories")) {
                    JsonObject cat = ce.getAsJsonObject();
                    if (!categoryId.equals(cat.get("id").getAsString())) continue;
                    for (JsonElement fe : cat.getAsJsonArray("families")) {
                        JsonObject f = fe.getAsJsonObject();
                        String bracket = f.get("bracket").getAsString();
                        JsonArray th = brackets.getAsJsonArray(bracket);
                        if (th == null) continue;
                        int[] thr = new int[th.size()];
                        for (int i = 0; i < thr.length; i++) thr[i] = th.get(i).getAsInt();
                        out.add(new Family(f.get("id").getAsString(), thr,
                                f.has("cap") ? f.get("cap").getAsInt() : 0, f.has("maxTier") ? f.get("maxTier").getAsInt() : 0));
                    }
                }
            }
        } catch (Exception e) {
            Sky.log().warning("[weapons] could not read the mythological bestiary families: " + e.getMessage());
        }
        return out;
    }
}
