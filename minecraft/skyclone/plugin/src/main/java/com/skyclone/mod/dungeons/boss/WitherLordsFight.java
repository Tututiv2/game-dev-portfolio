package com.skyclone.mod.dungeons.boss;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.mod.dungeons.DDials;
import com.skyclone.mod.dungeons.Fx;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.ext.BossContext;
import com.skyclone.mod.dungeons.gen.arena.WitherArena;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Floor VII / Master VII: the four Wither Lords, then (Master Mode) the Hollow King (research 04 sec 3.13).
 * <ol>
 *   <li><b>Gravax</b> (Maxor, 100M / 800M): immune until lasered. Carry the two Energy Crystals from the ledges to the
 *       podiums; the laser pins him if he is near the centre. The first laser can only take him to 25%; at least two
 *       lasers are needed. Wither Guards and Miners join in.</li>
 *   <li><b>Tempestra</b> (Storm, 400M / 1B): stand on a corner pad and its crusher falls 3 s later; crush her under it
 *       to stun her for 10 s (full damage; otherwise she takes a quarter). She cannot die before two crushes. Giga
 *       Lightning: two bursts of 75% max-HP true damage for anyone not sheltering under an unused crusher (a held
 *       Wither Cloak Sword protects on normal mode). Used pads bring a Shade Stalker; all three used = they reset.</li>
 *   <li><b>Aurik</b> (Goldor, 750M / 1.2B): immune; he follows you down four corridors throwing greatswords. Each
 *       section's gate opens 5 s after its terminals (4, 5, 4, 4), both levers and its device are done. Behind the
 *       last gate is his core: there he is vulnerable.</li>
 *   <li><b>Morvane</b> (Necron, 1B / 1.4B): skull barrages, fire circles, lightning, flying swords, rotating beams that
 *       cost 25% max HP (jump them), and at 80% and 50% a frenzy whose blast radius grows unless he is being hit;
 *       each frenzy breaks a stretch of the platform into lava.</li>
 *   <li><b>The Hollow King</b> (M7): five relics to the five matching cauldrons (a wrong one fails the run); then a
 *       dragon wakes every 30 s at its statue. Killed near its own statue it shatters the statue; elsewhere it comes
 *       back. Five dragons alive at once ends the run. Five statues down and the King yields.</li>
 * </ol>
 */
public final class WitherLordsFight extends FightBase {

    static final String[] NAMES = {"", "Gravax", "Tempestra", "Aurik", "Morvane", "The Hollow King"};
    static final String[] IDS = {"", "dboss_gravax", "dboss_tempestra", "dboss_aurik", "dboss_morvane", "dboss_hollow_king"};
    static final double[] HP_N = {0, 100e6, 400e6, 750e6, 1e9}, HP_M = {0, 800e6, 1e9, 1.2e9, 1.4e9};
    static final double[] DMG = {0, 1.0, 1.1, 1.25, 1.5};
    static final String[] DRAGON_NAMES = {"Verdant", "Ember", "Frost", "Might", "Soul"};
    static final Color[] DRAGON_COLOURS = {Color.LIME, Color.ORANGE, Color.AQUA, Color.RED, Color.PURPLE};

    static final class S {
        int phase;
        LivingEntity lord;
        final Set<UUID> adds = new HashSet<>();
        // P1
        final boolean[] crystalHere = {false, false}, podium = {false, false};
        /** carrier -> ledge side of the crystal they hold. */
        final Map<UUID, Integer> crystalBy = new HashMap<>();
        int lasers;
        long laserUntil = -1;
        boolean trapped;
        // P2
        final boolean[] used = new boolean[3];
        final long[] armed = {-1, -1, -1};
        int crushes;
        long stunUntil = -1, resetAt = -1, lightningAt = -1;
        // P3
        int section = 1;
        final Map<Integer, Integer> termsDone = new HashMap<>();
        final Map<Integer, Integer> leversDone = new HashMap<>();
        final Map<Integer, Devices.Device> devices = new HashMap<>();
        final Set<String> termDone = new HashSet<>();
        long gateAt = -1;
        boolean core;
        // P4
        final Set<Integer> frenzies = new HashSet<>();
        long frenzyUntil = -1, beamsAt;
        double frenzyR;
        boolean hitThisSecond;
        // P5
        final Map<UUID, Integer> carrying = new HashMap<>();
        final Set<Integer> placed = new HashSet<>();
        final Map<Integer, LivingEntity> dragons = new LinkedHashMap<>();
        final Set<Integer> shattered = new HashSet<>();
        int nextDragon, kingAdds;
        long dragonAt = -1;
        final Map<Integer, Long> respawn = new HashMap<>();
    }

    @Override public BossArena arena() { return WitherArena.INSTANCE; }

    static double lordHp(BossContext c, int n) { return (c.master() ? HP_M[n] : HP_N[n]) * Kit.hpScale() * Kit.partyHp(c); }

    static double lordDmg(BossContext c, int n) { return bossDmg(c) * DMG[Math.max(1, Math.min(4, n))]; }

    @Override
    public void start(BossContext c) {
        S s = st(c, S::new);
        Kit.announce(c, "&8&lTHE WITHERED VAULT", "&7Four lords stand between you and the light.", Sound.ENTITY_WITHER_SPAWN);
        Kit.dialogue(c, NAMES[1], List.of("Another party of grave-robbers. The Warden warned us you would come.",
                "Let us see what you are made of. Guards!"), () -> enter(c, s, 1));
    }

