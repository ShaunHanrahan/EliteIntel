package elite.intel.bindforge.devicefiles;

import elite.intel.bindforge.devicefiles.DeviceFilesPush.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restore point meaning "just before I standardised": every installation's device files, as found.
 */
class DeviceFilesSnapshotTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T14:03:09Z"), ZoneOffset.UTC);

    @TempDir
    Path temp;

    @Test
    void everyInstallationsFilesAreCopiedUnderOneLabelledFolder() throws IOException {
        Target steam = installation(1, "STEAM", "<Root><LVWAP/></Root>");
        Target epic = installation(2, "EPIC", "<Root/>");
        Files.createDirectories(steam.controlSchemes().resolve("DeviceButtonMaps"));
        Files.writeString(steam.controlSchemes().resolve("DeviceButtonMaps").resolve("LVWAP.buttonMap"), "<Root/>");

        Path folder = new DeviceFilesSnapshot(temp.resolve("backups"), CLOCK).take("first-setup", List.of(steam, epic));

        assertEquals("2026-10-07_14-03-09_first-setup", folder.getFileName().toString());
        assertEquals("<Root><LVWAP/></Root>", Files.readString(folder.resolve("1-STEAM").resolve("DeviceMappings.xml")));
        assertTrue(Files.isRegularFile(folder.resolve("1-STEAM").resolve("DeviceButtonMaps").resolve("LVWAP.buttonMap")));
        assertEquals("<Root/>", Files.readString(folder.resolve("2-EPIC").resolve("DeviceMappings.xml")));
    }

    @Test
    void anUnreachableInstallationIsLeftOutAndASecondSnapshotDoesNotOverwriteTheFirst() throws IOException {
        Target steam = installation(1, "STEAM", "<Root/>");
        Target gone = new Target(2, "EPIC", temp.resolve("unplugged").resolve("ControlSchemes"), false);
        DeviceFilesSnapshot snapshot = new DeviceFilesSnapshot(temp.resolve("backups"), CLOCK);

        Path first = snapshot.take("first-setup", List.of(steam, gone));
        Path second = snapshot.take("first-setup", List.of(steam, gone));

        assertFalse(Files.exists(first.resolve("2-EPIC")));
        assertNotEquals(first, second);
    }

    /** Activation's "before BindForge" backup: all of the user's input data, the bindings folder included. */
    @Test
    void theBindingsFoldersPresetsAndStartPresetAreCopiedAndNothingElse() throws IOException {
        Target steam = installation(1, "STEAM", "<Root/>");
        Path bindings = Files.createDirectories(temp.resolve("Bindings"));
        Files.writeString(bindings.resolve("Custom.4.2.binds"), "<Root/>");
        Files.writeString(bindings.resolve("StartPreset.4.start"), "Custom\nCustom\nCustom\nCustom");
        Files.writeString(bindings.resolve("StartPreset.start"), "Custom");
        Files.writeString(bindings.resolve("notes.txt"), "not input data");
        Path proton = Files.createDirectories(temp.resolve("ProtonBindings"));
        Files.writeString(proton.resolve("Other.binds"), "<Root/>");

        Path folder = new DeviceFilesSnapshot(temp.resolve("backups"), CLOCK)
                .take("before-bindforge", List.of(steam), List.of(bindings, temp.resolve("never-made"), proton));

        Path copied = folder.resolve("bindings");
        assertTrue(Files.isRegularFile(copied.resolve("Custom.4.2.binds")));
        assertTrue(Files.isRegularFile(copied.resolve("StartPreset.4.start")));
        assertTrue(Files.isRegularFile(copied.resolve("StartPreset.start")));
        assertFalse(Files.exists(copied.resolve("notes.txt")));
        assertTrue(Files.isRegularFile(folder.resolve("bindings-2").resolve("Other.binds")),
                "a missing folder takes no number");
        assertTrue(Files.isRegularFile(folder.resolve("1-STEAM").resolve("DeviceMappings.xml")));
    }

    /** A half-written snapshot would pass for a restore point in the backups list, so it is removed. */
    @Test
    void aCopyThatFailsLeavesNoSnapshotBehind() throws IOException {
        Target steam = installation(1, "STEAM", "<Root/>");
        // WHY: a second target with the same id copies to the same path, so its copy fails part way through.
        Target clash = new Target(1, "STEAM", installation(2, "OTHER", "<Root/>").controlSchemes(), false);
        Path backups = temp.resolve("backups");

        assertThrows(IOException.class,
                () -> new DeviceFilesSnapshot(backups, CLOCK).take("first-setup", List.of(steam, clash)));

        try (var left = Files.list(backups)) {
            assertEquals(0, left.count());
        }
    }

    private Target installation(long id, String storefront, String deviceMappings) throws IOException {
        Path controlSchemes = Files.createDirectories(temp.resolve(storefront).resolve("ControlSchemes"));
        Files.writeString(controlSchemes.resolve("DeviceMappings.xml"), deviceMappings, StandardCharsets.UTF_8);
        return new Target(id, storefront, controlSchemes, false);
    }
}
