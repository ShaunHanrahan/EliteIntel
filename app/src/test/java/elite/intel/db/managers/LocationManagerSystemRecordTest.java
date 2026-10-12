package elite.intel.db.managers;

import elite.intel.db.util.Database;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.session.PlayerSession;
import elite.intel.session.Position;
import elite.intel.util.Cypher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.PLANET;
import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.PRIMARY_STAR;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A system's facts - security, allegiance - live on whichever body the arrival event named. After a ship's jump
 * that is the primary star; after a carrier jump it is the orbit the carrier parked at, often a planet, and the
 * primary star has no record until it is scanned. "Where are we" and "set this as our home system" must work in
 * both.
 */
class LocationManagerSystemRecordTest {

    private final LocationManager locations = LocationManager.getInstance();

    @BeforeAll
    static void boot() throws Exception {
        Cypher.initializeKey();
        Database.init().close();
    }

    @Test
    void aSystemEnteredByCarrierHasItsFactsOnTheParkingOrbit() {
        long hyades = 2283077046962L;
        save(hyades, "Hyades Sector MH-V c2-8 6", PLANET, "High Security");

        PlayerSession.getInstance().moveTo(new Position(hyades, "Hyades Sector MH-V c2-8", 21L));
        Whereabouts here = locations.here().orElseThrow();

        assertEquals("Hyades Sector MH-V c2-8", here.starSystem());
        assertEquals("High Security", here.system().getSecurity());
    }

    @Test
    void thePrimaryStarsRecordIsPreferredWhenItCarriesTheFacts() {
        long system = 1144348415699L;
        save(system, "System Record Test 3", PLANET, "Low Security");
        save(system, "System Record Test A", PRIMARY_STAR, "High Security");

        assertEquals("System Record Test A", locations.findSystemRecord(system).getPlanetName());
    }

    /**
     * After a drop at a ring the commander's body is the ring, whose record holds no security and no coordinates;
     * "how safe is this system" and "how far to the next waypoint" are questions about the system.
     */
    @Test
    void atARingTheSystemsFactsComeFromTheSystemsRecord() {
        long system = 1144348415700L;
        save(system, "Ring Facts A", PRIMARY_STAR, "Anarchy");
        LocationDto ring = new LocationDto(9L, system);
        ring.setStarName("Ring Facts");
        ring.setPlanetName("Ring Facts 2 A Ring");
        LocationFixtures.store(ring);

        PlayerSession.getInstance().moveTo(new Position(system, "Ring Facts", 9L));

        assertEquals("Ring Facts 2 A Ring", locations.currentBody().getPlanetName());
        assertEquals("Anarchy", locations.currentSystem().getSecurity());
    }

    @Test
    void aSystemEnteredByCarrierHasAPosition() {
        long hyades = 2283077046965L;
        LocationDto parking = new LocationDto(21L, hyades);
        parking.setStarName("Carrier Position");
        parking.setPlanetName("Carrier Position 6");
        parking.setSecurity("High Security");
        parking.setX(119.90625);
        parking.setY(-87.6875);
        parking.setZ(-184.625);
        LocationFixtures.store(parking);

        PlayerSession.getInstance().moveTo(new Position(hyades, "Carrier Position", 21L));

        assertEquals(119.90625, locations.getGalacticCoordinates().x(), 1e-9);
    }

    @Test
    void aSystemWithNoArrivalOnRecordIsNotFound() {
        assertEquals(-1L, locations.findSystemRecord(987654321L).getBodyId());
    }

    private void save(long systemAddress, String name, LocationDto.LocationType type, String security) {
        LocationDto body = new LocationDto(21L, systemAddress);
        body.setStarName(name.replaceAll(" (\\d+|A)$", ""));
        body.setPlanetName(name);
        body.setLocationType(type);
        body.setSecurity(security);
        LocationFixtures.store(body);
    }
}
