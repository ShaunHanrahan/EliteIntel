package elite.intel.db.managers;

import elite.intel.db.dao.LocationDao;
import elite.intel.db.util.Database;
import elite.intel.gameapi.journal.events.dto.LocationDto;

/**
 * Seeds location records for tests, as a past visit would have left them. Production code writes records only through
 * {@link LocationManager#updateNamedBody}; a test that needs a record already on file - a scanned body, a station from
 * an older database - stores it here, under its planet name, or its station name when it has none.
 */
public final class LocationFixtures {

    private LocationFixtures() {
    }

    public static void store(LocationDto location) {
        String name = location.getPlanetName() != null && !location.getPlanetName().isBlank()
                ? location.getPlanetName()
                : location.getStationName();
        if (name == null || name.isBlank() || location.getStarName() == null) {
            throw new IllegalArgumentException("A fixture record needs a name and a system name");
        }
        Database.withDao(LocationDao.class, dao -> {
            dao.save(location.getBodyId(), name, location.getStarName(), location.getSystemAddress(), location.toJson());
            return null;
        });
    }
}
