package com.skyclone.mod.enchanting;

import com.google.gson.Gson;
import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.item.EnchantDef;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerExpChangeEvent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enchanting: every enchantment (data + effect), the enchanting table, the anvil, enchanted books,
 * the Experimentation Table (Superpairs, Chronomatron, Ultrasequencer) and the Enchanting XP they award.
 * See docs/handoff/enchanting.md.
 */
public final class EnchantingModule implements GameModule {

    static final String KEY = "enchanting";

    private static EnchantRegistry registry = new EnchantRegistry(List.of());
    private static ExperimentSpec experiments = new ExperimentSpec();
    private static List<LibrarianMenu.Row> librarianRows = List.of();
    private static Map<String, AnvilRules.Endcap> endcaps = Map.of();
    private static EDials dials;
    static int bookItems;
    static int aliasItems;
    static int aliasEnchants;

    @Override public String id() { return KEY; }

    static EnchantRegistry reg() { return registry; }
    static ExperimentSpec experiments() { return experiments; }
    static List<LibrarianMenu.Row> librarianRows() { return librarianRows; }
    /** Endcap Upgrade item id -> what it raises (data/enchanting/anvil_upgrades.json). */
    static Map<String, AnvilRules.Endcap> endcaps() { return endcaps; }
    static EDials dials() { return dials; }

