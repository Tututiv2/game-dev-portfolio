package com.skyclone.mod.accessories;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Accessory Bag Thaumaturgy: powers, stats tuning (+ templates), power stones, the Rift Prism. */
final class PowerMenus {

    private static final int[] TUNE_SLOTS = {19, 20, 21, 22, 23, 24, 25, 31};
    private static final int[] TEMPLATE_SLOTS = {38, 39, 40, 41, 42};

    private final Bags bags;
    private final Catalog cat;
    private Ui ui;

    PowerMenus(Bags bags, Catalog cat) {
        this.bags = bags;
        this.cat = cat;
    }

    void wire(Ui ui) { this.ui = ui; }

    static String fmt(double v) {
        return Text.commas(Math.round(v * 100) / 100.0);
    }

    static String statLine(Stat s, double v) {
        return "&" + s.color + (v >= 0 ? "+" : "") + fmt(v) + (s.percent ? "%" : "") + " " + s.symbol + " " + s.display;
    }

    // ---- main ----------------------------------------------------------------------------------

    void openMain(Player p) {
        if (!Bags.ready(p)) return;
        AccData d = Bags.data(p);
        Snapshot snap = bags.snapshot(p);
        int mp = bags.magicalPower(p);
        int points = AccMath.tuningPoints(mp, AccDials.MP_PER_TUNING.get());
        Menu m = new Menu("Accessory Bag Thaumaturgy", 6).chrome();

        List<String> sum = new ArrayList<>();
        sum.add("&7Your accessories add up to");
        sum.add("&6" + Text.commasInt(mp) + " Accessory Power&7.");
        sum.add("");
        for (Rarity r : Rarity.values()) {
            int n = snap.countByRarity.getOrDefault(r, 0);
            if (n > 0) sum.add("&" + r.color + r.display() + "&7: &a" + n + " &8(" + n * AccDials.mp(r) + " power)");
        }
        if (d.riftPrism) sum.add("&dRift Prism&7: &a+" + AccDials.RIFT_PRISM_MP.get());
        sum.add("");
        sum.add("&7Stat multiplier: &a" + fmt(AccMath.multiplier(mp)) + "x");
        sum.add("&7Tuning points: &e" + points);
        int per = Math.max(1, AccDials.MP_PER_TUNING.get());
        sum.add("&8" + (per - mp % per) + " more power for the next point.");
        sum.add("");
        sum.add("&8Multiplier = 29.97 x ln(0.0019 x power + 1)^1.2");
        m.set(4, Icons.of(Material.NETHER_STAR, "&6Accessory Power", sum));

        Catalog.Power pw = cat.powers.get(d.power);
        List<String> pl = new ArrayList<>();
        if (pw == null) pl.add("&cNo power selected!");
        else {
            pl.add("&7Selected: &a" + pw.name());
            pl.addAll(powerStats(pw, mp));
        }
        pl.add("");
        pl.add("&eClick to choose a power!");
        m.set(20, Icons.of(Material.ENCHANTED_BOOK, "&aPowers", pl), c -> openPowers(c.player(), 0));

        int used = 0;
        for (int v : d.tuning.values()) used += Math.max(0, v);
        m.set(22, Icons.of(Material.COMPARATOR, "&aStats Tuning", "&7Spend one point per &6" + per + " Accessory Power",
                "&7on the stats you want.", "", "&7Points: &e" + Math.min(used, points) + "&7/&e" + points, "",
                "&eClick to tune!"), c -> openTuning(c.player()));

        int learned = d.learned.size();
        m.set(24, Icons.of(Material.AMETHYST_SHARD, "&5Power Stones", "&7Learn new powers by bringing",
                "&a" + AccDials.STONES_TO_LEARN.get() + " &7of the same power stone.", "",
                "&7Learned: &a" + learned + "&7/&a" + cat.powerByStone.size(), "", "&eClick to view!"), c -> openStones(c.player(), 0));

        if (d.riftPrism) {
            m.set(30, Icons.of(Material.PRISMARINE_CRYSTALS, "&dRift Prism", "&7Consumed: &a+" + AccDials.RIFT_PRISM_MP.get() + " Accessory Power",
                    "&7for good."));
        } else {
            boolean has = bags.slotOf(p, "RIFT_PRISM") >= 0 || Sky.items().count(p, "RIFT_PRISM") > 0;
            m.set(30, Icons.of(Material.PRISMARINE_CRYSTALS, "&dRift Prism", "&7Consume a Rift Prism for a permanent",
                    "&6+" + AccDials.RIFT_PRISM_MP.get() + " Accessory Power&7.", "",
                    has ? "&eClick to consume it!" : "&cYou don't have one."), c -> consumePrism(c.player()));
        }
        m.set(32, Icons.of(Material.LEATHER, "&aAccessory Bag", "&eClick to open!"), c -> ui.bag.open(c.player(), 0));
        m.set(48, Icons.back("Accessory Bag"), c -> ui.bag.open(c.player(), 0));
        m.open(p);
    }

