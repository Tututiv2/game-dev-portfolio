package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.auraqol.skyblock.RecipeBuy.Click;
import com.auraqol.skyblock.RecipeBuy.Clicked;
import com.auraqol.skyblock.RecipeBuy.Decision;
import com.auraqol.skyblock.RecipeBuy.Market;
import com.auraqol.skyblock.RecipeBuy.Verdict;

/**
 * Recipe -> Bazaar. The rules that matter most are the negative ones: left
 * click, the player's own slots, menu buttons and non-recipe menus must all
 * pass straight through, and no click may ever produce more than one command.
 */
class RecipeBuyTest {

    /** Real-shaped price data: the PriceDataTest fixtures plus Cocoa Beans on the bazaar. */
    static PriceData data() {
        Map<String, PriceData.Bazaar> bz = new HashMap<>(PriceData.parseBazaar(PriceDataTest.BAZAAR));
        bz.put("INK_SACK:3", new PriceData.Bazaar(4, 3));
        return PriceData.EMPTY
                .withBazaar(bz, 1)
                .withLbin(Map.of("HYPERION", 4.4e8, "PET", 1.0, "SALMON_HAT", 2.0e6), 1, "test")
                .withItems(PriceData.parseItems(PriceDataTest.ITEMS), 1);
    }

    static final String RECIPE = "§aEnchanted Diamond Block Recipe";

    static Clicked click(String title, Click kind, String id, String name, String vanilla, List<String> lore) {
        return new Clicked(true, true, false, title, false, false, kind, 10, id, null, name, vanilla, lore);
    }

    static Clicked right(String id, String name) {
        return click(RECIPE, Click.RIGHT, id, name, "minecraft:diamond", List.of());
    }

    // ------------------------------------------------------------ screens

    @Test
    void recipeScreensAreSingleRecipeViewsOnly() {
        assertTrue(RecipeBuy.isRecipeScreen("Enchanted Diamond Recipe"));
        assertTrue(RecipeBuy.isRecipeScreen("§8Hyperion Recipe"));
        assertTrue(RecipeBuy.isRecipeScreen("(1/2) Hyperion Recipe"));
        assertTrue(RecipeBuy.isRecipeScreen("Recipe"));
        assertTrue(RecipeBuy.isRecipeScreen("Confirm Process"));
        // Lists of recipes: a click there must navigate.
        assertFalse(RecipeBuy.isRecipeScreen("Recipe Book"));
        assertFalse(RecipeBuy.isRecipeScreen("Farming Recipes"));
        // The crafting table grid holds the player's own items.
        assertFalse(RecipeBuy.isRecipeScreen("Craft Item"));
        assertFalse(RecipeBuy.isRecipeScreen("The Forge"));
        assertFalse(RecipeBuy.isRecipeScreen("Bazaar ➜ Mining"));
        assertFalse(RecipeBuy.isRecipeScreen("Ender Chest (1/9)"));
        assertFalse(RecipeBuy.isRecipeScreen(""));
        assertFalse(RecipeBuy.isRecipeScreen(null));
    }

    // ------------------------------------------------------------ names

    @Test
    void cleanNameStripsCountsStarsLevelsAndColours() {
        assertEquals("Enchanted Diamond", RecipeBuy.cleanName("§aEnchanted Diamond §8x64"));
        assertEquals("Enchanted Iron", RecipeBuy.cleanName("64x Enchanted Iron"));
        assertEquals("Enchanted Iron", RecipeBuy.cleanName("Enchanted Iron (x32)"));
        assertEquals("Enchanted Iron", RecipeBuy.cleanName("Enchanted Iron x1,280"));
        assertEquals("Golden Dragon", RecipeBuy.cleanName("§7[Lvl 100] §6Golden Dragon"));
        assertEquals("Hyperion", RecipeBuy.cleanName("§dHyperion §6✪✪✪✪✪§c➎"));
        assertEquals("Enchanted Hay Bale", RecipeBuy.cleanName(" ✔ Enchanted Hay Bale §8x2"));
        assertEquals("Jacob's Ticket", RecipeBuy.cleanName("Jacob's Ticket"));
        assertEquals("Hyperion", RecipeBuy.cleanName("LEGENDARY Hyperion"));
        // A name that merely starts with a rarity in normal case is a name.
        assertEquals("Rare Candy", RecipeBuy.cleanName("Rare Candy"));
        assertEquals("", RecipeBuy.cleanName(" "));
        assertEquals("", RecipeBuy.cleanName(null));
    }

