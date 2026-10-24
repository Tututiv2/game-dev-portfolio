package com.auraqol.skyblock;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chat lines the money tracker acts on: drops that ARE income (counted
 * straight from chat, because a drop can land in the item stash, in a sack or
 * in your inventory while a menu is open - places an inventory diff never sees
 * or must ignore) and deliveries that are NOT (bazaar claims, NPC purchases,
 * items moved out of your sacks...), which must be netted out when they turn
 * up in the inventory or the next [Sacks] summary.
 *
 * <h2>Source of truth</h2>
 * Every pattern is the colour-less form of a pattern from SkyHanni's repo
 * ({@code constants/regexesModern.json}, sourceLabel 96e0126, on disk at
 * {@code SkyblockClone/research/data/skyhanni-repo}); the key is cited on each.
 * SkyHanni writes some of them WITH colour codes - those were converted by
 * dropping the codes, nothing else. Where a pattern is not in that file it is
 * marked "(not in the SkyHanni repo)" and is best-effort.
 *
 * <h2>Text handling</h2>
 * Lines arrive colour-stripped. Modern Hypixel also puts private-use font
 * glyphs into chat (the Magic Find star is U+E01A, the Farming Fortune clover
 * U+E02B, the pest icon U+E07F/U+E018) - the patterns here run on text with
 * those removed ({@link #clean}), so where SkyHanni has {@code "+N% \uE01A
 * Magic Find"} the cleaned line reads {@code "+N%  Magic Find"}. The glyphs
 * are still looked at once, on the raw line, to tell a pest drop (Farming
 * Fortune bonus) from a combat one (Magic Find).
 */
public final class MoneyChat {

    private MoneyChat() {
    }

    /** One drop that is income. {@code petRarity} is set for PET DROP! when the colour told us. */
    public record Drop(String item, int count, IncomeSource source, String rule, String petRarity) {
    }

    /** Items that arrived but are not income: bought, claimed, moved. */
    public record Acquired(String item, int count, String rule) {
    }

    // ------------------------------------------------------------ cleaning

    /** Colour codes and private-use/emoji glyphs out, NBSP to space. Spacing is kept (Jacob lines are indented). */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        String t = Fmt.strip(s);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isSupplementaryCodePoint(cp) || (cp >= 0xE000 && cp <= 0xF8FF)
                    || cp == 0xFE0F || cp == 0x200B) {
                continue;
            }
            sb.appendCodePoint(cp == 0xA0 ? ' ' : cp);
        }
        return sb.toString();
    }

    /** A chat message can carry several lines (Jacob's rewards, trade summaries). */
    public static List<String> lines(String message) {
        List<String> out = new ArrayList<>();
        if (message == null) {
            return out;
        }
        for (String l : message.split("\\R")) {
            if (!l.isBlank()) {
                out.add(l);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ drops

    /** {@code garden.cropfever.drop}: "^(?<rarity>[\w ]+)! You dropped (?<amount>\d+)x (?<crop>[\w ]+)!" (crop widened to names with ' and -). */
    private static final Pattern CROP_FEVER = Pattern.compile(
            "^\\s*(?<rarity>[\\w ]+)! You dropped (?<amount>[\\d,]+)x (?<crop>[\\w '-]+?)!\\s*$");

    /**
     * {@code garden.pests-api.chat.pest-death}, colours dropped:
     * "You received (?<amount>[0-9]*)x (?<item>.*) for killing an? (?<pest>.*)!".
     */
    private static final Pattern PEST_DEATH = Pattern.compile(
            "^\\s*You received (?<amount>[\\d,]+)x (?<item>.+?) for killing an? (?<pest>.+?)!\\s*$");

    /**
     * The RARE DROP! family. Generalises {@code garden.pests.tracker.raredrop}
     * ("(?:RARE|PET) DROP! (?<item>.+?)(?: x(?<amount>[\d,]+))? \((?:\+[\d.,]+\uE02B|Cocoaleech)\)"),
     * {@code combat.ghosttracker.itemdrop.colorless} ("RARE DROP! (?<item>.+) \(\+(?<mf>\d+)%? \uE01A Magic Find\)"),
     * {@code raredrop.enchantedbook.colorless}, {@code raredrop.pet.droppedmessage} ("PET DROP! ...") and
     * {@code diana.chat.griffin.feather.drop} ("RARE DROP! You dug out a Griffin Feather!") to every tier word
     * Hypixel uses in front of DROP!: RARE, VERY RARE, CRAZY RARE, INSANE, PRAY TO RNGESUS, PET.
     */
    private static final Pattern RARE_DROP = Pattern.compile(
            "^\\s*(?<tier>(?:[A-Z]+ )*?(?:RARE|INSANE|RNGESUS|INCARNATE|PET|UNBELIEVABLE))\\s+DROP!\\s+(?<rest>.+?)\\s*$");

    /** {@code garden.rarecrops.*.colorless}: "(?:VERY )?RARE CROP! Cropie(?: .*)?" - one per crop there; one pattern here. */
    private static final Pattern RARE_CROP = Pattern.compile(
            "^\\s*(?<tier>(?:[A-Z]+ )*?RARE)\\s+CROP!\\s+(?<rest>.+?)\\s*$");

    /** {@code chat.jacobcompact.overclocker}: " {4}Overclocker 3000(?: x(?<count>\d+))?" (Jacob's contest rewards). */
    private static final Pattern JACOB_OVERCLOCKER = Pattern.compile(
            "^\\s{2,}Overclocker 3000(?:\\s+x(?<count>[\\d,]+))?\\s*$");

    /** {@code raredrop.pet.droppedmessage}, run on the LEGACY (colour-coded) text to read the pet's rarity colour. */
    private static final Pattern PET_DROP_COLOURED = Pattern.compile(
            "(?<start>(?:§.)*PET DROP! )(?:§.)*§(?<rarityColor>.)(?<petName>[^§(.]+)(?<end>(?: .*)?)");

    private static final Pattern TRAILING_PAREN = Pattern.compile("^(?<body>.*?)\\s*\\((?<inner>[^()]*)\\)\\s*$");
    private static final Pattern AMOUNT_SUFFIX = Pattern.compile("^(?<item>.+?)\\s+x(?<amount>[\\d,]+)$");
    private static final Pattern AMOUNT_PREFIX = Pattern.compile("^(?<amount>[\\d,]+)x\\s+(?<item>.+)$");

    /**
     * The drop in one chat line, or null.
     *
     * @param rawLine    the line as received (colour codes may be present, glyphs intact)
     * @param legacyLine the same line with legacy colour codes, or null if not available (pet rarity)
     */
    public static Drop drop(String rawLine, String legacyLine) {
        if (rawLine == null) {
            return null;
        }
        String line = clean(rawLine);

        Matcher m = CROP_FEVER.matcher(line);
        if (m.matches()) {
            int n = count(m.group("amount"));
            return n <= 0 ? null : new Drop(m.group("crop").trim(), n, IncomeSource.RARE, "crop-fever", null);
        }
        m = PEST_DEATH.matcher(line);
        if (m.matches()) {
            int n = count(m.group("amount"));
            return n <= 0 ? null : new Drop(m.group("item").trim(), n, IncomeSource.PEST, "pest-kill", null);
        }
        m = JACOB_OVERCLOCKER.matcher(line);
        if (m.matches()) {
            int n = m.group("count") == null ? 1 : count(m.group("count"));
            return n <= 0 ? null : new Drop("Overclocker 3000", n, IncomeSource.RARE, "jacob-overclocker", null);
        }
        m = RARE_CROP.matcher(line);
        if (m.matches()) {
            Drop d = fromRest(m.group("rest"), IncomeSource.RARE, "rare-crop");
            if (d != null) {
                return d;
            }
        }
        m = RARE_DROP.matcher(line);
        if (m.matches()) {
            String tier = m.group("tier");
            String raw = Fmt.strip(rawLine);
            boolean pest = raw.indexOf('\uE02B') >= 0 || raw.indexOf('\u2618') >= 0 || raw.contains("Cocoaleech");
            String bonus = trailingBonus(m.group("rest"));
            if (!pest && bonus != null && !bonus.contains("%") && !bonus.contains("Magic Find")) {
                pest = true; // "(+1,234)" with the clover glyph lost on the way: a Farming Fortune bonus
            }
            IncomeSource src = pest ? IncomeSource.PEST : IncomeSource.RARE;
            String rule = (tier.equals("PET") ? "pet-drop" : "rare-drop") + (pest ? "/pest" : "");
            Drop d = fromRest(m.group("rest"), src, rule);
            if (d == null) {
                return null;
            }
            if (tier.equals("PET")) {
                return new Drop(d.item(), d.count(), d.source(), d.rule(), petRarity(legacyLine));
            }
            return d;
        }
        return null;
    }

    private static String trailingBonus(String rest) {
        Matcher b = TRAILING_PAREN.matcher(rest.trim());
        if (b.matches() && looksLikeBonus(b.group("inner"))) {
            return b.group("inner");
        }
        return null;
    }

    private static boolean looksLikeBonus(String inner) {
        String s = inner.trim();
        return s.startsWith("+") || s.contains("Magic Find") || s.contains("Cocoaleech")
                || s.contains("Fortune") || s.contains("Luck");
    }

    /** "(Scythe Blade) (+250%  Magic Find)", "Enchanted Hay Bale x3 (+1,234)", "You dug out a Griffin Feather!" -> name + count. */
    private static Drop fromRest(String rest, IncomeSource src, String rule) {
        String r = rest.trim();
        Matcher b = TRAILING_PAREN.matcher(r);
        if (b.matches() && looksLikeBonus(b.group("inner"))) {
            r = b.group("body").trim();
        }
        r = r.replaceFirst("^You dug out (?:an? )?", "").replaceFirst("^You dropped ", "");
        if (r.endsWith("!")) {
            r = r.substring(0, r.length() - 1).trim();
        }
        if (r.length() > 2 && r.startsWith("(") && r.endsWith(")")) {
            r = r.substring(1, r.length() - 1).trim();
        }
        int n = 1;
        Matcher a = AMOUNT_SUFFIX.matcher(r);
        if (a.matches()) {
            n = count(a.group("amount"));
            r = a.group("item").trim();
        } else {
            a = AMOUNT_PREFIX.matcher(r);
            if (a.matches()) {
                n = count(a.group("amount"));
                r = a.group("item").trim();
            }
        }
        r = r.replaceFirst("^\\[Lvl \\d+\\]\\s*", "").trim();
        if (r.isEmpty() || n <= 0) {
            return null;
        }
        return new Drop(r, n, src, rule, null);
    }

    /** Rarity from the pet name's colour in a "PET DROP!" line; null if no colour. */
    static String petRarity(String legacyLine) {
        if (legacyLine == null) {
            return null;
        }
        Matcher m = PET_DROP_COLOURED.matcher(legacyLine);
        char code;
        if (m.find()) {
            code = m.group("rarityColor").charAt(0);
        } else {
            // Tolerant fallback: the first colour code after "PET DROP!" that
            // is followed by the name (the space may sit inside the coloured run).
            int at = legacyLine.indexOf("PET DROP!");
            if (at < 0) {
                return null;
            }
            code = 0;
            for (int i = at + 9; i + 1 < legacyLine.length(); i++) {
                char c = legacyLine.charAt(i);
                if (c == '§') {
                    char k = Character.toLowerCase(legacyLine.charAt(i + 1));
                    if ((k >= '0' && k <= '9') || (k >= 'a' && k <= 'f')) {
                        code = k;
                    }
                    i++;
                } else if (Character.isLetter(c)) {
                    break;
                }
            }
            if (code == 0) {
                return null;
            }
        }
        return switch (Character.toLowerCase(code)) {
            case 'f' -> "COMMON";
            case 'a' -> "UNCOMMON";
            case '9' -> "RARE";
            case '5' -> "EPIC";
            case '6' -> "LEGENDARY";
            case 'd' -> "MYTHIC";
            default -> null;
        };
    }

    // ------------------------------------------------------------ deliveries (not income)

    /** {@code bazaar.orders.chat.claimed.colorless}: "\[Bazaar] Claimed (?<amount>[\d,]+)x (?<item>.*) worth .* coins bought for .* each!" */
    private static final Pattern BZ_CLAIMED = Pattern.compile(
            "^\\s*\\[Bazaar] Claimed (?<amount>[\\d,]+)x (?<item>.+?) worth .* coins bought for .* each!\\s*$");

    /** Same claim, tolerant of a changed tail (not in the SkyHanni repo - a safety net). */
    private static final Pattern BZ_CLAIMED_LOOSE = Pattern.compile(
            "^\\s*\\[Bazaar] Claimed (?<amount>[\\d,]+)x (?<item>.+?) (?:worth|for) ");

    /**
     * {@code inventory.bazaar.transaction}: "\[Bazaar] (?<type>Bought|Buy Order Setup!|Sold|Sell Offer Setup!|Order Flipped!)
     * [\d,]+x (?<item>.*) for (?<coins>[\d,.]+) coins.*" - only Bought delivers items; the amount is captured here.
     */
    private static final Pattern BZ_BOUGHT = Pattern.compile(
            "^\\s*\\[Bazaar] Bought (?<amount>[\\d,]+)x (?<item>.+?) for (?<coins>[\\d,.]+) coins.*$");

    /**
     * {@code data.npctrade.bought}: "You bought (?<name>.+?)(?: x(?<amount>[\d,]+))?!" - NPC shops, SkyMart (Copper),
     * the Community Shop, Bits shop; same shape as {@code misc.chocolatefactory.shop.bought}.
     */
    private static final Pattern NPC_BOUGHT = Pattern.compile(
            "^\\s*You bought (?:back )?(?<name>.+?)(?: x(?<amount>[\\d,]+))?!\\s*$");

    /** {@code data.owninventory.chat.movedsacktoinventory} / {@code gfs.chat.from}: "Moved (?<amount>\d+) (?<item>.+) from your Sacks to your inventory." */
    private static final Pattern MOVED_FROM_SACKS = Pattern.compile(
            "^\\s*Moved (?<amount>[\\d,]+) (?<item>.+?) from your Sacks to your inventory\\.?\\s*$");

    /** {@code inventory.cake-tracker.cake.purchased} generalised: "You purchased (?<item>...) for (?<coins>[\d,]+) coins!" */
    private static final Pattern PURCHASED = Pattern.compile(
            "^\\s*You purchased (?<item>.+?)(?: x(?<amount>[\\d,]+))? for (?<coins>[\\d,.]+) [Cc]oins!?\\s*$");

    /** Auction House claim of a won/bought item (not in the SkyHanni repo): "You claimed X from [RANK] Name's auction!" */
    private static final Pattern AH_CLAIMED = Pattern.compile(
            "^\\s*You claimed (?<item>.+?) from (?:\\[[^\\]]+\\] )?\\S+'s auction!\\s*$");

    /**
     * Item stash pickup (not in the SkyHanni repo as an item line; {@code data.chat.player.privatemessage}
     * explicitly excludes lines starting "From stash: "): "From stash: Item x3".
     */
    private static final Pattern FROM_STASH = Pattern.compile(
            "^\\s*From stash: (?<item>.+?)(?: x(?<amount>[\\d,]+))?\\s*$");

    /** The delivery in one line, or null. */
    public static Acquired acquired(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String line = clean(rawLine);
        Matcher m = BZ_CLAIMED.matcher(line);
        if (m.matches()) {
            return acq(m.group("item"), m.group("amount"), "bazaar-claim");
        }
        m = BZ_CLAIMED_LOOSE.matcher(line);
        if (m.find()) {
            return acq(m.group("item"), m.group("amount"), "bazaar-claim");
        }
        m = BZ_BOUGHT.matcher(line);
        if (m.matches()) {
            return acq(m.group("item"), m.group("amount"), "bazaar-buy");
        }
        m = MOVED_FROM_SACKS.matcher(line);
        if (m.matches()) {
            return acq(m.group("item"), m.group("amount"), "moved-from-sacks");
        }
        m = AH_CLAIMED.matcher(line);
        if (m.matches()) {
            return acq(m.group("item"), null, "auction-claim");
        }
        m = PURCHASED.matcher(line);
        if (m.matches()) {
            return acq(m.group("item"), m.group("amount"), "purchase");
        }
        m = NPC_BOUGHT.matcher(line);
        if (m.matches()) {
            return acq(m.group("name"), m.group("amount"), "shop-buy");
        }
        m = FROM_STASH.matcher(line);
        if (m.matches()) {
            return acq(m.group("item"), m.group("amount"), "stash-pickup");
        }
        return null;
    }

    private static Acquired acq(String item, String amount, String rule) {
        int n = amount == null ? 1 : count(amount);
        String name = item == null ? "" : item.trim();
        if (n <= 0 || name.isEmpty()) {
            return null;
        }
        return new Acquired(name, n, rule);
    }

    // ------------------------------------------------------------ trades (purse/inventory grace)

    /** Line start -> rule name. Order matters only for the name reported. */
    private static final String[][] TRADE_PREFIXES = {
            {"[Bazaar]", "bazaar"},
            {"[Auction]", "auction"},
            {"You sold ", "npc-sell"},                   // misc.npcdaylimit.sold
            {"You bought ", "shop-buy"},                 // data.npctrade.bought
            {"You purchased ", "purchase"},              // inventory.cake-tracker.cake.purchased
            {"You collected ", "auction-collect"},
            {"You claimed ", "claim"},                   // AH, /claim, rewards
            {"Bought ", "buy"},
            {"Sold ", "sell"},
            {"Deposited ", "bank"},
            {"Withdrew ", "bank"},
            {"You withdrew ", "bank"},
            {"You have withdrawn ", "bank"},
            {"Escrow refunded ", "auction"},
            {"BIN Auction started ", "auction"},
            {"Auction started ", "auction"},
            {"Bid of ", "auction"},
            {"You placed a bid ", "auction"},
            {"Trade completed with ", "trade"},          // carry.trade.completed
            {"You don't have enough coins", "trade"},
            {"Coins deposited", "bank"},
            {"Moved ", "moved-from-sacks"},              // data.owninventory.chat.movedsacktoinventory
            {"From stash:", "stash-pickup"},
            {"One or more items didn't fit", "stash"},   // stash.compact.generic
            {"One or more materials didn't fit", "stash"},
            {"You picked up a minion", "minion"},        // minion.chat.pickup
            {"You placed a minion", "minion"},
            {"OFFER ACCEPTED with ", "visitor"},         // garden.visitor.droptracker.accept
    };

    /** The trade rule a line triggers, or null. */
    public static String tradeRule(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String s = clean(rawLine).trim();
        for (String[] p : TRADE_PREFIXES) {
            if (s.startsWith(p[0])) {
                return p[1];
            }
        }
        return null;
    }

    // ------------------------------------------------------------ helpers

    private static final int[] VANILLA_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    private static final String CODES = "0123456789abcdef";

    /**
     * The legacy colour code ('6' for gold) of a text colour, or 0 if it is
     * not one of the sixteen named colours. Lets the game side rebuild a
     * "§6..." string from a styled Component, so the SkyHanni patterns written
     * against legacy text ({@code raredrop.pet.droppedmessage}) apply as-is.
     */
    public static char legacyCode(int rgb) {
        int c = rgb & 0xFFFFFF;
        for (int i = 0; i < VANILLA_RGB.length; i++) {
            if (VANILLA_RGB[i] == c) {
                return CODES.charAt(i);
            }
        }
        return 0;
    }

    static int count(String s) {
        if (s == null) {
            return 0;
        }
        try {
            long v = Long.parseLong(s.replace(",", "").trim());
            return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
