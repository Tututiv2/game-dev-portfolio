package com.skyclone.mod.mayors;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure data checks shared by the live self-tests and the unit tests: they need no server, only the
 * roster/event data, and each returns null on success or what is wrong.
 */
final class MayorsChecks {

    private MayorsChecks() {}

    /** Perk ids other modules were told to read (MODULE_BRIEFS, pets' dials). Must all resolve. */
    static final List<String> PROMISED_IDS = List.of(
            "marina.fishing_festival", "cole.mining_fiesta", "aatrox.slayer_xp", "diana.mythological", "paul.ezpz",
            "diana.pet_xp_buff", "diana.sharing_is_caring", "cole.prospection", "cole.molten_forge", "derpy.turbo_minions",
            "derpy.quad_taxes", "diaz.shopping_spree", "finnegan.goated", "finnegan.pest_eradicator", "paul.marauder");

    static String roster(MayorRoster r) {
        if (r.mayors.size() < 11) return "only " + r.mayors.size() + " mayors";
        Set<String> ids = new HashSet<>();
        for (MayorRoster.Mayor m : r.mayors) {
            if (m.perks.isEmpty()) return m.id + " has no perks";
            if (!m.special && m.perks.size() > 4) return m.id + " has more than 4 perks";
            for (MayorRoster.Perk p : m.perks) {
                if (!p.id.startsWith(m.id + ".")) return "perk " + p.id + " is not prefixed by its mayor";
                if (!ids.add(p.id)) return "duplicate perk " + p.id;
                if (p.name == null || p.name.isBlank() || p.lore.isEmpty()) return "perk " + p.id + " has no name or lore";
                if (p.owner == null) return "perk " + p.id + " has no owner";
            }
        }
        for (String s : r.specialRotation) if (r.mayor(s) == null || !r.mayor(s).special) return "special rotation names " + s;
        for (String id : PROMISED_IDS) if (r.canonical(id) == null) return "promised perk id " + id + " does not resolve";
        return null;
    }

    /** research/05 §8: the election calendar numbers. */
    static String math(MayorRoster r) {
        if (ElectionMath.votingDays() != 279) return "voting window is " + ElectionMath.votingDays() + " SB days, research says 279 (93 h)";
        if (ElectionMath.votingDays() * 20 / 60 != 93) return "voting window is not 93 real hours";
        if (!"jerry".equals(ElectionMath.specialFor(496, 8, r.specialRotation))) return "496 should be Gerald (Jerry)";
        if (!"scorpius".equals(ElectionMath.specialFor(504, 8, r.specialRotation))) return "504 should be Corvane (Scorpius)";
        if (!"derpy".equals(ElectionMath.specialFor(512, 8, r.specialRotation))) return "512 should be Bonko (Derpy)";
        if (!"jerry".equals(ElectionMath.specialFor(520, 8, r.specialRotation))) return "520 should be Gerald (Jerry)";
        if (ElectionMath.specialFor(517, 8, r.specialRotation) != null) return "517 should have no special";
        if (ElectionMath.termOf(517, 3, 26) != 516 || ElectionMath.termOf(517, 3, 27) != 517) return "term boundary is not Late Spring 27";
        if (ElectionMath.openElection(517, 6, 26) != -1 || ElectionMath.openElection(517, 6, 27) != 517 || ElectionMath.openElection(518, 3, 26) != 517)
            return "voting window is not Late Summer 27 - Late Spring 27";
        for (int e = 500; e < 530; e++) {
            List<ElectionMath.Candidate> c = ElectionMath.rollCandidates(r, e, 517, Set.of("aatrox", "cole"), null, 8, false);
            if (c.size() != ElectionMath.CANDIDATES) return "election " + e + " has " + c.size() + " candidates";
            Set<String> seen = new HashSet<>();
            for (ElectionMath.Candidate k : c) {
                if (!seen.add(k.mayor)) return "election " + e + " lists " + k.mayor + " twice";
                if (!k.special && (k.mayor.equals("aatrox") || k.mayor.equals("cole"))) return "election " + e + " lists an excluded mayor";
                if (!k.special && (k.perks.isEmpty() || k.perks.size() > 4)) return "election " + e + ": " + k.mayor + " runs with " + k.perks.size() + " perks";
                if (!k.special && !k.perks.contains(k.ministerPerk)) return "election " + e + ": minister perk not on the ballot";
                if (r.mayor(k.mayor).retired) return "retired mayor on the ballot";
            }
            if ((e % 8 == 0) != c.stream().anyMatch(k -> k.special)) return "election " + e + " special slot wrong";
        }
        // the Long Game: a carried mayor runs with every perk
        List<ElectionMath.Candidate> lti = ElectionMath.rollCandidates(r, 501, 517, Set.of(), "marina", 8, false);
        if (lti.stream().noneMatch(k -> k.mayor.equals("marina") && k.perks.size() == r.mayor("marina").perks.size()))
            return "Long Game carry did not put marina on the ballot with all perks";
        return null;
    }

