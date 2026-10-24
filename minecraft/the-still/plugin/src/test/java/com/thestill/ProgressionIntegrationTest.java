package com.thestill;

import com.thestill.config.DialRegistry;
import com.thestill.config.Dials;
import com.thestill.economy.Economy;
import com.thestill.economy.Money;
import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.rank.Rank;
import com.thestill.rank.RankLadder;
import com.thestill.rank.RankService;
import com.thestill.state.StateMap;
import com.thestill.store.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The money and rank path, end to end, against a real SQLite database.
 *
 * <p>No Bukkit server, no mock framework — the store, the profiles, the economy
 * and the ladder are the production classes, wired the way
 * {@code TheStillPlugin} wires them. What is missing is only the parts that
 * genuinely need a client: an inventory to sell out of, and a screen to draw on.
 *
 * <p>That split is deliberate. Rendering failures are visible the moment a human
 * logs in; a rank-up that charges the wrong amount, or a balance that does not
 * survive a restart, is the kind of thing nobody notices until it has happened
 * to fifty people.
 */
class ProgressionIntegrationTest {

    private static final Logger LOG = Logger.getLogger("progression-test");
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path dir;

    private Database database;
    private Profiles profiles;
    private Economy economy;
    private RankService ranks;
    private DialRegistry dialRegistry;

    @BeforeEach
    void open() throws Exception {
        dialRegistry = new DialRegistry(LOG, new File(dir.toFile(), "dials.yml"));
        dialRegistry.load();
        Dials dials = new Dials(dialRegistry);

        database = new Database(LOG, dials, new File(dir.toFile(), "test.db"));
        database.start();

        profiles = new Profiles(LOG, database);
        economy = new Economy(LOG, profiles);
        RankLadder ladder = new RankLadder(LOG, new File(dir.toFile(), "ranks.yml"));
        ladder.start();
        ranks = new RankService(LOG, ladder, economy, profiles, dialRegistry);
    }

    @AfterEach
    void close() throws Exception {
        if (profiles != null) {
            profiles.stop();
        }
        if (database != null) {
            database.stop();
        }
    }

    /** Reopens the store against the same files, as a restart would. */
    private void restart() throws Exception {
        profiles.stop();
        database.stop();
        open();
    }

    private Profile join() throws Exception {
        return profiles.loadBlocking(PLAYER, "Tester");
    }

    // ---- money -----------------------------------------------------------

    @Test
    @DisplayName("a new player starts broke, at the bottom rank, and is not blocked from D1")
    void freshPlayer() throws Exception {
        Profile profile = join();
        assertEquals(0L, profile.balance());
        assertEquals(0, ranks.rankOf(profile).index());
        assertEquals(0L, ranks.costOf(ranks.rankOf(profile)),
                "the starting rank must cost nothing, or nobody can ever begin");
    }

    @Test
    @DisplayName("earnings survive a restart")
    void balancePersists() throws Exception {
        Profile profile = join();
        economy.deposit(profile, Money.fromUnits(1234.56), "test");
        assertEquals(Money.fromUnits(1234.56), profile.balance());

        restart();
        Profile reloaded = join();
        assertEquals(Money.fromUnits(1234.56), reloaded.balance(),
                "a player's money did not survive a restart");
    }

    @Test
    @DisplayName("you cannot spend what you do not have, and a refusal costs nothing")
    void cannotOverspend() throws Exception {
        Profile profile = join();
        economy.deposit(profile, Money.fromUnits(100), "test");
        assertFalse(economy.withdraw(profile, Money.fromUnits(101), "test"));
        assertEquals(Money.fromUnits(100), profile.balance(), "a failed withdrawal took money");
        assertTrue(economy.withdraw(profile, Money.fromUnits(100), "test"));
        assertEquals(0L, profile.balance());
    }

    @Test
    @DisplayName("a negative deposit is refused rather than quietly subtracting")
    void negativeAmountsAreRefused() throws Exception {
        Profile profile = join();
        assertThrows(IllegalArgumentException.class, () -> profile.deposit(-500));
        assertThrows(IllegalArgumentException.class, () -> profile.withdraw(-500));
        assertThrows(IllegalArgumentException.class, () -> profile.setBalance(-1));
    }

    @Test
    @DisplayName("a balance cannot overflow into a negative")
    void balanceSaturates() throws Exception {
        Profile profile = join();
        profile.setBalance(Long.MAX_VALUE - 10);
        profile.deposit(1000);
        assertEquals(Long.MAX_VALUE, profile.balance(), "wrapped instead of saturating");
        assertTrue(profile.balance() > 0);
    }

    // ---- ranks -----------------------------------------------------------

