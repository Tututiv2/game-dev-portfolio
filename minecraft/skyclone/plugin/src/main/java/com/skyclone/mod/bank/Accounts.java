package com.skyclone.mod.bank;

import com.skyclone.api.Sky;
import com.skyclone.api.economy.EconomyService;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moving coins between the purse and the two kinds of account, interest, and account tiers.
 *
 * <p>The CO-OP account is core's bank ({@code Sky.economy().bank*}): shared by every member, written
 * through on every change. A PERSONAL account (co-op members only, 1/5 of the co-op capacity) is a row
 * in {@code bank_personal}; it is cached while its owner is online and every change goes to the DB in
 * the same order it happened. Withdrawals take from the DB FIRST and pay the purse after, so a crash
 * can lose a withdrawal in flight but never pay it twice.
 */
final class Accounts {

    private Accounts() {}

    static final String GOLD_BLOCK = "ENCHANTED_GOLD_BLOCK";
    static final String GOLD_COLLECTION = "GOLD_INGOT";
    static final String[] BANKER_NPCS = {"bank_banker", "bank_banker_2"};
    static final String[] BANKER_ANCHORS = {"npc.banker", "npc.banker_2"};

    /** A personal account while its owner is online. */
    static final class Personal {
        final String profileId;
        long tenths;
        long lastPeriod = -1;
        boolean loaded;
        boolean busy;
        Personal(String profileId) { this.profileId = profileId; }
    }

    private static final Map<UUID, Personal> PERSONAL = new ConcurrentHashMap<>();

    static EconomyService eco() { return Sky.economy(); }

    // ---- personal account cache ----------------------------------------------------------------------

    static Personal personal(Player p) {
        Personal a = PERSONAL.get(p.getUniqueId());
        String pid = Sky.profiles().profileId(p);
        if (a != null && pid != null && pid.equals(a.profileId)) return a;
        return null;
    }

    static void loadPersonal(Player p) {
        String pid = Sky.profiles().profileId(p);
        if (pid == null || pid.isEmpty()) return;
        Personal a = new Personal(pid);
        PERSONAL.put(p.getUniqueId(), a);
        UUID id = p.getUniqueId();
        Bank.store.loadPersonal(pid, id).thenAccept(row -> Bank.sync(() -> {
            if (PERSONAL.get(id) != a) return;
            a.tenths = row[0];
            a.lastPeriod = row[1];
            a.loaded = true;
            Player live = org.bukkit.Bukkit.getPlayer(id);
            if (live != null && Bank.ready(live)) checkPersonalInterest(live);
        }));
        Bank.store.loadTx(BankStore.personalAccount(pid, id), 30);
        Bank.store.loadTx(BankStore.coopAccount(pid), 30);
    }

    static void unloadPersonal(Player p) { PERSONAL.remove(p.getUniqueId()); }

    static double personalBalance(Player p) {
        Personal a = personal(p);
        return a == null ? 0 : BankMath.coins(a.tenths);
    }

    // ---- deposits & withdrawals ----------------------------------------------------------------------

    /** Deposit up to {@code requested} coins into the chosen account. */
    static void deposit(Player p, double requested, boolean personal) {
        if (!Bank.ready(p)) return;
        double purse = eco().purse(p);
        if (purse < 0.1) { Msg.error(p, "You don't have any coins in your purse to deposit!"); return; }
        if (personal) { depositPersonal(p, requested, purse); return; }
        double room = eco().bankCapacity(p) - eco().bank(p);
        if (room < 0.1) { Msg.error(p, "Your account is full! Upgrade it at a Banker to store more."); return; }
        double amount = BankMath.floorTenth(Math.min(requested, Math.min(purse, room)));
        if (amount < 0.1) { Msg.error(p, "That amount is too small to deposit."); return; }
        if (!eco().bankDeposit(p, amount)) { Msg.error(p, "The deposit could not be made. Try again."); return; }
        String pid = Sky.profiles().profileId(p);
        Bank.store.logTx(BankStore.coopAccount(pid), p.getUniqueId(), p.getName(), "deposit",
                BankMath.creditTenths(amount), BankMath.creditTenths(eco().bank(p)));
        Msg.send(p, "&aDeposited &6" + BankMath.commas(amount) + " coins&a! The account now holds &6"
                + BankMath.commas(eco().bank(p)) + " coins&a.");
        roundedNote(p, requested, amount);
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
        firstDeposit(p);
        if (amount < requested - 0.05 && room <= requested) Msg.send(p, "&7Only &6" + BankMath.commas(amount) + " coins &7fit: the account is now full.");
    }

