package com.skyclone.mod.dungeons.boss;

import com.skyclone.api.ui.Msg;
import com.skyclone.mod.dungeons.Fx;
import com.skyclone.mod.dungeons.ext.BossContext;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Rotation;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The four devices of the Wither Lords' gauntlet (research 04 sec 3.13), one per section, built on a south-facing
 * wall panel whose bottom-left block is the section's {@code device} point:
 * <ul>
 *   <li>S1 Simon Says - a 4x4 wall of buttons; the panel flashes a sequence; repeat it (3, then 4, then 5 long).</li>
 *   <li>S2 Lights - five levers under nine lamps; each lever flips a fixed set of lamps; light them all.</li>
 *   <li>S3 Arrow Align - arrows in frames along a hidden path from the green block to the red one; rotate each to
 *       point at the next.</li>
 *   <li>S4 Shoot the Target - a 3x3 of targets; shoot (or punch from range) the lit one, nine times.</li>
 * </ul>
 * Pure logic (sequences, lamp sets, paths) is in static helpers so the unit tests can prove every board solvable.
 */
public final class Devices {

    private Devices() {}

    public abstract static class Device {
        protected final BossContext c;
        protected final Location o;          // bottom-left block of the panel (in the wall plane)
        protected final Runnable onDone;
        protected final Random rng = new Random();
        public boolean done;

        Device(BossContext c, Location origin, Runnable onDone) { this.c = c; this.o = origin; this.onDone = onDone; }

        abstract void build();

        protected Block wall(int dx, int dy) { return o.getWorld().getBlockAt(o.getBlockX() + dx, o.getBlockY() + dy, o.getBlockZ()); }

        protected Block front(int dx, int dy) { return o.getWorld().getBlockAt(o.getBlockX() + dx, o.getBlockY() + dy, o.getBlockZ() + 1); }

        protected void finish(Player by) {
            if (done) return;
            done = true;
            Fx.particle(front(2, 2).getLocation().add(0.5, 0.5, 0.5), Particle.HAPPY_VILLAGER, 40, 1.5, 0);
            Kit.sound(c, Sound.BLOCK_BEACON_ACTIVATE, 1.4f);
            c.broadcast("&a" + (by == null ? "The device" : by.getName()) + " &acompleted the device!");
            onDone.run();
        }
    }

    public static Device create(int section, BossContext c, Location origin, Runnable onDone) {
        Device d = switch (section) {
            case 1 -> new Simon(c, origin, onDone);
            case 2 -> new Lights(c, origin, onDone);
            case 3 -> new Arrows(c, origin, onDone);
            default -> new Targets(c, origin, onDone);
        };
        d.build();
        return d;
    }

    // ---- Simon Says ------------------------------------------------------------------------------------------
    static final class Simon extends Device {
        final List<Integer> seq = new ArrayList<>();
        int pos;
        boolean showing;

        Simon(BossContext c, Location o, Runnable d) { super(c, o, d); }

        @Override void build() {
            BlockData button = org.bukkit.Bukkit.createBlockData("stone_button[face=wall,facing=south,powered=false]");
            for (int i = 0; i < 16; i++) {
                int dx = i % 4, dy = i / 4;
                wall(dx, dy).setType(Material.OBSIDIAN, false);
                Block b = front(dx, dy);
                b.setBlockData(button, false);
                final int idx = i;
                Hooks.onBlock(c, b, (p, blk, a) -> { press(p, idx); return true; });
            }
            newRound(3);
        }

        void newRound(int len) {
            seq.clear();
            for (int i = 0; i < len; i++) seq.add(rng.nextInt(16));
            pos = 0;
            show();
        }

        void show() {
            showing = true;
            int t = 20;
            for (int i = 0; i < seq.size(); i++) {
                int idx = seq.get(i);
                Kit.later(c, t, () -> { wall(idx % 4, idx / 4).setType(Material.SEA_LANTERN, false); Kit.sound(c, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f + idx * 0.05f); });
                Kit.later(c, t + 10, () -> wall(idx % 4, idx / 4).setType(Material.OBSIDIAN, false));
                t += 14;
            }
            Kit.later(c, t, () -> showing = false);
        }

