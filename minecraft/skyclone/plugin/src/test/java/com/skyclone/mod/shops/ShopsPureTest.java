package com.skyclone.mod.shops;

import com.skyclone.mod.shops.ShopData.CurrencyCost;
import com.skyclone.mod.shops.ShopData.Entry;
import com.skyclone.mod.shops.ShopData.Shop;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The shops' pure logic (pricing, limits, buyback, ledger) and the SHIPPED data files
 * (plugin/src/main/resources/data/shops), checked against research 05 §4 and the neu-repo prices.
 */
class ShopsPureTest {

    static InputStream res(String file) throws IOException {
        Path dir = Path.of("src", "main", "resources", "data", "shops");
        if (!Files.isDirectory(dir)) dir = Path.of("plugin", "src", "main", "resources", "data", "shops");
        Path p = dir.resolve(file);
        return Files.exists(p) ? new FileInputStream(p.toFile()) : null;
    }

    static List<Shop> shops() throws IOException {
        try (InputStream in = res("shops.json")) {
            assertNotNull(in, "shops.json not found");
            return ShopData.readShops(in);
        }
    }

    static Shop shop(List<Shop> all, String id) {
        for (Shop s : all) if (s.id.equals(id)) return s;
        fail("no shop " + id);
        return null;
    }

    static Entry entry(Shop s, String item) {
        for (Entry e : s.entries) if (item.equals(e.item)) return e;
        fail(s.id + " does not stock " + item);
        return null;
    }

    // ---- pricing ---------------------------------------------------------------------------------------

    @Test
    void dailyLimitIsResearchNumber() {
        assertEquals(640, Pricing.dailyLimit(640, false, 10));
        assertEquals(6400, Pricing.dailyLimit(640, true, 10));     // Shopping Spree x10
        assertEquals(0, Pricing.dailyLimit(-5, true, 10));
    }

    @Test
    void discountsDoNotStackAndApplyToCoins() {
        assertEquals(3, Pricing.bestDiscount(true, true, true, 1, 2, 3), 1e-9);
        assertEquals(2, Pricing.bestDiscount(true, true, false, 1, 2, 3), 1e-9);
        assertEquals(0, Pricing.bestDiscount(false, false, false, 1, 2, 3), 1e-9);
        assertEquals(970, Pricing.bundleCoins(1000, 1, 0, 3, 1), 1e-9);
        assertEquals(99, Pricing.bundleCoins(100, 1, 0, 1, 1), 1e-9);
    }

    @Test
    void priceNeverBelowResellValue() {
        // Farm Merchant lists 3 wheat for 7 coins; wheat sells to NPCs for 6 each -> 18 (no coin printer)
        assertEquals(18, Pricing.bundleCoins(7, 3, 6, 0, 1), 1e-9);
        assertTrue(Pricing.clamped(7, 3, 6));
        // a discount cannot push it under the floor either
        assertEquals(18, Pricing.bundleCoins(7, 3, 6, 3, 1), 1e-9);
        // markup below 1 acts as 1
        assertEquals(18, Pricing.bundleCoins(7, 3, 6, 0, 0.5), 1e-9);
        // untouched when already above the floor
        assertEquals(100, Pricing.bundleCoins(100, 1, 8, 0, 1), 1e-9);
        assertFalse(Pricing.clamped(100, 1, 8));
        assertEquals(0, Pricing.bundleCoins(0, 1, 8, 0, 1), 1e-9);
    }

    @Test
    void tenthsRoundUp() {
        assertEquals(0.4, Pricing.ceilTenth(0.31), 1e-9);
        assertEquals(12.0, Pricing.ceilTenth(12.0), 1e-9);
        assertEquals(0, Pricing.ceilTenth(-3), 1e-9);
    }

