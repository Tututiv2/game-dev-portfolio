package com.auraqol.feature;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.auraqol.AuraQoL;
import com.auraqol.compat.Mc;
import com.auraqol.skyblock.BreakCounter;
import com.auraqol.skyblock.RollingCounter;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.joml.Matrix3x2fStack;

/**
 * Blocks broken per second: a rolling one-second count plus the session peak.
 *
 * <p><b>Two detectors, because SkyBlock breaks blocks two ways.</b>
 * <ul>
 *   <li><b>Client-side breaks</b> - crops in the Garden, anything that breaks
 *       instantly. The client destroys the block itself and Fabric's
 *       {@code ClientPlayerBlockBreakEvents.AFTER} fires. A poll would miss
 *       these: an instant break happens inside the same tick the crosshair
 *       first touches the block, so by END_CLIENT_TICK there is nothing left
 *       to watch.</li>
 *   <li><b>Server-side breaks</b> - the Dwarven Mines, Crystal Hollows and
 *       every other mining island. Hypixel gives you mining fatigue and does
 *       the breaking itself, so the client never finishes a break and the
 *       Fabric event never fires; the block simply turns to bedrock or air
 *       when the server says so. So while attack is held, every block under
 *       the crosshair is remembered with its state, and a block that turns
 *       into a DIFFERENT block (not just a different state - redstone ore
 *       lights up when hit) is counted as broken.</li>
 * </ul>
 * <p><b>Mining Spread.</b> Spread blocks break server-side at the same
 * moment as the block you mined, a block or a few away, and the crosshair
 * never touches them. {@code mixin/BlockChangeMixin} hands every block the
 * server changes to {@link #onServerBlock}; whether a change is yours is
 * decided by the pure, unit-tested {@link BreakCounter} (near your own break
 * in space and time, solid to air/bedrock, never a regeneration, each
 * position once). The crosshair poll below stays as the fallback detector
 * for the mined block itself and shares the counter's de-duplication.
 *
 * <p>A position counted by the event is dropped from the watch list in the same
 * call, so one block is never counted twice. Watched positions expire 1.5 s
 * after the crosshair leaves them, so a mithril vein regenerating minutes
 * later, or a block someone else breaks after you walked off, does not count.
 *
 * <p>Only breaks made in the world count: nothing is recorded while a screen
 * is open.
 */
public final class BlocksPerSecond {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("auraqol", "blocks_per_second");

    /** 512 is 25x the 20/s a client can break, so the ring never wraps inside a window. */
    private static final RollingCounter COUNTER = new RollingCounter(1000, 512);

    private static final long WATCH_MS = 1500;
    private static final int WATCH_MAX = 64;

    private record Watched(BlockState state, long lastSeen) {
    }

    private static final Map<BlockPos, Watched> WATCH = new HashMap<>();
    private static final BreakCounter BREAKS = BreakCounter.standard();
    private static ClientLevel watchLevel;
    private static boolean loggedFailure;

    private BlocksPerSecond() {
    }

