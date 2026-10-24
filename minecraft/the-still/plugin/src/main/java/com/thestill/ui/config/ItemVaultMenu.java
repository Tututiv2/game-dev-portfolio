package com.thestill.ui.config;

import com.thestill.admin.AdminTarget;
import com.thestill.config.DialRegistry;
import com.thestill.command.StillCommand;
import com.thestill.item.ItemRegistry;
import com.thestill.player.Profile;
import com.thestill.ui.Menu;
import com.thestill.ui.MenuItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * THE ITEM VAULT — every custom item this plugin can build, in one place, grabbable.
 *
 * <p>Asked for in these words: <i>"config needs item vault so i can give myself fog
 * lamp, how am i supposed to test it???"</i> — and that is the whole specification.
 * The Fog Lamp existed for a session behind {@code /still lamp give}, a command you
 * had to already know about to find. A thing that can only be tested by somebody who
 * has read the source is a thing that does not get tested.
 *
 * <p><b>It is generated from {@link ItemRegistry} and cannot fall behind it.</b> Same
 * arrangement as {@code /config}'s dial pages, and the same reason: a hand-written
 * catalogue drifts, silently, and the drift is only ever discovered by somebody
 * looking for a thing that is not on the list. Adding an item is adding a block to
 * {@code StillItems}; it appears here for free.
 *
 * <h2>Two rules this page is built around</h2>
 * <ol>
 *   <li><b>A tile is a picture of the past.</b> An open inventory never re-renders,
 *       so permission is re-checked at click time and not only at open — an admin
 *       demoted while this was on screen still has a working set of buttons
 *       otherwise. Straight out of {@code AdminSetMenu}, which learned it the same
 *       way.
 *   <li><b>Nothing is silently swallowed.</b> Whatever does not fit is dropped at
 *       the taker's feet and said out loud. An item that vanishes into a full
 *       inventory is indistinguishable from an item that was never given, and this
 *       page exists precisely to answer "does that work".
 * </ol>
 *
 * <h2>Layout</h2>
 * One category per row: a name plate in the left border column, then up to seven of
 * its items. A category with more than seven spills onto the next row, marked as a
 * continuation, and four rows to a page. That reads as a shelf rather than as a
 * grid, and it means a category stays visually intact as the catalogue grows —
 * which a flat paginated list would not.
 */
public final class ItemVaultMenu extends Menu {

    /** Interior rows, and the left-column slot that names each one. */
    private static final int[] LABEL_SLOT = {9, 18, 27, 36};

    /** Items per row: columns 1..7 of the row its label sits in. */
    private static final int PER_ROW = 7;

    /** Rows of shelf per page. */
    private static final int ROWS_PER_PAGE = LABEL_SLOT.length;

    private static final int PREVIOUS = 45;
    private static final int COUNTER = 47;
    private static final int BACK = 49;
    private static final int NEXT = 53;

    /** One row of the shelf: a category, and the slice of it that fits. */
    private record Shelf(String category, boolean continued, List<ItemRegistry.CustomItem> items) {
    }

    private final DialRegistry dials;
    private final ItemVault vault;
    private final int page;

