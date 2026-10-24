package com.skyclone.mod.progression;

import com.skyclone.api.Sky;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Bestiary (research 08 section 6.1): kills per mob family raise family tiers on the NEU kill
 * brackets; every tier pays 1 SkyBlock XP and a damage bonus against that family, every 10 tiers is a
 * milestone (+10 SkyBlock XP, +Health).
 *
 * <p>Families: the curated reference families (data/progression/bestiary.json) are matched to OUR
 * MobDefs by family id or by name; any mob that matches none gets its own family keyed
 * {@code "mob:" + MobDef.familyId()}, so every mob a module registers is in the bestiary.
 */
final class Bestiary implements Features.Bestiary, Listener {

    /** One family as the game sees it. */
    static final class Fam {
        final String key;
        final String name;
        final String texture;
        final String category;     // category id
        final List<Integer> thresholds;
        final int maxTier;
        final boolean curated;
        /** Stat the tiers pay instead of damage / Magic Find / coins (pests: Farming Fortune), or null. */
        final Stat fortune;
        final List<String> mobIds = new ArrayList<>();
        Fam(String key, String name, String texture, String category, List<Integer> thresholds, int maxTier, boolean curated, Stat fortune) {
            this.key = key; this.name = name; this.texture = texture; this.category = category;
            this.thresholds = thresholds; this.maxTier = maxTier; this.curated = curated; this.fortune = fortune;
        }
        boolean combat() { return fortune == null; }
    }

    static final class Cat {
        final String id;
        final String name;
        final String texture;
        final List<String> islands;
        final Stat fortune;
        final List<Fam> families = new ArrayList<>();
        Cat(String id, String name, String texture, List<String> islands, Stat fortune) {
            this.id = id; this.name = name; this.texture = texture; this.islands = islands; this.fortune = fortune;
        }
    }

    /** The reference's Mythological Creatures category (Daedalus Blade counts its tiers). */
    static final String MYTH_CATEGORY = "mythological_creatures";
    /** The hunting module's critters (critter_* mob ids): Hunting Fortune per tier. */
    static final String HUNT_CATEGORY = "hunting";
    /** Pest bestiary (the Hedgehog's Field Notes counts its tiers). */
    static final String PEST_CATEGORY = "garden";

