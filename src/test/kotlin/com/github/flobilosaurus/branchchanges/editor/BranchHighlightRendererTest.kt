package com.github.flobilosaurus.branchchanges.editor

import com.github.flobilosaurus.branchchanges.model.BranchChangeRegion
import com.github.flobilosaurus.branchchanges.model.BranchChangeType
import com.github.flobilosaurus.branchchanges.model.ComparisonIdentity
import com.github.flobilosaurus.branchchanges.model.ComparisonSnapshot
import com.github.flobilosaurus.branchchanges.model.TextMetadata
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.charset.StandardCharsets

class BranchHighlightRendererTest : BasePlatformTestCase() {
    fun testRendersRangesDeletionAnchorsAndAccessibleTooltips() {
        myFixture.configureByText("sample.txt", "added\nmodified\nlast\n")
        val editor = myFixture.editor
        val renderer = BranchHighlightRenderer()
        val snapshot = snapshot(
            listOf(
                BranchChangeRegion(BranchChangeType.ADDED, 0, 0, 0, 1),
                BranchChangeRegion(BranchChangeType.MODIFIED, 0, 1, 1, 2),
                BranchChangeRegion(BranchChangeType.DELETED, 1, 2, 2, 2, 2),
            ),
        )

        renderer.replace(editor, snapshot)

        val owned = renderer.ownedHighlighters(editor)
        assertSize(3, owned)
        assertEquals(BranchChangesColors.ADDED, owned[0].textAttributesKey)
        assertEquals(HighlighterTargetArea.LINES_IN_RANGE, owned[0].targetArea)
        assertEquals(BranchChangesColors.MODIFIED, owned[1].textAttributesKey)
        assertEquals(HighlighterTargetArea.EXACT_RANGE, owned[2].targetArea)
        assertEquals(owned[2].startOffset, owned[2].endOffset)
        val gutter = owned[1].lineMarkerRenderer as BranchGutterRenderer
        assertTrue(gutter.tooltipText.contains("Modified since fork with origin/main at abcdef1"))
        assertEquals(gutter.tooltipText, gutter.accessibleName)
        assertEquals(gutter.tooltipText, gutter.accessibleTooltipText)
    }

    fun testAtomicReplacementAndCleanupLeaveUnrelatedHighlighter() {
        myFixture.configureByText("sample.txt", "one\ntwo\n")
        val editor = myFixture.editor
        val unrelated = editor.markupModel.addRangeHighlighter(
            0,
            0,
            1,
            null,
            HighlighterTargetArea.EXACT_RANGE,
        )
        val renderer = BranchHighlightRenderer()
        renderer.replace(
            editor,
            snapshot(listOf(BranchChangeRegion(BranchChangeType.ADDED, 0, 0, 0, 1))),
        )
        val first = renderer.ownedHighlighters(editor).single()

        renderer.replace(editor, snapshot(emptyList()))

        assertFalse(first.isValid)
        assertEmpty(renderer.ownedHighlighters(editor))
        assertTrue(unrelated.isValid)
        renderer.clearAll()
        assertTrue(unrelated.isValid)
    }

    fun testColorPageExposesAllConfigurableKeys() {
        val descriptors = BranchChangesColors().attributeDescriptors
        assertEquals(
            setOf(BranchChangesColors.ADDED, BranchChangesColors.MODIFIED, BranchChangesColors.DELETED),
            descriptors.map { it.key }.toSet(),
        )
    }

    private fun snapshot(regions: List<BranchChangeRegion>): ComparisonSnapshot {
        val text = "added\nmodified\nlast\n"
        return ComparisonSnapshot(
            ComparisonIdentity(
                "file:///repo",
                "sample.txt",
                "feature",
                "refs/remotes/origin/main",
                "1".repeat(40),
                "2".repeat(40),
                "abcdef1234567890",
                "index",
                1,
                1,
            ),
            "sample.txt",
            "sample.txt",
            "baseline\n",
            text,
            TextMetadata(9, 1, StandardCharsets.UTF_8, true),
            TextMetadata(text.length.toLong(), 3, StandardCharsets.UTF_8, true),
            regions,
        )
    }
}
