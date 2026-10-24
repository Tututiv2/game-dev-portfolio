package org.aincrad.plugin.editor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Everything the editor knows about who is editing what: selections, clipboards, brushes, history, and
 * the one queue that all block writing goes through.
 *
 * <p>This is deliberately the only class that command handlers talk to, so there is a single place
 * where "do you have a selection", "is this world editable" and "how do I phrase that failure" are
 * answered. Scattering those checks is how command sets end up contradicting themselves.</p>
 */
public final class Editor {

    /** How far a click into empty air will reach when picking a corner. */
    public static final int AIR_CLICK_RANGE = 120;

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<dark_gray>[<aqua>edit</aqua>]</dark_gray> ";

    /** Marks an item as the selection rod, so any item can be one and normal axes still work as tools. */
    private final NamespacedKey wandKey;

    private final Plugin plugin;
    private final EditHistory history = new EditHistory();
    private final EditQueue queue;

    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, Clipboard> clipboards = new HashMap<>();
    private final Map<UUID, Brush> brushes = new HashMap<>();
    private final Map<UUID, Boolean> outlineOff = new HashMap<>();

    /** A configured brush: what shape, how big, which blocks. */
    public record Brush(String kind, int size, BlockPattern pattern, BlockMask mask) {
    }

    public Editor(Plugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "selection_rod");
        this.queue = new EditQueue(plugin, history);
    }

    public Plugin plugin() {
        return plugin;
    }

    public EditQueue queue() {
        return queue;
    }

    public EditHistory history() {
        return history;
    }

    public NamespacedKey wandKey() {
        return wandKey;
    }

    // ------------------------------------------------------------------ selections

    /** The player's selection in their current world, creating an empty one if needed. */
    public Selection selection(Player player) {
        Selection existing = selections.get(player.getUniqueId());
        if (existing == null || !existing.world().equals(player.getWorld())) {
            existing = new Selection(player.getWorld());
            selections.put(player.getUniqueId(), existing);
        }
        return existing;
    }

    public void setSelection(Player player, Selection selection) {
        selections.put(player.getUniqueId(), selection);
    }

    public void clearSelection(Player player) {
        selections.remove(player.getUniqueId());
    }

    /**
     * Returns the player's selection, or null after explaining what to do about it. Every command that
     * needs a selection routes through here so the advice is identical everywhere.
     */
    public Selection requireSelection(Player player) {
        Selection sel = selection(player);
        if (sel.isComplete()) {
            return sel;
        }
        if (!sel.hasCorner1() && !sel.hasCorner2()) {
            tell(player, "<red>You have not selected anything yet.");
            tell(player, "Hold the selection rod, <white>left-click</white> one corner and "
                    + "<white>right-click</white> the opposite corner.");
            tell(player, "No rod? " + runnable("//wand", "//wand") + " gives you one.");
        } else if (!sel.hasCorner2()) {
            tell(player, "<red>Only corner 1 is set.</red> Right-click the opposite corner with the rod.");
        } else {
            tell(player, "<red>Only corner 2 is set.</red> Left-click the first corner with the rod.");
        }
        return null;
    }

    public boolean outlineEnabled(Player player) {
        return !outlineOff.getOrDefault(player.getUniqueId(), false);
    }

    public boolean toggleOutline(Player player) {
        boolean nowOff = outlineEnabled(player);
        outlineOff.put(player.getUniqueId(), nowOff);
        return !nowOff;
    }

    // ------------------------------------------------------------------ clipboard and brush

    public Clipboard clipboard(Player player) {
        return clipboards.get(player.getUniqueId());
    }

    public void setClipboard(Player player, Clipboard clipboard) {
        clipboards.put(player.getUniqueId(), clipboard);
    }

    public Brush brush(Player player) {
        return brushes.get(player.getUniqueId());
    }

    public void setBrush(Player player, Brush brush) {
        if (brush == null) {
            brushes.remove(player.getUniqueId());
        } else {
            brushes.put(player.getUniqueId(), brush);
        }
    }

    // ------------------------------------------------------------------ the wand

    public ItemStack createWand() {
        ItemStack item = new ItemStack(Material.GOLDEN_AXE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MM.deserialize("<!italic><aqua>Selection Rod</aqua>"));
        meta.lore(List.of(
                MM.deserialize("<!italic><gray>Left-click a block  <white>corner 1</white>"),
                MM.deserialize("<!italic><gray>Right-click a block  <white>corner 2</white>"),
                MM.deserialize("<!italic><gray>Clicking air reaches up to " + AIR_CLICK_RANGE + " blocks"),
                MM.deserialize("<!italic><dark_gray>//help for everything you can do")));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isWand(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ chunk preloading

    /**
     * Ensures every chunk in a block range is loaded, then runs {@code action} on the main thread.
     *
     * <p>Needed by the operations that must <em>read</em> a region up front - copy, cut, stack, move -
     * because unlike writing, reading cannot be spread across ticks without the world changing
     * underneath it. Loading is asynchronous and bounded; the alternative, letting {@code getBlockAt}
     * pull chunks in synchronously, is the bug that has already killed this server twice.</p>
     */
    public void ensureChunksThen(World world, int minX, int minZ, int maxX, int maxZ,
                                 Player player, Consumer<Integer> action) {
        List<long[]> needed = new ArrayList<>();
        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    needed.add(new long[]{cx, cz});
                }
            }
        }
        if (needed.isEmpty()) {
            action.accept(0);
            return;
        }
        if (needed.size() > 4096) {
            tell(player, "<red>That area spans " + needed.size() + " chunks that are not loaded.</red>");
            tell(player, "Fly across it first, or work in smaller pieces.");
            return;
        }
        tell(player, "Loading " + plural(needed.size(), "chunk") + " first...");
        final int[] remaining = {needed.size()};
        for (long[] c : needed) {
            world.getChunkAtAsync((int) c[0], (int) c[1], true).whenComplete((chunk, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (--remaining[0] == 0) {
                            action.accept(needed.size());
                        }
                    }));
        }
    }

    // ------------------------------------------------------------------ copy

    /**
     * Reads a selection into a clipboard. Assumes the chunks are loaded - callers go through
     * {@link #ensureChunksThen}.
     */
    public Clipboard copyRegion(Selection sel, Player player) {
        int w = sel.width();
        int h = sel.height();
        int l = sel.length();
        short[] cells = new short[w * h * l];
        List<BlockData> palette = new ArrayList<>();
        Map<BlockData, Short> index = new HashMap<>();
        World world = sel.world();
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    BlockData data = world.getBlockAt(sel.minX() + x, sel.minY() + y, sel.minZ() + z)
                            .getBlockData();
                    Short id = index.get(data);
                    if (id == null) {
                        id = (short) palette.size();
                        palette.add(data);
                        index.put(data, id);
                    }
                    cells[x + w * (z + l * y)] = id;
                }
            }
        }
        int px = player.getLocation().getBlockX();
        int py = player.getLocation().getBlockY();
        int pz = player.getLocation().getBlockZ();
        return new Clipboard(w, h, l, sel.minX() - px, sel.minY() - py, sel.minZ() - pz, cells, palette);
    }

    // ------------------------------------------------------------------ undo / redo

    /** Applies a recorded snapshot, capturing the reverse so the other direction stays available. */
    public void applySnapshot(Player player, EditHistory.Entry entry, boolean isUndo) {
        World world = Bukkit.getWorld(entry.worldName());
        if (world == null) {
            tell(player, "<red>The world that edit happened in (" + entry.worldName()
                    + ") is not loaded.");
            return;
        }
        BlockSnapshot snapshot = entry.snapshot();
        BlockSnapshot reverse = new BlockSnapshot(snapshot.size());
        int n = snapshot.size();
        int changed = 0;
        for (int i = 0; i < n; i++) {
            long p = snapshot.positionAt(i);
            int x = BlockSnapshot.unpackX(p);
            int y = BlockSnapshot.unpackY(p);
            int z = BlockSnapshot.unpackZ(p);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                // Skip rather than sync-load. Reported below so it is never silent.
                continue;
            }
            BlockData target = snapshot.dataAt(i);
            var block = world.getBlockAt(x, y, z);
            BlockData current = block.getBlockData();
            if (current.matches(target)) {
                continue;
            }
            reverse.add(x, y, z, current);
            block.setBlockData(target, false);
            changed++;
        }
        if (isUndo) {
            history.pushRedo(player.getUniqueId(), new EditHistory.Entry(entry.label(), entry.worldName(), reverse));
        } else {
            history.pushUndoKeepingRedo(player.getUniqueId(),
                    new EditHistory.Entry(entry.label(), entry.worldName(), reverse));
        }
        String word = isUndo ? "Undid" : "Redid";
        tell(player, word + " <white>" + entry.label() + "</white>: restored "
                + plural(changed, "block") + ".");
        if (changed < n) {
            tell(player, "<gray>" + (n - changed) + " block(s) were skipped - either already correct, "
                    + "or in chunks that are not loaded right now.");
        }
    }

    // ------------------------------------------------------------------ messaging helpers

    public static void tell(CommandSender who, String miniMessage) {
        who.sendMessage(MM.deserialize(PREFIX + miniMessage));
    }

    /** No prefix, for the multi-line help pages where a prefix on every row is noise. */
    public static void raw(CommandSender who, String miniMessage) {
        who.sendMessage(MM.deserialize(miniMessage));
    }

    public static Component component(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    /** A clickable command that runs when clicked and can be shift-clicked to edit. */
    public static String runnable(String label, String command) {
        return "<click:suggest_command:'" + command + "'><hover:show_text:'<gray>Click to type "
                + "<white>" + command + "'><aqua>" + label + "</aqua></hover></click>";
    }

    /** A clickable command that runs immediately. */
    public static String instant(String label, String command) {
        return "<click:run_command:'" + command + "'><hover:show_text:'<gray>Click to run "
                + "<white>" + command + "'><aqua>" + label + "</aqua></hover></click>";
    }

    public static String plural(long n, String noun) {
        return format(n) + " " + noun + (n == 1 ? "" : "s");
    }

    public static String format(long n) {
        return String.format("%,d", n);
    }
}
