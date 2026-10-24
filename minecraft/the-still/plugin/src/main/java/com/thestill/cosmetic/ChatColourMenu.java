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
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * {@code /chatcolor} — <b>a transcription of Shadow Prison's {@code ChatColorMenu.fillMain}</b>
 * ({@code ../Minecraft game im making/plugin/src/main/java/com/yourgame/ui/ChatColorMenu.java}).
 *
 * <p>Six rows, a <b>white</b> glass frame rather than the grey one {@code /nick} uses, the
 * ten swatches at {@link Palette#COLOR_SLOTS} — 20 to 29, which crosses the frame twice —
 * and the theme door on slot 31.
 *
 * <p>Note the two differences from {@code /nick} that are in the source and are easy to
 * mistake for slips: the palette's tenth family here is <b>grey, not black</b>, and there
 * is <b>no right-click</b> on these swatches, because {@code SHADES} is a private table
 * inside the nickname menu that this page has no access to. Both are copied.
 */
public final class ChatColourMenu extends Menu {

    private final Profiles profiles;
    private final Profile profile;
    private final NickService nicks;
    private final ThemeService themes;
    private final NickPrompt prompt;

    public ChatColourMenu(Plugin plugin, Player viewer, Profiles profiles, Profile profile,
                          NickService nicks, ThemeService themes, NickPrompt prompt) {
        super(plugin, viewer);
        this.profiles = profiles;
        this.profile = profile;
        this.nicks = nicks;
        this.themes = themes;
        this.prompt = prompt;
    }

    @Override
    protected int rows() {
        // ChatColorMenu.java:89 — MenuItems.CHEST_SIZE.
        return 6;
    }

    @Override
    protected Component title() {
        // ChatColorMenu.java:31, verbatim.
        return Component.text("Chat Color", NamedTextColor.WHITE);
    }

    @Override
    protected void build() {
        // ChatColorMenu.java:112 — WHITE panes here, grey on the nickname page.
        fillBorder(MenuItems.pane(Material.WHITE_STAINED_GLASS_PANE));

        ChatTheme live = themes.live(profile, Target.CHAT);
        boolean animated = live != ThemeLibrary.NONE;
        boolean staticColorsUnlocked = themes.has(profile, Perks.CHAT_COLOUR);
        boolean admin = viewer.hasPermission("thestill.admin");
        boolean bold = admin && profile.state().get(CosmeticKeys.CHAT_BOLD);

        // ChatColorMenu.java:123-135, verbatim — plus one line, and only when it is true.
        // No slot moves and no tile is added: the layout is a deliberate copy and this is
        // a behaviour fix. What the extra line buys is that a player whose theme is
        // covering the swatches below is told on the page rather than by clicking ten of
        // them and concluding the menu is broken.
        List<Component> headerLore = new java.util.ArrayList<>(MenuItems.lore(
                "Preview: Hello everyone!",
                animated ? "Theme: " + live.name() : "Static color: " + colourHex(),
                admin ? "Bold: " + (bold ? "ON" : "OFF") : "Bold: Admin only"));
        if (animated && staticColorsUnlocked) {
            headerLore.add(Component.text("A theme covers the colours below.",
                    NamedTextColor.GOLD));
        }
        set(4, MenuItems.of(Material.WRITABLE_BOOK,
                Component.text("Your chat messages", NamedTextColor.GOLD, TextDecoration.BOLD),
                headerLore));

        set(13, previewMessage(live, animated, bold));

        // ChatColorMenu.java:139-158 — only drawn when the player owns at least one
        // theme. Ember is free here, so in practice it is always drawn; the branch is
        // kept because that is what the source does and because a library that ever goes
        // fully paid would want it.
        if (ThemeLibrary.unlockedIn(ChatTheme.Source.STILL, perk -> themes.has(profile, perk))
                + ThemeLibrary.unlockedIn(ChatTheme.Source.SHADOW, perk -> themes.has(profile, perk))
                > 0) {
            set(31, MenuItems.of(Material.NETHER_STAR,
                            animated
                                    ? live.render("Animated RGB Themes", themes.phase(), true)
                                    : Component.text("Animated RGB Themes",
                                            NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                            MenuItems.lore(
                                    ThemeLibrary.ALL.size() + " smooth moving chat palettes",
                                    "Current: " + themes.chosen(profile, Target.CHAT).name(),
                                    "Animated chat: " + (animated ? "ON" : "OFF"),
                                    "Click to browse themes")),
                    click -> openLater(new ThemeChooserMenu(plugin, viewer, profiles, profile,
                            nicks, themes, prompt, Target.CHAT)));
        }

        // ChatColorMenu.java:160-174.
        if (staticColorsUnlocked) {
            for (int i = 0; i < Palette.CHAT.size() && i < Palette.COLOR_SLOTS.length; i++) {
                Palette.Family family = Palette.CHAT.get(i);
                set(Palette.COLOR_SLOTS[i], swatch(family),
                        click -> selectStaticColor(family.hex()));
            }
        } else {
            // The source's two lore lines named that server's VOID role and its Void
            // Pulse theme; ours name the rank that opens the same thing here.
            set(22, MenuItems.of(Material.GRAY_DYE,
                    Component.text("Static Colors Locked", NamedTextColor.GRAY,
                            TextDecoration.BOLD),
                    MenuItems.lore(
                            "Free themes are still open to you",
                            "Static colors require the Hushed rank")));
        }

        // ChatColorMenu.java:176-189 — admin-only, and deliberately drawn nowhere else.
        if (admin) {
            set(39, MenuItems.of(
                            bold ? Material.LIME_DYE : Material.GRAY_DYE,
                            Component.text("Bold Text: " + (bold ? "ON" : "OFF"),
                                    bold ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                                    TextDecoration.BOLD),
                            MenuItems.lore(
                                    "Click to toggle",
                                    "Admin-only cosmetic",
                                    "Applies to animated themes too")),
                    click -> toggleBold());
        }

        // ChatColorMenu.java:191-203, verbatim. Note "Close" carries no lore at all.
        set(40, MenuItems.of(Material.BARRIER,
                        Component.text("Reset to White", NamedTextColor.RED),
                        MenuItems.lore("Default static chat color")),
                click -> resetToWhite());

        set(49, MenuItems.of(Material.ARROW,
                        Component.text("Close", NamedTextColor.GRAY),
                        List.of()),
                click -> closeLater());

        // Two live previews on this page — the header and the paper. See
        // ThemeService#redrawPickers for why this is a registration and not a timer.
        if (live.isMotion()) {
            themes.watchPicker(viewer, this);
        }
    }

    // ---- tiles -----------------------------------------------------------

    /**
     * ChatColorMenu.java:320-348 — one line of chat on a sheet of paper.
     *
     * <p>The source hard-codes the prefix as the literal string
     * {@code "Shadow [P1] [A] You: "}, which is that server's tags. Ours is the real one,
     * from {@link com.thestill.ui.NametagService#identity} — the same call chat itself
     * makes, so the paper cannot promise a line the server will not send.
     */
    private ItemStack previewMessage(ChatTheme live, boolean animated, boolean bold) {
        return MenuItems.of(Material.PAPER,
                animated
                        ? live.render(live.name() + " Preview", themes.phase(), bold)
                        : Component.text("Preview", NamedTextColor.WHITE, TextDecoration.BOLD),
                List.of(nicks.fullLine(viewer, profile)
                        .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                        .append(NickRender.chat(profile, "Hello!", themes, bold))));
    }

    /** ChatColorMenu.java:387-409 — no right-click, and only two lore lines. */
    private ItemStack swatch(Palette.Family family) {
        ItemStack item = MenuItems.of(family.wool(),
                Component.text(family.name(), NamedTextColor.WHITE, TextDecoration.BOLD),
                List.of(Component.text("Click to select", NamedTextColor.GRAY),
                        Component.text(family.hex(), NamedTextColor.DARK_GRAY)));
        return family.hex().equalsIgnoreCase(colourHex()) ? MenuItems.glint(item) : item;
    }

    /**
     * The stored static colour, defaulted the way the source defaults it.
     *
     * <p>{@code ChatDisplay.DEFAULT_CHAT_COLOR} is {@code "#FFFFFF"} and the reset button
     * says "Reset to White", so an unset profile has to read as white here or the glint
     * lands on nothing.
     */
    private String colourHex() {
        String stored = profile.state().get(CosmeticKeys.CHAT_COLOUR);
        return stored == null || stored.isBlank() ? "#FFFFFF" : stored;
    }

    // ---- clicks ----------------------------------------------------------

    /** ChatColorMenu.java:516-526. */
    private void selectStaticColor(String hex) {
        // Re-checked at click time: the tile was drawn when the menu opened and a rank can
        // be taken away while somebody is standing in it.
        if (!themes.has(profile, Perks.CHAT_COLOUR)) {
            nicks.deny(viewer, "Coloured chat unlocks with the Hushed rank — see /store.");
            return;
        }
        profile.state().set(CosmeticKeys.CHAT_COLOUR, hex);
        profile.markDirty();
        // The write always lands; whether it shows depends on the theme above it, and a
        // silent no-op is what "selecting chatcolor doesn't do anything" was.
        nicks.warnIfOverridden(viewer, profile, Target.CHAT);
        refresh();
    }

    /** ChatColorMenu.java:94-103 — the same path {@code /chatcolor clear} takes. */
    private void resetToWhite() {
        profile.state().set(CosmeticKeys.CHAT_COLOUR, "#FFFFFF");
        profile.markDirty();
        nicks.warnIfOverridden(viewer, profile, Target.CHAT);
        refresh();
    }

    /** ChatColorMenu.java:435-447 — admin only, re-checked at click time. */
    private void toggleBold() {
        if (!viewer.hasPermission("thestill.admin")) {
            return;
        }
        profile.state().set(CosmeticKeys.CHAT_BOLD,
                !profile.state().get(CosmeticKeys.CHAT_BOLD));
        profile.markDirty();
        refresh();
    }
}
