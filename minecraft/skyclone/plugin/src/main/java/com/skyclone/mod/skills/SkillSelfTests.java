package com.skyclone.mod.skills;

import com.skyclone.api.Sky;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Menu;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** {@code /sb selftest skills}: the data hangs together and gives the research numbers. */
final class SkillSelfTests {

    private SkillSelfTests() {}

    static void register(SkillServiceImpl svc, SkillTable t, SkillMenus menus) {
        var c = Sky.commands();

        c.selfTest("skills.table-valid", t::validate);

        c.selfTest("skills.research-numbers", () -> researchNumbers(t));

        c.selfTest("skills.service-provided", () ->
                Sky.service(SkillService.class) == svc ? null : "Sky.service(SkillService) is not this module's service");

        c.selfTest("skills.requirement-resolver", () -> {
            String d = Sky.requirements().describe("skill:combat:15");
            if (d == null || !d.contains("Combat")) return "describe(skill:combat:15) = " + d;
            String avg = Sky.requirements().describe("skill:average:20");
            return avg != null && avg.contains("Average") ? null : "describe(skill:average:20) = " + avg;
        });

        c.selfTest("skills.menus-build", () -> {
            SkillMenus.View v = SkillMenus.View.empty(svc);
            Menu m = menus.yourSkills(v);
            for (int slot : SkillMenus.SLOTS.values()) if (m.get(slot) == null) return "Your Skills slot " + slot + " empty";
            for (SkillType s : SkillType.values()) {
                // a half-way profile too, so the yellow "current" pane and the cap pane are exercised
                v.xp().put(s, t.total(s, 30) + 1);
                int pages = (t.maxLevel(s) + SkillMenus.PER_PAGE - 1) / SkillMenus.PER_PAGE;
                for (int pg = 0; pg < pages; pg++) {
                    Menu r = menus.road(v, s, pg);
                    if (r.get(SkillMenus.SNAKE[0]) == null) return s + " road page " + pg + " has no first level";
                }
            }
            menus.yourSkills(v);
            return null;
        });

        c.selfTest("skills.unlock-items-exist", () -> {
            Set<String> missing = new LinkedHashSet<>();
            for (SkillType s : SkillType.values())
                for (int L = 1; L <= t.maxLevel(s); L++)
                    for (SkillTable.Unlock u : t.level(s, L).unlocks)
                        if (("reforge_stone".equals(u.type) || "power_stone".equals(u.type) || "recipe".equals(u.type)
                                || "furniture".equals(u.type))
                                && (u.id == null || !Sky.items().exists(u.id)))
                            missing.add(String.valueOf(u.id));
            return missing.isEmpty() ? null : missing.size() + " unlock item ids not registered (owners: reforges/accessories/enchanting/crafting furniture): " + first(missing);
        });

        c.selfTest("skills.unlock-enchants-exist", () -> {
            Set<String> missing = new LinkedHashSet<>();
            for (int L = 1; L <= t.maxLevel(SkillType.ENCHANTING); L++)
                for (SkillTable.Unlock u : t.level(SkillType.ENCHANTING, L).unlocks)
                    if ("enchant".equals(u.type) && SkillText.enchant(u.id) == null) missing.add(u.id);
            if (missing.isEmpty() || SkillText.hideMissingEnchants.getAsBoolean()) return null;   // hidden from players: not a visible bug
            return missing.size() + " enchant ids not registered (owner: enchanting): " + first(missing);
        });

        c.selfTest("skills.access-islands-exist", () -> {
            Set<String> missing = new LinkedHashSet<>();
            for (SkillType s : SkillType.values())
                for (int L = 1; L <= t.maxLevel(s); L++)
                    for (SkillTable.Unlock u : t.level(s, L).unlocks)
                        if ("access".equals(u.type) && Sky.worlds().island(u.id) == null) missing.add(u.id);
            return missing.isEmpty() ? null : "islands named by skill unlocks but not registered: " + first(missing);
        });
    }

    private static String first(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        return String.join(", ", l.subList(0, Math.min(12, l.size()))) + (l.size() > 12 ? ", ..." : "");
    }