    List<String> powerStats(Catalog.Power pw, int mp) {
        List<String> out = new ArrayList<>();
        double mult = AccMath.multiplier(mp) * AccDials.POWER_SCALE.get();
        out.add("&7At &6" + Text.commasInt(mp) + " Accessory Power&7:");
        for (Map.Entry<Stat, Double> e : pw.base().entrySet()) out.add(statLine(e.getKey(), e.getValue() * mult));
        if (!pw.unique().isEmpty()) {
            out.add("");
            out.add("&7Unique Power Bonus:");
            for (Map.Entry<Stat, Double> e : pw.unique().entrySet()) out.add(statLine(e.getKey(), e.getValue()));
        }
        return out;
    }

    boolean unlocked(Player p, Catalog.Power pw) {
        return pw.starter() || Bags.data(p).learned.contains(pw.id());
    }

    // ---- powers --------------------------------------------------------------------------------

    void openPowers(Player p, int page) {
        if (!Bags.ready(p)) return;
        int mp = bags.magicalPower(p);
        List<Catalog.Power> list = new ArrayList<>(cat.powers.values());
        Sky.menus().paged("Select a Power", list, pw -> powerIcon(p, pw, mp), pc -> selectPower(pc.click().player(), pc.entry(), pc.page()),
                menu -> menu.set(48, Icons.back("Thaumaturgy"), c -> openMain(c.player())), page).open(p);
    }

    ItemStack powerIcon(Player p, Catalog.Power pw, int mp) {
        boolean selected = p != null && pw.id().equals(Bags.data(p).power);
        boolean open = p == null || unlocked(p, pw);
        boolean reqOk = p == null || pw.requirement() == null || Sky.requirements().check(p, pw.requirement());
        List<String> lore = new ArrayList<>(powerStats(pw, mp));
        lore.add("");
        if (pw.requirement() != null) {
            lore.add((reqOk ? "&a✔ " : "&c✘ ") + "&7Requires " + Sky.requirements().describe(pw.requirement()));
        }
        if (selected) lore.add("&aSELECTED");
        else if (!open) {
            ItemDef st = Sky.items().def(pw.stone());
            lore.add("&cLocked: learn it with " + AccDials.STONES_TO_LEARN.get() + "x " + (st == null ? pw.stone() : Text.strip(st.name)) + "&c.");
        } else if (!reqOk) lore.add("&cYou don't meet the requirement.");
        else lore.add("&eClick to select!");
        Material mat = selected ? Material.ENCHANTED_BOOK : open ? Material.BOOK : Material.GRAY_DYE;
        ItemStack it = Icons.of(mat, (selected ? "&a" : open ? "&e" : "&c") + pw.name() + (pw.starter() ? "" : " &8(stone power)"), lore);
        return selected ? Icons.glow(it) : it;
    }

    private void selectPower(Player p, Catalog.Power pw, int page) {
        if (!unlocked(p, pw)) { Msg.error(p, "You haven't learned that power yet."); return; }
        if (pw.requirement() != null && !Sky.requirements().checkOrTell(p, pw.requirement())) return;
        Bags.data(p).power = pw.id();
        bags.invalidate(p);
        Msg.success(p, "Selected the " + pw.name() + " power!");
        openPowers(p, page);
    }

    // ---- tuning --------------------------------------------------------------------------------