    @Test
    void reforgesAreOnlyASecondGuess() {
        assertEquals("Hyperion", RecipeBuy.withoutReforge("Heroic Hyperion"));
        assertNull(RecipeBuy.withoutReforge("Hyperion"));
        assertNull(RecipeBuy.withoutReforge("Heroic"));
        // Why it is never applied first: real items begin with reforge words.
        assertEquals("Dragon Helmet", RecipeBuy.withoutReforge("Strong Dragon Helmet"));
        assertEquals("Diamond", RecipeBuy.withoutReforge("Refined Diamond"));
    }

    // ------------------------------------------------------------ the happy path

    @Test
    void rightClickOnABazaarIngredientTypesBz() {
        Decision d = new RecipeBuy().handle(right("ENCHANTED_DIAMOND", "§aEnchanted Diamond"), data(), 1000);
        assertEquals(Verdict.SEND, d.verdict());
        assertEquals(Market.BAZAAR, d.action().market());
        assertEquals("bz Enchanted Diamond", d.action().command());
        assertEquals("Opened Bazaar for Enchanted Diamond - Buy Instantly → 1", d.hint());
    }

    @Test
    void shiftRightClickSendsTheSameSingleCommandWithAStackHint() {
        Clicked c = click(RECIPE, Click.SHIFT_RIGHT, "ENCHANTED_DIAMOND", "Enchanted Diamond x32",
                "minecraft:diamond", List.of());
        Decision d = new RecipeBuy().handle(c, data(), 1000);
        assertEquals(Verdict.SEND, d.verdict());
        assertEquals("bz Enchanted Diamond", d.action().command());
        assertTrue(d.hint().endsWith("Buy Instantly → stack"), d.hint());
    }

    @Test
    void auctionOnlyItemsSearchTheAuctionHouseByTheirRealName() {
        // The stack is reforged and starred; the search uses the item list's name.
        Decision d = new RecipeBuy().handle(right("HYPERION", "§dHeroic Hyperion ✪✪✪"), data(), 1000);
        assertEquals(Verdict.SEND, d.verdict());
        assertEquals(Market.AUCTION, d.action().market());
        assertEquals("ahs Hyperion", d.action().command());
        assertTrue(d.hint().contains("Auction House"), d.hint());
    }

    @Test
    void petsSearchWithoutTheirLevel() {
        Decision d = new RecipeBuy().handle(right("PET", "[Lvl 1] Golden Dragon"), data(), 1000);
        assertEquals("ahs Golden Dragon", d.action().command());
    }

    @Test
    void enchantedBooksUseTheirEnchantName() {
        Clicked c = new Clicked(true, true, false, RECIPE, false, false, Click.RIGHT, 3, "ENCHANTED_BOOK",
                Map.of("ultimate_wise", 5), "Enchanted Book", "minecraft:enchanted_book", List.of());
        Decision d = new RecipeBuy().handle(c, data(), 1000);
        assertEquals("bz Ultimate Wise", d.action().command());
    }

    @Test
    void anIngredientShownWithoutAnIdIsFoundByName() {
        Decision d = new RecipeBuy().handle(right(null, "Enchanted Diamond x16"), data(), 1000);
        assertEquals("bz Enchanted Diamond", d.action().command());
    }

    @Test
    void withNoPriceDataYetAnIdIsGuessedToBeBazaar() {
        Decision d = new RecipeBuy().handle(right("ENCHANTED_DIAMOND", "Enchanted Diamond"), PriceData.EMPTY, 1000);
        assertEquals("bz Enchanted Diamond", d.action().command());
    }

    // ------------------------------------------------------------ what must pass through

    @Test
    void leftClickIsNeverTouched() {
        // Hypixel's left-click in a recipe menu opens the ingredient's recipe.
        Clicked c = click(RECIPE, Click.OTHER, "ENCHANTED_DIAMOND", "Enchanted Diamond", "minecraft:diamond", List.of());
        assertEquals(Verdict.PASS, new RecipeBuy().handle(c, data(), 1000).verdict());
    }

