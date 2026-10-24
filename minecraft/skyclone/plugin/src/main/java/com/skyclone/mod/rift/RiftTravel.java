package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Getting in, staying in, getting out. Entry costs a Planar Infusion (1 free every 4 h, 3 stored), or
 * 200 Bits, or 32 Grand Experience Bottles; a Rift Prism makes it free (research 03 sec 19). Inside,
 * Rift Time drains once a second (frozen in the Gallery, the upper Wizard Tower and the Looking-Glass
 * Halls, half speed in the Colosseum) and at zero you are thrown back to the hub's Wizard Tower.
 *
 * <p>The session is a state machine checked every second, so every way in or out (warp, portal, death,
 * log-out, crash, admin teleport) ends in a consistent state: Rift inventory live if and only if the
 * player stands in the Rift with a session.
 */
final class RiftTravel implements Listener {

    enum Pay { FREE, PRISM, INFUSION, BITS, BOTTLES }

    private final RiftModule m;
    private final Map<UUID, Long> joinedAt = new HashMap<>();
    private final Map<UUID, Long> bounceMsg = new HashMap<>();

    RiftTravel(RiftModule m) { this.m = m; }

    // =========================================================================================
    // paying
    // =========================================================================================

    long periodMs() { return (long) (Math.max(0.01, m.dials.infusionHours.get()) * 3_600_000L); }

    /** Stored infusions after accruing the time since the last charge. */
    int infusions(Player p) {
        RiftData d = m.data(p);
        if (d == null) return 0;
        long now = System.currentTimeMillis();
        int max = Math.max(0, m.dials.maxInfusions.get());
        RiftMath.Infusions inf = RiftMath.accrue(Math.min(d.infusions, max), d.infusionClock, now, periodMs(), max);
        d.infusions = inf.count();
        d.infusionClock = inf.clock();
        return d.infusions;
    }

    long untilNextInfusion(Player p) {
        RiftData d = m.data(p);
        if (d == null) return 0;
        infusions(p);
        return RiftMath.untilNext(d.infusions, d.infusionClock, System.currentTimeMillis(), periodMs(), m.dials.maxInfusions.get());
    }

    boolean hasPrism(Player p) {
        try {
            if (Sky.service(Features.Accessories.class).has(p, "RIFT_PRISM")) return true;
        } catch (RuntimeException ignored) { }
        return Sky.items().exists("RIFT_PRISM") && Sky.items().count(p, "RIFT_PRISM") > 0;
    }

    /** Can enter without paying anything new: free mode, a Rift Prism, or a stored infusion. */
    boolean canPayFree(Player p) {
        return m.dials.freeEntry.get() || hasPrism(p) || infusions(p) > 0;
    }

    private Pay bestFree(Player p) {
        if (m.dials.freeEntry.get()) return Pay.FREE;
        if (hasPrism(p)) return Pay.PRISM;
        if (infusions(p) > 0) return Pay.INFUSION;
        return null;
    }

    /** Takes the payment. False (and nothing taken) when it cannot be paid. */
    private boolean pay(Player p, Pay pay) {
        RiftData d = m.data(p);
        if (d == null) return false;
        switch (pay) {
            case FREE: case PRISM: return true;
            case INFUSION: {
                if (infusions(p) <= 0) return false;
                boolean wasFull = d.infusions >= m.dials.maxInfusions.get();
                d.infusions--;
                if (wasFull) d.infusionClock = System.currentTimeMillis();
                return true;
            }
            case BITS: return Sky.economy().spendBits(p, m.dials.bitsCost.get(), "rift: entry");
            case BOTTLES: {
                int need = m.dials.bottleCost.get();
                if (!Sky.items().exists("GRAND_EXP_BOTTLE") || Sky.items().count(p, "GRAND_EXP_BOTTLE") < need) return false;
                return Sky.items().remove(p, "GRAND_EXP_BOTTLE", need) == need;
            }
            default: return false;
        }
    }

    private void refund(Player p, Pay pay) {
        RiftData d = m.data(p);
        if (d == null) return;
        switch (pay) {
            case INFUSION -> d.infusions = Math.min(m.dials.maxInfusions.get(), d.infusions + 1);
            case BITS -> Sky.economy().addBits(p, m.dials.bitsCost.get(), "rift: entry refund");
            case BOTTLES -> Sky.loot().give(p, "GRAND_EXP_BOTTLE", m.dials.bottleCost.get(), com.skyclone.api.loot.LootSource.REWARD);
            default -> { }
        }
    }

