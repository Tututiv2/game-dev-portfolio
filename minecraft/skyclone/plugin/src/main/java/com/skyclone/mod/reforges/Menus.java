package com.skyclone.mod.reforges;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.MenuClick;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Every reforge-module GUI. Each {@code build*} returns a fresh Menu for one viewer. */
final class Menus {

    private Menus() {}

    static final String HUB = "Reforging";

    // ---- shared bits ------------------------------------------------------------------------------

    static ItemStack iconOf(ItemDef d, String name, List<String> lore) {
        if (d != null && d.skull != null && !d.skull.isEmpty()) return Icons.skull(d.skull, name, lore);
        Material m = d == null ? null : Material.matchMaterial(d.material == null ? "" : d.material);
        if (m == null || !m.isItem() || m.isAir()) m = Material.PAPER;
        return Icons.of(m, name, lore);
    }

    /** A display-only copy of an item AFTER {@code change}: same name and lore, no sky id, no uuid. */
    static ItemStack preview(ItemStack item, Consumer<ItemData> change, List<String> extra) {
        ItemStack clone = item.clone();
        if (change != null) Sky.items().update(clone, change);
        ItemStack out = new ItemStack(clone.getType());
        ItemMeta src = clone.getItemMeta();
        ItemMeta dst = out.getItemMeta();
        if (src != null && dst != null) {
            dst.displayName(src.displayName());
            List<Component> lore = src.lore() == null ? new ArrayList<>() : new ArrayList<>(src.lore());
            lore.addAll(Text.lines(extra));
            dst.lore(lore);
            if (src instanceof SkullMeta s && dst instanceof SkullMeta d && s.getPlayerProfile() != null) d.setPlayerProfile(s.getPlayerProfile());
            Icons.hide(dst);
            out.setItemMeta(dst);
        }
        return out;
    }

    private static void frame(Menu m, Player p, boolean ready, int... slots) {
        Material pane = ready ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        for (int s : slots) m.set(s, Icons.of(pane, " "));
    }

    private static void backToHub(Menu m, Player p) {
        m.set(48, Icons.back(HUB), c -> hub(c.player()));
    }

    private static boolean ready(Player p) {
        if (Sky.profiles().isReady(p)) return true;
        Msg.error(p, "Your profile is still loading.");
        return false;
    }

    // ---- hub ------------------------------------------------------------------------------------

    static void hub(Player p) { buildHub(p).open(p); }

    static Menu buildHub(Player p) {
        Menu m = new Menu(HUB, 6).chrome();
        m.set(4, Icons.of(Material.ANVIL, "&aReforging",
                "&7Every way to make a piece of gear", "&7stronger, in one place."));
        m.set(20, Icons.of(Material.ANVIL, "&aBlacksmith",
                "&7Roll a random reforge onto a weapon,", "&7tool or armor piece for coins.", "", "&8/reforge", "", "&eClick to open!"),
                c -> blacksmith(c.player()));
        m.set(21, Icons.of(Material.CHIPPED_ANVIL, "&aReforge Anvil",
                "&7Apply reforge stones, potato tomes,", "&7the Rarity Reshaper, master stars,", "&7Art of War/Peace and enrichments.", "", "&8/reforgeanvil", "", "&eClick to open!"),
                c -> anvil(c.player()));
        m.set(22, Icons.of(Material.NETHER_STAR, "&aEssence & Stars",
                "&7Spend essence to add &6✪ stars&7.", "&7Each star raises the item's base", "&7stats by &a2%&7.", "", "&8/essence", "", "&eClick to open!"),
                c -> essence(c.player()));
        m.set(23, Icons.of(Material.AMETHYST_SHARD, "&aGemstone Grinder",
                "&7Unlock gemstone slots and set gems", "&7into your gear.", "", "&8/gemstones", "", "&eClick to open!"),
                c -> gems(c.player()));
        m.set(24, Icons.of(Material.BOOK, "&aReforge Guide",
                "&7Every reforge, what it fits, its", "&7stats at each rarity and its bonus.", "", "&8/reforgeguide", "", "&eClick to browse!"),
                c -> guide(c.player(), 0, 0));
        if (ReforgesModule.shopEnabled.get()) {
            m.set(31, Icons.of(Material.CHEST, "&aStone Merchant",
                    "&7Buy reforge stones for coins.", "", "&8/reforgeshop", "", "&eClick to browse!"), c -> shop(c.player(), 0));
        }
        if (Sky.has(Features.SkyMenu.class)) {
            m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        }
        return m;
    }

    // ---- blacksmith ("Reforge Item") -----------------------------------------------------------------

    static final int ITEM = 13, BUTTON = 22;

    static void blacksmith(Player p) { if (ready(p)) buildBlacksmith(p).open(p); }

    static Menu buildBlacksmith(Player p) {
        Menu m = new Menu("Reforge Item", 6);
        m.free(ITEM).chrome();
        m.onFreeSlotChange(c -> c.menu().redraw());
        m.drawer(mm -> drawBlacksmith(mm, p));
        return m;
    }

