package com.skyclone.mod.weapons;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.combat.DamageContext.Kind;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Every weapon passive: damage modifiers (outgoing at priority 150, between enchants and sets;
 * incoming for "take X% less" lines), the held-weapon StatProvider, per-item counters (kills) and
 * their lore, cleave, heal-on-hit, kill rewards, and the wand/ward damage reshaping.
 */
final class Passives implements Listener {

    private final Traits traits;
    private final Buffs buffs;
    private final Bows bows;
    private List<String> woodCollections = List.of();
    private List<String> combatCollections = List.of();
    private List<String> miningIslands = List.of();
    private boolean cleaving;
    private final Map<UUID, Long> lastHurt = new HashMap<>();
    private final Map<UUID, Long> greedCurse = new HashMap<>();
    /** Zombie Commander Whip: kills of its mob type during the current dungeon run (cleared on leaving the dungeon). */
    private final Map<UUID, Integer> whipKills = new HashMap<>();
    /** Per player: the last (inDungeon, class, whip kills) signature a dungeon-gear stat was computed from. */
    private final Map<UUID, String> dungeonSig = new HashMap<>();
    /** Set by the module after construction (Labyrinth Blade's bestiary tiers). */
    TargetBonuses targets;

    Passives(Traits traits, Buffs buffs, Bows bows) {
        this.traits = traits;
        this.buffs = buffs;
        this.bows = bows;
    }

    void meta(List<String> wood, List<String> combat, List<String> mining) {
        woodCollections = wood;
        combatCollections = combat;
        miningIslands = mining;
    }

    List<String> combatCollections() { return combatCollections; }

