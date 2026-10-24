package com.thestill.mine;

import com.thestill.config.Dial;
import com.thestill.config.DialRegistry;
import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.rank.Rank;
import com.thestill.rank.RankService;
import com.thestill.region.Region;
import com.thestill.region.RegionService;
import com.thestill.state.StateKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything that happens when a player breaks a block in the Depths.
 *
 * <p>One region lookup per event, threaded through the whole handler. Shadow
 * Prison asked "which mine is this" from fifteen separate listeners on this same
 * event — one of them six times — each a linear scan over every mine on the
 * server. That is the single biggest reason its review concluded "fine at 2
 * players, falls over at 20+".
 */
public final class MineListener implements Listener {

    /** Lifetime blocks broken. The session count lives in {@link SessionStats}. */
    public static final StateKey<Long> LIFETIME_BLOCKS = StateKey.ofLong("stats.blocks-mined", 0L);

    private static final String BYPASS = "thestill.admin";

    private final RegionService regions;
    private final Profiles profiles;
    private final RankService ranks;
    private final MineService mines;
    private final SessionStats sessions;

    private final Dial<Boolean> restrictBreaking;
    private final Dial<Boolean> autoPickup;
    private final Dial<Boolean> allowBuilding;

    /** Last time each player was told something, so warnings cannot become spam. */
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    public MineListener(DialRegistry dials, RegionService regions, Profiles profiles,
                        RankService ranks, MineService mines, SessionStats sessions) {
        this.regions = regions;
        this.profiles = profiles;
        this.ranks = ranks;
        this.mines = mines;
        this.sessions = sessions;

        this.restrictBreaking = dials.boolDial("mine.restrict-breaking", true)
                .category("Mines").label("Mining only in the Depths")
                .describe("Block breaking anywhere that is not a depth or shaft. "
                        + "Players with " + BYPASS + " are exempt.")
                .register();

        this.autoPickup = dials.boolDial("mine.auto-pickup", false)
                .category("Mines").label("Auto pickup")
                .describe("Send broken blocks straight to the inventory instead of dropping them. "
                        + "OFF by default, deliberately: if ore is already banked the moment you "
                        + "break it, then running away costs you nothing and the whole 'one more "
                        + "swing' gamble has no teeth. Dropped ore is a pile at your feet you have "
                        + "to survive long enough to carry out. Turn on only if a busy shaft "
                        + "actually chokes on item entities.")
                .register();

        this.allowBuilding = dials.boolDial("mine.allow-building", true)
                .category("Mines").label("Building inside mines")
                .describe("Let players place blocks inside a mine. Anything placed is wiped by the "
                        + "next refill, so it is always temporary — the point is that a wall you "
                        + "can throw up is what makes 'walls delay, they do not stop' a real "
                        + "decision instead of a line in a design document.")
                .register();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        // ONE lookup. Everything below reads this variable.
        Region region = regions.at(event.getBlock());

        if (!isMineable(region)) {
            if (restrictBreaking.asBoolean() && !player.hasPermission(BYPASS)) {
                event.setCancelled(true);
                notice(player, Component.text("You can only mine in the Depths.",
                        NamedTextColor.GRAY));
            }
            return;
        }

        if (mines.isResetting(region.id())) {
            // Breaking into a mine mid-refill races the fill cursor and produces
            // holes that never get filled.
            event.setCancelled(true);
            notice(player, Component.text("This shaft is refilling.", NamedTextColor.GRAY));
            return;
        }

        Profile profile = profiles.get(player.getUniqueId());
        if (profile == null) {
            return; // no profile means no session; let vanilla have it
        }

        if (!ranks.canAccess(profile, region) && !player.hasPermission(BYPASS)) {
            event.setCancelled(true);
            Rank needed = ranks.rankNeededFor(region);
            notice(player, Component.text("You need rank ", NamedTextColor.GRAY)
                    .append(Component.text(needed.display(), needed.colour()))
                    .append(Component.text(" to mine here.", NamedTextColor.GRAY)));
            return;
        }

        if (autoPickup.asBoolean() && player.getGameMode() != GameMode.CREATIVE) {
            Collection<ItemStack> drops = event.getBlock().getDrops(
                    player.getInventory().getItemInMainHand(), player);
            if (!drops.isEmpty() && !deliver(player, drops)) {
                // Cancel rather than drop on the floor: a full inventory should
                // stop you, not quietly scatter your ore for someone else.
                event.setCancelled(true);
                notice(player, Component.text("Your inventory is full.", NamedTextColor.GOLD));
                return;
            }
            event.setDropItems(false);
        }

        mines.recordBreak(region);
        sessions.recordBlock(player.getUniqueId());
        profile.state().set(LIFETIME_BLOCKS, profile.state().get(LIFETIME_BLOCKS) + 1L);
        // Deliberately NOT a durable write. A block count is worth a few seconds
        // of loss after a crash; forcing a transaction per broken block would put
        // a disk write on the hottest path in the game.
    }