    /**
     * Mayor/minister perks named by the real-game research (SkyblockMoney/work/{fishing,mining,combat}.md,
     * election.json Y516-Y518, NEU *_MAYOR_MONSTER lore): values, minister flags, the Fiesta rework.
     */
    static String referencePerks(MayorRoster r) {
        String[][] values = {
                {"marina.fishing_xp_buff", "50"}, {"marina.luck_of_the_sea", "15"}, {"marina.double_trouble", "0.1"},
                {"paul.marauder", "20"}, {"paul.benediction", "25"}, {"paul.ezpz", "10"},
                {"cole.prospection", "25"}, {"cole.mining_xp_buff", "60"}, {"cole.mining_fiesta", "25"},
                {"diana.huntress_intuition", "10"}, {"diana.pet_xp_buff", "35"}};
        for (String[] v : values) {
            MayorRoster.Perk p = r.perk(v[0]);
            if (p == null) return "perk " + v[0] + " is missing";
            if (Math.abs(p.value - Double.parseDouble(v[1])) > 1e-9) return v[0] + " is " + p.value + ", reference " + v[1];
        }
        // minister flags seen on real ballots (election.json): each was the flagged minister perk at least once
        for (String id : new String[]{"cole.mining_fiesta", "cole.mining_xp_buff", "marina.double_trouble", "marina.luck_of_the_sea",
                "paul.benediction", "foxy.extra_event", "foxy.chivalrous_carnival", "aatrox.pathfinder", "finnegan.pest_eradicator",
                "diaz.volume_trading", "diana.sharing_is_caring", "diana.huntress_intuition"})
            if (!r.perk(id).ministerEligible) return id + " must be minister-eligible (it was a real minister perk)";
        if (r.perk("diana.mythological_ritual").ministerEligible) return "a minister Diana must not run the Mythological Ritual";
        if (r.perk("diana.lucky") != null) return "Lucky! was replaced by Huntress' Intuition (NEU)";
        if (!"cole.mining_fiesta".equals(r.canonical("mining_fiesta"))) return "mining's dial id 'mining_fiesta' must resolve to Cole's perk";
        for (String l : r.perk("cole.mining_fiesta").lore) if (l.contains("+75") || l.contains("Five")) return "Fiesta lore still describes the pre-rework perk: " + l;
        if (!String.join(" ", r.perk("foxy.extra_event").lore).contains("{extra_event}")) return "Extra Event lore must name its rolled variant";
        // a ballot never makes a mayor-only perk the minister perk
        for (int e = 400; e < 600; e++) {
            for (ElectionMath.Candidate k : ElectionMath.rollCandidates(r, e, 517, Set.of(), null, 8, false)) {
                if (k.special) continue;
                MayorRoster.Perk mp = r.perk(k.ministerPerk);
                if (mp == null || !mp.ministerEligible) return "election " + e + ": " + k.mayor + " runs with minister perk " + k.ministerPerk;
            }
        }
        return null;
    }

    /** Foxy's Extra Event: the variant roll and the date window. */
    static String extraEvents() {
        if (!"fishing".equals(ElectionMath.extraEventVariant(1, 517, "Fishing"))) return "the variant dial must pin the roll";
        if (!"mining".equals(ElectionMath.extraEventVariant(1, 517, " mining "))) return "the variant dial must pin the roll";
        Set<String> seen = new HashSet<>();
        for (int e = 0; e < 60; e++) {
            String v = ElectionMath.extraEventVariant(517, e, "auto");
            if (!ElectionMath.EXTRA_EVENT_VARIANTS.contains(v)) return "auto rolled " + v;
            if (!v.equals(ElectionMath.extraEventVariant(517, e, "auto"))) return "the roll is not deterministic";
            seen.add(v);
        }
        if (seen.size() != 3) return "auto never rolls some variant: " + seen;
        // reference Y518: Late Summer 22-24
        if (ElectionMath.inWindow(6, 21, 6, 22, 3) || !ElectionMath.inWindow(6, 22, 6, 22, 3)
                || !ElectionMath.inWindow(6, 24, 6, 22, 3) || ElectionMath.inWindow(6, 25, 6, 22, 3))
            return "the extra festival window is not Late Summer 22-24";
        if (!ElectionMath.inWindow(1, 1, 12, 31, 2) || ElectionMath.inWindow(1, 2, 12, 31, 2)) return "the window must wrap the year end";
        return null;
    }

    /** research/05 §7.6: bracket thresholds pick the right bracket. */
    static String brackets(EventData d) {
        var b = d.spooky.brackets;
        if (b.isEmpty()) return "no spooky brackets";
        String r1 = id(EventData.bracketFor(b, 1, 5000, 1));
        String r60 = id(EventData.bracketFor(b, 60, 5000, 1));
        String d2k = id(EventData.bracketFor(b, 70, 2000, 1));
        String g = id(EventData.bracketFor(b, 70, 1999, 1));
        String i = id(EventData.bracketFor(b, 70, 1500, 1));
        if (!"top50".equals(r1) || !"emerald".equals(r60) || !"diamond".equals(d2k) || !"gold".equals(g) || !"iron".equals(i))
            return "brackets wrong: " + r1 + "/" + r60 + "/" + d2k + "/" + g + "/" + i;
        if (EventData.bracketFor(b, 5, 0, 1) != null) return "zero points earned a bracket";
        for (EventData.Bracket x : b) if (x.id.equals("diamond") && x.coins != 500_000) return "Diamond bracket must pay 500,000 (research)";
        return null;
    }

    private static String id(EventData.Bracket b) { return b == null ? "none" : b.id; }

    /** neu-repo / research numbers for the zoo and the Dark Auction. */
    static String prices(EventData d) {
        EventData.Offer whale = null, lion = null;
        for (EventData.Offer o : d.zoo.offers) {
            if ("BLUE_WHALE;4".equals(o.result)) whale = o;
            if ("LION;0".equals(o.result)) lion = o;
        }
        if (whale == null || whale.coins != 10_000_000 || !whale.cost.contains("ENCHANTED_COOKED_FISH:8"))
            return "Blue Whale LEGENDARY must cost 10,000,000 + 8 Enchanted Cooked Cod (research §7.9)";
        if (lion == null || lion.coins != 10_000) return "Lion COMMON must cost 10,000 coins";
        if (d.zoo.legendaryRotation.size() != 6) return "zoo legendary rotation must have 6 pets";
        if (d.darkAuction.entryPurse != 400_000 || d.darkAuction.rounds != 5 || d.darkAuction.bidSeconds != 10) return "Dark Auction rules differ from research §5.1";
        boolean midas = false;
        for (EventData.Lot l : d.darkAuction.lots) if (l.item.equals("MIDAS_SWORD") && l.minBid == 100_000) midas = true;
        if (!midas) return "Midas' Sword lot must start at 100,000";
        if (d.spooky.baseCandyChance != 0.10 || d.spooky.purplePoints != 5 || d.spooky.greenPoints != 1) return "candy rules differ from research §7.6";
        return null;
    }

