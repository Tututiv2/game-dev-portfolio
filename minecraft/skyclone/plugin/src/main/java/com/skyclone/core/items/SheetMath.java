package com.skyclone.core.items;

import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatContributor;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.item.ReforgeDef;
import com.skyclone.api.stat.Stat;

import java.util.Collection;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The stat sheet of one item instance. Pure Java so it is unit-testable.
 *
 * <pre>
 *   BASE     ItemDef.stats + ItemDef.damage
 *   REFORGE  ReforgeDef.statsFor(effective rarity)
 *   POTATO   research 01 §14b: weapons +2 Damage +2 Strength per book; armour +4 Health +2 Defense
 *   ...      every registered ItemStatContributor, in registration order
 * </pre>
 */
public final class SheetMath {

    private SheetMath() {}

    /** Research 01 §14b. */
    public static final double POTATO_WEAPON_DAMAGE = 2, POTATO_WEAPON_STRENGTH = 2;
    public static final double POTATO_ARMOR_HEALTH = 4, POTATO_ARMOR_DEFENSE = 2;
    /** Hot (10) + Fuming (5). */
    public static final int POTATO_MAX = 15;

    public static Rarity effectiveRarity(ItemDef def, ItemData data) {
        Rarity r = def.rarity == null ? Rarity.COMMON : def.rarity;
        return data != null && data.recombobulated ? r.upgrade() : r;
    }

    /** Potato books apply to swords, bows, longswords and fishing weapons (and the other melee kinds). */
    public static boolean potatoWeapon(ItemCategory c) {
        return c != null && (c.isMeleeWeapon() || c == ItemCategory.BOW);
    }

    public static ItemStatSheet compute(ItemDef def, ItemData data, Rarity rarity,
                                        Function<String, ReforgeDef> reforges,
                                        Collection<ItemStatContributor> contributors,
                                        BiConsumer<Object, Throwable> onError) {
        ItemStatSheet sheet = new ItemStatSheet();
        if (def == null) return sheet;
        if (data == null) data = new ItemData();
        if (def.damage != 0) sheet.addDamage(def.damage, false);
        if (def.stats != null) for (Map.Entry<Stat, Double> e : def.stats.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) sheet.add(e.getKey(), e.getValue(), ItemStatSheet.Source.BASE);
        }
        if (data.reforge != null && reforges != null) {
            ReforgeDef rf = reforges.apply(data.reforge);
            if (rf != null) {
                Map<Stat, Double> m = rf.statsFor(rarity);
                if (m != null) for (Map.Entry<Stat, Double> e : m.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) sheet.add(e.getKey(), e.getValue(), ItemStatSheet.Source.REFORGE);
                }
            }
        }
        int books = Math.min(POTATO_MAX, Math.max(0, data.potatoBooks));
        if (books > 0) {
            if (potatoWeapon(def.category)) {
                sheet.addDamage(books * POTATO_WEAPON_DAMAGE, true);
                sheet.add(Stat.STRENGTH, books * POTATO_WEAPON_STRENGTH, ItemStatSheet.Source.POTATO);
            } else if (def.category != null && def.category.isArmor()) {
                sheet.add(Stat.HEALTH, books * POTATO_ARMOR_HEALTH, ItemStatSheet.Source.POTATO);
                sheet.add(Stat.DEFENSE, books * POTATO_ARMOR_DEFENSE, ItemStatSheet.Source.POTATO);
            }
        }
        if (contributors != null) for (ItemStatContributor c : contributors) {
            try {
                c.contribute(def, data, rarity, sheet);
            } catch (Throwable t) {
                if (onError != null) onError.accept(c, t);
            }
        }
        return sheet;
    }
}
