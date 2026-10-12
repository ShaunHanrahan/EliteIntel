package elite.intel.gameapi.journal.subscribers;

import com.google.common.eventbus.Subscribe;
import elite.intel.ai.brain.actions.handlers.CommandHandlerFactory;
import elite.intel.ai.brain.vega.VegaRuntime;
import elite.intel.ai.mouth.EventNarrator;
import elite.intel.gameapi.journal.events.SupercruiseDestinationDropEvent;
import elite.intel.session.PlayerSession;
import elite.intel.util.StringUtls;

public class SuperCruiseDropSubscriber {

    private final PlayerSession playerSession = PlayerSession.getInstance();
    private final CommandHandlerFactory commandHandlerFactory = CommandHandlerFactory.getInstance();

    @Subscribe
    public void onSuperCruiseDrop(SupercruiseDestinationDropEvent event) {
        Thread.ofVirtual().start(() -> {
            if (event.getThreat() > 0) {
                String instructions = """
                            Notify user about supercruise exit and threat level
                            - level 0 threat level is none
                            - level 1 - 2 threat level low
                            - level 3 - 5 threat level medium
                            - level 6 - 8 threat level high
                        """;
                VegaRuntime.narrator().narrate(" Dropped from supercruise. Threat level: " + event.getThreat() + ". ", instructions);
            }


            String carrierName = playerSession.getFleetCarrierData().getCarrierName();
            if (carrierName != null && event.getType().toUpperCase().startsWith(carrierName.toUpperCase())) {
                EventNarrator.say(StringUtls.localizedEvent("event.supercruise.welcomeHomeCarrier", StringUtls.capitalizeWords(carrierName)));
            }
        });
    }
}
