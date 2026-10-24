package com.skyclone.mod.menus;

import com.skyclone.api.stat.Stat;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.time.SkyDate;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for the menus module: layouts, stat pages, calendar maths, settings, feature index. */
class MenusPureTest {

    @Test
    void skyMenuMatchesResearchSlots() {
        // research 08 section 11.1
        assertEquals(Set.of(13, 19, 20, 21, 22, 23, 24, 25, 29, 30, 31, 32, 33, 47, 48, 50, 51), SkyMenuLayout.RESEARCH.keySet());
        assertEquals("stats", SkyMenuLayout.RESEARCH.get(13));
        assertEquals("skills", SkyMenuLayout.RESEARCH.get(19));
        assertEquals("collections", SkyMenuLayout.RESEARCH.get(20));
        assertEquals("recipes", SkyMenuLayout.RESEARCH.get(21));
        assertEquals("sblevel", SkyMenuLayout.RESEARCH.get(22));
        assertEquals("quests", SkyMenuLayout.RESEARCH.get(23));
        assertEquals("calendar", SkyMenuLayout.RESEARCH.get(24));
        assertEquals("storage", SkyMenuLayout.RESEARCH.get(25));
        assertEquals("bags", SkyMenuLayout.RESEARCH.get(29));
        assertEquals("pets", SkyMenuLayout.RESEARCH.get(30));
        assertEquals("craft", SkyMenuLayout.RESEARCH.get(31));
        assertEquals("wardrobe", SkyMenuLayout.RESEARCH.get(32));
        assertEquals("bank", SkyMenuLayout.RESEARCH.get(33));
        assertEquals("fasttravel", SkyMenuLayout.RESEARCH.get(47));
        assertEquals("profiles", SkyMenuLayout.RESEARCH.get(48));
        assertEquals("settings", SkyMenuLayout.RESEARCH.get(50));
        assertEquals("cookie", SkyMenuLayout.RESEARCH.get(51));
        Set<Integer> all = new HashSet<>(SkyMenuLayout.RESEARCH.keySet());
        for (int s : SkyMenuLayout.QUICK_ROW.keySet()) assertTrue(all.add(s), "quick row collides at " + s);
        assertFalse(all.contains(SkyMenuLayout.CLOSE));
        for (String id : SkyMenuLayout.RESEARCH.values()) assertNotNull(FeatureIndex.get(id), id);
        for (String id : SkyMenuLayout.QUICK_ROW.values()) assertNotNull(FeatureIndex.get(id), id);
    }

    @Test
    void riftAndLeviathanHaveSkyMenuIcons() {
        // rift Request 3 / kuudra Request 3: icons beside Fast Travel, not on a research slot
        assertEquals("rift", SkyMenuLayout.DESTINATIONS.get(46));
        assertEquals("kuudra", SkyMenuLayout.DESTINATIONS.get(52));
        Set<Integer> all = new HashSet<>(SkyMenuLayout.RESEARCH.keySet());
        all.addAll(SkyMenuLayout.QUICK_ROW.keySet());
        for (int s : SkyMenuLayout.DESTINATIONS.keySet()) {
            assertTrue(all.add(s), "destination collides at " + s);
            assertTrue(s >= 0 && s < 54 && s != SkyMenuLayout.CLOSE, "bad slot " + s);
        }
        // they open the modules' own commands, with the names those modules use
        assertEquals("rift", FeatureIndex.get("rift").primaryCommand());
        assertEquals("kuudra", FeatureIndex.get("kuudra").primaryCommand());
        assertEquals("Leviathan's Hollow", FeatureIndex.get("kuudra").name());
        assertEquals("Leviathan's Hollow", TravelLayout.entry("kuudra").name());
        assertEquals("island.rift", FeatureIndex.get("rift").texture());
        assertEquals("island.kuudra", FeatureIndex.get("kuudra").texture());
    }

    @Test
    void everyStatOnExactlyOnePage() {
        int total = 0;
        for (StatCategories.Category c : StatCategories.Category.values()) {
            List<Stat> page = StatCategories.of(c);
            assertTrue(page.size() <= ProfileViewer.GRID.length, c + " overflows its page");
            total += page.size();
        }
        assertEquals(Stat.values().length, total);
        for (Stat s : Stat.values()) assertTrue(StatCategories.covered(s), s + " has no page");
        assertEquals(StatCategories.Category.COMBAT, StatCategories.info(Stat.STRENGTH).category());
        assertEquals(StatCategories.Category.WISDOM, StatCategories.info(Stat.FARMING_WISDOM).category());
        assertEquals(StatCategories.Category.GATHERING, StatCategories.info(Stat.MINING_FORTUNE).category());
        assertEquals(StatCategories.Category.MISC, StatCategories.info(Stat.MAGIC_FIND).category());
    }

