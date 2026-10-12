package elite.intel.gameapi.journal.subscribers;

import elite.intel.gameapi.journal.ScanBodyClassifier;
import elite.intel.gameapi.journal.events.FSSBodySignalsEvent;
import elite.intel.gameapi.journal.events.SAASignalsFoundEvent;
import elite.intel.gameapi.journal.events.ScanEvent;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.gameapi.journal.events.dto.MaterialDto;
import elite.intel.util.GravityCalculator;

import java.util.ArrayList;
import java.util.List;

import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.MOON;
import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.PLANETARY_RING;

/**
 * What a Scan says about a body, written onto its record. Shared by the live subscriber and the startup pre-scan,
 * which kept two copies of this that had drifted: the live one counted the body's signals and the replay did not,
 * the replay recorded a ring's parent and the live one did not.
 * <p>
 * A Scan is the journal's own description of the body, so it overwrites what is there - including the type, since
 * a star's distance from the arrival point is the one proof of which star is primary.
 */
final class ScanRecord {

    private static final String BIOLOGICAL = "$SAA_SignalType_Biological;";
    private static final String GEOLOGICAL = "$SAA_SignalType_Geological;";

    private ScanRecord() {
    }

    /**
     * @param system the record of the system's facts (see {@code LocationManager.findSystemRecord}): its
     *               coordinates, and its population, which decides whether the scan's discovery flags mean anything
     */
    static void describe(LocationDto location, ScanEvent event, LocationDto system) {
        LocationDto.LocationType locationType = ScanBodyClassifier.classify(event);
        location.setSystemAddress(event.getSystemAddress());
        location.setBodyId(event.getBodyID());
        location.setStarName(event.getStarSystem());
        location.setPlanetName(event.getBodyName());
        location.setPlanetShortName(event.getBodyName());
        location.setX(system.getX());
        location.setY(system.getY());
        location.setZ(system.getZ());
        location.setLocationType(locationType);
        if (MOON.equals(locationType) && event.getParents() != null && !event.getParents().isEmpty()) {
            ScanEvent.Parent first = event.getParents().getFirst();
            // WHY: day length for a moon uses its parent planet's orbital period.
            if (first.getPlanet() != null) location.setParentBodyId(first.getPlanet());
        }
        if (PLANETARY_RING.equals(locationType)) {
            location.setParentBodyName(ScanBodyClassifier.ringParent(event.getBodyName()));
        }

        location.setStarClass(event.getStarType());
        location.setPlanetClass(event.getPlanetClass());
        location.setVolcanism(event.getVolcanism());
        location.setMassEM(event.getMassEM());
        location.setRadius(event.getRadius());
        // WHY computed: the journal's SurfaceGravity is not accurate.
        Double gravity = GravityCalculator.calculateSurfaceGravity(event.getMassEM(), event.getRadius());
        if (gravity != null) location.setGravity(gravity);
        location.setSurfaceTemperature(event.getSurfaceTemperature());
        location.setLandable(event.isLandable());
        location.setTerraformable("Terraformable".equalsIgnoreCase(event.getTerraformState()));
        location.setTidalLocked(event.isTidalLock());
        location.setAtmosphere(event.getAtmosphereType());
        location.setDistance(event.getDistanceFromArrivalLS());
        location.setOrbitalPeriod(event.getOrbitalPeriod());
        location.setRotationPeriod(event.getRotationPeriod());
        location.setAxialTilt(event.getAxialTilt());
        if (event.getMaterials() != null) location.setMaterials(materials(event.getMaterials()));
        // A nav beacon's discovery flags describe how this scan learned about the body, not who found
        // it first (see carriesNoDiscoveryInformation), so they are not recorded. Leaving the stored
        // values alone keeps whatever a real scan established; an unseen body then stays "not ours",
        // which is the only answer a beacon can support and the safe one in a populated system.
        if (!ScanEventSubscriber.carriesNoDiscoveryInformation(event, system.getPopulation())) {
            location.setOurDiscovery(!event.isWasDiscovered());
            location.setWeMappedIt(!event.isWasMapped());
        }
        countSignals(location);
    }

    /**
     * The bio and geo counts from the signal reports already on the record: the FSS report first, the DSS one for
     * whatever the FSS did not catch.
     */
    private static void countSignals(LocationDto location) {
        int bio = 0;
        int geo = 0;
        List<FSSBodySignalsEvent.Signal> fssSignals = location.getFssSignals();
        if (fssSignals != null) {
            for (FSSBodySignalsEvent.Signal signal : fssSignals) {
                if (BIOLOGICAL.equalsIgnoreCase(signal.getType())) bio += signal.getCount();
                if (GEOLOGICAL.equalsIgnoreCase(signal.getType())) geo += signal.getCount();
            }
        }
        List<SAASignalsFoundEvent.Signal> saaSignals = location.getSaaSignals();
        if ((bio == 0 || geo == 0) && saaSignals != null) {
            int fssBio = bio;
            int fssGeo = geo;
            for (SAASignalsFoundEvent.Signal signal : saaSignals) {
                if (fssBio == 0 && BIOLOGICAL.equalsIgnoreCase(signal.getType())) bio += signal.getCount();
                if (fssGeo == 0 && GEOLOGICAL.equalsIgnoreCase(signal.getType())) geo += signal.getCount();
            }
        }
        location.setBioSignals(bio);
        location.setGeoSignals(geo);
    }

    private static List<MaterialDto> materials(List<ScanEvent.Material> scanned) {
        List<MaterialDto> materials = new ArrayList<>();
        for (ScanEvent.Material material : scanned) {
            materials.add(new MaterialDto(material.getName(), material.getPercent()));
        }
        return materials;
    }
}
