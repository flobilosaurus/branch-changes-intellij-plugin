# Branch Changes

Branch Changes is an IntelliJ IDEA plugin for branch-relative editor change highlighting. Each Git root and local branch gets an explicitly selected comparison base and comparison mode. This avoids guessing branch ancestry from Git metadata while supporting both fork-relative and selected-branch-tip comparisons.

> Development status: selectable comparison modes, base selection, live branch-relative markers, rename awareness, and marker-click full diff are implemented.

## Requirements

- IntelliJ IDEA Community or Ultimate 2024.3 or newer
- Validated maximum: IntelliJ IDEA 2026.2.2 (`262.*`)
- Git repository with a local branch and at least one commit
- Java 21 for plugin development

Releases newer than the validated maximum are unsupported until compatibility is verified.

## Install from a local ZIP

Build the plugin:

```shell
./gradlew buildPlugin
```

Install the ZIP from `build/distributions/` using **Settings | Plugins | gear menu | Install Plugin from Disk**, then restart the IDE when prompted. Publishing and signing credentials are not needed.

## First use, base selection, and comparison mode

1. Open a text file in a Git repository on a local branch.
2. Find **Branch Changes: Select base** in the status bar, or use **Select base...** in the editor notification.
3. Choose a locally known local or remote branch.
4. Confirm the choice explicitly.
5. Open the status popup to switch between **Compare from Fork point** and **Compare with Branch HEAD**.

**Fork point** is the default. Existing workspace mappings created by older plugin versions also migrate to Fork point without losing their selected bases. Selecting **Branch HEAD** before configuring a base opens the same chooser; cancellation changes nothing, while confirmation saves base and mode together.

When available, the remote's locally recorded default branch (commonly `origin/main`) is preselected as a suggestion. It is never saved until confirmation. Current local branch itself is rejected in both modes, but another ref pointing to the same commit remains valid.

## Branch-specific, multi-root persistence

Base and mode are stored together per `(normalized Git root, current local branch)` in IntelliJ workspace state. They are not written to tracked project files. Switching branches or Git roots restores that mapping independently. Projects with multiple Git roots keep independent mappings, and status widget follows active editor's root and branch. Changing base preserves mode; changing mode preserves base.

## Status widget and project toggle

Status values:

- **Branch Changes: _base_** — selected base for the active editor
- **Branch Changes: Select base** — supported branch has no mapping
- **Branch Changes: Disabled** — highlighting is disabled for this project
- **Branch Changes: Unavailable** — active editor has no usable branch context or its saved base is invalid

Widget icon shows active mode: Lucide `git-fork` for Fork point and `git-compare-arrows` for Branch HEAD. Popup lists both mode actions first, followed by base selection and project toggle. Active mode says `(active)` and is disabled. Tooltip names mode and base; after successful current comparison it also shows actual baseline commit.

Open widget popup to switch mode, select/change base, or toggle Branch Changes for whole project. Disabling hides setup notifications while preserving every saved mapping. Reenabling restores them.

## Comparison and marker semantics

Every recomputation resolves latest locally known selected ref tip and current `HEAD`:

- **Fork point** runs `git merge-base --all` and requires exactly one best merge base. That merge-base commit is baseline.
- **Branch HEAD** uses selected ref tip itself as baseline and does not run or require merge-base selection. It therefore works with unrelated and criss-cross histories.

Baseline text is compared directly with current live editor document. Both modes include net committed, staged, unstaged, and unsaved changes while staying aligned with visible editor lines. If selected ref advances or is rewritten locally, next refresh resolves new tip. Fork point recomputes merge-base; Branch HEAD compares directly with new tip.

Marker legend:

- **Added** — whole-line added background plus gutter stripe
- **Modified** — whole-line modified background plus gutter stripe
- **Deleted** — gutter triangle anchored before next live line or at EOF

Colors use theme-aware IntelliJ diff defaults and can be changed under **Settings | Editor | Color Scheme | Branch Changes**. Tooltips and accessibility text identify change type, selected mode, selected base, actual abbreviated baseline commit, and local-edit scope. Click any added, modified, or deleted gutter marker to open full-file diff from mode-selected baseline content to current live document. Diff title and baseline label distinguish `Fork point <commit>` from `Branch HEAD <commit>` and retain old/new paths for renames.

Branch Changes markers coexist with IntelliJ built-in VCS gutter markers. Built-in markers remain `HEAD`-relative; Branch Changes markers use selected comparison mode.

## Refresh and performance limits

