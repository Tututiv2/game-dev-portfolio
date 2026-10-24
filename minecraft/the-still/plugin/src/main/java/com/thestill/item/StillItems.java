package com.thestill.item;

import com.thestill.fog.FogLamp;
import com.thestill.player.Onboarding;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * THE CATALOGUE — every custom item in The Still, built here and nowhere else.
 *
 * <p>This is the only class that can hand a stack its identity, because
 * {@link ItemTags#stamp} is package-private and this package has exactly one other
 * member. That is deliberate and it is the whole point: see {@link ItemRegistry}
 * for the law and the precedent ({@code DialRegistry}) it is copied from.
 *
 * <p><b>Adding an item is adding a block below.</b> It shows up in {@code /config}'s
 * item vault the moment it registers, with no second list to update — which is the
 * failure the vault was asked for in the first place ("config needs item vault so i
 * can give myself fog lamp, how am i supposed to test it???").
 *
 * <p>The <em>meaning</em> of an item still lives with the system that reads it —
 * {@link FogLamp} owns what a lamp does and how to recognise one, {@link Onboarding}
 * owns the first sixty seconds. What moved here is the construction, and only the
 * construction, so there can be one answer to "what does a Fog Lamp look like".
 */
public final class StillItems {

    /** The starter pickaxe. Unbreakable and deliberately bad — see the factory below. */
    public static final String RIG_PICK = "rig_pick";

    /** The handheld lamp that opens the fog where you look. */
    public static final String FOG_LAMP = "fog_lamp";

    /** Things you swing. */
    private static final String TOOLS = "Tools";

    /** Things you look through. The rig's INSTRUMENTS unit sells these. */
    private static final String INSTRUMENTS = "Instruments";

    private StillItems() {
    }

    /**
     * A registry holding everything, seeded once at enable.
     *
     * <p>Nothing here touches Bukkit while registering — every factory is a lambda
     * and none of them runs until somebody asks for an item. That is what lets a
     * unit test walk the real catalogue without a server under it.
     */
    public static ItemRegistry catalogue(ItemTags tags) {
        ItemRegistry registry = new ItemRegistry();

        // ---- Tools ------------------------------------------------------

        registry.define(RIG_PICK)
                .category(TOOLS)
                .label("Rig Pick")
                .purpose("The starter pickaxe, handed out once on the Landing. Unbreakable, "
                        + "and too weak for gold, lapis, redstone or emerald.")
                .tag(Onboarding.TOOL_KIND, Onboarding.RIG_PICK_ID)
                .register(() -> rigPick(tags));

        // ---- Instruments -------------------------------------------------

        registry.define(FOG_LAMP)
                .category(INSTRUMENTS)
                .label("Fog Lamp")
                .purpose("Hold it in your main hand below the fog line and the weather opens "
                        + "down a cone where you are looking. Needs fog.lamp-enabled ON.")
                .tag(FogLamp.KIND, FogLamp.MK1)
                .register(() -> fogLamp(tags));

        return registry;
    }

    /**
     * The starting tool: <b>unbreakable, and deliberately bad</b>.
     *
     * <p>The two properties are a pair, and neither works alone.
     *
     * <p><b>Unbreakable</b> because it is the floor. A player whose only pickaxe
     * snapped, with no money to replace it, cannot earn — and therefore cannot
     * recover. That is a soft-lock, and it lands on exactly the new player the
     * Phase 1 gate is about. This tool guarantees you can always get back to work.
     *
     * <p><b>Stone</b> because unbreakable-and-good would delete a money sink and a
     * source of tension outright. Stone cannot harvest gold, lapis, redstone or
     * emerald, so it runs out of usefulness around D9 and buying a real pickaxe
     * becomes the first thing worth saving for. Every pickaxe above this one is
     * breakable and much faster, which is what makes "do I take my good pick into a
     * Warren" a bet you can actually lose — without ever making the game unwinnable.
     *
     * <p>Tagged rather than merely named. A tagged item with no entry in the price
     * table is not sellable at all, so {@code /sellall} can never take somebody's
     * only pickaxe.
     */
    private static ItemStack rigPick(ItemTags tags) {
        ItemStack pick = new ItemStack(Material.STONE_PICKAXE);
        pick.editMeta(meta -> {
            meta.displayName(Component.text("Rig Pick", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    grey("Standard issue. It will never break,"),
                    grey("and it will never be good.")));
            meta.setUnbreakable(true);
        });
        return tags.stamp(pick, Onboarding.TOOL_KIND, Onboarding.RIG_PICK_ID);
    }

    /**
     * The lamp.
     *
     * <p>A soul lantern rather than a lantern because it reads cold, and cold is what
     * this world rewards. Note that it is a <em>placeable</em> block:
     * {@code FogService} cancels placement of a tagged lamp, because the first thing
     * anyone does with a lantern in a dark tunnel is put it on the wall, and a lamp
     * you can lose into a wall is a lamp you will lose into a wall.
     *
     * <p>What it does, and why it costs your main hand, is in {@link FogLamp}.
     */
    private static ItemStack fogLamp(ItemTags tags) {
        ItemStack lamp = new ItemStack(Material.SOUL_LANTERN);
        lamp.editMeta(meta -> {
            meta.displayName(Component.text("Fog Lamp", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    grey("Hold it in your main hand and the fog"),
                    grey("opens where you are looking."),
                    Component.empty(),
                    Component.text("You cannot swing a pick and hold it.",
                            NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        });
        return tags.stamp(lamp, FogLamp.KIND, FogLamp.MK1);
    }

    private static Component grey(String text) {
        return Component.text(text, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }
}
