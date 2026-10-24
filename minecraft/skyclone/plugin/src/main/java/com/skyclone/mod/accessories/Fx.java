package com.skyclone.mod.accessories;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.stat.Stat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One parsed accessory / equipment effect ({@code ItemDef.extra.effects[]}, written by the generator).
 * A flat bag of optional fields: each type reads the ones it needs. Parsing never throws; an unknown
 * type is kept with {@link #known} false so the self-test can report it.
 */
final class Fx {

    static final Set<String> TYPES = Set.of("stat_if", "stat_pct", "stat_zero", "dmg_taken", "dmg_dealt", "burst",
            "coins_minute", "coin_find", "scavenger", "piggy", "fall", "fire_immune", "lava_immune", "potion",
            "intimidate", "heal_on_kill", "regen", "reaper", "counter", "nth_hit", "wedding", "crowd", "vamp_regen",
            "xp_boost", "bits_double", "gravity", "magic8", "auto_recomb", "compactor", "deletor", "equip_mult",
            "nearby_def", "per_skill", "set",
            // wave 3: effects that used to be lore-only hooks
            "magnet", "unseen", "poison_resist", "reflect", "contagion", "drop_boost", "tp_strike", "ability_boost", "weapon_buff", "vs_stat", "static", "piece_bonus", "pet_copy",
            // wave 3 fixer: Ancient Cloak, Catacombs Expert Ring + Professor's books
            "health_ability", "dungeon_xp",
            // wave 5: per-target Magic Find, bats, fire downsides, Flaming Fist, Soulweaver skull, counters, bags
            "mf_vs", "bat_person", "fire_taken", "fire_fist", "haunt_skull", "runic_counter", "keep_bag", "abicase",
            // wave 5 (continued): Organ Donor's Frozen Corpse ding
            "corpse_ding");

    final String type;
    final String source;     // item id that carries it
    final boolean known;

    Stat stat;
    double value, coins, pct, chance, dmg, cd, hp, per, blocks, save, min, max, str, window, crimsonPct, cap, range, mult;
    /** Wave 5: Bone Necklace's Tank-in-dungeons range / cap; Scourge Cloak's share of the last fire hit added to the next. */
    double tankRange, tankCap, carry;
    /** Wave 5: what a keep_bag holds ("mask" / "cake"). */
    String kind;
    int n, players, slots, amp, level, need;
    boolean multiplicative, scale;
    /** Tiered bonus: the values are PER PIECE, and only equipment pieces count here (armour's module pays its own). */
    boolean perPiece;
    String mob, weapon, next, effect, island, skill, name, prefix, item, match;
    Cond when = Cond.ALWAYS;
    List<String> members = new ArrayList<>();
    List<Fx> subs = new ArrayList<>();

    private Fx(String type, String source) {
        this.type = type;
        this.source = source;
        this.known = TYPES.contains(type);
    }

    static Fx parse(JsonObject o, String source) {
        String t = str(o, "type", "?");
        Fx f = new Fx(t, source);
        f.stat = Stat.parse(str(o, "stat", null));
        f.value = num(o, "value");
        f.coins = num(o, "coins");
        f.pct = num(o, "pct");
        f.chance = num(o, "chance");
        f.dmg = num(o, "dmg");
        f.cd = num(o, "cd");
        f.hp = num(o, "hp");
        f.per = num(o, "per");
        f.blocks = num(o, "blocks");
        f.save = num(o, "save");
        f.min = num(o, "min");
        f.max = num(o, "max");
        f.str = num(o, "str");
        f.window = num(o, "window");
        f.crimsonPct = num(o, "crimsonPct");
        f.cap = num(o, "cap");
        f.range = num(o, "range");
        f.mult = num(o, "mult");
        f.tankRange = num(o, "tankRange");
        f.tankCap = num(o, "tankCap");
        f.carry = num(o, "carry");
        f.kind = str(o, "kind", null);
        f.n = (int) num(o, "n");
        f.players = (int) num(o, "players");
        f.slots = (int) num(o, "slots");
        f.amp = (int) num(o, "amp");
        f.level = (int) num(o, "level");
        f.need = (int) num(o, "need");
        f.multiplicative = o.has("mult") && o.get("mult").isJsonPrimitive() && o.get("mult").getAsJsonPrimitive().isBoolean()
                && o.get("mult").getAsBoolean();
        if (f.multiplicative) f.mult = 0;
        f.scale = o.has("scale") && o.get("scale").getAsBoolean();
        f.perPiece = o.has("perPiece") && o.get("perPiece").getAsBoolean();
        f.mob = str(o, "mob", null);
        f.weapon = str(o, "weapon", null);
        f.next = str(o, "next", null);
        f.effect = str(o, "effect", null);
        f.island = str(o, "island", null);
        f.skill = str(o, "skill", null);
        f.name = str(o, "name", null);
        f.prefix = str(o, "prefix", null);
        f.item = str(o, "item", null);
        f.match = str(o, "match", null);
        f.when = Cond.parse(str(o, "when", null));
        if (o.has("members") && o.get("members").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("members")) f.members.add(e.getAsString());
        }
        if (o.has("effects") && o.get("effects").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("effects")) {
                if (e.isJsonObject()) f.subs.add(parse(e.getAsJsonObject(), source));
            }
        }
        return f;
    }

    static List<Fx> parseAll(JsonObject extra, String source) {
        List<Fx> out = new ArrayList<>();
        if (extra == null || !extra.has("effects") || !extra.get("effects").isJsonArray()) return out;
        JsonArray arr = extra.getAsJsonArray("effects");
        for (JsonElement e : arr) {
            try {
                if (e.isJsonObject()) out.add(parse(e.getAsJsonObject(), source));
            } catch (RuntimeException ex) {
                out.add(new Fx("broken:" + ex.getClass().getSimpleName(), source));
            }
        }
        return out;
    }

    /**
     * What the class-conditional stat effects among {@code fx} (Adaptive Belt) grant for dungeon class {@code cls},
     * one display line per stat ("&amp;c+10 ❁ Strength"); null when none of {@code fx} depends on the class, so a
     * caller can tell "nothing class-based is worn" from "worn, but no bonus for this class" (empty list). Uses the
     * engine's own {@link Cond#test}, with every fact except the class false. Pure (unit-tested).
     */
    static List<String> classBonusLines(List<Fx> fx, String cls) {
        List<Fx> flat = new ArrayList<>();
        for (Fx f : fx) f.flatten(flat);
        Cond.Ctx ctx = new Cond.Ctx() {
            @Override public boolean night() { return false; }
            @Override public boolean burning() { return false; }
            @Override public boolean inDungeon() { return false; }
            @Override public String island() { return null; }
            @Override public String zone() { return null; }
            @Override public int season() { return -1; }
            @Override public String dungeonClass() { return cls; }
        };
        java.util.Map<Stat, Double> sum = new java.util.EnumMap<>(Stat.class);
        boolean any = false;
        for (Fx f : flat) {
            if (!f.type.equals("stat_if") || f.stat == null || !f.when.uses("class")) continue;
            any = true;
            if (f.when.test(ctx)) sum.merge(f.stat, f.value, Double::sum);
        }
        if (!any) return null;
        List<String> lines = new ArrayList<>();
        for (java.util.Map.Entry<Stat, Double> e : sum.entrySet()) {
            double v = e.getValue();
            String n = v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
            lines.add("&" + e.getKey().color + (v >= 0 ? "+" : "") + n + (e.getKey().percent ? "%" : "") + " "
                    + e.getKey().symbol + " " + e.getKey().display);
        }
        return lines;
    }

    /** Every leaf effect, sets flattened (for validity checks). */
    void flatten(List<Fx> into) {
        into.add(this);
        for (Fx s : subs) s.flatten(into);
    }

    private static String str(JsonObject o, String k, String def) {
        if (!o.has(k) || o.get(k).isJsonNull()) return def;
        JsonElement e = o.get(k);
        return e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static double num(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonPrimitive()) return 0;
        try { return o.get(k).getAsDouble(); } catch (RuntimeException e) { return 0; }
    }
}
