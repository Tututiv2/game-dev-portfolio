package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rare things that happen while mining: treasure goblins, fallen stars and powder ghasts in the Dwarven
 * Mines (Cave Luck / Tunnel Vision), treasure chests with the lockpick game, worms and crystals in the
 * crystal caverns, Suspicious Scrap in the frozen tunnels, and the event drops (Raffle, Mithril Feast).
 */
final class Finds implements Listener {

    static final class Chest {
        UUID owner;
        Block block;
        int locks;
        BlockFace face;
        long until;
        String gem;
    }

    private static final BlockFace[] FACES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP};
    private final MiningModule m;
    private final Map<Regen.Key, Chest> chests = new HashMap<>();
    private final Map<UUID, Long> spillAt = new HashMap<>();
    private final Map<UUID, Long> kineticAt = new HashMap<>();
    private final Map<UUID, Long> wormAt = new HashMap<>();

    Finds(MiningModule m) {
        this.m = m;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::chestTick, 10L, 10L);
    }

    double rareMult(Player p, String island) {
        double pct = m.abilities.rareBoost(p);
        if ("dwarven_mines".equals(island)) pct += m.hotm.value(p, "luck_of_the_cave");
        return 1 + pct / 100.0;
    }

    static String zoneGem(Location l) {
        Layout.Zone z = Sky.worlds().zoneAt(l);
        String s = z == null ? "" : (z.id() + " " + z.displayName()).toLowerCase();
        if (s.contains("jungle")) return "AMETHYST";
        if (s.contains("mithril")) return "JADE";
        if (s.contains("precursor")) return "SAPPHIRE";
        if (s.contains("goblin")) return "AMBER";
        if (s.contains("magma")) return "TOPAZ";
        if (s.contains("fairy") || s.contains("grotto")) return "JASPER";
        return "RUBY";
    }

    void onBlock(Player p, BlockTable.Def def, Block b, String island) {
        if (island == null) return;
        var r = MiningUtil.rnd();
        double mult = rareMult(p, island);
        switch (island) {
            case "dwarven_mines" -> {
                if (r.nextDouble() < goblinChance(p, mult)) goblin(p, r.nextDouble() < m.dials.diamondGoblinRatio.get());
                if (r.nextDouble() < m.dials.starChance.get() * mult) m.events.fallenStar(p);
                if (r.nextDouble() < m.dials.ghastChance.get() * mult) m.events.powderGhast(p);
                if (r.nextDouble() < m.dials.raffleChance.get()) m.events.raffleTicket(p);
                if (def.isMithril() && r.nextDouble() < m.dials.gourmandChance.get()) m.events.giveMithrilFeast(p);
            }
            case "crystal_hollows" -> {
                if (def.kind == BlockTable.Kind.HARD_STONE) {
                    // Great Explorer + the Tunnel Wyrm pet's "Tunnel Luck" (+X% chests)
                    double c = m.dials.chestChance.get() * (1 + (chestBonus(p) + MiningUtil.pet(p, "scatha_burrowing")) / 100.0);
                    if (r.nextDouble() < c) chest(p, b);
                }
                // worms: Hard Stone only, 1/600, one per 30 s per player (wiki /Scatha; audit 2026-10-06 M5/M17)
                if (def.kind == BlockTable.Kind.HARD_STONE && wormReady(p.getUniqueId(), System.currentTimeMillis())) {
                    if (r.nextDouble() < wormChance(p, mult)) {
                        wormAt.put(p.getUniqueId(), System.currentTimeMillis());
                        worm(p, b, r.nextDouble() < m.dials.scathaRatio.get());
                    }
                }
                if (def.kind == BlockTable.Kind.GEMSTONE && def.gem != null && r.nextDouble() < m.dials.crystalChance.get() * mult) crystal(p, def.gem);
                if ("JUNGLE_PICKAXE".equals(MiningUtil.heldId(p)) && "AMETHYST".equals(zoneGem(b.getLocation())) && r.nextDouble() < 0.01) {
                    Sky.loot().give(p, "SLUDGE_JUICE", 1, LootSource.MINING);
                }
            }
            case "glacite_tunnels", "mineshaft" -> {
                double c = m.dials.scrapChance.get();
                if ("mineshaft".equals(island)) c *= 1 + (m.hotm.value(p, "no_stone_unturned") + m.tunnels.mayhemScrap(p) + paleontologistPct(p)) / 100.0;
                if (r.nextDouble() < c) {
                    Sky.loot().give(p, "SUSPICIOUS_SCRAP", 1, LootSource.MINING);
                    MiningData d = MiningUtil.data(p);
                    d.scrapsFound++;
                    m.tunnels.scrapFound(p);
                    m.comms.progress(p, "SCRAP", "", 1, null);
                    Msg.rareDrop(p, "&9&lRARE FIND!", MiningUtil.itemName("SUSPICIOUS_SCRAP"), 0);
                }
            }
            default -> { }
        }
    }

    /**
     * Per-block treasure goblin chance in the Dwarven Mines: base x Cave Luck / Tunnel Vision x Sky Mall (x10) x
     * the held tool's <b>Ambered</b> reforge (+10..50% by rarity, read off the item's reforge data).
     */
    double goblinChance(Player p, double rareMult) {
        double base = m.dials.goblinChance.get();
        double mult = rareMult * (m.skyMall.is(p, SkyMall.Buff.GOBLINS) ? 10 : 1);
        double pct = ReforgeFinds.amberedPct(p);
        double paid = m.dials.reforgesExtraRoll.get() ? MiningMath.reforgesExtraRoll(base, pct) : 0;
        return MiningMath.findChance(base, mult, pct, paid);
    }

    /**
     * Per-block cave worm chance on Hard Stone in the crystal caverns (30 s cooldown applied by the caller): base x Cave Luck / Tunnel Vision x
     * <b>Blazing</b> (every worn Blazing piece: +0.05% more likely per 1 Heat, read as relative to the base chance,
     * as the reforges module words it).
     */
    double wormChance(Player p, double rareMult) {
        double base = m.dials.wormChance.get();
        int heat = m.hollows.heat(p);
        double pct = heat <= 0 ? 0 : ReforgeFinds.blazingPctPerHeat(p) * heat;
        double paid = m.dials.reforgesExtraRoll.get() ? MiningMath.reforgesExtraRoll(base, pct) : 0;
        return MiningMath.findChance(base, rareMult, pct, paid);
    }

    /**
     * Paleontologist (pickaxe/drill/gauntlet enchant, +2.5% per level, max 12.5%: research 01) as "% more Suspicious
     * Scrap" inside mining's own mineshaft roll - but only once the enchanting module stops rolling its own fallback
     * (its dial {@code enchanting.paleontologist-fallback}), so the bonus is paid exactly once at either setting.
     */
    double paleontologistPct(Player p) {
        if (m.dials.paleontologistFallback.get()) return 0;
        org.bukkit.inventory.ItemStack h = p.getInventory().getItemInMainHand();
        if (h == null || h.getType().isAir() || Sky.items().idOf(h) == null) return 0;
        return 2.5 * Math.max(0, Math.min(5, Sky.items().data(h).enchant("paleontologist")));
    }

    double chestBonus(Player p) {
        HotmTree.Perk ge = m.hotm.tree.perk("great_explorer");
        int l = m.hotm.effective(p, "great_explorer");
        return ge == null || l <= 0 ? 0 : HotmTree.Perk.at(ge.values_chance, l);
    }

    int chestLocks(Player p) {
        HotmTree.Perk ge = m.hotm.tree.perk("great_explorer");
        int l = m.hotm.effective(p, "great_explorer");
        int cut = ge == null || l <= 0 ? 0 : (int) HotmTree.Perk.at(ge.values_locks, l);
        return Math.max(1, m.dials.chestLocks.get() - cut);
    }

    // ---- goblins, worms, crystals ---------------------------------------------------------------

    void goblin(Player p, boolean diamond) {
        String id = diamond ? "mining_diamond_goblin" : "mining_golden_goblin";
        Location at = MiningEvents.groundNear(p.getLocation(), 2, 5);
        if (at == null) at = p.getLocation();
        try {
            LivingEntity e = Sky.mobs().spawn(id, at, p);
            if (e == null) return;
        } catch (Exception ex) { return; }
        Msg.send(p, (diamond ? "&b&lDIAMOND GOBLIN! " : "&6&lGOLDEN GOBLIN! ") + "&7A treasure goblin scurries out of the rock. Catch it!");
        Msg.sound(p, Sound.ENTITY_PIGLIN_CELEBRATE, 1.4f);
    }

    void worm(Player p, Block b, boolean scatha) {
        Location at = b.getLocation().add(0.5, 0, 0.5);
        if (!at.getBlock().isPassable()) at = p.getLocation();
        try { Sky.mobs().spawn(scatha ? "mining_scatha" : "mining_worm", at, p); } catch (Exception ex) { return; }
        Msg.send(p, scatha ? "&c&lTUNNEL WYRM! &7You hear the scraping of something huge..." : "&e&lCAVE WORM! &7A worm bursts out of the rock!");
        Msg.sound(p, Sound.ENTITY_SILVERFISH_AMBIENT, 0.5f);
    }

    void crystal(Player p, String gem) {
        String id = gem + "_CRYSTAL";
        if (!MiningUtil.exists(id)) return;
        Sky.loot().give(p, id, 1, LootSource.MINING);   // counted for commissions by onCrystal (ItemGain)
        Msg.rareDrop(p, "&5&lCRYSTAL FOUND!", MiningUtil.itemName(id), 0);
    }

    /**
     * Structure quests: a crystal counts for the "Crystal Hunter" commissions and the found-crystals record however it
     * was earned - mined (a rare gem find), from a treasure chest or the Nucleus, or handed over by the hollows
     * module's structure quests (Temple Altar, the Keepers, the Professor, the Goblin King, Magmaw, the Goblin Queen).
     * Moving one around (pickup, trade, storage, sacks, admin) never counts. Listens even to a cancelled gain (that
     * only means a sack absorbed it).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCrystal(SkyEvents.ItemGain e) {
        if (e.player == null || e.itemId == null || !e.itemId.endsWith("_CRYSTAL") || !MiningUtil.ready(e.player)) return;
        if (!countsAsFound(e.source)) return;
        for (int i = 0; i < Math.max(1, Math.min(5, e.amount)); i++) m.comms.crystal(e.player, e.itemId);
    }

    /**
     * Lucky Pick (reference: Stonk): End Stone mined with it drops experience and has its {@code extra.endStoneBonus}
     * (5%) chance of one more End Stone. Reacts to any module's MINING Gather of End Stone (the end island's
     * owner fires it when it makes end stone mineable); never fires Gather itself, so nothing loops.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEndStone(SkyEvents.Gather e) {
        if (e.player == null || e.kind != LootSource.MINING || e.what == null || !MiningUtil.ready(e.player)) return;
        String w = e.what.toUpperCase(java.util.Locale.ROOT);
        if (!w.equals("END_STONE") && !w.equals("ENDER_STONE")) return;
        String held = MiningUtil.heldId(e.player);
        com.skyclone.api.item.ItemDef d = held == null ? null : Sky.items().def(held);
        if (d == null || d.extra == null || !d.extra.has("endStoneBonus")) return;
        e.player.giveExp(1 + MiningUtil.rnd().nextInt(2));
        double chance;
        try { chance = d.extra.get("endStoneBonus").getAsDouble(); } catch (RuntimeException ex) { return; }
        if (MiningUtil.rnd().nextDouble() < chance) {
            String id = MiningUtil.exists("ENDER_STONE") ? "ENDER_STONE" : "END_STONE";
            Sky.loot().give(e.player, id, 1, LootSource.MINING);
        }
    }

    static boolean countsAsFound(LootSource s) {
        return s == LootSource.MINING || s == LootSource.MOB_DROP || s == LootSource.REWARD || s == LootSource.EVENT
                || s == LootSource.DUNGEON || s == LootSource.SLAYER;
    }

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || e.def == null || !MiningUtil.ready(e.killer)) return;
        gauntletKill(e.killer);
        // Magmaw pet "Heat Sink": each kill in the crystal caverns lowers Heat
        if ("crystal_hollows".equals(MiningUtil.island(e.killer))) {
            int cool = (int) Math.round(MiningUtil.pet(e.killer, "bal_dispersion"));
            if (cool > 0) m.hollows.cool(e.killer, cool);
        }
        String key = switch (e.def.id) {
            case "mining_golden_goblin" -> "golden";
            case "mining_diamond_goblin" -> "diamond";
            default -> null;
        };
        if (key == null) return;
        MiningLoot.Goblin g = m.loot.f.goblins.get(key);
        if (g != null && g.powder != null) {
            double amt = m.loot.between(g.powder);
            long got = m.hotm.grant(e.killer, "MITHRIL", amt, "treasure goblin", true);
            Msg.send(e.killer, "&6You looted the goblin's stash: &2+" + got + " Mithril Powder&6!");
        }
    }

    /**
     * Gemstone Gauntlet "Kinetic": a kill on a mining island takes 0.5 s per Perfect gem slotted in it off every
     * running Forge timer (reference cooldown 1 s).
     */
    private void gauntletKill(Player p) {
        if (!MiningUtil.onMiningIsland(p) || !"GEMSTONE_GAUNTLET".equals(MiningUtil.heldId(p))) return;
        long now = System.currentTimeMillis();
        Long last = kineticAt.get(p.getUniqueId());
        if (last != null && now - last < 1000L) return;
        int perfect = 0;
        for (String g : Sky.items().data(p.getInventory().getItemInMainHand()).gems.values())
            if (g != null && g.startsWith("PERFECT_")) perfect++;
        if (perfect <= 0) return;
        kineticAt.put(p.getUniqueId(), now);
        long cut = 500L * perfect;
        for (MiningData.ForgeSlot fs : MiningUtil.data(p).forge) fs.end = Math.max(fs.start, fs.end - cut);
    }

    /**
     * Gemstone Gauntlet "Reduced To Atoms": a mob hit with it on a mining island spills the gems slotted in it, as
     * their rough form ({@link MiningMath#gauntletSpill}: one per quality step, x dial), at most once per
     * {@code mining.gauntlet-spill-cooldown} seconds. Only real sky mobs count (no players, NPCs or armour stands),
     * and the gems are given as a mob drop so collections and sacks see them.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGauntletHit(SkyEvents.Damage e) {
        Player p = e.ctx.playerAttacker();
        if (p == null || !(e.ctx.victim instanceof LivingEntity le) || le instanceof Player || !MiningUtil.ready(p)) return;
        if (!MiningUtil.onMiningIsland(p) || !"GEMSTONE_GAUNTLET".equals(MiningUtil.heldId(p))) return;
        if (Sky.mobs().defOf(le) == null) return;
        long now = System.currentTimeMillis();
        Long last = spillAt.get(p.getUniqueId());
        if (last != null && now - last < (long) (Math.max(0, m.dials.gauntletSpillCooldown.get()) * 1000)) return;
        Map<String, Integer> spill = gauntletSpill(Sky.items().data(p.getInventory().getItemInMainHand()).gems.values(), m.dials.gauntletSpillMult.get());
        if (spill.isEmpty()) return;
        spillAt.put(p.getUniqueId(), now);
        for (Map.Entry<String, Integer> en : spill.entrySet()) Sky.loot().give(p, en.getKey(), en.getValue(), LootSource.MOB_DROP);
        le.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, le.getLocation().add(0, 1, 0), 8, 0.3, 0.4, 0.3, 0);
        p.playSound(le.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.6f, 1.4f);
    }

    /** Rough gem id -> amount the slotted gems spill (unknown / missing ids skipped). */
    static Map<String, Integer> gauntletSpill(java.util.Collection<String> slotted, double mult) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        if (slotted == null) return out;
        for (String g : slotted) {
            String type = MiningMath.gemType(g);
            int n = MiningMath.gauntletSpill(g, mult);
            if (type == null || n <= 0) continue;
            String rough = "ROUGH_" + type + "_GEM";
            if (!MiningUtil.exists(rough)) continue;
            out.merge(rough, n, Integer::sum);
        }
        return out;
    }

    void forget(UUID u) { spillAt.remove(u); kineticAt.remove(u); wormAt.remove(u); }

    /** No worm can spawn for a player within {@code mining.worm-cooldown-seconds} of their last one. */
    boolean wormReady(UUID u, long now) {
        return MiningMath.cooldownOver(wormAt.get(u), now, m.dials.wormCooldown.get());
    }

    // ---- treasure chests ---------------------------------------------------------------------------

    void chest(Player p, Block b) {
        Regen.Key k = Regen.key(b);
        if (chests.containsKey(k)) return;
        Chest c = new Chest();
        c.owner = p.getUniqueId();
        c.block = b;
        c.locks = chestLocks(p);
        c.face = FACES[MiningUtil.rnd().nextInt(FACES.length)];
        c.until = System.currentTimeMillis() + 60_000L;
        c.gem = zoneGem(b.getLocation());
        b.setType(Material.CHEST, false);
        chests.put(k, c);
        Msg.send(p, "&6&lTREASURE! &7You uncovered a treasure chest! Right-click the &asparkling side &7to pick its &a" + c.locks + " &7locks.");
        Msg.sound(p, Sound.BLOCK_CHEST_LOCKED, 1.2f);
    }

    private void chestTick() {
        if (chests.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<Regen.Key, Chest> e : new ArrayList<>(chests.entrySet())) {
            Chest c = e.getValue();
            if (c.until < now || c.block.getType() != Material.CHEST) { remove(e.getKey(), c); continue; }
            Player p = Bukkit.getPlayer(c.owner);
            if (p == null) continue;
            Location at = c.block.getLocation().add(0.5, 0.5, 0.5).add(c.face.getDirection().multiply(0.55));
            p.spawnParticle(Particle.HAPPY_VILLAGER, at, 3, 0.1, 0.1, 0.1, 0);
        }
    }

    private void remove(Regen.Key k, Chest c) {
        chests.remove(k);
        if (c.block.getType() == Material.CHEST) c.block.setType(m.regen.isPending(c.block) ? Material.BEDROCK : Material.AIR, false);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.CHEST) return;
        Regen.Key k = Regen.key(e.getClickedBlock());
        Chest c = chests.get(k);
        if (c == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.getUniqueId().equals(c.owner)) { Msg.error(p, "This treasure chest belongs to someone else."); return; }
        if (e.getBlockFace() != c.face) {
            p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_HIT, 1f, 0.6f);
            Sky.vitals().flash(p, "&cWrong side! Pick the &asparkling &cside.", 40);
            return;
        }
        c.locks--;
        p.playSound(p.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_ON, 1f, 1.6f);
        if (c.locks > 0) {
            BlockFace next;
            do { next = FACES[MiningUtil.rnd().nextInt(FACES.length)]; } while (next == c.face);
            c.face = next;
            Sky.vitals().flash(p, "&aLock picked! &7" + c.locks + " to go.", 40);
            return;
        }
        remove(k, c);
        MiningData d = MiningUtil.data(p);
        d.chestsOpened++;
        List<String> got = m.loot.rollTable(p, m.loot.f.treasure_chest, c.gem, "GEMSTONE", LootSource.MINING);
        m.comms.progress(p, "CHEST", "", 1, null);
        Msg.box(p, "&6&lTREASURE CHEST &7opened!", got);
        Msg.sound(p, Sound.BLOCK_CHEST_OPEN, 1f);
    }

    void clear() {
        for (Map.Entry<Regen.Key, Chest> e : new ArrayList<>(chests.entrySet())) remove(e.getKey(), e.getValue());
    }
}
