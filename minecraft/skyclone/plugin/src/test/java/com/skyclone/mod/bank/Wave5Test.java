package com.skyclone.mod.bank;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 5: museum interest bonus, fame multiplier on other sources, fame per gem, co-op slots, reader detection. */
class Wave5Test {

    @Test
    void museumBonusIsTwoPercentPerMilestoneCapped() {
        assertEquals(1.0, BankMath.museumInterestMultiplier(0, 0.02, 36), 1e-12);
        assertEquals(1.2, BankMath.museumInterestMultiplier(10, 0.02, 36), 1e-12);
        assertEquals(1.72, BankMath.museumInterestMultiplier(36, 0.02, 36), 1e-12, "research: up to +72%");
        assertEquals(1.72, BankMath.museumInterestMultiplier(99, 0.02, 36), 1e-12, "capped");
        assertEquals(1.0 + 0.02 * 99, BankMath.museumInterestMultiplier(99, 0.02, 0), 1e-12, "0 = no cap");
        assertEquals(1.0, BankMath.museumInterestMultiplier(-3, 0.02, 36), 1e-12);
        assertEquals(1.0, BankMath.museumInterestMultiplier(10, Double.NaN, 36), 1e-12, "a broken dial pays the base");
    }

    @Test
    void fameBonusCarriesFractionsAndNeverMints() {
        double[] carry = {0};
        long total = 0;
        for (int i = 0; i < 100; i++) total += BankMath.fameBonus(15, 1.1, carry);
        assertEquals(150, total, "100 slayer T5 rewards (15 bits) at 1.1x add exactly 150");
        assertEquals(0, BankMath.fameBonus(15, 1.0, new double[]{0}), "rank 1 adds nothing");
        assertEquals(0, BankMath.fameBonus(0, 2.0, new double[]{0}));
        assertEquals(0, BankMath.fameBonus(10, Double.POSITIVE_INFINITY, new double[]{0}));
        assertEquals(80, BankMath.fameBonus(80, 2.0, new double[]{0}), "Diamond medal 80 at 2x adds 80");
        double[] bad = {5.0};   // a corrupt carry is ignored, not paid out
        assertEquals(1, BankMath.fameBonus(15, 1.1, bad));
    }

    @Test
    void fameAppliesOnlyToOtherModulesRealRewards() {
        List<String> all = List.of("*");
        List<String> ex = List.of("admin", "refund", "npc-buy", "restore", "rollback", "trade");
        assertTrue(BankMath.fameAppliesTo("slayer: zombie T5", all, ex, Set.of()));
        assertTrue(BankMath.fameAppliesTo("farming: harvest fair GOLD", all, ex, Set.of()));
        assertTrue(BankMath.fameAppliesTo("garden: pest beetle", all, ex, Set.of()));
        assertFalse(BankMath.fameAppliesTo("bank: boon buff bits", all, ex, Set.of()), "already multiplied");
        assertFalse(BankMath.fameAppliesTo("bank: fame multiplier on slayer", all, ex, Set.of()), "never a bonus on a bonus");
        assertFalse(BankMath.fameAppliesTo("accessories: bits talisman double", all, ex, Set.of()));
        assertFalse(BankMath.fameAppliesTo("rift: entry refund", all, ex, Set.of()));
        assertFalse(BankMath.fameAppliesTo("npc-buy-refund:shop:x", all, ex, Set.of()));
        assertFalse(BankMath.fameAppliesTo("slayer: zombie T5", all, ex, Set.of("slayer")), "self-applying module skipped");
        assertFalse(BankMath.fameAppliesTo("", all, ex, Set.of()));
        assertFalse(BankMath.fameAppliesTo(null, all, ex, Set.of()));
        assertTrue(BankMath.fameAppliesTo("slayer: x", List.of("slayer"), ex, Set.of()));
        assertFalse(BankMath.fameAppliesTo("garden: x", List.of("slayer"), ex, Set.of()), "source list narrows it");
        assertEquals("slayer", BankMath.reasonModule("Slayer: zombie"));
        assertNull(BankMath.reasonModule("no colon here"));
    }

