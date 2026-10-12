package elite.intel.session;

import java.util.Objects;

/**
 * Where the commander is: the star system, and the body within it the journal last placed them at.
 * <p>
 * The system's address and its name travel together so they can never disagree. They used to be written by
 * separate calls, and events that moved one without the other left "where are we" naming one system while the
 * body record came from another (bundle 2026-10-11).
 *
 * @param bodyId the BodyID of the body the commander is at, or null when the event that placed them named none.
 *               It is the journal's id for a body, unique only within the system, and says nothing about which
 *               body is the primary star.
 */
public record Position(long systemAddress, String starSystem, Long bodyId) {

    public Position {
        if (systemAddress <= 0) {
            throw new IllegalArgumentException("A position needs the system's address, got " + systemAddress);
        }
        if (starSystem == null || starSystem.isBlank()) {
            throw new IllegalArgumentException("A position needs the system's name (address " + systemAddress + ")");
        }
    }

    public boolean isIn(long otherSystemAddress) {
        return systemAddress == otherSystemAddress;
    }

    public boolean isAt(long otherSystemAddress, long otherBodyId) {
        return isIn(otherSystemAddress) && Objects.equals(bodyId, otherBodyId);
    }
}
