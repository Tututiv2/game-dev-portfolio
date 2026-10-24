package com.skyclone.mod.combatzones;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.combatzones.gen.DenGenerator;
import com.skyclone.mod.combatzones.gen.EndGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The timed and community bosses: the Broodmother's five-stage clock on the Top of the Nest,
 * the End Stone Colossus that rises when enough end stone is quarried in the Protector's Field,
 * the mineable blocks that drive it (and the Den's gravel), and the Spider's Den weather.
 */
final class WorldBosses implements Listener {

    static final String RAIN = "spiders_den_rain", THUNDER = "spiders_den_thunder";

    private final CombatzonesModule m;
    long broodSlainAt = System.currentTimeMillis() - 4L * 60_000;     // first boot: already "Soon"
    UUID broodId;
    private int lastBroodStage = -1;
    long endStoneMined;
    UUID colossusId;
    boolean colossusWarning;
    private int lastColossusStage;
    final Map<UUID, Double> colossusDamage = new LinkedHashMap<>();
    private final Map<Block, BlockData> regen = new HashMap<>();
    private final BukkitTask ticker;

    WorldBosses(CombatzonesModule m) {
        this.m = m;
        // research 03 sec 13: rain 20 of every 40 minutes, every third rain is a thunderstorm
        Sky.calendar().register(CalendarEvent.realtime(RAIN, "Spider's Den Rain", 40, 0, 20));
        Sky.calendar().register(CalendarEvent.realtime(THUNDER, "Spider's Den Thunderstorm", 120, 0, 20));
        Sky.worlds().addBreakHandler(new Quarry());
        ticker = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 60L, 20L);
        // the Broodmother clock and the Colossus meter survive a restart (they used to reset)
        CzState.loadAll(rows -> {
            broodSlainAt = CzState.parseLong(rows.get("brood.slain-at"), broodSlainAt);
            endStoneMined = Math.max(endStoneMined, CzState.parseLong(rows.get("colossus.mined"), 0));
            savedBrood = broodSlainAt;
            savedMined = endStoneMined;
            stateLoaded = true;
        });
    }

    private long savedBrood = Long.MIN_VALUE, savedMined = Long.MIN_VALUE;
    private boolean stateLoaded;

    /** Writes the two clocks when they changed; nothing before the stored values were read. */
    private void persistClocks() {
        if (!stateLoaded) return;
        if (broodSlainAt != savedBrood) { savedBrood = broodSlainAt; CzState.put("brood.slain-at", Long.toString(broodSlainAt)); }
        if (endStoneMined != savedMined) { savedMined = endStoneMined; CzState.put("colossus.mined", Long.toString(endStoneMined)); }
    }

    void shutdown() {
        ticker.cancel();
        regen.forEach((b, d) -> b.setBlockData(d, false));
        regen.clear();
        for (UUID u : new UUID[]{broodId, colossusId}) {
            Entity e = u == null ? null : Bukkit.getEntity(u);
            if (e instanceof LivingEntity le) Sky.mobs().remove(le);
        }
    }

    // ===================================================================================== broodmother

    int broodStage() {
        if (living(broodId) != null) return 5;
        return DragonMath.broodStage((System.currentTimeMillis() - broodSlainAt) / 1000, m.dials.broodCycle.get());
    }

    String broodLabel() {
        int s = broodStage();
        if (s == 5 && living(broodId) == null) return "&cAwakening";
        return switch (s) {
            case 0 -> "&7Slain";
            case 1 -> "&8Dormant";
            case 2 -> "&eSoon";
            case 3 -> "&6Awakening";
            case 4 -> "&cImminent";
            default -> "&4&lALIVE!";
        };
    }

    private void tick() {
        persistClocks();
        // ---- broodmother
        int stage = broodStage();
        if (stage != lastBroodStage) {
            if (stage >= 3 && stage <= 4) denBroadcast("&4☠ &cThe Broodmother stirs on the Top of the Nest: " + broodLabel());
            lastBroodStage = stage;
        }
        if (stage >= 5 && living(broodId) == null && Sky.worlds().isBuilt(DenGenerator.ID)) {
            Location at = Sky.worlds().anchor(DenGenerator.ID, "boss.broodmother");
            if (at != null && near(at, m.dials.broodRadius.get())) {
                LivingEntity b = Sky.mobs().spawn("den_broodmother_12", at);
                if (b != null) {
                    broodId = b.getUniqueId();
                    denBroadcast("&4☠ &c&lThe Broodmother has risen on the Top of the Nest!");
                    for (Player p : at.getWorld().getPlayers()) Msg.sound(p, Sound.ENTITY_SPIDER_DEATH, 0.5f);
                }
            }
        }
        // ---- colossus
        if (colossusId != null && living(colossusId) == null) { colossusId = null; colossusDamage.clear(); }
        int cs = DragonMath.protectorStage(endStoneMined, m.dials.protectorPerStage.get());
        if (cs != lastColossusStage && cs > 0 && colossusId == null && !colossusWarning) {
            endBroadcast("&e⛏ &7The End Stone Colossus stirs... &eStage " + cs + "&7/&a5");
            lastColossusStage = cs;
        }
        if (cs >= 5 && colossusId == null && !colossusWarning) {
            colossusWarning = true;
            int w = Math.max(1, m.dials.protectorWarning.get());
            endBroadcast("&e&lThe End Stone Colossus will rise in " + w + " seconds! &7(Protector's Field)");
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), this::spawnColossus, w * 20L);
        }
    }

    void spawnColossus() {
        colossusWarning = false;
        Location at = Sky.worlds().anchor(EndGenerator.ID, "boss.end_stone_protector");
        if (at == null || !Sky.worlds().isBuilt(EndGenerator.ID)) return;
        LivingEntity b = Sky.mobs().spawn("end_stone_protector", at);
        if (b == null) return;
        colossusId = b.getUniqueId();
        colossusDamage.clear();
        endStoneMined = 0;
        lastColossusStage = 0;
        endBroadcast("&e&lThe End Stone Colossus has risen!");
        for (Player p : at.getWorld().getPlayers()) Msg.sound(p, Sound.ENTITY_IRON_GOLEM_HURT, 0.5f);
    }

    void spawnBrood() { broodSlainAt = 0; }

    @EventHandler
    public void onDamage(SkyEvents.Damage e) {
        if (colossusId == null || e.ctx.victim == null || !colossusId.equals(e.ctx.victim.getUniqueId()) || e.ctx.cancelled) return;
        Player p = e.ctx.playerAttacker();
        if (p != null) colossusDamage.merge(p.getUniqueId(), Math.max(0, e.ctx.finalDamage), Double::sum);
    }

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.def == null) return;
        if (e.def.id.equals("den_broodmother_12")) {
            broodSlainAt = System.currentTimeMillis();
            broodId = null;
            lastBroodStage = 0;
            denBroadcast("&4☠ &7The Broodmother was slain" + (e.killer != null ? " by &a" + e.killer.getName() : "") + "&7!");
            if (e.killer != null && Sky.profiles().isReady(e.killer)) m.data(e.killer).broodmotherKills++;
        } else if (e.def.id.equals("end_stone_protector")) {
            Map<UUID, Integer> place = DragonMath.placements(colossusDamage);
            for (Map.Entry<UUID, Integer> en : place.entrySet()) {
                Player p = Bukkit.getPlayer(en.getKey());
                if (p == null || !Sky.profiles().isReady(p)) continue;
                boolean killer = e.killer != null && e.killer.getUniqueId().equals(p.getUniqueId());
                double dmg = colossusDamage.getOrDefault(p.getUniqueId(), 0.0);
                if (!killer && dmg >= e.def.health * 0.01) {
                    Sky.loot().roll(p, e.def.drops, LootSource.MOB_DROP, Sky.loot().magicFind(p, e.def));      // wave 5: per-target MF
                    Sky.service(SkillService.class).addXp(p, SkillType.COMBAT, e.def.combatXp * 0.5, "colossus");
                }
                m.data(p).colossusKills++;
                Msg.box(p, "&e&lEND STONE COLOSSUS DOWN!", List.of("&7Your Damage: &e" + Text.commasInt(dmg) + " &7(Position #" + en.getValue() + ")"));
            }
            colossusId = null;
            colossusDamage.clear();
        }
    }

    // ===================================================================================== quarrying

    /** End stone in Protector's Field and gravel in the Gravel Mines: break, drop, grow back. */
    private final class Quarry implements WorldService.BreakHandler {
        @Override public int priority() { return 60; }

        @Override public boolean handles(Player p, Block block, Layout.Zone zone) {
            String isl = Sky.worlds().islandOf(block.getWorld());
            if (EndGenerator.ID.equals(isl) && block.getType() == Material.END_STONE) return inArea(isl, "endstone.quarry", block);
            if (DenGenerator.ID.equals(isl) && block.getType() == Material.GRAVEL) return inArea(isl, "gravel.blocks", block);
            return false;
        }

        @Override public void onBreak(Player p, Block block, BlockBreakEvent event) {
            if (!Sky.profiles().isReady(p) || regen.containsKey(block)) return;
            boolean end = block.getType() == Material.END_STONE;
            String drop = end ? Sky.items().vanillaId(Material.END_STONE)
                    : (Math.random() < m.dials.gravelFlintChance.get() ? Sky.items().vanillaId(Material.FLINT) : Sky.items().vanillaId(Material.GRAVEL));
            BlockData was = block.getBlockData();
            Location at = block.getLocation();
            regen.put(block, was);
            block.setType(Material.BEDROCK, false);
            Sky.loot().give(p, drop, 1, LootSource.MINING);
            Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, LootSource.MINING, drop, 1, at));
            Sky.service(SkillService.class).addXp(p, SkillType.MINING, end ? m.dials.endStoneXp.get() : 1, end ? "end_stone" : "gravel");
            block.getWorld().playSound(at, end ? Sound.BLOCK_STONE_BREAK : Sound.BLOCK_GRAVEL_BREAK, 1f, 1f);
            if (end) {
                endStoneMined++;
                m.data(p).endStoneMined++;
            }
            int secs = Math.max(1, m.dials.regenSeconds.get());
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                BlockData d = regen.remove(block);
                if (d != null) block.setBlockData(d, false);
            }, secs * 20L);
        }
    }

    static boolean inArea(String island, String area, Block b) {
        Box box = Sky.worlds().area(island, area);
        return box != null && box.contains(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
    }

    // ===================================================================================== weather

    @EventHandler
    public void onStart(SkyEvents.CalendarStart e) {
        if (RAIN.equals(e.eventId) || THUNDER.equals(e.eventId)) applyWeather();
        if (RAIN.equals(e.eventId) && m.dials.rainEnabled.get()) denBroadcast("&9☂ &bIt has started to rain in the Spider's Den. &7(Rain Slimes are out!)");
        if (THUNDER.equals(e.eventId) && m.dials.rainEnabled.get()) denBroadcast("&9⚡ &bA thunderstorm rolls over the Spider's Den!");
    }

    @EventHandler
    public void onEnd(SkyEvents.CalendarEnd e) {
        if (RAIN.equals(e.eventId) || THUNDER.equals(e.eventId)) applyWeather();
    }

    @EventHandler
    public void onBuilt(SkyEvents.IslandBuilt e) {
        if (DenGenerator.ID.equals(e.islandId)) applyWeather();
    }

    boolean raining() { return m.dials.rainEnabled.get() && Sky.calendar().active(RAIN); }

    boolean thundering() { return m.dials.rainEnabled.get() && Sky.calendar().active(THUNDER); }

    void applyWeather() {
        World w = Bukkit.getWorld("sb_" + DenGenerator.ID);
        if (w == null) return;
        boolean rain = raining() || thundering();
        w.setStorm(rain);
        w.setThundering(thundering());
        if (rain) w.setWeatherDuration(20 * 60 * 25); else w.setClearWeatherDuration(Integer.MAX_VALUE / 2);
    }

    // ===================================================================================== helpers

    static LivingEntity living(UUID u) {
        if (u == null) return null;
        Entity e = Bukkit.getEntity(u);
        return e instanceof LivingEntity le && le.isValid() ? le : null;
    }

    private static boolean near(Location at, int r) {
        for (Player p : at.getWorld().getPlayers()) if (p.getLocation().distanceSquared(at) <= (double) r * r) return true;
        return false;
    }

    static List<Player> on(String island) {
        World w = Bukkit.getWorld("sb_" + island);
        return w == null ? List.of() : new ArrayList<>(w.getPlayers());
    }

    void denBroadcast(String s) { for (Player p : on(DenGenerator.ID)) Msg.send(p, s); }

    void endBroadcast(String s) { for (Player p : on(EndGenerator.ID)) Msg.send(p, s); }
}
