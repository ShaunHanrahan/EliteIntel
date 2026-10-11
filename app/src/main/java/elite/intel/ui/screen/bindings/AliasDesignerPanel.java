package elite.intel.ui.screen.bindings;

import elite.intel.bindforge.devicefiles.BindsDeviceReferences;
import elite.intel.bindforge.devicefiles.DeviceApply;
import elite.intel.bindforge.devicefiles.DeviceDivergence;
import elite.intel.bindforge.devicefiles.DeviceDivergenceScanner;
import elite.intel.bindforge.devicefiles.DeviceEntry;
import elite.intel.bindforge.devicefiles.DeviceEntry.HardwareId;
import elite.intel.bindforge.devicefiles.DeviceFilesAdopt;
import elite.intel.bindforge.devicefiles.DeviceFilesCheck;
import elite.intel.bindforge.devicefiles.DeviceFilesPush;
import elite.intel.bindforge.devicefiles.DeviceMappingsParser;
import elite.intel.bindforge.devicefiles.DivergenceWaysOut;
import elite.intel.bindforge.devicefiles.DivergenceWaysOut.WayOut;
import elite.intel.bindforge.devicefiles.DraftChanges;
import elite.intel.bindforge.devicefiles.FirstSetup;
import elite.intel.bindforge.devicefiles.FrontierStockDevices;
import elite.intel.bindforge.devicefiles.InstallationDeviceScanner;
import elite.intel.bindforge.devicefiles.LabelReMerge;
import elite.intel.bindforge.devices.BuiltInDevice;
import elite.intel.bindforge.devices.BuiltInDeviceList;
import elite.intel.bindforge.devices.DeviceEditorInputs;
import elite.intel.bindforge.devices.DeviceEditorMiddle;
import elite.intel.bindforge.devices.DeviceIdentities;
import elite.intel.bindforge.devices.InstallationMarkers;
import elite.intel.bindforge.devices.MyDevice;
import elite.intel.bindforge.devices.MyDeviceList;
import elite.intel.bindforge.install.GameInstallation;
import elite.intel.bindforge.install.InstallationRegistry;
import elite.intel.bindforge.install.WindowsGameInstallationProvider;
import elite.intel.db.dao.BindForgeInstallationsDao.InstallationRow;
import elite.intel.db.dao.BindForgeDeviceDraftDao.DraftRow;
import elite.intel.db.dao.BindForgeDeviceMasterDao.DeviceRow;
import elite.intel.db.managers.BindForgeDeviceDraftManager;
import elite.intel.db.managers.BindForgeDeviceMasterManager;
import elite.intel.devices.DeviceService;
import elite.intel.devices.model.Device;
import elite.intel.devices.model.DeviceDescription;
import elite.intel.devices.model.DeviceIdentity;
import elite.intel.session.PlayerSession;
import elite.intel.ui.dialog.HudConfirmDialog;
import elite.intel.ui.theme.AppTheme;
import elite.intel.ui.theme.HudGlyphs;
import elite.intel.ui.theme.HudPalette;
import elite.intel.ui.widget.HudFooter;
import elite.intel.ui.widget.HudPanel;
import elite.intel.ui.widget.HudSection;
import elite.intel.ui.widget.HudTable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static elite.intel.ui.i18n.MultiLingualTextProvider.getText;
import static elite.intel.ui.theme.AppTheme.*;
import static elite.intel.ui.theme.HudPalette.HUD_COLOR_ROLE_APPLICATION_BACKGROUND;
import static elite.intel.ui.theme.HudPalette.HUD_ICON_TABLE;
import static elite.intel.ui.theme.HudPalette.HUD_TABLE_ROW_HEIGHT;

/**
 * Alias Designer, being built in stages: My Devices above the divergence list, and first setup opened from here
 * while the master is empty. The list compares each installation with the master, or before first setup, the
 * installations with each other.
 * <p>
 * Every installation is supposed to hold the same device files, so anywhere they disagree is something to
 * resolve. <strong>One list, not three</strong> - every kind of disagreement appears together, because
 * splitting them would mean BindForge deciding which kinds deserve attention. What ranks them is the
 * severity, and severity is judged against the bindings: a missing entry only costs the user something when
 * a binding actually names that device.
 * <p>
 * <strong>The ways out are offered per row</strong> once the master exists: select a row and its buttons appear
 * beneath the list - revert, adopt, or the label re-merge - each confirmed before it runs (Alan, 2026-10-08).
 * <p>
 * <strong>My Devices carries one Installations column per installation</strong> once the master exists: each cell
 * says whether that installation matches the master for the device, in the divergence list's colour - a readout,
 * never a switch (Alan, 2026-10-08).
 * <p>
 * <strong>Built-in Devices is the second view of the device list</strong>, a tab beside My Devices: Frontier's
 * shipped entries from the reference in the jar, read-only, with the divergence list beneath both (Alan, 2026-10-08).
 * <p>
 * <strong>A gear ends every My Devices row</strong> and opens the Device Editor for that device - the one record,
 * edited in a dialog and saved to the draft (Alan, 2026-10-10). Before first setup it says SET UP comes first and
 * offers it: first setup throws any draft away as it fills the master, so edits saved before then would be lost.
 * <p>
 * <strong>APPLY and DISCARD are the screen's</strong>, in its footer (Alan, 2026-10-11, A10). APPLY writes the master
 * and the draft's changes to every installation and reports per installation; it is offered whenever the master
 * exists, since Apply with nothing saved is the retry after a partial one. DISCARD throws the whole draft away and
 * is offered only when the draft changes something, which the line beside it names.
 */
public class AliasDesignerPanel extends JPanel {

    private static final Logger log = LogManager.getLogger(AliasDesignerPanel.class);

    private static final int MAX_COLUMN_WIDTH = 420;

    /**
     * The My Devices columns describing the device, in order. The Installations columns follow them, so their count
     * is where the markers start - stated once here, for the column names and the renderer alike.
     */
    private static final List<String> DEVICE_COLUMN_KEYS = List.of(
            "bindings.aliasDesigner.column.device",
            "bindings.aliasDesigner.column.vidPid",
            "bindings.aliasDesigner.column.status",
            "bindings.aliasDesigner.column.alias");

    /** The Built-in Devices columns, in order - stated once, for the column names and the tooltip's column alike. */
    private static final List<String> BUILT_IN_COLUMN_KEYS = List.of(
            "bindings.aliasDesigner.column.device",
            "bindings.aliasDesigner.column.vidPid",
            "bindings.aliasDesigner.column.alsoCovers",
            "bindings.aliasDesigner.column.status");

    /** The Built-in Devices column carrying the alternative pairs, whose cell lists them all as a tooltip. */
    private static final int ALSO_COVERS_COLUMN =
            BUILT_IN_COLUMN_KEYS.indexOf("bindings.aliasDesigner.column.alsoCovers");

    /** Pairs per line of that tooltip - GamePad's 79 on one line would run off the screen. */
    private static final int PAIRS_PER_TOOLTIP_LINE = 6;

    private final InstallationRegistry registry;

