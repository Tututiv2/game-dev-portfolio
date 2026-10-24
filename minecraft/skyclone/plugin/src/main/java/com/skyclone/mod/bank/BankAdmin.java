package com.skyclone.mod.bank;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Admin tools: {@code /sb banking ...} (core already owns {@code /sb bank} for raw balances),
 * {@code /sb cookie ...} and {@code /sb trade ...}. Every sub takes a player NAME so it works over RCON,
 * and prints synchronously.
 */
final class BankAdmin {

    private BankAdmin() {}

    static void register() {
        Sky.commands().admin("banking", "help | info <p> | tier <p> <n> | interest <p> [payouts] | period | deposit|withdraw <p> <amount> [personal] | tx <p> [personal] | open <p> <menu> | cshop <p> finish|set <id> <tier>|reset | readers [clear [id]] | fame <p> [bits]",
                "Bank module admin: accounts, tiers, interest, Community Shop", BankAdmin::banking, BankAdmin::bankingTab);
        Sky.commands().admin("cookie", "<p> give [n] | eat | hours <h> | clear | bits <pool> | payout | fame <n> | free-reset",
                "Boon Cookie / Boon Buff / bits admin", BankAdmin::cookie, (s, a) -> a.length <= 1 ? names() :
                        a.length == 2 ? List.of("give", "eat", "hours", "clear", "bits", "payout", "fame", "free-reset") : List.of());
        Sky.commands().admin("trade", "list | log [n] | start <p1> <p2> | request <from> <to> | cancel <p> | coins <p> <amount> | offer <p> <invSlot> | accept <p> | deliver <p>",
                "Trade admin and test driver", BankAdmin::trade, (s, a) -> a.length <= 1
                        ? List.of("list", "log", "start", "request", "cancel", "coins", "offer", "accept", "deliver") : names());
    }

