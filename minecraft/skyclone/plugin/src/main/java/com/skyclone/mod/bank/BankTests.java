package com.skyclone.mod.bank;

import com.skyclone.api.Sky;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Live self-tests ({@code /sb selftest bank}): the data hangs together and the formulas give the research's numbers. */
final class BankTests {

    private BankTests() {}

    static void register() {
        Sky.commands().selfTest("bank.data-loaded", () -> {
            BankCatalog c = Bank.catalog;
            if (c == null) return "catalog not loaded";
            if (c.tiers.size() != 7) return "expected 7 account tiers (research 1.2), got " + c.tiers.size();
            if (c.bands.size() != 8) return "expected 8 interest bands (research 1.3), got " + c.bands.size();
            if (c.fame.size() != 24) return "expected 24 fame ranks (research 6.3), got " + c.fame.size();
            if (c.bitsShop.isEmpty()) return "bits shop is empty";
            if (c.upgrades.size() != 15) return "expected 15 community shop upgrades, got " + c.upgrades.size();
            for (int i = 0; i < c.tiers.size(); i++) {
                BankMath.Tier t = c.tiers.get(i);
                if (t.index != i) return "tier " + t.name + " has index " + t.index + " at position " + i;
                if (i > 0 && t.capacity <= c.tiers.get(i - 1).capacity) return "tier capacities must grow: " + t.name;
            }
            for (BankCatalog.Upgrade u : c.upgrades.values()) {
                if (u.tiers.isEmpty()) return "upgrade " + u.id + " has no tiers";
                for (int i = 0; i < u.tiers.size(); i++) if (u.tiers.get(i).tier != i + 1) return "upgrade " + u.id + " tier " + (i + 1) + " misnumbered";
            }
            return null;
        });

        Sky.commands().selfTest("bank.wave3", () -> {
            // every "live" Community Shop upgrade id exists (a typo in the dial would silently lock it)
            for (String id : Bank.cshopLiveUpgrades.get().split(",")) {
                if (!id.isBlank() && !Bank.catalog.upgrades.containsKey(id.trim())) return "bank.cshop.live-upgrades names unknown upgrade '" + id.trim() + "'";
            }
            // the shop never sells a cookie below what the Bazaar pays for it (no buy-here-sell-there loop)
            double[] bz = CommunityShop.bazaarCookie();
            if (bz != null && Bank.cookieFollowsBazaar.get() && CommunityShop.cookiePrice() < bz[1])
                return "cookie price " + CommunityShop.cookiePrice() + " is below the Bazaar's instant sell " + bz[1];
            if (BankMath.tradeRisk(true, false, 0, 3, 1_000_000, "x") == null) return "giving for nothing must ask twice";
            if (!"north-west".equals(BankMath.compass(-1, -1))) return "compass is off: " + BankMath.compass(-1, -1);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (Bank.ready(p) && !AccountWide.loaded(p)) return p.getName() + " is ready but the account-wide copy never loaded";
            }
            return null;
        });

        Sky.commands().selfTest("bank.interest-formula", () -> {
            List<BankMath.Band> b = Bank.catalog.bands;
            // research 05 section 1.3: Starter at 50M earns 10M x 2% + 5M x 1% = 250,000; at 10M exactly 200,000
            if (Math.abs(BankMath.interest(10_000_000, 0, b) - 200_000) > 0.5) return "Starter at 10M should earn 200,000, got " + BankMath.interest(10_000_000, 0, b);
            if (Math.abs(BankMath.interest(50_000_000, 0, b) - 250_000) > 0.5) return "Starter at 50M should earn 250,000, got " + BankMath.interest(50_000_000, 0, b);
            if (Math.abs(BankMath.interest(1_000_000, 0, b) - 20_000) > 0.5) return "1M should earn 20,000";
            for (BankMath.Tier t : Bank.catalog.tiers) {
                double full = BankMath.interest(t.capacity * 100.0, t.index, b);
                if (Math.abs(full - t.maxInterest) > 0.5) return t.name + " max interest " + full + " != research " + t.maxInterest;
            }
            if (Math.abs(BankMath.interest(55_160_000_000.0, 6, b) - 1_500_000) > 1) return "Palatial cap should be 1,500,000";
            return null;
        });

        Sky.commands().selfTest("bank.items-exist", () -> {
            List<String> missing = new ArrayList<>();
            for (String id : List.of(Cookie.COOKIE, Cookie.FREE_COOKIE, "PERSONAL_BANK_ITEM")) if (!Sky.items().exists(id)) missing.add(id);
            for (String id : List.of(Accounts.GOLD_BLOCK)) if (!Sky.items().exists(id)) missing.add(id + " (materials)");
            for (Recipe r : Sky.recipes().producing("PERSONAL_BANK_ITEM")) {
                for (String cell : r.grid) {
                    if (cell == null || cell.isEmpty()) continue;
                    String id = Sky.items().exists(cell) ? cell : cell.substring(0, cell.lastIndexOf(':') < 0 ? cell.length() : cell.lastIndexOf(':'));
                    if (!Sky.items().exists(id)) missing.add(id + " (recipe)");
                }
            }
            if (Sky.recipes().producing("PERSONAL_BANK_ITEM").isEmpty()) missing.add("recipe for PERSONAL_BANK_ITEM");
            return missing.isEmpty() ? null : "missing: " + String.join(", ", missing);
        });

