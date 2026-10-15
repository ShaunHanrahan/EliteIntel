package elite.intel.ai.brain.actions.handlers.commands.builtin;

import com.google.gson.JsonObject;
import elite.intel.ai.brain.actions.handlers.commands.IntelCommand;
import elite.intel.ai.brain.actions.handlers.commands.RegisterCommand;
import elite.intel.gameapi.inputs.RoutePlotter;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.session.PlayerSession;
import elite.intel.session.Status;
import elite.intel.util.StringUtls;

import java.util.Optional;

/**
 * Self-describing "navigate to fleet carrier" command.
 * Owns its own execution: body migrated 1:1 from the legacy NavigateToMyFleetCarrier,
 * routed through CommandRegistry via the self-describing model.
 */
@RegisterCommand
public final class NavigateToFleetCarrierCommand implements IntelCommand {
    public static final String ID = "navigate_to_fleet_carrier";

    @Override
    public String llmDescription() {
        return "Plot a route to the commander's fleet carrier's last known location (falls back to the home system if no carrier is known).";
    }


    @Override
    public String id() {
        return ID;
    }

    /**
     * Route plotting is available anywhere in the game, so the command is offered everywhere.
     */
    @Override
    public boolean isVisibleForLLM(Status status) {
        return true;
    }

    @Override
    public String execute(JsonObject params, String responseText) {
        PlayerSession playerSession = PlayerSession.getInstance();
        Optional<String> destination = destination(playerSession.getLastKnownCarrierLocation(), playerSession.getHomeSystem());
        // Not Optional.map: plotRoute returns null when it plotted, which map would turn into "no destination".
        if (destination.isEmpty()) return StringUtls.localizedResponse("handler.navigate.noHomeSystem");
        return new RoutePlotter().plotRoute(destination.get());
    }

    /**
     * The carrier's last known system, else the home system, else nothing to plot to.
     * <p>
     * WHY the carrier's location is the test of having one: it is written only by events that witness the
     * commander's own carrier. This used to ask whether the carrier data and the home system were null - and
     * neither ever is, since both come back as an empty record when absent - so a commander with no carrier was
     * told "carrier location not available" and never reached the home system.
     */
    static Optional<String> destination(String carrierSystem, LocationDto homeSystem) {
        if (carrierSystem != null && !carrierSystem.isBlank()) return Optional.of(carrierSystem);
        boolean homeIsSet = homeSystem.getBodyId() != -1 && homeSystem.getStarName() != null && !homeSystem.getStarName().isBlank();
        return homeIsSet ? Optional.of(homeSystem.getStarName()) : Optional.empty();
    }
}
