package dev.pam.nativeapp.modules

import org.junit.Assert.assertEquals
import org.junit.Test

class PamReloadListenersTest {
    @Test
    fun deepLinkWaiterCanBeRearmedAfterReload() {
        var staleCompletions = 0
        var activeCompletions = 0
        PamDeepLinks.next(ModuleCompletion { _, _ -> staleCompletions++ })

        PamDeepLinks.prepareReload()
        PamDeepLinks.next(ModuleCompletion { status, _ ->
            if (status == ModuleResultStatus.SUCCESS) activeCompletions++
        })
        PamDeepLinks.reportOpened("zechat://profile/42")

        assertEquals(0, staleCompletions)
        assertEquals(1, activeCompletions)
    }




}