    @Test
    void ownSlotsCarryingBypassOffAndOffSkyblockAllPass() {
        PriceData p = data();
        Clicked base = right("ENCHANTED_DIAMOND", "Enchanted Diamond");
        assertEquals(Verdict.PASS, new RecipeBuy().handle(new Clicked(false, true, false, RECIPE, false, false,
                Click.RIGHT, 1, "ENCHANTED_DIAMOND", null, "x", "", null), p, 1000).verdict(), "feature off");
        assertEquals(Verdict.PASS, new RecipeBuy().handle(new Clicked(true, false, false, RECIPE, false, false,
                Click.RIGHT, 1, "ENCHANTED_DIAMOND", null, "x", "", null), p, 1000).verdict(), "not on SkyBlock");
        assertEquals(Verdict.PASS, new RecipeBuy().handle(new Clicked(true, true, true, RECIPE, false, false,
                Click.RIGHT, 1, "ENCHANTED_DIAMOND", null, "x", "", null), p, 1000).verdict(), "Ctrl held");
        assertEquals(Verdict.PASS, new RecipeBuy().handle(new Clicked(true, true, false, RECIPE, true, false,
                Click.RIGHT, 1, "ENCHANTED_DIAMOND", null, "x", "", null), p, 1000).verdict(), "own inventory");
        assertEquals(Verdict.PASS, new RecipeBuy().handle(new Clicked(true, true, false, RECIPE, false, true,
                Click.RIGHT, 1, "ENCHANTED_DIAMOND", null, "x", "", null), p, 1000).verdict(), "carrying");
        assertEquals(Verdict.SEND, new RecipeBuy().handle(base, p, 1000).verdict(), "control case");
    }

    @Test
    void outsideARecipeMenuAPlainItemPasses() {
        for (String t : List.of("Ender Chest (1/9)", "Craft Item", "Recipe Book", "Bazaar ➜ Mining", "Large Chest")) {
            Clicked c = click(t, Click.RIGHT, "ENCHANTED_DIAMOND", "Enchanted Diamond", "minecraft:diamond", List.of());
            assertEquals(Verdict.PASS, new RecipeBuy().handle(c, data(), 1000).verdict(), t);
        }
    }

    @Test
    void menuButtonsPass() {
        RecipeBuy r = new RecipeBuy();
        PriceData p = data();
        assertEquals(Verdict.PASS, r.handle(click(RECIPE, Click.RIGHT, null, "§aGo Back", "minecraft:arrow",
                List.of("§7To Recipe Book")), p, 1000).verdict());
        assertEquals(Verdict.PASS, r.handle(click(RECIPE, Click.RIGHT, null, "§cClose", "minecraft:barrier",
                List.of()), p, 1000).verdict());
        assertEquals(Verdict.PASS, r.handle(click(RECIPE, Click.RIGHT, null, " ",
                "minecraft:black_stained_glass_pane", List.of()), p, 1000).verdict());
        // Even a pane that somehow carries an id is furniture.
        assertEquals(Verdict.PASS, r.handle(click(RECIPE, Click.RIGHT, "ENCHANTED_DIAMOND", "Enchanted Diamond",
                "minecraft:gray_stained_glass_pane", List.of()), p, 1000).verdict());
        // A button whose name is not a tradeable item stays a button.
        assertEquals(Verdict.PASS, r.handle(click(RECIPE, Click.RIGHT, null, "Crafting Table",
                "minecraft:crafting_table", List.of()), p, 1000).verdict());
        assertEquals(Verdict.PASS, r.handle(click(RECIPE, Click.RIGHT, null, "Supercraft",
                "minecraft:golden_pickaxe", List.of()), p, 1000).verdict());
        // Without price data a nameless-id item cannot be told from a button.
        assertEquals(Verdict.PASS, r.handle(right(null, "Enchanted Diamond"), PriceData.EMPTY, 1000).verdict());
    }

    @Test
    void itemsHypixelAlreadyGivesARightClickAreLeftAlone() {
        List<String> shopLore = List.of("§7Cost", "§aEnchanted Diamond §8x2", "",
                "§eClick to trade!", "§eRight-Click for more trading options!");
        Clicked c = click(RECIPE, Click.RIGHT, "ENCHANTED_DIAMOND", "Enchanted Diamond", "minecraft:diamond", shopLore);
        assertEquals(Verdict.PASS, new RecipeBuy().handle(c, data(), 1000).verdict());
        assertTrue(RecipeBuy.usesRightClick(List.of("right click to open")));
        assertFalse(RecipeBuy.usesRightClick(List.of("Click to view recipes!")));
    }

    // ------------------------------------------------------------ one click, one command

