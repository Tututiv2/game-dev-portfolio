package com.skyclone.mod.slayer;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One running slayer boss fight: the boss entity, its minions and props, the boss bar, the floating label, the quest
 * timer and the shared mechanics every boss uses (invulnerability, HP thresholds, true / %-of-max damage, healing).
 * Subclasses implement the per-boss state machines (research 04 sec 6.2).
 *
 * <p>{@link #step()} runs every 5 ticks; {@code ticks} counts those steps, so 4 steps = 1 second.
 */
abstract class BossFight {

    final SlayerModule mod;
    final UUID ownerId;
    final String ownerName;
    final SlayerCatalog.Type type;
    final SlayerCatalog.Tier tier;
    final int tierNo;
    LivingEntity boss;
    long ticks;
    final long startedMillis = System.currentTimeMillis();
    long deadlineMillis;
    BossBar bar;
    TextDisplay label;
    /** Non-null = the boss ignores damage; the text is shown to the player ("Web Cocoon"). */
    String immune;
    final Set<UUID> minions = new HashSet<>();
    final List<Entity> props = new ArrayList<>();
    final Map<UUID, Double> damageBy = new HashMap<>();
    int outOfRangeSteps;
    boolean ended;
    long lastImmuneMsg;
    /** Extra line on the label and boss bar (shield hits, attunement...). */
    String status = "";
    BossBar.Color barColor = BossBar.Color.RED;
    /** Where the boss was on the last tick it existed (to tell "it unloaded under me" from "I walked away"). */
    Location lastBossLocation;
    /** Co-op helpers who were shown the boss bar (hidden again when the fight ends). */
    private final Set<UUID> barViewers = new HashSet<>();

    BossFight(SlayerModule mod, Player owner, SlayerCatalog.Type type, SlayerCatalog.Tier tier) {
        this.mod = mod;
        this.ownerId = owner.getUniqueId();
        this.ownerName = owner.getName();
        this.type = type;
        this.tier = tier;
        this.tierNo = tier.tier;
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    /** Called once the boss entity exists. */
    void begin(LivingEntity boss, int seconds) {
        this.boss = boss;
        this.lastBossLocation = boss.getLocation();
        this.deadlineMillis = System.currentTimeMillis() + seconds * 1000L;
        Player o = owner();
        if (SlayerDials.COOP_HELP.get() && o != null) {
            String pid = Sky.profiles().profileId(o);
            Sky.mobs().setAllies(boss, p -> pid != null && pid.equals(Sky.profiles().profileId(p)));
        }
        try {
            bar = BossBar.bossBar(Text.c(barTitle()), 1f, barColor, BossBar.Overlay.NOTCHED_10);
            if (o != null) o.showBossBar(bar);
        } catch (RuntimeException ignored) { }
        spawnLabel();
        onBegin();
    }

    void onBegin() {}

    /** Every 5 ticks while the fight runs. */
    final void tick() {
        if (ended) return;
        ticks++;
        if (boss == null || !boss.isValid() || boss.isDead()) {
            mod.quests.bossVanished(this);
            return;
        }
        lastBossLocation = boss.getLocation();
        Player o = owner();
        if (o == null || !o.isOnline()) return;   // Quests handles the quit
        keepTarget(o);
        if (ticks % 4 == 0) {
            followOwner(o);
            refreshHud();
        }
        try {
            step();
        } catch (RuntimeException e) {
            Sky.log().log(java.util.logging.Level.WARNING, "[slayer] fight step threw for " + type.id + " T" + tierNo, e);
        }
        cleanMinions();
    }

    abstract void step();

    /** Remove everything this fight created. Idempotent. */
    void end() {
        if (ended) return;
        ended = true;
        Player o = owner();
        if (bar != null && o != null) {
            try { o.hideBossBar(bar); } catch (RuntimeException ignored) { }
        }
        if (bar != null) {
            for (UUID id : barViewers) {
                Player h = Bukkit.getPlayer(id);
                if (h != null) { try { h.hideBossBar(bar); } catch (RuntimeException ignored) { } }
            }
        }
        barViewers.clear();
        if (label != null) {
            try { label.remove(); } catch (RuntimeException ignored) { }
        }
        for (UUID id : new ArrayList<>(minions)) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity le && Sky.mobs().isSkyMob(le)) Sky.mobs().remove(le);
            else if (e != null) e.remove();
            mod.fightIndex.remove(id);
        }
        minions.clear();
        for (Entity e : props) {
            try { e.remove(); } catch (RuntimeException ignored) { }
        }
        props.clear();
        if (boss != null) {
            restoreAi();
            mod.fightIndex.remove(boss.getUniqueId());
        }
        onEnd();
    }

    void onEnd() {}

    // ---- hooks from the behaviour ----------------------------------------------------------------

    /** The boss is about to take a hit. */
    void damaged(DamageContext ctx) {
        Player hitter = ctx.playerAttacker();
        if (immune != null) {
            ctx.cancelled = true;
            if (hitter != null && System.currentTimeMillis() - lastImmuneMsg > 900) {
                lastImmuneMsg = System.currentTimeMillis();
                Sky.vitals().flash(hitter, "&c&lIMMUNE &7(" + immune + ")", 20);
                Fx.sound(hitter, Sound.BLOCK_ANVIL_LAND, 0.3f, 2f);
            }
            return;
        }
        onHit(ctx);
        if (!ctx.cancelled && hitter != null && !hitter.getUniqueId().equals(ownerId)) {
            damageBy.merge(hitter.getUniqueId(), Math.max(0, ctx.finalDamage), Double::sum);
            if (bar != null && barViewers.add(hitter.getUniqueId())) {
                try { hitter.showBossBar(bar); } catch (RuntimeException ignored) { }
            }
        }
    }

    void onHit(DamageContext ctx) {}

    /** The boss is about to hit a player. */
    void attacking(Player target, DamageContext ctx) {
        if (mechanic) {                 // our own ability damage is not a melee strike
            // Tagged so potion Dodge/Agility (alchemy) does not let a God Potion dodge Life Drain or Pestilence:
            // wave-4 loops playtest, a T3 Ghoul with a God Potion (64% dodge) never drained once.
            ctx.tags.add("ability");
            ctx.tags.add("slayer_mechanic");
            return;
        }
        ctx.multiplicative *= Math.max(0, SlayerDials.BOSS_DAMAGE_SCALE.get());
        onStrike(target, ctx);
    }

    /** True while a mechanic of this fight is dealing damage (so onAttack hooks skip it). */
    private boolean mechanic;

    void onStrike(Player target, DamageContext ctx) {}

    void minionDamaged(LivingEntity m, DamageContext ctx) {}

    void minionAttacking(LivingEntity m, Player target, DamageContext ctx) {
        ctx.multiplicative *= Math.max(0, SlayerDials.BOSS_DAMAGE_SCALE.get());
    }

    void minionDied(LivingEntity m) {}

    /** The boss died (MobKill). Return false to veto completion (a phase change replaced the entity). */
    boolean bossDied() { return true; }

    // ---- helpers ---------------------------------------------------------------------------------

    Player owner() {
        Player p = Bukkit.getPlayer(ownerId);
        return p != null && p.isOnline() ? p : null;
    }

    double hp() { return boss == null ? 0 : Sky.mobs().health(boss); }

    double max() { return boss == null ? 1 : Math.max(1, Sky.mobs().maxHealth(boss)); }

    double frac() { return hp() / max(); }

    long secondsLeft() { return Math.max(0, (deadlineMillis - System.currentTimeMillis()) / 1000); }

    long elapsedSeconds() { return ticks / 4; }

    boolean every(double seconds) {
        long steps = Math.max(1, Math.round(seconds * 4));
        return ticks % steps == 0;
    }

    double mech() { return Math.max(0, SlayerDials.MECHANIC_DAMAGE_SCALE.get()); }

    void heal(double amount) {
        if (boss == null || amount <= 0) return;
        Sky.mobs().setHealth(boss, Math.min(max(), hp() + amount));
    }

    /** True damage (True Defense still applies). Returns the damage actually dealt. */
    double hurtTrue(Player p, double amount) {
        if (p == null || amount <= 0) return 0;
        mechanic = true;
        try { return Sky.combat().damagePlayer(p, amount * mech(), boss, DamageContext.Kind.TRUE); } finally { mechanic = false; }
    }

    /** Normal damage (Defense applies). Returns the damage actually dealt. */
    double hurt(Player p, double amount) {
        if (p == null || amount <= 0) return 0;
        mechanic = true;
        try { return Sky.combat().damagePlayer(p, amount * mech(), boss, DamageContext.Kind.MOB); } finally { mechanic = false; }
    }

    /** Damage as a percentage of the victim's max health; no defense (research 6.1 PercentMaxHpDamage). */
    void hurtPercent(Player p, double percent, String cause) {
        if (p == null || percent <= 0) return;
        double amt = Sky.vitals().maxHealth(p) * percent / 100.0 * mech();
        Sky.vitals().damage(p, Math.max(1, amt), "slayer:" + cause);
    }

    /** Players this fight may hurt (owner + allies) within r blocks of a point. */
    List<Player> playersNear(Location at, double r) {
        List<Player> out = new ArrayList<>();
        if (at == null || at.getWorld() == null) return out;
        double r2 = r * r;
        for (Player p : at.getWorld().getPlayers()) {
            if (p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
            if (!p.getUniqueId().equals(ownerId) && !ally(p)) continue;
            if (p.getLocation().distanceSquared(at) <= r2) out.add(p);
        }
        return out;
    }

    boolean ally(Player p) {
        if (!SlayerDials.COOP_HELP.get()) return false;
        Player o = owner();
        if (o == null) return false;
        String a = Sky.profiles().profileId(o);
        return a != null && a.equals(Sky.profiles().profileId(p));
    }

    /**
     * Keeps a hit from taking the boss below {@code fraction} of max health; returns true when this hit reaches it
     * (so a phase can trigger exactly once at its threshold and is never skipped by a big hit).
     */
    boolean clampAt(DamageContext ctx, double fraction) {
        double floor = max() * fraction;
        double now = hp();
        if (now <= floor) return false;
        if (now - ctx.finalDamage <= floor) {
            ctx.finalDamage = Math.max(0, now - floor);
            return true;
        }
        return false;
    }

    void setImmobile(boolean frozen) {
        if (boss instanceof Mob m) {
            try { m.setAI(!frozen); } catch (RuntimeException ignored) { }
        }
    }

    void restoreAi() {
        if (boss instanceof Mob m && boss.isValid()) {
            try { m.setAI(true); } catch (RuntimeException ignored) { }
        }
    }

    void setSpeedMultiplier(double mult) {
        if (boss == null) return;
        AttributeInstance a = boss.getAttribute(Attribute.MOVEMENT_SPEED);
        if (a == null) return;
        if (baseSpeed < 0) baseSpeed = a.getBaseValue();
        a.setBaseValue(baseSpeed * mult);
    }

    private double baseSpeed = -1;

    void teleportBoss(Location to) {
        if (boss == null || to == null) return;
        try {
            if (boss.teleport(to)) return;
            // older servers refuse to move an entity carrying passengers: hop off, move, hop back on
            List<Entity> riders = new ArrayList<>(boss.getPassengers());
            for (Entity r : riders) boss.removePassenger(r);
            boss.teleport(to);
            for (Entity r : riders) {
                r.teleport(to);
                boss.addPassenger(r);
            }
        } catch (RuntimeException ignored) { }
    }

    /** The first standable spot at or below {@code at} (max 6 down), else {@code at}. */
    static Location ground(Location at) {
        if (at == null || at.getWorld() == null) return at;
        World w = at.getWorld();
        Location l = at.clone();
        for (int i = 0; i < 6; i++) {
            Block b = w.getBlockAt(l.getBlockX(), l.getBlockY() - 1, l.getBlockZ());
            if (b.getType().isSolid()) {
                l.setY(b.getY() + 1);
                return l;
            }
            l.subtract(0, 1, 0);
        }
        return at;
    }

    /** A random spot on a ring around {@code c}. */
    static Location around(Location c, double minR, double maxR) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double a = r.nextDouble(Math.PI * 2), d = r.nextDouble(minR, Math.max(minR + 0.01, maxR));
        return ground(c.clone().add(Math.cos(a) * d, 1.5, Math.sin(a) * d));
    }

    LivingEntity spawnMinion(String mobId, Location at, double health) {
        Player o = owner();
        LivingEntity m;
        try {
            m = Sky.mobs().spawn(mobId, at, o);
        } catch (RuntimeException e) {
            Sky.log().warning("[slayer] could not spawn minion " + mobId + ": " + e.getMessage());
            return null;
        }
        if (m == null) return null;
        if (health > 0) Sky.mobs().setMaxHealth(m, health);
        if (SlayerDials.COOP_HELP.get() && o != null) {
            String pid = Sky.profiles().profileId(o);
            Sky.mobs().setAllies(m, p -> pid != null && pid.equals(Sky.profiles().profileId(p)));
        }
        if (m instanceof Mob mm && o != null) mm.setTarget(o);
        minions.add(m.getUniqueId());
        mod.fightIndex.put(m.getUniqueId(), this);
        return m;
    }

    int liveMinions(String mobId) {
        int n = 0;
        for (UUID id : minions) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity le && le.isValid() && !le.isDead()) {
                var d = Sky.mobs().defOf(le);
                if (d != null && (mobId == null || d.id.equals(mobId))) n++;
            }
        }
        return n;
    }

    List<LivingEntity> minionsOf(String mobId) {
        List<LivingEntity> out = new ArrayList<>();
        for (UUID id : minions) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity le && le.isValid() && !le.isDead()) {
                var d = Sky.mobs().defOf(le);
                if (d != null && (mobId == null || d.id.equals(mobId))) out.add(le);
            }
        }
        return out;
    }

    private void cleanMinions() {
        if (ticks % 8 != 0) return;
        Iterator<UUID> it = minions.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity e = Bukkit.getEntity(id);
            if (e == null || !e.isValid() || e.isDead()) {
                it.remove();
                mod.fightIndex.remove(id);
            }
        }
    }

    // ---- hud ---------------------------------------------------------------------------------------

    String bossTitle() {
        return "&" + type.color + "&l☠ &" + type.color + type.bossName(tierNo) + " " + SlayerMath.roman(tierNo);
    }

    String barTitle() {
        String hp = Text.shortNum(hp());
        String s = bossTitle() + " &8| &c" + hp + "&8/&c" + Text.shortNum(max()) + "❤ &8| &e⌚ " + SlayerMath.clock(secondsLeft());
        if (immune != null) s += " &8| &f&lIMMUNE";
        else if (!status.isEmpty()) s += " &8| " + status;
        return s;
    }

    void refreshHud() {
        if (bar != null) {
            try {
                bar.name(Text.c(barTitle()));
                bar.progress((float) Math.max(0, Math.min(1, frac())));
                bar.color(immune != null ? BossBar.Color.WHITE : barColor);
            } catch (RuntimeException ignored) { }
        }
        if (label != null && label.isValid()) {
            StringBuilder sb = new StringBuilder();
            if (immune != null) sb.append("&f&lIMMUNE &7(").append(immune).append(")\n");
            else if (!status.isEmpty()) sb.append(status).append("\n");
            sb.append("&c").append(SlayerMath.clock(secondsLeft())).append("\n");
            sb.append("&eSpawned by: &b").append(ownerName);
            try { label.text(Text.c(sb.toString())); } catch (RuntimeException ignored) { }
        } else if (boss != null && boss.isValid() && ticks % 20 == 0) {
            spawnLabel();
        }
    }

    private void spawnLabel() {
        if (boss == null || !boss.isValid()) return;
        try {
            Location at = boss.getLocation().add(0, boss.getHeight() + 0.6, 0);
            label = boss.getWorld().spawn(at, TextDisplay.class, td -> {
                td.setPersistent(false);
                td.setBillboard(Display.Billboard.CENTER);
                td.setSeeThrough(false);
                td.setShadowed(true);
                td.setDefaultBackground(false);
                td.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
                td.setTransformation(new Transformation(new Vector3f(0f, 0.45f, 0f), new AxisAngle4f(),
                        new Vector3f(0.9f, 0.9f, 0.9f), new AxisAngle4f()));
                td.text(Text.c("&eSpawned by: &b" + ownerName));
            });
            boss.addPassenger(label);
        } catch (RuntimeException e) {
            label = null;
        }
    }

    private void keepTarget(Player o) {
        if (!(boss instanceof Mob m) || !m.hasAI()) return;
        LivingEntity t = m.getTarget();
        if (t == null || !t.isValid() || t.isDead() || !(t instanceof Player tp) || (!tp.getUniqueId().equals(ownerId) && !ally(tp))) {
            if (o.getWorld() == boss.getWorld()) m.setTarget(o);
        }
    }

    private void followOwner(Player o) {
        if (o.getWorld() != boss.getWorld()) return;
        double d = o.getLocation().distance(boss.getLocation());
        if (d > Math.max(8, SlayerDials.FOLLOW_RANGE.get()) && d < 200) {
            Location to = around(o.getLocation(), 2, 4);
            Fx.particle(boss.getLocation().add(0, 1, 0), Particle.LARGE_SMOKE, 20, 0.4, 0.02);
            teleportBoss(to);
            Fx.particle(to.clone().add(0, 1, 0), Particle.LARGE_SMOKE, 20, 0.4, 0.02);
        }
    }

    /** For subclasses that need a vanilla look for a block display. */
    static org.bukkit.block.data.BlockData blockData(Material m) {
        return m.createBlockData();
    }
}
