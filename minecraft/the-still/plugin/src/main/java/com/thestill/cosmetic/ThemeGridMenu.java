package com.thestill.cosmetic;

import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.role.Perks;
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

import java.util.List;

/**
 * The theme grid — <b>a transcription of {@code ChatColorMenu.openThemes}</b>
 * ({@code ChatColorMenu.java:210-318}).
 *
 * <p>Purple frame, the library header on slot 4, twenty-seven tiles, the master toggle on
 * slot 31 with the grid parting around it, category tabs on 47 and 51, Back on 49, and
 * page arrows on 45 and 53.
 *
 * <h2>The grid really does skip 31</h2>
 * {@code THEME_SLOTS} ({@code ChatColorMenu.java:57-62}) runs {@code 28, 29, 30, 32, 33,
 * 34} — twenty-seven cells, not twenty-eight, because the "Animated Chat: ON/OFF" toggle
 * stands in the middle of the page. That hole is the layout, and closing it would put a
 * theme tile under the toggle.
 *
 * <h2>The one source bug that is not reproduced</h2>
 * {@code NickMenu.openNametagThemes} draws the same two category tabs on 47 and 51 and
 * then, from page two onwards, draws its page arrows on <b>47 and 51 as well</b>
 * ({@code NickMenu.java:557, 562}) — so the button under your cursor changes meaning
 * while you are looking at it, and the Colour/Motion tabs vanish exactly when a player
 * has enough themes to want them. {@code ChatColorMenu} gets this right with arrows on 45
 * and 53, and that is the version used here for both surfaces. It is the only layout
 * decision on this page that is not a straight copy.
 *
 * <p>Where the source has one flat page of everything, this grid is the third level of
 * the owner's own navigation — a library is chosen first, in {@link ThemeChooserMenu} —
 * so it is cut by {@link ChatTheme.Source} as well as by tab.
 */
public final class ThemeGridMenu extends Menu {

    /** {@code ChatColorMenu.java:57-62}, verbatim. Twenty-seven cells; 31 is not one. */
    private static final int[] THEME_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private static final int PREVIOUS = 45;
    private static final int COLOUR_TAB = 47;
    private static final int BACK = 49;
    private static final int MOTION_TAB = 51;
    private static final int NEXT = 53;
    private static final int TOGGLE = 31;

    private final Profiles profiles;
    private final Profile profile;
    private final NickService nicks;
    private final ThemeService themes;
    private final NickPrompt prompt;
    private final Target target;
    private final ChatTheme.Source source;
    private final boolean motion;
    private final int page;

    public ThemeGridMenu(Plugin plugin, Player viewer, Profiles profiles, Profile profile,
                         NickService nicks, ThemeService themes, NickPrompt prompt,
                         Target target, ChatTheme.Source source, boolean motion, int page) {
        super(plugin, viewer);
        this.profiles = profiles;
        this.profile = profile;
        this.nicks = nicks;
        this.themes = themes;
        this.prompt = prompt;
        this.target = target;
        this.source = source;
        this.motion = motion;
        this.page = page;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        // ChatColorMenu.java:32-33, verbatim. Which library and which tab you are in is
        // on the header tile; the title is the source's.
        return Component.text("Animated RGB Themes", NamedTextColor.LIGHT_PURPLE);
    }

