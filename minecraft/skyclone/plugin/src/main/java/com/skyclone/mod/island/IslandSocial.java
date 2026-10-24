package com.skyclone.mod.island;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Wave 5b - the Social road's island features: the Island Decorations screen (what the decorator's goods are doing on
 * this island), Island Ranks (Social 9) and Island Naming (Social 20). Commands: {@code /is decor}, {@code /is name},
 * {@code /is rank}, {@code /is ranks}, {@code /is parkour}.
 */
final class IslandSocial {

    private final IslandModule mod;
    private final IslandService svc;
    private final IslandDecorations decor;

    IslandSocial(IslandModule mod, IslandService svc, IslandDecorations decor) {
        this.mod = mod;
        this.svc = svc;
        this.decor = decor;
    }

    private IslandDials dials() { return svc.dials(); }

    // =========================================================================================
    // Social level gates
    // =========================================================================================

    static String socialReq(int level) { return "skill:social:" + Math.max(1, level); }

    /** Has the Social level (an unknown requirement prefix fails closed, so a server without skills shows the gate). */
    private static boolean social(Player p, int level) {
        if (level <= 0) return true;
        try { return Sky.requirements().check(p, socialReq(level)); } catch (RuntimeException e) { return false; }
    }

    // =========================================================================================
    // Island Decorations
    // =========================================================================================

