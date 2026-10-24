package com.skyclone.mod.chocolate;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Chocolate Factory chest menu (slot layout follows the reference: cookie 13, prestige 27, employees 28-34,
 * barn 35, Hand-Baked 37, Time Tower 38, Shrine 39, Coach 41, production 45, Bounty Hunter 51, leaderboard 52,
 * milestones 53) plus the Collection, Shop, Milestones and Bounty Hunter sub-menus.
 */
final class FactoryMenus {

    private FactoryMenus() {}

    static final int COOKIE = 13, PRESTIGE = 27, BARN = 35, HAND = 37, TOWER = 38, SHRINE = 39, COACH = 41,
            INFO = 45, COLLECTION = 46, SHOP = 47, BACK = 48, HUNT = 50, BOUNTY = 51, LEADERBOARD = 52, MILESTONES = 53;
    static final int[] EMPLOYEE_SLOTS = {28, 29, 30, 31, 32, 33, 34};
    /** Where a stray rabbit may pop up: the top three rows except the cookie. */
    static final int[] STRAY_SLOTS = {0, 1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26};

    /** A stray rabbit in an open factory menu. */
    static final class Stray {
        int slot;
        long until;
        String golden;   // null = plain
        int seconds;
    }

    /** Per-viewer state of an open factory: seconds open and the current stray. */
    static final class Session {
        int secondsOpen;
        Stray stray;
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    static Session session(Player p) { return SESSIONS.get(p.getUniqueId()); }

    // ---- main menu ------------------------------------------------------------------------------------------

    static void openFactory(Player p) {
        if (!Choc.ready(p)) return;
        Session s = new Session();
        Menu m = new Menu("Chocolate Factory", 6);
        m.drawer(menu -> {
            s.secondsOpen++;
            strayTick(p, s);
            draw(p, menu, s);
        }).refreshEvery(20);
        m.onClose(pl -> SESSIONS.remove(pl.getUniqueId(), s));
        draw(p, m, s);
        m.open(p);
        SESSIONS.put(p.getUniqueId(), s);
    }

    static String employeeTexture(int i) {
        String rarity = ChocMath.RARITIES[Math.min(i, ChocMath.RARITIES.length - 1)];
        for (ChocCatalog.Rabbit r : Choc.catalog.rabbits) if (r.rarity.equals(rarity) && r.texture != null) return r.texture;
        return null;
    }

