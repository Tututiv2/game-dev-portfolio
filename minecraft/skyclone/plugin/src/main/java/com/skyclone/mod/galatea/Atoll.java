package com.skyclone.mod.galatea;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** The Lotus Atoll's living parts: the rain, the Frogcoin pool and its blessings, the Lotus Eater, the launch pad, travel scrolls. */
final class Atoll implements Listener {

    /** Frogcoin blessings: id, label, stat, amount. // TUNE: the reference says "temporary stat bonuses", no table. */
    record Blessing(String id, String label, Stat stat, double amount) {}

    static final List<Blessing> BLESSINGS = List.of(
            new Blessing("speed", "Blessing of the Current", Stat.FISHING_SPEED, 15),
            new Blessing("creatures", "Blessing of the Deep", Stat.SEA_CREATURE_CHANCE, 3),
            new Blessing("treasure", "Blessing of Fortune", Stat.TREASURE_CHANCE, 2),
            new Blessing("trophy", "Blessing of the Collector", Stat.TROPHY_FISH_CHANCE, 10),
            new Blessing("wisdom", "Blessing of the Lotus", Stat.FISHING_WISDOM, 10));

    static Blessing blessing(String id) {
        for (Blessing b : BLESSINGS) if (b.id.equals(id)) return b;
        return null;
    }

    // ---- stats & sidebar -------------------------------------------------------------------------

    static void stats(Player p, StatMap into) {
        if (!GalateaModule.ready(p) || !GalateaModule.on(p, GalateaModule.ATOLL)) return;
        if (Sky.calendar().active(GalateaModule.RAIN)) into.add(Stat.FISHING_SPEED, GalateaModule.D.rainFs.get());
        GalateaData d = GalateaModule.data(p);
        Blessing b = d.blessingUntil > System.currentTimeMillis() ? blessing(d.blessing) : null;
        if (b != null) into.add(b.stat, b.amount);
    }

    static List<String> sidebar(Player p) {
        if (!GalateaModule.D.sidebar.get() || !GalateaModule.ready(p)) return List.of();
        List<String> out = new ArrayList<>();
        if (GalateaModule.on(p, GalateaModule.ATOLL)) {
            if (Sky.calendar().active(GalateaModule.RAIN))
                out.add("&9Rain: &b" + Text.duration(Sky.calendar().millisRemaining(GalateaModule.RAIN) / 1000) + " &7(&b+" + GalateaModule.D.rainFs.get() + "&7 FS)");
            GalateaData d = GalateaModule.data(p);
            long left = d.blessingUntil - System.currentTimeMillis();
            if (left > 0 && blessing(d.blessing) != null) out.add("&dBlessed: &a" + Text.duration(left / 1000));
        } else if (GalateaModule.on(p, GalateaModule.CANYON)) {
            String next = Journal.nextTask(p);
            if (next != null) out.add("&6Journal: &f" + next);
        }
        return out;
    }

    // ---- rain ------------------------------------------------------------------------------------

