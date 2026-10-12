package elite.intel.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Tells which of a set of files changed since the last look, by polling - for files another process writes.
 * <p>
 * <strong>WHY polling, not a {@link java.nio.file.WatchService}</strong>: notifications for files another process
 * writes arrive late, arrive merged, or never arrive, and a missed one is never recovered. {@code AuxiliaryFilesMonitor}
 * polls for the same reason. A poll that misses nothing is worth more here than one that answers instantly.
 * <p>
 * <strong>A changed stamp is not a changed content.</strong> Last-modified plus size says a file was written, not
 * that it holds anything new - a game can rewrite a file with every value identical. A caller that must know what
 * changed compares content after this says where to look.
 * <p>
 * Holds no thread. The caller decides how often to ask; {@link #poll()} is safe to call from any one thread at a time.
 * <p>
 * <em>Added on {@code V1.2-BindForge}, not {@code V1.1-Release}: a new class only V1.2 calls edits no file V1.1
 * ships (Alan, 2026-10-11 - see the platform map's "shared write path").</em>
 */
public final class FilePoller {

    private final Supplier<Set<Path>> watched;
    /** The stamp each file had at the last poll; {@code null} until the first poll has looked. */
    private Map<Path, String> stamps;

    /**
     * @param watched the files to watch, asked on every poll - so files that appear or leave the set (a new
     *                installation, a deleted {@code .buttonMap}) are followed without telling this anything
     */
    public FilePoller(Supplier<Set<Path>> watched) {
        this.watched = Objects.requireNonNull(watched, "watched");
    }

    /**
     * The files whose stamp differs from the last poll: written, created, deleted, or come into or gone out of the
     * watched set. The first call only records where things stand, and returns nothing.
     */
    public synchronized Set<Path> poll() {
        Map<Path, String> now = new HashMap<>();
        for (Path file : watched.get()) {
            now.put(file, stampOf(file));
        }
        Map<Path, String> before = stamps;
        stamps = now;
        if (before == null) return Set.of();

        Set<Path> everyFile = new HashSet<>(before.keySet());
        everyFile.addAll(now.keySet());
        Set<Path> changed = new LinkedHashSet<>();
        for (Path file : everyFile) {
            if (!Objects.equals(before.get(file), now.get(file))) changed.add(file);
        }
        return changed;
    }

    /**
     * Last-modified and size together, or {@code null} for a file that is not there or cannot be read.
     * <p>
     * WHY the full {@link java.time.Instant} and the size, as {@code AuxiliaryFilesMonitor} keeps them: truncating to
     * milliseconds makes two same-size writes inside one millisecond look identical, and on a filesystem with coarse
     * times (FAT keeps two seconds) the size is the only thing left to tell two writes apart.
     */
    private static String stampOf(Path file) {
        try {
            if (!Files.exists(file)) return null;
            return Files.getLastModifiedTime(file).toInstant() + ":" + Files.size(file);
        } catch (IOException e) {
            return null;
        }
    }
}
