package com.skyclone.mod.slayer;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every slayer menu. Each one is reachable from /slayer and from the Slayer master / SkyBlock Menu. */
final class SlayerMenus {

    private final SlayerModule mod;
    private Map<String, List<String>> unlockCache;

    static final int[] INNER = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    static final char[] TIER_COLOR = {'a', 'e', 'c', '4', '5'};

    SlayerMenus(SlayerModule mod) { this.mod = mod; }

    private SlayerData data(Player p) { return mod.quests.data(p); }

    // ---- shared icons -------------------------------------------------------------------------------

    static Material mat(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m == null || !m.isItem() ? fallback : m;
    }

    /**
     * A sky item's name in its rarity colour, with what tells it apart when the name alone cannot: every enchanted
     * book level is called "Enchanted Book", so the RNG meter list and the drop lines read
     * "&9Enchanted Book (Smite VI)" (wave-4 loops: the meter entry said only "Enchanted Book").
     */
    static String itemName(ItemDef d) {
        if (d == null) return "&7?";
        String base = Text.strip(d.name == null ? Text.titleCase(d.id) : d.name);
        String ench = null;
        int level = 0;
        if (d.extra != null && d.extra.has("enchant")) {
            try {
                String id = d.extra.get("enchant").getAsString();
                level = d.extra.has("level") ? d.extra.get("level").getAsInt() : 0;
                ench = Text.titleCase(id);
                com.skyclone.api.item.EnchantDef ed = Sky.items().enchant(id);
                if (ed != null && ed.name != null && !ed.name.isBlank()) ench = Text.strip(ed.name);
            } catch (RuntimeException ignored) { }
        }
        return (d.rarity == null ? "&f" : d.rarity.code()) + SlayerMath.withEnchant(base, ench, level);
    }

    /** {@link #itemName(ItemDef)} by id; the bare id when nothing is registered under it. */
    static String itemName(String id) {
        ItemDef d = id == null ? null : Sky.items().def(id);
        return d == null ? "&7" + id : itemName(d);
    }

    /** A display-only copy of a sky item (no sky id, no uuid: nothing in a menu can become a real item). */
    static ItemStack display(String itemId, List<String> extra) {
        ItemDef d = Sky.items().def(itemId);
        if (d == null) return Icons.of(Material.BARRIER, "&c" + itemId, extra);
        String name = itemName(d);
        List<String> lore = new ArrayList<>(extra);
        ItemStack it = d.skull != null && !d.skull.isBlank() ? Icons.skull(d.skull, name, lore) : Icons.of(mat(d.material, Material.PAPER), name, lore);
        if (d.glow) Icons.glow(it);
        return it;
    }

    /** Is this loot entry shown in the drop / RNG lists? Registered items, and shards while hunting is loaded. */
    static boolean shown(SlayerCatalog.LootEntry e) {
        return e.shard ? SlayerLoot.available(e) : Sky.items().exists(e.item);
    }

    /** Menu icon of a loot entry: the item, or a Hunting shard (not an item: drawn here, display-only). */
    static ItemStack lootIcon(SlayerCatalog.LootEntry e, List<String> lore) {
        if (!e.shard) return display(e.item, lore);
        ItemStack it = Icons.of(Material.PRISMARINE_SHARD, SlayerLoot.entryName(e), lore);
        Icons.glow(it);
        return it;
    }

    /** Name for a dropLog key: an item's name, or a shard's (shards are not items). */
    String logName(String id) {
        ItemDef def = Sky.items().def(id);
        if (def != null) return itemName(def);
        for (SlayerCatalog.Type t : mod.catalog.types) {
            SlayerCatalog.LootEntry e = t.lootEntry(id);
            if (e != null && e.shard) return SlayerLoot.entryName(e);
        }
        return "&7" + id;
    }

    static String tierName(int tier) { return "&" + TIER_COLOR[Math.max(0, Math.min(4, tier - 1))] + "Tier " + SlayerMath.roman(tier); }

    private void backToSkyMenu(Menu m) {
        if (Sky.has(Features.SkyMenu.class)) {
            m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        }
    }

    private List<String> xpLines(Player p, SlayerCatalog.Type t) {
        SlayerData d = data(p);
        double xp = d.xp(t.id);
        int lvl = SlayerMath.level(t.xp, xp);
        long next = SlayerMath.nextThreshold(t.xp, xp);
        List<String> l = new ArrayList<>();
        l.add("&7Level: &e" + lvl + " &8(" + t.levelName(lvl) + ")");
        if (next < 0) {
            l.add("&7XP: &d" + Text.commasInt(xp) + " &a&lMAXED");
        } else {
            long start = SlayerMath.levelStart(t.xp, xp);
            l.add("&7Progress to " + SlayerMath.roman(lvl + 1) + ": &d" + Text.commasInt(xp - start) + "&7/&d" + Text.commasInt(next - start));
            l.add(Text.bar(SlayerMath.levelProgress(t.xp, xp), 20));
        }
        return l;
    }

    // ---- main -----------------------------------------------------------------------------------------

    void openMain(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        Menu m = new Menu("Slayer", 6).chrome();
        SlayerData d = data(p);
        List<String> head = new ArrayList<>();
        head.add("&7Hunt the bosses that lurk behind");
        head.add("&7the island's monsters, earn slayer XP");
        head.add("&7and craft their gear.");
        head.add("");
        int total = 0;
        for (SlayerCatalog.Type t : mod.catalog.types) {
            int lvl = mod.level(p, t);
            total += lvl;
            head.add(" &" + t.color + t.slayerName() + ": &e" + lvl);
        }
        head.add("");
        head.add("&7Total slayer levels: &e" + total);
        head.add("&7Bosses slain: &a" + Text.commasInt(d.questsCompleted));
        m.set(4, Icons.playerHead(p.getUniqueId(), "&5&lSlayer Overview", head));

        int[] slots = {19, 20, 21, 23, 24, 25};
        for (int i = 0; i < mod.catalog.types.size() && i < slots.length; i++) {
            SlayerCatalog.Type t = mod.catalog.types.get(i);
            m.set(slots[i], typeIcon(p, t), c -> openType(c.player(), t));
        }
        m.set(22, questIcon(p), c -> {
            SlayerData dd = data(c.player());
            if (!dd.hasQuest()) return;
            if (c.right()) { confirmCancel(c.player()); return; }
            if (mod.quests.canCallNow(c.player())) { c.player().closeInventory(); mod.quests.summonReady(c.player()); return; }
            SlayerCatalog.Type t = mod.catalog.type(dd.questType);
            if (t != null) openType(c.player(), t);
        });
        m.set(38, Icons.of(Material.EXPERIENCE_BOTTLE, "&dRNG Meter", "&7Pick a rare drop for each slayer.", "&7Slayer XP fills its meter; a full",
                "&7meter guarantees that drop.", "", "&eClick to view!"), c -> openRngAll(c.player()));
        m.set(39, Icons.of(Material.EMERALD, "&aSlayer Shop", "&7Mixins, stakes and your free", "&7Slayer Hotline.", "", "&eClick to browse!"),
                c -> openShop(c.player()));
        m.set(40, Icons.of(Material.WRITABLE_BOOK, "&eSlayer Statistics", "&7Kills per tier, best times,", "&7and every drop you have found.", "",
                "&eClick to view!"), c -> openStats(c.player()));
        m.set(41, Icons.of(Material.BOOK, "&bHow Slayers Work", "&7A short guide to quests, bosses,", "&7levels and the RNG meter.", "", "&eClick to read!"),
                c -> openHelp(c.player()));
        m.set(42, autoSummary(p), c -> openAutoAll(c.player()));
        backToSkyMenu(m);
        m.refreshEvery(20).drawer(mm -> mm.set(22, questIcon(p), mm.handler(22)));
        m.open(p);
        Fx.sound(p, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f);
    }

