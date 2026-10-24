package com.auraqol.skyblock;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the player is DOING on Hypixel SkyBlock right now - Mining, Farming, Fishing, Foraging,
 * Combat - read from the skill-XP part of the action bar ("+23.4 Farming (1,234/2,000)").
 *
 * <p>Owner, 2026-10-13: the money panel should say Combat while killing zombies and doing slayers,
 * Farming while farming, and so on. Hypixel already tells us exactly that: every XP gain prints the
 * skill it went to, so there is nothing to guess (slayer kills give Combat XP, which is what the owner
 * means by "doing slayers"). Pure: no Minecraft imports, unit-tested.
 */
public final class SkillActivity {

    /** How long after the last XP popup the activity still shows. */
    public static final long HOLD_MS = 60_000;

    private static final Pattern GAIN = Pattern.compile(
            "\\+[\\d,.]+ (Farming|Mining|Combat|Foraging|Fishing|Enchanting|Alchemy|Taming|Carpentry|Runecrafting|Social|Hunting) \\(");

    private String skill;
    private long at = Long.MIN_VALUE;

    /** Feed every action-bar line (plain text, colour codes already removed). */
    public void observe(String actionBar, long nowMs) {
        String s = parse(actionBar);
        if (s != null) {
            skill = s;
            at = nowMs;
        }
    }

    /** The skill that last gained XP, or null if none did in the last {@link #HOLD_MS}. */
    public String current(long nowMs) {
        return skill != null && nowMs - at <= HOLD_MS ? skill : null;
    }

    public void clear() {
        skill = null;
        at = Long.MIN_VALUE;
    }

    /** The skill name in one action-bar line, or null. */
    public static String parse(String actionBar) {
        if (actionBar == null || actionBar.indexOf('+') < 0) {
            return null;
        }
        Matcher m = GAIN.matcher(actionBar.replaceAll("§.", ""));
        return m.find() ? m.group(1) : null;
    }
}