    private DefaultTableModel tableModel;
    private JTable table;
    private DefaultTableModel myDevicesModel;
    private JTable myDevicesTable;
    private DefaultTableModel builtInModel;
    private JTable builtInTable;
    private List<BuiltInDevice> currentBuiltIn = List.of();
    private List<DeviceDivergence.Finding> currentFindings = List.of();
    private Markers currentMarkers = Markers.NONE;
    /** The check the findings came from, or {@code null} before first setup, when there are no ways out. */
    private DeviceFilesCheck.Report currentReport;
    private JPanel waysOutPanel;
    private Map<String, String> installLabels = Map.of();
    /** Each installation's folder, keyed as findings key them - for the Device Editor's install strip. */
    private Map<String, String> installPaths = Map.of();
    /** The My Devices rows, in table order - the gear opens the one it sits on. */
    private List<MyDevice> currentMyDevices = List.of();
    private final AtomicBoolean refreshInProgress = new AtomicBoolean();
    private boolean deviceServiceRunning;
    private final InstallationDeviceScanner deviceScanner = new InstallationDeviceScanner();
    private JButton setUpButton;
    private JButton applyButton;
    private JButton discardButton;
    private JLabel pendingLine;
    private boolean masterEmpty;
    private List<String> currentChanges = List.of();
    private boolean screenActionRunning;

    public AliasDesignerPanel() {
        this(new InstallationRegistry(
                new WindowsGameInstallationProvider(PlayerSession.getInstance().getBindingsDir())));
    }

    AliasDesignerPanel(InstallationRegistry registry) {
        this.registry = registry;
        buildUi();
    }

