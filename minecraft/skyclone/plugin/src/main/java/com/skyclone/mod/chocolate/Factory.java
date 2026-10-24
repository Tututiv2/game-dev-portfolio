package com.skyclone.mod.chocolate;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The factory's runtime: production, clicks, purchases, prestige, the Time Tower and rabbit grants. Every number
 * comes from {@link ChocMath} over the catalog; this class only reads services and moves chocolate.
 */
final class Factory {

    private Factory() {}

    // ---- production -----------------------------------------------------------------------------------------

    /** Highest chocolate talisman the player has active, or null. */
    static ChocCatalog.Talisman activeTalisman(Player p) {
        if (p == null) return null;
        ChocCatalog.Talisman best = null;
        Features.Accessories acc = Sky.service(Features.Accessories.class);
        for (ChocCatalog.Talisman t : Choc.catalog.factory.talismans) {
            boolean has;
            try { has = acc.has(p, t.item); } catch (Throwable e) { has = false; }
            if (has && (best == null || t.cps > best.cps)) best = t;
        }
        return best;
    }

    static double attribute(Player p, String key) {
        if (p == null) return 0;
        try { return Math.max(0, Sky.service(Features.Hunting.class).attributeLevel(p, key)); } catch (Throwable t) { return 0; }
    }

    static boolean cookie(Player p) {
        if (p == null) return false;
        try { return Sky.service(Features.Bank.class).cookieActive(p); } catch (Throwable t) { return false; }
    }

    /** Hot Chocolate Mixin (alchemy): +0.05x while active (NEU item lore). Matched by effect id. */
    static double mixin(Player p) {
        if (p == null) return 0;
        if (mixinActive(p)) return 0.05;
        try {
            Map<String, Integer> act = Sky.service(Features.Potions.class).active(p);
            if (act == null) return 0;
            for (String k : act.keySet()) if (k != null && k.toLowerCase(java.util.Locale.ROOT).contains("hot_chocolate")) return 0.05;
        } catch (Throwable ignored) { }
        return 0;
    }

    /** This module's own HOT_CHOCOLATE_MIXIN (wave-5 integration): active while {@code hotMixinUntil} is ahead. */
    static boolean mixinActive(Player p) {
        return Choc.ready(p) && Choc.data(p).hotMixinUntil > System.currentTimeMillis();
    }

    static ChocMath.Production production(Player p, ChocData d, long now) {
        ChocCatalog c = Choc.catalog;
        double[] rb = ChocMath.rabbitBonus(c, d.rabbits.keySet());
        ChocCatalog.Talisman t = activeTalisman(p);
        boolean tower = d.towerUntil > now && d.timeTower > 0;
        return new ChocMath.Production(
                ChocMath.employeeCps(c.factory, d.employees),
                rb[0],
                t == null ? 0 : t.cps,
                attribute(p, "rabbit_crew") * 5,                       // NEU shard lore: +5 CpS per level
                ChocMath.prestigeBonus(c, d.prestige),
                rb[1],
                d.coach * c.factory.coach.perLevel,
                cookie(p) ? Choc.cookieBonus.get() : 0,
                d.timeTower * c.factory.timeTower.perLevel * (tower ? 1 : 0),
                mixin(p),
                0,
                attribute(p, "chocolate_speed") * 0.0025,              // NEU shard lore: +0.25% per level
                Math.max(0, Choc.productionScale.get()));
    }

    static double cps(Player p, ChocData d) { return production(p, d, System.currentTimeMillis()).cps(); }

    /** Pay production up to now; called every second for online players and once on ProfileReady. */
    static void tick(Player p, ChocData d, long now, boolean login) {
        if (d.lastTick <= 0 || d.lastTick > now) { d.lastTick = now; return; }
        long from = d.lastTick;
        if (login) {
            if (!Choc.offline.get()) from = now;
            double cap = Choc.offlineCapHours.get();
            if (cap > 0) from = Math.max(from, now - (long) (cap * 3_600_000L));
        }
        double made = ChocMath.produce(production(p, d, now), from, now, d.towerUntil);
        d.lastTick = now;
        if (made > 0) earn(p, d, made);
        if (login && made >= 1 && now - from > 60_000) {
            Msg.send(p, "&6&lCHOCOLATE FACTORY &7Your rabbits made &6" + Choc.num(made) + " Chocolate &7while you were away ("
                    + Text.duration((now - from) / 1000) + ").");
        }
        regenTower(d, now);
    }

