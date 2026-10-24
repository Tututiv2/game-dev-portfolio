package com.skyclone.core.combat;

import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.ui.Text;

/**
 * Pure text formats for combat: damage indicators and mob nameplates (legacy '&amp;' strings).
 * No Bukkit, so they are unit-testable.
 */
public final class CombatFormat {

    private CombatFormat() {}

    /** The genre's crit colour cycle: white, yellow, gold, red. */
    private static final char[] CRIT_CYCLE = {'f', 'e', '6', 'c'};

    /**
     * Indicator text. Normal hits: grey "1,234". Crits: "✧1,234✧" with the first char white and
     * every following char cycling white -> yellow -> gold -> red (research 01 §11.3:
     * {@code §f✧§f1§e2§63§c4§f✧}).
     */
    public static String indicator(double damage, boolean crit, DamageContext.Kind kind) {
        String num = Text.commasInt(Math.max(0, damage));
        if (crit) {
            String s = "✧" + num + "✧";
            StringBuilder sb = new StringBuilder(s.length() * 3);
            int idx = 0;
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                char c = idx == 0 ? 'f' : CRIT_CYCLE[(idx - 1) % CRIT_CYCLE.length];
                sb.append('&').append(c).appendCodePoint(cp);
                i += Character.charCount(cp);
                idx++;
            }
            return sb.toString();
        }
        String colour = switch (kind == null ? DamageContext.Kind.MELEE : kind) {
            case TRUE -> "&f";
            case ENVIRONMENT -> "&6";
            default -> "&7";
        };
        return colour + num;
    }

    /** Health number colour: green above 50%, yellow above 25%, red below. */
    public static char healthColour(double hp, double max) {
        if (max <= 0) return 'a';
        double f = hp / max;
        if (f > 0.5) return 'a';
        if (f > 0.25) return 'e';
        return 'c';
    }

    /** Commas below the threshold, k/M/B above it. */
    public static String hpNum(double v, double shortThreshold) {
        double c = Math.ceil(Math.max(0, v));
        return c >= shortThreshold ? Text.shortNum(c) : Text.commasInt(c);
    }

    /**
     * Nameplate. Normal mobs: {@code &8[&7Lv30&8] &cCrypt Ghoul &a1,800&f/&a2,000&c❤}.
     * Bosses ({@code boss} true): {@code &c☠ &4Name &a50M&c❤} as the genre shows them.
     * {@code format} (nullable) overrides with placeholders {level} {name} {hp} {max} {hpc}
     * ({hpc} = the health colour code, e.g. "&amp;a").
     */
    public static String nameplate(String name, int level, double hp, double max, boolean boss,
                                   double shortThreshold, String format) {
        char c = healthColour(hp, max);
        String hpS = hpNum(hp, shortThreshold);
        String maxS = hpNum(max, shortThreshold);
        String n = name == null ? "???" : name;
        if (format != null && !format.isBlank()) {
            return format.replace("{level}", String.valueOf(level)).replace("{name}", n)
                    .replace("{hp}", hpS).replace("{max}", maxS).replace("{hpc}", "&" + c);
        }
        if (boss) return "&c☠ &4" + n + " &" + c + hpS + "&c❤";
        return "&8[&7Lv" + level + "&8] &c" + n + " &" + c + hpS + "&f/&a" + maxS + "&c❤";
    }

    /** "+12.5 coins" flash text. */
    public static String coins(double coins) {
        return "&6+" + Text.commas(Math.floor(coins * 10) / 10.0) + " coins";
    }
}
