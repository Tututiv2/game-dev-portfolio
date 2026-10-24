package com.thestill.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Write-a-file-or-leave-the-old-one utilities.
 *
 * <p>Shadow Prison lost live player data on 2026-07-18 to a plain in-place YAML
 * write that was truncated by a shutdown mid-flush. The truncated file then
 * loaded as <em>empty rather than as an error</em> — which is the genuinely
 * dangerous part, because the next save happily persisted that empty state over
 * the backup. Never write a data file in place.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * Writes {@code content} to {@code target} such that a crash at any instant
     * leaves either the complete old file or the complete new one, never a
     * partial one.
     */
    public static void writeString(File target, String content) throws IOException {
        Path destination = target.toPath();
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Thread id in the name so two writers racing on the same file cannot
        // clobber each other's temp file and produce a spliced result.
        Path temp = destination.resolveSibling(
                target.getName() + "." + Thread.currentThread().threadId() + ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Some network and virtualised filesystems refuse ATOMIC_MOVE.
                // A plain replace is weaker but still far better than writing
                // through the destination handle.
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