    /** Mythological fight numbers (neu-repo bestiary pages): construct stages, ramp caps. */
    static String mythMechanics() {
        if (MythMechanics.stageOf(1.0) != 1 || MythMechanics.stageOf(0.67) != 1) return "construct should be stage 1 above 66% HP";
        if (MythMechanics.stageOf(0.5) != 2) return "construct should be stage 2 between 33% and 66% HP";
        if (MythMechanics.stageOf(0.2) != 3) return "construct should be stage 3 below 33% HP";
        if (MythMechanics.ramp(0, MythMechanics.INQUISITOR_RAMP) != 0) return "inquisitor ramp should start at 0";
        if (Math.abs(MythMechanics.ramp(20 * 10, MythMechanics.INQUISITOR_RAMP) - 0.6) > 1e-9) return "inquisitor should be +60% after 10 s";
        if (MythMechanics.ramp(20 * 100, MythMechanics.INQUISITOR_RAMP) < MythMechanics.RAMP_CAP - 1e-9) return "inquisitor should cap (+600%) at 100 s";
        if (MythMechanics.ramp(20 * 120, MythMechanics.CHAMPION_RAMP) >= MythMechanics.RAMP_CAP) return "champion capped too early (before ~171 s)";
        if (MythMechanics.ramp(20 * 200, MythMechanics.CHAMPION_RAMP) != MythMechanics.RAMP_CAP) return "champion ramp is not capped at +600%";
        if (!"lynx".equals(MythMechanics.kind(def("myth_lynx_3")))) return "kind(myth_lynx_3) should be lynx";
        if (!"inquisitor".equals(MythMechanics.kind(def("myth_inquisitor_3")))) return "kind(myth_inquisitor_3) should be inquisitor";
        if (!MythMechanics.kind(def("spooky_wraith")).isEmpty()) return "non-myth mobs must have no myth kind";
        return null;
    }

    private static com.skyclone.api.mob.MobDef def(String id) {
        com.skyclone.api.mob.MobDef d = new com.skyclone.api.mob.MobDef();
        d.id = id;
        return d;
    }

    /** research/05 fame ranks: a ballot is worth the votes of the voter's Fame Rank. */
    static String fameVotes() {
        List<FameVotes.Rank> t = List.of(new FameVotes.Rank("New Player", 0, 1), new FameVotes.Rank("Citizen", 80_000, 2),
                new FameVotes.Rank("Patron", 800_000, 10), new FameVotes.Rank("Senator", 33_000_000, 100));
        if (FameVotes.votesFor(0, t) != 1) return "no fame should be 1 vote";
        if (FameVotes.votesFor(79_999, t) != 1) return "just under Citizen should be 1 vote";
        if (FameVotes.votesFor(80_000, t) != 2) return "Citizen should be 2 votes";
        if (FameVotes.votesFor(1_000_000, t) != 10) return "Patron should be 10 votes";
        if (FameVotes.votesFor(500_000_000, t) != 100) return "Senator+ should be 100 votes";
        return null;
    }

