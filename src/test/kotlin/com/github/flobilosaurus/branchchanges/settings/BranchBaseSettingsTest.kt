package com.github.flobilosaurus.branchchanges.settings

import com.github.flobilosaurus.branchchanges.model.ComparisonMode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.atomic.AtomicInteger

class BranchBaseSettingsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BranchBaseSettings.getInstance(project).loadState(BranchBaseSettings.SettingsState())
    }

    fun testDefaults() {
        val settings = BranchBaseSettings.getInstance(project)

        assertTrue(settings.isEnabled())
        assertNull(settings.getBase("file:///repo", "main"))
        assertEquals(ComparisonMode.FORK_POINT, settings.getMode("file:///repo", "main"))
        assertEmpty(settings.mappings())
    }

    fun testMappingsAreIsolatedByRootAndBranch() {
        val settings = BranchBaseSettings.getInstance(project)

        settings.setConfiguration(
            "file:///repo-one",
            "feature",
            "refs/remotes/origin/main",
            ComparisonMode.BRANCH_HEAD,
        )
        settings.setBase("file:///repo-one", "main", "refs/remotes/origin/release")
        settings.setBase("file:///repo-two", "feature", "refs/heads/develop")

        assertEquals("refs/remotes/origin/main", settings.getBase("file:///repo-one", "feature"))
        assertEquals(ComparisonMode.BRANCH_HEAD, settings.getMode("file:///repo-one", "feature"))
        assertEquals("refs/remotes/origin/release", settings.getBase("file:///repo-one", "main"))
        assertEquals(ComparisonMode.FORK_POINT, settings.getMode("file:///repo-one", "main"))
        assertEquals("refs/heads/develop", settings.getBase("file:///repo-two", "feature"))
        assertEquals(ComparisonMode.FORK_POINT, settings.getMode("file:///repo-two", "feature"))
        assertNull(settings.getBase("file:///repo-two", "main"))
    }

    fun testStateRoundTrip() {
        val settings = BranchBaseSettings.getInstance(project)
        settings.setEnabled(false)
        settings.setConfiguration(
            "file:///repo",
            "feature",
            "refs/remotes/origin/main",
            ComparisonMode.BRANCH_HEAD,
        )

        val restored = BranchBaseSettings(project)
        restored.loadState(settings.state)

        assertFalse(restored.isEnabled())
        assertEquals("refs/remotes/origin/main", restored.getBase("file:///repo", "feature"))
        assertEquals(ComparisonMode.BRANCH_HEAD, restored.getMode("file:///repo", "feature"))
        assertEquals(ComparisonMode.BRANCH_HEAD.serializedId, restored.state.mappings.single().mode)
    }

    fun testEnableTogglePreservesMappings() {
        val settings = BranchBaseSettings.getInstance(project)
        settings.setBase("file:///repo", "feature", "refs/remotes/origin/main")

        settings.setEnabled(false)
        assertFalse(settings.isEnabled())
        settings.setEnabled(true)

        assertTrue(settings.isEnabled())
        assertEquals("refs/remotes/origin/main", settings.getBase("file:///repo", "feature"))
    }

    fun testStaleEntriesRemainIndependentAndCanBeRemoved() {
        val settings = BranchBaseSettings.getInstance(project)
        settings.setBase("file:///deleted-root", "deleted-branch", "refs/heads/old-base")
        settings.setBase("file:///repo", "feature", "refs/remotes/origin/main")

        assertEquals("refs/heads/old-base", settings.getBase("file:///deleted-root", "deleted-branch"))
        settings.removeBase("file:///repo", "feature")

        assertEquals("refs/heads/old-base", settings.getBase("file:///deleted-root", "deleted-branch"))
        assertNull(settings.getBase("file:///repo", "feature"))
    }

    fun testRootNormalization() {
        assertEquals("file:///repo/work", BranchBaseSettings.normalizeRootUrl(" file:///repo/./work/ "))
        assertEquals("/repo/work", BranchBaseSettings.normalizeRootUrl("/repo/other/../work/"))
        assertEquals("C:/work/repo", BranchBaseSettings.normalizeRootUrl("C:\\work\\repo\\"))

        val settings = BranchBaseSettings.getInstance(project)
        settings.setBase("file:///repo/work/", "feature", "refs/heads/main")
        assertEquals("refs/heads/main", settings.getBase("file:///repo/work", "feature"))
    }

    fun testLegacyAndUnknownModesMigrateToForkPointWithoutLosingBase() {
        val state = BranchBaseSettings.SettingsState().apply {
            mappings.add(BranchBaseSettings.BaseMappingState("file:///legacy", "feature", "refs/heads/main"))
            mappings.add(
                BranchBaseSettings.BaseMappingState(
                    "file:///unknown",
                    "topic",
                    "refs/heads/develop",
                    "NOT_A_MODE",
                ),
            )
        }
        val settings = BranchBaseSettings(project)

        settings.loadState(state)

        assertEquals("refs/heads/main", settings.getBase("file:///legacy", "feature"))
        assertEquals(ComparisonMode.FORK_POINT, settings.getMode("file:///legacy", "feature"))
        assertEquals("refs/heads/develop", settings.getBase("file:///unknown", "topic"))
        assertEquals(ComparisonMode.FORK_POINT, settings.getMode("file:///unknown", "topic"))
        assertTrue(settings.state.mappings.all { it.mode == ComparisonMode.FORK_POINT.serializedId })
    }

    fun testBaseAndModeUpdatesPreserveOtherConfigurationAndNotifyOnce() {
        val settings = BranchBaseSettings.getInstance(project)
        val notifications = AtomicInteger()
        project.messageBus.connect(testRootDisposable).subscribe(
            BranchBaseSettings.TOPIC,
            BranchBaseSettings.Listener { notifications.incrementAndGet() },
        )

        settings.setConfiguration(
            "file:///repo",
            "feature",
            "refs/heads/main",
            ComparisonMode.BRANCH_HEAD,
        )
        assertEquals(1, notifications.get())

        settings.setBase("file:///repo", "feature", "refs/heads/release")
        assertEquals(2, notifications.get())
        assertEquals(ComparisonMode.BRANCH_HEAD, settings.getMode("file:///repo", "feature"))

        settings.setConfiguration(
            "file:///repo",
            "feature",
            "refs/heads/release",
            ComparisonMode.FORK_POINT,
        )
        assertEquals(3, notifications.get())
        assertEquals("refs/heads/release", settings.getBase("file:///repo", "feature"))

        settings.setConfiguration(
            "file:///repo",
            "feature",
            "refs/heads/release",
            ComparisonMode.FORK_POINT,
        )
        assertEquals(3, notifications.get())
    }

    fun testLoadDropsMalformedEntriesAndDeduplicatesKeys() {
        val state = BranchBaseSettings.SettingsState().apply {
            mappings.add(BranchBaseSettings.BaseMappingState("", "feature", "refs/heads/main"))
            mappings.add(BranchBaseSettings.BaseMappingState("file:///repo", "", "refs/heads/main"))
            mappings.add(BranchBaseSettings.BaseMappingState("file:///repo", "feature", ""))
            mappings.add(BranchBaseSettings.BaseMappingState("file:///repo/", "feature", "refs/heads/main"))
            mappings.add(BranchBaseSettings.BaseMappingState("file:///repo", "feature", "refs/heads/other"))
        }
        val settings = BranchBaseSettings(project)

        settings.loadState(state)

        assertSize(1, settings.mappings())
        assertEquals("refs/heads/main", settings.getBase("file:///repo", "feature"))
    }
}