    static void draw(Player p, Menu m, Session s) {
        if (!Choc.ready(p)) return;
        ChocData d = Choc.data(p);
        long now = System.currentTimeMillis();
        ChocMath.Production prod = Factory.production(p, d, now);
        ChocCatalog.Factory f = Choc.catalog.factory;
        m.chrome();

        m.set(COOKIE, cookieIcon(d, prod), c -> {
            Factory.click(c.player());
            Session ss = session(c.player());
            if (ss != null && Choc.ready(c.player())) {
                ChocData dd = Choc.data(c.player());
                c.menu().set(COOKIE, cookieIcon(dd, Factory.production(c.player(), dd, System.currentTimeMillis())), c.menu().handler(COOKIE));
            }
        });

        m.set(PRESTIGE, prestigeIcon(d), c -> prestigeConfirm(c.player()));

        for (int i = 0; i < f.employees.size() && i < EMPLOYEE_SLOTS.length; i++) {
            ChocCatalog.Employee e = f.employees.get(i);
            int idx = i;
            m.set(EMPLOYEE_SLOTS[i], employeeIcon(p, d, e, idx), c -> {
                Factory.buyEmployee(c.player(), e.id, c.shift() ? 10 : 1);
                draw(c.player(), c.menu(), s);
            });
        }

        m.set(BARN, barnIcon(d), c -> { Factory.buyBarn(c.player()); draw(c.player(), c.menu(), s); });
        m.set(HAND, handIcon(d), c -> { Factory.buyHandBaked(c.player()); draw(c.player(), c.menu(), s); });
        m.set(TOWER, towerIcon(d, now), c -> {
            if (c.right() || Choc.data(c.player()).timeTower == 0) Factory.buyUpgrade(c.player(), "tower");
            else Factory.activateTower(c.player());
            draw(c.player(), c.menu(), s);
        });
        m.set(SHRINE, upgradeIcon(d, "shrine"), c -> { Factory.buyUpgrade(c.player(), "shrine"); draw(c.player(), c.menu(), s); });
        m.set(COACH, upgradeIcon(d, "coach"), c -> { Factory.buyUpgrade(c.player(), "coach"); draw(c.player(), c.menu(), s); });

        m.set(INFO, productionIcon(p, d, prod));
        m.set(COLLECTION, Icons.skull(employeeTexture(4), "&aHopsworth's Collection",
                List.of("&7Every Chocolate Rabbit you have found", "&7and where the rest are hiding.", "",
                        "&7Found: &a" + d.unique() + "&7/&a" + Choc.catalog.rabbits.size(), "", "&eClick to view!")),
                c -> openCollection(c.player(), "ALL", 0));
        m.set(SHOP, Icons.of(Material.EMERALD, "&aChocolate Shop",
                "&7Spend Chocolate on talismans, tools", "&7and cosmetics.", "",
                "&7Chocolate spent: &6" + Choc.num(d.spent), "", "&eClick to browse!"), c -> openShop(c.player()));
        m.set(BACK, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        m.set(HUNT, huntIcon(d), c -> HuntMenus.openHunt(c.player(), false));
        m.set(BOUNTY, bountyIcon(d), c -> openBounty(c.player()));
        m.set(LEADERBOARD, Icons.of(Material.OAK_SIGN, "&aChocolate Leaderboard",
                "&7The players whose factories have", "&7made the most Chocolate ever.", "",
                "&7Your all-time Chocolate: &6" + Choc.num(d.allTime), "", "&eClick to view!"), c -> Leaderboard.open(c.player()));
        m.set(MILESTONES, Icons.of(Material.LADDER, "&aChocolate Milestones",
                "&7Rabbits that join once your factory", "&7has made (or spent) enough.", "",
                "&7All-time: &6" + Choc.num(d.allTime), "&7Spent in the shop: &6" + Choc.num(d.spent), "", "&eClick to view!"),
                c -> openMilestones(c.player(), false));

        if (s != null && s.stray != null) {
            Stray st = s.stray;
            m.set(st.slot, strayIcon(st), c -> catchStray(c.player(), c.menu(), s));
        }
    }

    static ItemStack cookieIcon(ChocData d, ChocMath.Production prod) {
        List<String> lore = new ArrayList<>();
        lore.add("&6" + Choc.num(prod.cps()) + " &7Chocolate per second");
        lore.add("&7All-time: &6" + Choc.num(d.allTime));
        lore.add("");
        lore.add("&7Each click makes &6" + d.handBaked + " Chocolate&7.");
        lore.add("");
        lore.add("&eClick to make Chocolate!");
        return Icons.of(Material.COOKIE, "&6" + Choc.num(d.chocolate) + " Chocolate", lore);
    }

    static ItemStack prestigeIcon(ChocData d) {
        List<String> lore = new ArrayList<>();
        int next = d.prestige + 1;
        double req = ChocMath.prestigeRequirement(Choc.catalog, next);
        lore.add("&7Current bonus: &6+" + Factory.fmtMult(ChocMath.prestigeBonus(Choc.catalog, d.prestige)));
        lore.add("");
        if (req < 0) {
            lore.add("&aYour factory is at its highest tier!");
            return Icons.glow(Icons.of(Material.DROPPER, "&6Chocolate Factory " + Text.roman(d.prestige), lore));
        }
        lore.add("&7Prestige to &6Chocolate Factory " + Text.roman(next) + "&7:");
        lore.add(" &8- &6+" + Factory.fmtMult(ChocMath.prestigeBonus(Choc.catalog, next)) + " &7production");
        lore.add(" &8- &7Employees up to level &a" + (Choc.catalog.factory.levelCapBase + Choc.catalog.factory.levelCapPerPrestige * next));
        for (ChocCatalog.Employee e : Choc.catalog.factory.employees) if (e.prestige == next) lore.add(" &8- &7Hire " + e.name);
        if (Choc.catalog.factory.timeTower.prestige == next) lore.add(" &8- &7Unlocks the &dTime Tower");
        if (Choc.catalog.factory.shrine.prestige == next) lore.add(" &8- &7Unlocks the &dRabbit Shrine");
        if (Choc.catalog.factory.coach.prestige == next) lore.add(" &8- &7Unlocks &dCoach Longstride");
        lore.add("");
        lore.add("&7Chocolate this prestige:");
        lore.add("&6" + Choc.shortNum(d.thisPrestige) + "&7/&6" + Choc.shortNum(req) + " " + Text.bar(Math.min(1, d.thisPrestige / req), 20));
        lore.add("");
        lore.add("&cResets your Chocolate, employees and");
        lore.add("&cHand-Baked Chocolate. Rabbits stay.");
        lore.add("");
        lore.add(d.thisPrestige >= req ? "&eClick to prestige!" : "&cNot enough Chocolate yet!");
        return Icons.of(Material.DROPPER, "&6Chocolate Factory " + Text.roman(d.prestige), lore);
    }

    static ItemStack employeeIcon(Player p, ChocData d, ChocCatalog.Employee e, int index) {
        int lvl = d.level(e.id);
        if (d.prestige < e.prestige) {
            return Icons.of(Material.GRAY_DYE, "&c" + e.name, "&7Produces &6+" + e.cps + " Chocolate &7per second", "&7for every level.", "",
                    "&cRequires Chocolate Factory " + Text.roman(e.prestige) + "!");
        }
        ChocCatalog.Factory f = Choc.catalog.factory;
        int cap = ChocMath.employeeCap(f, e, d.prestige, Factory.youth(p));
        List<String> lore = new ArrayList<>();
        lore.add("&7Produces &6+" + e.cps + " Chocolate &7per second");
        lore.add("&7for every level.");
        lore.add("");
        lore.add("&7Level: &e" + lvl + "&7/&e" + cap + " " + Factory.rankTitle(lvl));
        lore.add("&7Making: &6+" + Choc.num((double) e.cps * lvl) + " &7Chocolate per second");
        lore.add("");
        if (lvl >= cap) {
            lore.add("&cMax level for this factory tier!");
        } else {
            double one = Factory.scaled(ChocMath.employeeCost(e, f.employeeGrowth, lvl));
            int ten = Math.min(10, cap - lvl);
            double tenCost = 0;
            for (int i = 0; i < ten; i++) tenCost += Factory.scaled(ChocMath.employeeCost(e, f.employeeGrowth, lvl + i));
            lore.add("&7Cost: &6" + Choc.num(one) + " Chocolate");
            lore.add("&7Cost x" + ten + ": &6" + Choc.num(tenCost) + " Chocolate");
            lore.add("");
            lore.add(d.chocolate >= one ? (lvl == 0 ? "&eClick to hire!" : "&eClick to promote! &8(Shift: x" + ten + ")") : "&cNot enough Chocolate!");
        }
        ItemStack it = Icons.skull(employeeTexture(index), (lvl > 0 ? "&a" : "&f") + e.name + " &8[" + lvl + "]", lore);
        return Icons.amount(it, Math.max(1, Math.min(64, lvl)));
    }

    static ItemStack barnIcon(ChocData d) {
        ChocCatalog.Upgrade b = Choc.catalog.factory.barn;
        int cap = ChocMath.barnCapacity(b, d.barn);
        List<String> lore = new ArrayList<>();
        lore.add("&7Room for the unique rabbits you find.");
        lore.add("&7Kept through every prestige.");
        lore.add("");
        lore.add("&7Rabbits: &a" + d.unique() + "&7/&a" + cap);
        lore.add("");
        if (d.barn >= Factory.barnMax()) lore.add("&aThe barn holds every rabbit there is!");
        else {
            lore.add("&7Upgrade to hold &a" + ChocMath.barnCapacity(b, d.barn + 1) + " &7rabbits.");
            lore.add("&7Cost: &6" + Choc.num(Factory.scaled(ChocMath.barnCost(b, d.barn))) + " Chocolate");
            lore.add("");
            lore.add("&eClick to upgrade!");
        }
        return Icons.of(d.unique() >= cap ? Material.RED_TERRACOTTA : Material.OAK_FENCE_GATE, "&aRabbit Barn " + Text.roman(d.barn), lore);
    }

    static ItemStack handIcon(ChocData d) {
        ChocCatalog.Upgrade u = Choc.catalog.factory.handBaked;
        List<String> lore = new ArrayList<>();
        lore.add("&7Each click on the cookie makes");
        lore.add("&6" + d.handBaked + " Chocolate&7.");
        lore.add("");
        if (d.handBaked >= u.max) lore.add("&aMaxed!");
        else {
            lore.add("&7Next level: &6" + (d.handBaked + 1) + " &7per click");
            lore.add("&7Cost: &6" + Choc.num(Factory.scaled(ChocMath.handBakedCost(u, d.handBaked))) + " Chocolate");
            lore.add("");
            lore.add("&eClick to upgrade!");
        }
        return Icons.of(Material.COOKIE, "&dHand-Baked Chocolate " + Text.roman(d.handBaked), lore);
    }

    static ItemStack towerIcon(ChocData d, long now) {
        ChocCatalog.Upgrade u = Choc.catalog.factory.timeTower;
        List<String> lore = new ArrayList<>();
        if (d.prestige < u.prestige) {
            return Icons.of(Material.GRAY_DYE, "&cTime Tower", "&7Charges that speed the factory up", "&7for an hour at a time.", "",
                    "&cRequires Chocolate Factory " + Text.roman(u.prestige) + "!");
        }
        Factory.regenTower(d, now);
        lore.add("&7Use a charge to make the factory");
        lore.add("&7run &d+" + Factory.fmtMult(Math.max(1, d.timeTower) * u.perLevel) + " &7faster for &a" + Text.duration(Factory.towerMillis(d) / 1000) + "&7.");
        lore.add("&7A charge returns every &a" + (int) u.chargeHours + "h&7.");
        lore.add("");
        if (d.timeTower > 0) {
            lore.add("&7Charges: &a" + Math.max(0, d.towerCharges) + "&7/&a" + Factory.towerMaxCharges(d));
            if (d.towerChargeAt > now) lore.add("&7Next charge in &a" + Text.duration((d.towerChargeAt - now) / 1000));
            if (d.towerUntil > now) lore.add("&dRUNNING! &7Ends in &a" + Text.duration((d.towerUntil - now) / 1000));
            lore.add("");
        }
        if (d.timeTower < u.max) {
            lore.add("&7Upgrade to &d+" + Factory.fmtMult((d.timeTower + 1) * u.perLevel) + "&7: &6" + Choc.num(Factory.scaled(ChocMath.upgradeCost(u, d.timeTower))) + " Chocolate");
        } else lore.add("&aMaxed!");
        lore.add("");
        lore.add(d.timeTower == 0 ? "&eClick to build!" : "&eLeft-click to activate! &bRight-click to upgrade!");
        ItemStack it = Icons.of(Material.CLOCK, "&dTime Tower " + Text.roman(d.timeTower), lore);
        return d.towerUntil > now ? Icons.glow(it) : it;
    }

    static ItemStack upgradeIcon(ChocData d, String which) {
        boolean shrine = "shrine".equals(which);
        ChocCatalog.Upgrade u = shrine ? Choc.catalog.factory.shrine : Choc.catalog.factory.coach;
        String name = shrine ? "Rabbit Shrine" : "Coach Longstride";
        int lvl = shrine ? d.shrine : d.coach;
        if (d.prestige < u.prestige) {
            return Icons.of(Material.GRAY_DYE, "&c" + name, shrine ? "&7Better odds of rare rabbits." : "&7A permanent production bonus.", "",
                    "&cRequires Chocolate Factory " + Text.roman(u.prestige) + "!");
        }
        List<String> lore = new ArrayList<>();
        if (shrine) {
            lore.add("&7Eggs are &a+" + Math.round(lvl * u.perLevel * 100) + "% &7more likely to hold");
            lore.add("&7a &9Rare &7or better rabbit.");
        } else {
            lore.add("&7Your factory produces &6+" + Factory.fmtMult(lvl * u.perLevel));
            lore.add("&7more Chocolate.");
        }
        lore.add("");
        if (lvl >= u.max) lore.add("&aMaxed!");
        else {
            lore.add("&7Next level: &a" + (shrine ? "+" + Math.round((lvl + 1) * u.perLevel * 100) + "%" : "+" + Factory.fmtMult((lvl + 1) * u.perLevel)));
            lore.add("&7Cost: &6" + Choc.num(Factory.scaled(ChocMath.upgradeCost(u, lvl))) + " Chocolate");
            lore.add("");
            lore.add("&eClick to upgrade!");
        }
        return Icons.of(shrine ? Material.RABBIT_FOOT : Material.GOLDEN_CARROT, "&d" + name + " " + Text.roman(lvl), lore);
    }

    static ItemStack productionIcon(Player p, ChocData d, ChocMath.Production prod) {
        List<String> lore = new ArrayList<>();
        lore.add("&6" + Choc.num(prod.cps()) + " &7Chocolate per second");
        lore.add("&6" + Choc.num(prod.cps() * 3600) + " &7Chocolate per hour");
        lore.add("");
        lore.add("&7Base: &6" + Choc.num(prod.baseCps()));
        lore.add(" &8- &7Employees: &6+" + Choc.num(prod.employeeCps()));
        lore.add(" &8- &7Rabbits: &6+" + Choc.num(prod.rabbitCps()));
        if (prod.talismanCps() > 0) lore.add(" &8- &7Chocolate talisman: &6+" + Choc.num(prod.talismanCps()));
        if (prod.shardCps() > 0) lore.add(" &8- &7Rabbit Crew: &6+" + Choc.num(prod.shardCps()));
        lore.add("");
        lore.add("&7Multiplier: &6" + Factory.fmtMult(prod.multiplier()));
        lore.add(" &8- &7Base: &61.000x");
        if (prod.prestigeBonus() > 0) lore.add(" &8- &7Factory tier: &6+" + Factory.fmtMult(prod.prestigeBonus()));
        lore.add(" &8- &7Rabbits: &6+" + Factory.fmtMult(prod.rabbitMult()));
        if (prod.coachBonus() > 0) lore.add(" &8- &7Coach Longstride: &6+" + Factory.fmtMult(prod.coachBonus()));
        if (prod.cookieBonus() > 0) lore.add(" &8- &7Boon Cookie: &6+" + Factory.fmtMult(prod.cookieBonus()));
        if (prod.timeTowerBonus() > 0) lore.add(" &8- &7Time Tower: &d+" + Factory.fmtMult(prod.timeTowerBonus()));
        if (prod.mixinBonus() > 0) lore.add(" &8- &7Hot Chocolate Mixin: &6+" + Factory.fmtMult(prod.mixinBonus()));
        if (prod.speedBonus() > 0) lore.add("&7Chocolate Speed: &a+" + String.format(java.util.Locale.ROOT, "%.2f%%", prod.speedBonus() * 100));
        if (prod.scale() != 1.0) lore.add("&8Server event scale: x" + prod.scale());
        lore.add("");
        lore.add("&7Clicks: &e" + Choc.num(d.clicks) + " &8| &7Strays caught: &e" + d.strays);
        return Icons.of(Material.PAPER, "&6Chocolate Production", lore);
    }

    static ItemStack huntIcon(ChocData d) {
        boolean on = Hunt.active();
        List<String> lore = new ArrayList<>();
        lore.add("&7Eggs hide on the islands every Spring.");
        lore.add("&7Each one holds a Chocolate Rabbit.");
        lore.add("");
        if (on) {
            long abs = Hunt.nowAbs();
            StringBuilder sb = new StringBuilder("&7Today: ");
            for (ChocCatalog.Meal m : Hunt.meals()) sb.append(Hunt.mealGlyph(d, m, abs)).append(' ');
            lore.add(sb.toString());
            lore.add("&7Ends in &a" + Text.duration(Sky.calendar().millisRemaining(Choc.HUNT) / 1000));
        } else {
            lore.add("&7Starts in &e" + Text.duration(Sky.calendar().millisUntil(Choc.HUNT) / 1000));
        }
        lore.add("&7Eggs found: &a" + d.eggsFound);
        lore.add("");
        lore.add("&eClick to view!");
        return Icons.of(Material.EGG, (on ? "&a" : "&7") + Choc.HUNT_NAME, lore);
    }

    static ItemStack bountyIcon(ChocData d) {
        return Icons.of(Material.IRON_SWORD, "&aRabbit Bounty Hunter",
                "&7Keeps the eggs you missed during the", "&7Hunt so you can open them later.", "",
                "&7Slots: &a" + d.hitmanSlots + "&7/&a" + Choc.catalog.factory.hitmanCosts.size(),
                "&7Eggs waiting: &6" + d.hitmanEggs, "", "&eClick to view!");
    }

    // ---- strays ---------------------------------------------------------------------------------------------

    static void strayTick(Player p, Session s) {
        long now = System.currentTimeMillis();
        if (s.stray != null && s.stray.until <= now) s.stray = null;
        if (s.stray != null || !Choc.ready(p)) return;
        if (s.secondsOpen % Math.max(1, Choc.strayInterval.get()) != 0) return;
        ChocData d = Choc.data(p);
        double chance = Choc.strayChance.get() * (1 + ChocMath.ability(Choc.catalog, d.rabbits.keySet(), "stray_rate"));
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (rng.nextDouble() >= chance) return;
        spawnStray(p, s, rng.nextDouble() < Choc.goldenChance.get() * Math.max(1, ChocMath.ability(Choc.catalog, d.rabbits.keySet(), "golden_stray")));
    }

    static void spawnStray(Player p, Session s, boolean golden) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        ChocData d = Choc.data(p);
        Stray st = new Stray();
        st.slot = STRAY_SLOTS[rng.nextInt(STRAY_SLOTS.length)];
        long life = (long) (Choc.strayLife.get() + ChocMath.ability(Choc.catalog, d.rabbits.keySet(), "stray_linger")) * 1000L;
        st.until = System.currentTimeMillis() + life;
        if (golden) st.golden = ChocMath.GOLDEN[ChocMath.pickWeighted(ChocMath.GOLDEN_WEIGHTS, rng.nextDouble())];
        else st.seconds = ChocMath.straySeconds(rng.nextDouble());
        s.stray = st;
        p.playSound(p.getLocation(), golden ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_RABBIT_AMBIENT, 1f, 1.4f);
    }

