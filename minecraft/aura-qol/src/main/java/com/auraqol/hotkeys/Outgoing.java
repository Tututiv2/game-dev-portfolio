package com.auraqol.hotkeys;

/**
 * What a hotkey's text becomes on the wire: a command or a chat message.
 *
 * <p>The split matters because they are sent differently - a command goes out
 * WITHOUT its slash through {@code sendCommand} (which is also where Fabric's
 * client commands like {@code /aq} are caught), while chat goes through
 * {@code sendChat}. Typing "/warp garden" into the box must run the warp, not
 * say "/warp garden" to the lobby.
 *
 * @param command true to send as a command
 * @param body    the text to send, with the leading slash already removed
 */
public record Outgoing(boolean command, String body) {

    /**
     * Vanilla's chat box limit. A server is entitled to kick for a longer
     * message, so a hotkey is held to the same limit as typing it would be.
     */
    public static final int MAX_LENGTH = 256;

    /** The message for this text, or null if there is nothing to send. */
    public static Outgoing of(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() > MAX_LENGTH) {
            t = t.substring(0, MAX_LENGTH);
        }
        if (t.startsWith("/")) {
            String body = t.substring(1).strip();
            return body.isEmpty() ? null : new Outgoing(true, body);
        }
        return new Outgoing(false, t);
    }
}
