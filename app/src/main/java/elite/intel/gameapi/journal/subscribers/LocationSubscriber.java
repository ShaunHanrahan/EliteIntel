package elite.intel.gameapi.journal.subscribers;

import com.google.common.eventbus.Subscribe;
import elite.intel.db.managers.LocationManager;
import elite.intel.gameapi.journal.events.LocationEvent;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.gameapi.search.edsm.EdsmApiClient;
import elite.intel.gameapi.search.edsm.dto.DeathsDto;
import elite.intel.gameapi.search.edsm.dto.TrafficDto;
import elite.intel.session.PlayerSession;
import elite.intel.session.Position;

public class LocationSubscriber {

    private final LocationManager locationManager = LocationManager.getInstance();
    private final PlayerSession playerSession = PlayerSession.getInstance();

    @Subscribe
    public void onLocationEvent(LocationEvent event) {
        // Before the EDSM calls below, so a question asked meanwhile is answered right.
        pointAt(event);
        long systemAddress = event.getSystemAddress();
        Long eventBodyId = event.getBodyID();

        Thread.ofVirtual().start(() -> {
            // A jump arrival files EDSM's body list for the system, but a session that starts inside one -
            // the commander logged out docked or landed - never jumped in. Without the list the only bodies
            // on record are the ones the commander's own scans write, and the FSS does not repeat a Scan for
            // a body resolved on an earlier visit, so a moon's bio signals landed on a row nothing could
            // classify. Fetched only while the system has no planet or moon on record, so a re-read of the
            // journal at every start does not become a call per start.
            if (EdsmSystemBodies.hasNoClassifiedBodies(locationManager.findAllBySystemAddress(event.getSystemAddress()))) {
                EdsmSystemBodies.fetchAndRecord(event.getStarSystem(), event.getSystemAddress(), event.getStarPos());
            }

            // WHY fetched before the write: the body lock is never held across a network call. The write comes after
            // the EDSM body fetch above, so what EDSM just filed for this body is built on rather than written over.
            TrafficDto traffic = EdsmApiClient.searchTraffic(event.getStarSystem());
            DeathsDto deaths = EdsmApiClient.searchDeaths(event.getStarSystem());
            LocationDto body = LocationEventMapper.record(event, record -> {
                record.setTrafficDto(traffic);
                record.setDeathsDto(deaths);
            });

            // LocationDto.setBodyId ignores a lower id, so the row may hold another id than the event's 0.
            // Re-point at it, unless a later event (a supercruise exit, an approach) has moved the position.
            if (body != null && body.getBodyId() != eventBodyId && isStillAt(systemAddress, eventBodyId)) {
                playerSession.moveWithinSystem(systemAddress, body.getBodyId());
            }

            // After the body, never onto it: this is the arrival of a commander who quit on a pad, and the
            // station standing here is not the body the event names. Written last so that where the two are
            // the same record - an orbital station, whose Body IS its own name - the station's fields land on
            // top of the body write rather than under it.
            if (event.isDocked()) {
                DockedStationRecord.of(event).store();
            }
        });
    }

    /**
     * Moves the commander's current-location pointer to where the event says the session starts. The live bus
     * moved it only on FSDJump and CarrierJump, so a session that started somewhere other than where the last
     * journal ended - the carrier jumped while the game was closed, which writes no CarrierJump - answered
     * "where are we" with the old system for the whole session (bundle 2026-10-11).
     */
    void pointAt(LocationEvent event) {
        playerSession.moveTo(new Position(event.getSystemAddress(), event.getStarSystem(), event.getBodyID()));
    }

    private boolean isStillAt(long systemAddress, long bodyId) {
        return playerSession.position().map(here -> here.isAt(systemAddress, bodyId)).orElse(false);
    }
}
