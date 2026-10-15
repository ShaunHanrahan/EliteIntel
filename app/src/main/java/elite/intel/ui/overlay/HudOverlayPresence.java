package elite.intel.ui.overlay;

import elite.intel.eventbus.UiBus;
import elite.intel.session.SystemSession;
import elite.intel.ui.event.AppLogEvent;
import elite.intel.ui.event.HudOverlayVisibilityChangedEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static elite.intel.ui.i18n.MultiLingualTextProvider.getText;

/**
 * The one owner of whether the HUD overlay is on screen.
 * <p>
 * The overlay is switched by the AI tab's button and by voice, and both must agree: before voice could do
 * it, the button kept its own flag, and an overlay shown by voice would have left the button offering to
 * show it again. So both ask here, the stored setting is written here, and the button follows
 * {@link HudOverlayVisibilityChangedEvent} instead of its own idea of the state.
 */
public final class HudOverlayPresence {

    private static final Logger log = LogManager.getLogger(HudOverlayPresence.class);
    private static HudOverlayPresence instance;

    private final NativeHudOverlay overlay;
    private final SystemSession systemSession = SystemSession.getInstance();

    /**
     * Seam for tests.
     */
    HudOverlayPresence(NativeHudOverlay overlay) {
        this.overlay = overlay;
    }

    /**
     * Built on first use, not at class load: the overlay reads its stored layout when it is constructed.
     */
    public static synchronized HudOverlayPresence getInstance() {
        if (instance == null) instance = new HudOverlayPresence(new NativeHudOverlay());
        return instance;
    }

    /**
     * The overlay itself, for the settings dialog, which tunes it while it runs.
     */
    public NativeHudOverlay overlay() {
        return overlay;
    }

    public boolean isShowing() {
        return overlay.isRunning();
    }

    /**
     * Whether the current display mode has a desktop card that can be moved by section.
     */
    public boolean hasDesktopCard() {
        return overlay.getDisplayMode().hasDesktopCard();
    }

    /**
     * Puts the overlay on screen if it is not already. Returns whether it is running.
     * <p>
     * The stored setting records what the commander asked for, not what happened: a start that failed to
     * spawn the process is a broken install, not a change of mind, and storing it as "off" would quietly
     * retire the overlay for good.
     */
    public synchronized boolean show() {
        if (overlay.isRunning()) return true;
        rememberVisibility(true);
        return start();
    }

    public synchronized void hide() {
        overlay.stop();
        rememberVisibility(false);
        UiBus.publish(new HudOverlayVisibilityChangedEvent(false));
    }

    /**
     * Sends the desktop card to a section of its monitor, opening the overlay first if it is closed: asking
     * to put it somewhere means the commander wants to see it. Returns whether it is running.
     */
    public synchronized boolean moveTo(HudScreenSection section) {
        overlay.moveTo(section);
        return show();
    }

    /**
     * Puts the overlay back on screen if that is where the commander left it when the app last ran.
     * <p>
     * A read of the setting, never a write. An overlay that cannot start - a missing binary, a half install -
     * must leave the stored preference alone, so fixing the install brings the overlay back instead of the
     * commander finding a setting that turned itself off while they were not looking.
     */
    public synchronized void restore() {
        if (!systemSession.isHudOverlayVisible()) return;
        start();
    }

    private boolean start() {
        boolean started = overlay.start();
        if (!started) UiBus.publish(new AppLogEvent(getText("overlay.error.notStarted")));
        UiBus.publish(new HudOverlayVisibilityChangedEvent(started));
        return started;
    }

    /**
     * A settings write must never cost the commander the overlay they just asked for, so a failure here is
     * logged and the overlay is still switched.
     */
    private void rememberVisibility(boolean visible) {
        try {
            systemSession.setHudOverlayVisible(visible);
        } catch (RuntimeException e) {
            log.warn("Could not store the HUD overlay visibility: {}", e.getMessage());
        }
    }
}
