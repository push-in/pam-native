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

    @Test
    fun decoderRejectsMalformedUtf8AcrossTextContainers() {
        val invalid = byteArrayOf(0xc3.toByte(), 0x28)
        assertThrows(ProtocolException::class.java) {
            BatchDecoder.decode(textBatch(invalid))
        }

        val list = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(1)
            putInt(invalid.size)
            put(invalid)
            flip()
        }
        assertThrows(ProtocolException::class.java) { PackedStringList.decode(list) }

        val sections = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(1)
            putInt(invalid.size)
            put(invalid)
            putInt(0)
            flip()
        }
        assertThrows(ProtocolException::class.java) { PackedSectionList.decode(sections) }

        val wire = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1.toShort())
            putShort(1.toShort())
            put('a'.code.toByte())
            put(1.toByte())
            putInt(invalid.size)
            put(invalid)
        }.array()
        assertThrows(IllegalArgumentException::class.java) { WireMap.decode(wire) }
        assertThrows(IllegalArgumentException::class.java) {
            WireMap.encode(mapOf("1invalid" to WireValue.Text("value")))
        }

        val invalidBoolean = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1.toShort())
            putShort(4.toShort())
            put("flag".toByteArray())
            put(4.toByte())
            put(2.toByte())
        }.array()
        assertThrows(IllegalStateException::class.java) { WireMap.decode(invalidBoolean) }

        val duplicate = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(2.toShort())
            repeat(2) { index ->
                putShort(3.toShort())
                put("key".toByteArray())
                put(4.toByte())
                put(index.toByte())
            }
        }.array()
        assertThrows(IllegalArgumentException::class.java) { WireMap.decode(duplicate) }
    }

    @Test
    fun wireProtocolsRejectNonFiniteDecimals() {
        assertThrows(ProtocolException::class.java) {
            BatchDecoder.decode(decimalBatch(Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WireMap.encode(mapOf("value" to WireValue.Decimal(Double.POSITIVE_INFINITY)))
        }

        val wire = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1.toShort())
            putShort(5.toShort())
            put("value".toByteArray())
            put(3.toByte())
            putDouble(Double.NEGATIVE_INFINITY)
        }.array()
        assertThrows(IllegalArgumentException::class.java) { WireMap.decode(wire) }
    }

    @Test
    fun wireMapEncodingIsCanonicalAcrossInsertionOrders() {
        val expected = "04000500616c706861010300000050616d0700656e61626c65640401" +
            "0500726174696f03000000000000f83f04007a657461022a00000000000000"
        val first = linkedMapOf(
            "zeta" to WireValue.Integer(42),
            "ratio" to WireValue.Decimal(1.5),
            "enabled" to WireValue.Flag(true),
            "alpha" to WireValue.Text("Pam"),
        )
        val second = linkedMapOf(
            "alpha" to WireValue.Text("Pam"),
            "enabled" to WireValue.Flag(true),
            "ratio" to WireValue.Decimal(1.5),
            "zeta" to WireValue.Integer(42),
        )
        assertEquals(expected, WireMap.encode(first).hex())
        assertEquals(WireMap.encode(first).toList(), WireMap.encode(second).toList())
    }

    private fun textBatch(length: Int): ByteBuffer =
        textBatch(ByteArray(length) { 'a'.code.toByte() })

    private fun textBatch(payload: ByteArray): ByteBuffer =
        batch(propertyCount = 1, payloadBytes = payload.size).apply {
            putShort(PropKey.TEXT.value.toShort())
            put(1.toByte())
            putInt(payload.size)
            put(payload)
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

    private fun decimalBatch(value: Double): ByteBuffer =
        batch(propertyCount = 1, payloadBytes = 11).apply {
            putShort(PropKey.WIDTH.value.toShort())
            put(3.toByte())
            putDouble(value)
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

    private fun ByteArray.hex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
