package com.skyclone.mod.extras;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * City Projects (research 08 section 6.8 names the Community Shop tab; research 05: 200 fame per contribution).
 * The research has no project table, so the projects are designed in the reference's spirit and every number is
 * TUNE: a few community builds run at once for {@code extras.city.cycle-days}; each has components a player funds
 * once (coins + items) for a PERMANENT personal perk and SkyBlock XP; when the server's contributions to a project
 * reach its goal, a community perk switches on for everyone until the cycle ends.
 */
final class City implements Listener {

    /** "cycle:project" -> contributions this cycle (server-wide). */
    static final Map<String, Integer> PROGRESS = new ConcurrentHashMap<>();

    static long cycle() { return Rotation.cycle(System.currentTimeMillis(), XDials.cityCycleDays.get() * 24); }

    static long cycleMillisLeft() {
        double h = XDials.cityCycleDays.get() * 24;
        long c = cycle();
        return Math.max(0, Rotation.cycleStart(c + 1, h) - System.currentTimeMillis());
    }

    static List<Catalog.Project> active() {
        List<Catalog.Project> all = new ArrayList<>(X.catalog.projects.values());
        List<Catalog.Project> out = new ArrayList<>();
        if (!XDials.cityEnabled.get()) return out;
        for (int i : Rotation.activeProjects(cycle(), all.size(), XDials.cityPerCycle.get())) out.add(all.get(i));
        return out;
    }

    static boolean isActive(Catalog.Project p) {
        for (Catalog.Project a : active()) if (a.id.equals(p.id)) return true;
        return false;
    }

    static int goal(Catalog.Project p) { return Math.max(1, (int) Math.ceil(p.goal * XDials.cityGoalScale.get())); }

    static int progress(Catalog.Project p) { return PROGRESS.getOrDefault(cycle() + ":" + p.id, 0); }

    static boolean complete(Catalog.Project p) { return isActive(p) && progress(p) >= goal(p); }

    static long cost(long coins) { return Math.round(coins * XDials.cityCostScale.get()); }

    static int cost(int n) { return Math.max(1, (int) Math.ceil(n * XDials.cityCostScale.get())); }

    // ---- persistence ---------------------------------------------------------------------------------------

