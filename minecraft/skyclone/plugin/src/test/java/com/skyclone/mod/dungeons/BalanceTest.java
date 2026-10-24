package com.skyclone.mod.dungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.mod.dungeons.boss.GrinkoFight;
import com.skyclone.mod.dungeons.boss.LecturerFight;
import com.skyclone.mod.dungeons.boss.VeilwrightFight;
import com.skyclone.mod.dungeons.gen.arena.TombArena;
import com.skyclone.mod.dungeons.model.Balance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixer (integrate-w2-1, "F1 cannot be survived by a mid-game player"): F1 at the DEFAULT dials, against the
 * integration pass's own character (588 HP / 630 Defense), with the shipping data files and the hit sizes the Grinko
 * fight actually uses. Each floor below is "this many hits from full health", so a later retune that makes F1 a
 * one- or two-shot again fails here instead of in a playtest.
 */
class BalanceTest {

    static final Map<String, Double> DMG = new HashMap<>();
    static final Map<String, Double> HP = new HashMap<>();
    static final Map<String, String> BEHAVIOUR = new HashMap<>();
    static final Map<String, JsonObject> FLOORS = new HashMap<>();
    static JsonObject f1;

    @BeforeAll
    static void load() throws Exception {
        for (String f : new String[]{"data/dungeons/mobs.json", "data/dungeons/boss_mobs.json"})
            for (JsonElement e : read(f).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                String id = o.get("id").getAsString();
                DMG.put(id, o.has("damage") ? o.get("damage").getAsDouble() : 0);
                HP.put(id, o.has("health") ? o.get("health").getAsDouble() : 0);
                JsonArray b = o.has("behaviours") ? o.getAsJsonArray("behaviours") : null;
                if (b != null && !b.isEmpty()) BEHAVIOUR.put(id, b.get(0).getAsString());
            }
        for (JsonElement e : read("data/dungeons/floors.json").getAsJsonArray())
            FLOORS.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        f1 = FLOORS.get("F1");
        assertNotNull(f1, "floors.json has F1");
    }

    static JsonElement read(String res) throws Exception {
        try (InputStreamReader r = new InputStreamReader(BalanceTest.class.getClassLoader().getResourceAsStream(res), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r);
        }
    }

    static int hits(double raw) { return Balance.hitsToKill(raw, Balance.MID); }

    @Test
    void arithmeticMatchesCore() {
        // core DamageMath.reduce: raw * 100/(100+def), floored by damagePlayer
        assertEquals(364, Balance.landed(2664, 630));
        assertEquals(2, Balance.hitsToKill(2664, 588, 630));
        assertEquals(Integer.MAX_VALUE, Balance.hitsToKill(0, 588, 630));
    }

    @Test
    void f1BossIsSurvivableAtDefaults() {
        double bossDamage = f1.get("bossDamage").getAsDouble();
        assertEquals(2664, bossDamage, "research F1 headline damage");
        double body = Balance.bossBody(bossDamage, DDials.DEF_BOSS_MELEE_SHARE, DDials.DEF_BOSS_DMG);
        // before the fix the body swung the full headline number: two swings from full health
        assertTrue(hits(Balance.bossBody(bossDamage, 1.0, 1.0)) <= 2);
        assertTrue(hits(body) >= 5, "Grinko's body: " + hits(body) + " hits");
        for (double f : new double[]{GrinkoFight.SKULL, GrinkoFight.SKULL_P2, GrinkoFight.BARRAGE}) {
            double raw = Balance.scripted(bossDamage, f, DDials.DEF_BOSS_DMG);
            assertTrue(hits(raw) >= 2, "a Grinko attack (x" + f + ") must not one-shot: " + Balance.landed(raw, 630));
        }
        double undead = Balance.addContact(GrinkoFight.UNDEAD_DMG, DDials.DEF_ADD_MELEE_SHARE, DDials.DEF_BOSS_DMG);
        assertTrue(hits(undead) >= 3, "Circus Undead: " + hits(undead) + " hits");
    }

    // ---- solo boss floors (wave-4 endgame) -----------------------------------------------------------------------
    //
    // The playtest character: Superior Dragon set (983 HP / 741 Defense), a Highlord with Wither Impact. Its DPS is the
    // measured one: Grinko's two lives (2 x 250,000) fell to Wither Impact in ~50 s. Solo it died on F1 in 8 s in melee,
    // on F2 in ~10 s three times, and lost 30% of its health within 2 s of arriving on F3.

