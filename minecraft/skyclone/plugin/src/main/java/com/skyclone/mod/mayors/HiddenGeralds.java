package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gerald Sightings (Jerry's "Jerrypocalypse", research/05 §8.5): while the perk is on, chopping,
 * farming, mining and killing can reveal a hidden Gerald (Green 54.55%, Blue 27.27%, Purple 13.64%,
 * Golden 4.55% — neu-repo odds). Each drops a box of its colour holding coins, skill XP and trinkets.
 */
final class HiddenGeralds implements Listener {

    private static final String[] COLORS = {"green", "blue", "purple", "golden"};
    private static final SkillType[] SKILLS = {SkillType.FARMING, SkillType.MINING, SkillType.COMBAT, SkillType.FORAGING, SkillType.FISHING};

    private final MayorsModule mod;

    HiddenGeralds(MayorsModule mod) { this.mod = mod; }

    private boolean active() { return mod.election().perkActive("jerry.jerrypocalypse"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGather(SkyEvents.Gather e) {
        if (e.player != null && e.location != null) maybe(e.player, e.location);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || e.def == null || "hidden_jerry".equals(e.def.familyId())) return;
        maybe(e.killer, e.location);
    }

    private void maybe(Player p, Location at) {
        if (!active() || ThreadLocalRandom.current().nextDouble() >= mod.dials().jerryChance.get()) return;
        spawn(p, at);
    }

    /** Reveal a weighted-random Gerald next to a player. */
    void spawn(Player p, Location at) {
        double total = 0;
        double[] w = new double[COLORS.length];
        for (int i = 0; i < COLORS.length; i++) {
            MobDef d = Sky.mobs().def("hidden_jerry_" + COLORS[i]);
            w[i] = d == null || !d.extra.has("weight") ? 0 : d.extra.get("weight").getAsDouble();
            total += w[i];
        }
        if (total <= 0) return;
        double r = ThreadLocalRandom.current().nextDouble() * total;
        int pick = 0;
        for (int i = 0; i < w.length; i++) { r -= w[i]; if (r < 0) { pick = i; break; } }
        try {
            Sky.mobs().spawn("hidden_jerry_" + COLORS[pick], at.clone().add(0, 0.5, 0), p);
            PlayerData pd = mod.data(p);
            if (pd != null) pd.geraldsFound++;
            Msg.send(p, "&a&lGERALD! &7A hidden &" + new String[]{"a", "9", "5", "6"}[pick] + Text.titleCase(COLORS[pick]) + " Gerald &7popped out!");
            Msg.sound(p, Sound.ENTITY_VILLAGER_YES, 1.2f);
        } catch (Throwable t) {
            Sky.log().warning("[mayors] could not spawn a hidden Gerald: " + t);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onOpen(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        String id = Sky.items().idOf(p.getInventory().getItemInMainHand());
        EventData.Bag bag = id == null ? null : mod.data().jerryBoxes.get(id);
        if (bag == null) return;
        e.setCancelled(true);
        if (Sky.items().remove(p, id, 1) < 1) return;
        int coins = Goods.between(bag.coins);
        if (coins > 0) Sky.economy().deposit(p, coins, "mayors: opened " + id);
        int xp = Goods.between(bag.skillXp);
        SkillType skill = SKILLS[ThreadLocalRandom.current().nextInt(SKILLS.length)];
        if (xp > 0) Sky.service(SkillService.class).addXp(p, skill, xp, "gerald box");
        List<String> got = Goods.roll(bag.drops, 1.0);
        for (String g : got) Goods.give(p, g, 1, LootSource.EVENT);
        StringBuilder sb = new StringBuilder(Goods.displayName(id) + "&7: &6+" + Text.commasInt(coins) + " coins&7, &3+" + Text.commasInt(xp) + " " + skill.display + " XP");
        for (String g : got) sb.append("&7, ").append(Goods.displayName(g));
        Msg.send(p, sb.toString());
        Msg.sound(p, Sound.BLOCK_CHEST_OPEN, 1.3f);
    }
}