    private static void drawBlacksmith(Menu m, Player p) {
        ItemStack it = m.get(ITEM);
        ItemDef def = Gear.def(it);
        List<ReforgeData.Row> pool = def == null ? List.of() : ReforgesModule.DATA.blacksmithPool(def);
        boolean ok = def != null && def.isUnique() && !pool.isEmpty() && !def.has("no_reforge");
        frame(m, p, ok, 3, 4, 5, 12, 14, 21, 23);
        m.set(40, Icons.filler());
        if (Gear.empty(it)) {
            m.set(BUTTON, Icons.of(Material.ANVIL, "&aReforge Item",
                    "&7Place a weapon, tool, armor piece", "&7or equipment piece above to give it", "&7a random reforge from its category.",
                    "", "&7The cost depends on its rarity."));
        } else if (!ok) {
            m.set(BUTTON, Icons.of(Material.BARRIER, "&cCannot Reforge", "&7The Blacksmith cannot reforge this", "&7item. Some items only take",
                    "&7reforge stones (Reforge Anvil)."));
        } else {
            Rarity r = Sky.items().rarity(it);
            ItemData d = Sky.items().data(it);
            ReforgeData.Row cur = d.reforge == null ? null : ReforgesModule.DATA.rows.get(d.reforge);
            List<String> lore = new ArrayList<>(List.of("&7Reforges the item above with a", "&7random reforge from its pool.", "",
                    "&7Current: " + (cur == null ? "&8None" : "&9" + cur.name), "&7Possible: &9" + pool.size() + " reforges", "", "&7Cost"));
            lore.add(costLine(p, r));
            lore.add("");
            lore.add("&eClick to reforge!");
            m.set(BUTTON, Icons.of(Material.ANVIL, "&aReforge Item", lore), c -> doBlacksmith(c));
            List<String> poolLore = new ArrayList<>(List.of("&7At " + r.code() + r.display() + "&7 this item could become:", ""));
            for (ReforgeData.Row row : pool) poolLore.add("&9" + row.name + " &8" + compactStats(ReforgeData.statsFor(row, r)));
            m.set(40, Icons.of(Material.BOOK, "&aPossible Reforges", poolLore));
        }
        backToHub(m, p);
        m.set(50, Icons.of(Material.CHIPPED_ANVIL, "&aReforge Anvil", "&7For reforge stones and upgrades.", "", "&eClick to open!"),
                c -> anvil(c.player()));
        if (ReforgesModule.shopEnabled.get())
            m.set(51, Icons.of(Material.CHEST, "&aStone Merchant", "&7Buy reforge stones.", "", "&eClick to browse!"), c -> shop(c.player(), 0));
    }

    private static boolean coalReforge(Player p) {
        return ReforgesModule.firstReforgeCoal.get() && Sky.profiles().isReady(p) && !ReforgesModule.data(p).firstReforgeDone;
    }

    private static String costLine(Player p, Rarity r) {
        if (coalReforge(p)) return "&f" + ReforgesModule.firstReforgeCoalAmount.get() + "x Coal &8(your first reforge!)";
        return Gear.coins(ReforgeData.blacksmithCost(r) * ReforgesModule.blacksmithCostMult.get());
    }

    private static void doBlacksmith(MenuClick c) {
        Player p = c.player();
        Menu m = c.menu();
        ItemStack it = m.get(ITEM);
        ItemDef def = Gear.def(it);
        if (def == null || !def.isUnique() || def.has("no_reforge")) { Msg.error(p, "Place an item to reforge first."); return; }
        List<ReforgeData.Row> pool = ReforgesModule.DATA.blacksmithPool(def);
        if (pool.isEmpty()) { Msg.error(p, "The Blacksmith cannot reforge that."); return; }
        ItemData d = Sky.items().data(it);
        Rarity r = Sky.items().rarity(it);
        List<ReforgeData.Row> choices = new ArrayList<>();
        for (ReforgeData.Row row : pool) if (!row.id.equals(d.reforge)) choices.add(row);
        if (choices.isEmpty()) choices = pool;
        boolean coal = coalReforge(p);
        if (coal) {
            int need = ReforgesModule.firstReforgeCoalAmount.get();
            if (!Gear.take(p, "COAL", need)) { Msg.error(p, "Your first reforge costs " + need + " Coal - bring some!"); return; }
        } else {
            double cost = ReforgeData.blacksmithCost(r) * ReforgesModule.blacksmithCostMult.get();
            if (!Sky.economy().withdraw(p, cost, "reforges: blacksmith reforge " + def.id + " (" + r.name() + ")")) {
                Msg.error(p, "You don't have enough coins!");
                return;
            }
        }
        ReforgeData.Row pick = choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
        String oldName = Gear.name(it);
        Sky.items().update(it, x -> x.reforge = pick.id);
        m.getInventory().setItem(ITEM, it);
        ReforgesModule.PData pd = ReforgesModule.data(p);
        pd.firstReforgeDone = true;
        pd.reforges++;
        String newName = Gear.name(it);
        String bare = Text.strip(newName == null ? "" : newName).trim().toLowerCase(Locale.ROOT);
        String article = !bare.isEmpty() && "aeiou".indexOf(bare.charAt(0)) >= 0 ? "an " : "a ";
        Msg.send(p, "&aYou reforged your " + oldName + " &ainto " + article + newName + "&a!");
        Msg.sound(p, Sound.BLOCK_ANVIL_USE, 1f);
        m.redraw();
    }

    static String compactStats(Map<String, Double> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : m.entrySet()) {
            double v = e.getValue();
            String sym, col;
            boolean pct = false;
            if ("DAMAGE".equals(e.getKey())) { sym = "Dmg"; col = "c"; }
            else {
                Stat s = Stat.parse(e.getKey());
                if (s == null) continue;
                sym = s.symbol; col = String.valueOf(s.color); pct = s.percent;
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append('&').append(col).append(v >= 0 ? "+" : "").append(Text.commas(v)).append(pct ? "%" : "").append(sym);
        }
        return sb.toString();
    }

