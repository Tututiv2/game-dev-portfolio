package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Fixtures are the wording SkyHanni's patterns describe (constants/regexesModern.json, key in each
 * comment), written out the way Hypixel sends them - with colour codes, and with the modern client's
 * private-use glyphs (U+E01A Magic Find, U+E02B Farming Fortune) where SkyHanni's pattern has them.
 */
class MoneyChatTest {

    private static MoneyChat.Drop drop(String line) {
        return MoneyChat.drop(line, line);
    }

    // ------------------------------------------------------------ deliveries

    @Test
    void bazaarClaimOfABuyOrder() {
        // bazaar.orders.chat.claimed.colorless
        MoneyChat.Acquired a = MoneyChat.acquired(
                "§6[Bazaar] §7Claimed §a1,280x §fWheat §7worth §67,680 coins §7bought for §66.0 §7each!");
        assertEquals(new MoneyChat.Acquired("Wheat", 1280, "bazaar-claim"), a);
        assertEquals("bazaar", MoneyChat.tradeRule(
                "§6[Bazaar] §7Claimed §a1,280x §fWheat §7worth §67,680 coins §7bought for §66.0 §7each!"));
        // A different tail still nets (safety net, not in the SkyHanni repo).
        assertEquals(new MoneyChat.Acquired("Enchanted Hay Bale", 64, "bazaar-claim"),
                MoneyChat.acquired("[Bazaar] Claimed 64x Enchanted Hay Bale worth 9.8M coins!"));
    }

    @Test
    void bazaarInstantBuyDeliversButSellAndSetupDoNot() {
        // inventory.bazaar.transaction
        assertEquals(new MoneyChat.Acquired("Enchanted Diamond", 64, "bazaar-buy"),
                MoneyChat.acquired("[Bazaar] Bought 64x Enchanted Diamond for 87,578 coins!"));
        assertNull(MoneyChat.acquired("[Bazaar] Sold 640x Wheat for 3,840 coins!"));
        // bazaar.orders.chat.setup.colorless
        assertNull(MoneyChat.acquired("[Bazaar] Buy Order Setup! 160x Enchanted Hay Bale for 24,576,000 coins."));
        assertEquals("bazaar", MoneyChat.tradeRule("[Bazaar] Buy Order Setup! 160x Enchanted Hay Bale for 24,576,000 coins."));
        // bazaar.orders.chat.cancelled.colorless
        assertEquals("bazaar", MoneyChat.tradeRule("[Bazaar] Cancelled! Refunded 1,000,000 coins from cancelling Buy Order!"));
    }

    @Test
    void shopBuysAndMovesOutOfSacks() {
        // data.npctrade.bought
        assertEquals(new MoneyChat.Acquired("Compost", 16, "shop-buy"), MoneyChat.acquired("You bought Compost x16!"));
        assertEquals(new MoneyChat.Acquired("Jacob's Ticket", 1, "shop-buy"), MoneyChat.acquired("§aYou bought §fJacob's Ticket§a!"));
        // data.owninventory.chat.movedsacktoinventory / gfs.chat.from
        assertEquals(new MoneyChat.Acquired("Wheat", 64, "moved-from-sacks"),
                MoneyChat.acquired("§aMoved §r§e64 Wheat§r§a from your Sacks to your inventory."));
        // inventory.cake-tracker.cake.purchased (generalised)
        assertEquals(new MoneyChat.Acquired("New Year Cake (Year 400)", 1, "purchase"),
                MoneyChat.acquired("§eYou purchased §6New Year Cake (Year 400) §efor §6§l1,000,000 coins§e!"));
        // AH claim (not in the SkyHanni repo)
        assertEquals(new MoneyChat.Acquired("Hyperion", 1, "auction-claim"),
                MoneyChat.acquired("You claimed Hyperion from [MVP+] Someone's auction!"));
        assertNull(MoneyChat.acquired("You received 7x Dung for killing a Fly!"));
        assertNull(MoneyChat.acquired(null));
    }

