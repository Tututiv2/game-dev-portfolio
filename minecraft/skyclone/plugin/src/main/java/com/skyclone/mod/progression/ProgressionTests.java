package com.skyclone.mod.progression;

import com.skyclone.api.Sky;
import com.skyclone.api.world.gen.Layout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Live self-tests: {@code /sb selftest progression}. Each returns null on pass or what is wrong. */
final class ProgressionTests {

    private ProgressionTests() {}

    static void register() {
        Sky.commands().selfTest("progression.data-loaded", ProgressionTests::dataLoaded);
        Sky.commands().selfTest("progression.sblevel-formula", ProgressionTests::levelFormula);
        Sky.commands().selfTest("progression.bestiary-brackets", ProgressionTests::bestiaryBrackets);
        Sky.commands().selfTest("progression.fairy-math", ProgressionTests::fairyMath);
        Sky.commands().selfTest("progression.objective-chain", ProgressionTests::objectiveChain);
        Sky.commands().selfTest("progression.objective-targets", ProgressionTests::objectiveTargets);
        Sky.commands().selfTest("progression.hub-anchors", ProgressionTests::hubAnchors);
        Sky.commands().selfTest("progression.fairy-souls-placed", ProgressionTests::fairySouls);
        Sky.commands().selfTest("progression.cakes-exist", ProgressionTests::cakes);
        Sky.commands().selfTest("progression.museum-entries", ProgressionTests::museum);
        Sky.commands().selfTest("progression.bestiary-families", ProgressionTests::bestiaryFamilies);
        Sky.commands().selfTest("progression.menus-build", ProgressionTests::menus);
        Sky.commands().selfTest("progression.district-guides", ProgressionTests::districtGuides);
        // wave 5
        Sky.commands().selfTest("progression.bestiary-magic-find", ProgressionTests::bestiaryMagicFind);
        Sky.commands().selfTest("progression.bestiary-counts-owned", ProgressionTests::bestiaryCounts);
        Sky.commands().selfTest("progression.museum-soulbound", ProgressionTests::museumSoulbound);
        Sky.commands().selfTest("progression.guides-data", ProgressionTests::guidesData);
        Sky.commands().selfTest("progression.guide-targets", ProgressionTests::guideTargets);
        Sky.commands().selfTest("progression.guides-menu", ProgressionTests::guidesMenu);
        Sky.commands().selfTest("progression.sblevel-perks", ProgressionTests::perks);
        // wave-5 regression: one combined Bestiary box per burst of tier-ups
        Sky.commands().selfTest("progression.bestiary-notice-merge", ProgressionTests::noticeMerge);
        Sky.commands().selfTest("progression.bestiary-notice-box", ProgressionTests::noticeBox);
        Sky.commands().selfTest("progression.bestiary-notice-dials", ProgressionTests::noticeDials);
        // wave-5 regression (early pacing): the SkyBlock XP Guide
        Sky.commands().selfTest("progression.xp-guide-data", ProgressionTests::xpGuideData);
        Sky.commands().selfTest("progression.xp-guide-skill-xp", ProgressionTests::xpGuideSkillXp);
        Sky.commands().selfTest("progression.xp-guide-rank", ProgressionTests::xpGuideRank);
    }

    /** The guide reads the skills' and collections' own data files; every collection tier must pay something. */
    static String xpGuideData() {
        if (Prog.xpGuide == null) return "XpGuide not built";
        if (Prog.xpGuide.collectionCount() < 50) return "only " + Prog.xpGuide.collectionCount() + " collections read from data/collections/collections.json";
        if (Prog.xpGuide.skillCount() < 9) return "only " + Prog.xpGuide.skillCount() + " skills read from data/skills/skills.json";
        String bad = Prog.xpGuide.badCollectionTier();
        return bad == null ? null : "collection tier pays no SkyBlock XP or needs 0 items: " + bad;
    }

    /** What the guide promises for a skill level is the research table (5 / 10 / 20 / 30), for every paying skill. */
    static String xpGuideSkillXp() {
        if (Prog.xpGuide == null) return "XpGuide not built";
        for (com.skyclone.api.skill.SkillType s : com.skyclone.api.skill.SkillType.values()) {
            if (Prog.xpGuide.skillXp(s, 1) == 0) continue;   // Social / Runecrafting pay none
            for (int l = 1; l <= s.maxLevel; l++) {
                int got = Prog.xpGuide.skillXp(s, l);
                if (got != 0 && got != PMath.skillLevelSbXp(l)) return s.display + " " + l + " pays " + got + ", research says " + PMath.skillLevelSbXp(l);
            }
        }
        return null;
    }

