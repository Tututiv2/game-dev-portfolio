package com.shadowprison.money;

import net.kyori.adventure.text.format.TextColor;

/**
 * Every currency the server has, declared once. Adding one is a row here — the wallet, ledger and
 * menus pick it up. (SP1 had seven currencies in seven different stores.)
 */
public enum Currency {
    /** Earned by mining and selling. Spent on ranks, shops, the auction house. */
    COINS("Coins", TextColor.color(0xFFD45E), true),
    /** Bought with real money in the store. Cosmetics and convenience ONLY — never power. */
    SHARDS("Shadow Shards", TextColor.color(0xC07CFF), false);

    private final String display;
    private final TextColor color;
    private final boolean earnable;

    Currency(String display, TextColor color, boolean earnable) {
        this.display = display;
        this.color = color;
        this.earnable = earnable;
    }

    public String display() {
        return display;
    }

    public TextColor color() {
        return color;
    }

    /** False for the paid currency: no gameplay path may ever mint it. */
    public boolean earnable() {
        return earnable;
    }

    public String format(long amount) {
        return this == COINS ? Money.format(amount) : String.format(java.util.Locale.ROOT, "%,d %s", amount / 100, display);
    }
}
