package elite.intel.gameapi.journal.subscribers;

import elite.intel.db.managers.LocationManager;
import elite.intel.gameapi.journal.ScanBodyClassifier;
import elite.intel.gameapi.journal.events.LocationEvent;
import elite.intel.gameapi.journal.events.dto.LocationDto;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Records the body a Location event names. Shared by the live subscriber and the startup pre-scan,
 * which kept two copies of this mapping that had to be edited in step.
 */
final class LocationEventMapper {

    private LocationEventMapper() {
    }

    /**
     * Files the event's body under its journal name in one locked write, applying {@code alsoUnderLock} in the same
     * write.
     *
     * @return the record as saved, or null when the event names no body to file
     */
    static LocationDto record(LocationEvent event, Consumer<LocationDto> alsoUnderLock) {
        if (event.getBody() == null || event.getBody().isBlank() || event.getBodyID() == null) return null;
        return LocationManager.getInstance().updateNamedBody(event.getSystemAddress(), event.getBodyID(), event.getBody(),
                body -> {
                    describe(body, event);
                    alsoUnderLock.accept(body);
                });
    }

    private static void describe(LocationDto dto, LocationEvent event) {
        dto.setSystemAddress(event.getSystemAddress());
        dto.setX(event.getStarPos()[0]);
        dto.setY(event.getStarPos()[1]);
        dto.setZ(event.getStarPos()[2]);
        dto.setBodyId(event.getBodyID());
        dto.setStarName(event.getStarSystem());
        dto.setPlanetName(event.getBody());
        dto.setAllegiance(event.getSystemAllegiance());
        dto.setBodyType(event.getBodyType());
        dto.setControllingPower(event.getControllingPower());
        dto.setGovernment(event.getSystemGovernmentLocalised());
        dto.setDistance(event.getDistFromStarLS());
        dto.setEconomy(event.getSystemEconomyLocalised());
        dto.setSecondEconomy(event.getSystemSecondEconomyLocalised());

        // WHY: fills a blank only. A Location does not prove what a body is: docked at a carrier, it names a star
        // with no distance even where the carrier parked at a planet. Only an FSDJump arrival or a Scan at 0 Ls
        // proves a primary star, and those write the type themselves.
        String bodyType = event.getBodyType() == null ? "" : event.getBodyType().toLowerCase(Locale.ROOT);
        LocationDto.LocationType type = LocationDto.determineType(bodyType, event.getDistFromStarLS() == 0);
        if (type != null && ScanBodyClassifier.isUnclassified(dto.getLocationType())) {
            dto.setLocationType(type);
        }

        dto.setPopulation(event.getPopulation());
        dto.setPowerplayState(event.getPowerplayState());
        dto.setPowerplayStateControlProgress(event.getPowerplayStateControlProgress());
        dto.setPowerplayStateReinforcement(event.getPowerplayStateReinforcement());
        dto.setPowerplayStateUndermining(event.getPowerplayStateUndermining());
        dto.setSecurity(event.getSystemSecurityLocalised());
        if (event.getSystemFaction() != null) dto.setSystemFaction(event.getSystemFaction().getName());
    }
}
