package com.skyclone.mod.dungeons.party;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import com.skyclone.mod.dungeons.DDials;
import com.skyclone.mod.dungeons.Progress;
import com.skyclone.mod.dungeons.run.RunManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The party system (research 04 sec 6.5): invite / accept / deny / leave / kick / transfer / disband / list / warp /
 * chat, 1-5 players (dial), invites expire after 60 s. Commands: {@code /party} (alias {@code /p}) and {@code /pc}.
 * A solo player is a party of one; nothing requires a party to exist.
 */
public final class Parties implements Listener {

    private static Parties INSTANCE;

    private final Map<UUID, Party> byMember = new HashMap<>();
    private final Map<UUID, Long> lastInvite = new HashMap<>();

    public static Parties get() { return INSTANCE; }

    public static void init() {
        INSTANCE = new Parties();
        Bukkit.getPluginManager().registerEvents(INSTANCE, Sky.plugin());
        Sky.commands().register(SkyCommand.of("party", "Party commands: invite, accept, leave, kick, list, warp, transfer, disband, chat")
                .alias("p").category("Dungeons").playerOnly().usage("[invite|accept|deny|leave|kick|list|warp|transfer|disband|chat|finder] [player]")
                .run((s, a) -> INSTANCE.command((Player) s, a))
                .tab((s, a) -> a.length <= 1 ? List.of("invite", "accept", "deny", "leave", "kick", "list", "warp", "transfer", "disband", "chat", "finder")
                        : a.length == 2 ? onlineNames() : List.of()));
        Sky.commands().register(SkyCommand.of("pc", "Send a message to your party").category("Dungeons").playerOnly().usage("<message>")
                .run((s, a) -> INSTANCE.chat((Player) s, String.join(" ", a))));
    }