    Menu decorations(Player p) {
        String key = IslandService.keyOf(p);
        Decor.State s = decor.state(key);
        IslandSettings st = svc.store().settings(key);
        Menu m = new Menu("Island Decorations", 6).chrome();
        m.back("Island Management", () -> mod.menus().management(p));
        Map<String, Integer> lim = dials().decorLimits();
        List<String> head = new ArrayList<>(List.of("&7Goods from the &aIsland Decorator&7,", "&7unlocked along your &bSocial &7skill.", ""));
        for (String k : Decor.KINDS) head.add("&7" + Decor.label(k) + ": &a" + Decor.count(s, k) + "&7/&a" + lim.getOrDefault(k, 0));
        if (!dials().decorEnabled.get()) head.addAll(List.of("", "&cTurned off on this server."));
        m.set(4, Icons.of(Material.FLOWER_POT, "&aIsland Decorations", head));

        Decor.Course c = Decor.course(s);
        List<String> pk = new ArrayList<>(List.of("&7Start/End plates, checkpoints in the", "&7order you place them, and plinths", "&7with the best times. Guests race too!", ""));
        if (c.ok()) {
            pk.add("&7Course: &a" + (c.loop() ? "a loop" : "start to finish") + " &7with &e" + c.checkpoints().size() + " &7checkpoint" + (c.checkpoints().size() == 1 ? "" : "s"));
        } else pk.add("&e" + c.problem());
        pk.add("");
        var top = Decor.leaderboard(s.parkourBest, 3);
        if (top.isEmpty()) pk.add("&7No times yet.");
        int i = 1;
        for (var e : top) pk.add("&e#" + (i++) + " &f" + svc.nameOf(UUID.fromString(e.getKey())) + " &7- &a" + Decor.time(e.getValue()));
        pk.addAll(List.of("", "&8/is parkour", "&eLeft-click for every time!", "&cRight-click to reset the times!"));
        m.set(19, Icons.of(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, "&aParkour", pk), cl -> {
            if (cl.right()) {
                if (!mod.menus().manage(cl.player(), key)) return;
                Sky.menus().confirm(cl.player(), "Reset parkour times?", List.of("&7Every best time on this island", "&7is forgotten."),
                        () -> { synchronized (st) { s.parkourBest.clear(); } svc.store().save(key); decor.refresh(key); Msg.success(cl.player(), "Parkour times reset."); },
                        () -> decorations(cl.player()).open(cl.player()));
                return;
            }
            cl.player().closeInventory();
            Msg.send(cl.player(), decor.courseLines(key));
            Msg.send(cl.player(), decor.timesLines(key, Math.max(1, dials().boardLines.get())));
        });

        List<String> sd = new ArrayList<>(List.of("&7A golden plinth honouring the", "&7guests who visit you most.", ""));
        var vis = Decor.topVisitors(st.guestVisits, 3);
        if (vis.isEmpty()) sd.add("&7Nobody has visited yet.");
        i = 1;
        for (var e : vis) { UUID u = IslandService.uuid(e.getKey()); if (u != null) sd.add("&e#" + (i++) + " &f" + svc.nameOf(u) + " &7- &b" + e.getValue()); }
        sd.addAll(List.of("", "&7Placed: &a" + Decor.count(s, Decor.DISPLAY), "", "&eClick for the full list!"));
        m.set(20, Icons.of(Material.GOLD_BLOCK, "&aSocial Display", sd), cl -> {
            cl.player().closeInventory();
            Msg.send(cl.player(), decor.visitorLines(key, Math.max(1, dials().boardLines.get())));
        });

        int eggs = Decor.count(s, Decor.EGG);
        m.set(21, Icons.of(Material.EGG, "&aEgg Hunt",
                "&7Hide eggs around the island; every", "&7guest can find each egg once.", "",
                "&7Eggs hidden: &a" + eggs + "&7/&a" + lim.getOrDefault(Decor.EGG, 10),
                "&7Guests who found them all: &b" + Decor.completedHunts(s),
                "&7Guests hunting: &b" + s.eggsFound.size(), "", "&cRight-click to start a new hunt!", "&8(forgets who found what)"),
                cl -> {
                    if (!cl.right() || !mod.menus().manage(cl.player(), key)) return;
                    synchronized (st) { s.eggsFound.clear(); }
                    svc.store().save(key);
                    Msg.success(cl.player(), "A new egg hunt begins - every guest can find every egg again.");
                    decorations(cl.player()).open(cl.player());
                });
        m.set(22, Icons.of(Material.GLASS, "&aShowcase Blocks",
                "&7Glass cases that show off an item.", "&7Right-click one holding an item; the", "&7item stays in your inventory - the", "&7case shows a picture of it.", "",
                "&7Sneak + right-click with an empty", "&7hand to clear one.", "", "&7Placed: &a" + Decor.count(s, Decor.SHOWCASE)));
        List<Decor.Placed> npcs = Decor.of(s, Decor.NPC);
        List<String> nl = new ArrayList<>(List.of("&7Residents for your island: name", "&7them, give them a line, pick a look.", ""));
        for (Decor.Placed n : npcs) nl.add("&8- &a" + (n.name == null ? "Resident" : n.name));
        if (npcs.isEmpty()) nl.add("&7None yet.");
        nl.addAll(List.of("", "&7Right-click an NPC to edit it."));
        m.set(23, Icons.of(Material.VILLAGER_SPAWN_EGG, "&aIsland NPCs", nl));
        m.set(25, Icons.of(Material.EMERALD, "&aIsland Decorator",
                "&7Sells every good here, each", "&7unlocked at a Social level:", "",
                "&71 &8Parkour  &72 &8Social Display", "&73 &8Egg Hunt  &75 &8Island NPC", "&710 &8Showcase Block", "", "&eClick to browse!"),
                cl -> { if (!Sky.commands().run(cl.player(), "merchants", "decorator")) Msg.error(cl.player(), "The Island Decorator is in the Hub."); });

        // Island Naming (Social 20) and Island Ranks (Social 9)
        int nameLvl = dials().nameSocial.get(), rankLvl = dials().rankSocial.get();
        boolean canName = social(p, nameLvl), canRank = social(p, rankLvl);
        List<String> nm = new ArrayList<>(List.of("&7Give your island a name. Guests", "&7see it when they arrive.", "",
                "&7Now: &a" + svc.islandTitle(key), ""));
        if (!canName) nm.add("&cRequires Social " + nameLvl + "!");
        else nm.addAll(List.of("&8/is name <name>", "&eLeft-click to type!", "&cRight-click to remove!"));
        m.set(30, Icons.of(Material.NAME_TAG, (canName ? "&a" : "&c") + "Island Name", nm), cl -> {
            if (cl.right()) { setName(cl.player(), null); decorations(cl.player()).open(cl.player()); return; }
            if (!social(cl.player(), nameLvl)) { Sky.requirements().checkOrTell(cl.player(), socialReq(nameLvl)); return; }
            Sky.menus().input(cl.player(), "Island name", raw -> { if (raw != null) setName(cl.player(), raw); });
        });
        List<String> rk = new ArrayList<>(List.of("&7Give players you trust a rank here:", "&aFriend &7- ignores the guest limit and", "&7may always use doors and switches.",
                "&bTrusted &7- a Friend who may also open", "&7containers and pick up items.", "&7Nobody outside your co-op can build.", "",
                "&7Ranked: &a" + st.ranks.size() + "&7/&a" + dials().rankCap.get(), ""));
        rk.add(canRank ? "&eClick to manage!" : "&cRequires Social " + rankLvl + "!");
        m.set(32, Icons.of(Material.GOLDEN_HELMET, (canRank ? "&a" : "&c") + "Island Ranks", rk), cl -> ranks(cl.player(), 0).open(cl.player()));
        return m;
    }

