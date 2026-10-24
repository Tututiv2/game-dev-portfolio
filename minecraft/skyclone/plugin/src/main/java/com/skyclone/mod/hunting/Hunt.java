package com.skyclone.mod.hunting;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The hunting state machine and {@link Features.Hunting}: the Hunting Box, syphoning, attribute levels,
 * capturing (shards + Hunting XP + the collection), Charm Chance and Hunting Fortune.
 */
final class Hunt implements Features.Hunting {

    static ShardCatalog cat = new ShardCatalog(List.of(), List.of());
    private static final Map<UUID, AttrSnapshot> SNAP = new HashMap<>();

    static final Set<String> FORAGING_ISLANDS = Set.of("park", "galatea");
    static final Set<String> FISHING_ISLANDS = Set.of("backwater_bayou");
    static final Set<String> MINING_ISLANDS = Set.of("gold_mine", "deep_caverns", "dwarven_mines", "crystal_hollows", "glacite_tunnels", "mineshaft");
    static final Set<String> COMBAT_ISLANDS = Set.of("spiders_den", "the_end", "crimson_isle", "kuudra");

    static void loadCatalog() {
        try (InputStream a = Sky.plugin().getResource("data/hunting/shards.json");
             InputStream b = Sky.plugin().getResource("data/hunting/habitats.json")) {
            if (a == null) { Sky.log().severe("[hunting] data/hunting/shards.json is missing"); return; }
            cat = ShardCatalog.load(new InputStreamReader(a, StandardCharsets.UTF_8),
                    b == null ? null : new InputStreamReader(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Sky.log().severe("[hunting] could not read the shard catalog: " + e);
        }
    }

    static HuntingData data(Player p) { return Sky.profiles().member(p, "hunting", HuntingData.class); }

    static boolean ready(Player p) { return p != null && Sky.profiles().isReady(p); }

    // ---------------------------------------------------------------- attributes

    static AttrSnapshot snapshot(Player p) {
        if (!ready(p)) return AttrSnapshot.EMPTY;
        AttrSnapshot s = SNAP.get(p.getUniqueId());
        if (s == null) {
            HuntingData d = data(p);
            Map<String, Integer> levels = new HashMap<>();
            for (Map.Entry<String, Integer> e : d.syphoned.entrySet()) {
                ShardDef sd = cat.get(e.getKey());
                if (sd == null || e.getValue() == null) continue;
                int lvl = HuntMath.level(sd.rarity, e.getValue());
                if (lvl > 0) levels.put(sd.attr, lvl);
            }
            s = new AttrSnapshot(levels, cat, d.disabled);
            SNAP.put(p.getUniqueId(), s);
        }
        return s;
    }

    static void invalidate(Player p) {
        SNAP.remove(p.getUniqueId());
        try { Sky.stats().invalidate(p); } catch (RuntimeException ignored) { }
    }

    static void forget(UUID id) { SNAP.remove(id); }

    /** Does an effect condition hold for this player right now? */
    static boolean cond(Player p, String c) {
        if (c == null) return true;
        String island = Sky.worlds().islandOf(p.getWorld());
        return switch (c) {
            case "night" -> Sky.calendar().now().isNight();
            case "day" -> !Sky.calendar().now().isNight();
            case "foraging_islands" -> island != null && FORAGING_ISLANDS.contains(island);
            case "fishing_islands" -> island != null && FISHING_ISLANDS.contains(island);
            case "mining_islands" -> island != null && MINING_ISLANDS.contains(island);
            case "combat_islands" -> island != null && COMBAT_ISLANDS.contains(island);
            default -> c.startsWith("island:") && c.substring(7).equals(island);
        };
    }

    /** Charm Chance (%) towards a killed mob: skill perk + Charmed attribute + active salts. */
    static double charm(Player p, String island, boolean seaCreature) {
        if (!ready(p)) return 0;
        double c = HuntMath.skillCharm(Sky.service(SkillService.class).level(p, SkillType.HUNTING));
        c += snapshot(p).pseudo("charm_chance", x -> cond(p, x));
        HuntingData d = data(p);
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : d.salts.entrySet()) {
            if (e.getValue() == null || e.getValue() < now) continue;
            Salts.Salt s = Salts.get(e.getKey());
            if (s == null) continue;
            if (s.target == null || ("sea_creature".equals(s.target) && seaCreature)
                    || (s.target.startsWith("island:") && s.target.substring(7).equals(island)))
                c += s.charm;
        }
        return c;
    }

    /** Pull of a net in hand: the net's own Pull + Eelastic. */
    static double pull(Player p, double netPull) {
        return netPull + snapshot(p).pseudo("pull", x -> cond(p, x));
    }

    // ---------------------------------------------------------------- capture

    /**
     * A shard was CAPTURED by this player (lasso, net, black hole, trap, capsule, charm drop, fishing).
     * Gives 1 + Hunting Fortune shards as items, Hunting XP per shard and counts the collection.
     *
     * @param toolHf extra Hunting Fortune from the tool ("+5 on Forest Shards caught with this Lasso")
     * @param multiplier reward multiplier (a Sparkling critter is 10x)
     * @return shards given
     */
    static int capture(Player p, ShardDef s, String how, double toolHf, int multiplier) {
        if (!ready(p) || s == null) return 0;
        AttrSnapshot snap = snapshot(p);
        double hf = Sky.stats().get(p, Stat.HUNTING_FORTUNE) + toolHf + snap.hfRarity.getOrDefault(s.rarity, 0.0);
        int n = HuntMath.shardsPerCatch(hf, ThreadLocalRandom.current().nextDouble()) * Math.max(1, multiplier);
        HuntingData d = data(p);
        long before = d.caught.getOrDefault(s.id, 0L);
        d.caught.merge(s.id, (long) n, Long::sum);
        d.totalCaught += n;
        Sky.loot().give(p, s.id, n, LootSource.MOB_DROP);
        xp(p, HuntMath.captureXp(s.rarity) * n, "hunting:capture:" + how);
        Msg.send(p, (before == 0 ? "&d&lNEW SHARD! " : "&d&lSHARD CAPTURED! ") + rarityColor(s.rarity) + s.name + " Shard"
                + (n > 1 ? " &8x" + n : "") + " &7(" + Text.titleCase(how) + ")");
        Msg.sound(p, before == 0 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_EXPERIENCE_ORB_PICKUP, before == 0 ? 1.2f : 1.6f);
        return n;
    }

    static void xp(Player p, double amount, String source) {
        double v = amount * HDials.XP_MULT.get();
        if (v > 0) Sky.service(SkillService.class).addXp(p, SkillType.HUNTING, v, source);
    }

    static String rarityColor(String rarity) {
        return switch (HuntMath.rarityIndex(rarity)) {
            case 0 -> "&f";
            case 1 -> "&a";
            case 2 -> "&9";
            case 3 -> "&5";
            default -> "&6";
        };
    }

    // ---------------------------------------------------------------- box + syphon

    static int boxCount(Player p, String id) { return ready(p) ? data(p).box.getOrDefault(id, 0) : 0; }

    /** Move shard ITEMS from the inventory into the Hunting Box. Returns how many moved. */
    static int storeFromInventory(Player p, String id, int max) {
        if (!ready(p) || cat.get(id) == null) return 0;
        int removed = Sky.items().remove(p, id, max);
        if (removed > 0) data(p).box.merge(id, removed, Integer::sum);
        return removed;
    }

    /** Take shards out of the box as items. */
    static int withdraw(Player p, String id, int n) {
        if (!ready(p)) return 0;
        HuntingData d = data(p);
        int have = d.box.getOrDefault(id, 0);
        int take = Math.min(have, Math.max(0, n));
        if (take <= 0) return 0;
        put(d, id, have - take);
        Sky.loot().give(p, id, take, LootSource.STORAGE);
        return take;
    }

    static void put(HuntingData d, String id, int n) {
        if (n <= 0) d.box.remove(id); else d.box.put(id, n);
    }

    /**
     * Syphon shards from the box into the shard's attribute, up to the next level ({@code all}: up to max or
     * until the box runs out). Returns shards used; messages the player.
     */
    static int syphon(Player p, ShardDef s, boolean all) {
        if (!ready(p) || s == null) return 0;
        if (s.unconsumable) { Msg.error(p, "&cThe " + s.name + " Shard cannot be syphoned: it only fuses."); return 0; }
        HuntingData d = data(p);
        int have = d.box.getOrDefault(s.id, 0);
        int done = d.syphoned.getOrDefault(s.id, 0);
        int before = HuntMath.level(s.rarity, done);
        if (before >= HuntMath.MAX_LEVEL) { Msg.error(p, "&c" + s.attrName + " is already at its max level."); return 0; }
        int want = all ? HuntMath.maxShards(s.rarity) - done : HuntMath.toNext(s.rarity, done);
        int use = Math.min(have, want);
        if (use <= 0) {
            Msg.error(p, "&cYou need &f" + HuntMath.toNext(s.rarity, done) + "x " + s.name + " Shard &cin your Hunting Box.");
            return 0;
        }
        put(d, s.id, have - use);
        d.syphoned.put(s.id, done + use);
        d.totalSyphoned += use;
        int after = HuntMath.level(s.rarity, done + use);
        invalidate(p);
        xp(p, HuntMath.syphonXp(s.rarity) * use, "hunting:syphon");
        if (after > before) {
            Msg.send(p, "&d&lATTRIBUTE " + (before == 0 ? "UNLOCKED" : "LEVEL UP") + "! " + rarityColor(s.rarity) + s.attrName
                    + " &8" + (before == 0 ? "" : Text.roman(before) + " &7-> ") + "&a" + Text.roman(after));
            Msg.send(p, "  " + s.effectText(after, snapshot(p).echoMult.getOrDefault(s.attr, 1.0)));
            Msg.sound(p, Sound.BLOCK_BEACON_POWER_SELECT, 1.4f);
        } else {
            Msg.send(p, "&aSyphoned &f" + use + "x " + s.name + " Shard&a. &7" + HuntMath.toNext(s.rarity, done + use) + " more to "
                    + s.attrName + " " + Text.roman(after + 1) + ".");
        }
        return use;
    }

    /** Fuse: consumes the inputs from the box, adds the output to the box. */
    static boolean fuse(Player p, ShardDef a, ShardDef b, Fusion.Output out) {
        if (!ready(p) || a == null || b == null || out == null) return false;
        ShardDef res = cat.get(out.shardId());
        if (res == null) return false;
        // re-validate: the output must still be one this pair gives
        boolean valid = false;
        for (Fusion.Output o : Fusion.outputs(cat, a, b)) if (o.equals(out)) { valid = true; break; }
        if (!valid) return false;
        HuntingData d = data(p);
        int ca = Fusion.cost(a), cb = Fusion.cost(b);
        int ha = d.box.getOrDefault(a.id, 0), hb = d.box.getOrDefault(b.id, 0);
        if (a.id.equals(b.id) ? ha < ca + cb : (ha < ca || hb < cb)) {
            Msg.error(p, "&cYou need " + ca + "x " + a.name + " and " + cb + "x " + b.name + " Shards in your Hunting Box.");
            return false;
        }
        if (a.id.equals(b.id)) put(d, a.id, ha - ca - cb);
        else { put(d, a.id, ha - ca); put(d, b.id, hb - cb); }
        int amount = out.amount();
        if (out.kind().startsWith("family:Reptile")) {
            double pure = snapshot(p).value("pure_reptile");
            if (pure > 0 && ThreadLocalRandom.current().nextDouble() * 100 < pure) amount *= 2;
        }
        d.box.merge(res.id, amount, Integer::sum);
        d.totalFusions++;
        Msg.send(p, "&d&lFUSION! &7" + a.name + " + " + b.name + " &7-> " + rarityColor(res.rarity) + res.name + " Shard"
                + (amount > 1 ? " &8x" + amount : "") + " &7(sent to your Hunting Box)");
        Msg.sound(p, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2f);
        return true;
    }

    // ---------------------------------------------------------------- Features.Hunting

    @Override public int shards(Player p, String shardId) {
        ShardDef s = cat.find(shardId);
        return s == null ? 0 : boxCount(p, s.id) + (p == null ? 0 : Sky.items().count(p, s.id));
    }

    /** Other modules' rewards (Leviathan chests, slayer drops...): straight into the Hunting Box, with Hunting XP. */
    @Override public void addShards(Player p, String shardId, int n, String reason) {
        ShardDef s = cat.find(shardId);
        if (s == null || n <= 0 || !ready(p)) {
            if (s == null) Sky.log().info("[hunting] addShards: unknown shard " + shardId + " (" + reason + ")");
            return;
        }
        HuntingData d = data(p);
        d.box.merge(s.id, n, Integer::sum);
        d.caught.merge(s.id, (long) n, Long::sum);
        d.totalCaught += n;
        xp(p, HuntMath.captureXp(s.rarity) * n, "hunting:" + (reason == null ? "reward" : reason));
        Msg.send(p, "&d+" + n + " " + rarityColor(s.rarity) + s.name + " Shard &7(Hunting Box)");
    }

    /**
     * Plain key ("fishing_speed") -> attribute level 0..10. Two extra forms for modules that want the number:
     * {@code "value:<attr>"} -> level x per-level value x echo (e.g. value:fishing_speed = Fishing Speed granted),
     * {@code "stat:<pseudo>"} -> charm_chance / tracking / pull / respiration / pressure_resistance... summed for
     * the player's current place.
     */
    @Override public double attributeLevel(Player p, String attribute) {
        if (p == null || attribute == null || !ready(p)) return 0;
        String a = attribute.toLowerCase(Locale.ROOT);
        AttrSnapshot s = snapshot(p);
        if (a.startsWith("value:")) return s.value(a.substring(6));
        if (a.startsWith("stat:")) {
            String st = a.substring(5);
            if (st.equals("charm_chance")) return charm(p, Sky.worlds().islandOf(p.getWorld()), false);
            if (st.equals("tracking")) return s.pseudo(st, x -> cond(p, x)) + perkTracking();
            return s.pseudo(st, x -> cond(p, x));
        }
        if (a.startsWith("attribute_shard_")) a = a.substring(16);
        ShardDef sd = cat.find(a);
        if (sd != null) a = sd.attr;
        return s.level(a);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Tracking from the mayor's office: Diana's Huntress' Intuition (+10, minister-eligible; mayors.json gives the
     * number and lists hunting as its owner). Tracking is read through {@code attributeLevel(p, "stat:tracking")}: the
     * mayors module's Griffin Burrow pools (Mythology.tracking) are the system that spends it.
     */
    static double perkTracking() {
        try {
            Features.Mayors m = Sky.service(Features.Mayors.class);
            return m != null && m.perkActive(HUNTRESS_PERK) ? Math.max(0, m.perkValue(HUNTRESS_PERK)) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    static final String HUNTRESS_PERK = "diana.huntress_intuition";

    static ShardDef shardOfItem(String itemId) {
        if (itemId == null || !itemId.startsWith("SHARD_")) return null;
        return cat.get(itemId);
    }

    static JsonObject toolExtra(String itemId) {
        ItemDef d = itemId == null ? null : Sky.items().def(itemId);
        if (d == null || d.extra == null || !d.extra.has("tool")) return null;
        return d.extra;
    }

    static String tool(String itemId) {
        JsonObject x = toolExtra(itemId);
        return x == null ? null : x.get("tool").getAsString();
    }

    static double num(JsonObject o, String k, double def) {
        try { return o != null && o.has(k) ? o.get(k).getAsDouble() : def; } catch (RuntimeException e) { return def; }
    }
}
