---
date: 2026-09-11T14:39:44.738531+00:00
git_commit: 2006320012476bee9c4449f331e556af845d0e1c
branch: main
topic: "Selectable fork-point and branch-HEAD comparison modes"
tags: [plan, intellij-plugin, git, status-bar, comparison]
status: manual-verification
---

# PLAN: Selectable Fork-Point and Branch-HEAD Comparison Modes

Extend Branch Changes with two persisted comparison modes selectable from its existing status-bar widget:

- **Fork point** compares the live document with the continuously recomputed sole merge-base of the selected base ref and current `HEAD`. This remains the default and preserves existing behavior.
- **Branch HEAD** compares the live document directly with the latest locally known commit at the selected base ref, such as `origin/main`.

Both modes share the branch-specific selected base. The active mode is represented by a distinct embedded Lucide icon in the widget and by two icon-bearing popup actions. Git behavior remains local, read-only, and no-fetch.

## Acceptance Criteria

### Configuration and migration

- [x] Plugin defines stable `FORK_POINT` and `BRANCH_HEAD` comparison modes with user-facing names `Fork point` and `Branch HEAD`.
- [x] Comparison mode persists with the shared base ref per `(normalized Git root, current local branch)` in workspace-local state.
- [x] Existing persisted mappings lacking a mode, malformed/unknown persisted mode values, and mappings created through ordinary base selection default to `Fork point` without losing their base refs; the explicit Branch-HEAD chooser flow instead saves `BRANCH_HEAD`.
- [x] Branch and Git-root switches restore each mapping's base ref and mode independently.
- [x] Changing the selected base preserves the configured mode; changing the mode preserves the selected base.
- [x] Current local branch remains rejected as comparison base in both modes.
- [x] Selecting `Branch HEAD` without a configured base opens the existing base chooser; successful confirmation saves base and mode together, while cancellation changes nothing.

### Comparison semantics

- [x] `Fork point` resolves selected ref tip and current `HEAD`, requires exactly one best merge-base, and uses that merge-base commit as baseline on every recomputation.
- [x] `Branch HEAD` resolves selected ref tip on every recomputation and uses that exact commit as baseline without invoking or requiring merge-base selection.
- [x] Advancing the selected local/remote ref externally refreshes `Branch HEAD` comparisons against its new local tip; plugin never fetches.
- [x] `Branch HEAD` remains usable for unrelated histories and criss-cross histories because only ref resolution, not merge-base resolution, is required.
- [x] Baseline blob loading and rename detection use mode-selected baseline commit: merge-base for `Fork point`, selected ref tip for `Branch HEAD`.
- [x] Both modes continue comparing baseline text directly with the live document, including committed, staged, unstaged, and unsaved net changes.
- [x] Comparison identity includes mode and selected baseline hash; changing mode or moving selected ref cancels/supersedes stale computations and cannot install stale markers or open stale diffs.
- [x] Cached snapshots are revalidated before result application, split-editor reuse, direct diff opening, and pending-diff completion; any mode/configuration mismatch clears or recomputes instead of reusing old-mode data.
- [x] Mode, base, branch, `HEAD`, or selected-tip identity changes immediately clear already-rendered old-baseline markers and cached snapshots before replacement computation, so widget and gutters never advertise different modes/baselines.
- [x] Existing binary, size, line-count, missing-object, index-change, cancellation, and Git-failure handling applies equally to both modes; no/multiple merge-base reasons apply only to `Fork point`.
- [x] Every Git command remains local and read-only with `GIT_NO_LAZY_FETCH=1`, `GIT_TERMINAL_PROMPT=0`, and `GIT_OPTIONAL_LOCKS=0`.

### Status-bar and editor UX

- [x] Existing single widget keeps text shape `Branch Changes: <base>` and displays icon for active mode.
- [x] Widget uses embedded Lucide `git-fork` icon for `Fork point` and `git-compare-arrows` icon for `Branch HEAD`.
- [x] Popup contains direct icon-bearing actions for both modes before base/toggle actions; active mode includes `(active)` and is disabled.
- [x] Mode actions remain available whenever current editor has resolvable Git branch context; without context they do not mutate settings.
- [x] Widget icon falls back to `Fork point` for branches without mappings and updates immediately after mode, branch, root, base, enable-state, or repository changes.
- [x] Widget tooltip always identifies active mode and selected base; when a matching successful snapshot exists it also shows actual baseline commit, while pending/unavailable states never fabricate one.
- [x] Marker tooltip/accessibility text, full-diff title/content labels, and debug logs identify selected comparison mode and actual baseline commit without calling a Branch-HEAD baseline a fork.
- [x] Existing base selection, project enable/disable, marker rendering, gutter interaction, and live-document diff behavior remain intact.

