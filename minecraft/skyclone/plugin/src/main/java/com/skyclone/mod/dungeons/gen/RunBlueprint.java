package com.skyclone.mod.dungeons.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.gen.MemoryCanvas;
import com.skyclone.mod.dungeons.model.FloorDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one run needs, generated off-thread before a single block is placed: the blocks (a MemoryCanvas in
 * template coordinates) and, per room, where mobs stand, where the secrets, crypts and weak walls are, and the named
 * points puzzles and bosses use. All coordinates are template-relative ints; the run adds the instance origin.
 */
public final class RunBlueprint {

    /** Secret kinds (research 04 sec 3.8). */
    public enum SecretKind { CHEST, ITEM, BAT, ESSENCE, LEVER }

    public record Secret(SecretKind kind, int x, int y, int z) {}

    public static final class RoomInfo {
        public final int index;
        public String name;
        public String tile;
        public final List<Box> interiors = new ArrayList<>();
        /** Standing point at the room's middle (x, y, z). */
        public int[] center;
        public final List<int[]> mobSpots = new ArrayList<>();
        public final List<Secret> secrets = new ArrayList<>();
        /** Crypt lids: blown open by explosives (+1 score each). */
        public final List<Box> crypts = new ArrayList<>();
        /** Cracked walls that explosives break. */
        public final List<Box> weakWalls = new ArrayList<>();
        public final Map<String, int[]> points = new LinkedHashMap<>();
        public final Map<String, Box> boxes = new LinkedHashMap<>();
        public final Map<String, String> data = new LinkedHashMap<>();

        public RoomInfo(int index) { this.index = index; }

        public int[] point(String name) { return points.get(name); }
    }

    public static final class DoorInfo {
        public final int index;
        public final DungeonPlan.DoorKind kind;
        public final Box opening;
        public final int[] center;

        public DoorInfo(int index, DungeonPlan.DoorKind kind, Box opening, int[] center) {
            this.index = index; this.kind = kind; this.opening = opening; this.center = center;
        }
    }

    public final DungeonPlan plan;
    public final FloorDef floor;
    public final MemoryCanvas canvas;
    public final RoomInfo[] rooms;
    public final List<DoorInfo> doors = new ArrayList<>();
    public final Theme theme;
    /** The boss arena footprint and its named points (empty for the Entrance). */
    public Box arena;
    public final Map<String, int[]> arenaPoints = new LinkedHashMap<>();
    public String arenaId;
    public long buildMillis;

    public RunBlueprint(DungeonPlan plan, FloorDef floor, Theme theme) {
        this.plan = plan;
        this.floor = floor;
        this.theme = theme;
        this.canvas = new MemoryCanvas();
        this.rooms = new RoomInfo[plan.rooms.size()];
        for (int i = 0; i < rooms.length; i++) rooms[i] = new RoomInfo(i);
    }

    public int totalSecrets() {
        int n = 0;
        for (RoomInfo r : rooms) n += r.secrets.size();
        return n;
    }

    public int totalCrypts() {
        int n = 0;
        for (RoomInfo r : rooms) n += r.crypts.size();
        return n;
    }

    public RoomInfo start() { return rooms[plan.startRoom]; }

    public RoomInfo blood() { return rooms[plan.bloodRoom]; }
}