    @Test
    void atMostOneCommandPerHalfSecond() {
        RecipeBuy r = new RecipeBuy();
        PriceData p = data();
        Clicked c = right("ENCHANTED_DIAMOND", "Enchanted Diamond");
        assertEquals(Verdict.SEND, r.handle(c, p, 10_000).verdict());
        // Swallowed, not passed: a too-fast second click must not pick the item up either.
        assertEquals(Verdict.SWALLOW, r.handle(c, p, 10_200).verdict());
        assertNull(r.handle(c, p, 10_499).action());
        assertEquals(Verdict.SEND, r.handle(c, p, 10_500).verdict());
        // A swallowed click does not restart the window.
        assertEquals(Verdict.SWALLOW, r.handle(c, p, 10_900).verdict());
        assertEquals(Verdict.SEND, r.handle(c, p, 11_000).verdict());
    }

    @Test
    void commandsAreOneCleanLine() {
        assertEquals("a b c", RecipeBuy.safe("a/b§\nc"));
        assertEquals(100, RecipeBuy.safe("x".repeat(300)).length());
        Decision d = new RecipeBuy().handle(right(null, "§aEnchanted Diamond"), data(), 1000);
        assertEquals("bz Enchanted Diamond", d.action().command());
    }

    // ------------------------------------------------------------ ingredients that live in lore

    static final List<String> VISITOR = List.of(
            "§7Items Required:",
            " §9Enchanted Hay Bale §8x2",
            " §fCocoa Beans §8x64",
            "",
            "§7Rewards:",
            " §8+§35 §7Garden Experience",
            " §8+§c2,000 Copper",
            "",
            "§eClick to give!");

    @Test
    void requiredItemsAreReadFromLore() {
        assertEquals(List.of("Enchanted Hay Bale", "Cocoa Beans"), RecipeBuy.requiredItems(VISITOR));
        assertEquals(List.of("Refined Diamond", "Enchanted Gold"), RecipeBuy.requiredItems(List.of(
                "§7Required Items:", "§9Refined Diamond §8x2", "§aEnchanted Gold §8x32",
                "§7100,000 Coins", "", "§7Duration: §a8h")));
        // Currency is not an item.
        assertEquals(List.of("Enchanted Diamond"), RecipeBuy.requiredItems(List.of(
                "Cost", "Enchanted Diamond x2", "25,000 Coins", "1,500 Gemstone Powder")));
        assertEquals(List.of(), RecipeBuy.requiredItems(List.of("Requires Mining Skill 5", "Enchanted Diamond")));
        assertEquals(List.of(), RecipeBuy.requiredItems(null));
        assertTrue(RecipeBuy.isRequirementHeader("§7Items Required:"));
        assertFalse(RecipeBuy.isRequirementHeader("Rewards:"));
    }

    @Test
    void aVisitorOfferCyclesThroughWhatItNeedsOnePerClick() {
        RecipeBuy r = new RecipeBuy();
        PriceData p = data();
        // A visitor's menu is titled with the visitor's name, not "Recipe".
        Clicked accept = click("Jacob", Click.RIGHT, null, "§aAccept Offer", "minecraft:green_terracotta", VISITOR);
        Decision a = r.handle(accept, p, 1000);
        assertEquals(Verdict.SEND, a.verdict());
        // Not in the item list fixture: guessed to be a Bazaar material.
        assertEquals("bz Enchanted Hay Bale", a.action().command());
        assertTrue(a.hint().contains("(1/2"), a.hint());
        Decision b = r.handle(accept, p, 1600);
        assertEquals("bz Cocoa Beans", b.action().command());
        assertTrue(b.hint().contains("(2/2"), b.hint());
        // A swallowed click does not advance the cycle.
        assertEquals(Verdict.SWALLOW, r.handle(accept, p, 1700).verdict());
        assertEquals("bz Enchanted Hay Bale", r.handle(accept, p, 2200).action().command());
        // Left-click on Accept Offer still accepts.
        assertEquals(Verdict.PASS, r.handle(click("Jacob", Click.OTHER, null, "Accept Offer",
                "minecraft:green_terracotta", VISITOR), p, 5000).verdict());
    }

    @Test
    void aDifferentButtonStartsItsOwnCycle() {
        RecipeBuy r = new RecipeBuy();
        PriceData p = data();
        Clicked one = click("Jacob", Click.RIGHT, null, "Accept Offer", "minecraft:green_terracotta", VISITOR);
        r.handle(one, p, 1000);
        Clicked other = click("Confirm Process", Click.RIGHT, null, "Refined Diamond", "minecraft:diamond",
                List.of("Required Items:", "Enchanted Diamond x160"));
        Decision d = r.handle(other, p, 2000);
        assertEquals("bz Enchanted Diamond", d.action().command());
        assertFalse(d.hint().contains("(1/"), "a single requirement has no counter");
    }
}
