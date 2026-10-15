package elite.intel.gameapi.journal.subscribers;

import com.google.gson.JsonParser;
import elite.intel.db.managers.LocationFixtures;
import elite.intel.db.managers.LocationManager;
import elite.intel.db.util.Database;
import elite.intel.gameapi.journal.events.FSSSignalDiscoveredEvent;
import elite.intel.gameapi.journal.events.dto.FssSignalDto;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.util.Cypher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.PLANET;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A system entered by carrier has no primary star on record until the star is scanned; its facts sit on the orbit
 * the carrier parked at. A signal found there used to be filed on the missing primary star's record - that is, on
 * nothing - and took the resource sites and conflict zones it announced with it.
 */
class FSSSignalDiscoveredRecordTest {

    private final LocationManager locations = LocationManager.getInstance();

    @BeforeAll
    static void boot() throws Exception {
        Cypher.initializeKey();
        Database.init().close();
    }

    @Test
    void aSignalInASystemEnteredByCarrierIsFiledOnTheParkingOrbit() {
        long system = 4000000000101L;
        LocationDto parking = new LocationDto(6L, system);
        parking.setStarName("Signal Carrier");
        parking.setPlanetName("Signal Carrier 6");
        parking.setLocationType(PLANET);
        parking.setSecurity("Low Security");
        LocationFixtures.store(parking);

        FSSSignalDiscoveredSubscriber.recordSignal(event(system, "$MULTIPLAYER_SCENARIO14_TITLE;"));

        List<String> signals = locations.findBySystemAddress(system, "Signal Carrier 6").getDetectedSignals().stream()
                .map(FssSignalDto::getSignalName).toList();
        assertEquals(List.of("$MULTIPLAYER_SCENARIO14_TITLE;"), signals);
    }

    @Test
    void aSignalInASystemWithNoArrivalOnRecordIsNotFiled() {
        long system = 4000000000102L;

        LocationDto returned = FSSSignalDiscoveredSubscriber.recordSignal(event(system, "$Fixed_Event_Life_Cloud;"));

        assertEquals(-1L, returned.getBodyId());
        assertEquals(-1L, locations.findSystemRecord(system).getBodyId(), "nothing may be created to hold it");
    }

    private static FSSSignalDiscoveredEvent event(long system, String signalName) {
        return new FSSSignalDiscoveredEvent(JsonParser.parseString("""
                { "timestamp":"2026-07-14T19:02:11Z", "event":"FSSSignalDiscovered", "SystemAddress":%d,
                  "SignalName":"%s", "SignalType":"ResourceExtraction" }
                """.formatted(system, signalName)).getAsJsonObject());
    }
}