    static final Balance.Ref SOLO = new Balance.Ref("solo Superior Dragon (983 HP / 741 Def)", 983, 741, 500_000 / 50.0);

    @Test
    void partyScaleArithmetic() {
        assertEquals(0.3, Balance.partyScale(1, 0.3), 1e-9);
        assertEquals(1.0, Balance.partyScale(5, 0.3), 1e-9);
        assertEquals(1.0, Balance.partyScale(9, 0.3), 1e-9);
        assertEquals(0.3, Balance.partyScale(0, 0.3), 1e-9);
        for (int n = 1; n < 5; n++) assertTrue(Balance.partyScale(n, 0.3) < Balance.partyScale(n + 1, 0.3), "rises with the party");
        assertEquals(1.0, Balance.partyScale(3, 1.0), 1e-9, "solo 1.0 = unchanged for every party size");
        assertEquals(10, Balance.guardianWindowSeconds(5, 10), 1e-9, "a full party keeps the research 10 s");
        assertTrue(Balance.guardianWindowSeconds(1, 10) > Balance.guardianWindowSeconds(2, 10));
        assertEquals(2, GrinkoFight.undeadCap(false, 1));
        assertEquals(4, GrinkoFight.undeadCap(false, 5));
        assertEquals(6, GrinkoFight.undeadCap(true, 5));
    }

    /** What lands per second if everything the opening of a boss floor puts on the field reaches one player at once. */
    static double openingPerSecond(String fid, double partyDmg, double addShare, int undead, Balance.Ref p) {
        JsonObject f = FLOORS.get(fid);
        double bd = f.get("bossDamage").getAsDouble();
        double s = 0;
        switch (fid) {
            case "F1" -> {
                s += Balance.landed(Balance.bossBody(bd, DDials.DEF_BOSS_MELEE_SHARE, DDials.DEF_BOSS_DMG) * partyDmg, p.defense());
                s += Balance.landed(bd * GrinkoFight.SKULL * partyDmg, p.defense()) / (GrinkoFight.SKULL_EVERY / 4.0);
                s += undead * Balance.landed(Balance.addContact(GrinkoFight.UNDEAD_DMG, addShare, 1) * partyDmg, p.defense());
            }
            case "F2" -> {
                for (String k : new String[]{"warrior", "archer", "mage", "priest"})
                    s += Balance.landed(Balance.addContact(DMG.get("dboss_veil_" + k), addShare, 1) * partyDmg, p.defense());
                s += Balance.landed(DMG.get("dboss_veil_archer") * partyDmg, p.defense()) / (VeilwrightFight.ARROW_EVERY / 4.0);
                s += Balance.landed(DMG.get("dboss_veil_warrior") * VeilwrightFight.CHARGE_FACTOR * partyDmg, p.defense()) / (VeilwrightFight.CHARGE_EVERY / 4.0);
                // the Mage's fire circles are telegraphed rings: not counted
            }
            case "F3" -> {
                for (String k : TombArena.GUARDIANS)
                    s += Balance.landed(Balance.addContact(1_500, addShare, 1) * partyDmg, p.defense());
                s += LecturerFight.LASER_TRUE * partyDmg;                      // true damage: Defense does not apply
            }
            default -> throw new IllegalArgumentException(fid);
        }
        return s;
    }

    /**
     * The arrival: with every add, bolt and beam of the opening on him at once (no dodging, no healing, no Wither
     * Shield), the solo player lasts long enough to act. At the old numbers (solo = party content, full-figure add
     * swings, four undead) the same model gives the playtest's deaths: well under two seconds.
     */
    @Test
    void soloArrivalIsSurvivableAtDefaults() {
        int undead = GrinkoFight.undeadCap(false, 1);
        for (String fid : new String[]{"F1", "F2", "F3"}) {
            double now = SOLO.health() / openingPerSecond(fid, Balance.partyScale(1, DDials.DEF_SOLO_BOSS_DMG), DDials.DEF_ADD_MELEE_SHARE, undead, SOLO);
            double old = SOLO.health() / openingPerSecond(fid, 1.0, 1.0, 4, SOLO);
            System.out.println("[balance] solo " + fid + " opening: lasts " + Math.round(now * 10) / 10.0 + " s (was " + Math.round(old * 10) / 10.0 + " s)");
            assertTrue(now >= 5, fid + ": the solo player lasts " + now + " s with the whole opening on him");
            assertTrue(old < 1.5, fid + ": the old numbers must fail this check (" + old + " s)");
            assertTrue(DDials.DEF_ARRIVAL_GRACE > 0, "an arrival grace exists");
        }
    }

