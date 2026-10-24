package com.skyclone.mod.rift;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rift combat. Inside the Rift your main-world damage formula does not apply to Rift monsters: every
 * hit deals your Rift Damage (research 01 sec 2.3: "normal weapons don't work there"), and Rift
 * monsters hit you for whole hearts of your Rift Hearts. Vampire slayer bosses fought in the Rift take
 * Rift Damage scaled by slayer's own {@code slayer.vampire-hp-scale}. Kills pay Motes and a second of
 * Rift Time. The three Rift bosses and their behaviours live here too.
 */
final class RiftCombat implements Listener {

    static final String[] BOSSES = {"rift_leech_sovereign", "rift_bloatmass", "rift_chateau_lord"};

    private final RiftModule m;
    /** boss mob id -> players who hit it (for charm credit). */
    private final Map<UUID, java.util.Set<UUID>> bossHelpers = new HashMap<>();

    RiftCombat(RiftModule m) { this.m = m; }

    void register() {
        AbilityService ab = Sky.abilities();
        ab.register("rift_leechstep", this::leechstep);
        ab.register("rift_melon", this::melon);
        ab.register("rift_elixir", this::elixir);
        Sky.mobs().registerBehaviour("rift_leech_boss", new LeechBoss());
        Sky.mobs().registerBehaviour("rift_bloat_boss", new BloatBoss());
        Sky.mobs().registerBehaviour("rift_lord_boss", new LordBoss());
        // behaviours are attached by id in code (the data generator keeps mobs plain)
        attach("rift_leech_sovereign", "rift_leech_boss");
        attach("rift_bloatmass", "rift_bloat_boss");
        attach("rift_chateau_lord", "rift_lord_boss");
        // Vampire slayer bosses fought inside the Rift take Rift Damage x slayer's Rift-HP scale. Done as a
        // modifier (priority 950, after slayer's 400) so slayer's own thresholds and immunities still see the hit.
        Sky.combat().addModifier("rift_vampire_boss", 950, ctx -> {
            Player a = ctx.playerAttacker();
            if (a == null || m.session(a) == null || !(ctx.victim instanceof LivingEntity le)) return;
            MobDef d = Sky.mobs().defOf(le);
            if (d == null || isRiftMob(d) || !"vampire".equals(d.slayer) || !d.has("boss")) return;
            if (ctx.kind == DamageContext.Kind.FEROCITY) { ctx.cancelled = true; return; }
            if (ctx.kind != DamageContext.Kind.MELEE && ctx.kind != DamageContext.Kind.PROJECTILE) return;
            double dmg = m.stats.sheet(a).damage();
            ItemDef held = heldDef(a);
            if (held != null && held.extra != null) dmg *= 1 + num(held.extra, "vampireBonus", 0);
            dmg *= vampireScale();
            double str = Math.max(0, Sky.stats().get(a, com.skyclone.api.stat.Stat.STRENGTH));
            ctx.base = dmg - 5;
            ctx.flatBonus = 0;
            ctx.multiplicative = ctx.multiplicative / (1 + str / 100.0);
            ctx.crit = false;
        });
    }

    private void attach(String mob, String behaviour) {
        MobDef d = Sky.mobs().def(mob);
        if (d != null && !d.behaviours.contains(behaviour)) d.behaviours.add(behaviour);
    }

    void shutdown() {
        for (String id : BOSSES) for (LivingEntity e : new ArrayList<>(Sky.mobs().live(id))) Sky.mobs().remove(e);
    }

    static boolean isRiftMob(MobDef d) {
        return d != null && d.extra != null && d.extra.has("rift") && d.extra.get("rift").getAsBoolean();
    }

    private static double num(JsonObject o, String k, double def) {
        try { return o != null && o.has(k) ? o.get(k).getAsDouble() : def; } catch (RuntimeException e) { return def; }
    }