    private void enter(BossContext c, S s, int n) {
        s.phase = n;
        for (UUID u : s.adds) { Entity e = org.bukkit.Bukkit.getEntity(u); if (e instanceof LivingEntity le && le.isValid()) Sky.mobs().remove(le); }
        s.adds.clear();
        if (n > 1) { for (Player p : c.players()) Hooks.stripCarried(p, "crystal"); s.crystalBy.clear(); }
        Location sp = c.point("p" + n + ".spawn");
        if (n > 1 && sp != null) Kit.teleportAll(c, sp);
        Location at = c.point(n == 5 ? "p5.king" : "p" + n + ".boss");
        if (n == 5) {
            s.lord = c.spawnExact(IDS[5], at, 1, bossDmg(c) * 1.5);
            if (s.lord != null) { Kit.freeze(s.lord, true); s.lord.setGravity(false); }   // he sits on his throne; his pull reads p5.king
            Kit.announce(c, "&4&lTHE HOLLOW KING", "&7Return the relics to their cauldrons.", Sound.ENTITY_WITHER_DEATH);
            setupRelics(c, s);
            return;
        }
        s.lord = spawnBoss(c, IDS[n], at, lordHp(c, n), lordDmg(c, n));
        if (s.lord == null) { c.complete(); return; }
        Kit.scale(s.lord, n == 4 ? 2.4 : 1.8);
        switch (n) {
            case 1 -> { Kit.announce(c, "&5&lGRAVAX", "&7Carry the crystals to the podiums!", Sound.ENTITY_WITHER_AMBIENT); setupCrystals(c, s); }
            case 2 -> {
                Kit.announce(c, "&b&lTEMPESTRA", "&7Stand on a pad to drop its crusher on her.", Sound.ENTITY_LIGHTNING_BOLT_THUNDER);
                c.bossSay(NAMES[2], "Gravax always was the weak one. I am the storm itself!");
                s.lightningAt = c.steps() + 60;
            }
            case 3 -> {
                Kit.announce(c, "&6&lAURIK", "&7Terminals, levers, devices - open every gate.", Sound.ENTITY_IRON_GOLEM_HURT);
                c.bossSay(NAMES[3], "Nothing gets past me. Nothing has EVER got past me.");
                setupGauntlet(c, s);
            }
            case 4 -> {
                Kit.freeze(s.lord, true);
                s.lord.setGravity(false);
                s.lord.teleport(at.clone().add(0, 1.5, 0));
                Kit.announce(c, "&4&lMORVANE", "&7The Warden himself.", Sound.ENTITY_WITHER_SPAWN);
                c.bossSay(NAMES[4], "You have come far. You will go no further.");
                s.beamsAt = c.steps() + 40;
                // wave 5 (part B "not built": the platform-lifting cutscene): he lifts the party over his lava-ringed
                // platform and lets them float down onto it - the arrival grace covers the landing
                for (Player p : c.alive()) {
                    Kit.effect(p, org.bukkit.potion.PotionEffectType.LEVITATION, 30, 1);
                    Kit.effect(p, org.bukkit.potion.PotionEffectType.SLOW_FALLING, 120, 0);
                    Fx.particle(p.getLocation().add(0, 1, 0), Particle.SOUL, 20, 0.6, 0.02);
                }
                c.title("&4&lMORVANE", "&7\"Let me show you where you will die.\"");
            }
            default -> { }
        }
    }

    private void nextLord(BossContext c, S s) {
        int n = s.phase + 1;
        if (n == 5 && !c.master()) { win(c, NAMES[4], "The vault... is... yours..."); return; }
        s.phase = -n;
        c.title("&7The floor gives way...", "&8" + (n == 5 ? "The throne room" : NAMES[n] + " awaits"));
        Kit.sound(c, Sound.BLOCK_END_PORTAL_SPAWN, 0.6f);
        Kit.later(c, 50, () -> enter(c, s, n));
    }

    @Override
    public void tick(BossContext c) {
        S s = st(c, S::new);
        long t = c.steps();
        switch (s.phase) {
            case 1 -> gravax(c, s, t);
            case 2 -> tempestra(c, s, t);
            case 3 -> aurik(c, s, t);
            case 4 -> morvane(c, s, t);
            case 5 -> king(c, s, t);
            default -> { }
        }
    }

    // ======================================================================================================
    // P1 Gravax
    // ======================================================================================================

    private void setupCrystals(BossContext c, S s) {
        for (int i = 0; i < 2; i++) {
            spawnCrystal(c, s, i);
            final int side = i;
            Location pod = c.point("p1.podium." + (i == 0 ? "a" : "b"));
            if (pod != null) {
                Hooks.label(c, pod.clone().add(0, 1.6, 0), "&dEnergy Podium &7(right-click with a crystal)");
                Hooks.onBlock(c, block(pod), (p, b, a) -> { podium(c, s, p, side); return true; });
            }
        }
    }

    private void spawnCrystal(BossContext c, S s, int side) {
        Location at = c.point("p1.crystal." + (side == 0 ? "a" : "b"));
        if (at == null) return;
        s.crystalHere[side] = true;
        final Entity[] box = new Entity[1];
        box[0] = Hooks.clickableItem(c, at, new ItemStack(Material.END_CRYSTAL), "&dEnergy Crystal &7(right-click)", 1.2f, (p, e) -> {
            if (!s.crystalHere[side]) return;
            if (s.crystalBy.containsKey(p.getUniqueId())) { Msg.error(p, "You are already carrying a crystal."); return; }
            s.crystalHere[side] = false;
            s.crystalBy.put(p.getUniqueId(), side);
            // review fix: a tagged vanilla stack, not the MAXOR_ENERGY_CRYSTAL sky item through Sky.loot() (a sack could
            // swallow it and soft-lock the podiums; it could also leave the run in a quitter's inventory)
            give(p, Hooks.carried(Material.END_CRYSTAL, "&dEnergy Crystal", List.of("&7Right-click an &dEnergy Podium &7with it."), "crystal"));
            for (Entity near : e.getNearbyEntities(0.5, 2.5, 0.5)) if (near instanceof org.bukkit.entity.Display) near.remove();
            e.remove();
            c.broadcast("&b" + p.getName() + " &dpicked up an Energy Crystal!");
        });
    }

    /** Give a carried item; with a full inventory, a hotbar slot is emptied for it (its item dropped for the player). */
    static void give(Player p, ItemStack it) {
        if (p.getInventory().addItem(it).isEmpty()) return;
        int slot = p.getInventory().getHeldItemSlot();
        ItemStack old = p.getInventory().getItem(slot);
        if (com.skyclone.mod.dungeons.run.RunManager.isMap(old)) { slot = (slot + 1) % 9; old = p.getInventory().getItem(slot); }
        p.getInventory().setItem(slot, it);
        if (old != null && !old.getType().isAir()) Sky.loot().dropFor(p, p.getLocation(), old);
    }