    ItemStack typeIcon(Player p, SlayerCatalog.Type t) {
        String lock = mod.quests.typeLock(p, t);
        SlayerData d = data(p);
        List<String> lore = new ArrayList<>();
        lore.add("&8" + t.slayerName());
        lore.add("");
        if (lock != null) {
            lore.add("&cLocked");
            lore.add(lock);
            return Icons.of(Material.BARRIER, "&c☠ " + t.name, lore);
        }
        lore.addAll(xpLines(p, t));
        lore.add("");
        lore.add("&7Bosses slain: &a" + Text.commasInt(d.totalKills(t.id)));
        int hi = d.highestTierKilled(t.id);
        lore.add("&7Highest tier: " + (hi == 0 ? "&8none" : tierName(hi)));
        if (t.id.equals(d.questType)) {
            lore.add("");
            lore.add("&5&lACTIVE QUEST");
        }
        lore.add("");
        lore.add("&eClick to view!");
        ItemStack it = Icons.of(mat(t.icon, Material.ROTTEN_FLESH), "&" + t.color + "☠ " + t.name, lore);
        return t.id.equals(d.questType) ? Icons.glow(it) : it;
    }

    ItemStack questIcon(Player p) {
        SlayerData d = data(p);
        if (!d.hasQuest()) {
            return Icons.of(Material.MAP, "&aNo Active Quest", "&7Pick a slayer to start a hunt.", "",
                    "&7Kill its monsters to fill the bar,", "&7then defeat the boss that appears.");
        }
        SlayerCatalog.Type t = mod.catalog.type(d.questType);
        SlayerCatalog.Tier tier = t == null ? null : t.tier(d.questTier);
        if (tier == null) return Icons.of(Material.MAP, "&cBroken Quest", "&7Right-click to cancel.");
        List<String> lore = new ArrayList<>();
        lore.add("&" + t.color + t.bossName(tier.tier) + " " + SlayerMath.roman(tier.tier));
        lore.add("");
        double target = mod.quests.spawnTarget(tier);
        switch (d.questState == null ? Quests.COLLECTING : d.questState) {
            case Quests.FIGHTING -> lore.add("&cSlay the boss!");
            case Quests.READY -> lore.add("&eYour boss is waiting to be summoned.");
            case Quests.SPAWNING -> {
                if (mod.quests.isGathering(p)) {
                    lore.add("&cThe boss stirs. It rises once you are");
                    lore.add("&chealed and out of the fight.");
                } else {
                    lore.add("&cThe boss is coming...");
                }
            }
            default -> {
                lore.add("&7Kill " + t.mobPlural().toLowerCase(java.util.Locale.ROOT) + " to summon the boss:");
                lore.add("&7(&e" + Text.commasInt(d.questProgress) + "&7/&c" + Text.commasInt(target) + "&7) Combat XP");
                lore.add(Text.bar(d.questProgress / target, 20));
            }
        }
        lore.add("");
        if (Quests.READY.equals(d.questState)) lore.add("&eClick to summon!");
        else if (mod.quests.isGathering(p)) lore.add("&eClick to call it now!");
        else lore.add("&eLeft-click to view!");
        lore.add("&cRight-click to cancel the quest.");
        return Icons.glow(Icons.of(Material.FILLED_MAP, "&5&lActive Slayer Quest", lore));
    }

    // ---- type -----------------------------------------------------------------------------------------

    void openType(Player p, SlayerCatalog.Type t) {
        if (!Sky.profiles().isReady(p)) return;
        String lock = mod.quests.typeLock(p, t);
        if (lock != null) {
            Msg.send(p, lock);
            Msg.sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
            return;
        }
        Menu m = new Menu(t.name, 6).chrome();
        List<String> head = new ArrayList<>();
        head.add("&8" + t.slayerName());
        head.add("");
        head.addAll(xpLines(p, t));
        head.add("");
        head.add("&7Kill &f" + t.mobPlural().toLowerCase(java.util.Locale.ROOT) + " &7to summon it.");
        m.set(4, Icons.of(mat(t.icon, Material.ROTTEN_FLESH), "&" + t.color + "&l" + t.name, head));
        int n = t.tiers.size();
        int start = n >= 5 ? 11 : 11;
        for (int i = 0; i < n; i++) {
            SlayerCatalog.Tier tier = t.tiers.get(i);
            int slot = n == 4 ? new int[]{11, 12, 14, 15}[i] : start + i;
            m.set(slot, tierIcon(p, t, tier), c -> confirmStart(c.player(), t, tier));
        }
        SlayerData d = data(p);
        if (t.id.equals(d.questType)) m.set(22, questIcon(p), c -> {
            if (c.right()) confirmCancel(c.player());
            else if (mod.quests.canCallNow(c.player())) { c.player().closeInventory(); mod.quests.summonReady(c.player()); }
        });
        int hiTier = Math.max(1, Math.min(t.tiers.size(), Math.max(d.highestTierKilled(t.id) + 1, 1)));
        m.set(29, Icons.of(Material.CHEST, "&6Boss Drops", "&7Everything the " + t.name, "&7can drop, tier by tier, with your",
                "&7real odds.", "", "&eClick to view!"), c -> openDrops(c.player(), t, hiTier));
        m.set(30, Icons.of(Material.GOLD_BLOCK, "&aLevel Rewards", "&7Stats and recipes for every", "&7" + t.slayerName() + " level.", "",
                "&eClick to view!"), c -> openRewards(c.player(), t));
        m.set(31, rngIcon(p, t, true), c -> openRng(c.player(), t));
        m.set(32, Icons.of(Material.WITHER_SKELETON_SKULL, "&cMinibosses", "&7Rare guardians that crawl out", "&7while you fill a quest bar.", "",
                "&eClick to view!"), c -> openMinis(c.player(), t));
        m.set(33, autoIcon(p, t), c -> toggleAuto(c.player(), t, () -> openType(c.player(), t)));
        back(m, "Slayer", this::openMain);
        m.open(p);
    }

