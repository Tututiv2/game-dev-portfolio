package com.auraqol.skyblock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading Hypixel's text: the sidebar purse line, the [Sacks] summary, and
 * the chat lines that mean "coins or items just changed hands in a trade".
 * Pure string work, so every pattern here has a test with the real wording.
 */
public final class SkyblockText {

    private SkyblockText() {
    }

    // ------------------------------------------------------------ purse

    /**
     * "Purse: 12,345,678" or "Piggy: 1,234.5", optionally followed by a
     * recent-change hint like " (+50)". The Piggy spelling is what the line
     * says while a Piggy Bank holds your purse. Same capture as SkyHanni's
     * {@code data.purse.coins} ("(?:Piggy|Purse): (?<coins>[\d,.]+)...") without the colour codes.
     */
    private static final Pattern PURSE = Pattern.compile(
            "(?:Purse|Piggy):\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)");

    /**
     * The purse from a list of sidebar lines, or null if no line has one (a
     * lobby, the Rift - which shows Motes instead - or a scoreboard mid-redraw).
     * Hypixel pads sidebar lines with formatting codes and emoji spacers, so
     * each line is colour-stripped and searched rather than matched whole.
     */
    public static Double purse(List<String> sidebarLines) {
        if (sidebarLines == null) {
            return null;
        }
        for (String raw : sidebarLines) {
            if (raw == null) {
                continue;
            }
            Matcher m = PURSE.matcher(Fmt.strip(raw));
            if (m.find()) {
                try {
                    return Double.parseDouble(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** True if a sidebar title reads like SkyBlock's ("SKYBLOCK", "SKYBLOCK CO-OP", ...). */
    public static boolean isSkyblockTitle(String title) {
        return title != null && Fmt.strip(title).toUpperCase(Locale.ROOT).contains("SKYBLOCK");
    }

    // ------------------------------------------------------------ sacks

    /** One line of a [Sacks] hover: "+1,280 Enchanted Cobblestone (Mining Sack)". */
    public record SackChange(String itemName, int delta) {
    }

    /**
     * SkyHanni's {@code SackApi.sackChangeRegex} ({@code ([+-][\d,]+) (.+) \((.+)\)}) - it lives in the Kotlin
     * source, not in the repo's regexesModern.json, which has no [Sacks] chat pattern at all. Same groups;
     * anchored to the line end so an item name with brackets keeps them.
     */
    private static final Pattern SACK_LINE = Pattern.compile(
            "([+-][0-9][0-9,]*) (.+?) \\(([^()]+)\\)\\s*$");

    /**
     * The chat line itself: "[Sacks] +1,280 items, -64 items. (Last 30s.)".
     * Only the summary is in the visible text; the per-item breakdown is in
     * the hover of the "+1,280 items" part, which is why the caller collects
     * hover texts and hands them to {@link #sackChanges}.
     */
    public static boolean isSackSummary(String plain) {
        return plain != null && Fmt.strip(plain).trim().startsWith("[Sacks]");
    }

    /**
     * Every item line in one hover text. The hover reads:
     * <pre>
     * Added items:
     *   +1,280 Enchanted Cobblestone (Mining Sack)
     *   +64 Coal (Mining Sack)
     *
     * This message can be disabled in the settings.
     * </pre>
     * "Removed items:" hovers use the same line format with minus signs. A
     * truncated list ends "...and 3 other items", which has no name to price
     * and is ignored rather than guessed at.
     */
    public static List<SackChange> sackChanges(String hoverText) {
        List<SackChange> out = new ArrayList<>();
        if (hoverText == null) {
            return out;
        }
        for (String line : MoneyChat.clean(hoverText).split("\\R")) {
            Matcher m = SACK_LINE.matcher(line.trim());
            if (!m.find()) {
                continue;
            }
            try {
                int delta = Integer.parseInt(m.group(1).replace(",", "").replace("+", ""));
                String name = m.group(2).trim();
                if (!name.isEmpty() && delta != 0) {
                    out.add(new SackChange(name, delta));
                }
            } catch (NumberFormatException ignored) {
                // a count past int range is not a sack line
            }
        }
        return out;
    }

    // ------------------------------------------------------------ trades

    /**
     * Chat that means a purse or inventory change is a TRADE, not income.
     * Buying, selling, banking and trading move value between forms - coins
     * to items, purse to bank - and a money tracker that counted them would
     * call a bazaar flip-in "-5M/h" and the flip-out "+5M/h". Most of these
     * happen inside a menu, which is excluded already; the chat check covers
     * the purse redraw that lands a second after the menu closes. The list
     * itself (with the SkyHanni pattern keys) lives in {@link MoneyChat#tradeRule}.
     */
    public static boolean isTradeMessage(String plain) {
        return MoneyChat.tradeRule(plain) != null;
    }

    /** "64x Enchanted Diamond" bought or claimed on the bazaar. */
    public record Acquired(String itemName, int count) {
    }

    /**
     * Items a bazaar purchase delivered ({@code bazaar.orders.chat.claimed.colorless},
     * {@code inventory.bazaar.transaction} "Bought"). They can land in your SACKS
     * rather than your inventory, and then turn up in the next [Sacks] "+N" -
     * which must not be counted as income when the coins that bought them were
     * (correctly) not counted as a loss. Null for any other line.
     */
    public static Acquired bazaarAcquired(String plain) {
        MoneyChat.Acquired a = MoneyChat.acquired(plain);
        if (a == null || !a.rule().startsWith("bazaar")) {
            return null;
        }
        return new Acquired(a.item(), a.count());
    }

    /**
     * Menu titles where items leaving the inventory are going INTO sacks, so
     * the next [Sacks] "+N" for them is a move, not a gain.
     */
    public static boolean isSackMenu(String title) {
        if (title == null) {
            return false;
        }
        String s = Fmt.strip(title).toLowerCase(Locale.ROOT);
        return s.contains("sack");
    }
}