    @Test
    void allowedBundlesRespectsLimit() {
        assertEquals(3, Pricing.allowedBundles(10, 3, 630, 640));
        assertEquals(0, Pricing.allowedBundles(1, 3, 639, 640));
        assertEquals(64, Pricing.allowedBundles(64, 1, 0, 640));
        assertEquals(10, Pricing.allowedBundles(64, 64, 0, 640));
    }

    @Test
    void ledgerRollsAtUtcMidnight() {
        long day = Pricing.utcDay(1_700_000_000_000L);
        Pricing.Ledger l = new Pricing.Ledger();
        l.roll(day);
        l.addBought("farm_merchant", "WHEAT", 30);
        l.sold = 500;
        l.met.add("farm_merchant");
        l.roll(day);
        assertEquals(30, l.boughtToday("farm_merchant", "WHEAT"));
        l.roll(day + 1);
        assertEquals(0, l.boughtToday("farm_merchant", "WHEAT"));
        assertEquals(0, l.sold, 1e-9);
        assertTrue(l.met.contains("farm_merchant"), "meeting a merchant is not a daily counter");
        assertEquals(30, l.lifetimeItemsBought);
        assertEquals(Pricing.DAY_MS, Pricing.msToReset(0));
        assertEquals(1, Pricing.msToReset(Pricing.DAY_MS - 1));
    }

    @Test
    void buybackKeepsTenForFifteenMinutes() {
        Pricing.Buyback<String> bb = new Pricing.Buyback<>();
        for (int i = 0; i < 12; i++) bb.push("item" + i, "X", 1, i, i * 1000L, "s", 10);
        assertEquals(10, bb.size());
        long max = 15 * 60_000L;
        List<Pricing.Buyback.Sale<String>> l = bb.list(11_000, max);
        assertEquals("item11", l.get(0).item, "newest first");
        Pricing.Buyback.Sale<String> s = bb.take(0, 11_000, max);
        assertEquals("item11", s.item);
        assertEquals(9, bb.size());
        bb.restore(s, 10);
        assertEquals(10, bb.size());
        assertTrue(bb.list(2_000 + max + 1, max).size() < 10, "old sales expire");
        assertNull(bb.take(99, 0, max));
    }

    // ---- shipped data ------------------------------------------------------------------------------------

    @Test
    void dataIsWellFormed() throws IOException {
        List<Shop> all = shops();
        assertTrue(all.size() >= 70, "only " + all.size() + " merchants");
        List<String> bad = ShopData.validate(all);
        assertTrue(bad.isEmpty(), String.join("\n", bad));
        int lines = 0;
        for (Shop s : all) lines += s.entries.size();
        assertTrue(lines >= 800, "only " + lines + " stock lines");
    }

    @Test
    void everyContractHubAnchorIsStaffed() throws IOException {
        Set<String> anchors = new HashSet<>();
        for (Shop s : shops()) if ("hub".equals(s.island) && s.contract()) anchors.add(s.anchor);
        for (String a : List.of("npc.farm_merchant", "npc.mine_merchant", "npc.lumber_merchant", "npc.fish_merchant",
                "npc.weaponsmith", "npc.armorsmith", "npc.adventurer", "npc.builder", "npc.bartender"))
            assertTrue(anchors.contains(a), "no merchant for " + a);
        assertFalse(anchors.contains("npc.community_shop"), "the bank module owns the Community Shop");
    }

