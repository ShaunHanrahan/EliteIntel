package elite.intel.bindforge.devicefiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "before BindForge" backup is taken once ever, recorded, and the user is told then - whichever way into
 * activation reaches it first (Alan, 2026-10-13).
 */
class BeforeBindForgeBackupTest {

    @TempDir
    Path temp;

    private final List<String> order = new ArrayList<>();
    private String recorded;
    private boolean recordWorks = true;

    @Test
    void theFirstCallTakesRecordsAndAnnouncesIt() throws IOException {
        Optional<Path> taken = backup().ensureTaken();

        assertEquals(Optional.of(temp.resolve("before")), taken);
        assertEquals(temp.resolve("before").toString(), recorded);
        assertEquals(List.of("snapshot", "announce"), order);
    }

    /** First setup can be abandoned and reopened on a later run - the first copy is the one that means "before". */
    @Test
    void aBackupAlreadyTakenIsNotTakenOrAnnouncedAgain() throws IOException {
        recorded = temp.resolve("earlier").toString();

        assertTrue(backup().ensureTaken().isEmpty());
        assertTrue(order.isEmpty());
    }

    @Test
    void aCopyThatFailsIsNotRecordedOrAnnounced() {
        BeforeBindForgeBackup backup = new BeforeBindForgeBackup(List::of, targets -> {
            order.add("snapshot");
            throw new IOException("disk full");
        }, store(), folder -> order.add("announce"));

        assertThrows(IOException.class, backup::ensureTaken);
        assertEquals(List.of("snapshot"), order);
        assertNull(recorded);
    }

    /** A copy the database will not record is a failure - the next attempt takes another, and records that one. */
    @Test
    void aBackupThatCannotBeRecordedIsAFailureAndIsNotAnnounced() {
        recordWorks = false;

        IOException failure = assertThrows(IOException.class, () -> backup().ensureTaken());

        assertTrue(failure.getMessage().contains("could not be recorded"));
        assertEquals(List.of("snapshot"), order);
    }

    private BeforeBindForgeBackup backup() {
        return new BeforeBindForgeBackup(List::of, targets -> {
            order.add("snapshot");
            return temp.resolve("before");
        }, store(), folder -> order.add("announce"));
    }

    private BeforeBindForgeBackup.Record store() {
        return new BeforeBindForgeBackup.Record() {
            @Override
            public String taken() {
                return recorded;
            }

            @Override
            public void record(String folder) {
                if (!recordWorks) throw new IllegalStateException("database locked");
                recorded = folder;
            }
        };
    }
}
