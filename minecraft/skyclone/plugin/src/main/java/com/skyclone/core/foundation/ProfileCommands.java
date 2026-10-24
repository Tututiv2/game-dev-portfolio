package com.skyclone.core.foundation;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.config.Dials;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.MenuService;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.core.foundation.ProfileModel.ProfileRow;
import com.skyclone.core.foundation.ProfileModel.Session;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * /profiles (Profile Management menu) and /coop. Switching profile is done by saving and
 * kicking with a friendly message: reloading every module's live state in place is not safe, and a
 * rejoin goes through the same proven load path as every login.
 */
final class ProfileCommands {

    record Invite(UUID inviter, String inviterName, String profileId, String profileName, long expires) {}

    private final ProfileServiceImpl profiles;
    private final EconomyServiceImpl economy;
    private final Map<UUID, Invite> invites = new ConcurrentHashMap<>();
    private final Map<UUID, Long> createCooldown = new ConcurrentHashMap<>();
    private final Dials.Dial<Integer> inviteSeconds;

    ProfileCommands(ProfileServiceImpl profiles, EconomyServiceImpl economy, Dials dials) {
        this.profiles = profiles;
        this.economy = economy;
        this.inviteSeconds = dials.integer("profiles.coop-invite-seconds", 60, "How long a /coop invite stays valid");
    }

    void register(CommandServiceImpl cmds) {
        cmds.register(SkyCommand.of("profiles", "Profile Management: see, create and switch profiles")
                .alias("profile", "pm").category("Profile").playerOnly()
                .usage("[list | switch <name> | create]")
                .run(this::profilesCmd)
                .tab((s, a) -> a.length <= 1 ? List.of("list", "switch", "create") : List.of()));
        cmds.register(SkyCommand.of("coop", "Co-op: invite players to your profile, accept, list members, leave")
                .category("Profile").playerOnly()
                .usage("<invite <player> | accept | deny | list | leave>")
                .run(this::coopCmd)
                .tab((s, a) -> {
                    if (a.length <= 1) return List.of("invite", "accept", "deny", "list", "leave");
                    if (a.length == 2 && a[0].equalsIgnoreCase("invite")) return onlineNames(s);
                    return List.of();
                }));
        cmds.register(SkyCommand.of("coopadd", "Invite a player to your co-op (same as /coop invite)")
                .category("Profile").playerOnly().usage("<player>")
                .run((s, a) -> coopCmd(s, prepend("invite", a)))
                .tab((s, a) -> a.length <= 1 ? onlineNames(s) : List.of()));
    }

    static List<String> onlineNames(CommandSender viewer) {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (viewer instanceof Player v && !v.canSee(p)) continue;
            out.add(p.getName());
        }
        return out;
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    void forget(UUID u) {
        invites.remove(u);
        createCooldown.remove(u);
    }

    // ---- /profiles -------------------------------------------------------------------------------

    private void profilesCmd(CommandSender sender, String[] args) {
        Player p = (Player) sender;
        if (!profiles.isLoaded(p)) { Msg.error(p, "Your profile is still loading."); return; }
        if (args.length == 0) { openMenu(p); return; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> withRows(p, rows -> sendList(p, rows));
            case "create", "new" -> askCreate(p);
            case "switch", "select" -> {
                if (args.length < 2) { Msg.error(p, "Usage: /profiles switch <name>"); return; }
                String want = args[1];
                withRows(p, rows -> {
                    for (ProfileRow r : rows) {
                        if (r.name().equalsIgnoreCase(want)) { askSwitch(p, r); return; }
                    }
                    Msg.error(p, "You have no profile called '" + want + "'.");
                });
            }
            default -> Msg.error(p, "Usage: /profiles [list | switch <name> | create]");
        }
    }

    private void withRows(Player p, java.util.function.Consumer<List<ProfileRow>> then) {
        profiles.listProfiles(p.getUniqueId()).whenComplete((rows, ex) -> Bukkit.getScheduler().runTask(profiles.plugin, () -> {
            if (!p.isOnline()) return;
            if (ex != null) {
                profiles.log.log(Level.WARNING, "[profiles] listing profiles failed", ex);
                Msg.error(p, "Could not read your profiles right now.");
                return;
            }
            then.accept(rows);
        }));
    }

    private void sendList(Player p, List<ProfileRow> rows) {
        Msg.send(p, "&aYour profiles &7(" + rows.size() + "/" + profiles.maxProfiles.get() + "):");
        for (ProfileRow r : rows) {
            Msg.send(p, (r.active() ? " &a● " : " &7○ ") + "&e" + r.name() + " &7- " + modeLabel(r)
                    + " &7- purse &6" + Coins.formatTenths(r.purse()) + (r.active() ? " &a(current)" : ""));
        }
        Msg.send(p, "&7Switch with &e/profiles switch <name>&7, create with &e/profiles create&7.");
    }