    /** Crystal carriers who died, quit or left: their crystal goes back to its ledge (no soft-lock). */
    private void checkCrystalCarriers(BossContext c, S s) {
        List<Player> alive = c.alive();
        for (Map.Entry<UUID, Integer> en : new ArrayList<>(s.crystalBy.entrySet())) {
            Player q = org.bukkit.Bukkit.getPlayer(en.getKey());
            if (q != null && alive.contains(q) && Hooks.countCarried(q, "crystal") > 0) continue;
            s.crystalBy.remove(en.getKey());
            if (q != null) Hooks.stripCarried(q, "crystal");
            int side = en.getValue();
            if (!s.crystalHere[side]) { spawnCrystal(c, s, side); c.broadcast("&dAn Energy Crystal returns to its ledge."); }
        }
    }

    private void podium(BossContext c, S s, Player p, int side) {
        if (s.podium[side]) { Msg.error(p, "This podium is already powered."); return; }
        if (!s.crystalBy.containsKey(p.getUniqueId()) || Hooks.countCarried(p, "crystal") < 1) { Msg.error(p, "Bring an Energy Crystal from a ledge."); return; }
        s.crystalBy.remove(p.getUniqueId());
        Hooks.stripCarried(p, "crystal");
        s.podium[side] = true;
        Location pod = c.point("p1.podium." + (side == 0 ? "a" : "b"));
        block(pod.clone().add(0, 1, 0)).setType(Material.END_ROD, false);
        c.broadcast("&b" + p.getName() + " &dpowered a podium! &7(" + ((s.podium[0] ? 1 : 0) + (s.podium[1] ? 1 : 0)) + "/2)");
        Kit.sound(c, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f);
        if (s.podium[0] && s.podium[1]) {
            s.laserUntil = c.steps() + 60;
            s.trapped = false;
            Kit.announce(c, "&d&lLASER ONLINE", "&7Lure Gravax into the centre!", Sound.BLOCK_BEACON_ACTIVATE);
        }
    }

    private void gravax(BossContext c, S s, long t) {
        if (!Kit.alive(s.lord)) return;
        checkCrystalCarriers(c, s);
        boolean vuln = s.trapped && t < s.laserUntil;
        Kit.bar(c, NAMES[1], s.lord, vuln ? "&a&lVULNERABLE" : "&7Lasers: " + s.lasers, vuln ? BossBar.Color.GREEN : BossBar.Color.PURPLE);
        Location centre = c.point("p1.laser");
        if (s.laserUntil >= 0) {
            if (t % 2 == 0) for (String k : new String[]{"a", "b"}) {
                Location pod = c.point("p1.podium." + k);
                if (pod != null && centre != null) Fx.line(pod.clone().add(0, 1.5, 0), centre.clone().add(0, 1.5, 0), Color.fromRGB(220, 60, 255), 0.5);
            }
            if (!s.trapped && centre != null && s.lord.getLocation().distance(centre) < 7) {
                s.trapped = true;
                s.lord.teleport(centre);
                Kit.freeze(s.lord, true);
                Kit.announce(c, "&a&lGRAVAX IS TRAPPED", "&7Hit him now!", Sound.ENTITY_WITHER_HURT);
            }
            if (t >= s.laserUntil) {
                if (s.trapped) s.lasers++;
                s.laserUntil = -1;
                s.trapped = false;
                Kit.freeze(s.lord, false);
                s.podium[0] = s.podium[1] = false;
                for (String k : new String[]{"a", "b"}) { Location pod = c.point("p1.podium." + k); if (pod != null) block(pod.clone().add(0, 1, 0)).setType(Material.AIR, false); }
                c.broadcast("&dThe laser powers down. &7The crystals will return in 5 seconds.");
                Kit.later(c, 100, () -> {
                    for (int side = 0; side < 2; side++) if (!s.crystalHere[side] && !s.crystalBy.containsValue(side)) spawnCrystal(c, s, side);
                });
            }
        }
        if (t % 12 == 0) {
            Player p = pick(c, rng);
            if (p != null) Kit.bolt(c, s.lord.getEyeLocation(), p.getEyeLocation(), 1.2, 50, 1.2, Particle.SMOKE, Color.BLACK, q -> Kit.hit(q, lordDmg(c, 1) * 0.6));
        }
        adds(c, s, t, 60, 4);
    }

    private void adds(BossContext c, S s, long t, int every, int max) {
        if (t % every != every / 2) return;
        s.adds.removeIf(u -> { Entity e = org.bukkit.Bukkit.getEntity(u); return e == null || !e.isValid(); });
        if (s.adds.size() >= max) return;
        boolean miner = rng.nextBoolean();
        Location at = s.lord.getLocation().clone().add(rng.nextInt(9) - 4, 0, rng.nextInt(9) - 4);
        LivingEntity e = c.spawnExact(miner ? "dboss_wither_miner" : "dboss_wither_guard", at,
                miner ? Kit.nm(c, 10e6, 300e6) : Kit.nm(c, 3.5e6, 300e6), miner ? Kit.nm(c, 14_460, 180_000) : Kit.nm(c, 20_000, 225_000));
        if (e != null) s.adds.add(e.getUniqueId());
    }

    // ======================================================================================================
    // P2 Tempestra
    // ======================================================================================================

