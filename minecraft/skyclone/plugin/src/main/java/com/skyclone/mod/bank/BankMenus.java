package com.skyclone.mod.bank;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every bank menu (research 05 section 1.4): the account (Deposit / Withdraw / Recent Transactions /
 * Information / Upgrades), the co-op vs personal chooser, the deposit and withdraw option menus, and the
 * account-tier upgrade road. Opening them is gated (banker, Pocket Bank, or the Boon Buff); moving
 * between them is not.
 */
final class BankMenus {

    private BankMenus() {}

    static final String BANKER_NAME = "&fBanker Odell";
    static final String BANKER_2_NAME = "&fBanker Prue";

    static void registerNpcs() {
        String[][] npcs = {{"bank_banker", BANKER_NAME, "npc.banker", "banker"}, {"bank_banker_2", BANKER_2_NAME, "npc.banker_2", "banker_2"}};
        for (String[] n : npcs) {
            NpcDef def = NpcDef.of(n[0], n[1], "hub", n[2]).title("&6&lBANKER");
            String skin = Bank.catalog.npcSkins.get(n[3]);
            if (skin != null) def.skin(skin, null);
            def.lines.add("&7Deposits, withdrawals & upgrades");
            Sky.npcs().register(def, p -> {
                if (!Bank.ready(p)) return;
                BankData.Member m = Bank.member(p);
                if (!m.firstDeposit && Sky.economy().bank(p) <= 0) {
                    Sky.npcs().talk(p, n[0], List.of(
                            "Welcome to the bank!",
                            "Coins in your purse are at risk: you drop half of them when you die.",
                            "Coins in here are safe, and they earn interest every season."), () -> openRoot(p));
                } else {
                    openRoot(p);
                }
            });
        }
    }

    // ---- entry points -----------------------------------------------------------------------------------

    /** /bank: at a banker, with the Boon Buff (+ its collection gate), or with the global dial. */
    static void openCommand(Player p) {
        if (!Bank.ready(p)) return;
        if (Accounts.nearBanker(p) || Accounts.remoteAllowed(p)) { openRoot(p); return; }
        Msg.error(p, Accounts.remoteDenial(p));
    }

    static void openRoot(Player p) {
        if (!Bank.ready(p)) return;
        Accounts.checkInterest(p);
        if (Bank.hasPersonal(p)) chooser(p).open(p);
        else account(p, false).open(p);
    }

    // ---- chooser -------------------------------------------------------------------------------------------

    static Menu chooser(Player p) {
        Menu m = new Menu("Bank", 4).chrome();
        m.set(11, Icons.of(Material.GOLD_BLOCK, "&6Co-op Bank Account",
                "&7Shared by everyone on this profile.", "",
                "&7Balance: &6" + BankMath.commas(Sky.economy().bank(p)) + " coins",
                "&7Capacity: &6" + BankMath.shortNum(Sky.economy().bankCapacity(p)), "", "&eClick to open!"),
                c -> account(c.player(), false).open(c.player()));
        Accounts.Personal a = Accounts.personal(p);
        m.set(15, Icons.of(Material.GOLD_INGOT, "&aPersonal Bank Account",
                "&7Only you can use this account.", "&7Handy in a co-op: nobody else", "&7can withdraw from it.", "",
                "&7Balance: &6" + (a == null || !a.loaded ? "&8loading..." : BankMath.commas(BankMath.coins(a.tenths)) + " coins"),
                "&7Capacity: &6" + BankMath.shortNum(Bank.personalCapacity(p)), "", "&eClick to open!"),
                c -> account(c.player(), true).open(c.player()));
        m.set(13, Icons.of(Material.BOOK, "&eWhich account?",
                "&7Your co-op shares the &6Co-op Account&7:", "&7any member can deposit and withdraw.",
                "", "&7Your &aPersonal Account &7holds up to", "&7" + percent(Bank.personalFraction.get()) + " of the co-op account's capacity."));
        backToSkyMenu(m, 30);
        return m;
    }

    private static String percent(double f) { return BankMath.commas(f * 100) + "%"; }

