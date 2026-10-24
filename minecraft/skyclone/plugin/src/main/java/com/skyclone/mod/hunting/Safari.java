package com.skyclone.mod.hunting;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Critter Safari: an island of four biomes (Cavern, Forest, Haunted, Icy). Hunting XV and a Safari Ticket
 * start a timed hunt with a stack of Critter Capsules; every capture pays its shard, Hunting XP and Safari
 * Essence (more with a better ticket), 1 in 4,096 critters is Sparkling (10x). Hunts per biome are milestones
 * that power the Safari Belt; essence buys perks. Tickets: Basic 32 capsules; Economy 40 (+10% essence,
 * +5 Hunting Wisdom); Premium 48 (+25%, +15); First-Class 56 (+50%, +25) - research 03 section 25.
 */
final class Safari implements Listener {

    static final String ISLAND = "critter_safari";
    static final String[] BIOMES = {"cavern", "forest", "haunted", "icy"};
    /** Hunts in one biome per milestone tier. // TUNE */
    static final int[] MILESTONES = {10, 50, 150, 400, 1000};
    static final String[] TICKETS = {"SAFARI_TICKET_BASIC", "SAFARI_TICKET_ECONOMY", "SAFARI_TICKET_PREMIUM", "SAFARI_TICKET_FIRST_CLASS"};
    /** Forest Essence to upgrade a ticket one step. // TUNE */
    static final long[] UPGRADE = {500, 1500, 5000};

    /** Perk id -> {max level, essence per level (x level)}. // TUNE */
    static final Map<String, int[]> PERKS = new LinkedHashMap<>();
    static {
        PERKS.put("capsules", new int[]{5, 150});
        PERKS.put("essence", new int[]{10, 100});
        PERKS.put("sparkle", new int[]{5, 400});
    }