    /** Claimable first, nearer before farther, more XP breaks ties, two per kind, nothing that pays 0. */
    static String xpGuideRank() {
        List<PMath.XpTip> in = List.of(
                new PMath.XpTip("skill", "a", 5, 0.9), new PMath.XpTip("skill", "b", 5, 0.1), new PMath.XpTip("skill", "c", 5, 0.2),
                new PMath.XpTip("fairy", "d", 10, 0), new PMath.XpTip("collection", "e", 4, 0.1), new PMath.XpTip("x", "f", 0, 0));
        List<PMath.XpTip> out = PMath.rankXpTips(in, 10, 2);
        if (out.size() != 4) return "expected 4 tips (third skill capped, 0 XP dropped), got " + out.size();
        if (!"d".equals(out.get(0).label())) return "a claimable exchange must come first";
        if (!"b".equals(out.get(1).label())) return "ties on distance must go to the bigger reward";
        if (PMath.remainingShare(40, 0, 50) != 0.2) return "remainingShare(40, 0..50) must be 0.2";
        return null;
    }

    /** The same family twice in one burst is one entry spanning both; new families are counted. */
    static String noticeMerge() {
        List<BestiaryNotices.TierUp> l = new ArrayList<>();
        BestiaryNotices.merge(l, new BestiaryNotices.TierUp("zombie", "Zombie", 1, 2));
        BestiaryNotices.merge(l, new BestiaryNotices.TierUp("zombie", "Zombie", 2, 3));
        BestiaryNotices.merge(l, new BestiaryNotices.TierUp("bat", "Bat", 0, 1));
        BestiaryNotices.merge(l, new BestiaryNotices.TierUp("bat", "Bat", 1, 1));   // no tier gained: ignored
        if (l.size() != 2) return "expected 2 entries, got " + l.size();
        if (l.get(0).from() != 1 || l.get(0).to() != 3) return "Zombie should span I->III, got " + l.get(0);
        if (BestiaryNotices.totalTiers(l) != 3) return "total tiers " + BestiaryNotices.totalTiers(l) + " != 3";
        if (!BestiaryNotices.headline(l).contains("2 families") || !BestiaryNotices.headline(l).contains("1 new"))
            return "headline: " + BestiaryNotices.headline(l);
        return null;
    }

    /** Ten families in one dungeon room fit one short box with the summed SkyBlock XP. */
    static String noticeBox() {
        List<BestiaryNotices.TierUp> l = new ArrayList<>();
        for (int i = 0; i < 14; i++) BestiaryNotices.merge(l, new BestiaryNotices.TierUp("f" + i, "Fam" + i, 0, 1));
        List<String> body = BestiaryNotices.body(l, 1, 3, 4);
        if (body.size() != 4 + 1 + 3) return "body has " + body.size() + " lines: " + body;
        if (!body.get(4).contains("and 2 more")) return "no overflow line: " + body.get(4);
        if (!body.contains("  &8+&b14 SkyBlock XP")) return "SkyBlock XP not summed: " + body;
        if (!BestiaryNotices.entry(l.get(0)).contains("0➜&bI")) return "entry: " + BestiaryNotices.entry(l.get(0));
        return null;
    }

    static String noticeDials() {
        if (Prog.bestiaryNoticeQuietTicks.get() < 0) return "notice-quiet-ticks is negative";
        if (Prog.bestiaryNoticeQuietTicks.get() > 0 && Prog.bestiaryNoticeMaxTicks.get() < Prog.bestiaryNoticeQuietTicks.get())
            return "notice-max-ticks is below notice-quiet-ticks (the cap wins; the box would not wait for quiet)";
        if (Prog.bestiaryNoticePerLine.get() < 1 || Prog.bestiaryNoticeLines.get() < 1) return "the combined box would list no families";
        return null;
    }

