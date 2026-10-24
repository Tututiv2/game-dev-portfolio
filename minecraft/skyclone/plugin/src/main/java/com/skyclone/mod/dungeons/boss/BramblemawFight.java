package com.skyclone.mod.dungeons.boss;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.dungeons.DDials;
import com.skyclone.mod.dungeons.Fx;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.ext.BossContext;
import com.skyclone.mod.dungeons.gen.arena.GroveArena;
import com.skyclone.mod.dungeons.run.DungeonRun;
import com.skyclone.mod.dungeons.run.RunManager;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Floor IV / Master IV: Bramblemaw (research 04 sec 3.13, Thorn). It cannot be hurt by anything but a Spirit Bow:
 * 4 hits (6 on Master Mode). It keeps calling spirit animals - Bull (charges), Wolf (packs), Bat, Rabbit (throws you
 * up), Chicken (leaves a mine where it dies) and Sheep (explodes) - and after 25 kills (30 on M4) the Spirit Bear
 * comes. Kill the bear and its Spirit Bow appears in a player's hands: one arrow, 20 seconds. A hit stuns Bramblemaw
 * for 5 s and it speeds up after. The green circles heal players; the orange ones burn animals.
 */
public final class BramblemawFight extends FightBase {

    public static final String NAME = "Bramblemaw";
    static final String[] ANIMALS = {"bull", "wolf", "bat", "rabbit", "chicken", "sheep"};

    static final class S {
        LivingEntity boss;
        boolean ready;
        int hits, need, kills, killsNeeded;
        final Map<UUID, String> animals = new HashMap<>();
        LivingEntity bear;
        long stunnedUntil = -1, hittableAt;
        int spawnEvery = 8;
        UUID bowHolder;
        long bowUntil;
    }

    @Override public BossArena arena() { return GroveArena.INSTANCE; }

    @Override
    public void start(BossContext c) {
        S s = st(c, S::new);
        s.need = c.master() ? DDials.THORN_HITS_MASTER.get() : DDials.THORN_HITS.get();
        s.killsNeeded = c.master() ? Math.round(DDials.SPIRIT_KILLS.get() * 1.2f) : DDials.SPIRIT_KILLS.get();
        s.boss = spawnBoss(c, "dboss_bramblemaw", c.point("boss"), s.need, bossDmg(c));
        if (s.boss == null) { c.complete(); return; }
        Kit.scale(s.boss, 1.4);
        Hooks.onArrows(c, (p, victim, block) -> spiritHit(c, p, victim));
        Kit.announce(c, "&2&lBRAMBLEMAW", "&7Only a Spirit Bow can hurt it.", Sound.ENTITY_RAVAGER_ROAR);
        Kit.dialogue(c, NAME, List.of("The grove remembers every axe. Every boot. Every one of YOU.",
                "Run, little saplings. My children are hungry."), () -> s.ready = true);
    }

