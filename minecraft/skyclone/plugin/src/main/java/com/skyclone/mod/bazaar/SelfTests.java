package com.skyclone.mod.bazaar;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.world.gen.Layout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code /sb selftest bazaar}: the data hangs together and the money maths gives the research numbers. */
final class SelfTests {

    private SelfTests() {}

    static void register() {
        Sky.commands().selfTest("bazaar.catalogue", SelfTests::catalogue);
        Sky.commands().selfTest("bazaar.live-products", SelfTests::live);
        Sky.commands().selfTest("bazaar.tax-and-matching", SelfTests::taxAndMatching);
        Sky.commands().selfTest("bazaar.mm-invariants", SelfTests::mmInvariants);
        Sky.commands().selfTest("bazaar.no-craft-arbitrage", SelfTests::recipeArbitrage);
        Sky.commands().selfTest("bazaar.book-integrity", () -> Bz.market.audit());
        Sky.commands().selfTest("bazaar.npc-anchor", SelfTests::anchor);
        Sky.commands().selfTest("bazaar.icons-build", SelfTests::icons);
        Sky.commands().selfTest("bazaar.book-ladder", SelfTests::bookLadder);
        Sky.commands().selfTest("bazaar.all-reachable", SelfTests::reachable);
        Sky.commands().selfTest("bazaar.event-shocks", SelfTests::eventShocks);
        Sky.commands().selfTest("bazaar.special-bazaar", SelfTests::specialBazaar);
        Sky.commands().selfTest("bazaar.access-gate", SelfTests::accessGate);
        Sky.commands().selfTest("bazaar.seed-catalogue", SelfTests::seedCatalogue);
        Sky.commands().selfTest("bazaar.seed-no-arbitrage", SelfTests::seedNoArbitrage);
        Sky.commands().selfTest("bazaar.seed-never-accumulates", SelfTests::seedNoAccumulation);
    }

    /**
     * The Merchant Guild list (generator + dial) names real products that can carry a supply offer, the generator
     * found a sensible number of progression-gating materials, and at least one of them is live in this game.
     */
    static String seedCatalogue() {
        int gen = 0;
        for (Catalogue.Product p : Bz.cat.products.values()) {
            if (p.seed <= 0) continue;
            gen++;
            if (p.seedFor == null || p.seedFor.isEmpty()) return p.id + " is seeded but unlocks nothing (generator)";
            if (MarketMaker.bidsOnly(p) || !p.mm) return p.id + " is seeded but has no trusted ask price";
        }
        if (gen < 10) return "only " + gen + " progression-gating materials in products.json (generator found 30)";
        int live = 0;
        for (var e : Bz.seeder.list().entrySet()) {
            Catalogue.Product p = Bz.cat.product(e.getKey());
            if (p == null) return "bazaar.seed-products names unknown product " + e.getKey();
            if (e.getValue() <= 0) return e.getKey() + " has a batch of " + e.getValue();
            if (p.live) live++;
        }
        if (BzDials.seedEnabled.get() && live == 0 && Bz.cat.liveProducts >= 50)
            return "none of the " + Bz.seeder.list().size() + " Merchant Guild materials is registered by any module";
        return null;
    }

