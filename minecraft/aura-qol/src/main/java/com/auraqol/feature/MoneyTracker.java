package com.auraqol.feature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.auraqol.AuraQoL;
import com.auraqol.compat.Mc;
import com.auraqol.skyblock.Fmt;
import com.auraqol.skyblock.IncomeEngine;
import com.auraqol.skyblock.IncomeLedger;
import com.auraqol.skyblock.IncomeSource;
import com.auraqol.skyblock.MoneyChat;
import com.auraqol.skyblock.MoneyLog;
import com.auraqol.skyblock.MoneyRouter;
import com.auraqol.skyblock.PriceData;
import com.auraqol.skyblock.PriceService;
import com.auraqol.skyblock.Pricing;
import com.auraqol.skyblock.RateTracker;
import com.auraqol.skyblock.SbItem;
import com.auraqol.skyblock.SkyblockText;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;

/**
 * Coins per hour - on SkyBlock - as a session average or over the last N
 * minutes of active play, with a per-source breakdown.
 *
 * <p>All the rules about what is and is not income live in the pure
 * {@link IncomeEngine} (and its tests); the chat patterns in {@link MoneyChat}.
 * This class only observes the game and feeds them:
 * <ul>
 *   <li>the sidebar purse, twice a second;</li>
 *   <li>an inventory snapshot keyed and valued by {@link Pricing}, twice a second;</li>
 *   <li>whether a container menu (anything but your own inventory) is open,
 *       every tick - menus are where buying, selling and claiming happen;</li>
 *   <li>chat: trade lines, deliveries (bazaar claims, shop buys, items moved
 *       out of sacks), drops (RARE DROP!, pest loot, ...), and the [Sacks]
 *       summary's hover breakdown. Both the normal and the CANCELLED chat
 *       events are listened to, because SkyHanni and Skyblocker can hide
 *       these messages, and a hidden drop is still income;</li>
 *   <li>activity - moving, turning, breaking blocks - so idle time is trimmed;</li>
 *   <li>what you are collecting (the Sacks panel's crop/ore family), so a sack
 *       gain of it right after a menu is still counted.</li>
 * </ul>
 * {@code /money log} writes every decision to {@code config/auraqol-money-log.txt}.
 */
public final class MoneyTracker {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("auraqol", "money_tracker");
    /** What the player is doing (Mining/Farming/Fishing/Foraging/Combat), from the action bar's skill XP. */
    private static final com.auraqol.skyblock.SkillActivity ACTIVITY = new com.auraqol.skyblock.SkillActivity();

    /** Hold-off after a menu closes or a trade line: the sidebar purse redraws ~1 s late. */
    private static final long GRACE_MS = 3000;
    /** A [Sacks] summary covers the last 30 s and arrives up to ~30 s later. */
    private static final long PENDING_MS = 90_000;
    /** Below this much active time the rate is not shown - one sack line would extrapolate to billions. */
    private static final long MIN_RATE_MS = 15_000;
    /** The log rotates past this. */
    private static final long LOG_CAP = 2L * 1024 * 1024;

    private static IncomeEngine engine = newEngine();
    private static MoneyLog log;

    private static ClientLevel lastLevel;
    private static boolean wasSkyblock;
    private static Vec3 lastPos;
    private static float lastYaw;
    private static float lastPitch;
    private static int tick;
    private static boolean loggedFailure;

    private MoneyTracker() {
    }

    private static IncomeEngine newEngine() {
        IncomeEngine e = new IncomeEngine(new RateTracker(HudConfig.get().moneyIdleSeconds * 1000L), GRACE_MS, PENDING_MS);
        e.setListener(MoneyTracker::logEvent);
        return e;
    }

