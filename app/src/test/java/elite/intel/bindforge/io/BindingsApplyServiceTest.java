package elite.intel.bindforge.io;

import elite.intel.io.TimestampedBackups;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BindingsApplyServiceTest {

    private static final String PRESET = "Custom.3.0.binds";

    @TempDir
    Path tempDir;

    @Test
    void applyDirtyDraftWritesGameFileAndUpdatesBaseline() throws Exception {
        Path gameFile = gameFile();
        String original = binds("Key_A");
        String eiDraft = binds("Key_E");
        write(gameFile, original);

        BindingsWorkingCopyRepository repo = repo();
        Path workingCopy = repo.loadOrImportFromGame(PRESET, gameFile);
        write(workingCopy, eiDraft);

        Path backup = service(repo).apply(PRESET, gameFile);

        assertNotNull(backup);
        assertEquals(original, Files.readString(backup, StandardCharsets.UTF_8));
        assertEquals(eiDraft, Files.readString(gameFile, StandardCharsets.UTF_8));
        assertFalse(repo.hasUnappliedDraft(PRESET, gameFile));
    }

    @Test
    void applyWithoutEiDraftAfterGameChangeDoesNotRestoreOldWorkingCopy() throws Exception {
        Path gameFile = gameFile();
        String original = binds("Key_A");
        String changedByGame = binds("Key_G");
        write(gameFile, original);

        BindingsWorkingCopyRepository repo = repo();
        Path workingCopy = repo.loadOrImportFromGame(PRESET, gameFile);
        write(gameFile, changedByGame);

        Path backup = service(repo).apply(PRESET, gameFile);

        assertNull(backup);
        assertEquals(changedByGame, Files.readString(gameFile, StandardCharsets.UTF_8));
        assertEquals(changedByGame, Files.readString(workingCopy, StandardCharsets.UTF_8));
        assertTrue(backups().isEmpty());
        assertFalse(repo.hasUnappliedDraft(PRESET, gameFile));
    }

    @Test
    void applyDirtyDraftFailsWhenGameFileChangedSinceBaseline() throws Exception {
        Path gameFile = gameFile();
        String original = binds("Key_A");
        String eiDraft = binds("Key_E");
        String changedByGame = binds("Key_G");
        write(gameFile, original);

        BindingsWorkingCopyRepository repo = repo();
        Path workingCopy = repo.loadOrImportFromGame(PRESET, gameFile);
        write(workingCopy, eiDraft);
        write(gameFile, changedByGame);

        BindingsApplyException thrown = assertThrows(
                BindingsApplyException.class,
                () -> service(repo).apply(PRESET, gameFile));

        assertEquals("bindings.apply.conflict", thrown.localizationKey());
        assertEquals(changedByGame, Files.readString(gameFile, StandardCharsets.UTF_8));
        assertTrue(backups().isEmpty());
    }

    @Test
    void applyFromPushesTheSourceAndLeavesTheDraftAlone() throws Exception {
        Path gameFile = gameFile();
        String editedInGame = binds("Key_G");
        String saved = binds("Key_A");
        String eiDraft = binds("Key_E");
        write(gameFile, binds("Key_A"));

        BindingsWorkingCopyRepository repo = repo();
        Path workingCopy = repo.loadOrImportFromGame(PRESET, gameFile);
        write(workingCopy, eiDraft);
        write(gameFile, editedInGame);
        Path source = tempDir.resolve("master").resolve(PRESET);
        write(source, saved);

        Path backup = service(repo).applyFrom(source, gameFile);

        assertNotNull(backup);
        assertEquals(editedInGame, Files.readString(backup, StandardCharsets.UTF_8));
        assertEquals(saved, Files.readString(gameFile, StandardCharsets.UTF_8));
        assertEquals(eiDraft, Files.readString(workingCopy, StandardCharsets.UTF_8));
    }

    @Test
    void applyFromOverAMatchingGameFileWritesAndBacksUpNothing() throws Exception {
        Path gameFile = gameFile();
        write(gameFile, binds("Key_A"));
        Path source = tempDir.resolve("master").resolve(PRESET);
        write(source, binds("Key_A"));
        Files.setLastModifiedTime(gameFile, java.nio.file.attribute.FileTime.fromMillis(0));

        assertNull(service(repo()).applyFrom(source, gameFile));
        assertTrue(backups().isEmpty());
        assertEquals(0, Files.getLastModifiedTime(gameFile).toMillis());
    }

    @Test
    void applyFromCreatesAMissingGameFileWithoutABackup() throws Exception {
        Path gameFile = gameFile();
        Path source = tempDir.resolve("master").resolve(PRESET);
        write(source, binds("Key_A"));

        assertNull(service(repo()).applyFrom(source, gameFile));
        assertEquals(binds("Key_A"), Files.readString(gameFile, StandardCharsets.UTF_8));
        assertTrue(backups().isEmpty());
    }

    @Test
    void applyFromRefusesInvalidXmlAndLeavesTheGameFile() throws Exception {
        Path gameFile = gameFile();
        write(gameFile, binds("Key_A"));
        Path source = tempDir.resolve("master").resolve(PRESET);
        write(source, "<Root><Unclosed></Root>");

        assertThrows(BindingsApplyException.class, () -> service(repo()).applyFrom(source, gameFile));
        assertEquals(binds("Key_A"), Files.readString(gameFile, StandardCharsets.UTF_8));
        assertTrue(backups().isEmpty());
    }

    @Test
    void applyFromAMissingSourceIsAnError() throws Exception {
        Path gameFile = gameFile();
        write(gameFile, binds("Key_A"));

        assertThrows(BindingsApplyException.class,
                () -> service(repo()).applyFrom(tempDir.resolve("nowhere.binds"), gameFile));
        assertEquals(binds("Key_A"), Files.readString(gameFile, StandardCharsets.UTF_8));
    }

    @Test
    void aDraftRewrittenWithTheGameFileStillAppliesOnceItsBaselineMoves() throws Exception {
        Path gameFile = gameFile();
        write(gameFile, controllerBinds("Throttle", "Key_A"));
        BindingsWorkingCopyRepository repo = repo();
        Path workingCopy = repo.loadOrImportFromGame(PRESET, gameFile);
        write(workingCopy, controllerBinds("Throttle", "Key_E"));

        DeviceRewriteResult rewrite = new BindingsWriter().rewriteDeviceReferences(List.of(gameFile, workingCopy),
                DeviceReferenceRewrite.rename("Throttle", "LeftStick"), tempDir.resolve("rewrite-backups"));
        repo.recordGameFileBaseline(PRESET, gameFile);

        assertEquals(DeviceRewriteResult.Status.REWRITTEN, rewrite.status());
        assertTrue(repo.hasUnappliedDraft(PRESET, gameFile));
        assertNotNull(service(repo).apply(PRESET, gameFile));
        assertEquals(controllerBinds("LeftStick", "Key_E"), Files.readString(gameFile, StandardCharsets.UTF_8));
    }

    @Test
    void aCleanWorkingCopyRewrittenWithTheGameFileIsNotADraft() throws Exception {
        Path gameFile = gameFile();
        write(gameFile, controllerBinds("Throttle", "Key_A"));
        BindingsWorkingCopyRepository repo = repo();
        Path workingCopy = repo.loadOrImportFromGame(PRESET, gameFile);

        new BindingsWriter().rewriteDeviceReferences(List.of(gameFile, workingCopy),
                DeviceReferenceRewrite.rename("Throttle", "LeftStick"), tempDir.resolve("rewrite-backups"));
        repo.recordGameFileBaseline(PRESET, gameFile);

        assertFalse(repo.hasUnappliedDraft(PRESET, gameFile));
        assertNull(service(repo).apply(PRESET, gameFile));
        assertEquals(controllerBinds("LeftStick", "Key_A"), Files.readString(gameFile, StandardCharsets.UTF_8));
    }

    private BindingsWorkingCopyRepository repo() {
        return new BindingsWorkingCopyRepository(tempDir.resolve("working"));
    }

    private BindingsApplyService service(BindingsWorkingCopyRepository repo) {
        return new BindingsApplyService(repo, new TimestampedBackups(), tempDir.resolve("backups"));
    }

    private Path gameFile() throws Exception {
        Path gameFile = tempDir.resolve("game").resolve(PRESET);
        Files.createDirectories(gameFile.getParent());
        return gameFile;
    }

    private List<Path> backups() throws Exception {
        Path backupDir = tempDir.resolve("backups");
        if (!Files.exists(backupDir)) {
            return List.of();
        }
        try (var stream = Files.list(backupDir)) {
            return stream.toList();
        }
    }

    private void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String controllerBinds(String device, String key) {
        return """
                <Root>
                    <FocusLeftPanel>
                        <Primary Device="Keyboard" Key="%s" />
                        <Secondary Device="%s" Key="Joy_5" />
                    </FocusLeftPanel>
                </Root>
                """.formatted(key, device);
    }

    private String binds(String key) {
        return """
                <Root>
                    <FocusLeftPanel>
                        <Primary Device="Keyboard" Key="%s" />
                        <Secondary Device="{NoDevice}" Key="" />
                    </FocusLeftPanel>
                </Root>
                """.formatted(key);
    }
}
