package com.github.flobilosaurus.branchchanges.git

import com.github.flobilosaurus.branchchanges.GitPlatformTestCase
import com.github.flobilosaurus.branchchanges.settings.BranchBaseSettings
import com.intellij.testFramework.PlatformTestUtil

class BranchContextAndRefPlatformTest : GitPlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BranchBaseSettings.getInstance(project).loadState(BranchBaseSettings.SettingsState())
    }

    fun testResolvesNormalBranchAndRejectsNonGitFile() {
        val fixture = createGitRoot("repo")
        registerGitRoots(fixture.root)
        val resolver = BranchContextResolver(project)

        val available = resolver.resolve(fixture.file)
        assertInstanceOf(available, BranchContextResolver.Result.Available::class.java)
        val context = (available as BranchContextResolver.Result.Available).context
        assertEquals("main", context.branchName)
        assertEquals(40, context.headHash.length)
        assertNull(context.configuredBaseRef)

        val outside = myFixture.tempDirFixture.createFile("outside.txt", "outside\n")
        assertEquals(
            BranchContextResolver.UnavailableReason.NOT_GIT,
            (resolver.resolve(outside) as BranchContextResolver.Result.Unavailable).reason,
        )
    }

    fun testRejectsUnbornAndDetachedRepositories() {
        val unborn = createGitRoot("unborn", commit = false)
        val detached = createGitRoot("detached")
        registerGitRoots(unborn.root, detached.root)
        val resolver = BranchContextResolver(project)

        assertEquals(
            BranchContextResolver.UnavailableReason.UNBORN_BRANCH,
            (resolver.resolve(unborn.file) as BranchContextResolver.Result.Unavailable).reason,
        )

        git(detached.root, "checkout", "--detach")
        refreshRepository(detached.root)
        assertEquals(
            BranchContextResolver.UnavailableReason.DETACHED_HEAD,
            (resolver.resolve(detached.file) as BranchContextResolver.Result.Unavailable).reason,
        )
    }

    fun testDefaultSuggestionAndValidationSemantics() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "branch", "feature")
        addLocalRemoteDefault(fixture)
        val repository = registerGitRoots(fixture.root).single()
        val provider = GitBranchRefProvider(project)

        val discovery = PlatformTestUtil.callOnBgtSynchronously(
            { provider.discover(repository) },
            10,
        )!!
        assertEquals("origin/main", discovery.suggestedRef?.displayName)
        assertContainsElements(discovery.refs.map { it.displayName }, "main", "feature", "origin/main")

        assertEquals(
            BranchRefProvider.Validation.CurrentBranch,
            PlatformTestUtil.callOnBgtSynchronously(
                { provider.validate(repository, "main", "refs/heads/main") },
                10,
            )!!,
        )
        assertEquals(
            BranchRefProvider.Validation.Invalid,
            PlatformTestUtil.callOnBgtSynchronously(
                { provider.validate(repository, "main", "refs/remotes/origin/missing") },
                10,
            )!!,
        )

        val equalTipRemote = PlatformTestUtil.callOnBgtSynchronously(
            { provider.validate(repository, "main", "refs/remotes/origin/main") },
            10,
        )!!
        assertInstanceOf(equalTipRemote, BranchRefProvider.Validation.Valid::class.java)
        assertEquals(
            repository.currentRevision,
            (equalTipRemote as BranchRefProvider.Validation.Valid).commitHash,
        )
    }

    fun testMappingsAreIndependentAcrossGitRoots() {
        val first = createGitRoot("first")
        val second = createGitRoot("second", initialBranch = "develop")
        registerGitRoots(first.root, second.root)
        val settings = BranchBaseSettings.getInstance(project)
        settings.setBase(first.root.url, "main", "refs/remotes/origin/main")
        settings.setBase(second.root.url, "develop", "refs/heads/release")
        val resolver = BranchContextResolver(project)

        val firstContext = (resolver.resolve(first.file) as BranchContextResolver.Result.Available).context
        val secondContext = (resolver.resolve(second.file) as BranchContextResolver.Result.Available).context

        assertEquals("refs/remotes/origin/main", firstContext.configuredBaseRef)
        assertEquals("refs/heads/release", secondContext.configuredBaseRef)
    }

    fun testBranchSwitchRestoresMapping() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "branch", "feature-one")
        git(fixture.root, "branch", "feature-two")
        val repository = registerGitRoots(fixture.root).single()
        val settings = BranchBaseSettings.getInstance(project)
        settings.setBase(fixture.root.url, "feature-one", "refs/heads/main")
        settings.setBase(fixture.root.url, "feature-two", "refs/heads/feature-one")
        val resolver = BranchContextResolver(project)

        git(fixture.root, "checkout", "feature-one")
        refreshRepository(repository.root)
        val one = (resolver.resolve(fixture.file) as BranchContextResolver.Result.Available).context
        assertEquals("refs/heads/main", one.configuredBaseRef)

        git(fixture.root, "checkout", "feature-two")
        refreshRepository(repository.root)
        val two = (resolver.resolve(fixture.file) as BranchContextResolver.Result.Available).context
        assertEquals("refs/heads/feature-one", two.configuredBaseRef)
    }
}