    @Override
    public void tick(BossContext c) {
        S s = st(c, S::new);
        if (s.boss == null || !Kit.alive(s.boss) || !s.ready) return;
        long t = c.steps();
        boolean stunned = t < s.stunnedUntil;
        c.bossBar("&2&l" + NAME + " &7- &aSpirit hits: &e" + s.hits + "&7/" + s.need + " &8| &7Animals " + s.kills + "/" + s.killsNeeded
                + (stunned ? " &e&lSTUNNED" : ""), Math.max(0.02f, 1f - s.hits / (float) s.need), stunned ? BossBar.Color.YELLOW : BossBar.Color.GREEN);
        circles(c, s, t);
        if (s.bowHolder != null && System.currentTimeMillis() <= s.bowUntil) {
            // the holder died, quit or left: the bow passes on (review fix - it used to vanish with them, costing a bear)
            Player h = org.bukkit.Bukkit.getPlayer(s.bowHolder);
            if (FIRED.contains(s.bowHolder)) { /* the arrow is already in the air: nothing to pass on */ }
            else if (h == null || !c.alive().contains(h)) {
                if (h != null) takeBow(h);
                long until = s.bowUntil;
                s.bowHolder = null;
                Player to = Kit.nearest(c, s.boss.getLocation());
                if (to != null) { giveBow(c, s, to); s.bowUntil = until; }
            }
        }
        if (s.bowHolder != null && System.currentTimeMillis() > s.bowUntil) {
            Player h = org.bukkit.Bukkit.getPlayer(s.bowHolder);
            if (h != null) { takeBow(h); Msg.send(h, "&7The Spirit Bow fades away..."); }
            s.bowHolder = null;
        }
        if (stunned) { Fx.particle(s.boss.getEyeLocation().add(0, 0.6, 0), Particle.WAX_OFF, 4, 0.4, 0); return; }
        // spirit animals keep coming
        s.animals.keySet().removeIf(u -> { Entity e = org.bukkit.Bukkit.getEntity(u); return e == null || !e.isValid() || e.isDead(); });
        if (t % s.spawnEvery == 0 && s.animals.size() < (c.master() ? 10 : 8) && s.bear == null) {
            String k = ANIMALS[rng.nextInt(ANIMALS.length)];
            Location at = c.point("animal." + rng.nextInt(8));
            double hpMul = c.master() ? 30 : 1;
            double base = switch (k) { case "bull" -> 90_000; case "wolf" -> 60_000; case "sheep" -> 40_000; case "bat" -> 25_000; default -> 30_000; };
            double dmg = switch (k) { case "bull" -> 2_400; case "wolf" -> 1_800; case "sheep" -> 3_000; case "bat" -> 1_200; default -> 900; };
            LivingEntity a = c.spawnExact("dboss_spirit_" + k, at, base * hpMul, dmg * (c.master() ? 20 : 1));
            if (a != null) { s.animals.put(a.getUniqueId(), k); Fx.particle(at, Particle.SOUL, 15, 0.4, 0.05); }
        }
        // Bramblemaw itself: thorn volleys
        if (t % 20 == 10) {
            Player p = pick(c, rng);
            if (p != null) Kit.bolt(c, s.boss.getEyeLocation(), p.getEyeLocation(), 1.0, 45, 1.2, Particle.SPORE_BLOSSOM_AIR, Color.fromRGB(40, 160, 40),
                    q -> Kit.hit(q, bossDmg(c) * 0.4));
        }
        if (t % 4 == 0) animalTricks(c, s, t);
        if (s.bear == null && s.kills >= s.killsNeeded) {
            s.kills = 0;
            Location at = c.point("animal." + rng.nextInt(8));
            s.bear = c.spawnExact("dboss_spirit_bear", at, Kit.nm(c, 5_000_000, 180_000_000), Kit.nm(c, 4_000, 80_000));
            Kit.announce(c, "&f&lTHE SPIRIT BEAR", "&7Slay it to claim the Spirit Bow!", Sound.ENTITY_POLAR_BEAR_WARNING);
        }
    }

    private void circles(BossContext c, S s, long t) {
        if (t % 4 != 0) return;
        for (int i = 0; i < 2; i++) {
            Location g = c.point("circle.green." + i), o = c.point("circle.orange." + i);
            if (g != null) {
                Fx.ring(g, GroveArena.CIRCLE_R, Color.LIME, 24);
                for (Player p : Kit.within(c, g, GroveArena.CIRCLE_R)) Sky.vitals().heal(p, Sky.vitals().maxHealth(p) * 0.02);
            }
            if (o != null) {
                Fx.ring(o, GroveArena.CIRCLE_R, Color.ORANGE, 24);
                for (UUID u : s.animals.keySet()) {
                    Entity e = org.bukkit.Bukkit.getEntity(u);
                    if (e instanceof LivingEntity le && Kit.alive(le) && Kit.flat(le.getLocation(), o) <= GroveArena.CIRCLE_R) {
                        Sky.combat().rawDamage(le, Kit.max(le) * 0.05, null, DamageContext.Kind.TRUE);
                        le.setFireTicks(20);
                    }
                }
            }
        }
    }

