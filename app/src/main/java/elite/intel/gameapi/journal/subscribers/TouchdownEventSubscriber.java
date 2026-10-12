package elite.intel.gameapi.journal.subscribers;

import com.google.common.eventbus.Subscribe;
import elite.intel.ai.brain.vega.VegaRuntime;
import elite.intel.ai.mouth.EventNarrator;
import elite.intel.db.managers.LocationManager;
import elite.intel.gameapi.journal.events.TouchdownEvent;
import elite.intel.session.PlayerSession;

import static elite.intel.util.StringUtls.localizedEvent;

@SuppressWarnings("unused")
public class TouchdownEventSubscriber {

    private final PlayerSession playerSession = PlayerSession.getInstance();
    private final LocationManager locationManager = LocationManager.getInstance();


    @Subscribe
    public void onTouchdownEvent(TouchdownEvent event) {
        Thread.ofVirtual().start(() -> {
            String body = event.getBody();
            Double latitude = event.getLatitude();
            Double longitude = event.getLongitude();
            String pointOfInterest = event.getNearestDestinationLocalised();
            String starSystem = event.getStarSystem();
            boolean isStation = event.isOnStation();
            boolean isOnPlanet = event.isOnPlanet();
            String locationType = isStation ? localizedEvent("event.touchdown.station") : isOnPlanet ? localizedEvent("event.touchdown.planet") : null;

            StringBuilder sb = new StringBuilder();
            sb.append(localizedEvent("event.touchdown.header")).append(": ");
            sb.append(" ");
            sb.append(event.isPlayerControlled() ? localizedEvent("event.touchdown.manual") : localizedEvent("event.touchdown.unmanned"));
            sb.append(" ");
            sb.append(locationType == null ? localizedEvent("event.touchdown.unknown") : locationType);
            sb.append(" ");
            sb.append(body);
            sb.append(" ");
            sb.append(localizedEvent("event.touchdown.latitude")).append(": ");
            sb.append(latitude);
            sb.append(" ");
            sb.append(localizedEvent("event.touchdown.longitude")).append(": ");
            sb.append(longitude);
            sb.append(" ");
            sb.append(localizedEvent("event.touchdown.pointOfInterest")).append(": ");
            sb.append(pointOfInterest);


            // WHY the event's own body, not the one the position points at: a stale position wrote the landing
            // site onto another body. Only a body on record is written - the landing names nothing else about it.
            if (body != null && latitude != null && longitude != null
                    && locationManager.findBySystemAddress(event.getSystemAddress(), body).getStarName() != null) {
                locationManager.updateNamedBody(event.getSystemAddress(), event.getBodyId(), body, landedOn -> {
                    landedOn.setStarName(starSystem);
                    landedOn.setLandingCoordinates(new double[]{latitude, longitude});
                });
            }

            boolean atPointOfInterest = pointOfInterest != null && !pointOfInterest.isEmpty();
            // Hopping between bio samples lands beside the same beacon over and over: name it once per stay.
            if (atPointOfInterest && playerSession.markPointOfInterestAnnounced(event.getBodyId(), pointOfInterest)) {
                VegaRuntime.narrator().narrate(sb.toString(), "Confirm touchdown. State the point of interest we have landed at.");
            } else {
                EventNarrator.critical(localizedEvent("event.touchdown"));
            }
            playerSession.setShipAutoDeparted(false);
        });
    }
}