### Icons, documentation, and compatibility

- [x] Lucide SVGs are copied from commit `a79b2d131dab2bf20cb224bd0937b439a9c4fa99`, adapted to IntelliJ 16×16 light/dark resources, loaded through a plugin icon holder, and packaged locally with no runtime network dependency.
- [x] Packaged resources include Lucide ISC copyright/license notice; README records icon names, pinned source URLs, modifications, and license.
- [x] README explains mode semantics, default/migration behavior, status-popup selection, persistence scope, moving selected refs, mode-specific rename behavior, and no-fetch policy.
- [x] Plugin description/change notes advertise both comparison modes accurately.
- [x] Tests and Plugin Verifier remain compatible with supported IDEA builds `243` through `262.*`.

## Technical Key Decisions and Tradeoffs

1. **Continuously recomputed fork point:** Keep current moving-ref merge-base behavior rather than store an immutable origin commit.
   - Why: Explicit user choice; preserves existing behavior and follows selected branch/history updates.
   - Impact: `Fork point` can change after ref movement or history rewrites and still reports zero/multiple merge-base as unavailable.

2. **Direct selected-tip baseline for Branch HEAD:** Use resolved selected ref commit without merge-base.
   - Why: Matches direct comparison with latest locally known `main`/other selected branch.
   - Impact: Changes introduced on selected branch after divergence appear in comparison; unrelated histories remain comparable.

3. **Mode stored beside shared base:** Persist one base and one mode per root/current-branch mapping.
   - Why: Branch-specific behavior already exists; sharing base makes mode switching predictable.
   - Impact: Settings schema gains migration-safe mode field and atomic base+mode update path.

4. **Fork point default:** Missing or unknown persisted modes resolve to `Fork point`.
   - Why: Backward-compatible upgrade with no marker behavior change.
   - Impact: Existing workspace files require no explicit migration command.

5. **One status widget with active icon:** Retain `MultipleTextValuesPresentation`; implement dynamic `getIcon()` and two popup actions.
   - Why: Meets mode discoverability without consuming extra status-bar space.
   - Impact: Popup uses ordinary icon-bearing actions because IntelliJ toggle actions replace custom icons with checkmarks; active action instead gets `(active)` and disabled state.

6. **Chooser-on-unconfigured mode selection:** Selecting Branch HEAD with no base launches chooser, then saves both values only on success.
   - Why: Avoids nullable/partial persisted mappings while making mode directly selectable from first-use status state.
   - Impact: Service owns combined selection callback and must reject context changes while chooser is open.

7. **Mode-neutral baseline model:** Replace fork-specific internal baseline names with `baselineHash`/`baselineCommit` and carry mode explicitly.
   - Why: Prevents Branch-HEAD paths, caches, labels, and logs from accidentally retaining fork semantics.
   - Impact: Internal Git operation interfaces, snapshots, cache keys, fakes, and tests change together; no public API compatibility requirement exists.

8. **Pinned embedded Lucide assets:** Use `git-fork.svg` and `git-compare-arrows.svg` under ISC license.
   - Why: Distinct semantics and stable cross-version visuals without depending on changing platform icon names.
   - Impact: Repository and packaged plugin must preserve license notice; SVGs need IntelliJ-sized light/dark adaptation.

## Current State

Current settings store only `baseRef` per root/current branch (`BranchBaseSettings.kt:12-63`). Every comparison unconditionally resolves selected ref and `HEAD`, computes all merge-bases, accepts exactly one, and stores it as `forkHash` (`BranchGitClient.kt:67-101`; `ComparisonSnapshot.kt:12-33`). Rename scanning and baseline blob loading consume that fork hash (`BaselinePathResolver.kt:22-52`; `BranchChangesProjectService.kt:420-450`).

