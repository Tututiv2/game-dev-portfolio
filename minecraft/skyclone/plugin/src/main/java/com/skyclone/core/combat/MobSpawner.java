package com.skyclone.core.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.mob.SpawnRule;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spawn rules. Runs once a second on the main thread. Never loads a world or a chunk: a rule
 * only works while its island world is loaded and a player is within activationRange, and spots
 * are only searched in loaded chunks.
 */
final class MobSpawner {

    static final class RuleState {
        final SpawnRule rule;
        final String source;
        int live;
        final ArrayDeque<Long> pending = new ArrayDeque<>();
        final List<Location> spots = new ArrayList<>();
        Box box;
        final List<Location> anchors = new ArrayList<>();
        int anchorIdx;
        long resolvedAt;
        boolean paused;
        boolean active;
        String status = "idle";

        RuleState(SpawnRule rule, String source) { this.rule = rule; this.source = source; }
    }

    private final MobServiceImpl mobs;
    private final Map<String, RuleState> rules = new LinkedHashMap<>();

    MobSpawner(MobServiceImpl mobs) { this.mobs = mobs; }

    // ---- registration -----------------------------------------------------------------------

    void add(SpawnRule r, String source) {
        if (r == null || r.mob == null || r.mob.isBlank() || r.island == null || r.island.isBlank()) {
            Sky.log().warning("[spawner] ignored a spawn rule without mob/island (" + source + ")");
            return;
        }
        if (r.id == null || r.id.isBlank()) r.id = r.island + "_" + r.mob + "_" + rules.size();
        if (r.anchors == null) r.anchors = new ArrayList<>();
        if (r.max < 0) r.max = 0;
        if (r.respawnSeconds < 0) r.respawnSeconds = 0;
        if (r.activationRange <= 0) r.activationRange = 64;
        if (rules.containsKey(r.id)) {
            Sky.log().warning("[spawner] duplicate spawn rule id '" + r.id + "' — keeping the first");
            return;
        }
        rules.put(r.id, new RuleState(r, source));
    }

    int load(String module, String file) {
        JsonArray arr = mobs.readArray(module, file);
        if (arr == null) return 0;
        int n = 0;
        for (JsonElement el : arr) {
            try {
                SpawnRule r = mobs.gson().fromJson(el, SpawnRule.class);
                int before = rules.size();
                add(r, module + "/" + file);
                if (rules.size() > before) n++;
            } catch (RuntimeException ex) {
                Sky.log().warning("[spawner] " + module + "/" + file + ": bad rule — " + ex.getMessage());
            }
        }
        Sky.log().info("[spawner] loaded " + n + " spawn rules from " + module + "/" + file);
        return n;
    }

    Collection<RuleState> states() { return Collections.unmodifiableCollection(rules.values()); }

    RuleState state(String id) { return rules.get(id); }

    // ---- counters fed by MobServiceImpl -----------------------------------------------------

    void onTracked(String ruleId) {
        if (ruleId == null) return;
        RuleState rs = rules.get(ruleId);
        if (rs != null) rs.live++;
    }

    void onUntracked(String ruleId, boolean died) {
        if (ruleId == null) return;
        RuleState rs = rules.get(ruleId);
        if (rs == null) return;
        rs.live = Math.max(0, rs.live - 1);
        if (died && rs.rule.respawnSeconds > 0) {
            rs.pending.add(System.currentTimeMillis() + (long) (rs.rule.respawnSeconds * 1000));
        }
    }

    // ---- the tick ---------------------------------------------------------------------------

    void tick() {
        if (!CombatDials.SPAWNER_ENABLED.get() || rules.isEmpty()) return;
        if (!Sky.has(WorldService.class)) return;
        WorldService ws = Sky.worlds();
        int budget = Math.max(0, CombatDials.SPAWN_GLOBAL.get());
        int perRule = Math.max(0, CombatDials.SPAWN_PER_RULE.get());
        int maxAlive = Math.max(0, CombatDials.MAX_ALIVE.get());
        long now = System.currentTimeMillis();
        for (RuleState rs : rules.values()) {
            rs.active = false;
            if (rs.paused) { rs.status = "paused"; continue; }
            SpawnRule r = rs.rule;
            MobDef def = mobs.def(r.mob);
            if (def == null) { rs.status = "unknown mob " + r.mob; continue; }
            World w = Bukkit.getWorld("sb_" + r.island);
            if (w == null) { rs.status = "world not loaded"; continue; }
            if (!conditionHolds(r.condition)) { rs.status = "condition '" + r.condition + "' false"; continue; }
            if (now - rs.resolvedAt > 30_000 || (rs.box == null && rs.anchors.isEmpty() && now - rs.resolvedAt > 5_000)) {
                resolve(rs, ws);
                rs.resolvedAt = now;
            }
            if (rs.box == null && rs.anchors.isEmpty()) { rs.status = "no area/anchors in layout"; continue; }
            if (!playerNear(rs, w)) { rs.status = "no player near"; continue; }
            rs.active = true;
            while (!rs.pending.isEmpty() && rs.pending.peekFirst() <= now) rs.pending.pollFirst();
            int deficit = r.max - rs.live - rs.pending.size();
            int n = Math.min(Math.min(deficit, perRule), Math.min(budget, maxAlive - mobs.aliveCount()));
            if (rs.box != null) refillSpots(rs, w, ws);
            int spawned = 0;
            for (int i = 0; i < n; i++) {
                Location at = pick(rs, w);
                if (at == null) break;
                if (mobs.spawnInternal(def, at, null, r.id) != null) spawned++;
            }
            budget -= spawned;
            rs.status = deficit > 0 && spawned == 0 && n > 0 ? "no valid spot" : "ok";
        }
        leash(ws);
    }

