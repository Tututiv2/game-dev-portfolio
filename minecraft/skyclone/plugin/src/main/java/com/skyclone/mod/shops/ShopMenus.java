package com.skyclone.mod.shops;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.MenuClick;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.shops.ShopData.CurrencyCost;
import com.skyclone.mod.shops.ShopData.Entry;
import com.skyclone.mod.shops.ShopData.Group;
import com.skyclone.mod.shops.ShopData.ItemCost;
import com.skyclone.mod.shops.ShopData.Shop;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Every shop screen: the merchant menu, trading options, buyback, the sell box and the directory. */
final class ShopMenus {

    private ShopMenus() {}

    /** How a shop was opened. {@code canTrade} false = browse only, {@code blockReason} says why. */
    record Session(Shop shop, boolean canTrade, String blockReason, boolean fromDirectory) {}

    static final int[] AREA = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    /** Mutable view state of one open shop menu. */
    private static final class View {
        String group;
        int page;
    }

    // ---- the merchant menu ----------------------------------------------------------------------------

    static void openShop(Player p, Session s) {
        if (p == null || s == null || s.shop() == null) return;
        shopMenu(p, s, null, 0).open(p);
    }

    static Menu shopMenu(Player p, Session s, String group, int page) {
        Shop shop = s.shop();
        View v = new View();
        v.group = group != null ? group : (shop.groups != null && !shop.groups.isEmpty() ? shop.groups.get(0).id : null);
        v.page = Math.max(0, page);
        Menu m = new Menu(Text.strip(shop.name), 6);
        m.drawer(menu -> drawShop(menu, p, s, v));
        m.onPlayerInventoryClick((click, live) -> onSellClick(click, live, s, v));
        return m;
    }

    private static void drawShop(Menu m, Player p, Session s, View v) {
        Shop shop = s.shop();
        m.chrome();
        // category tabs
        List<Group> groups = shop.groups == null ? List.of() : shop.groups;
        if (!groups.isEmpty()) {
            int n = Math.min(9, groups.size());
            int start = n <= 7 ? 1 + (7 - n) / 2 : 0;
            for (int i = 0; i < n; i++) {
                Group g = groups.get(i);
                boolean on = g.id.equals(v.group);
                ItemStack icon = Icons.of(material(g.icon, Material.STONE), (on ? "&a" : "&e") + g.name,
                        "&7" + Shops.available(shop, g.id).size() + " items", "", on ? "&aSelected!" : "&eClick to view!");
                if (on) Icons.glow(icon);
                m.set(start + i, icon, c -> { v.group = g.id; v.page = 0; m.redraw(); });
            }
        }
        List<Entry> list = Shops.available(shop, groups.isEmpty() ? null : v.group);
        int pages = Math.max(1, (list.size() + AREA.length - 1) / AREA.length);
        if (v.page >= pages) v.page = pages - 1;
        int from = v.page * AREA.length;
        java.util.Set<Entry> ambiguous = Shops.ambiguousLines(list);
        for (int i = 0; i < AREA.length && from + i < list.size(); i++) {
            Entry e = list.get(from + i);
            m.set(AREA[i], entryIcon(p, s, e, ambiguous.contains(e)), c -> onEntryClick(c, s, v, e));
        }
        if (list.isEmpty()) {
            m.set(22, Icons.of(Material.BARRIER, "&cNothing in stock",
                    "&7This merchant has nothing to sell", "&7right now. Come back later!"));
        }
        if (v.page > 0) m.set(45, Icons.prevPage(v.page + 1), c -> { v.page--; m.redraw(); });
        if (v.page < pages - 1) m.set(53, Icons.nextPage(v.page + 1), c -> { v.page++; m.redraw(); });

        if (s.fromDirectory()) {
            m.set(48, Icons.back("Merchant Directory"), c -> directory(c.player(), 0).open(c.player()));
        } else {
            m.set(48, Icons.of(Material.COMPASS, "&aMerchant Directory", "&7Every merchant in the world,",
                    "&7where to find them and what", "&7they sell.", "", "&eClick to open!"), c -> directory(c.player(), 0).open(c.player()));
        }
        m.set(47, infoIcon(p, s));
        m.set(50, buybackIcon(p, s), c -> {
            if (!s.canTrade()) { Msg.error(c.player(), Text.strip(s.blockReason())); return; }
            if (c.right()) { buybackMenu(c.player(), s, v).open(c.player()); return; }
            if (Trade.buybackList(c.player()).isEmpty()) return;
            if (Trade.tooFast(c.player())) return;
            Trade.buyBack(c.player(), 0);
            m.redraw();
        });
        m.set(51, Icons.of(Material.CHEST, "&aSell Items", "&7Put many items into a box and", "&7sell them all in one go.", "",
                "&7Or simply click an item in your", "&7inventory to sell that stack.", "", "&eClick to open!"), c -> {
            if (!s.canTrade()) { Msg.error(c.player(), Text.strip(s.blockReason())); return; }
            sellBox(c.player(), s, v).open(c.player());
        });
    }

