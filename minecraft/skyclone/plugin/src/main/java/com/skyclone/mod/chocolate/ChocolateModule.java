package com.skyclone.mod.chocolate;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CHOCOLATE: the Chocolate Factory idle game and Hopsworth's Hunt (research/08 section 6.6).
 *
 * <p>The factory: click the cookie, hire seven rabbit employees, upgrade the barn, Hand-Baked Chocolate, the Time
 * Tower, the Rabbit Shrine and Coach Longstride, prestige through six factory tiers, catch stray (and golden)
 * rabbits, climb factory and shop milestones, spend chocolate in the Chocolate Shop. Production runs online and
 * offline. The Hunt: through Spring, every listed island hides one egg per meal; each holds one of 577 Chocolate
 * Rabbits (new = permanent CpS and multiplier; duplicate = chocolate). Data: {@code data/chocolate/*.json} from
 * {@code tools/gen/chocolate/gen_chocolate.py}.
 */
public final class ChocolateModule implements GameModule, Listener {

    private final List<BukkitTask> tasks = new ArrayList<>();
    private int seconds;

    @Override public String id() { return "chocolate"; }

    @Override
    public void enable() {
        Choc.catalog = ChocCatalog.load(f -> Sky.plugin().getResource("data/chocolate/" + f));
        Choc.registerDials();
        Leaderboard.migrate();
        Sky.items().loadJson("chocolate", "items.json");
        Sky.calendar().register(CalendarEvent.onDate(Choc.HUNT, Choc.HUNT_NAME, 1, 1, 93));   // Early Spring 1 -> Late Spring 31
        Hunt.init();

        Sky.abilities().register("chocolate_egglocator", use -> Hunt.seek(use.player()));
        Sky.abilities().register("chocolate_add_rabbit", this::addRabbitItem);
        Sky.abilities().register("time_warp", this::timeWarp);
        Sky.abilities().register("chocolate_hot_mixin", this::drinkMixin);
        // Hot Chocolate Mixin: +15 Pet Luck while it runs (NEU lore); the +0.05x is in Factory.mixin
        Sky.stats().addProvider("chocolate_mixin", (p, into) -> {
            if (Factory.mixinActive(p)) into.add(com.skyclone.api.stat.Stat.PET_LUCK, 15);
        });
        Sky.requirements().register("chocolate", new Resolver());

        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new Hunt(), Sky.plugin());

        registerCommands();
        ChocAdmin.register();
        ChocTests.register();

        Sky.sidebar().section("chocolate.hunt", 400, p -> {
            if (!Hunt.active() || !Choc.ready(p)) return List.of();
            boolean show;
            try { show = Sky.service(Features.Settings.class).flag(p, "sidebar.hoppity", true); } catch (Throwable t) { show = true; }
            if (!show) return List.of();
            ChocData d = Choc.data(p);
            long abs = Hunt.nowAbs();
            StringBuilder sb = new StringBuilder("&dHunt: ");
            for (ChocCatalog.Meal m : Hunt.meals()) sb.append(Hunt.mealGlyph(d, m, abs));
            return List.of(sb.toString());
        });
        Sky.sidebar().tabWidget("chocolate", 150, p -> {
            if (!Choc.ready(p)) return List.of();
            ChocData d = Choc.data(p);
            if (d.allTime <= 0 && d.unique() == 0) return List.of();
            return List.of("&6&lChocolate Factory " + Text.roman(d.prestige),
                    " &7Chocolate: &6" + Choc.shortNum(d.chocolate),
                    " &7Per second: &6" + Choc.shortNum(Factory.cps(p, d)),
                    " &7Rabbits: &a" + d.unique() + "&7/&a" + Choc.catalog.rabbits.size());
        });

        // production every second (+ Time Tower charges, egg finder)
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::secondTick, 40L, 20L));
        // the eggs on the islands
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            try { Hunt.tick(); } catch (Throwable t) { Sky.log().warning("[chocolate] egg tick failed: " + t); }
        }, 60L, 40L));
    }

    @Override
    public void postEnable() {
        try { HuntMenus.registerNpc(); } catch (Throwable t) { Sky.log().warning("[chocolate] Hopsworth not registered: " + t); }
    }

    @Override
    public void disable() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Choc.ready(p)) continue;
            try { Factory.tick(p, Choc.data(p), now, false); } catch (Throwable ignored) { }
        }
        Hunt.clearAll();
    }

    private void secondTick() {
        seconds++;
        long now = System.currentTimeMillis();
        boolean every10 = seconds % 10 == 0;
        long abs = every10 ? Hunt.nowAbs() : 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Choc.ready(p)) continue;
            try {
                ChocData d = Choc.data(p);
                Factory.tick(p, d, now, false);
                Hunt.eggFinder(p, d, 1);
                if (every10) Hunt.accrueMissed(d, abs);
            } catch (Throwable t) {
                Sky.log().warning("[chocolate] tick failed for " + p.getName() + ": " + t);
            }
        }
        if (seconds % Math.max(60, Choc.lbMinutes.get() * 60) == 0) Leaderboard.pushAll();
    }

    private void registerCommands() {
        Sky.commands().register(SkyCommand.of("cf", "Open your Chocolate Factory").alias("chocolatefactory", "chocolate")
                .category("Events").playerOnly().run((s, a) -> FactoryMenus.openFactory((Player) s)));
        Sky.commands().register(SkyCommand.of("hoppity", "Hopsworth's Hunt: today's eggs, the Egg Seeker and his offer")
                .alias("hopsworth").category("Events").playerOnly().run((s, a) -> HuntMenus.openHunt((Player) s, false)));
        Sky.commands().register(SkyCommand.of("rabbits", "Hopsworth's Collection: every Chocolate Rabbit")
                .alias("chocolaterabbits").category("Events").playerOnly().run((s, a) -> FactoryMenus.openCollection((Player) s, "ALL", 0)));
        Sky.commands().register(SkyCommand.of("chocshop", "Open the Chocolate Shop").alias("chocolateshop")
                .category("Events").playerOnly().run((s, a) -> FactoryMenus.openShop((Player) s)));
        Sky.commands().register(SkyCommand.of("bountyhunter", "Open the Rabbit Bounty Hunter (missed Hunt eggs)").alias("rabbithitman")
                .category("Events").playerOnly().run((s, a) -> FactoryMenus.openBounty((Player) s)));
    }

    /** FACTION_RABBIT_* items: right-click moves the boxed rabbit into the factory. */
    private boolean addRabbitItem(AbilityService.Use use) {
        Player p = use.player();
        if (!Choc.ready(p) || use.itemDef() == null || use.itemDef().extra == null || !use.itemDef().extra.has("rabbit")) return false;
        ChocCatalog.Rabbit r = Choc.catalog.rabbit(use.itemDef().extra.get("rabbit").getAsString());
        if (r == null) return false;
        ChocData d = Choc.data(p);
        if (d.has(r.id)) { Msg.send(p, "&cYou already have " + Choc.rabbitName(r) + " &cin your factory!"); return false; }
        if (d.unique() >= Factory.barnCapacity(d)) { Msg.send(p, "&cYour Rabbit Barn is full! Upgrade it first."); return false; }
        if (Sky.items().remove(p, use.itemDef().id, 1) < 1) return false;
        Factory.grant(p, r, "item", false, false);
        return true;
    }

    // ---- events ---------------------------------------------------------------------------------------------

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        try {
            ChocData d = Choc.data(p);
            long now = System.currentTimeMillis();
            Factory.tick(p, d, now, true);
            Hunt.accrueMissed(d, Hunt.nowAbs());
            Factory.checkMilestones(p, d);
            Factory.awardPrestigeXp(p, d);
            if (Hunt.active() && d.hitmanEggs > 0) {
                Msg.send(p, "&d&lHOPSWORTH'S HUNT &7The &aRabbit Bounty Hunter &7is keeping &6" + d.hitmanEggs + " &7eggs for you! &e/bountyhunter");
            }
        } catch (Throwable t) {
            Sky.log().warning("[chocolate] profile setup failed for " + p.getName() + ": " + t);
        }
    }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) {
        try {
            if (Choc.ready(e.player)) Factory.tick(e.player, Choc.data(e.player), System.currentTimeMillis(), false);
            Leaderboard.push(e.player);
        } catch (Throwable ignored) { }
        Factory.forget(e.player.getUniqueId());
        FactoryMenus.forget(e.player.getUniqueId());
    }

    @EventHandler
    public void onCalendarStart(SkyEvents.CalendarStart e) {
        if (Choc.HUNT.equals(e.eventId)) Hunt.announce(true);
    }

    @EventHandler
    public void onCalendarEnd(SkyEvents.CalendarEnd e) {
        if (!Choc.HUNT.equals(e.eventId)) return;
        Hunt.clearAll();
        Hunt.announce(false);
    }

    @EventHandler
    public void onFish(SkyEvents.FishCaught e) {
        try { Hunt.onFish(e.player); } catch (Throwable t) { Sky.log().warning("[chocolate] fishing egg failed: " + t); }
    }

    /** HOT_CHOCOLATE_MIXIN: 36 h (stacking) of +0.05x factory and +15 Pet Luck; needs an active Boon Cookie (NEU lore). */
    private boolean drinkMixin(AbilityService.Use use) {
        Player p = use.player();
        if (!Choc.ready(p)) return false;
        if (!Factory.cookie(p)) {
            Msg.send(p, "&cYou need an active Boon Cookie to drink a mixin!");
            return false;
        }
        if (Sky.items().remove(p, "HOT_CHOCOLATE_MIXIN", 1) < 1) return false;
        ChocData d = Choc.data(p);
        long now = System.currentTimeMillis();
        d.hotMixinUntil = Math.max(now, d.hotMixinUntil) + (long) (Math.max(0.01, Choc.mixinHours.get()) * 3_600_000L);
        Sky.stats().invalidate(p);
        Msg.send(p, "&6&lHOT CHOCOLATE &7Mixin active for &a" + Text.duration((d.hotMixinUntil - now) / 1000) + "&7: &6+0.05x Chocolate&7, &d+15 Pet Luck&7.");
        return true;
    }

    /**
     * The Enchanted Clock's Time Warp (ability id {@code time_warp} on materials' {@code ENCHANTED_TIME_CLOCK}):
     * one hour of factory production, then a cooldown kept in the profile ({@code chocolate.time-warp-cooldown-hours},
     * reference 48 h) so relogging cannot reset it. The item is not consumed (reference).
     */
    private boolean timeWarp(AbilityService.Use use) {
        Player p = use.player();
        if (!Choc.ready(p)) return false;
        ChocData d = Choc.data(p);
        long now = System.currentTimeMillis();
        if (d.timeWarpAt > now) {
            Msg.send(p, "&cTime Warp is on cooldown for " + Text.duration((d.timeWarpAt - now + 999) / 1000) + ".");
            return false;
        }
        double made = Factory.cps(p, d) * 3600;
        Factory.earn(p, d, made);
        d.timeWarpAt = now + (long) (Math.max(0, Choc.timeWarpHours.get()) * 3_600_000L);
        Msg.send(p, "&6&lTIME WARP &7An hour passed in a blink: &6+" + Choc.num(made) + " Chocolate");
        return true;
    }

    // ---- requirement keys -----------------------------------------------------------------------------------

    /**
     * {@code chocolate:prestige:<n>} (Chocolate Factory tier), {@code chocolate:rabbit:<id>} (a rabbit in the
     * collection, e.g. Masked Hopper's Cape), {@code chocolate:rabbits:<n>} (unique rabbits found).
     */
    static final class Resolver implements Requirements.Resolver {
        @Override
        public boolean check(Player p, String[] parts) {
            if (parts.length < 3 || !Choc.ready(p)) return false;
            ChocData d = Choc.data(p);
            String kind = parts[1].toLowerCase(Locale.ROOT);
            try {
                return switch (kind) {
                    case "prestige", "cf" -> d.prestige >= Integer.parseInt(parts[2]);
                    case "rabbit" -> d.has(parts[2].toLowerCase(Locale.ROOT));
                    case "rabbits" -> d.unique() >= Integer.parseInt(parts[2]);
                    default -> false;
                };
            } catch (NumberFormatException ex) {
                return false;
            }
        }

        @Override
        public String describe(String[] parts) {
            if (parts.length < 3) return "&cChocolate Factory";
            String kind = parts[1].toLowerCase(Locale.ROOT);
            try {
                return switch (kind) {
                    case "prestige", "cf" -> "&6Chocolate Factory " + Text.roman(Integer.parseInt(parts[2]));
                    case "rabbit" -> {
                        ChocCatalog.Rabbit r = Choc.catalog == null ? null : Choc.catalog.rabbit(parts[2]);
                        yield (r == null ? "&d" + parts[2] : Choc.rabbitName(r)) + " &7in Hopsworth's Collection";
                    }
                    case "rabbits" -> "&a" + parts[2] + " Chocolate Rabbits";
                    default -> "&cChocolate Factory";
                };
            } catch (NumberFormatException ex) {
                return "&cChocolate Factory";
            }
        }
    }
}
