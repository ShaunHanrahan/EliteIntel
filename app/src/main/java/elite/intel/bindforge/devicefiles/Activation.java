package elite.intel.bindforge.devicefiles;

import elite.intel.ai.mouth.subscribers.events.MissionCriticalAnnouncementEvent;
import elite.intel.ai.mouth.subscribers.events.VocalisationRequestEvent;
import elite.intel.bindforge.devicefiles.DeviceFilesPush.MasterDevice;
import elite.intel.bindforge.devicefiles.DeviceFilesPush.Target;
import elite.intel.db.managers.BindForgeActivationManager;
import elite.intel.db.managers.BindForgeDeviceDraftManager;
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
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Activation: the first opening of the BindForge tab (alias-designer.md, <em>Activation</em>, Alan, 2026-10-13).
 * <p>
 * <ul>
 *     <li><strong>A "before BindForge" backup first</strong>, whichever way activation goes: every reachable
 *     installation's device files and every {@code .binds} and {@code StartPreset} in the bindings folder. Taken
 *     once ever, recorded, never pruned - and the user is told once, spoken and logged.</li>
 *     <li><strong>Identical installations activate with no prompt</strong>, the master filled from what they hold,
 *     which may be nothing. "Identical" means filling the master would write no game file.</li>
 *     <li><strong>Anything else opens first setup</strong> - a difference, even one with nothing to ask, or a file
 *     that cannot be read. Opening BindForge is the user asking; first setup still writes nothing until its last
 *     button, and activates when it does.</li>
 * </ul>
 * Writes no game file. The only writes are the backup, Elite-Intel's own database, and - for identical
 * installations - the master.
 */
public final class Activation {

    private static final Logger log = LogManager.getLogger(Activation.class);
    static final String SNAPSHOT_LABEL = "before-bindforge";

    public enum Outcome {
        /** BindForge was already activated. Nothing was done. */
        ALREADY_ACTIVATED,
        /** The installations were identical: the master is filled from them and BindForge is activated. */
        ACTIVATED,
        /** The installations differ, or one could not be read: first setup is the user's to finish. */
        FIRST_SETUP,
        /** The "before BindForge" backup could not be taken. Nothing else was done; the next run tries again. */
        BACKUP_FAILED,
        /** Activating failed after the backup; the reason says how. Nothing was activated. */
        FAILED
    }

