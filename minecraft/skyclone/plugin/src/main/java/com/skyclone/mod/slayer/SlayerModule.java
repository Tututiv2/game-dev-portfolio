package com.skyclone.mod.slayer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SLAYER (research 04 sec 2): six slayer bosses with every tier and ability, quests, levels and their rewards, the RNG
 * meter, minibosses, slayer drops and armour, the usable slayer items, the Slayer master and the Slayer Hotline.
 * Provides {@link Features.Slayers} and the requirement prefixes {@code slayer:<type>:<lvl>} and
 * {@code slayerkill:<type>:<tier>}.
 */
public final class SlayerModule implements GameModule, Listener {

    static SlayerModule INSTANCE;

    SlayerCatalog catalog = new SlayerCatalog();
    /** Boss and minion entity uuid -> the fight it belongs to. */
    final Map<UUID, BossFight> fightIndex = new HashMap<>();
    Quests quests;
    SlayerLoot loot;
    SlayerMenus menus;
    SlayerGear gear;
    SlayerItems items;
    Cocoon cocoon;
    final Debuffs debuffs = new Debuffs();
    int itemsLoaded, mobsLoaded, recipesLoaded, fallbackLoaded, fallbackSkipped;

    @Override public String id() { return "slayer"; }

    @Override
    public void enable() {
        INSTANCE = this;
        SlayerDials.init();
        catalog = SlayerCatalog.read(Sky.plugin().getResource("data/slayer/slayers.json"));
        itemsLoaded = Sky.items().loadJson("slayer", "items.json");
        mobsLoaded = Sky.mobs().loadJson("slayer", "mobs.json");
        recipesLoaded = Sky.recipes().loadJson("slayer", "recipes.json");

        quests = new Quests(this);
        loot = new SlayerLoot(this);
        gear = new SlayerGear(this);
        items = new SlayerItems(this);
        menus = new SlayerMenus(this);
        cocoon = new Cocoon(this);

        registerBehaviours();
        Sky.provide(Features.Slayers.class, new Service());
        Sky.requirements().register("slayer", new LevelResolver());
        Sky.requirements().register("slayerkill", new KillResolver());

        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(quests, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(gear, Sky.plugin());
        gear.register();
        items.register();

        Sky.stats().addProvider("slayer_levels", this::levelStats);
        Sky.stats().addProvider("slayer_debuffs", debuffs::contribute);

        Sky.npcs().register(npcDef(), p -> {
            List<String> lines = greeting(p);
            Runnable open = () -> {
                Sky.service(Features.Objectives.class).complete(p, "talk_slayer_master");
                menus.openMain(p);
            };
            if (lines.isEmpty()) open.run();
            else Sky.npcs().talk(p, "slayer_master", lines, open);
        });

        Sky.commands().register(SkyCommand.of("slayer", "Open the Slayer menu (/slayer summon | cancel | rng | shop | <type>)")
                .alias("slayers").category("Combat").usage("[summon|cancel|rng|shop|stats|<type>]").playerOnly()
                .run((s, a) -> playerCommand((Player) s, a))
                .tab((s, a) -> a.length <= 1 ? tabTypes(List.of("summon", "cancel", "rng", "shop", "stats", "help")) : List.of()));
        SlayerAdmin.register(this);
        SlayerTests.register(this);

        Sky.sidebar().section("slayer", 200, quests::sidebar);
        Sky.vitals().actionBarSegment("slayer", 300, quests::actionBar);
        Sky.sidebar().tabWidget("slayer", 160, this::tabLines);

        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), quests::tick, 5L, 5L);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), items::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), cocoon::tick, 20L, 20L);
    }

    @Override
    public void postEnable() {
        registerFallbackItems();
        registerLateRecipes();
    }

    /** Recipes whose ingredients belong to modules that may be missing: only when every ingredient exists. */
    private void registerLateRecipes() {
        InputStream in = Sky.plugin().getResource("data/slayer/recipes_late.json");
        if (in == null) return;
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonArray arr = JsonParser.parseReader(r).getAsJsonArray();
            Gson g = new Gson();
            for (JsonElement e : arr) {
                com.skyclone.api.item.Recipe rec;
                try { rec = g.fromJson(e, com.skyclone.api.item.Recipe.class); } catch (RuntimeException ex) { continue; }
                if (rec == null || rec.result == null || !Sky.items().exists(rec.result)) continue;
                boolean ok = true;
                for (String cell : rec.grid) {
                    if (cell == null || cell.isBlank()) continue;
                    String id = Sky.items().exists(cell) ? cell : cell.contains(":") ? cell.substring(0, cell.lastIndexOf(':')) : cell;
                    if (!Sky.items().exists(id)) { ok = false; lateSkipped.add(rec.result + " (needs " + id + ")"); break; }
                }
                if (ok) { Sky.recipes().register(rec); recipesLoaded++; }
            }
        } catch (Exception ex) {
            Sky.log().warning("[slayer] recipes_late.json: " + ex.getMessage());
        }
    }

    final List<String> lateSkipped = new ArrayList<>();

    @Override
    public void disable() {
        try { quests.shutdown(); } catch (RuntimeException ignored) { }
        try { items.shutdown(); } catch (RuntimeException ignored) { }
        try { cocoon.shutdown(); } catch (RuntimeException ignored) { }
    }

    // ---- data helpers -----------------------------------------------------------------------------

    /** Cosmetic runes, dyes and the pet skin: ours unless another module made them first. */
    private void registerFallbackItems() {
        InputStream in = Sky.plugin().getResource("data/slayer/items_fallback.json");
        if (in == null) return;
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonArray arr = JsonParser.parseReader(r).getAsJsonArray();
            Gson g = new Gson();
            for (JsonElement e : arr) {
                ItemDef def;
                try { def = g.fromJson(e, ItemDef.class); } catch (RuntimeException ex) { continue; }
                if (def == null || def.id == null) continue;
                if (Sky.items().exists(def.id) && !isAutoVanilla(def.id)) { fallbackSkipped++; continue; }
                Sky.items().register(def);
                fallbackLoaded++;
            }
        } catch (Exception ex) {
            Sky.log().warning("[slayer] items_fallback.json: " + ex.getMessage());
        }
    }

    private static boolean isAutoVanilla(String id) {
        try { org.bukkit.Material.valueOf(id); return true; } catch (IllegalArgumentException e) { return false; }
    }

    private NpcDef npcDef() {
        NpcDef d = NpcDef.of("slayer_master", "&5" + catalog.npcName, "hub", "npc.slayer_master").title("&e&lSLAYER");
        d.hand = "NETHERITE_SWORD";
        d.lines.add("&7Bounties on every horror");
        return d;
    }

    /**
     * The introduction, ONCE per profile: it used to play on every click until the first quest was bought, so a
     * player reading the tier pages before paying heard the same three lines each time (wave-5 regression
     * playtest). Like the reference's slayer master, the first talk is the intro and every later click opens
     * the board. {@link SlayerData#introHeard} is set when the intro starts, so walking off mid-intro still
     * counts as heard.
     */
    List<String> greeting(Player p) {
        if (!Sky.profiles().isReady(p)) return List.of("&fHunters only.");
        return introOnce(quests.data(p));
    }

    /** The intro lines the first time (and marks them heard), else empty: regulars go straight to the board. */
    static List<String> introOnce(SlayerData d) {
        if (d.questsStarted == 0 && !d.introHeard) {
            d.introHeard = true;
            return List.of("&fYou look like you can swing a sword.",
                    "&fThe island is crawling with horrors. I pay to see them gone.",
                    "&fPick a target, pay the bounty fee, and slay what crawls out.");
        }
        return List.of();
    }

    private void playerCommand(Player p, String[] a) {
        if (!Sky.profiles().isReady(p)) return;
        if (a.length == 0) { menus.openMain(p); return; }
        String sub = a[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "summon" -> quests.summonReady(p);
            case "cancel" -> menus.confirmCancel(p);
            case "rng" -> {
                SlayerCatalog.Type t = a.length > 1 ? catalog.type(a[1]) : null;
                if (t != null) menus.openRng(p, t); else menus.openRngAll(p);
            }
            case "shop" -> menus.openShop(p);
            case "stats" -> menus.openStats(p);
            case "help" -> menus.openHelp(p);
            default -> {
                SlayerCatalog.Type t = catalog.type(sub);
                if (t != null) menus.openType(p, t); else menus.openMain(p);
            }
        }
    }

    List<String> tabTypes(List<String> extra) {
        List<String> out = new ArrayList<>(extra);
        for (SlayerCatalog.Type t : catalog.types) out.add(t.id);
        return out;
    }

    private List<String> tabLines(Player p) {
        if (!Sky.profiles().isReady(p)) return List.of();
        List<String> out = new ArrayList<>();
        out.add("&5&lSlayers:");
        SlayerData d = quests.data(p);
        for (SlayerCatalog.Type t : catalog.types) {
            int lvl = level(p, t);
            if (lvl == 0 && d.totalKills(t.id) == 0) continue;
            out.add(" &" + t.color + t.mobWord + ": &f" + lvl);
        }
        if (out.size() == 1) out.add(" &7None yet");
        return out;
    }

    // ---- levels ---------------------------------------------------------------------------------

    int level(Player p, SlayerCatalog.Type t) {
        if (t == null || !Sky.profiles().isReady(p)) return 0;
        return SlayerMath.level(t.xp, quests.data(p).xp(t.id));
    }

    /** Add slayer XP; announce level-ups, award SkyBlock XP, refresh stats. */
    void addXp(Player p, SlayerCatalog.Type t, double amount, String reason) {
        if (t == null || amount == 0) return;
        SlayerData d = quests.data(p);
        double before = d.xp(t.id);
        double after = Math.max(0, before + amount);
        d.xp.put(t.id, after);
        long next = SlayerMath.nextThreshold(t.xp, after);
        int lvl = SlayerMath.level(t.xp, after);
        if (amount > 0) {
            Msg.send(p, "   &e" + t.slayerName() + " LVL " + lvl + " &5- " + (next < 0 ? "&a&lLVL MAXED OUT!"
                    : "&7Next LVL in &d" + Text.commasInt(next - after) + " XP&7!") + " &8(+" + Text.commas(Math.floor(amount * 10) / 10) + ")");
        }
        checkLevelAnnouncements(p);
        Sky.stats().invalidate(p);
    }

    /** Announce every level above the last announced one (also catches admin changes and offline grants). */
    void checkLevelAnnouncements(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        SlayerData d = quests.data(p);
        for (SlayerCatalog.Type t : catalog.types) {
            int lvl = SlayerMath.level(t.xp, d.xp(t.id));
            int shown = d.announced.getOrDefault(t.id, 0);
            if (lvl <= shown) {
                if (lvl < shown) d.announced.put(t.id, lvl);
                continue;
            }
            for (int l = shown + 1; l <= lvl; l++) announce(p, t, l);
            d.announced.put(t.id, lvl);
        }
    }

    private void announce(Player p, SlayerCatalog.Type t, int lvl) {
        SlayerCatalog.Reward r = lvl - 1 < t.rewards.size() ? t.rewards.get(lvl - 1) : null;
        List<String> body = new ArrayList<>();
        // Rank names mix nouns and adjectives ("Apprentice", "Skilled"), so no article: "You are now a Skilled!"
        body.add("&7New rank: &" + t.color + t.levelName(lvl) + "&7!");
        body.add("");
        body.add("&a&lREWARDS");
        if (r != null) {
            for (Map.Entry<String, Double> e : r.stats.entrySet()) {
                Stat s = Stat.parse(e.getKey());
                if (s != null) body.add("  &8+&a" + Text.commas(e.getValue()) + " " + s.label());
            }
            for (String n : r.notes) body.add("  &7" + n);
        }
        for (String rec : menus.unlocksAt(t, lvl)) body.add("  &7Recipe: " + rec);
        String from = lvl - 1 <= 0 ? "0" : SlayerMath.roman(lvl - 1);
        Msg.box(p, "&5&lSLAYER LEVEL UP! &e" + t.slayerName() + " " + from + " &8➜ &e" + SlayerMath.roman(lvl), body);
        Fx.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.9f);
        if (r != null && r.sbXp > 0) {
            Sky.service(Features.SkyLevel.class).award(p, "slayer:" + t.id + ":" + lvl, r.sbXp, t.slayerName() + " " + lvl);
        }
        if ("wolf".equals(t.id) && lvl == 3) Msg.send(p, "&aClaim your free &eSlayer Hotline &ain the Slayer Shop!");
    }

    private void levelStats(Player p, StatMap into) {
        if (!Sky.profiles().isReady(p)) return;
        SlayerData d = quests.data(p);
        for (SlayerCatalog.Type t : catalog.types) {
            int lvl = SlayerMath.level(t.xp, d.xp(t.id));
            for (int i = 0; i < lvl && i < t.rewards.size(); i++) {
                for (Map.Entry<String, Double> e : t.rewards.get(i).stats.entrySet()) {
                    Stat s = Stat.parse(e.getKey());
                    if (s != null) into.add(s, e.getValue());
                }
            }
        }
    }

    // ---- mob behaviours ---------------------------------------------------------------------------

    private void registerBehaviours() {
        Sky.mobs().registerBehaviour("slayer_boss", new MobBehaviour() {
            @Override public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
                BossFight f = fightIndex.get(mob.getUniqueId());
                if (f == null || f.ended) return;
                if (f.boss != null && f.boss.getUniqueId().equals(mob.getUniqueId())) f.damaged(ctx);
            }
            @Override public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
                BossFight f = fightIndex.get(mob.getUniqueId());
                if (f != null && !f.ended) f.attacking(target, ctx);
            }
        });
        Sky.mobs().registerBehaviour("slayer_minion", new MobBehaviour() {
            @Override public void onSpawn(LivingEntity mob, MobDef def) {
                if (mob instanceof Slime s) {
                    try { s.setSize(1); } catch (RuntimeException ignored) { }
                }
            }
            @Override public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
                BossFight f = fightIndex.get(mob.getUniqueId());
                if (f != null && !f.ended) f.minionDamaged(mob, ctx);
            }
            @Override public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
                BossFight f = fightIndex.get(mob.getUniqueId());
                if (f != null && !f.ended) f.minionAttacking(mob, target, ctx);
            }
        });
    }

    // ---- small listeners --------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossTeleport(EntityTeleportEvent e) {
        BossFight f = fightIndex.get(e.getEntity().getUniqueId());
        if (f != null || (cocoon != null && cocoon.isHatched(e.getEntity().getUniqueId()))) e.setCancelled(true);       // bosses only move by their own mechanics
    }

    /**
     * Bosses and their minions only hurt players through scripted mechanics and their melee: a vanilla blaze fireball,
     * ghast charge or skeleton arrow would bypass the slayer numbers (and set fire to the arena).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFightProjectile(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (fightIndex.isEmpty()) return;
        if (e.getEntity().getShooter() instanceof LivingEntity le && fightIndex.containsKey(le.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteractMinion(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof LivingEntity le)) return;
        BossFight f = fightIndex.get(le.getUniqueId());
        if (f instanceof VampireFight vf && vf.isSpring(le)) {
            e.setCancelled(true);
            if (e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) vf.springClick(e.getPlayer(), false);
        }
    }

    @EventHandler
    public void onJump(com.destroystokyo.paper.event.player.PlayerJumpEvent e) {
        BossFight f = quests.fights.get(e.getPlayer().getUniqueId());
        if (f instanceof VampireFight vf) vf.playerJumped(e.getPlayer());
    }

    @EventHandler
    public void onSneakCommand(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        BossFight f = quests.fights.get(e.getPlayer().getUniqueId());
        if (f instanceof VampireFight vf) vf.playerSneaked(e.getPlayer());
    }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) {
        debuffs.clear(e.player);
    }

    // ---- feature service & requirements -----------------------------------------------------------

    private final class Service implements Features.Slayers {
        @Override public int level(Player p, String type) { return SlayerModule.this.level(p, catalog.type(type)); }
        @Override public double xp(Player p, String type) {
            return Sky.profiles().isReady(p) ? quests.data(p).xp(type == null ? "" : type.toLowerCase(java.util.Locale.ROOT)) : 0;
        }
        @Override public boolean questActive(Player p) { return Sky.profiles().isReady(p) && quests.data(p).hasQuest(); }
        @Override public void openMenu(Player p) { menus.openMain(p); }
    }

    /**
     * slayer:&lt;type&gt;:&lt;level&gt; (also accepts the reference's "eman"), and (wave 5, soulflow request 1)
     * slayer:mixin:&lt;MIXIN_ID&gt; = that mixin is active right now (End Portal Fumes' +30% Overflow is read this way).
     */
    private final class LevelResolver implements Requirements.Resolver {
        @Override public boolean check(Player p, String[] parts) {
            if (parts.length < 3) return false;
            if ("mixin".equalsIgnoreCase(parts[1].trim())) {
                return Sky.profiles().isReady(p) && items.mixinActive(p, parts[2].trim().toUpperCase(java.util.Locale.ROOT));
            }
            SlayerCatalog.Type t = catalog.type(alias(parts[1]));
            try { return t != null && level(p, t) >= Integer.parseInt(parts[2].trim()); } catch (NumberFormatException e) { return false; }
        }
        @Override public String describe(String[] parts) {
            if (parts.length >= 3 && "mixin".equalsIgnoreCase(parts[1].trim())) {
                ItemDef def = Sky.items().def(parts[2].trim().toUpperCase(java.util.Locale.ROOT));
                return "&aan active " + (def == null ? Text.titleCase(parts[2].trim()) : Text.strip(def.name));
            }
            if (parts.length < 3) return "&cSlayer ?";
            SlayerCatalog.Type t = catalog.type(alias(parts[1]));
            return "&5" + (t == null ? Text.titleCase(parts[1]) + " Slayer" : t.slayerName()) + " " + parts[2].trim();
        }
    }

    /** slayerkill:&lt;type&gt;:&lt;tier&gt; = has defeated that tier (or higher) at least once. */
    private final class KillResolver implements Requirements.Resolver {
        @Override public boolean check(Player p, String[] parts) {
            if (parts.length < 3 || !Sky.profiles().isReady(p)) return false;
            try { return quests.data(p).highestTierKilled(alias(parts[1])) >= Integer.parseInt(parts[2].trim()); } catch (NumberFormatException e) { return false; }
        }
        @Override public String describe(String[] parts) {
            if (parts.length < 3) return "&cdefeating a slayer boss";
            SlayerCatalog.Type t = catalog.type(alias(parts[1]));
            int tier;
            try { tier = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException e) { tier = 1; }
            return "&adefeating a " + (t == null ? parts[1] : "&" + t.color + t.bossName(tier)) + " " + SlayerMath.roman(tier);
        }
    }

    static String alias(String s) {
        String k = s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (k) {
            case "eman", "ender" -> "enderman";
            case "revenant", "zombies" -> "zombie";
            case "tarantula", "spiders" -> "spider";
            case "sven", "wolves" -> "wolf";
            case "inferno" -> "blaze";
            case "riftstalker", "vamp" -> "vampire";
            default -> k;
        };
    }
}
