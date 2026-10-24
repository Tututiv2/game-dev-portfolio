package com.skyclone.mod.enchanting;

import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the Anvil does, as pure functions (unit tested). Research 08 §3.3 + 01 §14.1:
 * <ul>
 *   <li>item + book: adds the enchant; a higher book level replaces a lower one; an equal level is
 *       upgraded by one if the enchant can be combined and the anvil cap allows it;</li>
 *   <li>book + book of the same enchant AND level: one book one level higher (anvil cap);</li>
 *   <li>conflicts are refused, EXCEPT ultimates (a new ultimate replaces the old one) and One For All
 *       (applying it strips every enchant it conflicts with);</li>
 *   <li>Hot Potato Book: +1 up to 10; Fuming Potato Book: +1 from 10 up to 15 (weapons and armour).</li>
 * </ul>
 * Anvil XP cost: research clone default, {@code sum(newLevel x 2)} over the enchants that changed. TUNE.
 */
public final class AnvilRules {

    private AnvilRules() {}

    public static final String OFA = "ultimate_one_for_all";

    public static final class Result {
        public boolean ok;
        public String reason = "";
        /** XP levels it costs. */
        public int cost;
        /** New enchant map for the left item (item + book). */
        public LinkedHashMap<String, Integer> enchants;
        /** Enchants that were stripped (replaced ultimate, One For All). */
        public final List<String> removed = new ArrayList<>();
        /** New potato-book count (potato), else -1. */
        public int potatoBooks = -1;
        /** book + book: the resulting book. */
        public String bookEnchant;
        public int bookLevel;

        static Result fail(String why) { Result r = new Result(); r.ok = false; r.reason = why; return r; }
    }

    /** Highest level two books (or a book and an equal item level) can be combined up to. */
    public static int anvilCap(EnchantSpec s, int overTable) {
        if (!s.combinable) return 0;
        if (s.onTable()) return Math.min(s.maxLevel, s.tableMax + Math.max(0, overTable));
        return s.maxLevel;
    }

    public static Result applyBook(EnchantRegistry reg, ItemDef left, Map<String, Integer> current,
                                   Map<String, Integer> book, int skillLevel, boolean enforceSkill, int overTable) {
        if (left == null) return Result.fail("Put an item in the left slot.");
        if (book == null || book.isEmpty()) return Result.fail("That book holds no enchantments.");
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>(current == null ? Map.of() : current);
        Result r = new Result();
        int cost = 0;
        for (Map.Entry<String, Integer> e : book.entrySet()) {
            EnchantSpec s = reg.spec(e.getKey());
            int lvl = e.getValue() == null ? 0 : e.getValue();
            if (s == null || lvl <= 0) return Result.fail("That book's enchantment is unknown.");
            if (!s.appliesTo(left)) return Result.fail(s.name + " cannot be applied to this item!");
            if (enforceSkill && s.skill > skillLevel) return Result.fail("Requires Enchanting " + s.skill + " to apply " + s.name + "!");
            Integer cur = out.get(s.id);
            if (cur != null) {
                int next;
                if (lvl > cur) next = lvl;
                else if (lvl == cur && cur + 1 <= anvilCap(s, overTable)) next = cur + 1;
                else return Result.fail("The item already has " + s.name + " " + EnchantSpec.roman(cur) + "!");
                out.put(s.id, next);
                cost += next * 2;
                continue;
            }
            // conflicts with what is on the item
            for (String have : new ArrayList<>(out.keySet())) {
                EnchantSpec hs = reg.spec(have);
                boolean clash = s.conflictsWith(have) || (hs != null && hs.conflictsWith(s.id))
                        || (s.ultimate && hs != null && hs.ultimate);
                if (!clash) continue;
                if (s.ultimate && hs != null && hs.ultimate) { out.remove(have); r.removed.add(have); continue; }
                if (OFA.equals(s.id)) { out.remove(have); r.removed.add(have); continue; }
                return Result.fail(s.name + " conflicts with " + (hs == null ? have : hs.name) + "!");
            }
            out.put(s.id, lvl);
            cost += lvl * 2;
        }
        r.ok = true;
        r.enchants = out;
        r.cost = cost;
        return r;
    }

