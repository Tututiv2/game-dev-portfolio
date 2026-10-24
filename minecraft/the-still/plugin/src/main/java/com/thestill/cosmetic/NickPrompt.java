package com.thestill.cosmetic;

import com.thestill.core.Service;
import com.thestill.ui.Msg;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * "Type it in chat" — a chest menu asking for free text.
 *
 * <p>An anvil GUI would be the obvious way and it is worse: it costs an item, it eats
 * the player's cursor, and its text field silently truncates. Swallowing one chat line
 * is cheap and it works on every client. But it is a state machine that spans a thread
 * boundary, and every part of that is a trap the source hit.
 *
 * <h2>The four rules, and what each one cost over there</h2>
 *
 * <ol>
 *   <li><b>Close the menu BEFORE arming.</b> {@code closeInventory()} fires
 *       {@code InventoryCloseEvent} <em>synchronously</em>, and the source's close
 *       handler disarmed the prompt — so arming first meant the close immediately
 *       disarmed it and the player typed into the void. This class deliberately does not
 *       listen for closes at all, which removes the race rather than ordering around it;
 *       the menus still close first, because a player cannot type with a chest open.
 *   <li><b>The pending set is a {@link ConcurrentHashMap}.</b> It is written from the
 *       main thread (a click, a quit, the sweeper) and read from the async chat thread.
 *       A plain {@code HashMap} here is a data race that shows up as a prompt that
 *       occasionally does not fire.
 *   <li><b>{@link EventPriority#LOWEST}</b>, so the line is claimed before any chat
 *       formatter, filter or logger sees it. The whole point is that this message is not
 *       chat.
 *   <li><b>Hop to the main thread before doing anything.</b> The handler runs async;
 *       everything it goes on to touch — profiles, menus, the scoreboard — is main-thread
 *       only.
 * </ol>
 *
 * <h2>And the fifth, which the source did not have at all</h2>
 * <b>A timeout.</b> Over there, arming was permanent: the menu closed, you got distracted,
 * and forty minutes later the next thing you said in chat became your nickname and never
 * reached anybody. A prompt that can eat an arbitrary future message is not a prompt, it
 * is a trap — so one is armed with a deadline and a sweeper tells you when it lapses.
 */
public final class NickPrompt implements Listener, Service {

    private record Pending(long deadline, Consumer<String> onText) {
    }

    private final Plugin plugin;
    private final CosmeticDials dials;

    private final Map<UUID, Pending> waiting = new ConcurrentHashMap<>();

    private BukkitTask sweeper;

    public NickPrompt(Plugin plugin, CosmeticDials dials) {
        this.plugin = plugin;
        this.dials = dials;
    }

    @Override
    public void start() {
        // Once a second is plenty: the deadline is tens of seconds and nothing depends
        // on the expiry being prompt, only on it happening.
        sweeper = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 20L, 20L);
    }

    @Override
    public void stop() {
        if (sweeper != null) {
            sweeper.cancel();
            sweeper = null;
        }
        // Anything still armed must go, or a reload leaves listeners gone and a player
        // whose next message vanishes into a handler that no longer exists.
        waiting.clear();
    }

    /**
     * Arms the prompt for one line.
     *
     * <p>The callback runs on the main thread with the raw plain text, exactly once. The
     * word "cancel" never reaches it.
     */
    public void arm(Player player, Consumer<String> onText) {
        long deadline = System.currentTimeMillis()
                + dials.nickPromptSeconds.asInt() * 1000L;
        waiting.put(player.getUniqueId(), new Pending(deadline, onText));
        Msg.info(player, "Type it in chat — or say " + "cancel" + " to leave it alone. ("
                + dials.nickPromptSeconds.asInt() + "s)");
    }

    public void disarm(UUID playerId) {
        waiting.remove(playerId);
    }

    public boolean isArmed(UUID playerId) {
        return waiting.containsKey(playerId);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending pending = waiting.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }
        if (System.currentTimeMillis() > pending.deadline()) {
            // Lapsed between the sweeper's last pass and now. Let the line through as
            // ordinary chat rather than silently consuming it — the failure mode this
            // whole class exists to avoid is a message that goes nowhere.
            return;
        }

        // Claimed. Cancel first, so nothing downstream can broadcast it even if the
        // main-thread hop below throws.
        event.setCancelled(true);
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (raw.strip().equalsIgnoreCase("cancel")) {
                Msg.info(player, "Left it alone.");
                return;
            }
            pending.onText().accept(raw);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Pending> entry : waiting.entrySet()) {
            if (now <= entry.getValue().deadline()) {
                continue;
            }
            if (waiting.remove(entry.getKey(), entry.getValue())) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    Msg.info(player, "Stopped waiting for a name. Type /nick to try again.");
                }
            }
        }
    }
}
