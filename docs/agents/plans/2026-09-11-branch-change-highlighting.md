---
date: 2026-09-11T08:08:02.490720+00:00
git_commit: ""
branch: ""
repository: branch-changes-intellij-plugin
topic: "Branch-relative editor change highlighting"
tags: [plan, intellij-plugin, git, editor, highlighting]
status: draft
---

# PLAN: Branch-Relative Editor Change Highlighting

Build **Branch Changes**, an IntelliJ IDEA plugin that highlights lines changed between a branch's fork point and the live editor document. Users explicitly select a comparison base for each current branch and Git root. Plugin renders added, modified, and deleted regions without replacing IntelliJ's existing HEAD-relative VCS markers.

Repository is currently empty and is not initialized as a Git repository. Plan therefore includes complete plugin scaffold, behavior, tests, and user documentation. Marketplace publishing, signing, Remote Development, and automatic remote fetches are outside scope.

## Acceptance Criteria

### Plugin and compatibility

- [ ] Plugin uses display name `Branch Changes`, plugin ID/package namespace `com.github.flobilosaurus.branchchanges`, Kotlin, Gradle Kotlin DSL, and IntelliJ Platform Gradle Plugin 2.x.
- [ ] Plugin declares IntelliJ IDEA Community and Ultimate 2024.3 as minimum, records latest stable release validated during implementation as maximum supported release, and declares bundled `Git4Idea` dependency; later releases remain best-effort until verified.
- [ ] Build produces locally installable plugin ZIP without requiring publishing or signing credentials.
- [ ] Plugin Verifier reports no compatibility problems for IDEA 2024.3 and recorded maximum supported Community/Ultimate releases.

### Base selection and persistence

- [ ] Plugin stores one explicit base-ref mapping per `(Git root, current local branch)` in workspace-local IntelliJ state.
- [ ] First encounter with an unconfigured branch shows an editor notification containing a `Select base` action.
- [ ] Base picker suggests remote default branch when locally known, but requires explicit user confirmation and never selects silently.
- [ ] Base picker lists locally known local and remote refs, validates selected ref, and rejects only current local branch ref itself; distinct refs sharing same tip remain valid.
- [ ] Multi-root projects resolve and persist base mappings independently.
- [ ] Status-bar widget shows current editor's selected base or a clear `Select base`, `Disabled`, or `Unavailable` state.
- [ ] Status-bar popup allows changing base and toggling highlighting for whole project while preserving saved mappings.
- [ ] Branch checkout restores that branch's mapping and refreshes all affected open editors.
- [ ] Plugin never fetches remotes, disables partial-clone lazy object fetching for every Git command, and performs no Git operation that modifies object database, refs, index, or working tree.

### Comparison semantics

- [ ] Baseline commit is sole merge-base of captured selected base tip and captured current `HEAD`; histories with zero or multiple best merge-bases are reported unavailable rather than choosing arbitrarily.
- [ ] Baseline file text is compared directly with current live `Document`, so results include committed, staged, unstaged, and unsaved net changes while remaining aligned with visible lines.
- [ ] Added blocks are identified when baseline range is empty and current range is non-empty.
- [ ] Modified blocks are identified when both baseline and current ranges are non-empty.
- [ ] Deleted blocks are identified when current range is empty and retain an anchor at beginning, between lines, or EOF.
- [ ] New and untracked non-ignored text files render every current line as added; empty new files produce no line marker.
- [ ] Files Git detects as renames between merge-base and index at native default similarity threshold are mapped to their baseline path, covering committed and staged renames.
- [ ] Pure committed/staged renames produce no changed-line markers; renamed-and-edited files detected above Git's similarity threshold show only net edited regions, while lower-similarity delete/add results follow new-file behavior.
- [ ] Unstaged filesystem moves are treated as new files until staged, and this limitation is documented.

### Editor behavior and UX

- [ ] Added, modified, and deleted regions use distinct, theme-aware, user-configurable color-scheme keys.
- [ ] Added and modified regions render both subtle whole-line backgrounds and gutter stripes.
- [ ] Deleted-only regions render gutter anchors because no current line exists to receive a background.
- [ ] Gutter geometry scales with IDE UI scale and coexists with built-in VCS markers.
- [ ] Marker tooltip states change type, selected base, fork commit abbreviation, and that comparison includes local edits.
- [ ] Marker accessibility text communicates same essential information as tooltip.
- [ ] Clicking any gutter marker opens IntelliJ full-file diff from fork-baseline content to current live document.
- [ ] Disabling project toggle immediately removes owned markers; reenabling recomputes them.
- [ ] Plugin removes only highlighters it owns and clears them when editor, file, project, or plugin is disposed.

### Refresh, performance, and failures

