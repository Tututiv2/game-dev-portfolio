package com.auraqol.feature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.auraqol.AuraQoL;
import com.auraqol.compat.Mc;
import com.auraqol.skyblock.Fmt;
import com.auraqol.skyblock.PriceData;
import com.auraqol.skyblock.PriceService;
import com.auraqol.skyblock.SackFamilies;
import com.auraqol.skyblock.SackStore;
import com.auraqol.skyblock.SackText;
import com.auraqol.skyblock.SbItem;
import com.auraqol.skyblock.SkyblockText;
import com.auraqol.skyblock.TabWidgets;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

/**
 * Sacks panel: how much of the crop / ore you are collecting right now is in
 * your sacks (raw item, its enchanted forms, and the total in raw items), and
 * how fast it is growing.
 *
 * <p>The client cannot read sacks, so counts come from two places (rules in
 * the pure {@link SackStore}): opening a sack menu (each item's lore says
 * "Stored: X/Y" - read while the menu is open), and then the [Sacks] chat
 * summaries, added and removed. Counts are saved to
 * {@code config/auraqol-sacks.txt} so a restart does not forget them.
 *
 * <p>What "you are collecting" means, best first: the crop of the farming
 * tool in your hand; else the block you last broke (last 2 min); else the
 * family that gained most in the last [Sacks] summary (last 10 min); else the
 * last answer. The same family tells the money tracker which sack gains are
 * "what you are collecting" (see {@code IncomeEngine}).
 */
public final class SacksHud {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("auraqol", "sacks");

    private static SackStore store = new SackStore();
    private static SackFamilies.Family current;
    private static SackFamilies.Family blockFamily;
    private static long blockAt = -1;
    private static SackFamilies.Family sackFamily;
    private static long sackAt = -1;
    private static int tick;
    private static long lastSave;
    private static boolean loggedFailure;

    private SacksHud() {
    }