    @Test
    void pricesComeFromTheResearch() throws IOException {
        List<Shop> all = shops();
        Entry w = entry(shop(all, "farm_merchant"), "WHEAT");
        assertEquals(3, w.amount);
        assertEquals(7, w.coins, 1e-9);
        assertEquals(8, entry(shop(all, "mine_merchant"), "COAL").coins, 1e-9);
        assertEquals(2, entry(shop(all, "mine_merchant"), "COAL").amount);
        assertEquals(12, entry(shop(all, "lumber_merchant"), "ROOKIE_AXE").coins, 1e-9);
        assertEquals(40, entry(shop(all, "weaponsmith"), "ARROW").coins, 1e-9);
        assertEquals(1050, entry(shop(all, "armorsmith"), "ROSETTA_HELMET").coins, 1e-9);
        assertEquals(5000, entry(shop(all, "armorsmith"), "SQUIRE_SWORD").coins, 1e-9);
        assertEquals(250, entry(shop(all, "adventurer"), "BEGINNER_COMBAT_SACK").coins, 1e-9);
        assertEquals(5000, entry(shop(all, "bartender"), "DECENT_COFFEE").coins, 1e-9);
        // item-cost trades keep their inputs
        Entry gp = entry(shop(all, "mine_merchant"), "GOLD_PICKAXE");
        assertEquals(0, gp.coins, 1e-9);
        assertEquals("GOLD_INGOT", gp.items.get(0).item);
        assertEquals(3, gp.items.get(0).amount);
        // enchanted books map to the enchanting module's ids, the research id kept as an alternate
        Entry sharp = entry(shop(all, "librarian"), "ENCHANTMENT_SHARPNESS_1");
        assertEquals(30, sharp.coins, 1e-9);
        assertTrue(sharp.alts.contains("SHARPNESS;1"));
        // legacy meta ids use ':' (core's convention), never neu's '-'
        entry(shop(all, "farm_merchant"), "INK_SACK:3");
        // the two hub fishing merchants were merged without duplicates
        Shop fish = shop(all, "fish_merchant");
        int rods = 0;
        for (Entry e : fish.entries) if (e.item.equals("FISHING_ROD")) rods++;
        assertEquals(1, rods);
        entry(fish, "RAW_FISH");
    }

    @Test
    void builderPricesAreSmall() throws IOException {
        Shop b = shop(shops(), "builder");
        assertTrue(b.groups.size() >= 6);
        assertTrue(b.entries.size() >= 150, "builder has " + b.entries.size());
        for (Entry e : b.entries) {
            assertTrue(e.item.startsWith("@"), e.item);
            assertTrue(e.coins >= 1 && e.coins <= 25, e.item + " costs " + e.coins);
            assertTrue(e.tune);
            assertNotNull(b.group(e.group), e.item + " group " + e.group);
        }
    }

    @Test
    void noPayToWinAndNoForeignIslands() throws IOException {
        Set<String> islands = Set.of("hub", "barn", "mushroom_desert", "park", "gold_mine", "deep_caverns", "dwarven_mines",
                "glacite_tunnels", "crystal_hollows", "spiders_den", "the_end", "crimson_isle", "backwater_bayou",
                "jerrys_workshop", "dungeon_hub");
        for (Shop s : shops()) {
            assertTrue(islands.contains(s.island), s.id + " on " + s.island);
            for (Entry e : s.entries) for (CurrencyCost c : e.currency) assertFalse(c.id.equals("gem"), s.id + " sells for gems");
        }
    }

    @Test
    void referenceNamesAreRenamed() throws IOException {
        String[] proper = {"Rosetta", "Jax", "Pat", "Bea", "Zog", "Lukas", "Seymour", "Liz", "Lucius", "Agnes", "Alda", "Amelia",
                "Fann", "Grumblefoot", "Scoop", "Joe", "Shifty", "Smithmonger", "Jake", "Moby", "Talbot", "Tony", "Nyko", "Walter",
                "Lumina", "Bubu", "Bulvar", "Brynmor", "Beryl", "Chunk", "Gemma", "Odawa", "Shaggy", "Gregory", "Ezekiel", "Grog",
                "Hilda", "Marthos", "Lys", "Odger", "Udel", "Drakuu", "Joel", "Einary", "Sherry", "Terry", "Alex", "Ophelia", "Malik",
                "Elizabeth", "Maddox"};
        for (Shop s : shops()) for (String n : proper) {
            assertFalse(Pattern.compile("\\b" + n + "\\b").matcher(s.name).find(), s.id + " still named " + s.name);
            for (String g : s.greeting) assertFalse(Pattern.compile("\\b" + n + "\\b").matcher(g).find(), s.id + " greeting names " + n);
        }
    }

