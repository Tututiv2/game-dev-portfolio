package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Mythological Ritual (research/05 §8.4, reference wiki "Mythological Ritual", wave 5): while the huntress's
 * ritual perk is on she sells the Griffin pet and the Ancestral Spade. With a Griffin out, a spade's Echo
 * (right-click) reveals a chain of Griffin Burrows in the Hub - 4 / 6 / 8 long for the Ancestral / Archaic / Deific
 * Spade, +2 with the Erudite reforge (max 10) - and points to the next one. The first burrow of a chain is always
 * treasure; after that 75% hold a creature. The Griffin's rarity picks the creature pool (each rarity opens two more
 * creatures, from the Hunter and the Twin Lynxes at Common to the Manticore and the Labyrinth King at Mythic) and the
 * variant you meet (Common .. Empyrean). Tracking (Golden Fleece, the Elusive Hunter carnival perk, a Mythic Griffin's
 * Old Earth, hunting shards) makes the Legendary-and-up creatures likelier. A Sphinx asks a riddle: answer right
 * for a piece of treasure, wrong and it enrages.
 */
final class Mythology implements Listener {

    static final class Chain {
        final List<Location> burrows = new ArrayList<>();
        int next;
        /** Griffin rarity index 0..5 the chain was revealed with. */
        final int rarity;
        final String spade;
        Chain(int rarity, String spade) { this.rarity = rarity; this.spade = spade; }
        Location current() { return next < burrows.size() ? burrows.get(next) : null; }
    }

    /** A Sphinx waiting for its digger's answer. */
    private record Riddle(UUID owner, SphinxRiddles.Riddle riddle, long seed, String spade) {}

    private final MayorsModule mod;
    private final Map<UUID, Chain> chains = new HashMap<>();
    private final Map<UUID, Riddle> riddles = new HashMap<>();      // sphinx entity -> its riddle
    private boolean announced;

    Mythology(MayorsModule mod) { this.mod = mod; }

    boolean active() { return mod.election().perkActive("diana.mythological_ritual"); }

    void register() {
        Sky.abilities().register("mayors_spade_echo", use -> echo(use.player()));
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
    }

    /** Every second: keep the calendar event in step and draw each player's next burrow. */
    void tick() {
        boolean on = active();
        Cal.drive(Cal.MYTH, on);
        if (on && !announced) {
            announced = true;
            for (Player p : Bukkit.getOnlinePlayers())
                if (EventAlerts.news(p, Cal.MYTH))
                    Msg.send(p, "&2&lMYTHOLOGICAL RITUAL! &7The huntress is in town selling &fGriffins&7. Dig up &eGriffin Burrows &7in the Hub! (&a/mythology&7)");
        } else if (!on) {
            announced = false;
            chains.clear();
        }
        riddles.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
        if (!on) return;
        for (Map.Entry<UUID, Chain> e : chains.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            Location b = e.getValue().current();
            if (p == null || b == null || b.getWorld() != p.getWorld()) continue;
            if (b.distanceSquared(p.getLocation()) > 96 * 96) continue;
            p.spawnParticle(Particle.CRIT, b.clone().add(0.5, 1.1, 0.5), 12, 0.3, 0.2, 0.3, 0.02);
            p.spawnParticle(Particle.ENCHANT, b.clone().add(0.5, 1.4, 0.5), 8, 0.3, 0.3, 0.3, 0.5);
        }
    }

    // =============================================================================================== griffin, spade, tracking

    /** Griffin rarity index 0..5 of the active pet; -1 when there is no Griffin out and the dial requires one. */
    int rarity(Player p) {
        String type = null, rarity = null;
        try {
            Features.Pets pets = Sky.service(Features.Pets.class);
            type = pets.activeType(p);
            rarity = pets.activeRarity(p);
        } catch (Throwable ignored) { }
        if (type == null || !type.toUpperCase().startsWith("GRIFFIN")) return mod.dials().mythRequireGriffin.get() ? -1 : 0;
        return rarityIndex(rarity);
    }

    /** True when the player has a Griffin pet out (whatever the dial says). */
    static boolean griffinOut(Player p) {
        try {
            String type = Sky.service(Features.Pets.class).activeType(p);
            return type != null && type.toUpperCase().startsWith("GRIFFIN");
        } catch (Throwable t) { return false; }
    }

