package com.auraqol.skyblock;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The slayer quest as Hypixel prints it on the sidebar. Pure, no Minecraft.
 *
 * <pre>
 * Slayer Quest
 * Revenant Horror IV
 *  (1,234/1,500) Combat XP     while you are filling the bar
 *  Slay the Boss!              once the boss is spawned
 *  Boss slain!                 after the kill, until the quest is claimed
 * </pre>
 * The progress line has been seen with and without the parentheses and, in
 * the Rift, counted in Kills; numbers may carry k/M. The boss line may lead
 * with a symbol (a skull) and the tier may be Roman or a digit.
 */
public final class SlayerQuest {

    private SlayerQuest() {
    }

    public enum State { PROGRESS, SPAWNED, SLAIN, UNKNOWN }

    /**
     * {@code tier} 0 = not shown. {@code current}/{@code target} are NaN unless
     * {@code state} is PROGRESS. {@code unit} is "Combat XP", "Kills", ...
     */
    public record Quest(String boss, int tier, double current, double target, String unit, State state) {

        /** "Revenant Horror IV" / "Revenant Horror". */
        public String title() {
            return tier > 0 ? boss + " " + roman(tier) : boss;
        }

        public boolean hasProgress() {
            return state == State.PROGRESS && !Double.isNaN(current) && !Double.isNaN(target) && target > 0;
        }

        /** 0..1, or 1 once the boss is up. */
        public double fraction() {
            if (state == State.SPAWNED || state == State.SLAIN) {
                return 1;
            }
            return hasProgress() ? Math.max(0, Math.min(1, current / target)) : 0;
        }

        public double remaining() {
            return hasProgress() ? Math.max(0, target - current) : 0;
        }
    }

    private static final Pattern HEADER = Pattern.compile("^Slayer Quest\\b.*$", Pattern.CASE_INSENSITIVE);

    /** Boss name, then a tier: "Revenant Horror IV", "Voidgloom Seraph 3". */
    private static final Pattern BOSS = Pattern.compile("^[^A-Za-z]*([A-Za-z][A-Za-z' -]*?)\\s+([IVX]{1,4}|[1-9])\\s*$");
    private static final Pattern BOSS_NO_TIER = Pattern.compile("^[^A-Za-z]*([A-Za-z][A-Za-z' -]*[A-Za-z])\\s*$");

    private static final String NUM = "([0-9][0-9,]*(?:\\.[0-9]+)?\\s*[kKmMbB]?)";
    /** "(1,234/1,500) Combat XP", "1.2k/1.5k Combat XP", "(15/40) Kills". */
    private static final Pattern PROGRESS = Pattern.compile(
            "^\\(?\\s*" + NUM + "\\s*/\\s*" + NUM + "\\s*\\)?\\s*(.*)$");

    /**
     * The quest on this sidebar, or null when there is none. Lines are in
     * display order (top first); blank lines between the parts are skipped.
     */
    public static Quest parse(List<String> sidebar) {
        if (sidebar == null) {
            return null;
        }
        for (int i = 0; i < sidebar.size(); i++) {
            String head = TabWidgets.clean(sidebar.get(i));
            if (!HEADER.matcher(head).matches()) {
                continue;
            }
            String bossLine = null;
            int j = i + 1;
            // One-line form: "Slayer Quest: Revenant Horror IV"
            int colon = head.indexOf(':');
            if (colon >= 0 && !head.substring(colon + 1).isBlank()) {
                bossLine = head.substring(colon + 1).trim();
                j = i;
            }
            for (; bossLine == null && j < sidebar.size(); j++) {
                String c = TabWidgets.clean(sidebar.get(j));
                if (!c.isEmpty()) {
                    bossLine = c;
                    break;
                }
            }
            if (bossLine == null) {
                return null;
            }
            String boss;
            int tier = 0;
            Matcher bm = BOSS.matcher(bossLine);
            if (bm.matches()) {
                boss = bm.group(1).trim();
                tier = tier(bm.group(2));
            } else {
                Matcher nm = BOSS_NO_TIER.matcher(bossLine);
                if (!nm.matches()) {
                    return null; // not a boss name: a stray "Slayer Quest" mention
                }
                boss = nm.group(1).trim();
            }
            for (int k = j + 1; k < sidebar.size() && k <= j + 3; k++) {
                String c = TabWidgets.clean(sidebar.get(k));
                if (c.isEmpty()) {
                    continue;
                }
                String low = c.toLowerCase(Locale.ROOT);
                if (low.contains("slay the boss")) {
                    return new Quest(boss, tier, Double.NaN, Double.NaN, "", State.SPAWNED);
                }
                if (low.contains("boss slain")) {
                    return new Quest(boss, tier, Double.NaN, Double.NaN, "", State.SLAIN);
                }
                Matcher pm = PROGRESS.matcher(c);
                if (pm.matches()) {
                    double cur = number(pm.group(1));
                    double tgt = number(pm.group(2));
                    if (!Double.isNaN(cur) && !Double.isNaN(tgt) && tgt > 0) {
                        return new Quest(boss, tier, cur, tgt, pm.group(3).trim(), State.PROGRESS);
                    }
                }
                break; // the line after the boss is the status, whatever it is
            }
            return new Quest(boss, tier, Double.NaN, Double.NaN, "", State.UNKNOWN);
        }
        return null;
    }

    /** "1,234" -> 1234, "1.2k" -> 1200, "3M" -> 3e6; NaN if unreadable. */
    public static double number(String s) {
        if (s == null) {
            return Double.NaN;
        }
        String t = s.replace(",", "").replace(" ", "");
        if (t.isEmpty()) {
            return Double.NaN;
        }
        double mul = 1;
        char last = Character.toLowerCase(t.charAt(t.length() - 1));
        if (last == 'k' || last == 'm' || last == 'b') {
            mul = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
            t = t.substring(0, t.length() - 1);
        }
        try {
            return Double.parseDouble(t) * mul;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static int tier(String s) {
        if (s.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(s);
        }
        return switch (s) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            case "VI" -> 6;
            case "VII" -> 7;
            case "VIII" -> 8;
            case "IX" -> 9;
            case "X" -> 10;
            default -> 0;
        };
    }

    static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : Integer.toString(n);
    }
}