    @Test
    void townsfolkLoad() throws IOException {
        try (InputStream in = res("townsfolk.json")) {
            List<ShopData.Townsfolk> t = ShopData.readTownsfolk(in);
            assertTrue(t.size() >= 10);
            Set<String> ids = new HashSet<>();
            for (ShopData.Townsfolk f : t) {
                assertTrue(ids.add(f.id));
                assertTrue(f.anchor.startsWith("npc.lore."));
                assertFalse(f.lines.isEmpty());
            }
        }
    }

    // ---- lines a player can tell apart (playtest 2026-10-03) -------------------------------------------

    @Test
    void ambiguousLabelsAreFound() {
        // the playtest's pair: legacy SANDSTONE:2 is named "Smooth Sandstone", so is the modern block
        assertEquals(Set.of(0, 2), ShopData.ambiguous(List.of("&fSmooth Sandstone", "&fSandstone", "smooth  sandstone ")));
        assertTrue(ShopData.ambiguous(List.of("&fCut Sandstone", "&fSmooth Sandstone", "&aSmooth Red Sandstone")).isEmpty());
        assertTrue(ShopData.ambiguous(List.of()).isEmpty());
    }

    @Test
    void builderNeverListsOneBlockTwiceInATab() throws IOException {
        Shop b = shop(shops(), "builder");
        Set<String> seen = new HashSet<>();
        for (Entry e : b.entries) {
            assertTrue(e.item.startsWith("@"), "builder line not a block: " + e.item);
            assertTrue(seen.add(e.group + "|" + e.item), "builder lists " + e.item + " twice in " + e.group);
        }
        // both sandstones stay on sale; the menu labels them by block (Cut Sandstone / Smooth Sandstone)
        entry(b, "@CUT_SANDSTONE");
        entry(b, "@SMOOTH_SANDSTONE");
    }

    @Test
    void enchantedBooksAreTitledByTheirEnchant() throws IOException {
        // playtest 2026-10-03: every Librarian book showed as "Enchanted Book" - the variant tells them apart
        assertEquals("Sharpness I", ShopData.enchantLabel("Sharpness", 1));
        assertEquals("Smite VI", ShopData.enchantLabel("Smite", 6));
        assertEquals("Protection X", ShopData.enchantLabel(" Protection ", 10));
        assertEquals("&9Enchanted Book (Sharpness I)", ShopData.withVariant("&9Enchanted Book", "Sharpness I"));
        assertEquals("&fWheat", ShopData.withVariant("&fWheat", null));
        assertEquals("&fWheat", ShopData.withVariant("&fWheat", " "));
        List<String> titles = new java.util.ArrayList<>();
        for (Entry e : shop(shops(), "librarian").entries)
            if (e.item.startsWith("ENCHANTMENT_")) {
                String rest = e.item.substring("ENCHANTMENT_".length());
                int u = rest.lastIndexOf('_');
                titles.add(ShopData.withVariant("Enchanted Book",
                        ShopData.enchantLabel(rest.substring(0, u), Integer.parseInt(rest.substring(u + 1)))));
            }
        assertTrue(titles.size() >= 8, "librarian sells " + titles.size() + " books");
        assertTrue(ShopData.ambiguous(titles).isEmpty(), "librarian books still collide: " + titles);
    }

    // ---- wave 3: requirements, key prices, currencies -------------------------------------------------

