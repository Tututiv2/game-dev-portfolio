package com.skyclone.mod.slayer;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Msg;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.MagmaCube;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cinder Demonlord (research 04 sec 2.9 / 6.2):
 * <pre>
 * all  Immolate: true damage every second within 6 blocks, 3 steps by its health (table 2.9)
 * all  Demon Split at 50% (T1-T2) or 66% and 33% (T3-T4): invulnerable until Quorrix and Tyrphon both die
 * T2+  Attuned Shield: only a dagger attuned to the shield colour does full damage (anything else 1%); the colour
 *      steps Ashen -> Spirit -> Auric -> Crystal every 8 matching hits. Quorrix cycles Spirit/Crystal, Tyrphon
 *      Ashen/Auric
 * T2+  Fire Pillars (from 50%/66%) every 25 s: 8 hits within 7 s or everyone within 6 blocks dies
 * T3+  Fire Pit Dance under 33% every 12 s: 5 glowing 3x3 pits, then 950 / 2,865 true damage per second inside them
 * Quorrix: a beam every 8 s, 1,500 true damage. Tyrphon: leaves a trail of fire, 1,350 true damage per second on it
 * </pre>
 */
final class BlazeFight extends BossFight {

    static final String[] CYCLE = {"ashen", "spirit", "auric", "crystal"};
    private int colour;
    private int matched;
    private final Map<UUID, int[]> demonShield = new HashMap<>();   // uuid -> {index into its pair, hits}
    private boolean split66, split33, split50;
    private boolean splitting;
    private long pillarSince = -1;
    /** The last player to strike the live pillar, and whether that strike earns Coldfused's break bonus. */
    private UUID pillarBreaker;
    private boolean breakerColdfused;
    private final List<Pit> pits = new ArrayList<>();
    private final Deque<Location> trail = new ArrayDeque<>();

    private static final class Pit {
        Location c;
        long armedAt;
        long endsAt;
    }

    BlazeFight(SlayerModule mod, Player owner, SlayerCatalog.Type type, SlayerCatalog.Tier tier) {
        super(mod, owner, type, tier);
        barColor = BossBar.Color.YELLOW;
    }

    @Override
    void onBegin() { updateStatus(); }

    private void updateStatus() {
        if (tierNo < 2) { status = ""; return; }
        status = colourName(CYCLE[colour]) + " &7(" + (8 - matched) + ")";
        barColor = barFor(CYCLE[colour]);
    }

    static String colourName(String a) {
        return switch (a) {
            case "ashen" -> "&8&lASHEN";
            case "spirit" -> "&f&lSPIRIT";
            case "auric" -> "&e&lAURIC";
            default -> "&b&lCRYSTAL";
        };
    }

    private static BossBar.Color barFor(String a) {
        return switch (a) {
            case "ashen" -> BossBar.Color.RED;
            case "spirit" -> BossBar.Color.WHITE;
            case "auric" -> BossBar.Color.YELLOW;
            default -> BossBar.Color.BLUE;
        };
    }

