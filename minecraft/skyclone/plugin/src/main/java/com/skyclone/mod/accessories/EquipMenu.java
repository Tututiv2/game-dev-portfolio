package com.skyclone.mod.accessories;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** "/equipment": the four extra slots (necklace, cloak, belt, gloves) next to the worn armour. */
final class EquipMenu {

    private static final int[] ARMOR_SLOTS = {10, 19, 28, 37};
    private static final int[] EQUIP_SLOTS = {11, 20, 29, 38};
    private static final int[] LABEL_SLOTS = {12, 21, 30, 39};
    private static final String[] LABELS = {"Necklace", "Cloak", "Belt", "Gloves"};
    private static final String[] ARMOR_NAMES = {"Helmet", "Chestplate", "Leggings", "Boots"};
    private static final Stat[] SHOWN = {Stat.HEALTH, Stat.DEFENSE, Stat.STRENGTH, Stat.INTELLIGENCE, Stat.CRIT_CHANCE,
            Stat.CRIT_DAMAGE, Stat.SPEED, Stat.MAGIC_FIND, Stat.FEROCITY, Stat.ATTACK_SPEED};

    private final Bags bags;
    private Ui ui;

    EquipMenu(Bags bags) { this.bags = bags; }

    void wire(Ui ui) { this.ui = ui; }

    void open(Player p) {
        if (!Bags.ready(p)) { Msg.error(p, "Your profile is still loading, try again in a moment."); return; }
        Menu m = new Menu("Your Equipment and Stats", 6).keepFreeSlotItems();
        m.free(EQUIP_SLOTS);
        m.chrome();
        ItemStack[] armor = p.getInventory().getArmorContents();   // boots, legs, chest, helmet
        for (int i = 0; i < 4; i++) {
            ItemStack a = armor.length > 3 - i ? armor[3 - i] : null;
            if (a == null || a.getType().isAir()) {
                m.set(ARMOR_SLOTS[i], Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Empty " + ARMOR_NAMES[i] + " Slot"));
            } else {
                m.set(ARMOR_SLOTS[i], a.clone());
            }
        }
        for (int i = 0; i < 4; i++) {
            ItemStack e = bags.equipped(p, Bags.EQUIP_SLOTS[i]);
            m.getInventory().setItem(EQUIP_SLOTS[i], e == null ? null : e.clone());
            m.set(LABEL_SLOTS[i], Icons.of(Material.LIME_STAINED_GLASS_PANE, "&a← " + LABELS[i] + " Slot",
                    "&7Place a " + LABELS[i].toLowerCase() + " in the slot to the", "&7left. You can also right-click", "&7one in your hand to equip it."));
        }
        drawLive(m, p);
        String pet = Sky.service(Features.Pets.class).activeType(p);
        m.set(33, Icons.of(Material.BONE, "&aActive Pet", pet == null || pet.isEmpty() ? "&7None" : "&7" + Text.titleCase(pet)
                + " &8(Lvl " + Sky.service(Features.Pets.class).activeLevel(p) + ")", "", "&eClick to open your pets!"),
                c -> Sky.service(Features.Pets.class).openMenu(c.player()));
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        // Stats and Accessory Power were drawn once at open; redraw them when a piece goes in or out
        // (playtest: menu icons stayed stale until reopened). Core recomputes stats on the tick AFTER
        // invalidate, so the stats icon is redrawn again two ticks later with the settled numbers.
        m.onFreeSlotChange(c -> {
            Player pl = c.player();
            if (!sync(pl, m)) return;
            if (BagMenus.isOpen(pl, m)) drawLive(m, pl);
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (BagMenus.isOpen(pl, m)) drawLive(m, pl);
            }, 2L);
        });
        m.onClose(pl -> {
            sync(pl, m);
            try { Sky.profiles().saveNow(pl); } catch (RuntimeException ignored) { }
        });
        m.open(p);
    }

    /** The icons that depend on what is worn: Your Stats (15) and the Accessory Bag's power (24). */
    private void drawLive(Menu m, Player p) {
        List<String> st = new ArrayList<>();
        for (Stat s : SHOWN) st.add("&" + s.color + s.symbol + " " + s.display + "&f: " + Text.commas(Math.round(Sky.stats().get(p, s) * 10) / 10.0)
                + (s.percent ? "%" : ""));
        // Adaptive Belt: say which class bonus is live, since the class is picked in another module's menu
        // and Mending is not in the list above (wave 4)
        String cls = EffectEngine.selectedClass(p);
        List<String> bonus = Fx.classBonusLines(bags.snapshot(p).fx, cls);
        if (bonus != null) {
            st.add("");
            st.add("&7Dungeon class bonus" + (cls == null ? "&7: &cno class selected" : " &8(&b" + Text.titleCase(cls) + "&8)&7:"));
            if (cls != null && bonus.isEmpty()) st.add("  &8none for this class");
            for (String b : bonus) st.add("  " + b);
        }
        st.add("");
        st.add("&8Use the Stats menu for the full list.");
        m.set(15, Icons.of(Material.PLAYER_HEAD, "&aYour Stats", st));
        m.set(24, Icons.of(Material.LEATHER, "&aAccessory Bag", "&7Accessory Power: &6" + bags.magicalPower(p), "", "&eClick to open!"),
                c -> ui.bag.open(c.player(), 0));
    }

    /** Validate and persist the four slots; true when anything worn changed. */
    private boolean sync(Player p, Menu m) {
        if (!Bags.ready(p)) return false;
        boolean changed = false;
        for (int i = 0; i < 4; i++) {
            String slot = Bags.EQUIP_SLOTS[i];
            ItemStack it = m.getInventory().getItem(EQUIP_SLOTS[i]);
            if (it != null && it.getType().isAir()) it = null;
            if (it != null) {
                ItemDef def = Sky.items().def(Sky.items().idOf(it));
                if (!slot.equals(Bags.slotFor(def))) {
                    m.getInventory().setItem(EQUIP_SLOTS[i], null);
                    BagMenus.giveBack(p, it);
                    Msg.error(p, "That does not go in the " + LABELS[i].toLowerCase() + " slot.");
                    it = null;
                } else if (it.getAmount() > 1) {
                    ItemStack rest = it.clone();
                    rest.setAmount(it.getAmount() - 1);
                    it.setAmount(1);
                    m.getInventory().setItem(EQUIP_SLOTS[i], it);
                    BagMenus.giveBack(p, rest);
                }
            }
            ItemStack cur = bags.equipped(p, slot);
            boolean same = (it == null && cur == null) || (it != null && it.equals(cur));
            if (!same) {
                bags.setEquip(p, slot, it);
                changed = true;
            }
        }
        if (changed) Msg.sound(p, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f);
        return changed;
    }

    /** Right-click with an equipment piece in the main hand: swap it with whatever is worn. */
    boolean equipFromHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemDef def = Sky.items().def(Sky.items().idOf(hand));
        String slot = Bags.slotFor(def);
        if (slot == null || !Bags.ready(p)) return false;
        ItemStack piece = hand.clone();
        piece.setAmount(1);
        ItemStack old = bags.equipped(p, slot);
        bags.setEquip(p, slot, piece);
        if (hand.getAmount() > 1) {
            hand.setAmount(hand.getAmount() - 1);
            p.getInventory().setItemInMainHand(hand);
            if (old != null) BagMenus.giveBack(p, old);
        } else {
            p.getInventory().setItemInMainHand(old == null ? null : old.clone());
        }
        Msg.send(p, "&aYou equipped " + def.name + "&a!");
        Msg.sound(p, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f);
        return true;
    }
}
