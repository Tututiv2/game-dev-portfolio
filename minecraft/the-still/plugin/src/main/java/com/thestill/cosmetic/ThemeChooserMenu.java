package com.thestill.cosmetic;

import com.thestill.player.Profile;
import com.thestill.player.Profiles;
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

/**
 * Which library, and whether the theme is on at all.
 *
 * <p>A hundred and forty-six themes cannot be one list. The source put all of them behind
 * a single button and then discovered the same thing from the other end — its owner made
 * it split motion into its own tab, "not mixed into the same 76-entry list". This splits
 * on the axis that actually means something to a player: <b>the house set, or the
 * catalogue.</b> Five themes that are things in this world, or a hundred and forty-one
 * colours. Motion versus colour is the tab <em>inside</em> whichever they chose, because
 * it is a question about a theme rather than about a collection.
 *
 * <p>Serves both surfaces. Which one is the {@link Target}, and that is the only
 * difference between the two ways in.
 */
public final class ThemeChooserMenu extends Menu {

    private final Profiles profiles;
    private final Profile profile;
    private final NickService nicks;
    private final ThemeService themes;
    private final NickPrompt prompt;
    private final Target target;

    public ThemeChooserMenu(Plugin plugin, Player viewer, Profiles profiles, Profile profile,
                            NickService nicks, ThemeService themes, NickPrompt prompt,
                            Target target) {
        super(plugin, viewer);
        this.profiles = profiles;
        this.profile = profile;
        this.nicks = nicks;
        this.themes = themes;
        this.prompt = prompt;
        this.target = target;
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected Component title() {
        return Component.text("Themes · " + target.label(), NamedTextColor.WHITE);
    }

    @Override
    protected void build() {
        fillBorder(MenuItems.filler());

        ChatTheme chosen = themes.chosen(profile, target);
        ChatTheme live = themes.live(profile, target);

        List<Component> header = new ArrayList<>();
        header.add(grey("Applied to " + target.where() + "."));
        header.add(Component.empty());
        header.add(grey("Picked: " + chosen.name()));
        if (chosen != ThemeLibrary.NONE && live == ThemeLibrary.NONE) {
            // The honest version of the failure. A theme that is picked but not rendering
            // is either switched off or no longer paid for, and saying which is the
            // difference between a menu and a shrug.
            header.add(Component.text("  "
                            + (profile.state().get(target.enabledKey())
                            ? "Your rank no longer opens it."
                            : "Switched off below."),
                    NamedTextColor.GOLD));
        }
        set(4, MenuItems.of(live == ThemeLibrary.NONE ? Material.NETHER_STAR : live.icon(),
                live == ThemeLibrary.NONE
                        ? Component.text(target.label(), NamedTextColor.LIGHT_PURPLE,
                                TextDecoration.BOLD)
                        : live.preview(themes.phase()),
                header));

        set(10, library(ChatTheme.Source.STILL, Material.MAGMA_BLOCK, "The Still",
                        "Written for this world.",
                        "Ember, Rust, Fogline, Drowned, Choir."),
                click -> openLater(grid(ChatTheme.Source.STILL, false)));

        set(12, library(ChatTheme.Source.SHADOW, Material.AMETHYST_CLUSTER, "The Catalogue",
                        "Every palette the old server had.",
                        "Colours, and themes that move."),
                click -> openLater(grid(ChatTheme.Source.SHADOW, false)));

        set(14, toggle(), click -> flip());

        if (target == Target.NICKTAG) {
            set(16, matchTile(), click -> flipMatch());
        } else {
            set(16, MenuItems.of(Material.GRAY_DYE,
                            Component.text("No theme", NamedTextColor.GRAY, TextDecoration.BOLD),
                            List.of(grey("Drop the theme and go back to"),
                                    grey("a flat colour."))),
                    click -> clearTheme());
        }

        button(22, Material.ARROW, Component.text("Back", NamedTextColor.YELLOW),
                List.of(grey(target == Target.NICKTAG ? "To your name." : "To chat colours.")),
                click -> openLater(target == Target.NICKTAG
                        ? new NickMenu(plugin, viewer, profiles, profile, nicks, themes, prompt)
                        : new ChatColourMenu(plugin, viewer, profiles, profile, nicks, themes,
                                prompt)));

        // The header tile is a live preview, so it has to be redrawn to be one.
        if (live.isMotion()) {
            themes.watchPicker(viewer, this);
        }
    }

    // ---- tiles -----------------------------------------------------------

    private ItemStack library(ChatTheme.Source source, Material icon, String name,
                              String... lines) {
        int total = ThemeLibrary.countIn(source, false) + ThemeLibrary.countIn(source, true);
        int open = ThemeLibrary.unlockedIn(source, perk -> themes.has(profile, perk));
        List<Component> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add(grey(line));
        }
        lore.add(Component.empty());
        lore.add(Component.text("  " + open, NamedTextColor.GREEN)
                .append(Component.text(" of " + total + " open to you", NamedTextColor.GRAY)));
        lore.add(Component.text("  Click to browse", NamedTextColor.YELLOW));
        return MenuItems.of(icon, Component.text(name, NamedTextColor.LIGHT_PURPLE,
                TextDecoration.BOLD), lore);
    }

    private ItemStack toggle() {
        boolean on = profile.state().get(target.enabledKey());
        return MenuItems.of(on ? Material.LIME_DYE : Material.RED_DYE,
                Component.text("Theme: " + (on ? "ON" : "OFF"),
                        on ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD),
                List.of(grey(on ? "Your pick is rendering." : "Rendering flat."),
                        grey("Click to switch.")));
    }

    /**
     * One follow-flag, and only one.
     *
     * <p>The source had two — nametag follows chat, and chat follows nametag — which is a
     * cycle a player can switch both halves of. There is no useful answer to what that
     * should do, so there is one direction.
     */
    private ItemStack matchTile() {
        boolean match = profile.state().get(CosmeticKeys.TAG_MATCH_CHAT);
        return MenuItems.of(match ? Material.LIME_DYE : Material.GRAY_DYE,
                Component.text("Match chat: " + (match ? "ON" : "OFF"),
                        match ? NamedTextColor.GREEN : NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(grey(match ? "Your name wears your chat theme."
                                : "Your name carries its own."),
                        grey("One look everywhere, or two.")));
    }

    // ---- clicks ----------------------------------------------------------

    private ThemeGridMenu grid(ChatTheme.Source source, boolean motion) {
        return new ThemeGridMenu(plugin, viewer, profiles, profile, nicks, themes, prompt,
                target, source, motion, 1);
    }

    private void flip() {
        profile.state().set(target.enabledKey(), !profile.state().get(target.enabledKey()));
        profile.markDirty();
        nicks.redraw(viewer, profile);
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
        refresh();
    }

    private void flipMatch() {
        profile.state().set(CosmeticKeys.TAG_MATCH_CHAT,
                !profile.state().get(CosmeticKeys.TAG_MATCH_CHAT));
        profile.markDirty();
        nicks.redraw(viewer, profile);
        refresh();
    }

    private void clearTheme() {
        profile.state().set(target.themeKey(), "");
        profile.state().set(target.enabledKey(), false);
        profile.markDirty();
        nicks.redraw(viewer, profile);
        refresh();
    }

    private static Component grey(String text) {
        return Component.text("  " + text, NamedTextColor.DARK_GRAY);
    }
}
