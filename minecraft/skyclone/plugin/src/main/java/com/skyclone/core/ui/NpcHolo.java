package com.skyclone.core.ui;

import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure styling for the NPC nametag (no Bukkit), so the look can be self-tested.
 *
 * <p>Reference-game look, top to bottom: optional title ("&amp;6&amp;lBANKER"), any extra
 * {@code def.lines}, the NAME in a colour (never bare white), and "&amp;e&amp;lCLICK" for every NPC that
 * has a click handler. A title or line that only says CLICK is moved under the name.
 */
final class NpcHolo {

    private NpcHolo() {}

    static final String CLICK = "&e&lCLICK";
    /** Colour for names that bring none of their own and have no usable title colour. */
    static final String DEFAULT_COLOUR = "&a";

    /** Lines top to bottom, '&amp;' coded. */
    static List<String> lines(NpcDef d, boolean clickable) {
        List<String> out = new ArrayList<>();
        String title = d.title;
        boolean click = clickable || isClick(title);
        if (title != null && !title.isBlank() && !isClick(title)) out.add(title);
        if (d.lines != null) {
            for (String s : d.lines) {
                if (s == null) continue;
                if (isClick(s)) { click = true; continue; }   // CLICK always goes under the name
                out.add(s);
            }
        }
        if (d.name != null && !d.name.isBlank()) out.add(styledName(d.name, title));
        if (click) out.add(CLICK);
        return out;
    }

    static boolean isClick(String s) {
        return s != null && Text.strip(s).trim().equalsIgnoreCase("CLICK");
    }

    /**
     * The name with a real colour. Keeps an explicit non-white colour; replaces a leading bare
     * white (or reset, or none at all) with the title's colour, else {@link #DEFAULT_COLOUR}.
     */
    static String styledName(String name, String title) {
        String n = name.replace('§', '&');
        int i = 0;
        char colour = 0;
        int colourAt = -1;
        while (i + 1 < n.length() && n.charAt(i) == '&') {
            char c = Character.toLowerCase(n.charAt(i + 1));
            if (c == '#' && i + 8 <= n.length()) { colour = '#'; colourAt = i; i += 8; continue; }
            if ("0123456789abcdef".indexOf(c) >= 0) { colour = c; colourAt = i; i += 2; continue; }
            if ("klmnor".indexOf(c) >= 0) { if (c == 'r') { colour = 0; colourAt = -1; } i += 2; continue; }
            break;
        }
        if (colour != 0 && colour != 'f') return n;
        String pick = colourFor(title);
        if (colour == 'f') return n.substring(0, colourAt) + pick + n.substring(colourAt + 2);
        return pick + n;
    }

    /** First real colour in the title (skipping white/grays/black), or the default. */
    static String colourFor(String title) {
        if (title == null || isClick(title)) return DEFAULT_COLOUR;
        String t = title.replace('§', '&').toLowerCase(Locale.ROOT);
        for (int i = 0; i + 1 < t.length(); i++) {
            if (t.charAt(i) != '&') continue;
            char c = t.charAt(i + 1);
            if ("123456abcde9".indexOf(c) >= 0) return "&" + c;
        }
        return DEFAULT_COLOUR;
    }

    /** Self-test: null when the styling rules hold. */
    static String check() {
        NpcDef white = NpcDef.of("t1", "&fBanker Rhys", "hub", "a").title("&6&lBANKER");
        List<String> l = lines(white, true);
        if (!l.equals(List.of("&6&lBANKER", "&6Banker Rhys", CLICK))) return "banker lines: " + l;
        NpcDef plain = NpcDef.of("t2", "Ranger Hollis", "hub", "a").title("&e&lCLICK");
        l = lines(plain, true);
        if (!l.equals(List.of(DEFAULT_COLOUR + "Ranger Hollis", CLICK))) return "click-title lines: " + l;
        NpcDef coloured = NpcDef.of("t3", "&dThaumaturgist", "hub", "a");
        l = lines(coloured, false);
        if (!l.equals(List.of("&dThaumaturgist"))) return "coloured, no handler: " + l;
        if (!styledName("&f&lBold", null).equals(DEFAULT_COLOUR + "&lBold")) return "&f&l: " + styledName("&f&lBold", null);
        if (!styledName("&#ff8800Hex", null).equals("&#ff8800Hex")) return "hex name changed";
        NpcDef withLines = NpcDef.of("t4", "&fA", "hub", "a");
        withLines.lines.add("&7Shop");
        withLines.lines.add("&e&lCLICK");
        l = lines(withLines, true);
        if (!l.equals(List.of("&7Shop", DEFAULT_COLOUR + "A", CLICK))) return "CLICK not alone under the name: " + l;
        return null;
    }
}
