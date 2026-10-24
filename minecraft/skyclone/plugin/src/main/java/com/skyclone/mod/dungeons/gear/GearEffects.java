package com.skyclone.mod.dungeons.gear;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import com.skyclone.mod.dungeons.DDials;
import com.skyclone.mod.dungeons.DungeonItems;
import com.skyclone.mod.dungeons.Fx;
import com.skyclone.mod.dungeons.Progress;
import com.skyclone.mod.dungeons.loot.RewardChests;
import com.skyclone.mod.dungeons.model.DClass;
import com.skyclone.mod.dungeons.run.DungeonRun;
import com.skyclone.mod.dungeons.run.RunManager;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the dungeon gear DOES (research 04 / NEU lore, numbers kept): set bonuses (Phalanx, Oathbound, Drill
 * Sergeant, Lord of the Halls, Volley/Marksman, Bone Collector, Ricochet, Ever Learning, Silent Hunt, Soul Whisper,
 * Witherborn), piece bonuses (wither damage reduction, arrow damage, Sinew, Mending/Vitality in dungeons, boss heads
 * doubled on their floor, the Stone/Metal/Steel aura), and the abilities that core cannot trigger because they sit on
 * armour: Last Laugh and Second Wind (cheat death), Spirit Glide and Eye Beam (sneak).
 */
public final class GearEffects implements Listener {

    private GearEffects() {}

    static final String[] WITHER_SETS = {"wither", "power_wither", "tank_wither", "speed_wither", "wise_wither"};

    private static final Map<UUID, Long> immuneUntil = new HashMap<>(), strUntil = new HashMap<>(), speedUntil = new HashMap<>(),
            sinewUntil = new HashMap<>(), sinewCd = new HashMap<>(), clownCd = new HashMap<>(), windCd = new HashMap<>(),
            impAt = new HashMap<>(), beamUntil = new HashMap<>(), beamStart = new HashMap<>();
    private static final Map<UUID, String> killsRun = new HashMap<>();
    private static final Map<UUID, Integer> killsInRun = new HashMap<>();
    private static boolean bouncing;