    // =========================================================================================
    // damage
    // =========================================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Player attacker = ctx.playerAttacker();
        // ---- a player hits something in the Rift ----
        if (attacker != null && m.session(attacker) != null && ctx.victim != null && !(ctx.victim instanceof Player)) {
            MobDef d = Sky.mobs().defOf(ctx.victim);
            if (d == null) return;
            boolean rift = isRiftMob(d);
            if (!rift) return;                        // vampire slayer bosses are handled by the modifier below
            boolean vampireBoss = false;
            if (ctx.kind == DamageContext.Kind.FEROCITY) { ctx.cancelled = true; return; }   // ferocity is a main-world stat
            double dmg = m.stats.sheet(attacker).damage();
            ItemDef held = heldDef(attacker);
            if (held != null && held.extra != null) {
                double bonus = num(held.extra, "vampireBonus", 0);
                if (bonus > 0 && (vampireBoss || "vampire".equals(d.slayer))) dmg *= 1 + bonus;
                double mana = num(held.extra, "manaOnHit", 0);
                if (mana > 0) Sky.vitals().addMana(attacker, mana);
            }
            if (ctx.kind == DamageContext.Kind.MAGIC) dmg *= 0.5;                             // TUNE: wands are utilities here
            if (vampireBoss) dmg *= vampireScale();
            ctx.crit = false;
            ctx.finalDamage = Math.max(1, Math.floor(dmg));
            if (rift && d.has("boss")) bossHelpers.computeIfAbsent(ctx.victim.getUniqueId(), k -> new java.util.HashSet<>()).add(attacker.getUniqueId());
            return;
        }
        // ---- a Rift monster hits a player: whole hearts ----
        Player victim = ctx.playerVictim();
        if (victim != null && ctx.attacker != null && m.session(victim) != null) {
            MobDef d = ctx.attacker instanceof LivingEntity le ? Sky.mobs().defOf(le) : null;
            if (!isRiftMob(d)) return;
            double hearts = num(d.extra, "hearts", 1);
            if (ctx.tags.contains("rift_hearts")) hearts = ctx.base;            // scripted boss hits pass hearts as base
            RiftStats.Sheet sh = m.stats.sheet(victim);
            ctx.finalDamage = Math.ceil(RiftMath.heartDamage(hearts, Sky.vitals().maxHealth(victim), sh.hearts()));
            double drain = num(d.extra, "timeDrain", 0);
            if (drain > 0) {
                m.travel.addTime(victim, -drain);
                Sky.vitals().flash(victim, "&c-" + (int) drain + "sф &7(" + d.name + ")", 20);
            }
        }
    }

    private double vampireScale() {
        try { return Sky.dials().number("slayer.vampire-hp-scale", m.dials.vampireBonusCap.get(), "").get(); }
        catch (RuntimeException e) { return m.dials.vampireBonusCap.get(); }
    }

    private static ItemDef heldDef(Player p) {
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it.getType().isAir()) return null;
        String id = Sky.items().idOf(it);
        return id == null ? null : Sky.items().def(id);
    }

    // =========================================================================================
    // kills
    // =========================================================================================

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (!isRiftMob(e.def)) return;
        e.coins = 0;                                     // Rift monsters pay Motes, not coins
        UUID mobId = e.mob.getUniqueId();
        java.util.Set<UUID> helpers = bossHelpers.remove(mobId);
        Player k = e.killer;
        if (k != null && m.session(k) != null) {
            RiftData d = m.data(k);
            long motes = (long) num(e.def.extra, "motes", 0);
            m.addMotes(k, motes, e.def.name);
            double t = m.dials.killTime.get();
            ItemStack helm = k.getInventory().getHelmet();
            if (helm != null) {
                String hid = Sky.items().idOf(helm);
                ItemDef hd = hid == null ? null : Sky.items().def(hid);
                if (hd != null && hd.extra != null) t += num(hd.extra, "killTime", 0);
            }
            m.travel.addTime(k, t);
            if (d != null) {
                int kills = d.kills.merge(e.def.id, 1, Integer::sum);
                if (e.def.extra.has("vermin")) m.npcs.verminKilled(k);
                crux(k, e.def.id, kills);
            }
        }
        if (e.def.has("boss")) bossDown(e, helpers);
    }

    /**
     * Crux talismans (wave 3): Crux Fortune rolls bonus cruxes on a tracked crux monster, and a kill that
     * reaches a milestone says so (research 06: "+5 Rift Time and +5 Crux Fortune per kill milestone").
     */
    private void crux(Player k, String mobId, int kills) {
        int idx = RiftMath.CRUX_MOBS.indexOf(mobId);
        if (idx < 0) return;
        RiftStats.Sheet sh = m.stats.sheet(k);
        if (sh.cruxTier() <= idx) return;                       // this talisman does not track that monster yet
        int bonus = RiftMath.cruxBonus(sh.cruxFortune(), Math.random());
        String item = RiftMath.CRUX_ITEMS.get(idx);
        if (bonus > 0 && Sky.items().exists(item)) Sky.loot().give(k, item, bonus, LootSource.MOB_DROP);
        for (int step : RiftMath.CRUX_STEPS) {
            if (kills == step) {
                m.stats.invalidate(k);
                Msg.success(k, "&dCrux milestone! &7" + step + " kills: &a+5ф Rift Time &7and &d+5 Crux Fortune&7.");
                Msg.sound(k, org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f);
                break;
            }
        }
    }

    private void bossDown(SkyEvents.MobKill e, java.util.Set<UUID> helpers) {
        String charm = switch (e.def.id) {
            case "rift_bloatmass" -> "globulate";
            case "rift_chateau_lord" -> "vampiric";
            default -> null;
        };
        java.util.Set<Player> credit = new java.util.HashSet<>();
        if (e.killer != null) credit.add(e.killer);
        if (helpers != null) for (UUID u : helpers) { Player q = Bukkit.getPlayer(u); if (q != null) credit.add(q); }
        for (Player q : e.location.getWorld().getPlayers())
            if (q.getLocation().distanceSquared(e.location) < 48 * 48 && m.session(q) != null) credit.add(q);
        for (Player q : credit) {
            if (m.session(q) == null) continue;
            if (q != e.killer) m.addMotes(q, m.dials.bossBonusMotes.get(), e.def.name);
            Msg.box(q, "&5&l" + e.def.name.toUpperCase() + " DEFEATED", List.of("&7Well fought. The Rift remembers."));
            if (charm != null) m.puzzles.grantCharm(q, charm);
        }
    }

    // =========================================================================================
    // abilities
    // =========================================================================================

    private boolean leechstep(AbilityService.Use use) {
        Player p = use.player();
        if (m.session(p) == null) { Msg.error(p, "This wand only works inside the Rift."); return false; }
        int dist = (int) num(use.itemDef().extra, "blink", 5);
        Location from = p.getLocation();
        Vector dir = from.getDirection().normalize();
        Location best = null;
        for (double t = 1; t <= dist; t += 0.5) {
            Location c = from.clone().add(dir.clone().multiply(t));
            if (!c.getBlock().isPassable() || !c.clone().add(0, 1, 0).getBlock().isPassable()) break;
            best = c;
        }
        if (best == null) return false;
        best.setYaw(from.getYaw()); best.setPitch(from.getPitch());
        p.teleport(best);
        p.setFallDistance(0);
        p.getWorld().spawnParticle(Particle.PORTAL, best, 20, 0.3, 0.6, 0.3, 0.1);
        Msg.sound(p, Sound.ENTITY_ENDERMAN_TELEPORT, 1.6f);
        return true;
    }

    private boolean melon(AbilityService.Use use) {
        Player p = use.player();
        if (m.session(p) == null) { Msg.error(p, "This only works inside the Rift."); return false; }
        double hearts = num(use.itemDef().extra, "heal", 1);
        double max = Sky.vitals().maxHealth(p);
        if (Sky.vitals().health(p) >= max) { Msg.error(p, "You are already at full Hearts."); return false; }
        double hp = RiftMath.heartDamage(hearts, max, m.stats.sheet(p).hearts());
        Sky.vitals().heal(p, hp);
        consumeOne(use.item());
        Msg.sound(p, Sound.ENTITY_GENERIC_EAT, 1.2f);
        Sky.vitals().flash(p, "&c+" + Text.commas(hearts) + "❤", 30);
        return true;
    }

    private boolean elixir(AbilityService.Use use) {
        Player p = use.player();
        RiftSession s = m.session(p);
        if (s == null) { Msg.error(p, "This only works inside the Rift."); return false; }
        String type = use.itemDef().extra.has("elixir") ? use.itemDef().extra.get("elixir").getAsString() : "speed";
        s.elixirs.put(type, System.currentTimeMillis() + 120_000);
        if (type.equals("jump")) p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 120 * 20, 1, true, false, true));
        m.stats.invalidate(p);
        Sky.stats().invalidate(p);
        consumeOne(use.item());
        Msg.sound(p, Sound.ENTITY_GENERIC_DRINK, 1.2f);
        Msg.success(p, "You feel the " + type + " elixir take hold for 2 minutes.");
        return true;
    }

    private static void consumeOne(ItemStack it) {
        if (it != null) it.setAmount(it.getAmount() - 1);
    }

    // =========================================================================================
    // bosses
    // =========================================================================================

    /** Summon one of the three Rift bosses at its anchor, paid in Motes. One of each alive at a time. */
    boolean summon(Player p, String mobId, String anchor, long cost) {
        if (m.session(p) == null) { Msg.error(p, "You must be in the Rift."); return false; }
        if (!Sky.mobs().live(mobId).isEmpty()) { Msg.error(p, "It is already awake. Help fight it!"); return false; }
        Location at = Sky.worlds().anchor(RiftCatalog.ISLAND, anchor);
        if (at == null) { Msg.error(p, "Nothing answers the call."); return false; }
        if (!m.takeMotes(p, cost)) { Msg.error(p, "You need " + Text.commasInt(cost) + " Motes."); return false; }
        LivingEntity boss = Sky.mobs().spawn(mobId, at, p);
        if (boss == null) { m.addMotes(p, cost, null); Msg.error(p, "Nothing answers the call."); return false; }
        Sky.mobs().setAllies(boss, q -> m.session(q) != null);
        MobDef d = Sky.mobs().def(mobId);
        for (Player q : at.getWorld().getPlayers()) {
            if (q.getLocation().distanceSquared(at) > 64 * 64) continue;
            q.showTitle(Title.title(Text.c("&c&l" + (d == null ? mobId : d.name)), Text.c("&7has been called by &f" + p.getName()),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(500))));
            Msg.sound(q, Sound.ENTITY_WITHER_SPAWN, 1.4f);
        }
        return true;
    }

    /** Scripted boss hit: {@code hearts} whole hearts through the normal pipeline (defense, events). */
    private static void heartsHit(LivingEntity boss, Player p, double hearts) {
        Sky.combat().damagePlayer(p, hearts, boss, DamageContext.Kind.MOB);
    }

    private static List<Player> near(LivingEntity mob, double r) {
        List<Player> out = new ArrayList<>();
        for (Player p : mob.getWorld().getPlayers()) if (p.getLocation().distanceSquared(mob.getLocation()) < r * r && !p.isDead()) out.add(p);
        return out;
    }

    private void addSpawn(LivingEntity boss, String add, int n) {
        UUID owner = Sky.mobs().owner(boss);
        Player op = owner == null ? null : Bukkit.getPlayer(owner);
        for (int i = 0; i < n; i++) {
            Location l = boss.getLocation().clone().add((Math.random() - 0.5) * 6, 0.5, (Math.random() - 0.5) * 6);
            LivingEntity a = Sky.mobs().spawn(add, l, op);
            if (a != null) Sky.mobs().setAllies(a, q -> m.session(q) != null);
        }
    }

    /** Leech Sovereign: every 8 s two leechlings; its hits drain Rift Time (data: timeDrain). */
    final class LeechBoss implements MobBehaviour {
        @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
            if (tick % 160 != 0) return;
            if (Sky.mobs().live("rift_leechling").size() < 6) addSpawn(mob, "rift_leechling", 2);
            mob.getWorld().spawnParticle(Particle.SQUID_INK, mob.getLocation().add(0, 1, 0), 15, 0.6, 0.5, 0.6, 0.02);
        }
    }

    /** The Bloatmass: bloatlings every 10 s; below half health it pulses, knocking everyone back for a heart. */
    final class BloatBoss implements MobBehaviour {
        @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
            if (tick % 200 == 0 && Sky.mobs().live("rift_bloatling").size() < 8) addSpawn(mob, "rift_bloatling", 2);
            double frac = Sky.mobs().health(mob) / Math.max(1, Sky.mobs().maxHealth(mob));
            if (frac < 0.5 && tick % 100 == 0) {
                mob.getWorld().spawnParticle(Particle.ITEM_SLIME, mob.getLocation().add(0, 1, 0), 60, 3, 1, 3, 0.1);
                mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_SLIME_SQUISH, 2f, 0.5f);
                for (Player p : near(mob, 7)) {
                    Vector push = p.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
                    if (push.lengthSquared() < 0.01) push = new Vector(1, 0, 0);
                    p.setVelocity(push.normalize().multiply(1.2).setY(0.5));
                    heartsHit(mob, p, 1);
                }
            }
        }
    }

    /** The Chateau Lord: drinks from whoever is closest every 6 s (heals 3%), and calls crypt bats. */
    final class LordBoss implements MobBehaviour {
        @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
            if (tick % 120 == 0) {
                List<Player> ps = near(mob, 10);
                if (!ps.isEmpty()) {
                    Player t = ps.get(0);
                    heartsHit(mob, t, 1);
                    Sky.mobs().setHealth(mob, Math.min(Sky.mobs().maxHealth(mob), Sky.mobs().health(mob) + Sky.mobs().maxHealth(mob) * 0.03));
                    mob.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, t.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.1);
                    Sky.vitals().flash(t, "&4The Chateau Lord drinks from you!", 30);
                }
            }
            if (tick % 300 == 0 && Sky.mobs().live("rift_crypt_bat").size() < 6) addSpawn(mob, "rift_crypt_bat", 3);
        }
    }
}
