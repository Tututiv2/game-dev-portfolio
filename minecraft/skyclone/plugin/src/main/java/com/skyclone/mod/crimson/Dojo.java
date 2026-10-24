package com.skyclone.mod.crimson;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Dojo: seven timed trials in one arena, one player at a time. Points per trial (max 1,000) are
 * graded S..F; the best score of each trial adds up to a total that earns the six belts. Hazards
 * (Stamina walls, Swiftness tiles) are drawn client-side for the one player in the arena, so nothing in
 * the world changes; Tenacity's embers are simulated, not real fireballs.
 */
final class Dojo implements Listener {

    static final String DUMMY = "crimson_dojo_dummy", STRIKE = "crimson_dojo_strike", HOLD = "crimson_dojo_hold",
            TARGET = "crimson_dojo_target", SHADE = "crimson_dojo_shade";

    private static Run active;

    /** One trial in progress. */
    static final class Run {
        final Player p;
        final CrimsonTables.Trial trial;
        final Box arena;
        final int floor;
        final long start, end;
        int score, hits, step;
        BukkitTask task;
        final List<LivingEntity> mobs = new ArrayList<>();
        final Map<LivingEntity, int[]> mobState = new HashMap<>();   // [hits, spawnStep, kind]
        final List<TextDisplay> labels = new ArrayList<>();
        final Map<Location, BlockData> fake = new HashMap<>();
        // stamina
        int wallZ = Integer.MIN_VALUE, holeX, wallsPassed; boolean wallHit, init;
        // swiftness
        Location tile; long tileAt;
        // control
        int onTicks, totalTicks;
        // tenacity
        final List<double[]> embers = new ArrayList<>();

        Run(Player p, CrimsonTables.Trial t, Box arena, int floor, long start, long end) {
            this.p = p; this.trial = t; this.arena = arena; this.floor = floor; this.start = start; this.end = end;
        }
    }

    static void register() {
        reg(DUMMY, "Training Dummy", "ZOMBIE", List.of("passive"), Map.of("HEAD", "LEATHER_HELMET"));
        reg(STRIKE, "Strike Dummy", "ZOMBIE", List.of("passive", "no_ai"), Map.of("HEAD", "LIME_STAINED_GLASS"));
        reg(HOLD, "Hold Dummy", "ZOMBIE", List.of("passive", "no_ai"), Map.of("HEAD", "RED_STAINED_GLASS"));
        reg(TARGET, "Target", "SKELETON", List.of("passive", "no_ai"), Map.of("HEAD", "TARGET"));
        reg(SHADE, "Shade", "WITHER_SKELETON", List.of("passive", "no_ai", "silent"), Map.of());
    }

    private static void reg(String id, String name, String entity, List<String> flags, Map<String, String> equip) {
        MobDef d = new MobDef();
        d.id = id; d.name = name; d.entity = entity; d.level = 1; d.health = 1_000_000; d.damage = 0;
        d.coins = 0; d.combatXp = 0; d.family = "crimson_dojo";
        d.flags.addAll(flags);
        d.flags.add("no_knockback");
        if (id.equals(DUMMY)) d.flags.remove("no_knockback");
        d.equipment.putAll(equip);
        Sky.mobs().register(d);
    }

    // ---- points ---------------------------------------------------------------------------------------

    static int total(CrimsonData d) {
        int t = 0;
        for (CrimsonTables.Trial tr : CrimsonModule.tables.dojo.trials) t += Math.min(CrimsonModule.tables.dojo.max_points, d.dojoBest.getOrDefault(tr.id, 0));
        return t;
    }

    // ---- menu -------------------------------------------------------------------------------------------

