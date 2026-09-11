package com.github.flobilosaurus.branchchanges.ui

import com.github.flobilosaurus.branchchanges.BranchChangesProjectService
import com.github.flobilosaurus.branchchanges.GitPlatformTestCase
import com.github.flobilosaurus.branchchanges.git.BranchRefProvider
import com.github.flobilosaurus.branchchanges.git.GitBranchRefProvider
import com.github.flobilosaurus.branchchanges.settings.BranchBaseSettings
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil

class BranchChangesUiPlatformTest : GitPlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BranchBaseSettings.getInstance(project).loadState(BranchBaseSettings.SettingsState())
    }

    fun testWidgetTracksSelectedEditorAndToggleState() {
        val fixture = createGitRoot("repo")
        addLocalRemoteDefault(fixture)
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val settings = BranchBaseSettings.getInstance(project)
        val widget = BranchChangesStatusBarWidget(project)
        Disposer.register(testRootDisposable, widget)

        assertEquals("Branch Changes: Select base", widget.getSelectedValue())

        settings.setBase(fixture.root.url, "main", "refs/remotes/origin/main")
        assertEquals("Branch Changes: origin/main", widget.getSelectedValue())

        settings.setEnabled(false)
        assertEquals("Branch Changes: Disabled", widget.getSelectedValue())
        settings.setEnabled(true)
        assertEquals("Branch Changes: origin/main", widget.getSelectedValue())
    }

    fun testNotificationShownOnlyWhenConfigurationIsNeededAndEnabled() {
        val fixture = createGitRoot("repo")
        addLocalRemoteDefault(fixture)
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val fileEditor = manager!!.getSelectedEditor(fixture.file)!!
        val settings = BranchBaseSettings.getInstance(project)
        val provider = BranchBaseEditorNotificationProvider()

        val missing = provider.collectNotificationData(project, fixture.file)
        assertNotNull(missing)
        val missingPanel = missing!!.apply(fileEditor)
        assertTrue(
            (missingPanel as com.intellij.ui.EditorNotificationPanel).text.contains("needs a comparison base"),
        )

        settings.setBase(fixture.root.url, "main", "refs/remotes/origin/main")
        assertNull(provider.collectNotificationData(project, fixture.file))

        settings.removeBase(fixture.root.url, "main")
        settings.setEnabled(false)
        assertNull(provider.collectNotificationData(project, fixture.file))
    }

    fun testInvalidSavedBaseShowsRecoveryNotification() {
        val fixture = createGitRoot("repo")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val settings = BranchBaseSettings.getInstance(project)
        settings.setBase(fixture.root.url, "main", "refs/remotes/origin/missing")
        val service = BranchChangesProjectService.getInstance(project)
        service.initialize()
        service.refresh("test-invalid-base")

        PlatformTestUtil.waitWithEventsDispatching(
            "Invalid base was not detected",
            { service.uiState(fixture.file) is BranchChangesProjectService.UiState.InvalidBase },
            10_000,
        )

        val provider = BranchBaseEditorNotificationProvider()
        val notification = provider.collectNotificationData(project, fixture.file)
        assertNotNull(notification)
        val panel = notification!!.apply(manager!!.getSelectedEditor(fixture.file)!!)
            as com.intellij.ui.EditorNotificationPanel
        assertTrue(panel.text.contains("no longer available"))
    }

    fun testDefaultSuggestionDoesNotPersistWithoutExplicitConfirmation() {
        val fixture = createGitRoot("repo")
        addLocalRemoteDefault(fixture)
        val repository = registerGitRoots(fixture.root).single()
        val settings = BranchBaseSettings.getInstance(project)
        val provider = GitBranchRefProvider(project)

        val discovery = PlatformTestUtil.callOnBgtSynchronously(
            { provider.discover(repository) },
            10,
        )!!
        assertEquals("origin/main", discovery.suggestedRef?.displayName)
        assertNull(settings.getBase(fixture.root.url, "main"))

        val confirmed = PlatformTestUtil.callOnBgtSynchronously(
            {
                provider.validate(
                    repository,
                    "main",
                    discovery.suggestedRef!!.fullName,
                )
            },
            10,
        )!!
        assertInstanceOf(confirmed, BranchRefProvider.Validation.Valid::class.java)
        assertNull(settings.getBase(fixture.root.url, "main"))

        settings.setBase(
            fixture.root.url,
            "main",
            (confirmed as BranchRefProvider.Validation.Valid).fullName,
        )
        assertEquals("refs/remotes/origin/main", settings.getBase(fixture.root.url, "main"))
    }

    fun testWidgetInitializationOffEdtDefersActiveEditorLookup() {
        val fixture = createGitRoot("repo")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)

        val widget = PlatformTestUtil.callOnBgtSynchronously(
            { BranchChangesStatusBarWidget(project) },
            10,
        )!!
        Disposer.register(testRootDisposable, widget)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Branch Changes: Select base", widget.getSelectedValue())
    }

    fun testWidgetDisposalDetachesPresentation() {
        val widget = BranchChangesStatusBarWidget(project)
        widget.dispose()
        BranchBaseSettings.getInstance(project).setEnabled(false)

        assertEquals("Branch Changes: Disabled", widget.getSelectedValue())
    }
}
