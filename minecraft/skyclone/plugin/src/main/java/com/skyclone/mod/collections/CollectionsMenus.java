package com.skyclone.mod.collections;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The three collection menus, in the genre's layout (research 08 section 11.4):
 * <pre>
 *   Collections            painting overview at 4, categories at 20-25 + Boss at 31
 *   Farming Collections    one icon per collection (gray dye "find this item" until discovered)
 *   Wheat Collection       the tier road: lime = unlocked, yellow = next, red = locked
 * </pre>
 */
final class CollectionsMenus {

    /** Inner 7x4 area of a 6-row chest. */
    private static final java.util.regex.Pattern MINION_TIER = java.util.regex.Pattern.compile("^(.+_GENERATOR)_\\d+$");

    static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    private final CollectionsModule mod;

    CollectionsMenus(CollectionsModule mod) { this.mod = mod; }

    private CollectionsService svc() { return mod.service(); }

    private CollectionRewards rw() { return mod.rewards(); }

    // ---- main ------------------------------------------------------------------------------

    void openMain(Player p) {
        if (!ready(p)) return;
        buildMain(p).open(p);
    }

    Menu buildMain(Player p) {
        Menu m = new Menu("Collections", 6).chrome();
        CollectionCatalog cat = mod.catalog();

        int discovered = 0, maxed = 0, total = 0, tiers = 0;
        for (CollectionCatalog.Coll c : cat.all()) {
            if (c.isBoss()) continue;
            total++;
            long n = svc().total(p, c);
            int t = CollectionMath.tierFor(c, n);
            if (n > 0) discovered++;
            if (t >= c.maxTier()) maxed++;
            tiers += t;
        }
        int allTiers = Math.max(1, cat.resourceTiers());
        double unlocked = total == 0 ? 0 : discovered / (double) total;
        // "Collections Unlocked" counts COLLECTIONS (x/90), exactly as each category icon and the SkyBlock
        // Menu do; tiers are their own line. (PLAYTEST: the header said 0/790 tiers while the Mining page
        // said 1 collection unlocked - one label, two meanings, one click apart.)
        List<String> lore = new ArrayList<>(List.of(
                "&7Everything you gather, fish, chop or",
                "&7loot counts toward its collection.",
                "&7Climb the tiers to unlock recipes,",
                "&7perks and SkyBlock XP!",
                "",
                "&7Collections Unlocked: &e" + pct(unlocked * 100) + "&6%",
                Text.bar(unlocked, 20) + " &e" + discovered + "&6/&e" + total,
                "",
                "&7Collection Tiers: &e" + tiers + "&6/&e" + allTiers + " &8(" + pct(100.0 * tiers / allTiers) + "%)",
                "&7Collections Maxed: &e" + maxed + "&6/&e" + total,
                "&7Boss Collections: &e" + bossDiscovered(p) + "&6/&e" + cat.inCategory("BOSS").size()));
        m.set(4, Icons.of(Material.PAINTING, "&aCollections", lore));

        for (CollectionCatalog.Category c : cat.categories()) {
            m.set(c.slot, categoryIcon(p, c), click -> openCategory(click.player(), c.id));
        }

        if (Sky.has(Features.SkyMenu.class)) {
            m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        }
        m.set(50, Icons.of(Material.FURNACE, "&aCrafted Minions",
                "&7View every minion tier you have",
                "&7crafted and what it unlocked.",
                "",
                "&eClick to view!"), c -> {
            Player pl = c.player();
            if (!Sky.commands().run(pl, "craftedminions") && !Sky.commands().run(pl, "minions")) {
                Msg.error(pl, "Crafted minions are not available yet.");
            }
        });
        return m;
    }