    static ItemStack strayIcon(Stray st) {
        if (st.golden == null) {
            return Icons.skull(employeeTexture(0), "&eCAUGHT ONE? A stray rabbit!",
                    List.of("&7It wandered in and is carrying", "&6" + st.seconds + " seconds &7of production.", "", "&eClick to catch it!"));
        }
        String what = switch (st.golden) {
            case "jackpot" -> "&6Jackpot! &7Worth &6" + (int) ChocMath.goldenSeconds("jackpot") + " seconds &7of production.";
            case "mountain" -> "&6Chocolate Mountain! &7Worth &6" + (int) ChocMath.goldenSeconds("mountain") + " seconds&7!";
            case "golden_click" -> "&6Golden Click! &7Clicks pay extra for a while.";
            default -> "&6Goldenpaw! &7Catch three to keep him.";
        };
        return Icons.glow(Icons.skull(employeeTexture(4), "&6&lGOLDEN RABBIT!", List.of(what, "", "&eClick to catch it!")));
    }

    static void catchStray(Player p, Menu m, Session s) {
        Stray st = s.stray;
        if (st == null || st.until <= System.currentTimeMillis() || !Choc.ready(p)) return;
        s.stray = null;
        ChocData d = Choc.data(p);
        double cps = Factory.cps(p, d);
        d.strays++;
        if (st.golden == null) {
            double pay = Math.max(1, cps * st.seconds);
            Factory.earn(p, d, pay);
            Msg.send(p, "&e&lCAUGHT! &7The stray rabbit dropped &6" + Choc.num(pay) + " Chocolate&7!");
        } else {
            d.goldenStrays++;
            switch (st.golden) {
                case "jackpot", "mountain" -> {
                    double pay = Math.max(100, cps * ChocMath.goldenSeconds(st.golden));
                    Factory.earn(p, d, pay);
                    Msg.send(p, "&6&lGOLDEN RABBIT! &7" + ("jackpot".equals(st.golden) ? "Jackpot" : "Chocolate Mountain") + "! &6+" + Choc.num(pay) + " Chocolate");
                }
                case "golden_click" -> {
                    d.goldenClickUntil = System.currentTimeMillis() + 10_000L;
                    Msg.send(p, "&6&lGOLDEN RABBIT! &7Golden Click! For &a10s &7every cookie click pays &6"
                            + Choc.goldenClickSeconds.get() + "s &7of production.");
                }
                default -> {
                    ChocCatalog.Rabbit dorado = Choc.catalog.rabbit("el_dorado");
                    if (dorado != null && !d.has(dorado.id)) {
                        d.doradoCaught++;
                        if (d.doradoCaught >= 3) Factory.grant(p, dorado, "stray", true, false);
                        else Msg.send(p, "&6&lGOLDEN RABBIT! &7Goldenpaw slipped away... &e" + d.doradoCaught + "/3");
                    } else {
                        double pay = Math.max(100, cps * ChocMath.goldenSeconds("jackpot"));
                        Factory.earn(p, d, pay);
                        Msg.send(p, "&6&lGOLDEN RABBIT! &7Goldenpaw left a gift: &6+" + Choc.num(pay) + " Chocolate");
                    }
                }
            }
        }
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        draw(p, m, s);
    }

