package com.skyclone.mod.dungeons.party;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.dungeons.DDials;
import com.skyclone.mod.dungeons.DungeonMenus;
import com.skyclone.mod.dungeons.Floors;
import com.skyclone.mod.dungeons.Progress;
import com.skyclone.mod.dungeons.model.DClass;
import com.skyclone.mod.dungeons.model.FloorDef;
import com.skyclone.mod.dungeons.run.RunManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Party Finder (research 04 sec 3.1): public listings {leader, floor, min Catacombs level, min class level, wanted
 * classes, note}, newest first, auto-delisted when full or when the run starts. {@code /pf} or the board in the
 * Dungeon Hub. Joining adds you to the leader's party directly.
 */
public final class PartyFinder {

    private PartyFinder() {}

    public static final class Listing {
        public final UUID leader;
        public FloorDef floor;
        public int minCata;
        public int minClass;
        public final Set<DClass> wanted = EnumSet.noneOf(DClass.class);
        public String note = "";
        public final long created = System.currentTimeMillis();
        Listing(UUID leader, FloorDef floor) { this.leader = leader; this.floor = floor; }
    }

    private static final List<Listing> LISTINGS = new CopyOnWriteArrayList<>();

    public static void init() {
        Sky.commands().register(SkyCommand.of("pf", "Open the Party Finder").alias("partyfinder").category("Dungeons").playerOnly()
                .run((s, a) -> open((Player) s)));
    }

    public static List<Listing> listings() { return List.copyOf(LISTINGS); }

    static void delist(Party party, String why) {
        if (party.listing == null) return;
        LISTINGS.remove(party.listing);
        party.listing = null;
        for (Player m : party.online()) Msg.send(m, "&eYour Party Finder listing was removed (" + why + ").");
    }

    /** Called when a run starts. */
    public static void delistFor(UUID leader) {
        Party party = Parties.get().of(leader);
        if (party != null) delist(party, "the run started");
        LISTINGS.removeIf(l -> l.leader.equals(leader));
    }

    // ---- menus --------------------------------------------------------------------------------

    public static void open(Player p) { browse(p, 0); }

    static void browse(Player p, int page) {
        List<Listing> list = new ArrayList<>(LISTINGS);
        list.sort((a, b) -> Long.compare(b.created, a.created));
        Menu m = Sky.menus().paged("Party Finder", list, l -> icon(p, l), pc -> join(pc.click().player(), pc.entry()), menu -> {
            menu.set(48, Icons.back("Catacombs"), c -> DungeonMenus.main(c.player()));
            Party own = Parties.get().of(p);
            boolean listed = own != null && own.listing != null;
            menu.set(50, Icons.of(listed ? Material.BARRIER : Material.WRITABLE_BOOK, listed ? "&cRemove your listing" : "&aCreate a Listing",
                    listed ? List.of("&7Take your party off the board.", "", "&eClick to remove!")
                            : List.of("&7List your party so others can join.", "&7You choose the floor, a minimum", "&7Catacombs level and a note.", "", "&eClick to create!")),
                    c -> {
                        Party pr = Parties.get().of(c.player());
                        if (pr != null && pr.listing != null) { delist(pr, "removed"); browse(c.player(), 0); }
                        else create(c.player());
                    });
            menu.set(4, Icons.of(Material.BOOKSHELF, "&aParty Finder", "&7Groups looking for players.", "",
                    "&7Listings: &a" + list.size(), "&7Your Catacombs level: &c" + Progress.cataLevel(p)));
        }, page);
        m.open(p);
    }

    private static ItemStack icon(Player viewer, Listing l) {
        Party party = Parties.get().of(l.leader);
        int size = party == null ? 1 : party.size();
        List<String> lore = new ArrayList<>();
        lore.add("&7Floor: &b" + l.floor.display());
        lore.add("&7Note: &f" + (l.note.isBlank() ? "-" : l.note));
        lore.add("&7Min Catacombs: &c" + l.minCata + (l.minClass > 0 ? " &7| Min class: &a" + l.minClass : ""));
        if (!l.wanted.isEmpty()) {
            StringBuilder w = new StringBuilder();
            for (DClass c : l.wanted) w.append(w.length() == 0 ? "" : "&7, ").append(c.colored());
            lore.add("&7Looking for: " + w);
        }
        lore.add("");
        lore.add("&7Members: &a" + size + "&7/&a" + DDials.PARTY_MAX.get());
        if (party != null)
            for (UUID u : party.members) {
                Player m = Bukkit.getPlayer(u);
                if (m == null) continue;
                DClass c = Sky.profiles().isReady(m) ? Progress.selected(m) : DClass.BERSERK;
                lore.add(" &8- &b" + m.getName() + " &7(" + c.colored() + " " + (Sky.profiles().isReady(m) ? Progress.classLevel(m, c) : 0) + "&7)");
            }
        lore.add("");
        String why = joinBlock(viewer, l);
        lore.add(why == null ? "&eClick to join!" : "&c" + why);
        Player leader = Bukkit.getPlayer(l.leader);
        String name = (leader != null ? leader.getName() : "?") + "'s Party";
        return leader != null ? Icons.playerHead(leader.getUniqueId(), "&a" + name, lore) : Icons.of(Material.PLAYER_HEAD, "&a" + name, lore);
    }