```text
Workspace mapping
(root URL, current branch) -> selected base ref
                                  |
                                  v
                     resolve base tip + HEAD
                                  |
                     git merge-base --all
                                  |
                    exactly one fork hash
                                  |
             +--------------------+--------------------+
             |                                         |
       rename scan                              baseline blob
    fork tree -> index                         fork:path content
             |                                         |
             +--------------------+--------------------+
                                  v
                    baseline vs live Document
                                  |
                       markers + full diff
```

Status widget is text-only even though its existing `MultipleTextValuesPresentation` supports `getIcon()`. Popup currently contains `Select/Change base...`, separator, and enable/disable action (`BranchChangesStatusBarWidget.kt:14-106`). Marker and diff wording hard-code `fork` (`BranchHighlightRenderer.kt:61-81`; `ShowBranchDiffAction.kt:25-40`; `BranchChangesBundle.properties:42`). No icon resources exist.

Current test command passed before planning:

```text
./gradlew test
BUILD SUCCESSFUL
```

## Desired End State

```text
Workspace mapping
(root URL, current branch) -> { selected base ref, comparison mode }
                                      |
                         resolve base tip + HEAD
                                      |
                    +-----------------+-----------------+
                    |                                   |
             mode=Fork point                    mode=Branch HEAD
          merge-base --all(base, HEAD)             baseline=base tip
          require exactly one baseline           no merge-base needed
                    |                                   |
                    +-----------------+-----------------+
                                      v
                             baseline commit
                                      |
                     rename scan + baseline blob
                                      |
                         baseline vs live Document
                                      |
                   mode-aware markers + full diff
```

Status-bar shape:

```text
Fork point active:
[git-fork] Branch Changes: origin/main ▾

Branch HEAD active:
[git-compare-arrows] Branch Changes: origin/main ▾

Popup:
[git-fork]           Compare from Fork point (active)   [disabled]
[git-compare-arrows] Compare with Branch HEAD
------------------------------------------------------
                     Change base...
------------------------------------------------------
                     Disable highlighting
```

Unconfigured branch with Branch HEAD selected from popup:

```text
[git-fork] Branch Changes: Select base ▾
        |
        +-- click "Compare with Branch HEAD"
                |
                v
          existing base chooser
                |
        confirm origin/main
                |
                v
save { base=refs/remotes/origin/main, mode=BRANCH_HEAD }
```

## Abstractions and Code Reuse

- `src/main/kotlin/com/github/flobilosaurus/branchchanges/model/ComparisonMode.kt` — add stable mode enum, persisted IDs, display bundle keys, and baseline semantics identifier.
- `src/main/kotlin/com/github/flobilosaurus/branchchanges/settings/BranchBaseSettings.kt` — extend `BaseMappingState`; expose mode-aware configuration reads and atomic base/mode writes while preserving notification behavior.
- `src/main/kotlin/com/github/flobilosaurus/branchchanges/model/BranchContext.kt` — carry resolved configuration mode with base ref.
- `src/main/kotlin/com/github/flobilosaurus/branchchanges/model/ComparisonSnapshot.kt` — add mode and rename `forkHash` to mode-neutral `baselineHash` in comparison identity.
- `src/main/kotlin/com/github/flobilosaurus/branchchanges/git/BranchGitClient.kt` — branch baseline capture by mode while retaining sole native-Git boundary, limits, cancellation, and no-fetch environment.
- `src/main/kotlin/com/github/flobilosaurus/branchchanges/git/BaselinePathResolver.kt` — key and resolve rename map by mode-selected baseline hash rather than fork-specific hash.
- `src/main/kotlin/com/github/flobilosaurus/branchchanges/BranchChangesProjectService.kt` — coordinate mode selection, chooser fallback, refresh/cancellation, stale checks, rendering, and logging.
- `src/main/kotlin/com/github/flobilosaurus/branchchanges/ui/BranchChangesStatusBarWidget.kt` — reuse existing presentation/popup, add dynamic icon and mode actions.
- `src/main/kotlin/com/github/flobilosaurus/branchchanges/ui/BranchChangesIcons.kt` — load packaged SVGs through `IconLoader`.
- `src/main/kotlin/com/github/flobilosaurus/branchchanges/editor/BranchHighlightRenderer.kt` — produce mode-aware tooltip/accessibility wording.
- `src/main/kotlin/com/github/flobilosaurus/branchchanges/editor/ShowBranchDiffAction.kt` — produce mode-aware diff title and baseline label.
- `src/main/resources/icons/comparisonFork.svg` and `comparisonFork_dark.svg` — IntelliJ-adapted Lucide `git-fork`.
- `src/main/resources/icons/comparisonBranchHead.svg` and `comparisonBranchHead_dark.svg` — IntelliJ-adapted Lucide `git-compare-arrows`.
- `src/main/resources/META-INF/third-party/lucide-icons-ISC.txt` — packaged license and source provenance.
- `src/main/resources/messages/BranchChangesBundle.properties` — localized mode/action/status/marker/diff strings.
- `src/main/resources/META-INF/plugin.xml` and `README.md` — product and usage documentation.

