package com.github.flobilosaurus.branchchanges

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.VfsTestUtil
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import git4idea.GitVcs
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

abstract class GitPlatformTestCase : FileEditorManagerTestCase() {
    data class GitFixture(
        val root: VirtualFile,
        val file: VirtualFile,
    )

    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    override fun tearDown() {
        try {
            manager?.closeAllFiles()
            ProjectLevelVcsManager.getInstance(project).directoryMappings = emptyList()
            val repositories = VcsRepositoryManager.getInstance(project)
            runBlocking { repositories.ensureUpToDate() }
            repositories.waitForAsyncTaskCompletion()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally {
            super.tearDown()
        }
    }

    protected fun createGitRoot(
        name: String,
        initialBranch: String = "main",
        commit: Boolean = true,
    ): GitFixture {
        val root = myFixture.tempDirFixture.findOrCreateDir(name)
        git(root, "init", "--initial-branch=$initialBranch")
        git(root, "config", "user.name", "Branch Changes Test")
        git(root, "config", "user.email", "test@example.invalid")
        val file = myFixture.tempDirFixture.createFile("$name/readme.txt", "initial\n")
        VfsTestUtil.syncRefresh()
        if (commit) {
            git(root, "add", ".")
            git(root, "commit", "-m", "initial")
        }
        return GitFixture(root, file)
    }

    protected fun addLocalRemoteDefault(fixture: GitFixture, defaultBranch: String = "main") {
        git(fixture.root, "remote", "add", "origin", fixture.root.path)
        git(fixture.root, "update-ref", "refs/remotes/origin/$defaultBranch", defaultBranch)
        git(
            fixture.root,
            "symbolic-ref",
            "refs/remotes/origin/HEAD",
            "refs/remotes/origin/$defaultBranch",
        )
        refreshVfs()
    }

    protected fun registerGitRoots(vararg roots: VirtualFile): List<GitRepository> {
        ProjectLevelVcsManager.getInstance(project).directoryMappings = roots.map {
            VcsDirectoryMapping(it.path, GitVcs.NAME)
        }
        refreshVfs()
        val vcsRepositories = VcsRepositoryManager.getInstance(project)
        runBlocking { vcsRepositories.ensureUpToDate() }
        vcsRepositories.waitForAsyncTaskCompletion()

        val manager = GitRepositoryManager.getInstance(project)
        return roots.map { root ->
            manager.getRepositoryForRootQuick(root)
                ?: error("Git repository was not discovered for ${root.path}")
        }
    }

    protected fun refreshRepository(root: VirtualFile): GitRepository {
        refreshVfs()
        val manager = GitRepositoryManager.getInstance(project)
        PlatformTestUtil.callOnBgtSynchronously(
            { manager.updateRepository(root) },
            10,
        )
        return manager.getRepositoryForRootQuick(root)
            ?: error("Git repository disappeared for ${root.path}")
    }

    protected fun git(root: VirtualFile, vararg args: String): String {
        val command = mutableListOf("git", "-C", root.path)
        command.addAll(args)
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "Git command timed out: ${command.joinToString(" ")}" }
        val stdout = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        val stderr = process.errorStream.readBytes().toString(StandardCharsets.UTF_8)
        check(process.exitValue() == 0) {
            "Git command failed (${process.exitValue()}): ${command.joinToString(" ")}\n$stderr"
        }
        return stdout.trim()
    }

    protected fun refreshVfs() {
        VfsTestUtil.syncRefresh()
    }
}
