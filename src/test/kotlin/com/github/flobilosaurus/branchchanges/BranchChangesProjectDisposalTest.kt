package com.github.flobilosaurus.branchchanges

import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil

class BranchChangesProjectDisposalTest : HeavyPlatformTestCase() {
    fun testProjectDisposalCancelsAndDisposesService() {
        val service = BranchChangesProjectService.getInstance(project)
        service.initialize()

        assertFalse(service.disposed)
        assertTrue(ProjectManagerEx.getInstanceEx().forceCloseProject(project))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue(project.isDisposed)
        assertTrue(service.disposed)
    }
}
