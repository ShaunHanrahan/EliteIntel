package elite.intel.db.util;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EdgeTtsRemovedMigrationTest {

    @Test
    void anEdgeCommanderMovesToKokoroAndEveryOtherEngineStays() throws IOException {
        try (Handle handle = Jdbi.create("jdbc:sqlite::memory:").open()) {
            handle.execute("CREATE TABLE game_session (id INTEGER PRIMARY KEY, ttsProvider TEXT NOT NULL)");
            handle.execute("INSERT INTO game_session (id, ttsProvider) VALUES (1, 'EDGE'), (2, 'GOOGLE'), "
                    + "(3, 'SUPERTONIC'), (4, 'KOKORO')");

            handle.execute(loadMigration());

            assertEquals("KOKORO", provider(handle, 1), "Edge is gone, Kokoro speaks in its place");
            assertEquals("GOOGLE", provider(handle, 2));
            assertEquals("SUPERTONIC", provider(handle, 3));
            assertEquals("KOKORO", provider(handle, 4));
        }
    }

    private static String provider(Handle handle, int id) {
        return handle.createQuery("SELECT ttsProvider FROM game_session WHERE id = :id")
                .bind("id", id)
                .mapTo(String.class)
                .one();
    }

    private static String loadMigration() throws IOException {
        try (InputStream input = EdgeTtsRemovedMigrationTest.class.getResourceAsStream(
                "/db-migration/11007__edge_tts_removed.sql")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
