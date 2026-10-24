package com.skyclone.mod.bank;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wave 5: {@link Features.Bank} for other modules (open the bank, "is the buff on?", the fame-rank bits multiplier),
 * and the fame multiplier on bits from OTHER sources (research 05 section 6.2: "Other sources ... all x fame
 * multiplier": slayer T5, Jacob's contests, pests and visitors, bosses, dungeons...).
 *
 * <p>How the multiplier reaches other sources without anyone changing: every bits gain fires core's
 * {@code CurrencyChange}; for a gain from another module the bank adds {@code base x (mult - 1)} on top, with the
 * fraction carried per player so nothing is minted or lost. Never for our own cookie payouts (already multiplied),
 * Bits Talisman doubles, refunds or admin grants (dial {@code bank.fame.bits-exclude}).
 *
 * <p>No double multiplier: a module that calls {@link #bitsMultiplier} is remembered as applying it itself (dial
 * {@code bank.fame.learn-callers}) and its reason prefix is skipped from then on; {@code bank.fame.self-applying}
 * lists such modules by hand. A module that wants the bank to do it simply adds the base amount.
 */
final class BankService implements Features.Bank, Listener {

    /** Modules (ledger-reason prefixes) that multiply their own bits; learned from bitsMultiplier callers. */
    private static final Set<String> LEARNED = ConcurrentHashMap.newKeySet();
    /** Fraction of a bit owed to each player (in memory: at most one bit per player per restart). */
    private static final Map<UUID, double[]> CARRY = new ConcurrentHashMap<>();

    static void register() {
        BankService s = new BankService();
        Sky.provide(Features.Bank.class, s);
        Bukkit.getPluginManager().registerEvents(s, Sky.plugin());
    }

    // ---- Features.Bank -----------------------------------------------------------------------------------

    @Override public void openMenu(Player p) { if (p != null && Bank.ready(p)) BankMenus.openCommand(p); }

    @Override public boolean cookieActive(Player p) { return p != null && Cookie.active(p); }

    @Override public long cookieMillisLeft(Player p) { return p == null ? 0 : Cookie.millisLeft(p); }

    @Override
    public double bitsMultiplier(Player p) {
        if (Bank.fameLearnCallers != null && Bank.fameLearnCallers.get()) learnCaller();
        if (p == null || !Bank.ready(p) || Bank.catalog == null) return 1.0;
        return Math.max(1.0, Bank.fameMultiplier(p));
    }

    /** Remember the module that asked: it applies the multiplier itself, so the automatic bonus skips it. */
    private static void learnCaller() {
        try {
            List<String> frames = StackWalker.getInstance().walk(st -> st.limit(24).map(f -> f.getClassName()).toList());
            String mod = BankMath.callerModule(frames);
            if (mod != null && LEARNED.add(mod)) {
                Sky.log().info("[bank] module '" + mod + "' asked Features.Bank.bitsMultiplier: its bits are treated as already multiplied");
            }
        } catch (RuntimeException ignored) { }
    }

    static Set<String> selfApplying() {
        Set<String> out = new TreeSet<>(LEARNED);
        for (String s : Bank.csv(Bank.fameSelfApplying.get())) out.add(s.toLowerCase(java.util.Locale.ROOT).replace(":", ""));
        return out;
    }

    // ---- fame multiplier on other sources -------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBits(SkyEvents.CurrencyChange e) {
        if (!"bits".equals(e.currency) || !(e.delta > 0) || e.player == null) return;
        if (!Bank.fameOtherSources.get()) return;
        Player p = e.player;
        if (!Bank.accountReady(p)) return;   // fame is account-wide: wait until it is the real one
        if (!BankMath.fameAppliesTo(e.reason, Bank.csv(Bank.fameSources.get()), Bank.csv(Bank.fameExclude.get()), selfApplying())) return;
        double mult = Bank.fameMultiplier(p);
        if (!(mult > 1.0)) return;
        long base = Math.round(e.delta);
        double[] carry = CARRY.computeIfAbsent(p.getUniqueId(), k -> new double[1]);
        long bonus;
        synchronized (carry) { bonus = BankMath.fameBonus(base, mult, carry); }
        if (bonus <= 0) return;
        String why = e.reason == null ? "" : e.reason;
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!p.isOnline() || !Bank.ready(p)) return;
            Sky.economy().addBits(p, bonus, "bank: fame multiplier on " + (why.length() > 48 ? why.substring(0, 48) : why));
            if (Bank.fameNotify.get()) {
                BankMath.FameRank r = Bank.catalog.fameRank(Bank.member(p).fame);
                Msg.send(p, "&b+" + BankMath.commas(bonus) + " Bits &7from your fame rank &e" + (r == null ? "" : r.name)
                        + " &8(" + BankMath.commas(mult) + "x)");
            }
        });
    }

    static void forget(Player p) { if (p != null) CARRY.remove(p.getUniqueId()); }

    /** For self-tests: the decision the listener makes for this reason. */
    static boolean wouldMultiply(String reason) {
        return BankMath.fameAppliesTo(reason, Bank.csv(Bank.fameSources.get()), Bank.csv(Bank.fameExclude.get()), selfApplying());
    }

    static List<String> learned() { return new ArrayList<>(LEARNED); }
}
