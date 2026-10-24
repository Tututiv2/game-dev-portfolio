package com.skyclone.mod.dungeons.boss;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.mob.MobDef;
import com.skyclone.mod.dungeons.DDials;
import com.skyclone.mod.dungeons.Fx;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.ext.BossContext;
import com.skyclone.mod.dungeons.gen.arena.MirrorArena;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Floor V / Master V: Lurid (research 04 sec 3.13, Livid). Alone for 16 s, then a flash of blindness and eight
 * copies, each with its own nametag colour and kit (Vendetta teleports and stabs, Crossed blinds, Hockey charges,
 * Doctor splashes true damage, Frog poisons, Smile slows, Scream knocks back, Arcade shoots). The real one's colour
 * is the wool block in the ceiling well. Killing a copy does nothing: it springs back to full and heals the others by
 * 25% (and on Master Mode they all hit harder). Only the real Lurid's death ends the fight.
 */
public final class LuridFight extends FightBase {

    public static final String NAME = "Lurid";
    static final String[] COLOURS = {"red", "green", "blue", "yellow", "purple", "gray", "pink", "white", "cyan"};
    static final String[] CODES = {"&c", "&a", "&9", "&e", "&5", "&7", "&d", "&f", "&3"};
    static final Material[] WOOL = {Material.RED_WOOL, Material.LIME_WOOL, Material.BLUE_WOOL, Material.YELLOW_WOOL, Material.PURPLE_WOOL,
            Material.GRAY_WOOL, Material.PINK_WOOL, Material.WHITE_WOOL, Material.CYAN_WOOL};
    static final String[] KITS = {"vendetta", "frog", "crossed", "hockey", "doctor", "smile", "scream", "arcade", "vendetta"};

    static final class S {
        LivingEntity real;
        int realColour;
        final Map<LivingEntity, Integer> copies = new LinkedHashMap<>();
        boolean split;
        long splitAt;
        double rage = 1;
    }

    @Override public BossArena arena() { return MirrorArena.INSTANCE; }

    @Override
    public void start(BossContext c) {
        S s = st(c, S::new);
        s.realColour = rng.nextInt(COLOURS.length);
        Location wool = c.point("ceiling.wool");
        if (wool != null) block(wool).setType(WOOL[s.realColour], false);
        s.real = spawnBoss(c, "dboss_lurid_" + COLOURS[s.realColour], c.point("boss"), bossHp(c), bossDmg(c));
        if (s.real == null) { c.complete(); return; }
        s.splitAt = c.steps() + DDials.LURID_DELAY.get() * 4L;
        Kit.announce(c, CODES[s.realColour] + "&lLURID", "&7Remember this face.", Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR);
        Kit.dialogue(c, NAME, List.of("Welcome to my hall of mirrors. Do try to keep track of me.",
                "Which one of us is real? Even I forget sometimes."), null);
    }

    @Override
    public void tick(BossContext c) {
        S s = st(c, S::new);
        if (s.real == null || !Kit.alive(s.real)) return;
        long t = c.steps();
        if (!s.split && t >= s.splitAt) split(c, s);
        c.bossBar(CODES[s.realColour] + "&l" + NAME + " &7- &a" + com.skyclone.api.ui.Text.shortNum(Kit.hp(s.real)) + "&c❤"
                + (s.split ? " &8| &7Which one is real? &8(look up)" : ""), (float) Kit.frac(s.real), BossBar.Color.PURPLE);
        if (t % 12 == 0) act(c, s, s.real, s.realColour);
        if (s.split && t % 12 == 6) for (Map.Entry<LivingEntity, Integer> en : new ArrayList<>(s.copies.entrySet())) act(c, s, en.getKey(), en.getValue());
    }

    private void split(BossContext c, S s) {
        s.split = true;
        for (Player p : c.players()) Kit.effect(p, PotionEffectType.BLINDNESS, 30, 0);
        Kit.announce(c, "&5&lTHE MIRRORS SHATTER", "&7Which Lurid is real? &eLook up.", Sound.ENTITY_ILLUSIONER_MIRROR_MOVE);
        List<Integer> spots = new ArrayList<>();
        for (int i = 0; i < 9; i++) spots.add(i);
        java.util.Collections.shuffle(spots, rng);
        s.real.teleport(c.point("clone." + spots.get(0)));
        int k = 1;
        for (int col = 0; col < COLOURS.length; col++) {
            if (col == s.realColour) continue;
            LivingEntity e = c.spawnExact("dboss_lurid_" + COLOURS[col], c.point("clone." + spots.get(k++ % 9)), bossHp(c) / Kit.hpScale(), bossDmg(c) * Kit.meleeShare(), false);
            if (e != null) {
                Sky.mobs().setMaxHealth(e, Kit.max(s.real));
                Sky.mobs().setHealth(e, Kit.hp(s.real));
                s.copies.put(e, col);
            }
        }
    }