    private ItemStack categoryIcon(Player p, CollectionCatalog.Category cat) {
        List<CollectionCatalog.Coll> list = mod.catalog().inCategory(cat.id);
        int disc = 0, maxed = 0, tiers = 0, allTiers = 0;
        for (CollectionCatalog.Coll c : list) {
            long n = svc().total(p, c);
            int t = CollectionMath.tierFor(c, n);
            if (n > 0) disc++;
            if (t >= c.maxTier()) maxed++;
            tiers += t;
            allTiers += c.maxTier();
        }
        boolean boss = "BOSS".equals(cat.id);
        List<String> lore = new ArrayList<>();
        if (boss) {
            lore.add("&7Defeat the great dungeon and");
            lore.add("&7volcano bosses to unlock their");
            lore.add("&7reward chest prizes!");
        } else {
            lore.add("&7View your " + cat.name + " Collections!");
        }
        lore.add("");
        double frac = list.isEmpty() ? 0 : disc / (double) list.size();
        lore.add("&7Collections Unlocked: &e" + pct(frac * 100) + "&6%");
        lore.add(Text.bar(frac, 20) + " &e" + disc + "&6/&e" + list.size());
        lore.add("");
        lore.add("&7Collection Tiers: &e" + tiers + "&6/&e" + allTiers);
        lore.add("&7Collections Maxed: &e" + maxed + "&6/&e" + list.size());
        lore.add("");
        lore.add("&eClick to view!");
        Material mat = mat(cat.icon, Material.CHEST);
        return Icons.of(mat, "&a" + cat.name + " Collections", lore);
    }

    // ---- category ------------------------------------------------------------------------------

    void openCategory(Player p, String categoryId) {
        if (!ready(p)) return;
        CollectionCatalog.Category cat = mod.catalog().category(categoryId);
        if (cat == null) { openMain(p); return; }
        buildCategory(p, cat).open(p);
    }

    Menu buildCategory(Player p, CollectionCatalog.Category cat) {
        Menu m = new Menu(cat.name + " Collections", 6).chrome();
        m.set(4, categoryIcon(p, cat));
        List<CollectionCatalog.Coll> list = mod.catalog().inCategory(cat.id);
        for (int i = 0; i < list.size() && i < GRID.length; i++) {
            CollectionCatalog.Coll c = list.get(i);
            long n = svc().total(p, c);
            if (n <= 0 && !mod.previewUndiscovered()) {
                m.set(GRID[i], Icons.of(Material.GRAY_DYE, "&c" + rw().collName(c),
                        "&7Find this item to add it to your",
                        "&7collection and unlock collection",
                        "&7rewards!"));
            } else {
                m.set(GRID[i], collectionIcon(p, c, true), click -> openCollection(click.player(), c.id));
            }
        }
        m.back("Collections", () -> buildMain(p));
        return m;
    }

    /** The genre's collection icon: tier, progress to next, co-op shares, next tier rewards. */
    ItemStack collectionIcon(Player p, CollectionCatalog.Coll c, boolean clickHint) {
        long n = svc().total(p, c);
        int tier = CollectionMath.tierFor(c, n);
        String name = rw().collName(c);
        List<String> lore = new ArrayList<>();
        lore.add("&7View all your " + name + " Collection");
        lore.add("&7progress and rewards!");
        lore.add("");
        CollectionCatalog.Tier next = c.tier(tier + 1);
        if (next != null) {
            double f = CollectionMath.progressTo(next, n);
            lore.add("&7Progress to " + name + " " + mod.tierText(p, next.tier) + ": &e" + pct(f * 100) + "&6%");
            lore.add(Text.bar(f, 20) + " &e" + CollectionsService.num(n) + "&6/&e" + Text.shortNum(next.amount));
        } else {
            lore.add("&7Total Collected: &e" + CollectionsService.num(n));
            lore.add("&6&lMAXED OUT!");
        }
        String lb = leaderboardLine(p, c);
        if (lb != null) lore.add(lb);
        List<Map.Entry<String, Long>> con = svc().contributions(p, c);
        if (con.size() > 1) {
            lore.add("");
            lore.add("&7Co-op Contributions:");
            int k = 0;
            for (var e : con) {
                if (k++ >= 5) { lore.add(" &8...and " + (con.size() - 5) + " more"); break; }
                lore.add(" &b" + e.getKey() + "&7: &e" + CollectionsService.num(e.getValue()));
            }
        }
        if (next != null) {
            lore.add("");
            lore.add("&7" + name + " " + mod.tierText(p, next.tier) + " Rewards:");
            for (String l : rw().lines(c, next, 8)) lore.add("  " + l);
        }
        if (clickHint) { lore.add(""); lore.add("&eClick to view!"); }
        String title = "&e" + name + (tier > 0 ? " " + mod.tierText(p, tier) : "");
        return icon(c, title, lore);
    }

    // ---- tier road -----------------------------------------------------------------------------