    /**
     * The Carnival (wave 5, reference wiki numbers): 25 tickets a day, 5 a game, 1 token per 100 points (+4% a Party
     * piece), the token shop prices, the 49-square patch (34 fruit, 10 bombs, 5 rum), every fruit rule, the dowsing
     * modes, and scripted Zombie Shootout / Catch a Fish rounds, goals and perks.
     */
    static String carnival(EventData d) {
        EventData.Carnival c = d.carnival;
        if (c.ticketsPerDay != 25) return "tickets per day should be 25, not " + c.ticketsPerDay;
        if (c.gameCost != 5) return "a game should cost 5 tickets, not " + c.gameCost;
        if (c.digs != 15 || c.digSeconds != 300 || c.shootSeconds != 100 || c.fishSeconds != 100) return "digs/time limits are not 15 / 5 min / 100 s / 100 s";
        java.util.Map<String, Long> want = java.util.Map.of("CARNIVAL_MASK_BAG", 250L, "ZOMBIE_MASK", 500L, "SALMON_MASK", 500L,
                "ARMADILLO_MASK", 1000L, "PARROT_MASK", 1000L, "BEE_MASK", 2000L, "FROG_MASK", 2000L);
        if (c.shop.size() != want.size()) return "token shop should have " + want.size() + " lines, has " + c.shop.size();
        for (EventData.TokenOffer o : c.shop)
            if (!Long.valueOf(o.tokens).equals(want.get(o.result))) return o.result + " should cost " + want.get(o.result) + " tokens, not " + o.tokens;
        int cells = 0;
        for (int n : c.grid.values()) cells += n;
        if (cells != FruitDig.CELLS) return "patch mix fills " + cells + " squares, not " + FruitDig.CELLS;
        if (c.grid.getOrDefault("BOMB", 0) != 10 || c.grid.getOrDefault("RUM", 0) != 5) return "the patch should hide 10 bombs and 5 rum";
        String[] lay = FruitDig.layout(c.grid, 42);
        int fruit = 0;
        for (String x : lay) if (FruitDig.isFruit(x)) fruit++;
        if (fruit != 34) return "the patch should hold 34 fruit, holds " + fruit;
        // tokens: 1 per 100 points rounded down; Carnival Craze +4% a piece
        if (CarnivalRules.tokens(2345, c.tokensPerPoint, 0) != 23) return "2,345 points should be 23 tokens";
        if (CarnivalRules.tokens(5000, c.tokensPerPoint, 5) != 60) return "5,000 points with a full Party set should be 60 tokens";
        if (CarnivalRules.tokens(99, c.tokensPerPoint, 0) != 0) return "99 points should be 0 tokens";

        java.util.Map<String, Integer> pts = c.points;
        // hand-made patch, row 0 (north): APPLE APPLE CHERRY CHERRY POMEGRANATE MANGO COCONUT; row 1: BOMB RUM DURIAN DRAGONFRUIT ...
        String[] g0 = new String[FruitDig.CELLS];
        java.util.Arrays.fill(g0, "DIRT");
        String[] row0 = {"APPLE", "APPLE", "CHERRY", "CHERRY", "POMEGRANATE", "MANGO", "COCONUT"};
        System.arraycopy(row0, 0, g0, 0, row0.length);
        g0[7] = "BOMB"; g0[8] = "RUM"; g0[9] = "DURIAN"; g0[10] = "DRAGONFRUIT";
        FruitDig g = new FruitDig(g0.clone(), 15);
        g.dig(0, FruitDig.Mode.MINES, pts);
        g.dig(1, FruitDig.Mode.MINES, pts);
        if (g.score != 100 + 200) return "two apples should score 100 + 200, scored " + g.score;
        g.dig(2, FruitDig.Mode.MINES, pts);
        g.dig(3, FruitDig.Mode.MINES, pts);
        int afterCherries = 300 + 200 + 500;
        if (g.score != afterCherries) return "a cherry pair should add 200 + 500, total " + afterCherries + ", got " + g.score;
        g.dig(4, FruitDig.Mode.MINES, pts);
        int before = g.score;
        g.dig(5, FruitDig.Mode.MINES, pts);
        if (g.score - before != Math.round(300 * 1.5)) return "pomegranate should make the next dig x1.5";
        g.dig(6, FruitDig.Mode.MINES, pts);
        if (!g.shield) return "a coconut should guard the next dig";
        if (g.dig(6, FruitDig.Mode.MINES, pts) != null) return "a dug square must not be dug twice";
        FruitDig.Result boom = g.dig(7, FruitDig.Mode.MINES, pts);
        if (boom == null || g.bombs != 0 || g.over) return "the coconut should stop the bomb and the round goes on";
        // rum: the next dig does not dowse
        FruitDig.Result rum = g.dig(8, FruitDig.Mode.MINES, pts);
        FruitDig.Result drunk = g.dig(9, FruitDig.Mode.MINES, pts);
        if (rum == null || drunk == null || drunk.dowse() == null || drunk.dowse().contains("MINES")) return "rum should switch dowsing off for one dig";
        before = g.score;
        g.dig(10, FruitDig.Mode.MINES, pts);
        if (g.score - before != 600) return "durian should halve the next dig (dragonfruit 1,200 -> 600), got " + (g.score - before);
        if (!g.dragonfruit) return "dragonfruit not recorded";
        // a bomb without a coconut destroys the fruit around it
        String[] g1 = new String[FruitDig.CELLS];
        java.util.Arrays.fill(g1, "DIRT");
        g1[8] = "BOMB"; g1[0] = "MANGO"; g1[16] = "MANGO"; g1[20] = "MANGO";
        FruitDig b = new FruitDig(g1, 15);
        FruitDig.Result r1 = b.dig(8, FruitDig.Mode.TREASURE, pts);
        if (!b.destroyed[0] || !b.destroyed[16] || b.destroyed[20] || b.over) return "a bomb should destroy only the fruit touching it, and the round goes on";
        if (b.dig(0, FruitDig.Mode.MINES, pts) != null) return "a destroyed square cannot be dug";
        if (r1 == null || !r1.dowse().contains("no fruits")) return "treasure next to destroyed fruit should find none";
        // watermelon splashes the fruit around it for half
        String[] g2 = new String[FruitDig.CELLS];
        java.util.Arrays.fill(g2, "DIRT");
        g2[24] = "WATERMELON"; g2[16] = "MANGO"; g2[32] = "APPLE"; g2[25] = "BOMB";
        FruitDig w = new FruitDig(g2, 15);
        w.dig(24, FruitDig.Mode.MINES, pts);
        if (w.score != 100 + 150 + 50) return "watermelon 100 + half a mango 150 + half the first apple 50 = 300, got " + w.score;
        if (!w.dug[16] || !w.dug[32] || w.dug[25]) return "watermelon should open the fruit around it and leave the bomb";
        // dowsing modes
        String[] g3 = new String[FruitDig.CELLS];
        java.util.Arrays.fill(g3, "DIRT");
        g3[0] = "BOMB"; g3[2] = "BOMB"; g3[14] = "MANGO"; g3[16] = "APPLE"; g3[22] = "DRAGONFRUIT";
        FruitDig m = new FruitDig(g3, 15);
        FruitDig.Result mines = m.dig(8, FruitDig.Mode.MINES, pts);
        if (mines == null || !mines.dowse().contains("&c2 &7bombs")) return "mines should count the 2 bombs touching the square: " + (mines == null ? null : mines.dowse());
        FruitDig.Result treasure = m.dig(15, FruitDig.Mode.TREASURE, pts);
        if (treasure == null || !treasure.dowse().contains("Dragonfruit")) return "treasure should name the best fruit nearby";
        FruitDig.Result anchor = m.dig(9, FruitDig.Mode.ANCHOR, pts);
        if (anchor == null || anchor.anchorCell() != 16 || !anchor.dowse().contains("south")) return "anchor should point at the lowest fruit (the apple, south)";
        // out of digs
        FruitDig o = new FruitDig(g0.clone(), 1);
        o.dig(20, FruitDig.Mode.MINES, pts);
        if (!o.over) return "running out of digs should end the round";

        // Zombie Shootout: points, the lamp window, accuracy, the clock
        ShootRound s = new ShootRound(0, 100, 2, new double[]{16, 16}, 5, 7);
        s.step(1_000);
        if (!s.targets.isEmpty()) return "no zombie in the first second";
        s.step(1_600);
        if (s.targets.isEmpty()) return "a zombie should appear after 1.5 s";
        ShootRound.Target t = s.targets.get(0);
        s.fired();
        int p1 = s.hitZombie(t.id, 1_700);
        if (p1 != t.points()) return "a hit should score the zombie's points";
        for (ShootRound.Kind k : ShootRound.Kind.values()) {
            int[] exp = {30, 50, 80, 120};
            int[] life = {7000, 6000, 5000, 4000};
            if (k.points != exp[k.ordinal()] || k.lifeMs != life[k.ordinal()]) return k + " should be " + exp[k.ordinal()] + " points / " + life[k.ordinal()] + " ms";
        }
        s.lit = 2;
        s.fired();
        if (s.hitLamp(1, 2_000) != 0) return "only the lit lamp counts";
        s.fired();
        if (s.hitLamp(2, 2_000) != 100 || !s.boosted(7_900) || s.boosted(8_100)) return "a lit lamp is +100 and x1.2 for 6 s";
        ShootRound.Target t2 = new ShootRound.Target(999, ShootRound.Kind.DIAMOND, false, 0, 0.5, 1, 2_000);
        s.targets.add(t2);
        s.fired();
        if (s.hitZombie(999, 3_000) != 144 || s.diamonds != 1) return "a diamond zombie in the lamp window should be 120 x 1.2 = 144";
        if (s.shots != 4 || s.hits != 3 || Math.abs(s.accuracy() - 75) > 1e-9) return "accuracy should be hits / darts (3/4)";
        ShootRound.Target t3 = new ShootRound.Target(1000, ShootRound.Kind.GOLD, false, 0, 0.5, 1, 3_000);
        s.targets.add(t3);
        s.step(8_100);
        if (s.target(1000) != null) return "a gold zombie should leave after 5 s";
        s.step(100_001);
        if (!s.over || s.hitZombie(1, 100_002) != 0) return "the shootout should end after 100 s";

        // Catch a Fish: hold to reel, streak bonus, escapes, the clock
        FishRound f = new FishRound(0, 100, 3);
        f.step(300);
        if (!f.fish.isEmpty()) return "no fish in the first 0.6 s";
        f.fish.clear();
        f.nextSpawn = Long.MAX_VALUE;
        FishRound.Fish red = new FishRound.Fish(500, FishRound.Kind.RED, 50_000);
        f.fish.add(red);
        if (!f.hook(500, 1_000)) return "hooking a fish should work";
        FishRound.Pull last = null;
        for (int i = 0; i < FishRound.Kind.RED.pulls; i++) last = f.pull(1_000 + i * 200L);
        if (last == null || !last.caught() || f.caught != 1) return "a red fish should come in after " + FishRound.Kind.RED.pulls + " pulls";
        if (last.points() != 50 && last.points() != 100) return "the first red fish is worth 50 (or 100 doubled), got " + last.points();
        FishRound.Fish blue = new FishRound.Fish(501, FishRound.Kind.BLUE, 50_000);
        f.fish.add(blue);
        f.hook(501, 3_000);
        for (int i = 0; i < FishRound.Kind.BLUE.pulls; i++) last = f.pull(3_000 + i * 200L);
        if (f.streak != 2 || (last.points() != 105 && last.points() != 210)) return "the second catch in a row is +5, got " + last.points();
        FishRound.Fish yel = new FishRound.Fish(502, FishRound.Kind.YELLOW, 50_000);
        f.fish.add(yel);
        f.hook(502, 6_000);
        f.pull(6_100);
        f.step(6_100 + FishRound.ESCAPE_MS + 50);
        if (f.hooked() != null || f.streak != 0) return "a fish left unreeled should escape and break the streak";
        if (FishRound.Kind.RED.pulls >= FishRound.Kind.BLUE.pulls || FishRound.Kind.BLUE.pulls >= FishRound.Kind.YELLOW.pulls)
            return "red should reel in fastest and yellow slowest";
        f.step(100_001);
        if (!f.over || f.hook(502, 100_002)) return "the pond should close after 100 s";

        // goals: 15, five per booth, 50/100 tokens + 5 tickets; plays count across games
        if (c.goals.size() != 15) return "there should be 15 goals, not " + c.goals.size();
        java.util.Map<String, Integer> stats = new java.util.HashMap<>(java.util.Map.of("score", 3100, "apples", 3, "unique", 4, "dragonfruit", 0));
        java.util.List<EventData.Goal> met = CarnivalRules.newlyMet(c.goals, "FRUIT", stats, 1, new java.util.ArrayList<>());
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (EventData.Goal x : met) ids.add(x.id);
        if (!ids.equals(java.util.Set.of("fruit_score", "fruit_apples"))) return "a 3,100 game with 3 apples should meet score + apples, met " + ids;
        if (CarnivalRules.newlyMet(c.goals, "FRUIT", stats, 3, new java.util.ArrayList<>(java.util.List.of("fruit_score", "fruit_apples"))).size() != 1)
            return "the third game should meet only the plays goal";
        for (EventData.Goal x : c.goals) if (x.tickets != 5 || (x.tokens != 50 && x.tokens != 100)) return x.id + " should pay 50/100 tokens and 5 tickets";

        // perks: neu-repo tiers and costs, value per tier
        if (c.perks.size() != 24) return "there should be 24 Carnival Perks, not " + c.perks.size();
        EventData.Perk cs = c.perk("candy_scavenger");
        if (cs == null || cs.costs.size() != 5 || cs.costs.get(4) != 250 || CarnivalRules.value(cs, 3) != 6) return "Candy Scavenger: 5 tiers to 250 tokens, +2% each";
        EventData.Perk toe = c.perk("trick_or_treat_enthusiast");
        if (toe == null || toe.costs.size() != 2 || toe.costs.get(1) != 1000 || CarnivalRules.nextCost(toe, 2) != -1) return "Trick or Treat Enthusiast: 100 / 1,000 tokens then maxed";
        if (CarnivalRules.tier(cs, java.util.Map.of("candy_scavenger", 99)) != 5) return "a stored tier is clamped to the perk's tiers";
        return null;
    }