    private static String joinBlock(Player p, Listing l) {
        Party party = Parties.get().of(l.leader);
        if (party == null) return "This party no longer exists.";
        if (party.members.contains(p.getUniqueId())) return "This is your party.";
        if (party.size() >= DDials.PARTY_MAX.get()) return "Full!";
        if (!Sky.profiles().isReady(p)) return "Loading...";
        if (Progress.cataLevel(p) < l.minCata) return "Requires Catacombs " + l.minCata + "!";
        if (l.minClass > 0 && Progress.classLevel(p, Progress.selected(p)) < l.minClass) return "Your class needs level " + l.minClass + "!";
        if (!DungeonMenus.floorUnlocked(p, l.floor)) return "You cannot enter this floor yet!";
        return null;
    }

    static void join(Player p, Listing l) {
        String why = joinBlock(p, l);
        if (why != null) { Msg.error(p, why); return; }
        Party party = Parties.get().of(l.leader);
        if (party != null && Parties.get().join(p, party)) p.closeInventory();
    }

    static void create(Player p) {
        if (RunManager.get().runOf(p) != null) { Msg.error(p, "You are in a run."); return; }
        Party party = Parties.get().ensure(p);
        if (!party.isLeader(p)) { Msg.error(p, "Only the party leader can list the party."); return; }
        List<FloorDef> floors = new ArrayList<>();
        for (FloorDef f : Floors.all()) if (DungeonMenus.floorUnlocked(p, f)) floors.add(f);
        if (floors.isEmpty()) { Msg.error(p, "You cannot enter any floor yet: " + Text.strip(Sky.requirements().describe(DDials.ENTRANCE_REQ.get()))); return; }
        Menu m = new Menu("Choose a Floor", 4).chrome();
        int slot = 10;
        for (FloorDef f : floors) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= 35) break;
            m.set(slot++, Icons.of(f.master ? Material.WITHER_SKELETON_SKULL : f.isEntrance() ? Material.IRON_DOOR : Material.SKELETON_SKULL,
                    "&a" + f.display(), "&7List your party for this floor.", "", "&eClick to choose!"), c -> configure(c.player(), f));
        }
        DungeonMenus.backTo(m, "Party Finder", q -> browse(q, 0));
        m.open(p);
    }

    static void configure(Player p, FloorDef floor) {
        Party party = Parties.get().ensure(p);
        Listing l = party.listing != null ? party.listing : new Listing(p.getUniqueId(), floor);
        l.floor = floor;
        settings(p, l, party);
    }

    private static void settings(Player p, Listing l, Party party) {
        Menu m = new Menu("Listing: " + l.floor.display(), 5).chrome();
        m.set(4, Icons.of(Material.SKELETON_SKULL, "&a" + l.floor.display(), "&7The floor you will run."));
        m.set(19, Icons.of(Material.EXPERIENCE_BOTTLE, "&aMin Catacombs: &c" + l.minCata, "&7Left-click +1, right-click -1.", "&7Shift: +/- 5."),
                c -> { l.minCata = Math.max(0, Math.min(50, l.minCata + (c.right() ? -1 : 1) * (c.shift() ? 5 : 1))); settings(c.player(), l, party); });
        m.set(20, Icons.of(Material.IRON_SWORD, "&aMin Class Level: &a" + l.minClass, "&7Left-click +1, right-click -1.", "&7Shift: +/- 5."),
                c -> { l.minClass = Math.max(0, Math.min(50, l.minClass + (c.right() ? -1 : 1) * (c.shift() ? 5 : 1))); settings(c.player(), l, party); });
        m.set(22, Icons.of(Material.NAME_TAG, "&aNote: &f" + (l.note.isBlank() ? "-" : l.note), "&7A short line for the board.", "", "&eClick to write!"),
                c -> Sky.menus().input(c.player(), "Party note", s -> {
                    if (s != null) l.note = Text.strip(s).replaceAll("[^\\p{L}\\p{N} .,!?'+\\-:/()]", "").trim();
                    if (l.note.length() > 40) l.note = l.note.substring(0, 40);
                    settings(c.player(), l, party);
                }));
        int slot = 29;
        for (DClass dc : DClass.values()) {
            boolean on = l.wanted.contains(dc);
            m.set(slot++, Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE, dc.colored() + (on ? " &a(wanted)" : " &7(any)"), "&eClick to toggle!"),
                    c -> { if (!l.wanted.remove(dc)) l.wanted.add(dc); settings(c.player(), l, party); });
        }
        m.set(25, Icons.of(Material.LIME_CONCRETE, "&aList Party", "&7Put your party on the board.", "", "&eClick to list!"), c -> {
            if (!party.isLeader(c.player())) { Msg.error(c.player(), "Only the leader can do that."); return; }
            if (party.listing == null) { party.listing = l; LISTINGS.add(l); }
            Msg.success(c.player(), "Your party is listed in the Party Finder for " + l.floor.display() + ".");
            browse(c.player(), 0);
        });
        DungeonMenus.backTo(m, "Party Finder", q -> browse(q, 0));
        m.open(p);
    }
}
