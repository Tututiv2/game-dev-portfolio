package com.skyclone.core.combat;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemService;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.loot.LootService;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * THE way earned items enter an inventory. Fires ItemGain first (a sack may absorb it), then
 * merges into the main inventory, then drops the overflow at the player's feet owned by them.
 */
final class LootServiceImpl implements LootService, Listener {

    private final Map<UUID, Long> fullMsg = new HashMap<>();
    private final java.util.Set<String> warned = new java.util.HashSet<>();

    // ---- give ---------------------------------------------------------------------------------

    @Override
    public int give(Player p, ItemStack stack, LootSource source) {
        if (p == null || stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return 0;
        if (source == null) source = LootSource.OTHER;
        ItemStack copy = stack.clone();
        ItemService items = Sky.items();
        String id = items.idOf(copy);
        if (id == null) id = items.vanillaId(copy.getType());
        if (id == null) id = copy.getType().name();
        SkyEvents.ItemGain ev = new SkyEvents.ItemGain(p, id, copy.getAmount(), copy.clone(), source);
        try {
            Bukkit.getPluginManager().callEvent(ev);
        } catch (Throwable t) {
            Sky.log().log(Level.WARNING, "[loot] an ItemGain listener threw", t);
        }
        if (ev.isCancelled()) return 0;   // absorbed (sack)
        // Rewards and unique items are named in chat and saved at once when they go to the stash.
        boolean important = isImportant(source) || isUnique(id);
        if (!p.isOnline()) {
            // Never lose earned items: an offline player's go to their item stash (the floor of a world
            // they are not in is the worst place for them).
            return ItemStash.put(p, copy, true);
        }
        PlayerInventory inv = p.getInventory();
        Map<Integer, ItemStack> left = inv.addItem(copy);
        int dropped = 0;
        for (ItemStack rest : left.values()) {
            // Playtest "endgame": a Dark Auction prize given with a full inventory lay on the floor as an item
            // entity. Overflow goes to the item stash (/pickupstash); only a FULL stash falls back to the floor.
            int notStashed = ItemStash.put(p, rest, important);
            if (notStashed <= 0) continue;
            ItemStack floor = rest.clone();
            floor.setAmount(notStashed);
            dropped += dropOwned(p, p.getLocation(), floor, ownerMillis());
        }
        if (dropped > 0 && !ItemStash.enabled()) {
            long now = System.currentTimeMillis();
            Long last = fullMsg.get(p.getUniqueId());
            if (last == null || now - last >= Math.max(0, CombatDials.FULL_MSG_COOLDOWN.get()) * 1000L) {
                fullMsg.put(p.getUniqueId(), now);
                Msg.send(p, "&cYour inventory is full!");
            }
        }
        return dropped;
    }

    /** Sources whose overflow is named in chat and saved at once (one-off, valuable hand-overs - not grinding). */
    static boolean isImportant(LootSource s) {
        return switch (s) {
            case REWARD, ADMIN, AUCTION, TRADE, BAZAAR, NPC_SHOP -> true;
            default -> false;
        };
    }

    private static boolean isUnique(String id) {
        try {
            ItemDef def = Sky.items().def(id);
            return def != null && def.isUnique();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long ownerMillis() { return Math.max(0, CombatDials.OWNER_SECONDS.get()) * 1000L; }

    /** Drop {@code stack} (split into legal stack sizes) owned by {@code p}. {@code ownMillis} 0 = forever. Returns amount dropped. */
    private int dropOwned(Player p, Location at, ItemStack stack, long ownMillis) {
        World w = at.getWorld();
        if (w == null) return 0;
        int total = stack.getAmount();
        int max = Math.max(1, stack.getMaxStackSize());
        int remaining = total;
        long until = ownMillis <= 0 ? 0 : System.currentTimeMillis() + ownMillis;
        UUID owner = p.getUniqueId();
        while (remaining > 0) {
            int n = Math.min(max, remaining);
            remaining -= n;
            ItemStack part = stack.clone();
            part.setAmount(n);
            Item item = w.dropItem(at, part, it -> {
                it.setOwner(owner);
                it.setThrower(owner);
                it.setCanMobPickup(false);
                it.setPickupDelay(10);
                PersistentDataContainer pdc = it.getPersistentDataContainer();
                pdc.set(CombatKeys.LOOT_OWNER, PersistentDataType.STRING, owner.toString());
                pdc.set(CombatKeys.LOOT_UNTIL, PersistentDataType.LONG, until);
            });
            if (until > 0) {
                long ticks = Math.max(1, ownMillis / 50);
                Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                    if (item.isValid()) item.setOwner(null);
                }, ticks);
            }
        }
        return total;
    }

    // ---- roll ---------------------------------------------------------------------------------

    @Override
    public List<ItemStack> roll(Player p, List<DropDef> table, LootSource source, double magicFind) {
        List<ItemStack> out = new ArrayList<>();
        if (p == null || table == null || table.isEmpty()) return out;
        if (source == null) source = LootSource.MOB_DROP;
        ItemService items = Sky.items();
        double threshold = CombatDials.MF_THRESHOLD.get();
        double cap = CombatDials.MF_CAP.get();
        double mf = Math.max(0, Math.min(magicFind, cap));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (DropDef d : table) {
            if (d == null || d.item == null) continue;
            if (d.requirement != null && !d.requirement.isBlank()) {
                boolean ok;
                try { ok = Sky.requirements().check(p, d.requirement); } catch (RuntimeException e) { ok = false; }
                if (!ok) continue;
            }
            double chance = LootMath.chance(d, mf, threshold, cap);
            if (chance <= 0 || rnd.nextDouble() >= chance) continue;
            int amount = LootMath.amount(d, rnd.nextDouble());
            if (amount <= 0) continue;
            if (!items.exists(d.item)) {
                if (warned.add(d.item)) Sky.log().warning("[loot] drop item '" + d.item + "' is not registered; skipped");
                continue;
            }
            List<ItemStack> stacks = new ArrayList<>();
            ItemDef def = items.def(d.item);
            try {
                if (def != null && def.isUnique()) {
                    for (int i = 0; i < amount; i++) stacks.add(items.create(d.item, 1));
                } else {
                    stacks.add(items.create(d.item, amount));
                }
            } catch (RuntimeException e) {
                if (warned.add("create:" + d.item)) Sky.log().log(Level.WARNING, "[loot] could not create " + d.item, e);
                continue;
            }
            for (ItemStack s : stacks) {
                give(p, s, source);
                out.add(s);
            }
            DropDef.Label label = d.effectiveLabel();
            if (label != null && label != DropDef.Label.NONE && label.text != null) {
                String name = displayName(stacks.get(0), def, d.item);
                if (amount > 1) name = "&8" + amount + "x " + name;
                Msg.rareDrop(p, label.text, name, LootMath.mfApplies(d, mf, threshold) ? mf : 0);
            }
        }
        return out;
    }

    private static String displayName(ItemStack stack, ItemDef def, String id) {
        try {
            Rarity r = Sky.items().rarity(stack);
            String n = def != null && def.name != null ? def.name : Text.titleCase(id);
            return (r != null ? r.code() : "&f") + n;
        } catch (RuntimeException e) {
            return "&f" + Text.titleCase(id);
        }
    }

    // ---- per-target Magic Find (wave 5) -------------------------------------------------------

    private final Map<String, java.util.function.BiFunction<Player, com.skyclone.api.mob.MobDef, Double>> mfModifiers =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void addMagicFindModifier(String id, java.util.function.BiFunction<Player, com.skyclone.api.mob.MobDef, Double> modifier) {
        if (id == null) return;
        if (modifier == null) mfModifiers.remove(id); else mfModifiers.put(id, modifier);
    }

    @Override
    public double magicFind(Player p, com.skyclone.api.mob.MobDef def) {
        if (p == null) return 0;
        double mf = Sky.stats().get(p, com.skyclone.api.stat.Stat.MAGIC_FIND);
        if (def == null || mfModifiers.isEmpty()) return mf;
        for (var e : mfModifiers.entrySet()) {
            try {
                Double add = e.getValue().apply(p, def);
                if (add != null && Double.isFinite(add)) mf += add;
            } catch (Throwable t) {
                if (warned.add("mf:" + e.getKey())) Sky.log().log(Level.WARNING, "[loot] magic find modifier '" + e.getKey() + "' threw", t);
            }
        }
        return mf;
    }

    /** Registered per-target Magic Find modifier ids (self-test / admin). */
    java.util.Set<String> magicFindModifierIds() { return java.util.Set.copyOf(mfModifiers.keySet()); }

    // ---- dropFor ------------------------------------------------------------------------------

    @Override
    public void dropFor(Player p, Location at, ItemStack stack) {
        if (p == null || at == null || at.getWorld() == null || stack == null || stack.getType().isAir()) return;
        World w = at.getWorld();
        UUID owner = p.getUniqueId();
        int remaining = stack.getAmount();
        int max = Math.max(1, stack.getMaxStackSize());
        while (remaining > 0) {
            int n = Math.min(max, remaining);
            remaining -= n;
            ItemStack part = stack.clone();
            part.setAmount(n);
            Item item = w.dropItem(at, part, it -> {
                it.setVisibleByDefault(false);
                it.setOwner(owner);
                it.setThrower(owner);
                it.setCanMobPickup(false);
                it.setUnlimitedLifetime(false);
                PersistentDataContainer pdc = it.getPersistentDataContainer();
                pdc.set(CombatKeys.LOOT_OWNER, PersistentDataType.STRING, owner.toString());
                pdc.set(CombatKeys.LOOT_UNTIL, PersistentDataType.LONG, 0L);
            });
            p.showEntity(Sky.plugin(), item);
        }
    }

    // ---- pickup rules ---------------------------------------------------------------------------

    /** Owner uuid if this item is still restricted, else null (clearing an expired restriction). */
    private static UUID restrictedTo(Item item) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        String o = pdc.get(CombatKeys.LOOT_OWNER, PersistentDataType.STRING);
        if (o == null) return null;
        Long until = pdc.get(CombatKeys.LOOT_UNTIL, PersistentDataType.LONG);
        if (until != null && until > 0 && System.currentTimeMillis() > until) {
            pdc.remove(CombatKeys.LOOT_OWNER);
            pdc.remove(CombatKeys.LOOT_UNTIL);
            item.setOwner(null);
            return null;
        }
        try { return UUID.fromString(o); } catch (IllegalArgumentException e) { return null; }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttemptPickup(PlayerAttemptPickupItemEvent e) {
        UUID owner = restrictedTo(e.getItem());
        if (owner != null && !owner.equals(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.setFlyAtPlayer(false);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent e) {
        UUID owner = restrictedTo(e.getItem());
        if (owner != null && !owner.equals(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent e) {
        if (restrictedTo(e.getItem()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMerge(ItemMergeEvent e) {
        PersistentDataContainer a = e.getEntity().getPersistentDataContainer();
        PersistentDataContainer b = e.getTarget().getPersistentDataContainer();
        if (a.has(CombatKeys.LOOT_OWNER, PersistentDataType.STRING) || b.has(CombatKeys.LOOT_OWNER, PersistentDataType.STRING)) {
            e.setCancelled(true);
        }
    }

    void forget(UUID player) { fullMsg.remove(player); }

    Collection<UUID> throttled() { return fullMsg.keySet(); }
}