    public static Result combineBooks(EnchantRegistry reg, String enchantA, int levelA, String enchantB, int levelB, int overTable) {
        EnchantSpec s = reg.spec(enchantA);
        if (s == null || !enchantA.equals(enchantB)) return Result.fail("Only two books of the same enchantment combine.");
        if (levelA != levelB) return Result.fail("Both books must be the same level.");
        if (!s.combinable) return Result.fail(s.name + " cannot be combined!");
        int next = levelA + 1;
        if (next > anvilCap(s, overTable)) return Result.fail(s.name + " " + EnchantSpec.roman(levelA) + " cannot be combined any higher!");
        Result r = new Result();
        r.ok = true;
        r.bookEnchant = s.id;
        r.bookLevel = next;
        r.cost = next * 2;
        return r;
    }

    /** data/enchanting/anvil_upgrades.json row: an Endcap Upgrade item raising {@code enchant} from {@code requires} by one. */
    public static final class Endcap {
        public String item;
        public String enchant;
        public int requires;
    }

    /** ItemData.extra flag set once an Endcap Upgrade of this enchant has been applied ("Can be applied once"). */
    public static String endcapFlag(String enchant) { return "endcap_" + enchant; }

    /**
     * Item + Endcap Upgrade (End Stone Idol, Golden Bounty, Gold Bottle Cap, ...; NEU lore): the item must carry the
     * enchant at EXACTLY {@code requires}; it becomes {@code requires + 1}, once per item.
     */
    public static Result endcap(EnchantRegistry reg, ItemDef left, Map<String, Integer> current, Endcap up, boolean alreadyApplied) {
        if (left == null) return Result.fail("Put an item in the left slot.");
        EnchantSpec s = up == null ? null : reg.spec(up.enchant);
        if (s == null) return Result.fail("That upgrade's enchantment is unknown.");
        if (!s.appliesTo(left)) return Result.fail(s.name + " cannot be on this item!");
        if (alreadyApplied) return Result.fail("This item already took one of these upgrades.");
        int cur = current == null ? 0 : current.getOrDefault(s.id, 0);
        if (cur != up.requires) return Result.fail("Requires " + s.name + " " + EnchantSpec.roman(up.requires) + "!");
        int next = up.requires + 1;
        if (next > s.maxLevel) return Result.fail(s.name + " cannot go any higher!");
        Result r = new Result();
        r.ok = true;
        r.enchants = new LinkedHashMap<>(current);
        r.enchants.put(s.id, next);
        r.cost = next * 2;
        return r;
    }

    public static boolean potatoable(ItemDef def) {
        if (def == null || def.category == null) return false;
        ItemCategory c = def.category;
        return c.isArmor() || c == ItemCategory.SWORD || c == ItemCategory.LONGSWORD || c == ItemCategory.BOW
                || c == ItemCategory.FISHING_WEAPON || c == ItemCategory.GAUNTLET || c == ItemCategory.DUNGEON_WEAPON;
    }

    public static Result potato(ItemDef left, int current, boolean fuming, int hotMax, int totalMax) {
        if (!potatoable(left)) return Result.fail("Potato books only work on weapons and armor!");
        if (!fuming && current >= hotMax) return Result.fail("This item already has " + hotMax + " Hot Potato Books!");
        if (fuming && current < hotMax) return Result.fail("Apply " + hotMax + " Hot Potato Books before a Fuming one!");
        if (fuming && current >= totalMax) return Result.fail("This item already has " + totalMax + " potato books!");
        Result r = new Result();
        r.ok = true;
        r.potatoBooks = current + 1;
        r.cost = 0;   // TUNE: research gives no anvil cost for potato books
        return r;
    }
}
