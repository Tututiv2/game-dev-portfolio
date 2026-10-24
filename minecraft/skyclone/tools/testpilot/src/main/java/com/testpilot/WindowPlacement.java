package com.testpilot;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;

/**
 * Where the test window goes. Default: centred on the work area of the first
 * monitor that is NOT the primary one (the owner's second screen), 1280x720,
 * windowed. With one monitor: bottom-right corner of the primary work area.
 *
 * <p>Overrides: TESTPILOT_WINDOW=WxH, TESTPILOT_MONITOR=secondary|primary|&lt;index&gt;,
 * TESTPILOT_POS=x,y (absolute virtual-desktop coordinates).
 */
public final class WindowPlacement {

    private WindowPlacement() {
    }

    public static int[] size() {
        String s = TestPilot.setting("TESTPILOT_WINDOW", "testpilot.window", "1280x720");
        try {
            String[] p = s.toLowerCase().split("x");
            return new int[] {Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
        } catch (RuntimeException e) {
            return new int[] {1280, 720};
        }
    }

    /** Top-left corner for a window of the given size, or null to leave vanilla's choice. */
    public static int[] position(int w, int h) {
        String abs = TestPilot.setting("TESTPILOT_POS", "testpilot.pos", "");
        if (!abs.isEmpty()) {
            try {
                String[] p = abs.split(",");
                return new int[] {Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        long primary = GLFW.glfwGetPrimaryMonitor();
        if (monitors == null || monitors.limit() == 0) {
            return null;
        }
        String want = TestPilot.setting("TESTPILOT_MONITOR", "testpilot.monitor", "secondary").toLowerCase();
        long chosen = 0L;
        if (want.equals("primary")) {
            chosen = primary;
        } else if (want.matches("\\d+")) {
            int i = Integer.parseInt(want);
            if (i < monitors.limit()) {
                chosen = monitors.get(i);
            }
        } else {
            for (int i = 0; i < monitors.limit(); i++) {
                if (monitors.get(i) != primary) {
                    chosen = monitors.get(i);
                    break;
                }
            }
        }
        boolean onPrimary = chosen == 0L || chosen == primary;
        if (chosen == 0L) {
            chosen = primary;
        }
        int[] x = new int[1];
        int[] y = new int[1];
        int[] ww = new int[1];
        int[] hh = new int[1];
        GLFW.glfwGetMonitorWorkarea(chosen, x, y, ww, hh);
        int px;
        int py;
        if (onPrimary && !want.equals("primary")) {
            // Only one monitor: tuck it in the bottom-right corner, out of the way.
            px = x[0] + ww[0] - w - 16;
            py = y[0] + hh[0] - h - 48;
        } else {
            px = x[0] + (ww[0] - w) / 2;
            py = y[0] + (hh[0] - h) / 2;
        }
        TestPilot.LOG.info("[testpilot] {} monitor(s); placing window on {} at {},{} (work area {},{} {}x{})",
                monitors.limit(), onPrimary ? "PRIMARY" : "secondary", px, py, x[0], y[0], ww[0], hh[0]);
        return new int[] {px, py};
    }
}
