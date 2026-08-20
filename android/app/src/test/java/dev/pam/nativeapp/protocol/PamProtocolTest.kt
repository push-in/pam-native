package dev.pam.nativeapp.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PamProtocolTest {
    @Test
    fun protocolEnumsRemainSequentialAndAppendOnly() {
        assertEquals((1..31).toList(), NodeKind.entries.map(NodeKind::value))
        assertEquals((1..65).toList(), EventKind.entries.map(EventKind::value))
        assertEquals((1..451).toList(), PropKey.entries.map(PropKey::value))
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

    @Test
    fun decoderEnforcesSharedPropertyAndNodeLimits() {
        val acceptedText = BatchDecoder.decode(textBatch(1024 * 1024))
        val text = (acceptedText.single() as Mutation.Create)
            .node.properties.getValue(PropKey.TEXT) as PropValue.Text
        assertEquals(1024 * 1024, text.value.length)
        assertThrows(IllegalStateException::class.java) {
            BatchDecoder.decode(textBatch(1024 * 1024 + 1))
        }

        assertEquals(
            128,
            (BatchDecoder.decode(propertyBatch(128)).single() as Mutation.Create)
                .node.properties.size,
        )
        assertThrows(IllegalStateException::class.java) {
            BatchDecoder.decode(propertyBatch(129))
        }
    }

    private fun textBatch(length: Int): ByteBuffer =
        batch(propertyCount = 1, payloadBytes = length).apply {
            putShort(PropKey.TEXT.value.toShort())
            put(1.toByte())
            putInt(length)
            repeat(length) { put('a'.code.toByte()) }
            flip()
        }

    private fun propertyBatch(count: Int): ByteBuffer =
        batch(propertyCount = count, payloadBytes = count * 4).apply {
            repeat(count) { index ->
                putShort((index + 1).toShort())
                put(4.toByte())
                put(1.toByte())
            }
            flip()
        }

    private fun batch(propertyCount: Int, payloadBytes: Int): ByteBuffer =
        ByteBuffer.allocate(48 + payloadBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("PNB1".toByteArray())
                putShort(1.toShort())
                putInt(1)
                put(1.toByte())
                putLong(1L)
                putLong(0L)
                putInt(0)
                put(NodeKind.SCREEN.value.toByte())
                putShort(propertyCount.toShort())
            }

    private fun String.hexBytes(): ByteArray =
        chunked(2)
            .map { byte -> byte.toInt(16).toByte() }
            .toByteArray()
}
