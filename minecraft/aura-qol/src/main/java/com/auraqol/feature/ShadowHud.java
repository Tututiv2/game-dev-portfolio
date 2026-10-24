package com.auraqol.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.auraqol.AuraQoL;
import com.auraqol.shadow.LiveState;
import com.auraqol.shadow.Msg;
import com.auraqol.shadow.ProfileCard;
import com.auraqol.shadow.RankEta;
import com.auraqol.shadow.ShadowFmt;
import com.auraqol.skyblock.Fmt;
import com.auraqol.skyblock.RateTracker;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

/**
 * The Shadow Prison panels: Mining, Earnings, Rank-up, Boosts, Contracts,
 * Skills and the profile card. Every number comes from {@link LiveState}, which
 * holds only what the server sent; this class formats and draws, it never
 * computes money from anything else.
 *
 * <p>Each panel is its own HUD element ({@code /aq toggle sp-...}, placeable and
 * scalable with ]), drawn only after WELCOME, only for features the server
 * listed, and only once that feature's data has arrived. While the HUD editor
 * is open (and the Shadow set applies) a panel with no data draws a sample so
 * it can be found and placed.
 *
 * <p>All panels share one row-based renderer so seven panels read as one
 * product: header (status dot, title, mode, right-hand note), the gold rule,
 * then text rows, bars and thin separators, in {@link HudStyle}.
 */
final class ShadowHud {

    private ShadowHud() {
    }

    private static boolean loggedFailure;

    // ------------------------------------------------------------ rows

    private record Seg(String text, int color) {
    }

    private record Row(int kind, List<Seg> left, String right, int rightColor, double frac, int barColor) {
        static final int TEXT = 0;
        static final int BAR = 1;
        static final int SEP = 2;
        static final int HEAD = 3;
    }

    /** One panel's content. Built fresh each frame (a handful of small objects; the HUD is not a hot loop). */
    private static final class Panel {
        final String id;
        String title = "";
        String sub = "";
        String right = "";
        int rightColor = HudStyle.DIM;
        int dot = HudStyle.ACCENT;
        int minW = 120;
        final List<Row> rows = new ArrayList<>();

        Panel(String id) {
            this.id = id;
        }

        Panel text(Seg... segs) {
            rows.add(new Row(Row.TEXT, List.of(segs), "", 0, 0, 0));
            return this;
        }

        Panel pair(String left, int leftColor, String right, int rightColor) {
            rows.add(new Row(Row.TEXT, List.of(new Seg(left, leftColor)), right, rightColor, 0, 0));
            return this;
        }

        Panel bar(double frac, int color) {
            rows.add(new Row(Row.BAR, List.of(), "", 0, frac, color));
            return this;
        }

        Panel sep() {
            rows.add(new Row(Row.SEP, List.of(), "", 0, 0, 0));
            return this;
        }

        Panel head(String text) {
            rows.add(new Row(Row.HEAD, List.of(new Seg(text, HudStyle.DIM)), "", 0, 0, 0));
            return this;
        }
    }

    private static Seg s(String text, int color) {
        return new Seg(text, color);
    }

    // ------------------------------------------------------------ register

    static void register() {
        add(HudLayout.SP_MINING, ShadowHud::mining);
        add(HudLayout.SP_EARNINGS, ShadowHud::earnings);
        add(HudLayout.SP_RANKUP, ShadowHud::rankup);
        add(HudLayout.SP_BOOSTS, ShadowHud::boosts);
        add(HudLayout.SP_CONTRACT, ShadowHud::contract);
        add(HudLayout.SP_SKILLS, ShadowHud::skills);
        add(HudLayout.SP_PROFILE, ShadowHud::profile);
    }

    private interface Builder {
        /** The panel to draw, or null for nothing. {@code sample} = draw an example (editor). */
        Panel build(LiveState st, long now, boolean sample);
    }