    /**
     * Playtest endgame #9/#10: Dark Auction round news follows presence (a player who stepped out and warped away
     * heard every later round), and the Spooky sidebar names its candy counts (green/purple told apart only by colour).
     */
    static String chatAndSidebar() {
        if (!DarkAuction.inRoom(true, false) || !DarkAuction.inRoom(false, true)) return "the auction menu or the hall should count as in the room";
        if (DarkAuction.inRoom(false, false)) return "a player out of the menu and out of the hall still hears round news";
        java.util.List<String> l = SpookyFestival.sidebarLines(5, 1, 10);
        String all = String.join("\n", l).replaceAll("&.", "");
        if (!all.contains("5 Green") || !all.contains("1 Purple") || !all.contains("Points: 10"))
            return "the spooky sidebar should name each count (got " + all.replace("\n", " | ") + ")";
        return null;
    }

    /**
     * Calendar chat (playtest wave 3: every player got two lines for every event, the Dark Auction's every hour):
     * who hears what under the default rules. Plays the AI player's case - a fresh profile with no coins - first.
     */
    static String alerts() {
        AlertPolicy.Rules r = new AlertPolicy.Rules(AlertPolicy.ids(AlertPolicy.DEFAULT_IMPORTANT),
                AlertPolicy.ids(AlertPolicy.DEFAULT_OWNER_ANNOUNCED), AlertPolicy.gates(AlertPolicy.DEFAULT_PERK_GATES), 400_000, 0.5);
        AlertPolicy.Kind R = AlertPolicy.Kind.REMIND, S = AlertPolicy.Kind.START;
        AlertPolicy.Line NONE = AlertPolicy.Line.NONE, PLAIN = AlertPolicy.Line.PLAIN;
        AlertPolicy.Scope IMP = AlertPolicy.Scope.IMPORTANT, ALL = AlertPolicy.Scope.ALL, OFF = AlertPolicy.Scope.OFF;

        if (AlertPolicy.Scope.parse(null) != IMP || AlertPolicy.Scope.parse("garbage") != IMP) return "a missing/unknown scope must read as important";
        if (AlertPolicy.Scope.parse("ALL") != ALL || AlertPolicy.Scope.parseStrict("Off") != OFF || AlertPolicy.Scope.parseStrict("x") != null) return "scope parsing";
        if (IMP.next() != ALL || ALL.next() != OFF || OFF.next() != IMP) return "the board button must cycle important -> all -> off -> important";

        // a fresh (AI) player: 0 coins, menus' toggles at their defaults
        AlertPolicy.Prefs fresh = new AlertPolicy.Prefs(IMP, true, true, 0);
        for (String quiet : new String[]{Cal.DARK_AUCTION, "spiders_den_rain", "spiders_den_thunder", "farming_contest", "crimson_volcano_eruption", Cal.WORKSHOP})
            for (AlertPolicy.Kind k : AlertPolicy.Kind.values())
                if (AlertPolicy.decide(quiet, k, true, fresh, r) != NONE) return quiet + " " + k + " reaches a fresh player on 'important'";
        for (String loud : new String[]{Cal.SPOOKY, Cal.NEW_YEAR, Cal.ZOO_SUMMER, Cal.ZOO_WINTER, Cal.SEASON_OF_JERRY, Cal.ELECTION, Cal.TERM_START, "progression_century_celebration"})
            if (AlertPolicy.decide(loud, R, true, fresh, r) != PLAIN) return loud + " reminder should reach a fresh player";
        // festivals announce themselves on start: no second, generic line
        for (String own : new String[]{Cal.SPOOKY, Cal.NEW_YEAR, Cal.ZOO_SUMMER, Cal.SEASON_OF_JERRY, Cal.ELECTION, Cal.TERM_START, Cal.CARNIVAL, Cal.MYTH, Cal.DARK_AUCTION}) {
            if (AlertPolicy.decide(own, S, true, fresh, r) != NONE) return own + " gets a generic start line on top of its own announcement";
            if (!Cal.DARK_AUCTION.equals(own) && !AlertPolicy.ownLine(own, true, fresh, r)) return own + "'s own announcement is filtered out on 'important'";
        }
        // the Dark Auction: never the coin requirement to someone far below it
        if (AlertPolicy.decide(Cal.DARK_AUCTION, R, true, new AlertPolicy.Prefs(IMP, true, true, 400_000), r) != AlertPolicy.Line.DA_OPEN) return "a player at the entry purse should be told to come";
        if (AlertPolicy.decide(Cal.DARK_AUCTION, R, true, new AlertPolicy.Prefs(IMP, true, true, 250_000), r) != AlertPolicy.Line.DA_SHORT) return "62% of the purse is within reach: remind with the shortfall";
        if (AlertPolicy.decide(Cal.DARK_AUCTION, R, true, new AlertPolicy.Prefs(IMP, true, true, 150_000), r) != NONE) return "37% of the purse is far below: say nothing";
        AlertPolicy.Line poorAll = AlertPolicy.decide(Cal.DARK_AUCTION, R, true, new AlertPolicy.Prefs(ALL, true, true, 0), r);
        if (poorAll != PLAIN) return "'all' with no coins should get the bare Dark Auction reminder (no requirement), got " + poorAll;
        if (AlertPolicy.withinReach(399_999, 400_000, 0) || AlertPolicy.withinReach(400_000, 400_000, 0.5)) return "withinReach edges";
        // 'all' is the old behaviour, minus repeats
        if (AlertPolicy.decide("spiders_den_rain", R, true, new AlertPolicy.Prefs(ALL, true, true, 0), r) != PLAIN) return "'all' should hear Spider's Den rain";
        if (AlertPolicy.decide("spiders_den_rain", S, true, new AlertPolicy.Prefs(ALL, true, true, 0), r) != PLAIN) return "'all' should hear the rain start";
        // perk gate: the fishing festival is registered every month and only runs under its perk
        if (AlertPolicy.decide("fishing_festival", R, false, new AlertPolicy.Prefs(ALL, true, true, 0), r) != NONE) return "a fishing festival without its perk was announced";
        if (AlertPolicy.decide("fishing_festival", S, true, fresh, r) != PLAIN) return "a real fishing festival should announce its start";
        // off, and menus' own toggles
        AlertPolicy.Prefs off = new AlertPolicy.Prefs(OFF, true, true, 1e12);
        for (String id : new String[]{Cal.SPOOKY, Cal.DARK_AUCTION, "fishing_festival"})
            for (AlertPolicy.Kind k : AlertPolicy.Kind.values()) if (AlertPolicy.decide(id, k, true, off, r) != NONE) return "'off' still announced " + id;
        if (AlertPolicy.ownLine(Cal.SPOOKY, true, off, r)) return "'off' still gets the Spooky Festival's own line";
        if (AlertPolicy.decide(Cal.SPOOKY, R, true, new AlertPolicy.Prefs(IMP, false, true, 0), r) != NONE) return "menus' reminder toggle is ignored";
        if (AlertPolicy.ownLine(Cal.SPOOKY, true, new AlertPolicy.Prefs(IMP, true, false, 0), r)) return "menus' start-message toggle is ignored";
        // parsing of the dials
        if (!"marina.fishing_festival".equals(AlertPolicy.gates(" fishing_festival = marina.fishing_festival ,bad,=x,y=").get("fishing_festival"))
                || AlertPolicy.gates("bad,=x,y=").size() != 0) return "perk-gate parsing";
        if (!AlertPolicy.ids(" Spooky_Festival ,, carnival").equals(java.util.Set.of("spooky_festival", "carnival"))) return "id-list parsing";
        return null;
    }

