package elite.intel.ui.screen.bindings;

import com.google.common.eventbus.Subscribe;
import elite.intel.bindforge.devicefiles.*;
import elite.intel.bindforge.devicefiles.DivergenceWaysOut.WayOut;
import elite.intel.bindforge.devices.MissingControllerWarning;
import elite.intel.bindforge.devices.MyDevice;
import elite.intel.bindforge.devices.Onboarding;
import elite.intel.bindforge.install.InstallationRegistry;
import elite.intel.db.dao.BindForgeInstallationsDao.InstallationRow;
import elite.intel.db.managers.BindForgeActivationManager;
import elite.intel.db.managers.BindForgeDeviceDraftManager;
import elite.intel.eventbus.UiBus;
import elite.intel.ui.dialog.HudConfirmDialog;
import elite.intel.ui.event.AppLogEvent;
import elite.intel.ui.event.DeviceFilesChangedEvent;
import elite.intel.ui.screen.bindings.AliasDesignerReader.OnboardingReading;
import elite.intel.ui.screen.bindings.AliasDesignerReader.Snapshot;
import elite.intel.util.OsDetector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static elite.intel.ui.i18n.MultiLingualTextProvider.getText;
import static elite.intel.util.StringUtls.messageOf;

/**
 * The Alias Designer screen's flows: the refresh, activation, first setup, APPLY and DISCARD, the ways out, onboarding,
 * and opening the Device Editor. It decides when each runs, runs the disk work off the EDT, asks and tells the user,
 * and hands what it read to {@link AliasDesignerPanel}, which only draws.
 * <p>
 * State is kept on the EDT, but for the three flags background threads touch, which are atomic.
 */
final class AliasDesignerController {

    private static final Logger log = LogManager.getLogger(AliasDesignerController.class);

    /**
     * What the screen shows before its first refresh has finished.
     */
    private static final Snapshot NOTHING_YET = new Snapshot(Map.of(), Map.of(), null, List.of(), List.of(),
            List.of(), AliasDesignerReader.Markers.NONE, false, List.of(), false, false, false);

    private final AliasDesignerPanel view;
    private final AliasDesignerReader reader;
    private final InstallationRegistry registry;

    private final RefreshCoalescer refreshes = new RefreshCoalescer();
    /**
     * Set when the screen is shown while this run's missing-controller warning is still to come, and taken by the next
     * refresh to start. A refresh already running when the screen appeared may have read the hardware before the
     * Device Service had listed it, so it is never the one that judges.
     */
    private final AtomicBoolean missingWarningWanted = new AtomicBoolean();
    /**
     * Activation is tried once per run - abandoned first setup is then SET UP's, and the next run's to offer again.
     */
    private final AtomicBoolean activationTried = new AtomicBoolean();
    /**
     * Set when activation is tried and on CHECK AGAIN, and taken by the first refresh after it that finds BindForge
     * activated and the hardware listed: onboarding opens then, for the controllers attached (Alan, 2026-10-14, A6b).
     */
    private final AtomicBoolean onboardingWanted = new AtomicBoolean();

    /**
     * The last refresh, with its unread answers filled from the one before. EDT only.
     */
    private Snapshot shown = NOTHING_YET;
    /**
     * Whether activation is running. SET UP waits for it, or the two could open first setup twice (review,
     * 2026-10-13). EDT only.
     */
    private boolean activationRunning;
    /**
     * Whether a write the user started - APPLY, DISCARD, a way out, onboarding - is running. EDT only.
     */
    private boolean actionRunning;

    AliasDesignerController(AliasDesignerPanel view, InstallationRegistry registry) {
        this.view = view;
        this.registry = registry;
        this.reader = new AliasDesignerReader(registry);
        UiBus.register(this);
    }

    void dispose() {
        UiBus.unregister(this);
    }

    /**
     * What the view draws from. EDT only.
     */
    Snapshot shown() {
        return shown;
    }

    /**
     * Whether a write is running, so the view draws the ways out disabled. EDT only.
     */
    boolean actionRunning() {
        return actionRunning;
    }

    /**
     * An installation's device files changed on disk - read again, so the check runs on what is there now. A refresh
     * already running may have read them before the change, so it is followed by another rather than trusted.
     */
    @Subscribe
    public void onDeviceFilesChanged(DeviceFilesChangedEvent event) {
        refresh();
    }