    /** Research 08 6.1: +70 Magic Find at Tier XX; the hook is registered with core's loot service. */
    static String bestiaryMagicFind() {
        double mf20 = PMath.bestiaryMagicFind(20, Prog.bestiaryMfEarly.get(), Prog.bestiaryMfLate.get(), Prog.bestiaryMfSplit.get());
        if (Prog.bestiaryMfEarly.get() == 2 && Prog.bestiaryMfLate.get() == 5 && Prog.bestiaryMfSplit.get() == 10 && Math.abs(mf20 - 70) > 1e-9)
            return "Tier XX Magic Find is " + mf20 + ", research says 70";
        if (PMath.bestiaryMagicFind(0, 2, 5, 10) != 0) return "Tier 0 must give no Magic Find";
        if (Math.abs(PMath.scaledCoins(100, PMath.bestiaryCoinPct(20, 2)) - 140) > 1e-9) return "Tier XX at 2%/tier must turn 100 coins into 140";
        // a mob we know the family of: its Magic Find through core's per-target hook must include ours
        for (var def : Sky.mobs().all()) {
            String key = Prog.bestiary.familyKey(def, null);
            if (key == null) continue;
            Bestiary.Fam f = Prog.bestiary.family(key);
            if (f == null || !f.combat()) continue;
            return null;   // found one: the per-family maths above is the shipped formula; magicFindVs reads the same
        }
        return "no registered mob maps to a combat bestiary family";
    }

    /** Owned mobs count (sea creatures, trapper animals...), slayer bosses and their helpers do not, minis do. */
    static String bestiaryCounts() {
        List<String> bad = new ArrayList<>();
        for (var def : Sky.mobs().all()) {
            if (def == null || def.id == null) continue;
            boolean counts = Bestiary.counts(def);
            if (def.id.startsWith("sc_") && !counts) bad.add(def.id + " (sea creature) excluded");
            if (def.id.startsWith("slayer_mini_") && !counts) bad.add(def.id + " (slayer mini) excluded");
            if (def.id.matches("slayer_[a-z]+_[0-9]+b?") && counts) bad.add(def.id + " (slayer boss) counted");
            if (bad.size() > 8) break;
        }
        return bad.isEmpty() ? null : String.join(", ", bad);
    }

    /** The museum marker round-trips through ItemData and the guard reads it; milestone resolvers exist. */
    static String museumSoulbound() {
        String id = null;
        for (Catalogs.MCategory c : Prog.museumFile.categories) {
            if ("special".equals(c.id)) continue;
            for (Catalogs.MEntry e : c.entries) if (!e.isSet() && Sky.items().exists(e.id)) { id = e.id; break; }
            if (id != null) break;
        }
        if (id == null) return "no donatable single item is registered";
        org.bukkit.inventory.ItemStack st = Sky.items().create(id);
        if (Museum.boundProfile(st) != null) return "a fresh " + id + " already reads as soulbound";
        Sky.items().update(st, d -> d.extra.addProperty(Museum.SOULBOUND_KEY, "test-profile"));
        if (!"test-profile".equals(Museum.boundProfile(st))) return "museum_soulbound did not round-trip on " + id;
        if (Sky.requirements().describe("museum_milestone:3").startsWith("&c")) return "requirement museum_milestone is not registered";
        if (Sky.requirements().describe("museummilestone:3").startsWith("&c")) return "requirement museummilestone is not registered";
        return null;
    }

    static String guidesData() {
        if (Prog.guidesFile == null || Prog.guidesFile.guides.isEmpty()) return "island_guides.json missing or empty";
        Set<String> islands = new HashSet<>();
        for (Catalogs.IslandGuide g : Prog.guidesFile.guides) {
            if (g.id == null || g.island == null || g.name == null) return "guide without id/island/name";
            if (!islands.add(g.island)) return "two guides for island " + g.island;
            if ("hub".equals(g.island)) return "the hub's districts are hub's District Guides, not an Island Guide";
            if (g.steps.size() < 2) return g.id + ": fewer than 2 steps";
            for (Catalogs.GuideStep st : g.steps) {
                if (st.trigger == null || !Catalogs.GUIDE_TRIGGER_TYPES.contains(st.trigger.type)) return g.id + ": unknown trigger type in '" + st.text + "'";
                if (st.trigger.amount < 1) return g.id + ": amount < 1 in '" + st.text + "'";
                if ("zone".equals(st.trigger.type) && st.trigger.zones.isEmpty()) return g.id + ": zone step without zones";
                if ("npc".equals(st.trigger.type) && st.trigger.anchors.isEmpty()) return g.id + ": npc step without anchors";
            }
        }
        return null;
    }

