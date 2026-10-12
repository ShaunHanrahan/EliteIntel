package elite.intel.junit.gameapi.journal.subscribers;

import com.google.gson.JsonObject;
import elite.intel.gameapi.journal.events.SupercruiseExitEvent;
import elite.intel.gameapi.journal.subscribers.SupercruiseExitedSubscriber;
import elite.intel.session.PlayerSession;
import elite.intel.session.Position;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class SupercruiseExitedSubscriberTest {

    private final SupercruiseExitedSubscriber subscriber = new SupercruiseExitedSubscriber();
    private final PlayerSession session = PlayerSession.getInstance();

    @Test
    void supercruiseExitUpdatesCurrentLocationIdInSession() throws InterruptedException {
        long sysAddr = 555_444_333L;
        long bodyId = 12L;
        session.moveTo(new Position(sysAddr, "Wolf 359", null));

        subscriber.onSupercruiseExited(supercruiseExitEvent(sysAddr, bodyId, "Wolf 359 A 1", "Planet"));

        awaitTrue(() -> {
            Position loc = session.position().orElseThrow();
            return Long.valueOf(bodyId).equals(loc.bodyId()) && sysAddr == loc.systemAddress();
        });

        Position loc = session.position().orElseThrow();
        assertEquals(sysAddr, loc.systemAddress());
        assertEquals(bodyId, loc.bodyId());
    }

    @Test
    void supercruiseExitToStationUpdatesLocationId() throws InterruptedException {
        long sysAddr = 555_111_222L;
        long bodyId = 5L;
        session.moveTo(new Position(sysAddr, "Sol", null));

        subscriber.onSupercruiseExited(supercruiseExitEvent(sysAddr, bodyId, "Galileo", "Station"));

        awaitTrue(() -> Long.valueOf(bodyId).equals(session.position().orElseThrow().bodyId()));

        assertEquals(sysAddr, session.currentSystemAddress());
    }

    @Test
    void nullBodyIdDoesNotUpdateLocationId() throws InterruptedException {
        long uniqueSystem = 55544321L;
        long knownBodyId = 88L;
        session.moveTo(new Position(uniqueSystem, "Test System", knownBodyId));

        subscriber.onSupercruiseExited(supercruiseExitEventNullBodyId(uniqueSystem, "Wolf 359 A 1", "Planet"));

        Thread.sleep(300);

        Position loc = session.position().orElseThrow();
        assertEquals(knownBodyId, loc.bodyId(), "null BodyID must not overwrite current_location_id");
    }

    private static SupercruiseExitEvent supercruiseExitEvent(long systemAddress, long bodyId,
                                                             String body, String bodyType) {
        JsonObject j = new JsonObject();
        j.addProperty("timestamp", Instant.now().toString());
        j.addProperty("event", "SupercruiseExit");
        j.addProperty("StarSystem", "Sol");
        j.addProperty("SystemAddress", systemAddress);
        j.addProperty("Body", body);
        j.addProperty("BodyID", bodyId);
        j.addProperty("BodyType", bodyType);
        j.addProperty("Taxi", false);
        j.addProperty("Multicrew", false);
        return new SupercruiseExitEvent(j);
    }

    private static SupercruiseExitEvent supercruiseExitEventNullBodyId(long systemAddress,
                                                                       String body, String bodyType) {
        JsonObject j = new JsonObject();
        j.addProperty("timestamp", Instant.now().toString());
        j.addProperty("event", "SupercruiseExit");
        j.addProperty("StarSystem", "Wolf 359");
        j.addProperty("SystemAddress", systemAddress);
        j.addProperty("Body", body);
        // BodyID intentionally omitted — Gson will deserialise it as null
        j.addProperty("BodyType", bodyType);
        j.addProperty("Taxi", false);
        j.addProperty("Multicrew", false);
        return new SupercruiseExitEvent(j);
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("Condition not met within 2 seconds");
            Thread.sleep(10);
        }
    }
}
