package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The six pickaxe abilities of the tree (research 2.7.4), fired by right-clicking any pickaxe or drill.
 * Every pickaxe/drill carries one AbilityDef "mining_pickaxe_ability"; the player picks WHICH ability in
 * /hotm. Ability levels 1-3: base 1, +1 from Core of the Mountain, +1 from a Blue Cheese omelette.
 */
final class PickaxeAbilities {

    static final String ID = "mining_pickaxe_ability";
    static final List<String> ALL = List.of("mining_speed_boost", "pickobulus", "anomalous_desire", "maniac_miner", "gemstone_infusion", "sheer_force");

    static final class Active {
        long msbUntil; double msbBoost;
        long sheerUntil; double sheerSpread = 200;
        long maniacUntil; double maniacStacks; double maniacPer;
        long infusionUntil; double infusionMult = 2;
        long tunnelUntil; double tunnelBoost;
    }

    private final MiningModule m;
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();

    PickaxeAbilities(MiningModule m) {
        this.m = m;
        Sky.abilities().register(ID, use -> fire(use.player()));
    }

    static String name(String perk) {
        return switch (perk == null ? "" : perk) {
            case "mining_speed_boost" -> "Mining Speed Boost";
            case "pickobulus" -> "Pickblast";
            case "anomalous_desire" -> "Tunnel Vision";
            case "maniac_miner" -> "Maniac Miner";
            case "gemstone_infusion" -> "Gemstone Infusion";
            case "sheer_force" -> "Sheer Force";
            default -> "None";
        };
    }

    /** Fallback numbers if hotm.json lacks them (research 2.7.4); the shipped data carries the same values. */
    private static final Map<String, int[][]> FALLBACK = Map.of(
            "mining_speed_boost", new int[][]{{200, 250, 300}, {10, 15, 20}, {120, 120, 120}},
            "pickobulus", new int[][]{{2, 2, 2}, {0, 0, 0}, {60, 50, 40}},
            "anomalous_desire", new int[][]{{30, 40, 50}, {30, 30, 30}, {120, 110, 100}},
            "maniac_miner", new int[][]{{5, 10, 15}, {25, 30, 35}, {120, 120, 120}},
            "gemstone_infusion", new int[][]{{2, 2, 2}, {20, 25, 30}, {140, 140, 140}},
            "sheer_force", new int[][]{{200, 200, 200}, {20, 25, 30}, {120, 120, 120}});

    /** An ability's "value" / "duration" (s) / "cooldown" (s) at ability level 1..3, read from hotm.json. */
    double num(String perk, String which, int lvl) {
        HotmTree.Perk pk = m.hotm == null ? null : m.hotm.tree.perk(perk);
        double v = pk == null ? Double.NaN : pk.ability(which, lvl);
        if (!Double.isNaN(v)) return v;
        int[][] f = FALLBACK.get(perk);
        if (f == null) return "cooldown".equals(which) ? 120 : 0;
        int row = "value".equals(which) ? 0 : "duration".equals(which) ? 1 : 2;
        return f[row][Math.max(0, Math.min(2, lvl - 1))];
    }

    private int n(String perk, String which, int lvl) { return (int) Math.round(num(perk, which, lvl)); }

    /** Base cooldown in seconds at an ability level. */
    double baseCooldown(String perk, int lvl) { return num(perk, "cooldown", Math.max(1, lvl)); }

