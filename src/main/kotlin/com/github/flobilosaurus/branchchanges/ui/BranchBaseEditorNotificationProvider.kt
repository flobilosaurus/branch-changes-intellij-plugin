package com.github.flobilosaurus.branchchanges.ui

import com.github.flobilosaurus.branchchanges.BranchChangesBundle
import com.github.flobilosaurus.branchchanges.BranchChangesProjectService
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.function.Function
import javax.swing.JComponent

class BranchBaseEditorNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        if (!file.isValid || file.isDirectory || file.fileType.isBinary) return null
        val service = BranchChangesProjectService.getInstance(project)
        service.initialize()

        return when (val state = service.uiState(file)) {
            is BranchChangesProjectService.UiState.SelectBase -> Function { fileEditor ->
                createPanel(
                    fileEditor,
                    BranchChangesBundle.message("notification.missing", state.context.branchName),
                    service,
                    file,
                )
            }

            is BranchChangesProjectService.UiState.InvalidBase -> Function { fileEditor ->
                createPanel(
                    fileEditor,
                    BranchChangesBundle.message(
                        "notification.invalid",
                        service.displayRef(state.baseRef),
                        state.context.branchName,
                    ),
                    service,
                    file,
                )
            }

            is BranchChangesProjectService.UiState.ComparisonUnavailable -> Function { fileEditor ->
                EditorNotificationPanel(fileEditor).apply {
                    setText(comparisonUnavailableText(state.reason))
                }
            }

            BranchChangesProjectService.UiState.Disabled,
            BranchChangesProjectService.UiState.NoFile,
            is BranchChangesProjectService.UiState.Base,
            is BranchChangesProjectService.UiState.Unavailable,
            -> null
        }
    }

    private fun comparisonUnavailableText(
        reason: com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason,
    ): String = BranchChangesBundle.message(
        when (reason) {
            com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.NO_BASE -> "unavailable.noBase"
            com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.INVALID_BASE -> "unavailable.invalidBase"
            com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.BINARY -> "unavailable.binary"
            com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.NEW_FILE_IGNORED -> "unavailable.ignored"
            com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.FILE_TOO_LARGE -> "unavailable.fileTooLarge"
            com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.TOO_MANY_LINES -> "unavailable.tooManyLines"
            com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.NO_MERGE_BASE -> "unavailable.noMergeBase"
            com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.MULTIPLE_MERGE_BASES -> "unavailable.multipleMergeBases"
            com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.DIFF_TOO_BIG -> "unavailable.diffTooBig"
            com.github.flobilosaurus.branchchanges.model.ComparisonUnavailableReason.INDEX_CHANGED -> "unavailable.indexChanged"
            else -> "unavailable.gitFailure"
        },
    )

    private fun createPanel(
        fileEditor: FileEditor,
        text: String,
        service: BranchChangesProjectService,
        file: VirtualFile,
    ): EditorNotificationPanel = EditorNotificationPanel(fileEditor).apply {
        setText(text)
        createActionLabel(BranchChangesBundle.message("action.selectBase")) {
            service.selectBase(file)
        }
    }
}
