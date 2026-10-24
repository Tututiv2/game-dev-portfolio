package com.skyclone.mod.chocolate;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.time.CalendarEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Live self-tests ({@code /sb selftest chocolate}). Each one is fast and checks data or a documented number. */
final class ChocTests {

    private ChocTests() {}

    static void register() {
        var c = Sky.commands();

        c.selfTest("chocolate.catalog", () -> {
            ChocCatalog cat = Choc.catalog;
            int[] want = {244, 142, 94, 53, 28, 11, 5};   // NEU hoppity.json rarity lists
            for (int i = 0; i < want.length; i++) {
                int n = cat.ofRarity(ChocMath.RARITIES[i]).size();
                if (n != want[i]) return ChocMath.RARITIES[i] + " has " + n + " rabbits, NEU has " + want[i];
            }
            Set<String> names = new HashSet<>();
            for (ChocCatalog.Rabbit r : cat.rabbits) {
                if (!names.add(r.name.toLowerCase())) return "duplicate rabbit name " + r.name;
                if (r.texture == null) return "no head texture for " + r.id;
            }
            ChocCatalog.Rabbit common = cat.rabbit("aaron");
            if (common == null || common.cps != 1 || common.mult != 0.002) return "common rabbit bonus is not +1 CpS / +0.002x";
            if (cat.factory.employees.size() != 7) return "expected 7 employees";
            int[] cps = {1, 2, 3, 4, 5, 6, 7};
            for (int i = 0; i < 7; i++) if (cat.factory.employees.get(i).cps != cps[i]) return "employee " + i + " cps " + cat.factory.employees.get(i).cps;
            return null;
        });

        // wave-5 integration: the Enchanted Clock's Time Warp and the Hot Chocolate Mixin are abilities on real items
        c.selfTest("chocolate.time-warp-and-mixin", () -> {
            for (String[] want : new String[][]{{"ENCHANTED_TIME_CLOCK", "time_warp"}, {"HOT_CHOCOLATE_MIXIN", "chocolate_hot_mixin"}}) {
                ItemDef def = Sky.items().def(want[0]);
                if (def == null) return want[0] + " is not registered";
                boolean has = false;
                if (def.abilities != null) for (var a : def.abilities) if (a != null && want[1].equals(a.id)) has = true;
                if (!has) return want[0] + " does not carry ability " + want[1] + " (first registration wins: another module owns the def?)";
            }
            if (Choc.timeWarpHours.get() <= 0) return "chocolate.time-warp-cooldown-hours is " + Choc.timeWarpHours.get() + ": Time Warp could be spammed";
            return null;
        });

        c.selfTest("chocolate.items-exist", () -> {
            List<String> miss = new ArrayList<>();
            for (String id : List.of("EGGLOCATOR", "STEAMED_CHOCOLATE_FISH", "CHOCOLATE_FACTORY_BARN_SKIN", "DYE_CHOCOLATE")) {
                if (!Sky.items().exists(id)) miss.add(id);
            }
            for (String r : Choc.catalog.factory.factionRabbits) {
                String id = "FACTION_RABBIT_" + r.toUpperCase();
                ItemDef def = Sky.items().def(id);
                if (def == null) miss.add(id);
                else if (def.extra == null || !def.extra.has("rabbit") || Choc.catalog.rabbit(def.extra.get("rabbit").getAsString()) == null) miss.add(id + "(rabbit)");
            }
            for (ChocCatalog.ShopEntry e : Choc.catalog.shop) if (!e.optional && !Sky.items().exists(e.item)) miss.add("shop:" + e.item);
            for (ChocCatalog.Talisman t : Choc.catalog.factory.talismans) if (!Sky.items().exists(t.item)) miss.add("talisman:" + t.item);
            return miss.isEmpty() ? null : "missing items: " + String.join(", ", miss);
        });

        c.selfTest("chocolate.formulas", () -> {
            ChocCatalog cat = Choc.catalog;
            double[] bonus = {0, 0.1, 0.25, 0.5, 1.0, 1.5};                    // research/08 6.6: 1.1x .. 2.5x
            double[] req = {0, 150e6, 1e9, 4e9, 10e9, 30e9};
            for (int i = 0; i < 6; i++) {
                if (Math.abs(ChocMath.prestigeBonus(cat, i + 1) - bonus[i]) > 1e-9) return "CF" + (i + 1) + " bonus " + ChocMath.prestigeBonus(cat, i + 1);
                if (i > 0 && ChocMath.prestigeRequirement(cat, i + 1) != req[i]) return "CF" + (i + 1) + " requirement " + ChocMath.prestigeRequirement(cat, i + 1);
            }
            if (ChocMath.handBakedCost(cat.factory.handBaked, 1) != 500) return "Hand-Baked II should cost 500";
            if (ChocMath.handBakedCost(cat.factory.handBaked, 9) != 4500) return "Hand-Baked X should cost 4,500";
            if (ChocMath.barnCost(cat.factory.barn, 1) != 5000) return "Barn II should cost 5,000";
            if (ChocMath.barnCapacity(cat.factory.barn, 1) != 20) return "Barn I holds 20";
            if (Math.abs(cat.factory.timeTower.max * cat.factory.timeTower.perLevel - 1.5) > 1e-9) return "Time Tower XV should be +1.5x";
            if (Math.abs(cat.factory.coach.max * cat.factory.coach.perLevel - 0.2) > 1e-9) return "Coach XX should be +0.2x";
            if (Math.abs(cat.factory.shrine.max * cat.factory.shrine.perLevel - 0.2) > 1e-9) return "Shrine XX should be +20%";
            ChocData d = new ChocData();
            d.employees.put("bro", 10);
            d.employees.put("sis", 2);
            d.rabbits.put("aaron", 1);
            ChocMath.Production p = new ChocMath.Production(ChocMath.employeeCps(cat.factory, d.employees), 1, 0, 0,
                    0.1, 0.002, 0, 0, 0, 0, 0, 0, 1);
            if (Math.abs(p.cps() - 17 * 1.102) > 1e-9) return "CpS for 10 Bro + 2 Sis + one common at CF2 should be 18.734, got " + p.cps();
            return null;
        });

        c.selfTest("chocolate.egg-clock", () -> {
            long abs = ChocMath.absMinute(1, 1, 1, 6, 59);             // Early Spring 1st 6:59am
            if (ChocMath.mealInstance(abs, 7) != -1) return "breakfast before 7am should still be yesterday's";
            if (ChocMath.mealInstance(abs + 1, 7) != 0) return "breakfast at 7am should be today's";
            if (!ChocMath.springAt(abs)) return "Early Spring is the Hunt";
            if (ChocMath.springAt(ChocMath.absMinute(1, 4, 1, 0, 0))) return "Early Summer is not the Hunt";
            if (ChocMath.minutesUntilNext(abs, 7) != 1) return "next breakfast in 1 minute";
            for (CalendarEvent e : Sky.calendar().events()) {
                if (Choc.HUNT.equals(e.id)) return e.month == 1 && e.day == 1 && e.durationDays == 93 ? null : "hunt event is not Early Spring 1 for 93 days";
            }
            return "calendar event " + Choc.HUNT + " is not registered";
        });

        c.selfTest("chocolate.egg-roll", () -> {
            java.util.Random rng = new java.util.Random(7);
            double[] w = ChocMath.eggWeights(Choc.catalog.factory.eggOdds, 0, 0, 0, 0);
            Set<String> owned = new HashSet<>();
            for (int i = 0; i < 2000; i++) {
                String island = i % 3 == 0 ? "hub" : null;
                ChocCatalog.Rabbit r = ChocMath.rollEgg(Choc.catalog, island, Hunt::islandExists, owned, w, 0.5, 0, rng::nextDouble);
                if (r == null) return "an egg held nothing";
                if (!r.fromEggs()) return "an egg held milestone-only rabbit " + r.id;
                if (r.resident() && island == null) {
                    for (String isl : r.islands) if (Hunt.islandExists(isl)) return "resident " + r.id + " came out of an egg off its island";
                }
                owned.add(r.id);
            }
            return owned.size() < 100 ? "only " + owned.size() + " distinct rabbits from 2000 eggs" : null;
        });

        c.selfTest("chocolate.milestones", () -> {
            for (ChocCatalog.Milestone m : Choc.catalog.factory.factoryMilestones) {
                ChocCatalog.Rabbit r = Choc.catalog.rabbit(m.rabbit);
                if (r == null || !"factory_milestone".equals(r.source)) return "factory milestone rabbit " + m.rabbit;
            }
            for (ChocCatalog.Milestone m : Choc.catalog.factory.shopMilestones) {
                ChocCatalog.Rabbit r = Choc.catalog.rabbit(m.rabbit);
                if (r == null || !"shop_milestone".equals(r.source)) return "shop milestone rabbit " + m.rabbit;
            }
            if (Choc.catalog.factory.factoryMilestones.size() != 26) return "26 factory milestones expected";
            if (Choc.catalog.factory.hitmanCosts.size() != 28) return "28 Bounty Hunter slots expected";
            return null;
        });

        c.selfTest("chocolate.menus-build", () -> {
            ChocData d = new ChocData();
            d.rabbits.put("aaron", 2);
            d.foundWhere.put("aaron", "hub");
            d.prestige = 4;
            d.timeTower = 3;
            return FactoryMenus.buildAll(d);
        });

        c.selfTest("chocolate.hunt-islands", () -> {
            List<String> isl = Hunt.islands();
            if (isl.isEmpty()) return "no hunt island is registered";
            List<String> none = new ArrayList<>();
            for (String i : isl) {
                boolean has;
                try { has = Sky.worlds().layout(i) != null; } catch (Throwable t) { has = false; }
                if (has && Hunt.spots(i).isEmpty()) none.add(i);
            }
            return none.isEmpty() ? null : "no hiding places on " + none;
        });
    }
}
