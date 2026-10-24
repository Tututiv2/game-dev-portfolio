package com.skyclone.mod.combatzones;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.combatzones.gen.DenGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Silkmother's Sanctuary altar: four Callings (one each, from up to four players) wake the
 * level 300 Silkmother; a Silkmother Crystal wakes her at level 500. At half health and a quarter
 * she calls her brood. Everyone who dealt at least 5% of her health rolls her loot table.
 */
final class ArachneAltar implements Listener {

    static final String ISLAND = DenGenerator.ID;

    private final CombatzonesModule m;
    final List<UUID> callings = new ArrayList<>();
    private UUID bossId;
    private int bossLevel;
    private boolean broodHalf, broodQuarter, summoning;
    /** Level being summoned (set before the 5 s delay, so a shutdown mid-summon knows it). */
    private int pendingLevel;
    /** Wave 5: a fight a restart interrupted (its summoning items were spent): the next altar click re-wakes her free. */
    int interruptedLevel;
    private long lastHit;
    final Map<UUID, Double> damage = new LinkedHashMap<>();
    private final BukkitTask ticker;

    ArachneAltar(CombatzonesModule m) {
        this.m = m;
        ticker = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 40L, 20L);
        // pending Callings were paid for with items: they survive a restart (they used to vanish)
        CzState.loadAll(rows -> {
            String s = rows.get("arachne.callings");
            if (s != null && !s.isBlank() && callings.isEmpty() && bossId == null && !summoning) {
                int need = Math.max(1, m.dials.arachneCallings.get());
                for (String u : s.split(",")) {
                    try { if (callings.size() < need) callings.add(UUID.fromString(u.trim())); } catch (IllegalArgumentException ignored) { }
                }
                if (callings.size() >= need) callings.subList(need - 1, callings.size()).clear(); // the last one is placed again by hand
                if (Sky.worlds().isBuilt(ISLAND)) lightSockets();
            }
            String cut = rows.get("arachne.interrupted");
            if (cut != null && cut.contains(":") && bossId == null && !summoning) {
                interruptedLevel = (int) CzState.parseLong(cut.substring(0, cut.indexOf(':')), 0);
                callings.clear();
                for (String u : cut.substring(cut.indexOf(':') + 1).split(",")) {
                    try { if (!u.isBlank()) callings.add(UUID.fromString(u.trim())); } catch (IllegalArgumentException ignored) { }
                }
                if (Sky.worlds().isBuilt(ISLAND)) lightSockets();
                CzState.put("arachne.interrupted", "");
            }
            savedCallings = pendingCallings();
            stateLoaded = true;
        });
    }

    private String savedCallings = "";
    private boolean stateLoaded;

    /** Callings waiting on the altar (no boss up, none being summoned), comma-joined; "" otherwise. */
    private String pendingCallings() {
        if (bossId != null || summoning || interruptedLevel > 0) return "";
        StringBuilder b = new StringBuilder();
        for (UUID u : callings) b.append(b.length() == 0 ? "" : ",").append(u);
        return b.toString();
    }

    void shutdown() {
        ticker.cancel();
        // wave 5: a restart during the summon or the fight no longer eats the Callings / Crystal that paid for it
        if (busy() || interruptedLevel > 0) {
            int lvl = interruptedLevel > 0 ? interruptedLevel : bossId != null ? bossLevel : pendingLevel;
            StringBuilder b2 = new StringBuilder();
            for (UUID u : callings) b2.append(b2.length() == 0 ? "" : ",").append(u);
            if (lvl > 0) CzState.put("arachne.interrupted", lvl + ":" + b2);
        }
        LivingEntity b = boss();
        if (b != null) Sky.mobs().remove(b);
    }

    Location altarBlock() {
        Layout lay = Sky.worlds().layout(ISLAND);
        World w = Sky.worlds().world(ISLAND);
        if (lay == null || w == null || lay.data().get("altar.arachne.block") == null) return null;
        String[] p = lay.data().get("altar.arachne.block").split(",");
        return new Location(w, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    boolean busy() { return boss() != null || summoning; }

    LivingEntity boss() {
        if (bossId == null) return null;
        Entity e = Bukkit.getEntity(bossId);
        return e instanceof LivingEntity le && le.isValid() ? le : null;
    }

    int level() { return bossLevel; }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onAltar(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (b.getType() != Material.LODESTONE && b.getType() != Material.CHISELED_POLISHED_BLACKSTONE && b.getType() != Material.CRYING_OBSIDIAN) return;
        if (!ISLAND.equals(Sky.worlds().islandOf(b.getWorld()))) return;
        Location a = altarBlock();
        if (a == null || a.getWorld() != b.getWorld() || a.distanceSquared(b.getLocation()) > 26) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!Sky.profiles().isReady(p)) return;
        use(p, p.getInventory().getItemInMainHand());
    }

    void use(Player p, ItemStack hand) {
        if (busy()) { Msg.error(p, "The Silkmother is already awake!"); return; }
        if (interruptedLevel > 0) {                             // her last fight was cut short by a restart: no new items needed
            int lvl = interruptedLevel;
            interruptedLevel = 0;
            broadcast("&5☬ &dThe altar still remembers the last calling...");
            summon(lvl, p);
            return;
        }
        String id = Sky.items().idOf(hand);
        int need = Math.max(1, m.dials.arachneCallings.get());
        if ("ARACHNE_CRYSTAL".equals(id)) {
            hand.setAmount(hand.getAmount() - 1);
            callings.clear();
            callings.add(p.getUniqueId());
            broadcast("&5☬ &d" + p.getName() + " &5set a &dSilkmother Crystal &5into the altar...");
            summon(500, p);
            return;
        }
        if (!"ARACHNE_KEEPER_FRAGMENT".equals(id)) {
            Msg.send(p, "&5☬ &7The altar waits for a &aSilkmother's Calling &7(" + callings.size() + "/" + need + "), or a &5Silkmother Crystal&7.");
            Msg.send(p, "&8Keepers in the Silkmother's Burrow carry Callings.");
            return;
        }
        if (callings.contains(p.getUniqueId()) && callings.size() < need && distinctOnline() > 1) {
            Msg.error(p, "You already placed a Calling. Others must add theirs.");
            return;
        }
        hand.setAmount(hand.getAmount() - 1);
        callings.add(p.getUniqueId());
        lightSockets();
        broadcast("&5☬ &d" + p.getName() + " &5placed a Silkmother's Calling! &7(&e" + callings.size() + "&7/&a" + need + "&7)");
        Msg.sound(p, Sound.ENTITY_SPIDER_AMBIENT, 0.6f);
        if (callings.size() >= need) summon(300, p);
    }

    private int distinctOnline() {
        World w = Bukkit.getWorld("sb_" + ISLAND);
        return w == null ? 1 : w.getPlayers().size();
    }

    void summon(int level, Player who) {
        summoning = true;
        pendingLevel = level;
        broadcast("&5☬ &dThe sanctuary trembles. Something with too many legs is coming...");
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            summoning = false;
            Location at = Sky.worlds().anchor(ISLAND, "boss.arachne");
            if (at == null) { broadcast("&cThe sanctuary is not built."); callings.clear(); return; }
            LivingEntity b = Sky.mobs().spawn("den_arachne_" + level, at);
            if (b == null) { broadcast("&cThe Silkmother did not come."); callings.clear(); return; }
            bossId = b.getUniqueId();
            bossLevel = level;
            broodHalf = broodQuarter = false;
            damage.clear();
            lastHit = System.currentTimeMillis();
            at.getWorld().playSound(at, Sound.ENTITY_SPIDER_DEATH, 2f, 0.4f);
            at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at, 60, 2, 1, 2, 0.02);
            broadcast("&5☬ &c&lThe Silkmother &7[Lv" + level + "] &chas awoken!");
            for (Player p : players())
                p.showTitle(net.kyori.adventure.title.Title.title(Text.c("&c&lSILKMOTHER"), Text.c("&7Level " + level)));
        }, 100L);
    }

    private void lightSockets() {
        Location a = altarBlock();
        if (a == null) return;
        int[][] s = {{-3, -3}, {3, -3}, {-3, 3}, {3, 3}};
        for (int i = 0; i < 4; i++) {
            Block b = a.clone().add(s[i][0], 0, s[i][1]).getBlock();
            b.setType(i < callings.size() ? Material.CRYING_OBSIDIAN : Material.CHISELED_POLISHED_BLACKSTONE, false);
        }
    }

    private void tick() {
        if (stateLoaded) {
            String now = pendingCallings();
            if (!now.equals(savedCallings)) { savedCallings = now; CzState.put("arachne.callings", now); }
        }
        LivingEntity b = boss();
        if (bossId != null && b == null) {                       // unloaded or removed
            bossId = null;
            callings.clear();
            lightSockets();
            return;
        }
        if (b == null) return;
        double frac = Sky.mobs().health(b) / Math.max(1, Sky.mobs().maxHealth(b));
        if (!broodHalf && frac <= 0.5) { broodHalf = true; brood(b, 4); }
        if (!broodQuarter && frac <= 0.25) { broodQuarter = true; brood(b, 4); }
        if (System.currentTimeMillis() - lastHit > m.dials.arachneIdleMinutes.get() * 60_000L) {
            broadcast("&5☬ &7The Silkmother lost interest and crawled back into the dark.");
            Sky.mobs().remove(b);
            bossId = null;
            callings.clear();
            lightSockets();
        }
    }

    private void brood(LivingEntity b, int n) {
        String mob = bossLevel >= 500 ? "den_arachnes_brood_200" : "den_arachnes_brood_100";
        broadcast("&5☬ &cThe Silkmother calls her brood!");
        for (int i = 0; i < n; i++) {
            Location l = b.getLocation().clone().add((i % 2 == 0 ? 3 : -3), 0.5, (i < 2 ? 3 : -3));
            if (!l.getBlock().isPassable()) l = b.getLocation();
            Sky.mobs().spawn(mob, l);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        if (bossId == null || e.ctx.victim == null || !bossId.equals(e.ctx.victim.getUniqueId())) return;
        Player p = e.ctx.playerAttacker();
        if (p == null) return;
        damage.merge(p.getUniqueId(), Math.max(0, e.ctx.finalDamage), Double::sum);
        lastHit = System.currentTimeMillis();
    }

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.def == null || !e.def.id.startsWith("den_arachne_")) return;
        if (bossId == null || !bossId.equals(e.mob.getUniqueId())) return;
        MobDef def = e.def;
        double max = def.health;
        String lvl = String.valueOf(bossLevel);
        for (Map.Entry<UUID, Double> en : damage.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !Sky.profiles().isReady(p)) continue;
            boolean isKiller = e.killer != null && e.killer.getUniqueId().equals(p.getUniqueId());
            if (!isKiller && en.getValue() >= max * 0.05)                       // core already paid the killer
                Sky.loot().roll(p, def.drops, LootSource.MOB_DROP, Sky.loot().magicFind(p, def));     // wave 5: per-target MF
            CzData d = m.data(p);
            d.arachneKills.merge(lvl, 1, Integer::sum);
            Sky.service(Features.SkyLevel.class).award(p, "arachne:" + lvl, m.dials.arachneSbXp.get(), "Slay the Silkmother (Lv" + lvl + ")");
            Msg.box(p, "&c&lSILKMOTHER DOWN!", List.of("&7Level " + lvl + " slain by &a" + (e.killer == null ? "?" : e.killer.getName()),
                    "&7Your Damage: &e" + Text.commasInt(en.getValue()) + " &7(" + Math.round(en.getValue() * 100 / max) + "%)"));
        }
        bossId = null;
        callings.clear();
        damage.clear();
        lightSockets();
    }

    List<Player> players() {
        World w = Bukkit.getWorld("sb_" + ISLAND);
        return w == null ? List.of() : new ArrayList<>(w.getPlayers());
    }

    void broadcast(String s) { for (Player p : players()) Msg.send(p, s); }
}