    @Test
    void statFormatting() {
        assertEquals("65%", StatCategories.format(Stat.CRIT_CHANCE, 65));
        assertEquals("1,234", StatCategories.format(Stat.HEALTH, 1234));
        assertEquals("12.5", StatCategories.format(Stat.FEROCITY, 12.5));
        assertEquals("&c❁ Strength &f350", StatCategories.valueLine(Stat.STRENGTH, 350));
        assertEquals("Held Item", StatCategories.sourceName("held"));
        assertEquals("Century Cakes", StatCategories.sourceName("progression.cakes"));
        assertEquals("Some Thing", StatCategories.sourceName("some.thing"));
    }

    @Test
    void calendarResearchNumbers() {
        // research 08 s8: 1 SB day = 20 real minutes -> 50 s per SB hour, 372 days = 124 real hours
        assertEquals(50.0, CalendarMath.realSecondsPerHour(1_200_000), 1e-9);
        assertEquals(124.0, CalendarMath.realHoursPerYear(1_200_000), 1e-9);
        assertEquals(372, CalendarMath.DAYS_PER_YEAR);
        assertEquals(0, CalendarMath.dayIndex(1, 1));
        assertEquals(371, CalendarMath.dayIndex(12, 31));
    }

    @Test
    void calendarEventSpans() {
        CalendarEvent spooky = CalendarEvent.onDate("spooky", "Spooky Festival", 8, 29, 3);   // Autumn 29-31
        assertTrue(CalendarMath.occursOn(spooky, 3, 8, 29));
        assertTrue(CalendarMath.occursOn(spooky, 3, 8, 31));
        assertFalse(CalendarMath.occursOn(spooky, 3, 8, 28));
        assertFalse(CalendarMath.occursOn(spooky, 3, 9, 1));
        assertEquals(2, CalendarMath.dayOfEvent(spooky, 3, 8, 30));

        CalendarEvent wrap = CalendarEvent.onDate("ny", "New Year", 12, 30, 4);
        assertTrue(CalendarMath.occursOn(wrap, 5, 12, 31));
        assertTrue(CalendarMath.occursOn(wrap, 6, 1, 2));
        assertFalse(CalendarMath.occursOn(wrap, 6, 1, 3));

        CalendarEvent monthly = CalendarEvent.onDate("m", "Monthly", 0, 31, 2);
        assertTrue(CalendarMath.occursOn(monthly, 1, 4, 31));
        assertTrue(CalendarMath.occursOn(monthly, 1, 5, 1));
        assertFalse(CalendarMath.occursOn(monthly, 1, 5, 2));
        assertTrue(CalendarMath.occursOn(monthly, 2, 1, 1));    // spill from Late Winter of year 1

        CalendarEvent everyThird = CalendarEvent.onDate("e3", "Every third", 3, 1, 1);
        everyThird.everyYears = 3;
        everyThird.yearOffset = 1;
        assertTrue(CalendarMath.occursOn(everyThird, 4, 3, 1));
        assertFalse(CalendarMath.occursOn(everyThird, 5, 3, 1));

        CalendarEvent rt = CalendarEvent.realtime("da", "Auction", 60, 55, 5);
        assertFalse(CalendarMath.occursOn(rt, 1, 1, 1));
        assertEquals("Every hour, lasts 5 min", CalendarMath.schedule(rt));
        assertEquals("Every year: Autumn 29th to 31st (3 days)", CalendarMath.schedule(spooky));
        assertEquals("Every year: Late Winter 30th to Early Spring 2nd (4 days)", CalendarMath.schedule(wrap));
    }