    @Override
    protected void build() {
        // ChatColorMenu.java:216 — purple panes, unlike either of the pages that open it.
        fillBorder(MenuItems.pane(Material.PURPLE_STAINED_GLASS_PANE));

        List<ChatTheme> all = ThemeLibrary.in(source, motion);
        int pages = Math.max(1, (all.size() + THEME_SLOTS.length - 1) / THEME_SLOTS.length);
        int here = Math.clamp(page, 1, pages);
        int from = (here - 1) * THEME_SLOTS.length;

        ChatTheme selected = themes.chosen(profile, target);
        boolean selectedUnlocked = themes.unlocked(profile, selected);
        boolean enabled = profile.state().get(target.enabledKey()) && selectedUnlocked
                && selected != ThemeLibrary.NONE;

        // ChatColorMenu.java:224-236, verbatim but for the library line, which the source
        // does not need because it has only one library.
        set(4, MenuItems.of(Material.END_CRYSTAL,
                selected == ThemeLibrary.NONE
                        ? Component.text("Animated RGB Theme Library",
                                NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                        : selected.render("Animated RGB Theme Library", themes.phase(), true),
                MenuItems.lore(
                        source == ChatTheme.Source.STILL ? "The Still" : "The Catalogue",
                        "The line is sent once",
                        "Your resource pack animates it smoothly",
                        "Selected: " + selected.name(),
                        "Page " + here + "/" + pages)));

        for (int i = 0; i < THEME_SLOTS.length && from + i < all.size(); i++) {
            ChatTheme theme = all.get(from + i);
            set(THEME_SLOTS[i], themeButton(theme, selected), click -> selectTheme(theme));
        }

        // ChatColorMenu.java:255-267, verbatim.
        set(COLOUR_TAB, MenuItems.of(
                        motion ? Material.GRAY_DYE : Material.LIME_DYE,
                        Component.text("Colour themes",
                                motion ? NamedTextColor.GRAY : NamedTextColor.GREEN),
                        MenuItems.lore("Palettes that cycle through colours",
                                motion ? "Click to view" : "▶ Showing these")),
                click -> openLater(at(false, 1)));
        set(MOTION_TAB, MenuItems.of(
                        motion ? Material.LIME_DYE : Material.PRISMARINE_SHARD,
                        Component.text("Motion themes",
                                motion ? NamedTextColor.GREEN : NamedTextColor.AQUA),
                        MenuItems.lore(
                                ThemeLibrary.countIn(source, true) + " themes where the text MOVES",
                                "Waves, shakes, drifts, glitches",
                                motion ? "▶ Showing these" : "Click to view")),
                click -> openLater(at(true, 1)));

        // ChatColorMenu.java:268-279 — 45 and 53, the far corners.
        if (here > 1) {
            set(PREVIOUS, MenuItems.of(Material.ARROW,
                            Component.text("◀ Page " + (here - 1), NamedTextColor.YELLOW),
                            MenuItems.lore("More themes this way")),
                    click -> openLater(at(motion, here - 1)));
        }
        if (here < pages) {
            set(NEXT, MenuItems.of(Material.ARROW,
                            Component.text("Page " + (here + 1) + " ▶", NamedTextColor.YELLOW),
                            MenuItems.lore("More themes this way")),
                    click -> openLater(at(motion, here + 1)));
        }

        // ChatColorMenu.java:281-308. The word is the source's own for each surface:
        // "Animated Chat" on the chat page, "Moving Identity" on the nametag one
        // (NickMenu.java:505).
        String toggleWord = target == Target.CHAT ? "Animated Chat" : "Moving Identity";
        if (selectedUnlocked && selected != ThemeLibrary.NONE) {
            set(TOGGLE, MenuItems.of(
                            enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                            enabled
                                    ? selected.render(toggleWord + ": ON", themes.phase(), true)
                                    : Component.text(toggleWord + ": OFF", NamedTextColor.GRAY,
                                            TextDecoration.BOLD),
                            MenuItems.lore(
                                    "Current theme: " + selected.name(),
                                    "Click to turn " + (enabled ? "OFF" : "ON"))),
                    click -> toggle());
        } else {
            set(TOGGLE, MenuItems.of(Material.BARRIER,
                    Component.text("Selected Theme Locked", NamedTextColor.RED,
                            TextDecoration.BOLD),
                    MenuItems.lore(
                            unlockLine(selected),
                            "Choose a theme you own")));
        }

        // ChatColorMenu.java:310-315. Back goes to the chooser rather than straight to
        // the colour page, because there is a level in between here.
        set(BACK, MenuItems.of(Material.ARROW,
                        Component.text("Back", NamedTextColor.YELLOW),
                        MenuItems.lore("Back to the libraries")),
                click -> openLater(new ThemeChooserMenu(plugin, viewer, profiles, profile,
                        nicks, themes, prompt, target)));

        // Only the moving tab, and only when something on the page moves. A COLOUR theme
        // ignores the phase by design, so redrawing that page would be a flicker paid for
        // nothing. See ThemeService#redrawPickers.
        if (motion || selected.isMotion()) {
            themes.watchPicker(viewer, this);
        }
    }

    // ---- tiles -----------------------------------------------------------

    /**
     * ChatColorMenu.java:350-385 — three lore lines, always, in this order: what it is,
     * whether you can have it, and which collection it belongs to.
     *
     * <p>The source's third line marks VOID and Admin exclusives in their own colours.
     * Ours marks the two perks that do the same job, and everything else falls through to
     * the same grey collection line.
     */
    private ItemStack themeButton(ChatTheme theme, ChatTheme selected) {
        boolean unlocked = themes.unlocked(profile, theme);
        ItemStack item = MenuItems.of(theme.icon(),
                theme.render(theme.name(), themes.phase(), false),
                List.of(
                        Component.text(theme.isMotion()
                                        ? "It travels through the letters"
                                        : "A flat palette across the letters",
                                NamedTextColor.GRAY),
                        Component.text(unlocked ? "Click to select and enable"
                                        : unlockLine(theme),
                                unlocked ? NamedTextColor.GREEN : NamedTextColor.RED),
                        exclusivity(theme)));
        return theme.id().equals(selected.id()) ? MenuItems.glint(item) : item;
    }

    private static Component exclusivity(ChatTheme theme) {
        String unlock = theme.unlock() == null ? "" : theme.unlock();
        return switch (unlock) {
            case Perks.TAG_ANIMATED -> Component.text("✦ The Still exclusive",
                    NamedTextColor.DARK_PURPLE);
            case Perks.CHAT_ANIMATED -> Component.text("✦ Unheard exclusive",
                    NamedTextColor.DARK_PURPLE);
            default -> Component.text("Animated RGB Chat collection", NamedTextColor.DARK_GRAY);
        };
    }

    private static String unlockLine(ChatTheme theme) {
        return switch (theme.unlock() == null ? "" : theme.unlock()) {
            case Perks.TAG_ANIMATED -> "Unlocks with The Still rank";
            case Perks.CHAT_ANIMATED -> "Unlocks with the Unheard rank";
            case Perks.CHAT_COLOUR -> "Unlocks with the Hushed rank";
            case "" -> "Pick a theme first";
            default -> "Needs " + Perks.describe(theme.unlock());
        };
    }

    // ---- clicks ----------------------------------------------------------

    private ThemeGridMenu at(boolean tab, int which) {
        return new ThemeGridMenu(plugin, viewer, profiles, profile, nicks, themes, prompt,
                target, source, tab, which);
    }

    /**
     * ChatColorMenu.java:494-514 — picking a theme also switches the surface on.
     *
     * <p>Somebody who just chose a look and then has to find a second button to make it
     * happen has been handed a puzzle. Switching it off is still one click, on slot 31.
     */
    private void selectTheme(ChatTheme theme) {
        // Re-checked at click time. The tile was drawn when the menu opened and a rank can
        // be taken away while somebody is standing in it.
        if (!themes.unlocked(profile, theme)) {
            nicks.deny(viewer, unlockLine(theme) + ".");
            return;
        }
        profile.state().set(target.themeKey(), theme.id());
        profile.state().set(target.enabledKey(), true);
        if (target == Target.NICKTAG) {
            // They have just picked a theme for the name specifically, so the name is no
            // longer borrowing chat's. Leaving the follow-flag on would make this click
            // appear to do nothing.
            profile.state().set(CosmeticKeys.TAG_MATCH_CHAT, false);
        }
        profile.markDirty();
        nicks.redraw(viewer, profile);
        viewer.sendMessage(Component.text("  ", NamedTextColor.DARK_GRAY)
                .append(theme.render(theme.name(), themes.phase(), true))
                .append(Component.text(" — on " + target.where() + ".",
                        NamedTextColor.DARK_GRAY)));
        viewer.playSound(viewer.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.5f);
        refresh();
    }

    /** ChatColorMenu.java:476-492. */
    private void toggle() {
        ChatTheme selected = themes.chosen(profile, target);
        if (!themes.unlocked(profile, selected)) {
            nicks.deny(viewer, unlockLine(selected) + ".");
            return;
        }
        profile.state().set(target.enabledKey(), !profile.state().get(target.enabledKey()));
        profile.markDirty();
        nicks.redraw(viewer, profile);
        refresh();
    }
}
