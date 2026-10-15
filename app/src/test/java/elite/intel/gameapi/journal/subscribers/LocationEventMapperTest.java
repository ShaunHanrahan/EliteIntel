package elite.intel.gameapi.journal.subscribers;

import com.google.gson.JsonParser;
import elite.intel.db.managers.LocationFixtures;
import elite.intel.db.util.Database;
import elite.intel.gameapi.journal.events.LocationEvent;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.util.Cypher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.PRIMARY_STAR;
import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.STAR;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Location event does not prove what a body is. Docked at a fleet carrier, the game names a star with no distance
 * even where the carrier parked at a planet, so the type it implies only fills a blank.
 */
class LocationEventMapperTest {

    private static final String DOCKED_AT_A_CARRIER = """
            { "timestamp":"2026-10-12T05:25:04Z", "event":"Location", "Docked":true, "StationName":"GHY-L8X",
              "StationType":"FleetCarrier", "MarketID":3712500736, "StarSystem":"%s", "SystemAddress":%d,
              "StarPos":[119.90625,-87.68750,-184.62500], "Body":"%s", "BodyID":0, "BodyType":"Star" }
            """;


    @BeforeAll
    static void boot() throws Exception {
        Cypher.initializeKey();
        Database.init().close();
    }

    @Test
    void aTypeAlreadyOnRecordIsKept() {
        long system = 2283077046963L;
        LocationDto star = new LocationDto(0L, system);
        star.setStarName("Location Mapper Kept");
        star.setPlanetName("Location Mapper Kept");
        star.setLocationType(STAR);
        LocationFixtures.store(star);

        LocationDto mapped = LocationEventMapper.record(event("Location Mapper Kept", system), body -> {
        });

        assertEquals(STAR, mapped.getLocationType());
    }

    @Test
    void aBodyWithNoTypeYetTakesTheOneTheEventImplies() {
        LocationDto mapped = LocationEventMapper.record(event("Location Mapper Blank", 2283077046964L), body -> {
        });

        assertEquals(PRIMARY_STAR, mapped.getLocationType());
    }

    private static LocationEvent event(String system, long systemAddress) {
        String line = DOCKED_AT_A_CARRIER.formatted(system, systemAddress, system);
        return new LocationEvent(JsonParser.parseString(line).getAsJsonObject());
    }
}
