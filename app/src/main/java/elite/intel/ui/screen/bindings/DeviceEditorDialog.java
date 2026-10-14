package elite.intel.ui.screen.bindings;

import com.google.common.eventbus.Subscribe;
import elite.intel.bindforge.devices.DeviceEditorInputs;
import elite.intel.bindforge.devices.DeviceEditorMiddle;
import elite.intel.bindforge.devices.DeviceEditorLights;
import elite.intel.bindforge.devices.DeviceNames;
import elite.intel.bindforge.devices.InstallationMarkers;
import elite.intel.bindforge.devices.MyDevice;
import elite.intel.db.managers.BindForgeDeviceDraftManager;
import elite.intel.devices.events.DeviceAxisEvent;
import elite.intel.devices.events.DeviceButtonEvent;
import elite.intel.devices.events.DeviceDisconnectedEvent;
import elite.intel.devices.events.DeviceHatEvent;
import elite.intel.devices.model.DeviceDescription;
import elite.intel.eventbus.DeviceBus;
import elite.intel.ui.dialog.HudConfirmDialog;
import elite.intel.ui.theme.AppTheme;
import elite.intel.ui.theme.HudPalette;
import elite.intel.ui.widget.HudModalSpec;
import elite.intel.ui.widget.HudTextField;
import elite.intel.ui.widget.HudUnsavedHint;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static elite.intel.ui.i18n.MultiLingualTextProvider.getText;
import static elite.intel.ui.theme.AppTheme.makeButton;
import static elite.intel.ui.theme.AppTheme.makeButtonSubtle;
import static elite.intel.ui.theme.AppTheme.transparentPanel;

/**
 * The Device Editor: one device's record, opened from the gear on its My Devices row (alias-designer.md, <em>the
 * Device Editor</em>, 2026-10-10).
 * <p>
 * <strong>Built from what the controller reports.</strong> DEVICE always; AXES, BUTTONS and HATS only when the
 * controller has that kind of input ({@link DeviceEditorInputs}). A device with no name yet keeps its input tabs
 * disabled - a {@code .buttonMap} is named after the device, so there is nothing to label until it has a name.
 * <p>
 * <strong>SAVE writes the draft and nothing else</strong> - never the master, never a game installation. APPLY is
 * the screen's button. Leaving with unsaved edits asks Save, Discard or Keep editing.
 * <p>
 * <strong>CLEAR - RESET LABELS on a built-in - takes the device out of the draft</strong>, and APPLY takes it out of
 * every installation ({@link DeviceEditorMiddle}, A10). A device cleared and not applied says so, cannot be edited,
 * and offers its undo in the same place.
 * <p>
 * <strong>Live highlighting</strong> listens to the Device Service for this device alone while the dialog is open
 * ({@link DeviceEditorLights}). The dialog never changes tab for an input: a box lights only where the user is
 * looking.
 */
final class DeviceEditorDialog extends JDialog {

    private static final Logger log = LogManager.getLogger(DeviceEditorDialog.class);

    /** Six axes to a column, as the spec lays them out. */
    private static final int AXES_PER_COLUMN = 6;
    /** One sub-tab per 32 buttons, numbered as the game numbers them. */
    private static final int BUTTONS_PER_GROUP = 32;
    private static final int BUTTONS_PER_COLUMN = 8;
    private static final int FIELD_COLUMNS = 14;

    /**
     * One line of the install strip.
     *
     * @param marker {@code null} when the installation has no marker for this device
     */
    record StripLine(String installation, InstallationMarkers.Marker marker, String path) {
    }

    /**
     * Everything the dialog shows, read off the EDT by the screen.
     *
     * @param deviceName  the name the record is saved under - the master's, or Frontier's for a built-in - or
     *                    {@code null} for a device with no name yet
     * @param liveId      the Device Service's id for the controller when it is attached, else {@code null}
     * @param description which axes and hats it has when attached, else {@code null}
     * @param saved       the labels saved before the dialog opened: the draft's, or the master's with no draft
     * @param gameDrawsIt whether the game draws this device's inputs itself, so untouched defaults are not written
     * @param middle      what the middle button is, and whether it can be pressed
     */
    record Opening(MyDevice device, String deviceName, Integer liveId, DeviceDescription description,
                   DeviceEditorInputs inputs, Map<String, String> saved, boolean gameDrawsIt,
                   List<StripLine> strip, DeviceEditorMiddle middle) {
    }

