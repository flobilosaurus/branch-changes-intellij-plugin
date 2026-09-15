package com.github.flobilosaurus.branchchanges.ui

import com.github.flobilosaurus.branchchanges.BranchChangesProjectService
import com.github.flobilosaurus.branchchanges.GitPlatformTestCase
import com.github.flobilosaurus.branchchanges.git.BranchRefProvider
import com.github.flobilosaurus.branchchanges.git.GitBranchRefProvider
import com.github.flobilosaurus.branchchanges.model.ComparisonMode
import com.github.flobilosaurus.branchchanges.settings.BranchBaseSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
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

    fun testWidgetIconsPopupOrderAndActiveModeState() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val settings = BranchBaseSettings.getInstance(project)
        val widget = BranchChangesStatusBarWidget(project)
        Disposer.register(testRootDisposable, widget)

        assertSame(BranchChangesIcons.ForkPoint, widget.getIcon())
        var actions = widget.createActionGroup().getChildren(null)
        assertSize(6, actions)
        var forkPresentation = updated(actions[0])
        var branchHeadPresentation = updated(actions[1])
        assertEquals("Compare from Fork point (active)", forkPresentation.text)
        assertSame(BranchChangesIcons.ForkPoint, forkPresentation.icon)
        assertFalse(forkPresentation.isEnabled)
        assertEquals("Compare with Branch HEAD", branchHeadPresentation.text)
        assertSame(BranchChangesIcons.BranchHead, branchHeadPresentation.icon)
        assertTrue(branchHeadPresentation.isEnabled)
        assertInstanceOf(actions[2], com.intellij.openapi.actionSystem.Separator::class.java)
        assertEquals("Select base...", actions[3].templatePresentation.text)
        assertInstanceOf(actions[4], com.intellij.openapi.actionSystem.Separator::class.java)
        assertEquals("Disable highlighting", actions[5].templatePresentation.text)

        settings.setConfiguration(
            fixture.root.url,
            "feature",
            "refs/heads/main",
            ComparisonMode.BRANCH_HEAD,
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertSame(BranchChangesIcons.BranchHead, widget.getIcon())
        actions = widget.createActionGroup().getChildren(null)
        forkPresentation = updated(actions[0])
        branchHeadPresentation = updated(actions[1])
        assertEquals("Compare from Fork point", forkPresentation.text)
        assertTrue(forkPresentation.isEnabled)
        assertEquals("Compare with Branch HEAD (active)", branchHeadPresentation.text)
        assertFalse(branchHeadPresentation.isEnabled)
        assertEquals("Change base...", actions[3].templatePresentation.text)
    }

    fun testBranchHeadWithoutBaseChooserPersistsOnlyAfterConfirmation() {
        val fixture = createGitRoot("repo")
        addLocalRemoteDefault(fixture)
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val service = BranchChangesProjectService.getInstance(project)
        service.initialize()
        val settings = BranchBaseSettings.getInstance(project)

        service.chooseBase = { _, _ -> }
        service.selectMode(ComparisonMode.BRANCH_HEAD, fixture.file)
        assertNull(settings.getConfiguration(fixture.root.url, "main"))

        var confirmation: ((BranchRefProvider.Validation.Valid) -> Unit)? = null
        service.chooseBase = { _, callback -> confirmation = callback }
        service.selectMode(ComparisonMode.BRANCH_HEAD, fixture.file)
        confirmation!!.invoke(
            BranchRefProvider.Validation.Valid(
                "refs/remotes/origin/main",
                git(fixture.root, "rev-parse", "main"),
            ),
        )
        assertEquals(
            BranchBaseSettings.Configuration("refs/remotes/origin/main", ComparisonMode.BRANCH_HEAD),
            settings.getConfiguration(fixture.root.url, "main"),
        )
    }

    fun testTooltipShowsModeBaseAndOnlyMatchingSuccessfulBaseline() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val settings = BranchBaseSettings.getInstance(project)
        val service = BranchChangesProjectService.getInstance(project)
        service.gitOperations = com.github.flobilosaurus.branchchanges.git.BranchGitClient(project)
        service.initialize()
        val widget = BranchChangesStatusBarWidget(project)
        Disposer.register(testRootDisposable, widget)

        settings.setConfiguration(
            fixture.root.url,
            "feature",
            "refs/heads/main",
            ComparisonMode.BRANCH_HEAD,
        )
        assertEquals(
            "Branch HEAD; base main; baseline pending or unavailable",
            widget.getTooltipText(),
        )
        PlatformTestUtil.waitWithEventsDispatching(
            "Successful snapshot was not exposed in tooltip",
            { service.matchingSnapshot(fixture.file) != null },
            10,
        )
        val baseline = service.matchingSnapshot(fixture.file)!!.identity.baselineHash.take(7)
        assertEquals("Branch HEAD; base main; baseline $baseline", widget.getTooltipText())

        settings.setEnabled(false)
        assertEquals(
            "Branch change highlighting is disabled; Branch HEAD; base main",
            widget.getTooltipText(),
        )
        settings.setEnabled(true)
        settings.removeBase(fixture.root.url, "feature")
        settings.setEnabled(false)
        assertEquals(
            "Branch change highlighting is disabled; Fork point; no base selected",
            widget.getTooltipText(),
        )
    }

    fun testNoFileModeActionsAreDisabledAndCannotMutateSettings() {
        val fixture = createGitRoot("repo")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val widget = BranchChangesStatusBarWidget(project)
        Disposer.register(testRootDisposable, widget)
        manager!!.closeAllFiles()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val actions = widget.createActionGroup().getChildren(null)
        assertFalse(updated(actions[0]).isEnabled)
        assertFalse(updated(actions[1]).isEnabled)
        BranchChangesProjectService.getInstance(project).selectMode(ComparisonMode.BRANCH_HEAD)
        assertEmpty(BranchBaseSettings.getInstance(project).mappings())
        assertSame(BranchChangesIcons.ForkPoint, widget.getIcon())
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

    private fun updated(action: AnAction): Presentation {
        val event = AnActionEvent.createFromAnAction(action, null, "test", DataContext.EMPTY_CONTEXT)
        action.update(event)
        return event.presentation
    }

    fun testWidgetDisposalDetachesPresentation() {
        val widget = BranchChangesStatusBarWidget(project)
        widget.dispose()
        BranchBaseSettings.getInstance(project).setEnabled(false)

        assertEquals("Branch Changes: Disabled", widget.getSelectedValue())
    }
}
