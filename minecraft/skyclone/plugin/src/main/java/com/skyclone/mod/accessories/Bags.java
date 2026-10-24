package com.skyclone.mod.accessories;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.stat.Stat;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every player's bag and equipment at runtime: decoded stacks cached per player (decoding base64 every
 * 10 ticks would be wasteful), written back to {@link AccData} on every change, and the {@link Snapshot}.
 */
final class Bags {

    static final String KEY = "accessories";
    static final String[] EQUIP_SLOTS = {"NECKLACE", "CLOAK", "BELT", "GLOVES"};

    /** Runtime state of one online player. Never persisted directly. */
    static final class State {
        final Map<Integer, ItemStack> bag = new TreeMap<>();
        /** Slots whose stored bytes failed to decode: kept untouched in AccData, never overwritten. */
        final Set<Integer> broken = new HashSet<>();
        final Map<String, ItemStack> equip = new LinkedHashMap<>();
        Snapshot snap;
        boolean dirty = true;
        // effect runtime
        final ArrayDeque<Long> kills = new ArrayDeque<>();
        UUID hitTarget;
        int hitCount;
        long lastHealKill, lastVamp, minuteTimer, coinFindTimer;
        /** Wall time of the last teleport (Shadow Assassin Cloak); 0 = spent. */
        long lastTeleport;
        /** Families of the last monster you hit and when (Shriveled Bracelet's "against arthropods"). */
        Set<String> lastTargetTypes = Set.of();
        long lastTargetAt;
        boolean vsActive;
        /** Thunderbolt Necklace: charges count from here; 0 = not started. */
        long staticSince;
        /** Adaptive Belt: the dungeon class the stats were last computed for. */
        String dungeonClass;
        /** Ancient Cloak: a health-cost ability is about to run (core cost check) / did run (AbilityUsed). */
        String hpAbilityItem;
        long hpAbilityArmedAt, hpAbilityUsedAt;
        // ---- wave 5 ----
        /** Flaming Fist: no new volley before this wall time. */
        long fistReadyAt;
        /** Scourge Cloak: when fire last hurt you (env listener) and how much it did (final damage), for the next hit. */
        long fireAt;
        double lastFireDamage;
        /** Soulweaver Gloves: next timed Haunted Skull; the last melee target and when (melee-kill trigger). */
        long skullNextAt, lastMeleeAt;
        UUID lastMeleeTarget;
        /** Bat Person: wall times of guided bats summoned by abilities; the bat count stats were last computed for. */
        final ArrayDeque<Long> guidedBats = new ArrayDeque<>();
        int lastBats = -1;
        /** Mask / cake bag: the distinct keys inside each (decoded once per change); null entry = recompute. */
        final Map<String, Set<String>> keepKeys = new HashMap<>();
        /** Organ Donor: the Frozen Corpses within range at the last check (a Ding is for a newcomer). */
        final Set<UUID> corpsesNear = new HashSet<>();
    }

    private final Catalog cat;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    Bags(Catalog cat) { this.cat = cat; }

    static AccData data(Player p) { return Sky.profiles().member(p, KEY, AccData.class); }

    static boolean ready(Player p) {
        try { return p != null && p.isOnline() && Sky.profiles().isReady(p); }
        catch (RuntimeException e) { return false; }
    }

    // ---- codec ---------------------------------------------------------------------------------

    static String encode(ItemStack s) {
        if (s == null || s.getType().isAir()) return null;
        return Base64.getEncoder().encodeToString(s.serializeAsBytes());
    }

