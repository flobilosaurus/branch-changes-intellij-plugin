# Branch Changes

Branch Changes is an IntelliJ IDEA plugin for branch-relative editor change highlighting. Each Git root and local branch gets an explicitly selected comparison base. This avoids guessing branch ancestry from Git metadata.

> Development status: base selection, persistence, status widget, and setup notifications are implemented. Branch-relative editor markers are planned in the next implementation phase.

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

## Current unavailable states

Configuration UI remains inactive for non-Git files, detached HEAD, unborn branches, and repositories without a current local branch. Missing saved refs appear as unavailable and can be replaced through **Select base...**.

## Non-goals

Current scope excludes:

- automatic remote fetches or any repository mutation
- Marketplace publishing/signing pipeline
- JetBrains Remote Development support
- automatic source/parent branch inference
