package com.skyclone.mod.menus;

import com.skyclone.api.Sky;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.world.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code /sb selftest menus}: proves the module's data hangs together on the live server. */
final class MenusSelfTests {

    private MenusSelfTests() {}

    static void register() {
        Sky.commands().selfTest("menus.sbmenu-layout", MenusSelfTests::layout);
        Sky.commands().selfTest("menus.stat-categories", MenusSelfTests::statCategories);
        Sky.commands().selfTest("menus.calendar-math", MenusSelfTests::calendarMath);
        Sky.commands().selfTest("menus.fast-travel", MenusSelfTests::fastTravel);
        Sky.commands().selfTest("menus.settings", MenusSelfTests::settings);
        Sky.commands().selfTest("menus.collections-data", MenusSelfTests::collections);
        Sky.commands().selfTest("menus.textures", MenusSelfTests::textures);
        Sky.commands().selfTest("menus.icons-valid", MenusSelfTests::icons);
        Sky.commands().selfTest("menus.core-links", MenusSelfTests::coreLinks);
        Sky.commands().selfTest("menus.screens-build", MenusSelfTests::screens);
        Sky.commands().selfTest("menus.sbmenu-links", MenusSelfTests::sbmenuLinks);
        Sky.commands().selfTest("menus.event-links", MenusSelfTests::eventLinks);
        Sky.commands().selfTest("menus.upcoming-sane", MenusSelfTests::upcomingSane);
        Sky.commands().selfTest("menus.breakdown-keys", MenusSelfTests::breakdownKeys);
        // wave 5
        Sky.commands().selfTest("menus.settings-service", MenusSelfTests::settingsService);
        Sky.commands().selfTest("menus.sidebar-toggles", MenusSelfTests::sidebarToggles);
        Sky.commands().selfTest("menus.pv-snapshot", MenusSelfTests::pvSnapshot);
        Sky.commands().selfTest("menus.request-gates", MenusSelfTests::requestGates);
    }