    // ---- prestige confirm -----------------------------------------------------------------------------------

    static void prestigeConfirm(Player p) {
        ChocData d = Choc.data(p);
        if (!Factory.canPrestige(d)) {
            double req = ChocMath.prestigeRequirement(Choc.catalog, d.prestige + 1);
            if (req < 0) Msg.send(p, "&aYour factory is already at its highest tier!");
            else Msg.send(p, "&cYou need &6" + Choc.shortNum(req) + " Chocolate &cthis prestige (you have &6" + Choc.shortNum(d.thisPrestige) + "&c).");
            return;
        }
        Sky.menus().confirm(p, "Prestige your factory?", List.of(
                "&7Go to &6Chocolate Factory " + Text.roman(d.prestige + 1) + "&7.",
                "&cYour Chocolate, employees and Hand-Baked",
                "&cChocolate reset. Rabbits, the barn and",
                "&cupgrades stay."), () -> {
            Factory.prestige(p);
            openFactory(p);
        }, () -> openFactory(p));
    }

    // ---- collection -----------------------------------------------------------------------------------------

    static final String[] FILTERS = {"ALL", "COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE"};

    static void openCollection(Player p, String filter, int page) {
        if (!Choc.ready(p)) return;
        ChocData d = Choc.data(p);
        List<ChocCatalog.Rabbit> list = new ArrayList<>();
        for (ChocCatalog.Rabbit r : Choc.catalog.rabbits) if ("ALL".equals(filter) || r.rarity.equals(filter)) list.add(r);
        int found = 0;
        for (ChocCatalog.Rabbit r : list) if (d.has(r.id)) found++;
        int foundF = found;
        Sky.menus().paged("Hopsworth's Collection", list, r -> rabbitIcon(d, r), pc -> { }, menu -> {
            int fi = 0;
            for (int i = 0; i < FILTERS.length; i++) if (FILTERS[i].equals(filter)) fi = i;
            String next = FILTERS[(fi + 1) % FILTERS.length];
            menu.set(4, Icons.of(Material.BOOK, "&aHopsworth's Collection",
                    "&7Found: &a" + foundF + "&7/&a" + list.size() + (filter.equals("ALL") ? "" : " &7(" + Choc.rarityColor(filter) + filter + "&7)"),
                    "&7Duplicates found: &e" + d.duplicates));
            menu.set(50, Icons.of(Material.HOPPER, "&aFilter: " + ("ALL".equals(filter) ? "&fAll" : Choc.rarityColor(filter) + filter),
                    "&7Next: " + ("ALL".equals(next) ? "&fAll" : Choc.rarityColor(next) + next), "", "&eClick to switch!"),
                    c -> openCollection(c.player(), next, 0));
            menu.set(48, Icons.back("Chocolate Factory"), c -> openFactory(c.player()));
        }, page).open(p);
    }

