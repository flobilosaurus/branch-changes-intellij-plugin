package com.github.flobilosaurus.branchchanges.editor

import com.github.flobilosaurus.branchchanges.model.ComparisonIdentity
import com.github.flobilosaurus.branchchanges.model.ComparisonMode
import com.github.flobilosaurus.branchchanges.model.ComparisonSnapshot
import com.github.flobilosaurus.branchchanges.model.TextMetadata
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.util.Pair
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Canvas
import java.awt.event.MouseEvent
import java.nio.charset.StandardCharsets

class ShowBranchDiffActionTest : BasePlatformTestCase() {
    fun testRequestUsesForkBaselineAndLiveDocumentWithRenamedLabels() {
        val file = myFixture.configureByText("new-name.txt", "live unsaved\n").virtualFile
        val snapshot = snapshot("old-name.txt", "new-name.txt", "baseline\n")
        val request = ShowBranchDiffAction().createRequest(project, myFixture.editor.document, file, snapshot)

        assertEquals("Branch Changes: Fork point abcdef1 (origin/main) → local edits", request.title)
        assertEquals("old-name.txt — Fork point abcdef1 (origin/main)", request.contentTitles[0])
        assertEquals("new-name.txt — live document (includes local edits)", request.contentTitles[1])
        assertEquals("baseline\n", (request.contents[0] as DocumentContent).document.text)
        assertEquals("live unsaved\n", (request.contents[1] as DocumentContent).document.text)
    }

    fun testBranchHeadRequestUsesModeSpecificLabelsWithoutForkWording() {
        val file = myFixture.configureByText("new-name.txt", "live unsaved\n").virtualFile
        val snapshot = snapshot(
            "old-name.txt",
            "new-name.txt",
            "baseline\n",
            ComparisonMode.BRANCH_HEAD,
        )
        val request = ShowBranchDiffAction().createRequest(project, myFixture.editor.document, file, snapshot)

        assertEquals("Branch Changes: Branch HEAD abcdef1 (origin/main) → local edits", request.title)
        assertEquals("old-name.txt — Branch HEAD abcdef1 (origin/main)", request.contentTitles[0])
        assertEquals("new-name.txt — live document (includes local edits)", request.contentTitles[1])
        assertFalse(
            (listOfNotNull(request.title) + request.contentTitles.filterNotNull())
                .any { it.contains("fork", ignoreCase = true) },
        )
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

    fun testRequestScrollsRightSideToTargetLine() {
        val file = myFixture.configureByText("file.txt", "first\nsecond\nthird\n").virtualFile
        val request = ShowBranchDiffAction().createRequest(
            project,
            myFixture.editor.document,
            file,
            snapshot("file.txt", "file.txt", "baseline\n"),
            1,
        )

        assertEquals(
            Pair.create(Side.RIGHT, 1),
            request.getUserData(DiffUserDataKeys.SCROLL_TO_LINE),
        )
    }

    fun testGutterActionDelegatesEditorAndClickedLine() {
        myFixture.configureByText("file.txt", "first\nsecond\nthird\n")
        var clicked: Pair<Editor, Int>? = null
        val renderer = BranchGutterRenderer(
            com.github.flobilosaurus.branchchanges.model.BranchChangeType.DELETED,
            BranchChangesColors.DELETED,
            "Deleted",
        ) { editor, line -> clicked = Pair.create(editor, line) }
        val y = myFixture.editor.logicalPositionToXY(LogicalPosition(1, 0)).y
        val event = MouseEvent(Canvas(), MouseEvent.MOUSE_CLICKED, 0, 0, 0, y, 1, false)

        assertTrue(renderer.canDoAction(myFixture.editor, event))
        renderer.doAction(myFixture.editor, event)
        assertTrue(event.isConsumed)
        assertSame(myFixture.editor, clicked!!.first)
        assertEquals(1, clicked!!.second)
    }

    private fun snapshot(
        baselinePath: String,
        currentPath: String,
        baseline: String,
        mode: ComparisonMode = ComparisonMode.FORK_POINT,
    ): ComparisonSnapshot =
        ComparisonSnapshot(
            ComparisonIdentity(
                "file:///repo",
                currentPath,
                "feature",
                "refs/remotes/origin/main",
                mode,
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
