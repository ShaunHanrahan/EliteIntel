package elite.intel.bindforge.devicefiles;

import elite.intel.bindforge.devicefiles.DeviceFilesPush.Outcome;
import elite.intel.bindforge.devicefiles.DeviceFilesPush.Report;
import elite.intel.bindforge.io.DeviceRewriteResult;
import elite.intel.db.dao.BindForgeDeviceMasterDao.DeviceRow;
import elite.intel.db.managers.BindForgeDeviceDraftManager;
import elite.intel.db.managers.BindForgeDeviceMasterManager;
import elite.intel.session.PlayerSession;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>
 * <strong>Bindings that name a master device by hardware id move to its name</strong> once the push has put the
 * entry in at least one installation (Alan, 2026-10-14, A6b) - onboarding a controller {@code .binds} names by hex
 * waits for exactly this. The user's Apply comes first: an installation that missed the entry does not hold the move
 * back, it is reported and caught up later. When no installation took it, nothing was applied for the bindings to
 * follow, and they stay as they are for the next Apply. One of Frontier's names is never a target.
 */
public final class DeviceApply {

    private static final Logger log = LogManager.getLogger(DeviceApply.class);

    /** The names {@code .binds} gives devices, read when Apply runs. */
    @FunctionalInterface
    interface BindsNames {
        Set<String> read() throws IOException;
    }

    /** Moves the bindings that name a device by hardware id - {@link BindsHardwareIdRewrite} in use. */
    interface HardwareIdRewrite {
        /** As {@link BindsDeviceReferences#hardwareIdCounts(java.nio.file.Path)}: strict. */
        Map<String, Integer> hardwareIdCounts() throws IOException;

        /** Every reference whose value is one of {@code found} becomes {@code name}, all or nothing. */
        Rewritten rewrite(String name, Set<String> found);
    }

    /**
     * @param status   how the rewrite ended
     * @param bindings how many bindings in the game's own {@code .binds} it moved - working copies not counted, as
     *                 they hold the same bindings
     */
    record Rewritten(DeviceRewriteResult.Status status, int bindings) {
    }

    /** What became of the bindings naming one master device by hardware id. */
    public enum MoveOutcome {
        /** They name the device now. */
        MOVED,
        /** No installation took the entry, so they were left on the hardware id for the next Apply. */
        NOT_APPLIED,
        /** Gone by the time the rewrite ran - the game saved {@code .binds} in between - so nothing was written. */
        NOT_FOUND,
        /** The rewrite stopped and put everything back - {@link Move#status()} says why. */
        FAILED
    }

    /**
     * @param bindings how many bindings moved, when they did; otherwise how many named the device by hardware id
     * @param status   the rewrite's status when it ran, else {@code null}
     */
    public record Move(String name, int bindings, MoveOutcome outcome, DeviceRewriteResult.Status status) {
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
     * @param moves         the bindings that named a master device by hardware id, and what became of them
     * @param movesUnread   whether {@code .binds} could not be read to find such bindings, so none was moved
     */
    public record Result(List<String> removed, Report report, List<String> cleanedUp, List<String> notEverywhere,
                         List<String> heldBack, boolean bindsUnread, List<Move> moves, boolean movesUnread) {
        public Result {
            removed = List.copyOf(removed);
            Objects.requireNonNull(report, "report");
            cleanedUp = List.copyOf(cleanedUp);
            notEverywhere = List.copyOf(notEverywhere);
            heldBack = List.copyOf(heldBack);
            moves = List.copyOf(moves);
        }
    }

    private final Supplier<List<String>> promote;
    private final Function<Set<String>, Report> push;
    private final BindsNames bindsNames;
    private final FrontierStockDevices stock;
    private final BindForgeDeviceMasterManager master;
    private final HardwareIdRewrite hardwareIds;

    /**
     * Promotes the stored draft and pushes to the stored installations.
     *
     * @throws IOException if Elite-Intel's data folder cannot be created for Edit History
     */
    public DeviceApply() throws IOException {
        this(BindForgeDeviceDraftManager.getInstance()::promote, new DeviceFilesPush()::push,
                () -> BindsDeviceReferences.referenceCounts(PlayerSession.getInstance().getBindingsDir()).keySet(),
                FrontierStockDevices.getInstance(), BindForgeDeviceMasterManager.getInstance(),
                BindsHardwareIdRewrite.stored(PlayerSession.getInstance().getBindingsDir()));
    }