    private static List<String> names() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }

    private static List<String> bankingTab(CommandSender s, String[] a) {
        if (a.length <= 1) return List.of("help", "info", "tier", "interest", "period", "deposit", "withdraw", "tx", "open", "cshop", "readers", "fame");
        if (a.length == 2 && a[0].equals("readers")) return List.of("clear");
        if (a.length == 3 && a[0].equals("readers")) return new ArrayList<>(Bank.catalog.upgrades.keySet());
        if (a.length == 2 && !a[0].equals("period")) return names();
        if (a.length == 3 && a[0].equals("open")) return List.of("bank", "coop", "personal", "deposit", "withdraw", "upgrades", "cshop", "bits", "cshop-upgrades", "fame", "cookie");
        if (a.length == 3 && a[0].equals("cshop")) return List.of("finish", "set", "reset");
        if (a.length == 4 && a[0].equals("cshop")) return new ArrayList<>(Bank.catalog.upgrades.keySet());
        return List.of();
    }

    private static Player player(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) { s.sendMessage("Player " + name + " is not online."); return null; }
        if (!Bank.ready(p)) { s.sendMessage(name + " is still loading."); return null; }
        return p;
    }

    private static double amount(CommandSender s, String text) {
        try {
            String t = text.trim().toLowerCase(Locale.ROOT).replace(",", "");
            double mult = 1;
            if (t.endsWith("k")) { mult = 1e3; t = t.substring(0, t.length() - 1); }
            else if (t.endsWith("m")) { mult = 1e6; t = t.substring(0, t.length() - 1); }
            else if (t.endsWith("b")) { mult = 1e9; t = t.substring(0, t.length() - 1); }
            double v = Double.parseDouble(t) * mult;
            if (!(v >= 0) || Double.isInfinite(v)) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            s.sendMessage("Not an amount: " + text);
            return -1;
        }
    }

    // ---- /sb banking ------------------------------------------------------------------------------------

    private static void banking(CommandSender s, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            s.sendMessage("/sb banking info <p> - account, tier, personal, buff, fame");
            s.sendMessage("/sb banking tier <p> <0-" + Bank.catalog.maxTier() + "|name> - set the account tier (sets capacity)");
            s.sendMessage("/sb banking interest <p> [payouts] - pay interest now (default 1 payout)");
            s.sendMessage("/sb banking period - current interest period and time to the next payout");
            s.sendMessage("/sb banking deposit|withdraw <p> <amount|all> [personal] - as if they clicked it");
            s.sendMessage("/sb banking tx <p> [personal] - recent transactions");
            s.sendMessage("/sb banking open <p> <bank|coop|personal|deposit|withdraw|upgrades|cshop|bits|cshop-upgrades|fame|cookie>");
            s.sendMessage("/sb banking cshop <p> finish | set <upgrade> <tier> | reset - Community Shop upgrades");
            s.sendMessage("/sb banking readers [clear [upgrade]] - which modules read each Community Shop upgrade (live = buildable)");
            s.sendMessage("/sb banking fame <p> <bits> - preview the fame-rank bonus on a reward of <bits> from another source");
            s.sendMessage("Also: /sb cookie ..., /sb trade ... ; raw balances: /sb bank <p> give|take|set|cap (core)");
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("readers")) {
            if (a.length > 1 && a[1].equalsIgnoreCase("clear")) {
                String id = a.length > 2 ? a[2] : null;
                CommunityShop.clearReaders(id);
                s.sendMessage("Forgot " + (id == null ? "every" : id) + " Community Shop reader; upgrades are live again only through"
                        + " bank.cshop.live-upgrades or the next time a module reads them.");
                return;
            }
            s.sendMessage("Community Shop upgrades and who reads them (live = buildable):");
            for (BankCatalog.Upgrade u : Bank.catalog.upgrades.values()) {
                s.sendMessage("  " + u.id + ": " + (CommunityShop.live(u.id) ? "LIVE" : "coming soon") + " - readers "
                        + CommunityShop.readersOf(u.id) + (CommunityShop.inLiveDial(u.id) ? " (in dial)" : ""));
            }
            s.sendMessage("Fame multiplier: self-applying modules " + BankService.selfApplying() + "; sources '" + Bank.fameSources.get()
                    + "', excluded '" + Bank.fameExclude.get() + "'");
            return;
        }
        if (sub.equals("period")) {
            s.sendMessage("Interest period " + Bank.period() + " (every " + Bank.interestMonths.get() + " months); next payout in "
                    + BankMath.duration(Bank.millisToInterest()) + "; date " + Sky.calendar().now().dayLabel());
            return;
        }
        if (a.length < 2) { s.sendMessage("Usage: /sb banking " + sub + " <player> ..."); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        switch (sub) {
            case "info" -> {
                BankData.Shared sh = Bank.shared(p);
                BankData.Member m = Bank.member(p);
                BankMath.Tier t = Bank.catalog.tier(Bank.tier(p));
                s.sendMessage(p.getName() + " profile " + Sky.profiles().profileName(p) + " (" + Sky.profiles().profileId(p) + ")"
                        + (Bank.isCoop(p) ? " CO-OP" : " solo"));
                String pid = Sky.profiles().profileId(p);
                s.sendMessage(" co-op cap " + CoopSlots.capFor(p) + " (members " + (pid == null ? "?" : Sky.profiles().members(pid).size())
                        + ", base " + CoopSlots.base() + ", Co-op Slots " + CommunityShop.owned(p, "coop_slots") + ", core cap " + CoopSlots.coreCap()
                        + ", upgrade " + (CommunityShop.live("coop_slots") ? "live" : "coming soon") + ")");
                s.sendMessage(" account " + t.name + " (tier " + t.index + "): " + BankMath.commas(Sky.economy().bank(p)) + " / "
                        + BankMath.commas(Sky.economy().bankCapacity(p)) + "; purse " + BankMath.commas(Sky.economy().purse(p)));
                Accounts.Personal pa = Accounts.personal(p);
                s.sendMessage(" personal: " + (pa == null ? "none" : (pa.loaded ? BankMath.commas(BankMath.coins(pa.tenths)) : "loading")
                        + " / " + BankMath.commas(Bank.personalCapacity(p)) + " (last period " + (pa == null ? "-" : pa.lastPeriod) + ")"));
                s.sendMessage(" interest: last period " + sh.lastInterestPeriod + ", now " + Bank.period() + ", earned " + BankMath.commas(sh.interestEarned)
                        + ", next ~" + BankMath.commas(Accounts.onePayout(p, Sky.economy().bank(p), Sky.economy().bankCapacity(p), false))
                        + " (museum milestone " + Bank.museumMilestone(p) + " = x" + BankMath.commas(Bank.museumInterestMultiplier(p) * 100) + "%)");
                s.sendMessage(" buff: " + (Cookie.active(p) ? BankMath.duration(Cookie.millisLeft(p)) + " left" : "off")
                        + ", bits " + Sky.economy().bits(p) + ", available " + m.bitsAvailable + ", fame " + m.fame
                        + " (" + Bank.catalog.fameRank(m.fame).name + ", x" + Bank.fameMultiplier(p) + ")");
                s.sendMessage(" trade: sent today " + BankMath.commas(Bank.today().equals(m.tradeDay) ? m.tradeSentToday : 0)
                        + ", cap left " + BankMath.commas(Trades.capLeft(p)) + ", completed " + m.tradesCompleted);
                s.sendMessage(" upgrades: profile " + sh.upgrades + (sh.building.isEmpty() ? "" : " building " + sh.building + " " + sh.buildingTier)
                        + "; account " + m.upgrades + (m.building.isEmpty() ? "" : " building " + m.building + " " + m.buildingTier));
                s.sendMessage(" " + AccountWide.describe(p));
            }
            case "tier" -> {
                if (a.length < 3) { s.sendMessage("Usage: /sb banking tier <p> <n|name>"); return; }
                int tier = -1;
                for (BankMath.Tier t : Bank.catalog.tiers) if (t.id.equalsIgnoreCase(a[2]) || t.name.equalsIgnoreCase(a[2])) tier = t.index;
                if (tier < 0) try { tier = Integer.parseInt(a[2]); } catch (NumberFormatException e) { tier = -1; }
                if (tier < 0 || tier > Bank.catalog.maxTier()) { s.sendMessage("Tier must be 0-" + Bank.catalog.maxTier() + " or a name."); return; }
                Accounts.applyTier(p, tier);
                s.sendMessage(p.getName() + " is now " + Bank.catalog.tier(tier).name + " (capacity " + BankMath.commas(Sky.economy().bankCapacity(p)) + ")");
            }
            case "interest" -> {
                int n = 1;
                if (a.length > 2) try { n = Math.max(1, Math.min(100, Integer.parseInt(a[2]))); } catch (NumberFormatException ignored) { }
                double before = Sky.economy().bank(p);
                double paid = Accounts.payCoopInterest(p, n);
                if (paid > 0) {
                    Bank.store.logTx(BankStore.coopAccount(Sky.profiles().profileId(p)), null, "Bank Interest", "interest",
                            BankMath.creditTenths(paid), BankMath.creditTenths(Sky.economy().bank(p)));
                    Bank.shared(p).interestEarned += paid;
                }
                Accounts.Personal pa = Accounts.personal(p);
                if (pa != null && pa.loaded) { pa.lastPeriod = Math.max(-1, Bank.period() - n); Accounts.checkPersonalInterest(p); }
                s.sendMessage("Paid " + BankMath.commas(paid) + " coins of interest over " + n + " payout(s): " + BankMath.commas(before)
                        + " -> " + BankMath.commas(Sky.economy().bank(p)) + (pa != null && pa.loaded ? "; personal now " + BankMath.commas(BankMath.coins(pa.tenths)) : ""));
            }
            case "deposit", "withdraw" -> {
                if (a.length < 3) { s.sendMessage("Usage: /sb banking " + sub + " <p> <amount|all> [personal]"); return; }
                boolean personal = a.length > 3 && a[3].equalsIgnoreCase("personal");
                double amt = a[2].equalsIgnoreCase("all") ? Double.MAX_VALUE / 4 : amount(s, a[2]);
                if (amt < 0) return;
                double bankBefore = personal ? Accounts.personalBalance(p) : Sky.economy().bank(p);
                if (sub.equals("deposit")) Accounts.deposit(p, amt, personal);
                else Accounts.withdraw(p, amt, personal);
                s.sendMessage(sub + " requested; " + (personal ? "personal" : "co-op") + " balance " + BankMath.commas(bankBefore) + " -> "
                        + BankMath.commas(personal ? Accounts.personalBalance(p) : Sky.economy().bank(p)) + " (purse " + BankMath.commas(Sky.economy().purse(p)) + ")"
                        + (personal && sub.equals("withdraw") ? " - personal withdrawals land a moment later" : ""));
            }
            case "tx" -> {
                boolean personal = a.length > 2 && a[2].equalsIgnoreCase("personal");
                String acct = BankMenus.accountKey(p, personal);
                List<BankStore.TxRow> rows = acct == null ? List.of() : Bank.store.cachedTx(acct, 20);
                s.sendMessage(acct + ": " + rows.size() + " cached row(s)" + (acct != null && !Bank.store.txCached(acct) ? " (not loaded yet)" : ""));
                long now = System.currentTimeMillis();
                for (BankStore.TxRow r : rows) {
                    s.sendMessage("  " + BankMath.ago(now - r.ts()) + " " + r.kind() + " " + (r.delta() >= 0 ? "+" : "") + BankMath.commas(BankMath.coins(r.delta()))
                            + " -> " + BankMath.commas(BankMath.coins(r.balance())) + " by " + r.actorName());
                }
            }
            case "open" -> {
                String what = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "bank";
                switch (what) {
                    case "bank" -> BankMenus.openRoot(p);
                    case "coop" -> BankMenus.account(p, false).open(p);
                    case "personal" -> BankMenus.account(p, true).open(p);
                    case "deposit" -> BankMenus.deposit(p, false).open(p);
                    case "withdraw" -> BankMenus.withdraw(p, false).open(p);
                    case "upgrades" -> BankMenus.upgrades(p).open(p);
                    case "cshop" -> CommunityShop.main(p).open(p);
                    case "bits" -> CommunityShop.bitsCategories(p).open(p);
                    case "cshop-upgrades" -> CommunityShop.upgrades(p).open(p);
                    case "fame" -> CommunityShop.fameMenu(p).open(p);
                    case "cookie" -> Cookie.menu(p).open(p);
                    default -> { s.sendMessage("Unknown menu " + what); return; }
                }
                s.sendMessage("Opened " + what + " for " + p.getName());
            }
            case "cshop" -> {
                String op = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "";
                BankData.Shared sh = Bank.shared(p);
                BankData.Member m = Bank.member(p);
                switch (op) {
                    case "finish" -> {
                        if (!sh.building.isEmpty()) sh.buildingDone = 0;
                        if (!m.building.isEmpty()) m.buildingDone = 0;
                        CommunityShop.checkUpgrades(p);
                        s.sendMessage("Finished any upgrade under construction. Profile " + sh.upgrades + ", account " + m.upgrades);
                    }
                    case "set" -> {
                        if (a.length < 5) { s.sendMessage("Usage: /sb banking cshop <p> set <upgrade> <tier>"); return; }
                        BankCatalog.Upgrade u = Bank.catalog.upgrades.get(a[3]);
                        if (u == null) { s.sendMessage("Unknown upgrade " + a[3] + ": " + Bank.catalog.upgrades.keySet()); return; }
                        int t;
                        try { t = Math.max(0, Math.min(u.maxTier(), Integer.parseInt(a[4]))); } catch (NumberFormatException e) { s.sendMessage("Bad tier"); return; }
                        (u.profileScope() ? sh.upgrades : m.upgrades).put(u.id, t);
                        Sky.stats().invalidate(p);
                        s.sendMessage(u.name + " set to " + t + " for " + p.getName());
                    }
                    case "reset" -> {
                        sh.upgrades.clear(); sh.building = ""; m.upgrades.clear(); m.building = ""; sh.allowanceDay = "";
                        Sky.stats().invalidate(p);
                        s.sendMessage("Community Shop upgrades cleared for " + p.getName());
                    }
                    default -> s.sendMessage("Usage: /sb banking cshop <p> finish | set <upgrade> <tier> | reset");
                }
            }
            case "fame" -> {
                long base = 25;
                if (a.length > 2) try { base = Math.max(1, Long.parseLong(a[2])); } catch (NumberFormatException ignored) { }
                double mult = Bank.fameMultiplier(p);
                long bonus = BankMath.fameBonus(base, mult, new double[]{0});
                s.sendMessage(p.getName() + " fame " + Bank.member(p).fame + " (" + Bank.catalog.fameRank(Bank.member(p).fame).name + ", x" + mult
                        + "): a " + base + "-bit reward from another source pays " + (base + bonus) + " (+" + bonus + "); other sources "
                        + (Bank.fameOtherSources.get() ? "ON" : "OFF"));
            }
            default -> s.sendMessage("Unknown sub. /sb banking help");
        }
    }

    // ---- /sb cookie --------------------------------------------------------------------------------------

    private static void cookie(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage("Usage: /sb cookie <p> give [n] | eat | hours <h> | clear | bits <pool> | payout | fame <n> | free-reset"); return; }
        Player p = player(s, a[0]);
        if (p == null) return;
        BankData.Member m = Bank.member(p);
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                int n = 1;
                if (a.length > 2) try { n = Math.max(1, Math.min(64, Integer.parseInt(a[2]))); } catch (NumberFormatException ignored) { }
                Sky.loot().give(p, Cookie.COOKIE, n, com.skyclone.api.loot.LootSource.ADMIN);
                s.sendMessage("Gave " + n + " Boon Cookie(s) to " + p.getName());
            }
            case "eat" -> {
                Cookie.grant(p, (long) (Bank.cookieHours.get() * 3_600_000L), true);
                m.cookiesEaten++;
                s.sendMessage(p.getName() + " has the buff for " + BankMath.duration(Cookie.millisLeft(p)) + "; bits available " + m.bitsAvailable);
            }
            case "hours" -> {
                double h = a.length > 2 ? amount(s, a[2]) : -1;
                if (h < 0) return;
                Cookie.grant(p, (long) (h * 3_600_000L), false);
                s.sendMessage(p.getName() + " buff: " + BankMath.duration(Cookie.millisLeft(p)) + " left");
            }
            case "clear" -> { Cookie.clear(p); s.sendMessage("Buff cleared for " + p.getName()); }
            case "bits" -> {
                double n = a.length > 2 ? amount(s, a[2]) : -1;
                if (n < 0) return;
                m.bitsAvailable = (long) n;
                s.sendMessage(p.getName() + " bits available = " + m.bitsAvailable);
            }
            case "payout" -> {
                if (!Cookie.active(p)) { s.sendMessage("The buff is not active (use /sb cookie " + p.getName() + " eat)."); return; }
                long before = Sky.economy().bits(p);
                Cookie.payBits(p, m, System.currentTimeMillis());
                s.sendMessage("Bits " + before + " -> " + Sky.economy().bits(p) + "; available " + m.bitsAvailable);
            }
            case "fame" -> {
                double n = a.length > 2 ? amount(s, a[2]) : -1;
                if (n < 0) return;
                m.fame = (long) n;
                s.sendMessage(p.getName() + " fame = " + m.fame + " (" + Bank.catalog.fameRank(m.fame).name + ", x" + Bank.fameMultiplier(p) + ")");
            }
            case "free-reset" -> { m.freeCookieClaimed = false; s.sendMessage("Free cookie can be claimed again by " + p.getName()); }
            default -> s.sendMessage("Unknown cookie sub.");
        }
    }

    // ---- /sb trade --------------------------------------------------------------------------------------

    private static void trade(CommandSender s, String[] a) {
        if (a.length == 0) { s.sendMessage("Usage: /sb trade list | log [n] | start <p1> <p2> | request <from> <to> | cancel <p> | coins <p> <amount> | offer <p> <invSlot> | accept <p> | deliver <p>"); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                var all = Trades.sessions();
                s.sendMessage(all.size() + " open trade(s)");
                for (TradeSession t : all) {
                    s.sendMessage(" " + t.id.substring(0, 8) + " " + t.a.name + " [" + t.a.offers.size() + " items, " + BankMath.commas(t.a.coins)
                            + (t.a.accepted ? ", ACCEPTED" : "") + "] <-> " + t.b.name + " [" + t.b.offers.size() + " items, " + BankMath.commas(t.b.coins)
                            + (t.b.accepted ? ", ACCEPTED" : "") + "]" + (t.committing ? " COMMITTING" : ""));
                }
            }
            case "log" -> {
                int n = 10;
                if (a.length > 1) try { n = Integer.parseInt(a[1]); } catch (NumberFormatException ignored) { }
                var rows = Bank.store.recentTrades(n);
                s.sendMessage(rows.size() + " trade(s) since boot (full history: table bank_trades)");
                long now = System.currentTimeMillis();
                for (BankStore.TradeLog r : rows) {
                    s.sendMessage(" " + BankMath.ago(now - r.ts()) + " " + r.aName() + " gave [" + r.aItems() + "] + " + BankMath.commas(BankMath.coins(r.aCoins()))
                            + " ; " + r.bName() + " gave [" + r.bItems() + "] + " + BankMath.commas(BankMath.coins(r.bCoins())));
                }
            }
            case "start", "request" -> {
                if (a.length < 3) { s.sendMessage("Usage: /sb trade " + sub + " <p1> <p2>"); return; }
                Player p1 = player(s, a[1]), p2 = player(s, a[2]);
                if (p1 == null || p2 == null) return;
                if (sub.equals("request")) { Trades.request(p1, p2); s.sendMessage("Request sent as " + p1.getName()); return; }
                String why = Trades.cannotTrade(p1, p2, false);
                if (why != null) { s.sendMessage("Cannot: " + why); return; }
                TradeSession t = Trades.start(p1, p2);
                s.sendMessage("Trade " + t.id.substring(0, 8) + " opened between " + p1.getName() + " and " + p2.getName() + " (distance not checked)");
            }
            case "cancel" -> {
                if (a.length < 2) return;
                Player p = player(s, a[1]);
                if (p == null) return;
                TradeSession t = Trades.session(p);
                if (t == null) { s.sendMessage(p.getName() + " is not trading."); return; }
                Trades.cancel(t, "an admin cancelled it.", null);
                s.sendMessage("Cancelled.");
            }
            case "coins" -> {
                if (a.length < 3) return;
                Player p = player(s, a[1]);
                if (p == null) return;
                TradeSession t = Trades.session(p);
                if (t == null) { s.sendMessage(p.getName() + " is not trading."); return; }
                double amt = amount(s, a[2]);
                if (amt < 0) return;
                Trades.adminCoins(t, p, amt);
                s.sendMessage(p.getName() + " now offers " + BankMath.commas(t.side(p.getUniqueId()).coins) + " coins");
            }
            case "offer" -> {
                if (a.length < 3) return;
                Player p = player(s, a[1]);
                if (p == null) return;
                TradeSession t = Trades.session(p);
                if (t == null) { s.sendMessage(p.getName() + " is not trading."); return; }
                int slot;
                try { slot = Integer.parseInt(a[2]); } catch (NumberFormatException e) { s.sendMessage("Bad slot"); return; }
                ItemStack it = slot >= 0 && slot < 36 ? p.getInventory().getItem(slot) : null;
                if (it == null || it.getType().isAir()) { s.sendMessage("Slot " + slot + " is empty."); return; }
                Trades.adminOffer(t, p, slot, it);
                s.sendMessage(p.getName() + " offers " + t.side(p.getUniqueId()).offers.size() + " stack(s)");
            }
            case "accept" -> {
                if (a.length < 2) return;
                Player p = player(s, a[1]);
                if (p == null) return;
                TradeSession t = Trades.session(p);
                if (t == null) { s.sendMessage(p.getName() + " is not trading."); return; }
                t.lastChange = 0;   // admin accept skips the change lock
                Trades.adminAccept(t, p);
                s.sendMessage(p.getName() + " accepted" + (t.committing || t.closed ? "; trade committing/closed" : "; waiting for the other side"));
            }
            case "deliver" -> {
                if (a.length < 2) return;
                Player p = player(s, a[1]);
                if (p == null) return;
                Trades.deliver(p, false);
                s.sendMessage("Delivery check queued for " + p.getName());
            }
            default -> s.sendMessage("Unknown trade sub.");
        }
    }
}
