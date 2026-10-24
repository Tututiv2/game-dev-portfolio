package com.skyclone.mod.hollows;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.util.Requirements;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * The two entry passes (research 03 §10/§12): the Crystal Caverns Pass (10,000 coins for 6 hours, bought from the
 * guide menu, {@code /hollows pass}) and the Frozen Tunnels' rail pass (SECRET_RAILROAD_PASS, forged by the mining
 * module: shown once at the gate it is punched and kept, i.e. permanent access). Both are requirement prefixes
 * ({@code hollows_pass}, {@code hollows_railpass}) appended to the islands' entry requirements, each behind a dial.
 */
final class Passes implements Listener {

    static final String CH_REQ = "hollows_pass", GT_REQ = "hollows_railpass", RAIL_ITEM = "SECRET_RAILROAD_PASS";

    private final HollowsModule m;

    Passes(HollowsModule m) {
        this.m = m;
        Sky.requirements().register(CH_REQ, new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) { return !m.dials.passRequired.get() || passLeft(p) > 0; }
            @Override public String describe(String[] parts) {
                return "&aa Crystal Caverns Pass &7(" + com.skyclone.api.ui.Text.commas(m.dials.passPrice.get()) + " coins, /hollows pass)";
            }
        });
        Sky.requirements().register(GT_REQ, new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (!m.dials.railPassRequired.get() || !Sky.items().exists(RAIL_ITEM)) return true;
                return Sky.profiles().isReady(p) && (m.quests.data(p).railPass || Sky.items().count(p, RAIL_ITEM) > 0);
            }
            @Override public String describe(String[] parts) { return "&aa Rimestone Rail Pass &7(forged at the Forge)"; }
        });
    }

    /** Millis of Caverns Pass left (0 = none). */
    long passLeft(Player p) {
        if (!Sky.profiles().isReady(p)) return 0;
        return Math.max(0, m.quests.data(p).passUntil - System.currentTimeMillis());
    }

    /** Buy (or extend) a Caverns Pass. */
    boolean buy(Player p) {
        if (!Sky.profiles().isReady(p)) return false;
        double price = Math.max(0, m.dials.passPrice.get());
        long hours = Math.max(1, m.dials.passHours.get());
        if (passLeft(p) > hours * 3_600_000L / 2) { Msg.error(p, "Your pass still has more than half its time left."); return false; }
        if (!Sky.economy().withdraw(p, price, "hollows: crystal caverns pass " + hours + "h")) { Msg.error(p, "You need " + com.skyclone.api.ui.Text.commas(price) + " coins!"); return false; }
        HollowsData d = m.quests.data(p);
        d.passUntil = Math.max(System.currentTimeMillis(), d.passUntil) + hours * 3_600_000L;
        Msg.success(p, "Crystal Caverns Pass bought: " + LobbyMath.span(passLeft(p)) + " of access.");
        Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f);
        return true;
    }

    /** First entry with a rail pass punches it: the pass is kept as permanent access. */
    @EventHandler
    public void onIsland(SkyEvents.IslandChange e) {
        Player p = e.player;
        if (!Quests.GT.equals(e.to) || !Sky.profiles().isReady(p) || !m.dials.railPassRequired.get()) return;
        HollowsData d = m.quests.data(p);
        if (d.railPass || Sky.items().count(p, RAIL_ITEM) < 1) return;
        if (Sky.items().remove(p, RAIL_ITEM, 1) < 1) return;
        d.railPass = true;
        Msg.send(p, "&bThe gatekeeper punches your &fRimestone Rail Pass&b: the Frozen Tunnels are open to you for good.");
        Msg.sound(p, Sound.BLOCK_CHAIN_PLACE, 1f);
    }
}
