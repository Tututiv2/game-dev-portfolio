package com.skyclone.mod.collections;

import com.skyclone.api.Sky;
import com.skyclone.api.item.Recipe;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** {@code /sb selftest collections}: the data hangs together and the formulas give research numbers. */
final class CollectionsSelfTests {

    private CollectionsSelfTests() {}

    static void register(CollectionsModule mod) {

        // 90 collections / 790 tiers (research 02 section 3.1), strictly rising thresholds, SB XP on every tier.
        Sky.commands().selfTest("collections.data-complete", () -> {
            CollectionCatalog cat = mod.catalog();
            if (cat.resourceCount() != 90) return "expected 90 resource collections, got " + cat.resourceCount();
            if (cat.resourceTiers() != 790) return "expected 790 tiers, got " + cat.resourceTiers();
            if (cat.categories().size() != 7) return "expected 7 categories, got " + cat.categories().size();
            for (CollectionCatalog.Category c : cat.categories())
                if (cat.inCategory(c.id).isEmpty()) return "category " + c.id + " is empty";
            for (CollectionCatalog.Coll c : cat.all()) {
                long prev = 0;
                for (int i = 0; i < c.tiers.size(); i++) {
                    CollectionCatalog.Tier t = c.tiers.get(i);
                    if (t.tier != i + 1) return c.id + " tier numbering broken at " + t.tier;
                    if (t.amount <= prev) return c.id + " tier " + t.tier + " threshold does not rise (" + t.amount + ")";
                    if (t.sbxp <= 0) return c.id + " tier " + t.tier + " gives no SkyBlock XP";
                    prev = t.amount;
                }
            }
            return null;
        });

        // Known research numbers: Wheat road, tier maths, gemstone weights, boss kill units.
        Sky.commands().selfTest("collections.formulas", () -> {
            CollectionCatalog cat = mod.catalog();
            CollectionCatalog.Coll wheat = cat.get("WHEAT");
            if (wheat == null) return "WHEAT missing";
            long[] want = {50, 100, 250, 500, 1000, 2500, 10000, 15000, 25000, 50000, 100000};
            if (wheat.tiers.size() != want.length) return "Wheat has " + wheat.tiers.size() + " tiers, research says 11";
            for (int i = 0; i < want.length; i++)
                if (wheat.tiers.get(i).amount != want[i]) return "Wheat " + (i + 1) + " = " + wheat.tiers.get(i).amount + ", research " + want[i];
            if (CollectionMath.tierFor(wheat, 22_600) != 8) return "22,600 wheat should be tier VIII";
            if (CollectionMath.tierFor(wheat, 49) != 0) return "49 wheat should be tier 0";
            if (Math.abs(CollectionMath.progress(wheat, 22_600) - 0.904) > 1e-9) return "progress to IX should be 22,600/25,000";
            CollectionCatalog.Feed g = cat.feed("FLAWED_RUBY_GEM");
            if (g == null || !g.coll().id.equals("GEMSTONE_COLLECTION") || g.weight() != 80) return "flawed ruby should feed Gemstone x80";
            CollectionCatalog.Feed cocoa = cat.feed("COCOA_BEANS");
            if (cocoa == null || !cocoa.coll().id.equals("INK_SACK:3")) return "COCOA_BEANS should feed INK_SACK:3";
            if (cat.feed("ENCHANTED_WHEAT") != null) return "enchanted forms must not count";
            CollectionCatalog.Coll bonzo = cat.get("BOSS_BONZO");
            if (bonzo == null) return "BOSS_BONZO missing";
            if (CollectionMath.bossUnits(bonzo, "catacombs_f1_bonzo", 2) != 1) return "F1 boss kill should count 1";
            if (CollectionMath.bossUnits(bonzo, "catacombs_m1_bonzo", 2) != 2) return "M1 boss kill should count 2";
            if (CollectionMath.bossUnits(bonzo, "catacombs_f2_scarf", 2) != 0) return "scarf must not count for bonzo";
            CollectionCatalog.Coll kuudra = cat.get("BOSS_KUUDRA");
            if (kuudra == null || CollectionMath.bossUnits(kuudra, "kuudra_infernal_boss", 2) != 5) return "infernal kuudra should count 5";
            int sb = 0;
            for (CollectionCatalog.Coll c : cat.inCategory("BOSS")) sb += CollectionMath.sbxpThrough(c, c.maxTier());
            if (sb != 1015) return "boss collections SB XP sum " + sb + ", research task max 1,015";
            return null;
        });

        // Leaderboard: the table exists, ranks are 1 + others strictly above, ties share, own stale row ignored.
        Sky.commands().selfTest("collections.leaderboard", () -> {
            if (!mod.leaderboard().available()) return "collections_lb table unavailable (migration failed)";
            List<CollectionsLeaderboard.Row> rows = List.of(
                    new CollectionsLeaderboard.Row("a", 90_000, "A"),
                    new CollectionsLeaderboard.Row("b", 50_000, "B"),
                    new CollectionsLeaderboard.Row("me", 999_999, "Me (stale)"),
                    new CollectionsLeaderboard.Row("c", 40_000, "C"));
            CollectionsLeaderboard.Position pos = CollectionsLeaderboard.rank(rows, "me", 50_000);
            if (pos.rank() != 2 || pos.size() != 4) return "rank of 50,000 should be #2 of 4 (tie with b), got #" + pos.rank() + " of " + pos.size();
            CollectionsLeaderboard.Position fresh = CollectionsLeaderboard.rank(rows, "new", 100_000);
            if (fresh.rank() != 2 || fresh.size() != 5) return "a new 100,000 should be #2 of 5 (the stale 999,999 row is another profile here)";
            if (new CollectionsLeaderboard.Position(1, 1000).topPercent() != 1) return "#1 of 1,000 should read Top 1%";
            if (new CollectionsLeaderboard.Position(50, 1000).topPercent() != 5) return "#50 of 1,000 should read Top 5%";
            return null;
        });

        // Wave 5: Classic / Ironman / Stranded / Bingo rank on separate boards; the split dial merges them.
        Sky.commands().selfTest("collections.leaderboard-modes", () -> {
            if (!"normal".equals(CollectionsLeaderboard.boardOf(null, true))) return "null mode should rank on Classic";
            if (!"normal".equals(CollectionsLeaderboard.boardOf("Classic", true))) return "'Classic' should rank on Classic";
            if (!"ironman".equals(CollectionsLeaderboard.boardOf("IRONMAN", true))) return "ironman should rank on its own board";
            if (!"normal".equals(CollectionsLeaderboard.boardOf("ironman", false))) return "split off: everyone on one board";
            if (!"ironman".equals(CollectionsLeaderboard.parseBoard("im"))) return "'im' should name the Ironman board";
            List<CollectionsLeaderboard.Raw> raws = List.of(
                    new CollectionsLeaderboard.Raw("WHEAT", "normal", new CollectionsLeaderboard.Row("a", 90_000, "A")),
                    new CollectionsLeaderboard.Raw("WHEAT", "ironman", new CollectionsLeaderboard.Row("b", 50_000, "B")),
                    new CollectionsLeaderboard.Raw("WHEAT", "stranded", new CollectionsLeaderboard.Row("c", 70_000, "C")),
                    new CollectionsLeaderboard.Raw("WHEAT", "ironman", new CollectionsLeaderboard.Row("d", 60_000, "D")));
            var split = CollectionsLeaderboard.group(raws, true);
            var iron = split.get(CollectionsLeaderboard.key("ironman", "WHEAT"));
            if (iron == null || iron.size() != 2 || !iron.get(0).profileId().equals("d")) return "ironman board should be [d, b], got " + iron;
            if (split.get(CollectionsLeaderboard.key("normal", "WHEAT")).size() != 1) return "Classic board should hold only a";
            if (CollectionsLeaderboard.rank(iron, "b", 50_000).rank() != 2) return "b should be #2 on Ironman, not ranked against Classic";
            var one = CollectionsLeaderboard.group(raws, false).get(CollectionsLeaderboard.key("normal", "WHEAT"));
            if (one == null || one.size() != 4 || !one.get(0).profileId().equals("a")) return "unsplit board should hold all four, a first";
            // The live player's own board is what the menus rank them on.
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!Sky.profiles().isReady(p)) continue;
                String want = CollectionsLeaderboard.boardOf(Sky.profiles().mode(p), mod.leaderboardSplitModes());
                if (!want.equals(mod.leaderboard().boardFor(p))) return p.getName() + " ranks on " + mod.leaderboard().boardFor(p) + ", mode says " + want;
                break;
            }
            return null;
        });

        // Wave 5: the deleted-profile prune query runs against core's schema (read-only count; never deletes here).
        Sky.commands().selfTest("collections.leaderboard-prune", () -> {
            if (!mod.leaderboard().available()) return "collections_lb table unavailable";
            Integer n;
            try { n = mod.leaderboard().pruneCandidates().get(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception e) { return "prune count failed: " + e; }
            if (n == null || n < 0) return "prune query could not read core's profile_members table";
            Sky.log().info("[collections] leaderboard-prune: " + n + " rows belong to deleted profiles; join live modes=" + mod.leaderboard().joinOk());
            return null;
        });

        // Wave 5: Settings > Roman Numerals (numbers.roman) decides how tiers read; default Roman.
        Sky.commands().selfTest("collections.roman-setting", () -> {
            if (!CollectionsModule.roman(null)) return "no player should default to Roman";
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!Sky.profiles().isReady(p)) continue;
                String t = mod.tierText(p, 7);
                boolean r = CollectionsModule.roman(p);
                if (!t.equals(r ? "VII" : "7")) return "tierText(7) = " + t + " with roman=" + r;
                if (!mod.tierText(p, 0).equals("0")) return "tier 0 should read 0";
                break;
            }
            return null;
        });

        // Requirement keys parse the contract's way, including ids that contain ':'.
        Sky.commands().selfTest("collections.requirement-keys", () -> {
            CollectionCatalog cat = mod.catalog();
            CollectionKey a = CollectionKey.parse(cat, "collection:INK_SACK:3:5");
            if (a == null || !a.coll().id.equals("INK_SACK:3") || a.tier() != 5) return "collection:INK_SACK:3:5 -> " + a;
            CollectionKey b = CollectionKey.parse(cat, "collection:INK_SACK:3");
            if (b == null || !b.coll().id.equals("INK_SACK") || b.tier() != 3) return "collection:INK_SACK:3 -> " + b;
            CollectionKey c = CollectionKey.parse(cat, "collection:COCOA_BEANS:VII");
            if (c == null || !c.coll().id.equals("INK_SACK:3") || c.tier() != 7) return "collection:COCOA_BEANS:VII -> " + c;
            String d = Sky.requirements().describe("collection:WHEAT:5");
            if (d == null || !d.contains("Collection V")) return "describe(collection:WHEAT:5) = " + d;
            return null;
        });

        // Every recipe another module gated on a collection names a real collection and a real tier.
        Sky.commands().selfTest("collections.recipe-unlocks-valid", () -> {
            List<String> bad = new ArrayList<>();
            int gated = 0;
            for (Recipe r : Sky.recipes().all()) {
                if (r == null || r.unlock == null) continue;
                for (String part : r.unlock.split("&")) {
                    String key = part.trim();
                    if (!key.startsWith("collection:")) continue;
                    gated++;
                    CollectionKey k = CollectionKey.parse(mod.catalog(), key);
                    if (k == null) bad.add(r.result + "<-" + key + " (unknown collection)");
                    else if (k.tier() > k.coll().maxTier()) bad.add(r.result + "<-" + key + " (max " + k.coll().maxTier() + ")");
                }
            }
            if (!bad.isEmpty()) return bad.size() + "/" + gated + " bad unlock keys: " + String.join(", ", bad.subList(0, Math.min(8, bad.size())));
            return null;
        });

        // The items that FEED each collection exist (otherwise nothing can ever count toward it).
        Sky.commands().selfTest("collections.items-exist", () -> {
            List<String> missing = new ArrayList<>();
            for (CollectionCatalog.Coll c : mod.catalog().all()) {
                if (c.isBoss()) continue;
                boolean any = Sky.items().exists(c.id);
                for (String a : c.aliases.keySet()) any |= Sky.items().exists(a);
                if (!any) missing.add(c.id);
            }
            // TUNE: the Rift and the newest islands are wave 2; tolerate their items being absent for now.
            missing.removeIf(id -> {
                CollectionCatalog.Coll c = mod.catalog().get(id);
                return c != null && "RIFT".equals(c.category);
            });
            return missing.isEmpty() ? null : missing.size() + " collections have no registered item: " + String.join(", ", missing.subList(0, Math.min(12, missing.size())));
        });

        // Reward lore renders for every tier, and menus build for an online player if there is one.
        Sky.commands().selfTest("collections.menus-build", () -> {
            Set<String> unregistered = new LinkedHashSet<>();
            int lines = 0;
            for (CollectionCatalog.Coll c : mod.catalog().all()) {
                for (CollectionCatalog.Tier t : c.tiers) {
                    lines += mod.rewards().lines(c, t, 0).size();
                    for (CollectionCatalog.Reward r : t.rewards)
                        if ("recipe".equals(r.type) && r.item != null && !Sky.items().exists(r.item)) unregistered.add(r.item);
                }
            }
            if (lines < 798) return "only " + lines + " reward lines rendered";
            // Collection Recipes: every unlock is a registered item; the common ones are not empty.
            int unlocks = 0;
            for (CollectionCatalog.Coll c : mod.catalog().all()) {
                if (c.isBoss()) continue;
                for (CollectionsMenus.Unlock u : mod.menus().unlocks(c)) {
                    if (!Sky.items().exists(u.itemId())) return c.id + " lists unregistered unlock " + u.itemId();
                    if (u.tier() < 1 || u.tier() > c.maxTier()) return c.id + " unlock " + u.itemId() + " at bad tier " + u.tier();
                    unlocks++;
                }
            }
            CollectionCatalog.Coll wheatColl = mod.catalog().get("WHEAT");
            if (wheatColl != null && Sky.items().exists("ENCHANTED_BREAD") && mod.menus().unlocks(wheatColl).isEmpty())
                return "Wheat unlocks no recipes in the Collection Recipes list";
            Sky.log().info("[collections] menus-build: " + unlocks + " collection recipe unlocks listed");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!Sky.profiles().isReady(p)) continue;
                mod.menus().buildMain(p);
                for (CollectionCatalog.Category cat : mod.catalog().categories()) mod.menus().buildCategory(p, cat);
                for (CollectionCatalog.Coll c : mod.catalog().all()) mod.menus().buildCollection(p, c);
                break;
            }
            if (CollectionsMenus.roadSlots(12).length != 12 || CollectionsMenus.roadSlots(12)[11] > 53) return "road layout broken";
            Sky.log().info("[collections] menus-build: " + unregistered.size() + " recipe reward items not registered yet (hidden from lore)");
            return null;
        });
    }
}
