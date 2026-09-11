package com.github.flobilosaurus.branchchanges.ui

import com.github.flobilosaurus.branchchanges.BranchChangesBundle
import com.github.flobilosaurus.branchchanges.BranchChangesProjectService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import kotlinx.coroutines.CoroutineScope

class BranchChangesStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = BranchChangesProjectService.WIDGET_ID

    override fun getDisplayName(): String = BranchChangesBundle.message("plugin.name")

    override fun isAvailable(project: Project): Boolean = !project.isDefault

    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget =
        BranchChangesStatusBarWidget(project)

    override fun isEnabledByDefault(): Boolean = true

    override fun isConfigurable(): Boolean = true
}