    static String modeLabel(ProfileRow r) {
        String m = r.mode() == null || r.mode().equals("normal") ? "Classic" : Text.titleCase(r.mode());
        return r.members() > 1 ? m + " Co-op (" + r.members() + ")" : m;
    }

    void openMenu(Player p) {
        withRows(p, rows -> {
            if (!Sky.has(MenuService.class)) { sendList(p, rows); return; }
            buildMenu(p, rows).open(p);
        });
    }

    private Menu buildMenu(Player p, List<ProfileRow> rows) {
        int max = Math.max(1, Math.min(7, profiles.maxProfiles.get()));
        Menu m = new Menu("Profile Management", 4);
        m.chrome();
        Session s = profiles.session(p);
        String current = s == null ? "?" : s.profile.name;
        m.set(4, Icons.playerHead(p.getUniqueId(), "&aProfile Management", List.of(
                "&7Each profile is a separate", "&7SkyBlock adventure with its own",
                "&7island, skills and items.", "",
                "&7Playing on: &a" + current,
                "&7Profiles: &e" + rows.size() + "&7/&e" + max)));
        int start = max <= 5 ? 11 : 10;
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (int i = 0; i < max; i++) {
            int slot = start + i;
            if (i < rows.size()) {
                ProfileRow r = rows.get(i);
                List<String> lore = new ArrayList<>();
                lore.add("&8" + modeLabel(r));
                lore.add("");
                lore.add("&7Purse: &6" + Coins.formatTenths(r.purse()) + " coins");
                lore.add("&7Members: &a" + r.members());
                lore.add("&7Created: &a" + df.format(new Date(r.created())));
                lore.add("");
                if (r.active()) {
                    lore.add("&aYou are playing on this profile!");
                    m.set(slot, Icons.glow(Icons.of(Material.EMERALD_BLOCK, "&aProfile: &e" + r.name(), lore)));
                } else {
                    lore.add("&eClick to switch!");
                    m.set(slot, Icons.of(Material.GRASS_BLOCK, "&aProfile: &e" + r.name(), lore), c -> askSwitch(c.player(), r));
                }
            } else {
                m.set(slot, Icons.of(Material.OAK_SAPLING, "&eEmpty Profile Slot",
                        "&7Start a brand new SkyBlock", "&7adventure on a fresh island.", "",
                        "&7Your other profiles are kept", "&7exactly as they are.", "",
                        "&eClick to create!"), c -> askCreate(c.player()));
            }
        }
        m.set(22, coopIcon(p), c -> {
            if (c.right()) { askLeave(c.player()); return; }
            c.player().closeInventory();
            showCoop(c.player());
        });
        if (Sky.has(Features.SkyMenu.class)) {
            m.set(m.closeSlot() - 1, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        }
        return m;
    }

    private ItemStack coopIcon(Player p) {
        Session s = profiles.session(p);
        List<String> lore = new ArrayList<>();
        lore.add("&7Play one profile together:");
        lore.add("&7a shared island, bank and");
        lore.add("&7collections, with your own");
        lore.add("&7skills, purse and inventory.");
        lore.add("");
        if (s != null && s.profile.members.size() > 1) {
            lore.add("&7Members:");
            for (UUID u : s.profile.members) lore.add(" " + (Bukkit.getPlayer(u) != null ? "&a" : "&7") + nameOf(u));
            lore.add("");
            lore.add("&eClick to list members!");
            lore.add("&cRight-click to leave this co-op!");
        } else {
            lore.add("&7Invite: &e/coop invite <player>");
            lore.add("&7Max members: &a" + profiles.maxCoopMembers.get());
            lore.add("");
            lore.add("&eClick for co-op help!");
        }
        return Icons.of(Material.WRITABLE_BOOK, "&aCo-op", lore);
    }

    static String nameOf(UUID u) {
        Player online = Bukkit.getPlayer(u);
        if (online != null) return online.getName();
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        String n = op.getName();
        return n == null ? u.toString().substring(0, 8) : n;
    }

    private void askSwitch(Player p, ProfileRow r) {
        if (r.active()) { Msg.error(p, "You are already playing on " + r.name() + "."); return; }
        Runnable yes = () -> {
            if (!p.isOnline()) return;
            Msg.success(p, "Switching to " + r.name() + "...");
            profiles.switchProfile(p, r.id(), r.name());
        };
        if (Sky.has(MenuService.class)) {
            Sky.menus().confirm(p, "Switch Profile?", List.of(
                    "&7Switch to &a" + r.name() + "&7?", "",
                    "&7You will be disconnected so the", "&7profile can load safely. Just",
                    "&7rejoin straight away."), yes, () -> openMenu(p));
        } else {
            yes.run();
        }
    }

    private void askCreate(Player p) {
        Long last = createCooldown.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < 5000) { Msg.error(p, "Please wait a moment."); return; }
        Runnable yes = () -> {
            if (!p.isOnline()) return;
            createCooldown.put(p.getUniqueId(), System.currentTimeMillis());
            profiles.createExtraProfile(p.getUniqueId(), p.getName()).whenComplete((idName, ex) ->
                    Bukkit.getScheduler().runTask(profiles.plugin, () -> {
                        if (!p.isOnline()) return;
                        if (ex != null) {
                            Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                            Msg.error(p, c instanceof IllegalStateException ? c.getMessage() : "Could not create a profile right now.");
                            if (!(c instanceof IllegalStateException)) profiles.log.log(Level.WARNING, "[profiles] create failed", ex);
                            return;
                        }
                        Msg.success(p, "Created profile " + idName[1] + "!");
                        profiles.switchProfile(p, idName[0], idName[1]);
                    }));
        };
        if (Sky.has(MenuService.class)) {
            Sky.menus().confirm(p, "Create Profile?", List.of(
                    "&7Create a brand new profile and", "&7switch to it?", "",
                    "&7Your current profile is saved", "&7and stays exactly as it is."), yes, () -> openMenu(p));
        } else {
            yes.run();
        }
    }