    @Test
    void calendarCountdowns() {
        long day = 1_200_000;
        SkyDate noon = new SkyDate(1, 1, 1, 12, 0);
        assertEquals(day / 2, CalendarMath.millisUntilDay(noon, 1, 2, day, false));
        assertEquals(0, CalendarMath.millisUntilDay(noon, 1, 1, day, false));
        assertEquals(0, CalendarMath.millisUntilDay(noon, 1, 1, day, true));               // today is today, not next year
        SkyDate lateNoon = new SkyDate(1, 1, 5, 12, 0);
        assertEquals(Math.round((CalendarMath.DAYS_PER_YEAR - 4.5) * day), CalendarMath.millisUntilDay(lateNoon, 1, 1, day, true));
        int[] next = CalendarMath.shiftMonth(12, 4, 1);
        assertEquals(1, next[0]);
        assertEquals(5, next[1]);
        int[] prev = CalendarMath.shiftMonth(1, 4, -1);
        assertEquals(12, prev[0]);
        assertEquals(3, prev[1]);
        SkyDate after = CalendarMath.dateAfter(noon, day, day);
        assertEquals(new SkyDate(1, 1, 2, 12, 0), after);
        SkyDate wrapped = CalendarMath.dateAfter(new SkyDate(1, 12, 31, 0, 0), day, day);
        assertEquals(new SkyDate(2, 1, 1, 0, 0), wrapped);
    }

    @Test
    void travelLayout() {
        Set<Integer> slots = new HashSet<>();
        for (TravelLayout.Entry e : TravelLayout.entries()) {
            assertTrue(TravelLayout.CONTRACT_ISLANDS.contains(e.island()), e.island());
            assertTrue(slots.add(e.slot()), "slot " + e.slot());
            int col = e.slot() % 9;
            assertTrue(col >= 1 && col <= 7 && e.slot() >= 10 && e.slot() <= 43, e.island() + " on the border");
        }
        // research 08 s11.10 slots for the islands it lists
        assertEquals(10, TravelLayout.entry("private_island").slot());
        assertEquals(11, TravelLayout.entry("hub").slot());
        assertEquals(14, TravelLayout.entry("garden").slot());
        assertEquals(21, TravelLayout.entry("dwarven_mines").slot());
        assertEquals(25, TravelLayout.entry("crimson_isle").slot());
        assertEquals(28, TravelLayout.entry("jerrys_workshop").slot());
        Map<String, Integer> a = TravelLayout.assign(List.of("hub", "made_up_island", "another"));
        assertEquals(11, a.get("hub"));
        assertEquals(33, a.get("made_up_island"));
        assertEquals(34, a.get("another"));
        for (int s : TravelLayout.OVERFLOW_SLOTS) assertFalse(slots.contains(s), "overflow slot " + s + " is taken");
    }

    @Test
    void settingsData() {
        Set<String> keys = new HashSet<>();
        for (SettingDefs.Setting s : SettingDefs.all()) {
            assertTrue(keys.add(s.key()), "duplicate " + s.key());
            assertTrue(s.valid(s.def()), s.key() + " default");
            assertTrue(s.key().matches("[a-z0-9._]+"), s.key());
        }
        for (SettingDefs.Category c : SettingDefs.Category.values()) assertFalse(SettingDefs.of(c).isEmpty(), c.name());
        SettingDefs.Setting t = SettingDefs.get(SettingDefs.CHAT_PLAYERS);
        assertEquals("false", SettingDefs.next(t, "true", false));
        assertEquals("true", SettingDefs.next(t, "false", true));
        SettingDefs.Setting c = SettingDefs.get(SettingDefs.NOTIFY_REMIND_MIN);
        assertEquals("10", SettingDefs.next(c, "5", false));
        assertEquals("1", SettingDefs.next(c, "5", true));
        assertEquals("1", SettingDefs.next(c, "30", false));
        assertFalse(c.valid("7"));
        assertEquals("&e5 minutes", SettingDefs.label(c, "5"));
    }

    @Test
    void featureIndex() {
        Set<String> ids = new HashSet<>();
        for (FeatureIndex.Feature f : FeatureIndex.all()) {
            assertTrue(ids.add(f.id()), f.id());
            assertTrue(f.service() != null || !f.commands().isEmpty(), f.id() + " cannot be opened");
            for (String cmd : f.commands()) assertTrue(cmd.matches("[a-z0-9_-]+"), f.id() + " command " + cmd);
        }
        for (FeatureIndex.Category c : FeatureIndex.Category.values()) assertFalse(FeatureIndex.of(c).isEmpty(), c.name());
        for (String id : MiscMenus.BAG_IDS) assertNotNull(FeatureIndex.get(id), id);
        // every guide category has a slot, and a category page fits one paged screen (28)
        assertEquals(FeatureIndex.Category.values().length, MiscMenus.GUIDE_CATS.length);
        for (FeatureIndex.Category c : FeatureIndex.Category.values()) assertTrue(FeatureIndex.of(c).size() <= 28, c.name());
        // the cookie slot's first command is the one the bank registers
        assertEquals("cookie", FeatureIndex.get("cookie").primaryCommand());
        // storage owns /bags; ours is reachable as /bagsmenu
        assertEquals(List.of("bags", "bagsmenu"), FeatureIndex.get("bags").commands());
    }

