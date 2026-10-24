package com.skyclone.mod.foraging;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.EnchantDef;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every foraging bonus that lives OUTSIDE the stat sheet (wave 5): per-wood fortune and Sweep, breath and
 * pressure, contest/whisper/essence/HotF-XP multipliers, honey and Tree Gift odds. Each reads the systems
 * that own the source at use time, so a missing module just answers 0:
 * <ul>
 *   <li><b>accessories</b> ({@code Features.Accessories.has}): Moonmarsh/Torrent talismans (Fig/Mangrove/Spiral
 *       Fortune), Pressure and Respiration talismans, Lumberjack (axe comes back sooner), Honeycomb (lure faster).
 *       Their numbers are read from the item's {@code extra.otherStats} where the data has them.</li>
 *   <li><b>armour + equipment</b> worn: {@code extra.otherStats} (Pressure Resistance on Snorkeling/Diver/Abyssal),
 *       {@code extra.respiration}, the Respiration and Scuba enchants, the Marshy reforge, the contest belts,
 *       a full Diver's/Abyssal set breathes forever.</li>
 *   <li><b>pets</b> ({@code Features.Pets}): Frog Respiration (0.1/level), Relic Drone (no throw penalty),
 *       Sloth's "Grove Favourite" (contest points).</li>
 *   <li><b>hunting attributes</b> ({@code Features.Hunting.attributeLevel}, "value:" / "stat:" forms).</li>
 *   <li><b>Heart of the Forest</b>: Pressure Resistance, Iron Lungs, Starfall Supreme and the two daily perks.</li>
 * </ul>
 */
final class Bonuses {

    private Bonuses() {}

    // ================================================================================ sources

    static double hunt(Player p, String key) {
        try { return Math.max(0, Sky.service(Features.Hunting.class).attributeLevel(p, key)); } catch (RuntimeException e) { return 0; }
    }

    /** Index (0 = first) of the first id in {@code ids} the accessory bag counts, -1 for none. List the best first. */
    static int accessory(Player p, String... ids) {
        try {
            Features.Accessories acc = Sky.service(Features.Accessories.class);
            for (int i = 0; i < ids.length; i++) if (acc.has(p, ids[i])) return i;
        } catch (RuntimeException ignored) { }
        return -1;
    }

    /** The best {@code extra.otherStats.<key>} among the accessories of a family the player has. */
    static double accessoryOther(Player p, String key, String... ids) {
        int i = accessory(p, ids);
        return i < 0 ? 0 : otherStat(Sky.items().def(ids[i]), key);
    }

    /** A number from an item def's {@code extra.otherStats} (case-insensitive key), 0 when absent. */
    static double otherStat(ItemDef d, String key) {
        if (d == null || d.extra == null || !d.extra.has("otherStats") || !d.extra.get("otherStats").isJsonObject()) return 0;
        JsonObject o = d.extra.getAsJsonObject("otherStats");
        for (var e : o.entrySet()) {
            if (!e.getKey().equalsIgnoreCase(key)) continue;
            try { return e.getValue().getAsDouble(); } catch (RuntimeException ex) { return 0; }
        }
        return 0;
    }

    static double extraNumber(ItemDef d, String key) {
        if (d == null || d.extra == null || !d.extra.has(key)) return 0;
        JsonElement e = d.extra.get(key);
        try { return e.isJsonPrimitive() ? e.getAsDouble() : 0; } catch (RuntimeException ex) { return 0; }
    }