    List<String> describe(String perk, int lvl) {
        int l = Math.max(1, Math.min(3, lvl));
        return switch (perk) {
            case "mining_speed_boost" -> List.of("&7Grants &a+" + n(perk, "value", l) + "% &6⸕ Mining Speed &7for", "&a" + n(perk, "duration", l) + "s&7.");
            case "pickobulus" -> List.of("&7Throw a blast that mines every ore", "&7and metal within &a" + n(perk, "value", l) + " &7blocks of", "&7where it lands. Gems are spared.");
            case "anomalous_desire" -> List.of("&7Rare finds happen &a+" + n(perk, "value", l) + "% &7more", "&7often for &a" + n(perk, "duration", l) + "s&7.");
            case "maniac_miner" -> List.of("&7Grants &2+1 Ⓟ Breaking Power &7and", "&6+" + n(perk, "value", l) + " ☘ Mining Fortune &7per block", "&7(max 1,000) for &a" + n(perk, "duration", l) + "s&7. Each", "&7block costs &b20 Mana&7.");
            case "gemstone_infusion" -> List.of("&7The gems slotted in your tool", "&7count &a" + n(perk, "value", l) + "x &7for &a" + n(perk, "duration", l) + "s&7.");
            case "sheer_force" -> List.of("&7Grants &e+" + n(perk, "value", l) + " ▚ Mining Spread", "&7for &a" + n(perk, "duration", l) + "s&7.");
            default -> List.of();
        };
    }

    double cooldownFor(Player p, String perk) {
        double cd = baseCooldown(perk, m.hotm.abilityLevel(p));
        double cut = m.drills.cooldownCut(p);
        if (m.skyMall.is(p, SkyMall.Buff.COOLDOWN)) cut += 20;
        cut += m.tunnels.mayhemCooldownCut(p);
        cd *= Math.max(0.1, 1 - cut / 100.0);
        return cd * m.dials.abilityCooldownMult.get();
    }