    public static void register() {
        Skyblock.register();
        engine = newEngine();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                onTick(mc);
            } catch (Throwable t) {
                fail(t);
            }
        });
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> safeChat(msg, overlay));
        ClientReceiveMessageEvents.GAME_CANCELED.register((msg, overlay) -> safeChat(msg, overlay));
        HudElementRegistry.addLast(ID, (g, delta) -> {
            try {
                draw(g);
            } catch (Throwable t) {
                fail(t);
            }
        });
    }

    // ------------------------------------------------------------ log

    private static MoneyLog log() {
        if (log == null) {
            log = new MoneyLog(FabricLoader.getInstance().getConfigDir().resolve("auraqol-money-log.txt"), LOG_CAP);
        }
        return log;
    }

    private static void logEvent(long now, String channel, String what, double value, boolean counted, String rule) {
        if (!HudConfig.get().moneyLog) {
            return;
        }
        log().add(MoneyLog.line(System.currentTimeMillis(), channel, what, value, counted, rule));
    }

    /** /money log: flip, and say where it goes. */
    public static boolean toggleLog() {
        HudConfig c = HudConfig.get();
        c.moneyLog = !c.moneyLog;
        c.save();
        if (c.moneyLog) {
            log().add("---- logging started " + java.time.LocalDateTime.now().withNano(0)
                    + "  (mode " + (c.moneyRecent ? "last " + c.moneyWindowMinutes + "m" : "session") + ")");
        } else {
            log().add("---- logging stopped " + java.time.LocalDateTime.now().withNano(0));
        }
        log().flush();
        return c.moneyLog;
    }

    public static String logPath() {
        return log().file().toString();
    }

    // ------------------------------------------------------------ commands

    public static void reset() {
        engine = newEngine();
        lastLevel = null;
    }

    public static boolean togglePause() {
        long now = now();
        RateTracker r = engine.rate();
        if (r.paused()) {
            r.resume(now);
            // Anything that changed while paused is not this session's.
            engine.rebaseline(now);
            return false;
        }
        r.pause(now);
        return true;
    }

    public static boolean paused() {
        return engine.rate().paused();
    }

    private static long windowMs() {
        return HudConfig.get().moneyWindowMinutes * 60_000L;
    }

    /** "last 10m" or "session avg". */
    static String modeLabel() {
        HudConfig c = HudConfig.get();
        return c.moneyRecent ? "last " + c.moneyWindowMinutes + "m" : "session avg";
    }

    private static double perHour(long now) {
        RateTracker r = engine.rate();
        return HudConfig.get().moneyRecent ? engine.recentPerHour(now, windowMs(), MIN_RATE_MS)
                : r.perHour(now, MIN_RATE_MS);
    }

    /** Coins per hour per source in the current mode; they add up to {@link #perHour}. */
    private static double[] perHourBySource(long now) {
        RateTracker r = engine.rate();
        long active = r.activeMs(now);
        double[] out = HudConfig.get().moneyRecent ? engine.recentBySource(now, windowMs()) : engine.sessionBySource();
        long span = HudConfig.get().moneyRecent ? IncomeLedger.span(active, windowMs()) : active;
        for (int i = 0; i < out.length; i++) {
            out[i] = active < MIN_RATE_MS || span <= 0 ? 0 : out[i] * 3_600_000.0 / span;
        }
        return out;
    }

    /** Lines for /money. */
    public static List<String> status() {
        long now = now();
        RateTracker r = engine.rate();
        HudConfig c = HudConfig.get();
        List<String> out = new ArrayList<>();
        out.add("Session " + Fmt.signedCompact(r.total()) + " over " + Fmt.duration(r.activeMs(now))
                + " active (" + stateWord(r.state(now)) + ")");
        if (r.activeMs(now) >= MIN_RATE_MS) {
            out.add("  " + Fmt.compact(perHour(now)) + " /hour (" + modeLabel() + ")   session avg "
                    + Fmt.compact(r.perHour(now, MIN_RATE_MS)) + " /hour");
        }
        out.add(c.moneyRecent
                ? "  Rate = income in the last " + c.moneyWindowMinutes + " min of ACTIVE play / that time (AFK gaps cut out)."
                : "  Rate = session income / active session time (AFK gaps cut out).");
        double[] sess = engine.sessionBySource();
        StringBuilder sb = new StringBuilder("  session:");
        for (IncomeSource s : IncomeSource.values()) {
            sb.append("  ").append(s.shortLabel()).append(' ').append(Fmt.signedCompact(sess[s.ordinal()]));
        }
        out.add(sb.toString());
        Map<String, Integer> up = engine.unpriced();
        if (!up.isEmpty()) {
            out.add("  " + up.size() + " unpriced item kind(s) seen, not counted - /money unpriced");
        }
        out.add("  Counted: purse coins, items gained (valued at bazaar insta-sell, else lowest BIN, else NPC),"
                + " [Sacks] gains, RARE/PET/CROP drops and pest loot from chat.");
        out.add("  Not counted: anything in a menu (+3s), bazaar/AH claims and buys, shop buys, items moved out of"
                + " sacks, sack gains right after a menu unless it is what you are collecting.");
        if (!Skyblock.inSkyblock()) {
            out.add("  Not on SkyBlock right now - nothing is being tracked.");
        }
        return out;
    }

    public static Map<String, Integer> unpriced() {
        return engine.unpriced();
    }

    /** Called by the blocks/s detector: breaking blocks is activity. */
    static void activity() {
        if (Skyblock.inSkyblock()) {
            engine.rate().activity(now());
        }
    }

    // ------------------------------------------------------------ observe

    private static void onTick(Minecraft mc) {
        if (tick % 40 == 0 && log != null) {
            log.flush();
        }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            lastLevel = null;
            return;
        }
        long now = now();
        HudConfig cfg = HudConfig.get();
        if (!cfg.moneyEnabled) {
            tick++;
            return;
        }
        if (level != lastLevel) {
            // New island, new server, or a respawn into a new world object:
            // both baselines are about somewhere else now.
            engine.rebaseline(now);
            lastLevel = level;
        }
        boolean sky = Skyblock.inSkyblock();
        if (sky != wasSkyblock) {
            engine.rebaseline(now);
            wasSkyblock = sky;
        }
        if (!sky) {
            tick++;
            return;
        }

        Screen screen = Mc.screen(mc);
        boolean menu = screen instanceof AbstractContainerScreen<?>
                && !(screen instanceof InventoryScreen)
                && !(screen instanceof CreativeModeInventoryScreen);
        engine.menu(now, menu, menu && SkyblockText.isSackMenu(screen.getTitle().getString()));

        Vec3 pos = player.position();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        if (lastPos != null && (pos.distanceToSqr(lastPos) > 0.0004
                || Math.abs(yaw - lastYaw) > 0.5f || Math.abs(pitch - lastPitch) > 0.5f)) {
            engine.rate().activity(now);
        }
        lastPos = pos;
        lastYaw = yaw;
        lastPitch = pitch;

        if (++tick % 10 != 0) {
            return;
        }
        engine.setCollecting(SacksHud.collectingIds());
        engine.setKnownSackItems(SacksHud.knownIds());
        engine.purse(now, SkyblockText.purse(Skyblock.sidebar()));
        if (cfg.moneyCountItems) {
            engine.inventory(now, snapshot(player, PriceService.data()));
        } else {
            engine.forgetInventory();
        }
        if (tick % 200 == 0) {
            engine.prune(now);
        }
    }

    private static Map<String, IncomeEngine.Holding> snapshot(LocalPlayer player, PriceData data) {
        Inventory inv = player.getInventory();
        Map<String, IncomeEngine.Holding> out = new HashMap<>();
        Map<String, Double> unitCache = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            SbItem it = SkyblockItems.read(stack);
            if (it == null) {
                continue;
            }
            String key = Pricing.valueKey(it);
            if (key == null) {
                continue;
            }
            double unit = unitCache.computeIfAbsent(key, k -> Pricing.unitValue(data, it));
            IncomeEngine.Holding h = out.get(key);
            out.put(key, new IncomeEngine.Holding((h == null ? 0 : h.count()) + stack.getCount(), unit));
        }
        return out;
    }

    private static void safeChat(Component msg, boolean overlay) {
        try {
            onChat(msg, overlay);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void onChat(Component msg, boolean overlay) {
        if (overlay && msg != null && Skyblock.inSkyblock()) {
            // The action bar: "+23.4 Farming (...)" says what the player is doing; the panel title shows it.
            ACTIVITY.observe(msg.getString(), now());
            return;
        }
        if (overlay || msg == null || !HudConfig.get().moneyEnabled || !Skyblock.inSkyblock()) {
            return;
        }
        long now = now();
        String raw = msg.getString();
        PriceData data = PriceService.data();
        boolean items = HudConfig.get().moneyCountItems;
        String legacy = raw.contains("PET DROP!") ? legacy(msg) : null;
        for (String line : MoneyChat.lines(raw)) {
            MoneyRouter.line(engine, data, now, line, legacy, items);
        }
        if (items && SkyblockText.isSackSummary(raw)) {
            List<String> hovers = new ArrayList<>();
            Skyblock.hoverTexts(msg, hovers);
            MoneyRouter.sackSummary(engine, data, now, hovers);
        }
    }

    /**
     * The message as legacy "§6..." text, rebuilt from each styled run's
     * colour (only the sixteen named colours map). Used for the pet rarity.
     */
    private static String legacy(Component msg) {
        StringBuilder sb = new StringBuilder();
        msg.visit((Style style, String text) -> {
            TextColor c = style.getColor();
            if (c != null) {
                char code = MoneyChat.legacyCode(c.getValue());
                if (code != 0) {
                    sb.append('§').append(code);
                }
            }
            sb.append(text);
            return Optional.empty();
        }, Style.EMPTY);
        return sb.toString();
    }

    // ------------------------------------------------------------ draw

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        HudConfig cfg = HudConfig.get();
        if (mc == null || mc.player == null || !cfg.moneyEnabled) {
            return;
        }
        if (ShadowLive.active()) {
            return; // Shadow Prison: its Earnings panel has the server's real numbers
        }
        Font font = mc.font;
        long now = now();
        RateTracker r = engine.rate();
        RateTracker.State st = r.state(now);
        boolean sky = Skyblock.inSkyblock();
        boolean hasRate = r.activeMs(now) >= MIN_RATE_MS;

        String activity = ACTIVITY.current(now);
        String title = activity == null ? "Money" : "Money · " + activity;
        String mode = modeLabel();
        String right;
        int rightColor;
        int dotColor;
        if (st == RateTracker.State.PAUSED) {
            right = "PAUSED";
            rightColor = HudStyle.WARN;
            dotColor = HudStyle.WARN;
        } else if (!sky) {
            right = "off SkyBlock";
            rightColor = HudStyle.FAINT;
            dotColor = HudStyle.FAINT;
        } else if (st == RateTracker.State.IDLE) {
            right = "idle  " + Fmt.duration(r.activeMs(now));
            rightColor = HudStyle.DIM;
            dotColor = HudStyle.IDLE;
        } else if (st == RateTracker.State.WAITING) {
            right = "ready";
            rightColor = HudStyle.DIM;
            dotColor = HudStyle.IDLE;
        } else {
            right = Fmt.duration(r.activeMs(now));
            rightColor = HudStyle.DIM;
            dotColor = HudStyle.GAIN;
        }

        double ph = perHour(now);
        String perHour = hasRate ? Fmt.compact(ph) : "-";
        String perMin = hasRate ? Fmt.compact(ph / 60.0) : "-";
        String total = Fmt.signedCompact(r.total());
        double recent = engine.recentGain(now, 2500);
        String recentTxt = recent == 0 ? "" : "  " + Fmt.signedCompact(recent);

        // Breakdown: two per row, only sources that moved.
        List<String[]> cells = new ArrayList<>();
        if (cfg.moneyBreakdown && hasRate) {
            double[] by = perHourBySource(now);
            for (IncomeSource s : IncomeSource.values()) {
                double v = by[s.ordinal()];
                if (Math.abs(v) >= 0.5) {
                    cells.add(new String[]{s.shortLabel(), Fmt.compact(v), v < 0 ? "-" : "+"});
                }
            }
        }

        int lh = font.lineHeight + 2;
        int headerW = 6 + font.width(title) + 4 + font.width(mode) + 10 + font.width(right);
        int rowW = Math.max(font.width(perHour + " /hour"), font.width(perMin + " /min"));
        int sessW = font.width("Session ") + font.width(total) + font.width(recentTxt);
        int cellW = 0;
        for (String[] c : cells) {
            cellW = Math.max(cellW, font.width(c[0] + " ") + font.width(c[1]));
        }
        int breakW = cells.isEmpty() ? 0 : cellW * 2 + 10;
        int innerW = Math.max(96, Math.max(headerW, Math.max(rowW, Math.max(sessW, breakW))));
        int breakRows = (cells.size() + 1) / 2;
        int w = innerW + HudStyle.PAD * 2;
        int h = HudStyle.PAD + lh + 3 + lh * 3 + (breakRows > 0 ? 3 + breakRows * lh : 0) + HudStyle.PAD - 2;

        float sc = HudLayout.scale(HudLayout.MONEY);
        HudLayout.reportSize(HudLayout.MONEY, w, h);
        int[] p = HudLayout.place(HudLayout.MONEY, g.guiWidth(), g.guiHeight());

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate((float) p[0], (float) p[1]);
            pose.scale(sc, sc);
            HudStyle.panel(g, 0, 0, w, h);
            int x = HudStyle.PAD;
            int y = HudStyle.PAD;

            HudStyle.dot(g, x, y + 2, dotColor);
            g.text(font, title, x + 6, y, HudStyle.ACCENT);
            g.text(font, mode, x + 6 + font.width(title) + 4, y, HudStyle.DIM);
            g.text(font, right, w - HudStyle.PAD - font.width(right), y, rightColor);
            y += lh;
            HudStyle.rule(g, x, y, innerW);
            y += 3;

            int valueColor = hasRate && sky ? HudStyle.COIN : HudStyle.FAINT;
            g.text(font, perHour, x, y, valueColor);
            g.text(font, " /hour", x + font.width(perHour), y, cfg.labelColor);
            y += lh;
            g.text(font, perMin, x, y, valueColor);
            g.text(font, " /min", x + font.width(perMin), y, cfg.labelColor);
            y += lh;
            g.text(font, "Session ", x, y, cfg.labelColor);
            int tx = x + font.width("Session ");
            int totalColor = r.total() > 0 ? HudStyle.GAIN : r.total() < 0 ? HudStyle.LOSS : cfg.textColor;
            g.text(font, total, tx, y, totalColor);
            if (!recentTxt.isEmpty()) {
                g.text(font, recentTxt, tx + font.width(total), y, recent > 0 ? 0xFF8CE68C : 0xFFE68C8C);
            }
            if (breakRows > 0) {
                y += lh;
                g.fill(x, y, x + innerW, y + 1, 0x30FFFFFF);
                y += 3;
                int colW = (innerW - 10) / 2;
                for (int i = 0; i < cells.size(); i++) {
                    String[] c = cells.get(i);
                    int cx = x + (i % 2 == 0 ? 0 : colW + 10);
                    int cy = y + (i / 2) * lh;
                    g.text(font, c[0], cx, cy, cfg.labelColor);
                    g.text(font, c[1], cx + colW - font.width(c[1]), cy,
                            "-".equals(c[2]) ? HudStyle.LOSS : cfg.textColor);
                }
            }
        } finally {
            pose.popMatrix();
        }
    }

    private static String stateWord(RateTracker.State s) {
        return switch (s) {
            case ACTIVE -> "running";
            case IDLE -> "idle, clock stopped";
            case PAUSED -> "paused";
            case WAITING -> "waiting for activity";
        };
    }

    private static void fail(Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            AuraQoL.LOG.warn("[auraqol] money tracker failed once (further errors silenced)", t);
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
