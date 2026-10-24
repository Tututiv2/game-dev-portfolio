package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Box;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The Timecharm puzzles (research 08 sec 5.4-5.5, our own designs):
 * <ul>
 *   <li><b>Crown</b>: climb the Great Wyld Tree's spiral of steps to the crown.</li>
 *   <li><b>Glass</b>: the Looking-Glass Halls' three rooms - an upside-down jumping room, a maze whose safe
 *       path glows for a few seconds and then must be remembered, and a dance floor that calls colours.</li>
 *   <li><b>Quickmetal</b>: the Living Heart jumps to another spot in the cave each time you hit it.</li>
 *   <li><b>Starbound</b>: bring the other seven to the summit altar.</li>
 *   <li>Also the West Village race (Racer's Sneakers).</li>
 * </ul>
 * The others are quests (RiftNpcs) and boss kills (RiftCombat).
 */
final class RiftPuzzles implements Listener {

    static final String[] DANCE_NAMES = {"&cRED", "&6ORANGE", "&eYELLOW", "&aLIME", "&3CYAN", "&9BLUE", "&5PURPLE", "&dMAGENTA", "&fWHITE"};
    static final Material[] DANCE_WOOL = {Material.RED_WOOL, Material.ORANGE_WOOL, Material.YELLOW_WOOL, Material.LIME_WOOL, Material.CYAN_WOOL,
            Material.BLUE_WOOL, Material.PURPLE_WOOL, Material.MAGENTA_WOOL, Material.WHITE_WOOL};
    static final Material HEART_BLOCK = Material.SCULK_CATALYST;

    private final RiftModule m;
    private final Random rnd = new Random();
    private int heartSpot = -1;
    private Location heartLoc;

    RiftPuzzles(RiftModule m) { this.m = m; }

    void shutdown() { removeHeart(); }

    void forget(Player p) {
        RiftSession s = m.sessions.get(p.getUniqueId());
        if (s == null) return;
        s.raceStart = 0; s.raceNext = 0; s.danceRound = -1; s.maze = null;
    }

    // =========================================================================================
    // charms
    // =========================================================================================

    void grantCharm(Player p, String id) {
        RiftData d = m.data(p);
        RiftCatalog.Charm c = RiftCatalog.charm(id);
        if (d == null || c == null || !d.charms.add(id)) return;
        m.stats.invalidate(p);
        m.travel.addTime(p, m.dials.perCharm.get());
        p.showTitle(Title.title(Text.c("&a&lTIMECHARM"), Text.c("&d" + c.name()),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(600))));
        Msg.box(p, "&a&lTIMECHARM OBTAINED &d" + c.name(), List.of(
                "&7Your max Rift Time grew by &a" + RiftMath.clock(m.dials.perCharm.get()) + "ф&7.",
                "&7Timecharms: &d" + d.charms.size() + "&7/" + RiftCatalog.CHARMS.size(),
                "&7New places may now let you in. Check &e/rift areas&7.",
                "&7Secure it with &dCurator Ilsabet &7in the &dRift Gallery&7."));
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
        m.award(p, "timecharm:" + id, 100, "Timecharm");
        Sky.profiles().saveNow(p);
    }

    // =========================================================================================
    // every 5 ticks
    // =========================================================================================

    void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            RiftSession s = m.session(p);
            if (s == null) continue;
            RiftData d = m.data(p);
            if (d == null) continue;
            try {
                crown(p, d);
                parkour(p, s, d);
                maze(p, s, d);
                dance(p, s, d);
                race(p, s, d);
            } catch (Throwable t) {
                Sky.log().warning("[rift] puzzle tick for " + p.getName() + ": " + t);
            }
        }
    }

    private Location anchor(String name) { return Sky.worlds().anchor(RiftCatalog.ISLAND, name); }

    private static boolean near(Player p, Location l, double r) {
        return l != null && l.getWorld() == p.getWorld() && p.getLocation().distanceSquared(l) <= r * r;
    }

    private void crown(Player p, RiftData d) {
        if (d.charms.contains("supreme")) return;
        Location top = anchor("charm.supreme.top");
        if (top != null && near(p, top, 6) && p.getLocation().getY() >= top.getY() - 1) grantCharm(p, "supreme");
    }

    // ---- Looking-Glass: the upside-down room ----
    private void parkour(Player p, RiftSession s, RiftData d) {
        Box room = Sky.worlds().area(RiftCatalog.ISLAND, "mirror.parkour.room");
        Location start = anchor("mirror.parkour.start");
        if (room == null || start == null) return;
        Location l = p.getLocation();
        boolean inRoom = l.getX() >= room.minX() && l.getX() < room.maxX() + 1 && l.getZ() >= room.minZ() && l.getZ() < room.maxZ() + 1
                && l.getY() >= room.minY() && l.getY() < room.maxY() + 1;
        if (inRoom && l.getY() < start.getY() - 1.5) {
            p.teleport(start);
            p.setFallDistance(0);
            Msg.sound(p, Sound.BLOCK_GLASS_BREAK, 1.2f);
            Sky.vitals().flash(p, "&cYou fell through the floor of the ceiling!", 30);
            return;
        }
        if (!d.mirror.contains("parkour") && near(p, anchor("mirror.parkour.end"), 2.2)) roomDone(p, d, "parkour", "the upside-down room");
    }

    // ---- Looking-Glass: the maze ----
    private void maze(Player p, RiftSession s, RiftData d) {
        Box tiles = Sky.worlds().area(RiftCatalog.ISLAND, "mirror.maze.tiles");
        Location start = anchor("mirror.maze.start");
        if (tiles == null || start == null) return;
        Location l = p.getLocation();
        int w = tiles.maxX() - tiles.minX() + 1, len = tiles.maxZ() - tiles.minZ() + 1;
        if (near(p, start, 1.6)) {
            if (s.maze == null) {
                s.maze = RiftMath.mazePath(w, len, rnd.nextLong());
                s.mazeRevealUntil = System.currentTimeMillis() + m.dials.mazeRevealSeconds.get() * 1000L;
                Msg.send(p, "&b[Looking-Glass] &7Watch the floor. The safe tiles glow for &a" + m.dials.mazeRevealSeconds.get() + "s&7, then you are on your own.");
            }
        }
        if (s.maze == null) return;
        if (System.currentTimeMillis() < s.mazeRevealUntil) {
            World wd = p.getWorld();
            // row 0 is the side next to the start (the high-z end)
            for (int row = 0; row < len; row++)
                for (int col = 0; col < w; col++)
                    if (RiftMath.mazeSafe(s.maze, row, col))
                        p.spawnParticle(Particle.HAPPY_VILLAGER, new Location(wd, tiles.minX() + col + 0.5, tiles.minY() + 1.1, tiles.maxZ() - row + 0.5), 1, 0.15, 0, 0.15, 0);
        }
        int bx = l.getBlockX(), bz = l.getBlockZ();
        if (Math.abs(l.getY() - (tiles.minY() + 1)) < 0.6 && bx >= tiles.minX() && bx <= tiles.maxX() && bz >= tiles.minZ() && bz <= tiles.maxZ()) {
            int row = tiles.maxZ() - bz, col = bx - tiles.minX();
            if (!RiftMath.mazeSafe(s.maze, row, col)) {
                p.teleport(start);
                Msg.sound(p, Sound.BLOCK_GLASS_BREAK, 0.8f);
                Sky.vitals().flash(p, "&cThe tile shatters under you! Back to the start.", 30);
                s.mazeRevealUntil = System.currentTimeMillis() + 1500;     // a short reminder, not the full show
            }
        }
        if (!d.mirror.contains("maze") && near(p, anchor("mirror.maze.end"), 1.8)) { roomDone(p, d, "maze", "the hidden-path room"); s.maze = null; }
    }

    // ---- Looking-Glass: the dance floor ----
    private void dance(Player p, RiftSession s, RiftData d) {
        Box floor = Sky.worlds().area(RiftCatalog.ISLAND, "mirror.dance.floor");
        if (floor == null) return;
        Location l = p.getLocation();
        boolean on = l.getBlockX() >= floor.minX() && l.getBlockX() <= floor.maxX() && l.getBlockZ() >= floor.minZ() && l.getBlockZ() <= floor.maxZ()
                && Math.abs(l.getY() - (floor.minY() + 1)) < 1.2;
        long now = System.currentTimeMillis();
        if (s.danceRound < 0) {
            if (on && !d.mirror.contains("dance")) { s.danceRound = 0; s.danceDeadline = now + 3000; s.danceColor = -1;
                Msg.send(p, "&b[Looking-Glass] &7Stand on the colour I call. Ready..."); }
            return;
        }
        if (!on && s.danceColor >= 0) { failDance(p, s, "You left the dance floor."); return; }
        if (now < s.danceDeadline) return;
        if (s.danceColor >= 0) {
            Material under = l.clone().add(0, -0.2, 0).getBlock().getType();
            if (under != DANCE_WOOL[s.danceColor]) { failDance(p, s, "Wrong colour!"); return; }
            s.danceRound++;
            Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_BELL, 1f + s.danceRound * 0.05f);
            if (s.danceRound >= m.dials.danceRounds.get()) {
                s.danceRound = -1; s.danceColor = -1;
                roomDone(p, d, "dance", "the dance floor");
                return;
            }
        }
        int next;
        do { next = rnd.nextInt(DANCE_WOOL.length); } while (next == s.danceColor);
        s.danceColor = next;
        long window = Math.max(1200, 2600 - s.danceRound * 140L);
        s.danceDeadline = now + window;
        p.showTitle(Title.title(Text.c(DANCE_NAMES[next] + "!"), Text.c("&7Round " + (s.danceRound + 1) + "/" + m.dials.danceRounds.get()),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(window), Duration.ofMillis(100))));
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_HARP, 1.2f);
    }

    private void failDance(Player p, RiftSession s, String why) {
        s.danceRound = -1; s.danceColor = -1;
        p.showTitle(Title.title(Text.c("&c&lMISSED!"), Text.c("&7" + why + " Step back on to try again."),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300))));
        Msg.sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
        Location c = anchor("mirror.dance.center");
        if (c != null) p.teleport(c.clone().add(0, 0, 8));
    }

    private void roomDone(Player p, RiftData d, String room, String label) {
        if (!d.mirror.add(room)) return;
        Msg.success(p, "You beat " + label + "! &7(" + d.mirror.size() + "/3 Looking-Glass rooms)");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
        m.award(p, "mirror:" + room, 3, "Looking-Glass room");
        if (d.mirror.size() >= 3) grantCharm(p, "mirror");
    }

    // ---- the West Village race ----
    void startRace(Player p) {
        RiftSession s = m.session(p);
        if (s == null) return;
        s.raceStart = System.currentTimeMillis();
        s.raceNext = 1;
        Msg.send(p, "&e[NPC] &fRacer Gustav&f: Go go go! Touch all " + RiftCatalog.RACE_CHECKPOINTS + " green banners in order, then come back here. You have &a"
                + m.dials.raceSeconds.get() + "s&f!");
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 2f);
    }

    private void race(Player p, RiftSession s, RiftData d) {
        if (s.raceStart <= 0) return;
        long elapsed = System.currentTimeMillis() - s.raceStart;
        if (elapsed > m.dials.raceSeconds.get() * 1000L) {
            s.raceStart = 0;
            Msg.error(p, "Too slow! Talk to Racer Gustav to try again.");
            return;
        }
        Sky.vitals().flash(p, "&eRace: &a" + Text.commas(Math.floor(elapsed / 100.0) / 10.0) + "s &7(checkpoint "
                + Math.min(s.raceNext, RiftCatalog.RACE_CHECKPOINTS) + "/" + RiftCatalog.RACE_CHECKPOINTS + ")", 8);
        if (s.raceNext <= RiftCatalog.RACE_CHECKPOINTS) {
            Location cp = anchor("race." + s.raceNext);
            if (cp == null || near(p, cp, 3.5)) {
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f + s.raceNext * 0.1f);
                s.raceNext++;
            } else if (cp != null) p.spawnParticle(Particle.HAPPY_VILLAGER, cp.clone().add(0, 1.5, 0), 6, 0.3, 0.5, 0.3, 0);
            return;
        }
        if (near(p, anchor("race.finish"), 3.5)) {
            s.raceStart = 0;
            boolean best = d.raceBest == 0 || elapsed < d.raceBest;
            if (best) d.raceBest = elapsed;
            Msg.box(p, "&e&lRACE FINISHED", List.of("&7Time: &a" + Text.commas(Math.floor(elapsed / 100.0) / 10.0) + "s" + (best ? " &6&lNEW BEST!" : ""),
                    "&7Best: &a" + Text.commas(Math.floor(d.raceBest / 100.0) / 10.0) + "s"));
            if (d.flags.add("race_reward") && Sky.items().exists("GUNTHER_SNEAKERS")) {
                Sky.loot().give(p, "GUNTHER_SNEAKERS", 1, LootSource.REWARD);
                Msg.success(p, "Racer Gustav gives you his spare &9Racer's Sneakers&a!");
                m.award(p, "quest:race", 3, "West Village race");
            }
        }
    }

    // =========================================================================================
    // the Living Heart
    // =========================================================================================

    private List<Location> heartSpots() {
        List<Location> out = new ArrayList<>();
        for (int i = 1; i <= RiftCatalog.HEART_SPOTS; i++) {
            Location l = anchor("heart." + i);
            if (l != null) out.add(l);
        }
        return out;
    }

    void placeHeart() {
        List<Location> spots = heartSpots();
        if (spots.isEmpty() || !Sky.worlds().isBuilt(RiftCatalog.ISLAND)) return;
        int next = spots.size() == 1 ? 0 : rnd.nextInt(spots.size());
        if (next == heartSpot && spots.size() > 1) next = (next + 1) % spots.size();
        removeHeart();
        heartSpot = next;
        heartLoc = spots.get(next).getBlock().getLocation();
        if (heartLoc.getWorld().isChunkLoaded(heartLoc.getBlockX() >> 4, heartLoc.getBlockZ() >> 4))
            heartLoc.getBlock().setType(HEART_BLOCK, false);
    }

    private void removeHeart() {
        if (heartLoc != null && heartLoc.getWorld() != null && heartLoc.getBlock().getType() == HEART_BLOCK) heartLoc.getBlock().setType(Material.AIR, false);
        heartLoc = null;
    }

    void ensureHeart() {
        if (heartLoc == null) { placeHeart(); return; }
        if (heartLoc.getWorld().isChunkLoaded(heartLoc.getBlockX() >> 4, heartLoc.getBlockZ() >> 4) && heartLoc.getBlock().getType() != HEART_BLOCK)
            heartLoc.getBlock().setType(HEART_BLOCK, false);
    }

    Location heartLocation() { return heartLoc; }

    @EventHandler(priority = EventPriority.LOW)
    public void onHit(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b.getType() != HEART_BLOCK || heartLoc == null || !b.getLocation().equals(heartLoc)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        RiftData d = m.data(p);
        if (m.session(p) == null || d == null) return;
        d.heartHits++;
        int need = Math.max(1, m.dials.heartHitsNeeded.get());
        p.getWorld().spawnParticle(Particle.SCULK_SOUL, b.getLocation().add(0.5, 1, 0.5), 12, 0.3, 0.3, 0.3, 0.05);
        p.getWorld().playSound(b.getLocation(), Sound.BLOCK_SCULK_CATALYST_BLOOM, 1f, 1.4f);
        if (d.heartHits >= need) {
            d.heartHits = 0;
            d.heartsCaught++;
            Msg.success(p, "You caught the Living Heart!");
            if (Sky.items().exists("METAL_HEART")) Sky.loot().give(p, "METAL_HEART", 1, LootSource.MINING);
            grantCharm(p, "living");
        } else {
            Sky.vitals().flash(p, "&bThe Living Heart flees! &7(" + d.heartHits + "/" + need + ")", 30);
        }
        placeHeart();
    }

    // =========================================================================================
    // the summit
    // =========================================================================================

    /** Starkeeper: hand over the seven to receive the eighth. */
    boolean tryCelestial(Player p) {
        RiftData d = m.data(p);
        if (d == null || d.charms.contains("celestial")) return false;
        int have = 0;
        for (RiftCatalog.Charm c : RiftCatalog.CHARMS) if (!c.id().equals("celestial") && d.charms.contains(c.id())) have++;
        if (have < RiftCatalog.CHARMS.size() - 1) return false;
        grantCharm(p, "celestial");
        return true;
    }
}
