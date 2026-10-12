package elite.intel.gameapi.journal.subscribers;

import com.google.common.eventbus.Subscribe;
import elite.intel.ai.brain.vega.VegaRuntime;
import elite.intel.gameapi.SignalName;
import elite.intel.gameapi.data.BioForms;
import elite.intel.gameapi.journal.events.SAASignalsFoundEvent;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.session.PlayerSession;
import elite.intel.session.Status;
import elite.intel.util.ExoBio;
import elite.intel.util.TTSFriendlyNumberConverter;
import java.util.ArrayList;

import java.util.List;

import static elite.intel.util.StringUtls.localizedEvent;
import static elite.intel.util.StringUtls.spokenList;

public class SAASignalsFoundSubscriber {

    private final PlayerSession playerSession = PlayerSession.getInstance();

    private static void announce(String sb) {
        Status status = Status.getInstance();
        if (status.isInMainShip() && !status.isLanded() && !status.isDocked()) {
            String instructions = """
                        Report the signals detected on the body the sensor data names, and say which body it
                        is. List each signal type briefly, with how many of it were found.
                        If biological signals are present, name each genus and state the average projected payout.
                        State a first-discovery bonus only if the sensor data gives one, and keep any doubt it
                        expresses about it - never present an uncertain bonus as earnings.
                        If the sensor data says the survey there is already complete, say only that, naming the body.
                    """;
            VegaRuntime.narrator().narrate(sb, instructions);
        }
    }

    @Subscribe
    public void onSAASignalsFound(SAASignalsFoundEvent event) {
        Thread.ofVirtual().start(() -> {
            int liveSignals = event.getGenuses() != null ? event.getGenuses().size() : 0;
            // WHY the latch is settled inside the write: it is stored on the body, and a concurrent Scan of the
            // same body must not save over it.
            LocationDto location = SaaSignalsRecord.record(event, body -> {
                if (liveSignals > 0) settleSurveyLatch(body);
            });
            if (location == null || !playerSession.isDiscoveryAnnouncementOn()) return;

            List<SAASignalsFoundEvent.Signal> signals = event.getSignals();
            if (signals == null || signals.isEmpty()) {
                announce(localizedEvent("event.signals.none"));
                return;
            }
            // The game re-reports every mapped body that comes into range in supercruise, so on the run
            // between a carrier and the unsurveyed moons of a system this fires for every surveyed one
            // passed. A body yields its organics once: for one already sampled out the whole listing is
            // data the commander has, and re-listing the genuses reads as work to do. One named sentence.
            // Named in both forms, because two surveyed moons with the same counts, nine seconds apart,
            // were heard as one report twice.
            StringBuilder sb = new StringBuilder();
            if (liveSignals > 0 && location.isBioScansCompleted()) {
                sb.append(" ").append(localizedEvent("event.signals.bioSurveyAlreadyComplete", event.getBodyName()));
            } else {
                appendSignalReport(sb, event, location, liveSignals);
            }
            announce(sb.toString());
        });
    }

    /**
     * The full report for a body still worth the detour: every signal type with its count, and for organics the
     * genuses with the projected payout.
     */
    private static void appendSignalReport(StringBuilder sb, SAASignalsFoundEvent event, LocationDto location, int liveSignals) {
        sb.append(" ").append(localizedEvent("event.signals.found", event.getBodyName())).append(" ");
        for (SAASignalsFoundEvent.Signal signal : event.getSignals()) {
            // The game's own wording, never the symbol - see SignalName. The mining update added
            // $PlanetaryMiningLocation_Name; and the narrator read it out as it stood.
            String type = SignalName.display(signal.getTypeLocalised(), signal.getType());
            // With the count: a body can carry 29 mining locations and 2 geological sites, and which
            // is worth the detour is the whole question the report is there to answer.
            if (type != null) {
                sb.append(" ").append(localizedEvent("event.signals.type", type, signal.getCount()));
            }
        }
        if (liveSignals == 0) return;

        sb.append(" ").append(localizedEvent("event.signals.exobio", liveSignals));
        long averageProjectedPayment = 0;
        long averageFirstDiscoveryBonus = 0;
        List<String> genusNames = new ArrayList<>();
        for (SAASignalsFoundEvent.Genus genus : event.getGenuses()) {
            BioForms.ProjectedPayment averagePayment = BioForms.getAverageProjectedPayment(genus.getGenus());
            if (averagePayment != null) {
                averageProjectedPayment = averageProjectedPayment + averagePayment.payment();
                averageFirstDiscoveryBonus = averageFirstDiscoveryBonus + averagePayment.firstDiscoveryBonus();
            }
            genusNames.add(genus.getGenusLocalised());
        }
        sb.append(" ").append(spokenList(genusNames)).append(" ");
        sb.append(localizedEvent("event.signals.avgPayment", TTSFriendlyNumberConverter.formatCreditsForSpeech(averageProjectedPayment)));
        // The bonus is Vista Genomics' payment for being first to log the organism, which
        // is not the same question as who charted the body. On a body nobody had found,
        // nobody can have sampled it either, so the bonus is ours to claim. On a charted
        // body it is genuinely unknown - the journal never says whether anyone sampled
        // here - so it is offered as a possibility, never added to a projection.
        if (averageFirstDiscoveryBonus > 0) {
            sb.append(" ").append(location.isOurDiscovery()
                    ? localizedEvent("event.signals.firstDiscoveryBonus", TTSFriendlyNumberConverter.formatCreditsForSpeech(averageFirstDiscoveryBonus))
                    : localizedEvent("event.signals.firstDiscoveryBonusUncertain", TTSFriendlyNumberConverter.formatCreditsForSpeech(averageFirstDiscoveryBonus)));
        }
    }

    /**
     * Marks the body sampled out when it is; the caller reads the answer back from
     * {@link LocationDto#isBioScansCompleted()} on the saved record.
     *
     * <p>The stored flag is the authority, because the derivation behind it does not survive a sale:
     * completed samples are session state that {@code SellOrganicData} clears. Deriving it as well
     * heals bodies finished before the flag existed, and bodies whose last sample was taken while
     * this DSS had not yet written a genus list to count against.
     *
     * <p>What it replaced answered a different question: it said "complete" as soon as <em>one</em>
     * genus on the body had been sampled, which silenced the survey briefing for every body the
     * commander had merely started.
     */
    private void settleSurveyLatch(LocationDto location) {
        if (location.isBioScansCompleted()) return;
        if (!ExoBio.isSurveyComplete(location.getGenus(), playerSession.getBioCompletedSamples(), location.getPlanetName())) {
            return;
        }
        location.markBioScansCompleted();
    }

}