    private void tempestra(BossContext c, S s, long t) {
        if (!Kit.alive(s.lord)) return;
        boolean stunned = t < s.stunUntil;
        Kit.bar(c, NAMES[2], s.lord, stunned ? "&a&lCRUSHED" : "&7Crushes: " + s.crushes + "/" + DDials.STORM_CRUSHES.get(),
                stunned ? BossBar.Color.GREEN : BossBar.Color.BLUE);
        if (!stunned && s.stunUntil > 0) { Kit.freeze(s.lord, false); s.stunUntil = 0; }
        // pads
        for (int i = 0; i < 3; i++) {
            Location pad = c.point("p2.pad." + i), zone = c.point("p2.crusher." + i);
            if (pad == null || zone == null) continue;
            if (!s.used[i]) Fx.ring(pad, 1.4, Color.LIME, 10);
            if (!s.used[i] && s.armed[i] < 0)
                for (Player p : c.alive())
                    if (Kit.flat(p.getLocation(), pad) < 1.7 && Math.abs(p.getLocation().getY() - pad.getY()) < 1.5) {
                        s.armed[i] = t + 12;
                        c.title("&e⚠ CRUSHER " + (i + 1), "&7drops in 3 seconds!");
                        Kit.sound(c, Sound.BLOCK_PISTON_EXTEND, 0.5f);
                        LivingEntity sh = c.spawnExact("dboss_shade_stalker", pad.clone().add(2, 0, 2), Kit.nm(c, 20e6, 200e6), Kit.nm(c, 18_000, 270_000));
                        if (sh != null) s.adds.add(sh.getUniqueId());
                        break;
                    }
            if (s.armed[i] >= 0) {
                Fx.ring(zone, 3.2, Color.RED, 30);
                if (t >= s.armed[i]) { s.armed[i] = -1; s.used[i] = true; crush(c, s, zone, t); }
            }
        }
        boolean allUsed = s.used[0] && s.used[1] && s.used[2];
        if (allUsed && s.resetAt < 0) s.resetAt = t + 40;
        if (s.resetAt >= 0 && t >= s.resetAt) {
            s.resetAt = -1;
            java.util.Arrays.fill(s.used, false);
            c.broadcast("&7The crushers grind back into place.");
        }
        // Giga Lightning
        if (s.lightningAt >= 0 && t == s.lightningAt) {
            c.title("&e&l⚡ GIGA LIGHTNING ⚡", "&7Get under an unused crusher!");
            Kit.sound(c, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f);
        }
        if (s.lightningAt >= 0 && t >= s.lightningAt + 12) {
            s.lightningAt = t + 80;
            gigaLightning(c, s);
            Kit.later(c, 10, () -> gigaLightning(c, s));
        }
        if (t % 16 == 0) {
            Player p = pick(c, rng);
            if (p != null) Kit.bolt(c, s.lord.getEyeLocation(), p.getEyeLocation(), 1.0, 50, 1.4, Particle.FLAME, Color.ORANGE, q -> Kit.hit(q, lordDmg(c, 2) * 0.6));
        }
    }

    private void crush(BossContext c, S s, Location zone, long t) {
        Location a = zone.clone().add(-2, 3, -2), b = zone.clone().add(2, 8, 2);
        BlockData stone = Material.DIORITE.createBlockData();
        fill(a, b, stone);
        Fx.particle(zone.clone().add(0, 3, 0), Particle.BLOCK, 80, 2, 0.1, Material.DIORITE);
        Kit.sound(c, Sound.BLOCK_ANVIL_LAND, 0.5f);
        for (Player p : c.alive()) if (Kit.flat(p.getLocation(), zone) < 3.2 && p.getLocation().getY() < zone.getY() + 3) { Kit.pct(p, 0.4); Kit.knock(p, zone, 1.6, 0.4); }
        if (Kit.flat(s.lord.getLocation(), zone) < 4) {
            s.crushes++;
            s.stunUntil = t + 40;
            s.lord.teleport(zone);
            Kit.freeze(s.lord, true);
            Kit.announce(c, "&a&lTEMPESTRA CRUSHED!", "&7She is stunned - full damage for 10s!", Sound.ENTITY_WITHER_HURT);
        } else c.broadcast("&cThe crusher missed Tempestra!");
        Kit.later(c, 20, () -> fill(a, b, Material.AIR.createBlockData()));
    }

    private void gigaLightning(BossContext c, S s) {
        for (Player p : c.alive()) {
            boolean safe = false;
            for (int i = 0; i < 3; i++) {
                Location zone = c.point("p2.crusher." + i);
                if (zone != null && !s.used[i] && Kit.flat(p.getLocation(), zone) < 3.2) safe = true;
            }
            if (!c.master() && "WITHER_CLOAK".equals(Sky.items().idOf(p.getInventory().getItemInMainHand()))) safe = true;
            p.getWorld().strikeLightningEffect(p.getLocation());
            if (!safe) Kit.pct(p, 0.75);
        }
    }

    // ======================================================================================================
    // P3 Aurik: terminals, levers, devices, gates
    // ======================================================================================================

    private void setupGauntlet(BossContext c, S s) {
        for (int sec = 1; sec <= 4; sec++) {
            final int fs = sec;
            int terms = WitherArena.TERMS[sec - 1].length;
            for (int i = 0; i < terms; i++) {
                Location at = c.point("p3.s" + sec + ".term." + i);
                if (at == null) continue;
                final String key = sec + ":" + i;
                TextDisplay label = Hooks.label(c, at.clone().add(0, 2.2, 0), "&c&lTerminal &7(inactive)");
                Hooks.clickableItem(c, at, new ItemStack(Material.COMMAND_BLOCK), null, 0.7f, (p, e) -> {
                    if (s.termDone.contains(key)) { Msg.send(p, "&aThis terminal is already active."); return; }
                    Terminals.open(p, () -> {
                        if (!Kit.live(c) || s.phase != 3 || !s.termDone.add(key)) return;   // solved after the fight moved on
                        s.termsDone.merge(fs, 1, Integer::sum);
                        Hooks.relabel(label, "&a&lTerminal &7(active)");
                        c.broadcast("&b" + p.getName() + " &aactivated a terminal! &7(" + s.termsDone.get(fs) + "/" + terms + " in section " + fs + ")");
                        progress(c, s);
                    });
                });
            }
            for (int l = 0; l < 2; l++) {
                Location at = c.point("p3.s" + sec + ".lever." + l);
                if (at == null) continue;
                Block b = block(at);
                b.setBlockData(org.bukkit.Bukkit.createBlockData("lever[face=wall,facing=south,powered=false]"), false);
                Hooks.onBlock(c, b, (p, blk, a) -> {
                    BlockData d = blk.getBlockData();
                    if (d instanceof Powerable pw && !pw.isPowered()) {
                        pw.setPowered(true);
                        blk.setBlockData(d, false);
                        s.leversDone.merge(fs, 1, Integer::sum);
                        Msg.sound(p, Sound.BLOCK_LEVER_CLICK, 0.6f);
                        c.broadcast("&b" + p.getName() + " &apulled a lever! &7(" + s.leversDone.get(fs) + "/2 in section " + fs + ")");
                        progress(c, s);
                    }
                    return true;
                });
            }
            Location dev = c.point("p3.s" + sec + ".device");
            if (dev != null) s.devices.put(sec, Devices.create(sec, c, dev, () -> progress(c, s)));
        }
        c.broadcast("&6Section 1: &7" + WitherArena.TERMS[0].length + " terminals, 2 levers and the &eSimon Says &7device open the first gate.");
    }

