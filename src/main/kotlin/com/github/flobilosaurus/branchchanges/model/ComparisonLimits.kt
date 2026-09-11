package com.github.flobilosaurus.branchchanges.model

object ComparisonLimits {
    const val MAX_BYTES: Long = 2L * 1024L * 1024L
    const val MAX_LINES: Int = 10_000
}

fun normalizedLineCount(text: CharSequence): Int {
    if (text.isEmpty()) return 0
    var count = 1
    for (character in text) if (character == '\n') count++
    return if (text.last() == '\n') count - 1 else count
}