    // ---- /coop -----------------------------------------------------------------------------------

    private void coopCmd(CommandSender sender, String[] args) {
        Player p = (Player) sender;
        Session s = profiles.session(p);
        if (s == null) { Msg.error(p, "Your profile is still loading."); return; }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "invite", "add" -> {
                if (args.length < 2) { Msg.error(p, "Usage: /coop invite <player>"); return; }
                invite(p, s, args[1]);
            }
            case "accept", "join" -> accept(p);
            case "deny", "decline" -> {
                Invite inv = invites.remove(p.getUniqueId());
                if (inv == null) { Msg.error(p, "You have no pending co-op invite."); return; }
                Msg.send(p, "&eYou declined &b" + inv.inviterName() + "&e's co-op invite.");
                Player inviter = Bukkit.getPlayer(inv.inviter());
                if (inviter != null) Msg.send(inviter, "&b" + p.getName() + " &edeclined your co-op invite.");
            }
            case "list", "members" -> showCoop(p);
            case "leave" -> askLeave(p);
            case "help" -> coopHelp(p);
            default -> coopHelp(p);
        }
    }

    private void coopHelp(Player p) {
        Msg.send(p, "&aCo-op commands:");
        Msg.send(p, " &e/coop invite <player> &7- invite someone to this profile");
        Msg.send(p, " &e/coop accept &7/ &e/coop deny &7- answer an invite");
        Msg.send(p, " &e/coop list &7- members of this profile");
        Msg.send(p, " &e/coop leave &7- leave this co-op profile");
    }

    private void invite(Player p, Session s, String targetName) {
        Player t = Bukkit.getPlayerExact(targetName);
        if (t == null || !p.canSee(t)) { Msg.error(p, "That player is not online."); return; }
        if (t.equals(p)) { Msg.error(p, "You can't invite yourself."); return; }
        Session ts = profiles.session(t);
        if (ts == null) { Msg.error(p, t.getName() + " is still loading. Try again in a moment."); return; }
        if (s.profile.members.contains(t.getUniqueId())) { Msg.error(p, t.getName() + " is already on this profile."); return; }
        if (s.profile.members.size() >= profiles.maxCoopMembers.get()) {
            Msg.error(p, "This co-op is full (" + profiles.maxCoopMembers.get() + " members).");
            return;
        }
        if (!"normal".equals(s.profile.mode) || !"normal".equals(ts.profile.mode)) {
            // TUNE: special modes (ironman etc.) get their own co-op rules when those modes exist.
            Msg.error(p, "Co-op invites only work between Classic profiles for now.");
            return;
        }
        Invite prev = invites.get(t.getUniqueId());
        if (prev != null && prev.inviter().equals(p.getUniqueId()) && prev.expires() > System.currentTimeMillis()) {
            Msg.error(p, "You already invited " + t.getName() + ".");
            return;
        }
        long expires = System.currentTimeMillis() + Math.max(10, inviteSeconds.get()) * 1000L;
        invites.put(t.getUniqueId(), new Invite(p.getUniqueId(), p.getName(), s.profile.id, s.profile.name, expires));
        Msg.success(p, "Invited " + t.getName() + " to your co-op! The invite lasts " + inviteSeconds.get() + "s.");
        Msg.send(t, Msg.BAR);
        Msg.send(t, "&b" + p.getName() + " &einvited you to their co-op profile &a" + s.profile.name + "&e!");
        Msg.send(t, "&7You would share their island, bank and collections.");
        Msg.send(t, "&eType &a/coop accept &eto join, or &c/coop deny&e.");
        Msg.send(t, Msg.BAR);
    }

    private void accept(Player p) {
        Invite inv = invites.get(p.getUniqueId());
        if (inv == null) { Msg.error(p, "You have no pending co-op invite."); return; }
        if (inv.expires() < System.currentTimeMillis()) {
            invites.remove(p.getUniqueId());
            Msg.error(p, "That co-op invite has expired.");
            return;
        }
        Runnable yes = () -> {
            if (!p.isOnline()) return;
            if (invites.remove(p.getUniqueId(), inv)) {
                profiles.addMember(inv.profileId(), p.getUniqueId()).whenComplete((ok, ex) ->
                        Bukkit.getScheduler().runTask(profiles.plugin, () -> {
                            if (!p.isOnline()) return;
                            if (ex != null || !Boolean.TRUE.equals(ok)) {
                                if (ex != null) profiles.log.log(Level.WARNING, "[profiles] co-op join failed", ex);
                                Msg.error(p, "Could not join: the co-op is full, or you already have "
                                        + profiles.maxProfiles.get() + " profiles.");
                                return;
                            }
                            Player inviter = Bukkit.getPlayer(inv.inviter());
                            if (inviter != null) Msg.success(inviter, p.getName() + " joined your co-op!");
                            p.kick(Text.c("&aJoining co-op profile &e" + inv.profileName() + "&a...\n\n&7Rejoin to play on it."));
                        }));
            } else {
                Msg.error(p, "That invite is no longer valid.");
            }
        };
        if (Sky.has(MenuService.class)) {
            Sky.menus().confirm(p, "Join Co-op?", List.of(
                    "&7Join &b" + inv.inviterName() + "&7's profile &a" + inv.profileName() + "&7?", "",
                    "&7This becomes a NEW profile for you.", "&7Your current profile is kept.", "",
                    "&7You will be disconnected; rejoin", "&7to start playing together."), yes, () -> { });
        } else {
            yes.run();
        }
    }

    private void showCoop(Player p) {
        Session s = profiles.session(p);
        if (s == null) return;
        List<UUID> members = new ArrayList<>(s.profile.members);
        if (members.size() <= 1) {
            Msg.send(p, "&eYou are playing solo on &a" + s.profile.name + "&e.");
            coopHelp(p);
            return;
        }
        Msg.send(p, "&aCo-op members of &e" + s.profile.name + " &7(" + members.size() + "/" + profiles.maxCoopMembers.get() + "):");
        for (UUID u : members) {
            boolean online = Bukkit.getPlayer(u) != null;
            Msg.send(p, (online ? " &a● " : " &7○ ") + "&f" + nameOf(u) + (u.equals(p.getUniqueId()) ? " &7(you)" : ""));
        }
    }

    private void askLeave(Player p) {
        Session s = profiles.session(p);
        if (s == null) return;
        if (s.profile.members.size() < 2) { Msg.error(p, "You are not in a co-op."); return; }
        Runnable yes = () -> { if (p.isOnline()) profiles.leaveCoop(p); };
        if (Sky.has(MenuService.class)) {
            Sky.menus().confirm(p, "Leave Co-op?", List.of(
                    "&7Leave the co-op profile &a" + s.profile.name + "&7?", "",
                    "&cYou lose access to this profile.", "&7Its island and bank stay with", "&7the other members."),
                    yes, () -> openMenu(p));
        } else {
            yes.run();
        }
    }

    /** Self-test helper: the menu builds for this player without throwing. */
    String menuBuilds(Player p) {
        try {
            buildMenu(p, List.of(new ProfileRow("x", "Apple", "normal", System.currentTimeMillis(), 1, 0, true)));
            return null;
        } catch (Throwable t) {
            return "profile menu threw " + t;
        }
    }

    static List<String> fruits() { return Arrays.asList(ProfileModel.FRUITS.toArray(new String[0])); }
}
