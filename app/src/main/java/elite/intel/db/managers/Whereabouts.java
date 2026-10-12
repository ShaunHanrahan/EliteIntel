package elite.intel.db.managers;

import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.session.Position;

/**
 * The commander's position with the records it points at, read together so a reader never pairs one system's
 * name with another system's record.
 *
 * @param body   the record of the body the commander is at; the not-found marker (body id -1) when none is on record
 * @param system the record holding the system's facts (security, allegiance, coordinates); the not-found marker
 *               when no arrival in this system is on record
 */
public record Whereabouts(Position position, LocationDto body, LocationDto system) {

    public String starSystem() {
        return position.starSystem();
    }
}
