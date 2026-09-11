package com.github.flobilosaurus.branchchanges

import com.github.flobilosaurus.branchchanges.git.BaselineContent
import com.github.flobilosaurus.branchchanges.git.BranchGitOperations
import com.github.flobilosaurus.branchchanges.git.CapturedGitIdentity
import com.github.flobilosaurus.branchchanges.git.GitOperationResult
import com.github.flobilosaurus.branchchanges.model.BranchChangeType
import com.github.flobilosaurus.branchchanges.settings.BranchBaseSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.PlatformTestUtil
import git4idea.repo.GitRepository
import java.nio.charset.Charset
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
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertNotEmpty(service.ownedHighlighters(second))
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
    ) : BranchGitOperations {
        val captureCount = AtomicInteger()
        private val fingerprintCalls = AtomicInteger()
        @Volatile var calledOnEdt = false

        override fun capture(
            repository: GitRepository,
            baseRef: String,
        ): GitOperationResult<CapturedGitIdentity> {
            calledOnEdt = calledOnEdt || ApplicationManager.getApplication().isDispatchThread
            captureCount.incrementAndGet()
            val head = repository.currentRevision!!
            return GitOperationResult.Success(CapturedGitIdentity(head, head, head, "index"))
        }

        override fun loadBaseline(
            repository: GitRepository,
            forkHash: String,
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
        private val loadCount = AtomicInteger()

        override fun capture(repository: GitRepository, baseRef: String): GitOperationResult<CapturedGitIdentity> {
            captureCount.incrementAndGet()
            val head = repository.currentRevision!!
            return GitOperationResult.Success(CapturedGitIdentity(head, head, head, "index"))
        }

        override fun loadBaseline(
            repository: GitRepository,
            forkHash: String,
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