        Sky.commands().selfTest("bank.hub-anchors", () -> {
            Layout l = Sky.worlds().layout("hub");
            if (l == null) return "no hub layout yet";
            List<String> missing = new ArrayList<>();
            for (String a : List.of("npc.banker", "npc.banker_2", "npc.community_shop")) if (Sky.worlds().anchor("hub", a) == null) missing.add(a);
            return missing.isEmpty() ? null : "hub anchors missing: " + String.join(", ", missing);
        });

        Sky.commands().selfTest("bank.bits-shop-resolves", () -> {
            int ok = 0;
            List<String> missing = new ArrayList<>();
            for (BankCatalog.BitsOffer o : Bank.catalog.bitsShop) {
                if (CommunityShop.resolve(o) != null) ok++;
                else missing.add(o.item);
            }
            if (ok == 0) return "no Bits Shop item exists in this game (owners: alchemy, pets, minions, storage, enchanting, accessories...)";
            Sky.log().info("[bank] bits shop: " + ok + "/" + Bank.catalog.bitsShop.size() + " offers available; not registered: " + missing);
            return null;
        });

        Sky.commands().selfTest("bank.trade-and-bits-math", () -> {
            if (BankMath.tradeCap(0, 50e6, 1e9, 1e10, 50, 100) != 50e6) return "trade cap below 50 should be 50M";
            if (BankMath.tradeCap(50, 50e6, 1e9, 1e10, 50, 100) != 1e9) return "trade cap at 50 should be 1B";
            if (BankMath.tradeCap(100, 50e6, 1e9, 1e10, 50, 100) != 1e10) return "trade cap at 100 should be 10B";
            if (BankMath.cookiePool(4800, 1.0) != 4800) return "cookie pool should be 4,800 at x1";
            if (BankMath.cookiePool(4800, 1.1) != 5280) return "cookie pool should be 5,280 at x1.1";
            if (BankMath.bitsPayout(4800, 250, 1.0) != 250) return "payout should be 250";
            if (BankMath.bitsPayout(100, 250, 1.0) != 100) return "payout should never exceed the pool";
            var f = Bank.catalog.fame;
            if (f.get(0).fame != 0 || f.get(0).mult != 1.0) return "first fame rank should be 0 fame, x1";
            for (int i = 1; i < f.size(); i++) if (f.get(i).fame <= f.get(i - 1).fame || f.get(i).mult < f.get(i - 1).mult) return "fame ranks out of order at " + f.get(i).name;
            if (!BankMath.coinWords(12_500_000).equals("12,500,000 coins (12.5M)")) return "coinWords: " + BankMath.coinWords(12_500_000);
            return null;
        });

        Sky.commands().selfTest("bank.period-math", () -> {
            if (BankMath.periodIndex(1, 1, 3) == BankMath.periodIndex(1, 3, 3) && BankMath.periodIndex(1, 4, 3) == BankMath.periodIndex(1, 3, 3) + 1) {
                double d = BankMath.daysUntilNextPeriod(1, 1, 0, 0, 3, 31);
                if (Math.abs(d - 93) > 1e-9) return "a season is 93 days, got " + d;
                double d2 = BankMath.daysUntilNextPeriod(3, 31, 12, 0, 3, 31);
                if (Math.abs(d2 - 0.5) > 1e-9) return "noon of the last day should be half a day before payout, got " + d2;
                return null;
            }
            return "season boundaries are wrong";
        });

        // ---- wave 5 ----

        Sky.commands().selfTest("bank.wave5-provides-feature", () -> {
            if (!Sky.has(com.skyclone.api.feature.Features.Bank.class)) return "Features.Bank is not provided by the bank module";
            var fb = Sky.service(com.skyclone.api.feature.Features.Bank.class);
            if (fb.bitsMultiplier(null) != 1.0) return "bitsMultiplier(null) should be 1.0, got " + fb.bitsMultiplier(null);
            if (fb.cookieActive(null)) return "cookieActive(null) should be false";
            if (fb.cookieMillisLeft(null) != 0) return "cookieMillisLeft(null) should be 0";
            return null;
        });