    /** "Go Back" at slot 48 that opens another of our menus (Menu.back would build a second copy). */
    static void back(Menu m, String where, java.util.function.Consumer<Player> to) {
        m.set(m.closeSlot() - 1, Icons.back(where), c -> to.accept(c.player()));
    }

    ItemStack tierIcon(Player p, SlayerCatalog.Type t, SlayerCatalog.Tier tier) {
        List<String> lore = new ArrayList<>();
        lore.add("&8" + tier.difficulty);
        lore.add("");
        double hp = tier.health * ("vampire".equals(t.id) ? SlayerDials.VAMPIRE_HP_SCALE.get() : 1) * SlayerDials.BOSS_HP_SCALE.get();
        lore.add("&7Health: &c" + Text.commasInt(hp) + "❤");
        if ("vampire".equals(t.id)) {
            lore.add("&7Damage: &c" + Text.commas(tier.dps) + " heart" + (tier.dps == 1 ? "" : "s") + " &7per second");
        } else if ("blaze".equals(t.id) && t.immolate != null) {
            double[] row = t.immolate[Math.min(t.immolate.length, tier.tier) - 1];
            lore.add("&7Immolate: &f" + Text.commasInt(row[0]) + "&7/&f" + Text.commasInt(row[1]) + "&7/&f" + Text.commasInt(row[2]) + " &7true /s");
        } else {
            lore.add("&7Damage: &c" + Text.commasInt(tier.dps * SlayerDials.BOSS_DAMAGE_SCALE.get()) + " &7per second");
        }
        if (tier.trueDps > 0) lore.add("&7True Damage: &f" + Text.commasInt(tier.trueDps) + " &7per second");
        if ("zombie".equals(t.id)) {
            // The damage line above is the whole bill: melee + Life Drain + Pestilence add up to it (wave-4 loops; the
            // abilities used to come on top of it, x1.55 at T3).
            double mech = Math.max(0, SlayerDials.MECHANIC_DAMAGE_SCALE.get());
            double drainS = SlayerDials.ZOMBIE_DRAIN_SECONDS.get();
            double melee = tier.dps * SlayerMath.ghoulMeleeShare(tier.tier, drainS) * Math.max(0, SlayerDials.BOSS_DAMAGE_SCALE.get());
            lore.add("&8  of which:");
            lore.add("&7  Melee: &c" + Text.commasInt(melee) + " &7per second" + (tier.tier >= 3 && tier.tier <= 4 ? " &8(x2 enraged)" : ""));
            lore.add("&7  Life Drain: &c" + Text.commasInt(SlayerMath.lifeDrain(tier.dps, drainS) * mech)
                    + " &7every &a" + Text.commas(SlayerMath.DRAIN_INTERVAL) + "s");
            if (tier.tier >= 2) lore.add("&7  Pestilence: &c" + Text.commasInt(SlayerMath.pestilence(tier.dps) * mech) + " &7per second nearby");
            lore.add("&8  Defense reduces all of these.");
        }
        lore.addAll(standingLines(p, t, tier));
        lore.add("");
        for (SlayerCatalog.Ability a : t.abilities) {
            if (a.tier > tier.tier) continue;
            lore.add("&a" + a.name);
            lore.addAll(Text.wrap("&7" + a.desc, 34));
        }
        lore.add("");
        lore.add("&7Reward: &d" + Text.commas(tier.slayerXp * SlayerDials.XP_SCALE.get()) + " " + t.slayerName() + " XP");
        lore.add("&8  + boss drops and RNG meter progress");
        lore.add("&7Combat XP to spawn: &c" + Text.commasInt(mod.quests.spawnTarget(tier)));
        double price = mod.quests.price(p, t, tier);
        double base = mod.quests.basePrice(t, tier);
        lore.add("&7Cost to start: " + Quests.feeText(t, price) + (price < base ? " &a(sale!)" : ""));
        SlayerData d = data(p);
        int k = d.kills(t.id, tier.tier);
        Long best = d.bestTime.get(t.id + ":" + tier.tier);
        lore.add("&7Kills: &a" + Text.commasInt(k) + (best == null ? "" : " &8| &7Best: &e" + SlayerMath.clock(best)));
        lore.add("");
        String lock = mod.quests.tierLock(p, t, tier);
        ItemStack it;
        if (lock != null) {
            lore.add(lock);
            it = Icons.of(Material.COAL_BLOCK, "&c" + t.bossName(tier.tier) + " " + SlayerMath.roman(tier.tier), lore);
        } else if (d.hasQuest()) {
            lore.add("&cYou already have an active quest!");
            it = Icons.of(tierMaterial(tier.tier), tierName(tier.tier) + " &8- &" + t.color + t.bossName(tier.tier), lore);
        } else {
            lore.add("&eClick to slay!");
            it = Icons.of(tierMaterial(tier.tier), tierName(tier.tier) + " &8- &" + t.color + t.bossName(tier.tier), lore);
        }
        it.setAmount(Math.max(1, tier.tier));
        return it;
    }

