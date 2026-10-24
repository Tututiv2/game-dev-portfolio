package com.skyclone.mod.mayors;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The election math, the roster and the event tables against the research numbers — no server. */
class MayorsPureTest {

    static MayorRoster roster() throws Exception {
        try (var in = Files.newInputStream(Path.of("src/main/resources/data/mayors/mayors.json"))) {
            return MayorRoster.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    static EventData events() throws Exception {
        try (var in = Files.newInputStream(Path.of("src/main/resources/data/mayors/events.json"))) {
            return EventData.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test void rosterHangsTogether() throws Exception { assertNull(MayorsChecks.roster(roster())); }

    @Test void electionCalendarMatchesResearch() throws Exception { assertNull(MayorsChecks.math(roster())); }

    @Test void perksMatchTheRealGame() throws Exception { assertNull(MayorsChecks.referencePerks(roster())); }

    @Test void foxyExtraEventVariantsAndWindow() { assertNull(MayorsChecks.extraEvents()); }

    @Test void spookyBrackets() throws Exception { assertNull(MayorsChecks.brackets(events())); }

    @Test void pricesMatchResearch() throws Exception { assertNull(MayorsChecks.prices(events())); }

    @Test void mythMechanicsNumbers() { assertNull(MayorsChecks.mythMechanics()); }

    @Test void fameRankVotes() { assertNull(MayorsChecks.fameVotes()); }

    @Test void carnivalRules() throws Exception { assertNull(MayorsChecks.carnival(events())); }

    @Test void calendarAlerts() { assertNull(MayorsChecks.alerts()); }

    @Test void auctionNewsFollowsPresenceAndCandyIsLabelled() { assertNull(MayorsChecks.chatAndSidebar()); }

    @Test void mythologicalRitualNumbers() throws Exception { assertNull(MayorsChecks.mythRules(events())); }

    @Test void siegeWavesAndGifts() throws Exception { assertNull(MayorsChecks.siegeRules(events())); }

    @Test void maskAbilitiesAndSpookyPerks() { assertNull(MayorsChecks.masksAndSpooky()); }

    @Test void aliasesResolve() throws Exception {
        MayorRoster r = roster();
        assertEquals("aatrox.slayer_xp_buff", r.canonical("aatrox.slayer_xp"));
        assertEquals("diana.mythological_ritual", r.canonical("DIANA.MYTHOLOGICAL"));
        assertNull(r.canonical("nobody.nothing"));
        assertEquals(25.0, r.perk("aatrox.slayer_xp").value);
        assertEquals(60.0, r.perk("cole.mining_xp_buff").value);
    }

    @Test void tallyIsDeterministicAndBreaksTies() {
        List<String> c = List.of("a", "b", "c");
        List<String> o1 = ElectionMath.rank(Map.of("a", 5L, "b", 5L, "c", 9L), c, 517, 10);
        List<String> o2 = ElectionMath.rank(Map.of("a", 5L, "b", 5L, "c", 9L), c, 517, 10);
        assertEquals("c", o1.get(0));
        assertEquals(o1, o2);
    }

    @Test void perkParadeRotatesEvery18Days() throws Exception {
        MayorRoster r = roster();
        String a = ElectionMath.perkpocalypseMayor(r, 520, 0, 18, 517);
        String b = ElectionMath.perkpocalypseMayor(r, 520, 17, 18, 517);
        String c = ElectionMath.perkpocalypseMayor(r, 520, 18, 18, 517);
        assertNotNull(a);
        assertEquals(a, b);
        assertTrue(!a.equals(c));
        for (int d = 0; d < 372; d++) assertTrue(!"diaz".equals(ElectionMath.perkpocalypseMayor(r, 520, d, 18, 517)));
    }

    @Test void npcVotesSumToTotal() throws Exception {
        var cands = ElectionMath.rollCandidates(roster(), 10, 517, Set.of(), null, 8, false);
        long sum = ElectionMath.npcVotes(cands, 25, 517, 10).values().stream().mapToLong(Long::longValue).sum();
        assertEquals(25, sum);
    }
}
