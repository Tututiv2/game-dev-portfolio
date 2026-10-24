package com.auraqol.skyblock;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pickaxe ability timer: which ability, and whether it is READY, ACTIVE
 * or cooling down. Pure, no Minecraft.
 *
 * <p><b>Who is believed, in order.</b>
 * <ol>
 *   <li>Chat from the server - "You used your Mining Speed Boost Pickaxe
 *       Ability!", "Mining Speed Boost is now available!", "Your Mining Speed
 *       Boost has expired!", "This ability is on cooldown for 45s." These
 *       are the server stating facts, so they always win.</li>
 *   <li>The tab list's Pickaxe Ability widget ("Mining Speed Boost: 45s" /
 *       "Available"), re-read twice a second, which keeps the countdown
 *       honest when Core of the Mountain, Sky Mall or a cooldown perk changes
 *       the real number.</li>
 *   <li>The held tool's lore ("Cooldown: 120s", "... for 20s") - only used to
 *       start a countdown when nothing better says how long it is.</li>
 * </ol>
 * With none of those the countdown is unknown and shows as such rather than
 * as a guessed number.
 */
public final class PickaxeAbility {

    public enum Phase { NONE, READY, ACTIVE, COOLDOWN }

    /**
     * What to draw. {@code msLeft}: ACTIVE = time left if the duration is
     * known, else -1; COOLDOWN = time until ready, -1 if unknown.
     * {@code activeMs} = how long it has been running (ACTIVE only).
     */
    public record View(String name, Phase phase, long msLeft, long activeMs) {
    }

    /** Ability names the chat patterns accept even before one has been seen. */
    public static final Set<String> KNOWN = Set.of(
            "mining speed boost", "pickobulus", "maniac miner", "tunnel vision", "sheer force",
            "gemstone infusion", "anomalous desire", "vein seeker", "hazardous miner", "vanguard seeker");

    /** Abilities that happen in an instant: no ACTIVE phase. */
    private static final Set<String> INSTANT = Set.of("pickobulus");

    /** Without an "expired" message an ability is assumed over after this. */
    private static final long ACTIVE_FALLBACK_MS = 60_000;

    private static final Pattern USED = Pattern.compile(
            "^You used your (.+?) Pickaxe Ability!?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AVAILABLE = Pattern.compile(
            "^(?:Your )?(.+?) is now available!?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPIRED = Pattern.compile(
            "^Your (.+?) has expired!?$", Pattern.CASE_INSENSITIVE);
    /** "This ability is on cooldown for 45s.", "Your pickaxe ability is ...", "Your Pickobulus is ...". */
    private static final Pattern ON_COOLDOWN = Pattern.compile(
            "^(?:Your )?(.+?) is on cooldown for (\\d+(?:\\.\\d+)?)\\s*s", Pattern.CASE_INSENSITIVE);

    private static final Pattern LORE_ABILITY = Pattern.compile(
            "^(?:Pickaxe )?Ability:\\s*(.+?)(?:\\s+(?:RIGHT|LEFT) CLICK.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LORE_COOLDOWN = Pattern.compile(
            "^Cooldown:\\s*(\\d+(?:\\.\\d+)?)\\s*s", Pattern.CASE_INSENSITIVE);
    private static final Pattern LORE_DURATION = Pattern.compile(
            "\\bfor (\\d+(?:\\.\\d+)?)\\s*s\\b", Pattern.CASE_INSENSITIVE);

    /** What a tool's lore says about its ability; seconds are -1 when absent. */
    public record Lore(String name, int cooldownSec, int durationSec) {
    }

    private String name;
    private long usedAt = -1;
    private long activeUntil = -1;      // -1: duration unknown
    private boolean activeEnded = true;
    private long readyAt = -1;          // -1: unknown
    private boolean readyKnown;         // true once something told us when it is ready
    private int loreCooldownSec = -1;
    private int loreDurationSec = -1;

    // ------------------------------------------------------------ inputs