    static void load() {
        long c = cycle();
        Sky.db().query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT project, contributions FROM extras_city WHERE cycle = ?")) {
                ps.setLong(1, c);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) PROGRESS.put(c + ":" + rs.getString(1), rs.getInt(2));
                }
            }
            return null;
        });
    }

    private static void persist(long c, String project) {
        Sky.db().transaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO extras_city (cycle, project, contributions) VALUES (?, ?, 1) "
                            + "ON CONFLICT(cycle, project) DO UPDATE SET contributions = contributions + 1")) {
                ps.setLong(1, c);
                ps.setString(2, project);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---- perks ---------------------------------------------------------------------------------------------

    /** Personal perks: every component the player ever funded, permanently. */
    static void contribute(Player p, StatMap into) {
        ExtrasData d = X.data(p);
        if (d.cityDone.isEmpty()) return;
        double scale = XDials.cityPerkScale.get();
        for (String key : d.cityDone) {
            int i = key.indexOf(':');
            if (i < 0) continue;
            Catalog.Project pr = X.catalog.projects.get(key.substring(0, i));
            Catalog.Component co = pr == null ? null : pr.component(key.substring(i + 1));
            if (co == null || co.stat == null) continue;
            Stat s = Stat.parse(co.stat);
            if (s != null) into.add(s, co.value * scale);
        }
        // community stat perks (type "stat") for everyone while complete
        for (Catalog.Project pr : active()) {
            if (pr.perk != null && "stat".equals(pr.perk.type) && complete(pr)) {
                Stat s = Stat.parse(pr.perk.skill);
                if (s != null) into.add(s, pr.perk.value * scale);
            }
        }
    }

    static double communityValue(String type, String skill) {
        double v = 0;
        for (Catalog.Project pr : active()) {
            if (pr.perk == null || !type.equals(pr.perk.type) || !complete(pr)) continue;
            if (skill != null && !skill.equalsIgnoreCase(pr.perk.skill)) continue;
            v += pr.perk.value;
        }
        return v * XDials.cityPerkScale.get();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSkillXp(SkyEvents.SkillXpGain e) {
        SkillType s = e.skill;
        if (s == null || e.amount <= 0) return;
        double v = communityValue("skill_xp", s.name());
        if (v > 0) e.amount *= 1 + v;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || e.coins <= 0) return;
        double v = communityValue("mob_coins", null);
        if (v > 0) e.coins *= 1 + v;
    }

    // ---- contributing -------------------------------------------------------------------------------------

    static void fund(Player p, Catalog.Project pr, Catalog.Component co) {
        if (!X.ready(p)) return;
        if (!isActive(pr)) { Msg.error(p, "That project is not being built right now."); return; }
        ExtrasData d = X.data(p);
        String key = pr.id + ":" + co.id;
        if (d.cityDone.contains(key)) { Msg.error(p, "You already funded that!"); return; }
        long coins = cost(co.coins);
        if (Sky.economy().purse(p) < coins) { Msg.error(p, "You need " + Text.commas(coins) + " coins in your purse."); return; }
        for (Map.Entry<String, Integer> e : co.items.entrySet()) {
            if (Sky.items().count(p, e.getKey()) < cost(e.getValue())) {
                Msg.error(p, "You need " + Text.commas(cost(e.getValue())) + "x " + Text.strip(X.itemName(e.getKey())) + ".");
                return;
            }
        }
        if (coins > 0 && !Sky.economy().withdraw(p, coins, "extras: city project " + key)) { Msg.error(p, "You cannot afford that."); return; }
        for (Map.Entry<String, Integer> e : co.items.entrySet()) Sky.items().remove(p, e.getKey(), cost(e.getValue()));
        d.cityDone.add(key);
        long c = cycle();
        int now = PROGRESS.merge(c + ":" + pr.id, 1, Integer::sum);
        persist(c, pr.id);
        Sky.stats().invalidate(p);
        X.sbxp(p, "city:" + key, co.sbxp, "City Project");
        List<String> body = new ArrayList<>();
        Stat s = co.stat == null ? null : Stat.parse(co.stat);
        if (s != null) body.add("&a+" + Text.commas(co.value * XDials.cityPerkScale.get()) + " " + s.label() + " &8(permanent)");
        body.add("&7Project progress: &a" + now + "&7/&a" + goal(pr));
        Msg.box(p, "&6&lCITY PROJECT &e" + co.name + " &7funded!", body);
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.3f);
        if (now == goal(pr) && pr.perk != null) {
            Msg.broadcast("&6&l✦ CITY PROJECT COMPLETE! &e" + pr.name + " &7is finished. Everyone gets &a" + pr.perk.text
                    + " &7until the projects change!");
            for (Player o : Bukkit.getOnlinePlayers()) Sky.stats().invalidate(o);
        }
        Sky.profiles().saveNow(p);
    }

    // ---- menus ---------------------------------------------------------------------------------------------

    static void open(Player p) {
        if (!X.ready(p)) { Msg.error(p, "Your profile is still loading."); return; }
        if (!XDials.cityEnabled.get()) { Msg.error(p, "City Projects are closed right now."); return; }
        Menu m = new Menu("City Projects", 4).chrome();
        List<Catalog.Project> act = active();
        int[] slots = {11, 13, 15, 20, 22, 24};
        for (int i = 0; i < act.size() && i < slots.length; i++) {
            Catalog.Project pr = act.get(i);
            m.set(slots[i], projectIcon(p, pr), c -> project(c.player(), pr));
        }
        m.set(4, Icons.of(Material.BRICKS, "&aCity Projects", "&7Fund the parts of a build to earn a", "&7permanent perk. Finish a project",
                "&7together and everyone gets its bonus.", "", "&7Projects change in &e" + Text.duration(cycleMillisLeft() / 1000)));
        m.set(32, perksIcon(p));
        m.set(30, Icons.back("Community"), c -> Community.open(c.player()));
        m.open(p);
    }

    private static org.bukkit.inventory.ItemStack projectIcon(Player p, Catalog.Project pr) {
        ExtrasData d = X.data(p);
        int mine = 0;
        for (Catalog.Component co : pr.components) if (d.cityDone.contains(pr.id + ":" + co.id)) mine++;
        List<String> l = new ArrayList<>();
        for (String s : pr.desc) l.add("&7" + s);
        l.add("");
        int prog = progress(pr), goal = goal(pr);
        l.add("&7Community: &a" + Math.min(prog, goal) + "&7/&a" + goal + " &8" + Text.bar(Math.min(1.0, prog / (double) goal), 20));
        if (pr.perk != null) l.add((complete(pr) ? "&a✔ " : "&7When finished: ") + "&a" + pr.perk.text + " &7for everyone");
        l.add("&7You funded: &a" + mine + "&7/&a" + pr.components.size());
        l.add("");
        l.add("&eClick to view!");
        Material mat = Material.matchMaterial(pr.icon == null ? "BRICKS" : pr.icon);
        return Icons.of(mat == null ? Material.BRICKS : mat, (complete(pr) ? "&a" : "&e") + pr.name, l);
    }

    private static org.bukkit.inventory.ItemStack perksIcon(Player p) {
        StatMap tmp = new StatMap();
        ExtrasData d = X.data(p);
        double scale = XDials.cityPerkScale.get();
        List<String> l = new ArrayList<>();
        l.add("&7Permanent perks from every project");
        l.add("&7part you have funded:");
        l.add("");
        for (String key : d.cityDone) {
            int i = key.indexOf(':');
            Catalog.Project pr = i < 0 ? null : X.catalog.projects.get(key.substring(0, i));
            Catalog.Component co = pr == null ? null : pr.component(key.substring(i + 1));
            Stat s = co == null || co.stat == null ? null : Stat.parse(co.stat);
            if (s != null) tmp.add(s, co.value * scale);
        }
        boolean any = false;
        for (Stat s : Stat.values()) {
            double v = tmp.flat(s);
            if (v != 0) { l.add("&8 • &a+" + Text.commas(v) + " " + s.label()); any = true; }
        }
        if (!any) l.add("&8None yet.");
        return Icons.of(Material.NETHER_STAR, "&aYour City Perks", l);
    }

    static void project(Player p, Catalog.Project pr) {
        ExtrasData d = X.data(p);
        Menu m = new Menu(pr.name, 4).chrome();
        m.set(4, projectIcon(p, pr));
        int[] slots = {19, 21, 23, 25};
        for (int i = 0; i < pr.components.size() && i < slots.length; i++) {
            Catalog.Component co = pr.components.get(i);
            boolean done = d.cityDone.contains(pr.id + ":" + co.id);
            List<String> l = new ArrayList<>();
            Stat s = co.stat == null ? null : Stat.parse(co.stat);
            if (s != null) l.add("&7Perk: &a+" + Text.commas(co.value * XDials.cityPerkScale.get()) + " " + s.label() + " &8(permanent)");
            l.add("&7Reward: &b+" + co.sbxp + " SkyBlock XP");
            l.add("");
            l.add("&7Cost:");
            if (co.coins > 0) l.add("&8 • &6" + Text.commas(cost(co.coins)) + " coins");
            co.items.forEach((id, n) -> l.add("&8 • &a" + Text.commas(cost(n)) + "x " + X.itemName(id)));
            l.add("");
            l.add(done ? "&aFunded!" : isActive(pr) ? "&eClick to fund!" : "&cThis project is not running now.");
            m.set(slots[i], Icons.of(done ? Material.LIME_STAINED_GLASS : Material.SCAFFOLDING, (done ? "&a" : "&e") + co.name, l), c -> {
                if (done || !isActive(pr)) return;
                List<String> conf = new ArrayList<>();
                conf.add("&7Fund &e" + co.name + "&7 for:");
                if (co.coins > 0) conf.add("&6" + Text.commas(cost(co.coins)) + " coins");
                co.items.forEach((id, n) -> conf.add("&a" + Text.commas(cost(n)) + "x " + X.itemName(id)));
                Sky.menus().confirm(c.player(), "Fund " + co.name + "?", conf, () -> {
                    fund(c.player(), pr, co);
                    project(c.player(), pr);
                }, () -> project(c.player(), pr));
            });
        }
        m.set(30, Icons.back("City Projects"), c -> open(c.player()));
        m.open(p);
    }
}
