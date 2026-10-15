// X11/Xwayland shell for the HUD overlay.
//
// Owns only the window and the event loop; every pixel comes from hud_render().
// A Win32 shell is the same shape: create a translucent always-on-top window,
// let the user drag it, present a finished ARGB buffer.
//
// The window is override-redirect, which means the compositor leaves it
// unmanaged and stacked above managed windows, and it never takes keyboard
// focus - so every keystroke still reaches the game. It does receive pointer
// events, which is what makes dragging work.

#include "hud.h"

#include <X11/Xlib.h>
#include <X11/Xatom.h>
#include <X11/extensions/Xrandr.h>
#include <X11/Xutil.h>
#include <cairo/cairo-xlib.h>

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/time.h>
#include <unistd.h>

static long now_ms(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return tv.tv_sec * 1000L + tv.tv_usec / 1000L;
}

/// What the monitor lookups need from the shell.
typedef struct {
    Display *dpy;
    int screen;
} X11Monitors;

/// The monitor the point (x, y) sits on, in root-window coordinates.
///
/// WHY XRandR rather than DisplayWidth/DisplayHeight: on a multi-head setup those
/// describe the whole virtual screen spanning every monitor, so the computed
/// "centre" lands on the seam between two displays and a card centred on the
/// game's monitor is treated as far off centre. Elite commanders commonly run
/// more than one display.
///
/// Falls back to the full screen when XRandR reports nothing, when the point is
/// on no monitor (dragged into a gap between two of them), or on a server without
/// the extension. That is the single-monitor answer, which is exactly right for
/// the case it is describing - and the return value says it was a fallback.
static int monitor_at(void *ctx, int x, int y, HudRect *out) {
    X11Monitors *m = ctx;
    *out = (HudRect) {0, 0, DisplayWidth(m->dpy, m->screen), DisplayHeight(m->dpy, m->screen)};

    int count = 0;
    XRRMonitorInfo *monitors = XRRGetMonitors(m->dpy, RootWindow(m->dpy, m->screen), True, &count);
    if (!monitors) return 0;

    int found = 0;
    for (int i = 0; i < count && !found; i++) {
        if (x < monitors[i].x || x >= monitors[i].x + monitors[i].width) continue;
        if (y < monitors[i].y || y >= monitors[i].y + monitors[i].height) continue;
        *out = (HudRect) {monitors[i].x, monitors[i].y, monitors[i].width, monitors[i].height};
        found = 1;
    }
    XRRFreeMonitors(monitors);
    return found;
}

/// The monitor XRandR calls primary, else the first it lists, else the whole
/// screen. Only where a card with no history opens: once it has been placed, the
/// card's own position decides which monitor it is on.
static HudRect primary_monitor(void *ctx) {
    X11Monitors *m = ctx;
    HudRect rect = {0, 0, DisplayWidth(m->dpy, m->screen), DisplayHeight(m->dpy, m->screen)};

    int count = 0;
    XRRMonitorInfo *monitors = XRRGetMonitors(m->dpy, RootWindow(m->dpy, m->screen), True, &count);
    if (!monitors) return rect;
    for (int i = 0; i < count; i++) {
        if (i == 0 || monitors[i].primary) {
            rect = (HudRect) {monitors[i].x, monitors[i].y, monitors[i].width, monitors[i].height};
        }
        if (monitors[i].primary) break;
    }
    XRRFreeMonitors(monitors);
    return rect;
}