    static ItemStack rabbitIcon(ChocData d, ChocCatalog.Rabbit r) {
        List<String> lore = new ArrayList<>();
        boolean has = d.has(r.id);
        String bonus = Factory.bonusText(r);
        if (has) {
            if (r.cps > 0) lore.add("&7+&6" + Choc.num(r.cps) + " Chocolate &7per second");
            if (r.mult > 0) lore.add("&7+&6" + String.format(java.util.Locale.ROOT, "%.3fx", r.mult) + " &7Chocolate multiplier");
            if (r.abilityText != null) lore.add("&7" + r.abilityText);
            lore.add("");
            String where = d.foundWhere.getOrDefault(r.id, "?");
            lore.add("&7Found: &a" + (Hunt.islandExists(where) ? Hunt.islandName(where) : Text.titleCase(where)));
            int dup = d.rabbits.getOrDefault(r.id, 1) - 1;
            if (dup > 0) lore.add("&7Duplicates: &e" + dup);
        } else {
            lore.add("&8You have not found this rabbit.");
            lore.add("");
            lore.add(hint(r));
        }
        lore.add("");
        lore.add(Choc.rarityColor(r.rarity) + "&l" + r.rarity + " RABBIT");
        if (!has) return Icons.of(Material.GRAY_DYE, "&7???", lore);
        return Icons.skull(r.texture, Choc.rabbitName(r), lore);
    }