    /** The creature variant 1..6 this player would meet (0 = no Griffin): for the admin readout. */
    int tier(Player p) { int r = rarity(p); return r < 0 ? 0 : MythRules.variant(r); }

    static int rarityIndex(String rarity) {
        if (rarity == null) return 0;
        String r = rarity.trim().toUpperCase();
        try { return Math.max(0, Math.min(5, Integer.parseInt(r))); } catch (NumberFormatException ignored) { }
        return switch (r) {
            case "UNCOMMON" -> 1; case "RARE" -> 2; case "EPIC" -> 3; case "LEGENDARY" -> 4; case "MYTHIC" -> 5; default -> 0;
        };
    }

    private static String heldSpade(Player p) {
        String id = Sky.items().idOf(p.getInventory().getItemInMainHand());
        return MythRules.isSpade(id) ? id : null;
    }

    private static boolean erudite(Player p) {
        try {
            ItemStack held = p.getInventory().getItemInMainHand();
            ItemData d = Sky.items().data(held);
            return d != null && "erudite".equalsIgnoreCase(d.reforge);
        } catch (Throwable t) { return false; }
    }

    /**
     * Tracking on Griffin Burrows: Golden Fleece +15, the Elusive Hunter carnival perk (+2.5 a tier), a Mythic Griffin's
     * Old Earth (+1 per burrow dug in the current chain) and hunting's own Tracking (shards).
     */
    double tracking(Player p) {
        double t = 0;
        try {
            if (Sky.service(Features.Accessories.class).has(p, "GOLDEN_FLEECE")) t += 15;
            else for (ItemStack it : Sky.service(Features.Equipment.class).worn(p))
                if ("GOLDEN_FLEECE".equals(it == null ? null : Sky.items().idOf(it))) { t += 15; break; }
        } catch (Throwable ignored) { }
        t += mod.carnival().perks().value(p, "elusive_hunter");
        Chain c = chains.get(p.getUniqueId());
        if (c != null && griffinOut(p)) {
            try { if (rarityIndex(Sky.service(Features.Pets.class).activeRarity(p)) >= 5) t += c.next; } catch (Throwable ignored) { }
        }
        try { t += Math.max(0, Sky.service(Features.Hunting.class).attributeLevel(p, "stat:tracking")); } catch (Throwable ignored) { }
        return t;
    }

    // =============================================================================================== echo + dig