    // ---- reforge anvil ------------------------------------------------------------------------------

    static final int A_ITEM = 29, A_MOD = 33, A_OUT = 13, A_BUTTON = 22;

    static void anvil(Player p) { if (ready(p)) buildAnvil(p).open(p); }

    static Menu buildAnvil(Player p) {
        Menu m = new Menu("Reforge Anvil", 6);
        m.free(A_ITEM, A_MOD).chrome();
        m.onFreeSlotChange(c -> c.menu().redraw());
        m.drawer(mm -> drawAnvil(mm, p));
        return m;
    }

    /** Plan for the current anvil contents, including the coin multiplier and the stone's skill gate. */
    private static ItemMods.Plan anvilPlan(Player p, Menu m) {
        ItemStack it = m.get(A_ITEM), mod = m.get(A_MOD);
        ItemDef def = Gear.def(it), md = Gear.def(mod);
        if (Gear.empty(it)) return new ItemMods.Plan("Put the item to upgrade in the left slot.", 0, null, null, null);
        if (def == null) return new ItemMods.Plan("That is not an item the anvil knows.", 0, null, null, null);
        if (Gear.empty(mod)) return new ItemMods.Plan("Put a reforge stone or an upgrade in the right slot.", 0, null, null, null);
        if (it.getAmount() != 1) return new ItemMods.Plan("Upgrade one item at a time.", 0, null, null, null);
        ItemMods.Plan plan = ReforgesModule.MODS.plan(def, Sky.items().data(it), Sky.items().rarity(it), md);
        if (!plan.ok()) return plan;
        if (plan.reforgeId() != null) {
            return new ItemMods.Plan(null, plan.coins() * ReforgesModule.stoneCostMult.get(), plan.verb(), plan.change(), plan.reforgeId());
        }
        return plan;
    }

    private static void drawAnvil(Menu m, Player p) {
        ItemStack it = m.get(A_ITEM);
        ItemMods.Plan plan = anvilPlan(p, m);
        frame(m, p, plan.ok(), 11, 12, 14, 15, 38, 42);
        m.set(A_ITEM - 9, Icons.of(Material.ORANGE_STAINED_GLASS_PANE, "&6Item to Upgrade", "&7The weapon, tool, armor or", "&7accessory goes below."));
        m.set(A_MOD - 9, Icons.of(Material.ORANGE_STAINED_GLASS_PANE, "&6Upgrade", "&7A reforge stone, potato tome,", "&7Rarity Reshaper, master star,",
                "&7book of Art, Polarvoid Book, Book", "&7of Stats, Wood Singularity,", "&7enrichment, power scroll, Silex,",
                "&7Mana Disintegrator, Transmission", "&7Tuner, Phasewarp Merger, Farmer's", "&7Almanac or Bookworm's book."));
        if (plan.ok()) {
            List<String> extra = new ArrayList<>(List.of("", "&8" + Text.strip(plan.verb())));
            if (plan.coins() > 0) extra.add("&7Cost: " + Gear.coins(plan.coins()));
            m.set(A_OUT, preview(it, plan.change(), extra));
            List<String> lore = new ArrayList<>(List.of("&7" + plan.verb() + "&7.", ""));
            if (plan.coins() > 0) { lore.add("&7Cost"); lore.add(Gear.coins(plan.coins())); lore.add(""); }
            ItemDef md = Gear.def(m.get(A_MOD));
            if (md != null && md.requirements != null && !md.requirements.isEmpty() && ReforgesModule.stoneSkillReq.get()) {
                for (String req : md.requirements) {
                    boolean has = Sky.requirements().check(p, req);
                    lore.add((has ? "&a✔ " : "&c✖ ") + "&7Requires " + Sky.requirements().describe(req));
                }
                lore.add("");
            }
            lore.add("&eClick to combine!");
            m.set(A_BUTTON, Icons.of(Material.ANVIL, "&aCombine Items", lore), Menus::doAnvil);
        } else {
            m.set(A_OUT, Icons.of(Material.BARRIER, "&cAnvil", Text.wrap("&7" + plan.error(), 34)));
            m.set(A_BUTTON, Icons.of(Material.ANVIL, "&aCombine Items", "&7Combine the item on the left", "&7with the upgrade on the right."));
        }
        backToHub(m, p);
        m.set(50, Icons.of(Material.ANVIL, "&aBlacksmith", "&7Random reforges for coins.", "", "&eClick to open!"), c -> blacksmith(c.player()));
    }

    private static void doAnvil(MenuClick c) {
        Player p = c.player();
        Menu m = c.menu();
        ItemMods.Plan plan = anvilPlan(p, m);
        if (!plan.ok()) { Msg.error(p, plan.error()); return; }
        ItemStack it = m.get(A_ITEM), mod = m.get(A_MOD);
        ItemDef md = Gear.def(mod);
        if (md != null && md.requirements != null && ReforgesModule.stoneSkillReq.get()) {
            for (String req : md.requirements) if (!Sky.requirements().checkOrTell(p, req)) return;
        }
        if (plan.coins() > 0 && !Sky.economy().withdraw(p, plan.coins(),
                "reforges: anvil " + md.id + " -> " + Sky.items().idOf(it))) {
            Msg.error(p, "You don't have enough coins!");
            return;
        }
        String before = Gear.name(it);
        Sky.items().update(it, plan.change());
        m.getInventory().setItem(A_ITEM, it);
        int left = mod.getAmount() - 1;
        if (left <= 0) m.getInventory().setItem(A_MOD, null);
        else { mod.setAmount(left); m.getInventory().setItem(A_MOD, mod); }
        if (plan.reforgeId() != null) ReforgesModule.data(p).reforges++;
        Msg.send(p, "&aYou upgraded your " + before + "&a! &7(" + plan.verb() + "&7)");
        Msg.sound(p, Sound.BLOCK_ANVIL_USE, 1.2f);
        m.redraw();
    }