    // =========================================================================================
    // Island Naming
    // =========================================================================================

    /** Set (raw text) or clear (null) the island name. */
    void setName(Player p, String raw) {
        String key = IslandService.keyOf(p);
        if (!mod.menus().manage(p, key)) return;
        IslandSettings s = svc.store().settings(key);
        if (raw == null) {
            synchronized (s) { s.islandName = null; }
            svc.store().save(key);
            Msg.success(p, "Your island no longer has a name.");
            return;
        }
        int lvl = dials().nameSocial.get();
        if (!social(p, lvl)) { Sky.requirements().checkOrTell(p, socialReq(lvl)); return; }
        String t = decor.cleanTyped(p, raw, Math.max(4, dials().nameMax.get()));
        if (t == null) return;
        synchronized (s) { s.islandName = t; }
        svc.store().save(key);
        Msg.success(p, "Your island is now called " + t + ".");
        for (Player m : svc.membersOnline(key)) if (!m.equals(p)) Msg.send(m, "&b[Island] &f" + p.getName() + " &7named the island &a" + t + "&7.");
    }

    // =========================================================================================
    // Island Ranks
    // =========================================================================================

    Menu ranks(Player p, int page) {
        String key = IslandService.keyOf(p);
        IslandSettings s = svc.store().settings(key);
        List<String> ids;
        synchronized (s) { ids = new ArrayList<>(s.ranks.keySet()); }
        ids.sort((a, b) -> nameOf(a).compareToIgnoreCase(nameOf(b)));
        int lvl = dials().rankSocial.get();
        return Sky.menus().paged("Island Ranks", ids,
                id -> {
                    IslandRank r = IslandRank.of(s.ranks, id);
                    List<String> lore = List.of("&7Rank: " + r.color + r.label, "", "&eLeft-click: next rank", "&cRight-click: remove rank");
                    UUID u = IslandService.uuid(id);
                    return u == null ? Icons.of(Material.SKELETON_SKULL, r.color + nameOf(id), lore) : Icons.playerHead(u, r.color + nameOf(id), lore);
                },
                pc -> {
                    Player viewer = pc.click().player();
                    UUID u = IslandService.uuid(pc.entry());
                    if (u == null) return;
                    IslandRank cur = IslandRank.of(s.ranks, pc.entry());
                    IslandRank next = pc.click().right() ? IslandRank.GUEST : (cur.next() == IslandRank.GUEST ? IslandRank.FRIEND : cur.next());
                    setRank(viewer, u, nameOf(pc.entry()), next);
                    ranks(viewer, pc.page()).open(viewer);
                },
                menu -> {
                    menu.back("Island Decorations", () -> decorations(p));
                    menu.set(4, Icons.of(Material.GOLDEN_HELMET, "&aIsland Ranks",
                            "&aFriend&7: ignores the guest limit, may", "&7always use doors and switches.",
                            "&bTrusted&7: a Friend who may also open", "&7containers and pick up items.", "",
                            "&7Giving ranks needs &bSocial " + lvl + "&7."));
                    menu.set(50, Icons.of(Material.OAK_SIGN, "&aRank a Player", "&7Type a player's name to make", "&7them a Friend.", "", "&8/is rank <player> <friend|trusted|guest>", "&eClick to type!"),
                            c -> Sky.menus().input(c.player(), "Player name", name -> {
                                if (name == null || name.isBlank()) return;
                                rankByName(c.player(), name.trim(), IslandRank.FRIEND);
                                ranks(c.player(), 0).open(c.player());
                            }));
                }, page);
    }

