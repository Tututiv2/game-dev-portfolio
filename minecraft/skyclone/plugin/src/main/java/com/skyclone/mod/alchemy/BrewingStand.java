package com.skyclone.mod.alchemy;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Brewing stands. Wave 5: <b>every brewing stand block holds its own brew</b> (the reference behaviour):
 *
 * <ul>
 *   <li><b>ISLAND</b> - a stand on an instanced island you are a member of (your private island, your garden):
 *       its contents live in the profile's SHARED data ({@link IslandStands}, key {@code alchemy_stands}), so
 *       every co-op member sees the same bottles. One viewer at a time (a lock), so two members can never
 *       take the same potion out of two copies of one inventory.</li>
 *   <li><b>PUBLIC</b> - any other stand block in a game world (the hub's alchemy shop, someone else's island):
 *       each block keeps YOUR brew, separately from everyone else's ({@link AlchemyData#stands}), so strangers
 *       never share bottles (a dupe surface on a public server) and nobody can steal your potions.</li>
 *   <li><b>PORTABLE</b> - {@code /brew} (dial {@code alchemy.brew-anywhere}): the old personal stand, kept
 *       so its contents from before wave 5 are never lost.</li>
 * </ul>
 * Every stand keeps brewing while closed and finishes in the background. Alchemy XP is paid to whoever
 * TAKES a brewed potion out (research 02 section 2.4.7), never twice.
 *
 * <pre>
 *  slot 13           ingredient / modifier (free)
 *  slots 38 40 42    bottles (free)
 *  columns 19-37 / 25-43   progress bars
 *  slot 4            status
 * </pre>
 */
final class BrewingStand implements Listener {

    static final int ING = 13;
    static final int[] BOTTLES = {38, 40, 42};
    static final int[] BAR_LEFT = {37, 28, 19};
    static final int[] BAR_RIGHT = {43, 34, 25};
    static final String ISLAND_KEY = "alchemy_stands";

    enum Where { PORTABLE, PUBLIC, ISLAND }

    /** Which stand: where its data lives + its key there + how to name it to the player. */
    record Ref(Where where, String key, String place) {
        static Ref portable() { return new Ref(Where.PORTABLE, "portable", "your portable stand (/brew)"); }
    }

    private record Session(Menu menu, Ref ref, String lock) {}

    private final EffectTable table;
    /** Viewer -> the stand menu they have open. */
    private final Map<UUID, Session> sessions = new HashMap<>();
    /** Stand lock -> the one viewer allowed to have it open. */
    private final Map<String, UUID> locks = new HashMap<>();

    BrewingStand(EffectTable table) {
        this.table = table;
    }

    private static AlchemyData data(Player p) { return EffectEngine.data(p); }

    private static IslandStands islandStands(Player p) { return Sky.profiles().shared(p, ISLAND_KEY, IslandStands.class); }

    // ---- which stand ---------------------------------------------------------------------------

    /** The stand a block is, for this player. */
    Ref refAt(Player p, Block b) {
        String island = Sky.worlds().islandOf(b.getWorld());
        if (island == null) return null;
        IslandDef def = Sky.worlds().island(island);
        String name = def != null && def.displayName != null ? def.displayName : Text.titleCase(island.replace('_', ' '));
        if (def != null && def.instanced && AlchemyDials.ISLAND_STANDS_SHARED.get() && member(p, b)) {
            return new Ref(Where.ISLAND, Stands.islandKey(island, b.getX(), b.getY(), b.getZ(), def.instanceSpacing),
                    "your " + Text.strip(name));
        }
        return new Ref(Where.PUBLIC, Stands.publicKey(island, b.getX(), b.getY(), b.getZ()), "the " + Text.strip(name));
    }

    private static boolean member(Player p, Block b) {
        try { return Sky.service(Features.PrivateIsland.class).canBuild(p, b.getLocation()); }
        catch (RuntimeException e) { return false; }
    }

