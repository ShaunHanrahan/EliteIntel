# Alias Designer

**Naming controllers, and keeping every installation's device files identical.** The tab exists and shows real
data; most of what the spec describes is still ahead.

## Spec

- [alias-designer.md](../02-features/bindforge/alias-designer.md) — **read end to end at the start of every
  session.** It is the screen's definition, and building from the slice's name instead of the page is how this
  tab went wrong the first time — see `CLAUDE.md`.
- [device-provenance.md](../03-data-models/device-provenance.md) — the three tables, and why there are three.
- [EliteDangerous-DeviceMappings-ButtonMap.md](../02-features/bindforge/domain-knowledge/EliteDangerous-DeviceMappings-ButtonMap.md)
  — the file formats, and what has been measured on a live game.
- [ui-component-map.md — Alias Designer](../02-features/bindforge/ui-component-map.md#alias-designer).

## Built

| What | Where |
|---|---|
| The tab | `ui.screen.bindings.AliasDesignerPanel` — the device list (My Devices and Built-in Devices tabs) above the divergence list, in `BindingsTabPanel` |
| My Devices — all five columns | `bindforge.devices.MyDeviceList` (the union, a pure function), `MyDevice`. Built-ins show `BUILT-IN` in the Alias column |
| Installations markers (A4) | `bindforge.devices.InstallationMarkers` (pure: one marker per installation, coloured from the divergence findings); one column per installation in `AliasDesignerPanel`, shown once the master exists |
| Built-in Devices (A5) | `bindforge.devices.BuiltInDeviceList` (pure: Frontier's elements in file order, primary pair, alternatives, attached), `BuiltInDevice`; the second `COMPACT` tab of the device list in `AliasDesignerPanel`, with the capture date `FrontierStockDevices.CAPTURED` beneath it |
| Device names | `bindforge.devices.DeviceNames` — the default-name rule, alias validation (`Verdict`, `Problem`), `sameName`, and `needingName` (who onboarding asks). Pure; the caller passes the names already taken |
| Hardware VID/PID | `bindforge.devices.DeviceIdentities` — from SDL's GUID; confirmed on four controllers, real GUIDs in its test |
| Frontier's shipped list | `bindforge.devicefiles.FrontierStockDevices`, resource `bindforge/FrontierStock-DeviceMappings.xml` |
| First setup (A1) | `bindforge.devicefiles.FirstSetup` (read, then backup → master → push), `FirstSetupPlan` (the four cases and the answers, pure), `FirstSetupReader`, `DeviceFilesSnapshot` (the labelled backup), `FrontierStockButtonMaps` (resources `FrontierStock-VPC*.buttonMap.xml`); `BindForgeDeviceDraftManager.establish`; `ui.screen.bindings.FirstSetupDialog`, opened by **SET UP** on `AliasDesignerPanel` while the master is empty |
| `.buttonMap` label merge (A2) | `bindforge.devicefiles.LabelMerge` (pure: the disagreeing inputs, answers per input, the whole-file route, the merged labels; sides keyed by name); `ui.screen.bindings.LabelMergeDialog`, opened by **Compare N inputs** on a *Button labels disagree* row in `FirstSetupDialog` |
| Startup `.buttonMap` (A8) | `bindforge.devicefiles.ButtonMapGeneration` (per installation, create-only), `ButtonMapLabels` (the generated labels: buttons, the axes the controller has — all eight when unknown — and four per hat); `bindforge.devices.ButtonMapStartup` runs it on every connect, started as `BUTTON_MAP_STARTUP` in `AppController` |
| Device Editor (A9) | `ui.screen.bindings.DeviceEditorDialog`, opened by the gear column on My Devices in `AliasDesignerPanel` (`GearCell`, copied from `CommanderTabPanel`); `bindforge.devices.DeviceEditorInputs` (boxes, defaults, what SAVE writes) and `DeviceEditorLights` (highlighting), both pure; `BindForgeDeviceDraftManager.saveLabels`; `FrontierStockDevices.gameDrawsIt`; `devices.events.DeviceHatEvent`, polled in `DeviceService`; the footer's middle place and unsaved hint in `HudModalSpec` |
| Actions (A10) | `bindforge.devices.DeviceEditorMiddle` (CLEAR / RESET LABELS / their undo, and when refused — pure), wired in `DeviceEditorDialog`; `BindForgeDeviceDraftManager.removeByName`, `restoreFromMaster`, `stored`; APPLY and DISCARD in `AliasDesignerPanel`'s footer, calling `DeviceApply` (removals held back while `.binds` names them; cleared once every installation took them); `DeviceFilesPush.push(Set)` and `DeviceMappingsMerge.remove`; `FrontierStockButtonMaps.shippedContent`; `BindsDeviceReferences.referenceCounts`; `bindforge.devicefiles.DraftChanges` (does the draft change anything — pure) |
| Activation (A1b) | `bindforge.devicefiles.Activation` (backup once, then identical → master filled with no prompt, otherwise first setup; `identical` is the push's own test); `DeviceFilesSnapshot.take(label, targets, bindingsFolders)`; migration `12005__bindforge_activation.sql`, `BindForgeActivationDao`, `BindForgeActivationManager`; `establish` activates in its transaction; `AliasDesignerPanel.activateOnFirstShowing()`, called by `BindingsTabPanel`'s first showing; `speech.bindforgeBackedUp` in the nine bundles |
| Missing-controller warning (A13) | `bindforge.devices.MissingControllers` (who is named, pure), `MissingControllerWarning` (once per process, spoken and logged); triggered by `AliasDesignerPanel`'s first showing, which starts its own refresh (`missingWarningWanted`); `speech.missingController(s)` in the nine `gui*.properties` |
| Which axes and hats (A8b) | `elite.intel.devices.model.DeviceDescription` (axes by game slot, `axesWithoutSlot`, SDL's numbering via `axisAt`, hat count) and `GameAxis`; `devices.hid.DeviceDescriber` reads it at connect — `WindowsHidUsages` (`hid.dll`), `LinuxHidUsages` + `HidReportDescriptor` (`/sys`), `AxisSlots` (pure rules); `DeviceService.description(deviceId)` |
| Divergence list (A3) | `DeviceDivergence.againstMaster` (pure; each installation against the master, from `DeviceFilesCheck`), `DeviceDivergenceScanner.scanAgainstMaster` (adds orphans and `.binds`), `BindsDeviceReferences`, `ButtonMapAudit`. Before first setup, `DeviceDivergence.rank` still compares installations with each other |
| Ways out per row (A3b) | `bindforge.devicefiles.DivergenceWaysOut` (which ways a row gets, pure), `DeviceFilesAdopt.adopt(installId, deviceName)` (one device) and `adoptLabels` (the re-merge), `LabelReMerge` (master plus installations as `LabelMerge` sides); buttons beneath the list in `AliasDesignerPanel`, each confirmed. Revert is `DeviceFilesPush.push(installId)` |
| What each installation holds | `InstallationDeviceScanner`, run on every refresh; rows in `bindforge_device_installs` |
| Game Install Locations screen | `ui.screen.bindings.GameInstallLocationsPanel` — in Binding Management, where the spec puts it in File Manager |
| Master store | see [core.md](core.md) |

**The divergence list compares each installation with the master** once first setup has filled it, and the
installations with each other before then. Once the master exists, each row offers its ways out.

## Settled — and where it is recorded

- **The list is the union of live hardware and file entries.** `DeviceMappings.xml` supplies names, never
  existence. [Spec, My Devices](../02-features/bindforge/alias-designer.md#my-devices)
- **The Installations markers wait for first setup.** `M`, *not added* and the divergence colour are all defined
  against a master record, so before first setup there are no Installations columns. [Spec, the Installations markers](../02-features/bindforge/alias-designer.md#the-installations-markers--2026-10-08-alan)
- **"Device service is not running" is a row in the table**, not just a log line — an empty list because nothing
  is plugged in and one because nothing is looking are different states.
- **Duplicate-VID/PID handling is out of scope.** [Spec, Deferred](../02-features/bindforge/alias-designer.md#deferred)
- **DirectInput instance GUIDs are out of scope** — the game keys on VID/PID, and no file could carry more.
- **The game ignores `DeviceMappings.xml` formatting; it does not ignore missing children.**
  [Domain doc §1.2c](../02-features/bindforge/domain-knowledge/EliteDangerous-DeviceMappings-ButtonMap.md#12c-the-game-parses-it-as-xml-and-does-not-reformat-it--measured-2026-10-03)

## Known issues — fix when a slice passes them

- **The gear cell is a copy of `CommanderTabPanel`'s** (`AliasDesignerPanel.GearCell`, with its renderer and editor).
  The review of A9/A10 (2026-10-11, finding 4) asks for one shared HUD gear column in `ui.widget` now, not at a third
  list. **Deferred by Alan:** sharing it edits `CommanderTabPanel`, V1.1's fleet screen — settle with Krondor first.

- **First setup has no undo in the app** (found 2026-10-08, Alan). Its labelled backup
  (`playerbackups\<time>_first-setup\<id>-<storefront>\`) can only be copied back by hand: the Player Backups
  restore handles `.binds` presets only, and Edit History has no browser until File Manager. Copying the files back
  is not enough either — the master stays filled, so the installations then show as diverging, **SET UP** is not
  offered, and nothing in the app empties the master. An undo needs both halves: the files restored and the master
  (and any draft) emptied.
- **The first-setup backup shows in the Player Backups list.** `DeviceFilesSnapshot` writes beside
  `PlayerBackupService`'s folders, so `listBackups()` lists it, with installation subfolders as its "files". File
  Manager's restore work should recognise it (or Krondor moves it) — `bindforge.io`.
- **The first-setup backup ignores a chosen backup destination** — the resolver is private to `PlayerBackupService`.
- **`ButtonInputMapper.axisToBindsToken` throws for axis index 6 and above.** A8 went around it (`ButtonMapLabels`
  labels all eight tokens); the class is shared with V1.1, so the fix — add U and V — is Krondor's, and the
  Bind Editor's capture needs it.
- **SDL numbers axes without gaps**, so anything mapping an SDL axis index to a game token by position is wrong
  for a stick reporting X, Y and RZ. *Answered by A8b (2026-10-10):* use `DeviceService.description(id).axisAt(i)`,
  which is empty when the numbering could not be matched — never `ButtonInputMapper.axisToBindsToken`. The
  capture dialog must do the same. [Spec](../02-features/bindforge/alias-designer.md#which-axes-and-hats-a-controller-has--2026-10-10-alan)
- **Still open from the first code review:**
  - **#7 — paths are stored as typed**, so `E:\SteamLibrary\…` and `e:\steamlibrary\…\` become two rows for one
    installation. Discussed 2026-09-30 and leaning towards deleting **relocate** altogether, since Remove then
    Add covers every case and nothing yet depends on an installation keeping its id. **Undecided — ask Alan.**
  - **#12 — `WindowsGameInstallationProvider` reads `InstallPath` under `HKCU\Software\Valve\Steam`**, where the
    value is named `SteamPath`. The two `HKLM` lookups work, so detection works; the `HKCU` fallback never finds
    anything.

## Boundaries

- **Owns** `bindforge.devices`, `bindforge.devicefiles`, `bindforge.install` and `ui.screen.bindings.AliasDesignerPanel`.
- **Writes through Core, never around it.** No slice here writes a game file itself.
- **The Game Install Locations screen belongs to File Manager** — fix bugs in it here if a slice needs to, but its
  move into File Manager is that section's work.

## Slices

| # | Slice | Needs | Status |
|---|---|---|---|
| A1 | **First setup** — turn what the installations hold into the master: per element, the four cases, a name conflict routed into the rename, a labelled backup and Edit History before writing | Core C1, C2 (both done) | **done 2026-10-07** |
| A1b | **Activation** — the first opening of the BindForge tab: a never-pruned "before BindForge" backup of every installation's device files and every `.binds`/`StartPreset` in the bindings folder; the master filled with no prompt when the installations are identical (possibly empty), first setup opened when they differ; a stored "activated" fact (migration `12005`) replacing every "empty master means not set up" test — gear, APPLY, **SET UP**, `DeviceFilesCheck`; told once, spoken and logged. [Spec](../02-features/bindforge/alias-designer.md#activation--2026-10-13-alan) | A1 (done) | **done 2026-10-13** |
| A2 | **`.buttonMap` label merge** — one row per input that disagrees, nothing else | A1 (done) | **done 2026-10-08** |
| A3 | **Divergence against the master** — replace installation-against-installation | A1 (done) | **done 2026-10-08** |
| A3b | **The ways out, per row** — adopt (`DeviceFilesAdopt`), revert (`DeviceFilesPush.push(installId)`), and the label re-merge with the master as one side of `LabelMergeDialog`. Confirms first; backups before any game-file write | A3 (done) | **done 2026-10-08** |
| A4 | **Installations markers** — `M`, *not added*, severity colour | A1 (done) | **done 2026-10-08** |
| A5 | **Built-in Devices tab** — Frontier's list, read-only | — | **done 2026-10-08** |
| A6 | **Onboarding: the rule** — the default-name rule and alias validation | Core C1 (done) | **done 2026-10-06** |
| A6b | **Onboarding: the dialog and its writes** — name the controllers `needingName` returns, then write the entry and a generated `.buttonMap` straight away. A controller `.binds` names by hex waits for Apply, with a `.binds` rewrite | A6, A8, A1b (done) | **next** — sources approved 2026-10-13 (hand-off note); the `.binds` device rewrite from [the foundation branch](README.md#the-foundation-branch--how-a-v12-feature-changes-a-file-v11-ships-krondor-2026-10-12) has merged up |
| A7 | **Automatic registration and hot-plug** — `DeviceBus` connect and disconnect | A6b | waiting |
| A8 | **`.buttonMap` generation at Elite-Intel startup** — connected, named, none on disk | Core C1 (done) | **done 2026-10-06** |
| A8b | **Which axes and hats a controller has** — read each axis's identity (X, Y, Z, Rx, Ry, Rz, Slider, Dial) from the controller's HID description, on Windows and Linux, and the number of hats it reports, in the shared `elite.intel.devices`, built on this branch (Alan, 2026-10-10). SDL gives only an axis count, two Virpil sticks put the twist on Z and RZ, and the Device Service reads no hats today. The Device Editor is built from what this reports: a tab only for a kind of input the controller has. **A8's generated `.buttonMap` gains hat labels** in the form `Hat 1 Up`, `Hat 1 Right`, `Hat 1 Down`, `Hat 1 Left` (the spec's order), beside its existing `Button N` and `X Axis` (Alan, 2026-10-10). [Spec](../02-features/bindforge/alias-designer.md#which-axes-and-hats-a-controller-has--2026-10-10-alan) | — | **done 2026-10-10** |
| A9 | **Device Editor** — a dialog opened from a gear on every My Devices row: DEVICE (name, VID/PID, install strip), AXES, BUTTONS (groups of 32), HATS, each only where the controller has them; default labels; live highlighting; **SAVE** to the draft and BACK's Save / Discard / Keep editing; the footer's middle place (GAP 7), with CLEAR's button laid out for A10. [Spec](../02-features/bindforge/alias-designer.md#a-dialog-opened-from-the-row--2026-10-10-alan) | C2 (done), A8b (done) | **done 2026-10-11** |
| A10 | **Actions** — DISCARD of the whole draft (if any), CLEAR, RESET LABELS, APPLY, reported per installation. *SAVE moved to A9 (2026-10-10).* Unlocks [Core C6](core.md#slices), the exit prompt, which needs an editor holding unsaved edits. **RESET LABELS must delete the `.buttonMap` in every installation**, or A8 never regenerates it | A9 (done) | **done 2026-10-11** |
| A11 | **Rename** — every installation first, `.binds` last, all or nothing | A10 (done) | ready — unblocked 2026-10-13, the `.binds` rewrite has merged up; after A6b |
| A12 | **When hardware changes** — a missing device, the identity question, retarget, one missing device at a time | A11 | waiting |
| A13 | **The missing-controller warning**, once per run | — | **done 2026-10-11** |

**A6b is next**, then A11; A7 and A12 wait on them. The `.binds` rewrite merged up 2026-10-13. Core C6, the exit
prompt A10 unblocked, was settled 2026-10-12 as a ruling with no code — the Device Editor's own prompt is it.

**One `.binds` rewrite unblocks three slices:** A6b's hex case, A11's rename, and CLEAR on a device `.binds` names
(refused until then — [the rule](../02-features/bindforge/alias-designer.md#an-edit-binds-depends-on-updates-binds--2026-10-11-alan)).
*Settled 2026-10-12:* Alias Designer writes it inside its own Apply. The API is
`BindingsWriter.rewriteDeviceReferences(List<Path>, DeviceReferenceRewrite, Path)`: drafts and masters first, the
game's `.binds` **last**; a hex reference rewritten **from the value `BindsDeviceReferences` found**; then
`recordGameFileBaseline` for each rewritten draft. [The rules](../02-features/bindforge/alias-designer.md#an-edit-binds-depends-on-updates-binds--2026-10-11-alan).

## Hand-off notes

*Newest first. Two or three lines each: what was decided and where it is recorded, what was found, what the
next slice needs.*

- **2026-10-13 — A1b built** (Alan: the outer BIND FORGE tab). Recorded in [Activation, built](../02-features/bindforge/alias-designer.md#built--2026-10-13-alan-a1b):
  tried once per run; "identical" is `DeviceFilesPush.plan` writing nothing (a missing `DeviceMappings.xml` opens
  first setup, whose Continue now stays offered with nothing to ask); activated written in `establish`'s
  transaction; backup once, folder in `bindforge_activation.backup_folder`, told then. **Found:** activation must
  re-detect installations (`rescan()`), or a fresh database activates empty. **Owed:** File Manager's retention must
  exempt `backup_folder`; two concurrent detections can collide on an installation row's insert (activation then
  fails and retries next run). The Swing trigger is untested by the suite. **A6b needs:** onboarding runs after
  activation — test `BindForgeActivationManager.isActivated()`, never an empty master.

- **2026-10-13 — A6b planned, then A1b split out ahead of it** (Alan answered five questions, one at a time; no
  code). **Found:** first setup had become a button every user must press, and an empty master meant *not set up*,
  so a new player and a player with only a custom `.binds` could never activate BindForge. Recorded in
  [Activation](../02-features/bindforge/alias-designer.md#activation--2026-10-13-alan) and two notes in
  overview.md: silent when the installations are identical; first setup opened straight away when they differ;
  never-pruned "before BindForge" backup of all input data; told once, spoken. **A1b needs:** where the backup
  lives (beside `DeviceFilesSnapshot`'s, in the default player backups folder), the identical test (likely
  `FirstSetupPlan` with nothing to write), the trigger (first showing, as A13's `missingWarningWanted`).
  **A6b's sources, approved:** `needingName` → `OnboardingDialog` (default name, `validate`, names taken = master +
  draft + every installation + Frontier + names picked in the dialog); labels from `ButtonMapLabels.generate` with
  `DeviceService` counts and description; **not hex-named** — written now to master *and* a started draft (one
  transaction, or `promote()` drops it) and to every installation by a new `DeviceOnboarding` (one element inserted
  at master-order position, Edit History, `AtomicFiles`; `.buttonMap` by `CreateOnlyFiles`); **hex-named** — held in
  the draft, and `DeviceApply` rewrites after the push via `rewriteDeviceReferences` (working copies first, the
  game's `.binds` last, then `recordGameFileBaseline`); hex counts by a new strict reader in
  `BindsDeviceReferences`. **Still to ask in A6b:** what a partial Apply does to the hex rewrite (rename's
  all-or-nothing, or not); what opens onboarding; whether Next writes per controller or at the end.

- **2026-10-13 — the `.binds` rewrite merged up; A6b and A11 unblocked.** Krondor narrowed the hex match to VID
  then PID and asked for the game's file last; both accepted and recorded in
  [the spec](../02-features/bindforge/alias-designer.md#an-edit-binds-depends-on-updates-binds--2026-10-11-alan).
  **A6b needs:** the API and its three caller rules above.

- **2026-10-11 — A13 and second A10 review fixes** (`code-integrity-review.md`, Alan: all six). Recorded under *After
  review* in [built — A13](../02-features/bindforge/alias-designer.md#built--2026-10-11-alan-a13) and *Second review*
  in [built — A10](../02-features/bindforge/alias-designer.md#built--2026-10-11-alan-a10): the warning waits for the
  new `DeviceService.hasEnumerated()` (running is not listed); Apply reads `.binds` only for a user's pending name;
  `DeviceApply` takes the master manager; a stale CLEAR shows its own message; `deviceServiceRunning` is set on the
  EDT.

- **2026-10-11 — A13 built** (Alan answered three questions, one at a time). Recorded in [built — A13](../02-features/bindforge/alias-designer.md#built--2026-10-11-alan-a13):
  only controllers `.binds` names; **not at Elite-Intel startup but the first time Alias Designer is shown** in the
  run; spoken and logged. **Found:** `AliasDesignerPanel.initData()` first runs at application start, possibly before
  the Device Service has listed anything — so the first showing starts its own refresh and the warning judges that
  one. Device Service down keeps the warning; unreadable `.binds` uses it up. The Swing trigger is untested by the
  suite — unplug a bound stick, open the tab twice, hear one line. **Next:** nothing here is unblocked; Core C6 is.

- **2026-10-11 — A9/A10 review fixes** (`code-integrity-review.md`, Alan: all but #4). Recorded under *After review*
  in [built — A10](../02-features/bindforge/alias-designer.md#built--2026-10-11-alan-a10): a reset built-in is applied
  whatever `.binds` says (Apply had held back every named pending name, Frontier's included, so a reset never landed);
  `BindsDeviceReferences.referenceCounts` is strict — a missing folder, or one with no readable `.binds`, throws, and
  CLEAR is refused (`Why.BINDS_UNREAD`) while the editor still opens; a no-op CLEAR or undo is reported; the gear
  asks `masterEmpty`; hats cap at four. One off-EDT runner per screen (`offEdt` + `runAndReport`, `runAndClose`).
  **#4 deferred** — Known issues. **Anything deciding a removal reads `referenceCounts`, never the lenient
  `referencedEntryNames`.**

- **2026-10-11 — A10 built** (Alan answered five questions, one at a time). Recorded in [built — A10](../02-features/bindforge/alias-designer.md#built--2026-10-11-alan-a10)
  and a new rule, [an edit `.binds` depends on updates `.binds`](../02-features/bindforge/alias-designer.md#an-edit-binds-depends-on-updates-binds--2026-10-11-alan):
  CLEAR is refused while `.binds` names the device, and Apply never removes such an entry; RESET LABELS on
  `VPCPanel`/`VPCThrottle` writes Frontier's map back; a cleared device offers UNDO CLEAR; APPLY (always, once the
  master exists) and DISCARD (only when `DraftChanges` finds something) in the footer; orphans nobody recorded are
  left alone. **Found and fixed:** a device cleared then saved again would have become a pending removal while in
  the master (`promote`). **Owed:** a **Remove** way out for an unrecorded orphan `.buttonMap`; the `.binds`
  rewrite (see above). **C6 needs:** `DraftChanges.stored()` is the "has edits" test. The Swing layout is untested
  by the suite — CLEAR → reopen → APPLY on a real stick.

- **2026-10-11 — A9 built** (Alan answered five questions, one at a time). Recorded in [built as a dialog](../02-features/bindforge/alias-designer.md#built-as-a-dialog--2026-10-10-alan-a9):
  before first setup the gear offers SET UP instead of the editor; unreadable axes show all eight; an axis rests
  where first seen; SAVE writes every box, but only edited or saved ones on a device the game draws with icons;
  CLEAR/RESET LABELS sits disabled in the footer's new middle place. SAVE closes the dialog. GAP 7 is built and in
  the canon §10. `DeviceService` now polls hats (`DeviceHatEvent`). **A10 needs:** wire the disabled middle button in `DeviceEditorDialog.buildUi`; the draft holds saved labels
  but the Alias column and install strip still read the master, so APPLY is what makes a save visible there. The
  Swing layout is untested by the suite — open the editor on a real stick.

- **2026-10-10 — A8b review fixes** (`code-integrity-review.md`, Alan). Recorded under *After review* in
  [which axes and hats a controller has](../02-features/bindforge/alias-designer.md#which-axes-and-hats-a-controller-has--2026-10-10-alan):
  labels fall back to all eight when the description's count disagrees with SDL's (`agreesWith` — **A9's AXES tab
  should use it too**); the Windows path check ignores case; the Linux parser follows the kernel on usage counts
  and delimiters; `DeviceDescription` keeps axes sorted and unique.

- **2026-10-10 — A8b built** (Alan chose six design answers, one at a time). Recorded in [which axes and hats a
  controller has](../02-features/bindforge/alias-designer.md#which-axes-and-hats-a-controller-has--2026-10-10-alan):
  SDL3 stays for live input and HID is read once at connect (`hid.dll` / `/sys`, not DirectInput's COM — a JNA
  mistake there crashes the app); axes are named by game slot by Wine's DirectInput rule; SDL's numbering is
  matched only when certain; hats from SDL; `Device` untouched, a separate `DeviceDescription`. **Found** in source:
  SDL sorts DirectInput axes by slot, evdev by kernel code (Slider before Dial); Wine also makes Throttle a slider,
  Wheel Z, Rudder Rz. Testing items 21–23 added — **21 Alan can run** with the two VIRPILs, reading the new
  `Device '…': axes …` log line. **A9 needs:** hat *movement* is not polled — add it to `DeviceService`'s loop;
  decide what AXES shows when `axesKnown` is false; offer *"N more axes the game cannot use"* from
  `axesWithoutSlot`; light axes through `axisAt`, never by position.

- **2026-10-10 — A9 redesigned before building; A8b added** (Alan, no code). The Device Editor is a **dialog**
  opened from a gear, not an inline expansion — recorded in [the Device Editor](../02-features/bindforge/alias-designer.md#a-dialog-opened-from-the-row--2026-10-10-alan),
  with the confirm step dropped, default labels, highlighting rules and the button layout; the
  [UI component map](../02-features/bindforge/ui-component-map.md#alias-designer) and the mockup follow it; testing
  items 17–20 added. **Found:** SDL cannot say which axis is which, so **A8b** reads axes and hats from the
  controller, in the shared `elite.intel.devices` on this branch — `CLAUDE.md`'s shared-code rule names
  `elite.intel.io`, `bindforge.io` and `.rules`, not the devices package. **A8b's design is not decided**: start it
  by asking its questions one at a time. **The gear is copied** from `CommanderTabPanel`, a shared gear column
  later. Alan asks for **one question at a time** in design discussion.

- **2026-10-08 — A5 review fixes** (`code-integrity-review.md`, Alan). Recorded under *After review* in
  [Built as the stock reference](../02-features/bindforge/alias-designer.md#built-as-the-stock-reference--2026-10-08-alan):
  `DeviceEntry` now carries `primary` — the element's own pair, `null` when it has only `<Alternative>`s — read by
  `BuiltInDeviceList` and `InstallationDeviceScanner` instead of "the first pair"; the Built-in columns are stated
  once (`BUILT_IN_COLUMN_KEYS`); a test ties `FrontierStockDevices.CAPTURED` to the stock README. Dates stay ISO in
  every language (Alan). Tests added for the shared `044F:FFFF` pair and an element with no pair of its own.
  **Anything reading an entry's VID/PID should use `primary()`**, never the set's order.

- **2026-10-08 — A5 built** (Alan chose all four recommendations). Recorded in [Built as the stock reference](../02-features/bindforge/alias-designer.md#built-as-the-stock-reference--2026-10-08-alan):
  My Devices and Built-in Devices are `COMPACT` tabs with the divergence list beneath both; columns Device, VID / PID,
  Also covers (count, pairs in the tooltip), Status; a capture-date note. **Found and fixed:** `DeviceMappingsParser`
  returned an entry's pairs through `Set.copyOf`, salted per run, so `InstallationDeviceScanner` stored a random pair
  for `<GamePad>`/`<DualShock4>`; pairs now keep file order, primary first. **A9** opens a row of My Devices — the
  Built-in tab is read-only and acts on nothing; the device list is now a `JTabbedPane`, not a `HudSection`.

- **2026-10-08 — A4 review fixes** (`code-integrity-review.md`, Alan). Recorded under [the Installations markers](../02-features/bindforge/alias-designer.md#the-installations-markers--2026-10-08-alan):
  a built-in reads M only where Frontier's entry is there, even when the master labels it — the check never compares
  Frontier's elements, so a gone one raised no finding. `Severity`'s declaration order is now documented as
  load-bearing; the device columns are stated once (`DEVICE_COLUMN_KEYS`); the marker has its own *not added* key;
  GAP 5 is marked avoided in the component map. Two tests added.

- **2026-10-08 — A4 built** (Alan chose all six recommendations). Recorded in [the Installations markers](../02-features/bindforge/alias-designer.md#the-installations-markers--2026-10-08-alan):
  one column per installation; colour read from the divergence findings; *not added* in the finding's colour for an
  entry the master holds and the installation lacks; a reset file marks every master device in its row's colour; a
  gone folder reads *not found*; no columns before first setup. The markers read the check, not
  `bindforge_device_installs` (noted in device-provenance.md). **Found:** first setup has no undo in the app — now a
  known issue. **A9's install strip** can reuse `InstallationMarkers.of` for the open device; the spec draws it with `•`.

- **2026-10-08 — A3b built** (Alan chose all five recommendations). Recorded in [the ways out](../02-features/bindforge/alias-designer.md#the-ways-out--2026-10-08-alan):
  adopt from a row takes **one device** (`adopt(installId, deviceName)`), never a missing entry; revert stays the
  whole installation and the confirm counts the rows it reaches; Edit History is the backup before revert; adopt and
  the re-merge write the master (and draft) only, so the other installations become rows to revert. An unreadable
  `DeviceMappings.xml` and an orphan `.buttonMap` have no way out. **Owed:** the orphan's removal is A10's (CLEAR);
  an unreadable file has no repair until File Manager's restore. **A4** reads the same `DeviceFilesCheck.Report` the
  panel now keeps as `currentReport`.

- **2026-10-08 — A3 review fixes** (`code-integrity-review.md`, Alan). Recorded in [against the master](../02-features/bindforge/alias-designer.md#against-the-master--2026-10-08-alan):
  reset to Frontier's file is red only when `.binds` names one of the user's own devices, and no file at all is
  always red (`InstallationCheck.fileGone`); one cause is one row — no label rows beside a reset, no orphan row for
  a missing entry's map. `DeviceFilesCheck.Report` now carries `masterNames` and `userNames`, so the screen reads
  the master once; `againstMaster` refuses a report with no master; `installKey(long)` is shared by the panel;
  `ENTRY_MISSING_FROM_SOME` became `ENTRY_MISSING`; an unchanged installation logs at DEBUG. **A4** can read the
  same `Report`.

- **2026-10-08 — A3 built** (Alan chose all three recommendations). Recorded in [against the master](../02-features/bindforge/alias-designer.md#against-the-master--2026-10-08-alan):
  before first setup the list still compares installations with each other; a reset or unreadable
  `DeviceMappings.xml` is one row; an entry the master lacks, and one out of place, are red when `.binds` names
  them; labels are one yellow row per device. **Split:** the ways out became **A3b** — they are writes, and the
  re-merge needs a new write of one device's labels into the master *and* the draft (three-way, like adopt).
  **A3b needs:** findings carry installation ids (`DeviceDivergence.installKey(long)`), which is what adopt and revert take;
  `LabelMerge` takes the master as a side by name. A4's `M` markers can read the same `DeviceFilesCheck.Report`.

- **2026-10-08 — A2 built** (Alan chose option A). Recorded in [the label merge](../02-features/bindforge/alias-designer.md#the-buttonmap-label-merge--2026-10-08-alan):
  the device keeps one row in first setup and opens the merge view; an answer is the label, keyed
  `labels:<device>:<input>`; every disagreeing input is its own question; *use X as the master* answers only inputs X
  labels. **Found:** `List.copyOf(...).contains(null)` throws — `LabelMerge.unanswered` checks null first. **A3 needs:**
  the re-merge is not built; `LabelMerge` takes sides by name, so pass the master as one side and reuse
  `LabelMergeDialog` (its `sideNames` map names the master).

- **2026-10-08 — A1 review fixes** (`code-integrity-review.md`, Alan). Recorded under *After review* in
  [First setup](../02-features/bindforge/alias-designer.md#settled-while-building-it--2026-10-07-alan): two names
  on one VID/PID inside one installation are not a question — the first in the file is the device, the rest are
  `SHADOWED` rows and pending removals (Alan chose this over asking); a name winning any name conflict is kept, and
  answers putting two devices on one VID/PID count as unanswered; `Outcome.FAILED` names the step, and the dialog
  closes whenever the master is filled; a failed snapshot removes its folder. `Target.isReachable()` replaced the
  same check in five classes. **A10 owes:** the pending-removal cleanup must not remove an entry `.binds` still
  names — a shadowed or losing name can be one.

- **2026-10-07 — A1 built** (Alan). Rulings in [First setup, settled while building it](../02-features/bindforge/alias-designer.md#settled-while-building-it--2026-10-07-alan):
  a name conflict is answerable only when the losing name is not in `.binds` (the rename is A11, its `.binds`
  rewrite blocked) — the loser stays on disk under the winner as a pending removal; colliding labels are answered
  per device by an installation, the rest unioned; "use as master" never drops another installation's extras; the
  backup is device files only, in the default folder; any draft is discarded as the master is filled. **Found:**
  `app/build.gradle` packages resources by extension, so the Frontier maps ship as `.buttonMap.xml`. **A2 needs:**
  replace `FirstSetupPlan`'s per-device `LABELS_DIFFER` answer with one row per colliding input (the spec's merge
  table), keeping the whole-file route; the same view should serve a later re-merge. **A3/A4** now have a master
  to compare against — call `DeviceFilesCheck.check()` (C3 note). **Still owed (C3 note):** generated A8 maps are
  mastered by first setup as labels; whether those count as user content is undecided.

- **2026-10-06 — A8 second review fixes** (`code-integrity-review.md`, Alan). `stop()` now interrupts and drops
  queued work, so nothing lands in a game folder after it returns and no temp file is left at exit; the name and
  the icon flag come from one pass over one element; the create-only write moved to package-private
  `CreateOnlyFiles` (temp names as `AtomicFiles` names them), for A6b to share; the case-insensitive master check
  carries its `// WHY:`. **Found by the new test:** on Windows an element named `C:x` resolved to a path on drive
  C, outside the game folder — `GameInstallation.buttonMapIn` now refuses any name that does not land directly in
  `DeviceButtonMaps`, which also covers Apply and the startup check. **Owed by whoever wires the startup check (C3) at startup:** an element an installation
  added gets a generated `.buttonMap`, which the check lists as 30–40 `ADDED` labels and adoption would take into
  the master. Decide whether a file byte-identical to a generated one is user content, and which runs first.

- **2026-10-06 — A8 review fixes** (`code-integrity-review.md`, Alan). All eight axis labels whenever a
  controller has any axis, instead of by position; devices the game draws itself are skipped — `<SupportsIcons>`
  and `<GamePad>` — after Frontier's readme confirmed icon tokens (`[x52b1]`); the create-only write publishes by
  hard link, so it refuses atomically on Linux too; temp cleanup no longer masks the outcome; device-file paths
  live once in `GameInstallation` (`deviceMappingsIn`, `buttonMapIn`), used by Push, Check, the scanners and both
  panels; `ButtonMapStartupTest` added. All recorded in the spec. Frontier's readme is in `reference-data`; its
  "put maps in the bindings folder" line does **not** change where `.buttonMap` lives — the installation's
  `ControlSchemes\DeviceButtonMaps`, as documented (Alan).

- **2026-10-06 — A8 built** (Alan). `ButtonMapGeneration` creates a `.buttonMap` for a connected controller an
  installation already names, never over an existing file; decisions recorded in
  [the startup `.buttonMap`](../02-features/bindforge/alias-designer.md#at-elite-intel-startup-a-buttonmap-for-every-connected-named-controller):
  every connect, not only startup; the name is per installation (first match); a device the master labels is
  skipped as C3's drift; axes by position X–V; no hats (they report as buttons). **Found:** SDL compacts axis
  indices — see Known issues. Started as `BUTTON_MAP_STARTUP` in `AppController.buildServices` after `DEVICE`
  (Alan: no need to wait on Krondor for one line; diagnostics stubs it). **A6b reuses** `ButtonMapLabels` and
  `CreateOnlyFiles.createNew`. **A1 next** — first setup will
  meet generated maps for built-ins in every installation; they are identical, so they merge without a question.

- **2026-10-06 — A6 built: the rule, not the dialog** (Alan). `bindforge.devices.DeviceNames` is pure, and the
  spec's six examples are its tests. Decided and recorded in [the default-name rule](../02-features/bindforge/alias-designer.md#the-default-name-rule):
  ASCII letters only with accents folded; numbering starts at 2 and shortens by the number's width; a device's
  own name is not a clash with itself. **Split:** the onboarding dialog and its writes became **A6b**, because
  the hex case rewrites `.binds` (blocked on `bindforge.io`) and generated labels need A8's generator. **A8
  needs:** a create-only `.buttonMap` write in `bindforge.devicefiles` (never `push()`), and a fix for
  `ButtonInputMapper.axisToBindsToken` throwing at axis 6 and above. A6b will reuse A8's label generator.
  **Callers owe the rule:** the names-taken set is the master's names, every installation's entries and
  Frontier's `names()`, minus the device's own name when it is renamed.

- **2026-10-03 — brief written.** Built so far: install detection, device-file parsing, the orphan check, severity,
  the divergence list, the master store, Frontier's stock reference, the hardware VID/PID derivation, My Devices
  and the installation scan.