    private static ItemStack entryIcon(Player p, Session s, Entry e, boolean ambiguous) {
        String id = Shops.resolve(e);
        ItemDef def = Shops.def(id);
        int shown = Math.max(1, Math.min(e.amount, def == null ? 64 : def.effectiveMaxStack()));
        ItemStack base = labelled(Sky.items().create(id, shown), e, ambiguous);
        base.setAmount(Math.max(1, Math.min(shown, base.getMaxStackSize())));
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Cost");
        lore.addAll(costLines(p, s.shop(), e, 1));
        String blocked = Trade.blocked(e);
        List<String> reqs = Shops.requirementLines(p, s.shop(), e);
        if (!reqs.isEmpty()) { lore.add(""); lore.addAll(reqs); }
        boolean locked = p != null && !Shops.meets(p, Shops.requirement(s.shop(), e));
        lore.add("");
        if (p != null && Sky.profiles().isReady(p) && def != null) {
            int limit = Trade.dailyLimit();
            int done = Trade.ledger(p).boughtToday(s.shop().id, id);
            lore.add("&7Daily stock: &a" + Text.commasInt(Math.max(0, limit - done)) + "&7/&a" + Text.commasInt(limit));
            lore.add("");
        }
        if (blocked != null) {
            lore.add(blocked);
            String hint = Trade.blockedHint(e);
            if (hint != null) lore.addAll(Text.wrap(hint, 34));
        } else if (locked) {
            lore.add("&cRequirements not met!");
        } else if (!s.canTrade()) {
            lore.addAll(Text.wrap(s.blockReason(), 34));
        } else {
            lore.add("&eClick to trade!");
            lore.add("&eShift-Click to buy a stack!");
            lore.add("&eRight-Click for more trading options!");
        }
        return Icons.withLore(base, lore);
    }

    /**
     * A stock line whose item name another line of the same tab shares (legacy "Smooth Sandstone" = the modern Cut
     * Sandstone, beside the real Smooth Sandstone) is titled by the block it stands for, with the name the item
     * itself carries underneath, so the two lines can be told apart before buying. Enchanted books are always
     * titled with their enchant, and other same-named lines with their rarity ({@link Shops#lineName}).
     */
    static ItemStack labelled(ItemStack base, Entry e, boolean ambiguous) {
        if (base == null || !Shops.renamed(e, ambiguous)) return base;
        boolean byBlock = ambiguous && Shops.blockName(e) != null;
        ItemStack it = base.clone();
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        // a book line is titled "Enchanted Book (Sharpness I)": every level's own name is just "Enchanted Book";
        // two same-named lines of different rarity are titled "Farming Exp Boost (Common)" / "(Rare)"
        meta.displayName(Text.c(Shops.lineName(e, ambiguous)));
        it.setItemMeta(meta);
        if (!byBlock) return it;
        return Icons.withLore(it, List.of("&8Item name: " + Text.strip(Shops.displayName(Shops.resolve(e)))));
    }

