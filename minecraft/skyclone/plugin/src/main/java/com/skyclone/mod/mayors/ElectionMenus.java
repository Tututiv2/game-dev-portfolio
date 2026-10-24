package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The Election Booth, the Mayor's office and the election history. */
final class ElectionMenus {

    private ElectionMenus() {}

    private static MayorsModule mod() { return MayorsModule.get(); }

    static Material mat(String name, Material def) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m == null || !m.isItem() ? def : m;
    }

    static List<String> perkLines(MayorRoster roster, List<String> perkIds, String ministerPerk, boolean markActive) {
        return perkLines(roster, perkIds, ministerPerk, markActive, null);
    }

    /** {@code election}: the ballot's election for the Extra Event variant; null = the government in office. */
    static List<String> perkLines(MayorRoster roster, List<String> perkIds, String ministerPerk, boolean markActive, Integer election) {
        List<String> out = new ArrayList<>();
        ElectionService es = mod().election();
        String variant = election == null ? es.extraEventVariant() : es.extraEventVariantForElection(election);
        String extra = extraEventText(variant);
        for (String id : perkIds) {
            MayorRoster.Perk p = roster.perk(id);
            if (p == null) continue;
            String head = "&e" + p.name;
            if (p.id.equals(ministerPerk)) head += " &b(Minister perk)";
            if (markActive && es.perkActive(p.id)) head += " &a✔";
            out.add(head);
            for (String l : p.lore) out.add(l.replace("{v}", Text.commas(es.baseValue(p.id))).replace("{extra_event}", extra));
            out.add("");
        }
        return out;
    }

    /** Lore words for an Extra Event variant, with its date. */
    static String extraEventText(String variant) {
        MayorDials d = mod().dials();
        String when = "&7(" + seasonName(d.extraEventMonth.get()) + " " + d.extraEventDay.get() + "-"
                + (d.extraEventDay.get() + Math.max(1, d.extraEventDays.get()) - 1) + ")";
        return switch (variant) {
            case "mining" -> "&6Mining Fiesta " + when;
            case "spooky" -> "&6Spooky Festival &7(" + seasonName(d.spookyExtraMonth.get()) + " 29-31)";
            default -> "&bFishing Festival " + when;
        };
    }

    static String seasonName(int month) { return com.skyclone.api.time.SkyDate.MONTHS[Math.max(1, Math.min(12, month)) - 1]; }

    /** /election */
    static void open(Player p) {
        ElectionService es = mod().election();
        MayorRoster roster = es.roster();
        SkyDate d = Sky.calendar().now();
        Menu m = new Menu("Election", 6).chrome();
        boolean open = es.votingOpen();
        ElectionService.State st = es.state();
        String myVote = es.voteOf(p.getUniqueId());

        List<String> info = new ArrayList<>();
        if (open) {
            int days = ElectionMath.daysUntilClose(d.year(), d.month(), d.day());
            info.add("&7Vote for the mayor of &aYear " + (st.election + 1) + "&7.");
            info.add("&7The winner becomes &6Mayor&7; the runner-up");
            info.add("&7becomes &bMinister &7with one perk.");
            info.add("");
            info.add("&7Voting closes in &e" + Text.duration(days * Sky.calendar().millisPerDay() / 1000) + " &8(" + days + " SB days)");
            info.add("&7Total votes: &e" + Text.commasInt(es.totalVotes()));
            int weight = es.voteWeight(p);
            info.add("&7Your ballot is worth &a" + weight + " &7vote" + (weight == 1 ? "" : "s") + ".");
            FameVotes.Rank fr = MayorsModule.get().dials().fameVotes.get() ? FameVotes.rankOf(p) : null;
            if (fr != null) info.add("&8Fame Rank: &e" + fr.name() + " &8(" + fr.votes() + " vote" + (fr.votes() == 1 ? "" : "s") + ")");
            info.add("");
            MayorRoster.Mayor mine = roster.mayor(myVote);
            info.add(mine == null ? "&cYou have not voted yet!" : "&aYou voted for " + mine.display() + "&a.");
        } else {
            int days = ElectionMath.daysUntilOpen(d.year(), d.month(), d.day());
            info.add("&7The booth is &cclosed&7.");
            info.add("&7It opens on &eLate Summer 27th&7, in");
            info.add("&e" + Text.duration(days * Sky.calendar().millisPerDay() / 1000) + "&7.");
        }
        m.set(4, Icons.of(Material.JUKEBOX, "&aElection Booth", info));

        if (open) {
            Map<String, Long> tally = es.tally();
            long total = Math.max(1, es.totalVotes());
            int[] slots = st.candidates.size() <= 4 ? new int[]{20, 21, 23, 24} : new int[]{20, 21, 22, 23, 24};
            for (int i = 0; i < st.candidates.size() && i < slots.length; i++) {
                ElectionMath.Candidate c = st.candidates.get(i);
                MayorRoster.Mayor cm = roster.mayor(c.mayor);
                if (cm == null) continue;
                long v = tally.getOrDefault(c.mayor, 0L);
                List<String> lore = new ArrayList<>();
                lore.add("&8" + cm.type + (c.special ? " &5Special Candidate" : " Candidate"));
                lore.add("");
                lore.addAll(perkLines(roster, c.perks, c.special ? null : c.ministerPerk, false, st.election));
                lore.add("&7Votes: &a" + ElectionService.pct(v, total) + " &8(" + Text.commasInt(v) + ")");
                lore.add(Text.bar(v / (double) total, 20));
                lore.add("");
                boolean mineVote = c.mayor.equals(myVote);
                lore.add(mineVote ? "&aYou voted for this candidate!" : "&eClick to vote for " + cm.name + "!");
                ItemStack icon = Icons.of(mat(cm.icon, Material.PLAYER_HEAD), cm.display(), lore);
                if (mineVote) Icons.glow(icon);
                String id = c.mayor;
                m.set(slots[i], icon, click -> {
                    Sky.menus().confirm(click.player(), "Vote for " + cm.name + "?",
                            List.of("&7Cast your ballot for " + cm.display() + "&7.", "&7You can change it while the booth is open."),
                            () -> {
                                // A cast ballot closes the booth so its chat confirmation is readable: reopening
                                // the booth on top of it hid the line (playtest: "no chat confirmation for a vote").
                                // A refused vote reopens it, since the refusal reason is also in chat anyway.
                                if (!mod().election().vote(click.player(), id)) open(click.player());
                            }, () -> open(click.player()));
                });
                double frac = v / (double) total;
                Material pane = mineVote ? Material.LIME_STAINED_GLASS_PANE : frac >= 0.3 ? Material.YELLOW_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE;
                m.set(slots[i] + 9, Icons.glass(pane, cm.display() + " &7" + ElectionService.pct(v, total), Text.bar(frac, 20)));
            }
        } else {
            m.set(22, Icons.of(Material.CLOCK, "&cThe booth is closed", "&7Candidates are announced when", "&7voting opens on &eLate Summer 27th&7."));
        }

        m.set(38, mayorIcon(p, roster, st), c -> openMayor(c.player()));
        m.set(40, Icons.of(Material.FIREWORK_ROCKET, "&6Festivals & Events", "&7Every event the mayors run and", "&7when it happens next.", "", "&eClick to view!"),
                c -> EventBoard.open(c.player()));
        m.set(42, Icons.of(Material.BOOK, "&aElection History", "&7The last results, vote by vote.", "", "&eClick to view!"),
                c -> openHistory(c.player()));
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        m.open(p);
    }

    private static ItemStack mayorIcon(Player p, MayorRoster roster, ElectionService.State st) {
        MayorRoster.Mayor cur = roster.mayor(st.mayor);
        List<String> lore = new ArrayList<>();
        if (cur == null) {
            lore.add("&7No mayor yet.");
            return Icons.of(Material.GOLDEN_HELMET, "&6Current Mayor", lore);
        }
        lore.add("&8" + cur.type + " Mayor");
        lore.add("");
        lore.add("&7Perks: &e" + countActive(cur));
        MayorRoster.Mayor min = roster.mayor(st.minister);
        if (min != null) lore.add("&7Minister: " + min.display());
        lore.add("");
        lore.add("&eClick to view!");
        return Icons.glow(Icons.of(Material.GOLDEN_HELMET, "&6Mayor " + cur.display(), lore));
    }

    private static int countActive(MayorRoster.Mayor m) {
        int n = 0;
        for (MayorRoster.Perk p : m.perks) if (mod().election().perkActive(p.id)) n++;
        return n;
    }

    /** /mayor : the sitting mayor, minister and Perk Parade. */
    static void openMayor(Player p) {
        ElectionService es = mod().election();
        MayorRoster roster = es.roster();
        ElectionService.State st = es.state();
        Menu m = new Menu("Mayor", 5).chrome();
        MayorRoster.Mayor cur = roster.mayor(st.mayor);
        if (cur != null) {
            List<String> perks = (cur.special || mod().dials().mayorAllPerks.get()) ? cur.perkIds() : st.mayorPerks;
            List<String> lore = new ArrayList<>();
            lore.add("&8" + cur.type + " Mayor &7- term &aYear " + st.term);
            lore.add("");
            lore.addAll(perkLines(roster, perks, null, true));
            m.set(13, Icons.glow(Icons.of(mat(cur.icon, Material.GOLDEN_HELMET), "&6Mayor " + cur.display(), lore)));
        } else {
            m.set(13, Icons.of(Material.BARRIER, "&cNo mayor", "&7The first election has not been held."));
        }
        MayorRoster.Mayor min = roster.mayor(st.minister);
        if (min != null) {
            List<String> lore = new ArrayList<>();
            lore.add("&8" + min.type + " Minister");
            lore.add("");
            lore.addAll(perkLines(roster, st.ministerPerk == null ? List.of() : List.of(st.ministerPerk), null, true));
            m.set(29, Icons.of(mat(min.icon, Material.IRON_HELMET), "&bMinister " + min.display(), lore));
        } else {
            m.set(29, Icons.of(Material.GRAY_DYE, "&7No Minister", "&7A special mayor governs alone."));
        }
        String parade = es.paradeMayor();
        if (parade != null) {
            MayorRoster.Mayor pm = roster.mayor(parade);
            SkyDate d = Sky.calendar().now();
            int into = ElectionMath.daysIntoTerm(d.year(), d.month(), d.day());
            int period = Math.max(1, mod().dials().paradeDays.get());
            int left = period - into % period;
            List<String> lore = new ArrayList<>();
            lore.add("&7Right now every perk of " + (pm == null ? parade : pm.display()) + " &7is on.");
            lore.add("&7Next mayor in &e" + Text.duration(left * Sky.calendar().millisPerDay() / 1000) + "&7.");
            lore.add("");
            if (pm != null) lore.addAll(perkLines(roster, pm.perkIds(), null, true));
            m.set(31, Icons.of(Material.NOTE_BLOCK, "&a&lPerk Parade", lore));
        }
        List<String> act = new ArrayList<>();
        act.add("&7Every perk switched on right now:");
        act.add("");
        for (String id : es.activePerks()) {
            MayorRoster.Perk pk = roster.perk(id);
            MayorRoster.Mayor owner = roster.mayorOfPerk(id);
            if (pk != null) act.add("&8▸ &e" + pk.name + " &8(" + (owner == null ? "?" : owner.name) + ")");
        }
        m.set(33, Icons.of(Material.BEACON, "&aActive Perks", act));
        m.set(39, Icons.back("Election"), c -> open(c.player()));
        m.set(41, Icons.of(Material.FIREWORK_ROCKET, "&6Festivals & Events", "&eClick to view!"), c -> EventBoard.open(c.player()));
        m.open(p);
    }

    static void openHistory(Player p) {
        ElectionService es = mod().election();
        MayorRoster roster = es.roster();
        List<ElectionService.Result> hist = es.state().history;
        Menu m = new Menu("Election History", 4).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23};
        for (int i = 0; i < hist.size() && i < slots.length; i++) {
            ElectionService.Result r = hist.get(i);
            MayorRoster.Mayor w = roster.mayor(r.mayor);
            long total = 0;
            for (long v : r.votes.values()) total += v;
            List<String> lore = new ArrayList<>();
            lore.add("&8Term Year " + (r.election + 1));
            lore.add("");
            for (Map.Entry<String, Long> e : r.votes.entrySet()) {
                MayorRoster.Mayor cm = roster.mayor(e.getKey());
                lore.add((cm == null ? "&f" + e.getKey() : cm.display()) + " &7" + ElectionService.pct(e.getValue(), total)
                        + " &8(" + Text.commasInt(e.getValue()) + ")");
            }
            MayorRoster.Mayor min = roster.mayor(r.minister);
            if (min != null) { lore.add(""); lore.add("&7Minister: " + min.display()); }
            m.set(slots[i], Icons.of(w == null ? Material.PAPER : mat(w.icon, Material.PAPER), "&6Year " + (r.election + 1) + ": "
                    + (w == null ? r.mayor : w.display()), lore));
        }
        if (hist.isEmpty()) m.set(13, Icons.of(Material.PAPER, "&7No elections yet", "&7The first results appear here."));
        m.set(30, Icons.back("Election"), c -> open(c.player()));
        m.open(p);
    }
}