    /**
     * What this tier does to YOU, from your current Health and Defense: "Against you: ~21❤/s" and how long you last
     * standing in it with no healing. Wave-4 loops: a player paid 20,000 coins twice for a T3 Ghoul their kit could
     * not survive, with nothing on the way in saying so; the menu now does that arithmetic before the fee. A floor,
     * not a forecast (Life Steal, regen and wands stretch it). Empty for the Bloodfiend (Rift hearts).
     */
    List<String> standingLines(Player p, SlayerCatalog.Type t, SlayerCatalog.Tier tier) {
        List<String> out = new ArrayList<>();
        if ("vampire".equals(t.id) || p == null) return out;
        double hp, def, tdef;
        try {
            hp = Sky.vitals().maxHealth(p);
            def = Sky.stats().get(p, Stat.DEFENSE);
            tdef = Sky.stats().get(p, Stat.TRUE_DEFENSE);
        } catch (RuntimeException e) {
            return out;
        }
        if (!(hp > 0)) return out;
        double mech = Math.max(0, SlayerDials.MECHANIC_DAMAGE_SCALE.get());
        double dmgScale = Math.max(0, SlayerDials.BOSS_DAMAGE_SCALE.get());
        boolean ghoul = "zombie".equals(t.id);
        boolean shred = ghoul && tier.tier >= 2;
        double effDef = shred ? def * 0.75 : def;
        double incoming;
        if (ghoul) {
            incoming = SlayerMath.afterDefense(SlayerMath.ghoulRawPerSecond(tier.tier, tier.dps,
                    SlayerDials.ZOMBIE_DRAIN_SECONDS.get(), dmgScale, mech), effDef);
        } else {
            double trueRaw = tier.trueDps * mech;
            if ("blaze".equals(t.id) && t.immolate != null && t.immolate.length > 0) {
                double[] row = t.immolate[Math.min(t.immolate.length, tier.tier) - 1];
                if (row != null && row.length > 0) trueRaw += row[0] * mech;
            }
            incoming = SlayerMath.afterDefense(tier.dps * dmgScale, def) + SlayerMath.afterDefense(trueRaw, tdef);
        }
        double secs = SlayerMath.secondsToLast(hp, incoming);
        out.add("");
        out.add("&7Against you: &c~" + Text.commasInt(incoming) + "❤/s &8(" + Text.commasInt(def) + "❈ Defense"
                + (shred ? ", " + Text.commasInt(effDef) + " under Pestilence" : "") + ")");
        if (Double.isInfinite(secs)) {
            out.add("&7Nothing gets through your Defense.");
        } else {
            String col = secs >= 90 ? "&a" : secs >= 30 ? "&e" : "&c";
            long s = (long) Math.floor(secs);
            out.add("&7Your " + Text.commasInt(hp) + "❤ last " + col + "~" + (s < 120 ? s + "s" : SlayerMath.clock(s))
                    + " &7with no healing.");
            if (secs < 90) out.add("&8  Kill it first, out-heal it or bring more Health.");
        }
        if (!ghoul) out.add("&8  Its abilities hit on top of this.");
        return out;
    }

    static Material tierMaterial(int tier) {
        return switch (tier) {
            case 1 -> Material.LIME_TERRACOTTA;
            case 2 -> Material.YELLOW_TERRACOTTA;
            case 3 -> Material.RED_TERRACOTTA;
            case 4 -> Material.BROWN_TERRACOTTA;
            default -> Material.PURPLE_TERRACOTTA;
        };
    }

    void confirmStart(Player p, SlayerCatalog.Type t, SlayerCatalog.Tier tier) {
        String lock = mod.quests.tierLock(p, t, tier);
        if (lock != null) { Msg.send(p, lock); Msg.sound(p, Sound.ENTITY_VILLAGER_NO, 1f); return; }
        if (data(p).hasQuest()) { Msg.error(p, "You already have an active slayer quest!"); return; }
        double price = mod.quests.price(p, t, tier);
        List<String> lines = new ArrayList<>(List.of(
                "&" + t.color + t.bossName(tier.tier) + " " + SlayerMath.roman(tier.tier),
                "&7Cost: " + Quests.feeText(t, price),
                "&7Kill &c" + Text.commasInt(mod.quests.spawnTarget(tier)) + " Combat XP &7worth of " + t.mobPlural().toLowerCase(java.util.Locale.ROOT) + "."));
        lines.addAll(standingLines(p, t, tier));
        lines.add("");
        lines.add("&cThe fee is not refunded if you fail.");
        Sky.menus().confirm(p, "Start " + SlayerMath.roman(tier.tier) + "?", lines, () -> {
                    p.closeInventory();
                    mod.quests.start(p, t.id, tier.tier, false, false);
                }, null);
    }

    void confirmCancel(Player p) {
        SlayerData d = data(p);
        if (!d.hasQuest()) { Msg.error(p, "You have no active slayer quest."); return; }
        Sky.menus().confirm(p, "Cancel Quest?", List.of("&7Abandon your current slayer quest?", "&cYou will NOT get the fee back."),
                () -> { p.closeInventory(); mod.quests.cancel(p); }, null);
    }

    // ---- drops ----------------------------------------------------------------------------------------

    void openDrops(Player p, SlayerCatalog.Type t, int tierNo) {
        int tier = Math.max(1, Math.min(t.tiers.size(), tierNo));
        Menu m = new Menu(t.name + " Drops", 6).chrome();
        for (int i = 1; i <= t.tiers.size(); i++) {
            final int ti = i;
            ItemStack b = Icons.of(tierMaterial(i), tierName(i) + (i == tier ? " &a(viewing)" : ""), "&7Show the drops of this tier.", "", "&eClick to view!");
            if (i == tier) Icons.glow(b);
            b.setAmount(i);
            m.set(1 + i, b, c -> openDrops(c.player(), t, ti));
        }
        Map<String, Double> ch = mod.loot.chancesFor(p, t, tier);
        Map<String, Double> share = mod.loot.chancesFor(p, t, tier, true);
        double meter = mod.loot.meterMult(p, t);
        SlayerData d = data(p);
        int lvl = mod.level(p, t);
        String sel = d.rngSelected.get(t.id);
        double mf = SlayerLoot.magicFind(p, t, tier);
        int idx = 0;
        for (SlayerCatalog.LootEntry e : t.loot) {
            if (!e.dropsAt(tier) || idx >= INNER.length) continue;
            if (e.shard ? !SlayerLoot.available(e) : !Sky.items().exists(e.item)) continue;
            List<String> lore = new ArrayList<>();
            if (e.shard) lore.add("&8Hunting shard: goes to your Hunting Box");
            if (e.independent && !e.item.startsWith("DYE_")) lore.add("&8Rolled on its own, on top of the other drops");
            if (e.lootshareOnly) {
                lore.add("&6Lootshare only: &7drops to players who help kill");
                lore.add("&7someone else's boss, never to the summoner.");
            }
            double pct;
            if (e.token) pct = 100;
            else if (e.independent) pct = mod.loot.independentFor(p, t, e, tier, Math.max(lvl, e.minLevel), mf, meter) * 100;
            else if (e.lootshareOnly) pct = share.getOrDefault(e.item, 0.0) * 100 * SlayerLoot.LOOTSHARE_ROLL;
            else pct = ch.getOrDefault(e.item, 0.0) * 100;
            String amount = e.minAt(tier) == e.maxAt(tier) ? "" + e.minAt(tier) : e.minAt(tier) + "-" + e.maxAt(tier);
            lore.add("");
            lore.add("&7Amount: &e" + amount);
            if (lvl >= e.minLevel || e.token) {
                lore.add("&7Your chance: " + SlayerLoot.label(pct) + " &7(" + fmtPct(pct) + ")");
            } else {
                lore.add("&7Chance at max level: " + SlayerLoot.label(e.chanceAt(tier)) + " &7(" + fmtPct(e.chanceAt(tier)) + ")");
                lore.add("&cRequires " + t.slayerName() + " " + e.minLevel);
            }
            if (e.rng > 0) {
                lore.add("&7RNG Meter: &d" + Text.commasInt(e.rng) + " XP");
            }
            Long found = d.dropLog.get(e.item);
            if (found != null && found > 0) lore.add("&7You found: &a" + Text.commasInt(found));
            if (e.rng > 0 && lvl >= e.minLevel) {
                lore.add("");
                lore.add(e.item.equals(sel) ? "&a✔ Selected for the RNG meter" : "&eClick to select for the RNG meter!");
            }
            ItemStack icon = lootIcon(e, lore);
            if (e.item.equals(sel)) Icons.glow(icon);
            m.set(INNER[idx++], icon, c -> {
                if (e.rng > 0 && mod.level(c.player(), t) >= e.minLevel) {
                    selectRng(c.player(), t, e);
                    openDrops(c.player(), t, tier);
                }
            });
        }
        m.set(50, Icons.of(Material.ENCHANTED_BOOK, "&bAbout these odds", "&7One drop is picked per kill from", "&7the pool your level allows.",
                "&7Levelling up adds rarer drops, which", "&7makes common ones a little rarer.", "&b✯ Magic Find &7boosts drops under &a5%&7."));
        back(m, t.name, pl -> openType(pl, t));
        m.open(p);
    }

