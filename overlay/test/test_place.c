// Checks the placement arithmetic in hud_place.c against two fake monitors: a
// 2560x1440 primary at the origin and a 1920x1080 secondary to its right.
//
//   make test

#include "hud_place.h"

#include <stdio.h>

static int failures;

#define CHECK_EQ(actual, expected) do { \
    long a_ = (long) (actual), e_ = (long) (expected); \
    if (a_ != e_) { \
        fprintf(stderr, "%s:%d: %s == %ld, expected %ld\n", __FILE__, __LINE__, #actual, a_, e_); \
        failures++; \
    } \
} while (0)

static const HudRect PRIMARY = {0, 0, 2560, 1440};
static const HudRect SECOND = {2560, 0, 1920, 1080};

static int inside(HudRect r, int x, int y) {
    return x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h;
}

static int fake_monitor_at(void *ctx, int x, int y, HudRect *out) {
    (void) ctx;
    if (inside(SECOND, x, y)) { *out = SECOND; return 1; }
    *out = PRIMARY;
    return inside(PRIMARY, x, y);
}

static HudRect fake_primary(void *ctx) {
    (void) ctx;
    return PRIMARY;
}

static const HudMonitors MONS = {fake_monitor_at, fake_primary, NULL};

static HudPlaceRequest request(HudPlaceKind kind, HudSection section, int x, int y) {
    HudPlaceRequest r = {kind, section, x, y};
    return r;
}

static void names_round_trip(void) {
    for (int s = HUD_SEC_NW; s <= HUD_SEC_SE; s++) {
        CHECK_EQ(hud_section_parse(hud_section_name((HudSection) s)), s);
    }
    CHECK_EQ(hud_section_parse("north"), HUD_SEC_FREE);
    CHECK_EQ(hud_section_parse(NULL), HUD_SEC_FREE);
    CHECK_EQ(hud_section_name(HUD_SEC_FREE) == NULL, 1);
}

static void classify_by_centre(void) {
    // Centre at (100, 100): top-left cell.
    CHECK_EQ(hud_section_classify((HudRect) {0, 0, 200, 200}, PRIMARY), HUD_SEC_NW);
    // Centre at (1280, 1300): bottom middle.
    CHECK_EQ(hud_section_classify((HudRect) {880, 1200, 800, 200}, PRIMARY), HUD_SEC_S);
    // A centre hanging past the right edge still lands in the right column.
    CHECK_EQ(hud_section_classify((HudRect) {2500, 600, 200, 200}, PRIMARY), HUD_SEC_E);
    // Measured against the monitor it is given, not the virtual screen.
    CHECK_EQ(hud_section_classify((HudRect) {2600, 40, 200, 200}, SECOND), HUD_SEC_NW);
}

static void anchor_is_the_edge_facing_the_screen_edge(void) {
    HudRect card = {100, 200, 760, 300};
    int ax, ay;
    hud_anchor_of(HUD_SEC_NE, card, &ax, &ay);
    CHECK_EQ(ax, 860);
    CHECK_EQ(ay, 200);
    hud_anchor_of(HUD_SEC_S, card, &ax, &ay);
    CHECK_EQ(ax, 480);
    CHECK_EQ(ay, 500);

    // And back: a taller card anchored at the same bottom edge starts higher.
    int x, y;
    hud_rect_at_anchor(HUD_SEC_S, 480, 500, 760, 450, &x, &y);
    CHECK_EQ(x, 100);
    CHECK_EQ(y, 50);
}

static void default_spots_inset_by_the_margin(void) {
    int ax, ay;
    hud_default_anchor(HUD_SEC_SE, PRIMARY, &ax, &ay);     // margin 57
    CHECK_EQ(ax, 2560 - 57);
    CHECK_EQ(ay, 1440 - 57);
    hud_default_anchor(HUD_SEC_W, SECOND, &ax, &ay);       // margin 43
    CHECK_EQ(ax, 2560 + 43);
    CHECK_EQ(ay, 540);
}

static void opens_top_centre_of_the_primary(void) {
    HudPlacement p;
    hud_place_init(&p, 760, 200, &MONS);
    CHECK_EQ(p.anchor, HUD_SEC_N);
    CHECK_EQ(p.x, 1280 - 380);
    CHECK_EQ(p.y, 57);
}

static void move_uses_the_saved_point_on_this_monitor(void) {
    HudPlacement p;
    hud_place_init(&p, 760, 200, &MONS);
    HudPlaceRequest r = request(HUD_PLACE_MOVE, HUD_SEC_NE, 2400, 100);
    CHECK_EQ(hud_place_resolve(&p, &r, 760, 200, &MONS), 1);
    CHECK_EQ(r.kind, HUD_PLACE_NONE);
    CHECK_EQ(p.anchor, HUD_SEC_NE);
    CHECK_EQ(p.ax, 2400);
    CHECK_EQ(p.ay, 100);
    hud_place_fit(&p, 760, 200, &MONS);
    CHECK_EQ(p.x, 2400 - 760);
    CHECK_EQ(p.y, 100);
}

static void move_ignores_a_point_saved_on_another_monitor(void) {
    // The card is on the secondary; the saved NE point is on the primary.
    HudPlacement p = {2800, 300, HUD_SEC_NW, 2800, 300};
    HudPlaceRequest r = request(HUD_PLACE_MOVE, HUD_SEC_NE, 2400, 100);
    CHECK_EQ(hud_place_resolve(&p, &r, 760, 200, &MONS), 1);
    CHECK_EQ(p.anchor, HUD_SEC_NE);
    CHECK_EQ(p.ax, 2560 + 1920 - 43);
    CHECK_EQ(p.ay, 43);
}