    // ---- essence & stars ----------------------------------------------------------------------------

    static void essence(Player p) { if (ready(p)) buildEssence(p).open(p); }

    static Menu buildEssence(Player p) {
        Menu m = new Menu("Essence & Stars", 6);
        m.free(ITEM).chrome();
        m.onFreeSlotChange(c -> c.menu().redraw());
        m.drawer(mm -> drawEssence(mm, p));
        return m;
    }

    private static void drawEssence(Menu m, Player p) {
        ItemStack it = m.get(ITEM);
        ItemDef def = Gear.def(it);
        boolean can = false;
        if (Gear.empty(it)) {
            m.set(BUTTON, Icons.of(Material.NETHER_STAR, "&aUpgrade Item", "&7Place a dungeon or other starrable", "&7item above to add &6✪ stars&7.",
                    "", "&7Each star: &a+2% &7of the item's base", "&7stats (more inside dungeons)."));
            m.set(31, Icons.filler());
        } else {
            ItemData d = Sky.items().data(it);
            List<ReforgeData.Cost> next = def == null ? null : ReforgesModule.MODS.nextStarCost(def, d);
            int max = ReforgesModule.DATA.essenceStars(def);
            if (next != null) {
                can = Gear.canPay(p, next, 1.0) == null;
                List<String> lore = new ArrayList<>(List.of("&7Stars: " + starRow(d.stars, max), "", "&7Upgrade to " + starRow(d.stars + 1, max), "", "&7Cost"));
                lore.addAll(Gear.describe(p, next, 1.0));
                lore.add("");
                lore.add(can ? "&eClick to upgrade!" : "&cYou cannot afford this yet.");
                m.set(BUTTON, Icons.of(Material.NETHER_STAR, "&aUpgrade Item", lore), Menus::doStar);
            } else if (def != null && ReforgesModule.DATA.masterStarsAllowed(def) && d.stars >= 5 && d.stars < 10) {
                m.set(BUTTON, Icons.of(Material.NETHER_STAR, "&aFully Starred", "&7This item has every essence star.",
                        "&7Set &cmaster stars &7into it at the", "&7Reforge Anvil to keep going."));
            } else if (max > 0) {
                m.set(BUTTON, Icons.of(Material.NETHER_STAR, "&aMaxed!", "&7This item has every star it can take."));
            } else {
                m.set(BUTTON, Icons.of(Material.BARRIER, "&cCannot Upgrade", "&7This item cannot take stars."));
            }
            if (max > 0) {
                ReforgeData.StarEntry e = ReforgesModule.DATA.stars.get(def.id);
                List<String> ladder = new ArrayList<>();
                for (int i = 0; i < e.costs.size(); i++) {
                    StringBuilder sb = new StringBuilder(i < d.stars ? "&a✔ " : "&8- ").append("&6").append(i + 1).append("✪ &7");
                    boolean first = true;
                    for (ReforgeData.Cost cst : e.costs.get(i)) {
                        if (!first) sb.append("&7, ");
                        first = false;
                        if (cst.essence != null) sb.append("&d").append(cst.amount).append(' ').append(Text.titleCase(cst.essence.toLowerCase(Locale.ROOT)));
                        else if (cst.item != null) sb.append(Gear.itemName(cst.item)).append(" &8x").append(cst.amount);
                        else if (cst.coins != null) sb.append("&6").append(Text.shortNum(cst.coins));
                    }
                    ladder.add(sb.toString());
                }
                m.set(31, Icons.of(Material.LADDER, "&aStar Ladder", ladder));
            } else m.set(31, Icons.filler());
        }
        frame(m, p, can, 3, 4, 5, 12, 14, 21, 23);
        int slot = 36;
        for (String type : ReforgeData.ESSENCES) {
            ItemDef ed = Sky.items().def("ESSENCE_" + type);
            m.set(slot++, iconOf(ed, Gear.essenceName(type), List.of("&7Stored: &d" + Text.commasInt(Gear.essence(p, type)), "",
                    "&7Spent on stars. Right-click essence", "&7items to store them here.")));
        }
        backToHub(m, p);
        // the Catacombs own the essence shop and salvaging; these are doors to them (no-op when absent)
        m.set(50, Icons.of(Material.BLAZE_POWDER, "&aEssence Shop", "&7Spend essence on permanent perks.", "", "&eClick to open!"),
                c -> { if (!Sky.commands().run(c.player(), "dungeons", "essence")) Msg.error(c.player(), "The essence shop is not open yet."); });
        m.set(51, Icons.of(Material.GRINDSTONE, "&aSalvage Items", "&7Break dungeon gear down into", "&7essence.", "", "&eClick to open!"),
                c -> { if (!Sky.commands().run(c.player(), "dungeons", "salvage")) Msg.error(c.player(), "Salvaging is not open yet."); });
    }