    /**
     * Rule-spawned mobs belong to their area. One that strays more than mob.leash-range outside
     * the area box (or furthest anchor), or walks into a SAFE/NO_MOBS zone, is put back on a valid
     * spot with its target cleared, or despawned when no spot of its area is loaded. Gate run 1: a pen
     * zombie walked onto the spawn plaza and killed the respawning player every 2 seconds.
     */
    private void leash(WorldService ws) {
        double range = CombatDials.LEASH_RANGE.get();
        if (range <= 0) return;
        double r2 = range * range;
        for (LiveMob lm : new ArrayList<>(mobs.liveMobs())) {
            if (lm.ruleId == null || lm.dead || lm.entity == null || !lm.entity.isValid()) continue;
            RuleState rs = rules.get(lm.ruleId);
            if (rs == null) continue;
            Location l = lm.entity.getLocation();
            boolean stray;
            if (rs.box != null) {
                stray = distSqToBox(rs.box, l.getX(), l.getY(), l.getZ()) > r2;
                // A layered area (Crypt under the Graveyard) needs a vertical limit of its own: 13 blocks
                // up is well inside a 24-block sphere, and Lv60 Golden Ghouls walked the Lv1 Graveyard.
                double vert = CombatDials.LEASH_VERTICAL.get();
                if (!stray && vert > 0) {
                    double y = l.getY();
                    double dy = y < rs.box.minY() ? rs.box.minY() - y : (y > rs.box.maxY() + 1 ? y - rs.box.maxY() - 1 : 0);
                    stray = dy > vert;
                }
            } else if (!rs.anchors.isEmpty()) {
                double best = Double.MAX_VALUE;
                for (Location a : rs.anchors) if (a.getWorld() == l.getWorld()) best = Math.min(best, a.distanceSquared(l));
                stray = best > r2;
            } else continue;
            if (!stray && !forbidden(ws, l)) continue;
            Location back = pick(rs, l.getWorld());
            if (back == null) {
                // Nowhere loaded to put it back: the player who pulled it out has left the area, so its
                // chunks (and the spot cache) are gone. Despawn it; the rule refills when someone is near.
                // Playtest "loops" 2026-10-03: Crypt Ghouls and the Golden Ghoul followed a player out of
                // the Crypt and roamed the Village for good, because this branch used to do nothing.
                lm.entity.remove();
                continue;
            }
            if (lm.entity instanceof org.bukkit.entity.Mob m) m.setTarget(null);
            lm.entity.teleport(back);
        }
    }

    static boolean conditionHolds(String cond) {
        if (cond == null || cond.isBlank()) return true;
        String c = cond.trim().toLowerCase(Locale.ROOT);
        boolean negate = c.startsWith("!");
        if (negate) c = c.substring(1);
        boolean v;
        try {
            if (c.equals("night")) v = Sky.calendar().now().isNight();
            else if (c.equals("day")) v = !Sky.calendar().now().isNight();
            else if (c.startsWith("event:")) v = Sky.calendar().active(cond.trim().substring(negate ? 7 : 6));
            else return false;   // unknown condition: closed by default
        } catch (RuntimeException e) {
            return false;
        }
        return negate != v;
    }

    private void resolve(RuleState rs, WorldService ws) {
        SpawnRule r = rs.rule;
        rs.box = null;
        rs.anchors.clear();
        rs.spots.clear();
        try {
            if (r.area != null && !r.area.isBlank()) rs.box = ws.area(r.island, r.area);
            for (String a : r.anchors) {
                if (a == null || a.isBlank()) continue;
                if (a.endsWith("*")) {
                    rs.anchors.addAll(ws.anchors(r.island, a.substring(0, a.length() - 1)));
                } else {
                    Location l = ws.anchor(r.island, a);
                    if (l != null) rs.anchors.add(l);
                }
            }
        } catch (RuntimeException e) {
            mobs.warnOnce("resolve:" + r.id, "[spawner] could not resolve rule " + r.id + ": " + e);
        }
    }

