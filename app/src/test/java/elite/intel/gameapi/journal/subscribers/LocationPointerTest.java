package elite.intel.gameapi.journal.subscribers;

import com.google.gson.JsonParser;
import elite.intel.gameapi.journal.events.LocationEvent;
import elite.intel.session.PlayerSession;
import elite.intel.session.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The carrier jumped while the game was closed, so no CarrierJump was ever written: the startup replay left
 * the commander in the system the last journal ended in, and the game's own Location line at load was the
 * only word of the new one. "Where are we" kept naming the old system all session, and "set this as our home
 * system" stored it (bundle 2026-10-11).
 */
class LocationPointerTest {

    /**
     * Verbatim from that session's journal, trimmed to the fields the pointer reads.
     */
    private static final String LOAD_ON_A_MOVED_CARRIER = """
            { "timestamp":"2026-10-12T05:25:04Z", "event":"Location", "Docked":true, "StationName":"GHY-L8X",
              "StationType":"FleetCarrier", "MarketID":3712500736, "Taxi":false, "Multicrew":false,
              "StarSystem":"Hyades Sector MH-V c2-8", "SystemAddress":2283077046962,
              "StarPos":[119.90625,-87.68750,-184.62500], "Body":"Hyades Sector MH-V c2-8", "BodyID":0,
              "BodyType":"Star" }
            """;

    private final PlayerSession player = PlayerSession.getInstance();

    @Test
    void aSessionStartingInAnotherSystemMovesThePositionThere() {
        player.moveTo(new Position(18270924776817L, "Synuefe UC-I b43-8", 0L));

        new LocationSubscriber().pointAt(new LocationEvent(JsonParser.parseString(LOAD_ON_A_MOVED_CARRIER).getAsJsonObject()));

        assertEquals(new Position(2283077046962L, "Hyades Sector MH-V c2-8", 0L), player.position().orElseThrow());
    }
}
