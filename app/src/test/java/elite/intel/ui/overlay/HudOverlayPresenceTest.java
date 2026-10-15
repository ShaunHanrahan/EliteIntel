package elite.intel.ui.overlay;

import com.google.common.eventbus.Subscribe;
import elite.intel.eventbus.UiBus;
import elite.intel.session.SystemSession;
import elite.intel.ui.event.HudOverlayVisibilityChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The button and voice both switch the overlay through the presence, so what it stores and announces is
 * what keeps them agreeing. Run against a binary that is not there, so nothing is ever spawned.
 */
class HudOverlayPresenceTest {

    private final SystemSession session = SystemSession.getInstance();
    private final List<Boolean> announced = new CopyOnWriteArrayList<>();
    private final Object listener = new Object() {
        @Subscribe
        public void on(HudOverlayVisibilityChangedEvent event) {
            announced.add(event.visible());
        }
    };
    private boolean previouslyVisible;
    private SystemSession.HudOverlayLayout previousLayout;

    @BeforeEach
    void remember() {
        previouslyVisible = session.isHudOverlayVisible();
        previousLayout = session.getHudOverlayLayout();
        UiBus.register(listener);
    }

    @AfterEach
    void restore() {
        UiBus.unregister(listener);
        session.setHudOverlayVisible(previouslyVisible);
        session.setHudOverlayLayout(previousLayout);
    }

    /**
     * A broken install is not a change of mind: the commander asked for the overlay, so that is stored, and
     * fixing the install brings it back. What is announced is what happened, so the button stays honest.
     */
    @Test
    void aFailedShowStoresTheRequestAndAnnouncesTheFailure() {
        session.setHudOverlayVisible(false);

        assertFalse(presence().show());

        assertTrue(session.isHudOverlayVisible());
        assertEquals(List.of(false), announced);
    }

    @Test
    void hideStoresOff() {
        session.setHudOverlayVisible(true);

        presence().hide();

        assertFalse(session.isHudOverlayVisible());
        assertEquals(List.of(false), announced);
    }

    /**
     * Asking to put the overlay somewhere means wanting to see it, so a move while closed opens it.
     */
    @Test
    void aMoveWhileClosedAsksForTheOverlay() {
        session.setHudOverlayVisible(false);

        presence().moveTo(HudScreenSection.SOUTH_EAST);

        assertTrue(session.isHudOverlayVisible());
    }

    @Test
    void restoreNeverWritesTheSetting() {
        session.setHudOverlayVisible(true);

        presence().restore();

        assertTrue(session.isHudOverlayVisible(), "a failed start must not switch the stored overlay off");
    }

    private static HudOverlayPresence presence() {
        return new HudOverlayPresence(new NativeHudOverlay(() -> Path.of("/nonexistent/elite-intel-overlay")));
    }
}