    /**
     * The screen became visible. The first time in a run, not at startup (Alan, 2026-10-11, A13): the refresh at
     * startup runs before the Device Service has looked at any hardware, so this asks for one of its own and the
     * missing-controller warning judges that one. Onboarding's offer, still waiting, asks the same.
     */
    void onShown() {
        boolean warning = MissingControllerWarning.getInstance().pending();
        if (!warning && !onboardingWanted.get()) return;
        if (warning) missingWarningWanted.set(true);
        refresh();
    }

    /**
     * CHECK AGAIN: reads everything again, and offers onboarding again. Backed out of, onboarding is spent for the
     * run, and a refresh alone changes nothing on screen - so CHECK AGAIN looked dead to a user who still had
     * controllers to name (Krondor, 2026-10-14). It says nothing when nobody needs a name.
     */
    void checkAgain() {
        onboardingWanted.set(true);
        refresh();
    }

    // ------------------------------------------------------------------ the refresh

    /**
     * Reads the installations and their device files off the EDT, same as
     * {@code BindingManagementPanel.performBackup()} - parsing every {@code .binds} and every installation's
     * {@code DeviceMappings.xml} would otherwise freeze the window, and an installation on a spun-down or
     * disconnected drive freezes it for as long as the filesystem takes to answer.
     * <p>
     * A refresh already in flight is left to finish rather than a second one being started beside it, and one more
     * follows it, however many requests came in meanwhile - see {@link RefreshCoalescer} for why none is dropped. A
     * ship-profile change mid-refresh therefore costs one extra refresh; nothing here depends on the ship.
     */
    void refresh() {
        if (!refreshes.tryStart()) return;
        new Thread(() -> {
            Snapshot read = reader.read(missingWarningWanted.getAndSet(false));
            SwingUtilities.invokeLater(() -> {
                shown = keepingLastAnswers(read);
                refreshButtons();
                view.render(shown);
                // WHY: only once the hardware is listed, and only while the user is looking. Before the listing every
                // controller reads absent, so nobody would need a name and the offer would be spent on nothing; a
                // refresh the user did not start would put the dialog over another screen.
                if (activated() && read.listed() && view.isShowing() && onboardingWanted.compareAndSet(true, false)) {
                    openOnboarding(null);
                }
                // WHY: something asked for a refresh while this one ran - the screen shown, a device file changed, an
                // action finished - and this one may have read what it changed too early.
                if (refreshes.finish()) refresh();
            });
        }, "BindForge-Divergence").start();
    }

    /**
     * A refresh that failed before reading activation or the hardware keeps the last answers for them.
     */
    private Snapshot keepingLastAnswers(Snapshot read) {
        return new Snapshot(read.labels(), read.paths(), read.report(), read.findings(), read.myDevices(),
                read.builtIn(), read.markers(),
                read.activated() != null ? read.activated() : shown.activated(),
                read.changes(),
                read.running() != null ? read.running() : shown.running(),
                read.listed(),
                read.applyWork());
    }

    private boolean activated() {
        return Boolean.TRUE.equals(shown.activated());
    }

    /**
     * SET UP until BindForge is activated, and not while activation runs, which may open first setup itself. APPLY
     * once activated and only when it has work ({@link ApplyWork}), DISCARD while the draft changes something - and
     * neither while a write runs.
     */
    private void refreshButtons() {
        view.refreshButtons(!activated() && !activationRunning, canApply(),
                !shown.changes().isEmpty() && !actionRunning, shown.changes());
    }

    private boolean canApply() {
        return activated() && shown.applyWork() && !actionRunning;
    }

    // ------------------------------------------------------------------ activation and first setup