    static Stat statOf(String name) {
        if (name == null || name.isBlank()) return null;
        try { return Stat.valueOf(name.trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
    }

    private final Map<String, Fam> curated = new LinkedHashMap<>();
    private final Map<String, List<Fam>> byMatchName = new HashMap<>();
    private final Map<String, Cat> categories = new LinkedHashMap<>();
    /** every family by key (curated + dynamic). */
    private final Map<String, Fam> families = new ConcurrentHashMap<>();
    /** mob id + "|" + island -> family key ("" = not counted). */
    private final Map<String, String> resolved = new ConcurrentHashMap<>();
    /** Families shown in menus (have a registered mob, or kills). Rebuilt by {@link #index()}. */
    private volatile List<Cat> visible = List.of();

    Bestiary() {
        Catalogs.BestiaryFile f = Prog.bestiaryFile;
        for (Catalogs.BCategory c : f.categories) {
            Stat fortune = statOf(c.fortune);
            Cat cat = new Cat(c.id, c.name, c.texture, c.islands, fortune);
            categories.put(c.id, cat);
            for (Catalogs.Family fam : c.families) {
                List<Integer> th = f.brackets.get(String.valueOf(fam.bracket));
                if (th == null) continue;
                Fam x = new Fam(fam.id, fam.name, fam.texture, c.id, th, fam.maxTier, true, fortune);
                curated.put(fam.id, x);
                families.put(fam.id, x);
                for (String n : fam.matchNames) byMatchName.computeIfAbsent(n, k -> new ArrayList<>()).add(x);
            }
        }
        categories.put(HUNT_CATEGORY, new Cat(HUNT_CATEGORY, "Critters", "", List.of(), Stat.HUNTING_FORTUNE));
        categories.put("other", new Cat("other", "Other Creatures", "", List.of(), null));
    }

    int curatedCount() { return curated.size(); }

    List<Integer> bracket(int b) { return Prog.bestiaryFile.brackets.get(String.valueOf(b)); }

    // ---- matching ------------------------------------------------------------------------------------------

    static String snake(String s) {
        if (s == null) return "";
        String t = Text.strip(s).toLowerCase(Locale.ROOT).replace("'", "");
        t = t.replaceAll("[^a-z0-9]+", "_");
        return t.replaceAll("^_+|_+$", "");
    }

    /** The family key a kill of {@code def} on {@code island} counts for, or null when it does not count. */
    String familyKey(MobDef def, String island) {
        if (def == null || def.id == null) return null;
        String cacheKey = def.id + "|" + (island == null ? "" : island);
        String hit = resolved.get(cacheKey);
        if (hit != null) return hit.isEmpty() ? null : hit;
        String key = resolve(def, island);
        resolved.put(cacheKey, key == null ? "" : key);
        return key;
    }

    private String resolve(MobDef def, String island) {
        if (!counts(def)) return null;
        String fam = def.familyId();
        if (curated.containsKey(fam)) return fam;
        String inferred = island != null ? island : inferIsland(def.id);
        // the hunting module's shard critters: their own category, paying Hunting Fortune (research 08 6.1)
        if (def.id.startsWith("critter_")) return autoFamily(def, fam, HUNT_CATEGORY);
        boolean sea = def.has("sea_creature");
        for (String n : new String[]{snake(fam), snake(def.name), snake(def.id)}) {
            List<Fam> cands = byMatchName.get(n);
            if (cands == null || cands.isEmpty()) continue;
            Fam best = null;
            for (Fam c : cands) {
                Cat cat = categories.get(c.category);
                boolean fishCat = c.category.startsWith("fishing");
                if (sea != fishCat) continue;
                if (inferred != null && cat != null && cat.islands.contains(inferred)) { best = c; break; }
                if (best == null) best = c;
            }
            if (best == null && !sea) best = cands.get(0);
            if (best != null) return best.key;
        }
        return autoFamily(def, fam, categoryForIsland(inferred));
    }

    /** A family for a mob no curated family matched ({@code "mob:" + familyId}, default bracket and cap dials). */
    private String autoFamily(MobDef def, String fam, String cat) {
        String key = "mob:" + fam;
        families.computeIfAbsent(key, k -> {
            int br = Math.max(1, Math.min(8, Prog.bestiaryUnknownBracket.get()));
            List<Integer> th = bracket(br);
            if (th == null) th = bracket(2);
            int max = PMath.bestiaryMaxTier(th, Prog.bestiaryUnknownCap.get());
            String name = def.name != null && !def.name.isBlank() ? Text.strip(def.name) : Text.titleCase(fam);
            Cat c = categories.get(cat);
            return new Fam(k, name, def.skull == null ? "" : def.skull, cat, th, max, false, c == null ? null : c.fortune);
        });
        return key;
    }

    /**
     * Whether a mob can be a bestiary family at all. Slayer BOSSES and their fight's helpers (springs, ichor, bats,
     * egg sacs, pups...) are not; slayer MINI-BOSSES are (research 08 6.1 lists "Slayer minis"). Summons and
     * anything tagged {@code no_bestiary} are not.
     */
    static boolean counts(MobDef def) {
        if (def == null || def.id == null) return false;
        if (def.has("no_bestiary") || def.has("summon")) return false;
        if (def.has("boss") && def.slayer != null) return false;
        String id = def.id;
        if (id.startsWith("slayer_") && !id.startsWith("slayer_mini_")) return false;
        return true;
    }

    private String categoryForIsland(String island) {
        if (island == null) return "other";
        for (Cat c : categories.values()) if (c.islands.contains(island) && !c.id.startsWith("fishing")) return c.id;
        return "other";
    }

    /** Island from a mob id's prefix ("hub_graveyard_zombie" -> hub, "end_zealot" -> the_end). */
    static String inferIsland(String mobId) {
        if (mobId == null) return null;
        String best = null;
        for (var d : Sky.worlds().islands()) {
            if (d == null || d.id == null) continue;
            if (mobId.startsWith(d.id + "_") && (best == null || d.id.length() > best.length())) best = d.id;
        }
        if (best != null) return best;
        if (mobId.startsWith("end_")) return "the_end";
        if (mobId.startsWith("spider_") || mobId.startsWith("den_")) return "spiders_den";
        if (mobId.startsWith("crimson_")) return "crimson_isle";
        if (mobId.startsWith("dwarven_")) return "dwarven_mines";
        if (mobId.startsWith("hollows_")) return "crystal_hollows";
        if (mobId.startsWith("glacite_")) return "glacite_tunnels";
        if (mobId.startsWith("winter_") || mobId.startsWith("jerry_")) return "jerrys_workshop";
        if (mobId.startsWith("dungeon_") || mobId.startsWith("catacombs_")) return "catacombs";
        if (mobId.startsWith("pest_")) return "garden";
        if (mobId.startsWith("island_")) return "private_island";
        return null;
    }

    /** Map every registered MobDef to a family and rebuild the visible list (postEnable + on menu open). */
    void index() {
        Map<String, List<String>> mobsPer = new HashMap<>();
        for (MobDef d : Sky.mobs().all()) {
            String k = familyKey(d, null);
            if (k != null) mobsPer.computeIfAbsent(k, x -> new ArrayList<>()).add(d.id);
        }
        Map<String, Cat> fresh = new LinkedHashMap<>();
        for (Cat c : categories.values()) fresh.put(c.id, new Cat(c.id, c.name, c.texture, c.islands, c.fortune));
        for (Fam f : families.values()) {
            List<String> mobs = mobsPer.get(f.key);
            if (mobs == null || mobs.isEmpty()) continue;
            f.mobIds.clear();
            f.mobIds.addAll(mobs);
            Cat c = fresh.get(f.category);
            if (c == null) c = fresh.get("other");
            c.families.add(f);
        }
        List<Cat> out = new ArrayList<>();
        for (Cat c : fresh.values()) {
            if (c.families.isEmpty()) continue;
            c.families.sort(Comparator.comparing(x -> x.name));
            out.add(c);
        }
        visible = out;
    }

    List<Cat> visible() { return visible; }

    Fam family(String key) { return families.get(key); }

    // ---- per player ----------------------------------------------------------------------------------------

    int tier(PData.Bestiary d, Fam f) {
        int k = d.kills.getOrDefault(f.key, 0);
        return PMath.bestiaryTier(k, f.thresholds, f.maxTier);
    }

    int totalTiers(PData.Bestiary d) {
        int sum = 0;
        for (Map.Entry<String, Integer> e : d.kills.entrySet()) {
            Fam f = families.get(e.getKey());
            if (f != null) sum += PMath.bestiaryTier(e.getValue(), f.thresholds, f.maxTier);
        }
        return sum;
    }

    @Override public int kills(Player p, String familyId) {
        if (!Prog.ready(p) || familyId == null) return 0;
        PData.Bestiary d = Prog.best(p);
        Integer v = d.kills.get(familyId);
        if (v == null) v = d.kills.get("mob:" + familyId);
        return v == null ? 0 : v;
    }

    @Override public int milestone(Player p) {
        if (!Prog.ready(p)) return 0;
        return PMath.bestiaryMilestone(totalTiers(Prog.best(p)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (!Prog.ready(p) || e.def == null) return;
        // Owned mobs COUNT (wave 5): sea creatures, Trapper animals, Golden Goblins, worms, Vanquishers, hidden imps
        // and mythological creatures are all spawned owned by the player who found them, and every one is a bestiary
        // family in the reference. Slayer bosses and summons are excluded by counts() instead.
        String island = e.location != null && e.location.getWorld() != null ? Sky.worlds().islandOf(e.location.getWorld()) : null;
        String key = familyKey(e.def, island);
        if (key == null) return;
        addKills(p, key, 1);
    }

    /**
     * "+X% coins vs that mob" (research 08 6.1). HIGH, so it scales the payout before core pays it and before the
     * MONITOR handler counts this kill: the tier that pays is the one the player had when the mob died.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onKillCoins(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (!Prog.ready(p) || e.def == null || !(e.coins > 0)) return;
        String island = e.location != null && e.location.getWorld() != null ? Sky.worlds().islandOf(e.location.getWorld()) : null;
        Fam f = fam(e.def, island);
        if (f == null || !f.combat()) return;
        int t = tier(Prog.best(p), f);
        if (t > 0) e.coins = PMath.scaledCoins(e.coins, PMath.bestiaryCoinPct(t, Prog.bestiaryCoinPerTier.get()));
    }

    private Fam fam(MobDef def, String island) {
        String key = familyKey(def, island);
        return key == null ? null : families.get(key);
    }

    /** Magic Find this player has against one kind of mob from the bestiary (+ the Daedalus Blade's share). */
    double magicFindVs(Player p, MobDef def) {
        if (!Prog.ready(p) || def == null) return 0;
        Fam f = fam(def, Sky.worlds().islandOf(p.getWorld()));
        if (f == null || !f.combat()) return 0;
        PData.Bestiary d = Prog.best(p);
        double mf = PMath.bestiaryMagicFind(tier(d, f), Prog.bestiaryMfEarly.get(), Prog.bestiaryMfLate.get(), Prog.bestiaryMfSplit.get());
        if (MYTH_CATEGORY.equals(f.category) && holdsDaedalus(p)) mf += categoryTiers(d, MYTH_CATEGORY) * Prog.daedalusMf.get();
        return mf;
    }

    /** Sum of this player's tiers over every family of one category. */
    int categoryTiers(PData.Bestiary d, String category) {
        int sum = 0;
        for (Map.Entry<String, Integer> e : d.kills.entrySet()) {
            Fam f = families.get(e.getKey());
            if (f != null && category.equals(f.category)) sum += PMath.bestiaryTier(e.getValue(), f.thresholds, f.maxTier);
        }
        return sum;
    }

    static boolean holdsDaedalus(Player p) {
        try {
            String id = Sky.items().idOf(p.getInventory().getItemInMainHand());
            return "DAEDALUS_AXE".equals(id) || "STARRED_DAEDALUS_AXE".equals(id);
        } catch (Throwable t) { return false; }
    }

    /** Count kills; announce tier-ups and milestones; pay their SkyBlock XP. */
    void addKills(Player p, String key, int n) {
        Fam f = families.get(key);
        if (f == null || n <= 0) return;
        PData.Bestiary d = Prog.best(p);
        int before = d.kills.getOrDefault(key, 0);
        int oldTier = PMath.bestiaryTier(before, f.thresholds, f.maxTier);
        int after = (int) Math.min(Integer.MAX_VALUE, (long) before + n);
        d.kills.put(key, after);
        int newTier = PMath.bestiaryTier(after, f.thresholds, f.maxTier);
        if (before == 0 && !visibleHas(key)) index();
        if (newTier > oldTier) {
            noticeTierUp(p, f, oldTier, newTier);
            for (int t = oldTier + 1; t <= newTier; t++) {
                Prog.award(p, "bestiary:" + key + ":" + t, Prog.levelsFile.bestiaryFamilyXp, "Bestiary");
            }
            int ms = PMath.bestiaryMilestone(totalTiers(d));
            while (d.announcedMilestone < ms) {
                d.announcedMilestone++;
                double combatXp = Math.max(0, Prog.bestiaryMilestoneCombatXp.get()) * d.announcedMilestone;
                List<String> rew = new ArrayList<>();
                rew.add("&a&lREWARDS");
                rew.add("  &8+&c" + Text.commas(Prog.bestiaryMilestoneHealth.get()) + " " + Stat.HEALTH.label());
                rew.add("  &8+&b" + Prog.levelsFile.bestiaryMilestoneXp + " SkyBlock XP");
                if (combatXp > 0) rew.add("  &8+&3" + Text.commasInt(combatXp) + " Combat XP");
                String msHead = "&3&lBESTIARY MILESTONE " + Text.roman(d.announcedMilestone);
                noticeAfter(p, () -> Msg.box(p, msHead, rew));   // after the tier-up box that reached it
                Prog.award(p, "bestiary:milestone:" + d.announcedMilestone, Prog.levelsFile.bestiaryMilestoneXp, "Bestiary Milestone");
                // Combat XP lump, once per milestone (announcedMilestone only ever rises, and is saved with the profile)
                if (combatXp > 0 && Sky.has(SkillService.class)) {
                    try { Sky.service(SkillService.class).addXp(p, SkillType.COMBAT, combatXp, "bestiary:milestone:" + d.announcedMilestone); }
                    catch (Throwable t) { Sky.log().warning("[progression] bestiary combat xp: " + t); }
                }
            }
            Sky.stats().invalidate(p);
        }
    }

    // ---- tier-up announcements (coalesced) ----------------------------------------------------------------

    /** Tier-ups (and milestone boxes) waiting for a quiet moment, per player. Main thread only. */
    private static final class PendingNotices {
        final List<BestiaryNotices.TierUp> ups = new ArrayList<>();
        final List<Runnable> after = new ArrayList<>();
        long first;
        int gen;
    }

    private final Map<java.util.UUID, PendingNotices> pendingNotices = new HashMap<>();

    /** The reference's own box for ONE family tier-up: "BESTIARY Zombie I➜II", its bonuses and SkyBlock XP. */
    void announceOne(Player p, Fam f, int oldTier, int newTier) {
        List<String> body = new ArrayList<>();
        body.add("&a&lREWARDS");
        body.addAll(bonusLines(f, oldTier, newTier));
        body.add("  &8+&b" + (newTier - oldTier) * Prog.levelsFile.bestiaryFamilyXp + " SkyBlock XP");
        Msg.box(p, "&3&lBESTIARY &b" + f.name + " &8" + (oldTier == 0 ? "0" : Text.roman(oldTier)) + "➜&b" + Text.roman(newTier)
                + (oldTier == 0 ? " &8(new family!)" : ""), body);
    }

    /**
     * Announce a tier-up. With progression.bestiary.notice-quiet-ticks at 0 it prints at once (one box each, as
     * before); otherwise it waits until no tier-up has landed for that many ticks (at most notice-max-ticks after
     * the first) and prints ONE box: the full box for a lone family, a combined one for several - a dungeon room
     * used to print ~10 eight-line boxes (wave-5 regression playtest). The SkyBlock XP is paid at once either way.
     */
    private void noticeTierUp(Player p, Fam f, int oldTier, int newTier) {
        int quiet = Math.max(0, Prog.bestiaryNoticeQuietTicks.get());
        if (quiet == 0) {
            announceOne(p, f, oldTier, newTier);
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.0f);
            return;
        }
        PendingNotices pd = pendingNotices.computeIfAbsent(p.getUniqueId(), u -> new PendingNotices());
        BestiaryNotices.merge(pd.ups, new BestiaryNotices.TierUp(f.key, f.name, oldTier, newTier));
        schedule(p, pd, quiet);
    }

    /** Run {@code box} after the pending tier-up box (or now when nothing is pending / batching is off). */
    private void noticeAfter(Player p, Runnable box) {
        PendingNotices pd = pendingNotices.get(p.getUniqueId());
        if (pd == null || Prog.bestiaryNoticeQuietTicks.get() <= 0) { box.run(); return; }
        pd.after.add(box);
    }

    private void schedule(Player p, PendingNotices pd, int quiet) {
        long now = org.bukkit.Bukkit.getCurrentTick();
        if (pd.gen == 0) pd.first = now;
        int gen = ++pd.gen;
        long cap = Math.max(quiet, Prog.bestiaryNoticeMaxTicks.get());
        long delay = Math.max(1, Math.min(quiet, cap - (now - pd.first)));
        java.util.UUID id = p.getUniqueId();
        org.bukkit.Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (pendingNotices.get(id) != pd || pd.gen != gen) return;   // a later tier-up re-armed it
            pendingNotices.remove(id);
            if (p.isOnline()) flush(p, pd);
        }, delay);
    }

    private void flush(Player p, PendingNotices pd) {
        if (pd.ups.size() == 1) {
            BestiaryNotices.TierUp u = pd.ups.get(0);
            Fam f = families.get(u.key());
            if (f != null) announceOne(p, f, u.from(), u.to());
        } else if (!pd.ups.isEmpty()) {
            Msg.box(p, BestiaryNotices.headline(pd.ups), BestiaryNotices.body(pd.ups, Prog.levelsFile.bestiaryFamilyXp,
                    Math.max(1, Prog.bestiaryNoticePerLine.get()), Math.max(1, Prog.bestiaryNoticeLines.get())));
        }
        for (Runnable r : pd.after) {
            try { r.run(); } catch (Throwable t) { Sky.log().warning("[progression] bestiary notice: " + t); }
        }
        if (!pd.ups.isEmpty()) Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.0f);
    }