    /** Worn armour and equipment (necklace/cloak/belt/gloves), only our items. */
    static List<ItemStack> worn(Player p) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : p.getInventory().getArmorContents()) if (ours(it)) out.add(it);
        try {
            List<ItemStack> eq = Sky.service(Features.Equipment.class).worn(p);
            if (eq != null) for (ItemStack it : eq) if (ours(it)) out.add(it);
        } catch (RuntimeException ignored) { }
        return out;
    }

    private static boolean ours(ItemStack it) { return it != null && !it.getType().isAir() && Sky.items().idOf(it) != null; }

    static double enchantValue(ItemStack it, String enchant) {
        ItemData data = Sky.items().data(it);
        int lv = data == null ? 0 : data.enchant(enchant);
        if (lv <= 0) return 0;
        EnchantDef def = Sky.items().enchant(enchant);
        return def == null ? 0 : def.value(lv);
    }

    static String petType(Player p) {
        try { String t = Sky.service(Features.Pets.class).activeType(p); return t == null ? "" : t.toUpperCase(Locale.ROOT); }
        catch (RuntimeException e) { return ""; }
    }

    static int petLevel(Player p) {
        try { return Sky.service(Features.Pets.class).activeLevel(p); } catch (RuntimeException e) { return 0; }
    }

    static double petAbility(Player p, String key) {
        try { return Math.max(0, Sky.service(Features.Pets.class).abilityValue(p, key, "a")); } catch (RuntimeException e) { return 0; }
    }

    static long collection(Player p, String id) {
        try { return Sky.service(Features.Collections.class).count(p, id); } catch (RuntimeException e) { return 0; }
    }

    // ================================================================================ the daily perks

    /** Today's face of a daily HotF perk (0..n-1), or -1 when the perk is not taken / switched off. */
    static int daily(Player p, ForagingData d, String perk, int n) {
        if (d == null || !d.hotfUnlocked || Hotf.level(d, perk) <= 0) return -1;
        long day = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        return HiveMath.dailyPick(p.getUniqueId().getMostSignificantBits(), p.getUniqueId().getLeastSignificantBits(), day, perk, n);
    }

    static int dailyGrove(Player p) { return Sky.profiles().isReady(p) ? daily(p, ForagingModule.data(p), "daily_grove", 4) : -1; }

    static int dailyHive(Player p) { return Sky.profiles().isReady(p) ? daily(p, ForagingModule.data(p), "daily_hive", 4) : -1; }

    // ================================================================================ chopping

    private static String woodKey(Wood w) {
        return switch (w) { case FIG -> "fig"; case MANGROVE -> "mangrove"; case HELIX -> "helix"; default -> null; };
    }

    /** Fig / Mangrove / Spiral Fortune: added to Foraging Fortune for that wood only (research 01 §2.2). */
    static double woodFortune(Player p, Wood w) {
        String k = woodKey(w);
        if (k == null) return 0;
        String stat = k.toUpperCase(Locale.ROOT) + "_FORTUNE";
        double v = w == Wood.HELIX
                ? accessoryOther(p, stat, "TORRHUS_ARTIFACT", "TORRHUS_RING", "TORRHUS_TALISMAN")
                : accessoryOther(p, stat, "MOONGLADE_ARTIFACT", "MOONGLADE_RING", "MOONGLADE_TALISMAN");
        for (ItemStack it : worn(p)) v += otherStat(Sky.items().def(Sky.items().idOf(it)), stat);
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (ours(hand)) v += otherStat(Sky.items().def(Sky.items().idOf(hand)), stat);
        v += hunt(p, "stat:" + k + "_fortune");
        v += hunt(p, "value:" + k + "_collector") * HiveMath.digits(collection(p, w.itemId));
        int face = dailyGrove(p);
        if (face == HiveMath.DAILY_FIG && w == Wood.FIG || face == HiveMath.DAILY_MANGROVE && w == Wood.MANGROVE
                || face == HiveMath.DAILY_HELIX && w == Wood.HELIX) v += 50;
        return v;
    }

    /** Sweep towards one wood (Crow / Heron / Vulture "sharpening" attributes). */
    static double woodSweep(Player p, Wood w) {
        String k = woodKey(w);
        return k == null ? 0 : hunt(p, "value:" + k + "_sharpening");
    }

    /** Bambuleaf "Strong Arms": Sweep on throws. */
    static double throwSweep(Player p) { return hunt(p, "value:strong_arms"); }

    /** Mochibear "Strong Legs": Sweep on melee cuts. */
    static double meleeSweep(Player p) { return hunt(p, "value:strong_legs"); }

    /** Torrid "First Sweep": % stronger first cut of each tree. */
    static double firstSweepPercent(Player p) { return hunt(p, "value:first_sweep"); }

    /** Grizzly Bear "Tough Claws": toughness removed from every tree you cut. */
    static double toughClaws(Player p) { return hunt(p, "value:tough_claws"); }

    /** Hummingbird "Chop": % chance of one more log. */
    static double chopPercent(Player p) { return hunt(p, "value:chop"); }

    /** Extra Timber points: hunting's Timber pseudo stat, Woodpecker "Forest Felling" on spiral trees. */
    static double timberBonus(Player p, Wood w) {
        return hunt(p, "stat:timber") + (w == Wood.HELIX ? hunt(p, "value:forest_felling") : 0);
    }

    /** Relic Drone pet: "your foraging axe loses its throwing penalty". */
    static boolean noThrowPenalty(Player p) { return petType(p).equals("PRECURSOR_DRONE"); }

    /** Multiplier on the throwing axe's cooldown: Lumberjack 5/10/15 %, Drybark attribute. */
    static double throwCooldownFactor(Player p) {
        int lj = accessory(p, "LUMBERJACK_ARTIFACT", "LUMBERJACK_RING", "LUMBERJACK_TALISMAN");
        double pct = (lj < 0 ? 0 : 15 - 5 * lj) + hunt(p, "value:axe_cooldown");
        return Math.max(0.1, 1 - pct / 100.0);
    }

    // ================================================================================ diving

    /** Respiration (research 01 §2.2, base 30). */
    static double respiration(Player p) {
        double v = DepthMath.BASE_RESPIRATION;
        v += accessoryOther(p, "RESPIRATION", "RESPIRATION_ARTIFACT", "RESPIRATION_RING", "RESPIRATION_TALISMAN");
        for (ItemStack it : worn(p)) {
            ItemDef d = Sky.items().def(Sky.items().idOf(it));
            v += otherStat(d, "RESPIRATION") + extraNumber(d, "respiration") + enchantValue(it, "respiration");
        }
        v += hunt(p, "stat:respiration");
        if (Sky.profiles().isReady(p)) v += Hotf.level(ForagingModule.data(p), "iron_lungs");
        if (petType(p).equals("FROG")) v += 0.1 * petLevel(p);   // pets data: Frog x1 0.1 -> x100 10 Respiration
        return v;
    }

    /** Pressure Resistance (base 0). */
    static double pressureResistance(Player p) {
        double v = accessoryOther(p, "PRESSURE_RESISTANCE", "PRESSURE_ARTIFACT", "PRESSURE_RING", "PRESSURE_TALISMAN");
        for (ItemStack it : worn(p)) {
            ItemDef d = Sky.items().def(Sky.items().idOf(it));
            v += otherStat(d, "PRESSURE_RESISTANCE") + enchantValue(it, "scuba");
        }
        v += hunt(p, "stat:pressure_resistance");
        if (Sky.profiles().isReady(p)) v += Hotf.level(ForagingModule.data(p), "pressure_resistance");
        return v;
    }

    /** A full Diver's or Abyssal set: "One with the Fish", breathe permanently underwater (research 06). */
    static boolean breathesWater(Player p) {
        String set = null;
        int n = 0;
        for (ItemStack it : p.getInventory().getArmorContents()) {
            if (!ours(it)) return false;
            ItemDef d = Sky.items().def(Sky.items().idOf(it));
            String s = d == null ? null : d.setId != null ? d.setId
                    : d.extra != null && d.extra.has("setKey") ? d.extra.get("setKey").getAsString() : null;
            if (s == null) return false;
            s = s.toUpperCase(Locale.ROOT);
            if (!(s.equals("DIVER") || s.equals("ABYSSAL"))) return false;
            if (set != null && !set.equals(s)) return false;
            set = s;
            n++;
        }
        return n == 4;
    }

    // ================================================================================ contests, whispers, essence

    /** % more Starfall Contest points. {@code chop} = points from cutting trees (the Sloth only helps those). */
    static double contestPercent(Player p, boolean chop) {
        double pct = hunt(p, "value:starlyn_training");
        if (Sky.profiles().isReady(p)) pct += 0.2 * Hotf.level(ForagingModule.data(p), "starlyn_supreme");
        for (ItemStack it : worn(p)) {
            String id = Sky.items().idOf(it);
            if ("MANGROVE_VINE".equals(id)) pct += 5;        // Moonmarsh Belt (accessories data lore)
            else if ("TORRHUS_BELT".equals(id)) pct += 10;   // Torrent Belt
        }
        if (chop) pct += petAbility(p, "sloth_starlyn_s_favorite");
        return pct;
    }

    /** Pieces worn (armour + equipment) carrying a reforge. */
    static int reforged(Player p, String reforge) {
        int n = 0;
        for (ItemStack it : worn(p)) {
            ItemData d = Sky.items().data(it);
            if (d != null && reforge.equalsIgnoreCase(d.reforge)) n++;
        }
        return n;
    }

    /** Forest Whispers multiplier: Marshy reforge +1 % per worn piece (reforges request 20), Pangolin attribute. */
    static double forestWhisperFactor(Player p) { return 1 + (reforged(p, "marshy") + hunt(p, "value:atomized_whispers")) / 100.0; }

    static double desertWhisperFactor(Player p) { return 1 + hunt(p, "value:atomized_whispers") / 100.0; }

    /** Dreadwing "Forest Essence" attribute. */
    static double essenceFactor(Player p) { return 1 + hunt(p, "value:forest_essence") / 100.0; }

    /** Ember "Galatea Training": % more HotF XP. */
    static double hotfXpFactor(Player p) { return 1 + (hunt(p, "value:galatea_training") + bingoClimber(p)) / 100.0; }

    /** Bingo pet "Climber": % more HotM AND HotF XP (pets marks it info; mining owns the HotM half). */
    static double bingoClimber(Player p) { return "BINGO".equals(petType(p)) ? petAbility(p, "bingo_climber") : 0; }

    /** Harpy "Tree Lurker" attribute: % better odds of a creature in a Tree Gift (5 per level). */
    static double treeLurkerPercent(Player p) { return hunt(p, "value:tree_lurker"); }

    // ================================================================================ Tree Gifts

    /** Rare-item odds in a Tree Gift: Piranha (Moonmarsh) / Stingray (canyon) "serendipity". */
    static double giftRareFactor(Player p, Wood w) {
        return 1 + hunt(p, w == Wood.HELIX ? "value:torrhus_serendipity" : "value:moonglade_serendipity") / 100.0;
    }

    /** Puck "Forest Serendipity": % chance a gift holds double loot. */
    static double giftDoublePercent(Player p) { return hunt(p, "value:forest_serendipity"); }

    // ================================================================================ honey

    /** % faster lures on lathered trees: Honeycomb talismans 5/10/15, Hivehog, the daily face 20. */
    static double latherFasterPercent(Player p) {
        int hc = accessory(p, "HONEYCOMB_ARTIFACT", "HONEYCOMB_RING", "HONEYCOMB_TALISMAN");
        double pct = (hc < 0 ? 0 : 15 - 5 * hc) + hunt(p, "value:honey_speed");
        if (dailyHive(p) == HiveMath.DAILY_LURE) pct += 20;
        return pct;
    }

    /** Honeybuzz "Honey Haul": % chance a hive holds double. */
    static double honeyHaulPercent(Player p) { return hunt(p, "value:honey_haul"); }

    /** Queen Bee "Honey Refill": % chance a hive refills at once. */
    static double honeyRefillPercent(Player p) { return hunt(p, "value:honey_refill"); }

    /** Pollendart "Honey Luck" + the daily face (25 %): chance of a second critter. */
    static double honeyLuckPercent(Player p) { return hunt(p, "value:honey_luck") + (dailyHive(p) == HiveMath.DAILY_SECOND ? 25 : 0); }

    /** Honey Badger "Honey Serendipity": % chance the critter comes at once. */
    static double honeySerendipityPercent(Player p) { return hunt(p, "value:honey_serendipity"); }

    /** Hivecolossus "Lathering Greed": % more Honeycomb from lured critters. */
    static double latheringGreedPercent(Player p) { return hunt(p, "value:lathering_greed"); }
}
