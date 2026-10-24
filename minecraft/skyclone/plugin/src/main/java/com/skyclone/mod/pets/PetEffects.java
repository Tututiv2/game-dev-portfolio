package com.skyclone.mod.pets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.pets.PetData.OwnedPet;
import com.skyclone.mod.pets.PetService.View;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Makes pet abilities and held items DO what their lore says. Every effect is data
 * ({@code fx} objects generated from tools/gen/pets/abilities.py and pet_items.py); this class is the
 * interpreter. Stat effects run in the "pets" StatProvider; damage effects in a DamageModifier at
 * priority 300 (CONTRACT); the rest hang off SkyEvents and a 10-tick heartbeat.
 */
final class PetEffects implements Listener {

    /** One active effect: which pet (view), which ability (null for a held item), the effect object. */
    record Src(View v, PetDefs.Ability a, JsonObject fx, boolean item) {
        String t() { return PetService.str(fx, "t"); }
        double num(String key) { return PetEffects.num(this, fx.get(key)); }
        double num(String key, double def) { return fx.has(key) ? num(key) : def; }
    }

    static double num(Src s, JsonElement e) {
        if (e == null || e.isJsonNull()) return 0;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsDouble();
        String str = e.getAsString();
        if (str.endsWith("-1")) return num(s, new com.google.gson.JsonPrimitive(str.substring(0, str.length() - 2))) - 1;
        if (s.a() != null && s.a().tok.containsKey(str)) return s.v().num(str, s.a());
        try { return Double.parseDouble(str); } catch (NumberFormatException ex) { return 0; }
    }

    /** Per-player transient state (buffs, stacks, cooldowns). Never persisted. */
    static final class RT {
        Location lastLoc; long stillSince;
        final Deque<Long> killStacks = new ArrayDeque<>();
        final Deque<Long> bowStacks = new ArrayDeque<>();
        long castBuffUntil, jumpBuffUntil, treasureBuffUntil, reaperUntil, weaponDmgUntil;
        double weaponDmg;
        int hits, flareHits;
        long tossCd, lastStandCd, lastStandUntil, rekindleCd, immuneUntil, rekindleBuffUntil;
        long lastDamaged = System.currentTimeMillis();
        boolean overhealGiven;
        int hitsTaken; long vitalityUntil;
        long lastZap, lastSkelShot, lastSnow, lastStun;
        final Map<UUID, Long> attackedBy = new HashMap<>();
        int combo; long comboUntil;
        LivingEntity lastTarget;
        long spawnCd;
        Location poolAt; long poolUntil, nextPool, bathUntil; double bathRegen;
        boolean flightGranted, kbApplied;
        boolean hasTargetPerk;
        double lastMelee;
        long lastHit;
        long builtAt; boolean dirty = true;
        // wave 5
        long gemReadyAt, chopReadyAt; boolean chopFlagged;
        final Map<String, Long> abilityBuff = new HashMap<>();
        long attrAt; int attrMaxed;
        long blessUntil; double blessMf, blessCopy; UUID blessBy;
        List<String> digTags = List.of(); long digAt; boolean hasDigPerk;
        List<Src> sources = List.of();
        View active;
    }

    private final PetsModule mod;
    private final PetService svc;
    private final Map<UUID, RT> rts = new HashMap<>();
    private final Set<UUID> withering = new HashSet<>();
    /** Tiger "Hemorrhage": mobs whose healing is cut, with the cut, the end time and the last health seen. */
    private final Map<UUID, Hemo> bleeding = new HashMap<>();
    private final Map<String, com.skyclone.api.config.Dials.Dial<Double>> foreignDials = new HashMap<>();
    static final String PILLAR = "slayer_fire_pillar";

    static final class Hemo {
        final LivingEntity mob; double pct; long until; double lastHp;
        Hemo(LivingEntity mob) { this.mob = mob; }
    }
    private NamespacedKey kbKey;

    PetEffects(PetsModule mod, PetService svc) {
        this.mod = mod;
        this.svc = svc;
    }

    RT rt(Player p) { return rts.computeIfAbsent(p.getUniqueId(), k -> new RT()); }

    void invalidate(Player p) { rt(p).dirty = true; }

    void forget(Player p) {
        RT r = rts.remove(p.getUniqueId());
        if (r != null && r.flightGranted) disableFlight(p);
        removeKb(p);
    }

    // ---- registration ------------------------------------------------------------------------

