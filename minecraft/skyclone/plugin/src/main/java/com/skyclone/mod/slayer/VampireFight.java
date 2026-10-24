package com.skyclone.mod.slayer;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.ui.Msg;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Duskborne Bloodfiend (research 04 sec 2.10 / 6.2). Its numbers are in Rift hearts; one heart is
 * {@code slayer.vampire-heart-percent}% of your max health because there is no Rift health scale outside the Rift.
 * <pre>
 * all  Coagulation every 1.5 s: 0.75/1.5/1.5/1.5/2.25 hearts, healing itself
 * all  Spectral Form: becomes bats and reappears beside you every 10 s or when you run
 * all  Mania at its thresholds (T1-2: 50%; T3-4: 66/33%; T5: 75/50/25%): invulnerable 26 s, a moving safe ring;
 *      outside it you take 2/3/5/6/15 hearts every 2 s
 * T2+  Twin Claws every 7 s: a telegraphed frontal slam for 3/5/6/8 hearts (T2-T5)
 * T2+  Stone Wrath every 30 s: two Blood Gargoyles, each saps 10% of your max health while it lives
 * T3+  Spring Trap every 25 s: alternate LEFT and RIGHT clicks on the spring 6 times within 15 s, or die
 * T3+  Command every 18 s: JUMP / SNEAK / LOOK UP / LOOK DOWN within 2.5 s (T4+ 2 s) or 4/5/20 hearts
 * T5   Blood Ichor every 30 s: only its own Twin Claws can break it; 25 s, else it heals 10% and bites you
 * all  Execution: it cannot go below 20% except to a Steak Stake (players under Vampire 2 may finish it by hand)
 * </pre>
 */
final class VampireFight extends BossFight {

    private static final double[] DRAIN = {0.75, 1.5, 1.5, 1.5, 2.25};
    private static final double[] MANIA = {2, 3, 5, 6, 15};
    private static final double[] CLAWS = {0, 3, 5, 6, 8};
    private static final double[] COMMAND = {0, 0, 4, 5, 20};

    private final double[] maniaAt;
    private int maniaDone;
    private long maniaStart = -1;
    private Location ring;
    private long clawsAt = -1;
    private Vector clawsDir;
    private Location clawsFrom;
    private long springStart = -1;
    private LivingEntity spring;
    private int springCount;
    private boolean springLastLeft;
    private boolean springFirst = true;
    private long commandUntil = -1;
    private String command;
    private boolean commandDone;
    private BlockDisplay ichor;
    private Location ichorAt;
    private long ichorStart = -1;
    boolean executed;

    VampireFight(SlayerModule mod, Player owner, SlayerCatalog.Type type, SlayerCatalog.Tier tier) {
        super(mod, owner, type, tier);
        barColor = BossBar.Color.RED;
        maniaAt = switch (tierNo) {
            case 1, 2 -> new double[]{0.5};
            case 3, 4 -> new double[]{0.66, 0.33};
            default -> new double[]{0.75, 0.5, 0.25};
        };
    }

    private double heartPct() { return Math.max(0, SlayerDials.VAMPIRE_HEART_PERCENT.get()); }

    private void hearts(Player p, double hearts, String cause) { hurtPercent(p, hearts * heartPct(), cause); }

