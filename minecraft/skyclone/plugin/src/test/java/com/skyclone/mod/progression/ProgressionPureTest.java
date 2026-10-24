package com.skyclone.mod.progression;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure checks of the progression formulas and data files against the research numbers. */
class ProgressionPureTest {

    private static <T> T load(String file, Class<T> type) throws Exception {
        try (InputStream in = ProgressionPureTest.class.getResourceAsStream("/data/progression/" + file)) {
            assertNotNull(in, "missing resource " + file);
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return Catalogs.read(r, type);
            }
        }
    }

    @Test
    void selfAnnouncedAwardsAreQuiet() {
        // wave-3 playtest: a new minion printed "+1 SkyBlock XP (New unique minion: Oak Minion I)" AND minions'
        // own "New unique minion: Oak Minion I! (1 crafted) +1 SkyBlock XP"
        String q = PMath.DEFAULT_QUIET_AWARDS;
        assertTrue(PMath.quietAward("minion:WHEAT:1", q));
        assertTrue(PMath.quietAward("hub_guide:mining_district", q));
        assertTrue(PMath.quietAward("collection:WHEAT:3", q));
        assertTrue(PMath.quietAward("MINION:OAK:1", q), "case-insensitive");
        // wave-4 endgame playtest: the Bestiary box already lists "+1 SkyBlock XP"
        assertTrue(PMath.quietAward("bestiary:dungeon_cellar_spider:1", q));
        assertTrue(PMath.quietAward("bestiary:milestone:2", q));
        // sources that print nothing themselves keep the generic line
        assertFalse(PMath.quietAward("skill:FARMING:5", q));
        assertFalse(PMath.quietAward("fairy:exchange:1", q));
        assertFalse(PMath.quietAward("scroll:HUB_CASTLE_TRAVEL_SCROLL", q));
        assertFalse(PMath.quietAward("objective:talk_banker", q));
        assertFalse(PMath.quietAward("minionx", q), "a prefix ends at its colon");
        assertFalse(PMath.quietAward("minion:OAK:1", ""));
        assertFalse(PMath.quietAward("minion:OAK:1", " , ,"));
        assertTrue(PMath.quietAward("anything", " anything "));
    }

    @Test
    void skyblockLevelFormula() {
        // research 02 section 6.1
        assertEquals(0, PMath.level(99, 100));
        assertEquals(1, PMath.level(100, 100));
        assertEquals(123, PMath.level(12_345, 100));
        assertEquals(45, PMath.intoLevel(12_345, 100), 1e-9);
        // the "how far to the gate" figure in the SkyBlock Level refusal hint
        assertEquals(645, PMath.xpToReach(55, 7, 100));
        assertEquals(0, PMath.xpToReach(700, 7, 100));
        assertEquals(0, PMath.xpToReach(900, 7, 100));
        assertEquals(1, PMath.xpToReach(699.5, 7, 100));
        assertEquals(50, PMath.levelHealth(10, 5), 1e-9);
        assertEquals(2, PMath.levelStrength(10, 5));
        assertEquals(0, PMath.levelStrength(4, 5));
        assertEquals("&8[&b212&8]", PMath.levelBracket(212));
        assertEquals("&7", PMath.levelColor(0));
        assertEquals("&f", PMath.levelColor(40));
        assertEquals("&4", PMath.levelColor(999));
    }

    @Test
    void bestiaryTiers() throws Exception {
        Catalogs.BestiaryFile f = load("bestiary.json", Catalogs.BestiaryFile.class);
        List<Integer> b1 = f.brackets.get("1");
        assertEquals(25, b1.size());
        assertEquals(20, b1.get(0));
        assertEquals(1_000_000, b1.get(24));      // research 08 section 6.1
        assertEquals(4, PMath.bestiaryTier(199, b1, 25));
        assertEquals(5, PMath.bestiaryTier(200, b1, 25));
        assertEquals(5, PMath.bestiaryTier(10_000, b1, 5));
        assertEquals(5, PMath.bestiaryMaxTier(b1, 200));
        assertEquals(-1, PMath.bestiaryNext(5, b1, 5));
        assertEquals(2, PMath.bestiaryMilestone(29));
        int fams = 0;
        Set<String> ids = new HashSet<>();
        for (Catalogs.BCategory c : f.categories) {
            for (Catalogs.Family fam : c.families) {
                fams++;
                assertTrue(ids.add(fam.id), "duplicate family id " + fam.id);
                assertEquals(PMath.bestiaryMaxTier(f.brackets.get(String.valueOf(fam.bracket)), fam.cap), fam.maxTier, fam.id);
                assertFalse(fam.matchNames.isEmpty(), fam.id);
            }
        }
        assertTrue(fams > 200, "families: " + fams);
        // the hub graveyard zombie: bracket 1, capped at 200 kills -> 5 tiers
        Catalogs.Family gz = null;
        for (Catalogs.BCategory c : f.categories) for (Catalogs.Family fam : c.families) if (fam.id.equals("graveyard_zombie")) gz = fam;
        assertNotNull(gz);
        assertEquals(5, gz.maxTier);
    }

    @Test
    void fairyExchanges() {
        assertEquals(1, PMath.fairyExchangesAvailable(12, 1));
        assertEquals(0, PMath.fairyExchangesAvailable(4, 0));
        assertEquals(0, PMath.fairyExchangesAvailable(10, 2));
        double[] s = PMath.fairyStats(10, 3, 1, 2, 10);
        assertEquals(30, s[0], 1e-9);
        assertEquals(10, s[1], 1e-9);
        assertEquals(5, s[2], 1e-9);
        assertEquals(1, s[3], 1e-9);
    }

    @Test
    void objectiveChainIsWellFormed() throws Exception {
        Catalogs.ObjectivesFile f = load("objectives.json", Catalogs.ObjectivesFile.class);
        assertTrue(f.chain.size() >= 25 && f.chain.size() <= 40, "chain length " + f.chain.size());
        Set<String> ids = new HashSet<>();
        for (Catalogs.ObjectiveDef o : f.chain) {
            assertTrue(ids.add(o.id), "duplicate " + o.id);
            assertTrue(Catalogs.TRIGGER_TYPES.contains(o.trigger.type), o.id + " " + o.trigger.type);
            assertTrue(o.trigger.amount >= 1, o.id);
            assertFalse(o.text.isBlank(), o.id);
            assertTrue(o.text.length() <= 32, "sidebar text too long: " + o.id);
            if (!o.trigger.zones.isEmpty()) assertNotNull(o.trigger.island, o.id + ": zones need an island");
        }
        // integration round 1: the SkyBlock Menu (slot 43) opened the Auction House and completed "Visit the
        // Auction House" without a visit. A visit completes at the place (its NPCs or its zone), never from a
        // menu title or a command that opens the same screen from anywhere.
        for (String id : List.of("visit_auction_house", "visit_bazaar")) {
            Catalogs.ObjectiveDef o = f.chain.stream().filter(x -> x.id.equals(id)).findFirst().orElseThrow();
            assertTrue(o.trigger.menu.isEmpty(), id + " completes from a menu title");
            assertTrue(o.trigger.commands.isEmpty(), id + " completes from a command");
            assertFalse(o.trigger.anchors.isEmpty(), id + " has no NPC");
            assertFalse(o.trigger.zones.isEmpty(), id + " has no zone");
            assertEquals("hub", o.trigger.island, id);
        }
        assertEquals("talk_island_helper", f.chain.get(0).id);
    }

    /**
     * playtest-endgame: a player who never talked to the Island Helper got "OBJECTIVE COMPLETE Visit The End /
     * The Barn / The Park" while the sidebar still said "Talk to your Island Helper". A step ahead of the chain
     * is recorded "early" (no box); the box comes when the chain reaches it, as one catch-up run.
     */
    @Test
    void outOfOrderObjectivesWaitForTheChain() throws Exception {
        Catalogs.ObjectivesFile f = load("objectives.json", Catalogs.ObjectivesFile.class);
        List<String> chain = new java.util.ArrayList<>();
        for (Catalogs.ObjectiveDef o : f.chain) chain.add(o.id);
        List<String> done = new java.util.ArrayList<>();
        List<String> skipped = new java.util.ArrayList<>();
        List<String> early = new java.util.ArrayList<>();

        assertEquals("talk_island_helper", PMath.currentObjective(chain, done, skipped));
        for (String id : List.of("visit_the_end", "visit_barn", "visit_park", "break_log")) {
            assertFalse(PMath.objectiveReached(chain, done, skipped, id), id + " credited ahead of the chain");
            early.add(id);
        }
        assertTrue(PMath.objectiveReached(chain, done, skipped, "talk_island_helper"));
        assertTrue(PMath.objectiveReached(chain, done, skipped, "not_in_chain"), "foreign ids are recorded at once");
        assertTrue(PMath.objectiveCatchUp(chain, done, skipped, early).isEmpty(), "nothing to catch up yet");

        // talk to the helper: break_log (done early) is credited with it, craft_workbench becomes current
        done.add("talk_island_helper");
        List<String> run = PMath.objectiveCatchUp(chain, done, skipped, early);
        assertEquals(List.of("break_log"), run);
        done.addAll(run); early.removeAll(run);
        assertEquals("craft_workbench", PMath.currentObjective(chain, done, skipped));

        // a skipped step is behind the pointer: doing it later is credited at once
        skipped.add("craft_workbench");
        assertTrue(PMath.objectiveReached(chain, done, skipped, "craft_workbench"));

        // the chain reaching visit_barn credits it and passes over a skipped step on the way to visit_park
        done.clear(); skipped.clear();
        for (String id : chain) { if (id.equals("visit_barn")) break; done.add(id); }
        skipped.add("reach_farming_5"); skipped.add("reach_foraging_5");
        assertEquals(List.of("visit_barn", "visit_park"), PMath.objectiveCatchUp(chain, done, skipped, early));
    }

    @Test
    void levelsAndMuseumData() throws Exception {
        Catalogs.LevelsFile l = load("levels.json", Catalogs.LevelsFile.class);
        assertEquals(100, l.xpPerLevel);
        assertEquals(5, l.objectiveXp);
        assertEquals(10, l.fairyExchangeXp);
        assertEquals(8, l.categories.size());
        assertEquals(14235, l.categories.get(0).max);   // sblevels.json core_task
        Catalogs.MuseumFile m = load("museum.json", Catalogs.MuseumFile.class);
        int total = 0, xp = 0;
        Set<String> ids = new HashSet<>();
        for (Catalogs.MCategory c : m.categories) for (Catalogs.MEntry e : c.entries) { total++; xp += e.xp; ids.add(e.id); }
        assertTrue(total > 900, "entries " + total);
        // research: museum progression is worth ~3,522 SkyBlock XP
        assertTrue(xp > 3000 && xp < 4200, "museum xp " + xp);
        for (Catalogs.MCategory c : m.categories)
            for (Catalogs.MEntry e : c.entries)
                if (e.child != null) assertTrue(ids.contains(e.child), e.id + " -> " + e.child);
    }

    @Test
    void levelRewardsFollowTheirGateDial() throws Exception {
        // The level-up box must never promise a feature at a different level than its gate opens it
        // (final verification 2026-10-04: Bazaar opened at Level 5, the Level 7 box still said "Access to the Bazaar").
        assertEquals(5, PMath.gateLevel("sblevel:5", 7));
        assertEquals(3, PMath.gateLevel(" 3 ", 7));
        assertEquals(7, PMath.gateLevel("'sblevel:7'", 5));
        assertEquals(-1, PMath.gateLevel("none", 7));
        assertEquals(-1, PMath.gateLevel("0", 7));
        assertEquals(-1, PMath.gateLevel("", 7));
        assertEquals(7, PMath.gateLevel("skill:combat:5", 7));
        Catalogs.LevelsFile f = load("levels.json", Catalogs.LevelsFile.class);
        Set<String> gated = new HashSet<>();
        for (Catalogs.Reward r : f.rewards) if (r.dial != null) gated.add(r.text + "=" + r.dial);
        assertTrue(gated.contains("Access to the Bazaar=bazaar.access-requirement"), gated.toString());
        assertTrue(gated.contains("Access to the Garden=garden.requirement"), gated.toString());
    }

    // ---- wave 5 -------------------------------------------------------------------------------------------

    @Test
    void bestiaryMagicFindAndCoinsFollowTheResearch() {
        // research 08 6.1: "+70 Magic Find (vs that mob)" and about "+40% coins" at Tier XX
        assertEquals(70.0, PMath.bestiaryMagicFind(20, 2, 5, 10), 1e-9);
        assertEquals(20.0, PMath.bestiaryMagicFind(10, 2, 5, 10), 1e-9);
        assertEquals(0.0, PMath.bestiaryMagicFind(0, 2, 5, 10), 1e-9);
        assertEquals(0.0, PMath.bestiaryMagicFind(-3, 2, 5, 10), 1e-9);
        assertEquals(40.0, PMath.bestiaryCoinPct(20, 2), 1e-9);
        assertEquals(140.0, PMath.scaledCoins(100, 40), 1e-9);
        assertEquals(0.0, PMath.scaledCoins(0, 40), 1e-9);
        assertTrue(Double.isNaN(PMath.scaledCoins(Double.NaN, 40)), "odd values are left exactly as they were");
    }

    @Test
    void pestBestiaryPaysFarmingFortune() throws Exception {
        Catalogs.BestiaryFile f = load("bestiary.json", Catalogs.BestiaryFile.class);
        Catalogs.BCategory garden = null;
        for (Catalogs.BCategory c : f.categories) if ("garden".equals(c.id)) garden = c;
        assertNotNull(garden);
        assertEquals("FARMING_FORTUNE", garden.fortune);
        int tiers = 0;
        for (Catalogs.Family fam : garden.families) tiers += fam.maxTier;
        // research 08: the pest bestiary is worth about +102 Farming Fortune at 0.4 a tier
        double ff = tiers * 0.4;
        assertTrue(ff >= 95 && ff <= 105, "pest bestiary max FF " + ff);
        for (Catalogs.BCategory c : f.categories) if (!"garden".equals(c.id)) assertEquals(null, c.fortune, c.id);
    }

    @Test
    void islandGuidesAreWellFormed() throws Exception {
        Catalogs.GuidesFile g = load("island_guides.json", Catalogs.GuidesFile.class);
        assertTrue(g.guides.size() >= 10, "guides " + g.guides.size());
        Set<String> islands = new HashSet<>();
        for (Catalogs.IslandGuide x : g.guides) {
            assertTrue(islands.add(x.island), "two guides for " + x.island);
            assertFalse("hub".equals(x.island), "hub districts are hub's District Guides");
            assertFalse("private_island".equals(x.island), "the private island is the objective chain's");
            assertTrue(x.steps.size() >= 2 && x.steps.size() <= 6, x.id + " steps " + x.steps.size());
            assertTrue(x.sbxp > 0, x.id);
            for (Catalogs.GuideStep st : x.steps) {
                assertTrue(Catalogs.GUIDE_TRIGGER_TYPES.contains(st.trigger.type), x.id + ": " + st.trigger.type);
                assertTrue(st.trigger.amount >= 1, x.id + ": " + st.text);
                assertTrue(st.hint != null && !st.hint.isBlank(), x.id + ": no hint for " + st.text);
                if ("zone".equals(st.trigger.type)) assertFalse(st.trigger.zones.isEmpty(), x.id);
                if ("npc".equals(st.trigger.type)) assertFalse(st.trigger.anchors.isEmpty(), x.id);
            }
        }
    }

    @Test
    void guideMatchingAndAdvance() {
        assertTrue(PMath.killMatches("den_splitter_spider_2", "splitter_spider", List.of("den_")));
        assertFalse(PMath.killMatches("hub_wolf", "wolf", List.of("den_")));
        assertTrue(PMath.killMatches("marsh_seacurse", "tidetot", List.of("tidetot")), "family ids match too");
        assertTrue(PMath.killMatches("anything", null, List.of()), "no filter = any mob");
        assertTrue(PMath.gatherMatches("MITHRIL_ORE", List.of("mithril")));
        assertFalse(PMath.gatherMatches("COAL_ORE", List.of("MITHRIL")));
        assertTrue(PMath.gatherMatches("WHEAT", List.of()));
        assertEquals(1, PMath.guideAdvance(0, 4));
        assertEquals(4, PMath.guideAdvance(3, 4));
        assertEquals(4, PMath.guideAdvance(9, 4), "never past finished");
        assertTrue(PMath.quietAward("island_guide:barn", PMath.DEFAULT_QUIET_AWARDS), "the guide box shows its own XP");
    }

    @Test
    void tomeOfProgressStages() {
        // research 02 6.2: given at 25, Uncommon 75, Rare 150, Epic 225, Legendary 300, Mythic 400
        assertEquals(0, PMath.tomeStage(24));
        assertEquals(1, PMath.tomeStage(25));
        assertEquals(2, PMath.tomeStage(75));
        assertEquals(3, PMath.tomeStage(150));
        assertEquals(6, PMath.tomeStage(400));
        assertEquals(6, PMath.tomeStage(9999));
    }

    @Test
    void xpGuideRanksTheNearestXpFirst() {
        // wave-5 regression: 86 SkyBlock XP in 25 minutes because every step stopped short of the first thresholds;
        // the guide must put "claim it now" and "nearly there" above the long ones, and not fill up with one kind
        List<PMath.XpTip> out = PMath.rankXpTips(List.of(
                new PMath.XpTip("skill", "Combat I", 5, 1.0),
                new PMath.XpTip("skill", "Farming I", 5, 0.2),
                new PMath.XpTip("skill", "Foraging I", 5, 0.4),
                new PMath.XpTip("collection", "Wheat I", 4, 0.2),
                new PMath.XpTip("fairy", "Exchange 5 Fairy Souls", 10, 0),
                new PMath.XpTip("objective", "Talk to the Banker", 5, 0.5),
                new PMath.XpTip("bestiary", "Nothing", 0, 0)), 5, 2);
        assertEquals(5, out.size());
        assertEquals("Exchange 5 Fairy Souls", out.get(0).label(), "claimable first");
        assertEquals("Farming I", out.get(1).label(), "same distance: more XP first");
        assertEquals("Wheat I", out.get(2).label());
        assertEquals("Foraging I", out.get(3).label());
        assertEquals("Talk to the Banker", out.get(4).label(), "third skill is capped out, the objective takes the slot");
        assertTrue(out.stream().noneMatch(t -> t.xp() <= 0), "a source paying nothing is never suggested");
        assertEquals(0.2, PMath.remainingShare(40, 0, 50), 1e-9);
        assertEquals(0.5, PMath.remainingShare(75, 50, 100), 1e-9);
        assertEquals(0.0, PMath.remainingShare(120, 50, 100), 1e-9);
    }

    @Test
    void skillLevelXpMatchesTheSkillsData() throws Exception {
        // the guide promises what the skills module pays (data/skills/skills.json), which is the research table
        try (InputStream in = ProgressionPureTest.class.getResourceAsStream("/data/skills/skills.json")) {
            assertNotNull(in, "missing data/skills/skills.json");
            com.google.gson.JsonObject skills = com.google.gson.JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("skills");
            for (String id : List.of("FARMING", "MINING", "COMBAT", "FORAGING", "FISHING", "ENCHANTING", "ALCHEMY", "TAMING")) {
                com.google.gson.JsonArray lv = skills.getAsJsonObject(id).getAsJsonArray("levels");
                for (int i = 0; i < lv.size(); i++) {
                    assertEquals(PMath.skillLevelSbXp(i + 1), lv.get(i).getAsJsonObject().get("sbxp").getAsInt(), id + " level " + (i + 1));
                }
            }
        }
        assertEquals(5, PMath.skillLevelSbXp(1));
        assertEquals(10, PMath.skillLevelSbXp(11));
        assertEquals(20, PMath.skillLevelSbXp(26));
        assertEquals(30, PMath.skillLevelSbXp(51));
    }
}