    /** The cost of {@code bundles} bundles at {@code shop}, one line per currency. */
    static List<String> costLines(Player p, Shop shop, Entry e, int bundles) {
        List<String> out = new ArrayList<>();
        double coins = Pricing.ceilTenth(Trade.bundleCoins(p, shop, e) * bundles);
        if (coins > 0) {
            double pct = Trade.discountPct(p, shop);
            out.add("&6" + Sky.economy().format(coins) + " Coins" + (pct > 0 ? " &8(&a-" + Text.commas(pct) + "%&8)" : ""));
            int rep = Trade.emissaryDiscount(p, shop, e);
            if (rep > 0) out.add("&8 incl. &a-" + rep + "% &8faction reputation discount");
        }
        if (e.bits > 0) out.add("&b" + Text.commasInt(e.bits * bundles) + " Bits");
        if (e.hasItemCosts()) for (ItemCost c : e.items) {
            String rid = Shops.resolve(c);
            out.add((rid == null ? "&c" + Text.titleCase(c.item) : Shops.displayName(rid)) + " &8x" + Text.commasInt((long) c.amount * bundles));
        }
        if (e.hasCurrency()) for (CurrencyCost c : e.currency) {
            String item = Shops.currencyItem(c.id);
            String line = (item != null ? Shops.displayName(item) : Shops.currencyName(c.id)) + " &8x" + Text.commasInt(c.amount * bundles);
            long bal = item == null ? Currencies.balance(p, c.id) : -1;
            if (bal >= 0) line += " &8(you have " + Text.commasInt(bal) + ")";
            out.add(line);
        }
        if (out.isEmpty()) out.add("&aFree");
        return out;
    }

    private static void onEntryClick(MenuClick c, Session s, View v, Entry e) {
        Player p = c.player();
        if (!s.canTrade()) { Msg.error(p, Text.strip(s.blockReason())); return; }
        if (c.right() && !c.shift()) { optionsMenu(p, s, v, e).open(p); return; }
        if (Trade.tooFast(p)) return;
        int bundles = c.shift() ? Trade.stackBundles(e) : 1;
        Trade.buy(p, s.shop(), e, bundles, false);
        c.menu().redraw();
    }

    private static void onSellClick(MenuClick c, ItemStack live, Session s, View v) {
        Player p = c.player();
        if (!s.canTrade()) { Msg.error(p, Text.strip(s.blockReason())); return; }
        if (Trade.tooFast(p)) return;
        double value = Trade.sellValue(live);
        if (value <= 0) { Msg.error(p, "Merchants won't buy that item."); return; }
        int slot = c.slot();
        ItemStack snapshot = live.clone();
        if (Trade.needsConfirm(live)) {
            String name = Shops.displayName(Sky.items().idOf(live));
            Sky.menus().confirm(p, "Sell this item?", List.of("&7Sell " + name + " &7for", "&6" + Sky.economy().format(value) + " Coins&7?",
                            "", "&7You can buy it back for &a" + ShopDials.BUYBACK_MINUTES.get() + " minutes&7."),
                    () -> { Trade.sellSlot(p, s.shop(), slot, snapshot); shopMenu(p, s, v.group, v.page).open(p); },
                    () -> shopMenu(p, s, v.group, v.page).open(p));
            return;
        }
        Trade.sellSlot(p, s.shop(), slot, snapshot);
        c.menu().redraw();
    }

    private static ItemStack infoIcon(Player p, Session s) {
        Shop shop = s.shop();
        List<String> lore = new ArrayList<>();
        lore.add("&8" + shop.category);
        lore.add("");
        lore.add("&7Island: &b" + Shops.islandName(shop.island));
        lore.add("&7Daily limit: &a" + Text.commasInt(Trade.dailyLimit()) + " &7of each item");
        lore.add("&7Restocks in: &e" + Text.duration(Pricing.msToReset(System.currentTimeMillis()) / 1000));
        if (shop.req != null) {
            lore.add("");
            lore.add("&7Only trades with:");
            lore.addAll(Shops.requirementLines(p, shop, null));
        }
        double acc = Trade.accessoryDiscount(p), attr = Trade.attributeDiscount(p, shop);
        double pct = Pricing.combinedDiscount(acc, attr);
        if (pct > 0) {
            lore.add("&7Your discount: &a" + Text.commas(pct) + "%");
            if (acc > 0 && attr > 0) lore.add("&8 " + Text.commas(acc) + "% accessory + " + Text.commas(attr) + "% attribute");
            else if (attr > 0) lore.add("&8 from your Hunting attribute");
        }
        if (p != null && Sky.profiles().isReady(p)) {
            Pricing.Ledger l = Trade.ledger(p);
            lore.add("&7Sold today: &6" + Text.shortNum(l.sold) + "&7/&6" + Text.shortNum(ShopDials.SELL_CAP.get()));
        }
        lore.add("");
        lore.add("&7Click an item in your inventory");
        lore.add("&7to sell the whole stack.");
        return Icons.of(Material.BOOK, "&aMerchant Info", lore);
    }