    @Override
    void step() {
        Player o = owner();
        if (o == null) return;
        Location b = boss.getLocation();

        // Coagulation
        if (every(1.5) && o.getWorld() == boss.getWorld() && o.getLocation().distance(b) < 16) {
            double h = DRAIN[Math.min(DRAIN.length, tierNo) - 1];
            hearts(o, h, "coagulation");
            heal(max() * 0.004 * h);          // TUNE
            Fx.line(o.getLocation().add(0, 1, 0), b.clone().add(0, 1.4, 0), Fx.rgb(120, 0, 0), 0.5);
        }

        // Spectral Form
        if (maniaStart < 0 && o.getWorld() == boss.getWorld() && (every(10) || o.getLocation().distance(b) > 10)) {
            Fx.particle(b.clone().add(0, 1, 0), Particle.LARGE_SMOKE, 25, 0.6, 0.05);
            Fx.sound(b, Sound.ENTITY_BAT_TAKEOFF, 1f, 0.6f);
            teleportBoss(around(o.getLocation(), 2, 3));
        }

        if (maniaStart >= 0) tickMania(o);

        // Twin Claws
        if (tierNo >= 2 && maniaStart < 0 && clawsAt < 0 && every(7)) {
            clawsFrom = boss.getLocation();
            clawsDir = o.getLocation().toVector().subtract(clawsFrom.toVector()).setY(0);
            if (clawsDir.lengthSquared() < 0.01) clawsDir = clawsFrom.getDirection().setY(0);
            if (clawsDir.lengthSquared() < 1e-4) clawsDir = new Vector(0, 0, 1);
            clawsDir.normalize();
            clawsAt = ticks + 5;
            setImmobile(true);
            Fx.sound(b, Sound.ENTITY_RAVAGER_ATTACK, 1f, 0.6f);
        }
        if (clawsAt >= 0) {
            drawCone(clawsFrom, clawsDir, ticks >= clawsAt);
            if (ticks >= clawsAt) {
                clawsAt = -1;
                if (maniaStart < 0) setImmobile(false);      // a Mania that began meanwhile keeps it rooted
                double h = CLAWS[Math.min(CLAWS.length, tierNo) - 1];
                for (Player p : playersNear(clawsFrom, 5)) {
                    if (inCone(clawsFrom, clawsDir, p.getLocation())) {
                        hearts(p, h, "twin_claws");
                        Fx.sound(p, Sound.ENTITY_PLAYER_HURT, 1f, 0.6f);
                    }
                }
                if (ichorAt != null && inCone(clawsFrom, clawsDir, ichorAt)) {
                    removeIchor();
                    Msg.send(o, "&aThe Twin Claws shattered the Blood Ichor!");
                }
            }
        }

        // Stone Wrath
        if (tierNo >= 2 && every(30)) {
            for (int i = 0; i < 2; i++) spawnMinion("slayer_clotgoyle", around(b, 2, 4).add(0, 2, 0), Math.max(5000, max() * 0.02));
            Fx.warn(o, "&4&lSTONE WRATH", "&7Blood Gargoyles sap your health!");
        }
        int gargoyles = liveMinions("slayer_clotgoyle");
        if (gargoyles > 0) mod.debuffs.sap(o, 10.0 * gargoyles, 1500);

        // Spring Trap
        if (tierNo >= 3 && springStart < 0 && maniaStart < 0 && every(25)) {
            // flat facing; looking straight up or down has no horizontal part (normalize() of zero = NaN location)
            Vector face = o.getLocation().getDirection().setY(0);
            if (face.lengthSquared() < 1e-4) face = new Vector(0, 0, 1);
            spring = spawnMinion("slayer_spring", ground(o.getLocation().clone().add(face.normalize().multiply(1.5)).add(0, 0.5, 0)), 6);
            if (spring != null) {
                if (spring instanceof Slime s) s.setSize(2);
                springStart = ticks;
                springCount = 0;
                springFirst = true;
                Fx.warn(o, "&e&lSPRING TRAP", "&7Alternate &aLEFT &7and &aRIGHT &7clicks on it!");
            }
        }
        if (springStart >= 0) {
            if (spring == null || !spring.isValid() || springCount >= 6) {
                if (spring != null && spring.isValid()) Sky.mobs().remove(spring);
                if (springCount >= 6) Msg.send(o, "&aYou disarmed the spring!");
                springStart = -1;
                spring = null;
            } else if (ticks - springStart >= 60) {
                Sky.mobs().remove(spring);
                spring = null;
                springStart = -1;
                Fx.particle(o.getLocation(), Particle.EXPLOSION, 2, 0.3, 0);
                Sky.vitals().kill(o, "slayer:spring_trap");
            } else {
                Fx.particle(spring.getLocation().add(0, 1, 0), Particle.CRIT, 4, 0.3, 0.05);
            }
        }

        // Command
        if (tierNo >= 3 && commandUntil < 0 && maniaStart < 0 && every(18)) {
            String[] cmds = {"JUMP", "SNEAK", "LOOK UP", "LOOK DOWN"};
            command = cmds[ThreadLocalRandom.current().nextInt(cmds.length)];
            commandDone = false;
            commandUntil = ticks + (tierNo >= 4 ? 8 : 10);
            Fx.title(o, "&c&l" + command + "!", "&7Now!", 0, 30, 5);
            Fx.sound(o, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
        }
        if (commandUntil >= 0) {
            float pitch = o.getLocation().getPitch();
            if ("LOOK UP".equals(command) && pitch < -55) commandDone = true;
            if ("LOOK DOWN".equals(command) && pitch > 55) commandDone = true;
            if (commandDone) {
                commandUntil = -1;
                Fx.sound(o, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
            } else if (ticks >= commandUntil) {
                commandUntil = -1;
                hearts(o, COMMAND[Math.min(COMMAND.length, tierNo) - 1], "command");
                Msg.send(o, "&cYou ignored the command!");
            }
        }

        // Blood Ichor (T5)
        if (tierNo >= 5 && ichorStart < 0 && every(30)) {
            ichorAt = around(b, 3, 5);
            try {
                ichor = b.getWorld().spawn(ichorAt.clone().add(-0.5, 0, -0.5), BlockDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setBlock(Material.REDSTONE_BLOCK.createBlockData());
                    d.setGlowing(true);
                });
                props.add(ichor);
            } catch (RuntimeException ignored) { }
            ichorStart = ticks;
            Fx.warn(o, "&4&lBLOOD ICHOR", "&7Bait its Twin Claws into the ichor!");
        }
        if (ichorStart >= 0) {
            if (ichorAt != null) Fx.particle(ichorAt.clone().add(0, 1, 0), Particle.DRIPPING_LAVA, 3, 0.3, 0);
            if (ichorAt == null) {
                ichorStart = -1;
            } else if (ticks - ichorStart >= 100) {          // 25 s
                removeIchor();
                ichorStart = -1;
                heal(max() * 0.10);
                hearts(o, 5, "blood_ichor");
                Msg.send(o, "&4The Blood Ichor fed the Bloodfiend!");
            }
        }
    }

    private void removeIchor() {
        if (ichor != null) {
            try { ichor.remove(); } catch (RuntimeException ignored) { }
            props.remove(ichor);
        }
        ichor = null;
        ichorAt = null;
        ichorStart = -1;
    }

    private void tickMania(Player o) {
        long t = ticks - maniaStart;
        if (ring == null || t % 24 == 0) {
            ring = around(o.getLocation(), 3, 6);
            Fx.sound(ring, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f);
        }
        Fx.ring(ring, 2.5, Fx.rgb(60, 220, 60), 30);
        Fx.particle(boss.getLocation().add(0, 2, 0), Particle.DRIPPING_LAVA, 4, 0.5, 0);
        if (t % 8 == 0) {
            for (Player p : playersNear(boss.getLocation(), 40)) {
                Location pl = p.getLocation();
                double dx = pl.getX() - ring.getX(), dz = pl.getZ() - ring.getZ();
                if (dx * dx + dz * dz > 2.5 * 2.5) {
                    hearts(p, MANIA[Math.min(MANIA.length, tierNo) - 1], "mania");
                    Sky.vitals().flash(p, "&4&lMANIA! &7Get in the green ring!", 20);
                }
            }
        }
        if (t >= 104) {      // 26 s
            maniaStart = -1;
            ring = null;
            immune = null;
            setImmobile(false);
            Msg.send(o, "&aThe Mania fades.");
        }
    }

    private boolean inCone(Location from, Vector dir, Location at) {
        Vector to = at.toVector().subtract(from.toVector()).setY(0);
        double len = to.length();
        if (len > 4.5 || len < 0.01) return len <= 0.01;
        return dir.angle(to.normalize()) < Math.toRadians(50);
    }

    private void drawCone(Location from, Vector dir, boolean hit) {
        for (double d = 1; d <= 4.5; d += 0.9) {
            for (int a = -50; a <= 50; a += 25) {
                Vector v = dir.clone().rotateAroundY(Math.toRadians(a)).multiply(d);
                Location l = from.clone().add(v).add(0, 0.15, 0);
                if (hit) Fx.particle(l, Particle.SWEEP_ATTACK, 1, 0, 0);
                else Fx.dust(l, Fx.rgb(200, 0, 0), 1.2f, 1, 0.05);
            }
        }
    }

    @Override
    void onHit(DamageContext ctx) {
        Player p = ctx.playerAttacker();
        // mania thresholds
        if (maniaDone < maniaAt.length && clampAt(ctx, maniaAt[maniaDone])) {
            maniaDone++;
            maniaStart = ticks;
            immune = "Mania";
            setImmobile(true);
            Player o = owner();
            if (o != null) Fx.warn(o, "&4&lMANIA", "&7Stand in the green ring!");
            return;
        }
        boolean stake = p != null && ctx.weapon != null && isStake(Sky.items().idOf(ctx.weapon));
        double floor = max() * 0.20;
        if (stake) {
            if (hp() <= floor + 0.5) {
                ctx.finalDamage = hp();
                executed = true;
                mod.items.stakeKill(p, Sky.items().idOf(ctx.weapon));
                Fx.title(p, "&4&lEXECUTED!", "", 0, 30, 10);
                Fx.sound(p, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.5f);
            } else {
                ctx.finalDamage = 0;    // "it does not actually hurt the vampire"
            }
            return;
        }
        if (hp() - ctx.finalDamage <= floor) {
            boolean byHand = p != null && mod.level(p, type) < SlayerDials.STAKE_REQUIRED_LEVEL.get() && hp() <= floor + 0.5;
            if (byHand) {
                ctx.finalDamage = hp();
                executed = true;
                return;
            }
            ctx.finalDamage = Math.max(0, hp() - floor);
            if (p != null) Sky.vitals().flash(p, "&4&lEXPOSED! &7Finish it with a &aSteak Stake&7!", 30);
        }
    }

    static boolean isStake(String id) {
        return "STEAK_STAKE".equals(id) || "SHARP_STEAK_STAKE".equals(id);
    }

    @Override
    void onStrike(Player target, DamageContext ctx) {
        // its melee is measured in hearts per second
        ctx.cancelled = true;
        hearts(target, tier.dps, "bite");
    }

    @Override
    void minionDamaged(LivingEntity m, DamageContext ctx) {
        var d = Sky.mobs().defOf(m);
        if (d == null) return;
        if (d.id.equals("slayer_spring")) {
            ctx.cancelled = true;
            springClick(ctx.playerAttacker(), true);
        }
        if (d.id.equals("slayer_ichor")) ctx.cancelled = true;
    }

    /** A left (hit) or right (interact) click on the spring. */
    void springClick(Player p, boolean left) {
        if (p == null || springStart < 0) return;
        if (!springFirst && left == springLastLeft) {
            Sky.vitals().flash(p, "&cAlternate! &7" + (left ? "RIGHT" : "LEFT") + " click next", 15);
            return;
        }
        springFirst = false;
        springLastLeft = left;
        springCount++;
        Fx.sound(p, Sound.BLOCK_PISTON_CONTRACT, 0.8f, 0.6f + springCount * 0.15f);
        Sky.vitals().flash(p, "&eSpring &a" + springCount + "&7/&a6", 15);
    }

    boolean isSpring(LivingEntity e) { return spring != null && e != null && spring.getUniqueId().equals(e.getUniqueId()); }

    void playerJumped(Player p) {
        if (commandUntil >= 0 && "JUMP".equals(command)) commandDone = true;
    }

    void playerSneaked(Player p) {
        if (commandUntil >= 0 && "SNEAK".equals(command)) commandDone = true;
    }

    @Override
    void onEnd() { removeIchor(); }
}
