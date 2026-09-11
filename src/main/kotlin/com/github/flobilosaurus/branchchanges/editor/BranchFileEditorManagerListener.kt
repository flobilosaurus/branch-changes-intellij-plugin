package com.github.flobilosaurus.branchchanges.editor

import com.github.flobilosaurus.branchchanges.BranchChangesProjectService
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class BranchFileEditorManagerListener : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        refresh(source.project)
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        refresh(source.project)
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        refresh(event.manager.project)
    }

    private fun refresh(project: Project) {
        if (!project.isDisposed) {
            BranchChangesProjectService.getInstance(project).onEditorContextChanged()
        }
    }
}