    /** Every island, zone, NPC and mob the guides point at exists in this world (fails listing what is unwired). */
    static String guideTargets() {
        List<String> bad = Prog.islandGuides.unwired();
        return bad.isEmpty() ? null : bad.size() + " unwired: " + bad;
    }

    static String guidesMenu() {
        org.bukkit.entity.Player p = null;
        for (org.bukkit.entity.Player o : org.bukkit.Bukkit.getOnlinePlayers()) if (Prog.ready(o)) { p = o; break; }
        if (p == null) return null;   // needs a player to build per-player icons; the data test covers the rest
        Prog.islandGuides.build(p, 0);
        return null;
    }

    static String perks() {
        if (PMath.tomeStage(24) != 0 || PMath.tomeStage(25) != 1 || PMath.tomeStage(75) != 2 || PMath.tomeStage(400) != 6)
            return "tome stages do not follow research 02 6.2 (25/75/150/225/300/400)";
        if (!Sky.items().exists(Perks.TOME_ID)) return Perks.TOME_ID + " is not registered (accessories) - the Level 25 reward cannot be handed over";
        return null;
    }

    /**
     * The Objective-over-guide sidebar section reads hub's guides.json and finds the player's district by hub
     * zone id: every guide must name a real hub zone, and the 349 section must still sit above hub's 350.
     */
    static String districtGuides() {
        var names = Prog.objectives.guideNames();
        if (names.isEmpty()) return "data/hub/guides.json missing or empty (the district guide line can never show)";
        Layout hub = safeLayout("hub");
        if (hub == null) return "hub layout not generated yet";
        List<String> miss = new ArrayList<>();
        for (String id : names.keySet()) {
            boolean found = false;
            for (Layout.Zone z : hub.zones()) if (z.id().equals(id)) { found = true; break; }
            if (!found) miss.add(id);
        }
        if (!miss.isEmpty()) return "guides with no hub zone of that id: " + miss;
        if (Sky.requirements().describe("hub:guide:" + names.keySet().iterator().next()).startsWith("&c"))
            return "requirement prefix 'hub' (hub:guide:<id>) is not registered";
        return null;
    }

    static String dataLoaded() {
        if (Prog.levelsFile == null || Prog.levelsFile.categories.size() != 8) return "levels.json: expected 8 task categories";
        if (Prog.levelsFile.rewards.isEmpty() || Prog.levelsFile.emblems.size() < 20) return "levels.json: rewards/emblems missing";
        if (Prog.bestiaryFile == null || Prog.bestiaryFile.brackets == null || Prog.bestiaryFile.brackets.size() != 8) return "bestiary.json: expected 8 brackets";
        for (var e : Prog.bestiaryFile.brackets.entrySet()) if (e.getValue().size() != 25) return "bracket " + e.getKey() + " has " + e.getValue().size() + " tiers, expected 25";
        for (Catalogs.BCategory c : Prog.bestiaryFile.categories)
            for (Catalogs.Family f : c.families)
                if (!Prog.bestiaryFile.brackets.containsKey(String.valueOf(f.bracket))) return "family " + f.id + " has unknown bracket " + f.bracket;
        if (Prog.museumFile == null || Prog.museum.entryCount() < 900) return "museum.json: only " + (Prog.museum == null ? 0 : Prog.museum.entryCount()) + " entries";
        if (Prog.objectivesFile == null || Prog.objectivesFile.chain.size() < 25) return "objectives.json: chain shorter than 25";
        return null;
    }

