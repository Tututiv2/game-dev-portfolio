package com.skyclone.mod.dungeons.gen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The abstract layout of ONE run: a W x H grid of 32-block cells, the rooms that cover it, the doors between rooms,
 * which doors are locked and where their keys are. Pure data; {@link PlanGenerator} makes it, {@link RunBuilder}
 * turns it into blocks, the run reads it at runtime (rooms, map, keys).
 */
public final class DungeonPlan {

    /** Room types (research 04 sec 3.7). {@code leaf} rooms keep exactly one door. */
    public enum RoomType {
        START("Entrance", 0x3BA33B, false),
        NORMAL("Room", 0x6B4A2A, false),
        PUZZLE("Puzzle", 0x8E3BC2, true),
        TRAP("Trap", 0xE0761C, true),
        MINIBOSS("Miniboss", 0xD8C632, true),
        FAIRY("Fairy", 0xF07BC9, true),
        BLOOD("Blood", 0xB3202A, true);

        public final String label;
        public final int rgb;
        public final boolean leaf;

        RoomType(String label, int rgb, boolean leaf) { this.label = label; this.rgb = rgb; this.leaf = leaf; }
    }

    public static final class Room {
        public final int index;
        public final RoomType type;
        /** Cells as {gx, gz}. */
        public final List<int[]> cells = new ArrayList<>();
        /** "1x1", "1x2", "1x3", "1x4", "2x2", "L". */
        public String shape = "1x1";
        /** Tile template id (decided by RunBuilder). */
        public String tile;
        /** Puzzle id for PUZZLE rooms. */
        public String puzzle;
        /** Display name (from the tile). */
        public String name;
        /** Number of wither doors between START and this room on the tree. */
        public int region;
        /** Index of the wither door whose key this room holds, -1 if none. */
        public int witherKeyFor = -1;
        public boolean bloodKey;

        public Room(int index, RoomType type) { this.index = index; this.type = type; }

        public boolean has(int gx, int gz) {
            for (int[] c : cells) if (c[0] == gx && c[1] == gz) return true;
            return false;
        }

        public boolean holdsKey() { return witherKeyFor >= 0 || bloodKey; }

        public int[] firstCell() { return cells.get(0); }
    }

    public enum DoorKind { NORMAL, WITHER, BLOOD }

    /**
     * A door on the edge between cell (gx, gz) and its neighbour: {@code dir} 'E' = (gx+1, gz), 'S' = (gx, gz+1).
     * {@code a} is the room on the (gx, gz) side.
     */
    public static final class Door {
        public final int a, b;
        public final int gx, gz;
        public final char dir;
        public DoorKind kind = DoorKind.NORMAL;
        /** Order of a wither door along the START->BLOOD path (0 first), -1 otherwise. */
        public int lockIndex = -1;
        public boolean tree;

        public Door(int a, int b, int gx, int gz, char dir) { this.a = a; this.b = b; this.gx = gx; this.gz = gz; this.dir = dir; }

        public int other(int room) { return room == a ? b : a; }

        public boolean locked() { return kind != DoorKind.NORMAL; }

        /** The cell on the far side of the edge. */
        public int[] cell2() { return dir == 'E' ? new int[]{gx + 1, gz} : new int[]{gx, gz + 1}; }
    }

    public final int w, h;
    public final long seed;
    public final String floorId;
    public final List<Room> rooms = new ArrayList<>();
    public final List<Door> doors = new ArrayList<>();
    /** cellRoom[gx][gz] = room index, -1 = empty. */
    public final int[][] cellRoom;
    public int startRoom = -1, bloodRoom = -1;
    public int witherDoors;
    /** Retries the generator needed (diagnostics). */
    public int attempts;

    public DungeonPlan(int w, int h, long seed, String floorId) {
        this.w = w;
        this.h = h;
        this.seed = seed;
        this.floorId = floorId;
        this.cellRoom = new int[w][h];
        for (int[] col : cellRoom) java.util.Arrays.fill(col, -1);
    }

    public Room room(int i) { return i < 0 || i >= rooms.size() ? null : rooms.get(i); }