- [ ] File open, document edit, branch/ref change, base change, and enable-state change trigger coalesced recomputation.
- [ ] Git I/O and line comparison run off EDT; editor rendering runs on EDT.
- [ ] Each result carries repository revision/base identity, read-only index fingerprint, normalized file path, document modification stamp, and generation; stale or cancelled results never replace current markers.
- [ ] Rapid edits cancel or supersede prior work without UI freezes or marker flicker.
- [ ] Plugin skips binary files, ignored files, files larger than 2 MiB, and documents over 10,000 lines with a non-modal explanatory state.
- [ ] Detached HEAD, unborn branch, missing base ref, missing merge-base, unrelated history, malformed Git output, `DiffTooBigException`, and disposed/invalid editor state clear stale markers and remain recoverable.
- [ ] Git failures are logged without file contents and surfaced through concise non-modal UI rather than repeated dialogs.

### Documentation

- [ ] README explains installation, first-use setup, status widget, colors, marker click behavior, project toggle, live-document semantics, and local-only base freshness.
- [ ] README documents supported IDE versions, multi-root behavior, performance limits, staged/committed rename support, unstaged-rename limitation, and troubleshooting states.
- [ ] Plugin description and change notes accurately describe MVP scope without promising Marketplace publication or Remote Development support.

## Technical Key Decisions and Tradeoffs

1. **Explicit branch-specific base:** Persist `(Git root, current local branch) -> base ref` rather than infer source branch.
   - Why: Git stores commit ancestry, not canonical parent-branch identity. Upstream commonly points to remote copy of feature branch.
   - Impact: First use requires confirmation; status widget and notification make selection discoverable.

2. **Suggested remote default branch:** Use locally available remote `HEAD` only as picker preselection.
   - Why: Good common default without silently asserting historical origin.
   - Impact: Missing/stale remote `HEAD` is harmless; user can pick any known ref.

3. **Moving base ref plus unambiguous merge-base:** Resolve selected ref and `HEAD` to object IDs, run merge-base with all-result detection, and proceed only when exactly one best merge-base exists.
   - Why: Matches normal three-dot pull-request semantics, avoids inconsistent reads if refs move, and prevents arbitrary baselines in criss-cross histories.
   - Impact: Ref updates recompute baseline; zero/multiple merge-base results become recoverable unavailable states; no network fetch occurs.

4. **Live document as target:** Compare baseline text directly with immutable snapshot of editor `Document`.
   - Why: Marker coordinates match visible text and include all net work on branch.
   - Impact: UI and docs must say local/unsaved edits are included; built-in gutter still separately communicates HEAD-relative changes.

5. **IntelliJ diff engine:** Use `ComparisonManager.compareLines()` and current-side half-open ranges instead of parsing unified patches for line classification.
   - Why: Avoids patch quoting, newline, and hunk-coordinate bugs and integrates cancellation/size limits.
   - Impact: Deletions need explicit zero-width anchor model.

6. **Git4Idea command infrastructure behind one adapter:** Resolve repositories through `GitRepositoryManager`; execute read-only merge-base, rename-status, and blob commands through Git4Idea handlers.
   - Why: Honors IDE-configured Git executable, cancellation, and repository environment.
   - Impact: Git4Idea APIs have weaker cross-release guarantees than core editor APIs; adapter isolation and Plugin Verifier reduce upgrade risk.

7. **Rename mapping against index:** Run whole-tree, NUL-delimited rename-aware comparison from fork commit to index, then filter destination path; key shared bounded cache by read-only SHA-256 index-file fingerprint and fork hash.
   - Why: Handles Git-detected committed/staged renames without destination-only pathspec errors while avoiding repeated expensive whole-tree scans.
   - Impact: Unstaged untracked destination and below-threshold rename candidates cannot be reliably paired with deleted source and remain new-file behavior. Index fingerprinting must never call `git write-tree`.

8. **Owned `RangeHighlighter` rendering:** Use public `MarkupModel`, `RangeHighlighter`, `TextAttributesKey`, and `ActiveGutterRenderer` APIs.
   - Why: Supports backgrounds, custom gutter paint, tooltips, accessibility, and click behavior without replacing internal `LineStatusTracker`.
   - Impact: Service must track and remove every owned highlighter; never call `removeAllHighlighters()`.

9. **Project service with cancellable coroutine work:** Stateless listeners delegate to project service owning editor registrations, jobs, caches, and highlighters.
   - Why: Project coroutine scope/disposal gives lifecycle safety and keeps listeners fast.
   - Impact: Every asynchronous apply step verifies generation, captured refs, document stamp, and object validity.

10. **Workspace-local configuration:** Store mappings and project toggle in workspace file.
    - Why: Remote names and branch ancestry differ between users/checkouts; repository should not receive IDE-state churn.
    - Impact: New checkout requires fresh explicit selections.