    private void animalTricks(BossContext c, S s, long t) {
        for (Map.Entry<UUID, String> en : s.animals.entrySet()) {
            Entity e = org.bukkit.Bukkit.getEntity(en.getKey());
            if (!(e instanceof LivingEntity a) || !Kit.alive(a)) continue;
            Player near = Kit.nearest(c, a.getLocation());
            if (near == null) continue;
            double d = near.getLocation().distance(a.getLocation());
            switch (en.getValue()) {
                case "bull" -> {
                    if (t % 12 == 0 && d < 16) {
                        Vector v = near.getLocation().toVector().subtract(a.getLocation().toVector()).setY(0);
                        if (v.lengthSquared() > 0.1) a.setVelocity(v.normalize().multiply(1.5).setY(0.25));
                        Kit.later(c, 8, () -> { if (Kit.alive(a)) for (Player q : Kit.within(c, a.getLocation(), 2.2)) Kit.hit(q, Kit.nm(c, 2_400, 48_000) * 0.75); });
                    }
                }
                case "rabbit" -> {
                    if (d < 2.5) { near.setVelocity(near.getVelocity().setY(1.3)); Fx.particle(near.getLocation(), Particle.CLOUD, 10, 0.3, 0.05); }
                }
                case "sheep" -> {
                    if (d < 3) {
                        Location at = a.getLocation();
                        Sky.mobs().remove(a);
                        Kit.circle(c, at, 3.5, 16, Color.WHITE, q -> Kit.hit(q, Kit.nm(c, 3_000, 60_000)), null);
                    }
                }
                case "bat" -> {
                    if (d < 2 && !near.isOnGround()) Kit.hit(near, Kit.nm(c, 1_200, 24_000));
                }
                default -> { }
            }
        }
    }

    // ---- the Spirit Bow -------------------------------------------------------------------------------------

    /**
     * A plain vanilla bow with the spirit and run-only tags. (Review fix: it used to be the weapons module's
     * BOSS_SPIRIT_BOW sky item - a real, sellable weapon a player could walk out of the run with.)
     */
    static ItemStack spiritBow() {
        ItemStack bow = Hooks.carried(Material.BOW, "&d&lSpirit Bow",
                List.of("&7One arrow. Make it count.", "&7Shoot it at &2Bramblemaw&7!", "", "&cVanishes after one shot or 20 seconds."), "spirit_bow");
        ItemMeta m = bow.getItemMeta();
        m.getPersistentDataContainer().set(Hooks.SPIRIT, PersistentDataType.BYTE, (byte) 1);
        m.setUnbreakable(true);
        bow.setItemMeta(m);
        return bow;
    }