    static String hint(ChocCatalog.Rabbit r) {
        return switch (r.source) {
            case "factory_milestone" -> "&7Joins at &6" + Choc.shortNum(r.milestone) + " &7all-time Chocolate.";
            case "shop_milestone" -> "&7Joins at &6" + Choc.shortNum(r.milestone) + " &7Chocolate spent in the shop.";
            case "stray" -> "&7Catch a &6golden &7stray rabbit... three times.";
            default -> {
                if (r.resident()) {
                    StringBuilder sb = new StringBuilder();
                    for (String i : r.islands) {
                        if (!Hunt.islandExists(i)) continue;
                        if (sb.length() > 0) sb.append("&7, ");
                        sb.append("&a").append(Hunt.islandName(i));
                    }
                    if (sb.length() > 0) yield "&7Lives in eggs on " + sb + "&7.";
                }
                yield "&7Hides in Chocolate Rabbit Eggs.";
            }
        };
    }

    // ---- milestones -----------------------------------------------------------------------------------------

    static void openMilestones(Player p, boolean shop) {
        if (!Choc.ready(p)) return;
        ChocData d = Choc.data(p);
        List<ChocCatalog.Milestone> list = shop ? Choc.catalog.factory.shopMilestones : Choc.catalog.factory.factoryMilestones;
        double have = shop ? d.spent : d.allTime;
        Sky.menus().paged(shop ? "Shop Milestones" : "Factory Milestones", list, ms -> {
            ChocCatalog.Rabbit r = Choc.catalog.rabbit(ms.rabbit);
            boolean done = have >= ms.amount;
            List<String> lore = new ArrayList<>();
            lore.add("&7" + (shop ? "Spend" : "Make") + " &6" + Choc.num(ms.amount) + " Chocolate" + (shop ? " &7in the shop" : " &7in total") + ".");
            lore.add("");
            lore.add("&7Reward: " + (r == null ? "&c?" : Choc.rabbitName(r) + " " + Factory.bonusText(r)));
            lore.add("");
            lore.add(done ? "&aReached!" : "&7Progress: &6" + Choc.shortNum(have) + "&7/&6" + Choc.shortNum(ms.amount) + " " + Text.bar(Math.min(1, have / ms.amount), 15));
            return Icons.of(done ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                    (done ? "&a" : "&c") + (shop ? "Shop" : "Factory") + " Milestone " + Text.roman(list.indexOf(ms) + 1), lore);
        }, pc -> { }, menu -> {
            menu.set(48, Icons.back("Chocolate Factory"), c -> openFactory(c.player()));
            menu.set(50, Icons.of(shop ? Material.DROPPER : Material.EMERALD, shop ? "&aFactory Milestones" : "&aShop Milestones", "", "&eClick to switch!"),
                    c -> openMilestones(c.player(), !shop));
        }, 0).open(p);
    }

