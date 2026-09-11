# Branch Changes

Branch Changes is an IntelliJ IDEA plugin for branch-relative editor change highlighting. Each Git root and local branch gets an explicitly selected comparison base. This avoids guessing branch ancestry from Git metadata.

> Development status: MVP base selection, live branch-relative markers, rename awareness, and marker-click full diff are implemented.

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

## First use and base selection

1. Open a text file in a Git repository on a local branch.
2. Find **Branch Changes: Select base** in the status bar, or use **Select base...** in the editor notification.
3. Choose a locally known local or remote branch.
4. Confirm the choice explicitly.

When available, the remote's locally recorded default branch (commonly `origin/main`) is preselected as a suggestion. It is never saved until confirmation. The current local branch itself is rejected, but another ref pointing to the same commit remains valid.

## Branch-specific, multi-root persistence

Mappings are stored per `(Git root, current local branch)` in IntelliJ workspace state. They are not written to tracked project files. Switching branches restores that branch's mapping. Projects with multiple Git roots keep independent mappings, and the status widget follows the active editor's root and branch.

## Status widget and project toggle

Status values:

- **Branch Changes: _base_** — selected base for the active editor
- **Branch Changes: Select base** — supported branch has no mapping
- **Branch Changes: Disabled** — highlighting is disabled for this project
- **Branch Changes: Unavailable** — active editor has no usable branch context or its saved base is invalid

Open the widget popup to select/change the base or toggle Branch Changes for the whole project. Disabling hides setup notifications while preserving every saved mapping. Reenabling restores them.

## Comparison and marker semantics

Branch Changes resolves selected base and current `HEAD`, then requires exactly one best merge base. File text at that fork commit is compared directly with current live editor document. Results therefore include net committed, staged, unstaged, and unsaved changes since fork while staying aligned with visible editor lines.

Marker legend:

- **Added** — whole-line added background plus gutter stripe
- **Modified** — whole-line modified background plus gutter stripe
- **Deleted** — gutter triangle anchored before next live line or at EOF

Colors use theme-aware IntelliJ diff defaults and can be changed under **Settings | Editor | Color Scheme | Branch Changes**. Tooltips identify change type, selected base, abbreviated fork commit, and local-edit scope. Click any added, modified, or deleted gutter marker to open full-file diff from fork-baseline content to current live document.

Branch Changes markers coexist with IntelliJ built-in VCS gutter markers. Built-in markers remain `HEAD`-relative; Branch Changes markers are fork-relative.

## Refresh and performance limits

Open/close, live document edits, branch or local ref updates, base changes, project toggle changes, VFS moves/renames, and color-scheme changes trigger debounced recomputation. Rapid edits supersede stale work. Git and line comparison run outside UI thread.

Highlighting is skipped, with non-modal status, for:

- binary or ignored files
- files larger than 2 MiB
- documents over 10,000 lines
- detached or unborn `HEAD`
- missing/invalid base refs
- histories with no merge base or multiple best merge bases
- missing partial-clone objects when lazy fetching is disabled
- malformed Git output or differences too large for IntelliJ diff engine

Disable project highlighting from status widget to remove Branch Changes markers immediately. Saved branch/root mappings remain and highlighting recomputes after reenabling.

## Rename behavior

Committed and staged renames detected by Git between fork commit and current index use Git's native default similarity threshold. Pure detected renames show no changed-line markers. Detected rename-plus-edit files show only net edited regions. Lower-similarity delete/add results use new-file behavior for destination.

Unstaged filesystem moves are not paired with deleted source paths: destination is treated as new until rename is staged. This is intentional MVP limitation.

## Local-only ref freshness and safety

Branch Changes only reads refs and objects already present locally. It never fetches or contacts remotes. Run `git fetch` yourself when you want fresher remote-tracking refs, then the plugin reacts to the local repository update.

All plugin Git commands are read-only and set:

- `GIT_NO_LAZY_FETCH=1` to prevent partial-clone object fetching
- `GIT_TERMINAL_PROMPT=0` to prevent credential prompts
- `GIT_OPTIONAL_LOCKS=0` to avoid optional repository locks

Base discovery and validation do not modify refs, objects, index, or working tree.

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

Configuration UI remains inactive for non-Git files, detached HEAD, unborn branches, and repositories without a current local branch. Missing saved refs appear unavailable and can be replaced through **Select base...**. Fetch stale refs manually, then wait for repository refresh. Binary, ignored, oversized, excessive-line, ambiguous-history, and Git failure states appear as editor notifications or status-widget tooltips without repeated dialogs. Git command failures are available in IDE log; file contents are not logged.

## Non-goals

Current scope excludes:

- automatic remote fetches or any repository mutation
- Marketplace publishing/signing pipeline
- JetBrains Remote Development support
- automatic source/parent branch inference
