package com.thestill.player;

import com.thestill.config.Dials;
import com.thestill.mine.SessionStats;
import com.thestill.region.RegionService;
import com.thestill.ui.sidebar.Sidebar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Loads a profile before the player exists, and saves it the moment they leave. */
public final class PlayerListener implements Listener {

    private final Logger log;
    private final Profiles profiles;
    private final RegionService regions;
    private final Dials dials;
    private final Onboarding onboarding;
    private final Sidebar sidebar;
    private final SessionStats sessions;
    private final com.thestill.ui.NametagService nametags;

    public PlayerListener(Logger log, Profiles profiles, RegionService regions, Dials dials,
                          Onboarding onboarding, Sidebar sidebar, SessionStats sessions,
                          com.thestill.ui.NametagService nametags) {
        this.log = log;
        this.profiles = profiles;
        this.regions = regions;
        this.dials = dials;
        this.onboarding = onboarding;
        this.sidebar = sidebar;
        this.sessions = sessions;
        this.nametags = nametags;
    }

    /**
     * Loads the profile off-thread, before the player is in the world.
     *
     * <p>A load failure <b>refuses the login</b>. Letting someone in with a
     * blank profile is far worse than a connection error: they would play a
     * session on top of an empty record, and the first save would overwrite
     * everything they actually own with that blank. Refusing is recoverable;
     * overwriting is not.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        try {
            profiles.loadBlocking(event.getUniqueId(), event.getName());
        } catch (Exception e) {
            log.log(Level.SEVERE, "could not load profile for " + event.getName()
                    + "; refusing login rather than risk overwriting it", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Could not load your profile. Try again in a moment.",
                            NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        profiles.beginSession(event.getPlayer().getUniqueId());
        Profile profile = profiles.get(event.getPlayer().getUniqueId());
        if (profile == null) {
            return;
        }

        sidebar.show(event.getPlayer());
        // Runs a few ticks later, after the sidebar has handed over the board the
        // teams need to live on.
        nametags.onJoin(event.getPlayer());

        if (onboarding.isNew(profile)) {
            // Runs once, ever. Handles the kit, the teleport and the three lines
            // that are the whole tutorial.
            onboarding.welcome(event.getPlayer(), profile);
            return;
        }
        if (dials.joinMessageEnabled.asBoolean()) {
            event.getPlayer().sendMessage(Component.text("The Still ", NamedTextColor.DARK_AQUA)
                    .append(Component.text("· ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Welcome back.", NamedTextColor.GRAY)));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        var playerId = event.getPlayer().getUniqueId();
        profiles.endSession(playerId);
        regions.forget(playerId);
        sidebar.forget(playerId);
        sessions.forget(playerId);
        // The online count in the tab footer just changed for everyone else.
        nametags.refreshTabHeaders();
    }
}