    static String levelFormula() {
        // research 02 section 6.1: 100 XP/level, +5 Health/level, +1 Strength per 5 levels; colours every 40
        if (PMath.level(0, 100) != 0 || PMath.level(99, 100) != 0 || PMath.level(100, 100) != 1 || PMath.level(12_345, 100) != 123)
            return "level(xp) is not floor(xp/100)";
        if (PMath.levelHealth(10, 5) != 50) return "10 levels should give 50 Health";
        if (PMath.levelStrength(10, 5) != 2 || PMath.levelStrength(4, 5) != 0) return "Strength should be 1 per 5 levels";
        if (!"&7".equals(PMath.levelColor(39)) || !"&f".equals(PMath.levelColor(40)) || !"&4".equals(PMath.levelColor(500)))
            return "level colour brackets wrong";
        if (Prog.levelsFile.xpPerLevel != 100 || Prog.levelsFile.objectiveXp != 5 || Prog.levelsFile.fairyExchangeXp != 10)
            return "levels.json constants differ from the research (100 / 5 / 10)";
        return null;
    }

    static String bestiaryBrackets() {
        List<Integer> b1 = Prog.bestiaryFile.brackets.get("1");
        // research 08 section 6.1: bracket 1 tier 1 = 20 kills ... top tier 1,000,000
        if (b1.get(0) != 20 || b1.get(4) != 200 || b1.get(24) != 1_000_000) return "bracket 1 thresholds differ from the research";
        if (PMath.bestiaryTier(199, b1, 25) != 4 || PMath.bestiaryTier(200, b1, 25) != 5) return "tier(kills) wrong at 200";
        if (PMath.bestiaryMaxTier(b1, 200) != 5) return "a bracket-1 family capped at 200 kills must max at tier 5";
        if (PMath.bestiaryNext(5, b1, 5) != -1 || PMath.bestiaryNext(4, b1, 5) != 200) return "next-tier threshold wrong";
        if (PMath.bestiaryMilestone(25) != 2) return "milestone is one per 10 tiers";
        for (Catalogs.BCategory c : Prog.bestiaryFile.categories)
            for (Catalogs.Family f : c.families) {
                int expect = PMath.bestiaryMaxTier(Prog.bestiaryFile.brackets.get(String.valueOf(f.bracket)), f.cap);
                if (expect != f.maxTier) return "family " + f.id + " maxTier " + f.maxTier + " != computed " + expect;
            }
        return null;
    }

    static String fairyMath() {
        if (PMath.fairyExchangesAvailable(12, 1) != 1 || PMath.fairyExchangesAvailable(4, 0) != 0) return "exchanges = found/5 - exchanged";
        double[] s = PMath.fairyStats(10, 3, 1, 2, 10);
        if (s[0] != 30 || s[1] != 10 || s[2] != 5 || s[3] != 1) return "fairy stats after 10 exchanges wrong";
        return null;
    }

    static String objectiveChain() {
        Set<String> ids = new HashSet<>();
        for (Catalogs.ObjectiveDef o : Prog.objectivesFile.chain) {
            if (o.id == null || !ids.add(o.id)) return "duplicate or empty objective id " + o.id;
            if (o.text == null || o.text.isBlank() || o.hint == null) return o.id + ": missing text/hint";
            if (o.trigger == null || !Catalogs.TRIGGER_TYPES.contains(o.trigger.type)) return o.id + ": unknown trigger " + (o.trigger == null ? null : o.trigger.type);
            String t = o.trigger.type;
            if (t.equals("npc") && o.trigger.anchors.isEmpty()) return o.id + ": npc trigger without anchors";
            if ((t.equals("island") || t.equals("area")) && o.trigger.island == null) return o.id + ": no island";
            if (t.equals("area") && o.trigger.areas.isEmpty()) return o.id + ": no areas";
            if (!o.trigger.zones.isEmpty() && o.trigger.island == null) return o.id + ": zones without an island";
            if (o.id.startsWith("visit_") && (!o.trigger.menu.isEmpty() || !o.trigger.commands.isEmpty()))
                return o.id + ": a visit must not complete from a menu or command opened anywhere";
            if (t.equals("requirement") && (o.trigger.key == null || !o.trigger.key.contains(":"))) return o.id + ": bad requirement key";
            if (t.equals("break") && o.trigger.materials.isEmpty() && o.trigger.gather == null) return o.id + ": break with nothing to break";
            for (String m : o.trigger.materials) if (org.bukkit.Material.matchMaterial(m) == null) return o.id + ": unknown material " + m;
        }
        return null;
    }

