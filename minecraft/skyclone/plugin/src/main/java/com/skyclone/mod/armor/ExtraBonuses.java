package com.skyclone.mod.armor;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.EnchantDef;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Text;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.skyclone.mod.armor.SetIds.*;

/**
 * Wave 5, second pass: the behaviour gaps found by diffing our bonus text against the reference lore.
 * <ul>
 *   <li><b>Peace Treaty</b> (Bronze/Silver/Gold/Diamond Hunter, 2+ pieces of one set): sea creatures cannot
 *       hurt you and you cannot hurt them (here); you no longer fish them up ({@link BonusStats} sets your
 *       Sea Creature Chance to 0 in the final stat pass).</li>
 *   <li><b>Spooky</b> (Great Spook, full set): punching another player while holding the Great Spook Staff
 *       gives them a fright (title, sound, particles). Pure fun, no damage; works where PvP is off, since it
 *       listens to the attack ATTEMPT.</li>
 *   <li><b>Old Blood on Feather Falling</b>: enchanting turns Feather Falling into safe-fall distance; Old
 *       Blood adds 50% of what the Old Dragon pieces' own Feather Falling gives, as its own attribute
 *       modifier, refreshed once a second and removed the moment the set comes off.</li>
 * </ul>
 */
final class ExtraBonuses implements Listener {

    static final NamespacedKey OLD_FALL_KEY = new NamespacedKey("skyclone", "armor_old_feather_falling");
    private static final Map<UUID, Long> SPOOKED = new HashMap<>();