    @Test
    void requirementKeysJoinShopAndLine() {
        assertEquals(List.of("faction:mage", "faction:mage:3000"), ShopData.requirementKeys("faction:mage", "faction:mage:3000"));
        assertEquals("faction:mage&faction:mage:3000", ShopData.requirement("faction:mage", "faction:mage:3000"));
        assertEquals(List.of("a:1", "b:2"), ShopData.requirementKeys("a:1&b:2", "a:1"));
        assertNull(ShopData.requirement(null, " "));
        assertEquals("skill:combat:5", ShopData.requirement(null, "skill:combat:5"));
    }

    @Test
    void validateRejectsMalformedRequirement() throws IOException {
        List<Shop> all = shops();
        assertTrue(ShopData.validate(all).isEmpty(), "shipped data invalid: " + ShopData.validate(all));
        Shop s = all.get(0);
        String was = s.entries.get(0).req;
        s.entries.get(0).req = "Faction Mage!";
        assertFalse(ShopData.validate(all).isEmpty(), "a malformed requirement passed validation");
        s.entries.get(0).req = was;
    }

    /** research 04 §4.3: keys come from your own faction's emissary, rep 0 / 1k / 3k / 7k / 12k, 200k..3M coins. */
    @Test
    void envoysAreFactionGatedWithResearchRep() throws IOException {
        List<Shop> all = shops();
        String[] keys = {"KUUDRA_TIER_KEY", "KUUDRA_HOT_TIER_KEY", "KUUDRA_BURNING_TIER_KEY", "KUUDRA_FIERY_TIER_KEY", "KUUDRA_INFERNAL_TIER_KEY"};
        int[] rep = {0, 1000, 3000, 7000, 12000};
        double[] coins = {200_000, 400_000, 750_000, 1_500_000, 3_000_000};
        for (String fac : new String[]{"mage", "barbarian"}) {
            Shop s = shop(all, fac + "_envoy");
            assertEquals("faction:" + fac, s.req, s.id + " is not gated to its faction");
            for (int i = 0; i < keys.length; i++) {
                Entry e = entry(s, keys[i]);
                assertEquals(coins[i], e.coins, 1e-9, s.id + " " + keys[i] + " price");
                if (rep[i] == 0) assertNull(e.req, s.id + " basic key needs no rep");
                else assertEquals("faction:" + fac + ":" + rep[i], e.req, s.id + " " + keys[i] + " rep gate");
            }
        }
    }

    /** Four keys of a tier craft one of the next: buying four must never cost less than buying the next one. */
    @Test
    void keyLadderIsNotACoinPrinter() throws IOException {
        String[] keys = {"KUUDRA_TIER_KEY", "KUUDRA_HOT_TIER_KEY", "KUUDRA_BURNING_TIER_KEY", "KUUDRA_FIERY_TIER_KEY", "KUUDRA_INFERNAL_TIER_KEY"};
        for (String fac : new String[]{"mage", "barbarian"}) {
            Shop s = shop(shops(), fac + "_envoy");
            for (int i = 0; i + 1 < keys.length; i++)
                assertTrue(4 * entry(s, keys[i]).coins >= entry(s, keys[i + 1]).coins,
                        s.id + ": 4 x " + keys[i] + " is cheaper than " + keys[i + 1]);
        }
    }

    /** The Stargazer's stock costs North Stars: the currency id the runtime maps onto mayors' NORTH_STAR item. */
    @Test
    void starTraderTradesInNorthStars() throws IOException {
        Shop s = shop(shops(), "star_trader");
        int n = 0;
        for (Entry e : s.entries) if (e.hasCurrency()) for (CurrencyCost c : e.currency) {
            assertEquals("north_star", c.id);
            assertTrue(c.amount > 0);
            n++;
        }
        assertTrue(n >= 15, "star trader has only " + n + " north-star lines");
        assertEquals(20, entry(s, "WHITE_GIFT_TALISMAN").currency.get(0).amount);
    }

    // ---- wave 5 (2026-10-06) ---------------------------------------------------------------------------