    private static ItemStack buybackIcon(Player p, Session s) {
        List<Pricing.Buyback.Sale<ItemStack>> list = Trade.buybackList(p);
        if (list.isEmpty()) {
            return Icons.of(Material.HOPPER, "&aSell Item", "&7Click items in your inventory to", "&7sell them to this merchant!", "",
                    "&7Sold items can be bought back", "&7for &a" + ShopDials.BUYBACK_MINUTES.get() + " minutes&7.");
        }
        Pricing.Buyback.Sale<ItemStack> last = list.get(0);
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Buyback cost: &6" + Sky.economy().format(last.price) + " Coins");
        lore.add("");
        lore.add("&eClick to buy back!");
        lore.add("&eRight-Click to see all &a" + list.size() + "&e recent sales!");
        return Icons.withLore(last.item, lore);
    }

    // ---- trading options --------------------------------------------------------------------------------

    static Menu optionsMenu(Player p, Session s, View v, Entry e) {
        Menu m = new Menu("Shop Trading Options", 6);
        m.drawer(menu -> {
            menu.chrome();
            String id = Shops.resolve(e);
            ItemDef def = Shops.def(id);
            boolean unique = def == null || def.isUnique();
            int[] wanted = unique ? new int[]{1} : new int[]{1, 5, 10, 32, 64};
            int[] slots = {20, 21, 22, 23, 24};
            int i = 0;
            boolean ambiguous = Shops.ambiguousLines(Shops.available(s.shop(), e.group)).contains(e);
            for (int n : wanted) {
                int bundles = n;
                int units = bundles * Math.max(1, e.amount);
                if (n > 1 && units > ShopDials.MAX_CUSTOM.get()) continue;
                ItemStack icon = labelled(Sky.items().create(id, 1), e, ambiguous);
                icon.setAmount(Math.max(1, Math.min(units, icon.getMaxStackSize() > 1 ? 64 : 1)));
                List<String> lore = new ArrayList<>();
                lore.add("");
                lore.add("&7Amount: &a" + Text.commasInt(units));
                lore.add("&7Cost");
                lore.addAll(costLines(p, s.shop(), e, bundles));
                lore.add("");
                lore.add("&eClick to purchase!");
                int b = bundles;
                menu.set(unique ? 22 : slots[i++], Icons.withLore(icon, lore), c -> {
                    if (Trade.tooFast(c.player())) return;
                    Trade.buy(c.player(), s.shop(), e, b, false);
                    menu.redraw();
                });
                if (i >= slots.length) break;
            }
            if (!unique) {
                menu.set(31, Icons.of(Material.OAK_SIGN, "&aCustom Amount", "&7Buy any number of this item,",
                        "&7up to &a" + Text.commasInt(ShopDials.MAX_CUSTOM.get()) + "&7 at once.", "", "&eClick to choose!"), c -> {
                    Player pl = c.player();
                    Sky.menus().inputNumber(pl, "How many?", n -> {
                        if (n == null || !(n >= 1)) { optionsMenu(pl, s, v, e).open(pl); return; }
                        int items = (int) Math.min(ShopDials.MAX_CUSTOM.get(), Math.floor(n));
                        int bundles = Math.max(1, (int) Math.ceil(items / (double) Math.max(1, e.amount)));
                        Trade.buy(pl, s.shop(), e, bundles, false);
                        optionsMenu(pl, s, v, e).open(pl);
                    });
                });
            }
            // inside the drawer: chrome() above repaints the whole bottom row on every draw (open() draws too),
            // so a Go Back set once outside it was wiped before the player ever saw it
            menu.back(Text.strip(s.shop().name), () -> shopMenu(p, s, v == null ? null : v.group, v == null ? 0 : v.page));
        });
        return m;
    }

    // ---- buyback list -----------------------------------------------------------------------------------

