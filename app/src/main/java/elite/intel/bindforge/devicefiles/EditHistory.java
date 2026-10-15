package elite.intel.bindforge.devicefiles;

import elite.intel.bindforge.install.GameInstallation;
import elite.intel.db.managers.BindForgeSettingsManager;
import elite.intel.io.TimestampedBackups;
import elite.intel.util.AppPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Edit History for the device files: a copy of a game file kept before it is replaced, under Elite-Intel's own data
 * folder, one folder per installation (file-manager.md, <em>Edit History</em>).
 * <p>
 * <strong>Unique content is kept once per write</strong>: after standardisation every installation holds the same
 * files, so keeping each one would be several identical copies of one file. The caller passes the set of contents
 * already kept, for as long as one write lasts - a push across every installation, or onboarding's.
 * <p>
 * Shared by the push and onboarding (A6b), so both keep their copies in the same place and prune them the same way.
 */
final class EditHistory {

    private final TimestampedBackups backups;
    private final Path historyRoot;
    private final IntSupplier retention;

    /**
     * Edit History in Elite-Intel's own data folder, with the user's retention - the one place that folder is named.
     *
     * @throws IOException if Elite-Intel's data folder cannot be created
     */
    static EditHistory stored() throws IOException {
        return new EditHistory(new TimestampedBackups(), AppPaths.getBindingsWorkingDir().resolve("history"),
                BindForgeSettingsManager.getInstance()::getEditHistoryRetention);
    }

    EditHistory(TimestampedBackups backups, Path historyRoot, IntSupplier retention) {
        this.backups = backups;
        this.historyRoot = historyRoot;
        this.retention = retention;
    }

    /** How many copies of one file to keep, read once at the start of a write. */
    int retention() {
        return retention.getAsInt();
    }

    /**
     * Keeps {@code file} as it is now, unless {@code previous} - its content - was already kept during this write.
     * Nothing is kept for a file that does not exist yet.
     *
     * @param previous the file's content now, or {@code null} when there is no file
     * @param kept     the contents already kept during this write, by hash; added to
     * @throws IOException when the copy cannot be taken - the caller must not write the file then
     */
    void keep(long installId, Path file, byte[] previous, int keep, Set<String> kept) throws IOException {
        if (previous == null) return;
        if (!kept.add(sha256(previous))) return;
        Path folder = historyRoot.resolve(String.valueOf(installId));
        if (file.getFileName().toString().endsWith(GameInstallation.BUTTON_MAP_SUFFIX)) {
            folder = folder.resolve(GameInstallation.DEVICE_BUTTON_MAPS);
        }
        backups.create(file, folder);
        backups.prune(folder, file.getFileName().toString(), keep);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
