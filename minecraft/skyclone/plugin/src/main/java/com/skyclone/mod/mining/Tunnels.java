package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The frozen tunnels (reference: Glacite Tunnels, research 2.13): Cold, the mineshaft pity counter,
 * instanced mineshafts with variants and Frozen Corpses, the exit reward, Mineshaft Mayhem, and the
 * Portable Campfire.
 */
final class Tunnels implements Listener {

    static final List<String> VARIANTS = List.of("TITANIUM", "TUNGSTEN", "UMBER", "RUBY", "SAPPHIRE", "AMBER", "JADE", "AMETHYST",
            "TOPAZ", "JASPER", "OPAL", "ONYX", "AQUAMARINE", "CITRINE", "PERIDOT", "FAIRY");

    static final class Session {
        String variant;
        String key;
        int corpses;
        int scraps;
        int coldPeak;
        String mayhem;
        final List<ArmorStand> stands = new ArrayList<>();
        /** Rimestone guardians standing over the corpses (owned by the player; removed with the run). */
        final List<org.bukkit.entity.LivingEntity> guardians = new ArrayList<>();
        final Map<UUID, String> corpseType = new HashMap<>();
    }

    private final MiningModule m;
    private final Map<UUID, Integer> cold = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> blocksForCold = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pending = new ConcurrentHashMap<>();      // found, not yet entered
    private final Map<UUID, String> pendingVariant = new ConcurrentHashMap<>();
    private final Map<UUID, Long> campfireAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> chillAt = new ConcurrentHashMap<>();
    private int tick;

    /** Frozen corpses this player has looted in the mineshaft they are in now (0 outside one). */
    int corpsesThisRun(Player p) {
        Session s = sessions.get(p.getUniqueId());
        return s == null ? 0 : s.corpses;
    }