11. **Background and gutter together:** Added/modified blocks use whole-line background plus active gutter stripe; deletions use gutter anchor only.
    - Why: Meets visibility goal while retaining location and interaction affordance.
    - Impact: Colors must be subtle/theme-aware; custom gutter stripe may visually coexist with built-in VCS stripe.

12. **Bounded MVP:** Local IDEA from 2024.3 through latest stable release validated during implementation, 2 MiB/10,000-line limits, no Remote Development, no automatic fetch, no Marketplace pipeline.
    - Why: Keeps first release responsive and verifiable while avoiding frontend/backend split and credential workflows.
    - Impact: Exact validated maximum is recorded in build/docs; newer versions are best-effort until verifier coverage advances. Unsupported states are explicit and documented.

## Current State

```text
branch-changes-intellij-plugin/
└── (empty; no .git, build, source, tests, or documentation)
```

No existing code or project-local conventions can be reused. Plan follows current IntelliJ Platform Plugin SDK guidance and isolates less-stable Git4Idea integration.

## Desired End State

```text
Live editor/document events          Git repository/ref events
             |                                  |
             +----------------+-----------------+
                              v
                 BranchChangesProjectService
                 - debounce/cancel generations
                 - editor/highlighter ownership
                              |
                  capture short read snapshot
                              |
                              v
                    background coroutine
        +---------------------+---------------------+
        |                                           |
 BranchContextResolver                         BranchGitClient
 root/current branch                           base tip + HEAD
 saved base mapping                            merge-base
                                                rename mapping
                                                baseline blob
        +---------------------+---------------------+
                              v
                    BranchLineComparator
             baseline text vs live document text
                  -> added/modified/deleted
                              |
                  verify generation + stamp
                              |
                              v EDT
                   BranchHighlightRenderer
            background + gutter + deletion anchor
                              |
                    click -> full file diff
```

User-facing editor shape:

```text
Status bar:  Branch Changes: origin/main ▾

Plugin   VCS   Line  Editor
 gutter gutter
   ▌      ▎     18  added line       green background
   ▌            19  modified line    blue background
   ▲            20  next live line   red deletion anchor before line

Hover: Modified since fork with origin/main at a1b2c3d; includes local edits
Click: baseline-at-fork <-> current live document full diff
```

Unconfigured state:

```text
┌──────────────────────────────────────────────────────────────┐
│ Branch Changes needs a comparison base for feat/my-feature. │
│ [Select base…]                                               │
└──────────────────────────────────────────────────────────────┘
Status: Branch Changes: Select base
```

## Abstractions and Code Reuse

Platform abstractions reused:

- `GitRepositoryManager` / `GitRepository` for root, current branch, refs, and repository-change topic.
- Git4Idea command handlers for read-only native Git operations.
- `ComparisonManager` / `LineFragment` for cancellable line comparison.
- `EditorFactoryListener`, `DocumentListener`, and `FileEditorManagerListener` for lifecycle and refresh events.
- `MarkupModel`, `RangeHighlighter`, `TextAttributesKey`, and `ActiveGutterRenderer` for visual state and interaction.
- `DiffManager`, `DiffContentFactory`, and `SimpleDiffRequest` for full-file diff.
- `StatusBarWidgetFactory`, editor notification provider, and popup chooser APIs for configuration UX.
- Project service coroutine scope and IntelliJ disposer hierarchy for cancellation and cleanup.

Planned source tree:

```text
branch-changes-intellij-plugin/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/wrapper/*
├── README.md
└── src
    ├── main
    │   ├── kotlin/com/github/flobilosaurus/branchchanges
    │   │   ├── BranchChangesProjectActivity.kt
    │   │   ├── BranchChangesProjectService.kt
    │   │   ├── model
    │   │   │   ├── BranchContext.kt
    │   │   │   ├── BranchChangeRegion.kt
    │   │   │   └── ComparisonSnapshot.kt
    │   │   ├── settings
    │   │   │   └── BranchBaseSettings.kt
    │   │   ├── git
    │   │   │   ├── BranchContextResolver.kt
    │   │   │   ├── BranchGitClient.kt
    │   │   │   ├── BaselinePathResolver.kt
    │   │   │   └── NameStatusParser.kt
    │   │   ├── diff
    │   │   │   └── BranchLineComparator.kt
    │   │   ├── editor
    │   │   │   ├── BranchEditorFactoryListener.kt
    │   │   │   ├── BranchFileEditorManagerListener.kt
    │   │   │   ├── BranchHighlightRenderer.kt
    │   │   │   ├── BranchGutterRenderer.kt
    │   │   │   ├── BranchChangesColors.kt
    │   │   │   └── ShowBranchDiffAction.kt
    │   │   └── ui
    │   │       ├── BranchChangesStatusBarWidgetFactory.kt
    │   │       ├── BranchChangesStatusBarWidget.kt
    │   │       ├── BranchBaseChooser.kt
    │   │       └── BranchBaseEditorNotificationProvider.kt
    │   └── resources
    │       ├── META-INF/plugin.xml
    │       └── messages/BranchChangesBundle.properties
    └── test
        └── kotlin/com/github/flobilosaurus/branchchanges
            ├── settings/BranchBaseSettingsTest.kt
            ├── git/NameStatusParserTest.kt
            ├── git/BaselinePathResolverTest.kt
            ├── git/BranchGitClientIntegrationTest.kt
            ├── diff/BranchLineComparatorTest.kt
            ├── editor/BranchHighlightRendererTest.kt
            └── BranchChangesProjectServiceTest.kt
```