    static void menu(Player p) {
        CrimsonData d = CrimsonModule.data(p);
        CrimsonTables.Dojo dj = CrimsonModule.tables.dojo;
        Menu m = new Menu("Dojo", 6).chrome();
        m.back("Crimson Isle", () -> CrimsonMenus.mainMenu(p));
        int total = total(d);
        CrimsonTables.Belt belt = CrimsonRules.belt(dj.belts, total);
        CrimsonTables.Belt next = null;
        for (CrimsonTables.Belt b : dj.belts) if (b.points > total && (next == null || b.points < next.points)) next = b;
        List<String> head = new ArrayList<>();
        head.add("&7Total points: &a" + Text.commasInt(total) + "&7/&a" + Text.commasInt(dj.max_points * dj.trials.size()));
        head.add("&7Belt: " + (belt == null ? "&8none yet" : CUtil.name(belt.id)));
        if (next != null) {
            head.add("&7Next: " + CUtil.name(next.id) + " &7at &a" + Text.commasInt(next.points));
            head.add(CUtil.bar((double) total / next.points));
        }
        head.add("");
        head.add("&7Your best score in each trial counts.");
        head.add("&7Grades: &6S &71,000 &aA &7800 &9B &7600 &eC &7400 &cD &7200");
        m.set(4, Icons.of(Material.LEATHER_CHESTPLATE, "&eSensei Kiyoshi's Dojo", head));
        int[] slots = {19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        boolean here = inDojo(p);
        for (CrimsonTables.Trial t : dj.trials) {
            int best = d.dojoBest.getOrDefault(t.id, 0);
            List<String> lore = new ArrayList<>();
            for (String h : t.how) lore.add("&7" + h);
            lore.add("");
            lore.add("&7Time: &e" + seconds(t) + "s");
            lore.add("&7Best: &a" + Text.commasInt(best) + " &8(" + gradeColor(CrimsonRules.grade(dj.ranks, best)) + CrimsonRules.grade(dj.ranks, best) + "&8)");
            lore.add("");
            if (active != null && active.p != p) lore.add("&cSomeone is in the arena (" + active.p.getName() + ").");
            else lore.add(here ? "&eClick to begin!" : "&cStep into the Dojo to begin.");
            Material mat = Material.matchMaterial(t.icon);
            final String tid = t.id;
            m.set(slots[i++], Icons.of(mat == null ? Material.PAPER : mat, "&a" + t.name, lore), c -> start(c.player(), tid));
        }
        int bs = 38;
        for (CrimsonTables.Belt b : dj.belts) {
            boolean got = total >= b.points;
            List<String> lore = List.of("&7Needs &a" + Text.commasInt(b.points) + " &7total points.", "&8+&b" + b.sbxp + " SkyBlock XP", "",
                    got ? "&aEARNED" : "&cNOT YET");
            ItemStack ic = CUtil.icon(b.id, Material.LEATHER);
            m.set(bs++, Icons.withLore(ic, lore));
            if (bs == 44) break;
        }
        if (active != null && active.p == p) m.set(49, Icons.of(Material.BARRIER, "&cGive Up", List.of("&7End your current trial.")), c -> quit(c.player()));
        m.open(p);
    }

    private static String gradeColor(String g) {
        return switch (g) { case "S" -> "&6"; case "A" -> "&a"; case "B" -> "&9"; case "C" -> "&e"; case "D" -> "&c"; default -> "&8"; };
    }

    private static int seconds(CrimsonTables.Trial t) { return Math.max(10, t.seconds * CrimsonModule.D.dojoTrialScale.get() / 100); }

    static boolean inDojo(Player p) {
        if (!CrimsonModule.onIsle(p)) return false;
        Layout.Zone z = Sky.worlds().zoneOf(p);
        return z != null && "crimson_dojo".equals(z.id());
    }

    // ---- running a trial ------------------------------------------------------------------------------------

    static void start(Player p, String trialId) {
        CrimsonTables.Trial t = CrimsonModule.tables.trial(trialId);
        if (t == null) return;
        if (!inDojo(p)) { Msg.error(p, "Trials are taken in the Dojo, north-east of the Crimson Fields."); return; }
        if (active != null) {
            if (active.p == p) Msg.error(p, "You are already in a trial. /dojoquit gives up.");
            else Msg.error(p, active.p.getName() + " is in the arena. Wait for them to finish.");
            return;
        }
        Box arena = Sky.worlds().area(CrimsonModule.ISLAND, "dojo.arena");
        Location centre = Sky.worlds().anchor(CrimsonModule.ISLAND, "dojo.center");
        if (arena == null || centre == null) { Msg.error(p, "The Dojo is still being built."); return; }
        p.closeInventory();
        p.teleport(centre);
        long now = System.currentTimeMillis();
        Run r = new Run(p, t, arena, arena.minY() - 1, now + 3000, now + 3000 + seconds(t) * 1000L);
        active = r;
        Msg.send(p, "&e&l" + t.name.toUpperCase() + " &7- " + String.join(" ", t.how).replace("&a", "").replace("&c", "").replace("&7", ""));
        r.task = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            try { step(r); } catch (Throwable ex) { Sky.log().warning("[crimson] dojo step failed: " + ex); finish(r, "an error"); }
        }, 1L, 2L);
    }

    static void quit(Player p) {
        if (active != null && active.p == p) finish(active, "you gave up");
        else Msg.error(p, "You are not in a Dojo trial.");
    }

    static void stopAll(String why) { if (active != null) finish(active, why); }

    private static void step(Run r) {
        long now = System.currentTimeMillis();
        Player p = r.p;
        if (!p.isOnline() || p.getWorld() != Sky.worlds().world(CrimsonModule.ISLAND)) { finish(r, "you left"); return; }
        if (now < r.start) {
            long left = (r.start - now + 999) / 1000;
            if (r.step != (int) left) {
                r.step = (int) left;
                p.showTitle(Title.title(Text.c("&e" + left), Text.c("&7" + r.trial.name), Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ZERO)));
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1f);
            }
            return;
        }
        if (r.step >= 0) { r.step = -1; p.showTitle(Title.title(Text.c("&a&lGO!"), Text.c(""), Title.Times.times(Duration.ZERO, Duration.ofMillis(600), Duration.ofMillis(200)))); }
        if (now >= r.end) { finish(r, null); return; }
        Location l = p.getLocation();
        if (!r.arena.grow(1).containsXZ(l.getX(), l.getZ())) { finish(r, "you left the arena"); return; }
        int tick = (int) ((now - r.start) / 100);   // tenths of a second since GO
        switch (r.trial.id) {
            case "force" -> force(r, tick);
            case "stamina" -> stamina(r, tick);
            case "mastery" -> mastery(r, now);
            case "discipline" -> discipline(r, now);
            case "swiftness" -> swiftness(r, now);
            case "control" -> control(r, tick);
            case "tenacity" -> tenacity(r, tick);
            default -> { }
        }
        r.score = CrimsonRules.clampPoints(r.score, CrimsonModule.tables.dojo.max_points);
        Sky.vitals().flash(p, "&e" + r.trial.name + " &7| &a" + r.score + " pts &7| &e" + ((r.end - now) / 1000 + 1) + "s", 6);
    }

    private static Location randomSpot(Run r, int margin) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int x = rnd.nextInt(r.arena.minX() + margin, r.arena.maxX() - margin + 1);
        int z = rnd.nextInt(r.arena.minZ() + margin, r.arena.maxZ() - margin + 1);
        return new Location(r.p.getWorld(), x + 0.5, r.floor + 1, z + 0.5);
    }

    private static LivingEntity spawn(Run r, String id, Location at, int kind) {
        LivingEntity e = Sky.mobs().spawn(id, at, r.p);
        if (e != null) { r.mobs.add(e); r.mobState.put(e, new int[]{0, (int) Math.max(0, (System.currentTimeMillis() - r.start) / 100), kind}); }
        return e;
    }

    private static void despawn(Run r, LivingEntity e) {
        r.mobs.remove(e);
        r.mobState.remove(e);
        if (e.isValid()) Sky.mobs().remove(e);
    }

    private static TextDisplay label(Run r, Location at, String text) {
        TextDisplay td = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.text(Text.c(text));
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
        });
        r.labels.add(td);
        return td;
    }

    // Force: dummies wander in; hits score (crits more); three hits topple one.
    private static void force(Run r, int tick) {
        if (tick % 15 == 0 && r.mobs.size() < 4) spawn(r, DUMMY, randomSpot(r, 2), 0);
        // wave-4 endgame playtest: Training Dummies have wander AI and the arena wall has open gates (south 5 wide, north
        // 3 wide), so they strolled out of the arena (one reached z -68, 18 blocks past the gate). Chasing one ends the
        // trial with "you left the arena", and a dummy outside is a dummy lost for the round. Keep them on the mat.
        for (LivingEntity e : new ArrayList<>(r.mobs)) {
            if (e.isValid() && !r.arena.containsXZ(e.getLocation().getX(), e.getLocation().getZ())) e.teleport(randomSpot(r, 2));
        }
    }

    // Stamina: walls with a gap sweep north to south, one block every 3 ticks.
    private static void stamina(Run r, int tick) {
        Player p = r.p;
        int x0 = r.arena.minX(), x1 = r.arena.maxX();
        if (r.wallZ == Integer.MIN_VALUE) {
            if (tick % 6 != 0) return;
            r.wallZ = r.arena.minZ();
            r.holeX = ThreadLocalRandom.current().nextInt(x0 + 1, x1 - 3);
            r.wallHit = false;
            drawWall(r, r.wallZ, true);
            return;
        }
        drawWall(r, r.wallZ, false);
        r.wallZ++;
        if (r.wallZ > r.arena.maxZ()) {
            if (!r.wallHit) { r.score += 60; r.wallsPassed++; Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.6f); }
            r.wallZ = Integer.MIN_VALUE;
            return;
        }
        drawWall(r, r.wallZ, true);
        Location l = p.getLocation();
        if (!r.wallHit && l.getBlockZ() == r.wallZ) {
            int bx = l.getBlockX();
            boolean inHole = bx >= r.holeX && bx < r.holeX + 3 && l.getY() < r.floor + 2.9;
            if (!inHole) {
                r.wallHit = true;
                p.setVelocity(new Vector(0, 0.35, 0.7));
                Msg.sound(p, Sound.ENTITY_PLAYER_HURT, 1f);
            }
        }
    }

    private static void drawWall(Run r, int z, boolean on) {
        World w = r.p.getWorld();
        BlockData glass = Bukkit.createBlockData(Material.RED_STAINED_GLASS);
        for (int x = r.arena.minX(); x <= r.arena.maxX(); x++)
            for (int y = r.floor + 1; y <= r.floor + 3; y++) {
                boolean hole = x >= r.holeX && x < r.holeX + 3 && y <= r.floor + 2;
                Location l = new Location(w, x, y, z);
                if (on && !hole) { r.p.sendBlockChange(l, glass); r.fake.put(l, l.getBlock().getBlockData()); }
                else if (!on && r.fake.containsKey(l)) { r.p.sendBlockChange(l, l.getBlock().getBlockData()); r.fake.remove(l); }
            }
    }

    // Mastery: a target rises every 4s; it turns green after 3s for 1.5s. Strike it then.
    private static void mastery(Run r, long now) {
        long since = now - r.start;
        if (since / 4000 + 1 > r.mobs.size() + r.hits && r.mobs.isEmpty()) {
            LivingEntity e = spawn(r, TARGET, randomSpot(r, 3), 0);
            if (e != null) label(r, e.getLocation().add(0, 2.3, 0), "&c3");
            r.hits++;     // counts targets raised
        }
        for (Iterator<LivingEntity> it = new ArrayList<>(r.mobs).iterator(); it.hasNext(); ) {
            LivingEntity e = it.next();
            int[] st = r.mobState.get(e);
            if (st == null) continue;
            long age = now - r.start - st[1] * 100L;
            TextDisplay td = r.labels.isEmpty() ? null : r.labels.get(r.labels.size() - 1);
            if (age < 3000) { if (td != null) td.text(Text.c(age < 1000 ? "&c3" : age < 2000 ? "&62" : "&e1")); }
            else if (age < 4500) {
                if (td != null) td.text(Text.c("&a&lSTRIKE!"));
                e.setGlowing(true);
                st[2] = 1;
            } else {
                if (td != null) { td.remove(); r.labels.remove(td); }
                despawn(r, e);
            }
        }
    }

    // Discipline: STRIKE dummies (+50) and HOLD dummies (-60) pop up; each lasts 4 seconds.
    private static void discipline(Run r, long now) {
        long since = now - r.start;
        if (since / 1200 > r.step * -1 - 1 && r.mobs.size() < 5) {
            r.step--;
            boolean strike = ThreadLocalRandom.current().nextDouble() < 0.7;
            LivingEntity e = spawn(r, strike ? STRIKE : HOLD, randomSpot(r, 2), strike ? 1 : 2);
            if (e != null) label(r, e.getLocation().add(0, 2.3, 0), strike ? "&a&lSTRIKE" : "&c&lHOLD");
        }
        for (LivingEntity e : new ArrayList<>(r.mobs)) {
            int[] st = r.mobState.get(e);
            if (st != null && now - r.start - st[1] * 100L > 4000) { removeLabelNear(r, e.getLocation()); despawn(r, e); }
        }
    }

    private static void removeLabelNear(Run r, Location l) {
        for (Iterator<TextDisplay> it = r.labels.iterator(); it.hasNext(); ) {
            TextDisplay td = it.next();
            if (td.getLocation().distanceSquared(l.clone().add(0, 2.3, 0)) < 1.5) { td.remove(); it.remove(); }
        }
    }

    // Swiftness: a tile lights up; reach it within 2.5s - faster scores more.
    private static void swiftness(Run r, long now) {
        Player p = r.p;
        if (r.tile == null) {
            Location spot;
            int guard = 0;
            do { spot = randomSpot(r, 1); spot.setY(r.floor); guard++; }
            while (guard < 20 && spot.distanceSquared(p.getLocation().clone().subtract(0, 1, 0)) < 16);
            r.tile = spot.getBlock().getLocation();
            r.tileAt = now;
            p.sendBlockChange(r.tile, Bukkit.createBlockData(Material.LIME_CONCRETE));
            r.fake.put(r.tile, r.tile.getBlock().getBlockData());
            return;
        }
        Location l = p.getLocation();
        boolean on = l.getBlockX() == r.tile.getBlockX() && l.getBlockZ() == r.tile.getBlockZ();
        long took = now - r.tileAt;
        if (on || took > 2500) {
            if (on) { r.score += CrimsonRules.swiftnessStep(took); Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.8f); }
            p.sendBlockChange(r.tile, r.tile.getBlock().getBlockData());
            r.fake.remove(r.tile);
            r.tile = null;
        } else p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, r.tile.clone().add(0.5, 1.2, 0.5), 2, 0.2, 0.1, 0.2, 0);
    }

    // Control: keep your eyes on a shade that blinks around the arena.
    private static void control(Run r, int tick) {
        Player p = r.p;
        LivingEntity shade = r.mobs.isEmpty() ? spawn(r, SHADE, randomSpot(r, 2), 0) : r.mobs.get(0);
        if (shade == null) return;
        if (tick % 15 == 0 && tick > 0) {
            Location to = randomSpot(r, 2);
            shade.teleport(to);
            p.getWorld().spawnParticle(Particle.PORTAL, to.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.2);
        }
        Vector look = p.getEyeLocation().getDirection();
        Vector to = shade.getLocation().add(0, 1.2, 0).toVector().subtract(p.getEyeLocation().toVector());
        r.totalTicks++;
        if (to.lengthSquared() > 0 && look.dot(to.normalize()) > 0.985) r.onTicks++;
        r.score = CrimsonRules.control(r.onTicks, r.totalTicks, CrimsonModule.tables.dojo.max_points);
    }

    // Tenacity: simulated embers fly at you; each hit costs 100 of the 1,000 you start with.
    private static void tenacity(Run r, int tick) {
        Player p = r.p;
        if (!r.init) { r.init = true; r.score = 1000; }
        if (tick % 6 == 0 && r.embers.size() < 6) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            boolean alongX = rnd.nextBoolean();
            double x = alongX ? rnd.nextDouble(r.arena.minX(), r.arena.maxX() + 1) : (rnd.nextBoolean() ? r.arena.minX() : r.arena.maxX() + 1);
            double z = alongX ? (rnd.nextBoolean() ? r.arena.minZ() : r.arena.maxZ() + 1) : rnd.nextDouble(r.arena.minZ(), r.arena.maxZ() + 1);
            double y = r.floor + 1.2 + rnd.nextDouble(0.8);
            Vector dir = p.getLocation().add(0, 1, 0).toVector().subtract(new Vector(x, y, z)).normalize().multiply(0.7);
            r.embers.add(new double[]{x, y, z, dir.getX(), dir.getY(), dir.getZ(), 0});
        }
        World w = p.getWorld();
        Location body = p.getLocation().add(0, 1, 0);
        for (Iterator<double[]> it = r.embers.iterator(); it.hasNext(); ) {
            double[] e = it.next();
            for (int k = 0; k < 2; k++) { e[0] += e[3]; e[1] += e[4]; e[2] += e[5]; }
            e[6]++;
            Location at = new Location(w, e[0], e[1], e[2]);
            w.spawnParticle(Particle.FLAME, at, 2, 0.05, 0.05, 0.05, 0);
            if (at.distanceSquared(body) < 0.9) {
                r.score -= 100;
                r.hits++;
                w.spawnParticle(Particle.LAVA, body, 6, 0.3, 0.4, 0.3, 0);
                Msg.sound(p, Sound.ENTITY_PLAYER_HURT_ON_FIRE, 1f);
                it.remove();
            } else if (e[6] > 40 || !r.arena.grow(3).containsXZ(e[0], e[2])) it.remove();
        }
    }

    // ---- hits on dojo mobs -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(SkyEvents.Damage e) {
        if (!(e.ctx.victim instanceof LivingEntity v)) return;
        MobDef d = Sky.mobs().defOf(v);
        if (d == null || !"crimson_dojo".equals(d.familyId())) return;
        e.setCancelled(true);
        Run r = active;
        if (r == null || e.ctx.playerAttacker() != r.p || !r.mobState.containsKey(v)) return;
        if (System.currentTimeMillis() < r.start) return;
        int[] st = r.mobState.get(v);
        Player p = r.p;
        switch (r.trial.id) {
            case "force" -> {
                if (e.ctx.kind == com.skyclone.api.combat.DamageContext.Kind.FEROCITY) return;
                r.score += CrimsonRules.forceHit(e.ctx.crit);
                Vector push = v.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                if (push.lengthSquared() > 0) v.setVelocity(push.normalize().multiply(0.8).setY(0.3));
                if (++st[0] >= 3) { v.getWorld().spawnParticle(Particle.POOF, v.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0); despawn(r, v); }
            }
            case "mastery" -> {
                r.score += CrimsonRules.masteryHit(st[2] == 1);
                Msg.sound(p, st[2] == 1 ? Sound.ENTITY_ARROW_HIT_PLAYER : Sound.BLOCK_NOTE_BLOCK_BASS, 1.4f);
                removeLabelNear(r, v.getLocation());
                despawn(r, v);
            }
            case "discipline" -> {
                if (st[2] == 1) { r.score += 50; Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f); }
                else { r.score = Math.max(0, r.score - 60); Msg.sound(p, Sound.ENTITY_VILLAGER_NO, 1f); }
                removeLabelNear(r, v.getLocation());
                despawn(r, v);
            }
            default -> { }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { if (active != null && active.p == e.getPlayer()) finish(active, "you left"); }

    @EventHandler
    public void onDeath(SkyEvents.SkyDeath e) { if (active != null && active.p == e.player) finish(active, "you died"); }

    // ---- the end of a trial -------------------------------------------------------------------------------------

    private static void finish(Run r, String abortReason) {
        if (active == r) active = null;
        if (r.task != null) r.task.cancel();
        for (LivingEntity e : new ArrayList<>(r.mobs)) if (e.isValid()) Sky.mobs().remove(e);
        r.mobs.clear();
        for (TextDisplay td : r.labels) if (td.isValid()) td.remove();
        r.labels.clear();
        Player p = r.p;
        if (p.isOnline()) for (Location l : r.fake.keySet()) p.sendBlockChange(l, l.getBlock().getBlockData());
        r.fake.clear();
        if (!p.isOnline() || !CrimsonModule.ready(p)) return;
        long now = System.currentTimeMillis();
        int score = CrimsonRules.clampPoints(r.score, CrimsonModule.tables.dojo.max_points);
        if (now < r.start) score = 0;
        CrimsonData d = CrimsonModule.data(p);
        int before = d.dojoBest.getOrDefault(r.trial.id, 0);
        boolean best = score > before;
        if (best) d.dojoBest.put(r.trial.id, score);
        String grade = CrimsonRules.grade(CrimsonModule.tables.dojo.ranks, score);
        List<String> body = new ArrayList<>();
        if (abortReason != null) body.add("&cEnded early: " + abortReason + ".");
        body.add("&7Points: &a" + score + (best ? " &6&lNEW BEST!" : " &8(best " + before + ")"));
        body.add("&7Grade: " + gradeColor(grade) + "&l" + grade);
        body.add("&7Dojo total: &a" + Text.commasInt(total(d)));
        Msg.box(p, "&e&l" + r.trial.name.toUpperCase() + " COMPLETE", body);
        Msg.sound(p, grade.equals("S") || grade.equals("A") ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1f);
        Factions.dojoScored(p, r.trial.id, score);
        checkBelts(p, d);
        Location out = Sky.worlds().anchor(CrimsonModule.ISLAND, "dojo.spectate");
        if (out != null && p.getWorld() == out.getWorld()) p.teleport(out);
    }

    static void checkBelts(Player p, CrimsonData d) {
        int total = total(d);
        for (CrimsonTables.Belt b : CrimsonModule.tables.dojo.belts) {
            if (total < b.points || d.beltsGiven.contains(b.id)) continue;
            d.beltsGiven.add(b.id);
            if (Sky.items().exists(b.id)) Sky.loot().give(p, b.id, 1, LootSource.REWARD);
            if (b.sbxp > 0) Sky.service(Features.SkyLevel.class).award(p, "crimson:belt:" + b.id, b.sbxp, "Dojo " + b.name);
            Msg.box(p, "&6&lBELT EARNED! " + CUtil.name(b.id), List.of("&7Sensei Kiyoshi ties the " + b.name + " &7around your waist.", "&8+&b" + b.sbxp + " SkyBlock XP"));
        }
        Sky.profiles().saveNow(p);
    }

    static Run active() { return active; }
}
