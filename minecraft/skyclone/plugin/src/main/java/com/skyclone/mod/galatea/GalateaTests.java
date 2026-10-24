package com.skyclone.mod.galatea;

import com.skyclone.api.Sky;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Live self-tests ({@code /sb selftest galatea}). Each returns null on pass. */
final class GalateaTests {

    private GalateaTests() {}

    static void register() {
        Sky.commands().selfTest("galatea.items-and-mobs", GalateaTests::itemsAndMobs);
        Sky.commands().selfTest("galatea.layouts", GalateaTests::layouts);
        Sky.commands().selfTest("galatea.warps", GalateaTests::warps);
        Sky.commands().selfTest("galatea.frog-math", GalateaTests::frogMath);
        Sky.commands().selfTest("galatea.shops", GalateaTests::shops);
        Sky.commands().selfTest("galatea.menus-build", GalateaTests::menus);
    }

    static String itemsAndMobs() {
        List<String> bad = new ArrayList<>();
        for (FrogMath.Species s : FrogMath.SPECIES) for (FrogMath.Tier t : FrogMath.Tier.values()) if (!Sky.items().exists(s.item(t))) bad.add(s.item(t));
        for (String id : List.of("LOTUS_ATOLL_TRAVEL_SCROLL", "TORRHUS_CANYON_TRAVEL_SCROLL", "TORRHUS_SPRINGS_TRAVEL_SCROLL", "TORRHUS_SAFARI_ENTRANCE_TRAVEL_SCROLL",
                "RIBBIT_THE_FISH", "LOTUS", "LOTUS_SILVER", "LOTUS_GOLD", "LOTUS_DIAMOND", "FROGCOIN")) if (!Sky.items().exists(id)) bad.add(id);
        for (String m : List.of("galatea_parched", "galatea_grizzly_bear", "galatea_queen_ant", "galatea_soldier_ant")) {
            MobDef d = Sky.mobs().def(m);
            if (d == null) { bad.add("mob " + m); continue; }
            for (DropDef dr : d.drops) if (!Sky.items().exists(dr.item)) bad.add(m + " drop " + dr.item);
        }
        for (String r : List.of("TORRHUS_CANYON_TRAVEL_SCROLL", "TORRHUS_SPRINGS_TRAVEL_SCROLL")) {
            boolean found = false;
            for (Recipe rec : Sky.recipes().producing(r)) { found = true; break; }
            if (!found) bad.add("no recipe for " + r);
        }
        return bad.isEmpty() ? null : "missing: " + bad;
    }

    static String layouts() {
        List<String> bad = new ArrayList<>();
        Map<String, Integer> souls = Map.of(GalateaModule.ATOLL, 6, GalateaModule.CANYON, 12);
        for (String island : List.of(GalateaModule.ATOLL, GalateaModule.CANYON)) {
            Layout l = Sky.worlds().layout(island);
            if (l == null) { bad.add(island + " has no layout"); continue; }
            for (String a : List.of("spawn")) if (l.anchor(a) == null) bad.add(island + " anchor " + a);
            if (l.area("portal.hub") == null) bad.add(island + " portal.hub");
            int n = 0;
            for (String a : l.anchors().keySet()) if (a.startsWith("fairy_soul.")) n++;
            if (n != souls.get(island)) bad.add(island + " has " + n + " fairy souls, want " + souls.get(island));
            boolean zone = false;
            for (Layout.Zone z : l.zones()) if (z.id().equals(island)) zone = true;
            if (!zone) bad.add(island + " zone " + island + " missing");
        }
        for (GalateaModule.Npc n : GalateaModule.NPCS.values()) {
            Layout l = Sky.worlds().layout(n.island);
            if (l != null && l.anchor(n.anchor) == null) bad.add("npc " + n.id + " anchor " + n.anchor);
        }
        return bad.isEmpty() ? null : String.join("; ", bad);
    }

    static String warps() {
        Map<String, WorldService.WarpInfo> by = new HashMap<>();
        for (WorldService.WarpInfo w : Sky.worlds().warpInfos()) by.put(w.id(), w);
        List<String> bad = new ArrayList<>();
        for (String w : List.of(GalateaModule.ATOLL, GalateaModule.CANYON, "torrhus_springs", "torrhus_safari"))
            if (!by.containsKey(w)) bad.add(w);
        if (by.containsKey(GalateaModule.ATOLL) && !GalateaModule.ATOLL.equals(by.get(GalateaModule.ATOLL).island())) bad.add("lotus_atoll warp on the wrong island");
        if (!by.containsKey("hub")) bad.add("no hub warp for the portals");
        if (!by.containsKey("backwater_bayou")) bad.add("no backwater_bayou warp for the atoll's second arch");
        if (!by.containsKey("moonmarsh")) bad.add("no moonmarsh warp for the canyon's second arch");
        return bad.isEmpty() ? null : "warps missing/wrong: " + bad;
    }

    static String frogMath() {
        Map<String, Integer> m = new HashMap<>();
        for (FrogMath.Species s : FrogMath.SPECIES) m.put(s.item(FrogMath.Tier.SILVER), 1);
        if (FrogMath.title(m) != 2) return "every species at Silver should be Adept (2), got " + FrogMath.title(m);
        if (FrogMath.tier(0.99, 0, 100, 0, 100, 600) != FrogMath.Tier.GOLD) return "gold pity at 100 did not fire";
        if (FrogMath.tier(0.99, 0, 0, 600, 100, 600) != FrogMath.Tier.DIAMOND) return "diamond pity at 600 did not fire";
        long[] d = FrogMath.denominations(FrogMath.DIAMOND + FrogMath.GOLD + FrogMath.SILVER + 7);
        if (d[0] != 1 || d[1] != 1 || d[2] != 1 || d[3] != 7) return "lotus denominations wrong";
        return null;
    }

    static String shops() {
        List<String> bad = new ArrayList<>();
        for (List<GalateaMenus.Offer> list : List.of(GalateaMenus.PROFESSOR, GalateaMenus.VENDOR, GalateaMenus.EATER, GalateaMenus.OVERSEER))
            for (GalateaMenus.Offer o : list) {
                if (!Sky.items().exists(o.item())) bad.add(o.item());
                for (String c : o.costItems()) if (!Sky.items().exists(c)) bad.add("cost " + c);
            }
        return bad.isEmpty() ? null : "shop items not registered (hidden from the shop): " + bad;
    }

    static String menus() {
        GalateaMenus.buildMain(null);
        GalateaMenus.buildLedger(null);
        GalateaMenus.buildProfessor(null);
        GalateaMenus.buildRelease(null);
        GalateaMenus.buildJournal(null);
        GalateaMenus.buildShop(null, "t", GalateaMenus.OVERSEER, null);
        if (Journal.chapters().size() < 4) return "journal has " + Journal.chapters().size() + " chapters, want 4";
        return null;
    }
}