    /** Every 5 s: show the Atoll rain to whoever is on the Atoll, clear it for everyone else. */
    static void tick() {
        boolean rain = Sky.calendar().active(GalateaModule.RAIN) && GalateaModule.D.rainWeather.get();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean here = GalateaModule.on(p, GalateaModule.ATOLL);
            if (here && rain) { if (p.getPlayerWeather() != WeatherType.DOWNFALL) p.setPlayerWeather(WeatherType.DOWNFALL); }
            else if (p.getPlayerWeather() != null && (here || GalateaModule.on(p, GalateaModule.CANYON) || p.getPlayerWeather() == WeatherType.DOWNFALL)) p.resetPlayerWeather();
        }
    }

    @EventHandler
    public void onStart(SkyEvents.CalendarStart e) {
        if (!GalateaModule.RAIN.equals(e.eventId)) return;
        for (Player p : Bukkit.getOnlinePlayers())
            if (GalateaModule.on(p, GalateaModule.ATOLL)) Msg.send(p, "&9&lRAIN! &7Rain sweeps over the Atoll: &b+" + GalateaModule.D.rainFs.get() + " Fishing Speed&7, and the Wetlands Frogs are singing.");
        tick();
    }

    @EventHandler
    public void onEnd(SkyEvents.CalendarEnd e) {
        if (GalateaModule.RAIN.equals(e.eventId)) tick();
    }

    @EventHandler
    public void onIsland(SkyEvents.IslandChange e) {
        Bukkit.getScheduler().runTask(Sky.plugin(), Atoll::tick);
    }

    /**
     * Playtest wave 5: the pad is a 3x3 of slime blocks and stepping on it did nothing (only clicking Hobb
     * launched), which reads as broken. Stepping onto the slime now launches the same way.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPadStep(org.bukkit.event.player.PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null || (to.getBlockX() == e.getFrom().getBlockX() && to.getBlockY() == e.getFrom().getBlockY()
                && to.getBlockZ() == e.getFrom().getBlockZ())) return;
        Player p = e.getPlayer();
        if (to.clone().subtract(0, 0.2, 0).getBlock().getType() != org.bukkit.Material.SLIME_BLOCK) return;
        if (!GalateaModule.on(p, GalateaModule.ATOLL) || !GalateaModule.ready(p)) return;
        Location pad = Sky.worlds().anchor(GalateaModule.ATOLL, "warp.launch_pad");
        if (pad == null || pad.getWorld() != to.getWorld() || pad.distanceSquared(to) > 16) return;
        launch(p);
    }

    // ---- the Frogcoin pool, blessings and the Lotus Eater ----------------------------------------

    /** Right-clicking the cave pool with a Frogcoin in hand throws it in. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player p = e.getPlayer();
        if (!GalateaModule.on(p, GalateaModule.ATOLL) || !"FROGCOIN".equals(Sky.items().idOf(p.getInventory().getItemInMainHand()))) return;
        Box pool = Sky.worlds().area(GalateaModule.ATOLL, "cave_pool");
        if (pool == null || !pool.grow(4).contains(p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ())) return;
        e.setCancelled(true);
        Sky.abilities().consumeClick(p);
        toss(p);
    }

    static void toss(Player p) {
        if (!GalateaModule.ready(p)) return;
        if (Sky.items().count(p, "FROGCOIN") <= 0) {
            Msg.send(p, "&7The water glitters. &eThrow a &aFrogcoin &einto it for a blessing.");
            return;
        }
        if (Sky.items().remove(p, "FROGCOIN", 1) != 1) return;
        GalateaData d = GalateaModule.data(p);
        Blessing b = BLESSINGS.get(ThreadLocalRandom.current().nextInt(BLESSINGS.size()));
        d.blessing = b.id;
        d.blessingUntil = System.currentTimeMillis() + GalateaModule.D.blessingMinutes.get() * 60_000L;
        d.coinsTotal++;
        Msg.send(p, "&a&lPLOP! &7The coin sinks. &d" + b.label + "&7: &a+" + Text.commas(b.amount) + " " + Text.strip(b.stat.label())
                + " &7on the Atoll for &a" + GalateaModule.D.blessingMinutes.get() + "m&7.");
        Msg.sound(p, Sound.ENTITY_PLAYER_SPLASH, 1.4f);
        p.getWorld().spawnParticle(Particle.SPLASH, p.getLocation().add(p.getLocation().getDirection().multiply(2)), 20, 0.4, 0.2, 0.4);
        if (d.eaterAwakeUntil <= System.currentTimeMillis()) {
            d.coinsTossed++;
            int need = Math.max(1, GalateaModule.D.eaterCoins.get());
            if (d.coinsTossed >= need) {
                d.coinsTossed = 0;
                d.eaterAwakeUntil = System.currentTimeMillis() + GalateaModule.D.eaterMinutes.get() * 60_000L;
                Msg.send(p, "&2[NPC] The Lotus Eater&f: ...Ribbit. &7(Something large stirs at the edge of the pool. &aThe Lotus Eater is awake&7.)");
                Msg.sound(p, Sound.ENTITY_FROG_LONG_JUMP, 0.6f);
            } else Msg.send(p, "&8Something below the water shifts. (" + d.coinsTossed + "/" + need + ")");
        }
        Sky.stats().invalidate(p);
    }

    static void lotusEater(Player p) {
        GalateaData d = GalateaModule.data(p);
        if (d.eaterAwakeUntil > System.currentTimeMillis()) { GalateaMenus.lotusEater(p); return; }
        Msg.send(p, "&2[NPC] The Lotus Eater&f: &8*snores*");
        Msg.send(p, "&7He is asleep. Throw &aFrogcoins &7into the cave pool to wake him: &e" + d.coinsTossed + "&7/&e" + GalateaModule.D.eaterCoins.get());
    }

    // ---- the launch pad --------------------------------------------------------------------------

    static void launch(Player p) {
        GalateaData d = GalateaModule.data(p);
        long now = System.currentTimeMillis();
        if (now - d.lastLaunch < GalateaModule.D.launchCooldown.get() * 1000L) return;
        Location to = Sky.worlds().anchor(GalateaModule.ATOLL, "warp.highlands");
        if (to == null) { Msg.error(p, "The launch pad is not built yet."); return; }
        d.lastLaunch = now;
        Msg.send(p, "&e[NPC] Launcher Hobb&f: Hold on to your hat!");
        Msg.sound(p, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f);
        p.setVelocity(new Vector(0, 1.6, 0));
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (!p.isOnline() || !GalateaModule.on(p, GalateaModule.ATOLL)) return;
            p.setFallDistance(0);
            p.teleport(to);
            p.getWorld().spawnParticle(Particle.CLOUD, to, 30, 0.6, 0.3, 0.6, 0.02);
            Msg.sound(p, Sound.ENTITY_FROG_LONG_JUMP, 1f);
        }, 14L);
    }

    // ---- the Safari (gate and office) ------------------------------------------------------------

    static void safari(Player p, GalateaModule.Npc n) {
        if (Sky.commands().exists("safari")) {
            if (Sky.requirements().checkOrTell(p, "skill:hunting:15")) Sky.commands().run(p, "safari");
            return;
        }
        Msg.send(p, "&e[NPC] " + n.name + "&f: The Safari's trails are being cleared. Come back once the hunters have mapped them!");
        Msg.send(p, "&7(The Critter Safari is not open in this game yet. Entry will need &aHunting XV&7 and a Safari Ticket.)");
    }

    // ---- travel scrolls --------------------------------------------------------------------------

    static String scrollName(String id) {
        return switch (id) {
            case "lotus_atoll" -> "Travel Scroll to the Lotus Atoll";
            case "torrhus_canyon" -> "Travel Scroll to Torrent Canyon";
            case "torrhus_springs" -> "Travel Scroll to Torrent Springs";
            case "torrhus_safari" -> "Travel Scroll to the Critter Safari Entrance";
            default -> "Travel Scroll";
        };
    }

    static boolean readScroll(Player p, ItemStack item) {
        if (!GalateaModule.ready(p) || item == null) return false;
        var def = Sky.items().def(Sky.items().idOf(item));
        if (def == null || def.extra == null || !def.extra.has("scroll")) return false;
        String id = def.extra.get("scroll").getAsString();
        GalateaData d = GalateaModule.data(p);
        if (d.scrolls.contains(id)) { Msg.error(p, "You have already read this scroll."); return false; }
        if (!Sky.items().meetsRequirements(p, item, true)) return false;
        item.setAmount(item.getAmount() - 1);
        d.scrolls.add(id);
        Msg.success(p, "You read the " + scrollName(id) + "! &7The destination is open to you for good.");
        Sky.profiles().saveNow(p);
        return true;
    }

    /** Debug/admin: which zone of the atoll a player is in. */
    static String zoneId(Player p) {
        Layout.Zone z = Sky.worlds().zoneOf(p);
        return z == null ? "-" : z.id();
    }
}
