package com.skyclone.mod.dungeons.model;

import java.util.List;

/**
 * The five dungeon classes (research 04 sec 3.4). Pure: names, colours, icons, the numbers of each passive and
 * ability, and the lore the class menu shows. Behaviour lives in {@code classes.ClassSystem}.
 */
public enum DClass {
    HEALER("Healer", 'd', "SPLASH_POTION",
            "Healing Circle", 2, "Wish", 120,
            List.of("&7Renew: &aheals +50% &7(+1%/lvl)",
                    "&7Healing Aura: allies within &a8 &7blocks heal &a0.5% &7max HP/s",
                    "&7Revive: a fallen ally rises after &a100s &7(-1s/lvl)",
                    "&7Orbies: kills may drop a healing orb (&a15%&7)",
                    "&7Soul Tether: the most hurt ally in &a30 &7blocks gets",
                    "&7  &a0.5% &7of your max HP/s; every &a5 &7melee hits heal both &a1%",
                    "&7Overheal: overflow healing becomes absorption, up to",
                    "&7  &a20% &7of the target's max HP (+0.4%/lvl)",
                    "&7+0.2 Vitality and +0.5 Mending per level"),
            List.of("&7Ability: &aHealing Circle &7- a ring that heals &a2% &7max HP/s",
                    "&7Ultimate: &aWish &7- heal the party to full + &a20% &7absorption",
                    "&7Ghost: &aHealing Potion &7(10 blocks) / &aRevive &7yourself after &a50s")),
    MAGE("Mage", 'b', "BLAZE_ROD",
            "Guided Sheep", 30, "Thunderstorm", 500,
            List.of("&7Mage Staff: swing at air to fire a &b10&7-block beam",
                    "&7  dealing &b(30% + 0.9 x Int/1000) &7of your melee damage",
                    "&7Efficient Spells: item cooldowns &b-25% &7(+1% per 2 lvls)",
                    "&7+5 Intelligence per level, +1 Ability Damage per 5 levels"),
            List.of("&7Ability: &bGuided Sheep &7- an exploding sheep; breaks crypts",
                    "&7  and cracked walls (&b100 &7-> &b2,000 &7damage)",
                    "&7Ultimate: &bThunderstorm &7- lightning in &b10 &7blocks for &b15s",
                    "&7Ghost: &bFireball &7/ &bInstant Wall &7(5x3, 10s)")),
    BERSERK("Berserk", 'c', "IRON_SWORD",
            "Throwing Axe", 10, "Ragnarok", 60,
            List.of("&7Bloodlust: after a kill your next hit heals &c3% &7missing HP",
                    "&7Lust for Blood: each hit on one enemy &c+3% &7damage (capped)",
                    "&7Weapon Master: melee hits up to &c5 &7enemies in front",
                    "&7Indomitable: gain &c10% &7(+0.2%/lvl) of your Strength as Defense",
                    "&7+0.75% melee damage per level"),
            List.of("&7Ability: &cThrowing Axe &7- damage = your highest hit this minute",
                    "&7Ultimate: &cRagnarok &7- &c+100 &7Attack Speed, &c+400 &7Speed, &c+50%",
                    "&7  damage for &c15s&7; &c3 &7zombies rise to fight beside you",
                    "&7Ghost: &cBuff Potion &7(+30 Strength, +2/5 lvls) / &cGhost Axe")),
    ARCHER("Archer", '6', "BOW",
            "Explosive Shot", 40, "Rapid Fire", 100,
            List.of("&7Doubleshot: &650% &7(+1%/lvl) chance for an extra arrow hit",
                    "&7Bone Plating: blocks part of the next hit (&6250s &7cooldown)",
                    "&7Bouncy Arrows: &6+2%/lvl &7chance to strike a second target",
                    "&7+1.6% arrow damage per level"),
            List.of("&7Ability: &6Explosive Shot &7- a &64&7-block blast; breaks crypts",
                    "&7Ultimate: &6Rapid Fire &7- &65 &7arrows/s for &64s &7(+1s per 10 lvls)",
                    "&7Ghost: &6Stun Bow &7/ &6Healing Bow &7(+10% HP, +50 Strength)")),
    TANK("Tank", '7', "LEATHER_CHESTPLATE",
            "Seismic Wave", 15, "Castle of Stone", 150,
            List.of("&7Base: &a+100 &7Health, &a+50 &7Defense, &a+10 &7Vitality",
                    "&7Protective Barrier: &a+25% &7Defense; below &a50% &7HP gain a shield",
                    "&7Taunt: mobs prefer you while above &a25% &7HP",
                    "&7Defensive Stance: immune to knockback",
                    "&7Diversion: &a80% &7of the damage allies within &a30 &7blocks take",
                    "&7  is taken by you instead",
                    "&7Solo: &a+50% &7Defense; the barrier triggers on any hit (&a45s&7)",
                    "&7+1 Defense, +0.1 Vitality, +0.2% Defense per level"),
            List.of("&7Ability: &aSeismic Wave &7- a line of shockwaves (&a20,000 &7+)",
                    "&7Ultimate: &aCastle of Stone &7- &a70% &7damage reduction for &a20s",
                    "&7Ghost: &aAbsorption Potion &7/ &aStun Potion"));

    public final String display;
    public final char color;
    public final String icon;
    public final String ability;
    public final int abilityCooldown;
    public final String ultimate;
    public final int ultimateCooldown;
    public final List<String> passives;
    public final List<String> abilities;

    DClass(String display, char color, String icon, String ability, int abilityCooldown, String ultimate, int ultimateCooldown,
           List<String> passives, List<String> abilities) {
        this.display = display;
        this.color = color;
        this.icon = icon;
        this.ability = ability;
        this.abilityCooldown = abilityCooldown;
        this.ultimate = ultimate;
        this.ultimateCooldown = ultimateCooldown;
        this.passives = passives;
        this.abilities = abilities;
    }

    /** "&dHealer" */
    public String colored() { return "&" + color + display; }

    public String key() { return name().toLowerCase(java.util.Locale.ROOT); }

    public static DClass parse(String s) {
        if (s == null) return null;
        try { return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
    }
}
