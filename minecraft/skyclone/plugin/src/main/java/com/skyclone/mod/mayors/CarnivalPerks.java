package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carnival Perks (Doug's second shop; neu-repo constants/carnivalshops.json for ids, tiers and costs, the reference
 * wiki's Doug page for what each tier is worth). Bought with Carnival Tokens, kept for good, each working during its
 * own festival. Applied here:
 * <ul>
 *   <li>Spooky Festival: Candy Scavenger (+candy), Purple Vogue (+purple share), Treat Seeker (+chest chance) -
 *       read by {@link SpookyFestival}.</li>
 *   <li>Season of Gerald: Present Connoisseur / Red Fad (siege gifts, {@link WinterWorkshop}), Professional Gifter
 *       (gift coins and XP), Icy Hook (+Sea Creature Chance on the Winter Workshop while it is open).</li>
 *   <li>Mythological Ritual: Storied Stinger (+damage to mythological creatures), Deadly Greed (+burrow coins,
 *       {@link Mythology}), Huntress's Favor (+Magic Find against mythological creatures).</li>
 *   <li>Fishing Festival / Mining Fiesta / Harvest Feast: the stat perks (Fishing Wisdom, Mining Fortune, Farming
 *       Fortune) while that festival runs.</li>
 * </ul>
 * The rest (shark, powder, titanium, seasoning, pest, flask and Tracking perks) belong to systems other modules
 * own: they read the tier through the requirement key {@code mayors:carnival_perk:<id>:<tier>} (true when the
 * player owns at least that tier) - see the hand-off.
 */
final class CarnivalPerks {

    private static final String[][] EVENTS = {
            {"spooky_festival", "&6Spooky Festival", "JACK_O_LANTERN"},
            {"season_of_jerry", "&cSeason of Gerald", "SNOWBALL"},
            {"fishing_festival", "&3Fishing Festival", "FISHING_ROD"},
            {"mining_fiesta", "&6Mining Fiesta", "IRON_PICKAXE"},
            {"mythological_ritual", "&2Mythological Ritual", "GOLDEN_SHOVEL"},
            {"harvest_feast", "&eHarvest Feast", "WHEAT"},
    };

    private final MayorsModule mod;
    private final Carnival carnival;

    CarnivalPerks(MayorsModule mod, Carnival carnival) { this.mod = mod; this.carnival = carnival; }

    void register() {
        Sky.stats().addProvider("mayors_carnival_perks", this::contribute);
        Sky.combat().addModifier("mayors_storied_stinger", 450, ctx -> {
            Player p = ctx.playerAttacker();
            if (p == null || !(ctx.victim instanceof LivingEntity le)) return;
            MobDef d = Sky.mobs().defOf(le);
            if (d == null || !"mythological".equals(d.familyId())) return;
            double v = value(p, "storied_stinger");
            if (v > 0) ctx.additive += v;
        });
        Sky.loot().addMagicFindModifier("mayors_carnival", (p, def) ->
                def != null && "mythological".equals(def.familyId()) ? value(p, "dianas_favor") : 0.0);
    }

    /** The player's tier of a perk (0 when none / unknown). */
    int tier(Player p, String perkId) {
        EventData.Perk perk = carnival.data().perk(perkId);
        PlayerData pd = mod.data(p);
        return perk == null || pd == null ? 0 : CarnivalRules.tier(perk, pd.carnivalPerks);
    }

    /** What the player's perk is worth (Candy Scavenger 3 -> 6, meaning +6%). */
    double value(Player p, String perkId) {
        EventData.Perk perk = carnival.data().perk(perkId);
        return perk == null ? 0 : CarnivalRules.value(perk, tier(p, perkId));
    }

    private boolean on(String event) {
        try { if (Sky.calendar().active(event)) return true; } catch (Throwable ignored) { }
        for (String id : mod.dials().carnivalFeastEvents.get().split(","))
            if ("harvest_feast".equals(event) && !id.isBlank()) {
                try { if (Sky.calendar().active(id.trim()) || mod.election().perkActive(id.trim())) return true; } catch (Throwable ignored) { }
            }
        return false;
    }

    private void contribute(Player p, StatMap into) {
        if (!Sky.profiles().isReady(p)) return;
        double v;
        if ((v = value(p, "icy_hook")) > 0 && WinterWorkshop.ISLAND.equals(Sky.worlds().islandOf(p.getWorld())) && mod.winter().open())
            into.add(Stat.SEA_CREATURE_CHANCE, v);
        if ((v = value(p, "fishing_prodigy")) > 0 && on("fishing_festival")) into.add(Stat.FISHING_WISDOM, v);
        if ((v = value(p, "fortunate_festivity")) > 0 && on("mining_fiesta")) into.add(Stat.MINING_FORTUNE, v);
        if ((v = value(p, "fortunate_feasting")) > 0 && on("harvest_feast")) into.add(Stat.FARMING_FORTUNE, v);
    }

    // =============================================================================================== menus

    void open(Player p) {
        Menu m = new Menu("Carnival Perks", 4).chrome();
        PlayerData pd = mod.data(p);
        long have = pd == null ? 0 : pd.carnivalTokens;
        m.set(4, Icons.of(Material.NETHER_STAR, "&6Carnival Perks", "&7Spend &dCarnival Tokens &7on lasting boosts", "&7to the festivals. Each tier is bought once",
                "&7and works whenever its festival runs.", "", "&7Your tokens: &d" + Text.commasInt(have)));
        int[] slots = {11, 12, 13, 14, 15, 22};
        for (int i = 0; i < EVENTS.length; i++) {
            String[] ev = EVENTS[i];
            int owned = 0, total = 0;
            for (EventData.Perk perk : carnival.data().perks) {
                if (!perk.event.equals(ev[0])) continue;
                total += perk.costs.size();
                owned += pd == null ? 0 : CarnivalRules.tier(perk, pd.carnivalPerks);
            }
            Material icon = Material.matchMaterial(ev[2]);
            m.set(slots[i], Icons.of(icon == null ? Material.PAPER : icon, ev[1], "&7Tiers owned: &a" + owned + "&7/&a" + total, "", "&eClick to view!"),
                    c -> openEvent(c.player(), ev));
        }
        m.set(31, Icons.back("Carnival"), c -> carnival.openLeader(c.player()));
        m.open(p);
    }

    void openEvent(Player p, String[] ev) {
        Menu m = new Menu(Text.strip(ev[1]) + " Perks", 4).chrome();
        PlayerData pd = mod.data(p);
        long have = pd == null ? 0 : pd.carnivalTokens;
        int slot = 10;
        for (EventData.Perk perk : carnival.data().perks) {
            if (!perk.event.equals(ev[0])) continue;
            int t = pd == null ? 0 : CarnivalRules.tier(perk, pd.carnivalPerks);
            long cost = CarnivalRules.nextCost(perk, t);
            List<String> lore = new ArrayList<>();
            lore.add("&7Tier: &a" + t + "&7/&a" + perk.costs.size());
            lore.add("&7Now: &a+" + fmt(CarnivalRules.value(perk, t)) + perk.unit + " &7" + perk.effect);
            if (cost >= 0) {
                lore.add("&7Next: &a+" + fmt(CarnivalRules.value(perk, t + 1)) + perk.unit + " &7" + perk.effect);
                lore.add("");
                lore.add("&7Cost: &d" + Text.commasInt(cost) + " Carnival Tokens");
                lore.add(have >= cost ? "&eClick to buy!" : "&cNot enough tokens!");
            } else {
                lore.add("");
                lore.add("&a&lMAXED");
            }
            m.set(slot, Icons.of(t >= perk.costs.size() ? Material.ENCHANTED_BOOK : Material.BOOK, (t > 0 ? "&a" : "&e") + perk.name,
                    lore.toArray(new String[0])), c -> { buy(c.player(), perk); openEvent(c.player(), ev); });
            slot += 2;
        }
        m.set(31, Icons.back("Carnival Perks"), c -> open(c.player()));
        m.open(p);
    }

    static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }

    void buy(Player p, EventData.Perk perk) {
        PlayerData pd = mod.data(p);
        if (pd == null) return;
        int t = CarnivalRules.tier(perk, pd.carnivalPerks);
        long cost = CarnivalRules.nextCost(perk, t);
        if (cost < 0) { Msg.error(p, perk.name + " is already maxed!"); return; }
        if (pd.carnivalTokens < cost) { Msg.error(p, "You need " + Text.commasInt(cost) + " Carnival Tokens!"); return; }
        pd.carnivalTokens -= cost;
        pd.carnivalPerks.put(perk.id, t + 1);
        Msg.send(p, "&aYou unlocked &6" + perk.name + " " + Text.roman(t + 1) + "&a! &7(+" + fmt(CarnivalRules.value(perk, t + 1)) + perk.unit + " " + perk.effect + ")");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.6f);
        Sky.stats().invalidate(p);
    }

    /** Every perk the player owns, for the admin readout. */
    Map<String, Integer> owned(Player p) {
        PlayerData pd = mod.data(p);
        return pd == null ? Map.of() : new LinkedHashMap<>(pd.carnivalPerks);
    }
}