    /**
     * The kill: health the solo player must take off each floor at the default solo scale, at the measured Wither
     * Impact rate. F1 at full health took the playtest ~50 s (this model says 50); F2 and F3 at full party health would
     * take 4.8 and 12 minutes solo.
     */
    @Test
    void soloKillTimeIsBounded() {
        double h = Balance.partyScale(1, DDials.DEF_SOLO_BOSS_HP);
        double f1 = 2 * FLOORS.get("F1").get("bossHp").getAsDouble();
        double f2 = FLOORS.get("F2").get("bossHp").getAsDouble();
        for (String k : new String[]{"warrior", "archer", "mage", "priest"}) f2 += HP.get("dboss_veil_" + k);
        double f3 = FLOORS.get("F3").get("bossHp").getAsDouble(), guards = 0, smallest = Double.MAX_VALUE;
        for (String k : TombArena.GUARDIANS) {
            double g = FLOORS.get("F3").get("bossHp").getAsDouble() * (k.equals("reinforced") ? 0.5 : 0.3);
            guards += g;
            smallest = Math.min(smallest, g);
        }
        f3 += guards;
        assertEquals(50, f1 / SOLO.dps(), 1, "calibration: F1 at full health = the playtest's 50 s");
        for (double hpTotal : new double[]{f1, f2, f3}) {
            double secs = hpTotal * h / SOLO.dps();
            assertTrue(secs <= 240, "a solo boss floor takes " + Math.round(secs) + " s of Wither Impact");
        }
        assertTrue(f2 / SOLO.dps() > 240, "F2 at party health was out of reach solo");
        // F3: after the first guardian falls the other three must be dead before it rises. Solo they die one after
        // another, so the solo window must cover killing the three biggest; a full party keeps the research 10 s
        double three = (guards - smallest) * h / SOLO.dps();
        assertTrue(Balance.guardianWindowSeconds(1, LecturerFight.WINDOW_SECONDS) >= three,
                "solo guardian window " + Balance.guardianWindowSeconds(1, LecturerFight.WINDOW_SECONDS) + " s vs " + three + " s of kills");
        assertTrue(Balance.guardianWindowSeconds(1, LecturerFight.WINDOW_SECONDS) > LecturerFight.WINDOW_SECONDS);
    }

    /**
     * wave-5 regression: a solo Superior Dragon + Undead Sword (Smite V) player was killed in F1 room 2 (the last hit a
     * starred Crypt Souleater's). Nothing modelled a ROOM: every mob of a 2-cell room aggroed at once (vanilla follow
     * range 35 over 32-block cells) and each took ~7 s of his 320 DPS. Now a pack is about one cell's worth
     * ({@code dungeons.room-aggro-range} 12) and a solo run's room numbers scale like the boss floor's.
     */
    @Test
    void f1RoomPacksAreSurvivableSolo() {
        double dScale = f1.get("dmgScale").getAsDouble(), hScale = f1.get("hpScale").getAsDouble();
        int pack = f1.get("mobsPerCell").getAsInt();
        double raw = 0, hp = 0, worstRaw = 0, worstHp = 0;
        JsonArray pool = f1.getAsJsonArray("mobPool");
        for (JsonElement e : pool) {
            String id = e.getAsString();
            raw += DMG.get(id) / pool.size();
            hp += HP.get(id) / pool.size();
            if (DMG.get(id) * HP.get(id) > worstRaw * worstHp) { worstRaw = DMG.get(id); worstHp = HP.get(id); }
        }
        Balance.Ref p = Balance.EARLY;
        double sd = Balance.partyScale(1, DDials.DEF_SOLO_ROOM_DMG), sh = Balance.partyScale(1, DDials.DEF_SOLO_ROOM_HP);
        double avg = Balance.packTaken(pack, Balance.roomMob(raw, dScale, DDials.DEF_MOB_DMG) * sd, hp * hScale * sh, p);
        double worst = Balance.packTaken(pack, Balance.roomMob(worstRaw, dScale, DDials.DEF_MOB_DMG) * sd, worstHp * hScale * sh, p);
        double room = Balance.packTaken(2 * pack, Balance.roomMob(raw, dScale, DDials.DEF_MOB_DMG), hp * hScale, p);
        System.out.println("[balance] solo F1 room pack of " + pack + ": average costs " + Math.round(avg) + " HP, worst "
                + Math.round(worst) + " of " + Math.round(p.health()) + " (old: a whole 2-cell room at party numbers cost " + Math.round(room) + ")");
        assertTrue(avg <= p.health() * 0.4, "an average F1 pack costs the solo player " + Math.round(avg) + " of " + p.health());
        assertTrue(worst < p.health() * 0.75, "the worst F1 pack (all " + worstRaw + "-damage mobs) costs " + Math.round(worst));
        // the shape of the old setup must fail (so the check can fail at all): the whole room at once, party numbers
        assertTrue(room > p.health(), "the old room fight was lost");
        // and neither lever alone meets the bar: party numbers in packs, or solo numbers with the whole 2-cell room at once
        double packsAlone = Balance.packTaken(pack, Balance.roomMob(raw, dScale, 1), hp * hScale, p);
        double soloAlone = Balance.packTaken(2 * pack, Balance.roomMob(raw, dScale, 1) * sd, hp * hScale * sh, p);
        System.out.println("[balance] solo F1: packs at party numbers cost " + Math.round(packsAlone) + ", solo numbers with the whole room " + Math.round(soloAlone));
        assertTrue(packsAlone > p.health() * 0.4, "packs alone");
        assertTrue(soloAlone > p.health() * 0.4, "solo numbers alone");
        // a full party is untouched by the solo dials
        assertEquals(1.0, Balance.partyScale(5, DDials.DEF_SOLO_ROOM_HP), 1e-9);
        assertTrue(DDials.DEF_ROOM_AGGRO > 0 && DDials.DEF_ROOM_AGGRO < 32, "a pack is at most about one 32-block cell");
    }