    private static boolean playerNear(RuleState rs, World w) {
        double range = rs.rule.activationRange;
        double r2 = range * range;
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            Location l = p.getLocation();
            if (rs.box != null && distSqToBox(rs.box, l.getX(), l.getY(), l.getZ()) <= r2) return true;
            for (Location a : rs.anchors) {
                if (a.getWorld() == w && a.distanceSquared(l) <= r2) return true;
            }
        }
        return false;
    }

    static double distSqToBox(Box b, double x, double y, double z) {
        double dx = x < b.minX() ? b.minX() - x : (x > b.maxX() + 1 ? x - b.maxX() - 1 : 0);
        double dy = y < b.minY() ? b.minY() - y : (y > b.maxY() + 1 ? y - b.maxY() - 1 : 0);
        double dz = z < b.minZ() ? b.minZ() - z : (z > b.maxZ() + 1 ? z - b.maxZ() - 1 : 0);
        return dx * dx + dy * dy + dz * dz;
    }

    private Location pick(RuleState rs, World w) {
        if (!rs.anchors.isEmpty()) {
            for (int tries = 0; tries < rs.anchors.size(); tries++) {
                Location a = rs.anchors.get(Math.floorMod(rs.anchorIdx++, rs.anchors.size()));
                if (a.getWorld() == null) continue;
                if (a.getWorld().isChunkLoaded(a.getBlockX() >> 4, a.getBlockZ() >> 4)) return a.clone();
            }
            return null;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        while (!rs.spots.isEmpty()) {
            int i = rnd.nextInt(rs.spots.size());
            Location s = rs.spots.get(i);
            if (w.isChunkLoaded(s.getBlockX() >> 4, s.getBlockZ() >> 4) && standable(w, s.getBlockX(), s.getBlockY(), s.getBlockZ())) {
                return new Location(w, s.getBlockX() + 0.5, s.getBlockY() + 1, s.getBlockZ() + 0.5, rnd.nextFloat() * 360f, 0f);
            }
            rs.spots.remove(i);
        }
        return null;
    }

    /** Keep a cache of up to 24 standable floor blocks per area; at most 6 column scans per second. */
    private void refillSpots(RuleState rs, World w, WorldService ws) {
        if (rs.spots.size() >= 24) return;
        Box b = rs.box;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // The box is where a mob may STAND (feet space), so the floor block can be one below minY.
        // Scanning only minY..maxY found nothing for the natural "air above the ground" box (gate run 1).
        int top = Math.min(b.maxY(), w.getMaxHeight() - 3);
        int bottom = Math.max(b.minY() - 1, w.getMinHeight());
        for (int attempt = 0; attempt < 6 && rs.spots.size() < 24; attempt++) {
            int x = b.minX() + rnd.nextInt(b.maxX() - b.minX() + 1);
            int z = b.minZ() + rnd.nextInt(b.maxZ() - b.minZ() + 1);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            List<Integer> ys = new ArrayList<>(4);
            for (int y = top; y >= bottom; y--) {
                if (standable(w, x, y, z)) { ys.add(y); y -= 2; }
            }
            if (ys.isEmpty()) continue;
            int y = ys.get(rnd.nextInt(ys.size()));
            Location l = new Location(w, x, y, z);
            if (forbidden(ws, new Location(w, x + 0.5, y + 1, z + 0.5))) continue;
            rs.spots.add(l);
        }
    }

    /** Solid floor at y, two passable non-liquid blocks above. */
    static boolean standable(World w, int x, int y, int z) {
        Block floor = w.getBlockAt(x, y, z);
        if (!floor.getType().isSolid()) return false;
        Block a = w.getBlockAt(x, y + 1, z), b = w.getBlockAt(x, y + 2, z);
        return a.isPassable() && !a.isLiquid() && b.isPassable() && !b.isLiquid();
    }

    private static boolean forbidden(WorldService ws, Location l) {
        try {
            Layout.Zone z = ws.zoneAt(l);
            return z != null && z.flags() != null && (z.flags().contains(Layout.Zone.SAFE) || z.flags().contains(Layout.Zone.NO_MOBS));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Admin: flip pause. Returns new paused state, or null if unknown. */
    Boolean togglePause(String id) {
        RuleState rs = rules.get(id);
        if (rs == null) return null;
        rs.paused = !rs.paused;
        return rs.paused;
    }
}
