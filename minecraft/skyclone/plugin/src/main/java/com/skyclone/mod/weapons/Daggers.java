package com.skyclone.mod.weapons;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The blaze-slayer daggers' second abilities (research 06):
 * <ul>
 *   <li><b>Burning Vengeance</b> (Ashen/Auric daggers): on hit, the last 8 hits of damage you dealt repeat
 *       after 6 s IF the demon's shield switched attunement meanwhile.</li>
 *   <li><b>Wraith Drain</b> (Spirit/Crystal daggers): on hit, heal 200 + 4% of max Health after 6 s on the
 *       same condition.</li>
 * </ul>
 * The shield itself is the slayer module's (it reads our {@code attunement} from the dagger). "The shield
 * switched" is observed the only way that does not need its internals: you can only keep damaging a demon whose
 * shield changed colour by changing the attunement you hit it with, so the effect fires when your hits on that
 * foe 6 s later carry a different attunement than the hit that started the timer. One timer per player at a
 * time (no stacking), and only against blaze-slayer mobs ({@code MobDef.slayer = "blaze"}).
 */
final class Daggers implements Listener {

    private static final int HITS = 8;
    private static final long DELAY_MS = 6000;

    private final Traits traits;
    /** Last hits (final damage) per player, newest last. */
    private final Map<UUID, Deque<Double>> recent = new HashMap<>();
    /** The attunement of each player's most recent dagger hit on a blaze-slayer foe. */
    private final Map<UUID, String> lastAttune = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();

    private final Buffs buffs;

    Daggers(Traits traits, Buffs buffs) { this.traits = traits; this.buffs = buffs; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Player p = ctx.playerAttacker();
        if (p == null || ctx.kind != DamageContext.Kind.MELEE || ctx.cancelled || ctx.victim == null) return;
        String id = Traits.idOf(ctx.weapon);
        if (id == null || !traits.owns(id)) return;
        Trait t = traits.of(id);
        if (!t.vengeance && !t.wraith) return;
        MobDef def = Sky.mobs().defOf(ctx.victim);
        if (def == null || def.slayer == null || !def.slayer.toLowerCase(Locale.ROOT).contains("blaze")) return;
        String att = attunement(ctx.weapon);
        UUID pid = p.getUniqueId();
        lastAttune.put(pid, att);
        Deque<Double> q = recent.computeIfAbsent(pid, k -> new ArrayDeque<>());
        q.addLast(Math.max(0, ctx.finalDamage));
        while (q.size() > HITS) q.removeFirst();
        if (!pending.add(pid)) return;
        LivingEntity target = ctx.victim;
        boolean vengeance = t.vengeance;
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            pending.remove(pid);
            Player pl = Bukkit.getPlayer(pid);
            if (pl == null) return;
            String now = lastAttune.get(pid);
            if (now == null || now.equals(att)) return;   // the shield never changed colour
            if (vengeance) {
                if (!target.isValid() || target.isDead()) return;
                double sum = WeaponMath.vengeance(recent.getOrDefault(pid, new ArrayDeque<>()));
                if (sum <= 0) return;
                Fx.burst(target.getLocation().add(0, 1, 0), Particle.FLAME, 25, 0.5);
                Fx.sound(target.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 0.7f);
                Sky.combat().rawDamage(target, sum, pl, DamageContext.Kind.TRUE);
                Sky.vitals().flash(pl, "&6Burning Vengeance! &c" + Text.commasInt(sum), 30);
            } else {
                double heal = WeaponMath.wraithDrain(Sky.vitals().maxHealth(pl));
                Sky.vitals().heal(pl, heal);
                Fx.burst(pl.getLocation().add(0, 1, 0), Particle.SOUL, 15, 0.4);
                Sky.vitals().flash(pl, "&bWraith Drain! &a+" + Text.commasInt(heal) + "❤", 30);
            }
        }, DELAY_MS / 50);
    }

    /**
     * Soul Howl (katanas): +200/300/400 Ferocity against Endermen for 4 s. Core's Ferocity is a player stat with
     * no per-target hook, so the bonus strikes are ours: each melee hit on an Enderman while it holds rolls
     * {@link WeaponMath#ferocityStrikes} extra strikes of the same damage, 0.2 s apart, as FEROCITY damage.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSoulcry(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Player p = ctx.playerAttacker();
        if (p == null || ctx.kind != DamageContext.Kind.MELEE || ctx.cancelled || ctx.victim == null || buffs == null) return;
        Double fer = buffs.flagValue(p, "soulcry");
        if (fer == null || fer <= 0 || !MobTypes.is("enderman", ctx.victim)) return;
        int n = WeaponMath.ferocityStrikes(fer, Math.random());
        double dmg = ctx.finalDamage;
        LivingEntity v = ctx.victim;
        for (int i = 1; i <= n; i++) {
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (!v.isValid() || v.isDead() || !p.isOnline()) return;
                Sky.combat().rawDamage(v, dmg, p, DamageContext.Kind.FEROCITY);
                Fx.sound(v.getLocation(), Sound.ITEM_TRIDENT_HIT, 0.6f, 1.6f);
            }, 4L * i);
        }
    }

    static String attunement(ItemStack s) {
        try {
            JsonElement e = Sky.items().data(s).extra.get("attunement");
            if (e != null) return e.getAsString().toLowerCase(Locale.ROOT);
            ItemDef d = Sky.items().def(Traits.idOf(s));
            if (d != null && d.extra != null && d.extra.has("attunement")) return d.extra.get("attunement").getAsString().toLowerCase(Locale.ROOT);
        } catch (RuntimeException ignored) { }
        return "";
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        recent.remove(id);
        lastAttune.remove(id);
    }
}