    static void register() {
        Bukkit.getPluginManager().registerEvents(new Safari(), Sky.plugin());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), Safari::tick, 40L, 20L);
    }

    static boolean active(Player p) {
        if (!Hunt.ready(p)) return false;
        HuntingData d = Hunt.data(p);
        return d.safariTicket != null && d.safariEndsAt > System.currentTimeMillis();
    }

    static ItemDef ticket(Player p) {
        HuntingData d = Hunt.data(p);
        return d.safariTicket == null ? null : Sky.items().def(d.safariTicket);
    }

    /** Ticket bonuses while hunting, Safari Belt by milestones. */
    static void stats(Player p, StatMap m) {
        if (active(p)) {
            ItemDef t = ticket(p);
            if (t != null && t.extra != null && t.extra.has("wisdom")) m.add(Stat.HUNTING_WISDOM, t.extra.get("wisdom").getAsDouble());
        }
        if (AttributeEffects.wearing(p, "SAFARI_BELT")) {
            int tiers = milestoneTiers(Hunt.data(p));
            m.add(Stat.HUNTING_FORTUNE, tiers);          // TUNE: +1 Hunting Fortune per milestone tier
            m.add(Stat.HUNTING_WISDOM, tiers * 0.5);     // TUNE: +0.5 Hunting Wisdom per milestone tier
        }
    }

    static int milestoneTier(long hunts) {
        int t = 0;
        for (int m : MILESTONES) if (hunts >= m) t++;
        return t;
    }

    static int milestoneTiers(HuntingData d) {
        int t = 0;
        for (String b : BIOMES) t += milestoneTier(d.safariHunts.getOrDefault(b, 0L));
        return t;
    }

    // ---------------------------------------------------------------- hunt sessions

    static boolean start(Player p, ItemStack ticketItem) {
        if (!Hunt.ready(p)) return false;
        String id = Sky.items().idOf(ticketItem);
        ItemDef t = Sky.items().def(id);
        if (t == null || !t.has("safari_ticket")) return false;
        if (!ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) {
            Msg.error(p, "&cUse your ticket inside the Critter Safari. &7(/warp safari)");
            return false;
        }
        if (!Sky.requirements().checkOrTell(p, "skill:hunting:" + HDials.SAFARI_LEVEL.get())) return false;
        if (active(p)) { Msg.error(p, "&cYou are already on a hunt. &7(" + Text.duration(left(p)) + " left)"); return false; }
        HuntingData d = Hunt.data(p);
        int caps = (t.extra.has("capsules") ? t.extra.get("capsules").getAsInt() : 32) + 2 * d.safariPerks.getOrDefault("capsules", 0);
        ticketItem.setAmount(ticketItem.getAmount() - 1);
        d.safariTicket = id;
        d.safariCapsules = caps;
        d.safariCaught = 0;
        d.safariEndsAt = System.currentTimeMillis() + (long) (HDials.SAFARI_MINUTES.get() * 60_000);
        Sky.loot().give(p, "CRITTER_CAPSULE", caps, LootSource.REWARD);
        Sky.profiles().saveNow(p);
        Hunt.invalidate(p);
        Msg.box(p, "&a&lSAFARI HUNT STARTED", java.util.List.of(
                "&7Ticket: " + t.name, "&7Capsules: &a" + caps, "&7Time: &e" + Text.duration(left(p)),
                "&7Throw capsules at critters in the four biomes!"));
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.2f);
        return true;
    }

    static long left(Player p) {
        return Math.max(0, (Hunt.data(p).safariEndsAt - System.currentTimeMillis()) / 1000);
    }

    static void end(Player p, String why) {
        if (!Hunt.ready(p)) return;
        HuntingData d = Hunt.data(p);
        if (d.safariTicket == null) return;
        int removed = Sky.items().remove(p, "CRITTER_CAPSULE", 10_000) + Sky.items().remove(p, "MASTERFUL_CRITTER_CAPSULE", 10_000);
        Msg.box(p, "&6&lSAFARI HUNT OVER", java.util.List.of("&7" + why, "&7Critters caught: &a" + d.safariCaught,
                removed > 0 ? "&8" + removed + " unused capsules vanished." : "&8All capsules used."));
        d.safariTicket = null;
        d.safariCapsules = 0;
        d.safariEndsAt = 0;
        Hunt.invalidate(p);
        Sky.profiles().saveNow(p);
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Hunt.ready(p)) continue;
            HuntingData d = Hunt.data(p);
            if (d.safariTicket == null) continue;
            if (d.safariEndsAt <= now) end(p, "Time is up!");
            else if (!ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) end(p, "You left the Critter Safari.");
        }
    }

    /** A Critter Capsule caught a critter. */
    static void capsuleCatch(Player p, LivingEntity le, ShardDef s) {
        if (!active(p)) {
            Msg.error(p, "&cYou need an active Safari hunt (use a Safari Ticket).");
            return;
        }
        HuntingData d = Hunt.data(p);
        String hab = le.getPersistentDataContainer().get(new NamespacedKey(Sky.plugin(), "hunting_habitat"), PersistentDataType.STRING);
        int perk = d.safariPerks.getOrDefault("sparkle", 0);
        if (!Critters.sparkling(le) && perk > 0 && ThreadLocalRandom.current().nextDouble() < HDials.SPARKLING.get() * 0.10 * perk)
            le.getPersistentDataContainer().set(Critters.SPARKLING, PersistentDataType.BYTE, (byte) 1);   // the Sparkle perk: +10% per level
        boolean sparkle = Critters.sparkling(le);
        Tools.catchCritter(p, le, s, "capsule", 0);
        d.safariCaught++;
        String biome = hab != null && hab.startsWith("safari_") ? hab.substring(7) : "forest";
        long before = d.safariHunts.getOrDefault(biome, 0L);
        d.safariHunts.merge(biome, 1L, Long::sum);
        if (milestoneTier(before + 1) > milestoneTier(before))
            Msg.send(p, "&6&lSAFARI MILESTONE! &e" + Text.titleCase(biome) + " Biome " + Text.roman(milestoneTier(before + 1))
                    + " &7- your Safari Belt grows stronger.");
        ItemDef t = ticket(p);
        double bonus = (t != null && t.extra.has("essence") ? t.extra.get("essence").getAsDouble() : 0) + 5 * d.safariPerks.getOrDefault("essence", 0);
        long ess = Math.round(HuntMath.safariEssence(s.rarity) * (1 + bonus / 100.0) * (sparkle ? HDials.SPARKLING_MULT.get() : 1));
        addEssence(p, ess);
    }

    /** A Sparkling critter: the Rainbow Feather, plus Splendor's bonus feather. */
    static void sparkled(Player p) {
        HuntingData d = Hunt.data(p);
        d.sparkling++;
        Msg.send(p, "&d&l✦ SPARKLING! &7Rewards x" + HuntMath.fmt(HDials.SPARKLING_MULT.get()) + "!");
        Sky.loot().give(p, "RAINBOW_FEATHER", 1, LootSource.REWARD);
        double splendor = Hunt.snapshot(p).value("splendor");
        if (splendor > 0 && ThreadLocalRandom.current().nextDouble() * 100 < splendor) Sky.loot().give(p, "RAINBOW_FEATHER", 1, LootSource.REWARD);
    }

    static void addEssence(Player p, long n) {
        if (n <= 0) return;
        if (Sky.has(Features.Essence.class)) Sky.loot().give(p, "ESSENCE_SAFARI", (int) Math.min(Integer.MAX_VALUE, n), LootSource.REWARD);
        else Hunt.data(p).safariEssence += n;
    }

    static long essence(Player p) {
        long pouch = Sky.has(Features.Essence.class) ? Sky.service(Features.Essence.class).essence(p, "SAFARI") : 0;
        return pouch + Hunt.data(p).safariEssence + Sky.items().count(p, "ESSENCE_SAFARI");
    }

    static boolean takeEssence(Player p, long n) {
        if (essence(p) < n) return false;
        HuntingData d = Hunt.data(p);
        long need = n;
        long own = Math.min(d.safariEssence, need);
        d.safariEssence -= own;
        need -= own;
        if (need > 0) need -= Sky.items().remove(p, "ESSENCE_SAFARI", (int) Math.min(Integer.MAX_VALUE, need));
        if (need > 0 && !Sky.service(Features.Essence.class).takeEssence(p, "SAFARI", need)) {
            d.safariEssence += n - need;   // give back what we took; nothing is lost
            return false;
        }
        return true;
    }

    static boolean buyPerk(Player p, String perk) {
        int[] def = PERKS.get(perk);
        if (def == null || !Hunt.ready(p)) return false;
        HuntingData d = Hunt.data(p);
        int lvl = d.safariPerks.getOrDefault(perk, 0);
        if (lvl >= def[0]) { Msg.error(p, "&cThat perk is maxed."); return false; }
        long cost = (long) def[1] * (lvl + 1);
        if (!takeEssence(p, cost)) { Msg.error(p, "&cYou need &f" + Text.commas(cost) + " Safari Essence&c."); return false; }
        d.safariPerks.put(perk, lvl + 1);
        Msg.success(p, "&aUpgraded &e" + Text.titleCase(perk) + " &ato " + Text.roman(lvl + 1) + "&a!");
        return true;
    }

    /** Upgrade the ticket in hand one class with Forest Essence (June Bug's Ticket Discount applies). */
    static boolean upgradeTicket(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(hand);
        int idx = -1;
        for (int i = 0; i < TICKETS.length; i++) if (TICKETS[i].equals(id)) idx = i;
        if (idx < 0) { Msg.error(p, "&cHold the Safari Ticket you want to upgrade."); return false; }
        if (idx >= TICKETS.length - 1) { Msg.error(p, "&cThat is already a First-Class ticket."); return false; }
        double disc = Hunt.snapshot(p).value("ticket_discount");
        long cost = Math.round(UPGRADE[idx] * Math.max(0, 1 - disc / 100.0));
        Features.Essence pouch = Sky.service(Features.Essence.class);
        if (!pouch.takeEssence(p, "FOREST", cost)) {
            Msg.error(p, "&cYou need &f" + Text.commas(cost) + " Forest Essence &cin your essence pouch.");
            return false;
        }
        hand.setAmount(hand.getAmount() - 1);
        Sky.loot().give(p, TICKETS[idx + 1], 1, LootSource.NPC_SHOP);
        Msg.success(p, "&aUpgraded to " + Sky.items().def(TICKETS[idx + 1]).name + "&a!");
        return true;
    }

    @EventHandler
    public void onTicket(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getPlayer().getInventory().getItemInMainHand();
        if (hand.getType().isAir()) return;
        ItemDef d = Sky.items().def(Sky.items().idOf(hand));
        if (d == null || !d.has("safari_ticket")) return;
        e.setCancelled(true);
        start(e.getPlayer(), hand);
    }
}
