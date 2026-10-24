package com.skyclone.mod.menus;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.feature.Features;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The SkyBlock Menu: the nether star in hotbar slot 9 and {@code /sbmenu}. Research 08 section 11.1
 * slot for slot ({@link SkyMenuLayout#RESEARCH}), plus a quick-access row and the Rift / Leviathan
 * destination icons ({@link SkyMenuLayout#DESTINATIONS}). Every icon opens its
 * feature through {@link Links}, so a missing module shows "Coming soon!" instead of breaking.
 */
final class SkyBlockMenu implements Features.SkyMenu {

    private final Dials.Dial<Boolean> quickRow;

    SkyBlockMenu(Dials.Dial<Boolean> quickRow) { this.quickRow = quickRow; }

    @Override
    public void open(Player p) {
        if (p == null || !p.isOnline()) return;
        if (!Sky.profiles().isReady(p)) { Msg.error(p, "Your profile is still loading - try again in a moment."); return; }
        build(p).open(p);
    }

    Menu build(Player p) {
        Menu m = new Menu("SkyBlock Menu", 6).chrome();
        place(m, p, SkyMenuLayout.RESEARCH);
        place(m, p, SkyMenuLayout.DESTINATIONS);
        if (quickRow.get() && SettingsStore.on(p, SettingDefs.QUICK_ROW)) place(m, p, SkyMenuLayout.QUICK_ROW);
        return m;
    }

    private void place(Menu m, Player p, Map<Integer, String> layout) {
        for (Map.Entry<Integer, String> e : layout.entrySet()) {
            String id = e.getValue();
            ItemStack icon;
            try {
                icon = icon(p, id);
            } catch (Throwable t) {
                Sky.log().warning("[menus] SkyBlock Menu icon '" + id + "' failed: " + t);
                FeatureIndex.Feature f = FeatureIndex.get(id);
                icon = Icons.of(Material.PAPER, "&a" + (f == null ? id : f.name()), "&eClick to open!");
            }
            m.set(e.getKey(), icon, c -> {
                MenuKit.click(c.player());
                String alt = RIGHT_CLICK.get(id);
                if (c.right() && alt != null) {
                    if (alt.equals("menus:upcoming")) CalendarMenu.upcoming(c.player(), 0).open(c.player());
                    else Links.open(c.player(), alt);
                    return;
                }
                Links.open(c.player(), id);
            });
        }
    }

    /**
     * Right-click shortcuts on a few icons: the cookie opens the Community Shop (research 08 s11.1:
     * "Community-Shop shortcuts"), the calendar goes straight to the upcoming-events list.
     */
    static final Map<String, String> RIGHT_CLICK = Map.of("cookie", "cshop", "calendar", "menus:upcoming");

    // ---- icons -------------------------------------------------------------------------------------

    ItemStack icon(Player p, String id) {
        FeatureIndex.Feature f = FeatureIndex.get(id);
        List<String> lore = new ArrayList<>();
        String verb = "open";
        switch (id) {
            case "stats" -> {
                lore.add("&7View your equipment, stats and more!");
                lore.add("");
                for (Stat s : new Stat[]{Stat.HEALTH, Stat.DEFENSE, Stat.SPEED, Stat.STRENGTH, Stat.INTELLIGENCE,
                        Stat.CRIT_CHANCE, Stat.CRIT_DAMAGE, Stat.ATTACK_SPEED, Stat.MAGIC_FIND}) {
                    lore.add(" " + StatCategories.valueLine(s, Sky.stats().get(p, s)));
                }
                lore.add("");
                lore.add("&eClick to view!");
                return Icons.playerHead(p.getUniqueId(), "&aStats & Equipment", lore);
            }
            case "skills" -> {
                lore.addAll(MenuKit.wrap("View your skill progress and the rewards waiting at every level."));
                lore.add("");
                lore.add("&6" + String.format(java.util.Locale.US, "%.1f", Summaries.skillAverage(p)) + " Skill Avg. &8(non-cosmetic)");
                verb = "view";
            }
            case "collections" -> {
                lore.addAll(MenuKit.wrap("Every item in the world has a collection. Gather more of one to unlock its recipes and rewards."));
                lore.add("");
                int[] c = Summaries.collectionProgress(p);
                lore.addAll(Summaries.collectionSummary(c));
                verb = "view";
            }
            case "recipes" -> {
                lore.addAll(MenuKit.wrap("Every recipe you have unlocked, and what you still need for the rest."));
                lore.add("");
                int[] r = Summaries.recipeProgress(p);
                lore.addAll(MenuKit.progress("Recipe Book Unlocked", r[0], r[1]));
                verb = "view";
            }
            case "sblevel" -> {
                int lvl = Summaries.sbLevel(p);
                double xp = Summaries.sbXp(p);
                lore.add("&7Your SkyBlock Level: " + MenuKit.sbLevelTag(lvl));
                lore.add("");
                lore.addAll(MenuKit.wrap("Earn SkyBlock XP from almost everything you do to raise your level and unlock emblems."));
                lore.add("");
                double into = xp - lvl * 100.0;              // research 02: 100 SkyBlock XP per level
                if (into < 0 || into > 100) into = xp % 100;
                lore.addAll(MenuKit.progress("Progress to Level " + (lvl + 1), Math.min(100, into), 100));
                verb = "view";
            }
            case "quests" -> {
                lore.addAll(MenuKit.wrap("Your current objective and every quest you have finished."));
                String obj = Summaries.objectiveText(p);
                lore.add("");
                lore.add(obj == null ? "&7Objective: &aAll done!" : "&7Objective: &e" + obj);
                String hint = obj == null ? null : Summaries.objectiveHint(p);
                if (hint != null) lore.addAll(Text.wrap("&8" + hint, 34));
                verb = "view";
            }
            case "calendar" -> {
                SkyDate d = Sky.calendar().now();
                lore.add("&7" + d.dayLabel() + ", Year " + d.year());
                lore.add("&7" + d.timeLabel() + " " + (d.isNight() ? "&b☽" : "&e☀"));
                lore.add("");
                String mayor = Summaries.mayorName();
                if (mayor != null) lore.add("&7Mayor: &a" + mayor);
                List<Summaries.Upcoming> up = Summaries.upcoming();
                if (up.isEmpty()) lore.add("&8No events scheduled.");
                else for (int i = 0; i < Math.min(3, up.size()); i++) {
                    Summaries.Upcoming u = up.get(i);
                    lore.add("&6" + Summaries.eventName(u.event()) + " " + Summaries.when(u));
                }
                verb = "view";
            }
            case "pets" -> {
                lore.addAll(MenuKit.wrap("Summon pets that follow you around and grant stats and abilities."));
                lore.add("");
                String pet = MenuKit.petLabel(p);
                lore.add("&7Active Pet: " + (pet == null ? "&cNone" : pet));
                verb = "view";
            }
            case "bank" -> {
                lore.addAll(MenuKit.wrap("Coins in the bank are safe when you die and earn interest."));
                lore.add("");
                lore.add("&7Bank: &6" + Sky.economy().format(Sky.economy().bank(p)));
                lore.add("&7Purse: &6" + Sky.economy().format(Sky.economy().purse(p)));
                // Playtest: clicking this in the Hub got "only at a Banker in the Hub" while standing in the
                // Hub. The rule is "at a Banker" (or anywhere with the buff), so say that before the click.
                lore.add("");
                if (Boolean.TRUE.equals(cookieBuffActive(p)))
                    lore.addAll(MenuKit.wrap("Your &d" + cookieBuffName() + " &7lets you bank from anywhere."));
                else
                    lore.addAll(MenuKit.wrap("Talk to any &aBanker &7to bank. The &d" + cookieBuffName()
                            + " &7or a &fPocket Bank &7lets you bank from anywhere."));
            }
            case "profiles" -> {
                lore.addAll(MenuKit.wrap("Switch between your profiles, start a new one or manage your co-op."));
                lore.add("");
                lore.add("&7Playing on: &a" + Sky.profiles().profileName(p));
                String mode = Sky.profiles().mode(p);
                if (mode != null && !mode.isBlank() && !mode.equalsIgnoreCase("normal")) lore.add("&7Mode: &e" + Text.titleCase(mode));
                int members = Sky.profiles().members(Sky.profiles().profileId(p)).size();
                if (members > 1) lore.add("&7Co-op members: &b" + members);
                verb = "manage";
            }
            case "fasttravel" -> {
                lore.addAll(MenuKit.wrap("Teleport to the islands and areas you have unlocked."));
                lore.add("");
                int[] w = FastTravelMenu.countUnlocked(p);
                lore.add("&7Destinations unlocked: &a" + w[0] + "&7/&a" + w[1]);
                verb = "pick a destination";
            }
            case "cookie" -> {
                String name = cookieName();
                lore.addAll(MenuKit.wrap("Your " + name + " buff and the bits it earns, plus the shop to spend them in."));
                lore.add("");
                Boolean on = cookieBuffActive(p);
                String buff = cookieBuffName();
                if (on != null) lore.add("&7" + buff + ": " + (on ? "&aActive" : "&cNot active"));
                long left = on != null && on ? cookieMillisLeft(p) : 0;
                if (left > 0) lore.add("&7Duration: &a" + Text.duration(Math.max(1, left / 1000)));
                lore.add("&7Bits: &b" + Text.commasInt(Sky.economy().bits(p)));
                if (on != null && !on) { lore.add(""); lore.addAll(MenuKit.wrap("Eat a " + name + " to start the buff and earn bits while you play.")); }
                verb = "view";
                ItemStack icon = Icons.of(Material.COOKIE, "&6" + name, lore);
                return finish(on != null && on ? Icons.glow(icon) : icon, id, verb);
            }
            case "effects" -> {
                Map<String, Integer> fx = activeEffects(p);
                lore.addAll(MenuKit.wrap("View every potion effect currently running on you."));
                lore.add("");
                lore.add("&7Currently Active: &e" + fx.size());
                verb = "view";
                return finish(MenuKit.potion(Color.fromRGB(0xCC33CC), "&aActive Effects", lore), id, verb);
            }
            case "wardrobe" -> {
                lore.addAll(MenuKit.wrap("Store whole armor sets and swap between them in one click."));
                return finish(MenuKit.leather(Material.LEATHER_CHESTPLATE, Color.fromRGB(0x7F3FB2), "&aWardrobe", lore), id, verb);
            }
            case "island" -> {
                lore.addAll(MenuKit.wrap("Go back to your private island."));
                verb = "warp";
            }
            case "rift" -> {
                lore.addAll(MenuKit.wrap(f.description()));
                double time = riftTime(p);
                if (time > 0) { lore.add(""); lore.add(StatCategories.valueLine(Stat.RIFT_TIME, time)); }
                verb = "open";
            }
            case "kuudra" -> {
                lore.addAll(MenuKit.wrap(f.description()));
                String best = bestLeviathanClear(p);
                if (best != null) { lore.add(""); lore.add("&7Best clear: " + best); }
                verb = "view the hunts";
            }
            case "bazaar", "auction" -> {
                if (f != null) lore.addAll(MenuKit.wrap(f.description()));
                lore.addAll(marketLore(p, id));
            }
            case "guide" -> {
                lore.addAll(MenuKit.wrap("Every feature of the game in one place, with the command that opens it."));
                lore.add("");
                lore.add("&7Features: &a" + FeatureIndex.all().size());
                verb = "browse";
            }
            default -> {
                if (f != null) lore.addAll(MenuKit.wrap(f.description()));
            }
        }
        String name = "&a" + (f == null ? Text.titleCase(id) : f.name());
        if (id.equals("fasttravel")) name = "&bFast Travel";
        ItemStack base = MenuKit.icon(f == null ? null : f.texture(), f == null ? "PAPER" : f.icon(), name, lore);
        return finish(base, id, verb);
    }

    /** Appends the command hint, coming-soon state and the click line. */
    private ItemStack finish(ItemStack base, String id, String verb) {
        List<String> tail = new ArrayList<>();
        String hint = Links.commandHint(id);
        List<String> state = Links.stateLore(id);
        if (!state.isEmpty()) tail.addAll(state);
        else {
            tail.add("");
            if (!hint.isEmpty()) tail.add(hint);
            String alt = RIGHT_CLICK.get(id);
            if (alt == null) tail.add("&eClick to " + verb + "!");
            else {
                tail.add("&eLeft-click to " + verb + "!");
                tail.add(alt.equals("menus:upcoming") ? "&eRight-click for upcoming events!"
                        : "&eRight-click for the " + (FeatureIndex.get(alt) == null ? "shop" : FeatureIndex.get(alt).name()) + "!");
            }
        }
        return Icons.withLore(base, tail);
    }

    static String cookieName() {
        try {
            var def = Sky.items().def("BOOSTER_COOKIE");
            if (def != null && def.name != null && !def.name.isBlank()) return Text.strip(def.name);
        } catch (Throwable ignored) {
            // items not ready
        }
        return "Cookie Buff";
    }

    /**
     * Is the cookie buff on? The bank module registers the requirement key {@code cookie} for exactly
     * this question. Null when nobody resolves that key (core describes an unknown key as "&amp;c" + key),
     * so the slot then simply leaves the line out instead of claiming "Not active".
     */
    static Boolean cookieBuffActive(Player p) {
        try {
            if (Sky.has(Features.Bank.class)) return Sky.service(Features.Bank.class).cookieActive(p);
            if (!cookieResolverPresent()) return null;
            return Sky.requirements().check(p, "cookie");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Milliseconds of cookie buff left (menus Request 6, answered by core's {@code Features.Bank.cookieMillisLeft},
     * which the bank provides); 0 when unknown or inactive, and the Duration line is then left out.
     */
    static long cookieMillisLeft(Player p) {
        try {
            return Sky.has(Features.Bank.class) ? Math.max(0, Sky.service(Features.Bank.class).cookieMillisLeft(p)) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    static boolean cookieResolverPresent() {
        String d = Sky.requirements().describe("cookie");
        return d != null && !Text.strip(d).equalsIgnoreCase("cookie");
    }

    /** "Boon Buff" from the bank's own description ("&amp;dthe Boon Buff"), else "Cookie Buff". */
    static String cookieBuffName() {
        try {
            String d = Text.strip(Sky.requirements().describe("cookie")).trim();
            if (d.toLowerCase(java.util.Locale.ROOT).startsWith("the ")) d = d.substring(4);
            if (!d.isBlank() && !d.equalsIgnoreCase("cookie")) return d;
        } catch (Throwable ignored) {
            // requirements not up
        }
        return "Cookie Buff";
    }

    /**
     * Bazaar and Auction House icons (playtest wave4-endgame #12: the two sat side by side and seemed to follow
     * different rules away from the Hub). Both owners gate a remote open the same way for players - a requirement
     * dial ({@code bazaar.remote-requirement} / {@code auction.remote-requirement}, default the bank's
     * {@code cookie} key, the Boon Buff) - so the icons now state that rule, in one sentence
     * ({@link SkyMenuLayout#reachText}), read from the owners' own dials; plus the Bazaar's access requirement
     * ({@code bazaar.access-requirement}, SkyBlock Level 5 by the owner's choice) while it is not met. Lore only:
     * the click still goes through the owner's gate. Empty when the owner module is not installed.
     */
    static List<String> marketLore(Player p, String id) {
        List<String> out = new ArrayList<>();
        try {
            boolean bz = id.equals("bazaar");
            if (bz ? !Sky.has(Features.Bazaar.class) : !Sky.has(Features.Auctions.class)) return out;
            if (bz) {
                String gate = SkyMenuLayout.accessKey(Sky.dials().text("bazaar.access-requirement", "sblevel:5", BZ_ACCESS).get());
                boolean failsOpen = gate.startsWith("sblevel:") && !Sky.has(Features.SkyLevel.class);
                if (!gate.isEmpty() && !failsOpen && !Sky.requirements().check(p, gate)) {
                    out.add("");
                    out.addAll(Text.wrap("&cRequires " + Sky.requirements().describe(gate) + "&c.", 34));
                }
            }
            boolean anywhere = !bz && Sky.dials().flag("auction.anywhere", false, AH_ANYWHERE).get();
            String remote = bz ? Sky.dials().text("bazaar.remote-requirement", "cookie", BZ_REMOTE).get()
                    : Sky.dials().text("auction.remote-requirement", "cookie", AH_REMOTE).get();
            String buff = null;
            Boolean active = null;
            String r = remote == null ? "" : remote.trim();
            // the bazaar lets everyone in remotely while the cookie item does not exist (Bz.check)
            boolean cookieless = bz && r.equals("cookie") && !Sky.items().exists("BOOSTER_COOKIE");
            if (bz && (r.isEmpty() || cookieless)) anywhere = true;          // bazaar: blank = anywhere (auction: blank = nobody)
            else if (!r.isEmpty()) {
                buff = r.equals("cookie") ? cookieBuffName() : Text.strip(Sky.requirements().describe(r)).replaceFirst("(?i)^the ", "");
                active = Sky.requirements().check(p, r);
            }
            out.add("");
            out.addAll(MenuKit.wrap(SkyMenuLayout.reachText(bz ? "the &6Bazaar &7in the Hub" : "the &6Auction House &7in the Hub",
                    buff, active, anywhere)));
        } catch (Throwable t) {
            return List.of();
        }
        return out;
    }

    // Same text as the owners' registrations: registering an existing key returns that dial (CORE_GUIDE 3.4).
    private static final String BZ_ACCESS = "Requirement to use the Bazaar at all (reference game: Level 7; owner chose Level 5 on 2026-10-04). A bare number N = SkyBlock Level N (e.g. 3 for a show); none / off / 0 = everyone; anything else is a requirement key such as sblevel:7";
    private static final String BZ_REMOTE = "Extra requirement to open /bz away from the Bazaar NPC (research: an active Booster Cookie = bank's 'cookie' key). Empty = anywhere";
    private static final String AH_REMOTE = "Requirement that lets /ah work away from the Auction House (research 05 3.1; the bank module's Boon Buff = 'cookie'); blank = nobody";
    private static final String AH_ANYWHERE = "Allow /ah anywhere for everyone. False (genre) = at the hub Auction House, or anywhere while auction.remote-requirement is met";

    /** Rift Time from the stat sheet (the rift module provides it); 0 when nobody does. */
    static double riftTime(Player p) {
        try {
            return Sky.stats().get(p, Stat.RIFT_TIME);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The tiers of the Leviathan hunt, lowest first (kuudra's tiers.json ids and names). */
    static final String[][] LEVIATHAN_TIERS = {
            {"basic", "&aBasic"}, {"hot", "&eHot"}, {"burning", "&6Burning"}, {"fiery", "&cFiery"}, {"infernal", "&4Infernal"}};

    /**
     * Highest hunt tier this player has cleared, through kuudra's requirement key {@code kuudra:<tier>}.
     * Null when the kuudra module is not installed (unknown prefix: core describes it as the bare key),
     * so the line is left out instead of claiming "none"; "&amp;7None yet" when it is and nothing is cleared.
     */
    static String bestLeviathanClear(Player p) {
        try {
            String d = Sky.requirements().describe("kuudra:basic");
            if (d == null || Text.strip(d).equalsIgnoreCase("kuudra:basic")) return null;
            String best = null;
            for (String[] t : LEVIATHAN_TIERS) if (Sky.requirements().check(p, "kuudra:" + t[0])) best = t[1];
            return best == null ? "&7None yet" : best;
        } catch (Throwable t) {
            return null;
        }
    }

    static Map<String, Integer> activeEffects(Player p) {
        try {
            Map<String, Integer> m = Sky.service(Features.Potions.class).active(p);
            return m == null ? Map.of() : m;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    static UUID id(Player p) { return p.getUniqueId(); }
}
