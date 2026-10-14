package elite.intel.bindforge.devicefiles;

import elite.intel.bindforge.devicefiles.DeviceFilesPush.MasterDevice;
import elite.intel.bindforge.devicefiles.DeviceFilesPush.Target;
import elite.intel.db.managers.BindForgeActivationManager;
import elite.intel.db.managers.BindForgeDeviceDraftManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Activation: the first opening of the BindForge tab (alias-designer.md, <em>Activation</em>, Alan, 2026-10-13).
 * <p>
 * <ul>
 *     <li><strong>A "before BindForge" backup first</strong>, whichever way activation goes -
 *     {@link BeforeBindForgeBackup}, which SET UP takes too.</li>
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

    public enum Outcome {
        /** BindForge was already activated. Nothing was done. */
        ALREADY_ACTIVATED,
        /** The installations were identical: the master is filled from them and BindForge is activated. */
        ACTIVATED,
        /** The installations differ, or a file could not be read: first setup is the user's to finish. */
        FIRST_SETUP,
        /** The "before BindForge" backup could not be taken. Nothing else was done; the next run tries again. */
        BACKUP_FAILED,
        /** Activating failed after the backup; the reason says how. Nothing was activated. */
        FAILED
    }

    /**
     * @param setup   for {@link Outcome#FIRST_SETUP}: what to open first setup with, or {@code null} when the
     *                installations could not be read
     * @param reading for {@link Outcome#FIRST_SETUP}: what the installations hold, or {@code null} when they could not
     *                be read
     * @param reason  what went wrong - for {@link Outcome#FIRST_SETUP}, the file that could not be read, which first
     *                setup names; {@code null} when nothing did
     */
    public record Result(Outcome outcome, FirstSetup setup, FirstSetup.Reading reading, String reason) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null, null, null);
        }
    }

    /** Takes the "before BindForge" backup unless it was taken before - {@link BeforeBindForgeBackup#ensureTaken()}. */
    @FunctionalInterface
    interface Backup {
        void ensureTaken() throws IOException;
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
    private final Backup backup;
    private final FirstSetupSource firstSetup;
    private final PushPlanner planner;
    private final FirstSetup.Establish establish;

    /** The stored installations, master and activation. */
    public static Activation stored() {
        FrontierStockDevices stock = FrontierStockDevices.getInstance();
        BeforeBindForgeBackup backup = BeforeBindForgeBackup.stored();
        return new Activation(
                BindForgeActivationManager.getInstance()::isActivated,
                backup::ensureTaken,
                FirstSetup::stored,
                (target, master) -> !DeviceFilesPush.plan(target, master, stock).writes().isEmpty(),
                BindForgeDeviceDraftManager.getInstance()::establish);
    }

    Activation(BooleanSupplier activated, Backup backup, FirstSetupSource firstSetup, PushPlanner planner,
               FirstSetup.Establish establish) {
        this.activated = activated;
        this.backup = backup;
        this.firstSetup = firstSetup;
        this.planner = planner;
        this.establish = establish;
    }

    /** Runs activation, if BindForge is not activated yet. Touches the disk - not for the EDT. */
    public Result run() {
        if (activated.getAsBoolean()) return Result.of(Outcome.ALREADY_ACTIVATED);

        // WHY: before anything is written. Taken once ever - first setup can be abandoned and reopened on a later run,
        // by when the files may have changed, so the first copy is the one that means "before BindForge".
        try {
            backup.ensureTaken();
        } catch (IOException | RuntimeException e) {
            // WHY: broad, because recording the backup is a database write, and JDBI's failures are unchecked.
            // Whatever the cause, nothing may be activated without the backup; the next run tries again.
            log.warn("BindForge was not activated: the backup before BindForge could not be taken", e);
            return new Result(Outcome.BACKUP_FAILED, null, null, FirstSetup.messageOf(e));
        }

        FirstSetup setup;
        FirstSetup.Reading reading;
        try {
            setup = firstSetup.get();
            reading = setup.read();
        } catch (IOException e) {
            // WHY: an unread file could hold the one entry the master must not lose, so it stops silent activation,
            // and the reason names it.
            log.info("BindForge activation opens first setup: {}", e.getMessage());
            return new Result(Outcome.FIRST_SETUP, null, null, FirstSetup.messageOf(e));
        } catch (RuntimeException e) {
            // WHY: the stored installations are read from the database, whose failures are unchecked. Not activated,
            // so SET UP stays offered.
            log.warn("BindForge activation could not read the installations", e);
            return new Result(Outcome.FAILED, null, null, FirstSetup.messageOf(e));
        }

        Optional<FirstSetupPlan.Resolution> fill;
        try {
            fill = fillWithoutWriting(reading, planner);
        } catch (IOException e) {
            // WHY: the same rule as a failed read - the file is named, so first setup does not open on a reading that
            // looks complete (review, 2026-10-13).
            log.info("BindForge activation opens first setup, a file could not be read: {}", e.getMessage());
            return new Result(Outcome.FIRST_SETUP, setup, reading, FirstSetup.messageOf(e));
        }
        if (fill.isEmpty()) {
            log.info("The installations' device files differ, so BindForge activation opens first setup");
            return new Result(Outcome.FIRST_SETUP, setup, reading, null);
        }

        FirstSetupPlan.Resolution resolution = fill.get();
        try {
            if (!establish.intoEmptyMaster(resolution.devices(), resolution.pendingRemovals())) {
                return Result.of(Outcome.ALREADY_ACTIVATED);
            }
        } catch (RuntimeException e) {
            // WHY: one transaction, whose failures are unchecked - it leaves nothing activated and nothing filled, so
            // the next run can simply try again.
            log.error("BindForge was not activated: the master could not be saved", e);
            return new Result(Outcome.FAILED, null, null, FirstSetup.messageOf(e));
        }
        log.info("BindForge activated: the installations agree, {} devices taken into the master",
                resolution.devices().size());
        return Result.of(Outcome.ACTIVATED);
    }

    /**
     * The master to fill with no prompt, when filling it from what the installations hold would write no game file.
     * Nothing to ask and nothing shadowed, and the push idle in every reachable installation. No reachable
     * installation at all qualifies: activation goes ahead with an empty master, and one found later is judged
     * against it.
     * <p>
     * Returns the resolution it tested, so what is saved is exactly what was judged identical (review, 2026-10-13).
     *
     * @return the master to fill, or empty when the installations are not identical
     * @throws IOException if an installation's files cannot be read for the push's plan
     */
    static Optional<FirstSetupPlan.Resolution> fillWithoutWriting(FirstSetup.Reading reading, PushPlanner planner)
            throws IOException {
        FirstSetupPlan plan = reading.plan();
        for (FirstSetupPlan.Row row : plan.rows()) {
            // WHY: a shadowed name becomes a pending removal - something first setup would leave to clean up, so
            // the installations are not what the master would make them.
            if (row.isQuestion() || row.kind() == FirstSetupPlan.Kind.SHADOWED) return Optional.empty();
        }
        FirstSetupPlan.Resolution resolution = plan.resolve(Map.of());
        List<MasterDevice> master = resolution.devices().stream()
                .map(device -> new MasterDevice(device.deviceName(), device.vid(), device.pid(), device.labels()))
                .toList();
        for (Target target : reading.targets()) {
            if (!target.isReachable()) continue;
            if (planner.wouldWrite(target, master)) return Optional.empty();
        }
        return Optional.of(resolution);
    }
}
