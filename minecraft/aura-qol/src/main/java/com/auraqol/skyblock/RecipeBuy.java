package com.auraqol.skyblock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Recipe -> Bazaar": RIGHT-click an ingredient in a recipe menu and the
 * Bazaar (or the Auction House, for things the Bazaar does not sell) opens on it.
 *
 * <p><b>The rule this is built around: one physical click, at most one
 * command, and never a click on a Hypixel menu button.</b> Clicking buttons
 * for the player (picking "Buy Instantly", then "64") would be a macro under
 * Hypixel's rules and could cost the account. So this only ever TYPES the
 * command the player could have typed - {@code /bz Enchanted Diamond} - and
 * the amount is chosen by the player in Hypixel's own Buy Instantly menu.
 * Shift-right-click sends the very same command; only the hint differs
 * ("1" vs "stack"), as a reminder of which button the player meant.
 *
 * <p><b>Left-click is never touched</b>: Hypixel uses it in recipe menus to
 * open the ingredient's own recipe. And an item whose lore mentions
 * right-click (NPC shops: "Right-Click for more trading options!") is left
 * alone too, because Hypixel already uses its right-click.
 *
 * <p>Pure: no Minecraft imports. The mixin turns a slot click into a
 * {@link Clicked}, asks {@link #handle}, and does what the {@link Decision}
 * says. Everything here is unit-tested in {@code RecipeBuyTest}.
 *
 * <h2>When a right-click qualifies</h2>
 * <ul>
 *   <li>Never on the player's own inventory slots, never while carrying an
 *       item, never off SkyBlock, never with the bypass modifier (Ctrl) held,
 *       never when the feature is off.</li>
 *   <li><b>In a menu showing ONE recipe</b> ({@link #isRecipeScreen}) -
 *       "Enchanted Diamond Recipe", "Confirm Process" (the Forge): the clicked
 *       ingredient. Not "Recipe Book" or "Farming Recipes" (recipe lists), and
 *       not "Craft Item" (the crafting grid holds the player's OWN items, and a
 *       right-click there must split a stack).</li>
 *   <li><b>In any SkyBlock menu, on an item whose lore lists what it needs</b>
 *       ("Items Required:" on a Garden visitor's Accept Offer, "Required
 *       Items:" on a Forge process): the next listed ingredient, cycling on
 *       repeated right-clicks. Left-click still accepts / starts.</li>
 *   <li>The item must be tradeable: a SkyBlock id, or a display name the price
 *       data knows as a Bazaar product / lowest-BIN item. Menu buttons (Go
 *       Back, Close, arrows, glass panes) have neither, and are also refused by
 *       name ({@link #isNavigation}).</li>
 * </ul>
 */
public final class RecipeBuy {

    /** The minimum gap between two commands. Clicks inside it are swallowed, not passed on. */
    public static final long MIN_GAP_MS = 500;

    public enum Click { RIGHT, SHIFT_RIGHT, OTHER }

    public enum Market { BAZAAR, AUCTION }

    public enum Verdict {
        /** Not ours: let the click through untouched. */
        PASS,
        /** Ours, but nothing is sent (inside the rate limit). */
        SWALLOW,
        /** Cancel the click and send {@link Decision#action()}'s command. */
        SEND
    }

    /**
     * Everything the decision needs about one click, copied out of the game.
     *
     * @param enabled        the toggle
     * @param inSkyblock     sidebar says SKYBLOCK
     * @param bypass         the bypass modifier (Ctrl) is held: click normally
     * @param screenTitle    the open menu's title (colour codes allowed)
     * @param playerSlot     the slot belongs to the player's own inventory
     * @param carrying       the cursor holds an item
     * @param click          what kind of click it was
     * @param slotIndex      menu slot number (only used to tell items apart for cycling)
     * @param id             SkyBlock id from custom_data, or null
     * @param enchantments   custom_data enchantments (book pricing), may be null
     * @param displayName    the stack's hover name (colour codes allowed)
     * @param vanillaItem    registry key, e.g. {@code minecraft:black_stained_glass_pane}
     * @param lore           lore lines (colour codes allowed), may be null
     */
    public record Clicked(boolean enabled, boolean inSkyblock, boolean bypass,
                          String screenTitle, boolean playerSlot, boolean carrying, Click click, int slotIndex,
                          String id, Map<String, Integer> enchantments, String displayName,
                          String vanillaItem, List<String> lore) {

        public Clicked {
            enchantments = enchantments == null ? Map.of() : enchantments;
            lore = lore == null ? List.of() : lore;
            displayName = displayName == null ? "" : displayName;
            vanillaItem = vanillaItem == null ? "" : vanillaItem;
            screenTitle = screenTitle == null ? "" : screenTitle;
        }
    }

    /**
     * One command to type.
     *
     * @param market  which market it opens
     * @param query   the search text ("Enchanted Diamond")
     * @param command the command without its slash ("bz Enchanted Diamond")
     */
    public record Action(Market market, String query, String command) {
    }

    /**
     * @param verdict what the hook does with the click
     * @param action  the command when {@code verdict == SEND}, else null
     * @param hint    the one-line note for the player when {@code SEND}, else null
     */
    public record Decision(Verdict verdict, Action action, String hint) {
        static final Decision PASS = new Decision(Verdict.PASS, null, null);
        static final Decision SWALLOW = new Decision(Verdict.SWALLOW, null, null);
    }

    // ------------------------------------------------------------ state

    private long lastSent = Long.MIN_VALUE / 2;
    private String cycleKey = "";
    private int cycleIndex;

    /**
     * Decide one click. Stateful only in two small ways: the rate limit, and
     * which listed ingredient a repeated right-click on the same item is on.
     */
    public Decision handle(Clicked c, PriceData d, long nowMs) {
        if (c == null || !c.enabled() || !c.inSkyblock() || c.bypass() || c.playerSlot() || c.carrying()) {
            return Decision.PASS;
        }
        if (c.click() != Click.RIGHT && c.click() != Click.SHIFT_RIGHT) {
            return Decision.PASS; // left-click is Hypixel's (opens the sub-recipe) - never touched
        }
        if (usesRightClick(c.lore())) {
            // Hypixel gives this item its own right-click ("Right-Click for more
            // trading options!" in NPC shops). Taking it would lose that.
            return Decision.PASS;
        }
        if (d == null) {
            d = PriceData.EMPTY;
        }
        Action action;
        String suffix = "";
        boolean cycling = false;
        List<String> needs = requiredItems(c.lore());
        if (!needs.isEmpty()) {
            // A button that lists what it needs (visitor offer, Forge process):
            // the ingredients exist only in its lore, so cycle through them.
            String key = Fmt.strip(c.screenTitle()) + "#" + c.slotIndex() + "#" + needs;
            if (!key.equals(cycleKey)) {
                cycleKey = key;
                cycleIndex = 0;
            }
            int i = Math.floorMod(cycleIndex, needs.size());
            action = nameAction(needs.get(i), d);
            if (action == null) {
                return Decision.PASS;
            }
            if (needs.size() > 1) {
                cycling = true;
                suffix = "  (" + (i + 1) + "/" + needs.size() + ", right-click again for the next)";
            }
        } else {
            if (!isRecipeScreen(c.screenTitle())) {
                return Decision.PASS;
            }
            action = itemAction(c, d);
            if (action == null) {
                return Decision.PASS;
            }
        }
        if (nowMs - lastSent < MIN_GAP_MS) {
            return Decision.SWALLOW;
        }
        lastSent = nowMs;
        if (cycling) {
            cycleIndex++;
        }
        return new Decision(Verdict.SEND, action, hint(action, c.click() == Click.SHIFT_RIGHT) + suffix);
    }

    private static final Pattern RIGHT_CLICK_LORE = Pattern.compile("(?i)\\bright[- ]?click");

    /** True when the item's lore says Hypixel does something on right-click. */
    public static boolean usesRightClick(List<String> lore) {
        if (lore == null) {
            return false;
        }
        for (String l : lore) {
            if (l != null && RIGHT_CLICK_LORE.matcher(Fmt.strip(l)).find()) {
                return true;
            }
        }
        return false;
    }

    /** The one-line note shown after a command is sent ("1" for right-click, "stack" for shift). */
    public static String hint(Action a, boolean stack) {
        if (a.market() == Market.BAZAAR) {
            return "Opened Bazaar for " + a.query() + " - Buy Instantly \u2192 " + (stack ? "stack" : "1");
        }
        return "Searching the Auction House for " + a.query() + " (not sold on the Bazaar)";
    }

    // ------------------------------------------------------------ screens

    private static final Pattern RECIPE_WORD = Pattern.compile("\\brecipe\\b");

    /**
     * True for a menu that shows ONE recipe's ingredients as slots.
     * "Enchanted Diamond Recipe", "(1/2) Hyperion Recipe", "Recipe",
     * "Confirm Process". False for recipe LISTS ("Recipe Book",
     * "Farming Recipes") and for the crafting table ("Craft Item").
     */
    public static boolean isRecipeScreen(String title) {
        String t = squash(Fmt.strip(title == null ? "" : title)).toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return false;
        }
        if (t.contains("recipe book") || t.contains("recipes")) {
            return false;
        }
        return RECIPE_WORD.matcher(t).find() || t.contains("confirm process");
    }

    private static final Set<String> NAV = Set.of(
            "go back", "back", "close", "next page", "previous page", "prev page", "confirm", "cancel",
            "accept offer", "refuse offer", "supercraft", "recipe book", "search", "sort", "quick crafting",
            "craft item", "crafting table", "view recipe", "view recipes", "start process", "confirm process");

    /** Menu furniture: glass panes, barriers and the named buttons every menu has. */
    public static boolean isNavigation(String displayName, String vanillaItem) {
        String v = vanillaItem == null ? "" : vanillaItem.toLowerCase(Locale.ROOT);
        if (v.contains("glass_pane") || v.endsWith(":barrier") || v.equals("barrier")) {
            return true;
        }
        String n = cleanName(displayName).toLowerCase(Locale.ROOT);
        return n.isEmpty() || NAV.contains(n);
    }

    // ------------------------------------------------------------ names

    private static final Pattern PET_LEVEL = Pattern.compile("^\\[\\s*lvl\\s*\\d+\\s*\\]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_SUFFIX = Pattern.compile("\\s*[x\u00d7]\\s?\\d[\\d,]*\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_PAREN = Pattern.compile("\\s*\\(\\s*[x\u00d7]?\\s?\\d[\\d,]*\\s?[x\u00d7]?\\s*\\)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_PREFIX = Pattern.compile("^\\d[\\d,]*\\s?[x\u00d7]\\s+", Pattern.CASE_INSENSITIVE);
    /** Stars, master stars, check marks, arrows, emoji: symbols and private-use glyphs. */
    private static final Pattern DECOR = Pattern.compile("[\\p{So}\\p{Sk}\\p{Co}\\p{No}\\p{Cs}]");
    private static final Pattern RARITY = Pattern.compile(
            "^(VERY SPECIAL|COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|SUPREME|ULTIMATE)\\s+");
    private static final Pattern LEAD_BULLET = Pattern.compile("^[-\u2022\u00bb>*]+\\s*");

    /**
     * A display name as a search: colour codes, pet level, counts ("x64",
     * "64x ", "(x2)"), stars and other symbols and an ALL-CAPS rarity word
     * removed. Reforges are NOT removed here - "Strong Dragon Helmet" and
     * "Refined Diamond" begin with reforge names - see {@link #withoutReforge}.
     */
    public static String cleanName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = Fmt.strip(raw).replace('\u00a0', ' ');
        s = DECOR.matcher(s).replaceAll(" ");
        s = squash(s);
        s = LEAD_BULLET.matcher(s).replaceFirst("");
        s = PET_LEVEL.matcher(s).replaceFirst("");
        s = RARITY.matcher(s).replaceFirst("");
        for (int i = 0; i < 2; i++) {
            s = COUNT_PAREN.matcher(s).replaceFirst("");
            s = COUNT_SUFFIX.matcher(s).replaceFirst("");
            s = COUNT_PREFIX.matcher(s).replaceFirst("");
        }
        return squash(s);
    }

    /** Reforge prefixes a crafted or dropped item can carry in its name. */
    private static final Set<String> REFORGES = Set.of(
            "gentle", "odd", "fast", "fair", "epic", "sharp", "heroic", "spicy", "legendary", "dirty", "fabled",
            "suspicious", "gilded", "warped", "withered", "bulky", "jerry's", "fanged", "coldfused", "fleet",
            "salty", "treacherous", "stiff", "lucky", "pitchin'", "deadly", "fine", "grand", "hasty", "neat",
            "rapid", "unreal", "awkward", "rich", "precise", "spiritual", "headstrong", "clean", "fierce",
            "heavy", "light", "mythic", "pure", "smart", "titanic", "wise", "perfect", "necrotic", "ancient",
            "spiked", "renowned", "cubic", "hyper", "reinforced", "loving", "ridiculous", "empowered", "giant",
            "submerged", "jaded", "bustling", "mossy", "festive", "glistening", "strengthened", "waxed",
            "fortified", "rooted", "blooming", "snowy", "blood-soaked", "unyielding", "prospector's",
            "excellent", "sturdy", "fortunate", "lustrous", "polished", "refined", "auspicious",
            "mithraic", "stellar", "heated", "ambered", "fruitful", "magnetic", "great", "rugged", "lush",
            "green thumb", "peasant's", "robust", "zooming", "strong", "shaded", "sweet", "silky", "bloody",
            "toil", "blessed", "earthy", "double-bit", "lumberjack's", "moil", "shiny", "recombobulated");

    /** "Heroic Hyperion" -> "Hyperion"; null when the first word is not a reforge or nothing would remain. */
    public static String withoutReforge(String clean) {
        if (clean == null) {
            return null;
        }
        int sp = clean.indexOf(' ');
        if (sp <= 0 || sp == clean.length() - 1) {
            return null;
        }
        String first = clean.substring(0, sp).toLowerCase(Locale.ROOT);
        return REFORGES.contains(first) ? clean.substring(sp + 1).trim() : null;
    }

    // ------------------------------------------------------------ lore

    private static final Pattern REQ_HEADER = Pattern.compile(
            "^(items required|required items|item requirements|ingredients|requires|required|materials|cost|item cost)\\s*:?$");
    private static final Pattern CURRENCY = Pattern.compile(
            "(?i)^[\\d,.]+\\s*[kmb]?\\s+(coins?|bits|motes|copper|gems|pelts|north stars|fragments?)$"
                    + "|(?i)^.*\\b(coins|powder|bits|motes|copper)$"
                    + "|(?i)^(click|right-click|left-click|shift|missing|you (do not )?have|requires? ).*");

    /**
     * The item names listed under a requirements header in lore - a Garden
     * visitor's "Items Required:", a Forge process's "Required Items:".
     * Entries run until a blank line or the next header; coins, powders, bits
     * and "Click to ..." lines are not items and are dropped.
     */
    public static List<String> requiredItems(List<String> lore) {
        if (lore == null || lore.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        boolean in = false;
        for (String raw : lore) {
            String line = squash(Fmt.strip(raw == null ? "" : raw).replace('\u00a0', ' '));
            String lower = line.toLowerCase(Locale.ROOT);
            if (!in) {
                if (REQ_HEADER.matcher(lower).matches()) {
                    in = true;
                }
                continue;
            }
            if (line.isEmpty() || (line.endsWith(":") && !line.contains(" x"))) {
                if (!out.isEmpty() || line.endsWith(":")) {
                    in = REQ_HEADER.matcher(lower).matches();
                }
                if (!in && !out.isEmpty()) {
                    break;
                }
                continue;
            }
            String name = cleanName(line);
            if (name.isEmpty() || CURRENCY.matcher(name).matches() || CURRENCY.matcher(line).matches()) {
                continue;
            }
            out.add(name);
            if (out.size() >= 12) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------ actions

    /** The command for a clicked stack, or null if it is not something a market sells. */
    public static Action itemAction(Clicked c, PriceData d) {
        if (isNavigation(c.displayName(), c.vanillaItem())) {
            return null;
        }
        String clean = cleanName(c.displayName());
        if (c.id() != null && !c.id().isBlank()) {
            String id = c.id();
            String bzId = id;
            String query = null;
            if ("ENCHANTED_BOOK".equals(id)) {
                if (c.enchantments().size() == 1) {
                    Map.Entry<String, Integer> e = c.enchantments().entrySet().iterator().next();
                    bzId = "ENCHANTMENT_" + e.getKey().toUpperCase(Locale.ROOT) + "_" + e.getValue();
                    query = enchantName(e.getKey());
                }
            }
            return forId(id, bzId, query, clean, d);
        }
        // No SkyBlock id (a display copy in a menu): only act if the price data
        // knows the name as something tradeable, so a "Crafting Table" button
        // or a renamed decoration stays a button.
        String id = tradeableIdForName(clean, d);
        if (id == null) {
            String bare = withoutReforge(clean);
            id = bare == null ? null : tradeableIdForName(bare, d);
        }
        return id == null ? null : forId(id, id, null, clean, d);
    }

    /** The command for a name read out of lore. Unknown names are guessed to be Bazaar materials. */
    public static Action nameAction(String name, PriceData d) {
        String clean = cleanName(name);
        if (clean.isEmpty()) {
            return null;
        }
        String id = d == null ? null : d.idForName(clean);
        if (id == null) {
            // Price data not loaded yet or a name the item list lacks: what a
            // visitor or the Forge asks for is almost always a Bazaar material,
            // and a search with no hit is harmless.
            return make(Market.BAZAAR, clean);
        }
        return forId(id, id, null, clean, d);
    }

    private static String tradeableIdForName(String clean, PriceData d) {
        if (d == null || clean.isEmpty()) {
            return null;
        }
        String id = d.idForName(clean);
        if (id == null) {
            return null;
        }
        return d.isBazaarProduct(id) || !Double.isNaN(d.lbin(id)) ? id : null;
    }

    private static Action forId(String id, String bzId, String query, String clean, PriceData d) {
        Market m;
        if (d.isBazaarProduct(bzId)) {
            m = Market.BAZAAR;
        } else if (d.bazaarSize() == 0 && Double.isNaN(d.lbin(id))) {
            // No bazaar data yet and nothing says it is an auction item: a
            // recipe ingredient is far more often a Bazaar material.
            m = Market.BAZAAR;
        } else {
            m = Market.AUCTION;
        }
        String q = query;
        if (q == null) {
            q = d.nameForId(m == Market.BAZAAR ? bzId : id);
        }
        if (q == null) {
            q = d.nameForId(id);
        }
        if (q == null || q.isBlank()) {
            q = clean;
        }
        return make(m, cleanName(q));
    }

    private static Action make(Market m, String query) {
        String q = safe(query);
        if (q.isEmpty()) {
            return null;
        }
        return new Action(m, q, (m == Market.BAZAAR ? "bz " : "ahs ") + q);
    }

    /** "ultimate_wise" -> "Ultimate Wise". */
    static String enchantName(String key) {
        StringBuilder sb = new StringBuilder();
        for (String w : key.toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Only characters chat accepts, one line, at most 100 of them. */
    static String safe(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\u00a7' || c == '/') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String out = squash(sb.toString());
        return out.length() > 100 ? out.substring(0, 100).trim() : out;
    }

    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static String squash(String s) {
        return SPACES.matcher(s).replaceAll(" ").trim();
    }

    /** Exposed for tests: does the header pattern accept this lore line. */
    static boolean isRequirementHeader(String line) {
        Matcher m = REQ_HEADER.matcher(squash(Fmt.strip(line)).toLowerCase(Locale.ROOT));
        return m.matches();
    }
}
