package com.github.flobilosaurus.branchchanges.model

import git4idea.repo.GitRepository

/** Immutable repository/branch identity captured before background comparison starts. */
data class BranchContext(
    val repository: GitRepository,
    val rootUrl: String,
    val rootPath: String,
    val relativePath: String,
    val branchName: String,
    val headHash: String,
    val configuredBaseRef: String?,
    val comparisonMode: ComparisonMode,
)
