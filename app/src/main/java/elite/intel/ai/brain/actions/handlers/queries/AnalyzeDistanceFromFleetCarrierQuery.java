package elite.intel.ai.brain.actions.handlers.queries;

import com.google.gson.JsonObject;
import elite.intel.db.dao.LocationDao;
import elite.intel.db.managers.LocationManager;
import elite.intel.gameapi.journal.events.dto.CarrierDataDto;
import elite.intel.gameapi.journal.events.dto.shiploadout.ShipLoadOutDto;
import elite.intel.session.PlayerSession;
import elite.intel.util.NavigationUtils;
import elite.intel.util.StringUtls;

@RegisterQuery
public class AnalyzeDistanceFromFleetCarrierQuery extends BaseQueryAnalyzer implements IntelQuery {
    public static final String ID = "query_distance_to_carrier";

    @Override
    public String llmDescription() {
        return "Report the distance from the commander's current location to their personal fleet carrier.";
    }


    @Override public String id() { return ID; }


    private final PlayerSession playerSession = PlayerSession.getInstance();
    private final LocationManager locationManager = LocationManager.getInstance();

    @Override public JsonObject handle(String action, JsonObject params, String originalUserInput) throws Exception {
        //GameEventBus.publish(new AiVoxResponseEvent("Analyzing travel telemetry. Stand by."));

        CarrierDataDto carrierData = playerSession.getFleetCarrierData();
        if (carrierData == null) return process(StringUtls.localizedResponse("query.noData"));
        double carrierLocationX = carrierData.getX();
        double carrierLocationY = carrierData.getY();
        double carrierDataZ = carrierData.getZ();

        if (carrierLocationX == 0 && carrierLocationY == 0 && carrierDataZ == 0) {
            return process(StringUtls.localizedResponse("query.carrier.noCoords"));
        }

        // WHY the system's coordinates rather than a primary star's: a system entered by carrier has no primary star
        // on record until it is scanned, and zeroes are Sol's, not "unknown".
        LocationDao.Coordinates here = locationManager.getGalacticCoordinates();
        if (here == null) {
            return process(StringUtls.localizedResponse("query.noPlayerCoords"));
        }
        double x = here.x();
        double y = here.y();
        double z = here.z();

        ShipLoadOutDto shipLoadout = playerSession.getShipLoadout();
        double jumpRange = shipLoadout == null ? -1 : shipLoadout.getMaxJumpRange();
        double distance = NavigationUtils.calculateGalacticDistance(x, y, z, carrierLocationX, carrierLocationY, carrierDataZ);

        int numberOfJumps = (int) (distance / jumpRange) + 1;
        return process(StringUtls.localizedResponse("query.carrier.distance", (int) distance, numberOfJumps));
    }
}
