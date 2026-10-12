package elite.intel.gameapi.journal.subscribers;

import com.google.common.eventbus.Subscribe;
import elite.intel.db.managers.LocationManager;
import elite.intel.gameapi.journal.events.SupercruiseEntryEvent;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.session.PlayerSession;

import java.util.ArrayList;

public class SupercruiseEntryEventSubscriber {

    private final PlayerSession playerSession = PlayerSession.getInstance();
    private final LocationManager locationManager = LocationManager.getInstance();

    @Subscribe
    public void onSuperCruiseEntryEvent(SupercruiseEntryEvent event) {
        playerSession.clearAnnouncedPointOfInterest();
        Thread.ofVirtual().start(() -> playerSession.position()
                .filter(here -> here.bodyId() != null)
                .ifPresent(here -> locationManager.updateBody(here.systemAddress(), here.bodyId(),
                        SupercruiseEntryEventSubscriber::clearStationFields)));
    }

    /**
     * WHY: docking used to write the station's fields onto the record of the body we dropped at, and databases
     * written then still carry them - which reads as being docked at a body. Leaving for supercruise heals the body
     * we were at. A station's own record keeps its fields: a station is still a station once we leave.
     */
    private static void clearStationFields(LocationDto location) {
        LocationDto.LocationType type = location.getLocationType();
        if (type == LocationDto.LocationType.STATION || type == LocationDto.LocationType.FLEET_CARRIER) return;
        location.setStationFaction(null);
        location.setStationName(null);
        location.setStationServices(new ArrayList<>());
        location.setStationAllegiance(null);
        location.setStationEconomy(null);
        location.setStationGovernment(null);
        location.setStationType(null);
    }
}
