package com.skyclone.mod.hollows;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.mob.SpawnRule;
import com.skyclone.api.world.IslandDef;
import com.skyclone.mod.hollows.gen.HollowsGenerator;
import com.skyclone.mod.hollows.gen.TunnelsGenerator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The Crystal Caverns (crystal_hollows) and the Frozen Tunnels (glacite_tunnels): two generated
 * cave islands, their mobs and spawn rules, the five crystal quests that feed the mining module's
 * Crystal Nucleus, and the tunnels' base-camp NPCs. Mining SYSTEMS (breaking, powder, heat, cold,
 * Nucleus sockets, commissions) belong to the mining module.
 */
public final class HollowsModule implements GameModule {

    HollowsDials dials;
    Quests quests;
    HollowsMenus menus;
    Waypoints waypoints;
    Stealth stealth;
    TempleTraps traps;
    Magmaw magmaw;
    GoblinQueen queen;
    TempleRun temple;
    VarnasDig varnas;
    Lobbies lobbies;
    Passes passes;
    IslandDef caverns, tunnels;

    @Override public String id() { return "hollows"; }

    @Override
    public void enable() {
        dials = new HollowsDials();
        HollowsGenerator.lobby = Math.max(0, dials.lobby.get());

        // ---- islands + warps ----
        caverns = IslandDef.of("crystal_hollows", "Crystal Caverns", new HollowsGenerator());
        caverns.requirement = cavernsRequirement(dials.cavernsRequirement.get());
        caverns.fixedTime = 18000;
        Sky.worlds().registerIsland(caverns);
        tunnels = IslandDef.of("glacite_tunnels", "Frozen Tunnels", new TunnelsGenerator());
        tunnels.requirement = tunnelsRequirement(dials.tunnelsRequirement.get());
        tunnels.fixedTime = 18000;
        Sky.worlds().registerIsland(tunnels);
        dials.cavernsRequirement.onChange(v -> caverns.requirement = cavernsRequirement(v));
        dials.lobbyRotation.onChange(v -> caverns.requirement = cavernsRequirement(dials.cavernsRequirement.get()));
        dials.tunnelsRequirement.onChange(v -> tunnels.requirement = tunnelsRequirement(v));
        dials.passRequired.onChange(v -> caverns.requirement = cavernsRequirement(dials.cavernsRequirement.get()));
        dials.railPassRequired.onChange(v -> tunnels.requirement = tunnelsRequirement(dials.tunnelsRequirement.get()));
        Sky.worlds().registerWarp("crystal_hollows", "crystal_hollows", "spawn", "Crystal Caverns", null);
        Sky.worlds().registerWarp("glacite_tunnels", "glacite_tunnels", "spawn", "Frozen Tunnels", null);

        // ---- items this module owns (quest items of the Mines of Varnas) ----
        registerQuestItems();

        // ---- mobs ----
        Sky.mobs().loadJson("hollows", "mobs.json");
        magmaw = new Magmaw(this);
        Sky.mobs().registerBehaviour("hollows_magmaw", magmaw);
        queen = new GoblinQueen(this);
        Sky.mobs().registerBehaviour("hollows_goblin_queen", queen);
        Sky.mobs().registerBehaviour("hollows_ember_bat", new Magmaw.EmberBat());
        Sky.mobs().loadSpawnRules("hollows", "spawns.json");
        Sky.mobs().addSpawnRule(rule("ch_magmaw", "crystal_hollows", "ch_magmaw", List.of("boss.magmaw"), dials.magmawRespawn.get()));
        Sky.mobs().addSpawnRule(rule("ch_goblin_queen", "crystal_hollows", GoblinQueen.MOB, List.of("boss.goblin_queen"), dials.queenRespawn.get()));
        if (dials.depthsGuardians.get()) {
            // off by default: in the reference these four only guard mineshafts (mining spawns them there)
            for (String g : List.of("bowman", "mage", "caver", "mutt")) {
                SpawnRule r = rule("gt_depths_" + g, "glacite_tunnels", "gt_glacite_" + g, List.of(), 45);
                r.anchors = null;
                r.area = "mobs.depths";
                r.max = g.equals("mutt") ? 3 : 2;
                Sky.mobs().addSpawnRule(r);
            }
        }
        Sky.mobs().addSpawnRule(rule("ch_gang_boss", "crystal_hollows", "ch_boss_corleone", List.of("boss.gang_boss"), dials.gangBossRespawn.get()));

        // ---- quests, NPCs, menus ----
        quests = new Quests(this);
        menus = new HollowsMenus(this);
        waypoints = new Waypoints();
        stealth = new Stealth(this);
        traps = new TempleTraps(this);
        Bukkit.getPluginManager().registerEvents(traps, Sky.plugin());
        temple = new TempleRun(this);
        varnas = new VarnasDig(this);
        Bukkit.getPluginManager().registerEvents(varnas, Sky.plugin());
        lobbies = new Lobbies(this);
        Bukkit.getPluginManager().registerEvents(lobbies, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new Kalhuiki(this), Sky.plugin());
        passes = new Passes(this);
        Bukkit.getPluginManager().registerEvents(passes, Sky.plugin());
        quests.registerNpcs();
        Bukkit.getPluginManager().registerEvents(quests, Sky.plugin());

        // ---- commands ----
        Sky.commands().register(SkyCommand.of("hollows", "Open the Crystal Caverns guide (crystals, Nucleus, travel)")
                .alias("crystalcaverns", "caverns").category("Skills").playerOnly().run((s, a) -> {
                    if (a.length > 0 && (a[0].equalsIgnoreCase("find") || a[0].equalsIgnoreCase("track"))) waypoints.menu((Player) s);
                    else if (a.length > 0 && a[0].equalsIgnoreCase("bosses")) menus.bosses((Player) s);
                    else if (a.length > 0 && a[0].equalsIgnoreCase("pass")) menus.pass((Player) s);
                    else menus.guide((Player) s);
                }));
        Sky.commands().register(SkyCommand.of("tunnels", "Open the Frozen Tunnels menu (camp, supplies, corpses)")
                .alias("frozentunnels", "basecamp").category("Skills").playerOnly().run((s, a) -> menus.tunnels((Player) s)));
        HollowsAdmin.register(this);
        HollowsTests.register(this);

        // ---- sidebar ----
        Sky.sidebar().section("hollows", dials.sidebarPriority.get(), this::sidebar);
    }

