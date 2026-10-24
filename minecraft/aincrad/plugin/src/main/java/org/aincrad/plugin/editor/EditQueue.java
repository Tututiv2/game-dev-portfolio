package org.aincrad.plugin.editor;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.UUID;

/**
 * Applies edits a fixed number of blocks per tick, recording what was overwritten so it can be undone.
 *
 * <p><b>Why not just loop.</b> A {@code //set} over a 500x100x500 selection is 25 million blocks. Done
 * in one command handler that is a frozen server and a watchdog kill; this project has already been
 * bitten by exactly that twice (see docs/ARCHITECTURE-DECISION.md 20). So every operation is a stream
 * of writes drained under a per-tick budget, and the player gets progress messages while it runs.</p>
 *
 * <p><b>Chunk safety.</b> Writing through {@code World#getBlockAt} on an unloaded chunk loads - and if
 * necessary generates - that chunk synchronously on the main thread. Operations here therefore stream
 * their writes <em>grouped by chunk</em>, and when the next chunk is not resident the task parks itself
 * and requests it asynchronously. Nothing blocks the tick loop waiting on disk.</p>
 */
public final class EditQueue {

    /** One block write. */
    public record Write(int x, int y, int z, BlockData data) {
    }

    /**
     * A stream of writes grouped by chunk. Implementations must not jump back to a chunk they have
     * already left, or the queue will load it twice.
     */
    public interface WriteSource {
        /** Total blocks this source expects to emit, for progress reporting. May be an estimate. */
        long estimate();

        Iterator<Write> iterator();

        /**
         * Which existing blocks may be overwritten. Evaluated by the queue rather than by the source,
         * because deciding it requires reading the world and the queue is the only place that knows a
         * chunk is resident. A source that read the world itself would reintroduce synchronous chunk
         * loading through the back door.
         */
        default BlockMask mask() {
            return BlockMask.any();
        }
    }

    private static final class Task {
        final UUID player;
        final World world;
        final String label;
        final Iterator<Write> writes;
        final long estimate;
        final BlockSnapshot undo;
        final BlockMask mask;
        final boolean physics;
        final boolean recordUndo;
        final boolean announce;
        Write pending;
        long applied;
        long changed;
        long lastReportMillis;
        int currentChunkX = Integer.MIN_VALUE;
        int currentChunkZ = Integer.MIN_VALUE;
        boolean awaitingChunk;

        Task(UUID player, World world, String label, WriteSource source, boolean physics,
             boolean recordUndo, boolean announce) {
            this.player = player;
            this.world = world;
            this.label = label;
            this.estimate = source.estimate();
            this.writes = source.iterator();
            this.mask = source.mask();
            this.physics = physics;
            this.recordUndo = recordUndo;
            this.announce = announce;
            this.undo = recordUndo
                    ? new BlockSnapshot((int) Math.min(1 << 20, Math.max(64, this.estimate)))
                    : null;
        }
    }

    private final Plugin plugin;
    private final EditHistory history;
    private final Deque<Task> tasks = new ArrayDeque<>();
    private BukkitTask ticker;
    private int blocksPerTick = 12_000;
    /** Blocks actually changed since startup, for callers that batch many tasks. */
    private long totalChanged;

    public EditQueue(Plugin plugin, EditHistory history) {
        this.plugin = plugin;
        this.history = history;
    }

    public int blocksPerTick() {
        return blocksPerTick;
    }

    public void setBlocksPerTick(int n) {
        this.blocksPerTick = Math.max(200, Math.min(200_000, n));
    }

    public boolean isBusy() {
        return !tasks.isEmpty();
    }

    public long totalChanged() {
        return totalChanged;
    }

    public int queuedOperations() {
        return tasks.size();
    }

    /** Cancels everything queued. Blocks already written stay written and stay undoable. */
    public int cancelAll() {
        int n = tasks.size();
        for (Task t : tasks) {
            finish(t, true);
        }
        tasks.clear();
        return n;
    }

    /**
     * Queues an operation.
     *
     * @param label   shown to the player, e.g. "set stone"
     * @param physics whether to run block updates; false for bulk work so sand does not collapse and
     *                water does not flow mid-operation
     */
    public void submit(Player player, World world, String label, WriteSource source, boolean physics) {
        tasks.add(new Task(player.getUniqueId(), world, label, source, physics, true, true));
        start();
    }