    private boolean visibleHas(String key) {
        for (Cat c : visible) for (Fam f : c.families) if (f.key.equals(key)) return true;
        return false;
    }

    // ---- bonuses --------------------------------------------------------------------------------------------

    /** The reward lines for going from {@code from} to {@code to} tiers of a family (tier box + family icon). */
    List<String> bonusLines(Fam f, int from, int to) {
        List<String> out = new ArrayList<>();
        int n = to - from;
        if (n <= 0) return out;
        if (!f.combat()) {
            out.add("  &8+&6" + Text.commas(n * Prog.bestiaryFortunePerTier.get()) + " " + f.fortune.label());
            return out;
        }
        out.add("  &8+&c" + Text.commas(Prog.bestiaryDamage.get() * n) + "% &7damage vs &f" + f.name);
        double mf = PMath.bestiaryMagicFind(to, Prog.bestiaryMfEarly.get(), Prog.bestiaryMfLate.get(), Prog.bestiaryMfSplit.get())
                - PMath.bestiaryMagicFind(from, Prog.bestiaryMfEarly.get(), Prog.bestiaryMfLate.get(), Prog.bestiaryMfSplit.get());
        if (mf > 0) out.add("  &8+&b" + Text.commas(mf) + " " + Stat.MAGIC_FIND.label() + " &7vs &f" + f.name);
        double coins = Prog.bestiaryCoinPerTier.get() * n;
        if (coins > 0) out.add("  &8+&6" + Text.commas(coins) + "% &7coins from &f" + f.name);
        return out;
    }

