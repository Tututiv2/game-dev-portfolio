package com.skyclone.mod.combatzones;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.combatzones.gen.DenGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spider's Den pacing (playtest-loops 2026-10-03: "after 9 kills nothing respawned within 40 blocks
 * for ~4 minutes; many spiders sit on terrain the player cannot reach"). Runs the real generator and
 * the shipping spawns.json and checks the two root causes stay fixed: every mountain-band rule owns
 * ONE compact ground (core cycles a rule's anchors round-robin, so the anchor list IS where respawns
 * land), and every spawn anchor is on a cell a player can walk to.
 */
class DenHuntingGroundsTest {

    static IslandRun.Result run;
    static Walkability.Report walk;

    @BeforeAll
    static void generate() {
        DenGenerator gen = new DenGenerator();
        run = IslandRun.run(gen, IslandRun.seedOf(gen.islandId()));
        Walkability.Options o = new Walkability.Options();
        o.skipFloating = true;
        walk = Walkability.analyse(run.canvas, run.layout, o);
    }

    @Test
    void everySpawnAnchorIsWalkableAndNoneAreSkipped() {
        assertNull(run.layout.data().get("walk.ignore"), "mob anchors must not be excluded from the walk check");
        List<String> bad = new ArrayList<>();
        for (Walkability.AnchorResult a : walk.anchors)
            if (a.name().startsWith("spawn.mob.") && a.status() != Walkability.Status.REACHABLE) bad.add(a.name() + " " + a.status());
        assertTrue(bad.isEmpty(), "unreachable spawn anchors: " + bad);
        assertTrue(walk.ok(), "walk report: " + walk.failed());
    }

    @Test
    void groundsAreCompactPacks() {
        Layout l = run.layout;
        for (String pre : DenGenerator.groundPrefixes()) {
            String key = "ground." + pre.substring("spawn.mob.".length(), pre.length() - 1);
            String data = l.data().get(key);
            assertNotNull(data, key);
            String[] p = data.split(",");
            int cx = Integer.parseInt(p[0]), cz = Integer.parseInt(p[1]), r = Integer.parseInt(p[2]);
            List<Layout.Anchor> as = new ArrayList<>();
            for (var e : l.anchors().entrySet()) if (e.getKey().startsWith(pre)) as.add(e.getValue());
            assertTrue(as.size() >= 8, pre + " has " + as.size() + " anchors");
            for (Layout.Anchor a : as)
                assertTrue(Math.hypot(a.x() - cx - 0.5, a.z() - cz - 0.5) <= r + 1, pre + ": " + a.name() + " outside its ground");
        }
    }

    @Test
    void relicsAreAllPlacedAsSkullsOnWalkableGround() {
        String s = run.layout.data().get("relics");
        assertNotNull(s, "layout data relics");
        String[] ps = s.split(";");
        assertEquals(DenGenerator.RELICS, ps.length, "relic count");
        for (String p : ps) {
            String[] q = p.split(",");
            int x = Integer.parseInt(q[0]), y = Integer.parseInt(q[1]), z = Integer.parseInt(q[2]);
            assertTrue(run.canvas.get(x, y, z).startsWith("skeleton_skull"), "no skull at " + p);
            assertFalse(run.canvas.isAir(x, y - 1, z), "relic floats at " + p);
        }
    }

    @Test
    void firstLowGroundIsAShortWalkFromTheEntrance() {
        Layout.Anchor spawn = run.layout.anchor("spawn");
        String[] p = run.layout.data().get("ground.low.a").split(",");
        double d = Math.hypot(Integer.parseInt(p[0]) - spawn.x(), Integer.parseInt(p[1]) - spawn.z());
        assertTrue(d <= 80, "first low ground is " + (int) d + " blocks from spawn");
    }

    @Test
    void groundsAreClearOfThingsSpidersClimb() {
        List<String> bad = new ArrayList<>();
        for (String pre : DenGenerator.groundPrefixes()) {
            String[] p = run.layout.data().get("ground." + pre.substring("spawn.mob.".length(), pre.length() - 1)).split(",");
            int cx = Integer.parseInt(p[0]), cz = Integer.parseInt(p[1]), r = Integer.parseInt(p[2]);
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (dx * dx + dz * dz > r * r) continue;
                    for (int y = 70; y <= 170; y++) {
                        String b = run.canvas.get(cx + dx, y, cz + dz);
                        if (b.contains("_log") || b.contains("_leaves") || b.startsWith("cobweb")) {
                            bad.add(pre + " " + b + " at " + (cx + dx) + "," + y + "," + (cz + dz));
                            break;
                        }
                    }
                }
        }
        assertTrue(bad.size() <= 3, bad.size() + " climbable columns in grounds: " + bad.subList(0, Math.min(8, bad.size())));
    }

    @Test
    void mountainRulesEachOwnOneGround() throws Exception {
        JsonArray rules;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("data/combatzones/spawns.json")) {
            assertNotNull(in, "spawns.json on the classpath");
            rules = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
        }
        List<String> grounds = DenGenerator.groundPrefixes();
        Map<String, Integer> pack = new HashMap<>();
        for (JsonElement el : rules) {
            JsonObject r = el.getAsJsonObject();
            if (!"spiders_den".equals(r.get("island").getAsString())) continue;
            for (JsonElement a : r.getAsJsonArray("anchors")) {
                String an = a.getAsString();
                if (!an.matches("spawn\\.mob\\.(low|mid|high)\\..*")) continue;
                String pre = an.substring(0, an.length() - 1);
                assertTrue(grounds.contains(pre), r.get("id") + " spans more than one ground: " + an);
                pack.merge(pre, r.get("max").getAsInt(), Integer::sum);
                assertTrue(r.get("respawnSeconds").getAsDouble() <= 25, r.get("id") + " respawns too slowly");
            }
        }
        for (String g : grounds) assertTrue(pack.getOrDefault(g, 0) >= 6, g + " pack of " + pack.get(g));
        for (String g : grounds) if (g.startsWith("spawn.mob.low.")) assertTrue(pack.get(g) >= 10, g + " low pack of " + pack.get(g));
    }
}
