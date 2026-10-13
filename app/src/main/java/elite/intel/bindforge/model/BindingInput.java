package elite.intel.bindforge.model;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One input as {@code .binds} writes it: the device ({@code Keyboard}, {@code Mouse}, a {@code DeviceMappings.xml}
 * name, or a hardware id) and its token ({@code Key_A}, {@code Mouse_1}, {@code Joy_5}, {@code Joy_POV1Up},
 * {@code Neg_Joy_YAxis}, {@code Joy_RZAxis}). Whether a token suits a slot is the writer's to judge.
 * <p>
 * Also holds the two rules about device names that every writer of {@code Device="..."} shares:
 * {@link #isReservedDevice} and {@link #isDeviceName}.
 */
public record BindingInput(String device, String key) {

    public static final String KEYBOARD = "Keyboard";
    public static final String MOUSE = "Mouse";
    public static final String NO_DEVICE = "{NoDevice}";

    private static final Set<String> RESERVED = Set.of("keyboard", "mouse", "{nodevice}");
    // The characters an XML element name may hold; a device name is a DeviceMappings.xml element name, or hex.
    private static final Pattern DEVICE_NAME = Pattern.compile("[\\p{L}\\p{N}_.:-]+");

    public BindingInput {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(key, "key");
    }

    /** Same device and token, ignoring case - the game's conflict matching does not distinguish them. */
    public boolean sameInputAs(BindingInput other) {
        return other != null && device.equalsIgnoreCase(other.device) && key.equalsIgnoreCase(other.key);
    }

    /**
     * True for the game's own literals - {@code Keyboard}, {@code Mouse}, {@code {NoDevice}} - in any case. They are
     * never a controller's name, so a lower-case {@code mouse} must not pass as one.
     */
    public static boolean isReservedDevice(String device) {
        return device != null && RESERVED.contains(device.toLowerCase(Locale.ROOT));
    }

    /** True for a controller's name or hardware id: characters an XML element name allows, and not reserved. */
    public static boolean isDeviceName(String device) {
        return device != null && DEVICE_NAME.matcher(device).matches() && !isReservedDevice(device);
    }
}
