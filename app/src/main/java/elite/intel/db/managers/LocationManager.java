package elite.intel.db.managers;

import elite.intel.db.dao.LocationDao;
import elite.intel.db.util.Database;
import elite.intel.eventbus.GameEventBus;
import elite.intel.gameapi.gamestate.status_events.BioSurveyCompletedEvent;
import elite.intel.gameapi.journal.ScanBodyClassifier;
import elite.intel.gameapi.journal.events.dto.LocationDto;
import elite.intel.session.DockedMarket;
import elite.intel.session.PlayerSession;
import elite.intel.session.Position;
import elite.intel.util.NavigationUtils;
import elite.intel.util.json.GsonFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Singleton class for managing locations.
 * Use getInstance() to access the single instance of this class.
 */
public class LocationManager {
    private static final Logger log = LogManager.getLogger(LocationManager.class);
    private static LocationManager instance;

    /**
     * Striped locks that serialize read-modify-write of one record across subscribers, keyed by the record's name.
     * Journal events for the same body (e.g. SAASignalsFound + Scan) fire on separate virtual threads within the
     * same instant; without this they race and the later writer overwrites the earlier writer's fields (blind
     * whole-JSON upsert). See {@link #updateNamedBody}.
     * <p>
     * WHY ReentrantLock and not {@code synchronized}: those writers are virtual threads, and on Java 21 one blocked
     * on a monitor pins its carrier. Enough writers queued on one stripe pin every carrier, and the writer holding
     * the database connection then has nowhere to resume - a deadlock. A ReentrantLock waiter parks instead.
     */
    private static final int WRITE_LOCK_STRIPES = 64;
    private final ReentrantLock[] writeLocks = new ReentrantLock[WRITE_LOCK_STRIPES];

    private LocationManager() {
        for (int i = 0; i < writeLocks.length; i++) {
            writeLocks[i] = new ReentrantLock();
        }
    }

    public static synchronized LocationManager getInstance() {
        if (instance == null) {
            instance = new LocationManager();
        }
        return instance;
    }

    /**
     * WHY the name: it is the record's identity - the table's unique key, and the one a record is saved under - so
     * two writers of the same record always take the same lock, whichever BodyID each of their events reported.
     */
    private ReentrantLock lockFor(String locationName) {
        return writeLocks[Math.floorMod(locationName.hashCode(), WRITE_LOCK_STRIPES)];
    }

    /**
     * The one way a location record is written: read the record stored under {@code locationName}, apply
     * {@code mutator}, and save it, all under that record's lock, so a concurrent writer of the same record cannot
     * interleave and clobber fields.
     * <p>
     * WHY by name rather than by BodyID: a body is identified by the system it is in and the name the journal gives
     * it, a station by its MarketID (see {@code DockedStationRecord}). A BodyID is unique only within a system, and
     * stations used to be filed under the BodyID of the body we dropped at, so an ID lookup can hand back the wrong
     * record.
     * <p>
     * A record found under that name but belonging to another system is not adopted: station names are unique
     * per system, not across the galaxy, and merging a namesake's gravity and materials into this one would be
     * worse than starting a fresh record.
     * <p>
     * The record is saved under {@code locationName} whatever the mutator does to its name fields, so a write can
     * never move a record onto another one's key.
     *
     * @return the record as saved, whose body id may differ from {@code bodyId} (see {@link LocationDto#setBodyId})
     */
    public LocationDto updateNamedBody(long systemAddress, long bodyId, String locationName, Consumer<LocationDto> mutator) {
        if (locationName == null || locationName.isBlank()) {
            throw new IllegalArgumentException("A location record needs a name (body " + bodyId + ", system " + systemAddress + ")");
        }
        LocationDto location;
        boolean surveyWasComplete;
        ReentrantLock lock = lockFor(locationName);
        lock.lock();
        try {
            LocationDto stored = findByLocationName(locationName);
            boolean sameSystem = stored != null && (stored.getSystemAddress() == 0 || stored.getSystemAddress() == systemAddress);
            location = sameSystem ? stored : new LocationDto(bodyId, systemAddress);
            surveyWasComplete = location.isBioScansCompleted();
            mutator.accept(location);
            save(location, locationName);
        } finally {
            lock.unlock();
        }
        // Every writer of the survey-complete latch comes through here, so this is where a flip is
        // announced - after the lock is released, since a listener may well read this body back.
        if (surveyWasComplete != location.isBioScansCompleted()) {
            GameEventBus.publish(new BioSurveyCompletedEvent(systemAddress, location.getBodyId(), location.isBioScansCompleted()));
        }
        return location;
    }

