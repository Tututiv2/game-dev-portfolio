package com.skyclone.mod.combatzones;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Wanderer Tavi, The End's lone adventurer (research 03 sec 14.5: sells the Void Sword, gives kill-Endermen tasks).
 * The quest ladder is {@link ZoneQuests}; progress is per profile member ({@code CzData.taviQuest/taviProgress}).
 */
final class Adventurer implements Listener {

    static final String VOID_SWORD = "VOID_SWORD";

    private final CombatzonesModule m;

    Adventurer(CombatzonesModule m) { this.m = m; }

    boolean chainDone(Player p) { return ZoneQuests.allDone(m.data(p).taviQuest); }

    double swordPrice(Player p) {
        return ZoneQuests.voidSwordPrice(m.dials.voidSwordPrice.get(), m.dials.voidSwordDiscount.get(), chainDone(p));
    }

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || e.def == null || !Sky.profiles().isReady(e.killer)) return;
        if (e.def.id.startsWith("end_dragon_")) return;            // every participant is counted by dragonSlain
        CzData d = m.data(e.killer);
        ZoneQuests.Quest q = ZoneQuests.at(d.taviQuest);
        if (q == null) return;
        int before = d.taviProgress;
        d.taviProgress = ZoneQuests.progress(q, before, e.def.id);
        if (d.taviProgress == before) return;
        if (d.taviProgress >= q.count()) complete(e.killer, d, q);
        else if (d.taviProgress % Math.max(1, q.count() / 5) == 0)
            Sky.vitals().flash(e.killer, "&e" + q.title() + ": &a" + d.taviProgress + "&7/&a" + q.count(), 40);
    }

    /** The dragon's death is not a MobKill for most of its fighters (only the last hitter is the killer). */
    void dragonSlain(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        CzData d = m.data(p);
        ZoneQuests.Quest q = ZoneQuests.at(d.taviQuest);
        if (q == null || !q.counts("end_dragon_")) return;
        d.taviProgress = Math.min(q.count(), d.taviProgress + 1);
        if (d.taviProgress >= q.count()) complete(p, d, q);
    }

    private void complete(Player p, CzData d, ZoneQuests.Quest q) {
        double scale = Math.max(0, m.dials.questRewardScale.get());
        double coins = Math.round(q.coins() * scale), xp = q.combatXp() * scale;
        d.taviQuest++;
        d.taviProgress = 0;
        if (coins > 0) Sky.economy().deposit(p, coins, "combatzones: Tavi quest " + q.id());
        if (xp > 0) Sky.service(SkillService.class).addXp(p, SkillType.COMBAT, xp, "quest:tavi_" + q.id());
        if (q.item() != null && q.itemAmount() > 0 && Sky.items().exists(q.item())) Sky.loot().give(p, q.item(), q.itemAmount(), LootSource.REWARD);
        List<String> body = new ArrayList<>();
        body.add("&e" + q.title() + " &7- slay " + q.count() + " " + q.what());
        body.add("");
        body.add("&7Rewards:");
        if (coins > 0) body.add(" &8+&6" + Text.commasInt(coins) + " Coins");
        if (xp > 0) body.add(" &8+&3" + Text.commasInt(xp) + " Combat Experience");
        if (q.item() != null && Sky.items().exists(q.item())) body.add(" &8+&5" + q.itemAmount() + "x " + Text.strip(Sky.items().def(q.item()).name));
        ZoneQuests.Quest next = ZoneQuests.at(d.taviQuest);
        body.add("");
        body.add(next == null ? "&aThat was the last of Wanderer Tavi's tasks!" : "&7Next: &e" + next.title() + " &7(" + next.count() + " " + next.what() + ")");
        Msg.box(p, "&6&lQUEST COMPLETE", body);
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        Sky.profiles().saveNow(p);
    }

    // ============================================================================================ menu

    Menu menu(Player p) {
        Menu menu = new Menu("Wanderer Tavi", 6).chrome();
        CzData d = m.data(p);
        ZoneQuests.Quest cur = ZoneQuests.at(d.taviQuest);
        menu.set(4, Icons.of(Material.LEATHER_CHESTPLATE, "&fWanderer Tavi",
                "&7Came for the dragons, stayed for", "&7the view. Pays well for help.", "",
                "&7Tasks done: &a" + Math.min(d.taviQuest, ZoneQuests.QUESTS.size()) + "&7/&a" + ZoneQuests.QUESTS.size()));
        int slot = 19;
        for (int i = 0; i < ZoneQuests.QUESTS.size(); i++) {
            ZoneQuests.Quest q = ZoneQuests.QUESTS.get(i);
            boolean done = i < d.taviQuest, active = i == d.taviQuest;
            List<String> lore = new ArrayList<>();
            lore.add("&7Slay &a" + q.count() + " &7" + q.what() + ".");
            if (active) lore.add("&7Progress: &a" + d.taviProgress + "&7/&a" + q.count() + " " + bar(d.taviProgress, q.count()));
            lore.add("");
            lore.add("&7Rewards:");
            double scale = Math.max(0, m.dials.questRewardScale.get());
            lore.add(" &8+&6" + Text.commasInt(Math.round(q.coins() * scale)) + " Coins");
            if (q.combatXp() > 0) lore.add(" &8+&3" + Text.commasInt(q.combatXp() * scale) + " Combat Experience");
            if (q.item() != null) lore.add(" &8+&5" + q.itemAmount() + "x " + (Sky.items().exists(q.item()) ? Text.strip(Sky.items().def(q.item()).name) : q.item()));
            lore.add("");
            lore.add(done ? "&a&lCOMPLETE" : active ? "&eIn progress" : "&cLocked &7(finish the one before)");
            menu.set(slot, Icons.of(done ? Material.LIME_DYE : active ? Material.BOOK : Material.GRAY_DYE,
                    (done ? "&a" : active ? "&e" : "&7") + q.title(), lore));
            slot = ZoneMenus.next(slot);
        }
        // the Void Sword
        double price = swordPrice(p);
        List<String> sl = new ArrayList<>();
        sl.add("&7Cost: &6" + Text.commasInt(price) + " coins" + (chainDone(p) ? " &a(-" + DraconicMath.fmt(m.dials.voidSwordDiscount.get()) + "% for a friend)" : ""));
        if (!chainDone(p)) sl.add("&8Finish all my tasks and it is cheaper.");
        sl.add("");
        sl.add("&eClick to buy!");
        ItemStack sword = Sky.items().exists(VOID_SWORD) ? ZoneMenus.iconOf(VOID_SWORD, sl) : Icons.of(Material.DIAMOND_SWORD, "&5Void Sword", sl);
        menu.set(40, sword, c -> buySword(c.player()));
        menu.set(42, Icons.of(Material.DRAGON_HEAD, "&5The Dragon's Nest", "&7Variants, the altar and loot quality.", "", "&eClick to view!"),
                c -> m.menus.dragon(c.player()).open(c.player()));
        menu.set(38, Icons.of(Material.ENDER_EYE, "&5Current task", cur == null ? List.of("&aEverything is done. Thank you!")
                : List.of("&e" + cur.title(), "&7Slay &a" + cur.count() + " &7" + cur.what(), "&7Progress: &a" + d.taviProgress + "&7/&a" + cur.count())));
        menu.back("Combat Zones", () -> m.menus.main(p));
        return menu;
    }

    static String bar(int have, int need) {
        int n = need <= 0 ? 10 : (int) Math.round(10.0 * Math.min(have, need) / need);
        return "&2" + "-".repeat(n) + "&f" + "-".repeat(10 - n);
    }

    void buySword(Player p) {
        if (!Sky.items().exists(VOID_SWORD)) { Msg.error(p, "Tavi has no sword to sell right now."); return; }
        double price = swordPrice(p);
        ItemDef def = Sky.items().def(VOID_SWORD);
        Sky.menus().confirm(p, "Buy Void Sword?", List.of("&7Pay &6" + Text.commasInt(price) + " coins", "&7for a " + def.rarity.code() + Text.strip(def.name) + "&7."), () -> {
            if (!Sky.economy().withdraw(p, price, "combatzones: Tavi sells " + VOID_SWORD)) { Msg.error(p, "You don't have enough coins!"); return; }
            Sky.loot().give(p, VOID_SWORD, 1, LootSource.NPC_SHOP);
            Msg.success(p, "Bought a " + Text.strip(def.name) + "!");
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
            menu(p).open(p);
        }, null);
    }
}
