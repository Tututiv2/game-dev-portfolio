package com.skyclone.mod.dungeons;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.IslandDef;
import com.skyclone.mod.dungeons.classes.ClassSystem;
import com.skyclone.mod.dungeons.ext.Bosses;
import com.skyclone.mod.dungeons.ext.RewardHook;
import com.skyclone.mod.dungeons.gen.CatacombsShellGenerator;
import com.skyclone.mod.dungeons.gen.DungeonHubGenerator;
import com.skyclone.mod.dungeons.model.FloorDef;
import com.skyclone.mod.dungeons.party.Parties;
import com.skyclone.mod.dungeons.party.PartyFinder;
import com.skyclone.mod.dungeons.run.DefaultBoss;
import com.skyclone.mod.dungeons.run.DungeonRun;
import com.skyclone.mod.dungeons.run.RunHud;
import com.skyclone.mod.dungeons.run.RunListener;
import com.skyclone.mod.dungeons.run.RunManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * DUNGEONS, part A (the framework; research 04 sec 3): the Dungeon Hub island, parties and the Party Finder, the five
 * classes, Catacombs levels and the dungeon stat boost, and the run itself - a fresh map generated for every run from
 * room tiles on a grid (start, fairy, blood, puzzle, trap, miniboss and normal rooms), wither and blood doors with
 * keys, secrets, crypts, the Dungeon Map, ghosts and revives, the Watcher, score and XP, and the hand-over to a boss
 * floor. Part B (bosses and loot) plugs in through {@code ext}: {@link Bosses}, {@link RewardHook},
 * {@link com.skyclone.mod.dungeons.gen.Tiles}, {@link com.skyclone.mod.dungeons.ext.DungeonHooks}.
 *
 * <p>Provides {@link Features.Dungeons} and the requirement prefixes {@code catacombs}, {@code class} and
 * {@code dungeon_completion}.
 */
public final class DungeonsModule implements GameModule {

    @Override public String id() { return "dungeons"; }

