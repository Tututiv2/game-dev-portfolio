package com.skyclone.mod.kuudra;

import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.kuudra.gen.KuudraArenaGenerator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Live self-tests ({@code /sb selftest kuudra}). */
final class KTests {

    private KTests() {}

    static final String[] KINDS = {"follower", "landmine", "magma_cube", "berserker", "knocker", "wandering_blaze", "blight",
            "explosive_imp", "magma_follower", "dropship", "wither_sentry", "blazing_golem", "slasher", "cindermite"};

    static void register(KuudraModule mod) {
        Sky.commands().selfTest("kuudra.research-numbers", () -> research(mod.cat));
        Sky.commands().selfTest("kuudra.items-exist", () -> {
            List<String> bad = new ArrayList<>();
            for (String id : List.of("KUUDRA_SHOP_ITEM", "HEAT_CORE", "TENTACLE_DYE", KLoot.ANANKE_FEATHER, KMenus.WHEEL, KLoot.ESSENCE, KLoot.TEETH))
                if (!Sky.items().exists(id)) bad.add(id);
            for (KCatalog.Tier t : mod.cat.tiers) if (!Sky.items().exists(t.key)) bad.add(t.key + " (key)");
            boolean hunting = Sky.has(com.skyclone.api.feature.Features.Hunting.class);
            java.util.Set<String> missing = new java.util.TreeSet<>();
            for (KCatalog.Loot l : mod.cat.loot.values())
                for (KCatalog.Entry e : l.allEntries()) {
                    if (e.optional || e.item == null || Sky.items().exists(e.item)) continue;
                    if (KLoot.isShard(e.item) && !hunting) continue;                  // hunting off: no shards at all
                    missing.add(e.item);
                }
            if (!missing.isEmpty()) bad.add("chest rows nobody registered " + missing);
            return bad.isEmpty() ? null : "missing: " + bad;
        });
        Sky.commands().selfTest("kuudra.mobs-exist", () -> {
            List<String> bad = new ArrayList<>();
            for (KCatalog.Tier t : mod.cat.tiers) {
                MobDef b = Sky.mobs().def(t.bossId());
                if (b == null || !b.has("boss")) bad.add(t.bossId());
                else if (Math.abs(b.health - t.bossHp) > 1) bad.add(t.bossId() + " hp " + b.health);
                for (String k : KINDS) if (Sky.mobs().def(mod.kindId(k, t)) == null) bad.add(mod.kindId(k, t));
                if (mod.pickWaveMob(t, 1, new Random(1)) == null) bad.add(t.id + ": empty wave pool");
            }
            return bad.isEmpty() ? null : "missing: " + bad;
        });
        Sky.commands().selfTest("kuudra.pet-drops", () -> {
            // every tier's paid chest can hold the Leviathan pet at that tier's rarity (pets module ids KUUDRA;0..4)
            List<String> bad = new ArrayList<>();
            for (KCatalog.Tier t : mod.cat.tiers) {
                KCatalog.Loot l = mod.cat.loot.get(t.id);
                String want = "KUUDRA;" + t.index;
                boolean listed = false;
                if (l != null) for (KCatalog.Rare r : l.rares) if (want.equals(r.item) && r.chance > 0) listed = true;
                if (!listed) bad.add(t.id + ": no " + want + " in the rares");
                else if (!Sky.items().exists(want)) bad.add(want + " is not registered (pets module off?)");
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("kuudra.formulas", KTests::formulas);
        Sky.commands().selfTest("kuudra.shard-pool", () -> {
            String pure = shardPool(mod.cat);
            if (pure != null) return pure;
            if (!Sky.items().exists(KMenus.WHEEL)) return KMenus.WHEEL + " is not registered";
            if (!Sky.has(com.skyclone.api.feature.Features.Hunting.class)) return null;    // hunting off: chests fall back to armor
            List<String> bad = new ArrayList<>();
            for (KCatalog.Loot l : mod.cat.loot.values())
                for (KCatalog.Shard s : l.shards) if (!Sky.items().exists(s.id)) bad.add(s.id);
            if (!bad.isEmpty()) return "hunting is on but these shards are not registered: " + new java.util.TreeSet<>(bad);
            Random rng = new Random(5);
            for (KCatalog.Loot l : mod.cat.loot.values()) {
                int shards = 0;
                for (int i = 0; i < 400; i++) if (KLoot.shardIn(KLoot.roll(l, rng, Sky.items()::exists, 0).paid()) != null) shards++;
                if (shards == 0) return l.tier + ": 400 paid chests and not one shard";
            }
            return null;
        });
        Sky.commands().selfTest("kuudra.intro-steps", () -> {
            String pure = intro();
            if (pure != null) return pure;
            if (!Sky.requirements().known("kuudra")) return "requirement prefix kuudra not registered";
            String key = mod.cat.tier(0) == null ? null : mod.cat.tier(0).key;
            if (KDials.INTRO_KEY.get() && (key == null || !Sky.items().exists(key))) return "Nell's key " + key + " does not exist";
            return null;
        });
        Sky.commands().selfTest("kuudra.cutscenes", () -> {
            String pure = scripts();
            if (pure != null) return pure;
            if (Scenes.FREEZE == null) return "freeze key not set";
            return null;
        });
        Sky.commands().selfTest("kuudra.loot-rolls", () -> {
            Random rng = new Random(7);
            for (KCatalog.Loot l : mod.cat.loot.values())
                for (int i = 0; i < 400; i++) {
                    KLoot.Rolled r = KLoot.roll(l, rng, Sky.items()::exists, 0);
                    if (r.paid().isEmpty()) return l.tier + ": an empty paid chest";
                    if (!r.free().containsKey(KLoot.ESSENCE)) return l.tier + ": free chest without essence";
                    for (String id : r.paid().keySet()) if (!Sky.items().exists(id)) return l.tier + ": rolled unknown " + id;
                }
            return null;
        });
        Sky.commands().selfTest("kuudra.arena-layout", () -> {
            IslandRun.Result r = IslandRun.run(new KuudraArenaGenerator(), IslandRun.seedOf(KuudraArenaGenerator.ISLAND));
            String miss = missing(r.layout);
            if (miss != null) return miss;
            if (r.outsideBounds > 0) return r.outsideBounds + " writes outside bounds";
            Walkability.Options o = new Walkability.Options();
            o.skipFloating = true;
            Walkability.Report rep = Walkability.analyse(r.canvas, r.layout, o);
            if (rep.spawnProblem != null) return rep.spawnProblem;
            for (Walkability.AnchorResult a : rep.anchors)
                if (a.status() == Walkability.Status.UNREACHABLE || a.status() == Walkability.Status.INSIDE_SOLID) return a.name() + " " + a.status();
            return null;
        });
        Sky.commands().selfTest("kuudra.crimson-dock", () -> {
            if (Sky.worlds().island("crimson_isle") == null) return null;                     // crimson absent: nothing to join
            Layout l = Sky.worlds().layout("crimson_isle");
            if (l == null) return null;                                                       // not generated yet
            if (l.anchor("npc.leviathan_dock") == null) return "crimson_isle has no npc.leviathan_dock anchor";
            if (l.area("leviathan_passage") == null) return "crimson_isle has no leviathan_passage area";
            if (!Sky.worlds().warps().contains("kuudra")) return "warp kuudra not registered";
            return null;
        });
        Sky.commands().selfTest("kuudra.menus-build", () -> {
            Player p = null;
            for (Player x : Bukkit.getOnlinePlayers()) if (Sky.profiles().isReady(x)) { p = x; break; }
            if (p == null) return null;
            KData d = mod.data(p);
            for (KCatalog.Tier t : mod.cat.tiers) { String why = mod.blocker(p, t); if (why != null && why.isBlank()) return "blank blocker"; }
            d.totalCompletions();
            return null;
        });
    }

    static String missing(Layout l) {
        List<String> need = new ArrayList<>(List.of("spawn", "rewards", "npc.ferryman", "npc.quartermaster", "kuudra.rise", "kuudra.dps",
                "lair.spawn", "lair.head", "stomach.spawn"));
        for (int i = 0; i < 6; i++) { need.add("pad." + i); need.add("fuel." + i); need.add("kuudra.pos." + i); need.add("stomach.orb." + i); }
        for (int i = 0; i < 12; i++) { need.add("supply." + i); need.add("spawn.mob." + i); }
        for (String a : need) if (l.anchor(a) == null) return "anchor " + a + " missing";
        for (String a : List.of("cannon", "ballista", "stomach", "lair", "arena")) if (l.area(a) == null) return "area " + a + " missing";
        for (String k : List.of("kuudra.ballista", "kuudra.orbs", "kuudra.chest.free", "kuudra.chest.paid")) if (!l.data().containsKey(k)) return "data " + k + " missing";
        return null;
    }

    static String research(KCatalog c) {
        int[] rep = {0, 1000, 3000, 7000, 12000};
        int[] xp = {20, 40, 60, 80, 100};
        int[] wave = {35, 30, 25, 20, 15};
        double[] hp = {10e6, 25e6, 60e6, 150e6, 800e6};
        int[] free = {10, 25, 50, 100, 200};                        // official June-2025 free-chest essence (K8)
        int[] sure = {1, 1, 2, 2, 3};                               // guaranteed Kraken shards and teeth (K5, K6)
        double[] tempo = {.02, .11, .2, .29, .38};                  // Fatal Tempo / Inferno weight (K3)
        double[] wheel = {2.2, 2.8, 3.4, 4.0, 4.6};                 // Wheel of Fate weight (K11)
        double[] core = {0, 0, .1, .3, .5};                         // Burning Kuudra Core, slot 1 (K23)
        double[] mandraa = {0, 0, .8, 1.0, 1.2};                    // slot 1 (K20)
        if (c.tiers.size() != 5) return "expected 5 tiers, got " + c.tiers.size();
        for (int i = 0; i < 5; i++) {
            KCatalog.Tier t = c.tiers.get(i);
            if (t.rep != rep[i]) return t.id + " rep " + t.rep;
            if (t.sbXp != xp[i]) return t.id + " sbxp " + t.sbXp;
            if (t.waveSeconds != wave[i]) return t.id + " wave " + t.waveSeconds;
            if (t.bossHp != hp[i]) return t.id + " hp " + t.bossHp;
            if (t.collection != i + 1) return t.id + " collection " + t.collection;
            if (t.freeEssence[0] != free[i] || t.freeEssence[1] != free[i]) return t.id + " free essence";
            if (t.stun != (i >= 2) || t.lair != (i == 4)) return t.id + " phase flags";
            KCatalog.Loot l = c.loot.get(t.id);
            if (l == null) return t.id + ": no loot";
            if (l.freeEssence[0] != free[i]) return t.id + " loot free essence";
            if (l.paidEssence == null || l.paidEssence[0] <= 0) return t.id + ": no guaranteed paid essence";
            if (i > 0 && l.paidEssence[0] <= c.loot.get(c.tiers.get(i - 1).id).paidEssence[0]) return t.id + ": paid essence does not climb";
            if (count(l.guaranteed, KLoot.KRAKEN) != sure[i]) return t.id + " guaranteed Kraken " + count(l.guaranteed, KLoot.KRAKEN);
            if (count(l.guaranteed, KLoot.TEETH) != sure[i]) return t.id + " guaranteed teeth " + count(l.guaranteed, KLoot.TEETH);
            if (l.bookLevel != i + 1) return t.id + " book level " + l.bookLevel;
            // slot 2: four mana books at 16.5 and level == tier, the Tentacle at 16, the Wheel and the ultimates
            for (String b : List.of("STRONG_MANA", "HARDENED_MANA", "FEROCIOUS_MANA", "MANA_VAMPIRE"))
                if (weight(l.slot2, "ENCHANTMENT_" + b + "_" + (i + 1)) != 16.5) return t.id + " book " + b;
            for (KCatalog.Entry e : l.slot2)
                if (e.item != null && e.item.matches("ENCHANTMENT_(STRONG|HARDENED|FEROCIOUS)_MANA_\\d+|ENCHANTMENT_MANA_VAMPIRE_\\d+")
                        && !e.item.endsWith("_" + (i + 1))) return t.id + " book of the wrong level " + e.item;
            if (weight(l.slot2, "KUUDRA_TENTACLE") != 16) return t.id + " Tentacle weight";
            if (weight(l.slot2, KMenus.WHEEL) != wheel[i]) return t.id + " Wheel weight";
            if (weight(l.slot2, "ENCHANTMENT_ULTIMATE_FATAL_TEMPO_1") != tempo[i] || weight(l.slot2, "ENCHANTMENT_ULTIMATE_INFERNO_1") != tempo[i])
                return t.id + " ultimate book weight";
            if ((weight(l.slot2, "HEAVY_PEARL") > 0) != (i >= 3)) return t.id + " Heavy Pearl rows";
            if ((weight(l.slot2, KLoot.TEETH) > 0) != (i >= 2)) return t.id + " teeth rows (Burning+)";
            // slot 1: twenty BASIC armour pieces at 2.875 (no prestige prefix ever drops), staves, cores, Mandraa
            int armor = 0;
            for (KCatalog.Entry e : l.slot1) {
                if (e.item == null) continue;
                if (e.item.matches("(HOT|BURNING|FIERY|INFERNAL)_(CRIMSON|AURORA|TERROR|FERVOR|HOLLOW)_.*")) return t.id + " drops prestige armour " + e.item;
                if (e.item.matches("(CRIMSON|AURORA|TERROR|FERVOR|HOLLOW)_(HELMET|CHESTPLATE|LEGGINGS|BOOTS)")) {
                    if (e.weight != 2.875) return t.id + " armour weight " + e.item;
                    armor++;
                }
            }
            if (armor != 20) return t.id + " armour pieces " + armor;
            if (weight(l.slot1, "RUNIC_STAFF") != .7 || weight(l.slot1, "HOLLOW_WAND") != .7) return t.id + " staves";
            if (weight(l.slot1, "BURNING_KUUDRA_CORE") != core[i]) return t.id + " Burning Kuudra Core weight";
            if (weight(l.slot1, "MANDRAA") != mandraa[i]) return t.id + " Mandraa weight";
            boolean t5 = i == 4;
            for (String id : List.of("TORMENTOR", "HELLSTORM_STAFF", KLoot.ANANKE_FEATHER))
                if ((weight(l.slot1, id) > 0) != t5) return t.id + " " + id + " (Infernal only)";
            if ((weight(l.slot2, "KUUDRA_MANDIBLE") > 0) != t5) return t.id + " Mandible (Infernal only)";
            for (KCatalog.Entry e : l.slot1) if (KLoot.KRAKEN.equals(e.item)) return t.id + ": Kraken is guaranteed, not a slot-1 roll";
            for (KCatalog.Entry e : l.slot2) if (KLoot.KRAKEN.equals(e.item)) return t.id + ": Kraken is guaranteed, not a slot-2 roll";
            for (KCatalog.Rare r : l.rares) if (KMenus.WHEEL.equals(r.item) || "KUUDRA_MANDIBLE".equals(r.item)) return t.id + ": " + r.item + " is a slot row now";
        }
        if (c.fight.fuelPerVolley != 4 || c.fight.supplies != 6 || c.fight.lairAtPercent != 25) return "fight constants";
        return null;
    }

    static int count(List<KCatalog.Entry> rows, String id) {
        int n = 0;
        for (KCatalog.Entry e : rows) if (id.equals(e.item)) n += e.count;
        return n;
    }

    /** Summed weight of the rows for one id (several rows can share an id: teeth x5 / x25 / x100). */
    static double weight(List<KCatalog.Entry> rows, String id) {
        double w = 0;
        for (KCatalog.Entry e : rows) if (id.equals(e.item)) w += e.weight;
        return Math.round(w * 1e6) / 1e6;
    }

    /**
     * The shard pool hangs together (pure; the unit test runs it on the shipping loot.json): every tier has shard rows
     * in slot 1, every rolled shard is on the Wheel ladder, the ladder runs Common to Legendary without the guaranteed
     * Kraken, the Wheel of Embers is a slot-2 row everywhere and always climbs (or stops at the top), and the rarest
     * shard on offer never drops as the tier rises.
     */
    static String shardPool(KCatalog c) {
        int lastTop = -1;
        for (KCatalog.Tier t : c.tiers) {
            KCatalog.Loot l = c.loot.get(t.id);
            if (l == null) return t.id + ": no loot";
            if (l.shards.isEmpty()) return t.id + ": no Wheel ladder";
            for (String r : KCatalog.SHARD_RARITIES) if (KLoot.ofRarity(l, r, id -> true).isEmpty()) return t.id + ": the ladder has no " + r;
            for (KCatalog.Shard s : l.shards) {
                if (KLoot.KRAKEN.equals(s.id)) return t.id + ": the guaranteed Kraken is on the Wheel ladder";
                if (!KCatalog.SHARD_RARITIES.contains(s.rarity)) return t.id + ": unknown rarity " + s.rarity;
            }
            boolean any = false;
            int top = -1;
            for (KCatalog.Entry e : l.slot1) if (KLoot.isShard(e.item)) any = true;
            if (!any) return t.id + ": shards are not a slot-1 roll";
            for (KCatalog.Entry e : l.allEntries()) {
                if (!KLoot.isShard(e.item) || KLoot.KRAKEN.equals(e.item)) continue;
                String r = l.shardRarityOf(e.item);
                if (r == null) return t.id + ": " + e.item + " rolls but is not on the Wheel ladder";
                top = Math.max(top, KCatalog.SHARD_RARITIES.indexOf(r));
            }
            if (top < lastTop) return t.id + ": rarest shard rarity fell below the tier before";
            lastTop = top;
            if (weight(l.slot2, KMenus.WHEEL) <= 0) return t.id + ": no Wheel of Embers in slot 2";
            Random rng = new Random(3);
            for (KCatalog.Shard s : l.shards) {
                String up = KLoot.upgradeShard(l, s.id, rng, id -> true);
                int from = KCatalog.SHARD_RARITIES.indexOf(s.rarity);
                if (up == null) { if (from != KCatalog.SHARD_RARITIES.size() - 1) return s.id + " cannot be wheeled up"; continue; }
                if (KCatalog.SHARD_RARITIES.indexOf(l.shardRarityOf(up)) <= from) return s.id + " wheeled to " + up + ", not higher";
            }
        }
        if (KLoot.extraTeeth(0, 0) != 0 || KLoot.extraTeeth(5, 0.04) != 1 || KLoot.extraTeeth(5, 0.06) != 0 || KLoot.extraTeeth(150, 0.6) != 1
                || KLoot.extraTeeth(150, 0.4) != 2) return "extra teeth math";
        return null;
    }

    /** The intro state machine (pure). */
    static String intro() {
        if (KIntro.STEPS.size() != KIntro.DONE) return "step list";
        for (int i = 0; i < KIntro.STEPS.size(); i++) if (KIntro.STEPS.get(i).index() != i) return "step " + i + " out of order";
        if (KIntro.advance(KIntro.MEET, true, 99, 15, 0) != KIntro.MEET) return "MEET must need Nell";
        if (KIntro.talk(KIntro.MEET) != KIntro.PLEDGE) return "talk MEET";
        if (KIntro.advance(KIntro.PLEDGE, false, 0, 15, 0) != KIntro.PLEDGE) return "PLEDGE without a faction";
        if (KIntro.advance(KIntro.PLEDGE, true, 0, 15, 0) != KIntro.CULL) return "PLEDGE with a faction";
        if (KIntro.advance(KIntro.PLEDGE, true, 15, 15, 0) != KIntro.REPORT) return "pledge and kills together";
        if (KIntro.advance(KIntro.CULL, true, 14, 15, 0) != KIntro.CULL) return "CULL short";
        if (KIntro.advance(KIntro.REPORT, true, 15, 15, 0) != KIntro.REPORT) return "REPORT must need Nell";
        if (KIntro.talk(KIntro.REPORT) != KIntro.HUNT || !KIntro.unlocked(KIntro.HUNT) || KIntro.unlocked(KIntro.REPORT)) return "send-off";
        if (KIntro.advance(KIntro.HUNT, true, 15, 15, 1) != KIntro.DONE) return "a clear ends it";
        if (KIntro.migrate(KIntro.MEET, 3) != KIntro.DONE || KIntro.migrate(KIntro.CULL, 0) != KIntro.CULL) return "veteran migration";
        if (KIntro.advance(KIntro.DONE + 4, false, 0, 15, 0) != KIntro.DONE || KIntro.advance(-3, true, 0, 15, 0) != KIntro.MEET) return "clamping";
        if (KIntro.objective(KIntro.DONE, 0, 15) != null) return "objective after the end";
        if (!KIntro.objective(KIntro.CULL, 4, 15).contains("15") || !KIntro.objective(KIntro.CULL, 4, 15).contains("(4/15)")) return "cull objective";
        for (int i = KIntro.MEET; i <= KIntro.DONE; i++) if (KIntro.lines(i, false).isEmpty()) return "Nell has nothing to say at step " + i;
        return null;
    }

    /** Cutscene timelines (pure): hunt-step aligned, ordered, ending on "end", the rise long enough to finish. */
    static String scripts() {
        for (KScript.Scene s : List.of(KScript.opening(), KScript.victory())) {
            int last = 0;
            for (KScript.Beat b : s.beats()) {
                if (b.tick() % 5 != 0) return s.id() + "/" + b.id() + " is off the 5-tick grid";
                if (b.tick() < last) return s.id() + "/" + b.id() + " out of order";
                if (b.tick() <= 0) return s.id() + "/" + b.id() + " at tick 0 (beats start after the first step)";
                last = b.tick();
            }
            if (s.at("end") != s.length()) return s.id() + ": end is not the last beat";
            if (s.between(-1, s.length()).size() != s.beats().size()) return s.id() + ": between() loses beats";
            if (s.length() > 400) return s.id() + ": longer than 20 s";
        }
        KScript.Scene o = KScript.opening();
        if (o.at("roar") - o.at("rise") < KScript.RISE_TICKS) return "the roar comes before the beast has surfaced";
        if (o.at("tentacles") >= o.at("rise")) return "tentacles after the rise";
        if (!o.freeze() || !o.skippable()) return "opening flags";
        if (KScript.playFull(false, true, List.of(false))) return "cutscenes off still play";
        if (!KScript.playFull(true, false, List.of(true, false))) return "a newcomer in the crew must see it";
        if (KScript.playFull(true, false, List.of(true, true))) return "veterans get the short cut";
        if (!KScript.skipped(2, 2) || KScript.skipped(1, 2) || KScript.skipped(0, 0)) return "skip vote";
        if (KMath.crawlProgress(0, 12) != 0 || KMath.crawlProgress(12, 12) != 1 || Math.abs(KMath.crawlProgress(6, 12) - 0.5) > 1e-9) return "crawl curve";
        return null;
    }

    static String formulas() {
        if (Math.abs(KMath.partyHpScale(1, 0.5) - 0.5) > 1e-9 || Math.abs(KMath.partyHpScale(4, 0.5) - 1) > 1e-9) return "party scale";
        if (KMath.volleysToDps(15, 40) != 4) return "volleys to dps " + KMath.volleysToDps(15, 40);
        if (Math.abs(KMath.volley(1000, 1000, 15, 0, 400) - 150) > 1e-6) return "volley";
        if (Math.abs(KMath.volley(450, 1000, 15, 0, 400) - 50) > 1e-6) return "volley floor";
        if (KMath.orbHits(3, 2) != 1 || KMath.orbHits(3, 5) != 1) return "orb hits";
        if (!"4:07".equals(KMath.clock(247_000))) return "clock";
        // pure: also called by the offline unit test, where no dial is registered
        if (Math.abs(KMath.broodDamageScale(1, 0.6) - 0.6) > 1e-9 || Math.abs(KMath.broodDamageScale(4, 0.6) - 1) > 1e-9) return "brood damage scale";
        if (!KMath.channelHolds(500) || KMath.channelHolds(KMath.CHANNEL_GRACE_MILLIS)) return "channel grace";
        return null;
    }
}
