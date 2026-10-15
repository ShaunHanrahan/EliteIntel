// Placement of the desktop card. See hud_place.h for the model.

#include "hud_place.h"

#include <string.h>

// In HudSection order. Must match HudScreenSection on the Java side, which the
// Java test suite checks.
static const char *const SECTION_NAMES[] = {"nw", "n", "ne", "w", "c", "e", "sw", "s", "se"};

const char *hud_section_name(HudSection section) {
    if (section < HUD_SEC_NW || section > HUD_SEC_SE) return NULL;
    return SECTION_NAMES[section];
}

HudSection hud_section_parse(const char *name) {
    if (!name) return HUD_SEC_FREE;
    for (int i = 0; i < 9; i++) {
        if (!strcmp(name, SECTION_NAMES[i])) return (HudSection) i;
    }
    return HUD_SEC_FREE;
}

static int column(HudSection section) { return section % 3; }
static int row(HudSection section)    { return section / 3; }

/// Which third of `extent` starting at `origin` the coordinate falls in, held to
/// 0..2 so a centre just past an edge still lands in the nearest cell.
static int third(int coordinate, int origin, int extent) {
    if (extent <= 0) return 1;
    int n = (int) ((long) (coordinate - origin) * 3 / extent);
    return n < 0 ? 0 : n > 2 ? 2 : n;
}

HudSection hud_section_classify(HudRect card, HudRect monitor) {
    int col = third(card.x + card.w / 2, monitor.x, monitor.w);
    int r = third(card.y + card.h / 2, monitor.y, monitor.h);
    return (HudSection) (r * 3 + col);
}

void hud_anchor_of(HudSection section, HudRect card, int *ax, int *ay) {
    *ax = card.x + card.w * column(section) / 2;
    *ay = card.y + card.h * row(section) / 2;
}

void hud_rect_at_anchor(HudSection section, int ax, int ay, int w, int h, int *x, int *y) {
    *x = ax - w * column(section) / 2;
    *y = ay - h * row(section) / 2;
}

void hud_default_anchor(HudSection section, HudRect monitor, int *ax, int *ay) {
    int margin = monitor.h * 4 / 100;
    int xs[] = {monitor.x + margin, monitor.x + monitor.w / 2, monitor.x + monitor.w - margin};
    int ys[] = {monitor.y + margin, monitor.y + monitor.h / 2, monitor.y + monitor.h - margin};
    *ax = xs[column(section)];
    *ay = ys[row(section)];
}

/// Pins the card and moves the window with it. The window moves now rather than
/// at the next fit because a move resolved straight after a restore asks which
/// monitor the card is on, and the answer must be the restored one.
static void pin(HudPlacement *p, HudSection section, int ax, int ay, int w, int h) {
    p->anchor = section;
    p->ax = ax;
    p->ay = ay;
    hud_rect_at_anchor(section, ax, ay, w, h, &p->x, &p->y);
}

static void pin_default(HudPlacement *p, HudSection section, HudRect monitor, int w, int h) {
    int ax, ay;
    hud_default_anchor(section, monitor, &ax, &ay);
    pin(p, section, ax, ay, w, h);
}

/// Whether a w x h card anchored at (ax, ay) would have its centre on `monitor`.
/// The centre rather than the anchor point, because a right or bottom anchor sits
/// on the monitor's exclusive edge and would test as off it.
static int lands_on(HudSection section, int ax, int ay, int w, int h, HudRect monitor) {
    int x, y;
    hud_rect_at_anchor(section, ax, ay, w, h, &x, &y);
    int cx = x + w / 2, cy = y + h / 2;
    return cx >= monitor.x && cx < monitor.x + monitor.w
           && cy >= monitor.y && cy < monitor.y + monitor.h;
}

void hud_place_init(HudPlacement *p, int w, int h, const HudMonitors *mons) {
    pin_default(p, HUD_SEC_N, mons->primary(mons->ctx), w, h);
    hud_place_fit(p, w, h, mons);
}

void hud_place_settle(HudPlacement *p, int w, int h, const HudMonitors *mons) {
    HudRect card = {p->x, p->y, w, h}, monitor;
    mons->monitor_at(mons->ctx, p->x + w / 2, p->y + h / 2, &monitor);
    HudSection section = hud_section_classify(card, monitor);
    int ax, ay;
    hud_anchor_of(section, card, &ax, &ay);
    pin(p, section, ax, ay, w, h);
}

int hud_place_resolve(HudPlacement *p, HudPlaceRequest *req, int w, int h, const HudMonitors *mons) {
    HudPlaceRequest r = *req;
    req->kind = HUD_PLACE_NONE;
    int has_point = r.x != HUD_POS_UNSET && r.y != HUD_POS_UNSET;

    switch (r.kind) {
        case HUD_PLACE_NONE:
            return 0;

        case HUD_PLACE_MOVE: {
            if (r.section == HUD_SEC_FREE) return 0;
            // The monitor the card is on before it moves: a section names a part
            // of THIS screen, whichever screen the commander has put the card on.
            HudRect here;
            mons->monitor_at(mons->ctx, p->x + w / 2, p->y + h / 2, &here);
            if (has_point && lands_on(r.section, r.x, r.y, w, h, here)) pin(p, r.section, r.x, r.y, w, h);
            else pin_default(p, r.section, here, w, h);
            return 1;
        }

        case HUD_PLACE_RESTORE: {
            if (r.section == HUD_SEC_FREE) return 0;
            HudRect probe;
            int on_screen = 0;
            if (has_point) {
                int x, y;
                hud_rect_at_anchor(r.section, r.x, r.y, w, h, &x, &y);
                on_screen = mons->monitor_at(mons->ctx, x + w / 2, y + h / 2, &probe);
            }
            if (on_screen) pin(p, r.section, r.x, r.y, w, h);
            else pin_default(p, r.section, mons->primary(mons->ctx), w, h);
            return 0;
        }

        case HUD_PLACE_SETTLE:
            if (has_point) {
                p->x = r.x;
                p->y = r.y;
            }
            p->anchor = HUD_SEC_FREE;
            hud_place_settle(p, w, h, mons);
            return 1;
    }
    return 0;
}

static int clamp(int v, int lo, int hi) {
    if (hi < lo) return lo;                 // card larger than the monitor: show its start
    return v < lo ? lo : v > hi ? hi : v;
}

int hud_place_fit(HudPlacement *p, int w, int h, const HudMonitors *mons) {
    if (p->anchor == HUD_SEC_FREE) return 0;
    int x, y;
    hud_rect_at_anchor(p->anchor, p->ax, p->ay, w, h, &x, &y);
    // Kept on screen without moving the anchor: a card that grew too tall for its
    // spot is pushed back on, and returns to the exact spot when it shrinks.
    HudRect monitor;
    mons->monitor_at(mons->ctx, x + w / 2, y + h / 2, &monitor);
    x = clamp(x, monitor.x, monitor.x + monitor.w - w);
    y = clamp(y, monitor.y, monitor.y + monitor.h - h);
    int moved = x != p->x || y != p->y;
    p->x = x;
    p->y = y;
    return moved;
}
