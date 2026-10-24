package com.thestill.role;

import com.thestill.core.Service;
import com.thestill.player.Profile;
import com.thestill.state.StateKey;
import com.thestill.ui.Gradients;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Who somebody is, as far as the tag above their head is concerned.
 *
 * <p>Roles are stored one per category on the profile's {@link com.thestill.state.StateMap},
 * so this adds no columns and needs no migration — Phase 0's rule 3 doing its job.
 */
public final class RoleService implements Service {

    /**
     * Explicitly cleared, as distinct from never set.
     *
     * <p>Without this sentinel, clearing somebody's role silently re-applies the
     * category default on the next read, and an admin who removed a tag watches it come
     * back. "Absent" and "removed" are different states and the storage has to say so.
     */
    public static final String NONE = "none";

    /** The one role that renders in chrome. Scarcity is the product. */
    public static final String CHROME_ROLE = "thestill";

    private final Logger log;
    private final Plugin plugin;
    private final RoleConfig config;

    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public RoleService(Logger log, Plugin plugin, RoleConfig config) {
        this.log = log;
        this.plugin = plugin;
        this.config = config;
    }

    public RoleConfig config() {
        return config;
    }

    /** The role id somebody holds in a category. */
    public static StateKey<String> key(String category) {
        return StateKey.ofString("role." + category, "");
    }

    /** One boolean per hideable tag, so /tags covers chat, head and board at once. */
    public static StateKey<Boolean> hidden(String tag) {
        return StateKey.ofBoolean("tag.hidden." + tag, false);
    }

    // ---- resolution -----------------------------------------------------

    /**
     * The role somebody actually has in a category, defaults included.
     *
     * <p>Three outcomes and the order matters: an explicit {@link #NONE} means nothing
     * and stops the default; an explicit id that resolves <em>and belongs to this
     * category</em> wins; anything else falls through to the default.
     */
    public RoleDefinition roleIn(Profile profile, String category) {
        if (profile == null) {
            return null;
        }
        String stored = profile.state().get(key(category));
        if (NONE.equals(stored)) {
            return null;
        }
        RoleDefinition role = config.role(stored);
        if (role != null && role.category().equals(category)) {
            return role;
        }
        return config.role(config.defaultRole(category));
    }

    public List<RoleDefinition> activeRoles(Profile profile) {
        List<RoleDefinition> out = new ArrayList<>();
        for (String category : config.categories()) {
            RoleDefinition role = roleIn(profile, category);
            if (role != null) {
                out.add(role);
            }
        }
        return out;
    }

    public void assign(Profile profile, String category, String roleId) {
        profile.state().set(key(category),
                roleId == null || roleId.isBlank() ? NONE : roleId.toLowerCase());
        profile.markDirty();
    }

    // ---- capabilities ---------------------------------------------------

    /**
     * Every permission somebody's roles grant.
     *
     * <p>Weight cascades <em>within</em> a category: Admin inherits Mod inherits
     * Helper, because a staff ladder where each rung has to re-list the one below it is
     * a staff ladder that drifts.
     */
    public Set<String> permissionsOf(Profile profile) {
        Set<String> out = new HashSet<>();
        for (RoleDefinition held : activeRoles(profile)) {
            for (RoleDefinition other : config.inCategory(held.category())) {
                if (other.weight() <= held.weight()) {
                    out.addAll(other.permissions());
                }
            }
        }
        return out;
    }

    /** Does any of somebody's roles carry this perk? */
    public boolean has(Profile profile, String perk) {
        for (RoleDefinition role : activeRoles(profile)) {
            if (role.perks().contains(perk)) {
                return true;
            }
        }
        return false;
    }

    public void applyPermissions(Player player, Profile profile) {
        clearPermissions(player);
        PermissionAttachment attachment = player.addAttachment(plugin);
        for (String node : permissionsOf(profile)) {
            if (!node.endsWith("*")) {
                attachment.setPermission(node, true);
            }
        }
        attachments.put(player.getUniqueId(), attachment);
    }

