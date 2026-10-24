package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Cake;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Wave 5: the Riddle Soul puzzles (research: NEU rift_guide.json names every Enigma Soul and what it asks;
 * {@link RiftEnigma} holds our version of each). The generator ({@code RiftProps}) put the props in the world;
 * this class runs them: flower-pot trails, balloons, the Flitwraith you follow, rune stones, cakes, two-player
 * plates and the beacon-and-hopper, a breakable living wall, eight wooden buttons, shops, a quiz, a hot dog
 * contest, a lonely NPC, a magic trick, squats, a lap of the Colosseum and Ubaldo's three games.
 *
 * <p>Soul collection itself stays in {@link RiftWorldState}: it asks {@link #open} whether a walk-in may
 * collect a soul, and puzzles that hand a soul over call {@link RiftWorldState#grantSoul}. Per-visit progress
 * lives in the session ({@link State}); what is solved for good lives in {@link RiftData#riddles}.
 */
final class RiftRiddles implements Listener {

    /** Per-visit puzzle state (held by the RiftSession). */
    static final class State {
        final Map<String, Integer> progress = new HashMap<>();        // pots index, cake bites
        final Map<String, Set<Integer>> hits = new HashMap<>();       // balloons popped, buttons pressed ("buttons")
        final Map<String, int[]> runes = new HashMap<>();             // facing per rune, as this player sees it
        String spook; Location spookAt; long spookLostAt;
        long lonelySince; boolean lonely;
        long stillSince; Location stillAt;
        long squatUntil; int squats;
        long circleUntil; double circleAngle; Double circleLast; boolean circleDone;
        long lastWet;
        long lockedHintAt;
        Location prev;
    }

    /** One soul's puzzle and the props the generator gave it. */
    static final class Puzzle {
        final RiftEnigma.Riddle r;
        final String soul;
        final Location at;
        final List<Location> pots = new ArrayList<>(), balloons = new ArrayList<>(), runes = new ArrayList<>(), plates = new ArrayList<>();
        Location cake, beacon, hopper, spook, cage, pillar;
        Puzzle(RiftEnigma.Riddle r, String soul, Location at) { this.r = r; this.soul = soul; this.at = at; }

        /** Props missing (a cramped spot): the riddle falls back to a plain walk-in. */
        boolean degraded() {
            return switch (r.kind()) {
                case POTS -> pots.isEmpty();
                case BALLOONS -> balloons.isEmpty();
                case RUNES -> runes.isEmpty();
                case CAKE -> cake == null;
                case SPOOK -> spook == null;
                case PLATES -> plates.size() < 2;
                case BEACON -> beacon == null || hopper == null;
                case FALL -> pillar == null;
                default -> false;
            };
        }
    }

    private final RiftModule m;
    private final Random rnd = new Random();
    private final Map<String, Puzzle> byRiddle = new LinkedHashMap<>();
    private final Map<String, Puzzle> bySoul = new HashMap<>();
    /** Block key -> (puzzle, prop kind, index). */
    private final Map<String, Object[]> props = new HashMap<>();
    private final List<Location> buttons = new ArrayList<>();
    private long indexedAt;
    // shared world state
    private final Map<String, long[]> beaconUse = new HashMap<>();          // riddle -> {beaconMs, hopperMs}
    private final Map<String, UUID[]> beaconWho = new HashMap<>();
    private final Map<String, Long> cageOpenUntil = new HashMap<>();        // riddle -> ms
    private final Map<String, List<Location>> cageRemoved = new HashMap<>();
    private final Map<Location, Long> wallRemoved = new LinkedHashMap<>();
    private final List<Integer> averages = new ArrayList<>(List.of(4, 6, 5, 7, 3, 5));   // TUNE: seeded picks
    private int tickNo;

    RiftRiddles(RiftModule m) { this.m = m; }

    // =========================================================================================
    // setup
    // =========================================================================================

    void register() {
        AbilityService ab = Sky.abilities();
        ab.register("rift_blowgun", this::blowgun);
        Sky.worlds().addBreakHandler(new WorldService.BreakHandler() {
            @Override public int priority() { return 39; }
            @Override public boolean handles(Player p, Block b, Layout.Zone zone) {
                return b.getType() == Material.CHISELED_TUFF_BRICKS && RiftCatalog.ISLAND.equals(Sky.worlds().islandOf(b.getWorld())) && wallOf(b) != null;
            }
            @Override public void onBreak(Player p, Block b, BlockBreakEvent event) { breakWall(p, b); }
        });
        for (Map.Entry<String, String> e : RiftEnigma.NPCS.entrySet()) {
            String key = e.getKey();
            NpcDef d = NpcDef.of(RiftEnigma.npcId(key), e.getValue(), RiftCatalog.ISLAND, RiftEnigma.npcAnchor(key)).title(npcTitle(key));
            String ent = RiftEnigma.npcEntity(key);
            if (ent != null) d.entity(ent);
            Sky.npcs().register(d, p -> npcClick(p, key));
        }
    }

    private static String npcTitle(String key) {
        return switch (key) {
            case "rourke", "blitzcoin", "thistledown", "skink" -> "&6&lSHOP";
            case "ubaldo" -> "&e&lGAMES";
            default -> "&5&lRIDDLE";
        };
    }

    void index() {
        Layout l = Sky.worlds().layout(RiftCatalog.ISLAND);
        World w = Sky.worlds().world(RiftCatalog.ISLAND);
        if (l == null || w == null) return;
        byRiddle.clear(); bySoul.clear(); props.clear(); buttons.clear();
        for (int n = 1; n <= RiftCatalog.totalSouls(); n++) {
            String soul = "soul." + n;
            Layout.Anchor a = l.anchor(soul);
            RiftEnigma.Riddle r = RiftEnigma.riddle(l.data().get(soul + ".riddle"));
            if (a == null || r == null) continue;
            Puzzle pz = new Puzzle(r, soul, new Location(w, a.x(), a.y(), a.z()));
            for (int k = 1; k <= 12; k++) {
                addProp(pz, pz.pots, l, w, soul + ".pot." + k, "pot", k);
                addProp(pz, pz.balloons, l, w, soul + ".balloon." + k, null, k);
                addProp(pz, pz.runes, l, w, soul + ".rune." + k, "rune", k);
            }
            addProp(pz, pz.plates, l, w, soul + ".plate.1", null, 1);
            addProp(pz, pz.plates, l, w, soul + ".plate.2", null, 2);
            pz.cake = single(pz, l, w, soul + ".cake", "cake");
            pz.beacon = single(pz, l, w, soul + ".beacon", "beacon");
            pz.hopper = single(pz, l, w, soul + ".hopper", "hopper");
            pz.spook = loc(l, w, soul + ".spook");
            pz.cage = xyz(w, l.data().get(soul + ".cage"));
            pz.pillar = xyz(w, l.data().get(soul + ".pillar"));
            byRiddle.put(r.id(), pz);
            bySoul.put(soul, pz);
        }
        for (int k = 1; k <= 16; k++) {
            Location b = loc(l, w, "riddle.button." + k);
            if (b == null) continue;
            buttons.add(b);
            props.put(key(b.getBlock()), new Object[]{null, "button", k - 1});
        }
        indexedAt = System.currentTimeMillis();
    }

    private void ensure() { if (byRiddle.isEmpty() || System.currentTimeMillis() - indexedAt > 600_000) index(); }

    private void addProp(Puzzle pz, List<Location> into, Layout l, World w, String name, String kind, int k) {
        Location at = loc(l, w, name);
        if (at == null) return;
        into.add(at);
        if (kind != null) props.put(key(at.getBlock()), new Object[]{pz, kind, k});
    }

    private Location single(Puzzle pz, Layout l, World w, String name, String kind) {
        Location at = loc(l, w, name);
        if (at != null) props.put(key(at.getBlock()), new Object[]{pz, kind, 1});
        return at;
    }

    private static Location loc(Layout l, World w, String name) {
        Layout.Anchor a = l.anchor(name);
        return a == null ? null : new Location(w, a.x(), a.y(), a.z());
    }

    private static Location xyz(World w, String s) {
        if (s == null) return null;
        try {
            String[] p = s.split(",");
            return new Location(w, Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        } catch (RuntimeException e) { return null; }
    }

    private static String key(Block b) { return b.getX() + "," + b.getY() + "," + b.getZ(); }

    Puzzle puzzleOfSoul(String soul) { ensure(); return bySoul.get(soul); }

    Puzzle puzzle(String riddleId) { ensure(); return byRiddle.get(riddleId); }

    Map<String, Puzzle> puzzles() { ensure(); return byRiddle; }

    int buttonCount() { ensure(); return buttons.size(); }

    void shutdown() {
        for (List<Location> cells : cageRemoved.values()) for (Location l : cells) if (l.getBlock().getType().isAir()) l.getBlock().setType(Material.GLASS, false);
        cageRemoved.clear();
        for (Location l : wallRemoved.keySet()) if (l.getBlock().getType().isAir()) l.getBlock().setType(Material.CHISELED_TUFF_BRICKS, false);
        wallRemoved.clear();
    }

    // =========================================================================================
    // the gate RiftWorldState asks before a walk-in collects a soul
    // =========================================================================================

    /** May this player collect this soul by walking into it right now? */
    boolean open(Player p, RiftData d, String soul) {
        Puzzle pz = puzzleOfSoul(soul);
        if (pz == null || pz.degraded()) return true;
        RiftEnigma.Kind k = pz.r.kind();
        if (d.riddles.contains(pz.r.id())) return true;
        return switch (k) {
            case PLACE, PLATES, WALL -> true;
            case DRY -> {
                RiftSession s = m.session(p);
                boolean dry = s == null || System.currentTimeMillis() - s.rs.lastWet > m.dials.dryWindow.get() * 1000L;
                if (!dry) lockedHint(p, "&cThe toxic water clings to you. Leave the Lair, dry off, and try again without touching it.");
                yield dry;
            }
            case FALL -> false;                         // collected by onMove, landing from high above
            default -> false;
        };
    }

    /** A locked soul nearby: tell the player what it wants (rate-limited). */
    void lockedNear(Player p, String soul) {
        Puzzle pz = puzzleOfSoul(soul);
        if (pz == null) return;
        lockedHint(p, "&5" + pz.r.name() + "&7: " + pz.r.task());
    }

    private void lockedHint(Player p, String text) {
        RiftSession s = m.session(p);
        long now = System.currentTimeMillis();
        if (s == null || now - s.rs.lockedHintAt < 6000) return;
        s.rs.lockedHintAt = now;
        Msg.send(p, text);
    }

    /** Puzzle solved: grant the soul now (grant kinds, or reveal kinds when standing close), else reveal it. */
    void solve(Player p, String riddleId, String how) {
        RiftData d = m.data(p);
        Puzzle pz = puzzle(riddleId);
        if (d == null || pz == null) return;
        boolean first = d.riddles.add(riddleId);
        if (d.souls.contains(pz.soul)) return;
        boolean near = pz.at.getWorld() == p.getWorld() && p.getLocation().distanceSquared(pz.at) < 64;
        if (pz.r.kind().grants() || near) {
            if (how != null) Msg.send(p, "&5" + pz.r.name() + "&7: " + how);
            m.world.grantSoul(p, pz.soul);
            soulTrail(p, pz.at);
        } else if (first) {
            Msg.send(p, "&5" + pz.r.name() + " &dsolved! &7The soul is free: walk into it to collect it.");
            Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f);
        }
    }

    private void soulTrail(Player p, Location from) {
        if (from.getWorld() != p.getWorld()) return;
        Vector dir = p.getLocation().add(0, 1, 0).toVector().subtract(from.toVector());
        double len = Math.min(40, dir.length());
        if (len < 0.5) return;
        dir.normalize();
        for (double t = 0; t < len; t += 0.8)
            p.spawnParticle(Particle.END_ROD, from.clone().add(dir.clone().multiply(t)).add(0, 0.8, 0), 1, 0, 0, 0, 0);
    }

    // =========================================================================================
    // every 5 ticks
    // =========================================================================================

    void tick() {
        tickNo++;
        if (m.sessions.isEmpty()) return;
        ensure();
        long now = System.currentTimeMillis();
        boolean draw = m.dials.particles.get() && tickNo % 2 == 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            RiftSession s = m.session(p);
            RiftData d = s == null ? null : m.data(p);
            if (d == null) continue;
            try {
                if (p.isInWater()) s.rs.lastWet = now;
                spook(p, s, d, now);
                lonely(p, s, d, now);
                still(p, s, d, now);
                squatTimeout(p, s, now);
                circle(p, s, d, now);
                if (draw) guides(p, s, d);
            } catch (Throwable t) {
                Sky.log().warning("[rift] riddle tick for " + p.getName() + ": " + t);
            }
        }
        plates(now);
        if (tickNo % 4 == 0) restore(now);
    }

    /** Next pot, unpopped balloons, unpressed buttons nearby: each player sees their own. */
    private void guides(Player p, RiftSession s, RiftData d) {
        Location pl = p.getLocation();
        for (Puzzle pz : byRiddle.values()) {
            if (pz.at.getWorld() != p.getWorld() || d.riddles.contains(pz.r.id()) || d.souls.contains(pz.soul)) continue;
            double d2 = pl.distanceSquared(pz.at);
            if (d2 > 60 * 60) continue;
            switch (pz.r.kind()) {
                case POTS -> {
                    int next = s.rs.progress.getOrDefault(pz.r.id(), 0);
                    if (next < pz.pots.size()) {
                        Location pot = pz.pots.get(next);
                        if (pl.distanceSquared(pot) < 24 * 24) p.spawnParticle(Particle.HAPPY_VILLAGER, pot.clone().add(0.0, 0.7, 0.0), 3, 0.2, 0.2, 0.2, 0);
                    }
                }
                case BALLOONS -> {
                    Set<Integer> popped = s.rs.hits.getOrDefault(pz.r.id(), Set.of());
                    for (int i = 0; i < pz.balloons.size(); i++) {
                        if (popped.contains(i)) continue;
                        Location b = pz.balloons.get(i);
                        Color c = BALLOON_COLORS[i % BALLOON_COLORS.length];
                        p.spawnParticle(Particle.DUST, b, 14, 0.25, 0.3, 0.25, 0, new Particle.DustOptions(c, 2.2f));
                        p.spawnParticle(Particle.DUST, b.clone().add(0, -1.0, 0), 2, 0.02, 0.4, 0.02, 0, new Particle.DustOptions(Color.WHITE, 0.6f));
                    }
                }
                default -> { }
            }
        }
        // the buttons: a faint glint within 12 blocks of each one not pressed this visit
        RiftData dd = d;
        if (!dd.riddles.contains("rift_buttons_soul")) {
            Set<Integer> pressed = s.rs.hits.getOrDefault("buttons", Set.of());
            for (int i = 0; i < buttons.size(); i++) {
                Location b = buttons.get(i);
                if (pressed.contains(i) || b.getWorld() != p.getWorld() || pl.distanceSquared(b) > 144) continue;
                p.spawnParticle(Particle.WAX_ON, b.clone().add(0, 0.3, 0), 2, 0.15, 0.1, 0.15, 0);
            }
        }
    }

    static final Color[] BALLOON_COLORS = {Color.RED, Color.YELLOW, Color.LIME, Color.AQUA, Color.FUCHSIA, Color.ORANGE};

    // ---- the Flitwraith ----
    private void spook(Player p, RiftSession s, RiftData d, long now) {
        if (s.rs.spook == null) {
            for (Puzzle pz : byRiddle.values()) {
                if (pz.r.kind() != RiftEnigma.Kind.SPOOK || pz.spook == null || d.riddles.contains(pz.r.id()) || d.souls.contains(pz.soul)) continue;
                if (pz.spook.getWorld() != p.getWorld() || p.getLocation().distanceSquared(pz.spook) > 9) continue;
                s.rs.spook = pz.r.id();
                s.rs.spookAt = pz.spook.clone().add(0, 1.6, 0);
                s.rs.spookLostAt = 0;
                Msg.send(p, "&5A Flitwraith shrieks and darts away! &7Follow it, and do not fall behind.");
                Msg.sound(p, Sound.ENTITY_VEX_CHARGE, 0.8f);
                break;
            }
            return;
        }
        Puzzle pz = byRiddle.get(s.rs.spook);
        if (pz == null) { s.rs.spook = null; return; }
        Location at = s.rs.spookAt, target = pz.at.clone().add(0, 1.4, 0);
        double dist = p.getLocation().distance(at);
        if (dist <= 7) {
            s.rs.spookLostAt = 0;
            Vector step = target.toVector().subtract(at.toVector());
            double left = step.length();
            if (left < 1.2) {
                s.rs.spook = null;
                p.spawnParticle(Particle.SOUL, target, 30, 0.4, 0.6, 0.4, 0.05);
                Msg.sound(p, Sound.ENTITY_VEX_DEATH, 1.2f);
                solve(p, pz.r.id(), "The Flitwraith vanishes and leaves a soul behind.");
                return;
            }
            double speed = dist < 3.5 ? 1.25 : 0.8;              // it flees faster the closer you get
            at.add(step.normalize().multiply(Math.min(speed, left)));
        } else if (dist > 16) {
            if (s.rs.spookLostAt == 0) s.rs.spookLostAt = now;
            else if (now - s.rs.spookLostAt > m.dials.spookLoseSeconds.get() * 1000L) {
                s.rs.spook = null;
                Msg.send(p, "&7You lost the Flitwraith. It has gone back to where it waits.");
                return;
            }
        }
        double bob = Math.sin(tickNo * 0.6) * 0.25;
        Location draw = at.clone().add(0, bob, 0);
        p.spawnParticle(Particle.SOUL, draw, 4, 0.2, 0.2, 0.2, 0.01);
        p.spawnParticle(Particle.DUST, draw, 6, 0.15, 0.25, 0.15, 0, new Particle.DustOptions(Color.fromRGB(220, 220, 255), 1.6f));
    }

    // ---- Lonely Yxvael ----
    private void lonely(Player p, RiftSession s, RiftData d, long now) {
        if (!s.rs.lonely) return;
        Location npc = anchor("yxvael");
        if (npc == null || npc.getWorld() != p.getWorld()) { s.rs.lonely = false; return; }
        double dist = p.getLocation().distance(npc);
        if (dist < 3) {
            s.rs.lonely = false;
            Msg.send(p, "&d[NPC] Lonely Yxvael&f: T-too close! ...Can we start over?");
            return;
        }
        if (dist > 10) { s.rs.lonely = false; Msg.send(p, "&d[NPC] Lonely Yxvael&f: Oh. You left. Everyone leaves."); return; }
        int need = m.dials.lonelySeconds.get();
        long held = (now - s.rs.lonelySince) / 1000;
        Sky.vitals().flash(p, "&dKeeping Yxvael company: &a" + held + "&7/" + need + "s &8(3-10 blocks away)", 8);
        if (held >= need) {
            s.rs.lonely = false;
            Msg.send(p, "&d[NPC] Lonely Yxvael&f: That was... nice. Here. I want you to have this.");
            solve(p, "rift_lonely_dave", null);
        }
    }

    // ---- Magician Prestwick ----
    private void still(Player p, RiftSession s, RiftData d, long now) {
        if (s.rs.stillAt == null) return;
        if (p.getLocation().getWorld() != s.rs.stillAt.getWorld() || p.getLocation().distanceSquared(s.rs.stillAt) > 0.04) {
            s.rs.stillAt = null;
            Msg.send(p, "&5[NPC] Magician Prestwick&f: You flinched! The rabbit refuses to come out. Again, from the top.");
            Msg.sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
            return;
        }
        int need = puzzle("rift_rabbit_under_hat") == null ? 8 : puzzle("rift_rabbit_under_hat").r.count();
        long held = (now - s.rs.stillSince) / 1000;
        Sky.vitals().flash(p, "&5Stay calm... &a" + held + "&7/" + need + "s", 8);
        if (held >= need) {
            s.rs.stillAt = null;
            Location hat = anchor("prestwick");
            if (hat != null) p.spawnParticle(Particle.CLOUD, hat.clone().add(0, 1.5, 0), 20, 0.3, 0.3, 0.3, 0.02);
            Msg.sound(p, Sound.ENTITY_RABBIT_AMBIENT, 1.4f);
            Msg.send(p, "&5[NPC] Magician Prestwick&f: ...and a RABBIT! And what is this, clinging to its ear? A soul! Yours.");
            solve(p, "rift_rabbit_under_hat", null);
        }
    }

    // ---- Okrel ----
    private void squatTimeout(Player p, RiftSession s, long now) {
        if (s.rs.squatUntil > 0 && now > s.rs.squatUntil) {
            s.rs.squatUntil = 0;
            Msg.send(p, "&b[NPC] Okrel the Squatter&f: " + s.rs.squats + " squats? Pathetic. Again!");
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p = e.getPlayer();
        RiftSession s = m.session(p);
        if (s == null || s.rs.squatUntil <= 0) return;
        Location o = anchor("okrel");
        if (o == null || o.getWorld() != p.getWorld() || p.getLocation().distanceSquared(o) > 64) return;
        s.rs.squats++;
        int need = puzzle("rift_mountain_ikrus") == null ? 20 : puzzle("rift_mountain_ikrus").r.count();
        Sky.vitals().flash(p, "&bSquats: &a" + s.rs.squats + "&7/" + need, 20);
        if (s.rs.squats >= need) {
            s.rs.squatUntil = 0;
            Msg.send(p, "&b[NPC] Okrel the Squatter&f: YES! I can feel the Rift letting go of me! Take this, squat-sibling.");
            solve(p, "rift_mountain_ikrus", null);
        }
    }

    // ---- Full Circle ----
    private void circle(Player p, RiftSession s, RiftData d, long now) {
        if (s.rs.circleUntil <= 0 || s.rs.circleDone) return;
        if (now > s.rs.circleUntil) { s.rs.circleUntil = 0; Msg.send(p, "&6[NPC] Digby the Mole&f: Too slow! Talk to me to try again."); return; }
        Location c = colosseumCentre();
        if (c == null || c.getWorld() != p.getWorld()) return;
        double dx = p.getLocation().getX() - c.getX(), dz = p.getLocation().getZ() - c.getZ();
        double r = Math.hypot(dx, dz);
        if (r < 10 || r > 75) { s.rs.circleLast = null; return; }
        double a = Math.atan2(dz, dx);
        if (s.rs.circleLast != null) s.rs.circleAngle += RiftEnigma.angleStep(s.rs.circleLast, a);
        s.rs.circleLast = a;
        int pct = (int) Math.min(100, Math.abs(s.rs.circleAngle) / (2 * Math.PI) * 100);
        Sky.vitals().flash(p, "&6Full Circle: &a" + pct + "% &7(" + (s.rs.circleUntil - now) / 1000 + "s)", 8);
        if (Math.abs(s.rs.circleAngle) >= 2 * Math.PI) {
            s.rs.circleDone = true;
            Msg.send(p, "&6You went all the way around! &7Go back to &6Digby the Mole&7.");
            Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.5f);
        }
    }

    private Location colosseumCentre() {
        Box b = Sky.worlds().area(RiftCatalog.ISLAND, "colosseum.pit");
        World w = Sky.worlds().world(RiftCatalog.ISLAND);
        if (b == null || w == null) return null;
        return new Location(w, (b.minX() + b.maxX()) / 2.0, b.minY(), (b.minZ() + b.maxZ()) / 2.0);
    }

    // ---- two plates ----
    private void plates(long now) {
        for (Puzzle pz : byRiddle.values()) {
            if (pz.r.kind() != RiftEnigma.Kind.PLATES || pz.plates.size() < 2 || pz.cage == null) continue;
            if (cageOpenUntil.containsKey(pz.r.id())) continue;
            Player a = onPlate(pz.plates.get(0)), b = onPlate(pz.plates.get(1));
            boolean solo = m.dials.soloTwoPlayer.get();
            if (a == null && b == null) continue;
            if (!(a != null && b != null && a != b) && !(solo && (a != null || b != null))) {
                Player one = a != null ? a : b;
                if (tickNo % 20 == 0) Sky.vitals().flash(one, "&eThe glass trembles... &7someone must stand on the other plate too!", 30);
                continue;
            }
            openCage(pz, now);
            for (Player p : new Player[]{a, b}) if (p != null) Msg.send(p, "&aThe glass slides open! &7Quick, before it closes.");
        }
    }

    private Player onPlate(Location plate) {
        for (Player p : plate.getWorld().getPlayers()) {
            if (m.session(p) == null) continue;
            Location l = p.getLocation();
            if (l.getBlockX() == plate.getBlockX() && l.getBlockZ() == plate.getBlockZ() && Math.abs(l.getY() - plate.getBlockY()) < 1.1) return p;
        }
        return null;
    }

    private void openCage(Puzzle pz, long now) {
        List<Location> removed = new ArrayList<>();
        Location c = pz.cage;
        for (int dy = 0; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    Block b = c.clone().add(dx, dy, dz).getBlock();
                    if (b.getType() == Material.GLASS) { b.setType(Material.AIR, false); removed.add(b.getLocation()); }
                }
        c.getWorld().playSound(c, Sound.BLOCK_GLASS_BREAK, 1f, 0.8f);
        cageRemoved.put(pz.r.id(), removed);
        cageOpenUntil.put(pz.r.id(), now + m.dials.cageOpenSeconds.get() * 1000L);
    }

    /** Close cages and regrow the living wall once nobody stands in the way. */
    private void restore(long now) {
        cageOpenUntil.entrySet().removeIf(e -> {
            if (e.getValue() > now) return false;
            List<Location> cells = cageRemoved.getOrDefault(e.getKey(), List.of());
            for (Location l : cells) if (occupied(l)) return false;
            for (Location l : cells) if (l.getBlock().getType().isAir()) l.getBlock().setType(Material.GLASS, false);
            cageRemoved.remove(e.getKey());
            return true;
        });
        wallRemoved.entrySet().removeIf(e -> {
            if (e.getValue() > now || occupied(e.getKey())) return false;
            if (e.getKey().getBlock().getType().isAir()) e.getKey().getBlock().setType(Material.CHISELED_TUFF_BRICKS, false);
            return true;
        });
    }

    private static boolean occupied(Location block) {
        for (Player p : block.getWorld().getPlayers()) {
            Location l = p.getLocation();
            if (l.getBlockX() == block.getBlockX() && l.getBlockZ() == block.getBlockZ() && l.getBlockY() >= block.getBlockY() - 1 && l.getBlockY() <= block.getBlockY()) return true;
        }
        return false;
    }

    // ---- the living wall ----
    private Puzzle wallOf(Block b) {
        ensure();
        for (Puzzle pz : byRiddle.values()) {
            if (pz.r.kind() != RiftEnigma.Kind.WALL || pz.cage == null || pz.cage.getWorld() != b.getWorld()) continue;
            if (Math.abs(b.getX() - pz.cage.getBlockX()) <= 1 && Math.abs(b.getZ() - pz.cage.getBlockZ()) <= 1
                    && b.getY() >= pz.cage.getBlockY() && b.getY() <= pz.cage.getBlockY() + 2) return pz;
        }
        return null;
    }

    private void breakWall(Player p, Block b) {
        if (m.session(p) == null) return;
        b.setType(Material.AIR, false);
        wallRemoved.put(b.getLocation(), System.currentTimeMillis() + m.dials.wallRegrowSeconds.get() * 1000L);
        p.getWorld().playSound(b.getLocation(), Sound.BLOCK_SCULK_BREAK, 1f, 0.7f);
        Sky.vitals().flash(p, "&9The living wall groans. &7It will grow back in " + m.dials.wallRegrowSeconds.get() + "s.", 30);
    }

    // ---- Leap of Faith: land on it from high above ----
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo().getY() >= e.getFrom().getY()) return;
        Player p = e.getPlayer();
        if (p.getFallDistance() < m.dials.leapHeight.get()) return;
        RiftSession s = m.session(p);
        if (s == null) return;
        Puzzle pz = byRiddle.get("rift_mountain_leap_of_faith");
        if (pz == null || pz.at.getWorld() != p.getWorld()) return;
        Location l = e.getTo();
        double dx = l.getX() - pz.at.getX(), dz = l.getZ() - pz.at.getZ(), dy = l.getY() - pz.at.getY();
        if (dx * dx + dz * dz > 2.6 || dy < -1 || dy > 4) return;
        RiftData d = m.data(p);
        if (d == null || d.souls.contains(pz.soul)) return;
        solve(p, pz.r.id(), "A leap of faith, rewarded.");
    }

    // =========================================================================================
    // clicks on props
    // =========================================================================================

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (m.session(p) != null) popBalloon(p, 5.0);
        }
        Block b = e.getClickedBlock();
        if (b == null || (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK)) return;
        if (!RiftCatalog.ISLAND.equals(Sky.worlds().islandOf(b.getWorld()))) return;
        ensure();
        Object[] prop = props.get(key(b));
        if (prop == null) return;
        e.setCancelled(true);
        RiftSession s = m.session(p);
        RiftData d = m.data(p);
        if (s == null || d == null) return;
        Sky.abilities().consumeClick(p);
        Puzzle pz = (Puzzle) prop[0];
        String kind = (String) prop[1];
        int idx = (Integer) prop[2];
        switch (kind) {
            case "pot" -> pot(p, s, d, pz, idx);
            case "rune" -> rune(p, s, d, pz, idx, b);
            case "cake" -> cake(p, s, d, pz, b);
            case "beacon", "hopper" -> beacon(p, d, pz, kind);
            case "button" -> button(p, s, d, idx, b);
            default -> { }
        }
    }

    private void pot(Player p, RiftSession s, RiftData d, Puzzle pz, int k) {
        if (d.riddles.contains(pz.r.id())) { Msg.send(p, "&7The flowers have nothing more to say."); return; }
        int done = s.rs.progress.getOrDefault(pz.r.id(), 0);
        if (k == done + 1) {
            s.rs.progress.put(pz.r.id(), k);
            Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f + k * 0.15f);
            p.spawnParticle(Particle.HAPPY_VILLAGER, pz.pots.get(k - 1).clone().add(0, 0.6, 0), 8, 0.2, 0.2, 0.2, 0);
            if (k >= pz.pots.size()) { s.rs.progress.remove(pz.r.id()); solve(p, pz.r.id(), "The last flower opens."); }
            else Sky.vitals().flash(p, "&aFlower pot &e" + k + "&7/" + pz.pots.size() + " &7- the next one is glowing.", 30);
        } else if (k == 1) {
            s.rs.progress.put(pz.r.id(), 1);
            Sky.vitals().flash(p, "&aFlower pot &e1&7/" + pz.pots.size() + " &7- start again from here.", 30);
        } else {
            s.rs.progress.put(pz.r.id(), 0);
            Msg.sound(p, Sound.BLOCK_GRASS_BREAK, 0.6f);
            Sky.vitals().flash(p, "&cThe flowers wilt. &7Start from the farthest pot.", 40);
        }
    }

    private void rune(Player p, RiftSession s, RiftData d, Puzzle pz, int k, Block b) {
        if (d.riddles.contains(pz.r.id())) return;
        int[] face = s.rs.runes.computeIfAbsent(pz.r.id(), id -> {
            int[] f = new int[pz.runes.size()];
            for (int i = 0; i < f.length; i++) f[i] = faceIndex(pz.runes.get(i).getBlock().getBlockData());
            return f;
        });
        if (k - 1 >= face.length) return;
        face[k - 1] = (face[k - 1] + 1) % 4;
        BlockData bd = b.getBlockData().clone();
        if (bd instanceof Directional dir) dir.setFacing(FACES[face[k - 1]]);
        p.sendBlockChange(b.getLocation(), bd);
        Msg.sound(p, Sound.BLOCK_GRINDSTONE_USE, 1.4f);
        int right = 0;
        for (int i = 0; i < face.length; i++) {
            Location r = pz.runes.get(i);
            if (face[i] == RiftEnigma.facingTowards(r.getX(), r.getZ(), pz.at.getX(), pz.at.getZ())) right++;
        }
        Sky.vitals().flash(p, "&dRunes facing the soul: &a" + right + "&7/" + face.length, 30);
        if (right == face.length) { s.rs.runes.remove(pz.r.id()); solve(p, pz.r.id(), "The runes glow and the roots let go."); }
    }

    static final BlockFace[] FACES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private static int faceIndex(BlockData bd) {
        if (!(bd instanceof Directional d)) return 0;
        for (int i = 0; i < 4; i++) if (FACES[i] == d.getFacing()) return i;
        return 0;
    }

    private void cake(Player p, RiftSession s, RiftData d, Puzzle pz, Block b) {
        if (d.riddles.contains(pz.r.id()) || d.souls.contains(pz.soul)) { Msg.send(p, "&7You could not eat another bite."); return; }
        int bites = s.rs.progress.merge(pz.r.id(), 1, Integer::sum);
        Msg.sound(p, Sound.ENTITY_GENERIC_EAT, 1f);
        int need = Math.max(1, pz.r.count());
        if (bites >= need) {
            p.sendBlockChange(b.getLocation(), Material.AIR.createBlockData());
            s.rs.progress.remove(pz.r.id());
            solve(p, pz.r.id(), "Inside the last slice: a soul.");
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) p.sendBlockChange(b.getLocation(), b.getBlockData()); }, 600L);
            return;
        }
        BlockData bd = b.getBlockData().clone();
        if (bd instanceof Cake c) c.setBites(Math.min(c.getMaximumBites(), bites * c.getMaximumBites() / need));
        p.sendBlockChange(b.getLocation(), bd);
        Sky.vitals().flash(p, "&dCake: &a" + bites + "&7/" + need + " bites", 20);
    }

    private void beacon(Player p, RiftData d, Puzzle pz, String which) {
        long now = System.currentTimeMillis();
        long[] t = beaconUse.computeIfAbsent(pz.r.id(), k -> new long[2]);
        UUID[] who = beaconWho.computeIfAbsent(pz.r.id(), k -> new UUID[2]);
        int i = which.equals("beacon") ? 0 : 1;
        t[i] = now;
        who[i] = p.getUniqueId();
        Msg.sound(p, i == 0 ? Sound.BLOCK_BEACON_ACTIVATE : Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1.3f);
        boolean together = Math.abs(t[0] - t[1]) <= 2500 && who[0] != null && who[1] != null
                && (!who[0].equals(who[1]) || m.dials.soloTwoPlayer.get());
        if (!together) {
            Sky.vitals().flash(p, "&eThe " + which + " hums. &7Someone must use the " + (i == 0 ? "hopper" : "beacon") + " at the same moment.", 40);
            return;
        }
        t[0] = t[1] = 0;
        for (UUID u : new HashSet<>(List.of(who[0], who[1]))) {
            Player q = Bukkit.getPlayer(u);
            if (q != null && m.session(q) != null) solve(q, pz.r.id(), "Beacon and hopper, together. Teamwork!");
        }
    }

    private void button(Player p, RiftSession s, RiftData d, int idx, Block b) {
        if (d.riddles.contains("rift_buttons_soul") || d.souls.contains(soulOf("rift_buttons_soul"))) return;
        Set<Integer> pressed = s.rs.hits.computeIfAbsent("buttons", k -> new HashSet<>());
        if (!pressed.add(idx)) { Sky.vitals().flash(p, "&7Already pressed this visit.", 20); return; }
        p.playSound(b.getLocation(), Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 1f, 1.2f);
        int total = buttons.size();
        Sky.vitals().flash(p, "&eButtons pressed: &a" + pressed.size() + "&7/" + total, 40);
        if (pressed.size() >= total) solve(p, "rift_buttons_soul", "Every button pressed. Something clicks open in the hayloft.");
    }

    String soulOf(String riddle) { Puzzle pz = puzzle(riddle); return pz == null ? "" : pz.soul; }

    // ---- balloons ----
    /** Pop the first balloon along the player's aim within {@code range}. */
    private boolean popBalloon(Player p, double range) {
        RiftSession s = m.session(p);
        RiftData d = m.data(p);
        if (s == null || d == null) return false;
        ensure();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        for (Puzzle pz : byRiddle.values()) {
            if (pz.r.kind() != RiftEnigma.Kind.BALLOONS || d.riddles.contains(pz.r.id()) || pz.at.getWorld() != p.getWorld()) continue;
            if (eye.distanceSquared(pz.at) > (range + 20) * (range + 20)) continue;
            Set<Integer> popped = s.rs.hits.computeIfAbsent(pz.r.id(), k -> new HashSet<>());
            for (int i = 0; i < pz.balloons.size(); i++) {
                if (popped.contains(i)) continue;
                Vector to = pz.balloons.get(i).toVector().subtract(eye.toVector());
                double along = to.dot(dir);
                if (along < 0 || along > range) continue;
                double off = to.clone().subtract(dir.clone().multiply(along)).length();
                if (off > 1.2) continue;
                popped.add(i);
                p.spawnParticle(Particle.DUST, pz.balloons.get(i), 30, 0.4, 0.4, 0.4, 0,
                        new Particle.DustOptions(BALLOON_COLORS[i % BALLOON_COLORS.length], 1.4f));
                p.playSound(pz.balloons.get(i), Sound.ENTITY_CHICKEN_EGG, 1f, 2f);
                Sky.vitals().flash(p, "&dPop! &7Balloons: &a" + popped.size() + "&7/" + pz.balloons.size(), 30);
                if (popped.size() >= pz.balloons.size()) solve(p, pz.r.id(), "The last balloon pops and a soul drifts down.");
                return true;
            }
        }
        return false;
    }

    /** Berberis Blowgun: a thorn that pops balloons, presses buttons, and only hurts chickens and Pufflings. */
    private boolean blowgun(AbilityService.Use use) {
        Player p = use.player();
        if (m.session(p) == null) { Msg.error(p, "The blowgun only works inside the Rift."); return false; }
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        for (double t = 1; t < 40; t += 1.5) p.spawnParticle(Particle.CRIT, eye.clone().add(dir.clone().multiply(t)), 1, 0, 0, 0, 0);
        Msg.sound(p, Sound.ENTITY_ARROW_SHOOT, 1.6f);
        if (popBalloon(p, 40)) return true;
        RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, dir, 40);
        if (hit != null && hit.getHitBlock() != null) {
            ensure();
            Object[] prop = props.get(key(hit.getHitBlock()));
            RiftSession s = m.session(p);
            RiftData d = m.data(p);
            if (prop != null && "button".equals(prop[1]) && s != null && d != null) button(p, s, d, (Integer) prop[2], hit.getHitBlock());
        }
        RayTraceResult ent = p.getWorld().rayTraceEntities(eye, dir, 40, 0.4, e -> e != p && e instanceof org.bukkit.entity.LivingEntity);
        if (ent != null && ent.getHitEntity() instanceof org.bukkit.entity.LivingEntity le) {
            var def = Sky.mobs().defOf(le);
            if (def != null && "rift_puff".equals(def.id) || le.getType() == org.bukkit.entity.EntityType.CHICKEN)
                Sky.combat().playerAttack(p, le, use.item(), com.skyclone.api.combat.DamageContext.Kind.PROJECTILE);
        }
        return true;
    }

    // =========================================================================================
    // the riddle NPCs
    // =========================================================================================

    private Location anchor(String npcKey) { return Sky.worlds().anchor(RiftCatalog.ISLAND, RiftEnigma.npcAnchor(npcKey)); }

    private void npcClick(Player p, String key) {
        RiftData d = m.data(p);
        if (d == null) return;
        if (m.session(p) == null) { Msg.error(p, "Come back when you are properly in the Rift."); return; }
        String id = RiftEnigma.npcId(key);
        switch (key) {
            case "rourke" -> shop(p, d, "rift_lagoon_shop", List.of("I fished this out of the pool. Finders keepers,", "sellers... sellers."));
            case "blitzcoin" -> shop(p, d, "rift_scammer", List.of("BLZ! The coin of tomorrow! It can only go up!", "Buy one today and I'll throw in a soul. Free!"));
            case "thistledown" -> shop(p, d, "rift_fairylosopher_soul", List.of("Is a soul for sale a soul at all?", "Discuss. Then pay."));
            case "skink" -> shop(p, d, "rift_mountain_sungecko_perks", List.of("Sun-warmed wares, fresh from the summit.", "The soul is the warmest of all."));
            case "mossbeard" -> {
                if (d.riddles.contains("rift_long_shrooms") || d.souls.contains(soulOf("rift_long_shrooms"))) {
                    Sky.npcs().talk(p, id, List.of("Go on, it's right where I said. Mind the mushrooms."), null);
                    return;
                }
                Sky.npcs().talk(p, id, List.of("A soul? Me? Hide a soul? ...Fine.", "I tucked it in among the reeds, right by me. Don't tell the mushrooms."),
                        () -> solve(p, "rift_long_shrooms", null));
            }
            case "vinny" -> hotdogIntro(p, d);
            case "yxvael" -> {
                if (solvedOrFound(d, "rift_lonely_dave")) { Sky.npcs().talk(p, id, List.of("You came back! ...From a safe distance, please."), null); return; }
                RiftSession s = m.session(p);
                Sky.npcs().talk(p, id, List.of("Nobody ever stays. Would you... stay? Just a little while?", "Not too close though. Personal space."), () -> {
                    s.rs.lonely = true;
                    s.rs.lonelySince = System.currentTimeMillis();
                });
            }
            case "prestwick" -> {
                if (solvedOrFound(d, "rift_rabbit_under_hat")) { Sky.npcs().talk(p, id, List.of("My finest trick! Thank you for staying so still."), null); return; }
                RiftSession s = m.session(p);
                Sky.npcs().talk(p, id, List.of("Ladies and gentlemen! A rabbit, from this empty hat!", "But rabbits are shy. Do NOT move a muscle until it appears. Abra..."), () -> {
                    s.rs.stillAt = p.getLocation().clone();
                    s.rs.stillSince = System.currentTimeMillis();
                });
            }
            case "okrel" -> {
                if (solvedOrFound(d, "rift_mountain_ikrus")) { Sky.npcs().talk(p, id, List.of("Still squatting. Still in the Rift. Worth it."), null); return; }
                RiftSession s = m.session(p);
                int need = puzzle("rift_mountain_ikrus") == null ? 20 : puzzle("rift_mountain_ikrus").r.count();
                Sky.npcs().talk(p, id, List.of("They say if you squat hard enough, the Rift lets you go.", "Squat with me! " + need + " squats, 30 seconds. GO!"), () -> {
                    s.rs.squats = 0;
                    s.rs.squatUntil = System.currentTimeMillis() + m.dials.squatSeconds.get() * 1000L;
                });
            }
            case "digby" -> {
                if (solvedOrFound(d, "rift_mole")) { Sky.npcs().talk(p, id, List.of("Round and round. Good times."), null); return; }
                RiftSession s = m.session(p);
                if (s.rs.circleDone) {
                    s.rs.circleDone = false;
                    s.rs.circleUntil = 0;
                    Sky.npcs().talk(p, id, List.of("All the way round! You're a natural mole. Here's your prize."), () -> solve(p, "rift_mole", null));
                    return;
                }
                Sky.npcs().talk(p, id, List.of("I dug a tunnel all the way round this arena once. Can you go round it faster?",
                        "Run a full circle around the Colosseum and come back. You have " + m.dials.circleSeconds.get() + " seconds!"), () -> {
                    s.rs.circleAngle = 0; s.rs.circleLast = null; s.rs.circleDone = false;
                    s.rs.circleUntil = System.currentTimeMillis() + m.dials.circleSeconds.get() * 1000L;
                });
            }
            case "ubaldo" -> ubaldo(p, d);
            default -> { }
        }
    }

    private boolean solvedOrFound(RiftData d, String riddle) { return d.riddles.contains(riddle) || d.souls.contains(soulOf(riddle)); }

    private int price(RiftEnigma.Riddle r) { return (int) Math.max(0, Math.round(r.cost() * m.dials.riddleShopScale.get())); }

    private void shop(Player p, RiftData d, String riddle, List<String> pitch) {
        RiftEnigma.Riddle r = RiftEnigma.riddle(riddle);
        if (r == null) return;
        String name = RiftEnigma.NPCS.get(r.npc());
        boolean got = solvedOrFound(d, riddle);
        Menu menu = new Menu(Text.strip(name), 3).chrome();
        List<String> lore = new ArrayList<>();
        for (String l : pitch) lore.add("&7" + l);
        lore.add("");
        int cost = price(r);
        if (got) lore.add("&aYou already bought it.");
        else {
            lore.add("&7Cost: &d" + Text.commasInt(cost) + " Motes");
            lore.add("&7You have: &d" + Text.commasInt(d.motes));
            lore.add("");
            lore.add(d.motes >= cost ? "&eClick to buy!" : "&cNot enough Motes.");
        }
        menu.set(13, Icons.glow(Icons.of(Material.ENDER_EYE, "&5Riddle Soul &8(" + r.name() + ")", lore)), c -> {
            RiftData dd = m.data(c.player());
            if (dd == null || solvedOrFound(dd, riddle)) return;
            if (!m.takeMotes(c.player(), cost)) { Msg.error(c.player(), "You need " + Text.commasInt(cost) + " Motes."); return; }
            c.player().closeInventory();
            solve(c.player(), riddle, "Bought for " + Text.commasInt(cost) + " Motes.");
        });
        menu.open(p);
    }

    // ---- Old Vel'ka's quiz ----
    static final String[][] QUIZ = {
            {"What do you earn in the Rift instead of coins?", "Motes", "Bits", "Gems"},
            {"How much Rift Time does a Mote Orb give back?", "2 seconds", "1 minute", "None at all"},
            {"How many Timecharms are hidden in the Rift?", "8", "5", "52"}};

    /** Old Vel'ka (RiftNpcs): after the first meeting she quizzes you for her soul. */
    boolean velkaQuiz(Player p) {
        RiftData d = m.data(p);
        if (d == null || solvedOrFound(d, "rift_tel_kar")) return false;
        long wait = d.riddleRetry.getOrDefault("rift_tel_kar", 0L) - System.currentTimeMillis();
        if (wait > 0) {
            Sky.npcs().talk(p, "rift_vel_ka", List.of("Wrong answers need time to settle. Come back in " + Text.duration(wait / 1000 + 1) + "."), null);
            return true;
        }
        Sky.npcs().talk(p, "rift_vel_ka", List.of("You want my soul? Show me you have been paying attention."), () -> quiz(p, 0));
        return true;
    }

    private void quiz(Player p, int q) {
        if (q >= QUIZ.length) {
            Msg.send(p, "&2[NPC] Old Vel'ka&f: Sharp as a leech's tooth. The soul is yours.");
            solve(p, "rift_tel_kar", null);
            return;
        }
        Menu menu = new Menu("Old Vel'ka asks... (" + (q + 1) + "/" + QUIZ.length + ")", 3).chrome();
        menu.set(4, Icons.of(Material.BOOK, "&2" + QUIZ[q][0], "&7Pick an answer."));
        List<Integer> order = new ArrayList<>(List.of(1, 2, 3));
        java.util.Collections.shuffle(order, rnd);
        int slot = 11;
        for (int opt : order) {
            final boolean right = opt == 1;
            menu.set(slot, Icons.of(Material.PAPER, "&e" + QUIZ[q][opt], "&eClick to answer!"), c -> {
                if (right) { Msg.sound(c.player(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.5f); quiz(c.player(), q + 1); }
                else {
                    c.player().closeInventory();
                    RiftData d = m.data(c.player());
                    if (d != null) d.riddleRetry.put("rift_tel_kar", System.currentTimeMillis() + m.dials.riddleRetrySeconds.get() * 1000L / 5);
                    Msg.send(c.player(), "&2[NPC] Old Vel'ka&f: Wrong. Go and look around some more.");
                }
            });
            slot += 2;
        }
        menu.open(p);
    }

    // ---- Vinny's hot dog showdown ----
    private void hotdogIntro(Player p, RiftData d) {
        if (solvedOrFound(d, "rift_hot_dog")) {
            Sky.npcs().talk(p, RiftEnigma.npcId("vinny"), List.of("You're a legend. My record is yours. Life goal: achieved. Sort of."), null);
            return;
        }
        int need = puzzle("rift_hot_dog") == null ? 20 : puzzle("rift_hot_dog").r.count();
        Sky.npcs().talk(p, RiftEnigma.npcId("vinny"), List.of("My life's goal: meet someone who can out-eat me.",
                need + " hot dogs, " + m.dials.hotdogSeconds.get() + " seconds. Click 'em as they land. Ready?"), () -> hotdog(p, need));
    }

    private void hotdog(Player p, int need) {
        Menu menu = new Menu("Hot Dog Showdown", 6).chrome();
        long[] start = {0};
        int[] eaten = {0};
        int[] at = {-1};
        Runnable[] place = new Runnable[1];
        place[0] = () -> {
            if (at[0] >= 0) menu.set(at[0], Icons.of(Material.WHITE_STAINED_GLASS_PANE, " "));
            int slot;
            do { slot = 9 + rnd.nextInt(36); } while (slot == at[0] || slot % 9 == 0 || slot % 9 == 8);
            at[0] = slot;
            menu.set(slot, Icons.of(Material.COOKED_PORKCHOP, "&cHOT DOG", "&7" + eaten[0] + "/" + need + " eaten", "&eClick to eat!"), c -> {
                long now = System.currentTimeMillis();
                if (start[0] == 0) start[0] = now;
                if (now - start[0] > m.dials.hotdogSeconds.get() * 1000L) {
                    c.player().closeInventory();
                    Msg.send(c.player(), "&c[NPC] Vinny Saucetoss&f: Time! " + eaten[0] + " dogs. Not bad, but my record stands. Try again!");
                    return;
                }
                eaten[0]++;
                Msg.sound(c.player(), Sound.ENTITY_GENERIC_EAT, 0.9f + rnd.nextFloat() * 0.4f);
                if (eaten[0] >= need) {
                    c.player().closeInventory();
                    Msg.send(c.player(), "&c[NPC] Vinny Saucetoss&f: " + need + " in " + Text.commas((now - start[0]) / 100 / 10.0) + "s?! You beat me! Take this. It's been in the mustard.");
                    solve(c.player(), "rift_hot_dog", null);
                    return;
                }
                place[0].run();
            });
        };
        for (int s = 9; s < 45; s++) if (s % 9 != 0 && s % 9 != 8) menu.set(s, Icons.of(Material.WHITE_STAINED_GLASS_PANE, " "));
        menu.set(4, Icons.of(Material.CLOCK, "&eHot Dog Showdown", "&7Eat " + need + " hot dogs in " + m.dials.hotdogSeconds.get() + "s.",
                "&7The clock starts on your first bite."));
        place[0].run();
        menu.open(p);
    }

    // ---- Ubaldo at the summit: Averages and Boxes ----
    private void ubaldo(Player p, RiftData d) {
        Menu menu = new Menu("Ubaldo Twofold", 3).chrome();
        boolean avg = solvedOrFound(d, "rift_mountain_ubik_averages"), box = solvedOrFound(d, "rift_mountain_ubik_boxes");
        menu.set(11, Icons.of(Material.COMPARATOR, "&eUbaldo's Averages", avg ? List.of("&aSolved!") : List.of(
                "&7Pick a number from 1 to 9. You win", "&7if it is the average of your pick", "&7and the last " + averages.size() + " picks anyone made.",
                "", "&7Last average: &e" + RiftEnigma.averageTarget(averages.subList(1, averages.size()), averages.get(0)), "", retryLine(d, "rift_mountain_ubik_averages"))),
                c -> { if (!avg && ready(c.player(), "rift_mountain_ubik_averages")) averagesMenu(c.player()); });
        menu.set(13, Icons.of(Material.CHEST, "&eUbaldo's Boxes", box ? List.of("&aSolved!") : List.of(
                "&7One of three boxes holds a soul.", "&7Pick one; I open an empty one;", "&7then you may switch.", "", retryLine(d, "rift_mountain_ubik_boxes"))),
                c -> { if (!box && ready(c.player(), "rift_mountain_ubik_boxes")) boxesMenu(c.player(), -1, rnd.nextInt(3)); });
        menu.set(15, Icons.of(Material.GOLD_INGOT, "&eTit for Tat", "&7Split or Steal is played down in", "&7the Wildwood stone circle.",
                "&7Play one round for my third soul.", "", solvedOrFound(d, "rift_mountain_ubik_tats") ? "&aSolved!" : "&cNot played yet."));
        menu.open(p);
    }

    private String retryLine(RiftData d, String riddle) {
        long wait = d.riddleRetry.getOrDefault(riddle, 0L) - System.currentTimeMillis();
        return wait > 0 ? "&cTry again in " + Text.duration(wait / 1000 + 1) + "." : "&eClick to play!";
    }

    private boolean ready(Player p, String riddle) {
        RiftData d = m.data(p);
        if (d == null) return false;
        long wait = d.riddleRetry.getOrDefault(riddle, 0L) - System.currentTimeMillis();
        if (wait > 0) { Msg.error(p, "Ubaldo is shuffling. Try again in " + Text.duration(wait / 1000 + 1) + "."); return false; }
        return true;
    }

    private void lose(Player p, String riddle, String line) {
        RiftData d = m.data(p);
        if (d != null) d.riddleRetry.put(riddle, System.currentTimeMillis() + m.dials.riddleRetrySeconds.get() * 1000L);
        p.closeInventory();
        Msg.send(p, "&e[NPC] Ubaldo Twofold&f: " + line);
        Msg.sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
    }

    private void averagesMenu(Player p) {
        Menu menu = new Menu("Ubaldo's Averages", 3).chrome();
        for (int n = 1; n <= 9; n++) {
            final int pick = n;
            menu.set(8 + n, Icons.amount(Icons.of(Material.PAPER, "&e" + n, "&eClick to pick " + n + "!"), n), c -> {
                List<Integer> recent = new ArrayList<>(averages);
                int target = RiftEnigma.averageTarget(recent, pick);
                averages.add(0, pick);
                while (averages.size() > 6) averages.remove(averages.size() - 1);
                if (pick == target) {
                    c.player().closeInventory();
                    Msg.send(c.player(), "&e[NPC] Ubaldo Twofold&f: The average was " + target + ". And you picked " + pick + ". Of course you did.");
                    solve(c.player(), "rift_mountain_ubik_averages", null);
                } else lose(c.player(), "rift_mountain_ubik_averages", "The average came out at " + target + ", not " + pick + ". Think about what everyone else picks.");
            });
        }
        menu.open(p);
    }

    private void boxesMenu(Player p, int first, int prize) {
        Menu menu = new Menu(first < 0 ? "Ubaldo's Boxes: pick one" : "Ubaldo's Boxes: switch?", 3).chrome();
        int opened = first < 0 ? -1 : RiftEnigma.boxToOpen(prize, first, 3);
        for (int i = 0; i < 3; i++) {
            final int box = i;
            int slot = 11 + i * 2;
            if (i == opened) { menu.set(slot, Icons.of(Material.BARRIER, "&7Box " + (i + 1), "&7Empty. Ubaldo opened it.")); continue; }
            String verb = first < 0 ? "&eClick to pick this box!" : i == first ? "&eClick to keep your box!" : "&eClick to switch to this box!";
            menu.set(slot, Icons.of(i == first ? Material.ENDER_CHEST : Material.CHEST, "&eBox " + (i + 1), verb), c -> {
                if (first < 0) { boxesMenu(c.player(), box, prize); return; }
                if (box == prize) {
                    c.player().closeInventory();
                    Msg.send(c.player(), "&e[NPC] Ubaldo Twofold&f: Box " + (box + 1) + "! " + (box == first ? "Stubborn, and right." : "You switched, and you were right to."));
                    solve(c.player(), "rift_mountain_ubik_boxes", null);
                } else lose(c.player(), "rift_mountain_ubik_boxes", "Empty! The soul was in box " + (prize + 1) + ".");
            });
        }
        menu.open(p);
    }

    /** Split or Steal resolved for this player (RiftSplitSteal): Ubaldo's Tit for Tat soul. */
    void splitStealPlayed(Player p) {
        if (p != null && m.session(p) != null) solve(p, "rift_mountain_ubik_tats", "You played Ubaldo's game. He keeps his word.");
    }

    /** The Infested House is clear (RiftNpcs.verminReward): the Pest Control soul. */
    void verminDone(Player p) { solve(p, "rift_kat_soul", "Not a squeak left. A soul was hiding under the floorboards."); }

    /** Check-ups for players who finished a quest before wave 5 added its soul. */
    void catchUp(Player p, RiftData d) {
        if (d.flags.contains("vermin_reward") && !solvedOrFound(d, "rift_kat_soul")) verminDone(p);
        if (d.splitStealRounds > 0 && !solvedOrFound(d, "rift_mountain_ubik_tats")) splitStealPlayed(p);
    }

    /** Self-test helper: every riddle that is not a walk-in has what it needs to be solved. */
    String check() {
        ensure();
        List<String> bad = new ArrayList<>();
        for (RiftEnigma.Riddle r : RiftEnigma.RIDDLES) {
            Puzzle pz = byRiddle.get(r.id());
            if (pz == null) { bad.add(r.id() + " has no soul"); continue; }
            if (r.npc() != null && anchor(r.npc()) == null) bad.add(r.id() + " npc anchor");
            if (r.kind() == RiftEnigma.Kind.WALL || r.kind() == RiftEnigma.Kind.PLATES) if (pz.cage == null) bad.add(r.id() + " cage");
        }
        if (buttons.isEmpty()) bad.add("no buttons");
        return bad.isEmpty() ? null : String.join(", ", bad);
    }

    /** Admin: solve one riddle for a player (grants the soul). */
    boolean adminSolve(Player p, String riddle) {
        if (RiftEnigma.riddle(riddle) == null) return false;
        RiftData d = m.data(p);
        if (d == null) return false;
        d.riddles.add(riddle);
        Puzzle pz = puzzle(riddle);
        if (pz != null) m.world.grantSoul(p, pz.soul);
        return true;
    }

    /** Show a title for big moments. */
    static void title(Player p, String a, String b) {
        p.showTitle(Title.title(Text.c(a), Text.c(b), Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1600), Duration.ofMillis(400))));
    }
}
