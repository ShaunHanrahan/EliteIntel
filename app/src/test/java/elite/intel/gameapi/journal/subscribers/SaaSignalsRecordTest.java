package elite.intel.gameapi.journal.subscribers;

import com.google.gson.JsonParser;
import elite.intel.db.managers.LocationFixtures;
import elite.intel.db.managers.LocationManager;
import elite.intel.db.util.Database;
import elite.intel.gameapi.journal.events.SAASignalsFoundEvent;
import elite.intel.gameapi.journal.events.dto.GenusDto;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.util.Cypher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.PLANET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One record of a surface scan for the live bus and the startup replay. The replay used to drop the genus list, so a
 * body surveyed before a restart offered no organics until it was surveyed again.
 */
class SaaSignalsRecordTest {

    private final LocationManager locations = LocationManager.getInstance();

    @BeforeAll
    static void boot() throws Exception {
        Cypher.initializeKey();
        Database.init().close();
    }

    @Test
    void theGenusListIsRecorded() {
        long system = 4000000000001L;
        seed(system, "Saa Record", 1L, PLANET);

        SaaSignalsRecord.record(event("Saa Record 2", 2, system, """
                "Signals":[ { "Type":"$SAA_SignalType_Biological;", "Count":1 } ],
                "Genuses":[ { "Genus":"$Codex_Ent_Bacterial_Genus_Name;", "Genus_Localised":"Bacterium" } ]
                """), body -> {
        });

        List<String> genus = locations.findBySystemAddress(system, "Saa Record 2").getGenus().stream()
                .map(GenusDto::getGenusLocalised).toList();
        assertEquals(List.of("Bacterium"), genus);
    }

    @Test
    void aRingMarksItsParentOnlyWhenTheParentIsOnRecord() {
        long system = 4000000000002L;
        seed(system, "Saa Ring", 1L, PLANET);
        seed(system, "Saa Ring 3", 3L, PLANET);

        SaaSignalsRecord.record(event("Saa Ring 3 A Ring", 4, system, """
                "Signals":[ { "Type":"Painite", "Count":2 } ]
                """), body -> {
        });
        SaaSignalsRecord.record(event("Saa Ring 5 A Ring", 6, system, """
                "Signals":[ { "Type":"Painite", "Count":2 } ]
                """), body -> {
        });

        assertTrue(locations.findBySystemAddress(system, "Saa Ring 3").isHasRings());
        assertEquals(-1L, locations.findBySystemAddress(system, "Saa Ring 5").getBodyId(),
                "a parent nothing is known of gets no record of its own");
    }

    private static void seed(long system, String name, long bodyId, LocationDto.LocationType type) {
        LocationDto body = new LocationDto(bodyId, system);
        body.setStarName(name.replaceAll(" \\d+$", ""));
        body.setPlanetName(name);
        body.setLocationType(type);
        LocationFixtures.store(body);
    }

    private static SAASignalsFoundEvent event(String bodyName, int bodyId, long system, String signals) {
        return new SAASignalsFoundEvent(JsonParser.parseString("""
                { "timestamp":"2026-07-14T19:02:11Z", "event":"SAASignalsFound", "BodyName":"%s", "BodyID":%d,
                  "SystemAddress":%d, %s }
                """.formatted(bodyName, bodyId, system, signals)).getAsJsonObject());
    }
}