    static Menu buybackMenu(Player p, Session s, View v) {
        Menu m = new Menu("Buyback", 6);
        m.drawer(menu -> {
            menu.chrome();
            List<Pricing.Buyback.Sale<ItemStack>> list = Trade.buybackList(p);
            long now = System.currentTimeMillis();
            for (int i = 0; i < list.size() && i < AREA.length; i++) {
                Pricing.Buyback.Sale<ItemStack> sale = list.get(i);
                List<String> lore = new ArrayList<>();
                lore.add("");
                lore.add("&7Sold for: &6" + Sky.economy().format(sale.price) + " Coins");
                lore.add("&7Sold &e" + Text.duration((now - sale.at) / 1000) + " &7ago");
                lore.add("&7Expires in &e" + Text.duration(Math.max(0, Trade.buybackMaxAge() - (now - sale.at)) / 1000));
                lore.add("");
                lore.add("&eClick to buy back!");
                int idx = i;
                menu.set(AREA[i], Icons.withLore(sale.item, lore), c -> {
                    if (s != null && !s.canTrade()) { Msg.error(c.player(), Text.strip(s.blockReason())); return; }
                    if (s == null && !ShopDials.BUYBACK_ANYWHERE.get()) { Msg.error(c.player(), "Talk to any merchant to use buyback."); return; }
                    if (Trade.tooFast(c.player())) return;
                    Trade.buyBack(c.player(), idx);
                    menu.redraw();
                });
            }
            if (list.isEmpty()) menu.set(22, Icons.of(Material.BARRIER, "&cNothing to buy back",
                    "&7Items you sell to merchants stay", "&7here for &a" + ShopDials.BUYBACK_MINUTES.get() + " minutes&7."));
            // inside the drawer, after chrome(): see optionsMenu
            if (s != null) menu.back(Text.strip(s.shop().name), () -> shopMenu(p, s, v == null ? null : v.group, v == null ? 0 : v.page));
            else menu.back("Merchant Directory", () -> directory(p, 0));
        });
        return m;
    }

    // ---- the sell box -----------------------------------------------------------------------------------

    static Menu sellBox(Player p, Session s, View v) {
        Menu m = new Menu("Sell Items", 6);
        m.free(AREA);
        m.drawer(menu -> {
            for (int i = 0; i < 54; i++) {
                boolean free = false;
                for (int a : AREA) if (a == i) { free = true; break; }
                if (!free) menu.set(i, Icons.filler());
            }
            menu.closeButton();
            menu.set(48, Icons.back(Text.strip(s.shop().name)), c -> shopMenu(c.player(), s, v == null ? null : v.group, v == null ? 0 : v.page).open(c.player()));
            double total = 0;
            int kept = 0;
            for (int a : AREA) {
                ItemStack it = menu.get(a);
                if (it == null || it.getType().isAir()) continue;
                double val = Trade.sellValue(it);
                if (val > 0 && !Trade.needsConfirm(it)) total += val; else kept++;
            }
            List<String> lore = new ArrayList<>();
            lore.add("&7Put the items you want to sell");
            lore.add("&7into the empty slots above.");
            lore.add("");
            lore.add("&7Total value: &6" + Sky.economy().format(total) + " Coins");
            if (kept > 0) lore.add("&c" + kept + " stack(s) can't be sold here");
            lore.add("");
            lore.add(total > 0 ? "&eClick to sell!" : "&7Nothing to sell yet.");
            menu.set(50, Icons.of(total > 0 ? Material.EMERALD : Material.HOPPER, "&aSell Items", lore), c -> sellBoxContents(c.player(), s, menu));
            menu.set(4, Icons.of(Material.OAK_SIGN, "&aSell Box", "&7Valuable unique items are never", "&7sold from here - sell those one",
                    "&7at a time from the shop.", "", "&7Unsold items go back to your", "&7inventory when you close this."));
        });
        m.onFreeSlotChange(c -> m.redraw());
        m.refreshEvery(20);
        return m;
    }

    private static void sellBoxContents(Player p, Session s, Menu m) {
        if (!s.canTrade()) { Msg.error(p, Text.strip(s.blockReason())); return; }
        if (!ShopDials.SELL_ENABLED.get()) { Msg.error(p, "Merchants are not buying right now."); return; }
        if (Trade.tooFast(p)) return;
        double total = 0;
        int stacks = 0;
        boolean capped = false;
        for (int a : AREA) {
            ItemStack it = m.get(a);
            if (it == null || it.getType().isAir()) continue;
            if (Trade.sellValue(it) <= 0 || Trade.needsConfirm(it)) continue;
            ItemStack copy = it.clone();
            m.getInventory().setItem(a, null);
            double paid = Trade.sellLoose(p, s.shop(), copy);
            if (paid <= 0) { m.getInventory().setItem(a, copy); capped = true; continue; }
            total += paid;
            stacks++;
        }
        if (stacks > 0) {
            Msg.send(p, "&aYou sold &e" + stacks + " &astack" + (stacks == 1 ? "" : "s") + " for &6" + Sky.economy().format(total) + " Coins&a!");
            Msg.sound(p, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f);
        }
        if (capped) Msg.error(p, "You hit today's selling limit - some items were not sold.");
        m.redraw();
    }