    private final Opening opening;
    private final Runnable onSaved;
    private final Runnable onName;
    private final Map<String, String> starting;
    private final Map<String, HudTextField> boxes = new LinkedHashMap<>();
    private final DeviceEditorLights lights;
    private final Map<String, Color[]> restingColours = new LinkedHashMap<>();
    private final HudUnsavedHint unsavedHint = new HudUnsavedHint();
    private JButton save;
    private JButton middle;
    /** A write to the draft is under way - SAVE, CLEAR, RESET LABELS or an undo - so nothing else may start. */
    private boolean busy;

    /**
     * @param onSaved run on the EDT after a save, so the screen reads everything again
     * @param onName  run on the EDT after the dialog closes, when the user asks to name an unnamed controller - which
     *                opens onboarding for it (Alan, 2026-10-14, A6b)
     */
    DeviceEditorDialog(Component parent, Opening opening, Runnable onSaved, Runnable onName) {
        super(SwingUtilities.getWindowAncestor(parent), ModalityType.APPLICATION_MODAL);
        setUndecorated(true);
        this.opening = opening;
        this.onSaved = onSaved;
        this.onName = onName;
        this.starting = opening.inputs().startingLabels(opening.saved());
        this.lights = new DeviceEditorLights(opening.description() == null
                ? DeviceDescription.axesUnknown(0)
                : opening.description());
        buildUi();
    }

    void showDialog() {
        // WHY: on the bus only while open, and only once the dialog exists - a press with no dialog to light is
        // nothing, and a subscriber left registered after close would light boxes nobody sees, forever.
        if (opening.liveId() != null) DeviceBus.register(this);
        try {
            AppTheme.runWithModalScrim(getOwner(), () -> setVisible(true));
        } finally {
            if (opening.liveId() != null) DeviceBus.unregister(this);
        }
    }

    private boolean named() {
        return opening.deviceName() != null;
    }

    /**
     * NAME, for a controller onboarding would ask about - {@link DeviceNames#needingName}, the one rule - and not
     * already named in the draft for Apply.
     */
    private boolean offersName() {
        return !named() && !DeviceNames.needingName(List.of(opening.device())).isEmpty();
    }

    /** Labels can be edited once the device has a name - and not while it is cleared or reset, waiting for APPLY. */
    private boolean editable() {
        return named() && !opening.middle().undoing();
    }

