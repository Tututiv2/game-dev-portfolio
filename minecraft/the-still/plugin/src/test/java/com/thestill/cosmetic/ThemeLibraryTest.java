package com.thestill.cosmetic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A theme id is a wire protocol, and this is what holds it still.
 *
 * <p>Ids are written onto profiles. Two libraries are edited independently by whoever is
 * adding a theme, and the failure when they collide is not a crash — it is that
 * somebody's saved choice silently starts rendering as a different theme, with nothing in
 * the log and no way to tell which players were affected.
 *
 * <p>{@link ShadowThemes} says <b>APPEND ONLY</b> at the top for the same reason, and
 * carries a permanent hole at index 34 where {@code OBSIDIAN_FLOW} was removed. Both of
 * those are claims, and a claim nothing checks is a comment.
 */
class ThemeLibraryTest {

    @Test
    @DisplayName("every id is unique across BOTH libraries")
    void idsAreUniqueEverywhere() {
        Map<String, String> seen = new HashMap<>();
        for (ChatTheme theme : ThemeLibrary.ALL) {
            String previous = seen.put(theme.id(), theme.name());
            assertEquals(null, previous, "id '" + theme.id() + "' is used by both '"
                    + previous + "' and '" + theme.name() + "' — one of them would "
                    + "silently take the other's players");
        }
        assertEquals(StillThemes.ALL.size() + ShadowThemes.ALL.size(), seen.size());
    }

    @Test
    @DisplayName("every id is typeable and storable")
    void idsAreLowercaseAndSafe() {
        // ChatTheme's constructor already enforces this, so the value here is that a
        // library that breaks it fails at class load rather than at the first player who
        // opens the menu. Asserting it again names the rule where somebody adding a theme
        // will read it.
        for (ChatTheme theme : ThemeLibrary.ALL) {
            assertTrue(theme.id().matches("[a-z0-9_.-]+"),
                    "'" + theme.id() + "' is stored on profiles and typed in commands");
            assertFalse(theme.name().isBlank(), theme.id() + " has no display name");
            assertNotNull(theme.icon(), theme.id() + " has no icon");
        }
    }

    @Test
    @DisplayName("an unknown id resolves to NONE, never to null and never to theme zero")
    void unknownIdsAreASentinel() {
        assertSame(ThemeLibrary.NONE, ThemeLibrary.byId("no_such_theme"));
        assertSame(ThemeLibrary.NONE, ThemeLibrary.byId(""));
        assertSame(ThemeLibrary.NONE, ThemeLibrary.byId(null));
        // The source returned its FIRST enum constant for an unknown id, so a profile
        // holding a renamed theme quietly repainted the player as Prismatic with nothing
        // in the log. The first entry must not be the fallback.
        assertFalse(ThemeLibrary.ALL.get(0).id().equals(ThemeLibrary.NONE.id()));

        assertSame(StillThemes.EMBER, ThemeLibrary.byId("still_ember"));
        assertSame(StillThemes.EMBER, ThemeLibrary.byId("STILL_EMBER"));
    }

    @Test
    @DisplayName("Shadow's shader indices are unique, and index 34 is still a hole")
    void shaderIndicesAreAWireProtocol() {
        Set<Integer> indices = new HashSet<>();
        for (ChatTheme theme : ShadowThemes.ALL) {
            assertTrue(indices.add(theme.index()),
                    "shader index " + theme.index() + " is used twice ('" + theme.id()
                            + "') — the index is what a resource pack decodes, so a "
                            + "duplicate is two themes that cannot be told apart");
        }
        assertFalse(indices.contains(34),
                "index 34 belonged to OBSIDIAN_FLOW, which was removed for sitting too "
                        + "close to the VOID rank's colours. The source's rule is that a "
                        + "removed index is never reused; honouring it costs nothing and "
                        + "keeps the two libraries comparable");
    }

    @Test
    @DisplayName("the two tabs partition each library — nothing is unreachable")
    void everyThemeIsOnExactlyOnePage() {
        for (ChatTheme.Source source : ChatTheme.Source.values()) {
            int colour = ThemeLibrary.countIn(source, false);
            int motion = ThemeLibrary.countIn(source, true);
            long total = ThemeLibrary.ALL.stream().filter(t -> t.source() == source).count();
            assertEquals(total, colour + motion,
                    source + " has a theme that neither tab shows, so nobody can pick it");
        }
        assertEquals(StillThemes.ALL.size(),
                ThemeLibrary.countIn(ChatTheme.Source.STILL, false)
                        + ThemeLibrary.countIn(ChatTheme.Source.STILL, true));
        assertEquals(ShadowThemes.ALL.size(),
                ThemeLibrary.countIn(ChatTheme.Source.SHADOW, false)
                        + ThemeLibrary.countIn(ChatTheme.Source.SHADOW, true));
    }

    @Test
    @DisplayName("a free theme is free and a gated one is gated")
    void unlocksResolveWithoutAServer() {
        // The house colour is deliberately given away — a colour only paying players can
        // wear stops being the house colour.
        assertTrue(StillThemes.EMBER.unlockedBy(perk -> false));
        assertFalse(StillThemes.CHOIR.unlockedBy(perk -> false));
        assertTrue(StillThemes.CHOIR.unlockedBy(perk -> true));
        assertSame(ThemeLibrary.NONE, ThemeLibrary.byId("none"),
                "the sentinel must not collide with a real theme's id");
    }
}
