package elite.intel.ui.event;

/**
 * An installation's {@code DeviceMappings.xml} or a {@code .buttonMap} changed on disk - written by the game, by
 * hand, or by BindForge itself. Published by {@code DeviceFilesMonitor}; the Alias Designer screen re-reads on it.
 * <p>
 * Carries nothing: the screen's refresh checks every installation against the master, and that is what decides
 * whether the change means anything.
 */
public class DeviceFilesChangedEvent {
}
