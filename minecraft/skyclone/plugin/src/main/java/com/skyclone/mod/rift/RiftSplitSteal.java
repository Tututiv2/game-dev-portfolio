package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Split or Steal (wave 3; the reference's Ubik's Cube minigame, renamed in docs/names/rift.md). Ubaldo Twofold
 * sits in the Wildwood stone circle with a pot of Motes. You pick SPLIT or STEAL without knowing what the
 * other side picks; you are paired with the next player who chooses (anywhere in the Rift), or, after
 * {@code rift.split-steal-wait} seconds alone, with the Cube's Echo (splits {@code rift.split-steal-echo-split}
 * of the time). Payouts are {@link RiftMath#splitSteal}: both split = half each, one steals = all of it,
 * both steal = nothing. One round per {@code rift.split-steal-hours}; the round is only charged to your
 * cooldown once it resolves, so leaving while you wait costs nothing. Every result is announced to the
 * whole Rift, because watching two people decide whether to trust each other is the point.
 */
final class RiftSplitSteal {

    static final String HOST = "rift_split_steal";
    static final String HOST_NAME = "Ubaldo Twofold";
    static final String ECHO = "the Cube's Echo";

    private record Choice(UUID id, String name, boolean steal, long at) {}

    private final RiftModule m;
    private final Map<UUID, Choice> waiting = new LinkedHashMap<>();
    private final Random rnd = new Random();

    RiftSplitSteal(RiftModule m) { this.m = m; }

    // =========================================================================================
    // the host
    // =========================================================================================

    void click(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        if (d.flags.add("met_pip")) {
            Sky.npcs().talk(p, HOST, List.of(
                    "Step into the circle, step into the circle! One pot, two players, one choice each.",
                    "Both SPLIT and you halve it. One STEALS and the thief takes the lot. Both steal? Nobody gets a thing.",
                    "You won't know what the other picked until it's too late. That's what makes it fun!"),
                    () -> menu(p));
            m.award(p, "quest:split_steal", 3, "Met " + HOST_NAME);
            return;
        }
        menu(p);
    }

    void menu(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        long pot = m.dials.splitStealPot.get();
        long now = System.currentTimeMillis();
        long cd = RiftMath.splitStealCooldown(d.splitStealAt, now, cooldownMs());
        Choice mine = waiting.get(p.getUniqueId());
        Menu menu = new Menu("Split or Steal", 4).chrome();
        List<String> info = new ArrayList<>(List.of(
                "&7Pot: &d" + Text.commasInt(pot) + " Motes", "",
                "&aSPLIT &7+ &aSPLIT&7: &d" + Text.commasInt(pot / 2) + " &7each",
                "&cSTEAL &7+ &aSPLIT&7: the thief takes &d" + Text.commasInt(pot),
                "&cSTEAL &7+ &cSTEAL&7: nobody gets anything", "",
                "&7You are paired with the next player", "&7who chooses. Nobody else? After &e" + m.dials.splitStealWait.get() + "s",
                "&7the Cube's Echo plays against you.", "",
                "&7One round every &e" + Text.commas(m.dials.splitStealHours.get()) + " hours&7.", "",
                "&7Rounds played: &f" + d.splitStealRounds + " &8(" + d.splitStealSplits + " split)",
                "&7Motes won: &d" + Text.commasInt(d.splitStealMotes)));
        if (d.splitStealLast != null) { info.add(""); info.add("&7Last round: " + d.splitStealLast); }
        menu.set(4, Icons.of(Material.PLAYER_HEAD, "&e" + HOST_NAME + "'s Split or Steal", info));
        if (mine != null) {
            long left = Math.max(0, m.dials.splitStealWait.get() - (now - mine.at()) / 1000);
            menu.set(22, Icons.of(Material.CLOCK, "&eWaiting for an opponent...", "&7You chose " + (mine.steal() ? "&cSTEAL" : "&aSPLIT") + "&7.",
                    "&7The Echo answers in &e" + left + "s &7if", "&7nobody else plays."));
        } else if (cd > 0) {
            menu.set(22, Icons.of(Material.CLOCK, "&cCome back later", "&7Your next round: &e" + Text.duration(cd / 1000)));
        } else {
            menu.set(20, Icons.of(Material.LIME_GLAZED_TERRACOTTA, "&a&lSPLIT", "&7Share the pot with your opponent.", "",
                    "&7If they split too, you both get", "&d" + Text.commasInt(pot / 2) + " Motes&7. If they steal,", "&7you get nothing.", "",
                    "&eClick to SPLIT!"), c -> choose(c.player(), false));
            menu.set(24, Icons.of(Material.RED_GLAZED_TERRACOTTA, "&c&lSTEAL", "&7Try to take the whole pot.", "",
                    "&7If they split, you get all", "&d" + Text.commasInt(pot) + " Motes&7. If they steal", "&7too, nobody gets anything.", "",
                    "&eClick to STEAL!"), c -> choose(c.player(), true));
        }
        menu.set(31, Icons.close(), c -> c.player().closeInventory());
        menu.open(p);
    }

    private long cooldownMs() { return (long) (m.dials.splitStealHours.get() * 3_600_000L); }

    void choose(Player p, boolean steal) {
        RiftData d = m.data(p);
        if (d == null) return;
        p.closeInventory();
        if (m.session(p) == null) { Msg.error(p, "You can only play inside the Rift."); return; }
        if (waiting.containsKey(p.getUniqueId())) { Msg.error(p, "You are already waiting for an opponent."); return; }
        long cd = RiftMath.splitStealCooldown(d.splitStealAt, System.currentTimeMillis(), cooldownMs());
        if (cd > 0) { Msg.error(p, "Your next round is in " + Text.duration(cd / 1000) + "."); return; }
        Choice me = new Choice(p.getUniqueId(), p.getName(), steal, System.currentTimeMillis());
        // pair with the oldest other player still waiting
        Choice other = null;
        for (Choice c : waiting.values()) {
            if (c.id().equals(p.getUniqueId())) continue;
            Player op = Bukkit.getPlayer(c.id());
            if (op != null && op.isOnline()) { other = c; break; }
        }
        Msg.send(p, "&eYou chose " + (steal ? "&c&lSTEAL" : "&a&lSPLIT") + "&e. No take-backs!");
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_BELL, steal ? 0.7f : 1.4f);
        if (other == null) {
            waiting.put(p.getUniqueId(), me);
            Msg.send(p, "&7Waiting for another player to choose... &8(the Echo answers in " + m.dials.splitStealWait.get() + "s)");
            announce("&e" + HOST_NAME + "&7: &f" + p.getName() + " &7has made a choice. Who will face them? &8(talk to me in the stone circle)");
            return;
        }
        waiting.remove(other.id());
        resolve(me, other);
    }

    /** Every second: waiting players alone too long face the Echo; players who left are dropped (no cooldown spent). */
    void tick() {
        if (waiting.isEmpty()) return;
        long now = System.currentTimeMillis();
        long wait = m.dials.splitStealWait.get() * 1000L;
        Iterator<Choice> it = waiting.values().iterator();
        List<Choice> due = new ArrayList<>();
        while (it.hasNext()) {
            Choice c = it.next();
            Player p = Bukkit.getPlayer(c.id());
            if (p == null || !p.isOnline() || m.data(p) == null) { it.remove(); continue; }
            if (now - c.at() >= wait) { it.remove(); due.add(c); }
        }
        for (Choice c : due) {
            boolean echoSteals = rnd.nextDouble() >= m.dials.splitStealEchoSplit.get();
            resolve(c, new Choice(null, ECHO, echoSteals, now));
        }
    }

    private void resolve(Choice a, Choice b) {
        long[] pay = RiftMath.splitSteal(a.steal(), b.steal(), m.dials.splitStealPot.get());
        settle(a, b, pay[0]);
        settle(b, a, pay[1]);
        String verdict;
        if (!a.steal() && !b.steal()) verdict = "&aboth SPLIT &7and share &d" + Text.commasInt(pay[0] + pay[1]) + " Motes&7!";
        else if (a.steal() && b.steal()) verdict = "&cboth STOLE &7and walk away with nothing!";
        else {
            Choice thief = a.steal() ? a : b;
            verdict = "&7- &c" + thief.name() + " STOLE &7all &d" + Text.commasInt(Math.max(pay[0], pay[1])) + " Motes&7!";
        }
        announce("&e" + HOST_NAME + "&7: &f" + a.name() + " &7and &f" + b.name() + " " + verdict);
    }

    /** Pays and tells one side (the Echo has no side). */
    private void settle(Choice me, Choice them, long won) {
        if (me.id() == null) return;
        Player p = Bukkit.getPlayer(me.id());
        RiftData d = p == null ? null : m.data(p);
        if (d == null) return;
        d.splitStealAt = System.currentTimeMillis();
        d.splitStealRounds++;
        if (!me.steal()) d.splitStealSplits++;
        d.splitStealMotes += won;
        d.splitStealLast = (me.steal() ? "&cyou stole" : "&ayou split") + "&7, " + them.name() + (them.steal() ? " &cstole" : " &asplit")
                + "&7: &d+" + Text.commasInt(won);
        if (won > 0) m.addMotes(p, won, "Split or Steal");
        p.showTitle(Title.title(Text.c(them.steal() ? "&c&lSTEAL" : "&a&lSPLIT"),
                Text.c("&f" + them.name() + " &7chose " + (them.steal() ? "&cto steal" : "&ato split") + "&7 - you win &d" + Text.commasInt(won)),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(3000), Duration.ofMillis(600))));
        Msg.sound(p, won > 0 ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_VILLAGER_NO, 1f);
        m.riddles.splitStealPlayed(p);            // wave 5: Ubaldo's Tit for Tat soul
        Sky.profiles().saveNow(p);
    }

    private void announce(String line) {
        World w = Sky.worlds().world(RiftCatalog.ISLAND);
        if (w == null) return;
        for (Player q : w.getPlayers()) q.sendMessage(Text.c(line));
    }

    /** Admin: forget a player's cooldown. */
    void reset(Player p) {
        RiftData d = m.data(p);
        if (d != null) d.splitStealAt = 0;
        waiting.remove(p.getUniqueId());
    }

    int waitingCount() { return waiting.size(); }
}