    // ---- the directory ----------------------------------------------------------------------------------

    /**
     * One row of the Merchant Directory: one of our merchants (trading or yielded to another module's NPC on its
     * spot), or a merchant another module runs with its own menu ({@code ext}).
     */
    record DirEntry(Shop shop, ShopData.External ext) {
        String island() { return shop != null ? shop.island : ext.island; }
        String name() { return shop != null ? shop.name : ext.name; }
        boolean yielded() { return shop != null && Shops.status(shop) == Shops.Status.YIELDED; }
    }

    /** Every merchant in display order (island, then our contract merchants, then the rest by name). */
    static List<DirEntry> directoryList() {
        List<DirEntry> l = new ArrayList<>();
        boolean showYielded = ShopDials.DIRECTORY_YIELDED == null || ShopDials.DIRECTORY_YIELDED.get();
        for (Shop s : Shops.all()) {
            if (Shops.status(s) == Shops.Status.YIELDED && !showYielded) continue;
            l.add(new DirEntry(s, null));
        }
        if (ShopDials.DIRECTORY_EXTERNAL == null || ShopDials.DIRECTORY_EXTERNAL.get()) {
            for (ShopData.External x : Shops.EXTERNAL) if (Shops.externalLive(x)) l.add(new DirEntry(null, x));
        }
        l.sort(Comparator.comparingInt((DirEntry d) -> {
            int i = Shops.ISLAND_ORDER.indexOf(d.island());
            return i < 0 ? 99 : i;
        }).thenComparing(d -> d.shop() != null && d.shop().contract() ? 0 : d.ext() != null ? 1 : 2)
                .thenComparing(d -> Text.strip(d.name())));
        return l;
    }

    /** Null when the player may trade with this merchant from the directory, else the reason (with '&'). */
    static String remoteBlock(Player p, Shop s) {
        if (ShopDials.REMOTE_TRADE.get()) return null;
        if (onPhone(p, s)) return null;
        boolean stationed = Shops.isStationed(s);
        if (!stationed && ShopDials.REMOTE_UNSTATIONED.get()) {
            if ("hub".equals(s.island)) return null;
            try { if (p != null && Sky.requirements().check(p, "visited:" + s.island)) return null; } catch (Throwable ignored) { }
            return "&cVisit &b" + Shops.islandName(s.island) + " &cfirst to trade with this merchant from afar.";
        }
        return "&eVisit " + s.name + " &ein &b" + Shops.islandName(s.island) + "&e to trade.";
    }

    /**
     * True when this merchant is one of the player's Abiphone contacts (extras request 5): a phone call is a trade,
     * from anywhere. Contact ids are NPC ids ({@code shops_<shop>}); a merchant whose spot another module runs is
     * not ours to answer.
     */
    static boolean onPhone(Player p, Shop s) {
        if (p == null || s == null || Shops.status(s) == Shops.Status.YIELDED) return false;
        try {
            return ShopDials.ABIPHONE_REMOTE.get() && Sky.service(Features.Abiphone.class).hasContact(p, Shops.npcId(s));
        } catch (Throwable t) { return false; }
    }

    static Menu directory(Player p, int page) { return directory(p, page, null); }

    /** The directory, optionally filtered to one island ({@code island} null = all). */
    static Menu directory(Player p, int page, String island) {
        List<DirEntry> list = new ArrayList<>();
        List<String> islands = new ArrayList<>();
        for (DirEntry d : directoryList()) {
            if (!islands.contains(d.island())) islands.add(d.island());
            if (island == null || island.equals(d.island())) list.add(d);
        }
        String title = island == null ? "Merchant Directory" : "Merchants - " + Text.strip(Shops.islandName(island));
        return Sky.menus().paged(title, list, d -> directoryIcon(p, d), pc -> onDirectoryClick(pc.click().player(), pc.entry()), menu -> {
            menu.set(4, Icons.of(Material.COMPASS, "&aMerchant Directory", "&7Every merchant in the world:", "&7where they are and what they sell.",
                    "", "&7Merchants you can walk to must", "&7be visited to trade. Travelling", "&7merchants trade from right here,",
                    "&7and so does any merchant you", "&7have saved in your Ringphone."));
            List<String> lore = new ArrayList<>();
            lore.add(island == null ? "&a> All islands" : "&7All islands");
            for (String is : islands) lore.add((is.equals(island) ? "&a> " : "&7") + Text.strip(Shops.islandName(is)));
            lore.add("");
            lore.add("&eClick to switch!");
            lore.add("&eRight-Click to go back!");
            menu.set(47, Icons.of(Material.HOPPER, "&aFilter by Island", lore), c -> {
                int i = island == null ? -1 : islands.indexOf(island);
                int n = c.right() ? i - 1 : i + 1;
                String next;
                if (islands.isEmpty()) next = null;
                else if (n < 0) next = i == -1 ? islands.get(islands.size() - 1) : null;
                else next = n >= islands.size() ? null : islands.get(n);
                directory(c.player(), 0, next).open(c.player());
            });
            menu.set(51, Icons.of(Material.CLOCK, "&aBuyback", "&7Your recent sales, ready to be", "&7bought back.", "", "&eClick to view!"),
                    c -> {
                        if (!ShopDials.BUYBACK_ANYWHERE.get()) { Msg.error(c.player(), "Talk to any merchant to use buyback."); return; }
                        buybackMenu(c.player(), null, null).open(c.player());
                    });
        }, page);
    }