    void contribute(Player p, StatMap into) {
        if (!Prog.ready(p)) return;
        PData.Bestiary d = Prog.best(p);
        int ms = PMath.bestiaryMilestone(totalTiers(d));
        if (ms > 0) into.add(Stat.HEALTH, ms * Prog.bestiaryMilestoneHealth.get());
        // Fortune-paying families (the pest bestiary): research 08 6.1, +0.4 Farming Fortune a tier, 102 at max
        Map<Stat, Integer> fortuneTiers = new HashMap<>();
        for (Map.Entry<String, Integer> e : d.kills.entrySet()) {
            Fam f = families.get(e.getKey());
            if (f == null || f.combat()) continue;
            int t = PMath.bestiaryTier(e.getValue(), f.thresholds, f.maxTier);
            if (t > 0) fortuneTiers.merge(f.fortune, t, Integer::sum);
        }
        for (Map.Entry<Stat, Integer> e : fortuneTiers.entrySet()) into.add(e.getKey(), e.getValue() * Prog.bestiaryFortunePerTier.get());
        // Hedgehog pet "Field Notes": +0.7 Farming Fortune per pest bestiary tier (research 06; pets ships it as lore)
        try {
            if (Sky.has(Features.Pets.class) && "HEDGEHOG".equalsIgnoreCase(Sky.service(Features.Pets.class).activeType(p))) {
                int pest = categoryTiers(d, PEST_CATEGORY);
                if (pest > 0) into.add(Stat.FARMING_FORTUNE, pest * Prog.bestiaryHedgehogFf.get());
            }
        } catch (Throwable ignored) { }
    }

