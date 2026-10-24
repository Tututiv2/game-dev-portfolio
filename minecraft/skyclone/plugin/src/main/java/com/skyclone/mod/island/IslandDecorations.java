package com.skyclone.mod.island;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Bukkit side of {@link Decor}: placing and breaking the Island Decorator's goods on a Private Island, running
 * parkour, finding eggs, filling showcases, the Island NPCs, and the floating text over the plinths. Every entity made
 * here is NON-persistent and re-made by {@link #tick} while someone is on the island, so nothing piles up in the world.
 */
final class IslandDecorations implements Listener {

    static final String TAG = "skyclone_island_decor";
    private static final NamespacedKey SEQ_KEY = new NamespacedKey("skyclone", "island_decor");

    private final IslandModule mod;
    private final IslandService svc;
    private final IslandDials dials;

    /** key -> pos -> placed (rebuilt when {@link #dirty} is called). */
    private final Map<String, Map<String, Decor.Placed>> index = new HashMap<>();
    /** key -> part id ("seq:label") -> entity. */
    private final Map<String, Map<String, UUID>> shown = new HashMap<>();
    private final Map<UUID, String> shownState = new HashMap<>();

    /** A parkour run in progress. */
    private static final class Active {
        final String key;
        Decor.Run run;
        Location checkpoint;
        Active(String key, Decor.Run run, Location checkpoint) { this.key = key; this.run = run; this.checkpoint = checkpoint; }
    }

    private final Map<UUID, Active> runs = new HashMap<>();
    private final Set<UUID> selfTeleport = new HashSet<>();
    private final Map<UUID, Long> throttle = new HashMap<>();

    IslandDecorations(IslandModule mod, IslandService svc) {
        this.mod = mod;
        this.svc = svc;
        this.dials = svc.dials();
    }

    // =========================================================================================
    // state
    // =========================================================================================

    Decor.State state(String key) { return svc.store().settings(key).decor; }

    void dirty(String key) { index.remove(key); }

    private Map<String, Decor.Placed> index(String key) {
        return index.computeIfAbsent(key, k -> {
            Map<String, Decor.Placed> m = new HashMap<>();
            Decor.State s = state(k);
            synchronized (svc.store().settings(k)) { for (Decor.Placed p : s.placed) m.put(p.pos(), p); }
            return m;
        });
    }

    /** The decoration at a block of island {@code key}, or null. */
    Decor.Placed placedAt(String key, Block b) {
        if (key == null || b == null) return null;
        long[] o = svc.origin(key);
        if (o == null) return null;
        Map<String, Decor.Placed> m = index(key);
        if (m.isEmpty()) return null;
        return m.get(Decor.posKey((int) (b.getX() - o[0]), b.getY(), (int) (b.getZ() - o[1])));
    }

    private Location world(String key, Decor.Placed p) {
        long[] o = svc.origin(key);
        World w = svc.world();
        if (o == null || w == null) return null;
        return new Location(w, o[0] + p.x, p.y, o[1] + p.z);
    }

    private void save(String key) { svc.store().save(key); dirty(key); }

    private boolean throttled(Player p, long ms) {
        long now = System.currentTimeMillis();
        Long t = throttle.get(p.getUniqueId());
        if (t != null && t > now) return true;
        throttle.put(p.getUniqueId(), now + ms);
        return false;
    }

    private static String label(String kind) { return Decor.label(kind); }

    // =========================================================================================
    // placing / breaking
    // =========================================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceCheck(BlockPlaceEvent e) {
        String id = Sky.items().idOf(e.getItemInHand());
        if (!Decor.isBlock(id)) return;
        Player p = e.getPlayer();
        if (!dials.decorEnabled.get()) { e.setCancelled(true); Msg.error(p, "Island decorations are turned off on this server."); return; }
        String key = svc.keyAt(e.getBlockPlaced().getLocation());
        if (key == null || !svc.isMember(p, key)) {
            e.setCancelled(true);
            Msg.error(p, "You can only place a " + label(id) + " on your own Private Island.");
            return;
        }
        String why;
        synchronized (svc.store().settings(key)) { why = Decor.placeProblem(state(key), id, dials.decorLimits()); }
        if (why != null) { e.setCancelled(true); Msg.error(p, why); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaced(BlockPlaceEvent e) {
        String id = Sky.items().idOf(e.getItemInHand());
        if (!Decor.isBlock(id) || !dials.decorEnabled.get()) return;
        Player p = e.getPlayer();
        Block b = e.getBlockPlaced();
        String key = svc.keyAt(b.getLocation());
        long[] o = svc.origin(key);
        if (key == null || o == null || !svc.isMember(p, key)) return;
        IslandSettings s = svc.store().settings(key);
        boolean timesReset;
        synchronized (s) {
            Decor.remove(s.decor, (int) (b.getX() - o[0]), b.getY(), (int) (b.getZ() - o[1]));   // a stale record on this spot
            Decor.add(s.decor, id, (int) (b.getX() - o[0]), b.getY(), (int) (b.getZ() - o[1]), System.currentTimeMillis(), p.getUniqueId().toString());
            timesReset = Decor.syncCourse(s.decor);
        }
        save(key);
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
        Msg.send(p, placedHint(key, id));
        if (timesReset) Msg.send(p, "&eThe parkour course changed, so its best times were reset.");
        if (Decor.START.equals(id) || Decor.POINT.equals(id)) cancelRunsOn(key, "&cThe parkour course changed - your run was cancelled.");
        sync(key);
    }

    private String placedHint(String key, String id) {
        Decor.State s = state(key);
        return switch (id) {
            case Decor.START -> Decor.count(s, Decor.START) == 1
                    ? "&aParkour Start placed! &7Add checkpoints (in order) and a second Start/End plate as the finish - or run it as a loop."
                    : "&aParkour finish placed! &7Step on the start to race. &8(/is parkour)";
            case Decor.POINT -> "&aCheckpoint #" + Decor.count(s, Decor.POINT) + " placed! &7Runners take checkpoints in the order you placed them.";
            case Decor.TIMES -> "&aParkour Times placed! &7It shows the island's best parkour times.";
            case Decor.DISPLAY -> "&aSocial Display placed! &7It shows your island's most frequent visitors.";
            case Decor.EGG -> "&aEgg hidden! &7(" + Decor.count(s, Decor.EGG) + "/" + dials.decorMax.get(Decor.EGG).get() + ") Guests who right-click it find it.";
            case Decor.SHOWCASE -> "&aShowcase placed! &7Right-click it holding an item to show it off; sneak + right-click with an empty hand to clear it.";
            default -> "&aPlaced " + label(id) + ".";
        };
    }

    /** A decoration's block, or one hanging on it, is about to go: refund it (never the vanilla drop). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!svc.inIslandWorld(b.getLocation())) return;
        String key = svc.keyAt(b.getLocation());
        if (key == null) return;
        // something hanging on this block would pop off with a vanilla drop: make them take it down first
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block nb = b.getRelative(f);
            Decor.Placed on = placedAt(key, nb);
            if (on == null) continue;
            boolean wallHead = nb.getType() == Material.PLAYER_WALL_HEAD;
            boolean hangs = f == BlockFace.UP
                    ? !wallHead && (Decor.START.equals(on.kind) || Decor.POINT.equals(on.kind) || Decor.EGG.equals(on.kind))
                    : wallHead && nb.getBlockData() instanceof org.bukkit.block.data.Directional dir && dir.getFacing() == f;
            if (hangs) {
                e.setCancelled(true);
                Msg.error(e.getPlayer(), "Take the " + label(on.kind) + " off this block first.");
                return;
            }
        }
        Decor.Placed d = placedAt(key, b);
        if (d == null) return;
        e.setDropItems(false);
        e.setExpToDrop(0);
        remove(key, d, e.getPlayer(), true);
    }

    /** Remove a placed decoration (record + its entities); {@code refund} gives the item back to {@code to}. */
    void remove(String key, Decor.Placed d, Player to, boolean refund) {
        IslandSettings s = svc.store().settings(key);
        boolean timesReset;
        synchronized (s) {
            s.decor.placed.remove(d);
            timesReset = Decor.syncCourse(s.decor);
        }
        save(key);
        hide(key, d.seq);
        if (Decor.START.equals(d.kind) || Decor.POINT.equals(d.kind)) cancelRunsOn(key, "&cThe parkour course changed - your run was cancelled.");
        if (to != null) {
            if (refund && Sky.items().exists(d.kind)) Sky.loot().give(to, d.kind, 1, LootSource.OTHER);
            Msg.send(to, "&7Took down the &a" + label(d.kind) + "&7." + (timesReset ? " &eThe parkour course changed, so its best times were reset." : ""));
        }
        sync(key);
    }

    // =========================================================================================
    // parkour
    // =========================================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo(), from = e.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY() && to.getBlockZ() == from.getBlockZ()) return;
        Player p = e.getPlayer();
        Active a = runs.get(p.getUniqueId());
        if (!svc.inIslandWorld(to)) { if (a != null) cancel(p, "&cParkour cancelled: you left the island."); return; }
        String key = svc.keyAt(to);
        if (a != null && !a.key.equals(key)) { cancel(p, "&cParkour cancelled: you left the island."); a = null; }
        if (key == null || !dials.decorEnabled.get()) return;
        Decor.Placed d = placedAt(key, to.getBlock());
        if (d == null || !(Decor.START.equals(d.kind) || Decor.POINT.equals(d.kind))) return;
        step(p, key, d, a);
    }

    private void step(Player p, String key, Decor.Placed d, Active a) {
        if (p.isFlying() || p.isGliding()) {
            if (!throttled(p, 2000)) Msg.error(p, "You can't run the parkour while flying!");
            if (a != null) cancel(p, null);
            return;
        }
        Decor.Course c;
        synchronized (svc.store().settings(key)) { c = Decor.course(state(key)); }
        Decor.Run[] out = new Decor.Run[1];
        long now = System.currentTimeMillis();
        Decor.Step st = Decor.step(c, a == null ? null : a.run, d.pos(), now, out);
        Location here = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        here.setYaw(p.getLocation().getYaw());
        switch (st) {
            case NONE -> {
                if (a == null && !c.ok() && !throttled(p, 4000)) Msg.send(p, "&e" + c.problem());
            }
            case STARTED -> {
                runs.put(p.getUniqueId(), new Active(key, out[0], here));
                Msg.send(p, "&a&lPARKOUR! &7Run started" + (c.checkpoints().isEmpty() ? "" : " - &e" + c.checkpoints().size() + " &7checkpoint" + (c.checkpoints().size() == 1 ? "" : "s")) + ". &8(/is parkour checkpoint | restart | cancel)");
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f);
            }
            case RESTARTED -> {
                runs.put(p.getUniqueId(), new Active(key, out[0], here));
                Msg.send(p, "&aTimer reset to 0!");
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f);
            }
            case CHECKPOINT -> {
                a.checkpoint = here;
                Msg.send(p, "&aReached checkpoint &e#" + a.run.next + "&7/&e" + c.checkpoints().size() + " &7after &f" + Decor.time(now - a.run.startMs) + "&7!");
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_BELL, 1.6f);
            }
            case WRONG_CHECKPOINT -> { if (!throttled(p, 1500)) Msg.error(p, "Wrong checkpoint! Take checkpoint #" + (a.run.next + 1) + " first."); }
            case MISSED -> { if (!throttled(p, 1500)) Msg.error(p, "You haven't reached every checkpoint yet! (" + a.run.next + "/" + c.checkpoints().size() + ")"); }
            case COURSE_CHANGED -> cancel(p, "&cThe parkour course changed - your run was cancelled.");
            case FINISHED -> finish(p, key, c, out, a);
        }
    }

    private void finish(Player p, String key, Decor.Course c, Decor.Run[] out, Active a) {
        runs.remove(p.getUniqueId());
        long ms = a.run.finalMs;
        IslandSettings s = svc.store().settings(key);
        boolean best;
        Long old;
        synchronized (s) {
            old = s.decor.parkourBest.get(p.getUniqueId().toString());
            if (!c.signature().equals(s.decor.courseSig)) old = null;
            best = Decor.recordTime(s.decor, c.signature(), p.getUniqueId().toString(), ms, Math.max(1, dials.boardCap.get()));
        }
        if (best) save(key);
        Msg.send(p, "&a&lPARKOUR COMPLETE! &7You finished in &f" + Decor.time(ms) + "&7!"
                + (best ? (old == null ? " &6&lFIRST TIME!" : " &6&lNEW BEST! &8(was " + Decor.time(old) + ")") : " &8(best: " + Decor.time(old == null ? ms : old) + ")"));
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0);
        if (best && !svc.isMember(p, key))
            for (Player m : svc.membersOnline(key))
                if (IslandService.notifyOn(m)) Msg.send(m, "&b[Island] &f" + p.getName() + " &7set a parkour time of &f" + Decor.time(ms) + " &7on your island!");
        sync(key);
    }

    void cancel(Player p, String why) {
        if (runs.remove(p.getUniqueId()) != null && why != null) Msg.send(p, why);
    }

    private void cancelRunsOn(String key, String why) {
        for (UUID u : new ArrayList<>(runs.keySet())) {
            Active a = runs.get(u);
            if (a == null || !a.key.equals(key)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) cancel(p, why); else runs.remove(u);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (selfTeleport.contains(e.getPlayer().getUniqueId())) return;
        if (runs.containsKey(e.getPlayer().getUniqueId())) cancel(e.getPlayer(), "&cParkour cancelled: you teleported.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        runs.remove(e.getPlayer().getUniqueId());
        throttle.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(SkyEvents.SkyDeath e) {
        cancel(e.player, "&cParkour cancelled: you died.");
    }

    /** /is parkour checkpoint | restart | cancel | times | (none: course info). */
    void parkourCommand(Player p, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
        Active run = runs.get(p.getUniqueId());
        switch (sub) {
            case "checkpoint", "cp", "back" -> {
                if (run == null) { Msg.error(p, "You are not running a parkour course."); return; }
                selfTp(p, run.checkpoint);
            }
            case "restart", "reset" -> {
                if (run == null) { Msg.error(p, "You are not running a parkour course."); return; }
                Decor.Course c = Decor.course(state(run.key));
                Location start = c.start() == null ? null : world(run.key, c.start());
                cancel(p, null);
                if (start != null) selfTp(p, start.add(0.5, 0, 0.5));
                Msg.send(p, "&7Back to the start - step on the plate to go again.");
            }
            case "cancel", "stop", "quit" -> { if (run == null) Msg.error(p, "You are not running a parkour course."); else cancel(p, "&7Parkour cancelled."); }
            case "times", "top", "leaderboard" -> {
                String key = here(p);
                if (key == null) { Msg.error(p, "Stand on a private island to see its parkour times."); return; }
                Msg.send(p, timesLines(key, Math.max(1, dials.boardLines.get())));
            }
            default -> {
                String key = here(p);
                if (key == null) { Msg.error(p, "Stand on a private island to see its parkour course."); return; }
                Msg.send(p, courseLines(key));
            }
        }
    }

    private void selfTp(Player p, Location to) {
        if (to == null) return;
        selfTeleport.add(p.getUniqueId());
        try { IslandService.teleport(p, to); } finally { selfTeleport.remove(p.getUniqueId()); }
    }

    private String here(Player p) { return svc.inIslandWorld(p.getLocation()) ? svc.keyAt(p.getLocation()) : null; }

    List<String> courseLines(String key) {
        Decor.Course c = Decor.course(state(key));
        List<String> l = new ArrayList<>();
        l.add("&a&lParkour &7- " + svc.islandTitle(key));
        if (!c.ok()) l.add(" &e" + c.problem());
        else {
            l.add(" &7Start: &a" + c.start().pos() + (c.loop() ? " &8(also the finish - a loop)" : ""));
            l.add(" &7Checkpoints: &e" + c.checkpoints().size());
            if (!c.loop()) l.add(" &7Finish: &c" + c.end().pos());
        }
        l.add(" &8/is parkour times | checkpoint | restart | cancel");
        return l;
    }

    List<String> timesLines(String key, int n) {
        List<String> l = new ArrayList<>();
        l.add("&b&lParkour Times");
        var top = Decor.leaderboard(state(key).parkourBest, n);
        if (top.isEmpty()) l.add("&7No times yet - be the first!");
        int i = 1;
        for (var e : top) l.add("&e#" + (i++) + " &f" + svc.nameOf(UUID.fromString(e.getKey())) + " &7- &a" + Decor.time(e.getValue()));
        return l;
    }

    List<String> visitorLines(String key, int n) {
        IslandSettings s = svc.store().settings(key);
        List<String> l = new ArrayList<>();
        l.add("&6&lTop Visitors");
        var top = Decor.topVisitors(s.guestVisits, n);
        if (top.isEmpty()) l.add("&7Nobody has visited yet.");
        int i = 1;
        for (var e : top) {
            UUID u = IslandService.uuid(e.getKey());
            if (u == null) continue;
            l.add("&e#" + (i++) + " &f" + svc.nameOf(u) + " &7- &b" + e.getValue() + " visit" + (e.getValue() == 1 ? "" : "s"));
        }
        l.add("&7Unique guests: &b" + s.uniqueGuests.size());
        return l;
    }

    /** Action bar segment while running. */
    String actionBar(Player p) {
        Active a = runs.get(p.getUniqueId());
        if (a == null) return null;
        Decor.Course c = Decor.course(state(a.key));
        return "&a⏱ " + Decor.time(System.currentTimeMillis() - a.run.startMs) + (c.checkpoints().isEmpty() ? "" : " &7CP &e" + a.run.next + "&7/&e" + c.checkpoints().size());
    }

    // =========================================================================================
    // right-clicks: eggs, showcases, plinths, the NPC spawn egg
    // =========================================================================================

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || !svc.inIslandWorld(b.getLocation())) return;
        Player p = e.getPlayer();
        String key = svc.keyAt(b.getLocation());
        if (key == null) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (Decor.NPC.equals(Sky.items().idOf(hand))) {
            e.setCancelled(true);
            placeNpc(p, key, b.getRelative(e.getBlockFace()), hand);
            return;
        }
        Decor.Placed d = placedAt(key, b);
        if (d == null || !dials.decorEnabled.get()) return;
        switch (d.kind) {
            case Decor.EGG -> { e.setCancelled(true); egg(p, key, d, b); }
            case Decor.SHOWCASE -> {
                boolean empty = hand.getType().isAir();
                if (p.isSneaking() && !empty) return;      // sneak + block = build against the case as usual
                e.setCancelled(true);
                showcase(p, key, d, hand, empty);
            }
            case Decor.TIMES -> { if (hand.getType().isAir() && !throttled(p, 800)) Msg.send(p, timesLines(key, Math.max(1, dials.boardLines.get()))); }
            case Decor.DISPLAY -> { if (hand.getType().isAir() && !throttled(p, 800)) Msg.send(p, visitorLines(key, Math.max(1, dials.boardLines.get()))); }
            default -> { }
        }
    }

    private void egg(Player p, String key, Decor.Placed d, Block b) {
        if (throttled(p, 400)) return;
        IslandSettings s = svc.store().settings(key);
        if (svc.isMember(p, key)) {
            int found = 0;
            synchronized (s) { for (var f : s.decor.eggsFound.values()) if (f.contains(d.pos())) found++; }
            Msg.send(p, "&eThis egg has been found by &a" + found + " &eguest" + (found == 1 ? "" : "s") + ". &7Break it to take it back.");
            return;
        }
        boolean fresh;
        int[] prog;
        synchronized (s) {
            fresh = Decor.findEgg(s.decor, p.getUniqueId().toString(), d.pos(), Math.max(1, dials.boardCap.get()));
            prog = Decor.eggProgress(s.decor, p.getUniqueId().toString());
        }
        if (!fresh) { Msg.send(p, "&7You already found this egg! &8(" + prog[0] + "/" + prog[1] + ")"); return; }
        save(key);
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, c, 15, 0.3, 0.3, 0.3, 0);
        if (prog[0] >= prog[1]) {
            Msg.send(p, "&d&lEGG HUNT COMPLETE! &7You found every egg on &a" + svc.islandTitle(key) + "&7!");
            Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.4f);
            for (Player m : svc.membersOnline(key))
                if (IslandService.notifyOn(m)) Msg.send(m, "&b[Island] &f" + p.getName() + " &7found every egg on your island!");
        } else {
            Msg.send(p, "&dYou found an egg! &7(" + prog[0] + "/" + prog[1] + ")");
            Msg.sound(p, Sound.ENTITY_CHICKEN_EGG, 1.4f);
        }
    }

    private void showcase(Player p, String key, Decor.Placed d, ItemStack hand, boolean empty) {
        if (throttled(p, 300)) return;
        ItemStack shownItem = decode(d.item);
        boolean editor = svc.may(p, key, CoopPerm.BUILD) || Sky.worlds().buildBypass(p);
        if (editor && !empty) {
            ItemStack copy = displayCopy(hand);
            IslandSettings s = svc.store().settings(key);
            synchronized (s) { d.item = encode(copy); }
            save(key);
            p.sendMessage(Text.c("&aNow showing: ").append(copy.displayName()));
            Msg.sound(p, Sound.ITEM_ARMOR_EQUIP_GENERIC, 1.2f);
            refresh(key);
            return;
        }
        if (editor && p.isSneaking() && shownItem != null) {
            IslandSettings s = svc.store().settings(key);
            synchronized (s) { d.item = null; }
            save(key);
            Msg.send(p, "&7Showcase cleared.");
            refresh(key);
            return;
        }
        if (shownItem == null) Msg.send(p, editor ? "&7An empty showcase. Right-click it holding an item to show it off." : "&7An empty showcase.");
        else p.sendMessage(Text.c("&7Showcase: ").append(shownItem.displayName()));
    }

    /** A display-only picture of an item: one of it, with every plugin tag stripped (it is never a real item). */
    static ItemStack displayCopy(ItemStack hand) {
        ItemStack copy = hand.clone();
        copy.setAmount(1);
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            for (NamespacedKey k : new ArrayList<>(pdc.getKeys())) pdc.remove(k);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    private static String encode(ItemStack s) {
        try { return Base64.getEncoder().encodeToString(s.serializeAsBytes()); } catch (RuntimeException e) { return null; }
    }

    private static ItemStack decode(String b64) {
        if (b64 == null || b64.isBlank()) return null;
        try { return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64)); } catch (RuntimeException e) { return null; }
    }

    // =========================================================================================
    // Island NPCs
    // =========================================================================================

    private void placeNpc(Player p, String key, Block at, ItemStack hand) {
        if (!dials.decorEnabled.get()) { Msg.error(p, "Island decorations are turned off on this server."); return; }
        if (!svc.isMember(p, key)) { Msg.error(p, "You can only place an Island NPC on your own Private Island."); return; }
        if (!svc.may(p, key, CoopPerm.BUILD)) { Msg.error(p, "The island owner has not given you the \"Place Blocks\" permission."); return; }
        if (!svc.insideBuildSquare(key, at.getLocation())) { Msg.error(p, "That spot is outside your island's build area."); return; }
        if (!at.isPassable() || !at.getRelative(BlockFace.UP).isPassable()) { Msg.error(p, "There isn't room for an NPC there."); return; }
        long[] o = svc.origin(key);
        if (o == null) return;
        IslandSettings s = svc.store().settings(key);
        String why;
        synchronized (s) { why = Decor.placeProblem(s.decor, Decor.NPC, dials.decorLimits()); }
        if (why != null) { Msg.error(p, why); return; }
        if (!Sky.items().is(hand, Decor.NPC) || hand.getAmount() < 1) return;
        hand.setAmount(hand.getAmount() - 1);                       // consume BEFORE recording: a failure loses, never duplicates
        p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
        synchronized (s) {
            Decor.Placed d = Decor.add(s.decor, Decor.NPC, (int) (at.getX() - o[0]), at.getY(), (int) (at.getZ() - o[1]), System.currentTimeMillis(), p.getUniqueId().toString());
            d.name = "Resident";
            d.look = "none";
            d.yaw = p.getLocation().getYaw() + 180f;
        }
        save(key);
        sync(key);
        Msg.send(p, "&aYour Island NPC moved in! &7Right-click it to name it, give it a line and change its look.");
        Msg.sound(p, Sound.ENTITY_VILLAGER_YES, 1f);
    }

    private Decor.Placed npcOf(Entity ent) {
        if (!ent.getScoreboardTags().contains(TAG)) return null;
        Long seq = ent.getPersistentDataContainer().get(SEQ_KEY, PersistentDataType.LONG);
        if (seq == null) return null;
        String key = svc.keyAt(ent.getLocation());
        return key == null ? null : Decor.bySeq(state(key), seq);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcClick(PlayerInteractEntityEvent e) {
        Entity ent = e.getRightClicked();
        if (!ent.getScoreboardTags().contains(TAG)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Decor.Placed d = npcOf(ent);
        if (d == null) return;
        String key = svc.keyAt(ent.getLocation());
        if (svc.may(p, key, CoopPerm.BUILD) || (Sky.worlds().buildBypass(p) && svc.isMember(p, key))) { npcEditor(p, key, d.seq).open(p); return; }
        if (throttled(p, 1000)) return;
        Msg.send(p, "&e[NPC] &a" + (d.name == null ? "Resident" : d.name) + "&f: " + (d.text == null || d.text.isBlank() ? "Welcome to " + svc.islandTitle(key) + "!" : d.text));
        p.playSound(ent.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 1f, 1f);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcClickAt(PlayerInteractAtEntityEvent e) {
        if (e.getRightClicked().getScoreboardTags().contains(TAG)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDecorDamage(EntityDamageEvent e) {
        if (e.getEntity().getScoreboardTags().contains(TAG)) e.setCancelled(true);
    }

    Menu npcEditor(Player p, String key, long seq) {
        Decor.Placed d = Decor.bySeq(state(key), seq);
        Menu m = new Menu("Island NPC", 4).chrome();
        if (d == null) { m.set(13, Icons.of(Material.BARRIER, "&cThis NPC moved out")); return m; }
        String name = d.name == null ? "Resident" : d.name;
        m.set(4, Icons.of(Material.VILLAGER_SPAWN_EGG, "&a" + name,
                "&7Says: &f" + (d.text == null || d.text.isBlank() ? "&8(the default greeting)" : d.text),
                "&7Look: &e" + Text.titleCase(d.look == null ? "none" : d.look)));
        boolean typing = dials.customText.get();
        m.set(10, Icons.of(Material.NAME_TAG, "&aRename", "&7Give your NPC a name.", "", typing ? "&eClick to type!" : "&cTyping is turned off on this server."),
                c -> { if (typing) typeText(c.player(), key, seq, true); });
        m.set(11, Icons.of(Material.WRITABLE_BOOK, "&aSet Greeting", "&7What it says when a guest", "&7right-clicks it.", "",
                        typing ? "&eClick to type!" : "&cTyping is turned off on this server.", "&bRight-click to reset!"),
                c -> {
                    if (c.right()) { edit(key, seq, x -> x.text = null); npcEditor(c.player(), key, seq).open(c.player()); return; }
                    if (typing) typeText(c.player(), key, seq, false);
                });
        m.set(12, Icons.of(Material.LEATHER_CHESTPLATE, "&aChange Look", "&7Current: &e" + Text.titleCase(d.look == null ? "none" : d.look), "",
                        "&eLeft-click: next", "&eRight-click: previous"),
                c -> { edit(key, seq, x -> x.look = Decor.nextLook(x.look, c.right() ? -1 : 1)); npcEditor(c.player(), key, seq).open(c.player()); });
        m.set(14, Icons.of(Material.COMPASS, "&aFace Me", "&7Turn the NPC to look at you.", "", "&eClick!"),
                c -> {
                    Location at = world(key, d);
                    if (at == null) return;
                    Location pl = c.player().getLocation();
                    float yaw = (float) Math.toDegrees(Math.atan2(-(pl.getX() - (at.getX() + 0.5)), pl.getZ() - (at.getZ() + 0.5)));
                    edit(key, seq, x -> x.yaw = yaw);
                    c.player().closeInventory();
                });
        m.set(15, Icons.of(Material.ENDER_PEARL, "&aMove Here", "&7Move the NPC to where you", "&7are standing.", "", "&eClick!"),
                c -> moveNpc(c.player(), key, seq));
        m.set(16, Icons.of(Material.BARRIER, "&cPick Up", "&7Send the NPC back into its egg.", "&7You get the Island NPC back.", "", "&eClick to pick up!"),
                c -> {
                    c.player().closeInventory();
                    Decor.Placed x = Decor.bySeq(state(key), seq);
                    if (x != null) remove(key, x, c.player(), true);
                });
        return m;
    }

    private void edit(String key, long seq, java.util.function.Consumer<Decor.Placed> change) {
        IslandSettings s = svc.store().settings(key);
        synchronized (s) {
            Decor.Placed d = Decor.bySeq(s.decor, seq);
            if (d == null) return;
            change.accept(d);
        }
        save(key);
        refresh(key);
    }

    private void typeText(Player p, String key, long seq, boolean name) {
        Sky.menus().input(p, name ? "NPC name" : "NPC greeting", raw -> {
            String t = cleanTyped(p, raw, name ? 16 : Math.max(8, dials.npcTextMax.get()));
            if (t == null) return;
            if (!svc.may(p, key, CoopPerm.BUILD) && !Sky.worlds().buildBypass(p)) { Msg.error(p, "You can no longer edit this NPC."); return; }
            edit(key, seq, x -> { if (name) x.name = t; else x.text = t; });
            Msg.success(p, name ? "Your NPC is now called " + t + "." : "Greeting set.");
            npcEditor(p, key, seq).open(p);
        });
    }

    /** Player-typed text, cleaned; null (and tells them) when nothing usable is left or it is blocked. */
    String cleanTyped(Player p, String raw, int max) {
        if (!dials.customText.get()) { Msg.error(p, "Typing names is turned off on this server."); return null; }
        String t = Decor.cleanText(raw, max);
        if (t == null) { Msg.error(p, "Use letters, numbers, spaces and simple punctuation."); return null; }
        if (Decor.blocked(t, dials.textBlocklist.get())) { Msg.error(p, "That text is not allowed."); return null; }
        return t;
    }

    private void moveNpc(Player p, String key, long seq) {
        p.closeInventory();
        Location l = p.getLocation();
        if (!key.equals(svc.keyAt(l)) || !svc.insideBuildSquare(key, l)) { Msg.error(p, "Stand inside your island's build area."); return; }
        long[] o = svc.origin(key);
        if (o == null) return;
        Block b = l.getBlock();
        IslandSettings s = svc.store().settings(key);
        synchronized (s) {
            if (Decor.at(s.decor, (int) (b.getX() - o[0]), b.getY(), (int) (b.getZ() - o[1])) != null) { Msg.error(p, "Something else is already there."); return; }
        }
        edit(key, seq, x -> { x.x = (int) (b.getX() - o[0]); x.y = b.getY(); x.z = (int) (b.getZ() - o[1]); x.yaw = l.getYaw(); });
        hide(key, seq);
        sync(key);
        Msg.success(p, "Moved your NPC.");
    }

    // =========================================================================================
    // the shown entities (every 2 s for islands somebody is on)
    // =========================================================================================

    void tick() {
        World w = svc.world();
        Set<String> active = new HashSet<>();
        if (w != null) for (Player p : w.getPlayers()) {
            if (!Sky.profiles().isLoaded(p)) continue;
            String key = svc.keyAt(p.getLocation());
            if (key != null) active.add(key);
        }
        for (String key : active) {
            try { sync(key); } catch (RuntimeException e) { Sky.log().warning("[island] decor sync failed for " + key + ": " + e); }
        }
        for (String key : new ArrayList<>(shown.keySet())) if (!active.contains(key)) clear(key);
        // runs: time limit and flying
        long now = System.currentTimeMillis();
        long max = Math.max(10, dials.parkourMaxSeconds.get()) * 1000L;
        for (UUID u : new ArrayList<>(runs.keySet())) {
            Player p = Bukkit.getPlayer(u);
            Active a = runs.get(u);
            if (p == null || a == null) { runs.remove(u); continue; }
            if (now - a.run.startMs > max) cancel(p, "&cParkour cancelled: you ran out of time.");
            else if (p.isFlying() || p.isGliding()) cancel(p, "&cParkour cancelled: no flying!");
        }
        throttle.values().removeIf(t -> t < now);
        if (index.size() > 500) index.clear();
    }

    /** Re-applies text / item / NPC state now (after an edit). */
    void refresh(String key) { sync(key); }

    void sync(String key) {
        World w = svc.world();
        long[] o = svc.origin(key);
        if (w == null || o == null) return;
        if (!dials.decorEnabled.get()) { clear(key); return; }
        IslandSettings s = svc.store().settings(key);
        List<Decor.Placed> list;
        synchronized (s) { list = new ArrayList<>(s.decor.placed); }
        Decor.Course course = Decor.course(s.decor);
        Set<String> want = new HashSet<>();
        List<Decor.Placed> gone = new ArrayList<>();
        int lines = Math.max(1, dials.boardLines.get());
        for (Decor.Placed d : list) {
            int bx = (int) o[0] + d.x, bz = (int) o[1] + d.z;
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) {
                // keep whatever is up for an unloaded spot (its entity unloads with the chunk anyway)
                Map<String, UUID> m = shown.get(key);
                if (m != null) for (String id : m.keySet()) if (id.startsWith(d.seq + ":")) want.add(id);
                continue;
            }
            Block b = w.getBlockAt(bx, d.y, bz);
            if (Decor.isBlock(d.kind) && !Decor.materialMatches(d.kind, b.getType().name())) { gone.add(d); continue; }
            Location c = b.getLocation().add(0.5, 0, 0.5);
            switch (d.kind) {
                case Decor.START -> {
                    int role = course.role(d.pos());
                    String t = role == -1 ? "&a&lParkour Start" : course.loop() && course.start() == d ? "&a&lParkour Start &7/ &c&lFinish" : role == -2 ? "&c&lParkour Finish" : "&7Parkour Start/End";
                    if (!course.ok() && course.start() == d) t += "\n&e" + "Needs a checkpoint or a finish";
                    text(key, d, "label", c.clone().add(0, 1.2, 0), t, w, want);
                }
                case Decor.POINT -> {
                    int role = course.role(d.pos());
                    text(key, d, "label", c.clone().add(0, 1.2, 0), "&eCheckpoint #" + (role >= 0 ? role + 1 : "?"), w, want);
                }
                case Decor.TIMES -> text(key, d, "label", c.clone().add(0, 1.3, 0), String.join("\n", timesLines(key, lines)), w, want);
                case Decor.DISPLAY -> text(key, d, "label", c.clone().add(0, 1.3, 0), String.join("\n", visitorLines(key, lines)), w, want);
                case Decor.SHOWCASE -> {
                    ItemStack it = decode(d.item);
                    if (it != null) {
                        item(key, d, c.clone().add(0, 0.5, 0), it, d.item, w, want);
                        text(key, d, "label", c.clone().add(0, 1.25, 0), Text.toLegacy(it.effectiveName()), w, want);
                    }
                }
                case Decor.NPC -> npc(key, d, c, w, want);
                default -> { }
            }
        }
        if (!gone.isEmpty()) {
            synchronized (s) { s.decor.placed.removeAll(gone); Decor.syncCourse(s.decor); }
            save(key);
            for (Decor.Placed d : gone) Sky.log().info("[island] " + d.kind + " at " + d.pos() + " on " + key + " is no longer there; record dropped");
        }
        Map<String, UUID> m = shown.get(key);
        if (m != null) for (String id : new ArrayList<>(m.keySet())) if (!want.contains(id)) removePart(key, id);
    }

    private Entity live(String key, String id) {
        Map<String, UUID> m = shown.get(key);
        UUID u = m == null ? null : m.get(id);
        Entity e = u == null ? null : Bukkit.getEntity(u);
        return e != null && e.isValid() ? e : null;
    }

    private void track(String key, String id, Entity e, String stateSig) {
        shown.computeIfAbsent(key, k -> new HashMap<>()).put(id, e.getUniqueId());
        shownState.put(e.getUniqueId(), stateSig);
    }

    private void text(String key, Decor.Placed d, String part, Location at, String text, World w, Set<String> want) {
        String id = d.seq + ":" + part;
        want.add(id);
        Entity e = live(key, id);
        if (e instanceof TextDisplay td) {
            if (!text.equals(shownState.get(td.getUniqueId()))) { td.text(Text.c(text)); shownState.put(td.getUniqueId(), text); }
            if (td.getLocation().distanceSquared(at) > 0.01) td.teleport(at);
            return;
        }
        removePart(key, id);
        TextDisplay td = w.spawn(at, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.addScoreboardTag(TAG);
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
            t.setViewRange(0.4f);
            t.text(Text.c(text));
        });
        track(key, id, td, text);
    }

    private void item(String key, Decor.Placed d, Location at, ItemStack it, String sig, World w, Set<String> want) {
        String id = d.seq + ":item";
        want.add(id);
        Entity e = live(key, id);
        if (e instanceof ItemDisplay idp && sig.equals(shownState.get(idp.getUniqueId()))) return;
        removePart(key, id);
        ItemDisplay idp = w.spawn(at, ItemDisplay.class, t -> {
            t.setPersistent(false);
            t.addScoreboardTag(TAG);
            t.setItemStack(it);
            t.setBillboard(Display.Billboard.VERTICAL);
            t.setViewRange(0.4f);
            t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.55f, 0.55f, 0.55f), new AxisAngle4f()));
        });
        track(key, id, idp, sig);
    }

    private void npc(String key, Decor.Placed d, Location at, World w, Set<String> want) {
        String id = d.seq + ":npc";
        want.add(id);
        String name = d.name == null ? "Resident" : d.name;
        String sig = name + "|" + d.look + "|" + d.yaw + "|" + d.pos();
        Location loc = at.clone();
        loc.setYaw(d.yaw);
        Entity e = live(key, id);
        if (e instanceof Villager v) {
            if (!sig.equals(shownState.get(v.getUniqueId()))) {
                v.customName(Text.c("&a" + name));
                applyLook(v, d.look);
                v.teleport(loc);
                shownState.put(v.getUniqueId(), sig);
            }
            return;
        }
        removePart(key, id);
        Villager v = w.spawn(loc, Villager.class, t -> {
            t.setPersistent(false);
            t.addScoreboardTag(TAG);
            t.getPersistentDataContainer().set(SEQ_KEY, PersistentDataType.LONG, d.seq);
            t.setAI(false);
            t.setSilent(true);
            t.setInvulnerable(true);
            t.setCollidable(false);
            t.setRemoveWhenFarAway(false);
            t.customName(Text.c("&a" + name));
            t.setCustomNameVisible(true);
            applyLook(t, d.look);
        });
        if (v == null || !v.isValid()) return;
        track(key, id, v, sig);
    }

    private static void applyLook(Villager v, String look) {
        try {
            Villager.Profession prof = Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(look == null ? "none" : look));
            if (prof != null) v.setProfession(prof);
        } catch (RuntimeException ignored) { }
    }

    private void removePart(String key, String id) {
        Map<String, UUID> m = shown.get(key);
        if (m == null) return;
        UUID u = m.remove(id);
        if (u == null) return;
        shownState.remove(u);
        Entity e = Bukkit.getEntity(u);
        if (e != null) e.remove();
    }

    private void hide(String key, long seq) {
        Map<String, UUID> m = shown.get(key);
        if (m == null) return;
        for (String id : new ArrayList<>(m.keySet())) if (id.startsWith(seq + ":")) removePart(key, id);
    }

    void clear(String key) {
        Map<String, UUID> m = shown.remove(key);
        if (m == null) return;
        for (UUID u : m.values()) {
            shownState.remove(u);
            Entity e = Bukkit.getEntity(u);
            if (e != null) e.remove();
        }
    }

    void clearAll() {
        for (String k : new ArrayList<>(shown.keySet())) clear(k);
        runs.clear();
    }

    /** Admin: forget every decoration of an island (no refunds - the blocks stay as plain blocks). */
    int wipe(String key) {
        IslandSettings s = svc.store().settings(key);
        int n;
        synchronized (s) {
            n = s.decor.placed.size();
            s.decor = new Decor.State();
        }
        save(key);
        clear(key);
        cancelRunsOn(key, "&cParkour cancelled.");
        return n;
    }

    int activeRuns() { return runs.size(); }
}
