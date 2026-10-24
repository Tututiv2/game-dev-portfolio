package com.shadowprison.stat;

import net.kyori.adventure.text.format.TextColor;

/**
 * Every stat in the game. Items, skills, collections, pets and enchants all contribute to the same
 * table, and mining/combat read one number per stat — SkyBlock's model. (SP1 had a different
 * bespoke multiplier per system, which is why its sell boost stacked to an unknowable product.)
 */
public enum Stat {
    MINING_SPEED("Mining Speed", "⸕", 0xFFAA00, false),
    MINING_FORTUNE("Mining Fortune", "☘", 0xFFAA00, false),
    STRENGTH("Strength", "❁", 0xFF5555, false),
    CRIT_CHANCE("Crit Chance", "☣", 0x5555FF, true),
    CRIT_DAMAGE("Crit Damage", "☠", 0x5555FF, true),
    DEFENSE("Defense", "❈", 0x55FF55, false),
    HEALTH("Health", "❤", 0xFF5555, false),
    SPEED("Speed", "✦", 0xFFFFFF, false),
    SELL_BONUS("Sell Bonus", "⛃", 0xFFD45E, true),
    SKILL_WISDOM("Skill Wisdom", "☯", 0x55FFFF, true);

    private final String display;
    private final String symbol;
    private final TextColor color;
    private final boolean percent;

    Stat(String display, String symbol, int rgb, boolean percent) {
        this.display = display;
        this.symbol = symbol;
        this.color = TextColor.color(rgb);
        this.percent = percent;
    }

    public String display() {
        return display;
    }

    public String symbol() {
        return symbol;
    }

    public TextColor color() {
        return color;
    }

    public boolean percent() {
        return percent;
    }

    /** "+50" or "+12%" */
    public String formatValue(long value) {
        return (value >= 0 ? "+" : "") + value + (percent ? "%" : "");
    }
}