        void press(Player p, int idx) {
            if (done || showing) return;
            if (seq.get(pos) != idx) {
                Msg.error(p, "Wrong button! The sequence starts again.");
                newRound(3);
                return;
            }
            Msg.sound(p, Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1.2f);
            pos++;
            if (pos >= seq.size()) {
                if (seq.size() >= 5) { finish(p); return; }
                c.broadcast("&eSimon Says: round " + (seq.size() - 1) + " of 3 done.");
                int next = seq.size() + 1;
                seq.add(rng.nextInt(16));
                while (seq.size() < next) seq.add(rng.nextInt(16));
                pos = 0;
                show();
            }
        }
    }

    // ---- Lights ----------------------------------------------------------------------------------------------
    /** Lamp sets per lever (indices 0..8) for a 5-lever, 9-lamp board; deterministic for a seed. */
    public static int[] lampSets(Random r) {
        int[] sets = new int[5];
        for (int i = 0; i < 5; i++) {
            int m = 0;
            while (Integer.bitCount(m) < 3) m |= 1 << r.nextInt(9);
            sets[i] = m;
        }
        return sets;
    }

    /** A start state (bitmask of LIT lamps) that some lever combination turns fully on, and is not already solved. */
    public static int startState(int[] sets, Random r) {
        for (int tries = 0; tries < 64; tries++) {
            int state = 0x1FF;
            int combo = 1 + r.nextInt(31);
            for (int i = 0; i < 5; i++) if ((combo >> i & 1) == 1) state ^= sets[i];
            if (state != 0x1FF) return state;
        }
        return 0x1FF ^ sets[0];
    }

    static final class Lights extends Device {
        int[] sets;
        int state;
        final boolean[] lever = new boolean[5];

        Lights(BossContext c, Location o, Runnable d) { super(c, o, d); }

        @Override void build() {
            sets = lampSets(rng);
            state = startState(sets, rng);
            for (int i = 0; i < 5; i++) {
                Block b = front(i, 0);
                b.setBlockData(org.bukkit.Bukkit.createBlockData("lever[face=wall,facing=south,powered=false]"), false);
                final int idx = i;
                Hooks.onBlock(c, b, (p, blk, a) -> { flip(p, idx, blk); return true; });
            }
            draw();
        }

        void draw() {
            for (int i = 0; i < 9; i++) {
                Block b = wall(1 + i % 3, 2 + i / 3);
                BlockData d = Material.REDSTONE_LAMP.createBlockData();
                if (d instanceof Lightable l) l.setLit((state >> i & 1) == 1);
                b.setBlockData(d, false);
            }
        }

        void flip(Player p, int idx, Block b) {
            if (done) return;
            lever[idx] = !lever[idx];
            BlockData d = b.getBlockData();
            if (d instanceof Powerable pw) { pw.setPowered(lever[idx]); b.setBlockData(d, false); }
            state ^= sets[idx];
            Msg.sound(p, Sound.BLOCK_LEVER_CLICK, lever[idx] ? 0.6f : 0.5f);
            draw();
            if (state == 0x1FF) finish(p);
        }
    }

    // ---- Arrow Align -------------------------------------------------------------------------------------------
    /** Moves: 0 right, 1 up, 2 down. A path from column 0 to column 4 of a 5x5 grid: list of {col, row, move}. */
    public static List<int[]> path(Random r) {
        for (int attempt = 0; attempt < 200; attempt++) {
            List<int[]> out = new ArrayList<>();
            boolean[][] seen = new boolean[5][5];
            int col = 0, row = r.nextInt(5);
            boolean ok = true;
            while (true) {
                seen[col][row] = true;
                if (col == 4 && r.nextInt(3) == 0 || out.size() > 14 && col == 4) { out.add(new int[]{col, row, 0}); break; }
                List<Integer> moves = new ArrayList<>();
                if (col < 4 && !seen[col + 1][row]) { moves.add(0); moves.add(0); }
                if (row < 4 && !seen[col][row + 1]) moves.add(1);
                if (row > 0 && !seen[col][row - 1]) moves.add(2);
                if (moves.isEmpty()) { if (col == 4) { out.add(new int[]{col, row, 0}); break; } ok = false; break; }
                int m = moves.get(r.nextInt(moves.size()));
                out.add(new int[]{col, row, m});
                if (m == 0) col++; else if (m == 1) row++; else row--;
            }
            if (ok && out.size() >= 6) return out;
        }
        List<int[]> straight = new ArrayList<>();
        for (int i = 0; i < 5; i++) straight.add(new int[]{i, 2, 0});
        return straight;
    }