    private static String lockKey(Player p, Ref r) {
        if (r.where() == Where.ISLAND) return "I|" + Sky.profiles().profileId(p) + "|" + r.key();
        return p.getUniqueId() + "|" + r.where() + "|" + r.key();
    }

    /** The stand's data (created when {@code create}); null when it does not exist and create is false. */
    static AlchemyData.Stand state(Player p, Ref r, boolean create) {
        Map<String, AlchemyData.Stand> m;
        switch (r.where()) {
            case PORTABLE -> {
                AlchemyData d = data(p);
                if (!create && d.portable == null && d.brewStart <= 0 && (d.stand == null || d.stand.isEmpty())) return null;
                return Stands.portable(d);
            }
            case PUBLIC -> m = data(p).stands;
            default -> m = islandStands(p).stands;
        }
        AlchemyData.Stand s = m.get(r.key());
        if (s == null) {
            if (!create) return null;
            s = new AlchemyData.Stand();
            m.put(r.key(), s);
        }
        Stands.normalise(s);
        return s;
    }

    /** Every stand of this player that has data: portable, public ones, their island's. */
    List<Ref> refsOf(Player p) {
        List<Ref> out = new ArrayList<>();
        AlchemyData d = data(p);
        if (d.portable != null || d.brewStart > 0 || (d.stand != null && !d.stand.isEmpty())) out.add(Ref.portable());
        for (String k : List.copyOf(d.stands.keySet())) out.add(new Ref(Where.PUBLIC, k, placeOf(k)));
        for (String k : List.copyOf(islandStands(p).stands.keySet())) out.add(new Ref(Where.ISLAND, k, "your " + placeOf(k)));
        return out;
    }

    private static String placeOf(String key) {
        int bar = key.indexOf('|');
        String island = bar < 0 ? key : key.substring(0, bar);
        IslandDef def = Sky.worlds().island(island);
        return Text.strip(def != null && def.displayName != null ? def.displayName : Text.titleCase(island.replace('_', ' ')));
    }

    // ---- locks -----------------------------------------------------------------------------------

    /** Is the session holding this lock still the viewer's open menu? */
    private boolean held(String lock) {
        UUID h = locks.get(lock);
        if (h == null) return false;
        Session s = sessions.get(h);
        Player hp = Bukkit.getPlayer(h);
        if (s == null || hp == null || !hp.isOnline() || !lock.equals(s.lock()) || Sky.menus().current(hp) != s.menu()) {
            locks.remove(lock);
            return false;
        }
        return true;
    }

