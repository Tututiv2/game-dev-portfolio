package com.skyclone.mod.hunting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Fusion Machine: two shards in, a choice of shards out. Pure.
 *
 * <p>The reference publishes the IDEA (shards sit on id lines C1..L60; the Chameleon "grants the next 3 shards
 * from that shard's ID line"; Reptile fusions exist and can double) but not the result table, so the rules
 * here are ours, faithful in spirit and marked TUNE:
 * <ol>
 *   <li><b>Line</b> - the next shard after the first input on the id line of the HIGHER input rarity
 *       (same rarity: 2 shards out, otherwise 1).</li>
 *   <li><b>Family</b> - two shards sharing a family give a member of that family one rarity up, of the
 *       first input's alignment when it has one there; which member depends on the two id numbers. This is
 *       the only way to make the fusion-only shards (the upper Elementals, the top Reptiles).</li>
 *   <li><b>Chameleon</b> - Chameleon + X gives the next 3 shards on X's line, one each.</li>
 * </ol>
 */
public final class Fusion {

    private Fusion() {}

    public static final String CHAMELEON = "SHARD_CHAMELEON";

    public record Output(String shardId, int amount, String kind) {}

    public static List<Output> outputs(ShardCatalog cat, ShardDef a, ShardDef b) {
        Map<String, Output> out = new LinkedHashMap<>();
        if (a == null || b == null) return List.of();
        boolean ca = CHAMELEON.equals(a.id), cb = CHAMELEON.equals(b.id);
        if (ca && cb) return List.of();
        if (ca || cb) {
            ShardDef x = ca ? b : a;
            List<ShardDef> line = cat.line(x.rarity);
            int idx = indexOf(line, x);
            for (int k = 1; k <= 3 && !line.isEmpty(); k++) {
                ShardDef s = line.get((idx + k) % line.size());
                if (s.id.equals(x.id) || s.id.equals(CHAMELEON)) continue;
                out.putIfAbsent(s.id, new Output(s.id, 1, "chameleon"));
            }
            return new ArrayList<>(out.values());
        }
        // 1. family
        int top = Math.max(a.rarityIndex(), b.rarityIndex());
        for (String f : a.families) {
            if (!b.inFamily(f)) continue;
            ShardDef pick = familyPick(cat, f, Math.min(4, top + 1), a, b);
            if (pick == null && top < 4) continue;
            if (pick == null) pick = familyPick(cat, f, top, a, b);
            if (pick != null && !pick.id.equals(a.id) && !pick.id.equals(b.id))
                out.putIfAbsent(pick.id, new Output(pick.id, 1, "family:" + f));
        }
        // 2. line
        String lineRarity = HuntMath.RARITIES[top];
        List<ShardDef> line = cat.line(lineRarity);
        if (!line.isEmpty()) {
            ShardDef next = null;
            for (ShardDef s : line) if (s.num > a.num && !s.id.equals(CHAMELEON)) { next = s; break; }
            if (next == null) for (ShardDef s : line) if (!s.id.equals(CHAMELEON)) { next = s; break; }
            if (next != null && !next.id.equals(a.id)) {
                int amt = a.rarityIndex() == b.rarityIndex() ? 2 : 1;
                out.putIfAbsent(next.id, new Output(next.id, amt, "line"));
            }
        }
        return new ArrayList<>(out.values());
    }

    private static ShardDef familyPick(ShardCatalog cat, String family, int rarity, ShardDef a, ShardDef b) {
        List<ShardDef> c = new ArrayList<>();
        for (ShardDef s : cat.line(HuntMath.RARITIES[rarity])) if (s.inFamily(family) && !s.id.equals(CHAMELEON)) c.add(s);
        if (c.isEmpty()) return null;
        List<ShardDef> same = new ArrayList<>();
        for (ShardDef s : c) if (s.alignment != null && s.alignment.equals(a.alignment)) same.add(s);
        List<ShardDef> pool = same.isEmpty() ? c : same;
        return pool.get(Math.floorMod(a.num + b.num, pool.size()));
    }

    private static int indexOf(List<ShardDef> line, ShardDef x) {
        for (int i = 0; i < line.size(); i++) if (line.get(i).id.equals(x.id)) return i;
        return 0;
    }

    /** Shards of each input consumed (Chameleon is consumed 1 at a time). */
    public static int cost(ShardDef s) {
        if (CHAMELEON.equals(s.id)) return 1;
        return HuntMath.fuseCost(s.rarity);
    }
}