Existing `BranchRefProvider`, `BranchBaseChooser`, `BranchLineComparator`, marker geometry, editor lifecycle, debounce/cancellation infrastructure, and Git environment hardening remain reused. No custom popup renderer or internal IntelliJ action API is needed.

## Logging & Observability

Keep existing IntelliJ `Logger`; add mode and neutral baseline hash to completion records:

```text
Branch Changes refresh complete: root=/repo branch=feature base=refs/remotes/origin/main mode=BRANCH_HEAD baseline=9abc123 generation=42 regions=7 gitMs=18 diffMs=4
```

Fork-specific unavailable records remain explicit:

```text
Branch Changes comparison unavailable: root=/repo path=src/App.kt mode=FORK_POINT reason=NO_MERGE_BASE
```

Mode changes use existing refresh-cause debug logging (`comparison-mode`) and settings topic. Logs continue excluding document/baseline content, credentials, and remote operations.

## Implementation

Dependencies: None.

Deliver both modes as one end-to-end vertical slice: persisted selection, Git baseline semantics, status-bar UX/icons, marker/diff wording, regression coverage, and documentation.

**Tasks**:

- [x] Add `model/ComparisonMode.kt` with `FORK_POINT` and `BRANCH_HEAD`, stable serialized IDs, migration parser defaulting missing/unknown values to `FORK_POINT`, and bundle-backed display/action wording hooks.
- [x] Extend `settings/BranchBaseSettings.kt` mapping state with persisted mode; introduce immutable mode-aware mapping/configuration reads and atomic `setConfiguration(root, branch, baseRef, mode)` behavior; keep `setBase` preserving mode and settings notifications firing once per effective change.
- [x] Update settings load normalization/deduplication so legacy mappings retain base refs and acquire `FORK_POINT`; malformed roots/branches/base refs still drop as today, while malformed mode alone falls back safely.
- [x] Propagate comparison mode through `BranchContext`, `ComparisonRequest`, `UiState`, and `ComparisonIdentity`; rename fork-only identity fields to mode-neutral baseline fields and include mode in equality/staleness identity.
- [x] Change `BranchGitOperations.capture()` and `CapturedGitIdentity` to accept mode and return `baselineHash`; preserve base-tip, current-HEAD, and index fingerprint capture for both modes.
- [x] Implement mode-specific capture in `BranchGitClient`: keep exact current `merge-base --all` validation for `FORK_POINT`; set `baselineHash = baseTipHash` and skip merge-base command for `BRANCH_HEAD`; keep cancellation and typed unavailable behavior.
- [x] Generalize `loadBaseline()`, `loadNameStatus()`, `BaselinePathResolver`, cache-key names, Git parameters, and related diagnostics from `forkHash` to `baselineHash`, ensuring rename comparison is `<baseline commit> -> current index` in either mode.
- [x] Update `BranchChangesProjectService.compute()` to feed mode-selected baseline through rename/blob/comparator pipeline and record mode/baseline in snapshot and timing log.
- [x] Add one centralized snapshot-current validator covering root, path, branch, base ref, mode, current `HEAD`, selected base tip, generation, document stamp, and available index identity; use it before result application, `onEditorCreated()` split-editor snapshot reuse, direct `showDiff()`, and pending-diff completion, scheduling recomputation whenever validity is uncertain.
- [x] On mode/base/branch/`HEAD`/selected-tip identity invalidation, clear each affected entry's cached result and owned markers before scheduling replacement work; retain current debounce behavior for ordinary document edits to avoid unnecessary marker flicker.
- [x] Add service mode-selection entry point used by status popup: when base exists, persist new mode and refresh all affected editors; when base is absent, invoke existing chooser and atomically save chosen ref plus requested mode only if root/branch context remains unchanged.
- [x] Preserve base chooser behavior so ordinary `Select/Change base...` updates only base ref and retains current mode; continue rejecting current local branch for both modes.
- [x] Add pinned Lucide `git-fork.svg` and `git-compare-arrows.svg` resources from commit `a79b2d131dab2bf20cb224bd0937b439a9c4fa99`; adapt 24×24 source geometry to IntelliJ 16×16 base sizing, provide light `#6C707E` and dark `#CED0D6` variants, and preserve recognizable stroke geometry.
- [x] Add `BranchChangesIcons.kt` using `IconLoader.getIcon()` with leading resource paths; avoid platform-internal icon classes and network/resource fetching.
- [x] Extend `BranchChangesStatusBarWidget.getIcon()` to resolve active mapping's mode, defaulting to fork icon for unconfigured/no-context states while preserving current status text.
- [x] Rebuild widget popup ordering with two icon-bearing mode actions, then existing base action and project toggle; label active mode with `(active)`, disable it, and disable/no-op mode mutation when current file lacks resolvable branch context.
- [x] Update widget tooltip state to name active mode and selected base, include abbreviated baseline hash only from a matching successful current-file snapshot, and preserve explicit pending/disabled/unavailable fallbacks.
- [x] Update `BranchHighlightRenderer` tooltip/accessibility text and `ShowBranchDiffAction` title/old-content label to distinguish `Fork point <baselineHash>` from `Branch HEAD <baselineHash>`; keep live-document/local-edit labels and renamed paths.
- [x] Add complete Lucide ISC text and pinned source provenance under `src/main/resources/META-INF/third-party/lucide-icons-ISC.txt`; verify resource is present inside built plugin artifact.
- [x] Extend `BranchBaseSettingsTest` with legacy-state migration, unknown-mode fallback, base/mode preservation, atomic configuration, root/branch isolation, round-trip, and notification-count coverage.
- [x] Extend Git integration tests with divergent histories proving fork baseline differs from selected Branch HEAD, selected-ref advancement changes Branch-HEAD baseline, Branch HEAD skips zero/multiple merge-base failures, and mode-selected baseline drives blob/rename lookup.
- [x] Extend service tests and fakes for mode-aware capture, mode-switch recomputation, old-mode cancellation, stale result rejection during apply/direct diff/pending diff, immediate split-editor opening after a mode switch, mode-preserving base changes, chooser cancellation/context drift, and external selected-tip movement.
- [x] Add blocked/unavailable replacement tests proving mode/base/ref identity changes immediately remove already-rendered old-baseline markers and cached snapshots until a matching replacement succeeds.
- [x] Extend UI platform tests for default fork icon, restored per-branch/root icons, two popup mode actions and their icons/order, `(active)` disabled state, immediate widget refresh, Branch-HEAD-without-base chooser flow, exact successful/pending tooltip content, disabled mapped/unmapped states, and no-file/non-Git action disablement plus settings non-mutation.
- [x] Extend renderer and full-diff tests with exact mode-specific tooltip/accessibility/title/content-label assertions and ensure no Branch-HEAD output says `fork`.
- [x] Extend read-only Git integration coverage to execute both capture modes and assert refs, index, object count, working tree, untracked files, and network state remain unchanged.
- [x] Update `BranchChangesBundle.properties` with localized mode names/actions/tooltips/diff labels and remove generic fork-only wording where mode-neutral behavior is required.
- [x] Update README first-use, persistence, status-widget, comparison semantics, marker/diff, rename, unsupported-state, and troubleshooting sections with both modes and the moving-local-ref/no-fetch distinction.
- [x] Add README third-party asset section naming both Lucide icons, pinned commit/source URLs, 16×16/color adaptations, ISC license, and packaged notice location.
- [x] Update `plugin.xml` description/change notes to mention selectable Fork point/Branch HEAD baselines without changing supported IDE bounds or promising network fetches.