    /** Credit chocolate made (clicks, production, strays, duplicates): counts toward prestige and milestones. */
    static void earn(Player p, ChocData d, double amount) {
        if (!(amount > 0) || Double.isInfinite(amount)) return;
        d.chocolate += amount;
        d.thisPrestige += amount;
        d.allTime += amount;
        if (p != null) checkMilestones(p, d);
    }

    /** Take chocolate; {@code shop} purchases also count toward shop milestones. False (nothing taken) when short. */
    static boolean spend(Player p, ChocData d, double amount, boolean shop) {
        if (!(amount >= 0) || d.chocolate + 1e-6 < amount) return false;
        d.chocolate = Math.max(0, d.chocolate - amount);
        if (shop) {
            d.spent += amount;
            if (p != null) checkMilestones(p, d);
        }
        return true;
    }

    static double scaled(double cost) { return Math.ceil(cost * Math.max(0, Choc.costScale.get())); }

    // ---- clicking -------------------------------------------------------------------------------------------

    private static final Map<UUID, long[]> CLICKS = new HashMap<>();

    static void click(Player p) {
        if (!Choc.ready(p)) return;
        long sec = System.currentTimeMillis() / 1000;
        long[] w = CLICKS.computeIfAbsent(p.getUniqueId(), k -> new long[2]);
        if (w[0] != sec) { w[0] = sec; w[1] = 0; }
        if (++w[1] > Math.max(1, Choc.clickCap.get())) return;
        ChocData d = Choc.data(p);
        double per = d.handBaked * (1 + ChocMath.ability(Choc.catalog, d.rabbits.keySet(), "click_bonus"));
        long now = System.currentTimeMillis();
        if (d.goldenClickUntil > now) per += cps(p, d) * Choc.goldenClickSeconds.get();
        d.clicks++;
        earn(p, d, per);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    static void forget(UUID id) { CLICKS.remove(id); }

    // ---- upgrades -------------------------------------------------------------------------------------------

    static int youth(Player p) { return (int) attribute(p, "rabbit_youth"); }

    /** Buy up to {@code n} levels of an employee; returns levels bought. */
    static int buyEmployee(Player p, String id, int n) {
        ChocData d = Choc.data(p);
        ChocCatalog.Employee e = Choc.catalog.employee(id);
        if (e == null) return 0;
        if (d.prestige < e.prestige) {
            Msg.send(p, "&cRequires Chocolate Factory " + Text.roman(e.prestige) + "!");
            return 0;
        }
        int cap = ChocMath.employeeCap(Choc.catalog.factory, e, d.prestige, youth(p));
        int bought = 0;
        while (bought < n && d.level(id) < cap) {
            double cost = scaled(ChocMath.employeeCost(e, Choc.catalog.factory.employeeGrowth, d.level(id)));
            if (!spend(p, d, cost, false)) break;
            d.employees.put(id, d.level(id) + 1);
            bought++;
        }
        if (bought == 0) {
            if (d.level(id) >= cap) Msg.send(p, "&c" + e.name + " is at the level cap for your Factory tier!");
            else Msg.send(p, "&cYou don't have enough Chocolate!");
            return 0;
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        Msg.send(p, "&a" + (d.level(id) == bought ? "Hired " : "Promoted ") + e.name + " &ato &e[" + d.level(id) + "] &a"
                + rankTitle(d.level(id)) + "&a!");
        return bought;
    }

    /** Employee rank titles by level (flavour; the genre shows Intern ... Executive). */
    static String rankTitle(int level) {
        if (level <= 0) return "&7Unemployed";
        if (level < 10) return "&fIntern";
        if (level < 75) return "&aEmployee";
        if (level < 120) return "&9Assistant";
        if (level < 160) return "&5Manager";
        if (level < 200) return "&6Director";
        return "&dExecutive";
    }

    static boolean buyHandBaked(Player p) {
        ChocData d = Choc.data(p);
        ChocCatalog.Upgrade u = Choc.catalog.factory.handBaked;
        if (d.handBaked >= u.max) { Msg.send(p, "&cHand-Baked Chocolate is maxed!"); return false; }
        double cost = scaled(ChocMath.handBakedCost(u, d.handBaked));
        if (!spend(p, d, cost, false)) { Msg.send(p, "&cYou don't have enough Chocolate!"); return false; }
        d.handBaked++;
        Msg.send(p, "&aHand-Baked Chocolate upgraded to &e" + Text.roman(d.handBaked) + "&a! Clicks now make &6" + d.handBaked + " Chocolate&a.");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        return true;
    }

    static int barnMax() {
        ChocCatalog.Upgrade b = Choc.catalog.factory.barn;
        int need = Choc.catalog.rabbits.size() - b.start;
        return 1 + Math.max(0, (int) Math.ceil(need / Math.max(1.0, b.perLevel)));
    }

    static boolean buyBarn(Player p) {
        ChocData d = Choc.data(p);
        ChocCatalog.Upgrade b = Choc.catalog.factory.barn;
        if (d.barn >= barnMax()) { Msg.send(p, "&cYour Rabbit Barn already holds every rabbit there is!"); return false; }
        double cost = scaled(ChocMath.barnCost(b, d.barn));
        if (!spend(p, d, cost, false)) { Msg.send(p, "&cYou don't have enough Chocolate!"); return false; }
        d.barn++;
        Msg.send(p, "&aRabbit Barn upgraded! It now holds &e" + ChocMath.barnCapacity(b, d.barn) + " &arabbits.");
        p.playSound(p.getLocation(), Sound.BLOCK_WOOD_PLACE, 1f, 1f);
        return true;
    }

    /** Time Tower / Shrine / Coach purchase. */
    static boolean buyUpgrade(Player p, String which) {
        ChocData d = Choc.data(p);
        ChocCatalog.Upgrade u = switch (which) {
            case "tower" -> Choc.catalog.factory.timeTower;
            case "shrine" -> Choc.catalog.factory.shrine;
            default -> Choc.catalog.factory.coach;
        };
        String name = switch (which) {
            case "tower" -> "Time Tower";
            case "shrine" -> "Rabbit Shrine";
            default -> "Coach Longstride";
        };
        if (d.prestige < u.prestige) { Msg.send(p, "&cRequires Chocolate Factory " + Text.roman(u.prestige) + "!"); return false; }
        int lvl = switch (which) { case "tower" -> d.timeTower; case "shrine" -> d.shrine; default -> d.coach; };
        if (lvl >= u.max) { Msg.send(p, "&c" + name + " is maxed!"); return false; }
        double cost = scaled(ChocMath.upgradeCost(u, lvl));
        if (!spend(p, d, cost, false)) { Msg.send(p, "&cYou don't have enough Chocolate!"); return false; }
        switch (which) {
            case "tower" -> {
                d.timeTower++;
                if (d.towerCharges < 0) d.towerCharges = towerMaxCharges(d);
            }
            case "shrine" -> d.shrine++;
            default -> d.coach++;
        }
        Msg.send(p, "&a" + name + " upgraded to &e" + Text.roman(lvl + 1) + "&a!");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
        return true;
    }

    static int towerMaxCharges(ChocData d) {
        return Choc.catalog.factory.timeTower.charges + (int) ChocMath.ability(Choc.catalog, d.rabbits.keySet(), "tt_charge");
    }

    static long towerMillis(ChocData d) {
        double minutes = Choc.catalog.factory.timeTower.minutes + ChocMath.ability(Choc.catalog, d.rabbits.keySet(), "tt_duration")
                + Choc.towerMinutesBonus.get();
        return (long) (minutes * 60_000L);
    }

    static void regenTower(ChocData d, long now) {
        if (d.timeTower <= 0) return;
        int max = towerMaxCharges(d);
        if (d.towerCharges < 0) d.towerCharges = max;
        long period = (long) (Choc.catalog.factory.timeTower.chargeHours * 3_600_000L);
        if (d.towerCharges >= max) { d.towerChargeAt = 0; return; }
        if (d.towerChargeAt <= 0) d.towerChargeAt = now + period;
        while (d.towerCharges < max && d.towerChargeAt <= now) {
            d.towerCharges++;
            d.towerChargeAt = d.towerCharges >= max ? 0 : d.towerChargeAt + period;
        }
    }

    static boolean activateTower(Player p) {
        ChocData d = Choc.data(p);
        long now = System.currentTimeMillis();
        if (d.timeTower <= 0) { Msg.send(p, "&cBuild the Time Tower first!"); return false; }
        regenTower(d, now);
        if (d.towerUntil > now) { Msg.send(p, "&cYour Time Tower is already running!"); return false; }
        if (d.towerCharges <= 0) { Msg.send(p, "&cThe Time Tower has no charges left!"); return false; }
        // pay what was made so far at the old rate before the multiplier changes
        tick(p, d, now, false);
        d.towerCharges--;
        d.towerUntil = now + towerMillis(d);
        regenTower(d, now);
        Msg.send(p, "&d&lTIME TOWER! &7Your factory runs &d+" + fmtMult(d.timeTower * Choc.catalog.factory.timeTower.perLevel)
                + " &7faster for &a" + Text.duration(towerMillis(d) / 1000) + "&7!");
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
        return true;
    }

    static boolean buyHitmanSlot(Player p) {
        ChocData d = Choc.data(p);
        double cost = ChocMath.hitmanSlotCost(Choc.catalog.factory, d.hitmanSlots);
        if (cost < 0) { Msg.send(p, "&cThe Bounty Hunter has no more slots to sell!"); return false; }
        cost = scaled(cost);
        if (!spend(p, d, cost, false)) { Msg.send(p, "&cYou don't have enough Chocolate!"); return false; }
        d.hitmanSlots++;
        Msg.send(p, "&aThe Rabbit Bounty Hunter can now keep &e" + d.hitmanSlots + " &amissed eggs.");
        return true;
    }

    // ---- prestige -------------------------------------------------------------------------------------------

    static boolean canPrestige(ChocData d) {
        double req = ChocMath.prestigeRequirement(Choc.catalog, d.prestige + 1);
        return req >= 0 && d.thisPrestige >= req;
    }

    static boolean prestige(Player p) {
        ChocData d = Choc.data(p);
        if (!canPrestige(d)) { Msg.send(p, "&cYou haven't made enough Chocolate this prestige!"); return false; }
        d.prestige++;
        d.chocolate = 0;
        d.thisPrestige = 0;
        d.employees.clear();
        d.handBaked = 1;
        Msg.box(p, "&6&lCHOCOLATE FACTORY " + Text.roman(d.prestige), java.util.List.of(
                "&7Your factory grew to &6Chocolate Factory " + Text.roman(d.prestige) + "&7!",
                "&7Production multiplier: &6+" + fmtMult(ChocMath.prestigeBonus(Choc.catalog, d.prestige)),
                "&7Employees can now reach level &a" + (Choc.catalog.factory.levelCapBase + Choc.catalog.factory.levelCapPerPrestige * d.prestige) + "&7.",
                "&8Chocolate, employees and Hand-Baked Chocolate were reset."));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        awardPrestigeXp(p, d);
        return true;
    }

    static void awardPrestigeXp(Player p, ChocData d) {
        int xp = Choc.catalog.factory.skyXp.getOrDefault("prestige", 25);
        for (int lvl = 2; lvl <= d.prestige; lvl++) {
            int bit = 1 << lvl;
            if ((d.prestigeXpPaid & bit) != 0) continue;
            d.prestigeXpPaid |= bit;
            try { Sky.service(Features.SkyLevel.class).award(p, "chocolate:prestige:" + lvl, xp, "Chocolate Factory " + Text.roman(lvl)); }
            catch (Throwable t) { Sky.log().warning("[chocolate] sb xp award failed: " + t); }
        }
    }

    static String fmtMult(double m) { return String.format(java.util.Locale.ROOT, "%.3fx", m); }

    // ---- rabbits --------------------------------------------------------------------------------------------

    enum Grant { NEW, DUPLICATE, BARN_FULL, UNKNOWN }

    static int barnCapacity(ChocData d) { return ChocMath.barnCapacity(Choc.catalog.factory.barn, d.barn); }

    /**
     * Give a rabbit. A new one needs barn room unless {@code bypassBarn} (milestones, admin); a duplicate pays
     * chocolate (or, sometimes, its tradeable boxed form). Prints the genre's NEW / DUPLICATE lines.
     */
    static Grant grant(Player p, ChocCatalog.Rabbit r, String where, boolean bypassBarn, boolean quiet) {
        if (r == null) return Grant.UNKNOWN;
        ChocData d = Choc.data(p);
        if (!d.has(r.id) && !bypassBarn && d.unique() >= barnCapacity(d)) {
            payDuplicate(p, d, r, true);
            return Grant.BARN_FULL;
        }
        if (!d.has(r.id)) {
            // pay production so far at the old rate first, then the new rabbit counts from now
            tick(p, d, System.currentTimeMillis(), false);
            d.rabbits.put(r.id, 1);
            d.foundWhere.put(r.id, where == null ? "unknown" : where);
            if (!quiet) {
                Msg.send(p, "&d&lNEW RABBIT! " + Choc.rabbitName(r) + " &7(" + Choc.rarityColor(r.rarity) + r.rarity + "&7) " + bonusText(r));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.8f);
            }
            String rar = r.rarity;
            int xp = "MYTHIC".equals(rar) ? Choc.catalog.factory.skyXp.getOrDefault("mythic", 10)
                    : "DIVINE".equals(rar) ? Choc.catalog.factory.skyXp.getOrDefault("divine", 10) : 0;
            if (xp > 0) {
                try { Sky.service(Features.SkyLevel.class).award(p, "chocolate:rabbit:" + r.id, xp, r.name); }
                catch (Throwable t) { Sky.log().warning("[chocolate] sb xp award failed: " + t); }
            }
            if (d.towerCharges >= 0 && "tt_charge".equals(r.ability)) regenTower(d, System.currentTimeMillis());
            return Grant.NEW;
        }
        payDuplicate(p, d, r, false);
        return Grant.DUPLICATE;
    }

    static String bonusText(ChocCatalog.Rabbit r) {
        StringBuilder sb = new StringBuilder();
        if (r.cps > 0) sb.append("&6+").append(Choc.num(r.cps)).append(" Chocolate");
        if (r.mult > 0) sb.append(sb.length() > 0 ? " &7and " : "").append("&6+").append(String.format(java.util.Locale.ROOT, "%.3fx", r.mult)).append(" Chocolate");
        if (sb.length() > 0) sb.append(" &7per second!");
        if (r.abilityText != null && r.ability != null && !r.ability.startsWith("flat_")) sb.append(" &7").append(r.abilityText);
        return sb.toString();
    }

    static void payDuplicate(Player p, ChocData d, ChocCatalog.Rabbit r, boolean barnFull) {
        if (!barnFull) {
            d.rabbits.merge(r.id, 1, Integer::sum);
            d.duplicates++;
        }
        String faction = "FACTION_RABBIT_" + r.id.toUpperCase(java.util.Locale.ROOT);
        if (!barnFull && Choc.catalog.factory.factionRabbits.contains(r.id) && Sky.items().exists(faction)
                && ThreadLocalRandom.current().nextDouble() < Choc.factionChance.get()) {
            Sky.loot().give(p, faction, 1, com.skyclone.api.loot.LootSource.REWARD);
            Msg.send(p, "&7&lDUPLICATE RABBIT! " + Choc.rabbitName(r) + " &7hopped into a box - it is now an item you can trade.");
            return;
        }
        double bonus = ChocMath.ability(Choc.catalog, d.rabbits.keySet(), "dupe_bonus");
        try { if (Sky.service(Features.Mayors.class).perkActive("foxy.sweet_benevolence")) bonus += 0.30; } catch (Throwable ignored) { }
        double pay = ChocMath.duplicateReward(Choc.catalog.factory, r.rarity, cps(p, d), bonus);
        earn(p, d, pay);
        if (barnFull) Msg.send(p, "&c&lBARN FULL! &7" + Choc.rabbitName(r) + " &7had nowhere to sleep. Upgrade your &aRabbit Barn&7! &6+" + Choc.num(pay) + " Chocolate");
        else Msg.send(p, "&7&lDUPLICATE RABBIT! " + Choc.rabbitName(r) + " &6+" + Choc.num(pay) + " Chocolate");
    }

    /** Factory (all-time) and shop (spent) milestones award their rabbits as soon as they are reached. */
    static void checkMilestones(Player p, ChocData d) {
        for (ChocCatalog.Milestone m : Choc.catalog.factory.factoryMilestones) {
            if (d.allTime >= m.amount && !d.has(m.rabbit)) milestone(p, m, "&6Factory");
        }
        for (ChocCatalog.Milestone m : Choc.catalog.factory.shopMilestones) {
            if (d.spent >= m.amount && !d.has(m.rabbit)) milestone(p, m, "&dShop");
        }
    }

    private static void milestone(Player p, ChocCatalog.Milestone m, String kind) {
        ChocCatalog.Rabbit r = Choc.catalog.rabbit(m.rabbit);
        if (r == null) return;
        Msg.send(p, "&6&lMILESTONE! " + kind + " Milestone &7reached at &6" + Choc.shortNum(m.amount) + " Chocolate&7!");
        grant(p, r, "milestone", true, false);
    }
}
