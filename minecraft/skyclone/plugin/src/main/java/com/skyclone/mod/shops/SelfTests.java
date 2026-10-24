package com.skyclone.mod.shops;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.shops.ShopData.Entry;
import com.skyclone.mod.shops.ShopData.Shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** {@code /sb selftest shops}: does the data hang together on the live server. */
final class SelfTests {

    private SelfTests() {}

    /** The merchants a new player meets in the first hour: every line they stock must exist. */
    static final Set<String> STARTER = Set.of("farm_merchant", "mine_merchant", "lumber_merchant", "fish_merchant",
            "adventurer", "weaponsmith", "armorsmith", "builder");

    static void register() {
        Sky.commands().selfTest("shops.data-valid", () -> {
            if (Shops.BY_ID.size() < 60) return "only " + Shops.BY_ID.size() + " merchants loaded (expected 70+)";
            List<String> bad = ShopData.validate(new ArrayList<>(Shops.all()));
            return bad.isEmpty() ? null : bad.size() + " problem(s): " + String.join("; ", bad.subList(0, Math.min(8, bad.size())));
        });

        Sky.commands().selfTest("shops.formulas", () -> {
            // research 05 §4.1: 640 per item per merchant per day, x10 under Shopping Spree
            if (Pricing.dailyLimit(640, false, 10) != 640) return "daily limit != 640";
            if (Pricing.dailyLimit(640, true, 10) != 6400) return "spree limit != 6,400";
            // research 06: Seal of the Family = 3% off; highest discount wins, they do not stack
            if (Math.abs(Pricing.bundleCoins(1000, 1, 0, Pricing.bestDiscount(true, true, true, 1, 2, 3), 1) - 970) > 1e-9)
                return "3% discount on 1,000 is not 970";
            // anti-arbitrage: 3 wheat listed at 7 coins but wheat sells for 6 each -> floored at 18
            if (Math.abs(Pricing.bundleCoins(7, 3, 6, 0, 1) - 18) > 1e-9) return "wheat x3 floor is not 18";
            if (Math.abs(Pricing.bundleCoins(100, 1, 1, 0, 1) - 100) > 1e-9) return "an unclamped price changed";
            if (Pricing.allowedBundles(10, 3, 630, 640) != 3) return "allowedBundles(10 x3, 630/640) != 3";
            Pricing.Buyback<String> bb = new Pricing.Buyback<>();
            for (int i = 0; i < 12; i++) bb.push("x" + i, "X", 1, i, 0, "s", 10);
            if (bb.size() != 10) return "buyback keeps " + bb.size() + " (expected 10)";
            if (!bb.list(15 * 60_000L + 1, 15 * 60_000L).isEmpty()) return "buyback did not expire after 15 minutes";
            // the shipped data carries the research prices
            String r = expect("farm_merchant", "WHEAT", 3, 7);
            if (r == null) r = expect("weaponsmith", "UNDEAD_SWORD", 1, 100);
            if (r == null) r = expect("armorsmith", "ROSETTA_HELMET", 1, 1050);
            if (r == null) r = expect("adventurer", "ZOMBIE_TALISMAN", 1, 500);
            return r;
        });

        Sky.commands().selfTest("shops.no-arbitrage", () -> {
            List<String> bad = new ArrayList<>();
            int clamped = 0;
            for (Shop s : Shops.all()) for (Entry e : s.entries) {
                String id = Shops.resolve(e);
                if (id == null || e.coins <= 0) continue;
                double each = Trade.npcSellEach(id);
                if (Pricing.clamped(e.coins, e.amount, each)) clamped++;
                double eff = Trade.bundleCoins(null, e);
                if (each > 0 && eff + 1e-9 < each * e.amount) bad.add(s.id + "/" + id + " " + eff + " < " + (each * e.amount));
            }
            if (!bad.isEmpty()) return bad.size() + " merchant price(s) below the NPC sell value: " + String.join(", ", bad.subList(0, Math.min(6, bad.size())));
            Sky.log().info("[shops] no-arbitrage: " + clamped + " listed price(s) lifted to the NPC sell floor");
            return null;
        });

        Sky.commands().selfTest("shops.starter-stock-exists", () -> {
            List<String> miss = new ArrayList<>();
            for (String id : STARTER) {
                Shop s = Shops.get(id);
                if (s == null) { miss.add(id + "(no shop)"); continue; }
                for (Entry e : s.entries) if (Shops.resolve(e) == null) miss.add(id + ":" + e.item);
            }
            return miss.isEmpty() ? null : miss.size() + " starter stock id(s) not registered by any module: "
                    + String.join(", ", miss.subList(0, Math.min(12, miss.size())));
        });

        Sky.commands().selfTest("shops.every-merchant-has-stock", () -> {
            List<String> empty = new ArrayList<>();
            for (Shop s : Shops.all()) if (s.contract() && Shops.status(s) != Shops.Status.YIELDED && Shops.availableCount(s) == 0) empty.add(s.id);
            return empty.isEmpty() ? null : "contract merchants with nothing resolvable to sell: " + empty;
        });

        Sky.commands().selfTest("shops.contract-anchors", () -> {
            List<String> miss = new ArrayList<>();
            for (Shop s : Shops.all()) {
                if (!s.contract() || Shops.status(s) == Shops.Status.YIELDED) continue;
                Layout l = Shops.layout(s.island);
                if (l == null) continue;   // island not built (or module absent): nothing to check against
                if (l.anchor(s.anchor) == null) miss.add(s.island + "/" + s.anchor);
            }
            return miss.isEmpty() ? null : "contract anchors missing in built layouts: " + miss;
        });

        Sky.commands().selfTest("shops.npcs-stationed", () -> {
            List<String> bad = new ArrayList<>();
            for (Shop s : Shops.all()) {
                Shops.Status st = Shops.status(s);
                if (s.contract() && st == Shops.Status.PENDING) bad.add(s.id);
                if (st == Shops.Status.STATIONED && Sky.npcs().def(Shops.npcId(s)) == null) bad.add(s.id + "(not registered)");
            }
            return bad.isEmpty() ? null : "contract merchants not placed: " + bad;
        });

        // playtest 2026-10-03: the Builder's Stone tab showed two "Smooth Sandstone" lines (legacy SANDSTONE:2 is
        // the modern Cut Sandstone), and Carved Pumpkin / Blue Orchid / Rose Bush resolved to other items entirely
        Sky.commands().selfTest("shops.distinct-lines", () -> {
            List<String> bad = new ArrayList<>();
            for (Shop s : Shops.all()) {
                List<String> tabs = new ArrayList<>();
                if (s.groups == null || s.groups.isEmpty()) tabs.add(null);
                else for (ShopData.Group g : s.groups) tabs.add(g.id);
                for (String tab : tabs) {
                    List<Entry> view = Shops.available(s, tab);
                    List<String> shown = new ArrayList<>();
                    Set<Entry> amb = Shops.ambiguousLines(view);
                    for (Entry e : view) shown.add(Shops.lineName(e, amb.contains(e)));
                    for (int i : ShopData.ambiguous(shown)) bad.add(s.id + (tab == null ? "" : "/" + tab) + ": " + view.get(i).item
                            + " shows as " + com.skyclone.api.ui.Text.strip(shown.get(i)));
                    for (Entry e : view) {
                        // the title on the actual icon, not just the computed label (books are named "Enchanted
                        // Book" by their module; only our rename says which one)
                        boolean a = amb.contains(e);
                        if (Shops.renamed(e, a)) {
                            org.bukkit.inventory.ItemStack icon = ShopMenus.labelled(Sky.items().create(Shops.resolve(e), 1), e, a);
                            org.bukkit.inventory.meta.ItemMeta meta = icon == null ? null : icon.getItemMeta();
                            String got = meta == null || meta.displayName() == null ? "" : com.skyclone.api.ui.Text.plain(meta.displayName());
                            String want = com.skyclone.api.ui.Text.strip(Shops.lineName(e, a));
                            if (!got.equals(want)) bad.add(s.id + ": " + e.item + " icon reads '" + got + "', not '" + want + "'");
                        }
                        if (!e.item.startsWith("@")) continue;
                        org.bukkit.Material m = org.bukkit.Material.matchMaterial(e.item.substring(1));
                        String id = Shops.resolve(e);
                        if (m != null && !Shops.isPlainBlock(id, m)) bad.add(s.id + ": " + e.item + " sells " + id + ", not that block");
                    }
                }
            }
            return bad.isEmpty() ? null : bad.size() + " line(s): " + String.join("; ", bad.subList(0, Math.min(8, bad.size())));
        });

        Sky.commands().selfTest("shops.menus-build", () -> {
            int built = 0;
            for (Shop s : Shops.all()) {
                Menu m = ShopMenus.shopMenu(null, new ShopMenus.Session(s, true, null, false), null, 0);
                m.redraw();
                Menu view = ShopMenus.shopMenu(null, new ShopMenus.Session(s, false, "&eVisit to trade.", true), null, 0);
                view.redraw();
                if (!s.entries.isEmpty()) {
                    Entry e = null;
                    for (Entry x : s.entries) if (Shops.resolve(x) != null) { e = x; break; }
                    if (e != null) {
                        Menu o = ShopMenus.optionsMenu(null, new ShopMenus.Session(s, true, null, false), null, e);
                        o.redraw();
                        if (o.handler(o.closeSlot() - 1) == null) return s.id + ": Shop Trading Options has no Go Back after a draw";
                    }
                }
                built++;
            }
            Shop any = Shops.get("farm_merchant");
            if (any != null) {
                ShopMenus.sellBox(null, new ShopMenus.Session(any, true, null, false), null).redraw();
                Menu bb = ShopMenus.buybackMenu(null, new ShopMenus.Session(any, true, null, false), null);
                bb.redraw();
                if (bb.handler(bb.closeSlot() - 1) == null) return "Buyback has no Go Back after a draw";
            }
            ShopMenus.directory(null, 0);
            return built == Shops.BY_ID.size() ? null : "built " + built + " of " + Shops.BY_ID.size();
        });

        // A merchant this module runs must never show a cost nobody can pay: North Stars are the NORTH_STAR item
        // (mayors). Pelt lines belong to merchants farming stands on (yielded), so they are not ours to sell.
        Sky.commands().selfTest("shops.currencies-spendable", () -> {
            List<String> bad = new ArrayList<>();
            int spendable = 0;
            for (Shop s : Shops.all()) {
                if (Shops.status(s) == Shops.Status.YIELDED) continue;
                for (Entry e : Shops.available(s, null)) if (e.hasCurrency()) for (ShopData.CurrencyCost c : e.currency) {
                    if (Shops.currencySupported(c.id)) spendable++;
                    else bad.add(s.id + "/" + e.item + " (" + c.id + ")");
                }
            }
            if (!bad.isEmpty()) return bad.size() + " line(s) cost a currency nobody exposes: " + String.join(", ", bad.subList(0, Math.min(8, bad.size())));
            Shop star = Shops.get("star_trader");
            if (star != null && Shops.status(star) != Shops.Status.YIELDED && spendable == 0) return "no North Star line resolved";
            return null;
        });

        // Every requirement key a line uses has a resolver (an unknown prefix fails closed: the line is unbuyable).
        Sky.commands().selfTest("shops.requirements-known", () -> {
            List<String> bad = new ArrayList<>();
            int gated = 0;
            for (Shop s : Shops.all()) for (Entry e : s.entries) {
                for (String k : ShopData.requirementKeys(s.req, e.req)) {
                    gated++;
                    String d = Shops.describeReq(k);
                    // core describes a key with no resolver as the bare key in red
                    if (d == null || com.skyclone.api.ui.Text.strip(d).trim().equals(k)) bad.add(s.id + ": " + k);
                }
            }
            if (gated == 0) return "no gated line at all (the envoys should be faction-gated)";
            List<String> uniq = new ArrayList<>(new java.util.LinkedHashSet<>(bad));
            return uniq.isEmpty() ? null : "requirement keys without a resolver: " + String.join(", ", uniq.subList(0, Math.min(8, uniq.size())));
        });

        // NPC_SHOP recipes: registered, priced at the floored price, never below what the merchants pay back.
        Sky.commands().selfTest("shops.recipes", () -> {
            if (!ShopDials.RECIPES.get()) return null;
            List<String> bad = new ArrayList<>();
            int n = 0;
            for (com.skyclone.api.item.Recipe r : Sky.recipes().byType(com.skyclone.api.item.Recipe.Type.NPC_SHOP)) {
                if (r.id == null || !r.id.startsWith("shops:")) continue;
                n++;
                if (!Sky.items().exists(r.result)) { bad.add(r.id + " -> " + r.result); continue; }
                long coins = 0;
                for (String in : r.inputs) {
                    int c = in.lastIndexOf(':');
                    String id = c < 0 ? in : in.substring(0, c);
                    long amt = c < 0 ? 1 : Long.parseLong(in.substring(c + 1));
                    if (id.equals("COINS")) coins += amt;
                    else if (!Sky.items().exists(id)) bad.add(r.id + " needs " + id);
                }
                double back = Trade.npcSellEach(r.result) * r.amount;
                if (coins > 0 && coins + 1e-9 < back) bad.add(r.id + " sells for " + coins + " < resell " + back);
            }
            if (n == 0) return "no shops: NPC_SHOP recipe registered";
            Shop farm = Shops.get("farm_merchant");
            if (farm != null && Shops.status(farm) != Shops.Status.YIELDED) {
                boolean wheat = false;
                for (com.skyclone.api.item.Recipe r : Sky.recipes().producing("WHEAT"))
                    if (r.type == com.skyclone.api.item.Recipe.Type.NPC_SHOP && r.id != null && r.id.startsWith("shops:farm_merchant:")) wheat = true;
                if (!wheat) bad.add("Wheat has no 'Sold by Farm Merchant' recipe");
            }
            return bad.isEmpty() ? null : bad.size() + " bad: " + String.join("; ", bad.subList(0, Math.min(8, bad.size())));
        });

        registerWave5();
    }