    private boolean sectionDone(S s, int sec) {
        Devices.Device d = s.devices.get(sec);
        return s.termsDone.getOrDefault(sec, 0) >= WitherArena.TERMS[sec - 1].length && s.leversDone.getOrDefault(sec, 0) >= 2 && (d == null || d.done);
    }

    private void progress(BossContext c, S s) {
        if (s.gateAt < 0 && !s.core && sectionDone(s, s.section)) {
            s.gateAt = c.steps() + 20;
            c.title("&6Section " + s.section + " complete!", "&7The gate opens in 5 seconds.");
        }
    }

    private void aurik(BossContext c, S s, long t) {
        if (!Kit.alive(s.lord)) return;
        Kit.bar(c, NAMES[3], s.lord, s.core ? "&a&lCORE EXPOSED" : "&7Section " + s.section + "/4", s.core ? BossBar.Color.GREEN : BossBar.Color.YELLOW);
        if (s.gateAt >= 0 && t >= s.gateAt) {
            s.gateAt = -1;
            fillBox(c, "p3.s" + s.section + ".gate.a", "p3.s" + s.section + ".gate.b", Material.AIR);
            Kit.sound(c, Sound.BLOCK_IRON_DOOR_OPEN, 0.6f);
            if (s.section >= 4) {
                s.core = true;
                Location core = c.point("p3.core");
                if (core != null) s.lord.teleport(core);
                Kit.announce(c, "&6&lAURIK'S CORE", "&7He can be hurt now!", Sound.ENTITY_IRON_GOLEM_DEATH);
                c.bossSay(NAMES[3], "No... NO! Not past ME!");
            } else {
                s.section++;
                c.title("&aGate " + (s.section - 1) + " open!", "&7Section " + s.section + ": " + WitherArena.TERMS[s.section - 1].length + " terminals, 2 levers, "
                        + new String[]{"", "Simon Says", "Lights", "Arrow Align", "Shoot the Target"}[s.section]);
                progress(c, s);
            }
        }
        // he walks after the party; if he falls far behind he steps through the walls to the current section
        if (!s.core && t % 40 == 0) {
            Player near = Kit.nearest(c, s.lord.getLocation());
            if (near != null && near.getLocation().distance(s.lord.getLocation()) > 35) {
                Location to = c.point(s.section == 1 ? "p3.boss" : "p3.s" + s.section + ".entry");
                if (to != null) { s.lord.teleport(to); Fx.particle(to, Particle.LARGE_SMOKE, 40, 1, 0.05); }
            }
        }
        if (t % (s.core ? 6 : 16) == 0) {
            Player p = Kit.nearest(c, s.lord.getLocation());
            if (p != null && p.getLocation().distance(s.lord.getLocation()) < 40)
                Kit.bolt(c, s.lord.getEyeLocation(), p.getEyeLocation(), 1.0, 40, 1.3, Particle.CRIT, Color.YELLOW, q -> Kit.hit(q, lordDmg(c, 3) * (s.core ? 0.4 : 0.6)));
        }
        if (!s.core && t % 8 == 0) for (Player p : Kit.within(c, s.lord.getLocation(), 3)) {
            Kit.hit(p, lordDmg(c, 3) * 2);
            Sky.vitals().flash(p, "&6Aurik's frenzy! &7Keep moving!", 30);
        }
    }

    // ======================================================================================================
    // P4 Morvane
    // ======================================================================================================

    private void morvane(BossContext c, S s, long t) {
        if (!Kit.alive(s.lord)) return;
        boolean frenzy = t < s.frenzyUntil;
        Kit.bar(c, NAMES[4], s.lord, frenzy ? "&c&lFRENZY" : "&7The Warden", frenzy ? BossBar.Color.RED : BossBar.Color.WHITE);
        double d = lordDmg(c, 4);
        Location centre = c.point("p4.boss");
        for (int threshold : new int[]{80, 50})
            if (!s.frenzies.contains(threshold) && Kit.frac(s.lord) * 100 <= threshold) {
                s.frenzies.add(threshold);
                s.frenzyUntil = t + 40;
                s.frenzyR = 6;
                frenzy = true;
                Kit.announce(c, "&c&lMORVANE'S FRENZY", "&7Hit him to shrink the blast!", Sound.ENTITY_WITHER_SHOOT);
                c.bossSay(NAMES[4], threshold == 80 ? "You are starting to annoy me." : "ENOUGH!");
                if (centre != null) s.lord.teleport(centre.clone().add(0, 1.5, 0));
            }
        if (frenzy) {
            if (t % 4 == 0 && centre != null) {
                s.frenzyR = Math.max(4, Math.min(20, s.frenzyR + (s.hitThisSecond ? -1.5 : 1)));
                s.hitThisSecond = false;
                Fx.ring(centre, s.frenzyR, Color.RED, (int) (s.frenzyR * 8));
                Fx.particle(centre.clone().add(0, 1, 0), Particle.EXPLOSION, 3, s.frenzyR / 3, 0);
                Kit.sound(c, Sound.ENTITY_GENERIC_EXPLODE, 0.8f);
                for (Player p : Kit.within(c, centre, s.frenzyR)) Kit.hit(p, d * 1.5);
            }
            if (t + 1 >= s.frenzyUntil) breakRing(c);
            return;
        }
        if (t % 16 == 0) for (int i = 0; i < 3; i++) {
            Player p = pick(c, rng);
            if (p == null) break;
            Location from = s.lord.getEyeLocation().add((i - 1) * 1.2, 0.6, 0);
            Kit.bolt(c, from, p.getEyeLocation(), 1.1, 50, 1.2, Particle.SMOKE, Color.BLACK, q -> { Kit.hit(q, d * 0.5); Kit.knock(q, from, 1.4, 0.5); });
        }
        if (t % 28 == 14) for (int i = 0; i < 2; i++) {
            Player p = pick(c, rng);
            if (p != null) Kit.circle(c, p.getLocation(), 3, 30, Color.ORANGE, q -> Kit.hit(q, d * 0.8), () -> Fx.particle(p.getLocation(), Particle.LAVA, 20, 1.5, 0));
        }
        if (t % 44 == 22) for (int i = 0; i < 3; i++) {
            Player p = pick(c, rng);
            if (p != null) {
                Location at = p.getLocation();
                Kit.circle(c, at, 2, 20, Color.YELLOW, q -> Kit.hit(q, d * 0.6), () -> at.getWorld().strikeLightningEffect(at));
            }
        }
        if (t % 60 == 30 && centre != null) for (int i = 0; i < 4; i++) {
            Player p = pick(c, rng);
            if (p == null) break;
            double a = Math.PI / 2 * i;
            Location from = centre.clone().add(Math.cos(a) * 20, 3, Math.sin(a) * 20);
            Kit.bolt(c, from, p.getEyeLocation(), 0.9, 50, 1.3, Particle.ENCHANTED_HIT, Color.AQUA, q -> { Kit.hit(q, d * 0.7); Kit.knock(q, from, 2.0, 0.6); });
        }
        if (t >= s.beamsAt && centre != null) {
            s.beamsAt = t + 100;
            rotatingBeams(c, centre);
        }
    }

