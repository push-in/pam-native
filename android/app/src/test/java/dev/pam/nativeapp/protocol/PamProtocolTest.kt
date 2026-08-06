package dev.pam.nativeapp.protocol

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PamProtocolTest {
    @Test
    fun protocolEnumsRemainSequentialAndAppendOnly() {
        assertEquals((1..31).toList(), NodeKind.entries.map(NodeKind::value))
        assertEquals((1..64).toList(), EventKind.entries.map(EventKind::value))
        assertEquals((1..447).toList(), PropKey.entries.map(PropKey::value))
        assertEquals(
            (1..19).toList(),
            dev.pam.nativeapp.modules.NativeOperation.entries.map { it.value },
        )
    }

    @Test
    fun rustGoldenSetRootBatchDecodesOnAndroid() {
        val mutations = BatchDecoder.decode(
            ByteBuffer.wrap(
                "504e4231010001000000060100000000000000".hexBytes(),
            ),
        )

        assertEquals(listOf(Mutation.SetRoot(1)), mutations)
    }

    @Test
    fun decoderRejectsVersionMismatchAndTrailingBytes() {
        val golden = "504e4231010001000000060100000000000000".hexBytes()
        val wrongVersion = golden.copyOf().also { it[4] = 2 }
        val trailing = golden + byteArrayOf(0)

        assertThrows(IllegalStateException::class.java) {
            BatchDecoder.decode(ByteBuffer.wrap(wrongVersion))
        }
        assertThrows(IllegalStateException::class.java) {
            BatchDecoder.decode(ByteBuffer.wrap(trailing))
        }
    }

    private fun String.hexBytes(): ByteArray =
        chunked(2)
            .map { byte -> byte.toInt(16).toByte() }
            .toByteArray()
}
