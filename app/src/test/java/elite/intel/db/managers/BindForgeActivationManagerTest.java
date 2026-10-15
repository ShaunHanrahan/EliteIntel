package elite.intel.db.managers;

import elite.intel.db.util.Database;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The activated fact and the "before BindForge" backup's folder (migration 12005, Alan, 2026-10-13).
 */
class BindForgeActivationManagerTest {

    private static final String MIGRATION = "/db-migration/12005__bindforge_activation.sql";

    private final BindForgeActivationManager activation = BindForgeActivationManager.getInstance();
    private final BindForgeDeviceMasterManager master = BindForgeDeviceMasterManager.getInstance();

    @BeforeEach
    @AfterEach
    void noMasterAndNotActivated() {
        master.findAll().forEach(row -> master.remove(row.id()));
        BindForgeDeviceDraftManagerTest.notActivated();
    }

    @Test
    void theBackupFolderIsRecorded() {
        assertNull(activation.backupFolder());

        activation.recordBackupFolder("C:\\backups\\2026-10-13_20-00-00_before-bindforge");

        assertEquals("C:\\backups\\2026-10-13_20-00-00_before-bindforge", activation.backupFolder());
    }

    /** A master filled by first setup before the fact existed counts as activated - the migration's backfill. */
    @Test
    void theMigrationActivatesAMasterThatAlreadyHasRows() throws IOException {
        master.record("LVWAP", "3344", "83F4", true);
        assertFalse(activation.isActivated());

        runMigrationAgain();

        assertTrue(activation.isActivated());
    }

    @Test
    void theMigrationLeavesAnEmptyMasterNotActivated() throws IOException {
        runMigrationAgain();

        assertFalse(activation.isActivated());
    }

    /**
     * The in-memory database migrates once, with an empty master, so the backfill is re-run here. Every statement in
     * the file is safe to repeat; it is split as {@code DatabaseMigrator} splits it.
     */
    private static void runMigrationAgain() throws IOException {
        String sql;
        try (InputStream in = BindForgeActivationManagerTest.class.getResourceAsStream(MIGRATION)) {
            assertNotNull(in, MIGRATION);
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        try (Handle handle = Database.init()) {
            for (String statement : sql.split(";\\s*\n")) {
                if (!statement.isBlank()) handle.execute(statement.trim());
            }
        }
    }
}
