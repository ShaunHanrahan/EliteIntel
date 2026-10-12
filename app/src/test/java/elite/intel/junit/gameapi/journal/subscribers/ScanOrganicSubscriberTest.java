package elite.intel.junit.gameapi.journal.subscribers;

import com.google.gson.JsonObject;
import elite.intel.gameapi.journal.events.ScanOrganicEvent;
import elite.intel.gameapi.journal.subscribers.ScanOrganicSubscriber;
import elite.intel.session.PlayerSession;
import elite.intel.session.Position;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScanOrganicSubscriberTest {

    private final ScanOrganicSubscriber subscriber = new ScanOrganicSubscriber();
    private final PlayerSession session = PlayerSession.getInstance();

    @Test
    void nullBodyDoesNotUpdateLocationId() throws InterruptedException {
        long uniqueSystem = 65001000L;
        long knownBodyId = 55L;
        session.moveTo(new Position(uniqueSystem, "Test System", knownBodyId));

        subscriber.onScanOrganicEvent(scanOrganicEventNullBody(uniqueSystem));

        Thread.sleep(300);

        Position loc = session.position().orElseThrow();
        assertEquals(knownBodyId, loc.bodyId(), "null Body must not overwrite current_location_id");
    }

    private static ScanOrganicEvent scanOrganicEventNullBody(long systemAddress) {
        JsonObject j = new JsonObject();
        j.addProperty("timestamp", Instant.now().toString());
        j.addProperty("event", "ScanOrganic");
        j.addProperty("ScanType", "Log");
        j.addProperty("Genus", "Bacterium");
        j.addProperty("Genus_Localised", "Bacterium Aurasus");
        j.addProperty("Species", "Bacterium Aurasus");
        j.addProperty("Species_Localised", "Bacterium Aurasus");
        j.addProperty("SystemAddress", systemAddress);
        // Body intentionally omitted — Gson will deserialise it as null
        return new ScanOrganicEvent(j);
    }
}
