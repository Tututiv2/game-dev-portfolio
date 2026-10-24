package com.skyclone.mod.island;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Every island screen: management, settings, guests, bans, visiting, and Gerald's menu. */
final class IslandMenus {

    private final IslandService svc;
    private final IslandModule mod;

    IslandMenus(IslandModule mod, IslandService svc) {
        this.mod = mod;
        this.svc = svc;
    }

    private static void backToSkyMenu(Menu m) {
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
    }

    private static ItemStack toggle(Material m, String name, boolean on, String... desc) {
        List<String> lore = new ArrayList<>();
        for (String d : desc) lore.add("&7" + d);
        lore.add("");
        lore.add(on ? "&aEnabled" : "&cDisabled");
        lore.add("");
        lore.add("&eClick to toggle!");
        return Icons.of(m, (on ? "&a" : "&c") + name, lore);
    }

    // =========================================================================================
    // management
    // =========================================================================================

    Menu management(Player p) {
        String key = IslandService.keyOf(p);
        Menu m = new Menu("Island Management", 6).chrome();
        backToSkyMenu(m);
        int[] up = svc.upgrades(key);
        int size = up[0];
        IslandSettings s = svc.store().settings(key);
        List<Player> members = svc.membersOnline(key);
        List<String> info = new ArrayList<>();
        if (s.islandName != null) info.add("&7Name: &a" + s.islandName);
        info.add("&7Owner: &a" + svc.ownerLabel(key));
        info.add("&7Profile: &e" + safe(Sky.profiles().profileName(p)));
        info.add("&7Island Size: &a" + size + "x" + size + " &8(tier " + Text.roman(Math.max(0, up[3])) + ")");
        info.add("&7Members online: &a" + members.size());
        info.add("&7Guests here now: &b" + svc.guestsOn(key).size() + "&7/&b" + svc.guestLimit(key));
        info.add("&7Visitors allowed: " + IslandMath.visitorsLabel(s.visitors));
        info.add("&7Unique guests: &b" + s.uniqueGuests.size() + " &8(" + s.totalVisits + " visits)");
        m.set(4, Icons.of(Material.GRASS_BLOCK, "&a" + (s.islandName != null ? s.islandName : "Your Private Island"), info));

        m.set(20, Icons.of(Material.ENDER_PEARL, "&aGo to Your Island",
                "&7Warp to your island's spawn.", "", "&8/is", "&eClick to warp!"), c -> { c.player().closeInventory(); if (svc.offCooldown(c.player())) svc.goHome(c.player()); });
        List<String> spawnLore = new ArrayList<>(List.of("&7Make the spot you are standing on", "&7your island's spawn point.", "",
                s.homeSet ? "&7Current: &acustom spawn" : "&7Current: &edefault spawn", "", "&8/setspawn", "&eLeft-click to set here!"));
        if (s.homeSet) spawnLore.add("&bRight-click to reset!");
        m.set(21, Icons.of(Material.RED_BED, "&aSet Island Spawn", spawnLore),
                c -> {
                    if (c.right()) { if (!manage(c.player(), key)) return; svc.resetSpawn(key); Msg.success(c.player(), "Island spawn reset to the default."); management(c.player()).open(c.player()); return; }
                    if (svc.setSpawn(c.player())) management(c.player()).open(c.player());
                });
        m.set(22, Icons.of(Material.COMPARATOR, "&aIsland Settings",
                "&7Who may visit, and what guests", "&7are allowed to touch.", "", "&8/is settings", "&eClick to open!"),
                c -> settings(c.player()).open(c.player()));
        m.set(23, Icons.of(Material.PLAYER_HEAD, "&aGuests",
                "&7See who is on your island right", "&7now, and kick or ban them.", "",
                "&7Here now: &b" + svc.guestsOn(key).size(), "", "&8/is guests", "&eClick to view!"),
                c -> guests(c.player()).open(c.player()));
        m.set(24, Icons.of(Material.OAK_BOAT, "&aVisit an Island",
                "&7Hop over to another player's island.", "", "&8/visit <player>", "&eClick to browse!"),
                c -> visitList(c.player(), 0).open(c.player()));
        List<String[]> gates = mod.portals().status(key);
        List<String> pl = new ArrayList<>(List.of("&7One portal for every island you", "&7have been to, on the islet across", "&7the bridge south-west of the plaza.", ""));
        int open = 0;
        for (String[] g : gates) {
            if ("open".equals(g[2])) open++;
            pl.add(("open".equals(g[2]) ? "&a✔ &b" : "closed".equals(g[2]) ? "&8✖ &8" : "&c✖ &7") + g[1]);
        }
        pl.add("");
        pl.add("&7Open: &a" + open + "&7/&a" + gates.size());
        pl.add("");
        pl.add("&eClick to view!");
        m.set(25, Icons.of(Material.CRYING_OBSIDIAN, "&dPortal Isle", pl), c -> portals(c.player()).open(c.player()));
        m.set(29, Icons.of(Material.MAP, "&aIsland Size",
                "&7Your build area is &a" + size + "x" + size + "&7.",
                "&7Grow it up to &a241x241 &7with Island Size",
                "&7upgrades at the Community Shop.", "", "&eClick to open the Community Shop!"),
                c -> { if (!Sky.commands().run(c.player(), "cshop")) Msg.error(c.player(), "The Community Shop is not available right now."); });
        m.set(30, Icons.of(Material.PLAYER_HEAD, "&aCo-op",
                "&7Share this island, your bank and", "&7your minions with friends.", "",
                "&7Members online: &a" + members.size(), "", "&8/coop", "&eClick to manage!"),
                c -> { if (!Sky.commands().run(c.player(), "coop")) Msg.error(c.player(), "Co-op is not available right now."); });
        m.set(31, Icons.of(Material.BARRIER, "&cBanned Players",
                "&7Players who can never visit.", "", "&7Banned: &c" + s.banned.size(), "", "&eClick to view!"),
                c -> bans(c.player()).open(c.player()));
        m.set(32, Icons.of(Material.VILLAGER_SPAWN_EGG, "&aGerald",
                "&7Your island helper. Starter kit,", "&7tips and a chat.", "", "&eClick to talk!"),
                c -> helper(c.player()).open(c.player()));
        m.set(33, Icons.of(Material.OBSIDIAN, "&aGo to the Hub",
                "&7The town at the centre of everything.", "", "&8/hub", "&eClick to warp!"),
                c -> { c.player().closeInventory(); Sky.worlds().warp(c.player(), "hub"); });
        // wave 5b: the Social road's island goods, Island Ranks and Island Naming
        var ds = s.decor;
        m.set(40, Icons.of(Material.FLOWER_POT, "&aIsland Decorations",
                "&7Parkour courses, Social Displays,", "&7an Egg Hunt, Showcase Blocks and", "&7Island NPCs from the Island Decorator.", "",
                "&7Placed: &a" + ds.placed.size(), "&7Island name and ranks live here too.", "", "&8/is decor", "&eClick to open!"),
                c -> mod.social().decorations(c.player()).open(c.player()));
        String ownerName = svc.ownerName(key);
        m.set(34, Icons.of(Material.NAME_TAG, "&aCo-op Permissions",
                "&7Choose what each co-op member may", "&7do on this island: place, break,", "&7open chests, use minions, manage.", "",
                "&7Owner: &6" + (ownerName == null ? "-" : ownerName),
                "&7You: " + (svc.isOwner(p, key) ? "&6Owner" : "&aMember"), "", "&8/is coop", "&eClick to open!"),
                c -> coop(c.player()).open(c.player()));
        return m;
    }

