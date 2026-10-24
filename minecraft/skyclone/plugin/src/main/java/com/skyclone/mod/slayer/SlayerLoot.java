package com.skyclone.mod.slayer;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Boss loot: the token, one weighted pick (research 2.11), independent dyes + Paragon, the RNG meter (2.12) and
 * lootshare (whose roll alone holds the lootshare-only drops, audit C3).
 * Wave 5: Hunting shards in the tables go to the Hunting Box ({@code Features.Hunting.addShards}), Magic Find is
 * core's per-target value ({@code Sky.loot().magicFind(p, bossDef)}), and a Primordial cocoon re-rolls the loot.
 */
final class SlayerLoot {

    private final SlayerModule mod;

    SlayerLoot(SlayerModule mod) { this.mod = mod; }

    /** Labels from the computed chance (research 2.5). */
    static String label(double chancePercent) {
        if (chancePercent >= 100) return "&aGuaranteed";
        if (chancePercent >= 5) return "&9Occasional";
        if (chancePercent >= 1) return "&5Rare";
        if (chancePercent >= 0.1) return "&6Extraordinary";
        if (chancePercent >= 0.01) return "&dPray to RNGesus";
        return "&cRNGesus Incarnate";
    }

    static String dropBanner(double chancePercent) {
        if (chancePercent >= 5) return null;
        if (chancePercent >= 1) return "&6&lRARE DROP!";
        if (chancePercent >= 0.1) return "&9&lVERY RARE DROP!";
        if (chancePercent >= 0.01) return "&d&lCRAZY RARE DROP!";
        return "&5&lPRAY TO RNGESUS DROP!";
    }

    double rarePerk() {
        Features.Mayors m = Sky.service(Features.Mayors.class);
        return m.perkActive("aatrox.pathfinder") ? m.perkValue("aatrox.pathfinder") : 0;
    }

    /** Can this drop reach a player right now? Shards need the hunting module (and the dial); items always roll. */
    static boolean available(SlayerCatalog.LootEntry e) {
        if (!e.shard) return true;
        return SlayerDials.SHARD_DROPS.get() && Sky.has(Features.Hunting.class);
    }

    /** The Magic Find a boss kill rolls with: the stat plus every per-target modifier (bestiary, vs-undead...). */
    static double magicFind(Player p, SlayerCatalog.Type t, int tier) {
        SlayerCatalog.Tier ti = t.tier(tier);
        MobDef def = ti == null || ti.mob == null ? null : Sky.mobs().def(ti.mob);
        try {
            return Sky.loot().magicFind(p, def);
        } catch (RuntimeException e) {
            return Sky.stats().get(p, com.skyclone.api.stat.Stat.MAGIC_FIND);
        }
    }

    /** Chances for a player at a tier, with their Magic Find, the mayor perk and their meter. */
    Map<String, Double> chancesFor(Player p, SlayerCatalog.Type t, int tier) {
        return chancesFor(p, t, tier, false);
    }

    /** {@link #chancesFor}; {@code lootshare} = a helper's roll, which also holds the lootshare-only drops. */
    Map<String, Double> chancesFor(Player p, SlayerCatalog.Type t, int tier, boolean lootshare) {
        SlayerData d = mod.quests.data(p);
        int lvl = mod.level(p, t);
        String sel = d.rngSelected.get(t.id);
        return SlayerMath.chances(t, tier, lvl, magicFind(p, t, tier), rarePerk(), sel, meterMult(p, t), SlayerLoot::available, lootshare);
    }

    /** The selected drop's RNG meter weight multiplier right now (1 with nothing selected). */
    double meterMult(Player p, SlayerCatalog.Type t) {
        SlayerData d = mod.quests.data(p);
        String sel = d.rngSelected.get(t.id);
        SlayerCatalog.LootEntry e = sel == null ? null : t.lootEntry(sel);
        if (e == null || e.rng <= 0) return 1;
        return SlayerMath.meterMultiplier(SlayerMath.meterProgress(d.rngXp.getOrDefault(t.id, 0.0), e.rng), SlayerDials.RNG_BOOST.get());
    }

    /** Chance (0..1) of an independent drop (dye, Paragon) for this player: Magic Find, mayor perk, meter. */
    double independentFor(Player p, SlayerCatalog.Type t, SlayerCatalog.LootEntry e, int tier, int lvl, double mf, double mult) {
        if (!available(e)) return 0;
        String sel = mod.quests.data(p).rngSelected.get(t.id);
        return SlayerMath.independentChance(e, tier, lvl, mf, rarePerk(), e.item.equals(sel) ? mult : 1);
    }

