package com.skyclone.api.event;

import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Every game event, as plain Bukkit events (listen with {@code @EventHandler} as usual).
 * These are how modules react to each other without importing each other:
 * the bestiary listens to MobKill, collections to ItemGain, pets to SkillXpGain, commissions
 * to Gather, and so on. Fire them with {@code Bukkit.getPluginManager().callEvent(e)}.
 *
 * <p>Who fires what:
 * <pre>
 *  ProfileReady / ProfileUnload   core, after load / before save on quit
 *  MobKill                        core (MobService) — killer may be null
 *  ItemGain                       core (LootService.give) — cancellable: a sack that absorbs it cancels
 *  Gather                         the module that owns the action (mining, farming, foraging, fishing)
 *  SkillXpGain / SkillLevelUp     skills module
 *  CollectionGain / CollectionTierUp  collections module
 *  CoinsChange / CurrencyChange   core economy (CurrencyChange: purse, bank and bits)
 *  IslandBuilt                    core worlds, when an island's blocks are all placed
 *  ZoneChange / IslandChange      core worlds
 *  CalendarStart / CalendarEnd    core calendar
 *  AbilityUsed                    core abilities
 *  Damage                         core combat, after modifiers, before applying — cancellable
 *  SkyDeath                       core vitals
 *  FishCaught                     fishing module
 *  DungeonXpGain                  dungeons module, before Catacombs / class XP is added (wave 5)
 * </pre>
 */
public final class SkyEvents {

    private SkyEvents() {}

    /** Base with the per-class HandlerList boilerplate each subclass still needs. */
    public abstract static class Base extends Event {
        protected Base() { super(false); }
    }

    // ---------------------------------------------------------------------------------------