    @Test
    void calendarEventsOpenTheirFeature() {
        // ids registered by mayors / farming / fishing / progression (2026-10-02)
        assertEquals("spooky", FeatureIndex.forEvent("spooky_festival"));
        assertEquals("spooky", FeatureIndex.forEvent("spooky_festival_extra"));
        assertEquals("newyear", FeatureIndex.forEvent("new_year_celebration"));
        assertEquals("newyear", FeatureIndex.forEvent("progression_new_year"));
        assertEquals("zoo", FeatureIndex.forEvent("traveling_zoo"));
        assertEquals("zoo", FeatureIndex.forEvent("traveling_zoo_winter"));
        assertEquals("winter", FeatureIndex.forEvent("season_of_jerry"));
        assertEquals("winter", FeatureIndex.forEvent("jerrys_workshop_open"));
        assertEquals("darkauction", FeatureIndex.forEvent("dark_auction"));
        assertEquals("election", FeatureIndex.forEvent("election_booth"));
        assertEquals("election", FeatureIndex.forEvent("mayor_term_start"));
        assertEquals("harvestfair", FeatureIndex.forEvent("farming_contest"));
        assertEquals("fishing", FeatureIndex.forEvent("fishing_festival"));
        assertEquals("cakes", FeatureIndex.forEvent("progression_century_celebration"));
        assertEquals("mythology", FeatureIndex.forEvent("mythological_ritual"));
        assertEquals("festivals", FeatureIndex.forEvent("something_new"));
        assertEquals("festivals", FeatureIndex.forEvent(null));
        for (String id : List.of("spooky_festival", "dark_auction", "x")) assertNotNull(FeatureIndex.get(FeatureIndex.forEvent(id)));
    }

    @Test
    void upcomingFilter() {
        long day = 1_200_000;
        long horizon = CalendarMath.horizonMillis(day);
        assertEquals(200L * 372 * day, horizon);
        assertTrue(CalendarMath.listable(true, 0, horizon));
        assertTrue(CalendarMath.listable(false, 5_000, horizon));
        assertFalse(CalendarMath.listable(false, -1, horizon), "core's 'never' (-1) must not read as 'in 0s'");
        assertFalse(CalendarMath.listable(false, horizon + 1, horizon), "a forced-only event parked a million years out");
        // a century event (every 100 years) is still listed
        assertTrue(CalendarMath.listable(false, 100L * 372 * day, horizon));
        // two registrations of one festival collapse, different festivals do not
        assertEquals(CalendarMath.dedupeKey("New Year Celebration", false, 600_000),
                CalendarMath.dedupeKey("new year celebration ", false, 610_000));
        assertFalse(CalendarMath.dedupeKey("Traveling Zoo", false, 600_000)
                .equals(CalendarMath.dedupeKey("Traveling Zoo", false, 600_000 + 186 * day)));
    }

    @Test
    void breakdownRowsAreToldApart() {
        assertEquals(new StatCategories.SourceKey("pets", StatCategories.PartKind.FLAT), StatCategories.parseKey("pets"));
        assertEquals(new StatCategories.SourceKey("pets", StatCategories.PartKind.PERCENT), StatCategories.parseKey("pets (%)"));
        assertEquals(new StatCategories.SourceKey("armor_sets_final", StatCategories.PartKind.MULTIPLIER), StatCategories.parseKey("armor_sets_final (x)"));
        assertEquals("Pet", StatCategories.sourceName("pets (%)"));
        assertEquals("Armor Set Bonus", StatCategories.sourceName("armor_sets_final (x)"));
        assertEquals("Cookie Buff", StatCategories.sourceName("bank.cookie"));
        assertEquals("&a+12", StatCategories.partValue(Stat.STRENGTH, StatCategories.PartKind.FLAT, 12));
        assertEquals("&a+10%", StatCategories.partValue(Stat.STRENGTH, StatCategories.PartKind.PERCENT, 10));
        assertEquals("&ax1.50", StatCategories.partValue(Stat.STRENGTH, StatCategories.PartKind.MULTIPLIER, 1.5));
        assertEquals("&c-5", StatCategories.partValue(Stat.DEFENSE, StatCategories.PartKind.FLAT, -5));
        assertEquals("COOKIE", StatCategories.sourceIcon("bank.cookie"));
    }