    private void act(BossContext c, S s, LivingEntity e, int colour) {
        if (!Kit.alive(e)) return;
        Player p = Kit.nearest(c, e.getLocation());
        if (p == null) return;
        double dmg = bossDmg(c) * s.rage;
        switch (KITS[colour]) {
            case "vendetta" -> {
                if (rng.nextInt(3) != 0) return;
                Vector back = p.getLocation().getDirection().setY(0).normalize().multiply(-1.5);
                e.teleport(p.getLocation().add(back));
                Fx.particle(e.getLocation(), Particle.PORTAL, 30, 0.5, 0.3);
                Kit.later(c, 6, () -> { if (Kit.alive(e) && e.getLocation().distance(p.getLocation()) < 3) Kit.hit(p, dmg * 0.8); });
            }
            case "crossed" -> { if (e.getLocation().distance(p.getLocation()) < 10) Kit.effect(p, PotionEffectType.BLINDNESS, 40, 0); }
            case "hockey" -> {
                Vector v = p.getLocation().toVector().subtract(e.getLocation().toVector()).setY(0);
                if (v.lengthSquared() > 0.1) e.setVelocity(v.normalize().multiply(1.7).setY(0.2));
                Kit.later(c, 8, () -> { if (Kit.alive(e)) for (Player q : Kit.within(c, e.getLocation(), 2.5)) { Kit.hit(q, dmg * 0.7); Kit.knock(q, e.getLocation(), 1.2, 0.4); } });
            }
            case "doctor" -> {
                Fx.particle(e.getLocation().add(0, 1, 0), Particle.WITCH, 30, 2, 0.05);
                for (Player q : Kit.within(c, e.getLocation(), 6)) Kit.pct(q, 0.05);
            }
            case "frog" -> { if (e.getLocation().distance(p.getLocation()) < 8) { Kit.effect(p, PotionEffectType.POISON, 100, 1); Kit.hit(p, dmg * 0.3); } }
            case "smile" -> { for (Player q : Kit.within(c, e.getLocation(), 6)) Kit.effect(q, PotionEffectType.SLOWNESS, 60, 1); }
            case "scream" -> {
                Kit.circle(c, e.getLocation(), 5, 14, Color.fromRGB(200, 60, 200), q -> { Kit.pct(q, 0.03); Kit.knock(q, e.getLocation(), 1.6, 0.6); }, null);
            }
            case "arcade" -> Kit.bolt(c, e.getEyeLocation(), p.getEyeLocation(), 1.3, 40, 1.0, Particle.CRIT, Color.WHITE, q -> Kit.hit(q, dmg * 0.6));
            default -> { }
        }
    }

    @Override
    public void onDamage(BossContext c, DamageContext dc) {
        S s = st(c, S::new);
        if (!(dc.victim instanceof LivingEntity le)) return;
        Integer col = s.copies.get(le);
        if (col == null) return;
        if (dc.finalDamage >= Kit.hp(le)) {
            dc.cancelled = true;
            healFull(le);
            for (LivingEntity o : s.copies.keySet()) if (Kit.alive(o) && !o.equals(le)) Sky.mobs().setHealth(o, Math.min(Kit.max(o), Kit.hp(o) + Kit.max(o) * 0.25));
            if (Kit.alive(s.real)) Sky.mobs().setHealth(s.real, Math.min(Kit.max(s.real), Kit.hp(s.real) + Kit.max(s.real) * 0.25));
            if (c.master()) s.rage += 0.15;
            Fx.particle(le.getLocation().add(0, 1, 0), Particle.ANGRY_VILLAGER, 10, 0.5, 0);
            c.title("&c&lWRONG LURID!", "&7The copies heal. Look at the ceiling.");
            Kit.sound(c, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.7f);
        }
    }

    @Override
    public void onMobKill(BossContext c, LivingEntity mob, MobDef def, Player killer) {
        S s = st(c, S::new);
        if (!is(s.real, mob)) return;
        for (LivingEntity e : s.copies.keySet()) if (e.isValid()) Sky.mobs().remove(e);
        s.copies.clear();
        win(c, NAME, "Impossible... you found... the real me...");
    }
}