    public ItemVaultMenu(Plugin plugin, Player viewer, DialRegistry dials,
                         ItemVault vault, int page) {
        super(plugin, viewer);
        this.dials = dials;
        this.vault = vault;
        this.page = Math.max(0, page);
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text("Item vault", NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        List<Shelf> shelves = shelves();
        int pages = Math.max(1, (shelves.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        int current = Math.min(page, pages - 1);

        for (int row = 0; row < ROWS_PER_PAGE; row++) {
            int index = current * ROWS_PER_PAGE + row;
            if (index >= shelves.size()) {
                break;
            }
            drawShelf(LABEL_SLOT[row], shelves.get(index));
        }

        if (shelves.isEmpty()) {
            set(22, MenuItems.of(Material.BARRIER,
                    Component.text("Nothing registered", NamedTextColor.RED),
                    List.of(grey("The catalogue is empty, which should be"),
                            grey("impossible — StillItems seeds it at enable."))));
        }

        if (current > 0) {
            set(PREVIOUS, MenuItems.of(Material.ARROW,
                            Component.text("Previous", NamedTextColor.YELLOW)),
                    click -> openLater(new ItemVaultMenu(plugin, viewer, dials, vault,
                            current - 1)));
        }
        if (current < pages - 1) {
            set(NEXT, MenuItems.of(Material.ARROW,
                            Component.text("Next", NamedTextColor.YELLOW)),
                    click -> openLater(new ItemVaultMenu(plugin, viewer, dials, vault,
                            current + 1)));
        }

        set(COUNTER, MenuItems.of(Material.CHEST,
                Component.text("The catalogue", NamedTextColor.WHITE),
                List.of(grey(vault.items().size() + " item"
                                + (vault.items().size() == 1 ? "" : "s") + " registered"),
                        grey("page " + (current + 1) + " of " + pages),
                        Component.empty(),
                        dark("An item registers itself where it is written,"),
                        dark("so this list is never out of date."),
                        dark("Every grant is logged against your name."))));

        set(BACK, MenuItems.back(),
                click -> openLater(new ConfigMenu(plugin, viewer, dials, vault)));

        fillBorder(MenuItems.filler());
    }

    /**
     * The categories, sliced into rows.
     *
     * <p>Slicing rather than truncating: a category of nine items is two rows, not
     * seven items and a bug report. The second row says so, because a row of tiles
     * under a heading that repeats reads as a duplicate.
     */
    private List<Shelf> shelves() {
        List<Shelf> out = new ArrayList<>();
        for (Map.Entry<String, List<ItemRegistry.CustomItem>> entry
                : vault.items().byCategory().entrySet()) {
            List<ItemRegistry.CustomItem> items = entry.getValue();
            for (int from = 0; from < items.size(); from += PER_ROW) {
                out.add(new Shelf(entry.getKey(), from > 0,
                        items.subList(from, Math.min(items.size(), from + PER_ROW))));
            }
        }
        return out;
    }

    private void drawShelf(int labelSlot, Shelf shelf) {
        set(labelSlot, MenuItems.of(Material.BOOKSHELF,
                Component.text(shelf.category(), NamedTextColor.AQUA),
                List.of(grey(shelf.continued()
                        ? "continued"
                        : shelf.items().size() + " item"
                                + (shelf.items().size() == 1 ? "" : "s")))));

        int slot = labelSlot + 1;
        for (ItemRegistry.CustomItem item : shelf.items()) {
            set(slot++, tile(item), click -> give(click, item));
        }
    }

    /**
     * The real item, with a caption stapled underneath.
     *
     * <p>The tile <em>is</em> the item — same material, same name, same lore, built by
     * the same factory a player's copy comes from. That is the point of a vault: what
     * you are looking at is what you will be holding, so a lore line that renders badly
     * or a name that is the wrong colour is visible here rather than after you have
     * handed one out.
     *
     * <p>It is a fresh stack every time, so appending the caption cannot leak into
     * anybody's inventory — see {@code CustomItem.create}.
     */
    private ItemStack tile(ItemRegistry.CustomItem item) {
        ItemStack stack = item.create();
        int max = Math.max(1, stack.getMaxStackSize());

        stack.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            List<Component> existing = meta.lore();
            if (existing != null) {
                lore.addAll(existing);
            }
            lore.add(Component.empty());
            wrap(item.purpose()).forEach(lore::add);
            lore.add(Component.empty());
            lore.add(yellow("Click for one"));
            lore.add(max == 1
                    ? dark("It does not stack — shift gives one too.")
                    : yellow("Shift-click for " + max));
            lore.add(Component.empty());
            lore.add(dark(item.id() + "  ·  " + item.kind() + "/" + item.tagId()));
            meta.lore(lore.stream()
                    .map(line -> line.decoration(TextDecoration.ITALIC, false))
                    .toList());
        });
        return stack;
    }

    // ---- taking one ------------------------------------------------------

    private void give(Click click, ItemRegistry.CustomItem item) {
        if (!guard()) {
            return;
        }
        Player player = click.player();
        ItemStack stack = item.create();
        int amount = click.isShift() ? Math.max(1, stack.getMaxStackSize()) : 1;
        stack.setAmount(amount);

        // addItem returns what did not fit. Dropped rather than discarded, and said
        // out loud — the same contract /still lamp give already keeps, and for the
        // same reason: this page exists to prove a thing works, so it must never be
        // the reason a test looks like a failure.
        int dropped = 0;
        for (ItemStack left : player.getInventory().addItem(stack).values()) {
            dropped += left.getAmount();
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }

        record(player, item, amount);

        player.sendMessage(Component.text("  + ", NamedTextColor.DARK_GRAY)
                .append(Component.text(item.label() + " x" + amount, NamedTextColor.AQUA))
                .append(dropped == 0
                        ? Component.empty()
                        : Component.text("  (" + dropped + " would not fit — dropped at "
                                + "your feet)", NamedTextColor.GOLD)));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.4f);

        // The stack count on a tool tile does not change, but the catalogue counter
        // and any future "you are holding N" line do. Cheap, and it keeps the page
        // honest rather than a snapshot.
        refresh();
    }

    /**
     * Who took what.
     *
     * <p>Through {@code AdminAudit} rather than a bare log line, because the question
     * asked a week later is always "where did they get that", and the audit table is
     * indexed to answer it. Admin and target are the same person here — this page only
     * ever gives to the person looking at it — and that is exactly the row you want:
     * <i>Tutut gave Tutut a Fog Lamp</i> is a complete answer.
     */
    private void record(Player player, ItemRegistry.CustomItem item, int amount) {
        if (vault.audit() == null) {
            return;
        }
        Profile profile = vault.profiles() == null
                ? null
                : vault.profiles().get(player.getUniqueId());
        vault.audit().record(player,
                new AdminTarget(player.getUniqueId(), player.getName(), profile, true),
                "item.give", null, item.id() + " x" + amount);
    }

    /**
     * Permission, re-asked.
     *
     * <p>An open inventory is a snapshot: it was drawn once, for somebody who was an
     * admin at the time, and it keeps working afterwards whatever happens to them.
     * The check that matters is this one, at the click, not the {@code requires} on
     * the command that opened the page.
     */
    private boolean guard() {
        if (!viewer.hasPermission(StillCommand.PERMISSION)) {
            viewer.sendMessage(Component.text("  You no longer have permission for that.",
                    NamedTextColor.DARK_GRAY, TextDecoration.ITALIC));
            closeLater();
            return false;
        }
        return true;
    }

    // ---- text ------------------------------------------------------------

    /** Purpose lines, broken so a tooltip does not run off the side of the screen. */
    private static List<Component> wrap(String text) {
        List<Component> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > 46 && !line.isEmpty()) {
                out.add(grey(line.toString()));
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            out.add(grey(line.toString()));
        }
        return out;
    }

    private static Component grey(String text) {
        return Component.text(text, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static Component dark(String text) {
        return Component.text(text, NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static Component yellow(String text) {
        return Component.text(text, NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false);
    }
}