    @Override
    void step() {
        Player o = owner();
        if (o == null) return;
        Location b = boss.getLocation();

        // A vanilla blaze hovers up to its target's eye level and stays out of sword reach; this boss fights
        // on the ground (its own fireballs are cancelled in SlayerModule), so pull it down when it drifts up.
        if (boss.getVelocity().getY() > -0.2) {
            Location below = ground(b.clone());
            if (b.getY() - below.getY() > 1.5) boss.setVelocity(boss.getVelocity().setY(-0.35));
        }

        // Immolate
        if (every(1)) {
            double dmg = SlayerMath.immolate(type.immolate == null ? null : type.immolate[Math.min(type.immolate.length, tierNo) - 1], frac());
            Fx.ring(b, 6, Fx.rgb(255, 120, 0), 36);
            for (Player p : playersNear(b, 6)) hurtTrue(p, dmg);
        }
        Fx.particle(b.clone().add(0, 1, 0), Particle.FLAME, 4, 0.5, 0.02);

        // demon split bookkeeping
        if (splitting) {
            tickDemons(o);
            if (liveMinions("slayer_quazii") + liveMinions("slayer_typhoeus") == 0) {
                splitting = false;
                immune = null;
                setImmobile(false);
                Msg.send(o, "&aBoth demons are dead! &7The Demonlord is vulnerable again.");
                Fx.sound(b, Sound.ENTITY_BLAZE_DEATH, 1f, 0.6f);
            }
        }

        // fire pillars
        double pillarFrom = tierNo == 2 ? 0.5 : 0.66;
        if (tierNo >= 2 && frac() <= pillarFrom && !splitting && pillarSince < 0 && every(25)) {
            LivingEntity pillar = spawnMinion("slayer_fire_pillar", around(o.getLocation(), 5, 8), 8);
            if (pillar != null) {
                pillar.setGravity(false);
                if (pillar instanceof MagmaCube m) m.setSize(2);
                pillarSince = ticks;
                Fx.warn(o, "&6&lFIRE PILLAR", "&7Hit it &c8 times &7within 7 seconds!");
            }
        }
        if (pillarSince >= 0) {
            List<LivingEntity> ps = minionsOf("slayer_fire_pillar");
            if (ps.isEmpty()) {
                pillarSince = -1;
                Msg.send(o, "&aYou toppled the fire pillar!");
                // Coldfused (research 01): the strike that broke it was Coldfused with a Wisp out -> +30 TD, x1.15, 60 s
                Player br = pillarBreaker == null ? null : org.bukkit.Bukkit.getPlayer(pillarBreaker);
                if (br != null && breakerColdfused) mod.gear.coldfusedBreak(br);
                pillarBreaker = null;
                breakerColdfused = false;
            } else {
                for (LivingEntity p : ps) {
                    for (int y = 0; y < 5; y++) Fx.particle(p.getLocation().add(0, y, 0), Particle.FLAME, 3, 0.25, 0.01);
                }
                if (ticks - pillarSince >= 28) {          // 7 s
                    for (LivingEntity p : ps) {
                        Location at = p.getLocation();
                        Sky.mobs().remove(p);
                        Fx.particle(at.clone().add(0, 1, 0), Particle.EXPLOSION_EMITTER, 2, 1, 0);
                        Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.5f);
                        for (Player pl : playersNear(at, 6)) Sky.vitals().kill(pl, "slayer:fire_pillar");
                    }
                    pillarSince = -1;
                    pillarBreaker = null;
                    breakerColdfused = false;
                }
            }
        }

