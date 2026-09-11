package com.github.flobilosaurus.branchchanges.ui

import com.github.flobilosaurus.branchchanges.BranchChangesBundle
import com.github.flobilosaurus.branchchanges.BranchChangesProjectService
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget

class BranchChangesStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.MultipleTextValuesPresentation {
    private val service = BranchChangesProjectService.getInstance(project)
    private var statusBar: StatusBar? = null

    init {
        service.initialize()
        project.messageBus.connect(this).subscribe(
            BranchChangesProjectService.UI_TOPIC,
            BranchChangesProjectService.UiListener { statusBar?.updateWidget(ID()) },
        )
    }

    override fun ID(): String = BranchChangesProjectService.WIDGET_ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getSelectedValue(): String = BranchChangesBundle.message(
        "status.prefix",
        when (val state = service.uiState()) {
            is BranchChangesProjectService.UiState.Base -> service.displayRef(state.baseRef)
            is BranchChangesProjectService.UiState.SelectBase -> BranchChangesBundle.message("status.selectBase")
            BranchChangesProjectService.UiState.Disabled -> BranchChangesBundle.message("status.disabled")
            is BranchChangesProjectService.UiState.InvalidBase,
            is BranchChangesProjectService.UiState.ComparisonUnavailable,
            is BranchChangesProjectService.UiState.Unavailable,
            BranchChangesProjectService.UiState.NoFile,
            -> BranchChangesBundle.message("status.unavailable")
        },
    )

    override fun getTooltipText(): String = when (val state = service.uiState()) {
        is BranchChangesProjectService.UiState.Base -> BranchChangesBundle.message(
            "status.tooltip.base",
            service.displayRef(state.baseRef),
        )

        is BranchChangesProjectService.UiState.SelectBase -> BranchChangesBundle.message(
            "status.tooltip.selectBase",
            state.context.branchName,
        )

        BranchChangesProjectService.UiState.Disabled -> BranchChangesBundle.message("status.tooltip.disabled")
        is BranchChangesProjectService.UiState.InvalidBase -> BranchChangesBundle.message(
            "status.tooltip.unavailable",
            BranchChangesBundle.message("unavailable.invalidBase"),
        )

        is BranchChangesProjectService.UiState.ComparisonUnavailable -> BranchChangesBundle.message(
            "status.tooltip.unavailable",
            state.reason.name.lowercase().replace('_', ' '),
        )

        is BranchChangesProjectService.UiState.Unavailable -> BranchChangesBundle.message(
            "status.tooltip.unavailable",
            unavailableText(state.reason),
        )

        BranchChangesProjectService.UiState.NoFile -> BranchChangesBundle.message(
            "status.tooltip.unavailable",
            BranchChangesBundle.message("unavailable.noFile"),
        )
    }

    override fun getPopup(): JBPopup? {
        if (project.isDisposed) return null
        val group = DefaultActionGroup()
        val hasBase = service.uiState() is BranchChangesProjectService.UiState.Base
        group.add(
            DumbAwareAction.create(
                BranchChangesBundle.message(if (hasBase) "action.changeBase" else "action.selectBase"),
            ) { service.selectBase() },
        )
        group.addSeparator()
        group.add(
            DumbAwareAction.create(
                BranchChangesBundle.message(if (service.isEnabled()) "action.disable" else "action.enable"),
            ) { service.toggleEnabled() },
        )

        val component = statusBar?.component ?: return null
        return JBPopupFactory.getInstance().createActionGroupPopup(
            BranchChangesBundle.message("plugin.name"),
            group,
            DataManager.getInstance().getDataContext(component),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
    }

    private fun unavailableText(reason: com.github.flobilosaurus.branchchanges.git.BranchContextResolver.UnavailableReason): String =
        BranchChangesBundle.message(
            when (reason) {
                com.github.flobilosaurus.branchchanges.git.BranchContextResolver.UnavailableReason.NOT_GIT ->
                    "unavailable.notGit"

                com.github.flobilosaurus.branchchanges.git.BranchContextResolver.UnavailableReason.DETACHED_HEAD ->
                    "unavailable.detachedHead"

                com.github.flobilosaurus.branchchanges.git.BranchContextResolver.UnavailableReason.UNBORN_BRANCH ->
                    "unavailable.unbornBranch"

                com.github.flobilosaurus.branchchanges.git.BranchContextResolver.UnavailableReason.NO_LOCAL_BRANCH ->
                    "unavailable.noLocalBranch"
            },
        )

    override fun dispose() {
        statusBar = null
    }
}
