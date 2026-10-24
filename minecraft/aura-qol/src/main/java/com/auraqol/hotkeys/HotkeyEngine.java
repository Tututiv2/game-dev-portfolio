package com.auraqol.hotkeys;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which hotkeys fire for one key or mouse press.
 *
 * <p>It is fed PRESS events only, never key-held state, so holding a key
 * sends once rather than once per frame or per key-repeat.
 *
 * <p><b>No cooldown, on purpose.</b> The first version refused a second fire
 * within 1 s so mashing could not get you kicked for chat spam; the owner's
 * verdict was that the delay made it useless for spamming commands while the
 * inventory fills. Every press fires. Several hotkeys bound to the SAME input
 * all fire together on one press - that is how a two-message macro is made.
 */
public final class HotkeyEngine {

    private HotkeyEngine() {
    }

    /**
     * The messages to send for this press, in list order. Empty when nothing
     * is bound to it or every match is disabled or empty.
     */
    public static List<Outgoing> press(List<Hotkey> hotkeys, boolean mouse, int code, int mods) {
        List<Outgoing> out = new ArrayList<>();
        for (Hotkey h : hotkeys) {
            if (h.enabled() && h.matches(mouse, code, mods)) {
                Outgoing o = Outgoing.of(h.text());
                if (o != null) {
                    out.add(o);
                }
            }
        }
        return out;
    }
}
