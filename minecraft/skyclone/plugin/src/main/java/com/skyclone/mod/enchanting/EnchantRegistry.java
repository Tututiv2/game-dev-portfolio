package com.skyclone.mod.enchanting;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.ui.Text;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every enchantment spec, plus the enchanted-book item ids. Pure (no Bukkit): the module fills it
 * from the jar, the unit tests from the source tree.
 *
 * <p><b>Book ids.</b> One stackable item per enchant level:
 * <ul>
 *   <li>canonical {@code ENCHANTMENT_SHARPNESS_5} (the Bazaar's product id, so the bazaar can trade books),</li>
 *   <li>alias {@code SHARPNESS;5} (the id the research's NPC shops and drop tables use). Aliases are
 *       turned into the canonical id the moment a player gains one.</li>
 * </ul>
 * The generic {@code ENCHANTED_BOOK} (unique, enchants in {@code ItemData.enchants}) is also accepted
 * everywhere a book is.
 */
public final class EnchantRegistry {

    public static final String BOOK_PREFIX = "ENCHANTMENT_";
    public static final String GENERIC_BOOK = "ENCHANTED_BOOK";

    private final Map<String, EnchantSpec> specs = new LinkedHashMap<>();
    /** alias id -> spec. Aliases are the ids other modules derive from an enchant's NAME ("woodsplitter"
     *  for {@code arcane}, "bank" for {@code ultimate_bank}); generated, never shadowing a real id. */
    private final Map<String, EnchantSpec> aliases = new LinkedHashMap<>();

    public EnchantRegistry(Collection<EnchantSpec> list) {
        for (EnchantSpec s : list) if (s != null && s.id != null) specs.put(s.id.toLowerCase(Locale.ROOT), s);
        for (EnchantSpec s : specs.values())
            if (s.aliases != null) for (String a : s.aliases) {
                if (a == null) continue;
                String k = a.toLowerCase(Locale.ROOT);
                if (!specs.containsKey(k)) aliases.putIfAbsent(k, s);
            }
    }

    public static EnchantRegistry read(Reader r) {
        List<EnchantSpec> list = new Gson().fromJson(r, new TypeToken<List<EnchantSpec>>() {}.getType());
        return new EnchantRegistry(list == null ? List.of() : list);
    }

    /** The spec by its id or by one of its aliases (case-insensitive). */
    public EnchantSpec spec(String id) {
        if (id == null) return null;
        String k = id.toLowerCase(Locale.ROOT);
        EnchantSpec s = specs.get(k);
        return s != null ? s : aliases.get(k);
    }

    /** The canonical id for an id or alias; null when neither. */
    public String canonical(String id) {
        EnchantSpec s = spec(id);
        return s == null ? null : s.id;
    }

    /** True when {@code id} is an alias (not a canonical id) of some enchant. */
    public boolean isAlias(String id) { return id != null && aliases.containsKey(id.toLowerCase(Locale.ROOT)); }

    /** alias id -> canonical id, for registration and checks. */
    public Map<String, String> aliasMap() {
        Map<String, String> m = new LinkedHashMap<>();
        aliases.forEach((a, s) -> m.put(a, s.id));
        return m;
    }

    public Collection<EnchantSpec> all() { return Collections.unmodifiableCollection(specs.values()); }

    public int size() { return specs.size(); }

    /** Sorted by display name, ultimates last. */
    public List<EnchantSpec> sorted() {
        List<EnchantSpec> l = new ArrayList<>(specs.values());
        l.sort(Comparator.<EnchantSpec, Boolean>comparing(s -> s.ultimate).thenComparing(s -> s.name));
        return l;
    }

    /** Enchants that can go on this item (any source). */
    public List<EnchantSpec> applicable(ItemDef def) {
        List<EnchantSpec> out = new ArrayList<>();
        for (EnchantSpec s : sorted()) if (s.appliesTo(def)) out.add(s);
        return out;
    }

    /** Enchants the table offers for this item ({@code def == null} or a plain Book = every table enchant). */
    public List<EnchantSpec> tableFor(ItemDef def) {
        List<EnchantSpec> out = new ArrayList<>();
        boolean book = def == null || isPlainBook(def.id);
        for (EnchantSpec s : sorted()) if (s.onTable() && (book || s.appliesTo(def))) out.add(s);
        return out;
    }

    public static boolean isPlainBook(String itemId) { return "BOOK".equals(itemId); }

    // ---- book ids -----------------------------------------------------------------------------

    public static String bookId(String enchant, int level) {
        return BOOK_PREFIX + enchant.toUpperCase(Locale.ROOT) + "_" + level;
    }

    public static String aliasId(String enchant, int level) {
        return enchant.toUpperCase(Locale.ROOT) + ";" + level;
    }

    /** A parsed book id. */
    public record BookRef(String enchant, int level) {}

    /** {@code ENCHANTMENT_SHARPNESS_5} or {@code SHARPNESS;5} -> (sharpness, 5) if that enchant exists; else null. */
    public BookRef parseBook(String itemId) {
        if (itemId == null) return null;
        String e;
        String lv;
        if (itemId.startsWith(BOOK_PREFIX)) {
            int u = itemId.lastIndexOf('_');
            if (u <= BOOK_PREFIX.length()) return null;
            e = itemId.substring(BOOK_PREFIX.length(), u);
            lv = itemId.substring(u + 1);
        } else {
            int s = itemId.indexOf(';');
            if (s <= 0) return null;
            e = itemId.substring(0, s);
            lv = itemId.substring(s + 1);
        }
        int level;
        try { level = Integer.parseInt(lv); } catch (NumberFormatException ex) { return null; }
        EnchantSpec spec = specs.get(e.toLowerCase(Locale.ROOT));   // canonical ids only: book items exist only for those
        if (spec == null || level < 1 || level > spec.maxLevel) return null;
        return new BookRef(spec.id, level);
    }

    public boolean isBook(String itemId) { return GENERIC_BOOK.equals(itemId) || parseBook(itemId) != null; }

    // ---- book item defs -------------------------------------------------------------------------

    /** The ItemDef of one book level; {@code id} = canonical or alias id. */
    public ItemDef bookDef(EnchantSpec s, int level, String id) {
        ItemDef d = ItemDef.of(id, "Enchanted Book", "ENCHANTED_BOOK", s.rarity(level), ItemCategory.ENCHANTED_BOOK);
        d.glow = true;
        d.lore.addAll(bookLore(s, level));
        d.tags.add("enchanting_book");
        d.tags.add("bazaar");
        d.extra.addProperty("enchant", s.id);
        d.extra.addProperty("level", level);
        return d;
    }

    public static List<String> bookLore(EnchantSpec s, int level) {
        List<String> l = new ArrayList<>();
        l.add(s.combinable ? "&8Combinable in Anvil" : "&8Cannot be combined");
        l.add("");
        l.add(s.displayName(level));
        l.addAll(Text.wrap("&7" + s.describe(level), 38));
        if (s.ultimate) {
            l.add("");
            l.add("&7Only one &d&lultimate&7 enchantment");
            l.add("&7fits on an item.");
        }
        if (s.levelsByUse()) {
            l.add("");
            l.add("&7Levels up as you use it, up to &a" + EnchantSpec.roman(s.maxLevel) + "&7.");
        }
        l.add("");
        l.add("&6Applied To:");
        for (String a : s.appliesToLabels()) l.add("&7- &f" + a);
        if (s.skill > 0) {
            l.add("");
            l.add("&7Requires &aEnchanting " + s.skill + "&7 to apply.");
        }
        return l;
    }

    /** Rarity colour code of a book level, for menus. */
    public static String rarityCode(EnchantSpec s, int level) {
        Rarity r = s.rarity(level);
        return r.code();
    }
}