    /** +X% damage per tier vs that family (outgoing player hits only), + the Daedalus Blade vs mythological. */
    void modify(DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null || !Prog.ready(p) || !(ctx.victim instanceof LivingEntity le) || ctx.victim instanceof Player) return;
        MobDef def = Sky.mobs().defOf(le);
        if (def == null) return;
        Fam f = fam(def, Sky.worlds().islandOf(le.getWorld()));
        if (f == null || !f.combat()) return;
        PData.Bestiary d = Prog.best(p);
        int t = tier(d, f);
        if (t > 0) ctx.additive += t * Prog.bestiaryDamage.get();
        if (MYTH_CATEGORY.equals(f.category) && holdsDaedalus(p)) {
            int myth = categoryTiers(d, MYTH_CATEGORY);
            if (myth > 0) ctx.additive += myth * Prog.daedalusDamage.get();
        }
    }

    Requirements.Resolver resolver() {
        return new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 3 || !Prog.ready(p)) return false;
                Fam f = families.get(parts[1]);
                if (f == null) f = families.get("mob:" + parts[1]);
                if (f == null) return false;
                try { return tier(Prog.best(p), f) >= Integer.parseInt(parts[2].trim()); } catch (NumberFormatException e) { return false; }
            }
            @Override public String describe(String[] parts) {
                Fam f = parts.length > 1 ? families.get(parts[1]) : null;
                String name = f != null ? f.name : parts.length > 1 ? Text.titleCase(parts[1]) : "?";
                return "&a" + name + " Bestiary " + (parts.length > 2 ? Text.roman(safeInt(parts[2])) : "");
            }
        };
    }

    private static int safeInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }

    // ---- menus -------------------------------------------------------------------------------------------

    @Override public void openMenu(Player p) {   // core-wave5: now Features.*.openMenu
        if (!Prog.ready(p)) { Msg.error(p, "Your profile is still loading."); return; }
        index();
        buildMain(p, Prog.best(p)).open(p);
    }

    Menu buildMain(Player viewer, PData.Bestiary d) {
        Menu m = new Menu("Bestiary", 6).chrome();
        int total = totalTiers(d);
        int ms = PMath.bestiaryMilestone(total);
        int maxTiers = 0, famCount = 0, found = 0;
        for (Cat c : visible) for (Fam f : c.families) { maxTiers += f.maxTier; famCount++; if (d.kills.getOrDefault(f.key, 0) > 0) found++; }
        m.set(4, Icons.of(Material.WRITABLE_BOOK, "&aBestiary",
                "&7Kill mobs to learn about them. Every", "&7family tier gives you damage, Magic", "&7Find and coins against that family",
                "&7(pests and critters: Fortune).", "",
                "&7Families Found: &a" + found + "&7/&a" + famCount,
                "&7Family Tiers: &a" + total + "&7/&a" + maxTiers, Prog.bar(total, Math.max(1, maxTiers)), "",
                "&7Milestone: &b" + Text.roman(Math.max(1, ms)) + (ms == 0 ? " &8(none yet)" : ""),
                "&7Next milestone at &b" + ((ms + 1) * 10) + " &7tiers.", "",
                "&7Milestone bonus: &c+" + Text.commas(ms * Prog.bestiaryMilestoneHealth.get()) + " " + Stat.HEALTH.label()));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        int i = 0;
        for (Cat c : visible) {
            if (i >= slots.length) break;
            int got = 0, max = 0, fams = 0, seen = 0;
            for (Fam f : c.families) { got += tier(d, f); max += f.maxTier; fams++; if (d.kills.getOrDefault(f.key, 0) > 0) seen++; }
            List<String> lore = List.of("&7Families Found: &a" + seen + "&7/&a" + fams,
                    "&7Tiers: &a" + got + "&7/&a" + max, Prog.bar(got, Math.max(1, max)), "", "&eClick to view!");
            ItemStack icon = c.texture == null || c.texture.isEmpty()
                    ? Icons.of(Material.ZOMBIE_HEAD, "&a" + c.name, lore) : Icons.skull(c.texture, "&a" + c.name, lore);
            final Cat cat = c;
            m.set(slots[i++], icon, click -> buildCategory(click.player(), cat, 0).open(click.player()));
        }
        if (visible.isEmpty()) m.set(22, Icons.of(Material.BARRIER, "&cNo creatures yet", "&7No mobs are registered in this world yet."));
        Prog.backToSkyMenu(m);
        return m;
    }

    Menu buildCategory(Player viewer, Cat cat, int page) {
        return Sky.menus().paged("Bestiary ➜ " + cat.name, new ArrayList<>(cat.families), f -> familyIcon(Prog.best(viewer), f),
                null, pg -> pg.back("Bestiary", () -> buildMain(viewer, Prog.best(viewer))), page);
    }

    ItemStack familyIcon(PData.Bestiary d, Fam f) {
        int kills = d.kills.getOrDefault(f.key, 0);
        if (kills <= 0) {
            return Icons.of(Material.GRAY_DYE, "&c" + f.name, "&7You have not killed one yet.", "", "&8Max tier: " + Text.roman(f.maxTier));
        }
        int t = PMath.bestiaryTier(kills, f.thresholds, f.maxTier);
        long next = PMath.bestiaryNext(t, f.thresholds, f.maxTier);
        List<String> lore = new ArrayList<>();
        lore.add("&7Tier: &a" + (t == 0 ? "0" : Text.roman(t)) + "&7/&a" + Text.roman(f.maxTier));
        lore.add("&7Kills: &a" + Text.commasInt(kills));
        lore.add("");
        if (next > 0) {
            lore.add("&7Progress to Tier " + Text.roman(t + 1) + ":");
            lore.add(Prog.bar(kills, next));
        } else {
            lore.add("&a&lMAXED!");
        }
        lore.add("");
        if (t > 0) {
            lore.add("&7Bonuses:");
            lore.addAll(bonusLines(f, 0, t));
        } else {
            lore.add("&7Reach Tier I for your first bonus.");
        }
        if (next > 0) {
            lore.add("");
            lore.add("&7Next tier:");
            lore.addAll(bonusLines(f, t, t + 1));
        }
        String name = (next > 0 ? "&e" : "&a") + f.name + " " + (t == 0 ? "" : Text.roman(t));
        return f.texture == null || f.texture.isEmpty() ? Icons.of(Material.SKELETON_SKULL, name, lore) : Icons.skull(f.texture, name, lore);
    }
}
