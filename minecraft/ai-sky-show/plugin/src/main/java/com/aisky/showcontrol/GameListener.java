package com.aisky.showcontrol;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.world.gen.Layout;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every game event the feed reports, turned into CONTRACT §3 events with a drama hint and a line a
 * human (or a director model) can read. All SkyEvents arrive on the main thread; chat is async and
 * only touches thread-safe state. Listeners never mutate the game except: MobKill coins (ruleset
 * knob), the freeze (movement + damage), and the post-reset inventory wipe.
 */
final class GameListener implements Listener {

    private final ShowControlPlugin plugin;
    private final Map<String, Long> debounce = new ConcurrentHashMap<>();
    private final long bootMs = System.currentTimeMillis();

    GameListener(ShowControlPlugin plugin) { this.plugin = plugin; }

    private ShowConfig cfg() { return plugin.config(); }

    private EventBus.Ev ev(String type) { return plugin.bus().ev(type); }

    /** True when {@code key} has not fired within {@code seconds}; arms it. */
    private boolean debounced(String key, double seconds) {
        long now = System.currentTimeMillis();
        Long last = debounce.get(key);
        if (last != null && now - last < seconds * 1000) return false;
        debounce.put(key, now);
        return true;
    }

    // ---- join / quit / profile ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent e) {
        plugin.resets().onPreLogin(e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinEarly(PlayerJoinEvent e) {
        // Wipe vanilla inventory of an account whose profile /show reset closed, BEFORE SkyClone's
        // join handling hands out the SkyBlock menu / starter items (ProfileReady is a tick later).
        plugin.resets().onJoin(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.camera().onJoin(p);
        if (cfg().isCamera(p)) return;
        ev("join").player(p.getName()).drama(1).loc(p.getLocation())
                .text(p.getName() + " joined the server").emit();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.snapshots().markOffline(p.getName());
        if (cfg().isCamera(p)) return;
        plugin.gather().flush(p.getName());
        ev("quit").player(p.getName()).drama(2).loc(p.getLocation())
                .text(p.getName() + " left the server").emit();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProfileReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        if (!cfg().tracked(p)) return;
        String profile = "";
        try { profile = Sky.profiles().profileName(p); } catch (RuntimeException ignored) { }
        ev("profile_ready").player(p.getName()).drama(e.firstJoin ? 2 : 1).loc(p.getLocation())
                .data("first_join", e.firstJoin).data("profile", profile)
                .text(e.firstJoin ? p.getName() + " wakes up on a brand-new island" : p.getName() + " is back in the game")
                .emit();
    }

    // ---- chat (ASYNC) ---------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (cfg().isCamera(p)) return;
        String msg = Names.plain(e.message());
        if (msg.isEmpty()) return;
        // Async: no Bukkit world reads here - the location comes from the last main-thread snapshot.
        ev("chat").player(p.getName()).drama(2).loc(plugin.snapshots().lastLoc(p.getName()))
                .data("message", msg).text(p.getName() + ": " + msg).emit();
    }

    // ---- combat --------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onMobKillCoins(SkyEvents.MobKill e) {
        // Ruleset knob: SkyClone has no dial for mob coins. Only cast kills, before core pays.
        double m = plugin.rulesets().mobCoinMult;
        if (m != 1.0 && e.killer != null && cfg().tracked(e.killer) && e.coins > 0) e.coins = e.coins * m;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobKill(SkyEvents.MobKill e) {
        Player k = e.killer;
        if (k == null || !cfg().tracked(k)) return;
        MobDef d = e.def;
        boolean boss = d != null && d.has("boss");
        boolean sea = d != null && d.has("sea_creature");
        String name = Names.mob(d);
        int drama = boss ? 7 : sea ? 3 : (d != null && d.level >= 30) ? 2 : 1;
        ev("mob_kill").player(k.getName()).drama(drama).loc(e.location)
                .data("mob_id", d == null ? null : d.id).data("mob_name", name)
                .data("level", d == null ? 0 : d.level).data("coins", Math.round(e.coins * 10) / 10.0)
                .data("combat_xp", Math.round(e.combatXp * 10) / 10.0).data("boss", boss)
                .text(k.getName() + (boss ? " defeated the boss " : " killed " + Names.article(name) + " ") + name).emit();
        plugin.first("first_kill", k, "first kill", 4, e.location);
        if (boss) plugin.first("first_boss_kill", k, "first boss kill", 8, e.location);
        if (sea) plugin.first("first_sea_creature_kill", k, "first sea creature slain", 5, e.location);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageFreeze(SkyEvents.Damage e) {
        if (!plugin.episode().frozen) return;
        Player v = e.ctx.playerVictim();
        if (v != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Player v = ctx.playerVictim();
        if (v == null || !cfg().tracked(v)) return;
        double max, hp;
        try { max = Sky.vitals().maxHealth(v); hp = Sky.vitals().health(v); } catch (RuntimeException ex) { return; }
        if (max <= 0) return;
        double dmg = ctx.finalDamage;
        if (dmg < max * cfg().bigHitFraction) return;
        if (!debounced("bighit:" + v.getName(), cfg().bigHitDebounceSeconds)) return;
        String from = attackerName(ctx.attacker, ctx.kind);
        ev("big_hit").player(v.getName()).drama(dmg >= hp ? 6 : 4).loc(v.getLocation())
                .data("damage", Math.round(dmg)).data("from", from)
                .data("health_before", Math.round(hp)).data("max", Math.round(max))
                .text(v.getName() + " took a huge hit: " + Math.round(dmg) + " damage from " + from).emit();
    }

    private static String attackerName(LivingEntity a, DamageContext.Kind kind) {
        if (a == null) return kind == null ? "the environment" : kind.name().toLowerCase(Locale.ROOT);
        if (a instanceof Player p) return p.getName();
        try {
            MobDef d = Sky.mobs().defOf(a);
            if (d != null) return Names.mob(d);
        } catch (RuntimeException ignored) { }
        return Names.titleCase(a.getType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(SkyEvents.SkyDeath e) {
        Player p = e.player;
        if (!cfg().tracked(p)) return;
        double purse = 0;
        try { purse = Sky.economy().purse(p); } catch (RuntimeException ignored) { }
        long lost = (long) Math.floor(Math.max(0, Math.min(purse, Double.isNaN(e.coinsLost) ? 0 : e.coinsLost)));
        String cause = describeCause(e.cause);
        ev("death").player(p.getName()).drama(lost >= 1000 ? 7 : 5).loc(p.getLocation())
                .data("cause", e.cause).data("cause_text", cause).data("coins_lost", lost)
                .text(p.getName() + " died (" + cause + ")" + (lost > 0 ? " and lost " + Names.coins(lost) + " coins" : "")).emit();
        plugin.first("first_death", p, "first death", 6, p.getLocation());
    }

    private static String describeCause(String cause) {
        if (cause == null || cause.isBlank()) return "died";
        if (cause.startsWith("mob:")) return "killed by " + Names.mob(cause.substring(4));
        return cause.replace('_', ' ');
    }

    // ---- items / gathering / skills / collections / coins ---------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemGain(SkyEvents.ItemGain e) {
        // Cancelled = a sack took it; it was still gained, so listen regardless.
        Player p = e.player;
        if (!cfg().tracked(p) || e.itemId == null) return;
        Rarity r = Rarity.COMMON;
        try {
            if (e.stack != null) r = Sky.items().rarity(e.stack);
            else if (Sky.items().def(e.itemId) != null) r = Sky.items().def(e.itemId).rarity;
        } catch (RuntimeException ignored) { }
        if (r == null) r = Rarity.COMMON;
        boolean rare = r.ordinal() >= Rarity.RARE.ordinal();
        boolean first = plugin.memory().firstItem(p.getName(), e.itemId);
        if (!rare && !first) return;
        int drama = switch (r) {
            case COMMON -> 1;
            case UNCOMMON -> 2;
            case RARE -> 4;
            case EPIC -> 6;
            case LEGENDARY -> 8;
            default -> 9;
        };
        String name = Names.item(e.itemId);
        String src = e.source == null ? "OTHER" : e.source.name();
        boolean natural = e.source != null && e.source.natural;
        String text = rare && natural
                ? r.display() + " DROP! " + p.getName() + " got " + name + (e.amount > 1 ? " x" + e.amount : "")
                : p.getName() + " got " + (e.amount > 1 ? e.amount + "x " : Names.article(name) + " ") + name
                    + (first ? " for the first time" : "");
        ev("item_gain").player(p.getName()).drama(drama).loc(p.getLocation())
                .data("item_id", e.itemId).data("item_name", name).data("amount", e.amount)
                .data("rarity", r.name()).data("source", src).data("first", first).text(text).emit();
        if (rare && natural && e.source != com.skyclone.api.loot.LootSource.ADMIN) {
            plugin.first("first_rare_drop", p, "first rare drop (" + name + ")", 7, p.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGather(SkyEvents.Gather e) {
        if (!cfg().tracked(e.player)) return;
        plugin.gather().add(e.player.getName(), e.kind == null ? "OTHER" : e.kind.name(), e.what, e.amount, e.location);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSkillLevel(SkyEvents.SkillLevelUp e) {
        Player p = e.player;
        if (!cfg().tracked(p) || e.skill == null) return;
        int lvl = e.newLevel;
        String skill = e.skill.display;
        int drama = lvl >= 15 ? 6 : lvl >= 10 ? 5 : lvl >= 5 ? 4 : 3;
        ev("skill_level_up").player(p.getName()).drama(drama).loc(p.getLocation())
                .data("skill", e.skill.name()).data("skill_name", skill).data("level", lvl)
                .text(p.getName() + " reached " + skill + " " + lvl).emit();
        plugin.first("first_skill_level", p, "first skill level-up (" + skill + " " + lvl + ")", 4, p.getLocation());
        if (lvl >= 5) plugin.first("first_skill_5", p, "first to reach skill level 5 (" + skill + ")", 6, p.getLocation());
        if (lvl >= 10) plugin.first("first_skill_10", p, "first to reach skill level 10 (" + skill + ")", 7, p.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCollectionGain(SkyEvents.CollectionGain e) {
        if (!cfg().tracked(e.player) || e.collection == null) return;
        plugin.memory().collection(e.player.getName(), e.collection, e.newTotal);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCollectionTier(SkyEvents.CollectionTierUp e) {
        Player p = e.player;
        if (!cfg().tracked(p) || e.collection == null) return;
        String name = Names.item(e.collection);
        ev("collection_tier_up").player(p.getName()).drama(e.tier >= 5 ? 4 : 3).loc(p.getLocation())
                .data("collection", e.collection).data("collection_name", name).data("tier", e.tier)
                .text(p.getName() + " unlocked " + name + " Collection " + roman(e.tier)).emit();
        plugin.first("first_collection_tier", p, "first collection tier (" + name + ")", 5, p.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCoins(SkyEvents.CoinsChange e) {
        Player p = e.player;
        if (!cfg().tracked(p)) return;
        if (e.delta <= 0) return;
        long m = plugin.memory().crossMilestones(p.getName(), e.purse, cfg().coinMilestones);
        if (m < 0) return;
        int drama = m >= 1_000_000 ? 8 : m >= 100_000 ? 6 : m >= 10_000 ? 4 : m >= 5_000 ? 3 : 2;
        ev("coins_milestone").player(p.getName()).drama(drama).loc(p.getLocation())
                .data("purse", Math.floor(e.purse)).data("milestone", m).data("reason", e.reason)
                .text(p.getName() + " now has " + Names.coins(e.purse) + " coins in the purse").emit();
        if (m >= 10_000) plugin.first("first_coins_" + m, p, "first to " + Names.coins(m) + " coins", 6, p.getLocation());
    }

    // ---- world ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onIsland(SkyEvents.IslandChange e) {
        Player p = e.player;
        if (!cfg().tracked(p)) return;
        String to = e.to;
        int drama = "hub".equals(to) ? 3 : (to != null && !"private_island".equals(to)) ? 3 : 2;
        ev("island_change").player(p.getName()).drama(drama).loc(p.getLocation())
                .data("from", e.from).data("to", to)
                .data("from_name", e.from == null ? null : Names.island(e.from))
                .data("to_name", to == null ? null : Names.island(to))
                .text(p.getName() + " arrived at " + Names.island(to)).emit();
        if (to != null && !"private_island".equals(to)) {
            String island = Names.island(to);
            int d = "hub".equals(to) ? 6 : 5;
            plugin.first("first_island:" + to, p, "first to reach " + ("hub".equals(to) ? "the Hub" : island), d, p.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onZone(SkyEvents.ZoneChange e) {
        Player p = e.player;
        if (!cfg().tracked(p)) return;
        Layout.Zone to = e.to, from = e.from;
        if (to == null) return;  // left every zone: not worth a feed line
        String toName = Names.strip(to.displayName());
        ev("zone_change").player(p.getName()).drama(1).loc(p.getLocation())
                .data("from", from == null ? null : from.id()).data("to", to.id()).data("island", e.island)
                .data("from_name", from == null ? null : Names.strip(from.displayName())).data("to_name", toName)
                .text(p.getName() + " entered " + toName).emit();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbility(SkyEvents.AbilityUsed e) {
        Player p = e.player;
        if (!cfg().tracked(p) || e.ability == null) return;
        String id = e.ability.id == null ? "ability" : e.ability.id;
        if (!debounced("ability:" + p.getName() + ":" + id, cfg().abilityDebounceSeconds)) return;
        String name = e.ability.name == null ? Names.titleCase(id) : Names.strip(e.ability.name);
        ev("ability_used").player(p.getName()).drama(2).loc(p.getLocation())
                .data("ability", id).data("ability_name", name)
                .text(p.getName() + " used " + name).emit();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFish(SkyEvents.FishCaught e) {
        Player p = e.player;
        if (!cfg().tracked(p)) return;
        String name = e.seaCreature ? Names.mob(e.what) : Names.item(e.what);
        int drama = e.seaCreature ? 4 : e.treasure
                ? ("outstanding".equalsIgnoreCase(e.quality) ? 6 : "great".equalsIgnoreCase(e.quality) ? 4 : 3) : 1;
        String text = e.seaCreature ? p.getName() + " fished up a sea creature: " + name
                : e.treasure ? p.getName() + " fished up " + (e.quality == null ? "" : e.quality + " ") + "treasure: " + name
                : p.getName() + " caught " + name;
        ev("fish_caught").player(p.getName()).drama(drama).loc(p.getLocation())
                .data("what", e.what).data("what_name", name).data("sea_creature", e.seaCreature)
                .data("treasure", e.treasure).data("quality", e.quality).data("lava", e.lava).text(text).emit();
        if (e.seaCreature) plugin.first("first_sea_creature", p, "first sea creature hooked", 5, p.getLocation());
    }

    // ---- calendar / bosses -------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCalendarStart(SkyEvents.CalendarStart e) {
        // SkyClone re-fires Start for every event already running on the first tick after boot.
        boolean boot = System.currentTimeMillis() - bootMs < 60_000;
        String name = Names.calendarEvent(e.eventId);
        ev("calendar_start").drama(boot ? 1 : 3).data("event", e.eventId).data("event_name", name)
                .data("at_boot", boot).text(name + " has started").emit();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCalendarEnd(SkyEvents.CalendarEnd e) {
        String name = Names.calendarEvent(e.eventId);
        ev("calendar_end").drama(2).data("event", e.eventId).data("event_name", name)
                .text(name + " has ended").emit();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le) || le instanceof Player
                || le instanceof org.bukkit.entity.ArmorStand) return;
        // SkyClone tags the entity as a sky mob right after spawning it: look one tick later.
        Bukkit.getScheduler().runTask(plugin, () -> checkBoss(le));
    }

    private void checkBoss(LivingEntity le) {
        if (!le.isValid()) return;
        MobDef d;
        try { d = Sky.mobs().defOf(le); } catch (RuntimeException ex) { return; }
        if (d == null || !d.has("boss")) return;
        String owner = null;
        try {
            UUID o = Sky.mobs().owner(le);
            if (o != null) { Player op = Bukkit.getPlayer(o); if (op != null) owner = op.getName(); }
        } catch (RuntimeException ignored) { }
        EventBus.Ev ev = ev("boss_spawn").drama(7).loc(le.getLocation())
                .data("mob_id", d.id).data("name", Names.mob(d)).data("level", d.level)
                .data("owner", owner);
        if (owner != null) ev.player(owner);
        else {
            for (Entity n : le.getNearbyEntities(48, 32, 48)) {
                if (n instanceof Player np && cfg().tracked(np)) ev.player(np.getName());
            }
        }
        ev.text(Names.mob(d) + " has spawned" + (owner != null ? " for " + owner : "")).emit();
    }

    // ---- freeze -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!plugin.episode().frozen) return;
        Player p = e.getPlayer();
        if (cfg().isCamera(p)) return;
        Location f = e.getFrom(), t = e.getTo();
        if (t == null) return;
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) {
            Location keep = f.clone();
            keep.setYaw(t.getYaw());
            keep.setPitch(t.getPitch());
            e.setTo(keep);
        }
    }

    static String roman(int n) {
        if (n <= 0) return String.valueOf(n);
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) while (n >= v[i]) { n -= v[i]; b.append(s[i]); }
        return b.toString();
    }
}
