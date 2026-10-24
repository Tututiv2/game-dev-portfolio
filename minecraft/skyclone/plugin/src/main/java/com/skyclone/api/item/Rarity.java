package com.skyclone.api.item;

/** Item / pet rarity. Colour is a legacy code char. Order matters: ordinal is the tier. */
public enum Rarity {
    COMMON('f'),
    UNCOMMON('a'),
    RARE('9'),
    EPIC('5'),
    LEGENDARY('6'),
    MYTHIC('d'),
    DIVINE('b'),
    SPECIAL('c'),
    VERY_SPECIAL('c'),
    ULTIMATE('4'),
    ADMIN('4');

    public final char color;

    Rarity(char color) { this.color = color; }

    /** One tier up (recombobulator, pet upgrade). Caps at DIVINE for normal items. */
    public Rarity upgrade() {
        return switch (this) {
            case COMMON -> UNCOMMON;
            case UNCOMMON -> RARE;
            case RARE -> EPIC;
            case EPIC -> LEGENDARY;
            case LEGENDARY -> MYTHIC;
            case MYTHIC -> DIVINE;
            case SPECIAL -> VERY_SPECIAL;
            default -> this;
        };
    }

    /** "LEGENDARY", "VERY SPECIAL" */
    public String display() { return name().replace('_', ' '); }

    public String code() { return "&" + color; }

    public static Rarity parse(String s) {
        if (s == null) return COMMON;
        try { return valueOf(s.trim().toUpperCase().replace(' ', '_')); }
        catch (IllegalArgumentException e) { return COMMON; }
    }
}