    /** Numbers straight from research 02 §2.1-2.4. Shared with the unit test. */
    static String researchNumbers(SkillTable t) {
        String r;
        if ((r = eq("FARMING XP to 60", t.total(SkillType.FARMING, 60), 111_672_425)) != null) return r;
        if ((r = eq("FARMING XP to 50", t.total(SkillType.FARMING, 50), 55_172_425)) != null) return r;
        if ((r = eq("COMBAT XP for level 25", t.step(SkillType.COMBAT, 25), 700_000)) != null) return r;
        if ((r = eq("RUNECRAFTING XP to 25", t.total(SkillType.RUNECRAFTING, 25), 94_450)) != null) return r;
        if ((r = eq("SOCIAL XP to 25", t.total(SkillType.SOCIAL, 25), 272_800)) != null) return r;
        if ((r = eq("FARMING Health at 50", stat(t, SkillType.FARMING, 50, Stat.HEALTH), 192)) != null) return r;
        if ((r = eq("FARMING Health at 60", stat(t, SkillType.FARMING, 60, Stat.HEALTH), 242)) != null) return r;
        if ((r = eq("MINING Defense at 60", stat(t, SkillType.MINING, 60, Stat.DEFENSE), 106)) != null) return r;
        if ((r = eq("COMBAT Crit Chance at 60", stat(t, SkillType.COMBAT, 60, Stat.CRIT_CHANCE), 30)) != null) return r;
        if ((r = eq("COMBAT perk at 50", t.perk(SkillType.COMBAT, 50), 200)) != null) return r;
        if ((r = eq("COMBAT perk at 60", t.perk(SkillType.COMBAT, 60), 210)) != null) return r;
        if ((r = eq("FORAGING Strength at 57", stat(t, SkillType.FORAGING, 57, Stat.STRENGTH), 100)) != null) return r;
        if ((r = eq("FORAGING perk at 57", t.perk(SkillType.FORAGING, 57), 228)) != null) return r;
        if ((r = eq("FARMING perk at 60", t.perk(SkillType.FARMING, 60), 240)) != null) return r;
        if ((r = eq("ENCHANTING Intelligence at 60", stat(t, SkillType.ENCHANTING, 60, Stat.INTELLIGENCE), 106)) != null) return r;
        if ((r = eq("ENCHANTING Ability Damage at 60", stat(t, SkillType.ENCHANTING, 60, Stat.ABILITY_DAMAGE), 30)) != null) return r;
        if ((r = eq("ALCHEMY Intelligence at 50", stat(t, SkillType.ALCHEMY, 50, Stat.INTELLIGENCE), 86)) != null) return r;
        if ((r = eq("CARPENTRY Health at 50", stat(t, SkillType.CARPENTRY, 50, Stat.HEALTH), 50)) != null) return r;
        if ((r = eq("TAMING Pet Luck at 60", stat(t, SkillType.TAMING, 60, Stat.PET_LUCK), 60)) != null) return r;
        if ((r = eq("TAMING perk at 60", t.perk(SkillType.TAMING, 60), 60)) != null) return r;
        if ((r = eq("TAMING exp share at 60", t.perk2(SkillType.TAMING, 60), 12)) != null) return r;
        if ((r = eq("FISHING treasure perk at 50", t.perk(SkillType.FISHING, 50), 5)) != null) return r;
        if ((r = eq("FARMING coins to 60", t.coinsThrough(SkillType.FARMING, 60), 27_259_100)) != null) return r;
        if ((r = eq("FISHING coins to 50", t.coinsThrough(SkillType.FISHING, 50), 17_259_100)) != null) return r;
        if ((r = eq("FARMING SkyBlock XP to 60", t.sbxpThrough(SkillType.FARMING, 60), 1_000)) != null) return r;
        if ((r = eq("FORAGING SkyBlock XP to 57", t.sbxpThrough(SkillType.FORAGING, 57), 910)) != null) return r;
        if ((r = eq("FISHING SkyBlock XP to 50", t.sbxpThrough(SkillType.FISHING, 50), 700)) != null) return r;
        if ((r = eq("FARMING default cap", t.defaultCap(SkillType.FARMING), 50)) != null) return r;
        if ((r = eq("TAMING default cap", t.defaultCap(SkillType.TAMING), 50)) != null) return r;
        if ((r = eq("level for 1,175 XP", t.levelFor(SkillType.MINING, 1_175), 5)) != null) return r;
        if ((r = eq("level for 1,174 XP", t.levelFor(SkillType.MINING, 1_174), 4)) != null) return r;
        return null;
    }

    private static double stat(SkillTable t, SkillType s, int lvl, Stat st) { return t.statsAt(s, lvl).getOrDefault(st, 0.0); }

    private static String eq(String what, double got, double want) {
        return Math.abs(got - want) < 1e-6 ? null : what + ": got " + got + ", research says " + want;
    }
}
