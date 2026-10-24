package com.skyclone.mod.slayer;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Text;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Slayer armour behaviour (research 06 slayer armour table): set bonuses and the kill-counting "Bulwark" pieces.
 * Set ids are {@code slayer_<set>} on the ItemDefs (data/slayer/items.json).
 */
final class SlayerGear implements Listener {

    static final String ZOMBIE = "slayer_zombie_set", REAPER = "slayer_reaper_set", SPIDER = "slayer_spider_set",
            PRIMORDIAL = "slayer_primordial_set", MASTIFF = "slayer_mastiff_set", PACK = "slayer_pack_set",
            ENDER = "slayer_enderman_set";

    /** Mastiff Armor's Defense cap (research 06 "Absolute Unit"). */
    static final double MASTIFF_DEFENSE_CAP = 300;

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final SlayerModule mod;
    private final Map<UUID, Integer> strikes = new HashMap<>();
    private final Map<UUID, Long> rageUntil = new HashMap<>();
    private final Map<UUID, Long> rageReady = new HashMap<>();
    private final Map<UUID, Long> mastiffHeal = new HashMap<>();
    /** Coldfused fire-pillar bonus (research 01): player -> expiry ms. */
    private final Map<UUID, Long> coldfusedUntil = new HashMap<>();

    SlayerGear(SlayerModule mod) { this.mod = mod; }