    /**
     * Activation, the first time the BindForge tab is shown in a run (Alan, 2026-10-13, A1b): a "before BindForge"
     * backup, then the master filled with no prompt when the installations are identical, or first setup opened when
     * they are not. On the EDT; does nothing once tried in this run.
     */
    void activateOnFirstShowing() {
        if (!activationTried.compareAndSet(false, true)) return;
        onboardingWanted.set(true);
        activationRunning = true;
        refreshButtons();
        new Thread(() -> {
            Activation.Result result = null;
            Map<String, String> names = Map.of();
            try {
                // WHY: asked first, so every run after activation - nearly all of them - pays for no detection and
                // starts none to race the refresh's (review, 2026-10-13).
                if (!BindForgeActivationManager.getInstance().isActivated()) {
                    // WHY: a detection of its own, not currentWithStartupScan(). Activation reads the stored list, and
                    // on a fresh database nothing is stored until detection has run; the startup scan may still be
                    // running on the refresh thread, and the call that loses that race returns the list as it was -
                    // so the user would activate with an empty master. rescan() waits for a detection in progress.
                    List<InstallationRow> rows = registry.rescan();
                    names = AliasDesignerReader.labelsFor(rows);
                    result = Activation.stored().run();
                    reportActivationFailure(result.outcome(), result.reason());
                }
            } catch (RuntimeException e) {
                // WHY: broad on purpose - a thread boundary. Not activated, so SET UP stays offered.
                log.warn("BindForge could not be activated", e);
                reportActivationFailure(Activation.Outcome.FAILED, messageOf(e));
            }
            Activation.Result loadedResult = result;
            Map<String, String> loadedNames = names;
            SwingUtilities.invokeLater(() -> {
                activationRunning = false;
                refreshButtons();
                if (loadedResult != null && loadedResult.outcome() == Activation.Outcome.FIRST_SETUP) {
                    showFirstSetup(loadedResult.setup(), loadedResult.reading(),
                            AliasDesignerReports.problemOf(loadedResult), loadedNames);
                }
                refresh();
            });
        }, "BindForge-Activation").start();
    }

    /**
     * A failed activation is said in the application log, as a successful one's backup is.
     */
    private static void reportActivationFailure(Activation.Outcome outcome, String reason) {
        String line = AliasDesignerReports.activationFailure(outcome, reason);
        if (line != null) UiBus.publish(new AppLogEvent(line));
    }

    /**
     * SET UP: reads every installation for first setup off the EDT, then opens it. Nothing is written until the user
     * confirms the summary inside it.
     */
    void openFirstSetup() {
        // WHY: activation may open first setup itself any moment now - one dialog, not two (review, 2026-10-13).
        if (activationRunning) return;
        view.refreshButtons(false, canApply(), !shown.changes().isEmpty() && !actionRunning, shown.changes());
        Map<String, String> names = shown.labels();
        new Thread(() -> {
            FirstSetup setup = null;
            FirstSetup.Reading reading = null;
            String problem = null;
            try {
                // WHY: SET UP is a way into activation too, so it takes the "before BindForge" backup if activation
                // could not - otherwise finishing first setup would activate without it, for good (review, 2026-10-13).
                BeforeBindForgeBackup.stored().ensureTaken();
            } catch (IOException | RuntimeException e) {
                // WHY: broad on purpose - a thread boundary, and the user pressed a button and must be told.
                log.warn("First setup did not open: the backup before BindForge could not be taken", e);
                problem = getText("bindings.aliasDesigner.firstSetup.result.backupFailed", messageOf(e));
            }
            if (problem == null) {
                try {
                    setup = FirstSetup.stored();
                    reading = setup.read();
                } catch (IOException | RuntimeException e) {
                    // WHY: broad on purpose - a thread boundary, and the user pressed a button and must be told.
                    log.warn("First setup could not read the installations", e);
                    problem = getText("bindings.aliasDesigner.firstSetup.readFailed", messageOf(e));
                }
            }
            FirstSetup loadedSetup = setup;
            FirstSetup.Reading loadedReading = reading;
            String loadedProblem = problem;
            SwingUtilities.invokeLater(() -> {
                refreshButtons();
                showFirstSetup(loadedSetup, loadedReading, loadedProblem, names);
            });
        }, "BindForge-FirstSetupRead").start();
    }

    /**
     * First setup's dialog for a reading, naming the problem when there is one - or, with no reading, the problem
     * alone. On the EDT.
     *
     * @param problem the text to show the user, already worded; {@code null} when nothing went wrong
     */
    private void showFirstSetup(FirstSetup setup, FirstSetup.Reading reading, String problem,
                                Map<String, String> names) {
        // WHY: decided on the reading, not the message. An exception can carry no message, and keyed on that, a
        // failed read would open the dialog with nothing to show.
        if (setup == null || reading == null) {
            HudConfirmDialog.info(view, getText("bindings.aliasDesigner.firstSetup.title"), problem,
                    getText("bindings.aliasDesigner.firstSetup.close"));
            return;
        }
        new FirstSetupDialog(view, setup, reading, problem, names, this::refresh).showDialog();
    }

