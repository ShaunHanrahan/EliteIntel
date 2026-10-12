package elite.intel.ai.brain.actions.handlers.commands.builtin;

import elite.intel.gameapi.journal.events.dto.LocationDto;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The carrier's last known system, else the home system. A commander with no carrier used to be told the carrier's
 * location was not available, because the "no carrier" and "no home system" checks tested for null records that
 * are never null.
 */
class NavigateToFleetCarrierDestinationTest {

    private static final LocationDto NO_HOME = new LocationDto(-1L);

    @Test
    void theCarriersSystemComesFirst() {
        assertEquals(Optional.of("Hyades Sector MH-V c2-8"),
                NavigateToFleetCarrierCommand.destination("Hyades Sector MH-V c2-8", home("Synuefe UC-I b43-8")));
    }

    @Test
    void withNoCarrierKnownTheHomeSystemIsTheDestination() {
        assertEquals(Optional.of("Synuefe UC-I b43-8"), NavigateToFleetCarrierCommand.destination(null, home("Synuefe UC-I b43-8")));
        assertEquals(Optional.of("Synuefe UC-I b43-8"), NavigateToFleetCarrierCommand.destination(" ", home("Synuefe UC-I b43-8")));
    }

    @Test
    void withNeitherThereIsNothingToPlotTo() {
        assertEquals(Optional.empty(), NavigateToFleetCarrierCommand.destination(null, NO_HOME));
    }

    private static LocationDto home(String system) {
        LocationDto home = new LocationDto(0L, 18270924776817L);
        home.setStarName(system);
        return home;
    }
}