**Automated Verification**:

- [x] `./gradlew clean test` passes settings migration, Git semantics, service cancellation/staleness, widget action/icon, renderer, diff, and repository read-only tests.
- [x] Automated Git command spy or integration assertions prove `BRANCH_HEAD` issues no merge-base command while `FORK_POINT` still uses `merge-base --all`.
- [x] Automated divergent-history fixture proves same selected base/document yields different expected baseline hashes and contents between modes.
- [x] Automated stale-generation test proves a delayed Fork-point result cannot replace a newer Branch-HEAD result, and vice versa; blocked or failed replacement leaves no old-mode markers visible.
- [x] `./gradlew verifyPluginProjectConfiguration verifyPluginStructure` passes without actionable configuration or structure warnings.
- [x] `./gradlew verifyPlugin` passes configured IDEA 2024.3 Community/Ultimate and IDEA 2026.2.2 compatibility matrix.
- [x] `./gradlew buildPlugin` produces installable ZIP containing all four SVG variants and `META-INF/third-party/lucide-icons-ISC.txt` with no external icon dependency.

**Manual Verification**:

- [ ] Run `./gradlew runIde`; on a feature branch whose selected `main` advanced after divergence, confirm Fork point shows feature-since-divergence changes while Branch HEAD also reflects selected branch's newer content.
- [ ] Open status popup in light and dark themes; confirm widget/action icons are distinct, aligned, legible, and switch immediately; confirm active action says `(active)` and cannot be invoked.
- [ ] On an unconfigured branch, choose Branch HEAD from popup, cancel chooser, and confirm nothing changes; repeat and confirm a base, then verify base and Branch-HEAD mode save together.
- [ ] Switch branches and Git roots, restart sandbox IDE, and confirm each configured mode/base pair restores correctly.
- [ ] Advance selected branch ref externally without fetching through plugin; confirm Branch HEAD recomputes against new tip and Fork point retains merge-base semantics.
- [ ] Click added, modified, and deleted markers in both modes; confirm tooltip/accessibility wording and full-diff title/baseline label report correct mode, commit, paths, and live-document scope.