    // =========================================================================================
    // co-op permissions (wave 5)
    // =========================================================================================

    /** False (and tells them) when {@code p} may not manage island {@code key}. */
    boolean manage(Player p, String key) {
        if (svc.may(p, key, CoopPerm.MANAGE)) return true;
        Msg.error(p, "The island owner has not given you permission to manage the island.");
        return false;
    }

    Menu coop(Player p) {
        String key = IslandService.keyOf(p);
        if (Sky.profiles().isReady(p)) svc.ensureOwner(p, key);    // an owner who left the co-op is replaced here too
        boolean owner = svc.isOwner(p, key);
        Menu m = new Menu("Co-op Permissions", 6).chrome();
        m.back("Island Management", () -> management(p));
        String on = svc.ownerName(key);
        List<String> head = new ArrayList<>(List.of(
                "&7The island's &6owner &7(its longest-standing", "&7member) always has every permission", "&7and decides what the others may do.", "",
                "&7Owner: &6" + (on == null ? "-" : on), "&7You are: " + (owner ? "&6the owner" : "&aa member")));
        if (!svc.dials().coopPermissions.get()) head.addAll(List.of("", "&eTurned off on this server:", "&eevery member may do everything."));
        m.set(4, Icons.of(Material.NAME_TAG, "&aCo-op Permissions", head));
        List<UUID> members = svc.knownMembers(key);
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int i = 0; i < members.size() && i < slots.length; i++) {
            UUID u = members.get(i);
            String name = svc.nameOf(u);
            boolean isOwnerRow = u.equals(svc.ownerOf(key));
            List<String> lore = new ArrayList<>();
            lore.add(isOwnerRow ? "&6Owner" : "&aMember");
            lore.add("");
            for (CoopPerm perm : CoopPerm.values()) {
                boolean ok = isOwnerRow || svc.may(u, key, perm);
                lore.add((ok ? "&a✔ " : "&c✖ ") + "&7" + perm.label);
            }
            lore.add("");
            if (isOwnerRow) lore.add("&8The owner can do everything.");
            else if (owner) lore.add("&eClick to change!");
            else lore.add("&8Only the owner can change these.");
            m.set(slots[i], Icons.playerHead(u, (isOwnerRow ? "&6" : "&a") + name, lore), c -> {
                if (!isOwnerRow && svc.isOwner(c.player(), key)) memberPerms(c.player(), u).open(c.player());
            });
        }
        if (members.isEmpty()) m.set(22, Icons.of(Material.BARRIER, "&cNo members loaded", "&7Members show up while someone", "&7of this co-op is online."));
        m.set(50, Icons.of(Material.PLAYER_HEAD, "&aCo-op", "&7Invite players, see members, leave.", "", "&8/coop", "&eClick to open!"),
                c -> { if (!Sky.commands().run(c.player(), "coop")) Msg.error(c.player(), "Co-op is not available right now."); });
        return m;
    }

    Menu memberPerms(Player p, UUID member) {
        String key = IslandService.keyOf(p);
        String name = svc.nameOf(member);
        Menu m = new Menu("Permissions: " + name, 5).chrome();
        m.back("Co-op Permissions", () -> coop(p));
        m.set(4, Icons.playerHead(member, "&a" + name, List.of("&7Co-op member", "", "&7Toggle what " + name + " may", "&7do on this island.")));
        int[] slots = {20, 21, 22, 23, 24};
        CoopPerm[] all = CoopPerm.values();
        for (int i = 0; i < all.length && i < slots.length; i++) {
            CoopPerm perm = all[i];
            boolean onNow = svc.may(member, key, perm);
            List<String> lore = new ArrayList<>(perm.desc);
            Material icon = Material.matchMaterial(perm.icon);
            m.set(slots[i], toggle(icon == null ? Material.PAPER : icon, perm.label, onNow, lore.toArray(new String[0])), c -> {
                String err = svc.setPerm(c.player(), key, member, perm, !svc.may(member, key, perm));
                if (err != null) { Msg.error(c.player(), err); return; }
                Player t = Bukkit.getPlayer(member);
                if (t != null && !svc.may(member, key, perm))
                    Msg.send(t, "&e[Island] &7" + c.player().getName() + " turned off your &c" + perm.label + " &7permission.");
                memberPerms(c.player(), member).open(c.player());
            });
        }
        m.set(38, Icons.of(Material.LIME_DYE, "&aGrant Everything", "&7Turn every permission on.", "", "&eClick to grant!"),
                c -> { setAll(c.player(), key, member, true); memberPerms(c.player(), member).open(c.player()); });
        m.set(39, Icons.of(Material.GRAY_DYE, "&cRevoke Everything", "&7Turn every permission off. They", "&7stay a co-op member and keep", "&7their own skills and items.", "", "&eClick to revoke!"),
                c -> { setAll(c.player(), key, member, false); memberPerms(c.player(), member).open(c.player()); });
        m.set(42, Icons.of(Material.GOLDEN_HELMET, "&6Make Owner",
                "&7Hand the island over to " + name + ".", "&7They get every permission and decide", "&7yours from now on.", "", "&cYou cannot undo this yourself!", "", "&eClick to transfer!"),
                c -> transferConfirm(c.player(), member));
        return m;
    }

    private void setAll(Player by, String key, UUID member, boolean on) {
        for (CoopPerm perm : CoopPerm.values()) {
            String err = svc.setPerm(by, key, member, perm, on);
            if (err != null) { Msg.error(by, err); return; }
        }
        Msg.success(by, (on ? "Granted every permission to " : "Revoked every permission from ") + svc.nameOf(member) + ".");
    }

    void transferConfirm(Player by, UUID member) {
        String key = IslandService.keyOf(by);
        String name = svc.nameOf(member);
        Sky.menus().confirm(by, "Make " + name + " the owner?", List.of("&7" + name + " becomes the island owner.", "&7They decide your permissions afterwards."),
                () -> {
                    String err = transfer(by, key, member);
                    if (err != null) Msg.error(by, err);
                    else coop(by).open(by);
                },
                () -> memberPerms(by, member).open(by));
    }

    /** Owner hands the island to another current member. Null = done, else why not. */
    String transfer(Player by, String key, UUID member) {
        if (!svc.isOwner(by, key)) return "Only the island owner can do that.";
        if (member.equals(by.getUniqueId())) return "You already own this island.";
        List<UUID> ms = IslandService.members(key);
        if (!ms.contains(member)) return "That player is not a member of this co-op.";
        svc.setOwner(key, member);
        // the old owner keeps everything until the new owner decides otherwise
        for (CoopPerm perm : CoopPerm.values()) svc.setPermForce(key, by.getUniqueId(), perm, true);
        for (Player m : svc.membersOnline(key))
            Msg.send(m, "&6[Island] &f" + svc.nameOf(member) + " &7is now the owner of this island &8(given by " + by.getName() + ")&7.");
        return null;
    }

    // =========================================================================================
    // the Portal Isle
    // =========================================================================================

    Menu portals(Player p) {
        String key = IslandService.keyOf(p);
        Menu m = new Menu("Portal Isle", 5).chrome();
        m.back("Island Management", () -> management(p));
        m.set(4, Icons.of(Material.CRYING_OBSIDIAN, "&dPortal Isle",
                "&7A portal lights up here for every", "&7island you have travelled to.", "&7Walk into a lit one to go there.", "",
                "&7Find it across the bridge south-west", "&7of your spawn plaza."));
        int[] slots = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24, 29, 30, 32, 33};   // Portal Isle rows, then the Far Court
        List<String[]> gates = mod.portals().status(key);
        for (int i = 0; i < gates.size() && i < slots.length; i++) {
            String[] g = gates.get(i);
            String warp = g[0];
            boolean open = "open".equals(g[2]), closed = "closed".equals(g[2]);
            Material icon = open ? Material.ENDER_EYE : closed ? Material.GRAY_DYE : Material.ENDER_PEARL;
            List<String> lore = new ArrayList<>();
            if (open) lore.addAll(List.of("&aOpen!", "&7Walk into it to travel there."));
            else if (closed) lore.addAll(List.of("&8This place is closed right now."));
            else lore.addAll(List.of("&cLocked", "&7Opens once you have been to", "&b" + g[1] + "&7. Find the way from the Hub!"));
            lore.add("");
            lore.add("&eClick to walk to this portal!");
            m.set(slots[i], Icons.of(icon, (open ? "&b" : closed ? "&8" : "&7") + g[1], lore), c -> {
                c.player().closeInventory();
                mod.goToPortal(c.player(), warp);
            });
        }
        return m;
    }

    // =========================================================================================
    // settings
    // =========================================================================================

    Menu settings(Player p) {
        String key = IslandService.keyOf(p);
        IslandSettings s = svc.store().settings(key);
        Menu m = new Menu("Island Settings", 6).chrome();
        m.back("Island Management", () -> management(p));
        List<String> vis = new ArrayList<>();
        vis.add("&7Who may use &e/visit &7to come here.");
        vis.add("");
        for (String mode : IslandMath.VISITOR_MODES)
            vis.add((mode.equals(s.visitors) ? "&b» " : "&8  ") + IslandMath.visitorsLabel(mode));
        vis.add("");
        vis.add("&7Current: " + IslandMath.visitorsLabel(s.visitors));
        vis.add("");
        vis.add("&eClick to cycle!");
        m.set(10, Icons.of(Material.OAK_DOOR, "&aVisitors", vis), c -> {
            if (!manage(c.player(), key)) return;
            int i = IslandMath.VISITOR_MODES.indexOf(s.visitors);
            int n = c.right() ? (i + IslandMath.VISITOR_MODES.size() - 1) : (i + 1);
            svc.setVisitors(key, IslandMath.VISITOR_MODES.get(Math.floorMod(n, IslandMath.VISITOR_MODES.size())));
            settings(c.player()).open(c.player());
        });
        m.set(12, toggle(Material.CHEST, "Guests: Containers", s.guestContainers,
                "Guests may open chests, barrels,", "furnaces and other containers."), c -> flip(c.player(), key, 0));
        m.set(13, toggle(Material.OAK_TRAPDOOR, "Guests: Doors & Switches", s.guestDoors,
                "Guests may use doors, trapdoors,", "gates, buttons and levers."), c -> flip(c.player(), key, 1));
        m.set(14, toggle(Material.HOPPER, "Guests: Pick Up Items", s.guestPickup,
                "Guests may pick up items lying", "around your island."), c -> flip(c.player(), key, 2));
        boolean offlineDial = svc.dials().visitOffline.get();
        List<String> off = new ArrayList<>(List.of("&7Let guests stay and visit while", "&7nobody from your island is online."));
        if (!offlineDial) off.addAll(List.of("", "&cDisabled on this server."));
        off.addAll(List.of("", s.offlineVisits ? "&aEnabled" : "&cDisabled", "", "&eClick to toggle!"));
        m.set(16, Icons.of(Material.CLOCK, (s.offlineVisits ? "&a" : "&c") + "Offline Visits", off), c -> flip(c.player(), key, 3));
        m.set(28, Icons.of(Material.IRON_SWORD, "&cPvP", "&7Players can never fight on a", "&7private island.", "", "&cAlways disabled"));
        m.set(30, Icons.of(Material.BELL, "&aVisitor Messages",
                "&7Your own choice: get a chat line when", "&7a guest arrives or leaves.", "",
                IslandService.notifyOn(p) ? "&aEnabled" : "&cDisabled", "", "&eClick to open your settings!"),
                c -> { if (!Sky.commands().run(c.player(), "settings", "island")) Msg.error(c.player(), "Settings are not available right now."); });
        m.set(32, Icons.of(Material.RED_BED, "&aReset Island Spawn",
                "&7Put the island spawn back at", "&7the plaza by the portal.", "", s.homeSet ? "&eClick to reset!" : "&8Already at the default."),
                c -> { if (!manage(c.player(), key)) return; svc.resetSpawn(key); Msg.success(c.player(), "Island spawn reset."); settings(c.player()).open(c.player()); });
        m.set(34, Icons.of(Material.TNT, "&cKick All Guests", "&7Send every guest home.", "", "&eClick to kick!"),
                c -> { if (!manage(c.player(), key)) return; int n = kickAll(c.player(), key); Msg.success(c.player(), "Kicked " + n + " guest" + (n == 1 ? "" : "s") + "."); });
        return m;
    }

    private void flip(Player p, String key, int which) {
        if (!manage(p, key)) return;
        IslandSettings s = svc.store().settings(key);
        synchronized (s) {
            switch (which) {
                case 0 -> s.guestContainers = !s.guestContainers;
                case 1 -> s.guestDoors = !s.guestDoors;
                case 2 -> s.guestPickup = !s.guestPickup;
                default -> s.offlineVisits = !s.offlineVisits;
            }
        }
        svc.store().save(key);
        settings(p).open(p);
    }

    int kickAll(Player by, String key) {
        int n = 0;
        for (Player g : svc.guestsOn(key)) { svc.sendOff(g, by.getName() + " sent all guests home."); n++; }
        return n;
    }

    // =========================================================================================
    // guests & bans
    // =========================================================================================

    Menu guests(Player p) {
        String key = IslandService.keyOf(p);
        List<Player> list = svc.guestsOn(key);
        return Sky.menus().paged("Guests (" + list.size() + "/" + svc.guestLimit(key) + ")", list,
                g -> {
                    IslandRank r = svc.rankOf(key, g.getUniqueId());
                    return Icons.playerHead(g.getUniqueId(), r.color + g.getName(),
                            List.of("&7A guest on your island.", "&7Rank: " + r.color + r.label, "", "&eLeft-click to kick!", "&cRight-click to ban!", "&bShift-click to change rank!"));
                },
                pc -> {
                    Player g = pc.entry();
                    Player viewer = pc.click().player();
                    if (!manage(viewer, key)) return;
                    if (!g.isOnline() || !key.equals(svc.guestAt.get(g.getUniqueId()))) { Msg.error(viewer, "They already left."); guests(viewer).open(viewer); return; }
                    if (pc.click().shift()) {
                        IslandRank r = svc.rankOf(key, g.getUniqueId()).next();
                        mod.social().setRank(viewer, g.getUniqueId(), g.getName(), r);
                        guests(viewer).open(viewer);
                        return;
                    }
                    if (pc.click().right()) ban(viewer, key, g.getUniqueId(), g.getName());
                    else { svc.sendOff(g, viewer.getName() + " kicked you from their island."); Msg.success(viewer, "Kicked " + g.getName() + "."); }
                    guests(viewer).open(viewer);
                },
                page -> page.back("Island Management", () -> management(p)), 0);
    }

    void ban(Player by, String key, UUID target, String name) {
        IslandSettings s = svc.store().settings(key);
        synchronized (s) { if (!s.banned.contains(target.toString())) s.banned.add(target.toString()); s.ranks.remove(target.toString()); }
        svc.store().save(key);
        Player t = Bukkit.getPlayer(target);
        if (t != null && key.equals(svc.keyAt(t.getLocation())) && !svc.isMember(t, key)) svc.sendOff(t, "You were banned from this island.");
        Msg.success(by, "Banned " + name + " from your island.");
    }

    Menu bans(Player p) {
        String key = IslandService.keyOf(p);
        IslandSettings s = svc.store().settings(key);
        List<String> ids = new ArrayList<>(s.banned);
        return Sky.menus().paged("Banned Players", ids,
                id -> {
                    String name = nameOf(id);
                    try { return Icons.playerHead(UUID.fromString(id), "&c" + name, List.of("&7Cannot visit your island.", "", "&eClick to unban!")); }
                    catch (IllegalArgumentException e) { return Icons.of(Material.SKELETON_SKULL, "&c" + name, "&eClick to unban!"); }
                },
                pc -> {
                    if (!manage(pc.click().player(), key)) return;
                    synchronized (s) { s.banned.remove(pc.entry()); }
                    svc.store().save(key);
                    Msg.success(pc.click().player(), "Unbanned " + nameOf(pc.entry()) + ".");
                    bans(pc.click().player()).open(pc.click().player());
                },
                page -> page.back("Island Management", () -> management(p)), 0);
    }

    String nameOf(String uuid) {
        try {
            UUID u = UUID.fromString(uuid);
            IslandStore.PlayerEntry e = svc.store().player(u);
            if (e != null) return e.name();
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            return op.getName() == null ? uuid.substring(0, 8) : op.getName();
        } catch (IllegalArgumentException e) { return uuid; }
    }

    // =========================================================================================
    // visiting
    // =========================================================================================

    Menu visitList(Player p, int page) {
        String own = IslandService.keyOf(p);
        List<Player> hosts = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p) || !Sky.profiles().isReady(o)) continue;
            if (own.equals(IslandService.keyOf(o))) continue;
            hosts.add(o);
        }
        hosts.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return Sky.menus().paged("Visit an Island", hosts,
                o -> {
                    String key = IslandService.keyOf(o);
                    String why = svc.visitBlocker(p, key);
                    List<String> lore = new ArrayList<>();
                    String isName = svc.store().settings(key).islandName;
                    if (isName != null) lore.add("&a" + isName);
                    IslandRank myRank = svc.rankOf(key, p.getUniqueId());
                    if (myRank != IslandRank.GUEST) lore.add("&7You are " + myRank.color + myRank.label + " &7there");
                    lore.add("&7Guests: &b" + svc.countedGuests(key) + "&7/&b" + svc.guestLimit(key));
                    lore.add("&7Size: &a" + svc.sizeOf(key) + "x" + svc.sizeOf(key));
                    lore.add("");
                    lore.add(why == null ? "&eClick to visit!" : "&c" + why);
                    return Icons.playerHead(o.getUniqueId(), "&a" + o.getName() + "'s Island", lore);
                },
                pc -> {
                    Player v = pc.click().player();
                    v.closeInventory();
                    if (svc.offCooldown(v)) svc.visit(v, IslandService.keyOf(pc.entry()), pc.entry().getName(), false);
                },
                m -> {
                    m.back("Island Management", () -> management(p));
                    m.set(50, Icons.of(Material.OAK_SIGN, "&aVisit by Name", "&7Type a player's name.", "", "&eClick to type!"),
                            c -> Sky.menus().input(c.player(), "Player name", name -> {
                                if (name == null || name.isBlank()) return;
                                mod.visitByName(c.player(), name.trim());
                            }));
                }, page);
    }

    // =========================================================================================
    // Gerald
    // =========================================================================================

    Menu helper(Player p) {
        Menu m = new Menu("Gerald", 4).chrome();
        IslandModule.MemberData md = mod.memberData(p);
        boolean claimed = md != null && md.kitClaimed;
        List<String> kitLore = new ArrayList<>();
        kitLore.add("&7A few tools to get you going:");
        for (IslandMath.KitEntry e : IslandMath.parseKit(svc.dials().starterKit.get())) {
            if (!Sky.items().exists(e.id())) continue;
            var def = Sky.items().def(e.id());
            String n = def == null || def.name == null ? Text.titleCase(e.id()) : def.name;
            String col = def == null || def.rarity == null ? "&f" : def.rarity.code();
            kitLore.add("&8- " + col + n + (e.amount() > 1 ? " &8x" + e.amount() : ""));
        }
        kitLore.add("");
        kitLore.add(claimed ? "&aAlready claimed!" : "&eClick to claim!");
        m.set(10, Icons.of(claimed ? Material.MINECART : Material.CHEST_MINECART, (claimed ? "&7" : "&a") + "Starter Kit", kitLore),
                c -> { mod.claimKit(c.player()); helper(c.player()).open(c.player()); });
        m.set(12, Icons.of(Material.WRITABLE_BOOK, "&aIsland Tips", tipsLore()), c -> mod.tellTip(c.player()));
        m.set(14, Icons.of(Material.GRASS_BLOCK, "&aIsland Management", "&7Settings, guests, spawn and size.", "", "&8/is menu", "&eClick to open!"),
                c -> management(c.player()).open(c.player()));
        m.set(16, Icons.of(Material.OBSIDIAN, "&aTo the Hub", "&7Step through the portal by the plaza,", "&7or let me send you.", "", "&eClick to warp!"),
                c -> { c.player().closeInventory(); Sky.worlds().warp(c.player(), "hub"); });
        m.set(4, Icons.of(Material.VILLAGER_SPAWN_EGG, "&aGerald", "&7Looks after your island while", "&7you are out. Very small. Very loyal."));
        String key = IslandService.keyOf(p);
        String missing = svc.dials().retrofitPortals.get() && key.equals(svc.keyAt(p.getLocation())) ? mod.portals().missingPart(key) : null;
        if ("far".equals(missing)) {
            m.set(22, Icons.of(Material.CRYING_OBSIDIAN, "&dBuild the Far Court",
                    "&7Newer islands have four more portals", "&7- the Garden, Backwater Bayou, Lotus", "&7Atoll and Torrhus Canyon - on a little", "&7islet west of the Portal Isle.", "",
                    "&7I can raise it for you. It only fills", "&7empty sky, so nothing you built is", "&7touched.", "",
                    "&eClick to build it!"),
                    c -> { c.player().closeInventory(); mod.portals().retrofit(IslandService.keyOf(c.player()), c.player()); });
        } else if ("isle".equals(missing)) {
            // islands made before the Portal Isle existed: Gerald builds it for them (only into empty void, never over a build)
            m.set(22, Icons.of(Material.CRYING_OBSIDIAN, "&dBuild the Portal Isle",
                    "&7Newer islands come with an islet of", "&7portals - one lights up for every", "&7island you have been to.", "",
                    "&7I can raise one off your south-west", "&7coast. It only fills empty sky, so", "&7nothing you built is touched.", "",
                    "&eClick to build it!"),
                    c -> { c.player().closeInventory(); mod.portals().retrofit(IslandService.keyOf(c.player()), c.player()); });
        } else {
            m.set(22, Icons.of(Material.CRYING_OBSIDIAN, "&dPortal Isle",
                    "&7Across the bridge south-west of the", "&7plaza: a portal for every island", "&7you have been to.", "", "&8/is portals", "&eClick to view!"),
                    c -> portals(c.player()).open(c.player()));
        }
        return m;
    }

    private static List<String> tipsLore() {
        List<String> l = new ArrayList<>();
        l.add("&7Gerald shares one of his tips.");
        l.add("");
        l.add("&eClick to listen!");
        return l;
    }

    private static String safe(String s) { return s == null || s.isBlank() ? "-" : s; }
}