    // =========================================================================================
    // entering
    // =========================================================================================

    /** From the Warden's menu: pay with {@code pay} (null = the best free way) and go. */
    void enter(Player p, Pay pay) {
        if (!Sky.profiles().isReady(p)) return;
        if (m.inRift(p) || m.session(p) != null) { Msg.error(p, "You are already in the Rift."); return; }
        if (!Sky.worlds().isBuilt(RiftCatalog.ISLAND)) { Msg.error(p, "The Rift is still taking shape. Try again in a minute."); return; }
        Location spawn = Sky.worlds().anchor(RiftCatalog.ISLAND, "spawn");
        if (spawn == null) { Msg.error(p, "The Rift has no way in right now."); return; }
        if (pay == null) pay = bestFree(p);
        if (pay == null) {
            Msg.error(p, "You have no Planar Infusion. The next one is ready in " + Text.duration(untilNextInfusion(p) / 1000) + ".");
            return;
        }
        if (!pay(p, pay)) { Msg.error(p, "You cannot pay for that."); return; }
        startSession(p, pay);
        if (!p.teleport(spawn)) {
            endSession(p, null, false);
            refund(p, pay);
            Msg.error(p, "The way into the Rift is blocked. Nothing was taken.");
        }
    }

    void startSession(Player p, Pay paid) {
        RiftData d = m.data(p);
        if (d == null || m.session(p) != null) return;
        List<String> carried = m.dials.separateInventory.get() ? RiftInventory.enter(p, m.economy.transferable) : List.of();
        RiftSession s = new RiftSession();
        m.sessions.put(p.getUniqueId(), s);
        m.stats.invalidate(p);
        s.remaining = m.stats.sheet(p).time();
        boolean first = d.visits == 0;
        d.visits++;
        Sky.stats().invalidate(p);
        Sky.profiles().saveNow(p);
        p.showTitle(Title.title(Text.c("&5&lTHE RIFT"), Text.c("&7You have &a" + RiftMath.clock(s.remaining) + "ф &7of Rift Time"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(700))));
        Msg.sound(p, Sound.BLOCK_PORTAL_TRAVEL, 1.6f);
        List<String> body = new ArrayList<>();
        body.add("&7Paid with: &d" + switch (paid == null ? Pay.FREE : paid) {
            case PRISM -> "your Rift Prism (free)";
            case INFUSION -> "a Planar Infusion &7(" + d.infusions + " left)";
            case BITS -> m.dials.bitsCost.get() + " Bits";
            case BOTTLES -> m.dials.bottleCost.get() + " Grand Experience Bottles";
            default -> "nothing";
        });
        if (m.dials.separateInventory.get()) {
            body.add("&7Your inventory waits outside; this is your &dRift inventory&7.");
            if (!carried.isEmpty()) body.add("&7Came with you: &a" + carried.size() + " &7transferable item(s).");
        }
        body.add("&7Rift Time drains while you are here. At &c0ф &7you are sent home.");
        if (first) body.add("&eTalk to Riddler Nyx in the Cradle to learn about Riddle Souls!");
        m.riddles.catchUp(p, d);
        Msg.box(p, "&5&lENTERED THE RIFT", body);
    }

    // =========================================================================================
    // leaving
    // =========================================================================================

    /** End the visit: swap the inventory back, report, clean up. Does not teleport. */
    void endSession(Player p, String why, boolean report) {
        RiftSession s = m.sessions.remove(p.getUniqueId());
        List<String> exported = RiftInventory.exit(p, m.economy.transferable);
        m.puzzles.forget(p);
        m.stats.forget(p.getUniqueId());
        Sky.stats().invalidate(p);
        RiftData d = m.data(p);
        if (s != null && d != null) d.secondsInside += (System.currentTimeMillis() - s.enteredAt) / 1000;
        if (Sky.profiles().isReady(p)) Sky.profiles().saveNow(p);
        if (!report || s == null) return;
        List<String> body = new ArrayList<>();
        if (why != null) body.add("&7" + why);
        body.add("&7Time inside: &a" + Text.duration((System.currentTimeMillis() - s.enteredAt) / 1000));
        body.add("&7Motes earned: &d" + Text.commasInt(s.motesThisVisit) + " &7(you have &d" + Text.commasInt(d == null ? 0 : d.motes) + "&7)");
        if (!exported.isEmpty()) body.add("&7Brought back: &a" + exported.size() + " &7exportable item(s).");
        Msg.box(p, "&5&lLEFT THE RIFT", body);
    }