## Implementation Notes

- IntelliJ action template presentations reject direct enabled-state mutation. Mode actions set text/enabled state through `update()`; UI tests exercise copied event presentations.
- Cached split-editor and direct-diff reuse now performs fresh background index fingerprint validation. Result application performs another index read after computation to close stale index/rename windows before EDT rendering and pending-diff completion.
- Unavailable results carry configuration identity after application; any absent/mismatched identity is cleared before replacement scheduling so old Fork-point failures cannot appear under Branch HEAD.
- Plugin Verifier 1.410 reports compatible for IC/IU 243.21565.193 and IU 262.10315.125. Existing cross-version deprecated/experimental API notices remain; no compatibility errors.
- Lucide source geometry remained recognizable at 16×16 without path edits; only rendered size and required light/dark stroke colors changed.

During implementation, record user feedback, unexpected IntelliJ/Git4Idea API differences, SVG rendering adjustments, test fixture limitations, and deviations here. Resolve every deviation before marking plan completed.

## References

- Existing implementation plan: `docs/agents/plans/2026-09-11-branch-change-highlighting.md`
- Current user documentation: `README.md`
- IntelliJ Platform SDK — Working with Icons: https://plugins.jetbrains.com/docs/intellij/icons.html
- IntelliJ `StatusBarWidget.MultipleTextValuesPresentation`: `platform/platform-api/src/com/intellij/openapi/wm/StatusBarWidget.kt`
- IntelliJ `DumbAwareAction`: `platform/editor-ui-api/src/com/intellij/openapi/project/DumbAwareAction.java`
- Git merge-base documentation: https://git-scm.com/docs/git-merge-base
- Git diff documentation: https://git-scm.com/docs/git-diff
- Lucide `git-fork` pinned source: https://raw.githubusercontent.com/lucide-icons/lucide/a79b2d131dab2bf20cb224bd0937b439a9c4fa99/icons/git-fork.svg
- Lucide `git-compare-arrows` pinned source: https://raw.githubusercontent.com/lucide-icons/lucide/a79b2d131dab2bf20cb224bd0937b439a9c4fa99/icons/git-compare-arrows.svg
- Lucide pinned ISC license: https://raw.githubusercontent.com/lucide-icons/lucide/a79b2d131dab2bf20cb224bd0937b439a9c4fa99/LICENSE
