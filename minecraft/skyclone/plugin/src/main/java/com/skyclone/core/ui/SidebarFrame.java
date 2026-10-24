package com.skyclone.core.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure assembly of the sidebar: fixed head, module sections (already in priority order, most
 * important first), a blank and the footer. Sections that do not fit in {@code maxLines} are
 * dropped, least important first; a section that does not fit is skipped and a smaller, less
 * important one may still take the space. Each included section is preceded by one blank line.
 */
public final class SidebarFrame {

    private SidebarFrame() {}

    public static List<String> compose(List<String> head, List<List<String>> sections, String footer, int maxLines) {
        return compose(head, sections, footer, maxLines, null);
    }

    /**
     * As {@link #compose(List, List, String, int)}, and every non-empty section that did not fit is added to
     * {@code dropped} (in priority order) when it is not null. The sidebar has 4 free lines under the 9-line
     * head, so one live section (a Harvest Fair, a slayer quest) pushes the always-present Objective off it -
     * the reference's own priority rule. The caller shows the dropped sections in the tab list instead, so
     * nothing a module wanted the player to see is lost silently (wave-5 regression playtest, sidebar note).
     */
    public static List<String> compose(List<String> head, List<List<String>> sections, String footer, int maxLines,
                                       List<List<String>> dropped) {
        List<String> out = new ArrayList<>(head);
        int remaining = maxLines - head.size() - 2;   // blank + footer at the end
        for (List<String> s : sections) {
            if (s == null || s.isEmpty()) continue;
            int need = 1 + s.size();
            if (need > remaining) {
                if (dropped != null) dropped.add(s);
                continue;
            }
            out.add("");
            out.addAll(s);
            remaining -= need;
        }
        out.add("");
        out.add(footer == null ? "" : footer);
        while (out.size() > maxLines) out.remove(out.size() - 2); // never drop the footer
        return out;
    }

    /** Tab footer: widgets separated by blank lines, at most {@code maxLines} lines. */
    public static List<String> tabFooter(List<List<String>> widgets, String tail, int maxLines) {
        return tabFooter(widgets, null, tail, maxLines);
    }

    /**
     * Tab footer with the sections the sidebar had no room for: they go FIRST (the player's live objective or
     * event matters more than the static widgets), each as its own block, then the widgets, within the same
     * {@code maxLines} budget.
     */
    public static List<String> tabFooter(List<List<String>> widgets, List<List<String>> overflow, String tail, int maxLines) {
        if (overflow != null && !overflow.isEmpty()) {
            List<List<String>> all = new ArrayList<>(overflow);
            all.addAll(widgets);
            widgets = all;
        }
        List<String> out = new ArrayList<>();
        for (List<String> w : widgets) {
            if (w == null || w.isEmpty()) continue;
            if (out.size() + w.size() + 1 > maxLines) continue;
            if (!out.isEmpty()) out.add("");
            out.addAll(w);
        }
        if (tail != null && !tail.isEmpty()) {
            out.add("");
            out.add(tail);
        }
        return out;
    }
}
