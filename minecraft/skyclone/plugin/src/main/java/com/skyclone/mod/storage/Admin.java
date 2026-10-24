package com.skyclone.mod.storage;

import com.google.gson.JsonPrimitive;
import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.skyclone.mod.storage.StorageModule.tell;

/** /sb storage ... : every action takes a player name so the integrator can drive it over RCON. */
final class Admin {

    private static final List<String> SUBS = List.of("info", "open", "sack", "givesack", "placebp", "absorb", "wardrobe", "gfs",
            "scan", "missing", "unlocks", "reset", "recipes", "eqwardrobe", "dupesrc");

    private final StorageModule mod;

    Admin(StorageModule mod) { this.mod = mod; }

    void register() {
        Sky.commands().admin("storage", "<" + String.join("|", SUBS) + "> <player> ...", "Storage, sacks, wardrobe and bags admin tools",
                this::run, (s, a) -> {
                    if (a.length <= 1) return SUBS;
                    if (a.length == 2 && !a[0].equals("missing") && !a[0].equals("recipes")) {
                        List<String> names = new ArrayList<>();
                        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                        return names;
                    }
                    if (a.length == 3 && a[0].equals("open")) return List.of("storage", "ec", "bp", "wardrobe", "eqwardrobe", "sacks", "sackofsacks", "quiver", "vault", "bags", "shop");
                    if (a.length == 3 && a[0].equals("sack")) return List.of("add", "take", "list", "clear");
                    if (a.length == 3 && a[0].equals("givesack")) return new ArrayList<>(mod.catalog().sacks.keySet());
                    if (a.length == 3 && a[0].equals("placebp")) return new ArrayList<>(mod.catalog().backpackSlots.keySet());
                    if (a.length == 3 && a[0].equals("wardrobe")) return List.of("equip", "unequip", "list");
                    if (a.length == 3 && a[0].equals("eqwardrobe")) return List.of("equip", "unequip", "take", "store", "list");
                    if (a.length == 3 && a[0].equals("reset")) return List.of("ender", "backpacks", "wardrobe", "sacks", "bags");
                    return List.of();
                });
    }

    private void run(CommandSender s, String[] a) {
        if (a.length == 0) { usage(s); return; }
        String sub = a[0].toLowerCase();
        if (sub.equals("missing")) { missing(s); return; }
        if (sub.equals("recipes")) { recipes(s); return; }
        if (a.length < 2) { usage(s); return; }
        Player p = Bukkit.getPlayerExact(a[1]);
        if (p == null) { tell(s, "&cNo online player " + a[1]); return; }
        if (!Store.ready(p)) { tell(s, "&c" + p.getName() + "'s profile is not ready yet"); return; }
        switch (sub) {
            case "info" -> info(s, p);
            case "open" -> open(s, p, a);
            case "sack" -> sack(s, p, a);
            case "givesack" -> giveSack(s, p, a);
            case "placebp" -> placeBp(s, p, a);
            case "absorb" -> absorb(s, p, a);
            case "wardrobe" -> wardrobe(s, p, a);
            case "eqwardrobe" -> eqWardrobe(s, p, a);
            case "dupesrc" -> dupeSource(s, p);
            case "scan" -> scan(s, p);
            case "gfs" -> gfs(s, p, a);
            case "unlocks" -> unlocks(s, p);
            case "reset" -> reset(s, p, a);
            default -> usage(s);
        }
    }

    private void usage(CommandSender s) {
        tell(s, "&e/sb storage info <p> &7- summary");
        tell(s, "&e/sb storage open <p> <storage|ec [n]|bp [n]|wardrobe [page]|sacks|sackofsacks|quiver|vault|bags|shop>");
        tell(s, "&e/sb storage sack <p> <add|take> <ITEM_ID> <n> &7| &e<list|clear>");
        tell(s, "&e/sb storage givesack <p> <SACK_ID> [linings] &7- straight into the Sack of Sacks");
        tell(s, "&e/sb storage placebp <p> <BACKPACK_ID> <slot 1-18>");
        tell(s, "&e/sb storage absorb <p> <ITEM_ID> <n> &7- a real loot gain (source OTHER) through the sack/quiver path");
        tell(s, "&e/sb storage wardrobe <p> <equip|unequip> <slot 1-18> &7| &elist");
        tell(s, "&e/sb storage eqwardrobe <p> <equip|unequip|take> <slot 1-18> &7| &estore <slot> <ITEM_ID...> &7| &elist");
        tell(s, "&e/sb storage dupesrc <p> &7- what core's dupe scan sees inside this player's storage");
        tell(s, "&e/sb storage gfs <p> <item> [amount|all] &7- run /gfs for a player, prints before/after");
        tell(s, "&e/sb storage scan <p> &7- duplicate uuids across storage + inventory");
        tell(s, "&e/sb storage unlocks <p> &7- every gate and why");
        tell(s, "&e/sb storage reset <p> <ender|backpacks|wardrobe|sacks|bags> confirm");
        tell(s, "&e/sb storage missing &7| &e/sb storage recipes");
    }

