package com.skyclone.mod.progression;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Museum (research 08 section 6.2): donate weapons, armour sets and skill items for one-time
 * SkyBlock XP. Donated items are STORED (serialised, uuid intact) in the profile's shared museum and
 * can be taken back out by a member (the XP stays earned; the slot shows "borrowed").
 * Donating an upgraded item also counts its lower tiers (NEU "children").
 */
final class Museum implements Features.Museum {

    static final String NPC_ID = "progression_curator";
    static final String NPC_NAME = "&6Curator Ottoline Vane";

    private final Map<String, Catalogs.MEntry> entries = new LinkedHashMap<>();
    private final Map<String, String> categoryOf = new HashMap<>();
    /** item id -> entry ids that contain it (an item may be a single entry AND a set piece). */
    private final Map<String, List<String>> byItem = new HashMap<>();
    /** child entry id -> parent entry ids that also count for it. */
    private final Map<String, List<String>> parentsOf = new HashMap<>();
    private final Map<String, Integer> stageRank = new HashMap<>();

    Museum() {
        Catalogs.MuseumFile f = Prog.museumFile;
        for (int i = 0; i < f.stageOrder.size(); i++) stageRank.put(f.stageOrder.get(i), i);
        for (Catalogs.MCategory c : f.categories) {
            for (Catalogs.MEntry e : c.entries) {
                if (entries.containsKey(e.id)) continue;
                entries.put(e.id, e);
                categoryOf.put(e.id, c.id);
                for (String id : e.ids()) byItem.computeIfAbsent(id, k -> new ArrayList<>()).add(e.id);
                if (e.child != null) parentsOf.computeIfAbsent(e.child, k -> new ArrayList<>()).add(e.id);
            }
        }
    }

    int entryCount() { return entries.size(); }

    Catalogs.MEntry entry(String id) { return entries.get(id); }

    /** An entry is in THIS world when every item it needs is registered. */
    boolean available(Catalogs.MEntry e) {
        for (String id : e.ids()) if (!Sky.items().exists(id)) return false;
        return true;
    }

    int availableCount() {
        int n = 0;
        for (Catalogs.MEntry e : entries.values()) if (available(e)) n++;
        return n;
    }

    // ---- donated state ----------------------------------------------------------------------------------

    /** Donated directly, or through a higher tier that was donated. */
    boolean isDonated(PData.Museum m, String entryId) {
        return isDonated(m, entryId, 0);
    }

    private boolean isDonated(PData.Museum m, String entryId, int depth) {
        if (m.exhibits.containsKey(entryId)) return true;
        if (depth > 12) return false;
        List<String> parents = parentsOf.get(entryId);
        if (parents != null) for (String p : parents) if (isDonated(m, p, depth + 1)) return true;
        return false;
    }

    @Override public boolean donated(Player p, String itemId) {
        if (!Prog.ready(p) || itemId == null) return false;
        PData.Museum m = Prog.museum(p);
        if (isDonated(m, itemId)) return true;
        List<String> ents = byItem.get(itemId);
        if (ents != null) for (String e : ents) if (isDonated(m, e)) return true;
        return false;
    }

    int exhibitCount(Player p) {
        if (!Prog.ready(p)) return 0;
        return Prog.museum(p).exhibits.size();
    }

    /** Museum milestone reached by museum SkyBlock XP (Features.Museum, wave 5: bank + auction gates). */
    @Override public int milestone(Player p) {
        if (!Prog.ready(p)) return 0;
        return PMath.museumMilestone(museumXp(Prog.museum(p)), PMath.MUSEUM_MILESTONES);
    }

    boolean isSpecial(String entryId) { return "special".equals(categoryOf.get(entryId)); }

    /** Special Items on display (not borrowed). */
    int specialCount(PData.Museum m) {
        int n = 0;
        for (Map.Entry<String, PData.Exhibit> e : m.exhibits.entrySet()) {
            if (isSpecial(e.getKey()) && e.getValue() != null && !e.getValue().borrowed) n++;
        }
        return n;
    }

