package elite.intel.gameapi.journal.subscribers;

import com.google.common.eventbus.Subscribe;
import elite.intel.ai.brain.vega.VegaRuntime;
import elite.intel.db.managers.LocationManager;
import elite.intel.gameapi.journal.events.ApproachBodyEvent;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.gameapi.journal.events.dto.MaterialDto;
import elite.intel.gameapi.search.edsm.EdsmApiClient;
import elite.intel.gameapi.search.edsm.dto.SystemBodiesDto;
import elite.intel.gameapi.search.edsm.dto.data.BodyData;
import elite.intel.session.PlayerSession;
import elite.intel.session.Status;
import elite.intel.util.GravityCalculator;

import java.util.List;
import java.util.Map;

import static elite.intel.util.StringUtls.localizedEvent;

public class ApproachBodySubscriber {

    private final PlayerSession playerSession = PlayerSession.getInstance();
    private final LocationManager locationManager = LocationManager.getInstance();

    private static double formatDouble(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @Subscribe
    public void onApproachBodyEvent(ApproachBodyEvent event) {
        Thread.ofVirtual().start(() -> {
            if (event.getBody() == null || event.getBodyID() == null) return;
            double orbitalCruiseEntryAltitude = Status.getInstance().getStatus().getAltitude();

            // Recorded before the tracking check, so the body is on record even when tracking is on.
            LocationDto location = locationManager.updateNamedBody(event.getSystemAddress(), event.getBodyID(), event.getBody(),
                    body -> recordApproach(body, event, orbitalCruiseEntryAltitude));
            playerSession.moveWithinSystem(event.getSystemAddress(), event.getBodyID());

            if (playerSession.getTracking().isEnabled()) return;

            StringBuilder sb = new StringBuilder();
            sb.append(localizedEvent("event.approach.body.entering", bodyNoun(location.getLocationType()), location.getPlanetName()));
            if (location.getGravity() > 0) {
                useOurData(location, sb);
            } else {
                describeFromEdsm(event, sb);
            }

            if (!event.isReplay() && playerSession.isPlanetaryApproachAnnouncementOn()) {
                String instructions = """
                            We are approaching planet/moon. Warn/Notify user with this data.
                            Temperature data is provided in C (Celsius)
                            Gravity around equal to or less than 1G is safe. Issue a gravity warning if gravity is higher than 1G.
                            If there is no atmosphere report as "Atmosphere: none".
                        """;
                VegaRuntime.narrator().narrate(sb.toString(), instructions);
            }
        }); // end virtual thread
    }

    private static void recordApproach(LocationDto body, ApproachBodyEvent event, double orbitalCruiseEntryAltitude) {
        if (body.getPlanetName() == null || body.getPlanetName().isEmpty()) {
            body.setPlanetName(event.getBody());
            body.setPlanetShortName(event.getBody());
        }
        // A record written before the body was scanned can carry no BodyID; the approach supplies it.
        if (body.getBodyId() <= 0) body.setBodyId(event.getBodyID());
        restoreBodyType(body);
        body.setStarName(event.getStarSystem());
        body.setOrbitalCruiseEntryAltitude(orbitalCruiseEntryAltitude);
    }

    /**
     * For a body we hold no data on: what EDSM knows, filed onto the record and added to the report.
     * <p>
     * WHY fetched outside the write and re-checked inside it: the lock is never held across a network call, and a
     * Scan of this body landing meanwhile is the journal's word, which EDSM must not overwrite.
     */
    private void describeFromEdsm(ApproachBodyEvent event, StringBuilder sb) {
        SystemBodiesDto systemBodiesDto = EdsmApiClient.searchSystemBodies(event.getStarSystem());
        List<BodyData> bodies = systemBodiesDto.getData() == null ? null : systemBodiesDto.getData().getBodies();
        if (bodies == null || bodies.isEmpty()) {
            sb.append(" ").append(localizedEvent("event.approach.body.noData", event.getBody()));
            sb.append(" ").append(localizedEvent("event.approach.body.checkData"));
            return;
        }
        bodies.stream().filter(bodyData -> event.getBody().equalsIgnoreCase(bodyData.getName())).findFirst()
                .ifPresent(bodyData -> locationManager.updateNamedBody(event.getSystemAddress(), event.getBodyID(), event.getBody(),
                        body -> {
                            if (body.getGravity() > 0) {
                                useOurData(body, sb);
                            } else {
                                extractDataFromEdsm(bodyData, body, sb);
                            }
                        }));
    }

    /**
     * We are in orbital cruise around this body, so its record is a body's record whatever a docking has written
     * onto it. Docking stores the station under the body we dropped at, which can leave the body itself typed
     * STATION or FLEET_CARRIER; correct it here so later readers - and the narrator - are not told a planet is a
     * carrier. A moon is the one we can still tell apart: only a moon's scan records a parent body.
     */
    static void restoreBodyType(LocationDto location) {
        LocationDto.LocationType type = location.getLocationType();
        boolean typedAsAPlace = LocationDto.LocationType.STATION == type
                || LocationDto.LocationType.FLEET_CARRIER == type
                || LocationDto.LocationType.MARKET == type
                || LocationDto.LocationType.FACILITY == type;
        if (!typedAsAPlace) return;
        Long parentBodyId = location.getParentBodyId();
        boolean isMoon = parentBodyId != null && parentBodyId > 0;
        location.setLocationType(isMoon ? LocationDto.LocationType.MOON : LocationDto.LocationType.PLANET);
    }

    /**
     * The body's kind as a word the commander would use, never the enum constant: the narrator reads this payload
     * out loud, so handing it LocationType.name() is what put "FLEET_CARRIER" into the report. A type that is not a
     * body kind contributes no word at all.
     */
    static String bodyNoun(LocationDto.LocationType type) {
        if (type == null) return "";
        return switch (type) {
            case PLANET -> localizedEvent("event.approach.body.type.planet");
            case MOON -> localizedEvent("event.approach.body.type.moon");
            default -> "";
        };
    }

    private void useOurData(LocationDto location, StringBuilder sb) {
        double surfaceGravity = formatDouble(location.getGravity());

        if (surfaceGravity > GravityCalculator.MAX_PLAUSIBLE_GRAVITY_G) {
            // Nothing in the game comes close, so the stored figure is not a gravity. Say so rather than narrate it:
            // recomputing here used to paper over EDSM radii being stored in km (see migration 01041).
            sb.append(" ").append(localizedEvent("event.approach.body.gravityAnomaly"));
        } else {
            sb.append(" ").append(localizedEvent("event.approach.body.surfaceGravity", surfaceGravity));
            if (surfaceGravity > 1) {
                sb.append(" ").append(localizedEvent("event.approach.body.gravityWarning"));
            }
        }


        if (!"None".equalsIgnoreCase(location.getAtmosphere())) {
            sb.append(" ").append(localizedEvent("event.approach.body.atmosphere", location.getAtmosphere()));
        }
        List<MaterialDto> materials = location.getMaterials();
        if (materials != null && !materials.isEmpty()) {
            sb.append(" ").append(localizedEvent("event.approach.body.materials", materials.size()));
        }
        appendTemperature(sb, location.getSurfaceTemperature());
        if (location.isTidalLocked()) sb.append(" ").append(localizedEvent("event.approach.body.tidalLocked"));
    }


    /**
     * Appends the surface temperature converted to Celsius for the narration. The location keeps the journal's Kelvin
     * value: converting in place and saving it back subtracted another 273 on every approach of the same body.
     */
    private void appendTemperature(StringBuilder sb, double surfaceTemperatureKelvin) {
        if (surfaceTemperatureKelvin > 0) {
            sb.append(" ").append(localizedEvent("event.approach.body.temperature", (int) (surfaceTemperatureKelvin - 273)));
        } else {
            sb.append(" ").append(localizedEvent("event.approach.body.temperatureUnknown"));
        }
    }

    private void extractDataFromEdsm(BodyData bodyData, LocationDto location, StringBuilder sb) {
        double gravity = formatDouble(bodyData.getGravity());
        location.setGravity(gravity);
        sb.append(" ").append(localizedEvent("event.approach.body.surfaceGravity", gravity));
        if (gravity > 1) {
            sb.append(" ").append(localizedEvent("event.approach.body.gravityWarning"));
        }
        double surfaceTemperatureKelvin = bodyData.getSurfaceTemperature();
        location.setSurfaceTemperature(surfaceTemperatureKelvin);
        appendTemperature(sb, surfaceTemperatureKelvin);
        if (bodyData.getAtmosphereType() != null && !bodyData.getAtmosphereType().isEmpty()) {
            sb.append(" ").append(localizedEvent("event.approach.body.atmosphere", bodyData.getAtmosphereType()));
        }
        Map<String, Double> materials = bodyData.getMaterials();
        if (materials != null && !materials.isEmpty()) {
            sb.append(" ").append(localizedEvent("event.approach.body.materials", materials.size()));
            for (Map.Entry<String, Double> material : materials.entrySet()) {
                location.addMaterial(new MaterialDto(material.getKey(), material.getValue()));
            }
        }
        location.setPlanetData(bodyData);
    }
}
