package com.skyclone.mod.crimson;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Matriarch's Heavy Pearls (Belly of the Beast, Mystic Marsh). Every day the Matriarch leaves each
 * player {@code crimson.pearls-per-day} pearls on spots across the stomach floor, the spiral ledge and the
 * top landing (the generator's {@code crimson.pearl.<n>} anchors); walking into one collects it. Each
 * player sees only their own (the floating pearls are hidden by default and shown per player), so a crowd
 * never steals from anyone. A Matriarch's Perfume (bits shop item, reference lore: up to 4 a day) releases
 * {@code crimson.perfume-pearls} more. Heavy Pearls feed the 8-10 star upgrades of Leviathan-line armor.
 */
final class Pearls implements Listener {

    static final String PERFUME = "MATRIARCH_PARFUM";
    static final String PEARL = "HEAVY_PEARL";
    private static final double SEE = 48;           // players nearer than this to the Matriarch get pearls drawn
    private static final double PICK = 1.6;

    /** spot index -> the floating pearl shown there (hidden from everyone unless shown per player). */
    private static final Map<Integer, ItemDisplay> DISPLAYS = new HashMap<>();
    private static final List<TextDisplay> LABELS = new ArrayList<>();
    private static List<Location> spots;
    private static long tick;

    static void register() {
        Bukkit.getPluginManager().registerEvents(new Pearls(), Sky.plugin());
        Sky.vitals().actionBarSegment("crimson_pearls", 55, p -> {
            if (!inBelly(p) || !CrimsonModule.ready(p)) return null;
            CrimsonData d = today(p);
            int allow = allowance(d);
            return "&6Heavy Pearls &e" + Math.min(allow, d.pearlsTaken.size()) + "&6/&e" + allow;
        });
    }

    // ---- pure-ish state ---------------------------------------------------------------------------------

    /** The player's data with the pearl fields rolled over to today. */
    static CrimsonData today(Player p) {
        CrimsonData d = CrimsonModule.data(p);
        String day = CUtil.today(CrimsonModule.D.resetHourUtc.get());
        if (!day.equals(d.pearlDay)) {
            d.pearlDay = day;
            d.pearlsTaken.clear();
            d.perfumeUses = 0;
            d.perfumePearls = 0;
        }
        return d;
    }

    static int allowance(CrimsonData d) { return Math.max(0, CrimsonModule.D.pearlsPerDay.get()) + Math.max(0, d.perfumePearls); }

    /** spot index -> pearl number floating there for this player right now. */
    static Map<Integer, Integer> showing(Player p) {
        List<Location> s = spots();
        if (s.isEmpty()) return Map.of();
        CrimsonData d = today(p);
        int[] order = CrimsonRules.pearlOrder(p.getUniqueId().toString(), d.pearlDay, s.size());
        return CrimsonRules.pearlsShowing(order, allowance(d), d.pearlsTaken);
    }

    static List<Location> spots() {
        if (spots == null || spots.isEmpty() || spots.get(0).getWorld() == null) {
            List<Location> out = new ArrayList<>();
            for (int i = 1; i <= 32; i++) {
                Location l = Sky.worlds().anchor(CrimsonModule.ISLAND, "crimson.pearl." + i);
                if (l == null) break;
                out.add(l.clone().add(0, 1.0, 0));
            }
            spots = out;
        }
        return spots;
    }

    static Location matriarch() { return Sky.worlds().anchor(CrimsonModule.ISLAND, "crimson.matriarch"); }

    static boolean inBelly(Player p) {
        Location m = matriarch();
        if (m == null || m.getWorld() != p.getWorld()) return false;
        Location l = p.getLocation();
        double dx = l.getX() - m.getX(), dz = l.getZ() - m.getZ();
        return dx * dx + dz * dz < 17 * 17 && Math.abs(l.getY() - m.getY()) < 30;      // the stomach shaft, floor to roof
    }

    // ---- the tick (every 10 server ticks) -----------------------------------------------------------------

    static void tick() {
        tick++;
        Location m = matriarch();
        if (m == null || m.getWorld() == null) return;
        List<Player> near = new ArrayList<>();
        for (Player p : m.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(m) < SEE * SEE && CrimsonModule.ready(p)) near.add(p);
        if (near.isEmpty()) return;
        List<Location> s = spots();
        ensureEntities(m, s);
        for (Player p : near) {
            Map<Integer, Integer> show = showing(p);
            for (Map.Entry<Integer, ItemDisplay> e : DISPLAYS.entrySet()) {
                ItemDisplay disp = e.getValue();
                if (disp == null || !disp.isValid()) continue;
                if (show.containsKey(e.getKey())) p.showEntity(Sky.plugin(), disp);
                else p.hideEntity(Sky.plugin(), disp);
            }
            for (Map.Entry<Integer, Integer> e : show.entrySet()) {
                Location at = s.get(e.getKey());
                if (tick % 2 == 0) p.spawnParticle(Particle.DUST, at.clone().add(0, 0.2, 0), 4, 0.25, 0.25, 0.25, 0,
                        new Particle.DustOptions(Color.fromRGB(255, 196, 120), 1.1f));
                if (p.getLocation().distanceSquared(at) < PICK * PICK
                        || p.getLocation().add(0, 1, 0).distanceSquared(at) < PICK * PICK) collect(p, e.getValue());
            }
        }
    }

    private static void ensureEntities(Location m, List<Location> s) {
        for (int i = 0; i < s.size(); i++) {
            ItemDisplay d = DISPLAYS.get(i);
            Location at = s.get(i);
            if (d != null && d.isValid()) {
                spin(d);
                continue;
            }
            if (!at.isChunkLoaded()) continue;
            ItemStack look = CUtil.icon(PEARL, Material.ENDER_PEARL);
            ItemDisplay nd = at.getWorld().spawn(at.clone().add(0, 0.15, 0), ItemDisplay.class, e -> {
                e.setVisibleByDefault(false);
                e.setPersistent(false);
                e.setItemStack(look);
                e.setBillboard(Display.Billboard.FIXED);
                e.setGlowing(true);
                e.setGlowColorOverride(Color.fromRGB(255, 170, 60));
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.7f, 0.7f, 0.7f), new AxisAngle4f()));
            });
            DISPLAYS.put(i, nd);
        }
        boolean labelOk = !LABELS.isEmpty() && LABELS.get(0).isValid();
        if (!labelOk && m.isChunkLoaded()) {
            for (TextDisplay t : LABELS) if (t.isValid()) t.remove();
            LABELS.clear();
            LABELS.add(m.getWorld().spawn(m.clone().add(0, -0.5, 0), TextDisplay.class, t -> {
                t.text(Text.c("&c&lThe Matriarch\n&7She sheds &6Heavy Pearls &7every day."));
                t.setBillboard(Display.Billboard.CENTER);
                t.setPersistent(false);
                t.setBrightness(new Display.Brightness(15, 15));
            }));
        }
    }

    private static void spin(ItemDisplay d) {
        float a = (float) ((tick * 0.35) % (Math.PI * 2));
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(10);
        d.setTransformation(new Transformation(new Vector3f(0, (float) (0.12 * Math.sin(tick * 0.5)), 0),
                new AxisAngle4f(a, 0, 1, 0), new Vector3f(0.7f, 0.7f, 0.7f), new AxisAngle4f()));
    }

    static void collect(Player p, int pearl) {
        CrimsonData d = today(p);
        if (d.pearlsTaken.contains(pearl) || pearl >= allowance(d)) return;
        if (!Sky.items().exists(PEARL)) return;
        d.pearlsTaken.add(pearl);
        d.pearlsTotal++;
        Sky.loot().give(p, PEARL, 1, LootSource.PICKUP);
        // hunting's "Matriarch Cubs" (Hellwisp shard): +x% chance the pearl comes with a second one (reference lore)
        double cubs = Attrs.value(p, "matriarch_cubs");
        if (cubs > 0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 100 < cubs) {
            d.pearlsTotal++;
            Sky.loot().give(p, PEARL, 1, LootSource.PICKUP);
            Msg.send(p, "&d&lMATRIARCH CUBS! &7A second &6Heavy Pearl &7was tucked under the first.");
        }
        int left = allowance(d) - d.pearlsTaken.size();
        Msg.send(p, "&6&lPEARL! &7You found a &6Heavy Pearl&7! " + (left > 0
                ? "&e" + left + " &7more today."
                : "&aThat's every pearl the Matriarch left you today."));
        Msg.sound(p, Sound.ENTITY_ITEM_PICKUP, 0.6f);
        Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4f);
    }

    static void removeAll() {
        for (ItemDisplay d : DISPLAYS.values()) if (d != null && d.isValid()) d.remove();
        DISPLAYS.clear();
        for (TextDisplay t : LABELS) if (t.isValid()) t.remove();
        LABELS.clear();
        spots = null;
    }

    // ---- Matriarch's Perfume -----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!PERFUME.equals(Sky.items().idOf(hand))) return;
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        if (!CrimsonModule.ready(p)) return;
        usePerfume(p);
    }

    /** Never let the perfume be drunk as a vanilla stew. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (PERFUME.equals(Sky.items().idOf(e.getItem()))) e.setCancelled(true);
    }

    static boolean usePerfume(Player p) {
        CrimsonData d = today(p);
        int max = Math.max(0, CrimsonModule.D.perfumeUsesPerDay.get());
        if (d.perfumeUses >= max) {
            Msg.error(p, "The Matriarch is already confused enough today. (" + max + "/" + max + ")");
            return false;
        }
        if (Sky.items().remove(p, PERFUME, 1) < 1) return false;
        int extra = Math.max(0, CrimsonModule.D.perfumePearls.get());
        d.perfumeUses++;
        d.perfumePearls += extra;
        Msg.send(p, "&dA pungent smell clings to you... &7The Matriarch in the &cBelly of the Beast &7releases &6"
                + extra + " &7more Heavy Pearl" + (extra == 1 ? "" : "s") + " for you. &8(" + d.perfumeUses + "/" + max + " today)");
        Msg.sound(p, Sound.ENTITY_GENERIC_DRINK, 1.0f);
        return true;
    }

    /** Menu lore: today's state. */
    static List<String> statusLore(Player p) {
        CrimsonData d = today(p);
        int allow = allowance(d);
        List<String> l = new ArrayList<>();
        l.add("&7The Matriarch lies in the stomach of a");
        l.add("&7dead leviathan in the &5Mystic Marsh&7. She");
        l.add("&7sheds &6Heavy Pearls &7every day; climb her");
        l.add("&7stomach to find yours.");
        l.add("");
        l.add("&7Collected today: " + (d.pearlsTaken.size() >= allow ? "&a" : "&e") + d.pearlsTaken.size() + "&7/&a" + allow);
        l.add("&7Perfume used today: &d" + d.perfumeUses + "&7/&d" + CrimsonModule.D.perfumeUsesPerDay.get());
        l.add("&7Pearls found, ever: &6" + Text.commasInt(d.pearlsTotal));
        l.add("&8Resets in " + (CUtil.secondsToReset(CrimsonModule.D.resetHourUtc.get()) / 3600) + "h.");
        return l;
    }
}