    // ------------------------------------------------------------------ APPLY and DISCARD

    /**
     * APPLY: the draft into the master, then the master and the pending removals out to every installation, reported
     * per installation. Asked first - it is the one button on the screen that writes a game file.
     */
    void apply() {
        String title = getText("bindings.aliasDesigner.apply.title");
        int installations = shown.labels().size();
        // WHY: said rather than asked. With no installation known there is nothing to write to, and "write to all 0
        // installations?" reads as a fault.
        if (installations == 0) {
            HudConfirmDialog.info(view, title, getText("bindings.aliasDesigner.apply.noInstallations",
                            getText("bindings.tab.bindingManagement"), getText("bindings.tab.gameInstallLocations")),
                    getText("bindings.aliasDesigner.firstSetup.close"));
            return;
        }
        String question = installations == 1
                ? getText("bindings.aliasDesigner.apply.confirm.one")
                : getText("bindings.aliasDesigner.apply.confirm", installations);
        // WHY: told, not fixed (Alan, 2026-10-14, A11). On Linux each installation has its own bindings folder, and
        // BindForge moves bindings in the one Elite-Intel uses - so a rename, or onboarding's hex move, can leave
        // another installation's preset naming a device by a name it no longer has.
        if (installations > 1 && OsDetector.getOs() == OsDetector.OS.LINUX) {
            question += "\n\n" + getText("bindings.aliasDesigner.apply.linuxNote");
        }
        if (!HudConfirmDialog.confirm(view, title, question,
                getText("bindings.aliasDesigner.apply.do"),
                getText("bindings.aliasDesigner.firstSetup.cancel"))) {
            return;
        }
        AliasDesignerReports reports = reports();
        runAndReport(title, () -> reports.applyText(new DeviceApply().apply()));
    }

    /**
     * DISCARD: the whole draft thrown away, after naming what it holds. No installation is touched.
     */
    void discard() {
        List<String> changes = shown.changes();
        if (changes.isEmpty()) return;
        if (!HudConfirmDialog.confirm(view, getText("bindings.aliasDesigner.discard.title"),
                getText("bindings.aliasDesigner.discard.confirm", String.join(", ", changes)),
                getText("bindings.aliasDesigner.discard.do"),
                getText("bindings.aliasDesigner.firstSetup.cancel"))) {
            return;
        }
        runAndReport(getText("bindings.aliasDesigner.discard.title"), () -> {
            BindForgeDeviceDraftManager.getInstance().discard();
            return null;
        });
    }

    // ------------------------------------------------------------------ the ways out

    /**
     * Revert: pushes the master to each installation the row names. That is the whole installation, not the row, so
     * the confirm says how many rows it reaches.
     */
    void revert(WayOut way) {
        Set<String> keys = new LinkedHashSet<>();
        way.installIds().forEach(id -> keys.add(DeviceDivergence.installKey(id)));
        long rows = shown.findings().stream()
                .filter(finding -> finding.installs().stream().anyMatch(keys::contains))
                .count();
        AliasDesignerReports reports = reports();
        if (!HudConfirmDialog.confirm(view, getText("bindings.aliasDesigner.wayOut.title"),
                getText("bindings.aliasDesigner.wayOut.revert.confirm", reports.namesOf(way.installIds()), rows),
                getText("bindings.aliasDesigner.wayOut.revert.do"),
                getText("bindings.aliasDesigner.firstSetup.cancel"))) {
            return;
        }
        runWayOut(() -> {
            DeviceFilesPush push = new DeviceFilesPush();
            List<DeviceFilesPush.InstallationResult> results = new ArrayList<>();
            for (long installId : way.installIds()) results.addAll(push.push(installId).installations());
            return reports.revertText(new DeviceFilesPush.Report(results));
        });
    }