    void rollBoss(Player p, BossFight f, double slayerXpGained) {
        SlayerCatalog.Type t = f.type;
        int tier = f.tier.tier;
        SlayerData d = mod.quests.data(p);
        int lvl = mod.level(p, t);
        double mf = magicFind(p, t, tier);
        ThreadLocalRandom r = ThreadLocalRandom.current();

        // 1. token
        for (SlayerCatalog.LootEntry e : t.loot) {
            if (e.token && e.dropsAt(tier)) give(p, t, e, tier, 100, mf, false);
        }

        // 2. RNG meter: credit this kill's slayer XP, guarantee when full
        String sel = d.rngSelected.get(t.id);
        SlayerCatalog.LootEntry selEntry = sel == null ? null : t.lootEntry(sel);
        boolean guaranteed = false;
        Map<String, Double> chances = chancesFor(p, t, tier);
        double indepMult = meterMult(p, t);      // before this kill's meter XP, like the weighted pick
        if (selEntry != null && selEntry.rng > 0 && selEntry.dropsAt(tier) && lvl >= selEntry.minLevel && available(selEntry)) {
            double xp = d.rngXp.getOrDefault(t.id, 0.0) + slayerXpGained;
            d.rngXp.put(t.id, xp);
            if (xp >= selEntry.rng) {
                guaranteed = true;
                d.rngXp.put(t.id, 0.0);
                Msg.send(p, "&d&lRNG METER! &7Your meter filled up and rewarded you a guaranteed drop!");
                Fx.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.6f);
                give(p, t, selEntry, tier, chances.getOrDefault(selEntry.item, 0.0) * 100, mf, true);
            }
        } else {
            // nothing selected (or not droppable yet): the XP still banks and applies once something is picked
            // (research 2.12; the menus say "Banked meter XP")
            d.rngXp.merge(t.id, slayerXpGained, Double::sum);
        }

        // 3. one weighted pick
        String pick = SlayerMath.pick(chances, r.nextDouble());
        if (!pick.isEmpty()) {
            SlayerCatalog.LootEntry e = t.lootEntry(pick);
            if (e != null && !(guaranteed && e == selEntry)) {
                give(p, t, e, tier, chances.getOrDefault(pick, 0.0) * 100, mf, false);
                if (e == selEntry) {
                    d.rngXp.put(t.id, 0.0);
                    Msg.send(p, "&7Your RNG meter reset because the selected drop dropped naturally.");
                }
            }
        }

        // 4. independent rolls: dyes and the Paragon shard, each in its own pool (audit C2)
        for (SlayerCatalog.LootEntry e : t.loot) {
            double c = independentFor(p, t, e, tier, lvl, mf, indepMult);
            if (c <= 0 || r.nextDouble() >= c || (guaranteed && e == selEntry)) continue;
            give(p, t, e, tier, c * 100, mf, false);
            if (e == selEntry) {
                d.rngXp.put(t.id, 0.0);
                Msg.send(p, "&7Your RNG meter reset because the selected drop dropped naturally.");
            }
        }

