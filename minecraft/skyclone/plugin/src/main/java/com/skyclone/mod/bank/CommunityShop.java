package com.skyclone.mod.bank;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.util.Requirements;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Community Shop (research 05 section 6.4) at hub {@code npc.community_shop}: the Boon Cookie for
 * coins (we sell nothing for real money), the Bits Shop, fame ranks, and Account & Profile Upgrades with
 * timed construction (one profile upgrade and one account upgrade building at a time).
 *
 * <p>Upgrades other modules read: {@code Sky.requirements().check(p, "cshop:<upgrade>:<tier>")}.
 * This module implements Coins Allowance, Magic Find and Garden Farming Fortune itself.
 */
final class CommunityShop {

    private CommunityShop() {}

    static final String NPC_ID = "bank_community_shop";
    static final String NPC_NAME = "&fRosamund";

    // ---- registration --------------------------------------------------------------------------------

    static void register() {
        NpcDef def = NpcDef.of(NPC_ID, NPC_NAME, "hub", "npc.community_shop").title("&e&lCOMMUNITY SHOP");
        String skin = Bank.catalog.npcSkins.get("community_shop");
        if (skin != null) def.skin(skin, null);
        def.lines.add("&7Bits, cookies & upgrades");
        Sky.npcs().register(def, p -> {
            if (!Bank.ready(p)) return;
            open(p, false);
        });
        Sky.stats().addProvider("bank.upgrades", CommunityShop::stats);
        Sky.requirements().register("cshop", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 2 || !Bank.ready(p)) return false;
                int need = 1;
                if (parts.length > 2) try { need = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException e) { return false; }
                noteReader(parts[1].trim());
                return owned(p, parts[1].trim()) >= need;
            }
            @Override public String describe(String[] parts) {
                BankCatalog.Upgrade u = parts.length > 1 ? Bank.catalog.upgrades.get(parts[1].trim()) : null;
                String tier = parts.length > 2 ? parts[2].trim() : "1";
                return "&a" + (u == null ? (parts.length > 1 ? parts[1] : "?") : u.name) + " " + roman(tier) + " &7(Community Shop)";
            }
        });
        Sky.requirements().register("fame", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 2 || !Bank.ready(p)) return false;
                try { return Bank.member(p).fame >= Long.parseLong(parts[1].trim()); } catch (NumberFormatException e) { return false; }
            }
            @Override public String describe(String[] parts) { return "&e" + (parts.length > 1 ? parts[1] : "?") + " Fame"; }
        });
        Sky.requirements().register("famerank", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 2 || !Bank.ready(p)) return false;
                try { return BankMath.fameRank(Bank.member(p).fame, Bank.catalog.fame) >= Integer.parseInt(parts[1].trim()); }
                catch (NumberFormatException e) { return false; }
            }
            @Override public String describe(String[] parts) {
                try {
                    int i = Integer.parseInt(parts[1].trim());
                    if (i >= 0 && i < Bank.catalog.fame.size()) return "&eFame Rank " + Bank.catalog.fame.get(i).name;
                } catch (RuntimeException ignored) { }
                return "&eFame Rank " + (parts.length > 1 ? parts[1] : "?");
            }
        });
    }

    private static String roman(String n) {
        try { return BankMath.roman(Integer.parseInt(n)); } catch (NumberFormatException e) { return n; }
    }

    static boolean nearNpc(Player p) {
        Entity e = Sky.npcs().entity(NPC_ID);
        Location at = p.getLocation();
        double r = Math.max(1, Bank.bankerRange.get());
        if (e != null && e.getWorld().equals(at.getWorld()) && e.getLocation().distanceSquared(at) <= r * r) return true;
        Location l = Sky.worlds().anchor("hub", "npc.community_shop");
        return l != null && l.getWorld() != null && l.getWorld().equals(at.getWorld()) && l.distanceSquared(at) <= r * r;
    }

    /** {@code remote}: opened by a command or another menu rather than the NPC. */
    static void open(Player p, boolean remote) {
        if (!Bank.ready(p)) return;
        if (!Bank.accountReady(p)) { Msg.error(p, "Your account is still loading, try again in a moment."); return; }
        if (remote && !Bank.cshopRemote.get() && !nearNpc(p)) {
            Msg.error(p, "Visit Rosamund at the Community Center in the Hub to use the Community Shop!");
            return;
        }
        checkUpgrades(p);
        main(p).open(p);
    }

    // ---- main menu -----------------------------------------------------------------------------------

    static Menu main(Player p) {
        Menu m = new Menu("Community Shop", 6).chrome();
        BankData.Member d = Bank.member(p);
        BankMath.FameRank rank = Bank.catalog.fameRank(d.fame);
        int ri = BankMath.fameRank(d.fame, Bank.catalog.fame);
        BankMath.FameRank next = ri + 1 < Bank.catalog.fame.size() ? Bank.catalog.fame.get(ri + 1) : null;
        List<String> me = new ArrayList<>();
        me.add("&7Bits: &b" + BankMath.commas(Sky.economy().bits(p)));
        me.add("&7Bits Available: &b" + BankMath.commas(d.bitsAvailable));
        me.add("");
        me.add("&7Fame: &e" + BankMath.commas(d.fame));
        me.add("&7Fame Rank: &e" + (rank == null ? "?" : rank.name));
        me.add("&7Bits multiplier: &a" + (rank == null ? "1" : BankMath.commas(rank.mult)) + "x");
        if (next != null) me.add("&7Next rank: &e" + next.name + " &7at &e" + BankMath.commas(next.fame) + " Fame");
        me.add("");
        me.add("&8Every Bit you spend here earns 1 Fame;");
        me.add("&8cookies and upgrades bought with coins");
        me.add("&8earn " + BankMath.commas(Bank.famePerGem.get()) + " Fame per gem of their value.");
        if (Bank.fameOtherSources.get()) me.add("&8Your multiplier also boosts Bits from other rewards.");
        m.set(4, Icons.playerHead(p.getUniqueId(), "&aYour Community Profile", me));

        double price = cookiePrice();
        List<String> cl = new ArrayList<>(List.of(
                "&7Grants the &dBoon Buff &7for &b" + BankMath.duration((long) (Bank.cookieHours.get() * 3_600_000L)) + "&7:",
                "&8- &7Bits while you play, &3+Wisdom&7,",
                "&8  &b+Magic Find&7, keep coins on death,",
                "&8  &7flight on your island, /bank anywhere.",
                "",
                "&7Cost",
                "&6" + BankMath.coinWords(price)));
        long cookieFame = fameForGems(Bank.cookieGems.get());
        if (cookieFame > 0) cl.add("&7Earns &e" + BankMath.commas(cookieFame) + " Fame");
        double[] bz = bazaarCookie();
        if (bz != null) {
            cl.add("");
            cl.add("&7Bazaar: &6" + (bz[0] > 0 ? BankMath.coinWords(bz[0]) : "&8none for sale") + " &7to buy");
            if (bz[1] > 0) cl.add("&7Bazaar: &6" + BankMath.coinWords(bz[1]) + " &7to sell");
        }
        cl.add("");
        cl.add("&eClick to buy!");
        if (bz != null) cl.add("&bRight-click to see it on the Bazaar!");
        m.set(19, Icons.glow(Icons.of(Material.COOKIE, "&6Boon Cookie", cl)), c -> {
            if (c.right() && bazaarCookie() != null) {
                try { Sky.service(Features.Bazaar.class).open(c.player(), Cookie.COOKIE); return; } catch (RuntimeException ignored) { }
            }
            buyCookie(c.player());
        });
        m.set(21, Icons.of(Material.PRISMARINE_CRYSTALS, "&bBits Shop",
                "&7Trade the &bBits &7your Boon Buff earns", "&7for useful items, books and sacks.", "",
                "&7Your Bits: &b" + BankMath.commas(Sky.economy().bits(p)), "", "&eClick to browse!"),
                c -> bitsCategories(c.player()).open(c.player()));
        m.set(23, Icons.of(Material.ANVIL, "&aAccount & Profile Upgrades",
                "&7Expand your island, minion slots,", "&7storage and more. Upgrades take", "&7real time to build.",
                "", buildingLine(Bank.shared(p).building, Bank.shared(p).buildingDone, "Profile"),
                buildingLine(d.building, d.buildingDone, "Account"), "", "&eClick to view!"),
                c -> upgrades(c.player()).open(c.player()));
        m.set(25, Icons.of(Material.GOLD_INGOT, "&eFame Ranks", "&7Spending Bits earns Fame, and Fame", "&7raises your bits multiplier and",
                "&7your votes in the election.", "", "&eClick to view!"), c -> fameMenu(c.player()).open(c.player()));
        m.set(31, Icons.of(Material.COOKIE, "&dBoon Buff",
                Cookie.active(p) ? "&7Active: &a" + BankMath.duration(Cookie.millisLeft(p)) + " left" : "&7Not active",
                "", "&eClick to view!"), c -> Cookie.menu(c.player()).open(c.player()));
        if (Bank.cookieFreeGift.get()) {
            if (!d.freeCookieClaimed) {
                m.set(33, Icons.glow(Icons.of(Material.CAKE, "&aFree Boon Cookie",
                        "&7A welcome gift: one Boon Cookie", "&7that cannot be traded.", "", "&eClick to claim!")),
                        c -> claimFree(c.player()));
            } else {
                m.set(33, Icons.of(Material.GRAY_DYE, "&7Free Boon Cookie", "&7You already claimed your gift."));
            }
        }
        m.set(29, Icons.of(Material.GOLD_BLOCK, "&6Bank", "&7Your bank account.", "",
                Accounts.nearBanker(p) || Accounts.remoteAllowed(p) ? "&eClick to open!" : "&cVisit a Banker or use the Boon Buff."),
                c -> BankMenus.openCommand(c.player()));
        // Playtest wave 5 (extras request 2): the reference's Community Shop has City Projects and Fire
        // Sales tabs; extras built both, but nothing in this menu led to them.
        if (Sky.commands().exists("cityprojects")) {
            m.set(38, Icons.of(Material.BRICKS, "&6City Projects", "&7Fund the town's projects for", "&7permanent perks and SkyBlock XP.",
                    "", "&eClick to view!"), c -> Sky.commands().run(c.player(), "cityprojects"));
        }
        if (Sky.commands().exists("firesales")) {
            m.set(42, Icons.of(Material.BLAZE_POWDER, "&cFire Sales", "&7Limited cosmetic skins, paid", "&7in earned Bits.",
                    "", "&eClick to view!"), c -> Sky.commands().run(c.player(), "firesales"));
        }
        if (Sky.has(Features.SkyMenu.class)) {
            m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        }
        return m;
    }

    private static String buildingLine(String building, long done, String scope) {
        if (building == null || building.isEmpty()) return "&7" + scope + " upgrade: &8none building";
        BankCatalog.Upgrade u = Bank.catalog.upgrades.get(building);
        long left = done - System.currentTimeMillis();
        return "&7" + scope + " upgrade: &e" + (u == null ? building : u.name)
                + (left > 0 ? " &7(" + BankMath.duration(left) + ")" : " &a(done!)");
    }

    // ---- cookie --------------------------------------------------------------------------------------

    /**
     * Community Shop cookie price. The cookie also trades on the Bazaar (research 6.5: the coin price floats
     * there), so the shop never sells below what the Bazaar pays for one: otherwise buying here and instantly
     * selling there would print coins. Dial {@code bank.cookie.price-follows-bazaar}.
     */
    static double cookiePrice() {
        double base = Math.max(0, Bank.cookiePrice.get());
        if (!Bank.cookieFollowsBazaar.get()) return base;
        double[] bz = bazaarCookie();
        if (bz == null || bz[1] <= base) return base;
        return Math.ceil(bz[1]);
    }

    /** {instant buy, instant sell} of a cookie on the Bazaar, or null when it is not traded there. */
    static double[] bazaarCookie() {
        try {
            if (!Sky.has(Features.Bazaar.class)) return null;
            Features.Bazaar b = Sky.service(Features.Bazaar.class);
            if (!b.isBazaarItem(Cookie.COOKIE)) return null;
            return new double[]{b.instantBuy(Cookie.COOKIE), b.instantSell(Cookie.COOKIE)};
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void buyCookie(Player p) {
        double price = cookiePrice();
        if (Sky.economy().purse(p) < price) {
            Msg.error(p, "You need " + BankMath.coinWords(price) + " in your purse.");
            return;
        }
        Sky.menus().confirm(p, "Buy a Boon Cookie?", List.of("&7Cost: &6" + BankMath.coinWords(price)), () -> {
            if (!Bank.ready(p)) return;
            if (!Sky.economy().withdraw(p, price, "bank: community shop boon cookie")) {
                Msg.error(p, "You don't have enough coins in your purse.");
                return;
            }
            Sky.loot().give(p, Cookie.COOKIE, 1, LootSource.NPC_SHOP);
            Msg.success(p, "You bought a &6Boon Cookie&a! Right-click it to eat it.");
            // research 6.3: 200 Fame per gem spent at the Community Shop; the cookie is 325 gems there
            long fame = Bank.accountReady(p) ? fameForGems(Bank.cookieGems.get()) : 0;
            if (fame > 0) {
                addFame(p, fame);
                Msg.send(p, "&e+" + BankMath.commas(fame) + " Fame");
                Sky.profiles().saveNow(p);
            }
            main(p).open(p);
        }, null);
    }

    static void claimFree(Player p) {
        BankData.Member d = Bank.member(p);
        if (d.freeCookieClaimed || !Bank.cookieFreeGift.get()) { Msg.error(p, "You already claimed your free Boon Cookie."); return; }
        d.freeCookieClaimed = true;
        Sky.profiles().saveNow(p);
        Sky.loot().give(p, Cookie.FREE_COOKIE, 1, LootSource.REWARD);
        Msg.success(p, "Here is your free &6Boon Cookie&a! Right-click it to eat it.");
        main(p).open(p);
    }

    // ---- bits shop ---------------------------------------------------------------------------------------

    /** The item a bits offer gives in this game, or null when no module registered it. */
    static String resolve(BankCatalog.BitsOffer o) {
        if (o.item != null && Sky.items().exists(o.item)) return o.item;
        if (o.alternatives != null) for (String a : o.alternatives) if (a != null && Sky.items().exists(a)) return a;
        return null;
    }

    static Map<String, List<BankCatalog.BitsOffer>> byCategory(boolean onlyAvailable) {
        Map<String, List<BankCatalog.BitsOffer>> out = new LinkedHashMap<>();
        for (BankCatalog.BitsOffer o : Bank.catalog.bitsShop) {
            if (onlyAvailable && resolve(o) == null) continue;
            out.computeIfAbsent(o.category == null ? "Items" : o.category, k -> new ArrayList<>()).add(o);
        }
        return out;
    }

    private static Material categoryIcon(String cat) {
        return switch (cat) {
            case "Abiphone", "Ringphone" -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            case "Dyes" -> Material.MAGENTA_DYE;
            case "Enchanted Books" -> Material.ENCHANTED_BOOK;
            case "Enrichments" -> Material.AMETHYST_SHARD;
            case "Sacks" -> Material.BUNDLE;
            default -> Material.CHEST;
        };
    }

    static Menu bitsCategories(Player p) {
        Menu m = new Menu("Bits Shop", 4).chrome();
        m.back("Community Shop", () -> main(p));
        Map<String, List<BankCatalog.BitsOffer>> cats = byCategory(true);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        for (Map.Entry<String, List<BankCatalog.BitsOffer>> e : cats.entrySet()) {
            if (i >= slots.length) break;
            String cat = e.getKey();
            m.set(slots[i++], Icons.of(categoryIcon(cat), "&a" + cat, "&7" + e.getValue().size() + " offers", "", "&eClick to browse!"),
                    c -> bitsList(c.player(), cat, 0).open(c.player()));
        }
        if (cats.isEmpty()) {
            m.set(13, Icons.of(Material.BARRIER, "&cNothing in stock", "&7None of the Bits Shop's items exist", "&7in this game yet."));
        }
        m.set(4, Icons.of(Material.PRISMARINE_CRYSTALS, "&bYour Bits", "&7Bits: &b" + BankMath.commas(Sky.economy().bits(p)),
                "&7Fame: &e" + BankMath.commas(Bank.member(p).fame)));
        return m;
    }

    static Menu bitsList(Player p, String category, int page) {
        List<BankCatalog.BitsOffer> list = byCategory(true).getOrDefault(category, List.of());
        long bits = Sky.economy().bits(p);
        return Sky.menus().paged("Bits Shop - " + category, list, o -> offerIcon(o, bits),
                pc -> buyOffer(pc.click().player(), pc.entry(), category, pc.page()),
                menu -> menu.back("Bits Shop", () -> bitsCategories(p)), page);
    }

    private static ItemStack offerIcon(BankCatalog.BitsOffer o, long bits) {
        String id = resolve(o);
        ItemStack base;
        try { base = Sky.items().create(id, 1); } catch (RuntimeException e) { base = new ItemStack(Material.BARRIER); }
        List<String> extra = new ArrayList<>();
        extra.add("");
        extra.add("&7Cost");
        extra.add("&b" + BankMath.commas(o.bits) + " Bits");
        extra.add("");
        extra.add(bits >= o.bits ? "&eClick to trade!" : "&cYou don't have enough Bits!");
        return Icons.withLore(base, extra);
    }

    static void buyOffer(Player p, BankCatalog.BitsOffer o, String category, int page) {
        String id = resolve(o);
        if (id == null) { Msg.error(p, "That item is not available."); return; }
        if (Sky.economy().bits(p) < o.bits) { Msg.error(p, "You don't have enough Bits!"); return; }
        Runnable buy = () -> {
            if (!Bank.ready(p)) return;
            if (!Sky.economy().spendBits(p, o.bits, "bank: bits shop " + id)) { Msg.error(p, "You don't have enough Bits!"); return; }
            BankData.Member d = Bank.member(p);
            d.bitsSpent += o.bits;
            Sky.loot().give(p, id, 1, LootSource.NPC_SHOP);
            ItemDef def = Sky.items().def(id);
            Msg.success(p, "You bought " + (def == null ? id : def.rarity.code() + def.name) + " &afor &b" + BankMath.commas(o.bits) + " Bits&a!");
            addFame(p, o.bits);   // research 6.3: 1 Fame per bit spent
            Sky.profiles().saveNow(p);
            bitsList(p, category, page).open(p);
        };
        if (o.bits >= Bank.confirmBits.get()) {
            ItemDef def = Sky.items().def(id);
            Sky.menus().confirm(p, "Confirm Purchase", List.of("&7Buy " + (def == null ? id : def.rarity.code() + def.name),
                    "&7for &b" + BankMath.commas(o.bits) + " Bits&7?"), buy, () -> bitsList(p, category, page).open(p));
        } else buy.run();
    }

    // ---- fame ranks ------------------------------------------------------------------------------------------

    static Menu fameMenu(Player p) {
        long fame = Bank.member(p).fame;
        int cur = BankMath.fameRank(fame, Bank.catalog.fame);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < Bank.catalog.fame.size(); i++) idx.add(i);
        return Sky.menus().paged("Fame Ranks", idx, i -> {
            BankMath.FameRank r = Bank.catalog.fame.get(i);
            Material mat = i < cur ? Material.LIME_STAINED_GLASS_PANE : i == cur ? Material.GOLD_INGOT : Material.GRAY_DYE;
            String status = i < cur ? "&aReached" : i == cur ? "&eYour rank" : "&7Need &e" + BankMath.commas(r.fame - fame) + " &7more Fame";
            return Icons.of(mat, (i == cur ? "&6" : i < cur ? "&a" : "&7") + r.name,
                    "&7Fame needed: &e" + BankMath.commas(r.fame),
                    "&7Bits multiplier: &a" + BankMath.commas(r.mult) + "x",
                    "&7Election votes: &a" + r.votes, "", status);
        }, pc -> {}, menu -> menu.back("Community Shop", () -> main(p)), cur / 28);
    }

    // ---- upgrades ------------------------------------------------------------------------------------------

    static int owned(Player p, String upgradeId) {
        BankCatalog.Upgrade u = Bank.catalog.upgrades.get(upgradeId);
        if (u == null) return 0;
        Map<String, Integer> map = u.profileScope() ? Bank.shared(p).upgrades : Bank.member(p).upgrades;
        return Math.max(0, Math.min(map.getOrDefault(upgradeId, 0), u.maxTier()));
    }

    static double value(Player p, String upgradeId) {
        BankCatalog.Upgrade u = Bank.catalog.upgrades.get(upgradeId);
        int t = owned(p, upgradeId);
        if (u == null || t <= 0) return 0;
        return u.tier(t).value;
    }

    static double coinCost(BankCatalog.UpgradeTier t) {
        return Math.ceil(t.gems * Math.max(0, Bank.coinsPerGem.get()));
    }

    static long buildMillis(BankCatalog.UpgradeTier t) {
        return (long) (t.hours * 3_600_000L * Math.max(0, Bank.buildTimeScale.get()));
    }

    static Menu upgrades(Player p) {
        checkUpgrades(p);
        Menu m = new Menu("Account & Profile Upgrades", 6).chrome();
        m.back("Community Shop", () -> main(p));
        BankData.Shared s = Bank.shared(p);
        BankData.Member d = Bank.member(p);
        m.set(4, Icons.of(Material.BOOK, "&aHow upgrades work",
                "&7One &eprofile &7upgrade (shared with your", "&7co-op) and one &eaccount &7upgrade can be",
                "&7built at a time. Building keeps going", "&7while you are offline.", "",
                buildingLine(s.building, s.buildingDone, "Profile"), buildingLine(d.building, d.buildingDone, "Account")));
        m.set(9, Icons.of(Material.OAK_SIGN, "&eProfile Upgrades", "&7Shared by everyone on this profile."));
        m.set(18, Icons.of(Material.OAK_SIGN, "&eAccount Upgrades", "&7Yours alone."));
        int[] profileSlots = {10, 11, 12, 13, 14, 15, 16};
        int[] accountSlots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int pi = 0, ai = 0;
        for (BankCatalog.Upgrade u : Bank.catalog.upgrades.values()) {
            int slot;
            if (u.profileScope()) { if (pi >= profileSlots.length) continue; slot = profileSlots[pi++]; }
            else { if (ai >= accountSlots.length) continue; slot = accountSlots[ai++]; }
            m.set(slot, upgradeIcon(p, u), c -> startUpgrade(c.player(), u.id));
        }
        return m;
    }

    private static Material upgradeMaterial(String id) {
        return switch (id) {
            case "island_size" -> Material.GRASS_BLOCK;
            case "minion_slots" -> Material.ARMOR_STAND;
            case "guests_limit" -> Material.OAK_DOOR;
            case "coop_slots" -> Material.PLAYER_HEAD;
            case "coins_allowance" -> Material.GOLD_NUGGET;
            case "armor_wardrobe" -> Material.LEATHER_CHESTPLATE;
            case "equipment_wardrobe" -> Material.LEAD;
            case "loadout_slots" -> Material.ITEM_FRAME;
            case "hotm_slots" -> Material.PRISMARINE_SHARD;
            case "ender_chest_pages" -> Material.ENDER_CHEST;
            case "accessory_bag" -> Material.BUNDLE;
            case "sack_of_sacks" -> Material.RABBIT_HIDE;
            case "magic_find" -> Material.RABBIT_FOOT;
            case "bazaar_flipper" -> Material.GOLD_BLOCK;
            case "garden_farming_fortune" -> Material.WHEAT;
            default -> Material.PAPER;
        };
    }

    static ItemStack upgradeIcon(Player p, BankCatalog.Upgrade u) {
        int cur = owned(p, u.id);
        BankCatalog.UpgradeTier next = u.tier(cur + 1);
        String building = u.profileScope() ? Bank.shared(p).building : Bank.member(p).building;
        long done = u.profileScope() ? Bank.shared(p).buildingDone : Bank.member(p).buildingDone;
        List<String> lore = new ArrayList<>();
        lore.add("&8" + (u.profileScope() ? "Profile upgrade (this profile)" : "Account upgrade (all your profiles)"));
        lore.add("");
        String what = WHAT.get(u.id);
        if (what != null) { for (String l : what.split("[|]")) lore.add("&7" + l); lore.add(""); }
        if (u.id.equals("coop_slots")) {
            String pid = Sky.profiles().profileId(p);
            int members = pid == null ? 1 : Math.max(1, Sky.profiles().members(pid).size());
            lore.add("&7Your co-op: &a" + members + "&7/&a" + CoopSlots.capFor(p) + " &7members");
            if (!CoopSlots.live()) lore.add("&8The server allows " + CoopSlots.coreCap() + " members per co-op.");
            lore.add("");
        }
        boolean live = live(u.id);
        lore.add("&7Current: " + (cur == 0 ? "&8none" : "&a" + BankMath.roman(cur) + " &7- &a" + fmt(u.tier(cur).value) + " &7" + u.unit));
        if (next != null) {
            lore.add("&7Next: &e" + BankMath.roman(next.tier) + " &7- &e" + fmt(next.value) + " &7" + u.unit);
            lore.add("");
            double cost = coinCost(next);
            lore.add("&7Cost: " + (cost <= 0 ? "&aFree" : "&6" + BankMath.coinWords(cost)));
            if (cost > 0 && fameForGems(next.gems) > 0) lore.add("&7Earns &e" + BankMath.commas(fameForGems(next.gems)) + " Fame");
            lore.add("&7Build time: &e" + (buildMillis(next) <= 0 ? "instant" : BankMath.duration(buildMillis(next))));
            lore.add("");
            if (u.id.equals(building)) {
                long left = done - System.currentTimeMillis();
                lore.add(left > 0 ? "&eBuilding... &7" + BankMath.duration(left) + " left" : "&aFinished!");
            } else if (!live && !Bank.cshopSellUnread.get()) {
                lore.add("&cComing soon!");
                lore.add("&8Nothing in this world uses it yet,");
                lore.add("&8so it cannot be built.");
            } else if (building != null && !building.isEmpty()) {
                lore.add("&cAnother " + (u.profileScope() ? "profile" : "account") + " upgrade is being built.");
            } else {
                lore.add("&eClick to start building!");
            }
        } else {
            lore.add("");
            lore.add("&aMaxed out!");
        }
        if (!live && Bank.cshopSellUnread.get()) {
            lore.add("");
            lore.add("&8Nothing in this world uses it yet:");
            lore.add("&8it only records the tier for now.");
        }
        ItemStack it = Icons.of(upgradeMaterial(u.id), (next == null ? "&a" : (live ? "&e" : "&7")) + u.name, lore);
        if (cur > 0) it.setAmount(Math.min(64, cur));
        return it;
    }

    /**
     * Upgrades that do something in this world: listed in dial {@code bank.cshop.live-upgrades}, or (wave 5, dial
     * {@code bank.cshop.auto-live}) seen being read by another module through {@code cshop:<id>:<tier>}. Co-op Slots
     * is this module's own ({@link CoopSlots}) and is live whenever the core co-op cap leaves room for it.
     */
    static boolean live(String id) {
        if (id == null) return false;
        if (inLiveDial(id)) return true;
        if (id.equals("coop_slots") && CoopSlots.live()) return true;
        if (!Bank.cshopAutoLive.get()) return false;
        java.util.Set<String> r = READERS.get(id);
        return r != null && !r.isEmpty();
    }

    static boolean inLiveDial(String id) {
        String list = Bank.cshopLiveUpgrades.get();
        if (list == null || id == null) return false;
        for (String s : list.split(",")) if (s.trim().equalsIgnoreCase(id)) return true;
        return false;
    }

    // ---- who reads each upgrade (wave 5) ---------------------------------------------------------------

    /** upgrade id -> modules seen calling {@code cshop:<id>:<tier>} (persisted in {@code bank_cshop_readers}). */
    private static final Map<String, java.util.Set<String>> READERS = new java.util.concurrent.ConcurrentHashMap<>();
    /** upgrade id -> last time the caller was looked up (a stack walk is cheap, but not on every check). */
    private static final Map<String, Long> LAST_WALK = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long WALK_EVERY_MS = 30_000;

    static void loadReaders() {
        Bank.store.loadReaders().whenComplete((rows, ex) -> {
            if (ex != null) { Sky.log().warning("[bank] could not load Community Shop readers: " + ex); return; }
            for (String[] r : rows) addReaderMemory(r[0], r[1]);
        });
    }

    private static boolean addReaderMemory(String upgrade, String module) {
        return READERS.computeIfAbsent(upgrade, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(module);
    }

    /**
     * Called from the {@code cshop} resolver: remember which module asked, so an upgrade becomes buildable the moment
     * its owner reads it (no dial edit). Requirement checks made by the bank itself, core or an admin command name no
     * other module and are ignored ({@link BankMath#callerModule}).
     */
    static void noteReader(String upgrade) {
        if (upgrade == null || Bank.catalog == null || !Bank.catalog.upgrades.containsKey(upgrade)) return;
        if (Bank.cshopAutoLive == null || !Bank.cshopAutoLive.get()) return;
        long now = System.currentTimeMillis();
        Long last = LAST_WALK.get(upgrade);
        if (last != null && now - last < WALK_EVERY_MS) return;
        LAST_WALK.put(upgrade, now);
        String mod;
        try {
            List<String> frames = StackWalker.getInstance().walk(st -> st.limit(32).map(StackWalker.StackFrame::getClassName).toList());
            mod = BankMath.callerModule(frames);
        } catch (RuntimeException e) {
            return;
        }
        if (mod == null) return;
        if (addReaderMemory(upgrade, mod)) {
            Sky.log().info("[bank] module '" + mod + "' reads Community Shop upgrade " + upgrade + ": it can now be built");
            Bank.store.addReader(upgrade, mod);
        }
    }

    static String readersOf(String upgrade) {
        java.util.Set<String> r = READERS.get(upgrade);
        return r == null || r.isEmpty() ? "none" : String.join(",", new java.util.TreeSet<>(r));
    }

    static void clearReaders(String upgrade) {
        if (upgrade == null) { READERS.clear(); LAST_WALK.clear(); }
        else { READERS.remove(upgrade); LAST_WALK.remove(upgrade); }
        Bank.store.clearReaders(upgrade);
    }

    // ---- fame (research 6.3: 1 per bit spent, 200 per gem spent at the Community Shop) ----------------------

    /** Add Fame and announce a rank-up. Fame is account-wide: callers check {@link Bank#accountReady} first. */
    static void addFame(Player p, long fame) {
        if (fame <= 0 || !Bank.ready(p)) return;
        BankData.Member d = Bank.member(p);
        int before = BankMath.fameRank(d.fame, Bank.catalog.fame);
        d.fame += fame;
        int after = BankMath.fameRank(d.fame, Bank.catalog.fame);
        if (after > before) {
            BankMath.FameRank r = Bank.catalog.fame.get(after);
            Msg.box(p, "&e&lFAME RANK UP &e" + r.name, List.of("&7Bits multiplier: &a" + BankMath.commas(r.mult) + "x",
                    "&7Election votes: &a" + r.votes));
            Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        }
    }

    /** Fame for something the reference sells for {@code gems} that we sell for coins (dial bank.cshop.fame-per-gem). */
    static long fameForGems(double gems) { return BankMath.fameForGems(gems, Bank.famePerGem.get()); }

    /** What each upgrade does (research 05 section 6.4), shown above its tiers. */
    static final Map<String, String> WHAT = Map.ofEntries(
            Map.entry("island_size", "Makes your private island|wider, so you can build further out."),
            Map.entry("minion_slots", "Lets you place more minions|on your island."),
            Map.entry("guests_limit", "More visitors may be on your|island at the same time."),
            Map.entry("coop_slots", "More members in your co-op."),
            Map.entry("coins_allowance", "Coins paid into your purse|once a day while you play."),
            Map.entry("armor_wardrobe", "More armor sets stored|in your Wardrobe."),
            Map.entry("equipment_wardrobe", "More equipment sets stored|in your Wardrobe."),
            Map.entry("loadout_slots", "More saved loadouts."),
            Map.entry("hotm_slots", "More saved trees for the|Heart of the Mountain."),
            Map.entry("ender_chest_pages", "More Ender Chest pages|in your Storage."),
            Map.entry("accessory_bag", "More slots in your|Accessory Bag."),
            Map.entry("sack_of_sacks", "More slots in your|Sack of Sacks."),
            Map.entry("magic_find", "Permanent Magic Find,|everywhere."),
            Map.entry("bazaar_flipper", "More Bazaar orders at once|and a lower Bazaar tax."),
            Map.entry("garden_farming_fortune", "Farming Fortune while|you farm on the Garden."));

    private static String fmt(double v) { return BankMath.commas(v); }

    static void startUpgrade(Player p, String id) {
        BankCatalog.Upgrade u = Bank.catalog.upgrades.get(id);
        if (u == null || !Bank.ready(p)) return;
        checkUpgrades(p);
        int cur = owned(p, id);
        BankCatalog.UpgradeTier next = u.tier(cur + 1);
        if (next == null) { Msg.error(p, "That upgrade is maxed out."); return; }
        if (!live(id) && !Bank.cshopSellUnread.get()) {
            Msg.error(p, u.name + " is coming soon: nothing in this world uses it yet, so it cannot be built.");
            return;
        }
        String building = u.profileScope() ? Bank.shared(p).building : Bank.member(p).building;
        if (building != null && !building.isEmpty()) {
            Msg.error(p, "You are already building a" + (u.profileScope() ? " profile" : "n account") + " upgrade.");
            return;
        }
        double cost = coinCost(next);
        List<String> lore = List.of("&7Build &e" + u.name + " " + BankMath.roman(next.tier),
                "&7Cost: " + (cost <= 0 ? "&aFree" : "&6" + BankMath.coinWords(cost)),
                "&7Build time: &e" + (buildMillis(next) <= 0 ? "instant" : BankMath.duration(buildMillis(next))));
        Sky.menus().confirm(p, "Start Building?", lore, () -> {
            if (!Bank.ready(p)) return;
            if (owned(p, id) != cur) return;   // changed meanwhile
            String b = u.profileScope() ? Bank.shared(p).building : Bank.member(p).building;
            if (b != null && !b.isEmpty()) { Msg.error(p, "Another upgrade started building first."); return; }
            if (cost > 0 && !Sky.economy().withdraw(p, cost, "bank: community shop upgrade " + id + " " + next.tier)) {
                Msg.error(p, "You don't have enough coins in your purse.");
                return;
            }
            long doneAt = System.currentTimeMillis() + buildMillis(next);
            if (u.profileScope()) {
                BankData.Shared s = Bank.shared(p);
                s.building = id; s.buildingTier = next.tier; s.buildingDone = doneAt;
            } else {
                BankData.Member d = Bank.member(p);
                d.building = id; d.buildingTier = next.tier; d.buildingDone = doneAt;
            }
            long fame = cost > 0 && Bank.accountReady(p) ? fameForGems(next.gems) : 0;
            if (fame > 0) addFame(p, fame);   // research 6.3: 200 Fame per gem; we charge coins per gem
            Sky.profiles().saveNow(p);
            Msg.success(p, "Started building &e" + u.name + " " + BankMath.roman(next.tier) + "&a!"
                    + (fame > 0 ? " &e+" + BankMath.commas(fame) + " Fame" : ""));
            checkUpgrades(p);
            upgrades(p).open(p);
        }, () -> upgrades(p).open(p));
    }

    /** Finish any upgrade whose build time is over; pay the daily Coins Allowance. */
    static void checkUpgrades(Player p) {
        if (!Bank.accountReady(p)) return;   // also writes the account-wide upgrades
        long now = System.currentTimeMillis();
        BankData.Shared s = Bank.shared(p);
        if (s.building != null && !s.building.isEmpty() && now >= s.buildingDone) {
            String id = s.building;
            int t = s.buildingTier;
            s.upgrades.merge(id, t, Math::max);
            s.building = "";
            finished(p, id, t);
        }
        BankData.Member d = Bank.member(p);
        if (d.building != null && !d.building.isEmpty() && now >= d.buildingDone) {
            String id = d.building;
            int t = d.buildingTier;
            d.upgrades.merge(id, t, Math::max);
            d.building = "";
            finished(p, id, t);
        }
        int allowanceTier = owned(p, "coins_allowance");
        if (allowanceTier > 0) {
            String today = Bank.today();
            if (!today.equals(s.allowanceDay)) {
                s.allowanceDay = today;
                Sky.profiles().saveNow(p);
                double coins = value(p, "coins_allowance");
                if (coins > 0) {
                    Sky.economy().deposit(p, coins, "bank: daily coins allowance");
                    Msg.send(p, "&6[Community Shop] &7Your daily &6Coins Allowance &7paid &6" + BankMath.commas(coins) + " coins&7!");
                }
            }
        }
    }

    private static void finished(Player p, String id, int tier) {
        BankCatalog.Upgrade u = Bank.catalog.upgrades.get(id);
        String name = u == null ? id : u.name;
        Msg.box(p, "&a&lUPGRADE COMPLETE &e" + name + " " + BankMath.roman(tier),
                u == null ? List.of() : List.of("&7Now: &a" + fmt(u.tier(tier).value) + " &7" + u.unit));
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.3f);
        // research 02 + NEU sblevels.json (community_shop_upgrades_xp 10, community_shop_upgrades_max): Island Size 10
        // (first tier only), Minion Slots 10 each (5), Guests Limit 10 (first tier only), Coins Allowance 10 each (5)
        int xpTiers = BankMath.communityXpTiers(id);
        if (tier <= xpTiers) {
            Sky.service(Features.SkyLevel.class).award(p, "community:" + id + ":" + tier, 10, "Community Shop upgrade");
        }
        Sky.stats().invalidate(p);
        Sky.profiles().saveNow(p);
    }

    private static void stats(Player p, StatMap into) {
        if (!Bank.ready(p)) return;
        double mf = value(p, "magic_find");
        if (mf > 0) into.add(Stat.MAGIC_FIND, mf);
        double ff = value(p, "garden_farming_fortune");
        if (ff > 0 && "garden".equals(Sky.worlds().islandOf(p.getLocation()))) into.add(Stat.FARMING_FORTUNE, ff);
    }
}
