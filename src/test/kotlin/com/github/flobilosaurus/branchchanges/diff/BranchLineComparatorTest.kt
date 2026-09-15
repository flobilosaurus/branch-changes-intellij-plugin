package com.github.flobilosaurus.branchchanges.diff

import com.github.flobilosaurus.branchchanges.model.BranchChangeType
import com.github.flobilosaurus.branchchanges.model.ComparisonLimits
import com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BranchLineComparatorTest : BasePlatformTestCase() {
    private lateinit var comparator: BranchLineComparator

    override fun setUp() {
        super.setUp()
        comparator = BranchLineComparator()
    }

    fun testEqualTextHasNoRegions() {
        assertEmpty(regions("one\ntwo\n", "one\ntwo\n"))
    }

    fun testInsertionAndNewFileAreAdded() {
        val insertion = regions("one\nthree\n", "one\ntwo\nthree\n").single()
        assertEquals(BranchChangeType.ADDED, insertion.type)
        assertEquals(1, insertion.currentStartLine)
        assertEquals(2, insertion.currentEndLine)
        assertEmpty(insertion.changedSpans)

        val fresh = regions("", "one\ntwo\n").single()
        assertEquals(BranchChangeType.ADDED, fresh.type)
        assertEquals(0, fresh.currentStartLine)
        assertEquals(2, fresh.currentEndLine)
        assertEmpty(regions("", ""))
    }

    fun testReplacementIsModifiedWithPreciseCurrentSpans() {
        val current = "one\nprefix new suffix\nthree\n"
        val replacement = regions("one\nprefix old suffix\nthree\n", current).single()
        assertEquals(BranchChangeType.MODIFIED, replacement.type)
        assertEquals(1, replacement.baselineStartLine)
        assertEquals(2, replacement.baselineEndLine)
        assertEquals(1, replacement.currentStartLine)
        assertEquals(2, replacement.currentEndLine)
        assertEquals(listOf("new"), replacement.changedSpans.map { current.substring(it.currentStartOffset, it.currentEndOffset) })

        val characterEdit = "one\nprefix foobaz suffix\nthree\n"
        val characterReplacement = regions("one\nprefix foobar suffix\nthree\n", characterEdit).single()
        assertEquals(
            listOf("foobaz"),
            characterReplacement.changedSpans.map { characterEdit.substring(it.currentStartOffset, it.currentEndOffset) },
        )
    }

    fun testUnequalReplacementRemainsModifiedBlock() {
        val replacement = regions("one\na\nb\nlast\n", "one\nx\nlast\n").single()
        assertEquals(BranchChangeType.MODIFIED, replacement.type)
        assertEquals(1, replacement.baselineStartLine)
        assertEquals(3, replacement.baselineEndLine)
        assertEquals(1, replacement.currentStartLine)
        assertEquals(2, replacement.currentEndLine)
    }

    fun testDeletionAnchorsAtStartMiddleAndEof() {
        val start = regions("gone\nkeep\n", "keep\n").single()
        assertEquals(BranchChangeType.DELETED, start.type)
        assertEquals(0, start.anchorLine)

        val middle = regions("one\ngone\nthree\n", "one\nthree\n").single()
        assertEquals(BranchChangeType.DELETED, middle.type)
        assertEquals(1, middle.anchorLine)

        val eof = regions("one\ngone\n", "one\n").single()
        assertEquals(BranchChangeType.DELETED, eof.type)
        assertEquals(1, eof.anchorLine)
    }

    fun testBlankLinesCrLfAndTrailingNewline() {
        assertEmpty(regions("one\n\ntwo\n", "one\r\n\r\ntwo\r\n"))

        val trailing = regions("one\n", "one").single()
        assertEquals(BranchChangeType.DELETED, trailing.type)
        assertEquals(1, trailing.anchorLine)
    }

    fun testPreflightLimitsAndCancellation() {
        val tooMany = "x\n".repeat(ComparisonLimits.MAX_LINES + 1)
        val result = comparator.compare("", tooMany, EmptyProgressIndicator())
        assertEquals(
            ComparisonUnavailableReason.TOO_MANY_LINES,
            (result as LineComparisonResult.Unavailable).reason,
        )

        val cancelled = EmptyProgressIndicator().also { it.cancel() }
        assertThrows(ProcessCanceledException::class.java) {
            comparator.compare("one\n", "two\n", cancelled)
        }
    }

    private fun regions(baseline: String, current: String) =
        (comparator.compare(
            baseline.replace("\r\n", "\n"),
            current.replace("\r\n", "\n"),
            EmptyProgressIndicator(),
        ) as LineComparisonResult.Success).regions
}