    static String fmtPct(double pct) {
        if (pct >= 1) return Text.commas(Math.round(pct * 100) / 100.0) + "%";
        if (pct >= 0.01) return String.format(java.util.Locale.US, "%.3f%%", pct);
        if (pct <= 0) return "0%";
        return String.format(java.util.Locale.US, "1 in %s", Text.commasInt(100 / pct));
    }

    // ---- rng --------------------------------------------------------------------------------------------

    ItemStack rngIcon(Player p, SlayerCatalog.Type t, boolean click) {
        List<String> lore = new ArrayList<>();
        lore.add("&8" + t.name);
        lore.add("");
        lore.addAll(rngLines(p, t));
        if (click) { lore.add(""); lore.add("&eClick to view!"); }
        return Icons.of(Material.EXPERIENCE_BOTTLE, "&dRNG Meter", lore);
    }

    List<String> rngLines(Player p, SlayerCatalog.Type t) {
        SlayerData d = data(p);
        String sel = d.rngSelected.get(t.id);
        SlayerCatalog.LootEntry e = sel == null ? null : t.lootEntry(sel);
        double xp = d.rngXp.getOrDefault(t.id, 0.0);
        List<String> lore = new ArrayList<>();
        if (e == null || !shown(e)) {
            lore.add("&7No drop selected.");
            lore.add("&7Banked meter XP: &d" + Text.commasInt(xp));
        } else {
            double prog = SlayerMath.meterProgress(xp, e.rng);
            lore.add("&7Selected: " + SlayerLoot.entryName(e));
            lore.add("&7Progress: &d" + Text.commasInt(Math.min(xp, e.rng)) + "&7/&d" + Text.commasInt(e.rng) + " &7(" + Text.commas(Math.floor(prog * 1000) / 10) + "%)");
            lore.add(Text.bar(prog, 20));
            if (SlayerDials.RNG_BOOST.get() > 0) {
                lore.add("&7Drop chance boost: &ax" + Text.commas(Math.round(SlayerMath.meterMultiplier(prog, SlayerDials.RNG_BOOST.get()) * 100) / 100.0));
            }
        }
        return lore;
    }

    void openRngAll(Player p) {
        Menu m = new Menu("RNG Meter", 4).chrome();
        int[] slots = {10, 11, 12, 14, 15, 16};
        for (int i = 0; i < mod.catalog.types.size() && i < slots.length; i++) {
            SlayerCatalog.Type t = mod.catalog.types.get(i);
            if (mod.quests.typeLock(p, t) != null) {
                m.set(slots[i], Icons.of(Material.BARRIER, "&c" + t.name, "&cLocked"));
                continue;
            }
            List<String> lore = rngLines(p, t);
            lore.add("");
            lore.add("&eClick to view!");
            m.set(slots[i], Icons.of(mat(t.icon, Material.EXPERIENCE_BOTTLE), "&" + t.color + t.name, lore), c -> openRng(c.player(), t));
        }
        back(m, "Slayer", this::openMain);
        m.open(p);
    }

    void openRng(Player p, SlayerCatalog.Type t) {
        Menu m = new Menu(t.name + " RNG Meter", 6).chrome();
        m.set(4, rngIcon(p, t, false));
        SlayerData d = data(p);
        int lvl = mod.level(p, t);
        String sel = d.rngSelected.get(t.id);
        int idx = 0;
        for (SlayerCatalog.LootEntry e : t.loot) {
            if (e.rng <= 0 || e.token || idx >= INNER.length || !shown(e)) continue;
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("&7Meter XP needed: &d" + Text.commasInt(e.rng));
            lore.add("&7Drops from: " + tierName(firstTier(e)) + "&7+");
            boolean ok = lvl >= e.minLevel;
            if (!ok) lore.add("&cRequires " + t.slayerName() + " " + e.minLevel);
            lore.add("");
            if (e.item.equals(sel)) lore.add("&a✔ Selected &8(click to clear)");
            else if (ok) lore.add("&eClick to select!");
            ItemStack icon = lootIcon(e, lore);
            if (e.item.equals(sel)) Icons.glow(icon);
            m.set(INNER[idx++], icon, c -> {
                if (!ok) { Msg.error(c.player(), "Requires " + t.slayerName() + " " + e.minLevel + "!"); return; }
                selectRng(c.player(), t, e);
                openRng(c.player(), t);
            });
        }
        back(m, t.name, pl -> openType(pl, t));
        m.open(p);
    }

    private static int firstTier(SlayerCatalog.LootEntry e) {
        for (int i = 1; i <= e.chance.length; i++) if (e.chance[i - 1] > 0) return i;
        return 1;
    }

