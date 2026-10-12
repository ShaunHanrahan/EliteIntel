package elite.intel.db.managers;

import elite.intel.db.dao.LocationDao;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.session.PlayerSession;
import elite.intel.session.Position;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.PRIMARY_STAR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Where the ship is, for every distance search: the coordinates every arrival event files on the system's record.
 * Sol sits at the origin, and it is one of the busiest systems in the bubble, so zeroes are a position like any other
 * and no search answers "I do not know where you are" there.
 */
class LocationManagerCoordinatesTest {

    private static final AtomicInteger RUN = new AtomicInteger();

    private final LocationManager locations = LocationManager.getInstance();
    private final PlayerSession player = PlayerSession.getInstance();

    @Test
    void aSystemAtTheOriginStillHasAPosition() {
        long systemAddress = arriveAt("Sol Test " + RUN.incrementAndGet(), 0, 0, 0);

        LocationDao.Coordinates here = locations.getGalacticCoordinates();

        assertNotNull(here, "Sol is at the origin, not unknown");
        assertEquals(player.currentStarSystem(), here.starSystem());
        assertEquals(0.0, here.x(), 1e-9);
        assertEquals(0.0, here.y(), 1e-9);
        assertEquals(0.0, here.z(), 1e-9);
        assertEquals(systemAddress, player.currentSystemAddress());
    }

    @Test
    void aSystemAwayFromTheOriginReadsItsOwnRow() {
        arriveAt("Shinrarta Test " + RUN.incrementAndGet(), 55.71875, 17.59375, 27.15625);

        LocationDao.Coordinates here = locations.getGalacticCoordinates();

        assertNotNull(here);
        assertEquals(55.71875, here.x(), 1e-9);
        assertEquals(17.59375, here.y(), 1e-9);
        assertEquals(27.15625, here.z(), 1e-9);
    }

    private long arriveAt(String star, double x, double y, double z) {
        long systemAddress = 10477373803L + RUN.get();
        long bodyId = 0;
        LocationDto primary = new LocationDto(bodyId, systemAddress);
        primary.setStarName(star);
        primary.setPlanetName(star);
        primary.setLocationType(PRIMARY_STAR);
        primary.setSecurity("High Security"); // every arrival files the system's security with its coordinates
        primary.setX(x);
        primary.setY(y);
        primary.setZ(z);
        LocationFixtures.store(primary);
        player.moveTo(new Position(systemAddress, star, bodyId));
        return systemAddress;
    }
}
