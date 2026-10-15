package elite.intel.ui.overlay;

import java.awt.*;
import java.util.Objects;

/**
 * Where the desktop card is pinned: a section of its monitor and the anchor point that holds it there, in
 * screen pixels. The anchor is the card's corner or edge midpoint facing the screen edge, so it is not the
 * window's top-left corner - see {@code overlay/PROTOCOL.md}.
 */
public record HudPlacement(HudScreenSection section, Point anchor) {

    public HudPlacement {
        Objects.requireNonNull(section, "section");
        anchor = new Point(Objects.requireNonNull(anchor, "anchor"));
    }

    @Override
    public Point anchor() {
        return new Point(anchor);
    }
}
