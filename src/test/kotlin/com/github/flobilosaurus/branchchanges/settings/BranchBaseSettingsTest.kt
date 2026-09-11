package com.github.flobilosaurus.branchchanges.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BranchBaseSettingsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BranchBaseSettings.getInstance(project).loadState(BranchBaseSettings.SettingsState())
    }

    fun testDefaults() {
        val settings = BranchBaseSettings.getInstance(project)

        assertTrue(settings.isEnabled())
        assertNull(settings.getBase("file:///repo", "main"))
        assertEmpty(settings.mappings())
    }

    fun testMappingsAreIsolatedByRootAndBranch() {
        val settings = BranchBaseSettings.getInstance(project)

        settings.setBase("file:///repo-one", "feature", "refs/remotes/origin/main")
        settings.setBase("file:///repo-one", "main", "refs/remotes/origin/release")
        settings.setBase("file:///repo-two", "feature", "refs/heads/develop")

        assertEquals("refs/remotes/origin/main", settings.getBase("file:///repo-one", "feature"))
        assertEquals("refs/remotes/origin/release", settings.getBase("file:///repo-one", "main"))
        assertEquals("refs/heads/develop", settings.getBase("file:///repo-two", "feature"))
        assertNull(settings.getBase("file:///repo-two", "main"))
    }

    fun testStateRoundTrip() {
        val settings = BranchBaseSettings.getInstance(project)
        settings.setEnabled(false)
        settings.setBase("file:///repo", "feature", "refs/remotes/origin/main")

        val restored = BranchBaseSettings(project)
        restored.loadState(settings.state)

        assertFalse(restored.isEnabled())
        assertEquals("refs/remotes/origin/main", restored.getBase("file:///repo", "feature"))
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
