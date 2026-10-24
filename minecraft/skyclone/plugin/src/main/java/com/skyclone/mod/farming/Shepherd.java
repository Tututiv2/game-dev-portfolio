package com.skyclone.mod.farming;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shepherd Maela's shearing task at the Shepherd's Keep (research 03 6.x: "Shepherd - shear N sheep at once task").
 * Talk to her, then shear {@code farming.shepherd.sheep} sheep within {@code farming.shepherd.seconds}: she pays a
 * FIXED purse and Farming XP on a cooldown (no gambling). Separately, shearing a sheep on any farming island pays a
 * little Farming XP ("Harvest crops and shear sheep to earn Farming EXP!"), and the sheep grows its wool back after
 * {@code farming.shepherd.wool-regrow-seconds} (the desert Keep has no grass for them to eat). Maela also sells Shears,
 * so the task never dead-ends for a player who has none.
 */
final class Shepherd implements Listener {

    static final String ISLAND = "mushroom_desert";

    private final FarmingModule m;
    /** Running tasks: player -> {deadline millis, sheep sheared}. Not saved: a task lasts half a minute. */
    private final Map<UUID, long[]> tasks = new HashMap<>();

    Shepherd(FarmingModule m) { this.m = m; }

    void start() { Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 40L, 10L); }

    // ---- NPC + menu ---------------------------------------------------------------------------------

    void talk(Player p) {
        FarmData d = m.data(p);
        if (d.shearingTasksDone == 0 && !tasks.containsKey(p.getUniqueId())) {
            Sky.npcs().talk(p, "farming_shepherd", List.of(
                    "&fMy flock grazes the Keep, and their fleeces grow faster than I can clip them.",
                    "&fShear &e" + need() + " &fof them in &e" + seconds() + " seconds &fand I'll pay you for the trouble.",
                    "&fNo shears? I keep a spare pair or two."), () -> open(p));
        } else open(p);
    }

    void open(Player p) {
        FarmData d = m.data(p);
        long now = System.currentTimeMillis();
        Menu menu = new Menu("Shepherd Maela", 4).chrome();
        long[] t = tasks.get(p.getUniqueId());
        int reward = Math.max(0, m.dials.shepherdReward.get()), xp = Math.max(0, m.dials.shepherdXp.get());
        if (t != null) {
            menu.set(13, Icons.of(Material.SHEARS, "&aShearing Task &e(running)", List.of(
                    "&7Sheared: &e" + t[1] + "&7/&e" + need(),
                    "&7Time left: &e" + Text.duration(Math.max(0, t[0] - now) / 1000 + 1), "",
                    "&7Pays: &6" + Text.commasInt(reward) + " coins &7+ &3" + xp + " Farming XP")));
        } else {
            long left = Math.max(0, d.shepherdCooldownUntil - now);
            List<String> l = new ArrayList<>(List.of(
                    "&7Shear &e" + need() + " sheep &7in the Keep", "&7within &e" + seconds() + " seconds&7.", "",
                    "&7Pays: &6" + Text.commasInt(reward) + " coins &7+ &3" + xp + " Farming XP",
                    "&8Always the same: no luck involved.", ""));
            if (left > 0) l.add("&cThe flock needs a rest: &e" + Text.duration(left / 1000 + 1));
            else l.add("&eClick to start!");
            menu.set(13, Icons.of(Material.WHITE_WOOL, "&aShearing Task", l), c -> begin(c.player()));
        }
        int cost = Math.max(0, m.dials.shearsCost.get());
        if (cost > 0)
            menu.set(11, Icons.of(Material.SHEARS, "&aBuy Shears", "&7Cost: &6" + Text.commasInt(cost) + " coins", "",
                    Sky.economy().purse(p) >= cost ? "&eClick to buy!" : "&cYou can't afford this!"), c -> buyShears(c.player()));
        menu.set(15, Icons.of(Material.BOOK, "&aYour Flock Record",
                "&7Sheep sheared: &e" + Text.commasInt(d.sheepSheared),
                "&7Tasks finished: &e" + d.shearingTasksDone,
                "&7Each shear on a farming island: &3+" + Math.max(0, m.dials.shearXp.get()) + " Farming XP"));
        menu.set(30, Icons.back("Farming"), c -> m.menus.openMain(c.player()));
        menu.open(p);
    }

    void begin(Player p) {
        FarmData d = m.data(p);
        long now = System.currentTimeMillis();
        if (tasks.containsKey(p.getUniqueId())) { Msg.error(p, "You are already shearing for Maela!"); return; }
        if (now < d.shepherdCooldownUntil) { Msg.error(p, "The flock needs a rest."); return; }
        tasks.put(p.getUniqueId(), new long[]{now + seconds() * 1000L, 0});
        p.closeInventory();
        Msg.npc(p, "&fShepherd Maela", List.of("&fGo on then! &e" + need() + " sheep&f, &e" + seconds() + " seconds&f. Starting... now!"), null);
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.4f);
        if (!hasShears(p)) Msg.send(p, "&cYou have no Shears! &7Maela sells a pair.");
    }

    private void buyShears(Player p) {
        int cost = Math.max(0, m.dials.shearsCost.get());
        if (cost <= 0) return;
        if (!Sky.economy().withdraw(p, cost, "farming: shears")) { Msg.error(p, "You can't afford this!"); return; }
        ItemStack s = Sky.items().exists("SHEARS") ? Sky.items().create("SHEARS") : new ItemStack(Material.SHEARS);
        Sky.loot().give(p, s, LootSource.NPC_SHOP);
        Msg.send(p, "&aYou bought &fShears &afor &6" + Text.commasInt(cost) + " coins&a.");
        Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f);
        open(p);
    }

    private static boolean hasShears(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType() == Material.SHEARS) return true;
        return false;
    }

    int need() { return Math.max(1, m.dials.shepherdCount.get()); }
    int seconds() { return Math.max(5, m.dials.shepherdSeconds.get()); }

    // ---- shearing -----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent e) {
        if (!(e.getEntity() instanceof Sheep sheep)) return;
        Player p = e.getPlayer();
        String island = Sky.worlds().islandOf(sheep.getWorld());
        if (island == null || !m.dials.islandSet().contains(island) || !Sky.profiles().isReady(p)) return;
        FarmData d = m.data(p);
        d.sheepSheared++;
        int xp = Math.max(0, m.dials.shearXp.get());
        if (xp > 0 && Sky.has(SkillService.class))
            Sky.service(SkillService.class).addXp(p, SkillType.FARMING, xp * m.dials.xpMultiplier.get(), "farming:shear");
        // the wool comes back on its own: a Keep with no grass would otherwise run out of fleeces for good
        int regrow = Math.max(0, m.dials.woolRegrow.get());
        if (regrow > 0) Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (sheep.isValid()) sheep.setSheared(false);
        }, regrow * 20L);
        long[] t = tasks.get(p.getUniqueId());
        if (t == null || !ISLAND.equals(island)) return;
        t[1]++;
        if (t[1] >= need()) finish(p, d);
        else p.sendActionBar(Text.c("&aSheared &e" + t[1] + "&7/&e" + need() + " &7- &e"
                + Text.duration(Math.max(0, t[0] - System.currentTimeMillis()) / 1000 + 1) + " left"));
    }

    private void finish(Player p, FarmData d) {
        tasks.remove(p.getUniqueId());               // first: a second shear in the same tick can never pay twice
        d.shearingTasksDone++;
        d.shepherdCooldownUntil = System.currentTimeMillis() + Math.max(0, m.dials.shepherdCooldown.get()) * 1000L;
        int reward = Math.max(0, m.dials.shepherdReward.get()), xp = Math.max(0, m.dials.shepherdXp.get());
        if (reward > 0) Sky.economy().deposit(p, reward, "farming: shearing task");
        if (xp > 0 && Sky.has(SkillService.class))
            Sky.service(SkillService.class).addXp(p, SkillType.FARMING, xp, "farming:shepherd");
        Sky.profiles().saveNow(p);
        p.spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0);
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.2f);
        Msg.box(p, "&a&lSHEARING TASK COMPLETE!", List.of("&7Maela's flock is clipped and grateful.",
                "&6+" + Text.commasInt(reward) + " coins   &3+" + xp + " Farming XP",
                "&7Tasks finished: &e" + d.shearingTasksDone));
    }

    private void tick() {
        if (tasks.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, long[]> en : new ArrayList<>(tasks.entrySet())) {
            if (now <= en.getValue()[0]) continue;
            tasks.remove(en.getKey());
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) continue;
            Msg.send(p, "&cOut of time! &7You sheared &e" + en.getValue()[1] + "&7/&e" + need()
                    + "&7. Talk to &fShepherd Maela &7to try again.");
            Msg.sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { tasks.remove(e.getPlayer().getUniqueId()); }

    /** Admin: drop any running task and the cooldown. */
    void reset(Player p) {
        tasks.remove(p.getUniqueId());
        m.data(p).shepherdCooldownUntil = 0;
    }
}
