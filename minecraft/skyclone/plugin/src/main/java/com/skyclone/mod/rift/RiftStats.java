package com.skyclone.mod.rift;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Rift's own stat sheet (research 01 sec 2.3, research 08 sec 5.2): Rift Time, Rift Damage,
 * Hearts, Rift Intelligence, Rift Speed, Mana Regen. Sources: base dials, worn armour + held weapon +
 * equipment (their {@code extra.riftStats}), Rift accessories in the bag (accessories module data,
 * {@code extra.riftStats}), Timecharms, soul milestones, elixirs, and the core RIFT_TIME stat (slayer's
 * Vampire level rewards). Cached for one second.
 */
final class RiftStats {

    record Sheet(double time, double damage, double hearts, double intelligence, double speed, double manaRegen,
                 Map<String, Double> timeSources, int cruxTier, double cruxFortune) {}

    private final RiftModule m;
    private final Map<UUID, Sheet> cache = new HashMap<>();
    private final Map<UUID, Long> cachedAt = new HashMap<>();
    /** Rift accessories: every registered ACCESSORY def that carries riftStats (built lazily). */
    private List<ItemDef> riftAccessories;

    RiftStats(RiftModule m) { this.m = m; }

    void invalidate(Player p) { cachedAt.remove(p.getUniqueId()); }

    void forget(UUID id) { cache.remove(id); cachedAt.remove(id); }

    Sheet sheet(Player p) {
        long now = System.currentTimeMillis();
        Long at = cachedAt.get(p.getUniqueId());
        Sheet s = cache.get(p.getUniqueId());
        if (s != null && at != null && now - at < 1000) return s;
        s = compute(p);
        cache.put(p.getUniqueId(), s);
        cachedAt.put(p.getUniqueId(), now);
        return s;
    }

    private Sheet compute(Player p) {
        RiftDials d = m.dials;
        double[] sum = new double[6]; // time, damage, hearts, int, speed, regen
        Map<String, Double> timeSrc = new LinkedHashMap<>();
        timeSrc.put("Base", d.baseTime.get());
        // gear: armour, held weapon, equipment
        double gearTime = 0;
        for (ItemStack it : p.getInventory().getArmorContents()) gearTime += add(p, sum, it);
        ItemStack held = p.getInventory().getItemInMainHand();
        ItemDef hd = defOf(held);
        if (hd != null && hd.category != null && hd.category.isWeapon()) gearTime += add(p, sum, held);
        try {
            for (ItemStack it : Sky.service(Features.Equipment.class).worn(p)) gearTime += add(p, sum, it);
        } catch (RuntimeException ignored) { }
        if (gearTime != 0) timeSrc.put("Gear", gearTime);
        // accessories (the crux line is one family: only its highest tier counts, wave 3)
        double accTime = 0;
        Features.Accessories acc = Sky.service(Features.Accessories.class);
        int cruxTier = 0;
        java.util.List<ItemDef> owned = new ArrayList<>();
        for (ItemDef def : riftAccessories()) {
            boolean has;
            try { has = acc.has(p, def.id); } catch (RuntimeException e) { has = false; }
            if (!has) continue;
            owned.add(def);
            cruxTier = Math.max(cruxTier, cruxTier(def.id));
        }
        for (ItemDef def : owned) {
            int t = cruxTier(def.id);
            if (t > 0 && t != cruxTier) continue;
            accTime += addStats(sum, def.extra.getAsJsonObject("riftStats"));
        }
        if (accTime != 0) timeSrc.put("Accessories", accTime);
        // progress
        RiftData data = m.data(p);
        // crux talismans: +5 Rift Time and +5 Crux Fortune per kill milestone of the monsters the tier tracks (research 06)
        int cruxSteps = data == null ? 0 : RiftMath.cruxMilestones(data.kills, cruxTier);
        if (cruxSteps > 0) { sum[0] += cruxSteps * 5.0; timeSrc.put("Crux milestones", cruxSteps * 5.0); }
        int charms = data == null ? 0 : data.charms.size();
        int souls = data == null ? 0 : data.souls.size();
        double charmTime = charms * d.perCharm.get();
        double soulTime = d.soulStep.get() <= 0 ? 0 : Math.floor((double) souls / d.soulStep.get()) * d.soulBonus.get();
        if (charmTime > 0) timeSrc.put("Timecharms", charmTime);
        if (soulTime > 0) timeSrc.put("Riddle Souls", soulTime);
        double statTime = 0;
        inStatCall = true;
        try { statTime = Sky.stats().get(p, Stat.RIFT_TIME); } catch (RuntimeException ignored) { } finally { inStatCall = false; }
        if (statTime != 0) timeSrc.put("Slayer & other", statTime);
        // wave 5: the Enigma Cloak (+20 per soul it holds), Pharaoh (+10 +15 per piece; +2 Mana Regen per piece from EPIC)
        if (data != null && data.cloakSouls > 0 && RiftCat.wears(p, RiftCat.CLOAK)) {
            double c = data.cloakSouls * RiftEnigma.CLOAK_SECONDS;
            sum[0] += c;
            timeSrc.put("Enigma Cloak", c);
        }
        if (data != null && m.cat != null && m.cat.catActive(p, data)) {
            double c = RiftEnigma.catRiftTime(data.catPieces.size());
            sum[0] += c;
            sum[5] += RiftEnigma.catManaRegen(data.catPieces.size());
            timeSrc.put("Pharaoh", c);
        }
        double max = RiftMath.maxTime(d.baseTime.get(), charms, d.perCharm.get(), souls, d.soulStep.get(), d.soulBonus.get(),
                sum[0] + statTime);
        // Golden Belt: "Increases your total Rift Time by 5%" (it drains 3% faster too: RiftTravel.drainHere)
        if (RiftEffects.has(p, "GOLDEN_BELT")) { timeSrc.put("Golden Belt (+5%)", max * 0.05); max *= 1.05; }
        // elixirs
        RiftSession s = m.sessions.get(p.getUniqueId());
        double elixirDamage = 0, elixirSpeed = 0;
        if (s != null) {
            long now = System.currentTimeMillis();
            if (s.elixir("damage", now)) elixirDamage = 5;
            if (s.elixir("speed", now)) elixirSpeed = 30;
        }
        return new Sheet(max, d.baseDamage.get() + sum[1] + elixirDamage, Math.max(1, d.baseHearts.get() + sum[2]),
                sum[3], sum[4] + elixirSpeed, sum[5], timeSrc, cruxTier, cruxSteps * 5.0);
    }

