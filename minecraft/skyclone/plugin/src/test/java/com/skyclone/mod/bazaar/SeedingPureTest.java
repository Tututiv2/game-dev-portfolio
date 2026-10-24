package com.skyclone.mod.bazaar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The Merchant Guild seeding trick (research s2.9): price floors, batch sizes, the dial grammar, the book rules. */
class SeedingPureTest {

    @Test
    void priceNeverUndercutsTheBotsTheNpcOrSitsOverAShop() {
        assertEquals(100, Seeding.price(10, 0, 0, 0, 100));        // plain: the nominal ask
        assertEquals(121, Seeding.price(10, 120, 0, 0, 100));      // one tick over the market maker's bid
        assertEquals(151, Seeding.price(10, 0, 15, 0, 100));       // over the NPC sell price
        assertEquals(80, Seeding.price(10, 0, 0, 8, 100));         // capped at the shop
        assertEquals(0, Seeding.price(10, 0, 15, 12, 100));        // the shop is under the floor: skip
        assertEquals(0, Seeding.price(0, 0, 0, 0, 100));           // never quoted
        assertEquals(90, Seeding.price(10, 0, 0, 0, 90));          // the dial discounts
        Random r = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            double nominal = Math.exp(r.nextDouble() * 14 - 2);
            long bid = r.nextBoolean() ? 0 : Pricing.tenthsDown(nominal * (0.5 + r.nextDouble()));
            double npc = r.nextBoolean() ? 0 : nominal * r.nextDouble() * 1.2;
            double shop = r.nextInt(4) == 0 ? nominal * (0.5 + r.nextDouble()) : 0;
            long p = Seeding.price(nominal, bid, npc, shop, 50 + r.nextDouble() * 100);
            if (p == 0) continue;
            assertTrue(p > bid, "never at or under the bots' bid");
            if (npc > 0) assertTrue(p > Pricing.tenthsUp(npc), "never at or under the NPC price");
            if (shop > 0) assertTrue(p <= Pricing.tenthsDown(shop), "never over an NPC shop");
        }
    }

    @Test
    void batchIsCappedByValueAndNeverZero() {
        assertEquals(640, Seeding.batch(640, 1.0, 100, 2_000_000));
        assertEquals(50, Seeding.batch(320, 1.0, 39_221.1, 2_000_000));   // a dear ticket: 2M / 39k
        assertEquals(1, Seeding.batch(16, 1.0, 5_000_000, 2_000_000));     // dearer than the cap: still one
        assertEquals(1280, Seeding.batch(640, 2.0, 0, 0));
        assertEquals(0, Seeding.batch(0, 1.0, 10, 0));
        assertEquals(Seeding.MAX_BATCH, Seeding.batch(70_000, 5, 0, 0));
    }

    @Test
    void dialEditsTheGeneratedList() {
        Map<String, Integer> m = Seeding.merge(Map.of("REVENANT_FLESH", 640, "WOLF_TOOTH", 640), "-wolf_tooth, null_sphere:100 SUMMONING_EYE", 64);
        assertEquals(640, m.get("REVENANT_FLESH"));
        assertFalse(m.containsKey("WOLF_TOOTH"));
        assertEquals(100, m.get("NULL_SPHERE"));
        assertEquals(64, m.get("SUMMONING_EYE"));
        assertEquals(2, Seeding.merge(Map.of("A", 1, "B", 2), "none", 64).size());     // the dial's "empty"
        assertEquals(1, Seeding.merge(Map.of("A", 1, "B", 2), "B:0", 64).size());      // :0 removes
        assertEquals(1, Seeding.merge(Map.of("A", 1), "C:abc,,", 64).size());           // a typo is skipped
    }

    @Test
    void dropTimer() {
        assertEquals(0, Seeding.untilNext(0, 1_000, 30));
        assertEquals(30 * 60_000L - 1_000, Seeding.untilNext(1, 1_001, 30));
        assertEquals(0, Seeding.untilNext(1, 1 + 30 * 60_000L, 30));
    }

    @Test
    void guildOffersSurviveAMarketMakerRequoteAndCountAgainstTheAllowance() {
        OrderBook b = new OrderBook("X");
        Order mm = new Order(-1, 1, "X", Order.Side.SELL, null, "mm", null, 100, 10, 0, Long.MAX_VALUE, true);
        Order guild = new Order(-2, 2, "X", Order.Side.SELL, null, Seeder.NAME, null, 100, 10, 0, Long.MAX_VALUE, false, true);
        assertTrue(guild.bot, "a guild offer is server stock");
        b.add(mm);
        b.add(guild);
        assertEquals(1, b.clearBots());
        assertEquals(guild, b.bestAsk(), "a re-quote must leave the guild's offer");
        // the hourly allowance against server stock covers the guild too
        OrderBook.Quote q = b.quote(Order.Side.BUY, 10, 0, null, 4);
        assertEquals(4, q.qty());
        var fills = b.take(Order.Side.BUY, 10, 0, null, 4, 1.25);
        assertEquals(4, fills.get(0).qty());
        assertEquals(0, guild.coinsPending, "server stock banks nothing pending");
        // a player at the same price arrived later: the guild keeps time priority
        Order player = new Order(5, 3, "X", Order.Side.SELL, UUID.randomUUID(), "p", "prof", 100, 5, 0, Long.MAX_VALUE, false);
        b.add(player);
        assertEquals(guild, b.take(Order.Side.BUY, 1, 0, null, 99, 1.25).get(0).maker());
    }

    @Test
    void generatorMarkedProgressionGatingMaterials() throws IOException {
        Path p = Path.of("src/main/resources/data/bazaar/products.json");
        if (!Files.exists(p)) p = Path.of("plugin/src/main/resources/data/bazaar/products.json");
        int seeded = 0;
        boolean flesh = false;
        for (JsonElement e : JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            if (!o.has("seed")) continue;
            seeded++;
            int n = o.get("seed").getAsInt();
            assertTrue(n >= 16 && n <= 640, o.get("id") + " batch " + n);
            assertTrue(o.has("seedFor") && o.getAsJsonArray("seedFor").size() > 0, o.get("id") + " unlocks nothing");
            assertFalse(o.has("mm") && !o.get("mm").getAsBoolean(), o.get("id") + " is seeded but unpriced");
            assertFalse(o.has("mmSides") && "bid".equals(o.get("mmSides").getAsString()), o.get("id") + " is seeded but bid-only");
            assertNotEquals("ENCHANTMENTS", o.get("category").getAsString());
            if (o.get("id").getAsString().equals("REVENANT_FLESH")) flesh = true;
        }
        assertTrue(seeded >= 10 && seeded <= 40, "seeded " + seeded);
        assertTrue(flesh, "the first slayer's drop gates early gear: it must be seeded");
    }
}
