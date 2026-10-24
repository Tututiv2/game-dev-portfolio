package com.skyclone.mod.island;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.island.gen.PrivateIslandGenerator;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Private Island: an instanced starter island per profile, its helper (Gerald), visiting, guest
 * permissions, island settings, and {@code Features.PrivateIsland} for core and the other modules.
 */
public final class IslandModule implements GameModule {

    public static final String HELPER_ID = "island_helper";
    static final String ISLAND = IslandService.ISLAND;

    /** Per-member state (member key "island"). Add fields, never retype them. */
    public static final class MemberData {
        public boolean kitClaimed;
        public long kitClaimedAt;
        public boolean metHelper;
        public int tip;
        public boolean welcomed;
    }

    private IslandDials dials;
    private IslandStore store;
    private IslandService svc;
    private IslandMenus menus;
    private IslandPortals portals;
    private IslandBridges bridges;
    private IslandDecorations decor;
    private IslandSocial social;
    private BukkitTask task, portalTask;

    @Override public String id() { return "island"; }

    @Override
    public void enable() {
        dials = new IslandDials();
        store = new IslandStore();
        store.migrate();
        store.loadAll();
        svc = new IslandService(dials, store);
        menus = new IslandMenus(this, svc);
        portals = new IslandPortals(svc);
        bridges = new IslandBridges(svc, dials);
        decor = new IslandDecorations(this, svc);
        social = new IslandSocial(this, svc, decor);
        svc.loadCommunityShopTables();

        IslandDef def = IslandDef.of(ISLAND, "Private Island", new PrivateIslandGenerator());
        def.instanced = true;
        def.instanceSpacing = IslandMath.SPACING;
        Sky.worlds().registerIsland(def);
        // "private_island" is the hub portal's warp (portal.private_island); "home" is the genre's /warp home.
        Sky.worlds().registerWarp("private_island", ISLAND, "spawn", "Private Island", null);
        Sky.worlds().registerWarp("home", ISLAND, "spawn", "Private Island", null);
        Sky.provide(Features.PrivateIsland.class, svc);
        registerRequirements();

        NpcDef helper = NpcDef.of(HELPER_ID, "&aGerald", ISLAND, "npc.island_helper").title("&e&lISLAND HELPER").entity("VILLAGER");
        helper.lines.add("&7Starter kit & tips");
        Sky.npcs().register(helper, this::onHelperClick);

        registerCommands();
        registerAdmin();
        registerSelfTests();

        Sky.sidebar().section("island", 300, this::sidebar);
        Sky.sidebar().tabWidget("island", 170, this::tab);

        Bukkit.getPluginManager().registerEvents(new IslandListener(this, svc), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(portals, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(decor, Sky.plugin());
        try { Sky.vitals().actionBarSegment("island_parkour", 40, decor::actionBar); } catch (RuntimeException e) { Sky.log().warning("[island] no action bar for parkour: " + e); }
        portalTask = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            try { portals.tick(); } catch (RuntimeException e) { Sky.log().warning("[island] portal tick failed: " + e); }
            try { bridges.tick(); } catch (RuntimeException e) { Sky.log().warning("[island] bridge repair failed: " + e); }
            try { decor.tick(); } catch (RuntimeException e) { Sky.log().warning("[island] decor tick failed: " + e); }
        }, 60L, 40L);
        task = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            try { svc.tick(); } catch (RuntimeException e) { Sky.log().warning("[island] tick failed: " + e); }
        }, 40L, 20L);
    }

    @Override
    public void postEnable() {
        svc.loadCommunityShopTables();
    }

    @Override
    public void disable() {
        if (task != null) task.cancel();
        if (portalTask != null) portalTask.cancel();
        task = null;
        portalTask = null;
        if (portals != null) portals.clearAll();
        if (decor != null) decor.clearAll();
        if (svc != null) for (String k : new ArrayList<>(svc.helperKeys)) svc.despawnHelper(k);
    }

    IslandPortals portals() { return portals; }

    IslandMenus menus() { return menus; }

    IslandDecorations decor() { return decor; }

    IslandSocial social() { return social; }

    /**
     * Requirement keys (the cross-module channel, CONTRACT §3):
     * <ul>
     *   <li>{@code island:perm:<build|break|containers|minions|manage>} - the player's co-op permission on THEIR OWN
     *       island (the owner and solo players always pass). The minions module should check
     *       {@code island:perm:minions} before opening/placing/picking up a minion (hand-off Request 9).</li>
     *   <li>{@code island:owner} - owns their island (solo players always do).</li>
     *   <li>{@code island:home} - standing on their own private island.</li>
     *   <li>{@code island:<islandId>} - standing on that island ({@code island:garden}).</li>
     * </ul>
     */
    private void registerRequirements() {
        Sky.requirements().register("island", new com.skyclone.api.util.Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (p == null || parts.length < 2) return false;
                String k = parts[1].toLowerCase(Locale.ROOT);
                switch (k) {
                    case "perm", "permission" -> {
                        CoopPerm perm = parts.length > 2 ? CoopPerm.parse(parts[2]) : null;
                        return perm != null && Sky.profiles().isReady(p) && svc.may(p, IslandService.keyOf(p), perm);
                    }
                    case "owner" -> { return Sky.profiles().isReady(p) && svc.isOwner(p, IslandService.keyOf(p)); }
                    case "home" -> { return svc.isOnOwnIsland(p); }
                    default -> { return k.equals(Sky.worlds().islandOf(p.getLocation())); }
                }
            }

            @Override public String describe(String[] parts) {
                if (parts.length < 2) return "&aIsland";
                String k = parts[1].toLowerCase(Locale.ROOT);
                return switch (k) {
                    case "perm", "permission" -> {
                        CoopPerm perm = parts.length > 2 ? CoopPerm.parse(parts[2]) : null;
                        yield "&aCo-op permission: " + (perm == null ? parts.length > 2 ? parts[2] : "?" : perm.label);
                    }
                    case "owner" -> "&aIsland Owner";
                    case "home" -> "&aBeing on your Private Island";
                    default -> {
                        var d = Sky.worlds().island(k);
                        yield "&aBeing on " + (d == null || d.displayName == null ? Text.titleCase(k) : d.displayName);
                    }
                };
            }
        });
    }

    /** Walk-free trip to the spot in front of one of the Portal Isle's portals on your own island. */
    void goToPortal(Player p, String warp) {
        if (!svc.offCooldown(p)) return;
        String key = IslandService.keyOf(p);
        Sky.worlds().ensureInstance(ISLAND, key).whenComplete((loc, err) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            Location at = err == null ? Sky.worlds().instanceAnchor(ISLAND, key, PrivateIslandGenerator.PORTAL_AREA + warp) : null;
            if (at == null || !at.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                Msg.error(p, "That portal is not on your island yet - ask Gerald to build it (only empty sky is used).");
                return;
            }
            svc.spawnHelper(key);
            IslandService.teleport(p, at);
        }));
    }

    MemberData memberData(Player p) {
        if (!Sky.profiles().isReady(p)) return null;
        return Sky.profiles().member(p, "island", MemberData.class);
    }

    // =========================================================================================
    // Gerald
    // =========================================================================================

    private static final List<String> INTRO = List.of(
            "&fOh! A new face. I'm Gerald - I keep an eye on this little island for you.",
            "&fEverything out here is yours: break it, build on it, plant it, move it.",
            "&fThat big oak by the plaza is a fine place to get your first logs.",
            "&fMake a crafting table, then a pickaxe, and give the rocks by the pond a go.",
            "&fWhen you want company and a bit of trade, step through the portal to the Hub.",
            "&fAnd over the bridge to the south-west is the Portal Isle: every island you visit lights a portal there.",
            "&fCome and see me again - I've put a starter kit aside for you!");

    private static final List<String> TIPS = List.of(
            "&fThose five stone-framed squares east of here are minion slots. A minion needs its 5x5 to itself!",
            "&fThe rock pile by the pond grows back a few seconds after you mine it. Handy, isn't it?",
            "&fFairy souls are hiding all over this island. Fifteen of them, if I counted right.",
            "&fYou can move your island spawn: stand somewhere you like and type /setspawn.",
            "&fFriends can drop by with /visit <your name>. Choose who may come in /is settings.",
            "&fGuests can look but can't build. You decide whether they may open your chests.",
            "&fInvite friends with /coop and the whole island - and its minions - becomes theirs too.",
            "&fYour build area is 161 blocks across. The Community Shop sells Island Size upgrades up to 241.",
            "&fCrops on the farm grow back if you replant them. Wheat makes bread, and bread makes friends.",
            "&fNever fall off the edge! The void is not as soft as it looks. Here you keep your coins, at least.",
            "&fThe lookout on the little islet to the north-west has a lovely view. And maybe something shiny.",
            "&fThe Hub has a Bank, a Bazaar and an Auction House. Coins go a long way there.",
            "&fBeen somewhere new? Its portal on the Portal Isle lights up - walk in and you're there. No walking through the Hub!",
            "&fA dark portal on the Portal Isle is a place you haven't been yet. The Hub's portals will get you there first.",
            "&fOne more bridge west of the Portal Isle is the Far Court: the Garden, the Bayou, the Atoll and the Canyon.",
            "&fSharing the island with a co-op? Whoever has been here longest is the owner, and decides who may build, mine or open chests. See /is coop.");

    private void onHelperClick(Player p) {
        try { Sky.service(Features.Objectives.class).complete(p, "talk_island_helper"); } catch (RuntimeException ignored) { }
        MemberData md = memberData(p);
        if (md != null && !md.metHelper) {
            md.metHelper = true;
            Sky.npcs().talk(p, HELPER_ID, INTRO, () -> { if (p.isOnline()) menus.helper(p).open(p); });
            return;
        }
        menus.helper(p).open(p);
    }

    void tellTip(Player p) {
        MemberData md = memberData(p);
        int i = md == null ? (int) (Math.random() * TIPS.size()) : Math.floorMod(md.tip++, TIPS.size());
        p.closeInventory();
        Sky.npcs().talk(p, HELPER_ID, List.of(TIPS.get(i)), null);
    }

    void claimKit(Player p) {
        MemberData md = memberData(p);
        if (md == null) { Msg.error(p, "Your profile is still loading - try again in a moment."); return; }
        if (md.kitClaimed) { Msg.error(p, "You already took your starter kit."); return; }
        List<IslandMath.KitEntry> kit = IslandMath.parseKit(dials.starterKit.get());
        // mark first and save, THEN give: a crash between the two loses the kit rather than duplicating it
        md.kitClaimed = true;
        md.kitClaimedAt = System.currentTimeMillis();
        Sky.profiles().saveNow(p);
        List<String> lines = new ArrayList<>();
        for (IslandMath.KitEntry e : kit) {
            if (!Sky.items().exists(e.id())) { Sky.log().info("[island] starter kit item " + e.id() + " is not registered; skipped"); continue; }
            ItemDef d = Sky.items().def(e.id());
            if (d != null && d.isUnique()) for (int i = 0; i < e.amount(); i++) Sky.loot().give(p, e.id(), 1, LootSource.REWARD);
            else Sky.loot().give(p, e.id(), e.amount(), LootSource.REWARD);
            String col = d == null || d.rarity == null ? "&f" : d.rarity.code();
            lines.add("&8+ " + col + (d == null || d.name == null ? Text.titleCase(e.id()) : d.name) + (e.amount() > 1 ? " &8x" + e.amount() : ""));
        }
        Msg.box(p, "&a&lSTARTER KIT", lines.isEmpty() ? List.of("&7Gerald rummaged around but found nothing to give.") : lines);
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
    }

    void welcome(Player p) {
        MemberData md = memberData(p);
        if (md != null) { if (md.welcomed) return; md.welcomed = true; }
        p.showTitle(Title.title(Text.c("&a&lYOUR ISLAND"), Text.c("&7Talk to &aGerald &7to get started"),
                Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3000), Duration.ofMillis(800))));
        Msg.box(p, "&a&lWELCOME TO YOUR PRIVATE ISLAND", List.of(
                "&7This floating island is yours to build on.",
                "&7Gerald, by the plaza, has a starter kit for you.",
                "&7The portal behind you leads to the &bHub&7.",
                "",
                "&e/is &7home  &e/is menu &7manage  &e/visit <name> &7explore"));
    }

    void visitByName(Player p, String name) {
        if (!svc.offCooldown(p)) return;
        Player t = Bukkit.getPlayerExact(name);
        if (t != null && Sky.profiles().isReady(t)) { svc.visit(p, IslandService.keyOf(t), t.getName(), false); return; }
        IslandStore.PlayerEntry e = store.byName(name);
        if (e == null || e.profileId() == null) { Msg.error(p, "Couldn't find a player called " + name + "."); return; }
        svc.visit(p, e.profileId(), e.name(), false);
    }

    // =========================================================================================
    // sidebar / tab
    // =========================================================================================

    private List<String> sidebar(Player p) {
        if (!svc.inIslandWorld(p.getLocation())) return List.of();
        String key = svc.keyAt(p.getLocation());
        if (key == null) return List.of();
        if (svc.isMember(p, key)) {
            int g = svc.guestsOn(key).size();
            return g == 0 ? List.of() : List.of("&7Guests: &b" + g + "&7/&b" + svc.guestLimit(key));
        }
        return List.of("&7Visiting: &b" + svc.islandTitle(key));
    }

    private List<String> tab(Player p) {
        if (!svc.inIslandWorld(p.getLocation())) return List.of();
        String key = svc.keyAt(p.getLocation());
        if (key == null) return List.of();
        IslandSettings s = store.settings(key);
        int size = svc.sizeOf(key);
        return List.of("&b&lIsland:",
                " " + (s.islandName != null ? "Name: &a" + s.islandName : "Owner: &a" + svc.ownerLabel(key)),
                " Size: &a" + size + "x" + size,
                " Guests: &b" + svc.guestsOn(key).size() + "&7/&b" + svc.guestLimit(key),
                " Visitors: " + IslandMath.visitorsLabel(s.visitors));
    }

    // =========================================================================================
    // player commands
    // =========================================================================================

    private void registerCommands() {
        Sky.commands().register(SkyCommand.of("is", "Go to your private island; /is menu to manage it")
                .alias("island", "home").category("Island").playerOnly()
                .usage("[menu | settings | setspawn | portals | coop | perm <member> <perm> <on|off> | transfer <member> | decor | parkour | name <name> | rank <player> <rank> | ranks | guests | kick <player> | kickall | ban <player> | unban <player> | bans | visit <player>]")
                .run((s, a) -> isCommand((Player) s, a))
                .tab((s, a) -> {
                    if (a.length <= 1) return List.of("menu", "settings", "setspawn", "portals", "coop", "perm", "transfer", "decor", "parkour", "name", "rank", "ranks", "guests", "kick", "kickall", "ban", "unban", "bans", "visit", "help");
                    if (a.length == 2 && "parkour".equalsIgnoreCase(a[0])) return List.of("times", "checkpoint", "restart", "cancel");
                    if (a.length == 2 && "rank".equalsIgnoreCase(a[0])) return onlineNames();
                    if (a.length == 3 && "rank".equalsIgnoreCase(a[0])) return IslandSocial.rankIds();
                    if (a.length == 2 && "name".equalsIgnoreCase(a[0])) return List.of("reset");
                    if (a.length == 2 && List.of("perm", "transfer").contains(a[0].toLowerCase(Locale.ROOT))) return memberNames((Player) s);
                    if (a.length == 3 && "perm".equalsIgnoreCase(a[0])) { List<String> l = new ArrayList<>(); for (CoopPerm c : CoopPerm.values()) l.add(c.id); l.add("all"); return l; }
                    if (a.length == 4 && "perm".equalsIgnoreCase(a[0])) return List.of("on", "off");
                    if (a.length == 2 && List.of("kick", "ban", "visit").contains(a[0].toLowerCase(Locale.ROOT))) return onlineNames();
                    return List.of();
                }));
        Sky.commands().register(SkyCommand.of("visit", "Visit another player's private island").category("Island").playerOnly()
                .usage("[player]")
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (a.length == 0) menus.visitList(p, 0).open(p);
                    else visitByName(p, a[0]);
                })
                .tab((s, a) -> a.length <= 1 ? onlineNames() : List.of()));
        Sky.commands().register(SkyCommand.of("setspawn", "Set your private island's spawn to where you stand").category("Island").playerOnly()
                .run((s, a) -> svc.setSpawn((Player) s)));
    }

    private List<String> memberNames(Player p) {
        List<String> out = new ArrayList<>();
        for (UUID u : IslandService.members(IslandService.keyOf(p))) if (!u.equals(p.getUniqueId())) out.add(svc.nameOf(u));
        return out;
    }

    /** A current member of island {@code key} by name (online or known), or null. */
    UUID memberByName(String key, String name) {
        for (UUID u : IslandService.members(key)) if (svc.nameOf(u).equalsIgnoreCase(name)) return u;
        return null;
    }

    /** /is perm <member> <perm|all> <on|off> - owner only. */
    private void permCommand(Player p, String key, String[] a) {
        if (a.length < 4) { Msg.error(p, "Usage: /is perm <member> <build|break|containers|minions|manage|all> <on|off>"); return; }
        UUID u = memberByName(key, a[1]);
        if (u == null) { Msg.error(p, a[1] + " is not a member of your co-op."); return; }
        String v = a[3].toLowerCase(Locale.ROOT);
        boolean on = v.equals("on") || v.equals("true") || v.equals("yes") || v.equals("allow");
        Set<CoopPerm> which = CoopPerm.parseSet(a[2]);
        if (which.isEmpty()) { Msg.error(p, "Unknown permission " + a[2] + ". Use build, break, containers, minions, manage or all."); return; }
        for (CoopPerm c : which) {
            String err = svc.setPerm(p, key, u, c, on);
            if (err != null) { Msg.error(p, err); return; }
        }
        Msg.success(p, (on ? "Allowed " : "Denied ") + svc.nameOf(u) + ": " + a[2].toLowerCase(Locale.ROOT) + ".");
    }

    private static List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getName());
        return out;
    }

    private void isCommand(Player p, String[] a) {
        if (a.length == 0) { if (svc.offCooldown(p)) svc.goHome(p); return; }
        String key = IslandService.keyOf(p);
        switch (a[0].toLowerCase(Locale.ROOT)) {
            // the welcome box reads "/is home" as plain text (wave-5 regression playtest: it printed the help)
            case "home", "go", "warp", "tp" -> { if (svc.offCooldown(p)) svc.goHome(p); }
            case "menu", "manage", "management" -> menus.management(p).open(p);
            case "settings", "options" -> menus.settings(p).open(p);
            case "setspawn", "sethome" -> svc.setSpawn(p);
            case "portals", "portal" -> menus.portals(p).open(p);
            case "coop", "perms", "permissions" -> menus.coop(p).open(p);
            case "perm", "permission" -> permCommand(p, key, a);
            case "transfer", "owner" -> {
                if (a.length < 2) { Msg.error(p, "Usage: /is transfer <member>"); return; }
                UUID u = memberByName(key, a[1]);
                if (u == null) { Msg.error(p, a[1] + " is not a member of your co-op."); return; }
                menus.transferConfirm(p, u);
            }
            case "decor", "decorations", "social" -> social.decorations(p).open(p);
            case "parkour", "pk" -> decor.parkourCommand(p, a);
            case "name", "rename" -> social.nameCommand(p, a);
            case "rank" -> social.rankCommand(p, a);
            case "ranks" -> social.ranks(p, 0).open(p);
            case "guests", "visitors" -> menus.guests(p).open(p);
            case "bans", "banlist" -> menus.bans(p).open(p);
            case "kickall" -> { if (!menus.manage(p, key)) return; int n = menus.kickAll(p, key); Msg.success(p, "Kicked " + n + " guest" + (n == 1 ? "" : "s") + "."); }
            case "kick" -> {
                if (!menus.manage(p, key)) return;
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (t == null || !key.equals(svc.guestAt.get(t.getUniqueId()))) { Msg.error(p, "That player is not a guest on your island."); return; }
                svc.sendOff(t, p.getName() + " kicked you from their island.");
                Msg.success(p, "Kicked " + t.getName() + ".");
            }
            case "ban" -> {
                if (!menus.manage(p, key)) return;
                if (a.length < 2) { Msg.error(p, "Usage: /is ban <player>"); return; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t != null) {
                    if (svc.isMember(t, key)) { Msg.error(p, "You can't ban a co-op member."); return; }
                    menus.ban(p, key, t.getUniqueId(), t.getName());
                    return;
                }
                IslandStore.PlayerEntry e = store.byName(a[1]);
                if (e == null) { Msg.error(p, "Couldn't find a player called " + a[1] + "."); return; }
                if (key.equals(e.profileId())) { Msg.error(p, "You can't ban a co-op member."); return; }
                menus.ban(p, key, e.uuid(), e.name());
            }
            case "unban" -> {
                if (!menus.manage(p, key)) return;
                if (a.length < 2) { Msg.error(p, "Usage: /is unban <player>"); return; }
                IslandStore.PlayerEntry e = store.byName(a[1]);
                Player t = Bukkit.getPlayerExact(a[1]);
                String id = t != null ? t.getUniqueId().toString() : e != null ? e.uuid().toString() : null;
                IslandSettings s = store.settings(key);
                boolean removed;
                synchronized (s) { removed = id != null && s.banned.remove(id); }
                if (!removed) { Msg.error(p, a[1] + " is not banned from your island."); return; }
                store.save(key);
                Msg.success(p, "Unbanned " + a[1] + ".");
            }
            case "visit" -> {
                if (a.length < 2) menus.visitList(p, 0).open(p);
                else visitByName(p, a[1]);
            }
            default -> Msg.send(p, List.of("&a&lPrivate Island",
                    " &e/is &7- go to your island",
                    " &e/is menu &7- island management",
                    " &e/is settings &7- visitors and guest permissions",
                    " &e/is coop &7- co-op member permissions (owner)",
                    " &e/is perm <member> <perm|all> <on|off>&7, &e/is transfer <member>",
                    " &e/is decor &7- parkour, displays, egg hunt, showcases, NPCs",
                    " &e/is parkour [times|checkpoint|restart|cancel]",
                    " &e/is name <name> &7(Social " + dials.nameSocial.get() + ")&7, &e/is rank <player> <friend|trusted|guest> &7(Social " + dials.rankSocial.get() + ")",
                    " &e/setspawn &7- set your island spawn",
                    " &e/is guests&7, &e/is kick <p>&7, &e/is kickall",
                    " &e/is ban <p>&7, &e/is unban <p>&7, &e/is bans",
                    " &e/visit <player> &7- visit someone's island"));
        }
    }

    // =========================================================================================
    // admin: /sb isle ...   (/sb island belongs to core's world tools)
    // =========================================================================================

    private void registerAdmin() {
        Sky.commands().admin("isle",
                "<info|home|tp|visit|kit|helper|owner|perm|settings|guests|kickall|spawn|portals|retrofit|bridges|decor|name|rank|reset> <player> [...]",
                "Private islands: inspect, send home, force-visit, starter kit, helper NPC, settings, guests, reset",
                this::admin,
                (s, a) -> {
                    if (a.length <= 1) return List.of("info", "home", "tp", "visit", "kit", "helper", "owner", "perm", "settings", "guests", "kickall", "spawn", "portals", "retrofit", "bridges", "decor", "name", "rank", "reset");
                    if (a.length == 3 && "decor".equalsIgnoreCase(a[0])) return List.of("list", "wipe");
                    if (a.length == 4 && "rank".equalsIgnoreCase(a[0])) return IslandSocial.rankIds();
                    if (a.length == 2 || (a.length == 3 && "visit".equalsIgnoreCase(a[0]))) return onlineNames();
                    if (a.length == 3 && "kit".equalsIgnoreCase(a[0])) return List.of("give", "reset", "status");
                    if (a.length == 3 && "settings".equalsIgnoreCase(a[0])) return List.of("visitors", "containers", "doors", "pickup", "offline");
                    if (a.length == 3 && "reset".equalsIgnoreCase(a[0])) return List.of("confirm");
                    return List.of();
                });
    }

    private void admin(CommandSender s, String[] a) {
        if (a.length == 0) { s.sendMessage(Text.c("&cUsage: /sb isle <info|home|tp|visit|kit|helper|owner|perm|portals|retrofit|bridges|settings|guests|kickall|spawn|reset> <player> ...")); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("guests")) {
            Map<String, List<String>> by = new java.util.TreeMap<>();
            for (Map.Entry<java.util.UUID, String> e : svc.guestAt.entrySet()) {
                Player g = Bukkit.getPlayer(e.getKey());
                by.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(g == null ? e.getKey().toString() : g.getName());
            }
            tell(s, "&aIslands with guests: &f" + by.size() + " &7(profiles known: " + store.profileCount() + ", players: " + store.playerCount() + ")");
            for (var e : by.entrySet()) tell(s, " &b" + svc.ownerLabel(e.getKey()) + " &8(" + e.getKey() + ")&7: &f" + String.join(", ", e.getValue()));
            return;
        }
        if (a.length < 2) { tell(s, "&cUsage: /sb isle " + sub + " <player>"); return; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null || !Sky.profiles().isReady(t)) { tell(s, "&c" + a[1] + " is not online (or still loading)."); return; }
        String key = IslandService.keyOf(t);
        switch (sub) {
            case "info" -> {
                long[] o = svc.origin(key);
                int[] up = svc.upgrades(key);
                IslandSettings st = store.settings(key);
                var f = Sky.worlds().ensureInstance(ISLAND, key);
                tell(s, "&aIsland of &f" + t.getName() + " &7key &f" + key);
                tell(s, " &7origin &f" + (o == null ? "?" : o[0] + ", " + o[1]) + " &7built &f" + (f.isDone() && !f.isCompletedExceptionally()));
                tell(s, " &7size &f" + up[0] + " &7(tier " + up[3] + ")  guest limit &f" + svc.guestLimit(key) + " &7(upgrade tier " + up[4] + ")");
                tell(s, " &7visitors &f" + st.visitors + " &7containers " + st.guestContainers + " doors " + st.guestDoors + " pickup " + st.guestPickup + " offline " + st.offlineVisits);
                tell(s, " &7spawn " + (st.homeSet ? "custom (" + fmt(st.hx) + ", " + fmt(st.hy) + ", " + fmt(st.hz) + ")" : "default")
                        + " &7banned " + st.banned.size() + " &7unique guests " + st.uniqueGuests.size() + " visits " + st.totalVisits);
                tell(s, " &7guests now &f" + svc.guestsOn(key).size() + " &7members online &f" + svc.membersOnline(key).size()
                        + " &7on own island &f" + svc.isOnOwnIsland(t) + " &7helper &f" + svc.helperKeys.contains(key));
                MemberData md = memberData(t);
                if (md != null) tell(s, " &7kit claimed &f" + md.kitClaimed + " &7met Gerald &f" + md.metHelper);
                UUID own = svc.ownerOf(key);
                tell(s, " &7owner &6" + (own == null ? "unknown" : svc.nameOf(own)) + " &7co-op permissions " + (svc.dials().coopPermissions.get() ? "&aon" : "&coff"));
                tell(s, " &7name &f" + (st.islandName == null ? "-" : st.islandName) + " &7ranks &f" + st.ranks.size() + " &7decorations &f" + st.decor.placed.size() + " &8(/sb isle decor " + t.getName() + ")");
                for (UUID u : IslandService.members(key)) {
                    if (u.equals(own)) continue;
                    tell(s, "  &7- &f" + svc.nameOf(u) + "&7: " + CoopPerm.summary(st.coopPerms.get(u.toString())));
                }
            }
            case "home" -> { svc.goHome(t); tell(s, "&aSending " + t.getName() + " home."); }
            case "tp" -> {
                if (!(s instanceof Player admin)) { tell(s, "&cOnly a player can teleport."); return; }
                svc.visit(admin, key, t.getName(), true);
            }
            case "visit" -> {
                if (a.length < 3) { tell(s, "&cUsage: /sb isle visit <visitor> <host>"); return; }
                Player h = Bukkit.getPlayerExact(a[2]);
                if (h == null) { tell(s, "&c" + a[2] + " is not online."); return; }
                String why = svc.visitBlocker(t, IslandService.keyOf(h));
                tell(s, "&7Visit check: " + (why == null ? "&aallowed" : "&c" + why) + " &7(forcing anyway)");
                svc.visit(t, IslandService.keyOf(h), h.getName(), true);
            }
            case "kit" -> {
                MemberData md = memberData(t);
                String op = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "status";
                switch (op) {
                    case "reset" -> { md.kitClaimed = false; md.metHelper = false; md.welcomed = false; tell(s, "&aReset kit/intro for " + t.getName()); }
                    case "give" -> { md.kitClaimed = false; claimKit(t); tell(s, "&aGave the starter kit to " + t.getName()); }
                    default -> tell(s, "&7kit claimed: &f" + md.kitClaimed + " &7at " + md.kitClaimedAt);
                }
            }
            case "helper" -> { svc.spawnHelper(key); tell(s, "&aRespawned Gerald on " + t.getName() + "'s island."); }
            case "owner" -> {
                if (a.length < 3) {
                    UUID o = svc.ownerOf(key);
                    tell(s, "&7Owner of " + t.getName() + "'s island: &6" + (o == null ? "unknown (nobody restricted)" : svc.nameOf(o) + " &8(" + o + ")"));
                    tell(s, "&7Set it: /sb isle owner " + t.getName() + " <member>");
                    return;
                }
                UUID u = memberByName(key, a[2]);
                if (u == null) { tell(s, "&c" + a[2] + " is not a current member of that co-op."); return; }
                svc.setOwner(key, u);
                tell(s, "&a" + svc.nameOf(u) + " now owns " + t.getName() + "'s island.");
            }
            case "perm" -> {
                if (a.length < 5) { tell(s, "&cUsage: /sb isle perm <player> <member> <build|break|containers|minions|manage|all> <on|off>"); return; }
                UUID u = memberByName(key, a[2]);
                if (u == null) { tell(s, "&c" + a[2] + " is not a current member of that co-op."); return; }
                Set<CoopPerm> which = CoopPerm.parseSet(a[3]);
                if (which.isEmpty()) { tell(s, "&cUnknown permission " + a[3]); return; }
                boolean on = List.of("on", "true", "yes", "allow").contains(a[4].toLowerCase(Locale.ROOT));
                for (CoopPerm c : which) svc.setPermForce(key, u, c, on);
                tell(s, "&aSet " + a[3] + " = " + on + " for " + svc.nameOf(u) + " on " + t.getName() + "'s island"
                        + (u.equals(svc.ownerOf(key)) ? " &e(they own it, so it changes nothing)" : "") + ".");
            }
            case "retrofit" -> {
                portals.retrofit(key, s);
            }
            case "decor" -> {
                String op = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "list";
                if (op.equals("wipe")) { tell(s, "&aForgot " + decor.wipe(key) + " decorations of " + t.getName() + "'s island (blocks left as plain blocks, nothing refunded)."); return; }
                Decor.State ds = decor.state(key);
                Decor.Course c = Decor.course(ds);
                tell(s, "&aDecorations of " + t.getName() + "'s island &7(" + ds.placed.size() + ", course " + (c.ok() ? "ok: " + c.checkpoints().size() + " checkpoints" + (c.loop() ? ", loop" : "") : "&e" + c.problem()) + "&7)");
                for (Decor.Placed d : ds.placed)
                    tell(s, " &7#" + d.seq + " &f" + d.kind + " &7at " + d.pos() + (d.name != null ? " &a" + d.name : "") + (d.item != null ? " &b(item)" : ""));
                tell(s, " &7best times " + ds.parkourBest.size() + ", egg hunters " + ds.eggsFound.size() + ", runs now " + decor.activeRuns());
            }
            case "name" -> {
                if (a.length < 3) { tell(s, "&7Name: &f" + svc.islandTitle(key) + " &8(/sb isle name <p> <name|reset>)"); return; }
                String raw = String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length));
                IslandSettings st = store.settings(key);
                String clean = raw.equalsIgnoreCase("reset") ? null : Decor.cleanText(raw, Math.max(4, dials.nameMax.get()));
                synchronized (st) { st.islandName = clean; }
                store.save(key);
                tell(s, "&aIsland name of " + t.getName() + ": &f" + svc.islandTitle(key));
            }
            case "rank" -> {
                if (a.length < 4) { tell(s, "&cUsage: /sb isle rank <host> <player> <friend|trusted|guest>"); return; }
                IslandRank r = IslandRank.parse(a[3]);
                IslandStore.PlayerEntry e = store.byName(a[2]);
                Player tp = Bukkit.getPlayerExact(a[2]);
                UUID u = tp != null ? tp.getUniqueId() : e != null ? e.uuid() : null;
                if (r == null || u == null) { tell(s, "&cUnknown rank or player."); return; }
                IslandSettings st = store.settings(key);
                synchronized (st) { IslandRank.set(st.ranks, u.toString(), r, 0); }
                store.save(key);
                tell(s, "&a" + a[2] + " is now " + r.label + " on " + t.getName() + "'s island.");
            }
            case "bridges" -> {
                if (bridges.repair(key, s) < 0) tell(s, "&e" + t.getName() + "'s bridges are not loaded - stand near them and try again.");
            }
            case "portals" -> {
                tell(s, "&dPortal Isle of " + t.getName() + "'s island &7(lit when any online member has visited + meets the warp requirement):");
                for (String[] g : portals.status(key))
                    tell(s, " &7- &f" + g[0] + " &8(" + g[1] + ") " + ("open".equals(g[2]) ? "&aopen" : "closed".equals(g[2]) ? "&8closed (no warp/island)" : "&clocked"));
            }
            case "settings" -> {
                if (a.length < 4) { tell(s, "&cUsage: /sb isle settings <player> <visitors|containers|doors|pickup|offline> <value>"); return; }
                IslandSettings st = store.settings(key);
                String v = a[3].toLowerCase(Locale.ROOT);
                boolean b = v.equals("true") || v.equals("on") || v.equals("yes");
                switch (a[2].toLowerCase(Locale.ROOT)) {
                    case "visitors" -> svc.setVisitors(key, v);
                    case "containers" -> { synchronized (st) { st.guestContainers = b; } }
                    case "doors" -> { synchronized (st) { st.guestDoors = b; } }
                    case "pickup" -> { synchronized (st) { st.guestPickup = b; } }
                    case "offline" -> { synchronized (st) { st.offlineVisits = b; } }
                    default -> { tell(s, "&cUnknown setting " + a[2]); return; }
                }
                store.save(key);
                tell(s, "&aSet " + a[2] + " = " + v + " for " + t.getName() + "'s island.");
            }
            case "kickall" -> tell(s, "&aKicked " + menus.kickAll(t, key) + " guests.");
            case "spawn" -> {
                Location l = svc.homeOf(key);
                tell(s, "&7Home of " + t.getName() + ": &f" + (l == null ? "none" : fmt(l.getX()) + ", " + fmt(l.getY()) + ", " + fmt(l.getZ())));
            }
            case "reset" -> {
                if (a.length < 3 || !"confirm".equalsIgnoreCase(a[2])) {
                    tell(s, "&cThis gives " + t.getName() + "'s profile a brand-new island (the old one is abandoned). Add 'confirm'.");
                    return;
                }
                svc.despawnHelper(key);
                decor.wipe(key);       // the decorations stood on the old island's blocks
                Sky.worlds().releaseInstance(ISLAND, key);
                Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                    svc.resetSpawn(key);
                    svc.forget(key);
                    svc.goHome(t);
                }, 2L);
                tell(s, "&aReleased the old island; building a new one for " + t.getName() + ".");
            }
            default -> tell(s, "&cUnknown sub-command " + sub);
        }
    }

    private static String fmt(double d) { return String.format(Locale.ROOT, "%.1f", d); }

    private static void tell(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    // =========================================================================================
    // self-tests
    // =========================================================================================

    private void registerSelfTests() {
        Sky.commands().selfTest("island.generator-walkable", () -> {
            PrivateIslandGenerator gen = new PrivateIslandGenerator();
            IslandRun.Result run = IslandRun.run(gen, IslandRun.seedOf(ISLAND));
            Walkability.Report r = Walkability.analyse(run.canvas, run.layout, new Walkability.Options());
            if (!r.ok()) return "not walkable: " + r.failed() + " " + r.failedPortals() + " " + r.spawnProblem;
            if (r.floatingComponents > 0 && r.floating.stream().anyMatch(f -> !f.decorative())) return "floating blocks: " + r.floating;
            if (run.outsideBounds > 0) return run.outsideBounds + " blocks written outside bounds, e.g. " + run.outsideSamples;
            return IslandChecks.layoutProblems(run.layout);
        });
        Sky.commands().selfTest("island.template-layout", () -> {
            Layout l = Sky.worlds().layout(ISLAND);
            if (l == null) return "no template layout for " + ISLAND + " (island not registered or not computed)";
            String p = IslandChecks.layoutProblems(l);
            if (p != null) return p;
            if (!Sky.worlds().warps().contains("hub")) return "portal.hub needs a 'hub' warp and none is registered (hub module missing?)";
            if (!Sky.worlds().warps().contains("private_island") || !Sky.worlds().warps().contains("home")) return "island warps missing";
            return null;
        });
        Sky.commands().selfTest("island.portal-isle", () -> {
            if (portals.gates().size() != PrivateIslandGenerator.ALL_PORTALS.length)
                return "template has " + portals.gates().size() + " of " + PrivateIslandGenerator.ALL_PORTALS.length + " Portal Isle + Far Court openings";
            List<String> bad = new ArrayList<>();
            var warps = IslandPortals.warpMap();
            for (String[] d : PrivateIslandGenerator.ALL_PORTALS) {
                var w = warps.get(d[0]);
                if (w == null) bad.add(d[0] + " (no warp)");
                else if (Sky.worlds().island(w.island()) == null) bad.add(d[0] + " (warp to unknown island " + w.island() + ")");
            }
            if (Sky.worlds().layout(ISLAND).area("portal_isle") == null) bad.add("area portal_isle");
            if (Sky.worlds().layout(ISLAND).area("far_court") == null) bad.add("area far_court");
            return bad.isEmpty() ? null : "Portal Isle destinations missing: " + bad;
        });
        Sky.commands().selfTest("island.coop-permissions", () -> {
            java.util.UUID owner = java.util.UUID.randomUUID(), member = java.util.UUID.randomUUID();
            java.util.Map<String, CoopPerm.Toggles> perms = new java.util.HashMap<>();
            perms.put(member.toString(), CoopPerm.Toggles.of(java.util.EnumSet.of(CoopPerm.BUILD)));
            for (CoopPerm c : CoopPerm.values())
                if (!CoopPerm.allowed(owner, c, owner, perms, java.util.EnumSet.noneOf(CoopPerm.class))) return "owner lacks " + c.id;
            if (!CoopPerm.allowed(member, CoopPerm.BUILD, owner, perms, Set.of()) || CoopPerm.allowed(member, CoopPerm.CONTAINERS, owner, perms, Set.of()))
                return "member toggles not honoured";
            String spec = dials.coopDefaults.get();
            for (String part : spec == null ? new String[0] : spec.split("[,; ]+"))
                if (!part.isBlank() && !part.equalsIgnoreCase("all") && !part.equalsIgnoreCase("none") && CoopPerm.parse(part) == null)
                    return "island.coop-default-perms has an unknown permission '" + part + "'";
            if (!Sky.requirements().known("island")) return "requirement prefix 'island' not registered";
            if (Sky.requirements().describe("island:perm:minions").isBlank()) return "island:perm:minions has no description";
            return null;
        });
        Sky.commands().selfTest("island.far-court", () -> {
            Layout l = Sky.worlds().layout(ISLAND);
            if (l == null) return "no template layout";
            List<String> bad = new ArrayList<>();
            var warps = IslandPortals.warpMap();
            for (String[] d : PrivateIslandGenerator.FAR_PORTALS) {
                if (l.area(PrivateIslandGenerator.PORTAL_AREA + d[0]) == null) bad.add(d[0] + " (no opening in the template)");
                var w = warps.get(d[0]);
                if (w == null) bad.add(d[0] + " (no warp registered)");
                else if (Sky.worlds().island(w.island()) == null) bad.add(d[0] + " (warp to unknown island " + w.island() + ")");
            }
            if (l.area("far_court") == null) bad.add("area far_court");
            var box = IslandPortals.RETRO_FAR;
            for (String[] d : PrivateIslandGenerator.FAR_PORTALS) {
                var a = l.area(PrivateIslandGenerator.PORTAL_AREA + d[0]);
                if (a != null && !(box.contains(a.minX(), a.minY(), a.minZ()) && box.contains(a.maxX(), a.maxY(), a.maxZ()))) bad.add(d[0] + " outside the retrofit box");
            }
            return bad.isEmpty() ? null : String.join(", ", bad);
        });
        // ---- wave 5b ----
        Sky.commands().selfTest("island.decor-rules", () -> {
            Decor.State s = new Decor.State();
            Decor.add(s, Decor.START, 0, 70, 0, 1, "a");
            Decor.add(s, Decor.POINT, 5, 71, 0, 2, "a");
            Decor.add(s, Decor.POINT, 10, 72, 0, 3, "a");
            Decor.add(s, Decor.START, 15, 73, 0, 4, "a");
            Decor.Course c = Decor.course(s);
            if (!c.ok() || c.loop() || c.checkpoints().size() != 2) return "course not start -> 2 checkpoints -> finish: " + c;
            Decor.Run[] out = new Decor.Run[1];
            if (Decor.step(c, null, "0,70,0", 1000, out) != Decor.Step.STARTED) return "start plate did not start a run";
            Decor.Run r = out[0];
            if (Decor.step(c, r, "10,72,0", 2000, out) != Decor.Step.WRONG_CHECKPOINT) return "skipping checkpoint 1 was allowed";
            if (Decor.step(c, r, "15,73,0", 2100, out) != Decor.Step.MISSED) return "finish without the checkpoints counted";
            Decor.step(c, r, "5,71,0", 3000, out);
            Decor.step(c, r, "10,72,0", 4000, out);
            if (Decor.step(c, r, "15,73,0", 5250, out) != Decor.Step.FINISHED || r.finalMs != 4250) return "finish did not time 4.25 s";
            Decor.add(s, Decor.EGG, 1, 70, 1, 5, "a");
            if (!Decor.findEgg(s, "g", "1,70,1", 10) || Decor.findEgg(s, "g", "1,70,1", 10)) return "an egg can be found twice";
            if (Decor.placeProblem(s, Decor.START, Map.of(Decor.START, 2)) == null) return "a third Start/End plate is allowed";
            return null;
        });
        Sky.commands().selfTest("island.decor-items", () -> {
            if (!dials.decorEnabled.get()) return null;
            List<String> bad = new ArrayList<>();
            for (String k : Decor.KINDS) {
                ItemDef d = Sky.items().def(k);
                if (d == null) { bad.add(k + " (not registered - the shops module sells these)"); continue; }
                if (Decor.isBlock(k) && !d.has("placeable")) bad.add(k + " (not placeable)");
                if (Decor.isBlock(k) && !Decor.materialMatches(k, d.material)) bad.add(k + " (material " + d.material + " is not what the island tracks)");
            }
            return bad.isEmpty() ? null : "decorations: " + bad;
        });
        Sky.commands().selfTest("island.ranks-and-names", () -> {
            Map<String, String> ranks = new java.util.HashMap<>();
            if (!IslandRank.set(ranks, "a", IslandRank.TRUSTED, 1) || IslandRank.set(ranks, "b", IslandRank.FRIEND, 1)) return "rank cap not honoured";
            IslandRank t = IslandRank.of(ranks, "a");
            if (!t.containers(false) || !t.pickup(false) || !t.ignoresGuestLimit()) return "Trusted does not loosen the guest rules";
            if (IslandRank.of(ranks, "b").containers(false) || IslandRank.GUEST.doors(false)) return "a Guest got more than the island allows";
            if (!"Red Name".equals(Decor.cleanText("&cRed §lName<>", 24))) return "colour codes / symbols survive cleanText: " + Decor.cleanText("&cRed §lName<>", 24);
            if (!Sky.requirements().known("skill")) return "requirement prefix 'skill' unknown - Social 9/20 gates would always fail";
            return null;
        });
        Sky.commands().selfTest("island.size-formula", () -> {
            String p = IslandChecks.sizeProblems(svc.sizeTable);
            if (p != null) return p;
            if (IslandMath.guestLimit(dials.guestLimit.get(), 0) != dials.guestLimit.get()) return "guest limit formula";
            return null;
        });
        Sky.commands().selfTest("island.starter-kit-items", () -> {
            List<String> missing = new ArrayList<>();
            List<IslandMath.KitEntry> kit = IslandMath.parseKit(dials.starterKit.get());
            if (kit.isEmpty()) return "starter kit dial is empty";
            for (IslandMath.KitEntry e : kit) if (!Sky.items().exists(e.id())) missing.add(e.id());
            return missing.isEmpty() ? null : "starter kit ids not registered: " + missing;
        });
        Sky.commands().selfTest("island.service-and-npc", () -> {
            if (!Sky.has(Features.PrivateIsland.class)) return "Features.PrivateIsland not provided";
            if (Sky.service(Features.PrivateIsland.class) != svc) return "another module replaced Features.PrivateIsland";
            if (Sky.npcs().def(HELPER_ID) == null) return "helper NPC not registered";
            IslandDef d = Sky.worlds().island(ISLAND);
            if (d == null || !d.instanced) return "private_island is not registered as instanced";
            if (d.instanceSpacing < 2 * PrivateIslandGenerator.MAX_HALF + 32) return "instance spacing " + d.instanceSpacing + " is smaller than the largest island";
            return null;
        });
        Sky.commands().selfTest("island.menus-build", () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!Sky.profiles().isReady(p)) continue;
                menus.management(p);
                menus.settings(p);
                menus.helper(p);
                menus.portals(p);
                menus.coop(p);
                menus.memberPerms(p, p.getUniqueId());
                social.decorations(p);
                social.ranks(p, 0);
                sidebar(p);
                tab(p);
                return null;
            }
            return null;   // nobody online: nothing to build against
        });
    }

    /** Box of the build square of an island of {@code size}, template-relative (for docs/tests). */
    static Box buildBox(int size, int minY, int maxY) {
        int h = IslandMath.half(size);
        return Box.of(-h, minY, -h, h, maxY, h);
    }
}