        // meter line
        if (selEntry != null && selEntry.rng > 0) {
            double prog = SlayerMath.meterProgress(d.rngXp.getOrDefault(t.id, 0.0), selEntry.rng);
            Msg.send(p, "   &dRNG Meter &7- " + Text.dashBar(prog, 20) + " &d" + Text.commas(Math.floor(prog * 1000) / 10) + "%");
        }
    }

    /**
     * A cocooned boss slain again (The Primordial): the token and one weighted pick, as a kill. It does not touch the
     * RNG meter (no slayer XP was earned) and gives no slayer XP, quest progress or bits.
     */
    void rollCocoon(Player p, SlayerCatalog.Type t, int tier) {
        double mf = magicFind(p, t, tier);
        for (SlayerCatalog.LootEntry e : t.loot) {
            if (e.token && e.dropsAt(tier)) give(p, t, e, tier, 100, mf, false);
        }
        Map<String, Double> chances = chancesFor(p, t, tier);
        String pick = SlayerMath.pick(chances, ThreadLocalRandom.current().nextDouble());
        SlayerCatalog.LootEntry e = pick.isEmpty() ? null : t.lootEntry(pick);
        if (e != null) give(p, t, e, tier, chances.getOrDefault(pick, 0.0) * 100, mf, false);
        int lvl = mod.level(p, t);
        for (SlayerCatalog.LootEntry x : t.loot) {
            double c = independentFor(p, t, x, tier, lvl, mf, meterMult(p, t));
            if (c > 0 && ThreadLocalRandom.current().nextDouble() < c) give(p, t, x, tier, c * 100, mf, false);
        }
    }

    /** Display name of a loot entry: the shard's own name, or the item's (books say which enchant). */
    static String entryName(SlayerCatalog.LootEntry e) {
        if (e.shard) {
            com.skyclone.api.item.Rarity r = rarityOf(e.rarity);
            return r.code() + (e.name == null ? Text.titleCase(e.item) : e.name);
        }
        return SlayerMenus.itemName(e.item);
    }

    static com.skyclone.api.item.Rarity rarityOf(String s) {
        try {
            return s == null ? com.skyclone.api.item.Rarity.RARE : com.skyclone.api.item.Rarity.valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return com.skyclone.api.item.Rarity.RARE;
        }
    }

    private void give(Player p, SlayerCatalog.Type t, SlayerCatalog.LootEntry e, int tier, double chancePercent, double mf, boolean meter) {
        int n = e.minAt(tier) + ThreadLocalRandom.current().nextInt(e.maxAt(tier) - e.minAt(tier) + 1);
        String name;
        if (e.shard) {
            if (!available(e)) return;
            // straight into the Hunting Box (+ Hunting XP); the hunting module prints its own "+1 X Shard" line
            Sky.service(Features.Hunting.class).addShards(p, e.item, n, "slayer_boss");
            name = entryName(e);
        } else {
            if (!Sky.items().exists(e.item)) return;
            ItemDef def = Sky.items().def(e.item);
            giveStacked(p, e.item, n, def == null ? 64 : def.effectiveMaxStack());
            name = def == null ? e.item : SlayerMenus.itemName(def);
        }
        mod.quests.data(p).dropLog.merge(e.item, (long) n, Long::sum);
        if (e.token) {
            Msg.send(p, "   &7+" + n + "x " + name);
            return;
        }
        String banner = meter ? "&d&lRNG METER DROP!" : dropBanner(chancePercent);
        if (banner == null) {
            Msg.send(p, "   &7+" + n + "x " + name);
        } else {
            Msg.rareDrop(p, banner, "&7(" + (n > 1 ? n + "x " : "") + name + "&7)", chancePercent < SlayerMath.RARE_THRESHOLD ? mf : 0);
            if (chancePercent < 0.1) {
                Bukkit.broadcast(Text.c("&5&lSLAYER! &b" + p.getName() + " &7got " + name + " &7from a &"
                        + t.color + t.bossName(tier) + " " + SlayerMath.roman(tier) + "&7!"));
            }
        }
    }

    /** Gives n of an item in stack-sized pieces (Flawed Opal drops 240-400). */
    static void giveStacked(Player p, String id, int n, int maxStack) {
        int per = Math.max(1, maxStack);
        while (n > 0) {
            int k = Math.min(per, n);
            Sky.loot().give(p, id, k, LootSource.SLAYER);
            n -= k;
        }
    }

    /** Odds that a lootshare helper gets a weighted pick at all (TUNE: the reference does not publish it). */
    static final double LOOTSHARE_ROLL = 0.2;

    /** Helpers who did enough damage get a reduced roll: a fifth of the token and one weighted pick at 20% odds. */
    void lootshare(BossFight f) {
        double need = f.max() * Math.max(0, SlayerDials.LOOTSHARE_PERCENT.get()) / 100.0;
        for (Map.Entry<UUID, Double> en : f.damageBy.entrySet()) {
            if (en.getValue() < need) continue;
            Player h = Bukkit.getPlayer(en.getKey());
            if (h == null || !Sky.profiles().isReady(h)) continue;
            Msg.send(h, "&a&lLOOT SHARE! &7You helped kill " + f.ownerName + "'s &" + f.type.color + f.type.bossName(f.tierNo) + "&7!");
            for (SlayerCatalog.LootEntry e : f.type.loot) {
                if (e.token && e.dropsAt(f.tierNo) && Sky.items().exists(e.item)) {
                    int n = Math.max(1, (e.minAt(f.tierNo) + e.maxAt(f.tierNo)) / 2 / 5);
                    Sky.loot().give(h, e.item, n, LootSource.SLAYER);
                }
            }
            if (ThreadLocalRandom.current().nextDouble() < LOOTSHARE_ROLL) {
                Map<String, Double> c = chancesFor(h, f.type, f.tierNo, true);   // lootshare-only drops live here
                String pick = SlayerMath.pick(c, ThreadLocalRandom.current().nextDouble());
                SlayerCatalog.LootEntry e = pick.isEmpty() ? null : f.type.lootEntry(pick);
                if (e != null) give(h, f.type, e, f.tierNo, c.getOrDefault(pick, 0.0) * 100, magicFind(h, f.type, f.tierNo), false);
            }
        }
    }
}