    private void gfs(CommandSender s, Player p, String[] a) {
        if (a.length < 3) { tell(s, "&c/sb storage gfs <p> <item> [amount|all]"); return; }
        String[] rest = java.util.Arrays.copyOfRange(a, 2, a.length);
        java.util.Map<String, Long> before = new java.util.HashMap<>(mod.store().sacks(p).stored);
        int invBefore = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (!Codec.empty(it)) invBefore += it.getAmount();
        mod.gfs().run(p, rest);
        int invAfter = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (!Codec.empty(it)) invAfter += it.getAmount();
        java.util.Map<String, Long> after = mod.store().sacks(p).stored;
        for (String id : new java.util.TreeSet<>(before.keySet())) {
            long b = before.get(id), n = after.getOrDefault(id, 0L);
            if (b != n) tell(s, "&7" + id + ": sacks &a" + b + " &7-> &a" + n);
        }
        tell(s, "&7Inventory items: &a" + invBefore + " &7-> &a" + invAfter);
    }

    private void info(CommandSender s, Player p) {
        Store st = mod.store();
        tell(s, "&6Storage of " + p.getName());
        int ecItems = 0;
        for (Map<String, String> pg : st.ender(p).pages.values()) ecItems += pg.size();
        tell(s, "&7Ender chest: &a" + st.ecPagesOpen(p) + "&7/" + st.ecPages() + " pages open, &a" + ecItems + " &7stacks stored");
        StorageData.Backpacks b = st.backpacks(p);
        StringBuilder bp = new StringBuilder();
        for (int i = 0; i < st.bpSlots(); i++) {
            ItemStack it = st.backpackItem(p, i);
            if (it != null) bp.append(" ").append(i + 1).append("=").append(Codec.id(it)).append("(").append(Store.used(b.contents.get(String.valueOf(i)))).append(")");
        }
        tell(s, "&7Backpacks:" + (bp.length() == 0 ? " &8none" : "&a" + bp));
        StorageData.Wardrobe w = st.wardrobe(p);
        tell(s, "&7Wardrobe: &a" + w.sets.size() + " &7sets stored, equipped slot &a" + (w.equipped < 0 ? "none" : String.valueOf(w.equipped + 1))
                + "&7, access " + (st.wardrobeAllowed(p) ? "&aYES" : "&cNO"));
        tell(s, "&7Equipment Wardrobe: &a" + w.eqSets.size() + " &7sets stored, equipped slot &a"
                + (w.eqEquipped < 0 ? "none" : String.valueOf(w.eqEquipped + 1)) + "&7, link " + (w.linked ? "&aON" : "&cOFF")
                + "&7, bridge &a" + mod.equipBridge().describe());
        StorageData.Sacks sk = st.sacks(p);
        long total = 0;
        for (long v : sk.stored.values()) total += v;
        tell(s, "&7Sacks: &a" + mod.sacks().familyCounts(p).size() + " &7kinds owned, &a" + sk.stored.size() + " &7item types, &a"
                + Text.commasInt(total) + " &7items; bag &a" + sk.bag.size() + "&7/" + st.sackBagSlots(p) + "; pickup " + (sk.autoPickup ? "&aON" : "&cOFF"));
        StorageData.Bags bags = st.bags(p);
        tell(s, "&7Quiver: &a" + bags.quiver.size() + "&7/" + st.quiverSlots(p) + " stacks (" + mod.bagMenus().quiverTotal(p) + " arrows); Vault: &a"
                + bags.vault.size() + "&7/" + st.vaultSlots(p));
    }

