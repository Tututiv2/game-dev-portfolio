package com.skyclone.mod.island;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.island.gen.PrivateIslandGenerator;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Portal Isle (generator v4): one portal per destination island, standing on an islet joined to the
 * private island by a bridge. Research 03 §1.1 - "a frame appears on the Private Island for every island you
 * have unlocked": the frames are part of the template, and this class LIGHTS each one per island (nether-portal
 * blocks in the obsidian opening) once any member has visited that island and meets its warp requirement.
 *
 * <p>Walking into an opening warps through core ({@code Sky.worlds().warp}), judged on the WALKER's own unlocks
 * (so a guest uses their own progress). The areas are {@code isportal.<warp>}, not {@code portal.<warp>}, because
 * core's portal handling would warp anyone in without the visited check.
 *
 * <p>Labels are non-persistent text displays (gone on chunk unload or restart, re-made on the next sync), so
 * nothing ever piles up in the world. Islands built before v4 have no frames: {@link #frameIntact} finds no
 * obsidian and the class leaves them alone (player builds are never touched).
 */
final class IslandPortals implements Listener {

    static final String TAG = "skyclone_isportal";
    private static final String MISSING = "#missing";

    record Gate(String warp, Box area) {}

    private final IslandService svc;
    private List<Gate> gates;
    /** key -> warp -> label entity. */
    private final Map<String, Map<String, UUID>> labels = new HashMap<>();
    private final Map<UUID, String> labelText = new HashMap<>();
    /** key -> warps lit last sync (to announce newly opened portals). */
    private final Map<String, Set<String>> litLast = new HashMap<>();
    private final Map<UUID, Long> enterCooldown = new HashMap<>();

    IslandPortals(IslandService svc) { this.svc = svc; }

    // =========================================================================================
    // data
    // =========================================================================================

    List<Gate> gates() {
        if (gates != null && !gates.isEmpty()) return gates;
        Layout l = Sky.worlds().layout(IslandService.ISLAND);
        List<Gate> out = new ArrayList<>();
        if (l != null) for (String[] d : PrivateIslandGenerator.ALL_PORTALS) {
            Box b = l.area(PrivateIslandGenerator.PORTAL_AREA + d[0]);
            if (b != null) out.add(new Gate(d[0], b));
        }
        gates = out;
        return out;
    }

    static Map<String, WorldService.WarpInfo> warpMap() {
        Map<String, WorldService.WarpInfo> m = new LinkedHashMap<>();
        for (WorldService.WarpInfo w : Sky.worlds().warpInfos()) m.putIfAbsent(w.id(), w);
        return m;
    }

    static String nameOf(String warp, WorldService.WarpInfo wi) {
        if (wi != null && wi.displayName() != null && !wi.displayName().isBlank()) return wi.displayName();
        return Text.titleCase(warp);
    }

    /** Null when {@code p} may use the portal; else {@link #MISSING} or the failing requirement key. */
    static String blocker(Player p, WorldService.WarpInfo wi) {
        if (wi == null) return MISSING;
        IslandDef def = Sky.worlds().island(wi.island());
        if (def == null) return MISSING;
        String visited = "visited:" + wi.island();
        if (!Sky.requirements().check(p, visited)) return visited;
        if (def.requirement != null && !def.requirement.isBlank() && !Sky.requirements().check(p, def.requirement)) return def.requirement;
        if (wi.requirement() != null && !wi.requirement().isBlank() && !Sky.requirements().check(p, wi.requirement())) return wi.requirement();
        return null;
    }

    /** Is this gate open for island {@code key}: any online member may use it. */
    boolean openFor(String key, WorldService.WarpInfo wi) {
        for (Player m : svc.membersOnline(key)) if (blocker(m, wi) == null) return true;
        return false;
    }

    // =========================================================================================
    // lighting + labels (every 2 s for islands somebody is standing on)
    // =========================================================================================

    void tick() {
        World w = svc.world();
        Set<String> active = new HashSet<>();
        if (w != null) for (Player p : w.getPlayers()) {
            if (!Sky.profiles().isLoaded(p)) continue;
            String key = svc.keyAt(p.getLocation());
            if (key != null) active.add(key);
        }
        Map<String, WorldService.WarpInfo> warps = active.isEmpty() ? Map.of() : warpMap();
        for (String key : active) {
            try { sync(key, w, warps); } catch (RuntimeException e) { Sky.log().warning("[island] portal sync failed for " + key + ": " + e); }
        }
        for (String key : new ArrayList<>(labels.keySet())) if (!active.contains(key)) clear(key);
        // litLast is kept while a key is away (you visit the Barn, come back: THAT is when its portal opens);
        // bounded so a long-running server cannot grow it without limit
        if (litLast.size() > 2000) litLast.keySet().retainAll(active);
        long now = System.currentTimeMillis();
        enterCooldown.values().removeIf(t -> t < now);
    }

    void sync(String key, World w, Map<String, WorldService.WarpInfo> warps) {
        long[] o = svc.origin(key);
        if (o == null || w == null) return;
        Set<String> lit = new HashSet<>();
        Set<String> before = litLast.get(key);
        for (Gate g : gates()) {
            int bx = (int) o[0] + g.area().minX(), bz = (int) o[1] + g.area().minZ();
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) { if (before != null && before.contains(g.warp())) lit.add(g.warp()); continue; }
            if (!frameIntact(w, g, o)) { removeLabel(key, g.warp()); continue; }    // an island from before v4, or the owner tore it down
            WorldService.WarpInfo wi = warps.get(g.warp());
            boolean exists = wi != null && Sky.worlds().island(wi.island()) != null;
            boolean open = exists && openFor(key, wi);
            setFill(w, g, o, open);
            if (open) lit.add(g.warp());
            if (open && before != null && !before.contains(g.warp())) announce(key, w, g, o, nameOf(g.warp(), wi));
            label(key, w, g.warp(), g.area(), o, labelFor(g.warp(), wi, exists, open), 2.55);
        }
        litLast.put(key, lit);
        // the plaza's hub portal gets a sign too (core owns its walk-in; it is always open)
        Box hub = svc.templateArea("portal.hub");
        if (hub != null && w.isChunkLoaded((int) (o[0] + hub.minX()) >> 4, (int) (o[1] + hub.minZ()) >> 4)
                && w.getBlockAt((int) o[0] + hub.minX(), hub.minY(), (int) o[1] + hub.minZ()).getType() == Material.NETHER_PORTAL)
            label(key, w, "hub", hub, o, "&b&l" + nameOf("hub", warps.get("hub")) + "\n&aWalk in to travel!", 3.4);
        else removeLabel(key, "hub");
    }

    private static boolean frameIntact(World w, Gate g, long[] o) {
        int x0 = (int) o[0] + g.area().minX() - 1, x1 = (int) o[0] + g.area().maxX() + 1, z = (int) o[1] + g.area().minZ();
        int y = g.area().minY();
        return w.getBlockAt(x0, y + 1, z).getType() == Material.OBSIDIAN && w.getBlockAt(x1, y + 1, z).getType() == Material.OBSIDIAN
                && w.getBlockAt(x0 + 1, y - 1, z).getType() == Material.OBSIDIAN && w.getBlockAt(x0 + 1, g.area().maxY() + 1, z).getType() == Material.OBSIDIAN;
    }

    /** Light (portal blocks into AIR only - never over something a player placed) or darken (portal blocks only). */
    private static void setFill(World w, Gate g, long[] o, boolean open) {
        BlockData portal = null;
        Box a = g.area();
        for (int y = a.minY(); y <= a.maxY(); y++)
            for (int x = a.minX(); x <= a.maxX(); x++)
                for (int z = a.minZ(); z <= a.maxZ(); z++) {
                    Block b = w.getBlockAt((int) o[0] + x, y, (int) o[1] + z);
                    Material t = b.getType();
                    if (open && t.isAir()) {
                        if (portal == null) {
                            portal = Material.NETHER_PORTAL.createBlockData();
                            if (portal instanceof Orientable or) or.setAxis(a.maxX() > a.minX() ? Axis.X : Axis.Z);
                        }
                        b.setBlockData(portal, false);
                    } else if (!open && t == Material.NETHER_PORTAL) {
                        b.setType(Material.AIR, false);
                    }
                }
    }

    private static String labelFor(String warp, WorldService.WarpInfo wi, boolean exists, boolean open) {
        String name = nameOf(warp, wi);
        if (!exists) return "&8" + name + "\n&8Closed";
        if (open) return "&b&l" + name + "\n&aWalk in to travel!";
        return "&7" + name + "\n&cVisit it first to open";
    }

    private void label(String key, World w, String id0, Box a, long[] o, String text, double dy) {
        Map<String, UUID> m = labels.computeIfAbsent(key, k -> new HashMap<>());
        UUID id = m.get(id0);
        Entity e = id == null ? null : Bukkit.getEntity(id);
        if (e instanceof TextDisplay td && td.isValid()) {
            if (!text.equals(labelText.get(id))) { td.text(Text.c(text)); labelText.put(id, text); }
            return;
        }
        if (id != null) labelText.remove(id);
        Location at = new Location(w, o[0] + (a.minX() + a.maxX() + 1) / 2.0, a.maxY() + dy, o[1] + (a.minZ() + a.maxZ() + 1) / 2.0);
        TextDisplay td = w.spawn(at, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.addScoreboardTag(TAG);
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
            t.setViewRange(0.5f);
            t.text(Text.c(text));
        });
        m.put(id0, td.getUniqueId());
        labelText.put(td.getUniqueId(), text);
    }

    private void removeLabel(String key, String warp) {
        Map<String, UUID> m = labels.get(key);
        if (m == null) return;
        UUID id = m.remove(warp);
        if (id == null) return;
        labelText.remove(id);
        Entity e = Bukkit.getEntity(id);
        if (e != null) e.remove();
    }

    void clear(String key) {
        Map<String, UUID> m = labels.remove(key);
        if (m == null) return;
        for (UUID id : m.values()) {
            labelText.remove(id);
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
    }

    void clearAll() { for (String k : new ArrayList<>(labels.keySet())) clear(k); }

    private void announce(String key, World w, Gate g, long[] o, String name) {
        Box a = g.area();
        Location c = new Location(w, o[0] + (a.minX() + a.maxX() + 1) / 2.0, a.minY() + 1.5, o[1] + a.minZ() + 0.5);
        w.spawnParticle(Particle.PORTAL, c, 120, 0.8, 1.2, 0.4, 0.4);
        w.playSound(c, Sound.BLOCK_END_PORTAL_SPAWN, 0.6f, 1.4f);
        for (Player m : svc.membersOnline(key))
            Msg.send(m, "&5&lPORTAL OPENED! &dA portal to &b" + name + " &dnow stands on your island's Portal Isle.");
    }

    // =========================================================================================
    // walking in
    // =========================================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo(), from = e.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY() && to.getBlockZ() == from.getBlockZ()) return;
        if (!svc.inIslandWorld(to)) return;
        Box isle = svc.templateArea("portal_isle"), far = svc.templateArea("far_court");
        if (isle == null && far == null) return;
        String key = svc.keyAt(to);
        if (key == null) return;
        double[] r = svc.relative(key, to);
        if (r == null || !((isle != null && isle.containsXZ(r[0], r[2])) || (far != null && far.containsXZ(r[0], r[2])))) return;
        for (Gate g : gates()) {
            if (g.area().contains(r[0], r[1], r[2]) || g.area().contains(r[0], r[1] + 1, r[2])) {
                enter(e.getPlayer(), key, g);
                return;
            }
        }
    }

    private void enter(Player p, String key, Gate g) {
        long now = System.currentTimeMillis();
        Long until = enterCooldown.get(p.getUniqueId());
        if (until != null && until > now) return;
        enterCooldown.put(p.getUniqueId(), now + 2000);
        WorldService.WarpInfo wi = warpMap().get(g.warp());
        String why = blocker(p, wi);
        if (why == null) {
            Sky.worlds().warp(p, g.warp());
            return;
        }
        String name = nameOf(g.warp(), wi);
        if (MISSING.equals(why)) Msg.error(p, "This portal leads nowhere yet.");
        else if (why.startsWith("visited:")) Msg.error(p, "The portal to " + name + " opens once you have been there. Find the way from the Hub first!");
        else Sky.requirements().checkOrTell(p, why);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        // nudge them back out of the frame, toward the courtyard spot in front of it
        Layout l = Sky.worlds().layout(IslandService.ISLAND);
        Layout.Anchor front = l == null ? null : l.anchor(PrivateIslandGenerator.PORTAL_AREA + g.warp());
        long[] o = svc.origin(key);
        if (front != null && o != null) {
            Vector v = new Vector(o[0] + front.x() - p.getLocation().getX(), 0, o[1] + front.z() - p.getLocation().getZ());
            if (v.lengthSquared() > 1e-4) p.setVelocity(v.normalize().multiply(0.45).setY(0.25));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { enterCooldown.remove(e.getPlayer().getUniqueId()); }

    // =========================================================================================
    // retrofit: give an island built before v4 its Portal Isle
    // =========================================================================================

    /** Template-relative box holding the Portal Isle, the Far Court and their bridges (nothing else of the v3 island reaches past it). */
    static final Box RETRO = Box.of(-108, 20, 22, -22, 110, 56);
    /** Just the Far Court and its bridge (v6), for islands that already have the Portal Isle. Starts on the isle's west coast. */
    static final Box RETRO_FAR = Box.of(-108, 20, 26, -64, 110, 54);
    private static final java.util.Set<String> BRIDGE_PARTS = com.skyclone.mod.island.gen.IslandBridge.PARTS;
    private final Set<String> retrofitting = new HashSet<>();

    /** Does island {@code key} have Portal Isle frames (loads the one chunk it needs to look). */
    boolean hasIsle(String key) {
        World w = svc.world();
        long[] o = svc.origin(key);
        List<Gate> gs = gates();
        if (w == null || o == null || gs.isEmpty()) return true;              // nothing to offer
        Gate g = gs.get(0);
        w.getChunkAt((int) (o[0] + g.area().minX()) >> 4, (int) (o[1] + g.area().minZ()) >> 4);
        return frameIntact(w, g, o);
    }

    /** Does island {@code key} have the Far Court's frames (v6)? Loads the one chunk it needs. */
    boolean hasFarCourt(String key) {
        World w = svc.world();
        long[] o = svc.origin(key);
        if (w == null || o == null) return true;
        for (Gate g : gates()) {
            if (!g.warp().equals(PrivateIslandGenerator.FAR_PORTALS[0][0])) continue;
            w.getChunkAt((int) (o[0] + g.area().minX()) >> 4, (int) (o[1] + g.area().minZ()) >> 4);
            return frameIntact(w, g, o);
        }
        return true;                                                        // template without a Far Court: nothing to offer
    }

    /** What Gerald can still build on this island: "isle" (everything), "far" (the Far Court only) or null. */
    String missingPart(String key) {
        if (!hasIsle(key)) return "isle";
        if (!hasFarCourt(key)) return "far";
        return null;
    }

    /**
     * Paste the Portal Isle of the current template into an older island. Never overwrites anything: islet blocks go
     * only into columns that are EMPTY in the world (the void beside the old island), and the bridge's own parts go
     * into air where it meets the old coast. Runs the pure generator off-thread, then places ~3000 blocks a tick.
     */
    void retrofit(String key, org.bukkit.command.CommandSender report) {
        String part = missingPart(key);
        if (part == null) { tellTo(report, "&eThis island already has its Portal Isle and Far Court."); return; }
        retrofit(key, report, "far".equals(part) ? RETRO_FAR : RETRO, "far".equals(part) ? "Far Court" : "Portal Isle");
    }

    void retrofit(String key, org.bukkit.command.CommandSender report, Box box, String what) {
        if (!retrofitting.add(key)) { tellTo(report, "&eThe " + what + " is already being built."); return; }
        tellTo(report, "&7Building the &d" + what + "&7...");
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            com.skyclone.gen.IslandRun.Result run = com.skyclone.gen.IslandRun.run(new PrivateIslandGenerator(), com.skyclone.gen.IslandRun.seedOf(IslandService.ISLAND));
            List<int[]> cells = new ArrayList<>();
            List<String> states = new ArrayList<>();
            for (int x = box.minX(); x <= box.maxX(); x++)
                for (int z = box.minZ(); z <= box.maxZ(); z++)
                    for (int y = box.minY(); y <= box.maxY(); y++) {
                        if (run.canvas.isAir(x, y, z)) continue;
                        cells.add(new int[]{x, y, z});
                        states.add(run.canvas.get(x, y, z));
                    }
            return new Object[]{cells, states};
        }).whenComplete((res, err) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (err != null || res == null) {
                retrofitting.remove(key);
                tellTo(report, "&cCould not build the " + what + ": " + err);
                return;
            }
            @SuppressWarnings("unchecked") List<int[]> cells = (List<int[]>) res[0];
            @SuppressWarnings("unchecked") List<String> states = (List<String>) res[1];
            place(key, cells, states, report, box, what);
        }));
    }

    private void place(String key, List<int[]> cells, List<String> states, org.bukkit.command.CommandSender report, Box box, String what) {
        World w = svc.world();
        long[] o = svc.origin(key);
        if (w == null || o == null) { retrofitting.remove(key); tellTo(report, "&cThat island is not available."); return; }
        // which columns are empty void in the world right now (load the chunks once)
        for (int cx = (int) (o[0] + box.minX()) >> 4; cx <= (int) (o[0] + box.maxX()) >> 4; cx++)
            for (int cz = (int) (o[1] + box.minZ()) >> 4; cz <= (int) (o[1] + box.maxZ()) >> 4; cz++) w.getChunkAt(cx, cz);
        Set<Long> empty = new HashSet<>();
        for (int x = box.minX(); x <= box.maxX(); x++)
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                int top = w.getHighestBlockYAt((int) o[0] + x, (int) o[1] + z, org.bukkit.HeightMap.WORLD_SURFACE);
                if (top < box.minY() || w.getBlockAt((int) o[0] + x, top, (int) o[1] + z).getType().isAir()) empty.add(((long) x << 32) ^ (z & 0xFFFFFFFFL));
            }
        Map<String, BlockData> cache = new HashMap<>();
        int[] i = {0}, placed = {0};
        final int per = 3000;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), task -> {
            int end = Math.min(cells.size(), i[0] + per);
            for (; i[0] < end; i[0]++) {
                int[] c = cells.get(i[0]);
                String st = states.get(i[0]);
                boolean emptyCol = empty.contains(((long) c[0] << 32) ^ (c[2] & 0xFFFFFFFFL));
                if (!emptyCol && !BRIDGE_PARTS.contains(bare(st))) continue;
                Block b = w.getBlockAt((int) o[0] + c[0], c[1], (int) o[1] + c[2]);
                if (!b.getType().isAir()) continue;
                BlockData bd = cache.computeIfAbsent(st, IslandPortals::blockData);
                if (bd == null) continue;
                b.setBlockData(bd, false);
                placed[0]++;
            }
            if (i[0] >= cells.size()) {
                task.cancel();
                retrofitting.remove(key);
                tellTo(report, "&aThe &d" + what + " &ais ready! &7(" + placed[0] + " blocks, " + ("Far Court".equals(what)
                        ? "over the bridge west of the Portal Isle)" : "across the bridge south-west of the plaza)"));
                litLast.remove(key);
            }
        }, 1L, 1L);
    }

    private static String bare(String state) {
        String n = state.startsWith("minecraft:") ? state.substring(10) : state;
        int k = n.indexOf('[');
        return k >= 0 ? n.substring(0, k) : n;
    }

    static BlockData blockData(String state) {
        String full = state.startsWith("minecraft:") ? state : "minecraft:" + state;
        try { return Bukkit.createBlockData(full); }
        catch (IllegalArgumentException e) {
            try { return Bukkit.createBlockData("minecraft:" + bare(state)); } catch (IllegalArgumentException e2) { return null; }
        }
    }

    private static void tellTo(org.bukkit.command.CommandSender s, String msg) { if (s != null) s.sendMessage(Text.c(msg)); }

    // =========================================================================================
    // for menus / admin
    // =========================================================================================

    /** {name, state} per gate for island {@code key}: state = "open", "locked" or "closed". */
    List<String[]> status(String key) {
        Map<String, WorldService.WarpInfo> warps = warpMap();
        List<String[]> out = new ArrayList<>();
        for (Gate g : gates()) {
            WorldService.WarpInfo wi = warps.get(g.warp());
            boolean exists = wi != null && Sky.worlds().island(wi.island()) != null;
            out.add(new String[]{g.warp(), nameOf(g.warp(), wi), !exists ? "closed" : openFor(key, wi) ? "open" : "locked"});
        }
        return out;
    }
}
