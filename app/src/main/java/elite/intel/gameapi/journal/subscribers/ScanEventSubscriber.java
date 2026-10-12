package elite.intel.gameapi.journal.subscribers;

import com.google.common.eventbus.Subscribe;
import elite.intel.ai.brain.commons.BiomeAnalyzer;
import elite.intel.ai.brain.vega.VegaRuntime;
import elite.intel.db.managers.LocationManager;
import elite.intel.gameapi.journal.ScanBodyClassifier;
import elite.intel.gameapi.journal.events.ScanEvent;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.session.PlayerSession;
import elite.intel.session.SystemSession;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;

import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.*;
import static elite.intel.util.StringUtls.localizedEvent;
import static elite.intel.util.StringUtls.subtractString;

@SuppressWarnings("unused")
public class ScanEventSubscriber {


    /**
     * {@code ScanType} of the bulk system dump a nav beacon produces. Its discovery flags are not to be
     * believed; see {@link #carriesNoDiscoveryInformation}.
     */
    private static final String NAV_BEACON_SCAN = "NavBeaconDetail";
    private static final Logger log = LogManager.getLogger(ScanEventSubscriber.class);
    private static final Set<String> valuablePlanetClasses = Set.of(
            "ammonia world",
            "water world",
            "earthlike body",
            "water giant",
            "gas giant with ammonia-based life",
            "helium gas giant",
            "class v gas giant",
            "class iv gas giant",
            "sudarsky class ii gas giant",
            "gas giant with water-based life"
    );
    private final PlayerSession playerSession = PlayerSession.getInstance();
    private final SystemSession systemSession = SystemSession.getInstance();
    private final LocationManager locationManager = LocationManager.getInstance();
    private final BiomeAnalyzer biomeAnalyzer = BiomeAnalyzer.getInstance();

    /**
     * Bodies already announced in {@link #announcedSystemAddress}; see {@link #isFirstAnnouncementForBody}.
     */
    private final Set<Long> announcedBodies = new HashSet<>();
    private long announcedSystemAddress = Long.MIN_VALUE;

    private static String getDetails(ScanEvent event, String shortName) {
        boolean hasMats = event.getMaterials() != null && !event.getMaterials().isEmpty();
        boolean isTerraformable = event.getTerraformState() != null && !event.getTerraformState().isEmpty();
        boolean isLandable = event.isLandable();
        return "Previously discovered: " + shortName + " "
                + (hasMats ? ". Materials detected. " : " ")
                + (isTerraformable ? " Terraformable, " : " ")
                + (isLandable ? " landable. " : ". ");
    }

    @Subscribe
    public void onScanEvent(ScanEvent event) {
        Thread.ofVirtual().start(() -> {
            if (event.getBodyName() == null || event.getBodyID() == null) return;
            if (BELT_CLUSTER.equals(ScanBodyClassifier.classify(event))) return;

            LocationDto system = locationManager.findSystemRecord(event.getSystemAddress());
            LocationDto location = locationManager.updateNamedBody(event.getSystemAddress(), event.getBodyID(),
                    event.getBodyName(), body -> ScanRecord.describe(body, event, system));

            announceIfNewDiscovery(event, location, system.getPopulation());
            playerSession.setLastScan(location);
        });
    }

    private void announceIfNewDiscovery(ScanEvent event, LocationDto location, long systemPopulation) {
        if (carriesNoDiscoveryInformation(event, systemPopulation)) return;

        boolean wasDiscovered = event.isWasDiscovered();
        boolean wasMapped = event.isWasMapped();
        String shortName = subtractString(event.getBodyName(), event.getStarSystem());

        if (!wasDiscovered && PLANET.equals(location.getLocationType())) {
            if (event.getTerraformState() != null && !event.getTerraformState().isEmpty()) {
                announceOnce(event, localizedEvent("event.scan.newTerraformable", shortName));
            } else if (event.getPlanetClass() != null && valuablePlanetClasses.contains(event.getPlanetClass().toLowerCase())) {
                announceOnce(event, localizedEvent("event.scan.newDiscovery", event.getPlanetClass()));
            }
        }

        if (wasDiscovered && !STAR.equals(location.getLocationType())) {
            if (!BELT_CLUSTER.equals(location.getLocationType())) {
                String sensorData = getDetails(event, shortName);

                log.info(sensorData);
            }
        } else if (!wasDiscovered && PRIMARY_STAR.equals(location.getLocationType())) {
            announceOnce(event, localizedEvent("event.scan.newSystem"));
        }
    }

    /**
     * The journal's {@code WasDiscovered} flag is not to be believed in two situations, and both have
     * announced "New System discovered!" to a commander sitting in the middle of the bubble.
     * <p>
     * A nav beacon hands over the whole system's body data at once, and the Scan events it produces report
     * {@code WasDiscovered:false} for bodies that were charted decades ago. The flags describe how <em>this</em>
     * scan learned about the body, not whether anyone had been there before. The journal contradicts itself
     * outright: of 97 {@code NavBeaconDetail} scans in one session, 37 claimed {@code WasMapped:true} with
     * {@code WasDiscovered:false}, which cannot happen. Believing them announced Wolf 1323, a populated system
     * with named planets.
     * <p>
     * A populated system has no discovery record at all. Nat9481 (380,000 people, a Powerplay stronghold, two
     * fleet carriers parked in it) reported {@code WasDiscovered:false} on the commander's own {@code Detailed}
     * scan of its primary star, and its beacon flagged every one of its bodies the same way, mapped ones
     * included. Systems settled before anyone could discover them were never "discovered" as far as the game
     * is concerned. Whatever the flag says, nobody discovers a system that already has a population, so a
     * scan in one carries no discovery information either.
     *
     * @param systemPopulation the population the journal reported on arrival, 0 when unknown or empty
     */
    static boolean carriesNoDiscoveryInformation(ScanEvent event, long systemPopulation) {
        return NAV_BEACON_SCAN.equalsIgnoreCase(event.getScanType()) || systemPopulation > 0;
    }

    /**
     * Announces a discovery at most once per body.
     * <p>
     * The game emits several Scan events for the same body - an {@code AutoScan} on arrival and a
     * {@code Detailed} scan when it is honked or targeted - all carrying the same
     * {@code WasDiscovered:false}. Without this guard each one announced, so arriving in an
     * undiscovered system said "New System discovered!" twice, seconds apart.
     */
    private void announceOnce(ScanEvent event, String message) {
        if (isFirstAnnouncementForBody(event.getSystemAddress(), event.getBodyID())) {
            VegaRuntime.narrator().announce(message, false);
        }
    }

    /**
     * Records a body as announced and reports whether this call was the first to do so.
     * <p>
     * Scoped to the current system so the set stays bounded on a long exploration run: leaving a
     * system drops its bodies, and re-entering one can only re-announce bodies that are by then
     * flagged {@code WasDiscovered:true} anyway. Synchronized because scans are handled on virtual
     * threads and two scans of the same body can be in flight together.
     * <p>
     * // WHY: a body with no {@code BodyID} always announces. Folding those onto one shared key would
     * silence every unidentified body in the system after the first, turning a missing field into a
     * lost discovery; repeating an announcement is the cheaper failure than never making it.
     */
    synchronized boolean isFirstAnnouncementForBody(long systemAddress, Long bodyId) {
        if (systemAddress != announcedSystemAddress) {
            announcedSystemAddress = systemAddress;
            announcedBodies.clear();
        }
        if (bodyId == null) {
            return true;
        }
        return announcedBodies.add(bodyId);
    }

}
