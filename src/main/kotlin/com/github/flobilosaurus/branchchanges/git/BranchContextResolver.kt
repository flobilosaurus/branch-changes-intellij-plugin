package com.github.flobilosaurus.branchchanges.git

import com.github.flobilosaurus.branchchanges.settings.BranchBaseSettings
import com.intellij.dvcs.repo.Repository
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

class BranchContextResolver(
    project: Project,
    private val settings: BranchBaseSettings = BranchBaseSettings.getInstance(project),
    private val repositoryManager: GitRepositoryManager = GitRepositoryManager.getInstance(project),
) {
    sealed interface Result {
        data class Available(val context: Context) : Result
        data class Unavailable(val reason: UnavailableReason) : Result
    }

    data class Context(
        val repository: GitRepository,
        val rootUrl: String,
        val branchName: String,
        val headHash: String,
        val configuredBaseRef: String?,
    )

    enum class UnavailableReason {
        NOT_GIT,
        DETACHED_HEAD,
        UNBORN_BRANCH,
        NO_LOCAL_BRANCH,
    }

    fun resolve(file: VirtualFile): Result {
        val repository = repositoryManager.getRepositoryForFileQuick(file)
            ?: return Result.Unavailable(UnavailableReason.NOT_GIT)

        if (repository.state == Repository.State.DETACHED) {
            return Result.Unavailable(UnavailableReason.DETACHED_HEAD)
        }

        val revision = repository.currentRevision
            ?: return Result.Unavailable(UnavailableReason.UNBORN_BRANCH)
        val branch = repository.currentBranch
            ?: return Result.Unavailable(UnavailableReason.NO_LOCAL_BRANCH)
        val rootUrl = BranchBaseSettings.normalizeRootUrl(repository.root.url)

        return Result.Available(
            Context(
                repository = repository,
                rootUrl = rootUrl,
                branchName = branch.name,
                headHash = revision,
                configuredBaseRef = settings.getBase(rootUrl, branch.name),
            ),
        )
    }
}
