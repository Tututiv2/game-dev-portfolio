package com.skyclone.mod.dungeons.gen;

import com.skyclone.mod.dungeons.gen.DungeonPlan.Door;
import com.skyclone.mod.dungeons.gen.DungeonPlan.DoorKind;
import com.skyclone.mod.dungeons.gen.DungeonPlan.Room;
import com.skyclone.mod.dungeons.gen.DungeonPlan.RoomType;
import com.skyclone.mod.dungeons.model.FloorDef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The per-run map generator (research 04 sec 3.6, the proposed clone algorithm):
 * <ol>
 *   <li>START on a random edge cell, BLOOD on the edge cell furthest from it, FAIRY at least 2 cells from both;</li>
 *   <li>puzzle, trap and miniboss rooms on single cells;</li>
 *   <li>normal rooms fill the rest in 1x1/1x2/1x3/1x4/2x2/L shapes by weight;</li>
 *   <li>a random spanning tree from START (special rooms stay leaves), plus ~20% loop doors;</li>
 *   <li>wither doors on the START->BLOOD path, the blood door into BLOOD, loops only inside a lock region;</li>
 *   <li>each key in a normal room reachable before its door; validated by a key-simulating BFS, reseeded if needed.</li>
 * </ol>
 * Pure and deterministic for (floor, seed).
 */
public final class PlanGenerator {

    private PlanGenerator() {}

    /** Shape weights {1x1:30, 1x2:25, 1x3:12, 1x4:6, 2x2:12, L:15}. */
    private static final String[] SHAPES = {"1x1", "1x2", "1x3", "1x4", "2x2", "L"};
    private static final int[] WEIGHTS = {30, 25, 12, 6, 12, 15};

    public static DungeonPlan generate(FloorDef f, long seed) {
        Random seeds = new Random(seed);
        for (int attempt = 0; attempt < 400; attempt++) {
            long s = attempt == 0 ? seed : seeds.nextLong();
            DungeonPlan p = tryGenerate(f, s, seed);
            if (p != null) {
                p.attempts = attempt + 1;
                return p;
            }
        }
        throw new IllegalStateException("no valid dungeon plan for " + f.id + " seed " + seed);
    }

