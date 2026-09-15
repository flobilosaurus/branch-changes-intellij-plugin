package com.github.flobilosaurus.branchchanges.editor

import com.github.flobilosaurus.branchchanges.BranchChangesBundle
import com.github.flobilosaurus.branchchanges.model.ComparisonSnapshot
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.VirtualFile

class ShowBranchDiffAction(
    private val diffManager: DiffManager = DiffManager.getInstance(),
    private val contentFactory: DiffContentFactory = DiffContentFactory.getInstance(),
) {
    fun show(
        project: Project,
        document: Document,
        file: VirtualFile,
        snapshot: ComparisonSnapshot,
        targetLine: Int,
    ) {
        if (project.isDisposed || !file.isValid) return
        diffManager.showDiff(project, createRequest(project, document, file, snapshot, targetLine))
    }

    internal fun createRequest(
        project: Project,
        document: Document,
        file: VirtualFile,
        snapshot: ComparisonSnapshot,
        targetLine: Int? = null,
    ): SimpleDiffRequest {
        val base = displayRef(snapshot.identity.baseRef)
        val mode = snapshot.identity.mode.displayName()
        val baseline = snapshot.identity.baselineHash.take(7)
        val oldLabel = BranchChangesBundle.message(
            "diff.baselineLabel",
            snapshot.baselinePath,
            mode,
            baseline,
            base,
        )
        val newLabel = BranchChangesBundle.message("diff.liveLabel", snapshot.currentPath)
        return SimpleDiffRequest(
            BranchChangesBundle.message("diff.title", mode, baseline, base),
            contentFactory.create(project, snapshot.baselineText, file.fileType),
            contentFactory.create(project, document, file),
            oldLabel,
            newLabel,
        ).also { request ->
            if (targetLine != null) {
                val line = targetLine.coerceIn(0, document.lineCount - 1)
                request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, Pair.create(Side.RIGHT, line))
            }
        }
    }

    private fun displayRef(ref: String): String = ref
        .removePrefix("refs/remotes/")
        .removePrefix("refs/heads/")
}
