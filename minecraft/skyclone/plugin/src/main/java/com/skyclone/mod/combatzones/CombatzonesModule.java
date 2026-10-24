package com.skyclone.mod.combatzones;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.world.IslandDef;
import com.skyclone.mod.combatzones.gen.DenGenerator;
import com.skyclone.mod.combatzones.gen.EndGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spider's Den and The End: both islands (generated), their mobs and spawns, the Broodmother,
 * the Silkmother altar, the Dragon's Nest fight, the End Stone Colossus, the Den's weather, the
 * drops this module owns, travel scrolls, NPCs, menus and admin tools.
 */
public final class CombatzonesModule implements GameModule, Listener {

    static CombatzonesModule INSTANCE;

    CzDials dials;
    DragonFight fight;
    ArachneAltar arachne;
    WorldBosses bosses;
    Relics relics;
    Behaviours behaviours;
    ZoneMenus menus;
    DragonEssence essence;
    Adventurer tavi;
    MobTypeGuide mobTypes;
    /** Foreign drop ids that were missing at postEnable and were dropped from tables. */
    final List<String> prunedDrops = new ArrayList<>();
    private final Map<UUID, Long> invulnerable = new HashMap<>();

    @Override public String id() { return "combatzones"; }

    @Override
    public void enable() {
        INSTANCE = this;
        dials = new CzDials();
        Sky.items().loadJson("combatzones", "items.json");
        Sky.mobs().loadJson("combatzones", "mobs.json");
        behaviours = new Behaviours(this);
        Sky.mobs().loadSpawnRules("combatzones", "spawns.json");

        // ---- islands + warps
        IslandDef den = IslandDef.of(DenGenerator.ID, "Spider's Den", new DenGenerator());
        Sky.worlds().registerIsland(den);
        IslandDef end = IslandDef.of(EndGenerator.ID, "The End",
                new EndGenerator(dials.hideoutRequirement.get(), dials.tombRequirement.get()));
        end.fixedTime = 18000;                                  // a permanent midnight sky (NORMAL env: no vanilla dragon fight)
        Sky.worlds().registerIsland(end);
        String denReq = CzDials.blankToNull(dials.denRequirement.get()), endReq = CzDials.blankToNull(dials.endRequirement.get());
        Sky.worlds().registerWarp("spiders_den", DenGenerator.ID, "spawn", "Spider's Den", denReq);
        Sky.worlds().registerWarp("the_end", EndGenerator.ID, "spawn", "The End", endReq);
        Sky.worlds().registerWarp("nest", DenGenerator.ID, "warp.nest", "Top of the Nest", and(denReq, "combatzones:scroll:nest"));
        Sky.worlds().registerWarp("sanctuary", DenGenerator.ID, "warp.sanctuary", "Silkmother's Sanctuary", and(denReq, "combatzones:scroll:sanctuary"));
        Sky.worlds().registerWarp("drag", EndGenerator.ID, "warp.drag", "Dragon's Nest", and(endReq, "combatzones:scroll:drag"));
        Sky.worlds().registerWarp("void", EndGenerator.ID, "warp.void", "Null Tomb",
                and(and(endReq, CzDials.blankToNull(dials.tombRequirement.get())), "combatzones:scroll:void"));

        Sky.requirements().register("combatzones", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 3 || !Sky.profiles().isReady(p)) return false;
                CzData d = data(p);
                return switch (parts[1]) {
                    case "scroll" -> d.scrolls.contains(parts[2]);
                    case "dragons" -> d.dragonKills.values().stream().mapToInt(Integer::intValue).sum() >= parseInt(parts[2]);
                    default -> false;
                };
            }
            @Override public String describe(String[] parts) {
                if (parts.length >= 3 && parts[1].equals("scroll")) {
                    for (String[] s : ZoneMenus.SCROLLS) if (s[0].equals(parts[2])) return "&athe " + s[2] + " Travel Scroll";
                    return "&aa Travel Scroll";
                }
                if (parts.length >= 3 && parts[1].equals("dragons")) return "&a" + parts[2] + " Dragon kills";
                return "&a" + String.join(":", parts);
            }
        });

        fight = new DragonFight(this);              // runs schema v1 (eyes)
        CzState.migrate();                          // schema v2 (zone clocks), must follow v1
        arachne = new ArachneAltar(this);
        bosses = new WorldBosses(this);
        relics = new Relics(this);
        menus = new ZoneMenus(this);
        essence = new DragonEssence(this);          // wave 5: Keeper Ixo's altar + perks (modifier, stats, cost hook)
        tavi = new Adventurer(this);
        mobTypes = new MobTypeGuide(this);
        Sky.combat().addModifier("combatzones_dragon", 400, ctx -> fight.modify(ctx));

        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(fight, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(arachne, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(bosses, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(relics, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(behaviours, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(essence, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(tavi, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(mobTypes, Sky.plugin());

        Npcs.register(this);
        Commands.register(this);
        SelfTests.register(this);

        Sky.sidebar().section("combatzones", 300, this::sidebar);
    }

    @Override
    public void postEnable() {
        // drops of items owned by modules that are not installed would fail the loot roll: drop them, loudly
        for (MobDef def : Sky.mobs().all()) {
            if (def.id == null || !(def.id.startsWith("den_") || def.id.startsWith("end_"))) continue;
            if (def.extra == null || !def.extra.has("zone")) continue;
            def.drops.removeIf(d -> {
                if (Sky.items().exists(d.item)) return false;
                if (!prunedDrops.contains(d.item)) prunedDrops.add(d.item);
                return true;
            });
        }
        if (!prunedDrops.isEmpty()) Sky.log().warning("[combatzones] dropped unregistered drop ids from loot tables: " + prunedDrops);
    }

    @Override
    public void disable() {
        if (fight != null) fight.shutdown();
        if (arachne != null) arachne.shutdown();
        if (bosses != null) bosses.shutdown();
        if (behaviours != null) behaviours.removeRiders();
    }

    CzData data(Player p) { return Sky.profiles().member(p, "combatzones", CzData.class); }

    static String and(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + " & " + b;
    }

    static int parseInt(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return Integer.MAX_VALUE; } }

    // =========================================================================================== sidebar

    private List<String> sidebar(Player p) {
        String isl = Sky.worlds().islandOf(p.getWorld());
        if (DenGenerator.ID.equals(isl)) {
            List<String> l = new ArrayList<>();
            l.add("&7Broodmother: " + bosses.broodLabel());
            if (bosses.thundering()) l.add("&9⚡ Thunderstorm");
            else if (bosses.raining()) l.add("&9☂ Raining");
            if (arachne.busy()) l.add("&dSilkmother &cawake!");
            return l;
        }
        if (EndGenerator.ID.equals(isl)) {
            List<String> l = new ArrayList<>(fight.sidebar());
            int cs = DragonMath.protectorStage(bosses.endStoneMined, dials.protectorPerStage.get());
            if (bosses.colossusId != null) l.add("&eColossus &c&lALIVE!");
            else if (cs > 0) l.add("&eColossus: &7Stage " + cs + "/5");
            return l;
        }
        return List.of();
    }

    // =========================================================================================== items in hand

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType().isAir()) return;
        String id = Sky.items().idOf(it);
        if (id == null) return;
        ItemDef def = Sky.items().def(id);
        if (def == null || def.extra == null) return;
        Player p = e.getPlayer();
        if (def.extra.has("czWarp")) {
            e.setCancelled(true);
            if (!Sky.profiles().isReady(p)) return;
            String w = def.extra.get("czWarp").getAsString();
            if (!data(p).scrolls.add(w)) { Msg.error(p, "You already unlocked this destination."); return; }
            it.setAmount(it.getAmount() - 1);
            Msg.success(p, "Unlocked a new fast travel destination! &e/warp " + w);
            Msg.sound(p, Sound.ITEM_BOOK_PAGE_TURN, 1f);
            Sky.profiles().saveNow(p);
            return;
        }
        if ("SLEEPING_EYE".equals(id) && (e.getClickedBlock() == null || e.getClickedBlock().getType() != org.bukkit.Material.END_PORTAL_FRAME)) {
            e.setCancelled(true);
            if (fight.eyesOf(p.getUniqueId()) == 0 && fight.state == DragonFight.State.IDLE) {
                // its eye is gone (the dragon already woke): it becomes a remnant, as the lore says
                int n = it.getAmount();
                it.setAmount(0);
                Sky.loot().give(p, "REMNANT_OF_THE_EYE", n, LootSource.OTHER);
                Msg.send(p, "&5☬ &7The eye has already awoken. It is a &5Remnant of the Eye &7now.");
            } else Msg.send(p, "&5☬ &7Your eye rests in the altar. Hold this and right-click its frame to take it back.");
        }
    }

    // =========================================================================================== remnant of the eye

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDamage(SkyEvents.Damage e) {
        Player p = e.ctx.playerVictim();
        if (p == null || e.ctx.cancelled) return;
        Long until = invulnerable.get(p.getUniqueId());
        if (until != null) {
            if (until > System.currentTimeMillis()) { e.ctx.cancelled = true; return; }
            invulnerable.remove(p.getUniqueId());
        }
        if (!EndGenerator.ID.equals(Sky.worlds().islandOf(p.getWorld()))) return;
        if (e.ctx.kind == DamageContext.Kind.ENVIRONMENT && p.getLocation().getY() < 0) return;      // the void still wins
        double left = Sky.vitals().health(p) + Sky.vitals().absorption(p);
        if (e.ctx.finalDamage < left) return;
        if (Sky.items().count(p, "REMNANT_OF_THE_EYE") < 1) return;
        Sky.items().remove(p, "REMNANT_OF_THE_EYE", 1);
        e.ctx.cancelled = true;
        invulnerable.put(p.getUniqueId(), System.currentTimeMillis() + dials.remnantSeconds.get() * 1000L);
        Sky.vitals().setHealth(p, Math.max(Sky.vitals().health(p), Sky.vitals().maxHealth(p) * 0.1));
        if (Sky.profiles().isReady(p)) data(p).remnantSaves++;
        Msg.send(p, "&5☬ &dYour Remnant of the Eye shattered and saved your life! &7(" + dials.remnantSeconds.get() + "s of invulnerability)");
        Msg.sound(p, Sound.ITEM_TOTEM_USE, 1.2f);
    }

    // =========================================================================================== leaving the end

    @EventHandler
    public void onIsland(SkyEvents.IslandChange e) {
        if (EndGenerator.ID.equals(e.from) && !EndGenerator.ID.equals(e.to)) fight.refundAll(e.player);
    }

    @EventHandler
    public void onBuilt(SkyEvents.IslandBuilt e) {
        if (EndGenerator.ID.equals(e.islandId) && e.instanceKey == null) fight.syncFrames();
    }

    // shared helper for admin/self-tests
    static String fmt(double d) { return Text.commas(d); }

    static List<DropDef> drops(String mob) {
        MobDef d = Sky.mobs().def(mob);
        return d == null ? List.of() : d.drops;
    }

    static NpcDef npc(String id, String name, String island, String anchor) { return NpcDef.of(id, name, island, anchor); }

    static SkyCommand cmd(String n, String d) { return SkyCommand.of(n, d); }
}
