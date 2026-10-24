package com.skyclone.mod.farming;

import com.google.gson.JsonPrimitive;
import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** Small rules around farming: animals pay Farming XP, vanilla growth stays off, tilling hoes, the fungi switch. */
final class FarmListener implements Listener {

    private final FarmingModule m;

    FarmListener(FarmingModule m) { this.m = m; }

    // ---- animals: Farming XP, not Combat XP (research 03 5.1) ---------------------------------------

    // NORMAL: runs before the skills module reads combatXp at HIGH.
    @EventHandler(priority = EventPriority.NORMAL)
    public void onAnimal(SkyEvents.MobKill e) {
        if (e.def == null || e.def.extra == null || !e.def.extra.has("farmAnimal")) return;
        e.combatXp = 0;
        if (e.killer == null) return;
        double xp = e.def.extra.has("farmingXp") ? e.def.extra.get("farmingXp").getAsDouble() : 0;
        if (xp > 0) Sky.service(SkillService.class).addXp(e.killer, SkillType.FARMING, xp * m.dials.xpMultiplier.get(), "farming:animal");
    }

    // ---- no vanilla growth on islands this module drives --------------------------------------------

    private boolean ours(Block b) {
        if (!m.dials.blockVanillaGrowth.get()) return false;
        String island = Sky.worlds().islandOf(b.getWorld());
        return island != null && m.dials.islandSet().contains(island);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) { if (ours(e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        Material t = e.getSource().getType();
        if ((t == Material.RED_MUSHROOM || t == Material.BROWN_MUSHROOM) && ours(e.getBlock())) e.setCancelled(true);
    }

    // ---- private island / build zones: XP for grown crops broken the vanilla way ----------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanillaBreak(BlockBreakEvent e) {
        double mult = m.dials.privateIslandXp.get();
        if (mult <= 0) return;
        Block b = e.getBlock();
        String island = Sky.worlds().islandOf(b.getWorld());
        if (island == null || m.dials.islandSet().contains(island) || "garden".equals(island)) return;
        Crop c = Crop.ofBlock(b.getType().getKey().getKey());
        if (c == null) return;
        int blocks = 1;
        switch (c.shape) {
            case AGEABLE -> { if (!(b.getBlockData() instanceof Ageable a) || a.getAge() < a.getMaximumAge()) return; }
            case STACK -> {
                if (b.getRelative(BlockFace.DOWN).getType() != b.getType()) {                       // the root is not a harvest
                    for (Block u = b; u.getType() == b.getType(); u = u.getRelative(BlockFace.UP)) forget(u);
                    return;
                }
                // vanilla physics takes everything above too: pay only for blocks that GREW, never for placed ones
                blocks = 0;
                for (Block u = b; u.getType() == b.getType(); u = u.getRelative(BlockFace.UP)) if (!forget(u)) blocks++;
                if (blocks == 0) return;
            }
            case TALL -> {
                Block lower = b.getBlockData() instanceof Bisected bi && bi.getHalf() == Bisected.Half.TOP ? b.getRelative(BlockFace.DOWN) : b;
                if (forget(lower)) return;
            }
            default -> { if (forget(b)) return; }       // melons, pumpkins, mushrooms: a placed one is not a harvest
        }
        Player p = e.getPlayer();
        if (!Sky.profiles().isReady(p)) return;
        Sky.service(SkillService.class).addXp(p, SkillType.FARMING, c.xp * blocks * mult * m.dials.xpMultiplier.get(), "farming:" + c.name().toLowerCase());
        Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, LootSource.FARMING, c.dropId, blocks, b.getLocation()));
    }

    // ---- player-placed crop blocks (anti XP-farming on private islands) -------------------------------
    //
    // Cane, cactus, melons, pumpkins, mushrooms and the tall flowers drop themselves, so "place, break, place" would
    // pay Farming XP for ever. Every such block a player places outside the farming islands is remembered in its
    // chunk's PersistentDataContainer (so it survives restarts) and pays nothing when broken. Blocks that GROW (cane on
    // cane, a melon from its stem, a spreading mushroom) are never marked. Wheat-like crops need max age, which a
    // placed seed never has.

    private NamespacedKey placedKey;

    private NamespacedKey placedKey() {
        if (placedKey == null) placedKey = new NamespacedKey(Sky.plugin(), "farm_placed");
        return placedKey;
    }

    static long packPos(int x, int y, int z) { return ((long) (y + 4096) << 8) | ((long) (z & 15) << 4) | (x & 15); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!m.dials.trackPlaced.get()) return;
        Block b = e.getBlockPlaced();
        Crop c = Crop.ofBlock(b.getType().getKey().getKey());
        if (c == null || c.shape == Crop.Shape.AGEABLE) return;
        String island = Sky.worlds().islandOf(b.getWorld());
        if (island == null || m.dials.islandSet().contains(island) || "garden".equals(island)) return;
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        long[] old = pdc.getOrDefault(placedKey(), PersistentDataType.LONG_ARRAY, new long[0]);
        long k = packPos(b.getX(), b.getY(), b.getZ());
        for (long v : old) if (v == k) return;
        int cap = Math.max(16, m.dials.placedMemory.get());
        int keep = Math.min(old.length, cap - 1);
        long[] next = new long[keep + 1];
        System.arraycopy(old, old.length - keep, next, 0, keep);       // oldest forgotten first
        next[keep] = k;
        pdc.set(placedKey(), PersistentDataType.LONG_ARRAY, next);
    }

    /** True (and forgets it) when a player placed this block. */
    private boolean forget(Block b) {
        if (!m.dials.trackPlaced.get()) return false;
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        long[] old = pdc.get(placedKey(), PersistentDataType.LONG_ARRAY);
        if (old == null || old.length == 0) return false;
        long k = packPos(b.getX(), b.getY(), b.getZ());
        for (int i = 0; i < old.length; i++) {
            if (old[i] != k) continue;
            long[] next = new long[old.length - 1];
            System.arraycopy(old, 0, next, 0, i);
            System.arraycopy(old, i + 1, next, i, old.length - i - 1);
            if (next.length == 0) pdc.remove(placedKey()); else pdc.set(placedKey(), PersistentDataType.LONG_ARRAY, next);
            return true;
        }
        return false;
    }

    // ---- the Garden: its harvests count for the Harvest Fair and for farming tools --------------------
    //
    // The garden module owns crop breaking there and fires SkyEvents.Gather(FARMING, collection item id, drops). That
    // event is the documented hand-shake (docs/handoff/garden.md request 3c), so no cross-module call is needed.

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGardenGather(SkyEvents.Gather e) {
        if (e.kind != LootSource.FARMING || e.player == null || e.amount <= 0 || e.location == null) return;
        if (!"garden".equals(Sky.worlds().islandOf(e.location))) return;
        Player p = e.player;
        if (!Sky.profiles().isReady(p)) return;
        Crop c = Crop.parse(e.what);
        if (c == null) return;
        ItemStack held = p.getInventory().getItemInMainHand();
        ToolTracker.View v = m.tools.view(p, held);
        if (v != null && v.uuid() != null && v.spec().boosts(c))
            m.tools.add(p, held, v.uuid(), e.amount, e.amount * m.dials.toolXpPerDrop.get() * m.perks.toolXpMultiplier(p));
        if (m.shiny != null) m.shiny.onHarvest(p, e.location);
        if (m.dials.contestGarden.get() && m.contests.onHarvest(p, c, e.amount)) {
            int rare = m.harvest.contestFinds(p, c, 1);
            if (rare > 0) Harvest.give(p, c.contestDrop, rare, LootSource.FARMING);
        }
    }

    // ---- tools: tilling hoes and the fungi cutter -----------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(held);
        if (id == null) return;
        if (TRUFFLE.equals(id)) {
            e.setCancelled(true);                           // a player head: never place it
            try { Sky.abilities().consumeClick(p); } catch (RuntimeException ignored) { }
            eatTruffle(p, held);
            return;
        }
        ItemDef def = Sky.items().def(id);
        if (def == null || def.extra == null) return;
        ToolSpec spec = ToolSpec.of(def.id, def.extra);
        if (spec != null && spec.kind.equals("fungi")) {
            String next = ToolSpec.fungiOf(Sky.items().data(held).extra).equals("red") ? "brown" : "red";
            m.tools.flush(p);
            Sky.items().update(held, d -> d.extra.add(ToolSpec.K_FUNGI, new JsonPrimitive(next)));
            Msg.send(p, "&aFungi Cutter set to " + (next.equals("red") ? "&cRed Mushrooms" : "&6Brown Mushrooms") + "&a.");
            Msg.sound(p, Sound.UI_BUTTON_CLICK, 1.4f);
            e.setCancelled(true);
            return;
        }
        if (def.extra.has("tilling") && e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            int n = till(p, e.getClickedBlock(), def.extra.get("tilling").getAsString());
            if (n > 0) {
                Msg.sound(p, Sound.ITEM_HOE_TILL, 1f);
                e.setCancelled(true);
            }
        }
    }

    // ---- Refined Dark Cacao Truffle (materials registers it, lore only; materials request 11) -----------
    //
    // NEU lore: "Consume to gain +30 Cocoa Beans Fortune for 60m! ... Consuming this grants permanent +1 Cocoa Beans
    // Fortune. You may consume 5 of these before they lose their magical energy." Eating a sixth still gives the
    // hour; only the permanent part stops at 5. The fortune itself is in Perks.cropParts (drops, /fortune, garden tool).

    static final String TRUFFLE = "REFINED_DARK_CACAO_TRUFFLE";

    void eatTruffle(Player p, ItemStack held) {
        if (!Sky.profiles().isReady(p) || held == null || held.getAmount() <= 0) return;
        FarmData d = m.data(p);
        long now = System.currentTimeMillis();
        long mins = Math.max(1, m.dials.truffleMinutes.get());
        held.setAmount(held.getAmount() - 1);
        d.truffleUntil = now + mins * 60_000L;               // a new truffle restarts the hour (does not stack)
        int cap = Math.max(0, m.dials.trufflePermanentMax.get());
        boolean perm = d.trufflesEaten < cap;
        if (perm) d.trufflesEaten++;
        Msg.send(p, "&6&lYUM! &7You ate a &6Refined Dark Cacao Truffle&7: &6+" + com.skyclone.api.ui.Text.commasInt(m.dials.truffleFortune.get())
                + " Cocoa Beans Fortune &7for &a" + mins + "m&7.");
        Msg.send(p, perm ? "&7Aged like fine dark chocolate: &6+1 permanent Cocoa Beans Fortune &8(" + d.trufflesEaten + "/" + cap + ")"
                : "&8These truffles have lost their magical energy for you (" + cap + "/" + cap + " permanent).");
        Msg.sound(p, Sound.ENTITY_GENERIC_EAT, 1f);
    }

    private int till(Player p, Block clicked, String mode) {
        List<Block> targets = new ArrayList<>();
        int y = clicked.getY();
        if (mode.equals("row")) {
            BlockFace f = p.getFacing();
            for (int i = 0; i < 11; i++) targets.add(clicked.getRelative(f, i));
        } else {
            int r = mode.equals("5x5") ? 2 : 1;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) targets.add(clicked.getWorld().getBlockAt(clicked.getX() + dx, y, clicked.getZ() + dz));
        }
        int n = 0;
        for (Block b : targets) {
            Material t = b.getType();
            if (t != Material.DIRT && t != Material.GRASS_BLOCK && t != Material.DIRT_PATH && t != Material.COARSE_DIRT) continue;
            if (!b.getRelative(BlockFace.UP).getType().isAir()) continue;
            if (!mayBuild(p, b.getLocation())) continue;
            b.setType(Material.FARMLAND, true);
            n++;
        }
        return n;
    }

    private boolean mayBuild(Player p, Location l) {
        if (Sky.worlds().buildBypass(p)) return true;
        Layout.Zone z = Sky.worlds().zoneAt(l);
        if (z != null && z.flags().contains(Layout.Zone.BUILD)) return true;
        return Sky.service(Features.PrivateIsland.class).canBuild(p, l);
    }

    /** Cactus Knife's Swift Slicer: held on a farming island or the garden. */
    boolean swiftSlicer(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(held);
        if (id == null) return false;
        ItemDef def = Sky.items().def(id);
        ToolSpec s = def == null ? null : ToolSpec.of(def.id, def.extra);
        if (s == null || !s.kind.equals("knife")) return false;
        String island = Sky.worlds().islandOf(p.getWorld());
        return island != null && (m.dials.islandSet().contains(island) || "garden".equals(island));
    }
}