    /**
     * Takes the row's device, as one installation holds it, into the master - and nothing else of that installation's.
     */
    void adopt(WayOut way, DeviceDivergence.Finding finding) {
        long installId = way.installIds().getFirst();
        AliasDesignerReports reports = reports();
        String installName = reports.namesOf(way.installIds());
        if (!HudConfirmDialog.confirm(view, getText("bindings.aliasDesigner.wayOut.title"),
                getText("bindings.aliasDesigner.wayOut.adopt.confirm", finding.deviceName(), installName,
                        String.valueOf(way.hardware())),
                getText("bindings.aliasDesigner.wayOut.adopt.do"),
                getText("bindings.aliasDesigner.firstSetup.cancel"))) {
            return;
        }
        runWayOut(() -> reports.adoptText(new DeviceFilesAdopt().adopt(installId, finding.deviceName()),
                getText("bindings.aliasDesigner.wayOut.adopt.done", finding.deviceName(), installName)));
    }

    /**
     * The label merge with the master as one side. The labels are read off the EDT, the merge view asks about the
     * inputs that collide - none, when the sides only add to each other - and the answer goes into the master.
     */
    void reMerge(WayOut way, DeviceDivergence.Finding finding) {
        String device = finding.deviceName();
        String title = getText("bindings.aliasDesigner.wayOut.title");
        AliasDesignerReports reports = reports();
        Map<String, String> installLabels = shown.labels();
        offEdt(title, () -> LabelReMerge.stored(device, way.installIds()), merge -> {
            Map<String, String> answers = Map.of();
            if (!merge.collisions().isEmpty()) {
                Map<String, String> sideNames = new LinkedHashMap<>(installLabels);
                sideNames.put(LabelReMerge.MASTER, getText("bindings.aliasDesigner.wayOut.masterSide"));
                Optional<Map<String, String>> chosen =
                        new LabelMergeDialog(view, device, merge, sideNames, Map.of()).showDialog();
                if (chosen.isEmpty()) return;
                answers = chosen.get();
            }
            List<String> unanswered = merge.unanswered(answers);
            if (!unanswered.isEmpty()) {
                HudConfirmDialog.info(view, title,
                        getText("bindings.aliasDesigner.wayOut.remerge.unanswered", unanswered.size()),
                        getText("bindings.aliasDesigner.firstSetup.close"));
                return;
            }
            Map<String, String> merged = merge.merged(answers);
            if (!HudConfirmDialog.confirm(view, title,
                    getText("bindings.aliasDesigner.wayOut.remerge.confirm", device),
                    getText("bindings.aliasDesigner.wayOut.remerge.do"),
                    getText("bindings.aliasDesigner.firstSetup.cancel"))) {
                return;
            }
            runWayOut(() -> reports.adoptText(new DeviceFilesAdopt().adoptLabels(device, merged),
                    getText("bindings.aliasDesigner.wayOut.remerge.done", device)));
        });
    }

    private void runWayOut(Callable<String> work) {
        runAndReport(getText("bindings.aliasDesigner.wayOut.title"), work);
    }

    // ------------------------------------------------------------------ onboarding and the Device Editor

    /**
     * Onboarding: reads who needs a name off the EDT, opens the dialog, and writes what the user chose when they
     * finish (Alan, 2026-10-14, A6b).
     *
     * @param only the controller to name, from the gear - or {@code null} for every controller that needs one, which
     *             says nothing when nobody does
     */
    void openOnboarding(MyDevice only) {
        String title = getText("bindings.aliasDesigner.onboarding.title");
        AliasDesignerReports reports = reports();
        offEdt(title, () -> reader.readOnboarding(only), (OnboardingReading reading) -> {
            if (reading.problem() != null) {
                HudConfirmDialog.info(view, title, reading.problem(),
                        getText("bindings.aliasDesigner.firstSetup.close"));
                return;
            }
            if (reading.pages().isEmpty()) {
                if (only != null) {
                    HudConfirmDialog.info(view, title, getText("bindings.aliasDesigner.onboarding.nobody"),
                            getText("bindings.aliasDesigner.firstSetup.close"));
                }
                return;
            }
            new OnboardingDialog(view, reading.pages(), reading.taken(),
                    names -> runAndReport(title, () -> reports.onboardText(nameAll(reading.pages(), names))))
                    .showDialog();
        });
    }