    @Test
    void tradeLinesNameTheirRule() {
        assertEquals("npc-sell", MoneyChat.tradeRule("You sold Rotten Flesh x64 for 128 Coins!")); // misc.npcdaylimit.sold
        assertEquals("trade", MoneyChat.tradeRule("§6Trade completed with §b[MVP+] Someone§r§6!")); // carry.trade.completed
        assertEquals("minion", MoneyChat.tradeRule("You picked up a minion!"));                    // minion.chat.pickup
        assertEquals("stash", MoneyChat.tradeRule(
                "§eOne or more items didn't fit in your inventory and were added to your item stash! §6Click here §eto pick them up!"));
        assertEquals("visitor", MoneyChat.tradeRule("OFFER ACCEPTED with Jacob (UNCOMMON)"));     // garden.visitor.droptracker.accept
        assertNull(MoneyChat.tradeRule("RARE DROP! Summoning Eye"));
        assertNull(MoneyChat.tradeRule("You died and lost 1,234 coins!"));
    }

    // ------------------------------------------------------------ drops

    @Test
    void pestRareDropIsPestLoot() {
        // garden.pests.tracker.raredrop: §6§l(?:RARE|PET) DROP! (?<item>.+?)(?: §8x(?<amount>))? (?:§.)*\((?:§.)?\+[\d.,]+\uE02B\)
        assertEquals(new MoneyChat.Drop("Pretty Fly Vinyl", 1, IncomeSource.PEST, "rare-drop/pest", null),
                drop("§6§lRARE DROP! §r§9Pretty Fly Vinyl §6(+1,234\uE02B)"));
        assertEquals(new MoneyChat.Drop("Enchanted Hay Bale", 3, IncomeSource.PEST, "rare-drop/pest", null),
                drop("§6§lRARE DROP! §r§9Enchanted Hay Bale §8x3 §6(§6+250\uE02B)"));
        // The clover glyph lost on the way (another mod re-wrote the line): "(+N)" without % is still Farming Fortune.
        assertEquals(IncomeSource.PEST, drop("RARE DROP! Overclocker 3000 (+1,234)").source());
        assertEquals("Overclocker 3000", drop("RARE DROP! Overclocker 3000 (+1,234)").item());
    }

    @Test
    void petDropReadsItsRarityFromTheColour() {
        // raredrop.pet.droppedmessage (on the legacy text) + garden.pests.tracker.raredrop "(Cocoaleech)"
        MoneyChat.Drop d = drop("§6§lPET DROP! §r§5Slug §6(Cocoaleech)");
        assertEquals("Slug", d.item());
        assertEquals("EPIC", d.petRarity());
        assertEquals(IncomeSource.PEST, d.source());
        assertEquals("pet-drop/pest", d.rule());
        assertEquals("LEGENDARY", MoneyChat.drop("PET DROP! Rat (+120% Magic Find)", "§6§lPET DROP! §6Rat §b(+120% Magic Find)").petRarity());
        assertNull(MoneyChat.drop("PET DROP! Rat", null).petRarity());
        // A styled Component rebuilt to legacy text can put the space inside the coloured run.
        assertEquals("EPIC", MoneyChat.drop("PET DROP! Slug", "§6§lPET DROP!§r§5 Slug §6(+5)").petRarity());
    }

