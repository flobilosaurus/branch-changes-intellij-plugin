package com.github.flobilosaurus.branchchanges.editor

import com.github.flobilosaurus.branchchanges.model.ComparisonSnapshot
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
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
    ) {
        if (project.isDisposed || !file.isValid) return
        diffManager.showDiff(project, createRequest(project, document, file, snapshot))
    }

    internal fun createRequest(
        project: Project,
        document: Document,
        file: VirtualFile,
        snapshot: ComparisonSnapshot,
    ): SimpleDiffRequest {
        val base = displayRef(snapshot.identity.baseRef)
        val fork = snapshot.identity.forkHash.take(7)
        val oldLabel = "${snapshot.baselinePath} — fork $fork ($base)"
        val newLabel = "${snapshot.currentPath} — live document (includes local edits)"
        return SimpleDiffRequest(
            "Branch Changes: $base fork $fork → local edits",
            contentFactory.create(project, snapshot.baselineText, file.fileType),
            contentFactory.create(project, document, file),
            oldLabel,
            newLabel,
        )
    }

    private fun displayRef(ref: String): String = ref
        .removePrefix("refs/remotes/")
        .removePrefix("refs/heads/")
}
