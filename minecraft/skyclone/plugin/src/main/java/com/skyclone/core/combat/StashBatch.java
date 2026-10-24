package com.skyclone.core.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One player's NAMED stash notices (rewards, trades, uniques) gathered into a single chat line. Pure: no Bukkit.
 *
 * <p>Playtest wave4-endgame: {@code /sb foraging kit} (17 unique axes) into a full inventory printed 17
 * "Your inventory is full! X went to your item stash" lines - the bulk throttle covered stacks, but every
 * unique item is its own give and was named on its own. A burst is now said once: one item keeps the old
 * line, several become "17 items went to your item stash: A, B, C and 14 more" with the full list on hover.
 */
final class StashBatch {

    /** How many names the chat line spells out before "and N more". */
    static final int SHOWN = 3;
    /** How many names the hover lists before "...and N more". */
    static final int HOVER_MAX = 20;

    /** Display name (with colour codes) -> amount, in arrival order; the same name given twice adds up. */
    private final Map<String, Long> items = new LinkedHashMap<>();

    void add(String name, long amount) {
        if (name == null || amount <= 0) return;
        items.merge(name, amount, Long::sum);
    }

    boolean isEmpty() { return items.isEmpty(); }

    int kinds() { return items.size(); }

    long total() {
        long t = 0;
        for (long n : items.values()) t += n;
        return t;
    }

    /** The chat line, without the [PICK UP] button (the caller appends it). */
    String line() {
        if (items.isEmpty()) return "";
        if (items.size() == 1) {
            Map.Entry<String, Long> e = items.entrySet().iterator().next();
            return "&cYour inventory is full! " + e.getKey() + amountTag(e.getValue()) + " &ewent to your item stash. ";
        }
        long total = total();
        StringBuilder sb = new StringBuilder("&cYour inventory is full! &e")
                .append(commas(total)).append(" items went to your item stash: ");
        int i = 0;
        for (Map.Entry<String, Long> e : items.entrySet()) {
            if (i == SHOWN) break;
            if (i > 0) sb.append("&e, ");
            sb.append(e.getKey()).append(amountTag(e.getValue()));
            i++;
        }
        int more = items.size() - SHOWN;
        if (more > 0) sb.append(" &7and ").append(more).append(" more");
        return sb.append("&e. ").toString();
    }

    /** Hover lines naming every item (capped), or empty when the line already names them all. */
    List<String> hover() {
        List<String> out = new ArrayList<>();
        if (items.size() <= SHOWN) return out;
        out.add("&eIn your item stash now:");
        int i = 0;
        for (Map.Entry<String, Long> e : items.entrySet()) {
            if (i == HOVER_MAX) { out.add("&7...and " + (items.size() - HOVER_MAX) + " more"); break; }
            out.add("&7- " + e.getKey() + amountTag(e.getValue()));
            i++;
        }
        out.add("");
        out.add("&eClick &6[PICK UP] &eto collect them.");
        return out;
    }

    /**
     * Milliseconds to wait before saying the next batch: at least one tick so a same-tick burst lands in one
     * line, and never sooner than {@code windowMs} after the previous named line, so a give spread across
     * ticks still reads as one or two lines rather than one per item.
     */
    static long delayMs(Long lastLineAt, long now, long windowMs) {
        long tick = 50;
        if (lastLineAt == null) return tick;
        long wait = lastLineAt + Math.max(0, windowMs) - now;
        return Math.max(tick, wait);
    }

    private static String amountTag(long n) {
        return n > 1 ? " &8x" + commas(n) : "";
    }

    private static String commas(long n) {
        return String.format(java.util.Locale.ROOT, "%,d", n);
    }
}
