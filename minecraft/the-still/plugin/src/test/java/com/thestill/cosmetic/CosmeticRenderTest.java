package com.thestill.cosmetic;

import com.thestill.config.DialRegistry;
import com.thestill.player.Profile;
import com.thestill.role.Perks;
import com.thestill.role.RoleConfig;
import com.thestill.role.RoleService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The render half of {@code /nick} and {@code /chatcolor}, walked end to end.
 *
 * <p><b>Written after three cosmetic bugs were reported at once</b> and every one of the
 * 214 tests then in the suite passed straight over all of them — because every one of
 * them tested a palette, an id, a slot table or a marker byte, and not one of them ever
 * asked "and does a stored choice reach the screen".
 *
 * <p>The state in {@link #owner} is not invented. It is the exact row set read out of the
 * live {@code thestill.db} for the player who filed the report, and it is what makes this
 * a regression test rather than a demonstration: a chat theme switched on, a nametag
 * borrowing it, a custom name, and no nick colour at all — because picking one was the
 * thing that appeared to do nothing.
 */
class CosmeticRenderTest {

    private static final Logger LOG = Logger.getLogger("cosmetic-test");
    private static final String NAME = "staff1";

    // ---- fixtures --------------------------------------------------------

    /**
     * A theme service over the shipped roles.
     *
     * <p>Pointed at files that do not exist so both configs take the write-the-defaults
     * branch, and deliberately not at temp files they would then create — a test that
     * writes YAML into the build tree passes differently on its second run.
     */
    private static ThemeService themes(Path dir) {
        RoleConfig config = new RoleConfig(LOG, new File(dir.toFile(), "roles.yml")) {
            @Override
            public void save() {
                // The shipped defaults are the subject; persisting them is not.
            }
        };
        config.load();
        return new ThemeService(dials(dir), new RoleService(LOG, null, config));
    }

    private static CosmeticDials dials(Path dir) {
        return new CosmeticDials(new DialRegistry(LOG, new File(dir.toFile(), "dials.yml")));
    }

    /** The reporter's live profile, key for key. */
    private static Profile owner() {
        Profile profile = Profile.fresh(UUID.randomUUID(), NAME, 0L);
        profile.state().set(RoleService.key(RoleConfig.DONOR), "thestill");
        profile.state().set(CosmeticKeys.NICK_TEXT, "a dil");
        profile.state().set(CosmeticKeys.NICK_GRADIENT, true);
        profile.state().set(CosmeticKeys.CHAT_THEME, "chrome");
        profile.state().set(CosmeticKeys.CHAT_THEME_ON, true);
        profile.state().set(CosmeticKeys.TAG_THEME, "chrome");
        profile.state().set(CosmeticKeys.TAG_THEME_ON, true);
        profile.state().set(CosmeticKeys.TAG_MATCH_CHAT, true);
        return profile;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** The colours a rendered component actually uses, in order, runs collapsed. */
    private static List<TextColor> colours(Component component) {
        List<TextColor> out = new ArrayList<>();
        collect(component, out);
        return out;
    }

    private static void collect(Component component, List<TextColor> into) {
        TextColor colour = component.color();
        if (colour != null && (into.isEmpty() || !colour.equals(into.get(into.size() - 1)))) {
            into.add(colour);
        }
        component.children().forEach(child -> collect(child, into));
    }

    // ---- what the reporter was actually looking at -----------------------

    @Test
    @DisplayName("the reporter's stored state DOES render — the theme was never the bug")
    void theOwnersStateRenders(@TempDir Path dir) {
        ThemeService themes = themes(dir);
        Profile profile = owner();

        assertSame(ThemeLibrary.byId("chrome"), themes.live(profile, Target.NICKTAG),
                "the nametag borrows chat's theme; TAG_MATCH_CHAT is what makes that true");
        assertSame(ThemeLibrary.byId("chrome"), themes.live(profile, Target.CHAT));

        Component name = NickRender.name(profile, NAME, themes);
        assertEquals("a dil", plain(name), "the stored nickname is what renders");
        assertTrue(colours(name).size() > 3,
                "a theme is one colour per glyph; a single flat colour would mean the "
                        + "theme never reached the renderer");
    }

    @Test
    @DisplayName("a theme outranks a colour, so picking one while a theme is on is silent")
    void aLiveThemeSwallowsThePalette(@TempDir Path dir) {
        // THE ROOT CAUSE of "selecting nick doesn't do anything". The click writes, the
        // write persists, and the surface renders the THEME, which outranks it — see
        // NickRender's precedence note. Nothing was broken; nothing said so either.
        ThemeService themes = themes(dir);
        Profile profile = owner();

        Component before = NickRender.name(profile, NAME, themes);
        profile.state().set(CosmeticKeys.NICK_START, "#FF0000");
        profile.state().set(CosmeticKeys.NICK_END, "#FF0000");
        assertEquals(before, NickRender.name(profile, NAME, themes),
                "with a theme live, a nick colour changes nothing on screen");
        assertNotNull(NickRender.overriddenBy(profile, themes, Target.NICKTAG),
                "so the surface must be able to name what is winning");

        // With the theme off, the very same stored click is immediately visible.
        profile.state().set(CosmeticKeys.TAG_THEME_ON, false);
        assertEquals(TextColor.color(0xFF0000),
                colours(NickRender.name(profile, NAME, themes)).get(0));
        assertNull(NickRender.overriddenBy(profile, themes, Target.NICKTAG));
    }

    @Test
    @DisplayName("chat behaves the same way, and for the same reason")
    void aLiveChatThemeSwallowsTheChatColour(@TempDir Path dir) {
        ThemeService themes = themes(dir);
        Profile profile = owner();

        profile.state().set(CosmeticKeys.CHAT_COLOUR, "#00FF00");
        assertNotEquals(TextColor.color(0x00FF00),
                colours(NickRender.chat(profile, "Hello!", themes)).get(0),
                "the chat theme is live, so the static colour cannot show");
        assertNotNull(NickRender.overriddenBy(profile, themes, Target.CHAT));

        profile.state().set(CosmeticKeys.CHAT_THEME_ON, false);
        assertEquals(TextColor.color(0x00FF00),
                colours(NickRender.chat(profile, "Hello!", themes)).get(0));
    }

    // ---- the surfaces ----------------------------------------------------

    @Test
    @DisplayName("the name above a head takes the nick's colour, not the ladder's")
    void theHeadFollowsTheNick(@TempDir Path dir) {
        // "It doesn't change above my head" was true, and it was not the protocol limit
        // it looked like: the team colour was the rank's, whatever the player picked. The
        // WORDS above a head really cannot follow — that part is the protocol.
        ThemeService themes = themes(dir);
        Profile profile = owner();
        TextColor rank = TextColor.color(0x123456);

        assertNotEquals(rank, NickRender.headColour(profile, themes, rank),
                "a live theme has to reach the head as at least one colour");

        profile.state().set(CosmeticKeys.TAG_THEME_ON, false);
        profile.state().set(CosmeticKeys.NICK_START, "#FF0000");
        assertEquals(TextColor.color(0xFF0000), NickRender.headColour(profile, themes, rank));

        // Nothing picked at all falls back to whatever the caller passes — the rank
        // colour — so a player who has never opened /nick looks exactly as before.
        Profile blank = Profile.fresh(UUID.randomUUID(), NAME, 0L);
        blank.state().set(RoleService.key(RoleConfig.DONOR), "thestill");
        assertEquals(rank, NickRender.headColour(blank, themes, rank));
    }

    // ---- the gates -------------------------------------------------------

    @Test
    @DisplayName("a role with no cosmetic perks still renders the plain username")
    void theGateStillGates(@TempDir Path dir) {
        ThemeService themes = themes(dir);
        Profile profile = owner();
        profile.state().set(RoleService.key(RoleConfig.DONOR), "member");

        assertFalse(themes.has(profile, Perks.NICK),
                "Member buys nothing, and the admin bypass is inert without a server");
        assertEquals(NAME, plain(NickRender.name(profile, NAME, themes)),
                "no nick perk means the real username, not the stored one — a lapsed "
                        + "rank must revert to who you really are");
        assertEquals(NamedTextColor.WHITE,
                colours(NickRender.name(profile, NAME, themes)).get(0));
    }

    @Test
    @DisplayName("the pickers draw server-side even when the shader path is switched on")
    void thePickersNeverEmitMarkers(@TempDir Path dir) {
        // A menu you cannot read is worse than an unanimated one. ThemeMarkers encodes a
        // theme as bytes only the resource pack's shader understands; without the pack
        // they are confetti, and a chooser rendered in confetti sells nothing. Every
        // picker calls ChatTheme#render / #preview directly, and this pins that.
        CosmeticDials dials = dials(dir);
        ThemeService themes = new ThemeService(dials, null);
        ChatTheme theme = ThemeLibrary.byId("chrome");

        assertNull(dials.clientThemes.applyFromString("true"),
                "the dial has to accept the value or this test proves nothing");
        assertNotEquals(theme.preview(0f), themes.render(theme, theme.name(), true),
                "with the dial on, the SURFACE path must be the marker path");
        assertEquals(theme.render(theme.name(), 0f, true), theme.preview(0f),
                "the PICKER path must be the palette, always — it takes no dial");
    }
}