    /**
     * Every guild offer on the book: above the market maker's best bid (and the maker's own quoted bid), above the
     * NPC sell price, not above an NPC shop, and known to the recipe caps (cost graph). Plus the pure price floors.
     */
    static String seedNoArbitrage() {
        if (Seeding.price(10, 120, 0, 0, 100) != 121) return "guild price must sit one tick over the market maker's bid";
        if (Seeding.price(10, 0, 15, 0, 100) != 151) return "guild price must sit over the NPC sell price";
        if (Seeding.price(10, 0, 15, 12, 100) != 0) return "a shop below the floor must skip the product";
        for (var e : Bz.seeder.resting.entrySet()) {
            Order o = e.getValue();
            if (!o.open()) continue;
            String id = e.getKey();
            Catalogue.Product p = Bz.cat.product(id);
            OrderBook b = Bz.market.book(id);
            long mmBid = b.best(Order.Side.BUY, x -> x.bot && !x.guild);
            if (mmBid > 0 && mmBid >= o.price) return id + ": market maker bids " + Pricing.fmt(mmBid) + " >= guild ask " + Pricing.fmt(o.price);
            MarketMaker.State s = Bz.mm.states.get(id);
            if (s != null && s.bid > 0 && s.bid >= o.price) return id + ": market maker quote bid " + Pricing.fmt(s.bid) + " >= guild ask " + Pricing.fmt(o.price);
            if (p != null && p.npcSell > 0 && o.price <= Pricing.tenthsUp(p.npcSell)) return id + ": guild ask " + Pricing.fmt(o.price) + " not above NPC sell " + p.npcSell;
            double shop = p == null ? 0 : MarketMaker.shopPrice(p);
            if (shop > 0 && o.price > Pricing.tenthsDown(shop)) return id + ": guild ask " + Pricing.fmt(o.price) + " above the NPC shop " + shop;
            double acq = Bz.mm.acquire(id);
            if (BzDials.mmRecipeCaps.get() && acq > 0 && acq > o.price / 10.0 + 0.11)
                return id + ": recipe caps think it costs " + acq + " but the guild sells it at " + Pricing.fmt(o.price);
        }
        return null;
    }

