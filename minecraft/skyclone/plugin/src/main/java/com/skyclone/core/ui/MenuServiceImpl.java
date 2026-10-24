package com.skyclone.core.ui;

import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.MenuClick;
import com.skyclone.api.ui.MenuService;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The one listener behind every {@link Menu}.
 *
 * <p>Rules (public-server safe):
 * <ul>
 *   <li>Every click in the menu (top) inventory is cancelled unless the slot is a declared free slot.
 *       That includes number-key swaps, double-click collect, drags and shift-clicks.</li>
 *   <li>Slot handlers run on the NEXT tick (Bukkit forbids opening/closing inventories inside a
 *       click event), debounced per player, and only if the same menu is still open.</li>
 *   <li>Free slots behave like a normal chest; {@code onFreeSlotChange} runs next tick.</li>
 *   <li>Player-inventory clicks are allowed. A shift-click into the menu is only honoured into free
 *       slots, and core moves the item itself. If the menu set {@code onPlayerInventoryClick}, every
 *       click in the player's inventory is cancelled and handed to that handler instead.</li>
 *   <li>On close: {@code onClose} runs first; anything it left in free slots goes back to the player.</li>
 * </ul>
 */
public final class MenuServiceImpl implements MenuService, Listener {

    private static final class Open {
        final Menu menu;
        long lastRedraw;
        Open(Menu menu, long now) { this.menu = menu; this.lastRedraw = now; }
    }

    private final JavaPlugin plugin;
    private final InputPrompts prompts;
    private final Map<UUID, Open> open = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    /** The menu whose close was handled most recently (used to detect a close event that never arrived). */
    private Menu lastHandled;
    private BukkitTask task;
    private long tick;
    /** >0 while core is dispatching inside an inventory event; open() defers itself then. */
    private int inEvent;

