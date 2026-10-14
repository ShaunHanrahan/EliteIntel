package elite.intel.bindforge.devicefiles;

import elite.intel.bindforge.devicefiles.DeviceEntry.HardwareId;
import elite.intel.bindforge.devicefiles.DeviceFilesPush.Report;
import elite.intel.bindforge.devicefiles.DeviceFilesPush.Target;
import elite.intel.db.managers.BindForgeDeviceDraftManager.StoredDevice;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Activation, the first opening of the BindForge tab (Alan, 2026-10-13): a "before BindForge" backup once, then the
 * master filled with no prompt when the installations are identical, and first setup when they are not.
 */
class ActivationTest {

    private static final HardwareId LEFT = new HardwareId("3344", "83F4");

    @TempDir
    Path temp;

    private final List<String> order = new ArrayList<>();
    private boolean activated;
    private String backupTaken;
    private boolean backupWorks = true;
    private boolean pushWouldWrite;

    @Test
    void anActivatedBindForgeIsLeftAlone() throws IOException {
        activated = true;

        Activation.Result result = activation(List.of(reachable(1)), sameEverywhere()).run();

        assertEquals(Activation.Outcome.ALREADY_ACTIVATED, result.outcome());
        assertTrue(order.isEmpty(), "no backup, no read, nothing said");
    }

    @Test
    void identicalInstallationsAreBackedUpToldAndActivatedWithNoPrompt() throws IOException {
        Activation.Result result = activation(List.of(reachable(1), reachable(2)), sameEverywhere()).run();

        assertEquals(Activation.Outcome.ACTIVATED, result.outcome());
        assertEquals(List.of("backup", "announce", "establish [LVWAP]"), order);
    }

    /** The backup is taken once ever: first setup can be abandoned and opened again on a later run. */
    @Test
    void aBackupAlreadyTakenIsNotTakenOrAnnouncedAgain() throws IOException {
        backupTaken = temp.resolve("earlier").toString();

        Activation.Result result = activation(List.of(reachable(1)), sameEverywhere()).run();

        assertEquals(Activation.Outcome.ACTIVATED, result.outcome());
        assertEquals(List.of("establish [LVWAP]"), order);
    }

    @Test
    void aBackupThatFailsStopsActivation() throws IOException {
        backupWorks = false;

        Activation.Result result = activation(List.of(reachable(1)), sameEverywhere()).run();

        assertEquals(Activation.Outcome.BACKUP_FAILED, result.outcome());
        assertTrue(result.reason().contains("disk full"));
        assertEquals(List.of("backup"), order, "nothing said, nothing activated");
    }

    /** Any difference opens first setup - even one with nothing to ask, such as a file only the push would add. */
    @Test
    void aDifferenceTheFillWouldWriteOpensFirstSetup() throws IOException {
        pushWouldWrite = true;

        Activation.Result result = activation(List.of(reachable(1), reachable(2)), sameEverywhere()).run();

        assertEquals(Activation.Outcome.FIRST_SETUP, result.outcome());
        assertNotNull(result.reading());
        assertNotNull(result.setup());
        assertEquals(List.of("backup", "announce"), order, "first setup writes nothing until its last button");
    }

    @Test
    void aQuestionOpensFirstSetup() throws IOException {
        Activation.Result result = activation(List.of(reachable(1), reachable(2)),
                target -> new FirstSetupPlan.Installation(target.installId(), target.storefront(),
                        Map.of("LVWAP", target.installId() == 1 ? LEFT : new HardwareId("3344", "83F3")),
                        Map.of(), Map.of())).run();

        assertEquals(Activation.Outcome.FIRST_SETUP, result.outcome());
        assertFalse(order.contains("establish [LVWAP]"));
    }

    /** An unread file could hold the one entry the master must not lose, so it is never activated past. */
    @Test
    void anUnreadableInstallationOpensFirstSetupNamingIt() throws IOException {
        Activation.Result result = activation(List.of(reachable(1)), target -> {
            throw new IOException("DeviceMappings.xml is not well-formed");
        }).run();

        assertEquals(Activation.Outcome.FIRST_SETUP, result.outcome());
        assertNull(result.reading());
        assertTrue(result.reason().contains("STEAM"), "the reason names the installation");
        assertEquals(List.of("backup", "announce"), order);
    }