    private boolean fire(Player p) {
        if (!MiningUtil.ready(p)) return false;
        MiningData d = MiningUtil.data(p);
        String sel = d.selectedAbility;
        if (sel == null || !m.hotm.has(p, sel)) {
            if (MiningUtil.onMiningIsland(p)) Msg.error(p, "You have no Pickaxe Ability selected! Unlock and pick one in /hotm.");
            return false;
        }
        int lvl = m.hotm.abilityLevel(p);
        long now = System.currentTimeMillis();
        Active a = active.computeIfAbsent(p.getUniqueId(), k -> new Active());
        switch (sel) {
            case "mining_speed_boost" -> {
                a.msbBoost = num(sel, "value", lvl);
                a.msbUntil = now + (long) (num(sel, "duration", lvl) * 1000);
            }
            case "pickobulus" -> { if (!pickobulus(p)) return false; }
            case "anomalous_desire" -> {
                a.tunnelBoost = num(sel, "value", lvl);
                a.tunnelUntil = now + (long) (num(sel, "duration", lvl) * 1000);
            }
            case "maniac_miner" -> {
                a.maniacPer = num(sel, "value", lvl);
                a.maniacStacks = 0;
                a.maniacUntil = now + (long) (num(sel, "duration", lvl) * 1000);
            }
            case "gemstone_infusion" -> {
                a.infusionMult = Math.max(1, num(sel, "value", lvl));
                a.infusionUntil = now + (long) (num(sel, "duration", lvl) * 1000);
            }
            case "sheer_force" -> {
                a.sheerSpread = num(sel, "value", lvl);
                a.sheerUntil = now + (long) (num(sel, "duration", lvl) * 1000);
            }
            default -> { return false; }
        }
        double cd = cooldownFor(p, sel);
        Sky.abilities().setCooldown(p, ID, cd);
        Sky.stats().invalidate(p);
        Msg.send(p, "&aYou used your &6" + name(sel) + " &aPickaxe Ability!");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.8f);
        UUID u = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            Player q = Bukkit.getPlayer(u);
            if (q != null) { Sky.stats().invalidate(q); }
        }, 20L * 36);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            Player q = Bukkit.getPlayer(u);
            if (q != null && Sky.abilities().cooldownLeft(q, ID) <= 0.05) Msg.send(q, "&6" + name(sel) + " &ais now available!");
        }, (long) Math.ceil(cd * 20) + 2);
        return true;
    }

    private boolean pickobulus(Player p) {
        RayTraceResult r = p.rayTraceBlocks(30, FluidCollisionMode.NEVER);
        if (r == null || r.getHitBlock() == null) { Msg.error(p, "Aim at a block to throw your pickaxe at!"); return false; }
        Block centre = r.getHitBlock();
        Location c = centre.getLocation().add(0.5, 0.5, 0.5);
        p.getWorld().spawnParticle(Particle.CRIT, p.getEyeLocation(), 20, 0.3, 0.3, 0.3, 0.2);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            c.getWorld().spawnParticle(Particle.EXPLOSION, c, 3, 1, 1, 1, 0);
            c.getWorld().playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.3f);
            int broken = 0;
            int rad = Math.max(1, n("pickobulus", "value", m.hotm.abilityLevel(p)));
            for (int dx = -rad; dx <= rad; dx++) for (int dy = -rad; dy <= rad; dy++) for (int dz = -rad; dz <= rad; dz++) {
                if (dx * dx + dy * dy + dz * dz > rad * rad + 1) continue;
                Block b = centre.getRelative(dx, dy, dz);
                BlockTable.Def def = m.engine.mineable(p, b, false);
                if (def == null || def.kind == BlockTable.Kind.GEMSTONE || def.kind == BlockTable.Kind.STAR) continue;
                if (m.engine.toolPower(p) < def.breakingPower) continue;
                m.breaker.breakBlock(p, b, def, true);
                broken++;
                if (broken >= 40) return;
            }
        }, 10L);
        return true;
    }

    /** Maniac Miner: stack fortune and spend mana per block. */
    void onBlock(Player p) {
        Active a = active.get(p.getUniqueId());
        if (a == null || a.maniacUntil < System.currentTimeMillis()) return;
        if (!Sky.vitals().useMana(p, 20)) {
            a.maniacUntil = 0;
            Msg.send(p, "&cManiac Miner ran out of mana!");
            Sky.stats().invalidate(p);
            return;
        }
        a.maniacStacks = Math.min(1000, a.maniacStacks + a.maniacPer);
        Sky.stats().invalidate(p);
    }

    void contribute(Player p, StatMap s) {
        Active a = active.get(p.getUniqueId());
        if (a == null) return;
        long now = System.currentTimeMillis();
        if (a.msbUntil > now) s.multiply(Stat.MINING_SPEED, 1 + a.msbBoost / 100.0);
        if (a.sheerUntil > now) s.add(Stat.MINING_SPREAD, a.sheerSpread);
        if (a.maniacUntil > now) {
            s.add(Stat.BREAKING_POWER, 1);
            s.add(Stat.MINING_FORTUNE, a.maniacStacks);
        }
        if (a.infusionUntil > now) {
            ItemStack h = p.getInventory().getItemInMainHand();
            if (h != null && !h.getType().isAir() && Sky.items().idOf(h) != null) {
                ItemStatSheet sheet = Sky.items().sheet(h);
                for (Stat st : sheet.view().keySet()) s.add(st, (a.infusionMult - 1) * sheet.part(st, ItemStatSheet.Source.GEMSTONE));
            }
        }
    }

    /** Tunnel Vision bonus to rare-find chances, in percent. */
    double rareBoost(Player p) {
        Active a = active.get(p.getUniqueId());
        return a != null && a.tunnelUntil > System.currentTimeMillis() ? a.tunnelBoost : 0;
    }

    List<String> statusLines(Player p) {
        List<String> out = new ArrayList<>();
        Active a = active.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (a != null) {
            if (a.msbUntil > now) out.add("&6Mining Speed Boost &7" + Text.duration((a.msbUntil - now) / 1000));
            if (a.maniacUntil > now) out.add("&6Maniac Miner &7+" + (int) a.maniacStacks + " fortune");
            if (a.sheerUntil > now) out.add("&eSheer Force &7" + Text.duration((a.sheerUntil - now) / 1000));
        }
        return out;
    }

    void forget(UUID u) { active.remove(u); }
}
