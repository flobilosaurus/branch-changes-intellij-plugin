package com.github.flobilosaurus.branchchanges.editor

import com.github.flobilosaurus.branchchanges.BranchChangesProjectService
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class BranchFileEditorManagerListener : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val service = service(source.project) ?: return
        source.getEditors(file).filterIsInstance<TextEditor>().forEach { service.onEditorCreated(it.editor) }
        service.onEditorContextChanged()
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        service(source.project)?.onEditorContextChanged()
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val service = service(event.manager.project) ?: return
        event.newFile?.let { file ->
            event.manager.getEditors(file).filterIsInstance<TextEditor>().forEach { service.onEditorCreated(it.editor) }
        }
        service.onEditorContextChanged()
    }

    private fun service(project: Project): BranchChangesProjectService? =
        if (project.isDisposed) null else BranchChangesProjectService.getInstance(project)
}
