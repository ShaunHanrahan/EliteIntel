package elite.intel.bindforge.devicefiles;

import elite.intel.bindforge.devicefiles.DeviceFilesPush.Target;
import elite.intel.bindforge.install.GameInstallation;
import elite.intel.util.AppPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A labelled copy of every installation's device files, taken immediately before an operation that rewrites them
 * all - first setup's <em>"just before I standardised"</em> restore point (alias-designer.md, <em>Two ways
 * back</em>).
 * <p>
 * Each installation's {@code DeviceMappings.xml} and its whole {@code DeviceButtonMaps} folder, as found, under
 * {@code <player backups>/<yyyy-MM-dd_HH-mm-ss>_<label>/<installation id>-<storefront>/}.
 * <p>
 * <strong>Always the default player backups folder</strong> (Alan, 2026-10-07). A destination the user chose is
 * resolved only inside {@code PlayerBackupService}, which is shared with V1.1; honouring it here waits for that
 * resolver to be exposed. The folder name sorts with the player backups because it starts the same way.
 * <p>
 * <strong>Activation's "before BindForge" backup also takes the bindings folder</strong> (Alan, 2026-10-13, A1b):
 * every {@code .binds} and {@code StartPreset} file in it, under {@code bindings/} - all of the user's input data,
 * as it was before BindForge wrote anything.
 */
public final class DeviceFilesSnapshot {

    private static final Logger log = LogManager.getLogger(DeviceFilesSnapshot.class);
    // Matches PlayerBackupService's folder names, so the two sort together.
    private static final DateTimeFormatter FOLDER_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    static final String BINDINGS = "bindings";

    private final Path root;
    private final Clock clock;

    /** @throws IOException if Elite-Intel's player backups folder cannot be created */
    public DeviceFilesSnapshot() throws IOException {
        this(AppPaths.getPlayerBackupsDir(), Clock.systemDefaultZone());
    }

    DeviceFilesSnapshot(Path root, Clock clock) {
        this.root = root;
        this.clock = clock;
    }

    /**
     * Copies every reachable installation's device files. An installation whose folder is gone has nothing to
     * copy and is left out; the operation that follows skips it too.
     *
     * @param label names the snapshot - {@code first-setup}
     * @return the snapshot's folder
     * @throws IOException if any copy fails. The operation it protects must not go ahead, and the half-written
     *                     folder is removed - a partial snapshot in the backups list would pass for a restore point
     */
    public Path take(String label, List<Target> targets) throws IOException {
        return take(label, targets, List.of());
    }

    /**
     * As {@link #take(String, List)}, and every {@code .binds} and {@code StartPreset.#.start} in each bindings
     * folder too - into {@code bindings/}, then {@code bindings-2/} and on where there is more than one, as on Linux,
     * where each Proton installation has its own (input-environment.md §7). A bindings folder that is not there has
     * nothing to copy: a user who never changed a binding in the game has none.
     */
    public Path take(String label, List<Target> targets, List<Path> bindingsFolders) throws IOException {
        Path folder = uniqueFolder(ZonedDateTime.now(clock).format(FOLDER_TIMESTAMP) + "_" + label);
        try {
            copyInto(folder, targets);
            copyBindings(folder, bindingsFolders);
        } catch (IOException e) {
            deleteQuietly(folder, e);
            throw e;
        }
        log.info("Device files backed up before {} at {}", label, folder);
        return folder;
    }

    private static void copyInto(Path folder, List<Target> targets) throws IOException {
        Files.createDirectories(folder);
        for (Target target : targets) {
            if (!target.isReachable()) continue;
            Path into = folder.resolve(target.installId() + "-" + safe(target.storefront()));
            Files.createDirectories(into);

            Path deviceMappings = GameInstallation.deviceMappingsIn(target.controlSchemes());
            if (Files.isRegularFile(deviceMappings)) {
                Files.copy(deviceMappings, into.resolve(GameInstallation.DEVICE_MAPPINGS),
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
            Path buttonMaps = GameInstallation.deviceButtonMapsIn(target.controlSchemes());
            if (!Files.isDirectory(buttonMaps)) continue;
            Path mapsInto = into.resolve(GameInstallation.DEVICE_BUTTON_MAPS);
            Files.createDirectories(mapsInto);
            try (Stream<Path> files = Files.list(buttonMaps)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Files.copy(file, mapsInto.resolve(file.getFileName().toString()),
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void copyBindings(Path folder, List<Path> bindingsFolders) throws IOException {
        int n = 1;
        for (Path bindings : bindingsFolders) {
            if (!Files.isDirectory(bindings)) continue;
            Path into = folder.resolve(n == 1 ? BINDINGS : BINDINGS + "-" + n);
            n++;
            Files.createDirectories(into);
            try (Stream<Path> files = Files.list(bindings)) {
                for (Path file : files.filter(Files::isRegularFile).filter(DeviceFilesSnapshot::isInputFile).toList()) {
                    Files.copy(file, into.resolve(file.getFileName().toString()), StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    /** A preset, or the file naming the active ones - {@code StartPreset.start} or {@code StartPreset.4.start}. */
    static boolean isInputFile(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".binds") || (name.startsWith("StartPreset.") && name.endsWith(".start"));
    }

    /** Removes a failed snapshot. What cannot be removed is attached to the failure, not thrown over it. */
    private static void deleteQuietly(Path folder, IOException failure) {
        if (!Files.exists(folder)) return;
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }

    private Path uniqueFolder(String name) {
        Path folder = root.resolve(name);
        for (int n = 2; Files.exists(folder); n++) {
            folder = root.resolve(name + "-" + n);
        }
        return folder;
    }

    /** A storefront is a label, not a filename; anything that could not be one is replaced. */
    private static String safe(String storefront) {
        return storefront.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