    static String starRow(int stars, int max) {
        int gold = Math.min(stars, Math.max(5, Math.min(max, 15)));
        StringBuilder sb = new StringBuilder("&6");
        sb.append("✪".repeat(Math.max(0, Math.min(gold, 15))));
        int rest = Math.max(0, max - gold);
        if (rest > 0) sb.append("&8").append("✪".repeat(Math.min(rest, 15)));
        return sb.toString();
    }

    private static void doStar(MenuClick c) {
        Player p = c.player();
        Menu m = c.menu();
        ItemStack it = m.get(ITEM);
        ItemDef def = Gear.def(it);
        if (def == null) return;
        ItemData d = Sky.items().data(it);
        List<ReforgeData.Cost> next = ReforgesModule.MODS.nextStarCost(def, d);
        if (next == null) { Msg.error(p, "This item cannot take another star."); return; }
        String why = Gear.canPay(p, next, 1.0);
        if (why != null) { Msg.error(p, why); return; }
        if (!Gear.pay(p, next, 1.0, "reforges: star " + (d.stars + 1) + " on " + def.id)) { Msg.error(p, "You cannot afford that."); return; }
        int to = d.stars + 1;
        Sky.items().update(it, x -> x.stars = to);
        m.getInventory().setItem(ITEM, it);
        ReforgesModule.data(p).starsAdded++;
        Msg.send(p, "&aYou upgraded your " + Gear.name(it) + "&a!");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
        m.redraw();
    }

    // ---- gemstone grinder ---------------------------------------------------------------------------

    static final int[] GEM_POS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    static final int[] OPT_POS = {37, 38, 39, 40, 41, 42, 43};

    static void gems(Player p) { if (ready(p)) buildGems(p).open(p); }

    static Menu buildGems(Player p) {
        Menu m = new Menu("Gemstone Grinder", 6);
        int[] selected = {-1};
        m.free(ITEM).chrome();
        m.onFreeSlotChange(c -> { selected[0] = -1; c.menu().redraw(); });
        m.drawer(mm -> drawGems(mm, p, selected));
        return m;
    }

    private static boolean slotUnlocked(ItemDef def, ItemData d, int i) {
        return ReforgesModule.DATA.slotCost(def, i).isEmpty() || (d.unlockedGemSlots != null && d.unlockedGemSlots.contains(String.valueOf(i)));
    }

    private static void drawGems(Menu m, Player p, int[] selected) {
        for (int s : GEM_POS) m.set(s, Icons.filler());
        for (int s : OPT_POS) m.set(s, Icons.filler());
        ItemStack it = m.get(ITEM);
        ItemDef def = Gear.def(it);
        List<String> types = ReforgesModule.DATA.slotTypes(def);
        if (Gear.empty(it) || def == null || types.isEmpty()) {
            m.set(4, Icons.of(Material.AMETHYST_SHARD, "&aGemstone Grinder", "&7Place an item with gemstone slots", "&7below to unlock slots and set",
                    "&7or remove gems.", "", Gear.empty(it) ? "&7Waiting for an item..." : "&cThat item has no gemstone slots."));
            backToHub(m, p);
            return;
        }
        ItemData d = Sky.items().data(it);
        Rarity r = Sky.items().rarity(it);
        // nothing picked yet: open on the first empty, unlocked slot so the fitting gems show straight away
        if (selected[0] < 0 || selected[0] >= types.size() || (d.gems != null && d.gems.containsKey(String.valueOf(selected[0])))
                || !slotUnlocked(def, d, selected[0])) {
            selected[0] = -1;
            for (int i = 0; i < types.size() && i < GEM_POS.length; i++) {
                if ((d.gems == null || !d.gems.containsKey(String.valueOf(i))) && slotUnlocked(def, d, i)) { selected[0] = i; break; }
            }
        }
        m.set(4, Icons.of(Material.AMETHYST_SHARD, "&aGemstone Grinder", "&7Click a slot below to work on it.", "",
                "&7Locked slots cost coins and gems", "&7to open. Removing a gem costs a fee", "&7and gives the gem back."));
        for (int i = 0; i < types.size() && i < GEM_POS.length; i++) {
            final int idx = i;
            String type = types.get(i);
            String gem = d.gems == null ? null : d.gems.get(String.valueOf(i));
            if (gem != null) {
                ItemDef gd = Sky.items().def(gem);
                Stat st = ReforgesModule.DATA.gemStat(type(gem));
                double v = ReforgesModule.DATA.gemValue(gem, r, type);
                double fee = ReforgesModule.DATA.removalFee(gem) * ReforgesModule.removalFeeMult.get();
                m.set(GEM_POS[i], iconOf(gd, Gear.itemName(gem), List.of("&7Slot: &f" + slotLabel(type),
                                "&7Grants: " + (st == null ? "&8nothing" : "&" + st.color + "+" + Text.commas(v) + st.symbol + " " + st.display), "",
                                "&7Removal fee: " + Gear.coins(fee), "&7The gem goes back to you.", "", "&eClick to remove!")),
                        c -> removeGem(c, idx));
            } else if (!slotUnlocked(def, d, i)) {
                List<String> lore = new ArrayList<>(List.of("&cLocked", "", "&7Unlock cost:"));
                lore.addAll(Gear.describe(p, ReforgesModule.DATA.slotCost(def, i), ReforgesModule.unlockCostMult.get()));
                lore.add("");
                lore.add("&eClick to unlock!");
                m.set(GEM_POS[i], Icons.of(Material.GRAY_DYE, "&c" + slotLabel(type) + " Gemstone Slot", lore), c -> unlockSlot(c, idx));
            } else {
                boolean sel = selected[0] == i;
                m.set(GEM_POS[i], Icons.of(sel ? Material.LIME_STAINED_GLASS : glassFor(type), "&a" + slotLabel(type) + " Gemstone Slot",
                        "&7Empty", "&7Accepts: &f" + accepts(type), "", sel ? "&aSelected! &7Pick a gem below." : "&eClick to pick a gem!"),
                        c -> { selected[0] = idx; c.menu().redraw(); });
            }
        }
        // options for the selected empty slot: the player's own gems that fit
        int sel = selected[0];
        if (sel >= 0 && sel < types.size() && (d.gems == null || !d.gems.containsKey(String.valueOf(sel))) && slotUnlocked(def, d, sel)) {
            String type = types.get(sel);
            Map<String, Integer> have = new LinkedHashMap<>();
            for (ItemStack s : p.getInventory().getStorageContents()) {
                if (Gear.empty(s)) continue;
                String id = Sky.items().idOf(s);
                String[] qt = ReforgeData.parseGem(id);
                if (qt == null || !ReforgeData.slotAccepts(type, qt[1])) continue;
                have.merge(id, s.getAmount(), Integer::sum);
            }
            // gems in the Gemstone Sack count too (the reference grinder reads sacks)
            for (String g : ReforgesModule.DATA.allGemIds()) {
                String[] qt = ReforgeData.parseGem(g);
                if (qt == null || !ReforgeData.slotAccepts(type, qt[1])) continue;
                int inSack = Gear.sackCount(p, g);
                if (inSack > 0) have.merge(g, inSack, Integer::sum);
            }
            List<String> ids = new ArrayList<>(have.keySet());
            ids.sort(Comparator.comparingDouble((String g) -> -ReforgesModule.DATA.gemValue(g, r, type)));
            if (ids.isEmpty()) {
                m.set(OPT_POS[3], Icons.of(Material.BARRIER, "&cNo Fitting Gems", "&7You have no gems that fit a", "&f" + slotLabel(type) + " &7slot.",
                        "&7Accepts: &f" + accepts(type)));
            }
            for (int k = 0; k < ids.size() && k < OPT_POS.length; k++) {
                String g = ids.get(k);
                Stat st = ReforgesModule.DATA.gemStat(type(g));
                double v = ReforgesModule.DATA.gemValue(g, r, type);
                m.set(OPT_POS[k], iconOf(Sky.items().def(g), Gear.itemName(g), List.of("&7You have: &a" + have.get(g)
                                        + (Gear.sackCount(p, g) > 0 ? " &8(" + Gear.sackCount(p, g) + " in sacks)" : ""),
                                "&7Grants: " + (st == null ? "&8nothing" : "&" + st.color + "+" + Text.commas(v) + st.symbol + " " + st.display), "",
                                "&eClick to apply!")),
                        c -> applyGem(c, sel, g));
            }
        }
        backToHub(m, p);
    }

