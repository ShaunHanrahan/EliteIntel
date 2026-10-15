package elite.intel.ai.brain.actions.handlers.commands.builtin;

import com.google.gson.JsonObject;
import elite.intel.ai.brain.actions.ActionParameterSpec;
import elite.intel.session.Status;
import elite.intel.session.SystemSession;
import elite.intel.ui.overlay.HudDisplayMode;
import elite.intel.ui.overlay.HudOverlayPresence;
import elite.intel.util.StringUtls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Only the paths that answer before the overlay is touched: the success path spawns the real overlay.
 */
class MoveHudOverlayToScreenSectionCommandTest {

    private final MoveHudOverlayToScreenSectionCommand command = new MoveHudOverlayToScreenSectionCommand();
    private final HudOverlayPresence presence = HudOverlayPresence.getInstance();
    private SystemSession.HudOverlayLayout previousLayout;
    private HudDisplayMode previousMode;

    @BeforeEach
    void remember() {
        previousLayout = SystemSession.getInstance().getHudOverlayLayout();
        previousMode = presence.overlay().getDisplayMode();
    }

    @AfterEach
    void restore() {
        presence.overlay().setDisplayMode(previousMode);
        SystemSession.getInstance().setHudOverlayLayout(previousLayout);
    }

    @Test
    void theDirectionIsAClosedSetOfTheEightCompassPoints() {
        ActionParameterSpec direction = command.parameters().getFirst();

        assertEquals("direction", direction.getName());
        assertTrue(direction.isRequired());
        assertEquals(List.of("north_west", "north", "north_east", "west", "east", "south_west", "south", "south_east"),
                direction.getEnumValues());
    }

    @Test
    void aMissingDirectionIsAskedForInsteadOfGuessed() {
        presence.overlay().setDisplayMode(HudDisplayMode.DESKTOP);

        assertEquals(StringUtls.localizedResponse("handler.overlay.whichDirection"), command.execute(new JsonObject(), ""));
        JsonObject nonsense = new JsonObject();
        nonsense.addProperty("direction", "upwards");
        assertEquals(StringUtls.localizedResponse("handler.overlay.whichDirection"), command.execute(nonsense, ""));
    }

    /**
     * In the headset the card is placed by the VR setting or the capture tool, so there is nothing to move.
     */
    @Test
    void itIsNotOfferedWithoutADesktopCard() {
        presence.overlay().setDisplayMode(HudDisplayMode.VR);
        assertFalse(command.isVisibleForLLM(Status.getInstance()));
        JsonObject params = new JsonObject();
        params.addProperty("direction", "north_east");
        assertEquals(StringUtls.localizedResponse("handler.overlay.desktopOnly"), command.execute(params, ""));

        presence.overlay().setDisplayMode(HudDisplayMode.BOTH);
        assertTrue(command.isVisibleForLLM(Status.getInstance()));
    }
}
