package com.github.flobilosaurus.branchchanges.editor

import com.github.flobilosaurus.branchchanges.BranchChangesProjectService
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener

class BranchEditorFactoryListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        event.editor.project?.let { project ->
            if (!project.isDisposed) BranchChangesProjectService.getInstance(project).onEditorCreated(event.editor)
        }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        event.editor.project?.let { project ->
            if (!project.isDisposed) BranchChangesProjectService.getInstance(project).onEditorReleased(event.editor)
        }
    }
}
