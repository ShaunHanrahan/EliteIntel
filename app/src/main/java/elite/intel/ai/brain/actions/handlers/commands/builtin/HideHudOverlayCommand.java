package elite.intel.ai.brain.actions.handlers.commands.builtin;

import com.google.gson.JsonObject;
import elite.intel.ai.brain.actions.handlers.commands.IntelCommand;
import elite.intel.ai.brain.actions.handlers.commands.RegisterCommand;
import elite.intel.session.Status;
import elite.intel.ui.overlay.HudOverlayPresence;

/**
 * "Close the overlay" - takes the HUD overlay off screen, the same as the AI tab's button. Its placement is
 * kept, so showing it again puts it back where it was.
 */
@RegisterCommand
public final class HideHudOverlayCommand implements IntelCommand {
    public static final String ID = "hide_hud_overlay";

    @Override
    public String llmDescription() {
        return "Close the HUD overlay, taking it off screen.";
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * Only while the overlay is on.
     */
    @Override
    public boolean isVisibleForLLM(Status status) {
        return HudOverlayPresence.getInstance().isShowing();
    }

    @Override
    public String execute(JsonObject params, String responseText) {
        HudOverlayPresence.getInstance().hide();
        return null;
    }
}