    /** 1..7 for CRUX_TALISMAN_1..7, else 0. */
    static int cruxTier(String id) {
        if (id == null || !id.startsWith("CRUX_TALISMAN_")) return 0;
        try { return Integer.parseInt(id.substring("CRUX_TALISMAN_".length())); } catch (NumberFormatException e) { return 0; }
    }

    private double add(Player p, double[] sum, ItemStack it) {
        ItemDef def = defOf(it);
        if (def == null || def.extra == null || !def.extra.has("riftStats") || !def.extra.get("riftStats").isJsonObject()) return 0;
        if (!def.requirements.isEmpty() && !Sky.items().meetsRequirements(p, it, false)) return 0;
        // wave 3: the necklaces "become more powerful as you fill the Rift Gallery" (research 06)
        double k = 1;
        if (RiftGallery.SCALED.contains(def.id)) {
            RiftData d = m.data(p);
            k = RiftMath.galleryScale(d == null ? 0 : d.gallery.size(), RiftCatalog.CHARMS.size());
        }
        double t = addStats(sum, def.extra.getAsJsonObject("riftStats"), k);
        // accessories data files a few Rift stats under otherStats (Inner Dimensional Necklace: HEARTS, RIFT_DAMAGE)
        if (def.extra.has("otherStats") && def.extra.get("otherStats").isJsonObject()) {
            JsonObject os = def.extra.getAsJsonObject("otherStats");
            sum[1] += num(os, "RIFT_DAMAGE") * k;
            sum[2] += num(os, "HEARTS") * k;
        }
        return t;
    }

    /** Adds one riftStats object; returns the Rift Time part. */
    static double addStats(double[] sum, JsonObject rs) { return addStats(sum, rs, 1); }

    /** Adds one riftStats object scaled by {@code k}; returns the Rift Time part. */
    static double addStats(double[] sum, JsonObject rs, double k) {
        if (rs == null) return 0;
        double t = num(rs, "RIFT_TIME") * k;
        sum[0] += t;
        sum[1] += num(rs, "RIFT_DAMAGE") * k;
        sum[2] += num(rs, "RIFT_HEALTH") * k;
        sum[3] += num(rs, "RIFT_INTELLIGENCE") * k;
        sum[4] += (rs.has("RIFT_SPEED") ? num(rs, "RIFT_SPEED") : num(rs, "RIFT_WALK_SPEED")) * k;
        sum[5] += num(rs, "RIFT_MANA_REGEN") * k;
        return t;
    }

    private static double num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try { return e == null || e.isJsonNull() ? 0 : e.getAsDouble(); } catch (RuntimeException ex) { return 0; }
    }

    private static ItemDef defOf(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        String id = Sky.items().idOf(it);
        return id == null ? null : Sky.items().def(id);
    }

    List<ItemDef> riftAccessories() {
        if (riftAccessories == null) {
            List<ItemDef> l = new ArrayList<>();
            for (ItemDef def : Sky.items().all())
                if (def.category == ItemCategory.ACCESSORY && def.extra != null && def.extra.has("riftStats") && def.extra.get("riftStats").isJsonObject()) l.add(def);
            riftAccessories = l;
        }
        return riftAccessories;
    }

    /**
     * Core stat provider: inside a Rift session the Rift's own Intelligence / Speed / Mana Regen REPLACE the
     * main ones (research 08 sec 5.2: "the only stats that apply"), and Magic Find is the Rift's (0: no Rift
     * piece gives any). Uses core's per-player {@code StatMap.set} override (core-items, rift Request 5), so
     * main skills, pets and accessories stop leaking in. Dial {@code rift.replace-main-stats} false = the
     * old behaviour (Rift bonuses added on top of the main stats).
     */
    private boolean computing, inStatCall;

    void contribute(Player p, StatMap into) {
        if (computing || inStatCall || !m.sessions.containsKey(p.getUniqueId())) return;
        Sheet s;
        computing = true;
        try { s = sheet(p); } finally { computing = false; }
        if (m.dials.replaceMainStats.get()) {
            into.set(Stat.INTELLIGENCE, s.intelligence());
            into.set(Stat.SPEED, Stat.SPEED.base + s.speed());
            into.set(Stat.MANA_REGEN, s.manaRegen());
            into.set(Stat.MAGIC_FIND, 0);
            if (m.dials.zeroMainCombat.get()) {        // wave 5: Rift Damage replaces the main combat sheet
                into.set(Stat.STRENGTH, 0);
                into.set(Stat.CRIT_CHANCE, 0);
                into.set(Stat.CRIT_DAMAGE, 0);
                into.set(Stat.FEROCITY, 0);
            }
            return;
        }
        into.add(Stat.INTELLIGENCE, s.intelligence());
        into.add(Stat.SPEED, s.speed());
        into.add(Stat.MANA_REGEN, s.manaRegen());
    }
}
