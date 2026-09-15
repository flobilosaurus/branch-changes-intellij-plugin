package com.github.flobilosaurus.branchchanges.git

import com.github.flobilosaurus.branchchanges.model.BranchContext
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
        data class Available(val context: BranchContext) : Result
        data class Unavailable(val reason: UnavailableReason) : Result
    }

    enum class UnavailableReason {
        NOT_GIT,
        DETACHED_HEAD,
        UNBORN_BRANCH,
        NO_LOCAL_BRANCH,
    }

    fun resolve(file: VirtualFile): Result {
        val repository = repositoryManager.repositories
            .asSequence()
            .filter { com.intellij.openapi.vfs.VfsUtilCore.isAncestor(it.root, file, false) }
            .maxByOrNull { it.root.path.length }
            ?: return Result.Unavailable(UnavailableReason.NOT_GIT)

        if (repository.state == Repository.State.DETACHED) {
            return Result.Unavailable(UnavailableReason.DETACHED_HEAD)
        }

        val revision = repository.currentRevision
            ?: return Result.Unavailable(UnavailableReason.UNBORN_BRANCH)
        val branch = repository.currentBranch
            ?: return Result.Unavailable(UnavailableReason.NO_LOCAL_BRANCH)
        val rootUrl = BranchBaseSettings.normalizeRootUrl(repository.root.url)
        val configuration = settings.getConfiguration(rootUrl, branch.name)

        return Result.Available(
            BranchContext(
                repository = repository,
                rootUrl = rootUrl,
                rootPath = repository.root.path,
                relativePath = com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, repository.root, '/')
                    ?: return Result.Unavailable(UnavailableReason.NOT_GIT),
                branchName = branch.name,
                headHash = revision,
                configuredBaseRef = configuration?.baseRef,
                comparisonMode = configuration?.mode ?: com.github.flobilosaurus.branchchanges.model.ComparisonMode.FORK_POINT,
            ),
        )
    }
}