    private static String type(String gemId) { String[] qt = ReforgeData.parseGem(gemId); return qt == null ? "" : qt[1]; }

    static String slotLabel(String type) { return Text.titleCase(type.toLowerCase(Locale.ROOT)); }

    static String accepts(String type) {
        return switch (type) {
            case "UNIVERSAL" -> "any gem";
            case "COMBAT" -> "Ruby, Amethyst, Sapphire, Jasper, Onyx";
            case "OFFENSIVE" -> "Sapphire, Jasper";
            case "DEFENSIVE" -> "Ruby, Amethyst, Opal";
            case "MINING" -> "Jade, Amber, Topaz";
            case "CHISEL" -> "Citrine, Aquamarine, Peridot, Onyx";
            default -> slotLabel(type);
        };
    }

    private static Material glassFor(String type) {
        return switch (type) {
            case "RUBY" -> Material.RED_STAINED_GLASS;
            case "AMETHYST" -> Material.PURPLE_STAINED_GLASS;
            case "JADE", "PERIDOT" -> Material.LIME_STAINED_GLASS;
            case "AMBER", "CITRINE" -> Material.ORANGE_STAINED_GLASS;
            case "SAPPHIRE", "AQUAMARINE" -> Material.LIGHT_BLUE_STAINED_GLASS;
            case "TOPAZ" -> Material.YELLOW_STAINED_GLASS;
            case "JASPER" -> Material.PINK_STAINED_GLASS;
            case "OPAL" -> Material.WHITE_STAINED_GLASS;
            case "ONYX" -> Material.BLACK_STAINED_GLASS;
            default -> Material.GLASS;
        };
    }

    private static void unlockSlot(MenuClick c, int idx) {
        Player p = c.player();
        Menu m = c.menu();
        ItemStack it = m.get(ITEM);
        ItemDef def = Gear.def(it);
        if (def == null) return;
        ItemData d = Sky.items().data(it);
        if (slotUnlocked(def, d, idx)) { m.redraw(); return; }
        List<ReforgeData.Cost> cost = ReforgesModule.DATA.slotCost(def, idx);
        double mult = ReforgesModule.unlockCostMult.get();
        String why = Gear.canPay(p, cost, mult);
        if (why != null) { Msg.error(p, why); return; }
        if (!Gear.pay(p, cost, mult, "reforges: unlock gem slot " + idx + " on " + def.id)) { Msg.error(p, "You cannot afford that."); return; }
        Sky.items().update(it, x -> x.unlockedGemSlots.add(String.valueOf(idx)));
        m.getInventory().setItem(ITEM, it);
        Msg.success(p, "Gemstone slot unlocked!");
        m.redraw();
    }

