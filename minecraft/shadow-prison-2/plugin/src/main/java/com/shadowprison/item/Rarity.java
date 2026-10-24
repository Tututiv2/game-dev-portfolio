package com.shadowprison.item;

import net.kyori.adventure.text.format.TextColor;

/** SkyBlock-style rarity ladder. The name colour IS the rarity — a player reads it at a glance. */
public enum Rarity {
    COMMON(0xFFFFFF),
    UNCOMMON(0x55FF55),
    RARE(0x5555FF),
    EPIC(0xAA00AA),
    LEGENDARY(0xFFAA00),
    MYTHIC(0xFF55FF),
    DIVINE(0x55FFFF),
    SPECIAL(0xFF5555);

    private final TextColor color;

    Rarity(int rgb) {
        this.color = TextColor.color(rgb);
    }

    public TextColor color() {
        return color;
    }

    public Rarity next() {
        return this == SPECIAL ? SPECIAL : values()[ordinal() + 1];
    }
}
