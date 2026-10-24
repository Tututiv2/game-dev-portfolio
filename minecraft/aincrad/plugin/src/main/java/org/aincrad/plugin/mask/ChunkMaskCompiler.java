package org.aincrad.plugin.mask;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Deterministic, independently-verified compiler for the Town review-slice chunk mask
 * (town_core rectangle unioned with 3 corridor buffers, deduplicated). Reproduces the algorithm
 * checked into scripts/verify_chunk_mask.py exactly, including its integer chunk-flooring
 * behavior for negative coordinates, and validates itself against whatever
 * first_review_slice.exact_deduplicated_chunk_count / chunk_bounds the bundled layout JSON
 * currently states, rather than hardcoding a count.
 */
public final class ChunkMaskCompiler {

    private static final int CHUNK = 16;
    private static final int SEARCH_PAD = 400;

    private final JsonObject layout;

    public ChunkMaskCompiler(JsonObject layout) {
        this.layout = layout;
    }

    public static ChunkMaskCompiler fromClasspathResource(ClassLoader loader) throws IOException {
        try (InputStream in = loader.getResourceAsStream("floor-01-layout.json")) {
            if (in == null) {
                throw new IOException("floor-01-layout.json not found on classpath");
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return new ChunkMaskCompiler(JsonParser.parseReader(reader).getAsJsonObject());
            }
        }
    }

    /** Reads first_review_slice.full_floor_generation_authorized from the layout JSON. */
    public boolean fullFloorAuthorized() {
        JsonObject slice = layout.getAsJsonObject("first_review_slice");
        return slice.has("full_floor_generation_authorized")
                && slice.get("full_floor_generation_authorized").getAsBoolean();
    }

    public MaskResult compile() {
        JsonObject slice = layout.getAsJsonObject("first_review_slice");
        JsonObject townCore = slice.getAsJsonObject("town_core");
        JsonArray bounds = townCore.getAsJsonArray("block_bounds_xz");
        int minX = bounds.get(0).getAsInt();
        int minZ = bounds.get(1).getAsInt();
        int maxX = bounds.get(2).getAsInt();
        int maxZ = bounds.get(3).getAsInt();

        Set<ChunkCoord> chunks = new HashSet<>();
        int[] cxRange = blockRangeToChunkRange(minX, maxX);
        int[] czRange = blockRangeToChunkRange(minZ, maxZ);
        for (int cx = cxRange[0]; cx <= cxRange[1]; cx++) {
            for (int cz = czRange[0]; cz <= czRange[1]; cz++) {
                chunks.add(new ChunkCoord(cx, cz));
            }
        }
        int townCoreCount = chunks.size();

        JsonArray corridors = slice.getAsJsonArray("corridors");
        int minAllX = Integer.MAX_VALUE, maxAllX = Integer.MIN_VALUE;
        int minAllZ = Integer.MAX_VALUE, maxAllZ = Integer.MIN_VALUE;
        for (var el : corridors) {
            JsonObject corridor = el.getAsJsonObject();
            JsonArray polyline = corridor.getAsJsonArray("polyline");
            for (var pointEl : polyline) {
                JsonArray point = pointEl.getAsJsonArray();
                int x = point.get(0).getAsInt();
                int z = point.get(1).getAsInt();
                minAllX = Math.min(minAllX, x);
                maxAllX = Math.max(maxAllX, x);
                minAllZ = Math.min(minAllZ, z);
                maxAllZ = Math.max(maxAllZ, z);
            }
        }
        int[] searchCx = blockRangeToChunkRange(minAllX - SEARCH_PAD, maxAllX + SEARCH_PAD);
        int[] searchCz = blockRangeToChunkRange(minAllZ - SEARCH_PAD, maxAllZ + SEARCH_PAD);

        for (var el : corridors) {
            JsonObject corridor = el.getAsJsonObject();
            JsonArray polyline = corridor.getAsJsonArray("polyline");
            JsonArray a = polyline.get(0).getAsJsonArray();
            JsonArray b = polyline.get(1).getAsJsonArray();
            double ax = a.get(0).getAsDouble(), az = a.get(1).getAsDouble();
            double bx = b.get(0).getAsDouble(), bz = b.get(1).getAsDouble();
            double buffer = corridor.get("horizontal_buffer_blocks").getAsDouble();

            for (int cx = searchCx[0]; cx <= searchCx[1]; cx++) {
                for (int cz = searchCz[0]; cz <= searchCz[1]; cz++) {
                    double[] center = chunkCenter(cx, cz);
                    double distance = pointSegmentDistance(center[0], center[1], ax, az, bx, bz);
                    if (distance <= buffer) {
                        chunks.add(new ChunkCoord(cx, cz));
                    }
                }
            }
        }

        int minChunkX = Integer.MAX_VALUE, maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE, maxChunkZ = Integer.MIN_VALUE;
        for (ChunkCoord c : chunks) {
            minChunkX = Math.min(minChunkX, c.x());
            maxChunkX = Math.max(maxChunkX, c.x());
            minChunkZ = Math.min(minChunkZ, c.z());
            maxChunkZ = Math.max(maxChunkZ, c.z());
        }

        int expectedTotal = slice.get("exact_deduplicated_chunk_count").getAsInt();
        JsonObject expectedBounds = slice.getAsJsonObject("chunk_bounds");
        int expectedMinX = expectedBounds.get("min_chunk_x").getAsInt();
        int expectedMaxX = expectedBounds.get("max_chunk_x").getAsInt();
        int expectedMinZ = expectedBounds.get("min_chunk_z").getAsInt();
        int expectedMaxZ = expectedBounds.get("max_chunk_z").getAsInt();

        return new MaskResult(
                chunks, townCoreCount, chunks.size(), expectedTotal,
                minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                expectedMinX, expectedMaxX, expectedMinZ, expectedMaxZ
        );
    }

    private static int[] blockRangeToChunkRange(int lo, int hi) {
        return new int[]{Math.floorDiv(lo, CHUNK), Math.floorDiv(hi, CHUNK)};
    }

    private static double[] chunkCenter(int cx, int cz) {
        return new double[]{cx * (double) CHUNK + CHUNK / 2.0, cz * (double) CHUNK + CHUNK / 2.0};
    }

    private static double pointSegmentDistance(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double seglen2 = dx * dx + dz * dz;
        double t = seglen2 == 0
                ? 0.0
                : Math.max(0.0, Math.min(1.0, ((px - ax) * dx + (pz - az) * dz) / seglen2));
        double cx = ax + t * dx;
        double cz = az + t * dz;
        return Math.hypot(px - cx, pz - cz);
    }
}
