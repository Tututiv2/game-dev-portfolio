package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Rift Gallery (wave 3; research 08 sec 5.4, research 06 "Becomes more powerful as you fill the Rift
 * Gallery. Secured: 8/8 Timecharms"). Its curator, Curator Ilsabet, takes each Timecharm you have earned
 * and SECURES it in the gallery: it then floats over its own pedestal (an item display only the players who
 * secured that charm can see; everyone sees the pedestal's label), pays a one-time Mote fee, and powers the
 * Inner Dimensional Necklace ({@link RiftStats} scales its Rift stats by secured/8). Her shop sells both
 * necklaces, gated on how full your gallery is ({@code rift:gallery:<n>}).
 *
 * <p>Exhibit spots are layout data {@code gallery.exhibit.1..8} ("x,y,z", the air block over each pedestal),
 * written by the generator (v2). Displays are not persistent: they are spawned when the gallery's chunk is
 * loaded and re-spawned if they vanish.
 */
final class RiftGallery {

    static final String CURATOR = "rift_curator";
    static final String TAG = "skyclone_rift_exhibit";
    /** Items whose Rift stats scale with the gallery. */
    static final Set<String> SCALED = Set.of("RIFT_NECKLACE_INSIDE");
    /** What each Timecharm looks like on its pedestal (catalog order). */
    static final Map<String, Material> LOOK = Map.of(
            "supreme", Material.GOLDEN_HELMET,
            "chicken", Material.EGG,
            "mirror", Material.GLASS,
            "citizen", Material.PAPER,
            "living", Material.HEART_OF_THE_SEA,
            "globulate", Material.SLIME_BALL,
            "vampiric", Material.REDSTONE,
            "celestial", Material.NETHER_STAR);

    private final RiftModule m;
    private final Map<Integer, ItemDisplay> items = new HashMap<>();
    private final Map<Integer, TextDisplay> labels = new HashMap<>();
    private float spin;

    RiftGallery(RiftModule m) { this.m = m; }

    // =========================================================================================
    // the curator
    // =========================================================================================

    void click(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        if (d.flags.add("met_curator")) {
            Sky.npcs().talk(p, CURATOR, List.of(
                    "Welcome to the Rift Gallery. Time stands still in here - mind the dust, it has been settling for centuries.",
                    "Eight pedestals, one for each Timecharm. Bring me a charm and I will secure it here for you.",
                    "A secured charm is safe for ever, and it feeds my necklaces. The fuller your gallery, the stronger they shine."),
                    () -> menu(p));
            m.award(p, "quest:curator", 3, "Met the Curator");
            return;
        }
        menu(p);
    }

    void menu(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        Menu menu = new Menu("Rift Gallery", 5).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        int i = 0, ready = 0;
        for (RiftCatalog.Charm c : RiftCatalog.CHARMS) {
            boolean owned = d.charms.contains(c.id()), secured = d.gallery.contains(c.id());
            List<String> lore = new ArrayList<>(Text.wrap("&7" + c.how(), 30));
            lore.add("");
            ItemStack icon;
            if (secured) {
                lore.add("&aSecured in the Gallery!");
                icon = Icons.glow(Icons.of(LOOK.getOrDefault(c.id(), Material.CLOCK), "&a" + c.name(), lore));
                menu.set(slots[i++], icon);
            } else if (owned) {
                ready++;
                lore.add("&7Securing pays &d" + Text.commasInt(m.dials.gallerySecureMotes.get()) + " Motes&7 once");
                lore.add("&7and powers the gallery necklaces.");
                lore.add("");
                lore.add("&eClick to secure it!");
                icon = Icons.of(LOOK.getOrDefault(c.id(), Material.CLOCK), "&e" + c.name(), lore);
                menu.set(slots[i++], icon, ck -> { secure(ck.player(), c.id(), true); menu(ck.player()); });
            } else {
                lore.add("&cYou have not found this Timecharm.");
                icon = Icons.of(Material.GRAY_DYE, "&7" + c.name(), lore);
                menu.set(slots[i++], icon);
            }
        }
        int n = d.gallery.size(), total = RiftCatalog.CHARMS.size();
        menu.set(4, Icons.of(Material.ITEM_FRAME, "&dThe Rift Gallery",
                "&7Secured: &a" + n + "&7/" + total + " Timecharms", "",
                "&7Each secured charm floats over its", "&7pedestal here and makes the", "&dDimensional Necklaces &7stronger.", "",
                "&7Necklace power: &a" + Math.round(RiftMath.galleryScale(n, total) * 100) + "%"));
        if (ready > 0) {
            final int r = ready;
            menu.set(31, Icons.glow(Icons.of(Material.LIME_DYE, "&aSecure Everything", "&7Secure all &a" + r + " &7Timecharms", "&7you are carrying.", "",
                    "&eClick to secure!")), ck -> { secureAll(ck.player()); menu(ck.player()); });
        }
        menu.set(30, Icons.of(Material.AMETHYST_CLUSTER, "&dCurator's Necklaces", "&7Necklaces that grow with your", "&7gallery, sold for Motes.", "",
                "&7Your Motes: &d" + Text.commasInt(d.motes), "", "&eClick to browse!"), ck -> m.menus.shop(ck.player(), "curator"));
        menu.set(32, Icons.of(Material.CLOCK, "&aTime stands still", "&7Rift Time does not drain inside", "&7the Rift Gallery."));
        menu.set(40, Icons.close(), ck -> ck.player().closeInventory());
        menu.open(p);
    }

    void secureAll(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        int n = 0;
        for (RiftCatalog.Charm c : RiftCatalog.CHARMS) if (d.charms.contains(c.id()) && !d.gallery.contains(c.id())) { secure(p, c.id(), false); n++; }
        if (n > 0) {
            Msg.success(p, "Secured " + n + " Timecharm" + (n == 1 ? "" : "s") + " in the Rift Gallery! (" + d.gallery.size() + "/" + RiftCatalog.CHARMS.size() + ")");
            Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f);
        }
    }

    /** Secures one owned charm. Returns false when it is not owned or already secured. */
    boolean secure(Player p, String charmId, boolean announce) {
        RiftData d = m.data(p);
        RiftCatalog.Charm c = RiftCatalog.charm(charmId);
        if (d == null || c == null || !d.charms.contains(charmId) || !d.gallery.add(charmId)) return false;
        int motes = m.dials.gallerySecureMotes.get();
        if (motes > 0) m.addMotes(p, motes, "Secured " + c.name());
        m.award(p, "gallery:" + charmId, 5, "Rift Gallery");
        m.stats.invalidate(p);
        if (announce) {
            Msg.success(p, "Secured the " + c.name() + " in the Rift Gallery! (" + d.gallery.size() + "/" + RiftCatalog.CHARMS.size() + ")");
            Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f);
        }
        int idx = RiftCatalog.CHARMS.indexOf(c) + 1;
        ItemDisplay e = items.get(idx);
        if (e != null && e.isValid()) {
            p.showEntity(Sky.plugin(), e);
            p.spawnParticle(Particle.END_ROD, e.getLocation(), 25, 0.2, 0.3, 0.2, 0.03);
        }
        Sky.profiles().saveNow(p);
        return true;
    }

    // =========================================================================================
    // exhibits (every second)
    // =========================================================================================

    void tick() {
        if (!m.dials.galleryExhibits.get()) { removeAll(); return; }
        World w = Sky.worlds().world(RiftCatalog.ISLAND);
        if (w == null) return;
        boolean anyone = false;
        for (Player p : w.getPlayers()) { anyone = true; break; }
        if (!anyone) return;
        ensure(w);
        spin += 40f;
        if (spin >= 360f) spin -= 360f;
        for (ItemDisplay e : items.values()) {
            if (!e.isValid()) continue;
            e.setInterpolationDelay(0);
            e.setInterpolationDuration(20);
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f((float) Math.toRadians(spin), 0, 1, 0),
                    new Vector3f(0.7f, 0.7f, 0.7f), new AxisAngle4f()));
        }
        for (Player p : w.getPlayers()) {
            RiftData d = m.data(p);
            for (Map.Entry<Integer, ItemDisplay> en : items.entrySet()) {
                ItemDisplay e = en.getValue();
                if (!e.isValid()) continue;
                RiftCatalog.Charm c = RiftCatalog.CHARMS.get(en.getKey() - 1);
                boolean show = d != null && d.gallery.contains(c.id());
                if (show && !p.canSee(e)) p.showEntity(Sky.plugin(), e);
                else if (!show && p.canSee(e)) p.hideEntity(Sky.plugin(), e);
            }
        }
    }

    /** Spawns any missing display whose chunk is loaded. */
    private void ensure(World w) {
        Layout l = Sky.worlds().layout(RiftCatalog.ISLAND);
        if (l == null) return;
        for (int i = 1; i <= RiftCatalog.CHARMS.size(); i++) {
            Location at = spot(l, w, i);
            if (at == null || !w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
            RiftCatalog.Charm c = RiftCatalog.CHARMS.get(i - 1);
            ItemDisplay it = items.get(i);
            if (it == null || !it.isValid()) {
                ItemStack look = new ItemStack(LOOK.getOrDefault(c.id(), Material.CLOCK));
                ItemDisplay e = w.spawn(at.clone().add(0, 0.45, 0), ItemDisplay.class, d -> {
                    d.setItemStack(look);
                    d.setVisibleByDefault(false);
                    d.setPersistent(false);
                    d.setGlowing(false);
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.addScoreboardTag(TAG);
                });
                items.put(i, e);
            }
            TextDisplay td = labels.get(i);
            if (td == null || !td.isValid()) {
                String name = "&d" + c.name();
                TextDisplay t = w.spawn(at.clone().add(0, 1.25, 0), TextDisplay.class, d -> {
                    d.text(Text.c(name));
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setPersistent(false);
                    d.addScoreboardTag(TAG);
                });
                labels.put(i, t);
            }
        }
    }

    /** Centre of exhibit {@code i}'s air block, or null when the layout has none (an island built before v2). */
    static Location spot(Layout l, World w, int i) {
        String v = l.data().get("gallery.exhibit." + i);
        if (v == null) return null;
        String[] p = v.split(",");
        if (p.length != 3) return null;
        try {
            return new Location(w, Integer.parseInt(p[0].trim()) + 0.5, Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()) + 0.5);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** How many exhibit spots the built layout has (self-test). */
    static int spots() {
        Layout l = Sky.worlds().layout(RiftCatalog.ISLAND);
        if (l == null) return 0;
        int n = 0;
        for (int i = 1; i <= RiftCatalog.CHARMS.size(); i++) if (l.data().containsKey("gallery.exhibit." + i)) n++;
        return n;
    }

    void removeAll() {
        for (Entity e : items.values()) if (e.isValid()) e.remove();
        for (Entity e : labels.values()) if (e.isValid()) e.remove();
        items.clear();
        labels.clear();
    }

    void shutdown() {
        removeAll();
        World w = Bukkit.getWorlds().isEmpty() ? null : Sky.worlds().world(RiftCatalog.ISLAND);
        if (w != null) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
    }
}