    private List<String> sidebar(Player p) {
        if (!"crystal_hollows".equals(Sky.worlds().islandOf(p.getWorld())) || !Sky.profiles().isReady(p)) return List.of();
        int carried = 0;
        for (Lore.Quest q : Lore.QUESTS) if (Sky.items().count(p, q.crystal()) > 0) carried++;
        return List.of("&5Crystals: &a" + carried + "&7/5 " + (lobbies == null ? "&8(lobby #" + dials.lobby.get() + ")" : lobbies.sidebarTag()));
    }

    @Override
    public void disable() {
        if (varnas != null) varnas.resealAll();
    }

    /** The dial's requirement plus the lobby gate (closed-to-newcomers / being rebuilt). */
    private String cavernsRequirement(String dial) {
        String req = blankToNull(dial);
        if (dials.passRequired.get()) req = and(req, Passes.CH_REQ);
        if (dials.lobbyRotation.get()) req = and(req, Lobbies.REQ);
        return req;
    }

    private String tunnelsRequirement(String dial) {
        String req = blankToNull(dial);
        return dials.railPassRequired.get() ? and(req, Passes.GT_REQ) : req;
    }

    private static String and(String a, String b) { return a == null ? b : a + " & " + b; }

    static SpawnRule rule(String id, String island, String mob, List<String> anchors, int respawn) {
        SpawnRule r = new SpawnRule();
        r.id = id;
        r.island = island;
        r.mob = mob;
        r.anchors = new ArrayList<>(anchors);
        r.max = 1;
        r.respawnSeconds = respawn;
        return r;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() || s.equalsIgnoreCase("none") ? null : s.trim(); }

    /** The Mines of Varnas' quest items, registered here unless another module already did. */
    private void registerQuestItems() {
        item("DWARVEN_LAPIS_SWORD", "Scavenged Lapis Sword", "DIAMOND_SWORD", "&7A dwarven blade, still blue with", "&7lapis. The Keeper of Lapis wants it back.");
        item("DWARVEN_EMERALD_HAMMER", "Scavenged Emerald Hammer", "WOODEN_AXE", "&7The Keeper of Emerald's hammer,", "&7lost in a cave-in long ago.");
        item("DWARVEN_DIAMOND_AXE", "Scavenged Diamond Axe", "DIAMOND_AXE", "&7An axe that once cut tunnels.", "&7The Keeper of Diamond misses it.");
        item("DWARVEN_GOLD_HAMMER", "Scavenged Golden Hammer", "GOLDEN_AXE", "&7Heavy, gilded and dented. It belongs", "&7to the Keeper of Gold.");
        item(Lore.METAL_DETECTOR, "Metal Detector", "IRON_HOE", "&7Finds the tools buried under the", "&7Mines of Varnas. It hums louder the", "&7closer you are; right-click to dig.");
    }

    private void item(String id, String name, String material, String... lore) {
        if (Sky.items().exists(id)) return;
        ItemDef d = ItemDef.of(id, name, material, Rarity.SPECIAL, ItemCategory.NONE);
        d.lore(lore);
        d.maxStack = 1;
        d.tag("no_trade", "no_ah", "no_reforge", "soulbound");
        Sky.items().register(d);
    }
}
