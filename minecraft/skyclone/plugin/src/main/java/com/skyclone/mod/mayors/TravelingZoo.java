package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The Traveling Zoo (research/05 §7.9): Early Summer 1-3 and Early Winter 1-3, one real hour each.
 * The keeper brings THREE pets: one LEGENDARY on a fixed six-step rotation (Giraffe, Elephant, Tiger,
 * Blue Whale, Monkey, Lion) and two random ones at COMMON-EPIC. Prices are the neu-repo shop table
 * (coins + materials scaling with rarity, e.g. Blue Whale LEGENDARY = 10,000,000 + 8 Enchanted Cooked Cod).
 */
final class TravelingZoo {

    static final String[] RARITY = {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic"};

    private final MayorsModule mod;
    private boolean announced;

    TravelingZoo(MayorsModule mod) { this.mod = mod; }

    boolean active() { return Cal.active(Cal.ZOO_SUMMER) || Cal.active(Cal.ZOO_WINTER); }

    /** Zoo number: two per year. Uses the visit that is running, or the next one. */
    static int visitIndex(SkyDate d) {
        int doy = ElectionMath.doy(d.month(), d.day());
        int summer = ElectionMath.doy(4, 1), winter = ElectionMath.doy(10, 1);
        if (doy < summer + 3) return d.year() * 2;          // this year's summer visit (running or next)
        if (doy < winter + 3) return d.year() * 2 + 1;      // this year's winter visit
        return (d.year() + 1) * 2;                          // next year's summer
    }

    /** The three offers of a visit: [legendary, random, random] as "TYPE;rarity" ids. */
    List<String> stock(int visit) {
        List<String> rot = mod.data().zoo.legendaryRotation;
        List<String> out = new ArrayList<>();
        if (rot.isEmpty()) return out;
        String legendary = rot.get(Math.floorMod(visit, rot.size()));
        out.add(legendary + ";4");
        List<String> others = new ArrayList<>(rot);
        others.remove(legendary);
        Random r = new Random(mod.dials().seed.get() * 31L + visit);
        java.util.Collections.shuffle(others, r);
        for (int i = 0; i < 2 && i < others.size(); i++) out.add(others.get(i) + ";" + r.nextInt(4));
        return out;
    }

    EventData.Offer offer(String pet) {
        for (EventData.Offer o : mod.data().zoo.offers) if (pet.equals(o.result)) return o;
        return null;
    }

    void tick() {
        boolean on = active();
        if (on && !announced) {
            announced = true;
            List<String> s = stock(visitIndex(Sky.calendar().now()));
            String leg = s.isEmpty() ? "?" : Text.titleCase(s.get(0).split(";")[0]);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!EventAlerts.news(p, Cal.ZOO_SUMMER)) continue;
                Msg.send(p, "&e&lTRAVELING ZOO! &7The zoo has arrived in the Hub with a &6Legendary " + leg + "&7! (&a/zoo&7)");
                Msg.sound(p, Sound.ENTITY_PARROT_AMBIENT, 1f);
            }
        } else if (!on) {
            announced = false;
        }
    }

    /** /zoo */
    void open(Player p) {
        Menu m = new Menu("Traveling Zoo", 4).chrome();
        boolean on = active();
        int visit = visitIndex(Sky.calendar().now());
        List<String> s = stock(visit);
        m.set(4, Icons.of(Material.LEAD, "&eTraveling Zoo",
                "&7Twice a year the keeper brings three pets:",
                "&7one &6Legendary &7on a fixed rotation and two",
                "&7surprises. Visits: &eEarly Summer &7and &eEarly Winter&7.",
                "",
                on ? "&aThe zoo is open!" : "&7Next visit: " + Cal.when(Cal.ZOO_SUMMER).replace("&e", "") + " &8/ " + Cal.when(Cal.ZOO_WINTER).replace("&e", "")));
        int[] slots = {11, 13, 15};
        for (int i = 0; i < s.size() && i < slots.length; i++) {
            String pet = s.get(i);
            EventData.Offer o = offer(pet);
            String[] parts = pet.split(";");
            int rarity = Integer.parseInt(parts[1]);
            if (o == null || !Goods.available(o)) {
                m.set(slots[i], Icons.of(Material.BARRIER, "&c" + RARITY[rarity] + " " + Text.titleCase(parts[0]), "&7This pet is not available on this server yet."));
                continue;
            }
            String lock = on ? null : "&7Preview: arrives with the next visit.";
            m.set(slots[i], ShopUi.offerIcon(p, o, 1.0, lock), c -> {
                if (!active()) { Msg.error(c.player(), "The zoo is not in town right now."); return; }
                Sky.menus().confirm(c.player(), "Adopt " + Text.titleCase(parts[0]) + "?", Goods.costLines(o, 1.0),
                        () -> { ShopUi.buy(c.player(), o, 1.0, "traveling zoo"); open(c.player()); }, () -> open(c.player()));
            });
        }
        List<String> rot = mod.data().zoo.legendaryRotation;
        List<String> next = new ArrayList<>();
        next.add("&7Legendary pets of the coming visits:");
        for (int k = 1; k <= 3 && !rot.isEmpty(); k++)
            next.add("&8▸ &6" + Text.titleCase(rot.get(Math.floorMod(visit + k, rot.size()))));
        m.set(22, Icons.of(Material.SPYGLASS, "&aComing Up", next));
        m.set(30, Icons.back("Festivals & Events"), c -> EventBoard.open(c.player()));
        m.open(p);
    }
}