    public static void register() {
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            try {
                SacksHud.broke(id(state));
                WATCH.remove(pos);
                int n = BREAKS.clientBreak(now(), pos.getX(), pos.getY(), pos.getZ(), kind(state, level, pos));
                recordBreaks(n);
            } catch (Throwable t) {
                fail(t);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                poll(mc);
            } catch (Throwable t) {
                fail(t);
            }
        });
        HudElementRegistry.addLast(ID, (g, delta) -> {
            try {
                draw(g);
            } catch (Throwable t) {
                fail(t);
            }
        });
    }

    /** Session peak back to zero (the /bps reset command). */
    public static void reset() {
        COUNTER.reset();
        BREAKS.clear();
    }

    /** Blocks counted because Mining Spread (or a multi-break) took them, this session. */
    public static long spreadTotal() {
        return BREAKS.spreadTotal();
    }

    /**
     * Every block state the server sets in the client world (single block
     * updates and section updates alike), BEFORE it is applied - called from
     * {@code BlockChangeMixin} on the client thread.
     */
    public static void onServerBlock(ClientLevel level, BlockPos pos, BlockState next) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || level != mc.level || mc.player == null || !HudConfig.get().bpsEnabled) {
                return;
            }
            BlockState prev = level.getBlockState(pos);
            if (prev == next) {
                return;
            }
            long t = now();
            boolean attack = Mc.screen(mc) == null && mc.options.keyAttack.isDown();
            if (attack && mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
                // The crosshair can still be on the block in the very tick it
                // breaks, before the end-of-tick poll ever aimed at it.
                BlockPos a = bhr.getBlockPos();
                BREAKS.aim(t, a.getX(), a.getY(), a.getZ());
            }
            int n = BREAKS.serverChange(t, pos.getX(), pos.getY(), pos.getZ(),
                    id(prev), kind(prev, level, pos), id(next), kind(next, level, pos), attack);
            if (n > 0) {
                WATCH.remove(pos);
                recordBreaks(n);
                SacksHud.broke(id(prev));
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static String id(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }

    /** What the counter needs to know about a state. Plants and liquids are never spread. */
    static BreakCounter.Kind kind(BlockState s, ClientLevel level, BlockPos pos) {
        if (s == null || s.isAir()) {
            return BreakCounter.Kind.AIR;
        }
        if (s.getDestroySpeed(level, pos) < 0) {
            return BreakCounter.Kind.UNBREAKABLE;
        }
        Block b = s.getBlock();
        if (b instanceof LiquidBlock || s.canBeReplaced()
                || b instanceof VegetationBlock || b instanceof CactusBlock || b instanceof SugarCaneBlock
                || b instanceof CocoaBlock || b instanceof BambooStalkBlock) {
            return BreakCounter.Kind.OTHER;
        }
        return BreakCounter.Kind.SOLID;
    }

    public static int current() {
        return COUNTER.count(now());
    }

    public static int peak() {
        return COUNTER.peak();
    }

    public static long total() {
        return COUNTER.total();
    }

    private static void recordBreaks(int n) {
        if (n <= 0) {
            return;
        }
        long t = now();
        for (int i = 0; i < n; i++) {
            COUNTER.add(t);
        }
        COUNTER.sample(t);
        MoneyTracker.activity();
    }

    private static void poll(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            WATCH.clear();
            watchLevel = null;
            return;
        }
        if (level != watchLevel) {
            WATCH.clear();
            BREAKS.forgetWorld();
            watchLevel = level;
        }
        long t = now();
        COUNTER.sample(t);

        boolean inWorld = Mc.screen(mc) == null;
        if (inWorld && mc.options.keyAttack.isDown()
                && mc.hitResult instanceof BlockHitResult bhr
                && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bhr.getBlockPos().immutable();
            BREAKS.aim(t, pos.getX(), pos.getY(), pos.getZ());
            Watched w = WATCH.get(pos);
            BlockState s = level.getBlockState(pos);
            if (w == null) {
                // Unbreakable blocks (bedrock: what a mined mithril vein
                // turns into) are never watched, or holding attack on one
                // until it regenerates would count the regeneration.
                if (!s.isAir() && s.getDestroySpeed(level, pos) >= 0 && WATCH.size() < WATCH_MAX) {
                    WATCH.put(pos, new Watched(s, t));
                }
            } else {
                WATCH.put(pos, new Watched(w.state(), t));
            }
        }

        Iterator<Map.Entry<BlockPos, Watched>> it = WATCH.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Watched> e = it.next();
            Watched w = e.getValue();
            BlockState now = level.getBlockState(e.getKey());
            if (now.getBlock() != w.state().getBlock()) {
                it.remove();
                if (inWorld) {
                    // Usually already counted by the block-update hook; the
                    // counter's de-duplication makes this a no-op then.
                    BlockPos p = e.getKey();
                    recordBreaks(BREAKS.primary(t, p.getX(), p.getY(), p.getZ()));
                }
            } else if (t - w.lastSeen() > WATCH_MS) {
                it.remove();
            }
        }
    }

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        HudConfig cfg = HudConfig.get();
        if (mc == null || mc.player == null || !cfg.bpsEnabled) {
            return;
        }
        Font font = mc.font;
        long t = now();
        int cur = COUNTER.sample(t);
        int peak = COUNTER.peak();

        String num = Integer.toString(cur);
        String unit = " blocks/s";
        String peakTxt = "  peak " + peak;
        int textW = font.width(num) + font.width(unit) + font.width(peakTxt);
        int w = textW + HudStyle.PAD * 2;
        int h = font.lineHeight + 5;

        float sc = HudLayout.scale(HudLayout.BPS);
        HudLayout.reportSize(HudLayout.BPS, w, h);
        int[] p = HudLayout.place(HudLayout.BPS, g.guiWidth(), g.guiHeight());

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate((float) p[0], (float) p[1]);
            pose.scale(sc, sc);
            HudStyle.panel(g, 0, 0, w, h);
            int x = HudStyle.PAD;
            int y = 3;
            // White when breaking, dimmed at rest, so a glance tells you
            // whether you are actually mining without reading the digit.
            g.text(font, num, x, y, cur > 0 ? cfg.textColor : HudStyle.IDLE);
            x += font.width(num);
            g.text(font, unit, x, y, cfg.labelColor);
            x += font.width(unit);
            g.text(font, peakTxt, x, y, HudStyle.DIM);
        } finally {
            pose.popMatrix();
        }
    }

    private static void fail(Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            AuraQoL.LOG.warn("[auraqol] blocks/s failed once (further errors silenced)", t);
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