    void selectRng(Player p, SlayerCatalog.Type t, SlayerCatalog.LootEntry e) {
        SlayerData d = data(p);
        if (e.item.equals(d.rngSelected.get(t.id))) {
            d.rngSelected.remove(t.id);
            Msg.send(p, "&7RNG meter cleared. &8(Your meter XP stays banked.)");
        } else {
            d.rngSelected.put(t.id, e.item);
            Msg.success(p, "RNG meter set to " + SlayerLoot.entryName(e) + "&a.");
        }
        Fx.sound(p, Sound.UI_BUTTON_CLICK, 1f, 1.2f);
    }

    // ---- rewards ------------------------------------------------------------------------------------

    /** "Name" of every recipe/item unlocked at exactly this slayer level (scans live registries, cached). */
    List<String> unlocksAt(SlayerCatalog.Type t, int level) {
        if (unlockCache == null) {
            Map<String, List<String>> c = new HashMap<>();
            for (Recipe r : Sky.recipes().all()) {
                if (r.unlock == null || r.result == null) continue;
                for (String part : r.unlock.split("&")) {
                    String k = part.trim();
                    if (!k.startsWith("slayer:")) continue;
                    String[] s = k.split(":");
                    if (s.length < 3) continue;
                    String key = SlayerModule.alias(s[1]) + ":" + s[2].trim();
                    ItemDef def = Sky.items().def(r.result);
                    String name = def == null ? Text.titleCase(r.result) : itemName(def);
                    List<String> l = c.computeIfAbsent(key, x -> new ArrayList<>());
                    if (!l.contains(name)) l.add(name);
                }
            }
            unlockCache = c;
        }
        return unlockCache.getOrDefault(t.id + ":" + level, List.of());
    }

