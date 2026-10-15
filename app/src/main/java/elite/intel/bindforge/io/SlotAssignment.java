package elite.intel.bindforge.io;

import elite.intel.bindforge.model.BindingInput;
import elite.intel.bindforge.model.BindingSlotType;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A deliberate edit of one button slot - Primary or Secondary - made by the user: any device, up to three
 * modifiers on any device, and the slot's Hold flag. Clears the slot when {@link #input()} is {@code null}.
 * <p>
 * For {@link BindingsWriter#assignSlot(SlotAssignment)} only, which nothing automatic may call; the assistant's
 * own writes go through {@link KeyboardBindingEdit}. The expected timestamp and size let the writer refuse a file
 * the game changed after it was read, as {@link KeyboardBindingEdit} does.
 *
 * @param modifiers held with the input, in the order given; not significant to the game
 * @param hold      the slot's Hold flag, written as {@code <Hold Value="1" />}
 */
public record SlotAssignment(
        Path file,
        String bindingId,
        BindingSlotType slotType,
        BindingInput input,
        List<BindingInput> modifiers,
        boolean hold,
        FileTime expectedLastModified,
        long expectedFileSize
) {
    /** The most modifiers the game keeps on one slot. */
    public static final int MAX_MODIFIERS = 3;

    public SlotAssignment {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(slotType, "slotType");
        modifiers = List.copyOf(modifiers == null ? List.of() : modifiers);
        if (input == null && (!modifiers.isEmpty() || hold)) {
            throw new IllegalArgumentException("Clearing a slot takes no modifiers and no Hold");
        }
        if (modifiers.size() > MAX_MODIFIERS) {
            throw new IllegalArgumentException("A slot takes at most " + MAX_MODIFIERS + " modifiers, not "
                    + modifiers.size());
        }
        List<BindingInput> seen = new ArrayList<>();
        for (BindingInput modifier : modifiers) {
            if (modifier.sameInputAs(input) || seen.stream().anyMatch(modifier::sameInputAs)) {
                throw new IllegalArgumentException("Modifier " + modifier + " repeats an input of the same slot");
            }
            seen.add(modifier);
        }
    }

    /** A request that empties the slot to {@code {NoDevice}}. */
    public static SlotAssignment clear(Path file, String bindingId, BindingSlotType slotType,
                                       FileTime expectedLastModified, long expectedFileSize) {
        return new SlotAssignment(file, bindingId, slotType, null, List.of(), false,
                expectedLastModified, expectedFileSize);
    }

    public boolean clearsSlot() {
        return input == null;
    }
}
