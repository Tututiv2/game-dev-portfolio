package com.skyclone.core.ui;

/** Pure maths for {@code MenuService.paged}: the inner 7x4 slots, page counts, title suffix. */
public final class PagedLayout {

    private PagedLayout() {}

    /** Slots 10-16, 19-25, 28-34, 37-43 — the inside of a 6-row chrome menu. */
    public static final int[] INNER = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    public static final int PER_PAGE = INNER.length;
    public static final int PREV_SLOT = 45;
    public static final int NEXT_SLOT = 53;

    /** At least 1, even for an empty list. */
    public static int pages(int entries) {
        return Math.max(1, (entries + PER_PAGE - 1) / PER_PAGE);
    }

    /** Clamp a 0-based page into range. */
    public static int clamp(int page, int entries) {
        return Math.max(0, Math.min(page, pages(entries) - 1));
    }

    public static int firstIndex(int page) { return page * PER_PAGE; }

    public static boolean hasPrev(int page) { return page > 0; }

    public static boolean hasNext(int page, int entries) { return page < pages(entries) - 1; }

    /** " (1/3)" when there is more than one page, otherwise "". {@code page} is 0-based. */
    public static String suffix(int page, int entries) {
        int n = pages(entries);
        return n > 1 ? " (" + (page + 1) + "/" + n + ")" : "";
    }
}