    static void start() {
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            for (Player p : Bukkit.getOnlinePlayers()) oldBloodFall(p);
        }, 45L, 20L);
    }

    // ================================================================================ Peace Treaty

    /** Before every other damage listener: the hit simply does not happen. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        if (!ArmorDials.ENABLED.get()) return;
        if (peaceTreatyBlocks(e.ctx)) e.setCancelled(true);
    }

    static boolean peaceTreatyBlocks(DamageContext ctx) {
        Player victim = ctx.playerVictim(), attacker = ctx.playerAttacker();
        if (victim != null && attacker == null && ctx.attacker != null) {
            MobDef def = ArmorUtil.def(ctx.attacker);
            return ArmorUtil.seaCreature(def) && Worn.of(victim).peaceTreaty();
        }
        if (attacker != null && victim == null && ctx.victim != null) {
            MobDef def = ArmorUtil.def(ctx.victim);
            return ArmorUtil.seaCreature(def) && Worn.of(attacker).peaceTreaty();
        }
        return false;
    }

    // ================================================================================ Spooky

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPunch(PrePlayerAttackEntityEvent e) {
        if (!ArmorDials.ENABLED.get() || !(e.getAttacked() instanceof Player victim)) return;
        Player p = e.getPlayer();
        if (victim.equals(p) || !Sky.profiles().isReady(p)) return;
        if (!GREAT_SPOOK_STAFF.equals(Sky.items().idOf(p.getInventory().getItemInMainHand()))) return;
        if (!Worn.of(p).full(GREAT_SPOOK)) return;
        long now = System.currentTimeMillis();
        Long last = SPOOKED.get(p.getUniqueId());
        if (last != null && now - last < ArmorDials.SPOOKY_COOLDOWN.get() * 1000L) {
            Sky.vitals().flash(p, "&cSpooker is on cooldown for " + Math.max(1, (ArmorDials.SPOOKY_COOLDOWN.get() * 1000L - (now - last)) / 1000) + "s.", 30);
            return;
        }
        SPOOKED.put(p.getUniqueId(), now);
        spook(p, victim);
    }

    // ================================================================================ Spook (staff, right click)

    private static final Map<UUID, org.bukkit.entity.BlockDisplay> DISGUISED = new HashMap<>();

    /** The staff's RIGHT CLICK: other players see a Dead Bush where you stand for {@code armor.spook-seconds}. */
    static boolean disguise(Player p) {
        if (DISGUISED.containsKey(p.getUniqueId())) return false;
        org.bukkit.Location at = p.getLocation().getBlock().getLocation();
        org.bukkit.entity.BlockDisplay bush = p.getWorld().spawn(at, org.bukkit.entity.BlockDisplay.class, d -> {
            d.setBlock(org.bukkit.Material.DEAD_BUSH.createBlockData());
            d.setPersistent(false);
            d.setTeleportDuration(2);
        });
        p.hideEntity(Sky.plugin(), bush);
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) o.hidePlayer(Sky.plugin(), p);
        DISGUISED.put(p.getUniqueId(), bush);
        p.playSound(p.getLocation(), Sound.BLOCK_GRASS_PLACE, 1f, 0.8f);
        long ticks = Math.max(1, ArmorDials.SPOOK_SECONDS.get()) * 20L;
        org.bukkit.scheduler.BukkitTask follow = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            if (p.isOnline() && bush.isValid() && bush.getWorld() == p.getWorld()) {
                org.bukkit.Location l = p.getLocation();
                bush.teleport(new org.bukkit.Location(l.getWorld(), l.getX() - 0.5, l.getY(), l.getZ() - 0.5));
            }
        }, 1L, 1L);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { follow.cancel(); undisguise(p.getUniqueId()); }, ticks);
        return true;
    }

    static void undisguise(UUID id) {
        org.bukkit.entity.BlockDisplay bush = DISGUISED.remove(id);
        if (bush != null && bush.isValid()) bush.remove();
        Player p = Bukkit.getPlayer(id);
        if (p != null) for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) o.showPlayer(Sky.plugin(), p);
    }

    static boolean disguised(Player p) { return DISGUISED.containsKey(p.getUniqueId()); }

    /** A player who joins mid-disguise must not see the disguised player either. */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        for (UUID id : DISGUISED.keySet()) {
            Player d = Bukkit.getPlayer(id);
            if (d != null && !d.equals(e.getPlayer())) e.getPlayer().hidePlayer(Sky.plugin(), d);
        }
    }

    static void spook(Player by, Player victim) {
        victim.showTitle(Title.title(Text.c("&5&lBOO!"), Text.c("&7" + by.getName() + " &7spooked you!"),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(900), Duration.ofMillis(300))));
        victim.playSound(victim.getLocation(), Sound.ENTITY_GHAST_SCREAM, 0.8f, 0.8f);
        victim.getWorld().spawnParticle(Particle.SOUL, victim.getLocation().add(0, 1.2, 0), 25, 0.4, 0.6, 0.4, 0.03);
        by.playSound(by.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 0.6f, 1.2f);
    }

    // ================================================================================ Old Blood

    /** Sum of {@code EnchantDef.value(level)} of Feather Falling over the usable Old Dragon pieces. */
    static double oldFeatherFalling(Worn w) {
        EnchantDef ff = Sky.items().enchant("feather_falling");
        if (ff == null) return 0;
        double t = 0;
        for (int i = 0; i < 4; i++) {
            if (!w.usable[i] || w.defs[i] == null || !OLD.equals(w.defs[i].setId)) continue;
            ItemData d = Sky.items().data(w.stacks[i]);
            int lvl = d == null ? 0 : d.enchant("feather_falling");
            if (lvl > 0) t += ff.value(lvl);
        }
        return t;
    }

    static void oldBloodFall(Player p) {
        double extra = 0;
        try {
            if (ArmorDials.ENABLED.get() && Sky.profiles().isReady(p)) {
                Worn w = Worn.of(p);
                if (w.full(OLD)) extra = SetMath.oldBloodSafeFall(oldFeatherFalling(w), ArmorDials.OLD_BOOST.get());
            }
        } catch (RuntimeException ignored) { }
        setFall(p, extra);
    }

    static void setFall(Player p, double amount) {
        AttributeInstance a = p.getAttribute(Attribute.SAFE_FALL_DISTANCE);
        if (a == null) return;
        AttributeModifier cur = a.getModifier(OLD_FALL_KEY);
        if (cur != null && Math.abs(cur.getAmount() - amount) < 1e-6) return;
        if (cur != null) a.removeModifier(OLD_FALL_KEY);
        if (amount > 1e-6) a.addTransientModifier(new AttributeModifier(OLD_FALL_KEY, amount, AttributeModifier.Operation.ADD_NUMBER));
    }

    static double fallModifier(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.SAFE_FALL_DISTANCE);
        AttributeModifier m = a == null ? null : a.getModifier(OLD_FALL_KEY);
        return m == null ? 0 : m.getAmount();
    }

    // ================================================================================ cleanup

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        SPOOKED.remove(e.getPlayer().getUniqueId());
        undisguise(e.getPlayer().getUniqueId());
        BatSwarm.forget(e.getPlayer().getUniqueId());
        setFall(e.getPlayer(), 0);
    }

    static void disable() {
        for (Player p : Bukkit.getOnlinePlayers()) setFall(p, 0);
        SPOOKED.clear();
        for (UUID id : new java.util.ArrayList<>(DISGUISED.keySet())) undisguise(id);
        BatSwarm.clear();
        NetherLord.revertAll();
    }

    /** For /sb armor info. */
    static String describe(Player p) {
        Worn w = Worn.of(p);
        return "Magma Lord tier " + w.magmaLord() + (w.lordsBlessing() ? " (Lord's Blessing)" : w.fireproof() ? " (Fireproof)" : "")
                + ", Peace Treaty " + (w.peaceTreaty() ? "ON" : "off")
                + ", bats: " + (BatSwarm.damageFor(w) > 0 ? BatSwarm.describe(p) : "-")
                + ", Old Blood safe fall +" + Math.round(fallModifier(p) * 10) / 10.0
                + ", obsidian live " + NetherLord.live();
    }
}
