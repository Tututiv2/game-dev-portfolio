package com.skyclone.mod.crafting;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The crafting experience: the "Craft Item" grid (any crafting table, /craft, the SkyBlock Menu),
 * the Recipe Book (/recipes, /recipe &lt;item&gt;), Supercraft and Quick Crafting from inventory and
 * sacks, Carpentry XP, collection Trades, furniture, plus the vanilla recipe set and the gap-filling
 * reference recipes so every craftable item in the game is craftable through one engine.
 */
public final class CraftingModule implements GameModule, Listener {

    CraftDials dials;
    RecipeIndex index;
    CraftEngine engine;
    CraftMenu craftMenu;
    RecipeBook book;
    Trades trades;
    Furniture furniture;
    Sources sources;

    @Override public String id() { return "crafting"; }

    @Override
    public void enable() {
        dials = new CraftDials();
        index = new RecipeIndex();
        index.load();
        engine = new CraftEngine(index, dials);
        craftMenu = new CraftMenu(this);
        book = new RecipeBook(this);
        sources = new Sources(this);
        trades = new Trades(this);
        trades.load();
        furniture = new Furniture(this);
        furniture.enable();

        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        registerCommands();
        new CraftAdmin(this).register();
        CraftSelfTests.register(this);
    }

    @Override
    public void postEnable() {
        furniture.postEnable();
        // After EVERY module's postEnable (materials registers its items there), on the first tick.
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            furniture.registerRecipes();
            index.lateRegister(dials.vanillaRecipes.get(), dials.fallbackRecipes.get());
            index.ensure();
            int smelts = 0;
            sources.clearCaches();
            try { smelts = sources.loadFurnace(); } catch (RuntimeException ex) { Sky.log().warning("[crafting] furnace recipes: " + ex); }
            int looted = 0;
            try { looted = sources.loadLoot(); } catch (RuntimeException ex) { Sky.log().warning("[crafting] loot tables: " + ex); }
            Sky.log().info("[crafting] " + index.all().size() + " grid recipes ready, " + smelts + " furnace recipes indexed, "
                    + looted + " items with boss/chest/fishing sources");
            applySellCap(false);
        });
    }

    /**
     * Lower every crafted item's npcSell that would make craft-and-sell a coin printer (see {@link SellCap}).
     * Returns what changed (id -> "old -> new"); {@code dry} only reports.
     */
    java.util.Map<String, String> applySellCap(boolean dry) {
        java.util.Map<String, Double> low = SellCap.compute(SellCap.ways(index.all(), dials.sellCapGated.get()),
                CraftEngine::npcSell, dials.sellCapFactor.get());
        java.util.Map<String, String> changed = new java.util.TreeMap<>();
        for (java.util.Map.Entry<String, Double> e : low.entrySet()) {
            com.skyclone.api.item.ItemDef d = Sky.items().def(e.getKey());
            if (d == null) continue;
            changed.put(e.getKey(), Math.round(d.npcSell) + " -> " + Math.round(e.getValue()));
            if (!dry) d.npcSell = e.getValue();
        }
        if (!dry && !changed.isEmpty()) {
            Sky.log().info("[crafting] lowered " + changed.size() + " NPC sell prices to " + dials.sellCapFactor.get()
                    + "x their recipe inputs (crafting.sell-cap-factor): " + changed);
        }
        return changed;
    }

    @Override
    public void disable() {
        if (furniture != null) furniture.shutdown();
    }

    private void registerCommands() {
        Sky.commands().register(SkyCommand.of("craft", "Open a 3x3 crafting grid")
                .alias("workbench", "wb", "craftingtable").category("Crafting").playerOnly()
                .run((s, a) -> craftMenu.open((Player) s)));
        Sky.commands().register(SkyCommand.of("recipes", "Open the Recipe Book")
                .alias("recipebook", "rb").category("Crafting").playerOnly()
                .run((s, a) -> book.openRoot((Player) s)));
        Sky.commands().register(SkyCommand.of("recipe", "Show the recipe for an item").usage("<item>")
                .category("Crafting").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (a.length == 0) { book.openRoot(p); return; }
                    String q = String.join(" ", a);
                    if (q.length() > 48) q = q.substring(0, 48);
                    book.openFor(p, q);
                })
                .tab((s, a) -> completeResults(a.length == 0 ? "" : a[a.length - 1])));
        Sky.commands().register(SkyCommand.of("trades", "Open the collection Trades menu")
                .category("Crafting").playerOnly()
                .run((s, a) -> trades.open((Player) s)));
        Sky.commands().register(SkyCommand.of("furniture", "Browse furniture you can craft and place")
                .category("Crafting").playerOnly()
                .run((s, a) -> furniture.openMenu((Player) s)));
    }

    List<String> completeResults(String prefix) {
        String pre = prefix.toUpperCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String id : index.allResults()) {
            if (id.startsWith(pre)) out.add(id);
            if (out.size() >= 60) break;
        }
        // Wave 3: /recipe also answers forged, shop-bought and smelted items (the How to Get page).
        if (out.size() < 60 && sources != null && pre.length() >= 2) {
            for (String id : sources.search(prefix)) {
                if (id.startsWith(pre) && !out.contains(id)) out.add(id);
                if (out.size() >= 60) break;
            }
        }
        out.sort(null);
        return out;
    }

    // ---- crafting tables ----------------------------------------------------------------------------

    /**
     * Any crafting table opens the Craft Item menu. LOW priority: core denies workstation use outside
     * BUILD zones at HIGH, so we see the click first either way (CORE_GUIDE §0.10).
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onTable(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.CRAFTING_TABLE) return;
        if (!dials.interceptTables.get()) return;
        Player p = e.getPlayer();
        if (e.getHand() != EquipmentSlot.HAND) {
            // The off-hand pass of the same click must not open the vanilla table behind our menu (denying the
            // BLOCK still lets an off-hand block be placed against it).
            e.setUseInteractedBlock(Event.Result.DENY);
            return;
        }
        ItemStack hand = e.getItem();
        if (p.isSneaking() && hand != null && hand.getType().isBlock() && !hand.getType().isAir()) return; // sneak-place
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        if (!Sky.profiles().isReady(p)) return;
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (p.isOnline()) craftMenu.open(p); });
    }

    /**
     * Wave 3: the vanilla Crafter (an auto-crafting block hoppers can feed) never goes through
     * PrepareItemCraftEvent, so core's vanilla guard does not see it. An Enchanted Diamond is a DIAMOND underneath:
     * without this a Crafter would turn custom items into plain vanilla results (lost items, or a coin printer for
     * any cheap custom item whose material is valuable). A Crafter holding any custom sky item crafts nothing.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafter(org.bukkit.event.block.CrafterCraftEvent e) {
        if (!(e.getBlock().getState(false) instanceof org.bukkit.block.Crafter c)) return;
        for (ItemStack s : c.getInventory().getContents()) {
            if (s == null || s.getType().isAir()) continue;
            String id;
            try { id = Sky.items().idOf(s); } catch (RuntimeException ex) { continue; }
            if (id == null) continue;
            String plain = Sky.items().vanillaId(s.getType());
            if (!id.equals(plain) && !index.canon(id).equals(index.canon(plain))) { e.setCancelled(true); return; }
        }
    }

    // ---- Recipe Book progress kept fresh for the SkyBlock Menu ------------------------------------

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline() && index.lateDone) book.updateProgress(p); }, 60L);
    }

    @EventHandler
    public void onTier(SkyEvents.CollectionTierUp e) {
        if (e.player != null && index.lateDone) book.updateProgress(e.player);
    }

    @EventHandler
    public void onLevel(SkyEvents.SkillLevelUp e) {
        if (e.player != null && index.lateDone) book.updateProgress(e.player);
    }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) {
        if (e.player != null) engine.forget(e.player.getUniqueId());
    }
}