    private static void depositPersonal(Player p, double requested, double purse) {
        Personal a = personal(p);
        if (a == null || !a.loaded) { Msg.error(p, "Your personal account is still loading. Try again in a moment."); return; }
        if (a.busy) { Msg.error(p, "Please wait for your last transaction to finish."); return; }
        double cap = Bank.personalCapacity(p);
        double room = cap - BankMath.coins(a.tenths);
        if (room < 0.1) { Msg.error(p, "Your personal account is full! Its capacity grows with the co-op account's tier."); return; }
        double amount = BankMath.floorTenth(Math.min(requested, Math.min(purse, room)));
        if (amount < 0.1) { Msg.error(p, "That amount is too small to deposit."); return; }
        if (!eco().withdraw(p, amount, "bank: personal account deposit")) { Msg.error(p, "The deposit could not be made. Try again."); return; }
        long t = BankMath.creditTenths(amount);
        a.tenths += t;
        Bank.store.personalAdd(a.profileId, p.getUniqueId(), t);
        Bank.store.logTx(BankStore.personalAccount(a.profileId, p.getUniqueId()), p.getUniqueId(), p.getName(), "deposit", t, a.tenths);
        Msg.send(p, "&aDeposited &6" + BankMath.commas(amount) + " coins &ainto your personal account! It now holds &6"
                + BankMath.commas(BankMath.coins(a.tenths)) + " coins&a.");
        roundedNote(p, requested, amount);
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
        firstDeposit(p);
    }

