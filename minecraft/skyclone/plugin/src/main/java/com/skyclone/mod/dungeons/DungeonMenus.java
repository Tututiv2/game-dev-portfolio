package com.skyclone.mod.dungeons;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.dungeons.ext.Bosses;
import com.skyclone.mod.dungeons.ext.DungeonHooks;
import com.skyclone.mod.dungeons.model.CataMath;
import com.skyclone.mod.dungeons.model.DClass;
import com.skyclone.mod.dungeons.model.FloorDef;
import com.skyclone.mod.dungeons.party.Parties;
import com.skyclone.mod.dungeons.party.Party;
import com.skyclone.mod.dungeons.party.PartyFinder;
import com.skyclone.mod.dungeons.puzzle.PuzzleBuilders;
import com.skyclone.mod.dungeons.run.DungeonRun;
import com.skyclone.mod.dungeons.run.RunManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Every dungeons menu: the Catacombs main menu ({@code /dungeons}), the floor selector (the gate), classes, floor
 * stats, the in-run info screen, settings. Reachable from the SkyBlock Menu through the menus module's link to
 * {@code /dungeons}.
 */
public final class DungeonMenus {

    private DungeonMenus() {}

    // ---- floor gating -------------------------------------------------------------------------

    public static boolean floorUnlocked(Player p, FloorDef f) { return lockReason(p, f) == null; }

    /** Null when the player may enter the floor, else the missing requirement ('&amp;' text). */
    public static String lockReason(Player p, FloorDef f) {
        if (DDials.IGNORE_REQS.get()) return null;
        if (!Sky.profiles().isReady(p)) return "&cLoading...";
        String entry = DDials.ENTRANCE_REQ.get();
        if (entry != null && !entry.isBlank() && !Sky.requirements().check(p, entry)) return "&cRequires " + Sky.requirements().describe(entry);
        if (Progress.cataLevel(p) < f.cataReq) return "&cRequires &aCatacombs Level " + f.cataReq;
        if (f.requirement != null && !f.requirement.isBlank() && !Sky.requirements().check(p, f.requirement))
            return "&cRequires " + Sky.requirements().describe(f.requirement);
        // wave 5: the reference's floor gate - each floor needs the one below it (Master floors the Master floor below)
        String prev = com.skyclone.mod.dungeons.model.DungeonPerks.previousFloor(f.id);
        if (prev != null && DDials.REQUIRE_PREVIOUS_FLOOR.get() && Progress.data(p).completions.getOrDefault(prev, 0) <= 0) {
            FloorDef pf = Floors.get(prev);
            return "&cRequires completing " + (pf == null ? prev : pf.display());
        }
        return null;
    }

    public static void backTo(Menu m, String where, java.util.function.Consumer<Player> to) {
        m.set(m.closeSlot() - 1, Icons.back(where), c -> to.accept(c.player()));
    }

    // ---- main ---------------------------------------------------------------------------------

