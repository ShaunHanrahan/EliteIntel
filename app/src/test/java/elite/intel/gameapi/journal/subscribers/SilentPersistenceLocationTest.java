package elite.intel.gameapi.journal.subscribers;

import com.google.gson.JsonParser;
import elite.intel.db.managers.LocationFixtures;
import elite.intel.db.util.Database;
import elite.intel.gameapi.journal.events.LocationEvent;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.session.PlayerSession;
import elite.intel.session.Position;
import elite.intel.util.Cypher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The startup replay places the commander where the live bus would. The journal can report BodyID 0 for a body
 * already on record under a higher id, which the record keeps; a position aimed at the journal's 0 would point at
 * nothing, so both paths point at the id the record holds.
 */
class SilentPersistenceLocationTest {

    @BeforeAll
    static void boot() throws Exception {
        Cypher.initializeKey();
        Database.init().close();
    }

    @Test
    void theReplayPointsAtTheIdTheRecordHolds() {
        long system = 4000000000201L;
        LocationDto star = new LocationDto(2L, system);
        star.setStarName("Replay Pointer");
        star.setPlanetName("Replay Pointer A");
        LocationFixtures.store(star);

        new SilentPersistenceSubscriber().onLocation(new LocationEvent(JsonParser.parseString("""
                { "timestamp":"2026-10-12T05:25:04Z", "event":"Location", "Docked":false,
                  "StarSystem":"Replay Pointer", "SystemAddress":%d, "StarPos":[1.0,2.0,3.0],
                  "Body":"Replay Pointer A", "BodyID":0, "BodyType":"Star" }
                """.formatted(system)).getAsJsonObject()));

        assertEquals(new Position(system, "Replay Pointer", 2L), PlayerSession.getInstance().position().orElseThrow());
    }
}
