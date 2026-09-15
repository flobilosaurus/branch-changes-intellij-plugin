package com.github.flobilosaurus.branchchanges.model

enum class BranchChangeType {
    ADDED,
    MODIFIED,
    DELETED,
}

data class BranchChangedSpan(
    val currentStartOffset: Int,
    val currentEndOffset: Int,
) {
    init {
        require(currentStartOffset >= 0)
        require(currentEndOffset > currentStartOffset)
    }
}

/** Half-open baseline/current line ranges. Deleted regions use an empty current range and [anchorLine]. */
data class BranchChangeRegion(
    val type: BranchChangeType,
    val baselineStartLine: Int,
    val baselineEndLine: Int,
    val currentStartLine: Int,
    val currentEndLine: Int,
    val anchorLine: Int = currentStartLine,
    val changedSpans: List<BranchChangedSpan> = emptyList(),
) {
    init {
        require(baselineStartLine >= 0 && baselineEndLine >= baselineStartLine)
        require(currentStartLine >= 0 && currentEndLine >= currentStartLine)
        require(anchorLine >= 0)
        require(type == BranchChangeType.DELETED || currentStartLine < currentEndLine)
        require(type != BranchChangeType.DELETED || currentStartLine == currentEndLine)
        require(type == BranchChangeType.MODIFIED || changedSpans.isEmpty())
    }
}