    private static void onDirectoryClick(Player pl, DirEntry d) {
        if (pl == null || d == null) return;
        if (d.ext() != null) {
            ShopData.External x = d.ext();
            String[] args = x.args == null ? new String[0] : x.args.toArray(new String[0]);
            // the owner's own command applies its own access rules (in person, cookie, event running...)
            if (!Shops.externalLive(x) || !Sky.commands().run(pl, x.command, args))
                Msg.error(pl, "Visit " + Text.strip(x.name) + " in " + Text.strip(Shops.islandName(x.island)) + ".");
            return;
        }
        Shop s = d.shop();
        if (d.yielded()) {
            Msg.send(pl, "&eVisit " + Shops.yieldedName(s) + " &ein &b" + Shops.islandName(s.island) + "&e to trade.");
            return;
        }
        String block = remoteBlock(pl, s);
        shopMenu(pl, new Session(s, block == null, block, true), null, 0).open(pl);
    }

    static ItemStack directoryIcon(Player p, DirEntry d) {
        if (d.ext() != null) return externalIcon(d.ext());
        Shop s = d.shop();
        if (d.yielded()) {
            List<String> lore = new ArrayList<>();
            lore.add("&8" + s.category);
            lore.add("");
            lore.add("&7Island: &b" + Shops.islandName(s.island));
            lore.add("&7Found: &aIn person");
            lore.add("");
            lore.addAll(Text.wrap("&7Keeps a stall of their own: visit them to see what they sell today.", 34));
            lore.add("");
            lore.add("&eClick for directions!");
            return Icons.of(material(s.icon, Material.EMERALD), Shops.yieldedName(s), lore);
        }
        List<String> lore = new ArrayList<>();
        lore.add("&8" + s.category);
        lore.add("");
        lore.add("&7Island: &b" + Shops.islandName(s.island));
        if (Shops.isStationed(s)) lore.add("&7Found: &aIn person");
        else {
            lore.add("&7Found: &eTravelling merchant");
            if (s.where != null) lore.addAll(Text.wrap("&8Will set up shop at " + s.where + ".", 34));
        }
        if (onPhone(p, s)) lore.add("&7Ringphone: &aIn your contacts");
        int n = Shops.availableCount(s);
        lore.add("&7Stock: &a" + n + " &7item" + (n == 1 ? "" : "s"));
        List<String> reqs = Shops.requirementLines(p, s, null);
        if (!reqs.isEmpty()) { lore.add(""); lore.addAll(reqs); }
        lore.add("");
        lore.add(remoteBlock(p, s) == null ? "&eClick to trade!" : "&eClick to browse!");
        return Icons.of(material(s.icon, Material.EMERALD), s.name, lore);
    }

    private static ItemStack externalIcon(ShopData.External x) {
        List<String> lore = new ArrayList<>();
        lore.add("&8" + (x.category == null ? "Merchant" : x.category));
        lore.add("");
        lore.add("&7Island: &b" + Shops.islandName(x.island));
        if (x.about != null && !x.about.isBlank()) lore.addAll(Text.wrap("&7" + x.about, 34));
        lore.add("");
        lore.add("&eClick to open!");
        return Icons.of(material(x.icon, Material.EMERALD), x.name, lore);
    }

    static Material material(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m == null || !m.isItem() || m.isAir() ? fallback : m;
    }
}