    static void takeBow(Player p) {
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it == null) continue;
            if ((it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(Hooks.SPIRIT, PersistentDataType.BYTE))
                    || "spirit_arrow".equals(Hooks.carryKind(it))) p.getInventory().setItem(i, null);
        }
    }

    /** The bow has been fired: it is used up (the arrow flies on). */
    static void bowFired(Player p, ItemStack bow) {
        FIRED.add(p.getUniqueId());
        org.bukkit.Bukkit.getScheduler().runTask(Sky.plugin(), () -> takeBow(p));
    }

    /** Holders whose one arrow has been loosed (so a holder dying after the shot does not pass on a second bow). */
    static final java.util.Set<UUID> FIRED = new java.util.HashSet<>();

    private void giveBow(BossContext c, S s, Player p) {
        if (p == null) return;
        takeBow(p);
        FIRED.remove(p.getUniqueId());
        // a full inventory must not cost the party the bow: whatever sits in the slot it needs is dropped for the
        // player (owner-protected), never deleted
        int held = p.getInventory().getHeldItemSlot();
        place(p, spiritBow(), held);
        place(p, Hooks.carried(Material.ARROW, "&dSpirit Arrow", List.of("&7For the Spirit Bow."), "spirit_arrow"), (held + 1) % 9);
        s.bowHolder = p.getUniqueId();
        s.bowUntil = System.currentTimeMillis() + 20_000;
        c.title("&d" + p.getName() + " holds the Spirit Bow!", "&7Shoot Bramblemaw while it is not stunned.");
        Kit.sound(c, Sound.ITEM_TRIDENT_RETURN, 1.4f);
    }

    /** Add to the inventory; if it is full, empty {@code fallbackSlot} (its item is dropped for the player) and use it. */
    private static void place(Player p, ItemStack it, int fallbackSlot) {
        if (p.getInventory().addItem(it).isEmpty()) return;
        ItemStack old = p.getInventory().getItem(fallbackSlot);
        if (RunManager.isMap(old)) { fallbackSlot = (fallbackSlot + 1) % 9; old = p.getInventory().getItem(fallbackSlot); }
        p.getInventory().setItem(fallbackSlot, it);
        if (old != null && !old.getType().isAir()) Sky.loot().dropFor(p, p.getLocation(), old);
    }

    private void spiritHit(BossContext c, Player p, Entity victim) {
        S s = st(c, S::new);
        if (!is(s.boss, victim)) return;
        long t = c.steps();
        if (t < s.hittableAt) { Msg.send(p, "&cBramblemaw shrugged the arrow off - wait for it to recover!"); return; }
        s.hits++;
        s.bowHolder = null;
        s.stunnedUntil = t + 20;
        s.hittableAt = t + 24;
        s.spawnEvery = Math.max(3, s.spawnEvery - 1);
        Fx.particle(s.boss.getEyeLocation(), Particle.SOUL_FIRE_FLAME, 60, 1, 0.1);
        Kit.announce(c, "&a&lSPIRIT HIT! &7(" + s.hits + "/" + s.need + ")", "&7Bramblemaw is stunned!", Sound.ENTITY_RAVAGER_STUNNED);
        Sky.mobs().setHealth(s.boss, Math.max(0, s.need - s.hits));   // at 0 it dies: onMobKill completes the run
    }

    @Override
    public void onDamage(BossContext c, DamageContext dc) {
        S s = st(c, S::new);
        if (is(s.boss, dc.victim)) Kit.immune(dc, "Only a Spirit Bow can hurt it!");
    }

    @Override
    public void onMobKill(BossContext c, LivingEntity mob, MobDef def, Player killer) {
        S s = st(c, S::new);
        if (is(s.boss, mob)) { win(c, NAME, "The grove... will grow... back..."); return; }
        if (is(s.bear, mob)) {
            s.bear = null;
            Player to = killer != null && !c.isGhost(killer) ? killer : Kit.nearest(c, mob.getLocation());
            giveBow(c, s, to);
            return;
        }
        String k = s.animals.remove(mob.getUniqueId());
        if (k != null) {
            s.kills++;
            if (k.equals("chicken")) {
                Location at = mob.getLocation();
                c.broadcast("&eA spirit chicken left a mine!");
                Kit.circle(c, at, 2.5, 60, Color.YELLOW, q -> { Kit.hit(q, Kit.nm(c, 2_000, 40_000)); Kit.effect(q, PotionEffectType.SLOWNESS, 40, 1); },
                        () -> at.getWorld().strikeLightningEffect(at));
            }
        }
    }

    @Override
    public void stop(BossContext c) {
        S s = st(c, S::new);
        if (s.bowHolder != null) { Player h = org.bukkit.Bukkit.getPlayer(s.bowHolder); if (h != null) takeBow(h); FIRED.remove(s.bowHolder); }
        for (Player p : c.players()) { takeBow(p); FIRED.remove(p.getUniqueId()); }
        super.stop(c);
    }

    @SuppressWarnings("unused")
    private static DungeonRun runOf(Player p) { return RunManager.get().runOf(p); }
}
