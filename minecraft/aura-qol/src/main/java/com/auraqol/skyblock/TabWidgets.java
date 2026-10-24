package com.auraqol.skyblock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading Hypixel's TAB-list widgets: the area, the Commissions widget and
 * the Pickaxe Ability widget. Pure string work, no Minecraft.
 *
 * <p>The tab list arrives as up to 80 player-list entries in display order
 * (4 columns of 20). Each widget is a header line with no indent
 * ({@code "Commissions:"}) followed by indented entries
 * ({@code " Mithril Miner: 45%"}), and widgets are separated by a blank
 * entry - but only when there is room, so a parser must ALSO stop at the
 * first line that is not shaped like an entry of that widget (the next
 * widget's header, a column title, another widget's " Mithril: 1,234").
 * That is why the entry patterns are strict: a commission entry must end in
 * a percentage, DONE or x/y, which no neighbouring widget's lines do.
 */
public final class TabWidgets {

    private TabWidgets() {
    }

    /**
     * Colour codes off, emoji (anything outside the Basic Multilingual Plane)
     * off, non-breaking spaces made plain, runs of spaces collapsed, trimmed.
     * Hypixel builds sidebar and tab lines with emoji fillers and splits a
     * line across team prefix / owner / suffix, so this is the one cleaning
     * every SkyBlock line parser applies before matching.
     */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        String t = Fmt.strip(s);
        StringBuilder sb = new StringBuilder(t.length());
        boolean space = false;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isSupplementaryCodePoint(cp) || cp == 0xFE0F || cp == 0x200B) {
                continue; // emoji, variation selector, zero-width space
            }
            if (cp == 0xA0 || Character.isWhitespace(cp)) {
                space = true;
                continue;
            }
            if (space && sb.length() > 0) {
                sb.append(' ');
            }
            space = false;
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ area

    private static final Pattern AREA = Pattern.compile("^(?:Area|Dungeon):\\s*(.+)$");

    /** "Area: Dwarven Mines" -> "Dwarven Mines"; null if the Info widget is not shown. */
    public static String area(List<String> tab) {
        if (tab == null) {
            return null;
        }
        for (String raw : tab) {
            Matcher m = AREA.matcher(clean(raw));
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return null;
    }

    private static final String[] MINING_AREAS = {
            "dwarven mines", "crystal hollows", "mineshaft", "glacite", "gold mine", "deep caverns"};

    /** The islands where commissions and pickaxe abilities matter. */
    public static boolean isMiningArea(String area) {
        if (area == null) {
            return false;
        }
        String a = area.toLowerCase(Locale.ROOT);
        for (String m : MINING_AREAS) {
            if (a.contains(m)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ commissions

    /**
     * One commission. {@code percent} is 0-100 (100 when done); {@code progress}
     * is what to print: "45%", "DONE", "3/5".
     */
    public record Commission(String name, double percent, boolean done, String progress) {
    }

    /** "Commissions:", "Commissions", "Commissions (2/4):". Not "Commission Milestones:". */
    private static final Pattern COMM_HEADER = Pattern.compile(
            "^Commissions(?:\\s*\\([^)]*\\))?\\s*:?$", Pattern.CASE_INSENSITIVE);

    /** "Mithril Miner: 45%", "Goblin Slayer: DONE", "Chest Looter: 3/5". */
    private static final Pattern COMM_LINE = Pattern.compile(
            "^(.*?[A-Za-z].*?):\\s*(DONE|COMPLETED?|\\d{1,3}(?:[.,]\\d+)?\\s*%|[\\d,]+\\s*/\\s*[\\d,]+)$",
            Pattern.CASE_INSENSITIVE);

    private static final int MAX_COMMISSIONS = 8;

    /**
     * The commissions in the tab list, in the order shown. Empty when there is
     * no Commissions widget (not on a mining island, or the widget is turned
     * off in /tab) - which is exactly when the HUD panel hides.
     */
    public static List<Commission> commissions(List<String> tab) {
        List<Commission> out = new ArrayList<>();
        if (tab == null) {
            return out;
        }
        for (int i = 0; i < tab.size(); i++) {
            if (!COMM_HEADER.matcher(clean(tab.get(i))).matches()) {
                continue;
            }
            for (int j = i + 1; j < tab.size() && out.size() < MAX_COMMISSIONS; j++) {
                Commission c = commission(clean(tab.get(j)));
                if (c == null) {
                    break; // blank line, next header, another widget's line
                }
                out.add(c);
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        return out;
    }

    /** One entry line, already cleaned; null if it is not a commission. */
    static Commission commission(String line) {
        Matcher m = COMM_LINE.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String name = m.group(1).trim();
        String p = m.group(2).replace(" ", "");
        String up = p.toUpperCase(Locale.ROOT);
        if (up.startsWith("DONE") || up.startsWith("COMPLETE")) {
            return new Commission(name, 100, true, "DONE");
        }
        try {
            if (p.endsWith("%")) {
                double pct = Double.parseDouble(p.substring(0, p.length() - 1).replace(',', '.'));
                pct = Math.max(0, Math.min(100, pct));
                return new Commission(name, pct, pct >= 100, pct >= 100 ? "DONE" : trimPct(pct));
            }
            String[] xy = p.split("/");
            double x = Double.parseDouble(xy[0].replace(",", ""));
            double y = Double.parseDouble(xy[1].replace(",", ""));
            if (y <= 0) {
                return null;
            }
            double pct = Math.max(0, Math.min(100, x * 100 / y));
            return new Commission(name, pct, x >= y, x >= y ? "DONE" : xy[0] + "/" + xy[1]);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    private static String trimPct(double pct) {
        if (pct == Math.rint(pct)) {
            return (long) pct + "%";
        }
        return String.format(Locale.ROOT, "%.1f%%", pct);
    }

    // ------------------------------------------------------------ pickaxe ability

    /**
     * The Pickaxe Ability widget: which ability, and either available or
     * {@code secondsLeft} until it is.
     */
    public record AbilityStatus(String name, boolean available, int secondsLeft) {
    }

    private static final Pattern ABILITY_HEADER = Pattern.compile(
            "^Pickaxe Ability:?\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    /** "Mining Speed Boost: Available", "Pickobulus: 45s", "Maniac Miner: 1m 5s". */
    private static final Pattern ABILITY_LINE = Pattern.compile("^(.*?[A-Za-z].*?):\\s*(.+)$");

    /**
     * The widget as either one line ({@code "Pickaxe Ability: Mining Speed
     * Boost: Available"}) or a header plus one entry. Null when absent or
     * unreadable.
     */
    public static AbilityStatus pickaxeAbility(List<String> tab) {
        if (tab == null) {
            return null;
        }
        for (int i = 0; i < tab.size(); i++) {
            Matcher h = ABILITY_HEADER.matcher(clean(tab.get(i)));
            if (!h.matches()) {
                continue;
            }
            String rest = h.group(1).trim();
            AbilityStatus s = rest.isEmpty() ? null : abilityLine(rest);
            if (s == null && i + 1 < tab.size()) {
                s = abilityLine(clean(tab.get(i + 1)));
            }
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    static AbilityStatus abilityLine(String line) {
        Matcher m = ABILITY_LINE.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String name = m.group(1).trim();
        String st = m.group(2).trim();
        String up = st.toUpperCase(Locale.ROOT);
        if (up.startsWith("AVAILABLE") || up.startsWith("READY")) {
            return new AbilityStatus(name, true, 0);
        }
        int secs = seconds(st);
        if (secs < 0) {
            return null;
        }
        return new AbilityStatus(name, secs == 0, secs);
    }

    private static final Pattern TIME_PART = Pattern.compile("(\\d+)\\s*([hms])", Pattern.CASE_INSENSITIVE);

    /** "45s", "1m 5s", "1m05s", "2m" -> seconds; -1 if it is not a time. */
    public static int seconds(String s) {
        if (s == null) {
            return -1;
        }
        String t = s.trim();
        if (!t.matches("(?i)^(\\d+\\s*[hms]\\s*)+$")) {
            return -1;
        }
        Matcher m = TIME_PART.matcher(t);
        int total = 0;
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            switch (Character.toLowerCase(m.group(2).charAt(0))) {
                case 'h' -> total += n * 3600;
                case 'm' -> total += n * 60;
                default -> total += n;
            }
        }
        return total;
    }
}
