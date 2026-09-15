package com.github.flobilosaurus.branchchanges

import com.github.flobilosaurus.branchchanges.git.BaselineContent
import com.github.flobilosaurus.branchchanges.git.BranchGitOperations
import com.github.flobilosaurus.branchchanges.git.CapturedGitIdentity
import com.github.flobilosaurus.branchchanges.git.BranchRefProvider
import com.github.flobilosaurus.branchchanges.git.GitOperationResult
import com.github.flobilosaurus.branchchanges.model.BranchChangeType
import com.github.flobilosaurus.branchchanges.model.ComparisonMode
import com.github.flobilosaurus.branchchanges.settings.BranchBaseSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.PlatformTestUtil
import git4idea.repo.GitRepository
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BranchChangesProjectServiceTest : GitPlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BranchBaseSettings.getInstance(project).loadState(BranchBaseSettings.SettingsState())
    }

    fun testRealGitEndToEndInstallsMarkers() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        java.nio.file.Files.writeString(java.nio.file.Path.of(fixture.file.path), "changed\n")
        git(fixture.root, "add", "readme.txt")
        git(fixture.root, "commit", "-m", "feature change")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val editor = manager!!.getSelectedTextEditor()!!
        val service = BranchChangesProjectService.getInstance(project)
        service.gitOperations = com.github.flobilosaurus.branchchanges.git.BranchGitClient(project)
        service.lineComparator = com.github.flobilosaurus.branchchanges.diff.BranchLineComparator()
        service.initialize()
        BranchBaseSettings.getInstance(project).setBase(fixture.root.url, "feature", "refs/heads/main")

        PlatformTestUtil.waitWithEventsDispatching(
            "Real Git comparison did not install markers: ${service.uiState(fixture.file)}",
            { service.ownedHighlighters(editor).isNotEmpty() },
            10,
        )
        assertNotNull(service.snapshotFor(editor.document))
        assertNotEmpty(service.ownedHighlighters(editor))
    }

    fun testUnsavedEditsAreComparedOffEdtAndDebounced() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val document = manager!!.getSelectedTextEditor()!!.document
        val fake = FakeGit()
        val service = BranchChangesProjectService.getInstance(project)
        service.gitOperations = fake
        service.initialize()
        service.onEditorCreated(manager!!.getSelectedTextEditor()!!)
        BranchBaseSettings.getInstance(project).setBase(fixture.root.url, "feature", "refs/heads/main")

        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("one\n")
            document.setText("one\ntwo\n")
            document.setText("one\ntwo\nthree\n")
        }

        PlatformTestUtil.waitWithEventsDispatching(
            "Comparison did not complete",
            { service.snapshotFor(document)?.currentText == "one\ntwo\nthree\n" },
            10,
        )
        val snapshot = service.snapshotFor(document)!!
        assertEquals(BranchChangeType.MODIFIED, snapshot.regions.first().type)
        assertFalse(fake.calledOnEdt)
        assertEquals(1, fake.captureCount.get())
        assertNotEmpty(service.ownedHighlighters(manager!!.getSelectedTextEditor()!!))
    }

    fun testSplitEditorReusesSnapshotAndDisableClearsOnlyOwnedMarkers() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val first = manager!!.getSelectedTextEditor()!!
        val unrelated = first.markupModel.addRangeHighlighter(
            0,
            0,
            1,
            null,
            com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE,
        )
        val fake = FakeGit()
        val service = BranchChangesProjectService.getInstance(project)
        service.gitOperations = fake
        service.initialize()
        service.onEditorCreated(first)
        BranchBaseSettings.getInstance(project).setBase(fixture.root.url, "feature", "refs/heads/main")
        WriteCommandAction.runWriteCommandAction(project) { first.document.setText("changed\n") }
        PlatformTestUtil.waitWithEventsDispatching(
            "Comparison did not complete",
            { service.snapshotFor(first.document) != null },
            10,
        )
        val count = fake.captureCount.get()

        val second = EditorFactory.getInstance().createEditor(first.document, project)
        try {
            service.onEditorCreated(second)
            PlatformTestUtil.waitWithEventsDispatching(
                "Split editor did not reuse snapshot",
                { service.ownedHighlighters(second).isNotEmpty() },
                10,
            )
            assertEquals(count, fake.captureCount.get())

            service.setEnabled(false)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEmpty(service.ownedHighlighters(first))
            assertEmpty(service.ownedHighlighters(second))
            assertTrue(unrelated.isValid)

            service.setEnabled(true)
            PlatformTestUtil.waitWithEventsDispatching(
                "Reenabled markers did not return",
                { service.ownedHighlighters(first).isNotEmpty() },
                10,
            )
            service.beforePluginUnload("com.github.flobilosaurus.branchchanges")
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEmpty(service.ownedHighlighters(first))
            assertEmpty(service.ownedHighlighters(second))
            assertTrue(unrelated.isValid)
        } finally {
            service.onEditorReleased(second)
            EditorFactory.getInstance().releaseEditor(second)
        }
    }

    fun testCancelledOutOfOrderResultCannotReplaceLatestDocument() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val editor = manager!!.getSelectedTextEditor()!!
        val fake = BlockingGit()
        val service = BranchChangesProjectService.getInstance(project)
        service.gitOperations = fake
        service.initialize()
        service.onEditorCreated(editor)
        BranchBaseSettings.getInstance(project).setBase(fixture.root.url, "feature", "refs/heads/main")
        WriteCommandAction.runWriteCommandAction(project) { editor.document.setText("first\n") }
        assertTrue(fake.firstLoadStarted.await(5, TimeUnit.SECONDS))

        WriteCommandAction.runWriteCommandAction(project) { editor.document.setText("latest\n") }
        fake.releaseFirstLoad.countDown()

        PlatformTestUtil.waitWithEventsDispatching(
            "Latest generation did not replace cancelled result",
            { service.snapshotFor(editor.document)?.currentText == "latest\n" },
            10,
        )
        assertEquals("latest\n", service.snapshotFor(editor.document)!!.currentText)
        assertTrue(fake.captureCount.get() >= 2)
    }

    fun testModeSwitchRecomputesAndImmediatelyClearsOldModeSnapshotAndMarkers() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val editor = manager!!.getSelectedTextEditor()!!
        val fake = FakeGit()
        val service = BranchChangesProjectService.getInstance(project)
        service.gitOperations = fake
        val presentedTargets = CopyOnWriteArrayList<Pair<ComparisonMode, Int>>()
        service.presentDiff = { _, _, _, snapshot, line -> presentedTargets += snapshot.identity.mode to line }
        service.initialize()
        service.onEditorCreated(editor)
        val settings = BranchBaseSettings.getInstance(project)
        settings.setBase(fixture.root.url, "feature", "refs/heads/main")
        WriteCommandAction.runWriteCommandAction(project) { editor.document.setText("changed\n") }
        PlatformTestUtil.waitWithEventsDispatching(
            "Fork-point comparison did not complete",
            { service.snapshotFor(editor.document)?.identity?.mode == ComparisonMode.FORK_POINT },
            10,
        )
        assertNotEmpty(service.ownedHighlighters(editor))

        settings.setConfiguration(
            fixture.root.url,
            "feature",
            "refs/heads/main",
            ComparisonMode.BRANCH_HEAD,
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertNull(service.snapshotFor(editor.document))
        assertEmpty(service.ownedHighlighters(editor))
        service.showDiff(editor, 1)

        PlatformTestUtil.waitWithEventsDispatching(
            "Branch-HEAD comparison did not replace old mode",
            { service.snapshotFor(editor.document)?.identity?.mode == ComparisonMode.BRANCH_HEAD },
            10,
        )
        assertEquals(
            listOf(ComparisonMode.FORK_POINT, ComparisonMode.BRANCH_HEAD),
            fake.capturedModes.distinct(),
        )
        assertNotEmpty(service.ownedHighlighters(editor))
        PlatformTestUtil.waitWithEventsDispatching(
            "Pending diff did not use replacement mode and target line",
            { presentedTargets.isNotEmpty() },
            10,
        )
        assertEquals(listOf(ComparisonMode.BRANCH_HEAD to 1), presentedTargets)
    }

    fun testBranchHeadWithoutBaseCancellationAndContextDriftDoNotPersist() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "branch", "other")
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
        assertNotNull(confirmation)
        git(fixture.root, "checkout", "other")
        refreshRepository(fixture.root)
        confirmation!!.invoke(
            BranchRefProvider.Validation.Valid(
                "refs/heads/other",
                git(fixture.root, "rev-parse", "other"),
            ),
        )
        assertNull(settings.getConfiguration(fixture.root.url, "main"))
        assertNull(settings.getConfiguration(fixture.root.url, "other"))
    }

    fun testDelayedForkPointCannotReplaceNewerBranchHeadMode() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val editor = manager!!.getSelectedTextEditor()!!
        val fake = BlockingGit()
        val service = BranchChangesProjectService.getInstance(project)
        service.gitOperations = fake
        service.initialize()
        service.onEditorCreated(editor)
        val settings = BranchBaseSettings.getInstance(project)
        settings.setBase(fixture.root.url, "feature", "refs/heads/main")
        WriteCommandAction.runWriteCommandAction(project) { editor.document.setText("changed\n") }
        assertTrue(fake.firstLoadStarted.await(5, TimeUnit.SECONDS))

        settings.setConfiguration(
            fixture.root.url,
            "feature",
            "refs/heads/main",
            ComparisonMode.BRANCH_HEAD,
        )
        fake.releaseFirstLoad.countDown()

        PlatformTestUtil.waitWithEventsDispatching(
            "New mode did not supersede delayed fork-point result",
            { service.snapshotFor(editor.document)?.identity?.mode == ComparisonMode.BRANCH_HEAD },
            10,
        )
        assertEquals(ComparisonMode.BRANCH_HEAD, service.snapshotFor(editor.document)!!.identity.mode)
        assertTrue(fake.capturedModes.containsAll(ComparisonMode.entries))
    }

    fun testExternalSelectedTipMovementRecomputesBranchHeadBaseline() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val editor = manager!!.getSelectedTextEditor()!!
        val service = BranchChangesProjectService.getInstance(project)
        service.gitOperations = com.github.flobilosaurus.branchchanges.git.BranchGitClient(project)
        service.initialize()
        BranchBaseSettings.getInstance(project).setConfiguration(
            fixture.root.url,
            "feature",
            "refs/heads/main",
            ComparisonMode.BRANCH_HEAD,
        )
        val oldTip = git(fixture.root, "rev-parse", "main")
        PlatformTestUtil.waitWithEventsDispatching(
            "Initial Branch-HEAD snapshot did not complete",
            { service.snapshotFor(editor.document)?.identity?.baselineHash == oldTip },
            10,
        )

        val tree = git(fixture.root, "rev-parse", "main^{tree}")
        val newTip = git(fixture.root, "commit-tree", tree, "-p", oldTip, "-m", "advance main")
        git(fixture.root, "update-ref", "refs/heads/main", newTip)
        refreshRepository(fixture.root)
        service.refresh("repository")

        PlatformTestUtil.waitWithEventsDispatching(
            "Moved selected tip did not replace Branch-HEAD baseline",
            { service.snapshotFor(editor.document)?.identity?.baselineHash == newTip },
            10,
        )
        assertEquals(newTip, service.snapshotFor(editor.document)!!.identity.baseTipHash)
    }

    fun testModeChangeClearsOldUnavailableReasonBeforeReplacement() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val editor = manager!!.getSelectedTextEditor()!!
        val service = BranchChangesProjectService.getInstance(project)
        service.gitOperations = FakeGit(unavailableMode = ComparisonMode.FORK_POINT)
        service.initialize()
        service.onEditorCreated(editor)
        val settings = BranchBaseSettings.getInstance(project)
        settings.setBase(fixture.root.url, "feature", "refs/heads/main")
        PlatformTestUtil.waitWithEventsDispatching(
            "Fork-point unavailable state did not appear",
            { service.uiState(fixture.file) is BranchChangesProjectService.UiState.ComparisonUnavailable },
            10,
        )

        settings.setConfiguration(
            fixture.root.url,
            "feature",
            "refs/heads/main",
            ComparisonMode.BRANCH_HEAD,
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertFalse(service.uiState(fixture.file) is BranchChangesProjectService.UiState.ComparisonUnavailable)
        PlatformTestUtil.waitWithEventsDispatching(
            "Branch-HEAD replacement did not recover",
            { service.snapshotFor(editor.document)?.identity?.mode == ComparisonMode.BRANCH_HEAD },
            10,
        )
    }

    fun testUnavailableModeReplacementLeavesNoOldModeSnapshotOrMarkers() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val editor = manager!!.getSelectedTextEditor()!!
        val service = BranchChangesProjectService.getInstance(project)
        service.gitOperations = FakeGit(unavailableMode = ComparisonMode.BRANCH_HEAD)
        service.initialize()
        service.onEditorCreated(editor)
        val settings = BranchBaseSettings.getInstance(project)
        settings.setBase(fixture.root.url, "feature", "refs/heads/main")
        WriteCommandAction.runWriteCommandAction(project) { editor.document.setText("changed\n") }
        PlatformTestUtil.waitWithEventsDispatching(
            "Initial markers did not appear",
            { service.ownedHighlighters(editor).isNotEmpty() },
            10,
        )

        settings.setConfiguration(
            fixture.root.url,
            "feature",
            "refs/heads/main",
            ComparisonMode.BRANCH_HEAD,
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertNull(service.snapshotFor(editor.document))
        assertEmpty(service.ownedHighlighters(editor))
        PlatformTestUtil.waitWithEventsDispatching(
            "Replacement failure was not surfaced",
            {
                (service.uiState(fixture.file) as? BranchChangesProjectService.UiState.ComparisonUnavailable)
                    ?.reason == com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.GIT_FAILURE
            },
            10,
        )
        assertNull(service.snapshotFor(editor.document))
        assertEmpty(service.ownedHighlighters(editor))
    }

    fun testUnsupportedBinaryStateClearsMarkersAndSurfacesReason() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val editor = manager!!.getSelectedTextEditor()!!
        val fake = FakeGit(
            baselineResult = GitOperationResult.Unavailable(
                com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.BINARY,
            ),
        )
        val service = BranchChangesProjectService.getInstance(project)
        service.gitOperations = fake
        service.initialize()
        service.onEditorCreated(editor)
        BranchBaseSettings.getInstance(project).setBase(fixture.root.url, "feature", "refs/heads/main")

        PlatformTestUtil.waitWithEventsDispatching(
            "Binary state was not surfaced",
            {
                (service.uiState(fixture.file) as? BranchChangesProjectService.UiState.ComparisonUnavailable)
                    ?.reason == com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.BINARY
            },
            10,
        )
        assertEmpty(service.ownedHighlighters(editor))
    }

    fun testIndexChangeRejectsResultAndClearsMarkers() {
        val fixture = createGitRoot("repo")
        git(fixture.root, "checkout", "-b", "feature")
        registerGitRoots(fixture.root)
        manager!!.openFile(fixture.file, true)
        val editor = manager!!.getSelectedTextEditor()!!
        val fake = FakeGit(finalFingerprint = "changed-index")
        val service = BranchChangesProjectService.getInstance(project)
        service.gitOperations = fake
        service.initialize()
        service.onEditorCreated(editor)
        BranchBaseSettings.getInstance(project).setBase(fixture.root.url, "feature", "refs/heads/main")
        WriteCommandAction.runWriteCommandAction(project) { editor.document.setText("changed\n") }

        PlatformTestUtil.waitWithEventsDispatching(
            "Index change was not reported",
            {
                (service.uiState(fixture.file) as? BranchChangesProjectService.UiState.ComparisonUnavailable)
                    ?.reason == com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.INDEX_CHANGED
            },
            10,
        )
        assertNull(service.snapshotFor(editor.document))
        assertEmpty(service.ownedHighlighters(editor))
    }

    private class FakeGit(
        private val finalFingerprint: String = "index",
        private val baselineResult: GitOperationResult<BaselineContent> =
            GitOperationResult.Success(BaselineContent("initial\n", 8, false)),
        private val unavailableMode: ComparisonMode? = null,
    ) : BranchGitOperations {
        val captureCount = AtomicInteger()
        val capturedModes = CopyOnWriteArrayList<ComparisonMode>()
        private val fingerprintCalls = AtomicInteger()
        @Volatile var calledOnEdt = false

        override fun capture(
            repository: GitRepository,
            baseRef: String,
            mode: ComparisonMode,
        ): GitOperationResult<CapturedGitIdentity> {
            calledOnEdt = calledOnEdt || ApplicationManager.getApplication().isDispatchThread
            captureCount.incrementAndGet()
            capturedModes += mode
            if (mode == unavailableMode) {
                return GitOperationResult.Unavailable(
                    com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.GIT_FAILURE,
                )
            }
            val head = repository.currentRevision!!
            val baseline = if (mode == ComparisonMode.FORK_POINT) head else "b".repeat(40)
            return GitOperationResult.Success(CapturedGitIdentity(head, head, baseline, "index"))
        }

        override fun loadBaseline(
            repository: GitRepository,
            baselineHash: String,
            relativePath: String,
            charset: Charset,
        ): GitOperationResult<BaselineContent> {
            calledOnEdt = calledOnEdt || ApplicationManager.getApplication().isDispatchThread
            return baselineResult
        }

        override fun indexFingerprint(repository: GitRepository): GitOperationResult<String> {
            calledOnEdt = calledOnEdt || ApplicationManager.getApplication().isDispatchThread
            fingerprintCalls.incrementAndGet()
            return GitOperationResult.Success(finalFingerprint)
        }
    }

    private class BlockingGit : BranchGitOperations {
        val firstLoadStarted = CountDownLatch(1)
        val releaseFirstLoad = CountDownLatch(1)
        val captureCount = AtomicInteger()
        val capturedModes = CopyOnWriteArrayList<ComparisonMode>()
        private val loadCount = AtomicInteger()

        override fun capture(
            repository: GitRepository,
            baseRef: String,
            mode: ComparisonMode,
        ): GitOperationResult<CapturedGitIdentity> {
            captureCount.incrementAndGet()
            capturedModes += mode
            val head = repository.currentRevision!!
            val baseline = if (mode == ComparisonMode.FORK_POINT) head else "b".repeat(40)
            return GitOperationResult.Success(CapturedGitIdentity(head, head, baseline, "index"))
        }

        override fun loadBaseline(
            repository: GitRepository,
            baselineHash: String,
            relativePath: String,
            charset: Charset,
        ): GitOperationResult<BaselineContent> {
            if (loadCount.incrementAndGet() == 1) {
                firstLoadStarted.countDown()
                releaseFirstLoad.await(5, TimeUnit.SECONDS)
            }
            return GitOperationResult.Success(BaselineContent("initial\n", 8, false))
        }

        override fun indexFingerprint(repository: GitRepository): GitOperationResult<String> =
            GitOperationResult.Success("index")
    }
}
