package dev.pam.nativeapp.modules

import java.util.ArrayDeque

internal class WatchChannel {
    private val queue = ArrayDeque<ByteArray>()
    private var waiter: ModuleCompletion? = null
    private var closed = false

    @Synchronized
    fun next(completion: ModuleCompletion) {
        if (closed) {
            completion.complete(ModuleResultStatus.FAILURE, "Observation is closed".toByteArray())
        } else if (queue.isNotEmpty()) {
            completion.complete(ModuleResultStatus.SUCCESS, queue.removeFirst())
        } else if (waiter != null) {
            completion.complete(ModuleResultStatus.FAILURE, "Observation already has a pending read".toByteArray())
        } else {
            waiter = completion
        }
    }

    fun offer(payload: ByteArray) {
        val callback = synchronized(this) {
            if (closed) return
            val value = waiter
            if (value == null) {
                if (queue.size >= 4) queue.removeFirst()
                queue.addLast(payload)
            } else {
                waiter = null
            }
            value
        }
        callback?.complete(ModuleResultStatus.SUCCESS, payload)
    }

    fun close() {
        val callback = synchronized(this) {
            if (closed) return
            closed = true
            queue.clear()
            val value = waiter
            waiter = null
            value
        }
        callback?.complete(ModuleResultStatus.FAILURE, "Observation stopped".toByteArray())
    }
}
