package com.github.flobilosaurus.branchchanges.model

import com.github.flobilosaurus.branchchanges.BranchChangesBundle

enum class ComparisonMode(
    val serializedId: String,
    private val displayNameKey: String,
    private val actionTextKey: String,
) {
    FORK_POINT("FORK_POINT", "mode.forkPoint", "action.mode.forkPoint"),
    BRANCH_HEAD("BRANCH_HEAD", "mode.branchHead", "action.mode.branchHead"),
    ;

    fun displayName(): String = BranchChangesBundle.message(displayNameKey)

    fun actionText(): String = BranchChangesBundle.message(actionTextKey)

    companion object {
        @JvmStatic
        fun fromSerialized(value: String?): ComparisonMode =
            entries.firstOrNull { it.serializedId == value } ?: FORK_POINT
    }
}
