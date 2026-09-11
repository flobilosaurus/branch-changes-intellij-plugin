package com.github.flobilosaurus.branchchanges

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class BranchChangesProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!project.isDisposed) {
            BranchChangesProjectService.getInstance(project).initialize()
        }
    }
}
