package elite.intel.gameapi.journal.subscribers;

import com.google.gson.JsonParser;
import elite.intel.gameapi.journal.events.FSSBodySignalsEvent;
import elite.intel.gameapi.journal.events.ScanEvent;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import org.junit.jupiter.api.Test;

import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.PLANETARY_RING;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One description of a Scan for the live bus and the startup replay. They used to disagree: only the live one counted
 * the body's signals, and only the replay recorded a ring's parent.
 */
class ScanRecordTest {

    private static final LocationDto SYSTEM = new LocationDto(1L, 1L);

    @Test
    void theSignalsAlreadyOnRecordAreCounted() {
        LocationDto moon = new LocationDto(12L, 1L);
        FSSBodySignalsEvent fss = new FSSBodySignalsEvent(JsonParser.parseString("""
                { "timestamp":"2026-07-14T19:02:11Z", "event":"FSSBodySignals", "BodyName":"Scan Record 3 a",
                  "BodyID":12, "SystemAddress":1, "Signals":[ { "Type":"$SAA_SignalType_Biological;", "Count":3 } ] }
                """).getAsJsonObject());
        moon.setFssSignals(fss.getSignals());

        ScanRecord.describe(moon, scan("Scan Record 3 a", 12, 1520.4), SYSTEM);

        assertEquals(3, moon.getBioSignals());
    }

    @Test
    void aRingRecordsItsParent() {
        LocationDto ring = new LocationDto(9L, 1L);

        ScanRecord.describe(ring, scan("Scan Record 3 A Ring", 9, 1520.4), SYSTEM);

        assertEquals(PLANETARY_RING, ring.getLocationType());
        assertEquals("Scan Record 3", ring.getParentBodyName());
    }

    private static ScanEvent scan(String bodyName, int bodyId, double distance) {
        return new ScanEvent(JsonParser.parseString("""
                { "timestamp":"2026-07-14T19:02:11Z", "event":"Scan", "ScanType":"Detailed",
                  "BodyName":"%s", "BodyID":%d, "StarSystem":"Scan Record", "SystemAddress":1,
                  "DistanceFromArrivalLS":%s, "WasDiscovered":true, "WasMapped":false }
                """.formatted(bodyName, bodyId, distance)).getAsJsonObject());
    }
}