    private void open(CommandSender s, Player p, String[] a) {
        String what = a.length > 2 ? a[2].toLowerCase() : "storage";
        int n = a.length > 3 ? StorageModule.parse(a[3], 1) - 1 : 0;
        switch (what) {
            case "ec" -> mod.storageMenus().openEnder(p, n);
            case "bp" -> mod.storageMenus().openBackpack(p, n);
            case "wardrobe" -> mod.wardrobe().open(p, n);
            case "eqwardrobe" -> mod.equipWardrobe().open(p, n);
            case "sacks" -> mod.sackMenus().openSacks(p, n);
            case "sackofsacks" -> mod.sackMenus().openBag(p);
            case "quiver" -> mod.bagMenus().openQuiver(p);
            case "vault" -> mod.bagMenus().openVault(p);
            case "bags" -> mod.bagMenus().openBags(p);
            case "shop" -> mod.bagMenus().openShop(p);
            default -> mod.storageMenus().openStorage(p);
        }
        tell(s, "&aOpened " + what + " for " + p.getName());
    }

    private void sack(CommandSender s, Player p, String[] a) {
        String op = a.length > 2 ? a[2].toLowerCase() : "list";
        StorageData.Sacks d = mod.store().sacks(p);
        switch (op) {
            case "add" -> {
                if (a.length < 5) { tell(s, "&cUsage: sack <p> add <ID> <n>"); return; }
                String id = a[3].toUpperCase();
                int n = StorageModule.parse(a[4], 0);
                if (!mod.catalog().sackable(id) && !mod.catalog().isArrow(id)) { tell(s, "&c" + id + " is held by no sack"); return; }
                int done = mod.sacks().add(p, id, n);
                tell(s, "&aStored " + done + "/" + n + " " + id + " (capacity " + mod.sacks().capacity(p, id, mod.sacks().familyCaps(p))
                        + ", now " + mod.sacks().count(p, id) + ")");
            }
            case "take" -> {
                if (a.length < 5) { tell(s, "&cUsage: sack <p> take <ID> <n>"); return; }
                String id = a[3].toUpperCase();
                int got = mod.sacks().remove(p, id, StorageModule.parse(a[4], 0));
                tell(s, "&aRemoved " + got + " " + id + " (left " + mod.sacks().count(p, id) + ")");
            }
            case "clear" -> {
                int n = d.stored.size();
                d.stored.clear();
                tell(s, "&aCleared " + n + " item types from " + p.getName() + "'s sacks");
            }
            default -> {
                Map<String, Long> sorted = new TreeMap<>(d.stored);
                tell(s, "&6Sack contents of " + p.getName() + " (" + sorted.size() + " types):");
                int shown = 0;
                for (Map.Entry<String, Long> en : sorted.entrySet()) {
                    if (shown++ >= 40) { tell(s, "&8..." + (sorted.size() - 40) + " more"); break; }
                    tell(s, " &7" + en.getKey() + ": &a" + Text.commasInt(en.getValue()));
                }
                Map<String, Long> caps = mod.sacks().familyCaps(p);
                tell(s, "&7Family capacities: &a" + caps);
            }
        }
    }

    private void giveSack(CommandSender s, Player p, String[] a) {
        if (a.length < 3) { tell(s, "&cUsage: givesack <p> <SACK_ID> [linings]"); return; }
        String id = a[2].toUpperCase();
        if (!mod.catalog().isSack(id) || !Sky.items().exists(id)) { tell(s, "&c" + id + " is not a registered sack"); return; }
        ItemStack sack = Sky.items().create(id);
        int lin = a.length > 3 ? StorageMath.clamp(StorageModule.parse(a[3], 0), 0, StorageDials.LINING_MAX.get()) : 0;
        if (lin > 0) Sky.items().update(sack, d -> d.extra.add(SackService.LININGS, new JsonPrimitive(lin)));
        StorageData.Sacks d = mod.store().sacks(p);
        int cap = mod.store().sackBagSlots(p);
        for (int i = 0; i < cap; i++) {
            if (!d.bag.containsKey(String.valueOf(i))) {
                if ("sackbag".equals(mod.openKey(p))) p.closeInventory();
                d.bag.put(String.valueOf(i), Codec.encode(sack));
                mod.sacks().invalidate(p);
                mod.guard().reindex(p, "sackbag", Store.decodeAll(d.bag));
                tell(s, "&aPut " + id + " (linings " + lin + ") in " + p.getName() + "'s Sack of Sacks slot " + (i + 1)
                        + "; capacity now " + mod.sacks().sackCapacity(sack));
                return;
            }
        }
        Sky.loot().give(p, sack, LootSource.ADMIN);
        tell(s, "&eSack of Sacks full (" + cap + " slots): gave " + id + " to the inventory instead");
    }