    /**
     * Every one of the 17 research slots of the SkyBlock Menu opens something real on THIS server: its
     * owner's service is installed or one of its commands is registered. Wave-1 modules own all of them,
     * so a MISSING here is a broken link the player would hit, not a feature still to come.
     */
    static String sbmenuLinks() {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<Integer, String> e : SkyMenuLayout.RESEARCH.entrySet()) {
            if (Links.state(e.getValue()) == Links.State.MISSING) missing.add(e.getKey() + "=" + e.getValue());
        }
        return missing.isEmpty() ? null : "SkyBlock Menu slots that open nothing: " + missing
                + " (see /sb menus links for the commands tried)";
    }

    /** Every registered calendar event opens a feature that exists when clicked in the Calendar. */
    static String eventLinks() {
        List<String> bad = new ArrayList<>();
        for (CalendarEvent e : Sky.calendar().events()) {
            String f = FeatureIndex.forEvent(e.id);
            if (FeatureIndex.get(f) == null) { bad.add(e.id + "->" + f + "(no feature)"); continue; }
            if (Links.state(f) == Links.State.MISSING) bad.add(e.id + "->" + f);
        }
        return bad.isEmpty() ? null : "calendar events whose click opens nothing: " + bad;
    }

    /** The Upcoming list never shows a negative countdown, a duplicate or something past the horizon. */
    static String upcomingSane() {
        Summaries.invalidateUpcoming();
        List<Summaries.Upcoming> up = Summaries.upcoming();
        long horizon = CalendarMath.horizonMillis(Sky.calendar().millisPerDay());
        Set<String> seen = new HashSet<>();
        for (Summaries.Upcoming u : up) {
            if (u.millis() < 0) return u.event().id + " has a negative countdown";
            if (!u.active() && u.millis() > horizon) return u.event().id + " is past the horizon";
            if (!seen.add(CalendarMath.dedupeKey(Summaries.eventName(u.event()), u.active(), u.millis()))) return u.event().id + " is listed twice";
        }
        return null;
    }

    /** Core's breakdown rows "x", "x (%)", "x (x)" are told apart (a multiplier is never added as flat points). */
    static String breakdownKeys() {
        if (StatCategories.parseKey("pets").kind() != StatCategories.PartKind.FLAT) return "flat key misread";
        if (StatCategories.parseKey("pets (%)").kind() != StatCategories.PartKind.PERCENT) return "percent key misread";
        StatCategories.SourceKey m = StatCategories.parseKey("armor_sets_final (x)");
        if (m.kind() != StatCategories.PartKind.MULTIPLIER || !m.source().equals("armor_sets_final")) return "multiplier key misread";
        if (!StatCategories.sourceName("pets (%)").equals("Pet")) return "percent row name is " + StatCategories.sourceName("pets (%)");
        return null;
    }

    /** The 17 research slots are exactly research 08 section 11.1, and every slot names a real feature. */
    static String layout() {
        int[] expect = {13, 19, 20, 21, 22, 23, 24, 25, 29, 30, 31, 32, 33, 47, 48, 50, 51};
        Set<Integer> got = SkyMenuLayout.RESEARCH.keySet();
        if (got.size() != expect.length) return "research layout has " + got.size() + " slots, expected " + expect.length;
        for (int s : expect) if (!got.contains(s)) return "research slot " + s + " is empty";
        Set<Integer> all = new HashSet<>(got);
        for (int s : SkyMenuLayout.QUICK_ROW.keySet()) if (!all.add(s)) return "quick row slot " + s + " collides";
        for (int s : SkyMenuLayout.DESTINATIONS.keySet()) if (!all.add(s)) return "destination slot " + s + " collides";
        if (all.contains(SkyMenuLayout.CLOSE)) return "a feature sits on the Close button";
        for (String id : SkyMenuLayout.RESEARCH.values()) if (FeatureIndex.get(id) == null) return "unknown feature " + id;
        for (String id : SkyMenuLayout.QUICK_ROW.values()) if (FeatureIndex.get(id) == null) return "unknown feature " + id;
        for (String id : SkyMenuLayout.DESTINATIONS.values()) if (FeatureIndex.get(id) == null) return "unknown feature " + id;
        if (!SkyMenuLayout.DESTINATIONS.containsValue("rift") || !SkyMenuLayout.DESTINATIONS.containsValue("kuudra"))
            return "the SkyBlock Menu lost its Rift / Leviathan icon";
        if (!"stats".equals(SkyMenuLayout.RESEARCH.get(13))) return "slot 13 must be Stats & Equipment";
        return null;
    }

    /** Every stat is on exactly one page with a real icon; formatting matches the genre. */
    static String statCategories() {
        int total = 0;
        for (StatCategories.Category c : StatCategories.Category.values()) total += StatCategories.of(c).size();
        if (total != Stat.values().length) return "pages list " + total + " stats, the game has " + Stat.values().length;
        for (Stat s : Stat.values()) {
            if (!StatCategories.covered(s)) return s + " has no page";
            if (MenuKit.mat(StatCategories.info(s).icon(), null) == null) return s + " icon " + StatCategories.info(s).icon() + " is not an item";
        }
        if (!StatCategories.format(Stat.CRIT_CHANCE, 65).equals("65%")) return "crit chance formats as " + StatCategories.format(Stat.CRIT_CHANCE, 65);
        if (!StatCategories.format(Stat.HEALTH, 1234).equals("1,234")) return "health formats as " + StatCategories.format(Stat.HEALTH, 1234);
        return null;
    }

    /** Research 08 s8: 31-day months, 20-minute days -> 50 s SkyBlock hours, 124-hour years; festival spans. */
    static String calendarMath() {
        if (Math.abs(CalendarMath.realSecondsPerHour(1_200_000) - 50) > 1e-9) return "SB hour is not 50 s at 20-minute days";
        if (Math.abs(CalendarMath.realHoursPerYear(1_200_000) - 124) > 1e-9) return "SB year is not 124 h at 20-minute days";
        CalendarEvent spooky = CalendarEvent.onDate("t_spooky", "Spooky", 8, 29, 3);
        if (!CalendarMath.occursOn(spooky, 1, 8, 29) || !CalendarMath.occursOn(spooky, 1, 8, 31)) return "3-day event misses its days";
        if (CalendarMath.occursOn(spooky, 1, 8, 28) || CalendarMath.occursOn(spooky, 1, 9, 1)) return "3-day event leaks outside its days";
        CalendarEvent newYear = CalendarEvent.onDate("t_ny", "NY", 12, 30, 4);
        if (!CalendarMath.occursOn(newYear, 2, 1, 2) || CalendarMath.occursOn(newYear, 2, 1, 3)) return "event wrapping into the new year is wrong";
        CalendarEvent monthly = CalendarEvent.onDate("t_m", "Monthly", 0, 31, 2);
        if (!CalendarMath.occursOn(monthly, 1, 5, 1) || CalendarMath.occursOn(monthly, 1, 5, 2)) return "monthly event spill-over is wrong";
        long ms = Sky.calendar().millisPerDay();
        if (ms <= 0) return "calendar reports a non-positive day length";
        return null;
    }

    /** Every fixed island is a contract island in a unique slot; every registered warp's island gets a slot. */
    static String fastTravel() {
        Set<Integer> slots = new HashSet<>();
        for (TravelLayout.Entry e : TravelLayout.entries()) {
            if (!TravelLayout.CONTRACT_ISLANDS.contains(e.island())) return "unknown island id " + e.island();
            if (!slots.add(e.slot())) return "two islands in slot " + e.slot();
            if (e.slot() < 9 || e.slot() > 44 || e.slot() % 9 == 0 || e.slot() % 9 == 8) return e.island() + " sits on the border (" + e.slot() + ")";
            if (e.texture() != null && !MenuKit.textures().containsKey(e.texture())) return "missing texture " + e.texture();
            if (MenuKit.mat(e.material(), null) == null) return e.island() + " icon " + e.material() + " is not an item";
        }
        Map<String, List<WorldService.WarpInfo>> warps = FastTravelMenu.byIsland();
        Map<String, Integer> placed = TravelLayout.assign(new ArrayList<>(warps.keySet()));
        List<String> unplaced = new ArrayList<>();
        for (String island : warps.keySet()) if (!placed.containsKey(island)) unplaced.add(island);
        if (!unplaced.isEmpty()) return "islands with warps but no Fast Travel slot: " + unplaced;
        return null;
    }

    static String settings() {
        Set<String> keys = new HashSet<>();
        for (SettingDefs.Setting s : SettingDefs.all()) {
            if (!keys.add(s.key())) return "duplicate key " + s.key();
            if (!s.valid(s.def())) return s.key() + " default '" + s.def() + "' is not one of its options";
            if (MenuKit.mat(s.icon(), null) == null) return s.key() + " icon " + s.icon() + " is not an item";
            if (!s.key().matches("[a-z0-9._]+")) return "bad key " + s.key();
        }
        for (SettingDefs.Category c : SettingDefs.Category.values()) {
            if (SettingDefs.of(c).isEmpty()) return "empty settings category " + c;
            if (SettingDefs.of(c).size() > SettingsMenu.PER_PAGE * 3) return "category " + c + " has too many settings for its pages";
        }
        for (String k : List.of(SettingDefs.CLICK_SOUNDS, SettingDefs.QUICK_ROW, SettingDefs.PV_ALLOW, SettingDefs.PV_SNEAK, SettingDefs.PV_COINS,
                SettingDefs.CHAT_PLAYERS, SettingDefs.NOTIFY_REMIND, SettingDefs.NOTIFY_REMIND_MIN, SettingDefs.NOTIFY_START,
                SettingDefs.TAB_STATS, SettingDefs.TAB_PET, SettingDefs.TAB_EFFECTS, SettingDefs.TAB_EVENT)) {
            if (SettingDefs.get(k) == null) return "implemented key " + k + " is not defined";
        }
        // the Calendar Alerts scope (playtest wave4-endgame #11): mayors' values, adopted from its PDC writes
        SettingDefs.Setting sc = SettingDefs.get(SettingDefs.NOTIFY_SCOPE);
        if (sc == null || sc.category() != SettingDefs.Category.NOTIFICATIONS) return SettingDefs.NOTIFY_SCOPE + " is not in Notifications";
        if (!sc.options().equals(List.of("important", "all", "off")) || "menus".equals(sc.reader()))
            return SettingDefs.NOTIFY_SCOPE + " must carry mayors' values and be adopted from the PDC";
        return null;
    }

    /**
     * research/data/collections.json: 90 collections with 790 tiers; when the collections module is
     * installed, it must agree that every one of our ids is a collection (our percentage reads its tiers).
     */
    static String collections() {
        List<Summaries.Col> cols = Summaries.collections();
        if (cols.size() != 90) return "expected 90 collections, data has " + cols.size();
        if (Summaries.totalCollectionTiers() != 790) return "expected 790 tiers, data has " + Summaries.totalCollectionTiers();
        if (Summaries.bossCollections().isEmpty()) return "boss_collections.json missing or empty (run tools/gen/menus/gen_menus.py)";
        if (!Sky.has(com.skyclone.api.feature.Features.Collections.class)) return null;
        var svc = Sky.service(com.skyclone.api.feature.Features.Collections.class);
        List<String> unknown = new ArrayList<>();
        for (Summaries.Col c : cols) if (!svc.isCollectionItem(c.id)) unknown.add(c.id);
        if (!unknown.isEmpty()) return unknown.size() + " of our collection ids are unknown to the collections module, e.g. "
                + unknown.subList(0, Math.min(5, unknown.size()));
        return null;
    }

    static String textures() {
        Map<String, String> t = MenuKit.textures();
        if (t.size() < 30) return "only " + t.size() + " textures loaded";
        for (Map.Entry<String, String> e : t.entrySet()) {
            try {
                String json = new String(Base64.getDecoder().decode(e.getValue()), StandardCharsets.UTF_8);
                if (!json.contains("textures")) return e.getKey() + " is not a skin texture";
            } catch (IllegalArgumentException ex) {
                return e.getKey() + " is not base64";
            }
        }
        for (FeatureIndex.Feature f : FeatureIndex.all()) {
            if (f.texture() != null && !t.containsKey(f.texture())) return "feature " + f.id() + " uses missing texture " + f.texture();
        }
        return null;
    }

    static String icons() {
        for (FeatureIndex.Feature f : FeatureIndex.all()) if (MenuKit.mat(f.icon(), null) == null) return f.id() + " icon " + f.icon() + " is not an item";
        for (FeatureIndex.Category c : FeatureIndex.Category.values()) {
            if (MenuKit.mat(c.icon, null) == null) return "guide category " + c + " icon is not an item";
            if (FeatureIndex.of(c).isEmpty()) return "guide category " + c + " is empty";
        }
        for (StatCategories.Category c : StatCategories.Category.values()) if (MenuKit.mat(c.icon, null) == null) return c + " icon";
        for (SettingDefs.Category c : SettingDefs.Category.values()) if (MenuKit.mat(c.icon, null) == null) return c + " icon";
        for (String id : MiscMenus.BAG_IDS) if (FeatureIndex.get(id) == null) return "bag " + id + " is not a feature";
        return null;
    }

    /** The commands core owns (and our own) are reachable, which also proves the availability probe works. */
    static String coreLinks() {
        List<String> missing = new ArrayList<>();
        for (String c : List.of("sbmenu", "pv", "fasttravel", "calendar", "settings", "bags", "guide", "profiles", "hub", "warp", "coop", "commands")) {
            Boolean k = Links.commandKnown(c);
            if (k == null) {
                Sky.log().warning("[menus] core-links: the Bukkit command map does not list registered commands, so menus cannot "
                        + "mark missing features 'Coming soon' in advance (clicks still fall back correctly)");
                return Sky.has(com.skyclone.api.feature.Features.SkyMenu.class) ? null : "Features.SkyMenu is not provided";
            }
            if (!k) missing.add("/" + c);
        }
        if (!missing.isEmpty()) return "not registered: " + missing;
        if (!Sky.has(com.skyclone.api.feature.Features.SkyMenu.class)) return "Features.SkyMenu is not provided";
        return null;
    }

    /** Every screen builds for every online player without throwing, and every research slot is filled. */
    static String screens() {
        List<? extends Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        int built = 0;
        for (Player p : players) {
            if (!Sky.profiles().isReady(p)) continue;
            for (String name : Screens.NAMES) {
                try {
                    Menu m = Screens.build(p, name, null);
                    m.redraw();
                    if (name.equals("sbmenu")) {
                        java.util.Set<Integer> filled = new HashSet<>(SkyMenuLayout.RESEARCH.keySet());
                        filled.addAll(SkyMenuLayout.DESTINATIONS.keySet());
                        for (int slot : filled) {
                            ItemStack it = m.get(slot);
                            if (it == null || it.getType() == Material.BLACK_STAINED_GLASS_PANE) return "SkyBlock Menu slot " + slot + " is empty for " + p.getName();
                        }
                    }
                    built++;
                } catch (Throwable t) {
                    return "screen " + name + " threw for " + p.getName() + ": " + t;
                }
            }
            for (Stat s : Stat.values()) {
                try { ProfileViewer.breakdown(p, p, s).redraw(); } catch (Throwable t) { return "breakdown " + s + " threw: " + t; }
            }
            for (String island : TravelLayout.CONTRACT_ISLANDS) {
                try { FastTravelMenu.islandIcon(p, island, FastTravelMenu.byIsland().getOrDefault(island, List.of()), false); }
                catch (Throwable t) { return "fast travel icon " + island + " threw: " + t; }
            }
        }
        if (built == 0) Sky.log().info("[menus] screens-build: no ready player online - only the data checks ran");
        return null;
    }

    // ---- wave 5 -----------------------------------------------------------------------------------------

    /**
     * Features.Settings is provided by this module and answers like the store: the default for a null player and
     * for an unknown key, and the stored value for every defined key of every ready online player.
     */
    static String settingsService() {
        if (!Sky.has(com.skyclone.api.feature.Features.Settings.class)) return "Features.Settings is not provided";
        var svc = Sky.service(com.skyclone.api.feature.Features.Settings.class);
        if (!svc.flag(null, SettingDefs.CLICK_SOUNDS, true) || svc.flag(null, SettingDefs.CLICK_SOUNDS, false))
            return "a null player must get the caller's default";
        if (!"x".equals(svc.text(null, "no.such.key", "x"))) return "an unknown key must get the caller's default";
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            if (!"zz".equals(svc.text(p, "menus.selftest.unknown_key", "zz"))) return "unknown key for " + p.getName() + " did not fall back";
            for (SettingDefs.Setting st : SettingDefs.all()) {
                String store = SettingsStore.get(p, st.key());
                String via = svc.text(p, st.key(), "<def>");
                if (!store.equals(via)) return st.key() + " for " + p.getName() + ": service says " + via + ", store says " + store;
                if (st.toggle() && svc.flag(p, st.key(), !"true".equals(store)) != "true".equals(store))
                    return st.key() + " flag() disagrees with the store for " + p.getName();
                // the off-thread cache is what async readers get
                if (!store.equals(SettingsStore.cached(p.getUniqueId(), st.key()))) return st.key() + " cache is stale for " + p.getName();
            }
        }
        return null;
    }

    /** Every sidebar toggle is a defined Display toggle read here; groups are disjoint and never hide a run's state. */
    static String sidebarToggles() {
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, List<String>> e : SidebarToggles.GROUPS.entrySet()) {
            SettingDefs.Setting st = SettingDefs.get(e.getKey());
            if (st == null || !st.toggle() || st.category() != SettingDefs.Category.DISPLAY || !"menus".equals(st.reader()))
                return e.getKey() + " must be a Display toggle implemented by menus";
            if (!"true".equals(st.def())) return e.getKey() + " must default to shown";
            for (String id : e.getValue()) {
                if (!seen.add(id)) return "section " + id + " is in two sidebar groups";
                if (SidebarToggles.NEVER.contains(id)) return "section " + id + " must never be hidden by a setting";
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            for (Map.Entry<String, List<String>> e : SidebarToggles.GROUPS.entrySet()) {
                boolean hidden = !SettingsStore.on(p, e.getKey());
                for (String id : e.getValue()) if (Sky.sidebar().hidden(p, id) != hidden)
                    return p.getName() + ": section " + id + " hidden=" + Sky.sidebar().hidden(p, id) + " but " + e.getKey() + " says " + !hidden;
            }
        }
        return null;
    }

    /**
     * The offline profile viewer: the refusal rules, and for every ready online player a fresh capture that
     * round-trips (every stored icon decodes, the head is there, every stat is recorded, nothing carries an item id).
     */
    static String pvSnapshot() {
        PvSnapshot.Data empty = new PvSnapshot.Data();
        if (PvSnapshot.refusal(true, empty, "X") == null) return "an empty snapshot must be refused";
        PvSnapshot.Data closed = new PvSnapshot.Data();
        closed.at = 1; closed.slots.put("4", "x"); closed.allowView = false;
        if (PvSnapshot.refusal(false, closed, "X") == null) return "a private profile must be refused to players";
        if (PvSnapshot.refusal(true, closed, "X") != null) return "admins bypass profile privacy";
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            PvSnapshot.Data d;
            try { d = PvSnapshot.capture(p); } catch (Throwable t) { return "capture threw for " + p.getName() + ": " + t; }
            if (!d.slots.containsKey(String.valueOf(ProfileViewer.HEAD))) return "snapshot of " + p.getName() + " has no head icon";
            if (d.stats.size() != Stat.values().length) return "snapshot of " + p.getName() + " has " + d.stats.size() + " stats";
            for (Map.Entry<String, String> e : d.slots.entrySet()) {
                ItemStack it = PvSnapshot.decode(e.getValue());
                if (it == null) return "slot " + e.getKey() + " of " + p.getName() + "'s snapshot does not decode";
                if (Sky.items().idOf(it) != null) return "slot " + e.getKey() + " of " + p.getName() + "'s snapshot carries a real item id";
            }
            try { PvSnapshot.build(p, d).redraw(); PvSnapshot.statPage(p, d, StatCategories.Category.COMBAT).redraw(); }
            catch (Throwable t) { return "offline viewer threw for " + p.getName() + ": " + t; }
        }
        return null;
    }

    /** /coop invite and /trade parsing for the Co-op Invites / Trade Requests settings. */
    static String requestGates() {
        if (!"Bob_1".equals(MenusListener.tradeTarget("/trade Bob_1"))) return "/trade Bob_1 not parsed";
        if (!"Bob".equals(MenusListener.tradeTarget("  /skyclone:TRADE   Bob "))) return "namespaced /trade not parsed";
        if (MenusListener.tradeTarget("/trade") != null || MenusListener.tradeTarget("/trades Bob") != null
                || MenusListener.tradeTarget("/trade b@d") != null) return "a non-request was read as a trade request";
        if (!"Amy".equals(MenusListener.coopInviteTarget("/coop invite Amy"))) return "/coop invite Amy not parsed";
        if (MenusListener.coopInviteTarget("/coop leave Amy") != null) return "/coop leave read as an invite";
        if (SettingDefs.get(SettingDefs.TRADE_REQUESTS) == null || SettingDefs.get(SettingDefs.COOP_INVITES) == null)
            return "the two request settings must be defined";
        return null;
    }
}
