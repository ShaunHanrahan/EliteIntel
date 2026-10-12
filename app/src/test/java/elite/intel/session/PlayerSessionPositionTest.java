package elite.intel.session;

import elite.intel.db.util.Database;
import elite.intel.util.Cypher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The commander's position is one value: the system's address, its name and the body, written together. Events
 * inside a system may move the body, but never the system - only a Location, FSDJump or CarrierJump may.
 */
class PlayerSessionPositionTest {

    private static final long HYADES = 2283077046962L;
    private static final long SYNUEFE = 18270924776817L;

    private final PlayerSession session = PlayerSession.getInstance();

    @BeforeAll
    static void boot() throws Exception {
        Cypher.initializeKey();
        Database.init().close();
    }

    @Test
    void anEventInsideTheSystemMovesOnlyTheBody() {
        session.moveTo(new Position(HYADES, "Hyades Sector MH-V c2-8", 0L));

        session.moveWithinSystem(HYADES, 21L);

        assertEquals(new Position(HYADES, "Hyades Sector MH-V c2-8", 21L), session.position().orElseThrow());
    }

    /**
     * Before this rule, a supercruise drop moved the body and address while the name stayed behind, and "where are
     * we" named Synuefe with Hyades' security (bundle 2026-10-11).
     */
    @Test
    void anEventNamingAnotherSystemLeavesThePositionAlone() {
        Position synuefe = new Position(SYNUEFE, "Synuefe UC-I b43-8", 0L);
        session.moveTo(synuefe);

        session.moveWithinSystem(HYADES, 2L);

        assertEquals(synuefe, session.position().orElseThrow());
    }

    @Test
    void aPositionNeedsTheSystemsAddressAndName() {
        assertThrows(IllegalArgumentException.class, () -> new Position(0, "Sol", 0L));
        assertThrows(IllegalArgumentException.class, () -> new Position(HYADES, " ", 0L));
    }
}