    void openRewards(Player p, SlayerCatalog.Type t) {
        Menu m = new Menu(t.slayerName() + " Rewards", 6).chrome();
        SlayerData d = data(p);
        double xp = d.xp(t.id);
        int lvl = SlayerMath.level(t.xp, xp);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 21, 22, 23};
        for (int i = 0; i < t.xp.length && i < slots.length; i++) {
            int L = i + 1;
            SlayerCatalog.Reward r = i < t.rewards.size() ? t.rewards.get(i) : null;
            List<String> lore = new ArrayList<>();
            lore.add("&8" + t.levelName(L));
            lore.add("");
            lore.add("&7XP required: &d" + Text.commasInt(t.xp[i]));
            if (L > lvl) lore.add("&7You need: &d" + Text.commasInt(Math.max(0, t.xp[i] - xp)) + " &7more");
            lore.add("");
            lore.add("&a&lREWARDS");
            if (r != null) {
                for (Map.Entry<String, Double> e : r.stats.entrySet()) {
                    Stat s = Stat.parse(e.getKey());
                    if (s != null) lore.add(" &8+&a" + Text.commas(e.getValue()) + " " + s.label());
                }
                for (String n : r.notes) lore.add(" &7" + n);
                if (r.sbXp > 0) lore.add(" &8+&b" + r.sbXp + " SkyBlock XP");
            }
            List<String> rec = unlocksAt(t, L);
            for (int k = 0; k < rec.size() && k < 8; k++) lore.add(" &7Recipe: " + rec.get(k));
            if (rec.size() > 8) lore.add(" &8...and " + (rec.size() - 8) + " more");
            lore.add("");
            Material mt;
            String name;
            if (L <= lvl) { mt = Material.LIME_STAINED_GLASS_PANE; name = "&a" + t.slayerName() + " " + SlayerMath.roman(L); lore.add("&aUnlocked!"); }
            else if (L == lvl + 1) { mt = Material.YELLOW_STAINED_GLASS_PANE; name = "&e" + t.slayerName() + " " + SlayerMath.roman(L); lore.add(Text.bar(SlayerMath.levelProgress(t.xp, xp), 20)); }
            else { mt = Material.RED_STAINED_GLASS_PANE; name = "&c" + t.slayerName() + " " + SlayerMath.roman(L); lore.add("&cLocked"); }
            ItemStack it = Icons.of(mt, name, lore);
            it.setAmount(L);
            m.set(slots[i], it);
        }
        List<String> tot = new ArrayList<>();
        Map<String, Double> sum = new LinkedHashMap<>();
        for (int i = 0; i < lvl && i < t.rewards.size(); i++) t.rewards.get(i).stats.forEach((k, v) -> sum.merge(k, v, Double::sum));
        tot.add("&7From " + t.slayerName() + " " + lvl + ":");
        if (sum.isEmpty()) tot.add(" &8nothing yet");
        sum.forEach((k, v) -> { Stat s = Stat.parse(k); if (s != null) tot.add(" &8+&a" + Text.commas(v) + " " + s.label()); });
        m.set(40, Icons.of(Material.NETHER_STAR, "&aYour Bonuses", tot));
        back(m, t.name, pl -> openType(pl, t));
        m.open(p);
    }

    // ---- minibosses -----------------------------------------------------------------------------------

    void openMinis(Player p, SlayerCatalog.Type t) {
        Menu m = new Menu(t.name + " Minibosses", 4).chrome();
        SlayerData d = data(p);
        int idx = 0;
        for (SlayerCatalog.Mini mi : t.minibosses) {
            MobDef def = Sky.mobs().def(mi.mob);
            if (def == null || idx >= 7) continue;
            List<String> lore = new ArrayList<>();
            lore.add("&8Level " + def.level);
            lore.add("");
            lore.add("&7Health: &c" + Text.commasInt(def.health) + "❤");
            lore.add("&7Appears during: " + tierName(mi.minTier) + "&7+ quests");
            lore.add("&7Combat XP: &3" + Text.commasInt(mi.combatXp) + " &8(fills your quest bar)");
            if (!def.drops.isEmpty()) {
                ItemDef id = Sky.items().def(def.drops.get(0).item);
                lore.add("&7Drops: &a" + def.drops.get(0).min + "x " + (id == null ? def.drops.get(0).item : Text.strip(id.name)));
            }
            lore.add("&7You killed: &a" + d.miniKills.getOrDefault(mi.mob, 0));
            lore.add("");
            lore.add("&8Chance per kill: " + Text.commas(SlayerDials.MINIBOSS_CHANCE.get() * 100) + "%");
            m.set(10 + idx++, Icons.of(Material.WITHER_SKELETON_SKULL, "&c" + def.name, lore));
        }
        if (idx == 0) m.set(13, Icons.of(Material.GRAY_DYE, "&7No minibosses", "&7This slayer has no minibosses."));
        back(m, t.name, pl -> openType(pl, t));
        m.open(p);
    }

    // ---- auto-slayer ----------------------------------------------------------------------------------

    ItemStack autoIcon(Player p, SlayerCatalog.Type t) {
        int need = SlayerDials.AUTO_SLAYER_LEVEL.get();
        boolean on = data(p).autoSlayer.contains(t.id);
        if (mod.level(p, t) < need) {
            return Icons.of(Material.REPEATER, "&cAuto-Slayer", "&7Restarts your last quest the", "&7moment you kill its boss.", "",
                    "&cRequires " + t.slayerName() + " " + need + ".");
        }
        ItemStack it = Icons.of(on ? Material.COMPARATOR : Material.REPEATER, "&aAuto-Slayer " + (on ? "&a&lON" : "&c&lOFF"),
                "&7Restarts your last quest the", "&7moment you kill its boss, paying", "&7the fee from your purse.", "", "&eClick to toggle!");
        return on ? Icons.glow(it) : it;
    }

    void toggleAuto(Player p, SlayerCatalog.Type t, Runnable reopen) {
        if (mod.level(p, t) < SlayerDials.AUTO_SLAYER_LEVEL.get()) {
            Msg.error(p, "Auto-Slayer needs " + t.slayerName() + " " + SlayerDials.AUTO_SLAYER_LEVEL.get() + "!");
            return;
        }
        SlayerData d = data(p);
        if (!d.autoSlayer.remove(t.id)) d.autoSlayer.add(t.id);
        Msg.send(p, "&7Auto-Slayer for " + t.name + ": " + (d.autoSlayer.contains(t.id) ? "&a&lON" : "&c&lOFF"));
        reopen.run();
    }

    private ItemStack autoSummary(Player p) {
        SlayerData d = data(p);
        List<String> lore = new ArrayList<>();
        lore.add("&7Restarts a quest the moment its");
        lore.add("&7boss dies. Unlocks at level &e" + SlayerDials.AUTO_SLAYER_LEVEL.get() + "&7.");
        lore.add("");
        for (SlayerCatalog.Type t : mod.catalog.types) {
            boolean ok = mod.level(p, t) >= SlayerDials.AUTO_SLAYER_LEVEL.get();
            lore.add(" &" + t.color + t.mobWord + ": " + (!ok ? "&8locked" : d.autoSlayer.contains(t.id) ? "&aON" : "&cOFF"));
        }
        lore.add("");
        lore.add("&eClick to manage!");
        return Icons.of(Material.REPEATER, "&aAuto-Slayer", lore);
    }

    void openAutoAll(Player p) {
        Menu m = new Menu("Auto-Slayer", 4).chrome();
        int[] slots = {10, 11, 12, 14, 15, 16};
        for (int i = 0; i < mod.catalog.types.size() && i < slots.length; i++) {
            SlayerCatalog.Type t = mod.catalog.types.get(i);
            ItemStack ic = autoIcon(p, t);
            var meta = ic.getItemMeta();
            if (meta != null) { meta.displayName(Text.c("&" + t.color + t.name + " &8- " + Text.toLegacy(meta.displayName()))); ic.setItemMeta(meta); }
            m.set(slots[i], ic, c -> toggleAuto(c.player(), t, () -> openAutoAll(c.player())));
        }
        back(m, "Slayer", this::openMain);
        m.open(p);
    }

    // ---- shop -----------------------------------------------------------------------------------------

    void openShop(Player p) {
        Menu m = new Menu("Slayer Shop", 5).chrome();
        SlayerData d = data(p);
        // free hotline
        SlayerCatalog.Type wolf = mod.catalog.type("wolf");
        boolean hotOk = wolf != null && mod.level(p, wolf) >= 3;
        List<String> hl = new ArrayList<>(List.of("&7Call &5" + mod.catalog.npcName + " &7from anywhere", "&7to start or check quests.", ""));
        if (d.hotlineClaimed) hl.add("&aAlready claimed. &7Craft more from its recipe.");
        else if (!hotOk) hl.add("&cRequires Wolf Slayer 3.");
        else hl.add("&eClick to claim your free one!");
        m.set(10, Sky.items().exists("AATROX_BATPHONE") ? display("AATROX_BATPHONE", hl) : Icons.of(Material.BARRIER, "&cSlayer Hotline"), c -> {
            Player cp = c.player();
            SlayerData dd = data(cp);
            if (dd.hotlineClaimed || !hotOk) return;
            dd.hotlineClaimed = true;
            Sky.loot().give(cp, "AATROX_BATPHONE", 1, LootSource.REWARD);
            Msg.success(cp, "You received a Slayer Hotline!");
            openShop(cp);
        });
        // stake
        double sp = SlayerDials.STAKE_PRICE.get();
        m.set(11, display("STEAK_STAKE", List.of("&7Needed to finish a Duskborne", "&7Bloodfiend at 20% health.", "",
                "&7Cost: &6" + Text.commasInt(sp) + " coins", "", "&eClick to buy!")), c -> buy(c.player(), "STEAK_STAKE", sp, null));
        // mixins: the reference sells them at the Bartender (research 2.13); this shop only fills in while no
        // Bartender stands (shops module absent / not stationed)
        boolean bartender = SlayerDials.MIXINS_AT_BARTENDER.get() && bartenderStands();
        int[] ms = {13, 14, 15, 16, 22};
        int i = 0;
        double mp = SlayerDials.MIXIN_PRICE.get();
        for (Map.Entry<String, Object[]> e : SlayerItems.MIXINS.entrySet()) {
            if (i >= ms.length) break;
            String id = e.getKey();
            SlayerCatalog.Type t = mod.catalog.type((String) e.getValue()[3]);
            String req = "slayer:" + (t == null ? "zombie" : t.id) + ":8";
            boolean ok = Sky.requirements().check(p, req);
            Long until = d.mixins.get(id);
            List<String> lore = new ArrayList<>();
            ItemDef def = Sky.items().def(id);
            if (def != null) for (var ab : def.abilities) lore.addAll(ab.lore);
            lore.add("");
            if (until != null && until > System.currentTimeMillis()) lore.add("&7Active: &a" + Text.duration((until - System.currentTimeMillis()) / 1000) + " left");
            if (bartender) {
                lore.add("&7Sold by the &fBartender &7in the hub");
                lore.add("&7tavern" + (ok ? "." : ", from " + Sky.requirements().describe(req) + "&7."));
                m.set(ms[i++], display(id, lore));
                continue;
            }
            lore.add("&7Cost: &6" + Text.commasInt(mp) + " coins");
            lore.add("");
            lore.add(ok ? "&eClick to buy!" : "&cRequires " + Sky.requirements().describe(req) + "&c.");
            m.set(ms[i++], display(id, lore), c -> {
                if (!Sky.requirements().checkOrTell(c.player(), req)) return;
                buy(c.player(), id, mp, null);
            });
        }
        m.set(31, Icons.of(Material.OAK_SIGN, "&eAbout the shop", "&7Mixins are drunk from your hand", "&7and last 36 hours (they stack).",
                bartender ? "&7The &fBartender &7pours them." : "&7No Bartender yet: buy them here.",
                "&7Power orbs, flares and armour are", "&7crafted: see each slayer's", "&aLevel Rewards&7."));
        back(m, "Slayer", this::openMain);
        m.open(p);
    }

    /** The shops module's hub Bartender ({@code shops_bartender}, anchor npc.bartender) is registered. */
    static boolean bartenderStands() {
        try {
            return Sky.npcs().def("shops_bartender") != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void buy(Player p, String id, double price, Runnable after) {
        if (!Sky.items().exists(id)) { Msg.error(p, "That item is not available."); return; }
        Runnable doBuy = () -> {
            if (!Sky.economy().withdraw(p, price, "slayer shop: " + id)) {
                Msg.error(p, "You don't have enough coins!");
                return;
            }
            Sky.loot().give(p, id, 1, LootSource.NPC_SHOP);
            ItemDef def = Sky.items().def(id);
            Msg.success(p, "You bought " + (def == null ? id : Text.strip(def.name)) + " for &6" + Text.commasInt(price) + " coins&a!");
            if (after != null) after.run();
        };
        if (price >= 100_000) {
            Sky.menus().confirm(p, "Buy for " + Text.shortNum(price) + "?", List.of("&7Spend &6" + Text.commasInt(price) + " coins&7?"), () -> { doBuy.run(); openShop(p); }, null);
        } else {
            doBuy.run();
        }
    }

    // ---- statistics ---------------------------------------------------------------------------------

    void openStats(Player p) {
        Menu m = new Menu("Slayer Statistics", 6).chrome();
        SlayerData d = data(p);
        m.set(4, Icons.of(Material.WRITABLE_BOOK, "&eQuest Totals",
                "&7Quests started: &e" + Text.commasInt(d.questsStarted),
                "&7Bosses slain: &a" + Text.commasInt(d.questsCompleted),
                "&7Quests failed: &c" + Text.commasInt(d.questsFailed),
                "&7Coins spent on fees: &6" + Text.commasInt(d.coinsSpent),
                "&7Motes spent on fees: &d" + Text.commasInt(d.motesSpent),
                "&7Cocooned monsters slain again: &5" + Text.commasInt(d.cocoonKills)));
        int[] slots = {19, 20, 21, 23, 24, 25};
        for (int i = 0; i < mod.catalog.types.size() && i < slots.length; i++) {
            SlayerCatalog.Type t = mod.catalog.types.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("&8" + t.slayerName() + " " + mod.level(p, t));
            lore.add("");
            for (SlayerCatalog.Tier tier : t.tiers) {
                Long best = d.bestTime.get(t.id + ":" + tier.tier);
                lore.add(tierName(tier.tier) + "&7: &a" + Text.commasInt(d.kills(t.id, tier.tier)) + (best == null ? "" : " &8(best " + SlayerMath.clock(best) + ")"));
            }
            m.set(slots[i], Icons.of(mat(t.icon, Material.ROTTEN_FLESH), "&" + t.color + t.name, lore));
        }
        List<Map.Entry<String, Long>> drops = new ArrayList<>(d.dropLog.entrySet());
        drops.sort((a, b) -> {
            ItemDef da = Sky.items().def(a.getKey()), db = Sky.items().def(b.getKey());
            int ra = da == null ? 0 : da.rarity.ordinal(), rb = db == null ? 0 : db.rarity.ordinal();
            return ra != rb ? Integer.compare(rb, ra) : Long.compare(b.getValue(), a.getValue());
        });
        List<String> lore = new ArrayList<>();
        for (int k = 0; k < drops.size() && k < 18; k++) {
            ItemDef def = Sky.items().def(drops.get(k).getKey());
            lore.add((def == null ? logName(drops.get(k).getKey()) : itemName(def)) + " &8x" + Text.commasInt(drops.get(k).getValue()));
        }
        if (lore.isEmpty()) lore.add("&7Nothing yet - go slay something!");
        if (drops.size() > 18) lore.add("&8...and " + (drops.size() - 18) + " more");
        m.set(40, Icons.of(Material.CHEST, "&6Collection Log", lore));
        back(m, "Slayer", this::openMain);
        m.open(p);
    }

    // ---- help ---------------------------------------------------------------------------------------

    void openHelp(Player p) {
        Menu m = new Menu("How Slayers Work", 4).chrome();
        m.set(10, Icons.of(Material.MAP, "&a1. Start a quest", "&7Pick a slayer and a tier and pay", "&7the fee. Higher tiers need more",
                "&7Combat levels or an earlier kill."));
        m.set(11, Icons.of(Material.IRON_SWORD, "&a2. Fill the bar", "&7Kill that slayer's monsters anywhere.", "&7Their Combat XP fills the quest bar",
                "&7(sidebar and action bar)."));
        m.set(12, Icons.of(Material.WITHER_SKELETON_SKULL, "&a3. Slay the boss", "&7The boss spawns next to you. You have",
                "&c" + SlayerMath.clock(SlayerDials.BOSS_TIME.get()) + " &7to kill it. Dying, logging out", "&7or running away fails the quest."));
        m.set(13, Icons.of(Material.EXPERIENCE_BOTTLE, "&a4. Level up", "&7Each kill gives slayer XP. Levels give", "&7permanent stats and unlock recipes",
                "&7for the bosses' gear."));
        m.set(14, Icons.of(Material.ENDER_EYE, "&dRNG Meter", "&7Select a rare drop. Every boss of that", "&7slayer adds its XP to the meter;",
                "&7a full meter guarantees the drop."));
        m.set(15, Icons.of(Material.CHEST, "&6Loot", "&7Every boss drops its token, then one", "&7weighted roll. &b✯ Magic Find",
                "&7helps drops under 5%. Helpers who", "&7deal enough damage get a share."));
        m.set(16, Icons.of(Material.PLAYER_HEAD, "&eTelegraphs", "&7Big moves are announced: watch for", "&7rings on the ground, titles and",
                "&7sounds before they land."));
        m.set(22, Icons.of(Material.BELL, "&5Slayer Hotline", "&7At Wolf Slayer 3 you can claim a free", "&7Hotline in the Slayer Shop to open",
                "&7this menu from anywhere."));
        back(m, "Slayer", this::openMain);
        m.open(p);
    }
}