    public MenuServiceImpl(JavaPlugin plugin, InputPrompts prompts) {
        this.plugin = plugin;
        this.prompts = prompts;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Close every open menu (returning free-slot items) and stop the refresh task. */
    void stop() {
        if (task != null) { task.cancel(); task = null; }
        for (Map.Entry<UUID, Open> en : new ArrayList<>(open.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            Menu m = en.getValue().menu;
            if (p == null) continue;
            lastHandled = null;
            try { p.closeInventory(); } catch (Throwable t) { UiUtil.warn("closing a menu at shutdown", t); }
            if (lastHandled != m) handleClosed(p, m);   // listener may already be gone
        }
        open.clear();
        lastHandled = null;
    }

    boolean busy() { return inEvent > 0; }

    int openCount() { return open.size(); }

    // ---- MenuService ---------------------------------------------------------------------------

    @Override
    public void open(Player p, Menu menu) {
        if (p == null || menu == null) return;
        if (!Bukkit.isPrimaryThread() || inEvent > 0) {
            Bukkit.getScheduler().runTask(plugin, () -> open(p, menu));
            return;
        }
        if (!p.isOnline()) return;
        Open cur = open.get(p.getUniqueId());
        if (cur != null && cur.menu == menu && p.getOpenInventory().getTopInventory().equals(menu.getInventory())) {
            return; // already showing; Menu.open() has redrawn it, the client sees the changes
        }
        InventoryView view;
        try {
            view = p.openInventory(menu.getInventory());
        } catch (Throwable t) {
            UiUtil.warn("opening menu '" + Text.strip(menu.title()) + "'", t);
            return;
        }
        if (view == null) return; // another plugin cancelled the open
        if (view.getTopInventory().getHolder(false) == menu) {
            open.put(p.getUniqueId(), new Open(menu, tick));
        }
    }

    @Override
    public Menu current(Player p) {
        if (p == null) return null;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getHolder(false) instanceof Menu m) return m;
        return null;
    }

    @Override
    public <T> Menu paged(String title, List<T> entries, Function<T, ItemStack> icon,
                          Consumer<PagedClick<T>> onClick, Consumer<Menu> decorate, int page) {
        final List<T> list = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
        final int pg = PagedLayout.clamp(page, list.size());
        Menu m = new Menu((title == null ? "" : title) + PagedLayout.suffix(pg, list.size()), 6);
        m.chrome();
        int start = PagedLayout.firstIndex(pg);
        for (int i = 0; i < PagedLayout.PER_PAGE && start + i < list.size(); i++) {
            final T entry = list.get(start + i);
            ItemStack it;
            try {
                it = icon == null ? null : icon.apply(entry);
            } catch (Throwable t) {
                UiUtil.warn("paged menu '" + title + "' icon for entry " + (start + i), t);
                it = Icons.of(Material.BARRIER, "&cError", "&7This entry failed to draw.");
            }
            if (UiUtil.empty(it)) it = Icons.of(Material.BARRIER, "&c?");
            m.set(PagedLayout.INNER[i], it, onClick == null ? null : c -> onClick.accept(new PagedClick<>(c, entry, pg)));
        }
        if (list.isEmpty()) m.set(22, Icons.of(Material.GRAY_DYE, "&7Nothing to show here!"));
        if (decorate != null) {
            try { decorate.accept(m); } catch (Throwable t) { UiUtil.warn("paged menu '" + title + "' decorate", t); }
        }
        if (PagedLayout.hasPrev(pg)) {
            m.set(PagedLayout.PREV_SLOT, Icons.prevPage(pg + 1),
                    c -> paged(title, list, icon, onClick, decorate, pg - 1).open(c.player()));
        }
        if (PagedLayout.hasNext(pg, list.size())) {
            m.set(PagedLayout.NEXT_SLOT, Icons.nextPage(pg + 1),
                    c -> paged(title, list, icon, onClick, decorate, pg + 1).open(c.player()));
        }
        return m;
    }

    @Override
    public void confirm(Player p, String title, List<String> description, Runnable onYes, Runnable onNo) {
        Menu m = buildConfirm(title, description, onYes, onNo);
        m.open(p);
    }

    Menu buildConfirm(String title, List<String> description, Runnable onYes, Runnable onNo) {
        String t = title == null || title.isEmpty() ? "Are you sure?" : title;
        Menu m = new Menu(t, 3);
        m.chrome();
        m.set(m.closeSlot(), Icons.filler());          // Cancel is the way out
        final boolean[] done = {false};
        List<String> info = new ArrayList<>();
        if (description != null) info.addAll(description);
        m.set(11, Icons.of(Material.GREEN_TERRACOTTA, "&aConfirm", "&eClick to confirm!"), c -> {
            if (done[0]) return;
            done[0] = true;
            finish(c.player(), m, onYes);
        });
        m.set(13, Icons.of(Material.OAK_SIGN, "&e" + t, info));
        m.set(15, Icons.of(Material.RED_TERRACOTTA, "&cCancel", "&eClick to cancel!"), c -> {
            if (done[0]) return;
            done[0] = true;
            finish(c.player(), m, onNo);
        });
        return m;
    }

    private void finish(Player p, Menu m, Runnable action) {
        if (action != null) {
            try { action.run(); }
            catch (Throwable t) { UiUtil.warn("confirm action in '" + m.title() + "'", t); Msg.error(p, "Something went wrong."); }
        }
        if (current(p) == m) p.closeInventory();
    }

    @Override
    public void input(Player p, String prompt, Consumer<String> result) {
        if (p == null) return;
        if (!Bukkit.isPrimaryThread() || inEvent > 0) {
            Bukkit.getScheduler().runTask(plugin, () -> input(p, prompt, result));
            return;
        }
        if (!p.isOnline()) return;
        InventoryType type = p.getOpenInventory().getType();
        if (type != InventoryType.CRAFTING && type != InventoryType.CREATIVE) p.closeInventory();
        prompts.ask(p, prompt == null ? "Enter a value" : prompt, result);
    }

    @Override
    public void inputNumber(Player p, String prompt, Consumer<Double> result) {
        askNumber(p, prompt, result, 0);
    }

    private void askNumber(Player p, String prompt, Consumer<Double> result, int attempt) {
        input(p, prompt, s -> {
            if (s == null) { result.accept(null); return; }
            Double v = NumberParse.parse(s);
            if (v == null) {
                if (attempt >= 4) {
                    Msg.error(p, "Too many invalid numbers, cancelled.");
                    result.accept(null);
                    return;
                }
                Msg.error(p, "That's not a valid number! Try something like 64, 1.5k or 2m.");
                askNumber(p, prompt, result, attempt + 1);
                return;
            }
            result.accept(v);
        });
    }

    // ---- refresh ------------------------------------------------------------------------------

    private void tick() {
        tick++;
        if (open.isEmpty()) return;
        for (Map.Entry<UUID, Open> en : new ArrayList<>(open.entrySet())) {
            Open o = en.getValue();
            int every = o.menu.refreshTicks();
            if (every <= 0 || tick - o.lastRedraw < every) continue;
            o.lastRedraw = tick;
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) { open.remove(en.getKey(), o); continue; }
            if (p.getOpenInventory().getTopInventory().getHolder(false) != o.menu) continue;
            run(p, o.menu, "redraw", o.menu::redraw);
        }
    }