    public static final class ProfileReady extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        public final boolean firstJoin;
        public ProfileReady(Player player, boolean firstJoin) { this.player = player; this.firstJoin = firstJoin; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class ProfileUnload extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        public ProfileUnload(Player player) { this.player = player; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class MobKill extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player killer;
        public final LivingEntity mob;
        public final MobDef def;
        public final Location location;
        /** Mutable: modules may scale the payout before core pays it. */
        public double coins;
        public double combatXp;
        public MobKill(Player killer, LivingEntity mob, MobDef def, Location location) {
            this.killer = killer; this.mob = mob; this.def = def; this.location = location;
            this.coins = def.coins; this.combatXp = def.combatXp;
        }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class ItemGain extends Base implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        public final String itemId;
        public final int amount;
        public final ItemStack stack;
        public final LootSource source;
        private boolean cancelled;
        public ItemGain(Player player, String itemId, int amount, ItemStack stack, LootSource source) {
            this.player = player; this.itemId = itemId; this.amount = amount; this.stack = stack; this.source = source;
        }
        /** Cancelled = something (a sack) took it; LootService will not put it in the inventory. Collections still count it. */
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean c) { cancelled = c; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /** A gathering action completed: a block mined, a crop broken, a log chopped. */
    public static final class Gather extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        public final LootSource kind;          // MINING, FARMING, FORAGING
        public final String what;              // block/crop id: "MITHRIL_ORE", "WHEAT", "OAK_LOG"
        public final int amount;               // drops after fortune
        public final Location location;
        public Gather(Player player, LootSource kind, String what, int amount, Location location) {
            this.player = player; this.kind = kind; this.what = what; this.amount = amount; this.location = location;
        }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class SkillXpGain extends Base implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        public final SkillType skill;
        /** Mutable, after wisdom. */
        public double amount;
        public final String source;
        private boolean cancelled;
        public SkillXpGain(Player player, SkillType skill, double amount, String source) {
            this.player = player; this.skill = skill; this.amount = amount; this.source = source;
        }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean c) { cancelled = c; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class SkillLevelUp extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        public final SkillType skill;
        public final int newLevel;
        public SkillLevelUp(Player player, SkillType skill, int newLevel) { this.player = player; this.skill = skill; this.newLevel = newLevel; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class CollectionGain extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        public final String collection;
        public final long amount;
        public final long newTotal;
        public CollectionGain(Player player, String collection, long amount, long newTotal) {
            this.player = player; this.collection = collection; this.amount = amount; this.newTotal = newTotal;
        }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class CollectionTierUp extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        public final String collection;
        public final int tier;
        public CollectionTierUp(Player player, String collection, int tier) { this.player = player; this.collection = collection; this.tier = tier; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class CoinsChange extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        public final double delta;
        public final double purse;
        public final String reason;
        public CoinsChange(Player player, double delta, double purse, String reason) {
            this.player = player; this.delta = delta; this.purse = purse; this.reason = reason;
        }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /**
     * Any balance change: {@code currency} is "purse", "bank" or "bits". Fires on the main thread for
     * the player whose action caused it (the bank is shared by a co-op; this is the member who moved
     * it). Coins are coins (one decimal), bits are whole. The purse ALSO fires {@link CoinsChange}.
     * (Added by the core gate, 2026-10-02.)
     */
    public static final class CurrencyChange extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        public final String currency;
        public final double delta;
        public final double after;
        public final String reason;
        public CurrencyChange(Player player, String currency, double delta, double after, String reason) {
            this.player = player; this.currency = currency; this.delta = delta; this.after = after; this.reason = reason;
        }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /**
     * A shared island's blocks finished placing ({@code instanceKey} null), or an instance finished
     * building ({@code instanceKey} = its key). Layouts exist BEFORE this - use it for anything that
     * needs the blocks themselves. (Added by the core gate, 2026-10-02.)
     */
    public static final class IslandBuilt extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final String islandId;
        public final String instanceKey;
        public IslandBuilt(String islandId, String instanceKey) { this.islandId = islandId; this.instanceKey = instanceKey; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class ZoneChange extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        public final Layout.Zone from;   // nullable
        public final Layout.Zone to;     // nullable
        public final String island;
        public ZoneChange(Player player, Layout.Zone from, Layout.Zone to, String island) {
            this.player = player; this.from = from; this.to = to; this.island = island;
        }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class IslandChange extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        public final String from;        // nullable
        public final String to;          // nullable
        public IslandChange(Player player, String from, String to) { this.player = player; this.from = from; this.to = to; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class CalendarStart extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final String eventId;
        public CalendarStart(String eventId) { this.eventId = eventId; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class CalendarEnd extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final String eventId;
        public CalendarEnd(String eventId) { this.eventId = eventId; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class AbilityUsed extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        public final ItemStack item;
        public final AbilityDef ability;
        public AbilityUsed(Player player, ItemStack item, AbilityDef ability) { this.player = player; this.item = item; this.ability = ability; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class Damage extends Base implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        public final DamageContext ctx;
        public Damage(DamageContext ctx) { this.ctx = ctx; }
        @Override public boolean isCancelled() { return ctx.cancelled; }
        @Override public void setCancelled(boolean c) { ctx.cancelled = c; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class SkyDeath extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        public final String cause;
        /** Mutable: e.g. a mayor perk or the "death in dungeon" rule can set this to 0. */
        public double coinsLost;
        /**
         * Mutable, nullable: where the player respawns. null = core decides (island spawn, private
         * island home, limbo). Dungeons/garden/rift set it for their instanced slots. (Gate, 2026-10-02.)
         */
        public org.bukkit.Location respawnAt;
        public SkyDeath(Player player, String cause, double coinsLost) { this.player = player; this.cause = cause; this.coinsLost = coinsLost; }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /**
     * Dungeon experience about to be added (wave 5, accessories request 12). The dungeons module fires it in
     * {@code Progress.addCataXp} (track "catacombs") and {@code addClassXp} (track = the class id) BEFORE adding,
     * then adds {@link #amount}. Listeners scale {@code amount} (Catacombs Expert Ring, Professor's books, mayor
     * perks); set it to 0 to grant nothing. Field names {@code player / track / amount} are load-bearing:
     * accessories' DungeonXpHook binds them by name.
     */
    public static final class DungeonXpGain extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        /** "catacombs" for Catacombs XP, else the class id (HEALER, MAGE, BERSERK, ARCHER, TANK). */
        public final String track;
        /** The class this XP is for (class XP), or the player's selected class for Catacombs XP; may be null. */
        public final String dungeonClass;
        /** Floor number: 0 = Entrance, 1..7. */
        public final int floor;
        /** True for a Master Mode run. */
        public final boolean master;
        /** "run" (completion/fail), "admin" (/sb dungeons xp) or another module-chosen source. */
        public final String source;
        /** Mutable: listeners scale it; the dungeons module adds whatever is left (negative is treated as 0). */
        public double amount;
        public DungeonXpGain(Player player, String track, String dungeonClass, int floor, boolean master,
                             double amount, String source) {
            this.player = player; this.track = track; this.dungeonClass = dungeonClass; this.floor = floor;
            this.master = master; this.amount = amount; this.source = source == null ? "run" : source;
        }
        /** True for Catacombs XP (track "catacombs", any case), false for class XP. */
        public boolean catacombs() { return track == null || track.equalsIgnoreCase("catacombs"); }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class FishCaught extends Base {
        private static final HandlerList HANDLERS = new HandlerList();
        public final Player player;
        /** Item id for a catch, mob id for a sea creature. */
        public final String what;
        public final boolean seaCreature;
        public final boolean lava;
        /** A treasure catch (added by integration round 2, fishing Request 5). */
        public final boolean treasure;
        /** Treasure quality "good" / "great" / "outstanding"; null when not treasure. */
        public final String quality;
        public FishCaught(Player player, String what, boolean seaCreature, boolean lava) {
            this(player, what, seaCreature, lava, false, null);
        }
        public FishCaught(Player player, String what, boolean seaCreature, boolean lava, boolean treasure, String quality) {
            this.player = player; this.what = what; this.seaCreature = seaCreature; this.lava = lava;
            this.treasure = treasure; this.quality = treasure ? quality : null;
        }
        @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
}