    /** Four horizontal beams sweep round the platform for 15 s at shin height: jump them or lose 25% max HP. */
    private void rotatingBeams(BossContext c, Location centre) {
        Kit.announce(c, "&c&lROTATING BEAMS", "&7Jump over them!", Sound.BLOCK_BEACON_AMBIENT);
        final double[] ang = {0};
        final int[] tick = {0};
        Map<UUID, Long> last = new HashMap<>();
        double y = centre.getY() + 0.3;
        Kit.add(c, () -> {
            ang[0] += 0.035;
            for (int k = 0; k < 4; k++) {
                double a = ang[0] + Math.PI / 2 * k;
                Location a1 = centre.clone().add(Math.cos(a) * 2, 0.3, Math.sin(a) * 2), a2 = centre.clone().add(Math.cos(a) * 25, 0.3, Math.sin(a) * 25);
                a1.setY(y);
                a2.setY(y);
                if (tick[0] % 2 == 0) Fx.line(a1, a2, Color.RED, 0.8);
                for (Player p : c.alive()) {
                    Location feet = p.getLocation();
                    if (Math.abs(feet.getY() - y) > 0.65) continue;
                    if (!Kit.nearSegment(feet.clone().add(0, 0.3, 0), a1, a2, 0.7)) continue;
                    long now = System.currentTimeMillis();
                    if (now - last.getOrDefault(p.getUniqueId(), 0L) < 800) continue;
                    last.put(p.getUniqueId(), now);
                    Kit.pct(p, 0.25);
                }
            }
            return ++tick[0] < 300;
        });
    }

    private void breakRing(BossContext c) {
        Location centre = c.point("p4.boss");
        if (centre == null) return;
        double start = rng.nextDouble() * Math.PI * 2;
        for (double a = start; a < start + Math.PI / 3; a += 0.03)
            for (int r = 21; r <= 25; r++) {
                Block b = centre.getWorld().getBlockAt((int) Math.floor(centre.getX() + Math.cos(a) * r), centre.getBlockY() - 1, (int) Math.floor(centre.getZ() + Math.sin(a) * r));
                b.setType(Material.LAVA, false);
            }
        c.broadcast("&cPart of the platform crumbles into the lava!");
        Kit.sound(c, Sound.BLOCK_BASALT_BREAK, 0.6f);
    }

    // ======================================================================================================
    // P5 the Hollow King (Master Mode)
    // ======================================================================================================

    private static String relicItem(int i) { return "WITHER_RELIC_" + WitherArena.RELICS[i].toUpperCase(java.util.Locale.ROOT); }

    static final Material[] RELIC_SHOWN = {Material.LIME_DYE, Material.ORANGE_DYE, Material.LIGHT_BLUE_DYE, Material.RED_DYE, Material.PURPLE_DYE};

