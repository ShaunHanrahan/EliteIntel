package elite.intel.bindforge.devicefiles;

import elite.intel.bindforge.devicefiles.DeviceFilesPush.Target;
import elite.intel.bindforge.install.GameInstallation;
import elite.intel.eventbus.UiBus;
import elite.intel.io.FilePoller;
import elite.intel.ui.controller.ManagedService;
import elite.intel.ui.event.DeviceFilesChangedEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Notices when an installation's {@code DeviceMappings.xml} or a {@code .buttonMap} changes while Elite-Intel runs,
 * and says so - so the startup check runs again "whenever a watched live file changes", as the overview's startup
 * check puts it. Core C5, 2026-10-11.
 * <p>
 * <strong>It reports; it never judges or writes.</strong> What the change means is {@link DeviceFilesCheck}'s to
 * decide, by content, on the refresh the event starts. A stamp alone is no evidence: the game rewrites files with
 * nothing changed.
 * <p>
 * <strong>BindForge's own writes are reported too</strong> - Apply, revert, adopt, first setup, generated
 * {@code .buttonMap}s. That costs one refresh that finds the files matching the master, which is cheaper than a way to
 * tell this watcher which writes were ours, and cannot be wrong (Alan, 2026-10-11).
 * <p>
 * Polls through {@link FilePoller} rather than extending {@code BindingsMonitor}'s {@code WatchService}: the game
 * writes these files, and a notification for another process's write can be lost for good (Krondor, 2026-10-03;
 * recorded in the overview's "External change detection"). Started as {@code ServiceType.DEVICE_FILES_MONITOR} in
 * {@code AppController}; diagnostics mode stubs it with the other game-file services.
 */
public final class DeviceFilesMonitor implements ManagedService {

    private static final Logger log = LogManager.getLogger(DeviceFilesMonitor.class);

    /**
     * These files change when the user saves in the game's Controls screen or a patch lands - rarely - and one poll
     * is a stamp per file. Two seconds is quick enough for a screen that is looked at, and costs next to nothing.
     */
    private static final Duration INTERVAL = Duration.ofSeconds(2);

    /**
     * Long enough for a poll on a drive that answers. Listing a folder and reading a file's times are not
     * interruptible, so a poll stuck on a drive that stopped answering is left behind on its daemon thread, and logged.
     */
    private static final long STOP_WAIT_SECONDS = 1;

    private final Supplier<List<Target>> targets;
    private final Runnable onChange;
    private final Duration interval;
    private final FilePoller poller;
    private ScheduledExecutorService worker;

    public DeviceFilesMonitor() {
        this(DeviceFilesPush::storedInstallations, () -> UiBus.publish(new DeviceFilesChangedEvent()), INTERVAL);
    }

    /**
     * @param targets  the stored installations, asked on every poll so one added or removed is followed
     * @param onChange told once per poll that found anything changed
     */
    DeviceFilesMonitor(Supplier<List<Target>> targets, Runnable onChange, Duration interval) {
        this.targets = targets;
        this.onChange = onChange;
        this.interval = interval;
        this.poller = new FilePoller(this::watchedFiles);
    }

    /** Records where the files stand now, then looks again every interval. */
    @Override
    public synchronized void start() {
        if (worker != null) return;
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bindforge-device-files-monitor");
            thread.setDaemon(true);
            return thread;
        });
        // WHY: the first poll only records, so nothing is reported for files as they stood at start - the screen's
        // own first refresh reads those.
        worker.scheduleWithFixedDelay(this::poll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (worker == null) return;
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("The device-file monitor did not stop within {} s", STOP_WAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        worker = null;
    }

    /** One look. Package-private so a test can drive it without waiting on the schedule. */
    void poll() {
        try {
            Set<Path> changed = poller.poll();
            if (changed.isEmpty()) return;
            log.info("Device files changed on disk: {}", changed);
            onChange.run();
        } catch (RuntimeException e) {
            // WHY: broad on purpose. An exception escaping a scheduled task cancels every later run, silently - the
            // monitor would stop and nothing would say so.
            log.warn("Could not poll the device files", e);
        }
    }

    /**
     * Every installation's {@code DeviceMappings.xml}, whether or not it exists - one appearing or vanishing is a
     * change - and every {@code .buttonMap} in its button-map folder. An installation known to be gone is skipped;
     * its folder coming back is the registry's to notice, not this.
     */
    private Set<Path> watchedFiles() {
        Set<Path> files = new LinkedHashSet<>();
        for (Target target : targets.get()) {
            if (target.missing()) continue;
            files.add(GameInstallation.deviceMappingsIn(target.controlSchemes()));
            Path buttonMaps = GameInstallation.deviceButtonMapsIn(target.controlSchemes());
            if (!Files.isDirectory(buttonMaps)) continue;
            try (Stream<Path> listing = Files.list(buttonMaps)) {
                listing.filter(file -> file.getFileName().toString().endsWith(GameInstallation.BUTTON_MAP_SUFFIX))
                        .forEach(files::add);
            } catch (IOException e) {
                // WHY: debug, not warn - a drive that stops answering would otherwise log every two seconds. Its
                // .buttonMaps leave the watched set, which reports them once as changed, and the refresh says why.
                log.debug("Could not list {}", buttonMaps, e);
            }
        }
        return files;
    }
}