        Sky.commands().selfTest("bank.wave5-fame-other-sources", () -> {
            // research 6.2: slayer T5 15 bits x fame multiplier; our own payouts, talisman doubles and refunds never
            if (BankMath.fameBonus(15, 1.1, new double[]{0}) != 1) return "15 bits at 1.1x should add 1 now";
            double[] carry = {0};
            long sum = 0;
            for (int i = 0; i < 10; i++) sum += BankMath.fameBonus(15, 1.1, carry);
            if (sum != 15) return "10 x 15 bits at 1.1x should add exactly 15 over time, got " + sum;
            List<String> all = List.of("*");
            List<String> ex = Bank.csv(Bank.fameExclude.get());
            if (BankMath.fameAppliesTo("bank: boon buff payout", all, ex, List.of())) return "own cookie payouts must not be multiplied again";
            if (BankMath.fameAppliesTo("accessories: bits talisman double", all, ex, List.of())) return "talisman doubles must not be multiplied";
            if (!BankMath.fameAppliesTo("slayer: zombie T5", all, ex, List.of())) return "slayer bits should get the fame bonus";
            if (BankMath.fameAppliesTo("slayer: zombie T5", all, ex, List.of("slayer"))) return "a self-applying module must be skipped";
            if (BankMath.fameAppliesTo("admin give", all, ex, List.of())) return "admin grants must not be multiplied";
            return null;
        });

        Sky.commands().selfTest("bank.wave5-museum-interest", () -> {
            // research 1.3: +2% per milestone, applied to the payout; Starter 10M -> 200,000 x 1.2 at milestone 10
            double m = BankMath.museumInterestMultiplier(10, 0.02, 36);
            if (Math.abs(m - 1.2) > 1e-9) return "milestone 10 should be x1.2, got " + m;
            if (Math.abs(BankMath.museumInterestMultiplier(50, 0.02, 36) - 1.72) > 1e-9) return "the bonus should cap at 36 milestones (+72%)";
            if (BankMath.museumInterestMultiplier(0, 0.02, 36) != 1.0) return "no milestone = no bonus";
            double base = BankMath.interest(10_000_000, 0, Bank.catalog.bands);
            if (Math.abs(base * m - 240_000) > 0.5) return "10M at milestone 10 should pay 240,000, got " + base * m;
            String key = Accounts.museumKey(3, 5);
            if (Sky.requirements().known("museummilestone") && !key.equals("museummilestone:3"))
                return "the tier gate should ask museummilestone:3 when progression resolves it, got " + key;
            return null;
        });

        Sky.commands().selfTest("bank.wave5-cshop-upgrades", () -> {
            // co-op slots: 5 base, +1..+3 (research 6.4), never above core's cap
            if (BankMath.coopCap(5, 0, 0, 5, 8) != 5) return "no Co-op Slots = 5";
            if (BankMath.coopCap(5, 3, 8, 5, 8) != 8) return "Co-op Slots III = 8";
            if (BankMath.coopCap(5, 3, 8, 5, 6) != 6) return "never above core's cap";
            String[] w = BankMath.coopCommand("/coop invite Steve");
            if (w == null || !w[0].equals("invite") || !w[1].equals("Steve")) return "/coop invite not parsed";
            if (BankMath.coopCommand("/skyclone:coopadd Alex") == null) return "/coopadd not parsed";
            if (BankMath.coopCommand("/coop list") != null) return "/coop list must be ignored";
            if (!CommunityShop.live("coins_allowance")) return "coins_allowance must be live (this module pays it)";
            if (CoopSlots.live() != (CoopSlots.coreCap() > CoopSlots.base())) return "coop_slots live state disagrees with the caps";
            // auto-live must not count the bank (or core) itself as a reader
            if (BankMath.callerModule(List.of("com.skyclone.mod.bank.CommunityShop$1", "com.skyclone.core.foundation.RequirementsImpl",
                    "com.skyclone.mod.bank.BankTests")) != null) return "the bank asking itself must not mark a reader";
            if (!"storage".equals(BankMath.callerModule(List.of("com.skyclone.mod.bank.CommunityShop$1",
                    "com.skyclone.core.foundation.RequirementsImpl", "com.skyclone.mod.storage.Store")))) return "storage should be detected as a reader";
            if (BankMath.fameForGems(Bank.cookieGems.get(), 200) != 65_000) return "a cookie (325 gems) should earn 65,000 Fame at 200/gem";
            return null;
        });

        Sky.commands().selfTest("bank.menus-build", () -> {
            Player p = null;
            for (Player o : Bukkit.getOnlinePlayers()) if (Bank.ready(o)) { p = o; break; }
            if (p == null) return null;   // needs a loaded profile to draw balances; nothing to build against
            BankMenus.chooser(p).redraw();
            BankMenus.account(p, false).redraw();
            BankMenus.account(p, true).redraw();
            BankMenus.deposit(p, false).redraw();
            BankMenus.withdraw(p, true).redraw();
            BankMenus.upgrades(p);
            CommunityShop.main(p);
            CommunityShop.bitsCategories(p);
            CommunityShop.fameMenu(p);
            Cookie.menu(p);
            return null;
        });
    }
}