    private void buildUi() {
        JTabbedPane tabs = AppTheme.makeCompactTabs();
        tabs.addTab(getText("bindings.aliasDesigner.editor.tab.device"), deviceTab());
        DeviceEditorInputs inputs = opening.inputs();
        if (!inputs.axes().isEmpty()) addInputTab(tabs, "bindings.aliasDesigner.editor.tab.axes", axesTab());
        if (!inputs.buttons().isEmpty()) addInputTab(tabs, "bindings.aliasDesigner.editor.tab.buttons", buttonsTab());
        if (inputs.hats() > 0) addInputTab(tabs, "bindings.aliasDesigner.editor.tab.hats", hatsTab());

        JPanel body = transparentPanel(new BorderLayout(0, HudPalette.HUD_GAP));
        body.add(tabs, BorderLayout.CENTER);
        body.add(note(getText("bindings.aliasDesigner.editor.workingCopy"), HudPalette.HUD_COLOR_ROLE_SECONDARY_TEXT),
                BorderLayout.SOUTH);

        save = makeButton(getText("button.save"));
        save.setEnabled(false);
        save.addActionListener(e -> save());
        JButton back = makeButtonSubtle(getText("button.back"));
        back.addActionListener(e -> leave());
        // WHY: in the middle, so the destructive button is beside neither of the ones reached for by habit. On a
        // built-in, RESET LABELS takes CLEAR's place, since CLEAR would remove Frontier's entry.
        DeviceEditorMiddle rule = opening.middle();
        middle = makeButtonSubtle(getText(switch (rule.kind()) {
            case CLEAR -> "bindings.aliasDesigner.editor.button.clear";
            case RESET_LABELS -> "bindings.aliasDesigner.editor.button.resetLabels";
            case UNDO_CLEAR -> "bindings.aliasDesigner.editor.button.undoClear";
            case UNDO_RESET -> "bindings.aliasDesigner.editor.button.undoReset";
        }));
        middle.setEnabled(rule.enabled());
        String why = whyText(rule);
        if (why != null) middle.setToolTipText(why);
        middle.addActionListener(e -> middleAction());

        HudModalSpec spec = HudModalSpec.builder()
                .title(getText("bindings.aliasDesigner.editor.title", titleName()))
                .onClose(this::leave)
                .body(body)
                .scrollBody(false)
                .dismiss(back)
                .middle(middle)
                .unsavedHint(unsavedHint)
                .primary(save)
                .build();
        setContentPane(AppTheme.hudModalScaffold(spec));
        getRootPane().registerKeyboardAction(e -> leave(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        // WHY: no default button. Enter in a label box would otherwise SAVE and close the dialog mid-edit.
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        pack();
        setLocationRelativeTo(getOwner());
    }

    private String titleName() {
        return named() ? opening.deviceName() : opening.device().label();
    }

    /** An input tab, disabled while the device has no name: there is nothing yet for its labels to be saved under. */
    private void addInputTab(JTabbedPane tabs, String titleKey, JComponent content) {
        tabs.addTab(getText(titleKey), content);
        tabs.setEnabledAt(tabs.getTabCount() - 1, editable());
    }

    // ------------------------------------------------------------------ DEVICE

    private JComponent deviceTab() {
        MyDevice device = opening.device();
        JPanel panel = transparentPanel(new GridBagLayout());
        int row = 0;
        row = field(panel, row, "bindings.aliasDesigner.editor.field.name", titleName());
        row = line(panel, row, getText(!named()
                ? "bindings.aliasDesigner.editor.noName"
                : device.builtIn() && device.alias() == null
                ? "bindings.aliasDesigner.editor.builtIn"
                : "bindings.aliasDesigner.editor.name.readOnly"), HudPalette.HUD_COLOR_ROLE_SECONDARY_TEXT);
        DeviceEditorMiddle rule = opening.middle();
        if (rule.undoing()) {
            row = line(panel, row, getText(rule.kind() == DeviceEditorMiddle.Kind.UNDO_RESET
                    ? "bindings.aliasDesigner.editor.reset"
                    : "bindings.aliasDesigner.editor.cleared"), HudPalette.HUD_COLOR_ROLE_WARNING);
        } else if (rule.why() == DeviceEditorMiddle.Why.NAMED_IN_BINDS
                || rule.why() == DeviceEditorMiddle.Why.BINDS_UNREAD) {
            row = line(panel, row, whyText(rule), HudPalette.HUD_COLOR_ROLE_WARNING);
        }
        if (offersName()) {
            JButton name = makeButton(getText("bindings.aliasDesigner.editor.button.name"));
            name.addActionListener(e -> {
                dispose();
                onName.run();
            });
            GridBagConstraints c = cell(0, row++);
            c.gridwidth = 3;
            panel.add(name, c);
        }
        row = field(panel, row, "bindings.aliasDesigner.editor.field.vid", device.vid());
        row = field(panel, row, "bindings.aliasDesigner.editor.field.pid", device.pid());
        row = readout(panel, row, "bindings.aliasDesigner.editor.field.windowsName",
                device.windowsName() == null ? "-" : device.windowsName());
        row = readout(panel, row, "bindings.aliasDesigner.editor.field.status", getText(device.attached()
                ? "bindings.aliasDesigner.status.attached"
                : "bindings.aliasDesigner.status.missing"));
        if (!device.attached()) {
            row = line(panel, row, getText(opening.inputs().isEmpty()
                    ? "bindings.aliasDesigner.editor.noInputs"
                    : "bindings.aliasDesigner.editor.notAttached"), HudPalette.HUD_COLOR_ROLE_WARNING);
        }
        row = installStrip(panel, row);
        filler(panel, row);
        return panel;
    }

    /**
     * Which installations the user has, and whether each matches the <em>saved master</em> for this device - never
     * the draft, or every keystroke would flip them all to "differs". A readout: it offers no repairs.
     */
    private int installStrip(JPanel panel, int row) {
        if (opening.strip().isEmpty()) return row;
        GridBagConstraints heading = cell(0, row++);
        heading.gridwidth = 3;
        heading.insets = new Insets(HudPalette.HUD_GAP * 2, 0, HudPalette.HUD_GAP / 2, 0);
        panel.add(AppTheme.hudReadoutLabel(getText("bindings.aliasDesigner.editor.strip.heading")), heading);
        int matching = 0;
        for (StripLine line : opening.strip()) {
            InstallationMarkers.Marker marker = line.marker();
            if (marker != null && marker.state() == InstallationMarkers.State.MATCHES) matching++;
            panel.add(AppTheme.hudReadoutLabel(line.installation()), cell(0, row));
            Color colour = marker == null || marker.severity() == null
                    ? HudPalette.HUD_COLOR_ROLE_PRIMARY_TEXT
                    : AliasDesignerPanel.colourOf(marker.severity());
            panel.add(AppTheme.hudReadoutValue(AliasDesignerPanel.markerText(marker), colour), cell(1, row));
            panel.add(AppTheme.hudReadoutValue(line.path(), HudPalette.HUD_COLOR_ROLE_SECONDARY_TEXT), cell(2, row));
            row++;
        }
        return line(panel, row, getText("bindings.aliasDesigner.editor.strip.summary", matching,
                opening.strip().size()), HudPalette.HUD_COLOR_ROLE_PRIMARY_TEXT);
    }

    /** A read-only field: the name until Rename is built, and VID/PID always - the game attaches a device by them. */
    private int field(JPanel panel, int row, String labelKey, String value) {
        panel.add(AppTheme.hudReadoutLabel(getText(labelKey)), cell(0, row));
        HudTextField field = new HudTextField();
        field.setText(value);
        field.setEditable(false);
        sized(field, FIELD_COLUMNS * 2);
        GridBagConstraints c = cell(1, row);
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, c);
        return row + 1;
    }

    private int readout(JPanel panel, int row, String labelKey, String value) {
        panel.add(AppTheme.hudReadoutLabel(getText(labelKey)), cell(0, row));
        GridBagConstraints c = cell(1, row);
        c.gridwidth = 2;
        panel.add(AppTheme.hudReadoutValue(value, HudPalette.HUD_COLOR_ROLE_PRIMARY_TEXT), c);
        return row + 1;
    }

    private int line(JPanel panel, int row, String text, Color colour) {
        GridBagConstraints c = cell(0, row);
        c.gridwidth = 3;
        panel.add(note(text, colour), c);
        return row + 1;
    }

    // ------------------------------------------------------------------ inputs

    private JComponent axesTab() {
        JPanel grid = transparentPanel(new GridBagLayout());
        List<String> axes = opening.inputs().axes();
        for (int i = 0; i < axes.size(); i++) {
            addBox(grid, axes.get(i), (i / AXES_PER_COLUMN) * 2, i % AXES_PER_COLUMN);
        }
        JPanel panel = transparentPanel(new BorderLayout(0, HudPalette.HUD_GAP));
        panel.add(grid, BorderLayout.NORTH);
        JPanel notes = transparentPanel(new GridLayout(0, 1, 0, HudPalette.HUD_GAP / 2));
        if (opening.inputs().attached() && !opening.inputs().axesKnown()) {
            notes.add(note(getText("bindings.aliasDesigner.editor.axesUnknown"),
                    HudPalette.HUD_COLOR_ROLE_SECONDARY_TEXT));
        }
        if (opening.inputs().axesWithoutSlot() > 0) {
            notes.add(note(getText("bindings.aliasDesigner.editor.axesWithoutSlot",
                    opening.inputs().axesWithoutSlot()), HudPalette.HUD_COLOR_ROLE_SECONDARY_TEXT));
        }
        panel.add(notes, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buttonsTab() {
        List<String> buttons = opening.inputs().buttons();
        if (buttons.size() <= BUTTONS_PER_GROUP) return buttonGroup(buttons);
        JTabbedPane groups = AppTheme.makeCompactTabs();
        for (int from = 0; from < buttons.size(); from += BUTTONS_PER_GROUP) {
            List<String> group = buttons.subList(from, Math.min(from + BUTTONS_PER_GROUP, buttons.size()));
            // WHY: named by the game's numbers. An unattached controller's buttons come from its labels and may skip,
            // so the range is the first and last token in the group, not a count.
            groups.addTab(number(group.getFirst()) + "–" + number(group.getLast()), buttonGroup(group));
        }
        return groups;
    }

    private JComponent buttonGroup(List<String> buttons) {
        JPanel grid = transparentPanel(new GridBagLayout());
        for (int i = 0; i < buttons.size(); i++) {
            addBox(grid, buttons.get(i), (i / BUTTONS_PER_COLUMN) * 2, i % BUTTONS_PER_COLUMN);
        }
        return top(grid);
    }

    private JComponent hatsTab() {
        JPanel grid = transparentPanel(new GridBagLayout());
        for (int hat = 1; hat <= opening.inputs().hats(); hat++) {
            List<String> tokens = DeviceEditorInputs.hatTokens(hat);
            for (int direction = 0; direction < tokens.size(); direction++) {
                addBox(grid, tokens.get(direction), direction * 2, hat - 1);
            }
        }
        return top(grid);
    }

    private static String number(String buttonToken) {
        return buttonToken.substring(buttonToken.indexOf('_') + 1);
    }

    /** One input: its token, as the game names it, then its label box. */
    private void addBox(JPanel grid, String token, int column, int row) {
        grid.add(AppTheme.hudReadoutLabel(token), cell(column, row));
        HudTextField box = new HudTextField();
        sized(box, FIELD_COLUMNS);
        ((AbstractDocument) box.getDocument()).setDocumentFilter(new LengthLimit());
        box.setText(starting.get(token));
        box.setEditable(editable());
        box.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshDirty(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshDirty(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshDirty(); }
        });
        box.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                // WHY: a box is never empty - clearing it puts the default back, and SAVE keeps what it shows.
                String kept = DeviceEditorInputs.clean(token, box.getText());
                if (!kept.equals(box.getText())) box.setText(kept);
            }
        });
        GridBagConstraints c = cell(column + 1, row);
        c.insets = new Insets(HudPalette.HUD_GAP / 2, HudPalette.HUD_GAP / 2, HudPalette.HUD_GAP / 2,
                HudPalette.HUD_GAP * 2);
        grid.add(box, c);
        boxes.put(token, box);
        restingColours.put(token, new Color[]{box.getBackground(), box.getForeground()});
    }

    private Map<String, String> boxValues() {
        Map<String, String> values = new LinkedHashMap<>();
        boxes.forEach((token, box) -> values.put(token, box.getText()));
        return values;
    }

    private boolean dirty() {
        return editable() && DeviceEditorInputs.changed(starting, boxValues());
    }

    private void refreshDirty() {
        boolean dirty = dirty();
        unsavedHint.setVisible(dirty);
        save.setEnabled(dirty && !busy);
    }

    // ------------------------------------------------------------------ leaving

    /** BACK, the close glyph and Esc: asks first when there are unsaved edits. */
    private void leave() {
        if (busy) return;
        if (!dirty()) {
            dispose();
            return;
        }
        HudConfirmDialog.Result answer = HudConfirmDialog.show(this,
                getText("bindings.aliasDesigner.editor.leave.title"),
                getText("bindings.aliasDesigner.editor.leave.message", titleName()),
                getText("button.save"),
                getText("bindings.aliasDesigner.editor.leave.discard"),
                getText("bindings.aliasDesigner.editor.leave.keepEditing"));
        switch (answer) {
            case PRIMARY -> save();
            case EXTRA -> dispose();
            // WHY: Keep editing is the dismiss answer, so Esc on the question returns to the dialog rather than
            // throwing edits away.
            case DISMISS -> { }
        }
    }

    /** Writes the device's whole label set into the draft, off the EDT, and closes once it is there. */
    private void save() {
        if (!dirty() || busy) return;
        Map<String, String> labels =
                DeviceEditorInputs.toSave(opening.saved(), boxValues(), opening.gameDrawsIt());
        MyDevice device = opening.device();
        runAndClose(() -> {
            BindForgeDeviceDraftManager.getInstance()
                    .saveLabels(opening.deviceName(), device.vid(), device.pid(), labels);
            return true;
        }, "bindings.aliasDesigner.editor.saveFailed");
    }

    // ------------------------------------------------------------------ the middle button

    /** Why the middle button cannot be pressed, as its tooltip - or {@code null} when it can. */
    private String whyText(DeviceEditorMiddle rule) {
        return switch (rule.why()) {
            case NONE -> null;
            case NO_NAME -> getText("bindings.aliasDesigner.editor.why.noName");
            case NOT_ADDED -> getText("bindings.aliasDesigner.editor.why.notAdded");
            case NOTHING_TO_RESET -> getText("bindings.aliasDesigner.editor.why.nothingToReset");
            case NAMED_IN_BINDS -> getText("bindings.aliasDesigner.editor.namedInBinds", rule.bindings(),
                    opening.deviceName());
            case BINDS_UNREAD -> getText("bindings.aliasDesigner.editor.why.bindsUnread");
        };
    }

    /**
     * CLEAR and RESET LABELS ask first, naming how many installations Apply will reach; the undo does not, since it
     * puts back what the master already holds. Each writes the draft alone, then closes.
     */
    private void middleAction() {
        DeviceEditorMiddle rule = opening.middle();
        if (busy || !rule.enabled()) return;
        String name = opening.deviceName();
        BindForgeDeviceDraftManager draft = BindForgeDeviceDraftManager.getInstance();
        if (rule.undoing()) {
            runAndClose(() -> draft.restoreFromMaster(name), "bindings.aliasDesigner.editor.actionFailed");
            return;
        }
        boolean reset = rule.kind() == DeviceEditorMiddle.Kind.RESET_LABELS;
        if (!HudConfirmDialog.confirm(this, getText("bindings.aliasDesigner.editor.heading"),
                getText(reset
                        ? "bindings.aliasDesigner.editor.reset.confirm"
                        : "bindings.aliasDesigner.editor.clear.confirm", name, opening.strip().size()),
                getText(reset
                        ? "bindings.aliasDesigner.editor.button.resetLabels"
                        : "bindings.aliasDesigner.editor.button.clear"),
                getText("button.back"))) {
            return;
        }
        runAndClose(() -> draft.removeByName(name), "bindings.aliasDesigner.editor.actionFailed");
    }

    /**
     * Writes the draft off the EDT - SAVE, CLEAR, RESET LABELS or an undo - then closes and has the screen read
     * everything again. A write that changed nothing ({@code false}) is reported, not closed on in silence: the device
     * changed since the dialog opened, an Apply having run meanwhile, say (review, 2026-10-11).
     *
     * @param failedKey the message for a write that threw, taking the reason as its argument. A stale device has its
     *                  own message, shown alone - wrapped in this one it would say "could not be done" and "nothing
     *                  was done" in one breath (review, 2026-10-11)
     */
    private void runAndClose(BooleanSupplier work, String failedKey) {
        busy = true;
        middle.setEnabled(false);
        refreshDirty();
        new Thread(() -> {
            String message = null;
            try {
                if (!work.getAsBoolean()) {
                    message = getText("bindings.aliasDesigner.editor.stale", opening.deviceName());
                }
            } catch (RuntimeException e) {
                // WHY: broad on purpose - a thread boundary, and the user pressed the button and must be told.
                log.warn("The Device Editor could not change {}", opening.deviceName(), e);
                message = getText(failedKey, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            String loadedMessage = message;
            SwingUtilities.invokeLater(() -> {
                busy = false;
                if (loadedMessage != null) {
                    middle.setEnabled(opening.middle().enabled());
                    refreshDirty();
                    HudConfirmDialog.info(this, getText("bindings.aliasDesigner.editor.heading"),
                            loadedMessage, getText("bindings.aliasDesigner.firstSetup.close"));
                    return;
                }
                dispose();
                onSaved.run();
            });
        }, "BindForge-DeviceEditorAction").start();
    }

    // ------------------------------------------------------------------ live highlighting

    @Subscribe
    public void onButton(DeviceButtonEvent event) {
        if (isThisDevice(event.deviceId())) onEdt(() -> lights.button(event.buttonIndex(), event.pressed()));
    }

    @Subscribe
    public void onAxis(DeviceAxisEvent event) {
        if (isThisDevice(event.deviceId())) onEdt(() -> lights.axis(event.axisIndex(), event.value()));
    }

    @Subscribe
    public void onHat(DeviceHatEvent event) {
        if (isThisDevice(event.deviceId())) onEdt(() -> lights.hat(event.hatIndex(), event.directions()));
    }

    @Subscribe
    public void onDisconnected(DeviceDisconnectedEvent event) {
        if (isThisDevice(event.deviceId())) {
            onEdt(() -> {
                lights.clear();
                return true;
            });
        }
    }

    private boolean isThisDevice(int deviceId) {
        return opening.liveId() != null && opening.liveId() == deviceId;
    }

    /** Runs a change to the lights on the EDT, and repaints the boxes when anything lit or went out. */
    private void onEdt(BooleanSupplier change) {
        SwingUtilities.invokeLater(() -> {
            if (change.getAsBoolean()) paintLights();
        });
    }

    /**
     * A lit box takes the selection fill - the canon allows a fill for activity - and goes back to its own colours
     * the moment its input is released.
     */
    private void paintLights() {
        Set<String> lit = lights.lit();
        boxes.forEach((token, box) -> {
            Color[] resting = restingColours.get(token);
            boolean on = lit.contains(token);
            box.setBackground(on ? HudPalette.HUD_COLOR_ROLE_SELECTION_BACKGROUND : resting[0]);
            box.setForeground(on ? HudPalette.HUD_COLOR_ROLE_SELECTED_TEXT : resting[1]);
        });
    }

    // ------------------------------------------------------------------ layout helpers

    private static GridBagConstraints cell(int column, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = column;
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(HudPalette.HUD_GAP / 2, 0, HudPalette.HUD_GAP / 2, HudPalette.HUD_GAP);
        return c;
    }

    /**
     * A field wide enough for {@code columns} characters at the HUD's field height. {@code HudTextField} fixes its
     * preferred width at zero, so columns alone would not size it.
     */
    private static void sized(HudTextField field, int columns) {
        int width = field.getFontMetrics(field.getFont()).charWidth('M') * columns;
        field.setPreferredSize(new Dimension(width, HudPalette.HUD_FIELD_HEIGHT));
    }

    private static void filler(JPanel panel, int row) {
        GridBagConstraints c = cell(0, row);
        c.weighty = 1;
        c.weightx = 1;
        c.gridwidth = 3;
        panel.add(Box.createGlue(), c);
    }

    private static JComponent top(JComponent content) {
        JPanel panel = transparentPanel(new BorderLayout());
        panel.add(content, BorderLayout.NORTH);
        return panel;
    }

    private static JLabel note(String text, Color colour) {
        return AppTheme.hudReadoutValue(text, colour);
    }

    /** Holds a box to the 18 characters the game can show, however the text arrives. */
    private static final class LengthLimit extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs)
                throws BadLocationException {
            replace(fb, offset, 0, text, attrs);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            String insert = text == null ? "" : text;
            int room = DeviceEditorInputs.MAX_LABEL_LENGTH - (fb.getDocument().getLength() - length);
            if (room <= 0) {
                if (length > 0) fb.remove(offset, length);
                return;
            }
            fb.replace(offset, length, insert.length() > room ? insert.substring(0, room) : insert, attrs);
        }
    }
}
