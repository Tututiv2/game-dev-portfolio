package com.testpilot;

import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.User32;

/**
 * Makes the test window one Windows will not activate. GLFW's hints only stop
 * GLFW itself from focusing the window; they cannot stop Windows from
 * activating it, which Windows does on its own in two cases:
 *
 * <ul>
 *   <li>the user minimises or closes the foreground window - Windows activates
 *       the next top-level window in Z-order, which can be the test client;</li>
 *   <li>the user clicks into the test window.</li>
 * </ul>
 *
 * {@code WS_EX_NOACTIVATE} covers both: "the system does not bring this window
 * to the foreground when the user minimizes or closes the foreground window",
 * and a click does not activate it. {@code WS_EX_APPWINDOW} keeps it on the
 * taskbar (NOACTIVATE alone would hide it there), so a human can still find it
 * and close it. This only changes the style of our own window; it sends no
 * input and focuses nothing. Disable with TESTPILOT_NOACTIVATE=0.
 */
public final class NoActivate {

    private NoActivate() {
    }

    public static void apply(long glfwWindow) {
        String setting = TestPilot.setting("TESTPILOT_NOACTIVATE", "testpilot.noactivate", "1");
        if (setting.equals("0") || setting.equalsIgnoreCase("false")) {
            TestPilot.LOG.info("[testpilot] WS_EX_NOACTIVATE disabled by setting");
            return;
        }
        if (Platform.get() != Platform.WINDOWS) {
            return;
        }
        try {
            long hwnd = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
            if (hwnd == 0L) {
                TestPilot.LOG.warn("[testpilot] no HWND for the game window; WS_EX_NOACTIVATE not applied");
                return;
            }
            long ex = User32.GetWindowLongPtr(hwnd, User32.GWL_EXSTYLE);
            long want = ex | User32.WS_EX_NOACTIVATE | User32.WS_EX_APPWINDOW;
            User32.SetWindowLongPtr(null, hwnd, User32.GWL_EXSTYLE, want);
            // Style changes to the frame take effect after SWP_FRAMECHANGED; every
            // flag here keeps size, position, Z-order and activation untouched.
            User32.SetWindowPos(null, hwnd, 0L, 0, 0, 0, 0,
                    User32.SWP_NOMOVE | User32.SWP_NOSIZE | User32.SWP_NOZORDER
                            | User32.SWP_NOACTIVATE | User32.SWP_FRAMECHANGED);
            long now = User32.GetWindowLongPtr(hwnd, User32.GWL_EXSTYLE);
            TestPilot.LOG.info("[testpilot] exstyle 0x{} -> 0x{} (WS_EX_NOACTIVATE {})", Long.toHexString(ex),
                    Long.toHexString(now), (now & User32.WS_EX_NOACTIVATE) != 0 ? "set" : "NOT set");
        } catch (Throwable t) {
            TestPilot.LOG.warn("[testpilot] could not set WS_EX_NOACTIVATE: {}", t.toString());
        }
    }
}
