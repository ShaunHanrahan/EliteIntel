package elite.intel.junit.gameapi.journal.events.dto;

import elite.intel.db.managers.LocationManager;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every "not found" lookup answers a record with BodyID -1, and callers test for it. setBodyId never lets a lower
 * id replace a higher one, and the field starts at 0, so the -1 was dropped and the empty record read as body 0:
 * "navigate to home system" then plotted to a blank name instead of saying no home was set (bundle 2026-10-11).
 */
class LocationDtoNotFoundTest {

    @Test
    void theNotFoundConstructorsKeepMinusOne() {
        assertEquals(-1L, new LocationDto(-1L).getBodyId());
        assertEquals(-1L, new LocationDto(-1L, 2283077046962L).getBodyId());
        assertEquals(-1L, new LocationDto(-1L, "Hyades Sector MH-V c2-8").getBodyId());
        assertEquals(-1L, new LocationDto(null, null).getBodyId());
    }

    @Test
    void aRealIdStillReplacesTheNotFoundMarker() {
        LocationDto location = new LocationDto(-1L, 2283077046962L);
        location.setBodyId(0L);
        assertEquals(0L, location.getBodyId());
    }

    @Test
    void aSystemWithNoRecordAnswersTheNotFoundMarker() {
        long unknownSystem = 9_999_999_999_991L;
        assertEquals(-1L, LocationManager.getInstance().findBySystemAddress(unknownSystem).getBodyId());
        assertEquals(-1L, LocationManager.getInstance().findBySystemAddress(unknownSystem, "Nowhere").getBodyId());
    }
}
