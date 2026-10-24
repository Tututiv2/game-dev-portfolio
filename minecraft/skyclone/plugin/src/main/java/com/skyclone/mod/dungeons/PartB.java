package com.skyclone.mod.dungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.gen.MemoryCanvas;
import com.skyclone.mod.dungeons.boss.BossFloors;
import com.skyclone.mod.dungeons.boss.Devices;
import com.skyclone.mod.dungeons.classes.ClassSystem;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.ext.BossFloor;
import com.skyclone.mod.dungeons.ext.DungeonHooks;
import com.skyclone.mod.dungeons.gear.GearEffects;
import com.skyclone.mod.dungeons.gear.WitherBlades;
import com.skyclone.mod.dungeons.gen.Geo;
import com.skyclone.mod.dungeons.loot.LData;
import com.skyclone.mod.dungeons.loot.LootTables;
import com.skyclone.mod.dungeons.loot.RewardChests;
import com.skyclone.mod.dungeons.loot.RngMeterMenu;
import com.skyclone.mod.dungeons.model.FloorDef;
import com.skyclone.mod.dungeons.run.DungeonRun;
import com.skyclone.mod.dungeons.run.RunManager;
import com.skyclone.mod.dungeons.shop.EssenceShop;
import com.skyclone.mod.dungeons.shop.GatekeeperShop;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * DUNGEONS, part B (content; research 04 sec 3.13-3.16): the seven boss floors, dungeon gear and what it does, reward
 * chests (Wood..Bedrock, keys, Fate Feather rerolls, RNG meter, the Vault Keeper), the essence shop, the Gatekeeper's
 * stock and salvaging, the wither scrolls. Wired from {@link DungeonsModule#enable()}.
 */
public final class PartB {

    private PartB() {}

    /** Arena points each fight reads (self-test + unit test). */
    public static final Map<String, List<String>> NEEDS = Map.of(
            "F1", List.of("spawn", "boss", "chests", "centre", "pillar.0"),
            "F2", List.of("spawn", "boss", "chests", "drop", "barrier.a", "barrier.b", "minion.0", "minion.3"),
            "F3", List.of("spawn", "boss", "chests", "drop", "barrier.a", "barrier.b", "guardian.healthy", "guardian.reinforced", "guardian.chaos", "guardian.laser"),
            "F4", List.of("spawn", "boss", "chests", "circle.green.0", "circle.orange.1", "animal.0", "animal.7"),
            "F5", List.of("spawn", "boss", "chests", "ceiling.wool", "clone.0", "clone.8"),
            "F6", List.of("spawn", "boss", "chests", "drop", "pot.0", "pot.11", "giant.diamond", "giant.laser", "giant.bigfoot", "giant.boulder"),
            "F7", List.of("spawn", "boss", "chests", "p1.crystal.a", "p1.crystal.b", "p1.podium.a", "p1.podium.b", "p1.laser", "p2.spawn", "p2.boss",
                    "p2.pad.0", "p2.pad.2", "p2.crusher.0", "p2.crusher.2", "p3.spawn", "p3.boss", "p3.core", "p3.s1.term.0", "p3.s2.term.4",
                    "p3.s4.term.3", "p3.s1.lever.1", "p3.s4.device", "p3.s1.gate.a", "p3.s4.gate.b", "p3.s2.entry", "p4.spawn", "p4.boss",
                    "p5.spawn", "p5.king", "p5.statue.soul", "p5.cauldron.apex", "p5.relic.ice"));

    static void enable() {
        int items = Sky.items().loadJson("dungeons", "gear.json");
        items += Sky.items().loadJson("dungeons", "dyes.json");       // wave 5: Lurid / Morvane dyes (boss chest cosmetics)
        int mobs = Sky.mobs().loadJson("dungeons", "boss_mobs.json");
        int recipes = Sky.recipes().loadJson("dungeons", "gear_recipes.json");
        Sky.log().info("[dungeons] part B: " + items + " gear items, " + mobs + " boss mobs, " + recipes + " recipes");
        BossFloors.register();
        RewardChests.init();
        EssenceShop.init();
        GatekeeperShop.init();
        GearEffects.init();
        WitherBlades.init();
        RunManager.ON_ENTER = GearEffects::onEnterRun;
        ClassSystem.BLESSING_BONUS = p -> EssenceShop.level(p, "forbidden_blessing") / 100.0;
        npcs();
        buttons();
        tests();
    }

    // ---- NPCs and menu buttons --------------------------------------------------------------------------------

    private static void npcs() {
        NpcDef vault = NpcDef.of("dungeon_vault_keeper", "&6Vault Keeper Orlin", "dungeon_hub", "npc.croesus").title("&6&lUNCLAIMED CHESTS");
        vault.lines.add("&7Reward chests you left behind");
        Sky.npcs().register(vault, p -> {
            if (!Sky.profiles().isReady(p)) return;
            if (RewardChests.data(p).croesus.isEmpty() && !Sky.npcs().talking(p, "dungeon_vault_keeper"))
                Sky.npcs().talk(p, "dungeon_vault_keeper", List.of("&fEvery chest you walk away from down there ends up in my vault.",
                        "&fCome back within " + DDials.CROESUS_DAYS.get() + " days and I will open it for you. For the usual price, of course."), () -> RewardChests.croesus(p));
            else RewardChests.croesus(p);
        });
        NpcDef ess = NpcDef.of("dungeon_essence_keeper", "&dEssence Keeper Thessaly", "dungeon_hub", "npc.essence_shop").title("&d&lESSENCE SHOP");
        ess.lines.add("&7Undead and Wither perks");
        Sky.npcs().register(ess, EssenceShop::open);
    }

    private static void buttons() {
        DungeonHooks.menuButton("rng", 28, p -> Icons.of(Material.PAINTING, "&dRNG Meter", "&7Pick an item per floor; S runs fill", "&7the meter until it is guaranteed.", "",
                "&8/dungeons rng", "&eClick to view!"), RngMeterMenu::open);
        DungeonHooks.menuButton("croesus", 29, p -> {
            int n = Sky.profiles().isReady(p) ? RewardChests.data(p).croesus.size() : 0;
            return Icons.of(Material.CHEST_MINECART, "&6Unclaimed Chests", "&7Reward chests you did not open.", "", "&7Runs waiting: &e" + n,
                    "&8Opened only at the Vault Keeper", "&8in the Dungeon Hub, or here.", "", "&eClick to view!");
        }, RewardChests::croesus);
        DungeonHooks.menuButton("essence_shop", 30, p -> Icons.of(Material.ZOMBIE_HEAD, "&dEssence Shop", "&7Undead and Wither essence perks.", "",
                "&8/dungeons essence", "&eClick to open!"), EssenceShop::open);
        DungeonHooks.menuButton("gatekeeper", 32, p -> Icons.of(Material.EMERALD, "&aGatekeeper's Stock", "&7Fate Feathers, Chest Keys,", "&7salvaging.", "",
                "&8/dungeons stock", "&eClick to open!"), GatekeeperShop::open);
        DungeonHooks.menuButton("scrolls", 33, p -> Icons.of(Material.PAPER, "&5Scroll Altar", "&7Bind wither scrolls to a Warden's", "&7Blade or one of its refinements.", "",
                "&8/dungeons scrolls", "&eClick to open!"), WitherBlades::altar);
        DungeonHooks.menuButton("bosses", 34, p -> Icons.of(Material.WITHER_SKELETON_SKULL, "&cBoss Guide", "&7What every floor's boss does,", "&7and how to beat it.", "",
                "&8/dungeons bosses", "&eClick to read!"), PartB::bossGuide);
        DungeonHooks.menuButton("boss_collections", 37, p -> Icons.of(Material.BOOK, "&aBoss Collections", "&7Each boss kill counts toward its", "&7collection; tiers unlock chest loot.", "",
                "&eClick to view!"), p -> Sky.service(Features.Collections.class).openMenu(p));
    }

    /** {@code /dungeons <sub>} for part B; false = not ours. */
    static boolean command(Player p, String sub) {
        switch (sub) {
            case "chests", "croesus", "vault", "unclaimed" -> RewardChests.croesus(p);
            case "rng", "rngmeter" -> RngMeterMenu.open(p);
            case "essence", "essenceshop", "perks" -> EssenceShop.open(p);
            case "stock", "gatekeeper", "shop2" -> GatekeeperShop.open(p);
            case "salvage" -> GatekeeperShop.salvage(p);
            case "scrolls", "altar" -> WitherBlades.altar(p);
            case "bosses", "guide" -> bossGuide(p);
            default -> { return false; }
        }
        return true;
    }

    static final String[][] GUIDE = {
            {"F1", "Grinko", "&7A circus ring with seven pillars.", "&7He throws skulls and raises Circus Undead.",
                    "&7At zero health he &crevives once&7, faster.", "&7Then &cBalloon Barrage&7: hide behind a pillar."},
            {"F2", "Veilwright", "&7He watches from a barred balcony while", "&7four students fight: Warrior, Archer, Mage,",
                    "&7and the &6Priest &7(heals and raises the others:", "&7kill it first). Then he comes down himself."},
            {"F3", "The Lecturer", "&7Four guardians on pedestals: kill all four", "&7within &e10s &7of each other or they return",
                    "&8(longer in a smaller party: 250s solo).",
                    "&7Interrupt &aGiga Heal &7with 10 hits. Stay off", "&7the pedestals. At 25% he absorbs them."},
            {"F4", "Bramblemaw", "&7Only a &dSpirit Bow &7hurts it: 4 hits (6 on M4).", "&7Kill 25 spirit animals, then the Spirit Bear;",
                    "&7its bow appears in a player's hands.", "&7Green circles heal; orange ones burn animals."},
            {"F5", "Lurid", "&7After 16s he splits into nine colours.", "&7The real one matches the &ewool block &7in the",
                    "&7ceiling well. Killing a copy heals them all."},
            {"F6", "Gorrak", "&7Two minutes of terracottas (each kill takes", "&7a second off). Then four giants: sword line,",
                    "&7ramping laser, stomp ring, boulders.", "&7Then Gorrak himself with all four.",
                    "&4M6&7: Mutant Giants use every kit and rise", "&7again beside him until he falls."},
            {"F7", "The Wither Lords", "&dGravax&7: carry both crystals to the podiums,", "&7trap him in the laser (twice at least).",
                    "&bTempestra&7: drop crushers on her from the pads;", "&7shelter under an unused crusher from lightning.",
                    "&6Aurik&7: terminals, levers and a device open each", "&7gate; his core is behind the last one.",
                    "&4Morvane&7: jump the beams, hit him in his frenzy.", "&5M7&7: relics to cauldrons, dragons die by their statues;", "&7Husks and Apostles join every 40s."}};

    public static void bossGuide(Player p) {
        Menu m = new Menu("Boss Guide", 4).chrome();
        int slot = 10;
        for (String[] g : GUIDE) {
            List<String> lore = new ArrayList<>();
            FloorDef f = Floors.get(g[0]);
            if (f != null) lore.add("&7Health: &a" + Text.shortNum(f.bossHp) + " &8(Master: " + Text.shortNum(Floors.get("M" + g[0].substring(1)).bossHp) + ")");
            // wave-4 endgame: boss floors scale with party size
            lore.add("&8Solo: health x" + String.format("%.2f", DDials.SOLO_BOSS_HP.get()) + ", damage x" + String.format("%.2f", DDials.SOLO_BOSS_DMG.get())
                    + " &8(full at 5)");
            lore.add("");
            for (int i = 2; i < g.length; i++) lore.add(g[i]);
            m.set(slot++, Icons.of(Material.SKELETON_SKULL, "&c" + g[0] + " &7- &c" + g[1], lore));
        }
        m.set(31, Icons.back("Catacombs"), c -> DungeonMenus.main(c.player()));
        m.open(p);
    }

    // ---- admin ------------------------------------------------------------------------------------------------------

    /** {@code /sb dungeons <sub>} for part B; false = not ours. */
    static boolean admin(CommandSender s, String[] a) {
        switch (a[0].toLowerCase()) {
            case "chest", "chests" -> {
                Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (p == null || !Sky.profiles().isReady(p)) { say(s, "&cUsage: chests <player> <floor> [score]"); return true; }
                String err = RewardChests.grant(p, a.length > 2 ? a[2] : "F1", a.length > 3 ? (int) DungeonAdmin.parse(a[3]) : 300);
                say(s, err == null ? "&aGave " + p.getName() + " a stored run's chests (open them with /dungeons chests)." : "&c" + err);
            }
            case "loot" -> {
                FloorDef f = a.length > 1 ? Floors.get(a[1]) : Floors.get("F7");
                LootTables.Chest c = a.length > 2 ? LootTables.Chest.parse(a[2]) : LootTables.Chest.BEDROCK;
                LootTables.FloorLoot fl = f == null ? null : LootTables.floor(f.id);
                if (fl == null || c == null || fl.chest(c) == null) { say(s, "&cUsage: loot <floor> <wood|gold|...|bedrock> [rolls]"); return true; }
                int n = a.length > 3 ? (int) DungeonAdmin.parse(a[3]) : 5;
                Random r = new Random();
                for (int i = 0; i < Math.min(20, n); i++) {
                    LootTables.Rolled x = LootTables.roll(fl, c, r, e -> Sky.items().exists(e.item), 0, 0, null, 1);
                    say(s, "&7" + c.display + " #" + (i + 1) + ": &f" + String.join(", ", x.items) + " &6" + Text.commas(x.cost) + " &d" + x.undead + "u/" + x.wither + "w");
                }
            }
            case "rng" -> {
                Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (p == null || a.length < 4 || !Sky.profiles().isReady(p)) { say(s, "&cUsage: rng <player> <floor> <xp>"); return true; }
                FloorDef f = Floors.get(a[2]);
                if (f == null) { say(s, "&cUnknown floor."); return true; }
                RewardChests.data(p).rngXp.put(f.id, DungeonAdmin.parse(a[3]));
                say(s, "&aRNG meter of " + p.getName() + " on " + f.id + " = " + a[3]);
            }
            case "perk" -> {
                Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (p == null || a.length < 4 || !Sky.profiles().isReady(p) || EssenceShop.perk(a[2]) == null) { say(s, "&cUsage: perk <player> <perk id> <level>"); return true; }
                RewardChests.data(p).perks.put(a[2], (int) DungeonAdmin.parse(a[3]));
                Sky.stats().invalidate(p);
                say(s, "&aSet " + a[2] + " to " + a[3] + " for " + p.getName());
            }
            case "gear" -> {
                Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                String set = a.length > 2 ? a[2].toLowerCase() : "";
                if (p == null || set.isEmpty()) { say(s, "&cUsage: gear <player> <set id|scrolls|keys>"); return true; }
                int n = 0;
                if (set.equals("scrolls")) for (String id : List.of("IMPLOSION_SCROLL", "SHADOW_WARP_SCROLL", "WITHER_SHIELD_SCROLL", "NECRON_BLADE")) { if (Sky.items().exists(id)) { Sky.loot().give(p, id, 1, LootSource.ADMIN); n++; } }
                else if (set.equals("keys")) for (String id : List.of("KISMET_FEATHER", "DUNGEON_CHEST_KEY")) { if (Sky.items().exists(id)) { Sky.loot().give(p, id, 3, LootSource.ADMIN); n++; } }
                else for (ItemDef d : Sky.items().all()) if (set.equals(d.setId)) { Sky.loot().give(p, d.id, 1, LootSource.ADMIN); n++; }
                say(s, "&aGave " + n + " item(s).");
            }
            case "phase" -> {
                Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                DungeonRun r = p == null ? null : RunManager.get().runOf(p);
                if (r == null || r.bossContext() == null) { say(s, "&cUsage: phase <player> (in a boss fight): prints the fight state"); return true; }
                say(s, "&eBoss " + r.floor.id + " steps " + r.bossContext().steps() + " state " + r.bossContext().state());
            }
            default -> { return false; }
        }
        return true;
    }

    private static void say(CommandSender s, String m) { s.sendMessage(Text.c(m)); }

    // ---- self-tests -------------------------------------------------------------------------------------------------

    private static void tests() {
        Sky.commands().selfTest("dungeons.b-gear-loaded", () -> {
            List<String> ids = ids("gear.json");
            if (ids.size() < 100) return "only " + ids.size() + " gear defs in gear.json";
            for (String id : ids) {
                if (!Sky.items().exists(id)) return id + " not registered";
                ItemDef d = Sky.items().def(id);
                if (d.category.isArmor() && !d.dungeon) return id + " is not flagged dungeon (stars would not apply)";
            }
            return null;
        });
        Sky.commands().selfTest("dungeons.b-boss-mobs", () -> {
            for (String id : ids("boss_mobs.json")) if (Sky.mobs().def(id) == null) return id + " not registered";
            return null;
        });
        Sky.commands().selfTest("dungeons.b-arenas", () -> {
            for (Map.Entry<String, BossFloor> en : BossFloors.ALL.entrySet()) {
                for (boolean master : new boolean[]{false, true}) {
                    FloorDef f = Floors.get((master ? "M" : "F") + en.getKey().substring(1));
                    BossArena a = en.getValue().arena();
                    if (a.sizeX() > Geo.ARENA_MAX_X || a.sizeZ() > Geo.ARENA_MAX_Z) return a.id() + " too big";
                    BossArena.Points pts = new BossArena.Points();
                    a.build(new MemoryCanvas(), 0, Geo.FLOOR_Y, 0, 1, f, pts);
                    for (String need : NEEDS.get(en.getKey())) if (pts.get(need) == null) return a.id() + " lacks point " + need;
                }
            }
            return null;
        });
        Sky.commands().selfTest("dungeons.b-loot", () -> {
            for (FloorDef f : Floors.all()) {
                LootTables.FloorLoot fl = LootTables.floor(f.id);
                if (fl == null) return f.id + " has no loot table";
                if (fl.chest(LootTables.Chest.WOOD) == null || fl.chest(LootTables.Chest.GOLD) == null) return f.id + " lacks Wood/Gold chests";
                int ok = 0;
                for (LootTables.Entry e : fl.entries) if (Sky.items().exists(e.item)) ok++;
                if (ok < Math.min(5, fl.entries.size())) return f.id + ": only " + ok + " of " + fl.entries.size() + " loot items exist";
            }
            LootTables.FloorLoot f7 = LootTables.floor("F7");
            LootTables.Rolled r = LootTables.roll(f7, LootTables.Chest.BEDROCK, new Random(1), e -> true, 0, 0, "NECRON_HANDLE", 1);
            if (!r.items.contains("NECRON_HANDLE:1")) return "F7 bedrock cannot hold the Warden's Handle";
            if (f7.entry("NECRON_HANDLE").cost != 98_000_000) return "research: the handle costs 98M on top of the chest";
            if (f7.chest(LootTables.Chest.BEDROCK).cost != 2_000_000) return "research: F7 Bedrock base is 2M";
            if (LootTables.unlocked(f7, false, 250, 2).contains(LootTables.Chest.OBSIDIAN)) return "Obsidian must need an S (270)";
            if (!LootTables.unlocked(LootTables.floor("M7"), true, 100, 0).contains(LootTables.Chest.BEDROCK)) return "Master Mode grants every chest";
            return null;
        });
        Sky.commands().selfTest("dungeons.b-recipes", () -> {
            for (Recipe r : Sky.recipes().all()) {
                if (r.id == null || !r.id.startsWith("dungeons:")) continue;
                for (String cell : r.grid) {
                    if (cell == null || cell.isEmpty()) continue;
                    String id = Sky.items().exists(cell) ? cell : cell.substring(0, cell.lastIndexOf(':'));
                    if (!Sky.items().exists(id)) return r.id + " needs unknown " + id;
                }
            }
            return null;
        });
        Sky.commands().selfTest("dungeons.b-devices-solvable", () -> {
            Random r = new Random(7);
            for (int i = 0; i < 200; i++) {
                int[] sets = Devices.lampSets(r);
                int start = Devices.startState(sets, r);
                boolean solvable = false;
                for (int combo = 0; combo < 32 && !solvable; combo++) {
                    int st = start;
                    for (int k = 0; k < 5; k++) if ((combo >> k & 1) == 1) st ^= sets[k];
                    if (st == 0x1FF) solvable = true;
                }
                if (!solvable || start == 0x1FF) return "lights board " + i + " is not a puzzle";
                List<int[]> path = Devices.path(r);
                if (path.get(0)[0] != 0 || path.get(path.size() - 1)[0] != 4) return "arrow path does not cross the board";
            }
            return null;
        });
        Sky.commands().selfTest("dungeons.b-shops", () -> {
            for (EssenceShop.Perk k : EssenceShop.PERKS) {
                if (k.costs().length == 0) return k.id() + " has no levels";
                for (int i = 1; i < k.costs().length; i++) if (k.costs()[i] < k.costs()[i - 1]) return k.id() + " costs fall";
            }
            if (!Sky.items().exists("ESSENCE_UNDEAD") || !Sky.items().exists("ESSENCE_WITHER")) return "essence items are not registered (reforges)";
            if (GatekeeperShop.salvageTable().size() < 50) return "salvage table is nearly empty";
            for (String id : List.of("KISMET_FEATHER", "DUNGEON_CHEST_KEY", "IMPLOSION_SCROLL", "SHADOW_WARP_SCROLL", "WITHER_SHIELD_SCROLL"))
                if (!Sky.items().exists(id)) return id + " missing";
            return null;
        });
        // review: nothing the Gatekeeper or the Provisioner sells may be flipped into more coins than it cost - neither by
        // an instant sell on the bazaar nor by salvaging it into essence and selling that
        Sky.commands().selfTest("dungeons.b-no-coin-loops", () -> {
            Features.Bazaar bz = Sky.service(Features.Bazaar.class);
            for (String[] o : new String[][]{{"KISMET_FEATHER", "k"}, {"DUNGEON_CHEST_KEY", "c"}}) {
                if (!Sky.items().exists(o[0])) continue;
                double dial = o[1].equals("k") ? DDials.KISMET_PRICE.get() : DDials.CHEST_KEY_PRICE.get();
                double price = GatekeeperShop.price(o[0], dial);
                double sell = bz.isBazaarItem(o[0]) ? bz.instantSell(o[0]) : 0;
                if (sell >= price) return o[0] + ": the Gatekeeper sells at " + Text.commas(price) + " but the bazaar buys at " + Text.commas(sell);
                ItemDef d = Sky.items().def(o[0]);
                if (d != null && d.npcSell >= price) return o[0] + " NPC-sells for more than the Gatekeeper charges";
            }
            for (DungeonItems.Offer o : DungeonItems.SHOP) {
                if (o.coins() <= 0 || !Sky.items().exists(o.id())) continue;
                GatekeeperShop.Salvage sv = GatekeeperShop.salvageOf(Sky.items().create(o.id(), 1));
                if (sv == null) continue;
                double ess = bz.isBazaarItem("ESSENCE_" + sv.essence()) ? bz.instantSell("ESSENCE_" + sv.essence()) : 0;
                double back = sv.amount() * DDials.SALVAGE_SCALE.get() * ess * Math.max(1, o.amount());
                if (back >= o.coins()) return o.id() + ": bought for " + Text.commas(o.coins()) + ", salvages into essence worth " + Text.commas(back);
            }
            return null;
        });
        Sky.commands().selfTest("dungeons.b-bosses-registered", () -> {
            for (String k : List.of("F1", "F2", "F3", "F4", "F5", "F6", "F7"))
                if (!com.skyclone.mod.dungeons.ext.Bosses.all().containsKey(k)) return k + " has no boss floor";
            if (!com.skyclone.mod.dungeons.ext.RewardHook.custom()) return "reward chests are not the reward hook";
            return null;
        });
    }

    private static List<String> ids(String file) {
        List<String> out = new ArrayList<>();
        InputStream in = Sky.plugin().getResource("data/dungeons/" + file);
        if (in == null) return out;
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonArray arr = JsonParser.parseReader(r).getAsJsonArray();
            for (JsonElement e : arr) out.add(e.getAsJsonObject().get("id").getAsString());
        } catch (Exception e) { out.add("<unreadable " + file + ">"); }
        return out;
    }

    @SuppressWarnings("unused")
    private static void unused(LData d, Msg m) { }
}
