package elite.intel.ai.brain.actions.handlers.commands.builtin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import elite.intel.ai.brain.actions.ActionParameterSpec;
import elite.intel.ai.brain.actions.handlers.commands.IntelCommand;
import elite.intel.ai.brain.actions.handlers.commands.RegisterCommand;
import elite.intel.session.Status;
import elite.intel.ui.overlay.HudOverlayPresence;
import elite.intel.ui.overlay.HudScreenSection;
import elite.intel.util.StringUtls;

import java.util.List;
import java.util.Optional;

/**
 * "Move the overlay to the north east" - sends the desktop HUD card to a section of the monitor it is on,
 * to the spot the commander last left it in that section, or the section's default spot when they never
 * did. Opens the overlay first when it is closed: asking to put it somewhere means they want to see it.
 * <p>
 * Desktop only. In the headset the card is placed by the VR position setting, or by the capture tool.
 */
@RegisterCommand
public final class MoveHudOverlayToScreenSectionCommand implements IntelCommand {
    public static final String ID = "move_hud_overlay_to_screen_section";
    private static final String PARAM_DIRECTION = "direction";
    private static final List<ActionParameterSpec> PARAMETERS = buildParameters();

    @Override
    public String llmDescription() {
        return "Move the HUD overlay card to a part of the screen, named by compass direction: north is the top "
                + "edge, south the bottom, east the right, west the left.";
    }

    private static List<ActionParameterSpec> buildParameters() {
        List<String> directions = HudScreenSection.commandableParameterNames();
        ActionParameterSpec direction = new ActionParameterSpec(
                PARAM_DIRECTION,
                "string",
                true,
                "Where on the screen to put the overlay.",
                directions,
                "top -> north, bottom -> south, right -> east, left -> west; top right -> north_east, "
                        + "bottom left -> south_west.",
                directions);
        direction.validate();
        return List.of(direction);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<ActionParameterSpec> parameters() {
        return PARAMETERS;
    }

    /**
     * Only when the display mode has a desktop card. Offered whether or not it is showing, because moving it
     * opens it.
     */
    @Override
    public boolean isVisibleForLLM(Status status) {
        return HudOverlayPresence.getInstance().hasDesktopCard();
    }

    @Override
    public String execute(JsonObject params, String responseText) {
        HudOverlayPresence presence = HudOverlayPresence.getInstance();
        if (!presence.hasDesktopCard()) return StringUtls.localizedResponse("handler.overlay.desktopOnly");
        Optional<HudScreenSection> section = direction(params);
        if (section.isEmpty()) return StringUtls.localizedResponse("handler.overlay.whichDirection");
        if (presence.moveTo(section.get())) return null;
        return StringUtls.localizedResponse("handler.overlay.notStarted");
    }

    private static Optional<HudScreenSection> direction(JsonObject params) {
        JsonElement value = params == null ? null : params.get(PARAM_DIRECTION);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return Optional.empty();
        return HudScreenSection.fromParameter(value.getAsString());
    }
}
