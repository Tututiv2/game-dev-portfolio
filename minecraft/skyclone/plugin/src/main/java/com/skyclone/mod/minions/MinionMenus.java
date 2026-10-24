package com.skyclone.mod.minions;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.MenuClick;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.minions.MinionCatalog.Drop;
import com.skyclone.mod.minions.MinionCatalog.FuelDef;
import com.skyclone.mod.minions.MinionCatalog.TierDef;
import com.skyclone.mod.minions.MinionCatalog.TypeDef;
import com.skyclone.mod.minions.MinionCatalog.UpgradeDef;
import com.skyclone.mod.minions.MinionStore.Crystal;
import com.skyclone.mod.minions.MinionStore.Placed;
import com.skyclone.mod.minions.MinionStore.Slot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every minion menu. Each click handler re-reads the profile data and re-validates (CORE_GUIDE trap 9). */
final class MinionMenus {

    private final MinionsModule mod;

    static final int[] STORAGE = {21, 22, 23, 24, 25, 30, 31, 32, 33, 34, 39, 40, 41, 42, 43};
    static final int[] INNER = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    static final int SKIN = 10, FUEL = 19, SHIP = 28, UP1 = 37, UP2 = 46;
    static final int CHEST = 17, CHEST_VIEW = 26, HOPPER = 35, BONUS = 44;
    static final int SPEED = 12, PROD = 13, STATUS = 14, INFUSE = 15, STORE = 16;
    static final int LAYOUT = 3, INFO = 4, NEXT = 5;
    static final int BACK = 45, COLLECT = 48, RECIPES = 50, PICKUP = 53;

    static final String[] CATEGORIES = {"ALL", "FARMING", "MINING", "COMBAT", "FORAGING", "FISHING", "SLAYER"};

    MinionMenus(MinionsModule mod) { this.mod = mod; }

    private MinionManager mgr() { return mod.manager; }

    private MinionCatalog cat() { return mod.cat; }

    // =====================================================================================================
    // The minion menu
    // =====================================================================================================

