package elite.intel.db.managers;

import elite.intel.db.util.Database;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.util.Cypher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.MOON;
import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.STATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every location write goes through one locked read-modify-write, addressed by the record's name: a body by the name
 * the journal gives it, a station by its MarketID-keyed record. A BodyID is unique only within a system, and older
 * databases still hold stations under the BodyID of the body they orbit.
 */
class LocationManagerWritePathTest {

    private final LocationManager locations = LocationManager.getInstance();

    @BeforeAll
    static void boot() throws Exception {
        Cypher.initializeKey();
        Database.init().close();
    }

    @Test
    void aWriteByBodyIdLandsOnTheBodyNotAStationSharingItsId() {
        long system = 3000000000001L;
        store(system, 7L, "Write Path 7 a", null, MOON);
        store(system, 7L, null, "Write Path Port", STATION);

        locations.updateBody(system, 7L, body -> body.setRadius(1_234_000));

        assertEquals(1_234_000, locations.findBySystemAddress(system, "Write Path 7 a").getRadius());
        assertEquals(List.of(0.0), locations.findStationsInCurrentStarSystem(system).stream().map(LocationDto::getRadius).toList(),
                "the station sharing the body's id must be left alone");
    }

    @Test
    void aWriteCannotMoveARecordOntoAnotherName() {
        long system = 3000000000002L;
        store(system, 3L, "Write Path 3", null, MOON);

        locations.updateNamedBody(system, 3L, "Write Path 3", body -> body.setPlanetName("Write Path Renamed"));

        assertEquals(1, locations.findAllBySystemAddress(system).size(), "the record stays under the name it was addressed by");
    }

    @Test
    void aBodyIdNothingIsKnownByIsNotWritten() {
        long system = 3000000000003L;

        locations.updateBody(system, 4L, body -> {
            body.setStarName("Write Path Nowhere");
            body.setPlanetName("Write Path Nowhere 4");
        });

        assertTrue(locations.findAllBySystemAddress(system).isEmpty());
    }

    private static void store(long system, long bodyId, String planetName, String stationName, LocationDto.LocationType type) {
        LocationDto record = new LocationDto(bodyId, system);
        record.setStarName("Write Path");
        if (planetName != null) record.setPlanetName(planetName);
        if (stationName != null) record.setStationName(stationName);
        record.setLocationType(type);
        LocationFixtures.store(record);
    }
}