int hud_run_desktop(int argc, char **argv) {
    int managed = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--managed")) managed = 1;
    }

    Display *dpy = XOpenDisplay(NULL);
    if (!dpy) { fprintf(stderr, "overlay: cannot open display\n"); return 1; }
    int screen = DefaultScreen(dpy);
    Window root = RootWindow(dpy, screen);

    // A 32-bit visual is the whole ballgame: without it there is no alpha
    // channel and the best available would be uniform window opacity.
    XVisualInfo vinfo;
    if (!XMatchVisualInfo(dpy, screen, 32, TrueColor, &vinfo)) {
        fprintf(stderr, "overlay: no 32-bit ARGB visual; per-pixel alpha unavailable\n");
        return 2;
    }

    X11Monitors monitors = {dpy, screen};
    HudMonitors mons = {monitor_at, primary_monitor, &monitors};

    int width = hud_card_width();
    int height = 200;
    HudPlacement pl;
    hud_place_init(&pl, width, height, &mons);
    int win_x = pl.x, win_y = pl.y;     // where the window actually is

    XSetWindowAttributes attrs;
    attrs.colormap = XCreateColormap(dpy, root, vinfo.visual, AllocNone);
    attrs.background_pixel = 0;
    attrs.border_pixel = 0;
    // Capture mode is managed for the same reason it drops the notification
    // hint below: an override-redirect window is invisible to the window
    // manager, and so to anything that asks the window manager what windows
    // exist - which is how a capture tool builds the list the commander picks
    // from.
    attrs.override_redirect = (managed || model.capture) ? False : True;
    attrs.event_mask = ExposureMask | ButtonPressMask | ButtonReleaseMask | PointerMotionMask;

    Window win = XCreateWindow(dpy, root, pl.x, pl.y, width, height, 0, 32,
                               InputOutput, vinfo.visual,
                               CWColormap | CWBackPixel | CWBorderPixel |
                               CWOverrideRedirect | CWEventMask, &attrs);
    XStoreName(dpy, win, model.capture ? "EliteIntel HUD (VR capture)" : "EliteIntel HUD Overlay");

    Atom wtype = XInternAtom(dpy, "_NET_WM_WINDOW_TYPE", False);
    // NOTIFICATION says "transient, not a real window" and window lists honour
    // that by leaving it out - but ONLY a managed window needs to say so, and
    // saying it while override-redirect is actively harmful.
    //
    // Harmful because a compositor does not merely file a notification window
    // away, it ANIMATES it. KWin's sliding-popups effect slides notification
    // windows in and out, and it does that to override-redirect windows too, by
    // translating them: a commander on Plasma saw the HUD ease upward on its own
    // in decelerating steps - 59px, then 15px, same x, same size - every time
    // something re-triggered the slide. From a video that is an overlay drifting
    // about "as if blown by a light wind", and no amount of looking at this
    // program's own XMoveWindow calls explains it, because none of them ran.
    //
    // And the hint was never buying anything here: an override-redirect window is
    // unmanaged, so it is already absent from _NET_CLIENT_LIST, wmctrl and every
    // window list built from them - verified. It is only in --managed mode, where
    // the window manager really does own the window, that NOTIFICATION does the
    // job the comment above describes and keeps it off the taskbar.
    //
    // So: NORMAL whenever this shell owns its own placement, NOTIFICATION only
    // when it has handed placement to the window manager. Capture mode is NORMAL
    // for its own reason - it must appear in the list a capture tool picks from.
    int self_placed = !managed && !model.capture;
    Atom kind = XInternAtom(dpy, (model.capture || self_placed)
                                     ? "_NET_WM_WINDOW_TYPE_NORMAL"
                                     : "_NET_WM_WINDOW_TYPE_NOTIFICATION", False);
    XChangeProperty(dpy, win, wtype, XA_ATOM, 32, PropModeReplace, (unsigned char *) &kind, 1);
    if (!model.capture) {
        Atom wstate = XInternAtom(dpy, "_NET_WM_STATE", False);
        Atom above = XInternAtom(dpy, "_NET_WM_STATE_ABOVE", False);
        XChangeProperty(dpy, win, wstate, XA_ATOM, 32, PropModeReplace, (unsigned char *) &above, 1);
    }

    XMapWindow(dpy, win);
    XRaiseWindow(dpy, win);

    cairo_surface_t *target = cairo_xlib_surface_create(dpy, win, vinfo.visual, width, height);
    cairo_surface_t *buffer = cairo_image_surface_create(CAIRO_FORMAT_ARGB32, width, height);

    int xfd = ConnectionNumber(dpy);
    int quit = 0, dirty = 1, dragging = 0, drag_x = 0, drag_y = 0;
    long last_tick = now_ms();

    while (!quit) {
        while (XPending(dpy)) {
            XEvent ev;
            XNextEvent(dpy, &ev);
            if (ev.type == Expose) dirty = 1;
            else if (ev.type == ButtonPress && ev.xbutton.button == Button1) {
                dragging = 1;
                // Unpinned for the drag: the hand holds the card now, and a row
                // arriving mid-drag must not yank it back to the old anchor.
                pl.anchor = HUD_SEC_FREE;
                drag_x = ev.xbutton.x_root - pl.x;
                drag_y = ev.xbutton.y_root - pl.y;
            } else if (ev.type == ButtonRelease && ev.xbutton.button == Button1) {
                if (dragging) {
                    hud_place_settle(&pl, width, height, &mons);
                    hud_report_placement(&pl);
                }
                dragging = 0;
            } else if (ev.type == MotionNotify && dragging) {
                pl.x = ev.xmotion.x_root - drag_x;
                pl.y = ev.xmotion.y_root - drag_y;
                XMoveWindow(dpy, win, pl.x, pl.y);
                win_x = pl.x;
                win_y = pl.y;
                // Moving the window used to be the whole of a drag, because
                // nothing about the card depended on where it was. The lean does,
                // so a move is now a reason to redraw: without this the card
                // keeps whatever angle it had when it was last drawn for some
                // other reason, and only snaps to the right one when the next
                // line arrives. The Win32 shell always redrew here.
                dirty = 1;
            }
        }

        long t = now_ms();
        if (t - last_tick >= TYPEWRITER_MS) {
            last_tick = t;
            if (hud_tick_typewriter()) dirty = 1;
        }

        // Resolved in the layout pass below, where the card's size is known.
        // Not while dragging: the hand wins, and the request waits for release.
        if (hud_place_pending() && !dragging) dirty = 1;
        if (hud_card_width() != width) dirty = 1;

        if (dirty) {
            dirty = 0;

            // Measured at the CARD's width, which is also the window's - the
            // shear is vertical, so it makes the window taller and never wider.
            int card_w = hud_card_width();

            cairo_t *measure = cairo_create(buffer);
            int card_h = hud_render(measure, card_w, 0);
            cairo_destroy(measure);
            if (card_h < 1) card_h = 1;

            // Placed before the lean is worked out, because the lean depends on
            // where the card sits - and placed again after, because the lean
            // makes the window taller and a bottom anchor has to account for it.
            int report = 0;
            if (!dragging) {
                report = hud_place_resolve(&pl, &model.place_start, card_w, card_h, &mons);
                report |= hud_place_resolve(&pl, &model.place_move, card_w, card_h, &mons);
                hud_place_fit(&pl, card_w, card_h, &mons);
            }

            // Measured first: the lean depends on where the card's centre sits
            // vertically, which is not known until its height is. Offsets are
            // subtracted so the card's position is relative to ITS monitor, not
            // to a virtual screen spanning all of them.
            int cx = pl.x + card_w / 2, cy = pl.y + card_h / 2;
            HudRect mon;
            monitor_at(&monitors, cx, cy, &mon);
            // Flat when captured: see the same decision in platform_win32.c.
            double slope = model.capture ? 0.0 : hud_tilt_slope(cx - mon.x, cy - mon.y, mon.w, mon.h);

            int win_h = hud_tilt_height(slope, card_w, card_h);
            if (!dragging) hud_place_fit(&pl, card_w, win_h, &mons);
            if (report) hud_report_placement(&pl);
            // Moved and resized in one request, so a card growing upward from a
            // bottom anchor never shows a frame at the new size in the old spot.
            // Only on a change: this pass runs on every typewriter tick.
            if (pl.x != win_x || pl.y != win_y || card_w != width || win_h != height) {
                XMoveResizeWindow(dpy, win, pl.x, pl.y, card_w, win_h);
                win_x = pl.x;
                win_y = pl.y;
            }
            if (card_w != width || win_h != height) {
                width = card_w;
                height = win_h;
                cairo_xlib_surface_set_size(target, width, height);
                cairo_surface_destroy(buffer);
                buffer = cairo_image_surface_create(CAIRO_FORMAT_ARGB32, width, height);
            }

            cairo_t *cr = cairo_create(buffer);
            // Cleared, not painted over: the shear leaves a wedge top and bottom
            // that has to reach the compositor as genuinely transparent.
            cairo_set_operator(cr, CAIRO_OPERATOR_CLEAR);
            cairo_paint(cr);
            cairo_set_operator(cr, CAIRO_OPERATOR_OVER);

            hud_tilt_apply(cr, slope, card_w);
            hud_paint_panel(cr, card_w, card_h);
            hud_render(cr, card_w, 1);
            cairo_destroy(cr);

            // One blit of a finished frame. The window is never cleared, so
            // there is no intermediate state for the compositor to present -
            // this is what AWT does not let you control, and why the Swing
            // overlay strobed on every typewriter tick.
            cairo_t *out = cairo_create(target);
            cairo_set_operator(out, CAIRO_OPERATOR_SOURCE);
            cairo_set_source_surface(out, buffer, 0, 0);
            cairo_paint(out);
            cairo_destroy(out);
            cairo_surface_flush(target);
            XFlush(dpy);
        }

        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(xfd, &fds);
        FD_SET(STDIN_FILENO, &fds);
        int maxfd = xfd > STDIN_FILENO ? xfd : STDIN_FILENO;
        struct timeval tv = {0, TYPEWRITER_MS * 1000};
        if (select(maxfd + 1, &fds, NULL, NULL, &tv) < 0 && errno != EINTR) break;

        if (FD_ISSET(STDIN_FILENO, &fds)) {
            char chunk[4096];
            int got = (int) read(STDIN_FILENO, chunk, sizeof(chunk));
            if (got <= 0) break;         // parent closed the pipe or died
            if (hud_feed(chunk, got, &quit)) dirty = 1;
        }
    }

    cairo_surface_destroy(buffer);
    cairo_surface_destroy(target);
    XDestroyWindow(dpy, win);
    XCloseDisplay(dpy);
    return 0;
}
