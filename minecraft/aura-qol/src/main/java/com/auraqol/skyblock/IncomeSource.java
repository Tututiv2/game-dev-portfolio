package com.auraqol.skyblock;

/**
 * Where a coin of income came from - the money panel's breakdown rows.
 * Order is the display order.
 */
public enum IncomeSource {
    /** [Sacks] gains, and inventory gains of the crop/ore you are collecting. */
    SACKS("Crops/sacks", "crops"),
    /** RARE DROP!, VERY RARE DROP!, PET DROP!, RARE CROP!, Crop Fever, Jacob's Overclocker. */
    RARE("Rare drops", "rare"),
    /** "You received Nx X for killing a Y!" and the pest RARE DROP! lines. */
    PEST("Pest loot", "pest"),
    /** Sidebar purse changes outside trades. */
    PURSE("Purse coins", "purse"),
    /** Any other inventory change, valued per item. */
    OTHER("Other items", "other");

    private final String label;
    private final String shortLabel;

    IncomeSource(String label, String shortLabel) {
        this.label = label;
        this.shortLabel = shortLabel;
    }

    public String label() {
        return label;
    }

    public String shortLabel() {
        return shortLabel;
    }
}
