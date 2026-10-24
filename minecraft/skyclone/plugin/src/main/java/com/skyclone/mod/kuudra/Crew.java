package com.skyclone.mod.kuudra;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hunting crews (research 04 sec 6.5: up to 4 for Kuudra, invites expire after 60 s). Session-only and kept
 * here because the API has no shared party service yet (handoff Request 1); a crew of one is always valid,
 * so every hunt is soloable.
 */
final class Crew {

    private static final long INVITE_MS = 60_000;

    /** leader -> members (leader first). */
    private final Map<UUID, LinkedHashSet<UUID>> crews = new HashMap<>();
    /** member -> leader. */
    private final Map<UUID, UUID> leaderOf = new HashMap<>();
    /** invitee -> (leader -> expiry). */
    private final Map<UUID, Map<UUID, Long>> invites = new HashMap<>();

    UUID leader(UUID p) {
        UUID l = leaderOf.get(p);
        if (l != null) return l;
        // integrator (request 1): with no crew, the shared /party (Features.Party, dungeons) counts as the crew
        Player me = Bukkit.getPlayer(p);
        if (me == null) return p;
        Player pl = Sky.service(Features.Party.class).leader(me);
        return pl == null ? p : pl.getUniqueId();
    }

    boolean isLeader(UUID p) { return leader(p).equals(p); }

    /** The player's crew, leader first; else their /party (capped at the crew size); just them when they have neither. */
    List<UUID> members(UUID p) {
        Set<UUID> s = crews.get(leaderOf.getOrDefault(p, p));
        if (s != null) return new ArrayList<>(s);
        Player me = Bukkit.getPlayer(p);
        if (me == null) return List.of(p);
        List<Player> party = Sky.service(Features.Party.class).members(me);
        if (party == null || party.size() <= 1) return List.of(p);
        List<UUID> out = new ArrayList<>();
        UUID lead = leader(p);
        out.add(lead);
        for (Player x : party) if (!x.getUniqueId().equals(lead) && out.size() < KDials.MAX_PARTY.get()) out.add(x.getUniqueId());
        if (!out.contains(p)) { out.remove(out.size() - 1); out.add(p); }
        return out;
    }

    List<Player> online(UUID p) {
        List<Player> out = new ArrayList<>();
        for (UUID u : members(p)) { Player x = Bukkit.getPlayer(u); if (x != null) out.add(x); }
        return out;
    }