    /** At most one guild offer per book, never more than one batch: a quiet week is one batch, not a week of them. */
    static String seedNoAccumulation() {
        Map<String, Integer> want = Bz.seeder.list();
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            OrderBook b = Bz.market.book(e.getKey());
            int n = 0;
            long units = 0;
            for (Order o : b.asks()) if (o.guild) { n++; units += o.remaining(); }
            for (Order o : b.bids()) if (o.guild) return e.getKey() + ": the guild has a BUY order";
            if (n > 1) return e.getKey() + ": " + n + " guild offers on one book";
            int cap = Seeding.batch(e.getValue(), BzDials.seedBatchMult.get(), 0, 0);
            if (units > cap) return e.getKey() + ": guild offers " + units + " units, batch is " + cap;
        }
        Map<String, Integer> m = Seeding.merge(Map.of("A", 10), "-A, B:5, C", 64);
        if (m.containsKey("A") || m.get("B") != 5 || m.get("C") != 64) return "bazaar.seed-products edits parse wrong: " + m;
        return null;
    }

    /**
     * Every event the shock dial names is a real calendar event and every product it names is in the catalogue (a
     * typo would silently never fire). Guild floors stay under the NPC shop price and over the NPC sell price.
     */
    static String eventShocks() {
        java.util.Set<String> events = new java.util.HashSet<>();
        for (com.skyclone.api.time.CalendarEvent e : Sky.calendar().events()) events.add(e.id);
        for (EventShocks.Shock s : EventShocks.current().all()) {
            if (!events.contains(s.event())) return "bazaar.event-shocks names event '" + s.event() + "', which no module registers on the calendar";
            for (String id : s.products()) if (Bz.cat.product(id) == null) return "bazaar.event-shocks names unknown product " + id;
        }
        for (Catalogue.Product p : Bz.cat.products.values()) {
            double shop = MarketMaker.shopPrice(p);
            double f = MarketMaker.guildFloor(p, shop);
            if (p.npcSell > 0 && f < p.npcSell) return p.id + ": guild floor " + f + " below NPC sell " + p.npcSell;
            if (f > p.npcSell && shop > 0 && f >= shop) return p.id + ": guild floor " + f + " reaches the NPC shop price " + shop;
        }
        return null;
    }

    /**
     * The access dial resolves to something the requirement registry knows. An unknown prefix fails CLOSED, so a
     * typo (or the old trap: typing "none", which was stored literally) would shut the Bazaar for everyone.
     */
    static String accessGate() {
        for (String dial : new String[] {"access-requirement", "remote-requirement"}) {
            String raw = (dial.startsWith("access") ? BzDials.accessRequirement : BzDials.remoteRequirement).get();
            String key = Gate.key(raw);
            for (String part : key.split("&")) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                String prefix = t.split(":", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
                if (prefix.equals("sblevel") && !Sky.has(com.skyclone.api.feature.Features.SkyLevel.class)) continue; // fails open by design
                if (t.equals("cookie") && !Sky.items().exists("BOOSTER_COOKIE")) continue;                              // fails open by design
                String d = Sky.requirements().describe(t);
                if (d != null && d.startsWith("&c" + prefix))
                    return "bazaar." + dial + " = '" + raw + "': no module owns requirement prefix '" + prefix
                            + "', so it would lock the Bazaar for everyone (use a number for a SkyBlock Level, or none)";
            }
        }
        if (!Gate.key("none").isEmpty() || !Gate.key("\"\"").isEmpty() || !Gate.key("3").equals("sblevel:3"))
            return "Gate.key does not map none / \"\" / 3 to everyone / everyone / sblevel:3";
        return null;
    }

    /** The Special Bazaar has something to sell, and its product list only names real products. */
    static String specialBazaar() {
        for (String id : BzDials.ironmanProducts.get().split(",")) {
            if (id.isBlank()) continue;
            if (Bz.cat.product(id.trim().toUpperCase(java.util.Locale.ROOT)) == null) return "bazaar.ironman-products names unknown product " + id.trim();
        }
        if (Bz.inList(BzDials.blockedModes.get(), "stranded") && Bz.inList(BzDials.specialModes.get(), "stranded"))
            return "stranded is in both bazaar.blocked-modes and bazaar.special-modes (blocked wins; drop it from one)";
        return null;
    }

    /** 6 categories, ~2,197 generated products, every group's products exist and sit in exactly one group. */
    static String catalogue() {
        Catalogue c = Bz.cat;
        if (c.categories.size() != 6) return "expected 6 categories, got " + c.categories.size();
        if (c.dataProducts < 2000) return "only " + c.dataProducts + " products in products.json (research snapshot has 2,197)";
        Map<String, String> seen = new HashMap<>();
        for (Catalogue.Group g : c.groups.values()) {
            if (!c.categories.containsKey(g.category)) return "group " + g.key + " has unknown category " + g.category;
            for (String id : g.products) {
                Catalogue.Product p = c.product(id);
                if (p == null) return "group " + g.key + " lists unknown product " + id;
                if (!p.group.equals(g.key)) return id + " says group " + p.group + " but sits in " + g.key;
                String prev = seen.put(id, g.key);
                if (prev != null) return id + " is in two groups: " + prev + " and " + g.key;
            }
        }
        for (Catalogue.Product p : c.products.values()) if (!seen.containsKey(p.id)) return p.id + " is in no group";
        // research numbers carried by the data (items.json / bazaar snapshot)
        Catalogue.Product ed = c.product("ENCHANTED_DIAMOND");
        if (ed == null || Math.abs(ed.npcSell - 1280) > 0.01) return "ENCHANTED_DIAMOND npc sell should be 1,280 (research), got " + (ed == null ? "missing" : ed.npcSell);
        return null;
    }

    /** Live products exist in the item registry, are stackable, and the vanilla-backed basics are tradeable. */
    static String live() {
        Catalogue c = Bz.cat;
        if (c.liveProducts < 50) return "only " + c.liveProducts + " live products - did the item modules load?";
        for (Catalogue.Product p : c.products.values()) {
            if (!p.live) continue;
            ItemDef d = Sky.items().def(p.id);
            if (d == null) return p.id + " is live but not registered";
            if (d.isUnique()) return p.id + " is live but unique (unstackable items belong to the Auction House)";
        }
        for (String must : List.of("DIAMOND", "COBBLESTONE", "IRON_INGOT", "GOLD_INGOT", "COAL", "EMERALD")) {
            if (!c.isLive(must)) return "basic product " + must + " is not live";
        }
        for (Catalogue.Group g : c.groups.values()) {
            if (g.live.isEmpty()) continue;
            if (c.groupName(g).isBlank()) return "group " + g.key + " has no display name";
        }
        return null;
    }

    /** Research 05: 1.25% tax on 1,000 coins of proceeds = 12.5, seller gets 987.5; price-time priority. */
    static String taxAndMatching() {
        if (Pricing.tax(10_000, 1.25) != 125) return "1.25% of 1,000 coins should be 12.5, got " + Pricing.coins(Pricing.tax(10_000, 1.25));
        if (Pricing.afterTax(10_000, 1.25) != 9_875) return "seller should get 987.5 of 1,000";
        if (Pricing.afterTax(10_000, 1.0) != 9_900) return "Flipper II rate 1.0% should leave 990";
        OrderBook b = new OrderBook("TEST");
        UUID a = UUID.randomUUID(), z = UUID.randomUUID();
        Order s1 = new Order(1, 1, "TEST", Order.Side.SELL, a, "a", "p", 50, 10, 0, Long.MAX_VALUE, false);
        Order s2 = new Order(2, 2, "TEST", Order.Side.SELL, z, "z", "p", 50, 10, 0, Long.MAX_VALUE, false);
        Order s3 = new Order(3, 3, "TEST", Order.Side.SELL, z, "z", "p", 49, 5, 0, Long.MAX_VALUE, false);
        b.add(s1); b.add(s2); b.add(s3);
        OrderBook.Quote q = b.quote(Order.Side.BUY, 12, 0, null, Integer.MAX_VALUE);
        if (q.qty() != 12 || q.cost() != 5 * 49 + 7 * 50) return "walk-the-book quote wrong: " + q;
        List<OrderBook.Fill> f = b.take(Order.Side.BUY, 12, 0, null, Integer.MAX_VALUE, 1.25);
        if (f.size() != 2 || f.get(0).maker() != s3 || f.get(1).maker() != s1) return "price-time priority broken: cheapest first, then oldest";
        if (s1.filled != 7 || s2.filled != 0 || s3.state != Order.State.CLOSED) return "fill accounting wrong";
        if (s1.coinsPending != Pricing.afterTax(350, 1.25)) return "maker proceeds should be net of tax";
        return null;
    }

    /**
     * Every market-maker quote: bid below ask, shop ceiling, NPC floor (where nothing outranks it), the reference
     * cap on bids, and one-sided products quoting only their trusted side.
     */
    static String mmInvariants() {
        if (!BzDials.mmEnabled.get()) return null;
        int checked = 0;
        for (Map.Entry<String, MarketMaker.State> e : Bz.mm.states.entrySet()) {
            Catalogue.Product p = Bz.cat.product(e.getKey());
            MarketMaker.State s = e.getValue();
            if (p == null || !p.live || !p.mm || (s.bid <= 0 && s.ask <= 0)) continue;
            checked++;
            String id = e.getKey();
            if (s.bid > 0 && s.ask > 0 && s.bid >= s.ask) return id + ": bid " + Pricing.fmt(s.bid) + " >= ask " + Pricing.fmt(s.ask);
            if (s.shop > 0 && s.ask > Pricing.tenthsDown(s.shop) + 1) return id + ": ask " + Pricing.fmt(s.ask) + " above shop price " + s.shop;
            if (s.bid > 0 && s.bidCap > 0 && s.bid > Pricing.tenthsDown(s.bidCap) + 1) return id + ": bid " + Pricing.fmt(s.bid) + " above its cap " + s.bidCap;
            double refCap = Bz.mm.ref(p) * BzDials.mmBidMaxRef.get();
            if (s.bid > Pricing.tenthsDown(refCap) + 1) return id + ": bid " + Pricing.fmt(s.bid) + " above the reference cap " + refCap;
            if (MarketMaker.bidsOnly(p) && s.ask > 0) return id + " is bid-only but asks " + Pricing.fmt(s.ask);
            if ((MarketMaker.bidsOnly(p) || MarketMaker.asksOnly(p)) && s.bid > Pricing.tenthsUp(p.npcSell)) {
                return id + " is one-sided but bids " + Pricing.fmt(s.bid) + " above the NPC price " + p.npcSell;
            }
            boolean floorHolds = BzDials.mmNpcFloor.get() && p.npcSell > 0 && s.bid > 0
                    && (s.bidCap <= 0 || s.bidCap >= p.npcSell) && (s.shop <= 0 || p.npcSell <= s.shop * 0.97);
            if (floorHolds && s.bid < Pricing.tenthsDown(p.npcSell)) return id + ": bid " + Pricing.fmt(s.bid) + " below NPC sell " + p.npcSell;
        }
        if (checked == 0 && Bz.cat.liveProducts > 0) return "no market-maker quotes at all";
        return null;
    }

    /** Two level-N books combine into N+1: the bots must never bid more for N+1 than two N cost from them. */
    static String bookLadder() {
        if (!BzDials.mmEnabled.get() || !BzDials.mmRecipeCaps.get()) return null;
        for (Map.Entry<String, Map<Integer, String>> e : Bz.cat.bookLadder.entrySet()) {
            for (Map.Entry<Integer, String> lv : e.getValue().entrySet()) {
                String below = e.getValue().get(lv.getKey() - 1);
                if (below == null || !MarketMaker.combinable(e.getKey(), lv.getKey())) continue;
                MarketMaker.State s = Bz.mm.states.get(lv.getValue());
                double two = 2 * Bz.mm.acquire(below);
                if (s == null || s.bid <= 0 || two <= 0) continue;
                if (s.bid / 10.0 > two * 1.02 + 0.2) {
                    return lv.getValue() + ": bots bid " + Pricing.fmt(s.bid) + " but two " + below + " cost " + two;
                }
            }
        }
        return null;
    }

    /** The menus never lose a product: every live product sits on exactly one page of exactly one live group. */
    static String reachable() {
        int onPages = 0;
        for (Catalogue.Group g : Bz.cat.groups.values()) {
            if (g.live.isEmpty()) continue;
            if (!Bz.cat.liveGroups(g.category).contains(g)) return "group " + g.key + " is live but not shown in " + g.category;
            onPages += g.live.size();
        }
        if (onPages != Bz.cat.liveProducts) return onPages + " products on group pages but " + Bz.cat.liveProducts + " live";
        if (Bz.market.maxInstant("ENDER_PEARL") > Bz.market.maxInstant("DIAMOND")
                && Inv.maxStack("ENDER_PEARL") < Inv.maxStack("DIAMOND")) return "instant-buy cap does not scale with stack size";
        return null;
    }

    /** Buying a recipe's inputs from the market makers and selling the result to them must never profit. */
    static String recipeArbitrage() {
        if (!BzDials.mmEnabled.get() || !BzDials.mmRecipeCaps.get()) return null;
        if (!Bz.mm.capsCurrent()) return "recipe caps were built before every module registered its recipes (stale graph)";
        for (Map.Entry<String, MarketMaker.State> e : Bz.mm.states.entrySet()) {
            MarketMaker.State s = e.getValue();
            if (s.bid <= 0) continue;
            for (Recipe r : Sky.recipes().producing(e.getKey())) {
                if (r.type != Recipe.Type.CRAFTING && r.type != Recipe.Type.FORGE) continue;
                double cost = Bz.mm.recipeCost(r);
                if (cost <= 0 || r.amount <= 0) continue;
                double out = s.bid / 10.0 * r.amount;
                // 2% slack: asks move a little between re-quotes (drift, stock skew); real arbitrage needs far more
                if (out > cost * 1.02 + 0.11 * r.amount) {
                    return e.getKey() + ": bid x" + r.amount + " = " + out + " > inputs at ask " + cost + " (recipe " + r.id + ")";
                }
            }
        }
        return null;
    }

    static String anchor() {
        if (Sky.worlds().island("hub") == null) return null;   // hub module not installed: nothing to check
        Layout l = Sky.worlds().layout("hub");
        if (l == null) return null;
        if (Sky.worlds().anchor("hub", "npc.bazaar") == null) return "hub layout has no npc.bazaar anchor (the Bazaar NPC will stand at spawn)";
        return null;
    }

    /** Every live group and product icon renders (names, lore, prices) without throwing. */
    static String icons() {
        int n = 0;
        for (Catalogue.Group g : Bz.cat.groups.values()) {
            if (g.live.isEmpty()) continue;
            BazaarMenus.groupIcon(null, g);
            n++;
        }
        for (Catalogue.Product p : Bz.cat.products.values()) {
            if (!p.live) continue;
            BazaarMenus.productIcon(null, p.id, true);
        }
        InfoMenus.help();
        return n == 0 && Bz.cat.liveProducts > 0 ? "no live groups" : null;
    }
}
