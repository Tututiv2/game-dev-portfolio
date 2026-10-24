package com.skyclone.mod.progression;

import com.skyclone.api.Sky;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fairy Souls. Island builders place anchors named {@code fairy_soul.*}; this class finds them in
 * every island's layout, draws a floating, spinning fairy head + sparkles at each one, lets a player
 * collect it by LEFT- or RIGHT-CLICKING it (once per profile member; like the reference there is no
 * walk-over pickup), and runs the fairy NPC's 5-soul exchange.
 *
 * <p>Each soul is two non-persistent entities: an {@link ItemDisplay} wearing the fairy skull (the
 * look: spin + bob, full-bright) and an {@link Interaction} box around it (the click target, because a
 * display entity cannot be hit). Both carry {@link #SOUL_KEY} = the soul id. Shared islands get one pair
 * per soul; instanced islands (private island, garden) get one pair per soul per profile instance,
 * spawned while someone is on that instance. Once a player has a soul, both entities are hidden for
 * that player only ({@code hideEntity}), so the click box also stops being clickable for them.
 */
final class FairySouls implements Listener {

    static final String NPC_ID = "progression_fairy";
    static final String NPC_NAME = "&dWren the Fairy";
    static final NamespacedKey SOUL_KEY = NamespacedKey.fromString("skyclone:fairy_soul");
    /** Height of the head's centre above the anchor (the anchor is the top of the block it sits on). */
    static final double HOVER = 0.65;
    static final float HEAD_SCALE = 1.0f;

    record Soul(String id, String island, String anchor, double x, double y, double z, boolean instanced) {}

    /** One drawn soul: the look and the click box. */
    record Head(String soulId, UUID display, UUID box) {}

    private volatile Map<String, List<Soul>> byIsland = Map.of();
    private volatile Map<String, Soul> byId = Map.of();
    /** head key (soul id, or soul id + "@" + profile id on instanced islands) -> its entities. */
    private final Map<String, Head> displays = new HashMap<>();
    /** player -> last click handled (ms), so one click that fires several events counts once. */
    private final Map<UUID, Long> lastClick = new HashMap<>();
    /** per-player cache of found soul ids (rebuilt on ready). */
    private final Map<UUID, Set<String>> foundCache = new HashMap<>();
    private float spin;

    int total() { return byId.size(); }

    Map<String, List<Soul>> byIsland() { return byIsland; }

    // ---- registry --------------------------------------------------------------------------------------

    /** Re-read every island layout for fairy_soul anchors. Cheap; run on boot, on IslandBuilt and every minute. */
    void rescan() {
        Set<String> excluded = new HashSet<>();
        for (String s : Prog.fairyExcluded.get().split(",")) if (!s.isBlank()) excluded.add(s.trim().toLowerCase(Locale.ROOT));
        Map<String, List<Soul>> isl = new LinkedHashMap<>();
        Map<String, Soul> ids = new LinkedHashMap<>();
        for (IslandDef def : Sky.worlds().islands()) {
            if (def == null || def.id == null || excluded.contains(def.id)) continue;
            Layout l;
            try { l = Sky.worlds().layout(def.id); } catch (Throwable t) { l = null; }
            if (l == null) continue;
            List<Soul> list = new ArrayList<>();
            for (Layout.Anchor a : l.anchors().values()) {
                if (!a.name().startsWith("fairy_soul")) continue;
                Soul s = new Soul(def.id + ":" + a.name(), def.id, a.name(), a.x(), a.y(), a.z(), def.instanced);
                list.add(s);
                ids.put(s.id(), s);
            }
            if (!list.isEmpty()) isl.put(def.id, List.copyOf(list));
        }
        if (!ids.keySet().equals(byId.keySet())) {
            byIsland = isl;
            byId = ids;
            // drop heads of souls that no longer exist
            displays.entrySet().removeIf(e -> {
                if (ids.containsKey(e.getValue().soulId())) return false;
                removeHead(e.getValue());
                return true;
            });
        }
    }

    Soul soul(String id) { return byId.get(id); }

    // ---- per-player --------------------------------------------------------------------------------------

    Set<String> found(Player p) {
        return foundCache.computeIfAbsent(p.getUniqueId(), u -> new HashSet<>(Prog.fairy(p).found));
    }

    void onReady(Player p) {
        foundCache.put(p.getUniqueId(), new HashSet<>(Prog.fairy(p).found));
        applyVisibility(p);
    }

    void onQuit(Player p) {
        foundCache.remove(p.getUniqueId());
        lastClick.remove(p.getUniqueId());
    }

    /** Hide the heads (look + click box) of souls this player already has; show the rest. */
    void applyVisibility(Player p) {
        if (!Prog.ready(p)) return;
        Set<String> f = found(p);
        for (Head h : displays.values()) {
            boolean hide = f.contains(h.soulId());
            for (UUID id : new UUID[]{h.display(), h.box()}) {
                Entity ent = id == null ? null : Bukkit.getEntity(id);
                if (ent == null || ent.getWorld() != p.getWorld()) continue;
                if (hide) p.hideEntity(Sky.plugin(), ent); else p.showEntity(Sky.plugin(), ent);
            }
        }
    }

    /** World position of a soul FOR this player (instanced islands are per profile). Null when unknown. */
    Location position(Soul s, Player p) {
        if (s.instanced()) {
            Location l;
            try { l = Sky.worlds().instanceAnchor(s.island(), Sky.profiles().profileId(p), s.anchor()); } catch (Throwable t) { l = null; }
            return l;
        }
        World w = Bukkit.getWorld("sb_" + s.island());
        return w == null ? null : new Location(w, s.x(), s.y(), s.z());
    }

    /** Runs every 5 ticks: sparkles around every soul this player has not found. No pickup here: souls are clicked. */
    void tick(boolean particles) {
        if (!particles) return;
        int range = Prog.fairyParticleRange.get();
        double range2 = (double) range * range;
        Particle.DustOptions pink = new Particle.DustOptions(Color.fromRGB(255, 120, 220), 1.0f);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Prog.ready(p)) continue;
            String island = Sky.worlds().islandOf(p.getWorld());
            if (island == null) continue;
            List<Soul> souls = byIsland.get(island);
            if (souls == null) continue;
            Set<String> f = found(p);
            Location pl = p.getLocation();
            for (Soul s : souls) {
                if (f.contains(s.id())) continue;
                Location at = s.instanced() ? position(s, p) : new Location(pl.getWorld(), s.x(), s.y(), s.z());
                if (at == null || at.getWorld() != pl.getWorld()) continue;
                double dx = at.getX() - pl.getX(), dy = at.getY() - pl.getY(), dz = at.getZ() - pl.getZ();
                if (dx * dx + dy * dy + dz * dz > range2) continue;
                double cy = at.getY() + HOVER;
                p.spawnParticle(Particle.DUST, at.getX(), cy, at.getZ(), 2, 0.3, 0.3, 0.3, 0, pink);
                p.spawnParticle(Particle.END_ROD, at.getX(), cy, at.getZ(), 1, 0.25, 0.3, 0.25, 0.005);
                if (!Prog.fairyDisplays.get()) {
                    p.spawnParticle(Particle.WITCH, at.getX(), cy, at.getZ(), 2, 0.15, 0.15, 0.15, 0);
                }
            }
        }
    }

    // ---- clicking -------------------------------------------------------------------------------------------

    /** Soul id on a fairy entity, or null. */
    static String soulIdOf(Entity e) {
        if (e == null || SOUL_KEY == null) return null;
        return e.getPersistentDataContainer().get(SOUL_KEY, PersistentDataType.STRING);
    }

    /** Right click (the box answers both interact events; one click is handled once). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEntityEvent e) {
        String id = soulIdOf(e.getRightClicked());
        if (id == null) return;
        e.setCancelled(true);
        clicked(e.getPlayer(), id, e.getRightClicked());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        String id = soulIdOf(e.getRightClicked());
        if (id == null) return;
        e.setCancelled(true);
        clicked(e.getPlayer(), id, e.getRightClicked());
    }

    /** Left click, Paper's early hook (fires before any damage logic). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        String id = soulIdOf(e.getAttacked());
        if (id == null) return;
        e.setCancelled(true);
        clicked(e.getPlayer(), id, e.getAttacked());
    }

    /** Left click, the Bukkit damage path (an Interaction fires it on attack); also the fallback if the early hook is skipped. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDamage(EntityDamageByEntityEvent e) {
        String id = soulIdOf(e.getEntity());
        if (id == null) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p) clicked(p, id, e.getEntity());
    }

    /** Nothing ever hurts a fairy entity (explosions, arrows, lava...). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onAnyDamage(EntityDamageEvent e) {
        if (soulIdOf(e.getEntity()) != null) e.setCancelled(true);
    }

    void clicked(Player p, String soulId, Entity ent) {
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < 250) return;
        lastClick.put(p.getUniqueId(), now);
        if (!Prog.ready(p)) { Msg.error(p, "Your profile is still loading."); return; }
        Soul s = byId.get(soulId);
        if (s == null) return;
        Location at = ent == null ? null : ent.getLocation();
        claim(p, s, at);
    }

    /** Collect a soul for this player (from a click). Says so when it was already found. */
    void claim(Player p, Soul s, Location at) {
        PData.Fairy d = Prog.fairy(p);
        if (d.found.contains(s.id())) {
            found(p).add(s.id());
            Msg.send(p, "&cYou have already found this Fairy Soul!");
            Msg.sound(p, Sound.ENTITY_VILLAGER_NO, 1.0f);
            hideFor(p, s.id());
            return;
        }
        d.found.add(s.id());
        found(p).add(s.id());
        int n = d.found.size();
        Msg.send(p, "&d&lFAIRY SOUL! &7You have found a &dFairy Soul&7! &d(" + n + "/" + Math.max(n, total()) + ")");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.8f);
        Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4f);
        if (at != null) {
            p.spawnParticle(Particle.HAPPY_VILLAGER, at.getX(), at.getY() + 0.3, at.getZ(), 16, 0.4, 0.4, 0.4, 0);
            p.spawnParticle(Particle.END_ROD, at.getX(), at.getY() + 0.3, at.getZ(), 12, 0.2, 0.3, 0.2, 0.06);
        }
        hideFor(p, s.id());
        if (PMath.fairyExchangesAvailable(n, d.exchanged) > PMath.fairyExchangesAvailable(n - 1, d.exchanged)) {
            Msg.send(p, "&7You have &d5 &7unexchanged souls. Bring them to " + NPC_NAME + " &7in the Hub!");
        }
        Sky.profiles().saveNow(p);
    }

    /** Nearest soul on the player's current island this player has not found, with its position; null when none. */
    Map.Entry<Soul, Location> nearestUnfound(Player p) {
        String island = Sky.worlds().islandOf(p.getWorld());
        List<Soul> souls = island == null ? null : byIsland.get(island);
        if (souls == null || !Prog.ready(p)) return null;
        Set<String> f = found(p);
        Map.Entry<Soul, Location> best = null;
        double bestD = Double.MAX_VALUE;
        for (Soul s : souls) {
            if (f.contains(s.id())) continue;
            Location at = position(s, p);
            if (at == null || at.getWorld() != p.getWorld()) continue;
            double d = at.distanceSquared(p.getLocation());
            if (d < bestD) { bestD = d; best = Map.entry(s, at); }
        }
        return best;
    }

    /** Hide every drawn copy of this soul from this player. */
    private void hideFor(Player p, String soulId) {
        for (Head h : displays.values()) {
            if (!h.soulId().equals(soulId)) continue;
            for (UUID id : new UUID[]{h.display(), h.box()}) {
                Entity ent = id == null ? null : Bukkit.getEntity(id);
                if (ent != null) p.hideEntity(Sky.plugin(), ent);
            }
        }
    }

    // ---- floating heads ------------------------------------------------------------------------------------

    /**
     * Runs every 40 ticks: (re)spawn a head for every shared soul whose chunk is loaded, and for every
     * instanced soul on each profile instance someone is standing on. Heads whose entities are gone are dropped.
     */
    void watchdog() {
        if (!Prog.fairyDisplays.get()) { removeDisplays(); return; }
        // drop entries whose entities died (chunk unload discards non-persistent entities)
        displays.entrySet().removeIf(e -> {
            Entity d = Bukkit.getEntity(e.getValue().display());
            Entity b = Bukkit.getEntity(e.getValue().box());
            boolean ok = d != null && d.isValid() && b != null && b.isValid();
            if (!ok) removeHead(e.getValue());
            return !ok;
        });
        for (Soul s : byId.values()) {
            if (s.instanced()) continue;
            World w = Bukkit.getWorld("sb_" + s.island());
            if (w == null) continue;
            ensure(s.id(), s, new Location(w, s.x(), s.y(), s.z()));
        }
        // instanced islands: one head per soul per profile instance with a player on it
        Set<String> doneProfiles = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Prog.ready(p)) continue;
            String island = Sky.worlds().islandOf(p.getWorld());
            if (island == null) continue;
            List<Soul> souls = byIsland.get(island);
            if (souls == null || souls.isEmpty() || !souls.get(0).instanced()) continue;
            String prof;
            try { prof = Sky.profiles().profileId(p); } catch (Throwable t) { prof = null; }
            if (prof == null || !doneProfiles.add(island + "|" + prof)) continue;
            for (Soul s : souls) {
                Location at = position(s, p);
                if (at == null || at.getWorld() != p.getWorld()) continue;
                ensure(s.id() + "@" + prof, s, at);
            }
        }
    }

    /** Spawn the look + click box for one head key when missing and its chunk is loaded. */
    private void ensure(String key, Soul s, Location at) {
        if (displays.containsKey(key)) return;
        World w = at.getWorld();
        if (w == null || !w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        // Clear any head of this soul still standing in the chunk first. A chunk can stay loaded while its entities
        // are not accessible (no player near), and Bukkit.getEntity then answers null: the watchdog forgot the head
        // and stacked a new one on the old (wave-4 loops playtest: six heads + six click boxes on one hub soul).
        for (Entity old : at.getChunk().getEntities()) {
            if (s.id().equals(old.getPersistentDataContainer().get(SOUL_KEY, PersistentDataType.STRING))) old.remove();
        }
        double cy = at.getY() + HOVER;
        ItemDisplay disp = w.spawn(new Location(w, at.getX(), cy, at.getZ()), ItemDisplay.class, dd -> {
            dd.setPersistent(false);
            dd.setItemStack(head());
            dd.setBillboard(Display.Billboard.FIXED);
            dd.setBrightness(new Display.Brightness(15, 15));
            dd.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(HEAD_SCALE, HEAD_SCALE, HEAD_SCALE), new Quaternionf()));
            dd.getPersistentDataContainer().set(SOUL_KEY, PersistentDataType.STRING, s.id());
        });
        float size = (float) Math.max(0.3, Prog.fairyHitbox.get());
        Interaction box = w.spawn(new Location(w, at.getX(), cy - size / 2.0, at.getZ()), Interaction.class, ii -> {
            ii.setPersistent(false);
            ii.setInteractionWidth(size);
            ii.setInteractionHeight(size);
            ii.setResponsive(true);
            ii.getPersistentDataContainer().set(SOUL_KEY, PersistentDataType.STRING, s.id());
        });
        displays.put(key, new Head(s.id(), disp.getUniqueId(), box.getUniqueId()));
        for (Player p : w.getPlayers()) {
            if (Prog.ready(p) && found(p).contains(s.id())) {
                p.hideEntity(Sky.plugin(), disp);
                p.hideEntity(Sky.plugin(), box);
            }
        }
    }

    /** Runs every 10 ticks: turn every head a little and bob it (the client interpolates). */
    void spin() {
        spin += (float) (Math.PI / 10);
        if (spin > Math.PI * 2) spin -= (float) (Math.PI * 2);
        Quaternionf rot = new Quaternionf(new AxisAngle4f(spin, 0, 1, 0));
        float bob = (float) (Math.sin(spin) * 0.07);
        Vector3f scale = new Vector3f(HEAD_SCALE, HEAD_SCALE, HEAD_SCALE);
        for (Head h : displays.values()) {
            Entity e = Bukkit.getEntity(h.display());
            if (!(e instanceof ItemDisplay d) || !d.isValid()) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(10);
            d.setTransformation(new Transformation(new Vector3f(0, bob, 0), rot, scale, new Quaternionf()));
        }
    }

    private static void removeHead(Head h) {
        for (UUID id : new UUID[]{h.display(), h.box()}) {
            Entity e = id == null ? null : Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
    }

    void removeDisplays() {
        for (Head h : displays.values()) removeHead(h);
        displays.clear();
    }

    int displayCount() { return displays.size(); }

    private ItemStack head() {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        if (!Prog.fairyTexture.isEmpty()) {
            SkullMeta meta = (SkullMeta) it.getItemMeta();
            Icons.applyTexture(meta, Prog.fairyTexture);
            it.setItemMeta(meta);
        }
        return it;
    }

    // ---- stats & requirement ---------------------------------------------------------------------------------

    void contribute(Player p, StatMap into) {
        if (!Prog.ready(p)) return;
        int ex = Prog.fairy(p).exchanged;
        if (ex <= 0) return;
        double[] s = stats(ex);
        into.add(Stat.HEALTH, s[0]);
        into.add(Stat.DEFENSE, s[1]);
        into.add(Stat.STRENGTH, s[2]);
        into.add(Stat.SPEED, s[3]);
    }

    double[] stats(int exchanges) {
        return PMath.fairyStats(exchanges, Prog.fairyHealth.get(), Prog.fairyDefense.get(),
                Prog.fairyStrengthEvery.get(), Prog.fairySpeedEvery.get());
    }

    Requirements.Resolver resolver() {
        return new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 2 || !Prog.ready(p)) return false;
                try { return Prog.fairy(p).found.size() >= Integer.parseInt(parts[1].trim()); } catch (NumberFormatException e) { return false; }
            }
            @Override public String describe(String[] parts) { return "&d" + (parts.length > 1 ? parts[1] : "?") + " Fairy Souls"; }
        };
    }

    // ---- NPC + exchange ---------------------------------------------------------------------------------------

    void registerNpc() {
        NpcDef def = NpcDef.of(NPC_ID, NPC_NAME, "hub", "npc.fairy").title("&d&lFAIRY").entity("ALLAY");
        def.lines.add("&7Fairy Soul exchange");
        Sky.npcs().register(def, p -> {
            if (!Prog.ready(p)) return;
            Prog.objectives.npcTalked(p, "npc.fairy");
            PData.Fairy d = Prog.fairy(p);
            if (d.found.isEmpty() && d.exchanged == 0) {
                Sky.npcs().talk(p, NPC_ID, List.of(
                        "Oh! A visitor who can see me!",
                        "My sisters' souls are scattered across every island.",
                        "Bring me five of them and I will make you stronger."), () -> openMenu(p, true));
            } else {
                openMenu(p, true);
            }
        });
    }

    void openMenu(Player p, boolean atFairy) {
        if (!Prog.ready(p)) { Msg.error(p, "Your profile is still loading."); return; }
        buildMenu(p, Prog.fairy(p), atFairy).open(p);
    }

    ItemStack summaryIcon(Player p) {
        PData.Fairy d = Prog.fairy(p);
        List<String> lore = new ArrayList<>();
        lore.add("&7Found: &d" + d.found.size() + "&7/&d" + total());
        lore.add("&7Exchanged: &d" + d.exchanged * 5);
        lore.add("");
        lore.add("&eClick to view!");
        return Prog.fairyTexture.isEmpty() ? Icons.of(Material.PINK_DYE, "&dFairy Souls", lore)
                : Icons.skull(Prog.fairyTexture, "&dFairy Souls", lore);
    }

    Menu buildMenu(Player viewer, PData.Fairy d, boolean atFairy) {
        Menu m = new Menu("Fairy", 6).chrome();
        int found = d.found.size();
        int avail = PMath.fairyExchangesAvailable(found, d.exchanged);
        List<String> head = new ArrayList<>();
        head.add("&7Fairy Souls are hidden on every");
        head.add("&7island. Click one to collect it.");
        head.add("");
        head.add("&7Found: &d" + found + "&7/&d" + total());
        head.add(Prog.bar(found, Math.max(1, total())));
        m.set(4, Prog.fairyTexture.isEmpty() ? Icons.of(Material.PINK_DYE, "&dFairy Souls", head)
                : Icons.skull(Prog.fairyTexture, "&dFairy Souls", head));

        double[] now = stats(d.exchanged);
        double[] next = stats(d.exchanged + 1);
        List<String> ex = new ArrayList<>();
        ex.add("&7Trade &d5 Fairy Souls &7for permanent");
        ex.add("&7stats and SkyBlock XP.");
        ex.add("");
        ex.add("&a&lNEXT EXCHANGE");
        if (next[0] > now[0]) ex.add(" &8+&c" + Text.commas(next[0] - now[0]) + " " + Stat.HEALTH.label());
        if (next[1] > now[1]) ex.add(" &8+&a" + Text.commas(next[1] - now[1]) + " " + Stat.DEFENSE.label());
        if (next[2] > now[2]) ex.add(" &8+&c" + Text.commas(next[2] - now[2]) + " " + Stat.STRENGTH.label());
        if (next[3] > now[3]) ex.add(" &8+&f" + Text.commas(next[3] - now[3]) + " " + Stat.SPEED.label());
        ex.add(" &8+&b" + Prog.levelsFile.fairyExchangeXp + " SkyBlock XP");
        ex.add("");
        ex.add("&7Souls ready to exchange: &d" + Math.max(0, found - d.exchanged * 5));
        ex.add("");
        boolean canHere = atFairy || Prog.fairyAnywhere.get();
        if (avail <= 0) ex.add("&cYou need 5 unexchanged souls!");
        else if (!canHere) ex.add("&cTalk to " + NPC_NAME + " &cin the Hub!");
        else ex.add("&eClick to exchange!");
        m.set(22, Icons.of(avail > 0 && canHere ? Material.NETHER_STAR : Material.GRAY_DYE, "&dExchange Fairy Souls", ex), c -> {
            Player p = c.player();
            if (!Prog.ready(p)) return;
            exchange(p, atFairy);
            buildMenu(p, Prog.fairy(p), atFairy).open(p);
        });

        List<String> tot = new ArrayList<>();
        tot.add("&7Exchanges made: &d" + d.exchanged);
        tot.add("");
        tot.add("&7Stats from the fairy so far:");
        tot.add(" &c+" + Text.commas(now[0]) + " " + Stat.HEALTH.label());
        tot.add(" &a+" + Text.commas(now[1]) + " " + Stat.DEFENSE.label());
        tot.add(" &c+" + Text.commas(now[2]) + " " + Stat.STRENGTH.label());
        tot.add(" &f+" + Text.commas(now[3]) + " " + Stat.SPEED.label());
        m.set(24, Icons.of(Material.GOLDEN_APPLE, "&aYour Rewards", tot));

        // per island
        Map<String, int[]> per = perIsland(d);
        int[] slots = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        int i = 0;
        for (Map.Entry<String, int[]> e : per.entrySet()) {
            if (i >= slots.length) break;
            IslandDef def = Sky.worlds().island(e.getKey());
            String name = def != null && def.displayName != null ? def.displayName : Text.titleCase(e.getKey());
            int[] fc = e.getValue();
            boolean all = fc[0] >= fc[1];
            m.set(slots[i++], Icons.of(all ? Material.LIME_STAINED_GLASS_PANE : Material.PINK_STAINED_GLASS_PANE,
                    (all ? "&a" : "&d") + name, "&7Found: &d" + fc[0] + "&7/&d" + fc[1], Prog.bar(fc[0], fc[1])));
        }
        if (per.isEmpty()) m.set(31, Icons.of(Material.BARRIER, "&cNo souls yet", "&7No island has hidden any souls yet."));
        Prog.backToSkyMenu(m);
        return m;
    }

    /** island -> {found, total} */
    Map<String, int[]> perIsland(PData.Fairy d) {
        Map<String, int[]> out = new LinkedHashMap<>();
        Set<String> f = new HashSet<>(d.found);
        for (Map.Entry<String, List<Soul>> e : byIsland.entrySet()) {
            int got = 0;
            for (Soul s : e.getValue()) if (f.contains(s.id())) got++;
            out.put(e.getKey(), new int[]{got, e.getValue().size()});
        }
        return out;
    }

    void exchange(Player p, boolean atFairy) {
        if (!atFairy && !Prog.fairyAnywhere.get()) { Msg.error(p, "Talk to the fairy in the Hub to exchange souls."); return; }
        PData.Fairy d = Prog.fairy(p);
        if (PMath.fairyExchangesAvailable(d.found.size(), d.exchanged) <= 0) { Msg.error(p, "You need 5 unexchanged Fairy Souls."); return; }
        double[] before = stats(d.exchanged);
        d.exchanged++;
        double[] after = stats(d.exchanged);
        List<String> body = new ArrayList<>();
        body.add("&7You gave &d5 Fairy Souls &7to the fairy.");
        body.add("");
        body.add("&a&lREWARDS");
        if (after[0] > before[0]) body.add("  &8+&c" + Text.commas(after[0] - before[0]) + " " + Stat.HEALTH.label());
        if (after[1] > before[1]) body.add("  &8+&a" + Text.commas(after[1] - before[1]) + " " + Stat.DEFENSE.label());
        if (after[2] > before[2]) body.add("  &8+&c" + Text.commas(after[2] - before[2]) + " " + Stat.STRENGTH.label());
        if (after[3] > before[3]) body.add("  &8+&f" + Text.commas(after[3] - before[3]) + " " + Stat.SPEED.label());
        Msg.box(p, "&d&lFAIRY SOUL EXCHANGE &8#" + d.exchanged, body);
        Msg.sound(p, Sound.ENTITY_ALLAY_ITEM_GIVEN, 1.2f);
        Prog.award(p, "fairy:exchange:" + d.exchanged, Prog.levelsFile.fairyExchangeXp, "Fairy Soul Exchange");
        Sky.stats().invalidate(p);
        Sky.profiles().saveNow(p);
    }

    /** Admin helper: mark every soul of an island (or all, island null) found for a player. */
    int grantAll(Player p, String island) {
        int n = 0;
        PData.Fairy d = Prog.fairy(p);
        for (Soul s : byId.values()) {
            if (island != null && !island.equals(s.island())) continue;
            if (d.found.contains(s.id())) continue;
            d.found.add(s.id());
            n++;
        }
        foundCache.put(p.getUniqueId(), new HashSet<>(d.found));
        applyVisibility(p);
        return n;
    }

    void reset(Player p) {
        PData.Fairy d = Prog.fairy(p);
        d.found.clear();
        d.exchanged = 0;
        foundCache.put(p.getUniqueId(), new HashSet<>());
        applyVisibility(p);
        Sky.stats().invalidate(p);
    }

    /** "hub=40, park=12" */
    String summary() {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<Soul>> e : byIsland.entrySet()) parts.add(e.getKey() + "=" + e.getValue().size());
        return total() + " souls" + (parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")");
    }
}