    @Test
    void callerModuleSkipsTheBankAndCore() {
        assertEquals("storage", BankMath.callerModule(List.of(
                "com.skyclone.mod.bank.CommunityShop", "com.skyclone.mod.bank.CommunityShop$1",
                "com.skyclone.core.foundation.RequirementsImpl", "com.skyclone.mod.storage.Store", "com.skyclone.mod.storage.BagMenus")));
        assertEquals("dungeons", BankMath.callerModule(List.of(
                "com.skyclone.mod.bank.BankService", "com.skyclone.mod.dungeons.classes.ClassSystem")));
        assertNull(BankMath.callerModule(List.of("com.skyclone.mod.bank.CommunityShop$1",
                "com.skyclone.core.foundation.RequirementsImpl", "com.skyclone.mod.bank.BankTests")), "the bank asking itself");
        assertNull(BankMath.callerModule(List.of("com.skyclone.mod.bank.CommunityShop$1",
                "com.skyclone.core.foundation.FoundationAdmin", "org.bukkit.craftbukkit.Something")), "an admin command");
        assertNull(BankMath.callerModule(List.of()));
    }

    @Test
    void fameForGems() {
        assertEquals(65_000, BankMath.fameForGems(325, 200), "a cookie: 325 gems x 200");
        assertEquals(120_000, BankMath.fameForGems(600, 200), "a co-op slot");
        assertEquals(0, BankMath.fameForGems(0, 200));
        assertEquals(0, BankMath.fameForGems(325, 0));
    }

    @Test
    void communityShopSkyblockXpMatchesNeu() {
        // NEU sblevels.json: community_shop_upgrades 120 = 10 x (island_size 1 + minion_slots 5 + guests 1 + allowance 5)
        int total = 0;
        for (String id : List.of("island_size", "minion_slots", "guests_limit", "coins_allowance", "coop_slots", "magic_find"))
            total += 10 * BankMath.communityXpTiers(id);
        assertEquals(120, total);
        assertEquals(0, BankMath.communityXpTiers(null));
    }

    @Test
    void coopCapAndCommands() {
        assertEquals(5, BankMath.coopCap(5, 0, 0, 5, 8));
        assertEquals(6, BankMath.coopCap(5, 1, 6, 5, 8));
        assertEquals(8, BankMath.coopCap(5, 3, 8, 5, 8));
        assertEquals(5, BankMath.coopCap(5, 3, 8, 5, 5), "core's cap wins");
        assertEquals(5, BankMath.coopCap(4, 1, 6, 5, 8), "a lower base keeps the +1");
        assertArrayEquals(new String[]{"invite", "Steve"}, BankMath.coopCommand("/coop invite Steve"));
        assertArrayEquals(new String[]{"invite", "Steve"}, BankMath.coopCommand("/COOP   add   Steve"));
        assertArrayEquals(new String[]{"invite", "Alex_2"}, BankMath.coopCommand("/coopadd Alex_2"));
        assertArrayEquals(new String[]{"invite", "Alex"}, BankMath.coopCommand("/skyclone:coop invite Alex"));
        assertArrayEquals(new String[]{"accept"}, BankMath.coopCommand("/coop accept"));
        assertArrayEquals(new String[]{"accept"}, BankMath.coopCommand("/coop join"));
        assertNull(BankMath.coopCommand("/coop list"));
        assertNull(BankMath.coopCommand("/coop invite"));
        assertNull(BankMath.coopCommand("/coop invite bad-name!"));
        assertNull(BankMath.coopCommand("/coopadd"));
        assertNull(BankMath.coopCommand("/cooperate invite Steve"));
        assertNull(BankMath.coopCommand(null));
    }
}
