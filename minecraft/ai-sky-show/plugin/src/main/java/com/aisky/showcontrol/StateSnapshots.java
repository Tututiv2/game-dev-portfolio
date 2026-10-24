package com.aisky.showcontrol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds every online player's structured state (CONTRACT §4 /state) on the MAIN thread every few
 * ticks and publishes it as JSON strings the HTTP threads hand out unchanged - HTTP threads never
 * call Bukkit or Sky. Also runs the polls that have no event: near_death and minion placement.
 *
 * <p>Rule: only what the Sky API (or Bukkit) actually exposes; a section that is not available is
 * omitted, never guessed. Every section is isolated in its own try so one failing service cannot
 * blank the whole state.
 */
final class StateSnapshots {

    private final ShowControlPlugin plugin;
    /** name (lower) -> /state JSON. Kept after quit with online=false. */
    private final Map<String, String> states = new ConcurrentHashMap<>();
    /** name (lower) -> /players row JSON. */
    private final Map<String, JsonObject> rows = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> lastLoc = new ConcurrentHashMap<>();
    private final Map<String, Long> nearDeathAt = new ConcurrentHashMap<>();
    private volatile double tps = 20.0;
    private volatile int online;
    private int tickCount;

    StateSnapshots(ShowControlPlugin plugin) { this.plugin = plugin; }

    double tps() { return tps; }

    int online() { return online; }

