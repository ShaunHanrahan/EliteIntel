package elite.intel.ui.event;

/**
 * BindForge has just been activated - the master filled for the first time, silently or by first setup. Published
 * by {@code Activation.establish}; {@code ButtonMapStartup} then generates {@code .buttonMap} files for the
 * controllers already connected, which it holds back until now.
 */
public class BindForgeActivatedEvent {
}
