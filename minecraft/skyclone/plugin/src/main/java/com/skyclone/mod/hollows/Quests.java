package com.skyclone.mod.hollows;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The five crystal quests of the Crystal Caverns and the NPCs who run them, plus the Frozen Tunnels'
 * camp NPCs. Crystals are handed out through {@link #reward}, which the mining module's Nucleus then
 * consumes (it owns the sockets and the Nucleus loot).
 */
final class Quests implements Listener {

    static final String CH = "crystal_hollows", GT = "glacite_tunnels";

    private final HollowsModule m;
    /** Live temple guardians: guardian uuid -> owner. */
    private final Map<UUID, UUID> guardians = new HashMap<>();

    Quests(HollowsModule m) {
        this.m = m;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::detectorTick, 40L, 10L);
        Sky.mobs().registerBehaviour("hollows_ignite", new MobBehaviour() {
            @Override public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) { target.setFireTicks(Math.max(target.getFireTicks(), 40)); }
        });
    }

    HollowsData data(Player p) { return Sky.profiles().member(p, "hollows", HollowsData.class); }

    static String island(Player p) { return Sky.worlds().islandOf(p.getWorld()); }

    static String name(String itemId) {
        var d = Sky.items().def(itemId);
        return d == null ? Text.titleCase(itemId) : (d.rarity != null ? d.rarity.code() : "&f") + Text.strip(d.name);
    }

    // =============================================================================================
    // rewards
    // =============================================================================================

    /** Give the crystal of a quest and count the run. */
    void reward(Player p, String gem, String flavour) {
        Lore.Quest q = Lore.quest(gem);
        if (q == null) return;
        if (!Sky.items().exists(q.crystal())) { Msg.error(p, "The " + Text.titleCase(gem) + " crystal is missing from the item list (tell an admin)."); return; }
        Sky.loot().give(p, q.crystal(), 1, LootSource.REWARD);
        HollowsData d = data(p);
        d.runs.merge(gem, 1, Integer::sum);
        Msg.box(p, "&5&lCRYSTAL FOUND", List.of(flavour, "&7You received " + name(q.crystal()) + "&7!",
                "&7Place it in the &dCrystal Nucleus &7with the other four.", "&8(" + q.structure() + " run #" + d.runs.get(gem) + ")"));
        Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f);
        Sky.service(Features.SkyLevel.class).award(p, "hollows:crystal:" + gem.toLowerCase(), 5, "First " + Text.titleCase(gem) + " Crystal");
    }

    // =============================================================================================
    // NPCs
    // =============================================================================================

    void registerNpcs() {
        var npcs = Sky.npcs();
        npcs.register(NpcDef.of("hollows_guide", "&eProspector Gundry", CH, "npc.hollows_guide").title("&e&lCAVERN GUIDE"), this::guide);
        npcs.register(NpcDef.of("hollows_temple_altar", "&5Tidecarver Shrine", CH, "npc.temple_altar").title("&5&lAMETHYST CRYSTAL"), this::altar);
        npcs.register(NpcDef.of("hollows_odala", "&aOdala", CH, "npc.odala").title("&e&lJUNGLE KEYS"), p ->
                Sky.npcs().talk(p, "hollows_odala", List.of("&fThe temple door only answers to a Jungle Key.",
                        "&fBring me Sludge Juice and I'll carve you one."), () -> m.menus.odala(p)));
        for (String k : Lore.KEEPER_TOOLS.keySet()) {
            String id = "hollows_keeper_" + k;
            npcs.register(NpcDef.of(id, "&6Keeper of " + Text.titleCase(k), CH, "npc.keeper_" + k).title("&6&lMINES OF VARNAS"), p -> keeper(p, k));
        }
        npcs.register(NpcDef.of("hollows_professor", "&bProfessor Gearhart", CH, "npc.professor").title("&b&lSAPPHIRE CRYSTAL"), p ->
                Sky.npcs().talk(p, "hollows_professor", List.of("&fAh, a visitor! These automatons were built from six parts.",
                        "&fBring me one of each and I will trade you something shiny."), () -> m.menus.professor(p)));
        NpcDef king = NpcDef.of("hollows_goblin_king", "&6King Grubnak", CH, "npc.goblin_king").title("&6&lAMBER CRYSTAL");
        String goblin = goblinSkin();
        if (goblin != null) king.skin = goblin;
        npcs.register(king, this::goblinKing);
        // Frozen Tunnels camp
        // (the camp's commissions emissary, fossil excavator and quartermaster stand on npc.camp_* and are the mining
        //  module's NPCs; ours is the supply tent next to them, so two bodies never share one anchor)
        npcs.register(NpcDef.of("hollows_camp_supplies", "&eSupply Keeper Dagny", GT, "npc.camp_supplies").title("&e&lCAMPFIRES & KEYS"), p ->
                Sky.npcs().talk(p, "hollows_camp_supplies", List.of("&fThe cold down there will kill you before the guardians do.",
                        "&fCampfires and corpse keys. Bring Umber or Tungsten for a key."), () -> m.menus.quartermaster(p)));
        npcs.register(NpcDef.of("hollows_old_lupo", "&7Old Lupo", GT, "npc.old_lupo").title("&7&lFROZEN CORPSES"), p ->
                Sky.npcs().talk(p, "hollows_old_lupo", List.of("&fThe deep shafts are full of frozen miners.",
                        "&fLapis ones open for anybody. Umber and Tungsten ones want a key.",
                        "&fDagny at the camp's supply tent will make you one."), () -> Sky.commands().run(p, "mineshaft")));
    }

    private static String goblinSkin() {
        var d = Sky.items().def("GOBLIN_HELMET");
        return d == null ? null : d.skull;
    }

    private void guide(Player p) {
        HollowsData d = data(p);
        if (!d.metGuide) {
            d.metGuide = true;
            Sky.npcs().talk(p, "hollows_guide", List.of("&fWelcome to the Crystal Caverns!",
                    "&fFive crystals hide in five corners of this place.",
                    "&fFind them all and set them into the Nucleus down there.",
                    "&fI'll keep track of how you're doing."), () -> m.menus.guide(p));
            return;
        }
        m.menus.guide(p);
    }

    // ---- Jungle Temple ----------------------------------------------------------------------------

    /** Is this player's Door Guardian standing right now? */
    boolean guardianAlive(Player p) {
        for (Map.Entry<UUID, UUID> e : guardians.entrySet())
            if (e.getValue().equals(p.getUniqueId())) {
                var ent = Bukkit.getEntity(e.getKey());
                if (ent != null && ent.isValid()) return true;
            }
        return false;
    }

    private void altar(Player p) {
        HollowsData d = data(p);
        boolean inner = m.temple != null && m.temple.available();
        if (!inner && guardianAlive(p)) { Msg.error(p, "The Door Guardian is already awake. Defeat it!"); return; }
        boolean paid = d.templePaidUntil > System.currentTimeMillis();
        if (!paid) {
            if (Sky.items().count(p, "JUNGLE_KEY") < 1) {
                Sky.npcs().talk(p, "hollows_temple_altar", List.of("&7The shrine has a keyhole carved like a wave. It is the temple's only door.",
                        "&7It wants a &5Jungle Key&7. Odala by the east wall makes them; Key Guardians carry them."), null);
                return;
            }
            Sky.menus().confirm(p, "Use your Jungle Key?", inner
                    ? List.of("&7The key turns and the shrine opens", "&7the way into the temple. Cross the", "&7pit; the Door Guardian waits inside.", "",
                            "&8A fall only sends you back. For " + m.dials.templeRetryMinutes.get() + " min the", "&8shrine lets you back in for free.")
                    : List.of("&7The key turns, and the temple's", "&7guardian wakes to stop you."), () -> {
                if (Sky.items().remove(p, "JUNGLE_KEY", 1) < 1) { Msg.error(p, "You don't have a Jungle Key."); return; }
                data(p).templePaidUntil = System.currentTimeMillis() + m.dials.templeRetryMinutes.get() * 60_000L;
                openTemple(p);
            }, null);
            return;
        }
        openTemple(p);
    }

    /** The key is paid: into the inner temple (v3 island), or the old shrine fight on an older build. */
    private void openTemple(Player p) {
        if (m.temple != null && m.temple.available() && m.temple.enter(p)) return;
        if (guardianAlive(p)) { Msg.error(p, "The Door Guardian is already awake. Defeat it!"); return; }
        summonGuardian(p);
    }

    void summonGuardian(Player p) {
        Location at = Sky.worlds().anchor(CH, "npc.temple_altar");
        if (at == null || at.getWorld() != p.getWorld()) at = p.getLocation();
        summonGuardian(p, at.clone().add(0, 0, 3));
    }

    void summonGuardian(Player p, Location spot) {
        LivingEntity g;
        try { g = Sky.mobs().spawn("ch_temple_guardian", spot, p); } catch (Exception e) { g = null; }
        if (g == null) { Msg.error(p, "The guardian would not wake. Try again in a moment."); return; }
        guardians.put(g.getUniqueId(), p.getUniqueId());
        Msg.send(p, "&5&lTIDECARVER DOOR GUARDIAN &7wakes! Defeat it to claim the crystal.");
        Msg.sound(p, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f);
    }

    // ---- Mines of Varnas -------------------------------------------------------------------------

    private void keeper(Player p, String keeper) {
        HollowsData d = data(p);
        String tool = Lore.KEEPER_TOOLS.get(keeper);
        String npc = "hollows_keeper_" + keeper;
        if (Sky.items().count(p, Lore.METAL_DETECTOR) < 1 && Sky.items().exists(Lore.METAL_DETECTOR)) {
            Sky.loot().give(p, Lore.METAL_DETECTOR, 1, LootSource.REWARD);
            Sky.npcs().talk(p, npc, List.of("&fOur order lost its " + Text.strip(name(tool)) + " somewhere under this hall.",
                    "&fTake this Metal Detector. Hold it and listen: the closer you are, the louder it sings.",
                    "&fOur caches lie under the floor. Where it screams, break the tile and open the chest."), () -> m.menus.keepers(p));
            return;
        }
        if (d.keepersGiven.contains(keeper)) {
            Sky.npcs().talk(p, npc, List.of("&fYou already returned our " + Text.strip(name(tool)) + ". Help the other Keepers."), () -> m.menus.keepers(p));
            return;
        }
        if (Sky.items().count(p, tool) < 1) {
            Sky.npcs().talk(p, npc, List.of("&fFind our " + Text.strip(name(tool)) + " and bring it back to me."), () -> m.menus.keepers(p));
            return;
        }
        Sky.items().remove(p, tool, 1);
        d.keepersGiven.add(keeper);
        Msg.success(p, "You returned the " + Text.strip(name(tool)) + " to the Keeper of " + Text.titleCase(keeper) + "! (" + d.keepersGiven.size() + "/4)");
        Msg.sound(p, Sound.ENTITY_VILLAGER_YES, 1f);
        if (d.keepersGiven.size() >= Lore.KEEPER_TOOLS.size()) {
            d.keepersGiven.clear();
            reward(p, "JADE", "&6The four Keepers open the Varnas vault together.");
        }
    }

    /** Pick a new buried treasure for this player (never the one just dug). */
    String newTarget(Player p) {
        Layout lay = Sky.worlds().layout(CH);
        if (lay == null) return null;
        List<String> spots = new ArrayList<>();
        for (String a : lay.anchors().keySet()) if (a.startsWith("varnas.treasure.")) spots.add(a);
        if (spots.isEmpty()) return null;
        HollowsData d = data(p);
        String old = d.detectorTarget;
        if (spots.size() > 1 && old != null) spots.remove(old);
        d.detectorTarget = spots.get(ThreadLocalRandom.current().nextInt(spots.size()));
        return d.detectorTarget;
    }

    private boolean inHall(Player p) {
        Box b = Sky.worlds().area(CH, "varnas.hall");
        Location l = p.getLocation();
        return b != null && CH.equals(island(p)) && b.grow(2).contains(l.getX(), l.getY(), l.getZ());
    }

    private void detectorTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p) || !CH.equals(island(p))) continue;
            if (!Lore.METAL_DETECTOR.equals(Sky.items().idOf(p.getInventory().getItemInMainHand()))) continue;
            if (!inHall(p)) { Sky.vitals().flash(p, "&7Metal Detector: &8silent (only works in the Mines of Varnas)", 12); continue; }
            double dist = targetDistance(p);
            if (dist < 0) continue;
            Sky.vitals().flash(p, Lore.reading(dist), 12);
            if (Bukkit.getCurrentTick() % 20 == 0) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, Lore.pitch(dist));
        }
    }

    private double targetDistance(Player p) {
        HollowsData d = data(p);
        if (d.detectorTarget == null && newTarget(p) == null) return -1;
        Location t = Sky.worlds().anchor(CH, d.detectorTarget);
        if (t == null && newTarget(p) != null) t = Sky.worlds().anchor(CH, d.detectorTarget);
        if (t == null || t.getWorld() != p.getWorld()) return -1;
        return t.distance(p.getLocation());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDetector(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        Player p = e.getPlayer();
        if (!Lore.METAL_DETECTOR.equals(Sky.items().idOf(e.getItem())) || !Sky.profiles().isReady(p)) return;
        e.setCancelled(true);
        if (!inHall(p)) { Msg.error(p, "The detector only picks up the treasures of the Mines of Varnas."); return; }
        double dist = targetDistance(p);
        if (dist < 0) { Msg.error(p, "The detector can't find anything here yet."); return; }
        if (dist > Lore.DIG_RADIUS) { Msg.send(p, Lore.reading(dist)); return; }
        if (m.varnas != null && m.varnas.buried()) { m.varnas.hint(p); return; }
        dig(p);
    }

    /** Old islands (no buried chests) and admins: the treasure comes straight up. */
    void dig(Player p) {
        Location spot = data(p).detectorTarget == null ? null : Sky.worlds().anchor(CH, data(p).detectorTarget);
        String found = treasure(p);
        if (found != null) Msg.send(p, "&6&lTREASURE! &7You dug up the " + name(found) + "&7! Return it to its Keeper.");
        p.getWorld().spawnParticle(Particle.BLOCK, p.getLocation(), 30, 0.4, 0.2, 0.4, org.bukkit.Material.DEEPSLATE_TILES.createBlockData());
        if (spot != null && spot.getWorld() == p.getWorld() && spot.getBlock().isPassable()) {
            // the cache surfaces for a moment (client-side only: nothing is placed in the world)
            Location b = spot.getBlock().getLocation();
            p.sendBlockChange(b, org.bukkit.Material.CHEST.createBlockData());
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, b.clone().add(0.5, 0.8, 0.5), 25, 0.3, 0.4, 0.3, 0.2);
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) p.sendBlockChange(b, b.getBlock().getBlockData()); }, 60L);
        }
    }

    /**
     * Pay out the treasure the player was hunting and pick the next spot: the next lost tool they neither carry nor
     * returned, else a cache of rough Jade. Returns the tool's id, or null for the Jade cache.
     */
    String treasure(Player p) {
        HollowsData d = data(p);
        String found = null;
        for (Map.Entry<String, String> e : Lore.KEEPER_TOOLS.entrySet())
            if (!d.keepersGiven.contains(e.getKey()) && Sky.items().count(p, e.getValue()) < 1) { found = e.getValue(); break; }
        newTarget(p);
        d.treasuresDug++;
        if (found == null) {
            Sky.loot().give(p, "ROUGH_JADE_GEM", 8 + ThreadLocalRandom.current().nextInt(9), LootSource.MINING);
            Msg.send(p, "&aYou found a cache of Jade. &7(You already carry every lost tool.)");
            return null;
        }
        Sky.loot().give(p, found, 1, LootSource.REWARD);
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
        return found;
    }

    // ---- Professor / Goblin King -------------------------------------------------------------------

    /** Hand the six parts (or an apparatus) to the Professor. */
    boolean professorTrade(Player p) {
        if (Sky.items().count(p, Lore.APPARATUS) > 0) {
            Sky.items().remove(p, Lore.APPARATUS, 1);
            reward(p, "SAPPHIRE", "&bProfessor Gearhart takes the apparatus apart with glee.");
            return true;
        }
        for (String part : Lore.ROBOT_PARTS) if (Sky.items().count(p, part) < 1) { Msg.error(p, "You still need: " + Text.strip(name(part)) + "."); return false; }
        for (String part : Lore.ROBOT_PARTS) Sky.items().remove(p, part, 1);
        reward(p, "SAPPHIRE", "&bProfessor Gearhart fits the six parts together.");
        return true;
    }

    private void goblinKing(Player p) {
        int refused = m.stealth == null ? 0 : m.stealth.refusedFor(p);
        if (refused > 0) {
            Sky.npcs().talk(p, "hollows_goblin_king", List.of("&fGUARDS! This one came stomping in like a troll!",
                    "&fCome back quietly and maybe I'll listen. &8(" + refused + "s, sneak past the guards)"), null);
            return;
        }
        String egg = null;
        for (String e : Lore.GOBLIN_EGGS) if (Sky.items().count(p, e) > 0) { egg = e; break; }
        if (egg == null) {
            Sky.npcs().talk(p, "hollows_goblin_king", List.of("&fWhat's this? A tall one, in MY burrow?",
                    "&fBring your king an egg. Any colour. Then we'll talk about amber.",
                    "&fAnd mind my guards. Nobody stomps into MY burrow. Creep in, or stay out.",
                    "&fThe Queen sits on the clutch, south past the fire pit. She bites."), null);
            return;
        }
        String chosen = egg;
        Sky.npcs().talk(p, "hollows_goblin_king", List.of("&fIs that... an egg? Give it here!"), () -> {
            if (Sky.items().remove(p, chosen, 1) < 1) { Msg.error(p, "You no longer have the egg."); return; }
            reward(p, "AMBER", "&6King Grubnak swaps you a crystal for " + Text.strip(name(chosen)) + ".");
        });
    }

    // =============================================================================================
    // events
    // =============================================================================================

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.def == null) return;
        if ("ch_temple_guardian".equals(e.def.id)) {
            UUID owner = guardians.remove(e.mob.getUniqueId());
            Player p = owner == null ? e.killer : Bukkit.getPlayer(owner);
            if (p != null && Sky.profiles().isReady(p)) {
                HollowsData d = data(p);
                d.templePaidUntil = 0;
                d.templeRuns++;
                reward(p, "AMETHYST", "&5The Door Guardian crumbles and the Tidecarver door opens.");
                if (m.temple != null) {
                    m.temple.done(p);
                    if (m.temple.available()) Msg.send(p, "&7Step on the &8lodestone &7at the sanctum's east end to leave the temple.");
                }
            }
            return;
        }
        if (e.killer == null || !Sky.profiles().isReady(e.killer)) return;
        if (e.def.id.startsWith("gt_glacite_") && !e.def.id.equals("gt_glacite_walker"))
            Sky.service(Features.Mining.class).addPowder(e.killer, "GLACITE", m.dials.guardianPowder.get(), "hollows: " + e.def.id);
    }

    @EventHandler
    public void onGather(SkyEvents.Gather e) {
        if (e.player == null || e.what == null || e.location == null) return;
        if (!CH.equals(Sky.worlds().islandOf(e.location)) || !e.what.toUpperCase().contains("AMETHYST")) return;
        if (ThreadLocalRandom.current().nextDouble() >= m.dials.thystChance.get()) return;
        try {
            if (Sky.mobs().spawn("ch_thyst", e.location.clone().add(0.5, 0, 0.5), e.player) != null)
                Msg.send(e.player, "&d&lAMETHYST LEECH! &7Something was feeding on that vein...");
        } catch (Exception ignored) { }
    }
}
