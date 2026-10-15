package elite.intel.ui.overlay;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HudScreenSectionTest {

    @Test
    void theRememberedSpotsSurviveARoundTrip() {
        Map<HudScreenSection, Point> points = new EnumMap<>(HudScreenSection.class);
        points.put(HudScreenSection.SOUTH_WEST, new Point(40, 1040));
        points.put(HudScreenSection.NORTH_EAST, new Point(-1200, 40));

        String stored = HudScreenSection.formatPoints(points);

        assertEquals("ne=-1200,40;sw=40,1040", stored, "compass order, so the stored text is stable");
        assertEquals(points, HudScreenSection.parsePoints(stored));
    }

    /**
     * The same leniency as the colour overrides: one bad entry costs that section its spot, never the rest.
     */
    @Test
    void aMalformedEntryCostsOnlyItsOwnSection() {
        Map<HudScreenSection, Point> parsed = HudScreenSection.parsePoints("ne=1720,40;up=1,2;sw=oops;c=5,5;s=10,20");

        assertEquals(Map.of(HudScreenSection.NORTH_EAST, new Point(1720, 40),
                HudScreenSection.SOUTH, new Point(10, 20)), parsed);
        assertEquals(Map.of(), HudScreenSection.parsePoints(""));
        assertEquals(Map.of(), HudScreenSection.parsePoints(null));
    }

    @Test
    void theCentreIsNeverOffered() {
        assertEquals(List.of("north_west", "north", "north_east", "west", "east", "south_west", "south", "south_east"),
                HudScreenSection.commandableParameterNames());
        assertEquals(Optional.empty(), HudScreenSection.fromParameter("centre"));
    }

    /**
     * A model told the enum is {@code north_east} still sometimes writes what the commander said.
     */
    @Test
    void aDirectionIsReadTheWayAModelWritesIt() {
        assertEquals(Optional.of(HudScreenSection.NORTH_EAST), HudScreenSection.fromParameter("north_east"));
        assertEquals(Optional.of(HudScreenSection.NORTH_EAST), HudScreenSection.fromParameter("North East"));
        assertEquals(Optional.of(HudScreenSection.SOUTH_WEST), HudScreenSection.fromParameter("south-west"));
        assertEquals(Optional.empty(), HudScreenSection.fromParameter("upwards"));
        assertEquals(Optional.empty(), HudScreenSection.fromParameter(null));
    }

    @Test
    void anUnknownStoredSectionReadsAsNone() {
        assertEquals(Optional.of(HudScreenSection.WEST), HudScreenSection.parseStored("WEST"));
        assertEquals(Optional.empty(), HudScreenSection.parseStored(""));
        assertEquals(Optional.empty(), HudScreenSection.parseStored("SIDEWAYS"));
    }
}