    private static DeviceOnboarding.Result nameAll(List<Onboarding.Page> pages, List<String> names) throws IOException {
        List<DeviceOnboarding.Naming> namings = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            Onboarding.Page page = pages.get(i);
            namings.add(new DeviceOnboarding.Naming(names.get(i), page.device().vid(), page.device().pid(),
                    page.labels(), page.bindings()));
        }
        return DeviceOnboarding.stored().name(namings);
    }

    /**
     * Opens the Device Editor for one My Devices row. Before activation there is no master for its edits to be saved
     * against - first setup throws any draft away - so it says SET UP comes first, and offers it (Alan, 2026-10-10).
     *
     * @param strip the install strip the view drew for the row
     */
    void openDeviceEditor(MyDevice device, List<DeviceEditorDialog.StripLine> strip) {
        // WHY: the stored fact, not the report - a refresh that failed also leaves no report, and once activated that is
        // no reason to send the user to first setup (review, 2026-10-11). Activated, not "the master has rows": a
        // user who owns nothing is activated with an empty master (Alan, 2026-10-13).
        if (!activated()) {
            if (HudConfirmDialog.confirm(view, getText("bindings.aliasDesigner.editor.heading"),
                    getText("bindings.aliasDesigner.editor.beforeSetUp.message"),
                    getText("bindings.aliasDesigner.firstSetup.button"),
                    getText("button.back"))) {
                openFirstSetup();
            }
            return;
        }
        new Thread(() -> {
            DeviceEditorDialog.Opening opening = null;
            String failure = null;
            try {
                opening = reader.openingFor(device, strip);
            } catch (RuntimeException e) {
                // WHY: broad on purpose - a thread boundary, and the user pressed the gear and must be told.
                log.warn("The Device Editor could not read {}", device.label(), e);
                failure = messageOf(e);
            }
            DeviceEditorDialog.Opening loaded = opening;
            String loadedFailure = failure;
            SwingUtilities.invokeLater(() -> {
                if (loaded == null) {
                    HudConfirmDialog.info(view, getText("bindings.aliasDesigner.editor.heading"),
                            getText("bindings.aliasDesigner.editor.loadFailed", loadedFailure),
                            getText("bindings.aliasDesigner.firstSetup.close"));
                    return;
                }
                new DeviceEditorDialog(view, loaded, this::refresh, () -> openOnboarding(device)).showDialog();
            });
        }, "BindForge-DeviceEditorRead").start();
    }

    // ------------------------------------------------------------------ running a write

    /**
     * The words for a report, with the installations named as they are on screen now.
     */
    private AliasDesignerReports reports() {
        return new AliasDesignerReports(shown.labels());
    }

    /**
     * Runs a write off the EDT - a way out, APPLY, DISCARD or onboarding's names - says what happened when there is
     * something to say, and reads everything again.
     */
    private void runAndReport(String title, Callable<String> work) {
        offEdt(title, work, message -> {
            if (message != null) {
                HudConfirmDialog.info(view, title, message, getText("bindings.aliasDesigner.firstSetup.close"));
            }
            refresh();
        });
    }

    /**
     * Reads or writes game files and the master off the EDT, with the buttons disabled meanwhile, then hands the
     * result back on it. A failure is shown under {@code title}, not only logged - the user pressed a button and must
     * be told - and the screen is read again, since a write may have got part of the way.
     */
    private <T> void offEdt(String title, Callable<T> work, Consumer<T> onEdt) {
        // WHY: APPLY and DISCARD wait too - a revert and an Apply writing the same installation at once would each
        // keep an Edit History copy of a file the other was replacing.
        actionRunning = true;
        view.disableWaysOut();
        refreshButtons();
        new Thread(() -> {
            T result = null;
            String failure = null;
            try {
                result = work.call();
            } catch (Exception e) {
                // WHY: broad on purpose - a thread boundary, and anything escaping it would leave the buttons
                // disabled with no word of why.
                log.warn("{} failed", title, e);
                failure = messageOf(e);
            }
            T loaded = result;
            String loadedFailure = failure;
            SwingUtilities.invokeLater(() -> {
                actionRunning = false;
                refreshButtons();
                view.showWaysOut();
                if (loadedFailure != null) {
                    HudConfirmDialog.info(view, title, getText("bindings.aliasDesigner.wayOut.failed", loadedFailure),
                            getText("bindings.aliasDesigner.firstSetup.close"));
                    refresh();
                    return;
                }
                onEdt.accept(loaded);
            });
        }, "BindForge-ScreenWrite").start();
    }
}
