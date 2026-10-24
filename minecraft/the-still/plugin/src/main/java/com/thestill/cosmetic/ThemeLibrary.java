package com.thestill.cosmetic;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The two theme lists, as one lookup.
 *
 * <p>Deliberately thin. {@link ChatTheme} already renders, already knows its own
 * unlock, and already carries which library it came from — so the only things missing
 * were "find one by the id on a profile" and "give me a page of them", and adding a
 * class that re-stated any of the rest would be adding a second place for the two
 * libraries to disagree.
 *
 * <p><b>An unknown id resolves to {@link #NONE}, never to null.</b> Ids are stored on
 * profiles and typed into commands, so a theme that is renamed, or a profile restored
 * from a backup taken before a theme existed, will absolutely arrive here as a string
 * nothing matches. Shadow Prison's equivalent returned its first enum constant for an
 * unknown id, which means a bad id silently repaints somebody as Prismatic and there is
 * nothing in the logs. A sentinel says "no theme" and the render path already knows what
 * to do with that.
 */
public final class ThemeLibrary {

    /**
     * No theme — plain text, and the state a player is in until they pick one.
     *
     * <p>It is a real {@link ChatTheme} rather than a null so that every caller can
     * render it, name it and compare against it without a branch. Its index is -1
     * because the shader indices are a wire protocol and this one is not on the wire.
     */
    public static final ChatTheme NONE = new ChatTheme("none", "No theme",
            ChatTheme.Source.STILL, ChatTheme.Motion.COLOUR, Material.LIGHT_GRAY_DYE,
            0xB0B0B0, 0xD8D8D8, 0xF0F0F0, false, -1, null);

    /** Both libraries, in menu order: the house set first, the catalogue after it. */
    public static final List<ChatTheme> ALL;

    private static final Map<String, ChatTheme> BY_ID = new HashMap<>();

    static {
        List<ChatTheme> all = new ArrayList<>(StillThemes.ALL.size() + ShadowThemes.ALL.size());
        all.addAll(StillThemes.ALL);
        all.addAll(ShadowThemes.ALL);
        ALL = List.copyOf(all);
        for (ChatTheme theme : ALL) {
            // Not putIfAbsent: a duplicate id is a bug that must be loud at class load,
            // because the two libraries are edited independently and a collision silently
            // gives one theme's players the other theme's look.
            if (BY_ID.put(theme.id(), theme) != null) {
                throw new IllegalStateException("duplicate theme id '" + theme.id()
                        + "' — a player's saved choice would resolve to the wrong theme");
            }
        }
    }

    private ThemeLibrary() {
    }

    /** {@link #NONE} for anything blank or unrecognised. Never null. */
    public static ChatTheme byId(String id) {
        if (id == null || id.isBlank()) {
            return NONE;
        }
        return BY_ID.getOrDefault(id.toLowerCase(java.util.Locale.ROOT), NONE);
    }

    /** One library, one tab — the grid a page is cut from. */
    public static List<ChatTheme> in(ChatTheme.Source source, boolean motion) {
        List<ChatTheme> out = new ArrayList<>();
        for (ChatTheme theme : ALL) {
            if (theme.source() == source && theme.isMotion() == motion) {
                out.add(theme);
            }
        }
        return out;
    }

    public static int countIn(ChatTheme.Source source, boolean motion) {
        return in(source, motion).size();
    }

    /** How many of a tab somebody can actually wear — what the chooser tile counts. */
    public static int unlockedIn(ChatTheme.Source source,
                                 java.util.function.Predicate<String> hasPerk) {
        int count = 0;
        for (ChatTheme theme : ALL) {
            if (theme.source() == source && theme.unlockedBy(hasPerk)) {
                count++;
            }
        }
        return count;
    }
}
