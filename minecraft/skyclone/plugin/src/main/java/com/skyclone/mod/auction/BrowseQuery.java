package com.skyclone.mod.auction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What the browser shows: tab, search text, sort, rarity and BIN filters. Pure - {@link #apply}
 * turns the live listings into one ordered page source, so the menu and the unit tests agree.
 */
public final class BrowseQuery {

    public enum Sort {
        HIGHEST("Highest Price"), LOWEST("Lowest Price"), ENDING("Ending Soon"), MOST_BIDS("Most Bids"), NEWEST("Newest");
        public final String label;
        Sort(String label) { this.label = label; }
        public Sort next() { return values()[(ordinal() + 1) % values().length]; }
        public Sort prev() { return values()[(ordinal() + values().length - 1) % values().length]; }
    }

    public enum BinFilter {
        ALL("Show All"), BIN_ONLY("BIN Only"), AUCTIONS_ONLY("Auctions Only");
        public final String label;
        BinFilter(String label) { this.label = label; }
        public BinFilter next() { return values()[(ordinal() + 1) % values().length]; }
        public BinFilter prev() { return values()[(ordinal() + values().length - 1) % values().length]; }
    }

    /** Rarities the filter cycles through; null = no filter. */
    public static final String[] RARITIES = {null, "COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL"};

    public AhTab tab = AhTab.WEAPONS;
    /** Null/blank = no search. A search looks across EVERY tab. */
    public String search;
    public Sort sort = Sort.HIGHEST;
    public String rarity;
    public BinFilter binFilter = BinFilter.ALL;
    /** Only this seller's listings (/ah &lt;player&gt;); null = everyone. */
    public String sellerName;
    public int page;
    /**
     * Listings with less than this left drop out of Browse and search (research 05 3.3: 4 min 45 s, marked (?)).
     * A seller's own page ({@code /ah <player>}) still shows them; 0 = off (dial {@code auction.browse-hide-seconds}).
     */
    public long hideBeforeEndMs;

    public boolean searching() { return search != null && !search.isBlank(); }

    public String nextRarity(int dir) {
        int i = 0;
        for (int k = 0; k < RARITIES.length; k++) if (java.util.Objects.equals(RARITIES[k], rarity)) { i = k; break; }
        return RARITIES[(i + dir + RARITIES.length) % RARITIES.length];
    }

    /** Every word of the search must appear in the listing's name+lore text (case-insensitive). */
    public static boolean matches(String haystack, String search) {
        if (search == null || search.isBlank()) return true;
        if (haystack == null) return false;
        String h = haystack.toLowerCase(Locale.ROOT);
        for (String w : search.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!w.isEmpty() && !h.contains(w)) return false;
        }
        return true;
    }

    public List<Listing> apply(Collection<Listing> all, long now) {
        List<Listing> out = new ArrayList<>();
        for (Listing l : all) {
            if (!l.active() || l.endsAt <= now) continue;
            if (hideBeforeEndMs > 0 && sellerName == null && l.endsAt - now < hideBeforeEndMs) continue;
            if (sellerName != null) {
                if (l.sellerName == null || !l.sellerName.equalsIgnoreCase(sellerName)) continue;
            } else if (searching()) {
                if (!matches(l.search, search)) continue;
            } else if (l.tab != tab) continue;
            if (rarity != null && !rarity.equals(l.rarity)) continue;
            if (binFilter == BinFilter.BIN_ONLY && !l.bin) continue;
            if (binFilter == BinFilter.AUCTIONS_ONLY && l.bin) continue;
            out.add(l);
        }
        out.sort(comparator(sort));
        return out;
    }

    public static Comparator<Listing> comparator(Sort sort) {
        Comparator<Listing> tie = Comparator.comparing((Listing l) -> l.id);
        return switch (sort) {
            case HIGHEST -> Comparator.comparingDouble(Listing::price).reversed().thenComparing(tie);
            case LOWEST -> Comparator.comparingDouble(Listing::price).thenComparing(tie);
            case ENDING -> Comparator.comparingLong((Listing l) -> l.endsAt).thenComparing(tie);
            case MOST_BIDS -> Comparator.comparingInt((Listing l) -> l.bidCount).reversed()
                    .thenComparing(Comparator.comparingDouble(Listing::price).reversed()).thenComparing(tie);
            case NEWEST -> Comparator.comparingLong((Listing l) -> l.created).reversed().thenComparing(tie);
        };
    }
}