    private void setupRelics(BossContext c, S s) {
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            spawnRelic(c, s, idx);
            Location cauldron = c.point("p5.cauldron." + WitherArena.RELICS[i]);
            if (cauldron != null) Hooks.onBlock(c, block(cauldron), (p, b, a) -> { cauldron(c, s, p, idx, b); return true; });
        }
    }

    /** A relic on its pedestal; picking it up gives a run-only tagged stack (review fix: was a sky item via loot). */
    private void spawnRelic(BossContext c, S s, int idx) {
        Location at = c.point("p5.relic." + WitherArena.RELICS[idx]);
        if (at == null) return;
        Hooks.clickableItem(c, at, new ItemStack(RELIC_SHOWN[idx]), colour(idx) + DRAGON_NAMES[idx] + " Relic", 1f, (p, e) -> {
            if (s.carrying.containsKey(p.getUniqueId())) { Msg.error(p, "You can only carry one relic."); return; }
            if (s.placed.contains(idx) || s.carrying.containsValue(idx)) return;
            s.carrying.put(p.getUniqueId(), idx);
            give(p, Hooks.carried(RELIC_SHOWN[idx], colour(idx) + DRAGON_NAMES[idx] + " Relic",
                    List.of("&7Place it in the " + DRAGON_NAMES[idx] + " cauldron.", "&cThe wrong cauldron fails the run!"), "relic"));
            for (Entity near : e.getNearbyEntities(0.5, 2.5, 0.5)) if (near instanceof org.bukkit.entity.Display) near.remove();
            e.remove();
            c.broadcast("&b" + p.getName() + " &7picked up the " + DRAGON_NAMES[idx] + " Relic.");
        });
    }

    /** Relic carriers who died, quit or left: the relic returns to its pedestal (no soft-lock). */
    private void checkRelicCarriers(BossContext c, S s) {
        List<Player> alive = c.alive();
        for (Map.Entry<UUID, Integer> en : new ArrayList<>(s.carrying.entrySet())) {
            Player q = org.bukkit.Bukkit.getPlayer(en.getKey());
            if (q != null && alive.contains(q) && Hooks.countCarried(q, "relic") > 0) continue;
            s.carrying.remove(en.getKey());
            if (q != null) Hooks.stripCarried(q, "relic");
            if (!s.placed.contains(en.getValue())) {
                spawnRelic(c, s, en.getValue());
                c.broadcast("&7The " + DRAGON_NAMES[en.getValue()] + " Relic returns to its place.");
            }
        }
    }

    private void cauldron(BossContext c, S s, Player p, int idx, Block b) {
        Integer carried = s.carrying.get(p.getUniqueId());
        if (carried == null) { Msg.send(p, "&7Bring the " + DRAGON_NAMES[idx] + " Relic here."); return; }
        if (s.placed.contains(idx)) { Msg.send(p, "&7This cauldron already holds its relic."); return; }
        s.carrying.remove(p.getUniqueId());
        Hooks.stripCarried(p, "relic");
        if (carried != idx) {
            Kit.announce(c, "&4&lTHE RITUAL BREAKS", "&7A relic in the wrong cauldron!", Sound.ENTITY_WITHER_DEATH);
            c.fail("A relic was placed in the wrong cauldron");
            return;
        }
        s.placed.add(idx);
        b.setBlockData(org.bukkit.Bukkit.createBlockData("water_cauldron[level=3]"), false);
        Fx.particle(b.getLocation().add(0.5, 1, 0.5), Particle.END_ROD, 30, 0.4, 0.05);
        c.broadcast("&a" + DRAGON_NAMES[idx] + " Relic placed! &7(" + s.placed.size() + "/5)");
        if (s.placed.size() == 5) {
            Kit.announce(c, "&5&lTHE STATUES AWAKEN", "&7Kill each dragon by its own statue!", Sound.ENTITY_ENDER_DRAGON_GROWL);
            s.dragonAt = c.steps() + 20;
        }
    }

    private void spawnDragon(BossContext c, S s, int i) {
        Location statue = c.point("p5.statue." + WitherArena.RELICS[i]);
        if (statue == null) return;
        LivingEntity d = c.spawnExact("dboss_dragon_" + WitherArena.RELICS[i], statue.clone().add(0, 12, 0), 1e9, 800_000);
        if (d == null) return;
        Kit.scale(d, 4);
        Kit.glow(d, true);
        s.dragons.put(i, d);
        c.title(colour(i) + "&l" + DRAGON_NAMES[i].toUpperCase() + " DRAGON", "&7Kill it near its statue!");
        Kit.sound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f);
    }

    private static String colour(int i) { return new String[]{"&a", "&6", "&b", "&c", "&5"}[i]; }

    private void king(BossContext c, S s, long t) {
        checkRelicCarriers(c, s);
        int alive = 0;
        for (LivingEntity d : s.dragons.values()) if (Kit.alive(d)) alive++;
        c.bossBar("&4&lThe Hollow King &7- &aStatues left: &e" + (5 - s.shattered.size()) + " &8| &7Dragons: &c" + alive,
                Math.max(0.02f, (5 - s.shattered.size()) / 5f), BossBar.Color.PURPLE);
        if (alive >= 5) {
            Kit.announce(c, "&4&lTHE RITUAL COMPLETES", "&7Five dragons fly together.", Sound.ENTITY_WITHER_DEATH);
            c.fail("All five dragons were alive at once");
            return;
        }
        if (s.dragonAt >= 0 && t >= s.dragonAt) {
            s.dragonAt = t + 120;
            for (int k = 0; k < 5; k++) {
                int i = (s.nextDragon + k) % 5;
                if (!s.shattered.contains(i) && !s.dragons.containsKey(i)) { spawnDragon(c, s, i); s.nextDragon = i + 1; break; }
            }
        }
        for (Map.Entry<Integer, Long> r : new ArrayList<>(s.respawn.entrySet()))
            if (t >= r.getValue()) { s.respawn.remove(r.getKey()); spawnDragon(c, s, r.getKey()); }
        Location king = c.point("p5.king");
        if (king == null) return;
        if (t % 8 == 0) for (Player p : Kit.within(c, king, 6)) {
            Fx.line(king.clone().add(0, 2, 0), p.getEyeLocation(), Color.BLACK, 0.4);
            Kit.pct(p, 2.0);
            c.broadcast("&4The Hollow King pulled &b" + p.getName() + " &4into the dark.");
        }
        if (t % 20 == 10) {
            Player p = pick(c, rng);
            if (p != null) Kit.beam(c, king.clone().add(0, 3, 0), p.getEyeLocation(), 20, Color.fromRGB(80, 0, 120), q -> Kit.pct(q, 0.15));
        }
        if (t % 30 == 0) {
            Player p = pick(c, rng);
            if (p != null) { Location at = p.getLocation(); Kit.circle(c, at, 2.5, 24, Color.YELLOW, q -> Kit.pct(q, 0.2), () -> at.getWorld().strikeLightningEffect(at)); }
        }
        kingAdds(c, s, t);
        // dragon gimmicks (Flame dives, Ice slows, Power drops TNT, Soul teleports, Apex simply hits hard)
        if (t % 20 == 0) for (Map.Entry<Integer, LivingEntity> en : s.dragons.entrySet()) {
            LivingEntity d = en.getValue();
            if (!Kit.alive(d)) continue;
            Player p = Kit.nearest(c, d.getLocation());
            if (p == null) continue;
            switch (en.getKey()) {
                case 1 -> Kit.circle(c, p.getLocation(), 3, 24, Color.ORANGE, q -> Kit.hit(q, 400_000), null);
                case 2 -> Kit.effect(p, org.bukkit.potion.PotionEffectType.SLOWNESS, 60, 2);
                case 3 -> { Location centre = c.point("p5.chests"); if (centre != null) Kit.circle(c, centre, 6, 30, Color.RED, q -> Kit.hit(q, 500_000), null); }
                case 4 -> d.teleport(p.getLocation().clone().add(rng.nextInt(9) - 4, 6, rng.nextInt(9) - 4));
                default -> { }
            }
        }
    }

    /**
     * M7 phase 5 adds (wave 5, research 04 sec 3.13: "Wither Husks (500M), Apostles (800M, 120k dmg)"): every
     * {@code dungeons.m7-king-add-seconds} (40) the King calls one to the throne room, alternating Husk / Apostle, near
     * a random player but never inside the King's pull; at most 3 alive (TUNE).
     */
    private void kingAdds(BossContext c, S s, long t) {
        long every = Math.max(5, DDials.KING_ADD_SECONDS.get()) * 4L;
        if (s.dragonAt < 0 || t % every != every / 2) return;
        s.adds.removeIf(u -> { Entity e = org.bukkit.Bukkit.getEntity(u); return e == null || !e.isValid(); });
        if (s.adds.size() >= 3) return;
        Player p = pick(c, rng);
        Location king = c.point("p5.king");
        if (p == null) return;
        Location at = p.getLocation().clone().add(rng.nextInt(11) - 5, 0, rng.nextInt(11) - 5);
        if (king != null && Kit.flat(at, king) < 10) at = p.getLocation();
        boolean apostle = (s.kingAdds++ % 2) == 1;
        LivingEntity e = c.spawnExact(apostle ? "dboss_apostle" : "dboss_wither_husk", at,
                apostle ? 800_000_000 : 500_000_000, apostle ? 120_000 : 100_000);
        if (e != null) {
            s.adds.add(e.getUniqueId());
            c.broadcast("&4The Hollow King &7calls " + (apostle ? "&5a Hollow Apostle" : "&8a Wither Husk") + "&7!");
        }
    }

    /** Master Mode Defense of the Wither Lords (research 04 sec 3.13): Tempestra 1,200, Aurik 1,800, Morvane 2,100. */
    static double masterLordDefense(int phase) {
        return switch (phase) { case 2 -> 1_200; case 3 -> 1_800; case 4 -> 2_100; default -> 0; };
    }

    // ======================================================================================================

    @Override
    public void onDamage(BossContext c, DamageContext dc) {
        S s = st(c, S::new);
        // wave 5: the Master Mode lords carry their research Defense (the boss mob defs have none; the dragons already
        // have 2,500 on theirs). Applied here so normal mode is untouched
        if (c.master() && DDials.MASTER_LORD_DEFENSE.get() && is(s.lord, dc.victim) && s.phase >= 2 && s.phase <= 4)
            dc.finalDamage *= 100.0 / (100.0 + masterLordDefense(s.phase));
        if (s.phase == 5 && is(s.lord, dc.victim)) { Kit.immune(dc, "The King cannot be harmed. Shatter the statues!"); return; }
        if (!is(s.lord, dc.victim)) return;
        long t = c.steps();
        switch (s.phase) {
            case 1 -> {
                if (!(s.trapped && t < s.laserUntil)) { Kit.immune(dc, "Trap him in the laser first!"); return; }
                if (s.lasers == 0) {
                    double floor = Kit.max(s.lord) * 0.25;
                    if (Kit.hp(s.lord) - dc.finalDamage < floor) {
                        dc.finalDamage = Math.max(0, Kit.hp(s.lord) - floor);
                        if (dc.finalDamage <= 0) { Kit.immune(dc, "Gravax is enraged - he needs another laser!"); }
                    }
                }
            }
            case 2 -> {
                if (t >= s.stunUntil) dc.finalDamage *= 0.25;
                if (s.crushes < DDials.STORM_CRUSHES.get() && dc.finalDamage >= Kit.hp(s.lord)) {
                    dc.finalDamage = Math.max(0, Kit.hp(s.lord) - 1);
                    Player p = dc.playerAttacker();
                    if (p != null) Sky.vitals().flash(p, "&bShe must be crushed " + DDials.STORM_CRUSHES.get() + " times first!", 40);
                }
            }
            case 3 -> { if (!s.core) Kit.immune(dc, "Aurik is invulnerable until you reach his core!"); }
            case 4 -> s.hitThisSecond = true;
            default -> Kit.immune(dc, "Not yet!");
        }
    }

    @Override
    public void onMobKill(BossContext c, LivingEntity mob, MobDef def, Player killer) {
        S s = st(c, S::new);
        if (s.phase >= 1 && s.phase <= 4 && is(s.lord, mob)) {
            String[] last = {"", "Tempestra... avenge me...", "Aurik... hold the line...", "Warden... forgive me...", "The vault... falls..."};
            c.bossSay(NAMES[s.phase], last[s.phase]);
            Kit.announce(c, "&a&l" + NAMES[s.phase].toUpperCase() + " DEFEATED", "", Sound.ENTITY_WITHER_DEATH);
            nextLord(c, s);
            return;
        }
        s.adds.remove(mob.getUniqueId());
        if (s.phase == 5) for (Map.Entry<Integer, LivingEntity> en : new ArrayList<>(s.dragons.entrySet())) {
            if (!mob.equals(en.getValue())) continue;
            int i = en.getKey();
            s.dragons.remove(i);
            Location statue = c.point("p5.statue." + WitherArena.RELICS[i]);
            if (statue != null && Kit.flat(mob.getLocation(), statue) < 18) {
                s.shattered.add(i);
                fill(statue.clone().add(-1, 0, -1), statue.clone().add(1, 9, 1), Material.COBBLED_DEEPSLATE.createBlockData());
                Kit.announce(c, colour(i) + "&l" + DRAGON_NAMES[i].toUpperCase() + " STATUE SHATTERED", "&7(" + s.shattered.size() + "/5)", Sound.BLOCK_GLASS_BREAK);
                if (s.shattered.size() >= 5) win(c, "The Hollow King", "Enough. ENOUGH. Take your prize, and leave my tomb.");
            } else {
                c.broadcast(colour(i) + DRAGON_NAMES[i] + " Dragon &7fell far from its statue - &cit will return!");
                s.respawn.put(i, c.steps() + 20);
            }
        }
    }

    /** A carrier who dies drops what they carried: the crystal or relic goes back to where it came from. */
    @Override
    public void onPlayerDeath(BossContext c, Player p) {
        S s = st(c, S::new);
        // the carrier became a ghost: what they carried goes back where it came from (the same checks the tick runs)
        if (s.phase == 1) checkCrystalCarriers(c, s);
        if (s.phase == 5) checkRelicCarriers(c, s);
    }

    @Override
    public void stop(BossContext c) {
        for (Player p : c.players()) {
            Hooks.stripCarried(p, null);
            // the old sky-item versions, in case a pre-fix build handed them out
            if (Sky.items().exists("MAXOR_ENERGY_CRYSTAL")) { int n = Sky.items().count(p, "MAXOR_ENERGY_CRYSTAL"); if (n > 0) Sky.items().remove(p, "MAXOR_ENERGY_CRYSTAL", n); }
            for (int i = 0; i < 5; i++) if (Sky.items().exists(relicItem(i))) { int n = Sky.items().count(p, relicItem(i)); if (n > 0) Sky.items().remove(p, relicItem(i), n); }
        }
        super.stop(c);
    }
}
