package elite.intel.bindforge.devicefiles;

import elite.intel.bindforge.devicefiles.DeviceFilesPush.MasterDevice;
import elite.intel.bindforge.devicefiles.DeviceFilesPush.Outcome;
import elite.intel.bindforge.devicefiles.DeviceFilesPush.Report;
import elite.intel.bindforge.devicefiles.DeviceFilesPush.Target;
import elite.intel.db.dao.BindForgeDeviceMasterDao.DeviceRow;
import elite.intel.db.managers.BindForgeDeviceDraftManager;
import elite.intel.db.managers.BindForgeDeviceMasterManager;
import elite.intel.io.TimestampedBackups;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Apply for the device files: the draft becomes the master first, so a write that fails loses none of the
 * user's edits - and the pending removals leave every installation, unless a binding still names them.
 */
class DeviceApplyTest {

    private static final String DEVICE_MAPPINGS = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <Root>
            \t<T-Rudder><PID>B679</PID><VID>044F</VID></T-Rudder>
            </Root>
            """;

    private final BindForgeDeviceMasterManager master = BindForgeDeviceMasterManager.getInstance();
    private final BindForgeDeviceDraftManager draft = BindForgeDeviceDraftManager.getInstance();

    @TempDir
    Path temp;

    @BeforeEach
    void anEmptyMasterAndNoDraft() {
        draft.discard();
        master.findAll().forEach(row -> master.remove(row.id()));
        master.pendingRemovals().forEach(master::clearPendingRemoval);
    }

    @Test
    void theDraftIsPromotedBeforeAnythingIsPushed() {
        List<String> order = new ArrayList<>();
        Report empty = new Report(List.of());

        DeviceApply.Result result = new DeviceApply(
                () -> { order.add("promote"); return List.of("RVWAP"); },
                removals -> { order.add("push"); return empty; },
                Set::of, FrontierStockDevices.getInstance()).apply();

        assertEquals(List.of("promote", "push"), order);
        assertEquals(List.of("RVWAP"), result.removed(), "what the draft removed is handed to the caller");
    }

    @Test
    void savedEditsReachEveryInstallation() throws IOException {
        Target steam = steamInstallation();
        long row = draft.record("RVWAP", "3344", "03F5", true).id();
        draft.replaceLabels(row, Map.of("Joy_1", "RV Main Trigger"));

        DeviceApply.Result result = apply(List.of(steam), Set.of());

        assertEquals(Outcome.WRITTEN, result.report().installations().getFirst().outcome());
        assertTrue(Files.readString(steam.controlSchemes().resolve("DeviceMappings.xml")).contains("<RVWAP>"));
        assertFalse(draft.isStarted(), "applied edits are no longer a draft");
    }

    /**
     * The point of promoting first (Alan, 2026-10-04): an installation that cannot be written is reported, and
     * the edits stay in the master for the next Apply rather than being lost with the failed write.
     */
    @Test
    void anInstallationThatCannotBeReachedLosesNoEdits() {
        draft.record("RVWAP", "3344", "03F5", true);

        DeviceApply.Result result = apply(List.of(unplugged()), Set.of());

        assertEquals(Outcome.SKIPPED_MISSING, result.report().installations().getFirst().outcome());
        assertNotNull(master.findByName("RVWAP"), "the edit is in the master, waiting for the next Apply");
    }

    /** With nothing saved, Apply pushes the master as it stands - the retry after a partial Apply. */
    @Test
    void withNoDraftTheMasterIsStillPushed() throws IOException {
        Target steam = steamInstallation();
        master.record("LVWAP", "3344", "83F4", true);

        DeviceApply.Result result = apply(List.of(steam), Set.of());

        assertTrue(result.removed().isEmpty());
        assertEquals(Outcome.WRITTEN, result.report().installations().getFirst().outcome());
        assertTrue(Files.readString(steam.controlSchemes().resolve("DeviceMappings.xml")).contains("<LVWAP>"));
    }

    /** CLEAR, then APPLY: the device leaves every installation, and is no longer pending (A10). */
    @Test
    void aClearedDeviceLeavesEveryInstallationAndStopsBeingPending() throws IOException {
        Target steam = steamInstallation();
        lvwapAppliedTo(steam);
        draft.removeByName("LVWAP");

        DeviceApply.Result result = apply(List.of(steam), Set.of());

        assertEquals(List.of("LVWAP"), result.removed());
        assertEquals(List.of("LVWAP"), result.cleanedUp());
        assertFalse(Files.readString(steam.controlSchemes().resolve("DeviceMappings.xml")).contains("LVWAP"));
        assertFalse(Files.exists(buttonMap(steam, "LVWAP")));
        assertTrue(master.pendingRemovals().isEmpty());
    }

    /**
     * Without its entry the game rejects the whole preset, so an entry the bindings name stays - compared ignoring
     * case - and stays pending until a .binds rewrite can move them (Alan, 2026-10-11).
     */
    @Test
    void anEntryTheBindingsNameIsHeldBackAndStaysPending() throws IOException {
        Target steam = steamInstallation();
        lvwapAppliedTo(steam);
        draft.removeByName("LVWAP");

        DeviceApply.Result result = apply(List.of(steam), Set.of("lvwap"));

        assertEquals(List.of("LVWAP"), result.heldBack());
        assertTrue(result.cleanedUp().isEmpty());
        assertTrue(Files.readString(steam.controlSchemes().resolve("DeviceMappings.xml")).contains("<LVWAP>"));
        assertTrue(Files.exists(buttonMap(steam, "LVWAP")));
        assertEquals(List.of("LVWAP"), master.pendingRemovals());
    }

    @Test
    void bindingsThatCannotBeReadHoldEveryRemovalBack() throws IOException {
        Target steam = steamInstallation();
        lvwapAppliedTo(steam);
        draft.removeByName("LVWAP");

        DeviceApply.Result result = new DeviceApply(draft::promote, pusher(List.of(steam))::push,
                () -> { throw new IOException("locked"); }, FrontierStockDevices.getInstance()).apply();

        assertTrue(result.bindsUnread());
        assertEquals(List.of("LVWAP"), result.heldBack());
        assertTrue(Files.readString(steam.controlSchemes().resolve("DeviceMappings.xml")).contains("<LVWAP>"));
        assertEquals(List.of("LVWAP"), master.pendingRemovals());
    }

    /** An installation the removal could not reach keeps it pending, so the next Apply finishes the job. */
    @Test
    void aRemovalAnInstallationMissedStaysPending() throws IOException {
        Target steam = steamInstallation();
        lvwapAppliedTo(steam);
        draft.removeByName("LVWAP");

        DeviceApply.Result result = apply(List.of(steam, unplugged()), Set.of());

        assertEquals(List.of("LVWAP"), result.notEverywhere());
        assertTrue(result.cleanedUp().isEmpty());
        assertFalse(Files.readString(steam.controlSchemes().resolve("DeviceMappings.xml")).contains("LVWAP"),
                "the installation that was reachable lost it");
        assertEquals(List.of("LVWAP"), master.pendingRemovals());
    }

    /**
     * Review, 2026-10-11: RESET LABELS on a built-in the bindings name still takes effect. Frontier's element stays,
     * and .binds names inputs by token, never by label, so nothing a binding needs is lost.
     */
    @Test
    void aResetBuiltInIsAppliedEvenWhenTheBindingsNameIt() throws IOException {
        Target steam = steamInstallation();
        long id = master.record("T-Rudder", "044F", "B679", true).id();
        master.replaceLabels(id, Map.of("Joy_XAxis", "RUDDER"));
        apply(List.of(steam), Set.of());
        draft.removeByName("T-Rudder");

        DeviceApply.Result result = apply(List.of(steam), Set.of("T-Rudder"));

        assertEquals(List.of("T-Rudder"), result.cleanedUp());
        assertTrue(result.heldBack().isEmpty());
        assertFalse(Files.exists(buttonMap(steam, "T-Rudder")));
        assertEquals(DEVICE_MAPPINGS, Files.readString(steam.controlSchemes().resolve("DeviceMappings.xml")),
                "Frontier's element is left exactly as it was");
        assertTrue(master.pendingRemovals().isEmpty());
    }

    /** Unreadable bindings hold back the user's entries, never a reset - it needs nothing from them. */
    @Test
    void unreadableBindingsDoNotHoldBackAReset() throws IOException {
        Target steam = steamInstallation();
        long id = master.record("T-Rudder", "044F", "B679", true).id();
        master.replaceLabels(id, Map.of("Joy_XAxis", "RUDDER"));
        apply(List.of(steam), Set.of());
        draft.removeByName("T-Rudder");

        DeviceApply.Result result = new DeviceApply(draft::promote, pusher(List.of(steam))::push,
                () -> { throw new IOException("locked"); }, FrontierStockDevices.getInstance()).apply();

        assertFalse(result.bindsUnread(), "nothing was held back for it");
        assertEquals(List.of("T-Rudder"), result.cleanedUp());
    }

    private void lvwapAppliedTo(Target steam) {
        long id = master.record("LVWAP", "3344", "83F4", true).id();
        master.replaceLabels(id, Map.of("Joy_1", "LV MAIN TRIGGER"));
        apply(List.of(steam), Set.of());
        assertTrue(Files.exists(buttonMap(steam, "LVWAP")));
    }

    private DeviceApply.Result apply(List<Target> targets, Set<String> namedInBinds) {
        return new DeviceApply(draft::promote, pusher(targets)::push, () -> namedInBinds,
                FrontierStockDevices.getInstance()).apply();
    }

    private DeviceFilesPush pusher(List<Target> targets) {
        return new DeviceFilesPush(() -> targets, this::storedMaster,
                FrontierStockDevices.getInstance(), new TimestampedBackups(), temp.resolve("history"), () -> 10,
                target -> { });
    }

    private List<MasterDevice> storedMaster() {
        List<MasterDevice> devices = new ArrayList<>();
        for (DeviceRow row : master.findAll()) {
            devices.add(new MasterDevice(row.deviceName(), row.vid(), row.pid(), master.labelsOf(row.id())));
        }
        return devices;
    }

    private Target unplugged() {
        return new Target(2, "EPIC", temp.resolve("unplugged").resolve("ControlSchemes"), false);
    }

    private Target steamInstallation() throws IOException {
        Path controlSchemes = temp.resolve("STEAM").resolve("ControlSchemes");
        Files.createDirectories(controlSchemes);
        Files.writeString(controlSchemes.resolve("DeviceMappings.xml"), DEVICE_MAPPINGS);
        return new Target(1, "STEAM", controlSchemes, false);
    }

    private static Path buttonMap(Target target, String device) {
        return target.controlSchemes().resolve("DeviceButtonMaps").resolve(device + ".buttonMap");
    }
}