    // ---- shop -----------------------------------------------------------------------------------------------

    static void openShop(Player p) {
        if (!Choc.ready(p)) return;
        ChocData d = Choc.data(p);
        List<ChocCatalog.ShopEntry> list = new ArrayList<>();
        for (ChocCatalog.ShopEntry e : Choc.catalog.shop) if (Sky.items().exists(e.item)) list.add(e);
        Sky.menus().paged("Chocolate Shop", list, e -> shopIcon(d, e), pc -> buy(pc.click().player(), pc.entry()), menu -> {
            menu.set(4, Icons.of(Material.COOKIE, "&6" + Choc.num(d.chocolate) + " Chocolate", "&7Spent here so far: &6" + Choc.num(d.spent)));
            menu.set(48, Icons.back("Chocolate Factory"), c -> openFactory(c.player()));
            menu.set(50, Icons.of(Material.LADDER, "&aShop Milestones", "", "&eClick to view!"), c -> openMilestones(c.player(), true));
        }, 0).open(p);
    }

    static double price(ChocCatalog.ShopEntry e) { return Factory.scaled(e.cost); }

    static ItemStack shopIcon(ChocData d, ChocCatalog.ShopEntry e) {
        ItemStack base = Sky.items().create(e.item);
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Cost: &6" + Choc.num(price(e)) + " Chocolate");
        if (e.prestige > 1) lore.add((d.prestige >= e.prestige ? "&a" : "&c") + "Requires Chocolate Factory " + Text.roman(e.prestige));
        if (e.requires != null) {
            ChocCatalog.Rabbit r = Choc.catalog.rabbit(e.requires);
            lore.add((d.has(e.requires) ? "&a" : "&c") + "Requires " + (r == null ? e.requires : r.name) + " in your collection");
        }
        if (e.talisman && Boolean.TRUE.equals(d.talismans.get(e.item))) lore.add("&aAlready bought!");
        else lore.add(d.chocolate >= price(e) ? "&eClick to buy!" : "&cNot enough Chocolate!");
        return Icons.withLore(base, lore);
    }

