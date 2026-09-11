package com.github.flobilosaurus.branchchanges.editor

import com.github.flobilosaurus.branchchanges.model.ComparisonIdentity
import com.github.flobilosaurus.branchchanges.model.ComparisonSnapshot
import com.github.flobilosaurus.branchchanges.model.TextMetadata
import com.intellij.diff.contents.DocumentContent
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Canvas
import java.awt.event.MouseEvent
import java.nio.charset.StandardCharsets

class ShowBranchDiffActionTest : BasePlatformTestCase() {
    fun testRequestUsesForkBaselineAndLiveDocumentWithRenamedLabels() {
        val file = myFixture.configureByText("new-name.txt", "live unsaved\n").virtualFile
        val snapshot = snapshot("old-name.txt", "new-name.txt", "baseline\n")
        val request = ShowBranchDiffAction().createRequest(project, myFixture.editor.document, file, snapshot)

        assertEquals("Branch Changes: origin/main fork abcdef1 → local edits", request.title)
        assertEquals("old-name.txt — fork abcdef1 (origin/main)", request.contentTitles[0])
        assertEquals("new-name.txt — live document (includes local edits)", request.contentTitles[1])
        assertEquals("baseline\n", (request.contents[0] as DocumentContent).document.text)
        assertEquals("live unsaved\n", (request.contents[1] as DocumentContent).document.text)
    }

    fun testNewFileUsesEmptyBaseline() {
        val file = myFixture.configureByText("new.txt", "new\n").virtualFile
        val request = ShowBranchDiffAction().createRequest(
            project,
            myFixture.editor.document,
            file,
            snapshot("new.txt", "new.txt", ""),
        )
        assertEquals("", (request.contents[0] as DocumentContent).document.text)
    }

    fun testGutterActionDelegatesForDeletionAnchor() {
        myFixture.configureByText("file.txt", "line\n")
        var clicked: Editor? = null
        val renderer = BranchGutterRenderer(
            com.github.flobilosaurus.branchchanges.model.BranchChangeType.DELETED,
            BranchChangesColors.DELETED,
            "Deleted",
        ) { clicked = it }
        val event = MouseEvent(Canvas(), MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, 1, false)

        assertTrue(renderer.canDoAction(myFixture.editor, event))
        renderer.doAction(myFixture.editor, event)
        assertSame(myFixture.editor, clicked)
    }

    private fun snapshot(baselinePath: String, currentPath: String, baseline: String): ComparisonSnapshot =
        ComparisonSnapshot(
            ComparisonIdentity(
                "file:///repo",
                currentPath,
                "feature",
                "refs/remotes/origin/main",
                "1".repeat(40),
                "2".repeat(40),
                "abcdef1234567890",
                "index",
                1,
                1,
            ),
            baselinePath,
            currentPath,
            baseline,
            "live unsaved\n",
            TextMetadata(baseline.length.toLong(), 1, StandardCharsets.UTF_8, baseline.endsWith('\n')),
            TextMetadata(13, 1, StandardCharsets.UTF_8, true),
            emptyList(),
        )
}
