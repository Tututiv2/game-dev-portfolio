package com.skyclone.mod.bank;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * BANK: the bank (co-op + personal accounts, seven account tiers, seasonal interest, history, the
 * Pocket Bank), the Boon Cookie and its buff with bits, the Community Shop (cookie, Bits Shop, fame,
 * timed account/profile upgrades) and the secure player-to-player {@code /trade}.
 *
 * <p>Research: 05 sections 1 (coins, bank, interest, trade), 6 (cookie, bits, fame, Community Shop),
 * 11 (anti-dupe / anti-scam). Data: {@code data/bank/*.json} from {@code tools/gen/bank/gen_bank.py}.
 */
public final class BankModule implements GameModule, Listener {

    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override public String id() { return "bank"; }

    @Override
    public void enable() {
        Bank.catalog = BankCatalog.load(f -> Sky.plugin().getResource("data/bank/" + f));
        Bank.registerDials();
        Bank.store.migrate();
        Bank.store.escrowToDeliveryAtBoot();

        Sky.items().loadJson("bank", "items.json");
        Sky.recipes().loadJson("bank", "recipes.json");

        BankMenus.registerNpcs();
        BankMenus.registerPocket();
        Cookie.register();
        CommunityShop.register();
        CommunityShop.loadReaders();
        CoopSlots.register();
        BankService.register();   // wave 5: Features.Bank + fame multiplier on bits from other sources

        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new Cookie(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new Trades(), Sky.plugin());

        registerCommands();
        BankAdmin.register();
        BankTests.register();

        Sky.sidebar().tabWidget("bank.account", 140, p -> {
            if (!Bank.ready(p)) return List.of();
            List<String> out = new ArrayList<>();
            out.add("&6&lBank");
            out.add(" &7Balance: &6" + BankMath.shortNum(Sky.economy().bank(p)) + "&7/&6" + BankMath.shortNum(Sky.economy().bankCapacity(p)));
            if (Bank.hasPersonal(p)) out.add(" &7Personal: &6" + BankMath.shortNum(Accounts.personalBalance(p)));
            out.add(" &7Interest in: &b" + BankMath.duration(Bank.millisToInterest()));
            return out;
        });

        // interest + upgrade completion + allowance, on the dial's cadence
        final int[] seconds = {0};
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            seconds[0]++;
            if (seconds[0] % Math.max(5, Bank.interestCheckSeconds.get()) != 0) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!Bank.ready(p)) continue;
                try {
                    Accounts.checkInterest(p);
                    CommunityShop.checkUpgrades(p);
                } catch (Throwable t) {
                    Sky.log().warning("[bank] periodic check failed for " + p.getName() + ": " + t);
                }
            }
        }, 40L, 20L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            try { Cookie.tick(); } catch (Throwable t) { Sky.log().warning("[bank] cookie tick failed: " + t); }
            AccountWide.saveAll();
        }, 60L, 400L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            try { Trades.tick(); } catch (Throwable t) { Sky.log().warning("[bank] trade tick failed: " + t); }
        }, 20L, 10L));
    }

    private void registerCommands() {
        Sky.commands().register(SkyCommand.of("bank", "Open your bank account (at a Banker, or anywhere with the Boon Buff)")
                .category("Economy").playerOnly().run((s, a) -> BankMenus.openCommand((Player) s)));
        Sky.commands().register(SkyCommand.of("trade", "Send a trade request, or accept one").usage("<player>")
                .category("Economy").playerOnly().run((s, a) -> {
                    Player p = (Player) s;
                    if (a.length < 1) { p.sendMessage(com.skyclone.api.ui.Text.c("&cUsage: /trade <player>")); return; }
                    String name = a[0];
                    if (!name.matches("[A-Za-z0-9_]{1,16}")) { p.sendMessage(com.skyclone.api.ui.Text.c("&cThat is not a player name.")); return; }
                    Player target = Bukkit.getPlayerExact(name);
                    if (target == null) { p.sendMessage(com.skyclone.api.ui.Text.c("&cThat player is not online.")); return; }
                    Trades.request(p, target);
                }).tab((s, a) -> {
                    List<String> out = new ArrayList<>();
                    if (a.length <= 1) {
                        String pre = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
                        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(s) && o.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(o.getName());
                    }
                    return out;
                }));
        Sky.commands().register(SkyCommand.of("cookie", "Your Boon Buff: time left, bits and perks")
                .alias("boostercookie", "boonbuff").category("Economy").playerOnly()
                .run((s, a) -> { Player p = (Player) s; if (Bank.ready(p)) Cookie.menu(p).open(p); }));
        Sky.commands().register(SkyCommand.of("cshop", "Open the Community Shop (Boon Cookies, Bits Shop, upgrades)")
                .alias("communityshop", "bitsshop").category("Economy").playerOnly()
                .run((s, a) -> CommunityShop.open((Player) s, true)));
    }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        try {
            Accounts.syncCapacity(p);
            Accounts.loadPersonal(p);
            Accounts.checkInterest(p);
            Trades.deliver(p, false);
        } catch (Throwable t) {
            Sky.log().warning("[bank] profile setup failed for " + p.getName() + ": " + t);
        }
        // the Boon Buff, bits, fame and account upgrades follow the account: bring them in first
        AccountWide.load(p, () -> {
            try {
                Cookie.onReady(p);
                CommunityShop.checkUpgrades(p);
            } catch (Throwable t) {
                Sky.log().warning("[bank] account setup failed for " + p.getName() + ": " + t);
            }
        });
    }

    @Override
    public void postEnable() {
        try { CoopSlots.raiseCoreCap(); } catch (Throwable t) { Sky.log().warning("[bank] co-op cap check failed: " + t); }
    }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) {
        Accounts.unloadPersonal(e.player);
        BankService.forget(e.player);
        CoopSlots.forget(e.player);
        try { AccountWide.unload(e.player); } catch (Throwable t) { Sky.log().warning("[bank] account-wide unload failed: " + t); }
    }

    @Override
    public void disable() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        Trades.shutdown();
    }
}
