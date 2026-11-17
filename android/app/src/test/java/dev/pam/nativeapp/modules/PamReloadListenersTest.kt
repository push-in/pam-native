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
        PamDeepLinks.reportOpened("app://profile/42")

        assertEquals(0, staleCompletions)
        assertEquals(1, activeCompletions)
    }

    @Test
    fun aStaleWaitArmedAfterTheReloadIsSupersededInsteadOfFailingTheNewListener() {
        PamDeepLinks.prepareReload()
        val stale = mutableListOf<String>()
        var delivered: String? = null
        var failure: String? = null
        // The replaced request's wait reaches the module after prepareReload.
        PamDeepLinks.next(ModuleCompletion { status, payload ->
            stale += "$status:${String(payload)}"
        })
        PamDeepLinks.next(ModuleCompletion { status, payload ->
            if (status == ModuleResultStatus.SUCCESS) delivered = String(payload) else failure = String(payload)
        })
        PamDeepLinks.reportOpened("app://orders/7")

        assertEquals(listOf("FAILURE:${PamDeepLinks.SUPERSEDED}"), stale)
        assertEquals(null, failure)
        assertEquals(true, delivered?.contains("app://orders/7"))
        PamDeepLinks.prepareReload()
    }
}
