package elite.intel.ui.event;

/**
 * Published on the UI bus whenever the HUD overlay is shown or hidden, by the button or by voice.
 * {@code visible} is whether an overlay is actually running, so a failed start reports {@code false}.
 */
public record HudOverlayVisibilityChangedEvent(boolean visible) {
}