    /**
     * {@link #updateNamedBody} for the events that name a body only by BodyID (a codex entry, an organic scan, the
     * body the commander is at). The record is found by that id - a body's before a station's - and written under
     * its name. With no record known by that id there is nothing to write to: a record needs a name to be stored.
     */
    public void updateBody(long systemAddress, long bodyId, Consumer<LocationDto> mutator) {
        String locationName = Database.withDao(LocationDao.class, dao -> dao.findBodyName(systemAddress, bodyId));
        if (locationName == null) {
            log.debug("No record of body {} in system {} to write to; left unwritten", bodyId, systemAddress);
            return;
        }
        updateNamedBody(systemAddress, bodyId, locationName, mutator);
    }

    private LocationDto findByLocationName(String locationName) {
        return Database.withDao(LocationDao.class, dao -> {
            LocationDao.Location location = dao.findByLocationName(locationName);
            return location == null ? null : GsonFactory.getGson().fromJson(location.getJson(), LocationDto.class);
        });
    }

    /**
     * WHY a record with no system name is not stored: every reader finds records through their system, and one
     * without it would sit in the table unreachable.
     */
    private void save(LocationDto location, String locationName) {
        if (location.getStarName() == null) return;
        Database.withDao(LocationDao.class, dao -> {
            dao.save(location.getBodyId(), locationName, location.getStarName(), location.getSystemAddress(), location.toJson());
            return null;
        });
    }

    /**
     * Where the ship is, for a distance search, or null when the app cannot fix a position at all.
     * <p>
     * WHY from the system's record: every arrival event carries the system's StarPos and files it there, so the
     * coordinates are exact - zeroes included, which is Sol. The query this replaced looked the system up by name
     * and skipped zero coordinates, and needed a fallback for Sol.
     */
    public LocationDao.Coordinates getGalacticCoordinates() {
        Optional<Position> position = PlayerSession.getInstance().position();
        if (position.isEmpty()) return null;
        LocationDto system = findSystemRecord(position.get().systemAddress());
        if (system.getBodyId() == -1) return null;
        return new LocationDao.Coordinates(position.get().starSystem(), system.getX(), system.getY(), system.getZ());
    }

    public LocationDto findBySystemAddress(long systemAddress, String planetName) {
        return Database.withDao(LocationDao.class, dao -> {
            LocationDao.Location location = dao.findPrimaryBySystemAddress(systemAddress, planetName);
            return location == null ? new LocationDto(-1L, systemAddress) : GsonFactory.getGson().fromJson(location.getJson(), LocationDto.class);
        });
    }

    /**
     * Where the commander is, the record of the body they are at, and the record holding their system's facts.
     * Empty before any journal event has placed them.
     */
    public Optional<Whereabouts> here() {
        return PlayerSession.getInstance().position().map(position ->
                new Whereabouts(position, bodyAt(position), findSystemRecord(position.systemAddress())));
    }

    /**
     * The record of the body the commander is at; the not-found marker (body id -1) when no journal event has placed
     * them, or placed them at no body.
     */
    public LocationDto currentBody() {
        return PlayerSession.getInstance().position().map(this::bodyAt).orElseGet(() -> new LocationDto(-1L));
    }

    /**
     * The record of the commander's system's facts - security, allegiance, factions, coordinates (see
     * {@link #findSystemRecord}); the not-found marker (body id -1) when no journal event has placed them.
     */
    public LocationDto currentSystem() {
        return PlayerSession.getInstance().position().map(position -> findSystemRecord(position.systemAddress()))
                .orElseGet(() -> new LocationDto(-1L));
    }

