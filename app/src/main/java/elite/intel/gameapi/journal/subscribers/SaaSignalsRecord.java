package elite.intel.gameapi.journal.subscribers;

import elite.intel.db.managers.LocationManager;
import elite.intel.gameapi.SignalName;
import elite.intel.gameapi.data.BioForms;
import elite.intel.gameapi.journal.ScanBodyClassifier;
import elite.intel.gameapi.journal.events.SAASignalsFoundEvent;
import elite.intel.gameapi.journal.events.dto.GenusDto;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.gameapi.journal.events.dto.MaterialDto;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static elite.intel.gameapi.journal.events.dto.LocationDto.LocationType.PLANETARY_RING;

/**
 * What a surface scan (DSS) found on a body, written onto its record. Shared by the live subscriber and the
 * startup pre-scan, which kept two copies that had drifted: the replay never recorded the genus list, and only the
 * live one marked a ring's parent as ringed.
 */
final class SaaSignalsRecord {

    private SaaSignalsRecord() {
    }

    /**
     * Files the scan under the body's name in one locked write, applying {@code alsoUnderLock} in the same write,
     * then marks a ring's parent as ringed.
     *
     * @return the record as saved, or null when the event names no body
     */
    static LocationDto record(SAASignalsFoundEvent event, Consumer<LocationDto> alsoUnderLock) {
        if (event.getBodyName() == null) return null;
        LocationManager locations = LocationManager.getInstance();
        LocationDto system = locations.findSystemRecord(event.getSystemAddress());
        String starName = locations.findStarName(event.getSystemAddress());

        LocationDto body = locations.updateNamedBody(event.getSystemAddress(), event.getBodyID(), event.getBodyName(),
                location -> {
                    describe(location, event, system, starName);
                    alsoUnderLock.accept(location);
                });
        if (PLANETARY_RING.equals(body.getLocationType())) {
            markParentRinged(locations, event.getSystemAddress(), body.getParentBodyName());
        }
        return body;
    }

    private static void describe(LocationDto location, SAASignalsFoundEvent event, LocationDto system, String starName) {
        location.setSystemAddress(event.getSystemAddress());
        location.setBodyId(event.getBodyID());
        location.setPlanetName(event.getBodyName());
        location.setStarName(starName);
        location.setX(system.getX());
        location.setY(system.getY());
        location.setZ(system.getZ());
        location.addSaaSignals(event.getSignals());

        List<SAASignalsFoundEvent.Genus> genuses = event.getGenuses();
        if (genuses != null && !genuses.isEmpty()) {
            location.setBioSignals(genuses.size());
            location.setGenus(toGenusDto(genuses, location.getPlanetName()));
        }
        // Rings are bodies: classify, record the parent, and keep the mining reserves.
        String ringParent = ScanBodyClassifier.ringParent(event.getBodyName());
        if (ringParent != null) {
            location.setLocationType(PLANETARY_RING);
            location.setParentBodyName(ringParent);
            if (event.getSignals() != null && !event.getSignals().isEmpty()) {
                location.setMaterials(toMaterials(event.getSignals()));
            }
        }
    }

    /**
     * WHY only a parent on record: the ring report names the parent but says nothing else about it, and a record
     * holding nothing but a ring flag would stand in for a body we know nothing of.
     */
    private static void markParentRinged(LocationManager locations, long systemAddress, String parentName) {
        if (parentName == null || parentName.isBlank()) return;
        LocationDto parent = locations.findBySystemAddress(systemAddress, parentName);
        if (parent.getStarName() == null || parent.getStarName().isBlank()) return;
        locations.updateNamedBody(systemAddress, parent.getBodyId(), parentName, p -> p.setHasRings(true));
    }

    /**
     * A ring's reserves. Named the way the game names them, because this list is read back to the commander:
     * "Low Temp. Diamonds", not the LowTemperatureDiamond the event is keyed by.
     */
    private static List<MaterialDto> toMaterials(List<SAASignalsFoundEvent.Signal> signals) {
        List<MaterialDto> materials = new ArrayList<>();
        for (SAASignalsFoundEvent.Signal signal : signals) {
            String name = SignalName.display(signal.getTypeLocalised(), signal.getType());
            if (name != null) materials.add(new MaterialDto(name, 100, true));
        }
        return materials;
    }

    /**
     * WHY the first-discovery bonus is stored unconditionally rather than only when the body is ours:
     * it is the table figure for the organism, not a claim that we will be paid it. Baking the
     * body's discovery state into the number froze whatever that state happened to be at DSS time,
     * so a body wrongly flagged as ours kept an unearned bonus in every later projection even after
     * a real scan corrected the flag. Callers gate the figure on the body's discovery state as they
     * read it; see {@link elite.intel.ai.brain.actions.handlers.queries.AnalyzeExplorationProfitsQuery}.
     */
    private static List<GenusDto> toGenusDto(List<SAASignalsFoundEvent.Genus> organics, String planetName) {
        List<GenusDto> result = new ArrayList<>();
        for (SAASignalsFoundEvent.Genus genus : organics) {
            GenusDto dto = new GenusDto();
            dto.setGenusLocalised(genus.getGenusLocalised());   // localised - for speech/display
            dto.setGenusSymbol(BioForms.normalizeGenus(genus.getGenus())); // language-independent - for joins
            dto.setPlanetName(planetName);
            BioForms.ProjectedPayment projectedPayment = BioForms.getAverageProjectedPayment(genus.getGenus());
            if (projectedPayment != null && projectedPayment.payment() != null) {
                dto.setRewardInCredits(projectedPayment.payment());
                dto.setBonusCreditsForFirstDiscovery(projectedPayment.firstDiscoveryBonus());
            }
            result.add(dto);
        }
        return result;
    }
}
