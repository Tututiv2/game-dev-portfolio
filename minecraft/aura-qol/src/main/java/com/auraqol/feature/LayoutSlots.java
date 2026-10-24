package com.auraqol.feature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.auraqol.AuraQoL;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Save slots for the whole HUD setup: where every panel sits and how big it is
 * ({@code auraqol-layout.json}) AND which features are on ({@code auraqol-hud.json}).
 *
 * <p>A slot is just a copy of those two files in {@code config/auraqol-slots/slotN/}. Copying the
 * real files, rather than a third format, means a slot can never disagree with what the mod actually
 * loads, and a new setting is saved by slots the day it is added with no change here. Loading copies
 * them back and re-reads both, so the HUD changes on the next frame.
 */
public final class LayoutSlots {

    public static final int COUNT = 5;
    private static final String LAYOUT = "auraqol-layout.json";
    private static final String HUD = "auraqol-hud.json";
    private static final String INFO = "info.txt";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, HH:mm");

    private LayoutSlots() {
    }

    private static Path config() {
        return FabricLoader.getInstance().getConfigDir();
    }

    private static Path dir(int slot) {
        return config().resolve("auraqol-slots").resolve("slot" + slot);
    }

    public static boolean used(int slot) {
        return Files.exists(dir(slot).resolve(LAYOUT));
    }

    /** "Shadow Prison  ·  Oct 13, 20:55", or "Empty". */
    public static String describe(int slot) {
        if (!used(slot)) {
            return "Empty";
        }
        try {
            return Files.readString(dir(slot).resolve(INFO), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "Saved";
        }
    }

    /** @return a line for the player */
    public static String save(int slot) {
        if (slot < 1 || slot > COUNT) {
            return "Slots are 1-" + COUNT + ".";
        }
        try {
            HudLayout.save();
            HudConfig.get().save();
            Path d = dir(slot);
            Files.createDirectories(d);
            copy(config().resolve(LAYOUT), d.resolve(LAYOUT));
            copy(config().resolve(HUD), d.resolve(HUD));
            String where = ShadowLive.active() ? "Shadow Prison" : Skyblock.onHypixel() ? "Hypixel" : "";
            String info = (where.isEmpty() ? "" : where + "  ·  ") + LocalDateTime.now().format(WHEN);
            Files.writeString(d.resolve(INFO), info, StandardCharsets.UTF_8);
            return "Saved your HUD to slot " + slot + ".";
        } catch (IOException | RuntimeException e) {
            AuraQoL.LOG.warn("[auraqol] could not save slot {}", slot, e);
            return "Could not save slot " + slot + ": " + e.getMessage();
        }
    }

    /** @return a line for the player */
    public static String load(int slot) {
        if (slot < 1 || slot > COUNT) {
            return "Slots are 1-" + COUNT + ".";
        }
        if (!used(slot)) {
            return "Slot " + slot + " is empty. Save to it first.";
        }
        try {
            Path d = dir(slot);
            copy(d.resolve(LAYOUT), config().resolve(LAYOUT));
            if (Files.exists(d.resolve(HUD))) {
                copy(d.resolve(HUD), config().resolve(HUD));
            }
            HudLayout.load();
            HudConfig.reload();
            return "Loaded slot " + slot + ".";
        } catch (IOException | RuntimeException e) {
            AuraQoL.LOG.warn("[auraqol] could not load slot {}", slot, e);
            return "Could not load slot " + slot + ": " + e.getMessage();
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        if (!Files.exists(from)) {
            return;
        }
        Path tmp = to.resolveSibling(to.getFileName() + ".tmp");
        Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