    void register() {
        kbKey = new NamespacedKey(Sky.plugin(), "pet_knockback");
        Sky.stats().addProvider("pets", this::contribute);
        Sky.combat().addModifier("pets", 300, this::modify);
        Sky.abilities().addCostModifier("pets", this::cost);
        // wave 5: "Magic Find against X" applies to exactly that mob's drops (core's per-target hook), not to everything
        Sky.loot().addMagicFindModifier("pets", this::magicFindVs);
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::heartbeat, 40L, 10L);
    }

    // ---- sources -----------------------------------------------------------------------------

    List<Src> sources(Player p) {
        RT r = rt(p);
        long now = System.currentTimeMillis();
        if (!r.dirty && now - r.builtAt < 1000) return r.sources;
        List<Src> out = new ArrayList<>();
        PetData d = svc.data(p);
        View active = d == null ? null : svc.active(p);
        if (active != null && active.t() != null) {
            for (PetDefs.Ability a : active.t().abilities) for (JsonObject fx : a.fx) out.add(new Src(active, a, fx, false));
            if (active.held() != null) for (JsonObject fx : active.held().fx) out.add(new Src(active, null, fx, true));
        }
        if (d != null && mod.dialPassivePets()) {
            boolean bingoProfile = "bingo".equalsIgnoreCase(safeMode(p));
            Map<String, View> best = new HashMap<>();
            for (OwnedPet o : d.pets) {
                if (active != null && o.uuid.equals(active.owned().uuid)) continue;
                View v = svc.view(o);
                if (v == null || !v.def().passive || v.t() == null) continue;
                if (active != null && v.def().id.equals(active.def().id)) continue;
                if ("BINGO".equals(v.def().id) && !bingoProfile) continue;
                View cur = best.get(v.def().id);
                if (cur == null || v.level() > cur.level()) best.put(v.def().id, v);
            }
            for (View v : best.values()) for (PetDefs.Ability a : v.t().abilities) for (JsonObject fx : a.fx) out.add(new Src(v, a, fx, false));
        }
        r.sources = out;
        r.hasTargetPerk = false;
        for (Src s : out) if (s.fx().has("when") && s.fx().get("when").toString().contains("target:")) { r.hasTargetPerk = true; break; }
        r.hasDigPerk = false;
        for (Src s : out) if (s.fx().has("when") && s.fx().get("when").toString().contains("dig:")) { r.hasDigPerk = true; break; }
        r.active = active;
        r.builtAt = now;
        r.dirty = false;
        return out;
    }

    private static String safeMode(Player p) {
        try { return Sky.profiles().mode(p); } catch (RuntimeException e) { return "normal"; }
    }

    // ---- conditions ----------------------------------------------------------------------------

    boolean when(Player p, JsonObject fx) {
        JsonElement w = fx.get("when");
        if (w == null || !w.isJsonArray()) return true;
        for (JsonElement c : w.getAsJsonArray()) if (!cond(p, c.getAsString())) return false;
        return true;
    }

    private static final Set<String> MINING = Set.of("gold_mine", "deep_caverns", "dwarven_mines", "crystal_hollows", "glacite_tunnels");

    boolean cond(Player p, String c) {
        int i = c.indexOf(':');
        String k = i < 0 ? c : c.substring(0, i);
        String arg = i < 0 ? "" : c.substring(i + 1);
        String island = PetService.island(p);
        switch (k) {
            case "island": {
                if (island == null) return false;
                for (String s : arg.split("\\|")) if (s.equals(island)) return true;
                return false;
            }
            case "mining": return island != null && MINING.contains(island);
            case "public": return island != null && !"private_island".equals(island);
            case "night": try { return Sky.calendar().now().isNight(); } catch (RuntimeException e) { return false; }
            case "day": try { return !Sky.calendar().now().isNight(); } catch (RuntimeException e) { return false; }
            case "event": try { return Sky.calendar().active(arg); } catch (RuntimeException e) { return false; }
            case "hp_above": return hpPct(p) > parse(arg);
            case "hp_below": return hpPct(p) < parse(arg);
            case "water": return nearFluid(p);
            case "still": { RT r = rt(p); return r.stillSince > 0 && System.currentTimeMillis() - r.stillSince >= 1000; }
            case "sneaking": return p.isSneaking();
            case "riding": return mod.ride() != null && mod.ride().riding(p);   // sitting on / riding your pet (PetRide)
            case "rain": return p.getWorld().hasStorm();
            case "dungeon": return Sky.service(Features.Dungeons.class).inDungeon(p);
            case "target": {   // the mob you last hit (within 6s) is of this family: "MF against X" perks
                RT r = rt(p);
                LivingEntity t = r.lastTarget;
                if (t == null || !t.isValid() || System.currentTimeMillis() - r.lastHit > 6000) return false;
                return matches(arg, t, Sky.mobs().defOf(t));
            }
            case "dig": {   // the mining block you are breaking (3s): "Mining Speed when mining Mithril / Ores"
                RT r = rt(p);
                return System.currentTimeMillis() - r.digAt <= 3000 && r.digTags.contains(arg);
            }
            case "potion": {   // an active potion effect (alchemy's ids: "jump_boost", "speed"...)
                if (!Sky.has(Features.Potions.class)) return false;
                try {
                    Map<String, Integer> fx = Sky.service(Features.Potions.class).active(p);
                    return fx != null && fx.getOrDefault(arg, 0) > 0;
                } catch (RuntimeException e) { return false; }
            }
            case "holding": {
                ItemDef d = heldDef(p);
                return d != null && d.category != null && d.category.name().equals(arg);
            }
            case "zone": {
                try {
                    Layout.Zone z = Sky.worlds().zoneOf(p);
                    return z != null && (z.id().toLowerCase().contains(arg) || z.displayName().toLowerCase().contains(arg));
                } catch (RuntimeException e) { return false; }
            }
            default: return false;
        }
    }

    private static double parse(String s) { try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; } }

    /** An enchant's level summed over the player's worn armour (default id: Last Stand). */
    static int wornEnchantSum(Player p, String enchant) {
        String id = enchant == null ? "ultimate_last_stand" : enchant;
        int sum = 0;
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getArmorContents()) {
            if (it == null || it.getType().isAir()) continue;
            try {
                com.skyclone.api.item.ItemData d = Sky.items().data(it);
                if (d != null) sum += Math.max(0, d.enchant(id));
            } catch (RuntimeException ignored) { }
        }
        return sum;
    }

    static double hpPct(Player p) {
        try {
            double max = Sky.vitals().maxHealth(p);
            return max <= 0 ? 100 : Sky.vitals().health(p) / max * 100.0;
        } catch (RuntimeException e) { return 100; }
    }

    private static boolean nearFluid(Player p) {
        if (p.isInWater() || p.isInLava()) return true;
        Block b = p.getLocation().getBlock();
        for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 1; dy++) for (int dz = -2; dz <= 2; dz++) {
            Material m = b.getRelative(dx, dy, dz).getType();
            if (m == Material.WATER || m == Material.LAVA) return true;
        }
        return false;
    }

    static ItemDef heldDef(Player p) {
        String id = Sky.items().idOf(p.getInventory().getItemInMainHand());
        return id == null ? null : Sky.items().def(id);
    }

    /** {@code mf_vs}: Magic Find the summoned pet adds when the killed mob is of that family (LootService hook). */
    double magicFindVs(Player p, MobDef def) {
        if (p == null || def == null || !Sky.profiles().isReady(p)) return 0;
        double mf = 0;
        String key = defKey(def);
        for (Src s : sources(p)) {
            if (!"mf_vs".equals(s.t()) || !when(p, s.fx())) continue;
            if (matchesKey(PetService.str(s.fx(), "match"), key)) mf += s.num("v");
        }
        return mf;
    }

    /** The family key from a def alone (no entity): "id family slayer name ENTITY". */
    static String defKey(MobDef def) {
        return (def.id + " " + def.familyId() + " " + def.slayer + " " + def.name + " " + def.entity).toLowerCase();
    }

    // ---- mob matching -------------------------------------------------------------------------

    static boolean matches(String match, LivingEntity e, MobDef def) {
        if (match == null || "*".equals(match)) return true;
        String key = ((def == null ? "" : (def.id + " " + def.familyId() + " " + def.slayer + " " + def.name)) + " " + e.getType().name()).toLowerCase();
        if ("passive".equals(match)) return (def != null && def.has("passive")) || e instanceof Animals;
        return matchesKey(match, key);
    }

    /** The family test on a lower-case "id family slayer name ENTITY_TYPE" key. Pure, unit-tested. */
    static boolean matchesKey(String match, String key) {
        if (match == null || "*".equals(match)) return true;
        return switch (match) {
            case "ender" -> any(key, "ender", "zealot", "voidling", "watcher", "obsidian", "end_", "dragon");
            case "undead" -> any(key, "zombie", "skeleton", "ghoul", "wither", "revenant", "crypt", "drowned", "husk", "stray", "phantom", "undead", "lich", "zombified", "ghost");
            case "zombie" -> any(key, "zombie", "ghoul", "revenant", "drowned", "husk");
            case "skeleton" -> any(key, "skeleton", "skeletor", "stray", "bone");
            case "spider" -> any(key, "spider", "tarantula", "arachne", "broodmother", "weaver", "dasher", "splitter");
            case "wolf" -> any(key, "wolf", "hound", "sven");
            case "wither" -> any(key, "wither");
            case "blaze" -> any(key, "blaze", "ashfang", "demon");
            case "slime" -> any(key, "slime", "magma_cube", "magma cube", "magmacube");
            // festival mobs only: "witch"/"bat" also hit sea witches, cinderbats and every vanilla BAT entity (wave 3)
            case "spooky" -> any(key, "spooky", "scarecrow", "wraith", "headless", "phantom_spirit", "trick_or_treat");
            // garden pests are all garden_pest_*: "mite"/"fly" also hit endermites, deathmites and dragonflies (wave 3)
            case "pest" -> any(key, "pest");
            case "mythological" -> any(key, "myth_", "minos", "inquisitor", "gaia", "minotaur", "siamese", "lynx");
            case "winter" -> any(key, "yeti", "reindrake", "frosty", "grinch", "nutcracker", "frozen_steve", "winter");
            case "kuudra" -> any(key, "kuudra");
            case "bal" -> key.startsWith("bal ") || any(key, "_bal", "bal_", "magmaw");   // our name for Bal is Magmaw (ch_magmaw)
            default -> key.contains(match);
        };
    }

    private static boolean any(String key, String... words) {
        for (String w : words) if (key.contains(w)) return true;
        return false;
    }

    // ---- stats ---------------------------------------------------------------------------------

    private static final List<Stat> COMBAT = List.of(Stat.HEALTH, Stat.DEFENSE, Stat.TRUE_DEFENSE, Stat.STRENGTH, Stat.INTELLIGENCE,
            Stat.CRIT_CHANCE, Stat.CRIT_DAMAGE, Stat.ATTACK_SPEED, Stat.ABILITY_DAMAGE, Stat.FEROCITY, Stat.HEALTH_REGEN, Stat.VITALITY, Stat.MENDING);
    private static final List<Stat> FISHING = List.of(Stat.SEA_CREATURE_CHANCE, Stat.FISHING_SPEED, Stat.TREASURE_CHANCE,
            Stat.DOUBLE_HOOK_CHANCE, Stat.TROPHY_FISH_CHANCE);
    private static final List<Stat> MISC = List.of(Stat.SPEED, Stat.MAGIC_FIND, Stat.PET_LUCK);

    static List<Stat> group(String g) {
        return switch (g == null ? "" : g) {
            case "combat" -> COMBAT;
            case "fishing" -> FISHING;
            case "misc" -> MISC;
            default -> List.of();
        };
    }

    private static List<Stat> statList(Src s) {
        List<Stat> out = new ArrayList<>();
        if (s.fx().has("group")) out.addAll(group(PetService.str(s.fx(), "group")));
        if (s.fx().has("stats") && s.fx().get("stats").isJsonArray())
            for (JsonElement e : s.fx().getAsJsonArray("stats")) { Stat st = Stat.parse(e.getAsString()); if (st != null) out.add(st); }
        return out;
    }

    /** "stats":{"STRENGTH":"a"} -> resolved values. */
    private static Map<Stat, Double> statMap(Src s, String key) {
        Map<Stat, Double> out = new EnumMap<>(Stat.class);
        JsonElement e = s.fx().get(key);
        if (e == null || !e.isJsonObject()) return out;
        for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
            Stat st = Stat.parse(x.getKey());
            if (st != null) out.merge(st, num(s, x.getValue()), Double::sum);
        }
        return out;
    }

    /** The StatProvider "pets". */
    void contribute(Player p, StatMap into) {
        if (!Sky.profiles().isReady(p)) return;
        into.add(Stat.MAGIC_FIND, svc.scoreMagicFind(p));
        crestStats(p, into);
        List<Src> src = sources(p);
        RT r = rt(p);
        View active = r.active;
        long now = System.currentTimeMillis();
        // the pet's own base stats, with base-stat boosts
        if (active != null) {
            double extra = 0;
            double itemBoost = 0;
            for (Src s : src) {
                String t = s.t();
                if ("pet_base".equals(t) && s.v() == active && when(p, s.fx())) extra += s.num("v");
                else if ("pet_base_all".equals(t) && when(p, s.fx())) extra += s.num("v");
                else if ("pet_item_boost".equals(t) && s.v() == active) itemBoost += s.num("v");
            }
            for (Map.Entry<Stat, Double> e : svc.petStats(active, extra).entrySet()) into.add(e.getKey(), e.getValue());
            if (active.held() != null) {
                for (Src s : src) {
                    if (!s.item() || !"stat".equals(s.t()) || !when(p, s.fx())) continue;
                    for (Map.Entry<Stat, Double> e : statMap(s, "stats").entrySet()) {
                        double m = COMBAT.contains(e.getKey()) ? 1 + itemBoost / 100.0 : 1;
                        into.add(e.getKey(), e.getValue() * m);
                    }
                }
            }
        }
        for (Src s : src) {
            if (s.item() && "stat".equals(s.t())) continue;   // done above (Tyrant boost)
            if (!when(p, s.fx())) continue;
            switch (s.t()) {
                case "stat" -> statMap(s, "stats").forEach(into::add);
                case "pct" -> { double v = s.num("v"); for (Stat st : statList(s)) into.addPercent(st, v); }
                case "per_stat" -> {
                    Stat st = Stat.parse(PetService.str(s.fx(), "stat"));
                    Stat from = Stat.parse(PetService.str(s.fx(), "from"));
                    double every = s.num("every", 1);
                    if (st == null || from == null || every <= 0) break;
                    double base = Sky.stats().get(p, from) - s.num("above", 0);
                    double v = Math.floor(Math.max(0, base) / every) * s.num("v");
                    double cap = s.num("cap", 0);
                    into.add(st, cap > 0 ? Math.min(cap, v) : v);
                }
                case "frac_stat" -> {
                    Stat st = Stat.parse(PetService.str(s.fx(), "stat"));
                    Stat from = Stat.parse(PetService.str(s.fx(), "from"));
                    if (st == null || from == null) break;
                    double v = Sky.stats().get(p, from) * s.num("v") / 100.0;
                    double cap = s.num("cap", 0);
                    into.add(st, cap > 0 ? Math.min(cap, v) : v);
                }
                case "per_stat_pct" -> {
                    Stat from = Stat.parse(PetService.str(s.fx(), "from"));
                    double every = s.num("every", 1);
                    if (from == null || every <= 0) break;
                    double val = Sky.stats().get(p, from);
                    if (s.fx().has("top")) val = Math.min(val, s.num("top"));
                    double pct = Math.floor(Math.max(0, val - s.num("above", 0)) / every + 1e-9) * s.num("v");
                    double cap = s.num("cap", 0);
                    if (cap > 0) pct = Math.min(cap, pct);
                    for (Stat st : statList(s)) into.addPercent(st, pct);
                }
                case "per_skill" -> {
                    int lv = 0;
                    SkillService sk = Sky.service(SkillService.class);
                    for (JsonElement e : s.fx().getAsJsonArray("skills")) {
                        SkillType t = SkillType.parse(e.getAsString());
                        if (t != null) lv += sk.level(p, t);
                    }
                    final int levels = lv;
                    statMap(s, "stats").forEach((st, v) -> into.add(st, v * levels));
                }
                case "per_hotm" -> {
                    int h = Sky.service(Features.Mining.class).hotmLevel(p);
                    statMap(s, "stats").forEach((st, v) -> into.add(st, v * h));
                }
                case "per_digit" -> {
                    long c = Sky.service(Features.Collections.class).count(p, PetService.str(s.fx(), "collection"));
                    int digits = c <= 0 ? 0 : (int) Math.min(s.num("max", 9), String.valueOf(c).length());
                    statMap(s, "stats").forEach((st, v) -> into.add(st, v * digits));
                }
                case "per_players" -> {
                    int n = countPlayers(p, s.num("radius", 25), (int) s.num("max", 99), PetService.str(s.fx(), "pet"));
                    statMap(s, "stats").forEach((st, v) -> into.add(st, v * n));
                }
                case "per_mobs" -> {
                    int n = countMobs(p, s.num("radius", 15), (int) s.num("max", 10), PetService.str(s.fx(), "match"));
                    statMap(s, "stats").forEach((st, v) -> into.add(st, v * n));
                }
                case "kill_stacks" -> {
                    prune(r.killStacks, now);
                    int n = (int) Math.min(r.killStacks.size(), s.num("max", 20));
                    statMap(s, "stats").forEach((st, v) -> into.add(st, v * n));
                }
                case "bow_stacks" -> {
                    prune(r.bowStacks, now);
                    int n = (int) Math.min(r.bowStacks.size(), s.num("max", 20));
                    statMap(s, "stats").forEach((st, v) -> into.add(st, v * n));
                }
                case "cast_buff" -> {
                    if (now < r.castBuffUntil) {
                        double cap = s.num("cap", 0);
                        statMap(s, "stats").forEach((st, v) -> into.add(st, cap > 0 ? Math.min(cap, v) : v));
                    }
                }
                case "jump_buff" -> { if (now < r.jumpBuffUntil) statMap(s, "stats").forEach(into::add); }
                case "treasure_buff" -> { if (now < r.treasureBuffUntil) statMap(s, "stats").forEach(into::add); }
                case "kill_buff" -> { if (now < r.reaperUntil) statMap(s, "stats").forEach(into::add); }
                case "rekindle" -> { if (now < r.rekindleBuffUntil) statMap(s, "stats").forEach(into::add); }
                case "vitality_hits" -> { if (now < r.vitalityUntil) into.add(Stat.VITALITY, s.num("v")); }
                case "held_buff" -> {
                    String held = Sky.items().idOf(p.getInventory().getItemInMainHand());
                    if (held != null && contains(s.fx(), "items", held)) statMap(s, "stats").forEach(into::add);
                }
                case "category_buff" -> {
                    ItemDef d = heldDef(p);
                    if (d != null && d.category != null && contains(s.fx(), "categories", d.category.name())) statMap(s, "stats").forEach(into::add);
                }
                case "armor_buff" -> armorBuff(p, s, into);
                case "armor_flat" -> {
                    int n = 0;
                    for (ItemStack it : gear(p)) {
                        String id = Sky.items().idOf(it);
                        if (id != null && containsKeyword(s.fx(), "match", id)) n++;
                    }
                    final int count = n;
                    statMap(s, "stats").forEach((st, v) -> into.add(st, v * count));
                }
                case "potato_double" -> {
                    for (ItemStack it : gear(p)) {
                        if (Sky.items().idOf(it) == null) continue;
                        ItemStatSheet sh = Sky.items().sheet(it);
                        for (Stat st : sh.view().keySet()) into.add(st, sh.part(st, ItemStatSheet.Source.POTATO));
                    }
                }
                case "combo" -> {
                    if (now < r.comboUntil) {
                        int c = r.combo;
                        double mf = (c >= 5 ? 3 : 0) + (c >= 15 ? 3 : 0) + (c >= 25 ? 3 : 0);
                        if (c >= 50 && s.v().owned().heldItem != null && s.v().owned().heldItem.equals("GRANDMAS_KNITTING_NEEDLE")) mf += 5;
                        into.add(Stat.MAGIC_FIND, mf);
                        if (c >= 20) into.add(Stat.COMBAT_WISDOM, 15);
                    }
                }
                case "symbiosis" -> {
                    int n = symbiosis(p, s);
                    if (n > 0) statMap(s, "stats").forEach((st, v) -> into.add(st, v * n));
                }
                case "per_level" -> {
                    double every = s.num("every", 2);
                    int steps = (int) Math.floor(s.v().level() / every);
                    statMap(s, "stats").forEach((st, v) -> into.add(st, v * steps));
                }
                case "attr_max" -> {
                    Stat st = Stat.parse(PetService.str(s.fx(), "stat"));
                    int n = st == null ? 0 : maxedAttributes(p, s, r, now);
                    if (n > 0) into.addPercent(st, s.num("v") * n);
                }
                case "chop_ready" -> { if (now >= r.chopReadyAt) statMap(s, "stats").forEach(into::add); }
                case "ability_buff" -> {
                    Long until = r.abilityBuff.get(s.a() == null ? "item" : s.a().key);
                    if (until != null && now < until) statMap(s, "stats").forEach(into::add);
                }
                default -> { }
            }
        }
        // Rat "Rodent Blessing" lands on whoever was blessed, whatever pet they have
        if (now < r.blessUntil) into.add(Stat.MAGIC_FIND, r.blessMf);
        batPerson(p, active, into);
        // auras from other players' pets (and your own): non-stacking, the best value counts
        auras(p, into);
    }

    private void armorBuff(Player p, Src s, StatMap into) {
        double pct = s.num("v") / 100.0;
        for (ItemStack it : p.getInventory().getArmorContents()) {
            String id = Sky.items().idOf(it);
            if (id == null || !containsKeyword(s.fx(), "match", id)) continue;
            ItemStatSheet sh = Sky.items().sheet(it);
            for (Stat st : sh.view().keySet()) into.add(st, sh.total(st) * pct);
        }
    }

    /** Bat Person accessories (accessories hand-off: "hook pets"): a summoned Bat pet is one bat you summoned. */
    private static final String[][] BAT_PERSON = {{"BAT_PERSON_ARTIFACT", "10"}, {"BAT_PERSON_RING", "5"}, {"BAT_PERSON_TALISMAN", "2"}};

    private static void batPerson(Player p, View active, StatMap into) {
        if (active == null || !"BAT".equals(active.def().id) || !Sky.has(Features.Accessories.class)) return;
        try {
            Features.Accessories acc = Sky.service(Features.Accessories.class);
            for (String[] b : BAT_PERSON) {
                if (!acc.has(p, b[0])) continue;
                double v = Double.parseDouble(b[1]);
                into.add(Stat.DEFENSE, v);
                into.add(Stat.STRENGTH, v);
                into.add(Stat.INTELLIGENCE, v);
                return;
            }
        } catch (RuntimeException ignored) { }
    }

    /** Hunting attributes at their max level (Jade Dragon "Canopy King"); asked of the hunting module at most every 10 s. */
    private int maxedAttributes(Player p, Src s, RT r, long now) {
        if (now - r.attrAt < 10_000) return r.attrMaxed;
        r.attrAt = now;
        int n = 0;
        if (Sky.has(Features.Hunting.class)) {
            Features.Hunting h = Sky.service(Features.Hunting.class);
            double max = s.num("max", 10);
            JsonElement e = s.fx().get("attrs");
            if (e != null && e.isJsonArray()) for (JsonElement a : e.getAsJsonArray()) {
                try { if (h.attributeLevel(p, a.getAsString()) >= max) n++; } catch (RuntimeException ignored) { }
            }
        }
        r.attrMaxed = n;
        return n;
    }

    /** Fear: 5 per worn Great Spook piece (as the weapons module counts it) plus Eerie "Fear Harvest". */
    double fear(Player p) {
        int pieces = 0;
        try { pieces = Sky.items().setPieces(p, "great_spook"); } catch (RuntimeException ignored) { }
        double f = PetMath.armorFear(pieces);
        for (Src s : sources(p)) {
            if (!"fear_harvest".equals(s.t()) || s.v() == null) continue;
            f += PetMath.fearHarvest(s.v().owned().counter("fear"), (int) s.num("every", 10), (int) s.num("cap", 150), s.num("v"));
        }
        return f;
    }

    /** A number another module owns as a dial (read at use time; same key + default returns the owner's dial). */
    double foreignDial(String key, double def) {
        if (key == null) return def;
        var d = foreignDials.computeIfAbsent(key, k -> Sky.dials().number(k, def, "(also read by pets)"));
        Double v = d.get();
        return v == null ? def : v;
    }

    /** "a|b": any of the families. */
    static boolean matchesAny(String match, LivingEntity e, MobDef def) {
        if (match == null) return true;
        for (String m : match.split("[|]")) if (matches(m, e, def)) return true;
        return false;
    }

    private static List<ItemStack> gear(Player p) {
        List<ItemStack> l = new ArrayList<>();
        for (ItemStack it : p.getInventory().getArmorContents()) if (it != null) l.add(it);
        ItemStack h = p.getInventory().getItemInMainHand();
        if (h != null && !h.getType().isAir()) l.add(h);
        return l;
    }

    private static boolean contains(JsonObject fx, String key, String value) {
        JsonElement e = fx.get(key);
        if (e == null || !e.isJsonArray()) return false;
        for (JsonElement x : e.getAsJsonArray()) if (x.getAsString().equals(value)) return true;
        return false;
    }

    private static boolean containsKeyword(JsonObject fx, String key, String id) {
        JsonElement e = fx.get(key);
        if (e == null || !e.isJsonArray()) return false;
        String up = id.toUpperCase();
        for (JsonElement x : e.getAsJsonArray()) if (up.contains(x.getAsString().toUpperCase())) return true;
        return false;
    }

    private int symbiosis(Player p, Src s) {
        if (!s.v().maxed()) return 0;
        PetData d = svc.data(p);
        if (d == null) return 0;
        String skill = PetService.str(s.fx(), "skill");
        Set<String> types = new HashSet<>();
        for (OwnedPet o : d.pets) {
            if (o.type.equals(s.v().def().id)) continue;
            View v = svc.view(o);
            if (v != null && v.maxed() && skill.equals(v.def().skill)) types.add(o.type);
        }
        return types.size();
    }

    private void auras(Player p, StatMap into) {
        Map<Stat, Double> flat = new EnumMap<>(Stat.class), pct = new EnumMap<>(Stat.class);
        for (Player o : p.getWorld().getPlayers()) {
            if (!Sky.profiles().isReady(o)) continue;
            double dist2 = o.getLocation().distanceSquared(p.getLocation());
            for (Src s : sources(o)) {
                if (!"aura".equals(s.t())) continue;
                double rad = s.num("radius", 20);
                if (dist2 > rad * rad) continue;
                int max = (int) s.num("max", 0);
                if (max > 0 && o != p && !amongNearest(o, p, max, rad)) continue;
                statMap(s, "stats").forEach((st, v) -> flat.merge(st, v, Math::max));
                statMap(s, "pct").forEach((st, v) -> pct.merge(st, v, Math::max));
            }
        }
        flat.forEach(into::add);
        pct.forEach(into::addPercent);
    }

    private static boolean amongNearest(Player owner, Player target, int n, double radius) {
        List<Player> near = new ArrayList<>();
        for (Player x : owner.getWorld().getPlayers()) {
            if (x == owner) continue;
            if (x.getLocation().distanceSquared(owner.getLocation()) <= radius * radius) near.add(x);
        }
        near.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(owner.getLocation()), b.getLocation().distanceSquared(owner.getLocation())));
        return near.indexOf(target) >= 0 && near.indexOf(target) < n;
    }

    private int countPlayers(Player p, double radius, int max, String pet) {
        int n = 0;
        for (Player o : p.getWorld().getPlayers()) {
            if (o == p) continue;
            if (radius < 10000 && o.getLocation().distanceSquared(p.getLocation()) > radius * radius) continue;
            if (pet != null && !pet.equals(svc.activeType(o))) continue;
            if (++n >= max) break;
        }
        return n;
    }

    private int countMobs(Player p, double radius, int max, String match) {
        int n = 0;
        for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
            if (!(e instanceof LivingEntity le) || e instanceof Player) continue;
            MobDef d = Sky.mobs().defOf(le);
            if (d == null || !matches(match, le, d)) continue;
            if (++n >= max) break;
        }
        return n;
    }

    private static void prune(Deque<Long> q, long now) { while (!q.isEmpty() && q.peekFirst() < now) q.pollFirst(); }

    // ---- damage --------------------------------------------------------------------------------

    void modify(DamageContext ctx) {
        Player att = ctx.playerAttacker();
        Player vic = ctx.playerVictim();
        long now = System.currentTimeMillis();
        if (att != null && vic == null && ctx.victim != null) outgoing(att, ctx, now);
        if (vic != null && att == null && ctx.attacker != null) incoming(vic, ctx, now);
    }

    private void outgoing(Player p, DamageContext ctx, long now) {
        if (!Sky.profiles().isReady(p)) return;
        LivingEntity victim = ctx.victim;
        MobDef def = Sky.mobs().defOf(victim);
        RT r = rt(p);
        boolean melee = ctx.kind == DamageContext.Kind.MELEE;
        r.lastHit = now;
        if (r.lastTarget != victim) {   // "against X" perks read the target: recompute on a new one
            r.lastTarget = victim;
            if (r.hasTargetPerk) Sky.stats().invalidate(p);
        }
        if (melee) r.hits++;
        String weapon = ctx.weapon == null ? null : Sky.items().idOf(ctx.weapon);
        ItemDef wdef = weapon == null ? null : Sky.items().def(weapon);
        for (Src s : sources(p)) {
            if (!when(p, s.fx())) continue;
            switch (s.t()) {
                case "dmg" -> { if (matches(PetService.str(s.fx(), "match"), victim, def)) ctx.additive += s.num("v"); }
                case "dmg_missing" -> {
                    double max = Sky.mobs().maxHealth(victim);
                    if (max > 0) ctx.additive += s.num("v") * Math.max(0, 1 - Sky.mobs().health(victim) / max);
                }
                case "dmg_far" -> { if (melee && p.getLocation().distance(victim.getLocation()) > s.num("dist", 3)) ctx.additive += s.num("v"); }
                case "dmg_near" -> { if (p.getLocation().distance(victim.getLocation()) <= s.num("dist", 1.5)) ctx.additive += s.num("v"); }
                case "dmg_isolated" -> { if (isolated(victim, s.num("radius", 15))) ctx.additive += s.num("v"); }
                case "dmg_retaliate" -> {
                    Long t = r.attackedBy.get(victim.getUniqueId());
                    if (t != null && now - t < 30_000) ctx.additive += s.num("v");
                }
                case "dmg_level" -> {
                    if (def == null) break;
                    double best = 0;
                    for (JsonElement e : s.fx().getAsJsonArray("tiers")) {
                        var a = e.getAsJsonArray();
                        if (def.level >= a.get(0).getAsInt()) best = Math.max(best, a.get(1).getAsDouble());
                    }
                    ctx.additive += best;
                }
                case "dmg_bank" -> {
                    double bank = Sky.economy().bank(p);
                    ctx.additive += Math.min(s.num("cap", 250), bank / 1_000_000.0 * s.num("v"));
                }
                case "dmg_kind" -> {
                    if (ctx.kind.name().equals(PetService.str(s.fx(), "kind"))) {
                        double v = s.num("v");
                        if (s.fx().has("dungeon") && Sky.service(Features.Dungeons.class).inDungeon(p)) v *= s.num("dungeon");
                        ctx.additive += v;
                    }
                }
                case "dmg_mult" -> ctx.multiplicative *= Math.max(1, s.num("v"));
                case "last_stand" -> { if (now < r.lastStandUntil) ctx.additive += s.num("dmg", 40); }
                case "held_buff" -> { if (weapon != null && contains(s.fx(), "items", weapon)) ctx.flatBonus += s.num("damage", 0); }
                case "category_buff" -> {
                    if (wdef != null && wdef.category != null && contains(s.fx(), "categories", wdef.category.name())) ctx.flatBonus += s.num("damage", 0);
                }
                case "cast_weapon_dmg" -> { if (now < r.weaponDmgUntil) ctx.flatBonus += r.weaponDmg; }
                case "toss" -> {
                    int every = (int) s.num("every", 5);
                    if (melee && every > 0 && r.hits % every == 0 && now >= r.tossCd) {
                        ctx.multiplicative *= s.num("mult", 5);
                        r.tossCd = now + (long) (s.num("cd", 5) * 1000);
                        later(() -> { if (victim.isValid()) victim.setVelocity(victim.getVelocity().add(new Vector(0, 0.9, 0))); });
                    }
                }
                case "slow_hit" -> {
                    if (melee) {
                        int amp = (int) Math.max(0, Math.round(s.num("v") / 15.0) - 1);
                        later(() -> { if (victim.isValid()) victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, amp, false, false)); });
                    }
                }
                case "dmg_per_fear" -> {
                    if (matchesAny(PetService.str(s.fx(), "match"), victim, def)) ctx.additive += s.num("v") * fear(p);
                }
                case "held_greed" -> {   // Golden Dragon "Midas Touch": the Midas weapons' greed (their damage) is v% stronger
                    if (wdef != null && weapon != null && contains(s.fx(), "items", weapon)) ctx.flatBonus += Math.max(0, wdef.damage) * s.num("v") / 100.0;
                }
                case "hemorrhage" -> { if (melee) hemorrhage(victim, s.num("v"), s.num("dur", 10)); }
                case "stun" -> {
                    if (now >= r.lastStun && ThreadLocalRandom.current().nextDouble(100) < s.num("chance")) {
                        r.lastStun = now + (long) (s.num("cd", 10) * 1000);
                        later(() -> { if (victim.isValid()) victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 9, false, false)); });
                    }
                }
                default -> { }
            }
        }
    }

    private static boolean isolated(LivingEntity victim, double radius) {
        for (Entity e : victim.getNearbyEntities(radius, radius, radius)) {
            if (e instanceof LivingEntity le && !(e instanceof Player) && Sky.mobs().isSkyMob(le)) return false;
        }
        return true;
    }

    private void incoming(Player p, DamageContext ctx, long now) {
        if (!Sky.profiles().isReady(p)) return;
        RT r = rt(p);
        LivingEntity attacker = ctx.attacker;
        MobDef def = Sky.mobs().defOf(attacker);
        r.attackedBy.put(attacker.getUniqueId(), now);
        if (r.attackedBy.size() > 64) r.attackedBy.values().removeIf(t -> now - t > 30_000);
        for (Src s : sources(p)) {
            if (!when(p, s.fx())) continue;
            switch (s.t()) {
                case "dmg_taken" -> {
                    double v = s.num("v") / 100.0;
                    if (s.fx().has("absorption")) {
                        if (Sky.vitals().absorption(p) > 0) ctx.multiplicative *= 1.0 / (1.0 + v);
                    } else if (s.fx().has("hp_below")) {
                        if (hpPct(p) < s.num("hp_below")) ctx.multiplicative *= Math.max(0, 1 - v);
                    } else if (matches(PetService.str(s.fx(), "match"), attacker, def)) {
                        ctx.multiplicative *= Math.max(0, 1 - v);
                    }
                }
                case "bulwark" -> {
                    if (s.v() != null && matches(PetService.str(s.fx(), "match"), attacker, def)) {
                        int steps = bulwarkSteps(s, s.v().owned().counter("bulwark"));
                        // wave 5: a TRUE hit (core's DamageContext.Kind.TRUE) is reduced by True Defense, so the Ward's
                        // True Defense steps apply there and its Defense steps everywhere else
                        boolean trueHit = ctx.kind == DamageContext.Kind.TRUE;
                        double extra = steps * (trueHit ? s.num("true_defense", 3) : s.num("defense", 30));
                        if (extra > 0) {
                            double d = Math.max(0, Sky.stats().get(p, trueHit ? Stat.TRUE_DEFENSE : Stat.DEFENSE));
                            ctx.multiplicative *= (d + 100) / (d + extra + 100);   // the genre's def/(def+100) with the extra (True) Defense
                        }
                    }
                }
                case "blizzard" -> {
                    if (attacker.getLocation().distance(p.getLocation()) <= s.num("radius", 16)) ctx.multiplicative *= Math.max(0, 1 - s.num("v") / 100.0);
                }
                case "last_stand_boost" -> {
                    // Ankylosaurus "Unbreakable Will" (wave 5): below 40% health the Last Stand enchant on your armour gives
                    // `per`% Defense per level per piece (enchanting data 2.5); this pet makes that a% stronger, as extra
                    // Defense in the same def/(def+100) curve the enchant uses. The enchant's own part stays enchanting's.
                    if (hpPct(p) < s.num("hp", 40)) {
                        double pct = PetMath.lastStandExtraPct(wornEnchantSum(p, PetService.str(s.fx(), "enchant")), s.num("per", 2.5), s.num("v"));
                        if (pct > 0) {
                            double d = Math.max(0, Sky.stats().get(p, Stat.DEFENSE));
                            ctx.multiplicative *= (d + 100) / (d + d * pct / 100.0 + 100);
                        }
                    }
                }
                case "last_stand" -> {
                    if (now >= r.lastStandCd && hpPct(p) < s.num("hp", 20)) {
                        r.lastStandUntil = now + (long) (s.num("dur", 12) * 1000);
                        r.lastStandCd = now + (long) (s.num("cd", 60) * 1000);
                        Sky.vitals().addAbsorption(p, Sky.vitals().maxHealth(p) * s.num("shield", 40) / 100.0);
                        com.skyclone.api.ui.Msg.send(p, "&6" + (s.a() == null ? "Last Stand" : s.a().name) + " &aactivated!");
                    }
                    if (now < r.lastStandUntil) ctx.multiplicative *= Math.max(0, 1 - s.num("reduce", 20) / 100.0);
                }
                default -> { }
            }
        }
    }

    /** After the final number is known: rebirth, rebound, withering, snowballs, tail club, stacks. */
    /**
     * Started breaking a block: remember what mining block it is (data/pets/dig.json, generated from mining's block
     * table, first match per island like mining's own lookup) so "when mining X" perks apply to exactly that block.
     * Stats are recomputed only when the kind changes and only for players whose pet has such a perk.
     */
    @EventHandler(priority = EventPriority.MONITOR)   // not ignoreCancelled: protection may cancel it while mining's own engine digs
    public void onDig(org.bukkit.event.block.BlockDamageEvent e) {
        Player p = e.getPlayer();
        if (!Sky.profiles().isReady(p)) return;
        RT r = rt(p);
        sources(p);
        if (!r.hasDigPerk) return;
        List<String> tags = svc.defs().digTags(PetService.island(p), e.getBlock().getType().name());
        long now = System.currentTimeMillis();
        boolean changed = !tags.equals(r.digTags) || now - r.digAt > 3000;
        r.digTags = tags;
        r.digAt = now;
        if (changed) Sky.stats().invalidate(p);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Player vic = ctx.playerVictim();
        long now = System.currentTimeMillis();
        if (vic != null && Sky.profiles().isReady(vic)) {
            RT r = rt(vic);
            if (now < r.immuneUntil) { e.setCancelled(true); return; }
            r.lastDamaged = now;
            r.overhealGiven = false;
            for (Src s : sources(vic)) {
                if (!when(vic, s.fx())) continue;
                switch (s.t()) {
                    case "rekindle" -> {
                        double left = Sky.vitals().health(vic) + Sky.vitals().absorption(vic);
                        if (ctx.finalDamage >= left && now >= r.rekindleCd) {
                            e.setCancelled(true);
                            long dur = (long) (s.num("dur", 4) * 1000);
                            r.immuneUntil = now + dur;
                            r.rekindleBuffUntil = now + dur;
                            r.rekindleCd = now + (long) (s.num("cd", 60) * 1000);
                            Sky.vitals().setHealth(vic, Math.max(1, Sky.vitals().health(vic)));
                            vic.getWorld().spawnParticle(Particle.FLAME, vic.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.05);
                            com.skyclone.api.ui.Msg.send(vic, "&6" + (s.a() == null ? "Rebirth" : s.a().name) + " &asaved your life!");
                            Sky.stats().invalidate(vic);
                            return;
                        }
                    }
                    case "ricochet" -> {
                        if (ctx.attacker != null && !(ctx.attacker instanceof Player) && ctx.kind != DamageContext.Kind.TRUE
                                && ThreadLocalRandom.current().nextDouble(100) < s.num("v")) {
                            double dmg = ctx.finalDamage;
                            LivingEntity a = ctx.attacker;
                            later(() -> { if (a.isValid()) Sky.combat().rawDamage(a, dmg, vic, DamageContext.Kind.TRUE); });
                        }
                    }
                    case "vitality_hits" -> {
                        if (++r.hitsTaken >= s.num("hits", 10)) {
                            r.hitsTaken = 0;
                            r.vitalityUntil = now + 10_000;
                            Sky.stats().invalidate(vic);
                        }
                    }
                    default -> { }
                }
            }
        }
        Player att = ctx.playerAttacker();
        if (att == null || ctx.victim instanceof Player || !Sky.profiles().isReady(att)) return;
        if (ctx.tags.contains("pets")) return;   // our own follow-up hits never chain
        RT r = rt(att);
        LivingEntity victim = ctx.victim;
        boolean melee = ctx.kind == DamageContext.Kind.MELEE;
        if (melee) r.lastMelee = ctx.finalDamage;
        if (melee) r.flareHits++;
        for (Src s : sources(att)) {
            if (!when(att, s.fx())) continue;
            switch (s.t()) {
                case "pillar" -> {   // Wisp "Frost Strike": blaze slayer fire pillars take 2 hits' worth per hit (slayer sets 1 per hit)
                    MobDef vd = Sky.mobs().defOf(victim);
                    if (vd != null && PILLAR.equals(vd.id)) ctx.finalDamage *= Math.max(1, Math.max(s.num("mult", 2), coldfused(att) ? 2 : 1));
                }
                case "bow_stacks" -> { if (ctx.kind == DamageContext.Kind.PROJECTILE) { r.bowStacks.addLast(now + (long) (s.num("dur", 8) * 1000)); Sky.stats().invalidate(att); } }
                case "wither" -> {
                    if (!withering.contains(victim.getUniqueId()) && ctx.finalDamage > 0) {
                        double total = ctx.finalDamage * s.num("v") / 100.0;
                        int secs = (int) Math.max(1, s.num("dur", 3));
                        dot(att, victim, total / secs, secs, Particle.SMOKE);
                    }
                }
                case "flare" -> {
                    int every = (int) s.num("every", 4);
                    if (melee && every > 0 && r.flareHits % every == 0 && !withering.contains(victim.getUniqueId())) {
                        double per = s.num("v") * Sky.stats().get(att, Stat.CRIT_DAMAGE);
                        dot(att, victim, per, (int) Math.max(1, s.num("dur", 4)), Particle.FLAME);
                    }
                }
                case "snowball" -> {
                    if (melee && now >= r.lastSnow) {
                        r.lastSnow = now + (long) (s.num("cd", 1) * 1000);
                        double dmg = Math.min(s.num("cap", 200000), ctx.finalDamage * s.num("v") / 100.0);
                        boolean dbl = hasType(att, "snowball_crit") && ThreadLocalRandom.current().nextDouble(100) < 50;
                        double fin = dbl ? dmg * 2 : dmg;
                        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                            if (!victim.isValid()) return;
                            victim.getWorld().spawnParticle(Particle.ITEM_SNOWBALL, victim.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0);
                            Sky.combat().rawDamage(victim, fin, att, DamageContext.Kind.MAGIC);
                        }, 4L);
                    }
                }
                case "club" -> {
                    int every = (int) s.num("every", 5);
                    if (melee && every > 0 && r.hits % every == 0) {
                        double dmg = ctx.finalDamage * s.num("v") / 100.0;
                        double rad = s.num("radius", 5);
                        later(() -> {
                            for (Entity en : victim.getNearbyEntities(rad, rad, rad)) {
                                if (en instanceof LivingEntity le && !(en instanceof Player) && Sky.mobs().isSkyMob(le))
                                    Sky.combat().rawDamage(le, dmg, att, DamageContext.Kind.MELEE);
                            }
                        });
                    }
                }
                default -> { }
            }
        }
    }

    private boolean hasType(Player p, String type) {
        for (Src s : sources(p)) if (type.equals(s.t())) return true;
        return false;
    }

    /** Damage over time, once per second, never stacking on the same victim. */
    private void dot(Player owner, LivingEntity victim, double perSecond, int seconds, Particle particle) {
        if (perSecond <= 0) return;
        UUID id = victim.getUniqueId();
        withering.add(id);
        final int[] left = {seconds};
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), task -> {
            if (!victim.isValid() || left[0]-- <= 0 || !owner.isOnline()) {
                withering.remove(id);
                task.cancel();
                return;
            }
            victim.getWorld().spawnParticle(particle, victim.getLocation().add(0, 1, 0), 6, 0.3, 0.5, 0.3, 0.01);
            Sky.combat().rawDamage(victim, perSecond, owner, DamageContext.Kind.MAGIC);
        }, 20L, 20L);
    }

    private static void later(Runnable r) { Bukkit.getScheduler().runTask(Sky.plugin(), r); }

    // ---- mana --------------------------------------------------------------------------------

    double cost(AbilityService.Use use, double cost) {
        Player p = use.player();
        if (!Sky.profiles().isReady(p)) return cost;
        double c = cost;
        String id = (use.ability() == null ? "" : String.valueOf(use.ability().id)) + " " + (use.itemDef() == null ? "" : use.itemDef().id);
        String lower = id.toLowerCase();
        for (Src s : sources(p)) {
            if (!when(p, s.fx())) continue;
            switch (s.t()) {
                case "mana_cost" -> {
                    String m = PetService.str(s.fx(), "match");
                    boolean ok = m == null || lower.contains(m.toLowerCase())
                            || ("boots".equals(m) && use.itemDef() != null && use.itemDef().category == ItemCategory.BOOTS);
                    if (ok) c *= Math.max(0, 1 - s.num("v") / 100.0);
                }
                case "free_cast" -> { if (ThreadLocalRandom.current().nextDouble(100) < s.num("v")) c = 0; }
                default -> { }
            }
        }
        return c;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbility(SkyEvents.AbilityUsed e) {
        Player p = e.player;
        if (!Sky.profiles().isReady(p)) return;
        RT r = rt(p);
        long now = System.currentTimeMillis();
        String id = ((e.ability == null ? "" : e.ability.id) + " " + Sky.items().idOf(e.item)).toLowerCase();
        ItemDef idef = Sky.items().def(Sky.items().idOf(e.item));
        for (Src s : sources(p)) {
            if (!when(p, s.fx())) continue;
            switch (s.t()) {
                case "cast_buff" -> { r.castBuffUntil = now + (long) (s.num("dur", 20) * 1000); Sky.stats().invalidate(p); }
                case "ability_buff" -> {   // Sloth "Mighty Throw": only that ability (the axe throw) arms it
                    String m = PetService.str(s.fx(), "match");
                    if (m == null || id.contains(m.toLowerCase())) {
                        r.abilityBuff.put(s.a() == null ? "item" : s.a().key, now + (long) (s.num("dur", 3) * 1000));
                        Sky.stats().invalidate(p);
                    }
                }
                case "cast_weapon_dmg" -> {
                    String m = PetService.str(s.fx(), "match");
                    if (m == null || id.contains(m) || id.contains("aspect_of_the_end") || id.contains("aspect_of_the_void")) {
                        r.weaponDmg = s.num("v");
                        r.weaponDmgUntil = now + (long) (s.num("dur", 5) * 1000);
                    }
                }
                case "cooldown" -> {
                    if (idef != null && idef.category != null && idef.category.isWeapon() && e.ability != null) {
                        String aid = e.ability.id;
                        double pct = s.num("v");
                        later(() -> {
                            double left = Sky.abilities().cooldownLeft(p, aid);
                            if (left > 0) Sky.abilities().setCooldown(p, aid, left * Math.max(0, 1 - pct / 100.0));
                        });
                    }
                }
                default -> { }
            }
        }
    }

    // ---- kills, gathering, skill XP, loot ---------------------------------------------------------

    /**
     * Beastmaster Crest (accessories hand-off: its "grows with every mythical beast" half is ours). The crest's own
     * Health and Intelligence (its item stats, which accessories already counts once) are added again for every
     * {@link PetMath#CREST_STEP} mythological kills, at most {@link PetMath#CREST_MAX_STEPS} times.
     */
    private void crestStats(Player p, StatMap into) {
        PetData d = svc.data(p);
        if (d == null || d.mythKills < PetMath.CREST_STEP) return;
        String id = svc.crest(p);
        ItemDef def = id == null ? null : Sky.items().def(id);
        if (def == null) return;
        int steps = PetMath.crestSteps(d.mythKills);
        for (Stat st : new Stat[]{Stat.HEALTH, Stat.INTELLIGENCE}) {
            Double b = def.stats.get(st);
            if (b != null && b > 0) into.add(st, b * steps);
        }
    }

    /** A mythological kill while a crest counts: one more toward its growth (stats recomputed on a new step). */
    private void crestKill(Player p, SkyEvents.MobKill e) {
        if (!matches("mythological", e.mob, e.def)) return;
        PetData d = svc.data(p);
        if (d == null || svc.crest(p) == null || d.mythKills >= 1_000_000) return;
        int before = PetMath.crestSteps(d.mythKills);
        d.mythKills++;
        if (PetMath.crestSteps(d.mythKills) != before) {
            Sky.stats().invalidate(p);
            com.skyclone.api.ui.Msg.send(p, "&6Beastmaster Crest &7grew stronger! &8(" + d.mythKills + " mythological kills)");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (p == null || !Sky.profiles().isReady(p)) return;
        crestKill(p, e);
        RT r = rt(p);
        long now = System.currentTimeMillis();
        boolean statsChanged = false;
        for (Src s : sources(p)) {
            if (!when(p, s.fx())) continue;
            switch (s.t()) {
                case "kill_stacks" -> {
                    r.killStacks.addLast(now + (long) (s.num("dur", 5) * 1000));
                    while (r.killStacks.size() > s.num("max", 20)) r.killStacks.pollFirst();
                    statsChanged = true;
                }
                case "kill_buff" -> { r.reaperUntil = now + (long) (s.num("dur", 5) * 1000); statsChanged = true; }
                case "bulwark" -> {
                    if (s.v() != null && matches(PetService.str(s.fx(), "match"), e.mob, e.def)) {
                        OwnedPet o = s.v().owned();
                        int before = bulwarkSteps(s, o.counter("bulwark"));
                        o.bump("bulwark");
                        int after = bulwarkSteps(s, o.counter("bulwark"));
                        if (after > before) {
                            com.skyclone.api.ui.Msg.send(p, "&6" + (s.a() == null ? "Bulwark" : s.a().name) + " &7is now &a+"
                                    + PetMath.fmt(after * s.num("defense", 30)) + " Defense &7and &f+" + PetMath.fmt(after * s.num("true_defense", 3))
                                    + " True Defense &7against blazes and demons.");
                        }
                    }
                }
                case "combat_xp" -> { if (matches(PetService.str(s.fx(), "match"), e.mob, e.def)) e.combatXp *= Math.max(1, s.num("v")); }
                case "coins_kill" -> e.coins += s.num("v");
                case "coins_level" -> e.coins += s.num("v") * Math.max(1, e.def.level);
                case "kill_drop" -> {
                    if (matches(PetService.str(s.fx(), "match"), e.mob, e.def) && ThreadLocalRandom.current().nextDouble(100) < s.num("chance")) giveRandom(p, s);
                }
                case "kill_armor" -> {   // Hound "Bloodhound": each armour drop of the mob gets an extra roll at v% of its chance
                    if (e.def.drops != null && !e.def.drops.isEmpty()) {
                        List<com.skyclone.api.mob.DropDef> extra = new ArrayList<>();
                        for (com.skyclone.api.mob.DropDef dd : e.def.drops) {
                            ItemDef idef = dd == null || dd.item == null ? null : Sky.items().def(dd.item);
                            if (idef == null || idef.category == null || !idef.category.isArmor()) continue;
                            com.skyclone.api.mob.DropDef c = new com.skyclone.api.mob.DropDef(dd.item, dd.min, dd.max, dd.chance * s.num("v") / 100.0);
                            c.magicFind = dd.magicFind;
                            c.label = dd.label;
                            c.requirement = dd.requirement;
                            extra.add(c);
                        }
                        if (!extra.isEmpty()) later(() -> Sky.loot().roll(p, extra, LootSource.MOB_DROP, Sky.stats().get(p, Stat.MAGIC_FIND)));
                    }
                }
                case "kill_double" -> {
                    if (e.def.drops != null && !e.def.drops.isEmpty() && matches(PetService.str(s.fx(), "match"), e.mob, e.def)
                            && ThreadLocalRandom.current().nextDouble(100) < s.num("chance")) {
                        var drops = e.def.drops;
                        later(() -> Sky.loot().roll(p, drops, LootSource.MOB_DROP, Sky.stats().get(p, Stat.MAGIC_FIND)));
                    }
                }
                case "combo" -> {
                    if (now > r.comboUntil) r.combo = 0;
                    r.combo++;
                    int bracket = Math.max(0, Math.min(5, r.combo / 5 - 1));
                    double secs = s.v().num(String.valueOf((char) ('a' + bracket)), s.a());
                    r.comboUntil = now + (long) (Math.max(1, secs) * 1000);
                    e.coins += (r.combo >= 10 ? 10 : 0) + (r.combo >= 30 ? 10 : 0);
                    statsChanged = true;
                }
                case "symbiosis" -> {
                    if (s.fx().has("coins")) e.coins += s.num("coins") * symbiosis(p, s);
                }
                case "pillar" -> { if (e.def != null && PILLAR.equals(e.def.id)) healOver(p, s.num("heal", 15), 3); }
                case "kill_bonus" -> killBonus(p, s, e);
                case "fear_harvest" -> {
                    if (s.v() != null && matchesAny(PetService.str(s.fx(), "match"), e.mob, e.def)) {
                        OwnedPet o = s.v().owned();
                        if (o.counter("fear") < s.num("cap", 150)) o.bump("fear");
                    }
                }
                default -> { }
            }
        }
        if (statsChanged) Sky.stats().invalidate(p);
    }

    private void giveRandom(Player p, Src s) {
        JsonElement items = s.fx().get("items");
        if (items == null || !items.isJsonArray() || items.getAsJsonArray().isEmpty()) return;
        List<String> ok = new ArrayList<>();
        for (JsonElement x : items.getAsJsonArray()) if (Sky.items().exists(x.getAsString())) ok.add(x.getAsString());
        if (ok.isEmpty()) return;
        String id = ok.get(ThreadLocalRandom.current().nextInt(ok.size()));
        later(() -> Sky.loot().give(p, id, 1, LootSource.PET));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGather(SkyEvents.Gather e) {
        Player p = e.player;
        if (p == null || !Sky.profiles().isReady(p)) return;
        for (Src s : sources(p)) {
            if (!when(p, s.fx())) continue;
            String t = s.t();
            if ("gather_spawn".equals(t)) { gatherSpawn(p, s, e); continue; }
            if ("crop_fortune".equals(t)) { cropFortune(p, s, e); continue; }
            if ("gem_double".equals(t)) { gemDouble(p, s, e); continue; }
            if ("chop_ready".equals(t)) { chopUsed(p, s, e); continue; }
            if (!"gather_drop".equals(t) && !"gather_orb".equals(t)) continue;
            if (e.kind == null || !e.kind.name().equals(PetService.str(s.fx(), "kind"))) continue;
            String what = PetService.str(s.fx(), "what");
            if (what != null && !what.equalsIgnoreCase(e.what)) continue;
            if (ThreadLocalRandom.current().nextDouble(100) >= s.num("chance")) continue;
            if ("gather_orb".equals(t)) p.giveExp(1 + ThreadLocalRandom.current().nextInt(3));
            else giveRandom(p, s);
        }
    }

    /**
     * Endermite "Mite Lure": breaking one of {@code what} (any of) has {@code chance}% per point of
     * {@code per_stat} (Pet Luck) to dig up {@code mob} next to the block, owned by the player.
     */
    private void gatherSpawn(Player p, Src s, SkyEvents.Gather e) {
        if (e.kind == null || e.location == null || !e.kind.name().equals(PetService.str(s.fx(), "kind"))) return;
        if (!contains(s.fx(), "what", String.valueOf(e.what).toUpperCase())) return;
        String mob = PetService.str(s.fx(), "mob");
        if (mob == null || Sky.mobs().def(mob) == null) return;
        double per = s.num("chance");
        String st = PetService.str(s.fx(), "per_stat");
        Stat stat = st == null ? null : Stat.parse(st);
        double chance = stat == null ? per : per * Math.max(0, Sky.stats().get(p, stat));
        RT r = rt(p);
        long now = System.currentTimeMillis();
        if (now < r.spawnCd) return;   // at most one every cd seconds, so a high-luck pet cannot flood the island
        if (ThreadLocalRandom.current().nextDouble(100) >= Math.min(100, chance)) return;
        r.spawnCd = now + (long) (s.num("cd", 3) * 1000);
        Location at = e.location.clone().add(0.5, 1, 0.5);
        later(() -> {
            if (!p.isOnline() || at.getWorld() != p.getWorld()) return;
            Sky.mobs().spawn(mob, at, p);
            com.skyclone.api.ui.Msg.send(p, "&6" + (s.a() == null ? "Mite Lure" : s.a().name) + "&7: you dug up a &5" + Sky.mobs().def(mob).name + "&7!");
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSkillXpBoost(SkyEvents.SkillXpGain e) {
        Player p = e.player;
        if (p == null || !Sky.profiles().isReady(p) || e.skill == null) return;
        double pct = 0;
        for (Src s : sources(p)) {
            if (!"skill_xp".equals(s.t()) || !when(p, s.fx())) continue;
            String sk = PetService.str(s.fx(), "skill");
            if ("ALL".equals(sk) || e.skill.name().equals(sk)) pct += s.num("v");
        }
        if (pct > 0) e.amount *= 1 + pct / 100.0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSkillXp(SkyEvents.SkillXpGain e) {
        if (e.player != null && e.skill != null) svc.onSkillXp(e.player, e.skill, e.amount);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemGain(SkyEvents.ItemGain e) {
        Player p = e.player;
        if (p == null || e.source == null || e.itemId == null || !Sky.profiles().isReady(p)) return;
        if (echoSource(e.source)) {
            echo(p, e);
            blessCopy(p, e);
        }
        if (e.source == LootSource.PET || !e.source.natural) return;
        double pct = 0;
        for (Src s : sources(p)) {
            if (!"collection_bonus".equals(s.t()) || !when(p, s.fx())) continue;
            String from = PetService.str(s.fx(), "source");
            if ("mob".equals(from) && e.source != LootSource.MOB_DROP && e.source != LootSource.SLAYER) continue;
            pct += s.num("v");
        }
        if (pct <= 0 || !Sky.service(Features.Collections.class).isCollectionItem(e.itemId)) return;
        double exp = e.amount * pct / 100.0;
        int n = (int) Math.floor(exp) + (ThreadLocalRandom.current().nextDouble() < exp - Math.floor(exp) ? 1 : 0);
        if (n <= 0) return;
        String id = e.itemId;
        later(() -> { if (p.isOnline()) Sky.loot().give(p, id, n, LootSource.PET); });
    }

    /**
     * Fishing: treasure catches (Hermit Crab "Shell Shelter" buff, Seal "Peak Form" bait) and the sea
     * creature you just hooked (Dolphin "Splash Stun", Spinosaurus "Sharp Teeth"). The sea creature is
     * found the tick after the catch: a live mob of that id, owned by this player, a couple of seconds old.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onFish(SkyEvents.FishCaught e) {
        Player p = e.player;
        if (p == null || !Sky.profiles().isReady(p)) return;
        RT r = rt(p);
        long now = System.currentTimeMillis();
        boolean statsChanged = false;
        double stun = 0, wound = 0;
        for (Src s : sources(p)) {
            if (!when(p, s.fx())) continue;
            switch (s.t()) {
                case "treasure_buff" -> {
                    if (e.treasure) { r.treasureBuffUntil = now + (long) (s.num("dur", 30) * 1000); statsChanged = true; }
                }
                case "treasure_drop" -> {
                    if (e.treasure && ThreadLocalRandom.current().nextDouble(100) < s.num("chance")) giveRandom(p, s);
                }
                case "echo_mob" -> {
                    if (e.seaCreature && e.what != null && contains(s.fx(), "ids", e.what)
                            && ThreadLocalRandom.current().nextDouble(100) < s.num("chance")) echoMob(p, e.what, s);
                }
                case "sc_treasure" -> {
                    if (e.seaCreature && ThreadLocalRandom.current().nextDouble(100) < s.num("chance")) rollTreasure(p, s, "table", "good");
                }
                case "treasure_up" -> {
                    String next = e.treasure ? PetMath.nextQuality(e.quality) : null;
                    if (next != null && ThreadLocalRandom.current().nextDouble(100) < s.num("v")) rollTreasure(p, s, next, next);
                }
                case "fish_shard" -> fishShard(p, s, e);
                case "sc_stun" -> { if (e.seaCreature) stun = Math.max(stun, s.num("dur", 5)); }
                case "sc_wound" -> { if (e.seaCreature) wound = Math.max(wound, s.num("v")); }
                default -> { }
            }
        }
        if (statsChanged) Sky.stats().invalidate(p);
        if (!e.seaCreature || e.what == null || (stun <= 0 && wound <= 0)) return;
        final double stunSecs = stun, woundPct = Math.min(90, wound);
        String mobId = e.what;
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            for (LivingEntity m : hookedSeaCreatures(p, mobId)) {
                if (woundPct > 0) {
                    double max = Sky.mobs().maxHealth(m);
                    double hp = Sky.mobs().health(m);
                    double target = Math.max(1, max * (1 - woundPct / 100.0));
                    if (max > 0 && hp > target) Sky.mobs().setHealth(m, target);
                }
                if (stunSecs > 0) {
                    int ticks = (int) Math.round(stunSecs * 20);
                    m.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 9, false, false));
                    m.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, 9, false, false));
                    m.getWorld().spawnParticle(Particle.SPLASH, m.getLocation().add(0, 1, 0), 30, 0.4, 0.5, 0.4, 0.1);
                }
            }
        }, 2L);
    }

    /** Sea creatures of this id that the player hooked in the last ~3 seconds. */
    private static List<LivingEntity> hookedSeaCreatures(Player p, String mobId) {
        List<LivingEntity> out = new ArrayList<>();
        java.util.Collection<LivingEntity> live;
        try { live = Sky.mobs().live(mobId); } catch (RuntimeException ex) { return out; }
        if (live == null) return out;
        for (LivingEntity m : live) {
            if (m == null || !m.isValid() || m.getWorld() != p.getWorld() || m.getTicksLived() > 60) continue;
            UUID owner = Sky.mobs().owner(m);
            if (owner != null && !owner.equals(p.getUniqueId())) continue;
            if (owner == null && m.getLocation().distanceSquared(p.getLocation()) > 40 * 40) continue;
            out.add(m);
        }
        return out;
    }

    /** Rift Ferret "Orb Chaser": experience orbs give v% more (the fraction is paid by chance). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onOrb(org.bukkit.event.player.PlayerExpChangeEvent e) {
        Player p = e.getPlayer();
        if (e.getAmount() <= 0 || !Sky.profiles().isReady(p)) return;
        double pct = 0;
        for (Src s : sources(p)) if ("xp_orbs".equals(s.t()) && when(p, s.fx())) pct += s.num("v");
        if (pct <= 0) return;
        double want = e.getAmount() * (1 + pct / 100.0);
        int n = (int) Math.floor(want);
        if (ThreadLocalRandom.current().nextDouble() < want - n) n++;
        e.setAmount(n);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(SkyEvents.SkyDeath e) {
        if (e.player == null || !Sky.profiles().isReady(e.player)) return;
        for (Src s : sources(e.player)) if ("no_coin_loss".equals(s.t())) { e.coinsLost = 0; return; }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!Sky.profiles().isReady(p) || PetService.island(p) == null) return;
        float fell = p.getFallDistance();
        for (Src s : sources(p)) {
            switch (s.t()) {
                case "trample" -> {
                    if (fell >= s.num("min", 20)) {
                        e.setCancelled(true);
                        ItemDef w = heldDef(p);
                        double dmg = (w == null ? 0 : w.damage) * s.num("v") / 100.0 * fell;
                        double rad = s.num("radius", 3);
                        if (dmg > 0) for (Entity en : p.getNearbyEntities(rad, rad, rad)) {
                            if (en instanceof LivingEntity le && !(en instanceof Player) && Sky.mobs().isSkyMob(le))
                                Sky.combat().rawDamage(le, dmg, p, DamageContext.Kind.MELEE);
                        }
                        return;
                    }
                }
                case "fall" -> {
                    double v = s.num("v");
                    if (v >= 100) { e.setCancelled(true); return; }
                    e.setDamage(e.getDamage() * (1 - v / 100.0));
                }
                default -> { }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJump(com.destroystokyo.paper.event.player.PlayerJumpEvent e) {
        Player p = e.getPlayer();
        if (!Sky.profiles().isReady(p)) return;
        for (Src s : sources(p)) {
            if ("jump_buff".equals(s.t())) {
                rt(p).jumpBuffUntil = System.currentTimeMillis() + (long) (s.num("dur", 20) * 1000);
                Sky.stats().invalidate(p);
                return;
            }
        }
    }

    // ---- wave 5: abilities that reach other systems through their outcomes ------------------------
    //
    // "X% more often" for something another module rolls is done as the pet's OWN extra roll on the
    // outcome: an echo of the item / creature at the pet's chance, or a bonus roll at base x pct / 100
    // (PetMath.bonusChance). The expected count rises by exactly the pet's percentage, and the owner's
    // code stays untouched. Copies are given as LootSource.PET, which no echo ever reacts to (no loops).

    private static String abilityName(Src s, String def) { return s.a() == null ? def : s.a().name; }

    private static String itemName(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? id : d.rarity.code() + d.name;
    }

    /** Sources an echo may copy: natural drops and rewards, never pets' own gifts, minions, pick-ups or trades. */
    static boolean echoSource(LootSource s) {
        return s != null && s != LootSource.PET && s != LootSource.MINION && s != LootSource.PICKUP && (s.natural || s == LootSource.REWARD);
    }

    /** {@code echo_item}: one of these items came in; at the pet's chance the same amount comes again. */
    private void echo(Player p, SkyEvents.ItemGain e) {
        for (Src s : sources(p)) {
            if (!"echo_item".equals(s.t()) || !contains(s.fx(), "items", e.itemId) || !when(p, s.fx())) continue;
            if (ThreadLocalRandom.current().nextDouble(100) >= s.num("chance")) continue;
            String id = e.itemId, label = abilityName(s, "Pet");
            int n = Math.max(1, e.amount);
            later(() -> {
                if (!p.isOnline() || !Sky.items().exists(id)) return;
                Sky.loot().give(p, id, n, LootSource.PET);
                com.skyclone.api.ui.Msg.send(p, "&6" + label + "&7: your pet found another " + itemName(id) + (n > 1 ? " &8x" + n : "") + "&7!");
            });
        }
    }

    /** Rat "Rodent Blessing": the blessed player's rare drops have a chance to reach the rat's owner too. */
    private void blessCopy(Player p, SkyEvents.ItemGain e) {
        RT r = rt(p);
        if (r.blessBy == null || System.currentTimeMillis() >= r.blessUntil || r.blessBy.equals(p.getUniqueId())) return;
        if (e.source != LootSource.MOB_DROP && e.source != LootSource.SLAYER && e.source != LootSource.DUNGEON) return;
        ItemDef d = Sky.items().def(e.itemId);
        if (d == null || d.rarity == null || d.rarity.ordinal() < com.skyclone.api.item.Rarity.RARE.ordinal()) return;   // TUNE: rare drops only
        if (ThreadLocalRandom.current().nextDouble(100) >= r.blessCopy) return;
        Player owner = Bukkit.getPlayer(r.blessBy);
        if (owner == null || !owner.isOnline() || !Sky.profiles().isReady(owner)) return;
        String id = e.itemId;
        int n = Math.max(1, e.amount);
        later(() -> {
            if (!owner.isOnline()) return;
            Sky.loot().give(owner, id, n, LootSource.PET);
            com.skyclone.api.ui.Msg.send(owner, "&eRodent Blessing&7: " + p.getName() + "'s " + itemName(id) + " &7came to you too!");
        });
    }

    /** Bless a player (Rat): +mf Magic Find for ms; their rare drops have copy% to also reach {@code by}. */
    void bless(Player target, double mf, long ms, UUID by, double copyPct) {
        RT r = rt(target);
        r.blessUntil = System.currentTimeMillis() + ms;
        r.blessMf = mf;
        r.blessBy = by;
        r.blessCopy = copyPct;
        Sky.stats().invalidate(target);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (target.isOnline()) Sky.stats().invalidate(target); }, ms / 50 + 2);
    }

    /** {@code kill_bonus}: a bonus roll at (the owner's dial) x v% for a special outcome: an item or a mob. */
    private void killBonus(Player p, Src s, SkyEvents.MobKill e) {
        if (e.def == null || e.mob == null) return;
        String m = PetService.str(s.fx(), "match");
        if (m != null && !matchesAny(m, e.mob, e.def)) return;
        String not = PetService.str(s.fx(), "not_match");
        if (not != null && matchesAny(not, e.mob, e.def)) return;
        if (e.def.has("boss")) return;
        double chance = PetMath.bonusChance(foreignDial(PetService.str(s.fx(), "dial"), s.num("base", 0)), s.num("v"));
        if (chance <= 0 || ThreadLocalRandom.current().nextDouble() >= chance) return;
        String msg = PetService.str(s.fx(), "msg"), item = PetService.str(s.fx(), "item"), mob = PetService.str(s.fx(), "spawn");
        Location at = (e.location == null ? p.getLocation() : e.location).clone().add(0, 0.5, 0);
        later(() -> {
            if (!p.isOnline()) return;
            if (item != null && Sky.items().exists(item)) Sky.loot().give(p, item, 1, LootSource.MOB_DROP);
            if (mob != null && Sky.mobs().def(mob) != null && at.getWorld() == p.getWorld()) Sky.mobs().spawn(mob, at, p);
            if (msg != null) com.skyclone.api.ui.Msg.send(p, msg);
            com.skyclone.api.ui.Msg.sound(p, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f);
        });
    }

    /** {@code echo_mob}: the sea creature you hooked brings a second one of its kind. */
    private void echoMob(Player p, String mobId, Src s) {
        MobDef md = Sky.mobs().def(mobId);
        if (md == null) return;
        String label = abilityName(s, "Pet");
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            List<LivingEntity> hooked = hookedSeaCreatures(p, mobId);
            Location at = hooked.isEmpty() ? p.getLocation().clone().add(p.getLocation().getDirection().setY(0).normalize().multiply(3))
                    : hooked.get(0).getLocation().clone().add(ThreadLocalRandom.current().nextDouble(-1, 1), 0.2, ThreadLocalRandom.current().nextDouble(-1, 1));
            if (at.getWorld() != p.getWorld()) return;
            Sky.mobs().spawn(mobId, at, p);
            com.skyclone.api.ui.Msg.send(p, "&3" + label + "&7: a second " + md.name + " &7took the bait!");
        }, 3L);
    }

    /** Roll one entry of a treasure table carried in the effect ({@code key}: "table" / "great" / "outstanding"). */
    private void rollTreasure(Player p, Src s, String key, String quality) {
        JsonElement t = s.fx().get(key);
        if (t == null || !t.isJsonArray() || t.getAsJsonArray().isEmpty()) return;
        List<JsonObject> rows = new ArrayList<>();
        for (JsonElement x : t.getAsJsonArray()) {
            JsonObject o = x.getAsJsonObject();
            String item = PetService.str(o, "item");
            if ("COINS".equals(item) || (item != null && Sky.items().exists(item))) rows.add(o);
        }
        JsonObject pick = PetMath.pick(rows, o -> o.has("weight") ? o.get("weight").getAsDouble() : 1, ThreadLocalRandom.current().nextDouble());
        if (pick == null) return;
        int lo = pick.has("min") ? pick.get("min").getAsInt() : 1, hi = pick.has("max") ? pick.get("max").getAsInt() : lo;
        int n = lo + (hi > lo ? ThreadLocalRandom.current().nextInt(hi - lo + 1) : 0);
        String item = PetService.str(pick, "item");
        String label = abilityName(s, "Pet");
        String head = "outstanding".equals(quality) ? "&d&lOUTSTANDING CATCH!" : "great".equals(quality) ? "&9&lGREAT CATCH!" : "&6&lGOOD CATCH!";
        later(() -> {
            if (!p.isOnline()) return;
            String what;
            if ("COINS".equals(item)) {
                Sky.economy().deposit(p, n, "pets: " + label);
                what = "&6" + com.skyclone.api.ui.Text.commasInt(n) + " Coins";
            } else {
                Sky.loot().give(p, item, n, LootSource.FISHING);
                what = itemName(item) + (n > 1 ? " &8x" + n : "");
            }
            com.skyclone.api.ui.Msg.send(p, head + " &8(" + label + ") &7You found " + what + "&7.");
        });
    }

    /** Frog "Pond Skimmer": a plain catch on a foraging island has (hunting's water-shard chance x v%) to bring a shard. */
    private void fishShard(Player p, Src s, SkyEvents.FishCaught e) {
        if (e.seaCreature || e.treasure || e.lava || !Sky.has(Features.Hunting.class)) return;
        String island = PetService.island(p);
        JsonElement m = s.fx().get("shards");
        if (island == null || m == null || !m.isJsonObject() || !m.getAsJsonObject().has(island)) return;
        var arr = m.getAsJsonObject().getAsJsonArray(island);
        if (arr == null || arr.isEmpty()) return;
        double chance = PetMath.bonusChance(foreignDial(PetService.str(s.fx(), "dial"), s.num("base", 0.01)), s.num("v"));
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;
        String shard = arr.get(ThreadLocalRandom.current().nextInt(arr.size())).getAsString();
        later(() -> { if (p.isOnline()) Sky.service(Features.Hunting.class).addShards(p, shard, 1, "pet:" + abilityName(s, "frog")); });
    }

    /** Bee "Pollinator": crop-specific fortune, paid as the extra drops that much more fortune would have given. */
    private void cropFortune(Player p, Src s, SkyEvents.Gather e) {
        if (e.kind != LootSource.FARMING || e.what == null || !when(p, s.fx())) return;
        String up = e.what.toUpperCase();
        if (!containsKeyword(s.fx(), "crops", up) || !Sky.items().exists(e.what)) return;
        double fortune = Sky.stats().get(p, Stat.FARMING_FORTUNE);
        int n = PetMath.payout(PetMath.extraFromFortune(e.amount, s.num("v"), fortune), ThreadLocalRandom.current().nextDouble());
        if (n <= 0) return;
        String id = e.what;
        later(() -> { if (p.isOnline()) Sky.loot().give(p, id, n, LootSource.PET); });
    }

    /** Armadillo "Rolling Start": every {@code every} s the next Gemstone mined drops double. */
    private void gemDouble(Player p, Src s, SkyEvents.Gather e) {
        if (e.kind != LootSource.MINING || e.what == null || e.amount <= 0) return;
        String up = e.what.toUpperCase();
        if (!up.contains("_GEM") || !Sky.items().exists(e.what)) return;
        RT r = rt(p);
        long now = System.currentTimeMillis();
        if (now < r.gemReadyAt) return;
        r.gemReadyAt = now + (long) (Math.max(1, s.num("every", 60)) * 1000);
        String id = e.what;
        int n = e.amount;
        String label = abilityName(s, "Rolling Start");
        later(() -> {
            if (!p.isOnline()) return;
            Sky.loot().give(p, id, n, LootSource.PET);
            p.sendActionBar(com.skyclone.api.ui.Text.c("&6" + label + "&7: double gemstones! &8(+" + n + ")"));
        });
    }

    /** Sloth "Slow Start": the buff is up while ready; a chop uses it and starts the 60 s wait again. */
    private void chopUsed(Player p, Src s, SkyEvents.Gather e) {
        if (e.kind != LootSource.FORAGING) return;
        RT r = rt(p);
        long now = System.currentTimeMillis();
        if (now < r.chopReadyAt) return;
        r.chopReadyAt = now + (long) (Math.max(1, s.num("every", 60)) * 1000);
        r.chopFlagged = false;
        later(() -> { if (p.isOnline()) Sky.stats().invalidate(p); });
    }

    /** Wisp pillar heal: pct% of max Health spread over {@code secs} seconds (half-second steps). */
    private void healOver(Player p, double pct, int secs) {
        int steps = Math.max(1, secs * 2);
        final int[] left = {steps};
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), task -> {
            if (!p.isOnline() || left[0]-- <= 0) { task.cancel(); return; }
            double max = Sky.vitals().maxHealth(p);
            if (max > 0) Sky.vitals().heal(p, max * pct / 100.0 / steps);
            p.getWorld().spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 1, 0), 6, 0.3, 0.5, 0.3, 0.01);
        }, 0L, 10L);
    }

    /** The held weapon carries the Coldfused reforge (reforges hand-off: its fire-pillar half belongs to the Wisp pets). */
    private static boolean coldfused(Player p) {
        try {
            ItemStack h = p.getInventory().getItemInMainHand();
            if (Sky.items().idOf(h) == null) return false;
            var d = Sky.items().data(h);
            return d != null && d.reforge != null && d.reforge.equalsIgnoreCase("coldfused");
        } catch (RuntimeException e) { return false; }
    }

    /** Tiger "Bleed": this mob's healing is cut by pct% for dur seconds. */
    private void hemorrhage(LivingEntity victim, double pct, double dur) {
        if (victim == null || pct <= 0 || !Sky.mobs().isSkyMob(victim)) return;
        Hemo h = bleeding.computeIfAbsent(victim.getUniqueId(), k -> new Hemo(victim));
        h.pct = Math.max(h.pct, Math.min(100, pct));
        h.until = System.currentTimeMillis() + (long) (dur * 1000);
        if (h.lastHp <= 0) h.lastHp = Sky.mobs().health(victim);
    }

    /** Every heartbeat: a bleeding mob that gained health keeps only part of the gain. */
    private void bleedTick(long now) {
        for (var it = bleeding.values().iterator(); it.hasNext(); ) {
            Hemo h = it.next();
            if (!h.mob.isValid() || now > h.until) { it.remove(); continue; }
            double hp = Sky.mobs().health(h.mob);
            if (hp > h.lastHp + 1e-6) {
                double fixed = PetMath.bleed(h.lastHp, hp, h.pct);
                Sky.mobs().setHealth(h.mob, fixed);
                hp = fixed;
            }
            h.lastHp = hp;
        }
    }

    // ---- heartbeat (every 10 ticks) -------------------------------------------------------------

    private int beat;

    private void heartbeat() {
        beat++;
        long now = System.currentTimeMillis();
        if (!bleeding.isEmpty()) bleedTick(now);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            RT r = rt(p);
            Location loc = p.getLocation();
            if (r.lastLoc == null || r.lastLoc.getWorld() != loc.getWorld() || r.lastLoc.distanceSquared(loc) > 0.01) r.stillSince = 0;
            else if (r.stillSince == 0) r.stillSince = now;
            r.lastLoc = loc.clone();
            boolean flight = false, kb = false;
            for (Src s : sources(p)) {
                switch (s.t()) {
                    case "potion" -> { if (beat % 4 == 0 && when(p, s.fx())) potion(p, s); }
                    case "flight" -> { if (contains(s.fx(), "islands", String.valueOf(PetService.island(p)))) flight = true; }
                    case "kb_immune" -> kb = true;
                    case "zap" -> {
                        if (now - r.lastZap >= s.num("every", 3) * 1000 && inCombat(r, now)) zap(p, s, r, now);
                    }
                    case "skel_arrow" -> {
                        if (now >= r.lastSkelShot) {
                            LivingEntity t = nearestMob(p, s.num("radius", 5));
                            if (t != null) {
                                r.lastSkelShot = now + (long) (s.num("cd", 5) * 1000);
                                p.getWorld().spawnParticle(Particle.CRIT, t.getLocation().add(0, 1, 0), 12, 0.3, 0.3, 0.3, 0.1);
                                Sky.combat().rawDamage(t, s.num("v", 30) * Sky.stats().get(p, Stat.CRIT_DAMAGE), p, DamageContext.Kind.PROJECTILE);
                            }
                        }
                    }
                    case "overheal" -> {
                        if (!r.overhealGiven && now - r.lastDamaged >= s.num("idle", 10) * 1000) {
                            r.overhealGiven = true;
                            double shield = Sky.vitals().maxHealth(p) * s.num("v") / 100.0;
                            if (Sky.vitals().absorption(p) < shield) Sky.vitals().addAbsorption(p, shield - Sky.vitals().absorption(p));
                        }
                    }
                    case "pool" -> { if (when(p, s.fx())) pool(p, s, r, now); }
                    case "chop_ready" -> {   // Sloth "Slow Start" came back: recompute so the next chop gets it
                        if (now >= r.chopReadyAt && !r.chopFlagged) {
                            r.chopFlagged = true;
                            Sky.stats().invalidate(p);
                            p.sendActionBar(com.skyclone.api.ui.Text.c("&2" + (s.a() == null ? "Slow Start" : s.a().name) + " &7is ready for your next chop!"));
                        }
                    }
                    case "blizzard" -> {
                        if (beat % 2 == 0) {
                            double rad = s.num("radius", 16);
                            for (Entity en : p.getNearbyEntities(rad, rad, rad)) {
                                if (en instanceof LivingEntity le && !(en instanceof Player) && Sky.mobs().isSkyMob(le))
                                    le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 0, false, false));
                            }
                        }
                    }
                    default -> { }
                }
            }
            if (flight && !r.flightGranted && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)) {
                p.setAllowFlight(true);
                r.flightGranted = true;
            } else if (!flight && r.flightGranted) {
                disableFlight(p);
                r.flightGranted = false;
            }
            if (kb != r.kbApplied) {
                if (kb) addKb(p); else removeKb(p);
                r.kbApplied = kb;
            }
            if ((r.comboUntil > 0 && now > r.comboUntil) || (!r.killStacks.isEmpty() && r.killStacks.peekFirst() < now)) {
                if (now > r.comboUntil) { r.combo = 0; r.comboUntil = 0; }
                Sky.stats().invalidate(p);
            }
        }
    }

    /**
     * Wisp "Cool Spring": while in combat (on the Crimson Isle, via the effect's condition) a healing
     * pool appears at your feet every {@code every} seconds and lasts as long. Stepping into it heals
     * {@code heal}% of max Health at once, then {@code regen}%/s for {@code dur} seconds. One bath per pool.
     * Runs on the 10-tick heartbeat, so the regen is paid in half-second steps.
     */
    private void pool(Player p, Src s, RT r, long now) {
        double max = Sky.vitals().maxHealth(p);
        if (r.bathUntil > now && max > 0) Sky.vitals().heal(p, max * r.bathRegen / 100.0 / 2.0);
        if (now >= r.nextPool && inCombat(r, now)) {
            long every = (long) (s.num("every", 8) * 1000);
            r.poolAt = p.getLocation().clone();
            r.poolUntil = now + every;
            r.nextPool = now + every;
        }
        if (r.poolAt == null || now >= r.poolUntil || r.poolAt.getWorld() != p.getWorld()) return;
        if (beat % 2 == 0) {
            for (int i = 0; i < 12; i++) {
                double a = Math.PI * 2 * i / 12;
                p.getWorld().spawnParticle(Particle.SPLASH, r.poolAt.clone().add(Math.cos(a) * 1.6, 0.15, Math.sin(a) * 1.6), 2, 0.05, 0, 0.05, 0);
            }
            p.getWorld().spawnParticle(Particle.DRIPPING_WATER, r.poolAt.clone().add(0, 0.6, 0), 6, 0.7, 0.1, 0.7, 0);
        }
        if (p.getLocation().distanceSquared(r.poolAt) <= 2.0 * 2.0) {
            if (max > 0) Sky.vitals().heal(p, max * s.num("heal", 15) / 100.0);
            r.bathRegen = s.num("regen", 4);
            r.bathUntil = now + (long) (s.num("dur", 8) * 1000);
            r.poolUntil = 0;   // bathed: the pool is used up
            p.getWorld().spawnParticle(Particle.SPLASH, p.getLocation().add(0, 0.5, 0), 40, 0.5, 0.4, 0.5, 0.2);
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_SPLASH, 0.6f, 1.4f);
        }
    }

    /** Ember Ward steps earned by a kill count: one per {@code every} kills, at most {@code max}. */
    static int bulwarkSteps(Src s, int kills) {
        int every = (int) Math.max(1, s.num("every", 100));
        return (int) Math.min(s.num("max", 10), kills / every);
    }

    private static boolean inCombat(RT r, long now) {
        if (now - r.lastDamaged < 15_000 || now - r.lastHit < 15_000) return true;
        for (long t : r.attackedBy.values()) if (now - t < 15_000) return true;
        return false;
    }

    private void zap(Player p, Src s, RT r, long now) {
        LivingEntity t = nearestMob(p, s.num("radius", 8));
        if (t == null) return;
        r.lastZap = now;
        double dmg = s.num("v") * Math.max(0, Sky.stats().get(p, Stat.INTELLIGENCE));
        if (dmg <= 0) return;
        Location a = p.getEyeLocation(), b = t.getLocation().add(0, 1, 0);
        Vector step = b.toVector().subtract(a.toVector()).multiply(0.1);
        Location cur = a.clone();
        for (int i = 0; i < 10; i++) { cur.add(step); p.getWorld().spawnParticle(Particle.END_ROD, cur, 1, 0, 0, 0, 0); }
        Sky.combat().rawDamage(t, dmg, p, DamageContext.Kind.MAGIC);
    }

    private static LivingEntity nearestMob(Player p, double radius) {
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity en : p.getNearbyEntities(radius, radius, radius)) {
            if (!(en instanceof LivingEntity le) || en instanceof Player || !Sky.mobs().isSkyMob(le)) continue;
            if (Sky.mobs().defOf(le) != null && Sky.mobs().defOf(le).has("passive")) continue;
            java.util.UUID owner = Sky.mobs().owner(le);
            if (owner != null && !owner.equals(p.getUniqueId())) continue;
            double d = en.getLocation().distanceSquared(p.getLocation());
            if (d < bd) { bd = d; best = le; }
        }
        return best;
    }

    private static void potion(Player p, Src s) {
        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(PetService.str(s.fx(), "effect").toLowerCase()));
        if (type == null) return;
        int amp = (int) Math.max(0, s.fx().has("amp") ? num(s, s.fx().get("amp")) : 0);
        int dur = type == PotionEffectType.NIGHT_VISION ? 400 : 100;
        p.addPotionEffect(new PotionEffect(type, dur, amp, true, false, false));
    }

    private static void disableFlight(Player p) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        p.setFlying(false);
        p.setAllowFlight(false);
    }

    private void addKb(Player p) {
        try {
            AttributeInstance a = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (a == null) return;
            a.removeModifier(kbKey);
            a.addModifier(new AttributeModifier(kbKey, 1.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        } catch (RuntimeException ignored) { }
    }

    private void removeKb(Player p) {
        try {
            AttributeInstance a = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (a != null && kbKey != null) a.removeModifier(kbKey);
        } catch (RuntimeException ignored) { }
    }
}
