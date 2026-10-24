package com.skyclone.mod.skills;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Runic Pedestal ({@code /runes}, and from the Runecrafting skill page).
 *
 * <p>Items never leave the player's inventory while they are "on" the pedestal: clicking an item
 * in your inventory only REMEMBERS its slot and a snapshot. The action re-reads the live slot and
 * refuses if it no longer matches — so closing, dropping or moving the item can never dupe it, and
 * nothing has to be handed back on close.
 *
 * <ul>
 *   <li>item + rune → the rune is applied (one rune per item; a new one replaces the old)</li>
 *   <li>rune + the same rune of the same tier → one rune of the next tier, plus Runecrafting XP</li>
 * </ul>
 */
final class RunicPedestal {

    private static final int LEFT = 20, RIGHT = 24, ACTION = 22, INFO = 4, SKILL = 40;

    private final Runes runes;
    private final SkillServiceImpl svc;
    private final SkillMenus menus;

    RunicPedestal(Runes runes, SkillServiceImpl svc, SkillMenus menus) {
        this.runes = runes; this.svc = svc; this.menus = menus;
    }

    /** One pedestal session: two remembered inventory slots. */
    private static final class Pick {
        int slot = -1;
        ItemStack snap;
        boolean set() { return slot >= 0 && snap != null; }
        void clear() { slot = -1; snap = null; }
    }

    void open(Player p) {
        Pick left = new Pick(), right = new Pick();
        Menu m = new Menu("Runic Pedestal", 6).chrome();
        m.drawer(mm -> draw(mm, p, left, right));
        m.onPlayerInventoryClick((c, live) -> {
            if (live == null || live.getType().isAir()) return;
            Runes.Ref r = runes.rune(live);
            if (r != null) {
                if (!right.set()) put(right, c.slot(), live);
                else if (!left.set()) put(left, c.slot(), live);
                else put(right, c.slot(), live);
            } else {
                ItemDef d = def(live);
                if (d == null || !anySlotFits(d)) { Msg.error(c.player(), "Runes can only go on weapons, bows, boots, chestplates and hoes."); return; }
                put(left, c.slot(), live);
            }
            m.redraw();
        });
        m.back("Runecrafting Skill", () -> menus.road(p, SkillType.RUNECRAFTING, 0));
        m.redraw();
        m.open(p);
    }

    private static void put(Pick k, int slot, ItemStack live) { k.slot = slot; k.snap = live.clone(); }

    private ItemDef def(ItemStack it) {
        try {
            String id = Sky.items().idOf(it);
            return id == null ? null : Sky.items().def(id);
        } catch (RuntimeException e) { return null; }
    }

    private boolean anySlotFits(ItemDef d) {
        for (String s : List.of("WEAPON", "BOW", "BOOTS", "CHESTPLATE", "HOE")) if (Runes.fits(s, d.category)) return true;
        return false;
    }

    private void draw(Menu m, Player p, Pick left, Pick right) {
        int rc = runes.rcLevel(p);
        m.set(INFO, Icons.of(Material.END_PORTAL_FRAME, "&5Runic Pedestal", List.of(
                "&7Click items in your inventory to",
                "&7place them on the pedestal.",
                "",
                "&dApply: &7an item on the left, a rune",
                "&7on the right. One rune per item; a",
                "&7new rune replaces the old one.",
                "",
                "&dFuse: &7two identical runes of the same",
                "&7tier become one of the next tier",
                "&7(up to III), for &3Runecrafting XP&7.",
                "",
                "&7Runes are cosmetic: they only add",
                "&7particle effects.")));

        m.set(LEFT, left.set() ? withHint(left.snap, "&eClick to take off!")
                : Icons.of(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&aItem or Rune",
                        "&7Click a weapon, bow, boots,", "&7chestplate or hoe in your", "&7inventory, or a rune to fuse."),
                c -> { left.clear(); m.redraw(); });
        m.set(RIGHT, right.set() ? withHint(right.snap, "&eClick to take off!")
                : Icons.of(Material.PURPLE_STAINED_GLASS_PANE, "&aRune", "&7Click a rune in your inventory."),
                c -> { right.clear(); m.redraw(); });

        Plan plan = plan(p, left, right);
        m.set(ACTION, plan.icon, c -> {
            if (plan.ok) {
                act(c.player(), left, right);
                m.redraw();
            }
        });

        double xp = SkillServiceImpl.rawXp(svc.data(p), SkillType.RUNECRAFTING);
        List<String> sk = new ArrayList<>();
        sk.add("&7Level: &e" + rc + "&7/&e" + svc.table.maxLevel(SkillType.RUNECRAFTING));
        sk.add("&7Total XP: &3" + Text.commas(xp));
        sk.add("");
        sk.add("&7A rune can be applied or fused once");
        sk.add("&7your Runecrafting level reaches the");
        sk.add("&7rune's own level.");
        sk.add("");
        sk.add("&7Earn XP by fusing runes and by slaying");
        sk.add("&5Runic &7mobs: rare, glowing purple, four");
        sk.add("&7times as tough, and they always drop a rune.");
        long rb = svc.data(p).runebookKills;
        if (rb > 0 || svc.hasRunebook(p)) {
            sk.add("");
            sk.add("&5Runebook: &d" + Text.commas(rb) + " &7Runic kills" + (svc.hasRunebook(p)
                    ? " &8(&3+" + TrainerTiers.digits(rb) + " Runecrafting Wisdom&8)" : " &8(not carried)"));
        }
        m.set(SKILL, Icons.of(Material.MAGMA_CREAM, "&aRunecrafting " + Text.roman(Math.max(1, rc)), sk));
    }