    @Test
    void packArithmetic() {
        Balance.Ref p = new Balance.Ref("t", 1000, 0, 100);
        // two mobs, 10 landed/s each, 100 HP each at 100 DPS: the first lives 1 s, the second 2 s -> 10 + 20
        assertEquals(30, Balance.packTaken(2, 10, 100, p), 1e-9);
        assertEquals(10 * 1 + 20 * 2 + 30 * 3, Balance.packTaken(new double[]{10, 20, 30}, new double[]{100, 100, 100}, p), 1e-9);
        assertTrue(Double.isInfinite(Balance.packTaken(1, 10, 100, new Balance.Ref("x", 1, 0, 0))));
    }

    @Test
    void f1RoomsAndBloodAreSurvivableAtDefaults() {
        double scale = f1.get("dmgScale").getAsDouble();
        check("mobPool", scale, 15);
        check("minibossPool", scale, 6);
        check("bloodPool", scale, 8);
    }

    /**
     * playtest-endgame: the F1 Fallen Explorer (then 40,000 HP / 400 damage) swung every second plus a lightning every
     * 6 s and killed a 1,079 HP / 678 Defense player in 12-18 s while he needed ~17 s to kill it: lost twice. The old
     * check said "19 hits" and passed. A duel is a race, so this one is in seconds: the reference player must outlast
     * every F1 miniboss at least twice over (the miniboss dies with the player at about half health or better).
     */
    @Test
    void f1MinibossDuelIsWonAtDefaults() {
        double scale = f1.get("dmgScale").getAsDouble(), hpScale = f1.get("hpScale").getAsDouble();
        for (JsonElement e : f1.getAsJsonArray("minibossPool")) {
            String id = e.getAsString();
            Balance.Pattern pt = Balance.pattern(BEHAVIOUR.get(id));
            double raw = Balance.roomMob(DMG.get(id), scale, DDials.DEF_MOB_DMG), hp = HP.get(id) * hpScale;
            double die = Balance.secondsToDie(raw, pt, Balance.MID), kill = Balance.secondsToKill(hp, pt, Balance.MID);
            assertTrue(Balance.margin(hp, raw, pt, Balance.MID) >= 2.0,
                    id + ": the reference player lasts " + Math.round(die) + " s and needs " + Math.round(kill) + " s to kill it");
        }
        // the shape of the old numbers must fail this check (so the check can fail at all)
        Balance.Pattern explorer = Balance.pattern("dungeons_explorer");
        assertTrue(Balance.margin(40_000, 400, explorer, Balance.MID) < 1, "40k / 400 was a lost race");
        // and the playtest's own character, at the old numbers, loses (it did, twice)
        Balance.Ref tester = new Balance.Ref("playtest", 1_079, 678, 40_000 / 17.0);
        assertTrue(Balance.margin(40_000, 400, explorer, tester) < 1.2, "the playtest lost this duel");
    }