    void register() {
        Sky.stats().addProvider("slayer_gear", this::stats);
        Sky.combat().addModifier("slayer_gear_out", 200, this::outgoing);
        Sky.combat().addModifier("slayer_gear_in", 200, this::incoming);
        Sky.items().addLoreContributor("slayer_gear", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(ItemDef def, ItemData data) { return bulwarkLore(def, data); }
        });
    }

    // ---- bulwark ----------------------------------------------------------------------------------

    /** Which mob type a piece counts, and how many kills per +20 Defense step. */
    static String bulwarkType(ItemDef def) {
        if (def == null || def.setId == null) return null;
        return switch (def.setId) {
            case ZOMBIE -> "zombie";
            case SPIDER -> "spider";
            case ENDER -> "enderman";
            default -> null;
        };
    }

    static int perStep(String type) { return "enderman".equals(type) ? 100 : 50; }

    static int kills(ItemData data) {
        if (data == null || data.extra == null) return 0;
        JsonElement e = data.extra.get("slayer_kills");
        return e == null ? 0 : e.getAsInt();
    }

    static double bulwark(String type, int kills) {
        double per = SlayerDials.GEAR_BULWARK_PER == null ? 20 : SlayerDials.GEAR_BULWARK_PER.get();
        return Math.min(per * 6, Math.floor(kills / (double) perStep(type)) * per);
    }

    private List<String> bulwarkLore(ItemDef def, ItemData data) {
        String type = bulwarkType(def);
        if (type == null) return List.of();
        int k = kills(data);
        double bonus = bulwark(type, k);
        int step = perStep(type);
        int next = (k / step + 1) * step;
        String noun = switch (type) { case "zombie" -> "Zombies"; case "spider" -> "Spiders"; default -> "Endermen"; };
        if (bonus >= SlayerDials.GEAR_BULWARK_PER.get() * 6) {
            return List.of("&7" + noun + " slain: &a" + Text.commasInt(k), "&7Bulwark: &a+" + Text.commasInt(bonus) + " ❈ &8(MAX)");
        }
        return List.of("&7" + noun + " slain: &a" + Text.commasInt(k),
                "&7Bulwark: &a+" + Text.commasInt(bonus) + " ❈ &8(next at " + Text.commasInt(next) + ")");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (p == null || e.def == null) return;
        String kind = mobType(e.def, e.mob);
        boolean undead = isUndead(e.def, e.mob);
        // reaper mask: heal 10% on undead kills
        ItemStack head = p.getInventory().getItem(EquipmentSlot.HEAD);
        if (undead && "REAPER_MASK".equals(Sky.items().idOf(head))) {
            Sky.vitals().heal(p, Sky.vitals().maxHealth(p) * 0.10);
        }
        if (kind == null) return;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack it = p.getInventory().getItem(slot);
            String id = Sky.items().idOf(it);
            if (id == null) continue;
            ItemDef def = Sky.items().def(id);
            if (!kind.equals(bulwarkType(def))) continue;
            Sky.items().update(it, d -> d.extra.addProperty("slayer_kills", kills(d) + 1));
            p.getInventory().setItem(slot, it);
        }
    }

    /** "zombie" / "spider" / "enderman" or null for other mobs. */
    static String mobType(MobDef def, LivingEntity mob) {
        if (def.slayer != null) return def.slayer.toLowerCase(Locale.ROOT);
        String ent = mob != null ? mob.getType().name() : def.entity == null ? "" : def.entity.toUpperCase(Locale.ROOT);
        return switch (ent) {
            case "ZOMBIE", "HUSK", "ZOMBIE_VILLAGER", "DROWNED" -> "zombie";
            case "SPIDER", "CAVE_SPIDER" -> "spider";
            case "ENDERMAN" -> "enderman";
            case "WOLF" -> "wolf";
            default -> null;
        };
    }

    static boolean isUndead(MobDef def, LivingEntity mob) {
        if (def != null && def.extra != null && def.extra.has("types")) {
            String t = def.extra.get("types").toString();
            if (t.contains("undead")) return true;
        }
        if ("zombie".equals(def == null ? null : def.slayer)) return true;
        String ent = mob != null ? mob.getType().name() : "";
        return switch (ent) {
            case "ZOMBIE", "HUSK", "ZOMBIE_VILLAGER", "DROWNED", "SKELETON", "STRAY", "WITHER_SKELETON", "ZOMBIFIED_PIGLIN",
                 "ZOGLIN", "WITHER", "PHANTOM", "BOGGED" -> true;
            default -> false;
        };
    }

    // ---- stats ------------------------------------------------------------------------------------

    /**
     * Re-entrancy guard. Several bonuses read another stat ({@code Sky.stats().get}); for a player whose stats were
     * never computed (just joined) core computes them on demand, which runs this provider again, which asks again...
     * Inside that nested pass we contribute nothing; the next 10-tick recompute reads the finished totals.
     */
    private boolean computing;

    private void stats(Player p, StatMap into) {
        if (computing) return;
        computing = true;
        try {
            statsInner(p, into);
        } finally {
            computing = false;
        }
    }

    private void statsInner(Player p, StatMap into) {
        String set = Sky.items().fullSet(p);
        long now = System.currentTimeMillis();
        if (coldfusedActive(p, now)) into.add(Stat.TRUE_DEFENSE, SlayerDials.COLDFUSED_BREAK_TRUE_DEFENSE.get());
        ItemStack head = p.getInventory().getItem(EquipmentSlot.HEAD);
        if ("WARDEN_HELMET".equals(Sky.items().idOf(head))) into.multiply(Stat.SPEED, 0.5);
        if (set == null) {
            if (Sky.items().setPieces(p, PACK) >= 4) pack(p, into);
            return;
        }
        switch (set) {
            // Radioactive (research 06): +1 Crit Damage per 10 Strength (the cap is 1,000 Strength = +100)
            case SPIDER -> into.add(Stat.CRIT_DAMAGE, Math.min(100, Math.floor(Sky.stats().get(p, Stat.STRENGTH) / 10)));
            case PRIMORDIAL -> into.add(Stat.CRIT_DAMAGE, Math.min(150, Math.floor(Sky.stats().get(p, Stat.STRENGTH) / 10)));
            case MASTIFF -> {
                // Absolute Unit (research 06): +1 Crit Damage per 2 Defense (Defense counted up to its 300 cap),
                // +1% Health per 25 Crit Damage, max 40%. The cap itself is applied to incoming hits (incoming()).
                double def = Math.min(MASTIFF_DEFENSE_CAP, Sky.stats().get(p, Stat.DEFENSE));
                into.add(Stat.CRIT_DAMAGE, Math.floor(Math.max(0, def) / 2));
                double cd = Sky.stats().get(p, Stat.CRIT_DAMAGE);
                into.addPercent(Stat.HEALTH, Math.min(40, Math.floor(Math.max(0, cd) / 25)));
            }
            case ENDER -> {
                if (vivid(p)) {
                    into.add(Stat.STRENGTH, 30);
                    into.add(Stat.ATTACK_SPEED, 20);
                    into.add(Stat.SPEED, 10);
                    into.multiply(Stat.INTELLIGENCE, 1.25);
                }
            }
            case REAPER -> {
                Long until = rageUntil.get(p.getUniqueId());
                if (until != null && until > now) {
                    into.add(Stat.SPEED, 100);
                    into.add(Stat.STRENGTH, 100);
                }
            }
            case PACK -> pack(p, into);
            default -> {}
        }
    }

    private void pack(Player p, StatMap into) {
        int others = 0;
        for (Player o : p.getWorld().getPlayers()) {
            if (o == p || o.getLocation().distanceSquared(p.getLocation()) > 30 * 30) continue;
            if (Sky.items().setPieces(o, PACK) > 0) others++;
        }
        others = Math.min(3, others);
        if (others > 0) {
            into.add(Stat.STRENGTH, 35 * others);
            into.add(Stat.DEFENSE, 80 * others);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        Sky.stats().invalidate(p);
        if (!e.isSneaking() || !REAPER.equals(Sky.items().fullSet(p))) return;
        long now = System.currentTimeMillis();
        Long ready = rageReady.get(p.getUniqueId());
        if (ready != null && ready > now) return;
        rageReady.put(p.getUniqueId(), now + 25_000);
        rageUntil.put(p.getUniqueId(), now + 6_000);
        Sky.vitals().flash(p, "&4&lREAPER'S RAGE! &7+100 Strength, Speed and damage", 40);
        Fx.sound(p, Sound.ENTITY_WITHER_AMBIENT, 0.6f, 1.4f);
        Fx.particle(p.getLocation().add(0, 1, 0), Particle.SOUL, 15, 0.4, 0.05);
        org.bukkit.Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) Sky.stats().invalidate(p); }, 121L);
    }

    // ---- Vivid Darkness (Last Horizon set, wave 5) -------------------------------------------------

    /** player -> last time they dealt or took a hit (the reference's "in combat"). */
    private final Map<UUID, Long> lastCombat = new HashMap<>();

    boolean inCombat(Player p) {
        Long at = lastCombat.get(p.getUniqueId());
        return at != null && System.currentTimeMillis() - at <= Math.max(1, SlayerDials.GEAR_COMBAT_SECONDS.get()) * 1000L;
    }

    /**
     * Vivid Darkness is on: full Last Horizon set, sneaking, in combat, and (NEU: "Costs 2 Soulflow per 5s") with
     * Soulstream in the pool. The soulflow module charges the 2 per 5 s; with no soulflow module loaded the bonus is
     * free, as before (a no-op pool reads 0 and would switch the set off for everyone).
     */
    boolean vivid(Player p) {
        if (!p.isSneaking() || !inCombat(p)) return false;
        if (!SlayerDials.VIVID_NEEDS_SOULFLOW.get() || !Sky.has(com.skyclone.api.feature.Features.Soulflow.class)) return true;
        try {
            return Sky.service(com.skyclone.api.feature.Features.Soulflow.class).soulflow(p) > 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombat(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Player a = ctx.playerAttacker(), v = ctx.playerVictim();
        long now = System.currentTimeMillis();
        if (a != null && v == null) {
            boolean was = inCombat(a);
            lastCombat.put(a.getUniqueId(), now);
            if (!was && a.isSneaking()) Sky.stats().invalidate(a);
            enderFerocity(a, ctx);
        }
        if (v != null && ctx.attacker != null && ctx.attacker != v) {
            boolean was = inCombat(v);
            lastCombat.put(v.getUniqueId(), now);
            if (!was && v.isSneaking()) Sky.stats().invalidate(v);
        }
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        lastCombat.remove(e.getPlayer().getUniqueId());
        coldfusedUntil.remove(e.getPlayer().getUniqueId());
    }

    // ---- Coldfused fire-pillar bonus (reforges request 22, wave 5) -------------------------------------

    boolean coldfusedActive(Player p, long now) {
        Long until = coldfusedUntil.get(p.getUniqueId());
        if (until == null) return false;
        if (until > now) return true;
        coldfusedUntil.remove(p.getUniqueId());
        return false;
    }

    /** Breaking a Fire Pillar with a Coldfused weapon and a Wisp out: +30 True Defense and x1.15 damage for 60 s. */
    void coldfusedBreak(Player p) {
        int secs = Math.max(1, SlayerDials.COLDFUSED_BREAK_SECONDS.get());
        coldfusedUntil.put(p.getUniqueId(), System.currentTimeMillis() + secs * 1000L);
        Sky.stats().invalidate(p);
        Sky.vitals().flash(p, "&b&lCOLDFUSED! &f+" + Text.commasInt(SlayerDials.COLDFUSED_BREAK_TRUE_DEFENSE.get())
                + "❂ True Defense &7and &cx" + SlayerDials.COLDFUSED_BREAK_DAMAGE.get() + " damage &7for " + secs + "s", 50);
        Fx.sound(p, Sound.BLOCK_GLASS_BREAK, 1f, 1.6f);
        Fx.particle(p.getLocation().add(0, 1, 0), Particle.SNOWFLAKE, 20, 0.5, 0.05);
        org.bukkit.Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) Sky.stats().invalidate(p); }, secs * 20L + 2);
    }

    /**
     * Grave Trolling (Ghoul armour, 3+ pieces): "Healing wands heal +50%". The weapons module heals
     * {@code extra.healPerSecond} for {@code extra.healSeconds} on a wand cast; we add half of it again over the same
     * seconds, only after a SUCCESSFUL cast (AbilityUsed).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbility(SkyEvents.AbilityUsed e) {
        if (e.ability == null || e.ability.id == null || !e.ability.id.contains("wand_heal")) return;
        Player p = e.player;
        if (!ZOMBIE.equals(Sky.items().fullSet(p)) && Sky.items().setPieces(p, ZOMBIE) < 3) return;
        ItemDef def = Sky.items().def(Sky.items().idOf(e.item));
        double hps = 60;
        int secs = 5;
        try {
            if (def != null && def.extra != null && def.extra.has("healPerSecond")) hps = def.extra.get("healPerSecond").getAsDouble();
            if (def != null && def.extra != null && def.extra.has("healSeconds")) secs = def.extra.get("healSeconds").getAsInt();
        } catch (RuntimeException ignored) { }
        double extra = hps * GRAVE_TROLLING_WAND_BONUS;
        for (int i = 0; i < Math.max(1, secs); i++) {
            org.bukkit.Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline() && !p.isDead()) Sky.vitals().heal(p, extra); }, 20L * i + 1);
        }
    }

    /** Grave Trolling's wand bonus (research 06 / NEU: +50%). */
    static final double GRAVE_TROLLING_WAND_BONUS = 0.5;

    /**
     * "+200 Ferocity against Endermen" (NEU). Ferocity is a stat core rolls per hit, not per target, so the bonus is
     * dealt here: the extra strikes the +200 adds on top of the player's own Ferocity (same rule, cap 500), each a
     * repeat of this hit, a few ticks apart like core's.
     */
    private void enderFerocity(Player p, DamageContext ctx) {
        if (ctx.kind != DamageContext.Kind.MELEE || ctx.finalDamage <= 0) return;
        if (!(ctx.victim instanceof LivingEntity victim) || !ENDER.equals(Sky.items().fullSet(p))) return;
        MobDef def = Sky.mobs().defOf(victim);
        if (def == null || !"enderman".equals(mobType(def, victim)) || !vivid(p)) return;
        int n = SlayerMath.bonusFerocityStrikes(Sky.stats().get(p, Stat.FEROCITY), SlayerDials.VIVID_ENDER_FEROCITY.get(),
                500, java.util.concurrent.ThreadLocalRandom.current().nextDouble());     // cap: research 01 / combat.ferocity-cap
        double dmg = ctx.finalDamage;
        for (int i = 1; i <= n; i++) {
            org.bukkit.Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (!p.isOnline() || !victim.isValid() || victim.isDead()) return;
                Sky.combat().rawDamage(victim, dmg, p, DamageContext.Kind.FEROCITY);
                Fx.dust(victim.getLocation().add(0, victim.getHeight() * 0.5, 0), Fx.rgb(200, 20, 20), 1.1f, 6, 0.3);
            }, 3L + 2L * i);
        }
    }

    // ---- damage -----------------------------------------------------------------------------------

    private void outgoing(DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null || !(ctx.victim instanceof LivingEntity victim)) return;
        // Sentinel Helmet, Brute Force (research 06): +20% base weapon damage per 25 Speed (Speed is already halved)
        if ((ctx.kind == DamageContext.Kind.MELEE || ctx.kind == DamageContext.Kind.PROJECTILE)
                && "WARDEN_HELMET".equals(Sky.items().idOf(p.getInventory().getItem(EquipmentSlot.HEAD)))) {
            double steps = Math.floor(Math.max(0, Sky.stats().get(p, Stat.SPEED)) / 25);
            if (steps > 0 && ctx.base > 0) ctx.flatBonus += ctx.base * 0.20 * steps;
        }
        // Coldfused fire-pillar bonus: x1.15 damage for 60 s after breaking a pillar
        if (ctx.kind != DamageContext.Kind.TRUE && coldfusedActive(p, System.currentTimeMillis())) {
            ctx.multiplicative *= Math.max(0, SlayerDials.COLDFUSED_BREAK_DAMAGE.get());
        }
        String set = Sky.items().fullSet(p);
        if (set == null) return;
        MobDef def = Sky.mobs().defOf(victim);
        switch (set) {
            case SPIDER, PRIMORDIAL -> {
                if (ctx.kind != DamageContext.Kind.MELEE) return;
                int n = strikes.merge(p.getUniqueId(), 1, Integer::sum);
                int every = SPIDER.equals(set) ? 4 : 3;
                if (n % every == 0) {
                    ctx.additive += SPIDER.equals(set) ? 100 : 50;
                    Fx.particle(victim.getLocation().add(0, 1, 0), Particle.ITEM_COBWEB, 6, 0.3, 0.05);
                }
            }
            case REAPER -> {
                if (isUndead(def, victim)) ctx.additive += 100;
                Long until = rageUntil.get(p.getUniqueId());
                if (until != null && until > System.currentTimeMillis()) ctx.additive += 100;
            }
            case ENDER -> {
                if (def != null && "enderman".equals(mobType(def, victim)) && vivid(p)) ctx.additive += 100;
            }
            default -> {}
        }
    }

    private void incoming(DamageContext ctx) {
        Player v = ctx.playerVictim();
        if (v == null || ctx.playerAttacker() != null || !(ctx.attacker instanceof LivingEntity attacker)) return;
        MobDef def = Sky.mobs().defOf(attacker);
        if (def == null) return;
        String kind = mobType(def, attacker);
        double extraDef = 0;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack it = v.getInventory().getItem(slot);
            String id = Sky.items().idOf(it);
            if (id == null) continue;
            ItemDef d = Sky.items().def(id);
            String bt = bulwarkType(d);
            if (bt != null && bt.equals(kind)) extraDef += bulwark(bt, kills(Sky.items().data(it)));
        }
        String set = Sky.items().fullSet(v);
        if (ZOMBIE.equals(set) || Sky.items().setPieces(v, ZOMBIE) >= 3) {
            if (isUndead(def, attacker)) extraDef += 100;
        }
        if (extraDef > 0) {
            double defense = Sky.stats().get(v, Stat.DEFENSE);
            ctx.multiplicative *= (100 + defense) / (100 + defense + extraDef);
        }
        if (MASTIFF.equals(set)) {
            if ("wolf".equals(kind) || attacker.getType().name().equals("WOLF") || (def.extra != null && def.extra.toString().contains("animal"))) {
                ctx.multiplicative *= 0.8;
            }
            // Defense capped at 300: core reduces by 100/(100+Defense); undo the part above the cap
            if (ctx.kind != DamageContext.Kind.TRUE) {
                double defense = Sky.stats().get(v, Stat.DEFENSE);
                if (defense > MASTIFF_DEFENSE_CAP) ctx.multiplicative *= (100 + defense) / (100 + MASTIFF_DEFENSE_CAP);
            }
            long now = System.currentTimeMillis();
            Long last = mastiffHeal.get(v.getUniqueId());
            if (last == null || now - last >= 1000) {
                mastiffHeal.put(v.getUniqueId(), now);
                Sky.vitals().heal(v, 150);
            }
        }
    }
}