    private LocationDto bodyAt(Position position) {
        return position.bodyId() == null ? new LocationDto(-1L, position.systemAddress())
                : findBySystemAddress(position.systemAddress(), position.bodyId());
    }

    /**
     * The record of a system's own facts (see {@link LocationDao#findSystemRecord}), or the not-found marker -
     * body id -1 - when no arrival in that system is on record.
     */
    public LocationDto findSystemRecord(long systemAddress) {
        return Database.withDao(LocationDao.class, dao -> {
            LocationDao.Location location = dao.findSystemRecord(systemAddress);
            return location == null ? new LocationDto(-1L, systemAddress) : GsonFactory.getGson().fromJson(location.getJson(), LocationDto.class);
        });
    }

    /**
     * The record of a system's primary star, or the not-found marker (body id -1). Only an FSDJump arrival or a Scan
     * at 0 Ls marks one, so a system entered by carrier has none until its star is scanned; for the system's facts
     * ask {@link #findSystemRecord}.
     */
    public LocationDto findPrimaryStar(long systemAddress) {
        return Database.withDao(LocationDao.class, dao -> {
            LocationDao.Location location = dao.findPrimaryBySystemAddress(systemAddress);
            return location == null ? new LocationDto(-1L, systemAddress) : GsonFactory.getGson().fromJson(location.getJson(), LocationDto.class);
        });
    }


    /**
     * The name of the system at {@code systemAddress}, or null when nothing on record names it.
     * <p>
     * WHY not the primary star's record: a system entered by carrier, or one the session started docked in,
     * never gets a PRIMARY_STAR record - only a jump writes that - yet every record filed there carries the
     * system's name in its own column. A writer that took the name from the primary star alone came away
     * with null in exactly those systems, and a record with no system name is not stored: this is how six
     * moons' FSS bio signals went unrecorded without a word. The commander's current system is the last
     * resort, for the first body filed in a system nothing else has written to yet.
     */
    public String findStarName(long systemAddress) {
        String filed = Database.withDao(LocationDao.class, dao -> dao.findStarName(systemAddress));
        if (filed != null && !filed.isBlank()) return filed;
        return PlayerSession.getInstance().position()
                .filter(here -> here.isIn(systemAddress))
                .map(Position::starSystem)
                .orElse(null);
    }

    public LocationDto findBySystemAddress(Long systemAddress, Long bodyId) {
        return Database.withDao(LocationDao.class, dao -> {
            LocationDao.Location location = dao.findPrimaryBySystemAddress(systemAddress, bodyId);
            return location == null ? new LocationDto(bodyId, systemAddress) : GsonFactory.getGson().fromJson(location.getJson(), LocationDto.class);
        });
    }

    /**
     * The record of the port the ship is standing on, or the current location when it is not on one.
     * <p>
     * Not the same question as "where am I": a fleet carrier, an orbital construction depot and a planetary
     * port are not the body the drop left us at, and the current location is that body. {@link DockedMarket}
     * holds the MarketID the journal gave on arrival - the one thing that names the pad unambiguously - and
     * the station is filed under it. The fallback is the answer callers used to get, for when we are in open
     * space or the station has no record yet.
     */
    public LocationDto findCurrentStation() {
        long marketId = DockedMarket.getInstance().marketId();
        if (marketId != 0) {
            LocationDto station = findByMarketId(marketId);
            if (station.getSystemAddress() > 0) return station;
        }
        return currentBody();
    }

    /**
     * Whether anything is on record for the system of this name. By name, because it answers for systems a search
     * names - those come with no address; for the commander's own system, ask by address.
     */
    public boolean isSystemOnRecord(String starSystem) {
        return Database.withDao(LocationDao.class, dao -> dao.isSystemOnRecord(starSystem));
    }


    public LocationDto findByMarketId(long marketID) {
        return Database.withDao(LocationDao.class, dao -> {
            LocationDao.Location location = dao.findByMarketId(marketID);
            return location == null ? new LocationDto(-1L, -1L) : GsonFactory.getGson().fromJson(location.getJson(), LocationDto.class);
        });
    }