    /**
     * Drops the attachment.
     *
     * <p>Called on quit, and that is not optional: an attachment outlives the player
     * object it was created for, so skipping this leaks one per join for the life of
     * the server.
     */
    public void clearPermissions(Player player) {
        PermissionAttachment existing = attachments.remove(player.getUniqueId());
        if (existing != null) {
            try {
                player.removeAttachment(existing);
            } catch (IllegalArgumentException ignored) {
                // Already gone — a reload can beat us to it. Not worth a log line.
            }
        }
    }

    @Override
    public void stop() {
        attachments.clear();
    }

    // ---- display --------------------------------------------------------

    /**
     * The tags that go before somebody's name.
     *
     * <p><b>{@code [Member]} is a placeholder and any earned tag replaces it.</b> A
     * player with a staff tag and the default tag should read {@code [Admin] Name}, not
     * {@code [Admin] [Member] Name} — the default exists so that somebody with nothing
     * still has something, and the moment they have something it has done its job.
     */
    public Component prefix(Profile profile) {
        List<RoleDefinition> tags = new ArrayList<>();
        for (RoleDefinition role : activeRoles(profile)) {
            if (role.hasTag() && !profile.state().get(hidden("role." + role.category()))) {
                tags.add(role);
            }
        }
        if (tags.size() > 1) {
            tags.removeIf(role -> RoleConfig.DEFAULT.equals(role.category()));
        }
        Component out = Component.empty();
        for (RoleDefinition role : tags) {
            out = out.append(tag(role)).append(Component.space());
        }
        return out;
    }

    /**
     * How far through the sweep the animated tags are.
     *
     * <p>One phase for the whole server rather than one per player, so every living tag
     * moves together. Two people standing next to each other with tags out of step look
     * like a bug; in step they look like a feature.
     */
    private float phase;

    /** Advanced by whoever is driving the refresh — currently the nametag ticker. */
    public void advance() {
        phase += 0.035f;
        if (phase > 1f) {
            phase -= 1f;
        }
    }

    public boolean isAnimated(RoleDefinition role) {
        return role != null && role.perks().contains(Perks.TAG_ANIMATED);
    }

    /** Does this player carry a tag that has to be redrawn every few ticks? */
    public boolean hasAnimatedTag(Profile profile) {
        for (RoleDefinition role : activeRoles(profile)) {
            if (isAnimated(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One role, rendered.
     *
     * <p>A role carrying {@link Perks#TAG_ANIMATED} sweeps rather than sitting still —
     * that is the top-tier perk and it is the one people actually buy for. It is a
     * <b>triangle</b> sweep, not a sawtooth, so there is no seam crawling back through
     * the letters at the wrap.
     */
    public Component tag(RoleDefinition role) {
        String text = role.brackets() ? "[" + role.name() + "]" : role.name();
        if (isAnimated(role)) {
            // The top tier is chrome; everything else that moves is a two-stop sweep.
            return CHROME_ROLE.equals(role.id())
                    ? Gradients.chrome(text, phase, true)
                    : Gradients.sweep(text, role.start(), role.end(), 1.5f, phase, true);
        }
        return role.gradient()
                ? Gradients.bold(text, role.start(), role.end())
                : Component.text(text, role.start()).decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Where somebody sorts in the tab list.
     *
     * <p>The best (lowest) category order among their tagged roles. <b>Hidden tags
     * still count</b> — hiding your tag is a cosmetic choice and must not demote you.
     */
    public int tabPriority(Profile profile) {
        int best = 99;
        for (RoleDefinition role : activeRoles(profile)) {
            if (role.hasTag() && !RoleConfig.DEFAULT.equals(role.category())) {
                best = Math.min(best, config.order(role.category()));
            }
        }
        return best;
    }
}
