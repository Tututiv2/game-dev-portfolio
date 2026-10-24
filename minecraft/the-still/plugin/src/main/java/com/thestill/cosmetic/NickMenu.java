package com.thestill.cosmetic;

import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.role.Perks;
import com.thestill.state.StateKey;
import com.thestill.ui.Menu;
import com.thestill.ui.MenuItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /nick} — <b>a transcription of Shadow Prison's {@code NickMenu.fill}</b>.
 *
 * <p>Every slot, material, colour, lore line and title below is the value in
 * {@code ../Minecraft game im making/plugin/src/main/java/com/yourgame/ui/NickMenu.java}.
 * A previous pass here rebuilt this page from scratch — seven colours instead of ten,
 * rows moved so they would stop crossing the frame, tiles rearranged so the gaps closed
 * up — and the owner's answer was that it looks terrible, because what they asked for was
 * their menu and none of that is their menu. <b>If a change to this file would make the
 * page look different from the source, it is wrong.</b>
 *
 * <p>Three things are deliberately not copies, and only three:
 *
 * <ol>
 *   <li><b>Dispatch.</b> {@link Menu} routes by {@link org.bukkit.inventory.InventoryHolder}
 *       identity with a lambda per slot. The source encodes an action string into
 *       persistent data and parses it back out through a switch, which this codebase
 *       forbids. Nothing about that reaches the screen.
 *   <li><b>The ranks named in lore.</b> Wraith/Reaper/Overlord/Sovereign are that
 *       server's tiers; ours are Muffled/Hushed/Unheard/The Still, carrying the same
 *       perks in the same order.
 *   <li><b>The theme door at slot 47</b> opens this server's three-level picker rather
 *       than the source's single flat page. That is the owner's own design and it is
 *       explicitly kept — see {@link ThemeChooserMenu}.
 * </ol>
 */
public final class NickMenu extends Menu {

    private final Profiles profiles;
    private final Profile profile;
    private final NickService nicks;
    private final ThemeService themes;
    private final NickPrompt prompt;

    public NickMenu(Plugin plugin, Player viewer, Profiles profiles, Profile profile,
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
        // NickMenu.java:143 — MenuItems.CHEST_SIZE, which is six rows.
        return 6;
    }

    @Override
    protected Component title() {
        // NickMenu.java:43, verbatim.
        return Component.text("Nickname", NamedTextColor.LIGHT_PURPLE);
    }

