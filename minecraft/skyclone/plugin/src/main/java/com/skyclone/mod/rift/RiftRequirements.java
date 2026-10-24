package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.ui.Text;
import org.bukkit.entity.Player;

/**
 * Requirement prefix {@code rift}:
 * <pre>
 *   rift:pass            can enter the Rift right now (a stored Planar Infusion, a Rift Prism, or free entry)
 *   rift:gate:&lt;area&gt;     the area's gate (Timecharms + Rift Time left); once passed, open for the rest of the visit
 *   rift:charm:&lt;id&gt;      owns that Timecharm
 *   rift:souls:&lt;n&gt;       found at least n Riddle Souls
 *   rift:motes:&lt;n&gt;       earned at least n Motes in total
 *   rift:gallery:&lt;n&gt;     secured at least n Timecharms in the Rift Gallery (wave 3)
 *   rift:visited         has been to the Rift
 * </pre>
 */
final class RiftRequirements implements Requirements.Resolver {

    private final RiftModule m;

    RiftRequirements(RiftModule m) { this.m = m; }

    @Override
    public boolean check(Player p, String[] parts) {
        if (parts.length < 2) return false;
        RiftData d = m.data(p);
        switch (parts[1]) {
            case "pass": return m.travel.canPayFree(p);
            case "visited": return d != null && d.visits > 0;
            default: break;
        }
        if (parts.length < 3 || d == null) return false;
        switch (parts[1]) {
            case "charm": return d.charms.contains(parts[2]);
            case "souls": return d.souls.size() >= num(parts[2]);
            case "motes": return d.lifetimeMotes >= num(parts[2]);
            case "gallery": return d.gallery.size() >= num(parts[2]);
            case "gate": return gate(p, d, parts[2]);
            default: return false;
        }
    }

    private boolean gate(Player p, RiftData d, String areaId) {
        if (!m.dials.gatesEnabled.get()) return true;
        RiftCatalog.Area a = RiftCatalog.area(areaId);
        if (a == null) return true;
        RiftSession s = m.session(p);
        if (s != null && s.gates.contains(areaId)) return true;
        double time = s == null ? 0 : s.remaining;
        if ("max".equalsIgnoreCase(m.dials.gateMode.get())) time = m.stats.sheet(p).time();
        boolean ok = RiftMath.passesGate(a, d.charms, time);
        if (ok && s != null) s.gates.add(areaId);
        return ok;
    }

    @Override
    public String describe(String[] parts) {
        if (parts.length < 2) return "&aa Rift requirement";
        switch (parts[1]) {
            case "pass": return "&da Planar Infusion &7(talk to the Warden of the Arch in the Wizard Tower)";
            case "visited": return "&dhaving visited the Rift";
            default: break;
        }
        if (parts.length < 3) return "&a" + String.join(":", parts);
        switch (parts[1]) {
            case "charm": {
                RiftCatalog.Charm c = RiftCatalog.charm(parts[2]);
                return "&dthe " + (c == null ? parts[2] : c.name());
            }
            case "souls": return "&d" + parts[2] + " Riddle Souls";
            case "motes": return "&d" + Text.commasInt(num(parts[2])) + " lifetime Motes";
            case "gallery": return "&d" + parts[2] + " Timecharms secured in the Rift Gallery";
            case "gate": {
                RiftCatalog.Area a = RiftCatalog.area(parts[2]);
                if (a == null) return "&a" + parts[2];
                StringBuilder sb = new StringBuilder();
                for (String c : a.gateCharms()) {
                    RiftCatalog.Charm ch = RiftCatalog.charm(c);
                    if (sb.length() > 0) sb.append("&c, ");
                    sb.append("&dthe ").append(ch == null ? c : ch.name());
                }
                if (a.gateSeconds() > 0) {
                    if (sb.length() > 0) sb.append("&c and ");
                    sb.append("&a").append(RiftMath.clock(a.gateSeconds())).append("ф &aof Rift Time left");
                }
                return sb.length() == 0 ? "&anothing" : sb.toString();
            }
            default: return "&a" + String.join(":", parts);
        }
    }

    private static long num(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return Long.MAX_VALUE; }
    }

    static String key(String sub, String arg) { return "rift:" + sub + ":" + arg; }

    static boolean has(Player p, String key) { return key == null || Sky.requirements().check(p, key); }
}
