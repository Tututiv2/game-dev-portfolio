package org.aincrad.plugin.editor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player undo and redo stacks, bounded by memory rather than by a step count.
 *
 * <p>A fixed "keep 15 operations" limit behaves badly at both ends: fifteen 20-million-block fills
 * would be gigabytes, while fifteen single-block pokes throw away history you obviously still want. So
 * the cap is a byte budget and steps are evicted oldest-first only when the budget is exceeded. In
 * practice that means dozens of small edits or a couple of enormous ones, which is the right shape.</p>
 *
 * <p>Redo is filled by performing an undo: applying a snapshot necessarily reads what was there, and
 * that reading <em>is</em> the redo entry. There is no separate bookkeeping to get out of step.</p>
 */
public final class EditHistory {

    public record Entry(String label, String worldName, BlockSnapshot snapshot) {
    }

    private static final long BUDGET_BYTES = 192L * 1024 * 1024;

    private final Map<UUID, Deque<Entry>> undo = new HashMap<>();
    private final Map<UUID, Deque<Entry>> redo = new HashMap<>();

    public void pushUndo(UUID player, Entry entry) {
        Deque<Entry> stack = undo.computeIfAbsent(player, k -> new ArrayDeque<>());
        stack.push(entry);
        trim(stack);
        // A fresh edit invalidates the redo branch, same as every text editor.
        redo.remove(player);
    }

    /** Used while applying an undo, so the reverse is available without extra bookkeeping. */
    public void pushRedo(UUID player, Entry entry) {
        Deque<Entry> stack = redo.computeIfAbsent(player, k -> new ArrayDeque<>());
        stack.push(entry);
        trim(stack);
    }

    /** Re-records an undo step without clearing redo - used when applying a redo. */
    public void pushUndoKeepingRedo(UUID player, Entry entry) {
        Deque<Entry> stack = undo.computeIfAbsent(player, k -> new ArrayDeque<>());
        stack.push(entry);
        trim(stack);
    }

    public Entry popUndo(UUID player) {
        Deque<Entry> stack = undo.get(player);
        return stack == null || stack.isEmpty() ? null : stack.pop();
    }

    public Entry popRedo(UUID player) {
        Deque<Entry> stack = redo.get(player);
        return stack == null || stack.isEmpty() ? null : stack.pop();
    }

    public int undoDepth(UUID player) {
        Deque<Entry> stack = undo.get(player);
        return stack == null ? 0 : stack.size();
    }

    public int redoDepth(UUID player) {
        Deque<Entry> stack = redo.get(player);
        return stack == null ? 0 : stack.size();
    }

    public void clear(UUID player) {
        undo.remove(player);
        redo.remove(player);
    }

    private static void trim(Deque<Entry> stack) {
        long total = 0;
        for (Entry e : stack) {
            total += e.snapshot().approximateBytes();
        }
        while (total > BUDGET_BYTES && stack.size() > 1) {
            Entry dropped = stack.removeLast();
            total -= dropped.snapshot().approximateBytes();
        }
    }
}