    // ---- events -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) return;

        boolean blocked = e.isCancelled();   // e.g. the SkyBlock Menu star guard ran first
        InventoryAction action = e.getAction();
        ClickType click = e.getClick();
        int raw = e.getRawSlot();
        int topSize = top.getSize();

        // Double-click collect pulls matching items out of EVERY slot of the menu: never allowed.
        if (action == InventoryAction.COLLECT_TO_CURSOR || click == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
            return;
        }
        if (raw < 0) return;   // outside the window: dropping the cursor is fine

        if (raw < topSize) {
            if (menu.freeSlots().contains(raw)) {
                if (blocked) return;
                if (action == InventoryAction.UNKNOWN) { e.setCancelled(true); return; }
                scheduleFreeChange(p, menu, raw, click);
                return;
            }
            e.setCancelled(true);
            if (blocked) return;
            Consumer<MenuClick> h = menu.handler(raw);
            if (h == null || !debounce(p)) return;
            MenuClick mc = new MenuClick(p, menu, raw, click, UiUtil.copy(e.getCurrentItem()), UiUtil.copy(e.getCursor()));
            UiUtil.later(() -> {
                if (!p.isOnline() || current(p) != menu) return;
                run(p, menu, "slot " + raw, () -> h.accept(mc));
            });
            return;
        }

