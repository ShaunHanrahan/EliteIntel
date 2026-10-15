package elite.intel.junit.gameapi;

import com.google.common.eventbus.EventBus;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import elite.intel.db.managers.LocationManager;
import elite.intel.gameapi.journal.events.LocationEvent;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.gameapi.journal.subscribers.SilentPersistenceSubscriber;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pre-scan replays every Location on startup and mirrors LocationSubscriber's classification. Both had the
 * distance test inverted: a login at a system's main star (no DistFromStarLS) typed it STAR, so the home system's
 * PRIMARY_STAR record was lost on the next start and "navigate to home system" found nothing (bundle 2026-10-11).
 *
 * <p>Synchronous, so it lives outside the quarantined subscriber package and runs in CI.
 */
class PreScanPrimaryStarTypeTest {

    private final LocationManager locationManager = LocationManager.getInstance();

    @Test
    void aLoginAtTheMainStarIsFiledAsThePrimaryStar() {
        EventBus privateBus = new EventBus("pre-scan-test");
        privateBus.register(new SilentPersistenceSubscriber());
        long sysAddr = 7268024264101L;
        String star = "Synuefuae PD-J b42-7";

        privateBus.post(starLocation(star, sysAddr, star, 0L, 0.0));

        assertEquals(LocationDto.LocationType.PRIMARY_STAR,
                locationManager.findBySystemAddress(sysAddr, star).getLocationType());
        assertEquals(star, locationManager.findPrimaryStar(sysAddr).getStarName());
    }

    @Test
    void aLoginAtACompanionStarIsFiledAsAStar() {
        EventBus privateBus = new EventBus("pre-scan-test");
        privateBus.register(new SilentPersistenceSubscriber());
        long sysAddr = 7268024264102L;
        String star = "Synuefuae PD-J b42-8";
        String companion = star + " B";

        privateBus.post(starLocation(star, sysAddr, companion, 2L, 1520.75));

        assertEquals(LocationDto.LocationType.STAR,
                locationManager.findBySystemAddress(sysAddr, companion).getLocationType());
    }

    private static LocationEvent starLocation(String starSystem, long systemAddress, String body,
                                              long bodyId, double distFromStarLs) {
        JsonObject j = new JsonObject();
        j.addProperty("timestamp", Instant.now().toString());
        j.addProperty("event", "Location");
        j.addProperty("StarSystem", starSystem);
        j.addProperty("SystemAddress", systemAddress);
        j.addProperty("Body", body);
        j.addProperty("BodyID", bodyId);
        j.addProperty("BodyType", "Star");
        if (distFromStarLs > 0) j.addProperty("DistFromStarLS", distFromStarLs);
        j.addProperty("SystemAllegiance", "");
        j.addProperty("Population", 0L);
        JsonArray starPos = new JsonArray();
        starPos.add(5068.6875);
        starPos.add(-104.65625);
        starPos.add(-13.28125);
        j.add("StarPos", starPos);
        return new LocationEvent(j);
    }
}