    private static ItemStack withHint(ItemStack snap, String hint) {
        ItemStack it = snap.clone();
        var meta = it.getItemMeta();
        if (meta != null) {
            List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Text.c(""));
            lore.add(Text.c(hint));
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private record Plan(boolean ok, boolean fuse, ItemStack icon) { }

    private Plan plan(Player p, Pick left, Pick right) {
        if (!left.set() || !right.set())
            return new Plan(false, false, Icons.of(Material.ANVIL, "&cNothing to do yet",
                    "&7Place an item and a rune, or two", "&7identical runes."));
        Runes.Ref a = runes.rune(left.snap), b = runes.rune(right.snap);
        if (b == null) return refuse("The right slot must hold a rune.");
        if (a != null) {
            if (a.def() != b.def() || a.tier() != b.tier()) return refuse("Only two identical runes of the same tier fuse.");
            if (a.tier() >= Runes.MAX_TIER) return refuse("This rune is already fully fused.");
            if (left.slot == right.slot && left.snap.getAmount() < 2) return refuse("You need two of this rune.");
            if (!runes.canUse(p, b.def())) return refuse("Requires Runecrafting " + b.def().level + ".");
            List<String> lore = new ArrayList<>();
            lore.add("&7Fuse into " + b.def().display(b.tier() + 1));
            lore.add("");
            lore.add("&7Reward: &3+" + Text.commas(runes.fuseXp(b.def(), b.tier() + 1)) + " Runecrafting XP");
            lore.add("");
            lore.add("&eClick to fuse!");
            return new Plan(true, true, Icons.glow(Icons.of(Material.ANVIL, "&aFuse Runes", lore)));
        }
        ItemDef d = def(left.snap);
        if (d == null || !Runes.fits(b.def().slot, d.category))
            return refuse(b.def().display(b.tier()) + " &7goes on " + Runes.slotLabel(b.def().slot).toLowerCase() + " only.");
        if (!runes.canUse(p, b.def())) return refuse("Requires Runecrafting " + b.def().level + ".");
        List<String> lore = new ArrayList<>();
        lore.add("&7Apply " + b.def().display(b.tier()));
        Runes.Ref old = runes.applied(left.snap);
        if (old != null) {
            lore.add("");
            lore.add("&cReplaces " + old.def().display(old.tier()) + "&c!");
        }
        lore.add("");
        lore.add("&eClick to apply!");
        return new Plan(true, false, Icons.glow(Icons.of(Material.ANVIL, "&aApply Rune", lore)));
    }

    private static Plan refuse(String why) {
        return new Plan(false, false, Icons.of(Material.BARRIER, "&cCan't do that", "&7" + why));
    }

    /** Re-validates the live slots, then applies or fuses. Main thread, one tick: no window for a dupe. */
    private void act(Player p, Pick left, Pick right) {
        Plan plan = plan(p, left, right);
        if (!plan.ok) return;
        var inv = p.getInventory();
        ItemStack l = inv.getItem(left.slot), r = inv.getItem(right.slot);
        if (!same(l, left.snap) || !same(r, right.snap)) {
            Msg.error(p, "Your items moved! Place them again.");
            left.clear(); right.clear();
            return;
        }
        Runes.Ref rune = runes.rune(r);
        if (plan.fuse) {
            int need = left.slot == right.slot ? 2 : 1;
            if (l.getAmount() < need) { Msg.error(p, "You need two of this rune."); return; }
            take(inv, left.slot, need == 2 ? 2 : 1);
            if (need == 1) take(inv, right.slot, 1);
            int next = rune.tier() + 1;
            Sky.loot().give(p, Sky.items().create(rune.def().itemId(next)), LootSource.CRAFT);
            double xp = runes.fuseXp(rune.def(), next);
            if (xp > 0) svc.addXp(p, SkillType.RUNECRAFTING, xp, "rune-fuse:" + rune.def().key);
            Msg.send(p, "&5&lRUNE FUSED! &r" + rune.def().display(next));
            p.playSound(p.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 1f, 1.2f);
            // keep the next pair on the pedestal if the same runes are still there (fuse a stack click by click)
            repick(inv, left);
            repick(inv, right);
            return;
        }
        take(inv, right.slot, 1);
        ItemStack target = inv.getItem(left.slot);
        if (target == null) return;
        Sky.items().update(target, d -> Runes.setApplied(d, rune));
        inv.setItem(left.slot, target);
        Msg.send(p, "&aApplied " + rune.def().display(rune.tier()) + " &ato your " + Text.plain(Text.c(name(target))) + "&a!");
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.4f);
        put(left, left.slot, target);
        right.clear();
    }

    private static String name(ItemStack it) {
        var meta = it.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) return Text.toLegacy(meta.displayName()).replace('§', '&');
        return Text.titleCase(it.getType().name());
    }

    private static void repick(org.bukkit.inventory.PlayerInventory inv, Pick k) {
        ItemStack now = k.set() ? inv.getItem(k.slot) : null;
        if (now != null && now.isSimilar(k.snap)) put(k, k.slot, now);
        else k.clear();
    }

    private static boolean same(ItemStack live, ItemStack snap) {
        return live != null && snap != null && live.isSimilar(snap) && live.getAmount() >= Math.min(snap.getAmount(), 1);
    }

    private static void take(org.bukkit.inventory.PlayerInventory inv, int slot, int n) {
        ItemStack it = inv.getItem(slot);
        if (it == null) return;
        if (it.getAmount() <= n) inv.setItem(slot, null);
        else { it.setAmount(it.getAmount() - n); inv.setItem(slot, it); }
    }
}
