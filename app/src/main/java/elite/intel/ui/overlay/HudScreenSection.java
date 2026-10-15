package elite.intel.ui.overlay;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * A cell of the 3x3 grid the desktop overlay cuts its monitor into, named for the compass because that is
 * how commanders ask for it: "move the overlay to the north east".
 * <p>
 * The geometry is the overlay binary's: it knows the monitor, the card's size and the native pixels, and
 * pins the card to the edge of its section so it grows away from the screen edge (see
 * {@code overlay/PROTOCOL.md}). This side only remembers one anchor point per section, so a section the
 * commander adjusted is where the card goes the next time they ask for it.
 * <p>
 * The compass names are safe here, unlike in {@link HudVrPosition}, because they are spoken about a monitor
 * the commander is looking at, never shown as a setting beside the game's own planetary compass.
 */
public enum HudScreenSection {

    NORTH_WEST("nw"),
    NORTH("n"),
    NORTH_EAST("ne"),
    WEST("w"),
    /**
     * Where a drag can leave the card, never a place to send it: nobody parks a card over the reticle, so
     * the voice command does not offer it and its spot is not remembered.
     */
    CENTRE("c"),
    EAST("e"),
    SOUTH_WEST("sw"),
    SOUTH("s"),
    SOUTH_EAST("se");

    private static final Logger log = LogManager.getLogger(HudScreenSection.class);
    private static final String ENTRY_SEPARATOR = ";";
    private static final String VALUE_SEPARATOR = "=";
    private static final String COORDINATE_SEPARATOR = ",";

    private final String wireName;

    HudScreenSection(String wireName) {
        this.wireName = wireName;
    }

    /**
     * The name the overlay protocol carries, e.g. {@code ne}.
     */
    public String wireName() {
        return wireName;
    }

    /**
     * The value the voice command's {@code direction} parameter carries, e.g. {@code north_east}.
     */
    public String parameterName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the commander can ask for this section by voice, and whether its spot is remembered.
     */
    public boolean isCommandable() {
        return this != CENTRE;
    }

    /**
     * Every {@link #parameterName()} the voice command accepts, in compass order.
     */
    public static List<String> commandableParameterNames() {
        return Stream.of(values()).filter(HudScreenSection::isCommandable).map(HudScreenSection::parameterName).toList();
    }

    public static Optional<HudScreenSection> fromWireName(String name) {
        return Stream.of(values()).filter(section -> section.wireName.equals(name)).findFirst();
    }

    /**
     * Reads a {@link #parameterName()} as the LLM or an alias supplied it. Lenient about case and about
     * spaces or hyphens in place of the underscore, which is how a model writes "north east" when it does not
     * copy the enum exactly.
     */
    public static Optional<HudScreenSection> fromParameter(String value) {
        if (value == null) return Optional.empty();
        String normalised = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return Stream.of(values())
                .filter(HudScreenSection::isCommandable)
                .filter(section -> section.name().equals(normalised))
                .findFirst();
    }

    /**
     * Reads the stored section name. Null or unrecognised is empty: a row written before sections existed.
     */
    public static Optional<HudScreenSection> parseStored(String stored) {
        if (stored == null || stored.isBlank()) return Optional.empty();
        try {
            return Optional.of(valueOf(stored.trim()));
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring unknown stored HUD overlay section: {}", stored);
            return Optional.empty();
        }
    }

    /**
     * Reads the remembered anchor points, stored as {@code ne=1720,40;sw=40,1040}. Lenient, like the colour
     * overrides: a malformed entry costs that section its spot, never the rest.
     */
    public static Map<HudScreenSection, Point> parsePoints(String stored) {
        Map<HudScreenSection, Point> points = new EnumMap<>(HudScreenSection.class);
        if (stored == null || stored.isBlank()) return points;

        for (String entry : stored.split(ENTRY_SEPARATOR)) {
            if (entry.isBlank()) continue;
            Optional<Map.Entry<HudScreenSection, Point>> parsed = parseEntry(entry);
            if (parsed.isEmpty()) {
                log.warn("Ignoring malformed HUD overlay section entry: {}", entry);
                continue;
            }
            points.put(parsed.get().getKey(), parsed.get().getValue());
        }
        return points;
    }

    private static Optional<Map.Entry<HudScreenSection, Point>> parseEntry(String entry) {
        String[] parts = entry.split(VALUE_SEPARATOR, 2);
        if (parts.length != 2) return Optional.empty();
        Optional<HudScreenSection> section = fromWireName(parts[0].trim()).filter(HudScreenSection::isCommandable);
        String[] coordinates = parts[1].split(COORDINATE_SEPARATOR, -1);
        if (section.isEmpty() || coordinates.length != 2) return Optional.empty();
        try {
            Point point = new Point(Integer.parseInt(coordinates[0].trim()), Integer.parseInt(coordinates[1].trim()));
            return Optional.of(Map.entry(section.get(), point));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * The inverse of {@link #parsePoints}, in compass order so the stored text is stable.
     */
    public static String formatPoints(Map<HudScreenSection, Point> points) {
        StringBuilder out = new StringBuilder();
        for (HudScreenSection section : values()) {
            Point point = points.get(section);
            if (point == null || !section.isCommandable()) continue;
            if (!out.isEmpty()) out.append(ENTRY_SEPARATOR);
            out.append(section.wireName).append(VALUE_SEPARATOR)
                    .append(point.x).append(COORDINATE_SEPARATOR).append(point.y);
        }
        return out.toString();
    }
}
