package com.skyclone.mod.collections;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.config.Dials;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.util.Requirements;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Collections: every item a player gathers counts toward its collection; tiers unlock recipes
 * (requirement keys {@code collection:<ITEM>:<tier>} that the crafting side checks), perks and
 * SkyBlock XP. Provides {@link Features.Collections}; owns the requirement prefixes
 * {@code collection} and {@code collection_discount}.
 */
public final class CollectionsModule implements GameModule, Listener {

    /** A per-tier numeric reward (stat bonus or skill cap) flattened for fast lookups. */
    record StatReward(CollectionCatalog.Coll coll, int tier, Stat stat, SkillType skill, long amount) {}

    private CollectionCatalog catalog = CollectionCatalog.empty();
    private CollectionsService service;
    private CollectionsMenus menus;
    private CollectionRewards rewards;
    private CollectionsLeaderboard leaderboard;
    private final List<StatReward> statRewards = new ArrayList<>();
    private final List<StatReward> capRewards = new ArrayList<>();

    private Dials.Dial<Double> gainMultiplier;
    private Dials.Dial<Double> sbxpMultiplier;
    private Dials.Dial<Boolean> skillXpRewards;
    private Dials.Dial<Boolean> announce;
    private Dials.Dial<Boolean> showUnregistered;
    private Dials.Dial<Boolean> previewUndiscovered;
    private Dials.Dial<Boolean> bossFromKills;
    private Dials.Dial<Integer> masterWeight;
    private Dials.Dial<Boolean> statRewardsOn;
    private Dials.Dial<Boolean> leaderboardOn;
    private Dials.Dial<Integer> leaderboardMin;
    private Dials.Dial<Integer> leaderboardRefresh;
    private Dials.Dial<Boolean> leaderboardSplit;
    private Dials.Dial<Boolean> leaderboardPrune;

    @Override public String id() { return "collections"; }

    // ---- accessors for the package ------------------------------------------------------------

    CollectionCatalog catalog() { return catalog; }
    CollectionsService service() { return service; }
    CollectionsMenus menus() { return menus; }
    CollectionRewards rewards() { return rewards; }
    CollectionsLeaderboard leaderboard() { return leaderboard; }
    List<StatReward> capRewards() { return capRewards; }
    List<StatReward> statRewards() { return statRewards; }

    double sbxpMultiplier() { return Math.max(0, sbxpMultiplier.get()); }
    boolean skillXpRewards() { return skillXpRewards.get(); }
    boolean announce() { return announce.get(); }
    boolean showUnregistered() { return showUnregistered.get(); }
    boolean previewUndiscovered() { return previewUndiscovered.get(); }
    boolean leaderboardOn() { return leaderboardOn.get(); }
    long leaderboardMin() { return Math.max(1, leaderboardMin.get()); }
    int leaderboardRefreshSeconds() { return leaderboardRefresh.get(); }
    boolean leaderboardSplitModes() { return leaderboardSplit == null || leaderboardSplit.get(); }
    boolean leaderboardPrune() { return leaderboardPrune != null && leaderboardPrune.get(); }

    /**
     * A tier as this player reads it: "VII", or "7" with the Settings toggle Roman Numerals off
     * ({@code numbers.roman}, owned by menus). Read through {@code Features.Settings} when menus provides
     * it, else through the PDC mirror menus writes ({@code skyclone:setting.numbers.roman}); default Roman.
     */
    String tierText(Player p, int tier) {
        if (tier <= 0) return "0";
        return roman(p) ? CollectionMath.roman(tier) : String.valueOf(tier);
    }