    public static void init() {
        Sky.stats().addProvider("dungeons_gear", GearEffects::stats);
        Sky.combat().addModifier("dungeons_gear", 200, GearEffects::modify);
        // armour abilities core never triggers (they are not in the main hand): handlers exist so lore and checks agree
        for (String id : List.of("dungeons_clownin", "dungeons_second_wind", "dungeons_spirit_glide", "dungeons_eye_beam"))
            Sky.abilities().register(id, use -> false);
        Sky.abilities().register("dungeons_infinileap", use -> DungeonItems.leap(use.player(), false));
        Bukkit.getPluginManager().registerEvents(new GearEffects(), Sky.plugin());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), GearEffects::second, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), GearEffects::beams, 10L, 10L);
    }

    static String id(ItemStack s) { return s == null ? null : Sky.items().idOf(s); }

    static ItemDef def(ItemStack s) { String i = id(s); return i == null ? null : Sky.items().def(i); }

    static DungeonRun run(Player p) { return RunManager.get() == null ? null : RunManager.get().runOf(p); }

    static boolean inRun(Player p) { DungeonRun r = run(p); return r != null && r.world != null && p.getWorld().equals(r.world); }

    static int pieces(Player p, String set) { return Sky.items().setPieces(p, set); }

    static boolean full(Player p, String set) { return set.equals(Sky.items().fullSet(p)); }

    // ---- stats ------------------------------------------------------------------------------------------------

    static void stats(Player p, StatMap into) {
        if (!Sky.profiles().isReady(p)) return;
        long now = System.currentTimeMillis();
        String fs = Sky.items().fullSet(p);
        boolean dungeon = inRun(p);
        if ("zombie_soldier".equals(fs)) {
            int n = 0;
            for (Player o : p.getWorld().getPlayers())
                if (!o.equals(p) && o.getLocation().distanceSquared(p.getLocation()) < 900 && "zombie_soldier".equals(Sky.items().fullSet(o))) n++;
            into.add(Stat.DEFENSE, 30 * n);
        }
        if ("zombie_knight".equals(fs) && "ZOMBIE_KNIGHT_SWORD".equals(id(p.getInventory().getItemInMainHand()))) into.add(Stat.DEFENSE, 50);
        if ("zombie_commander".equals(fs)) into.add(Stat.HEALTH, Math.min(500, RewardChests.data(p).zombieKills / 50 * 5));
        if (dungeon && ("zombie_lord".equals(fs) || "skeleton_lord".equals(fs))) {
            DungeonRun r = run(p);
            long min = Math.min(10, r.elapsedSeconds() / 60);
            if ("zombie_lord".equals(fs)) { into.add(Stat.STRENGTH, 10 * min); into.add(Stat.DEFENSE, 10 * min); }
            else { into.add(Stat.STRENGTH, 5 * min); into.add(Stat.CRIT_DAMAGE, 10 * min); }
        }
        if ("skeletor".equals(fs)) {
            long n = Math.min(100, RewardChests.data(p).bonelordKills / 10);
            into.add(Stat.STRENGTH, n);
            into.add(Stat.CRIT_DAMAGE, n);
        }
        if (dungeon && ("shadow_assassin".equals(fs) || "starred_shadow_assassin".equals(fs))) {
            DungeonRun r = run(p);
            if (r.id.equals(killsRun.get(p.getUniqueId()))) into.add(Stat.STRENGTH, killsInRun.getOrDefault(p.getUniqueId(), 0));
        }
        if (now < sinewUntil.getOrDefault(p.getUniqueId(), 0L))
            into.add(Stat.STRENGTH, 10 * (pieces(p, "shadow_assassin") + pieces(p, "starred_shadow_assassin")));
        if (now < strUntil.getOrDefault(p.getUniqueId(), 0L)) into.add(Stat.STRENGTH, 20 + Progress.cataLevel(p) / 2.0);
        if (now < speedUntil.getOrDefault(p.getUniqueId(), 0L)) into.add(Stat.SPEED, 50);
        // per worn piece: adaptive class bonuses and growth, mending in dungeons, boss heads on their floor
        DClass cls = Progress.selected(p);
        int cata = Progress.cataLevel(p);
        for (ItemStack s : p.getInventory().getArmorContents()) {
            ItemDef d = def(s);
            if (d == null || d.extra == null || !"dungeons".equals(str(d, "src"))) continue;
            if (d.setId != null && d.setId.endsWith("adaptive") && Sky.items().meetsRequirements(p, s, false)) {
                switch (cls) {
                    case BERSERK -> into.add(Stat.STRENGTH, 20);
                    case HEALER -> { into.add(Stat.MENDING, 5); into.add(Stat.HEALTH, 40); }
                    case MAGE -> into.add(Stat.INTELLIGENCE, 50);
                    case TANK -> into.add(Stat.DEFENSE, 30);
                    case ARCHER -> { into.add(Stat.CRIT_CHANCE, 5); into.add(Stat.CRIT_DAMAGE, 15); }
                }
                double grow = 0.02 * (cata / 5);
                if (grow > 0) d.stats.forEach((st, v) -> into.add(st, v * grow));
            }
            if (dungeon && d.extra.has("dungeonStats"))
                for (Map.Entry<String, com.google.gson.JsonElement> en : d.extra.getAsJsonObject("dungeonStats").entrySet()) {
                    Stat st = Stat.parse(en.getKey());
                    if (st != null) into.add(st, en.getValue().getAsDouble());
                }
            if (dungeon && d.extra.has("bossHead") && run(p).floor.number == d.extra.get("bossHead").getAsInt()) d.stats.forEach(into::add);
        }
    }

    static String str(ItemDef d, String k) { return d.extra != null && d.extra.has(k) ? d.extra.get(k).getAsString() : null; }

    // ---- damage ------------------------------------------------------------------------------------------------

    static boolean witherish(LivingEntity e) {
        if (e == null) return false;
        if (e.getType() == EntityType.WITHER_SKELETON || e.getType() == EntityType.WITHER) return true;
        MobDef d = Sky.mobs().defOf(e);
        return d != null && d.extra != null && d.extra.has("types") && d.extra.get("types").toString().contains("wither");
    }

    static boolean undead(LivingEntity e) {
        if (e == null) return false;
        MobDef d = Sky.mobs().defOf(e);
        if (d != null && d.extra != null && d.extra.has("types") && d.extra.get("types").toString().contains("undead")) return true;
        return switch (e.getType()) { case ZOMBIE, HUSK, DROWNED, SKELETON, STRAY, WITHER_SKELETON, ZOMBIFIED_PIGLIN, ZOMBIE_VILLAGER -> true; default -> false; };
    }

    static void modify(DamageContext ctx) {
        Player att = ctx.playerAttacker();
        if (att != null && Sky.profiles().isReady(att)) {
            if (ctx.kind == DamageContext.Kind.PROJECTILE) {
                int sol = pieces(att, "skeleton_soldier"), mas = pieces(att, "skeleton_master");
                double add = 5 * (sol + mas) + (sol == 4 || mas == 4 ? 25 : 0) + 5 * pieces(att, "speed_wither");
                ItemDef helm = def(att.getInventory().getHelmet());
                if (helm != null && helm.extra != null && helm.extra.has("sniper")) {
                    double dist = att.getLocation().distance(ctx.victim.getLocation());
                    if (dist > 20) add += (dist - 20) / 2;
                }
                ctx.additive += add;
            }
            if (inRun(att) && "necromancer_lord".equals(Sky.items().fullSet(att)) && undead(ctx.victim)) ctx.additive += 20;
        }
        Player vic = ctx.playerVictim();
        if (vic != null && ctx.attacker != null && !(ctx.attacker instanceof Player) && Sky.profiles().isReady(vic)) {
            if (witherish(ctx.attacker)) {
                double red = 0.05 * pieces(vic, "crypt_witherlord");
                for (String s : WITHER_SETS) red += 0.10 * pieces(vic, s);
                if (red > 0) ctx.multiplicative *= Math.max(0.2, 1 - red);
            }
            DungeonRun r = run(vic);
            if (r != null && vic.getWorld().equals(r.world)) {
                double best = 0;
                for (Player o : r.alive()) {
                    ItemDef chest = def(o.getInventory().getChestplate());
                    if (chest == null || chest.extra == null || !chest.extra.has("aura")) continue;
                    DungeonRun.Member m = r.member(o);
                    double range = m != null && m.cls == DClass.TANK ? 30 : 10;
                    if (o.getLocation().distanceSquared(vic.getLocation()) <= range * range) best = Math.max(best, chest.extra.get("aura").getAsDouble());
                }
                if (best > 0) ctx.multiplicative *= 1 - best;
            }
            if (System.currentTimeMillis() < WitherBlades.shieldUntil(vic)) ctx.multiplicative *= 0.9;
        }
    }

    /** Cheat-death masks and their 3-second immunity; the Bouncy set's ricochet. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Player v = ctx.playerVictim();
        long now = System.currentTimeMillis();
        if (v != null) {
            if (now < immuneUntil.getOrDefault(v.getUniqueId(), 0L)) { e.setCancelled(true); return; }
            double pool = Sky.vitals().health(v) + Sky.vitals().absorption(v);
            if (ctx.finalDamage >= pool && Sky.profiles().isReady(v)) {
                String helm = id(v.getInventory().getHelmet());
                if (("BONZO_MASK".equals(helm) || "STARRED_BONZO_MASK".equals(helm)) && inRun(v) && now >= clownCd.getOrDefault(v.getUniqueId(), 0L)) {
                    e.setCancelled(true);
                    int cata = Progress.cataLevel(v);
                    clownCd.put(v.getUniqueId(), now + Math.max(180, 360 - 2 * cata) * 1000L);       // TUNE: research "improves with level"
                    immuneUntil.put(v.getUniqueId(), now + 3000);
                    strUntil.put(v.getUniqueId(), now + 3000);
                    Sky.vitals().setHealth(v, Sky.vitals().maxHealth(v));
                    Sky.stats().invalidate(v);
                    Msg.send(v, "&6Your &9Grinko's Mask &6saved your life!");
                    Fx.particle(v.getLocation().add(0, 1, 0), Particle.TOTEM_OF_UNDYING, 40, 0.5, 0.3);
                    Fx.sound(v, Sound.ITEM_TOTEM_USE, 0.7f, 1.4f);
                    return;
                }
                if (("SPIRIT_MASK".equals(helm) || "STARRED_SPIRIT_MASK".equals(helm)) && now >= windCd.getOrDefault(v.getUniqueId(), 0L)) {
                    e.setCancelled(true);
                    windCd.put(v.getUniqueId(), now + 30_000);
                    immuneUntil.put(v.getUniqueId(), now + 3000);
                    speedUntil.put(v.getUniqueId(), now + 3000);
                    Sky.stats().invalidate(v);
                    if ("STARRED_SPIRIT_MASK".equals(helm))
                        for (int i = 1; i <= 5; i++) Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (v.isOnline()) Sky.vitals().heal(v, Sky.vitals().maxHealth(v) * 0.02); }, i * 20L);
                    Msg.send(v, "&bSecond Wind! &7Your Spirit Mask saved your life.");
                    Fx.particle(v.getLocation().add(0, 1, 0), Particle.CLOUD, 30, 0.5, 0.1);
                    return;
                }
            }
        }
        Player att = ctx.playerAttacker();
        if (!bouncing && att != null && ctx.kind == DamageContext.Kind.PROJECTILE && "bouncy".equals(Sky.items().fullSet(att)) && Math.random() < 0.25) {
            LivingEntity other = null;
            double best = 36;
            for (Entity n : ctx.victim.getNearbyEntities(6, 3, 6))
                if (n instanceof LivingEntity le && !(n instanceof Player) && !n.equals(ctx.victim) && Sky.mobs().isSkyMob(le)) {
                    double dd = n.getLocation().distanceSquared(ctx.victim.getLocation());
                    if (dd < best) { best = dd; other = le; }
                }
            if (other != null) {
                LivingEntity o = other;
                double dmg = ctx.finalDamage;
                Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                    bouncing = true;
                    try { Fx.line(ctx.victim.getLocation().add(0, 1, 0), o.getLocation().add(0, 1, 0), Color.YELLOW, 0.4); Sky.combat().rawDamage(o, dmg, att, DamageContext.Kind.PROJECTILE); }
                    finally { bouncing = false; }
                });
            }
        }
    }

    // ---- kills, teleports, sneaks ------------------------------------------------------------------------------

    /** Review fix: the per-player timers and counters were never dropped (a slow leak over a long uptime). */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        for (Map<UUID, Long> m : List.of(strUntil, speedUntil, sinewUntil, sinewCd, impAt, beamUntil, beamStart, immuneUntil)) m.remove(u);
        // clownCd / windCd are cheat-death cooldowns: kept, so relogging cannot reset them
    }

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        Player k = e.killer;
        if (k == null || !Sky.profiles().isReady(k)) return;
        if (e.mob.getType() == EntityType.ZOMBIE || e.mob.getType() == EntityType.HUSK || e.mob.getType() == EntityType.ZOMBIE_VILLAGER)
            RewardChests.data(k).zombieKills++;
        if (e.def != null && "dungeon_bonelord".equals(e.def.id)) RewardChests.data(k).bonelordKills++;
        DungeonRun r = run(k);
        if (r != null) {
            if (!r.id.equals(killsRun.get(k.getUniqueId()))) { killsRun.put(k.getUniqueId(), r.id); killsInRun.put(k.getUniqueId(), 0); }
            killsInRun.merge(k.getUniqueId(), 1, Integer::sum);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (e.getTo() == null || !e.getFrom().getWorld().equals(e.getTo().getWorld()) || e.getFrom().distanceSquared(e.getTo()) < 9) return;
        if (pieces(p, "shadow_assassin") + pieces(p, "starred_shadow_assassin") == 0) return;
        long now = System.currentTimeMillis();
        if (now < sinewCd.getOrDefault(p.getUniqueId(), 0L)) return;
        sinewCd.put(p.getUniqueId(), now + 3000);
        sinewUntil.put(p.getUniqueId(), now + 10_000);
        Sky.stats().invalidate(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        if (!e.isSneaking() || !Sky.profiles().isReady(p)) return;
        String boots = id(p.getInventory().getBoots()), helm = id(p.getInventory().getHelmet());
        if ("THORNS_BOOTS".equals(boots) || "STARRED_THORNS_BOOTS".equals(boots)) glide(p);
        if ("PRECURSOR_EYE".equals(helm)) {
            long now = System.currentTimeMillis();
            if (now < beamUntil.getOrDefault(p.getUniqueId(), 0L)) { beamUntil.remove(p.getUniqueId()); return; }
            beamStart.put(p.getUniqueId(), now);
            beamUntil.put(p.getUniqueId(), now + 5000);
        }
    }

    static void glide(Player p) {
        if (Sky.abilities().cooldownLeft(p, "dungeons_spirit_glide") > 0) return;
        if (!Sky.vitals().useMana(p, 250)) { Sky.vitals().flash(p, "&cNot enough mana for Spirit Glide!", 30); return; }
        Sky.abilities().setCooldown(p, "dungeons_spirit_glide", 60);
        p.setVelocity(p.getLocation().getDirection().multiply(0.8).setY(1.0));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1, false, false, true));
        Fx.particle(p.getLocation(), Particle.SOUL, 25, 0.5, 0.05);
        Sky.vitals().flash(p, "&b-250 Mana (&6Spirit Glide&b)", 30);
    }

    /** Eye Beam: twice a second while active, hit the first sky mob along 20 blocks. Damage doubles each second. */
    static void beams() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> en : new HashMap<>(beamUntil).entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || now > en.getValue() || !p.isSneaking() || !"PRECURSOR_EYE".equals(id(p.getInventory().getHelmet()))) { beamUntil.remove(en.getKey()); continue; }
            double sec = (now - beamStart.getOrDefault(en.getKey(), now)) / 1000.0;
            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection();
            Fx.line(eye.clone().add(dir.clone().multiply(0.8)), eye.clone().add(dir.clone().multiply(20)), Color.RED, 0.6);
            RayTraceResult hit = p.getWorld().rayTraceEntities(eye, dir, 20, 0.6, ent -> ent instanceof LivingEntity le && !(ent instanceof Player) && Sky.mobs().isSkyMob(le));
            if (hit == null || !(hit.getHitEntity() instanceof LivingEntity mob)) continue;
            double cost = 40 * Math.pow(1.25, Math.floor(sec)) / 2;
            if (!Sky.vitals().useMana(p, cost)) { beamUntil.remove(en.getKey()); Sky.vitals().flash(p, "&cOut of mana!", 30); continue; }
            Sky.combat().magicDamage(p, mob, 4000 * Math.pow(2, Math.floor(sec)) / 2, 0.2, "Eye Beam");
        }
    }

    /** Every second: Witherborn imps (a wither-set full set calls one every 30 s). */
    static void second() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            String fs = Sky.items().fullSet(p);
            boolean wither = false;
            for (String s : WITHER_SETS) if (s.equals(fs)) wither = true;
            if (!wither || now < impAt.getOrDefault(p.getUniqueId(), 0L)) continue;
            LivingEntity target = null;
            double best = 256;
            for (Entity n : p.getNearbyEntities(16, 8, 16))
                if (n instanceof LivingEntity le && !(n instanceof Player) && Sky.mobs().isSkyMob(le)) {
                    MobDef d = Sky.mobs().defOf(le);
                    if (d == null || d.has("passive")) continue;
                    double dd = n.getLocation().distanceSquared(p.getLocation());
                    if (dd < best) { best = dd; target = le; }
                }
            if (target == null) continue;
            impAt.put(p.getUniqueId(), now + 30_000);
            LivingEntity t = target;
            Location from = p.getLocation().add(0, 2, 0);
            final int[] ticks = {0};
            Bukkit.getScheduler().runTaskTimer(Sky.plugin(), task -> {
                if (!t.isValid() || !p.isOnline() || ticks[0]++ > 60) { task.cancel(); return; }
                Vector step = t.getLocation().add(0, 1, 0).toVector().subtract(from.toVector());
                if (step.length() < 1.2) {
                    task.cancel();
                    Fx.particle(t.getLocation().add(0, 1, 0), Particle.EXPLOSION, 2, 0.4, 0);
                    Fx.sound(t.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.6f, 1.6f);
                    Sky.combat().magicDamage(p, t, DDials.WITHERBORN_DAMAGE.get(), 0.3, "Witherborn");
                    return;
                }
                from.add(step.normalize().multiply(0.8));
                Fx.particle(from, Particle.SMOKE, 4, 0.1, 0);
                Fx.particle(from, Particle.SOUL, 1, 0, 0);
            }, 1L, 1L);
        }
    }

    /** "Help of the Fairies": a free Revive Stone when a player enters a run. */
    public static void onEnterRun(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        if (RewardChests.data(p).perks.getOrDefault("revive_stone", 0) > 0 && Sky.items().exists("REVIVE_STONE") && Sky.items().count(p, "REVIVE_STONE") == 0) {
            Sky.loot().give(p, "REVIVE_STONE", 1, com.skyclone.api.loot.LootSource.REWARD);
            Msg.send(p, "&dThe fairies lend you a &6Revive Stone &dfor this run.");
        }
    }
}