    void openTuning(Player p) {
        if (!Bags.ready(p)) return;
        AccData d = Bags.data(p);
        int points = AccMath.tuningPoints(bags.magicalPower(p), AccDials.MP_PER_TUNING.get());
        int used = 0;
        for (int v : d.tuning.values()) used += Math.max(0, v);
        Menu m = new Menu("Stats Tuning", 6).chrome();
        m.set(4, Icons.of(Material.COMPARATOR, "&aStats Tuning", "&7Points spent: &e" + used + "&7/&e" + points,
                used > points ? "&cYou lost Accessory Power: only the first " + points + " count." : "&7Unspent: &a" + Math.max(0, points - used),
                "", "&7Left-click: &a+1  &7Shift-left: &a+10", "&7Right-click: &c-1  &7Shift-right: &c-10"));
        List<String> order = AccDials.tuningOrder();
        for (int i = 0; i < order.size() && i < TUNE_SLOTS.length; i++) {
            String key = order.get(i);
            Stat s = Stat.parse(key);
            if (s == null) continue;
            int pts = d.tuning.getOrDefault(key, 0);
            double per = AccDials.TUNING.get(key).get();
            m.set(TUNE_SLOTS[i], Icons.amount(Icons.of(tuneIcon(s), "&" + s.color + s.symbol + " " + s.display,
                    "&7Points: &e" + pts, "&7Bonus: " + statLine(s, pts * per), "&8(" + fmt(per) + " per point)", "",
                    "&eClick to change!"), Math.max(1, pts)), c -> changeTuning(c.player(), key, c.left(), c.shift()));
        }
        m.set(29, Icons.of(Material.BARRIER, "&cReset Tuning", "&7Take back every point.", "", "&eClick to reset!"), c -> {
            Bags.data(c.player()).tuning.clear();
            bags.invalidate(c.player());
            openTuning(c.player());
        });
        for (int t = 0; t < TEMPLATE_SLOTS.length; t++) {
            final int n = t + 1;
            if (n > Math.max(1, AccDials.TEMPLATE_MAX.get())) break;
            boolean unlockedSlot = n <= d.templateSlots;
            Map<String, Integer> saved = d.templates.get(String.valueOf(n));
            if (!unlockedSlot) {
                m.set(TEMPLATE_SLOTS[t], Icons.of(Material.GRAY_DYE, "&cTemplate " + n, "&7Unlock this template slot for",
                        "&b" + Text.commasInt(AccDials.TEMPLATE_BITS.get()) + " Bits&7.", "", "&eClick to unlock!"),
                        c -> unlockTemplate(c.player(), n));
                continue;
            }
            List<String> lore = new ArrayList<>();
            if (saved == null || saved.isEmpty()) lore.add("&7Empty.");
            else for (Map.Entry<String, Integer> e : saved.entrySet()) {
                Stat s = Stat.parse(e.getKey());
                if (s != null && e.getValue() > 0) lore.add("&" + s.color + s.display + "&7: &e" + e.getValue());
            }
            lore.add("");
            lore.add("&eLeft-click to load!");
            lore.add("&eRight-click to save the current tuning!");
            m.set(TEMPLATE_SLOTS[t], Icons.of(Material.PAPER, "&aTemplate " + n, lore), c -> {
                AccData dd = Bags.data(c.player());
                if (c.right()) {
                    dd.templates.put(String.valueOf(n), new HashMap<>(dd.tuning));
                    Msg.success(c.player(), "Saved your tuning to template " + n + ".");
                } else {
                    Map<String, Integer> tpl = dd.templates.get(String.valueOf(n));
                    if (tpl == null) { Msg.error(c.player(), "That template is empty."); return; }
                    dd.tuning.clear();
                    dd.tuning.putAll(tpl);
                    bags.invalidate(c.player());
                    Msg.success(c.player(), "Loaded template " + n + ".");
                }
                openTuning(c.player());
            });
        }
        m.set(48, Icons.back("Thaumaturgy"), c -> openMain(c.player()));
        m.open(p);
    }

    private static Material tuneIcon(Stat s) {
        return switch (s) {
            case HEALTH -> Material.GOLDEN_APPLE;
            case DEFENSE -> Material.IRON_CHESTPLATE;
            case SPEED -> Material.SUGAR;
            case STRENGTH -> Material.BLAZE_POWDER;
            case CRIT_DAMAGE -> Material.DIAMOND_SWORD;
            case CRIT_CHANCE -> Material.GOLDEN_SWORD;
            case ATTACK_SPEED -> Material.GOLDEN_AXE;
            default -> Material.ENCHANTED_BOOK;
        };
    }

    private void changeTuning(Player p, String key, boolean add, boolean ten) {
        AccData d = Bags.data(p);
        int points = AccMath.tuningPoints(bags.magicalPower(p), AccDials.MP_PER_TUNING.get());
        int used = 0;
        for (int v : d.tuning.values()) used += Math.max(0, v);
        int cur = d.tuning.getOrDefault(key, 0);
        int step = ten ? 10 : 1;
        int next = add ? cur + Math.min(step, Math.max(0, points - used)) : Math.max(0, cur - step);
        if (add && next == cur) { Msg.error(p, "You have no tuning points left!"); return; }
        if (next == 0) d.tuning.remove(key); else d.tuning.put(key, next);
        bags.invalidate(p);
        openTuning(p);
    }

    private void unlockTemplate(Player p, int n) {
        AccData d = Bags.data(p);
        if (d.templateSlots >= n) return;
        if (n != d.templateSlots + 1) { Msg.error(p, "Unlock the earlier template slots first."); return; }
        long cost = Math.max(0, AccDials.TEMPLATE_BITS.get());
        if (!Sky.economy().spendBits(p, cost, "accessories: tuning template " + n)) {
            Msg.error(p, "You need " + Text.commasInt(cost) + " Bits.");
            return;
        }
        d.templateSlots = n;
        Msg.success(p, "Unlocked tuning template " + n + "!");
        openTuning(p);
    }

