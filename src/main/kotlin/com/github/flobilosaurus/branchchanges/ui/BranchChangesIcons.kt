package com.github.flobilosaurus.branchchanges.ui

import com.github.flobilosaurus.branchchanges.model.ComparisonMode
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object BranchChangesIcons {
    @JvmField
    val ForkPoint: Icon = IconLoader.getIcon("/icons/comparisonFork.svg", BranchChangesIcons::class.java)

    @JvmField
    val BranchHead: Icon = IconLoader.getIcon("/icons/comparisonBranchHead.svg", BranchChangesIcons::class.java)

    fun forMode(mode: ComparisonMode): Icon = when (mode) {
        ComparisonMode.FORK_POINT -> ForkPoint
        ComparisonMode.BRANCH_HEAD -> BranchHead
    }
}