    void register() {
        Sky.combat().addModifier("weapons_out", 150, this::outgoing);
        Sky.combat().addModifier("weapons_in", 150, this::incoming);
        Sky.stats().addProvider("weapons_held", this::heldStats);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::refreshPetCopies, 40L, 10L);
        Sky.items().addStatContributor("weapons", this::itemStats);
        Sky.items().addLoreContributor("weapons", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(ItemDef def, ItemData data) { return lore(def, data); }
        });
    }

    // ---- per-item counters ---------------------------------------------------------------------

    static long kills(ItemStack s) {
        if (s == null || s.getType().isAir()) return 0;
        try {
            ItemData d = Sky.items().data(s);
            JsonElement k = d.extra.get("kills");
            return k == null ? 0 : k.getAsLong();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static double paid(ItemData d) {
        // the mayors module's Dark Auction writes the reference key "winning_bid"; "paid" is our admin/legacy key
        JsonElement e = d == null ? null : d.extra.has("winning_bid") ? d.extra.get("winning_bid") : d.extra.get("paid");
        try { return e == null ? 0 : e.getAsDouble(); } catch (RuntimeException ex) { return 0; }
    }

    private static long kills(ItemData d) {
        JsonElement e = d == null ? null : d.extra.get("kills");
        try { return e == null ? 0 : e.getAsLong(); } catch (RuntimeException ex) { return 0; }
    }

    // ---- item stats (pure: def + instance data) --------------------------------------------------

    private void itemStats(ItemDef def, ItemData data, Rarity rarity, ItemStatSheet sheet) {
        if (!Traits.isOurs(def)) return;
        Trait t = traits.of(def.id);
        if ("sword".equals(t.greed)) {
            double b = Math.floor(WeaponMath.midasSwordBonus(paid(data)));
            if (b > 0) {
                sheet.addDamage(b, true);
                sheet.add(Stat.STRENGTH, b, ItemStatSheet.Source.OTHER);
            }
        }
        if (t.raider) {
            int b = WeaponMath.raiderDamage(kills(data));
            if (b > 0) sheet.addDamage(b, true);
        }
        if (t.killsDamage != null) {
            int b = WeaponMath.killsDamage(kills(data), t.killsDamage.per, t.killsDamage.max);
            if (b > 0) sheet.addDamage(b, true);
        }
        if (t.tiered != null) {
            int tier = itemTier(data);
            if (tier > 0) {
                for (Map.Entry<String, double[]> e : t.tiered.entrySet()) {
                    double add = WeaponMath.tierDelta(e.getValue(), tier);
                    if (add == 0) continue;
                    if ("DAMAGE".equals(e.getKey())) { sheet.addDamage(add, false); continue; }
                    Stat s = Stat.parse(e.getKey());
                    if (s == null) { try { s = Stat.valueOf(e.getKey()); } catch (IllegalArgumentException ignored) { } }
                    if (s != null) sheet.add(s, add, ItemStatSheet.Source.BASE);
                }
            }
        }
    }

    /** The reference key the dungeons module stamps on a floor drop: which roll of {@code tiered_stats} it carries. */
    static final String ITEM_TIER = "item_tier";

    static int itemTier(ItemData d) {
        JsonElement e = d == null || d.extra == null ? null : d.extra.get(ITEM_TIER);
        try { return e == null ? 0 : Math.max(0, e.getAsInt()); } catch (RuntimeException ex) { return 0; }
    }

    private List<String> lore(ItemDef def, ItemData data) {
        if (!Traits.isOurs(def)) return List.of();
        Trait t = traits.of(def.id);
        List<String> out = new ArrayList<>();
        if (t.tracksKills()) out.add("&7Kills: &a" + Text.commasInt(kills(data)));
        if (t.hurricane) {
            long k = kills(data);
            long next = WeaponMath.hurricaneNext(k);
            int arrows = WeaponMath.hurricaneArrows(k);
            out.add("&7Arrows per shot: &a" + arrows + (next > 0 ? " &8(next at " + next + " kills)" : " &8(max)"));
        }
        if (t.killsDamage != null) out.add("&7Bonus Damage: &c+" + WeaponMath.killsDamage(kills(data), t.killsDamage.per, t.killsDamage.max));
        if (t.greed != null) {
            double paid = paid(data);
            out.add("&7Price paid: &6" + (paid > 0 ? Text.commasInt(paid) + " coins" : "&8unknown"));
            if ("staff".equals(t.greed)) out.add("&7Bonus ability damage: &c+" + Text.commasInt(WeaponMath.midasStaffBonus(paid)));
        }
        JsonElement att = data == null ? null : data.extra.get("attunement");
        if (att == null && def.extra.has("attunement")) att = def.extra.get("attunement");
        if (att != null) out.add("&7Attuned: &6" + Text.titleCase(att.getAsString()));
        out.addAll(Teleporters.lore(def, data));
        out.addAll(Souls.lore(t.souls, data));
        if (t.tiered != null) {
            int tier = itemTier(data);
            out.add(tier > 0 ? "&7Floor Roll: &a" + WeaponMath.floorName(tier) : "&8Floor Roll: lowest");
        }
        return out;
    }

    // ---- Labyrinth Blade: copy the active pet's stats --------------------------------------------------

    /** Player -> the pet's flat stats, read off the stat engine's "pets" provider twice a second. */
    private final Map<UUID, Map<Stat, Double>> petCopies = new HashMap<>();

    /**
     * Research 06: the Daedalus Blade copies the stats of your active pet. Reading the pets provider from
     * INSIDE our own StatProvider could recurse into a stat compute, so the copy is taken here, outside any
     * compute, and the provider only reads this cache; a change invalidates the player's stats.
     */
    private void refreshPetCopies() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            try { refreshDungeonGear(p); } catch (RuntimeException ignored) { }
            String held = Traits.idOf(p.getInventory().getItemInMainHand());
            Map<Stat, Double> now = null;
            if (held != null && traits.owns(held) && traits.of(held).petCopy) {
                now = new java.util.EnumMap<>(Stat.class);
                for (Stat s : Stat.values()) {
                    Double v;
                    try { v = Sky.stats().breakdown(p, s).get("pets"); } catch (RuntimeException e) { v = null; }
                    if (v != null && v != 0) now.put(s, v);
                }
            }
            Map<Stat, Double> old = now == null ? petCopies.remove(id) : petCopies.put(id, now);
            if (old == null ? now != null && !now.isEmpty() : !old.equals(now)) Sky.stats().invalidate(p);
        }
    }

    int petCopyCount() { return petCopies.size(); }

    /**
     * Dungeon-only stats (Adaptive Blade class bonuses, Commander Whip kills) change when the player enters or leaves a
     * run or changes class, none of which fires an event we can hear. Twice a second, for players holding such a
     * weapon, compare a small signature and re-stat on change; leaving the dungeon also ends the whip's run.
     */
    private void refreshDungeonGear(Player p) {
        UUID id = p.getUniqueId();
        boolean in = inDungeon(p);
        if (!in && whipKills.remove(id) != null) Sky.stats().invalidate(p);
        Trait t = traits.held(p);
        if (!t.adaptive && t.whip == null) { dungeonSig.remove(id); return; }
        String sig = in + "|" + dungeonClass(p) + "|" + whipKills.getOrDefault(id, 0);
        String old = dungeonSig.put(id, sig);
        if (old != null && !old.equals(sig)) Sky.stats().invalidate(p);
    }

    static boolean inDungeon(Player p) {
        if (p == null || !Sky.has(Features.Dungeons.class)) return false;
        try { return Sky.service(Features.Dungeons.class).inDungeon(p); } catch (RuntimeException e) { return false; }
    }

    /** The selected dungeon class id (HEALER, MAGE, BERSERK, ARCHER, TANK) upper-cased, or null. */
    static String dungeonClass(Player p) {
        try {
            String c = Sky.service(Features.Dungeons.class).selectedClass(p);
            return c == null ? null : c.toUpperCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    int whipKills(Player p) { return whipKills.getOrDefault(p.getUniqueId(), 0); }

    void setWhipKills(Player p, int n) {
        if (n <= 0) whipKills.remove(p.getUniqueId()); else whipKills.put(p.getUniqueId(), n);
        Sky.stats().invalidate(p);
    }

    /**
     * The wearer's Fear (research 06), wave 5 = every source the reference has:
     * <ul>
     *   <li>the {@code FEAR} line ({@code ItemDef.extra.otherStats.FEAR}) of every worn armour piece and equipment piece
     *       (Great Spook helmet..boots and belt/cloak/necklace/gloves: 5 each, armor + accessories data);</li>
     *   <li>the best Great Spook accessory in the bag (Talisman 2 / Ring 4 / Artifact 6, {@code Features.Accessories.has});</li>
     *   <li>each worn piece reforged Greater Spook: the reforge's {@code otherStats.<RARITY>.fear} (reforges data:
     *       EPIC 4, LEGENDARY 5; 1 when the row is missing, the bonus text's "+1 Fear");</li>
     *   <li>the Dread potion ({@code great_spook} effect, +3);</li>
     *   <li>the Eerie pet's Fearcreasing (+a Fear per 10 Primal Fears slain, max 150 kills, counted in our player data).</li>
     * </ul>
     * Cached 1 s per player: it is read on every spooky hit and inside the held-stat compute.
     */
    static int fear(Player p) {
        if (p == null) return 0;
        long now = System.currentTimeMillis();
        long[] c = FEAR_CACHE.get(p.getUniqueId());
        if (c != null && now - c[1] < 1000) return (int) c[0];
        int gear = 0, reforged = 0, potion = 0;
        List<ItemStack> worn = new ArrayList<>(java.util.Arrays.asList(p.getInventory().getArmorContents()));
        try { worn.addAll(Sky.service(Features.Equipment.class).worn(p)); } catch (RuntimeException ignored) { }
        for (ItemStack s : worn) {
            if (s == null || s.getType().isAir()) continue;
            try {
                ItemDef def = Sky.items().def(Sky.items().idOf(s));
                gear += (int) Math.round(otherStat(def, "FEAR"));
                ItemData d = Sky.items().data(s);
                if (d != null && GREATER_SPOOK.equalsIgnoreCase(d.reforge)) reforged += greaterSpookFear(Sky.items().rarity(s));
            } catch (RuntimeException ignored) { }
        }
        try {
            Features.Accessories acc = Sky.service(Features.Accessories.class);
            gear += WeaponMath.bestOf(acc.has(p, "GREAT_SPOOK_ARTIFACT") ? accFear("GREAT_SPOOK_ARTIFACT", 6) : 0,
                    acc.has(p, "GREAT_SPOOK_RING") ? accFear("GREAT_SPOOK_RING", 4) : 0,
                    acc.has(p, "GREAT_SPOOK_TALISMAN") ? accFear("GREAT_SPOOK_TALISMAN", 2) : 0);
        } catch (RuntimeException ignored) { }
        try {
            Integer lvl = Sky.service(Features.Potions.class).active(p).get("great_spook");
            if (lvl != null && lvl > 0) potion = 3;   // alchemy data: Dread, params.fear = 3
        } catch (RuntimeException ignored) { }
        double perTen = WeaponDials.PET_PERKS.get() ? petValue(p, "eerie_fearcreasing", "a") : 0;
        WeaponData d = WeaponsModule.data(p);
        int f = WeaponMath.fearSum(gear, reforged, potion, d == null ? 0 : d.primalFears, perTen);
        FEAR_CACHE.put(p.getUniqueId(), new long[]{f, now});
        return f;
    }

    private static final Map<UUID, long[]> FEAR_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    static final String GREATER_SPOOK = "greater_spook";
    private static volatile Map<String, Double> greaterSpookTable;

    /** A number in {@code ItemDef.extra.otherStats} (armor / accessories write stats core has no Stat for there). */
    static double otherStat(ItemDef def, String key) {
        if (def == null || def.extra == null || !def.extra.has("otherStats")) return 0;
        try {
            JsonElement v = def.extra.getAsJsonObject("otherStats").get(key);
            return v == null ? 0 : v.getAsDouble();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int accFear(String id, int fallback) {
        double v = otherStat(Sky.items().def(id), "FEAR");
        return v > 0 ? (int) Math.round(v) : fallback;
    }

    /** Greater Spook's Fear at a rarity, read once (read-only) from the reforges module's data. */
    static int greaterSpookFear(Rarity r) {
        Map<String, Double> t = greaterSpookTable;
        if (t == null) {
            t = new HashMap<>();
            try (java.io.InputStream in = Sky.plugin().getResource("data/reforges/reforges.json")) {
                if (in != null) {
                    JsonElement root = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                    com.google.gson.JsonArray arr = root.isJsonArray() ? root.getAsJsonArray()
                            : root.getAsJsonObject().has("reforges") ? root.getAsJsonObject().getAsJsonArray("reforges") : new com.google.gson.JsonArray();
                    for (JsonElement e : arr) {
                        var o = e.getAsJsonObject();
                        if (!GREATER_SPOOK.equals(o.get("id").getAsString()) || !o.has("otherStats")) continue;
                        for (var en : o.getAsJsonObject("otherStats").entrySet()) {
                            var row = en.getValue().getAsJsonObject();
                            if (row.has("fear")) t.put(en.getKey().toUpperCase(java.util.Locale.ROOT), row.get("fear").getAsDouble());
                        }
                    }
                }
            } catch (Exception e) {
                Sky.log().warning("[weapons] could not read Greater Spook's Fear from the reforges data: " + e.getMessage());
            }
            greaterSpookTable = t;
        }
        Double v = r == null ? null : t.get(r.name());
        return v == null ? 1 : (int) Math.round(v);
    }

    static void forgetFear(UUID id) { FEAR_CACHE.remove(id); }

    /** A number behind the active pet's perk ({@code Features.Pets.abilityValue}); 0 without pets or the perk. */
    static double petValue(Player p, String key, String token) {
        try { return Sky.service(Features.Pets.class).abilityValue(p, key, token); } catch (RuntimeException e) { return 0; }
    }

    // ---- stats while holding one of our weapons ----------------------------------------------------

    private void heldStats(Player p, StatMap into) {
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Traits.idOf(held);
        if (id == null || !traits.owns(id)) return;
        ItemDef def = Sky.items().def(id);
        if (def == null || def.category == null || !(def.category.isWeapon() || def.category == ItemCategory.FISHING_WEAPON)) return;
        if (!Sky.items().meetsRequirements(p, held, false)) return;
        Trait t = traits.of(id);
        if (t.raider) {
            long wood = 0;
            Features.Collections col = Sky.service(Features.Collections.class);
            for (String c : woodCollections) {
                try { wood += Math.max(0, col.count(p, c)); } catch (RuntimeException ignored) { }
            }
            into.add(Stat.STRENGTH, WeaponMath.raiderStrength(wood));
        }
        if (t.voidSword > 0) {
            int pieces = 0;
            for (ItemStack a : p.getInventory().getArmorContents()) {
                String aid = Traits.idOf(a);
                if (aid != null && aid.startsWith("END_") && (aid.endsWith("HELMET") || aid.endsWith("CHESTPLATE")
                        || aid.endsWith("LEGGINGS") || aid.endsWith("BOOTS"))) pieces++;
            }
            into.add(Stat.STRENGTH, t.voidSword * pieces);
        }
        if (t.poisonStr > 0 && p.hasPotionEffect(PotionEffectType.POISON)) into.add(Stat.STRENGTH, t.poisonStr);
        if (t.cataStats != null) {
            int lvl = 0;
            try { lvl = Sky.service(Features.Dungeons.class).catacombsLevel(p); } catch (RuntimeException ignored) { }
            if (lvl > 0) {
                for (Map.Entry<String, Double> e : t.cataStats.entrySet()) {
                    if (e.getKey().equals("DAMAGE")) continue;
                    Stat s = Stat.parse(e.getKey());
                    if (s != null) into.add(s, e.getValue() * lvl);
                }
            }
        }
        if (t.fear) into.add(Stat.STRENGTH, fear(p));
        if (t.setStr != null) {
            String set = Sky.items().fullSet(p);
            Double str = set == null ? null : t.setStr.get(set);
            if (str != null) into.add(Stat.STRENGTH, str);
        }
        if (t.whip != null && inDungeon(p)) {
            Integer n = whipKills.get(p.getUniqueId());
            if (n != null && n > 0) into.add(Stat.STRENGTH, n);   // research 06: +1 Strength per zombie this run
        }
        if (t.adaptive && inDungeon(p)) {
            String cls = dungeonClass(p);
            if (cls != null) switch (cls) {   // research 06 (Adaptive Blade lore)
                case "BERSERK" -> { into.add(Stat.STRENGTH, 75); into.add(Stat.SPEED, 10); }
                case "HEALER" -> into.add(Stat.MENDING, 10);
                case "MAGE" -> into.add(Stat.INTELLIGENCE, 100);
                case "TANK" -> { into.add(Stat.DEFENSE, 100); into.add(Stat.VITALITY, 5); into.add(Stat.TRUE_DEFENSE, 5); }
                default -> { }
            }
        }
        if (t.petCopy) {
            Map<Stat, Double> pet = petCopies.get(p.getUniqueId());
            if (pet != null) pet.forEach(into::add);
        }
        double rag = Abilities.DoomcallHolder.get(p);
        if (rag > 0) {
            Double str = def.stats.get(Stat.STRENGTH);
            if (str != null) into.add(Stat.STRENGTH, str * (rag - 1));
        }
    }

    // ---- outgoing damage -------------------------------------------------------------------------

    private void outgoing(DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null || ctx.victim == null) return;
        LivingEntity v = ctx.victim;
        Kind k = ctx.kind;
        // accessories request 14: hits made inside a health-costing ability carry "health_cost" (Ancient Cloak reads it)
        if (Fx.healthCost > 0) ctx.tags.add("health_cost");
        if (Fx.explosion > 0) ctx.tags.add("explosion");   // Implosion Belt can match explosion hits (accessories request)
        // Eerie pet "Fearama" (research 06): +a% damage to spooky mobs per Fear, whatever deals it
        if ((k == Kind.MELEE || k == Kind.PROJECTILE || k == Kind.MAGIC) && WeaponDials.PET_PERKS.get() && MobTypes.is("spooky", v)) {
            double per = petValue(p, "eerie_fearama", "a");
            if (per > 0) ctx.additive += WeaponMath.fearama(fear(p), per);
        }
        // Adaptive Blade (Archer): a marked foe takes more from this player's arrows, whatever bow fired them
        if (k == Kind.PROJECTILE) {
            Double am = buffs.markValue(v, "adaptive_archer:" + p.getUniqueId());
            if (am != null) ctx.multiplicative *= am;
        }
        // marks on the victim count for every kind of player damage
        Double tw = buffs.markValue(v, "twilight");
        if (tw != null) ctx.multiplicative *= tw;
        if (buffs.markValue(v, "frozen_amp") != null) ctx.multiplicative *= 1.10;   // Frost Breath: +10% while frozen
        if (k == Kind.MAGIC) { magic(ctx, p); return; }
        if (k != Kind.MELEE && k != Kind.PROJECTILE) return;
        if (buffs.has(p, "veil")) { ctx.cancelled = true; return; }   // Ward Veil: cannot attack while it holds

        Bows.Shot s = k == Kind.PROJECTILE ? Bows.current : null;
        if (s != null && !p.getUniqueId().equals(s.shooter)) s = null;
        String wid = s != null && s.bowId != null ? s.bowId : Traits.idOf(ctx.weapon);
        ItemDef wdef = wid == null ? null : Sky.items().def(wid);
        if (k == Kind.MELEE && wdef != null && wdef.category == ItemCategory.BOW && !WeaponDials.BOW_MELEE.get()) ctx.base = 0;
        if (s != null && bows.auraImpact(s)) { ctx.cancelled = true; return; }
        Trait t = traits.of(wid);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // crits first: the Oblivion Shortbow re-rolls with crit chance / 4, then arrows may add theirs
        if (t.critDiv > 0) {
            double cc = WeaponMath.dividedCrit(Sky.stats().get(p, Stat.CRIT_CHANCE), t.critDiv);
            ctx.crit = rnd.nextDouble() * 100 < cc;
        }
        if (s != null) {
            ctx.flatBonus += s.arrowDamage;
            if (s.mult != 1.0) ctx.multiplicative *= s.mult;
            if (s.infusionMult != 1.0 && (s.infusionVs == null || MobTypes.is(s.infusionVs, v))) ctx.multiplicative *= s.infusionMult;
            if (s.arrowCrit > 0 && !ctx.crit) {
                double cc = Math.min(99.9, Sky.stats().get(p, Stat.CRIT_CHANCE));
                if (rnd.nextDouble() < s.arrowCrit / Math.max(1, 100 - cc)) ctx.crit = true;
            }
            if ("ARMORSHRED_ARROW".equals(s.arrowId)) ctx.multiplicative *= WeaponMath.defenseIgnoreMultiplier(defense(v), 5);
            if ("NANSORB_ARROW".equals(s.arrowId)) ctx.multiplicative *= WeaponMath.skewerMultiplier(s.victims - 1);
            if (s.forceCrit) ctx.crit = true;
        }
        if (t.alwaysCrit) ctx.crit = true;
        if (Traits.none(t) && s == null) { nextHitFlags(ctx, p, v); return; }

        if (t.vs != null) for (Map.Entry<String, Double> e : t.vs.entrySet()) if (MobTypes.is(e.getKey(), v)) ctx.additive += e.getValue();
        if (t.vsMult != null) for (Map.Entry<String, Double> e : t.vsMult.entrySet()) if (MobTypes.is(e.getKey(), v)) ctx.multiplicative *= e.getValue();
        if (t.melee != 0 && k == Kind.MELEE) ctx.additive += t.melee;
        if (t.mult > 0) ctx.multiplicative *= t.mult;
        if (t.water > 0 && p.isInWater()) ctx.additive += t.water;
        if (t.lavaStr > 0 && (v.isInLava() || v.getLocation().getBlock().getType() == Material.LAVA))
            ctx.multiplicative *= WeaponMath.strengthMultiplier(Sky.stats().get(p, Stat.STRENGTH), t.lavaStr);
        if (t.miningIslands > 0 && miningIslands.contains(island(p))) ctx.additive += t.miningIslands;
        if (t.spooky > 0 && MobTypes.is("spooky", v) && calendarActive(WeaponDials.SPOOKY_EVENT.get())) ctx.additive += t.spooky;
        if (t.dragonPct > 0 && MobTypes.is("dragon", v)) ctx.additive += t.dragonPct;
        if (t.shaman > 0) ctx.flatBonus += WeaponMath.perHealth(Sky.vitals().maxHealth(p), t.shaman);
        if (t.pooch > 0) {
            int lvl = 0;
            try { lvl = Sky.service(Features.Slayers.class).level(p, "wolf"); } catch (RuntimeException ignored) { }
            ctx.flatBonus += t.pooch * lvl;
            String set = Sky.items().fullSet(p);
            if (set != null && (set.contains("pack") || set.contains("mastiff")) && MobTypes.is("wolf", v)) ctx.additive += 100;
        }
        if (t.fear) ctx.flatBonus += fear(p);
        if (t.daedalus > 0) {
            int lvl = 0;
            try { lvl = Sky.service(SkillService.class).level(p, SkillType.TAMING); } catch (RuntimeException ignored) { }
            ctx.flatBonus += t.daedalus * lvl;
        }
        if (t.mythBestiary != null && targets != null && MobTypes.is("mythological", v))   // research 06: +1% per tier
            ctx.additive += WeaponMath.perTier(targets.mythTiers(p), t.mythBestiary.dmg);
        if (t.adaptive && inDungeon(p)) {
            String cls = dungeonClass(p);
            if ("BERSERK".equals(cls) || "MAGE".equals(cls)) ctx.flatBonus += 10;   // research 06: +10 Damage
        }
        if (t.tactician) {
            int n = 0;
            Features.Collections col = Sky.service(Features.Collections.class);
            for (String c : combatCollections) {
                try { if (col.tier(p, c) >= 7) n++; } catch (RuntimeException ignored) { }
            }
            ctx.flatBonus += WeaponMath.tacticianDamage(n);
        }
        if (t.emerald) {
            ctx.flatBonus += WeaponMath.emeraldBlade(Sky.economy().purse(p), WeaponDials.EMERALD_COEFF.get(), WeaponDials.EMERALD_CAP.get());
            greedCurse.put(p.getUniqueId(), Buffs.now() + 60_000);
        }
        if (t.missingVsUndead > 0 && MobTypes.is("undead", v))
            ctx.additive += WeaponMath.missingHealthBonus(Sky.vitals().health(p), Sky.vitals().maxHealth(p), t.missingVsUndead);
        if (t.missingSelf > 0 && MobTypes.is("undead", v))
            ctx.additive += WeaponMath.missingHealthBonus(Sky.vitals().health(p), Sky.vitals().maxHealth(p), t.missingSelf);
        if (t.targetMissing > 0 && Fx.target(v))
            ctx.additive += WeaponMath.missingHealthBonus(Sky.mobs().health(v), Sky.mobs().maxHealth(v), t.targetMissing);
        if (t.defShred != null) {
            Double stacks = buffs.markValue(v, "shred:" + p.getUniqueId());
            if (stacks != null) ctx.multiplicative *= WeaponMath.defenseIgnoreMultiplier(defense(v), t.defShred.pct * stacks);
        }
        if (t.cataStats != null && t.cataStats.containsKey("DAMAGE")) {
            int lvl = 0;
            try { lvl = Sky.service(Features.Dungeons.class).catacombsLevel(p); } catch (RuntimeException ignored) { }
            ctx.flatBonus += t.cataStats.get("DAMAGE") * lvl;
        }
        if ("LIVID_DAGGER".equals(wid) && ctx.crit && behind(p, v)) ctx.additive += 100;   // research 06: crits from behind +100%
        nextHitFlags(ctx, p, v);
    }

    private void nextHitFlags(DamageContext ctx, Player p, LivingEntity v) {
        Double focus = buffs.consume(p, "focus_next");
        if (focus != null) ctx.additive += focus;
        Double smash = buffs.consume(p, "smash");
        if (smash != null) {
            ctx.multiplicative *= 2;   // research 06: Big Bonk doubles the next hit
            if (MobTypes.is("animal", v)) buffs.mark(v, "weaken", 0.65, 30);
        }
    }

    private void magic(DamageContext ctx, Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        Trait t = traits.of(Traits.idOf(held));
        if ("staff".equals(t.greed)) {
            try { ctx.flatBonus += WeaponMath.midasStaffBonus(paid(Sky.items().data(held))); } catch (RuntimeException ignored) { }
        }
    }

    private static double defense(LivingEntity v) {
        try {
            MobDef d = Sky.mobs().defOf(v);
            return d == null ? 0 : d.defense;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean behind(Player p, LivingEntity v) {
        org.bukkit.util.Vector facing = v.getLocation().getDirection().setY(0);
        org.bukkit.util.Vector toP = p.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
        if (facing.lengthSquared() < 1e-4 || toP.lengthSquared() < 1e-4) return false;
        return facing.normalize().dot(toP.normalize()) < -0.3;
    }

    private static String island(Player p) {
        try { return Sky.worlds().islandOf(p.getWorld()); } catch (RuntimeException e) { return null; }
    }

    private static boolean calendarActive(String id) {
        try { return id != null && !id.isEmpty() && Sky.calendar().active(id); } catch (RuntimeException e) { return false; }
    }

    // ---- incoming damage -------------------------------------------------------------------------

    private void incoming(DamageContext ctx) {
        Player p = ctx.playerVictim();
        if (p == null || ctx.attacker == null || ctx.attacker instanceof Player) return;
        LivingEntity mob = ctx.attacker;
        Trait t = traits.held(p);
        if (t.lessFrom != null) {
            for (Map.Entry<String, Double> e : t.lessFrom.entrySet()) {
                if (MobTypes.is(e.getKey(), mob)) ctx.multiplicative *= Math.max(0, 1 - e.getValue() / 100.0);
            }
        }
        if (buffs.markValue(mob, "enrage:" + p.getUniqueId()) != null) ctx.multiplicative *= 0.90;   // Provoke: -10%
        Double weak = buffs.markValue(mob, "weaken");
        if (weak != null) ctx.multiplicative *= weak;
        if (buffs.has(p, "hellstorm")) ctx.multiplicative *= 2.0;
        Double dr = buffs.flagValue(p, "focus_dr");
        if (dr != null) ctx.multiplicative *= Math.max(0, 1 - dr / 100.0);
    }

    /** Ward Veil and Cell Sync reshape the FINAL damage a player takes. Also feeds Doomcall's "not hit" check. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerHurt(SkyEvents.Damage e) {
        Player p = e.ctx.playerVictim();
        if (p == null || e.ctx.finalDamage <= 0) return;
        lastHurt.put(p.getUniqueId(), Buffs.now());
        Double layers = buffs.flagValue(p, "veil");
        if (layers != null && layers > 0) {
            int need = (int) Math.max(1, Math.ceil(e.ctx.finalDamage / 1000.0));   // research 06: 1,000 per layer
            double left = layers - need;
            e.ctx.finalDamage = 0;
            Fx.burst(p.getLocation().add(0, 1, 0), Particle.TOTEM_OF_UNDYING, 8, 0.4);
            if (left <= 0) {
                buffs.clear(p, "veil");
                com.skyclone.api.ui.Msg.send(p, "&cYour ward veil shattered!");
            } else {
                buffs.flag(p, "veil", left, Math.max(0.1, (buffs.flagValue(p, "veil_until") == null ? 0
                        : buffs.flagValue(p, "veil_until") - Buffs.now()) / 1000.0));
            }
            return;
        }
        if (buffs.has(p, "aligned") && !e.ctx.tags.contains("aligned")) {
            double share = Math.floor(e.ctx.finalDamage / 3.0);
            e.ctx.finalDamage = e.ctx.finalDamage - 2 * share;
            for (int i = 1; i <= 2; i++) {
                Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                    if (p.isOnline() && !p.isDead()) Sky.vitals().damage(p, share, "aligned");
                }, 20L * i);   // research 06: spread over 3s
            }
        }
    }

    long lastHurt(Player p) { return lastHurt.getOrDefault(p.getUniqueId(), 0L); }

    // ---- burning (Flaming Sword) ---------------------------------------------------------------------

    /** Victim -> {damage per burn tick, ticks left}. A fresh hit refreshes the burn; burns never stack. */
    private final Map<UUID, double[]> burning = new HashMap<>();

    /**
     * Core cancels vanilla fire damage on sky mobs, so the flames alone did nothing. The burn is ours:
     * {@code weapons.ignite-percent-per-second} of the igniting hit, once a second, as true damage
     * credited to the player (kills and drops are theirs), for the item's ignite seconds.
     */
    private void ignite(Player p, LivingEntity v, double hit, double seconds) {
        v.setFireTicks(Math.max(v.getFireTicks(), (int) (seconds * 20)));
        double[] b = WeaponMath.burn(hit, WeaponDials.FLAMING_DOT_PERCENT.get(), seconds);
        if (b[0] <= 0 || b[1] <= 0) return;
        UUID vid = v.getUniqueId();
        double[] cur = burning.get(vid);
        if (cur != null) { cur[0] = Math.max(cur[0], b[0]); cur[1] = b[1]; return; }
        burning.put(vid, b);
        UUID pid = p.getUniqueId();
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), task -> {
            double[] st = burning.get(vid);
            Player pl = Bukkit.getPlayer(pid);
            if (st == null || pl == null || !v.isValid() || v.isDead()) { burning.remove(vid); task.cancel(); return; }
            Sky.combat().rawDamage(v, st[0], pl, DamageContext.Kind.TRUE);
            if (--st[1] <= 0) { burning.remove(vid); task.cancel(); }
        }, 20L, 20L);
    }

    int burningCount() { return burning.size(); }

    // ---- after a melee hit lands -----------------------------------------------------------------

    /**
     * Adaptive Blade (Healer): "allies within 10 blocks share 33% of your life-steal healing". Life Steal / Syphon are
     * the enchanting module's and heal on the same Damage event at MONITOR; we read the Health before the hit lands
     * (HIGHEST) and the gain one tick later, and give party members in a dungeon within 10 blocks the share.
     * // TUNE: natural regeneration landing in that one tick is shared too (at most one regen pulse a second).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void beforeHealerHit(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Player p = ctx.playerAttacker();
        if (p == null || ctx.kind != Kind.MELEE || cleaving || WeaponDials.ADAPTIVE_HEALER_SHARE.get() <= 0) return;
        if (!traits.of(Traits.idOf(ctx.weapon)).adaptive || !inDungeon(p) || !"HEALER".equals(dungeonClass(p))) return;
        double before = Sky.vitals().health(p);
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            double share = WeaponMath.healerShare(Sky.vitals().health(p) - before, WeaponDials.ADAPTIVE_HEALER_SHARE.get());
            if (share <= 0) return;
            List<Player> party;
            try { party = Sky.service(Features.Party.class).members(p); } catch (RuntimeException ex) { return; }
            for (Player m : party) {
                if (m == null || m.equals(p) || !m.isOnline() || !m.getWorld().equals(p.getWorld())) continue;
                if (m.getLocation().distanceSquared(p.getLocation()) > 100 || !inDungeon(m)) continue;
                Sky.vitals().heal(m, share);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterHit(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Player p = ctx.playerAttacker();
        if (p == null || ctx.kind != Kind.MELEE || cleaving || !Fx.target(ctx.victim)) return;
        ItemStack weapon = ctx.weapon;
        Trait t = traits.of(Traits.idOf(weapon));
        if (Traits.none(t)) return;
        LivingEntity v = ctx.victim;
        if (t.healHit > 0) Sky.vitals().heal(p, t.healHit);
        if (t.dungeonHeal > 0 && Sky.service(Features.Dungeons.class).inDungeon(p)) Sky.vitals().heal(p, t.dungeonHeal);
        if (t.ignite > 0) ignite(p, v, ctx.finalDamage, t.ignite);
        if (t.adaptive && inDungeon(p)) {
            String cls = dungeonClass(p);
            if ("MAGE".equals(cls)) Sky.vitals().addMana(p, WeaponMath.adaptiveMageMana(Sky.vitals().maxMana(p), WeaponDials.ADAPTIVE_MAGE_MANA.get()));
            if ("ARCHER".equals(cls)) buffs.mark(v, "adaptive_archer:" + p.getUniqueId(), 1 + WeaponDials.ADAPTIVE_ARCHER_PCT.get() / 100.0, 5);
        }
        if (t.cleave > 0) {
            cleaving = true;
            try {
                int n = 0;
                double pct = WeaponDials.CLEAVE_PERCENT.get() / 100.0;
                for (LivingEntity m : Fx.mobsNear(v.getLocation(), t.cleave)) {
                    if (m == v) continue;
                    if (++n > WeaponDials.CLEAVE_MAX.get()) break;
                    Fx.melee(p, m, weapon, pct);
                }
                if (n > 0) Fx.burst(v.getLocation().add(0, 1, 0), Particle.SWEEP_ATTACK, 1, 0);
            } finally {
                cleaving = false;
            }
        }
    }

    /** Lets code-driven area hits (abilities) skip cleave. */
    void cleaving(boolean on) { cleaving = on; }

    // ---- kills ----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (p == null || !p.isOnline()) return;
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Traits.idOf(held);
        Trait t = traits.of(id);
        if (e.def != null && e.def.id != null && e.def.id.toLowerCase(java.util.Locale.ROOT).contains("primal_fear")) {
            WeaponData wd = WeaponsModule.data(p);   // the Eerie pet's Fearcreasing counts these (any weapon)
            if (wd != null) wd.primalFears++;
        }
        if (Traits.none(t)) {
            if ("SILENT_DEATH".equals(id)) Sky.abilities().setCooldown(p, Abilities.PREFIX + "shadowstep", 0);
            return;
        }
        if (t.whip != null && inDungeon(p) && MobTypes.is(t.whip, e.mob.getType(), e.def)
                && Sky.items().meetsRequirements(p, held, false)) {
            int n = whipKills.merge(p.getUniqueId(), 1, Integer::sum);
            Sky.stats().invalidate(p);
            Sky.vitals().flash(p, "&6Rallying Lash &7(" + n + " zombies): &c+" + n + "❁ Strength", 20);
        }
        if (t.tracksKills()) {
            try {
                Sky.items().update(held, d -> {
                    long k = d.extra.has("kills") ? d.extra.get("kills").getAsLong() : 0;
                    d.extra.addProperty("kills", k + 1);
                });
            } catch (RuntimeException ignored) { }
        }
        if (t.raider) {
            int combat = 0;
            try { combat = Sky.service(SkillService.class).level(p, SkillType.COMBAT); } catch (RuntimeException ignored) { }
            if (combat >= 10) e.coins += WeaponDials.RAIDER_COINS.get();   // research 06: +20 coins at Combat 10+
        }
        if (t.wisdomVs != null) {
            for (Map.Entry<String, Double> w : t.wisdomVs.entrySet()) {
                if (MobTypes.is(w.getKey(), e.mob.getType(), e.def)) e.combatXp *= 1 + w.getValue() / 100.0;
            }
        }
        if (t.animalDrops != null) {
            Map<String, Integer> drops = t.animalDrops.get(e.mob.getType().name());
            if (drops != null) {
                for (Map.Entry<String, Integer> d : drops.entrySet()) {
                    Material m = Material.matchMaterial(d.getKey());
                    if (m == null) continue;
                    String sky = Sky.items().vanillaId(m);
                    if (sky != null && Sky.items().exists(sky)) Sky.loot().give(p, sky, d.getValue(), LootSource.MOB_DROP);
                }
            }
        }
    }

    // ---- Miser's Curse (Emerald Blade): nothing may soften the death penalty -------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(SkyEvents.SkyDeath e) {
        Long until = greedCurse.get(e.player.getUniqueId());
        if (until == null || until < Buffs.now()) return;
        double half = Sky.economy().purse(e.player) / 2.0;
        if (e.coinsLost > 0 && e.coinsLost < half) e.coinsLost = half;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastHurt.remove(e.getPlayer().getUniqueId());
        greedCurse.remove(e.getPlayer().getUniqueId());
        whipKills.remove(e.getPlayer().getUniqueId());
        dungeonSig.remove(e.getPlayer().getUniqueId());
        if (targets != null) targets.forget(e.getPlayer().getUniqueId());
        forgetFear(e.getPlayer().getUniqueId());
    }
}