static void move_without_a_point_takes_the_default(void) {
    HudPlacement p;
    hud_place_init(&p, 760, 200, &MONS);
    HudPlaceRequest r = request(HUD_PLACE_MOVE, HUD_SEC_SW, HUD_POS_UNSET, HUD_POS_UNSET);
    CHECK_EQ(hud_place_resolve(&p, &r, 760, 200, &MONS), 1);
    CHECK_EQ(p.ax, 57);
    CHECK_EQ(p.ay, 1440 - 57);
    hud_place_fit(&p, 760, 200, &MONS);
    CHECK_EQ(p.y, 1440 - 57 - 200);
}

static void restore_trusts_the_point_and_is_silent(void) {
    // Starts on the primary, restores onto the secondary: no monitor check
    // against where the fresh window happened to open.
    HudPlacement p;
    hud_place_init(&p, 760, 200, &MONS);
    HudPlaceRequest r = request(HUD_PLACE_RESTORE, HUD_SEC_NW, 2700, 80);
    CHECK_EQ(hud_place_resolve(&p, &r, 760, 200, &MONS), 0);
    CHECK_EQ(p.anchor, HUD_SEC_NW);
    CHECK_EQ(p.ax, 2700);
}

static void restore_off_every_monitor_falls_back_to_the_primary(void) {
    HudPlacement p;
    hud_place_init(&p, 760, 200, &MONS);
    HudPlaceRequest r = request(HUD_PLACE_RESTORE, HUD_SEC_SE, 9000, 9000);
    CHECK_EQ(hud_place_resolve(&p, &r, 760, 200, &MONS), 0);
    CHECK_EQ(p.ax, 2560 - 57);
    CHECK_EQ(p.ay, 1440 - 57);
}

static void a_move_right_after_a_restore_uses_the_restored_monitor(void) {
    // Overlay closed on the secondary, then "move to north east": the start
    // restores the last spot and the move follows in the same pass. The fresh
    // window opened on the primary, which must not decide which screen NE is on.
    HudPlacement p;
    hud_place_init(&p, 760, 200, &MONS);
    HudPlaceRequest restore = request(HUD_PLACE_RESTORE, HUD_SEC_NW, 2700, 80);
    HudPlaceRequest move = request(HUD_PLACE_MOVE, HUD_SEC_NE, HUD_POS_UNSET, HUD_POS_UNSET);
    hud_place_resolve(&p, &restore, 760, 200, &MONS);
    CHECK_EQ(hud_place_resolve(&p, &move, 760, 200, &MONS), 1);
    CHECK_EQ(p.ax, 2560 + 1920 - 43);
    CHECK_EQ(p.ay, 43);
}

static void settle_converts_a_legacy_corner(void) {
    HudPlacement p;
    hud_place_init(&p, 760, 200, &MONS);
    // Top-left (1700, 1150): centre (2080, 1250), bottom-right cell.
    HudPlaceRequest r = request(HUD_PLACE_SETTLE, HUD_SEC_FREE, 1700, 1150);
    CHECK_EQ(hud_place_resolve(&p, &r, 760, 200, &MONS), 1);
    CHECK_EQ(p.anchor, HUD_SEC_SE);
    CHECK_EQ(p.ax, 2460);
    CHECK_EQ(p.ay, 1350);
}

static void a_south_card_grows_upward(void) {
    HudPlacement p = {0, 0, HUD_SEC_S, 1280, 1380};
    hud_place_fit(&p, 760, 200, &MONS);
    CHECK_EQ(p.y, 1180);
    hud_place_fit(&p, 760, 500, &MONS);
    CHECK_EQ(p.y, 880);
    CHECK_EQ(p.ay, 1380);
}

static void a_card_too_big_for_its_spot_is_kept_on_screen(void) {
    // A north card taller than the room below its anchor is pushed up, and the
    // anchor itself is left alone so it returns when the card shrinks.
    HudPlacement p = {0, 0, HUD_SEC_N, 1280, 1300};
    hud_place_fit(&p, 760, 400, &MONS);
    CHECK_EQ(p.y, 1440 - 400);
    CHECK_EQ(p.ay, 1300);
}

static void a_free_card_is_left_alone(void) {
    HudPlacement p = {-50, -50, HUD_SEC_FREE, 0, 0};
    CHECK_EQ(hud_place_fit(&p, 760, 200, &MONS), 0);
    CHECK_EQ(p.x, -50);
}

int main(void) {
    names_round_trip();
    classify_by_centre();
    anchor_is_the_edge_facing_the_screen_edge();
    default_spots_inset_by_the_margin();
    opens_top_centre_of_the_primary();
    move_uses_the_saved_point_on_this_monitor();
    move_ignores_a_point_saved_on_another_monitor();
    move_without_a_point_takes_the_default();
    restore_trusts_the_point_and_is_silent();
    restore_off_every_monitor_falls_back_to_the_primary();
    a_move_right_after_a_restore_uses_the_restored_monitor();
    settle_converts_a_legacy_corner();
    a_south_card_grows_upward();
    a_card_too_big_for_its_spot_is_kept_on_screen();
    a_free_card_is_left_alone();
    if (failures) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    printf("placement: all checks passed\n");
    return 0;
}