    // ---- stones --------------------------------------------------------------------------------

    void openStones(Player p, int page) {
        if (!Bags.ready(p)) return;
        List<Catalog.Power> list = new ArrayList<>();
        for (Catalog.Power pw : cat.powers.values()) if (pw.stone() != null) list.add(pw);
        Sky.menus().paged("Power Stones", list, pw -> stoneIcon(p, pw), pc -> {
            learn(pc.click().player(), pc.entry());
            openStones(pc.click().player(), pc.page());
        }, menu -> menu.set(48, Icons.back("Thaumaturgy"), c -> openMain(c.player())), page).open(p);
    }

    ItemStack stoneIcon(Player p, Catalog.Power pw) {
        ItemStack it;
        try { it = Sky.items().create(pw.stone()); } catch (RuntimeException e) { it = Icons.of(Material.AMETHYST_SHARD, "&5" + pw.stone()); }
        int need = Math.max(1, AccDials.STONES_TO_LEARN.get());
        List<String> extra = new ArrayList<>();
        extra.add("");
        if (p != null && Bags.data(p).learned.contains(pw.id())) {
            extra.add("&a✔ You know the " + pw.name() + " power.");
        } else if (p != null) {
            int have = Sky.items().count(p, pw.stone());
            extra.add("&7In your inventory: " + (have >= need ? "&a" : "&c") + have + "&7/&a" + need);
            extra.add(have >= need ? "&eClick to learn " + pw.name() + "!" : "&cBring " + need + " to learn it.");
        }
        return Icons.withLore(it, extra);
    }

    /** Learn a stone power by consuming the stones (from a menu click or a right-click). */
    boolean learn(Player p, Catalog.Power pw) {
        if (!Bags.ready(p) || pw == null || pw.stone() == null) return false;
        AccData d = Bags.data(p);
        if (d.learned.contains(pw.id())) { Msg.error(p, "You already know the " + pw.name() + " power."); return false; }
        if (pw.requirement() != null && !Sky.requirements().checkOrTell(p, pw.requirement())) return false;
        int need = Math.max(1, AccDials.STONES_TO_LEARN.get());
        if (Sky.items().count(p, pw.stone()) < need) {
            Msg.error(p, "You need " + need + " of that stone in your inventory.");
            return false;
        }
        int took = Sky.items().remove(p, pw.stone(), need);
        if (took < need) {
            if (took > 0) Sky.loot().give(p, pw.stone(), took, com.skyclone.api.loot.LootSource.OTHER);
            Msg.error(p, "Could not take the stones.");
            return false;
        }
        d.learned.add(pw.id());
        if (!d.powerXpPaid.contains(pw.id())) {
            d.powerXpPaid.add(pw.id());
            Sky.service(Features.SkyLevel.class).award(p, "accessories:power:" + pw.id(), 15, "Learned the " + pw.name() + " power");
        }
        if (d.power == null || d.power.isEmpty()) d.power = pw.id();
        bags.invalidate(p);
        Msg.box(p, "&d&lPOWER LEARNED &a" + pw.name(), List.of("&7Select it in &e/powers &7to use it."));
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
        return true;
    }

    // ---- rift prism ----------------------------------------------------------------------------

    private void consumePrism(Player p) {
        AccData d = Bags.data(p);
        if (d.riftPrism) return;
        int slot = bags.slotOf(p, "RIFT_PRISM");
        boolean inv = slot < 0 && Sky.items().count(p, "RIFT_PRISM") > 0;
        if (slot < 0 && !inv) { Msg.error(p, "You need a Rift Prism in your bag or inventory."); return; }
        Sky.menus().confirm(p, "Consume Rift Prism?", List.of("&7It is used up, and you gain", "&6+" + AccDials.RIFT_PRISM_MP.get()
                + " Accessory Power &7for good."), () -> {
            AccData dd = Bags.data(p);
            if (dd.riftPrism) return;
            int s = bags.slotOf(p, "RIFT_PRISM");
            if (s >= 0) bags.setBag(p, s, null);
            else if (Sky.items().remove(p, "RIFT_PRISM", 1) < 1) { Msg.error(p, "The prism is gone."); return; }
            dd.riftPrism = true;
            bags.invalidate(p);
            Msg.success(p, "The Rift Prism dissolves into your bag: +" + AccDials.RIFT_PRISM_MP.get() + " Accessory Power!");
            openMain(p);
        }, () -> openMain(p));
    }
}
