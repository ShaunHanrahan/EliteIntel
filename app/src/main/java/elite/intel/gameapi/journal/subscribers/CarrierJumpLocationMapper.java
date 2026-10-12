package elite.intel.gameapi.journal.subscribers;

import elite.intel.db.managers.LocationManager;
import elite.intel.gameapi.journal.events.CarrierJumpEvent;
import elite.intel.gameapi.journal.events.dto.LocationDto;

/**
 * Records the arrival body of a carrier jump the commander was aboard for. Shared by the live subscriber and the
 * startup pre-scan so both persist the same shape: previously the two drifted, and a system recorded while the app
 * was running carried different fields than the same system recovered from the journal.
 */
final class CarrierJumpLocationMapper {

    private CarrierJumpLocationMapper() {
    }

    /**
     * Files the arrival body under its journal name in one locked write.
     * <p>
     * WHY by name, not by BodyID: a BodyID miss built a sparse row that the upsert then wrote over the real one,
     * destroying its scan data.
     *
     * @return the record as saved, whose body id may differ from the event's (see {@link LocationDto#setBodyId})
     */
    static LocationDto recordArrival(CarrierJumpEvent event, LocationManager locationManager) {
        return locationManager.updateNamedBody(event.getSystemAddress(), event.getBodyId(), event.getBody(),
                location -> describe(location, event));
    }

    private static void describe(LocationDto location, CarrierJumpEvent event) {
        location.setStarName(event.getStarSystem());
        // WHY: without systemAddress the row is unreachable. The current-location pointer stores the
        // real address and every lookup joins on it, so a row saved with the default 0 is orphaned.
        location.setSystemAddress(event.getSystemAddress());
        location.setBodyId(event.getBodyId());
        location.setPlanetName(event.getBody());
        location.setX(event.getStarPos()[0]);
        location.setY(event.getStarPos()[1]);
        location.setZ(event.getStarPos()[2]);
        location.setAllegiance(event.getSystemAllegiance());
        location.setStationGovernment(event.getSystemGovernmentLocalised());
        location.setSecurity(event.getSystemSecurityLocalised());
        location.setControllingPower(event.getControllingPower());
        if (event.getSystemFaction() != null) location.setSystemFaction(event.getSystemFaction().getName());
        location.setPopulation(event.getPopulation());
        location.setPowerplayState(event.getPowerplayState());
        location.setPowerplayStateControlProgress(event.getPowerplayStateControlProgress());
        location.setPowerplayStateReinforcement(event.getPowerplayStateReinforcement());
        location.setPowerplayStateUndermining(event.getPowerplayStateUndermining());

        // WHY: the type is left to the events that can prove it. A carrier parks at whichever orbit was free - a
        // planet in about a third of jumps, once beside Proxima Centauri in Alpha Centauri - so the body it parks
        // at says nothing about which star is primary. Claiming it was is how Proxima was filed as the primary.
        if (event.getBodyType() != null) location.setBodyType(event.getBodyType());
    }
}
