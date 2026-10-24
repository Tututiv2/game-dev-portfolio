package com.skyclone.mod.bazaar;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The module's singletons, so menus and commands reach the market without passing it everywhere. */
final class Bz {

    private Bz() {}

    static Catalogue cat;
    static Market market;
    static MarketMaker mm;
    static Seeder seeder;
    static Store store;

    /** Last category a player browsed (the genre reopens it). */
    static final Map<UUID, String> lastCategory = new HashMap<>();

    /**
     * May this player use the Bazaar right now? {@code atNpc}: opened from the Bazaar NPC (no remote requirement).
     * The access requirement fails OPEN only when the module that owns its prefix is missing (a missing SkyBlock
     * level module must not lock everyone out of the economy).
     */
    static boolean access(Player p, boolean atNpc, boolean tell) {
        if (!Sky.profiles().isReady(p)) { if (tell) Msg.error(p, "Your profile is still loading!"); return false; }
        String mode;
        try { mode = Sky.profiles().mode(p); } catch (RuntimeException e) { mode = null; }
        if (mode != null && !mode.isBlank()) {
            for (String b : BzDials.blockedModes.get().split(",")) {
                if (!b.isBlank() && b.trim().equalsIgnoreCase(mode)) {
                    if (tell) Msg.error(p, "The Bazaar is not available on " + com.skyclone.api.ui.Text.titleCase(mode.toLowerCase(java.util.Locale.ROOT)) + " profiles!");
                    return false;
                }
            }
        }
        if (!check(p, Gate.key(BzDials.accessRequirement.get()), tell)) return false;
        boolean anywhere = special(p) && inList(BzDials.specialAnywhereModes.get(), mode);
        if (!atNpc && !anywhere && !check(p, BzDials.remoteRequirement.get(), tell)) {
            if (tell) Msg.send(p, "&7Visit the &6Bazaar &7in the Hub to trade there instead.");
            return false;
        }
        return true;
    }

    /** The player's profile mode, lower case, or "" (normal / unknown). */
    static String mode(Player p) {
        try {
            String m = Sky.profiles().mode(p);
            return m == null ? "" : m.trim().toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) { return ""; }
    }

    /** Does this player get the Special Bazaar (Instant Buy of the listed products only)? */
    static boolean special(Player p) {
        String m = mode(p);
        return !m.isEmpty() && !m.equals("normal") && inList(BzDials.specialModes.get(), m);
    }

    /** May the Special Bazaar sell this product? */
    static boolean specialProduct(String pid) { return pid != null && inList(BzDials.ironmanProducts.get(), pid); }

    /**
     * Research 02 s8: essence can be BOUGHT on the Bazaar only with Catacombs 20, Forest Essence exempt (selling is
     * free). Returns the refusal, or null. Fails open when no module owns the requirement's prefix (no dungeons
     * module = nobody could ever buy essence).
     */
    static String essenceBlock(Player p, String pid) {
        if (!Gate.essenceGated(pid, BzDials.essenceBuyExempt.get())) return null;
        String req = Gate.key(BzDials.essenceBuyRequirement.get());
        if (req.isEmpty()) return null;
        String prefix = req.split(":", 2)[0].trim();
        if (!Sky.requirements().known(prefix)) return null;
        if (Sky.requirements().check(p, req)) return null;
        String d = com.skyclone.api.ui.Text.strip(Sky.requirements().describe(req));
        return "You need " + (d == null || d.isBlank() ? req : d) + " to buy Essence on the Bazaar! You can still sell it.";
    }

    static boolean inList(String csv, String v) {
        if (csv == null || v == null) return false;
        for (String s : csv.split(",")) if (!s.isBlank() && s.trim().equalsIgnoreCase(v)) return true;
        return false;
    }

    private static boolean check(Player p, String req, boolean tell) {
        if (req == null || req.isBlank()) return true;
        String r = req.trim();
        if (r.startsWith("sblevel:") && !Sky.has(Features.SkyLevel.class)) return true;
        // the Booster Cookie key belongs to the bank module: without it nobody could ever open /bz remotely
        if (r.equals("cookie") && !Sky.items().exists("BOOSTER_COOKIE")) return true;
        if (Sky.requirements().check(p, r)) return true;
        if (tell) Sky.requirements().checkOrTell(p, r);
        return false;
    }

    static Features.SkyMenu skyMenu() { return Sky.service(Features.SkyMenu.class); }
}