    @Test
    void rateArithmetic() {
        Balance.Pattern plain = Balance.PLAIN;
        assertEquals(54, Balance.landedPerSecond(400, plain, 630), 1e-9);              // floor(400 x 100/730)
        assertEquals(588 / 54.0, Balance.secondsToDie(400, plain, Balance.MID), 1e-9);
        assertEquals(10.0, Balance.secondsToKill(20_000, plain, Balance.MID), 1e-9);
        Balance.Pattern regen = new Balance.Pattern(1, 0, 0, 0.05, 1);                   // heals 5%/s of 50k = 2,500/s
        assertTrue(Double.isInfinite(Balance.secondsToKill(50_000, regen, Balance.MID)), "out-healed: never dies");
        assertEquals(0, Balance.margin(50_000, 100, regen, Balance.MID), 1e-9);
    }

    private static void check(String pool, double floorScale, int minHits) {
        JsonArray ids = f1.getAsJsonArray(pool);
        assertTrue(ids != null && !ids.isEmpty(), pool);
        for (JsonElement e : ids) {
            Double d = DMG.get(e.getAsString());
            assertNotNull(d, e.getAsString());
            double raw = Balance.roomMob(d, floorScale, DDials.DEF_MOB_DMG);
            assertTrue(hits(raw) >= minHits, pool + " " + e.getAsString() + " kills in " + hits(raw) + " hits (want >= " + minHits + ")");
        }
    }

    @Test
    void report() {
        // printed for the hand-off; asserts nothing beyond the data being there
        double bd = f1.get("bossDamage").getAsDouble();
        for (Balance.Ref p : new Balance.Ref[]{Balance.MID, Balance.ENTRY}) {
            System.out.println("[balance] F1 vs " + p.label()
                    + ": body " + Balance.landed(Balance.bossBody(bd, DDials.DEF_BOSS_MELEE_SHARE, 1), p.defense()) + " (" + Balance.hitsToKill(Balance.bossBody(bd, DDials.DEF_BOSS_MELEE_SHARE, 1), p) + " hits)"
                    + ", skull " + Balance.landed(bd * GrinkoFight.SKULL, p.defense()) + " (" + Balance.hitsToKill(bd * GrinkoFight.SKULL, p) + ")"
                    + ", barrage " + Balance.landed(bd * GrinkoFight.BARRAGE, p.defense()) + " (" + Balance.hitsToKill(bd * GrinkoFight.BARRAGE, p) + ")"
                    + ", undead " + Balance.landed(Balance.addContact(GrinkoFight.UNDEAD_DMG, DDials.DEF_ADD_MELEE_SHARE, 1), p.defense())
                    + " (" + Balance.hitsToKill(Balance.addContact(GrinkoFight.UNDEAD_DMG, DDials.DEF_ADD_MELEE_SHARE, 1), p) + ")"
                    + ", Rotter " + Balance.landed(DMG.get("dungeon_blood_rotter"), p.defense()) + " (" + Balance.hitsToKill(DMG.get("dungeon_blood_rotter"), p) + ")"
                    + ", Zombie Grunt " + Balance.landed(DMG.get("dungeon_zombie_grunt"), p.defense()) + " (" + Balance.hitsToKill(DMG.get("dungeon_zombie_grunt"), p) + ")");
        }
        // minibosses as duels, on their first floor (where each is the newest threat)
        for (String fid : new String[]{"F1", "F2", "F3", "F4"}) {
            JsonObject f = FLOORS.get(fid);
            for (JsonElement e : f.getAsJsonArray("minibossPool")) {
                String id = e.getAsString();
                Balance.Pattern pt = Balance.pattern(BEHAVIOUR.get(id));
                double raw = DMG.get(id) * f.get("dmgScale").getAsDouble(), hp = HP.get(id) * f.get("hpScale").getAsDouble();
                StringBuilder b = new StringBuilder("[balance] " + fid + " " + id + " (" + Math.round(hp) + " HP, " + Math.round(raw) + " raw):");
                for (Balance.Ref p : new Balance.Ref[]{Balance.MID, Balance.ENTRY})
                    b.append(" vs ").append(p.label()).append(" lasts ").append(Math.round(Balance.secondsToDie(raw, pt, p)))
                            .append(" s, kills it in ").append(Math.round(Balance.secondsToKill(hp, pt, p))).append(" s;");
                System.out.println(b);
            }
        }
    }
}