    public static void main(Player p) {
        if (!Sky.profiles().isReady(p)) { Msg.error(p, "Your profile is still loading."); return; }
        DData d = Progress.data(p);
        Menu m = new Menu("The Catacombs", 6).chrome();
        int lvl = Progress.cataLevel(p);
        double prog = CataMath.progress(d.cataXp, Progress.cap());
        m.set(4, Icons.of(Material.WITHER_SKELETON_SKULL, "&cThe Catacombs &8- &cLevel " + lvl,
                "&7Clear dungeon floors to earn Catacombs", "&7experience. Each level raises the stats", "&7of dungeon gear inside the Catacombs.", "",
                "&7Dungeon stat boost: &c+" + Text.commas(CataMath.statBoostPercent(lvl)) + "%",
                "&7Progress: " + Text.bar(prog, 20) + " &e" + Text.commasInt(CataMath.into(d.cataXp, Progress.cap())) + "&6/&e" + Text.shortNum(CataMath.next(d.cataXp, Progress.cap())),
                "&7Total XP: &3" + Text.commasInt(d.cataXp),
                "&7Selected class: " + Progress.selected(p).colored() + " " + Progress.classLevel(p, Progress.selected(p)),
                "&7Class average: &a" + Text.commas(Math.floor(Progress.classAverage(p) * 10) / 10)));
        DungeonRun run = RunManager.get().runOf(p);
        m.set(10, Icons.of(Material.IRON_DOOR, "&aCatacombs Gate", "&7Pick a floor and start a run", "&7for your party (or alone).", "",
                run != null ? "&cYou are in a run right now!" : "&eClick to choose a floor!"), c -> entrance(c.player()));
        m.set(12, Icons.of(Material.matchMaterial(Progress.selected(p).icon) == null ? Material.IRON_SWORD : Material.matchMaterial(Progress.selected(p).icon),
                "&aDungeon Classes", "&7Healer, Mage, Berserk, Archer, Tank.", "", "&7Selected: " + Progress.selected(p).colored(), "", "&eClick to view!"), c -> classes(c.player()));
        m.set(14, Icons.of(Material.BOOKSHELF, "&aParty Finder", "&7Join a group, or list your own.", "", "&7Listings: &a" + PartyFinder.listings().size(), "", "&eClick to browse!"),
                c -> PartyFinder.open(c.player()));
        Party party = Parties.get().of(p);
        List<String> pl = new ArrayList<>();
        if (party == null || party.size() <= 1) { pl.add("&7You are not in a party."); pl.add("&7Invite friends: &b/party invite <name>"); }
        else for (UUID u : party.members) pl.add((u.equals(party.leader) ? "&6★ " : "&7- ") + "&b" + org.bukkit.Bukkit.getOfflinePlayer(u).getName());
        pl.add("");
        pl.add("&eClick for party commands!");
        m.set(16, Icons.of(Material.PLAYER_HEAD, "&aYour Party", pl), c -> { c.player().closeInventory(); Parties.get().list(c.player()); });
        m.set(19, Icons.of(Material.PAPER, "&aFloor Records", "&7Completions, best scores and fastest", "&7S runs for every floor.", "", "&eClick to view!"), c -> stats(c.player()));
        m.set(21, Icons.of(Material.NETHER_STAR, "&aEssence & Stars", "&7Upgrade dungeon gear with essence.", "", "&8/essence", "&eClick to open!"),
                c -> { if (!Sky.commands().run(c.player(), "essence")) Msg.error(c.player(), "Essence upgrades are not available right now."); });
        boolean inHub = "dungeon_hub".equals(Sky.worlds().islandOf(p.getWorld()));
        m.set(23, Icons.of(Material.BREWING_STAND, "&aProvisioner", "&7Megablast TNT, Soul Leaps, Decoys,", "&7Dungeon Potions and gear upgrades.", "",
                inHub ? "&eClick to shop!" : "&cOnly in the Dungeon Hub!"), c -> { if (inHub) DungeonItems.shop(c.player()); else Msg.error(c.player(), "Visit the Provisioner in the Dungeon Hub."); });
        m.set(25, Icons.of(Material.ENDER_PEARL, "&aWarp to the Dungeon Hub", "&7Where parties gather.", "", "&8/warp dungeon_hub", "&eClick to warp!"),
                c -> { c.player().closeInventory(); Sky.worlds().warp(c.player(), "dungeon_hub"); });
        if (run != null) m.set(31, Icons.of(Material.CLOCK, "&cCurrent Run", "&7" + run.floor.display(), "", "&eClick for run info!"), c -> runInfo(c.player()));
        Set<Integer> used = new HashSet<>();
        for (DungeonHooks.Button b : DungeonHooks.buttons()) {
            int slot = b.slot();
            int[] free = {28, 29, 30, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
            boolean ok = slot >= 0 && slot < 54 && !used.contains(slot) && java.util.Arrays.stream(free).anyMatch(x -> x == b.slot()) && !(run != null && slot == 31);
            if (!ok) { slot = -1; for (int f : free) if (!used.contains(f)) { slot = f; break; } }
            if (slot < 0) break;
            used.add(slot);
            ItemStack icon;
            try { icon = b.icon().apply(p); } catch (RuntimeException e) { continue; }
            m.set(slot, icon, c -> b.click().accept(c.player()));
        }
        if (Sky.has(Features.SkyMenu.class)) m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        m.set(50, Icons.of(Material.REDSTONE_TORCH, "&aSettings", "&7Dungeon sidebar: " + (d.hideSidebar ? "&cHidden" : "&aShown"), "", "&eClick to toggle!"), c -> {
            DData dd = Progress.data(c.player());
            dd.hideSidebar = !dd.hideSidebar;
            main(c.player());
        });
        m.open(p);
    }

    // ---- the gate: floors ----------------------------------------------------------------------

    public static void entrance(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        DData d = Progress.data(p);
        Menu m = new Menu("Catacombs Gate", 6).chrome();
        m.set(4, Icons.of(Material.SKELETON_SKULL, "&cThe Catacombs", "&7Choose a floor. Your whole party", "&7is brought in together.", "",
                "&7Catacombs level: &c" + Progress.cataLevel(p), "&7Class: " + Progress.selected(p).colored()));
        List<FloorDef> normal = Floors.normal(), master = Floors.masters();
        int[] nSlots = {19, 20, 21, 22, 23, 24, 25, 13};
        for (int i = 0; i < normal.size() && i < 8; i++) {
            FloorDef f = normal.get(i);
            int slot = f.isEntrance() ? 13 : nSlots[i - 1];
            m.set(slot, floorIcon(p, d, f), c -> confirmStart(c.player(), f));
        }
        boolean mmVisible = d.completions.getOrDefault("F7", 0) > 0 || DDials.IGNORE_REQS.get();
        for (int i = 0; i < master.size() && i < 7; i++) {
            FloorDef f = master.get(i);
            if (!mmVisible) {
                m.set(37 + i, Icons.of(Material.GRAY_DYE, "&8Master Mode " + Text.roman(f.number), "&7Complete &cFloor VII &7to reveal", "&7Master Mode."));
                continue;
            }
            m.set(37 + i, floorIcon(p, d, f), c -> confirmStart(c.player(), f));
        }
        backTo(m, "The Catacombs", DungeonMenus::main);
        m.open(p);
    }

    private static ItemStack floorIcon(Player p, DData d, FloorDef f) {
        String lock = lockReason(p, f);
        List<String> lore = new ArrayList<>();
        lore.add("&7Map: &a" + f.gridW + "x" + f.gridH + " rooms &8| &7Puzzles: &d" + f.puzzlesMin + (f.puzzlesMax > f.puzzlesMin ? "-" + f.puzzlesMax : ""));
        lore.add("&7Boss: " + (f.bossName == null ? "&8none (the Blood room)" : "&c" + f.bossName + (Bosses.hasCustom(f) ? "" : " &8(stand-in fight)")));
        lore.add("&7Required: &cCatacombs " + f.cataReq + (f.requirement != null && !f.requirement.isBlank() ? " &7+ " + Sky.requirements().describe(f.requirement) : ""));
        lore.add("&7Secrets needed: &b" + Math.round(f.secretPct * 100) + "% &8| &7Speed limit: &e" + Text.duration(f.speedLimitSec));
        lore.add("");
        lore.add("&7Completions: &a" + d.completions.getOrDefault(f.id, 0));
        lore.add("&7Best score: &e" + (d.bestScore.containsKey(f.id) ? d.bestScore.get(f.id) + " (" + CataMath.grade(d.bestScore.get(f.id)) + ")" : "-"));
        if (d.fastest.containsKey(f.id)) lore.add("&7Fastest S run: &e" + Text.duration(d.fastest.get(f.id)));
        lore.add("&7Base XP: &3" + Text.commas(f.baseXp));
        lore.add("");
        lore.add(lock == null ? "&eClick to enter!" : lock);
        Material mat = f.isEntrance() ? Material.IRON_DOOR : f.master ? Material.WITHER_SKELETON_SKULL : Material.SKELETON_SKULL;
        ItemStack it = Icons.of(mat, (lock == null ? "&a" : "&c") + f.display(), lore);
        if (lock == null && d.completions.getOrDefault(f.id, 0) == 0 && !f.master) Icons.glow(it);
        return Icons.amount(it, Math.max(1, f.number));
    }

    static void confirmStart(Player p, FloorDef f) {
        if (RunManager.get().runOf(p) != null) { Msg.error(p, "You are already in a run."); return; }
        String lock = lockReason(p, f);
        if (lock != null) { Msg.error(p, Text.strip(lock)); return; }
        Party party = Parties.get().of(p);
        if (party != null && party.size() > 1 && !party.isLeader(p)) { Msg.error(p, "Only the party leader can start a run."); return; }
        int size = party == null ? 1 : party.size();
        Sky.menus().confirm(p, "Enter " + f.display() + "?", List.of("&7Party size: &a" + size, size == 1 ? "&7You will go in &esolo&7." : "&7Everyone comes with you."),
                () -> { p.closeInventory(); RunManager.get().start(p, f, false); }, null);
    }

    // ---- classes ----------------------------------------------------------------------------------

    public static void classes(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        DData d = Progress.data(p);
        Menu m = new Menu("Dungeon Classes", 6).chrome();
        DClass sel = Progress.selected(p);
        m.set(4, Icons.of(Material.ARMOR_STAND, "&aDungeon Classes", "&7Pick the class you play in your next", "&7run. Your selected class earns the full",
                "&7class XP; the others earn &a" + Math.round(DDials.CLASS_OTHER_SHARE.get() * 100) + "%&7.", "",
                "&7Class average: &a" + Text.commas(Math.floor(Progress.classAverage(p) * 10) / 10)));
        int slot = 20;
        for (DClass c : DClass.values()) {
            double xp = d.classXp.getOrDefault(c.key(), 0.0);
            int lvl = CataMath.level(xp, Progress.cap());
            List<String> lore = new ArrayList<>();
            lore.add("&7Level &a" + lvl + " &8| " + Text.bar(CataMath.progress(xp, Progress.cap()), 15));
            lore.add("");
            lore.add("&6Passives");
            lore.addAll(c.passives);
            lore.add("");
            lore.addAll(c.abilities);
            lore.add("&8Ability cooldown " + c.abilityCooldown + "s, Ultimate " + c.ultimateCooldown + "s");
            if (DDials.DUPLICATE_CLASS_SCALE.get() < 1)
                lore.add("&8Two of one class in a run: passives at " + Math.round(DDials.DUPLICATE_CLASS_SCALE.get() * 100) + "%");
            lore.add("");
            lore.add(c == sel ? "&aSELECTED" : "&eClick to select!");
            Material mat = Material.matchMaterial(c.icon);
            ItemStack it = Icons.of(mat == null ? Material.IRON_SWORD : mat, c.colored() + " &7Lv" + lvl, lore);
            if (c == sel) Icons.glow(it);
            m.set(slot++, it, ck -> {
                if (RunManager.get().runOf(ck.player()) != null) { Msg.error(ck.player(), "You cannot change class during a run."); return; }
                Progress.data(ck.player()).selectedClass = c.key();
                Msg.success(ck.player(), "You selected the " + c.display + " class!");
                classes(ck.player());
            });
        }
        backTo(m, "The Catacombs", DungeonMenus::main);
        m.open(p);
    }

    // ---- records ---------------------------------------------------------------------------------

    public static void stats(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        DData d = Progress.data(p);
        Menu m = new Menu("Floor Records", 6).chrome();
        int slot = 10;
        for (FloorDef f : Floors.all()) {
            while (slot % 9 == 0 || slot % 9 == 8) slot++;
            int comp = d.completions.getOrDefault(f.id, 0);
            Integer best = d.bestScore.get(f.id);
            m.set(slot++, Icons.amount(Icons.of(comp > 0 ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, "&a" + f.display(),
                    "&7Completions: &a" + comp,
                    "&7Best score: &e" + (best == null ? "-" : best + " (" + CataMath.grade(best) + ")"),
                    "&7Fastest S run: &e" + (d.fastest.containsKey(f.id) ? Text.duration(d.fastest.get(f.id)) : "-")), Math.max(1, Math.min(64, comp))));
        }
        m.set(49 - 9, Icons.of(Material.BOOK, "&aLifetime", "&7Runs started: &a" + d.runsStarted, "&7Runs failed: &c" + d.runsFailed,
                "&7Secrets found: &b" + d.totalSecrets, "&7Crypts opened: &6" + d.cryptsOpened, "&7Puzzles solved: &d" + d.puzzlesSolved, "&7Deaths: &c" + d.totalDeaths,
                "", "&7General's Medallion: " + (com.skyclone.mod.dungeons.classes.ClassSystem.medallion(p) > 0
                        ? "&a+" + com.skyclone.mod.dungeons.classes.ClassSystem.medallion(p) + "% &7dungeon gear base stats"
                        : "&8not owned &7(+1% per digit of secrets found)")));
        backTo(m, "The Catacombs", DungeonMenus::main);
        m.open(p);
    }

    // ---- in a run ---------------------------------------------------------------------------------

    public static void runInfo(Player p) {
        DungeonRun r = RunManager.get().runOf(p);
        if (r == null || r.bp == null) { main(p); return; }
        Menu m = new Menu(r.floor.display(), 5).chrome();
        int[] s = r.finalScore != null ? r.finalScore : r.score(false);
        String g = CataMath.grade(s[4]);
        m.set(4, Icons.of(Material.CLOCK, "&c" + r.floor.display(), "&7Time: &a" + Text.duration(r.elapsedSeconds()) + " &8/ &7limit " + Text.duration(r.floor.speedLimitSec),
                "&7State: &e" + r.state.name().toLowerCase(), "",
                "&7Score: " + CataMath.gradeColor(g) + s[4] + " (" + g + ")",
                "&7  Skill &a" + s[0], "&7  Exploration &a" + s[1], "&7  Speed &a" + s[2], "&7  Bonus &a" + s[3]));
        m.set(20, Icons.of(Material.MAP, "&aRooms", "&7Cleared: &a" + r.clearedPercent() + "%", "&7Rooms: &a" + r.rooms.length));
        m.set(21, Icons.of(Material.CHEST, "&bSecrets", "&7Found: &b" + r.secretsFound + "/" + r.bp.totalSecrets(),
                "&7Needed for full score: &b" + (int) Math.ceil(r.floor.secretPct * r.bp.totalSecrets())));
        m.set(22, Icons.of(Material.COAL_BLOCK, "&8Keys", "&7Wither Keys: &8" + r.witherKeys, "&7Blood Key: " + (r.bloodKey ? "&a✔" : "&c✖"),
                "&7Blood door: " + (r.bloodOpen ? "&aopen" : "&clocked")));
        List<String> puz = new ArrayList<>();
        for (DungeonRun.RoomRt rr : r.rooms) if (rr.room.puzzle != null)
            puz.add((rr.failed ? "&c✖ " : rr.cleared ? "&a✔ " : rr.entered ? "&e? " : "&7? ") + "&f" + (rr.entered ? PuzzleBuilders.name(rr.room.puzzle) : "Unknown puzzle"));
        m.set(23, Icons.of(Material.PURPLE_DYE, "&dPuzzles", puz.isEmpty() ? List.of("&7None") : puz));
        List<String> bl = new ArrayList<>();
        r.blessings.forEach((k, v) -> bl.add("&d" + Character.toUpperCase(k.charAt(0)) + k.substring(1) + " " + Text.roman(v)));
        m.set(24, Icons.of(Material.BEACON, "&dBlessings", bl.isEmpty() ? List.of("&7None yet") : bl));
        m.set(40, Icons.of(Material.OAK_DOOR, "&cLeave the Run", "&7Return to the Dungeon Hub.", r.state == DungeonRun.State.COMPLETE ? "" : "&cYou will not get rewards.", "", "&eClick to leave!"),
                c -> Sky.menus().confirm(c.player(), "Leave the run?", List.of("&7Back to the Dungeon Hub."), () -> RunManager.get().leave(c.player(), true), null));
        m.open(p);
    }
}
