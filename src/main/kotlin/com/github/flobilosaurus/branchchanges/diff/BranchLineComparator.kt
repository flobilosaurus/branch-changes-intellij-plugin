package com.github.flobilosaurus.branchchanges.diff

import com.github.flobilosaurus.branchchanges.model.BranchChangeRegion
import com.github.flobilosaurus.branchchanges.model.BranchChangeType
import com.github.flobilosaurus.branchchanges.model.ComparisonLimits
import com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason
import com.github.flobilosaurus.branchchanges.model.normalizedLineCount
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.diff.fragments.LineFragment
import com.intellij.openapi.progress.ProgressIndicator

sealed interface LineComparisonResult {
    data class Success(val regions: List<BranchChangeRegion>) : LineComparisonResult
    data class Unavailable(val reason: ComparisonUnavailableReason) : LineComparisonResult
}

class BranchLineComparator(
    private val comparisonManager: ComparisonManager = ComparisonManager.getInstance(),
) {
    fun compare(
        baselineText: CharSequence,
        currentText: CharSequence,
        indicator: ProgressIndicator,
    ): LineComparisonResult {
        if (baselineText.toString().toByteArray().size > ComparisonLimits.MAX_BYTES ||
            currentText.toString().toByteArray().size > ComparisonLimits.MAX_BYTES
        ) {
            return LineComparisonResult.Unavailable(ComparisonUnavailableReason.FILE_TOO_LARGE)
        }
        if (normalizedLineCount(baselineText) > ComparisonLimits.MAX_LINES ||
            normalizedLineCount(currentText) > ComparisonLimits.MAX_LINES
        ) {
            return LineComparisonResult.Unavailable(ComparisonUnavailableReason.TOO_MANY_LINES)
        }

        return try {
            indicator.checkCanceled()
            val fragments = comparisonManager.compareLines(
                baselineText,
                currentText,
                ComparisonPolicy.DEFAULT,
                indicator,
            )
            indicator.checkCanceled()
            LineComparisonResult.Success(fragments.map(::toRegion))
        } catch (_: DiffTooBigException) {
            LineComparisonResult.Unavailable(ComparisonUnavailableReason.DIFF_TOO_BIG)
        }
    }

    private fun toRegion(fragment: LineFragment): BranchChangeRegion {
        val baselineEmpty = fragment.startLine1 == fragment.endLine1
        val currentEmpty = fragment.startLine2 == fragment.endLine2
        val type = when {
            currentEmpty -> BranchChangeType.DELETED
            baselineEmpty -> BranchChangeType.ADDED
            else -> BranchChangeType.MODIFIED
        }
        return BranchChangeRegion(
            type = type,
            baselineStartLine = fragment.startLine1,
            baselineEndLine = fragment.endLine1,
            currentStartLine = fragment.startLine2,
            currentEndLine = fragment.endLine2,
            anchorLine = fragment.startLine2,
        )
    }
}
