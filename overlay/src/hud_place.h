// Where the desktop card sits on its monitor, and how it stays there.
//
// The monitor is cut into a 3x3 grid of sections named for the compass, and the
// card is pinned to its section by an ANCHOR: the corner or edge midpoint that
// faces the screen edge. A card in the north-east keeps its top-right corner
// where it was put, one in the south keeps its bottom-centre, one in the west
// its left-middle. When the card grows - a row arrives, the lean makes it taller,
// the commander widens it - it grows away from the screen edge instead of off it.
// Before anchors the window kept its top-left corner, so a card parked low on the
// screen slid below the bottom edge as soon as it gained a row.
//
// The app remembers one anchor point per section; this file owns the geometry,
// because only the overlay knows the monitor, the card's size and the native
// pixel coordinates both of those are in.
//
// Kept free of cairo and of any window system so the arithmetic can be tested on
// its own (test/test_place.c). The shells supply the monitors through HudMonitors.

#ifndef EI_HUD_PLACE_H
#define EI_HUD_PLACE_H

#include <limits.h>

// "No position requested." Deliberately not -1, and not any small negative
// number: a screen coordinate is legitimately negative on both platforms. A
// monitor placed left of or above the primary one starts at a negative offset,
// and a window nudged past the top or left edge sits at a negative coordinate
// on its own. Using -1 as the sentinel made every such position unstorable -
// the card came back centred with only its Y remembered - because the guard
// that applied it could not tell the commander's coordinate from "unset".
// Must match OverlayProtocol.POSITION_UNSET on the Java side.
#define HUD_POS_UNSET INT_MIN

/// A section of the monitor, as row * 3 + column, so the column (0 left, 1 centre,
/// 2 right) and the row (0 top, 1 middle, 2 bottom) fall out by arithmetic.
///
/// HUD_SEC_FREE is the absence of an anchor: the window holds its top-left corner,
/// which is what it does while it is being dragged.
typedef enum {
    HUD_SEC_FREE = -1,
    HUD_SEC_NW, HUD_SEC_N, HUD_SEC_NE,
    HUD_SEC_W,  HUD_SEC_C, HUD_SEC_E,
    HUD_SEC_SW, HUD_SEC_S, HUD_SEC_SE
} HudSection;

typedef struct { int x, y, w, h; } HudRect;

/// The monitors, as the shell's window system reports them.
typedef struct {
    /// The monitor containing (x, y). Returns 1 when the point is on one; 0 when
    /// it is on none (a gap between displays, a display since unplugged), and then
    /// still fills *out with the shell's best fallback so the card has somewhere
    /// to be.
    int (*monitor_at)(void *ctx, int x, int y, HudRect *out);
    /// The primary monitor: where a card with no history opens.
    HudRect (*primary)(void *ctx);
    void *ctx;
} HudMonitors;

/// What the app asked for, held until the shell's next layout pass resolves it.
/// Resolution waits for a pass because the card's size - which decides where an
/// anchor puts the window - is only known once it has been measured.
typedef enum {
    HUD_PLACE_NONE,
    /// `CFG move=<section>`, from the voice command. Uses the supplied point only
    /// when it lies on the monitor the card is on NOW, otherwise the section's
    /// default spot there. Reported back.
    HUD_PLACE_MOVE,
    /// `CFG restore=<section>`, at startup. Trusts the point, falling back to the
    /// section's default on the primary monitor only when the point is on no
    /// monitor at all. Not reported: the app already holds what it sent.
    HUD_PLACE_RESTORE,
    /// Bare `CFG x= y=`: a top-left corner stored before sections existed. Placed
    /// as given, then classified and reported, which converts the stored row.
    HUD_PLACE_SETTLE
} HudPlaceKind;

typedef struct {
    HudPlaceKind kind;
    HudSection section;
    int x, y;                // HUD_POS_UNSET when no point came with the request
} HudPlaceRequest;

/// The window's position and what holds it there. Owned by the shell.
typedef struct {
    int x, y;                // window top-left, screen pixels
    HudSection anchor;
    int ax, ay;              // the anchor point; meaningless while FREE
} HudPlacement;

/// Wire name of a section (`nw` ... `se`), or NULL for FREE.
const char *hud_section_name(HudSection section);

/// Section by wire name, or HUD_SEC_FREE for anything unrecognised.
HudSection hud_section_parse(const char *name);

/// Which cell of `monitor` the centre of `card` falls in.
HudSection hud_section_classify(HudRect card, HudRect monitor);

/// The anchor point of `card` for `section`. Right and bottom edges are the
/// exclusive ones (x + w, y + h), so a card flush with the monitor's right edge
/// has its anchor ON that edge.
void hud_anchor_of(HudSection section, HudRect card, int *ax, int *ay);

/// The window top-left that puts a w x h card's `section` anchor at (ax, ay).
void hud_rect_at_anchor(HudSection section, int ax, int ay, int w, int h, int *x, int *y);

/// Where a section's anchor goes on `monitor` when the commander has never put
/// the card there: its corner or edge midpoint, inset by a margin of 4% of the
/// monitor's height (the gap the card has always opened with below the top edge).
void hud_default_anchor(HudSection section, HudRect monitor, int *ax, int *ay);

/// Starts a w x h card with no history at the top centre of the primary monitor,
/// the spot it has always opened on.
void hud_place_init(HudPlacement *p, int w, int h, const HudMonitors *mons);

/// Applies a pending request to `p` for a w x h card and clears it. Returns 1
/// when the result must be reported to the app (hud_report_placement).
int hud_place_resolve(HudPlacement *p, HudPlaceRequest *req, int w, int h, const HudMonitors *mons);

/// Pins a FREE card to the section its centre is in now - a drag ending, or a
/// legacy position arriving. The caller reports the result.
void hud_place_settle(HudPlacement *p, int w, int h, const HudMonitors *mons);

/// Puts the window where the anchor says for a w x h card, kept wholly on the
/// card's monitor. A FREE card is left where it is. Returns 1 when x or y moved.
int hud_place_fit(HudPlacement *p, int w, int h, const HudMonitors *mons);

#endif
