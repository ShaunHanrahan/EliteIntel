package elite.intel.bindforge.devicefiles;

import elite.intel.ai.mouth.subscribers.events.MissionCriticalAnnouncementEvent;
import elite.intel.ai.mouth.subscribers.events.VocalisationRequestEvent;
import elite.intel.bindforge.devicefiles.DeviceFilesPush.Target;
import elite.intel.db.managers.BindForgeActivationManager;
import elite.intel.eventbus.GameEventBus;
import elite.intel.eventbus.UiBus;
import elite.intel.session.PlayerSession;
import elite.intel.ui.event.AppLogEvent;
import elite.intel.util.StringUtls;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The "before BindForge" backup: every reachable installation's device files and every {@code .binds} and
 * {@code StartPreset} in the bindings folder, taken once ever, before anything is written (alias-designer.md,
 * <em>Activation</em>, Alan, 2026-10-13).
 * <p>
 * <strong>Every way into activation takes it</strong> - activation on the tab's first showing, and SET UP before
 * first setup reads (review, 2026-10-13). Otherwise a user whose first attempt could not take it, and who then
 * pressed SET UP, would be activated without it, and every later run would skip the step.
 * <p>
 * Its folder is recorded so it is taken once, and the user is told then - spoken and logged.
 */
public final class BeforeBindForgeBackup {

    private static final Logger log = LogManager.getLogger(BeforeBindForgeBackup.class);
    static final String LABEL = "before-bindforge";
    /** One at a time across every caller, so activation and SET UP cannot both take it. */
    private static final Object TAKING = new Object();

    /** Where the taken backup's folder is recorded - {@link BindForgeActivationManager}. */
    interface Record {
        /** @return the folder already taken, or {@code null} */
        String taken();

        void record(String folder);
    }

    /** Copies the files - {@link DeviceFilesSnapshot#take(String, List, List)}. */
    @FunctionalInterface
    interface Snapshot {
        Path take(List<Target> targets) throws IOException;
    }

    private final Supplier<List<Target>> targets;
    private final Snapshot snapshot;
    private final Record store;
    private final Consumer<Path> announce;

    /** The stored installations, the default player backups folder and the bindings folder. */
    public static BeforeBindForgeBackup stored() {
        BindForgeActivationManager activation = BindForgeActivationManager.getInstance();
        return new BeforeBindForgeBackup(
                DeviceFilesPush::storedInstallations,
                targets -> new DeviceFilesSnapshot().take(LABEL, targets,
                        List.of(PlayerSession.getInstance().getBindingsDir())),
                new Record() {
                    @Override
                    public String taken() {
                        return activation.backupFolder();
                    }

                    @Override
                    public void record(String folder) {
                        activation.recordBackupFolder(folder);
                    }
                },
                BeforeBindForgeBackup::announce);
    }

    BeforeBindForgeBackup(Supplier<List<Target>> targets, Snapshot snapshot, Record store, Consumer<Path> announce) {
        this.targets = targets;
        this.snapshot = snapshot;
        this.store = store;
        this.announce = announce;
    }

    /**
     * Takes the backup unless it was taken before. Touches the disk - not for the EDT.
     *
     * @return the folder taken now, or empty when it had already been taken
     * @throws IOException if the files could not be copied or the folder could not be recorded. Nothing may be
     *                     written until it has been taken
     */
    public Optional<Path> ensureTaken() throws IOException {
        synchronized (TAKING) {
            if (store.taken() != null) return Optional.empty();
            Path folder = snapshot.take(targets.get());
            try {
                store.record(folder.toString());
            } catch (RuntimeException e) {
                // WHY: the copy is left where it is. It is a complete restore point, and an extra one is harmless;
                // the next attempt takes another, and that one is recorded. Deleting it would throw away a good backup
                // because the database refused one row.
                throw new IOException("the backup was taken at " + folder + " but could not be recorded: "
                        + StringUtls.messageOf(e), e);
            }
            announce.accept(folder);
            return Optional.of(folder);
        }
    }

    /** Told once, on the backup being taken: one spoken line, and a log line naming where it is. */
    private static void announce(Path folder) {
        GameEventBus.publish(new VocalisationRequestEvent(StringUtls.localizedSpeech("speech.bindforgeBackedUp"),
                MissionCriticalAnnouncementEvent.class, false));
        UiBus.publish(new AppLogEvent("BindForge backed up your input configuration files before changing anything: "
                + folder));
        log.info("Backup before BindForge taken at {}", folder);
    }
}
