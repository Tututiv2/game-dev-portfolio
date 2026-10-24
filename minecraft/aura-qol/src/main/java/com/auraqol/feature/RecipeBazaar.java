package com.auraqol.feature;

import java.util.ArrayList;
import java.util.List;

import com.auraqol.AuraQoL;
import com.auraqol.skyblock.PriceService;
import com.auraqol.skyblock.RecipeBuy;
import com.auraqol.skyblock.SbItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Recipe -> Bazaar, the game side. {@code mixin/RecipeClickMixin} calls
 * {@link #onSlotClick} at the head of {@code AbstractContainerScreen.slotClicked};
 * a true return cancels the click so Hypixel never sees it.
 *
 * <p>Every decision is made by the pure {@link RecipeBuy} (tested). This class
 * only copies the click out of the game and, at most once per physical click,
 * sends the one command the decision names. It never sends a container click
 * of its own - the player picks the amount in Hypixel's Buy Instantly menu.
 *
 * <p>Right-click only: vanilla turns a right-click on a slot into
 * {@code slotClicked(slot, id, 1, PICKUP)} and a shift-right-click into
 * {@code (.., 1, QUICK_MOVE)}. Left-click (button 0) is passed straight through.
 */
public final class RecipeBazaar {

    private static final RecipeBuy LOGIC = new RecipeBuy();
    private static boolean loggedFailure;

    private RecipeBazaar() {
    }

    /** The click hook itself is a mixin; this only makes sure the location state it reads is running. */
    public static void register() {
        Skyblock.register();
    }

    /** @return true to cancel the click */
    public static boolean onSlotClick(AbstractContainerScreen<?> screen, Slot slot, int slotId, int button,
                                      ContainerInput input) {
        try {
            if (button != 1 || slot == null || !HudConfig.get().recipeBuy) {
                return false; // cheapest exits first: this runs on every inventory click
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.player.connection == null) {
                return false;
            }
            RecipeBuy.Click click = input == ContainerInput.PICKUP ? RecipeBuy.Click.RIGHT
                    : input == ContainerInput.QUICK_MOVE ? RecipeBuy.Click.SHIFT_RIGHT
                    : RecipeBuy.Click.OTHER;
            ItemStack stack = slot.getItem();
            if (click == RecipeBuy.Click.OTHER || stack == null || stack.isEmpty()) {
                return false;
            }
            SbItem sb = SkyblockItems.read(stack);
            RecipeBuy.Clicked c = new RecipeBuy.Clicked(
                    true,
                    Skyblock.inSkyblock(),
                    mc.hasControlDown(),
                    screen.getTitle().getString(),
                    slot.container instanceof Inventory,
                    !screen.getMenu().getCarried().isEmpty(),
                    click,
                    slotId,
                    sb == null ? null : sb.id(),
                    sb == null ? null : sb.enchantments(),
                    stack.getHoverName().getString(),
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    lore(stack));
            RecipeBuy.Decision d = LOGIC.handle(c, PriceService.data(), System.currentTimeMillis());
            switch (d.verdict()) {
                case PASS -> {
                    return false;
                }
                case SWALLOW -> {
                    return true;
                }
                default -> {
                    mc.player.connection.sendCommand(d.action().command());
                    AuraQoL.LOG.info("[auraqol] recipe-buy: /{}", d.action().command());
                    if (HudConfig.get().recipeBuyHint) {
                        MutableComponent m = Component.literal("[Aura] ").withColor(HudStyle.ACCENT);
                        m.append(Component.literal(d.hint()).withColor(0xAAAAAA));
                        mc.player.sendSystemMessage(m);
                    }
                    return true;
                }
            }
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                AuraQoL.LOG.warn("[auraqol] recipe-buy failed once (further errors silenced)", t);
            }
            return false; // a fault here must never eat a click
        }
    }

    private static List<String> lore(ItemStack stack) {
        ItemLore l = stack.get(DataComponents.LORE);
        if (l == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(l.lines().size());
        for (Component c : l.lines()) {
            out.add(c.getString());
        }
        return out;
    }
}