    // ---- Co-op Soulbound (research 08 6.2: "First donation makes the item Co-op Soulbound") -------------------

    /** ItemData.extra key the museum writes (the profile id the item is bound to); bank trades already refuse it. */
    static final String SOULBOUND_KEY = "museum_soulbound";

    /** The profile id a stack is co-op soulbound to (museum or another module's coop_soulbound), or null. */
    static String boundProfile(ItemStack st) {
        if (st == null || st.getType().isAir()) return null;
        try {
            if (Sky.items().idOf(st) == null) return null;
            var data = Sky.items().data(st);
            if (data == null || data.extra == null) return null;
            for (String k : new String[]{SOULBOUND_KEY, "coop_soulbound"}) {
                if (!data.extra.has(k)) continue;
                var el = data.extra.get(k);
                if (el != null && el.isJsonPrimitive()) return el.getAsString();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** True when the stack is co-op soulbound to a profile that is not {@code p}'s active one. */
    static boolean foreignBound(Player p, ItemStack st) {
        String pid = boundProfile(st);
        if (pid == null || pid.isBlank()) return false;
        String mine = Sky.profiles().profileId(p);
        return mine == null || !pid.equals(mine);
    }

    /** Museum SkyBlock XP the profile has earned: every donated entry plus the lower tiers it covers. */
    int museumXp(PData.Museum m) {
        int xp = 0;
        for (Catalogs.MEntry e : entries.values()) if (isDonated(m, e.id)) xp += e.xp;
        return xp;
    }

    /** Pay any museum XP this member has not been paid yet (co-op members donate for each other). */
    void syncAwards(Player p) {
        if (!Prog.ready(p)) return;
        PData.Museum m = Prog.museum(p);
        for (Catalogs.MEntry e : entries.values()) {
            if (e.xp > 0 && isDonated(m, e.id)) Prog.award(p, "museum:" + e.id, e.xp, "Museum");
        }
    }

    // ---- donating -----------------------------------------------------------------------------------------

    /** Inventory slot (or armour) holding each needed id; null if something is missing. */
    private int[] findPieces(Player p, Catalogs.MEntry e) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        List<String> ids = e.ids();
        int[] slots = new int[ids.size()];
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            int found = -1;
            for (int s = 0; s < contents.length; s++) {
                if (used.contains(s)) continue;
                ItemStack it = contents[s];
                if (it == null || it.getType().isAir()) continue;
                // another co-op's soulbound item can never be donated here (it is theirs, wherever it ended up)
                if (ids.get(i).equals(Sky.items().idOf(it)) && !foreignBound(p, it)) { found = s; break; }
            }
            if (found < 0) return null;
            used.add(found);
            slots[i] = found;
        }
        return slots;
    }

    /** Entry the clicked stack can be donated as: its own single entry, else the set it completes. */
    String entryForItem(Player p, String itemId) {
        List<String> ents = byItem.get(itemId);
        if (ents == null) return null;
        PData.Museum m = Prog.museum(p);
        String fallback = null;
        for (String id : ents) {
            Catalogs.MEntry e = entries.get(id);
            if (e == null || !available(e)) continue;
            if (!isDonated(m, id)) return id;
            if (fallback == null) fallback = id;
        }
        return fallback;
    }

    void askDonate(Player p, String entryId, Runnable reopen) {
        Catalogs.MEntry e = entries.get(entryId);
        if (e == null || !available(e)) { Msg.error(p, "That item cannot be donated."); return; }
        PData.Museum m = Prog.museum(p);
        if (m.exhibits.containsKey(entryId) && !m.exhibits.get(entryId).borrowed) { Msg.error(p, "That is already on display."); return; }
        if (findPieces(p, e) == null) {
            Msg.error(p, e.isSet() ? "You need every piece of the set in your inventory." : "You need the item in your inventory.");
            return;
        }
        if (isSpecial(entryId) && !m.exhibits.containsKey(entryId) && specialCount(m) >= Math.max(0, Prog.museumSpecialMax.get())) {
            Msg.error(p, "Your Special Items display is full (" + Prog.museumSpecialMax.get() + ").");
            return;
        }
        boolean redonate = m.exhibits.containsKey(entryId);
        List<String> desc = new ArrayList<>();
        desc.add("&7Donate " + displayName(e) + "&7?");
        desc.add("");
        desc.add("&7The item stays in the Museum and");
        desc.add(isSpecial(entryId) ? "&7stays tradeable (Special Item)." : "&7becomes &dCo-op Soulbound&7.");
        if (!redonate && e.xp > 0) { desc.add(""); desc.add("&7Reward: &b+" + e.xp + " SkyBlock XP"); }
        Sky.menus().confirm(p, "Donate to the Museum?", desc, () -> {
            donate(p, entryId);
            if (reopen != null) reopen.run();
        }, reopen);
    }

    /** The one place items leave the player for the museum. Main thread; re-validates everything. */
    boolean donate(Player p, String entryId) {
        if (!Prog.ready(p)) return false;
        Catalogs.MEntry e = entries.get(entryId);
        if (e == null || !available(e)) return false;
        PData.Museum m = Prog.museum(p);
        PData.Exhibit old = m.exhibits.get(entryId);
        if (old != null && !old.borrowed) { Msg.error(p, "That is already on display."); return false; }
        int[] slots = findPieces(p, e);
        if (slots == null) { Msg.error(p, "The item is no longer in your inventory."); return false; }
        boolean special = isSpecial(entryId);
        if (special && old == null && specialCount(m) >= Math.max(0, Prog.museumSpecialMax.get())) {
            Msg.error(p, "Your Special Items display is full (" + Prog.museumSpecialMax.get() + ").");
            return false;
        }
        String profileId = Sky.profiles().profileId(p);
        PlayerInventory inv = p.getInventory();
        List<String> stored = new ArrayList<>();
        for (int s : slots) {
            ItemStack it = inv.getItem(s);
            if (it == null) return false;
            ItemStack one = it.clone();
            one.setAmount(1);
            // research 08 6.2: the first donation binds the item to the co-op; Special Items are "not soulbound"
            if (!special && profileId != null) {
                try {
                    Sky.items().update(one, d -> d.extra.addProperty(SOULBOUND_KEY, profileId));
                } catch (Throwable ignored) { }
            }
            stored.add(Base64.getEncoder().encodeToString(one.serializeAsBytes()));
        }
        // remove only after every piece serialised cleanly
        for (int s : slots) {
            ItemStack it = inv.getItem(s);
            if (it == null) continue;
            if (it.getAmount() > 1) { it.setAmount(it.getAmount() - 1); inv.setItem(s, it); }
            else inv.setItem(s, null);
        }
        boolean first = old == null;
        PData.Exhibit ex = old != null ? old : new PData.Exhibit();
        ex.stacks = stored;
        ex.borrowed = false;
        if (first) {
            ex.donatedAt = System.currentTimeMillis();
            ex.donorName = p.getName();
            ex.donorUuid = p.getUniqueId().toString();
        }
        m.exhibits.put(entryId, ex);
        Sky.profiles().saveNow(p);
        if (first) {
            List<String> body = new ArrayList<>();
            body.add("&7You donated " + displayName(e) + " &7to the Museum!");
            body.add("");
            body.add("&a&lREWARDS");
            int xpTotal = 0;
            for (Catalogs.MEntry c : covered(e)) xpTotal += c.xp;
            if (xpTotal > 0) body.add("  &8+&b" + xpTotal + " SkyBlock XP");
            Msg.box(p, "&6&lMUSEUM DONATION", body);
            Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.4f);
            syncAwards(p);
            checkMilestones(p, m);
        } else {
            Msg.success(p, "Put " + Text.strip(displayName(e)) + " back on display.");
        }
        if (Prog.objectives != null) Prog.objectives.poll(p);
        return true;
    }

