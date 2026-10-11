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
import java.util.Optional;
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
 * {@code .binds} rewrite, which is not built. When {@code .binds} cannot be read, every such removal is held back.
 * <strong>One of Frontier's names is never held back</strong> (review, 2026-10-11): RESET LABELS on a built-in removes
 * only its {@code .buttonMap}, never Frontier's element, and {@code .binds} names inputs by token, never by label -
 * so nothing a binding needs is lost, and holding it back would leave the reset pending forever.
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
     * @param bindsUnread   whether {@code .binds} could not be read and an entry of the user's was held back for it
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
    private final FrontierStockDevices stock;
    private final BindForgeDeviceMasterManager master;

    /**
     * Promotes the stored draft and pushes to the stored installations.
     *
     * @throws IOException if Elite-Intel's data folder cannot be created for Edit History
     */
    public DeviceApply() throws IOException {
        this(BindForgeDeviceDraftManager.getInstance()::promote, new DeviceFilesPush()::push,
                () -> BindsDeviceReferences.referenceCounts(PlayerSession.getInstance().getBindingsDir()).keySet(),
                FrontierStockDevices.getInstance(), BindForgeDeviceMasterManager.getInstance());
    }

    /** @param bindsNames must throw rather than answer when it cannot be sure - a missing folder is not "none" */
    DeviceApply(Supplier<List<String>> promote, Function<Set<String>, Report> push, BindsNames bindsNames,
                FrontierStockDevices stock, BindForgeDeviceMasterManager master) {
        this.promote = promote;
        this.push = push;
        this.bindsNames = bindsNames;
        this.stock = stock;
        this.master = master;
    }

    public Result apply() {
        List<String> removed = promote.get();

        Set<String> inMaster = master.findAll().stream().map(DeviceRow::deviceName).collect(Collectors.toSet());
        List<String> pending = master.pendingRemovals().stream().filter(name -> !inMaster.contains(name)).toList();

        // WHY: read only when it can decide something. Frontier's names never consult it, and most Applies remove
        // nothing - reading it then is wasted, and its warning would describe a hold-back that never happened.
        boolean anyUsersName = pending.stream().anyMatch(name -> !stock.ships(name));
        Split split = split(pending, anyUsersName ? readBindsNames() : Optional.empty());

        Report report = push.apply(split.removals());
        List<String> cleanedUp = List.of();
        List<String> notEverywhere = List.of();
        if (!split.removals().isEmpty()) {
            if (everyInstallationTookIt(report)) {
                split.removals().forEach(master::clearPendingRemoval);
                cleanedUp = List.copyOf(split.removals());
            } else {
                notEverywhere = List.copyOf(split.removals());
            }
        }
        return new Result(removed, report, cleanedUp, notEverywhere, split.heldBack(), split.bindsUnread());
    }

    /** The names {@code .binds} gives devices, folded to lower case - or empty when they cannot be read. */
    private Optional<Set<String>> readBindsNames() {
        try {
            return Optional.of(folded(bindsNames.read()));
        } catch (IOException e) {
            log.warn("Could not read .binds, so no entry of the user's is removed by this Apply: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Which pending removals this Apply can carry out, and which wait for a {@code .binds} rewrite. Frontier's names
     * go either way: their element stays, and a label is nothing a binding needs.
     *
     * @param named the names {@code .binds} gives devices, or empty when they could not be read - or were not read,
     *              because no name in {@code pending} is the user's
     */
    private Split split(List<String> pending, Optional<Set<String>> named) {
        Set<String> removals = new TreeSet<>();
        List<String> heldBack = new ArrayList<>();
        boolean bindsUnread = false;
        for (String name : pending) {
            if (stock.ships(name)) {
                removals.add(name);
            } else if (named.isEmpty()) {
                heldBack.add(name);
                bindsUnread = true;
            } else if (named.get().contains(name.toLowerCase(Locale.ROOT))) {
                // WHY: compared ignoring case. A hand-edited .binds can spell a name otherwise, and holding back an
                // entry that might be in use costs a leftover; removing one that is costs the whole preset.
                heldBack.add(name);
            } else {
                removals.add(name);
            }
        }
        return new Split(removals, heldBack, bindsUnread);
    }

    private record Split(Set<String> removals, List<String> heldBack, boolean bindsUnread) {
    }

    private static boolean everyInstallationTookIt(Report report) {
        return !report.installations().isEmpty() && report.installations().stream()
                .allMatch(result -> result.outcome() == Outcome.WRITTEN || result.outcome() == Outcome.UNCHANGED);
    }

    private static Set<String> folded(Set<String> names) {
        return names.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}