    private static void removeGem(MenuClick c, int idx) {
        Player p = c.player();
        Menu m = c.menu();
        ItemStack it = m.get(ITEM);
        ItemDef def = Gear.def(it);
        if (def == null) return;
        String gem = Sky.items().data(it).gems.get(String.valueOf(idx));
        if (gem == null) { m.redraw(); return; }
        double fee = ReforgesModule.DATA.removalFee(gem) * ReforgesModule.removalFeeMult.get();
        if (fee > 0 && !Sky.economy().withdraw(p, fee, "reforges: remove " + gem + " from " + def.id)) {
            Msg.error(p, "You need " + Text.commasInt(fee) + " coins to remove that gem.");
            return;
        }
        Sky.items().update(it, x -> x.gems.remove(String.valueOf(idx)));
        m.getInventory().setItem(ITEM, it);
        if (Sky.items().exists(gem)) Sky.loot().give(p, gem, 1, LootSource.OTHER);
        Msg.send(p, "&aRemoved " + Gear.itemName(gem) + " &afrom your " + Gear.name(it) + "&a.");
        m.redraw();
    }

    private static void applyGem(MenuClick c, int idx, String gem) {
        Player p = c.player();
        Menu m = c.menu();
        ItemStack it = m.get(ITEM);
        ItemDef def = Gear.def(it);
        if (def == null) return;
        ItemData d = Sky.items().data(it);
        List<String> types = ReforgesModule.DATA.slotTypes(def);
        String[] qt = ReforgeData.parseGem(gem);
        if (idx >= types.size() || qt == null || !ReforgeData.slotAccepts(types.get(idx), qt[1])
                || d.gems.containsKey(String.valueOf(idx)) || !slotUnlocked(def, d, idx)) { m.redraw(); return; }
        if (!Gear.take(p, gem, 1)) { Msg.error(p, "You no longer have that gem."); m.redraw(); return; }
        Sky.items().update(it, x -> x.gems.put(String.valueOf(idx), gem));
        m.getInventory().setItem(ITEM, it);
        if (Sky.profiles().isReady(p)) ReforgesModule.data(p).gemsApplied++;
        Msg.send(p, "&aYou set " + Gear.itemName(gem) + " &ainto your " + Gear.name(it) + "&a!");
        Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f);
        m.redraw();
    }

    // ---- guide ----------------------------------------------------------------------------------------

    static final String[] FILTERS = {"All", "Blacksmith", "Reforge Stones", "Armor", "Weapons", "Tools", "Equipment", "Fishing"};

    static void guide(Player p, int filter, int page) {
        List<ReforgeData.Row> list = new ArrayList<>();
        for (ReforgeData.Row r : ReforgesModule.DATA.rows.values()) if (matchesFilter(r, filter)) list.add(r);
        int f = filter;
        Sky.menus().paged("Reforge Guide (" + FILTERS[f] + ")", list, Menus::guideIcon,
                pc -> { if (pc.entry().stone != null && ReforgesModule.shopEnabled.get()) buyStone(pc.click().player(), pc.entry().stone, 0); },
                pg -> {
                    pg.set(48, Icons.back(HUB), c -> hub(c.player()));
                    List<String> lore = new ArrayList<>();
                    for (int i = 0; i < FILTERS.length; i++) lore.add((i == f ? "&a▶ " : "&7") + FILTERS[i]);
                    lore.add("");
                    lore.add("&eClick to cycle!");
                    pg.set(50, Icons.of(Material.HOPPER, "&aFilter", lore), c -> guide(c.player(), (f + 1) % FILTERS.length, 0));
                }, page).open(p);
    }

    private static boolean matchesFilter(ReforgeData.Row r, int f) {
        String t = r.types == null ? "" : r.types;
        return switch (f) {
            case 1 -> !r.isStone();
            case 2 -> r.isStone();
            case 3 -> t.equals("ARMOR") || t.equals("HELMET") || t.equals("CHESTPLATE");
            case 4 -> t.startsWith("SWORD") || t.equals("BOW");
            case 5 -> t.equals("PICKAXE") || t.equals("AXE") || t.equals("FARMING_TOOL") || t.equals("VACUUM");
            case 6 -> t.equals("EQUIPMENT") || t.equals("CLOAK") || t.equals("BELT");
            case 7 -> t.equals("ROD") || t.equals("FISHING_NET") || t.equals("SWORD/ROD");
            default -> true;
        };
    }

    static String typePhrase(ReforgeData.Row r) {
        if (r.types == null) {
            List<String> names = new ArrayList<>();
            for (String id : r.itemIds) names.add(Text.strip(Gear.itemName(id)));
            for (String mat : r.materials) names.add(Text.titleCase(mat));
            return names.isEmpty() ? "special items" : String.join(", ", names);
        }
        return switch (r.types) {
            case "ARMOR" -> "Armor";
            case "EQUIPMENT" -> "Equipment";
            case "SWORD" -> "Melee weapons";
            case "SWORD/ROD" -> "Melee weapons & fishing rods";
            case "BOW" -> "Bows";
            case "AXE" -> "Axes";
            case "PICKAXE" -> "Pickaxes & drills";
            case "FARMING_TOOL" -> "Farming tools";
            case "ROD" -> "Fishing rods";
            case "FISHING_NET" -> "Fishing nets";
            default -> Text.titleCase(r.types.toLowerCase(Locale.ROOT));
        };
    }

    private static Material typeIcon(ReforgeData.Row r) {
        String t = r.types == null ? "" : r.types;
        return switch (t) {
            case "ARMOR", "CHESTPLATE" -> Material.IRON_CHESTPLATE;
            case "HELMET" -> Material.IRON_HELMET;
            case "EQUIPMENT", "CLOAK", "BELT" -> Material.LEAD;
            case "SWORD", "SWORD/ROD" -> Material.IRON_SWORD;
            case "BOW" -> Material.BOW;
            case "AXE" -> Material.IRON_AXE;
            case "PICKAXE" -> Material.IRON_PICKAXE;
            case "FARMING_TOOL" -> Material.IRON_HOE;
            case "ROD", "FISHING_NET" -> Material.FISHING_ROD;
            case "VACUUM" -> Material.HOPPER;
            default -> Material.NAME_TAG;
        };
    }

    static ItemStack guideIcon(ReforgeData.Row r) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Fits: &f" + typePhrase(r));
        lore.add(r.isStone() ? "&7Source: " + Gear.itemName(r.stone) : "&7Source: &fBlacksmith (random)");
        lore.add("");
        for (Rarity rar : Rarity.values()) {
            Map<String, Double> m = r.stats.get(rar.name());
            if (m == null) continue;
            String c = compactStats(m);
            lore.add(rar.code() + rar.display().charAt(0) + "&8: " + (c.isEmpty() ? "&8no stats" : c)
                    + (r.isStone() && r.costs.containsKey(rar.name()) ? " &8(" + Text.shortNum(r.costs.get(rar.name())) + ")" : ""));
        }
        ReforgeData.Bonus b = ReforgeData.bonusFor(r, Rarity.LEGENDARY);
        if (b != null && b.text != null) {
            lore.add("");
            lore.add("&9" + r.name + " Bonus" + (r.bonusImpl || r.bonusBy != null ? "" : " &8(passive text)"));
            lore.addAll(Text.wrap(b.text, 36));
        }
        if (r.otherStats != null && !r.otherStats.isEmpty()) {
            Map<String, Double> o = null;
            for (Rarity rar : Rarity.values()) if (r.otherStats.containsKey(rar.name())) o = r.otherStats.get(rar.name());
            if (o != null) for (Map.Entry<String, Double> e : o.entrySet())
                lore.add("&7" + Text.titleCase(e.getKey()) + ": &d+" + Text.commas(e.getValue()) + " &8(top rarity)");
        }
        if (r.isStone() && ReforgesModule.shopEnabled.get() && ReforgesModule.DATA.shop.containsKey(r.stone)) {
            lore.add("");
            lore.add("&eClick to buy the stone!");
        }
        return Icons.of(typeIcon(r), "&9" + r.name, lore);
    }

    // ---- stone merchant ---------------------------------------------------------------------------------

    static void shop(Player p, int page) {
        if (!ReforgesModule.shopEnabled.get()) { Msg.error(p, "The stone merchant is closed."); return; }
        List<String> ids = new ArrayList<>();
        for (String id : ReforgesModule.DATA.shop.keySet()) if (Sky.items().exists(id)) ids.add(id);
        Sky.menus().paged("Stone Merchant", ids, id -> shopIcon(id),
                pc -> buyStone(pc.click().player(), pc.entry(), pc.page()),
                pg -> pg.set(48, Icons.back(HUB), c -> hub(c.player())), page).open(p);
    }

    static double price(String id) {
        Double base = ReforgesModule.DATA.shop.get(id);
        return base == null ? 0 : Math.max(1, Math.round(base * ReforgesModule.shopPriceMult.get()));
    }

    private static ItemStack shopIcon(String id) {
        ItemDef d = Sky.items().def(id);
        ReforgeData.Row r = ReforgesModule.DATA.byStone.get(id);
        List<String> lore = new ArrayList<>();
        if (r != null) {
            lore.add("&7Applies &9" + r.name + " &7to: &f" + typePhrase(r));
            Map<String, Double> best = ReforgeData.statsFor(r, Rarity.LEGENDARY);
            if (!best.isEmpty()) lore.add("&6L&8: " + compactStats(best));
        }
        lore.add("");
        lore.add("&7Price: " + Gear.coins(price(id)));
        lore.add("");
        lore.add("&eClick to buy!");
        return iconOf(d, Gear.itemName(id), lore);
    }

    private static void buyStone(Player p, String id, int page) {
        if (!ReforgesModule.shopEnabled.get() || !Sky.items().exists(id) || !ReforgesModule.DATA.shop.containsKey(id)) return;
        double cost = price(id);
        Sky.menus().confirm(p, "Buy " + Text.strip(Gear.itemName(id)) + "?", List.of("&7Price: " + Gear.coins(cost)), () -> {
            if (!Sky.economy().withdraw(p, cost, "reforges: stone merchant " + id)) { Msg.error(p, "You don't have enough coins!"); return; }
            Sky.loot().give(p, id, 1, LootSource.NPC_SHOP);
            Msg.success(p, "You bought " + Text.strip(Gear.itemName(id)) + " for " + Text.commasInt(cost) + " coins!");
            shop(p, page);
        }, () -> shop(p, page));
    }
}