    @Test
    void requirementParts() {
        assertEquals(List.of("skill:foraging:5", "foraging:scroll:jungle"), TravelLayout.requirementParts("skill:foraging:5 & foraging:scroll:jungle"));
        assertEquals(List.of(), TravelLayout.requirementParts(null));
        assertEquals(List.of(), TravelLayout.requirementParts("  "));
        assertEquals(List.of("hubzone:castle"), TravelLayout.requirementParts("hubzone:castle"));
    }

    private static com.skyclone.api.world.WorldService.WarpInfo w(String id, String island, String anchor, String name, String req) {
        return new com.skyclone.api.world.WorldService.WarpInfo(id, island, anchor, name, req);
    }

    /** Playtest: All Warps showed Dwarven Mines x3, The Forge x2, Crystal Caverns x2, Base Camp x2. */
    @Test
    void warpAliasesFoldIntoOneDestination() {
        var dm = w("dwarven_mines", "dwarven_mines", "spawn", "Dwarven Mines", "skill:mining:12");
        var mines = w("mines", "dwarven_mines", "spawn", "Dwarven Mines", "skill:mining:12");
        var forge = w("forge", "dwarven_mines", "station.forge", "The Forge", "skill:mining:12");
        var camp = w("camp", "dwarven_mines", "station.base_camp", "Dwarven Base Camp", "skill:mining:12");
        var mForge = w("mining_forge", "dwarven_mines", "npc.forge", "&6The Forge", "mining:scroll:forge");
        var mDwarf = w("mining_dwarven", "dwarven_mines", "spawn", "Dwarven Mines", "mining:scroll:mines_scroll");
        var ch = w("crystal_hollows", "crystal_hollows", "spawn", "Crystal Caverns", null);
        var mCh = w("mining_crystals", "crystal_hollows", "spawn", "Crystal Caverns", "mining:scroll:crystals");
        var nucleus = w("mining_nucleus", "crystal_hollows", "nucleus", "Crystal Nucleus", "mining:scroll:nucleus");
        var gt = w("glacite_tunnels", "glacite_tunnels", "spawn", "Frozen Tunnels", null);
        var mCamp = w("mining_base_camp", "glacite_tunnels", "spawn", "Base Camp", "mining:scroll:base_camp");
        var hub = w("hub", "hub", "spawn", "Hub", null);
        var village = w("village", "hub", "district.village", "Village", "hubzone:village");
        var groups = TravelLayout.aliasGroups(List.of(dm, mines, forge, camp, mForge, mDwarf, ch, mCh, nucleus, gt, mCamp, hub, village));
        assertEquals(List.of(
                List.of(dm, mines, mDwarf), List.of(forge, mForge), List.of(camp),
                List.of(ch, mCh), List.of(nucleus), List.of(gt, mCamp), List.of(hub), List.of(village)), groups);

        // the island's own warp wins when everything is open; an open alias beats a locked main warp
        assertEquals(dm, TravelLayout.pick(groups.get(0), "dwarven_mines", x -> true));
        assertEquals(mDwarf, TravelLayout.pick(groups.get(0), "dwarven_mines", x -> x == mDwarf));
        // never land on the NPC when another way to the same place is open
        assertEquals(forge, TravelLayout.pick(groups.get(1), "dwarven_mines", x -> true));
        assertEquals(mForge, TravelLayout.pick(groups.get(1), "dwarven_mines", x -> x == mForge));
        assertEquals(forge, TravelLayout.pick(groups.get(1), "dwarven_mines", x -> false));
    }

    @Test
    void overflowIslandsHaveADescription() {
        assertNotNull(TravelLayout.entry("galatea"), "the Moonmarsh");
        assertNotNull(TravelLayout.entry("galatea").about());
        assertFalse(TravelLayout.entries().stream().anyMatch(e -> e.island().equals("galatea")), "overflow islands keep their overflow slot");
    }