    @Override
    protected void build() {
        // NickMenu.java:150, verbatim.
        fillBorder(MenuItems.pane(Material.GRAY_STAINED_GLASS_PANE));

        set(4, previewHead());
        set(13, gradientToggle(), click -> toggleGradient());
        set(15, namePromptButton(), click -> promptForName());

        // NickMenu.java:159-161. Ten swatches over ten slots, 19..28 — three of which are
        // on the frame. See Palette.PRIMARY_SLOTS.
        for (int i = 0; i < Palette.NICK.size() && i < Palette.PRIMARY_SLOTS.length; i++) {
            Palette.Family family = Palette.NICK.get(i);
            set(Palette.PRIMARY_SLOTS[i],
                    swatch(family, profile.state().get(CosmeticKeys.NICK_START)),
                    click -> {
                        // NickMenu.java:752 — a right-click on a main-page colour browses
                        // its five shades instead of selecting it.
                        if (click.isRight() && family.hasShades()) {
                            openLater(shades(family, CosmeticKeys.NICK_START, "start"));
                            return;
                        }
                        pickStart(family.hex());
                    });
        }

        // NickMenu.java:163 — the end row is shown from the STORED flag alone, with no
        // perk check, which is why slot 13 can read LOCKED while this row is live. That
        // is the source's behaviour and it is reproduced rather than corrected.
        if (profile.state().get(CosmeticKeys.NICK_GRADIENT)) {
            for (int i = 0; i < Palette.NICK.size() && i < Palette.END_SLOTS.length; i++) {
                Palette.Family family = Palette.NICK.get(i);
                set(Palette.END_SLOTS[i],
                        swatch(family, profile.state().get(CosmeticKeys.NICK_END)),
                        click -> {
                            if (click.isRight() && family.hasShades()) {
                                openLater(shades(family, CosmeticKeys.NICK_END, "end"));
                                return;
                            }
                            pick(CosmeticKeys.NICK_END, family.hex());
                        });
            }
            // NickMenu.java:167-170, verbatim.
            set(31, label(Material.MAGENTA_DYE,
                    Component.text("Gradient end color", NamedTextColor.LIGHT_PURPLE),
                    "Pick the second color for your nick"));
        } else {
            // NickMenu.java:172-178, verbatim — the caption first, then the whole end row
            // blacked out.
            set(31, label(Material.GRAY_DYE,
                    Component.text("Solid color", NamedTextColor.GRAY),
                    "Enable gradient to pick a second color"));
            for (int slot : Palette.END_SLOTS) {
                set(slot, MenuItems.pane(Material.BLACK_STAINED_GLASS_PANE));
            }
        }

        // NickMenu.java:181-188, verbatim but for the two lore lines, which named an NPC
        // ("The Archivist") and a place ("Skydeck") that only exist on that server.
        set(33, MenuItems.of(Material.LECTERN,
                        Component.text("Archive Shades", NamedTextColor.GOLD,
                                TextDecoration.BOLD),
                        MenuItems.lore(
                                "Exclusive gradients you cannot buy",
                                "— owned ones apply instantly")),
                click -> openLater(new ArchiveShadeMenu(plugin, viewer, this::again)));

        // NickMenu.java:190-204. `rgb-nametag` over there is the top-rank perk; here that
        // is Perks.TAG_ANIMATED, carried by The Still and by nothing else.
        boolean movingTag = themes.has(profile, Perks.TAG_ANIMATED);
        ChatTheme live = themes.live(profile, Target.NICKTAG);
        set(47, MenuItems.of(
                        movingTag ? Material.NAME_TAG : Material.GRAY_DYE,
                        movingTag
                                ? (live == ThemeLibrary.NONE
                                        ? Component.text("Moving Nametag Themes",
                                                NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                                        : live.render("Moving Nametag Themes", themes.phase(), true))
                                : Component.text("Moving Nametag Themes", NamedTextColor.DARK_GRAY,
                                        TextDecoration.BOLD),
                        MenuItems.lore(movingTag
                                        ? "Animate your floating name + tab"
                                        : "Requires The Still rank",
                                movingTag ? "Match your chat theme, or pick its own"
                                        : "(tag-animated perk)")),
                click -> {
                    if (!themes.has(profile, Perks.TAG_ANIMATED)) {
                        // Not a dead button: the source's locked tile carries the "noop"
                        // action and says nothing at all when clicked, which reads as
                        // broken rather than as locked.
                        nicks.deny(viewer, "Moving nametag themes are a The Still perk.");
                        return;
                    }
                    openLater(new ThemeChooserMenu(plugin, viewer, profiles, profile, nicks,
                            themes, prompt, Target.NICKTAG));
                });

        // NickMenu.java:205-216, verbatim.
        set(48, MenuItems.of(Material.BARRIER,
                        Component.text("Clear Nick", NamedTextColor.RED, TextDecoration.BOLD),
                        MenuItems.lore("Remove custom nickname")),
                click -> clearName());
        set(49, MenuItems.of(Material.LIME_WOOL,
                        Component.text("Done", NamedTextColor.GREEN, TextDecoration.BOLD),
                        MenuItems.lore("Close menu")),
                click -> closeLater());

        // The preview head and the theme door both draw the live theme, so they only
        // preview it if something redraws them. See ThemeService#redrawPickers.
        if (live.isMotion()) {
            themes.watchPicker(viewer, this);
        }
    }

    // ---- tiles -----------------------------------------------------------

    /** NickMenu.java:219-237. */
    private ItemStack previewHead() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Full line:", NamedTextColor.GRAY));
        lore.add(nicks.fullLine(viewer, profile));
        if (profile.state().get(CosmeticKeys.NICK_TEXT).isBlank()) {
            lore.add(Component.text("Pick a color — applies to your username",
                    NamedTextColor.DARK_GRAY));
        }
        // Said here as well as on the click, because somebody who reads the page before
        // touching it should not have to discover the precedence by being disappointed.
        ChatTheme over = NickRender.overriddenBy(profile, themes, Target.NICKTAG);
        if (over != null) {
            lore.add(Component.text("⚠ The " + over.name() + " theme is on your name.",
                    NamedTextColor.GOLD));
            lore.add(Component.text("  Colours below will not show until it is off.",
                    NamedTextColor.GOLD));
        }
        head.editMeta(SkullMeta.class, meta -> {
            meta.setOwningPlayer(viewer);
            meta.displayName(Component.text("Preview", NamedTextColor.AQUA, TextDecoration.BOLD)
                    .append(Component.text(" · ", NamedTextColor.DARK_GRAY))
                    .append(NickRender.name(profile, viewer.getName(), themes))
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return head;
    }

    /** NickMenu.java:287-305 — the main-page swatch, including its selection glint. */
    private ItemStack swatch(Palette.Family family, String selectedHex) {
        ItemStack item = MenuItems.of(family.wool(),
                // The name is WHITE and bold — not the family's own colour. The wool
                // block is what carries the colour on this page.
                Component.text(family.name(), NamedTextColor.WHITE, TextDecoration.BOLD),
                List.of(Component.text("Left-click: select", NamedTextColor.GRAY),
                        Component.text("Right-click: lighter/darker shades", NamedTextColor.AQUA),
                        Component.text(family.hex(), NamedTextColor.DARK_GRAY)));
        return family.hex().equalsIgnoreCase(selectedHex) ? MenuItems.glint(item) : item;
    }

    /**
     * NickMenu.java:240-261.
     *
     * <p>{@code nick-gradient} unlocks at Overlord over there; here it is
     * {@link Perks#NICK_GRADIENT}, which Unheard is the first tier to carry.
     */
    private ItemStack gradientToggle() {
        if (!nicks.canGradient(profile)) {
            return MenuItems.of(Material.GRAY_DYE,
                    Component.text("Gradient — LOCKED", NamedTextColor.DARK_GRAY,
                            TextDecoration.BOLD),
                    MenuItems.lore(
                            "Unlocks with the Unheard rank",
                            "Blend two colours across your name"));
        }
        boolean on = profile.state().get(CosmeticKeys.NICK_GRADIENT);
        return MenuItems.of(on ? Material.LIME_WOOL : Material.GRAY_WOOL,
                Component.text("Gradient: " + (on ? "ON" : "OFF"),
                        on ? NamedTextColor.GREEN : NamedTextColor.GRAY, TextDecoration.BOLD),
                MenuItems.lore(
                        "Click to toggle gradient nick",
                        on ? "Pick start + end colors below" : "Uses one solid wool color"));
    }

    /**
     * NickMenu.java:264-285.
     *
     * <p>Custom nickname TEXT unlocks at Reaper over there; here it is
     * {@link Perks#NICK_RENAME}, first carried by Hushed, while Muffled buys the colours.
     */
    private ItemStack namePromptButton() {
        if (!nicks.canRename(profile)) {
            return MenuItems.of(Material.GRAY_DYE,
                    Component.text("Set Nickname — LOCKED", NamedTextColor.DARK_GRAY,
                            TextDecoration.BOLD),
                    MenuItems.lore(
                            "Unlocks with the Hushed rank",
                            "Muffled can recolour your name;",
                            "Hushed+ can rename it entirely"));
        }
        return MenuItems.of(Material.NAME_TAG,
                Component.text("Set Nickname", NamedTextColor.YELLOW, TextDecoration.BOLD),
                MenuItems.lore(
                        "Click, then type in chat",
                        "Or use /nick <name>",
                        "Max " + nicks.maxNameLength() + " characters"));
    }

    /** NickMenu.java:572-579 — a caption with no action behind it. */
    private static ItemStack label(Material material, Component name, String lore) {
        return MenuItems.of(material, name,
                List.of(Component.text(lore, NamedTextColor.GRAY)));
    }

    // ---- clicks ----------------------------------------------------------

    /**
     * NickMenu.java:756-760 — picking a start colour with the gradient off drags the end
     * colour along with it, so a solid name is genuinely solid.
     */
    private void pickStart(String hex) {
        profile.state().set(CosmeticKeys.NICK_START, hex);
        if (!profile.state().get(CosmeticKeys.NICK_GRADIENT)) {
            profile.state().set(CosmeticKeys.NICK_END, hex);
        }
        after();
    }

    private void pick(StateKey<String> key, String hex) {
        profile.state().set(key, hex);
        after();
    }

    private void after() {
        // Write-behind, not durable: a colour is cheap churn and the flush task and the
        // quit handler both cover it. The name itself IS saved durably, in NickService.
        profile.markDirty();
        nicks.redraw(viewer, profile);
        // The click saved. Whether it SHOWS is a different question, and one the player
        // cannot answer by looking at this page — a live theme outranks every colour on
        // it, silently, which is exactly what "picking a colour does nothing" was.
        nicks.warnIfOverridden(viewer, profile, Target.NICKTAG);
        refresh();
    }

    private ShadeMenu shades(Palette.Family family, StateKey<String> key, String what) {
        return new ShadeMenu(plugin, viewer, family, what,
                hex -> {
                    profile.state().set(key, hex);
                    profile.markDirty();
                    nicks.redraw(viewer, profile);
                    nicks.warnIfOverridden(viewer, profile, Target.NICKTAG);
                    // NickMenu.java:769 — a colour picked on a shade page returns you to
                    // the main page, not to the shade page you picked it on.
                    openLater(again());
                },
                this::again);
    }

    /** A fresh instance. Navigation opens a new menu; it never mutates the old one. */
    private NickMenu again() {
        return new NickMenu(plugin, viewer, profiles, profile, nicks, themes, prompt);
    }

    /**
     * The perk is re-checked here even though the tile is already drawn locked.
     *
     * <p>A tile is a picture taken when the menu was built. A rank can be removed by an
     * admin while somebody is standing in the menu looking at it, and the click is the
     * only moment that matters.
     */
    private void toggleGradient() {
        if (!nicks.canGradient(profile)) {
            nicks.deny(viewer, "Gradient names unlock with the Unheard rank — see /store.");
            return;
        }
        boolean on = !profile.state().get(CosmeticKeys.NICK_GRADIENT);
        profile.state().set(CosmeticKeys.NICK_GRADIENT, on);
        if (!on) {
            // NickMenu.java:702-704 — collapse the end onto the start, so switching the
            // gradient back on does not resurrect a pair the player abandoned.
            profile.state().set(CosmeticKeys.NICK_END,
                    profile.state().get(CosmeticKeys.NICK_START));
        }
        after();
    }

    private void promptForName() {
        if (!nicks.canRename(profile)) {
            nicks.deny(viewer, "Custom nicknames unlock with the Hushed rank — see /store.");
            return;
        }
        // Close BEFORE arming. See NickPrompt — the source armed first and its own close
        // handler disarmed it a microsecond later, so the player typed into nothing.
        closeLater();
        prompt.arm(viewer, text -> {
            if (nicks.apply(viewer, profile, text)) {
                openLater(again());
            }
        });
    }

    private void clearName() {
        nicks.clear(viewer, profile);
        refresh();
    }
}