    /** Accessory and Hunting-attribute discounts add (3% + 10% = 13% off 1,000 = 870) and never pass 100%. */
    @Test
    void attributeDiscountAddsToAccessoryDiscount() {
        assertEquals(13, Pricing.combinedDiscount(3, 10), 1e-9);
        assertEquals(100, Pricing.combinedDiscount(80, 50), 1e-9);
        assertEquals(0, Pricing.combinedDiscount(-5, Double.NaN), 1e-9);
        assertEquals(870, Pricing.bundleCoins(1000, 1, 0, Pricing.combinedDiscount(3, 10), 1), 1e-9);
        // the anti-arbitrage floor still wins: wheat x3 at 7 with 13% off is still 18
        assertEquals(18, Pricing.bundleCoins(7, 3, 6, Pricing.combinedDiscount(3, 10), 1), 1e-9);
    }

    /** The shipped data wires Woodchucker / Bloodbat to exactly the Builder and the Bartender. */
    @Test
    void builderAndBartenderCarryTheirHuntingDiscount() throws IOException {
        List<Shop> all = shops();
        assertEquals("builder_discount", shop(all, "builder").discountAttr);
        assertEquals("bartender_discount", shop(all, "bartender").discountAttr);
        for (Shop s : all) if (!s.id.equals("builder") && !s.id.equals("bartender")) assertNull(s.discountAttr, s.id);
    }

    /** external.json: valid, the Community Shop is there, every command a bare word (never player text). */
    @Test
    void externalMerchantsAreValidAndIncludeTheCommunityShop() throws IOException {
        List<ShopData.External> ext;
        try (InputStream in = res("external.json")) {
            assertNotNull(in, "external.json not found");
            ext = ShopData.readExternal(in);
        }
        Set<String> ids = new HashSet<>();
        for (Shop s : shops()) ids.add(s.id);
        assertEquals(List.of(), ShopData.validateExternal(ext, ids));
        assertTrue(ext.stream().anyMatch(x -> x.id.equals("community_shop") && x.command.equals("cshop") && x.island.equals("hub")));
        ShopData.External bad = new ShopData.External();
        bad.id = "x"; bad.name = "X"; bad.island = "hub"; bad.command = "say hi";
        assertFalse(ShopData.validateExternal(List.of(bad), Set.of()).isEmpty(), "a command with a space must be refused");
    }