    void openCollection(Player p, String collectionId) {
        if (!ready(p)) return;
        CollectionCatalog.Coll c = mod.catalog().resolve(collectionId);
        if (c == null) { openMain(p); return; }
        buildCollection(p, c).open(p);
    }

    Menu buildCollection(Player p, CollectionCatalog.Coll c) {
        String name = rw().collName(c);
        Menu m = new Menu(name + " Collection", 6).chrome();
        long n = svc().total(p, c);
        int tier = CollectionMath.tierFor(c, n);

        // summary icon
        List<String> lore = new ArrayList<>();
        lore.add("&7View all your " + name + " Collection");
        lore.add("&7progress and rewards!");
        lore.add("");
        lore.add("&7Total Collected: &e" + CollectionsService.num(n));
        lore.add("&7Tier: &e" + (tier == 0 ? "None" : mod.tierText(p, tier)) + "&6/&e" + mod.tierText(p, c.maxTier()));
        String lb = leaderboardLine(p, c);
        if (lb != null) lore.add(lb);
        List<Map.Entry<String, Long>> con = svc().contributions(p, c);
        if (con.size() > 1) {
            lore.add("");
            lore.add("&7Co-op Contributions:");
            for (var e : con) lore.add(" &b" + e.getKey() + "&7: &e" + CollectionsService.num(e.getValue()));
        }
        m.set(4, icon(c, "&e" + name + (tier > 0 ? " " + mod.tierText(p, tier) : ""), lore));

        boolean roman = CollectionsModule.roman(p);
        int[] slots = roadSlots(c.maxTier());
        for (int i = 0; i < c.tiers.size() && i < slots.length; i++) {
            m.set(slots[i], tierIcon(c, c.tiers.get(i), n, tier, roman));
        }

        m.back(mod.catalog().category(c.category) == null ? "Collections" : mod.catalog().category(c.category).name + " Collections",
                () -> {
                    CollectionCatalog.Category cat = mod.catalog().category(c.category);
                    return cat == null ? buildMain(p) : buildCategory(p, cat);
                });
        if (!c.isBoss()) {
            List<Unlock> unlocks = unlocks(c);
            int open = 0;
            for (Unlock u : unlocks) if (u.tier() <= tier) open++;
            String bookItem = registeredItem(c);
            List<String> bookLore = new ArrayList<>(List.of(
                    "&7Every recipe this collection",
                    "&7unlocks, tier by tier.",
                    "",
                    "&7Unlocked: &e" + open + "&6/&e" + unlocks.size(),
                    ""));
            bookLore.add(unlocks.isEmpty() ? "&8Nothing to craft here." : "&eLeft-click to view!");
            if (bookItem != null) bookLore.add("&bRight-click &7for recipes using " + name + "!");
            m.set(50, Icons.of(Material.BOOK, "&aCollection Recipes", bookLore), click -> {
                Player pl = click.player();
                if (click.right() && bookItem != null) {
                    if (!Sky.commands().run(pl, "recipe", bookItem)) Msg.error(pl, "The recipe book is not available yet.");
                    return;
                }
                if (unlocks.isEmpty()) { Msg.error(pl, name + " does not unlock any recipes."); return; }
                openUnlocks(pl, c, 0);
            });
            if (mod.leaderboard().available() && mod.leaderboardOn()) m.set(51, leaderboardIcon(p, c), click -> {
                Player pl = click.player();
                boolean every = click.right();
                // Close on the next tick: closing inside the click event is unsafe on Paper.
                org.bukkit.Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                    pl.closeInventory();
                    if (every) chatAllBoards(pl, c); else chatTop(pl, c, mod.leaderboard().boardFor(pl));
                });
            });
        }
        return m;
    }

    // ---- collection recipes --------------------------------------------------------------------

    /** One craftable thing a collection tier unlocks (a registered item id). */
    record Unlock(String itemId, int tier, boolean forge) {}

    /**
     * Every registered item this collection unlocks, in tier order: the data's recipe rewards plus any
     * recipe another module gated on {@code collection:<ID>:<tier>}. Minion tiers collapse to tier I.
     */
    List<Unlock> unlocks(CollectionCatalog.Coll c) {
        List<Unlock> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<String> minions = new java.util.HashSet<>();
        for (CollectionCatalog.Tier t : c.tiers) {
            for (CollectionCatalog.Reward r : t.rewards) {
                if ("recipe".equals(r.type) && r.item != null && CollectionRewards.itemName(r.item) != null && seen.add(r.item)) {
                    out.add(new Unlock(r.item, t.tier, r.forge));
                } else if ("minion".equals(r.type) && r.prefix != null && minions.add(r.prefix)) {
                    String first = r.prefix + "_1";
                    if (CollectionRewards.itemName(first) != null && seen.add(first)) out.add(new Unlock(first, t.tier, false));
                }
            }
            for (String res : rw().registeredUnlocks(c, t.tier)) {
                var mm = MINION_TIER.matcher(res);
                if (mm.matches()) {
                    if (!minions.add(mm.group(1))) continue;
                    res = mm.group(1) + "_1";
                }
                if (CollectionRewards.itemName(res) != null && seen.add(res)) out.add(new Unlock(res, t.tier, false));
            }
        }
        return out;
    }

    /** The registered item that stands for this collection (its id, else a weight-1 alias), or null. */
    String registeredItem(CollectionCatalog.Coll c) {
        if (CollectionRewards.itemName(c.id) != null) return c.id;
        for (var e : c.aliases.entrySet()) {
            if (e.getValue() != null && e.getValue() == 1 && CollectionRewards.itemName(e.getKey()) != null) return e.getKey();
        }
        return null;
    }

    void openUnlocks(Player p, CollectionCatalog.Coll c, int page) {
        if (!ready(p)) return;
        String name = rw().collName(c);
        int tier = svc().tierOf(p, c);
        List<Unlock> list = unlocks(c);
        boolean roman = CollectionsModule.roman(p);
        Sky.menus().paged(name + " Recipes", list, u -> unlockIcon(u, name, tier, roman), pc -> {
            Player pl = pc.click().player();
            if (pc.entry().tier() > tier) {
                Msg.error(pl, "Requires " + name + " Collection " + mod.tierText(pl, pc.entry().tier()) + ".");
                return;
            }
            if (!Sky.commands().run(pl, "recipe", pc.entry().itemId())) Msg.error(pl, "The recipe book is not available yet.");
        }, menu -> menu.back(name + " Collection", () -> buildCollection(p, c)), page).open(p);
    }

    private ItemStack unlockIcon(Unlock u, String collName, int tier, boolean roman) {
        boolean open = u.tier() <= tier;
        List<String> extra = new ArrayList<>();
        extra.add("");
        extra.add("&7Unlocked at: &e" + collName + " " + (roman ? CollectionMath.roman(u.tier()) : String.valueOf(u.tier())));
        if (u.forge()) extra.add("&7Made at: &6the Forge");
        extra.add("");
        extra.add(open ? "&aUNLOCKED" : "&cLOCKED");
        if (open) extra.add("&eClick to view recipe!");
        ItemStack base;
        try { base = Sky.items().create(u.itemId(), 1); } catch (RuntimeException e) { base = null; }
        if (base == null || !open) {
            String n = CollectionRewards.itemName(u.itemId());
            List<String> lore = new ArrayList<>(extra);
            return Icons.of(open ? Material.PAPER : Material.GRAY_DYE, (open ? "&a" : "&c") + (n == null ? u.itemId() : n), lore);
        }
        return Icons.withLore(base, extra);
    }

    // ---- leaderboard ---------------------------------------------------------------------------

    /** "Leaderboard" or "Ironman Leaderboard": Classic profiles read the plain word. */
    private static String boardTitle(String board) {
        return "normal".equals(board) ? "Leaderboard" : CollectionsLeaderboard.label(board) + " Leaderboard";
    }

    /** "Leaderboard: #12 (Top 4%)" once past the minimum; null below it or with leaderboards off. */
    String leaderboardLine(Player p, CollectionCatalog.Coll c) {
        if (c.isBoss() || !mod.leaderboardOn()) return null;
        CollectionsLeaderboard.Position pos = mod.leaderboard().position(p, c);
        if (pos == null) return null;
        return "&7" + boardTitle(mod.leaderboard().boardFor(p)) + ": &e#" + Text.commasInt(pos.rank())
                + " &7(Top &e" + pos.topPercent() + "%&7)";
    }

    private ItemStack leaderboardIcon(Player p, CollectionCatalog.Coll c) {
        String name = rw().collName(c);
        String board = mod.leaderboard().boardFor(p);
        List<String> lore = new ArrayList<>();
        lore.add("&7The top " + name + " collectors on");
        lore.add("&7the server, one entry per profile.");
        if (mod.leaderboardSplitModes()) lore.add("&7Board: &e" + CollectionsLeaderboard.label(board) + " &7profiles");
        lore.add("");
        List<CollectionsLeaderboard.Row> top = mod.leaderboard().top(c, board, 10);
        if (top.isEmpty()) {
            lore.add("&8Nobody has collected " + Text.commasInt(mod.leaderboardMin()));
            lore.add("&8yet. Be the first!");
        } else {
            int i = 1;
            for (CollectionsLeaderboard.Row r : top) {
                lore.add(rankColour(i) + "#" + i + " &f" + r.names() + " &8- &e" + Text.commasInt(r.total()));
                i++;
            }
        }
        lore.add("");
        CollectionsLeaderboard.Position pos = mod.leaderboard().position(p, c);
        if (pos != null) {
            lore.add("&7Your position: &e#" + Text.commasInt(pos.rank()) + "&7 of &e" + Text.commasInt(pos.size())
                    + " &7(Top &e" + pos.topPercent() + "%&7)");
        } else {
            long n = svc().total(p, c);
            long need = Math.max(0, mod.leaderboardMin() - n);
            lore.add("&7Collect &e" + Text.commasInt(need) + " &7more to join");
            lore.add("&7the leaderboard.");
        }
        lore.add("");
        lore.add("&eClick to show in chat!");
        if (mod.leaderboardSplitModes()) lore.add("&bRight-click &7for every profile mode's board!");
        String suffix = "normal".equals(board) || !mod.leaderboardSplitModes() ? "" : " &7(" + CollectionsLeaderboard.label(board) + ")";
        return Icons.of(Material.GOLD_BLOCK, "&6" + name + " Leaderboard" + suffix, lore);
    }

    private static String rankColour(int rank) {
        return switch (rank) { case 1 -> "&6&l"; case 2 -> "&f&l"; case 3 -> "&c&l"; default -> "&7"; };
    }

    /**
     * {@code /collection top <item> [classic|ironman|stranded|bingo]}: a board in chat (your own profile's
     * board by default).
     */
    void chatTop(Player p, String[] args) {
        if (!ready(p)) return;
        if (args.length < 2) { Msg.error(p, "Usage: /collection top <item> [classic|ironman|stranded|bingo]"); return; }
        String board = null;
        int end = args.length;
        if (args.length > 2) {
            board = CollectionsLeaderboard.parseBoard(args[args.length - 1]);
            if (board != null) end--;
        }
        String arg = String.join("_", java.util.Arrays.copyOfRange(args, 1, end));
        CollectionCatalog.Coll c = arg.length() > 48 ? null : mod.catalog().resolve(arg);
        if (c == null || c.isBoss()) { Msg.error(p, "Unknown collection: " + arg.replace('_', ' ')); return; }
        if (board == null || !mod.leaderboardSplitModes()) board = mod.leaderboard().boardFor(p);
        chatTop(p, c, board);
    }

    void chatTop(Player p, CollectionCatalog.Coll c, String board) {
        if (!mod.leaderboard().available() || !mod.leaderboardOn()) { Msg.error(p, "Collection leaderboards are turned off."); return; }
        String name = rw().collName(c);
        String mine = mod.leaderboard().boardFor(p);
        String tag = mod.leaderboardSplitModes() ? " &7[" + CollectionsLeaderboard.label(board) + "]" : "";
        Msg.send(p, "&6&l" + name + " Leaderboard" + tag + " &8(" + Text.commasInt(mod.leaderboard().size(c, board)) + " profiles)");
        List<CollectionsLeaderboard.Row> top = mod.leaderboard().top(c, board, 10);
        if (top.isEmpty()) Msg.send(p, " &8Nobody has collected " + Text.commasInt(mod.leaderboardMin()) + " " + name + " yet.");
        int i = 1;
        for (CollectionsLeaderboard.Row r : top) {
            Msg.send(p, " " + rankColour(i) + "#" + i + " &f" + r.names() + " &8- &e" + Text.commasInt(r.total()));
            i++;
        }
        if (!board.equals(mine)) {
            Msg.send(p, " &8Your profile ranks on the " + CollectionsLeaderboard.label(mine) + " board.");
            return;
        }
        CollectionsLeaderboard.Position pos = mod.leaderboard().position(p, c);
        if (pos != null) Msg.send(p, " &7You: &e#" + Text.commasInt(pos.rank()) + " &7(Top &e" + pos.topPercent() + "%&7)");
        else Msg.send(p, " &7You need &e" + Text.commasInt(Math.max(0, mod.leaderboardMin() - svc().total(p, c)))
                + " &7more " + name + " to join.");
    }

    /** Right-click on the leaderboard icon: the top 3 of every profile mode's board. */
    void chatAllBoards(Player p, CollectionCatalog.Coll c) {
        if (!mod.leaderboard().available() || !mod.leaderboardOn()) { Msg.error(p, "Collection leaderboards are turned off."); return; }
        if (!mod.leaderboardSplitModes()) { chatTop(p, c, "normal"); return; }
        String name = rw().collName(c);
        Msg.send(p, "&6&l" + name + " Leaderboards");
        for (String board : CollectionsLeaderboard.BOARDS) {
            int size = mod.leaderboard().size(c, board);
            Msg.send(p, " &e" + CollectionsLeaderboard.label(board) + " &8(" + Text.commasInt(size) + " profiles)");
            int i = 1;
            for (CollectionsLeaderboard.Row r : mod.leaderboard().top(c, board, 3)) {
                Msg.send(p, "   " + rankColour(i) + "#" + i + " &f" + r.names() + " &8- &e" + Text.commasInt(r.total()));
                i++;
            }
        }
        Msg.send(p, " &7Full board: &e/collection top " + c.id.toLowerCase(java.util.Locale.ROOT) + " <mode>");
    }

    private int bossDiscovered(Player p) {
        int n = 0;
        for (CollectionCatalog.Coll c : mod.catalog().inCategory("BOSS")) if (svc().total(p, c) > 0) n++;
        return n;
    }

    private ItemStack tierIcon(CollectionCatalog.Coll c, CollectionCatalog.Tier t, long total, int cur, boolean roman) {
        String name = rw().collName(c) + " " + (roman ? CollectionMath.roman(t.tier) : String.valueOf(t.tier));
        Material pane;
        String colour;
        if (t.tier <= cur) { pane = Material.LIME_STAINED_GLASS_PANE; colour = "&a"; }
        else if (t.tier == cur + 1) { pane = Material.YELLOW_STAINED_GLASS_PANE; colour = "&e"; }
        else { pane = Material.RED_STAINED_GLASS_PANE; colour = "&c"; }
        List<String> lore = new ArrayList<>();
        lore.add("");
        double f = CollectionMath.progressTo(t, total);
        lore.add("&7Progress: &e" + pct(f * 100) + "&6%");
        lore.add(Text.bar(f, 20) + " &e" + CollectionsService.num(Math.min(total, t.amount)) + "&6/&e" + CollectionsService.num(t.amount));
        lore.add("");
        lore.add("&7Rewards:");
        for (String l : rw().lines(c, t, 12)) lore.add("  " + l);
        lore.add("");
        lore.add(t.tier <= cur ? "&aUNLOCKED" : "&cLOCKED");
        return Icons.amount(Icons.of(pane, colour + name, lore), t.tier);
    }

    static int[] roadSlots(int n) { return CollectionsMenusLayout.road(n); }

    // ---- helpers -------------------------------------------------------------------------------

    private ItemStack icon(CollectionCatalog.Coll c, String name, List<String> lore) {
        if (c.skull != null && !c.skull.isEmpty() && "PLAYER_HEAD".equals(c.icon)) return Icons.skull(c.skull, name, lore);
        return Icons.of(mat(c.icon, Material.PAPER), name, lore);
    }

    static Material mat(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name);
        return m == null || !m.isItem() || m.isAir() ? fallback : m;
    }

    private static String pct(double v) {
        if (v >= 100) return "100";
        return String.valueOf(Math.floor(v * 10) / 10.0).replaceAll("\\.0$", "");
    }

    private static boolean ready(Player p) {
        if (Sky.profiles().isReady(p)) return true;
        Msg.error(p, "Your profile is still loading, try again in a moment.");
        return false;
    }
}
