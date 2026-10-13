package elite.intel.bindforge.io;

import elite.intel.bindforge.model.BindingSection;

import java.util.ArrayList;
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
 *                   sections; writing it back produces four lines
 */
public record StartPresetSections(String general, String ship, String srv, String onFoot, boolean singleLine) {

    private static final char BOM = '﻿';

    public StartPresetSections {
        Objects.requireNonNull(general, "general");
        Objects.requireNonNull(ship, "ship");
        Objects.requireNonNull(srv, "srv");
        Objects.requireNonNull(onFoot, "onFoot");
    }

    /**
     * Parses the text of a {@code StartPreset} file.
     * <ul>
     *   <li>Lines are read by position, each trimmed. Trailing blank lines (a final newline) are dropped, but a
     *       blank line between two names is an empty value for its section, so nothing shifts up a section.</li>
     *   <li>One line: the pre-Odyssey form, the same name for all four, {@link #singleLine()} true.</li>
     *   <li>Fewer than four lines: the missing sections are empty. More than four: the extra lines are ignored.</li>
     *   <li>A leading byte-order mark is stripped; CRLF and LF both end a line.</li>
     * </ul>
     */
    public static StartPresetSections parse(String text) {
        Objects.requireNonNull(text, "text");
        String body = !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;

        List<String> lines = new ArrayList<>(body.lines().map(String::trim).toList());
        while (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }

        if (lines.size() == 1) {
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