    /**
     * Queues an operation that records no undo data and reports nothing when it finishes.
     *
     * <p>For bulk imports only. A 7,000-chunk world import is well over a hundred million blocks; an
     * undo buffer for that would run to gigabytes, and a completion message per chunk would be
     * thousands of lines of chat. Callers must tell the player up front that the work is not undoable -
     * silently dropping history would be much worse than not offering it.</p>
     */
    public void submitWithoutUndo(Player player, World world, String label, WriteSource source) {
        tasks.add(new Task(player.getUniqueId(), world, label, source, false, false, false));
        start();
    }

    /**
     * Queues one large operation that records no undo data but does report progress and completion.
     *
     * <p>The difference from {@link #submitWithoutUndo} is who is submitting. That method is for callers
     * that queue thousands of small tasks - a world import is one per chunk - where a message each would
     * be thousands of lines of chat, so the caller reports for itself. This one is for a single
     * multi-million-block operation, where the queue's own progress reporting is exactly what is wanted
     * and silence would read as the server having hung.</p>
     */
    public void submitBulk(Player player, World world, String label, WriteSource source) {
        tasks.add(new Task(player.getUniqueId(), world, label, source, false, false, true));
        start();
    }

    private void start() {
        if (ticker != null) {
            return;
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        if (tasks.isEmpty()) {
            ticker.cancel();
            ticker = null;
            return;
        }
        Task task = tasks.peek();
        int budget = blocksPerTick;

        while (budget > 0) {
            if (task.awaitingChunk) {
                return;         // waiting on an async chunk load; try again next tick
            }
            Write w = task.pending;
            if (w == null) {
                if (!task.writes.hasNext()) {
                    tasks.poll();
                    finish(task, false);
                    return;
                }
                w = task.writes.next();
            }
            int cx = w.x() >> 4;
            int cz = w.z() >> 4;
            if (cx != task.currentChunkX || cz != task.currentChunkZ) {
                if (!task.world.isChunkLoaded(cx, cz)) {
                    // Park rather than block. isChunkLoaded is a cheap in-memory check; getBlockAt
                    // on an absent chunk is not.
                    task.pending = w;
                    task.awaitingChunk = true;
                    task.world.getChunkAtAsync(cx, cz, true).whenComplete((chunk, error) ->
                            Bukkit.getScheduler().runTask(plugin, () -> task.awaitingChunk = false));
                    return;
                }
                task.currentChunkX = cx;
                task.currentChunkZ = cz;
            }
            task.pending = null;

            Block block = task.world.getBlockAt(w.x(), w.y(), w.z());
            BlockData existing = block.getBlockData();
            if (task.mask.matches(existing) && !existing.matches(w.data())) {
                if (task.recordUndo) {
                    task.undo.add(w.x(), w.y(), w.z(), existing);
                }
                block.setBlockData(w.data(), task.physics);
                task.changed++;
                totalChanged++;
            }
            task.applied++;
            budget--;
        }
        report(task);
    }

    private void report(Task task) {
        long now = System.currentTimeMillis();
        if (!task.announce || now - task.lastReportMillis < 2500 || task.estimate < 200_000) {
            return;
        }
        task.lastReportMillis = now;
        Player p = Bukkit.getPlayer(task.player);
        if (p != null) {
            int pct = task.estimate <= 0 ? 0 : (int) Math.min(99, task.applied * 100 / task.estimate);
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    task.label + "  " + pct + "%  (" + task.changed + " blocks changed)"));
        }
    }

    private void finish(Task task, boolean cancelled) {
        Player p = Bukkit.getPlayer(task.player);
        if (task.recordUndo && !task.undo.isEmpty()) {
            history.pushUndo(task.player, new EditHistory.Entry(task.label, task.world.getName(), task.undo));
        }
        if (p == null || !task.announce) {
            return;
        }
        String verb = cancelled ? "Cancelled" : "Done";
        if (task.changed == 0) {
            Editor.tell(p, verb + ": " + task.label + " changed nothing - those blocks were already correct.");
        } else {
            Editor.tell(p, verb + ": " + task.label + " changed "
                    + Editor.plural(task.changed, "block")
                    + (task.recordUndo ? ". Type //undo to put it back." : "."));
        }
    }
}
