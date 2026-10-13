package elite.intel.bindforge.io;

import elite.intel.bindforge.model.BindingSection;

import java.util.List;
import java.util.Objects;

/**
 * The four preset names {@code StartPreset.*.start} holds, one per section of the game's controls screen:
 * General, Ship, SRV and On Foot, in the file's own line order.
 * <p>
 * Each value is a preset name as the file stores it - the {@code .binds} filename without its version suffix
 * and extension - and is empty when the file has no line for that section. Unlike
 * {@link BindingsLoader#getActivePresetName()}, which answers "which preset is the user on?" with the first
 * non-empty line, this keeps the four apart, so a split preset (General on one file, the rest on another) is
 * reported as it is.
 *
 * @param singleLine true when the file is the pre-Odyssey single-line form, whose one name applies to all four
 *                   sections; only then are the four names necessarily the same
 */
public record StartPresetSections(String general, String ship, String srv, String onFoot, boolean singleLine) {

    private static final char BOM = '\uFEFF';

    public StartPresetSections {
        Objects.requireNonNull(general, "general");
        Objects.requireNonNull(ship, "ship");
        Objects.requireNonNull(srv, "srv");
        Objects.requireNonNull(onFoot, "onFoot");
        if (singleLine && !(general.equals(ship) && ship.equals(srv) && srv.equals(onFoot))) {
            throw new IllegalArgumentException("A single-line StartPreset names one preset for all four sections, not "
                    + general + " / " + ship + " / " + srv + " / " + onFoot);
        }
    }

    /**
     * Parses the text of a {@code StartPreset} file.
     * <ul>
     *   <li>Lines are read by position, each trimmed. A blank line is an empty value for its section, wherever it
     *       is, so nothing shifts up a section; a final line ending does not add a line.</li>
     *   <li>Exactly one line, not blank: the pre-Odyssey form, the same name for all four, {@link #singleLine()}
     *       true. A name followed by blank lines is the four-line form with sections missing, not this.</li>
     *   <li>Fewer than four lines: the missing sections are empty. More than four: the extra lines are ignored.</li>
     *   <li>A leading byte-order mark is stripped; CRLF and LF both end a line.</li>
     * </ul>
     */
    public static StartPresetSections parse(String text) {
        Objects.requireNonNull(text, "text");
        String body = !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
        List<String> lines = body.lines().map(String::trim).toList();

        if (lines.size() == 1 && !lines.getFirst().isEmpty()) {
            String name = lines.getFirst();
            return new StartPresetSections(name, name, name, name, true);
        }
        return new StartPresetSections(line(lines, 0), line(lines, 1), line(lines, 2), line(lines, 3), false);
    }

    /**
     * The preset name for one section.
     *
     * @throws IllegalArgumentException for {@link BindingSection#OTHER}, which has no line in the file
     */
    public String forSection(BindingSection section) {
        return switch (section) {
            case GENERAL -> general;
            case SHIP -> ship;
            case SRV -> srv;
            case ON_FOOT -> onFoot;
            case OTHER -> throw new IllegalArgumentException("StartPreset has no line for section " + section);
        };
    }

    private static String line(List<String> lines, int index) {
        return index < lines.size() ? lines.get(index) : "";
    }
}