    @Override
    public void enable() {
        DDials.init();
        int floors = Floors.load();
        int items = Sky.items().loadJson("dungeons", "items.json");
        int mobs = Sky.mobs().loadJson("dungeons", "mobs.json");
        Sky.log().info("[dungeons] " + floors + " floors, " + items + " items, " + mobs + " mobs");

        IslandDef hub = IslandDef.of("dungeon_hub", "Dungeon Hub", new DungeonHubGenerator());
        hub.fixedTime = 14000;
        Sky.worlds().registerIsland(hub);
        Sky.worlds().registerWarp("dungeon_hub", "dungeon_hub", "spawn", "Dungeon Hub", null);
        IslandDef cata = IslandDef.of("catacombs", "The Catacombs", new CatacombsShellGenerator());
        cata.instanced = true;
        cata.instanceSpacing = 1024;
        cata.fixedTime = 18000;
        Sky.worlds().registerIsland(cata);

        Sky.provide(Features.Dungeons.class, Progress.INSTANCE);
        Progress.registerResolvers();
        Bosses.setFallback(DefaultBoss.INSTANCE);
        RewardHook.setFallback(DungeonsModule::freeChest);

        Behaviours.register();
        RunManager.init();
        Parties.init();
        // integrator (kuudra request 1): share the dungeon party with other group content
        Sky.provide(Features.Party.class, new Features.Party() {
            @Override public List<Player> members(Player p) { return Parties.get().group(p); }
            @Override public Player leader(Player p) {
                var party = Parties.get().of(p);
                Player l = party == null ? null : Bukkit.getPlayer(party.leader);
                return l == null ? p : l;
            }
        });
        PartyFinder.init();
        ClassSystem.init();
        DungeonItems.init();
        Bukkit.getPluginManager().registerEvents(new RunListener(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(com.skyclone.mod.dungeons.gear.TimedShields.INSTANCE, Sky.plugin());
        RunHud.register();
        npcs();
        commands();
        DungeonAdmin.register();
        DungeonTests.register();
        Wave5Tests.register();
        Wave5RegTests.register();
        PartB.enable();
    }

    @Override
    public void postEnable() {
        RunManager.get().recover();
        // wave 5: the starred dungeon weapons copy the weapons module's base defs, which it registers in ITS postEnable
        com.skyclone.mod.dungeons.gear.StarredWeapons.load();
        Bukkit.getScheduler().runTask(Sky.plugin(), com.skyclone.mod.dungeons.gear.StarredWeapons::registerAll);
    }

    @Override
    public void disable() {
        if (RunManager.get() != null) RunManager.get().shutdown();
    }

    /**
     * The fallback reward until agent B's chests exist: every member gets the free (Wood) chest's guaranteed essence,
     * the floor's Wood chest pair (wiki, audit C11: F6 10 Undead + 12 Wither, F7 10 + 15 - fixed, not a 1..max range).
     */
    public static void freeChest(com.skyclone.mod.dungeons.ext.RunResult r) {
        for (UUID u : r.members) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            int undead = r.floor.woodUndead, wither = Math.max(1, r.floor.woodWitherMax);
            Msg.send(p, "&6&lFREE CHEST &7(" + r.floor.display() + ")");
            if (Sky.items().exists("ESSENCE_UNDEAD")) { Sky.loot().give(p, "ESSENCE_UNDEAD", undead, LootSource.DUNGEON); Msg.send(p, "  &d+" + undead + " Undead Essence"); }
            if (Sky.items().exists("ESSENCE_WITHER")) { Sky.loot().give(p, "ESSENCE_WITHER", wither, LootSource.DUNGEON); Msg.send(p, "  &8+" + wither + " Wither Essence"); }
        }
    }

    private void npcs() {
        NpcDef guide = NpcDef.of("dungeon_guide", "&cCatacombs Guide", "hub", "npc.dungeon_guide").title("&c&lDUNGEONS");
        guide.lines.add("&7To the Dungeon Hub");
        Sky.npcs().register(guide, p -> Sky.npcs().talk(p, "dungeon_guide", List.of(
                "&fBelow this island lie the Catacombs: rooms full of the dead, keys, puzzles and worse.",
                "&fGather a party at the Dungeon Hub, pick a class, and walk through the great arch.",
                "&fI will take you there now."), () -> Sky.worlds().warp(p, "dungeon_hub")));

        NpcDef gate = NpcDef.of("dungeon_gatekeeper", "&fSexton Abel", "dungeon_hub", "npc.gatekeeper").title("&c&lTHE CATACOMBS");
        gate.lines.add("&7Floors, classes and records");
        Sky.npcs().register(gate, p -> {
            if (!Sky.profiles().isReady(p)) return;
            DData d = Progress.data(p);
            if (d.runsStarted == 0) Sky.npcs().talk(p, "dungeon_gatekeeper", List.of(
                    "&fNew blood. Listen well, it may keep you breathing.",
                    "&fEvery run the halls are rearranged. Clear rooms, find &8Wither Keys&f for the black doors.",
                    "&fThe &cBlood Key &fopens the red door. Behind it waits something that counts you as you fight.",
                    "&fPick a class at the altar, find a party at the board, and walk through the arch when you are ready."),
                    () -> DungeonMenus.main(p));
            else DungeonMenus.main(p);
        });

        NpcDef board = NpcDef.of("dungeon_party_board", "&eParty Board", "dungeon_hub", "npc.party_board").title("&e&lPARTY FINDER");
        board.lines.add("&7Find a group");
        Sky.npcs().register(board, PartyFinder::open);

        NpcDef altar = NpcDef.of("dungeon_class_altar", "&dClass Keeper Vesna", "dungeon_hub", "npc.class_altar").title("&d&lCLASSES");
        altar.lines.add("&7Choose your class");
        Sky.npcs().register(altar, DungeonMenus::classes);

        NpcDef shop = NpcDef.of("dungeon_provisioner", "&aProvisioner Margit", "dungeon_hub", "npc.provisioner").title("&a&lSHOP");
        shop.lines.add("&7Dungeon supplies");
        Sky.npcs().register(shop, DungeonItems::shop);

        NpcDef mort = NpcDef.of("dungeon_mort", "&fSexton Abel", "catacombs", "npc.mort").title("&e&lRUN INFO");
        Sky.npcs().register(mort, DungeonMenus::runInfo);
    }

    private void commands() {
        Sky.commands().register(SkyCommand.of("dungeons", "Open the Catacombs menu (/dungeons gate|classes|stats|shop|leave|info|hub|chests|rng|essence|stock|salvage|scrolls|bosses)")
                .alias("catacombs", "cata", "dungeon").category("Dungeons").playerOnly().usage("[gate|classes|stats|shop|leave|info|hub]")
                .run((s, a) -> playerCommand((Player) s, a))
                .tab((s, a) -> a.length <= 1 ? List.of("gate", "classes", "stats", "shop", "leave", "info", "hub", "start", "chests", "rng", "essence", "stock", "salvage", "scrolls", "bosses") : List.of()));
        Sky.commands().register(SkyCommand.of("dungeonhub", "Warp to the Dungeon Hub").alias("dh").category("Dungeons").playerOnly()
                .run((s, a) -> Sky.worlds().warp((Player) s, "dungeon_hub")));
        Sky.commands().register(SkyCommand.of("class", "Choose your dungeon class").category("Dungeons").playerOnly()
                .run((s, a) -> DungeonMenus.classes((Player) s)));
    }

    private static void playerCommand(Player p, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase();
        switch (sub) {
            case "gate", "floors", "enter" -> {
                if (!Sky.requirements().checkOrTell(p, DDials.ENTRANCE_REQ.get())) return;
                DungeonMenus.entrance(p);
            }
            case "start" -> {
                FloorDef f = a.length > 1 ? Floors.get(a[1]) : null;
                if (f == null) { Msg.error(p, "Usage: /dungeons start <E|F1..F7|M1..M7>"); return; }
                DungeonMenus.confirmStart(p, f);
            }
            case "classes", "class" -> DungeonMenus.classes(p);
            case "stats", "records" -> DungeonMenus.stats(p);
            case "shop" -> {
                if (!"dungeon_hub".equals(Sky.worlds().islandOf(p.getWorld()))) { Msg.error(p, "Visit the Provisioner in the Dungeon Hub."); return; }
                DungeonItems.shop(p);
            }
            case "leave", "quit" -> {
                DungeonRun r = RunManager.get().runOf(p);
                if (r == null) { Msg.error(p, "You are not in a dungeon run."); return; }
                RunManager.get().leave(p, true);
            }
            case "info", "run" -> DungeonMenus.runInfo(p);
            case "hub" -> Sky.worlds().warp(p, "dungeon_hub");
            default -> { if (!PartB.command(p, sub)) DungeonMenus.main(p); }
        }
    }
}