Responsibilities:

- `BranchBaseSettings`: workspace-persisted enable flag and root/branch/base mappings; no Git or UI behavior.
- `BranchContextResolver`: map `VirtualFile` to Git root, current local branch, captured HEAD, and configured base.
- `BranchGitClient`: sole wrapper around Git4Idea command APIs; return typed success/unavailable/failure results.
- `NameStatusParser`: parse NUL-delimited `--name-status -z -M` output without locale or whitespace assumptions.
- `BaselinePathResolver`: map current index path to merge-base path; direct path fallback; classify new/ignored/unsupported file.
- `BranchLineComparator`: convert `LineFragment` half-open ranges into added, modified, and zero-width deleted models.
- `BranchChangesProjectService`: coalesce events, own per-editor work/highlighters, reject stale results, publish widget/notification state.
- `BranchHighlightRenderer`: create/remove only plugin-owned range highlighters and resolve theme colors per editor scheme.
- `BranchGutterRenderer`: scaled paint, tooltip/accessibility, hit testing, and diff click delegation.
- `ShowBranchDiffAction`: open fork-baseline versus live-document diff from captured comparison result.

No code will be copied from JetBrains or Branch Lens implementations. Their sources serve only as API and UX references, avoiding source-license attribution obligations beyond normal dependency notices.

## Logging & Observability

Use `com.intellij.openapi.diagnostic.Logger` from service and Git adapter. No telemetry, editor content, baseline content, or secrets leave IDE.

Logging policy:

- Debug: refresh cause, root identifier, branch/base names, captured abbreviated hashes, generation, elapsed Git/diff time, region counts.
- Info: project toggle/base mapping changes only when useful for support.
- Warn: malformed Git output, missing merge-base, blob decode failure, unexpected renderer failure; coalesce repeated equivalent warnings.
- Expected unsupported states such as detached HEAD, ignored/binary/large files, and absent base mapping remain debug-level plus status/notification UI.

Example debug record:

```text
Branch Changes refresh complete: cause=document-change root=/repo branch=feat/my-feature base=origin/main fork=a1b2c3d generation=42 regions=7 gitMs=18 diffMs=4
```

Example warning:

```text
Branch Changes comparison unavailable: root=/repo branch=feat/my-feature base=origin/main reason=no-merge-base
```

## Implementation

### Phase 1: Runnable Plugin and Explicit Base Selection

Dependencies: None.

Deliver an installable plugin whose status widget and editor notification resolve current Git context, collect explicit per-branch base choices, persist them privately, and react to branch changes. No change markers appear yet.

**Tasks**:

- [x] Initialize Git repository if still absent, preserving generated plan, and add standard IntelliJ/Kotlin `.gitignore` entries without committing user-specific IDE files.
- [x] Create Gradle wrapper, `settings.gradle.kts`, `build.gradle.kts`, and `gradle.properties` using Kotlin, Java 21 toolchain, IntelliJ Platform Gradle Plugin 2.x, IDEA Community 2024.3 target, bundled `Git4Idea`, platform test framework, plugin verification configuration, and reproducible build settings.
- [x] Create `src/main/resources/META-INF/plugin.xml` with plugin ID/name/vendor/description, `since-build` for 2024.3, bounded supported build range, platform/VCS/Git4Idea dependencies, project activity, listeners, status-bar widget, and editor notification provider needed by Phase 1.
- [x] Add `messages/BranchChangesBundle.properties`; route user-facing strings through bundle keys for localization readiness.
- [x] Implement `settings/BranchBaseSettings.kt` as project-level `PersistentStateComponent` stored in `StoragePathMacros.WORKSPACE_FILE`, keyed by normalized Git root URL and current local branch name, with project-wide enabled flag and migration-safe default state.
- [x] Implement `git/BranchContextResolver.kt` using `GitRepositoryManager.getRepositoryForFileQuick()`, rejecting non-Git files, detached/unborn HEAD, and missing local branch while returning typed context for supported files.
- [x] Implement local branch/ref discovery and default-remote suggestion through repository snapshots/read-only Git4Idea calls; require selected ref to resolve to commit, reject only exact current local branch ref, and permit a distinct ref currently pointing to same commit.
- [x] Implement `ui/BranchBaseChooser.kt` with searchable local/remote ref list, preselected locally known remote default branch, explicit confirmation, invalid-ref feedback, and no fetch behavior.
- [x] Implement `ui/BranchChangesStatusBarWidgetFactory.kt` and `BranchChangesStatusBarWidget.kt` with current-editor-aware `base`, `Select base`, `Disabled`, and `Unavailable` states plus popup actions for base selection and project toggle.
- [x] Implement `ui/BranchBaseEditorNotificationProvider.kt` for supported open Git text files lacking base mapping, with `Select base` action and invalid-base recovery state; suppress configuration nag while project highlighting is disabled.
- [x] Implement stateless `BranchEditorFactoryListener.kt`, `BranchFileEditorManagerListener.kt`, and Git repository topic subscription that delegate branch/editor changes to project service without doing I/O in callbacks.
- [x] Implement minimal `BranchChangesProjectActivity.kt` and `BranchChangesProjectService.kt` lifecycle to initialize listeners, expose observable UI state, refresh widget/notifications, and dispose all registrations through project scope.
- [x] Add `settings/BranchBaseSettingsTest.kt` for mapping isolation by root/branch, state round-trip, defaults, enable toggle, stale entries, and root normalization.
- [x] Add platform tests for context resolution, default-branch suggestion, explicit-confirmation behavior, invalid-ref rejection, exact-current-ref rejection, equal-tip distinct-ref acceptance, multi-root selection, branch-switch restoration, widget state, notification visibility, and project disposal.
- [x] Add command-spy/integration assertion proving base discovery and selection perform no fetch and leave refs, index, working tree, object count, and status unchanged.
- [x] Add README sections for requirements, development setup, sandbox launch, first-use base selection, branch-specific persistence, local-ref freshness, project toggle, and explicit non-goals.

**Automated Verification**:

- [x] `./gradlew test` passes settings, context, widget, notification, multi-root, and lifecycle tests.
- [x] `./gradlew verifyPluginProjectConfiguration verifyPluginStructure` passes with no deprecated/incompatible configuration failures.
- [x] `./gradlew buildPlugin` creates installable ZIP under `build/distributions/`.

**Manual Verification**:

- [ ] Launch `./gradlew runIde`, open Git project on unconfigured feature branch, and confirm notification plus `Select base` widget state appear without UI freeze or network/fetch activity.
- [ ] Select suggested `origin/main`, confirm widget updates only after confirmation, restart sandbox IDE, and confirm mapping persists.
- [ ] Switch between two branches and two Git roots with different mappings; confirm widget follows active editor/root/branch and restores correct selection.
- [ ] Disable project highlighting from widget; confirm notification disappears and mapping remains after reenabling.

### Phase 2: Live Added, Modified, and Deleted Highlighting

Dependencies: Phase 1.

Deliver core end-to-end feature for unchanged paths and new/untracked files: fork-baseline resolution, live-document comparison, cancellable refresh, theme-aware background/gutter rendering, and deletion anchors.

**Tasks**:

- [ ] Define immutable `model/BranchContext.kt`, `ComparisonSnapshot.kt`, and `BranchChangeRegion.kt` models containing normalized root/path, branch/base refs and captured hashes, document stamp/generation, baseline/current text metadata, half-open ranges, deletion anchors, and typed unavailable reasons.
- [ ] Implement `git/BranchGitClient.kt` as only Git4Idea integration boundary for read-only ref resolution, merge-base, baseline blob loading, tracked/ignored checks, command cancellation, NUL-safe output, charset-aware decoding, and typed error mapping; inject `GIT_NO_LAZY_FETCH=1` and `GIT_TERMINAL_PROMPT=0` into every command.
- [ ] Capture base tip, `HEAD` object ID, and SHA-256 fingerprint of repository's actual index file before merge-base/blob work; invoke all-result merge-base detection and continue only for exactly one best merge-base so one comparison generation never mixes moving-ref/index states or chooses an ambiguous baseline.
- [ ] Normalize baseline line separators to `\n`, decode using current file charset when valid, detect NUL/binary content, and avoid logging content.
- [ ] Implement direct baseline-path behavior: load merge-base blob at current repository-relative path when present; use empty baseline for new/untracked non-ignored text files; skip ignored files.
- [ ] Implement 2 MiB and 10,000-line preflight guards before expensive comparison and map `DiffTooBigException` to typed unavailable state.
- [ ] Implement `diff/BranchLineComparator.kt` with `ComparisonManager.compareLines()` and `ComparisonPolicy.DEFAULT`, mapping `LineFragment.startLine2/endLine2` into added/modified ranges and zero-width deleted anchors, including beginning/EOF boundaries and final-line-without-newline cases.
- [ ] Expand `BranchChangesProjectService.kt` to register supported editors/documents, debounce rapid edits, maintain one cancellable computation/generation per `(Document, Git root, current branch, base ref)` context, run Git/diff work off EDT, and fan one validated result out to each editor displaying that document.
- [ ] Retain highlighters per editor while maintaining reference-counted per-document listeners/computations so split editors refresh together without duplicate Git jobs or leaked listeners.
- [ ] Before EDT apply, verify project/editor/file validity, normalized current file path, branch/base/hash identity, captured index fingerprint/generation, and document stamp; discard work when index changes during computation or before queued apply.
- [ ] Subscribe refresh invalidation to file open/close, document change, VFS rename/move, Git repository/ref or index update, branch checkout, base mapping change, project toggle, color-scheme change, dynamic plugin unload, and project disposal.
- [ ] Implement `editor/BranchChangesColors.kt` with configurable color-scheme descriptors and theme-aware defaults for added, modified, and deleted states; register its color settings extension in `plugin.xml` only after class exists, and avoid hard-coded final runtime colors.
- [ ] Implement `editor/BranchHighlightRenderer.kt` to atomically replace only plugin-owned highlighters, use `LINES_IN_RANGE` backgrounds for added/modified blocks, and retain highlighter registry per editor.
- [ ] Implement `editor/BranchGutterRenderer.kt` as accessible `ActiveGutterRenderer` with UI-scaled added/modified stripes and deletion triangles/anchors, robust beginning/EOF geometry, region tooltip, and placeholder click delegation for Phase 3.
- [ ] Ensure project disable, unsupported state, invalid ref, branch change, editor release, and disposal cancel work and immediately clear only owned highlighters.
- [ ] Surface skipped/error state through status widget tooltip or editor notification without repeated modal dialogs; preserve actionable `Select base` recovery for missing/invalid mappings.
- [ ] Add `diff/BranchLineComparatorTest.kt` covering equal, insertion, replacement, unequal replacement, deletion at start/middle/EOF, empty/new file, blank lines, CRLF normalization, trailing newline, and large-diff cancellation.
- [ ] Add `editor/BranchHighlightRendererTest.kt` covering color keys, line ranges, deletion anchors, tooltip/accessibility text, split editors, atomic replacement, coexistence with unrelated highlighters, toggle cleanup, and disposal.
- [ ] Add service/platform tests using controllable fake Git adapter for debounce, cancellation, out-of-order completion, stale document stamp/path/index fingerprint, concurrent staging, VFS rename/move, branch/base changes, split-editor computation deduplication, unsupported states, dynamic unload cleanup, and no Git/diff work on EDT.
- [ ] Add Git-backed integration tests for sole merge-base selection, unchanged/modified/new/untracked/ignored files, unsaved document edits, staged and unstaged changes, missing base, detached HEAD, unrelated history, synthetic criss-cross history with multiple best merge-bases, and partial-clone missing-object behavior with lazy fetch disabled.
- [ ] Update README with fork-baseline/live-document semantics, visual legend, marker tooltip, interaction placeholder, refresh behavior, project toggle, limits, unsupported states, and distinction from IntelliJ's HEAD-relative VCS gutter.

**Automated Verification**:

- [ ] `./gradlew test` passes pure comparator, renderer, service, and native-Git integration suites.
- [ ] Threading tests/assertions confirm Git and comparison work do not execute on EDT and stale generations/path identities cannot install markers.
- [ ] Highlighter ownership and dynamic-unload tests confirm plugin cleanup leaves unrelated editor highlighters untouched.
- [ ] `./gradlew verifyPluginProjectConfiguration verifyPluginStructure buildPlugin` passes and produces updated ZIP.

**Manual Verification**:

- [ ] In sandbox IDE, compare feature branch against `origin/main`; confirm added lines show green background/stripe, modified lines blue background/stripe, and pure deletions red gutter anchor.
- [ ] Type without saving and confirm markers update after debounce, remain aligned with visible lines, and do not flicker under rapid edits.
- [ ] Confirm built-in VCS gutter markers remain functional and visually distinguishable beside Branch Changes markers.
- [ ] Change editor color scheme and confirm all plugin colors update; customize Branch Changes color descriptors and confirm editor refresh.
- [ ] Repeat marker check at 100% and 200% UI scale; confirm stripes/anchors remain aligned, clickable, and do not cover line numbers.
- [ ] Open binary, ignored, oversized, detached-HEAD, and missing-base cases; confirm no stale markers and concise non-modal explanation.

### Phase 3: Rename Awareness, Full Diff, and Release Hardening

Dependencies: Phase 2.