    @Override
    public void enable() {
        dials = new EDials();
        registry = loadRegistry();
        experiments = loadExperiments();
        endcaps = loadEndcaps();
        librarianRows = LibrarianMenu.load();

        // enchant defs (core renders them in lore) + one book item per level (canonical + alias)
        for (EnchantSpec s : registry.all()) {
            Sky.items().registerEnchant(s.toDef());
            for (int l = s.minLevel; l <= s.maxLevel; l++) {
                Sky.items().register(registry.bookDef(s, l, EnchantRegistry.bookId(s.id, l)));
                bookItems++;
                ItemDef alias = registry.bookDef(s, l, EnchantRegistry.aliasId(s.id, l));
                alias.tags.remove("bazaar");
                alias.tags.add("enchanting_alias");
                Sky.items().register(alias);
                aliasItems++;
            }
        }
        // alias ids ("woodsplitter" for arcane, "bank" for ultimate_bank: the enchant's NAME snake-cased,
        // which is how the skills table names its unlocks) resolve through core too. Same data, own id;
        // an item that ever carries an alias key is rewritten to the canonical id (PlayerEnchants.normalise).
        registry.aliasMap().forEach((alias, canon) -> {
            EnchantSpec s = registry.spec(canon);
            EnchantDef d = s.toDef();
            d.id = alias;
            Sky.items().registerEnchant(d);
            aliasEnchants++;
        });
        Sky.items().loadJson(KEY, "items.json");
        Sky.recipes().loadJson(KEY, "recipes.json");

        StatEnchants.register();
        CombatEnchants combat = new CombatEnchants();
        combat.register();
        DefenseEnchants defense = new DefenseEnchants();
        defense.register();
        PlayerEnchants player = new PlayerEnchants();
        player.register();
        UseLeveling leveling = new UseLeveling();
        leveling.register();
        ExtraEnchants extra = new ExtraEnchants();
        extra.register();
        Bukkit.getPluginManager().registerEvents(combat, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(defense, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(player, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(leveling, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(extra, Sky.plugin());
        // wave 5: Vitality pool (Drain, Last Stand, the four converters), Stealth, Scuba / Green Thumb / Bug Blender
        Vitality vitality = new Vitality();
        vitality.register();
        Bukkit.getPluginManager().registerEvents(vitality, Sky.plugin());
        Stealth stealth = new Stealth();
        stealth.register();
        Bukkit.getPluginManager().registerEvents(stealth, Sky.plugin());
        Wave5Enchants wave5 = new Wave5Enchants();
        wave5.register();
        Bukkit.getPluginManager().registerEvents(wave5, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new Rend(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new BlockListener(), Sky.plugin());

        registerCommands();
        AdminCommands.register();
        SelfTests.register();
    }

    @Override
    public void postEnable() {
        // The CONTRACT gives npc.librarian to enchanting, the shops brief also names a Librarian:
        // only register ours when nobody else put an NPC on that anchor.
        if (dials.librarian.get()) {
            boolean taken = Sky.npcs().all().stream().anyMatch(d -> "hub".equals(d.island) && "npc.librarian".equals(d.anchor));
            if (!taken) {
                NpcDef d = NpcDef.of("enchanting_librarian", "&fLibrarian", "hub", "npc.librarian").title("&e&lCLICK");
                d.lines.add("&7Books & Experience");
                Sky.npcs().register(d, p -> Sky.npcs().talk(p, "enchanting_librarian", List.of(
                        "&fWelcome to the library!",
                        "&fEnchanting costs experience levels. Kill mobs, mine ores or smash bottles for more."),
                        () -> LibrarianMenu.open(p)));
            }
        }
    }

    @Override
    public void disable() {
        ExperimentsMenu.shutdown();
        CombatEnchants.shutdown();
        ExtraEnchants.shutdown();
        Vitality.shutdown();
        Stealth.shutdown();
        Wave5Enchants.shutdown();
        Rend.shutdown();
    }

    // ---- commands -----------------------------------------------------------------------------------

    private void registerCommands() {
        Sky.commands().register(SkyCommand.of("enchant", "Open the Enchant Item menu (enchanting table)")
                .alias("enchanting", "enchanttable").category("Skills").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!dials.tableCommand.get()) { Msg.error(p, "Right-click an enchanting table to enchant items."); return; }
                    TableMenu.open(p, BlockListener.nearbyPower(p, dials.commandPower.get()));
                }));
        Sky.commands().register(SkyCommand.of("anvil", "Open the Anvil (books, potato books)")
                .category("Skills").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!dials.anvilCommand.get()) { Msg.error(p, "Right-click an anvil to combine items."); return; }
                    AnvilMenu.open(p);
                }));
        Sky.commands().register(SkyCommand.of("experiments", "Open the Experimentation Table")
                .alias("et", "experimentation").category("Skills").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    String req = dials.experimentsRequirement.get();
                    if (req != null && !req.isBlank() && !Sky.requirements().checkOrTell(p, req)) return;
                    ExperimentsMenu.open(p);
                }));
        Sky.commands().register(SkyCommand.of("vitality", "Show your Vitality pool and what spends / refills it")
                .category("Skills").playerOnly()
                .run((s, a) -> Vitality.tell((Player) s)));
        Sky.commands().register(SkyCommand.of("enchants", "Browse every enchantment")
                .alias("enchantguide", "enchantments").category("Skills").playerOnly()
                .run((s, a) -> GuideMenu.open((Player) s, null, 0)));
    }

    // ---- shared helpers -----------------------------------------------------------------------------

    static PlayerData data(Player p) { return Sky.profiles().member(p, KEY, PlayerData.class); }

    static boolean ready(Player p) { return p != null && p.isOnline() && Sky.profiles().isReady(p); }

    /** Reset the daily experiment counter when the UTC day changed. */
    static PlayerData daily(Player p) {
        PlayerData d = data(p);
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        if (!today.equals(d.day)) {
            d.day = today;
            d.superpairsToday = 0;
            d.extraRunsToday = 0;
        }
        return d;
    }

    /** {@link #daily} plus the experiment charges brought up to now. */
    static PlayerData charges(Player p) {
        PlayerData d = daily(p);
        ExperimentCharges.refresh(d, System.currentTimeMillis(), dials.experimentsMaxCharges.get(), chargePeriodMs());
        return d;
    }

    static long chargePeriodMs() {
        return Math.max(60_000L, Math.round(dials.experimentsChargeHours.get() * 3_600_000.0));
    }

    /** +% chance for ultra rares in Superpairs: Guardian's Lucky Seven, plus Enderman Slayer 8 (wiki: +15%). */
    static double ultraRareBoost(Player p) {
        double boost = PetPerks.luckySeven(p);
        try {
            com.skyclone.api.feature.Features.Slayers sl = Sky.service(com.skyclone.api.feature.Features.Slayers.class);
            if (p != null && sl != null && sl.level(p, "enderman") >= 8) boost += dials.endermanUltraRareBoost.get();
        } catch (RuntimeException ignored) { }
        return boost;
    }

    static int enchantingLevel(Player p) {
        return Sky.service(SkillService.class).level(p, SkillType.ENCHANTING);
    }

    static void addEnchantingXp(Player p, double xp, String source) {
        if (xp <= 0) return;
        Sky.service(SkillService.class).addXp(p, SkillType.ENCHANTING, xp, source);
    }

    /**
     * Give vanilla experience points the way an orb would: PlayerExpChangeEvent fires first, so the
     * skills module's Conjurer perk (more experience orbs) applies to everything this module awards.
     */
    static void giveExp(Player p, int amount) {
        if (amount <= 0 || !p.isOnline()) return;
        PlayerExpChangeEvent e = new PlayerExpChangeEvent(p, amount);
        Bukkit.getPluginManager().callEvent(e);
        if (e.getAmount() > 0) p.giveExp(e.getAmount());
    }

    /** Take XP levels; false (and nothing taken) when short. Creative players pay nothing. */
    static boolean takeLevels(Player p, int levels) {
        if (levels <= 0 || p.getGameMode() == org.bukkit.GameMode.CREATIVE) return true;
        if (p.getLevel() < levels) return false;
        p.giveExpLevels(-levels);
        PlayerData d = data(p);
        d.levelsSpent += levels;
        return true;
    }

    // ---- data loading ---------------------------------------------------------------------------------

    private static Reader resource(String file) {
        InputStream in = Sky.plugin().getResource("data/" + KEY + "/" + file);
        if (in == null) throw new IllegalStateException("missing data/" + KEY + "/" + file);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    static EnchantRegistry loadRegistry() {
        try (Reader r = resource("enchants.json")) {
            return EnchantRegistry.read(r);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("enchants.json unreadable", e);
        }
    }

    static ExperimentSpec loadExperiments() {
        try (Reader r = resource("experiments.json")) {
            ExperimentSpec s = new Gson().fromJson(r, ExperimentSpec.class);
            return s == null ? new ExperimentSpec() : s;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("experiments.json unreadable", e);
        }
    }

    static Map<String, AnvilRules.Endcap> loadEndcaps() {
        try (Reader r = resource("anvil_upgrades.json")) {
            AnvilRules.Endcap[] rows = new Gson().fromJson(r, AnvilRules.Endcap[].class);
            Map<String, AnvilRules.Endcap> out = new LinkedHashMap<>();
            if (rows != null) for (AnvilRules.Endcap e : rows) if (e != null && e.item != null) out.put(e.item, e);
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("anvil_upgrades.json unreadable", e);
        }
    }

    static Reader openResource(String file) { return resource(file); }
}
