package elite.intel.ui.overlay;

import elite.intel.session.SystemSession;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The overlay ships as a separate binary, so it can simply be absent: stripped by an installer, lost
 * by an auto-update, or never built on a contributor's machine. {@link HudOverlayPresence} reports what
 * {@code start()} returns, and the button follows it, so a start that fails must say so rather than
 * throw or claim success - otherwise the button says HIDE OVERLAY with nothing on screen.
 */
class NativeHudOverlayLifecycleTest {

    @Test
    void aMissingBinaryFailsToStartInsteadOfThrowing() {
        NativeHudOverlay overlay = new NativeHudOverlay(() -> Path.of("/nonexistent/elite-intel-overlay"));

        boolean started = assertDoesNotThrow(overlay::start);

        assertFalse(started, "a missing binary must leave the toggle honest");
        assertFalse(overlay.isRunning());
    }

    /**
     * A directory is a path that exists and still is not something we can run.
     */
    @Test
    void aPathThatIsNotAFileFailsTheSameWay() {
        NativeHudOverlay overlay = new NativeHudOverlay(() -> Path.of(System.getProperty("java.io.tmpdir")));

        assertFalse(overlay.start());
        assertFalse(overlay.isRunning());
    }

    /**
     * Teardown runs on the failure path too: nothing was started, and stop() must not care.
     */
    @Test
    void stoppingSomethingThatNeverStartedIsHarmless() {
        NativeHudOverlay overlay = new NativeHudOverlay(() -> Path.of("/nonexistent/elite-intel-overlay"));
        overlay.start();

        assertDoesNotThrow(overlay::stop);
        assertDoesNotThrow(overlay::stop, "a double stop is a normal toggle sequence");
        assertFalse(overlay.isRunning());
    }

    /**
     * The restore guard, which is where the bug actually lived. Persisting a negative coordinate always
     * worked; what failed was applying one, because the guard read it as the "unset" sentinel and left the
     * window at its centred default with only the y honoured.
     */
    @Test
    void aNegativeLegacyCornerIsStillRestored() {
        withLayout(new SystemSession.HudOverlayLayout(
                0.5, 1.0, 760, -3, 590, "DESKTOP", "TOP_RIGHT", "", "", ""), () -> {
            assertEquals(List.of("CFG\tx=-3\ty=590"), overlay().placementLinesOnStart(),
                    "a row from before sections is sent as a corner for the overlay to convert");
        });
        withLayout(new SystemSession.HudOverlayLayout(
                0.5, 1.0, 760, OverlayProtocol.POSITION_UNSET, OverlayProtocol.POSITION_UNSET,
                "DESKTOP", "TOP_RIGHT", "", "", ""), () -> {
            assertEquals(List.of(), overlay().placementLinesOnStart(),
                    "a never-positioned card must still open where it defaults to");
        });
    }

    @Test
    void aPlacedCardIsRestoredToItsSection() {
        withLayout(new SystemSession.HudOverlayLayout(
                0.5, 1.0, 760, -1200, 1040, "DESKTOP", "BOTTOM", "", "SOUTH_WEST", "sw=-1200,1040"), () -> {
            assertEquals(List.of("CFG\trestore=sw\tx=-1200\ty=1040"), overlay().placementLinesOnStart());
        });
    }

    /**
     * "Move the overlay" while it is closed: the move is held for the next start and goes after the restore,
     * because a section names part of the monitor the card was left on, which the restore establishes.
     */
    @Test
    void aMoveWhileClosedFollowsTheRestoreWithTheRememberedSpot() {
        withLayout(new SystemSession.HudOverlayLayout(
                0.5, 1.0, 760, 3000, 80, "DESKTOP", "BOTTOM", "", "NORTH_WEST", "nw=3000,80;ne=4400,60"), () -> {
            NativeHudOverlay overlay = overlay();
            overlay.moveTo(HudScreenSection.NORTH_EAST);
            assertEquals(List.of("CFG\trestore=nw\tx=3000\ty=80", "CFG\tmove=ne\tx=4400\ty=60"),
                    overlay.placementLinesOnStart());
        });
    }

    @Test
    void aReportedPlacementIsRememberedForItsSection() {
        withLayout(new SystemSession.HudOverlayLayout(
                0.5, 1.0, 760, 10, 10, "DESKTOP", "BOTTOM", "", "", ""), () -> {
            overlay().rememberPlacement(new HudPlacement(HudScreenSection.SOUTH_EAST, new Point(3383, 1383)));

            SystemSession.HudOverlayLayout stored = SystemSession.getInstance().getHudOverlayLayout();
            assertEquals("SOUTH_EAST", stored.section());
            assertEquals(3383, stored.x(), "the legacy corner is replaced by the anchor");
            assertEquals("se=3383,1383", stored.sectionPoints());
        });
    }

    /**
     * A drag can end in the middle of the screen, but nobody can ask for it there, so that spot is where the
     * card is and not a section's memory.
     */
    @Test
    void aCardLeftInTheCentreIsPlacedButNotRemembered() {
        withLayout(new SystemSession.HudOverlayLayout(
                0.5, 1.0, 760, 10, 10, "DESKTOP", "BOTTOM", "", "", "ne=4400,60"), () -> {
            overlay().rememberPlacement(new HudPlacement(HudScreenSection.CENTRE, new Point(1720, 720)));

            SystemSession.HudOverlayLayout stored = SystemSession.getInstance().getHudOverlayLayout();
            assertEquals("CENTRE", stored.section());
            assertEquals("ne=4400,60", stored.sectionPoints());
        });
    }

    @Test
    void theCentreIsNotASectionToMoveTo() {
        assertThrows(IllegalArgumentException.class, () -> overlay().moveTo(HudScreenSection.CENTRE));
    }

    private static NativeHudOverlay overlay() {
        return new NativeHudOverlay(() -> Path.of("/nonexistent/elite-intel-overlay"));
    }

    private static void withLayout(SystemSession.HudOverlayLayout layout, Runnable check) {
        SystemSession session = SystemSession.getInstance();
        SystemSession.HudOverlayLayout previous = session.getHudOverlayLayout();
        try {
            session.setHudOverlayLayout(layout);
            check.run();
        } finally {
            session.setHudOverlayLayout(previous);
        }
    }
}