    /** Leave on purpose, or be thrown out: end the visit and land at the hub's Wizard Tower. */
    void eject(Player p, String why) {
        RiftSession s = m.session(p);
        if (s != null) s.ejecting = true;
        endSession(p, why, true);
        Location home = hubLanding();
        if (home != null) p.teleport(home); else Sky.worlds().sendHome(p);
    }

    Location hubLanding() {
        Location l = Sky.worlds().anchor("hub", "warp.wizard_tower");
        if (l == null) l = Sky.worlds().anchor("hub", "spawn");
        return l;
    }

    // =========================================================================================
    // the state machine
    // =========================================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onIslandChange(SkyEvents.IslandChange e) {
        Player p = e.player;
        if (RiftCatalog.ISLAND.equals(e.from) && !RiftCatalog.ISLAND.equals(e.to) && m.session(p) != null) {
            endSession(p, "You stepped out of the Rift.", true);
        } else if (RiftCatalog.ISLAND.equals(e.to) && m.session(p) == null) {
            arrivedWithoutSession(p);
        }
    }

    /** Someone reached the Rift without the Warden (a warp, a portal, an admin): charge them or send them back. */
    private void arrivedWithoutSession(Player p) {
        if (!Sky.profiles().isReady(p) || Sky.worlds().buildBypass(p) && p.isOp() && p.getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        Long j = joinedAt.get(p.getUniqueId());
        if (j != null && System.currentTimeMillis() - j < 10_000) {
            Msg.send(p, "&7Your last Rift visit ended when you left. Rift Time does not wait for anyone.");
            bounce(p);
            return;
        }
        Pay pay = bestFree(p);
        if (pay != null && pay(p, pay)) { startSession(p, pay); return; }
        long now = System.currentTimeMillis();
        Long last = bounceMsg.get(p.getUniqueId());
        if (last == null || now - last > 3000) {
            bounceMsg.put(p.getUniqueId(), now);
            Msg.error(p, "You need a Planar Infusion to enter the Rift. Talk to the Warden of the Arch by the Wizard Tower.");
        }
        bounce(p);
    }

    private void bounce(Player p) {
        Location home = hubLanding();
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            if (home != null) p.teleport(home); else Sky.worlds().sendHome(p);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { joinedAt.put(e.getPlayer().getUniqueId(), System.currentTimeMillis()); }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        joinedAt.put(p.getUniqueId(), System.currentTimeMillis());
        if (RiftInventory.inside(p) && m.session(p) == null) endSession(p, null, false);   // crash/restart recovery
        if (m.inRift(p)) {
            Msg.send(p, "&7Your last Rift visit ended when you left. Rift Time does not wait for anyone.");
            bounce(p);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        // swap back NOW, so the .dat written on quit holds the main inventory
        if (m.session(p) != null || RiftInventory.inside(p)) endSession(p, null, false);
        joinedAt.remove(p.getUniqueId());
        bounceMsg.remove(p.getUniqueId());
    }

    @EventHandler
    public void onDeath(SkyEvents.SkyDeath e) {
        Player p = e.player;
        RiftSession s = m.session(p);
        if (s == null) return;
        e.coinsLost = 0;                                   // the Rift has no purse to lose
        e.respawnAt = Sky.worlds().anchor(RiftCatalog.ISLAND, "spawn");
        RiftData d = m.data(p);
        if (d != null) d.deaths++;
        if (s.remaining > m.dials.warnAt.get()) {
            double lost = Math.min(m.dials.deathPenalty.get(), s.remaining - m.dials.warnAt.get());
            s.remaining -= lost;
            Msg.send(p, "&cYou died in the Rift and lost &a" + RiftMath.clock(lost) + "ф &cof Rift Time.");
        }
        m.puzzles.forget(p);
    }

    /** Every second: drain, warn, eject; and repair any state that drifted. */
    void tickSecond() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { tick(p); } catch (Throwable t) { Sky.log().warning("[rift] tick for " + p.getName() + ": " + t); }
        }
        for (UUID id : new ArrayList<>(m.sessions.keySet())) if (Bukkit.getPlayer(id) == null) m.sessions.remove(id);
    }

    private void tick(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        boolean in = m.inRift(p);
        RiftSession s = m.session(p);
        if (!in) {
            if (s != null || RiftInventory.inside(p)) endSession(p, "You stepped out of the Rift.", s != null);
            return;
        }
        if (s == null) { arrivedWithoutSession(p); return; }
        double max = m.stats.sheet(p).time();
        if (s.remaining > max) s.remaining = max;
        double drain = drainHere(p) * m.dials.drainScale.get();
        s.remaining -= drain;
        int r = (int) Math.ceil(s.remaining);
        if (drain > 0) {
            if (r <= 60 && !s.warned60) { s.warned60 = true; Msg.send(p, "&eYou have &a1 minute &eof Rift Time left!"); }
            if (r <= 30 && !s.warned30) { s.warned30 = true; Msg.send(p, "&eYou have &a30 seconds &eof Rift Time left!"); Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f); }
            if (r <= m.dials.warnAt.get() && !s.warned10) {
                s.warned10 = true;
                p.showTitle(Title.title(Text.c("&c" + RiftMath.clock(s.remaining) + "ф"), Text.c("&7Your Rift Time is running out!"),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1500), Duration.ofMillis(400))));
            }
            if (s.remaining > 60) s.warned60 = s.warned30 = s.warned10 = false;
        }
        if (s.remaining <= 0) {
            p.showTitle(Title.title(Text.c("&5&lRIFT TIME EXPIRED"), Text.c("&7The Rift lets go of you."),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(600))));
            eject(p, "Your Rift Time ran out.");
        }
    }

    /** Drain per second where the player stands: frozen zones 0, the Colosseum 0.5, else 1. */
    double drainHere(Player p) {
        Layout.Zone z = Sky.worlds().zoneOf(p);
        double base = 1;
        if (z != null) {
            Double sub = RiftCatalog.SUBZONE_DRAIN.get(z.id());
            RiftCatalog.Area a = RiftCatalog.area(z.id());
            base = sub != null ? sub : a == null ? 1 : a.drain();
        }
        if (base <= 0) return 0;
        // wave 5, accessory effects (research 06): Respiration holds your breath on entry, Satelite slows the loss
        // up high, the Golden Belt drains 3% faster
        RiftSession s = m.session(p);
        int breath = RiftEffects.respiration(p);
        if (s != null && breath > 0 && System.currentTimeMillis() - s.enteredAt < breath * 1000L) return 0;
        if (RiftEffects.has(p, "SATELITE") && p.getLocation().getY() >= m.dials.elevatedY.get()) base *= 0.8;
        if (RiftEffects.has(p, "GOLDEN_BELT")) base *= 1.03;
        return base;
    }

    /** Add (or remove) Rift Time this visit, capped at the max. */
    void addTime(Player p, double seconds) {
        RiftSession s = m.session(p);
        if (s == null) return;
        if (seconds < 0 && s.remaining <= m.dials.warnAt.get()) return;     // grace: no penalties under 10 s
        s.remaining = Math.max(0, Math.min(m.stats.sheet(p).time(), s.remaining + seconds));
    }

    // =========================================================================================
    // HUD
    // =========================================================================================

    String actionBar(Player p) {
        RiftSession s = m.session(p);
        if (s == null) return null;
        RiftStats.Sheet sh = m.stats.sheet(p);
        double hp = Sky.vitals().health(p), max = Sky.vitals().maxHealth(p);
        double hearts = RiftMath.heartsLeft(hp, max, sh.hearts());
        String col = s.remaining <= 30 ? "&c" : s.remaining <= 60 ? "&e" : "&a";
        double drain = drainHere(p);
        String frozen = drain == 0 ? " &b(frozen)" : drain < 1 ? " &e(slowed)" : "";
        return col + RiftMath.clock(s.remaining) + "ф Left" + frozen + "    &c" + Text.commas(hearts) + "/" + Text.commas(sh.hearts()) + "❤";
    }

    List<String> sidebar(Player p) {
        if (!m.dials.sidebar.get() || m.session(p) == null) return List.of();
        RiftData d = m.data(p);
        if (d == null) return List.of();
        return List.of("&fMotes: &d" + Text.commasInt(d.motes), "&fSouls: &d" + d.souls.size() + "&7/" + RiftCatalog.totalSouls());
    }
}