    private void release(Player pl, String lock, Menu m) {
        UUID id = pl.getUniqueId();
        Session cur = sessions.get(id);
        if (cur != null && cur.menu() == m) sessions.remove(id);
        cur = sessions.get(id);
        if ((cur == null || !cur.lock().equals(lock)) && id.equals(locks.get(lock))) locks.remove(lock);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null && e.getPlayer().getUniqueId().equals(locks.get(s.lock()))) locks.remove(s.lock());
    }

    // ---- opening -----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onClickStand(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.BREWING_STAND) return;
        if (Sky.worlds().islandOf(b.getWorld()) == null) return;     // not a game world: leave vanilla alone
        if (e.getPlayer().isSneaking() && e.getItem() != null && e.getItem().getType().isBlock()) return; // placing
        e.setCancelled(true);
        if (!Sky.profiles().isReady(e.getPlayer())) return;
        Ref r = refAt(e.getPlayer(), b);
        if (r != null) open(e.getPlayer(), r);
    }

    /** {@code /brew}: the portable stand. */
    void open(Player p) { open(p, Ref.portable()); }

    /** May {@code /brew} open the portable stand? (the dial, or it still holds something to take out) */
    boolean portableAllowed(Player p) {
        if (AlchemyDials.BREW_ANYWHERE.get()) return true;
        if (!Sky.profiles().isReady(p)) return false;
        return !Stands.idle(state(p, Ref.portable(), false));
    }

    void open(Player p, Ref ref) {
        if (!Sky.profiles().isReady(p)) { Msg.error(p, "Your profile is still loading."); return; }
        String lock = lockKey(p, ref);
        Session mine = sessions.get(p.getUniqueId());
        if (mine != null && mine.lock().equals(lock) && Sky.menus().current(p) == mine.menu()) return;   // already open
        if (held(lock) && !p.getUniqueId().equals(locks.get(lock))) {
            Msg.error(p, "Someone else is using this brewing stand!");
            return;
        }
        completeStored(p, ref, true);
        AlchemyData.Stand s = state(p, ref, true);
        Menu m = new Menu("Brewing Stand", 6);
        m.chrome();
        m.free(ING);
        m.free(BOTTLES);
        m.keepFreeSlotItems();
        Inventory inv = m.getInventory();
        List<String> slots = Stands.slots(s);
        inv.setItem(ING, PotionItems.decode(slots.get(0)));
        for (int i = 0; i < 3; i++) inv.setItem(BOTTLES[i], PotionItems.decode(slots.get(i + 1)));
        if (arrange(p, m)) sync(p, m, ref);     // a stand saved before arrange() existed may hold a stacked slot
        m.set(45, Icons.of(Material.BOOK, "&aBrewing Guide", "&7Every potion, its ingredients", "&7and how to boost it.", "",
                "&eClick to view!"), c -> AlchemyMenus.guide(c.player(), 0));
        m.set(53, Icons.of(Material.POTION, "&aActive Effects", "&7See what you are under right now.", "",
                "&eClick to view!"), c -> AlchemyMenus.effects(c.player()));
        m.set(48, Icons.back("Alchemy"), c -> AlchemyMenus.hub(c.player()));
        m.onFreeSlotChange(c -> { arrange(c.player(), c.menu()); sync(c.player(), c.menu(), ref); c.menu().redraw(); });
        m.onClose(pl -> { sync(pl, m, ref); release(pl, lock, m); });
        m.drawer(menu -> draw(p, menu, ref));
        m.refreshEvery(10);
        sessions.put(p.getUniqueId(), new Session(m, ref, lock));
        locks.put(lock, p.getUniqueId());
        m.open(p);
        Msg.sound(p, Sound.BLOCK_BREWING_STAND_BREW, 1.6f);
    }

    /**
     * Copy the menu's free slots into the stand's data (on every change and on close). A bottle slot whose
     * brewed potion is gone pays its waiting Alchemy XP to {@code p}, the player who took it.
     */
    private void sync(Player p, Menu m, Ref ref) {
        if (!Sky.profiles().isReady(p)) return;
        AlchemyData.Stand s = state(p, ref, true);
        Inventory inv = m.getInventory();
        List<String> slots = new ArrayList<>(4);
        slots.add(PotionItems.encode(inv.getItem(ING)));
        String[] sigs = new String[3];
        for (int i = 0; i < 3; i++) {
            String enc = PotionItems.encode(inv.getItem(BOTTLES[i]));
            slots.add(enc);
            sigs[i] = Stands.sig(enc);
        }
        double xp = Stands.collect(s, sigs);
        s.slots = slots;
        s.used = System.currentTimeMillis();
        if (xp > 0) payXp(p, xp);
    }

    private static void payXp(Player p, double xp) {
        double x = xp * AlchemyDials.XP_MULT.get();
        if (x > 0) Sky.service(SkillService.class).addXp(p, SkillType.ALCHEMY, x, "brewing");
    }

    /** The stand's slots in {@link Brew#arrange} order: ingredient, then the three bottles. */
    private static final int[] SLOTS = {ING, BOTTLES[0], BOTTLES[1], BOTTLES[2]};

    private Brew.SlotKind kindOf(ItemStack st) {
        if (st == null || st.getType().isAir() || st.getAmount() <= 0) return Brew.SlotKind.EMPTY;
        String id = PotionItems.itemId(st);
        if (table.isBrewingItem(id)) return Brew.SlotKind.INGREDIENT;
        if (PotionItems.bottleOf(st) != null) return Brew.SlotKind.BOTTLE;
        return Brew.SlotKind.OTHER;
    }

    /**
     * Put the stand right after any change (see {@link Brew#arrange}): a shift-clicked stack of Water
     * Bottles lands in the ingredient slot because core fills free slots in index order, so it is
     * moved down one per bottle slot; extras go back to the player. Returns true if anything moved.
     */
    private boolean arrange(Player p, Menu m) {
        Inventory inv = m.getInventory();
        ItemStack[] items = new ItemStack[SLOTS.length];
        Brew.SlotKind[] kind = new Brew.SlotKind[SLOTS.length];
        int[] amount = new int[SLOTS.length];
        for (int i = 0; i < SLOTS.length; i++) {
            items[i] = inv.getItem(SLOTS[i]);
            kind[i] = kindOf(items[i]);
            amount[i] = kind[i] == Brew.SlotKind.EMPTY ? 0 : items[i].getAmount();
        }
        Brew.Arrangement a = Brew.arrange(kind, amount);
        if (!a.changed()) return false;
        for (int t = 0; t < SLOTS.length; t++) {
            ItemStack put = null;
            if (a.from()[t] >= 0 && a.take()[t] > 0) { put = items[a.from()[t]].clone(); put.setAmount(a.take()[t]); }
            inv.setItem(SLOTS[t], put);
        }
        for (int s = 0; s < SLOTS.length; s++) {
            if (a.back()[s] <= 0) continue;
            ItemStack r = items[s].clone();
            r.setAmount(a.back()[s]);
            Sky.loot().give(p, r, LootSource.STORAGE);
        }
        return true;
    }

    // ---- the brew ----------------------------------------------------------------------------

    /** What would happen if the brew finished now. */
    record Plan(String ingredient, Brew.Result[] results, String blocked, double xp) {
        boolean ok() { return blocked == null && results != null; }
        int count() { int n = 0; if (results != null) for (Brew.Result r : results) if (r != null) n++; return n; }
    }

    Plan plan(Player p, ItemStack ingredient, ItemStack[] bottles) { return plan(p, ingredient, bottles, true); }

    /** {@code unlocks}: check collection unlocks (false when finishing a brew someone already started). */
    Plan plan(Player p, ItemStack ingredient, ItemStack[] bottles, boolean unlocks) {
        String ing = PotionItems.itemId(ingredient);
        if (ing == null) return new Plan(null, null, "&7Put an ingredient in the top slot.", 0);
        boolean anyBottle = false;
        Brew.Result[] out = new Brew.Result[bottles.length];
        double xp = 0;
        String lockedBy = null;
        boolean stacked = false;
        for (int i = 0; i < bottles.length; i++) {
            Brew.Bottle b = PotionItems.bottleOf(bottles[i]);
            if (b == null) continue;
            anyBottle = true;
            if (bottles[i].getAmount() > 1) { stacked = true; continue; }   // one bottle per slot: never brew a stack into one potion
            Brew.Result r = Brew.react(table, b, ing);
            if (r == null) continue;
            if (unlocks && r.ingredient() != null && r.ingredient().unlock != null
                    && !Sky.requirements().check(p, r.ingredient().unlock)) {
                lockedBy = r.ingredient().unlock;
                continue;
            }
            out[i] = r;
            xp += r.xp();
        }
        if (!anyBottle) return new Plan(ing, null, "&7Put water bottles or potions in the bottom slots.", 0);
        Plan pl = new Plan(ing, out, null, xp);
        if (pl.count() == 0) {
            if (lockedBy != null) return new Plan(ing, null, "&cRequires " + Sky.requirements().describe(lockedBy) + "&c!", 0);
            if (stacked) return new Plan(ing, null, "&cOnly one bottle per slot! Split your stack first.", 0);
            String why = brewRefusal(ing, bottles);
            if (why != null) return new Plan(ing, null, why, 0);
            return new Plan(ing, null, "&c" + Text.strip(name(ing)) + " does nothing to these bottles.", 0);
        }
        return pl;
    }

    /** A Basic Brew refused a real potion ingredient: say which potions it does make. */
    private String brewRefusal(String ing, ItemStack[] bottles) {
        if (table.ingredient("AWKWARD", ing) == null) return null;
        for (ItemStack st : bottles) {
            Brew.Bottle b = PotionItems.bottleOf(st);
            if (b == null || !b.isBasicBrew()) continue;
            EffectTable.BasicBrew bb = table.brew(b.brew());
            if (bb == null) continue;
            List<String> names = new ArrayList<>();
            for (EffectTable.Effect e : table.brewsInto(bb)) names.add(e.name);
            return "&c" + bb.name + " can't brew that. &7It only makes: &f" + String.join(", ", names)
                    + "&7. Use an Awkward Potion for anything else.";
        }
        return null;
    }

    private static String name(String id) {
        var d = Sky.items().def(id);
        return d == null ? Text.titleCase(id) : d.rarity.code() + d.name;
    }

    /** Witch pet "Quick Brew": % faster brewing (pets ability key {@code witch_alchemism}). */
    static double fasterPct(Player p) {
        try { return Math.max(0, Sky.service(Features.Pets.class).abilityValue(p, "witch_alchemism", "a")); }
        catch (RuntimeException e) { return 0; }
    }

    private void start(Player p, AlchemyData.Stand s, Plan plan, long now) {
        s.brewStart = now;
        s.brewIngredient = plan.ingredient();
        s.brewMillis = Stands.brewMillis(AlchemyDials.BREW_SECONDS.get(), fasterPct(p));
        s.brewerPct = EffectEngine.alchemyLevel(p) * AlchemyDials.BREWER_PCT.get();
        s.brewer = p.getUniqueId().toString();
        Msg.sound(p, Sound.BLOCK_BREWING_STAND_BREW, 1f);
    }

    private static ItemStack[] bottles(Inventory inv) {
        return new ItemStack[]{inv.getItem(BOTTLES[0]), inv.getItem(BOTTLES[1]), inv.getItem(BOTTLES[2])};
    }

    private void draw(Player p, Menu m, Ref ref) {
        if (!p.isOnline() || !Sky.profiles().isReady(p)) return;
        AlchemyData.Stand s = state(p, ref, true);
        Inventory inv = m.getInventory();
        Plan plan = plan(p, inv.getItem(ING), bottles(inv));
        long now = System.currentTimeMillis();
        int dial = AlchemyDials.BREW_SECONDS.get();
        if (!plan.ok()) {
            if (s.brewStart > 0) Stands.stop(s);
        } else if (s.brewStart <= 0 || !plan.ingredient().equals(s.brewIngredient)) {
            start(p, s, plan, now);
        } else if (now - s.brewStart >= Stands.need(s, dial)) {
            finish(p, s, inv, plan);
            Stands.stop(s);
            sync(p, m, ref);
            plan = plan(p, inv.getItem(ING), bottles(inv));
            if (plan.ok()) start(p, s, plan, now);
            Msg.sound(p, Sound.BLOCK_BREWING_STAND_BREW, 2f);
        }
        long need = Stands.need(s, dial);
        double frac = s.brewStart > 0 ? Math.min(1, (now - s.brewStart) / (double) need) : 0;
        drawBars(m, frac, s.brewStart > 0);
        m.set(4, status(plan, s, ref, need, now));
        m.set(22, Icons.of(s.brewStart > 0 ? Material.BLAZE_POWDER : Material.GRAY_DYE,
                s.brewStart > 0 ? "&6Brewing..." : "&7Idle",
                s.brewStart > 0 ? "&7Done in &a" + Text.duration(Math.max(0, (need - (now - s.brewStart) + 999) / 1000)) : "&7Nothing is brewing."));
    }

    private static void drawBars(Menu m, double frac, boolean brewing) {
        int lit = (int) Math.floor(frac * 3 + 1e-9);
        for (int i = 0; i < 3; i++) {
            Material mat = !brewing ? Material.GRAY_STAINED_GLASS_PANE
                    : i < lit ? Material.ORANGE_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE;
            String n = brewing ? "&6Brewing " + (int) Math.round(frac * 100) + "%" : "&7Not brewing";
            m.set(BAR_LEFT[i], Icons.of(mat, n));
            m.set(BAR_RIGHT[i], Icons.of(mat, n));
        }
    }

    private ItemStack status(Plan plan, AlchemyData.Stand s, Ref ref, long need, long now) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Top slot: an ingredient or modifier.");
        lore.add("&7Bottom slots: up to &a3 &7bottles.");
        lore.add("");
        if (plan.ok()) {
            lore.add("&7Brewing: " + name(plan.ingredient()));
            for (Brew.Result r : plan.results()) {
                if (r == null) continue;
                ItemStack preview = PotionItems.itemOf(r.bottle());
                EffectTable.BasicBrew bb = r.bottle().kind() == Brew.Kind.POTION ? table.brew(r.bottle().brew()) : null;
                if (preview != null) lore.add("  &8➜ " + Sky.items().rarity(preview).code() + Sky.items().def(Sky.items().idOf(preview)).name
                        + (bb != null ? " &8(" + bb.name + ")" : ""));
            }
            lore.add("");
            lore.add("&7Alchemy XP: &3+" + Text.commas(plan.xp() * AlchemyDials.XP_MULT.get()) + " &8(when taken out)");
            if (s.brewStart > 0) lore.add("&7Time left: &a" + Text.duration(Math.max(0, (need - (now - s.brewStart) + 999) / 1000)));
            if (s.brewMillis > 0 && s.brewMillis < Math.max(1, AlchemyDials.BREW_SECONDS.get()) * 1000L)
                lore.add("&8Quick Brew: " + Text.commas(s.brewMillis / 1000.0) + "s per step");
        } else {
            lore.addAll(Text.wrap(plan.blocked(), 32));
        }
        double waiting = Stands.pending(s);
        if (waiting > 0) {
            lore.add("");
            lore.add("&7Waiting Alchemy XP: &3+" + Text.commas(waiting * AlchemyDials.XP_MULT.get()));
            lore.add("&8Take the potions out to collect it.");
        }
        lore.add("");
        switch (ref.where()) {
            case ISLAND -> { lore.add("&8This stand is shared with your co-op"); lore.add("&8and keeps brewing while closed."); }
            case PUBLIC -> { lore.add("&8This stand keeps YOUR brew, separate"); lore.add("&8from everyone else's, while closed."); }
            default -> { lore.add("&8Your portable stand (/brew) keeps"); lore.add("&8brewing while closed."); }
        }
        return Icons.of(Material.BREWING_STAND, "&aBrewing Stand", lore);
    }

    /**
     * Apply a finished brew to the given slots: consumes one ingredient, transforms the bottles and stores
     * each bottle's Alchemy XP on the stand, to be paid when the potion is taken out. Returns potions made.
     */
    private int finish(Player viewer, AlchemyData.Stand s, Inventory inv, Plan plan) {
        ItemStack ing = inv.getItem(ING);
        if (ing == null || !plan.ingredient().equals(PotionItems.itemId(ing))) return 0;
        int made = 0;
        double brewer = Math.max(0, s.brewerPct);
        for (int i = 0; i < 3; i++) {
            Brew.Result r = plan.results()[i];
            if (r == null) continue;
            Brew.Bottle nb = r.bottle();
            Brew.Bottle before = PotionItems.bottleOf(inv.getItem(BOTTLES[i]));
            if (nb.kind() == Brew.Kind.POTION && (before == null || before.kind() != Brew.Kind.POTION)) nb = nb.withBrewer(brewer);
            ItemStack out = PotionItems.itemOf(nb);
            if (out == null) continue;
            inv.setItem(BOTTLES[i], out);
            Stands.store(s, i, r.xp(), Stands.sig(PotionItems.encode(inv.getItem(BOTTLES[i]))));
            made++;
        }
        if (made == 0) return 0;
        if (ing.getAmount() <= 1) inv.setItem(ING, null);
        else { ItemStack c = ing.clone(); c.setAmount(ing.getAmount() - 1); inv.setItem(ING, c); }
        Player brewerPlayer = s.brewer == null ? viewer : playerOrNull(s.brewer);
        if (brewerPlayer == null) brewerPlayer = viewer;
        if (brewerPlayer != null && Sky.profiles().isReady(brewerPlayer)) data(brewerPlayer).potionsBrewed += made;
        return made;
    }

    private static Player playerOrNull(String uuid) {
        try { return Bukkit.getPlayer(UUID.fromString(uuid)); } catch (RuntimeException e) { return null; }
    }

    /**
     * Finish a brew whose menu is closed (on open and from the 1-second ticker), working on the serialised
     * stand. Skipped while somebody has that stand open: the open menu's drawer owns it.
     */
    boolean completeStored(Player p, Ref ref, boolean quiet) {
        if (!Sky.profiles().isReady(p)) return false;
        String lock = lockKey(p, ref);
        if (held(lock)) return false;
        AlchemyData.Stand s = state(p, ref, false);
        if (s == null || s.brewStart <= 0) return false;
        if (System.currentTimeMillis() - s.brewStart < Stands.need(s, AlchemyDials.BREW_SECONDS.get())) return false;
        Inventory tmp = Bukkit.createInventory(null, 54);
        List<String> slots = Stands.slots(s);
        tmp.setItem(ING, PotionItems.decode(slots.get(0)));
        for (int i = 0; i < 3; i++) tmp.setItem(BOTTLES[i], PotionItems.decode(slots.get(i + 1)));
        Plan plan = plan(p, tmp.getItem(ING), bottles(tmp), false);
        int made = 0;
        if (plan.ok() && plan.ingredient().equals(s.brewIngredient)) {
            made = finish(p, s, tmp, plan);
            List<String> out = new ArrayList<>(4);
            out.add(PotionItems.encode(tmp.getItem(ING)));
            for (int b : BOTTLES) out.add(PotionItems.encode(tmp.getItem(b)));
            s.slots = out;
        }
        Player tell = s.brewer == null ? p : playerOrNull(s.brewer);
        if (tell == null) tell = p;
        Stands.stop(s);
        if (made > 0 && !quiet) {
            Msg.send(tell, "&aYour potions finished brewing! &7Collect them at " + ref.place() + "&7.");
            Msg.sound(tell, Sound.BLOCK_BREWING_STAND_BREW, 2f);
        }
        return made > 0;
    }

    void tickAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            try { tick(p); }
            catch (RuntimeException ex) { Sky.log().warning("[alchemy] brew tick failed for " + p.getName() + ": " + ex); }
        }
    }

    private void tick(Player p) {
        for (Ref r : refsOf(p)) completeStored(p, r, false);
        // forget public stands that hold nothing (they are only created by opening one)
        AlchemyData d = data(p);
        d.stands.entrySet().removeIf(en -> Stands.idle(en.getValue())
                && !held(lockKey(p, new Ref(Where.PUBLIC, en.getKey(), ""))));
    }

    /** Admin: make every running brew of this player finish now. Returns how many were running. */
    int rush(Player p) {
        int n = 0;
        for (Ref r : refsOf(p)) {
            AlchemyData.Stand s = state(p, r, false);
            if (s == null || s.brewStart <= 0) continue;
            s.brewStart = 1;
            n++;
            completeStored(p, r, false);
        }
        return n;
    }

    /** Is any of this player's stands brewing? (the Alchemy hub's status line) */
    boolean anyBrewing(Player p) {
        for (Ref r : refsOf(p)) {
            AlchemyData.Stand s = state(p, r, false);
            if (s != null && s.brewStart > 0) return true;
        }
        return false;
    }

    /** Admin: delete a stand's contents (closes it for whoever has it open first). */
    void clear(Player p, Ref r) {
        closeViewer(lockKey(p, r));
        AlchemyData.Stand s = state(p, r, false);
        if (s == null) return;
        s.slots = new ArrayList<>();
        s.xp = new ArrayList<>();
        s.sig = new ArrayList<>();
        Stands.stop(s);
        Stands.normalise(s);
    }

    private void closeViewer(String lock) {
        if (!held(lock)) return;
        Player h = Bukkit.getPlayer(locks.get(lock));
        if (h != null) h.closeInventory();
    }

    // ---- breaking a stand on your island -----------------------------------------------------

    /**
     * A brewing stand broken on a member's island hands its contents (and any waiting Alchemy XP) to the
     * player who broke it, then forgets the brew, so a new stand placed there starts empty.
     */
    /** A stand somebody has open cannot be broken (their open menu would write its bottles back afterwards). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakInUse(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.BREWING_STAND) return;
        Player p = e.getPlayer();
        if (!Sky.profiles().isReady(p) || Sky.worlds().islandOf(b.getWorld()) == null) return;
        Ref r = refAt(p, b);
        if (r == null || r.where() != Where.ISLAND || !held(lockKey(p, r))) return;
        e.setCancelled(true);
        Msg.error(p, "Someone is using this brewing stand!");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.BREWING_STAND) return;
        Player p = e.getPlayer();
        if (!Sky.profiles().isReady(p) || Sky.worlds().islandOf(b.getWorld()) == null) return;
        Ref r = refAt(p, b);
        if (r == null || r.where() != Where.ISLAND || held(lockKey(p, r))) return;
        AlchemyData.Stand s = state(p, r, false);
        if (s == null) return;
        int returned = 0;
        for (String enc : Stands.slots(s)) {
            ItemStack st = PotionItems.decode(enc);
            if (st == null || st.getType().isAir()) continue;
            Sky.loot().give(p, st, LootSource.STORAGE);
            returned += st.getAmount();
        }
        double xp = Stands.pending(s);
        islandStands(p).stands.remove(r.key());
        if (xp > 0) payXp(p, xp);
        if (returned > 0) Msg.send(p, "&eThe brewing stand's contents (&f" + returned + "&e) were returned to you.");
    }

    /** Status lines for the admin command. */
    List<String> describe(Player p) {
        List<String> out = new ArrayList<>();
        for (Ref r : refsOf(p)) {
            AlchemyData.Stand s = state(p, r, false);
            if (s == null) continue;
            StringBuilder sb = new StringBuilder("&e" + r.where() + " &f" + r.key() + " &7brewing=" + (s.brewStart > 0)
                    + (s.brewIngredient != null ? " (" + s.brewIngredient + ")" : "") + " xpWaiting=" + Text.commas(Stands.pending(s))
                    + (held(lockKey(p, r)) ? " &c[open]" : ""));
            List<String> slots = Stands.slots(s);
            for (int i = 0; i < slots.size(); i++) {
                ItemStack st = PotionItems.decode(slots.get(i));
                if (st != null) sb.append("\n   &7").append(i == 0 ? "ingredient" : "bottle " + i).append(": &f")
                        .append(PotionItems.itemId(st)).append(" x").append(st.getAmount());
            }
            out.add(sb.toString());
        }
        if (out.isEmpty()) out.add("&7No brewing stands in use.");
        return out;
    }
}
