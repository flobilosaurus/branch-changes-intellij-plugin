package com.github.flobilosaurus.branchchanges.git

import com.intellij.openapi.project.Project
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository

interface BranchRefProvider {
    enum class Kind {
        LOCAL,
        REMOTE,
    }

    data class Ref(
        val displayName: String,
        val fullName: String,
        val tipHash: String,
        val kind: Kind,
    )

    data class Discovery(
        val refs: List<Ref>,
        val suggestedRef: Ref?,
    )

    sealed interface Validation {
        data class Valid(val fullName: String, val commitHash: String) : Validation
        data object CurrentBranch : Validation
        data object Invalid : Validation
        data object Cancelled : Validation
    }

    fun discover(repository: GitRepository): Discovery

    fun validate(repository: GitRepository, currentBranchName: String, ref: String): Validation
}

class GitBranchRefProvider(
    private val project: Project,
    private val git: Git = Git.getInstance(),
) : BranchRefProvider {
    override fun discover(repository: GitRepository): BranchRefProvider.Discovery {
        val info = repository.info
        val localRefs = info.localBranchesWithHashes.map { (branch, hash) ->
            BranchRefProvider.Ref(
                displayName = branch.name,
                fullName = branch.fullName,
                tipHash = hash.asString(),
                kind = BranchRefProvider.Kind.LOCAL,
            )
        }
        val remoteRefs = info.remoteBranchesWithHashes.map { (branch, hash) ->
            BranchRefProvider.Ref(
                displayName = branch.nameForLocalOperations,
                fullName = branch.fullName,
                tipHash = hash.asString(),
                kind = BranchRefProvider.Kind.REMOTE,
            )
        }

        val refs = (localRefs + remoteRefs)
            .filterNot { it.displayName.endsWith("/HEAD") }
            .distinctBy { it.fullName }
            .sortedWith(compareBy<BranchRefProvider.Ref>({ it.kind }, { it.displayName }))
        val suggestionName = findRemoteDefault(repository)
        val suggestion = suggestionName?.let { name ->
            refs.firstOrNull { it.displayName == name || it.fullName == name }
        }

        return BranchRefProvider.Discovery(refs, suggestion)
    }

    override fun validate(
        repository: GitRepository,
        currentBranchName: String,
        ref: String,
    ): BranchRefProvider.Validation {
        val currentBranch = repository.currentBranch
        val currentFullName = currentBranch?.fullName ?: "refs/heads/$currentBranchName"
        val candidate = canonicalRef(repository, ref)

        if (candidate == currentFullName || (ref == currentBranchName && candidate == null)) {
            return BranchRefProvider.Validation.CurrentBranch
        }
        if (candidate == null) return BranchRefProvider.Validation.Invalid
        if (candidate == currentFullName) return BranchRefProvider.Validation.CurrentBranch

        val handler = readOnlyHandler(repository, GitCommand.REV_PARSE)
        handler.addParameters("--verify", "--quiet", "--end-of-options", "$candidate^{commit}")
        val result = git.runCommand(handler)
        if (!result.success()) return BranchRefProvider.Validation.Invalid
        val hash = result.output.firstOrNull()?.trim().orEmpty()
        if (!OBJECT_ID.matches(hash)) return BranchRefProvider.Validation.Invalid

        return BranchRefProvider.Validation.Valid(candidate, hash)
    }

    private fun canonicalRef(repository: GitRepository, ref: String): String? {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return null
        val known = repository.branches.findBranchByName(trimmed)
        if (known != null) return known.fullName

        return when {
            trimmed.startsWith("refs/heads/") -> repository.branches.localBranches
                .firstOrNull { it.fullName == trimmed }
                ?.fullName

            trimmed.startsWith("refs/remotes/") -> repository.branches.remoteBranches
                .firstOrNull { it.fullName == trimmed }
                ?.fullName

            else -> null
        }
    }

    private fun findRemoteDefault(repository: GitRepository): String? {
        val remotes = repository.remotes.sortedWith(
            compareBy<git4idea.repo.GitRemote> { it.name != "origin" }.thenBy { it.name },
        )
        for (remote in remotes) {
            val headName = "${remote.name}/HEAD"
            val handler = readOnlyHandler(repository, GitCommand.REV_PARSE)
            handler.addParameters("--verify", "--abbrev-ref=strict", "refs/remotes/$headName")
            val result = git.runCommand(handler)
            if (!result.success()) continue
            val target = result.output.firstOrNull()?.trim().orEmpty()
            if (target.isNotEmpty() && target != headName) return target
        }
        return null
    }

    private fun readOnlyHandler(repository: GitRepository, command: GitCommand): GitLineHandler =
        GitLineHandler(project, repository.root, command).also {
            it.setSilent(true)
            it.setEnableInteractiveCallbacks(false)
            it.withNoTty()
            it.addCustomEnvironmentVariable("GIT_NO_LAZY_FETCH", "1")
            it.addCustomEnvironmentVariable("GIT_TERMINAL_PROMPT", "0")
            it.addCustomEnvironmentVariable("GIT_OPTIONAL_LOCKS", "0")
        }

    private companion object {
        val OBJECT_ID = Regex("[0-9a-fA-F]{40,64}")
    }
}
