package com.skyclone.mod.bazaar;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Small presentation helpers shared by every Bazaar menu. */
final class Ui {

    private Ui() {}

    static final String ARROW = " ➜ ";
    static final String[] SPARK = {"▁", "▂", "▃", "▄", "▅", "▆", "▇", "█"};

    /** A menu icon that LOOKS like the product (its material, skull or glint), with our own name and lore. */
    static ItemStack product(String id, String name, List<String> lore) {
        ItemDef d = Sky.items().def(id);
        ItemStack it;
        if (d != null && d.skull != null && !d.skull.isEmpty()) {
            it = Icons.skull(d.skull, name, lore);
        } else {
            Material m = d == null ? null : Material.matchMaterial(d.material == null ? "" : d.material);
            if (m == null || !m.isItem() || m.isAir()) m = Material.matchMaterial(id);
            if (m == null || !m.isItem() || m.isAir()) m = Material.PAPER;
            it = Icons.of(m, name, lore);
        }
        if (d != null && (d.glow || d.has("glow"))) Icons.glow(it);
        return it;
    }

    static ItemStack material(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return new ItemStack(m == null || !m.isItem() ? fallback : m);
    }

    static Material mat(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m == null || !m.isItem() ? fallback : m;
    }

    static Material glass(String colour) {
        Material m = Material.matchMaterial((colour == null ? "GRAY" : colour) + "_STAINED_GLASS_PANE");
        return m == null ? Material.GRAY_STAINED_GLASS_PANE : m;
    }

    static String coins(long tenths) { return "&6" + Pricing.fmtTotal(tenths) + " coins"; }

    static String unit(long tenths) { return tenths <= 0 ? "&cnone" : "&6" + Pricing.fmt(tenths) + " coins"; }

    /** "+3.2%" / "-1.0%" coloured; "&8n/a" when unknown. */
    static String change(double f) {
        if (Double.isNaN(f)) return "&8n/a";
        String s = String.format(java.util.Locale.US, "%+.1f%%", f * 100);
        return (f > 0.0005 ? "&a" : f < -0.0005 ? "&c" : "&7") + s;
    }

    /** A 24-hour sparkline from bar prices (0 = no data, drawn as a grey dot). */
    static String sparkline(List<Long> series) {
        long lo = Long.MAX_VALUE, hi = 0;
        for (long v : series) if (v > 0) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        StringBuilder sb = new StringBuilder();
        long prev = 0;
        for (long v : series) {
            if (v <= 0) { sb.append("&8·"); continue; }
            int idx = hi == lo ? 3 : (int) Math.round((double) (v - lo) / (hi - lo) * 7);
            String col = prev <= 0 || v == prev ? "&e" : v > prev ? "&a" : "&c";
            sb.append(col).append(SPARK[Math.max(0, Math.min(7, idx))]);
            prev = v;
        }
        return sb.toString();
    }

    static List<String> lore(String... lines) {
        List<String> l = new ArrayList<>();
        for (String s : lines) l.add(s);
        return l;
    }

    static void result(Player p, Market.Result r) {
        if (r == null) return;
        if (r.ok()) {
            for (String line : r.message().split("\n")) Msg.send(p, line);
            if (PlayerData.of(p).sounds) Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f);
        } else {
            Msg.error(p, r.message());
        }
    }

    static String plural(long n, String word) { return Text.commasInt(n) + " " + word + (n == 1 ? "" : "s"); }

    /** Inner 7x4 area of a 6-row menu. */
    static final int[] INNER = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    /** Centred slots for {@code n} (<= 7) items on one row starting at row {@code row}. */
    static int[] centred(int row, int n) {
        int[] out = new int[n];
        int start = row * 9 + (9 - n) / 2;
        if (n % 2 == 0) {
            // even counts: leave the middle column empty for symmetry
            int left = n / 2;
            for (int i = 0; i < left; i++) out[i] = row * 9 + 4 - left + i;
            for (int i = 0; i < left; i++) out[left + i] = row * 9 + 5 + i;
            return out;
        }
        for (int i = 0; i < n; i++) out[i] = start + i;
        return out;
    }
}
