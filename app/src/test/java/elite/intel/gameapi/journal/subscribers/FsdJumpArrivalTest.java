package elite.intel.gameapi.journal.subscribers;

import com.google.gson.JsonParser;
import elite.intel.db.managers.LocationFixtures;
import elite.intel.db.managers.LocationManager;
import elite.intel.db.util.Database;
import elite.intel.gameapi.journal.events.FSDJumpEvent;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.session.PlayerSession;
import elite.intel.session.Position;
import elite.intel.util.Cypher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.PRIMARY_STAR;
import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.STATION;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A ship's jump arrives at the system's entry point, so the arrival body is the primary star - here "78 Iota
 * Leonis A", BodyID 1, as the journal reports it on every visit. The star is found by that name, never by BodyID:
 * a station filed under the star's BodyID used to be what a BodyID lookup returned, and the arrival was written
 * over it.
 */
class FsdJumpArrivalTest {

    private static final long IOTA_LEONIS = 1144348415691L;

    private static final String ARRIVAL = """
            { "timestamp":"2026-07-14T19:02:11Z", "event":"FSDJump", "StarSystem":"78 Iota Leonis",
              "SystemAddress":1144348415691, "StarPos":[63.03125,57.90625,-32.21875],
              "SystemAllegiance":"Federation", "SystemSecurity_Localised":"High Security", "Population":4500000,
              "Body":"78 Iota Leonis A", "BodyID":1, "BodyType":"Star", "JumpDist":12.3, "FuelUsed":1.2, "FuelLevel":30.0 }
            """;

    private final LocationManager locations = LocationManager.getInstance();

    @BeforeAll
    static void boot() throws Exception {
        Cypher.initializeKey();
        Database.init().close();
    }

    @Test
    void theArrivalStarIsFiledUnderItsNameAndKeepsItsScan() {
        LocationDto station = new LocationDto(1L, IOTA_LEONIS);
        station.setStarName("78 Iota Leonis");
        station.setStationName("Fullerene Hub");
        station.setLocationType(STATION);
        LocationFixtures.store(station);
        LocationDto scanned = new LocationDto(1L, IOTA_LEONIS);
        scanned.setStarName("78 Iota Leonis");
        scanned.setPlanetName("78 Iota Leonis A");
        scanned.setRadius(512_000_000);
        LocationFixtures.store(scanned);

        FsdJumpArrival.record(new FSDJumpEvent(JsonParser.parseString(ARRIVAL).getAsJsonObject()));

        LocationDto star = locations.findBySystemAddress(IOTA_LEONIS, "78 Iota Leonis A");
        assertEquals(PRIMARY_STAR, star.getLocationType());
        assertEquals(512_000_000, star.getRadius(), "the arrival must not overwrite the star's scan");
        assertEquals("High Security", star.getSecurity());
        assertEquals(List.of("Fullerene Hub"),
                locations.findStationsInCurrentStarSystem(IOTA_LEONIS).stream().map(LocationDto::getStationName).toList(),
                "the station sharing the star's BodyID must be left alone");
        assertEquals(new Position(IOTA_LEONIS, "78 Iota Leonis", 1L), PlayerSession.getInstance().position().orElseThrow());
    }
}