    private static void backToSkyMenu(Menu m, int slot) {
        if (Sky.has(Features.SkyMenu.class)) m.set(slot, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
    }

    // ---- account ---------------------------------------------------------------------------------------------

    static double balance(Player p, boolean personal) {
        return personal ? Accounts.personalBalance(p) : Sky.economy().bank(p);
    }

    static double capacity(Player p, boolean personal) {
        return personal ? Bank.personalCapacity(p) : Sky.economy().bankCapacity(p);
    }

    static String accountTitle(Player p, boolean personal) {
        if (personal) return "Personal Bank Account";
        return Bank.isCoop(p) ? "Co-op Bank Account" : "Bank Account";
    }

    static Menu account(Player p, boolean personal) {
        Menu m = new Menu(accountTitle(p, personal), 4).chrome();
        m.drawer(menu -> drawAccount(menu, p, personal));
        if (Bank.hasPersonal(p)) m.back("Bank", () -> chooser(p));
        else backToSkyMenu(m, 30);
        String account = accountKey(p, personal);
        if (account != null && !Bank.store.txCached(account)) {
            Bank.store.loadTx(account, 30).thenRun(() -> Bank.sync(() -> {
                if (p.isOnline() && Sky.menus().current(p) == m) m.redraw();
            }));
        }
        return m;
    }

    /** The bank_tx account key for this player's co-op or personal account. */
    static String accountKey(Player p, boolean personal) {
        String pid = Sky.profiles().profileId(p);
        if (pid == null || pid.isEmpty()) return null;
        return personal ? BankStore.personalAccount(pid, p.getUniqueId()) : BankStore.coopAccount(pid);
    }

    private static void drawAccount(Menu m, Player p, boolean personal) {
        double bal = balance(p, personal);
        double purse = Sky.economy().purse(p);
        m.set(11, Icons.of(Material.CHEST, "&aDeposit Coins",
                "&7Current balance: &6" + BankMath.commas(bal),
                "&7Your purse: &6" + BankMath.commas(purse), "",
                "&7Store coins in the bank to keep", "&7them safe while you adventure.", "",
                "&eClick to make a deposit!"), c -> deposit(c.player(), personal).open(c.player()));
        m.set(13, Icons.of(Material.DROPPER, "&aWithdraw Coins",
                "&7Current balance: &6" + BankMath.commas(bal), "",
                "&7Take coins out of the bank to", "&7spend them.", "",
                "&eClick to make a withdrawal!"), c -> withdraw(c.player(), personal).open(c.player()));
        m.set(15, transactionsIcon(p, personal));
        m.set(32, infoIcon(p, personal));
        if (!personal) {
            int tier = Bank.tier(p);
            BankMath.Tier t = Bank.catalog.tier(tier);
            BankMath.Tier next = tier < Bank.catalog.maxTier() ? Bank.catalog.tier(tier + 1) : null;
            m.set(35, Icons.of(Material.GOLD_BLOCK, "&6Bank Upgrades",
                    "&7Current account: &a" + t.name, "&7Capacity: &6" + BankMath.coinWords(t.capacity),
                    next == null ? "&aYour account is fully upgraded!" : "&7Next: &e" + next.name + " &7(" + BankMath.shortNum(next.capacity) + ")",
                    "", "&eClick to view upgrades!"), c -> upgrades(c.player()).open(c.player()));
        }
    }

    private static ItemStack transactionsIcon(Player p, boolean personal) {
        String acct = accountKey(p, personal);
        List<String> lore = new ArrayList<>();
        List<BankStore.TxRow> rows = acct == null ? List.of() : Bank.store.cachedTx(acct, Math.max(1, Bank.historySize.get()));
        long now = System.currentTimeMillis();
        if (acct != null && !Bank.store.txCached(acct)) lore.add("&8Loading...");
        else if (rows.isEmpty()) lore.add("&7No transactions yet.");
        for (BankStore.TxRow r : rows) {
            String who = r.actorName() == null ? "?" : r.actorName();
            String amount = BankMath.commas(BankMath.coins(Math.abs(r.delta())));
            String line;
            if (r.kind().startsWith("upgrade:")) line = "&e* &7Upgraded the account&7, &e" + BankMath.ago(now - r.ts()) + " &7by &b" + who;
            else if (r.kind().equals("interest")) line = "&a+ &6" + amount + "&7, &e" + BankMath.ago(now - r.ts()) + " &7- &bBank Interest";
            else line = (r.delta() >= 0 ? "&a+ &6" : "&c- &6") + amount + "&7, &e" + BankMath.ago(now - r.ts()) + " &7by &b" + who;
            lore.add(line);
        }
        return Icons.of(Material.FILLED_MAP, "&aRecent Transactions", lore);
    }

    private static ItemStack infoIcon(Player p, boolean personal) {
        int tier = Bank.tier(p);
        BankMath.Tier t = Bank.catalog.tier(tier);
        double bal = balance(p, personal);
        double cap = capacity(p, personal);
        double next = Accounts.onePayout(p, bal, cap, personal);
        int milestone = Bank.museumMilestone(p);
        double museum = Bank.museumInterestMultiplier(p);
        List<String> lore = new ArrayList<>();
        lore.add("&7Account: &a" + t.name + (personal ? " &8(personal)" : ""));
        lore.add("&7Balance: &6" + BankMath.commas(bal) + " &7/ &6" + BankMath.shortNum(cap));
        lore.add("");
        lore.add("&7Interest is paid every &e" + Bank.interestMonths.get() + " months &7of the");
        lore.add("&7calendar (every season), even while");
        lore.add("&7you are offline.");
        lore.add("");
        lore.add("&7Next payout in: &b" + BankMath.duration(Bank.millisToInterest()));
        lore.add("&7Projected interest: &6" + BankMath.commas(Math.floor(next)) + " coins");
        lore.add("&7Max per payout: &6" + BankMath.commas((personal
                ? BankMath.maxInterest(tier, Bank.catalog.bands) * Bank.personalInterestScale.get()
                : BankMath.maxInterest(tier, Bank.catalog.bands)) * Math.max(0, Bank.interestMultiplier.get()) * museum) + " coins");
        lore.add("");
        lore.add("&7Museum bonus: " + (museum > 1.0
                ? "&a+" + BankMath.commas((museum - 1.0) * 100) + "% &8(Milestone " + milestone + ")"
                : "&8none yet"));
        lore.add("&8Every Museum milestone adds &a+" + BankMath.commas(Math.max(0, Bank.interestMuseumBonus.get()) * 100) + "%");
        lore.add("&8to every interest payout.");
        lore.add("");
        lore.add("&8The first 10M earns the most; each");
        lore.add("&8band above it earns less.");
        return Icons.of(Material.REDSTONE_TORCH, "&aInformation", lore);
    }

    // ---- deposit / withdraw ----------------------------------------------------------------------------------

    static Menu deposit(Player p, boolean personal) {
        Menu m = new Menu("Bank Deposit", 4).chrome();
        m.back(accountTitle(p, personal), () -> account(p, personal));
        m.drawer(menu -> {
            long purse = BankMath.tenthsOf(Sky.economy().purse(p));
            long whole = depositTenths(p, personal, 1, 1), half = depositTenths(p, personal, 1, 2);
            menu.set(11, amountIcon(Material.CHEST, 64, "&aYour whole purse", whole, "&7Purse after: &6", purse - whole, "&eClick to deposit coins!"),
                    c -> { Accounts.deposit(c.player(), BankMath.coins(depositTenths(c.player(), personal, 1, 1)), personal); account(c.player(), personal).open(c.player()); });
            menu.set(13, amountIcon(Material.CHEST, 32, "&aHalf your purse", half, "&7Purse after: &6", purse - half, "&eClick to deposit coins!"),
                    c -> { Accounts.deposit(c.player(), BankMath.coins(depositTenths(c.player(), personal, 1, 2)), personal); account(c.player(), personal).open(c.player()); });
            menu.set(15, Icons.of(Material.OAK_SIGN, "&aSpecific amount",
                    "&7Your purse: &6" + BankMath.commasTenths(purse), "&7Space left: &6" + BankMath.commasTenths(roomTenths(p, personal)), "",
                    "&eClick to choose an amount!"), c -> askAmount(c.player(), personal, true));
        });
        return m;
    }

    static Menu withdraw(Player p, boolean personal) {
        Menu m = new Menu("Bank Withdrawal", 4).chrome();
        m.back(accountTitle(p, personal), () -> account(p, personal));
        m.drawer(menu -> {
            long bal = BankMath.tenthsOf(balance(p, personal));
            long all = bal, half = BankMath.share(bal, 1, 2), fifth = BankMath.share(bal, 1, 5);
            String left = "&7Left in the account: &6";
            menu.set(10, amountIcon(Material.DROPPER, 64, "&aEverything in the account", all, left, bal - all, "&eClick to withdraw coins!"),
                    c -> { Accounts.withdraw(c.player(), BankMath.coins(withdrawTenths(c.player(), personal, 1, 1)), personal); afterWithdraw(c.player(), personal); });
            menu.set(12, amountIcon(Material.DROPPER, 32, "&aHalf the account", half, left, bal - half, "&eClick to withdraw coins!"),
                    c -> { Accounts.withdraw(c.player(), BankMath.coins(withdrawTenths(c.player(), personal, 1, 2)), personal); afterWithdraw(c.player(), personal); });
            menu.set(14, amountIcon(Material.DROPPER, 1, "&aWithdraw 20%", fifth, left, bal - fifth, "&eClick to withdraw coins!"),
                    c -> { Accounts.withdraw(c.player(), BankMath.coins(withdrawTenths(c.player(), personal, 1, 5)), personal); afterWithdraw(c.player(), personal); });
            menu.set(16, Icons.of(Material.OAK_SIGN, "&aSpecific amount", "&7Balance: &6" + BankMath.commasTenths(bal), "",
                    "&eClick to choose an amount!"), c -> askAmount(c.player(), personal, false));
        });
        return m;
    }

    /** Room left in the account, in tenths (capacity is rounded down: a deposit may never pass it). */
    private static long roomTenths(Player p, boolean personal) {
        return Math.max(0, BankMath.creditTenths(capacity(p, personal)) - BankMath.tenthsOf(balance(p, personal)));
    }

    /**
     * What a "fraction of your purse" deposit moves, in tenths: the share of the purse (rounded down, see
     * {@link BankMath#share}), capped by the room left. The button and the click use this one function, so
     * the amount on the icon is the amount in the chat message.
     */
    static long depositTenths(Player p, boolean personal, long num, long den) {
        return Math.min(BankMath.share(BankMath.tenthsOf(Sky.economy().purse(p)), num, den), roomTenths(p, personal));
    }

    /** What a "fraction of the account" withdrawal moves, in tenths (rounded down; the odd tenth stays). */
    static long withdrawTenths(Player p, boolean personal, long num, long den) {
        return BankMath.share(BankMath.tenthsOf(balance(p, personal)), num, den);
    }

    /** A personal withdrawal completes a moment later (DB first, then the purse): reopen after it lands. */
    private static void afterWithdraw(Player p, boolean personal) {
        if (personal) org.bukkit.Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (Bank.ready(p)) account(p, true).open(p); }, 3L);
        else account(p, false).open(p);
    }

    /** Amount + what is left afterwards, both exact tenths, so "51.2 out, 51.3 left" is visible before the click. */
    private static ItemStack amountIcon(Material mat, int count, String name, long tenths, String leftLabel, long leftTenths, String hint) {
        ItemStack it = tenths >= 1
                ? Icons.of(mat, name, "&7Amount: &6" + BankMath.coinWords(BankMath.coins(tenths)),
                        leftLabel + BankMath.commasTenths(Math.max(0, leftTenths)) + " coins", "", hint)
                : Icons.of(mat, name, "&7Amount: &60 coins", "", "&cNothing to move.");
        it.setAmount(Math.max(1, Math.min(64, count)));
        return it;
    }

    private static void askAmount(Player p, boolean personal, boolean deposit) {
        UUID id = p.getUniqueId();
        Sky.menus().inputNumber(p, deposit ? "Deposit how much?" : "Withdraw how much?", n -> {
            Player live = org.bukkit.Bukkit.getPlayer(id);
            if (live == null || !Bank.ready(live)) return;
            if (n == null) { account(live, personal).open(live); return; }
            if (!(n > 0) || Double.isInfinite(n)) { Msg.error(live, "Enter a positive number of coins."); account(live, personal).open(live); return; }
            if (deposit) { Accounts.deposit(live, n, personal); account(live, personal).open(live); }
            else { Accounts.withdraw(live, n, personal); afterWithdraw(live, personal); }
        });
    }

    // ---- account tiers -----------------------------------------------------------------------------------------

    static Menu upgrades(Player p) {
        Menu m = new Menu("Bank Account Upgrades", 6).chrome();
        m.back(accountTitle(p, false), () -> account(p, false));
        int cur = Bank.tier(p);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        Material[] mats = {Material.IRON_INGOT, Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK,
                Material.DIAMOND, Material.EMERALD, Material.NETHER_STAR};
        for (int i = 0; i <= Bank.catalog.maxTier() && i < slots.length; i++) {
            BankMath.Tier t = Bank.catalog.tier(i);
            Material mat = i < mats.length ? mats[i] : Material.GOLD_BLOCK;
            List<String> lore = new ArrayList<>();
            lore.add("&7Capacity: &6" + BankMath.coinWords(t.capacity));
            lore.add("&7Max interest per payout: &6" + BankMath.commas(BankMath.maxInterest(t.index, Bank.catalog.bands)));
            lore.add("&7Personal account: &6" + BankMath.shortNum(t.capacity * Math.max(0, Bank.personalFraction.get())));
            final int tier = i;
            if (i <= cur) {
                lore.add("");
                lore.add(i == cur ? "&aYour current account!" : "&aUnlocked");
                ItemStack it = Icons.of(mat, "&a" + t.name + " Account", lore);
                if (i == cur) Icons.glow(it);
                m.set(slots[i], it);
            } else {
                lore.add("");
                lore.add("&7Requirements:");
                Accounts.TierCheck check = Accounts.checkTier(p, t);
                for (String l : check.lines()) lore.add(" " + l);
                if (t.sbxp > 0) { lore.add(""); lore.add("&7Reward: &b+" + t.sbxp + " SkyBlock XP"); }
                lore.add("");
                if (i != cur + 1) lore.add("&cUnlock the previous account first!");
                else lore.add(check.ok() ? "&eClick to upgrade!" : "&cYou don't meet the requirements!");
                m.set(slots[i], Icons.of(i == cur + 1 ? mat : Material.GRAY_DYE, (i == cur + 1 ? "&e" : "&c") + t.name + " Account", lore),
                        c -> confirmUpgrade(c.player(), tier));
            }
        }
        m.set(4, Icons.of(Material.BOOK, "&aAccount Upgrades",
                "&7Bigger accounts hold more coins and", "&7unlock higher interest bands.", "",
                "&7Upgrades are paid from your purse and", "&7are shared by the whole co-op."));
        return m;
    }

    private static void confirmUpgrade(Player p, int tier) {
        int cur = Bank.tier(p);
        if (tier != cur + 1) { Msg.error(p, "Unlock the previous account first!"); return; }
        BankMath.Tier t = Bank.catalog.tier(tier);
        Accounts.TierCheck check = Accounts.checkTier(p, t);
        if (!check.ok()) { Msg.error(p, "You don't meet the requirements for the " + t.name + " Account yet."); return; }
        List<String> lore = new ArrayList<>();
        lore.add("&7Upgrade to the &e" + t.name + " Account&7?");
        lore.addAll(check.lines());
        Sky.menus().confirm(p, "Upgrade Account?", lore, () -> {
            Accounts.upgrade(p, tier);
            upgrades(p).open(p);
        }, () -> upgrades(p).open(p));
    }

    // ---- Pocket Bank -----------------------------------------------------------------------------------------------

    static void registerPocket() {
        Sky.abilities().register("bank_pocket_open", use -> {
            Player p = use.player();
            if (!Bank.ready(p) || !pocketReady(p)) return false;
            openRoot(p);
            return false;
        });
        Sky.abilities().register("bank_pocket_deposit", use -> {
            Player p = use.player();
            if (!Bank.ready(p) || !pocketReady(p)) return false;
            Accounts.deposit(p, Sky.economy().purse(p), false);
            return false;
        });
    }

    private static boolean pocketReady(Player p) {
        BankData.Member m = Bank.member(p);
        long cd = Math.max(0, Bank.pocketCooldown.get()) * 1000L;
        long left = m.lastPocketUse + cd - System.currentTimeMillis();
        if (left > 0) {
            Msg.error(p, "Your Pocket Bank is on cooldown for " + Math.max(1, (left + 999) / 1000) + "s.");
            return false;
        }
        m.lastPocketUse = System.currentTimeMillis();
        return true;
    }
}
