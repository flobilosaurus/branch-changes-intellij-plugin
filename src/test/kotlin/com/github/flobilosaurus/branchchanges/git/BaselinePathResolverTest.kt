package com.github.flobilosaurus.branchchanges.git

import git4idea.repo.GitRepository
import java.lang.reflect.Proxy
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import junit.framework.TestCase

class BaselinePathResolverTest : TestCase() {
    fun testMapsOnlyRenameDestinationAndTreatsCopyAsDirectPath() {
        val fake = FakeGit(
            listOf(
                NameStatusRecord(NameStatusKind.RENAMED, "new.txt", "old.txt", 100),
                NameStatusRecord(NameStatusKind.COPIED, "copy.txt", "old.txt", 100),
            ),
        )
        val resolver = BaselinePathResolver(fake)

        assertEquals("old.txt", success(resolver.resolve(repository(), "root", "fork", "index", "new.txt")).baselinePath)
        assertEquals("copy.txt", success(resolver.resolve(repository(), "root", "fork", "index", "copy.txt")).baselinePath)
        assertEquals("other.txt", success(resolver.resolve(repository(), "root", "fork", "index", "other.txt")).baselinePath)
    }

    fun testCacheReusesKeyInvalidatesByIndexAndIsBounded() {
        val fake = FakeGit(listOf(NameStatusRecord(NameStatusKind.RENAMED, "new", "old", 90)))
        val resolver = BaselinePathResolver(fake, maxEntries = 2)
        val repository = repository()
        resolver.resolve(repository, "root", "fork", "index-1", "new")
        resolver.resolve(repository, "root", "fork", "index-1", "new")
        assertEquals(1, fake.calls.get())

        resolver.resolve(repository, "root", "fork", "index-2", "new")
        resolver.resolve(repository, "root", "fork", "index-3", "new")
        assertEquals(3, fake.calls.get())
        assertEquals(2, resolver.cacheSize())

        resolver.clear()
        assertEquals(0, resolver.cacheSize())
    }

    private fun success(result: BaselinePathResult) = result as BaselinePathResult.Success

    private fun repository(): GitRepository = Proxy.newProxyInstance(
        GitRepository::class.java.classLoader,
        arrayOf(GitRepository::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            else -> null
        }
    } as GitRepository

    private class FakeGit(private val records: List<NameStatusRecord>) : BranchGitOperations {
        val calls = AtomicInteger()

        override fun capture(repository: GitRepository, baseRef: String) = error("unused")
        override fun loadBaseline(repository: GitRepository, forkHash: String, relativePath: String, charset: Charset) = error("unused")
        override fun indexFingerprint(repository: GitRepository) = error("unused")
        override fun loadNameStatus(repository: GitRepository, forkHash: String): GitOperationResult<List<NameStatusRecord>> {
            calls.incrementAndGet()
            return GitOperationResult.Success(records)
        }
    }
}