    public int roomAt(int gx, int gz) {
        if (gx < 0 || gz < 0 || gx >= w || gz >= h) return -1;
        return cellRoom[gx][gz];
    }

    public List<Door> doorsOf(int room) {
        List<Door> out = new ArrayList<>();
        for (Door d : doors) if (d.a == room || d.b == room) out.add(d);
        return out;
    }

    public int count(RoomType t) {
        int n = 0;
        for (Room r : rooms) if (r.type == t) n++;
        return n;
    }

    /**
     * Simulates a party walking from START: a locked door opens once its key's room has been reached. True when every
     * room is reachable this way (so BLOOD is too). Used by the generator's validation and the tests.
     */
    public boolean solvable() {
        if (startRoom < 0) return false;
        Set<Integer> reached = new HashSet<>();
        int keysW = 0;
        boolean blood = false;
        Set<Door> opened = new HashSet<>();
        boolean progress = true;
        reached.add(startRoom);
        while (progress) {
            progress = false;
            ArrayDeque<Integer> q = new ArrayDeque<>(reached);
            while (!q.isEmpty()) {
                int r = q.poll();
                for (Door d : doorsOf(r)) {
                    int o = d.other(r);
                    if (reached.contains(o)) continue;
                    boolean pass = d.kind == DoorKind.NORMAL || opened.contains(d);
                    if (!pass) continue;
                    reached.add(o);
                    q.add(o);
                    progress = true;
                }
            }
            int haveW = 0;
            boolean haveB = false;
            for (int r : reached) {
                Room rm = rooms.get(r);
                if (rm.witherKeyFor >= 0) haveW++;
                if (rm.bloodKey) haveB = true;
            }
            int usedW = 0;
            for (Door d : opened) if (d.kind == DoorKind.WITHER) usedW++;
            for (Door d : doors) {
                if (opened.contains(d) || d.kind == DoorKind.NORMAL) continue;
                boolean touches = reached.contains(d.a) || reached.contains(d.b);
                if (!touches) continue;
                if (d.kind == DoorKind.WITHER && haveW - usedW > 0) { opened.add(d); usedW++; progress = true; }
                else if (d.kind == DoorKind.BLOOD && haveB) { opened.add(d); progress = true; }
            }
            keysW = haveW;
            blood = haveB;
        }
        return reached.size() == rooms.size() && (keysW >= 0) && (blood || bloodRoom < 0 || reached.contains(bloodRoom));
    }

    /** ASCII picture for admin output and tests: one char per cell (S start, B blood, F fairy, P puzzle, T trap, M miniboss, . normal). */
    public List<String> ascii() {
        List<String> out = new ArrayList<>();
        for (int z = 0; z < h; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < w; x++) {
                int r = cellRoom[x][z];
                char c = '#';
                if (r >= 0) {
                    Room rm = rooms.get(r);
                    c = switch (rm.type) {
                        case START -> 'S';
                        case BLOOD -> 'B';
                        case FAIRY -> 'F';
                        case PUZZLE -> 'P';
                        case TRAP -> 'T';
                        case MINIBOSS -> 'M';
                        default -> rm.holdsKey() ? 'k' : (char) ('a' + (r % 26));
                    };
                }
                row.append(c);
                Door e = edge(x, z, 'E');
                row.append(e == null ? ' ' : e.kind == DoorKind.WITHER ? 'W' : e.kind == DoorKind.BLOOD ? 'R' : '-');
            }
            out.add(row.toString());
            StringBuilder under = new StringBuilder();
            for (int x = 0; x < w; x++) {
                Door s = edge(x, z, 'S');
                under.append(s == null ? ' ' : s.kind == DoorKind.WITHER ? 'W' : s.kind == DoorKind.BLOOD ? 'R' : '|').append(' ');
            }
            out.add(under.toString());
        }
        return out;
    }

    public Door edge(int gx, int gz, char dir) {
        for (Door d : doors) if (d.gx == gx && d.gz == gz && d.dir == dir) return d;
        return null;
    }
}