    static void buy(Player p, ChocCatalog.ShopEntry e) {
        if (!Choc.ready(p)) return;
        ChocData d = Choc.data(p);
        if (d.prestige < e.prestige) { Msg.send(p, "&cRequires Chocolate Factory " + Text.roman(e.prestige) + "!"); return; }
        if (e.requires != null && !d.has(e.requires)) { Msg.send(p, "&cYou need that rabbit in your collection first!"); return; }
        if (e.talisman && Boolean.TRUE.equals(d.talismans.get(e.item))) { Msg.send(p, "&cYou already bought this talisman!"); return; }
        if (!Sky.items().exists(e.item)) { Msg.send(p, "&cThat item is not available."); return; }
        double cost = price(e);
        if (!Factory.spend(p, d, cost, true)) { Msg.send(p, "&cYou don't have enough Chocolate!"); return; }
        if (e.talisman) d.talismans.put(e.item, true);
        Sky.loot().give(p, e.item, 1, LootSource.NPC_SHOP);
        Msg.send(p, "&aYou bought &f" + Sky.items().def(e.item).name + " &afor &6" + Choc.num(cost) + " Chocolate&a!");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        openShop(p);
    }

    // ---- bounty hunter --------------------------------------------------------------------------------------

    static void openBounty(Player p) {
        if (!Choc.ready(p)) return;
        ChocData d = Choc.data(p);
        Menu m = new Menu("Rabbit Bounty Hunter", 4).chrome();
        double cost = ChocMath.hitmanSlotCost(Choc.catalog.factory, d.hitmanSlots);
        m.set(11, Icons.of(Material.CHEST, "&aBounty Slots",
                "&7Each slot keeps one egg you missed", "&7during the Hunt.", "",
                "&7Slots: &a" + d.hitmanSlots + "&7/&a" + Choc.catalog.factory.hitmanCosts.size(), "",
                cost < 0 ? "&aAll slots bought!" : "&7Next slot: &6" + Choc.num(Factory.scaled(cost)) + " Chocolate",
                cost < 0 ? "" : "&eClick to buy!"), c -> { Factory.buyHitmanSlot(c.player()); openBounty(c.player()); });
        m.set(13, Icons.of(d.hitmanEggs > 0 ? Material.EGG : Material.GRAY_DYE, "&6Open one egg",
                "&7Eggs waiting: &6" + d.hitmanEggs, "", d.hitmanEggs > 0 ? "&eClick to open!" : "&cNo eggs waiting."),
                c -> { Hunt.claimBounty(c.player(), 1); openBounty(c.player()); });
        m.set(15, Icons.of(d.hitmanEggs > 0 ? Material.TURTLE_EGG : Material.GRAY_DYE, "&6Open every egg",
                "&7Eggs waiting: &6" + d.hitmanEggs, "", d.hitmanEggs > 0 ? "&eClick to open all!" : "&cNo eggs waiting."),
                c -> { Hunt.claimBounty(c.player(), Integer.MAX_VALUE); openBounty(c.player()); });
        m.set(m.closeSlot() - 1, Icons.back("Chocolate Factory"), c -> openFactory(c.player()));
        m.open(p);
    }

    /** Self-test support: every icon builder runs without a player (services answer as no-ops). */
    static String buildAll(ChocData d) {
        ChocMath.Production prod = Factory.production(null, d, System.currentTimeMillis());
        cookieIcon(d, prod);
        prestigeIcon(d);
        barnIcon(d);
        handIcon(d);
        towerIcon(d, System.currentTimeMillis());
        upgradeIcon(d, "shrine");
        upgradeIcon(d, "coach");
        bountyIcon(d);
        for (ChocCatalog.Rabbit r : Choc.catalog.rabbits) { rabbitIcon(d, r); hint(r); }
        int i = 0;
        for (ChocCatalog.Employee e : Choc.catalog.factory.employees) employeeIcon(null, d, e, i++);
        for (Stray st : List.of(stray(null), stray("jackpot"), stray("mountain"), stray("golden_click"), stray("dorado"))) strayIcon(st);
        return null;
    }

    private static Stray stray(String golden) {
        Stray s = new Stray();
        s.golden = golden;
        s.seconds = 10;
        return s;
    }

    static void forget(UUID id) { SESSIONS.remove(id); }
}