    @Test
    void combatRareDropsAreRareDrops() {
        // combat.ghosttracker.itemdrop.colorless: RARE DROP! (?<item>.+) \(\+(?<mf>\d+)%? \uE01A Magic Find\)
        assertEquals(new MoneyChat.Drop("Foul Flesh", 1, IncomeSource.RARE, "rare-drop", null),
                drop("§6§lRARE DROP! §r§9Foul Flesh §r§b(+123% \uE01A Magic Find)"));
        // raredrop.enchantedbook.colorless
        assertEquals("Enchanted Book", drop("RARE DROP! Enchanted Book (+50% \uE01A Magic Find)").item());
        // Slayer style: the item in brackets, double space, every tier word.
        assertEquals(new MoneyChat.Drop("Beheaded Horror", 1, IncomeSource.RARE, "rare-drop", null),
                drop("§9§lVERY RARE DROP!  §r§5(Beheaded Horror) §r§b(+100% \uE01A Magic Find)"));
        assertEquals("Overflux Capacitor", drop("§d§lCRAZY RARE DROP!  (Overflux Capacitor) (+150% \uE01A Magic Find)").item());
        assertEquals("Judgement Core", drop("§d§lPRAY TO RNGESUS DROP! §r§6Judgement Core").item());
        assertEquals("Warden Heart", drop("INSANE DROP! (Warden Heart) (+250% Magic Find)").item());
        // diana.chat.griffin.feather.drop
        assertEquals("Griffin Feather", drop("RARE DROP! You dug out a Griffin Feather!").item());
    }

    @Test
    void pestKillLootRareCropsCropFeverAndJacob() {
        // garden.pests-api.chat.pest-death
        assertEquals(new MoneyChat.Drop("Dung", 7, IncomeSource.PEST, "pest-kill", null),
                drop("§eYou received §a7x §fDung §efor killing a §2Fly§e!"));
        assertEquals(new MoneyChat.Drop("Honey Jar", 2, IncomeSource.PEST, "pest-kill", null),
                drop("You received 2x Honey Jar for killing an Earthworm!"));
        // garden.rarecrops.cropie.colorless / squash
        assertEquals(new MoneyChat.Drop("Cropie", 1, IncomeSource.RARE, "rare-crop", null),
                drop("§6§lRARE CROP! §r§9Cropie §r§6(+500\uE02B)"));
        assertEquals("Squash", drop("§9§lVERY RARE CROP! §r§5Squash").item());
        // garden.cropfever.drop
        assertEquals(new MoneyChat.Drop("Enchanted Hay Bale", 2, IncomeSource.RARE, "crop-fever", null),
                drop("§6§lRARE! §r§eYou dropped §a2x §9Enchanted Hay Bale§e!"));
        // chat.jacobcompact.overclocker: " {4}Overclocker 3000(?: x(?<count>\d+))?"
        assertEquals(new MoneyChat.Drop("Overclocker 3000", 1, IncomeSource.RARE, "jacob-overclocker", null),
                drop("    §9Overclocker 3000"));
        assertEquals(2, drop("    Overclocker 3000 x2").count());
    }

    @Test
    void thingsThatAreNotDrops() {
        assertNull(drop("[NPC] Jacob: Overclocker 3000"));
        assertNull(drop("Overclocker 3000"));
        assertNull(drop("RARE DROP!"));
        assertNull(drop("FLOOR DROP! You found Bal Shard on the ground!"));
        assertNull(drop("[Bazaar] Bought 64x Wheat for 400 coins!"));
        assertNull(drop("You received 1,000 coins!"));
        assertNull(drop("[MVP+] Someone: RARE DROP! fake (+1)")); // a player typing it is not a drop
        assertNull(MoneyChat.drop(null, null));
    }

    @Test
    void multiLineMessagesSplitAndBlankLinesGo() {
        assertEquals(List.of("REWARDS", "    Overclocker 3000", "    +25 Bits"),
                MoneyChat.lines("REWARDS\n    Overclocker 3000\n\n    +25 Bits"));
    }

    @Test
    void legacyCodesForTheSixteenColours() {
        assertEquals('6', MoneyChat.legacyCode(0xFFAA00));
        assertEquals('5', MoneyChat.legacyCode(0xFFAA00AA));
        assertEquals('f', MoneyChat.legacyCode(0xFFFFFF));
        assertEquals((char) 0, MoneyChat.legacyCode(0x123456));
    }
}