        // fire pit dance
        if (tierNo >= 3 && frac() <= 1.0 / 3.0 && every(12)) {
            for (int i = 0; i < 5; i++) {
                Pit pit = new Pit();
                pit.c = i == 0 ? ground(o.getLocation().clone().add(0, 0.5, 0)) : around(o.getLocation(), 2, 8);
                pit.armedAt = ticks + 6;
                pit.endsAt = ticks + 22;
                pits.add(pit);
            }
            Fx.warn(o, "&c&lFIRE PITS", "&7Get out of the glowing squares!");
        }
        tickPits();
    }

    private void tickPits() {
        if (pits.isEmpty()) return;
        double dps = tierNo >= 4 ? 2865 : 950;
        Iterator<Pit> it = pits.iterator();
        while (it.hasNext()) {
            Pit pit = it.next();
            boolean armed = ticks >= pit.armedAt;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                Location l = pit.c.clone().add(dx, 0.1, dz);
                if (armed) Fx.particle(l, Particle.FLAME, 2, 0.3, 0.01);
                else Fx.particle(l, Particle.SMALL_FLAME, 1, 0.3, 0);
            }
            if (armed && ticks % 2 == 0) {
                for (Player p : playersNear(pit.c, 2.2)) {
                    Location pl = p.getLocation();
                    if (Math.abs(pl.getX() - pit.c.getX()) <= 1.6 && Math.abs(pl.getZ() - pit.c.getZ()) <= 1.6) hurtTrue(p, dps / 2);
                }
            }
            if (ticks >= pit.endsAt) it.remove();
        }
    }

    private void tickDemons(Player o) {
        int t = Math.max(1, Math.min(4, tierNo)) - 1;
        // Quorrix: beam every 8 s
        if (every(8)) {
            for (LivingEntity q : minionsOf("slayer_quazii")) {
                if (o.getWorld() != q.getWorld()) continue;
                Fx.line(q.getEyeLocation(), o.getLocation().add(0, 1, 0), Fx.rgb(240, 240, 255), 0.4);
                Fx.sound(q.getLocation(), Sound.ENTITY_GUARDIAN_ATTACK, 1f, 1.2f);
                hurtTrue(o, 1500);
                Sky.mobs().setHealth(q, Math.min(Sky.mobs().maxHealth(q), Sky.mobs().health(q) + Sky.mobs().maxHealth(q) * 0.02));
            }
        }
        // Tyrphon: trail of fire
        for (LivingEntity ty : minionsOf("slayer_typhoeus")) {
            if (ticks % 2 == 0) {
                trail.addLast(ty.getLocation().clone());
                while (trail.size() > 16) trail.removeFirst();
            }
        }
        for (Location l : trail) Fx.particle(l.clone().add(0, 0.1, 0), Particle.FLAME, 1, 0.2, 0);
        if (every(0.5) && !trail.isEmpty()) {
            for (Player p : playersNear(trail.peekLast(), 24)) {
                for (Location l : trail) {
                    if (l.getWorld() == p.getWorld() && l.distanceSquared(p.getLocation()) < 1.2) { hurtTrue(p, 1350 / 2.0); break; }
                }
            }
        }
        if (liveMinions("slayer_typhoeus") == 0) trail.clear();
    }

    private void split(Player o) {
        splitting = true;
        immune = "Demon Split";
        setImmobile(true);
        int t = Math.max(1, Math.min(4, tierNo)) - 1;
        double hp = (type.demons == null || type.demons.hp.length <= t ? 500_000 : type.demons.hp[t]) * Math.max(0.0001, SlayerDials.BOSS_HP_SCALE.get());
        Location b = boss.getLocation();
        LivingEntity q = spawnMinion("slayer_quazii", around(b, 2, 3), hp);
        LivingEntity ty = spawnMinion("slayer_typhoeus", around(b, 2, 3), hp);
        if (q != null) demonShield.put(q.getUniqueId(), new int[]{0, 0});
        if (ty != null) demonShield.put(ty.getUniqueId(), new int[]{0, 0});
        if (o != null) Fx.warn(o, "&6&lDEMON SPLIT", "&7Kill &fQuorrix &7and &cTyrphon&7!");
        Fx.sound(b, Sound.ENTITY_BLAZE_SHOOT, 1.2f, 0.5f);
        if (q == null && ty == null) {       // could not spawn: do not soft-lock
            splitting = false;
            immune = null;
            setImmobile(false);
        }
    }

    @Override
    void onHit(DamageContext ctx) {
        Player o = owner();
        if (tierNo <= 2) {
            if (!split50 && clampAt(ctx, 0.5)) { split50 = true; org.bukkit.Bukkit.getScheduler().runTask(Sky.plugin(), () -> split(o)); }
        } else {
            if (!split66 && clampAt(ctx, 0.667)) { split66 = true; org.bukkit.Bukkit.getScheduler().runTask(Sky.plugin(), () -> split(o)); }
            else if (!split33 && clampAt(ctx, 0.333)) { split33 = true; org.bukkit.Bukkit.getScheduler().runTask(Sky.plugin(), () -> split(o)); }
        }
        if (tierNo >= 2) {
            String need = CYCLE[colour];
            String has = attunement(ctx);
            if (need.equals(has)) {
                if (strike(ctx)) matched++;     // follow-ups land, only strikes shift the shield
                if (matched >= 8) {
                    matched = 0;
                    colour = (colour + 1) % CYCLE.length;
                    Player p = ctx.playerAttacker();
                    if (p != null) Sky.vitals().flash(p, "&7Shield shifted to " + colourName(CYCLE[colour]), 30);
                    Fx.sound(boss.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 1.2f);
                }
                updateStatus();
            } else {
                ctx.finalDamage *= 0.01;
                Player p = ctx.playerAttacker();
                if (p != null) Sky.vitals().flash(p, "&cWrong attunement! &7Need " + colourName(need), 15);
            }
        }
    }

    @Override
    void minionDamaged(LivingEntity m, DamageContext ctx) {
        var d = Sky.mobs().defOf(m);
        if (d == null) return;
        if (d.id.equals("slayer_fire_pillar")) {
            // one strike = one of its 8 hits. "Twice as fast" with a Wisp pet out (and Coldfused) is the pets module's:
            // its Damage handler doubles this 1 after us - doubling here too made it 4x.
            Player hitter = ctx.playerAttacker();
            ctx.finalDamage = SlayerMath.PILLAR_HITS_PER_STRIKE;
            boolean wisp = hitter != null && wispOut(hitter);
            if (hitter != null) {
                pillarBreaker = hitter.getUniqueId();
                breakerColdfused = SlayerMath.coldfusedBreakBonus(coldfused(hitter, ctx), wisp);
            }
            Fx.sound(m.getLocation(), Sound.BLOCK_BASALT_HIT, 1f, wisp ? 1.5f : 1f);
            return;
        }
        if (tierNo < 2) return;
        int[] st = demonShield.get(m.getUniqueId());
        if (st == null) return;
        String[] pair = d.id.equals("slayer_quazii") ? new String[]{"spirit", "crystal"} : new String[]{"ashen", "auric"};
        String need = pair[st[0] % 2];
        if (need.equals(attunement(ctx))) {
            if (strike(ctx)) st[1]++;
            if (st[1] >= 8) { st[1] = 0; st[0]++; }
        } else {
            ctx.finalDamage *= 0.01;
            Player p = ctx.playerAttacker();
            if (p != null) Sky.vitals().flash(p, "&cThis demon needs " + colourName(need), 15);
        }
    }

    @Override
    void minionAttacking(LivingEntity m, Player target, DamageContext ctx) {
        super.minionAttacking(m, target, ctx);
        var d = Sky.mobs().defOf(m);
        if (d != null && (d.id.equals("slayer_quazii") || d.id.equals("slayer_typhoeus")) && type.demons != null) {
            int t = Math.max(1, Math.min(4, tierNo)) - 1;
            if (t < type.demons.dps.length) ctx.base = type.demons.dps[t];
        }
    }

    /**
     * Does this hit count toward a shield's 8? A swing, or core's ferocity repeat of one (it carries the weapon); a
     * weaponless follow-up (Burning Vengeance, Soul Howl) lands at full damage but does not shift the shield.
     */
    static boolean strike(DamageContext ctx) {
        if (ctx.kind == DamageContext.Kind.MELEE) return true;
        return ctx.kind == DamageContext.Kind.FEROCITY && ctx.weapon != null && !ctx.weapon.getType().isAir();
    }

    /** The weapon of this hit is reforged Coldfused (reforges' stone reforge id). */
    static boolean coldfused(Player p, DamageContext ctx) {
        ItemStack w = ctx.weapon != null && !ctx.weapon.getType().isAir() ? ctx.weapon : p.getInventory().getItemInMainHand();
        try {
            return Sky.items().idOf(w) != null && "coldfused".equalsIgnoreCase(Sky.items().data(w).reforge);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A Wisp pet is out (any pet type ending in WISP: Droplet, Frost, Glacial, Subzero). */
    static boolean wispOut(Player p) {
        try {
            String t = Sky.service(com.skyclone.api.feature.Features.Pets.class).activeType(p);
            return t != null && t.toUpperCase(java.util.Locale.ROOT).endsWith("WISP");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The attunement of the dagger in the hit, or "" for anything else. Data contract with the weapons module.
     * Wave 5 (weapons request 11): a dagger's own follow-ups (Burning Vengeance = TRUE, Soul Howl strikes = FEROCITY)
     * carry no weapon; they take the attunement of the dagger in the attacker's hand.
     */
    static String attunement(DamageContext ctx) {
        if (ctx.kind != DamageContext.Kind.MELEE && ctx.kind != DamageContext.Kind.FEROCITY && ctx.kind != DamageContext.Kind.TRUE) return "";
        ItemStack w = ctx.weapon;
        if ((w == null || w.getType().isAir()) && ctx.kind != DamageContext.Kind.MELEE && ctx.playerAttacker() != null) {
            w = ctx.playerAttacker().getInventory().getItemInMainHand();
        }
        if (w == null || w.getType().isAir()) return "";
        String id = Sky.items().idOf(w);
        if (id == null || !id.endsWith("_DAGGER")) return "";
        try {
            JsonElement e = Sky.items().data(w).extra.get("attunement");
            if (e != null) return e.getAsString().toLowerCase(java.util.Locale.ROOT);
            ItemDef def = Sky.items().def(id);
            if (def != null && def.extra != null && def.extra.has("attunement")) return def.extra.get("attunement").getAsString().toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException ignored) { }
        return id.contains("MAW") ? "spirit" : "ashen";
    }
}