    static boolean roman(Player p) {
        if (p == null) return true;
        try {
            if (Sky.has(Features.Settings.class)) return Sky.service(Features.Settings.class).flag(p, "numbers.roman", true);
            String v = p.getPersistentDataContainer().get(new org.bukkit.NamespacedKey("skyclone", "setting.numbers.roman"),
                    org.bukkit.persistence.PersistentDataType.STRING);
            return !"false".equals(v);
        } catch (Throwable t) {
            return true;
        }
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    @Override
    public void enable() {
        gainMultiplier = Sky.dials().number("collections.gain-multiplier", 1.0,
                "Multiplier on every collection gain from gathering (1.0 = the genre's rate). TUNE: events/testing");
        sbxpMultiplier = Sky.dials().number("collections.sbxp-multiplier", 1.0,
                "Multiplier on the SkyBlock XP each collection tier awards (data: 4 per resource tier)");
        skillXpRewards = Sky.dials().flag("collections.skill-xp-rewards", true,
                "Pay the lump-sum skill XP some tiers give (e.g. Wheat IX: +25,000 Farming XP)");
        announce = Sky.dials().flag("collections.announce", true,
                "Show the COLLECTION UNLOCKED / COLLECTION LEVEL UP chat boxes");
        showUnregistered = Sky.dials().flag("collections.show-unregistered-rewards", false,
                "List recipe rewards whose item no module registered (shows the reference name; off by default)");
        previewUndiscovered = Sky.dials().flag("collections.preview-undiscovered", false,
                "Let players open the tier road of collections they have not found yet");
        bossFromKills = Sky.dials().flag("collections.boss-from-kills", false,
                "Count boss collections from boss kills (mob id token match, e.g. *_bonzo_* / *_f1_*)");
        masterWeight = Sky.dials().integer("collections.boss-master-weight", 2,
                "Boss collection units per master-mode boss kill (research: 2)");
        statRewardsOn = Sky.dials().flag("collections.stat-rewards", true,
                "Grant stat rewards from tiers (Umber/Tungsten/Obsidian/Glacite: +1 Mining Fortune each)");
        leaderboardOn = Sky.dials().flag("collections.leaderboard", true,
                "Global collection leaderboards (rank shown in the collection menus once past the minimum)");
        leaderboardMin = Sky.dials().integer("collections.leaderboard-min", 25_000,
                "Collection total a profile needs before it appears on (and sees) that collection's leaderboard (research: 25,000)");
        leaderboardRefresh = Sky.dials().integer("collections.leaderboard-refresh-seconds", 30,
                "How often pending leaderboard rows are written and the board re-read (applies after restart)");
        leaderboardSplit = Sky.dials().flag("collections.leaderboard-split-modes", true,
                "Rank Classic, Ironman, Stranded and Bingo profiles on separate collection leaderboards (off = one board)");
        leaderboardPrune = Sky.dials().flag("collections.leaderboard-prune", true,
                "Remove leaderboard rows of profiles that no longer have any member (deleted), every ~20 refreshes");

        loadCatalog();
        rewards = new CollectionRewards(this);
        service = new CollectionsService(this);
        menus = new CollectionsMenus(this);
        leaderboard = new CollectionsLeaderboard(this);
        indexNumericRewards();
        leaderboard.enable();

        Sky.provide(Features.Collections.class, service);
        Sky.requirements().register("collection", new CollectionResolver());
        Sky.requirements().register("collection_discount", new DiscountResolver());
        Sky.stats().addProvider("collections", (p, into) -> {
            if (!statRewardsOn.get() || !Sky.profiles().isReady(p)) return;
            for (StatReward sr : statRewards) {
                if (service.tierOf(p, sr.coll()) >= sr.tier()) into.add(sr.stat(), sr.amount());
            }
        });
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());

        Sky.commands().register(SkyCommand.of("collection", "Open your Collections")
                .alias("collections", "coll").category("Progression").usage("[category|item|top <item> [classic|ironman|stranded|bingo]]").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (a.length == 0) { menus.openMain(p); return; }
                    if (a[0].equalsIgnoreCase("top") || a[0].equalsIgnoreCase("lb")) { menus.chatTop(p, a); return; }
                    String arg = String.join("_", a);
                    if (arg.length() > 48) { menus.openMain(p); return; }
                    CollectionCatalog.Category cat = catalog.category(arg);
                    if (cat != null) { menus.openCategory(p, cat.id); return; }
                    CollectionCatalog.Coll c = catalog.resolve(arg);
                    if (c != null && (service.total(p, c) > 0 || previewUndiscovered())) { menus.openCollection(p, c.id); return; }
                    if (c != null) { com.skyclone.api.ui.Msg.error(p, "You have not discovered that collection yet."); return; }
                    menus.openMain(p);
                })
                .tab((s, a) -> a.length == 1 ? tabCategories(a[0]) : List.of()));

        CollectionsAdmin.register(this);
        CollectionsSelfTests.register(this);
    }

    @Override
    public void disable() {
        if (leaderboard != null) leaderboard.disable();
    }

    @Override
    public void postEnable() {
        rewards.recipeIndex();   // modules registered their recipes in enable(); index them now
    }

    private void loadCatalog() {
        try (InputStream in = Sky.plugin().getResource("data/collections/collections.json")) {
            if (in == null) { Sky.log().severe("[collections] data/collections/collections.json missing from the jar"); return; }
            catalog = CollectionCatalog.parse(in);
            Sky.log().info("[collections] " + catalog.resourceCount() + " collections, " + catalog.resourceTiers()
                    + " tiers, " + (catalog.all().size() - catalog.resourceCount()) + " boss collections");
        } catch (Exception e) {
            Sky.log().log(Level.SEVERE, "[collections] could not read collections.json", e);
        }
    }

    private void indexNumericRewards() {
        statRewards.clear();
        capRewards.clear();
        for (CollectionCatalog.Coll c : catalog.all()) {
            for (CollectionCatalog.Tier t : c.tiers) {
                for (CollectionCatalog.Reward r : t.rewards) {
                    if ("stat".equals(r.type)) {
                        Stat s = Stat.parse(r.stat);
                        if (s != null) statRewards.add(new StatReward(c, t.tier, s, null, r.amount));
                    } else if ("skillcap".equals(r.type)) {
                        SkillType st = SkillType.parse(r.skill);
                        if (st != null) capRewards.add(new StatReward(c, t.tier, null, st, r.amount));
                    }
                }
            }
        }
    }

    private List<String> tabCategories(String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (CollectionCatalog.Category c : catalog.categories()) {
            String n = c.name.toLowerCase(Locale.ROOT);
            if (n.startsWith(p)) out.add(n);
        }
        if ("top".startsWith(p)) out.add("top");
        return out;
    }

    // ---- events ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGain(SkyEvents.ItemGain e) {
        // Cancelled = a sack absorbed it: still counts (the javadoc says so), so no ignoreCancelled.
        if (e.player == null || e.source == null || !e.source.natural || e.amount <= 0) return;
        CollectionCatalog.Feed f = catalog.feed(e.itemId);
        if (f == null) return;
        long units = Math.round(e.amount * (double) f.weight() * Math.max(0, gainMultiplier.get()));
        if (units > 0) service.add(e.player, f.coll(), units);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        if (!bossFromKills.get() || e.killer == null || e.def == null || !e.def.has("boss")) return;
        String id = e.def.id;
        for (CollectionCatalog.Coll c : catalog.inCategory("BOSS")) {
            int units = CollectionMath.bossUnits(c, id, masterWeight.get());
            if (units > 0) { service.add(e.killer, c, units); return; }
        }
    }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        // Remember this member's name for co-op contribution lines, then pay anything owed.
        CollectionsData.Shared s = service.shared(p);
        s.names.put(p.getUniqueId().toString(), p.getName());
        service.syncAll(p);
        leaderboard.noteAll(p);
    }

    // ---- requirement resolvers ----------------------------------------------------------------

    /** {@code collection:<ITEM_ID>:<tier>} (tier as number or Roman); {@code collection:<ITEM_ID>} = discovered. */
    final class CollectionResolver implements Requirements.Resolver {
        @Override public boolean check(Player p, String[] parts) {
            CollectionKey k = CollectionKey.parts(catalog, parts);
            if (k == null) return false;
            long n = service.total(p, k.coll());
            return k.tier() <= 0 ? n > 0 : CollectionMath.tierFor(k.coll(), n) >= k.tier();
        }

        @Override public String describe(String[] parts) {
            CollectionKey k = CollectionKey.parts(catalog, parts);
            if (k == null) return "&a" + String.join(":", parts) + " Collection";
            String name = rewards.collName(k.coll());
            return k.tier() <= 0 ? "&aDiscovering " + name : "&a" + name + " Collection " + CollectionMath.roman(k.tier());
        }
    }

    /**
     * {@code collection_discount:<enchant_id>}: true when a collection tier the player reached grants
     * the -25% experience discount on that enchant. For the enchanting module.
     */
    final class DiscountResolver implements Requirements.Resolver {
        @Override public boolean check(Player p, String[] parts) {
            if (parts.length < 2) return false;
            String ench = parts[1].toLowerCase(Locale.ROOT);
            for (CollectionCatalog.Coll c : catalog.all()) {
                int tier = -1;
                for (CollectionCatalog.Tier t : c.tiers) {
                    for (CollectionCatalog.Reward r : t.rewards) {
                        if (!"discount".equals(r.type) || !ench.equals(r.enchant)) continue;
                        if (tier < 0) tier = service.tierOf(p, c);
                        if (tier >= t.tier) return true;
                    }
                }
            }
            return false;
        }

        @Override public String describe(String[] parts) {
            if (parts.length < 2) return "&aan enchant discount";
            String ench = parts[1].toLowerCase(Locale.ROOT);
            for (CollectionCatalog.Coll c : catalog.all())
                for (CollectionCatalog.Tier t : c.tiers)
                    for (CollectionCatalog.Reward r : t.rewards)
                        if ("discount".equals(r.type) && ench.equals(r.enchant))
                            return "&a" + rewards.collName(c) + " Collection " + CollectionMath.roman(t.tier);
            return "&a" + ench + " discount";
        }
    }
}