    public Collection<LocationDto> findAllBySystemAddress(long systemAddress) {
        return Database.withDao(LocationDao.class, dao -> {
            List<LocationDao.Location> bySystemAddress = dao.findAllBySystemAddress(systemAddress);
            Map<Long, LocationDto> result = new HashMap<>();
            for (LocationDao.Location entity : bySystemAddress) {
                result.put(entity.getInGameId(), GsonFactory.getGson().fromJson(entity.getJson(), LocationDto.class));
            }
            return result.values();
        });
    }

    /**
     * The stars and planets on record in one system, one per BodyID, in body order - what the FSS counts.
     * <p>
     * WHY not {@link #findAllBySystemAddress}: that keys rows by BodyID alone, and a station is filed under
     * the BodyID of the body it orbits, so it can stand in for that body. Stations, carriers, rings and belt
     * clusters are not bodies to the discovery scanner and are left out before the rows are keyed.
     */
    public List<LocationDto> findBodies(long systemAddress) {
        return Database.withDao(LocationDao.class, dao -> {
            Map<Long, LocationDto> byBodyId = new TreeMap<>();
            for (LocationDao.Location entity : dao.findAllBySystemAddress(systemAddress)) {
                LocationDto location = GsonFactory.getGson().fromJson(entity.getJson(), LocationDto.class);
                if (CELESTIAL_BODIES.contains(ScanBodyClassifier.resolve(location))) {
                    byBodyId.putIfAbsent(location.getBodyId(), location);
                }
            }
            return new ArrayList<>(byBodyId.values());
        });
    }

    private static final Set<LocationDto.LocationType> CELESTIAL_BODIES = EnumSet.of(
            LocationDto.LocationType.PRIMARY_STAR, LocationDto.LocationType.STAR, LocationDto.LocationType.BLACK_HOLE,
            LocationDto.LocationType.PLANET, LocationDto.LocationType.MOON);

    /**
     * Bodies in one system that a surface scan found biological genuses on, in body order.
     * <p>
     * Filtered in SQL rather than by loading the system and testing each body, because a
     * well-explored system holds scores of rows and the caller wants the handful with biology.
     */
    public List<LocationDto> findBioSignalBodies(long systemAddress) {
        return Database.withDao(LocationDao.class, dao -> dao.findBioSignalBodies(systemAddress).stream()
                .map(entity -> GsonFactory.getGson().fromJson(entity.getJson(), LocationDto.class))
                .sorted(Comparator.comparingLong(LocationDto::getBodyId))
                .collect(Collectors.toList()));
    }

    public List<LocationDto> findStationsInCurrentStarSystem(long systemAddress) {
        return Database.withDao(LocationDao.class, dao -> {
            List<LocationDao.Location> stations = dao.findStationsInCurrentStarSystem(systemAddress);
            return stations.stream().map(entity -> GsonFactory.getGson().fromJson(entity.getJson(), LocationDto.class)).collect(Collectors.toList());
        });
    }

    /**
     * Stations we have already visited within {@code maxLightYears} of the given point, nearest first.
     * <p>
     * The database narrows the search to a cube around the point; the corners of that cube - which are
     * further away than the radius - are trimmed here with the real galactic distance.
     */
    public List<LocationDto> findKnownStationsWithin(LocationDao.Coordinates origin, double maxLightYears) {
        if (origin == null) return List.of();
        return Database.withDao(LocationDao.class, dao -> dao.findStationsInCoordinateBox(
                        origin.x() - maxLightYears, origin.x() + maxLightYears,
                        origin.y() - maxLightYears, origin.y() + maxLightYears,
                        origin.z() - maxLightYears, origin.z() + maxLightYears
                ).stream()
                .map(row -> Map.entry(
                        row,
                        NavigationUtils.calculateGalacticDistance(row.x(), row.y(), row.z(), origin.x(), origin.y(), origin.z())
                ))
                .filter(entry -> entry.getValue() <= maxLightYears)
                .sorted(Map.Entry.comparingByValue())
                .map(entry -> GsonFactory.getGson().fromJson(entry.getKey().json(), LocationDto.class))
                .collect(Collectors.toList()));
    }
}
