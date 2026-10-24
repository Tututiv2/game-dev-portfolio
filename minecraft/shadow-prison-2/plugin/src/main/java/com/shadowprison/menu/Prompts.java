package com.shadowprison.menu;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * "Type a value in chat" input. The typed line never reaches public chat; the answer is delivered
 * on the MAIN thread. {@code cancel} backs out. One pending prompt per player.
 */
public final class Prompts implements Listener {

    private record Pending(Consumer<String> onAnswer, long expiresAt) {
    }

    private static final long TIMEOUT_MS = 60_000L;

    private final JavaPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public Prompts(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void ask(Player player, String question, Consumer<String> onAnswer) {
        player.closeInventory();
        pending.put(player.getUniqueId(), new Pending(onAnswer, System.currentTimeMillis() + TIMEOUT_MS));
        player.sendMessage(Ui.text("» ", Ui.PRIMARY).append(Ui.text(question, Ui.ACCENT)));
        player.sendMessage(Ui.text("  Type it in chat, or 'cancel'.", Ui.MUTED));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Pending p = pending.remove(event.getPlayer().getUniqueId());
        if (p == null) {
            return;
        }
        event.setCancelled(true);
        if (System.currentTimeMillis() > p.expiresAt()) {
            return;   // stale prompt: the line is swallowed rather than leaked to public chat
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("cancel")) {
                player.sendMessage(Ui.text("Cancelled.", Ui.MUTED));
                return;
            }
            p.onAnswer().accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
