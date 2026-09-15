package com.github.flobilosaurus.branchchanges.git

import com.github.flobilosaurus.branchchanges.GitPlatformTestCase
import com.github.flobilosaurus.branchchanges.model.ComparisonMode
import com.intellij.testFramework.PlatformTestUtil
import git4idea.commands.Git
import git4idea.commands.GitLineHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class GitReadOnlyIntegrationTest : GitPlatformTestCase() {
    private data class RepositorySnapshot(
        val refs: String,
        val indexHash: String,
        val worktreeHash: String,
        val objectCount: String,
        val status: String,
        val untracked: String,
    )

    fun testDiscoveryAndValidationUseOnlyReadOnlyLocalCommands() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "branch", "feature")
        addLocalRemoteDefault(fixture)
        val repository = registerGitRoots(fixture.root).single()
        val before = snapshot(fixture.root.path)
        val commands = mutableListOf<String>()
        val spyingGit = spyingGit(commands)
        val provider = GitBranchRefProvider(project, spyingGit)

        val discovery = PlatformTestUtil.callOnBgtSynchronously(
            { provider.discover(repository) },
            10,
        )!!
        val selected = discovery.suggestedRef ?: error("Expected remote default suggestion")
        val validation = PlatformTestUtil.callOnBgtSynchronously(
            { provider.validate(repository, "main", selected.fullName) },
            10,
        )!!

        assertInstanceOf(validation, BranchRefProvider.Validation.Valid::class.java)
        assertNotEmpty(commands)
        assertTrue(commands.all { it.contains(" rev-parse ") })
        assertTrue(commands.none { it.contains(" fetch ") || it.contains(" ls-remote ") })
        assertEquals(before, snapshot(fixture.root.path))
    }

    fun testBranchHeadCaptureSkipsMergeBaseWhileForkPointUsesAllMergeBases() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        val repository = registerGitRoots(fixture.root).single()
        val commands = mutableListOf<String>()
        val client = BranchGitClient(project, spyingGit(commands))

        PlatformTestUtil.callOnBgtSynchronously(
            { client.capture(repository, "refs/heads/main", ComparisonMode.BRANCH_HEAD) },
            10,
        )
        assertTrue(commands.none { it.contains(" merge-base ") })

        commands.clear()
        PlatformTestUtil.callOnBgtSynchronously(
            { client.capture(repository, "refs/heads/main", ComparisonMode.FORK_POINT) },
            10,
        )
        assertTrue(commands.any { it.contains(" merge-base ") && it.contains(" --all ") })
    }

    fun testComparisonAndRenameOperationsLeaveRepositoryUnchanged() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        git(fixture.root, "mv", "readme.txt", "renamed.txt")
        val repository = registerGitRoots(fixture.root).single()
        val before = snapshot(fixture.root.path)
        val client = BranchGitClient(project)

        for (mode in ComparisonMode.entries) {
            val identity = PlatformTestUtil.callOnBgtSynchronously(
                { client.capture(repository, "refs/heads/main", mode) },
                10,
            ) as GitOperationResult.Success
            PlatformTestUtil.callOnBgtSynchronously(
                { client.loadNameStatus(repository, identity.value.baselineHash) },
                10,
            )
            PlatformTestUtil.callOnBgtSynchronously(
                {
                    client.loadBaseline(
                        repository,
                        identity.value.baselineHash,
                        "readme.txt",
                        StandardCharsets.UTF_8,
                    )
                },
                10,
            )
        }

        assertEquals(before, snapshot(fixture.root.path))
    }

    private fun spyingGit(commands: MutableList<String>): Git {
        val delegate = Git.getInstance()
        return Proxy.newProxyInstance(
            Git::class.java.classLoader,
            arrayOf(Git::class.java),
        ) { _, method, args ->
            val arguments = args ?: emptyArray()
            if (method.name == "runCommand" && arguments.firstOrNull() is GitLineHandler) {
                val handler = arguments.first() as GitLineHandler
                check(handler.containsCustomEnvironmentVariable("GIT_NO_LAZY_FETCH"))
                check(handler.containsCustomEnvironmentVariable("GIT_TERMINAL_PROMPT"))
                check(handler.containsCustomEnvironmentVariable("GIT_OPTIONAL_LOCKS"))
                commands += handler.printableCommandLine()
            }
            try {
                method.invoke(delegate, *arguments)
            } catch (wrapped: InvocationTargetException) {
                throw wrapped.targetException
            }
        } as Git
    }

    private fun snapshot(root: String): RepositorySnapshot {
        val rootPath = Path.of(root)
        val indexPath = rootPath.resolve(".git/index")
        return RepositorySnapshot(
            refs = gitText(root, "show-ref"),
            indexHash = sha256(Files.readAllBytes(indexPath)),
            worktreeHash = worktreeHash(rootPath),
            objectCount = gitText(root, "count-objects", "-v"),
            status = gitText(root, "status", "--porcelain=v2", "--branch"),
            untracked = gitText(root, "ls-files", "--others", "--exclude-standard"),
        )
    }

    private fun gitText(root: String, vararg args: String): String {
        val command = mutableListOf("git", "-C", root)
        command.addAll(args)
        val process = ProcessBuilder(command).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        check(process.waitFor() == 0) { stderr }
        return stdout
    }

    private fun worktreeHash(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && !it.startsWith(root.resolve(".git")) }
                .sorted()
                .forEach {
                    digest.update(root.relativize(it).toString().toByteArray())
                    digest.update(Files.readAllBytes(it))
                }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
