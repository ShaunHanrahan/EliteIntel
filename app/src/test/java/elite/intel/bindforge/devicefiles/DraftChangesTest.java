package elite.intel.bindforge.devicefiles;

import elite.intel.db.managers.BindForgeDeviceDraftManager.Stored;
import elite.intel.db.managers.BindForgeDeviceDraftManager.StoredDevice;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A started draft may hold no edits. DISCARD, the "saved, not applied" line and the unsaved-work prompt need to know
 * which devices it actually changes (A10).
 */
class DraftChangesTest {

    private static final StoredDevice LVWAP_MASTER =
            new StoredDevice(1L, "LVWAP", "3344", "83F4", true, null, Map.of("Joy_1", "TRIGGER"));
    private static final StoredDevice RVWAP_MASTER =
            new StoredDevice(2L, "RVWAP", "3344", "03F5", true, null, Map.of());

    @Test
    void noDraftChangesNothing() {
        assertTrue(DraftChanges.of(new Stored(List.of(LVWAP_MASTER), null)).isEmpty());
    }

    @Test
    void aDraftStartedAndNotEditedChangesNothing() {
        Stored stored = new Stored(List.of(LVWAP_MASTER, RVWAP_MASTER), List.of(LVWAP_MASTER, RVWAP_MASTER));

        assertTrue(DraftChanges.of(stored).isEmpty());
    }

    @Test
    void aChangedLabelIsAChange() {
        StoredDevice edited = new StoredDevice(1L, "LVWAP", "3344", "83F4", true, null, Map.of("Joy_1", "FIRE"));

        assertEquals(List.of("LVWAP"),
                DraftChanges.of(new Stored(List.of(LVWAP_MASTER, RVWAP_MASTER), List.of(edited, RVWAP_MASTER))));
    }

    @Test
    void aClearedDeviceIsAChangeUnderItsMasterName() {
        assertEquals(List.of("RVWAP"),
                DraftChanges.of(new Stored(List.of(LVWAP_MASTER, RVWAP_MASTER), List.of(LVWAP_MASTER))));
    }

    @Test
    void aDeviceAddedInTheDraftIsAChange() {
        StoredDevice added = new StoredDevice(null, "T-Rudder", "044F", "B679", true, null, Map.of("Joy_XAxis", "X"));

        assertEquals(List.of("T-Rudder"),
                DraftChanges.of(new Stored(List.of(LVWAP_MASTER), List.of(LVWAP_MASTER, added))));
    }

    /** A device cleared and saved again is one change, not two. */
    @Test
    void aDeviceClearedAndSavedAgainIsNamedOnce() {
        StoredDevice again = new StoredDevice(null, "LVWAP", "3344", "83F4", true, null, Map.of("Joy_1", "TRIGGER"));

        assertEquals(List.of("LVWAP"), DraftChanges.of(new Stored(List.of(LVWAP_MASTER), List.of(again))));
    }

    @Test
    void otherHardwareIsAChange() {
        StoredDevice retargeted = new StoredDevice(1L, "LVWAP", "3344", "83F5", true, null, Map.of("Joy_1", "TRIGGER"));

        assertEquals(List.of("LVWAP"), DraftChanges.of(new Stored(List.of(LVWAP_MASTER), List.of(retargeted))));
    }
}