    /** With nothing named by hardware id - for the tests that are about removals. */
    DeviceApply(Supplier<List<String>> promote, Function<Set<String>, Report> push, BindsNames bindsNames,
                FrontierStockDevices stock, BindForgeDeviceMasterManager master) {
        this(promote, push, bindsNames, stock, master, NOTHING_BY_HARDWARE_ID);
    }

    /** @param bindsNames must throw rather than answer when it cannot be sure - a missing folder is not "none" */
    DeviceApply(Supplier<List<String>> promote, Function<Set<String>, Report> push, BindsNames bindsNames,
                FrontierStockDevices stock, BindForgeDeviceMasterManager master, HardwareIdRewrite hardwareIds) {
        this.promote = promote;
        this.push = push;
        this.bindsNames = bindsNames;
        this.stock = stock;
        this.master = master;
        this.hardwareIds = hardwareIds;
    }

    private static final HardwareIdRewrite NOTHING_BY_HARDWARE_ID = new HardwareIdRewrite() {
        @Override
        public Map<String, Integer> hardwareIdCounts() {
            return Map.of();
        }

        @Override
        public Rewritten rewrite(String name, Set<String> found) {
            throw new IllegalStateException("nothing is named by hardware id");
        }
    };

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
        Moves moves = moveHardwareIds(report);
        return new Result(removed, report, cleanedUp, notEverywhere, split.heldBack(), split.bindsUnread(),
                moves.moves(), moves.unread());
    }

    /**
     * The bindings naming a master device of the user's by hardware id, moved to its name - or left, when no
     * installation took the master. Where two master devices claim one VID/PID, the first in the master's order is
     * the one the push leads each file with, and so the one the game resolves.
     * <p>
     * One rewrite per device, back to back (Alan, 2026-10-14). Each ends on the game's {@code .binds}, which the
     * bindings monitor reloads, so a second device's rewrite can meet a working copy mid-reload and stop as
     * {@code STALE_FILE}. That device is reported as failed and, its bindings still on the hardware id, moved by the
     * next Apply. One pass for several devices would need a rewrite with several targets, which {@code bindforge.io}
     * does not offer.
     */
    private Moves moveHardwareIds(Report report) {
        Map<String, Integer> counts;
        try {
            counts = hardwareIds.hardwareIdCounts();
        } catch (IOException e) {
            log.warn("Could not read .binds, so no binding naming a device by hardware id was moved: {}",
                    e.getMessage());
            return new Moves(List.of(), true);
        }
        if (counts.isEmpty()) return new Moves(List.of(), false);

        boolean applied = report.matchingCount() > 0;
        Set<String> claimed = new HashSet<>();
        List<Move> moves = new ArrayList<>();
        for (DeviceRow row : master.findAll()) {
            if (stock.ships(row.deviceName()) || row.vid() == null || row.pid() == null) continue;
            Map<String, Integer> found =
                    new LinkedHashMap<>(BindsDeviceReferences.hardwareIdsFor(row.vid(), row.pid(), counts));
            found.keySet().removeIf(id -> !claimed.add(id.toUpperCase(Locale.ROOT)));
            if (found.isEmpty()) continue;
            int bindings = found.values().stream().mapToInt(Integer::intValue).sum();
            if (!applied) {
                moves.add(new Move(row.deviceName(), bindings, MoveOutcome.NOT_APPLIED, null));
                continue;
            }
            Rewritten result = hardwareIds.rewrite(row.deviceName(), found.keySet());
            moves.add(switch (result.status()) {
                case REWRITTEN -> new Move(row.deviceName(), result.bindings(), MoveOutcome.MOVED, result.status());
                case NO_REFERENCES -> new Move(row.deviceName(), bindings, MoveOutcome.NOT_FOUND, result.status());
                default -> {
                    log.warn("Could not move the bindings naming {} by hardware id {} to it: {}", row.deviceName(),
                            found.keySet(), result.status());
                    yield new Move(row.deviceName(), bindings, MoveOutcome.FAILED, result.status());
                }
            });
        }
        return new Moves(moves, false);
    }

    private record Moves(List<Move> moves, boolean unread) {
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