Complete chosen MVP semantics by resolving committed/staged renames, making gutter markers actionable, hardening Git/parser boundaries, and verifying installable artifact across supported IDE versions.

**Tasks**:

- [ ] Implement `git/NameStatusParser.kt` for NUL-delimited `git diff --cached --name-status -z -M <fork>` records, including add/modify/delete, `R100` through `R001`, unusual whitespace/newlines in paths, malformed/truncated records, and deterministic typed failures.
- [ ] Implement `git/BaselinePathResolver.kt` to run whole-tree rename detection, locate record whose destination equals current index path, map it to merge-base source path, compose direct-path fallback, and avoid destination-only pathspec behavior.
- [ ] Implement project-shared bounded LRU rename-map cache keyed by `(normalized root, fork hash, SHA-256 index-file fingerprint, rename threshold)`, populated once per key and invalidated on index/ref changes; cap at eight entries and never use `git write-tree` or another object-creating command for identity.
- [ ] Extend baseline loading so pure committed/staged Git-detected rename compares source blob with current document, while below-threshold/absent mapping retains new-file behavior and unstaged move remains intentionally unsupported.
- [ ] Add `ShowBranchDiffAction.kt` using `DiffContentFactory`, `SimpleDiffRequest`, and `DiffManager` to compare captured fork-baseline content/path against current live document, with titles identifying base, fork hash, and local-edit scope.
- [ ] Wire `BranchGutterRenderer.doAction()` and hit testing to open full diff for added, modified, and deleted markers; regenerate snapshot if captured data is stale before opening.
- [ ] Ensure diff action handles new files with empty baseline, renamed files with old/new path labels, disposed documents, invalid base refs, and Git failures without modal error loops.
- [ ] Harden Git command execution with cancellation, timeout/size bounds, fixed machine-readable options, no shell interpolation, NUL parsing, `GIT_NO_LAZY_FETCH=1`, `GIT_TERMINAL_PROMPT=0`, and distinction between expected Git diff exit status and actual failure/missing promised objects.
- [ ] Add warning deduplication and timing/region-count debug logs; verify logs exclude file/baseline contents and authentication data.
- [ ] Add `NameStatusParserTest.kt` and `BaselinePathResolverTest.kt` for committed rename, staged rename, rename plus edit, pure rename, low-similarity add/delete fallback, spaces/newlines/non-ASCII paths, copies treated as additions, malformed output, and cache invalidation.
- [ ] Extend native-Git integration tests to prove fork-to-index rename detection, pure-rename zero markers, above-threshold rename-plus-edit regions, below-threshold add/delete fallback, staged rename, unstaged move all-lines-added limitation, cache reuse/invalidation, and concurrent index-change rejection.
- [ ] Snapshot refs, index checksum, object count, worktree status, and untracked files before/after Git adapter integration tests; assert every plugin Git operation is repository-read-only and partial-clone missing objects cause unavailable state without remote contact.
- [ ] Add platform tests for marker-click full diff content/title, deletion-anchor click, new-file empty baseline, renamed path labels, stale snapshot refresh, and disposed editor safety.
- [ ] Finalize README usage/troubleshooting, color legend, marker click flow, Git similarity-threshold rename semantics, no-fetch/lazy-fetch policy, performance bounds, logs, local ZIP installation, supported IDEs, and deferred Marketplace/Remote Development scope.
- [ ] Finalize `plugin.xml` description/change notes and generated plugin metadata so installed plugin accurately advertises MVP behavior and compatibility.
- [ ] Configure Plugin Verifier matrix for IDEA 2024.3 and latest stable Community/Ultimate release available during implementation; record exact maximum supported build in `gradle.properties`, `plugin.xml`, and README, and isolate or replace Git4Idea calls rejected by verifier.

**Automated Verification**:

- [ ] `./gradlew clean test` passes all unit, platform, and native-Git integration tests from clean checkout.
- [ ] `./gradlew verifyPluginProjectConfiguration verifyPluginStructure` passes without configuration warnings requiring action.
- [ ] `./gradlew verifyPlugin` passes configured IDEA 2024.3/latest compatibility matrix without invalid API, missing dependency, or binary compatibility errors.
- [ ] `./gradlew buildPlugin` produces installable `Branch Changes` ZIP under `build/distributions/`.
- [ ] Install artifact contents contain plugin classes/resources and exclude test fixtures, local IDE state, repository fixtures, credentials, and build-only dependencies.

**Manual Verification**:

- [ ] Install generated ZIP into clean IntelliJ IDEA 2024.3 sandbox and latest available IDEA; confirm plugin loads, configures base, renders markers, and opens diff.
- [ ] Commit rename, stage rename, and perform unstaged filesystem move; confirm respectively zero/edit-only markers, zero/edit-only markers, and documented all-lines-added behavior.
- [ ] Click added, modified, and deleted markers; confirm full diff uses fork baseline, live document content, correct old/new path labels, and local-edit wording.
- [ ] Exercise rapid typing, branch checkout, base change, Git fetch performed externally, split editors, project disable/enable, file close, and project close; confirm responsive UI and no stale/leaked markers.
- [ ] Install ZIP through `Settings | Plugins | Install Plugin from Disk`, restart IDE, and confirm workspace mappings and project toggle survive as designed.
- [ ] Dynamically disable/unload plugin where IDE permits; confirm all Branch Changes markers, listeners, jobs, notifications, and widget instances disappear while unrelated VCS markers remain.

## Implementation Notes

- IntelliJ Platform Gradle Plugin 2.18.1 reports its generic recommendation to remove `until-build`; retained intentional `262.*` bound required by this approved plan. Project configuration and structure checks pass.
- Phase 1 targets IDEA Community 2024.3 (build line 243) and records IDEA 2026.2.2 (build line 262) as current validated maximum pending Phase 3 Plugin Verifier execution.
- Phase 1 manual run found status-bar widgets may be constructed off EDT. Active-editor lookup now uses an EDT-captured snapshot; refresh requests from background callbacks are deferred to EDT. Regression test covers off-EDT widget construction.
- Initial IDEA 2024.3 build `243.21565.193` cannot parse Java 25/26 entries now served by JetBrains' live Gradle compatibility matrix and persists the malformed cache. Development/test target moved within the same supported build line to maintenance release 2024.3.7.1 (`243.28141.41`), whose `JavaVersion.parse` supports both values; minimum `since-build=243` and verifier coverage for initial 2024.3 remain unchanged.
- [ ] During implementation, record user feedback, unexpected IntelliJ/Git4Idea API differences, tested IDE build numbers, verifier findings, performance measurements, and deviations here.
- [ ] Before marking implementation complete, resolve every recorded deviation by updating implementation or revising and re-approving plan; leave no open decisions.

## References

- IntelliJ Platform SDK — Editors: https://plugins.jetbrains.com/docs/intellij/editors.html
- IntelliJ Platform SDK — Documents: https://plugins.jetbrains.com/docs/intellij/documents.html
- IntelliJ Platform SDK — Listeners: https://plugins.jetbrains.com/docs/intellij/plugin-listeners.html
- IntelliJ Platform SDK — Threading Model: https://plugins.jetbrains.com/docs/intellij/threading-model.html
- IntelliJ Platform SDK — Services: https://plugins.jetbrains.com/docs/intellij/plugin-services.html
- IntelliJ Platform SDK — Disposer and Disposable: https://plugins.jetbrains.com/docs/intellij/disposers.html
- IntelliJ Platform SDK — Testing Plugins: https://plugins.jetbrains.com/docs/intellij/testing-plugins.html
- IntelliJ Platform SDK — IntelliJ Platform Gradle Plugin 2.x: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
- IntelliJ Platform SDK — Gradle tasks: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html
- IntelliJ source — `ComparisonManager`: https://github.com/JetBrains/intellij-community/blob/master/platform/diff-api/src/com/intellij/diff/comparison/ComparisonManager.java
- IntelliJ source — `MarkupModel`: https://github.com/JetBrains/intellij-community/blob/master/platform/editor-ui-api/src/com/intellij/openapi/editor/markup/MarkupModel.java
- IntelliJ source — `RangeHighlighter`: https://github.com/JetBrains/intellij-community/blob/master/platform/editor-ui-api/src/com/intellij/openapi/editor/markup/RangeHighlighter.java
- IntelliJ source — `ActiveGutterRenderer`: https://github.com/JetBrains/intellij-community/blob/master/platform/editor-ui-api/src/com/intellij/openapi/editor/markup/ActiveGutterRenderer.java
- IntelliJ source — `GitRepository`: https://github.com/JetBrains/intellij-community/blob/master/plugins/git4idea/backend/src/repo/GitRepository.java
- IntelliJ source — `GitHistoryUtils`: https://github.com/JetBrains/intellij-community/blob/master/plugins/git4idea/backend/src/history/GitHistoryUtils.java
- IntelliJ source — `GitChangeUtils`: https://github.com/JetBrains/intellij-community/blob/master/plugins/git4idea/backend/src/changes/GitChangeUtils.java
- Git documentation — merge-base: https://git-scm.com/docs/git-merge-base
- Git documentation — diff and three-dot semantics: https://git-scm.com/docs/git-diff
- Git documentation — rename detection: https://git-scm.com/docs/gitdiffcore
- JetBrains IntelliJ Platform Plugin Template: https://github.com/JetBrains/intellij-platform-plugin-template
- Branch Lens reference implementation (Apache-2.0; behavior reference only): https://github.com/samoylenkodmitry/difftrack
