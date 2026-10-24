package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.npc.NpcDef;
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
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wave 5, the Rift's other hidden things (research: NEU rift_guide.json):
 * <ul>
 *   <li><b>Pharaoh</b> (reference: Montezuma): Weeping Maribel lost her cat; its soul broke into nine pieces
 *       ({@code cat.1..9}). She hands out a Whisker Finder (DEAD_CAT_DETECTOR) that pings the nearest piece.
 *       Every piece grows the cat: +15 Rift Time each (+10 base), +2 Mana Regen each from EPIC (NEU lore of
 *       FRACTURED_MONTEZUMA_SOUL). The pet itself belongs to the pets module (Request); until it registers the
 *       pet, Pharaoh rides along as a Rift companion and the bonus applies directly.</li>
 *   <li><b>The Warden's eyes</b> (reference: Porhtal's eyes): seven, one per district ({@code eye.1..7}); each one
 *       found is a fast-travel point in the Warden's menu, for a little Rift Time.</li>
 *   <li><b>The Enigma Cloak</b> (accessories' item): Riddler Nyx gives it; every 4 souls he upgrades it, +20 Rift
 *       Time per soul it holds, up to 42 (its own lore).</li>
 *   <li>The <b>Timeshift Pistol</b> (TIME_GUN): turns Timite into Youngite (or Obsolite, sneaking) for a minute.</li>
 * </ul>
 */
final class RiftCat {

    static final String PET_ID = "FRACTURED_MONTEZUMA_SOUL";
    static final String DETECTOR = "DEAD_CAT_DETECTOR";
    static final String CLOAK = "ENIGMA_CLOAK";
    static final String MARIBEL = RiftEnigma.npcId("maribel");

    private final RiftModule m;
    /** Timite blocks the pistol aged or rejuvenated: location -> {revert ms}. */
    final Map<Location, Long> shifted = new HashMap<>();

    RiftCat(RiftModule m) { this.m = m; }

    void register() {
        Sky.npcs().register(NpcDef.of(MARIBEL, "&dWeeping Maribel", RiftCatalog.ISLAND, RiftEnigma.npcAnchor("maribel")).title("&9&lLOST CAT"), this::maribel);
        Sky.abilities().register("rift_meownar", this::meownar);
        Sky.abilities().register("rift_time_gun", this::timeGun);
    }

    // =========================================================================================
    // Pharaoh
    // =========================================================================================

    /** Pharaoh's bonus applies: the pets module's pet is summoned, or (no such pet registered) the companion is on. */
    boolean catActive(Player p, RiftData d) {
        if (d == null || d.catPieces.isEmpty()) return false;
        if (Sky.items().exists(PET_ID) && !Sky.items().isAutoVanilla(PET_ID)) {
            try { return PET_ID.equals(Sky.service(Features.Pets.class).activeType(p)); } catch (RuntimeException e) { return false; }
        }
        return d.catActive;
    }

    private void maribel(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        if (d.catStage == 0) {
            d.catStage = 1;
            Sky.npcs().talk(p, MARIBEL, List.of(
                    "My Pharaoh... he wandered into the Rift and his little soul broke apart.",
                    "Nine pieces, scattered everywhere. Please, find them for me.",
                    "Take this Whisker Finder. It hums when a piece is near."), () -> {
                giveDetector(p);
                menu(p);
            });
            m.award(p, "quest:maribel", 3, "Weeping Maribel");
            return;
        }
        menu(p);
    }

    private void giveDetector(Player p) {
        if (!Sky.items().exists(DETECTOR) || Sky.items().count(p, DETECTOR) > 0) return;
        Sky.loot().give(p, DETECTOR, 1, LootSource.REWARD);
    }

    void menu(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        int n = d.catPieces.size();
        Menu menu = new Menu("Weeping Maribel", 4).chrome();
        String[] rar = {"&fCOMMON", "&aUNCOMMON", "&9RARE", "&5EPIC", "&6LEGENDARY"};
        List<String> lore = new ArrayList<>(List.of("&8Fractured Soul Pet", "", "&7Found: &9" + n + "/" + RiftCatalog.CAT_PIECES + " Soul Pieces", "",
                "&7Rift Time: &a+" + (int) RiftEnigma.catRiftTime(n) + "s",
                "&7Mana Regen: &a+" + (int) RiftEnigma.catManaRegen(n) + "%", "",
                "&6Nine Lives", "&7Gain &a+15ф Rift Time &7per soul piece.", "",
                "&6Trickery &8(EPIC)", "&7Gain &b+2 Mana Regen &7per soul piece.", "", (n == 0 ? "&8" : rar[RiftEnigma.catRarity(n)]) + " PET"));
        menu.set(13, Icons.glow(Icons.of(Material.PLAYER_HEAD, "&9Pharaoh", lore)));
        int slot = 18;
        for (RiftEnigma.Hidden h : RiftEnigma.CAT_PIECES) {
            boolean got = d.catPieces.contains(h.n());
            menu.set(slot + (h.n() - 1) % 9, Icons.of(got ? Material.BONE : Material.GRAY_DYE, (got ? "&9" : "&7") + h.name(),
                    got ? "&aFound!" : "&7" + h.hint()));
        }
        boolean petExists = Sky.items().exists(PET_ID) && !Sky.items().isAutoVanilla(PET_ID);
        if (petExists) {
            boolean given = d.flags.contains("cat_pet_given");
            menu.set(11, Icons.of(Material.LEAD, "&9Adopt Pharaoh", given ? List.of("&aHe is in your pet menu.") :
                    n == 0 ? List.of("&cFind at least one piece first.") : List.of("&7Pharaoh joins your pets.", "", "&eClick to adopt!")), c -> {
                RiftData dd = m.data(c.player());
                if (dd == null || dd.catPieces.isEmpty() || !dd.flags.add("cat_pet_given")) return;
                Sky.loot().give(c.player(), PET_ID, 1, LootSource.REWARD);
                c.player().closeInventory();
            });
        } else {
            menu.set(11, Icons.of(d.catActive ? Material.LIME_DYE : Material.GRAY_DYE, "&9Pharaoh rides along: " + (d.catActive ? "&aON" : "&cOFF"),
                    "&7While on, Pharaoh's bonus counts", "&7inside the Rift.", "", "&eClick to toggle!"), c -> {
                RiftData dd = m.data(c.player());
                if (dd == null) return;
                dd.catActive = !dd.catActive;
                m.stats.invalidate(c.player());
                menu(c.player());
            });
        }
        menu.set(15, Icons.of(Material.POPPY, "&9Whisker Finder", "&7Lost yours? Here is another.", "", "&eClick to take one!"), c -> {
            giveDetector(c.player());
            c.player().closeInventory();
        });
        menu.open(p);
    }

    /** Whisker Finder: ping the nearest piece you have not found. */
    private boolean meownar(AbilityService.Use use) {
        Player p = use.player();
        RiftSession s = m.session(p);
        RiftData d = m.data(p);
        if (s == null || d == null) { Msg.error(p, "It only hums inside the Rift."); return false; }
        if (d.catStage == 0) d.catStage = 1;
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (int i = 1; i <= RiftCatalog.CAT_PIECES; i++) {
            if (d.catPieces.contains(i)) continue;
            Location l = anchor("cat." + i);
            if (l == null || l.getWorld() != p.getWorld()) continue;
            double dd = l.distanceSquared(p.getLocation());
            if (dd < bd) { bd = dd; best = l; }
        }
        if (best == null) { Msg.send(p, "&9Meow? &7Every piece of Pharaoh is already found."); return true; }
        Vector dir = best.toVector().subtract(p.getLocation().toVector());
        double dist = dir.length();
        String way = compass(dir.getX(), dir.getZ());
        String vert = dir.getY() > 4 ? " &7(above you)" : dir.getY() < -4 ? " &7(below you)" : "";
        Msg.send(p, "&9Meow! &7The nearest piece is &a" + (int) dist + " blocks " + way + vert + "&7.");
        Vector step = dir.clone().normalize();
        for (double t = 1; t < Math.min(10, dist); t += 0.5)
            p.spawnParticle(Particle.DUST, p.getLocation().add(0, 1.2, 0).add(step.clone().multiply(t)), 1, 0, 0, 0, 0,
                    new Particle.DustOptions(org.bukkit.Color.BLUE, 1.0f));
        s.catGlowUntil = System.currentTimeMillis() + 10_000;
        Msg.sound(p, Sound.ENTITY_CAT_AMBIENT, 1.2f);
        return true;
    }

    static String compass(double dx, double dz) {
        double a = Math.toDegrees(Math.atan2(dx, -dz));           // 0 = north, clockwise
        String[] n = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return n[(int) Math.floorMod(Math.round(a / 45.0), 8L)];
    }

    // =========================================================================================
    // every 5 ticks: pieces and eyes
    // =========================================================================================

    void tick() {
        if (m.sessions.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            RiftSession s = m.session(p);
            RiftData d = s == null ? null : m.data(p);
            if (d == null) continue;
            Location pl = p.getLocation();
            boolean glow = now < s.catGlowUntil;
            for (int i = 1; i <= RiftCatalog.CAT_PIECES; i++) {
                if (d.catPieces.contains(i)) continue;
                Location l = anchor("cat." + i);
                if (l == null || l.getWorld() != p.getWorld()) continue;
                double d2 = pl.distanceSquared(l);
                if (d2 < 3.2 && d.catStage >= 1) { findPiece(p, d, i); continue; }
                if ((glow && d2 < 48 * 48) || d2 < 16) p.spawnParticle(Particle.DUST, l.clone().add(0, 0.6, 0), 4, 0.2, 0.2, 0.2, 0,
                        new Particle.DustOptions(org.bukkit.Color.fromRGB(80, 110, 255), 1.2f));
            }
            for (int i = 1; i <= RiftCatalog.EYES; i++) {
                if (d.eyes.contains(i)) continue;
                Location l = anchor("eye." + i);
                if (l == null || l.getWorld() != p.getWorld()) continue;
                double d2 = pl.distanceSquared(l);
                if (d2 < 4) { findEye(p, d, i); continue; }
                if (d2 < 12 * 12) p.spawnParticle(Particle.PORTAL, l.clone().add(0, 1.0, 0), 10, 0.2, 0.4, 0.2, 0.2);
            }
        }
        // the pistol's shifted Timite comes back
        shifted.entrySet().removeIf(e -> {
            if (e.getValue() > now) return false;
            Block b = e.getKey().getBlock();
            if (b.getType() == Material.LIME_STAINED_GLASS || b.getType() == Material.GRAY_STAINED_GLASS) b.setType(Material.PURPLE_STAINED_GLASS, false);
            return true;
        });
    }

    private void findPiece(Player p, RiftData d, int i) {
        if (!d.catPieces.add(i)) return;
        int n = d.catPieces.size();
        RiftEnigma.Hidden h = RiftEnigma.CAT_PIECES.get(i - 1);
        RiftRiddles.title(p, "&9&lSOUL PIECE!", "&7" + h.name() + " &8(" + n + "/" + RiftCatalog.CAT_PIECES + ")");
        Msg.send(p, "&9You found a piece of Pharaoh's soul! &7(" + n + "/" + RiftCatalog.CAT_PIECES + ") &8Rift Time from Pharaoh: &a+" + (int) RiftEnigma.catRiftTime(n) + "s");
        Msg.sound(p, Sound.ENTITY_CAT_PURREOW, 1.2f);
        m.award(p, "cat:" + i, 5, "Pharaoh's soul piece");
        m.stats.invalidate(p);
        if (n >= RiftCatalog.CAT_PIECES) Msg.box(p, "&9&lPHARAOH IS WHOLE", List.of("&7All nine pieces found. Weeping Maribel will", "&7be overjoyed. Pharaoh is now &6LEGENDARY&7."));
        Sky.profiles().saveNow(p);
    }

    private void findEye(Player p, RiftData d, int i) {
        if (!d.eyes.add(i)) return;
        RiftEnigma.Hidden h = RiftEnigma.EYES.get(i - 1);
        RiftRiddles.title(p, "&5&lEYE FOUND!", "&d" + h.name());
        Msg.send(p, "&5You found &d" + h.name() + "&5! &7(" + d.eyes.size() + "/" + RiftCatalog.EYES + ") The Warden can now send you here: &e/rift eyes");
        Msg.sound(p, Sound.ENTITY_ENDER_EYE_DEATH, 1.0f);
        m.award(p, "eye:" + i, 3, "Warden's eye");
        Sky.profiles().saveNow(p);
    }

    private Location anchor(String name) { return Sky.worlds().anchor(RiftCatalog.ISLAND, name); }

    // =========================================================================================
    // eye travel
    // =========================================================================================

    void eyesMenu(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        Menu menu = new Menu("The Warden's Eyes", 4).chrome();
        int cost = m.dials.eyeTravelSeconds.get();
        for (RiftEnigma.Hidden h : RiftEnigma.EYES) {
            boolean got = d.eyes.contains(h.n());
            RiftCatalog.Area a = RiftCatalog.area(h.area());
            String area = a == null ? h.area() : "&" + a.color() + a.name();
            List<String> lore = new ArrayList<>(List.of(area, ""));
            if (got) { lore.add("&7Cost: &a" + cost + "sф &7of Rift Time"); lore.add(""); lore.add("&eClick to travel!"); }
            else { lore.add("&7" + h.hint()); lore.add(""); lore.add("&cNot found yet."); }
            menu.set(10 + h.n() - 1 + (h.n() > 7 ? 2 : 0), Icons.of(got ? Material.ENDER_EYE : Material.GRAY_DYE, (got ? "&d" : "&7") + h.name(), lore),
                    got ? c -> travel(c.player(), h) : null);
        }
        menu.set(4, Icons.of(Material.ENDER_EYE, "&5The Warden's Eyes", "&7Found: &d" + d.eyes.size() + "&7/" + RiftCatalog.EYES, "",
                "&7Each eye you find is a place the", "&7Warden can send you, inside the Rift."));
        menu.open(p);
    }

    private void travel(Player p, RiftEnigma.Hidden h) {
        RiftSession s = m.session(p);
        RiftData d = m.data(p);
        if (s == null || d == null || !d.eyes.contains(h.n())) { Msg.error(p, "You can only travel by eye inside the Rift."); return; }
        Location to = anchor("eye." + h.n());
        if (to == null) return;
        RiftCatalog.Area a = RiftCatalog.area(h.area());
        if (a != null && a.gated() && m.dials.gatesEnabled.get() && !s.gates.contains(a.id())
                && !Sky.requirements().checkOrTell(p, a.requirementKey())) return;
        int cost = m.dials.eyeTravelSeconds.get();
        if (s.remaining <= cost + m.dials.warnAt.get()) { Msg.error(p, "You do not have the Rift Time to spare."); return; }
        m.travel.addTime(p, -cost);
        p.closeInventory();
        to.setYaw(p.getLocation().getYaw());
        p.teleport(to);
        p.setFallDistance(0);
        Msg.sound(p, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f);
        Sky.vitals().flash(p, "&5Through " + h.name() + " &7(-" + cost + "sф)", 40);
    }

    // =========================================================================================
    // the Enigma Cloak
    // =========================================================================================

    /** Riddler Nyx: give the cloak once, then upgrade it every 4 souls. Returns true when he said something. */
    boolean cloakVisit(Player p) {
        RiftData d = m.data(p);
        if (d == null || !Sky.items().exists(CLOAK) || Sky.items().isAutoVanilla(CLOAK)) return false;
        if (d.flags.add("cloak_given")) {
            Sky.loot().give(p, CLOAK, 1, LootSource.REWARD);
            Sky.npcs().talk(p, "rift_riddler", List.of("Wear this cloak. Every soul you free, it remembers.",
                    "Come back to me every " + RiftEnigma.CLOAK_STEP + " souls and I will stitch them in."), () -> m.menus.souls(p));
            return true;
        }
        int can = RiftEnigma.cloakClaimable(d.souls.size());
        if (can > d.cloakSouls) {
            int added = can - d.cloakSouls;
            d.cloakSouls = can;
            m.stats.invalidate(p);
            Sky.npcs().talk(p, "rift_riddler", List.of("Ah, " + added + " more souls. Hold still...",
                    "There. Your cloak holds " + can + " souls: &a+" + (int) (can * RiftEnigma.CLOAK_SECONDS) + "s &fof Rift Time while you wear it."), () -> m.menus.souls(p));
            return true;
        }
        return false;
    }

    static boolean wears(Player p, String id) {
        try {
            for (ItemStack it : Sky.service(Features.Equipment.class).worn(p)) if (it != null && id.equals(Sky.items().idOf(it))) return true;
        } catch (RuntimeException ignored) { }
        return false;
    }

    // =========================================================================================
    // the Timeshift Pistol
    // =========================================================================================

    private boolean timeGun(AbilityService.Use use) {
        Player p = use.player();
        if (m.session(p) == null) { Msg.error(p, "Time only bends inside the Rift."); return false; }
        RayTraceResult hit = p.getWorld().rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), 24);
        Block b = hit == null ? null : hit.getHitBlock();
        if (b == null || b.getType() != Material.PURPLE_STAINED_GLASS) { Sky.vitals().flash(p, "&7Aim at &9Timite&7.", 20); return false; }
        boolean older = p.isSneaking();
        b.setType(older ? Material.GRAY_STAINED_GLASS : Material.LIME_STAINED_GLASS, false);
        shifted.put(b.getLocation(), System.currentTimeMillis() + 60_000L);
        p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, b.getLocation().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, 0.05);
        Msg.sound(p, Sound.BLOCK_BEACON_POWER_SELECT, older ? 0.6f : 1.8f);
        Sky.vitals().flash(p, older ? "&8The Timite ages into Obsolite." : "&aThe Timite grows young: Youngite.", 30);
        return true;
    }

    /** RiftWorldState asks: is this glass shifted Timite, and what does it give? */
    String shiftedItem(Block b) {
        if (!shifted.containsKey(b.getLocation())) return null;
        return b.getType() == Material.LIME_STAINED_GLASS ? "YOUNGITE" : b.getType() == Material.GRAY_STAINED_GLASS ? "OBSOLITE" : null;
    }

    static BlockData timite() { return Material.PURPLE_STAINED_GLASS.createBlockData(); }

    void restoreAll() {
        for (Location l : shifted.keySet()) {
            Block b = l.getBlock();
            if (b.getType() == Material.LIME_STAINED_GLASS || b.getType() == Material.GRAY_STAINED_GLASS || b.getType().isAir()) b.setType(Material.PURPLE_STAINED_GLASS, false);
        }
        shifted.clear();
    }

    /** Self-test: every piece and eye anchor exists. */
    String check() {
        Layout l = Sky.worlds().layout(RiftCatalog.ISLAND);
        World w = Sky.worlds().world(RiftCatalog.ISLAND);
        if (l == null || w == null) return "rift layout not loaded";
        List<String> miss = new ArrayList<>();
        for (int i = 1; i <= RiftCatalog.CAT_PIECES; i++) if (l.anchor("cat." + i) == null) miss.add("cat." + i);
        for (int i = 1; i <= RiftCatalog.EYES; i++) if (l.anchor("eye." + i) == null) miss.add("eye." + i);
        if (l.anchor(RiftEnigma.npcAnchor("maribel")) == null) miss.add("maribel");
        return miss.isEmpty() ? null : "missing " + miss;
    }
}