    private static void add(String id, Builder b) {
        Identifier rid = Identifier.fromNamespaceAndPath("auraqol", id.replace('-', '_'));
        HudElementRegistry.addLast(rid, (g, delta) -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null || mc.player == null || !HudLayout.enabled(id)) {
                    return;
                }
                boolean live = ShadowLive.active();
                boolean sample = !live && HudStyle.editing() && HudLayout.editorShows(id);
                if (!live && !sample) {
                    return;
                }
                Panel p = b.build(ShadowLive.state(), ShadowLive.now(), sample);
                if (p == null && live && HudStyle.editing()) {
                    p = b.build(ShadowLive.state(), ShadowLive.now(), true);
                }
                if (p != null) {
                    draw(g, mc.font, p);
                }
            } catch (Throwable t) {
                if (!loggedFailure) {
                    loggedFailure = true;
                    AuraQoL.LOG.warn("[auraqol] Shadow panel " + id + " failed once (further errors silenced)", t);
                }
            }
        });
    }

    private static boolean recent() {
        return HudConfig.get().spRecent;
    }

    private static long windowMs() {
        return HudConfig.get().spWindowMinutes * 60_000L;
    }

    private static int text() {
        return HudConfig.get().textColor;
    }

    private static int label() {
        return HudConfig.get().labelColor;
    }

    private static int dotFor(RateTracker.State st) {
        return switch (st) {
            case ACTIVE -> HudStyle.GAIN;
            case PAUSED -> HudStyle.WARN;
            default -> HudStyle.IDLE;
        };
    }

    // ------------------------------------------------------------ panels

    private static Panel mining(LiveState st, long now, boolean sample) {
        // One panel that follows what you are doing: Mining, or Combat once your last action was a kill.
        if (!sample && st.has("combat") && st.seenCombat() && "Combat".equals(st.activity())) {
            return combat(st, now);
        }
        if (!sample && (!st.has("breaks") || !st.seenBreaks())) {
            return null;
        }
        boolean rec = recent();
        long win = windowMs();
        Panel p = new Panel(HudLayout.SP_MINING);
        p.title = "Mining";
        p.sub = ShadowLive.modeLabel();
        String area = sample ? "Mine C" : st.has("area") ? st.area() : null;
        boolean hasRate = sample || st.miningHasRate(now);
        double bph = sample ? 12_340 : st.blocksPerHour(now, rec, win);
        double vph = sample ? 124_000_000 : st.valueCentsPerHour(now, rec, win);
        long sBlocks = sample ? 4321 : st.sessionBlocks();
        long sValue = sample ? 51_200_000 : st.sessionValueCents();
        RateTracker.State clock = sample ? RateTracker.State.ACTIVE : st.miningClock().state(now);
        p.dot = dotFor(clock);
        p.right = area != null && !area.isEmpty() ? area
                : clock == RateTracker.State.IDLE ? "idle" : Fmt.duration(sample ? 312_000 : st.miningClock().activeMs(now));
        p.rightColor = area != null && !area.isEmpty() ? HudStyle.ACCENT : HudStyle.DIM;
        int value = hasRate ? text() : HudStyle.FAINT;
        p.text(s(hasRate ? ShadowFmt.count(bph) : "-", value), s(" blocks/h", label()));
        p.text(s(hasRate ? ShadowFmt.money(vph) : "-", hasRate ? HudStyle.COIN : HudStyle.FAINT),
                s(" /h value mined", label()), s(" (est.)", HudStyle.DIM));
        p.sep();
        p.text(s("Session ", label()), s(String.format(Locale.ROOT, "%,d", sBlocks), text()),
                s(" blocks  ", label()), s(ShadowFmt.money(sValue), HudStyle.COIN), s(" est.", HudStyle.DIM));
        Msg.Progress pr = sample ? null : st.progress();
        if (sample || (pr != null && st.has("progress"))) {
            long total = sample ? 1_234_567 : pr.blocksMinedTotal();
            p.text(s("Total mined ", label()), s(String.format(Locale.ROOT, "%,d", total), text()));
        }
        return p;
    }

    /** The Mining panel's Combat face: kills/h, the value of what the kills dropped, session totals. */
    private static Panel combat(LiveState st, long now) {
        boolean rec = recent();
        long win = windowMs();
        Panel p = new Panel(HudLayout.SP_MINING);
        p.title = "Combat";
        p.sub = ShadowLive.modeLabel();
        boolean hasRate = st.miningHasRate(now);
        RateTracker.State clock = st.miningClock().state(now);
        p.dot = dotFor(clock);
        String area = st.has("area") ? st.area() : null;
        p.right = area != null && !area.isEmpty() ? area
                : clock == RateTracker.State.IDLE ? "idle" : Fmt.duration(st.miningClock().activeMs(now));
        p.rightColor = area != null && !area.isEmpty() ? HudStyle.ACCENT : HudStyle.DIM;
        int value = hasRate ? text() : HudStyle.FAINT;
        p.text(s(hasRate ? ShadowFmt.count(st.killsPerHour(now, rec, win)) : "-", value), s(" kills/h", label()));
        p.text(s(hasRate ? ShadowFmt.money(st.combatValueCentsPerHour(now, rec, win)) : "-",
                hasRate ? HudStyle.COIN : HudStyle.FAINT), s(" /h from drops", label()));
        p.sep();
        p.text(s("Session ", label()), s(String.format(Locale.ROOT, "%,d", st.sessionKills()), text()),
                s(" kills  ", label()), s(ShadowFmt.money(st.sessionCombatValueCents()), HudStyle.COIN));
        return p;
    }

    private static Panel earnings(LiveState st, long now, boolean sample) {
        if (!sample && (!st.has("income") || !st.seenIncome())) {
            return null;
        }
        boolean rec = recent();
        long win = windowMs();
        Panel p = new Panel(HudLayout.SP_EARNINGS);
        p.title = "Money / Hour";
        p.sub = ShadowLive.modeLabel();
        boolean hasRate = sample || st.earningHasRate(now);
        RateTracker.State clock = sample ? RateTracker.State.ACTIVE : st.earningClock().state(now);
        p.dot = dotFor(clock);
        p.right = clock == RateTracker.State.IDLE ? "idle"
                : Fmt.duration(sample ? 312_000 : st.earningClock().activeMs(now));
        double head = sample ? 231_000_000 : st.incomeCentsPerHour(now, rec, win);
        long session = sample ? 41_000_000 : st.sessionIncomeCents();
        p.text(s(hasRate ? ShadowFmt.money(head) : "-", hasRate ? HudStyle.COIN : HudStyle.FAINT), s(" /hour", label()));
        p.text(s("Session ", label()), s(ShadowFmt.signedMoney(session),
                session > 0 ? HudStyle.GAIN : session < 0 ? HudStyle.LOSS : text()));
        List<LiveState.Earning> rows = sample
                ? List.of(new LiveState.Earning("Mined", 200_000_000), new LiveState.Earning("Greed Enchant", 31_000_000))
                : st.incomeRows(now, rec, win);
        if (!rows.isEmpty()) {
            p.sep();
            for (LiveState.Earning e : rows) {
                p.pair(e.label(), label(), ShadowFmt.money(e.centsPerHour()) + "/h",
                        e.centsPerHour() < 0 ? HudStyle.LOSS : text());
            }
        }
        return p;
    }

    private static Panel rankup(LiveState st, long now, boolean sample) {
        RankEta.Result r;
        Msg.Progress pr;
        long bal;
        if (sample) {
            r = new RankEta.Result(RankEta.Kind.ETA, 380_000);
            pr = new Msg.Progress("C", "D", 500_000_000, 3, 0);
            bal = 320_000_000;
        } else {
            if (!st.has("progress") || !st.has("balance") || !st.has("income")) {
                return null;
            }
            r = st.rankEta(now, recent(), windowMs());
            pr = st.progress();
            bal = st.balance() == null ? 0 : st.balance().balanceCents();
            if (r.kind() == RankEta.Kind.HIDDEN || pr == null) {
                return null;
            }
        }
        String next = pr.nextRankDisplay();
        String rank = next.toLowerCase(Locale.ROOT).startsWith("rank") ? next : "Rank " + next;
        Panel p = new Panel(HudLayout.SP_RANKUP);
        p.title = "Rank-up";
        p.sub = ShadowLive.modeLabel();
        if (r.kind() == RankEta.Kind.AFFORDABLE) {
            p.dot = HudStyle.GAIN;
            p.text(s(rank, HudStyle.ACCENT), s("  affordable now", HudStyle.GAIN));
        } else {
            p.text(s(rank, HudStyle.ACCENT), s(" in ~" + Fmt.duration(r.etaMs()), text()));
        }
        long cost = Math.max(1, pr.nextRankCostCents());
        p.bar((double) Math.max(0, bal) / cost, r.kind() == RankEta.Kind.AFFORDABLE ? HudStyle.GAIN : HudStyle.ACCENT);
        p.text(s(ShadowFmt.money(bal), HudStyle.COIN), s(" / " + ShadowFmt.money(pr.nextRankCostCents()), label()));
        return p;
    }

    private static Panel boosts(LiveState st, long now, boolean sample) {
        List<LiveState.BoostView> list = sample
                ? List.of(new LiveState.BoostView("Sell Boost", "x2.0", 245_000, false),
                        new LiveState.BoostView("Skills XP Boost", "+50%", -1, true))
                : st.has("boosts") ? st.boosts(System.currentTimeMillis()) : List.of();
        if (list.isEmpty()) {
            return null;
        }
        Panel p = new Panel(HudLayout.SP_BOOSTS);
        p.title = "Boosts";
        p.right = Integer.toString(list.size());
        for (LiveState.BoostView b : list) {
            String left = b.name() + (b.multiplierText() == null || b.multiplierText().isEmpty() ? "" : " ");
            String time = b.permanent() ? "active"
                    : b.remainingMs() >= 3_600_000 ? Fmt.duration(b.remainingMs()) : HudStyle.countdown(b.remainingMs());
            int timeColor = !b.permanent() && b.remainingMs() < 30_000 ? HudStyle.WARN : HudStyle.DIM;
            p.rows.add(new Row(Row.TEXT, List.of(s(left, text()), s(b.multiplierText() == null ? "" : b.multiplierText(),
                    HudStyle.GAIN)), time, timeColor, 0, 0));
        }
        return p;
    }

    private static Panel contract(LiveState st, long now, boolean sample) {
        Msg.Contract c = sample ? new Msg.Contract(Msg.Contract.HUNTING, "Cave Lurker", 3, 425)
                : st.has("contract") ? st.contract() : null;
        if (c == null || c.state() == Msg.Contract.NONE) {
            return null;
        }
        Panel p = new Panel(HudLayout.SP_CONTRACT);
        p.title = "Contracts";
        String who = c.target() + (c.tier() > 0 ? " " + ShadowFmt.roman(c.tier()) : "");
        int barColor = HudStyle.ACCENT;
        String line;
        int lineColor;
        switch ((int) c.state()) {
            case Msg.Contract.BOSS_UP -> {
                p.right = "BOSS UP";
                p.rightColor = HudStyle.LOSS;
                p.dot = (now / 400) % 2 == 0 ? HudStyle.LOSS : 0xFF8A2020;
                barColor = HudStyle.LOSS;
                line = "Slay the boss!";
                lineColor = HudStyle.LOSS;
            }
            case Msg.Contract.SLAIN -> {
                p.right = "SLAIN";
                p.rightColor = HudStyle.GAIN;
                p.dot = HudStyle.GAIN;
                barColor = HudStyle.GAIN;
                line = "Return to the Executioner to claim";
                lineColor = HudStyle.GAIN;
            }
            case Msg.Contract.FAILED -> {
                p.right = "FAILED";
                p.rightColor = HudStyle.LOSS;
                p.dot = HudStyle.LOSS;
                barColor = HudStyle.FAINT;
                line = "Contract failed";
                lineColor = HudStyle.DIM;
            }
            default -> {
                p.right = ShadowFmt.permille(c.progressPermille());
                long eta = sample ? 70_000 : st.contractEtaMs(now);
                line = eta >= 0 ? "Boss in ~" + Fmt.duration(eta) : "Hunting";
                lineColor = eta >= 0 ? text() : HudStyle.DIM;
            }
        }
        p.text(s(who, 0xFFFF6E6E));
        p.bar(Math.max(0, Math.min(1000, c.progressPermille())) / 1000.0, barColor);
        p.text(s(line, lineColor));
        return p;
    }

    private static Panel skills(LiveState st, long now, boolean sample) {
        LiveState.SkillView v = sample ? new LiveState.SkillView("Mining", 17, 3400, 12000, 1_200, 25 * 60_000L)
                : st.has("skill") ? st.training(now, recent(), windowMs()) : null;
        if (v == null) {
            return null;
        }
        Panel p = new Panel(HudLayout.SP_SKILLS);
        p.title = "Skills";
        p.sub = ShadowLive.modeLabel();
        p.right = v.maxed() ? "MAX" : "Lv " + v.level();
        p.rightColor = v.maxed() ? HudStyle.GAIN : HudStyle.DIM;
        p.text(s(v.displayName() + " " + v.level(), HudStyle.ACCENT));
        p.bar(v.maxed() ? 1 : (double) Math.max(0, v.xpInLevel()) / Math.max(1, v.xpForLevel()),
                v.maxed() ? HudStyle.GAIN : HudStyle.ACTIVE);
        if (!v.maxed()) {
            p.text(s(String.format(Locale.ROOT, "%,d", v.xpInLevel()), text()),
                    s(" / " + String.format(Locale.ROOT, "%,d", v.xpForLevel()) + " XP", label()));
        }
        if (v.xpPerHour() > 0) {
            p.text(s(ShadowFmt.count(v.xpPerHour()), text()), s(" XP/h", label()),
                    s(v.etaMs() >= 0 ? "  next ~" + Fmt.duration(v.etaMs()) : "", HudStyle.DIM));
        }
        return p;
    }

    private static Panel profile(LiveState st, long now, boolean sample) {
        ProfileCard.Card c = sample
                ? ProfileCard.of(new Msg.Response(0, 0, 0, "Steve", "", true, List.of(
                        new Msg.Row("rank", "Rank", "C (Prestige 3)"),
                        new Msg.Row("skills", "Mining", "17"),
                        new Msg.Row("playtime", "Playtime", "12h 30m"))))
                : ShadowLive.card();
        if (c == null) {
            return null;
        }
        Panel p = new Panel(HudLayout.SP_PROFILE);
        p.minW = 140;
        p.title = c.name();
        p.right = c.online() ? "online" : "offline";
        p.rightColor = c.online() ? HudStyle.GAIN : HudStyle.DIM;
        p.dot = c.online() ? HudStyle.GAIN : HudStyle.IDLE;
        if (c.sections().isEmpty()) {
            p.text(s("Nothing shared publicly.", HudStyle.DIM));
        }
        boolean first = true;
        for (ProfileCard.Section sec : c.sections()) {
            if (!first) {
                p.sep();
            }
            first = false;
            p.head(sec.title());
            for (ProfileCard.Line l : sec.lines()) {
                p.pair(l.label(), label(), l.value(), text());
            }
        }
        return p;
    }

    // ------------------------------------------------------------ renderer

    private static void draw(GuiGraphicsExtractor g, Font font, Panel p) {
        int lh = font.lineHeight + 2;
        int maxRow = 220;
        int headerW = 6 + font.width(p.title) + (p.sub.isEmpty() ? 0 : 4 + font.width(p.sub))
                + (p.right.isEmpty() ? 0 : 10 + font.width(p.right));
        int innerW = Math.max(p.minW, headerW);
        for (Row r : p.rows) {
            if (r.kind() == Row.TEXT || r.kind() == Row.HEAD) {
                int w = 0;
                for (Seg sg : r.left()) {
                    w += font.width(sg.text());
                }
                if (!r.right().isEmpty()) {
                    w += 8 + font.width(r.right());
                }
                innerW = Math.max(innerW, Math.min(maxRow, w));
            }
        }
        int h = HudStyle.PAD + lh + 3;
        for (Row r : p.rows) {
            h += switch (r.kind()) {
                case Row.BAR -> 6;
                case Row.SEP -> 4;
                default -> lh;
            };
        }
        h += HudStyle.PAD - 2;
        int w = innerW + HudStyle.PAD * 2;

        float sc = HudLayout.scale(p.id);
        HudLayout.reportSize(p.id, w, h);
        int[] at = HudLayout.place(p.id, g.guiWidth(), g.guiHeight());

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate((float) at[0], (float) at[1]);
            pose.scale(sc, sc);
            HudStyle.panel(g, 0, 0, w, h);
            int x = HudStyle.PAD;
            int y = HudStyle.PAD;
            HudStyle.dot(g, x, y + 2, p.dot);
            String title = HudStyle.ellipsize(font, p.title, innerW - 6 - (p.right.isEmpty() ? 0 : 10 + font.width(p.right)));
            g.text(font, title, x + 6, y, HudStyle.ACCENT);
            int tx = x + 6 + font.width(title);
            if (!p.sub.isEmpty() && tx + 4 + font.width(p.sub) <= w - HudStyle.PAD - (p.right.isEmpty() ? 0
                    : 10 + font.width(p.right))) {
                g.text(font, p.sub, tx + 4, y, HudStyle.DIM);
            }
            if (!p.right.isEmpty()) {
                g.text(font, p.right, w - HudStyle.PAD - font.width(p.right), y, p.rightColor);
            }
            y += lh;
            HudStyle.rule(g, x, y, innerW);
            y += 3;
            for (Row r : p.rows) {
                switch (r.kind()) {
                    case Row.BAR -> {
                        HudStyle.bar(g, x, y + 1, innerW, 3, r.frac(), r.barColor());
                        y += 6;
                    }
                    case Row.SEP -> {
                        g.fill(x, y + 1, x + innerW, y + 2, 0x30FFFFFF);
                        y += 4;
                    }
                    default -> {
                        int rightW = r.right().isEmpty() ? 0 : font.width(r.right()) + 8;
                        int cx = x;
                        int limit = x + innerW - rightW;
                        for (Seg sg : r.left()) {
                            String t = sg.text();
                            if (cx + font.width(t) > limit) {
                                t = HudStyle.ellipsize(font, t, Math.max(0, limit - cx));
                            }
                            g.text(font, t, cx, y, sg.color());
                            cx += font.width(t);
                        }
                        if (!r.right().isEmpty()) {
                            g.text(font, r.right(), x + innerW - font.width(r.right()), y, r.rightColor());
                        }
                        y += lh;
                    }
                }
            }
        } finally {
            pose.popMatrix();
        }
    }
}