    static ItemStack decode(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try { return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64)); }
        catch (RuntimeException e) { return null; }
    }

    // ---- state ---------------------------------------------------------------------------------

    State state(Player p) {
        State s = states.get(p.getUniqueId());
        if (s != null) return s;
        s = new State();
        if (ready(p)) {
            AccData d = data(p);
            for (Map.Entry<String, String> en : d.bag.entrySet()) {
                int slot;
                try { slot = Integer.parseInt(en.getKey()); } catch (NumberFormatException e) { continue; }
                ItemStack it = decode(en.getValue());
                if (it == null) s.broken.add(slot);
                else s.bag.put(slot, it);
            }
            for (String slot : EQUIP_SLOTS) {
                ItemStack it = decode(d.equipment.get(slot));
                if (it != null) s.equip.put(slot, it);
            }
            states.put(p.getUniqueId(), s);
        }
        return s;
    }

    void unload(UUID id) { states.remove(id); }

    void invalidate(Player p) {
        State s = states.get(p.getUniqueId());
        if (s != null) s.dirty = true;
        try { Sky.stats().invalidate(p); } catch (RuntimeException ignored) { }
    }

    void invalidateAll() {
        for (State s : states.values()) s.dirty = true;
    }

    // ---- bag -----------------------------------------------------------------------------------

    ItemStack bagItem(Player p, int slot) { return state(p).bag.get(slot); }

    boolean isBroken(Player p, int slot) { return state(p).broken.contains(slot); }

    /** Put (or clear with null) one bag slot, in the cache AND in the saved data. */
    void setBag(Player p, int slot, ItemStack it) {
        if (!ready(p)) return;
        State s = state(p);
        if (s.broken.contains(slot)) return;   // never overwrite a slot we could not read
        AccData d = data(p);
        String enc = encode(it);
        if (enc == null) {
            s.bag.remove(slot);
            d.bag.remove(String.valueOf(slot));
        } else {
            s.bag.put(slot, it.clone());
            d.bag.put(String.valueOf(slot), enc);
        }
        invalidate(p);
    }

    /** Re-encode a cached bag stack after it was changed in place (enrichment, counters). */
    void resave(Player p, int slot) {
        ItemStack it = state(p).bag.get(slot);
        if (it != null) data(p).bag.put(String.valueOf(slot), encode(it));
        invalidate(p);
    }

    int firstFreeSlot(Player p) {
        int cap = capacity(p);
        State s = state(p);
        for (int i = 0; i < cap; i++) if (!s.bag.containsKey(i) && !s.broken.contains(i)) return i;
        return -1;
    }

    /** Bag slot holding this id, or -1. */
    int slotOf(Player p, String id) {
        for (Map.Entry<Integer, ItemStack> en : state(p).bag.entrySet()) {
            if (id.equals(Sky.items().idOf(en.getValue()))) return en.getKey();
        }
        return -1;
    }

    Map<Integer, ItemStack> bagView(Player p) { return java.util.Collections.unmodifiableMap(state(p).bag); }

    // ---- equipment -----------------------------------------------------------------------------

    ItemStack equipped(Player p, String slot) { return state(p).equip.get(slot); }

    void setEquip(Player p, String slot, ItemStack it) {
        if (!ready(p)) return;
        State s = state(p);
        AccData d = data(p);
        String enc = encode(it);
        if (enc == null) {
            s.equip.remove(slot);
            d.equipment.remove(slot);
        } else {
            s.equip.put(slot, it.clone());
            d.equipment.put(slot, enc);
        }
        invalidate(p);
    }

    List<ItemStack> worn(Player p) {
        if (!ready(p)) return List.of();
        List<ItemStack> out = new ArrayList<>(4);
        for (String slot : EQUIP_SLOTS) {
            ItemStack it = state(p).equip.get(slot);
            if (it != null) out.add(it);
        }
        return out;
    }

    static String slotFor(ItemDef d) {
        if (d == null || d.category == null) return null;
        return switch (d.category) {
            case NECKLACE -> "NECKLACE";
            case CLOAK -> "CLOAK";
            case BELT -> "BELT";
            case GLOVES -> "GLOVES";
            default -> null;
        };
    }

    // ---- capacity ------------------------------------------------------------------------------

    int milestonesReached(Player p) {
        int lvl = Sky.service(Features.SkyLevel.class).level(p);
        int n = 0;
        for (int need : AccDials.milestoneLevels()) if (lvl >= need) n++;
        return n;
    }

    int redstoneTier(Player p) {
        return Sky.service(Features.Collections.class).tier(p, "REDSTONE");
    }

    int capacity(Player p) {
        if (!ready(p)) return 0;
        AccData d = data(p);
        return AccMath.bagSlots(AccDials.BASE_SLOTS.get(), redstoneTier(p), AccDials.REDSTONE_PER_TIER.get(),
                milestonesReached(p), AccDials.MILESTONE_SLOTS.get(), d.redstoneExchange, AccDials.EXCHANGE_SLOTS.get(),
                Math.min(d.boughtPairs, Math.max(0, AccDials.MAX_PAIRS.get())));
    }

    // ---- snapshot ------------------------------------------------------------------------------

    Snapshot snapshot(Player p) {
        State s = state(p);
        long now = System.currentTimeMillis();
        Snapshot snap = s.snap;
        if (snap == null || s.dirty || now - snap.builtAt > AccDials.SNAPSHOT_SECONDS.get() * 1000L) {
            snap = build(p, s);
            s.snap = snap;
            s.dirty = false;
        }
        return snap;
    }

    private Snapshot build(Player p, State st) {
        Snapshot snap = new Snapshot();
        snap.builtAt = System.currentTimeMillis();
        if (!ready(p)) return snap;
        int[] table = AccDials.mpTable();

        // candidates: bag (+ inventory when the dial says so)
        List<AccMath.Candidate<Snapshot.Active>> cands = new ArrayList<>();
        Map<Integer, ItemStack> sources = new TreeMap<>(st.bag);
        if (AccDials.COUNT_INVENTORY.get()) {
            ItemStack[] inv = p.getInventory().getStorageContents();
            for (int i = 0; i < inv.length; i++) if (inv[i] != null) sources.put(-1 - i, inv[i]);
        }
        for (Map.Entry<Integer, ItemStack> en : sources.entrySet()) {
            ItemStack it = en.getValue();
            String id = Sky.items().idOf(it);
            ItemDef def = Sky.items().def(id);
            if (!Catalog.isAccessory(def)) continue;
            if (en.getKey() >= 0) snap.bagIds.add(id);
            if (Catalog.noPower(def)) {
                if (en.getKey() >= 0) snap.inactive.put(en.getKey(), "&dRift curio: no Accessory Power here.");
                continue;
            }
            if (!Sky.items().meetsRequirements(p, it, false)) {
                if (en.getKey() >= 0) snap.inactive.put(en.getKey(), "&cYou do not meet its requirements.");
                continue;
            }
            Rarity r = runicRarity(it, def, Sky.items().rarity(it));
            int mp = (int) Math.round(AccMath.mpFor(r, table) * Catalog.mpMult(def));
            cands.add(new AccMath.Candidate<>(Catalog.family(def), Catalog.familyRank(def), r.ordinal(),
                    new Snapshot.Active(en.getKey(), it, def, r, mp)));
        }
        List<Snapshot.Active> winners = AccMath.pickBest(cands);
        Set<Snapshot.Active> won = new HashSet<>(winners);
        for (AccMath.Candidate<Snapshot.Active> c : cands) {
            if (!won.contains(c.item()) && c.item().slot() >= 0) {
                snap.inactive.put(c.item().slot(), "&cA better accessory of this family already counts.");
            }
        }
        double dungeonMult = Math.max(1, AccDials.DUNGEON_MP_MULT.get());
        Set<String> setNames = new HashSet<>();
        for (Snapshot.Active a : winners) {
            snap.active.add(a);
            snap.activeIds.add(a.def().id);
            snap.mp += a.mp();
            if (Catalog.dungeonAccessory(a.def())) snap.mpDungeonExtra += (int) Math.round(a.mp() * (dungeonMult - 1));
            snap.countByRarity.merge(a.rarity(), 1, Integer::sum);
            ItemStatSheet sheet = Sky.items().sheet(a.stack());
            addSheet(sheet, snap);
            addFx(cat.effects(a.def()), snap, setNames);
        }
        if (data(p).riftPrism) snap.mp += Math.max(0, AccDials.RIFT_PRISM_MP.get());
        // Abicase (wave 5): +1 Accessory Power per 2 Ringphone contacts, on top of its rarity's
        for (Fx f : snap.fx) {
            if (!f.type.equals("abicase")) continue;
            snap.contacts = contacts(p);
            snap.abicaseMp = AccMath.abicaseMp(snap.contacts, f.per <= 0 ? 2 : f.per);
            snap.mp += snap.abicaseMp;
            break;
        }

        for (String slot : EQUIP_SLOTS) {
            ItemStack it = st.equip.get(slot);
            if (it == null) continue;
            ItemDef def = Sky.items().def(Sky.items().idOf(it));
            if (def == null || !Sky.items().meetsRequirements(p, it, false)) continue;
            snap.equipment.put(slot, it);
            addFx(cat.effects(def), snap, setNames);
        }
        for (Fx f : snap.fx) {
            if (f.type.equals("compactor")) snap.compactorSlots = Math.max(snap.compactorSlots, f.slots);
            if (f.type.equals("deletor")) snap.deletorSlots = Math.max(snap.deletorSlots, f.slots);
        }
        return snap;
    }

    /** Item-data key of the Runebook's Runic kill counter (the reference keeps it on the item too). */
    static final String RUNIC_KEY = "runicKills";

    static long runicCount(ItemStack it) {
        try {
            com.google.gson.JsonObject x = Sky.items().data(it).extra;
            return x != null && x.has(RUNIC_KEY) ? x.get(RUNIC_KEY).getAsLong() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Runebook: its rarity in the bag rises one step per digit of its Runic kill count (max 4). */
    Rarity runicRarity(ItemStack it, ItemDef def, Rarity base) {
        int steps = 0;
        for (Fx f : cat.effects(def)) {
            if (f.type.equals("runic_counter")) steps = AccMath.runicSteps(runicCount(it), f.cap <= 0 ? 4 : (int) f.cap);
        }
        Rarity r = base;
        for (int i = 0; i < steps; i++) r = r.upgrade();
        return r;
    }

    /** Ringphone contacts, read through the extras module's {@code abiphone:contacts:N} requirement key. */
    static int contacts(Player p) {
        try {
            if (!Sky.requirements().known("abiphone")) return 0;
            return AccMath.countByThreshold(n -> Sky.requirements().check(p, "abiphone:contacts:" + n), 256);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static void addSheet(ItemStatSheet sheet, Snapshot snap) {
        for (Map.Entry<Stat, EnumMap<ItemStatSheet.Source, Double>> e : sheet.view().entrySet()) {
            double t = 0;
            for (double v : e.getValue().values()) t += v;
            snap.stats.add(e.getKey(), t);
        }
    }

    private static void addFx(List<Fx> list, Snapshot snap, Set<String> setNames) {
        for (Fx f : list) {
            if (f.type.equals("set")) {
                String key = f.name == null ? f.source : f.name;
                if (!setNames.add(key)) continue;   // one copy of each set bonus, however many pieces carry it
            }
            snap.fx.add(f);
        }
    }

    /** Accessory Power right now (dungeon accessories count double inside a dungeon). */
    int magicalPower(Player p) {
        if (!ready(p)) return 0;
        Snapshot s = snapshot(p);
        int extra = 0;
        if (s.mpDungeonExtra > 0) {
            try { if (Sky.service(Features.Dungeons.class).inDungeon(p)) extra = s.mpDungeonExtra; }
            catch (RuntimeException ignored) { }
        }
        return s.mp + extra;
    }

    Map<UUID, State> live() { return states; }

    /** For admin output: slot -> id of everything in the bag. */
    Map<Integer, String> describeBag(Player p) {
        Map<Integer, String> out = new TreeMap<>();
        for (Map.Entry<Integer, ItemStack> en : state(p).bag.entrySet()) out.put(en.getKey(), Sky.items().idOf(en.getValue()));
        for (int b : state(p).broken) out.put(b, "<unreadable>");
        return out;
    }

    /** Re-read one player's stored data into the cache (after an admin edit of AccData). */
    void reload(Player p) {
        states.remove(p.getUniqueId());
        state(p);
        invalidate(p);
    }

    static Map<String, Integer> copy(Map<String, Integer> m) { return new HashMap<>(m); }

    /**
     * Anti-dupe: a unique item stored in the bag or an equipment slot must not ALSO sit in the player's
     * inventory (core's scanner only looks at inventories). Moving an item always removes it from one side
     * in the same tick, so a legitimate state never has both: the inventory copy is the duplicate.
     * Returns how many copies were removed.
     */
    int dupeSweep(Player p) {
        if (!ready(p)) return 0;
        State st = state(p);
        Set<String> stored = new HashSet<>();
        for (ItemStack it : storedStacks(p)) addUuid(stored, it);
        if (stored.isEmpty()) return 0;
        int removed = 0;
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < inv.length; i++) {
            ItemStack it = inv[i];
            if (it == null || Sky.items().idOf(it) == null) continue;
            String u = Sky.items().data(it).uuid;
            if (u != null && stored.contains(u)) {
                p.getInventory().setItem(i, null);
                removed++;
                Sky.log().warning("[accessories] removed a duplicate of " + Sky.items().idOf(it) + " (uuid " + u + ") from "
                        + p.getName() + "'s inventory: the same item is stored in their bag/equipment");
            }
        }
        return removed;
    }

    /**
     * Every item this module stores for the player: bag, equipment, and the mask / cake bags (wave 5). Also core's
     * dupe-scan source ({@code ItemService.addDupeScanSource}), so a copy in the inventory of anything stored here
     * is caught by core's scanner as well as by {@link #dupeSweep}.
     */
    List<ItemStack> storedStacks(Player p) {
        if (!ready(p)) return List.of();
        State st = state(p);
        List<ItemStack> out = new ArrayList<>(st.bag.values());
        out.addAll(st.equip.values());
        for (Map<String, String> kind : data(p).keepBags.values()) {
            for (String b64 : kind.values()) {
                ItemStack it = decode(b64);
                if (it != null) out.add(it);
            }
        }
        return out;
    }

    private static void addUuid(Set<String> into, ItemStack it) {
        if (it == null) return;
        String u = Sky.items().data(it).uuid;
        if (u != null) into.add(u);
    }
}
