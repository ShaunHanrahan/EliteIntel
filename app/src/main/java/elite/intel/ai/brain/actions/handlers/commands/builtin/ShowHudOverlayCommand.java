package elite.intel.ai.brain.actions.handlers.commands.builtin;

import com.google.gson.JsonObject;
import elite.intel.ai.brain.actions.handlers.commands.IntelCommand;
import elite.intel.ai.brain.actions.handlers.commands.RegisterCommand;
import elite.intel.session.Status;
import elite.intel.ui.overlay.HudOverlayPresence;
import elite.intel.util.StringUtls;

/**
 * "Show the overlay" - puts the HUD overlay on screen where it was last left, the same as the AI tab's button.
 * Silent when it works: the card appearing is the answer.
 */
@RegisterCommand
public final class ShowHudOverlayCommand implements IntelCommand {
    public static final String ID = "show_hud_overlay";

    @Override
    public String llmDescription() {
        return "Show the HUD overlay on screen, where it was last placed.";
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * Only while the overlay is off: there is nothing to show once it is on.
     */
    @Override
    public boolean isVisibleForLLM(Status status) {
        return !HudOverlayPresence.getInstance().isShowing();
    }

    @Override
    public String execute(JsonObject params, String responseText) {
        if (HudOverlayPresence.getInstance().show()) return null;
        return StringUtls.localizedResponse("handler.overlay.notStarted");
    }
}