    void invite(Player leader, Player target) {
        UUID l = leader(leader.getUniqueId());
        if (!l.equals(leader.getUniqueId())) { Msg.error(leader, "Only your crew's leader can invite."); return; }
        if (target.equals(leader)) { Msg.error(leader, "You cannot invite yourself."); return; }
        if (leaderOf.containsKey(target.getUniqueId())) { Msg.error(leader, target.getName() + " is already in a crew."); return; }
        if (members(l).size() >= KDials.MAX_PARTY.get()) { Msg.error(leader, "Your crew is full (" + KDials.MAX_PARTY.get() + ")."); return; }
        invites.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>()).put(l, System.currentTimeMillis() + INVITE_MS);
        Msg.success(leader, "Invited " + target.getName() + " to your hunting crew. They have 60 seconds.");
        Msg.send(target, Msg.BAR);
        Msg.send(target, "&e" + leader.getName() + " &7invites you to their &cLeviathan &7hunting crew!");
        target.sendMessage(Text.c(" &a&l[ACCEPT]").clickEvent(ClickEvent.runCommand("/kuudra crew accept " + leader.getName()))
                .hoverEvent(HoverEvent.showText(Text.c("&eClick to join " + leader.getName() + "'s crew"))));
        Msg.send(target, Msg.BAR);
    }

    void accept(Player p, String leaderName) {
        Player lp = leaderName == null ? null : Bukkit.getPlayerExact(leaderName);
        Map<UUID, Long> mine = invites.get(p.getUniqueId());
        if (lp == null || mine == null) { Msg.error(p, "You have no crew invite from that player."); return; }
        Long exp = mine.remove(lp.getUniqueId());
        if (exp == null || exp < System.currentTimeMillis()) { Msg.error(p, "That invite has expired."); return; }
        if (leaderOf.containsKey(p.getUniqueId())) { Msg.error(p, "Leave your current crew first."); return; }
        UUID l = leader(lp.getUniqueId());
        LinkedHashSet<UUID> s = crews.computeIfAbsent(l, k -> { LinkedHashSet<UUID> n = new LinkedHashSet<>(); n.add(l); return n; });
        if (s.size() >= KDials.MAX_PARTY.get()) { Msg.error(p, "That crew is full."); return; }
        s.add(p.getUniqueId());
        leaderOf.put(l, l);
        leaderOf.put(p.getUniqueId(), l);
        for (Player x : online(l)) Msg.send(x, "&a" + p.getName() + " &7joined the hunting crew. &8(" + s.size() + "/" + KDials.MAX_PARTY.get() + ")");
    }

    void leave(Player p) {
        UUID id = p.getUniqueId();
        UUID l = leaderOf.get(id);
        if (l == null) { Msg.error(p, "You are not in a crew."); return; }
        if (l.equals(id)) { disband(p); return; }
        LinkedHashSet<UUID> s = crews.get(l);
        if (s != null) s.remove(id);
        leaderOf.remove(id);
        Msg.send(p, "&7You left the crew.");
        for (Player x : online(l)) Msg.send(x, "&e" + p.getName() + " &7left the crew.");
        tidy(l);
    }

    void kick(Player leader, String name) {
        UUID l = leader.getUniqueId();
        if (!isLeader(l) || !crews.containsKey(l)) { Msg.error(leader, "You do not lead a crew."); return; }
        UUID victim = null;
        for (UUID u : crews.get(l)) { var op = Bukkit.getOfflinePlayer(u); if (op.getName() != null && op.getName().equalsIgnoreCase(name)) victim = u; }
        if (victim == null || victim.equals(l)) { Msg.error(leader, "No such crew member."); return; }
        crews.get(l).remove(victim);
        leaderOf.remove(victim);
        Player v = Bukkit.getPlayer(victim);
        if (v != null) Msg.send(v, "&cYou were removed from the crew.");
        Msg.send(leader, "&7Removed &e" + name + "&7.");
        tidy(l);
    }

    void disband(Player leader) {
        UUID l = leader.getUniqueId();
        LinkedHashSet<UUID> s = crews.remove(l);
        if (s == null) { Msg.error(leader, "You do not lead a crew."); return; }
        for (UUID u : s) {
            leaderOf.remove(u);
            Player x = Bukkit.getPlayer(u);
            if (x != null) Msg.send(x, "&7The hunting crew was disbanded.");
        }
    }

    /** Quit: leave quietly (a leader's crew passes to the next member). */
    void quit(UUID id) {
        invites.remove(id);
        UUID l = leaderOf.remove(id);
        if (l == null) return;
        LinkedHashSet<UUID> s = crews.get(l);
        if (s == null) return;
        s.remove(id);
        if (l.equals(id)) {
            crews.remove(l);
            if (s.size() >= 2) {
                UUID nl = s.iterator().next();
                crews.put(nl, s);
                for (UUID u : s) leaderOf.put(u, nl);
                Player np = Bukkit.getPlayer(nl);
                if (np != null) Msg.send(np, "&eYou now lead the hunting crew.");
            } else for (UUID u : s) leaderOf.remove(u);
        } else tidy(l);
    }

    private void tidy(UUID l) {
        LinkedHashSet<UUID> s = crews.get(l);
        if (s != null && s.size() <= 1) { crews.remove(l); for (UUID u : s) leaderOf.remove(u); }
    }

    List<String> pendingFrom(UUID invitee) {
        List<String> out = new ArrayList<>();
        Map<UUID, Long> m = invites.get(invitee);
        if (m == null) return out;
        long now = System.currentTimeMillis();
        for (var e : m.entrySet()) if (e.getValue() > now) { var op = Bukkit.getOfflinePlayer(e.getKey()); if (op.getName() != null) out.add(op.getName()); }
        return out;
    }
}