    @Test
    void noInstallationActivatesWithAnEmptyMaster() {
        Activation.Result result = activation(List.of(), sameEverywhere()).run();

        assertEquals(Activation.Outcome.ACTIVATED, result.outcome());
        assertEquals(List.of("backup", "announce", "establish []"), order);
    }

    @Test
    void anInstallationWhoseFolderIsGoneIsLeftOutOfIdentical() {
        pushWouldWrite = true;
        Target gone = new Target(2, "EPIC", temp.resolve("unplugged"), false);
        Activation activation = new Activation(() -> activated, () -> List.of(gone), backup(), folder -> { },
                () -> firstSetup(List.of(gone), sameEverywhere()), (target, master) -> {
                    order.add("planned " + target.storefront());
                    return pushWouldWrite;
                }, establish());

        assertEquals(Activation.Outcome.ACTIVATED, activation.run().outcome());
        assertFalse(order.contains("planned EPIC"));
    }

    /** Against the real push: Frontier's file in both and nothing of the user's is identical; no file at all is not. */
    @Test
    void identicalIsWhatThePushWouldWrite() throws IOException {
        Target steam = withFile(1, "STEAM", true);
        Target epic = withFile(2, "EPIC", true);
        Target bare = withFile(3, "FRONTIER", false);
        Activation.PushPlanner push = (target, master) ->
                !DeviceFilesPush.plan(target, master, FrontierStockDevices.getInstance()).writes().isEmpty();

        assertTrue(Activation.identical(reading(List.of(steam, epic)), push));
        assertFalse(Activation.identical(reading(List.of(steam, bare)), push),
                "the push would seed the missing file from Frontier's");
    }

    private Activation activation(List<Target> targets, FirstSetup.Reader reader) {
        return new Activation(() -> activated, () -> targets, backup(), folder -> order.add("announce"),
                () -> firstSetup(targets, reader), (target, master) -> pushWouldWrite, establish());
    }

    private Activation.BeforeBackup backup() {
        return new Activation.BeforeBackup() {
            @Override
            public String taken() {
                return backupTaken;
            }

            @Override
            public Path take(List<Target> targets) throws IOException {
                order.add("backup");
                if (!backupWorks) throw new IOException("disk full");
                backupTaken = temp.resolve("before").toString();
                return Path.of(backupTaken);
            }
        };
    }

    private FirstSetup.Establish establish() {
        return (devices, removals) -> {
            order.add("establish " + devices.stream().map(StoredDevice::deviceName).toList());
            activated = true;
            return true;
        };
    }

    private static FirstSetup firstSetup(List<Target> targets, FirstSetup.Reader reader) {
        return new FirstSetup(() -> targets, reader, Set::of, taken -> null, (devices, removals) -> false,
                () -> new Report(List.of()));
    }

    private static FirstSetup.Reader sameEverywhere() {
        return target -> new FirstSetupPlan.Installation(target.installId(), target.storefront(),
                Map.of("LVWAP", LEFT), Map.of(), Map.of());
    }

    private static FirstSetup.Reading reading(List<Target> targets) throws IOException {
        return firstSetup(targets, target -> new FirstSetupPlan.Installation(target.installId(), target.storefront(),
                Map.of(), Map.of(), Map.of())).read();
    }

    private Target reachable(long id) throws IOException {
        String storefront = id == 1 ? "STEAM" : "EPIC";
        return new Target(id, storefront, Files.createDirectories(temp.resolve(storefront)), false);
    }

    private Target withFile(long id, String storefront, boolean stockFile) throws IOException {
        Path controlSchemes = Files.createDirectories(temp.resolve(storefront).resolve("ControlSchemes"));
        if (stockFile) Files.write(controlSchemes.resolve("DeviceMappings.xml"), DeviceFilesPush.stockFile());
        return new Target(id, storefront, controlSchemes, false);
    }
}