    /** The Island Decorator sells exactly the ten goods this module registers, each gated by its Social level. */
    @Test
    void decoratorStockIsRegisteredHereAndSocialGated() throws IOException {
        String txt;
        try (InputStream in = res("items.json")) {
            assertNotNull(in, "items.json not found");
            txt = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        Shop deco = shop(shops(), "decorator");
        assertEquals(10, deco.entries.size());
        for (Entry e : deco.entries) {
            assertTrue(txt.contains("\"id\": \"" + e.item + "\""), e.item + " is not in items.json");
            assertNotNull(e.req, e.item + " has no Social gate");
            assertTrue(e.req.startsWith("skill:social:"), e.item + ": " + e.req);
        }
        assertEquals("skill:social:8", entry(deco, "ROCK_PAPER_SHEARS").req);
        assertFalse(txt.contains("Rock Paper Shears"), "the reference name leaked into items.json");
    }

    /** Tic Tac Toe / Four in a Row boards: lines win, discs stack, a full board is full. */
    @Test
    void boardGamesFollowTheirRules() {
        Games.Grid t = new Games.Grid(3, 3, 3);
        t.set(0, 2, 2); t.set(1, 1, 2); t.set(2, 0, 2);
        assertEquals(2, t.winner(), "anti-diagonal");
        Games.Grid t2 = new Games.Grid(3, 3, 3);
        int[] v = {1, 2, 1, 1, 2, 2, 2, 1, 1};
        for (int i = 0; i < 9; i++) t2.set(i % 3, i / 3, v[i]);
        assertEquals(0, t2.winner());
        assertTrue(t2.full());
        Games.Grid c = new Games.Grid(7, 6, 4);
        assertEquals(5, c.dropRow(0));
        for (int x = 0; x < 3; x++) c.set(x, c.dropRow(x), 1);
        assertEquals(0, c.winner());
        c.set(3, c.dropRow(3), 1);
        assertEquals(1, c.winner(), "four along the bottom row");
        for (int i = 0; i < 6; i++) c.set(6, c.dropRow(6), 2);
        assertEquals(-1, c.dropRow(6), "a full column takes no more discs");
    }

    /** Rock Paper Snips: the least picked sign goes through; ties and silence never empty the tournament. */
    @Test
    void rockPaperSnipsLeastPickedWins() {
        java.util.Map<String, Games.Sign> p = new java.util.HashMap<>();
        p.put("a", Games.Sign.ROCK); p.put("b", Games.Sign.ROCK); p.put("c", Games.Sign.PAPER); p.put("d", Games.Sign.ROCK);
        assertEquals(Set.of("c"), Games.rpsRound(List.of("a", "b", "c", "d", "e"), p), "e did not pick and is out");
        java.util.Map<String, Games.Sign> same = new java.util.HashMap<>();
        same.put("a", Games.Sign.PAPER); same.put("b", Games.Sign.PAPER); same.put("c", Games.Sign.PAPER);
        assertEquals(Set.of("a", "b", "c"), Games.rpsRound(List.of("a", "b", "c"), same), "one sign only: nobody out");
        assertEquals(Set.of("a", "b"), Games.rpsRound(List.of("a", "b"), java.util.Map.of()), "nobody picked: nobody out");
        java.util.Map<String, Games.Sign> fin = new java.util.HashMap<>();
        fin.put("a", Games.Sign.ROCK); fin.put("b", Games.Sign.SHEARS);
        assertEquals(Set.of("a"), Games.rpsRound(List.of("a", "b"), fin), "final: rock blunts shears");
        fin.put("b", Games.Sign.PAPER);
        assertEquals(Set.of("b"), Games.rpsRound(List.of("a", "b"), fin), "final: paper wraps rock");
        fin.put("b", Games.Sign.ROCK);
        assertEquals(Set.of("a", "b"), Games.rpsRound(List.of("a", "b"), fin), "final tie replays");
    }

    // ---- envoy Leviathan keys: the Emissary reputation discount (audit 2026-10-06 K17 follow-up) ------------------

    @Test
    void envoyKeysTakeTheEmissaryDiscount() throws IOException {
        java.util.TreeMap<Integer, Integer> t = Pricing.parseEmissary(Pricing.DEFAULT_EMISSARY);
        assertEquals(0, Pricing.emissaryPercent(t, rep -> 999 >= rep));
        assertEquals(5, Pricing.emissaryPercent(t, rep -> 1000 >= rep));
        assertEquals(10, Pricing.emissaryPercent(t, rep -> 6999 >= rep));
        assertEquals(15, Pricing.emissaryPercent(t, rep -> 7000 >= rep));
        assertEquals(20, Pricing.emissaryPercent(t, rep -> 50_000 >= rep));
        assertEquals(0, Pricing.emissaryPercent(Pricing.parseEmissary(""), rep -> true), "empty dial = no discount");
        assertEquals(1_200_000, Pricing.emissaryCoins(1_500_000, 20), 1e-9);
        List<Shop> all = shops();
        for (String id : List.of("mage_envoy", "barbarian_envoy")) {
            Shop s = shop(all, id);
            assertEquals(id.replace("_envoy", ""), s.emissaryFaction, id);
            int keys = 0;
            for (Entry e : s.entries) {
                assertEquals(e.item.startsWith("KUUDRA_") && e.item.endsWith("TIER_KEY"), e.emissary, id + " " + e.item);
                if (e.emissary) keys++;
            }
            assertEquals(5, keys, id + " sells the five keys");
        }
    }
}