    /** Spade right-click: dig if standing on a burrow, else reveal/point. Returns true to start the cooldown. */
    boolean echo(Player p) {
        if (!active()) { Msg.error(p, "The Mythological Ritual is not running right now."); return false; }
        if (!"hub".equals(Sky.worlds().islandOf(p.getWorld()))) { Msg.error(p, "Griffin Burrows are only found in the Hub."); return false; }
        int r = rarity(p);
        if (r < 0) { Msg.error(p, "You need a Griffin pet out to sense burrows!"); return false; }
        Chain c = chains.get(p.getUniqueId());
        if (c != null && c.current() != null && near(p, c.current())) { dig(p, c); return false; }
        if (c == null || c.current() == null) {
            String spade = heldSpade(p);
            c = newChain(p, r, spade == null ? MythRules.SPADES.get(0) : spade, MythRules.chainLength(mod.data().myth, spade, erudite(p)));
            if (c == null) { Msg.error(p, "The spade hums, but finds nothing nearby. Try somewhere more open."); return false; }
            chains.put(p.getUniqueId(), c);
            Msg.send(p, "&2&lBURROW! &7The spade senses a chain of &a" + c.burrows.size() + " &eGriffin Burrows&7.");
        }
        pointAt(p, c.current());
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.5f);
        return true;
    }

    private static boolean near(Player p, Location b) {
        return b.getWorld() == p.getWorld() && b.clone().add(0.5, 1, 0.5).distanceSquared(p.getLocation()) <= 3.2 * 3.2;
    }

    private void pointAt(Player p, Location target) {
        Location eye = p.getEyeLocation();
        Vector dir = target.clone().add(0.5, 1, 0.5).toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist < 0.1) return;
        dir.normalize();
        for (double d = 1; d < Math.min(18, dist); d += 0.6) {
            Location at = eye.clone().add(dir.clone().multiply(d));
            p.spawnParticle(Particle.DRIPPING_DRIPSTONE_LAVA, at, 1, 0, 0, 0, 0);
        }
        Sky.vitals().flash(p, "&eNext burrow: &a" + Math.round(dist) + " blocks", 40);
    }

    private Chain newChain(Player p, int rarity, String spade, int length) {
        EventData.Myth my = mod.data().myth;
        Chain c = new Chain(rarity, spade);
        Location from = p.getLocation();
        for (int i = 0; i < Math.max(1, length); i++) {
            Location spot = findSpot(from, my.radiusMin, my.radiusMax);
            if (spot == null) break;
            c.burrows.add(spot);
            from = spot;
        }
        return c.burrows.isEmpty() ? null : c;
    }

    /** A grass-like, open, non-safe ground block in the Hub within [rMin, rMax] of {@code from}. */
    private Location findSpot(Location from, int rMin, int rMax) {
        World w = from.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 40; attempt++) {
            double ang = r.nextDouble() * Math.PI * 2;
            double rad = rMin + r.nextDouble() * Math.max(1, rMax - rMin);
            int x = (int) Math.floor(from.getX() + Math.cos(ang) * rad);
            int z = (int) Math.floor(from.getZ() + Math.sin(ang) * rad);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getHighestBlockYAt(x, z);
            if (Math.abs(y - from.getY()) > 20) continue;
            Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();
            if (!(m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.COARSE_DIRT || m == Material.PODZOL
                    || m == Material.DIRT_PATH || m == Material.SAND || m == Material.GRAVEL || m == Material.MOSS_BLOCK)) continue;
            if (!w.getBlockAt(x, y + 1, z).isPassable() || !w.getBlockAt(x, y + 2, z).isPassable()) continue;
            Location at = new Location(w, x, y, z);
            Layout.Zone zone = Sky.worlds().zoneAt(at.clone().add(0.5, 1, 0.5));
            if (zone != null && (zone.flags().contains(Layout.Zone.SAFE) || zone.flags().contains(Layout.Zone.NO_MOBS))) continue;
            return at;
        }
        return null;
    }

    private void dig(Player p, Chain c) {
        Location b = c.current();
        boolean first = c.next == 0;
        c.next++;
        EventData.Myth my = mod.data().myth;
        boolean last = c.next >= c.burrows.size();
        PlayerData pd = mod.data(p);
        if (pd != null) pd.burrowsDug++;
        Location above = b.clone().add(0.5, 1, 0.5);
        p.getWorld().spawnParticle(Particle.BLOCK, above, 30, 0.4, 0.3, 0.4, b.getBlock().getBlockData());
        Msg.sound(p, Sound.BLOCK_GRAVEL_BREAK, 0.8f);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (MythRules.creatureBurrow(first, mod.dials().mythMobChance.get(), r.nextDouble())) {
            String kind = MythRules.pick(MythRules.pool(my, c.rarity, tracking(p)), r);
            spawnCreature(p, kind, MythRules.variant(c.rarity), above, c.spade);
        } else {
            treasure(p, c.spade, "&6&lTREASURE! ");
        }
        if (last) {
            Msg.send(p, "&2&lCHAIN COMPLETE! &7You dug every burrow. Use the spade again for a new chain.");
            chains.remove(p.getUniqueId());
        } else {
            Msg.send(p, "&7Burrow &a" + c.next + "/" + c.burrows.size() + " &7dug. Echo again to find the next one.");
        }
    }

    /** The highest variant at or below {@code variant} (1..6) that this creature exists in. */
    static String mobId(String kind, int variant) {
        for (int t = Math.min(6, variant); t >= 1; t--) {
            String id = "myth_" + kind + "_" + t;
            try { if (Sky.mobs().def(id) != null) return id; } catch (Throwable ignored) { }
        }
        // creatures that start at a higher rarity (Sphinx, Manticore...): their first variant
        for (int t = Math.max(1, variant); t <= 6; t++) {
            String id = "myth_" + kind + "_" + t;
            try { if (Sky.mobs().def(id) != null) return id; } catch (Throwable ignored) { }
        }
        return "myth_hunter_1";
    }

    void spawnCreature(Player p, String kind, int variant, Location at) { spawnCreature(p, kind, variant, at, heldSpade(p)); }

    void spawnCreature(Player p, String kind, int variant, Location at, String spade) {
        String id = mobId(kind, variant);
        int count = "lynx".equals(kind) ? 2 : 1;
        List<LivingEntity> made = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            try {
                LivingEntity e = Sky.mobs().spawn(id, at.clone().add(i * 1.2, 0, 0), p);
                double scale = mod.dials().mythHpScale.get();
                if (e != null && scale > 0 && Math.abs(scale - 1) > 1e-9) Sky.mobs().setMaxHealth(e, Sky.mobs().maxHealth(e) * scale);
                if (e != null) made.add(e);
            } catch (Throwable t) {
                Sky.log().warning("[mayors] could not spawn " + id + ": " + t);
            }
        }
        MobDef def = Sky.mobs().def(id);
        String nm = def == null ? kind : def.name;
        boolean named = nm.startsWith("The ") || nm.startsWith("the ");
        Msg.send(p, "&c&lUh oh! &7You dug out " + (count > 1 || named ? "" : "a ") + "&2" + nm + (count > 1 ? "es" : "") + "&7!");
        Msg.sound(p, Sound.ENTITY_RAVAGER_ROAR, 1.2f);
        String hint = hint(kind);
        if (hint != null) Msg.send(p, hint);
        if ("inquisitor".equals(kind) || "manticore".equals(kind) || "minos".equals(kind)) {
            String col = "minos".equals(kind) ? "&6&l" : "manticore".equals(kind) ? "&4&l" : "&6&l";
            Msg.broadcast(col + Text.strip(nm).toUpperCase() + "! &e" + p.getName() + " &7dug up " + "&6" + nm + " &7in the Hub at &e"
                    + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + "&7!");
        }
        if ("sphinx".equals(kind) && !made.isEmpty()) {
            LivingEntity sphinx = made.get(0);
            long seed = ThreadLocalRandom.current().nextLong();
            riddles.put(sphinx.getUniqueId(), new Riddle(p.getUniqueId(), SphinxRiddles.pick(seed), seed, spade));
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline() && sphinx.isValid()) openRiddle(p, sphinx); }, 20L);
        }
    }

    /** One line telling the player how this creature fights. */
    static String hint(String kind) {
        return switch (kind) {
            case "lynx" -> "&7Hit them &ein turn&7: only the lynx with &cangry particles &7can be hurt.";
            case "nymph" -> "&7Below half health it floods the ground around it and moves faster in the water. &eFight it on dry land!";
            case "bull" -> "&7It charges every few seconds. &eSidestep the charge!";
            case "harpy" -> "&7It leaps and shoots from the air; each arrow drains your &bIntelligence &7and &cVitality&7.";
            case "gaia" -> "&7Its stone shell only cracks every &e6th &7hit (more as it weakens). Watch the sky!";
            case "minotaur" -> "&7Its hits make you &4Bleed&7, and its thrown axe bleeds you more. Break line of sight!";
            case "champion" -> "&7It grows stronger &eevery second &7it lives. Kill it fast!";
            case "sphinx" -> "&7It has a riddle for you. &eAnswer right &7for treasure - a wrong answer &cenrages &7it.";
            case "inquisitor" -> "&7Each of your hits drains your stats; it grows stronger every second. &eMove when the ground sparks!";
            case "manticore" -> "&7When its tail glows green, &eget away&7: a Fatal Sting stops your healing - kill it within 30 seconds.";
            case "minos" -> "&7The king rides behind a &675-hit shield&7. Then every blow builds his &cRage&7. Your Vitality is halved near him.";
            default -> null;
        };
    }

    private void treasure(Player p, String spade, String head) {
        EventData.Myth my = mod.data().myth;
        EventData.Bag bag = my.treasure.get(spade == null ? MythRules.SPADES.get(0) : spade);
        if (bag == null) bag = my.treasure.get(MythRules.SPADES.get(0));
        if (bag == null && !my.treasure.isEmpty()) bag = my.treasure.values().iterator().next();
        if (bag == null) return;
        boolean crown = "CROWN_OF_GREED".equals(Sky.items().idOf(p.getInventory().getHelmet()));   // Crown of Greed: 2x burrow coins
        long coins = MythRules.treasureCoins(Goods.between(bag.coins), crown, mod.carnival().perks().value(p, "deadly_greed"));
        List<String> got = Goods.roll(bag.drops, 1.0);
        // reference: a treasure burrow holds a Griffin Feather OR coins (2/3 feathers); our table rolls the feather,
        // and only a burrow without one pays its coins
        boolean feather = got.contains("GRIFFIN_FEATHER");
        if (!feather && coins > 0) Sky.economy().deposit(p, coins, "mayors: griffin burrow treasure");
        for (String id : got) Goods.give(p, id, 1, LootSource.EVENT);
        StringBuilder sb = new StringBuilder(head + "&7You dug out ");
        boolean any = false;
        if (!feather && coins > 0) { sb.append("&6").append(Text.commas(coins)).append(" coins"); any = true; }
        for (String id : got) { sb.append(any ? "&7, " : "").append(Goods.displayName(id)); any = true; }
        if (!any) sb.append("&7nothing but dirt");
        Msg.send(p, sb + "&7!");
        Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f);
    }

    // =============================================================================================== sphinx

    void openRiddle(Player p, LivingEntity sphinx) {
        Riddle rd = riddles.get(sphinx.getUniqueId());
        if (rd == null) return;
        if (!rd.owner().equals(p.getUniqueId())) { Msg.error(p, "The Sphinx only answers to the one who dug it up."); return; }
        Menu m = new Menu("The Sphinx's Riddle", 3).chrome();
        m.set(4, Icons.of(Material.WRITABLE_BOOK, "&6The Sphinx asks:", "&e" + rd.riddle().question(), "",
                "&7Answer right for a piece of &6treasure&7.", "&7Answer wrong and it grows &cangrier&7."));
        List<String> answers = rd.riddle().shuffled(rd.seed());
        int[] slots = {11, 13, 15};
        for (int i = 0; i < answers.size() && i < slots.length; i++) {
            String a = answers.get(i);
            m.set(slots[i], Icons.of(Material.PAPER, "&a" + a, "", "&eClick to answer!"), c -> answer(c.player(), sphinx, a));
        }
        m.open(p);
    }

    private void answer(Player p, LivingEntity sphinx, String chosen) {
        Riddle rd = riddles.remove(sphinx.getUniqueId());
        p.closeInventory();
        if (rd == null || !sphinx.isValid()) return;
        if (SphinxRiddles.correct(rd.riddle(), chosen)) {
            Msg.send(p, "&e[NPC] &6Sphinx&f: Correct. Take this, and then let us see if you can take ME.");
            treasure(p, rd.spade(), "&6&lRIDDLE SOLVED! ");
        } else {
            Msg.send(p, "&e[NPC] &6Sphinx&f: Wrong! The answer was &a" + rd.riddle().answer() + "&f. Now you pay for it!");
            mod.mythMechanics().enrage(sphinx);
            Msg.send(p, "&cThe Sphinx is enraged: &7+50% health and a crueller bleed.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClickSphinx(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof LivingEntity le)) return;
        if (!riddles.containsKey(le.getUniqueId())) return;
        e.setCancelled(true);
        openRiddle(e.getPlayer(), le);
    }

    // =============================================================================================== listeners

    @EventHandler(priority = EventPriority.HIGH)
    public void onLeftClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_AIR) return;
        Player p = e.getPlayer();
        if (heldSpade(p) == null) return;
        Chain c = chains.get(p.getUniqueId());
        if (c == null || c.current() == null || !active()) return;
        if (near(p, c.current())) { e.setCancelled(true); dig(p, c); }
    }

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || e.def == null || !"mythological".equals(e.def.familyId())) return;
        PlayerData pd = mod.data(e.killer);
        if (pd != null) pd.mythKills++;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { chains.remove(e.getPlayer().getUniqueId()); }

    Chain chain(UUID u) { return chains.get(u); }

    // =============================================================================================== menu

    /** /mythology : the huntress's shop and the ritual guide. */
    void open(Player p) {
        Menu m = new Menu("Mythological Ritual", 5).chrome();
        boolean on = active();
        int r = rarity(p);
        String spade = heldSpade(p);
        EventData.Myth my = mod.data().myth;
        m.set(4, Icons.of(Material.GOLDEN_SHOVEL, "&2Mythological Ritual",
                "&7While the huntress governs, dig up",
                "&eGriffin Burrows &7across the Hub.",
                "",
                "&71. Buy a &fGriffin &7and an &9Ancestral Spade&7.",
                "&72. Summon the Griffin.",
                "&73. Right-click the spade to sense a chain.",
                "&74. Follow the trail and click the burrow.",
                "",
                "&7The first burrow of a chain is treasure;",
                "&7after that &c" + Math.round(mod.dials().mythMobChance.get() * 100) + "% &7hold a creature.",
                "",
                on ? "&aThe ritual is running!" : "&cNot running: the &aPets &cmayor must hold the ritual perk."));
        int slot = 11;
        for (EventData.Offer o : my.shop) {
            if (!Goods.available(o)) continue;
            m.set(slot, ShopUi.offerIcon(p, o, 1.0, on ? null : "&cOnly sold during the ritual."),
                    c -> { if (!active()) Msg.error(c.player(), "The huntress is not selling right now."); else ShopUi.buy(c.player(), o, 1.0, "mythological ritual"); open(c.player()); });
            slot += 2;
        }
        List<String> sp = new ArrayList<>();
        sp.add("&7Burrows per chain:");
        for (String s : MythRules.SPADES) {
            String nm = Sky.items().exists(s) ? Goods.displayName(s) : s;
            sp.add("&8 - " + nm + "&7: &a" + MythRules.chainLength(my, s, false) + " &8(+" + my.eruditeBonus + " Erudite)");
        }
        sp.add("");
        sp.add(spade == null ? "&7Hold a spade to see your chain." : "&7Your spade: &a" + MythRules.chainLength(my, spade, erudite(p)) + " burrows");
        m.set(15, Icons.of(Material.IRON_SHOVEL, "&9Spades", sp));
        List<String> you = new ArrayList<>();
        you.add(r < 0 ? "&7Griffin: &cnone out" : "&7Griffin variant: &a" + MythMechanics.VARIANT_NAMES[Math.max(0, Math.min(5, r))].trim());
        you.add("&7Tracking on burrows: &a" + Text.commas(Math.round(tracking(p) * 10) / 10.0));
        you.add("");
        if (r >= 0) {
            you.add("&7Creatures you can meet:");
            Map<String, Integer> pool = MythRules.pool(my, r, tracking(p));
            int total = 0;
            for (int w : pool.values()) total += w;
            for (Map.Entry<String, Integer> e : pool.entrySet()) {
                MobDef d = Sky.mobs().def(mobId(e.getKey(), MythRules.variant(r)));
                you.add("&8 - &2" + (d == null ? e.getKey() : d.name) + " &7" + (total == 0 ? "0" : String.format("%.1f", 100.0 * e.getValue() / total)) + "%");
            }
        }
        m.set(22, Icons.of(Material.FEATHER, "&fYour Griffin", you));
        PlayerData pd = mod.data(p);
        Chain c = chains.get(p.getUniqueId());
        m.set(31, Icons.of(Material.BOOK, "&aYour Ritual", "&7Burrows dug: &e" + (pd == null ? 0 : pd.burrowsDug),
                "&7Creatures slain: &e" + (pd == null ? 0 : pd.mythKills),
                c == null ? "&7No chain in progress." : "&7Chain: &a" + c.next + "/" + c.burrows.size()));
        m.set(40, Icons.back("Festivals & Events"), cl -> EventBoard.open(cl.player()));
        m.open(p);
    }

    List<String> sidebar(Player p) {
        Chain c = chains.get(p.getUniqueId());
        if (c == null || !active()) return List.of();
        return List.of("&2Griffin Burrows", " &7Chain: &a" + c.next + "/" + c.burrows.size());
    }
}
