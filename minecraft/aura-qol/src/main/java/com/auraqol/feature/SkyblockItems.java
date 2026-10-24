package com.auraqol.feature;

import java.util.LinkedHashMap;
import java.util.Map;

import com.auraqol.skyblock.Fmt;
import com.auraqol.skyblock.SbItem;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The one place that reads Hypixel's item NBT, turning it into a plain
 * {@link SbItem} the pricing rules can work with.
 *
 * <p><b>Where the id lives.</b> Since 1.20.5 there is no item "tag"; Hypixel's
 * old {@code ExtraAttributes} compound is delivered as the
 * {@code minecraft:custom_data} component, flattened, so {@code id},
 * {@code enchantments}, {@code petInfo} sit at its root. A nested
 * {@code ExtraAttributes} is still checked for, in case a proxy or an older
 * protocol path delivers the legacy shape. Vanilla items have no custom data
 * and return null at the first check, which is what keeps this cheap enough
 * to run over every hovered stack.
 *
 * <p>{@code CustomData.copyTag()} is the only accessor 26.x exposes and it
 * copies; the copy is a few hundred bytes for a SkyBlock item, which is fine
 * for a tooltip and for a 41-slot scan twice a second.
 */
final class SkyblockItems {

    private SkyblockItems() {
    }

    /** The SkyBlock view of a stack, or null if it is not a SkyBlock item. */
    static SbItem read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) {
            return null;
        }
        CompoundTag root = cd.copyTag();
        CompoundTag tag = root.contains("id") ? root : root.getCompoundOrEmpty("ExtraAttributes");
        String id = tag.getStringOr("id", "");
        if (id.isEmpty()) {
            return null;
        }
        String name = Fmt.strip(stack.getHoverName().getString());
        return new SbItem(
                id,
                intMap(tag.getCompoundOrEmpty("enchantments")),
                tag.getString("petInfo").orElse(null),
                intMap(tag.getCompoundOrEmpty("runes")),
                tag.getIntOr("new_years_cake", 0),
                tag.getString("potion").orElse(null),
                tag.getIntOr("potion_level", 0),
                flag(tag, "enhanced"),
                flag(tag, "extended"),
                flag(tag, "splash"),
                name,
                stack.getCount());
    }

    private static Map<String, Integer> intMap(CompoundTag t) {
        if (t == null || t.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String k : t.keySet()) {
            int v = t.getIntOr(k, 0);
            if (v > 0) {
                out.put(k, v);
            }
        }
        return out;
    }

    /** Hypixel stores these flags as bytes, ints or strings depending on the item's age. */
    private static boolean flag(CompoundTag t, String key) {
        Tag v = t.get(key);
        if (v == null) {
            return false;
        }
        if (t.getBooleanOr(key, false) || t.getIntOr(key, 0) != 0) {
            return true;
        }
        String s = t.getStringOr(key, "");
        return s.equalsIgnoreCase("true") || s.equals("1");
    }
}
