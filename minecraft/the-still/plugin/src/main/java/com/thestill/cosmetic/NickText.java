package com.thestill.cosmetic;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * What a nickname is allowed to be.
 *
 * <p>Shadow Prison's version of this was five lines — strip {@code &} and {@code §},
 * trim, truncate to 16, reject blank — plus a slur check bolted on at the call site. It
 * had <b>no</b> charset rule, no cooldown, no impersonation guard and no uniqueness
 * check, so {@code /nick Notch}, {@code /nick [Owner]}, and a name changed forty times a
 * minute were all legal. Free text in front of every other player is the highest-risk
 * input a cosmetic system has, and it was the least guarded thing in the plugin.
 *
 * <p>Six guards now, in a fixed order, and the order is documented on {@link #check}.
 *
 * <p><b>No Bukkit in here.</b> Everything the guards need about the world arrives as a
 * {@link Rules} record of two predicates and two numbers, which is what lets the whole
 * policy be walked by a unit test rather than by a person typing names on a live server.
 */
public final class NickText {

    /**
     * The charset.
     *
     * <p>Letters, digits, underscore and the space. The space is in because a nickname
     * is display text and people want "Iron Fist"; everything else is out because a
     * name is rendered into chat, a tab list, a scoreboard and a hover, and a name
     * containing section signs, brackets, newlines or right-to-left overrides is a name
     * that can rewrite the line it appears in rather than merely appear in it.
     */
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_ ]+");

    /** Collapses any run of whitespace, so a name cannot be padded out with tabs. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Words that are the server talking, not a player.
     *
     * <p>The charset already refuses {@code [Owner]}. This refuses {@code Owner}, which
     * the charset cannot see anything wrong with and which does the same job in a chat
     * line at a glance. Impersonation does not need punctuation to work.
     */
    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "mod", "moderator", "owner", "staff", "helper",
            "console", "server", "system", "thestill", "the still", "notch");

    public static final int MIN_LENGTH = 3;

    /**
     * The structural ceiling, as distinct from the policy one.
     *
     * <p>{@link #sanitise} truncates here so that nothing downstream — a component
     * builder, a tab list, a packet — is ever handed a "name" of arbitrary length. The
     * <em>policy</em> limit is {@code cosmetic.nick-max-length} and it <b>refuses</b>
     * rather than trims, because silently shortening somebody's name hands them a
     * different name from the one they asked for, and "TheRealAdminGuy" cut to sixteen is
     * "TheRealAdmin". Two limits, doing two different jobs, and neither one covers the
     * other.
     */
    public static final int HARD_LIMIT = 64;

    private NickText() {
    }

    /** Why a name was refused. Every one of these is a different message to the player. */
    public enum Reason {
        OK,
        EMPTY,
        TOO_SHORT,
        TOO_LONG,
        ILLEGAL_CHARACTERS,
        COOLDOWN,
        SLUR,
        IMPERSONATION,
        TAKEN
    }

    /**
     * The verdict.
     *
     * @param text       the sanitised name, and the <em>only</em> thing that may be
     *                   stored — see the ordering note on {@link #check}
     * @param reason     {@link Reason#OK} or why not
     * @param waitMillis how much longer the cooldown has to run, else zero
     */
    public record Result(String text, Reason reason, long waitMillis) {

        public boolean ok() {
            return reason == Reason.OK;
        }

        /** What to tell the player. Deliberately never says which filter fired for a slur. */
        public String message() {
            return switch (reason) {
                case OK -> "";
                case EMPTY -> "A name has to have something in it.";
                case TOO_SHORT -> "That is too short — " + MIN_LENGTH + " characters at least.";
                case TOO_LONG -> "That is too long.";
                case ILLEGAL_CHARACTERS -> "Letters, numbers, underscores and spaces only.";
                case COOLDOWN -> "You changed your name recently. "
                        + Math.max(1L, waitMillis / 1000L) + "s left.";
                case SLUR -> "That word is not welcome here.";
                case IMPERSONATION -> "That name belongs to somebody else.";
                case TAKEN -> "Somebody is already wearing that name.";
            };
        }
    }

    /**
     * Everything the guards need to know about the world outside this class.
     *
     * @param maxLength        from {@code cosmetic.nick-max-length}
     * @param cooldownMillis   from {@code cosmetic.nick-cooldown-seconds}
     * @param isAnotherAccount does this name belong to a real account that is not the
     *                         person asking? Case-insensitive, and it must answer
     *                         <em>false</em> for the asker's own username, or nobody can
     *                         recolour their own name
     * @param isWornByAnother  is another player already displaying this name?
     */
    public record Rules(int maxLength, long cooldownMillis,
                        Predicate<String> isAnotherAccount,
                        Predicate<String> isWornByAnother) {
    }

    /**
     * Strip, trim, truncate.
     *
     * <p>Formatting goes first because {@code &} and {@code §} are the two ways a name
     * paints itself a colour the player did not pay for, and control characters go with
     * them because a newline in a nickname is a nickname that forges a chat line.
     *
     * <p><b>A code is two characters and both of them go.</b> The source deleted only the
     * sigil — {@code raw.replaceAll("[&§]", "")} — so {@code &c&lDeckhand} was not
     * refused and was not cleaned either; it became {@code clDeckhand}, and that is what
     * its players actually walked around wearing. Dropping the sigil alone turns a
     * formatting attempt into litter, which is the worst of the three available
     * outcomes.
     */
    public static String sanitise(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder stripped = new StringBuilder(Math.min(raw.length(), HARD_LIMIT * 2));
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '&' || c == '§') {
                i++;    // and the code character with it
                continue;
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            stripped.append(c);
        }
        String cleaned = WHITESPACE.matcher(stripped).replaceAll(" ").trim();
        if (cleaned.length() > HARD_LIMIT) {
            cleaned = cleaned.substring(0, HARD_LIMIT).trim();
        }
        return cleaned;
    }

    /**
     * Every guard, in order.
     *
     * <p><b>The ordering rule: strip, trim, truncate, and only THEN check.</b> The
     * sanitiser is where the value changes, so checking the raw input and storing the
     * sanitised one means the thing that ends up on screen was never the thing that was
     * examined. Every guard below reads {@code text}, never {@code raw}.
     *
     * <p>The one exception is documented where it happens: the slur check runs against
     * <em>both</em>, because truncation can defeat the filter's own run-collapse — a
     * stretched slur cut off at the length limit normalises to a harmless stem. Checking
     * the raw as well costs one call and closes it.
     *
     * <p>Order of the guards themselves is cheapest-and-most-common first, with two
     * deliberate placements: <b>cooldown before slur</b>, so somebody on cooldown is told
     * about the cooldown rather than handed a free probe of the word filter; and
     * <b>uniqueness last</b>, because it is the only guard that has to look at other
     * players.
     */
    public static Result check(String raw, Rules rules, long now, long lastSetAt) {
        String text = sanitise(raw);

        if (text.isEmpty()) {
            return refuse(text, Reason.EMPTY);
        }
        if (text.length() < MIN_LENGTH) {
            return refuse(text, Reason.TOO_SHORT);
        }
        // The POLICY limit refuses; the hard ceiling in sanitise() already trimmed. See
        // HARD_LIMIT for why those are two different things and why this one does not
        // quietly shorten the name instead.
        if (text.length() > rules.maxLength()) {
            return refuse(text, Reason.TOO_LONG);
        }
        if (!ALLOWED.matcher(text).matches()) {
            return refuse(text, Reason.ILLEGAL_CHARACTERS);
        }

        long elapsed = now - lastSetAt;
        if (lastSetAt > 0L && elapsed >= 0L && elapsed < rules.cooldownMillis()) {
            return new Result(text, Reason.COOLDOWN, rules.cooldownMillis() - elapsed);
        }

        if (SlurFilter.containsBlockedWord(text) || SlurFilter.containsBlockedWord(raw)) {
            return refuse(text, Reason.SLUR);
        }

        if (impersonates(text) || rules.isAnotherAccount().test(text)) {
            return refuse(text, Reason.IMPERSONATION);
        }
        if (rules.isWornByAnother().test(text)) {
            return refuse(text, Reason.TAKEN);
        }
        return new Result(text, Reason.OK, 0L);
    }

    /**
     * Does this read as staff rather than as a player?
     *
     * <p>Two shapes. A reserved word on its own, and anything wearing brackets — the
     * bracket test is redundant against the current charset <em>on purpose</em>, because
     * the charset is the rule most likely to be widened by a future edit ("let them use
     * dashes") and the guard that matters should not depend on it having stayed narrow.
     */
    static boolean impersonates(String text) {
        String folded = text.toLowerCase(Locale.ROOT).trim();
        if (RESERVED.contains(folded)) {
            return true;
        }
        return folded.indexOf('[') >= 0 || folded.indexOf(']') >= 0;
    }

    private static Result refuse(String text, Reason reason) {
        return new Result(text, reason, 0L);
    }
}