    /**
     * A chat (or action bar) line, colour-stripped or not.
     *
     * @param holdingMiningTool whether the held item is a pickaxe/drill/gauntlet;
     *                          "This ability is on cooldown" is shared with
     *                          every other item ability and is only ours then
     * @return true if the line changed anything
     */
    public synchronized boolean chat(long now, String line, boolean holdingMiningTool) {
        String c = TabWidgets.clean(line);
        if (c.isEmpty()) {
            return false;
        }
        Matcher m = USED.matcher(c);
        if (m.matches()) {
            name = m.group(1).trim();
            usedAt = now;
            boolean instant = INSTANT.contains(name.toLowerCase(Locale.ROOT));
            activeEnded = instant;
            activeUntil = !instant && loreDurationSec > 0 ? now + loreDurationSec * 1000L : -1;
            if (loreCooldownSec > 0) {
                readyAt = now + loreCooldownSec * 1000L;
                readyKnown = true;
            } else {
                readyAt = -1;
                readyKnown = false;
            }
            return true;
        }
        m = EXPIRED.matcher(c);
        if (m.matches() && isAbility(m.group(1))) {
            name = m.group(1).trim();
            activeEnded = true;
            return true;
        }
        m = AVAILABLE.matcher(c);
        if (m.matches() && isAbility(m.group(1))) {
            name = m.group(1).trim();
            readyAt = now;
            readyKnown = true;
            activeEnded = true;
            return true;
        }
        m = ON_COOLDOWN.matcher(c);
        if (m.find()) {
            String subject = m.group(1).trim();
            String low = subject.toLowerCase(Locale.ROOT);
            // A named pickaxe ability is ours wherever the line comes from;
            // the generic "This ability" line is shared with every item
            // ability, so it is ours only while a mining tool is in hand.
            boolean named = isAbility(subject);
            boolean ours = named || low.equals("pickaxe ability")
                    || (low.equals("this ability") && holdingMiningTool);
            if (ours) {
                if (named) {
                    name = subject;
                } else if (name == null) {
                    name = "Pickaxe Ability"; // a countdown with no name yet is still worth showing
                }
                readyAt = now + Math.round(Double.parseDouble(m.group(2)) * 1000);
                readyKnown = true;
                return true;
            }
        }
        return false;
    }

    /** The tab widget, when present (null = not shown, ignored). */
    public synchronized void tab(long now, TabWidgets.AbilityStatus s) {
        if (s == null || s.name() == null || s.name().isEmpty()) {
            return;
        }
        name = s.name();
        if (s.available()) {
            readyAt = now;
            readyKnown = true;
            // The widget may say Available a moment before the chat line
            // arrives; it never says so while an ability is running.
            activeEnded = activeEnded || usedAt < 0;
        } else {
            long at = now + s.secondsLeft() * 1000L;
            // Whole seconds: only resync when the widget disagrees by more
            // than its own rounding, or the countdown would jitter.
            if (!readyKnown || Math.abs(at - readyAt) > 1500) {
                readyAt = at;
            }
            readyKnown = true;
        }
    }

    /** The held tool's lore, read whenever the held item changes. */
    public synchronized void lore(Lore l) {
        if (l == null) {
            return;
        }
        if (l.cooldownSec() > 0) {
            loreCooldownSec = l.cooldownSec();
        }
        if (l.durationSec() > 0) {
            loreDurationSec = l.durationSec();
        }
        if (name == null && l.name() != null) {
            name = l.name();
        }
    }

    public synchronized void reset() {
        name = null;
        usedAt = -1;
        activeUntil = -1;
        activeEnded = true;
        readyAt = -1;
        readyKnown = false;
        loreCooldownSec = -1;
        loreDurationSec = -1;
    }

    // ------------------------------------------------------------ output

    public synchronized View view(long now) {
        if (name == null) {
            return new View(null, Phase.NONE, -1, 0);
        }
        if (usedAt >= 0 && !activeEnded) {
            boolean running = activeUntil > 0 ? now < activeUntil : now - usedAt < ACTIVE_FALLBACK_MS;
            if (running) {
                return new View(name, Phase.ACTIVE, activeUntil > 0 ? activeUntil - now : -1, now - usedAt);
            }
        }
        if (readyKnown) {
            if (now >= readyAt) {
                return new View(name, Phase.READY, 0, 0);
            }
            return new View(name, Phase.COOLDOWN, readyAt - now, 0);
        }
        if (usedAt >= 0) {
            return new View(name, Phase.COOLDOWN, -1, 0);
        }
        return new View(name, Phase.READY, 0, 0);
    }

    /** True while it is running or cooling down - the panel stays up off the mining islands then. */
    public synchronized boolean busy(long now) {
        Phase p = view(now).phase();
        return p == Phase.ACTIVE || p == Phase.COOLDOWN;
    }

    // ------------------------------------------------------------ lore

    /** Ability, cooldown and duration from a tool's lore lines; null if it has no ability. */
    public static Lore parseLore(List<String> lines) {
        if (lines == null) {
            return null;
        }
        String ability = null;
        int cd = -1;
        int dur = -1;
        for (String raw : lines) {
            String c = TabWidgets.clean(raw);
            Matcher m = LORE_ABILITY.matcher(c);
            if (ability == null && m.matches()) {
                ability = m.group(1).trim();
                continue;
            }
            m = LORE_COOLDOWN.matcher(c);
            if (m.find()) {
                cd = (int) Math.round(Double.parseDouble(m.group(1)));
                continue;
            }
            if (ability != null && cd < 0 && dur < 0) {
                m = LORE_DURATION.matcher(c);
                if (m.find()) {
                    dur = (int) Math.round(Double.parseDouble(m.group(1)));
                }
            }
        }
        if (ability == null && cd < 0) {
            return null;
        }
        return new Lore(ability, cd, dur);
    }

    private boolean isAbility(String n) {
        String l = n.trim().toLowerCase(Locale.ROOT);
        return KNOWN.contains(l) || (name != null && name.equalsIgnoreCase(n.trim()));
    }
}