    private static List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }

    public Party of(Player p) { return byMember.get(p.getUniqueId()); }

    public Party of(UUID u) { return byMember.get(u); }

    /** The player's party, creating a solo party if they have none. */
    public Party ensure(Player p) {
        Party party = byMember.get(p.getUniqueId());
        if (party == null) {
            party = new Party(p.getUniqueId());
            byMember.put(p.getUniqueId(), party);
        }
        return party;
    }

    /** Members of the player's party (just the player when solo). */
    public List<Player> group(Player p) {
        Party party = of(p);
        return party == null ? List.of(p) : party.online();
    }

    // ---- commands ---------------------------------------------------------------------------

    void command(Player p, String[] a) {
        if (a.length == 0) { list(p); return; }
        String sub = a[0].toLowerCase(java.util.Locale.ROOT);
        String arg = a.length > 1 ? a[1] : null;
        switch (sub) {
            case "invite", "inv" -> { if (arg == null) Msg.error(p, "Usage: /party invite <player>"); else invite(p, arg); }
            case "accept", "join" -> accept(p, arg);
            case "deny" -> deny(p, arg);
            case "leave" -> leave(p, true);
            case "kick", "remove" -> { if (arg == null) Msg.error(p, "Usage: /party kick <player>"); else kick(p, arg); }
            case "list", "info" -> list(p);
            case "warp" -> warp(p);
            case "transfer", "promote" -> { if (arg == null) Msg.error(p, "Usage: /party transfer <player>"); else transfer(p, arg); }
            case "disband" -> disband(p);
            case "chat" -> chat(p, String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
            case "finder", "pf" -> PartyFinder.open(p);
            default -> {
                // "/party <name>" is an invite, like the genre
                if (Bukkit.getPlayerExact(a[0]) != null) invite(p, a[0]);
                else Msg.error(p, "Unknown party command. Try /party list");
            }
        }
    }

    static void bar(Player p, String... lines) {
        Msg.send(p, "&9&m-----------------------------------------------------");
        for (String l : lines) Msg.send(p, l);
        Msg.send(p, "&9&m-----------------------------------------------------");
    }

    void broadcast(Party party, String line) {
        for (Player m : party.online()) bar(m, line);
    }

    public void invite(Player p, String name) {
        long now = System.currentTimeMillis();
        Long last = lastInvite.get(p.getUniqueId());
        if (last != null && now - last < 2000) { Msg.error(p, "Slow down! Wait a moment before inviting again."); return; }
        lastInvite.put(p.getUniqueId(), now);
        Player t = Bukkit.getPlayerExact(name);
        if (t == null || !t.isOnline()) { Msg.error(p, "That player is not online."); return; }
        if (t.equals(p)) { Msg.error(p, "You cannot invite yourself."); return; }
        Party party = ensure(p);
        if (!party.isLeader(p)) { Msg.error(p, "Only the party leader can invite players."); return; }
        if (party.members.contains(t.getUniqueId())) { Msg.error(p, t.getName() + " is already in your party."); return; }
        if (party.size() >= DDials.PARTY_MAX.get()) { Msg.error(p, "Your party is full!"); return; }
        if (RunManager.get().runOf(p) != null) { Msg.error(p, "You cannot invite players during a dungeon run."); return; }
        party.invites.put(t.getUniqueId(), now + DDials.INVITE_SECONDS.get() * 1000L);
        broadcast(party, "&b" + p.getName() + " &einvited &b" + t.getName() + " &eto the party! They have &c" + DDials.INVITE_SECONDS.get() + " &eseconds to accept.");
        Msg.send(t, "&9&m-----------------------------------------------------");
        t.sendMessage(Text.c("&b" + p.getName() + " &ehas invited you to join their party!"));
        t.sendMessage(Text.c("&eYou have &c" + DDials.INVITE_SECONDS.get() + " &eseconds to accept. ")
                .append(Text.c("&6&l[CLICK HERE]").clickEvent(ClickEvent.runCommand("/party accept " + p.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text("Join " + p.getName() + "'s party")))));
        Msg.send(t, "&9&m-----------------------------------------------------");
        Msg.sound(t, Sound.BLOCK_NOTE_BLOCK_PLING, 1.4f);
    }

    public void accept(Player p, String from) {
        if (RunManager.get().runOf(p) != null) { Msg.error(p, "Finish your dungeon run first."); return; }
        long now = System.currentTimeMillis();
        Party target = null;
        for (Party party : new ArrayList<>(byMember.values())) {
            Long exp = party.invites.get(p.getUniqueId());
            if (exp == null || exp < now) continue;
            if (from != null && !from.equalsIgnoreCase(party.leaderName())) continue;
            target = party;
            break;
        }
        if (target == null) { Msg.error(p, "You have no pending party invite" + (from != null ? " from " + from : "") + "."); return; }
        join(p, target);
    }

    /** Put a player into a party (invite accepted or Party Finder join). */
    public boolean join(Player p, Party target) {
        if (target.size() >= DDials.PARTY_MAX.get()) { Msg.error(p, "That party is full!"); return false; }
        if (RunManager.get().runOf(target.leader) != null) { Msg.error(p, "That party is in a dungeon run."); return false; }
        Party old = of(p);
        if (old != null && old != target) leave(p, old.size() > 1);
        target.invites.remove(p.getUniqueId());
        target.members.add(p.getUniqueId());
        byMember.put(p.getUniqueId(), target);
        broadcast(target, "&b" + p.getName() + " &ejoined the party.");
        if (target.listing != null && target.size() >= DDials.PARTY_MAX.get()) PartyFinder.delist(target, "full");
        return true;
    }

    public void deny(Player p, String from) {
        int n = 0;
        for (Party party : byMember.values())
            if ((from == null || from.equalsIgnoreCase(party.leaderName())) && party.invites.remove(p.getUniqueId()) != null) n++;
        Msg.send(p, n > 0 ? "&eInvite declined." : "&cYou have no pending invite.");
    }

    public void leave(Player p, boolean tell) {
        Party party = of(p);
        if (party == null) { if (tell) Msg.error(p, "You are not in a party."); return; }
        if (RunManager.get().runOf(p) != null && tell) { Msg.error(p, "Leave the dungeon first (/dungeon leave)."); return; }
        remove(party, p.getUniqueId());
        if (tell) bar(p, "&eYou left the party.");
        broadcast(party, "&b" + p.getName() + " &ehas left the party.");
    }

    private void remove(Party party, UUID u) {
        party.members.remove(u);
        byMember.remove(u);
        if (party.members.isEmpty()) { PartyFinder.delist(party, "disbanded"); return; }
        if (u.equals(party.leader)) {
            party.leader = party.members.iterator().next();
            PartyFinder.delist(party, "leader left");
            broadcast(party, "&eThe party was transferred to &b" + party.leaderName() + "&e.");
        }
        if (party.members.size() == 1) {
            UUID last = party.members.iterator().next();
            Player lp = Bukkit.getPlayer(last);
            party.members.clear();
            byMember.remove(last);
            PartyFinder.delist(party, "disbanded");
            if (lp != null) bar(lp, "&eThe party was disbanded: you are the only one left.");
        }
    }

    public void kick(Player p, String name) {
        Party party = of(p);
        if (party == null || !party.isLeader(p)) { Msg.error(p, "Only the party leader can kick."); return; }
        if (RunManager.get().runOf(p) != null) { Msg.error(p, "You cannot kick during a run."); return; }
        UUID target = null;
        for (UUID u : party.members) if (!u.equals(party.leader) && name.equalsIgnoreCase(Bukkit.getOfflinePlayer(u).getName())) target = u;
        if (target == null) { Msg.error(p, name + " is not in your party."); return; }
        Player tp = Bukkit.getPlayer(target);
        broadcast(party, "&b" + name + " &ehas been removed from the party.");
        remove(party, target);
        if (tp != null) bar(tp, "&eYou have been kicked from the party by &b" + p.getName());
    }

    public void transfer(Player p, String name) {
        Party party = of(p);
        if (party == null || !party.isLeader(p)) { Msg.error(p, "Only the party leader can transfer the party."); return; }
        for (UUID u : party.members) {
            if (u.equals(party.leader) || !name.equalsIgnoreCase(Bukkit.getOfflinePlayer(u).getName())) continue;
            party.leader = u;
            PartyFinder.delist(party, "leader changed");
            broadcast(party, "&eThe party was transferred to &b" + name + " &eby &b" + p.getName());
            return;
        }
        Msg.error(p, name + " is not in your party.");
    }

    public void disband(Player p) {
        Party party = of(p);
        if (party == null || !party.isLeader(p)) { Msg.error(p, "Only the party leader can disband the party."); return; }
        if (RunManager.get().runOf(p) != null) { Msg.error(p, "You cannot disband during a run."); return; }
        broadcast(party, "&b" + p.getName() + " &ehas disbanded the party!");
        PartyFinder.delist(party, "disbanded");
        for (UUID u : new ArrayList<>(party.members)) byMember.remove(u);
        party.members.clear();
    }

    public void list(Player p) {
        Party party = of(p);
        if (party == null || party.size() <= 1) {
            bar(p, "&eYou are not in a party. &7Invite someone with &b/party invite <player>&7,", "&7or find one at the &eParty Finder &7(&b/pf&7).");
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("&6Party Members (" + party.size() + ")");
        lines.add("");
        for (UUID u : party.members) {
            Player m = Bukkit.getPlayer(u);
            String name = m != null ? m.getName() : Bukkit.getOfflinePlayer(u).getName();
            String cls = m != null && Sky.profiles().isReady(m) ? " &7(" + Progress.selected(m).colored() + " " + Progress.classLevel(m, Progress.selected(m)) + "&7)" : "";
            lines.add((u.equals(party.leader) ? "&eParty Leader: " : "&eParty Member: ") + "&b" + name + (m != null ? " &a●" : " &c●") + cls);
        }
        bar(p, lines.toArray(new String[0]));
    }

    public void warp(Player p) {
        Party party = of(p);
        if (party == null || !party.isLeader(p)) { Msg.error(p, "Only the party leader can warp the party."); return; }
        if (RunManager.get().runOf(p) != null) { Msg.error(p, "You cannot warp the party out of a dungeon."); return; }
        String island = Sky.worlds().islandOf(p.getWorld());
        IslandDef def = island == null ? null : Sky.worlds().island(island);
        if (def == null || def.instanced) { Msg.error(p, "Party warp only works on shared islands (not private islands)."); return; }
        int n = 0;
        for (Player m : party.online()) {
            if (m.equals(p) || RunManager.get().runOf(m) != null) continue;
            m.teleportAsync(p.getLocation());
            Msg.send(m, "&eYou were warped to &b" + p.getName() + "&e.");
            n++;
        }
        Msg.send(p, "&eWarped &a" + n + " &eparty member" + (n == 1 ? "" : "s") + " to you.");
    }

    public void chat(Player p, String msg) {
        Party party = of(p);
        if (party == null || party.size() <= 1) { Msg.error(p, "You are not in a party."); return; }
        String clean = Text.strip(msg).trim();
        if (clean.isEmpty()) return;
        if (clean.length() > 200) clean = clean.substring(0, 200);
        for (Player m : party.online()) m.sendMessage(Text.c("&9Party &8> &b" + p.getName() + "&f: ").append(Component.text(clean)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Party party = of(p);
        if (party == null || RunManager.get().runOf(p) != null) return;     // runs keep their members
        remove(party, p.getUniqueId());
        broadcast(party, "&b" + p.getName() + " &ehas disconnected and left the party.");
    }
}