    @Test
    @DisplayName("rank up charges exactly what it showed, and only when affordable")
    void rankupChargesTheAdvertisedPrice() throws Exception {
        Profile profile = join();
        Rank next = ranks.nextRank(profile);
        assertNotNull(next);
        long advertised = ranks.costOf(next);

        // One short.
        economy.deposit(profile, advertised - 1, "test");
        RankService.Result poor = ranks.rankup(profile);
        assertEquals(RankService.Status.TOO_POOR, poor.status());
        assertEquals(advertised - 1, profile.balance(), "a refused rank-up took money");
        assertEquals(1L, poor.shortBy());
        assertEquals(0, ranks.rankOf(profile).index());

        // Exactly enough.
        economy.deposit(profile, 1, "test");
        RankService.Result ok = ranks.rankup(profile);
        assertTrue(ok.promoted());
        assertEquals(0L, profile.balance(), "charged something other than the advertised price");
        assertEquals(1, ranks.rankOf(profile).index());
    }

    @Test
    @DisplayName("rank survives a restart")
    void rankPersists() throws Exception {
        Profile profile = join();
        economy.deposit(profile, Money.fromUnits(10_000_000), "test");
        ranks.rankup(profile);
        ranks.rankup(profile);
        ranks.rankup(profile);
        assertEquals(3, ranks.rankOf(profile).index());

        restart();
        assertEquals(3, ranks.rankOf(join()).index());
    }

    @Test
    @DisplayName("the ladder can be climbed all the way and then stops")
    void ladderTerminates() throws Exception {
        Profile profile = join();
        economy.deposit(profile, Long.MAX_VALUE / 2, "test");
        int climbed = 0;
        while (ranks.rankup(profile).promoted()) {
            climbed++;
            if (climbed > 200) {
                throw new AssertionError("rank up never reported AT_MAX — infinite ladder");
            }
        }
        assertEquals(ranks.ladder().size() - 1, climbed, "did not reach the top");
        assertEquals(RankService.Status.AT_MAX, ranks.rankup(profile).status());
    }

    @Test
    @DisplayName("the whole climb costs what the ladder says it costs")
    void totalClimbCost() throws Exception {
        Profile profile = join();
        long expected = 0;
        for (int i = 1; i < ranks.ladder().size(); i++) {
            expected += ranks.costOf(ranks.ladder().byIndex(i));
        }
        economy.deposit(profile, expected, "test");
        while (ranks.rankup(profile).promoted()) {
            // climb
        }
        assertEquals(ranks.ladder().size() - 1, ranks.rankOf(profile).index(),
                "exact ladder cost was not enough to reach the top");
        assertEquals(0L, profile.balance(), "the climb cost something other than the sum of rungs");
    }

    // ---- depth gating ----------------------------------------------------

    @Test
    @DisplayName("a depth opens at its required rank and not one rung earlier")
    void depthGating() throws Exception {
        Profile profile = join();
        var depth5 = new com.thestill.region.Region("depth.d5",
                com.thestill.region.RegionType.DEPTH, null,
                new com.thestill.region.Cuboid("world", 0, 0, 0, 10, 10, 10),
                0L, new StateMap());
        depth5.state().set(RankService.REQUIRED_RANK, 4);

        economy.deposit(profile, Money.fromUnits(10_000_000), "test");
        for (int i = 0; i < 4; i++) {
            assertFalse(ranks.canAccess(profile, depth5),
                    "D5 opened at rank index " + ranks.rankOf(profile).index() + ", too early");
            assertTrue(ranks.rankup(profile).promoted());
        }
        assertEquals(4, ranks.rankOf(profile).index());
        assertTrue(ranks.canAccess(profile, depth5), "D5 did not open at its required rank");
    }

    @Test
    @DisplayName("a region with no requirement is open to everyone")
    void unmarkedRegionsAreOpen() throws Exception {
        Profile profile = join();
        var open = new com.thestill.region.Region("hub.main",
                com.thestill.region.RegionType.HUB, null,
                new com.thestill.region.Cuboid("world", 0, 0, 0, 10, 10, 10),
                0L, new StateMap());
        assertTrue(ranks.canAccess(profile, open));
        assertEquals(0, ranks.requiredRank(open));
    }

    // ---- the Phase 1 scaffold -------------------------------------------

    @Test
    @DisplayName("the rank cost scale moves every rung together and never inverts the ladder")
    void costScaleIsUniform() throws Exception {
        var ladder = ranks.ladder();
        long previous = -1;
        for (int i = 1; i < ladder.size(); i++) {
            long cost = ranks.costOf(ladder.byIndex(i));
            assertTrue(cost >= previous,
                    "rung " + i + " costs less than the one below it after scaling");
            previous = cost;
        }
        // The scaffold must not make anything free, or the ladder stops being a
        // ladder and /rankup becomes a button you hold down.
        assertTrue(ranks.costOf(ladder.byIndex(1)) > 0,
                "the first purchasable rank became free at this scale");
    }
}
