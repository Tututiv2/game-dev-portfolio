package org.aincrad.plugin.editor;

import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A recorded set of "this position held this block", used for both undo and redo.
 *
 * <p><b>Why it is packed.</b> A naive undo buffer stores a Location and a BlockData per block. On a
 * million-block {@code //set} that is a million objects plus a million more, which is tens of
 * megabytes of garbage per operation and enough to matter after a few of them. Here each entry costs
 * a {@code long} position and a {@code short} palette index - ten bytes - and distinct block states
 * are stored once each in a palette, exactly like Minecraft's own chunk format. A million-block undo
 * costs about 10 MB with a palette of maybe five entries.</p>
 *
 * <p>Position packing uses 26 bits of X, 26 of Z and 12 of Y, the same layout vanilla uses for
 * BlockPos. Y is biased by 64 so the range -64..4031 fits unsigned, which covers this project's
 * 2,032-block dimension and every vanilla world.</p>
 */
public final class BlockSnapshot {

    private static final int Y_BIAS = 64;

    private long[] positions;
    private short[] indices;
    private int size;

    private final List<BlockData> palette = new ArrayList<>();
    private final Map<BlockData, Short> paletteIndex = new HashMap<>();

    public BlockSnapshot() {
        this(1024);
    }

    public BlockSnapshot(int initialCapacity) {
        int cap = Math.max(16, initialCapacity);
        positions = new long[cap];
        indices = new short[cap];
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (z & 0x3FFFFFF) << 12)
                | ((y + Y_BIAS) & 0xFFF);
    }

    // X sits in bits 38..63, so its sign bit is already bit 63 and a plain arithmetic shift right
    // sign-extends it. Z sits in bits 12..37, so it must first be shifted up by 26 to put its sign
    // bit at 63 before shifting back down. Getting either shift wrong does not throw - it silently
    // writes undo data to the wrong coordinates - so both are covered by selfTest().
    public static int unpackX(long p) {
        return (int) (p >> 38);
    }

    public static int unpackZ(long p) {
        return (int) (p << 26 >> 38);
    }

    public static int unpackY(long p) {
        return (int) (p & 0xFFF) - Y_BIAS;
    }

    public void add(int x, int y, int z, BlockData data) {
        if (size == positions.length) {
            grow();
        }
        Short idx = paletteIndex.get(data);
        if (idx == null) {
            if (palette.size() >= Short.MAX_VALUE) {
                // 32,767 distinct block states in one operation is not a real scenario; refusing is
                // better than silently mis-recording and corrupting an undo.
                throw new IllegalStateException("too many distinct block states to record for undo");
            }
            idx = (short) palette.size();
            palette.add(data);
            paletteIndex.put(data, idx);
        }
        positions[size] = pack(x, y, z);
        indices[size] = idx;
        size++;
    }

    private void grow() {
        int next = positions.length + (positions.length >> 1);
        long[] p = new long[next];
        short[] i = new short[next];
        System.arraycopy(positions, 0, p, 0, size);
        System.arraycopy(indices, 0, i, 0, size);
        positions = p;
        indices = i;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public long positionAt(int i) {
        return positions[i];
    }

    public BlockData dataAt(int i) {
        return palette.get(indices[i] & 0xFFFF);
    }

    public int paletteSize() {
        return palette.size();
    }

    /** Rough heap cost, for the history's memory cap. */
    public long approximateBytes() {
        return (long) size * 10 + (long) palette.size() * 64;
    }

    /**
     * Verifies the position packing round-trips, including negative coordinates and the Y extremes of
     * this project's tall dimension. Run once at plugin enable: a shift bug here corrupts undo data
     * silently rather than throwing, so it must be caught by assertion rather than by a player
     * discovering their build was restored into the wrong place.
     *
     * @return null when correct, otherwise a description of the first failure
     */
    public static String selfTest() {
        int[][] cases = {
                {0, 0, 0}, {1, 1, 1}, {-1, -1, -1},
                {1000, 320, -2000}, {-33554432, -64, 33554431}, {33554431, 1967, -33554432},
                {-704, 48, 815}, {5000, 4031, -5000},
        };
        for (int[] c : cases) {
            long p = pack(c[0], c[1], c[2]);
            if (unpackX(p) != c[0] || unpackY(p) != c[1] || unpackZ(p) != c[2]) {
                return "position packing broken for " + c[0] + "," + c[1] + "," + c[2]
                        + " -> " + unpackX(p) + "," + unpackY(p) + "," + unpackZ(p);
            }
        }
        return null;
    }
}