    private void buildUi() {
        setLayout(new BorderLayout());
        setBorder(hudSubtabContentBorder());
        setBackground(HUD_COLOR_ROLE_APPLICATION_BACKGROUND);

        tableModel = new ReadOnlyTableModel(columnNames(), 0);
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        HudTable.style(table);
        // WHY: severity is the row's text colour, never a fill or a pill - the HUD canon's rule for state.
        table.setDefaultRenderer(Object.class, new SeverityRenderer());
        // WHY: locked, or the palette pass that walks the tree later calls styleTable and puts the standard
        // renderer back, taking the severity colours with it. HUD_TABLE_STYLE_LOCKED is how a table says it
        // has styled itself deliberately - see ED_HUD_REFERENCE section 8.6.
        table.putClientProperty(AppTheme.HUD_TABLE_STYLE_LOCKED, Boolean.TRUE);

        HudSection section = new HudSection(
                getText("bindings.aliasDesigner.section.divergence"),
                new BorderLayout(),
                HudPanel.Variant.FLAT,
                6);
        section.body().add(HudTable.dataPlaneScrollPane(table), BorderLayout.CENTER);
        // WHY: beneath the list rather than in the footer, as first setup offers its choices - the buttons belong
        // to the selected row, and change with it.
        waysOutPanel = transparentPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        section.body().add(waysOutPanel, BorderLayout.SOUTH);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showWaysOut();
        });

        myDevicesModel = new MyDevicesTableModel(myDevicesColumnNames(List.of()));
        myDevicesTable = new JTable(myDevicesModel);
        myDevicesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        HudTable.style(myDevicesTable);
        // WHY: each Installations marker is coloured by its own state, the canon's text colour - and locked, as the
        // divergence table is, so the palette pass does not put the plain renderer back.
        myDevicesTable.setDefaultRenderer(Object.class, new MarkerRenderer());
        myDevicesTable.putClientProperty(AppTheme.HUD_TABLE_STYLE_LOCKED, Boolean.TRUE);

        builtInModel = new ReadOnlyTableModel(builtInColumnNames(), 0);
        builtInTable = new JTable(builtInModel);
        builtInTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        HudTable.style(builtInTable);
        // WHY: locked for the same reason - this renderer carries the alternative pairs' tooltip.
        builtInTable.setDefaultRenderer(Object.class, new AlternativesRenderer());
        builtInTable.putClientProperty(AppTheme.HUD_TABLE_STYLE_LOCKED, Boolean.TRUE);

        JPanel builtIn = transparentPanel(new BorderLayout(0, 6));
        builtIn.add(HudTable.dataPlaneScrollPane(builtInTable), BorderLayout.CENTER);
        // WHY: the reference belongs to one game version, so the list says which - a controller Frontier added
        // since is not on it, and its absence must not read as Frontier not supporting it.
        builtIn.add(AppTheme.hudReadoutValue(getText("bindings.aliasDesigner.builtIn.captured",
                        FrontierStockDevices.CAPTURED.toString()),
                HudPalette.HUD_COLOR_ROLE_SECONDARY_TEXT), BorderLayout.SOUTH);

        // WHY: the two views of the device list are a COMPACT tab pair, as the spec and the UI component map give
        // them. The tabs name the views, so neither sits in a titled section of its own.
        JTabbedPane myDevices = AppTheme.makeCompactTabs();
        myDevices.addTab(getText("bindings.aliasDesigner.section.myDevices"),
                HudTable.dataPlaneScrollPane(myDevicesTable));
        myDevices.addTab(getText("bindings.aliasDesigner.section.builtIn"), builtIn);

        JButton rescanButton = makeButton(getText("bindings.aliasDesigner.button.recheck"));
        rescanButton.addActionListener(e -> initData());

        // WHY: offered only while the master is empty - first setup runs once, and offered, never forced: the
        // user opens it when they choose to.
        setUpButton = makeButton(getText("bindings.aliasDesigner.firstSetup.button"));
        setUpButton.setEnabled(false);
        setUpButton.addActionListener(e -> openFirstSetup());

        // WHY: stacked rather than tabbed. The two answer different questions - what devices there are, and
        // where the installations disagree about them - and a user resolving a divergence wants the device it
        // names in sight. Equal halves because neither is the subordinate of the other. The divergence list stays
        // beneath both device views, since it is not a view of the device list.
        JPanel stacked = new JPanel(new GridLayout(2, 1, 0, 6));
        stacked.setOpaque(false);
        stacked.add(myDevices);
        stacked.add(section);

        // WHY: APPLY is the primary, last on the right; DISCARD sits on the left with the line naming what it would
        // throw away, so the two are never side by side.
        applyButton = makeButton(getText("bindings.aliasDesigner.button.apply"));
        applyButton.setEnabled(false);
        applyButton.addActionListener(e -> applyAll());
        discardButton = makeButtonSubtle(getText("bindings.aliasDesigner.button.discard"));
        discardButton.setEnabled(false);
        discardButton.addActionListener(e -> discardAll());
        pendingLine = AppTheme.hudReadoutValue("", HudPalette.HUD_COLOR_ROLE_WARNING);
        JPanel left = transparentPanel(new FlowLayout(FlowLayout.LEFT, HudPalette.HUD_GAP, 0));
        left.add(discardButton);
        left.add(pendingLine);

        add(stacked, BorderLayout.CENTER);
        add(HudFooter.build(false, null, left, List.of(setUpButton, rescanButton, applyButton)), BorderLayout.SOUTH);
    }

    /**
     * APPLY once the master exists, DISCARD while the draft changes something - and neither while one of them, or a
     * way out, is running.
     */
    private void refreshScreenActions() {
        applyButton.setEnabled(!masterEmpty && !screenActionRunning);
        discardButton.setEnabled(!currentChanges.isEmpty() && !screenActionRunning);
        pendingLine.setText(currentChanges.isEmpty()
                ? ""
                : getText("bindings.aliasDesigner.pending", String.join(", ", currentChanges)));
    }

    /**
     * Reads the installations and their device files off the EDT, same as
     * {@code BindingManagementPanel.performBackup()} - parsing every {@code .binds} and every installation's
     * {@code DeviceMappings.xml} would otherwise freeze the window, and an installation on a spun-down or
     * disconnected drive freezes it for as long as the filesystem takes to answer.
     * <p>
     * A refresh already in flight is left to finish rather than a second one being started beside it: this is
     * called again on every ship-profile change, and nothing here depends on the ship.
     */
    public void initData() {
        if (!refreshInProgress.compareAndSet(false, true)) return;
        new Thread(() -> {
            Map<String, String> labels = Map.of();
            Map<String, String> paths = Map.of();
            Divergence divergence = new Divergence(null, List.of());
            List<MyDevice> myDevices = List.of();
            List<BuiltInDevice> builtIn = List.of();
            Markers markers = Markers.NONE;
            boolean empty = false;
            List<String> changes = List.of();
            try {
                empty = BindForgeDeviceMasterManager.getInstance().findAll().isEmpty();
                changes = DraftChanges.stored();
                List<InstallationRow> rows = registry.currentWithStartupScan();
                labels = labelsFor(rows);
                paths = rows.stream().collect(Collectors.toMap(AliasDesignerPanel::keyOf, InstallationRow::rootPath,
                        (first, second) -> first, LinkedHashMap::new));
                recordWhatEachInstallationHolds(rows);
                divergence = divergenceFor(rows);
                Map<String, List<DeviceEntry>> entries = entriesByInstall(rows);
                List<Device> attached = readAttached();
                myDevices = readMyDevices(attached, entries);
                builtIn = BuiltInDeviceList.build(FrontierStockDevices.getInstance(), attached);
                // WHY: only against a master. Before first setup M, not added and the divergence colour mean
                // nothing, and the column is left out rather than filled with an invented state.
                if (divergence.report() != null) markers = markersFor(rows, entries, myDevices, divergence.findings());
            } catch (RuntimeException e) {
                // WHY: broad on purpose. This is a thread boundary, and an exception escaping it would kill
                // the thread silently and leave the table showing whatever it showed before, with no clue why.
                log.warn("Could not refresh the divergence list", e);
            }
            Map<String, String> loadedLabels = labels;
            Map<String, String> loadedPaths = paths;
            Divergence loadedDivergence = divergence;
            List<MyDevice> loadedMyDevices = myDevices;
            List<BuiltInDevice> loadedBuiltIn = builtIn;
            Markers loadedMarkers = markers;
            boolean loadedEmpty = empty;
            List<String> loadedChanges = changes;
            SwingUtilities.invokeLater(() -> {
                installLabels = loadedLabels;
                installPaths = loadedPaths;
                masterEmpty = loadedEmpty;
                currentChanges = loadedChanges;
                setUpButton.setEnabled(masterEmpty);
                refreshScreenActions();
                showMyDevices(loadedMyDevices, loadedMarkers);
                showBuiltIn(loadedBuiltIn);
                currentReport = loadedDivergence.report();
                showFindings(loadedDivergence.findings());
                refreshInProgress.set(false);
            });
        }, "BindForge-Divergence").start();
    }

    /**
     * Each installation against the master, once first setup has filled it. Before then there is nothing to compare
     * against, so the installations are compared with each other - which is what shows the user why SET UP is
     * worth pressing (Alan, 2026-10-08).
     */
    private Divergence divergenceFor(List<InstallationRow> rows) {
        Path bindings = PlayerSession.getInstance().getBindingsDir();
        // WHY: decided by the check's own reading of the master, not a separate one, so the rows and the master
        // they are judged against are always the same master.
        DeviceFilesCheck.Report report = new DeviceFilesCheck().check();
        return report.setUp()
                ? new Divergence(report, DeviceDivergenceScanner.scanAgainstMaster(report, bindings))
                : new Divergence(null, DeviceDivergenceScanner.scan(controlSchemesByInstall(rows), bindings));
    }

    /** @param report the check the findings came from, or {@code null} when they compare installations */
    private record Divergence(DeviceFilesCheck.Report report, List<DeviceDivergence.Finding> findings) {
    }

    /**
     * The Installations columns: one per installation, keyed as findings key them, and each device's markers in the
     * order My Devices lists the devices. Empty before first setup.
     */
    private record Markers(List<String> installs, List<Map<String, InstallationMarkers.Marker>> perDevice) {
        static final Markers NONE = new Markers(List.of(), List.of());
    }

    /**
     * Every installation gets a column, its folder there or not - one that is gone says <em>not found</em> rather
     * than vanishing, since the user still has it (Alan, 2026-10-08).
     */
    private static Markers markersFor(List<InstallationRow> rows, Map<String, List<DeviceEntry>> entries,
                                      List<MyDevice> devices, List<DeviceDivergence.Finding> findings) {
        List<String> installs = rows.stream().map(AliasDesignerPanel::keyOf).toList();
        Set<String> notFound = rows.stream().filter(InstallationRow::missing)
                .map(AliasDesignerPanel::keyOf)
                .collect(Collectors.toSet());
        List<Map<String, InstallationMarkers.Marker>> perDevice = devices.stream()
                .map(device -> InstallationMarkers.of(device, installs, notFound, entries, findings))
                .toList();
        return new Markers(installs, perDevice);
    }

    /**
     * The selected row's ways out, one button each - or, where there is none, why. Before first setup there is no
     * master to revert to or adopt into, so SET UP is the way out.
     */
    private void showWaysOut() {
        waysOutPanel.removeAll();
        int index = table.getSelectedRow();
        if (index >= 0 && index < currentFindings.size()) {
            DeviceDivergence.Finding finding = currentFindings.get(index);
            if (currentReport == null) {
                waysOutPanel.add(note(getText("bindings.aliasDesigner.wayOut.beforeSetUp")));
            } else {
                List<WayOut> ways = DivergenceWaysOut.of(finding, currentReport);
                if (ways.isEmpty()) waysOutPanel.add(note(getText(noWayOutKey(DivergenceWaysOut.whyNone(finding)))));
                for (WayOut way : ways) waysOutPanel.add(wayOutButton(way, finding));
            }
        }
        waysOutPanel.revalidate();
        waysOutPanel.repaint();
    }

    private static JLabel note(String text) {
        return AppTheme.hudReadoutValue(text, HudPalette.HUD_COLOR_ROLE_PRIMARY_TEXT);
    }

    private static String noWayOutKey(DivergenceWaysOut.NoWayOut why) {
        return why == DivergenceWaysOut.NoWayOut.ORPHAN
                ? "bindings.aliasDesigner.wayOut.none.orphan"
                : "bindings.aliasDesigner.wayOut.none.unreadable";
    }

    private JButton wayOutButton(WayOut way, DeviceDivergence.Finding finding) {
        JButton button = makeButtonSubtle(switch (way.kind()) {
            case REVERT -> getText("bindings.aliasDesigner.wayOut.revert", namesOf(way.installIds()));
            case ADOPT -> getText("bindings.aliasDesigner.wayOut.adopt", namesOf(way.installIds()),
                    String.valueOf(way.hardware()));
            case REMERGE -> getText("bindings.aliasDesigner.wayOut.remerge");
        });
        button.addActionListener(e -> {
            switch (way.kind()) {
                case REVERT -> revert(way);
                case ADOPT -> adopt(way, finding);
                case REMERGE -> reMerge(way, finding);
            }
        });
        button.setEnabled(!screenActionRunning);
        return button;
    }

    /**
     * Pushes the master to each installation the row names. That is the whole installation, not the row, so the
     * confirm says how many rows it reaches.
     */
    private void revert(WayOut way) {
        Set<String> keys = new LinkedHashSet<>();
        way.installIds().forEach(id -> keys.add(DeviceDivergence.installKey(id)));
        long rows = currentFindings.stream()
                .filter(finding -> finding.installs().stream().anyMatch(keys::contains))
                .count();
        if (!HudConfirmDialog.confirm(this, getText("bindings.aliasDesigner.wayOut.title"),
                getText("bindings.aliasDesigner.wayOut.revert.confirm", namesOf(way.installIds()), rows),
                getText("bindings.aliasDesigner.wayOut.revert.do"),
                getText("bindings.aliasDesigner.firstSetup.cancel"))) {
            return;
        }
        runWayOut(() -> {
            DeviceFilesPush push = new DeviceFilesPush();
            List<DeviceFilesPush.InstallationResult> results = new ArrayList<>();
            for (long installId : way.installIds()) results.addAll(push.push(installId).installations());
            return revertText(new DeviceFilesPush.Report(results));
        });
    }

    private String revertText(DeviceFilesPush.Report report) {
        StringBuilder text = new StringBuilder(getText("bindings.aliasDesigner.wayOut.revert.result",
                report.matchingCount(), report.installations().size()));
        for (DeviceFilesPush.InstallationResult result : report.installations()) {
            if (result.reason() == null) continue;
            text.append('\n').append(getText("bindings.aliasDesigner.firstSetup.result.installation",
                    namesOf(List.of(result.target().installId())), result.reason()));
        }
        return text.toString();
    }

    /** Takes the row's device, as one installation holds it, into the master - and nothing else of that installation's. */
    private void adopt(WayOut way, DeviceDivergence.Finding finding) {
        long installId = way.installIds().getFirst();
        String installName = namesOf(way.installIds());
        if (!HudConfirmDialog.confirm(this, getText("bindings.aliasDesigner.wayOut.title"),
                getText("bindings.aliasDesigner.wayOut.adopt.confirm", finding.deviceName(), installName,
                        String.valueOf(way.hardware())),
                getText("bindings.aliasDesigner.wayOut.adopt.do"),
                getText("bindings.aliasDesigner.firstSetup.cancel"))) {
            return;
        }
        runWayOut(() -> adoptText(new DeviceFilesAdopt().adopt(installId, finding.deviceName()),
                getText("bindings.aliasDesigner.wayOut.adopt.done", finding.deviceName(), installName)));
    }

    /**
     * The label merge with the master as one side. The labels are read off the EDT, the merge view asks about the
     * inputs that collide - none, when the sides only add to each other - and the answer goes into the master.
     */
    private void reMerge(WayOut way, DeviceDivergence.Finding finding) {
        String device = finding.deviceName();
        String title = getText("bindings.aliasDesigner.wayOut.title");
        offEdt(title, () -> LabelReMerge.stored(device, way.installIds()), merge -> {
            Map<String, String> answers = Map.of();
            if (!merge.collisions().isEmpty()) {
                Map<String, String> sideNames = new LinkedHashMap<>(installLabels);
                sideNames.put(LabelReMerge.MASTER, getText("bindings.aliasDesigner.wayOut.masterSide"));
                Optional<Map<String, String>> chosen =
                        new LabelMergeDialog(this, device, merge, sideNames, Map.of()).showDialog();
                if (chosen.isEmpty()) return;
                answers = chosen.get();
            }
            List<String> unanswered = merge.unanswered(answers);
            if (!unanswered.isEmpty()) {
                HudConfirmDialog.info(this, getText("bindings.aliasDesigner.wayOut.title"),
                        getText("bindings.aliasDesigner.wayOut.remerge.unanswered", unanswered.size()),
                        getText("bindings.aliasDesigner.firstSetup.close"));
                return;
            }
            Map<String, String> merged = merge.merged(answers);
            if (!HudConfirmDialog.confirm(this, getText("bindings.aliasDesigner.wayOut.title"),
                    getText("bindings.aliasDesigner.wayOut.remerge.confirm", device),
                    getText("bindings.aliasDesigner.wayOut.remerge.do"),
                    getText("bindings.aliasDesigner.firstSetup.cancel"))) {
                return;
            }
            runWayOut(() -> adoptText(new DeviceFilesAdopt().adoptLabels(device, merged),
                    getText("bindings.aliasDesigner.wayOut.remerge.done", device)));
        });
    }

    private String adoptText(DeviceFilesAdopt.Result result, String doneText) {
        return switch (result.outcome()) {
            case ADOPTED -> result.conflicts().isEmpty()
                    ? doneText
                    : doneText + "\n" + getText("bindings.aliasDesigner.wayOut.conflicts",
                    result.conflicts().stream()
                            .map(conflict -> conflict.inputToken() == null
                                    ? conflict.deviceName()
                                    : conflict.deviceName() + " " + conflict.inputToken())
                            .distinct()
                            .collect(Collectors.joining(", ")));
            case NOTHING_TO_ADOPT -> getText("bindings.aliasDesigner.wayOut.nothing");
            case REFUSED -> getText("bindings.aliasDesigner.wayOut.refused", result.reason());
        };
    }

    /** Runs a way out off the EDT, says what happened, and reads everything again. */
    private void runWayOut(Callable<String> work) {
        runAndReport(getText("bindings.aliasDesigner.wayOut.title"), work);
    }

    /**
     * Runs a write off the EDT - a way out, APPLY or DISCARD - says what happened when there is something to say, and
     * reads everything again.
     */
    private void runAndReport(String title, Callable<String> work) {
        offEdt(title, work, message -> {
            if (message != null) {
                HudConfirmDialog.info(this, title, message, getText("bindings.aliasDesigner.firstSetup.close"));
            }
            initData();
        });
    }

    /**
     * Reads or writes game files and the master off the EDT, with the buttons disabled meanwhile, then hands the
     * result back on it. A failure is shown under {@code title}, not only logged - the user pressed a button and must
     * be told - and the screen is read again, since a write may have got part of the way.
     */
    private <T> void offEdt(String title, Callable<T> work, Consumer<T> onEdt) {
        for (Component button : waysOutPanel.getComponents()) button.setEnabled(false);
        // WHY: APPLY and DISCARD wait too - a revert and an Apply writing the same installation at once would each
        // keep an Edit History copy of a file the other was replacing.
        screenActionRunning = true;
        refreshScreenActions();
        new Thread(() -> {
            T result = null;
            String failure = null;
            try {
                result = work.call();
            } catch (Exception e) {
                // WHY: broad on purpose - a thread boundary, and anything escaping it would leave the buttons
                // disabled with no word of why.
                log.warn("{} failed", title, e);
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            T loaded = result;
            String loadedFailure = failure;
            SwingUtilities.invokeLater(() -> {
                screenActionRunning = false;
                refreshScreenActions();
                showWaysOut();
                if (loadedFailure != null) {
                    HudConfirmDialog.info(this, title, getText("bindings.aliasDesigner.wayOut.failed", loadedFailure),
                            getText("bindings.aliasDesigner.firstSetup.close"));
                    initData();
                    return;
                }
                onEdt.accept(loaded);
            });
        }, "BindForge-ScreenWrite").start();
    }

    /** The installations' names as the list shows them, sorted. */
    private String namesOf(List<Long> installIds) {
        return installIds.stream()
                .map(DeviceDivergence::installKey)
                .map(key -> installLabels.getOrDefault(key, key))
                .sorted()
                .collect(Collectors.joining(", "));
    }

    /**
     * APPLY: the draft into the master, then the master and the pending removals out to every installation, reported
     * per installation. Asked first - it is the one button on the screen that writes a game file.
     */
    private void applyAll() {
        if (!HudConfirmDialog.confirm(this, getText("bindings.aliasDesigner.apply.title"),
                getText("bindings.aliasDesigner.apply.confirm", installLabels.size()),
                getText("bindings.aliasDesigner.apply.do"),
                getText("bindings.aliasDesigner.firstSetup.cancel"))) {
            return;
        }
        runAndReport(getText("bindings.aliasDesigner.apply.title"), () -> applyText(new DeviceApply().apply()));
    }

    private String applyText(DeviceApply.Result result) {
        StringBuilder text = new StringBuilder(revertText(result.report()));
        if (!result.cleanedUp().isEmpty()) {
            text.append('\n').append(getText("bindings.aliasDesigner.apply.removed",
                    String.join(", ", result.cleanedUp())));
        }
        if (!result.notEverywhere().isEmpty()) {
            text.append('\n').append(getText("bindings.aliasDesigner.apply.notEverywhere",
                    String.join(", ", result.notEverywhere())));
        }
        if (result.bindsUnread()) {
            text.append('\n').append(getText("bindings.aliasDesigner.apply.bindsUnread"));
        } else if (!result.heldBack().isEmpty()) {
            text.append('\n').append(getText("bindings.aliasDesigner.apply.heldBack",
                    String.join(", ", result.heldBack())));
        }
        return text.toString();
    }

    /** DISCARD: the whole draft thrown away, after naming what it holds. No installation is touched. */
    private void discardAll() {
        if (currentChanges.isEmpty()) return;
        if (!HudConfirmDialog.confirm(this, getText("bindings.aliasDesigner.discard.title"),
                getText("bindings.aliasDesigner.discard.confirm", String.join(", ", currentChanges)),
                getText("bindings.aliasDesigner.discard.do"),
                getText("bindings.aliasDesigner.firstSetup.cancel"))) {
            return;
        }
        runAndReport(getText("bindings.aliasDesigner.discard.title"), () -> {
            BindForgeDeviceDraftManager.getInstance().discard();
            return null;
        });
    }

    /**
     * Reads every installation for first setup off the EDT, then opens it. Nothing is written until the user
     * confirms the summary inside it.
     */
    private void openFirstSetup() {
        setUpButton.setEnabled(false);
        Map<String, String> names = installLabels;
        new Thread(() -> {
            FirstSetup setup = null;
            FirstSetup.Reading reading = null;
            String failure = null;
            try {
                setup = FirstSetup.stored();
                reading = setup.read();
            } catch (IOException | RuntimeException e) {
                // WHY: broad on purpose - a thread boundary, and the user pressed a button and must be told.
                log.warn("First setup could not read the installations", e);
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            FirstSetup loadedSetup = setup;
            FirstSetup.Reading loadedReading = reading;
            String loadedFailure = failure;
            SwingUtilities.invokeLater(() -> {
                setUpButton.setEnabled(masterEmpty);
                // WHY: decided on the reading, not the message. An exception can carry no message, and keyed on
                // that, a failed read would open the dialog with nothing to show.
                if (loadedSetup == null || loadedReading == null) {
                    HudConfirmDialog.info(this, getText("bindings.aliasDesigner.firstSetup.title"),
                            getText("bindings.aliasDesigner.firstSetup.readFailed", loadedFailure),
                            getText("bindings.aliasDesigner.firstSetup.close"));
                    return;
                }
                new FirstSetupDialog(this, loadedSetup, loadedReading, names, this::initData).showDialog();
            });
        }, "BindForge-FirstSetupRead").start();
    }

    /**
     * Records what each installation's device files hold right now.
     * <p>
     * A write on a refresh, which is deliberate: these rows are BindForge's only record of what is actually
     * on disk, and they are worth nothing if they are older than the disk. The installation list is already
     * refreshed the same way, on the same trigger.
     * <p>
     * The Installations markers do not read these rows: they need labels and a severity. Their colour and the
     * differences come from the check against the master; which names an installation gives the hardware comes from
     * this refresh's own read of its file (Alan, 2026-10-08). The rows stay the stored record of reality, for
     * whatever must notice a change between refreshes.
     */
    private void recordWhatEachInstallationHolds(List<InstallationRow> rows) {
        for (InstallationRow row : rows) {
            // WHY: a missing installation is skipped rather than scanned and found empty. Its folder is not
            // there, so reading it would record no devices - which is indistinguishable from a game patch
            // having wiped the file, and would raise exactly the alarm these rows exist to raise.
            if (row.missing()) continue;
            deviceScanner.scan(row.id(), GameInstallation.controlSchemesUnder(Path.of(row.rootPath())));
        }
    }

    /**
     * The controllers attached right now, read once per refresh for both device views.
     * <p>
     * {@code DeviceService} runs as one of the application's services, so with those stopped it reports no
     * controllers at all. The lists still build - the file half needs no hardware - but My Devices is then only
     * half a list and no built-in can read attached, and both views say so on screen rather than leaving an
     * absence to be mistaken for "nothing is plugged in".
     */
    private List<Device> readAttached() {
        DeviceService service = DeviceService.getInstance();
        deviceServiceRunning = service.isAvailable();
        if (!deviceServiceRunning) {
            log.info("Device service is not running, so no controllers can be read - the list will show only "
                    + "what the installations' files name");
        }
        return deviceServiceRunning ? service.getConnectedDevices() : List.of();
    }

    /**
     * Gathers the four things My Devices is built from: the attached controllers, every installation's device
     * entries, the master's records, and Frontier's shipped list.
     */
    private List<MyDevice> readMyDevices(List<Device> attached, Map<String, List<DeviceEntry>> entriesByInstall) {
        return MyDeviceList.build(
                attached,
                MyDeviceList.entriesAcross(entriesByInstall.values()),
                BindForgeDeviceMasterManager.getInstance().findAll(),
                FrontierStockDevices.getInstance());
    }

    /**
     * Each present installation's device entries.
     * <p>
     * An installation whose file cannot be read is left out rather than counted as holding nothing. The device
     * list is a union, so a missing contribution only makes it shorter - but silently treating an unreadable
     * file as an empty one would hide a real problem behind a shorter list.
     */
    private Map<String, List<DeviceEntry>> entriesByInstall(List<InstallationRow> rows) {
        Map<String, List<DeviceEntry>> byInstall = new LinkedHashMap<>();
        controlSchemesByInstall(rows).forEach((install, controlSchemes) -> {
            try {
                byInstall.put(install,
                        DeviceMappingsParser.parseIfPresent(GameInstallation.deviceMappingsIn(controlSchemes)));
            } catch (IOException e) {
                log.warn("Could not read device entries for {}: {}", install, e.getMessage());
            }
        });
        return byInstall;
    }

    /**
     * Only installations whose folder is there: a missing one cannot be read, and reporting its entries as
     * absent would fill the list with red caused by an unplugged drive.
     * <p>
     * Keyed by row id. <strong>Not by storefront</strong>, which is a label rather than a key - a machine can
     * hold two Frontier copies, several hand-added installations, or three Steams on Linux, and keying by
     * storefront would drop all but the last of them from the comparison without saying so.
     */
    private Map<String, Path> controlSchemesByInstall(List<InstallationRow> rows) {
        Map<String, Path> byInstall = new LinkedHashMap<>();
        for (InstallationRow row : rows) {
            if (row.missing()) continue;
            byInstall.put(keyOf(row), GameInstallation.controlSchemesUnder(Path.of(row.rootPath())));
        }
        return byInstall;
    }

    /**
     * The identity a finding carries. Unique by construction, and never shown to the user. The same key findings
     * against the master carry, so they find their names too.
     */
    static String keyOf(InstallationRow row) {
        return DeviceDivergence.installKey(row.id());
    }

    /**
     * What each installation is called on screen.
     * <p>
     * The storefront alone where it identifies one installation, and the storefront with its folder where it
     * does not - so two Frontier copies read as two different things rather than twice as "FRONTIER". Verbose
     * only when being brief would be ambiguous.
     */
    static Map<String, String> labelsFor(List<InstallationRow> rows) {
        Map<String, Long> countByStorefront = rows.stream()
                .collect(Collectors.groupingBy(InstallationRow::storefront, Collectors.counting()));

        Map<String, String> labels = new LinkedHashMap<>();
        for (InstallationRow row : rows) {
            boolean storefrontIsEnough = countByStorefront.getOrDefault(row.storefront(), 0L) == 1;
            labels.put(keyOf(row), storefrontIsEnough
                    ? row.storefront()
                    : row.storefront() + " (" + row.rootPath() + ")");
        }
        return labels;
    }

    /** The columns describing the device, then one per installation once the master exists. */
    private String[] myDevicesColumnNames(List<String> markerInstalls) {
        List<String> names = new ArrayList<>();
        DEVICE_COLUMN_KEYS.forEach(key -> names.add(getText(key)));
        markerInstalls.forEach(install -> names.add(installLabels.getOrDefault(install, install)));
        // WHY: the gear is last, after the Installations columns, and on every row (spec, My Devices).
        names.add("");
        return names.toArray(String[]::new);
    }

    private void showMyDevices(List<MyDevice> devices, Markers markers) {
        currentMarkers = markers;
        currentMyDevices = devices;
        myDevicesModel.setRowCount(0);
        // WHY: the column set follows the installations, which can be added or removed between refreshes.
        myDevicesModel.setColumnIdentifiers(myDevicesColumnNames(markers.installs()));
        for (int i = 0; i < devices.size(); i++) {
            MyDevice device = devices.get(i);
            List<Object> cells = new ArrayList<>(List.of(
                    device.label(),
                    device.vid() + ":" + device.pid(),
                    getText(device.attached()
                            ? "bindings.aliasDesigner.status.attached"
                            : "bindings.aliasDesigner.status.missing"),
                    aliasCell(device)));
            if (i < markers.perDevice().size()) {
                Map<String, InstallationMarkers.Marker> deviceMarkers = markers.perDevice().get(i);
                markers.installs().forEach(install -> cells.add(markerText(deviceMarkers.get(install))));
            }
            while (cells.size() < myDevicesModel.getColumnCount()) cells.add("");
            myDevicesModel.addRow(cells.toArray());
        }
        // WHY: said in the table rather than only in the log. An empty list because nothing is plugged in and
        // an empty list because nothing is looking are different states, and the second is the one a user
        // cannot diagnose.
        if (!deviceServiceRunning) {
            myDevicesModel.addRow(new Object[]{getText("bindings.aliasDesigner.deviceService.stopped")});
        }
        HudTable.fitColumnsToContent(myDevicesTable, MAX_COLUMN_WIDTH);
        // WHY: after the columns are rebuilt - setColumnIdentifiers makes new ones, without the gear's renderer.
        TableColumn gear = myDevicesTable.getColumnModel().getColumn(myDevicesModel.getColumnCount() - 1);
        gear.setCellRenderer(new GearRenderer());
        gear.setCellEditor(new GearEditor());
        gear.setPreferredWidth(HUD_TABLE_ROW_HEIGHT + 4);
        gear.setMaxWidth(HUD_TABLE_ROW_HEIGHT + 10);
    }

    /**
     * Opens the Device Editor for one My Devices row. Before first setup there is no master for its edits to be saved
     * against - first setup throws any draft away - so it says SET UP comes first, and offers it (Alan, 2026-10-10).
     */
    private void openDeviceEditor(int row) {
        if (row < 0 || row >= currentMyDevices.size()) return;
        // WHY: the master, not the report - a refresh that failed also leaves no report, and with a master present
        // that is no reason to send the user to first setup (review, 2026-10-11).
        if (masterEmpty) {
            if (HudConfirmDialog.confirm(this, getText("bindings.aliasDesigner.editor.heading"),
                    getText("bindings.aliasDesigner.editor.beforeSetUp.message"),
                    getText("bindings.aliasDesigner.firstSetup.button"),
                    getText("button.back"))) {
                openFirstSetup();
            }
            return;
        }
        MyDevice device = currentMyDevices.get(row);
        Map<String, InstallationMarkers.Marker> markers =
                row < currentMarkers.perDevice().size() ? currentMarkers.perDevice().get(row) : Map.of();
        List<DeviceEditorDialog.StripLine> strip = currentMarkers.installs().stream()
                .map(install -> new DeviceEditorDialog.StripLine(installLabels.getOrDefault(install, install),
                        markers.get(install), installPaths.getOrDefault(install, "")))
                .toList();
        new Thread(() -> {
            DeviceEditorDialog.Opening opening = null;
            String failure = null;
            try {
                opening = openingFor(device, strip);
            } catch (RuntimeException e) {
                // WHY: broad on purpose - a thread boundary, and the user pressed the gear and must be told.
                log.warn("The Device Editor could not read {}", device.label(), e);
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            DeviceEditorDialog.Opening loaded = opening;
            String loadedFailure = failure;
            SwingUtilities.invokeLater(() -> {
                if (loaded == null) {
                    HudConfirmDialog.info(this, getText("bindings.aliasDesigner.editor.heading"),
                            getText("bindings.aliasDesigner.editor.loadFailed", loadedFailure),
                            getText("bindings.aliasDesigner.firstSetup.close"));
                    return;
                }
                new DeviceEditorDialog(this, loaded, this::initData).showDialog();
            });
        }, "BindForge-DeviceEditorRead").start();
    }

    /**
     * What the Device Editor opens with. The name is the master's, or Frontier's for a built-in the master does not
     * hold; with neither, the device has no name yet. The labels are the draft's when it holds the device - SAVE
     * writes there - and the master's otherwise. The inputs are the controller's when it is attached, read by which
     * axis they are, and the ones its labels name when it is not.
     */
    private static DeviceEditorDialog.Opening openingFor(MyDevice device, List<DeviceEditorDialog.StripLine> strip) {
        FrontierStockDevices stock = FrontierStockDevices.getInstance();
        String name = device.alias() != null
                ? device.alias()
                : device.builtIn() ? stock.covering(device.vid(), device.pid()).map(DeviceEntry::name).orElse(null)
                : null;
        Map<String, String> saved = name == null ? Map.of() : storedLabels(name);

        Device live = device.attached() ? attachedController(device.hardware()) : null;
        DeviceDescription description = live == null
                ? null
                : DeviceService.getInstance().description(live.id()).orElse(DeviceDescription.axesUnknown(0));
        DeviceEditorInputs inputs = live == null
                ? DeviceEditorInputs.ofSavedLabels(saved)
                : DeviceEditorInputs.ofController(live.buttonCount(), live.axisCount(), description);
        return new DeviceEditorDialog.Opening(device, name, live == null ? null : live.id(), description, inputs,
                saved, name != null && stock.gameDrawsIt(name), strip, middleFor(device, name, stock));
    }

    /**
     * What the editor's middle button is: read from the master, the draft and {@code .binds} as it opens. A
     * {@code .binds} that cannot be read - a folder that is not there included - refuses CLEAR rather than offering it
     * on a device it might name, since the game would reject the whole preset. Labels can still be edited.
     */
    private static DeviceEditorMiddle middleFor(MyDevice device, String name, FrontierStockDevices stock) {
        boolean builtIn = name == null ? device.builtIn() : stock.ships(name);
        if (name == null) return DeviceEditorMiddle.of(null, builtIn, false, false, false, 0);
        BindForgeDeviceDraftManager draft = BindForgeDeviceDraftManager.getInstance();
        boolean started = draft.isStarted();
        OptionalInt bindings;
        try {
            bindings = OptionalInt.of(DeviceEditorMiddle.bindingsNaming(name,
                    BindsDeviceReferences.referenceCounts(PlayerSession.getInstance().getBindingsDir())));
        } catch (IOException e) {
            log.warn("Could not read which devices the bindings name, so Clear is refused for {}: {}", name,
                    e.getMessage());
            bindings = OptionalInt.empty();
        }
        return DeviceEditorMiddle.of(name, builtIn,
                BindForgeDeviceMasterManager.getInstance().findByName(name) != null,
                started, started && draft.findByName(name) != null, bindings);
    }

    private static Map<String, String> storedLabels(String name) {
        BindForgeDeviceDraftManager draft = BindForgeDeviceDraftManager.getInstance();
        if (draft.isStarted()) {
            DraftRow row = draft.findByName(name);
            if (row != null) return draft.labelsOf(row.id());
        }
        BindForgeDeviceMasterManager master = BindForgeDeviceMasterManager.getInstance();
        DeviceRow row = master.findByName(name);
        return row == null ? Map.of() : master.labelsOf(row.id());
    }

    /** The attached controller with this VID/PID - the first, since two identical units are out of scope. */
    private static Device attachedController(HardwareId hardware) {
        for (Device device : DeviceService.getInstance().getConnectedDevices()) {
            Optional<DeviceIdentity> identity = DeviceIdentities.of(device);
            if (identity.isPresent() && identity.get().vid().equals(hardware.vid())
                    && identity.get().pid().equals(hardware.pid())) {
                return device;
            }
        }
        return null;
    }

    /**
     * Frontier's entries, one row each in file order: the element's own pair, how many more it covers, and whether a
     * controller plugged in now resolves to it. Read-only - there is nothing on this view to act on.
     */
    private void showBuiltIn(List<BuiltInDevice> devices) {
        currentBuiltIn = devices;
        builtInModel.setRowCount(0);
        for (BuiltInDevice device : devices) {
            builtInModel.addRow(new Object[]{
                    device.name(),
                    device.primary() == null ? "" : pairText(device.primary()),
                    device.alternatives().isEmpty()
                            ? ""
                            : getText("bindings.aliasDesigner.builtIn.alsoCovers",
                            String.valueOf(device.alternatives().size())),
                    device.attached() ? getText("bindings.aliasDesigner.status.attached") : ""
            });
        }
        // WHY: as on My Devices - a list with nothing attached because nothing is looking must say so.
        if (!deviceServiceRunning) {
            builtInModel.addRow(new Object[]{getText("bindings.aliasDesigner.deviceService.stopped")});
        }
        HudTable.fitColumnsToContent(builtInTable, MAX_COLUMN_WIDTH);
    }

    private static String pairText(HardwareId pair) {
        return pair.vid() + ":" + pair.pid();
    }

    private static String[] builtInColumnNames() {
        return BUILT_IN_COLUMN_KEYS.stream().map(key -> getText(key)).toArray(String[]::new);
    }

    /**
     * What a marker says. A difference is a word as well as a colour, so the state never rests on colour alone
     * (Alan, 2026-10-08).
     */
    static String markerText(InstallationMarkers.Marker marker) {
        if (marker == null) return "";
        return getText(switch (marker.state()) {
            case MATCHES -> "bindings.aliasDesigner.marker.matches";
            // WHY: its own key, not the Alias column's. That one means the master has no record; this one means the
            // installation holds no entry. The same words today, but not the same fact.
            case NOT_ADDED -> "bindings.aliasDesigner.marker.notAdded";
            case DIFFERS -> "bindings.aliasDesigner.marker.differs";
            case NOT_FOUND -> "bindings.aliasDesigner.marker.notFound";
        });
    }

    /**
     * The master's name for this device, or why there is not one.
     * <p>
     * A built-in is not <em>not added</em>: Frontier's entry already names it, so there is nothing for
     * BindForge to create, and saying "not added" would invite the user to add what the game already has.
     */
    private String aliasCell(MyDevice device) {
        if (device.alias() != null) return device.alias();
        if (device.builtIn()) return getText("bindings.aliasDesigner.alias.builtIn");
        return getText("bindings.aliasDesigner.alias.notAdded");
    }

    private void showFindings(List<DeviceDivergence.Finding> findings) {
        currentFindings = findings;
        tableModel.setRowCount(0);
        for (DeviceDivergence.Finding finding : findings) {
            tableModel.addRow(new Object[]{
                    getText(severityKey(finding.severity())),
                    finding.deviceName(),
                    getText(issueKey(finding.issue())),
                    finding.installs().stream()
                            .map(key -> installLabels.getOrDefault(key, key))
                            .sorted()
                            .collect(Collectors.joining(", "))
            });
        }
        HudTable.fitColumnsToContent(table, MAX_COLUMN_WIDTH);
    }

    private String severityKey(DeviceDivergence.Severity severity) {
        return switch (severity) {
            case RED -> "bindings.aliasDesigner.severity.red";
            case YELLOW -> "bindings.aliasDesigner.severity.yellow";
            case GREEN -> "bindings.aliasDesigner.severity.green";
        };
    }

    private String issueKey(DeviceDivergence.Issue issue) {
        return switch (issue) {
            case ENTRY_MISSING -> "bindings.aliasDesigner.issue.entryMissing";
            case ENTRY_HARDWARE_DIFFERS -> "bindings.aliasDesigner.issue.hardwareDiffers";
            case ORPHANED_BUTTON_MAP -> "bindings.aliasDesigner.issue.orphanedButtonMap";
            case ENTRY_OUT_OF_PLACE -> "bindings.aliasDesigner.issue.outOfPlace";
            case ENTRY_NOT_IN_MASTER -> "bindings.aliasDesigner.issue.notInMaster";
            case LABELS_DIFFER -> "bindings.aliasDesigner.issue.labelsDiffer";
            case BUTTON_MAP_MISSING -> "bindings.aliasDesigner.issue.buttonMapMissing";
            case BUTTON_MAP_UNREADABLE -> "bindings.aliasDesigner.issue.buttonMapUnreadable";
            case FILE_RESET -> "bindings.aliasDesigner.issue.fileReset";
            case FILE_UNREADABLE -> "bindings.aliasDesigner.issue.fileUnreadable";
        };
    }

    private String[] columnNames() {
        return new String[]{
                getText("bindings.aliasDesigner.column.severity"),
                getText("bindings.aliasDesigner.column.device"),
                getText("bindings.aliasDesigner.column.issue"),
                getText("bindings.aliasDesigner.column.installations")
        };
    }

    /**
     * Colours the whole row by its severity, reading it from the findings rather than from the cell text.
     * <p>
     * Extends the HUD's own cell renderer rather than replacing it, so the row keeps its font, padding,
     * hover and selection behaviour and only the foreground changes. A selected row keeps the selection
     * colour: severity is worth less than knowing which row you are on.
     */
    private final class SeverityRenderer extends HudTable.CellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            Component cell =
                    super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            if (!selected && row < currentFindings.size()) {
                cell.setForeground(colourOf(currentFindings.get(row).severity()));
            }
            return cell;
        }
    }

    /**
     * Colours each Installations cell by its marker, and leaves the four device columns as the HUD draws them. A
     * marker with nothing to judge keeps the plain text colour. A selected row keeps the selection colour, as in
     * the divergence list.
     */
    private final class MarkerRenderer extends HudTable.CellRenderer {
        private static final int FIRST_MARKER_COLUMN = DEVICE_COLUMN_KEYS.size();

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            Component cell =
                    super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            int modelColumn = table.convertColumnIndexToModel(column);
            int marker = modelColumn - FIRST_MARKER_COLUMN;
            if (!selected && marker >= 0 && marker < currentMarkers.installs().size()
                    && row < currentMarkers.perDevice().size()) {
                InstallationMarkers.Marker found =
                        currentMarkers.perDevice().get(row).get(currentMarkers.installs().get(marker));
                if (found != null && found.severity() != null) cell.setForeground(colourOf(found.severity()));
            }
            return cell;
        }
    }

    /**
     * Lists every alternative pair as the <em>Also covers</em> cell's tooltip, a few to a line, and draws every cell
     * as the HUD otherwise does. The row shows the count; the pairs are there for whoever asks.
     */
    private final class AlternativesRenderer extends HudTable.CellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            Component cell =
                    super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            String tooltip = null;
            if (table.convertColumnIndexToModel(column) == ALSO_COVERS_COLUMN && row < currentBuiltIn.size()) {
                tooltip = tooltipOf(currentBuiltIn.get(row).alternatives());
            }
            setToolTipText(tooltip);
            return cell;
        }

        private static String tooltipOf(List<HardwareId> pairs) {
            if (pairs.isEmpty()) return null;
            StringBuilder html = new StringBuilder("<html>");
            for (int i = 0; i < pairs.size(); i++) {
                if (i > 0) html.append(i % PAIRS_PER_TOOLTIP_LINE == 0 ? "<br>" : ", ");
                html.append(pairText(pairs.get(i)));
            }
            return html.append("</html>").toString();
        }
    }

    static Color colourOf(DeviceDivergence.Severity severity) {
        return switch (severity) {
            case RED -> HudPalette.HUD_COLOR_ROLE_DANGER;
            case YELLOW -> HudPalette.HUD_COLOR_ROLE_WARNING;
            case GREEN -> HudPalette.HUD_COLOR_ROLE_SUCCESS;
        };
    }

    /** My Devices: read-only but for the gear, which is the last column. */
    private static final class MyDevicesTableModel extends DefaultTableModel {
        private MyDevicesTableModel(Object[] columnNames) {
            super(columnNames, 0);
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == getColumnCount() - 1;
        }
    }

    /**
     * A gear cell, copied from the fleet list's in {@code CommanderTabPanel} so the two lists open their editors the
     * same way (UI component map, Alias Designer). It becomes one shared HUD gear column when a third list takes it.
     */
    private static final class GearCell {
        final JPanel panel = new JPanel(new BorderLayout());
        final JButton gear = new JButton();
        private final ImageIcon gearBase =
                HudGlyphs.scaledIcon(AliasDesignerPanel.class, "/images/settings.png", HUD_ICON_TABLE);
        private ImageIcon gearOrange;
        private ImageIcon gearDark;

        GearCell() {
            gear.setOpaque(false);
            gear.setContentAreaFilled(false);
            gear.setBorderPainted(false);
            gear.setFocusPainted(false);
            gear.setHorizontalAlignment(SwingConstants.CENTER);
            gear.setToolTipText(getText("bindings.aliasDesigner.editor.gear"));
            panel.add(gear, BorderLayout.CENTER);
        }

        private ImageIcon gearIcon(boolean selected) {
            if (selected) {
                if (gearDark == null) {
                    gearDark = HudGlyphs.tintIcon(gearBase, HUD_ICON_TABLE, HUD_ICON_TABLE,
                            HudPalette.HUD_COLOR_ROLE_SELECTED_TEXT);
                }
                return gearDark;
            }
            if (gearOrange == null) {
                gearOrange = HudGlyphs.tintIcon(gearBase, HUD_ICON_TABLE, HUD_ICON_TABLE,
                        HudPalette.HUD_COLOR_ROLE_CONTROL_DECORATION);
            }
            return gearOrange;
        }

        /** @param device whether the row is a device - the "service stopped" row has no gear */
        JPanel apply(boolean selected, boolean device) {
            panel.setBackground(selected
                    ? HudPalette.HUD_COLOR_ROLE_PRIMARY_ACTION
                    : HudPalette.HUD_COLOR_ROLE_TABLE_CELL_BACKGROUND);
            panel.setOpaque(true);
            gear.setIcon(device ? gearIcon(selected) : null);
            gear.setEnabled(device);
            return panel;
        }
    }

    private final class GearRenderer implements TableCellRenderer {
        private final GearCell cell = new GearCell();

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            return cell.apply(selected, row < currentMyDevices.size());
        }
    }

    /** Opens the Device Editor on a single click, as the fleet list's gear opens a ship's settings. */
    private final class GearEditor extends AbstractCellEditor implements TableCellEditor {
        private final GearCell cell = new GearCell();
        private int editingRow = -1;

        GearEditor() {
            cell.gear.addActionListener(e -> {
                int row = editingRow;
                fireEditingStopped();
                openDeviceEditor(row);
            });
        }

        @Override
        public Object getCellEditorValue() {
            return "";
        }

        @Override
        public boolean isCellEditable(EventObject e) {
            return true;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row,
                                                     int column) {
            editingRow = row;
            return cell.apply(true, row < currentMyDevices.size());
        }
    }

    private static final class ReadOnlyTableModel extends DefaultTableModel {
        private ReadOnlyTableModel(Object[] columnNames, int rowCount) {
            super(columnNames, rowCount);
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    }
}