    /** Playtest wave4-endgame #11: the alert scope lives in Settings, and /sb menus setting names who reads what. */
    @Test
    void calendarAlertScopeIsASetting() {
        SettingDefs.Setting sc = SettingDefs.get(SettingDefs.NOTIFY_SCOPE);
        assertNotNull(sc);
        assertEquals(SettingDefs.Category.NOTIFICATIONS, sc.category());
        assertEquals("important", sc.def(), "mayors' default (AlertPolicy.Scope.parse(null))");
        assertEquals(List.of("important", "all", "off"), sc.options(), "mayors' ids, in its cycle order");
        assertFalse("menus".equals(sc.reader()), "mayors writes it on the PDC: it must be adopted, not overwritten");
        assertEquals("all", SettingDefs.next(sc, "important", false));
        assertEquals("off", SettingDefs.next(sc, "all", false));
        assertEquals("important", SettingDefs.next(sc, "off", false));
        assertEquals("&aImportant only", SettingDefs.label(sc, "important"));
        assertEquals("&eEverything", SettingDefs.label(sc, "all"));
        assertEquals("&cOff", SettingDefs.label(sc, "off"));
        assertTrue(SettingDefs.of(SettingDefs.Category.NOTIFICATIONS).size() <= SettingsMenu.PER_PAGE, "Notifications fits one page");

        SettingDefs.Setting remind = SettingDefs.get(SettingDefs.NOTIFY_REMIND);
        // mayors holds the job (its takeover switched menus.event-reminders off): the toggles are mayors'
        assertEquals("mayors", SettingDefs.liveReader(remind, false, true, true));
        assertEquals("mayors", SettingDefs.liveReader(sc, false, true, true));
        // menus announces, mayors installed: menus sends, mayors decides the scope
        assertEquals("menus", SettingDefs.liveReader(remind, true, true, false));
        assertEquals("menus, decided by mayors", SettingDefs.liveReader(sc, true, true, false));
        assertEquals("menus (off only)", SettingDefs.liveReader(sc, true, false, false));
        assertTrue(SettingDefs.liveReader(remind, false, true, false).startsWith("nobody"));
        // unrelated keys keep their static reader
        assertEquals("bazaar", SettingDefs.liveReader(SettingDefs.get("notify.bazaar_filled"), false, true, true));
    }

    /** Playtest wave4-endgame #12: the Bazaar and Auction House icons state one rule in one sentence. */
    @Test
    void marketIconsShareOneReachRule() {
        assertEquals("sblevel:5", SkyMenuLayout.accessKey("sblevel:5"));
        assertEquals("sblevel:3", SkyMenuLayout.accessKey("3"));
        assertEquals("", SkyMenuLayout.accessKey("none"));
        assertEquals("", SkyMenuLayout.accessKey("0"));
        assertEquals("", SkyMenuLayout.accessKey("\"\""));
        assertEquals("", SkyMenuLayout.accessKey(null));
        String bz = SkyMenuLayout.reachText("the &6Bazaar &7in the Hub", "Boon Buff", false, false);
        String ah = SkyMenuLayout.reachText("the &6Auction House &7in the Hub", "Boon Buff", false, false);
        assertEquals(bz.replace("Bazaar", "X"), ah.replace("Auction House", "X"), "same rule, same words");
        assertTrue(bz.contains("Boon Buff") && bz.contains("Hub"), bz);
        assertTrue(SkyMenuLayout.reachText("p", "Boon Buff", true, false).contains("lets you use it from anywhere"));
        assertEquals("You can use it from anywhere.", SkyMenuLayout.reachText("p", "Boon Buff", false, true));
        assertEquals("Use it at p&7.", SkyMenuLayout.reachText("p", null, null, false), "auction: blank remote requirement = nobody");
    }