    private void placeBp(CommandSender s, Player p, String[] a) {
        if (a.length < 4) { tell(s, "&cUsage: placebp <p> <BACKPACK_ID> <slot>"); return; }
        String id = a[2].toUpperCase();
        int slot = StorageModule.parse(a[3], 0) - 1;
        if (!mod.catalog().isBackpack(id) || !Sky.items().exists(id)) { tell(s, "&c" + id + " is not a registered backpack"); return; }
        if (slot < 0 || slot >= mod.store().bpSlots()) { tell(s, "&cSlot must be 1-" + mod.store().bpSlots()); return; }
        StorageData.Backpacks b = mod.store().backpacks(p);
        if (b.items.containsKey(String.valueOf(slot))) { tell(s, "&cSlot " + (slot + 1) + " already holds a backpack"); return; }
        p.closeInventory();
        b.items.put(String.valueOf(slot), Codec.encode(Sky.items().create(id)));
        mod.guard().reindex(p, "bpitems", Store.decodeAll(b.items));
        tell(s, "&aPlaced " + id + " (" + mod.catalog().backpackSlots(id) + " slots) in " + p.getName() + "'s backpack slot " + (slot + 1));
    }

    private void absorb(CommandSender s, Player p, String[] a) {
        if (a.length < 4) { tell(s, "&cUsage: absorb <p> <ID> <n>"); return; }
        String id = a[2].toUpperCase();
        int n = StorageModule.parse(a[3], 0);
        if (!Sky.items().exists(id) || n <= 0) { tell(s, "&cUnknown item or bad amount"); return; }
        long before = mod.sacks().count(p, id);
        int inv = Sky.items().count(p, id);
        int dropped = Sky.loot().give(p, id, n, LootSource.OTHER);
        long after = mod.sacks().count(p, id);
        tell(s, "&aGave " + n + " " + id + ": sacks/quiver " + before + " -> " + after + ", inventory " + inv + " -> " + Sky.items().count(p, id)
                + (dropped > 0 ? ", dropped " + dropped : ""));
    }

    private void wardrobe(CommandSender s, Player p, String[] a) {
        String op = a.length > 2 ? a[2].toLowerCase() : "list";
        if (op.equals("list")) {
            StorageData.Wardrobe w = mod.store().wardrobe(p);
            tell(s, "&6Wardrobe of " + p.getName() + " (equipped " + (w.equipped < 0 ? "none" : w.equipped + 1) + "):");
            for (Map.Entry<String, Map<String, String>> en : new TreeMap<>(w.sets).entrySet()) {
                List<String> ids = new ArrayList<>();
                for (ItemStack it : Store.decodeAll(en.getValue())) ids.add(Codec.id(it));
                tell(s, " &7slot " + (StorageModule.parse(en.getKey(), 0) + 1) + ": &a" + ids);
            }
            return;
        }
        int slot = a.length > 3 ? StorageModule.parse(a[3], 0) - 1 : -1;
        if (slot < 0) { tell(s, "&cGive a slot 1-18"); return; }
        if ("wd".equals(mod.openKey(p))) p.closeInventory();
        String err = op.equals("equip") ? mod.wardrobe().equip(p, slot) : mod.wardrobe().unequip(p, slot);
        tell(s, err == null ? "&aDone" : "&c" + err);
    }