        // ---- the player's own inventory ----
        BiConsumer<MenuClick, ItemStack> ph = menu.playerInventoryHandler();
        if (ph != null) {
            e.setCancelled(true);
            if (blocked || !debounce(p)) return;
            final int slot = e.getSlot();
            final ItemStack snapshot = UiUtil.copy(e.getCurrentItem());
            MenuClick mc = new MenuClick(p, menu, slot, click, snapshot, UiUtil.copy(e.getCursor()));
            UiUtil.later(() -> {
                if (!p.isOnline() || current(p) != menu) return;
                ItemStack live = p.getInventory().getItem(slot);
                if (!UiUtil.sameStack(live, snapshot)) return;   // it moved: never act on a stale item
                if (MenuStar.isStar(live)) return;
                run(p, menu, "player-inventory slot " + slot, () -> ph.accept(mc, live));
            });
            return;
        }
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            e.setCancelled(true);
            if (blocked || menu.freeSlots().isEmpty()) return;
            final int slot = e.getSlot();
            final ItemStack snapshot = UiUtil.copy(e.getCurrentItem());
            UiUtil.later(() -> shiftIntoFree(p, menu, slot, snapshot, click));
        }
        // anything else inside the player's own inventory is a normal click
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) return;
        if (e.isCancelled()) return;
        int topSize = top.getSize();
        int first = -1;
        for (int raw : e.getRawSlots()) {
            if (raw >= topSize) continue;
            if (!menu.freeSlots().contains(raw)) { e.setCancelled(true); return; }
            if (first < 0) first = raw;
        }
        if (first >= 0) scheduleFreeChange(p, menu, first, ClickType.LEFT);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!(e.getInventory().getHolder(false) instanceof Menu menu)) return;
        Open o = open.get(p.getUniqueId());
        if (o != null && o.menu == menu) open.remove(p.getUniqueId());
        handleClosed(p, menu);
    }

    /** Close any open menu BEFORE other quit listeners save the inventory, so returned items are kept. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        lastClick.remove(id);
        Open o = open.remove(id);
        if (o != null || current(p) != null) {
            Menu m = o != null ? o.menu : current(p);
            lastHandled = null;
            try { p.closeInventory(); } catch (Throwable t) { UiUtil.warn("closing menu on quit", t); }
            if (m != null && lastHandled != m) handleClosed(p, m);
            lastHandled = null;
        }
    }

    // ---- internals ------------------------------------------------------------------------------

    private void handleClosed(Player p, Menu menu) {
        lastHandled = menu;
        inEvent++;
        try {
            Consumer<Player> ch = menu.closeHandler();
            if (ch != null) run(p, menu, "onClose", () -> ch.accept(p));
            returnFreeItems(p, menu);
        } finally {
            inEvent--;
        }
    }

    /** Everything still sitting in a free slot goes back to the player (inventory, else their feet). */
    private void returnFreeItems(Player p, Menu menu) {
        if (menu.keepsFreeSlotItems()) return;   // a storage menu: its onClose saved them
        Inventory inv = menu.getInventory();
        for (int s : new ArrayList<>(menu.freeSlots())) {
            if (s < 0 || s >= inv.getSize()) continue;
            ItemStack it = inv.getItem(s);
            if (UiUtil.empty(it)) continue;
            inv.setItem(s, null);
            if (MenuStar.isStar(it)) continue;   // never duplicate the star
            try { UiUtil.give(p, it); }
            catch (Throwable t) {
                UiUtil.warn("returning " + it.getType() + " x" + it.getAmount() + " to " + p.getName(), t);
                p.getWorld().dropItem(p.getLocation(), it);
            }
        }
    }

    private void scheduleFreeChange(Player p, Menu menu, int slot, ClickType click) {
        Consumer<MenuClick> h = menu.freeSlotHandler();
        if (h == null) return;
        UiUtil.later(() -> fireFreeChange(p, menu, slot, click));
    }

    private void fireFreeChange(Player p, Menu menu, int slot, ClickType click) {
        Consumer<MenuClick> h = menu.freeSlotHandler();
        if (h == null || !p.isOnline() || current(p) != menu) return;
        MenuClick mc = new MenuClick(p, menu, slot, click, UiUtil.copy(menu.getInventory().getItem(slot)),
                UiUtil.copy(p.getItemOnCursor()));
        run(p, menu, "free slot " + slot, () -> h.accept(mc));
    }

    /** Shift-click from the player's inventory: merge into matching free stacks, then empty free slots. */
    private void shiftIntoFree(Player p, Menu menu, int slot, ItemStack snapshot, ClickType click) {
        if (!p.isOnline() || current(p) != menu) return;
        PlayerInventory pinv = p.getInventory();
        ItemStack live = pinv.getItem(slot);
        if (UiUtil.empty(live) || !UiUtil.sameStack(live, snapshot) || MenuStar.isStar(live)) return;
        Inventory top = menu.getInventory();
        List<Integer> free = new ArrayList<>();
        for (int s : menu.freeSlots()) if (s >= 0 && s < top.getSize()) free.add(s);
        Collections.sort(free);
        ItemStack moving = live.clone();
        int remaining = moving.getAmount();
        int max = Math.min(moving.getMaxStackSize(), top.getMaxStackSize());
        int firstChanged = -1;
        for (int s : free) {
            if (remaining <= 0) break;
            ItemStack t = top.getItem(s);
            if (UiUtil.empty(t) || !t.isSimilar(moving) || t.getAmount() >= max) continue;
            int mv = Math.min(remaining, max - t.getAmount());
            ItemStack merged = t.clone();
            merged.setAmount(t.getAmount() + mv);
            top.setItem(s, merged);
            remaining -= mv;
            if (firstChanged < 0) firstChanged = s;
        }
        for (int s : free) {
            if (remaining <= 0) break;
            if (!UiUtil.empty(top.getItem(s))) continue;
            ItemStack put = moving.clone();
            int mv = Math.min(remaining, max);
            put.setAmount(mv);
            top.setItem(s, put);
            remaining -= mv;
            if (firstChanged < 0) firstChanged = s;
        }
        if (firstChanged < 0) return;   // nothing fitted
        if (remaining <= 0) {
            pinv.setItem(slot, null);
        } else {
            ItemStack rest = moving.clone();
            rest.setAmount(remaining);
            pinv.setItem(slot, rest);
        }
        fireFreeChange(p, menu, firstChanged, click);
    }

    private boolean debounce(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        int gap = 100;
        try { gap = UiDials.menuDebounceMs.get(); } catch (Throwable ignored) { }
        if (last != null && now - last < gap) return false;
        lastClick.put(p.getUniqueId(), now);
        return true;
    }

    private void run(Player p, Menu menu, String what, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            UiUtil.warn("menu '" + Text.strip(menu.title()) + "' threw in " + what + " for " + p.getName(), t);
            try { Msg.error(p, "Something went wrong with that menu. Please report it to an admin."); }
            catch (Throwable ignored) { }
        }
    }
}