    /**
     * Wave 5, the Mythological Ritual's numbers (reference wiki): chains 4 / 6 / 8 by spade, +2 Erudite, max 10; each
     * Griffin rarity opens two creatures with weights 1000 / 800 / 600 / 400 / 75 / 15; Tracking tilts only the
     * Legendary-and-up creatures; the first burrow is treasure; Crown of Greed x2; the Griffin rule; the riddles.
     */
    static String mythRules(EventData d) {
        EventData.Myth m = d.myth;
        if (MythRules.chainLength(m, "ANCESTRAL_SPADE", false) != 4 || MythRules.chainLength(m, "ARCHAIC_SPADE", false) != 6
                || MythRules.chainLength(m, "DEIFIC_SPADE", false) != 8) return "spade chains should be 4 / 6 / 8";
        if (MythRules.chainLength(m, "ANCESTRAL_SPADE", true) != 6 || MythRules.chainLength(m, "DEIFIC_SPADE", true) != 10) return "Erudite should add 2 burrows";
        EventData.Myth capped = new EventData.Myth();
        capped.chainBySpade.put("DEIFIC_SPADE", 9);
        if (MythRules.chainLength(capped, "DEIFIC_SPADE", true) != 10) return "a chain is capped at 10";
        if (MythRules.chainLength(m, null, false) != 4) return "without a spade the chain falls back to 4";
        if (Math.abs(m.mobChance - 0.75) > 1e-9) return "75% of burrows should hold a creature, not " + m.mobChance;
        String[][] opened = {{"hunter", "lynx"}, {"nymph", "bull"}, {"harpy", "gaia"}, {"minotaur", "champion"}, {"sphinx", "inquisitor"}, {"manticore", "minos"}};
        int[] weight = {1000, 800, 600, 400, 75, 15};
        for (int r = 0; r < 6; r++) {
            java.util.Map<String, Integer> pool = MythRules.pool(m, r, 0);
            if (pool.size() != 2 * (r + 1)) return "rarity " + r + " should open " + 2 * (r + 1) + " creatures, opens " + pool.keySet();
            for (int t = 0; t <= r; t++)
                for (String k : opened[t]) if (!Integer.valueOf(weight[t]).equals(pool.get(k))) return k + " should weigh " + weight[t] + " at rarity " + r;
            if (r < 5 && pool.containsKey("minos")) return "the Labyrinth King needs a Mythic Griffin";
        }
        java.util.Map<String, Integer> tracked = MythRules.pool(m, 5, 100);
        if (tracked.get("minos") != 30 || tracked.get("inquisitor") != 150 || tracked.get("hunter") != 1000) return "100 Tracking should double only the rare creatures: " + tracked;
        if (MythRules.variant(0) != 1 || MythRules.variant(5) != 6 || MythRules.variant(9) != 6) return "variant = rarity + 1, max 6";
        if (MythRules.treasureCoins(10_000, false, 0) != 10_000 || MythRules.treasureCoins(10_000, true, 0) != 20_000
                || MythRules.treasureCoins(10_000, true, 10) != 22_000) return "Crown of Greed x2 then Deadly Greed +%";
        if (MythRules.creatureBurrow(true, 1.0, 0.0)) return "the first burrow of a chain is always treasure";
        if (!MythRules.creatureBurrow(false, 0.75, 0.74) || MythRules.creatureBurrow(false, 0.75, 0.75)) return "a later burrow holds a creature 75% of the time";
        if (MythRules.echoCooldown("ANCESTRAL_SPADE") != 3 || MythRules.echoCooldown("DEIFIC_SPADE") != 1) return "Echo cooldown 3 s / 2 s / 1 s";
        java.util.Random rnd = new java.util.Random(5);
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < 20_000; i++) seen.merge(MythRules.pick(MythRules.pool(m, 5, 0), rnd), 1, Integer::sum);
        double minosShare = seen.getOrDefault("minos", 0) / 20_000.0, expect = 15.0 / (2 * (1000 + 800 + 600 + 400 + 75 + 15));
        if (Math.abs(minosShare - expect) > expect * 0.6) return "the King should come out about " + expect + " of the time, came " + minosShare;
        if (MythMechanics.hurtAllowed("lynx", false, true) || !MythMechanics.hurtAllowed("hunter", false, true)
                || !MythMechanics.hurtAllowed("lynx", true, true) || !MythMechanics.hurtAllowed("lynx", false, false))
            return "only the Hunter may be hurt without a Griffin (when the rule is on)";
        for (SphinxRiddles.Riddle q : SphinxRiddles.ALL) {
            java.util.Set<String> a = new java.util.HashSet<>(q.shuffled(1));
            if (a.size() != 3 || !a.contains(q.answer())) return "riddle '" + q.question() + "' needs three different answers incl. the right one";
            if (!SphinxRiddles.correct(q, q.answer()) || SphinxRiddles.correct(q, q.wrong1())) return "riddle grading";
        }
        return null;
    }

    /**
     * Wave 5, the siege and the gifts: a round's attackers split over waves with every count kept and the boss last;
     * the next wave comes early on a cleared field; Present Connoisseur / Snow set / Reindeer extra gifts; Red Fad;
     * gift talismans and Professional Gifter scale coins and skill XP.
     */
    static String siegeRules(EventData d) {
        EventData.Siege s = d.winter.siege;
        if (s.rounds != 5 || s.waves.size() != 5) return "the siege should have 5 rounds (research §7.7)";
        java.util.Set<String> bosses = java.util.Set.of("siege_lava_behemoth");
        for (java.util.Map<String, Integer> round : s.waves) {
            java.util.List<java.util.Map<String, Integer>> w = SiegeRules.split(round, 4, bosses);
            java.util.Map<String, Integer> sum = new java.util.HashMap<>();
            for (java.util.Map<String, Integer> x : w) x.forEach((k, v) -> sum.merge(k, v, Integer::sum));
            java.util.Map<String, Integer> want = new java.util.HashMap<>(round);
            want.values().removeIf(v -> v == 0);
            if (!sum.equals(want)) return "splitting a round lost attackers: " + round + " -> " + w;
            for (int i = 0; i < w.size() - 1; i++) if (w.get(i).containsKey("siege_lava_behemoth")) return "the boss must come with the last wave";
        }
        java.util.List<java.util.Map<String, Integer>> w = SiegeRules.split(java.util.Map.of("a", 10), 4, java.util.Set.of());
        if (w.size() != 4 || w.get(0).get("a") != 2 || w.get(3).get("a") != 3) return "10 over 4 waves should be 2,2,3,3 (pressure rises)";
        if (SiegeRules.split(java.util.Map.of("a", 2), 4, java.util.Set.of()).size() != 2) return "empty waves are dropped";
        if (!SiegeRules.nextWaveDue(2, 8, 0, 1000) || SiegeRules.nextWaveDue(3, 8, 0, 1000) || !SiegeRules.nextWaveDue(8, 8, 1000, 1000))
            return "next wave: field down to a quarter, or the timer";
        if (SiegeRules.giftCount(0, 0.0) != 1 || SiegeRules.giftCount(30, 0.29) != 2 || SiegeRules.giftCount(30, 0.31) != 1
                || SiegeRules.giftCount(150, 0.6) != 2 || SiegeRules.giftCount(150, 0.4) != 3) return "extra gifts: whole 100%s plus a roll at the rest";
        if (!"RED_GIFT".equals(SiegeRules.upgradeToRed("WHITE_GIFT", false, 3, 0.02)) || !"WHITE_GIFT".equals(SiegeRules.upgradeToRed("WHITE_GIFT", true, 100, 0.0))
                || !"GREEN_GIFT".equals(SiegeRules.upgradeToRed("GREEN_GIFT", false, 3, 0.05))) return "Red Fad upgrades only when Reds are allowed";
        if (SiegeRules.giftScaled(1000, 25, 10) != 1350 || SiegeRules.giftScaled(1000, 0, 0) != 1000) return "gift talisman + gifter scale coins and XP";
        for (String g : new String[]{"WHITE_GIFT", "GREEN_GIFT", "RED_GIFT"}) {
            EventData.Bag b = d.gifts.get(g);
            if (b == null || b.skillXp[1] <= 0 || b.coins[1] <= 0) return g + " should give coins and skill XP";
        }
        return null;
    }

    /** Wave 5: the four mask abilities that used to be lore, and the Spooky perks/hooves. */
    static String masksAndSpooky() {
        if (CarnivalMasks.intimidationTier(id -> false) != 0) return "no Intimidation accessory = no Party Flock";
        if (CarnivalMasks.intimidationTier(id -> id.equals("INTIMIDATION_RING") || id.equals("INTIMIDATION_TALISMAN")) != 2) return "the best Intimidation piece counts (Ring = 2)";
        if (CarnivalMasks.intimidationTier(id -> id.equals("INTIMIDATION_RELIC")) != 4) return "the Relic is upgrade 4";
        String words = "ZOMBIE,SKELETON,REVENANT";
        if (!CarnivalMasks.undead("REVENANT_CHESTPLATE", words) || !CarnivalMasks.undead("ZOMBIE_KNIGHT_BOOTS", words) || CarnivalMasks.undead("FARM_SUIT_BOOTS", words))
            return "undead armor matching";
        List<String> regions = List.of("birch", "spruce", "dark", "savanna", "jungle");
        String a = CarnivalMasks.frogRegion(regions, 300, 4, 12), b = CarnivalMasks.frogRegion(regions, 300, 4, 12);
        if (a == null || !a.equals(b)) return "the Frog Mask region must hold for the whole day";
        Set<String> days = new HashSet<>();
        for (int day = 1; day <= 31; day++) days.add(CarnivalMasks.frogRegion(regions, 300, 4, day));
        if (days.size() < 3) return "the Frog Mask region should move between days, saw " + days;
        if (Math.abs(CarnivalMasks.lerp(5.25, 30, 100) - 30) > 1e-9 || Math.abs(CarnivalMasks.lerp(5.25, 30, 1) - 5.25) > 1e-9) return "Bee pet curve";
        if (Math.abs(SpookyFestival.trickChance(0.01, false, false, 0) - 0.01) > 1e-12) return "trick-or-treat base 1%";
        if (Math.abs(SpookyFestival.trickChance(0.01, true, true, 1.0) - 0.04) > 1e-12) return "Eternal Hoof (+2%, not stacking with Lucky) + Enthusiast II (+1%) = 4%";
        if (Math.abs(SpookyFestival.trickChance(0.03, true, false, 0) - 0.04) > 1e-12) return "Lucky Hoof +1% on a spooky mob = 4%";
        if (Math.abs(SpookyFestival.purpleShare(0.10, 3) - 0.13) > 1e-12) return "Purple Vogue III should make 13% of candy Purple";
        return null;
    }
}