    static Rotation rotationFor(int move) {
        return switch (move) { case 1 -> Rotation.COUNTER_CLOCKWISE_45; case 2 -> Rotation.CLOCKWISE_135; default -> Rotation.CLOCKWISE_45; };
    }

    static final class Arrows extends Device {
        final Map<ItemFrame, Rotation> want = new HashMap<>();

        Arrows(BossContext c, Location o, Runnable d) { super(c, o, d); }

        @Override void build() {
            for (int dx = 0; dx < 5; dx++) for (int dy = 0; dy < 5; dy++) wall(dx, dy).setType(Material.SMOOTH_STONE, false);
            List<int[]> p = path(rng);
            World w = o.getWorld();
            int[] first = p.get(0), last = p.get(p.size() - 1);
            wall(-1, first[1]).setType(Material.LIME_WOOL, false);
            wall(5, last[1]).setType(Material.RED_WOOL, false);
            Rotation[] all = Rotation.values();
            for (int[] cell : p) {
                Block b = front(cell[0], cell[1]);
                Location at = b.getLocation();
                ItemFrame f = w.spawn(at, ItemFrame.class, it -> {
                    it.setFacingDirection(BlockFace.SOUTH, true);
                    it.setItem(new ItemStack(Material.ARROW), false);
                    it.setFixed(true);
                    it.setInvulnerable(true);
                    it.setPersistent(false);
                });
                Rotation target = rotationFor(cell[2]);
                Rotation start = all[rng.nextInt(all.length)];
                if (start == target) start = start.rotateClockwise();
                f.setRotation(start);
                want.put(f, target);
                c.addProp(f);
                Hooks.onEntity(c, f, (pl, e) -> turn(pl, f));
            }
            c.broadcast("&7Arrow Align: turn every arrow so the path runs from the &agreen &7block to the &cred &7one.");
        }

        void turn(Player p, ItemFrame f) {
            if (done) return;
            f.setRotation(f.getRotation().rotateClockwise());
            Msg.sound(p, Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 1f);
            for (Map.Entry<ItemFrame, Rotation> en : want.entrySet()) if (en.getKey().getRotation() != en.getValue()) return;
            finish(p);
        }
    }

    // ---- Shoot the Target -----------------------------------------------------------------------------------------
    static final class Targets extends Device {
        int active = -1, hits;

        Targets(BossContext c, Location o, Runnable d) { super(c, o, d); }

        @Override void build() {
            for (int i = 0; i < 9; i++) {
                Block b = wall(1 + i % 3, 2 + i / 3);
                b.setType(Material.BLUE_TERRACOTTA, false);
                final int idx = i;
                Hooks.onBlock(c, b, (p, blk, a) -> { shot(p, idx); return true; });
            }
            light();
        }

        void light() {
            int prev = active;
            do active = rng.nextInt(9); while (active == prev);
            for (int i = 0; i < 9; i++) wall(1 + i % 3, 2 + i / 3).setType(i == active ? Material.EMERALD_BLOCK : Material.BLUE_TERRACOTTA, false);
        }

        void shot(Player p, int idx) {
            if (done || idx != active) return;
            hits++;
            Msg.sound(p, Sound.ENTITY_ARROW_HIT_PLAYER, 1.2f);
            if (hits >= 9) {
                for (int i = 0; i < 9; i++) wall(1 + i % 3, 2 + i / 3).setType(Material.EMERALD_BLOCK, false);
                finish(p);
                return;
            }
            c.broadcast("&7Target &a" + hits + "&7/9");
            light();
        }
    }
}