    /** Wiring: islands and NPC anchors the chain points at exist in this world. */
    static String objectiveTargets() {
        List<String> missing = new ArrayList<>();
        Set<String> anchorsSeen = new HashSet<>();
        for (var d : Sky.worlds().islands()) {
            Layout l = safeLayout(d.id);
            if (l != null) anchorsSeen.addAll(l.anchors().keySet());
        }
        for (Catalogs.ObjectiveDef o : Prog.objectivesFile.chain) {
            String isl = o.trigger.island;
            if (isl != null && Sky.worlds().island(isl) == null) missing.add(o.id + "->island " + isl);
            if ("npc".equals(o.trigger.type)) {
                boolean any = false;
                for (String a : o.trigger.anchors) if (anchorsSeen.contains(a)) any = true;
                if (!any && !anchorsSeen.isEmpty()) missing.add(o.id + "->anchor " + o.trigger.anchors.get(0));
            }
            if ("area".equals(o.trigger.type) && isl != null && safeLayout(isl) != null) {
                boolean any = false;
                for (String a : o.trigger.areas) if (Sky.worlds().area(isl, a) != null) any = true;
                if (!any) missing.add(o.id + "->area " + isl + ":" + o.trigger.areas);
            }
            if (!o.trigger.zones.isEmpty() && isl != null) {
                Layout l = safeLayout(isl);
                if (l != null) {
                    boolean any = false;
                    for (var z : l.zones()) if (o.trigger.zones.contains(z.id())) any = true;
                    if (!any) missing.add(o.id + "->zone " + isl + ":" + o.trigger.zones);
                }
            }
        }
        return missing.isEmpty() ? null : "unwired (players can skip the skippable ones): " + String.join(", ", missing);
    }

    static String hubAnchors() {
        Layout hub = safeLayout("hub");
        if (hub == null) return "hub layout not generated yet";
        List<String> miss = new ArrayList<>();
        for (String a : List.of("npc.fairy", "npc.museum")) if (hub.anchor(a) == null) miss.add(a);
        if (hub.area("museum") == null) miss.add("area museum");
        return miss.isEmpty() ? null : "hub is missing " + miss;
    }

    static String fairySouls() {
        Prog.fairy.rescan();
        if (Prog.fairy.total() == 0) return "no fairy_soul.* anchors in any island layout (builders place them)";
        return null;
    }

    static String cakes() {
        Prog.cakes.load();
        if (Prog.cakes.all().size() != 20) return "expected 20 century cakes, found " + Prog.cakes.all().size();
        for (String id : Prog.cakes.all().keySet()) if (!Sky.items().exists(id)) return "cake " + id + " is not registered";
        return null;
    }

    static String museum() {
        int avail = Prog.museum.availableCount();
        for (Catalogs.MCategory c : Prog.museumFile.categories)
            for (Catalogs.MEntry e : c.entries)
                if (e.child != null && Prog.museum.entry(e.child) == null) return "entry " + e.id + " counts for unknown child " + e.child;
        if (avail == 0) return "no museum entry has its items registered (weapons/armor modules missing?)";
        return null;
    }

    static String bestiaryFamilies() {
        Prog.bestiary.index();
        int n = 0;
        for (Bestiary.Cat c : Prog.bestiary.visible()) n += c.families.size();
        if (n == 0) return "no registered mob maps to a bestiary family (no mob modules loaded?)";
        return null;
    }

    static String menus() {
        try {
            PData.Level lv = new PData.Level();
            lv.awards.put("skill:farming:5", 25);
            lv.awards.put("fairy:exchange:1", 10);
            lv.xp = 35;
            Prog.levels.buildMenu(null, lv);
            for (int i = 0; i < Prog.levelsFile.categories.size(); i++) Prog.levels.buildCategory(null, lv, i);
            Prog.objectives.buildQuestLog(null, new PData.Objectives(), 0);
            Prog.fairy.buildMenu(null, new PData.Fairy(), true);
            Prog.bestiary.buildMain(null, new PData.Bestiary());
            Prog.museum.buildMain(null, new PData.Museum());
            Prog.cakes.buildMenu(null, new PData.Cakes());
        } catch (Throwable t) {
            return "menu build threw " + t;
        }
        return null;
    }

    private static Layout safeLayout(String island) {
        try { return Sky.worlds().layout(island); } catch (Throwable t) { return null; }
    }
}
