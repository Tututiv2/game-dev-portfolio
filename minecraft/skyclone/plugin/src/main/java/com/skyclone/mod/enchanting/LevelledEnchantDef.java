package com.skyclone.mod.enchanting;

import com.skyclone.api.item.EnchantDef;

/**
 * The EnchantDef every enchant registers with core: the api's single-{@code %s} {@link EnchantDef#description}
 * (series 0 only, rounded by core to 0.1) PLUS {@link #describe(int)}, the full text at a level with EVERY number
 * filled in at up to three decimals ("Dropping below 40% health grants +12.5% Defense for 10s and restores 10
 * Vitality"; Mana Steal "0.25%", Crop Fever "0.005%").
 *
 * <p>Forward compatible on purpose: core's {@code LoreRenderer} cannot call this yet (request 1). The day
 * {@code EnchantDef} gains {@code public String describe(int level)} and the renderer uses it, this method
 * overrides it automatically - same name, same signature - and multi-number item lore works with no change here.
 * Self-test {@code enchanting.multi-number-lore} reports which of the two renderers is live.
 */
public class LevelledEnchantDef extends EnchantDef {

    private final transient EnchantSpec spec;

    public LevelledEnchantDef(EnchantSpec spec) { this.spec = spec; }

    public EnchantSpec spec() { return spec; }

    /** Full description at {@code level}: every value series filled in ("&amp;" colour codes kept). */
    public String describe(int level) {
        if (spec == null) return description == null ? "" : description.replace("%s", EnchantSpec.fmt(value(level)));
        return spec.describe(Math.max(1, Math.min(level, Math.max(1, spec.maxLevel))));
    }

    /** True when the api's EnchantDef has {@code describe(int)} itself, i.e. core can render the full text. */
    public static boolean coreRendersFullText() {
        try {
            EnchantDef.class.getDeclaredMethod("describe", int.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
