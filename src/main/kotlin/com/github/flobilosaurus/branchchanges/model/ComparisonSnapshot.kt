package com.github.flobilosaurus.branchchanges.model

import java.nio.charset.Charset

data class TextMetadata(
    val byteSize: Long,
    val lineCount: Int,
    val charset: Charset,
    val hadTrailingNewline: Boolean,
)

data class ComparisonIdentity(
    val rootUrl: String,
    val relativePath: String,
    val branchName: String,
    val baseRef: String,
    val mode: ComparisonMode,
    val baseTipHash: String,
    val headHash: String,
    val baselineHash: String,
    val indexFingerprint: String,
    val documentModificationStamp: Long,
    val generation: Long,
)

data class ComparisonSnapshot(
    val identity: ComparisonIdentity,
    val baselinePath: String,
    val currentPath: String,
    val baselineText: String,
    val currentText: String,
    val baselineMetadata: TextMetadata,
    val currentMetadata: TextMetadata,
    val regions: List<BranchChangeRegion>,
)

enum class ComparisonUnavailableReason {
    DISABLED,
    NOT_GIT,
    DETACHED_HEAD,
    UNBORN_BRANCH,
    NO_LOCAL_BRANCH,
    NO_BASE,
    INVALID_BASE,
    NO_MERGE_BASE,
    MULTIPLE_MERGE_BASES,
    MISSING_OBJECT,
    NEW_FILE_IGNORED,
    BINARY,
    FILE_TOO_LARGE,
    TOO_MANY_LINES,
    DIFF_TOO_BIG,
    MALFORMED_GIT_OUTPUT,
    INDEX_CHANGED,
    FILE_CHANGED,
    CANCELLED,
    GIT_FAILURE,
    GIT_TIMEOUT,
    INVALID_EDITOR,
}

sealed interface ComparisonResult {
    data class Available(val snapshot: ComparisonSnapshot) : ComparisonResult
    data class Unavailable(
        val reason: ComparisonUnavailableReason,
        val detail: String? = null,
    ) : ComparisonResult
}
