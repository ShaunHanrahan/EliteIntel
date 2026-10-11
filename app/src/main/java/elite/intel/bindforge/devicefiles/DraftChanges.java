package elite.intel.bindforge.devicefiles;

import elite.intel.db.managers.BindForgeDeviceDraftManager;
import elite.intel.db.managers.BindForgeDeviceDraftManager.Stored;
import elite.intel.db.managers.BindForgeDeviceDraftManager.StoredDevice;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Which devices the draft changes - what separates saved, unapplied work from a draft that was only started.
 * <p>
 * <strong>A started draft may hold no edits</strong>: the editor starts one before reading the rows it changes, so
 * "a draft exists" is not "the user changed something". The screen's DISCARD, its <em>saved, not applied</em> line
 * and the unsaved-work prompt all need the second, and this is the one definition of it (Alan, 2026-10-11, A10).
 * <p>
 * Pure: the caller reads the master and the draft ({@link BindForgeDeviceDraftManager#stored()}).
 */
public final class DraftChanges {

    private DraftChanges() {
    }

    /** The changes in the stored draft, read now. */
    public static List<String> stored() {
        return of(BindForgeDeviceDraftManager.getInstance().stored());
    }

    /**
     * The devices the draft adds, removes or changes against the master, by name, sorted - a renamed device by its
     * new name. Empty when there is no draft, or when it matches the master.
     * <p>
     * A device is changed when its name, its hardware or any label differs. Whether its name has been seen is not
     * compared: nothing reads that flag any more.
     */
    public static List<String> of(Stored stored) {
        if (stored.draft() == null) return List.of();
        Map<Long, StoredDevice> master = stored.master().stream()
                .collect(Collectors.toMap(StoredDevice::masterId, device -> device));
        Set<String> changed = new TreeSet<>();
        Set<Long> kept = new HashSet<>();
        for (StoredDevice device : stored.draft()) {
            StoredDevice was = device.masterId() == null ? null : master.get(device.masterId());
            if (was == null) {
                changed.add(device.deviceName());
                continue;
            }
            kept.add(device.masterId());
            if (!same(was, device)) changed.add(device.deviceName());
        }
        master.forEach((id, device) -> {
            if (!kept.contains(id)) changed.add(device.deviceName());
        });
        return List.copyOf(changed);
    }

    private static boolean same(StoredDevice master, StoredDevice draft) {
        return master.deviceName().equals(draft.deviceName())
                && Objects.equals(master.vid(), draft.vid())
                && Objects.equals(master.pid(), draft.pid())
                && master.labels().equals(draft.labels());
    }
}
