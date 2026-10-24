package com.skyclone.core.ui;

import com.skyclone.api.ui.Text;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Public chat: "&amp;7[&lt;level&gt;] &amp;7Name&amp;f: message". The level comes from a cache the sidebar
 * fills on the main thread (this event is async). The message is flattened to plain text, so a player
 * can never inject colours, click events or hover text.
 */
final class ChatFormat implements Listener {

    private final SidebarServiceImpl sidebar;

    ChatFormat(SidebarServiceImpl sidebar) { this.sidebar = sidebar; }

    /** "&amp;7[&lt;colour&gt;12&amp;7] " */
    static String prefix(int level) {
        return "&7[" + LevelColors.of(level) + Math.max(0, level) + "&7] ";
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        boolean on = true;
        try { on = UiDials.chatFormat.get(); } catch (Throwable ignored) { }
        if (!on) return;
        Player sender = e.getPlayer();
        Integer cached = sidebar.levels.get(sender.getUniqueId());
        final int level = cached == null ? 0 : cached;
        String em = sidebar.emblems.get(sender.getUniqueId());
        final String name = sender.getName() + (em == null || em.isEmpty() ? "" : " " + em);
        e.renderer(ChatRenderer.viewerUnaware((source, displayName, message) -> {
            String plain = Text.plain(message);
            return Text.c(prefix(level) + "&7" + name + "&f: ")
                    .append(Component.text(plain, NamedTextColor.WHITE));
        }));
    }
}