    /**
     * @param setup       for {@link Outcome#FIRST_SETUP}: what to open first setup with
     * @param reading     for {@link Outcome#FIRST_SETUP}: what the installations hold, or {@code null} when they
     *                    could not be read - {@code reason} then names which
     * @param reason      what went wrong, or why first setup has no reading; {@code null} otherwise
     */
    public record Result(Outcome outcome, FirstSetup setup, FirstSetup.Reading reading, String reason) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null, null, null);
        }
    }

    /** The one backup, its folder recorded so it is taken once. */
    interface BeforeBackup {
        /** @return the folder already taken, or {@code null} */
        String taken();

        /** Takes it and records it. */
        Path take(List<Target> targets) throws IOException;
    }

    /** Opens first setup's reading of the stored installations - {@link FirstSetup#stored()}. */
    @FunctionalInterface
    interface FirstSetupSource {
        FirstSetup get() throws IOException;
    }

    /** Whether filling the master would write anything into one installation - {@link DeviceFilesPush#plan}. */
    @FunctionalInterface
    interface PushPlanner {
        boolean wouldWrite(Target target, List<MasterDevice> master) throws IOException;
    }

    private final BooleanSupplier activated;
    private final Supplier<List<Target>> targets;
    private final BeforeBackup backup;
    private final Consumer<Path> announce;
    private final FirstSetupSource firstSetup;
    private final PushPlanner planner;
    private final FirstSetup.Establish establish;

    /** The stored installations, master and activation. */
    public static Activation stored() {
        BindForgeActivationManager store = BindForgeActivationManager.getInstance();
        FrontierStockDevices stock = FrontierStockDevices.getInstance();
        return new Activation(
                store::isActivated,
                DeviceFilesPush::storedInstallations,
                new BeforeBackup() {
                    @Override
                    public String taken() {
                        return store.backupFolder();
                    }

                    @Override
                    public Path take(List<Target> targets) throws IOException {
                        Path folder = new DeviceFilesSnapshot().take(SNAPSHOT_LABEL, targets,
                                List.of(PlayerSession.getInstance().getBindingsDir()));
                        store.recordBackupFolder(folder.toString());
                        return folder;
                    }
                },
                Activation::announce,
                FirstSetup::stored,
                (target, master) -> !DeviceFilesPush.plan(target, master, stock).writes().isEmpty(),
                BindForgeDeviceDraftManager.getInstance()::establish);
    }

    Activation(BooleanSupplier activated, Supplier<List<Target>> targets, BeforeBackup backup, Consumer<Path> announce,
               FirstSetupSource firstSetup, PushPlanner planner, FirstSetup.Establish establish) {
        this.activated = activated;
        this.targets = targets;
        this.backup = backup;
        this.announce = announce;
        this.firstSetup = firstSetup;
        this.planner = planner;
        this.establish = establish;
    }

    /** Runs activation, if BindForge is not activated yet. Touches the disk - not for the EDT. */
    public Result run() {
        if (activated.getAsBoolean()) return Result.of(Outcome.ALREADY_ACTIVATED);

        // WHY: before anything is written, and once ever. First setup can be abandoned and reopened on a later run,
        // by when the files may have changed - so the first copy is the one that means "before BindForge".
        if (backup.taken() == null) {
            Path folder;
            try {
                folder = backup.take(targets.get());
            } catch (IOException | RuntimeException e) {
                log.warn("BindForge was not activated: the backup before BindForge could not be taken", e);
                return new Result(Outcome.BACKUP_FAILED, null, null, FirstSetup.messageOf(e));
            }
            announce.accept(folder);
        }

        FirstSetup setup;
        FirstSetup.Reading reading;
        try {
            setup = firstSetup.get();
            reading = setup.read();
        } catch (IOException e) {
            // WHY: an unread file could hold the one entry the master must not lose, so it stops silent activation,
            // and first setup opens to name it.
            log.info("BindForge activation opens first setup: {}", e.getMessage());
            return new Result(Outcome.FIRST_SETUP, null, null, FirstSetup.messageOf(e));
        } catch (RuntimeException e) {
            log.warn("BindForge activation could not read the installations", e);
            return new Result(Outcome.FAILED, null, null, FirstSetup.messageOf(e));
        }

        boolean same;
        try {
            same = identical(reading, planner);
        } catch (IOException e) {
            log.info("BindForge activation opens first setup: {}", e.getMessage());
            return new Result(Outcome.FIRST_SETUP, setup, reading, null);
        }
        if (!same) {
            log.info("The installations' device files differ, so BindForge activation opens first setup");
            return new Result(Outcome.FIRST_SETUP, setup, reading, null);
        }

        FirstSetupPlan.Resolution resolution = reading.plan().resolve(Map.of());
        try {
            if (!establish.intoEmptyMaster(resolution.devices(), resolution.pendingRemovals())) {
                return Result.of(Outcome.ALREADY_ACTIVATED);
            }
        } catch (RuntimeException e) {
            log.error("BindForge was not activated: the master could not be saved", e);
            return new Result(Outcome.FAILED, null, null, FirstSetup.messageOf(e));
        }
        log.info("BindForge activated: the installations agree, {} devices taken into the master",
                resolution.devices().size());
        return Result.of(Outcome.ACTIVATED);
    }

    /**
     * Whether filling the master from what the installations hold would write no game file - so it can be filled
     * with no prompt. Nothing to ask and nothing shadowed, and the push idle in every reachable installation. No
     * reachable installation at all is identical: activation goes ahead with an empty master, and one found later is
     * judged against it.
     *
     * @throws IOException if an installation's files cannot be read for the push's plan
     */
    static boolean identical(FirstSetup.Reading reading, PushPlanner planner) throws IOException {
        FirstSetupPlan plan = reading.plan();
        for (FirstSetupPlan.Row row : plan.rows()) {
            // WHY: a shadowed name becomes a pending removal - something first setup would leave to clean up, so
            // the installations are not what the master would make them.
            if (row.isQuestion() || row.kind() == FirstSetupPlan.Kind.SHADOWED) return false;
        }
        List<MasterDevice> master = plan.resolve(Map.of()).devices().stream()
                .map(device -> new MasterDevice(device.deviceName(), device.vid(), device.pid(), device.labels()))
                .toList();
        for (Target target : reading.targets()) {
            if (!target.isReachable()) continue;
            if (planner.wouldWrite(target, master)) return false;
        }
        return true;
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