    private String nameOf(String uuid) {
        UUID u = IslandService.uuid(uuid);
        return u == null ? uuid : svc.nameOf(u);
    }

    void rankByName(Player by, String name, IslandRank r) {
        Player t = Bukkit.getPlayerExact(name);
        UUID u = t != null ? t.getUniqueId() : null;
        String shown = t != null ? t.getName() : name;
        if (u == null) {
            IslandStore.PlayerEntry e = svc.store().byName(name);
            if (e == null) { Msg.error(by, "Couldn't find a player called " + name + "."); return; }
            u = e.uuid();
            shown = e.name();
        }
        setRank(by, u, shown, r);
    }

    /** Give {@code target} rank {@code r} on {@code by}'s island (GUEST clears it). */
    void setRank(Player by, UUID target, String name, IslandRank r) {
        String key = IslandService.keyOf(by);
        if (!mod.menus().manage(by, key)) return;
        int lvl = dials().rankSocial.get();
        if (r != IslandRank.GUEST && !social(by, lvl)) { Sky.requirements().checkOrTell(by, socialReq(lvl)); return; }
        if (IslandService.members(key).contains(target) || target.equals(by.getUniqueId())) { Msg.error(by, "Co-op members already have their own permissions (/is coop)."); return; }
        IslandSettings s = svc.store().settings(key);
        boolean ok;
        synchronized (s) {
            if (r != IslandRank.GUEST && s.banned.contains(target.toString())) { Msg.error(by, name + " is banned from your island. Unban them first."); return; }
            ok = IslandRank.set(s.ranks, target.toString(), r, Math.max(1, dials().rankCap.get()));
        }
        if (!ok) { Msg.error(by, "Your island has given out the most ranks it can (" + dials().rankCap.get() + ")."); return; }
        svc.store().save(key);
        Msg.success(by, r == IslandRank.GUEST ? name + " is a guest again." : name + " is now " + r.label + " on your island.");
        Player t = Bukkit.getPlayer(target);
        if (t != null) Msg.send(t, r == IslandRank.GUEST
                ? "&7You are no longer ranked on &a" + svc.islandTitle(key) + "&7."
                : "&b[Island] &7You are now " + r.color + r.label + " &7on &a" + svc.islandTitle(key) + "&7!");
    }

    /** /is rank <player> <friend|trusted|guest>. */
    void rankCommand(Player p, String[] a) {
        if (a.length < 2) { ranks(p, 0).open(p); return; }
        IslandRank r = a.length > 2 ? IslandRank.parse(a[2]) : IslandRank.FRIEND;
        if (r == null) { Msg.error(p, "Unknown rank " + a[2] + ". Use friend, trusted or guest."); return; }
        rankByName(p, a[1], r);
    }

    /** /is name <name...> | reset. */
    void nameCommand(Player p, String[] a) {
        if (a.length < 2) {
            Msg.send(p, "&7Your island: &a" + svc.islandTitle(IslandService.keyOf(p)) + " &8(/is name <name> | reset)");
            return;
        }
        String rest = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length));
        if (rest.equalsIgnoreCase("reset") || rest.equalsIgnoreCase("clear") || rest.equalsIgnoreCase("none")) setName(p, null);
        else setName(p, rest);
    }

    static List<String> rankIds() {
        List<String> l = new ArrayList<>();
        for (IslandRank r : IslandRank.values()) l.add(r.id.toLowerCase(Locale.ROOT));
        return l;
    }
}