    /** Main thread, every {@code snapshot-ticks}. */
    void tick() {
        tickCount++;
        try { tps = Math.min(20.0, Bukkit.getTPS()[0]); } catch (Throwable ignored) { }
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            n++;
            String key = p.getName().toLowerCase(Locale.ROOT);
            try {
                JsonObject s = build(p);
                states.put(key, EventBus.GSON.toJson(s));
                rows.put(key, row(p, s));
                if (s.has("pos")) lastLoc.put(key, EventBus.locJson(p.getLocation()));
            } catch (Throwable t) {
                plugin.getLogger().warning("state for " + p.getName() + " failed: " + t);
            }
            if (plugin.config().tracked(p)) {
                pollNearDeath(p);
                if (tickCount % 10 == 0) pollMinions(p);
            }
        }
        online = n;
    }

    void markOffline(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        String s = states.get(key);
        if (s != null) {
            JsonObject o = EventBus.GSON.fromJson(s, JsonObject.class);
            o.addProperty("online", false);
            states.put(key, EventBus.GSON.toJson(o));
        }
        JsonObject r = rows.get(key);
        if (r != null) { r = r.deepCopy(); r.addProperty("online", false); rows.put(key, r); }
    }

    JsonObject lastLoc(String name) { return lastLoc.get(name.toLowerCase(Locale.ROOT)); }

    /** HTTP thread safe. Never null: an unknown/offline player is {"name","online":false}. */
    String state(String name) {
        String s = states.get(name.toLowerCase(Locale.ROOT));
        if (s != null) return s;
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("online", false);
        o.addProperty("known", false);
        return EventBus.GSON.toJson(o);
    }

    /** HTTP thread safe. */
    String players() {
        JsonArray a = new JsonArray();
        for (JsonObject r : rows.values()) a.add(r);
        return EventBus.GSON.toJson(a);
    }

    // ---- polls -------------------------------------------------------------------------------

    private void pollNearDeath(Player p) {
        double hp, max;
        try { hp = Sky.vitals().health(p); max = Sky.vitals().maxHealth(p); } catch (RuntimeException e) { return; }
        if (max <= 0 || hp <= 0 || hp >= max * plugin.config().nearDeathFraction) return;
        long now = System.currentTimeMillis();
        Long last = nearDeathAt.get(p.getName());
        if (last != null && now - last < plugin.config().nearDeathDebounceSeconds * 1000L) return;
        nearDeathAt.put(p.getName(), now);
        plugin.bus().ev("near_death").player(p.getName()).drama(5).loc(p.getLocation())
                .data("health", Math.round(hp)).data("max", Math.round(max))
                .text(p.getName() + " is almost dead: " + Math.round(hp) + "/" + Math.round(max) + " HP").emit();
    }

    /**
     * SkyClone has no "minion placed" event or service, so the placed-minion count of the profile is
     * polled from the minions module's shared data (read-only, same class SkyClone stores). Guarded:
     * if that module or class is missing this simply never fires.
     */
    private void pollMinions(Player p) {
        if (!safeReady(p)) return;
        List<String[]> placed = minions(p);
        if (placed == null) return;
        Integer before = plugin.memory().swapMinionCount(p.getName(), placed.size());
        if (before == null || placed.size() <= before) return;
        String[] newest = placed.get(placed.size() - 1);
        String type = Names.titleCase(newest[0]);
        plugin.bus().ev("minion_placed").player(p.getName()).drama(4).loc(p.getLocation())
                .data("type", newest[0]).data("tier", Integer.parseInt(newest[1])).data("count", placed.size())
                .text(p.getName() + " placed a " + type + " Minion " + GameListener.roman(Integer.parseInt(newest[1]))
                        + " (" + placed.size() + " placed)").emit();
        plugin.first("first_minion", p, "first minion placed", 6, p.getLocation());
    }

    /** [type, tier, slotIndex] per placed minion, in placement order; null when unavailable. */
    private static List<String[]> minions(Player p) {
        try {
            com.skyclone.mod.minions.MinionStore st =
                    Sky.profiles().shared(p, "minions", com.skyclone.mod.minions.MinionStore.class);
            if (st == null || st.placed == null) return null;
            List<com.skyclone.mod.minions.MinionStore.Placed> list = new ArrayList<>(st.placed.values());
            list.sort(Comparator.comparingLong(m -> m.placedAt));
            List<String[]> out = new ArrayList<>();
            int i = 0;
            for (var m : list) out.add(new String[]{m.type, String.valueOf(m.tier), String.valueOf(i++)});
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean safeReady(Player p) {
        try { return Sky.profiles().isReady(p); } catch (RuntimeException e) { return false; }
    }

    // ---- the state document ----------------------------------------------------------------

    private JsonObject row(Player p, JsonObject s) {
        JsonObject r = new JsonObject();
        r.addProperty("name", p.getName());
        r.addProperty("uuid", p.getUniqueId().toString());
        r.addProperty("online", true);
        copy(s, r, "island", "island_name", "zone", "health", "maxHealth", "purse");
        if (plugin.config().isCamera(p)) r.addProperty("camera", true);
        else if (plugin.config().isCast(p.getName())) r.addProperty("cast", true);
        return r;
    }

    private static void copy(JsonObject from, JsonObject to, String... keys) {
        for (String k : keys) if (from.has(k)) to.add(k, from.get(k));
    }

    private JsonObject build(Player p) {
        JsonObject s = new JsonObject();
        s.addProperty("name", p.getName());
        s.addProperty("online", true);
        s.addProperty("t", System.currentTimeMillis());
        boolean ready = safeReady(p);
        s.addProperty("ready", ready);
        if (plugin.config().isCamera(p)) s.addProperty("camera", true);

        Location l = p.getLocation();
        JsonObject pos = new JsonObject();
        pos.addProperty("x", EventBus.round1(l.getX()));
        pos.addProperty("y", EventBus.round1(l.getY()));
        pos.addProperty("z", EventBus.round1(l.getZ()));
        pos.addProperty("yaw", Math.round(l.getYaw()));
        pos.addProperty("pitch", Math.round(l.getPitch()));
        pos.addProperty("world", l.getWorld() == null ? "" : l.getWorld().getName());
        s.add("pos", pos);
        s.addProperty("gamemode", p.getGameMode().name());

        WorldService w = Sky.worlds();
        guard(() -> {
            String island = w.islandOf(l.getWorld());
            if (island != null) {
                s.addProperty("island", island);
                s.addProperty("island_name", Names.island(island));
            }
            Layout.Zone z = w.zoneOf(p);
            if (z != null) {
                s.addProperty("zone", Names.strip(z.displayName()));
                s.addProperty("zone_id", z.id());
            }
        });
        if (!ready) return s;   // profile data before ProfileReady is a throwaway object: show nothing

        guard(() -> {
            s.addProperty("health", Math.round(Sky.vitals().health(p)));
            s.addProperty("maxHealth", Math.round(Sky.vitals().maxHealth(p)));
            s.addProperty("mana", Math.round(Sky.vitals().mana(p)));
            s.addProperty("maxMana", Math.round(Sky.vitals().maxMana(p)));
        });
        guard(() -> s.addProperty("defense", Math.round(Sky.stats().get(p, Stat.DEFENSE))));
        guard(() -> {
            JsonObject st = new JsonObject();
            for (Stat k : new Stat[]{Stat.HEALTH, Stat.DEFENSE, Stat.STRENGTH, Stat.CRIT_CHANCE, Stat.CRIT_DAMAGE,
                    Stat.SPEED, Stat.INTELLIGENCE, Stat.MAGIC_FIND, Stat.MINING_FORTUNE, Stat.FARMING_FORTUNE,
                    Stat.FORAGING_FORTUNE}) {
                st.addProperty(k.name(), EventBus.round1(Sky.stats().get(p, k)));
            }
            s.add("stats", st);
        });
        guard(() -> {
            s.addProperty("purse", Math.floor(Sky.economy().purse(p)));
            s.addProperty("bank", Math.floor(Sky.economy().bank(p)));
            s.addProperty("bits", Sky.economy().bits(p));
        });
        guard(() -> s.addProperty("profile", Sky.profiles().profileName(p)));
        guard(() -> {
            if (!Sky.has(SkillService.class)) return;
            SkillService sk = Sky.service(SkillService.class);
            JsonObject skills = new JsonObject();
            for (SkillType t : SkillType.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("level", sk.level(p, t));
                o.addProperty("xp", EventBus.round1(sk.xp(p, t)));
                o.addProperty("progress", EventBus.round1(sk.progress(p, t)));
                o.addProperty("next", EventBus.round1(sk.needed(p, t)));
                o.addProperty("cap", sk.cap(p, t));
                skills.add(t.name(), o);
            }
            s.add("skills", skills);
            s.addProperty("skill_average", EventBus.round1(sk.average(p)));
        });
        guard(() -> {
            if (Sky.has(Features.SkyLevel.class)) s.addProperty("sb_level", Sky.service(Features.SkyLevel.class).level(p));
        });
        guard(() -> {
            Map<String, Long> seen = plugin.memory().collections(p.getName());
            if (seen.isEmpty() && !Sky.has(Features.Collections.class)) return;
            Features.Collections col = Sky.service(Features.Collections.class);
            JsonArray arr = new JsonArray();
            for (Map.Entry<String, Long> e : seen.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", e.getKey());
                o.addProperty("name", Names.item(e.getKey()));
                o.addProperty("tier", col.tier(p, e.getKey()));
                o.addProperty("total", col.count(p, e.getKey()));
                arr.add(o);
            }
            s.add("collections", arr);
        });
        guard(() -> inventory(p, s));
        guard(() -> nearby(p, s));
        guard(() -> {
            JsonArray warps = new JsonArray();
            for (WorldService.WarpInfo wi : w.warpInfos()) {
                if (!w.isBuilt(wi.island())) continue;
                if (wi.requirement() != null && !wi.requirement().isBlank()
                        && !Sky.requirements().check(p, wi.requirement())) continue;
                var idef = w.island(wi.island());
                if (idef != null && idef.requirement != null && !idef.requirement.isBlank()
                        && !Sky.requirements().check(p, idef.requirement)) continue;
                warps.add(wi.id());
            }
            s.add("warps_unlocked", warps);
        });
        guard(() -> {
            List<String[]> m = minions(p);
            if (m == null) return;
            JsonArray arr = new JsonArray();
            for (String[] x : m) {
                JsonObject o = new JsonObject();
                o.addProperty("type", x[0]);
                o.addProperty("tier", Integer.parseInt(x[1]));
                o.addProperty("slot", Integer.parseInt(x[2]));
                arr.add(o);
            }
            s.add("minions", arr);
        });
        guard(() -> {
            if (!Sky.has(Features.Objectives.class)) return;
            String obj = Sky.service(Features.Objectives.class).current(p);
            if (obj != null && !obj.isBlank()) s.addProperty("objective", Names.strip(obj));
        });
        guard(() -> {
            if (!Sky.has(Features.Pets.class)) return;
            Features.Pets pets = Sky.service(Features.Pets.class);
            String type = pets.activeType(p);
            if (type == null) return;
            JsonObject o = new JsonObject();
            o.addProperty("type", type);
            o.addProperty("level", pets.activeLevel(p));
            o.addProperty("rarity", pets.activeRarity(p));
            s.add("pet", o);
        });
        return s;
    }

    private static void inventory(Player p, JsonObject s) {
        PlayerInventory inv = p.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        if (hand != null && hand.getType() != Material.AIR) s.add("held", item(hand, -1));
        JsonArray armor = new JsonArray();
        String[] slots = {"BOOTS", "LEGGINGS", "CHESTPLATE", "HELMET"};
        ItemStack[] arm = inv.getArmorContents();
        for (int i = 0; i < arm.length && i < slots.length; i++) {
            if (arm[i] == null || arm[i].getType() == Material.AIR) continue;
            JsonObject o = item(arm[i], -1);
            o.addProperty("slot", slots[i]);
            armor.add(o);
        }
        s.add("armor", armor);
        // Grouped by sky id so the agent reads "COAL x128", plus the hotbar slots it can select.
        Map<String, JsonObject> grouped = new LinkedHashMap<>();
        int free = 0;
        ItemStack[] storage = inv.getStorageContents();
        JsonArray hotbar = new JsonArray();
        for (int i = 0; i < storage.length; i++) {
            ItemStack it = storage[i];
            if (it == null || it.getType() == Material.AIR) { free++; continue; }
            JsonObject o = item(it, i);
            if (i < 9) hotbar.add(o.deepCopy());
            String key = o.get("id").getAsString();
            JsonObject g = grouped.get(key);
            if (g == null) {
                o.remove("slot");
                grouped.put(key, o);
            } else {
                g.addProperty("count", g.get("count").getAsInt() + it.getAmount());
            }
        }
        JsonArray arr = new JsonArray();
        for (JsonObject o : grouped.values()) arr.add(o);
        s.add("inventory", arr);
        s.add("hotbar", hotbar);
        s.addProperty("inventory_free", free);
        s.addProperty("held_slot", inv.getHeldItemSlot());
    }

    private static JsonObject item(ItemStack it, int slot) {
        JsonObject o = new JsonObject();
        String id = null;
        try { id = Sky.items().idOf(it); } catch (RuntimeException ignored) { }
        if (id == null) id = it.getType().name();
        o.addProperty("id", id);
        String name = null;
        try {
            ItemDef d = Sky.items().def(id);
            if (d != null && d.name != null) name = Names.strip(d.name);
        } catch (RuntimeException ignored) { }
        if (name == null || name.isEmpty()) {
            try {
                var meta = it.getItemMeta();
                if (meta != null && meta.hasDisplayName()) name = Names.plain(meta.displayName());
            } catch (RuntimeException ignored) { }
        }
        if (name == null || name.isEmpty()) name = Names.titleCase(it.getType().name());
        o.addProperty("name", name);
        o.addProperty("count", it.getAmount());
        try {
            Rarity r = Sky.items().rarity(it);
            if (r != null) o.addProperty("rarity", r.name());
        } catch (RuntimeException ignored) { }
        if (slot >= 0) o.addProperty("slot", slot);
        return o;
    }

    private void nearby(Player p, JsonObject s) {
        double r = plugin.config().stateRadius;
        Location me = p.getLocation();
        List<JsonObject> mobs = new ArrayList<>();
        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (!(e instanceof LivingEntity le) || e instanceof Player) continue;
            MobDef d;
            try { d = Sky.mobs().defOf(le); } catch (RuntimeException ex) { continue; }
            if (d == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", d.id);
            o.addProperty("name", Names.mob(d));
            o.addProperty("dist", EventBus.round1(le.getLocation().distance(me)));
            try {
                o.addProperty("health", Math.round(Sky.mobs().health(le)));
                o.addProperty("maxHealth", Math.round(Sky.mobs().maxHealth(le)));
            } catch (RuntimeException ignored) { }
            o.addProperty("level", d.level);
            if (d.has("boss")) o.addProperty("boss", true);
            if (d.has("passive")) o.addProperty("passive", true);
            Location ml = le.getLocation();
            o.addProperty("x", EventBus.round1(ml.getX()));
            o.addProperty("y", EventBus.round1(ml.getY()));
            o.addProperty("z", EventBus.round1(ml.getZ()));
            mobs.add(o);
        }
        mobs.sort(Comparator.comparingDouble(o -> o.get("dist").getAsDouble()));
        JsonArray ma = new JsonArray();
        for (int i = 0; i < mobs.size() && i < 12; i++) ma.add(mobs.get(i));
        s.add("nearby_mobs", ma);

        List<JsonObject> npcs = new ArrayList<>();
        for (NpcDef d : Sky.npcs().all()) {
            Entity ent;
            try { ent = Sky.npcs().entity(d.id); } catch (RuntimeException ex) { continue; }
            if (ent == null || !ent.isValid() || ent.getWorld() != me.getWorld()) continue;
            double dist = ent.getLocation().distance(me);
            if (dist > r * 2) continue;   // NPCs are destinations: look a little further
            JsonObject o = new JsonObject();
            o.addProperty("id", d.id);
            o.addProperty("name", Names.strip(d.name));
            if (d.title != null) o.addProperty("title", Names.strip(d.title));
            o.addProperty("dist", EventBus.round1(dist));
            Location nl = ent.getLocation();
            o.addProperty("x", EventBus.round1(nl.getX()));
            o.addProperty("y", EventBus.round1(nl.getY()));
            o.addProperty("z", EventBus.round1(nl.getZ()));
            npcs.add(o);
        }
        npcs.sort(Comparator.comparingDouble(o -> o.get("dist").getAsDouble()));
        JsonArray na = new JsonArray();
        for (int i = 0; i < npcs.size() && i < 12; i++) na.add(npcs.get(i));
        s.add("nearby_npcs", na);

        JsonArray others = new JsonArray();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == p || plugin.config().isCamera(o) || o.getWorld() != me.getWorld()) continue;
            double dist = o.getLocation().distance(me);
            if (dist > 64) continue;
            JsonObject j = new JsonObject();
            j.addProperty("name", o.getName());
            j.addProperty("dist", EventBus.round1(dist));
            others.add(j);
        }
        s.add("nearby_players", others);
    }

    private void guard(Runnable r) {
        try { r.run(); } catch (Throwable t) {
            // One missing service must not blank the state; log once per kind of failure.
            String k = t.getClass().getSimpleName() + ":" + t.getMessage();
            if (warned.add(k)) plugin.getLogger().warning("state section failed (once): " + t);
        }
    }

    private final java.util.Set<String> warned = ConcurrentHashMap.newKeySet();
}
