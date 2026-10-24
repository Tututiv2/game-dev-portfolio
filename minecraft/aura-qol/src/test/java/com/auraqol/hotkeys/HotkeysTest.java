package com.auraqol.hotkeys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class HotkeysTest {

    private static final int G = 71;      // GLFW_KEY_G
    private static final int H = 72;      // GLFW_KEY_H
    private static final int MOUSE4 = 3;  // GLFW_MOUSE_BUTTON_4
    private static final int CAPS_LOCK_BIT = 0x10;

    private static Hotkey key(int code, int mods, String text) {
        return new Hotkey(false, code, mods, text, true);
    }

    // ------------------------------------------------------------ Outgoing

    @Test
    void slashMeansCommandAndTheSlashIsNotSent() {
        assertEquals(new Outgoing(true, "warp garden"), Outgoing.of("/warp garden"));
        assertEquals(new Outgoing(true, "warp garden"), Outgoing.of("  /warp garden  "));
    }

    @Test
    void anythingElseIsChat() {
        assertEquals(new Outgoing(false, "gg wp"), Outgoing.of("gg wp"));
        // A slash later in the text does not make it a command.
        assertEquals(new Outgoing(false, "type /p to join"), Outgoing.of("type /p to join"));
    }

    @Test
    void emptyTextAndALoneSlashSendNothing() {
        assertNull(Outgoing.of(""));
        assertNull(Outgoing.of("   "));
        assertNull(Outgoing.of("/"));
        assertNull(Outgoing.of(" / "));
        assertNull(Outgoing.of(null));
    }

    @Test
    void textIsHeldToTheChatBoxLimit() {
        String longText = "a".repeat(400);
        assertEquals(Outgoing.MAX_LENGTH, Outgoing.of(longText).body().length());
    }

    // ------------------------------------------------------------ matching

    @Test
    void modifiersMustMatchExactly() {
        Hotkey plainG = key(G, 0, "hi");
        Hotkey ctrlG = key(G, Hotkey.CTRL, "hi");
        assertTrue(plainG.matches(false, G, 0));
        assertFalse(plainG.matches(false, G, Hotkey.CTRL), "plain G must stay quiet on Ctrl+G");
        assertTrue(ctrlG.matches(false, G, Hotkey.CTRL));
        assertFalse(ctrlG.matches(false, G, 0));
        assertFalse(ctrlG.matches(false, G, Hotkey.CTRL | Hotkey.SHIFT));
    }

    @Test
    void capsLockNeverStopsAKeyMatching() {
        assertTrue(key(G, 0, "hi").matches(false, G, CAPS_LOCK_BIT));
        assertTrue(key(G, Hotkey.SHIFT, "hi").matches(false, G, Hotkey.SHIFT | CAPS_LOCK_BIT));
    }

    @Test
    void mouseAndKeyboardCodesNeverCollide() {
        // GLFW key 3 does not exist, but mouse button 3 and key code 3 must still differ.
        Hotkey mouse4 = new Hotkey(true, MOUSE4, 0, "hi", true);
        assertTrue(mouse4.matches(true, MOUSE4, 0));
        assertFalse(mouse4.matches(false, MOUSE4, 0));
    }

    @Test
    void anUnboundHotkeyMatchesNothing() {
        Hotkey h = new Hotkey(false, Hotkey.UNBOUND, 0, "hi", true);
        assertFalse(h.bound());
        assertFalse(h.matches(false, Hotkey.UNBOUND, 0));
    }

    // ------------------------------------------------------------ engine

    @Test
    void aPressSendsTheBoundText() {
        List<Hotkey> list = List.of(key(G, 0, "/is"), key(H, 0, "hello"));
        assertEquals(List.of(new Outgoing(true, "is")), HotkeyEngine.press(list, false, G, 0));
        assertEquals(List.of(new Outgoing(false, "hello")), HotkeyEngine.press(list, false, H, 0));
        assertEquals(List.of(), HotkeyEngine.press(list, false, 90, 0), "unbound key sends nothing");
    }

    @Test
    void mashingTheKeyFiresEveryPress() {
        // The owner spams commands while the inventory fills: no cooldown.
        List<Hotkey> list = List.of(key(G, 0, "/sell"));
        for (int i = 0; i < 20; i++) {
            assertEquals(List.of(new Outgoing(true, "sell")), HotkeyEngine.press(list, false, G, 0));
        }
    }

    @Test
    void modifiersMakeSeparateInputs() {
        List<Hotkey> list = List.of(key(G, 0, "a"), key(H, 0, "b"), key(G, Hotkey.CTRL, "c"));
        assertEquals(List.of(new Outgoing(false, "a")), HotkeyEngine.press(list, false, G, 0));
        assertEquals(List.of(new Outgoing(false, "c")), HotkeyEngine.press(list, false, G, Hotkey.CTRL),
                "Ctrl+G is its own input");
    }

    @Test
    void twoHotkeysOnOneKeyFireTogetherAsAMacro() {
        List<Hotkey> list = List.of(key(G, 0, "/p warp"), key(G, 0, "warping!"));
        assertEquals(List.of(new Outgoing(true, "p warp"), new Outgoing(false, "warping!")),
                HotkeyEngine.press(list, false, G, 0));
    }

    @Test
    void disabledOrEmptyHotkeysDoNotFire() {
        List<Hotkey> off = List.of(key(G, 0, "hi").withEnabled(false), key(H, 0, "   "));
        assertEquals(0, HotkeyEngine.press(off, false, G, 0).size());
        assertEquals(0, HotkeyEngine.press(off, false, H, 0).size());
    }

    // ------------------------------------------------------------ codec

    @Test
    void roundTripKeepsEverything() {
        List<Hotkey> list = List.of(
                key(G, Hotkey.CTRL | Hotkey.SHIFT, "/warp garden"),
                new Hotkey(true, MOUSE4, 0, "gg \"quotes\" & <html>", false),
                Hotkey.blank());
        assertEquals(list, HotkeyCodec.parse(HotkeyCodec.write(list)));
    }

    @Test
    void aBrokenFileLosesOnlyWhatIsBroken() {
        String json = "{\"hotkeys\":[{\"code\":71,\"text\":\"ok\"},{\"code\":\"not a number\"},42,"
                + "{\"code\":72,\"text\":\"also ok\",\"futureField\":true}]}";
        List<Hotkey> got = HotkeyCodec.parse(json);
        assertEquals(2, got.size());
        assertEquals("ok", got.get(0).text());
        assertTrue(got.get(0).enabled(), "missing 'enabled' means on");
        assertEquals("also ok", got.get(1).text());
    }

    @Test
    void garbageAndEmptyGiveNoHotkeysRatherThanThrowing() {
        assertEquals(List.of(), HotkeyCodec.parse(""));
        assertEquals(List.of(), HotkeyCodec.parse(null));
        assertEquals(List.of(), HotkeyCodec.parse("{not json"));
        assertEquals(List.of(), HotkeyCodec.parse("{\"something\":1}"));
    }

    @Test
    void aCorruptFileCannotFloodTheList() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 500; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"code\":71,\"text\":\"x\"}");
        }
        sb.append("]");
        assertEquals(HotkeyCodec.MAX_HOTKEYS, HotkeyCodec.parse(sb.toString()).size());
    }
}