Open/close, live document edits, branch or local ref updates, base changes, project toggle changes, VFS moves/renames, and color-scheme changes trigger debounced recomputation. Rapid edits supersede stale work. Git and line comparison run outside UI thread.

Highlighting is skipped, with non-modal status, for:

- binary or ignored files
- files larger than 2 MiB
- documents over 10,000 lines
- detached or unborn `HEAD`
- missing/invalid base refs
- histories with no merge base or multiple best merge bases in Fork point mode
- missing partial-clone objects when lazy fetching is disabled
- malformed Git output or differences too large for IntelliJ diff engine

Disable project highlighting from status widget to remove Branch Changes markers immediately. Saved branch/root mappings remain and highlighting recomputes after reenabling.

## Rename behavior

Committed and staged renames detected by Git between mode-selected baseline commit and current index use Git's native default similarity threshold. Fork point scans from merge-base; Branch HEAD scans from selected ref tip. Pure detected renames show no changed-line markers. Detected rename-plus-edit files show only net edited regions. Lower-similarity delete/add results use new-file behavior for destination.

Unstaged filesystem moves are not paired with deleted source paths: destination is treated as new until rename is staged. This is intentional MVP limitation.

## Local-only ref freshness and safety

Branch Changes only reads refs and objects already present locally. It never fetches or contacts remotes. `origin/main` means locally stored remote-tracking ref, not live server state. Run `git fetch` yourself when you want fresher remote-tracking refs; plugin reacts after local ref update. Locally advancing any selected local or remote-tracking ref similarly refreshes comparisons.

All plugin Git commands are read-only and set:

- `GIT_NO_LAZY_FETCH=1` to prevent partial-clone object fetching
- `GIT_TERMINAL_PROMPT=0` to prevent credential prompts
- `GIT_OPTIONAL_LOCKS=0` to avoid optional repository locks

Base discovery and validation do not modify refs, objects, index, or working tree.

## Third-party icon assets

Status widget and popup embed Lucide icons under ISC license, pinned to commit [`a79b2d131dab2bf20cb224bd0937b439a9c4fa99`](https://github.com/lucide-icons/lucide/tree/a79b2d131dab2bf20cb224bd0937b439a9c4fa99):

- [`git-fork.svg`](https://raw.githubusercontent.com/lucide-icons/lucide/a79b2d131dab2bf20cb224bd0937b439a9c4fa99/icons/git-fork.svg)
- [`git-compare-arrows.svg`](https://raw.githubusercontent.com/lucide-icons/lucide/a79b2d131dab2bf20cb224bd0937b439a9c4fa99/icons/git-compare-arrows.svg)

Bundled variants render at 16×16, using `#6C707E` in light themes and `#CED0D6` in dark themes; original 24×24 geometry and stroke shape remain. Copyright, source provenance, modifications, and complete ISC notice ship at `META-INF/third-party/lucide-icons-ISC.txt` inside plugin artifact.

Plugin Manager logo uses Microsoft Fluent Emoji's [high-contrast face with monocle](https://github.com/microsoft/fluentui-emoji/blob/1ffb34c752ecf5d402f04cfb4b392c77f57c54bc/assets/Face%20with%20monocle/High%20Contrast/face_with_monocle_high_contrast.svg), licensed under MIT. Light and dark variants render on a 40×40 canvas. Source provenance, modifications, and complete MIT notice ship at `META-INF/third-party/fluentui-emoji-MIT.txt`.

## Development

Run tests and project checks:

```shell
./gradlew test
./gradlew verifyPluginProjectConfiguration verifyPluginStructure
```

Launch sandbox IntelliJ IDEA 2024.3:

```shell
./gradlew runIde
```

Build uses Kotlin, Java 21, Gradle Kotlin DSL, IntelliJ Platform Gradle Plugin 2.x, and bundled `Git4Idea` APIs.

## Troubleshooting unavailable states

Configuration UI remains inactive for non-Git files, detached HEAD, unborn branches, and repositories without current local branch. Missing saved refs appear unavailable and can be replaced through **Select base...**. Fetch stale refs manually, then wait for repository refresh. Binary, ignored, oversized, excessive-line, and Git failure states apply to both modes. No/multiple merge-base states apply only to Fork point; switch to Branch HEAD if direct selected-tip comparison is desired. States appear as editor notifications or status-widget tooltips without repeated dialogs. Git command failures are available in IDE log; file contents are not logged.

## Non-goals

Current scope excludes:

- automatic remote fetches or any repository mutation
- Marketplace publishing/signing pipeline
- JetBrains Remote Development support
- automatic source/parent branch inference