    void openMinion(Player p, String minionId) {
        Placed m = mgr().store(p).placed.get(minionId);
        if (m == null) return;
        TypeDef t = cat().type(m.type);
        if (t == null) return;
        Menu menu = new Menu(MinionText.plain(t, m.tier), 6);
        menu.drawer(mn -> {
            if (!Sky.profiles().isReady(p)) return;
            Placed cur = mgr().store(p).placed.get(minionId);
            if (cur == null) { Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (Sky.menus().current(p) == mn) p.closeInventory(); }); return; }
            drawMinion(mn, p, cur, mgr().env(p));
        });
        menu.refreshEvery(20);
        menu.onPlayerInventoryClick((c, item) -> {
            Placed cur = mgr().store(c.player()).placed.get(minionId);
            if (cur == null || item == null || item.getType().isAir()) return;
            if (MinionManager.FREE_WILL.equals(Sky.items().idOf(item))) { confirmFreeWill(c.player(), minionId); return; }
            String msg = mgr().install(c.player(), cur, item);
            if (msg != null && !msg.isEmpty()) Msg.send(c.player(), msg);
            if (msg != null && msg.startsWith("&6Your minion is now")) { openMinion(c.player(), minionId); return; }
            c.menu().redraw();
        });
        menu.open(p);
    }

    /** Free Will: say the odds and what is at stake, then roll only on an explicit yes. */
    void confirmFreeWill(Player p, String minionId) {
        Placed m = mgr().store(p).placed.get(minionId);
        if (m == null) return;
        if (!mod.dials.freeWill.get()) { Msg.error(p, "Free Will is turned off on this server."); return; }
        if (m.freeWill) { Msg.send(p, "&cThis minion already chose to stay with you."); return; }
        TypeDef t = cat().type(m.type);
        double chance = mgr().freeWillChance(m);
        List<String> l = new ArrayList<>();
        l.add("&7Offer &6Free Will &7to " + MinionText.name(t, m.tier) + "&7?");
        l.add("");
        l.add("&a" + MinionText.pct(chance) + " &7it stays: &a+10% &7speed for good.");
        l.add("&c" + MinionText.pct(100 - chance) + " &7it leaves: the minion is &cgone&7,");
        l.add("&7its items and upgrades come back to you,");
        l.add("&7and it mails you a &dPostcard&7.");
        l.add("");
        l.add("&8Higher tiers are more loyal.");
        Sky.menus().confirm(p, "Offer Free Will?", l, () -> {
            String msg = mgr().useFreeWill(p, minionId);
            if (msg != null && !msg.isEmpty()) Msg.send(p, msg);
            if (mgr().store(p).placed.containsKey(minionId)) openMinion(p, minionId); else openOverview(p, 0);
        }, () -> openMinion(p, minionId));
    }

    /** Draws a minion menu. {@code viewer} may be null (self-test); then clicks are not wired to a profile. */
    void drawMinion(Menu menu, Player viewer, Placed m, MinionManager.Env env) {
        MinionCatalog cat = cat();
        TypeDef t = cat.type(m.type);
        TierDef td = t.tier(m.tier);
        String id = m.id;
        menu.chrome();
        int cap = MinionSim.capacity(cat, m);
        int own = MinionSim.ownSlots(cat, m);
        Map<String, Double> speed = env.breakdown(m, t);
        double base = 0;
        for (double v : speed.values()) base += v;
        FuelDef fuel = MinionSim.activeFuel(cat, m, t);
        double fuelSpeed = fuel != null && (!fuel.solar || env.solarActive()) ? fuel.speed + fuel.categoryBonus.getOrDefault(t.category, 0.0) : 0;
        double total = base + fuelSpeed;
        double mult = env.outputMult(m, t) * (fuel != null ? Math.max(1, fuel.mult) : 1);
        boolean full = MinionSim.isFull(cat, m, t, cap, env);

        // ---- top row ----
        ItemDef def = Sky.items().def(t.itemId(m.tier));
        List<String> info = new ArrayList<>();
        info.add("&7Category: " + MinionText.category(t.category));
        info.add("&7Skill XP: " + MinionText.skillOf(t));
        info.add("");
        info.add("&7Time Between Actions: &a" + MinionText.seconds(MinionSim.effectiveTba(td.tba, total))
                + (total != 0 ? " &8(base " + MinionText.seconds(td.tba) + ")" : ""));
        info.add("&7Max Storage: &e" + Text.commas(td.storage) + (cap > own ? " &8+ " + ((cap - own) * 64) + " chest" : ""));
        info.add("&7Resources Generated: &b" + Text.commas(m.generated));
        info.add("");
        info.add("&7Placed by &f" + (m.placedBy == null ? "?" : m.placedBy) + " &7at &f" + MinionText.where(m));
        menu.set(INFO, Icons.skull(def == null ? null : def.skull, MinionText.name(t, m.tier), info));

        menu.set(LAYOUT, Icons.of(Material.REDSTONE_TORCH, "&aIdeal Layout",
                "&7See what this minion needs around", "&7it, and which cells are blocked.", "", "&eClick to view!"),
                c -> openLayout(c.player(), id));

        drawNextTier(menu, viewer, m, t);

        // ---- stats strip ----
        List<String> sp = new ArrayList<>();
        sp.add("&7Every bonus adds up and divides the");
        sp.add("&7time between actions.");
        sp.add("");
        if (speed.isEmpty() && fuelSpeed == 0) sp.add("&8No speed bonuses yet.");
        for (Map.Entry<String, Double> e : speed.entrySet()) sp.add("&7" + Text.strip(e.getKey()) + ": &a+" + MinionText.pct(e.getValue()));
        if (fuelSpeed > 0) sp.add("&7Fuel: &a+" + MinionText.pct(fuelSpeed));
        sp.add("");
        sp.add("&7Total: &a+" + MinionText.pct(total));
        menu.set(SPEED, Icons.of(Material.CLOCK, "&aSpeed &7(+" + MinionText.pct(total) + ")", sp));

        List<String> pr = new ArrayList<>();
        pr.add("&7What this minion makes per hour");
        pr.add("&7right now:");
        pr.add("");
        double harvestsPerHour = 3600.0 / (MinionSim.effectiveTba(td.tba, total) * Math.max(1, t.actionsPerHarvest));
        double perHourTotal = 0;
        for (Drop d : MinionSim.effectiveDrops(cat, m, t)) {
            double ph = harvestsPerHour * d.avg * mult;
            perHourTotal += ph;
            pr.add("&f" + Text.strip(MinionText.itemName(d.item)) + " &7x&e" + MinionText.rate(ph) + "&7/h");
        }
        if (mult != 1) pr.add("&8(output x" + Text.commas(Math.round(mult * 100) / 100.0) + ")");
        pr.add("");
        int space = Math.max(0, cap * 64 - m.stored());
        if (perHourTotal > 0 && MinionSim.shippingRate(cat, m) <= 0) {
            pr.add(full ? "&cStorage is full!" : "&7Storage full in &e" + Text.duration((long) (space / perHourTotal * 3600)));
        }
        menu.set(PROD, Icons.of(Material.HOPPER, "&aProduction", pr));

        String status;
        Material statusIcon;
        List<String> st = new ArrayList<>();
        boolean live = false;
        World w = Bukkit.getWorld(m.world);
        int valid = -1;
        if (w != null && w.isChunkLoaded(m.x >> 4, m.z >> 4)) {
            live = true;
            valid = mgr().layoutValid(w, m, t, System.currentTimeMillis());
        }
        if (m.seated) {
            status = "&eResting on a Minion Chair";
            statusIcon = Material.YELLOW_DYE;
            st.add("&7It is not working, burns no fuel");
            st.add("&7and does not count toward your");
            st.add("&7minion limit. Stand it up to resume.");
        } else if (full) {
            status = "&cStorage full";
            statusIcon = Material.RED_DYE;
            st.add("&7It stopped working. Collect its items,");
            st.add("&7attach a storage chest or add a hopper.");
        } else if (live && valid == 0 && mod.dials.layoutCheck.get()) {
            status = "&cThis location isn't perfect!";
            statusIcon = Material.ORANGE_DYE;
            st.add("&7There is no usable cell around it.");
            st.add("&7Check the &aIdeal Layout&7.");
        } else {
            status = "&aWorking";
            statusIcon = Material.LIME_DYE;
            st.add("&7All good.");
            if (live && valid > 0) st.add("&7Usable work cells: &a" + valid);
        }
        st.add("");
        st.add("&8Minions work while you are offline;");
        st.add("&8offline they assume a perfect layout.");
        menu.set(STATUS, Icons.of(statusIcon, "&7Status: " + status, st));

        if (m.infused) {
            menu.set(INFUSE, Icons.glow(Icons.of(Material.PRISMARINE_CRYSTALS, "&eMithril Infused", "&7This minion is permanently", "&a10% &7faster.")));
        } else {
            menu.set(INFUSE, Icons.of(Material.GRAY_DYE, "&7Not infused", "&7Click a &aMithril Infusion &7in your", "&7inventory to make this minion", "&a10% &7faster for good."));
        }
        drawChairAndFreeWill(menu, m, t);
        menu.set(STORE, Icons.of(Material.CHEST, "&aStorage", "&7Slots used: &e" + m.storage.size() + "&7/&e" + cap,
                "&7Items: &e" + Text.commas(m.stored()), "", "&8Its own " + own + " slots fill first,", "&8then an attached chest."));

        // ---- left column: skin, fuel, shipping, upgrades ----
        attachment(menu, SKIN, m.skin, Material.LIME_STAINED_GLASS_PANE, "&aMinion Skin Slot",
                List.of("&7Give your minion a new look.", "", "&eClick a minion skin in your", "&einventory to apply it."), "skin", id, null);
        drawFuel(menu, m, t, env);
        UpgradeDef ship = cat.upgrade(m.shipping);
        List<String> shipExtra = ship == null ? null : List.of("&7Sells overflow at &6" + Math.round(ship.sell * 100) + "% &7of NPC price.",
                "&7Held coins: &6" + Text.commas(Math.floor(m.heldCoins * 10) / 10));
        attachment(menu, SHIP, m.shipping, Material.BLUE_STAINED_GLASS_PANE, "&aAutomated Shipping",
                List.of("&7When storage is full, a hopper", "&7sells what the minion makes.", "", "&eClick a Budget or Enchanted", "&eHopper in your inventory."), "shipping", id, shipExtra);
        attachment(menu, UP1, m.upgrade1, Material.YELLOW_STAINED_GLASS_PANE, "&aUpgrade Slot",
                List.of("&7Compactors, smelters, spreading,", "&7expanders and more.", "", "&eClick an upgrade in your", "&einventory to install it."), "upgrade1", id, upgradeLines(m.upgrade1, t));
        attachment(menu, UP2, m.upgrade2, Material.YELLOW_STAINED_GLASS_PANE, "&aUpgrade Slot",
                List.of("&7Compactors, smelters, spreading,", "&7expanders and more.", "", "&eClick an upgrade in your", "&einventory to install it."), "upgrade2", id, upgradeLines(m.upgrade2, t));
        UpgradeDef chest = cat.upgrade(m.chest);
        attachment(menu, CHEST, m.chest, Material.BROWN_STAINED_GLASS_PANE, "&aStorage Chest",
                List.of("&7Attach a storage chest for more", "&7room: Small 3, Medium 9, Large 15,", "&7X-Large 21, XX-Large 27 slots.", "", "&eClick one in your inventory."), "chest", id,
                chest == null ? null : List.of("&7Extra slots: &e" + chest.slots, "&cEmpty it before taking it off."));
        if (chest != null) {
            menu.set(CHEST_VIEW, Icons.of(Material.CHEST, "&aChest Storage", "&7" + Math.max(0, m.storage.size() - own) + "/" + chest.slots + " slots used.",
                    m.chestBlock != null ? "&8The chest stands beside the minion;" : "&8It rides on the minion's back.",
                    m.chestBlock != null ? "&8clicking it opens this too." : "&8Place one next to a minion to see it.", "", "&eClick to open!"),
                    c -> openChest(c.player(), id));
        }
        if (m.heldCoins >= 0.1 || ship != null) {
            menu.set(HOPPER, Icons.of(Material.GOLD_NUGGET, "&6Held Coins: " + Text.commas(Math.floor(m.heldCoins * 10) / 10),
                    "&7Coins your hopper earned.", "", "&eClick to collect!"), c -> {
                Placed cur = mgr().store(c.player()).placed.get(id);
                if (cur == null || cur.heldCoins < 0.1) return;
                if (!mgr().canManage(c.player(), cur)) { Msg.error(c.player(), "Visit your island to collect from this minion!"); return; }
                double coins = Math.floor(cur.heldCoins * 10) / 10;
                cur.heldCoins -= coins;
                Sky.economy().deposit(c.player(), coins, "minion: hopper sales (" + cur.type + ")");
                Msg.success(c.player(), "Collected " + Text.commas(coins) + " coins.");
                c.menu().redraw();
            });
        }
        List<String> bonus = new ArrayList<>();
        bonus.add("&7Island-wide boosts in effect:");
        bonus.add("");
        int nb = 0;
        for (Map.Entry<String, Double> e : speed.entrySet()) {
            if (e.getKey().contains("Crystal") || e.getKey().contains("Postcard") || e.getKey().equals("Pet") || e.getKey().contains("Mayor")
                    || e.getKey().startsWith("Beacon")) {
                bonus.add("&7" + Text.strip(e.getKey()) + ": &a+" + MinionText.pct(e.getValue()));
                nb++;
            }
        }
        if (nb == 0) bonus.add("&8None. Crystals, pets and the mayor can help.");
        menu.set(BONUS, Icons.of(Material.AMETHYST_SHARD, "&dIsland Bonuses", bonus));

        // ---- storage ----
        for (int i = 0; i < STORAGE.length; i++) {
            int slot = STORAGE[i];
            if (i >= own) {
                int unlockAt = 0;
                for (TierDef x : t.tiers) if (x.slots() > i) { unlockAt = x.tier; break; }
                menu.set(slot, Icons.of(Material.WHITE_STAINED_GLASS_PANE, "&eStorage unlocked at tier " + (unlockAt == 0 ? "?" : Text.roman(unlockAt))));
                continue;
            }
            if (i >= m.storage.size()) { menu.set(slot, null); continue; }
            Slot s = m.storage.get(i);
            final int index = i;
            menu.set(slot, storageIcon(s), c -> {
                Placed cur = mgr().store(c.player()).placed.get(id);
                if (cur == null) return;
                if (!mgr().canManage(c.player(), cur)) { Msg.error(c.player(), "Visit your island to collect from this minion!"); return; }
                if (!sameSlot(cur, index, c.current())) { c.menu().redraw(); return; }
                mgr().collectSlot(c.player(), cur, index);
                c.menu().redraw();
            });
        }

        // ---- bottom row ----
        menu.set(BACK, Icons.back("Your Minions"), c -> openOverview(c.player(), 0));
        menu.set(COLLECT, Icons.of(Material.CHEST, "&aCollect All", "&7Take everything this minion has", "&7made, plus its hopper's coins.", "", "&eClick to collect!"), c -> {
            Placed cur = mgr().store(c.player()).placed.get(id);
            if (cur == null) return;
            if (!mgr().canManage(c.player(), cur)) { Msg.error(c.player(), "Visit your island to collect from this minion!"); return; }
            int n = mgr().collectAll(c.player(), cur, false);
            if (n == 0 && cur.storage.isEmpty()) Msg.send(c.player(), "&7This minion has nothing to collect.");
            c.menu().redraw();
        });
        menu.set(RECIPES, Icons.of(Material.KNOWLEDGE_BOOK, "&aRecipes", "&7See how to craft this minion", "&7and its next tiers.", "", "&eClick to view!"),
                c -> { if (!Sky.commands().run(c.player(), "recipe", t.itemId(Math.min(t.highestTier(), m.tier + 1)))) openType(c.player(), t.id); });
        menu.set(PICKUP, Icons.of(Material.BEDROCK, "&aPick Up Minion", "&7Puts the minion, its upgrades and", "&7its items back in your inventory.",
                "&7Timed fuel is destroyed.", "", "&eClick to pick up!"), c -> {
            Placed cur = mgr().store(c.player()).placed.get(id);
            if (cur == null) return;
            FuelDef f = cat().fuel(cur.fuel);
            boolean loses = f != null && !f.infinite();
            List<String> warn = new ArrayList<>();
            warn.add("&7Pick up " + MinionText.name(cat().type(cur.type), cur.tier) + "&7?");
            if (loses) warn.add("&cIts " + Text.strip(MinionText.itemName(cur.fuel)) + " will burn away.");
            if (!cur.storage.isEmpty()) warn.add("&7Its " + Text.commas(cur.stored()) + " items come with it.");
            Sky.menus().confirm(c.player(), "Pick up minion?", warn, () -> mgr().pickup(c.player(), id), () -> openMinion(c.player(), id));
        });
    }

    static final int FREEWILL = 6, CHAIR = 7;

    /** Top row: the Minion Chair (sit / stand up) and Free Will (loyal, or the odds). */
    private void drawChairAndFreeWill(Menu menu, Placed m, TypeDef t) {
        String id = m.id;
        if (m.seated) {
            ItemStack chair;
            try { chair = Sky.items().create(m.chair == null ? MinionManager.MINION_CHAIR : m.chair); }
            catch (Exception e) { chair = Icons.of(Material.OAK_STAIRS, "&fMinion Chair"); }
            menu.set(CHAIR, Icons.withLore(chair, List.of("", "&eThis minion is sitting down.", "&7It does not work and does not",
                    "&7count toward your minion limit.", "", "&eClick to stand it up!")), c -> {
                Placed cur = mgr().store(c.player()).placed.get(id);
                if (cur == null) return;
                String msg = mgr().takeOut(c.player(), cur, "chair");
                if (msg != null) Msg.send(c.player(), msg);
                c.menu().redraw();
            });
        } else if (mod.dials.chairs.get()) {
            menu.set(CHAIR, Icons.of(Material.OAK_STAIRS, "&aMinion Chair", "&7Seat this minion on a &fMinion Chair&7:",
                    "&7it stops working and no longer", "&7counts toward your minion limit.", "",
                    "&8Chairs are crafted (Carpentry I).", "", "&eClick a Minion Chair in your", "&einventory to seat it."));
        }
        if (m.freeWill) {
            menu.set(FREEWILL, Icons.glow(Icons.of(Material.BLAZE_POWDER, "&6Free Will: &aLoyal",
                    "&7This minion chose to stay with you.", "&a+10% &7speed, for good.", "&8It keeps it through pick-ups.")));
        } else if (mod.dials.freeWill.get()) {
            double ch = mgr().freeWillChance(m);
            menu.set(FREEWILL, Icons.of(Material.GRAY_DYE, "&6Free Will", "&7Offer it &6Free Will &7and it either",
                    "&astays &7(&a+10% &7speed for good) or", "&cleaves &7(you get its items and a &dPostcard&7).", "",
                    "&7Loyalty chance at tier " + Text.roman(m.tier) + ": &a" + MinionText.pct(ch), "",
                    "&eClick Free Will in your inventory", "&eto offer it."));
        }
    }

    private List<String> upgradeLines(String upId, TypeDef t) {
        UpgradeDef u = cat().upgrade(upId);
        if (u == null) return null;
        List<String> l = new ArrayList<>();
        if (!u.appliesTo(t)) l.add("&cDoes nothing for this minion!");
        if (u.speed > 0) l.add("&7Speed: &a+" + MinionText.pct(u.speed));
        if (u.compact != null) l.add("&7Compacts into " + ("block".equals(u.compact) ? "blocks" : "enchanted items"));
        if (Boolean.TRUE.equals(u.smelt)) l.add("&7Smelts the output");
        if (u.spread != null) l.add("&7Adds " + Text.strip(MinionText.itemName(u.spread.item)) + " now and then");
        if (u.output != null) l.add("&7Output: &cx" + u.output);
        if (u.perSecond != null && u.perSecond.every > 0)
            l.add("&7Makes &a" + Text.strip(MinionText.itemName(u.perSecond.item)) + " &7every &e" + Text.duration((long) u.perSecond.every));
        return l;
    }

    /**
     * Handlers run a tick after the click and production/compaction may have shuffled storage in between:
     * only collect if the slot still holds the item the player clicked.
     */
    static boolean sameSlot(Placed m, int index, ItemStack clicked) {
        if (index < 0 || index >= m.storage.size()) return false;
        if (clicked == null || clicked.getType().isAir()) return true;
        String id = Sky.items().idOf(clicked);
        return id == null || id.equals(m.storage.get(index).id);
    }

    private ItemStack storageIcon(Slot s) {
        ItemStack it;
        try {
            it = Sky.items().create(s.id, Math.max(1, Math.min(64, s.amount)));
        } catch (Exception e) {
            it = Icons.of(Material.PAPER, "&f" + s.id);
        }
        return Icons.withLore(it, List.of("", "&7Stored: &e" + s.amount, "&eClick to collect!"));
    }

    private void attachment(Menu menu, int slot, String itemId, Material empty, String title, List<String> emptyLore,
                            String which, String minionId, List<String> extra) {
        if (itemId == null) {
            menu.set(slot, Icons.of(empty, title, emptyLore));
            return;
        }
        ItemStack icon;
        try { icon = Sky.items().create(itemId); } catch (Exception e) { icon = Icons.of(Material.PAPER, "&f" + itemId); }
        List<String> add = new ArrayList<>();
        add.add("");
        if (extra != null) add.addAll(extra);
        add.add("&eClick to take out!");
        menu.set(slot, Icons.withLore(icon, add), c -> {
            Placed cur = mgr().store(c.player()).placed.get(minionId);
            if (cur == null) return;
            String msg = mgr().takeOut(c.player(), cur, which);
            if (msg != null) Msg.send(c.player(), msg);
            c.menu().redraw();
        });
    }

    private void drawFuel(Menu menu, Placed m, TypeDef t, MinionManager.Env env) {
        FuelDef f = mod.cat.fuel(m.fuel);
        if (f == null) {
            menu.set(FUEL, Icons.of(Material.ORANGE_STAINED_GLASS_PANE, "&aFuel",
                    "&7Fuel makes a minion faster, or", "&7multiplies what it makes.", "", "&eClick a fuel in your inventory", "&eto add it."));
            return;
        }
        ItemStack icon;
        try { icon = Sky.items().create(f.id, Math.max(1, Math.min(64, m.fuelCount))); } catch (Exception e) { icon = Icons.of(Material.COAL, "&f" + f.id); }
        List<String> l = new ArrayList<>();
        l.add("");
        if (!f.powers(t)) l.add("&cThis fuel does nothing here!");
        if (f.perSecond != null && f.perSecond.every > 0)
            l.add("&7Makes &a" + Text.strip(MinionText.itemName(f.perSecond.item)) + " &7every &e" + Text.duration((long) f.perSecond.every) + " &7of burn");
        if (f.speed > 0) l.add("&7Speed: &a+" + MinionText.pct(f.speed + f.categoryBonus.getOrDefault(t.category, 0.0)) + (f.solar ? " &8(daytime)" : ""));
        if (f.mult > 1) l.add("&7Output: &ax" + Text.commas(f.mult));
        if (f.specialty != null) l.add("&7Makes &e" + Text.strip(MinionText.itemName(f.specialty.item)) + " &7instead of most output");
        if (f.infinite()) l.add("&7Time left: &aUnlimited");
        else {
            l.add("&7Amount: &e" + m.fuelCount);
            l.add("&7Time left: &e" + Text.duration((long) MinionSim.fuelSecondsLeft(mod.cat, m)));
        }
        l.add("");
        if (f.infinite()) l.add("&eClick to take out!");
        else { l.add("&cTaking it out destroys it."); l.add("&eClick to take out!"); }
        String id = m.id;
        menu.set(FUEL, Icons.withLore(icon, l), c -> {
            Placed cur = mgr().store(c.player()).placed.get(id);
            if (cur == null || cur.fuel == null) return;
            FuelDef cf = mod.cat.fuel(cur.fuel);
            if (cf != null && !cf.infinite()) {
                Sky.menus().confirm(c.player(), "Destroy the fuel?", List.of("&7Timed fuel cannot be recovered.", "&cIt will be destroyed."),
                        () -> { Placed again = mgr().store(c.player()).placed.get(id); if (again != null) Msg.send(c.player(), mgr().takeOut(c.player(), again, "fuel")); openMinion(c.player(), id); },
                        () -> openMinion(c.player(), id));
            } else {
                String msg = mgr().takeOut(c.player(), cur, "fuel");
                if (msg != null) Msg.send(c.player(), msg);
                c.menu().redraw();
            }
        });
    }

    private void drawNextTier(Menu menu, Player viewer, Placed m, TypeDef t) {
        TierDef next = t.tier(m.tier + 1);
        if (next == null) {
            menu.set(NEXT, Icons.glow(Icons.of(Material.NETHER_STAR, "&6Maximum Tier", "&7This minion is as good as it gets.")));
            return;
        }
        TierDef cur = t.tier(m.tier);
        List<String> l = new ArrayList<>();
        l.add("&7Next: " + MinionText.name(t, next.tier));
        l.add("&7Time Between Actions: &8" + MinionText.seconds(cur.tba) + " &7-> &a" + MinionText.seconds(next.tba));
        l.add("&7Max Storage: &8" + cur.storage + " &7-> &e" + next.storage);
        l.add("");
        Recipe r = mgr().nextRecipe(m);
        String id = m.id;
        if (r == null) {
            l.add("&7This tier is not crafted:");
            l.add("&7trade for it at a minion smith or");
            l.add("&7use a &6tier XII upgrade stone&7.");
            menu.set(NEXT, Icons.of(Material.EMERALD, "&aNext Tier", l));
            return;
        }
        boolean can = true;
        l.add("&7Cost:");
        for (Map.Entry<String, Integer> e : mgr().upgradeCost(r, t.itemId(m.tier)).entrySet()) {
            int have = viewer == null ? 0 : mgr().have(viewer, e.getKey());
            boolean ok = have >= e.getValue();
            if (!ok) can = false;
            l.add((ok ? "&a" : "&c") + " " + Text.commas(e.getValue()) + "x &f" + Text.strip(MinionText.itemName(e.getKey())) + " &8(" + Text.commas(have) + ")");
        }
        boolean unlocked = viewer == null || Sky.recipes().unlocked(viewer, r);
        l.add("");
        if (!unlocked) l.add("&cRequires " + Sky.requirements().describe(r.unlock));
        else l.add(can ? "&eClick to upgrade!" : "&cYou don't have the materials.");
        menu.set(NEXT, Icons.of(Material.DIAMOND, "&aQuick Upgrade", l), c -> {
            Placed now = mgr().store(c.player()).placed.get(id);
            if (now == null) return;
            String msg = mgr().quickUpgrade(c.player(), now);
            if (msg != null && !msg.isEmpty()) Msg.send(c.player(), msg);
            if (msg != null && msg.startsWith("&aYour minion is now")) openMinion(c.player(), id);
            else c.menu().redraw();
        });
    }

    // =====================================================================================================
    // Chest storage
    // =====================================================================================================

    void openChest(Player p, String minionId) {
        Menu menu = new Menu("Minion Chest Storage", 6);
        menu.drawer(mn -> {
            Placed m = mgr().store(p).placed.get(minionId);
            mn.chrome();
            if (m == null) return;
            int own = MinionSim.ownSlots(mod.cat, m);
            int extra = MinionSim.chestSlots(mod.cat, m);
            for (int i = 0; i < INNER.length; i++) {
                int idx = own + i;
                if (i >= extra) { mn.set(INNER[i], Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&8No slot")); continue; }
                if (idx >= m.storage.size()) { mn.set(INNER[i], null); continue; }
                Slot s = m.storage.get(idx);
                mn.set(INNER[i], storageIcon(s), c -> {
                    Placed cur = mgr().store(c.player()).placed.get(minionId);
                    if (cur == null) return;
                    if (!mgr().canManage(c.player(), cur)) { Msg.error(c.player(), "Visit your island to collect from this minion!"); return; }
                    if (!sameSlot(cur, idx, c.current())) { c.menu().redraw(); return; }
                    mgr().collectSlot(c.player(), cur, idx);
                    c.menu().redraw();
                });
            }
            mn.set(48, Icons.back("Minion"), c -> openMinion(c.player(), minionId));
            mn.set(50, Icons.of(Material.CHEST, "&aCollect All", "&eClick to collect everything!"), c -> {
                Placed cur = mgr().store(c.player()).placed.get(minionId);
                if (cur == null) return;
                if (!mgr().canManage(c.player(), cur)) { Msg.error(c.player(), "Visit your island to collect from this minion!"); return; }
                mgr().collectAll(c.player(), cur, false);
                c.menu().redraw();
            });
        });
        menu.refreshEvery(20);
        menu.open(p);
    }

    // =====================================================================================================
    // Ideal layout
    // =====================================================================================================

    void openLayout(Player p, String minionId) {
        Placed m = mgr().store(p).placed.get(minionId);
        if (m == null) return;
        TypeDef t = mod.cat.type(m.type);
        Menu menu = new Menu("Ideal Layout", 6);
        menu.chrome();
        World w = Bukkit.getWorld(m.world);
        boolean loaded = w != null && w.isChunkLoaded(m.x >> 4, m.z >> 4);
        int r = mod.visuals.radius(m);
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int slot = (dz + 2) * 9 + (dx + 2) + 2;
                if (dx == 0 && dz == 0) {
                    ItemDef def = Sky.items().def(t.itemId(m.tier));
                    menu.set(slot, Icons.skull(def == null ? null : def.skull, MinionText.name(t, m.tier), List.of("&7The minion stands here.")));
                    continue;
                }
                if (!loaded) { menu.set(slot, Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&7Unknown", "&8Stand near the minion to check.")); continue; }
                Block cell = w.getBlockAt(m.x + dx, m.y, m.z + dz);
                String[] chestTag = mgr().chestTag(cell);
                if (chestTag != null && chestTag[1].equals(m.id)) {
                    menu.set(slot, Icons.of(Material.CHEST, "&6Its Storage Chest", "&7The minion's attached storage.",
                            "&7It takes this cell, like in a real", "&7island layout.", "", "&8Click the chest block to open it."));
                    continue;
                }
                Block below = cell.getRelative(0, -1, 0);
                boolean ok = cellOk(cell, m, t);
                List<String> l = new ArrayList<>();
                l.add("&7Cell: &f" + Text.titleCase(cell.getType().name()));
                l.add("&7Under it: &f" + Text.titleCase(below.getType().name()));
                l.add("");
                l.add(ok ? "&aThe minion can work here." : "&cThe minion cannot use this cell.");
                menu.set(slot, Icons.of(ok ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE, ok ? "&aUsable" : "&cBlocked", l));
            }
        }
        List<String> need = new ArrayList<>();
        need.add("&7A " + t.name + " Minion works the " + (2 * r + 1) + "x" + (2 * r + 1) + " area");
        need.add("&7around it, at its own height.");
        need.add("");
        switch (t.kind) {
            case "MOB" -> { need.add("&7Open space, two blocks high,"); need.add("&7on solid ground, for its mobs."); }
            case "WATER" -> need.add("&7Water in the area around it.");
            case "TREE" -> { need.addAll(MinionVisuals.describeFloor(t.floor)); need.add("&7and four blocks of air above."); }
            default -> need.addAll(MinionVisuals.describeFloor(t.floor));
        }
        if (r > 2) { need.add(""); need.add("&8Expanders widen it to " + (2 * r + 1) + "x" + (2 * r + 1) + "; only the"); need.add("&8inner 5x5 is shown."); }
        menu.set(8, Icons.of(Material.BOOK, "&aWhat it needs", need));
        menu.set(48, Icons.back("Minion"), c -> openMinion(c.player(), minionId));
        menu.open(p);
    }

    private boolean cellOk(Block cell, Placed m, TypeDef t) {
        return switch (t.kind) {
            case "WATER" -> cell.getType() == Material.WATER || cell.getRelative(0, -1, 0).getType() == Material.WATER;
            case "MOB" -> cell.isPassable() && !cell.isLiquid() && cell.getRelative(0, 1, 0).isEmpty()
                    && ("NONE".equals(t.floor) || cell.getRelative(0, -1, 0).getType().isSolid());
            default -> (cell.isEmpty() || mod.visuals.isVisual(cell, t)) && mod.visuals.floorOk(t.floor, cell);
        };
    }

    // =====================================================================================================
    // Your Minions (overview)
    // =====================================================================================================

    void openOverview(Player p, int page) {
        if (!Sky.profiles().isReady(p)) return;
        MinionStore s = mgr().store(p);
        List<Placed> list = new ArrayList<>(s.placed.values());
        list.sort(Comparator.comparing((Placed x) -> x.type).thenComparing(x -> -x.tier));
        MinionManager.Env env = mgr().env(p);
        Menu menu = Sky.menus().paged("Your Minions", list, m -> overviewIcon(p, m, env), pc -> {
            Placed m = mgr().store(pc.click().player()).placed.get(pc.entry().id);
            if (m == null) return;
            if (!mgr().canManage(pc.click().player(), m)) {
                Msg.error(pc.click().player(), "Visit your island to manage this minion! (/is)");
                return;
            }
            openMinion(pc.click().player(), m.id);
        }, mn -> decorateOverview(mn, p), page);
        menu.open(p);
    }

    private ItemStack overviewIcon(Player p, Placed m, MinionManager.Env env) {
        TypeDef t = mod.cat.type(m.type);
        if (t == null) return Icons.of(Material.BARRIER, "&cUnknown minion " + m.type);
        ItemDef def = Sky.items().def(t.itemId(m.tier));
        int cap = MinionSim.capacity(mod.cat, m);
        boolean full = !m.seated && MinionSim.isFull(mod.cat, m, t, cap, env);
        List<String> l = new ArrayList<>();
        l.add("&7Status: " + (m.seated ? "&eResting on a chair" : full ? "&cStorage full" : "&aWorking"));
        if (m.freeWill) l.add("&6Free Will: &aLoyal");
        l.add("&7Storage: &e" + m.storage.size() + "&7/&e" + cap + " &7slots &8(" + Text.commas(m.stored()) + " items)");
        FuelDef f = mod.cat.fuel(m.fuel);
        l.add("&7Fuel: " + (f == null ? "&8none" : "&f" + Text.strip(MinionText.itemName(f.id))
                + (f.infinite() ? "" : " &8(" + Text.duration((long) MinionSim.fuelSecondsLeft(mod.cat, m)) + ")")));
        l.add("&7Upgrades: " + (m.upgrade1 == null && m.upgrade2 == null ? "&8none"
                : "&f" + (m.upgrade1 == null ? "" : Text.strip(MinionText.itemName(m.upgrade1))) + (m.upgrade1 != null && m.upgrade2 != null ? ", " : "")
                + (m.upgrade2 == null ? "" : Text.strip(MinionText.itemName(m.upgrade2)))));
        if (m.shipping != null) l.add("&7Shipping: &f" + Text.strip(MinionText.itemName(m.shipping)) + " &6(" + Text.commasInt(m.heldCoins) + " coins)");
        if (m.infused) l.add("&eMithril Infused");
        l.add("&7Generated: &b" + Text.commas(m.generated));
        l.add("&8" + MinionText.where(m));
        l.add("");
        l.add(mgr().canManage(p, m) ? "&eClick to manage!" : "&7Visit your island to manage.");
        ItemStack it = Icons.skull(def == null ? null : def.skull, MinionText.name(t, m.tier), l);
        return full ? Icons.glow(it) : it;
    }

    private void decorateOverview(Menu mn, Player p) {
        MinionStore s = mgr().store(p);
        int slots = mgr().slotsFor(p, s);
        int uniques = s.crafted.size();
        int next = mod.cat.nextSlotAt(uniques);
        List<String> h = new ArrayList<>();
        h.add("&7Placed: &a" + s.working() + "&7/&a" + slots);
        h.add("&7Unique minions crafted: &e" + uniques);
        if (next > 0) h.add("&7Next slot at &e" + next + " &7uniques &8(" + (next - uniques) + " to go)");
        int cs = mgr().communitySlots(p);
        if (cs > 0) h.add("&7Community Shop slots: &a+" + cs);
        Crystal beacon = mgr().beacon(s);
        if (beacon != null) {
            long left = s.beaconPoweredUntil - System.currentTimeMillis();
            h.add("&7Beacon: " + (left > 0 ? "&bpowered &8(" + Text.duration(left / 1000) + ")" : "&cunpowered"));
        }
        h.add("");
        h.add("&7Lifetime production: &b" + Text.commas(s.totalGenerated));
        if (s.totalHopperCoins > 0) h.add("&7Lifetime hopper sales: &6" + Text.commasInt(s.totalHopperCoins));
        if (s.placed.isEmpty()) { h.add(""); h.add("&eRight-click a minion on your island"); h.add("&eto place it!"); }
        mn.set(4, Icons.of(Material.PLAYER_HEAD, "&aYour Minions", h));
        mn.set(46, Icons.of(Material.CRAFTING_TABLE, "&aCrafted Minions", "&7Every minion type and the tiers", "&7you have crafted.", "", "&eClick to view!"),
                c -> openCrafted(c.player(), "ALL", 0));
        mn.set(47, Icons.of(Material.ARMOR_STAND, "&aMinion Slots", "&7How many minions you can place,", "&7and how to get more.", "", "&eClick to view!"),
                c -> openSlots(c.player()));
        mn.set(48, Icons.back("SkyBlock Menu"), c -> {
            if (Sky.has(Features.SkyMenu.class)) Sky.service(Features.SkyMenu.class).open(c.player());
            else c.player().closeInventory();
        });
        mn.set(50, Icons.of(Material.CHEST_MINECART, "&aCollect From All", "&7Collect from every minion you can", "&7reach right now.", "", "&eClick to collect!"), c -> {
            Player pl = c.player();
            int total = 0, skipped = 0;
            for (Placed m : new ArrayList<>(mgr().store(pl).placed.values())) {
                if (!mgr().canManage(pl, m)) { skipped++; continue; }
                total += mgr().collectAll(pl, m, true);
            }
            if (total > 0) Msg.success(pl, "Collected " + Text.commas(total) + " items from your minions.");
            else Msg.send(pl, "&7Nothing to collect" + (skipped > 0 ? " here &8(" + skipped + " minions are on your island)" : "") + ".");
            openOverview(pl, 0);
        });
        mn.set(51, Icons.of(Material.ENCHANTED_BOOK, "&aFuels & Upgrades", "&7Everything you can put in a minion,", "&7and what it does.", "", "&eClick to browse!"),
                c -> openGear(c.player(), 0));
        mn.set(52, Icons.of(Material.AMETHYST_CLUSTER, "&dIsland Crystals & Beacon", "&7Crystals and a powered Beacon on", "&7your island speed up your minions.", "", "&eClick to view!"),
                c -> openCrystals(c.player()));
    }

    // =====================================================================================================
    // Crafted minions / guide
    // =====================================================================================================

    void openCrafted(Player p, String category, int page) {
        if (!Sky.profiles().isReady(p)) return;
        List<TypeDef> types = new ArrayList<>();
        for (TypeDef t : mod.cat.types()) if ("ALL".equals(category) || category.equals(t.category)) types.add(t);
        MinionStore s = mgr().store(p);
        Menu menu = Sky.menus().paged("Crafted Minions", types, t -> craftedIcon(p, s, t), pc -> openType(pc.click().player(), pc.entry().id), mn -> {
            MinionStore st = mgr().store(p);
            int uniques = st.crafted.size();
            int total = 0;
            for (TypeDef t : mod.cat.types()) total += t.tiers.size();
            mn.set(4, Icons.of(Material.CRAFTING_TABLE, "&aCrafted Minions", "&7Unique minions crafted: &e" + uniques + "&7/&e" + total,
                    Text.bar(uniques / (double) Math.max(1, total), 20), "", "&7Each new unique tier counts toward", "&7more minion slots and SkyBlock XP."));
            int idx = 0;
            for (int i = 0; i < CATEGORIES.length; i++) if (CATEGORIES[i].equals(category)) idx = i;
            String next = CATEGORIES[(idx + 1) % CATEGORIES.length];
            List<String> fl = new ArrayList<>();
            for (String c : CATEGORIES) fl.add((c.equals(category) ? "&a> " : "&7  ") + ("ALL".equals(c) ? "All" : Text.titleCase(c)));
            fl.add("");
            fl.add("&eClick to switch!");
            mn.set(50, Icons.of(Material.HOPPER, "&aFilter", fl), c -> openCrafted(c.player(), next, 0));
            mn.set(48, Icons.back("Your Minions"), c -> openOverview(c.player(), 0));
        }, page);
        menu.open(p);
    }

    private ItemStack craftedIcon(Player p, MinionStore s, TypeDef t) {
        int best = 0;
        StringBuilder tiers = new StringBuilder();
        int n = 0;
        for (TierDef td : t.tiers) {
            boolean has = s.crafted.contains(MinionStore.uniqueKey(t.id, td.tier));
            if (has) { best = td.tier; n++; }
            tiers.append(has ? "&a" : "&c").append(Text.roman(td.tier)).append(' ');
        }
        ItemDef def = Sky.items().def(t.itemId(Math.max(1, best == 0 ? t.lowestTier() : best)));
        List<String> l = new ArrayList<>();
        l.add("&7Category: " + MinionText.category(t.category));
        l.add("&7Crafted: &e" + n + "&7/&e" + t.tiers.size());
        l.add(tiers.toString().trim());
        l.add("");
        Map<String, Double> rate = MinionSim.perHour(t, Math.max(t.lowestTier(), best), 0, 1);
        l.add("&7Makes (tier " + Text.roman(Math.max(t.lowestTier(), best)) + ", no fuel):");
        int shown = 0;
        for (Map.Entry<String, Double> e : rate.entrySet()) {
            if (shown++ >= 4) { l.add("&8 ...and more"); break; }
            l.add("&f " + Text.strip(MinionText.itemName(e.getKey())) + " &7x&e" + MinionText.rate(e.getValue()) + "&7/h");
        }
        l.add("");
        if (t.unlock != null) {
            boolean ok = Sky.requirements().check(p, t.unlock);
            l.add((ok ? "&aRecipes unlocked" : "&cRecipes locked: ") + (ok ? "" : Sky.requirements().describe(t.unlock)));
        }
        l.add("&eClick to view every tier!");
        ItemStack it = Icons.skull(def == null ? null : def.skull, (n == 0 ? "&7" : n == t.tiers.size() ? "&6" : "&a") + t.name + " Minion", l);
        return n == t.tiers.size() ? Icons.glow(it) : it;
    }

    void openType(Player p, String typeId) {
        TypeDef t = mod.cat.type(typeId);
        if (t == null || !Sky.profiles().isReady(p)) return;
        MinionStore s = mgr().store(p);
        Menu menu = new Menu(t.name + " Minion", 6);
        menu.chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        for (TierDef td : t.tiers) {
            if (i >= slots.length) break;
            String itemId = t.itemId(td.tier);
            ItemDef def = Sky.items().def(itemId);
            boolean has = s.crafted.contains(MinionStore.uniqueKey(t.id, td.tier));
            List<String> l = new ArrayList<>();
            l.add("&7Time Between Actions: &a" + MinionText.seconds(td.tba));
            l.add("&7Max Storage: &e" + td.storage + " &8(" + td.slots() + " slots)");
            l.add("");
            l.add("&7Per hour, no fuel:");
            for (Map.Entry<String, Double> e : MinionSim.perHour(t, td.tier, 0, 1).entrySet())
                l.add("&f " + Text.strip(MinionText.itemName(e.getKey())) + " &7x&e" + MinionText.rate(e.getValue()));
            l.add("");
            List<Recipe> rs = Sky.recipes().producing(itemId);
            Recipe r = rs.stream().filter(x -> x.type == Recipe.Type.CRAFTING).findFirst().orElse(null);
            if (r == null) l.add("&7Obtained by trading at an NPC.");
            else {
                Map<String, Integer> cost = new LinkedHashMap<>();
                for (String c : r.grid) {
                    if (c == null || c.isEmpty()) continue;
                    cost.merge(MinionManager.cellId(c), MinionManager.cellAmount(c), Integer::sum);
                }
                l.add("&7Recipe:");
                for (Map.Entry<String, Integer> e : cost.entrySet())
                    l.add("&8 " + Text.commas(e.getValue()) + "x &7" + Text.strip(MinionText.itemName(e.getKey())));
            }
            l.add("");
            l.add(has ? "&aYou have crafted this tier!" : "&cNot crafted yet.");
            l.add("&eClick to view the recipe!");
            ItemStack it = Icons.skull(def == null ? null : def.skull, MinionText.name(t, td.tier), l);
            menu.set(slots[i++], has ? Icons.glow(it) : it, c -> {
                if (!Sky.commands().run(c.player(), "recipe", itemId)) Msg.send(c.player(), "&7The recipe book is not available.");
            });
        }
        List<String> drops = new ArrayList<>();
        drops.add("&7Each harvest yields on average:");
        for (Drop d : t.drops) {
            MinionCatalog.XpEntry xp = mod.cat.xp.get(d.item);
            drops.add("&f " + Text.strip(MinionText.itemName(d.item)) + " &7x&e" + Text.commas(Math.round(d.avg * 1000) / 1000.0)
                    + (xp != null && xp.xp > 0 ? " &8(" + Text.commas(xp.xp) + " " + Text.titleCase(xp.skill) + " XP)" : ""));
        }
        drops.add("");
        drops.add("&7One harvest every &a2 &7actions.");
        drops.add("&8XP is paid when you collect.");
        menu.set(31, Icons.of(Material.HOPPER, "&aDrops", drops));
        menu.set(30, Icons.of(Material.OAK_SIGN, "&aUnlock", "&7Recipes unlocked by:",
                t.unlock == null ? "&8nothing" : Sky.requirements().describe(t.unlock),
                t.unlock == null ? "" : (Sky.requirements().check(p, t.unlock) ? "&aUnlocked!" : "&cLocked")));
        List<String> needs = new ArrayList<>();
        switch (t.kind) {
            case "MOB" -> needs.add("&7Open, solid ground for its mobs.");
            case "WATER" -> needs.add("&7Water nearby.");
            default -> needs.addAll(MinionVisuals.describeFloor(t.floor));
        }
        menu.set(32, Icons.of(Material.GRASS_BLOCK, "&aWhere to place it", needs));
        menu.set(48, Icons.back("Crafted Minions"), c -> openCrafted(c.player(), "ALL", 0));
        menu.open(p);
    }

    // =====================================================================================================
    // Slots
    // =====================================================================================================

    void openSlots(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        MinionStore s = mgr().store(p);
        int uniques = s.crafted.size();
        Menu menu = new Menu("Minion Slots", 6);
        menu.chrome();
        int i = 0;
        for (MinionCatalog.SlotRow r : mod.cat.slots.table) {
            if (i >= INNER.length) break;
            boolean done = uniques >= r.uniques;
            boolean next = !done && (i == 0 || uniques >= mod.cat.slots.table.get(i - 1).uniques);
            List<String> l = new ArrayList<>();
            l.add("&7Placeable minions: &e" + r.minions);
            l.add("&7Needs &e" + r.uniques + " &7unique minions.");
            if (!done) {
                l.add("");
                l.add(Text.bar(uniques / (double) Math.max(1, r.uniques), 20) + " &e" + uniques + "&7/&e" + r.uniques);
            }
            Material mat = done ? Material.LIME_STAINED_GLASS_PANE : next ? Material.YELLOW_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            menu.set(INNER[i++], Icons.amount(Icons.of(mat, (done ? "&a" : next ? "&e" : "&c") + "Slot Tier " + Text.roman(r.tier), l), r.minions));
        }
        int cs = mgr().communitySlots(p);
        menu.set(4, Icons.of(Material.ARMOR_STAND, "&aMinion Slots",
                "&7You can place &a" + mgr().slotsFor(p, s) + " &7minions.", "",
                "&7From unique crafts: &e" + mod.cat.placeableFor(uniques),
                "&7From the Community Shop: &e+" + cs + " &8(max +" + mod.cat.slots.communityShopMax + ")",
                mod.dials.bonusSlots.get() != 0 ? "&7Server bonus: &e+" + mod.dials.bonusSlots.get() : "",
                "", "&7Craft minion tiers you have never", "&7crafted before to unlock more."));
        menu.set(48, Icons.back("Your Minions"), c -> openOverview(c.player(), 0));
        menu.open(p);
    }

    // =====================================================================================================
    // Fuels & upgrades guide
    // =====================================================================================================

    void openGear(Player p, int page) {
        List<String> ids = new ArrayList<>();
        for (FuelDef f : mod.cat.fuels.values()) if (Sky.items().exists(f.id)) ids.add(f.id);
        for (UpgradeDef u : mod.cat.upgrades.values())
            if (!"skin".equals(u.slot) && !"stone".equals(u.slot) && Sky.items().exists(u.id)) ids.add(u.id);
        Menu menu = Sky.menus().paged("Fuels & Upgrades", ids, this::gearIcon, pc -> {
            if (!Sky.commands().run(pc.click().player(), "recipe", pc.entry())) Msg.send(pc.click().player(), "&7No recipe to show.");
        }, mn -> mn.set(48, Icons.back("Your Minions"), c -> openOverview(c.player(), 0)), page);
        menu.open(p);
    }

    private ItemStack gearIcon(String id) {
        ItemStack it;
        try { it = Sky.items().create(id); } catch (Exception e) { return Icons.of(Material.PAPER, id); }
        List<String> l = new ArrayList<>();
        l.add("");
        FuelDef f = mod.cat.fuel(id);
        UpgradeDef u = mod.cat.upgrade(id);
        if (f != null) {
            l.add("&6Minion Fuel");
            if (f.speed > 0) l.add("&7Speed: &a+" + MinionText.pct(f.speed));
            if (f.mult > 1) l.add("&7Output: &ax" + Text.commas(f.mult));
            l.add("&7Lasts: " + (f.infinite() ? "&aforever" : "&e" + Text.duration(f.seconds)));
            if (f.onlyType != null) l.add("&7Only for: &c" + mod.cat.type(f.onlyType).name + " Minion");
            if (f.onlyCategory != null) l.add("&7Only for: " + MinionText.category(f.onlyCategory) + " &7minions");
            if (f.perSecond != null && f.perSecond.every > 0)
                l.add("&7Makes &a" + Text.strip(MinionText.itemName(f.perSecond.item)) + " &7every &e" + Text.duration((long) f.perSecond.every));
            if (f.onePerProfile) l.add("&cOne per island.");
        } else if (u != null) {
            l.add("&6" + switch (u.slot) {
                case "shipping" -> "Automated Shipping";
                case "chest" -> "Minion Storage";
                case "crystal" -> "Island Crystal";
                case "beacon" -> "Island Beacon";
                case "power" -> "Beacon Power";
                case "infusion" -> "Permanent Infusion";
                case "freewill" -> "Minion Loyalty (a chance!)";
                default -> "Minion Upgrade";
            });
            if ("power".equals(u.slot)) {
                l.add("&7Powers your Beacon for &e" + Text.duration(u.seconds));
                if (u.speed > 0) l.add("&7Extra minion speed: &a+" + MinionText.pct(u.speed));
            } else if (u.speed > 0) l.add("&7Speed: &a+" + MinionText.pct(u.speed) + ("beacon".equals(u.slot) ? " &8(while powered)" : ""));
            if (u.slots > 0) l.add("&7Storage: &e+" + u.slots + " slots");
            if (u.sell > 0) l.add("&7Sells overflow at &6" + Math.round(u.sell * 100) + "%");
            if (u.types != null && !u.types.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (String tt : u.types) { TypeDef td = mod.cat.type(tt); names.add(td == null ? tt : td.name); }
                l.add("&7Only for: &e" + String.join(", ", names));
            }
            if (u.categories != null && !u.categories.isEmpty()) l.add("&7Only for: &e" + Text.titleCase(String.join(", ", u.categories)) + " minions");
            if (u.requirement != null) l.add("&7Requires " + Sky.requirements().describe(u.requirement));
            if (u.onePerProfile) l.add("&cOne per island.");
        }
        l.add("");
        l.add("&eClick to view the recipe!");
        return Icons.withLore(it, l);
    }

    // =====================================================================================================
    // Crystals
    // =====================================================================================================

    void openCrystals(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        MinionStore s = mgr().store(p);
        Menu menu = new Menu("Island Crystals", 5);
        menu.chrome();
        menu.set(4, Icons.of(Material.AMETHYST_CLUSTER, "&dIsland Crystals",
                "&7Crystals and a Beacon placed on your", "&7island speed up your minions there.",
                "", "&7Right-click a block on your island", "&7with one to place it."));
        int i = 0;
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        int b = 0;
        int[] beaconSlots = {20, 21, 22, 23, 24};
        for (UpgradeDef u : mod.cat.upgrades.values()) {
            boolean beacon = "beacon".equals(u.slot);
            if (!"crystal".equals(u.slot) && !beacon) continue;
            if (beacon ? b >= beaconSlots.length : i >= slots.length) continue;
            Crystal placed = null;
            for (Crystal c : s.crystals.values()) if (c.item.equals(u.id)) placed = c;
            ItemStack it;
            try { it = Sky.items().create(u.id); } catch (Exception e) { it = Icons.of(Material.AMETHYST_CLUSTER, u.id); }
            List<String> l = new ArrayList<>();
            l.add("");
            l.add("&7Minion speed: &a+" + MinionText.pct(u.speed) + (beacon ? " &8(while powered)" : ""));
            if (u.requirement != null) l.add("&7Requires " + Sky.requirements().describe(u.requirement));
            if (MinionManager.WINTER_CRYSTAL.equals(u.id) && mod.dials.winterPresent.get()) {
                l.add("&7Once every SkyBlock year a &cpresent");
                l.add("&7appears somewhere near it.");
                int year;
                try { year = Sky.calendar().now().year(); } catch (Throwable t) { year = Integer.MAX_VALUE; }
                l.add(s.presentWorld != null ? "&aA present is waiting on your island!"
                        : placed == null ? "&8Place it to start receiving them."
                        : s.presentYear >= year ? "&8Next present: the next SkyBlock year." : "&8A present will appear soon.");
            }
            l.add("");
            if (placed != null) {
                l.add("&aPlaced at " + placed.x + ", " + placed.y + ", " + placed.z);
                l.add(beacon ? "&eClick to manage!" : "&eClick to pick it up!");
            } else {
                l.add("&7Not placed.");
                l.add("&8Right-click a block on your island");
                l.add("&8with it to place it.");
            }
            final String cid = placed == null ? null : placed.id;
            int slot = beacon ? beaconSlots[b++] : slots[i++];
            menu.set(slot, placed != null ? Icons.glow(Icons.withLore(it, l)) : Icons.withLore(it, l), c -> {
                if (cid == null) return;
                if (beacon) { openBeacon(c.player(), cid); return; }
                Sky.menus().confirm(c.player(), "Pick up crystal?", List.of("&7Your minions lose its bonus."),
                        () -> { mgr().pickupCrystal(c.player(), cid); openCrystals(c.player()); }, () -> openCrystals(c.player()));
            });
        }
        menu.set(31, beaconPowerIcon(s, false));
        menu.set(menu.closeSlot() - 1, Icons.back("Your Minions"), c -> openOverview(c.player(), 0));
        menu.open(p);
    }

    /** What the Beacon's power looks like right now (an icon, used by two menus). */
    private ItemStack beaconPowerIcon(MinionStore s, boolean clickHint) {
        long now = System.currentTimeMillis();
        boolean powered = now < s.beaconPoweredUntil;
        List<String> l = new ArrayList<>();
        if (powered) {
            l.add("&7Powered for &e" + Text.duration((s.beaconPoweredUntil - now) / 1000));
            if (s.beaconScorched) l.add("&6Scorched: &a+" + MinionText.pct(mgr().scorchedBonus()) + " &7extra minion speed");
            l.add("");
            l.add("&8Power drains in real time, even");
            l.add("&8while you are offline.");
        } else {
            l.add("&cNot powered.");
            l.add("");
            l.add("&7A Power Crystal runs a Beacon for");
            l.add("&e48 hours&7. Scorched ones add &a+1%&7.");
            if (clickHint) { l.add(""); l.add("&eClick a Power Crystal in your"); l.add("&einventory to put it in!"); }
        }
        return Icons.of(powered ? Material.NETHER_STAR : Material.GRAY_DYE, powered ? "&bBeacon Power" : "&7Beacon Power", l);
    }

    void openCrystal(Player p, String crystalId) {
        MinionStore s = mgr().store(p);
        Crystal c = s.crystals.get(crystalId);
        if (c == null) return;
        UpgradeDef u = mod.cat.upgrade(c.item);
        if (u != null && "beacon".equals(u.slot)) { openBeacon(p, crystalId); return; }
        Sky.menus().confirm(p, "Pick up crystal?", List.of("&7Pick up " + MinionText.itemName(c.item) + "&7?",
                "&7Your minions lose its bonus."), () -> mgr().pickupCrystal(p, crystalId), null);
    }

    /** The Beacon: its tier, its power, a slot for Power Crystals, pick up. */
    void openBeacon(Player p, String crystalId) {
        Menu menu = new Menu("Beacon", 5);
        menu.drawer(mn -> {
            mn.chrome();
            MinionStore s = mgr().store(p);
            Crystal c = s.crystals.get(crystalId);
            if (c == null) return;
            UpgradeDef u = mod.cat.upgrade(c.item);
            boolean powered = System.currentTimeMillis() < s.beaconPoweredUntil;
            ItemStack it;
            try { it = Sky.items().create(c.item); } catch (Exception e) { it = Icons.of(Material.BEACON, "&bBeacon"); }
            double sp = u == null ? 0 : u.speed;
            mn.set(13, Icons.withLore(it, List.of("", "&7Every minion on your island:",
                    "&a+" + MinionText.pct(sp + (powered && s.beaconScorched ? mgr().scorchedBonus() : 0)) + " &7speed"
                            + (powered ? "" : " &8(once powered)"),
                    "", "&8Higher tiers are made at the Forge.")));
            mn.set(22, beaconPowerIcon(s, true));
            mn.set(24, beaconStatIcon(s, u), cl -> {
                Msg.send(cl.player(), mgr().cycleBeaconStat(cl.player()));
                Msg.sound(cl.player(), org.bukkit.Sound.UI_BUTTON_CLICK, 1.2f);
                cl.menu().redraw();
            });
            mn.set(31, Icons.of(Material.BEDROCK, "&aPick Up Beacon", "&7Its power stays with your island,",
                    "&7so a higher tier can take over.", "", "&eClick to pick up!"), cl ->
                    Sky.menus().confirm(cl.player(), "Pick up the Beacon?", List.of("&7Your minions lose its bonus", "&7until you place one again."),
                            () -> { mgr().pickupCrystal(cl.player(), crystalId); openCrystals(cl.player()); },
                            () -> openBeacon(cl.player(), crystalId)));
            mn.set(mn.closeSlot() - 1, Icons.back("Island Crystals"), cl -> openCrystals(cl.player()));
        });
        menu.refreshEvery(20);
        menu.onPlayerInventoryClick((cl, item) -> {
            if (item == null || item.getType().isAir()) return;
            String id = Sky.items().idOf(item);
            UpgradeDef u = mod.cat.upgrade(id);
            if (u == null || !"power".equals(u.slot)) { Msg.error(cl.player(), "Only Power Crystals power a Beacon."); return; }
            Msg.send(cl.player(), mgr().powerBeacon(cl.player(), id));
            cl.menu().redraw();
        });
        menu.open(p);
    }

    /** The Beacon's profile stat: what it boosts, by how much, and the cycle hint. */
    private ItemStack beaconStatIcon(MinionStore s, UpgradeDef beacon) {
        com.skyclone.api.stat.Stat cur = MinionManager.beaconStatOf(s);
        long now = System.currentTimeMillis();
        double v = mgr().beaconStatValue(s, now);
        List<String> l = new ArrayList<>();
        l.add("&7While powered, every member of");
        l.add("&7this profile gets a stat boost,");
        l.add("&7wherever they are.");
        l.add("");
        for (com.skyclone.api.stat.Stat st : MinionManager.BEACON_STATS) {
            boolean sel = st == cur;
            l.add((sel ? "&a▶ " : "&8  ") + st.label() + (sel ? " &a(selected)" : ""));
        }
        l.add("");
        if (cur == null) l.add("&cNo stat chosen yet!");
        else if (v > 0) l.add("&7Now: &a+" + Text.commas(v) + " " + cur.label());
        else l.add("&7Now: &cnothing &8(" + (now < s.beaconPoweredUntil ? "no Beacon" : "unpowered") + ")");
        if (beacon != null && s.beaconScorched && now < s.beaconPoweredUntil)
            l.add("&6Scorched: &7stat boost x" + Text.commas(mod.dials.beaconScorchedStat.get()));
        l.add("");
        l.add("&eClick to switch stat!");
        return Icons.of(Material.ENCHANTED_BOOK, "&bProfile Stat Boost", l);
    }

    /** Menu-click helper for code that only has the click. */
    static Player who(MenuClick c) { return c.player(); }
}
