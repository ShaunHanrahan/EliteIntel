# Foundation — V1.2's changes to files V1.1 ships

**Not a tab, and not V1.2 code.** These are the additions to existing shared files that later BindForge slices
cannot be built without, made on **`V1.1-Release-bf-foundation`**, a branch off `V1.1-Release`. Krondor checks
each change and merges it into `V1.1-Release`, and it reaches `V1.2-BindForge` by the same merge-up as his own
fixes. **Why:** he ships fixes to `bindforge.io`, `bindforge.rules` and `elite.intel.io` every week. If V1.2
edited those files on its own branch, the next merge would collide in the most sensitive code in the project,
and the real risk is not a bad merge but silently dropping a fix already shipped to users.
[The ruling](README.md#the-foundation-branch--how-a-v12-feature-changes-a-file-v11-ships-krondor-2026-10-12).

**Built only as far as the next consumer needs.** A foundation slice runs just ahead of the slice it unblocks.

## The terms — every change on the branch meets all four (Krondor, 2026-10-12)

It ships in his weekly releases, so it must be invisible to V1.1 users:

1. **Additions only.** Existing methods do exactly what they did. A widened behaviour is a new method beside
   the old one, never a change to it.
2. **No migrations, no UI, no strings, no new startup work.**
3. **Nothing that depends on V1.2-only code** — `bindforge.devicefiles`, `bindforge.devices`, `bindforge.install`,
   the BindForge managers and migrations do not exist on `V1.1-Release`.
4. **Tested on its own**, and V1.1's own tests still pass.

**What does not go on it** (Alan, 2026-10-11): a **new** class that only V1.2 calls and that edits no file V1.1
ships is built on `V1.2-BindForge`, as the C5 file poller was. The foundation branch is for *changing an existing
file*. Recorded in the [platform map](../01-host-integration/elite-intel-platform-map.md#the-shared-write-path-is-eliteintelio--built-2026-10-03).

## How a foundation session runs

`V1.1-Release` holds none of BindForge's docs — no `docs/05-build-plan/`, no specs. So the work is split across two
checkouts:

- **Code and tests** on `V1.1-Release-bf-foundation`, in its own git worktree at
  **`E:\EliteIntelProject\EliteIntel-bf-foundation`**, so the main checkout stays on `V1.2-BindForge`. Branch and
  worktree created by Alan 2026-10-12, from `V1.1-Release` at `7a3c51d26`, and pushed. Run step 1 of
  `/next-slice` (fetch, behind or not) in the worktree too: `git -C ../EliteIntel-bf-foundation status -sb`.
- **This brief and every spec decision** in the main checkout, on `V1.2-BindForge`, as for every other section.
- **Tests:** `.\gradlew app:test` run **in the worktree**, against V1.1's own suite.
- **A slice is done when Krondor has merged it**, not when its tests pass. Until then it is *with Krondor*, and
  the slices it unblocks still wait. The merge-up reaching `V1.2-BindForge` is what releases them.
- **Never commit, push or merge** — Alan does, and Krondor merges into `V1.1-Release`.

## Spec

- [preset-editor.md](../02-features/bindforge/preset-editor.md), whole — F1. Especially
  [what a line holds](../02-features/bindforge/preset-editor.md#what-a-line-actually-holds--the-preset-name-not-the-file-name)
  and [what exists in code today](../02-features/bindforge/preset-editor.md#what-exists-in-code-today--checked-2026-09-17).
- [The `StartPreset` specimens](../02-features/bindforge/reference-data/SPECIMENS-README.md#startpreset-specimens--two-generations-and-a-split-preset-in-the-wild) — F1.
- [alias-designer.md](../02-features/bindforge/alias-designer.md): [what onboarding writes](../02-features/bindforge/alias-designer.md#what-onboarding-writes-and-when),
  [an edit `.binds` depends on updates `.binds`](../02-features/bindforge/alias-designer.md#an-edit-binds-depends-on-updates-binds--2026-10-11-alan),
  [Renaming a Device](../02-features/bindforge/alias-designer.md#renaming-a-device) through its end — F2.
- [Format §4.0](../02-features/bindforge/domain-knowledge/EliteDangerous-BindsFileFormat.md#40-the-rule-confirmed-2026-09-08) — how `.binds` names a device — F2.
- [overview.md](../02-features/bindforge/overview.md), from **Live File Synchronization** through **Upgrading the
  Existing Bind Editor** — F2, F3, F4. [There is one writer](../02-features/bindforge/overview.md#there-is-one-writer-and-it-already-exists)
  is why F2 and F4 grow `BindingsWriter` rather than add a writer.
- [bind-editor.md — what the capture dialog cannot do yet](../02-features/bindforge/bind-editor.md#what-the-dialog-cannot-do-yet--gaps-in-the-code-not-in-the-design) — F4, F5.

## Built — what the foundation adds to

| What | Where | State |
|---|---|---|
| Finding `StartPreset` | `bindforge.io.BindingsLoader.findStartPresetFile(Path)` | public, by prefix and extension; `findFirst()` on an unordered listing |
| The active preset | `BindingsLoader.getActivePresetName()` | **reads the first non-empty line only** — right for the assistant, wrong for a split preset. Callers: `BindingProfilePanel`, and `getLatestBindsFile` through the private `findActivePresetName` |
| Writing a binding | `bindforge.io.BindingsWriter` — one keyboard slot or `{NoDevice}`, by raw-text replacement, BOM kept, stale-file check by time and size | **refuses any controller slot** (`isEditableMainSlot`). Tests: `BindingsWriterTest` |
| `.binds` draft | `BindingsWorkingCopyRepository` — per-preset working copies in `elite-intel/bindings/`, a SHA-256 baseline per preset | **the master is only a hash** (core.md) |
| `.binds` apply | `BindingsApplyService.apply` — refuses when draft and game both changed, validates XML, backs up to `AppPaths.getBindingsBackupDir()`, writes through `AtomicFiles` | one preset per call |
| Reading `Device=` | `bindforge.devicefiles.BindsDeviceReferences` (V1.2 only) — every `.binds` in the folder, names and hex, counts per entry | read only; nothing writes `Device=` |
| Axis tokens | `elite.intel.devices.model.ButtonInputMapper.axisToBindsToken` | **throws at axis 6 and above** (no U, V) |

## Settled — and where it is recorded

- **V1.2 changes to existing shared files go on the foundation branch.** [README](README.md#the-foundation-branch--how-a-v12-feature-changes-a-file-v11-ships-krondor-2026-10-12), `CLAUDE.md`, the platform map.
- **The four-line read is a second method, not a change to `getActivePresetName()`** — it answers a different
  question for a different caller. [preset-editor.md](../02-features/bindforge/preset-editor.md#what-exists-in-code-today--checked-2026-09-17)
- **A line holds the preset name; strip a version suffix only when one is there** (`Custom.4.2 (2).binds` has
  none). [preset-editor.md](../02-features/bindforge/preset-editor.md#a-name-does-not-identify-a-file)
- **BindForge adds no writer, working copy or working folder; the existing class grows.**
  [overview.md](../02-features/bindforge/overview.md#there-is-one-writer-and-it-already-exists)
- **The writer widens by who initiates, not by device**, and `KeyBindingsParser` never widens.
  [overview.md](../02-features/bindforge/overview.md#two-narrow-boundaries--one-stays-one-widens)
- **In a rename, `.binds` goes last, and only if every installation succeeded.**
  [alias-designer.md](../02-features/bindforge/alias-designer.md#binds-goes-last-and-only-if-every-installation-succeeded)

## Known issues

- **`PlayerBackupService` already differs between the two lines** (found 2026-10-12). On `V1.2-BindForge` its
  `resolvePlayerBackupsDir` reads `BindForgeSettingsManager.getBackupDestination()` — a V1.2-only class — and
  `V1.1-Release` does not have that. It arrived on this branch through merge `f9deb5522`. That is a V1.2 change
  to a file Krondor maintains, and it depends on V1.2-only code, so it could not go on the foundation branch as
  written. His next edit to that file will conflict. **Raise with Alan and Krondor** — not fixed by any slice
  here.
- **Two `StartPreset` files in one folder** — `StartPreset.start` (the single-line, pre-Odyssey form) and
  `StartPreset.4.start` — and `findStartPresetFile` returns whichever the listing yields first. F1 must decide which wins
  without changing what the existing method returns.

## Boundaries

- **Only additions to existing files** in `bindforge.io`, `bindforge.rules`, `elite.intel.io`, `AppPaths` — and,
  for F5, `elite.intel.devices.model`. Anything new and V1.2-only goes on `V1.2-BindForge`.
- **The slice's consumer is not built here.** F1 gives Preset Editor a reader; the Preset Editor screen,
  its draft and its write are Preset Editor's.
- **`ai.hands`** — never.

## Slices

| # | Slice | Changes | Unblocks | Status |
|---|---|---|---|---|
| F1 | **Read all four `StartPreset` lines** — General, Ship, SRV, On Foot, in that order — as a new method beside `getActivePresetName()` | `BindingsLoader` | Preset Editor (first job), [Core C7](core.md#slices) | **next** |
| F2 | **The `.binds` device rewrite** — replace one `Device=` value with another across `.binds`: hex → name (onboarding), old name → new name (rename), name → hex (CLEAR) | `BindingsWriter` | [A6b, A11, CLEAR on a named device](alias-designer.md#slices) | waiting — two questions below |
| F3 | **Scope the `.binds` and `StartPreset` master** — what of C4's whole-file master must change an existing file, and what can be a new V1.2 class. A scoping slice: its output is a ruling and F3b's definition, not code | — | [Core C4](core.md#slices) | after F2 |
| F4 | **The writer's user-edit path** — a deliberate edit may write any device; the existing keyboard-only path stays for everything automatic | `BindingsWriter` | Bind Editor: editing | when the Bind Editor section starts |
| F5 | **`Joy_UAxis` and `Joy_VAxis`** in `ButtonInputMapper`'s axis tokens, so axis 6 and above stop throwing | `ButtonInputMapper` | the capture dialog | when the Bind Editor section starts |

### F1 — what the session needs to know

- **What it returns:** four named values, one per section, in the file's order. The specimen
  `StartPreset.4.start` is `KeyboardMouseOnly / Custom / Custom / Custom`, **CRLF, no newline after the last line**;
  `StartPreset.start` is one line, `Custom`. Both are in `reference-data/` and are the test fixtures.
- **To settle in the sources table:** what a one-line file means (the pre-Odyssey form — one preset for all
  four?), a file with fewer than four lines, blank lines, and no file at all; which file wins when both forms
  exist (Known issues).
- **Keep it pure where it can be** — a parse of the file's text, tested without a folder, plus the method that
  finds and reads it. Nothing V1.2-only: the result type lives in `bindforge.io` or `.model`.
- **`getActivePresetName()` and `getLatestBindsFile` do not change.** The assistant's answer stays what it is.

### F2 — two questions, settled before it is built

1. **Who writes it** — Alias Designer as part of its own Apply, or a task handed to the Bind Editor. Left
   undecided on 2026-10-11 ([the rule](../02-features/bindforge/alias-designer.md#an-edit-binds-depends-on-updates-binds--2026-10-11-alan)).
2. **What it does to a `.binds` draft.** A rename rewrites the game's `.binds`; a working copy in
   `elite-intel/bindings/` still names the old device, and `BindingsApplyService` would refuse it as changed on
   both sides. Rewrite the draft too, or refuse the rename while a draft exists.

*Also for its sources table:* every `.binds` in the folder or the active ones only (every preset is
[a first-class file](../02-features/bindforge/overview.md#what-the-master-covers-everything--settled-2026-09-24));
`Device=` appears on `<Modifier>` elements as well as slots; and all-or-nothing across several `.binds` files.

## Hand-off notes

*Newest first. Two or three lines each: what was decided and where it is recorded, what was found, what the
next slice needs.*

- **2026-10-12 — brief written.** Five slices from the specs of what Foundation unblocks; F1 next, as the
  smallest first change for Krondor to check. **Found:** `V1.1-Release` has no BindForge docs, so a foundation
  session works in a worktree (above); `PlayerBackupService` already diverges between the lines (Known issues);
  `ButtonInputMapper`'s U/V fix is a V1.1 file too, so it became F5. **F1 needs:** the branch and its worktree —
  created the same day, at `E:\EliteIntelProject\EliteIntel-bf-foundation`.