    public static void register() {
        Skyblock.register();
        load();
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

    // ------------------------------------------------------------ API

    /** The ids of the family being collected right now (empty if none). Read by the money tracker. */
    static Set<String> collectingIds() {
        SackFamilies.Family f = current;
        return f == null ? Set.of() : Set.copyOf(f.ids());
    }

    /** Item ids with a known sack count. Read by the money tracker: those items go to sacks. */
    static Set<String> knownIds() {
        return store.knownIds();
    }

        /** A block you broke (Garden client break, or a counted server-side mining break). */
    static void broke(String blockId) {
        try {
            SackFamilies.Family f = SackFamilies.forBlock(blockId, miningIsland());
            if (f != null) {
                blockFamily = f;
                blockAt = System.currentTimeMillis();
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Lines for /aq sacks. */
    static List<String> status() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        out.add("Known sack items: " + store.knownCount() + " (open /sacks or a sack menu to sync).");
        SackFamilies.Family f = current;
        if (f == null) {
            out.add("  Not collecting anything recognisable yet - hold a farming tool, or farm/mine for a bit.");
            return out;
        }
        SackStore.View v = store.view(f, now, windowMs());
        out.add("  Collecting: " + f.name() + (v.rawTotal() == null ? "  (not synced)" : "  total "
                + Fmt.coins(v.rawTotal()) + " " + f.name().toLowerCase(Locale.ROOT) + (v.partial() ? " (some forms unknown)" : "")));
        for (SackStore.Row r : v.rows()) {
            out.add("    " + name(r.id()) + ": " + (r.count() == null ? "?" : Fmt.coins(r.count()))
                    + (r.mult() > 1 ? "  (x" + Fmt.coins(r.mult()) + ")" : ""));
        }
        return out;
    }

    static void clear() {
        store.clear();
        save();
    }

    // ------------------------------------------------------------ observe

    private static void onTick(Minecraft mc) {
        tick++;
        if (mc.player == null || mc.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (tick % 10 == 0) {
            current = decide(mc, now);
        }
        Screen screen = Mc.screen(mc);
        if (tick % 10 == 0 && screen instanceof AbstractContainerScreen<?> acs
                && Skyblock.inSkyblock() && SackText.isSackMenu(screen.getTitle().getString())) {
            readMenu(acs, now);
        }
        if (store.dirty() && now - lastSave > 10_000) {
            save();
        }
    }

    private static SackFamilies.Family decide(Minecraft mc, long now) {
        ItemStack held = mc.player.getMainHandItem();
        SbItem it = SkyblockItems.read(held);
        SackFamilies.Family tool = it == null ? null : SackFamilies.forTool(it.id());
        if (tool != null) {
            return tool;
        }
        if (blockFamily != null && now - blockAt < 120_000) {
            return blockFamily;
        }
        if (sackFamily != null && now - sackAt < 600_000) {
            return sackFamily;
        }
        return current;
    }

    private static void readMenu(AbstractContainerScreen<?> screen, long now) {
        PriceData data = PriceService.data();
        int synced = 0;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = Fmt.strip(stack.getHoverName().getString());
            List<String> lore = AbilityHud.lore(stack);
            String gem = SackText.gemType(name);
            if (gem != null) {
                for (Map.Entry<String, Long> e : SackText.gemCounts(lore).entrySet()) {
                    store.sync(e.getKey() + "_" + gem + "_GEM", e.getValue(), now);
                    synced++;
                }
                continue;
            }
            long stored = SackText.stored(lore);
            if (stored < 0) {
                continue;
            }
            SbItem sb = SkyblockItems.read(stack);
            String id = sb != null && sb.id() != null ? sb.id() : data.idForName(name);
            if (id != null) {
                store.sync(id, stored, now);
                synced++;
            }
        }
        if (synced > 0 && store.dirty() && now - lastSave > 2_000) {
            save();
        }
    }

    private static void safeChat(Component msg, boolean overlay) {
        try {
            if (overlay || msg == null || !Skyblock.inSkyblock() || !SkyblockText.isSackSummary(msg.getString())) {
                return;
            }
            long now = System.currentTimeMillis();
            PriceData data = PriceService.data();
            List<String> hovers = new ArrayList<>();
            Skyblock.hoverTexts(msg, hovers);
            SackFamilies.Family best = null;
            double bestRaw = 0;
            for (String h : hovers) {
                for (SkyblockText.SackChange c : SkyblockText.sackChanges(h)) {
                    String id = data.idForName(c.itemName());
                    if (id == null) {
                        continue;
                    }
                    store.change(id, c.delta(), now);
                    SackFamilies.Family f = SackFamilies.forItem(id);
                    if (f != null && c.delta() > 0) {
                        double raw = (double) c.delta() * f.member(id).mult();
                        if (raw > bestRaw) {
                            bestRaw = raw;
                            best = f;
                        }
                    }
                }
            }
            if (best != null) {
                sackFamily = best;
                sackAt = now;
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static boolean miningIsland() {
        return TabWidgets.isMiningArea(TabWidgets.area(Skyblock.tabLines()));
    }

    private static long windowMs() {
        return HudConfig.get().moneyWindowMinutes * 60_000L;
    }

    private static String name(String id) {
        String n = PriceService.data().nameForId(id);
        if (n != null && !n.isBlank()) {
            return Fmt.plainName(n);
        }
        String s = id.toLowerCase(Locale.ROOT).replace(':', ' ').replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------ draw

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        HudConfig cfg = HudConfig.get();
        if (mc == null || mc.player == null || !cfg.sacksEnabled) {
            return;
        }
        boolean editing = HudStyle.editing();
        if (!Skyblock.inSkyblock() && !editing) {
            return;
        }
        SackFamilies.Family f = current;
        if (f == null && !editing) {
            return;
        }
        if (f == null) {
            f = SackFamilies.byName("Wheat");
        }
        long now = System.currentTimeMillis();
        Font font = mc.font;
        SackStore.View v = store.view(f, now, windowMs());

        String title = "Sacks";
        String right = v.oldestSync() < 0 ? "" : "synced " + Fmt.age(now - v.oldestSync());
        // Hypixel reports sacks every 20-30 s; between reports each count runs on at its recent rate,
        // marked "~" and dimmed, and snaps to the real number when the next [Sacks] summary lands.
        long activeUntil = blockFamily == f && blockAt > 0 ? blockAt + 1000 : now;
        List<String[]> rows = new ArrayList<>();
        long estTotal = 0;
        boolean estimated = false;
        for (SackStore.Row r : v.rows()) {
            if (r.count() != null && (r.count() > 0 || r.mult() == 1)) {
                Long est = store.estimate(r.id(), now, activeUntil);
                long shown = est == null ? r.count() : est;
                boolean guess = shown != r.count();
                estimated |= guess;
                estTotal += (shown - r.count()) * r.mult();
                rows.add(new String[]{name(r.id()), (guess ? "~" : "") + Fmt.coins(shown), guess ? "~" : ""});
            }
        }
        String totalLine;
        if (v.rawTotal() == null) {
            totalLine = "open /sacks to sync";
        } else {
            totalLine = (estimated ? "~ " : "= ") + Fmt.compact(v.rawTotal() + estTotal) + (v.partial() ? "+" : "")
                    + " " + f.name().toLowerCase(Locale.ROOT);
        }
        String rate = v.perHour() > 0 ? "+" + Fmt.compact(v.perHour()) + "/h" : "";

        int lh = font.lineHeight + 2;
        int innerW = Math.max(100, 6 + font.width(title) + 4 + font.width(f.name()) + 10 + font.width(right));
        for (String[] r : rows) {
            innerW = Math.max(innerW, font.width(r[0]) + 12 + font.width(r[1]));
        }
        innerW = Math.max(innerW, font.width(totalLine) + 10 + font.width(rate));
        int w = innerW + HudStyle.PAD * 2;
        int h = HudStyle.PAD + lh + 3 + lh * (rows.size() + 1) + HudStyle.PAD - 2;

        float sc = HudLayout.scale(HudLayout.SACKS);
        HudLayout.reportSize(HudLayout.SACKS, w, h);
        int[] p = HudLayout.place(HudLayout.SACKS, g.guiWidth(), g.guiHeight());
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate((float) p[0], (float) p[1]);
            pose.scale(sc, sc);
            HudStyle.panel(g, 0, 0, w, h);
            int x = HudStyle.PAD;
            int y = HudStyle.PAD;
            HudStyle.dot(g, x, y + 2, v.rawTotal() == null ? HudStyle.IDLE : HudStyle.GAIN);
            g.text(font, title, x + 6, y, HudStyle.ACCENT);
            g.text(font, f.name(), x + 6 + font.width(title) + 4, y, cfg.textColor);
            if (!right.isEmpty()) {
                g.text(font, right, w - HudStyle.PAD - font.width(right), y, HudStyle.DIM);
            }
            y += lh;
            HudStyle.rule(g, x, y, innerW);
            y += 3;
            for (String[] r : rows) {
                g.text(font, r[0], x, y, cfg.labelColor);
                g.text(font, r[1], x + innerW - font.width(r[1]), y, r[2].isEmpty() ? cfg.textColor : HudStyle.DIM);
                y += lh;
            }
            g.text(font, totalLine, x, y, v.rawTotal() == null ? HudStyle.WARN : estimated ? 0xFFC9A54A : HudStyle.COIN);
            if (!rate.isEmpty()) {
                g.text(font, rate, x + innerW - font.width(rate), y, HudStyle.GAIN);
            }
        } finally {
            pose.popMatrix();
        }
    }

    // ------------------------------------------------------------ file

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("auraqol-sacks.txt");
    }

    private static void load() {
        try {
            Path p = path();
            if (Files.isRegularFile(p)) {
                store = SackStore.fromText(Files.readString(p, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            AuraQoL.LOG.warn("[auraqol] sack counts unreadable, starting empty: {}", e.toString());
        }
    }

    private static void save() {
        lastSave = System.currentTimeMillis();
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, store.toText(), StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            store.clearDirty();
        } catch (IOException | RuntimeException e) {
            AuraQoL.LOG.warn("[auraqol] could not save sack counts: {}", e.toString());
        }
    }

    private static void fail(Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            AuraQoL.LOG.warn("[auraqol] sacks panel failed once (further errors silenced)", t);
        }
    }
}
