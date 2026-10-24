package com.skyclone.core.ui;

/**
 * SkyBlock-level bracket colours (every 40 levels a new colour), as the genre shows them in chat
 * and the tab list: "&amp;8[&amp;b212&amp;8]". Pure.
 */
public final class LevelColors {

    private LevelColors() {}

    private static final String[] CODES = {
            "&7", // 0-39
            "&f", // 40-79
            "&e", // 80-119
            "&a", // 120-159
            "&2", // 160-199
            "&b", // 200-239
            "&3", // 240-279
            "&9", // 280-319
            "&d", // 320-359
            "&5", // 360-399
            "&6", // 400-439
            "&c", // 440-479
            "&4"  // 480+
    };

    public static String of(int level) {
        if (level < 0) level = 0;
        int i = Math.min(CODES.length - 1, level / 40);
        return CODES[i];
    }

    /** "&amp;8[&amp;b212&amp;8]" */
    public static String bracket(int level) {
        return "&8[" + of(level) + Math.max(0, level) + "&8]";
    }
}
