package com.github.flobilosaurus.branchchanges.git

import com.github.flobilosaurus.branchchanges.GitPlatformTestCase
import com.github.flobilosaurus.branchchanges.diff.BranchLineComparator
import com.github.flobilosaurus.branchchanges.diff.LineComparisonResult
import com.github.flobilosaurus.branchchanges.model.BranchChangeType
import com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.PlatformTestUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class BranchGitClientIntegrationTest : GitPlatformTestCase() {
    fun testCapturesRefsMergeBaseAndIndexThenLoadsBaseline() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        Files.writeString(Path.of(fixture.file.path), "changed\r\ncontent\r\n")
        refreshVfs()
        git(fixture.root, "add", "readme.txt")
        git(fixture.root, "commit", "-m", "feature")
        val repository = registerGitRoots(fixture.root).single()
        val client = BranchGitClient(project)

        val captured = background { client.capture(repository, "refs/heads/main") }
        assertInstanceOf(captured, GitOperationResult.Success::class.java)
        val identity = (captured as GitOperationResult.Success).value
        assertEquals(git(fixture.root, "rev-parse", "main"), identity.baseTipHash)
        assertEquals(git(fixture.root, "rev-parse", "HEAD"), identity.headHash)
        assertEquals(identity.baseTipHash, identity.forkHash)
        assertEquals(64, identity.indexFingerprint.length)

        val baseline = background {
            client.loadBaseline(repository, identity.forkHash, "readme.txt", StandardCharsets.UTF_8)
        }
        assertInstanceOf(baseline, GitOperationResult.Success::class.java)
        assertEquals("initial\n", (baseline as GitOperationResult.Success).value.text)
        assertFalse(baseline.value.isNewFile)
    }

    fun testNewFileGetsEmptyBaselineAndIgnoredFileIsSkipped() {
        val fixture = createGitRoot("repo")
        val repository = registerGitRoots(fixture.root).single()
        val client = BranchGitClient(project)
        val fork = git(fixture.root, "rev-parse", "HEAD")

        val fresh = background {
            client.loadBaseline(repository, fork, "new.txt", StandardCharsets.UTF_8)
        }
        assertEquals("", (fresh as GitOperationResult.Success).value.text)
        assertTrue(fresh.value.isNewFile)

        Files.writeString(Path.of(fixture.root.path, ".gitignore"), "ignored.txt\n")
        refreshVfs()
        val ignored = background {
            client.loadBaseline(repository, fork, "ignored.txt", StandardCharsets.UTF_8)
        }
        assertEquals(
            ComparisonUnavailableReason.NEW_FILE_IGNORED,
            (ignored as GitOperationResult.Unavailable).reason,
        )
    }

    fun testCommittedAndStagedRenamesLoadSourceBaseline() {
        for (commitRename in listOf(false, true)) {
            val fixture = createGitRoot("repo-$commitRename")
            git(fixture.root, "checkout", "-b", "feature")
            git(fixture.root, "mv", "readme.txt", "renamed.txt")
            if (commitRename) git(fixture.root, "commit", "-m", "rename")
            val repository = registerGitRoots(fixture.root).last()
            val client = BranchGitClient(project)
            val identity = (background { client.capture(repository, "refs/heads/main") } as GitOperationResult.Success).value
            val records = (background { client.loadNameStatus(repository, identity.forkHash) } as GitOperationResult.Success).value
            assertEquals(NameStatusKind.RENAMED, records.single { it.path == "renamed.txt" }.kind)
            val resolved = BaselinePathResolver(client).resolve(
                repository,
                fixture.root.url,
                identity.forkHash,
                identity.indexFingerprint,
                "renamed.txt",
            ) as BaselinePathResult.Success
            assertEquals("readme.txt", resolved.baselinePath)
            val baseline = background {
                client.loadBaseline(repository, identity.forkHash, resolved.baselinePath, StandardCharsets.UTF_8)
            } as GitOperationResult.Success
            val compared = BranchLineComparator().compare(
                baseline.value.text,
                "initial\n",
                EmptyProgressIndicator(),
            ) as LineComparisonResult.Success
            assertEmpty(compared.regions)
        }
    }

    fun testRenameWithEditShowsOnlyEditAndLowSimilarityFallsBackToAddition() {
        val fixture = createGitRoot("repo")
        val original = (1..20).joinToString("\n") { "line-$it" } + "\n"
        Files.writeString(Path.of(fixture.file.path), original)
        git(fixture.root, "add", "readme.txt")
        git(fixture.root, "commit", "--amend", "-m", "larger baseline")
        git(fixture.root, "checkout", "-b", "feature")
        git(fixture.root, "mv", "readme.txt", "renamed.txt")
        val edited = original.replace("line-10", "edited-10")
        Files.writeString(Path.of(fixture.root.path, "renamed.txt"), edited)
        git(fixture.root, "add", "renamed.txt")
        val repository = registerGitRoots(fixture.root).single()
        val client = BranchGitClient(project)
        val identity = (background { client.capture(repository, "refs/heads/main") } as GitOperationResult.Success).value
        val resolved = BaselinePathResolver(client).resolve(
            repository, fixture.root.url, identity.forkHash, identity.indexFingerprint, "renamed.txt",
        ) as BaselinePathResult.Success
        assertEquals("readme.txt", resolved.baselinePath)
        val baseline = background {
            client.loadBaseline(repository, identity.forkHash, resolved.baselinePath, StandardCharsets.UTF_8)
        } as GitOperationResult.Success
        val regions = (BranchLineComparator().compare(
            baseline.value.text,
            edited,
            EmptyProgressIndicator(),
        ) as LineComparisonResult.Success).regions
        assertEquals(BranchChangeType.MODIFIED, regions.single().type)

        Files.writeString(Path.of(fixture.root.path, "renamed.txt"), "completely unrelated content\n")
        git(fixture.root, "add", "renamed.txt")
        val changedIndex = (background { client.capture(repository, "refs/heads/main") } as GitOperationResult.Success).value
        val fallback = BaselinePathResolver(client).resolve(
            repository, fixture.root.url, changedIndex.forkHash, changedIndex.indexFingerprint, "renamed.txt",
        ) as BaselinePathResult.Success
        assertEquals("renamed.txt", fallback.baselinePath)
        val empty = background {
            client.loadBaseline(repository, changedIndex.forkHash, fallback.baselinePath, StandardCharsets.UTF_8)
        } as GitOperationResult.Success
        assertTrue(empty.value.isNewFile)
    }

    fun testUnstagedMoveUsesNewFileBehavior() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        Files.move(Path.of(fixture.file.path), Path.of(fixture.root.path, "moved.txt"))
        val repository = registerGitRoots(fixture.root).single()
        val client = BranchGitClient(project)
        val identity = (background { client.capture(repository, "refs/heads/main") } as GitOperationResult.Success).value
        val resolved = BaselinePathResolver(client).resolve(
            repository, fixture.root.url, identity.forkHash, identity.indexFingerprint, "moved.txt",
        ) as BaselinePathResult.Success
        assertEquals("moved.txt", resolved.baselinePath)
        val baseline = background {
            client.loadBaseline(repository, identity.forkHash, resolved.baselinePath, StandardCharsets.UTF_8)
        } as GitOperationResult.Success
        assertTrue(baseline.value.isNewFile)
        val regions = (BranchLineComparator().compare(
            baseline.value.text,
            "initial\n",
            EmptyProgressIndicator(),
        ) as LineComparisonResult.Success).regions
        assertEquals(BranchChangeType.ADDED, regions.single().type)
    }

    fun testMissingBaseAndUnrelatedHistoryAreUnavailable() {
        val fixture = createGitRoot("repo")
        val repository = registerGitRoots(fixture.root).single()
        val client = BranchGitClient(project)
        assertEquals(
            ComparisonUnavailableReason.INVALID_BASE,
            (background { client.capture(repository, "refs/heads/missing") } as GitOperationResult.Unavailable).reason,
        )

        git(fixture.root, "checkout", "--orphan", "isolated")
        git(fixture.root, "rm", "-rf", ".")
        Files.writeString(Path.of(fixture.root.path, "isolated.txt"), "isolated\n")
        git(fixture.root, "add", ".")
        git(fixture.root, "commit", "-m", "isolated")
        val updated = refreshRepository(fixture.root)
        assertEquals(
            ComparisonUnavailableReason.NO_MERGE_BASE,
            (background { client.capture(updated, "refs/heads/main") } as GitOperationResult.Unavailable).reason,
        )
    }

    fun testMultipleBestMergeBasesAreRejected() {
        val fixture = createGitRoot("repo")
        val root = git(fixture.root, "rev-parse", "HEAD")
        val tree = git(fixture.root, "rev-parse", "HEAD^{tree}")
        val a1 = git(fixture.root, "commit-tree", tree, "-p", root, "-m", "a1")
        val b1 = git(fixture.root, "commit-tree", tree, "-p", root, "-m", "b1")
        val a2 = git(fixture.root, "commit-tree", tree, "-p", a1, "-p", b1, "-m", "a2")
        val b2 = git(fixture.root, "commit-tree", tree, "-p", b1, "-p", a1, "-m", "b2")
        git(fixture.root, "update-ref", "refs/heads/base", a2)
        git(fixture.root, "checkout", "-B", "feature", b2)
        val repository = registerGitRoots(fixture.root).single()
        val client = BranchGitClient(project)

        val result = background { client.capture(repository, "refs/heads/base") }

        assertEquals(
            ComparisonUnavailableReason.MULTIPLE_MERGE_BASES,
            (result as GitOperationResult.Unavailable).reason,
        )
    }

    fun testMissingBaselineObjectDoesNotBecomeNewFile() {
        val fixture = createGitRoot("repo")
        val fork = git(fixture.root, "rev-parse", "HEAD")
        val blob = git(fixture.root, "rev-parse", "HEAD:readme.txt")
        val objectPath = Path.of(fixture.root.path, ".git", "objects", blob.take(2), blob.drop(2))
        assertTrue(Files.deleteIfExists(objectPath))
        val repository = registerGitRoots(fixture.root).single()
        val client = BranchGitClient(project)

        val result = background {
            client.loadBaseline(repository, fork, "readme.txt", StandardCharsets.UTF_8)
        }

        assertEquals(
            ComparisonUnavailableReason.MISSING_OBJECT,
            (result as GitOperationResult.Unavailable).reason,
        )
    }

    private fun <T> background(action: () -> T): T =
        PlatformTestUtil.callOnBgtSynchronously(action, 10)!!
}