    /**
     * Building is allowed <b>inside a mine and nowhere else</b>.
     *
     * <p>Inside, because it makes the difficulty curve playable: walls are a
     * delay rather than a defence, and that only means something if a player can
     * actually put one up. Pillar out of trouble, seal a tunnel behind you, bridge
     * a gap — all fine, all temporary, because the next refill overwrites every
     * block in the region anyway. The cleanup is free.
     *
     * <p>Outside, never. The rigs, the stations and the descent are structure,
     * and structure a player can edit is structure a player can break.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        Region region = regions.at(event.getBlock());
        if (!isMineable(region)) {
            event.setCancelled(true);
            notice(player, Component.text("You can only build inside a mine.",
                    NamedTextColor.GRAY));
            return;
        }
        if (!allowBuilding.asBoolean()) {
            event.setCancelled(true);
            notice(player, Component.text("Building is disabled.", NamedTextColor.GRAY));
            return;
        }
        // Containers are refused even here. A refill overwrites the whole region,
        // so a chest or shulker left in a mine is destroyed WITH ITS CONTENTS —
        // and "the mine ate my shulker" is a support ticket every single time.
        if (holdsItems(event.getBlockPlaced())) {
            event.setCancelled(true);
            notice(player, Component.text("Nothing that holds items — the shaft refills and it "
                    + "would take that with it.", NamedTextColor.GOLD));
        }
    }

    private static boolean holdsItems(org.bukkit.block.Block block) {
        try {
            return block.getState() instanceof org.bukkit.inventory.InventoryHolder;
        } catch (Exception e) {
            // Some block states throw while the placement is still settling.
            // Refusing on doubt is the safe direction here.
            return true;
        }
    }

    /**
     * <b>You can break blocks exactly where blocks come back.</b>
     *
     * <p>Having an ore table is the one fact that means "this region refills", so
     * tying breaking and building to it makes the two impossible to get out of
     * step. The rule this replaced was "the region is a DEPTH or a SHAFT", which
     * was true of every region that mattered right up until plains got real ground
     * — at which point a player could quarry away the landscape of D5, the false
     * sky above it, and the walls of its own pit, none of which any refill would
     * ever restore. One depth, permanently ruined, by digging.
     *
     * <p>It also settles the descent for free: a cave is structure, has no table,
     * and so cannot be widened into a shortcut.
     */
    private boolean isMineable(Region region) {
        return region != null && mines.isMine(region.id());
    }

    /**
     * Puts drops in the inventory.
     *
     * @return false if any of it would not fit, having added nothing
     */
    private boolean deliver(Player player, Collection<ItemStack> drops) {
        ItemStack[] array = drops.toArray(ItemStack[]::new);
        // addItem returns what did not fit. Checking room first would need a
        // full simulation, so instead: add, and if anything bounced, give it back
        // and report failure. Net effect is all-or-nothing.
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(array);
        if (leftover.isEmpty()) {
            return true;
        }
        for (ItemStack returned : leftover.values()) {
            player.getInventory().removeItem(returned);
        }
        return false;
    }

    private void notice(Player player, Component message) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueId());
        if (last != null && now - last < 1_500L) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        player.sendActionBar(message);
    }

    public void forget(UUID playerId) {
        lastNotice.remove(playerId);
    }
}