    /** The Quest Log line reads progression's own text ("Talk to your Island Helper"), not the title-cased id. */
    @Test
    void objectiveTextsComeFromProgressionData() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("data/progression/objectives.json")) {
            assertNotNull(in, "progression's objectives.json on the classpath");
            var map = Summaries.parseObjectiveTexts(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(map.size() >= 10, "objective chain read: " + map.size());
            assertEquals("Talk to your Island Helper", map.get("talk_island_helper").text);
            for (var o : map.values()) assertTrue(o.text != null && !o.text.isBlank(), o.id + " has text");
        }
    }

    // ---- wave 5 (2026-10-06) ------------------------------------------------------------------------------

    /** Trade Requests / Co-op Invites: the typed commands the settings gate are read exactly. */
    @Test
    void requestCommandsAreParsed() {
        assertEquals("Bob_1", MenusListener.tradeTarget("/trade Bob_1"));
        assertEquals("Bob", MenusListener.tradeTarget("  /skyclone:TRADE   Bob "));
        assertEquals(null, MenusListener.tradeTarget("/trade"));
        assertEquals(null, MenusListener.tradeTarget("/trades Bob"));
        assertEquals(null, MenusListener.tradeTarget("/trade b@d"));
        assertEquals("Amy", MenusListener.coopInviteTarget("/coop invite Amy"));
        assertEquals("Amy", MenusListener.coopInviteTarget("/coop add Amy"));
        assertEquals(null, MenusListener.coopInviteTarget("/coop leave Amy"));
        assertEquals("menus", SettingDefs.get(SettingDefs.TRADE_REQUESTS).reader());
    }

    /** Sidebar toggles: Display toggles read here, disjoint groups, never the dungeon/hunt run state. */
    @Test
    void sidebarTogglesAreSafe() {
        Set<String> seen = new HashSet<>();
        assertEquals(4, SidebarToggles.GROUPS.size());
        for (var e : SidebarToggles.GROUPS.entrySet()) {
            SettingDefs.Setting st = SettingDefs.get(e.getKey());
            assertNotNull(st, e.getKey());
            assertTrue(st.toggle() && "true".equals(st.def()), e.getKey());
            assertEquals(SettingDefs.Category.DISPLAY, st.category());
            assertEquals("menus", st.reader());
            for (String id : e.getValue()) {
                assertTrue(seen.add(id), "in two groups: " + id);
                assertFalse(SidebarToggles.NEVER.contains(id), id);
            }
        }
        assertTrue(seen.contains("slayer") && seen.contains("progression.objective") && seen.contains("mayors"));
        assertTrue(SettingDefs.of(SettingDefs.Category.DISPLAY).size() <= SettingsMenu.PER_PAGE, "Display fits one page");
    }

    /** The offline profile viewer refuses empty and private snapshots; admins bypass privacy only. */
    @Test
    void offlineProfileRefusals() {
        PvSnapshot.Data d = new PvSnapshot.Data();
        assertNotNull(PvSnapshot.refusal(true, null, "X"));
        assertNotNull(PvSnapshot.refusal(true, d, "X"), "never-captured profile");
        d.at = 1;
        d.slots.put("4", "head");
        assertEquals(null, PvSnapshot.refusal(false, d, "X"));
        d.allowView = false;
        assertTrue(PvSnapshot.refusal(false, d, "X").contains("turned off"));
        assertEquals(null, PvSnapshot.refusal(true, d, "X"));
        assertEquals(StatCategories.Category.COMBAT, PvSnapshot.categoryAt(ProfileViewer.CATS[0]));
        assertEquals(null, PvSnapshot.categoryAt(ProfileViewer.HEAD));
    }

    /** Wave 5 features: unique ids, a command each, the reforge/enchant stations on the quick row. */
    @Test
    void waveFiveFeaturesAreIndexed() {
        for (String id : List.of("hunting", "attributes", "soulflow", "phone", "chocolate", "party", "partyfinder",
                "galatea", "sail", "runes", "community", "hotspots", "vitality", "skins", "furniture")) {
            FeatureIndex.Feature f = FeatureIndex.get(id);
            assertNotNull(f, id);
            assertNotNull(f.primaryCommand(), id);
        }
        assertEquals("Ringphone", FeatureIndex.get("phone").name(), "docs/names/extras.md");
        assertEquals("reforges", SkyMenuLayout.QUICK_ROW.get(36));
        assertEquals("enchant", SkyMenuLayout.QUICK_ROW.get(44));
        assertEquals("bank", FeatureIndex.get("bank").service());
        assertEquals("Critter Safari", TravelLayout.entry("critter_safari").name());
        Set<String> names = new HashSet<>();
        for (FeatureIndex.Feature f : FeatureIndex.all()) assertTrue(names.add(f.name()), "two features named " + f.name());
    }
}
