package com.thestill.cosmetic;

import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.ui.Msg;
import com.thestill.ui.NametagService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * The chat line: who is speaking, and what they said.
 *
 * <p><b>It sets a renderer, it does not send messages.</b> Cancelling the event and
 * broadcasting the line yourself is the way every one of these gets written first, and it
 * quietly breaks everything downstream — spy commands, ignore lists, the console log and
 * per-viewer formatting all hang off the renderer, and a plugin that bypasses it takes
 * them all out.
 *
 * <p>The prefix comes from {@link NametagService#identity} rather than being rebuilt
 * here, so chat, tab and the board cannot end up describing the same player three
 * different ways — which is the failure the source hit with eight near-copies of one
 * gradient helper.
 *
 * <p><b>Priority.</b> {@link NickPrompt} claims a line at {@code LOWEST} and cancels it;
 * this runs at {@code NORMAL} with {@code ignoreCancelled}, so a message that was really
 * an answer to a prompt never reaches a renderer.
 */
public final class ChatListener implements Listener {

    private final Profiles profiles;
    private final ThemeService themes;
    private final NametagService nametags;
    private final CosmeticDials dials;

    public ChatListener(Profiles profiles, ThemeService themes, NametagService nametags,
                        CosmeticDials dials) {
        this.profiles = profiles;
        this.themes = themes;
        this.nametags = nametags;
        this.dials = dials;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());

        // The one word family this server does not host. Same check /nick runs, which is
        // the entire reason SlurFilter is static and Bukkit-free.
        if (SlurFilter.containsBlockedWord(plain)) {
            event.setCancelled(true);
            Msg.error(event.getPlayer(), "That word is not welcome here.");
            return;
        }

        if (!dials.chatFormat.asBoolean()) {
            return;
        }

        event.renderer((source, sourceDisplayName, message, audience) -> {
            Profile profile = profiles.get(source.getUniqueId());
            if (profile == null) {
                // Their profile is still loading, or has already been dropped on quit.
                // Rendering a half-line is worse than rendering a plain one.
                return sourceDisplayName.append(Component.text(": ", NamedTextColor.DARK_GRAY))
                        .append(message);
            }
            String said = PlainTextComponentSerializer.plainText().serialize(message);
            return nametags.identity(profile)
                    .append(NickRender.name(profile, source.getName(), themes))
                    .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                    // The bold preference is admin-only and is re-asked here rather than
                    // trusted off the profile — ChatDisplay.java:53 is emphatic about it,
                    // and a demoted admin whose saved flag keeps shouting is exactly the
                    // failure it names.
                    .append(NickRender.chat(profile, said, themes,
                            source.hasPermission("thestill.admin")
                                    && profile.state().get(CosmeticKeys.CHAT_BOLD)));
        });
    }
}