    private static DungeonPlan tryGenerate(FloorDef f, long s, long publicSeed) {
        Random r = new Random(s);
        int w = Math.max(2, f.gridW), h = Math.max(2, f.gridH);
        DungeonPlan p = new DungeonPlan(w, h, publicSeed, f.id);

        // 1. START
        List<int[]> edges = new ArrayList<>();
        for (int x = 0; x < w; x++) for (int z = 0; z < h; z++) if (x == 0 || z == 0 || x == w - 1 || z == h - 1) edges.add(new int[]{x, z});
        int[] start = edges.get(r.nextInt(edges.size()));
        p.startRoom = place(p, RoomType.START, List.of(start), "1x1");

        // 2. BLOOD: edge cell maximising grid distance from START
        int best = -1;
        List<int[]> far = new ArrayList<>();
        for (int[] c : edges) {
            int d = Math.abs(c[0] - start[0]) + Math.abs(c[1] - start[1]);
            if (d > best) { best = d; far.clear(); }
            if (d == best) far.add(c);
        }
        int[] blood = far.get(r.nextInt(far.size()));
        p.bloodRoom = place(p, RoomType.BLOOD, List.of(blood), "1x1");

        // 3. FAIRY
        List<int[]> fairyCells = new ArrayList<>();
        for (int x = 0; x < w; x++)
            for (int z = 0; z < h; z++) {
                if (p.cellRoom[x][z] >= 0) continue;
                if (dist(x, z, start) >= 2 && dist(x, z, blood) >= 2) fairyCells.add(new int[]{x, z});
            }
        if (!fairyCells.isEmpty()) place(p, RoomType.FAIRY, List.of(fairyCells.get(r.nextInt(fairyCells.size()))), "1x1");

        // 4. puzzles, trap, minibosses
        List<String> pool = new ArrayList<>(f.puzzlePool);
        Collections.shuffle(pool, r);
        int puzzles = Math.min(pool.size(), f.puzzlesMin + r.nextInt(Math.max(1, f.puzzlesMax - f.puzzlesMin + 1)));
        for (int i = 0; i < puzzles; i++) {
            int[] c = randomEmpty(p, r, start);
            if (c == null) break;
            int idx = place(p, RoomType.PUZZLE, List.of(c), "1x1");
            p.rooms.get(idx).puzzle = pool.get(i);
        }
        if (f.trapRooms > 0 && r.nextDouble() < f.trapChance) {
            int[] c = randomEmpty(p, r, start);
            if (c != null) place(p, RoomType.TRAP, List.of(c), "1x1");
        }
        for (int i = 0; i < f.minibossRooms; i++) {
            int[] c = randomEmpty(p, r, start);
            if (c != null) place(p, RoomType.MINIBOSS, List.of(c), "1x1");
        }

        // 5. normal rooms
        List<int[]> empty = new ArrayList<>();
        for (int x = 0; x < w; x++) for (int z = 0; z < h; z++) if (p.cellRoom[x][z] < 0) empty.add(new int[]{x, z});
        Collections.shuffle(empty, r);
        for (int[] c : empty) {
            if (p.cellRoom[c[0]][c[1]] >= 0) continue;
            List<int[]> cells = null;
            String shape = "1x1";
            for (String sh : shapeOrder(r)) {
                List<List<int[]>> fits = placements(p, sh, c[0], c[1]);
                if (!fits.isEmpty()) { cells = fits.get(r.nextInt(fits.size())); shape = sh; break; }
            }
            if (cells == null) cells = List.of(c);
            place(p, RoomType.NORMAL, cells, shape);
        }

        // 6. adjacency (one candidate door per room pair, chosen at random among the shared edges)
        Map<Long, List<int[]>> shared = new HashMap<>();
        for (int x = 0; x < w; x++)
            for (int z = 0; z < h; z++) {
                int a = p.cellRoom[x][z];
                if (x + 1 < w) addShared(shared, a, p.cellRoom[x + 1][z], x, z, 'E');
                if (z + 1 < h) addShared(shared, a, p.cellRoom[x][z + 1], x, z, 'S');
            }
        Map<Long, Door> candidates = new HashMap<>();
        for (Map.Entry<Long, List<int[]>> e : shared.entrySet()) {
            int[] edge = e.getValue().get(r.nextInt(e.getValue().size()));
            int a = p.cellRoom[edge[0]][edge[1]];
            int[] c2 = edge[2] == 'E' ? new int[]{edge[0] + 1, edge[1]} : new int[]{edge[0], edge[1] + 1};
            int b = p.cellRoom[c2[0]][c2[1]];
            candidates.put(e.getKey(), new Door(a, b, edge[0], edge[1], (char) edge[2]));
        }

        // 7. spanning tree from START (randomised Prim; leaf rooms never expand)
        int n = p.rooms.size();
        int[] parent = new int[n];
        Door[] parentDoor = new Door[n];
        java.util.Arrays.fill(parent, -2);
        parent[p.startRoom] = -1;
        List<Door> frontier = new ArrayList<>();
        addFrontier(frontier, candidates.values(), p.startRoom, parent);
        int visited = 1;
        while (!frontier.isEmpty()) {
            Door d = frontier.remove(r.nextInt(frontier.size()));
            int in = parent[d.a] != -2 ? d.a : d.b;
            int out = d.other(in);
            if (parent[out] != -2) continue;
            parent[out] = in;
            parentDoor[out] = d;
            d.tree = true;
            p.doors.add(d);
            visited++;
            if (!p.rooms.get(out).type.leaf) addFrontier(frontier, candidates.values(), out, parent);
        }
        if (visited < n) return null;
        // every leaf room has exactly one door by construction; BLOOD must not be adjacent only through leaves (ensured above)

        // 8. the START -> BLOOD path, wither doors, blood door
        List<Door> path = new ArrayList<>();
        for (int cur = p.bloodRoom; parent[cur] >= 0; cur = parent[cur]) path.add(0, parentDoor[cur]);
        if (path.isEmpty()) return null;
        Door bloodDoor = path.get(path.size() - 1);
        bloodDoor.kind = DoorKind.BLOOD;
        List<Door> lockable = new ArrayList<>(path.subList(0, path.size() - 1));
        int want = f.witherDoorsMin + r.nextInt(Math.max(1, f.witherDoorsMax - f.witherDoorsMin + 1));
        want = Math.min(want, lockable.size());
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < lockable.size(); i++) idx.add(i);
        Collections.shuffle(idx, r);
        List<Integer> chosen = new ArrayList<>(idx.subList(0, want));
        Collections.sort(chosen);
        for (int k = 0; k < chosen.size(); k++) {
            Door d = lockable.get(chosen.get(k));
            d.kind = DoorKind.WITHER;
            d.lockIndex = k;
        }
        p.witherDoors = chosen.size();

