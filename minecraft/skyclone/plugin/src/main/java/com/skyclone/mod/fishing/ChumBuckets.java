package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chum Buckets (wave 5; reference lore from NEU's EMPTY_CHUM_BUCKET / EMPTY_CHUMCAP_BUCKET / CHUM pages):
 * <ul>
 *   <li><b>Place</b> an Empty Chum Bucket or Empty Chumcap Bucket (Fishing 15) on the ground next to water
 *       (right-click a block). It stands there as a bucket you can see, with its owner's name over it.</li>
 *   <li>Every sea creature slain within reach drops <b>+1 Chum per bucket</b>, up to +4 from buckets
 *       ({@link FishMath#chumDrop}); Glowy Chum Bait's +2 rides on top of a bucket.</li>
 *   <li><b>Deposit</b> Chum in ANY bucket (right-click it holding Chum; sneak = the whole stack): +20 coins and
 *       +10 Fishing XP each. The <b>Luminous Bracelet</b> pays the same again into someone ELSE's bucket.</li>
 *   <li>A placed <b>Chumcap Bucket</b> gives every angler in reach +4 Fishing Speed (up to +16 from buckets)
 *       and gives its OWNER the Mooshroom Diver replacement roll (pool {@code chumcap}, trigger {@code chumcap}).</li>
 *   <li>The owner picks it up again by right-clicking it without Chum (or {@code /chumbuckets return}). It also
 *       comes back by itself when the owner leaves that world or logs off, after a restart or a crash: the
 *       profile records every bucket it has OUT ({@link FishData#chumBuckets}) and the sweep hands back what
 *       is recorded but not standing - exactly once, never duplicated.</li>
 * </ul>
 * Entities are non-persistent display + interaction entities; no block is ever placed.
 */
final class ChumBuckets implements Listener {

    static final String CHUM = "CHUM";
    static final String BUCKET = "EMPTY_CHUM_BUCKET";
    static final String CHUMCAP = "EMPTY_CHUMCAP_BUCKET";
    static final NamespacedKey KEY = new NamespacedKey("skyclone", "fishing_chum_bucket");

    static final class Bucket {
        final UUID id;            // the Interaction entity (what you click)
        final UUID owner;
        final String ownerName;
        final String kind;
        final Location at;
        final long placedAt = System.currentTimeMillis();
        UUID display;
        UUID label;
        long deposits;

        Bucket(UUID id, UUID owner, String ownerName, String kind, Location at) {
            this.id = id; this.owner = owner; this.ownerName = ownerName; this.kind = kind; this.at = at;
        }

        boolean chumcap() { return CHUMCAP.equals(kind); }
    }

    static final Map<UUID, Bucket> LIVE = new LinkedHashMap<>();
    private static int task = -1;

    static boolean isBucket(String id) { return BUCKET.equals(id) || CHUMCAP.equals(id); }

    /** The Empty Chum Bucket is the materials module's item; its use is ours, so the lore says what it does. */
    static void register() {
        Sky.items().addLoreContributor("fishing_chum_bucket", new com.skyclone.api.item.LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(com.skyclone.api.item.ItemDef def, com.skyclone.api.item.ItemData data) {
                if (def == null || !BUCKET.equals(def.id)) return List.of();
                return List.of("", "&7Place it down next to water.", "&7Sea creatures slain nearby drop",
                        "&a+" + FishDials.CHUM_PER_BUCKET.get() + " Chum&7.", "&8Up to +" + FishDials.CHUM_CAP.get() + " total from buckets!");
            }
        });
    }

    static void start() {
        if (task != -1) return;
        task = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), ChumBuckets::sweep, 40L, 40L).getTaskId();
    }

    /** Remove every standing bucket. Their owners' records stay, so each one is handed back on the next sweep. */
    static void stop() {
        if (task != -1) { Bukkit.getScheduler().cancelTask(task); task = -1; }
        for (Bucket b : new ArrayList<>(LIVE.values())) despawn(b);
        LIVE.clear();
    }

    // ---- queries (used by Casting / SeaCreatures / the menus) ----------------------------------

    static int inReach(Location l, boolean chumcapOnly) {
        if (l == null || l.getWorld() == null) return 0;
        double r = Math.max(0, FishDials.CHUM_REACH.get());
        int n = 0;
        for (Bucket b : LIVE.values()) {
            if (chumcapOnly && !b.chumcap()) continue;
            if (b.at.getWorld() == l.getWorld() && b.at.distanceSquared(l) <= r * r) n++;
        }
        return n;
    }

    /** Fishing Speed the Chumcap Buckets in reach give an angler standing at {@code l}. */
    static double fishingSpeed(Location l) {
        if (!FishDials.CHUM_BUCKETS.get()) return 0;
        return FishMath.chumcapSpeed(inReach(l, true), FishDials.CHUMCAP_FS.get(), FishDials.CHUMCAP_FS_CAP.get());
    }

    /** The Mooshroom Diver trigger: the angler's OWN placed Chumcap Bucket within reach. */
    static boolean ownChumcapNear(Player p, Location l) {
        if (!FishDials.CHUM_BUCKETS.get() || l == null || l.getWorld() == null) return false;
        double r = Math.max(0, FishDials.CHUM_REACH.get());
        for (Bucket b : LIVE.values())
            if (b.chumcap() && b.owner.equals(p.getUniqueId()) && b.at.getWorld() == l.getWorld() && b.at.distanceSquared(l) <= r * r)
                return true;
        return false;
    }

    /** Chum a sea creature slain at {@code l} drops ({@code baitChum} = Glowy Chum Bait's extra). */
    static int chumFor(Location l, int baitChum) {
        if (!FishDials.CHUM_BUCKETS.get()) return Math.max(0, baitChum);
        return FishMath.chumDrop(inReach(l, false), FishDials.CHUM_PER_BUCKET.get(), FishDials.CHUM_CAP.get(),
                baitChum, FishDials.CHUM_BAIT_NEEDS_BUCKET.get());
    }

    static List<Bucket> owned(UUID owner) {
        List<Bucket> out = new ArrayList<>();
        for (Bucket b : LIVE.values()) if (b.owner.equals(owner)) out.add(b);
        return out;
    }

    // ---- placing ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        ItemStack it = e.getItem();
        String id = it == null ? null : Sky.items().idOf(it);
        if (!isBucket(id)) return;
        boolean denied = e.useItemInHand() == Event.Result.DENY;
        e.setCancelled(true);                      // never a head block, never scoop water
        Player p = e.getPlayer();
        if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getClickedBlock() == null) {
            Sky.vitals().flash(p, "&eRight-click the ground next to water to place it.", 40);
            return;
        }
        if (denied) { Msg.error(p, "You can't place that here."); return; }
        place(p, it, id, e.getClickedBlock(), e.getBlockFace());
    }

    /** Safety: a bucket item must never become a head block, whatever path the placement took. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (isBucket(Sky.items().idOf(e.getItemInHand()))) e.setCancelled(true);
    }

    /** Returns null when placed, else the reason it was not. */
    static String place(Player p, ItemStack hand, String kind, Block clicked, BlockFace face) {
        String why = placeCheck(p, hand, kind, clicked, face);
        if (why != null) { Msg.error(p, why); return why; }
        Block spot = face == BlockFace.UP || face == null ? clicked.getRelative(BlockFace.UP) : clicked.getRelative(face);
        hand.setAmount(hand.getAmount() - 1);
        FishData d = Fishing.data(p);
        d.chumBuckets.add(kind);
        Bucket b = spawn(p.getUniqueId(), p.getName(), kind, spot.getLocation().add(0.5, 0, 0.5));
        if (b == null) {                            // could not stand it up: hand it straight back
            d.chumBuckets.remove(kind);
            Sky.loot().give(p, kind, 1, LootSource.OTHER);
            Msg.error(p, "The bucket would not stand there.");
            return "spawn failed";
        }
        p.playSound(b.at, Sound.ITEM_BUCKET_EMPTY, 1f, 0.8f);
        Msg.send(p, "&aYou set down your " + Fishing.name(kind) + "&a. &7Sea creatures slain nearby drop &aChum&7;"
                + " right-click it with Chum to deposit, or with an empty hand to pick it up."
                + (b.chumcap() ? " &bAnglers nearby fish faster&7, and a &cMooshroom Diver &7may answer you." : ""));
        return null;
    }

    static String placeCheck(Player p, ItemStack hand, String kind, Block clicked, BlockFace face) {
        if (!FishDials.CHUM_BUCKETS.get()) return "Chum Buckets are switched off.";
        if (!Fishing.ready(p)) return "Your profile is still loading.";
        if (Fishing.island(clicked.getLocation()) == null) return "Chum Buckets only work on SkyBlock islands.";
        if (!Sky.items().meetsRequirements(p, hand, false)) return "You need Fishing 15 to place a Chumcap Bucket.";
        int max = Math.max(0, FishDials.CHUM_PER_PLAYER.get());
        if (owned(p.getUniqueId()).size() >= max) {
            Bucket o = owned(p.getUniqueId()).isEmpty() ? null : owned(p.getUniqueId()).get(0);
            return max == 0 ? "Chum Buckets cannot be placed right now."
                    : "You already have a bucket out" + (o == null ? "" : " at " + o.at.getBlockX() + ", " + o.at.getBlockY() + ", " + o.at.getBlockZ())
                    + " - pick it up first (/chumbuckets return).";
        }
        Block spot = face == BlockFace.UP || face == null ? clicked.getRelative(BlockFace.UP) : clicked.getRelative(face);
        if (!spot.isPassable() || spot.isLiquid()) return "There is no room for the bucket there.";
        if (!spot.getRelative(BlockFace.DOWN).getType().isSolid()) return "The bucket needs solid ground under it.";
        if (!nearWater(spot, Math.max(1, FishDials.CHUM_WATER_REACH.get()))) return "Place it next to water.";
        Location c = spot.getLocation().add(0.5, 0, 0.5);
        for (Bucket b : LIVE.values()) if (b.at.getWorld() == c.getWorld() && b.at.distanceSquared(c) < 1.0) return "There is already a bucket there.";
        return null;
    }

    /** Any water (or waterlogged block) within {@code r} blocks sideways, from one below to one above. Loaded chunks only. */
    static boolean nearWater(Block at, int r) {
        World w = at.getWorld();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -1; dy <= 1; dy++) {
            int x = at.getX() + dx, y = at.getY() + dy, z = at.getZ() + dz;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();
            if (m == Material.WATER || m == Material.BUBBLE_COLUMN) return true;
            if (b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) return true;
        }
        return false;
    }

    static Bucket spawn(UUID owner, String ownerName, String kind, Location at) {
        try {
            World w = at.getWorld();
            Interaction hit = w.spawn(at, Interaction.class, i -> {
                i.setInteractionWidth(0.8f);
                i.setInteractionHeight(0.9f);
                i.setResponsive(true);
                i.setPersistent(false);
                i.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, owner.toString());
            });
            Bucket b = new Bucket(hit.getUniqueId(), owner, ownerName, kind, at.clone());
            ItemStack shown = Sky.items().create(kind, 1);
            ItemDisplay disp = w.spawn(at.clone().add(0, 0.45, 0), ItemDisplay.class, d -> {
                d.setItemStack(shown);
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.FIXED);
                d.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, owner.toString());
            });
            b.display = disp.getUniqueId();
            TextDisplay td = w.spawn(at.clone().add(0, 1.3, 0), TextDisplay.class, t -> {
                t.text(Text.c(labelText(b)));
                t.setBillboard(Display.Billboard.CENTER);
                t.setPersistent(false);
                t.setShadowed(true);
                t.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, owner.toString());
            });
            b.label = td.getUniqueId();
            LIVE.put(b.id, b);
            return b;
        } catch (RuntimeException e) {
            Sky.log().warning("[fishing] chum bucket spawn: " + e);
            return null;
        }
    }

    static String labelText(Bucket b) {
        return (b.chumcap() ? "&c" : "&a") + b.ownerName + "'s " + (b.chumcap() ? "Chumcap Bucket" : "Chum Bucket")
                + "\n&7Deposit &aChum &7for coins + Fishing XP"
                + (b.deposits > 0 ? "\n&8" + Text.commasInt(b.deposits) + " chum deposited" : "");
    }

    static void despawn(Bucket b) {
        for (UUID u : new UUID[]{b.id, b.display, b.label}) {
            if (u == null) continue;
            Entity e = Bukkit.getEntity(u);
            if (e != null) e.remove();
        }
    }

    // ---- clicking a standing bucket ----------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(PlayerInteractEntityEvent e) {
        Bucket b = LIVE.get(e.getRightClicked().getUniqueId());
        if (b == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!Fishing.ready(p)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (CHUM.equals(Sky.items().idOf(hand))) { deposit(p, b, hand, p.isSneaking() ? hand.getAmount() : 1); return; }
        if (b.owner.equals(p.getUniqueId())) { pickUp(p, b, "&aYou picked up your " + Fishing.name(b.kind) + "&a."); return; }
        Msg.send(p, "&7" + b.ownerName + "'s " + Fishing.name(b.kind) + "&7. Right-click it holding &aChum &7to deposit"
                + " &6+" + Text.commas(FishDials.CHUM_COINS.get()) + " coins &7and &3+" + Text.commas(FishDials.CHUM_XP.get()) + " Fishing XP &7each"
                + (FishEffects.has(p, "LUMINOUS_BRACELET") ? " &8(+ your Luminous Bracelet's bonus)" : "") + "&7.");
    }

    /** Deposit {@code n} chum from {@code hand}. Returns {coins, xp} paid. */
    static double[] deposit(Player p, Bucket b, ItemStack hand, int n) {
        n = Math.max(0, Math.min(n, hand.getAmount()));
        if (n == 0) return new double[]{0, 0};
        boolean others = !b.owner.equals(p.getUniqueId());
        boolean bracelet = others && FishEffects.has(p, "LUMINOUS_BRACELET");
        double[] pay = FishMath.chumDeposit(n, FishDials.CHUM_COINS.get(), FishDials.CHUM_XP.get(), others, bracelet,
                FishDials.LUMINOUS_COINS.get(), FishDials.LUMINOUS_XP.get());
        hand.setAmount(hand.getAmount() - n);
        if (pay[0] > 0) Sky.economy().deposit(p, pay[0], "fishing:chum_deposit");
        if (pay[1] > 0) Fishing.addXp(p, pay[1], "fishing:chum_deposit");
        b.deposits += n;
        FishData d = Fishing.data(p);
        d.chumDeposited += n;
        if (others) d.chumDepositedOthers += n;
        Entity lab = b.label == null ? null : Bukkit.getEntity(b.label);
        if (lab instanceof TextDisplay td) td.text(Text.c(labelText(b)));
        p.playSound(b.at, Sound.ENTITY_GENERIC_SPLASH, 0.7f, 1.3f);
        b.at.getWorld().spawnParticle(Particle.SPLASH, b.at.clone().add(0, 0.8, 0), 12, 0.2, 0.1, 0.2, 0.05);
        Msg.send(p, "&aDeposited " + n + " Chum: &6+" + Text.commas(pay[0]) + " coins &3+" + Text.commas(pay[1]) + " Fishing XP"
                + (bracelet ? " &8(Luminous Bracelet)" : ""));
        return pay;
    }

    /** Take one of this owner's buckets back (entities removed; the item only if the profile recorded it). */
    static boolean pickUp(Player p, Bucket b, String msg) {
        LIVE.remove(b.id);
        despawn(b);
        FishData d = Fishing.data(p);
        if (d.chumBuckets.remove(b.kind)) {
            Sky.loot().give(p, b.kind, 1, LootSource.OTHER);
            if (msg != null) Msg.send(p, msg);
            return true;
        }
        // placed from another profile: that profile gets it back on its next sweep
        Msg.send(p, "&7That bucket was placed from your other profile - it went back there.");
        return false;
    }

    /** /chumbuckets return: every bucket this player has out comes back. */
    static int returnAll(Player p) {
        int n = 0;
        for (Bucket b : owned(p.getUniqueId())) if (pickUp(p, b, null)) n++;
        return n;
    }

    // ---- housekeeping --------------------------------------------------------------------------

    static void sweep() {
        long life = Math.max(0, FishDials.CHUM_LIFETIME.get()) * 60_000L;
        long now = System.currentTimeMillis();
        for (Iterator<Bucket> it = LIVE.values().iterator(); it.hasNext(); ) {
            Bucket b = it.next();
            Entity hit = Bukkit.getEntity(b.id);
            Player o = Bukkit.getPlayer(b.owner);
            boolean ownerHere = o != null && o.isOnline() && o.getWorld() == b.at.getWorld();
            boolean expired = life > 0 && now - b.placedAt > life;
            if (hit == null || !hit.isValid() || !ownerHere || expired || !FishDials.CHUM_BUCKETS.get()) {
                it.remove();
                despawn(b);
                if (expired && ownerHere) Msg.send(o, "&7Your " + Fishing.name(b.kind) + " &7has been returned to you.");
            }
        }
        // hand back every bucket a profile has recorded as out but that is no longer standing
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Fishing.ready(p)) continue;
            FishData d = Fishing.data(p);
            if (d.chumBuckets.isEmpty()) continue;
            returnMissing(p, d, owned(p.getUniqueId()));
        }
    }

    /** Give back the recorded buckets beyond the ones standing. Pure-ish decision in {@link #missing}. */
    static int returnMissing(Player p, FishData d, List<Bucket> standing) {
        List<String> back = missing(d.chumBuckets, standing.stream().map(b -> b.kind).toList());
        for (String kind : back) {
            d.chumBuckets.remove(kind);
            Sky.loot().give(p, kind, 1, LootSource.OTHER);
            Msg.send(p, "&7Your " + Fishing.name(kind) + " &7has been returned to you.");
        }
        return back.size();
    }

    /** Recorded-but-not-standing bucket ids (multiset difference, recorded minus standing). */
    static List<String> missing(List<String> recorded, List<String> standing) { return FishMath.missingBuckets(recorded, standing); }

    // ---- menu + command ----------------------------------------------------------------------

    static void menu(Player p) {
        if (!Fishing.ready(p)) return;
        Menu m = new Menu("Chum Buckets", 4).chrome();
        FishData d = Fishing.data(p);
        m.set(4, Icons.of(Material.BUCKET, "&aChum Buckets",
                "&7Set an &aEmpty Chum Bucket &7or &cChumcap", "&cBucket &7down next to water.", "",
                "&7Sea creatures slain nearby drop", "&a+" + FishDials.CHUM_PER_BUCKET.get() + " Chum &7per bucket &8(up to +" + FishDials.CHUM_CAP.get() + ")&7.",
                "&7Deposit Chum in any bucket: &6+" + Text.commas(FishDials.CHUM_COINS.get()) + " coins",
                "&7and &3+" + Text.commas(FishDials.CHUM_XP.get()) + " Fishing XP &7each.", "",
                "&7Chumcap: &b+" + Text.commas(FishDials.CHUMCAP_FS.get()) + " Fishing Speed &7to every angler",
                "&7nearby &8(up to +" + Text.commas(FishDials.CHUMCAP_FS_CAP.get()) + ")&7, and a &cMooshroom Diver",
                "&7may answer its owner.", "",
                "&7Chum deposited: &a" + Text.commasInt(d.chumDeposited) + " &8(" + Text.commasInt(d.chumDepositedOthers) + " in others' buckets)"));
        List<Bucket> mine = owned(p.getUniqueId());
        int slot = 11;
        for (Bucket b : mine) {
            if (slot > 15) break;
            String where = b.at.getBlockX() + ", " + b.at.getBlockY() + ", " + b.at.getBlockZ();
            m.set(slot++, Icons.of(b.chumcap() ? Material.RED_MUSHROOM : Material.BUCKET, "&a" + Fishing.name(b.kind),
                    "&7At &f" + where, "&7Chum deposited: &a" + Text.commasInt(b.deposits), "", "&eClick to pick it up!"),
                    c -> { if (LIVE.containsKey(b.id)) pickUp(c.player(), b, "&aYour bucket is back in your inventory."); menu(c.player()); });
        }
        if (mine.isEmpty()) m.set(13, Icons.of(Material.GRAY_DYE, "&7No bucket out", "&7Right-click the ground next to", "&7water holding a bucket."));
        int near = inReach(p.getLocation(), false);
        double fs = fishingSpeed(p.getLocation());
        m.set(22, Icons.of(Material.COMPASS, "&aHere", "&7Buckets in reach: &a" + near,
                "&7Chum per slain creature: &a+" + chumFor(p.getLocation(), 0),
                "&7Chumcap Fishing Speed: &b+" + Text.commas(fs)));
        m.set(31, Icons.back("Fishing"), c -> FishMenus.main(c.player()));
        m.open(p);
    }
}