    private void eqWardrobe(CommandSender s, Player p, String[] a) {
        String op = a.length > 2 ? a[2].toLowerCase() : "list";
        StorageData.Wardrobe w = mod.store().wardrobe(p);
        if (op.equals("list")) {
            tell(s, "&6Equipment Wardrobe of " + p.getName() + " (equipped " + (w.eqEquipped < 0 ? "none" : w.eqEquipped + 1)
                    + ", bridge " + mod.equipBridge().describe() + "):");
            for (Map.Entry<String, Map<String, String>> en : new TreeMap<>(w.eqSets).entrySet()) {
                List<String> ids = new ArrayList<>();
                for (ItemStack it : Store.decodeAll(en.getValue())) ids.add(Codec.id(it));
                tell(s, " &7slot " + (StorageModule.parse(en.getKey(), 0) + 1) + ": &a" + ids);
            }
            List<String> worn = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                ItemStack on = mod.equipBridge().equipped(p, i);
                worn.add(EquipBridge.NAMES[i] + "=" + (on == null ? "-" : Codec.id(on)));
            }
            tell(s, " &7worn now: &a" + worn);
            return;
        }
        int slot = a.length > 3 ? StorageModule.parse(a[3], 0) - 1 : -1;
        if (slot < 0 || slot >= mod.store().wdSlots()) { tell(s, "&cGive a slot 1-" + mod.store().wdSlots()); return; }
        if ("wdeq".equals(mod.openKey(p))) p.closeInventory();
        if (op.equals("store")) {
            // put fresh equipment straight into a slot (testing without a client); each id must be a necklace/cloak/belt/gloves
            if (a.length < 5) { tell(s, "&cUsage: eqwardrobe <p> store <slot> <ITEM_ID...>"); return; }
            Map<String, String> set = mod.store().eqSet(p, slot);
            List<String> done = new ArrayList<>();
            for (int i = 4; i < a.length; i++) {
                String id = a[i].toUpperCase();
                if (!Sky.items().exists(id)) { tell(s, "&c" + id + " is not a registered item"); continue; }
                ItemStack it = Sky.items().create(id);
                int piece = EquipBridge.piece(it);
                if (piece < 0) { tell(s, "&c" + id + " is not a necklace, cloak, belt or gloves"); continue; }
                if (set.containsKey(String.valueOf(piece))) { tell(s, "&c" + EquipBridge.NAMES[piece] + " row of slot " + (slot + 1) + " is taken"); continue; }
                set.put(String.valueOf(piece), Codec.encode(it));
                done.add(id);
            }
            if (set.isEmpty()) w.eqSets.remove(String.valueOf(slot));
            mod.equipWardrobe().reindex(p);
            tell(s, "&aStored " + done + " in Equipment Wardrobe slot " + (slot + 1));
            return;
        }
        String err = switch (op) {
            case "equip" -> mod.equipWardrobe().equip(p, slot);
            case "unequip" -> mod.equipWardrobe().unequip(p, slot);
            case "take" -> mod.equipWardrobe().take(p, slot);
            default -> "Unknown action " + op;
        };
        tell(s, err == null ? "&aDone" : "&c" + err);
    }

    private void dupeSource(CommandSender s, Player p) {
        List<ItemStack> u = mod.store().uniqueStacks(p);
        int stored = 0;
        for (List<String> l : mod.store().rawContainers(p).values()) stored += l.size();
        tell(s, "&7Core's dupe scan sees &a" + u.size() + " &7unique item(s) among &a" + stored + " &7stored stacks of " + p.getName()
                + " &7(source " + (StorageDials.DUPE_SCAN_SOURCE.get() ? "&aon" : "&coff") + "&7, decode cache " + mod.store().uniqueCacheSize() + ")");
    }

    private void scan(CommandSender s, Player p) {
        mod.flushOpen(p);
        Map<String, List<String>> d = mod.guard().scan(p);
        if (d.isEmpty()) { tell(s, "&aNo duplicate uuids for " + p.getName()); return; }
        tell(s, "&c" + d.size() + " duplicated uuid(s) for " + p.getName() + ":");
        for (Map.Entry<String, List<String>> en : d.entrySet()) tell(s, " &c" + en.getKey() + " &7-> " + en.getValue());
    }

    private void unlocks(CommandSender s, Player p) {
        Store st = mod.store();
        tell(s, "&6Gates for " + p.getName() + " &7(SkyBlock level " + Store.sbLevel(p) + ")");
        StringBuilder ec = new StringBuilder();
        for (int i = 0; i < st.ecPages(); i++) ec.append(st.ecPageOpen(p, i) ? "&a" : "&c").append(i + 1).append("(").append(st.ecPageLevelNeeded(p, i)).append(") ");
        tell(s, "&7Ender pages (level): " + ec + "&7shop tier " + Store.shopTier(p, StorageDials.EC_SHOP_UPGRADE.get(), 8));
        StringBuilder bp = new StringBuilder();
        for (int i = 0; i < st.bpSlots(); i++) bp.append(st.bpSlotOpen(p, i) ? "&a" : "&c").append(i + 1).append(" ");
        tell(s, "&7Backpack slots (fairy souls " + StorageDials.BP_SOULS.get() + "): " + bp);
        StringBuilder wd = new StringBuilder();
        for (int i = 0; i < st.wdSlots(); i++) wd.append(st.wdSlotOpen(p, i) ? "&a" : "&c").append(i + 1).append(" ");
        tell(s, "&7Wardrobe (" + StorageDials.WD_REQUIREMENT.get() + " " + (st.wardrobeAllowed(p) ? "&aok" : "&cno") + "&7): " + wd
                + "&7shop tier " + Store.shopTier(p, StorageDials.WD_SHOP_UPGRADE.get(), 9));
        StringBuilder eq = new StringBuilder();
        for (int i = 0; i < st.wdSlots(); i++) eq.append(st.eqSlotOpen(p, i) ? "&a" : "&c").append(i + 1).append(" ");
        tell(s, "&7Equipment Wardrobe (" + (StorageDials.EQ_WARDROBE.get() ? "&aon" : "&coff") + "&7, bridge "
                + (mod.equipBridge().live(p) ? "&alive" : "&cfallback") + "&7): " + eq
                + "&7shop tier " + Store.shopTier(p, StorageDials.EQ_SHOP_UPGRADE.get(), 9));
        tell(s, "&7Sack of Sacks slots: &a" + st.sackBagSlots(p) + " &7(" + StorageDials.SACKBAG_COLLECTION.get() + " tier "
                + Store.collectionTier(p, StorageDials.SACKBAG_COLLECTION.get()) + ")");
        tell(s, "&7Quiver slots: &a" + st.quiverSlots(p) + " &7(" + StorageDials.QUIVER_COLLECTION.get() + " tier "
                + Store.collectionTier(p, StorageDials.QUIVER_COLLECTION.get()) + ")");
        double cap;
        try { cap = Sky.economy().bankCapacity(p); } catch (RuntimeException e) { cap = 0; }
        tell(s, "&7Vault slots: &a" + st.vaultSlots(p) + " &7(bank capacity " + Text.commasInt(cap) + ")");
    }

    private void reset(CommandSender s, Player p, String[] a) {
        if (a.length < 4 || !a[a.length - 1].equalsIgnoreCase("confirm")) {
            tell(s, "&cThis DELETES stored items. Add 'confirm': /sb storage reset <p> <what> confirm");
            return;
        }
        p.closeInventory();
        switch (a[2].toLowerCase()) {
            case "ender" -> mod.store().ender(p).pages.clear();
            case "backpacks" -> { mod.store().backpacks(p).items.clear(); mod.store().backpacks(p).contents.clear(); }
            case "wardrobe" -> {
                StorageData.Wardrobe w = mod.store().wardrobe(p);
                w.sets.clear(); w.equipped = -1; w.eqSets.clear(); w.eqEquipped = -1;
            }
            case "sacks" -> { mod.store().sacks(p).stored.clear(); mod.store().sacks(p).bag.clear(); mod.sacks().invalidate(p); }
            case "bags" -> { mod.store().bags(p).quiver.clear(); mod.store().bags(p).vault.clear(); }
            default -> { tell(s, "&cUnknown part " + a[2]); return; }
        }
        mod.guard().forget(p.getUniqueId());
        Sky.log().warning("[storage] admin " + s.getName() + " reset " + a[2] + " of " + p.getName());
        tell(s, "&aReset " + a[2] + " of " + p.getName());
    }

    private void missing(CommandSender s) {
        List<String> miss = new ArrayList<>();
        for (String id : mod.catalog().allContents()) if (!Sky.items().exists(id)) miss.add(id);
        tell(s, "&6Sack contents not registered by any module: &e" + miss.size() + "&7/" + mod.catalog().allContents().size());
        StringBuilder line = new StringBuilder();
        for (String id : miss) {
            if (line.length() > 200) { tell(s, "&7" + line); line.setLength(0); }
            line.append(id).append(' ');
        }
        if (line.length() > 0) tell(s, "&7" + line);
    }

    private void recipes(CommandSender s) {
        tell(s, "&6Storage recipes: &a" + mod.recipesRegistered + " &7registered, &e" + mod.recipesSkipped.size() + " &7skipped");
        for (String r : mod.recipesSkipped) tell(s, " &7" + r);
    }
}
