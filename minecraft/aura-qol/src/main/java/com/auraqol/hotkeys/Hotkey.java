package com.auraqol.hotkeys;

/**
 * One chat hotkey: an input (a keyboard key or a mouse button, plus the
 * Ctrl/Shift/Alt held with it) and the text it sends.
 *
 * <p>Pure data - no Minecraft imports - so the rules about when it fires are
 * unit-tested without a game. {@code code} is the raw GLFW key or mouse-button
 * number, which is what the config stores: GLFW codes are stable across
 * versions, where a translated key name is not.
 *
 * @param mouse   true for a mouse button, false for a keyboard key
 * @param code    GLFW key / button, or {@link #UNBOUND}
 * @param mods    {@link #CTRL} | {@link #SHIFT} | {@link #ALT}
 * @param text    what to send; starting with "/" makes it a command
 * @param enabled a single hotkey can be parked without deleting its text
 */
public record Hotkey(boolean mouse, int code, int mods, String text, boolean enabled) {

    public static final int UNBOUND = -1;

    /** GLFW's own modifier bits, so the event's mods can be masked straight in. */
    public static final int SHIFT = 0x1;
    public static final int CTRL = 0x2;
    public static final int ALT = 0x4;
    public static final int MOD_MASK = SHIFT | CTRL | ALT;

    public Hotkey {
        text = text == null ? "" : text;
        mods &= MOD_MASK;
        if (code < 0) {
            code = UNBOUND;
        }
    }

    public static Hotkey blank() {
        return new Hotkey(false, UNBOUND, 0, "", true);
    }

    public boolean bound() {
        return code != UNBOUND;
    }

    public Hotkey withInput(boolean mouse, int code, int mods) {
        return new Hotkey(mouse, code, mods, text, enabled);
    }

    public Hotkey withText(String text) {
        return new Hotkey(mouse, code, mods, text, enabled);
    }

    public Hotkey withEnabled(boolean enabled) {
        return new Hotkey(mouse, code, mods, text, enabled);
    }

    public Hotkey unbound() {
        return new Hotkey(false, UNBOUND, 0, text, enabled);
    }

    /**
     * Exact match: the same input with exactly the same modifiers. So a plain
     * G binding stays quiet while you press Ctrl+G, and Caps/Num Lock (GLFW
     * bits 16/32) never stop a key from matching because they are masked off.
     */
    public boolean matches(boolean mouse, int code, int mods) {
        return bound() && this.mouse == mouse && this.code == code && this.mods == (mods & MOD_MASK);
    }

    /** Same input as another hotkey (for "this key is used twice" hints). */
    public boolean sameInput(Hotkey o) {
        return bound() && o.bound() && mouse == o.mouse && code == o.code && mods == o.mods;
    }
}
