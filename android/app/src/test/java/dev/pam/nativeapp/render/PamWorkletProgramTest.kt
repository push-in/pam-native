package dev.pam.nativeapp.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PamWorkletProgramTest {
    @Test
    fun executesPhpWorkletBytecodeWithoutPhpFrames() {
        val bytes = ByteBuffer.allocate(6 + 2 + 2 + 32 + 2 + 16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("PNW1".toByteArray())
            .putShort(3)
            .put(1.toByte()).put(0.toByte())
            .put(8.toByte()).put(4.toByte())
            .putDouble(0.0).putDouble(200.0).putDouble(1.0).putDouble(0.0)
            .put(7.toByte()).put(2.toByte())
            .putDouble(0.0).putDouble(1.0)
            .array()

        val program = PamWorkletProgram.decode(bytes)

        assertEquals(0.75, program?.evaluate(50.0) ?: -1.0, 0.000_001)
    }

    @Test
    fun rejectsUnboundedMalformedPrograms() {
        assertNull(PamWorkletProgram.decode("PNW1".toByteArray()))
        val divisionByZero = ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("PNW1".toByteArray())
            .putShort(1)
            .put(6.toByte()).put(1.toByte())
            .putDouble(0.0)
            .array()
        assertNull(PamWorkletProgram.decode(divisionByZero))
    }
}