    Tunnels(MiningModule m) {
        this.m = m;
        IslandDef def = IslandDef.of("mineshaft", "Rimestone Mineshaft", new MineshaftGenerator());
        def.instanced = true;
        def.instanceSpacing = 256;
        def.fixedTime = 18000;
        Sky.worlds().registerIsland(def);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::second, 20L, 20L);
    }

    // ---- cold -----------------------------------------------------------------------------------

    int cold(Player p) { return cold.getOrDefault(p.getUniqueId(), 0); }

    /** Admin/testing: set a player's Cold (0-100). */
    void setCold(Player p, int n) { int v = Math.max(0, Math.min(100, n)); if (v == 0) cold.remove(p.getUniqueId()); else cold.put(p.getUniqueId(), v); }

    void warm(Player p, int n) { cold.computeIfPresent(p.getUniqueId(), (k, v) -> Math.max(0, v - n)); }

    private boolean freezing(Player p) {
        String i = MiningUtil.island(p);
        if ("mineshaft".equals(i)) return true;
        if (!"glacite_tunnels".equals(i)) return false;
        Layout.Zone z = Sky.worlds().zoneOf(p);
        if (z != null && (z.id() + z.displayName()).toLowerCase().contains("camp")) return false;
        Box camp = Sky.worlds().area("glacite_tunnels", "base_camp");
        Location l = p.getLocation();
        if (camp != null && camp.grow(4).contains(l.getX(), l.getY(), l.getZ())) return false;
        Location spawn = Sky.worlds().anchor("glacite_tunnels", "spawn");
        return spawn == null || spawn.getWorld() != l.getWorld() || spawn.distanceSquared(l) > 20 * 20;
    }

    private void addCold(Player p, int n) {
        double res = Sky.stats().get(p, Stat.COLD_RESISTANCE);
        if (MiningUtil.rnd().nextDouble() < Math.min(0.9, res / 100.0)) return;
        int before = cold(p);
        int after = Math.min(100, before + n);
        cold.put(p.getUniqueId(), after);
        Session s = sessions.get(p.getUniqueId());
        if (s != null) s.coldPeak = Math.max(s.coldPeak, after);
        if (before < 25 && after >= 25) Msg.send(p, "&bBRRR! It's getting really cold in here!");
        if (before < 50 && after >= 50) Msg.send(p, "&3Your fingers are going numb. &7(slower mining)");
        if (before < 75 && after >= 75) Msg.send(p, "&3&lYou can barely move! &7Warm up at the Base Camp.");
        if (before < 90 && after >= 90) Msg.send(p, "&c&lYou are about to freeze solid!");
        if (after >= 100) freeze(p);
    }

    private void freeze(Player p) {
        cold.remove(p.getUniqueId());
        Msg.send(p, "&b&lYou froze! &7You were dragged back to the Base Camp.");
        if (sessions.containsKey(p.getUniqueId())) leave(p, true);
        else backToCamp(p);
    }

    double speedMultiplier(Player p) {
        // continuous, not stepped (wiki: -0.5% Mining Speed per Cold, so -25% at 50; audit 2026-10-06 M11)
        return MiningMath.coldSpeedMultiplier(cold(p), m.dials.coldSpeedPerPoint.get());
    }

    private void second() {
        tick++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!MiningUtil.ready(p)) continue;
            UUID u = p.getUniqueId();
            if (freezing(p)) {
                if (tick % Math.max(1, m.dials.coldSeconds.get()) == 0) addCold(p, 1);
                int c = cold(p);
                if (c >= 50) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, c >= 75 ? 1 : 0, true, false, false));
            } else if (cold.containsKey(u)) {
                warm(p, 5);
                if (cold(p) <= 0) cold.remove(u);
            }
            Long until = pending.get(u);
            if (until != null && until < System.currentTimeMillis()) { pending.remove(u); pendingVariant.remove(u); }
        }
    }

    // ---- mining hook --------------------------------------------------------------------------

    void onBlock(Player p, BlockTable.Def def, String island) {
        if (!"glacite_tunnels".equals(island) && !"mineshaft".equals(island)) return;
        int n = blocksForCold.merge(p.getUniqueId(), 1, Integer::sum);
        if (n >= m.dials.coldBlocks.get()) { blocksForCold.put(p.getUniqueId(), 0); addCold(p, 1); }
        if (!"glacite_tunnels".equals(island) || !m.dials.mineshafts.get() || pending.containsKey(p.getUniqueId())) return;
        MiningData d = MiningUtil.data(p);
        d.mineshaftPity -= Math.max(1, def.quality);
        double chance = 1.0 / Math.max(1, m.dials.mineshaftPity.get());
        double pct = m.hotm.value(p, "surveyor") + m.abilities.rareBoost(p) + (m.hotm.coreLevel(p) >= 9 ? 10 : 0);
        chance *= 1 + pct / 100.0;
        if (d.mineshaftPity <= 0 || MiningUtil.rnd().nextDouble() < chance) found(p, variantFor(p, def));
    }

    String variantFor(Player p, BlockTable.Def last) {
        String type = last.gem != null ? last.gem : last.isTitanium() ? "TITANIUM" : "umber".equals(last.id) ? "UMBER" : "tungsten".equals(last.id) ? "TUNGSTEN" : null;
        double crystalline = m.hotm.value(p, "crystalline");
        double vanguard = m.hotm.value(p, "vanguard_seeker");
        var r = MiningUtil.rnd();
        if (r.nextDouble() < (1.0 / VARIANTS.size()) * (1 + vanguard / 100.0)) return "FAIRY";
        if (type != null && r.nextDouble() < 1.0 / 3.0) return type;            // research: matching variant 1/3
        if (crystalline > 0 && r.nextDouble() < crystalline / 100.0) {
            List<String> gems = VARIANTS.subList(3, 15);
            return gems.get(r.nextInt(gems.size()));
        }
        return VARIANTS.get(r.nextInt(VARIANTS.size() - 1));
    }

    void found(Player p, String variant) {
        MiningData d = MiningUtil.data(p);
        d.mineshaftPity = m.dials.mineshaftPity.get();
        d.mineshaftsFound++;
        pending.put(p.getUniqueId(), System.currentTimeMillis() + 60_000L);
        pendingVariant.put(p.getUniqueId(), variant);
        m.comms.progress(p, "MINESHAFT", "", 1, null);
        p.showTitle(net.kyori.adventure.title.Title.title(Text.c("&b&lMINESHAFT!"), Text.c("&7A " + Text.titleCase(variant) + " mineshaft opened up")));
        Msg.sound(p, Sound.BLOCK_END_PORTAL_SPAWN, 1.6f);
        p.sendMessage(Text.c("&b&lWOW! &7You found a &b" + Text.titleCase(variant) + " Mineshaft&7! &e&l[CLICK TO ENTER]")
                .clickEvent(ClickEvent.runCommand("/mineshaft enter")));
        Msg.send(p, "&8(or type /mineshaft enter within 60 seconds)");
    }

    // ---- entering and leaving -------------------------------------------------------------------

    void enter(Player p) {
        UUID u = p.getUniqueId();
        if (sessions.containsKey(u)) { Msg.error(p, "You are already inside a mineshaft."); return; }
        if (!pending.containsKey(u)) { Msg.error(p, "You haven't found a mineshaft. Keep mining the frozen tunnels!"); return; }
        String variant = pendingVariant.getOrDefault(u, "TITANIUM");
        pending.remove(u);
        pendingVariant.remove(u);
        String key = "ms_" + u.toString().replace("-", "").substring(0, 12);
        Msg.send(p, "&7Opening the mineshaft...");
        Sky.worlds().ensureInstance("mineshaft", key).whenComplete((spawn, err) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            if (err != null || spawn == null) { Msg.error(p, "The mineshaft collapsed before you could get in (it could not be built)."); return; }
            Session s = new Session();
            s.variant = variant;
            s.key = key;
            sessions.put(u, s);
            refill(key, variant);
            spawnCorpses(p, s);
            spawnGuardians(p, s);
            Location at = Sky.worlds().instanceAnchor("mineshaft", key, "spawn");
            p.teleport(at != null ? at : spawn);
            if (m.hotm.has(p, "mineshaft_mayhem")) {
                String[] buffs = {"scrap", "fortune", "speed", "cold", "cooldown"};
                s.mayhem = buffs[MiningUtil.rnd().nextInt(buffs.length)];
                Msg.send(p, "&5Mineshaft Mayhem: &a" + mayhemText(s.mayhem));
            }
            Msg.box(p, "&b&l" + Text.titleCase(variant).toUpperCase() + " MINESHAFT", List.of("&7Mine the veins, loot the frozen corpses,", "&7then climb the &fladder &7at the start to leave.", "&7Frozen corpses here: &a" + s.stands.size()));
            Sky.stats().invalidate(p);
        }));
    }

    static String mayhemText(String b) {
        return switch (b) {
            case "scrap" -> "+5% Suspicious Scrap chance";
            case "fortune" -> "+100 Mining Fortune";
            case "speed" -> "+200 Mining Speed";
            case "cold" -> "+10 Cold Resistance";
            default -> "-25% Pickaxe Ability cooldown";
        };
    }

    /** Fill every vein pocket of this instance with the variant's blocks. */
    private void refill(String key, String variant) {
        Layout lay = Sky.worlds().layout("mineshaft");
        Location origin = Sky.worlds().instanceOrigin("mineshaft", key);
        if (lay == null || origin == null) return;
        World w = origin.getWorld();
        var r = MiningUtil.rnd();
        for (Map.Entry<String, Box> e : lay.areas().entrySet()) {
            if (!e.getKey().startsWith("vein.")) continue;
            Box b = e.getValue();
            for (int x = b.minX(); x <= b.maxX(); x++)
                for (int y = b.minY(); y <= b.maxY(); y++)
                    for (int z = b.minZ(); z <= b.maxZ(); z++) {
                        double roll = r.nextDouble();
                        Material mat = roll < 0.6 ? variantBlock(variant, r) : roll < 0.85 ? Material.PACKED_ICE : Material.STONE;
                        w.getBlockAt(origin.getBlockX() + x, origin.getBlockY() + y, origin.getBlockZ() + z).setType(mat, false);
                    }
        }
    }

    static Material variantBlock(String v, java.util.Random r) {
        return switch (v) {
            case "TITANIUM" -> Material.POLISHED_DIORITE;
            case "TUNGSTEN" -> Material.CLAY;
            case "UMBER" -> Material.TERRACOTTA;
            case "RUBY" -> Material.RED_STAINED_GLASS;
            case "SAPPHIRE" -> Material.LIGHT_BLUE_STAINED_GLASS;
            case "AMBER" -> Material.ORANGE_STAINED_GLASS;
            case "JADE" -> Material.LIME_STAINED_GLASS;
            case "AMETHYST" -> Material.PURPLE_STAINED_GLASS;
            case "TOPAZ" -> Material.YELLOW_STAINED_GLASS;
            case "JASPER" -> Material.MAGENTA_STAINED_GLASS;
            case "OPAL" -> Material.WHITE_STAINED_GLASS;
            case "ONYX" -> Material.BLACK_STAINED_GLASS;
            case "AQUAMARINE" -> Material.BLUE_STAINED_GLASS;
            case "CITRINE" -> Material.BROWN_STAINED_GLASS;
            case "PERIDOT" -> Material.GREEN_STAINED_GLASS;
            default -> {   // FAIRY: a mix of everything shiny
                Material[] mix = {Material.GOLD_BLOCK, Material.RED_STAINED_GLASS, Material.BLUE_STAINED_GLASS, Material.BLACK_STAINED_GLASS,
                        Material.GREEN_STAINED_GLASS, Material.BROWN_STAINED_GLASS, Material.POLISHED_DIORITE};
                yield mix[r.nextInt(mix.length)];
            }
        };
    }

    private void spawnCorpses(Player p, Session s) {
        int count = 1 + MiningUtil.rnd().nextInt(3);
        if (MiningUtil.rnd().nextDouble() * 100 < m.hotm.value(p, "dead_mans_chest")) count++;
        List<String> types = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double r = MiningUtil.rnd().nextDouble();
            types.add("FAIRY".equals(s.variant) && i == 0 ? "vanguard" : r < 0.5 ? "lapis" : r < 0.75 ? "umber" : "tungsten");
        }
        for (int i = 0; i < types.size(); i++) {
            Location at = Sky.worlds().instanceAnchor("mineshaft", s.key, "corpse." + (i + 1));
            if (at == null) break;
            String type = types.get(i);
            ArmorStand st = at.getWorld().spawn(at, ArmorStand.class, a -> {
                a.setPersistent(false);
                a.setInvulnerable(true);
                a.setGravity(false);
                a.setArms(true);
                a.setBasePlate(false);
                a.customName(Text.c(corpseName(type)));
                a.setCustomNameVisible(true);
                a.getEquipment().setHelmet(new ItemStack(Material.SKELETON_SKULL));
                a.getEquipment().setChestplate(dyed(Material.LEATHER_CHESTPLATE, type));
                a.getEquipment().setLeggings(dyed(Material.LEATHER_LEGGINGS, type));
                a.getEquipment().setBoots(dyed(Material.LEATHER_BOOTS, type));
                // accessories request 16a: Organ Donor matches this tag before falling back to the name
                a.getPersistentDataContainer().set(corpseKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            });
            s.stands.add(st);
            s.corpseType.put(st.getUniqueId(), type);
        }
    }

    /** Hollows' Rimestone guardians, 1..dial of them, each standing at a corpse (hollows request 7). */
    static final List<String> GUARDIANS = List.of("gt_glacite_bowman", "gt_glacite_mage", "gt_glacite_caver", "gt_glacite_mutt");

    private void spawnGuardians(Player p, Session s) {
        int max = m.dials.mineshaftGuardians.get();
        if (max <= 0) return;
        List<String> ids = new ArrayList<>();
        for (String id : GUARDIANS) if (Sky.mobs().def(id) != null) ids.add(id);
        if (ids.isEmpty()) return;
        int n = 1 + MiningUtil.rnd().nextInt(max);
        for (int i = 0; i < n; i++) {
            Location at = Sky.worlds().instanceAnchor("mineshaft", s.key, "corpse." + (i + 1));
            if (at == null) break;
            try {
                org.bukkit.entity.LivingEntity le = Sky.mobs().spawn(ids.get(MiningUtil.rnd().nextInt(ids.size())), at.clone().add(1.5, 0, 0), p);
                if (le != null) s.guardians.add(le);
            } catch (RuntimeException ex) {
                Sky.log().warning("[mining] mineshaft guardian: " + ex);
            }
        }
        if (!s.guardians.isEmpty()) Msg.send(p, "&3Something stirs over the frozen dead... &7(" + s.guardians.size() + " guardian" + (s.guardians.size() == 1 ? "" : "s") + ")");
    }

    private static void cleanup(Session s) {
        for (ArmorStand st : s.stands) if (st.isValid()) st.remove();
        for (org.bukkit.entity.LivingEntity g : s.guardians) if (g.isValid()) g.remove();
        s.guardians.clear();
    }

    /**
     * Mob hits chill you inside a frozen mineshaft: +{@code mining.cold-per-hit} Cold per hit (at most once a second),
     * unless the Mammoth pet's "Woolly Coat" shrugs it off (its % chance). Cold Resistance still applies (addCold).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(com.skyclone.api.event.SkyEvents.Damage e) {
        Player p = e.ctx.playerVictim();
        if (p == null || e.ctx.attacker == null || e.ctx.attacker instanceof Player || !sessions.containsKey(p.getUniqueId())) return;
        if (!"mineshaft".equals(MiningUtil.island(p)) || Sky.mobs().defOf(e.ctx.attacker) == null) return;
        int per = m.dials.coldPerHit.get();
        if (per <= 0) return;
        long now = System.currentTimeMillis();
        Long last = chillAt.get(p.getUniqueId());
        if (last != null && now - last < 1000L) return;
        chillAt.put(p.getUniqueId(), now);
        if (MiningUtil.rnd().nextDouble() * 100 < MiningUtil.pet(p, "mammoth_wooly_coat")) return;
        addCold(p, per);
    }

    /**
     * Penguin "Thick Blubber" (pets marks it info-only, mining owns Cold): each sea creature you catch lowers your
     * Cold by the ability's value. Only does anything while you have Cold, i.e. in the frozen tunnels / a mineshaft.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSeaCreature(com.skyclone.api.event.SkyEvents.FishCaught e) {
        if (!e.seaCreature || e.player == null || cold(e.player) <= 0) return;
        int n = (int) Math.round(MiningUtil.pet(e.player, "penguin_thick_blubber"));
        if (n <= 0) return;
        warm(e.player, n);
        Msg.send(e.player, "&bThick Blubber&7: -" + n + " Cold");
    }

    private static org.bukkit.NamespacedKey corpseKey;

    /** {@code skyclone:frozen_corpse} (PDC byte 1 on every unlooted Frozen Corpse stand). */
    static org.bukkit.NamespacedKey corpseKey() {
        if (corpseKey == null) corpseKey = new org.bukkit.NamespacedKey(Sky.plugin(), "frozen_corpse");
        return corpseKey;
    }

    static String corpseName(String type) {
        return switch (type) {
            case "umber" -> "&6Frozen Umber Corpse";
            case "tungsten" -> "&8Frozen Tungsten Corpse";
            case "vanguard" -> "&dFrozen Vanguard Corpse";
            default -> "&9Frozen Lapis Corpse";
        };
    }

    private static ItemStack dyed(Material m, String type) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) {
            lm.setColor(switch (type) {
                case "umber" -> Color.fromRGB(0x8B5A2B);
                case "tungsten" -> Color.fromRGB(0x505050);
                case "vanguard" -> Color.fromRGB(0xC060E0);
                default -> Color.fromRGB(0x2040C0);
            });
            it.setItemMeta(lm);
        }
        return it;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onCorpse(PlayerInteractAtEntityEvent e) {
        if (!(e.getRightClicked() instanceof ArmorStand st) || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        String type = s == null ? null : s.corpseType.get(st.getUniqueId());
        if (type == null) {
            for (Session o : sessions.values()) if (o.corpseType.containsKey(st.getUniqueId())) { e.setCancelled(true); Msg.error(p, "That corpse is someone else's to loot."); return; }
            return;
        }
        e.setCancelled(true);
        MiningLoot.Table t = m.loot.f.corpse.get(type);
        if (t == null) return;
        if (t.key != null && Sky.items().remove(p, t.key, 1) < 1) { Msg.error(p, "You need a " + Text.strip(MiningUtil.itemName(t.key)) + " to open this corpse."); return; }
        // per-corpse Rimestone (Glacite) powder + HOTM XP from the table (Lapis 500 + 100 XP, keyed 5k / 25k: audit M12)
        List<String> got = m.loot.rollTable(p, t, null, "GLACITE", LootSource.MINING);
        if (MiningUtil.rnd().nextDouble() * 100 < m.hotm.value(p, "gifts_from_the_departed") && !t.table.isEmpty())
            got.add(m.loot.give(p, m.loot.pick(t.table), null, LootSource.MINING) + " &d(Gift from the Departed)");
        s.corpseType.remove(st.getUniqueId());
        s.corpses++;
        Sky.stats().invalidate(p);   // Glacite Golem "Frozen Perfection" scales with corpses looted this run
        st.remove();
        MiningData d = MiningUtil.data(p);
        d.corpsesLooted++;
        m.comms.progress(p, "CORPSE", "", 1, null);
        Msg.box(p, corpseName(type) + " &7looted!", got);
        Msg.sound(p, Sound.BLOCK_POWDER_SNOW_BREAK, 0.8f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null || e.getFrom().getBlockY() == e.getTo().getBlockY() && e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || !"mineshaft".equals(MiningUtil.island(e.getTo()))) return;
        Location origin = Sky.worlds().instanceOrigin("mineshaft", s.key);
        Box exit = Sky.worlds().area("mineshaft", "exit");
        if (origin == null || exit == null) return;
        Location t = e.getTo();
        if (exit.contains(t.getX() - origin.getX(), t.getY() - origin.getY(), t.getZ() - origin.getZ()) && t.getY() - origin.getY() > MineshaftGenerator.FLOOR + 2.5)
            Bukkit.getScheduler().runTask(Sky.plugin(), () -> leave(e.getPlayer(), false));
    }

    /** Dying in a mineshaft ends the run (no exit reward) and wakes you at the Base Camp. */
    @EventHandler
    public void onDeath(com.skyclone.api.event.SkyEvents.SkyDeath e) {
        Session s = sessions.remove(e.player.getUniqueId());
        if (s == null) return;
        cleanup(s);
        cold.remove(e.player.getUniqueId());
        Location spawn = Sky.worlds().anchor("glacite_tunnels", "spawn");
        if (spawn != null && Sky.worlds().isBuilt("glacite_tunnels")) e.respawnAt = spawn;
        Msg.send(e.player, "&cThe mineshaft collapsed behind you. No exit reward this time.");
    }

    /**
     * A run does not survive a disconnect or a restart (sessions live in memory). Anyone who wakes up inside
     * the mineshaft world without a run would be stuck there - the exit only works for a live run - so they
     * are taken back to the Base Camp.
     */
    @EventHandler
    public void onReady(com.skyclone.api.event.SkyEvents.ProfileReady e) {
        Player p = e.player;
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (p.isOnline() && "mineshaft".equals(MiningUtil.island(p)) && !sessions.containsKey(p.getUniqueId())) {
                Msg.send(p, "&7The mineshaft caved in while you were away. You climb back to the Base Camp.");
                backToCamp(p);
            }
        }, 20L);
    }

    /** Leaving the mineshaft world by any other way (warp, scroll) still pays the exit reward. */
    @EventHandler
    public void onIsland(com.skyclone.api.event.SkyEvents.IslandChange e) {
        if ("mineshaft".equals(e.from) && !"mineshaft".equals(e.to) && sessions.containsKey(e.player.getUniqueId()))
            leave(e.player, false, false);
    }

    void scrapFound(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null) s.scraps++;
    }

    /** Leave the mineshaft, paid by the exit formula (research 2.13). */
    void leave(Player p, boolean froze) { leave(p, froze, true); }

    void leave(Player p, boolean froze, boolean teleport) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) { if (teleport) Msg.error(p, "You are not in a mineshaft."); return; }
        cleanup(s);
        long powder = MiningMath.mineshaftExitPowder(s.scraps, s.corpses, s.coldPeak);
        long got = m.hotm.grant(p, "GLACITE", powder, "mineshaft exit", true);
        if (teleport) backToCamp(p);
        Sky.stats().invalidate(p);
        Msg.box(p, "&b&lMINESHAFT " + (froze ? "&c(frozen out)" : "&acomplete"), List.of(
                "&7Corpses looted: &a" + s.corpses, "&7Scrap found: &a" + s.scraps, "&7Cold endured: &b" + s.coldPeak,
                "&b+" + Text.commas(got) + " Rimestone Powder"));
    }

    static void backToCamp(Player p) {
        if (Sky.worlds().warps().contains("glacite_tunnels") && Sky.worlds().warp(p, "glacite_tunnels")) return;
        Location spawn = Sky.worlds().anchor("glacite_tunnels", "spawn");
        if (spawn != null && Sky.worlds().isBuilt("glacite_tunnels")) p.teleport(spawn); else Sky.worlds().sendHome(p);
    }

    boolean inShaft(Player p) { return sessions.containsKey(p.getUniqueId()); }

    void quit(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s != null) cleanup(s);
        cold.remove(p.getUniqueId());
        chillAt.remove(p.getUniqueId());
        pending.remove(p.getUniqueId());
    }

    // ---- Mineshaft Mayhem -------------------------------------------------------------------------

    private String mayhem(Player p) {
        Session s = sessions.get(p.getUniqueId());
        return s == null ? null : s.mayhem;
    }

    void mayhem(Player p, StatMap s) {
        String b = mayhem(p);
        if (b == null) return;
        switch (b) {
            case "fortune" -> s.add(Stat.MINING_FORTUNE, 100);
            case "speed" -> s.add(Stat.MINING_SPEED, 200);
            case "cold" -> s.add(Stat.COLD_RESISTANCE, 10);
            default -> { }
        }
    }

    double mayhemScrap(Player p) { return "scrap".equals(mayhem(p)) ? 5 : 0; }

    double mayhemCooldownCut(Player p) { return "cooldown".equals(mayhem(p)) ? 25 : 0; }

    // ---- Portable Campfire -------------------------------------------------------------------------

    boolean campfire(Player p) {
        String i = MiningUtil.island(p);
        if (!"glacite_tunnels".equals(i) && !"mineshaft".equals(i)) { Msg.error(p, "Light it in the frozen tunnels."); return false; }
        long now = System.currentTimeMillis();
        Long last = campfireAt.get(p.getUniqueId());
        if (last != null && now - last < 5 * 60_000L) { Msg.error(p, "You can light another campfire in " + Text.duration((5 * 60_000L - (now - last)) / 1000) + "."); return false; }
        campfireAt.put(p.getUniqueId(), now);
        warm(p, 50);
        p.getWorld().spawnParticle(org.bukkit.Particle.CAMPFIRE_COSY_SMOKE, p.getLocation(), 10, 0.3, 0.5, 0.3, 0.01);
        Msg.success(p, "You warm your hands by the fire. -50 Cold.");
        return true;
    }

    int sessionCount() { return sessions.size(); }

    void shutdown() {
        for (UUID u : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) leave(p, false); else quitOffline(u);
        }
    }

    private void quitOffline(UUID u) {
        Session s = sessions.remove(u);
        if (s != null) cleanup(s);
    }

    /** Admin: open a mineshaft for a player right now. */
    void forceFind(Player p, String variant) { found(p, variant == null ? VARIANTS.get(MiningUtil.rnd().nextInt(VARIANTS.size())) : variant); }
}
