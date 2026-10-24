package com.skyclone.mod.skills;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.skill.SkillType;

import java.util.EnumMap;
import java.util.Locale;

/** Every tunable of the skills module. Read with {@code .get()} at use time. */
final class SkillDials {

    final Dials.Dial<Double> xpMultiplier;
    final EnumMap<SkillType, Dials.Dial<Double>> perSkill = new EnumMap<>(SkillType.class);
    final EnumMap<SkillType, Dials.Dial<Integer>> defaultCap = new EnumMap<>(SkillType.class);
    final Dials.Dial<Boolean> capGates;
    final Dials.Dial<Boolean> coinRewards;
    final Dials.Dial<Double> coinMultiplier;
    final Dials.Dial<Boolean> sbxpCatchUp;
    final Dials.Dial<Boolean> levelUpSound;
    final Dials.Dial<Integer> flashTicks;
    final Dials.Dial<Integer> bulkBoxThreshold;
    final Dials.Dial<Boolean> combatXp;
    final Dials.Dial<Boolean> combatXpPrivateIsland;
    final Dials.Dial<Boolean> warrior;
    final Dials.Dial<Boolean> warriorMagic;
    final Dials.Dial<Double> perkScale;
    final Dials.Dial<Boolean> conjurer;
    final Dials.Dial<Boolean> tabWidget;
    final Dials.Dial<Boolean> averageFractional;
    final Dials.Dial<Boolean> socialXp;
    final Dials.Dial<Double> socialVisitPerMinute;
    final Dials.Dial<Double> socialHostPerMinute;
    final Dials.Dial<Double> socialUniqueGuest;
    final Dials.Dial<Boolean> runebook;
    final Dials.Dial<String> averageHunting;
    final Dials.Dial<Boolean> averageCarpentry;

    SkillDials(SkillTable table) {
        var d = Sky.dials();
        xpMultiplier = d.number("skills.xp-multiplier", 1.0, "Global multiplier on all skill XP (after Wisdom)");
        for (SkillType s : SkillType.values()) {
            String k = s.name().toLowerCase(Locale.ROOT);
            perSkill.put(s, d.number("skills.xp-multiplier." + k, 1.0, "Extra multiplier on " + s.display + " XP"));
            defaultCap.put(s, d.integer("skills.default-cap." + k, table.defaultCap(s),
                    s.display + " level cap before any unlock (max " + table.maxLevel(s) + ")"));
        }
        capGates = d.flag("skills.cap-gates", true, "Enforce level caps (Farming/Taming/Foraging 50 until unlocked). false = every skill can reach its max");
        coinRewards = d.flag("skills.coin-rewards", true, "Pay the coin reward on each level up");
        coinMultiplier = d.number("skills.coin-multiplier", 1.0, "Multiplier on level-up coin rewards");
        sbxpCatchUp = d.flag("skills.sbxp-catch-up", true, "On join, award SkyBlock XP for skill levels reached while the SkyBlock Level system was missing");
        levelUpSound = d.flag("skills.level-up-sound", true, "Play the level-up sound");
        flashTicks = d.integer("skills.flash-ticks", 40, "How long the '+XP Skill (x/y)' action-bar popup stays (ticks)");
        bulkBoxThreshold = d.integer("skills.bulk-levelup-box", 3, "Gaining more levels than this at once shows ONE combined level-up box");
        combatXp = d.flag("skills.combat-xp", true, "Grant Combat XP from mob kills");
        combatXpPrivateIsland = d.flag("skills.combat-xp-private-island", false, "Grant Combat XP for mobs killed on private islands (reference: no)");
        warrior = d.flag("skills.perk.warrior", true, "Combat perk: +% damage to mobs per Combat level");
        warriorMagic = d.flag("skills.perk.warrior-magic", false, "Combat perk also boosts ability (magic) damage"); // TUNE: research does not say; off is the conservative read
        perkScale = d.number("skills.perk-scale", 1.0, "Multiplier on every skill perk value (fortunes, treasure chance, combat damage, orbs)");
        conjurer = d.flag("skills.perk.conjurer", true, "Enchanting perk: more vanilla experience orbs");
        tabWidget = d.flag("skills.tab-widget", true, "Show the Skills widget in the tab list");
        averageFractional = d.flag("skills.average-fractional", false, "Skill average counts progress into the current level (false = whole levels, like the reference menu)");
        socialXp = d.flag("skills.social-xp", true, "Grant Social XP for visiting and hosting on private islands");
        socialVisitPerMinute = d.number("skills.social.visit-per-minute", 1.0, "Social XP per minute spent visiting another player's island");
        socialHostPerMinute = d.number("skills.social.host-per-minute", 2.0, "Social XP per minute while hosting at least one guest");
        socialUniqueGuest = d.number("skills.social.unique-guest", 10.0, "Social XP the host gets for each NEW unique guest");
        runebook = d.flag("skills.runebook", true, "Runebook accessory: counts the Runic mobs you kill; +1 Runecrafting Wisdom per digit of the count");
        averageCarpentry = d.flag("skills.average-includes-carpentry", true,
                "Count Carpentry in the Skill Average (research 02: the reference counts every skill except Social, Runecrafting and Dungeoneering; the API marks Carpentry cosmetic)");
        averageHunting = d.text("skills.average-includes-hunting", "auto",
                "Count Hunting in the Skill Average: auto (only once a module provides Hunting, so nobody's average drops for a skill they cannot level) | true (research 02: the reference counts it) | false");
    }
}