    /** Wave 5 (2026-10-06) self-tests. */
    static void registerWave5() {
        // Hunting attributes Builder Discount / Bartender Discount: wired to exactly those two merchants, and the
        // attribute keys are ones the hunting module really has.
        Sky.commands().selfTest("shops.hunting-discounts", () -> {
            Shop builder = Shops.get("builder"), bar = Shops.get("bartender");
            if (builder == null || !"builder_discount".equals(builder.discountAttr)) return "the Builder carries no builder_discount";
            if (bar == null || !"bartender_discount".equals(bar.discountAttr)) return "the Bartender carries no bartender_discount";
            for (Shop x : Shops.all()) if (x.discountAttr != null && x != builder && x != bar) return x.id + " has an unexpected discount attribute";
            if (Math.abs(Pricing.combinedDiscount(3, 10) - 13) > 1e-9) return "3% + 10% is not 13%";
            if (Math.abs(Pricing.combinedDiscount(80, 50) - 100) > 1e-9) return "discount not capped at 100%";
            if (Math.abs(Pricing.bundleCoins(1000, 1, 0, Pricing.combinedDiscount(3, 10), 1) - 870) > 1e-9) return "13% off 1,000 is not 870";
            if (Trade.attributeDiscount(null, builder) != 0) return "a viewer-less price got an attribute discount";
            try (java.io.InputStream in = Sky.plugin().getResource("data/hunting/shards.json")) {
                if (in != null) {
                    String txt = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    for (String a : List.of("builder_discount", "bartender_discount"))
                        if (!txt.contains("\"" + a + "\"")) return "hunting's shards.json has no attribute " + a;
                }
            } catch (java.io.IOException e) { return "reading hunting shards: " + e; }
            return null;
        });

        // The directory holds every merchant: ours, the ones whose spot another module runs, and the ones other
        // modules run with their own menus (the Community Shop above all - the bank stations it on npc.community_shop).
        Sky.commands().selfTest("shops.directory-complete", () -> {
            List<String> bad = ShopData.validateExternal(Shops.EXTERNAL, Shops.BY_ID.keySet());
            if (!bad.isEmpty()) return String.join("; ", bad);
            boolean cshop = false;
            for (ShopData.External x : Shops.EXTERNAL) if (x.id.equals("community_shop") && x.command.equals("cshop")) cshop = true;
            if (!cshop) return "the Community Shop is not in external.json";
            Layout hub = Shops.layout("hub");
            if (hub != null && hub.anchor("npc.community_shop") != null && Stationing.anchorOwner("hub", "npc.community_shop") == null)
                return "nobody stands on hub npc.community_shop (the bank should)";
            List<ShopMenus.DirEntry> list = ShopMenus.directoryList();
            int ours = 0, yielded = 0, ext = 0;
            for (ShopMenus.DirEntry d : list) {
                if (d.ext() != null) ext++;
                else if (d.yielded()) yielded++;
                else ours++;
                if (ShopMenus.directoryIcon(null, d) == null) return "no icon for " + (d.shop() != null ? d.shop().id : d.ext().id);
            }
            int wantYielded = 0;
            for (Shop x : Shops.all()) if (Shops.status(x) == Shops.Status.YIELDED) wantYielded++;
            if (ShopDials.DIRECTORY_YIELDED.get() && yielded != wantYielded) return "directory lists " + yielded + " of " + wantYielded + " yielded merchants";
            if (ours + yielded > Shops.BY_ID.size()) return "directory lists a merchant twice";
            int live = 0;
            for (ShopData.External x : Shops.EXTERNAL) if (Shops.externalLive(x)) live++;
            if (ShopDials.DIRECTORY_EXTERNAL.get() && ext != live) return "directory lists " + ext + " of " + live + " live external merchants";
            if (live == 0) return "no external merchant's command is registered (bank's /cshop at least should be)";
            return null;
        });

        // The Island Decorator's goods exist, its whole stock resolves, and the minigame rules hold.
        Sky.commands().selfTest("shops.decor-items", () -> {
            for (String id : List.of("PARKOUR_CONTROLLER", "PARKOUR_POINT", "PARKOUR_TIMES", "SOCIAL_DISPLAY", "EGG_HUNT", "ISLAND_NPC",
                    Decorations.TTT, Decorations.C4, Decorations.RPS, "SHOWCASE_BLOCK")) {
                if (!Sky.items().exists(id)) return id + " is not registered";
            }
            Shop deco = Shops.get("decorator");
            if (deco == null) return "no decorator";
            if (Shops.availableCount(deco) != deco.entries.size())
                return "the Decorator shows " + Shops.availableCount(deco) + " of " + deco.entries.size() + " lines";
            com.skyclone.api.item.ItemDef rps = Sky.items().def(Decorations.RPS);
            if (rps == null || rps.abilities == null || rps.abilities.isEmpty()) return "Rock Paper Snips has no ability";
            if (rps.requirements == null || !rps.requirements.contains("skill:social:8")) return "Rock Paper Snips is not gated by Social 8";
            Games.Grid t = new Games.Grid(3, 3, 3);
            t.set(0, 0, 1); t.set(1, 1, 1); t.set(2, 2, 1);
            if (t.winner() != 1) return "tic tac toe diagonal not a win";
            Games.Grid c = new Games.Grid(7, 6, 4);
            for (int i = 0; i < 4; i++) c.set(3, c.dropRow(3), 2);
            if (c.winner() != 2) return "four in a row column not a win";
            if (c.dropRow(3) != 1) return "disc did not stack (row " + c.dropRow(3) + ")";
            java.util.Map<String, Games.Sign> picks = new java.util.HashMap<>();
            picks.put("a", Games.Sign.ROCK); picks.put("b", Games.Sign.ROCK); picks.put("c", Games.Sign.PAPER);
            if (!Games.rpsRound(List.of("a", "b", "c"), picks).equals(Set.of("c"))) return "least picked sign did not win";
            return null;
        });

        // The Fossil Researcher's costs are fossil essence (reforges registers ESSENCE_FOSSIL, mining's excavator gives
        // it, the pouch holds it): every line of hers must now be payable.
        Sky.commands().selfTest("shops.fossil-essence", () -> {
            Shop f = Shops.get("fossil_researcher");
            if (f == null) return "no fossil_researcher";
            if (!Sky.items().exists("ESSENCE_FOSSIL")) return "ESSENCE_FOSSIL is not registered (reforges)";
            int lines = 0;
            for (Entry e : f.entries) {
                if (Shops.resolve(e) == null) continue;
                lines++;
                String why = Trade.blocked(e);
                if (why != null) return e.item + ": " + com.skyclone.api.ui.Text.strip(why);
            }
            if (Shops.essenceType("ESSENCE_FOSSIL") == null || !Shops.essenceType("ESSENCE_FOSSIL").equals("FOSSIL")) return "pouch type of ESSENCE_FOSSIL is not FOSSIL";
            return lines == 0 ? "the Fossil Researcher has nothing resolvable" : null;
        });

        // Recipes follow the price dials without a restart; buyback is a dupe-scan source that changes nothing.
        Sky.commands().selfTest("shops.recipe-reprice", () -> {
            if (!ShopDials.RECIPES.get()) return null;
            ShopRecipes.refreshPrices();
            int again = ShopRecipes.refreshPrices();
            if (again != 0) return "re-pricing is not idempotent (" + again + " changed twice)";
            if (ShopRecipes.lineCount() == 0) return "no recipe lines tracked";
            for (com.skyclone.api.item.Recipe r : Sky.recipes().byType(com.skyclone.api.item.Recipe.Type.NPC_SHOP)) {
                if (r.id == null || !r.id.startsWith("shops:")) continue;
                if (ShopRecipes.recipeCoins(r) < 0) return r.id + " has an unreadable coin input";
            }
            if (!Trade.buybackStacks(null).isEmpty()) return "buybackStacks(null) is not empty";
            return null;
        });
    }

    private static String expect(String shop, String item, int amount, double coins) {
        Shop s = Shops.get(shop);
        if (s == null) return "no shop " + shop;
        for (Entry e : s.entries) {
            if (item.equals(e.item)) {
                if (e.amount != amount || Math.abs(e.coins - coins) > 1e-9)
                    return shop + " sells " + item + " x" + e.amount + " for " + e.coins + " (research: x" + amount + " for " + coins + ")";
                return null;
            }
        }
        return shop + " does not stock " + item;
    }
}