    static void withdraw(Player p, double requested, boolean personal) {
        if (!Bank.ready(p)) return;
        if (personal) { withdrawPersonal(p, requested); return; }
        double bal = eco().bank(p);
        if (bal < 0.1) { Msg.error(p, "There are no coins in the account to withdraw!"); return; }
        double amount = BankMath.floorTenth(Math.min(requested, bal));
        if (amount < 0.1) { Msg.error(p, "That amount is too small to withdraw."); return; }
        if (!eco().bankWithdraw(p, amount)) { Msg.error(p, "The withdrawal could not be made. Try again."); return; }
        String pid = Sky.profiles().profileId(p);
        Bank.store.logTx(BankStore.coopAccount(pid), p.getUniqueId(), p.getName(), "withdraw",
                -BankMath.creditTenths(amount), BankMath.creditTenths(eco().bank(p)));
        Msg.send(p, "&aWithdrew &6" + BankMath.commas(amount) + " coins&a! &6" + BankMath.commas(eco().bank(p))
                + " coins &aremain in the account.");
        roundedNote(p, requested, amount);
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f);
    }

    private static void withdrawPersonal(Player p, double requested) {
        Personal a = personal(p);
        if (a == null || !a.loaded) { Msg.error(p, "Your personal account is still loading. Try again in a moment."); return; }
        if (a.busy) { Msg.error(p, "Please wait for your last transaction to finish."); return; }
        double bal = BankMath.coins(a.tenths);
        if (bal < 0.1) { Msg.error(p, "There are no coins in your personal account!"); return; }
        double amount = BankMath.floorTenth(Math.min(requested, bal));
        if (amount < 0.1) { Msg.error(p, "That amount is too small to withdraw."); return; }
        long t = BankMath.creditTenths(amount);
        a.busy = true;
        a.tenths -= t;
        UUID id = p.getUniqueId();
        String pid = a.profileId;
        Bank.store.personalTake(pid, id, t).whenComplete((ok, err) -> Bank.sync(() -> {
            a.busy = false;
            Player live = org.bukkit.Bukkit.getPlayer(id);
            if (err != null || ok == null || !ok) {
                a.tenths += t;   // nothing left the DB: undo the cached change
                if (live != null) Msg.error(live, "The withdrawal could not be made. Try again.");
                if (err != null) Sky.log().warning("[bank] personal withdraw failed for " + id + ": " + err.getMessage());
                return;
            }
            if (live == null || !Bank.ready(live) || !pid.equals(Sky.profiles().profileId(live))) {
                Bank.store.personalAdd(pid, id, t);   // they left in between: put it back
                a.tenths += t;
                return;
            }
            eco().deposit(live, amount, "bank: personal account withdraw");
            Bank.store.logTx(BankStore.personalAccount(pid, id), id, live.getName(), "withdraw", -t, a.tenths);
            Msg.send(live, "&aWithdrew &6" + BankMath.commas(amount) + " coins &afrom your personal account! &6"
                    + BankMath.commas(BankMath.coins(a.tenths)) + " coins &aremain.");
            roundedNote(live, requested, amount);
            Msg.sound(live, Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f);
        }));
    }

    /**
     * Coins move in whole tenths (core stores tenths). When the player TYPED an amount finer than that
     * (51.25) and it was the amount that decided the move, say it was rounded down instead of letting
     * "51.2" look like an arithmetic slip. Menu buttons already pass whole tenths, so they never see this.
     */
    private static void roundedNote(Player p, double requested, double moved) {
        if (!(requested > 0) || Double.isInfinite(requested)) return;
        long asked = BankMath.creditTenths(requested);
        if (Math.abs(requested * 10.0 - asked) < 1e-6) return;          // already a whole tenth
        if (BankMath.creditTenths(moved) != asked) return;               // purse/room/balance capped it instead
        Msg.send(p, "&7Coins move in tenths: &6" + BankMath.commasExact(requested) + " &7was rounded down to &6"
                + BankMath.commas(moved) + "&7.");
    }

    private static void firstDeposit(Player p) {
        BankData.Member m = Bank.member(p);
        Sky.service(Features.Objectives.class).complete(p, "deposit_coins");
        if (!m.firstDeposit) m.firstDeposit = true;
    }

    // ---- interest ----------------------------------------------------------------------------------------

    /** Pay any interest that came due for this player's co-op account and personal account. */
    static void checkInterest(Player p) {
        if (!Bank.ready(p) || Bank.catalog == null) return;
        BankData.Shared s = Bank.shared(p);
        long now = Bank.period();
        if (s.lastInterestPeriod < 0 || s.lastInterestPeriod > now) {
            s.lastInterestPeriod = now;   // first sight (or a calendar rewind): start counting from here
            Sky.profiles().saveNow(p);
        } else if (now > s.lastInterestPeriod) {
            int n = (int) Math.min(now - s.lastInterestPeriod, Math.max(1, Bank.interestCatchup.get()));
            s.lastInterestPeriod = now;
            Sky.profiles().saveNow(p);   // record the payout BEFORE paying: a crash loses it, never doubles it
            double total = payCoopInterest(p, n);
            if (total > 0) {
                s.interestEarned += total;
                String pid = Sky.profiles().profileId(p);
                Bank.store.logTx(BankStore.coopAccount(pid), null, "Bank Interest", "interest",
                        BankMath.creditTenths(total), BankMath.creditTenths(eco().bank(p)));
                Msg.send(p, "&6[Bank] &7" + (n > 1 ? "While you were away, your" : "Your") + " account earned &6"
                        + BankMath.commas(total) + " coins &7of interest" + (n > 1 ? " over &e" + n + " &7payouts" : "") + "!");
                Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.8f);
            }
        }
        checkPersonalInterest(p);
    }

    /** {@code payouts} successive interest payouts into the co-op account; returns the total paid. */
    static double payCoopInterest(Player p, int payouts) {
        double total = 0;
        for (int i = 0; i < payouts; i++) {
            double bal = eco().bank(p);
            double amt = BankMath.floorTenth(onePayout(p, bal, eco().bankCapacity(p), false));
            if (amt < 0.1) break;
            eco().bankCredit(p, amt, "bank: interest");
            total += amt;
        }
        return total;
    }

    /**
     * One interest payout on {@code balance} before rounding: the tier's bands (personal accounts: bands 1/5 as
     * wide), x the interest dial, x the Museum bonus (research 1.3: +2% per milestone, "apply as a multiplier on
     * the computed payout"), then clipped to the room left when the cap dial says so. The menus show the same number.
     */
    static double onePayout(Player p, double balance, double capacity, boolean personal) {
        int tier = Bank.tier(p);
        double amt = personal
                ? BankMath.interestScaled(balance, tier, Bank.catalog.bands, Bank.personalInterestScale.get())
                : BankMath.interest(balance, tier, Bank.catalog.bands);
        amt *= Math.max(0, Bank.interestMultiplier.get());
        amt *= Bank.museumInterestMultiplier(p);
        if (Bank.interestRespectsCap.get()) amt = Math.min(amt, Math.max(0, capacity - balance));
        return Math.max(0, amt);
    }

    static void checkPersonalInterest(Player p) {
        Personal a = personal(p);
        if (a == null || !a.loaded || a.busy) return;
        long now = Bank.period();
        if (a.lastPeriod < 0 || a.lastPeriod > now) {
            a.lastPeriod = now;
            Bank.store.personalPeriod(a.profileId, p.getUniqueId(), now);
            return;
        }
        if (now <= a.lastPeriod) return;
        int n = (int) Math.min(now - a.lastPeriod, Math.max(1, Bank.interestCatchup.get()));
        a.lastPeriod = now;
        Bank.store.personalPeriod(a.profileId, p.getUniqueId(), now);
        double cap = Bank.personalCapacity(p);
        long paid = 0;
        for (int i = 0; i < n; i++) {
            double bal = BankMath.coins(a.tenths);
            long t = BankMath.creditTenths(BankMath.floorTenth(onePayout(p, bal, cap, true)));
            if (t < 1) break;
            a.tenths += t;
            paid += t;
        }
        if (paid > 0) {
            Bank.store.personalAdd(a.profileId, p.getUniqueId(), paid);
            Bank.store.logTx(BankStore.personalAccount(a.profileId, p.getUniqueId()), null, "Bank Interest", "interest", paid, a.tenths);
            Msg.send(p, "&6[Bank] &7Your personal account earned &6" + BankMath.commas(BankMath.coins(paid)) + " coins &7of interest!");
        }
    }

    // ---- access ------------------------------------------------------------------------------------------

    static boolean nearBanker(Player p) {
        double r = Math.max(1, Bank.bankerRange.get());
        Location at = p.getLocation();
        for (String id : BANKER_NPCS) {
            Entity e = Sky.npcs().entity(id);
            if (e != null && e.getWorld().equals(at.getWorld()) && e.getLocation().distanceSquared(at) <= r * r) return true;
        }
        for (String anchor : BANKER_ANCHORS) {
            Location l = Sky.worlds().anchor("hub", anchor);
            if (l != null && l.getWorld() != null && l.getWorld().equals(at.getWorld()) && l.distanceSquared(at) <= r * r) return true;
        }
        return false;
    }

    /**
     * Directions to the nearest banker (playtest: "only at a Banker in the Hub" while standing in the Hub told
     * the player nothing): distance and compass from where they stand, or from the Hub's spawn when elsewhere.
     */
    static String whereIsBanker(Player p) {
        Location at = p.getLocation();
        String bestName = null;
        Location best = null;
        double bestD = Double.MAX_VALUE;
        String[] names = {BankMenus.BANKER_NAME, BankMenus.BANKER_2_NAME};
        for (int i = 0; i < BANKER_NPCS.length; i++) {
            Location l = null;
            try {
                Entity e = Sky.npcs().entity(BANKER_NPCS[i]);
                if (e != null) l = e.getLocation();
                if (l == null) l = Sky.worlds().anchor("hub", BANKER_ANCHORS[i]);
            } catch (RuntimeException ignored) { }
            if (l == null || l.getWorld() == null || !l.getWorld().equals(at.getWorld())) continue;
            double d = l.distanceSquared(at);
            if (d < bestD) { bestD = d; best = l; bestName = names[i]; }
        }
        if (best != null) {
            long blocks = Math.round(Math.sqrt(bestD));
            return "&f" + com.skyclone.api.ui.Text.strip(bestName) + "&c is &e" + blocks
                    + " blocks " + BankMath.compass(best.getX() - at.getX(), best.getZ() - at.getZ()) + "&c of you, inside the Bank.";
        }
        Location spawn = null;
        Location banker = null;
        try {
            spawn = Sky.worlds().anchor("hub", "spawn");
            banker = Sky.worlds().anchor("hub", BANKER_ANCHORS[0]);
        } catch (RuntimeException ignored) { }
        if (spawn != null && banker != null) {
            long blocks = Math.round(Math.hypot(banker.getX() - spawn.getX(), banker.getZ() - spawn.getZ()));
            return "Bankers work in the Bank in the &aHub&c (&a/hub&c), &e" + blocks + " blocks "
                    + BankMath.compass(banker.getX() - spawn.getX(), banker.getZ() - spawn.getZ()) + "&c of where you arrive.";
        }
        return "Bankers work in the Bank in the &aHub&c (&a/hub&c).";
    }

    /** Remote access with the Boon Buff (plus the collection gate), or the global dial. */
    static boolean remoteAllowed(Player p) {
        if (Bank.remoteAnywhere.get()) return true;
        if (!Cookie.active(p)) return false;
        String req = Bank.remoteRequirement.get();
        return req == null || req.isBlank() || Sky.requirements().check(p, req.trim());
    }

    static String remoteDenial(Player p) {
        if (!Cookie.active(p)) return "You can only bank at a Banker! " + whereIsBanker(p)
                + " &cThe &dBoon Buff&c lets you use &a/bank&c anywhere, and so does a &fPocket Bank&c.";
        String req = Bank.remoteRequirement.get();
        return "Remote banking with the &dBoon Buff&c also needs &a" + Sky.requirements().describe(req.trim()) + "&c.";
    }

    // ---- account tiers -----------------------------------------------------------------------------------

    record TierCheck(boolean ok, List<String> lines) {}

    static int enchantedGoldBlocks(Player p) {
        return Sky.items().count(p, GOLD_BLOCK) + Math.max(0, Sky.service(Features.Sacks.class).count(p, GOLD_BLOCK));
    }

    /** Whether the player can buy {@code tier} now, with one checklist line per requirement. */
    static TierCheck checkTier(Player p, BankMath.Tier t) {
        List<String> lines = new ArrayList<>();
        boolean ok = true;
        double coins = Math.ceil(t.coins * Math.max(0, Bank.upgradeCostScale.get()));
        if (coins > 0) {
            boolean have = p != null && eco().purse(p) >= coins;
            ok &= have;
            lines.add(mark(have) + " &6" + BankMath.commas(coins) + " Coins &8(purse)");
        }
        if (t.enchantedGoldBlocks > 0) {
            int have = p == null ? 0 : enchantedGoldBlocks(p);
            boolean b = have >= t.enchantedGoldBlocks;
            ok &= b;
            lines.add(mark(b) + " &a" + t.enchantedGoldBlocks + "x &aEnchanted Gold Block &8(" + have + ")");
        }
        double goldScale = Bank.upgradeGoldScale.get();
        if (t.goldCollection > 0 && goldScale > 0) {
            long need = (long) Math.ceil(t.goldCollection * goldScale);
            long have = p == null ? 0 : Sky.service(Features.Collections.class).count(p, GOLD_COLLECTION);
            boolean b = have >= need;
            ok &= b;
            lines.add(mark(b) + " &7Gold collection &e" + BankMath.shortNum(need) + " &8(" + BankMath.shortNum(have) + ")");
        }
        int per = Bank.upgradeMuseumPerMilestone.get();
        if (t.museumMilestone > 0 && per > 0) {
            String key = museumKey(t.museumMilestone, per);
            boolean b = p != null && Sky.requirements().check(p, key);
            ok &= b;
            boolean byMilestone = !key.startsWith("museum:");
            String have = p == null ? "" : byMilestone ? " &8(you: " + Bank.museumMilestone(p) + ")" : "";
            lines.add(mark(b) + " &7Museum Milestone &e" + t.museumMilestone + have
                    + (byMilestone ? "" : " &8(" + Sky.requirements().describe(key) + "&8)"));
        }
        return new TierCheck(ok, lines);
    }

    private static String mark(boolean ok) { return ok ? "&a✔" : "&c✖"; }

    /**
     * The requirement key for an account tier's Museum milestone (dial {@code bank.upgrade.museum-key}). Wave 5: the
     * default asks progression's {@code museummilestone:<n>} (the research's exact gate); while nothing resolves that
     * prefix it falls back to {@code museum:<milestone x per>} donations, so the gate can never become impossible.
     */
    static String museumKey(int milestone, int per) {
        String template = Bank.upgradeMuseumKey.get();
        if (template == null || template.isBlank()) template = "museum:{donations}";
        template = template.trim();
        int colon = template.indexOf(':');
        String prefix = colon > 0 ? template.substring(0, colon) : template;
        boolean known;
        try { known = Sky.requirements().known(prefix); } catch (RuntimeException e) { known = false; }
        if (!known) template = "museum:{donations}";
        return template.replace("{milestone}", String.valueOf(milestone))
                .replace("{donations}", String.valueOf((long) milestone * Math.max(1, per)));
    }

    /** Buy the next account tier. Everything re-validated here; nothing is taken unless all of it can be. */
    static void upgrade(Player p, int target) {
        if (!Bank.ready(p)) return;
        int cur = Bank.tier(p);
        if (target != cur + 1 || target > Bank.catalog.maxTier()) { Msg.error(p, "You can only upgrade to the next account tier."); return; }
        BankMath.Tier t = Bank.catalog.tier(target);
        TierCheck c = checkTier(p, t);
        if (!c.ok()) { Msg.error(p, "You don't meet every requirement for the " + t.name + " Account yet."); return; }
        double coins = Math.ceil(t.coins * Math.max(0, Bank.upgradeCostScale.get()));
        if (coins > 0 && !eco().withdraw(p, coins, "bank: upgrade to " + t.name + " account")) {
            Msg.error(p, "You don't have enough coins in your purse."); return;
        }
        int need = t.enchantedGoldBlocks;
        int took = need > 0 ? Sky.items().remove(p, GOLD_BLOCK, need) : 0;
        if (took < need) took += Math.max(0, Sky.service(Features.Sacks.class).remove(p, GOLD_BLOCK, need - took));
        if (took < need) {
            // could not collect the blocks after all: undo everything
            if (took > 0) Sky.loot().give(p, GOLD_BLOCK, took, com.skyclone.api.loot.LootSource.OTHER);
            if (coins > 0) eco().deposit(p, coins, "bank: refund of failed upgrade to " + t.name);
            Msg.error(p, "You need " + need + " Enchanted Gold Blocks.");
            return;
        }
        applyTier(p, target);
        Sky.service(Features.SkyLevel.class).award(p, "bank:" + t.id, t.sbxp, t.name + " Bank Account");
        Msg.box(p, "&6&lACCOUNT UPGRADED &e" + t.name + " Account", List.of(
                "&7Capacity: &6" + BankMath.coinWords(t.capacity),
                "&7Max interest per payout: &6" + BankMath.commas(BankMath.maxInterest(t.index, Bank.catalog.bands)) + " coins"));
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
        String pid = Sky.profiles().profileId(p);
        Bank.store.logTx(BankStore.coopAccount(pid), p.getUniqueId(), p.getName(), "upgrade:" + t.id, 0,
                BankMath.creditTenths(eco().bank(p)));
    }

    static void applyTier(Player p, int tier) {
        BankData.Shared s = Bank.shared(p);
        s.tier = Math.max(0, Math.min(tier, Bank.catalog.maxTier()));
        eco().setBankCapacity(p, Bank.capacityFor(s.tier));
        Sky.profiles().saveNow(p);
    }

    /** On load: keep core's capacity in line with the tier (tier capacities are data and may be retuned). */
    static void syncCapacity(Player p) {
        double want = Bank.capacityFor(Bank.tier(p));
        if (Math.abs(eco().bankCapacity(p) - want) > 0.5) eco().setBankCapacity(p, want);
    }
}
