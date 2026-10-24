package com.skyclone.mod.dungeons.ext;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * EXTENSION POINT (agent B): buttons added to the Catacombs main menu ({@code /dungeons}) and actions for the
 * dungeon-hub NPC anchors the framework reserved ({@code npc.croesus}, {@code npc.essence_shop}).
 * <pre>
 *   DungeonHooks.menuButton("croesus", 33, p -&gt; Icons.of(Material.GOLD_BLOCK, "&amp;6Croesus", ...), p -&gt; croesus.open(p));
 * </pre>
 * Slots 28-34 and 37-43 of the main menu are free for hooks; a button whose slot is taken is placed in the next free one.
 */
public final class DungeonHooks {

    private DungeonHooks() {}

    public record Button(String id, int slot, Function<Player, ItemStack> icon, Consumer<Player> click) {}

    private static final List<Button> BUTTONS = new CopyOnWriteArrayList<>();

    public static void menuButton(String id, int slot, Function<Player, ItemStack> icon, Consumer<Player> click) {
        BUTTONS.removeIf(b -> b.id().equals(id));
        BUTTONS.add(new Button(id, slot, icon, click));
    }

    public static List<Button> buttons() { return List.copyOf(BUTTONS); }
}
