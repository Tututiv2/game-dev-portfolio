package com.skyclone.mod.hunting;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Lassos: right-click a Forest critter to rope it. It is held still while its stamina drains (faster with a
 * better lasso and Hunter's Suppress); when the stamina runs out you have {@code hunting.reel-window-seconds}
 * to right-click again and REEL IN. A critter needs 1-3 reels by rarity; miss the window too often and it
 * breaks free. Walking too far away snaps the rope.
 */
final class Lasso {

    private Lasso() {}

    private static final class Session {
        final UUID target;
        final String lasso;
        final Location hold;
        final double range;
        double stamina;
        double full;
        int reels;
        int needed;
        int misses;
        long windowEnds;
        Session(UUID target, String lasso, Location hold, double range) {
            this.target = target; this.lasso = lasso; this.hold = hold; this.range = range;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    static void start() {
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), Lasso::tick, 2L, 2L);
    }

    static boolean active(Player p) { return SESSIONS.containsKey(p.getUniqueId()); }

    static void forget(UUID id) {
        Session s = SESSIONS.remove(id);
        if (s != null) release(s);
    }

    /** The lasso's right-click: throw, or reel in. Never charges a cooldown. */
    static boolean use(Player p, ItemStack item) {
        Session s = SESSIONS.get(p.getUniqueId());
        if (s != null) {
            if (s.windowEnds > 0) {
                s.reels++;
                s.windowEnds = 0;
                Msg.sound(p, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.4f);
                if (s.reels >= s.needed) {
                    Entity e = Bukkit.getEntity(s.target);
                    SESSIONS.remove(p.getUniqueId());
                    if (e instanceof LivingEntity le && le.isValid()) {
                        ShardDef sd = Hunt.cat.byCritter(Sky.mobs().defOf(le).id);
                        JsonObject x = Hunt.toolExtra(s.lasso);
                        if (sd != null) Tools.catchCritter(p, le, sd, "lasso", Hunt.num(x, "hf", 0));
                    }
                    return false;
                }
                s.stamina = s.full;
                Sky.vitals().flash(p, "&aReeled! &7(" + s.reels + "/" + s.needed + ")", 20);
            } else {
                s.misses++;
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f);
                Sky.vitals().flash(p, "&cToo early! &7Wait for &eREEL IN!", 20);
                if (s.misses >= HDials.LASSO_MISSES.get()) breakFree(p, s, "struggled free");
            }
            return false;
        }
        String id = Sky.items().idOf(item);
        JsonObject x = Hunt.toolExtra(id);
        double range = Hunt.num(x, "range", 10);
        LivingEntity t = Tools.target(p, range, "lasso");
        if (t == null) {
            Sky.vitals().flash(p, "&7No &2Forest &7critter in reach.", 20);
            return false;
        }
        for (Session o : SESSIONS.values()) if (o.target.equals(t.getUniqueId())) {
            Msg.error(p, "&cSomeone else already has that critter roped.");
            return false;
        }
        MobDef d = Sky.mobs().defOf(t);
        double base = d.extra.has("stamina") ? d.extra.get("stamina").getAsDouble() : 3;
        double faster = Hunt.num(x, "faster", 0) + Hunt.snapshot(p).value("hunters_suppress");
        Session s2 = new Session(t.getUniqueId(), id, t.getLocation(), range + 4);
        s2.full = HuntMath.lassoStamina(base, faster);
        s2.stamina = s2.full;
        s2.needed = d.extra.has("reels") ? d.extra.get("reels").getAsInt() : 1;
        SESSIONS.put(p.getUniqueId(), s2);
        if (t instanceof Mob m) m.setAware(false);
        Msg.sound(p, Sound.BLOCK_TRIPWIRE_ATTACH, 1f);
        Sky.vitals().flash(p, "&2Lassoed &f" + d.name + "&2! &7Hold on...", 30);
        return false;
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Session> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            Session s = en.getValue();
            Entity e = Bukkit.getEntity(s.target);
            if (p == null || !(e instanceof LivingEntity le) || !le.isValid() || !p.getWorld().equals(le.getWorld())) {
                it.remove();
                if (e instanceof LivingEntity le2) release(s, le2);
                continue;
            }
            if (p.getLocation().distance(le.getLocation()) > s.range) {
                it.remove();
                release(s, le);
                Msg.send(p, "&cThe lasso snapped: you went too far.");
                continue;
            }
            String held = Sky.items().idOf(p.getInventory().getItemInMainHand());
            if (!"lasso".equals(Hunt.tool(held))) {
                it.remove();
                release(s, le);
                Msg.send(p, "&cYou let go of the lasso.");
                continue;
            }
            // hold it still
            if (le.getLocation().distanceSquared(s.hold) > 0.5) le.teleport(s.hold.clone().setDirection(le.getLocation().getDirection()));
            Tools.particlesLine(p.getLocation().add(0, 1.1, 0), le.getLocation().add(0, le.getHeight() / 2, 0), Color.fromRGB(139, 94, 52));
            if (s.windowEnds > 0) {
                if (now > s.windowEnds) {
                    s.windowEnds = 0;
                    s.misses++;
                    s.stamina = s.full;
                    if (s.misses >= HDials.LASSO_MISSES.get()) { it.remove(); breakFreeQuiet(p, s, le); continue; }
                    Sky.vitals().flash(p, "&cMissed the reel! &7(" + s.misses + "/" + HDials.LASSO_MISSES.get() + ")", 20);
                } else {
                    Sky.vitals().flash(p, "&e&lREEL IN! &7(right-click)", 4);
                }
                continue;
            }
            s.stamina -= 0.1;
            if (s.stamina <= 0) {
                s.windowEnds = now + (long) (HDials.REEL_WINDOW.get() * 1000);
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.8f);
                p.showTitle(net.kyori.adventure.title.Title.title(Text.c("&e&lREEL IN!"), Text.c("&7Right-click now"),
                        net.kyori.adventure.title.Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(800), java.time.Duration.ofMillis(200))));
            } else {
                Sky.vitals().flash(p, "&2Stamina " + Text.bar(s.stamina / s.full, 12) + " &7Reels &a" + s.reels + "&7/&a" + s.needed, 4);
            }
        }
    }

    private static void breakFree(Player p, Session s, String why) {
        SESSIONS.remove(p.getUniqueId());
        Entity e = Bukkit.getEntity(s.target);
        if (e instanceof LivingEntity le) breakFreeQuiet(p, s, le);
        else Msg.send(p, "&cThe critter " + why + "!");
    }

    private static void breakFreeQuiet(Player p, Session s, LivingEntity le) {
        release(s, le);
        Msg.send(p, "&cThe critter broke free!");
        le.setVelocity(le.getLocation().toVector().subtract(p.getLocation().toVector()).normalize().multiply(0.6).setY(0.3));
    }

    private static void release(Session s) {
        Entity e = Bukkit.getEntity(s.target);
        if (e instanceof LivingEntity le) release(s, le);
    }

    private static void release(Session s, LivingEntity le) {
        if (le instanceof Mob m && le.isValid()) m.setAware(true);
    }
}
