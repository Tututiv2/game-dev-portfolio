package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Live self-tests for the real-game audit fixes of 2026-10-06 (junk tiers, Moby-Duck, chapters, Jawbus, Y64, XP). */
final class FishSelfTestsAudit {

    private FishSelfTestsAudit() {}

    static void register() {
        Sky.commands().selfTest("fishing.audit-junk", FishSelfTestsAudit::junk);
        Sky.commands().selfTest("fishing.audit-chapters", FishSelfTestsAudit::chapters);
        Sky.commands().selfTest("fishing.audit-gates", FishSelfTestsAudit::gates);
    }

    /** Junk has its own 89/10/1 split and every junk row (Moby-Duck included) is a real, registered item. */
    static String junk() {
        FishingCatalog.Treasure t = Fishing.CAT.treasure;
        Map<String, Double> w = t.weightsFor(true);
        if (Math.abs(FishMath.treasureShare(w, 0, 0, 0, "outstanding") - 0.01) > 1e-9) return "junk OUTSTANDING share is not 1%: " + w;
        for (String q : new String[]{"good", "great", "outstanding"})
            for (FishingCatalog.TreasureEntry e : t.junk.getOrDefault(q, List.of()))
                if (!Sky.items().exists(e.item)) return "junk row " + e.item + " is not a registered item";
        if (!Sky.items().exists(MobyDuck.DUCK) || !Sky.items().exists(MobyDuck.EDITION)) return "Moby-Duck items missing";
        if (Sky.items().create(MobyDuck.DUCK, 1).getMaxStackSize() != 1) return "Moby-Duck stacks (its bonus lives on the item)";
        return null;
    }

    /** Seven chapters; every reward and every item a task names exists; every creature a task names is a mob. */
    static String chapters() {
        List<FishingCatalog.Chapter> ch = Fishing.CAT.jonesy;
        if (ch.size() != 7 || !ch.get(6).pearl) return "expected 7 chapters ending in the Pearl, got " + ch.size();
        List<String> bad = new ArrayList<>();
        for (FishingCatalog.Chapter c : ch) {
            for (FishingCatalog.TreasureEntry e : c.rewards) if (!Sky.items().exists(e.item)) bad.add("reward:" + e.item);
            for (FishingCatalog.Task task : c.tasks) {
                switch (task.kind) {
                    case "own", "fished" -> { boolean any = false; for (String id : task.arg.split("\\|")) any |= Sky.items().exists(id);
                        if (!any) bad.add(task.kind + ":" + task.arg); }
                    case "caught" -> { if (Sky.mobs().def(task.arg) == null) bad.add("caught:" + task.arg); }
                    case "part" -> { if (!"ANY".equals(task.arg) && !Fishing.CAT.parts.containsKey(task.arg)) bad.add("part:" + task.arg); }
                    default -> { }
                }
            }
        }
        return bad.isEmpty() ? null : "chapter rows naming nothing: " + bad;
    }

    /** Lord Jawbus needs the Adept title; the Flaming Worm needs lava above Y64; XP is the live game's. */
    static String gates() {
        MobDef j = Sky.mobs().def("sc_lord_jawbus");
        if (j == null || j.extra == null || !j.extra.has("requires") || !"trophy:title:2".equals(j.extra.get("requires").getAsString()))
            return "Lord Jawbus lost its trophy:title:2 requirement";
        FishingCatalog.Pool hl = Fishing.CAT.pool("hollows_lava");
        boolean y = false;
        if (hl != null) for (FishingCatalog.PoolCreature c : hl.creatures)
            if (c.mob.equals("sc_flaming_worm")) y = c.cond != null && c.cond.contains("above:64");
        if (!y) return "Flaming Worm is not limited to lava above Y64";
        PoolResolver.Spot s = new PoolResolver.Spot();
        s.island = "crystal_hollows"; s.liquid = "lava"; s.fishingLevel = 50; s.zoneText = " precursor_remnants"; s.y = 50;
        if (!Fishing.RES.options(s).isEmpty()) return "something bites in Precursor lava at Y50";
        MobDef w = Sky.mobs().def("sc_flaming_worm");
        if (w == null || Fishing.fishingXpOf(w) != 1150) return "Flaming Worm Fishing XP is not the live 1,150";
        return null;
    }
}
