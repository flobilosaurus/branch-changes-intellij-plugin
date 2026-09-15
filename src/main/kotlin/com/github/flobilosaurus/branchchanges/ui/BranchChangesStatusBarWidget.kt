package com.github.flobilosaurus.branchchanges.ui

import com.github.flobilosaurus.branchchanges.BranchChangesBundle
import com.github.flobilosaurus.branchchanges.BranchChangesProjectService
import com.github.flobilosaurus.branchchanges.model.ComparisonMode
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import javax.swing.Icon

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

    override fun getIcon(): Icon = BranchChangesIcons.forMode(service.activeComparisonMode())

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
        is BranchChangesProjectService.UiState.Base -> {
            val snapshot = service.matchingSnapshot()
            if (snapshot != null) {
                BranchChangesBundle.message(
                    "status.tooltip.ready",
                    state.mode.displayName(),
                    service.displayRef(state.baseRef),
                    snapshot.identity.baselineHash.take(7),
                )
            } else {
                BranchChangesBundle.message(
                    "status.tooltip.pending",
                    state.mode.displayName(),
                    service.displayRef(state.baseRef),
                )
            }
        }

        is BranchChangesProjectService.UiState.SelectBase -> BranchChangesBundle.message(
            "status.tooltip.selectBase",
            state.context.comparisonMode.displayName(),
            state.context.branchName,
        )

        BranchChangesProjectService.UiState.Disabled -> {
            val mode = service.activeComparisonMode().displayName()
            service.activeBaseRef()?.let {
                BranchChangesBundle.message(
                    "status.tooltip.disabled.configured",
                    mode,
                    service.displayRef(it),
                )
            } ?: BranchChangesBundle.message("status.tooltip.disabled.unconfigured", mode)
        }

        is BranchChangesProjectService.UiState.InvalidBase -> BranchChangesBundle.message(
            "status.tooltip.unavailable.configured",
            state.context.comparisonMode.displayName(),
            service.displayRef(state.baseRef),
            BranchChangesBundle.message("unavailable.invalidBase"),
        )

        is BranchChangesProjectService.UiState.ComparisonUnavailable -> BranchChangesBundle.message(
            "status.tooltip.unavailable.configured",
            state.mode.displayName(),
            service.displayRef(state.baseRef),
            state.reason.name.lowercase().replace('_', ' '),
        )

        is BranchChangesProjectService.UiState.Unavailable -> BranchChangesBundle.message(
            "status.tooltip.unavailable.unconfigured",
            service.activeComparisonMode().displayName(),
            unavailableText(state.reason),
        )

        BranchChangesProjectService.UiState.NoFile -> BranchChangesBundle.message(
            "status.tooltip.unavailable.unconfigured",
            service.activeComparisonMode().displayName(),
            BranchChangesBundle.message("unavailable.noFile"),
        )
    }

    override fun getPopup(): JBPopup? {
        if (project.isDisposed) return null
        val component = statusBar?.component ?: return null
        return JBPopupFactory.getInstance().createActionGroupPopup(
            BranchChangesBundle.message("plugin.name"),
            createActionGroup(),
            DataManager.getInstance().getDataContext(component),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
    }

    internal fun createActionGroup(): DefaultActionGroup = DefaultActionGroup().apply {
        add(modeAction(ComparisonMode.FORK_POINT))
        add(modeAction(ComparisonMode.BRANCH_HEAD))
        addSeparator()
        val state = service.uiState()
        val hasBase = state is BranchChangesProjectService.UiState.Base ||
            state is BranchChangesProjectService.UiState.InvalidBase ||
            state is BranchChangesProjectService.UiState.ComparisonUnavailable
        add(
            DumbAwareAction.create(
                BranchChangesBundle.message(if (hasBase) "action.changeBase" else "action.selectBase"),
            ) { service.selectBase() },
        )
        addSeparator()
        add(
            DumbAwareAction.create(
                BranchChangesBundle.message(if (service.isEnabled()) "action.disable" else "action.enable"),
            ) { service.toggleEnabled() },
        )
    }

    private fun modeAction(mode: ComparisonMode): DumbAwareAction = object : DumbAwareAction(
        modeActionText(mode),
        null,
        BranchChangesIcons.forMode(mode),
    ) {
        override fun actionPerformed(event: AnActionEvent) {
            service.selectMode(mode)
        }

        override fun update(event: AnActionEvent) {
            val active = service.activeComparisonMode() == mode
            event.presentation.text = modeActionText(mode)
            event.presentation.isEnabled = service.hasResolvableBranchContext() && !active
        }
    }

    private fun modeActionText(mode: ComparisonMode): String =
        if (service.activeComparisonMode() == mode) {
            BranchChangesBundle.message("action.mode.active", mode.actionText())
        } else {
            mode.actionText()
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