    /** The entry plus every lower tier its donation counts for. */
    List<Catalogs.MEntry> covered(Catalogs.MEntry e) {
        List<Catalogs.MEntry> out = new ArrayList<>();
        Catalogs.MEntry cur = e;
        int guard = 0;
        while (cur != null && guard++ < 12) {
            out.add(cur);
            cur = cur.child == null ? null : entries.get(cur.child);
        }
        return out;
    }

    void askRetrieve(Player p, String entryId, Runnable reopen) {
        if (!Prog.museumRetrieve.get()) { Msg.error(p, "Museum items cannot be retrieved on this server."); return; }
        PData.Museum m = Prog.museum(p);
        PData.Exhibit ex = m.exhibits.get(entryId);
        Catalogs.MEntry e = entries.get(entryId);
        if (ex == null || ex.borrowed || e == null) return;
        Sky.menus().confirm(p, "Retrieve from the Museum?", List.of("&7Take " + displayName(e) + " &7back out?", "",
                "&7Your SkyBlock XP stays earned, but", "&7the slot shows as empty until you", "&7donate it again."), () -> {
            retrieve(p, entryId);
            if (reopen != null) reopen.run();
        }, reopen);
    }

    boolean retrieve(Player p, String entryId) {
        if (!Prog.ready(p)) return false;
        PData.Museum m = Prog.museum(p);
        PData.Exhibit ex = m.exhibits.get(entryId);
        if (ex == null || ex.borrowed || ex.stacks.isEmpty()) return false;
        PlayerInventory inv = p.getInventory();
        int free = 0;
        for (ItemStack it : inv.getStorageContents()) if (it == null || it.getType().isAir()) free++;
        if (free < ex.stacks.size()) { Msg.error(p, "You need " + ex.stacks.size() + " free inventory slots."); return false; }
        List<ItemStack> items = new ArrayList<>();
        for (String b64 : ex.stacks) {
            try {
                items.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(b64)));
            } catch (Throwable t) {
                Sky.log().warning("[progression] museum item for " + p.getName() + " / " + entryId + " failed to load: " + t);
                Msg.error(p, "That exhibit could not be restored. An admin has been told.");
                return false;
            }
        }
        // clear the stored copy FIRST, then hand the items over: there is never a moment with two copies
        List<String> backup = ex.stacks;
        ex.stacks = new ArrayList<>();
        ex.borrowed = true;
        for (ItemStack it : items) {
            var left = inv.addItem(it);
            if (!left.isEmpty()) {   // cannot happen after the space check; never lose it
                for (ItemStack l : left.values()) p.getWorld().dropItem(p.getLocation(), l);
            }
        }
        Sky.profiles().saveNow(p);
        Sky.log().info("[progression] " + p.getName() + " retrieved museum exhibit " + entryId + " (" + backup.size() + " item(s))");
        Msg.success(p, "You took " + Text.strip(displayName(entries.get(entryId))) + " out of the Museum.");
        return true;
    }

    private void checkMilestones(Player p, PData.Museum m) {
        int xp = museumXp(m);
        int reached = PMath.museumMilestone(xp, PMath.MUSEUM_MILESTONES);
        for (int i = 1; i <= reached; i++) {
            if (m.milestonesPaid.contains(i)) continue;
            m.milestonesPaid.add(i);
            int threshold = PMath.MUSEUM_MILESTONES[i - 1];
            long coins = Math.round(threshold * Prog.museumMilestoneCoins.get());
            if (coins > 0) Sky.economy().deposit(p, coins, "museum: milestone " + i);
            Msg.box(p, "&6&lMUSEUM MILESTONE " + Text.roman(i), List.of(
                    "&7Your museum is worth &b" + Text.commasInt(xp) + " &7SkyBlock XP.", "",
                    "&a&lREWARDS", "  &6+" + Text.commasInt(coins) + " Coins"));
            Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
        }
    }

    /** Admin: put an entry on display without taking items (fresh items are created and stored). */
    boolean adminDonate(Player p, String entryId) {
        Catalogs.MEntry e = entries.get(entryId);
        if (e == null || !available(e)) return false;
        PData.Museum m = Prog.museum(p);
        PData.Exhibit ex = new PData.Exhibit();
        for (String id : e.ids()) ex.stacks.add(Base64.getEncoder().encodeToString(Sky.items().create(id).serializeAsBytes()));
        ex.donatedAt = System.currentTimeMillis();
        ex.donorName = p.getName();
        ex.donorUuid = p.getUniqueId().toString();
        m.exhibits.put(entryId, ex);
        syncAwards(p);
        checkMilestones(p, m);
        return true;
    }

    // ---- requirement "museum:<n>" ----------------------------------------------------------------------------

    Requirements.Resolver resolver() {
        return new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 2) return false;
                try { return exhibitCount(p) >= Integer.parseInt(parts[1].trim()); } catch (NumberFormatException e) { return false; }
            }
            @Override public String describe(String[] parts) { return "&6" + (parts.length > 1 ? parts[1] : "?") + " Museum Donations"; }
        };
    }

    /** {@code museum_milestone:<n>} (alias {@code museummilestone:<n>}): Museum Milestone n reached, by museum XP
     *  (auction request 6, bank request 6: the Vault and bank tier gates use the research's milestones). */
    Requirements.Resolver milestoneResolver() {
        return new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 2) return false;
                try { return milestone(p) >= Integer.parseInt(parts[1].trim()); } catch (NumberFormatException e) { return false; }
            }
            @Override public String describe(String[] parts) {
                int n = 0;
                try { n = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0; } catch (NumberFormatException ignored) { }
                return "&6Museum Milestone " + (n > 0 ? Text.roman(n) : "?");
            }
        };
    }

    // ---- NPC + menus -------------------------------------------------------------------------------------------

    void registerNpc() {
        NpcDef def = NpcDef.of(NPC_ID, NPC_NAME, "hub", "npc.museum").title("&6&lMUSEUM");
        def.lines.add("&7Donate items for SkyBlock XP");
        Sky.npcs().register(def, p -> {
            if (!Prog.ready(p)) return;
            Prog.objectives.npcTalked(p, "npc.museum");
            if (Prog.museum(p).exhibits.isEmpty()) {
                Sky.npcs().talk(p, NPC_ID, List.of(
                        "Welcome to the Museum!",
                        "Bring me your finest weapons, armour and tools.",
                        "I will keep them safe, and the whole island will know your name."), () -> openMenu(p));
            } else {
                openMenu(p);
            }
        });
    }

    String displayName(Catalogs.MEntry e) {
        if (e.isSet()) {
            ItemDef d = Sky.items().def(e.pieces.get(0));
            String n = d != null && d.name != null ? Text.strip(d.name) : Text.titleCase(e.id);
            n = n.replaceAll("(?i)\\s+(helmet|chestplate|leggings|boots|hat|cap|hood|crown|mask|tunic|robe|pants|trousers|shoes|sandals|galoshes|necklace|cloak|belt|gloves|bracelet)$", "");
            String color = d != null && d.rarity != null ? d.rarity.code() : "&f";
            return color + n + " Set";
        }
        ItemDef d = Sky.items().def(e.id);
        if (d == null) return "&f" + Text.titleCase(e.id);
        return (d.rarity != null ? d.rarity.code() : "&f") + Text.strip(d.name);
    }

    @Override public void openMenu(Player p) {   // core-wave5: now Features.*.openMenu
        if (!Prog.ready(p)) { Msg.error(p, "Your profile is still loading."); return; }
        buildMain(p).open(p);
    }

    Menu buildMain(Player viewer) { return buildMain(viewer, Prog.museum(viewer)); }

    Menu buildMain(Player viewer, PData.Museum m) {
        Menu menu = new Menu("Museum", 6).chrome();
        int xp = museumXp(m);
        int donatedAvail = 0, avail = 0;
        for (Catalogs.MEntry e : entries.values()) {
            if (!available(e)) continue;
            avail++;
            if (isDonated(m, e.id)) donatedAvail++;
        }
        int ms = PMath.museumMilestone(xp, PMath.MUSEUM_MILESTONES);
        menu.set(4, Icons.of(Material.GOLD_BLOCK, "&6Museum",
                "&7Donate items to show them off and", "&7earn SkyBlock XP. Donated items stay", "&7yours and can be taken back out.", "",
                "&7Items Donated: &a" + donatedAvail + "&7/&a" + avail, Prog.bar(donatedAvail, Math.max(1, avail)), "",
                "&7Museum SkyBlock XP: &b" + Text.commasInt(xp),
                "&7Milestone: &6" + (ms == 0 ? "None" : Text.roman(ms)), "",
                "&eClick an item in your inventory", "&eto donate it!"));
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 30, 32};
        int i = 0;
        for (Catalogs.MCategory c : Prog.museumFile.categories) {
            if (i >= slots.length) break;
            int got = 0, tot = 0;
            for (Catalogs.MEntry e : c.entries) {
                if (!available(e)) continue;
                tot++;
                if (isDonated(m, e.id)) got++;
            }
            final Catalogs.MCategory cat = c;
            List<String> lore = new ArrayList<>();
            lore.add("&7Donated: &a" + got + "&7/&a" + tot);
            lore.add(Prog.bar(got, Math.max(1, tot)));
            if ("special".equals(c.id)) {
                lore.add("");
                lore.add("&7Special items give no SkyBlock XP,");
                lore.add("&7just bragging rights, and they do not");
                lore.add("&7become soulbound.");
                lore.add("&7On display: &a" + specialCount(m) + "&7/&a" + Prog.museumSpecialMax.get());
            }
            lore.add("");
            lore.add(tot == 0 ? "&8Nothing in this world yet." : "&eClick to view!");
            menu.set(slots[i++], Icons.of(Prog.mat(c.icon, Material.CHEST), "&a" + c.name, lore),
                    click -> buildCategory(click.player(), cat, 0).open(click.player()));
        }
        List<String> ml = new ArrayList<>();
        ml.add("&7Milestones pay coins as your museum");
        ml.add("&7grows in SkyBlock XP.");
        ml.add("");
        for (int k = 0; k < PMath.MUSEUM_MILESTONES.length; k++) {
            int t = PMath.MUSEUM_MILESTONES[k];
            if (k >= ms + 4) break;
            if (k < ms - 3) continue;
            ml.add((xp >= t ? "&a✔ " : "&c✖ ") + "&7" + Text.roman(k + 1) + ": &b" + t + " XP &8- &6"
                    + Text.shortNum(t * Prog.museumMilestoneCoins.get()) + " coins");
        }
        menu.set(40, Icons.of(Material.DIAMOND, "&aMuseum Milestones", ml));
        menu.set(38, appraisalIcon(m));
        menu.onPlayerInventoryClick((click, stack) -> donateFromInventory(click.player(), stack, () -> buildMain(click.player()).open(click.player())));
        Prog.backToSkyMenu(menu);
        return menu;
    }

    // ---- appraisal (Museum Value) ------------------------------------------------------------------------------

    /**
     * Coin value of one item id for the appraisal: lowest BIN when the auction house knows it, else the
     * bazaar's instant-sell price, else what an NPC pays. 0 when nothing prices it.
     */
    static double appraise(String itemId) {
        if (itemId == null) return 0;
        try {
            if (Sky.has(Features.Auctions.class)) {
                double bin = Sky.service(Features.Auctions.class).lowestBin(itemId);
                if (bin > 0 && Double.isFinite(bin)) return bin;
            }
            if (Sky.has(Features.Bazaar.class)) {
                Features.Bazaar bz = Sky.service(Features.Bazaar.class);
                if (bz.isBazaarItem(itemId)) {
                    double s = bz.instantSell(itemId);
                    if (s > 0 && Double.isFinite(s)) return s;
                }
            }
            ItemDef d = Sky.items().def(itemId);
            if (d != null && d.npcSell > 0 && Double.isFinite(d.npcSell)) return d.npcSell;
        } catch (Throwable ignored) { }
        return 0;
    }

    /** {total, priced exhibits, unpriced exhibits} plus the three most valuable exhibits (entry id -> value). */
    record Appraisal(double total, int priced, int unpriced, List<Map.Entry<String, Double>> top) {}

    Appraisal appraisal(PData.Museum m) {
        double total = 0;
        int priced = 0, unpriced = 0;
        List<Map.Entry<String, Double>> values = new ArrayList<>();
        for (Map.Entry<String, PData.Exhibit> e : m.exhibits.entrySet()) {
            PData.Exhibit ex = e.getValue();
            if (ex == null || ex.borrowed) continue;
            Catalogs.MEntry me = entries.get(e.getKey());
            List<String> ids = me != null ? me.ids() : List.of(e.getKey());
            double v = 0;
            for (String id : ids) v += appraise(id);
            if (v > 0) { priced++; total += v; values.add(Map.entry(e.getKey(), v)); } else unpriced++;
        }
        values.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return new Appraisal(total, priced, unpriced, values.subList(0, Math.min(3, values.size())));
    }

    private ItemStack appraisalIcon(PData.Museum m) {
        Appraisal a = appraisal(m);
        List<String> lore = new ArrayList<>();
        lore.add("&7What everything on display would");
        lore.add("&7fetch today: lowest BIN, else the");
        lore.add("&7Bazaar, else an NPC's price.");
        lore.add("");
        lore.add("&7Museum Value: &6" + Text.commasInt((long) Math.floor(a.total())) + " coins");
        lore.add("&7Exhibits appraised: &a" + a.priced() + (a.unpriced() > 0 ? " &8(" + a.unpriced() + " priceless)" : ""));
        if (!a.top().isEmpty()) {
            lore.add("");
            lore.add("&6&lMOST VALUABLE");
            for (Map.Entry<String, Double> t : a.top()) {
                lore.add(" &f" + entryName(t.getKey()) + " &8- &6" + Text.shortNum(t.getValue()));
            }
        }
        lore.add("");
        lore.add("&8Borrowed items are not counted.");
        return Icons.of(Material.GOLD_INGOT, "&6Museum Appraisal", lore);
    }

    /** Display name for an entry (its item's name, or the id title-cased). */
    private static String entryName(String entryId) {
        try {
            ItemDef d = Sky.items().def(entryId);
            if (d != null && d.name != null && !d.name.isBlank()) return d.name;
        } catch (Throwable ignored) { }
        return Text.titleCase(entryId.toLowerCase(java.util.Locale.ROOT));
    }

    private void donateFromInventory(Player p, ItemStack stack, Runnable reopen) {
        if (stack == null || stack.getType().isAir()) return;
        String id = Sky.items().idOf(stack);
        String entry = id == null ? null : entryForItem(p, id);
        if (entry == null) { Msg.error(p, "The Museum does not collect that item."); return; }
        askDonate(p, entry, reopen);
    }

    Menu buildCategory(Player viewer, Catalogs.MCategory cat, int page) {
        List<Catalogs.MEntry> list = new ArrayList<>();
        for (Catalogs.MEntry e : cat.entries) if (available(e)) list.add(e);
        list.sort(Comparator.<Catalogs.MEntry>comparingInt(e -> stageRank.getOrDefault(e.stage, 0))
                .thenComparing(e -> Text.strip(displayName(e)).toLowerCase(Locale.ROOT)));
        Menu menu = Sky.menus().paged("Museum ➜ " + cat.name, list, e -> entryIcon(viewer, e), pc -> {
            Player p = pc.click().player();
            if (!Prog.ready(p)) return;
            Catalogs.MEntry e = pc.entry();
            PData.Museum m = Prog.museum(p);
            PData.Exhibit ex = m.exhibits.get(e.id);
            Runnable reopen = () -> buildCategory(p, cat, pc.page()).open(p);
            if (ex != null && !ex.borrowed) askRetrieve(p, e.id, reopen);
            else askDonate(p, e.id, reopen);
        }, pg -> {
            pg.back("Museum", () -> buildMain(viewer));
            pg.set(4, Icons.of(Prog.mat(cat.icon, Material.CHEST), "&a" + cat.name, "&7Click an entry to donate it, or click", "&7an item in your inventory."));
        }, page);
        menu.onPlayerInventoryClick((click, stack) -> donateFromInventory(click.player(), stack, () -> buildCategory(click.player(), cat, page).open(click.player())));
        return menu;
    }

    ItemStack entryIcon(Player viewer, Catalogs.MEntry e) {
        PData.Museum m = Prog.museum(viewer);
        PData.Exhibit ex = m.exhibits.get(e.id);
        boolean viaParent = ex == null && isDonated(m, e.id);
        String stage = e.stage == null ? "" : Text.titleCase(e.stage);
        List<String> info = new ArrayList<>();
        info.add("&8" + stage + (e.isSet() ? " Set" : ""));
        if (e.xp > 0) info.add("&7SkyBlock XP: &b+" + e.xp);
        if (ex != null && !ex.borrowed) {
            ItemStack icon = null;
            // show the real exhibit (its reforge, enchants, stars); menus cancel every click, so it cannot leave
            if (!ex.stacks.isEmpty()) {
                try { icon = ItemStack.deserializeBytes(Base64.getDecoder().decode(ex.stacks.get(0))); } catch (Throwable ignored) { }
            }
            if (icon == null) {
                try { icon = Sky.items().create(e.isSet() ? e.pieces.get(0) : e.id); } catch (Throwable t) { icon = new ItemStack(Material.PAPER); }
            }
            List<String> lore = new ArrayList<>(info);
            lore.add("");
            lore.add("&a✔ On display");
            lore.add("&7Donated by &f" + ex.donorName + " &7on &f" + new SimpleDateFormat("MMM d, yyyy", Locale.US).format(new Date(ex.donatedAt)));
            if (e.isSet()) lore.add("&7Pieces: &f" + e.pieces.size());
            if (Prog.museumRetrieve.get()) { lore.add(""); lore.add("&eClick to retrieve!"); }
            return Icons.withLore(icon, lore);
        }
        if (viaParent) {
            return Icons.of(Material.LIME_DYE, displayName(e), concat(info, "", "&a✔ Counted through an", "&aupgraded version on display."));
        }
        boolean has = findPieces(viewer, e) != null;
        if (ex != null && ex.borrowed) {
            return Icons.of(Material.ORANGE_DYE, displayName(e), concat(info, "", "&6Borrowed &7- the slot is empty.",
                    has ? "&eClick to put it back!" : "&7Bring it back to display it again."));
        }
        if (has) {
            return Icons.glow(Icons.of(Material.LIME_STAINED_GLASS_PANE, displayName(e), concat(info, "", "&aYou have this item!", "&eClick to donate!")));
        }
        List<String> lore = new ArrayList<>(info);
        lore.add("");
        lore.add("&cNot donated");
        if (e.isSet()) lore.add("&7Needs all " + e.pieces.size() + " pieces.");
        return Icons.of(Material.GRAY_DYE, displayName(e), lore);
    }

    private static List<String> concat(List<String> a, String... more) {
        List<String> out = new ArrayList<>(a);
        out.addAll(List.of(more));
        return out;
    }
}