        // regions: number of wither doors on the tree path from START
        int[] region = new int[n];
        computeRegions(p, parent, parentDoor, region);
        for (int i = 0; i < n; i++) p.rooms.get(i).region = region[i];

        // 9. loops: extra doors between non-leaf rooms of the same region
        List<Door> spare = new ArrayList<>();
        for (Door d : candidates.values()) {
            if (d.tree) continue;
            Room a = p.rooms.get(d.a), b = p.rooms.get(d.b);
            if (a.type.leaf || b.type.leaf) continue;
            if (region[d.a] != region[d.b]) continue;
            spare.add(d);
        }
        Collections.shuffle(spare, r);
        int loops = (int) Math.round(spare.size() * (0.15 + r.nextDouble() * 0.10));
        for (int i = 0; i < loops && i < spare.size(); i++) p.doors.add(spare.get(i));

        // 10. keys
        for (int k = 0; k < p.witherDoors; k++) {
            Room keyRoom = keyRoom(p, r, k);
            if (keyRoom == null) return null;
            keyRoom.witherKeyFor = k;
        }
        Room bk = keyRoom(p, r, p.witherDoors);
        if (bk == null) return null;
        bk.bloodKey = true;

        // 11. validate
        if (!p.solvable()) return null;
        return p;
    }

    private static Room keyRoom(DungeonPlan p, Random r, int region) {
        List<Room> normal = new ArrayList<>(), any = new ArrayList<>();
        for (Room rm : p.rooms) {
            if (rm.region != region || rm.holdsKey() || rm.type.leaf) continue;
            any.add(rm);
            if (rm.type == RoomType.NORMAL) normal.add(rm);
        }
        List<Room> from = !normal.isEmpty() ? normal : any;
        return from.isEmpty() ? null : from.get(r.nextInt(from.size()));
    }

    private static void computeRegions(DungeonPlan p, int[] parent, Door[] parentDoor, int[] region) {
        int n = p.rooms.size();
        for (int i = 0; i < n; i++) {
            int locks = 0;
            for (int cur = i; parent[cur] >= 0; cur = parent[cur]) if (parentDoor[cur].kind == DoorKind.WITHER) locks++;
            region[i] = locks;
        }
        // the blood room sits behind the blood door: one region past the last wither door
        region[p.bloodRoom] = p.witherDoors + 1;
    }

    private static void addFrontier(List<Door> frontier, Iterable<Door> all, int room, int[] parent) {
        for (Door d : all) {
            if (d.a != room && d.b != room) continue;
            int o = d.other(room);
            if (parent[o] == -2) frontier.add(d);
        }
    }

    private static void addShared(Map<Long, List<int[]>> shared, int a, int b, int x, int z, char dir) {
        if (a < 0 || b < 0 || a == b) return;
        long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
        shared.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{x, z, dir});
    }

    private static int dist(int x, int z, int[] c) { return Math.abs(x - c[0]) + Math.abs(z - c[1]); }

    private static int[] randomEmpty(DungeonPlan p, Random r, int[] avoid) {
        List<int[]> free = new ArrayList<>();
        for (int x = 0; x < p.w; x++)
            for (int z = 0; z < p.h; z++)
                if (p.cellRoom[x][z] < 0 && dist(x, z, avoid) >= 1) free.add(new int[]{x, z});
        return free.isEmpty() ? null : free.get(r.nextInt(free.size()));
    }

    private static int place(DungeonPlan p, RoomType t, List<int[]> cells, String shape) {
        Room rm = new Room(p.rooms.size(), t);
        rm.shape = shape;
        for (int[] c : cells) {
            rm.cells.add(new int[]{c[0], c[1]});
            p.cellRoom[c[0]][c[1]] = rm.index;
        }
        p.rooms.add(rm);
        return rm.index;
    }

    private static List<String> shapeOrder(Random r) {
        List<String> out = new ArrayList<>();
        List<Integer> left = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5));
        while (!left.isEmpty()) {
            int total = 0;
            for (int i : left) total += WEIGHTS[i];
            int roll = r.nextInt(total);
            for (int k = 0; k < left.size(); k++) {
                roll -= WEIGHTS[left.get(k)];
                if (roll < 0) { out.add(SHAPES[left.remove(k)]); break; }
            }
        }
        return out;
    }

    /** Every placement of a shape (all rotations/offsets) that covers (x, z) and only empty cells. */
    static List<List<int[]>> placements(DungeonPlan p, String shape, int x, int z) {
        List<int[][]> forms = new ArrayList<>();
        switch (shape) {
            case "1x1" -> forms.add(new int[][]{{0, 0}});
            case "1x2" -> { forms.add(new int[][]{{0, 0}, {1, 0}}); forms.add(new int[][]{{0, 0}, {0, 1}}); }
            case "1x3" -> { forms.add(new int[][]{{0, 0}, {1, 0}, {2, 0}}); forms.add(new int[][]{{0, 0}, {0, 1}, {0, 2}}); }
            case "1x4" -> { forms.add(new int[][]{{0, 0}, {1, 0}, {2, 0}, {3, 0}}); forms.add(new int[][]{{0, 0}, {0, 1}, {0, 2}, {0, 3}}); }
            case "2x2" -> forms.add(new int[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}});
            case "L" -> {
                forms.add(new int[][]{{0, 0}, {1, 0}, {0, 1}});
                forms.add(new int[][]{{0, 0}, {1, 0}, {1, 1}});
                forms.add(new int[][]{{0, 0}, {0, 1}, {1, 1}});
                forms.add(new int[][]{{1, 0}, {0, 1}, {1, 1}});
            }
            default -> { }
        }
        List<List<int[]>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int[][] f : forms) {
            for (int[] anchor : f) {
                int ox = x - anchor[0], oz = z - anchor[1];
                List<int[]> cells = new ArrayList<>();
                boolean ok = true;
                for (int[] c : f) {
                    int cx = ox + c[0], cz = oz + c[1];
                    if (cx < 0 || cz < 0 || cx >= p.w || cz >= p.h || p.cellRoom[cx][cz] >= 0) { ok = false; break; }
                    cells.add(new int[]{cx, cz});
                }
                if (!ok) continue;
                StringBuilder key = new StringBuilder();
                cells.stream().sorted((a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]).forEach(c -> key.append(c[0]).append(',').append(c[1]).append(';'));
                if (seen.add(key.toString())) out.add(cells);
            }
        }
        return out;
    }

    /** BFS distance in doors from START to every room (map widgets, tests). */
    public static int[] doorDistance(DungeonPlan p) {
        int[] d = new int[p.rooms.size()];
        java.util.Arrays.fill(d, -1);
        ArrayDeque<Integer> q = new ArrayDeque<>();
        d[p.startRoom] = 0;
        q.add(p.startRoom);
        while (!q.isEmpty()) {
            int cur = q.poll();
            for (Door door : p.doorsOf(cur)) {
                int o = door.other(cur);
                if (d[o] < 0) { d[o] = d[cur] + 1; q.add(o); }
            }
        }
        return d;
    }
}
