package elite.intel.bindforge.devicefiles;

import elite.intel.bindforge.devicefiles.DeviceFilesPush.Outcome;
import elite.intel.bindforge.devicefiles.DeviceFilesPush.Report;
import elite.intel.db.dao.BindForgeDeviceMasterDao.DeviceRow;
import elite.intel.db.managers.BindForgeDeviceDraftManager;
import elite.intel.db.managers.BindForgeDeviceMasterManager;
import elite.intel.session.PlayerSession;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * APPLY, for the device files: the draft becomes the master, then the master is pushed out and the pending
 * removals are taken out of every installation.
 * <p>
 * <strong>The draft is promoted before anything is written</strong> (Alan, 2026-10-04). The master records what
 * the user intends, so a push that cannot reach one installation still leaves the edits safe in the master -
 * that installation stops matching it, the install strip shows which, and the next Apply tries again. Nothing
 * the user saved is lost to a failed write.
 * <p>
 * <strong>A removal never strands a binding</strong> (Alan, 2026-10-11, A10). An entry {@code .binds} still names
 * is held back and stays pending: without it the game rejects the whole preset. Moving those bindings is a
 * {@code .binds} rewrite, which is not built. When {@code .binds} cannot be read, every removal is held back.
 * <p>
 * <strong>A removal stays pending until every installation has taken it</strong>, so one on an unplugged drive is
 * retried by the next Apply rather than forgotten.
 * <p>
 * With no draft, Apply still pushes the master. That is the retry after a partial Apply.
 */
public final class DeviceApply {

    private static final Logger log = LogManager.getLogger(DeviceApply.class);

    /** The names {@code .binds} gives devices, read when Apply runs. */
    @FunctionalInterface
    interface BindsNames {
        Set<String> read() throws IOException;
    }

    /**
     * @param removed       devices this Apply took out of the master - CLEAR and RESET LABELS, applied
     * @param report        what happened in each installation
     * @param cleanedUp     pending removals every installation has now lost, and which are pending no more
     * @param notEverywhere pending removals some installation did not take - unreachable, or failed - left pending
     *                      for the next Apply
     * @param heldBack      pending removals left in the installations because {@code .binds} names them, or could
     *                      not be read
     * @param bindsUnread   whether {@code .binds} could not be read, which is why everything was held back
     */
    public record Result(List<String> removed, Report report, List<String> cleanedUp, List<String> notEverywhere,
                         List<String> heldBack, boolean bindsUnread) {
        public Result {
            removed = List.copyOf(removed);
            Objects.requireNonNull(report, "report");
            cleanedUp = List.copyOf(cleanedUp);
            notEverywhere = List.copyOf(notEverywhere);
            heldBack = List.copyOf(heldBack);
        }
    }

    private final Supplier<List<String>> promote;
    private final Function<Set<String>, Report> push;
    private final BindsNames bindsNames;

    /**
     * Promotes the stored draft and pushes to the stored installations.
     *
     * @throws IOException if Elite-Intel's data folder cannot be created for Edit History
     */
    public DeviceApply() throws IOException {
        this(BindForgeDeviceDraftManager.getInstance()::promote, new DeviceFilesPush()::push,
                () -> BindsDeviceReferences.referencedEntryNames(PlayerSession.getInstance().getBindingsDir()));
    }

    DeviceApply(Supplier<List<String>> promote, Function<Set<String>, Report> push, BindsNames bindsNames) {
        this.promote = promote;
        this.push = push;
        this.bindsNames = bindsNames;
    }

    public Result apply() {
        List<String> removed = promote.get();
        BindForgeDeviceMasterManager master = BindForgeDeviceMasterManager.getInstance();

        Set<String> inMaster = master.findAll().stream().map(DeviceRow::deviceName).collect(Collectors.toSet());
        List<String> pending = master.pendingRemovals().stream().filter(name -> !inMaster.contains(name)).toList();

        Set<String> named;
        boolean bindsUnread = false;
        try {
            named = folded(bindsNames.read());
        } catch (IOException e) {
            log.warn("Could not read .binds, so no device is removed by this Apply: {}", e.getMessage());
            named = null;
            bindsUnread = true;
        }
        Set<String> removals = new TreeSet<>();
        List<String> heldBack = new ArrayList<>();
        for (String name : pending) {
            // WHY: compared ignoring case. A hand-edited .binds can spell a name otherwise, and holding back an
            // entry that might be in use costs a leftover; removing one that is costs the whole preset.
            if (named == null || named.contains(name.toLowerCase(Locale.ROOT))) {
                heldBack.add(name);
            } else {
                removals.add(name);
            }
        }

        Report report = push.apply(removals);
        List<String> cleanedUp = List.of();
        List<String> notEverywhere = List.of();
        if (!removals.isEmpty()) {
            if (everyInstallationTookIt(report)) {
                removals.forEach(master::clearPendingRemoval);
                cleanedUp = List.copyOf(removals);
            } else {
                notEverywhere = List.copyOf(removals);
            }
        }
        return new Result(removed, report, cleanedUp, notEverywhere, heldBack, bindsUnread);
    }

    private static boolean everyInstallationTookIt(Report report) {
        return !report.installations().isEmpty() && report.installations().stream()
                .allMatch(result -> result.outcome() == Outcome.WRITTEN || result.outcome() == Outcome.UNCHANGED);
    }

    private static Set<String> folded(Set<String> names) {
        return names.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}
